package gups.core.rules

import gups.core.Label
import gups.core.Rng
import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.Band
import gups.core.model.Channel
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.model.Direction
import gups.core.model.DispositionParts
import gups.core.model.Exit
import gups.core.model.Item
import gups.core.model.Player
import gups.core.model.Room
import gups.core.model.SkillProto
import gups.core.model.StandingDelta
import gups.core.world.Behavior
import gups.core.world.World

/**
 * Immutable contextual snapshot passed to every rules calculation call. Fields not applicable to a
 * specific calculation are null.
 */
class RulesContext(
  /** Authoritative world simulation reference. */
  val world: World,
  /** Primary actor involved in the rules call, or null for room-level queries. */
  val actor: Actor? = null,
  /** Secondary target actor involved in the rules call, or null. */
  val target: Actor? = null,
  /** Weapon item used in the attack or action, or null for unarmed combat. */
  val weapon: Item? = null,
  /** Spatial distance band where the action occurs. */
  val band: Band? = null,
  /** Skill or spell prototype being cast or resolved, or null. */
  val skill: SkillProto? = null,
  /** Item being manipulated, picked up, or used, or null. */
  val item: Item? = null,
  /** Room where the action occurs or perception is evaluated. */
  val room: Room? = null,
  /** Exit being inspected, traversed, or crossed by sensory perception. */
  val exit: Exit? = null,
  /** Sensory channel evaluated for perception (e.g. sound, sight). */
  val channel: Channel? = null,
  /** Sensory event type (e.g. `say`, `combat`, `open`). */
  val event: String? = null,
  /** Initial sensory signal intensity at the source. */
  val intensity: Int? = null,
  /** Accumulated path attenuation cost through room exits. */
  val cost: Int? = null,
  /** Room where the sensory event originated. */
  val sourceRoom: Room? = null,
  /** Direction from which the perceived signal arrived. */
  val viaExit: Direction? = null,
  /** Textual narrative payload associated with the event. */
  val text: String? = null,
  /** Action verb identifier (e.g. `open`, `close`). */
  val action: String? = null,
  /** Semantic tags associated with the context. */
  val tags: Set<String> = emptySet(),
  /** The actor who originated the perceived event, or null if environmental. */
  val source: Actor? = null,
  /** Damage type of the attack or damage being rolled, modified or mitigated, or null. */
  val damageType: DamageType? = null,
  /** Equipment slot of the swinging item in an auto-attack swing, or null outside swings. */
  val slot: String? = null,
  /** The affect whose modifier is running, or null outside an affect's modifier. */
  val affect: Affect? = null,
  /** The actor's carry limit asked without an item, in encumbrance_level and encumbrance calls. */
  val limit: Int? = null,
  /** Carried and worn weight, in encumbrance_level and encumbrance calls. */
  val carried: Int? = null,
  /** The level just computed, in encumbrance calls. */
  val encumbranceLevel: Int? = null,
  /** The experience pool a kill is being settled for, in xp_share; null elsewhere. */
  val xp: Long? = null,
  /** The victim's contributors, in xp_share; null elsewhere. */
  val contributors: List<Contributor>? = null,
  /** The prototype's behaviors, in owned_behaviors; null elsewhere. */
  val behaviors: List<Behavior>? = null,
  /** The parts of the default disposition, in disposition; null elsewhere. */
  val dispositionParts: DispositionParts? = null,
  /** Event amount (heal amount, item count), in standing_change; null elsewhere. */
  val amount: Long? = null,
  /** The helped actor, in an assist standing event; null elsewhere. */
  val helped: Actor? = null,
  /** The pet mob whose action raised an event for its owner; null elsewhere. */
  val pet: Actor? = null,
) {
  /** Returns [actor], throwing an [IllegalStateException] if this rules call lacks an actor. */
  fun requireActor(): Actor = checkNotNull(actor) { "This rules call has no actor" }

  /**
   * A copy of this context with [damageType] replaced; every other field is listed explicitly and
   * carried over unchanged.
   */
  fun withDamageType(type: DamageType): RulesContext =
    RulesContext(
      world = world,
      actor = actor,
      target = target,
      weapon = weapon,
      band = band,
      skill = skill,
      item = item,
      room = room,
      exit = exit,
      channel = channel,
      event = event,
      intensity = intensity,
      cost = cost,
      sourceRoom = sourceRoom,
      viaExit = viaExit,
      text = text,
      action = action,
      tags = tags,
      source = source,
      damageType = type,
      slot = slot,
      affect = affect,
      limit = limit,
      carried = carried,
      encumbranceLevel = encumbranceLevel,
      xp = xp,
      contributors = contributors,
      behaviors = behaviors,
      dispositionParts = dispositionParts,
      amount = amount,
      helped = helped,
      pet = pet,
    )

  /**
   * A copy of this context with [affect] replaced; every other field is listed explicitly and
   * carried over unchanged.
   */
  fun withAffect(a: Affect?): RulesContext =
    RulesContext(
      world = world,
      actor = actor,
      target = target,
      weapon = weapon,
      band = band,
      skill = skill,
      item = item,
      room = room,
      exit = exit,
      channel = channel,
      event = event,
      intensity = intensity,
      cost = cost,
      sourceRoom = sourceRoom,
      viaExit = viaExit,
      text = text,
      action = action,
      tags = tags,
      source = source,
      damageType = damageType,
      slot = slot,
      affect = a,
      limit = limit,
      carried = carried,
      encumbranceLevel = encumbranceLevel,
      xp = xp,
      contributors = contributors,
      behaviors = behaviors,
      dispositionParts = dispositionParts,
      amount = amount,
      helped = helped,
      pet = pet,
    )

  /** Attacking actor in a combat rules call (alias for [requireActor]). */
  val attacker: Actor
    get() = requireActor()

  /** Defending actor in a combat rules call (alias for [target]). */
  val defender: Actor
    get() = checkNotNull(target) { "This rules call has no defender" }

  /** Deterministic pseudo-random number generator provided by the world simulation. */
  val rng: Rng
    get() = world.rng
}

