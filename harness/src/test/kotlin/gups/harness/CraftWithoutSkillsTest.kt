package gups.harness

import gups.core.fixtures.TempFiles
import gups.script.WorldDir
import gups.script.testing.TestWorlds
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The craft library outside the template world: without `skills`, a recipe naming a skill is
 * refused and one without crafts at power 100; with `skills`, every rolled craft reports a skill
 * use.
 */
class CraftWithoutSkillsTest {
  private val templateWorld: Path =
    Path.of(System.getProperty("gups.templateWorld") ?: "../template-world")

  private fun world(files: Map<String, String>): WorldDir {
    val root: Path = TempFiles.dir("gups-craft")
    for ((path, text) in files) {
      root.resolve(path).also { it.parent.createDirectories() }.writeText(text.trimIndent() + "\n")
    }
    return WorldDir(root)
  }

  private fun lib(): Map<String, String> =
    mapOf(
      "lib/package.star" to templateWorld.resolve("lib/package.star").toFile().readText(),
      "lib/craft.star" to templateWorld.resolve("lib/craft.star").toFile().readText(),
    )

  private val zone =
    """
      load("//lib:craft.star", "craft")

      item("ore", name = "a lump of ore", stackable = True)
      item("ingot", name = "an ingot")

      _SMELT = craft.recipe("smelt", verb = "smelt", inputs = [craft.need("//zones/z:ore")], time = 2,
                            qualities = [craft.quality(at = 100, name = "a perfect ingot")],
                            result = craft.make("//zones/z:ingot"))
      _FORGE = craft.recipe("forge", verb = "forge", inputs = [craft.need("//zones/z:ore")], time = 2,
                            skill = "//zones/z:smithing", result = craft.make("//zones/z:ingot"))

      def _recipes(ctx):
          return [_SMELT, _FORGE]

      room("start", name = "Forge", flags = ["dead_end_ok"], hooks = {"craft_recipes": _recipes})
    """

  private fun runOne(files: Map<String, String>): TestResult {
    val report = TestRunner(world(files)).run(listOf("//zones/z"))
    assertTrue(report.problems.isEmpty(), report.problems.joinToString("\n"))
    val result = report.results.single()
    assertEquals(Status.PASS, result.status, result.message ?: "")
    return result
  }

  @Test
  fun `without skills a skill-less recipe crafts at power 100 and a skill recipe is refused`() {
    val features = listOf("combat")
    val files =
      lib() +
        mapOf(
          "MODULE.gups" to TestWorlds.module("features = [\"combat\"]"),
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to TestWorlds.rulesFor(features),
          "zones/z/package.star" to "package(api_version = 1, kind = \"zone\")",
          "zones/z/craft.star" to zone,
          "zones/z/craft_test.star" to
            """
              def test_craft_without_skills(t):
                  w = t.world(seed = 1)
                  p = w.spawn_player("Tess", at = ":start")
                  w.spawn("//zones/z:ore", at = p, count = 2)
                  t.contains(p.do("recipes"), "forge: forge (forge) — needs the skills feature")
                  t.contains(p.do("craft smelt"), "You smelt a perfect ingot.")
                  t.contains(p.do("craft forge"), "That recipe needs skills this world doesn't have.")
            """,
        )
    val result = runOne(files)
    assertTrue(
      result.warnings.none { "needs the" in it && "feature" in it },
      result.warnings.joinToString("\n"),
    )
  }

  @Test
  fun `every rolled craft reports a skill use and a skill-less one doesn't`() {
    val files =
      lib() +
        mapOf(
          "MODULE.gups" to TestWorlds.module(),
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to TestWorlds.rules(),
          "lib/uses.star" to
            """
              def _count(ctx):
                  n = ctx.get_attr(ctx.self, "uses", 0)
                  ctx.set_attr(ctx.self, "uses", n + 1)
                  ctx.set_attr(ctx.self, "last_use", "%s:%s:%s" % (ctx.outcome, ctx.skill.label, ctx.item.name if ctx.item else None))

              global_hook("on_skill_use", _count)
            """,
          "zones/z/package.star" to "package(api_version = 1, kind = \"zone\")",
          "zones/z/skills.star" to "skill(\"smithing\", kind = \"craft\")",
          "zones/z/craft.star" to zone,
          "zones/z/craft_test.star" to
            """
              def test_reports(t):
                  w = t.world(seed = 1)
                  p = w.spawn_player("Tess", at = ":start")
                  w.spawn("//zones/z:ore", at = p, count = 3)
                  p.do("craft forge")
                  p.do("craft smelt")
                  p.do("craft forge")
                  t.eq(w.get_attr(p, "lib:uses", 0), 2)
                  t.eq(w.get_attr(p, "lib:last_use", ""), "report://zones/z:smithing:an ingot")
            """,
        )
    runOne(files)
  }
}
