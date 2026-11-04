package gups.harness

import gups.script.WorldDir
import gups.script.testing.TestWorlds
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shop library in a world without the `combat` feature: it guards `keeper.fighting()` with
 * `ctx.has_feature("combat")`, so a keeper that can't fight also can't be checked for one.
 */
class ShopWithoutCombatTest {
  private val templateWorld: Path =
    Path.of(System.getProperty("gups.templateWorld") ?: "../template-world")

  private fun world(files: Map<String, String>): WorldDir {
    val root: Path = Files.createTempDirectory("gups-shop-nocombat")
    for ((path, text) in files) {
      root.resolve(path).also { it.parent.createDirectories() }.writeText(text.trimIndent() + "\n")
    }
    return WorldDir(root)
  }

  @Test
  fun `test_shop_trades_without_combat passes and no error names the combat feature`() {
    // TestWorlds.withDefaults supplies only the rules, so the library package and file are copied
    // from the template world verbatim.
    val files =
      mapOf(
        "MODULE.gups" to TestWorlds.module("features = [\"skills\", \"leveling\"]"),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rulesFor(listOf("skills", "leveling")),
        "lib/package.star" to templateWorld.resolve("lib/package.star").toFile().readText(),
        "lib/shop.star" to templateWorld.resolve("lib/shop.star").toFile().readText(),
        "zones/z/package.star" to "package(api_version = 1, kind = \"zone\")",
        "zones/z/shop.star" to
          """
            load("//lib:shop.star", "shop")

            item("widget", name = "a widget", stackable = True, value = 10)

            SHOP = shop.define(
                id = "tshop",
                stock = [shop.stock("//zones/z:widget", count = 2, every = 600)],
                till = shop.till(count = 100),
            )

            def _open(ctx):
                shop.open(ctx, ctx.self, SHOP)

            room("start", name = "Shop", flags = ["dead_end_ok"], spawns = [spawn(":keeper")])
            mob("keeper", name = "the keeper", on_spawn = _open)
            """,
        "zones/z/shop_test.star" to
          """
            def test_shop_trades_without_combat(t):
                w = t.world(seed = 1)
                p = w.spawn_player("Tess", at = ":start")
                w.spawn("//lib:gold_coin", at = p, count = 100)
                t.contains(p.do("list"), "offers")
                t.contains(p.do("buy widget"), "You buy")
                t.contains(p.do("value widget"), "would pay")
            """,
      )
    val report = TestRunner(world(files)).run(listOf("//zones/z"))
    assertTrue(report.problems.isEmpty(), report.problems.joinToString("\n"))
    val result = report.results.single()
    assertEquals(Status.PASS, result.status, result.message ?: "")
    assertTrue(
      result.warnings.none { "needs the combat feature" in it },
      result.warnings.joinToString("\n"),
    )
    assertTrue(
      result.message == null || "needs the combat feature" !in result.message,
      result.message,
    )
  }
}
