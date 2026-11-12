package gups.core.world

import gups.core.model.ActivityState
import gups.core.model.Actor
import gups.core.model.Condition
import gups.core.model.Interrupt

/** Why a timed task ended without completing. [word] is what scripts see. */
enum class TaskEnd(val word: String) {
  INTERRUPTED("interrupted"),
  CANCELLED("cancelled"),
  INVALID("invalid"),
  GONE("gone"),
}

/** A snapshot of a task, for callbacks and rules. */
data class TaskInfo(
  val name: String,
  val startedAt: Long,
  val endsAt: Long,
  val lag: Int,
  val interrupt: Interrupt,
)

/** What a task does when it ends; the script host and the plugin host implement it. */
interface TaskCallbacks {
  fun done(world: World, actor: Actor, task: TaskInfo)

  fun stopped(world: World, actor: Actor, task: TaskInfo, reason: TaskEnd)
}

/** The longest name a timed task accepts. */
const val TASK_NAME_MAX = 80

/**
 * What a timed task is: its [name] (the engine's lines read "You stop {name}."), how many [pulses]
 * it takes, the recovery [lag] once it completes, and what [interrupt]s it.
 */
data class TaskSpec(
  val name: String,
  val pulses: Int,
  val lag: Int = 0,
  val interrupt: Interrupt = Interrupt(),
) {
  init {
    require(pulses >= 1) { "pulses must be at least 1, got $pulses" }
    require(lag >= 0) { "lag must be at least 0, got $lag" }
    require(isShortLine(name, TASK_NAME_MAX)) {
      "name must be a non-empty single line of at most $TASK_NAME_MAX characters"
    }
    val pct = interrupt.damagePct
    require(pct == null || pct in 1..100) { "damage_pct must be 1..100 or None, got $pct" }
  }
}

/** An activity started directly by a script or plugin: a warmup with no veto and no cost. */
class TaskAction(val spec: TaskSpec, val callbacks: TaskCallbacks) : Action {
  override val warmup: Int
    get() = spec.pulses

  override val lag: Int
    get() = spec.lag

  override val interrupt: Interrupt
    get() = spec.interrupt

  val name: String
    get() = spec.name

  /** The task as it completes, set when its warmup ends. */
  internal var info: TaskInfo? = null

  override fun validate(w: World, actor: Actor): String? =
    if (actor.canWork) null else w.msg(KernelMsg.TASK_INVALID, "task" to name)

  override fun resolve(w: World, actor: Actor): Int {
    val task = info ?: return lag
    w.runTaskCallback(actor) { callbacks.done(w, actor, task) }
    return lag
  }
}

private val Actor.canWork: Boolean
  get() = inWorld && condition == Condition.OK && !isStunned

/** The task in [state], or null when it holds a cast. */
internal fun taskInfoOf(state: ActivityState): TaskInfo? {
  val task = state.action as? TaskAction ?: return null
  return TaskInfo(task.name, state.startedAt, state.endsAt, task.lag, task.interrupt)
}

/**
 * Starts the timed task [spec] on [actor]. Null when started, else the refusal for the actor. The
 * action queue is left alone; it waits until the task ends.
 */
fun World.startTask(actor: Actor, spec: TaskSpec, callbacks: TaskCallbacks): String? {
  if (!actor.canWork) return msg(KernelMsg.TASK_UNABLE)
  if (actor.isBusy || actor.waitState > 0) return msg(KernelMsg.TASK_BUSY)
  actor.activity = ActivityState(TaskAction(spec, callbacks), pulse, pulse + spec.pulses)
  actor.dirty = true
  return null
}

/** Cancels [actor]'s task with no lag. False when it isn't running one. */
fun World.stopTask(actor: Actor): Boolean = taskOf(actor) != null && cancelActivity(actor)

/** [actor]'s running task, else null. */
fun World.taskOf(actor: Actor): TaskInfo? = actor.activity?.let(::taskInfoOf)

/** Empties [actor]'s activity slot; a task hears [reason] once the slot is clear. */
internal fun World.endActivity(actor: Actor, reason: TaskEnd) {
  clearActivity(actor)?.let { notifyStopped(actor, it, reason) }
}

/** Empties [actor]'s activity slot without running anything; returns what a task needs to hear. */
internal fun World.clearActivity(actor: Actor): ActivityState? {
  val state = actor.activity ?: return null
  actor.activity = null
  actor.dirty = true
  return state.takeIf { it.action is TaskAction }
}

/** Tells the task in [state], already cleared from [actor]'s slot, that it ended for [reason]. */
internal fun World.notifyStopped(actor: Actor, state: ActivityState, reason: TaskEnd) {
  val action = state.action as? TaskAction ?: return
  val task = taskInfoOf(state) ?: return
  runTaskCallback(actor) { action.callbacks.stopped(this, actor, task, reason) }
}

/**
 * Runs a task callback as a guarded cascade entry. The hosts record their own diagnostics; this
 * catches what escapes them so the world stays consistent.
 */
internal fun World.runTaskCallback(actor: Actor, block: () -> Unit) {
  withCascadeGuard("task of $actor") {
    try {
      block()
    } catch (e: InvariantViolation) {
      throw e
    } catch (e: Exception) {
      if (config.strict) throw e
      diagnose("engine", "task callback of $actor failed: ${e.message}", e)
    }
  }
}
