package gups.script

import gups.core.EntityId
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
import gups.core.world.ReloadResult
import gups.core.world.ScriptHost
import gups.core.world.SkillResolver
import gups.core.world.ThinkFn
import gups.core.world.ThinkResult
import gups.core.world.TickCommand
import gups.core.world.World
import gups.core.world.ZoneCommand
import gups.core.world.applyPrototypes
import gups.core.world.findActor
import gups.core.world.opponentsOf
import gups.core.world.send
import net.starlark.java.eval.Dict
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Module
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.syntax.Location
import net.starlark.java.syntax.ParserInput
import net.starlark.java.syntax.SyntaxError

/** Where a script call is charged: its package (for budget, errors and quarantine) and what it is. */
internal class Site(
    val pkg: String,
    val label: Label?,
    val hook: String,
    val vetoable: Boolean,
    /** The entity the call runs for, if any. */
    val owner: EntityId?,
)

/** The top-level event a cascade of nested calls belongs to. */
internal class Root(val deadlineNanos: Long?, val limitMs: Long)

/** One line of script tracing (SPEC §5.13): a call, a verb it ran, its step count, or an error. */
data class TraceEvent(
    val pulse: Long,
    /** The package the call is charged to. */
    val pkg: String,
    val owner: EntityId?,
    /** The hook, command or timer, for example `on_enter` or `command 'pull'`. */
    val hook: String,
    val text: String,
)

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
            limitFail("wall_clock", "${site.hook} ran past the ${root.limitMs} ms wall-clock limit")
        }
    }

    fun trace(line: String) = host.trace(site, world.pulse, line)
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
class StarlarkHost(
    scripts: LoadResult,
    val limits: ScriptLimits = ScriptLimits(),
    /** Loads replacement packages for [reload]; without one, reload is refused. */
    private val loader: ScriptLoader? = null,
) : ScriptHost {
    /** The packages currently live; a successful reload replaces it. */
    var scripts: LoadResult = scripts
        private set

    private val stack = ArrayList<Frame>()
    private val errorPulses = HashMap<String, ArrayDeque<Long>>()
    private val quarantinedSet = sortedSetOf<String>()

    /** Packages whose scripts are disabled. */
    val quarantined: Set<String> get() = quarantinedSet

    override val quarantinedPackages: Set<String> get() = quarantinedSet

    /** Receives one event per script call, verb and error while set (`@trace`, `w.trace`). */
    var tracer: ((TraceEvent) -> Unit)? = null

    /** Instructor and owner notifications; wired to roles in M6 (D-068). */
    var alerts: (String) -> Unit = {}

    internal fun trace(site: Site, pulse: Long, text: String) {
        tracer?.invoke(TraceEvent(pulse, site.pkg, site.owner, site.hook, text))
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

    override fun quarantine(world: World, pkg: String, why: String) {
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
        val site = Site(owner.protoLabel.pkg, owner.protoLabel, hook.scriptName, hook.vetoable, owner.id)
        val extras = if (hook == HookName.ON_COMBAT_TICK && owner is Actor) CtxExtras(targets = world.opponentsOf(owner)) else CtxExtras()
        val result = call(world, site, ref, { Ctx(it, owner, ctx, extras) })
        val value = (result as? CallResult.Ok)?.value
        if (hook.vetoable && value is Denial) return HookOutcome.Deny(value.message)
        return HookOutcome.Allow
    }

    override fun invokeRef(world: World, owner: Entity, ref: HookRef, ctx: HookContext) {
        val pkg = chargeTo(owner, ref)
        val what = if (ctx.affect != null) "affect ${ctx.affect!!.name} ${ref.name}" else "timer ${ref.name}"
        val site = Site(pkg, owner.protoLabel.takeIf { it.pkg in scripts.packages }, what, vetoable = false, owner.id)
        call(world, site, ref, { Ctx(it, owner, ctx, CtxExtras()) }, kwargs = { f -> ctx.args.mapValues { fromAttr(it.value, f.thread.mutability()) } })
    }

    override fun isSuspended(owner: Entity, ref: HookRef): Boolean = chargeTo(owner, ref) in quarantinedSet

    // ---- Commands, think, resolve --------------------------------------------------------------

    /** A zone or global command. A failure (or quarantine) shows only "Nothing happens." (SPEC §6.2). */
    internal fun runCommand(world: World, verb: String, ref: HookRef, pkg: String, actor: Actor, args: String, owner: Entity) {
        val target = if (args.isBlank()) null else world.findActor(actor, args.trim().substringBefore(' '))
        val site = Site(pkg, owner.protoLabel.takeIf { it.pkg in scripts.packages }, "command '$verb'", vetoable = false, owner.id)
        val hc = HookContext(actor = actor, target = target, item = owner as? Item)
        val extras = CtxExtras(commandArgs = args, commandTarget = target)
        when (call(world, site, ref, { Ctx(it, owner, hc, extras) })) {
            is CallResult.Ok -> Unit
            CallResult.Failed, CallResult.Skipped -> world.send(actor, "Nothing happens.")
        }
    }

    internal fun think(world: World, mob: Mob, proposals: List<Proposal>, ref: HookRef, pkg: String): ThinkResult {
        val site = Site(pkg, mob.protoLabel, "think", vetoable = false, mob.id)
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
        val site = Site(pkg, null, "resolve ${ref.name}", vetoable = false, caster.id)
        call(world, site, ref, { Ctx(it, caster, HookContext(actor = caster, target = target, room = caster.room), CtxExtras()) })
    }

    // ---- Reload (SPEC §5.10) ---------------------------------------------------------------------

    /**
     * Re-evaluates the live packages plus [packages] from disk, all together, so every label
     * still resolves (D-081). Any error keeps the old version live. On success the prototype
     * table, hooks and global commands are swapped, live instances are reconciled, timers whose
     * function is gone are dropped, and the reloaded packages leave quarantine.
     */
    override fun reload(world: World, packages: List<String>): ReloadResult {
        val loader = loader ?: return ReloadResult(false, listOf("Reload needs the world directory; this host has none."))
        val dir = loader.dir
        val wanted = (scripts.packages.keys + packages).filter(dir::hasPackage).distinct().sorted()
        val gone = packages.filter { !dir.hasPackage(it) && it in scripts.packages }
        val next = loader.load(wanted)
        val named = packages.joinToString(", ") { "//$it" }
        if (!next.ok) {
            return ReloadResult(false, listOf("Reload of $named failed; the old version stays live.") + next.errors.map { it.render() })
        }
        if (next.prototypes().rooms.isEmpty()) return ReloadResult(false, listOf("Reload of $named failed: the world would have no rooms."))
        scripts = next
        val report = world.applyPrototypes(next.prototypes())
        world.commands.clearGlobals()
        install(world)
        val notes = report.notes.toMutableList()
        for (e in world.allEntities) {
            val dead = e.timers.filter { next.function(it.fn) == null }
            for (t in dead) {
                e.timers.remove(t)
                e.dirty = true
                val message = "timer ${t.fn.name} on $e dropped: ${t.fn.file} no longer defines it"
                world.record(Diagnostic(world.pulse, "reload", message, pkg = t.fn.file.pkg, seed = world.config.seed))
                notes += message
            }
        }
        packages.forEach(::unquarantine)
        for (room in report.created.sortedBy { it.id }) invokeHook(world, room, HookName.ON_LOAD, HookContext(room = room))
        val head = "Reloaded $named." + if (gone.isNotEmpty()) " Removed ${gone.joinToString(", ") { "//$it" }}." else ""
        return ReloadResult(true, listOf(head) + notes)
    }

    // ---- migrate (SPEC §3.4) --------------------------------------------------------------------

    /**
     * Calls the package's `migrate(old_version, attrs)` with [entity]'s own attributes by short
     * name, and stores the dict it returns in their place. Other packages' keys are untouched.
     * A failing migrate leaves the attributes as they were and logs a diagnostic (D-094).
     */
    override fun migrate(world: World, entity: Entity, fromVersion: Int) {
        val pkg = entity.protoLabel.pkg
        val ref = scripts.packages[pkg]?.migrate ?: return
        val ns = scripts.packages.getValue(pkg).namespace
        val site = Site(pkg, entity.protoLabel, "migrate", vetoable = false, entity.id)
        val own = entity.customAttributes.filterKeys { it.startsWith("$ns:") }
        val result = call(world, site, ref, { f -> Ctx(f, entity, HookContext(), CtxExtras()) }, positional = { f ->
            val attrs = own.entries.associate { it.key.removePrefix("$ns:") to fromAttr(it.value, f.thread.mutability()) }
            listOf(sInt(fromVersion), Dict.copyOf(f.thread.mutability(), attrs))
        })
        val value = (result as? CallResult.Ok)?.value ?: return
        val next = try {
            dict(value, "migrate() return value").mapValues { (k, v) -> toAttr(v, "migrate()[\"$k\"]", limits) }
        } catch (e: EvalException) {
            report(world, site, "script_error", "migrate must return a dict of storable values: ${e.message}", e, null)
            return
        }
        for (k in own.keys) entity.customAttributes.remove(k)
        for ((k, v) in next) if (v != null) entity.customAttributes["$ns:$k"] = v
        entity.dirty = true
    }

    // ---- @eval (SPEC §5.13) --------------------------------------------------------------------

    /**
     * Evaluates [code] with a live ctx bound to [actor]: `ctx`, `self` and `room` are
     * predeclared and verbs change the live world. Relative labels and attribute keys belong to
     * the package of the actor's room (D-085). Returns what to show the caller.
     */
    fun eval(world: World, actor: Actor, code: String): String {
        val pkg = actor.room.label.pkg
        val site = Site(pkg, null, "@eval", vetoable = false, actor.id)
        val root = Root(if (limits.wallClock) System.nanoTime() + limits.evalWallNanos else null, limits.evalWallNanos / 1_000_000)
        Mutability.create("@eval").use { mu ->
            val thread = StarlarkThread(mu, SEMANTICS)
            thread.setMaxExecutionSteps(limits.evalSteps)
            val printed = StringBuilder()
            thread.setPrintHandler { _, msg -> printed.append(msg).append('\n') }
            val frame = Frame(this, world, thread, site, root, limits.evalSteps)
            val ctx = Ctx(frame, actor, HookContext(actor = actor, room = actor.room), CtxExtras())
            val module = Module.withPredeclared(SEMANTICS, PACKAGE_GLOBALS + mapOf("ctx" to ctx, "self" to ctx.self(), "room" to ctx.room()))
            module.clientData = FileInfo(Label("//$pkg:eval.star"), pkg)
            stack += frame
            val result = try {
                val value = try {
                    Starlark.eval(ParserInput.fromString(code, "@eval"), FILE_OPTIONS, module, thread)
                } catch (_: SyntaxError.Exception) {
                    Starlark.execFile(ParserInput.fromString(code, "@eval"), FILE_OPTIONS, module, thread)
                    Starlark.NONE
                }
                if (value === Starlark.NONE) "" else Starlark.repr(value)
            } catch (e: SyntaxError.Exception) {
                "Syntax error: " + e.errors().joinToString("; ") { it.message() }
            } catch (e: EvalException) {
                "Error: " + if (isStepLimit(e)) "exceeded the ${limits.evalSteps}-step limit" else e.message
            } finally {
                frame.valid = false
                stack.removeAt(stack.size - 1)
            }
            return (printed.toString() + result).trimEnd()
        }
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
        /** Arguments in place of the ctx, for `migrate`; the ctx is still made for verbs' sake. */
        positional: ((Frame) -> List<Any>)? = null,
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
        val root = stack.lastOrNull()?.root ?: Root(if (limits.wallClock) System.nanoTime() + limits.hookWallNanos else null, limits.hookWallNanos / 1_000_000)
        Mutability.create(site.hook).use { mu ->
            val thread = StarlarkThread(mu, SEMANTICS)
            thread.setMaxExecutionSteps(remaining)
            thread.setPrintHandler { _, msg -> trace(site, world.pulse, "  print: $msg") }
            val frame = Frame(this, world, thread, site, root, remaining)
            trace(site, world.pulse, "${site.hook} on ${site.label ?: site.pkg} at pulse ${world.pulse}")
            stack += frame
            var failure: EvalException? = null
            var value: Any? = null
            var ctx: Ctx? = null
            try {
                ctx = ctxOf(frame)
                value = Starlark.call(thread, fn, positional?.invoke(frame) ?: listOf(ctx), kwargs(frame))
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
                trace(site, world.pulse, "  ${site.hook} done: $used steps")
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
            room = site.owner?.let(world::entity)?.let { world.roomOfEntity(it) }?.label,
        )
        world.record(d)
        trace(site, world.pulse, "  ERROR ${d.file ?: ""}${d.line?.let { ":$it" } ?: ""}: ${d.message}")
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
