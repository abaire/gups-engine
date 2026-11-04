package gups.script

import gups.core.Label
import gups.core.fixtures.FixtureRules
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Band
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.model.ItemLocation
import gups.core.rules.LevelUpResult
import gups.core.rules.PlayerDeathOutcome
import gups.core.rules.RulesContext
import gups.core.rules.StartingStats
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.core.world.applyAffect
import gups.core.world.changeVital
import gups.core.world.damageChain
import gups.core.world.moveItem
import gups.core.world.spawnItem
import gups.core.world.startCombat
import gups.core.world.toHitChain
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesTest {
  private val rulesPkg = "rules/package.star" to TestWorlds.RULES_PACKAGE

  /** A world whose rules are the template's with the functions of [combat] replaced. */
  private fun world(
    combat: String,
    rooms: String = DEFAULT_ROOMS,
    vararg more: Pair<String, String>,
  ) =
    ScriptFixture(
      mapOf(
        rulesPkg,
        "rules/combat.star" to TestWorlds.rules(combat),
        "zones/z/package.star" to zonePkg(),
        "zones/z/rooms.star" to rooms,
      ) + more,
      load = listOf("zones/z", "rules"),
    )

  private fun loadWith(combat: String): LoadResult {
    val root =
      tempWorld(
        mapOf(
          rulesPkg,
          "rules/combat.star" to combat,
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to DEFAULT_ROOMS,
        )
      )
    return ScriptLoader(WorldDir(root), ScriptLimits.HARNESS).load(listOf("zones/z", "rules"))
  }

  @Test
  fun `combat uses the Starlark rules module`() {
    val f =
      world(
        """
            def to_hit(ctx):
                return 100

            def damage(ctx):
                return 7 if ctx.weapon == None else 1

            def mitigate(ctx, amount, type):
                return vitals(hp = amount)
            """
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    f.w.startCombat(p, rat)
    f.run(8)
    assertEquals(100 - 7, rat.current("hp"))
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `ctx damage_type is readable in damage and mitigate and ctx slot is wield in to_hit`() {
    val f =
      world(
        """
            def to_hit(ctx):
                if ctx.slot == "wield" and ctx.damage_type == "bludgeon":
                    return 100
                return 0

            def damage(ctx):
                if ctx.damage_type == "bludgeon" and ctx.slot == "wield":
                    return 15
                return 0

            def mitigate(ctx, amount, type):
                if ctx.damage_type == type:
                    return vitals(hp = amount)
                return vitals(hp = 0)
            """
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    f.w.startCombat(p, rat)
    f.run(8)
    assertEquals(100 - 15, rat.current("hp"))
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())

    val fire = DamageType("fire")
    assertEquals(
      mapOf("hp" to 7),
      f.w.rules.mitigate(RulesContext(f.w, p, rat, damageType = fire), 7, fire),
    )
    assertEquals(mapOf("hp" to 0), f.w.rules.mitigate(RulesContext(f.w, p, rat), 7, fire))
  }

  @Test
  fun `a negative entry in a mitigate result raises that vital`() {
    val f =
      world(
        """
            def to_hit(ctx):
                return 100

            def damage(ctx):
                return 5

            def mitigate(ctx, amount, type):
                return vitals(hp = amount, mana = -1)
            """,
        rooms = "room(\"start\")\nmob(\"rat\", vitals = vitals(hp = 100, mana = 20))\n",
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    f.w.changeVital(rat, "mana", -10) // one below the maximum
    val before = rat.current("mana")
    f.w.startCombat(p, rat)
    f.run(8)
    assertEquals(before + 1, rat.current("mana"), "the -1 in the mitigate result raises mana by 1")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `an item's defend_hit modifier reads its wearer and lowers the chance to 0`() {
    val f =
      world(
        """
            def to_hit(ctx):
                return 100

            def damage(ctx):
                return 5

            def mitigate(ctx, amount, type):
                return vitals(hp = amount)
            """,
        rooms =
          """
            def _evade(ctx, base):
                # Runs on the wearer's chain when the wearer is hit: the wearer is
                # ctx.defender and the swinger is still ctx.attacker and ctx.actor.
                if ctx.defender.label != "//zones/z:rat" or ctx.attacker.id == ctx.defender.id:
                    return base
                if ctx.actor.id != ctx.attacker.id:
                    return base
                return 0

            room("start")
            mob("rat", vitals = vitals(hp = 100))
            item("cloak", wear = "body", modifiers = {"defend_hit": _evade})
            """,
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val cloak = f.w.spawnItem(Label("//zones/z:cloak"), ItemLocation.Carried(rat.id))
    f.w.moveItem(cloak, ItemLocation.Equipped(rat.id, "body"))
    f.w.startCombat(p, rat)
    f.run(8)
    assertEquals(100, rat.current("hp"), "every swing missed")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `an item's receive_damage modifier lowers the damage its wearer takes`() {
    val f =
      world(
        """
            def to_hit(ctx):
                return 100

            def damage(ctx):
                return 5

            def mitigate(ctx, amount, type):
                return vitals(hp = amount)
            """,
        rooms =
          """
            def _ward(ctx, base):
                # Runs on the wearer's chain before mitigate.
                return base - 3

            room("start")
            mob("rat", vitals = vitals(hp = 100))
            item("ward", wear = "body", modifiers = {"receive_damage": _ward})
            """,
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val ward = f.w.spawnItem(Label("//zones/z:ward"), ItemLocation.Carried(rat.id))
    f.w.moveItem(ward, ItemLocation.Equipped(rat.id, "body"))
    f.w.startCombat(p, rat)
    f.run(8)
    assertEquals(100 - 2, rat.current("hp"), "5 - 3 = 2 per swing")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `an item's mitigate modifier routes part of an hp loss into a mana gain`() {
    val f =
      world(
        """
            def to_hit(ctx):
                return 100

            def damage(ctx):
                return 5

            def mitigate(ctx, amount, type):
                return vitals(hp = amount)
            """,
        rooms =
          """
            def _siphon(ctx, base):
                # Runs on the wearer's chain after mitigate.
                return vitals(hp = base.hp - 1, mana = base.mana - 1)

            room("start")
            mob("rat", vitals = vitals(hp = 100, mana = 20))
            item("amulet", wear = "neck", modifiers = {"mitigate": _siphon})
            """,
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val amulet = f.w.spawnItem(Label("//zones/z:amulet"), ItemLocation.Carried(rat.id))
    f.w.moveItem(amulet, ItemLocation.Equipped(rat.id, "neck"))
    f.w.changeVital(rat, "mana", -5) // below the maximum, so the gain is not capped
    val beforeHp = rat.current("hp")
    val beforeMana = rat.current("mana")
    f.w.startCombat(p, rat)
    f.run(8)
    assertEquals(beforeHp - 4, rat.current("hp"), "the hp loss is reduced by 1")
    assertEquals(beforeMana + 1, rat.current("mana"), "routed into a mana gain")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `an item's bonus_damage modifier adds a typed part the rules mitigate with its own type`() {
    val f =
      world(
        """
            def to_hit(ctx):
                return 100

            def damage(ctx):
                return 5

            def mitigate(ctx, amount, type):
                if type == "fire":
                    return vitals(mana = amount)
                return vitals(hp = amount)
            """,
        rooms =
          """
            def _flame(ctx, base):
                # Runs on the attacker's chain, adding a fire part to each hit.
                return base + damages(fire = 2)

            room("start")
            mob("rat", vitals = vitals(hp = 100, mana = 20))
            item("amulet", wear = "neck", modifiers = {"bonus_damage": _flame})
            """,
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val amulet = f.w.spawnItem(Label("//zones/z:amulet"), ItemLocation.Carried(p.id))
    f.w.moveItem(amulet, ItemLocation.Equipped(p.id, "neck"))
    f.w.startCombat(p, rat)
    f.run(8)
    // The primary part's loss lands in hp; the fire part is mitigated with its own type.
    assertEquals(100 - 5, rat.current("hp"), "only the primary part hits hp")
    assertEquals(20 - 2, rat.current("mana"), "the fire part is mitigated with its own type")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `a defender modifier returning the wrong type leaves the value and records a script_error`() {
    val f =
      world(
        """
            def to_hit(ctx):
                return 100

            def damage(ctx):
                return 5

            def mitigate(ctx, amount, type):
                return vitals(hp = amount)
            """,
        rooms =
          """
            def _ward(ctx, base):
                return "a number"

            room("start")
            mob("rat", vitals = vitals(hp = 100))
            item("ward", wear = "body", modifiers = {"receive_damage": _ward})
            """,
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val ward = f.w.spawnItem(Label("//zones/z:ward"), ItemLocation.Carried(rat.id))
    f.w.moveItem(ward, ItemLocation.Equipped(rat.id, "body"))
    f.w.startCombat(p, rat)
    f.run(8)
    assertEquals(100 - 5, rat.current("hp"), "the wrong type leaves the value unchanged")
    assertContains(f.diagnostics("script_error").first().message, "expected an int, got string")
  }

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
        Case("interrupt_check", "ctx, amount", "True") { w, c ->
          assertFalse(w.rules.interruptCheck(c, 5))
        },
        Case("move_cost", "ctx", "vitals(move = 1)") { w, c ->
          assertEquals(emptyMap(), w.rules.moveCost(c))
        },
        Case("regen", "ctx", "vitals(hp = 1)") { w, c ->
          assertEquals(emptyMap(), w.rules.regen(c))
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
      val guard = if (case.export == "get_lag") "ctx.actor.level" else "ctx.actor.level"
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
      assertTrue(guard.isNotEmpty())
    }
  }

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

  @Test
  fun `a missing export fails the rules package, naming each one`() {
    val result = loadWith("def to_hit(ctx):\n    return 100\n")
    assertTrue("rules" in result.failed)
    val messages = result.errors.filter { it.pkg == "rules" }.map { it.message }
    assertTrue(messages.any { "must define damage(...)" in it }, messages.toString())
    assertTrue(messages.any { "must define starting_stats(...)" in it }, messages.toString())
    assertTrue(messages.any { "must define slot_families(...)" in it }, messages.toString())
    assertTrue(messages.any { "must define actor_slots(...)" in it }, messages.toString())
    assertTrue(messages.any { "must define slot_cost(...)" in it }, messages.toString())
    assertTrue(messages.none { "to_hit" in it })
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
  fun `the dry run catches a wrong return type in regen`() {
    val result = loadWith(TestWorlds.rules("def regen(ctx):\n    return {'hp': 1}\n"))
    assertTrue("rules" in result.failed)
    assertContains(result.errors.single { it.kind == "rules" }.message, "use vitals(...)")
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

  @Test
  fun `package modifiers are functions defined in package star, and apply in every room`() {
    val f =
      world(
        "def to_hit(ctx):\n    return 50\n",
        "room(\"start\")\nroom(\"other\")\nmob(\"rat\", vitals = vitals(hp = 100))\n",
        "zones/z/package.star" to
          """
                def _steady(ctx, base):
                    return base + 5

                package(api_version = 1, kind = "zone", modifiers = {"to_hit": _steady})
            """,
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val r = f.w.toHitChain(RulesContext(f.w, p, rat))
    assertEquals(55, r.value)
    assertEquals("rules.to_hit base=50 → zone:zones/z 55", r.text)
  }

  @Test
  fun `room, region, affect and item modifiers chain in order`() {
    val f =
      world(
        "def to_hit(ctx):\n    return 50\n",
        """
            load(":affects.star", "haste")

            def _region(ctx, base):
                return base * 2

            def _room(ctx, base):
                return base - 2

            def _sword(ctx, base):
                return base + 100 if ctx.weapon and ctx.weapon.kind == "melee" else base

            def _boost(ctx):
                ctx.apply_affect(ctx.actor, "haste", 100, modifiers = {"to_hit": haste})

            region("dim", modifiers = {"to_hit": _region})
            room("start", regions = ["dim"], modifiers = {"to_hit": _room}, commands = {"boost": _boost})
            mob("rat", vitals = vitals(hp = 100))
            item("sword", kind = "melee", wear = "wield", modifiers = {"to_hit": _sword})
            """,
        "zones/z/affects.star" to "def haste(ctx, base):\n    return base + 10\n",
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val c = { RulesContext(f.w, p, rat, weapon = p.equipped("wield")) }
    assertEquals((50 * 2) - 2, f.w.toHitChain(c()).value)
    f.input(p, "boost")
    f.run()
    assertEquals((50 * 2) - 2 + 10, f.w.toHitChain(c()).value)
    val sword = f.w.spawnItem(Label("//zones/z:sword"), ItemLocation.Carried(p.id))
    f.w.moveItem(sword, ItemLocation.Equipped(p.id, "wield"))
    val r = f.w.toHitChain(c())
    assertEquals((50 * 2) - 2 + 10 + 100, r.value)
    assertEquals(
      "rules.to_hit base=50 → region:dim 100 → room:start 98 → affect:haste 108 → item:sword 208",
      r.text,
    )
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `a cast_allowed modifier denies with ctx deny, which other modifiers may not use`() {
    val f =
      world(
        "",
        """
            def _vault(ctx, base):
                return ctx.deny("Your magic fizzles into nothing.")

            def _bad(ctx, base):
                return ctx.deny("nope")

            room("start", modifiers = {"cast_allowed": _vault, "to_hit": _bad})
            skill("spark", range = "far", effect = damage(dice = "1d1"))
            mob("rat", vitals = vitals(hp = 100))
            """,
      )
    val p = f.player()
    f.mob("//zones/z:rat")
    p.skills[Label("//zones/z:spark")] = 50
    f.input(p, "cast spark rat")
    f.run()
    assertContains(f.out(p), "Your magic fizzles into nothing.")
    val base = f.w.rules.toHit(RulesContext(f.w, p, p))
    assertEquals(base, f.w.toHitChain(RulesContext(f.w, p, p)).value)
    assertContains(
      f.diagnostics("script_error").single().message,
      "deny() is only valid in cast_allowed",
    )
  }

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
    w.applyAffect(a, "clumsy", 50, statDeltas = mapOf("dex" to -3))
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
    assertEquals(kotlin.encumbrance(c), w.rules.encumbrance(c))
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

  companion object {
    const val DEFAULT_ROOMS = "room(\"start\")\nmob(\"rat\", vitals = vitals(hp = 100))\n"
  }
}
