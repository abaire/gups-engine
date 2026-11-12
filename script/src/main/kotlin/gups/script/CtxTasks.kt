package gups.script

import gups.core.AttrValue
import gups.core.EntityId
import gups.core.HookRef
import gups.core.model.Actor
import gups.core.model.Entity
import gups.core.model.Interrupt
import gups.core.world.HookContext
import gups.core.world.TASK_NAME_MAX
import gups.core.world.TaskCallbacks
import gups.core.world.TaskEnd
import gups.core.world.TaskInfo
import gups.core.world.TaskSpec
import gups.core.world.World
import gups.core.world.isShortLine
import gups.core.world.startTask
import gups.core.world.stopTask
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.Starlark

/** Timed tasks: an activity that waits like a warmup, then calls back. */
abstract class CtxTasks
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxSkills(frame, owner, hc, extras) {
  @StarlarkMethod(
    name = "start_task",
    doc =
      "Starts a timed task on actor: after pulses, on_done(ctx, **args) runs, then lag applies." +
        " stop, clear, stop_task, a stun or damage interrupt_check accepts, death or removal end" +
        " it early with on_stop(ctx, reason, **args), reason one of 'interrupted', 'cancelled'," +
        " 'invalid' or 'gone'. Returns None when started, else the refusal message.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Player or mob that does the task."),
        Param(name = "pulses", named = true, doc = "Pulses the task takes, at least 1."),
        Param(name = "on_done", named = true, doc = "Top-level function on_done(ctx, **args)."),
        Param(
          name = "name",
          named = true,
          defaultValue = "'task'",
          doc = "What the actor is doing, a gerund phrase: 'You stop {name}.'",
        ),
        Param(name = "lag", named = true, defaultValue = "0", doc = "Recovery once it completes."),
        Param(
          name = "interrupt",
          named = true,
          defaultValue = "None",
          doc = "{'stun': bool, 'damage_pct': int 1..100 or None}; None is {'stun': True}.",
        ),
        Param(
          name = "on_stop",
          named = true,
          defaultValue = "None",
          doc = "Top-level function on_stop(ctx, reason, **args), or None.",
        ),
      ],
    extraKeywords =
      Param(name = "args", doc = "Keyword arguments for both callbacks; values must be storable."),
  )
  @Suppress("LongParameterList")
  fun startTask(
    actor: Any,
    pulses: Any,
    onDone: Any,
    name: Any,
    lag: Any,
    interrupt: Any,
    onStop: Any,
    args: Dict<String, Any>,
  ): Any =
    verb("start_task") {
      val a = actorArg(actor, "start_task(actor)")
      val spec =
        TaskSpec(taskName(name), taskPulses(pulses), taskLag(lag), taskInterrupt(interrupt))
      val callbacks =
        ScriptTaskCallbacks(
          frame.host,
          owner.id,
          hookRef(onDone, "start_task(on_done)"),
          hookRefOrNull(onStop, "start_task(on_stop)"),
          storedArgs(args),
        )
      world.startTask(a, spec, callbacks) ?: Starlark.NONE
    }

  @StarlarkMethod(
    name = "stop_task",
    doc = "Cancels actor's timed task, with reason 'cancelled' and no lag; True if it had one.",
    parameters = [Param(name = "actor", named = true, doc = "Player or mob.")],
  )
  fun stopTask(actor: Any): Any =
    verb("stop_task") { world.stopTask(actorArg(actor, "stop_task(actor)")) }

  private fun taskPulses(pulses: Any): Int {
    val n = int(pulses, "start_task(pulses)")
    if (n < 1) fail("start_task(pulses): must be at least 1, got $n")
    return n
  }

  private fun taskLag(lag: Any): Int {
    val n = int(lag, "start_task(lag)")
    if (n < 0) fail("start_task(lag): must be at least 0, got $n")
    return n
  }

  private fun taskName(name: Any): String {
    val s = str(name, "start_task(name)")
    if (!isShortLine(s, TASK_NAME_MAX))
      fail("start_task(name): must be a non-empty single line of at most $TASK_NAME_MAX characters")
    return s
  }

  private fun storedArgs(args: Dict<String, Any>): Map<String, AttrValue> =
    args.entries.associate { (k, x) ->
      k to
        (toAttr(x, "start_task($k)", frame.host.limits)
          ?: fail("start_task($k): None can't be passed to a task"))
    }
}

/** Parses a task's `interrupt` dict: only `stun` and `damage_pct`. */
internal fun taskInterrupt(raw: Any?): Interrupt {
  if (isNone(raw)) return Interrupt()
  val what = "start_task(interrupt)"
  val m = dict(raw, what)
  for (k in m.keys) choice(k, what, listOf("damage_pct", "stun"))
  val pct = m["damage_pct"]?.let { intOrNull(it, "$what[\"damage_pct\"]") }
  if (pct != null && pct !in 1..100) fail("$what: damage_pct must be 1 to 100 or None, got $pct")
  return Interrupt(damagePct = pct, stun = m["stun"]?.let { bool(it, "$what[\"stun\"]") } ?: true)
}

/** A task as scripts see it: `struct(name, started_at, ends_at, remaining, interrupt)`. */
internal fun taskStruct(world: World, task: TaskInfo): Any =
  StructValue(
    linkedMapOf(
      "name" to task.name,
      "started_at" to sInt(task.startedAt),
      "ends_at" to sInt(task.endsAt),
      "remaining" to sInt((task.endsAt - world.pulse).coerceAtLeast(0)),
      "interrupt" to interruptDict(task.interrupt),
    )
  )

/** `{'damage_pct': int or None, 'stun': bool}`, as a skill view's `interrupt`. */
internal fun interruptDict(i: Interrupt): Any =
  sDict(mapOf("damage_pct" to (i.damagePct?.let(::sInt) ?: Starlark.NONE), "stun" to i.stun))

/** One call of a task's callback [ref]; a stop callback also gets its [reason]. */
internal data class TaskCall(val ref: HookRef, val ctx: HookContext, val reason: String?)

/** Runs a script task's callbacks on the starter's `ctx.self`, skipped once that has left. */
private class ScriptTaskCallbacks(
  private val host: StarlarkHost,
  private val ownerId: EntityId,
  private val onDone: HookRef,
  private val onStop: HookRef?,
  private val args: Map<String, AttrValue>,
) : TaskCallbacks {
  override fun done(world: World, actor: Actor, task: TaskInfo) =
    call(world, onDone, HookContext(actor = actor, args = args, task = task), null)

  override fun stopped(world: World, actor: Actor, task: TaskInfo, reason: TaskEnd) {
    val ref = onStop ?: return
    call(world, ref, HookContext(actor = actor, args = args, task = task), reason.word)
  }

  private fun call(world: World, ref: HookRef, ctx: HookContext, reason: String?) {
    val owner = world.entity(ownerId) ?: return
    if (owner is Actor && !owner.inWorld) return
    host.invokeTask(world, owner, TaskCall(ref, ctx, reason))
  }
}
