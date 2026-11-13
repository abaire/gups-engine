package gups.pluginapi

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.rules.StartingStats
import gups.core.world.World
import gups.core.world.spawnItem
import gups.pluginapi.host.ActorFacade
import gups.pluginapi.host.ItemFacade
import gups.pluginapi.host.RoomFacade
import gups.pluginapi.host.WorldFacade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FacadeCustodyTest {
  private val testWorld: World = world(files = FacadeTest.ITEM_FILES)
  private val facade = WorldFacade(testWorld, "weather") {}
  private val player =
    testWorld.spawnPlayer(
      "Ann",
      Label("//zones/z:a"),
      overrides = StartingStats(max = mapOf("hp" to 20), current = mapOf("hp" to 20)),
    )
  private val ann = ActorFacade(player, testWorld)
  private val room = RoomFacade(testWorld.room(Label("//zones/z:a")), testWorld)

  private fun carry(label: String, count: Int = 1) =
    testWorld.spawnItem(Label("//zones/z:$label"), ItemLocation.Carried(player.id), count)

  private fun view(i: gups.core.model.Item) = ItemFacade(i, testWorld)

  @Test
  fun `spawnItem makes one stack of a stackable label`() {
    val made = facade.spawnItem("//zones/z:pebble", ann, 5)
    assertEquals(1, made.size)
    assertEquals(5, made[0].count)
    assertEquals(listOf(made[0].id), ann.inventory.map { it.id })
  }

  @Test
  fun `spawnItem makes count separate items of a non-stackable label`() {
    val made = facade.spawnItem("//zones/z:stick", ann, 3)
    assertEquals(3, made.size)
    assertEquals(3, made.map { it.id }.toSet().size)
    assertTrue(made.all { it.count == 1 })
    assertEquals(3, ann.inventory.size)
  }

  @Test
  fun `spawnItem places into a room and a container`() {
    val stick = facade.spawnItem("//zones/z:stick", room).single()
    assertEquals(PluginItemLocation.InRoom(room), stick.location)
    assertEquals(0, ((stick as ItemFacade).item.location as ItemLocation.InRoom).trackIndex)
    val bag = facade.spawnItem("//zones/z:bag", ann).single()
    val charm = facade.spawnItem("//zones/z:charm", bag).single()
    assertEquals(PluginItemLocation.InContainer(bag), charm.location)
    assertEquals(listOf(charm.id), bag.contents.map { it.id })
  }

  @Test
  fun `spawnItem rejects a mob, an unknown label, a bad count and a non-container`() {
    assertFailsWith<IllegalArgumentException> { facade.spawnItem("//zones/z:wolf", room) }
    assertFailsWith<IllegalArgumentException> { facade.spawnItem("//zones/z:nope", room) }
    assertFailsWith<IllegalArgumentException> { facade.spawnItem("not a label", room) }
    assertFailsWith<IllegalArgumentException> { facade.spawnItem("//zones/z:stick", room, 0) }
    val stick = view(carry("stick"))
    assertFailsWith<IllegalArgumentException> { facade.spawnItem("//zones/z:stick", stick) }
  }

  @Test
  fun `spawnItem skips container capacity`() {
    val tin = facade.spawnItem("//zones/z:tin", ann).single()
    val made = facade.spawnItem("//zones/z:charm", tin, 3)
    assertEquals(3, made.size)
    assertEquals(3, tin.contents.size)
  }

  @Test
  fun `moveItem moves a whole stack to a room at index 0`() {
    val pebbles = view(carry("pebble", 4))
    val moved = facade.moveItem(pebbles, room)
    assertEquals(4, moved.count)
    assertEquals(PluginItemLocation.InRoom(room), moved.location)
    assertEquals(0, ((moved as ItemFacade).item.location as ItemLocation.InRoom).trackIndex)
    assertTrue(ann.inventory.isEmpty())
  }

  @Test
  fun `moveItem moves part of a stack and rejects an out of range count`() {
    val pebbles = view(carry("pebble", 4))
    val moved = facade.moveItem(pebbles, room, 1)
    assertEquals(1, moved.count)
    assertEquals(3, pebbles.count)
    assertFailsWith<IllegalArgumentException> { facade.moveItem(pebbles, room, 0) }
    assertFailsWith<IllegalArgumentException> { facade.moveItem(pebbles, room, 4) }
  }

  @Test
  fun `moveItem into a non-container item is an error and moves a worn item`() {
    val stick = view(carry("stick"))
    assertFailsWith<IllegalArgumentException> { facade.moveItem(view(carry("charm")), stick) }
    val pack = view(carry("backpack"))
    assertNull(facade.wear(ann, pack))
    val moved = facade.moveItem(pack, room)
    assertEquals(PluginItemLocation.InRoom(room), moved.location)
    assertTrue(ann.equipment.isEmpty())
  }

  @Test
  fun `destroy removes a whole stack or part of one`() {
    val pebbles = view(carry("pebble", 4))
    facade.destroy(pebbles, 1)
    assertEquals(3, pebbles.count)
    assertFailsWith<IllegalArgumentException> { facade.destroy(pebbles, 4) }
    facade.destroy(pebbles)
    assertTrue(ann.inventory.isEmpty())
  }

  @Test
  fun `mergeStack folds an item into an identical stack`() {
    val first = view(carry("pebble", 2))
    val second = view(carry("pebble", 3))
    val held = facade.mergeStack(second)
    assertEquals(5, held.count)
    assertEquals(1, ann.inventory.size)
    assertTrue(held.id == first.id || held.id == second.id)
  }

  @Test
  fun `location reports each case`() {
    val stick = view(carry("stick"))
    assertEquals(PluginItemLocation.Carried(ann), stick.location)
    val bag = view(carry("bag"))
    val charm =
      view(testWorld.spawnItem(Label("//zones/z:charm"), ItemLocation.Contained(bag.item.id)))
    assertEquals(PluginItemLocation.InContainer(bag), charm.location)
    val pack = view(carry("backpack"))
    assertNull(facade.wear(ann, pack))
    assertEquals(PluginItemLocation.Equipped(ann, "held"), pack.location)
    val dropped = facade.moveItem(stick, room)
    assertIs<PluginItemLocation.InRoom>(dropped.location)
  }

  @Test
  fun `inventory lists carried items only and equipment lists worn ones by slot`() {
    val stick = view(carry("stick"))
    val pack = view(carry("backpack"))
    assertEquals(listOf(stick.id, pack.id), ann.inventory.map { it.id })
    assertTrue(ann.equipment.isEmpty())
    assertNull(facade.wear(ann, pack))
    assertEquals(listOf(stick.id), ann.inventory.map { it.id })
    assertEquals(mapOf("held" to pack.id), ann.equipment.mapValues { it.value.id })
  }

  @Test
  fun `count is the stack size`() {
    assertEquals(6, view(carry("pebble", 6)).count)
    assertEquals(1, view(carry("stick")).count)
  }
}
