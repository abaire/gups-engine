package gups.script

import gups.core.HookRef
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.Mob
import gups.core.world.Action
import gups.core.world.Diagnostic
import gups.core.world.FastCommand
import gups.core.world.HookContext
import gups.core.world.HookName
import gups.core.world.HookOutcome
import gups.core.world.Lane
import gups.core.world.Proposal
import gups.core.world.ScriptHost
import gups.core.world.SkillResolver
import gups.core.world.ThinkFn
import gups.core.world.ThinkResult
import gups.core.world.TickCommand
import gups.core.world.World
import gups.core.world.ZoneCommand
import gups.core.world.findActor
import gups.core.world.opponentsOf
import gups.core.world.send
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.syntax.Location

/** Where a script call is charged: its package (for budget, errors and quarantine) and what it is. */
internal class Site(
    val pkg: String,
    val label: Label?,
    val hook: String,
    val vetoable: Boolean,
)

/** The top-level event a cascade of nested calls belongs to. */
internal class Root(val deadlineNanos: Long?)

/** One running script call. Its views and ctx are valid only while it runs. */
internal class Frame(
    val host: StarlarkHost,
    override val world: World,
    val thread: StarlarkThread,
    val site: Site,
    val root: Root,
    /** The absolute step count at which this call's thread must stop. */
    var maxSteps: Long,
) : ViewScope {
    var valid = true

    /** Set when a think hook enqueued an action for its own mob (SPEC §5.8). */
    var acted = false

    override val pkg: String get() = site.pkg

    override fun check() {
        if (!valid) fail("this ctx has expired: ctx and its views are only valid until the hook returns")
    }

    fun checkClock() {
        val deadline = root.deadlineNanos ?: return
        if (System.nanoTime() > deadline) {
            limitFail("wall_clock", "${site.hook} ran past the ${host.limits.hookWallNanos / 1_000_000} ms wall-clock limit")
        }
    }

    fun trace(line: String) = host.trace(line)
}

private sealed interface CallResult {
    data class Ok(val value: Any?) : CallResult

    data object Failed : CallResult

    data object Skipped : CallResult
}

/**
 * The Starlark script host (SPEC §2.2, §4.11): runs the hooks, timers, commands, `think` and
 * `resolve` functions of the packages in [scripts]. One host serves one world.
 *
 * Every top-level event (a hook fired by the engine with no script running) gets a fresh step
 * budget; hooks it triggers through verbs run on their own threads but share that budget
 * (SPEC §5.6, §6.1). A failing call is logged as a diagnostic and treated as returning None
 * (SPEC §6.2); too many failures quarantine its package (SPEC §6.3).
 */
class StarlarkHost(val scripts: LoadResult, val limits: ScriptLimits = ScriptLimits()) : ScriptHost {
    private val stack = ArrayList<Frame>()
    private val errorPulses = HashMap<String, ArrayDeque<Long>>()
    private val quarantinedSet = sortedSetOf<String>()

    /** Packages whose scripts are disabled. */
    val quarantined: Set<String> get() = quarantinedSet

    /** Receives one line per script call and verb while set (`@trace`, `w.trace`). */
    var tracer: ((String) -> Unit)? = null

    /** Instructor and owner notifications; wired to roles in M6 (D-068). */
    var alerts: (String) -> Unit = {}

    internal fun trace(line: String) {
        tracer?.invoke(line)
    }

