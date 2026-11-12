package gups.core.world

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WearVerbsTest {
  private val ring = Label("//t:verb_ring")
  private val sword = Label("//t:verb_sword")
  private val shield = Label("//t:verb_shield")
  private val greatsword = Label("//t:verb_greatsword")
  private val pebble = Label("//t:verb_pebble")

  private val protos =
    listOf(
      ItemProto(ring, "a ring", listOf("ring"), wear = "ring"),
      ItemProto(sword, "a sword", listOf("sword"), wear = "hand"),
      ItemProto(shield, "a shield", listOf("shield"), wear = "offhand"),
      ItemProto(greatsword, "a greatsword", listOf("greatsword"), wear = "hand", slotCost = 2),
      ItemProto(pebble, "a pebble", listOf("pebble")),
    )

  private val f =
    Fixture(
      rules = SlotsTest.TestSlotRules(),
      protos = Prototypes(T.rooms, T.mobs, T.items + protos, T.skills),
    )
  private val p = f.player("Ann")

  private fun carry(p: Player, label: Label) = f.w.spawnItem(label, ItemLocation.Carried(p.id))

  private fun refuse(point: RestrictionPoint, label: Label, message: String) {
    f.w.restrictions =
      f.w.restrictions +
        (point to
          listOf(Restriction { _, r -> if (r.item?.proto?.label == label) message else null }))
  }

  @Test
  fun `wear puts a carried item on silently`() {
    val r = carry(p, ring)
    f.out(p)
    assertNull(f.w.wearItem(p, r))
    assertEquals(ItemLocation.Equipped(p.id, "finger_l"), r.location)
    assertEquals("", f.out(p))
  }

  @Test
  fun `wear takes one item off to make room, silently`() {
    val s = carry(p, sword)
    val gs = carry(p, greatsword)
    assertNull(f.w.wearItem(p, s, wield = true))
    f.out(p)
    assertNull(f.w.wearItem(p, gs, wield = true))
    assertEquals(ItemLocation.Equipped(p.id, "wield"), gs.location)
    assertEquals(ItemLocation.Carried(p.id), s.location)
    assertEquals("", f.out(p))
  }

  @Test
  fun `wear refuses what the command refuses, with its messages`() {
    val s = carry(p, sword)
    val sh = carry(p, shield)
    val gs = carry(p, greatsword)
    f.w.wearItem(p, s)
    f.w.wearItem(p, sh)
    assertEquals("You'd need to remove a sword, a shield first.", f.w.wearItem(p, gs))
    assertEquals(ItemLocation.Carried(p.id), gs.location)

    val rings = List(3) { carry(p, ring) }
    f.w.wearItem(p, rings[0])
    f.w.wearItem(p, rings[1])
    assertEquals("There's no room for a ring. Remove something first.", f.w.wearItem(p, rings[2]))

    assertEquals("You can't wear a pebble.", f.w.wearItem(p, carry(p, pebble)))
    assertEquals("You can't wield a ring.", f.w.wearItem(p, rings[2], wield = true))
    assertEquals(ItemLocation.Carried(p.id), rings[2].location)
  }

  @Test
  fun `wear asks the wear and remove restrictions`() {
    refuse(RestrictionPoint.WEAR, shield, "The shield won't sit on your arm.")
    val sh = carry(p, shield)
    assertEquals("The shield won't sit on your arm.", f.w.wearItem(p, sh))
    assertEquals(ItemLocation.Carried(p.id), sh.location)

    refuse(RestrictionPoint.REMOVE, sword, "The sword clings to your hand.")
    val s = carry(p, sword)
    val gs = carry(p, greatsword)
    assertNull(f.w.wearItem(p, s))
    assertEquals("The sword clings to your hand.", f.w.wearItem(p, gs))
    assertEquals(ItemLocation.Equipped(p.id, "wield"), s.location)
    assertEquals(ItemLocation.Carried(p.id), gs.location)
  }

  @Test
  fun `wear needs an item the actor carries`() {
    val other = f.player("Bob")
    val r = carry(other, ring)
    assertFailsWith<IllegalArgumentException> { f.w.wearItem(p, r) }
    f.w.wearItem(other, r)
    assertFailsWith<IllegalArgumentException> { f.w.wearItem(other, r) } // already worn
  }

  @Test
  fun `unwear moves a worn item back to the inventory silently`() {
    val r = carry(p, ring)
    f.w.wearItem(p, r)
    f.out(p)
    assertNull(f.w.unwearItem(p, r))
    assertEquals(ItemLocation.Carried(p.id), r.location)
    assertEquals("", f.out(p))
  }

  @Test
  fun `unwear asks the remove restriction`() {
    refuse(RestrictionPoint.REMOVE, ring, "The ring won't budge.")
    val r = carry(p, ring)
    f.w.wearItem(p, r)
    assertEquals("The ring won't budge.", f.w.unwearItem(p, r))
    assertEquals(ItemLocation.Equipped(p.id, "finger_l"), r.location)
  }

  @Test
  fun `unwear needs an item the actor wears`() {
    val r = carry(p, ring)
    assertFailsWith<IllegalArgumentException> { f.w.unwearItem(p, r) }
  }
}
