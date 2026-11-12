package gups.script

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.rules.RulesContext
import gups.core.world.damageChain
import gups.core.world.itemPowerChain
import gups.core.world.skillPowerChain
import gups.core.world.spawnItem
import gups.script.testing.TestWorlds
import gups.script.testing.tempWorld
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RulesDryRunTest : RulesTestBase() {
  @Test
  fun `a missing export fails the rules package, naming each one`() {
    val result = loadWith("def to_hit(ctx):\n    return 100\n")
    assertTrue("rules" in result.failed)
    val messages = result.errors.filter { it.pkg == "rules" }.map { it.message }
    assertTrue(messages.any { "must define damage(...)" in it }, messages.toString())
    assertTrue(messages.any { "must define item_power(...)" in it }, messages.toString())
    assertTrue(messages.any { "must define starting_stats(...)" in it }, messages.toString())
    assertTrue(messages.any { "must define slot_families(...)" in it }, messages.toString())
    assertTrue(messages.any { "must define actor_slots(...)" in it }, messages.toString())
    assertTrue(messages.any { "must define slot_cost(...)" in it }, messages.toString())
    assertTrue(messages.none { "to_hit" in it })
  }

  @Test
  fun `a ruleset without encumbrance_level fails to load`() {
    // The template rules, with only its encumbrance_level def dropped.
    val base = TestWorlds.rules().lines()
    val start = base.indexOfFirst { it.startsWith("def encumbrance_level(") }
    assertTrue(start >= 0, "the template rules define encumbrance_level")
    val end = (start + 1 until base.size).firstOrNull { base[it].startsWith("def ") } ?: base.size
    val without = (base.subList(0, start) + base.subList(end, base.size)).joinToString("\n")
    val result = loadWith(without)
    assertTrue("rules" in result.failed)
    val messages = result.errors.filter { it.pkg == "rules" }.map { it.message }
    assertTrue(messages.any { "must define encumbrance_level(...)" in it }, messages.toString())
  }

  @Test
  fun `encumbrance_level reads the limit and the carried weight set for it`() {
    val f =
      world(
        """
            def encumbrance_level(ctx):
                # The limit is carry_limit (str * 10); carried is the weight carried and worn.
                return ctx.limit * 1000 + ctx.carried if ctx.carried else 0
            """,
        DEFAULT_ROOMS,
        "zones/z/items.star" to "item('sack', weight = 60)\n",
      )
    val p = f.player()
    assertEquals(0, p.encumbranceLevel, "empty hands carry no level")
    f.w.spawnItem(Label("//zones/z:sack"), ItemLocation.Carried(p.id))
    assertEquals(100 * 1000 + 60, p.encumbranceLevel, "the level encodes ctx.limit and ctx.carried")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `encumbrance reads the level set for it`() {
    val f =
      world(
        """
            def encumbrance_level(ctx):
                return 1 if ctx.carried > ctx.limit else 0

            def encumbrance(ctx):
                # The dry run asks without the level set, so the level defaults to 0 there.
                return stats(dex = -3 * (ctx.encumbrance_level or 0))
            """,
        DEFAULT_ROOMS,
        "zones/z/items.star" to "item('sack', weight = 120)\n",
      )
    val p = f.player()
    assertEquals(10, p.stat("dex"))
    f.w.spawnItem(Label("//zones/z:sack"), ItemLocation.Carried(p.id))
    assertEquals(1, p.encumbranceLevel)
    assertEquals(10 - 3, p.stat("dex"), "dex loses 3 per level, from ctx.encumbrance_level")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `a weight field read in an export that does not set it fails the dry run`() {
    val result =
      loadWith(TestWorlds.rules("def move_cost(ctx):\n    return vitals(move = ctx.limit + 1)\n"))
    assertTrue("rules" in result.failed)
    val error = result.errors.single { it.kind == "rules" }
    assertContains(error.message, "rules.move_cost failed")
    assertContains(error.message, "NoneType")
  }

  @Test
  fun `a negative encumbrance_level is a bad return value`() {
    val f =
      world(
        // Only a level-9 actor gets the bad value, so the load-time dry run (level 1) still passes.
        """
        def encumbrance_level(ctx):
            if ctx.actor.level == 9:
                return -3
            return 0
        """
      )
    val alerts = mutableListOf<String>()
    f.host.alerts = { kind, _ -> alerts += kind }
    val p = f.w.spawnPlayer("Nine", Label("//zones/z:start"), level = 9)
    assertEquals(0, f.w.rules.encumbranceLevel(RulesContext(f.w, p)))
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(
      errors.any {
        "rules.encumbrance_level returned a bad value" in it && "must be at least 0" in it
      },
      errors.toString(),
    )
    assertEquals(listOf("rules_error"), alerts)
  }

  @Test
  fun `the actor view shows the stored level and 0 while encumbrance_level runs`() {
    val f =
      world(
        """
            def encumbrance_level(ctx):
                # 99 would mean the stored level was still set while this rule ran.
                if ctx.actor.encumbrance_level > 0:
                    return 99
                return 1 if ctx.carried > ctx.limit else 0

            def move_cost(ctx):
                return vitals(move = 1 + ctx.actor.encumbrance_level)
            """,
        DEFAULT_ROOMS,
        "zones/z/items.star" to "item('sack', weight = 120)\n",
      )
    val p = f.player()
    val c = RulesContext(f.w, p)
    assertEquals(1, f.w.rules.moveCost(c)["move"], "no load, no level, base move cost")
    f.w.spawnItem(Label("//zones/z:sack"), ItemLocation.Carried(p.id))
    assertEquals(1, p.encumbranceLevel, "99 would mean the rule saw its own previous answer")
    assertEquals(2, f.w.rules.moveCost(c)["move"], "the move cost reads the stored level")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  private fun slotFamiliesError(families: String): LoadError {
    val result = loadWith(TestWorlds.rules("def slot_families():\n    return $families\n"))
    assertTrue("rules" in result.failed)
    val error = result.errors.single { it.kind == "rules" }
    assertContains(error.file.orEmpty(), "combat.star")
    return error
  }

  @Test
  fun `the dry run catches a family colliding with a slot`() {
    assertContains(
      slotFamiliesError("{'head': ['head']}").message,
      "rules.slot_families: family 'head' has the name of a slot in equipment_slots()",
    )
  }

  @Test
  fun `the dry run catches an invalid family name`() {
    assertContains(
      slotFamiliesError("{'Ring': ['finger_l']}").message,
      "rules.slot_families: family 'Ring' is not a valid family name",
    )
  }

  @Test
  fun `the dry run catches an unknown slot in a family`() {
    assertContains(
      slotFamiliesError("{'ring': ['finger_l', 'finger_rr']}").message,
      "family 'ring' lists 'finger_rr', which is not in equipment_slots() (did you mean 'finger_r'?)",
    )
  }

  @Test
  fun `the dry run catches a slot listed twice in a family`() {
    assertContains(
      slotFamiliesError("{'ring': ['finger_l', 'finger_l']}").message,
      "family 'ring' lists slot 'finger_l' more than once",
    )
  }

  @Test
  fun `the dry run catches a slot in two families`() {
    assertContains(
      slotFamiliesError("{'ring_a': ['finger_l'], 'ring_b': ['finger_l']}").message,
      "slot 'finger_l' is in both 'ring_a' and 'ring_b'",
    )
  }

  @Test
  fun `the dry run catches an empty family`() {
    assertContains(slotFamiliesError("{'ring': []}").message, "family 'ring' lists no slots")
  }

  @Test
  fun `bad slot families don't blame the items worn in them`() {
    val root =
      tempWorld(
        mapOf(
          rulesPkg,
          "rules/combat.star" to
            TestWorlds.rules("def slot_families():\n    return {'head': ['head']}\n"),
          "zones/z/package.star" to zonePkg(),
          "zones/z/items.star" to "item('band', wear = 'ring')\n",
          "zones/z/rooms.star" to DEFAULT_ROOMS,
        )
      )
    val result = ScriptLoader(WorldDir(root), ScriptLimits.HARNESS).load(listOf("zones/z", "rules"))
    assertTrue("rules" in result.failed)
    assertTrue(result.errors.none { it.kind == "slot" }, result.report())
  }

  @Test
  fun `the dry run catches actor_slots returning an unknown slot`() {
    val result =
      loadWith(
        TestWorlds.rules(
          """
          def actor_slots(ctx):
              return ['head', 'wings']
          """
            .trimIndent()
        )
      )
    assertTrue("rules" in result.failed)
    val messages = result.errors.filter { it.kind == "rules" }.map { it.message }
    assertTrue(
      messages.any {
        "rules.actor_slots for player Dryrunner returned 'wings', which is not in equipment_slots()" in
          it
      },
      messages.toString(),
    )
    assertTrue(
      messages.any { "rules.actor_slots for mob //zones/z:rat returned 'wings'" in it },
      messages.toString(),
    )
  }

  @Test
  fun `the dry run reports the first slot_cost failure with its item and actor`() {
    val root =
      tempWorld(
        mapOf(
          rulesPkg,
          "rules/combat.star" to TestWorlds.rules("def slot_cost(ctx):\n    return 0\n"),
          "zones/z/package.star" to zonePkg(),
          "zones/z/items.star" to "item('sword', wear = 'wield')\nitem('helm', wear = 'head')\n",
          "zones/z/rooms.star" to DEFAULT_ROOMS,
        )
      )
    val result = ScriptLoader(WorldDir(root), ScriptLimits.HARNESS).load(listOf("zones/z", "rules"))
    assertTrue("rules" in result.failed)
    val error = result.errors.single { it.kind == "rules" }
    assertContains(error.message, "rules.slot_cost must be at least 1, got 0")
    assertContains(error.message, "worn by player Dryrunner)")
    assertContains(error.file.orEmpty(), "combat.star")
  }

  @Test
  fun `the dry run catches a misspelled vital in mitigate`() {
    val result =
      loadWith(
        TestWorlds.rules("def mitigate(ctx, amount, type):\n    return vitals(hpp = amount)\n")
      )
    assertTrue("rules" in result.failed)
    val error = result.errors.single { it.kind == "rules" }
    assertContains(error.message, "unknown vital 'hpp'")
    assertContains(error.message, "did you mean 'hp'")
  }

  @Test
  fun `a room skill_power modifier adding 20 gives 120`() {
    val f =
      world(
        "",
        """
        def _boost(ctx, base):
            return base + 20

        room("start", modifiers = {"skill_power": _boost})
        skill("spark")
        """
          .trimIndent(),
      )
    val p = f.player()
    val spark = f.w.protos.skills.getValue(Label("//zones/z:spark"))
    val c = RulesContext(f.w, p, skill = spark)
    assertEquals(120, f.w.skillPowerChain(c))
  }

  @Test
  fun `a modifier chain driving item_power to minus 30 gives 0`() {
    val f =
      world(
        "",
        """
        def _drain(ctx, base):
            return base - 130

        room("start", modifiers = {"item_power": _drain})
        item("wand")
        """
          .trimIndent(),
      )
    val p = f.player()
    val wand = f.w.spawnItem(Label("//zones/z:wand"), ItemLocation.Carried(p.id))
    val c = RulesContext(f.w, p, item = wand, event = "use")
    assertEquals(0, f.w.itemPowerChain(c))
  }

  @Test
  fun `the dry run catches a wrong return type in regen`() {
    val result = loadWith(TestWorlds.rules("def regen(ctx):\n    return {'hp': 1}\n"))
    assertTrue("rules" in result.failed)
    assertContains(result.errors.single { it.kind == "rules" }.message, "use vitals(...)")
  }

  @Test
  fun `the dry run catches a wrong return type in skill_power`() {
    val result = loadWith(TestWorlds.rules("def skill_power(ctx):\n    return 'strong'\n"))
    assertTrue("rules" in result.failed)
    assertContains(
      result.errors.single { it.kind == "rules" }.message,
      "rules.skill_power returned a bad value",
    )
  }

  @Test
  fun `a rules package that fails its dry run leaves no rules for the world`() {
    val result = loadWith(TestWorlds.rules("def condition(ctx):\n    return 'sleepy'\n"))
    assertTrue("rules" in result.failed)
    assertTrue("rules" !in result.packages)
    assertTrue("zones/z" in result.packages)
  }

  @Test
  fun `a package that loads from rules that fail their dry run fails too`() {
    val root =
      tempWorld(
        mapOf(
          rulesPkg,
          "rules/combat.star" to TestWorlds.rules("def condition(ctx):\n    return 'sleepy'\n"),
          "rules/helpers.star" to "BONUS = 2\n",
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to "load(\"//rules:helpers.star\", \"BONUS\")\n$DEFAULT_ROOMS",
        )
      )
    val result = ScriptLoader(WorldDir(root), ScriptLimits.HARNESS).load(listOf("zones/z", "rules"))
    assertTrue("rules" !in result.packages)
    assertTrue("zones/z" !in result.packages, result.errors.toString())
    assertContains(
      result.errors.single { it.pkg == "zones/z" }.message,
      "depends on //rules, which failed to load",
    )
  }

  @Test
  fun `a failing reload of the rules keeps the old rules live`() {
    val f = world("def to_hit(ctx):\n    return 77\n")
    f.w.runPulses(1)
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    assertEquals(77, f.w.rules.toHit(RulesContext(f.w, p, rat)))
    f.write("rules/combat.star", TestWorlds.rules("def regen(ctx):\n    return {'hp': 1}\n"))
    val result = f.host.reload(f.w, listOf("rules"))
    assertFalse(result.ok, result.messages.toString())
    assertEquals(77, f.w.rules.toHit(RulesContext(f.w, p, rat)))
  }

  @Test
  fun `a public function in combat star that isn't a rules export fails the rules package`() {
    val result =
      loadWith(
        TestWorlds.rules("def _clamp(n):\n    return n\n", "def tohit(ctx):\n    return 100\n")
      )
    assertTrue("rules" in result.failed)
    assertTrue("zones/z" in result.packages)
    val error = result.errors.single { it.kind == "rules" }
    assertContains(error.message, "'tohit' is not a rules export")
  }

  @Test
  fun `rules get their own 200,000-step budget, even inside a hook`() {
    val f =
      world(
        """
            def damage(ctx):
                n = 0
                for i in range(30000):
                    n += 1
                return 3
            """,
        """
            def _count(ctx):
                n = 0
                for i in range(30000):
                    n += 1

            room("start", commands = {"count": _count})
            mob("rat", vitals = vitals(hp = 100))
            """,
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    assertEquals(3, f.w.damageChain(RulesContext(f.w, p, rat)).value)
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
    // The same loop is too much for a hook's 50,000 steps.
    f.input(p, "count")
    f.run()
    assertEquals("step_limit", f.diagnostics().single().kind)
  }
}
