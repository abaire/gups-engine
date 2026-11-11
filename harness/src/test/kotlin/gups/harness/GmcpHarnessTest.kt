package gups.harness

import gups.core.fixtures.TempFiles
import gups.script.WorldDir
import gups.script.testing.TestWorlds
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class GmcpHarnessTest {
  private fun run(files: Map<String, String>): TestReport {
    val root = TempFiles.dir("gups-gmcp")
    for ((path, text) in TestWorlds.withDefaults(files)) {
      root.resolve(path).also { it.parent.createDirectories() }.writeText(text.trimIndent() + "\n")
    }
    return TestRunner(WorldDir(root)).run(listOf("//zones/a"))
  }

  private val files =
    mapOf(
      "lib/package.star" to "package(api_version = 1, kind = \"lib\")",
      "lib/a.star" to
        """
            gmcp_package("Shop.Stock", doc = "Items for sale")

            def send_stock(ctx):
                return ctx.gmcp(ctx.actor, "Shop.Stock", {"item": "sword"})
        """,
      "zones/a/package.star" to "package(api_version = 1, kind = \"zone\")",
      "zones/a/rooms.star" to
        """
            load("//lib:a.star", "send_stock")

            def _stock(ctx):
                send_stock(ctx)
                ctx.send(ctx.actor, "Here is the stock.")

            room("start", commands = {"stock": _stock})
        """,
      "zones/a/a_test.star" to
        """
            def test_supporting_client_receives_the_event(t):
                w = t.world()
                p = w.spawn_player("Ann", at = ":start", gmcp = ["Shop"])
                t.contains(p.do("stock"), "Here is the stock.")
                t.eq(p.gmcp("Shop"), [{"package": "Shop.Stock", "data": {"item": "sword"}}])
                t.eq(p.gmcp(), [])

            def test_unsupported_client_receives_nothing(t):
                w = t.world()
                p = w.spawn_player("Ann", at = ":start")
                p.do("stock")
                t.eq(p.gmcp(), [])

            def test_engine_packages_reach_the_test_client(t):
                w = t.world()
                p = w.spawn_player("Ann", at = ":start", gmcp = ["Char.Status"])
                w.run(1)
                msgs = p.gmcp("Char")
                t.true(len(msgs) > 0)
                t.eq(msgs[-1]["package"], "Char.Status")
                t.eq(msgs[-1]["data"]["name"], "Ann")
                t.eq(type(msgs[-1]["data"]["wait"]), "int")
        """,
    )

  @Test
  fun `the harness client receives GMCP for the packages it supports`() {
    val report = run(files)
    assertTrue(
      report.ok,
      report.results.joinToString("\n") { "${it.name}: ${it.status} ${it.message}" },
    )
  }
}
