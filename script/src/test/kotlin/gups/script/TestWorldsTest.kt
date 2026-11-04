package gups.script

import gups.script.testing.TestWorlds
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class TestWorldsTest {
  @TempDir lateinit var tmp: Path

  @Test
  fun `copying a world leaves out its local server data`() {
    val from = tmp.resolve("template")
    from.resolve("zones/z").createDirectories()
    from.resolve("MODULE.gups").writeText("module()\n")
    from.resolve("zones/z/zone.gups").writeText("zone()\n")
    from.resolve(".gups/backups").createDirectories()
    from.resolve(".gups/world.db").writeText("stale")

    val to = TestWorlds.copyWorld(from, tmp.resolve("copy"))

    assertEquals("module()\n", to.resolve("MODULE.gups").readText())
    assertEquals("zone()\n", to.resolve("zones/z/zone.gups").readText())
    assertFalse(to.resolve(".gups").exists())
    assertEquals(
      listOf("MODULE.gups", "zones"),
      Files.list(to).use { s -> s.map { it.fileName.toString() }.sorted().toList() },
    )
  }

  @Test
  fun `an override's preamble keeps the template's`() {
    val rules =
      TestWorlds.rules(
        """
        # An override's own comment, before its first def.
        _BONUS = 1

        def damage(ctx):
            return 5 + _BONUS
        """
      )
    assertTrue("_PHYSICAL = " in rules, "the template's constants stay: $rules")
    assertTrue("_BONUS = 1" in rules, "the override's constants are added: $rules")
  }
}
