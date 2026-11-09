package gups.script

import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The world for the move_pace tests: one room, a pace-printing command, and a mob for the dry run.
 */
private const val ROOM: String =
  """
  def _speed(ctx):
      ctx.send(ctx.actor, "move_pace=" + str(ctx.actor.move_pace))

  room("r0", name = "R0", desc = "Room 0.", exits = [], commands = {"speed": _speed})
  mob("guard", name = "a guard", keywords = ["guard"], vitals = vitals(hp = 10))
  """

/** A room whose move_pace modifier halves the base. */
private const val ROOM_HALF: String =
  """
  def _speed(ctx):
      ctx.send(ctx.actor, "move_pace=" + str(ctx.actor.move_pace))

  def _half(ctx, base):
      return base // 2

  room("r0", name = "R0", desc = "Room 0.", exits = [], commands = {"speed": _speed}, modifiers = {MODIFIER.move_pace: _half})
  mob("guard", name = "a guard", keywords = ["guard"], vitals = vitals(hp = 10))
  """

class MovePaceRulesTest {
  private fun world(export: String? = null, room: String = ROOM): ScriptFixture =
    ScriptFixture(
      buildMap {
        put("zones/z/package.star", zonePkg())
        put("lib/package.star", libPkg())
        put("rules/package.star", TestWorlds.RULES_PACKAGE)
        put(
          "rules/combat.star",
          if (export == null) TestWorlds.rules() else TestWorlds.rules(export),
        )
        put("zones/z/a.star", room)
      },
      load = listOf("zones/z", "lib"),
    )

  private fun ScriptFixture.speed(): String {
    val p = player(at = "//zones/z:r0")
    input(p, "speed")
    run()
    return out(p)
  }

  @Test
  fun `an export returning 250 makes the view read 250`() {
    val f = world("def move_pace(ctx):\n    return 250")
    assertEquals("move_pace=250\n", f.speed())
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics("script_error").toString())
  }

  @Test
  fun `with no export the view reads 100`() {
    assertEquals("move_pace=100\n", world().speed())
  }

  @Test
  fun `a room modifier that halves a 250 base reads 125`() {
    val f = world("def move_pace(ctx):\n    return 250", room = ROOM_HALF)
    assertEquals("move_pace=125\n", f.speed())
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics("script_error").toString())
  }

  @Test
  fun `a non-int export is a load-time error naming move_pace`() {
    val f = world("def move_pace(ctx):\n    return \"x\"")
    assertTrue(!f.result.ok, "a non-int move_pace must fail the load dry run")
    assertTrue("move_pace" in f.result.report(), f.result.report())
  }

  @Test
  fun `a failing export is a load-time error naming move_pace`() {
    val f = world("def move_pace(ctx):\n    fail(\"x\")")
    assertTrue(!f.result.ok, "a failing move_pace must fail the load dry run")
    assertTrue("move_pace" in f.result.report(), f.result.report())
  }

  @Test
  fun `an export that reads its own view field does not recurse`() {
    val f = world("def move_pace(ctx):\n    return ctx.actor.move_pace + 50")
    assertEquals("move_pace=150\n", f.speed())
  }

  @Test
  fun `a non-int result at run time reads 100 and is diagnosed`() {
    // Valid for the dry run's mob; a bad value for the player.
    val f = world("def move_pace(ctx):\n    return \"x\" if ctx.actor.is_player else 250")
    assertEquals("move_pace=100\n", f.speed())
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(errors.any { "move_pace" in it }, errors.toString())
  }

  @Test
  fun `a failing call at run time reads 100 and is diagnosed`() {
    val f =
      world("def move_pace(ctx):\n    if ctx.actor.is_player:\n        fail(\"x\")\n    return 250")
    assertEquals("move_pace=100\n", f.speed())
    val errors = f.diagnostics().map { it.message }
    assertTrue(errors.any { "move_pace" in it }, errors.toString())
  }

  @Test
  fun `another rules export can read the move_pace view`() {
    // chain_think is asked after the pursuer's step, and reads the pace through the view.
    val f =
      world(
        "def move_pace(ctx):\n    return 250\n\ndef chain_think(ctx):\n    return ctx.actor.move_pace == 250"
      )
    assertTrue(f.result.ok, f.result.report())
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics("script_error").toString())
  }
}
