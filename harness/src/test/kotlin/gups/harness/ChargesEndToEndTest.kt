package gups.harness

import gups.script.WorldDir
import gups.script.testing.TestWorlds
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class ChargesEndToEndTest {
  private fun run(files: Map<String, String>): TestReport {
    val root = Files.createTempDirectory("gups-charges")
    for ((path, text) in TestWorlds.withDefaults(files)) {
      root.resolve(path).also { it.parent.createDirectories() }.writeText(text.trimIndent() + "\n")
    }
    return TestRunner(WorldDir(root)).run(listOf("//zones/a"))
  }

  @Test
  fun `a potion is used up and a kept wand is refused once empty`() {
    val report =
      run(
        mapOf(
          "zones/a/package.star" to "package(api_version = 1, kind = \"zone\")",
          "zones/a/rooms.star" to
            """
                room("start", name = "Start")

                item("potion",
                    name = "a potion",
                    keywords = ["potion"],
                    charges = 1,
                    use_effect = heal(dice = "1d1+4", vital = "hp"),
                )

                item("wand",
                    name = "a wand",
                    keywords = ["wand"],
                    charges = 2,
                    empty = "keep",
                    use_effect = heal(dice = "1d1", vital = "hp"),
                )
            """,
          "zones/a/a_test.star" to
            """
                def test_potion_heals_and_is_gone(t):
                    w = t.world()
                    p = w.spawn_player("Pat", at = ":start", vitals = vitals(hp = 30))
                    w.damage(p, "hp", amount = 10)
                    w.spawn(":potion", at = p)
                    t.contains(p.do("use potion"), "You use a potion.")
                    t.eq(p.hp, 25)
                    t.false(p.has_item(":potion"))

                def test_wand_is_refused_on_the_third_use(t):
                    w = t.world()
                    p = w.spawn_player("Pat", at = ":start")
                    w.spawn(":wand", at = p)
                    t.contains(p.do("use wand"), "You use a wand.")
                    t.contains(p.do("use wand"), "You use a wand.")
                    t.contains(p.do("use wand"), "There are no charges left in a wand.")
                    t.true(p.has_item(":wand"))
            """,
        )
      )
    assertTrue(
      report.ok,
      report.results.joinToString("\n") { "${it.name}: ${it.status} ${it.message}" },
    )
  }
}
