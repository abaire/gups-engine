package gups.core.rules

import gups.core.Label
import gups.core.Rng
import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Channel
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.model.Direction
import gups.core.model.Exit
import gups.core.model.Item
import gups.core.model.Room
import gups.core.model.SkillProto
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
) {
  /** Returns [actor], throwing an [IllegalStateException] if this rules call lacks an actor. */
  fun requireActor(): Actor = checkNotNull(actor) { "This rules call has no actor" }

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

  /**
   * Calculates initial stats, maximum vitals, and base senses for a new character or spawning mob.
   */
  fun startingStats(c: RulesContext): StartingStats

  /**
   * Evaluates and returns the current life condition (`OK`, `INCAPACITATED`, or `DEAD`) of an
   * actor.
   */
  fun condition(c: RulesContext): Condition

  /** Calculates the hit probability percentage (1..99) for an attack. */
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

  /** Calculates maximum carrying weight capacity before encumbrance penalties apply. */
  fun carryLimit(c: RulesContext): Int

  /** Calculates stat penalties incurred when carrying weight in excess of the carry limit. */
  fun encumbrance(c: RulesContext): Map<String, Int>

  /**
   * Calculates extra recovery lag when retrieving an item at distance [band], or null if out of
   * reach.
   */
  fun getLag(c: RulesContext, band: Band): Int?

  /** Calculates experience points awarded to an actor for defeating a target. */
  fun xpForKill(c: RulesContext): Long

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
