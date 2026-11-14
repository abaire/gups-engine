package gups.core.world

import gups.core.Label
import gups.core.WORLD_TICK_PULSES
import gups.core.model.Actor
import gups.core.model.ContainerProto
import gups.core.model.EmptyAction
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Prototypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DestroyTest {
  private data class Record(
    val owner: Entity,
    val hook: HookName,
    val item: Item?,
    val event: String?,
    val actor: Actor?,
  )

  private val coin = Label("//t:coin")
  private val potion = Label("//t:potion")
  private val wand = Label("//t:wand")
  private val chest = Label("//t:chest")
  private val pebble = Label("//t:pebble")

  private val extraItems =
    listOf(
      ItemProto(coin, "a gold coin", listOf("coin"), stackable = true),
      ItemProto(
        potion,
        "a potion",
        listOf("potion"),
        charges = 1,
        stackable = true,
        empty = EmptyAction.Destroy,
      ),
      ItemProto(wand, "a wand", listOf("wand"), charges = 1, empty = EmptyAction.Destroy),
      ItemProto(chest, "a chest", listOf("chest"), container = ContainerProto()),
      ItemProto(pebble, "a pebble", listOf("pebble")),
    )

  private fun recordingFixture(
    onDestroy: ((World, Entity, HookContext, MutableList<Record>) -> Unit)? = null,
    bubble: Boolean = false,
  ): Pair<Fixture, MutableList<Record>> {
    val log = mutableListOf<Record>()
    val host = RecordingHost()
    host.handlers[HookName.ON_DESTROY] = { w, owner, ctx ->
      log += Record(owner, HookName.ON_DESTROY, ctx.item, ctx.event, ctx.actor)
      onDestroy?.invoke(w, owner, ctx, log)
      HookOutcome.Allow
    }
    val protos = Prototypes(T.rooms, T.mobs, T.items + extraItems, T.skills)
    return Fixture(protos = protos, scripts = host, bubble = bubble) to log
  }

  @Test
  fun `whole destroy of carried item fires item then room with actor and event destroy`() {
    val (f, log) = recordingFixture()
    val p = f.player("Ann")
    val item = f.w.spawnItem(T.KEY, ItemLocation.Carried(p.id))

    f.w.destroy(item)

    assertNull(f.w.item(item.id))
    assertEquals(
      listOf(
        Record(item, HookName.ON_DESTROY, item, "destroy", p),
        Record(p.room, HookName.ON_DESTROY, item, "destroy", p),
      ),
      log,
    )
  }

  @Test
  fun `whole destroy of ground item has null actor`() {
    val (f, log) = recordingFixture()
    val hall = f.w.room(T.HALL)
    val item = f.w.spawnItem(T.KEY, ItemLocation.InRoom(hall.id, 0))

    f.w.destroy(item)

    assertNull(f.w.item(item.id))
    assertEquals(
      listOf(
        Record(item, HookName.ON_DESTROY, item, "destroy", null),
        Record(hall, HookName.ON_DESTROY, item, "destroy", null),
      ),
      log,
    )
  }

  @Test
  fun `partial destroy of stack records nothing and leaves remainder`() {
    val (f, log) = recordingFixture()
    val p = f.player("Ann")
    val stack = f.w.spawnItem(coin, ItemLocation.Carried(p.id), count = 5)

    f.w.destroy(stack, 2)

    assertNotNull(f.w.item(stack.id))
    assertEquals(3, stack.count)
    assertTrue(log.isEmpty())
  }

  @Test
  fun `stack merge records nothing`() {
    val (f, log) = recordingFixture()
    val p = f.player("Ann")
    val first = f.w.spawnItem(coin, ItemLocation.Carried(p.id), count = 2)
    val second = f.w.spawnItem(coin, ItemLocation.Carried(p.id), count = 3)

    val merged = f.w.stack(second)

    assertEquals(first, merged)
    assertEquals(5, first.count)
    assertNull(f.w.item(second.id))
    assertTrue(log.isEmpty())
  }

  @Test
  fun `bag holding two items fires container first in ascending id order`() {
    val (f, log) = recordingFixture()
    val p = f.player("Ann")
    val bag = f.w.spawnItem(T.BAG, ItemLocation.Carried(p.id))
    val a = f.w.spawnItem(T.KEY, ItemLocation.Contained(bag.id))
    val b = f.w.spawnItem(pebble, ItemLocation.Contained(bag.id))
    assertTrue(a.id < b.id)

    f.w.destroy(bag)

    assertNull(f.w.item(bag.id))
    assertNull(f.w.item(a.id))
    assertNull(f.w.item(b.id))
    assertEquals(
      listOf(
        Record(bag, HookName.ON_DESTROY, bag, "destroy", p),
        Record(p.room, HookName.ON_DESTROY, bag, "destroy", p),
        Record(a, HookName.ON_DESTROY, a, "destroy", p),
        Record(p.room, HookName.ON_DESTROY, a, "destroy", p),
        Record(b, HookName.ON_DESTROY, b, "destroy", p),
        Record(p.room, HookName.ON_DESTROY, b, "destroy", p),
      ),
      log,
    )
  }

  @Test
  fun `nested containers fire chest then bag then key`() {
    val (f, log) = recordingFixture()
    val hall = f.w.room(T.HALL)
    val c = f.w.spawnItem(chest, ItemLocation.InRoom(hall.id, 0))
    val bag = f.w.spawnItem(T.BAG, ItemLocation.Contained(c.id))
    val key = f.w.spawnItem(T.KEY, ItemLocation.Contained(bag.id))

    f.w.destroy(c)

    assertNull(f.w.item(c.id))
    assertNull(f.w.item(bag.id))
    assertNull(f.w.item(key.id))
    assertEquals(
      listOf(
        Record(c, HookName.ON_DESTROY, c, "destroy", null),
        Record(hall, HookName.ON_DESTROY, c, "destroy", null),
        Record(bag, HookName.ON_DESTROY, bag, "destroy", null),
        Record(hall, HookName.ON_DESTROY, bag, "destroy", null),
        Record(key, HookName.ON_DESTROY, key, "destroy", null),
        Record(hall, HookName.ON_DESTROY, key, "destroy", null),
      ),
      log,
    )
  }

  @Test
  fun `bag hook moving item out spares it without hooks while siblings are destroyed`() {
    lateinit var bag: Item
    lateinit var a: Item
    lateinit var b: Item
    val (f, log) =
      recordingFixture(
        onDestroy = { w, owner, _, _ ->
          if (owner === bag) {
            w.moveItem(a, ItemLocation.InRoom(w.room(T.HALL).id, 0))
          }
        }
      )
    val hall = f.w.room(T.HALL)
    bag = f.w.spawnItem(T.BAG, ItemLocation.InRoom(hall.id, 0))
    a = f.w.spawnItem(T.KEY, ItemLocation.Contained(bag.id))
    b = f.w.spawnItem(pebble, ItemLocation.Contained(bag.id))

    f.w.destroy(bag)

    assertNull(f.w.item(bag.id))
    assertNotNull(f.w.item(a.id))
    assertEquals(ItemLocation.InRoom(hall.id, 0), a.location)
    assertNull(f.w.item(b.id))
    assertEquals(
      listOf(
        Record(bag, HookName.ON_DESTROY, bag, "destroy", null),
        Record(hall, HookName.ON_DESTROY, bag, "destroy", null),
        Record(b, HookName.ON_DESTROY, b, "destroy", null),
        Record(hall, HookName.ON_DESTROY, b, "destroy", null),
      ),
      log,
    )
  }

  @Test
  fun `inner item moving itself out skips room hook and survives`() {
    lateinit var a: Item
    val (f, log) =
      recordingFixture(
        onDestroy = { w, owner, _, _ ->
          if (owner === a) {
            w.moveItem(a, ItemLocation.InRoom(w.room(T.HALL).id, 0))
          }
        }
      )
    val hall = f.w.room(T.HALL)
    val bag = f.w.spawnItem(T.BAG, ItemLocation.InRoom(hall.id, 0))
    a = f.w.spawnItem(T.KEY, ItemLocation.Contained(bag.id))

    f.w.destroy(bag)

    assertNull(f.w.item(bag.id))
    assertNotNull(f.w.item(a.id))
    assertEquals(ItemLocation.InRoom(hall.id, 0), a.location)
    assertEquals(
      listOf(
        Record(bag, HookName.ON_DESTROY, bag, "destroy", null),
        Record(hall, HookName.ON_DESTROY, bag, "destroy", null),
        Record(a, HookName.ON_DESTROY, a, "destroy", null),
      ),
      log,
    )
  }

  @Test
  fun `root moving itself to floor is still removed`() {
    lateinit var root: Item
    val (f, log) =
      recordingFixture(
        onDestroy = { w, owner, _, _ ->
          if (owner === root) {
            w.moveItem(root, ItemLocation.InRoom(w.room(T.HALL).id, 0))
          }
        }
      )
    val p = f.player("Ann")
    val hall = f.w.room(T.HALL)
    root = f.w.spawnItem(T.KEY, ItemLocation.Carried(p.id))

    f.w.destroy(root)

    assertNull(f.w.item(root.id))
    assertEquals(
      listOf(
        Record(root, HookName.ON_DESTROY, root, "destroy", p),
        Record(hall, HookName.ON_DESTROY, root, "destroy", p),
      ),
      log,
    )
  }

  @Test
  fun `destroy on root or inner item during walk is a no-op and each is recorded once`() {
    lateinit var bag: Item
    lateinit var b: Item
    val (f, log) =
      recordingFixture(
        onDestroy = { w, owner, _, _ ->
          if (owner === bag) {
            w.destroy(bag)
            w.destroy(b)
          }
        }
      )
    val hall = f.w.room(T.HALL)
    bag = f.w.spawnItem(T.BAG, ItemLocation.InRoom(hall.id, 0))
    b = f.w.spawnItem(pebble, ItemLocation.Contained(bag.id))

    f.w.destroy(bag)

    assertNull(f.w.item(bag.id))
    assertNull(f.w.item(b.id))
    assertEquals(
      listOf(
        Record(bag, HookName.ON_DESTROY, bag, "destroy", null),
        Record(hall, HookName.ON_DESTROY, bag, "destroy", null),
        Record(b, HookName.ON_DESTROY, b, "destroy", null),
        Record(hall, HookName.ON_DESTROY, b, "destroy", null),
      ),
      log,
    )
  }

  @Test
  fun `root hook destroying its container fires each item once with the nested cause below the root`() {
    lateinit var bag: Item
    lateinit var c: Item
    val (f, log) =
      recordingFixture(
        onDestroy = { w, owner, _, _ ->
          if (owner === bag) w.destroy(c, cause = DestroyCause.DECAY)
        }
      )
    val hall = f.w.room(T.HALL)
    c = f.w.spawnItem(chest, ItemLocation.InRoom(hall.id, 0))
    bag = f.w.spawnItem(T.BAG, ItemLocation.Contained(c.id))
    val pebbleItem = f.w.spawnItem(pebble, ItemLocation.Contained(bag.id))

    f.w.destroy(bag)

    assertNull(f.w.item(c.id))
    assertNull(f.w.item(bag.id))
    assertNull(f.w.item(pebbleItem.id))
    assertEquals(
      listOf(
        Record(bag, HookName.ON_DESTROY, bag, "destroy", null),
        Record(c, HookName.ON_DESTROY, c, "decay", null),
        Record(hall, HookName.ON_DESTROY, c, "decay", null),
        Record(pebbleItem, HookName.ON_DESTROY, pebbleItem, "decay", null),
        Record(hall, HookName.ON_DESTROY, pebbleItem, "decay", null),
      ),
      log,
    )
  }

  @Test
  fun `destroyedRoot is the item whose destruction took each item down`() {
    lateinit var bag: Item
    lateinit var c: Item
    val roots = mutableListOf<Pair<Entity, Item?>>()
    val (f, _) =
      recordingFixture(
        onDestroy = { w, owner, ctx, _ ->
          if (owner is Item) roots += owner to ctx.destroyedRoot
          if (owner === bag) w.destroy(c)
        }
      )
    val hall = f.w.room(T.HALL)
    c = f.w.spawnItem(chest, ItemLocation.InRoom(hall.id, 0))
    bag = f.w.spawnItem(T.BAG, ItemLocation.Contained(c.id))
    val pebbleItem = f.w.spawnItem(pebble, ItemLocation.Contained(bag.id))

    f.w.destroy(bag)

    assertEquals(listOf<Pair<Entity, Item?>>(bag to bag, c to c, pebbleItem to c), roots)
  }

  @Test
  fun `an item that escaped and is destroyed again gets its hooks again`() {
    lateinit var a: Item
    var again = false
    val (f, log) =
      recordingFixture(
        onDestroy = { w, owner, _, _ ->
          if (owner === a && !again) {
            again = true
            w.moveItem(a, ItemLocation.InRoom(w.room(T.HALL).id, 0))
            w.destroy(a)
          }
        }
      )
    val hall = f.w.room(T.HALL)
    val bag = f.w.spawnItem(T.BAG, ItemLocation.InRoom(hall.id, 0))
    a = f.w.spawnItem(T.KEY, ItemLocation.Contained(bag.id))

    f.w.destroy(bag)

    assertNull(f.w.item(a.id))
    assertEquals(
      listOf(
        Record(bag, HookName.ON_DESTROY, bag, "destroy", null),
        Record(hall, HookName.ON_DESTROY, bag, "destroy", null),
        Record(a, HookName.ON_DESTROY, a, "destroy", null),
        Record(a, HookName.ON_DESTROY, a, "destroy", null),
        Record(hall, HookName.ON_DESTROY, a, "destroy", null),
      ),
      log,
    )
  }

  @Test
  fun `moving or spawning into a container whose on_destroy already ran throws`() {
    lateinit var bag: Item
    lateinit var other: Item
    var moveError: IllegalArgumentException? = null
    var spawnError: IllegalArgumentException? = null
    val (f, _) =
      recordingFixture(
        onDestroy = { w, owner, _, _ ->
          if (owner === bag) {
            moveError =
              assertFailsWith<IllegalArgumentException> {
                w.moveItem(other, ItemLocation.Contained(bag.id))
              }
            spawnError =
              assertFailsWith<IllegalArgumentException> {
                w.spawnItem(pebble, ItemLocation.Contained(bag.id))
              }
          }
        }
      )
    val hall = f.w.room(T.HALL)
    bag = f.w.spawnItem(T.BAG, ItemLocation.InRoom(hall.id, 0))
    other = f.w.spawnItem(T.KEY, ItemLocation.InRoom(hall.id, 0))

    f.w.destroy(bag)

    assertEquals("${bag.name} is being destroyed", moveError?.message)
    assertEquals("${bag.name} is being destroyed", spawnError?.message)
  }

  @Test
  fun `spawning into inner container before its turn succeeds and is destroyed after it`() {
    lateinit var outer: Item
    lateinit var inner: Item
    var spawned: Item? = null
    val (f, log) =
      recordingFixture(
        onDestroy = { w, owner, _, _ ->
          if (owner === outer) {
            spawned = w.spawnItem(pebble, ItemLocation.Contained(inner.id))
          }
        }
      )
    val hall = f.w.room(T.HALL)
    outer = f.w.spawnItem(chest, ItemLocation.InRoom(hall.id, 0))
    inner = f.w.spawnItem(T.BAG, ItemLocation.Contained(outer.id))

    f.w.destroy(outer)

    val s = assertNotNull(spawned)
    assertNull(f.w.item(s.id))
    assertEquals(
      listOf(
        Record(outer, HookName.ON_DESTROY, outer, "destroy", null),
        Record(hall, HookName.ON_DESTROY, outer, "destroy", null),
        Record(inner, HookName.ON_DESTROY, inner, "destroy", null),
        Record(hall, HookName.ON_DESTROY, inner, "destroy", null),
        Record(s, HookName.ON_DESTROY, s, "destroy", null),
        Record(hall, HookName.ON_DESTROY, s, "destroy", null),
      ),
      log,
    )
  }

  @Test
  fun `decaying ground container drops contents and records only itself with decay`() {
    val (f, log) = recordingFixture()
    val hall = f.w.room(T.HALL)
    val bag = f.w.spawnItem(T.BAG, ItemLocation.InRoom(hall.id, 0))
    val key = f.w.spawnItem(T.KEY, ItemLocation.Contained(bag.id))
    bag.decayAt = f.w.pulse + 1

    f.runTo(bag.decayAt!! + WORLD_TICK_PULSES)

    assertNull(f.w.item(bag.id))
    assertNotNull(f.w.item(key.id))
    assertEquals(ItemLocation.InRoom(hall.id, 0), key.location)
    assertEquals(
      listOf(
        Record(bag, HookName.ON_DESTROY, bag, "decay", null),
        Record(hall, HookName.ON_DESTROY, bag, "decay", null),
      ),
      log,
    )
  }

  @Test
  fun `decaying carried container records itself and its contents with decay`() {
    val (f, log) = recordingFixture()
    val p = f.player("Ann")
    val bag = f.w.spawnItem(T.BAG, ItemLocation.Carried(p.id))
    val key = f.w.spawnItem(T.KEY, ItemLocation.Contained(bag.id))
    bag.decayAt = f.w.pulse + 1

    f.runTo(bag.decayAt!! + WORLD_TICK_PULSES)

    assertNull(f.w.item(bag.id))
    assertNull(f.w.item(key.id))
    assertEquals(
      listOf(
        Record(bag, HookName.ON_DESTROY, bag, "decay", p),
        Record(p.room, HookName.ON_DESTROY, bag, "decay", p),
        Record(key, HookName.ON_DESTROY, key, "decay", p),
        Record(p.room, HookName.ON_DESTROY, key, "decay", p),
      ),
      log,
    )
  }

  @Test
  fun `decay on wake in dormant room records decay`() {
    val (f, log) = recordingFixture(bubble = true)
    val vault = f.w.room(T.VAULT)
    val key = f.w.spawnItem(T.KEY, ItemLocation.InRoom(vault.id, 0))
    key.decayAt = 10
    f.runTo(20)
    assertNotNull(f.w.item(key.id))
    assertTrue(log.isEmpty())

    f.player("Ann", at = T.VAULT)
    f.run()

    assertNull(f.w.item(key.id))
    assertEquals(
      listOf(
        Record(key, HookName.ON_DESTROY, key, "decay", null),
        Record(vault, HookName.ON_DESTROY, key, "decay", null),
      ),
      log,
    )
  }

  @Test
  fun `non-stackable item with empty destroy records empty on last charge`() {
    val (f, log) = recordingFixture()
    val p = f.player("Ann")
    val w = f.w.spawnItem(wand, ItemLocation.Carried(p.id))

    f.w.spendCharge(w)

    assertNull(f.w.item(w.id))
    assertEquals(
      listOf(
        Record(w, HookName.ON_DESTROY, w, "empty", p),
        Record(p.room, HookName.ON_DESTROY, w, "empty", p),
      ),
      log,
    )
  }

  @Test
  fun `stackable item with empty destroy records nothing until its last`() {
    val (f, log) = recordingFixture()
    val p = f.player("Ann")
    val stack = f.w.spawnItem(potion, ItemLocation.Carried(p.id), count = 3)

    f.w.spendCharge(stack)
    f.w.spendCharge(stack)
    assertNotNull(f.w.item(stack.id))
    assertEquals(1, stack.count)
    assertTrue(log.isEmpty())

    f.w.spendCharge(stack)
    assertNull(f.w.item(stack.id))
    assertEquals(
      listOf(
        Record(stack, HookName.ON_DESTROY, stack, "empty", p),
        Record(p.room, HookName.ON_DESTROY, stack, "empty", p),
      ),
      log,
    )
  }

  @Test
  fun `despawn of mob with carried item records nothing`() {
    val (f, log) = recordingFixture()
    val mob = f.w.spawnMob(T.GOBLIN, f.w.room(T.HALL))
    val key = f.w.spawnItem(T.KEY, ItemLocation.Carried(mob.id))

    f.w.despawn(mob)

    assertNull(f.w.item(key.id))
    assertTrue(log.isEmpty())
  }

  @Test
  fun `reload dropping an item prototype records nothing`() {
    val (f, log) = recordingFixture()
    val hall = f.w.room(T.HALL)
    val p = f.w.spawnItem(pebble, ItemLocation.InRoom(hall.id, 0))

    val nextItems = (T.items + extraItems).filter { it.label != pebble }
    f.w.applyPrototypes(Prototypes(T.rooms, T.mobs, nextItems, T.skills))

    assertNull(f.w.item(p.id))
    assertTrue(log.isEmpty())
  }
}
