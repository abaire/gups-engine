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

  @Test
  fun `item skill attaches worn affect to item which affects holder when worn and expires`() {
    val f =
      ScriptFixture(
        zone(
          """
          room("start")
          item("sword", wear = "wield")
          skill("enchant", target = "item", effect = apply_affect("keen", 50, applies = "worn", stat_deltas = stats(dex = 1)))
          """
        )
      )
    val p = f.player()
    val baseDex = p.stat("dex")
    val sword = f.w.spawnItem(Label("//zones/z:sword"), ItemLocation.Carried(p.id))
    p.skills[Label("//zones/z:enchant")] = 100

    f.input(p, "cast enchant sword")
    f.run()

    assertEquals(1, sword.affects.size)
    assertEquals("keen", sword.affects[0].name)
    assertEquals(baseDex, p.stat("dex"))

    f.input(p, "wield sword")
    f.run()
    assertEquals(baseDex + 1, p.stat("dex"))

    f.run(50)
    assertEquals(baseDex, p.stat("dex"))
    assertEquals(0, sword.affects.size)
  }

  @Test
  fun `permanent item affect with duration None`() {
    val f =
      ScriptFixture(
        zone(
          """
          room("start")
          item("sword", wear = "wield")
          skill("perma_enchant", target = "item", effect = apply_affect("perma", None, applies = "worn", stat_deltas = stats(dex = 1)))
          """
        )
      )
    val p = f.player()
    val sword = f.w.spawnItem(Label("//zones/z:sword"), ItemLocation.Carried(p.id))
    p.skills[Label("//zones/z:perma_enchant")] = 100

    f.input(p, "cast perma_enchant sword")
    f.run()

    assertEquals(1, sword.affects.size)
    assertEquals(Long.MAX_VALUE, sword.affects[0].expiresAt)

    val enemyPerm =
      load(
        zone(
          """
          room("start")
          skill("bad_enemy", target = "enemy", effect = apply_affect("perma", None))
          """
        )
      )
    assertFalse(enemyPerm.ok)
    assertContains(
      enemyPerm.report(),
      "skill(effect): apply_affect(duration) can be None only on an item skill",
    )
  }

  @Test
  fun `disallowed effect primitives on item and enemy skills fail to load`() {
    val damageOnItem =
      load(
        zone(
          """
          room("start")
          skill("bad_item_dmg", target = "item", effect = damage("1d6"))
          """
        )
      )
    assertFalse(damageOnItem.ok)
    assertContains(
      damageOnItem.report(),
      "skill(effect): damage acts on actors; an item skill's effect may use apply_affect and plugin primitives",
    )

    val seqDispelOnItem =
      load(
        zone(
          """
          room("start")
          skill("bad_seq", target = "item", effect = sequence(apply_affect("keen", 10), dispel(all = True)))
          """
        )
      )
    assertFalse(seqDispelOnItem.ok)
    assertContains(
      seqDispelOnItem.report(),
      "skill(effect): dispel acts on actors; an item skill's effect may use apply_affect and plugin primitives",
    )

    val carriedOnEnemy =
      load(
        zone(
          """
          room("start")
          skill("bad_enemy_applies", target = "enemy", effect = apply_affect("curse", 10, applies = "carried"))
          """
        )
      )
    assertFalse(carriedOnEnemy.ok)
    assertContains(
      carriedOnEnemy.report(),
      "skill(effect): apply_affect(applies) is only for item skills",
    )

    val stunnedOnItem =
      load(
        zone(
          """
          room("start")
          skill("stun_item", target = "item", effect = apply_affect("stun", 10, flags = ["stunned"]))
          """
        )
      )
    assertFalse(stunnedOnItem.ok)
    assertContains(stunnedOnItem.report(), "skill(effect): item affects can't stun")
  }
}
