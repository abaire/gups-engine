package gups.core.rules

import gups.core.model.Band
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Player

/**
 * The built-in Kotlin ruleset (SPEC §4.6, §6.3). Used when no rules package is loaded, and as the
 * fallback when the rules package fails. Open so tests can pin individual results.
 */
open class DefaultRules : RulesEngine {
  override fun equipmentSlots(): List<String> = DEFAULT_SLOTS

  override fun toHit(c: RulesContext): Int {
    val a = c.attacker
    val d = c.defender
    return (60 + (a.stat("dex") - d.stat("dex")) * 2 + (a.level - d.level) * 3).coerceIn(5, 95)
  }

  override fun damage(c: RulesContext): Int {
    val dice = c.weapon?.proto?.dice ?: UNARMED
    return (dice.roll(c.rng) + (c.attacker.stat("str") - 10) / 2).coerceAtLeast(1)
  }

  override fun mitigate(c: RulesContext, amount: Int, type: DamageType): Int {
    if (amount <= 0) return 0
    val d = c.defender
    val afterArmor = (amount - d.armor / 2).coerceAtLeast(1)
    val resist = d.resists[type] ?: 0
    return (afterArmor * (100 - resist) / 100).coerceAtLeast(0)
  }

  /** `dex` plus `level`, with no roll (SPEC §5.4.1). */
  override fun initiative(c: RulesContext): Int = c.actor.stat("dex") + c.actor.level

  override fun castAllowed(c: RulesContext): String? = null

  override fun interruptCheck(c: RulesContext, amount: Int): Boolean {
    val pct = c.actor.casting?.action?.interrupt?.damagePct ?: return false
    return amount * 100 >= pct * c.actor.maxHp.coerceAtLeast(1)
  }

  override fun retreatCheck(c: RulesContext): Int {
    val pinner = c.target?.stat("dex") ?: 10
    return (50 + (c.actor.stat("dex") - pinner) * 3).coerceIn(5, 95)
  }

  override fun fleeChance(c: RulesContext): Int =
    (40 + (c.actor.stat("dex") - 10) * 2).coerceIn(5, 95)

  override fun moveCost(c: RulesContext): Int = 1

  override fun regen(c: RulesContext): Regen {
    val a = c.actor
    return Regen(
      hp = 1 + a.stat("con") / 4 + a.level / 2,
      mana = 1 + (a.stat("int") + a.stat("wis")) / 8,
      move = 2 + a.stat("dex") / 4,
    )
  }

  override fun carryLimit(c: RulesContext): Int = c.actor.stat("str") * 10

  override fun encumbrance(c: RulesContext): Map<String, Int> = mapOf("dex" to -2)

  override fun getLag(c: RulesContext, band: Band): Int? =
    when (band) {
      Band.MELEE -> 0
      Band.NEAR -> 2
      Band.FAR -> null
    }

  override fun xpForKill(c: RulesContext): Long = 50L * c.defender.level

  override fun xpToLevel(level: Int): Long = 1000L * level

  override fun onPlayerDeath(c: RulesContext): PlayerDeathOutcome {
    val p = c.actor as Player
    return PlayerDeathOutcome(respawn = p.recallRoom, xpLoss = p.xp / 10)
  }

  companion object {
    val DEFAULT_SLOTS: List<String> =
      listOf(
        "head",
        "neck",
        "body",
        "arms",
        "hands",
        "finger_l",
        "finger_r",
        "waist",
        "legs",
        "feet",
        "wield",
        "offhand",
        "held",
      )
    private val UNARMED = Dice(1, 4)
  }
}
