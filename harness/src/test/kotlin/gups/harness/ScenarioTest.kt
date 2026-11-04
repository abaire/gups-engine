package gups.harness

import gups.script.WorldDir
import gups.script.testing.TestWorlds
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScenarioTest {
  private fun world(files: Map<String, String>): WorldDir {
    val root: Path = Files.createTempDirectory("gups-scenario-world")
    for ((path, text) in TestWorlds.withDefaults(files)) {
      root.resolve(path).also { it.parent.createDirectories() }.writeText(text.trimIndent() + "\n")
    }
    return WorldDir(root)
  }

  /** A scenario file in its own directory, outside any world. */
  private fun scenarioFile(text: String, name: String = "probe.star"): Path {
    val dir = Files.createTempDirectory("gups-scenario-file")
    return dir.resolve(name).also { it.writeText(text.trimIndent() + "\n") }
  }

  private val zone =
    mapOf(
      "zones/a/package.star" to "package(api_version = 1, kind = \"zone\")",
      "zones/a/rooms.star" to
        """
            def _ring(ctx):
                ctx.send(ctx.actor, "The bell rings!")
                ctx.echo(ctx.actor.room, "A bell rings nearby.", exclude = [ctx.actor])

            room("start", name = "Start", exits = [exit("north", ":hall")], commands = {"ring": _ring})
            room("hall", name = "Hall", exits = [exit("south", ":start")])
        """,
      "zones/a/util.star" to
        """
            GREETING = "hello from the world"
        """,
    )

  private fun scenario(world: Map<String, String>, text: String, pkg: String = "//zones/a") =
    TestRunner(world(world)).scenario(scenarioFile(text), pkg)

  @Test
  fun `a scenario file outside the world runs as if it were in the named package`() {
    val r =
      scenario(
        zone,
        """
            load("//zones/a:util.star", "GREETING")

            def test_walk(t):
                w = t.world(seed = 1)
                p = w.spawn_player("Pat", at = ":start")
                t.contains(p.do("north"), "Hall")
                print(GREETING)
        """,
      )
    assertTrue(r.problems.isEmpty(), r.problems.joinToString("\n"))
    val result = r.results.single()
    assertEquals(Status.PASS, result.status, result.message ?: "")
    assertEquals("test_walk", result.name)
    assertContains(result.transcript, "hello from the world")
  }

  @Test
  fun `a passing scenario still reports its trace`() {
    val r =
      scenario(
        zone,
        """
            def test_trace(t):
                w = t.world(seed = 1)
                w.trace()
                w.spawn_player("Pat", at = ":start")
        """,
      )
    val result = r.results.single()
    assertEquals(Status.PASS, result.status, result.message ?: "")
    assertContains(result.trace.orEmpty(), "rules.starting_stats on rules")
    assertContains(result.render(transcript = true), "trace:")
  }

  @Test
  fun `the transcript tags each command with the player and room, and notes moves`() {
    val r =
      scenario(
        zone,
        """
            def test_two(t):
                w = t.world(seed = 1)
                p = w.spawn_player("Pat", at = ":start")
                q = w.spawn_player("Quin", at = ":start")
                p.do("ring")
                print("between")
                q.output()
                p.do("north")
        """,
      )
    val transcript = r.results.single().transcript
    val expectedOrder =
      listOf(
        "Pat@//zones/a:start> ring",
        "  The bell rings!",
        "between",
        "Quin@//zones/a:start (output)",
        "  A bell rings nearby.",
        "Pat@//zones/a:start> north",
        "  (Pat is now in //zones/a:hall)",
      )
    var at = -1
    for (line in expectedOrder) {
      val i = transcript.indexOf(line, at + 1)
      assertTrue(i > at, "expected '$line' after position $at in:\n$transcript")
      at = i
    }
  }

  @Test
  fun `a failing scenario reports the failure and keeps its transcript`() {
    val r =
      scenario(
        zone,
        """
            def test_wrong(t):
                w = t.world(seed = 1)
                p = w.spawn_player("Pat", at = ":start")
                t.contains(p.do("ring"), "a gong")
        """,
      )
    val result = r.results.single()
    assertEquals(Status.FAIL, result.status)
    assertContains(result.transcript, "Pat@//zones/a:start> ring")
    val shown = result.render(transcript = true)
    assertContains(shown, "Pat@//zones/a:start> ring")
    assertContains(shown, "a gong")
    assertFalse(r.ok)
  }

  @Test
  fun `test files outside a scenario record no transcript`() {
    val report =
      TestRunner(
          world(
            zone +
              ("zones/a/a_test.star" to
                """
                    def test_walk(t):
                        w = t.world(seed = 1)
                        w.spawn_player("Pat", at = ":start").do("north")
                """)
          )
        )
        .run(listOf("//zones/a"))
    assertEquals("", report.results.single().transcript)
  }

  @Test
  fun `bad packages, file names and empty scenarios are problems`() {
    val unknown = scenario(zone, "def test_x(t):\n    pass", pkg = "//zones/nope")
    assertContains(unknown.problems.joinToString(), "no package //zones/nope")
    val badLabel = scenario(zone, "def test_x(t):\n    pass", pkg = "zones/a")
    assertContains(badLabel.problems.joinToString(), "package label such as //zones/forest")
    val runner = TestRunner(world(zone))
    val badName =
      runner.scenario(scenarioFile("def test_x(t):\n    pass", "probe.txt"), "//zones/a")
    assertContains(badName.problems.joinToString(), "a .star file")
    val empty = scenario(zone, "X = 1")
    assertContains(empty.problems.joinToString(), "no test_ functions")
    val broken = scenario(zone, "def test_x(t):\n    nope(")
    assertEquals(Status.ERROR, broken.results.single().status)
  }

  @Test
  fun `a scenario sees the package's fixture files`() {
    val r =
      scenario(
        zone + ("zones/a/probe_testdata.star" to "item(\"probe\", name = \"a probe\")"),
        """
            def test_probe(t):
                w = t.world()
                p = w.spawn_player("Pat", at = ":start")
                w.spawn(":probe", at = p)
                t.true(p.has_item(":probe"))
        """,
      )
    val result = r.results.single()
    assertEquals(Status.PASS, result.status, result.message ?: "")
  }
}
