package gups.script

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.world.moveItem
import gups.core.world.spawnItem
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemEffectsScriptTest {
  private fun zone(rooms: String, vararg more: Pair<String, String>) =
    mapOf("zones/z/package.star" to zonePkg(), "zones/z/content.star" to rooms) + more

  private fun load(files: Map<String, String>, vararg pkgs: String = arrayOf("zones/z")) =
    ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(pkgs.toList())

  @Test
  fun `carried item affect counts through custody tree and stops when moved to ground`() {
    val f =
      ScriptFixture(
        zone(
          """
          room("start")
          item("backpack", wear = "held", container = container())
          item("bag", container = container())
          item("charm", affects = [item_affect("luck", applies = "carried", stat_deltas = stats(dex = -1))])
          """
        )
      )
    val p = f.player()
    val baseDex = p.stat("dex")
    val backpack = f.w.spawnItem(Label("//zones/z:backpack"), ItemLocation.Carried(p.id))
    val bag = f.w.spawnItem(Label("//zones/z:bag"), ItemLocation.Contained(backpack.id))
    f.w.spawnItem(Label("//zones/z:charm"), ItemLocation.Contained(bag.id))

    assertEquals(baseDex - 1, p.stat("dex"))

    f.w.moveItem(bag, ItemLocation.InRoom(p.room.id, 0))
    assertEquals(baseDex, p.stat("dex"))
  }

  @Test
  fun `item affect and variant declaration errors fail to load`() {
    val missingLabel =
      load(zone("room(\"start\")\nitem(\"thing\", variants = {\"uncursed\": \":missing\"})"))
    assertFalse(missingLabel.ok)
    assertContains(missingLabel.report(), "declares no item named 'missing'")

    val badVariantName =
      load(
        zone(
          "room(\"start\")\nitem(\"target\")\nitem(\"thing\", variants = {\"Bad\": \":target\"})"
        )
      )
    assertFalse(badVariantName.ok)
    assertContains(badVariantName.report(), "'Bad' is not a valid variant name")

    val wornWithModsOnSlotless =
      load(
        zone(
          """
          def f(ctx, base):
              return base
          room("start")
          item("thing", affects = [item_affect("mod", applies = "worn", modifiers = {"to_hit": f})])
          """
        )
      )
    assertFalse(wornWithModsOnSlotless.ok)
    assertContains(
      wornWithModsOnSlotless.report(),
      "item(): a worn item_affect with modifiers needs a wear slot",
    )

    val stunnedFlag =
      load(
        zone(
          "room(\"start\")\nitem(\"thing\", affects = [item_affect(\"stun\", flags = [\"stunned\"])])"
        )
      )
    assertFalse(stunnedFlag.ok)
    assertContains(
      stunnedFlag.report(),
      "item_affect(flags): 'stunned' isn't allowed; item affects can't stun",
    )

    val nonItemAffectInAffects =
      load(zone("room(\"start\")\nitem(\"thing\", affects = [\"not_an_affect\"])"))
    assertFalse(nonItemAffectInAffects.ok)
    assertContains(
      nonItemAffectInAffects.report(),
      "item(affects): expected item_affect(...), got string",
    )
  }

  @Test
  fun `a carried item_affect with modifiers needs no wear slot`() {
    val r =
      load(
        zone(
          """
          def f(ctx, base):
              return base
          room("start")
          item("thing", affects = [item_affect("mod", applies = "carried", modifiers = {"to_hit": f})])
          """
        )
      )
    assertTrue(r.ok, r.report())
  }

  @Test
  fun `item_affect names must be non-blank and unique per item`() {
    val blank = load(zone("room(\"start\")\nitem(\"thing\", affects = [item_affect(\" \")])"))
    assertFalse(blank.ok)
    assertContains(blank.report(), "item_affect(name): can't be blank")

    val twice =
      load(
        zone("room(\"start\")\nitem(\"thing\", affects = [item_affect(\"a\"), item_affect(\"a\")])")
      )
    assertFalse(twice.ok)
    assertContains(twice.report(), "item(affects): 'a' appears twice")
  }

  @Test
  fun `a key with affects is listed by inventory, not by keyring`() {
    val f =
      ScriptFixture(
        zone(
          """
          room("start")
          item("special_key", name = "a special key", kind = "key", affects = [item_affect("magic", applies = "carried")])
          """
        )
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:special_key"), ItemLocation.Carried(p.id))

    f.input(p, "inventory")
    val inv = f.out(p)
    assertContains(inv, "a special key")

    f.input(p, "keyring")
    val kr = f.out(p)
    assertContains(kr, "Your keyring is empty.")
  }
}
