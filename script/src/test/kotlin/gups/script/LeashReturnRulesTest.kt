package gups.script

import gups.core.Label
import gups.core.world.applyAffect
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The world for the leash_return tests: a six-room chain with leashed guard prototypes. */
private const val GUARD_ZONE: String =
  """
  room("r0", name = "R0", desc = "Room 0.", exits = [exit("east", ":r1")])
  room("r1", name = "R1", desc = "Room 1.", exits = [exit("west", ":r0"), exit("east", ":r2")])
  room("r2", name = "R2", desc = "Room 2.", exits = [exit("west", ":r1"), exit("east", ":r3")])
  room("r3", name = "R3", desc = "Room 3.", exits = [exit("west", ":r2"), exit("east", ":r4")])
  room("r4", name = "R4", desc = "Room 4.", exits = [exit("west", ":r3"), exit("east", ":r5")])
  room("r5", name = "R5", desc = "Room 5.", exits = [exit("west", ":r4")])
  mob("guard", name = "a guard", keywords = ["guard"], vitals = vitals(hp = 10), leash = 3)
  mob("guard2", name = "a guard", keywords = ["guard2"], vitals = vitals(hp = 10), leash = 2)
  """

/**
 * Spawns a leashed [label] at [at] whose home room is forced to [home], outside its home ground.
 */
private fun ScriptFixture.exile(label: String, at: String, home: String) =
  w.spawnMob(Label("//zones/z:$label"), w.room(Label("//zones/z:$at"))).also {
    it.homeRoom = Label(home)
  }

class LeashReturnRulesTest {
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
        put("zones/z/a.star", GUARD_ZONE)
      },
      load = listOf("zones/z", "lib"),
    )

  @Test
  fun `the mob lingers until its away time passes`() {
    val f = world("def leash_return(ctx):\n    return ctx.away >= 20")
    val m = f.exile("guard", "r5", "//zones/z:r0")
    f.run(12)
    assertEquals(Label("//zones/z:r5"), m.room.label, "has not moved after 12 pulses")
    f.run(300)
    assertEquals(Label("//zones/z:r0"), m.room.label, "walked home once away passed")
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics("script_error").toString())
  }

  @Test
  fun `a False from the export keeps the mob there indefinitely`() {
    val f = world("def leash_return(ctx):\n    return False")
    val m = f.exile("guard", "r5", "//zones/z:r0")
    f.run(300)
    assertEquals(Label("//zones/z:r5"), m.room.label, "never goes home")
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics("script_error").toString())
  }

  @Test
  fun `a flagged mob is held back while its unflagged twin goes home`() {
    val f = world("def leash_return(ctx):\n    return not ctx.actor.has_flag(\"stubborn\")")
    val s = f.exile("guard", "r2", "//zones/z:r0")
    val n = f.exile("guard", "r2", "//zones/z:r0")
    f.w.applyAffect(s, "stubborn", 1_000_000, flags = setOf("stubborn"))
    f.run(300)
    assertEquals(Label("//zones/z:r2"), s.room.label, "the stubborn mob stays")
    assertEquals(Label("//zones/z:r0"), n.room.label, "the unflagged mob goes home")
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics("script_error").toString())
  }

  @Test
  fun `past_leash is True past the leash and False merely outside the home ground`() {
    val f = world("def leash_return(ctx):\n    return not ctx.past_leash")
    // leash 2, standing 5 from home: outside the home ground and past the leash.
    val past = f.exile("guard2", "r5", "//zones/z:r0")
    // leash 3, standing 2 from home: outside the home ground but inside the leash.
    val mid = f.exile("guard", "r2", "//zones/z:r0")
    f.run(300)
    assertEquals(Label("//zones/z:r5"), past.room.label, "past the leash it is held back")
    assertEquals(Label("//zones/z:r0"), mid.room.label, "inside the leash it goes home")
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics("script_error").toString())
  }

  @Test
  fun `a world without the export sends the mob home at once`() {
    val f = world()
    val m = f.exile("guard", "r5", "//zones/z:r0")
    f.run(300)
    assertEquals(Label("//zones/z:r0"), m.room.label, "walked home")
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics("script_error").toString())
  }

  @Test
  fun `a non-bool return is a script error and the mob still goes home`() {
    // Valid at the load-time dry run (away = 0); a bad value once the mob is away.
    val f = world("def leash_return(ctx):\n    return True if ctx.away == 0 else 3")
    val m = f.exile("guard", "r5", "//zones/z:r0")
    f.run(300)
    assertEquals(Label("//zones/z:r0"), m.room.label, "the default True applies")
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(errors.any { "leash_return" in it && "must be a bool" in it }, errors.toString())
  }

  @Test
  fun `an error inside the export is diagnosed and the mob still goes home`() {
    // Valid at the load-time dry run (away = 0); fails once the mob is away.
    val f =
      world("def leash_return(ctx):\n    if ctx.away > 0:\n        fail(\"x\")\n    return True")
    val m = f.exile("guard", "r5", "//zones/z:r0")
    f.run(300)
    assertEquals(Label("//zones/z:r0"), m.room.label, "the default True applies")
    val errors = f.diagnostics().map { it.message }
    assertTrue(errors.any { "leash_return" in it }, errors.toString())
  }
}