/** One actor's part in a kill, as `xp_share` sees it. */
data class Contributor(
  val actor: Actor,
  val damage: Int,
  val healing: Int,
  val support: Int,
  val lastPulse: Long,
  val owner: Player?,
  val groupId: Int?,
  val inRoom: Boolean,
)

/**
 * Initial attribute and vital values assigned to a newly created character or freshly spawned mob.
 */
data class StartingStats(
  /** Base attribute values keyed by declared stat name. */
  val stats: Map<String, Int> = emptyMap(),
  /** Base maximum vital values keyed by declared vital name. */
  val max: Map<String, Int> = emptyMap(),
  /** Starting current vital values; unlisted vitals start at their maximum. */
  val current: Map<String, Int> = emptyMap(),
  /** Base perception sense scores merged over engine defaults. */
  val senses: Map<Channel, Int> = emptyMap(),
)

/** Outcome of resolving a player character's death. */
data class PlayerDeathOutcome(
  /** Destination room label where the player character respawns. */
  val respawn: Label,
  /** Vital values restored upon respawning; unlisted vitals recover to maximum. */
  val vitals: Map<String, Int>?,
  /** Experience points deducted as a death penalty; never negative. */
  val xpLoss: Long,
  /** The number of levels lost after the respawn. */
  val levelLoss: Int = 0,
)

/**
 * Stat and vital deltas awarded when an actor advances a level.
 *
 * Returned by [RulesEngine.onLevelUp], whose `ctx.actor` already has its new level. All three
 * fields are deltas and may be negative. [stats] and [max] are recorded in `levelGains` and
 * [current] is not. A null result means no gains.
 */
data class LevelUpResult(
  /** Base stat deltas awarded by this level. */
  val stats: Map<String, Int> = emptyMap(),
  /** Base vital maximum deltas awarded by this level. */
  val max: Map<String, Int> = emptyMap(),
  /** Current vital deltas awarded by this level (e.g. healing). */
  val current: Map<String, Int> = emptyMap(),
)

/**
 * Authoritative game mechanics rules engine seam.
 *
 * Implemented by Starlark scripts under `//rules` in production, or test doubles during unit
 * testing. Every method corresponds to an exported function in the world's rules scripts.
 */
interface RulesEngine {
  /** Returns the list of declared equipment slot names valid in this world. */
  fun equipmentSlots(): List<String>

  /** Slot family names mapped to their ordered concrete slots. */
  fun slotFamilies(): Map<String, List<String>>

  /** The concrete slots `c.actor` has; a subset of [equipmentSlots]. */
  fun actorSlots(c: RulesContext): List<String>

  /** How many family slots `c.item` takes up when worn by `c.actor`; at least 1. */
  fun slotCost(c: RulesContext): Int

  /**
   * Calculates initial stats, maximum vitals, and base senses for a new character or spawning mob.
   */
  fun startingStats(c: RulesContext): StartingStats

  /**
   * Evaluates and returns the current life condition (`OK`, `INCAPACITATED`, or `DEAD`) of an
   * actor.
   */
  fun condition(c: RulesContext): Condition

  /**
   * Calculates the hit chance in percent for an auto-attack swing, or for an enemy spell when
   * `c.skill` is set (then `c.weapon` and `c.slot` are null).
   */
  fun toHit(c: RulesContext): Int

  /** Rolls raw auto-attack damage before damage mitigation. */
  fun damage(c: RulesContext): Int

  /**
   * Calculates vital damage reductions after armor and resistances, returning net losses per vital.
   */
  fun mitigate(c: RulesContext, amount: Int, type: DamageType): Map<String, Int>

  /** Rolls round initiative determining action order within a combat round. */
  fun initiative(c: RulesContext): Int

  /**
   * Validates whether an actor may begin casting a skill; returns null to permit or a refusal
   * message.
   */
  fun castAllowed(c: RulesContext): String?

  /** Determines whether suffering [amount] total vital damage interrupts an in-progress cast. */
  fun interruptCheck(c: RulesContext, amount: Int): Boolean

  /** Percent chance that c.actor gets c.skill off; 100 or more always succeeds. */
  fun skillCheck(c: RulesContext): Int = 100

