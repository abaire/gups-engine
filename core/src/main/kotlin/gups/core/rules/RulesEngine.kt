package gups.core.rules

import gups.core.Label
import gups.core.Rng
import gups.core.model.Actor
import gups.core.model.Band
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

data class Regen(val hp: Int, val mana: Int, val move: Int)

data class PlayerDeathOutcome(val respawn: Label, val xpLoss: Long)

/**
 * One method per `//rules:combat.star` export. [DefaultRules] is the built-in Kotlin ruleset; the
 * Starlark-backed implementation falls back to it.
 */
interface RulesEngine {
  fun equipmentSlots(): List<String>

  /** Hit chance in percent. */
  fun toHit(c: RulesContext): Int

  /** Raw auto-attack damage. */
  fun damage(c: RulesContext): Int

  /** Damage after armor and resists; [RulesContext.target] is the one taking it. */
  fun mitigate(c: RulesContext, amount: Int, type: DamageType): Int

  /** Higher acts first within a combat round. */
  fun initiative(c: RulesContext): Int

  /** Null to allow, or the denial message. */
  fun castAllowed(c: RulesContext): String?

  /** Whether [RulesContext.actor], who is casting, is interrupted by [amount] damage. */
  fun interruptCheck(c: RulesContext, amount: Int): Boolean

  /** Success chance in percent for retreating while pinned by [RulesContext.target]. */
  fun retreatCheck(c: RulesContext): Int

  fun fleeChance(c: RulesContext): Int

  fun moveCost(c: RulesContext): Int

  /** Vitals restored per world tick. */
  fun regen(c: RulesContext): Regen

  fun carryLimit(c: RulesContext): Int

  fun encumbrance(c: RulesContext): Map<String, Int>

  /** Extra lag for picking up [RulesContext.item] at [band]; null means out of reach. */
  fun getLag(c: RulesContext, band: Band): Int?

  /** XP that [RulesContext.actor] earns for killing [RulesContext.target]. */
  fun xpForKill(c: RulesContext): Long

  fun xpToLevel(level: Int): Long

  /** For the dead player [RulesContext.actor], killed by [RulesContext.target] if any. */
  fun onPlayerDeath(c: RulesContext): PlayerDeathOutcome

  // Track-movement lag. Engine-side settings, not Starlark exports in api_version 1.

  fun advanceLag(c: RulesContext): Int = 2

  fun retreatLag(c: RulesContext): Int = 2

  fun retreatFailLag(c: RulesContext): Int = 4

  fun fleeFailLag(c: RulesContext): Int = 4
}