    /** Registers the packages' global commands with [world]. Call once, before play starts. */
    fun install(world: World) {
        for (pkg in scripts.packages.values) {
            for (c in pkg.globalCommands) {
                world.commands.registerGlobal(
                    when (c.lane) {
                        Lane.FAST -> FastCommand(c.name) { call -> runCommand(call.world, c.name, c.fn, c.pkg, call.actor, call.args, call.actor) }
                        Lane.TICK -> TickCommand(c.name) { call ->
                            object : Action {
                                override fun validate(w: World, actor: Actor): String? = null

                                override fun resolve(w: World, actor: Actor): Int {
                                    runCommand(w, c.name, c.fn, c.pkg, actor, call.args, actor)
                                    return 0
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    // ---- Quarantine (SPEC §6.3) ----------------------------------------------------------------

    fun quarantine(world: World, pkg: String, why: String = "by request") {
        if (!quarantinedSet.add(pkg)) return
        val message = "package //$pkg quarantined $why: its hooks, think, commands and timers are disabled"
        world.record(Diagnostic(world.pulse, "quarantine", message, pkg = pkg, seed = world.config.seed))
        alerts(message)
    }

    fun unquarantine(pkg: String) {
        quarantinedSet.remove(pkg)
        errorPulses.remove(pkg)
    }

    private fun countError(world: World, pkg: String) {
        val times = errorPulses.getOrPut(pkg) { ArrayDeque() }
        times.addLast(world.pulse)
        while (times.isNotEmpty() && times.first() <= world.pulse - limits.quarantineWindowPulses) times.removeFirst()
        if (times.size > limits.quarantineErrors) {
            quarantine(world, pkg, "after ${times.size} errors in ${limits.quarantineWindowPulses} pulses")
        }
    }

    // ---- ScriptHost ----------------------------------------------------------------------------

    /** The package a call on [owner] is charged to: the owner's, or else the function's (D-065). */
    private fun chargeTo(owner: Entity, ref: HookRef): String =
        owner.protoLabel.pkg.takeIf { it in scripts.packages } ?: ref.file.pkg

    override fun invokeHook(world: World, owner: Entity, hook: HookName, ctx: HookContext): HookOutcome {
        val ref = scripts.hooks(owner.protoLabel)[hook] ?: return HookOutcome.Allow
        val site = Site(owner.protoLabel.pkg, owner.protoLabel, hook.scriptName, hook.vetoable)
        val extras = if (hook == HookName.ON_COMBAT_TICK && owner is Actor) CtxExtras(targets = world.opponentsOf(owner)) else CtxExtras()
        val result = call(world, site, ref, { Ctx(it, owner, ctx, extras) })
        val value = (result as? CallResult.Ok)?.value
        if (hook.vetoable && value is Denial) return HookOutcome.Deny(value.message)
        return HookOutcome.Allow
    }

    override fun invokeRef(world: World, owner: Entity, ref: HookRef, ctx: HookContext) {
        val pkg = chargeTo(owner, ref)
        val what = if (ctx.affect != null) "affect ${ctx.affect!!.name} ${ref.name}" else "timer ${ref.name}"
        val site = Site(pkg, owner.protoLabel.takeIf { it.pkg in scripts.packages }, what, vetoable = false)
        call(world, site, ref, { Ctx(it, owner, ctx, CtxExtras()) }) { f -> ctx.args.mapValues { fromAttr(it.value, f.thread.mutability()) } }
    }

    override fun isSuspended(owner: Entity, ref: HookRef): Boolean = chargeTo(owner, ref) in quarantinedSet

    // ---- Commands, think, resolve --------------------------------------------------------------

    /** A zone or global command. A failure (or quarantine) shows only "Nothing happens." (SPEC §6.2). */
    internal fun runCommand(world: World, verb: String, ref: HookRef, pkg: String, actor: Actor, args: String, owner: Entity) {
        val target = if (args.isBlank()) null else world.findActor(actor, args.trim().substringBefore(' '))
        val site = Site(pkg, owner.protoLabel.takeIf { it.pkg in scripts.packages }, "command '$verb'", vetoable = false)
        val hc = HookContext(actor = actor, target = target, item = owner as? Item)
        val extras = CtxExtras(commandArgs = args, commandTarget = target)
        when (call(world, site, ref, { Ctx(it, owner, hc, extras) })) {
            is CallResult.Ok -> Unit
            CallResult.Failed, CallResult.Skipped -> world.send(actor, "Nothing happens.")
        }
    }

    internal fun think(world: World, mob: Mob, proposals: List<Proposal>, ref: HookRef, pkg: String): ThinkResult {
        val site = Site(pkg, mob.protoLabel, "think", vetoable = false)
        var frame: Frame? = null
        val result = call(world, site, ref, { f -> Ctx(f, mob, HookContext(room = mob.room), CtxExtras(proposals = proposals)).also { frame = f } })
        val acted = frame?.acted == true
        return when {
            result !is CallResult.Ok -> ThinkResult.Accept
            acted -> ThinkResult.Idle
            result.value == "idle" -> ThinkResult.Idle
            else -> ThinkResult.Accept
        }
    }

    internal fun resolve(world: World, caster: Actor, target: Actor?, ref: HookRef, pkg: String) {
        val site = Site(pkg, null, "resolve ${ref.name}", vetoable = false)
        call(world, site, ref, { Ctx(it, caster, HookContext(actor = caster, target = target, room = caster.room), CtxExtras()) })
    }

    // ---- Calling Starlark ----------------------------------------------------------------------

    /**
     * Runs [ref] with a ctx built by [ctxOf] and keyword arguments from [kwargs]. Charges steps to
     * the current cascade, and reports failures. Engine (non-script) exceptions propagate.
     */
    private fun call(
        world: World,
        site: Site,
        ref: HookRef,
        ctxOf: (Frame) -> Ctx,
        kwargs: (Frame) -> Map<String, Any> = { emptyMap() },
    ): CallResult {
        if (site.pkg in quarantinedSet) return CallResult.Skipped
        val fn = scripts.function(ref) ?: run {
            report(world, site, "missing", "function ${ref.name} no longer exists in ${ref.file}", null, null)
            return CallResult.Failed
        }
        val remaining = if (stack.isEmpty()) limits.hookSteps else stack.minOf { it.maxSteps - it.thread.executedSteps }
        if (remaining <= 0) {
            report(world, site, "step_limit", "${site.hook} not run: the event's ${limits.hookSteps}-step budget is spent", null, null)
            return CallResult.Failed
        }
        val root = stack.lastOrNull()?.root ?: Root(if (limits.wallClock) System.nanoTime() + limits.hookWallNanos else null)
        Mutability.create(site.hook).use { mu ->
            val thread = StarlarkThread(mu, SEMANTICS)
            thread.setMaxExecutionSteps(remaining)
            thread.setPrintHandler { _, msg -> trace("  print: $msg") }
            val frame = Frame(this, world, thread, site, root, remaining)
            trace("${site.hook} on ${site.label ?: site.pkg} at pulse ${world.pulse}")
            stack += frame
            var failure: EvalException? = null
            var value: Any? = null
            var ctx: Ctx? = null
            try {
                ctx = ctxOf(frame)
                value = Starlark.call(thread, fn, listOf(ctx), kwargs(frame))
                checkValue(value, "${site.hook} return value", limits)
            } catch (e: EvalException) {
                failure = e
            } finally {
                frame.valid = false
                stack.removeAt(stack.size - 1)
                val used = thread.executedSteps
                for (outer in stack) {
                    outer.maxSteps -= used
                    outer.thread.setMaxExecutionSteps(outer.maxSteps)
                }
                trace("  ${site.hook} done: $used steps")
            }
            if (failure != null) {
                report(world, site, classify(failure), messageOf(failure), failure, ctx)
                return CallResult.Failed
            }
            return CallResult.Ok(value)
        }
    }

    private fun classify(e: EvalException): String {
        (e.cause as? LimitExceeded)?.let { return it.kind }
        if (isStepLimit(e)) return "step_limit"
        return "script_error"
    }

    private fun messageOf(e: EvalException): String =
        if (isStepLimit(e)) "exceeded the ${limits.hookSteps}-step limit for one event" else e.message ?: e.toString()

    private fun report(world: World, site: Site, kind: String, message: String, e: EvalException?, ctx: Ctx?) {
        val loc = e?.let(::innermost)
        val d = Diagnostic(
            pulse = world.pulse,
            kind = kind,
            message = "${site.hook}: $message",
            pkg = site.pkg,
            label = site.label,
            hook = site.hook,
            file = loc?.file(),
            line = loc?.line(),
            stack = e?.let(::formatStack),
            ctx = ctx?.summary(),
            seed = world.config.seed,
        )
        world.record(d)
        trace("  ERROR ${d.file ?: ""}${d.line?.let { ":$it" } ?: ""}: ${d.message}")
        countError(world, site.pkg)
    }
}

/** The Starlark stack, innermost call last, one `at name (file:line:col)` per frame. */
internal fun formatStack(e: EvalException): String = e.callStack.joinToString("\n") { entry ->
    val loc = entry.location
    "  at ${entry.name}" + if (loc == Location.BUILTIN) " (builtin)" else " (${loc.file()}:${loc.line()}:${loc.column()})"
}

private fun hostOf(world: World): StarlarkHost =
    world.scripts as? StarlarkHost ?: error("Starlark prototypes need a StarlarkHost; this world has ${world.scripts}")

/** A room or item `commands[verb]` entry (SPEC §4.4). */
internal class ScriptCommand(private val verb: String, private val ref: HookRef, private val pkg: String) : ZoneCommand {
    override fun run(world: World, actor: Actor, args: String, owner: Entity) =
        hostOf(world).runCommand(world, verb, ref, pkg, actor, args, owner)
}

/** A mob's `think` hook (SPEC §4.8). */
internal class ScriptThink(private val ref: HookRef, private val pkg: String) : ThinkFn {
    override fun think(world: World, mob: Mob, proposals: List<Proposal>): ThinkResult =
        hostOf(world).think(world, mob, proposals, ref, pkg)
}

/** A skill's custom `resolve(ctx)` (SPEC §4.3). */
internal class ScriptResolve(private val ref: HookRef, private val pkg: String) : SkillResolver {
    override fun resolve(world: World, caster: Actor, target: Actor?) = hostOf(world).resolve(world, caster, target, ref, pkg)
}
