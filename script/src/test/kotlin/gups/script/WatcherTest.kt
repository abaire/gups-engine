package gups.script

import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class WatcherTest {
  @Test
  fun `changes are reported per package after the debounce`() {
    val root =
      tempWorld(
        mapOf(
          "zones/a/package.star" to zonePkg(),
          "zones/a/rooms.star" to "room(\"x\")",
          "zones/b/package.star" to zonePkg(),
          "zones/b/sub/more.star" to "x = 1",
        )
      )
    val w = WorldWatcher(WorldDir(root), debounceNanos = 1000)
    assertEquals(emptyList(), w.poll(0))
    root.resolve("zones/a/rooms.star").writeText("room(\"y\")  # longer now\n")
    root.resolve("zones/b/sub/more.star").writeText("x = 22\n")
    root.resolve("zones/a/a_test.star").writeText("def test_x(t):\n    pass\n")
    assertEquals(emptyList(), w.poll(10), "still within the debounce")
    assertEquals(listOf("zones/a", "zones/b"), w.poll(5000))
    assertEquals(emptyList(), w.poll(10_000))
  }
}
