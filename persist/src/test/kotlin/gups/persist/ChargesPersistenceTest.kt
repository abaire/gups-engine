package gups.persist

import gups.core.EntityId
import gups.core.Label
import gups.core.fixtures.tempDb
import gups.core.model.EmptyAction
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Prototypes
import gups.core.world.quit
import gups.core.world.spawnItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChargesPersistenceTest {
  private val wandLabel = Label("//t:wand")
  private val wandProto =
    ItemProto(wandLabel, "a wand", listOf("wand"), charges = 3, empty = EmptyAction.Keep)

  private fun protos(): Prototypes {
    val base = P.protos()
    return Prototypes(
      rooms = base.rooms.values.toList(),
      mobs = base.mobs.values.toList(),
      items = base.items.values.filter { it.label != Prototypes.CORPSE.label } + wandProto,
      skills = base.skills.values.toList(),
      zones = base.zones.values.toList(),
    )
  }

  @Test
  fun `a wand with charges left round-trips with its charges`() {
    val file = tempDb()
    val a = PersistWorld(file, protos())
    val ann = a.login("Ann")
    val wand = a.w.spawnItem(wandLabel, ItemLocation.Carried(ann.id))
    wand.charges = 1
    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, protos())
    val ann2 = b.login("Ann")
    val wand2 = ann2.inventory.single { it.protoLabel == wandLabel }
    assertEquals(1, wand2.charges)
    b.close()
  }

  @Test
  fun `a payload JSON without charges restores a full item`() {
    val p = JSON.decodeFromString(ItemPayload.serializer(), """{"name":"a wand","count":1}""")
    assertNull(p.charges)
    val item = Item(EntityId(1), wandProto, ItemLocation.Carried(EntityId(2)))
    item.charges = 0
    applyItem(item, p)
    assertEquals(3, item.charges)
  }

  @Test
  fun `a restore caps charges at the prototype's current maximum`() {
    val p = JSON.decodeFromString(ItemPayload.serializer(), """{"name":"a wand","charges":5}""")
    val item = Item(EntityId(1), wandProto, ItemLocation.Carried(EntityId(2)))
    applyItem(item, p)
    assertEquals(3, item.charges)
  }

  @Test
  fun `a restore of a stackable item gives it the prototype's charges`() {
    val potion =
      ItemProto(Label("//t:potion"), "a potion", listOf("potion"), charges = 1, stackable = true)
    val p = JSON.decodeFromString(ItemPayload.serializer(), """{"name":"a potion","charges":0}""")
    val item = Item(EntityId(1), potion, ItemLocation.Carried(EntityId(2)), 2)
    applyItem(item, p)
    assertEquals(1, item.charges)
  }

  @Test
  fun `a restore of an item whose prototype lost its charges makes it unlimited`() {
    val lamp = ItemProto(Label("//t:lamp"), "a lamp", listOf("lamp"))
    val p = JSON.decodeFromString(ItemPayload.serializer(), """{"name":"a lamp","charges":2}""")
    val item = Item(EntityId(1), lamp, ItemLocation.Carried(EntityId(2)))
    applyItem(item, p)
    assertNull(item.charges)
  }
}
