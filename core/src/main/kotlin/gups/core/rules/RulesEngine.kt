package gups.core.rules

import gups.core.Label
import gups.core.Rng
import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.model.Item
import gups.core.model.SkillProto
import gups.core.world.World

/**
 * The read-only context handed to every rules call. Fields that do not apply to a call are null;
 * [attacker] and [defender] are the attack-call names for [actor] and [target].
 */
class RulesContext(
  val world: World,
  val actor: Actor,
  val target: Actor? = null,
  val weapon: Item? = null,
  val band: Band? = null,
  val skill: SkillProto? = null,
  val item: Item? = null,
) {
  val attacker: Actor
    get() = actor

  val defender: Actor
    get() = checkNotNull(target) { "This rules call has no defender" }

  val rng: Rng
    get() = world.rng
}

/**
 * The numbers `rules.starting_stats` gives a new actor. A [current] it omits equals the maximum in
 * [max].
 */
data class StartingStats(
  val stats: Map<String, Int> = emptyMap(),
  val max: Map<String, Int> = emptyMap(),
  val current: Map<String, Int> = emptyMap(),
)

/** What `rules.on_player_death` decides: where the player respawns and with which vitals. */
data class PlayerDeathOutcome(
  val respawn: Label,
  /** Current vitals of the respawned player; the rest go to their maximum. */
  val vitals: Map<String, Int>?,
  val xpLoss: Long,
)

/**
 * One method per `//rules:combat.star` export; the Starlark-backed implementation is the only one.
 */
interface RulesEngine {
  fun equipmentSlots(): List<String>

  /** Numbers for [RulesContext.actor], a new character or a mob about to spawn. */
  fun startingStats(c: RulesContext): StartingStats

  /** The life state of [RulesContext.actor]. */
  fun condition(c: RulesContext): Condition

  /** Hit chance in percent. */
  fun toHit(c: RulesContext): Int

  /** Raw auto-attack damage. */
  fun damage(c: RulesContext): Int

  /** The vital losses [amount] damage of [type] causes [RulesContext.target]. */
  fun mitigate(c: RulesContext, amount: Int, type: DamageType): Map<String, Int>

  /** Higher acts first within a combat round. */
  fun initiative(c: RulesContext): Int

  /** Null to allow, or the denial message. */
  fun castAllowed(c: RulesContext): String?

  /** Whether [RulesContext.actor], who is casting, is interrupted by [amount] total loss. */
  fun interruptCheck(c: RulesContext, amount: Int): Boolean

  /** Success chance in percent for retreating while pinned by [RulesContext.target]. */
  fun retreatCheck(c: RulesContext): Int

  fun fleeChance(c: RulesContext): Int

  /** What a room transition spends, per vital. */
  fun moveCost(c: RulesContext): Map<String, Int>

  /** What each vital gains per world tick; negative to lose. */
  fun regen(c: RulesContext): Map<String, Int>

  fun carryLimit(c: RulesContext): Int

  fun encumbrance(c: RulesContext): Map<String, Int>

  /** Extra lag for picking up [RulesContext.item] at [band]; null means out of reach. */
  fun getLag(c: RulesContext, band: Band): Int?

  /** XP that [RulesContext.actor] earns for killing [RulesContext.target]. */
  fun xpForKill(c: RulesContext): Long

  fun xpToLevel(level: Int): Long

  /** For the dead player [RulesContext.actor], killed by [RulesContext.target] if any. */
  fun onPlayerDeath(c: RulesContext): PlayerDeathOutcome

  /**
   * Recovery lag after [RulesContext.actor] steps toward [RulesContext.target], at the new band.
   */
  fun advanceLag(c: RulesContext): Int

  /**
   * Recovery lag after [RulesContext.actor] steps away from [RulesContext.target], at the new band.
   */
  fun retreatLag(c: RulesContext): Int

  /**
   * Recovery lag after [RulesContext.actor] fails to break free of the pinner,
   * [RulesContext.target].
   */
  fun retreatFailLag(c: RulesContext): Int

  /** Recovery lag after [RulesContext.actor] fails to flee; there is no target. */
  fun fleeFailLag(c: RulesContext): Int
}
