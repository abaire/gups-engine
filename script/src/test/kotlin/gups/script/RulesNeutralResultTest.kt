package gups.script

import gups.core.Label
import gups.core.model.Band
import gups.core.model.DamageType
import gups.core.rules.PlayerDeathOutcome
import gups.core.rules.RulesContext
import gups.core.rules.StartingStats
import gups.core.world.World
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesNeutralResultTest : RulesTestBase() {
  @Test
  fun `a failing export gives its neutral result, a script_error and one rules_error alert`() {
    val f =
      world(
        """
            def damage(ctx):
                if ctx.attacker.level == 9:
                    return 1 // 0
                return 4

            def to_hit(ctx):
                if ctx.attacker.level == 9:
                    return "always"
                return 50
            """
      )
    val alerts = mutableListOf<String>()
    f.host.alerts = { kind, _ -> alerts += kind }
    val p = f.w.spawnPlayer("Nine", Label("//zones/z:start"), level = 9)
    val rat = f.mob("//zones/z:rat")
    val c = RulesContext(f.w, p, rat)
    assertEquals(0, f.w.rules.damage(c))
    assertContains(f.diagnostics("script_error").first().message, "division by zero")
    assertEquals(0, f.w.rules.toHit(c))
    assertContains(
      f.diagnostics("script_error").last().message,
      "rules.to_hit returned a bad value",
    )
    assertEquals(
      listOf("rules_error", "rules_error"),
      alerts,
      "one alert per failing function in the window",
    )
  }

  @Test
  fun `the rules package is never quarantined, however many errors it makes`() {
    val f =
      world(
        """
            def to_hit(ctx):
                if ctx.attacker.level == 9:
                    return 1 // 0
                return 50
            """
      )
    val p = f.w.spawnPlayer("Nine", Label("//zones/z:start"), level = 9)
    val rat = f.mob("//zones/z:rat")
    repeat(25) { assertEquals(0, f.w.rules.toHit(RulesContext(f.w, p, rat))) }
    assertTrue(f.host.quarantined.isEmpty())
    assertEquals(50, f.w.rules.toHit(RulesContext(f.w, rat, p)), "healthy calls still work")
  }

  @Test
  fun `every export gives its neutral result when it fails`() {
    class Case(
      val export: String,
      val signature: String,
      val valid: String,
      val check: (World, RulesContext) -> Unit,
    )

    val cases =
      listOf(
        Case("to_hit", "ctx", "50") { w, c -> assertEquals(0, w.rules.toHit(c)) },
        Case("retreat_check", "ctx", "50") { w, c -> assertEquals(0, w.rules.retreatCheck(c)) },
        Case("flee_chance", "ctx", "50") { w, c -> assertEquals(0, w.rules.fleeChance(c)) },
        Case("damage", "ctx", "3") { w, c -> assertEquals(0, w.rules.damage(c)) },
        Case("mitigate", "ctx, amount, type", "vitals(hp = amount)") { w, c ->
          assertEquals(emptyMap(), w.rules.mitigate(c, 5, DamageType("fire")))
        },
        Case("initiative", "ctx", "5") { w, c -> assertEquals(0, w.rules.initiative(c)) },
        Case("cast_allowed", "ctx", "None") { w, c ->
          assertEquals("Nothing happens.", w.rules.castAllowed(c))
        },
        Case("skill_check", "ctx", "80") { w, c -> assertEquals(0, w.rules.skillCheck(c)) },
        Case("skill_power", "ctx", "100") { w, c -> assertEquals(100, w.rules.skillPower(c)) },
        Case("item_power", "ctx", "100") { w, c -> assertEquals(100, w.rules.itemPower(c)) },
        Case("interrupt_check", "ctx, amount", "True") { w, c ->
          assertFalse(w.rules.interruptCheck(c, 5))
        },
        Case("move_cost", "ctx", "vitals(move = 1)") { w, c ->
          assertEquals(emptyMap(), w.rules.moveCost(c))
        },
        Case("regen", "ctx", "vitals(hp = 1)") { w, c ->
          assertEquals(emptyMap(), w.rules.regen(c))
        },
        Case("encumbrance_level", "ctx", "2") { w, c ->
          assertEquals(0, w.rules.encumbranceLevel(c))
        },
        Case("encumbrance", "ctx", "stats(dex = -2)") { w, c ->
          assertEquals(emptyMap(), w.rules.encumbrance(c))
        },
        Case("starting_stats", "ctx", "struct(max = vitals(hp = 20))") { w, c ->
          assertEquals(StartingStats(), w.rules.startingStats(c))
        },
        Case("carry_limit", "ctx", "100") { w, c ->
          assertEquals(Int.MAX_VALUE, w.rules.carryLimit(c))
        },
        Case("get_lag", "ctx, band", "0") { w, c -> assertEquals(0, w.rules.getLag(c, Band.NEAR)) },
        Case("advance_lag", "ctx", "3") { w, c -> assertEquals(0, w.rules.advanceLag(c)) },
        Case("retreat_lag", "ctx", "3") { w, c -> assertEquals(0, w.rules.retreatLag(c)) },
        Case("retreat_fail_lag", "ctx", "3") { w, c -> assertEquals(0, w.rules.retreatFailLag(c)) },
        Case("flee_fail_lag", "ctx", "3") { w, c -> assertEquals(0, w.rules.fleeFailLag(c)) },
        Case("condition", "ctx", "'ok'") { w, c ->
          assertEquals(c.requireActor().condition, w.rules.condition(c))
        },
        Case("xp_for_kill", "ctx", "10") { w, c -> assertEquals(0L, w.rules.xpForKill(c)) },
        Case("on_level_up", "ctx", "None") { w, c -> assertNull(w.rules.onLevelUp(c)) },
        Case("on_player_death", "ctx", "{'respawn': ctx.actor.recall_room}") { w, c ->
          assertEquals(
            PlayerDeathOutcome(Label("//zones/z:start"), null, 0),
            w.rules.onPlayerDeath(c),
          )
        },
      )
    for (case in cases) {
      val args = case.signature
      val f =
        world(
          """
              def ${case.export}($args):
                  if ctx.actor.level == 9:
                      return 1 // 0
                  return ${case.valid}
              """
        )
      val alerts = mutableListOf<String>()
      f.host.alerts = { kind, _ -> alerts += kind }
      val p = f.w.spawnPlayer("Nine", Label("//zones/z:start"), level = 9)
      val rat = f.mob("//zones/z:rat")
      case.check(f.w, RulesContext(f.w, p, rat, band = Band.NEAR))
      assertTrue(
        f.diagnostics("script_error").isNotEmpty(),
        "${case.export}: a script_error diagnostic",
      )
      assertEquals(listOf("rules_error"), alerts, "${case.export}: a rules_error alert")
      assertTrue(f.host.quarantined.isEmpty(), "${case.export}: never quarantined")
    }
  }
}
