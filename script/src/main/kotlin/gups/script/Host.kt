package gups.script

import gups.core.EntityId
import gups.core.HookRef
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.Mob
import gups.core.model.ModifierPoint
import gups.core.rules.RulesContext
import gups.core.world.Action
import gups.core.world.Diagnostic
import gups.core.world.FastCommand
import gups.core.world.Feature
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
import gups.core.world.refreshAllOwnedBehaviors
import gups.core.world.roomOfEntity
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

/**
 * Where a script call is charged: its package (for budget, errors and quarantine) and what it is.
 */
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

/** One line of script tracing: a call, a verb it ran, its step count, or an error. */
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

  /** Set when a think hook enqueued an action for its own mob. */
  var acted = false

  override val pkg: String
    get() = site.pkg

  override fun check() {
    if (!valid)
      fail("this ctx has expired: ctx and its views are only valid until the hook returns")
  }

  fun checkClock() {
    val deadline = root.deadlineNanos ?: return
    if (System.nanoTime() > deadline) {
      limitFail("wall_clock", "${site.hook} ran past the ${root.limitMs} ms wall-clock limit")
    }
  }

  fun trace(line: String) = host.trace(site, world.pulse, line)
}

internal sealed interface CallResult {
  data class Ok(val value: Any?) : CallResult

  data class Failed(val message: String) : CallResult

  data object Skipped : CallResult
}

