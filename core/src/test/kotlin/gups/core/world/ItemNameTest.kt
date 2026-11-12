package gups.core.world

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Prototypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ItemNameTest {
  private val bar = Label("//t:bar")

  private fun fixture() =
    Fixture(
      protos =
        Prototypes(
          T.rooms,
          T.mobs,
          T.items + ItemProto(bar, "an iron bar", listOf("bar", "iron"), stackable = true),
          T.skills,
        )
    )

  @Test
  fun `setItemName sets and clears the override and reports a change`() {
    val f = fixture()
    val p = f.player("Ann")
    val item = f.w.spawnItem(bar, ItemLocation.Carried(p.id))
    assertTrue(f.w.setItemName(item, "a gleaming bar"))
    assertEquals("a gleaming bar", item.name)
    assertFalse(f.w.setItemName(item, "a gleaming bar"), "the same name changes nothing")
    assertTrue(item.matches("iron"), "keywords don't change")
    assertTrue(f.w.setItemName(item, null))
    assertNull(item.nameOverride)
    assertEquals("an iron bar", item.name)
    assertFalse(f.w.setItemName(item, null))
  }

  @Test
  fun `a name must be a single line of 1 to 80 characters`() {
    val f = fixture()
    val p = f.player("Ann")
    val item = f.w.spawnItem(bar, ItemLocation.Carried(p.id))
    assertFailsWith<IllegalArgumentException> { f.w.setItemName(item, "two\nlines") }
    assertFailsWith<IllegalArgumentException> { f.w.setItemName(item, "x".repeat(81)) }
    assertFailsWith<IllegalArgumentException> { f.w.setItemName(item, " ") }
    assertTrue(f.w.setItemName(item, "x".repeat(80)))
  }

  @Test
  fun `a renamed item no longer merges into a plain stack`() {
    val f = fixture()
    val p = f.player("Ann")
    val named = f.w.spawnItem(bar, ItemLocation.Carried(p.id))
    f.w.setItemName(named, "a fine bar")
    val plain = f.w.spawnItem(bar, ItemLocation.Carried(p.id), 2)
    assertSame(plain, f.w.stack(plain))
    assertSame(named, f.w.stack(named))
    assertEquals(2, plain.count)
  }
}
