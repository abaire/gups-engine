package gups.harness

import gups.script.WorldDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FuzzWalkerTest {

  @Test
  fun `fuzz walker runs on tutorial zone and passes`() {
    val templateWorld = System.getProperty("gups.templateWorld") ?: "../template-world"
    val dir = WorldDir(Path.of(templateWorld))
    val walker = FuzzWalker(dir)
    val outcome = walker.run(listOf("//zones/tutorial/..."), pulses = 50, seed = 12345L)
    assertTrue(outcome.ok, outcome.failureMessage)
    assertEquals(50, outcome.pulsesRun)
    assertEquals(1, outcome.bots)
    assertEquals(listOf("zones/tutorial"), outcome.zones)
  }

  @Test
  fun `fuzz walker fails when unknown zone is targeted`() {
    val templateWorld = System.getProperty("gups.templateWorld") ?: "../template-world"
    val dir = WorldDir(Path.of(templateWorld))
    val walker = FuzzWalker(dir)
    val outcome = walker.run(listOf("//zones/unknown/..."), pulses = 10)
    assertFalse(outcome.ok)
    assertNotNull(outcome.failureMessage)
  }

  @Test
  fun `fuzz walker respawns bot cleanly if it leaves the world`() {
    val root = java.nio.file.Files.createTempDirectory("gups-fuzz-respawn")
    val zoneDir = root.resolve("zones/z").also { java.nio.file.Files.createDirectories(it) }
    java.nio.file.Files.writeString(
      zoneDir.resolve("package.star"),
      "package(api_version = 1, kind = \"zone\")\n",
    )
    java.nio.file.Files.writeString(
      zoneDir.resolve("rooms.star"),
      """
      def _noop(ctx): pass
      room("r", name = "R", flags = ["dead_end_ok"], exits = [], commands = {"quit": _noop})
      """
        .trimIndent(),
    )
    val walker = FuzzWalker(WorldDir(root))
    val outcome = walker.run(listOf("//zones/z/..."), pulses = 30, seed = 42L)
    assertTrue(outcome.ok, outcome.failureMessage)
  }
}
