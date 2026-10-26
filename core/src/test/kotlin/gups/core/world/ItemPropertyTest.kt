package gups.core.world

import gups.core.model.ItemLocation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Random sequences of item verbs never break the single-location or forest invariant. */
class ItemPropertyTest {
  @Test
  fun `random item verbs keep every item in exactly one place`() {
    repeat(30) { seed ->
      val rng = Random(seed)
      val f = Fixture(seed = seed.toLong())
      val ann = f.player("Ann")
      val bob = f.player("Bob")
      val hall = f.w.room(T.HALL)
      repeat(4) { f.w.spawnItem(T.BAG, ItemLocation.InRoom(hall.id, it)) }
      repeat(8) { f.w.spawnItem(T.KEY, ItemLocation.Carried(if (it % 2 == 0) ann.id else bob.id)) }
      repeat(300) {
        val items = f.w.allEntities.filterIsInstance<gups.core.model.Item>().sortedBy { it.id }
        if (items.isEmpty()) return@repeat
        val item = items.random(rng)
        val bags = items.filter { it.container != null }
        runCatching {
          when (rng.nextInt(6)) {
            0 -> f.w.moveItem(item, ItemLocation.Carried(listOf(ann, bob).random(rng).id))
            1 -> bags.randomOrNull(rng)?.let { f.w.moveItem(item, ItemLocation.Contained(it.id)) }
            2 -> f.w.moveItem(item, ItemLocation.InRoom(hall.id, rng.nextInt(5)))
            3 -> f.w.moveItem(item, ItemLocation.Equipped(ann.id, "held"))
            4 -> if (rng.nextInt(10) == 0) f.w.destroy(item)
            else -> f.w.spawnItem(T.KEY, ItemLocation.Carried(bob.id))
          }
        }
        assertEquals(emptyList(), f.w.checkInvariants(), "seed $seed")
      }
    }
  }
}
