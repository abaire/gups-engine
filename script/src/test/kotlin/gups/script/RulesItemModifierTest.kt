package gups.script

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.world.changeVital
import gups.core.world.moveItem
import gups.core.world.spawnItem
import gups.core.world.startCombat
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulesItemModifierTest : RulesTestBase() {
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
}
