package gups.script

import gups.core.Label
import gups.core.fixtures.FixtureRules
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Band
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.rules.RulesContext
import gups.core.rules.StartingStats
import gups.core.world.AffectSpec
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.core.world.applyAffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulesTemplateWorldTest : RulesTestBase() {
  private fun loadTemplateWorld(): World {
    val dir = WorldDir(java.nio.file.Path.of("../template-world"))
    val loader = ScriptLoader(dir, ScriptLimits.HARNESS)
    val result = loader.load(listOf("rules", "zones/tutorial"))
    assertTrue(result.ok, result.report())
    val host = StarlarkHost(result, ScriptLimits.HARNESS)
    val w =
      World(
        result.prototypes(),
        loader.schema,
        host.rules(),
        host,
        WorldConfig(seed = 3, strict = true),
        levelMode = loader.module.levelMode,
        standingHistoryPulses = loader.module.standingHistoryPulses,
      )
    host.install(w)
    return w
  }

  @Test
  fun `the template world's rules agree with the fixture ruleset`() {
    val w = loadTemplateWorld()
    val kotlin = FixtureRules(FixtureSchemas.FANTASY)
    val a =
      w.spawnPlayer(
        "Ann",
        Label("//zones/tutorial:hall"),
        level = 3,
        overrides =
          StartingStats(
            stats = mapOf("str" to 14, "dex" to 12, "con" to 13, "int" to 9, "wis" to 11)
          ),
      )
    val b =
      w.spawnPlayer(
        "Bob",
        Label("//zones/tutorial:hall"),
        level = 5,
        overrides = StartingStats(stats = mapOf("armor" to 6)),
      )
    a.xp = 3500
    w.applyAffect(a, AffectSpec("clumsy", 50, statDeltas = mapOf("dex" to -3)))
    val c = RulesContext(w, a, b, band = Band.NEAR)
    assertEquals(kotlin.equipmentSlots(), w.rules.equipmentSlots())
    assertEquals(kotlin.startingStats(c), w.rules.startingStats(c))
    assertEquals(Condition.OK, w.rules.condition(c))
    assertEquals(kotlin.toHit(c), w.rules.toHit(c))
    assertEquals(
      kotlin.mitigate(c, 17, DamageType("fire")),
      w.rules.mitigate(c, 17, DamageType("fire")),
    )
    assertEquals(kotlin.initiative(c), w.rules.initiative(c))
    assertEquals(kotlin.retreatCheck(c), w.rules.retreatCheck(c))
    assertEquals(kotlin.fleeChance(c), w.rules.fleeChance(c))
    assertEquals(kotlin.moveCost(c), w.rules.moveCost(c))
    assertEquals(kotlin.regen(c), w.rules.regen(c))
    assertEquals(kotlin.carryLimit(c), w.rules.carryLimit(c))
    // The fixture's level formula needs both weight fields, so it gets them set, as the engine sets
    // them.
    val levelC = RulesContext(w, a, b, band = Band.NEAR, limit = 100, carried = 50)
    assertEquals(kotlin.encumbranceLevel(levelC), w.rules.encumbranceLevel(levelC))
    // The template prices dex by the level the engine just computed, so the fixture mirrors that
    // instead of its flat over-the-limit penalty, and both are asked with the level set.
    kotlin.encumbranceOf = { ctx -> mapOf(kotlin.fs.agility to -(ctx.encumbranceLevel ?: 0)) }
    val encC =
      RulesContext(w, a, b, band = Band.NEAR, limit = 100, carried = 95, encumbranceLevel = 3)
    assertEquals(kotlin.encumbrance(encC), w.rules.encumbrance(encC))
    for (band in Band.entries) assertEquals(kotlin.getLag(c, band), w.rules.getLag(c, band))
    assertEquals(kotlin.advanceLag(c), w.rules.advanceLag(c))
    assertEquals(kotlin.retreatLag(c), w.rules.retreatLag(c))
    assertEquals(kotlin.retreatFailLag(c), w.rules.retreatFailLag(c))
    assertEquals(kotlin.fleeFailLag(c), w.rules.fleeFailLag(c))
    assertEquals(kotlin.xpForKill(c), w.rules.xpForKill(c))
    assertEquals(kotlin.xpToLevel(c, 4), w.rules.xpToLevel(c, 4))
    assertEquals(kotlin.onPlayerDeath(c), w.rules.onPlayerDeath(c))
    assertEquals(null, w.rules.castAllowed(c))
    assertTrue(w.diagnostics.isEmpty(), w.diagnostics.toString())
  }

  @Test
  fun `template world off-hand swings take a to_hit penalty`() {
    val w = loadTemplateWorld()
    val a = w.spawnPlayer("Ann", Label("//zones/tutorial:hall"))
    val b = w.spawnPlayer("Bob", Label("//zones/tutorial:hall"))
    val wield = w.rules.toHit(RulesContext(w, a, b, slot = "wield"))
    assertEquals(wield, w.rules.toHit(RulesContext(w, a, b)))
    assertEquals(wield - 10, w.rules.toHit(RulesContext(w, a, b, slot = "offhand")))
  }

  @Test
  fun `template world kill XP scales with level difference`() {
    val w = loadTemplateWorld()
    val killer3 = w.spawnPlayer("Killer3", Label("//zones/tutorial:hall"), level = 3)
    val victim3 = w.spawnPlayer("Victim3", Label("//zones/tutorial:hall"), level = 3)
    val killer6 = w.spawnPlayer("Killer6", Label("//zones/tutorial:hall"), level = 6)
    val victim1 = w.spawnPlayer("Victim1", Label("//zones/tutorial:hall"), level = 1)
    val victim5 = w.spawnPlayer("Victim5", Label("//zones/tutorial:hall"), level = 5)
    val killer1 = w.spawnPlayer("Killer1", Label("//zones/tutorial:hall"), level = 1)

    // 5 levels below pays 0 (diff = 1 - 6 = -5)
    assertEquals(0L, w.rules.xpForKill(RulesContext(w, killer6, victim1)))
    // equal pays 50 * level (diff = 3 - 3 = 0, pct = 100%, pays 50 * 3)
    assertEquals(150L, w.rules.xpForKill(RulesContext(w, killer3, victim3)))
    // 2 levels above pays 140% (diff = 5 - 3 = 2, pct = 140%, pays 50 * 5 * 140 / 100)
    assertEquals(350L, w.rules.xpForKill(RulesContext(w, killer3, victim5)))
    // 4 above pays 150% (diff = 5 - 1 = 4, pct = 150%, pays 50 * 5 * 150 / 100)
    assertEquals(375L, w.rules.xpForKill(RulesContext(w, killer1, victim5)))
  }

  @Test
  fun `template world xp_to_level gives 1000, 3000 and 6000, and is strictly increasing to level 50`() {
    val w = loadTemplateWorld()
    val p = w.spawnPlayer("Tester", Label("//zones/tutorial:hall"))
    val c = RulesContext(w, p)
    assertEquals(1000L, w.rules.xpToLevel(c, 1))
    assertEquals(3000L, w.rules.xpToLevel(c, 2))
    assertEquals(6000L, w.rules.xpToLevel(c, 3))
    var prev = 0L
    for (level in 1..50) {
      val curr = w.rules.xpToLevel(c, level)
      assertTrue(
        curr > prev,
        "level $level ($curr) must be strictly greater than level ${level - 1} ($prev)",
      )
      prev = curr
    }
  }

  @Test
  fun `template world dying with 1500 XP at level 2 loses 50, and dying at level 1 with 100 XP loses 10`() {
    val w = loadTemplateWorld()
    val p2 = w.spawnPlayer("P2", Label("//zones/tutorial:hall"), level = 2)
    p2.xp = 1500L
    val outcome2 = w.rules.onPlayerDeath(RulesContext(w, p2, p2))
    assertEquals(50L, outcome2.xpLoss)
    assertEquals(0, outcome2.levelLoss)

    val p1 = w.spawnPlayer("P1", Label("//zones/tutorial:hall"), level = 1)
    p1.xp = 100L
    val outcome1 = w.rules.onPlayerDeath(RulesContext(w, p1, p1))
    assertEquals(10L, outcome1.xpLoss)
    assertEquals(0, outcome1.levelLoss)
  }

  @Test
  fun `template world dying with XP below the level's floor loses nothing`() {
    val w = loadTemplateWorld()
    val p = w.spawnPlayer("P5", Label("//zones/tutorial:hall"), level = 5)
    assertEquals(0L, p.xp)
    assertEquals(0L, w.rules.onPlayerDeath(RulesContext(w, p, p)).xpLoss)
    assertTrue(w.diagnostics.isEmpty(), w.diagnostics.toString())
  }
}
