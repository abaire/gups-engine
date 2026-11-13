package gups.script

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.world.spawnItem
import gups.script.testing.TestWorlds
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The shop library asks `fits` for the container's capacity and weight when it restocks and sells.
 */
class ShopFitsTest {
  private val shopLib: String = Files.readString(TestWorlds.templateWorld.resolve("lib/shop.star"))
  private val sword = Label("//zones/z:sword")
  private val coin = Label("//lib:gold_coin")

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
          item("cramped", name = "a cramped pack", keywords = ["pack"],
               container = container(capacity = 5, max_weight = 7))

          SHOP = shop.define(id = "s", container = "//zones/z:cramped", carried = True, key = None,
                             stock = [shop.stock("//zones/z:sword", count = 5, every = 600)],
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

  private fun ScriptFixture.swords(): Int {
    val keeper = w.room(Label("//zones/z:start")).actors.filterIsInstance<Mob>().single()
    val pack = keeper.inventory.single { it.protoLabel.v == "//zones/z:cramped" }
    return pack.contents.filter { it.protoLabel == sword }.sumOf { it.count }
  }

  @Test
  fun `a new shop holds only the swords the container's weight takes`() {
    assertEquals(2, fixture().swords())
  }

  @Test
  fun `sell is refused when the container's weight has no room, and works once there is`() {
    val f = fixture()
    val p = f.player()
    f.w.spawnItem(sword, ItemLocation.Carried(p.id), 1)
    assertContains(f.say(p, "sell sword"), "the keeper has no room for that.")
    assertEquals(2, f.swords())
    f.w.spawnItem(coin, ItemLocation.Carried(p.id), 100)
    assertContains(f.say(p, "buy sword"), "You buy 1 a sword")
    assertEquals(1, f.swords())
    assertContains(f.say(p, "sell sword"), "You sell 1 a sword")
    assertEquals(2, f.swords())
  }

  @Test
  fun `list restocks only what the container's weight takes`() {
    val f = fixture()
    val p = f.player()
    f.w.spawnItem(coin, ItemLocation.Carried(p.id), 200)
    f.say(p, "buy 2 sword")
    assertEquals(0, f.swords())
    f.run(1300)
    f.say(p, "list")
    assertEquals(2, f.swords())
  }
}
