package gups.core.fixtures

import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Channel
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.DoorState
import gups.core.model.Player
import gups.core.model.TrackSize
import gups.core.rules.LevelUpResult
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

  /** Replaces `on_level_up` when set. */
  var levelUpOf: ((RulesContext) -> LevelUpResult?)? = null

  /** Replaces `xp_to_level` when set. */
  var xpToLevelOf: ((Int) -> Long)? = null

  /** Replaces `xp_for_kill` when set. */
  var xpForKillOf: ((RulesContext) -> Long)? = null

  /** Replaces `sense_cost` when set. */
  var senseCostOf: ((RulesContext) -> Int)? = null

  override fun senseCost(c: RulesContext): Int {
    senseCostOf?.let {
      return it(c)
    }
    val exit = checkNotNull(c.exit)
    val channel = checkNotNull(c.channel)
    if ("sealed" in exit.tags) return -1
    var cost = 1
    if (checkNotNull(c.room).proto.size == TrackSize.LARGE && channel != Channel.SIGHT) cost++
    val door = exit.door
    if (door != null && door.state != DoorState.OPEN) {
      if (channel == Channel.SIGHT && "transparent" !in door.tags) return -1
      cost += if ("heavy" in door.tags) 2 else 1
    }
    return cost
  }

  override fun perceiveMessage(c: RulesContext): String? {
    val via = c.viaExit
    if (via == null && c.event != "shout") return null
    return when (c.event) {
      "shout" ->
        if (via == null)
          "${c.source?.name?.replaceFirstChar { it.uppercase() } ?: "Someone"} shouts, '${c.text.orEmpty()}'"
        else "Someone shouts from the $via, '${c.text.orEmpty()}'"
      "combat_start",
      "combat" -> "You hear the sounds of combat from the $via."
      "death" -> "You hear a death cry from the $via."
      "open",
      "close",
      "lock",
      "unlock" -> "You hear something ${c.event} to the $via."
      else -> c.text?.takeIf { it.isNotEmpty() }
    }
  }

  override fun doorIntensity(c: RulesContext): Int {
    val tags = c.tags
    return when {
      "silent" in tags -> 0
      c.action == "open" || c.action == "close" ->
        if (tags.any { it == "heavy" || it == "creaky" || it == "loud" }) 2 else 1
      c.action == "lock" || c.action == "unlock" ->
        if (tags.any { it == "clanking" || it == "noisy_lock" || it == "heavy" }) 1 else 0
      else -> 0
    }
  }

  private fun stat(a: Actor, name: String?): Int = name?.let(a::stat) ?: 0

  override fun equipmentSlots(): List<String> = SLOTS

  override fun startingStats(c: RulesContext): StartingStats {
    startingOf?.let {
      return it(c)
    }
    val a = c.requireActor()
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
    conditionCalls.merge(c.requireActor().name, 1, Int::plus)
    conditionOf?.let {
      return it(c)
    }
    return if (c.requireActor().current(fs.health) <= 0) Condition.DEAD else Condition.OK
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

  override fun initiative(c: RulesContext): Int =
    c.requireActor().stat(fs.agility) + c.requireActor().level

  override fun castAllowed(c: RulesContext): String? = null

  override fun interruptCheck(c: RulesContext, amount: Int): Boolean {
    val pct = c.requireActor().casting?.action?.interrupt?.damagePct ?: return false
    return amount * 100 >= pct * c.requireActor().max(fs.health).coerceAtLeast(1)
  }

  override fun retreatCheck(c: RulesContext): Int {
    val pinner = c.target?.stat(fs.agility) ?: 10
    return (50 + (c.requireActor().stat(fs.agility) - pinner) * 3).coerceIn(5, 95)
  }

  override fun fleeChance(c: RulesContext): Int =
    (40 + (c.requireActor().stat(fs.agility) - 10) * 2).coerceIn(5, 95)

  override fun moveCost(c: RulesContext): Map<String, Int> =
    moveCostOf?.invoke(c) ?: mapOf(fs.stamina to 1)

  override fun regen(c: RulesContext): Map<String, Int> {
    regenOf?.let {
      return it(c)
    }
    val a = c.requireActor()
    return buildMap {
      put(fs.health, 1 + a.stat(fs.toughness) / 4 + a.level / 2)
      if (fs.energy != fs.stamina) put(fs.energy, 1 + fs.mind.sumOf(a::stat) / (4 * fs.mind.size))
      put(fs.stamina, (get(fs.stamina) ?: 0) + 2 + a.stat(fs.agility) / 4)
    }
  }

  override fun carryLimit(c: RulesContext): Int = c.requireActor().stat(fs.strength) * 10

  override fun encumbrance(c: RulesContext): Map<String, Int> = mapOf(fs.agility to -2)

  override fun getLag(c: RulesContext, band: Band): Int? =
    when (band) {
      Band.MELEE -> 0
      Band.NEAR -> 2
      Band.FAR -> null
    }

  override fun advanceLag(c: RulesContext): Int = 2

  override fun retreatLag(c: RulesContext): Int = 2

  override fun retreatFailLag(c: RulesContext): Int = 4

  override fun fleeFailLag(c: RulesContext): Int = 4

  override fun xpForKill(c: RulesContext): Long = xpForKillOf?.invoke(c) ?: (50L * c.defender.level)

  override fun xpToLevel(level: Int): Long = xpToLevelOf?.invoke(level) ?: (1000L * level)

  override fun onLevelUp(c: RulesContext): LevelUpResult? = levelUpOf?.invoke(c)

  override fun onPlayerDeath(c: RulesContext): PlayerDeathOutcome {
    deathOf?.let {
      return it(c)
    }
    val p = c.requireActor() as Player
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
