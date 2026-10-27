package gups.script

import gups.core.Label
import gups.core.fixtures.FixtureRules
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Band
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.model.ItemLocation
import gups.core.rules.PlayerDeathOutcome
import gups.core.rules.RulesContext
import gups.core.rules.StartingStats
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.core.world.applyAffect
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
        Case("condition", "ctx", "'ok'") { w, c ->
          assertEquals(c.actor.condition, w.rules.condition(c))
        },
        Case("xp_for_kill", "ctx", "10") { w, c -> assertEquals(0L, w.rules.xpForKill(c)) },
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
            def xp_to_level(level):
                if level == 99:
                    return 1 // 0
                return 1000 * level
            """
      )
    assertEquals(Long.MAX_VALUE, f.w.rules.xpToLevel(99))
    assertEquals(2000L, f.w.rules.xpToLevel(2))
  }

  @Test
  fun `a missing export fails the rules package, naming each one`() {
    val result = loadWith("def to_hit(ctx):\n    return 100\n")
    assertTrue("rules" in result.failed)
    val messages = result.errors.filter { it.pkg == "rules" }.map { it.message }
    assertTrue(messages.any { "must define damage(...)" in it }, messages.toString())
    assertTrue(messages.any { "must define starting_stats(...)" in it }, messages.toString())
    assertTrue(messages.none { "to_hit" in it })
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

  @Test
  fun `the template world's rules agree with the fixture ruleset`() {
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
      )
    host.install(w)
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
    a.xp = 777
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
    assertEquals(kotlin.xpForKill(c), w.rules.xpForKill(c))
    assertEquals(kotlin.xpToLevel(4), w.rules.xpToLevel(4))
    assertEquals(kotlin.onPlayerDeath(c), w.rules.onPlayerDeath(c))
    assertEquals(null, w.rules.castAllowed(c))
    assertTrue(w.diagnostics.isEmpty(), w.diagnostics.toString())
  }

  companion object {
    const val DEFAULT_ROOMS = "room(\"start\")\nmob(\"rat\", vitals = vitals(hp = 100))\n"
  }
}
