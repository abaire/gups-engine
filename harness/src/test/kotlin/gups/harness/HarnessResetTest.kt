package gups.harness

import gups.core.fixtures.TempFiles
import gups.script.WorldDir
import gups.script.testing.TestWorlds
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class HarnessResetTest {
  private fun world(files: Map<String, String>): WorldDir {
    val root: Path = TempFiles.dir("gups-harness-reset")
    for ((path, text) in TestWorlds.withDefaults(files)) {
      root.resolve(path).also { it.parent.createDirectories() }.writeText(text.trimIndent() + "\n")
    }
    return WorldDir(root)
  }

  private val files =
    mapOf(
      "zones/a/package.star" to "package(api_version = 1, kind = \"zone\", reset_world_ticks = 10)",
      "zones/a/rooms.star" to
        """
        def log_reset(ctx):
            log = list(ctx.get_attr(ctx.self, "events", []))
            log.append("reset")
            ctx.set_attr(ctx.self, "events", log)

        def log_place(ctx):
            log = list(ctx.get_attr(ctx.room, "events", []))
            log.append("place")
            ctx.set_attr(ctx.room, "events", log)

        item("key", name = "a key")
        room("start", name = "Start",
             exits = [exit("east", to = ":mid")],
             spawns = [spawn(":key", id = "start_key", respawn = "reset")],
             on_reset = log_reset)
        room("mid", name = "Mid",
             exits = [exit("west", to = ":start"), exit("east", to = ":far")])
        room("far", name = "Far",
             exits = [exit("west", to = ":mid")],
             on_reset = log_reset)
        spawner("far_key", ":key", rooms = [":far"], respawn = "reset", on_place = log_place)
        """,
      "zones/none/package.star" to "package(api_version = 1, kind = \"zone\")",
      "zones/none/rooms.star" to "room(\"r\", name = \"R\")",
    )

  private fun run(testSource: String): Map<String, TestResult> {
    val report =
      TestRunner(world(files + ("zones/a/reset_test.star" to testSource))).run(listOf("//zones/a"))
    return report.results.associateBy { it.name }
  }

  @Test
  fun `w reset refills a reset rule and fires on_reset`() {
    val results =
      run(
        """
        def test_reset_refills_and_fires_hook(t):
            w = t.world()
            c = w.ctx(":start")
            c.destroy(w.room(":start").items()[0])
            t.eq(len(w.room(":start").items()), 0)
            w.reset()
            t.eq(len(w.room(":start").items()), 1)
            t.eq(w.get_attr(w.room(":start"), "events"), ["reset"])
        """
      )
    val r = results.values.single()
    assertEquals(Status.PASS, r.status, r.render())
  }

  @Test
  fun `w reset holds dormant pool copy and waking places it before missed on_reset`() {
    val results =
      run(
        """
        def test_dormant_pool_wake_order(t):
            w = t.world(bubble = True)
            far = w.room(":far")
            # Clear the initial key and event log from first fill.
            w.ctx(":far").destroy(far.items()[0])
            w.set_attr(far, "events", [])
            # Far is dormant (no player nearby); reset holds the replacement and marks reset missed.
            w.reset()
            t.eq(len(far.items()), 0)
            t.eq(w.ctx().spawner_info(":far_key").held, 1)
            t.eq(w.get_attr(far, "events"), [])
            # Walking a player into :mid wakes :far (distance 1): held copy is placed, then missed on_reset runs.
            p = w.spawn_player("Ann", at = ":start")
            p.do("east")
            t.eq(len(far.items()), 1)
            t.eq(w.get_attr(far, "events"), ["place", "reset"])
        """
      )
    val r = results.values.single()
    assertEquals(Status.PASS, r.status, r.render())
  }

  @Test
  fun `w reset errors for a package without reset_world_ticks`() {
    val results =
      run(
        """
        def test_no_reset(t):
            w = t.world()
            w.reset("//zones/none")
        """
      )
    val r = results.values.single()
    assertEquals(Status.ERROR, r.status, r.render())
    assertContains(r.message!!, "has no zone reset")
  }
}
