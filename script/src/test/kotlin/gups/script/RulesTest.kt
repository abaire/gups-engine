package gups.script

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.rules.RulesContext
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.core.world.applyAffect
import gups.core.world.damageChain
import gups.core.world.moveItem
import gups.core.world.spawnItem
import gups.core.world.startCombat
import gups.core.world.toHitChain
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulesTest {
  private val rulesPkg = "rules/package.star" to "package(api_version = 1, kind = \"rules\")\n"

  private fun world(
    combat: String,
    rooms: String = DEFAULT_ROOMS,
    vararg more: Pair<String, String>,
  ) =
    ScriptFixture(
      mapOf(
        rulesPkg,
        "rules/combat.star" to combat,
        "zones/z/package.star" to zonePkg(),
        "zones/z/rooms.star" to rooms,
      ) + more,
      load = listOf("zones/z", "rules"),
    )

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
                return amount
            """
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    f.w.startCombat(p, rat)
    f.run(8)
    assertEquals(100 - 7, rat.hp)
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `missing exports use the Kotlin rule quietly, failing ones loudly`() {
    val f =
      world(
        """
            def damage(ctx):
                return 1 // 0

            def to_hit(ctx):
                return "always"
            """
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val c = RulesContext(f.w, p, rat)
    assertEquals(
      f.w.rules.let { gups.core.rules.DefaultRules().initiative(c) },
      f.w.rules.initiative(c),
    )
    assertTrue(f.diagnostics().isEmpty())
    val dealt = f.w.rules.damage(c)
    assertTrue(dealt >= 1)
    assertContains(f.diagnostics("script_error").first().message, "division by zero")
    assertContains(
      f.diagnostics("rules").first().message,
      "rules.damage failed; using the built-in Kotlin rule",
    )
    f.w.rules.toHit(c)
    assertContains(
      f.diagnostics("script_error").last().message,
      "rules.to_hit returned a bad value",
    )
  }

  @Test
  fun `a rules package that failed to load falls back for everything, with one alert`() {
    val root =
      tempWorld(
        mapOf(
          rulesPkg,
          "rules/combat.star" to "def to_hit(ctx)\n",
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to DEFAULT_ROOMS,
        )
      )
    val loader = ScriptLoader(WorldDir(root), ScriptLimits.HARNESS)
    val result = loader.load(listOf("zones/z", "rules"))
    assertTrue("rules" in result.failed)
    val host = StarlarkHost(result, ScriptLimits.HARNESS)
    val alerts = mutableListOf<String>()
    host.alerts = { alerts += it }
    val w = World(result.prototypes(), host.rules(), host, WorldConfig(strict = true))
    host.install(w)
    val p = w.spawnPlayer("Ann", Label("//zones/z:start"))
    assertEquals(60, w.rules.toHit(RulesContext(w, p, p)))
    w.rules.damage(RulesContext(w, p, p))
    assertEquals(1, alerts.size)
    assertContains(alerts.single(), "//rules failed to load")
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
            mob("rat", hp = 100)
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
        "room(\"start\")\nroom(\"other\")\nmob(\"rat\", hp = 100)\n",
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
            mob("rat", hp = 100)
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
            mob("rat", hp = 100)
            """,
      )
    val p = f.player()
    f.mob("//zones/z:rat")
    p.skills[Label("//zones/z:spark")] = 50
    f.input(p, "cast spark rat")
    f.run()
    assertContains(f.out(p), "Your magic fizzles into nothing.")
    val base = gups.core.rules.DefaultRules().toHit(RulesContext(f.w, p, p))
    assertEquals(base, f.w.toHitChain(RulesContext(f.w, p, p)).value)
    assertContains(
      f.diagnostics("script_error").single().message,
      "deny() is only valid in cast_allowed",
    )
  }

  @Test
  fun `the template world's rules match the built-in ruleset`() {
    val dir = WorldDir(java.nio.file.Path.of("../template-world"))
    val result = ScriptLoader(dir, ScriptLimits.HARNESS).load(listOf("rules", "zones/tutorial"))
    assertTrue(result.ok, result.report())
    val host = StarlarkHost(result, ScriptLimits.HARNESS)
    val w = World(result.prototypes(), host.rules(), host, WorldConfig(seed = 3, strict = true))
    host.install(w)
    val kotlin = gups.core.rules.DefaultRules()
    val a =
      w.spawnPlayer(
        "Ann",
        Label("//zones/tutorial:hall"),
        level = 3,
        stats = mapOf("str" to 14, "dex" to 12, "con" to 13, "int" to 9, "wis" to 11),
      )
    val b = w.spawnPlayer("Bob", Label("//zones/tutorial:hall"), level = 5)
    a.xp = 777
    b.armor = 6
    w.applyAffect(a, "clumsy", 50, statDeltas = mapOf("dex" to -3))
    val c = RulesContext(w, a, b, band = gups.core.model.Band.NEAR)
    assertEquals(kotlin.equipmentSlots(), w.rules.equipmentSlots())
    assertEquals(kotlin.toHit(c), w.rules.toHit(c))
    assertEquals(
      kotlin.mitigate(c, 17, gups.core.model.DamageType.FIRE),
      w.rules.mitigate(c, 17, gups.core.model.DamageType.FIRE),
    )
    assertEquals(kotlin.initiative(c), w.rules.initiative(c))
    assertEquals(kotlin.retreatCheck(c), w.rules.retreatCheck(c))
    assertEquals(kotlin.fleeChance(c), w.rules.fleeChance(c))
    assertEquals(kotlin.regen(c), w.rules.regen(c))
    assertEquals(kotlin.carryLimit(c), w.rules.carryLimit(c))
    assertEquals(kotlin.encumbrance(c), w.rules.encumbrance(c))
    for (band in gups.core.model.Band.entries) assertEquals(
      kotlin.getLag(c, band),
      w.rules.getLag(c, band),
    )
    assertEquals(kotlin.xpForKill(c), w.rules.xpForKill(c))
    assertEquals(kotlin.xpToLevel(4), w.rules.xpToLevel(4))
    assertEquals(kotlin.onPlayerDeath(c), w.rules.onPlayerDeath(c))
    assertEquals(null, w.rules.castAllowed(c))
    assertTrue(w.diagnostics.isEmpty(), w.diagnostics.toString())
  }

  companion object {
    const val DEFAULT_ROOMS = "room(\"start\")\nmob(\"rat\", hp = 100)\n"
  }
}
