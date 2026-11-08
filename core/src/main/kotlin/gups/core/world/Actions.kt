package gups.core.world

import gups.core.EntityId
import gups.core.model.Actor
import gups.core.model.CastState
import gups.core.model.Entity
import gups.core.model.Interrupt
import gups.core.model.Item
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.QueuedAction
import gups.core.model.SkillProto

/**
 * One tick-lane action. The world drives the lifecycle: [validate], [veto], an optional warmup,
 * re-validation, [resolve], and recovery.
 */
interface Action {
  /** Pulses spent casting before [resolve]; 0 means instant. */
  val warmup: Int
    get() = 0

  /** Nominal recovery lag; an interrupt or failed re-validation applies half of it. */
  val lag: Int
    get() = 0

  val interrupt: Interrupt
    get() = Interrupt.NONE

  val attackWhileCasting: Boolean
    get() = false

  /** Step 1, and step 4 after a warmup. Null if valid, else the message for the actor. */
  fun validate(w: World, actor: Actor): String?

  /** Step 2: `before_*` hooks. Null to allow, else the denial message. */
  fun veto(w: World, actor: Actor): String? = null

  /** Called when the warmup starts, to message and fire `on_cast_start`. */
  fun begin(w: World, actor: Actor) = Unit

  /** Step 5. Returns the recovery lag (step 6). */
  fun resolve(w: World, actor: Actor): Int
}

/** An action that casts a skill, so rules and views can tell which. */
interface Castable {
  val skill: SkillProto
}

/** Pops and plans a queued command, then starts its lifecycle. */
internal fun World.runQueued(actor: Actor, queued: QueuedAction) {
  val orderedBy = queued.orderedBy
  if (orderedBy != null) {
    val owner = actor(orderedBy)
    if (
      actor !is Mob ||
        actor.owner != orderedBy ||
        owner == null ||
        !owner.inWorld ||
        owner.room !== actor.room
    ) {
      if (owner is Player && owner.inWorld) {
        tell(owner, KernelMsg.ORDER_DROPPED, "pet" to actor.name)
      }
      return
    }
  }
  val action = queued.command.plan(CommandCall(this, actor, queued.verb, queued.args)) ?: return
  execute(actor, action, orderedBy)
}

/** Steps 1–3, then 5–6 for instant actions. Failures and denials apply no lag. */
fun World.execute(actor: Actor, action: Action, orderedBy: EntityId? = null) {
  action.validate(this, actor)?.let {
    send(actor, it)
    return
  }
  action.veto(this, actor)?.let {
    send(actor, it)
    return
  }
  if (action.warmup > 0) {
    actor.casting = CastState(action, pulse, pulse + action.warmup, orderedBy)
    actor.dirty = true
    action.begin(this, actor)
    return
  }
  finish(actor, action)
}

internal fun World.advanceCast(actor: Actor, cast: CastState) {
  if (pulse < cast.endsAt) return
  actor.casting = null
  if (cast.orderedBy != null) {
    val owner = actor(cast.orderedBy)
    if (
      actor !is Mob ||
        actor.owner != cast.orderedBy ||
        owner == null ||
        !owner.inWorld ||
        owner.room !== actor.room
    ) {
      if (owner is Player && owner.inWorld) {
        tell(owner, KernelMsg.ORDER_DROPPED, "pet" to actor.name)
      }
      actor.waitState = cast.action.lag / 2
      return
    }
  }
  val failure = cast.action.validate(this, actor)
  if (failure != null) {
    send(actor, failure)
    actor.waitState = cast.action.lag / 2
    return
  }
  finish(actor, cast.action)
}

private fun World.finish(actor: Actor, action: Action) {
  val lag = action.resolve(this, actor)
  // A resolve can kill or interrupt the actor; recovery only applies to one still standing.
  if (actor.inWorld && actor.casting == null) actor.waitState = maxOf(actor.waitState, lag)
  actor.dirty = true
}

