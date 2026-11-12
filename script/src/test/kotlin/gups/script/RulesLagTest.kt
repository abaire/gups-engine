package gups.script

import gups.core.Label
import gups.core.model.Band
import gups.core.model.ItemLocation
import gups.core.rules.RulesContext
import gups.core.world.moveItem
import gups.core.world.spawnItem
import gups.core.world.startCombat
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulesLagTest : RulesTestBase() {
  // ---- track-movement lags --------------------------------------------------------------------

  /** A large room with a tag, an exit that a hook can refuse, and a rat. */
  private val LAG_ROOMS =
    """
        def _refuse(ctx):
            return ctx.deny("The door sticks.")

        room("start", size = SIZE.large, tags = ["slick"], exits = [exit("north", ":hall")],
             before_exit = _refuse)
        room("hall")
        mob("rat", vitals = vitals(hp = 100))
        """

  private fun quietCombat(fleeChance: Int) =
    """
        def to_hit(ctx):
            return 0

        def retreat_check(ctx):
            return 0

        def flee_chance(ctx):
            return $fleeChance
        """

  private fun lagWorld(vararg exports: String, fleeChance: Int = 100) =
    world(
      (listOf(quietCombat(fleeChance)) + exports).joinToString("\n\n") { it.trimIndent() },
      LAG_ROOMS,
    )

  private fun ScriptFixture.rat(index: Int = 0) =
    w.spawnMob(Label("//zones/z:rat"), w.room(Label("//zones/z:start")), index)

  private fun ScriptFixture.act(p: gups.core.model.Player, line: String) {
    input(p, line)
    run()
  }

  private val DISTINCT_LAGS =
    arrayOf(
      "def advance_lag(ctx):\n    return 7",
      "def retreat_lag(ctx):\n    return 5",
      "def retreat_fail_lag(ctx):\n    return 9",
      "def flee_fail_lag(ctx):\n    return 11",
    )

  @Test
  fun `advance leaves the lag advance_lag returns`() {
    val f = lagWorld(*DISTINCT_LAGS)
    val p = f.player()
    f.rat(index = 2)
    f.act(p, "advance rat")
    assertEquals(1, p.trackIndex)
    assertEquals(7, p.waitState)
  }

  @Test
  fun `a successful retreat leaves the lag retreat_lag returns`() {
    val f = lagWorld(*DISTINCT_LAGS)
    val p = f.player()
    f.rat()
    f.act(p, "retreat from rat")
    assertEquals(1, p.trackIndex)
    assertEquals(5, p.waitState)
  }

  @Test
  fun `a failed retreat leaves the lag retreat_fail_lag returns`() {
    val f = lagWorld(*DISTINCT_LAGS)
    val p = f.player()
    f.w.startCombat(p, f.rat())
    f.act(p, "retreat")
    assertEquals(0, p.trackIndex)
    assertEquals(9, p.waitState)
  }

  @Test
  fun `a failed flee roll leaves the lag flee_fail_lag returns`() {
    val f = lagWorld(*DISTINCT_LAGS, fleeChance = 0)
    val p = f.player()
    f.w.startCombat(p, f.rat())
    f.act(p, "flee")
    assertEquals(11, p.waitState)
  }

  @Test
  fun `a flee refused by before_exit leaves the lag flee_fail_lag returns`() {
    val f = lagWorld(*DISTINCT_LAGS)
    val p = f.player()
    f.w.startCombat(p, f.rat())
    f.act(p, "flee")
    assertContains(f.out(p), "The door sticks.")
    assertEquals("//zones/z:start", p.room.label.v)
    assertEquals(11, p.waitState)
  }

  @Test
  fun `a lag of 0 leaves no recovery`() {
    val f =
      lagWorld(
        "def advance_lag(ctx):\n    return 0",
        "def retreat_lag(ctx):\n    return 0",
        "def retreat_fail_lag(ctx):\n    return 0",
        "def flee_fail_lag(ctx):\n    return 0",
        fleeChance = 0,
      )
    val p = f.player()
    val rat = f.rat(index = 2)
    f.act(p, "advance rat")
    assertEquals(0, p.waitState)
    f.act(p, "retreat from rat")
    assertEquals(0, p.waitState)
    f.w.startCombat(p, rat)
    f.act(p, "flee")
    assertEquals(0, p.waitState)
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `advance_lag sees the approached actor and the band after the step`() {
    val f =
      lagWorld(
        """
        def advance_lag(ctx):
            who = 1 if ctx.target.label.endswith(":rat") and ctx.actor.name == "Ann" else 99
            return who + (10 if ctx.band == "melee" else 20)
        """
      )
    val p = f.player()
    f.rat(index = 2)
    f.act(p, "advance rat")
    assertEquals(21, p.waitState, "two apart on a large track: one step leaves them near")
    f.run(25)
    f.act(p, "advance rat")
    assertEquals(11, p.waitState, "the step that closes the gap ends in melee")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `retreat_lag sees the threat and the band after the step`() {
    val f =
      lagWorld(
        """
        def retreat_lag(ctx):
            who = 1 if ctx.target.label.endswith(":rat") and ctx.actor.name == "Ann" else 99
            return who + (10 if ctx.band == "melee" else 20)
        """
      )
    val p = f.player()
    f.rat()
    f.act(p, "retreat from rat")
    assertEquals(21, p.waitState)
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `retreat_fail_lag sees the pinner and melee`() {
    val f =
      lagWorld(
        """
        def retreat_fail_lag(ctx):
            who = 1 if ctx.target.label.endswith(":rat") and ctx.actor.name == "Ann" else 99
            return who + (10 if ctx.band == "melee" else 20)
        """
      )
    val p = f.player()
    f.w.startCombat(p, f.rat())
    f.act(p, "retreat")
    assertEquals(11, p.waitState)
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `flee_fail_lag sees no target and no band`() {
    val f =
      lagWorld(
        """
        def flee_fail_lag(ctx):
            return 6 if ctx.target == None and ctx.band == None and ctx.actor.name == "Ann" else 99
        """,
        fleeChance = 0,
      )
    val p = f.player()
    f.w.startCombat(p, f.rat())
    f.act(p, "flee")
    assertEquals(6, p.waitState)
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `a lag export can read the room tags`() {
    val f =
      lagWorld(
        """
        def advance_lag(ctx):
            return 8 if "slick" in ctx.room.tags else 3
        """
      )
    val p = f.player()
    f.rat(index = 2)
    f.act(p, "advance rat")
    assertEquals(8, p.waitState)
  }

  @Test
  fun `an item's advance_lag and retreat_lag modifiers change the lag`() {
    val f =
      world(
        (listOf(quietCombat(100)) + DISTINCT_LAGS).joinToString("\n\n") { it.trimIndent() },
        """
        def _quick(ctx, base):
            return base - 3

        room("start", size = SIZE.large)
        mob("rat", vitals = vitals(hp = 100))
        item("boots", wear = "wield", modifiers = {"advance_lag": _quick, "retreat_lag": _quick})
        """,
      )
    val p = f.player()
    val boots = f.w.spawnItem(Label("//zones/z:boots"), ItemLocation.Carried(p.id))
    f.w.moveItem(boots, ItemLocation.Equipped(p.id, "wield"))
    f.rat(index = 2)
    f.act(p, "advance rat")
    assertEquals(1, p.trackIndex)
    assertEquals(7 - 3, p.waitState)
    f.run(10)
    f.act(p, "retreat from rat")
    assertEquals(0, p.trackIndex)
    assertEquals(5 - 3, p.waitState)
  }

  @Test
  fun `a room's flee_fail_lag modifier changes a failed flee's lag`() {
    val f =
      world(
        (listOf(quietCombat(fleeChance = 0)) + DISTINCT_LAGS).joinToString("\n\n") {
          it.trimIndent()
        },
        """
        def _slow(ctx, base):
            return base + 2

        room("start", size = SIZE.large, exits = [exit("north", ":hall")], modifiers = {"flee_fail_lag": _slow})
        room("hall")
        mob("rat", vitals = vitals(hp = 100))
        """,
      )
    val p = f.player()
    f.w.startCombat(p, f.rat())
    f.act(p, "flee")
    assertEquals(11 + 2, p.waitState)
  }

  @Test
  fun `a lag chain that goes below 0 leaves no recovery`() {
    val f =
      world(
        (listOf(quietCombat(100)) + DISTINCT_LAGS).joinToString("\n\n") { it.trimIndent() },
        """
        def _quick(ctx, base):
            return base - 100

        def _boost(ctx):
            ctx.apply_affect(ctx.actor, "speed", 100, modifiers = {"retreat_fail_lag": _quick})

        room("start", size = SIZE.large, commands = {"boost": _boost})
        mob("rat", vitals = vitals(hp = 100))
        """,
      )
    val p = f.player()
    f.act(p, "boost")
    f.w.startCombat(p, f.rat())
    f.act(p, "retreat")
    assertEquals(0, p.trackIndex)
    assertEquals(0, p.waitState)
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  /** Sets up and triggers the action that calls [export], returning the actor. */
  private fun triggerLag(f: ScriptFixture, export: String): gups.core.model.Player {
    val p = f.player()
    when (export) {
      "advance_lag" -> {
        f.rat(index = 2)
        f.act(p, "advance rat")
      }
      "retreat_lag" -> {
        f.rat()
        f.act(p, "retreat from rat")
      }
      "retreat_fail_lag" -> {
        f.w.startCombat(p, f.rat())
        f.act(p, "retreat")
      }
      else -> {
        f.w.startCombat(p, f.rat())
        f.act(p, "flee")
      }
    }
    return p
  }

  @Test
  fun `a bad lag gives no recovery, one script_error and a rules_error alert`() {
    val bad =
      mapOf(
        "a negative value" to "return -1",
        "a wrong type" to "return 'long'",
        "a failure" to "fail('no lag today')",
      )
    for (export in listOf("advance_lag", "retreat_lag", "retreat_fail_lag", "flee_fail_lag")) {
      for ((what, body) in bad) {
        val f =
          lagWorld(
            "def $export(ctx):\n    if ctx.actor.name == \"Ann\":\n        $body\n    return 1",
            fleeChance = if (export == "flee_fail_lag") 0 else 100,
          )
        val alerts = mutableListOf<String>()
        f.host.alerts = { kind, _ -> alerts += kind }
        val p = triggerLag(f, export)
        assertEquals(0, p.waitState, "$export with $what")
        val errors = f.diagnostics("script_error")
        assertEquals(1, errors.size, "$export with $what: ${f.diagnostics()}")
        assertEquals("rules", errors.single().pkg, "$export with $what")
        assertEquals(listOf("rules_error"), alerts, "$export with $what")
      }
    }
  }

  @Test
  fun `a negative lag names the export in its message`() {
    val f =
      lagWorld(
        "def advance_lag(ctx):\n    if ctx.actor.name == \"Ann\":\n        return -3\n    return 1"
      )
    triggerLag(f, "advance_lag")
    assertContains(f.diagnostics("script_error").single().message, "must be at least 0")
  }

  @Test
  fun `get_lag rejects a negative value with its neutral result`() {
    val f =
      world(
        "def get_lag(ctx, band):\n    return -2 if band == 'near' and ctx.actor.name == 'Ann' else None"
      )
    val alerts = mutableListOf<String>()
    f.host.alerts = { kind, _ -> alerts += kind }
    val p = f.player()
    val c = RulesContext(f.w, p, band = Band.NEAR)
    assertEquals(0, f.w.rules.getLag(c, Band.NEAR))
    val error = f.diagnostics("script_error").single()
    assertContains(error.message, "must be at least 0")
    assertEquals("rules", error.pkg)
    assertEquals(listOf("rules_error"), alerts)
    assertEquals(null, f.w.rules.getLag(c, Band.FAR), "None is still out of reach")
    assertEquals(1, f.diagnostics("script_error").size)
  }

  @Test
  fun `the dry run fails a track lag that fails only at melee`() {
    val result =
      loadWith(
        TestWorlds.rules(
          "def advance_lag(ctx):\n    if ctx.band == 'melee':\n        return 1 // 0\n    return 2\n"
        )
      )
    assertTrue("rules" in result.failed)
    assertContains(result.errors.single { it.kind == "rules" }.message, "advance_lag")
  }

  @Test
  fun `the dry run gives flee_fail_lag no target`() {
    val result =
      loadWith(TestWorlds.rules("def flee_fail_lag(ctx):\n    return len(ctx.target.name)\n"))
    assertTrue("rules" in result.failed)
    assertContains(result.errors.single { it.kind == "rules" }.message, "flee_fail_lag")
  }

  @Test
  fun `the dry run gives each track lag the other actor at melee`() {
    val result =
      loadWith(
        TestWorlds.rules(
          """
          def advance_lag(ctx):
              return 1 + 0 * len(ctx.target.name)

          def retreat_lag(ctx):
              return 1 if ctx.band == "melee" and ctx.target.name != ctx.actor.name else -1

          def retreat_fail_lag(ctx):
              return 1 + 0 * len(ctx.target.name)
          """
        )
      )
    assertTrue(result.ok, result.report())
  }
}
