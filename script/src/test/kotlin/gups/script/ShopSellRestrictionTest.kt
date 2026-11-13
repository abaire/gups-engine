package gups.script

import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import gups.core.world.spawnItem
import gups.script.testing.TestWorlds
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The template world's shop library asks the give restriction before it takes a sold item. */
class ShopSellRestrictionTest {
  private val shopLib: String = Files.readString(TestWorlds.templateWorld.resolve("lib/shop.star"))

  private fun fixture() =
    ScriptFixture(
      mapOf(
        "MODULE.gups" to TestWorlds.MODULE,
        "lib/package.star" to libPkg(),
        "lib/shop.star" to shopLib,
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(),
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to
          """
          load("//lib:shop.star", "shop")

          item("sword", name = "a sword", kind = "melee", wield = "wield", dice = "1d6",
               damage_type = "slash", weight = 3, value = 50)

          SHOP = shop.define(id = "s",
                             stock = [shop.stock("//zones/z:sword", count = 3, every = 600)],
                             till = shop.till(count = 100, every = 60, amount = 10))

          def _open(ctx):
              shop.open(ctx, ctx.self, SHOP)

          room("start", spawns = [spawn(":keeper")])
          mob("keeper", name = "the keeper", vitals = vitals(hp = 50), behaviors = [sentinel()],
              on_spawn = _open)
          """,
      ),
      load = listOf("zones/z", "lib"),
    )

  private fun ScriptFixture.say(p: Player, line: String): String {
    input(p, line)
    run(3)
    return out(p)
  }

  @Test
  fun `sell is refused with the give restriction's message and the item stays`() {
    val f = fixture()
    val p = f.player()
    val sword = f.w.spawnItem(gups.core.Label("//zones/z:sword"), ItemLocation.Carried(p.id))
    f.w.restrictions =
      mapOf(RestrictionPoint.GIVE to listOf(Restriction { _, _ -> "It's bound to you." }))
    assertContains(f.say(p, "sell sword"), "It's bound to you.")
    assertEquals(ItemLocation.Carried(p.id), sword.location)
    assertEquals(listOf(sword), p.inventory.toList())

    f.w.restrictions = mapOf()
    assertContains(f.say(p, "sell sword"), "You sell 1 a sword for 25 gold coins.")
  }
}