/** Breaks [actor]'s cast: no cost is paid and half the lag applies. */
fun World.interruptCast(actor: Actor) {
  val cast = actor.casting ?: return
  actor.casting = null
  actor.waitState = cast.action.lag / 2
  actor.dirty = true
  tell(actor, SkillMsg.INTERRUPTED)
}

/** Cancels [actor]'s cast for `stop`/`clear`: no cost and no lag. */
internal fun World.cancelCast(actor: Actor): Boolean {
  if (actor.casting == null) return false
  actor.casting = null
  actor.dirty = true
  return true
}

// ---- Mob AI ---------------------------------------------------------------------------------

/**
 * One behavior's suggestion for this think cycle. [commandLine] describes it (and is what runs when
 * there is no [action]); [action] fixes its target, so `kill goblin` hits the goblin the behavior
 * chose rather than whichever goblin matches first.
 */
data class Proposal(
  val commandLine: String,
  val priority: Int,
  val source: String,
  val action: Action? = null,
)

/** A built-in behavior. Proposes at most one action per think cycle. */
fun interface Behavior {
  fun propose(world: World, mob: Mob): Proposal?

  /**
   * Whether the mob is already with what this behavior is after, inside its leash, so it stays
   * rather than taking the engine's step toward home.
   */
  fun holdsReturn(world: World, mob: Mob): Boolean = false
}

sealed interface ThinkResult {
  /** Enqueue the top proposal, if any. */
  data object Accept : ThinkResult

  /** Do nothing this turn. */
  data object Idle : ThinkResult

  /** Enqueue [commandLine] instead of the proposals. */
  data class Act(val commandLine: String) : ThinkResult
}

/** A mob's `think` hook. */
fun interface ThinkFn {
  fun think(world: World, mob: Mob, proposals: List<Proposal>): ThinkResult
}

/** A zone verb attached to a room or item; runs in the tick lane with no lag. */
fun interface ZoneCommand {
  fun run(world: World, actor: Actor, args: String, owner: Entity)
}

/** A skill's custom `resolve` hook. */
fun interface SkillResolver {
  /** [item] is the target of an `item` skill, else null. */
  fun resolve(world: World, caster: Actor, target: Actor?, item: Item?, power: Int)
}

internal fun World.think(mob: Mob) {
  mob.lastThink = pulse
  val behaviors = behaviorsOf(mob)
  val proposals =
    behaviors
      .mapIndexedNotNull { i, b ->
        val p =
          try {
            b.propose(this, mob)
          } catch (e: Exception) {
            if (config.strict) throw e
            diagnose("engine", "behavior of $mob failed: ${e.message}", e)
            null
          }
        p?.let { i to it }
      }
      .let { pairs ->
        val returnProposal =
          try {
            returnProposal(mob)?.let { behaviors.size to it }
          } catch (e: Exception) {
            if (config.strict) throw e
            diagnose("engine", "return proposal of $mob failed: ${e.message}", e)
            null
          }
        if (returnProposal != null) pairs + returnProposal else pairs
      }
      .filter { (_, p) -> Sentinel !in behaviors || Sentinel.allows(p) }
      .sortedWith(
        compareByDescending<Pair<Int, Proposal>> { it.second.priority }.thenBy { it.first }
      )
      .map { it.second }
  val result =
    mob.proto.think?.let { fn ->
      try {
        fn.think(this, mob, proposals)
      } catch (e: Exception) {
        diagnose("script", "think of $mob failed: ${e.message}", e)
        ThinkResult.Accept
      }
    } ?: ThinkResult.Accept
  when (result) {
    ThinkResult.Accept ->
      proposals.firstOrNull()?.let { p ->
        val action = p.action
        if (action != null) enqueue(mob, p.commandLine.substringBefore(' '), action)
        else act(mob, p.commandLine)
      }
    ThinkResult.Idle -> Unit
    is ThinkResult.Act -> act(mob, result.commandLine)
  }
}