  /** Percent strength of c.actor's successful cast of c.skill; 100 is as declared. */
  fun skillPower(c: RulesContext): Int = 100

  /**
   * Calculates success probability percentage when attempting to retreat while pinned by an
   * adversary.
   */
  fun retreatCheck(c: RulesContext): Int

  /** Calculates success probability percentage when attempting to flee from combat. */
  fun fleeChance(c: RulesContext): Int

  /** Calculates vital costs deducted when traversing an exit between rooms. */
  fun moveCost(c: RulesContext): Map<String, Int>

  /** Calculates periodic vital recovery or loss applied on each world tick. */
  fun regen(c: RulesContext): Map<String, Int>

  /** Calculates maximum carrying weight capacity, the most the actor can pick up. */
  fun carryLimit(c: RulesContext): Int

  /** The actor's encumbrance level, 0 or more; 0 means unencumbered. */
  fun encumbranceLevel(c: RulesContext): Int

  /** Calculates stat penalties for the current encumbrance level. */
  fun encumbrance(c: RulesContext): Map<String, Int>

  /** Percent strength of what c.item does for c.actor (use, or a proc); 100 is as declared. */
  fun itemPower(c: RulesContext): Int = 100

  /**
   * Calculates extra recovery lag when retrieving an item at distance [band], or null if out of
   * reach.
   */
  fun getLag(c: RulesContext, band: Band): Int?

  /** Calculates experience points awarded to an actor for defeating a target. */
  fun xpForKill(c: RulesContext): Long

  /**
   * The owned behavior list for [c]`actor` (a mob) owned by [c]`target`, or null for the default.
   */
  fun ownedBehaviors(c: RulesContext): List<Behavior>? = null

  /** The factions of [c]`actor`, or null for the default. */
  fun factions(c: RulesContext): List<Label>? = null

  /** What [c]`actor` thinks of [c]`target`, or null for the default. */
  fun disposition(c: RulesContext): Int? = null

  /** The standing changes produced by a standing event, or null for the default deltas. */
  fun standingChange(c: RulesContext): List<StandingDelta>? = null

  /** Who is awarded what of [c]`xp` for the kill; the default pays the killer all of it. */
  fun xpShare(c: RulesContext): Map<Actor, Long> =
    c.actor?.let { mapOf(it to (c.xp ?: 0L)) } ?: emptyMap()

  /**
   * Calculates the total experience points at which [level] ends for the actor in [c].
   *
   * [c]`actor` is the actor whose curve is asked about; its current level may differ from [level]
   * when evaluating higher or lower thresholds.
   *
   * The result must depend only on the actor and [level], as it is evaluated frequently and with
   * levels other than the actor's current level. It must not use random numbers, dice rolls, or
   * game time pulses.
   *
   * Values must be strictly increasing for each actor: each level threshold must be strictly
   * greater than the threshold for the level below it for the same actor.
   *
   * If an actor's curve changes, past levels are not re-evaluated; the new curve takes effect at
   * the next experience award, level check, or progression step without modifying existing level
   * gain records.
   */
  fun xpToLevel(c: RulesContext, level: Int): Long

  /**
   * Calculates stat and vital deltas awarded when an actor reaches a new level, or null for no
   * gains.
   *
   * `ctx.actor` already has its new level. All three fields are deltas; [LevelUpResult.stats] and
   * [LevelUpResult.max] are recorded in `levelGains` and [LevelUpResult.current] is not. A null
   * result or rules error means no gains.
   */
  fun onLevelUp(c: RulesContext): LevelUpResult?

  /**
   * Resolves player death consequences, returning respawn destination, vital recovery, and XP loss.
   */
  fun onPlayerDeath(c: RulesContext): PlayerDeathOutcome

  /** Calculates action recovery lag after stepping toward a target at a new band. */
  fun advanceLag(c: RulesContext): Int

  /** Calculates action recovery lag after stepping away from a target at a new band. */
  fun retreatLag(c: RulesContext): Int

  /** Calculates action recovery lag after failing to retreat while pinned. */
  fun retreatFailLag(c: RulesContext): Int

  /** Calculates action recovery lag after failing to flee from combat. */
  fun fleeFailLag(c: RulesContext): Int

  /**
   * Calculates signal attenuation cost when crossing an exit into a room (negative blocks
   * propagation).
   */
  fun senseCost(c: RulesContext): Int = 1

  /**
   * Evaluates whether an actor notices a sensory event based on sense, intensity, and path
   * attenuation.
   */
  fun canPerceive(c: RulesContext): Boolean {
    val sense = c.actor?.sense(c.channel ?: Channel.SOUND) ?: 0
    return sense > 0 &&
      (c.sourceRoom === (c.room ?: c.actor?.room) || (c.intensity ?: 1) + sense > (c.cost ?: 0))
  }

  /** Formats a narrative description of an event perceived across rooms, or null for silence. */
  fun perceiveMessage(c: RulesContext): String? = null

  /** Calculates sound intensity generated by manipulating a door or container (0 is silent). */
  fun doorIntensity(c: RulesContext): Int = if (c.action == "open" || c.action == "close") 1 else 0
}
