package gups.script

import gups.core.Label
import gups.core.rules.LevelUpResult
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesLevelTest : RulesTestBase() {
  @Test
  fun `xp_to_level fails neutrally with no level-up`() {
    val f =
      world(
        """
            def xp_to_level(ctx, level):
                if level == 99:
                    return 1 // 0
                return 1000 * level
            """
      )
    val p = f.player()
    val c = RulesContext(f.w, p)
    assertEquals(Long.MAX_VALUE, f.w.rules.xpToLevel(c, 99))
    assertEquals(2000L, f.w.rules.xpToLevel(c, 2))
  }

  @Test
  fun `on_level_up returning a struct with an undeclared name is a rules error that gives no gains`() {
    val f =
      world(
        """
        def on_level_up(ctx):
            if ctx.actor.level == 9:
                return struct(stats = stats(bogus = 1), max = vitals(hp = 5))
            return None
        """
      )
    val alerts = mutableListOf<String>()
    f.host.alerts = { kind, _ -> alerts += kind }
    val p = f.w.spawnPlayer("Nine", Label("//zones/z:start"), level = 9)
    val result = f.w.rules.onLevelUp(RulesContext(f.w, p))
    assertNull(result)
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(errors.any { "unknown stat 'bogus'" in it }, errors.toString())
    assertEquals(listOf("rules_error"), alerts)
  }

  @Test
  fun `on_level_up returning a struct with an unknown field is a rules error that gives no gains`() {
    val f =
      world(
        """
        def on_level_up(ctx):
            if ctx.actor.level == 9:
                return struct(unknown_field = 123)
            return None
        """
      )
    val alerts = mutableListOf<String>()
    f.host.alerts = { kind, _ -> alerts += kind }
    val p = f.w.spawnPlayer("Nine", Label("//zones/z:start"), level = 9)
    val ctx = RulesContext(f.w, p)
    val result = f.w.rules.onLevelUp(ctx)
    assertNull(result)
    assertTrue(f.diagnostics("script_error").isNotEmpty())
    assertEquals(listOf("rules_error"), alerts)
  }

  @Test
  fun `on_level_up parses valid struct and None`() {
    val f =
      world(
        """
        def on_level_up(ctx):
            if ctx.actor.level == 2:
                return struct(
                    stats = stats(str = 1),
                    max = vitals(hp = 5),
                    current = vitals(hp = 5),
                )
            return None
        """
      )
    val p = f.player()
    p.level = 2
    val res2 = f.w.rules.onLevelUp(RulesContext(f.w, p))
    assertEquals(
      LevelUpResult(stats = mapOf("str" to 1), max = mapOf("hp" to 5), current = mapOf("hp" to 5)),
      res2,
    )
    p.level = 3
    val res3 = f.w.rules.onLevelUp(RulesContext(f.w, p))
    assertNull(res3)
  }

  @Test
  fun `a negative level_loss is a bad return value`() {
    val f =
      world(
        // Only a level-9 actor gets the bad value, so the load-time dry run (level 1) still passes.
        """
        def on_player_death(ctx):
            if ctx.actor.level == 9:
                return {"respawn": ctx.actor.recall_room, "level_loss": -1}
            return {"respawn": ctx.actor.recall_room}
        """
      )
    val alerts = mutableListOf<String>()
    f.host.alerts = { kind, _ -> alerts += kind }
    val p = f.w.spawnPlayer("Nine", Label("//zones/z:start"), level = 9)
    val outcome = f.w.rules.onPlayerDeath(RulesContext(f.w, p))
    assertEquals(0, outcome.levelLoss)
    assertEquals(p.recallRoom, outcome.respawn)
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(
      errors.any { "rules.on_player_death returned a bad value" in it && "level_loss" in it },
      errors.toString(),
    )
    assertEquals(listOf("rules_error"), alerts)
  }

  @Test
  fun `a negative xp_loss is a bad return value`() {
    val f =
      world(
        // Only a level-9 actor gets the bad value, so the load-time dry run (level 1) still passes.
        """
        def on_player_death(ctx):
            if ctx.actor.level == 9:
                return {"respawn": ctx.actor.recall_room, "xp_loss": -1}
            return {"respawn": ctx.actor.recall_room}
        """
      )
    val alerts = mutableListOf<String>()
    f.host.alerts = { kind, _ -> alerts += kind }
    val p = f.w.spawnPlayer("Nine", Label("//zones/z:start"), level = 9)
    val outcome = f.w.rules.onPlayerDeath(RulesContext(f.w, p))
    assertEquals(0L, outcome.xpLoss)
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(
      errors.any { "rules.on_player_death returned a bad value" in it && "xp_loss" in it },
      errors.toString(),
    )
    assertEquals(listOf("rules_error"), alerts)
  }

  @Test
  fun `a negative skill_power falls back to 100 with a diagnostic`() {
    val f =
      world(
        """
        def skill_power(ctx):
            if ctx.actor.level == 9:
                return -10
            return 100
        """
      )
    val alerts = mutableListOf<String>()
    f.host.alerts = { kind, _ -> alerts += kind }
    val p = f.w.spawnPlayer("Nine", Label("//zones/z:start"), level = 9)
    val power = f.w.rules.skillPower(RulesContext(f.w, p))
    assertEquals(100, power)
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(
      errors.any { "rules.skill_power returned a bad value" in it && "must be at least 0" in it },
      errors.toString(),
    )
    assertEquals(listOf("rules_error"), alerts)
  }

  @Test
  fun `on_player_death parses level_loss`() {
    val f =
      world(
        """
        def on_player_death(ctx):
            return {"respawn": ctx.actor.recall_room, "level_loss": 3}
        """
      )
    val p = f.player()
    val outcome = f.w.rules.onPlayerDeath(RulesContext(f.w, p))
    assertEquals(3, outcome.levelLoss)
    assertEquals(p.recallRoom, outcome.respawn)
  }
}
