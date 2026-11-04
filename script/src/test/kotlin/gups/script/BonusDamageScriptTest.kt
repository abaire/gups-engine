package gups.script

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.world.moveItem
import gups.core.world.spawnItem
import gups.core.world.startCombat
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BonusDamageScriptTest {
  private val rulesPkg = "rules/package.star" to TestWorlds.RULES_PACKAGE

  /** A world whose rules are the template's with the functions of [combat] replaced. */
  private fun world(
    combat: String,
    rooms: String = "room(\"start\")\nmob(\"rat\", vitals = vitals(hp = 100))",
  ) =
    ScriptFixture(
      mapOf(
        rulesPkg,
        "rules/combat.star" to TestWorlds.rules(combat),
        "zones/z/package.star" to zonePkg(),
        "zones/z/rooms.star" to rooms,
      ),
      load = listOf("zones/z", "rules"),
    )

  private val mitigateAll =
    """
      def to_hit(ctx):
          return 100

      def damage(ctx):
          return 5

      def mitigate(ctx, amount, type):
          return vitals(hp = amount)
    """

  @Test
  fun `a worn item's bonus_damage modifier makes a hit carry two parts and the amount sums them`() {
    val f =
      world(
        mitigateAll,
        """
            def _rec(ctx):
                ctx.send(
                    ctx.actor,
                    "parts " + str(len(ctx.parts)) + " " + str(ctx.parts[1].type) + " " + str(ctx.amount),
                )

            def _flame(ctx, base):
                return base + damages(fire = 2)

            room("start")
            mob("rat", vitals = vitals(hp = 100), on_damage = _rec)
            item("amulet", wear = "neck", modifiers = {"bonus_damage": _flame})
            """,
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val amulet = f.w.spawnItem(Label("//zones/z:amulet"), ItemLocation.Carried(p.id))
    f.w.moveItem(amulet, ItemLocation.Equipped(p.id, "neck"))
    f.w.startCombat(p, rat)
    f.run(8)
    assertEquals(100 - 7, rat.current("hp"), "5 hp from the hit and 2 hp from the fire part")
    assertContains(f.out(p), "parts 2 fire 7")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `two bonus_damage sources add, and the second keeps the first`() {
    val f =
      world(
        mitigateAll,
        """
            def _rec(ctx):
                ctx.send(ctx.actor, "parts " + str(len(ctx.parts)) + " " + str(ctx.amount))

            def _room(ctx, base):
                return base + damages(fire = 1)

            def _neck(ctx, base):
                return base + damages(fire = 2)

            room("start", modifiers = {"bonus_damage": _room})
            mob("rat", vitals = vitals(hp = 100), on_damage = _rec)
            item("amulet", wear = "neck", modifiers = {"bonus_damage": _neck})
            """,
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val amulet = f.w.spawnItem(Label("//zones/z:amulet"), ItemLocation.Carried(p.id))
    f.w.moveItem(amulet, ItemLocation.Equipped(p.id, "neck"))
    f.w.startCombat(p, rat)
    f.run(8)
    // The room's 1 fire and the amulet's 2 fire add to one fire part of 3, on top of the 5 hp hit.
    assertEquals(100 - 8, rat.current("hp"))
    assertContains(f.out(p), "parts 2 8")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `a bonus_damage modifier returning a plain dict is refused and leaves the hit unchanged`() {
    val f =
      world(
        mitigateAll,
        """
            def _dict(ctx, base):
                return {"fire": 2}

            room("start")
            mob("rat", vitals = vitals(hp = 100))
            item("amulet", wear = "neck", modifiers = {"bonus_damage": _dict})
            """,
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val amulet = f.w.spawnItem(Label("//zones/z:amulet"), ItemLocation.Carried(p.id))
    f.w.moveItem(amulet, ItemLocation.Equipped(p.id, "neck"))
    f.w.startCombat(p, rat)
    f.run(8)
    assertEquals(100 - 5, rat.current("hp"), "the refused modifier leaves the hit as it was")
    assertContains(f.diagnostics("script_error").first().message, "damages")
  }

  @Test
  fun `a slot-guarded wielded weapon adds fire to its own swings and to nothing else`() {
    val f =
      world(
        mitigateAll,
        """
            def _rec(ctx):
                ctx.send(ctx.actor, "parts " + str(len(ctx.parts)))

            def _edge(ctx, base):
                if ctx.slot == None:
                    return base
                return base + damages(fire = 2)

            room("start")
            mob("rat", vitals = vitals(hp = 100), on_damage = _rec)
            item("sword", name = "a sword", wear = "wield", dice = "1d4", damage_type = "slash", modifiers = {"bonus_damage": _edge})
            skill("zap", effect = damage("1d4"))
            """,
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val sword = f.w.spawnItem(Label("//zones/z:sword"), ItemLocation.Carried(p.id))
    f.w.moveItem(sword, ItemLocation.Equipped(p.id, "wield"))
    f.w.startCombat(p, rat)
    f.run(8)
    assertTrue("parts 2" in f.out(p), "the guarded modifier adds fire to its own swings")
    f.out(p)
    // The sword stays worn; the guard returns base for the spell, so it adds no fire part.
    p.skills[Label("//zones/z:zap")] = 50
    f.input(p, "cast zap rat")
    f.run(8)
    val spellOut = f.out(p)
    assertTrue("parts 1" in spellOut, "the guard skips the spell: one part only: $spellOut")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `an unguarded wielded weapon adds fire to its swings and to the wearer's spells`() {
    val f =
      world(
        mitigateAll,
        """
            def _rec(ctx):
                ctx.send(ctx.actor, "parts " + str(len(ctx.parts)) + " " + ctx.type)

            def _edge(ctx, base):
                return base + damages(fire = 2)

            room("start")
            mob("rat", vitals = vitals(hp = 100), on_damage = _rec)
            item("sword", name = "a sword", wear = "wield", dice = "1d4", damage_type = "slash", modifiers = {"bonus_damage": _edge})
            skill("zap", effect = damage("1d4"))
            """,
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val sword = f.w.spawnItem(Label("//zones/z:sword"), ItemLocation.Carried(p.id))
    f.w.moveItem(sword, ItemLocation.Equipped(p.id, "wield"))
    f.w.startCombat(p, rat)
    f.run(8)
    assertTrue("parts 2 slash" in f.out(p), "the unguarded modifier adds fire to its own swings")
    f.out(p)
    p.skills[Label("//zones/z:zap")] = 50
    f.input(p, "cast zap rat")
    f.run(8)
    // The swings go on during the cast, so the spell's event is told apart by its primary type,
    // the unarmed bludgeon rather than the sword's slash.
    val spellOut = f.out(p)
    assertTrue(
      "parts 2 bludgeon" in spellOut,
      "the unguarded modifier adds fire to the wearer's spell: $spellOut",
    )
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `the raw damage verb fires on_damage with one part`() {
    val f =
      world(
        mitigateAll,
        """
            def _rec(ctx):
                ctx.say(ctx.self, "parts " + str(len(ctx.parts)))

            def _hurt(ctx):
                ctx.damage(ctx.find(":victim")[0], 1, DAMAGE.fire, "hp")

            room("start", commands = {"hurt": _hurt})
            mob("victim", name = "a victim", keywords = ["victim"], vitals = vitals(hp = 50), on_damage = _rec)
            """,
      )
    val p = f.player()
    f.mob("//zones/z:victim")
    f.input(p, "hurt victim")
    f.run()
    val rawOut = f.out(p)
    assertContains(rawOut, "parts 1")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `MODIFIER bonus_damage is the point's name`() {
    val f =
      ScriptFixture(
        mapOf(
          rulesPkg,
          "rules/combat.star" to TestWorlds.rules(""),
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            def _check(ctx):
                if MODIFIER.bonus_damage != "bonus_damage":
                    fail("MODIFIER.bonus_damage is not the point's name")
                ctx.send(ctx.actor, "ok")

            room("start", commands = {"check": _check})
            mob("rat", vitals = vitals(hp = 100))
            """,
        ),
        load = listOf("zones/z", "rules"),
      )
    val p = f.player()
    f.input(p, "check")
    f.run(3)
    assertContains(f.out(p), "ok")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `a bonus_damage room modifier runs for a damage effect without combat`() {
    val f =
      ScriptFixture(
        mapOf(
          "MODULE.gups" to TestWorlds.module("features = []"),
          rulesPkg,
          "rules/combat.star" to
            TestWorlds.rulesFor(
              emptySet(),
              "def mitigate(ctx, amount, type):\n    return vitals(hp = amount)\n",
            ),
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            def _embers(ctx, base):
                if ctx.weapon != None or ctx.slot != None or ctx.band != None:
                    return base
                return base + damages(fire = 2)

            room("start", modifiers = {"bonus_damage": _embers})
            mob("dummy", name = "a dummy", keywords = ["dummy"], vitals = vitals(hp = 50))
            item("wand", name = "a wand", use_target = "enemy", use_effect = damage(dice = "1d1"))
            """,
        ),
        load = listOf("zones/z", "rules"),
      )
    val p = f.player()
    val dummy = f.mob("//zones/z:dummy")
    f.w.spawnItem(Label("//zones/z:wand"), ItemLocation.Carried(p.id))
    f.input(p, "use wand dummy")
    f.run()
    // 1d1 rolls a 1 hp, and the fire part adds 2 more; both parts apply as one damage event.
    assertEquals(50 - 3, dummy.current("hp"), f.out(p))
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }
}
