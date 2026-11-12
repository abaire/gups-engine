package gups.script

import gups.core.rules.RulesContext
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RulesLevelCurveTest : RulesTestBase() {
  @Test
  fun `an on_level_up that fails for a level-2 actor fails the rules package load`() {
    val result =
      loadWith(
        TestWorlds.rules(
          """
          def on_level_up(ctx):
              if ctx.actor.level == 2:
                  return struct(stats = stats(bogus = 1))
              return None
          """
        )
      )
    assertTrue("rules" in result.failed)
    val error = result.errors.single { it.kind == "rules" }
    assertContains(error.message, "unknown stat 'bogus'")
  }

  @Test
  fun `an xp_to_level that is not strictly increasing in 1 to 50 fails load`() {
    val result =
      loadWith(
        TestWorlds.rules(
          """
          def xp_to_level(ctx, level):
              if level == 50:
                  return 49000
              return 1000 * level
          """
        )
      )
    assertTrue("rules" in result.failed)
    val error = result.errors.single { it.kind == "rules" }
    assertContains(error.message, "xp_to_level")
    assertContains(error.message, "not strictly increasing")
  }

  @Test
  fun `the dry run fails a curve that strictly increases for one synthetic actor and not for another, naming the second`() {
    val result =
      loadWith(
        TestWorlds.rules(
          """
          def xp_to_level(ctx, level):
              if ctx.actor.name == "Dryrunfoe":
                  return 1000
              return 1000 * level
          """
        )
      )
    assertTrue("rules" in result.failed)
    val error = result.errors.single { it.kind == "rules" }
    assertContains(error.message, "Dryrunfoe")
    assertContains(error.message, "not strictly increasing")
    assertFalse(error.message.contains("Dryrunner"))
  }

  @Test
  fun `reading ctx rng, ctx roll or ctx pulse in xp_to_level fails load at dry run`() {
    for (field in listOf("ctx.rng", "ctx.roll('1d6')", "ctx.pulse")) {
      val result =
        loadWith(
          TestWorlds.rules(
            """
            def xp_to_level(ctx, level):
                x = $field
                return 1000 * level
            """
          )
        )
      assertTrue("rules" in result.failed, "expected failure for $field")
      val error = result.errors.single { it.kind == "rules" }
      assertContains(error.message, "not available in xp_to_level")
      assertContains(error.message, "xp_to_level must give the same answer every time it is asked")
    }

    val okResult =
      loadWith(
        TestWorlds.rules(
          """
          def on_level_up(ctx):
              r = ctx.rng
              d = ctx.roll("1d6")
              p = ctx.pulse
              return None
          """
        )
      )
    assertFalse("rules" in okResult.failed)
  }

  @Test
  fun `the dry run applies nothing and synthetic actors stats are unchanged after it`() {
    val result =
      loadWith(
        TestWorlds.rules(
          """
          def starting_stats(ctx):
              return struct(
                  stats = stats(str = 10, dex = 10, con = 10, int = 10, wis = 10, armor = 0),
                  max = vitals(hp = 20, mana = 20, move = 100),
              )

          def on_level_up(ctx):
              return struct(
                  stats = stats(str = 20),
                  max = vitals(hp = 50),
                  current = vitals(hp = 50),
              )

          def carry_limit(ctx):
              if ctx.actor.str != 10 or ctx.actor.level != 1 or ctx.actor.max_hp != 20:
                  fail("actor state modified in dry run: str=" + str(ctx.actor.str) + " hp=" + str(ctx.actor.max_hp) + " lvl=" + str(ctx.actor.level))
              return 100
          """
        )
      )
    assertTrue(result.ok, result.report())
  }

  @Test
  fun `starting_stats uses custom rng from RulesContext`() {
    val f =
      world(
        """
        def starting_stats(ctx):
            return struct(stats = stats(str = ctx.rng.int(100, 200)))
        """
      )
    val p = f.player()
    val customRng = gups.core.Rng(42L)
    val expected = gups.core.Rng(42L).int(100, 200)
    val stats = f.w.rules.startingStats(RulesContext(f.w, p, rngOverride = customRng))
    assertEquals(expected, stats.stats["str"])
  }
}
