package gups.core.world

import gups.core.EntityId
import gups.core.model.Actor
import gups.core.model.CastState
import gups.core.model.Entity
import gups.core.model.Exit
import gups.core.model.Interrupt
import gups.core.model.Item
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.QueuedAction
import gups.core.model.SkillProto
import gups.core.model.ThinkOrigin
import gups.core.rules.RulesContext

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
  // A non-move step bars the actor from extra move rounds this pulse; a move records itself in
  // `chargeMove`.
  if (!queued.command.moves) actor.lastNonMovePulse = pulse
  val action = queued.command.plan(CommandCall(this, actor, queued.verb, queued.args)) ?: return
  execute(actor, action, orderedBy, queued.thinkOrigin)
}

/** Steps 1–3, then 5–6 for instant actions. Failures and denials apply no lag. */
fun World.execute(
  actor: Actor,
  action: Action,
  orderedBy: EntityId? = null,
  origin: ThinkOrigin? = null,
) {
  action.validate(this, actor)?.let {
    send(actor, it)
    return
  }
  action.veto(this, actor)?.let {
    send(actor, it)
    return
  }
  if (action.warmup > 0) {
    actor.casting = CastState(action, pulse, pulse + action.warmup, orderedBy, origin)
    actor.dirty = true
    action.begin(this, actor)
    return
  }
  finish(actor, action, origin)
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
  finish(actor, cast.action, cast.thinkOrigin)
}

private fun World.finish(actor: Actor, action: Action, origin: ThinkOrigin? = null) {
  // The exit names only the action that resolves; a move re-sets it in its deferred op.
  actor.lastMoveExit = null
  val lag = action.resolve(this, actor)
  // A resolve can kill or interrupt the actor; recovery only applies to one still standing.
  if (actor.inWorld && actor.casting == null) actor.waitState = maxOf(actor.waitState, lag)
  // A mob that chose its action may be asked whether to think again, once the action (a move, or a
  // finished warmup) has landed or been cancelled.
  if (actor is Mob && origin != null && actor.inWorld && actor.casting == null) {
    deferRemoval(actor, Deferred.Kind.CHAIN) { askChain(actor, origin) }
  }
  actor.dirty = true
}

/** The behaviors whose walks chain by default, so chasers and a mob walking home keep moving. */
internal val CHAIN_DEFAULT_SOURCES = setOf("pursue", "follow", "heel", "leash")

/**
 * Asks `rules.chain_think` whether [mob] thinks again after the action [origin] describes finished.
 * The default chains the built-in chasers and a mob walking home. A cancelled walk asks nothing.
 */
internal fun World.askChain(mob: Mob, origin: ThinkOrigin) {
  if (!mob.inWorld) return
  if (origin.moves && mob.lastMoveExit == null) return
  val c =
    RulesContext(
      this,
      mob,
      room = mob.room,
      exit = mob.lastMoveExit,
      behavior = origin.source,
      command = origin.command,
      chain = mob.chainCount,
    )
  val default = mob.lastMoveExit != null && origin.source in CHAIN_DEFAULT_SOURCES
  var chained = rules.chainThink(c) ?: default
  val call = ChainThinkCall(mob, origin.source, origin.command, mob.lastMoveExit, mob.chainCount)
  for (adjuster in chainThinkAdjusters) {
    try {
      chained = adjuster.adjust(this, call, chained)
    } catch (e: Throwable) {
      record(Diagnostic(pulse, "plugin", "chain think adjuster failed: $e", e))
    }
  }
  mob.chainDue = chained
  mob.dirty = true
}

/** One chained-think question a plugin handler answers. */
class ChainThinkCall(
  val mob: Mob,
  val source: String,
  val command: String,
  val exit: Exit?,
  val chain: Int,
)

/** Adjusts a mob's chained-think answer, after the rules export, in plugin-id order. */
fun interface ChainThinkAdjuster {
  fun adjust(world: World, call: ChainThinkCall, chained: Boolean): Boolean
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
  // A think for any reason clears the chained state; a chained think bumps the chain count.
  val chained = mob.chainDue
  mob.chainDue = false
  mob.chainCount = if (chained) mob.chainCount + 1 else 0
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
  var originSource: String? = null
  when (result) {
    ThinkResult.Accept ->
      proposals.firstOrNull()?.let { p ->
        originSource = p.source
        val action = p.action
        if (action != null) enqueue(mob, p.commandLine.substringBefore(' '), action)
        else act(mob, p.commandLine)
      }
    ThinkResult.Idle -> Unit
    is ThinkResult.Act -> {
      originSource = "think"
      act(mob, result.commandLine)
    }
  }
  // The queue was empty when the think ran, so any head now is the action this think chose; tag it
  // so the finished action can be asked whether to chain.
  mob.actionQueue.firstOrNull()?.let { queued ->
    queued.thinkOrigin =
      ThinkOrigin(originSource ?: "think", queued.toString(), queued.command.moves)
  }
  // The first action the think queued starts at once, in this step of the action pulse, as if
  // popped from the queue. A move at the head still waits for the movement debt to clear, and any
  // further actions wait their turn.
  if (mob.inWorld && mob.casting == null && mob.waitState == 0) {
    val head = mob.actionQueue.firstOrNull() ?: return
    if (head.command.moves && !canMove(mob)) return
    runQueued(mob, mob.actionQueue.removeFirst())
  }
}
