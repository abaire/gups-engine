package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureRules
import gups.core.model.ContainerProto
import gups.core.model.DoorState
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Prototypes
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** `fits` answers as the put, get and give commands would, and changes nothing. */
class FitsTest {
  private val coin = Label("//t:coin")
  private val brick = Label("//t:brick")
  private val box = Label("//t:box")
  private val ghost = Label("//t:ghost")

  private class Asked(val item: gups.core.model.Item?, val proto: ItemProto?)

  private val asked = mutableListOf<Asked>()
  private var limit = 100

  private val rules =
    object : FixtureRules() {
      override fun carryLimit(c: RulesContext): Int {
        asked += Asked(c.item, c.itemProto)
        return limit
      }
    }

  private val f =
    Fixture(
      rules = rules,
      protos =
        Prototypes(
          T.rooms,
          T.mobs,
          T.items +
            listOf(
              ItemProto(coin, "a coin", listOf("coin"), stackable = true, weight = 2),
              ItemProto(brick, "a brick", listOf("brick"), weight = 4),
              ItemProto(
                box,
                "a box",
                listOf("box"),
                container = ContainerProto(capacity = 2, maxWeight = 10, state = DoorState.LOCKED),
              ),
            ),
          T.skills,
        ),
    )
  private val p = f.player("Ann")
  private val hall = f.w.room(T.HALL)
  private val crate = f.w.spawnItem(box, ItemLocation.InRoom(hall.id, 0))
  private val inCrate = ItemLocation.Contained(crate.id)

  private fun ground(label: Label, count: Int = 1) =
    f.w.spawnItem(label, ItemLocation.InRoom(hall.id, 0), count)

  @Test
  fun `a container's free weight caps a stack and a closed one is still asked`() {
    val coins = ground(coin, 10)
    assertEquals(5, f.w.fits(coins, crate))
    assertEquals(3, f.w.fits(coins, crate, 3))
    assertEquals(DoorState.LOCKED, crate.container?.state)
  }

  @Test
  fun `a container's capacity counts entries`() {
    f.w.spawnItem(brick, inCrate)
    f.w.spawnItem(T.KEY, inCrate)
    assertEquals(0, f.w.fits(ground(coin), crate))
  }

  @Test
  fun `a stack that merges into one there takes no new entry`() {
    f.w.spawnItem(coin, inCrate, 1)
    f.w.spawnItem(brick, inCrate)
    assertEquals(2, f.w.fits(ground(coin, 9), crate))
  }

  @Test
  fun `non-stackable items are each an entry and a weight`() {
    assertEquals(2, f.w.fits(brick, crate, 5))
    f.w.spawnItem(brick, inCrate)
    assertEquals(1, f.w.fits(ground(brick), crate))
  }

  @Test
  fun `a label is sized from its prototype and spawns nothing`() {
    val before = f.w.entities.size
    assertEquals(5, f.w.fits(coin, crate, 8))
    assertEquals(1, f.w.fits(coin, crate))
    assertEquals(before, f.w.entities.size)
  }

  @Test
  fun `a label's stack merges into an existing one`() {
    f.w.spawnItem(coin, inCrate, 1)
    f.w.spawnItem(brick, inCrate)
    assertEquals(2, f.w.fits(coin, crate, 2))
  }

  @Test
  fun `an actor's carry limit asked about the item caps it`() {
    limit = 20
    val coins = ground(coin, 50)
    assertEquals(10, f.w.fits(coins, p))
    assertEquals(coins, asked.single().item)
    assertEquals(coins.proto, asked.single().proto)
  }

  @Test
  fun `a label asked of an actor gives the rules the prototype and no item`() {
    limit = 7
    assertEquals(3, f.w.fits(coin, p, 9))
    assertNull(asked.single().item)
    assertEquals(coin, asked.single().proto?.label)
  }

  @Test
  fun `a room takes everything`() {
    assertEquals(7, f.w.fits(coin, hall, 7))
    assertEquals(50, f.w.fits(ground(coin, 50), f.w.room(T.YARD)))
  }

  @Test
  fun `bad arguments are errors`() {
    assertFailsWith<IllegalArgumentException> { f.w.fits(ghost, crate) }
    assertFailsWith<IllegalArgumentException> { f.w.fits(coin, ground(brick)) }
    assertFailsWith<IllegalArgumentException> { f.w.fits(coin, crate, 0) }
    assertFailsWith<IllegalArgumentException> { f.w.fits(coin, crate, -1) }
    assertFailsWith<IllegalArgumentException> { f.w.fits(ground(coin, 2), crate, 3) }
  }
}
