package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchemas
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WornItemRecheckTest {
  private val fs = FixtureSchemas.FANTASY
  private val plate = Label("//t:plate")
  private val heavyPlate = Label("//t:heavy_plate")
  private val cloak = Label("//t:cloak")

  private val f =
    Fixture(
      fs = fs,
      protos =
        Prototypes(
          T.rooms,
          T.mobs(fs),
          T.items +
            listOf(
              ItemProto(plate, "a plate", listOf("plate"), wear = "neck"),
              ItemProto(
                heavyPlate,
                "a heavy plate",
                listOf("plate"),
                wear = "neck",
                tags = setOf("heavy"),
              ),
              ItemProto(cloak, "a cloak", listOf("cloak"), wear = "wield", tags = setOf("light")),
            ),
          T.skills(fs),
        ),
    )
  private val asked = mutableListOf<String>()

  private fun restrict(refusal: (gups.core.model.RestrictionRequest) -> String?) {
    f.w.restrictions =
      mapOf(
        RestrictionPoint.WEAR to
          listOf(
            Restriction { _, r ->
              asked += r.item!!.name
              refusal(r)
            }
          )
      )
  }

  private fun refuseHeavy() = restrict {
    if ("heavy" in it.item!!.tags) "Too heavy for you." else null
  }

  private fun wearer(): Player = f.player("Ann")

  private fun Player.wear(label: Label, slot: String) =
    f.w.spawnItem(label, ItemLocation.Equipped(id, slot))

  @Test
  fun `a tag added to a worn item the restriction refuses makes it inactive and tells the wearer`() {
    val p = wearer()
    val item = p.wear(plate, "neck")
    refuseHeavy()
    f.out(p)
    assertTrue(f.w.addItemTag(item, "heavy"))
    assertEquals(ItemLocation.Equipped(p.id, "neck"), item.location)
    assertEquals(setOf("neck"), p.inactiveSlots)
    f.run()
    assertContains(f.out(p), "Too heavy for you.")
  }

  @Test
  fun `removing the tag makes the worn item active again`() {
    val p = wearer()
    val item = p.wear(plate, "neck")
    refuseHeavy()
    f.w.addItemTag(item, "heavy")
    f.out(p)
    assertTrue(f.w.removeItemTag(item, "heavy"))
    assertTrue(p.inactiveSlots.isEmpty())
    f.run()
    assertContains(f.out(p), "fits you again")
  }

  @Test
  fun `transforming a worn item into a variant the restriction refuses leaves it worn but inactive`() {
    val p = wearer()
    val item = p.wear(plate, "neck")
    refuseHeavy()
    f.out(p)
    f.w.transformItem(item, heavyPlate)
    assertEquals(ItemLocation.Equipped(p.id, "neck"), item.location)
    assertEquals(setOf("neck"), p.inactiveSlots)
    f.run()
    assertContains(f.out(p), "Too heavy for you.")
    f.w.transformItem(item, plate)
    assertTrue(p.inactiveSlots.isEmpty())
  }

  @Test
  fun `an item affect the restriction keys on makes the item inactive until it expires`() {
    val p = wearer()
    val item = p.wear(plate, "neck")
    restrict { r -> if (r.item!!.affects.any { it.name == "hex" }) "It is hexed." else null }
    f.out(p)
    f.w.applyItemAffect(item, AffectSpec("hex", 5))
    assertEquals(setOf("neck"), p.inactiveSlots)
    f.run()
    assertContains(f.out(p), "It is hexed.")
    f.run(6)
    assertTrue(item.affects.isEmpty())
    assertTrue(p.inactiveSlots.isEmpty())
    assertContains(f.out(p), "fits you again")
  }

  @Test
  fun `removing an item affect asks the restriction again`() {
    val p = wearer()
    val item = p.wear(plate, "neck")
    restrict { r -> if (r.item!!.affects.any { it.name == "hex" }) "It is hexed." else null }
    f.w.applyItemAffect(item, AffectSpec("hex", null))
    assertEquals(setOf("neck"), p.inactiveSlots)
    assertTrue(f.w.removeItemAffect(item, "hex"))
    assertTrue(p.inactiveSlots.isEmpty())
  }

  @Test
  fun `a change to one worn item asks again about every worn item`() {
    val p = wearer()
    val armor = p.wear(plate, "neck")
    val wrap = p.wear(cloak, "wield")
    // A light item can't be worn while anything heavy is.
    restrict { r ->
      val item = r.item!!
      val otherHeavy = r.actor.equipmentIndex.values.any { it !== item && "heavy" in it.tags }
      if ("light" in item.tags && otherHeavy) "Too bulky to wear that." else null
    }
    f.out(p)
    assertTrue(f.w.addItemTag(armor, "heavy"))
    assertEquals(setOf("wield"), p.inactiveSlots)
    assertEquals(ItemLocation.Equipped(p.id, "wield"), wrap.location)
    f.run()
    assertContains(f.out(p), "Too bulky to wear that.")
  }

  @Test
  fun `a carried item gaining the tag does not ask the restriction`() {
    val p = wearer()
    p.wear(cloak, "wield")
    val carried = f.w.spawnItem(plate, ItemLocation.Carried(p.id))
    refuseHeavy()
    asked.clear()
    f.out(p)
    assertTrue(f.w.addItemTag(carried, "heavy"))
    f.w.applyItemAffect(carried, AffectSpec("hex", null))
    f.w.transformItem(carried, heavyPlate)
    assertTrue(asked.isEmpty())
    assertTrue(p.inactiveSlots.isEmpty())
  }
}