/**
 * The Starlark script host: runs the hooks, timers, commands, `think` and `resolve` functions of
 * the packages in [scripts]. One host serves one world.
 *
 * Every top-level event (a hook fired by the engine with no script running) gets a fresh step
 * budget; hooks it triggers through verbs run on their own threads but share that budget. A failing
 * call is logged as a diagnostic and treated as returning None; too many failures quarantine its
 * package.
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
  val quarantined: Set<String>
    get() = quarantinedSet

  /** Packages that failed to load, so a rules module can say why it falls back. */
  val failedPackages: Set<String>
    get() = scripts.failed

  /** The package whose scripts are never quarantined: the rules have no fallback. */
  private var rulesPackage = "rules"

  /** The Starlark rules module of package [pkg]. */
  fun rules(pkg: String = "rules"): StarlarkRules {
    rulesPackage = pkg
    return StarlarkRules(this, pkg)
  }

  override val quarantinedPackages: Set<String>
    get() = quarantinedSet

  /** Receives one event per script call, verb and error while set (`@trace`, `w.trace`). */
  var tracer: ((TraceEvent) -> Unit)? = null

  /** Instructor and owner notifications: `(kind, message)`, rate-limited per kind. */
  var alerts: (String, String) -> Unit = { _, _ -> }

  internal fun trace(site: Site, pulse: Long, text: String) {
    tracer?.invoke(TraceEvent(pulse, site.pkg, site.owner, site.hook, text))
  }

  /**
   * Registers the packages' global commands with [world], and binds its rules module. Call once,
   * before play starts.
   */
  fun install(world: World) {
    (world.rules as? StarlarkRules)?.world = world
    world.quarantineWindowPulses = limits.quarantineWindowPulses
    for (pkg in scripts.packages.values) {
      for (c in pkg.globalCommands) {
        if (world.commands.isPluginCommand(c.name)) {
          world.record(
            Diagnostic(
              world.pulse,
              "load",
              "global_command '${c.name}' clashes with a plugin command; skipped",
              pkg = pkg.path,
            )
          )
          continue
        }
        world.commands.registerGlobal(
          when (c.lane) {
            Lane.FAST ->
              FastCommand(c.name) { call ->
                runCommand(call.world, c.name, c.fn, c.pkg, call.actor, call.args, call.actor)
              }
            Lane.TICK ->
              TickCommand(c.name) { call ->
                object : Action {
                  override fun validate(w: World, actor: Actor): String? = null

                  override fun resolve(w: World, actor: Actor): Int {
                    runCommand(w, c.name, c.fn, c.pkg, actor, call.args, actor)
                    return 0
                  }
                }
              }
          }
        )
      }
    }
  }

  // ---- Quarantine ----------------------------------------------------------------------------

  override fun quarantine(world: World, pkg: String, why: String) {
    if (!quarantinedSet.add(pkg)) return
    val message =
      "package //$pkg quarantined $why: its hooks, think, commands and timers are disabled"
    world.record(
      Diagnostic(world.pulse, "quarantine", message, pkg = pkg, seed = world.config.seed)
    )
    alerts("quarantine", message)
  }

  override fun unquarantine(world: World, pkg: String) = unquarantine(pkg)

  fun unquarantine(pkg: String) {
    quarantinedSet.remove(pkg)
    errorPulses.remove(pkg)
  }

  override val packages: Set<String>
    get() = scripts.packages.keys

  private val steps = HashMap<String, Long>()

  override val stepsByPackage: Map<String, Long>
    get() = HashMap(steps)

  private fun countError(world: World, pkg: String) {
    if (pkg == rulesPackage) return
    val times = errorPulses.getOrPut(pkg) { ArrayDeque() }
    times.addLast(world.pulse)
    while (times.isNotEmpty() && times.first() <= world.pulse - limits.quarantineWindowPulses) times
      .removeFirst()
    if (times.size > limits.quarantineErrors) {
      quarantine(
        world,
        pkg,
        "after ${times.size} errors in ${limits.quarantineWindowPulses} pulses",
      )
    }
  }

  // ---- ScriptHost ----------------------------------------------------------------------------

  /** The package a call on [owner] is charged to: the owner's, or else the function's. */
  private fun chargeTo(owner: Entity, ref: HookRef): String =
    owner.protoLabel.pkg.takeIf { it in scripts.packages } ?: ref.file.pkg

  override fun invokeHook(
    world: World,
    owner: Entity,
    hook: HookName,
    ctx: HookContext,
  ): HookOutcome {
    val ref = scripts.hooks(owner.protoLabel)[hook] ?: return HookOutcome.Allow
    val site =
      Site(owner.protoLabel.pkg, owner.protoLabel, hook.scriptName, hook.vetoable, owner.id)
    val extras =
      if (hook == HookName.ON_COMBAT_TICK && (ctx.actor ?: owner) is Actor)
        CtxExtras(targets = world.opponentsOf(ctx.actor ?: owner as Actor))
      else CtxExtras()
    // on_wake(ctx, elapsed_pulses) takes the elapsed time as a second argument.
    val positional: ((Frame) -> List<Any>)? =
      if (hook == HookName.ON_WAKE) {
        { f -> listOf(Ctx(f, owner, ctx, extras), sInt(ctx.elapsedPulses ?: 0)) }
      } else {
        null
      }
    val result = call(world, site, ref, { Ctx(it, owner, ctx, extras) }, positional = positional)
    val value = (result as? CallResult.Ok)?.value
    if (hook.vetoable && value is Denial) return HookOutcome.Deny(value.message)
    return HookOutcome.Allow
  }

  override fun invokeRef(world: World, owner: Entity, ref: HookRef, ctx: HookContext) {
    val pkg = chargeTo(owner, ref)
    val what =
      if (ctx.affect != null) "affect ${ctx.affect!!.name} ${ref.name}" else "timer ${ref.name}"
    val site =
      Site(
        pkg,
        owner.protoLabel.takeIf { it.pkg in scripts.packages },
        what,
        vetoable = false,
        owner.id,
      )
    call(
      world,
      site,
      ref,
      { Ctx(it, owner, ctx, CtxExtras()) },
      kwargs = { f -> ctx.args.mapValues { fromAttr(it.value, f.thread.mutability()) } },
    )
  }

  override fun invokeCustom(
    world: World,
    owner: Entity,
    name: String,
    args: Map<String, Any?>,
  ): Any? {
    val ref = scripts.customHooks(owner.protoLabel)[name] ?: return null
    val site =
      Site(owner.protoLabel.pkg, owner.protoLabel, "hook $name", vetoable = false, owner.id)
    val result =
      call(
        world,
        site,
        ref,
        { Ctx(it, owner, HookContext(), CtxExtras()) },
        kwargs = { f -> args.mapValues { fromPlain(it.value, f.thread.mutability(), f) } },
      )
    val value = (result as? CallResult.Ok)?.value ?: return null
    return try {
      toPlain(value, "hook $name return value", limits)
    } catch (e: EvalException) {
      report(world, site, classify(e), e.message ?: e.toString(), e, null)
      null
    }
  }

  override fun isSuspended(owner: Entity, ref: HookRef): Boolean =
    chargeTo(owner, ref) in quarantinedSet

  override fun hasHook(owner: Entity, hook: HookName): Boolean =
    hook in scripts.hooks(owner.protoLabel)

  override fun invokeGlobal(world: World, hook: HookName, subject: Actor, ctx: HookContext) {
    if (hook == HookName.ON_LEVEL_CHANGE && Feature.LEVELING !in world.features) return
    for (pkg in scripts.packages.values.sortedBy { it.path }) {
      for (decl in pkg.globalHooks) {
        if (decl.hook != hook.scriptName) continue
        val site =
          Site(decl.pkg, null, "global_hook '${hook.scriptName}'", vetoable = false, subject.id)
        call(world, site, decl.fn, { Ctx(it, subject, ctx, CtxExtras()) })
      }
    }
  }

  override fun modify(
    world: World,
    point: ModifierPoint,
    ref: HookRef,
    source: String,
    c: RulesContext,
    base: Any?,
  ): Any? = runModifier(world, point, ref, source, c, base)

  // ---- Commands, think, resolve --------------------------------------------------------------

  /** A zone or global command. A failure (or quarantine) shows only "Nothing happens." */
  internal fun runCommand(
    world: World,
    verb: String,
    ref: HookRef,
    pkg: String,
    actor: Actor,
    args: String,
    owner: Entity,
  ) {
    val target =
      if (args.isBlank()) null else world.findActor(actor, args.trim().substringBefore(' '))
    val site =
      Site(
        pkg,
        owner.protoLabel.takeIf { it.pkg in scripts.packages },
        "command '$verb'",
        vetoable = false,
        owner.id,
      )
    val hc = HookContext(actor = actor, target = target, item = owner as? Item)
    val extras = CtxExtras(commandArgs = args, commandTarget = target)
    when (call(world, site, ref, { Ctx(it, owner, hc, extras) })) {
      is CallResult.Ok -> Unit
      is CallResult.Failed,
      CallResult.Skipped -> world.send(actor, "Nothing happens.")
    }
  }

  internal fun think(
    world: World,
    mob: Mob,
    proposals: List<Proposal>,
    ref: HookRef,
    pkg: String,
  ): ThinkResult {
    val site = Site(pkg, mob.protoLabel, "think", vetoable = false, mob.id)
    var frame: Frame? = null
    val result =
      call(
        world,
        site,
        ref,
        { f ->
          Ctx(f, mob, HookContext(room = mob.room), CtxExtras(proposals = proposals)).also {
            frame = f
          }
        },
      )
    val acted = frame?.acted == true
    return when {
      result !is CallResult.Ok -> ThinkResult.Accept
      acted -> ThinkResult.Idle
      result.value == "idle" -> ThinkResult.Idle
      else -> ThinkResult.Accept
    }
  }

  internal fun resolve(
    world: World,
    caster: Actor,
    target: Actor?,
    item: Item?,
    power: Int,
    ref: HookRef,
    pkg: String,
  ) {
    val site = Site(pkg, null, "resolve ${ref.name}", vetoable = false, caster.id)
    call(
      world,
      site,
      ref,
      {
        Ctx(
          it,
          caster,
          HookContext(actor = caster, target = target, targetItem = item, room = caster.room),
          CtxExtras(power = power),
        )
      },
    )
  }

  // ---- Reload -----------------------------------------------------------------------------------

  /**
   * Re-evaluates the live packages plus [packages] from disk, all together, so every label still
   * resolves. Any error keeps the old version live. On success the prototype table, hooks and
   * global commands are swapped, live instances are reconciled, timers whose function is gone are
   * dropped, and the reloaded packages leave quarantine.
   */
  override fun reload(world: World, packages: List<String>): ReloadResult {
    val loader =
      loader
        ?: return ReloadResult(
          false,
          listOf("Reload needs the world directory; this host has none."),
        )
    val dir = loader.dir
    val wanted = (scripts.packages.keys + packages).filter(dir::hasPackage).distinct().sorted()
    val gone = packages.filter { !dir.hasPackage(it) && it in scripts.packages }
    val next = loader.load(wanted)
    val named = packages.joinToString(", ") { "//$it" }
    if (!next.ok) {
      return ReloadResult(
        false,
        listOf("Reload of $named failed; the old version stays live.") +
          next.errors.map { it.render() },
      )
    }
    if (next.prototypes().rooms.isEmpty())
      return ReloadResult(false, listOf("Reload of $named failed: the world would have no rooms."))
    val notes = swapIn(world, next, packages)
    val head =
      "Reloaded $named." +
        if (gone.isNotEmpty()) " Removed ${gone.joinToString(", ") { "//$it" }}." else ""
    return ReloadResult(true, listOf(head) + notes)
  }

  /**
   * Loads every package of [dir], a deploy's staging tree, and checks it as one batch. It touches
   * no world, so it runs off the world thread.
   */
  fun stage(dir: WorldDir): StagedWorld {
    val packages = dir.packages()
    val next =
      ScriptLoader(
          dir,
          limits,
          pluginGlobals = loader?.pluginGlobals.orEmpty(),
          knownGmcp = loader?.knownGmcp ?: { emptySet() },
        )
        .load(packages)
    val errors =
      when {
        !next.ok -> next.errors.map { it.render() }
        next.prototypes().rooms.isEmpty() -> listOf("The world would have no rooms.")
        else -> emptyList()
      }
    return StagedWorld(next, packages, errors)
  }

  /**
   * Swaps a checked [staged] world in on the world thread, as a reload does, and clears the
   * quarantine of the [changed] packages.
   */
  fun swap(world: World, staged: StagedWorld, changed: List<String>): ReloadResult {
    if (!staged.ok) return ReloadResult(false, staged.errors)
    val notes = swapIn(world, staged.result, changed)
    return ReloadResult(true, listOf("Deployed ${staged.packages.size} packages.") + notes)
  }

  /**
   * Swaps [next] in: prototypes, global commands, reconciliation, dead timers, quarantine and new
   * rooms' `on_load`.
   */
  private fun swapIn(world: World, next: LoadResult, cleared: List<String>): List<String> {
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
        world.record(
          Diagnostic(world.pulse, "reload", message, pkg = t.fn.file.pkg, seed = world.config.seed)
        )
        notes += message
      }
    }
    cleared.forEach(::unquarantine)
    world.refreshAllOwnedBehaviors()
    for (room in report.created.sortedBy { it.id }) invokeHook(
      world,
      room,
      HookName.ON_LOAD,
      HookContext(room = room),
    )
    return notes
  }

  // ---- migrate ----------------------------------------------------------------------------------

  /**
   * Calls the package's `migrate(old_version, attrs)` with [entity]'s own attributes by short name,
   * and stores the dict it returns in their place. Other packages' keys are untouched. A failing
   * migrate leaves the attributes as they were and logs a diagnostic.
   */
  override fun migrate(world: World, entity: Entity, fromVersion: Int) {
    val pkg = entity.protoLabel.pkg
    val ref = scripts.packages[pkg]?.migrate ?: return
    val ns = scripts.packages.getValue(pkg).namespace
    val site = Site(pkg, entity.protoLabel, "migrate", vetoable = false, entity.id)
    val own = entity.customAttributes.filterKeys { it.startsWith("$ns:") }
    val result =
      call(
        world,
        site,
        ref,
        { f -> Ctx(f, entity, HookContext(), CtxExtras()) },
        positional = { f ->
          val attrs =
            own.entries.associate {
              it.key.removePrefix("$ns:") to fromAttr(it.value, f.thread.mutability())
            }
          listOf(sInt(fromVersion), Dict.copyOf(f.thread.mutability(), attrs))
        },
      )
    val value = (result as? CallResult.Ok)?.value ?: return
    val next =
      try {
        dict(value, "migrate() return value").mapValues { (k, v) ->
          toAttr(v, "migrate()[\"$k\"]", limits)
        }
      } catch (e: EvalException) {
        report(
          world,
          site,
          "script_error",
          "migrate must return a dict of storable values: ${e.message}",
          e,
          null,
        )
        return
      }
    for (k in own.keys) entity.customAttributes.remove(k)
    for ((k, v) in next) if (v != null) entity.customAttributes["$ns:$k"] = v
    entity.dirty = true
  }

  // ---- @eval ----------------------------------------------------------------------------------

  /**
   * Evaluates [code] with a live ctx bound to [actor]: `ctx`, `self` and `room` are predeclared and
   * verbs change the live world. Relative labels and attribute keys belong to the package of the
   * actor's room. Returns what to show the caller.
   */
  fun eval(world: World, actor: Actor, code: String): String {
    val pkg = actor.room.label.pkg
    val site = Site(pkg, null, "@eval", vetoable = false, actor.id)
    val root =
      Root(
        if (limits.wallClock) System.nanoTime() + limits.evalWallNanos else null,
        limits.evalWallNanos / 1_000_000,
      )
    Mutability.create("@eval").use { mu ->
      val thread = StarlarkThread(mu, SEMANTICS)
      thread.setMaxExecutionSteps(limits.evalSteps)
      val printed = StringBuilder()
      thread.setPrintHandler { _, msg -> printed.append(msg).append('\n') }
      val frame = Frame(this, world, thread, site, root, limits.evalSteps)
      thread.setThreadLocal(ViewScope::class.java, frame)
      val ctx = Ctx(frame, actor, HookContext(actor = actor, room = actor.room), CtxExtras())
      val module =
        Module.withPredeclared(
          SEMANTICS,
          (loader?.globals ?: packageGlobals(world.schema)) +
            mapOf("ctx" to ctx, "self" to ctx.self(), "room" to ctx.room()),
        )
      module.clientData = FileInfo(Label("//$pkg:eval.star"), pkg)
      stack += frame
      val result =
        try {
          val value =
            try {
              Starlark.eval(ParserInput.fromString(code, "@eval"), FILE_OPTIONS, module, thread)
            } catch (_: SyntaxError.Exception) {
              Starlark.execFile(ParserInput.fromString(code, "@eval"), FILE_OPTIONS, module, thread)
              Starlark.NONE
            }
          if (value === Starlark.NONE) "" else Starlark.repr(value)
        } catch (e: SyntaxError.Exception) {
          "Syntax error: " + e.errors().joinToString("; ") { it.message() }
        } catch (e: EvalException) {
          "Error: " +
            if (isStepLimit(e)) "exceeded the ${limits.evalSteps}-step limit" else e.message
        } finally {
          frame.valid = false
          stack.removeAt(stack.size - 1)
        }
      return (printed.toString() + result).trimEnd()
    }
  }

  // ---- Calling Starlark ----------------------------------------------------------------------

  /** `ctx.call_hook` depth: more than 8 nested calls is a script error. */
  private var customDepth = 0

  /** Enters one `ctx.call_hook` level; pair every successful call with [endCustomHook]. */
  internal fun beginCustomHook() {
    if (customDepth >= 8) fail("call_hook is nested too deeply")
    customDepth++
  }

  internal fun endCustomHook() {
    customDepth--
  }

  /**
   * Runs [ref] with a ctx built by [ctxOf] and keyword arguments from [kwargs]. Charges steps to
   * the current cascade, and reports failures. Engine (non-script) exceptions propagate.
   *
   * A call with its own [budget] (a rules evaluation) gets that many steps and the rules wall
   * clock, whatever is running around it, and isn't charged to the cascade.
   */
  internal fun call(
    world: World,
    site: Site,
    ref: HookRef,
    ctxOf: (Frame) -> Any,
    kwargs: (Frame) -> Map<String, Any> = { emptyMap() },
    /** Arguments in place of the ctx, for `migrate` and rules functions; the ctx is still made. */
    positional: ((Frame) -> List<Any>)? = null,
    budget: Long? = null,
    /** False to leave a failure to the caller, who gets its message in [CallResult.Failed]. */
    recordErrors: Boolean = true,
    /** Runs on the new thread before the call, to attach thread-locals. */
    onThread: (StarlarkThread) -> Unit = {},
  ): CallResult {
    if (site.pkg in quarantinedSet) return CallResult.Skipped
    val fn =
      scripts.function(ref)
        ?: run {
          val message = "function ${ref.name} no longer exists in ${ref.file}"
          if (!recordErrors) return CallResult.Failed(message)
          report(world, site, "missing", message, null, null)
          return CallResult.Failed(message)
        }
    val limit = budget ?: limits.hookSteps
    val remaining =
      budget
        ?: if (stack.isEmpty()) limits.hookSteps
        else stack.minOf { it.maxSteps - it.thread.executedSteps }
    if (remaining <= 0) {
      val message = "${site.hook} not run: the event's ${limits.hookSteps}-step budget is spent"
      if (recordErrors) report(world, site, "step_limit", message, null, null)
      return CallResult.Failed(message)
    }
    val root =
      if (budget != null) {
        Root(
          if (limits.wallClock) System.nanoTime() + limits.rulesWallNanos else null,
          limits.rulesWallNanos / 1_000_000,
        )
      } else {
        stack.lastOrNull()?.root
          ?: Root(
            if (limits.wallClock) System.nanoTime() + limits.hookWallNanos else null,
            limits.hookWallNanos / 1_000_000,
          )
      }
    Mutability.create(site.hook).use { mu ->
      val thread = StarlarkThread(mu, SEMANTICS)
      thread.setMaxExecutionSteps(remaining)
      thread.setPrintHandler { _, msg -> trace(site, world.pulse, "  print: $msg") }
      val frame = Frame(this, world, thread, site, root, remaining)
      thread.setThreadLocal(ViewScope::class.java, frame)
      onThread(thread)
      trace(site, world.pulse, "${site.hook} on ${site.label ?: site.pkg} at pulse ${world.pulse}")
      stack += frame
      var failure: EvalException? = null
      var value: Any? = null
      var ctx: Any? = null
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
        steps.merge(site.pkg, used, Long::plus)
        if (budget == null) {
          for (outer in stack) {
            outer.maxSteps -= used
            outer.thread.setMaxExecutionSteps(outer.maxSteps)
          }
        }
        trace(site, world.pulse, "  ${site.hook} done: $used steps")
      }
      if (failure != null) {
        val message = messageOf(failure, limit)
        if (recordErrors) report(world, site, classify(failure), message, failure, ctx as? Ctx)
        return CallResult.Failed(message)
      }
      return CallResult.Ok(value)
    }
  }

  private fun classify(e: EvalException): String {
    (e.cause as? LimitExceeded)?.let {
      return it.kind
    }
    if (isStepLimit(e)) return "step_limit"
    return "script_error"
  }

  private fun messageOf(e: EvalException, limit: Long): String =
    if (isStepLimit(e)) "exceeded the $limit-step limit for one event"
    else e.message ?: e.toString()

  internal fun report(
    world: World,
    site: Site,
    kind: String,
    message: String,
    e: EvalException?,
    ctx: Ctx?,
  ) {
    val loc = e?.let(::innermost)
    val d =
      Diagnostic(
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
internal fun formatStack(e: EvalException): String =
  e.callStack.joinToString("\n") { entry ->
    val loc = entry.location
    "  at ${entry.name}" +
      if (loc == Location.BUILTIN) " (builtin)"
      else " (${loc.file()}:${loc.line()}:${loc.column()})"
  }

private fun hostOf(world: World): StarlarkHost =
  world.scripts as? StarlarkHost
    ?: error("Starlark prototypes need a StarlarkHost; this world has ${world.scripts}")

/** A room or item `commands[verb]` entry. */
internal data class ScriptCommand(
  private val verb: String,
  private val ref: HookRef,
  private val pkg: String,
) : ZoneCommand {
  override fun run(world: World, actor: Actor, args: String, owner: Entity) =
    hostOf(world).runCommand(world, verb, ref, pkg, actor, args, owner)
}

/** A mob's `think` hook. */
internal data class ScriptThink(private val ref: HookRef, private val pkg: String) : ThinkFn {
  override fun think(world: World, mob: Mob, proposals: List<Proposal>): ThinkResult =
    hostOf(world).think(world, mob, proposals, ref, pkg)
}

/** A skill's custom `resolve(ctx)`. */
internal data class ScriptResolve(private val ref: HookRef, private val pkg: String) :
  SkillResolver {
  override fun resolve(world: World, caster: Actor, target: Actor?, item: Item?, power: Int) =
    hostOf(world).resolve(world, caster, target, item, power, ref, pkg)
}

/** A world loaded from a deploy's staging tree: [ok] when every package loaded. */
class StagedWorld
internal constructor(
  internal val result: LoadResult,
  val packages: List<String>,
  val errors: List<String>,
) {
  val ok: Boolean
    get() = errors.isEmpty()
}

/** The world of the hook running on [thread], or null outside one; plugin builtins use it. */
fun scriptWorld(thread: StarlarkThread): World? =
  thread.getThreadLocal(ViewScope::class.java)?.world
