package gups.script

import gups.core.Label
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A seven-room chain, a pursuing mob, and a mob that walks east from its own think. */
private const val CHAIN_ZONE: String =
  """
  def _think(ctx):
      return ctx.act(ctx.self, "east")

  room("r0", name = "R0", desc = "Room 0.", exits = [exit("east", ":r1")])
  room("r1", name = "R1", desc = "Room 1.", exits = [exit("west", ":r0"), exit("east", ":r2")])
  room("r2", name = "R2", desc = "Room 2.", exits = [exit("west", ":r1"), exit("east", ":r3")])
  room("r3", name = "R3", desc = "Room 3.", exits = [exit("west", ":r2"), exit("east", ":r4")])
  room("r4", name = "R4", desc = "Room 4.", exits = [exit("west", ":r3"), exit("east", ":r5")])
  room("r5", name = "R5", desc = "Room 5.", exits = [exit("west", ":r4"), exit("east", ":r6")])
  room("r6", name = "R6", desc = "Room 6.", exits = [exit("west", ":r5")])
  mob("pursuer", name = "a pursuer", keywords = ["pursuer"], vitals = vitals(hp = 10),
      behaviors = [pursue("players")])
  mob("thinker", name = "a thinker", keywords = ["thinker"], vitals = vitals(hp = 10),
      think = _think, think_every = 4)
  """

class ChainThinkRulesTest {
  private fun world(export: String? = null): ScriptFixture =
    ScriptFixture(
      buildMap {
        put("zones/z/package.star", zonePkg())
        put("lib/package.star", libPkg())
        put("rules/package.star", TestWorlds.RULES_PACKAGE)
        put(
          "rules/combat.star",
          if (export == null) TestWorlds.rules() else TestWorlds.rules(export),
        )
        put("zones/z/a.star", CHAIN_ZONE)
      },
      load = listOf("zones/z", "lib"),
    )

  @Test
  fun `a chain limited to under 2 chains gives three moves, then the cadence`() {
    val f = world("def chain_think(ctx):\n    return ctx.chain < 2")
    f.player(at = "//zones/z:r6")
    val m = f.w.spawnMob(Label("//zones/z:pursuer"), f.w.room(Label("//zones/z:r0")))
    f.run(3)
    assertEquals(Label("//zones/z:r3"), m.room.label, "a think and two chained thinks")
    f.run(3)
    assertEquals(Label("//zones/z:r3"), m.room.label, "the third ask said no: back on its cadence")
  }

  @Test
  fun `a pursuer chains without the export`() {
    val f = world()
    f.player(at = "//zones/z:r6")
    val m = f.w.spawnMob(Label("//zones/z:pursuer"), f.w.room(Label("//zones/z:r0")))
    f.run(8)
    assertEquals(Label("//zones/z:r6"), m.room.label, "the unchained-by-any-rule pursuer caught up")
  }

  @Test
  fun `a non-bool return falls back to the default and is diagnosed`() {
    // Valid at the load-time dry run (chain = 0); a bad value once the chain has started.
    val f = world("def chain_think(ctx):\n    return True if ctx.chain == 0 else 3")
    f.player(at = "//zones/z:r6")
    val m = f.w.spawnMob(Label("//zones/z:pursuer"), f.w.room(Label("//zones/z:r0")))
    f.run(8)
    assertEquals(Label("//zones/z:r6"), m.room.label, "the default (chain) still applies")
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(errors.any { "chain_think" in it && "must be a bool" in it }, errors.toString())
  }

  @Test
  fun `a failing export is diagnosed and the default applies`() {
    // Valid at the load-time dry run (chain = 0); fails once the chain has started.
    val f =
      world("def chain_think(ctx):\n    if ctx.chain > 0:\n        fail(\"x\")\n    return True")
    f.player(at = "//zones/z:r6")
    val m = f.w.spawnMob(Label("//zones/z:pursuer"), f.w.room(Label("//zones/z:r0")))
    f.run(8)
    assertEquals(Label("//zones/z:r6"), m.room.label, "the default (chain) still applies")
    val errors = f.diagnostics().map { it.message }
    assertTrue(errors.any { "chain_think" in it }, errors.toString())
  }

  @Test
  fun `behavior reads think for an action the think chose`() {
    val f = world("def chain_think(ctx):\n    return ctx.behavior == \"think\"")
    val m = f.w.spawnMob(Label("//zones/z:thinker"), f.w.room(Label("//zones/z:r0")))
    f.run(2)
    assertEquals(Label("//zones/z:r2"), m.room.label, "it chained because behavior was \"think\"")
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics("script_error").toString())
  }
}
