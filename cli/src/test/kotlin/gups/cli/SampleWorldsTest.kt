package gups.cli

import com.github.ajalt.clikt.testing.test
import gups.core.Label
import gups.script.WorldDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sample worlds are held to the same gates as a course world: lint, test and fuzz. */
class SampleWorldsTest {
  private val robot = System.getProperty("gups.robotWorld")

  @Test
  fun `the robot world boots as a server with its own schema`() {
    val logs = StringBuffer()
    val server =
      GupsServer(WorldDir(Path.of(robot)), port = 0, db = null) { logs.append(it).append('\n') }
    assertTrue(server.boot(), logs.toString())
    assertEquals(listOf("integrity", "sanity", "power", "rads"), server.world.schema.vitalNames)
    val gate = server.world.room(Label("//zones/foundry:gate"))
    val ann = server.world.spawnPlayer("Ann", gate.label)
    assertEquals(listOf("integrity", "sanity", "power", "rads"), ann.vitals.keys.toList())
  }

  @Test
  fun `the robot world lints clean`() {
    val result = gupsCommand().test(listOf("lint", "--world", robot, "//..."))
    assertEquals(0, result.statusCode, result.output)
  }

  @Test
  fun `the robot world's tests pass`() {
    val result = gupsCommand().test(listOf("test", "--world", robot, "//..."))
    assertEquals(0, result.statusCode, result.output)
    assertContains(result.stdout, "0 failed, 0 errors")
  }

  @Test
  fun `the robot world survives the fuzz walker`() {
    val result =
      gupsCommand()
        .test(listOf("fuzz", "--pulses", "2000", "--world", robot, "//zones/foundry/..."))
    assertEquals(0, result.statusCode, result.output)
    assertContains(result.stdout, "Fuzz walker completed 2000 pulses")
  }
}
