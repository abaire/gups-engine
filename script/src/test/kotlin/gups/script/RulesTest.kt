package gups.script

import gups.core.Label
import gups.core.model.DamageType
import gups.core.model.ItemLocation
import gups.core.rules.RulesContext
import gups.core.world.changeVital
import gups.core.world.moveItem
import gups.core.world.spawnItem
import gups.core.world.startCombat
import gups.core.world.toHitChain
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulesTest : RulesTestBase() {
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
  fun `an enemy spell can be resisted in starlark world`() {
    val f =
      world(
        "def _spell_hit(ctx):\n    return 0\n",
        """
        room("start")
        skill("zap", target = "enemy", effect = damage(dice = "1d4"))
        mob("rat", vitals = vitals(hp = 100))
        """,
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    p.skills[Label("//zones/z:zap")] = 100
    f.input(p, "cast zap rat")
    f.run()
    assertEquals(100, rat.current("hp"))
    assertContains(f.out(p), "Rat resists your zap.")
  }
}
