package gups.script

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.world.changeVital
import gups.core.world.spawnItem
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChargesScriptTest {
  private fun zone(content: String, vararg more: Pair<String, String>) =
    mapOf("zones/z/package.star" to zonePkg(), "zones/z/things.star" to content) + more

  @Test
  fun `charges and max_charges are readable in on_use and empty kept item is refused`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _use_wand(ctx):
              ctx.send(ctx.actor, "%s/%s" % (ctx.item.charges, ctx.item.max_charges))

          room("start")
          item("wand", name = "a wand", on_use = _use_wand, charges = 2, empty = "keep")
          """
        )
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:wand"), ItemLocation.Carried(p.id))

    f.input(p, "use wand")
    f.run()
    assertContains(f.out(p), "2/2")

    f.input(p, "use wand")
    f.run()
    assertContains(f.out(p), "1/2")

    f.input(p, "use wand")
    f.run()
    val refused = f.out(p)
    assertContains(refused, "There are no charges left in a wand.")
    assertFalse(refused.contains("0/2"), refused)
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `set_charges on a charged item fails if out of range`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _recharge(ctx):
              ctx.set_charges(ctx.item, 5)

          room("start")
          item("wand", commands = {"recharge": _recharge}, charges = 3)
          """
        )
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:wand"), ItemLocation.Carried(p.id))

    f.input(p, "recharge")
    f.run()
    assertContains(f.w.diagnostics.last().message, "between 0 and 3")
  }

  @Test
  fun `set_charges on an unlimited item fails with unlimited`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _recharge(ctx):
              ctx.set_charges(ctx.item, 1)

          room("start")
          item("sword", commands = {"recharge": _recharge})
          """
        )
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:sword"), ItemLocation.Carried(p.id))

    f.input(p, "recharge")
    f.run()
    assertContains(f.w.diagnostics.last().message, "unlimited")
  }

  @Test
  fun `set_charges within range changes the remaining uses`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _recharge(ctx):
              ctx.set_charges(ctx.item, 0)
              ctx.send(ctx.actor, "now %s" % ctx.item.charges)

          room("start")
          item("wand", commands = {"recharge": _recharge}, charges = 3, empty = "keep")
          """
        )
      )
    val p = f.player()
    val wand = f.w.spawnItem(Label("//zones/z:wand"), ItemLocation.Carried(p.id))

    f.input(p, "recharge")
    f.run()
    assertContains(f.out(p), "now 0")
    assertEquals(0, wand.charges)
    assertTrue(f.w.item(wand.id) === wand, "setting 0 must not run the empty action")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `set_charges on a stackable item fails with stackable`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _recharge(ctx):
              ctx.set_charges(ctx.item, 1)

          room("start")
          item("potion", commands = {"recharge": _recharge}, stackable = True, charges = 1)
          """
        )
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:potion"), ItemLocation.Carried(p.id))

    f.input(p, "recharge")
    f.run()
    assertContains(f.w.diagnostics.last().message, "set_charges: potion is stackable")
  }

  @Test
  fun `potion with charges and heal use_effect heals and is destroyed`() {
    val f =
      ScriptFixture(
        zone(
          """
          room("start")
          item("potion", name = "a potion", charges = 1, use_effect = heal(dice = "1d1+4", vital = "hp"))
          """
        )
      )
    val p = f.player()
    f.w.changeVital(p, "hp", -10)
    val startHp = p.current("hp")
    val item = f.w.spawnItem(Label("//zones/z:potion"), ItemLocation.Carried(p.id))

    f.input(p, "use potion")
    f.run()
    assertContains(f.out(p), "You use a potion.")
    assertEquals(startHp + 5, p.current("hp"))
    assertNull(f.w.item(item.id), "charges=1 potion must be destroyed after use")
  }

  @Test
  fun `item with enemy target and damage use_effect damages named mob`() {
    val f =
      ScriptFixture(
        zone(
          """
          room("start")
          mob("goblin", name = "a goblin")
          item("wand", name = "a wand", charges = 1, use_target = "enemy", use_effect = damage(dice = "1d1+4", type = DAMAGE.slash))
          """
        )
      )
    val p = f.player()
    val mob = f.w.spawnMob(Label("//zones/z:goblin"), f.w.room(Label("//zones/z:start")))
    val startHp = mob.current("hp")
    f.w.spawnItem(Label("//zones/z:wand"), ItemLocation.Carried(p.id))

    f.input(p, "use wand goblin")
    f.run()
    assertEquals(startHp - 5, mob.current("hp"))
  }

  @Test
  fun `item with use_lag sets player wait state`() {
    val f =
      ScriptFixture(
        zone(
          """
          room("start")
          item("potion", name = "a potion", charges = 1, use_lag = 2, use_effect = heal(dice = "1d1", vital = "hp"))
          """
        )
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:potion"), ItemLocation.Carried(p.id))

    f.input(p, "use potion")
    f.run()
    assertEquals(2, p.waitState)
  }
}
