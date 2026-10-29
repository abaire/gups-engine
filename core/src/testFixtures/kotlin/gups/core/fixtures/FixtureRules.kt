package gups.core.fixtures

import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Player
import gups.core.rules.PlayerDeathOutcome
import gups.core.rules.RulesContext
import gups.core.rules.RulesEngine
import gups.core.rules.StartingStats

/**
 * A Kotlin ruleset for engine tests, over either [FixtureSchemas] schema. It follows the template
 * world's formulas through the roles of [FixtureSchema]. Open, so tests can pin individual results.
 */
open class FixtureRules(val fs: FixtureSchema = FixtureSchemas.FANTASY) : RulesEngine {
  /** Counts the `condition` calls per actor name. */
  val conditionCalls = HashMap<String, Int>()

  /** Replaces `condition` when set. */
  var conditionOf: ((RulesContext) -> Condition)? = null

  /** Replaces `mitigate` when set. */
  var mitigation: ((RulesContext, Int, DamageType) -> Map<String, Int>)? = null

  /** Replaces `regen` when set. */
  var regenOf: ((RulesContext) -> Map<String, Int>)? = null

  /** Replaces `move_cost` when set. */
  var moveCostOf: ((RulesContext) -> Map<String, Int>)? = null

  /** Replaces `starting_stats` when set. */
  var startingOf: ((RulesContext) -> StartingStats)? = null

  /** Replaces `on_player_death` when set. */
  var deathOf: ((RulesContext) -> PlayerDeathOutcome)? = null

  private fun stat(a: Actor, name: String?): Int = name?.let(a::stat) ?: 0

  override fun equipmentSlots(): List<String> = SLOTS

  override fun startingStats(c: RulesContext): StartingStats {
    startingOf?.let {
      return it(c)
    }
    val a = c.actor
    val stats = fs.stats.associateWith { if (it == fs.armor) 0 else 10 }
    return if (a is Player) {
      StartingStats(stats = stats, max = fs.playerMax, current = fs.startsEmpty.mapValues { 0 })
    } else {
      StartingStats(
        stats = stats.mapValues { (name, v) -> a.baseStats[name]?.takeIf { it != 0 } ?: v },
        max = fs.mobMax.mapValues { (name, v) -> a.baseMax(name).takeIf { it != 0 } ?: v },
        current = fs.startsEmpty.mapValues { 0 },
      )
    }
  }

  override fun condition(c: RulesContext): Condition {
    conditionCalls.merge(c.actor.name, 1, Int::plus)
    conditionOf?.let {
      return it(c)
    }
    return if (c.actor.current(fs.health) <= 0) Condition.DEAD else Condition.OK
  }

  override fun toHit(c: RulesContext): Int {
    val a = c.attacker
    val d = c.defender
    return (60 + (a.stat(fs.agility) - d.stat(fs.agility)) * 2 + (a.level - d.level) * 3).coerceIn(
      5,
      95,
    )
  }

  override fun damage(c: RulesContext): Int {
    val dice = c.weapon?.proto?.dice ?: UNARMED
    return (dice.roll(c.rng) + (c.attacker.stat(fs.strength) - 10) / 2).coerceAtLeast(1)
  }

  override fun mitigate(c: RulesContext, amount: Int, type: DamageType): Map<String, Int> {
    mitigation?.let {
      return it(c, amount, type)
    }
    if (amount <= 0) return emptyMap()
    val d = c.defender
    val afterArmor = (amount - stat(d, fs.armor) / 2).coerceAtLeast(1)
    val resist = d.resists[type] ?: 0
    return mapOf(fs.health to (afterArmor * (100 - resist) / 100).coerceAtLeast(0))
  }

  override fun initiative(c: RulesContext): Int = c.actor.stat(fs.agility) + c.actor.level

  override fun castAllowed(c: RulesContext): String? = null

  override fun interruptCheck(c: RulesContext, amount: Int): Boolean {
    val pct = c.actor.casting?.action?.interrupt?.damagePct ?: return false
    return amount * 100 >= pct * c.actor.max(fs.health).coerceAtLeast(1)
  }

  override fun retreatCheck(c: RulesContext): Int {
    val pinner = c.target?.stat(fs.agility) ?: 10
    return (50 + (c.actor.stat(fs.agility) - pinner) * 3).coerceIn(5, 95)
  }

  override fun fleeChance(c: RulesContext): Int =
    (40 + (c.actor.stat(fs.agility) - 10) * 2).coerceIn(5, 95)

  override fun moveCost(c: RulesContext): Map<String, Int> =
    moveCostOf?.invoke(c) ?: mapOf(fs.stamina to 1)

  override fun regen(c: RulesContext): Map<String, Int> {
    regenOf?.let {
      return it(c)
    }
    val a = c.actor
    return buildMap {
      put(fs.health, 1 + a.stat(fs.toughness) / 4 + a.level / 2)
      if (fs.energy != fs.stamina) put(fs.energy, 1 + fs.mind.sumOf(a::stat) / (4 * fs.mind.size))
      put(fs.stamina, (get(fs.stamina) ?: 0) + 2 + a.stat(fs.agility) / 4)
    }
  }

  override fun carryLimit(c: RulesContext): Int = c.actor.stat(fs.strength) * 10

  override fun encumbrance(c: RulesContext): Map<String, Int> = mapOf(fs.agility to -2)

  override fun getLag(c: RulesContext, band: Band): Int? =
    when (band) {
      Band.MELEE -> 0
      Band.NEAR -> 2
      Band.FAR -> null
    }

  override fun xpForKill(c: RulesContext): Long = 50L * c.defender.level

  override fun xpToLevel(level: Int): Long = 1000L * level

  override fun onPlayerDeath(c: RulesContext): PlayerDeathOutcome {
    deathOf?.let {
      return it(c)
    }
    val p = c.actor as Player
    return PlayerDeathOutcome(p.recallRoom, mapOf(fs.health to 1), p.xp / 10)
  }

  companion object {
    val SLOTS: List<String> =
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
