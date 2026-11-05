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
import kotlin.test.assertTrue

class SkillVerbsHarnessTest {
  private fun world(files: Map<String, String>): WorldDir {
    val root: Path = Files.createTempDirectory("gups-skill-verbs")
    for ((path, text) in TestWorlds.withDefaults(files)) {
      root.resolve(path).also { it.parent.createDirectories() }.writeText(text.trimIndent() + "\n")
    }
    return WorldDir(root)
  }

  private val zone =
    mapOf(
      "zones/a/package.star" to "package(api_version = 1, kind = \"zone\")",
      "zones/a/skills.star" to "skill(\"zap\", kind = \"spell\")\nskill(\"mend\")",
      "zones/a/rooms.star" to "room(\"start\", name = \"Start\")",
    )

  private fun run(files: Map<String, String>, vararg patterns: String): TestReport =
    TestRunner(world(files)).run(patterns.toList())

  @Test
  fun `set_skill sets and raises, and remove_skill forgets, a skill`() {
    val report =
      run(
        zone +
          mapOf(
            "zones/a/skill_verbs_test.star" to
              """
              def test_set_and_remove(t):
                  w = t.world()
                  p = w.spawn_player("Pat", at = ":start")

                  res = w.set_skill(p, ":zap", 30)
                  t.eq(res, None)
                  t.eq(p.skills["//zones/a:zap"], 30)

                  res = w.set_skill(p, ":zap", 55)
                  t.eq(res, None)
                  t.eq(p.skills["//zones/a:zap"], 55)

                  knew = w.remove_skill(p, ":zap")
                  t.true(knew)
                  t.false("zap" in p.skills)

                  again = w.remove_skill(p, ":zap")
                  t.false(again)
              """
          ),
        "//zones/a",
      )
    assertTrue(report.ok, report.results.joinToString("\n") { it.render() })
  }

  @Test
  fun `an unknown skill and an out-of-range proficiency are errors naming the argument`() {
    for (call in listOf("set_skill(p, \":nope\", 10)", "set_skill(p, \":zap\", 101)")) {
      val report =
        run(
          zone +
            mapOf(
              "zones/a/bad_test.star" to
                """
                def test_bad(t):
                    w = t.world()
                    p = w.spawn_player("Pat", at = ":start")
                    w.$call
                """
            ),
          "//zones/a",
        )
      val result = report.results.single()
      assertEquals(Status.ERROR, result.status, call)
    }
    val unknown =
      run(
        zone +
          mapOf(
            "zones/a/bad_test.star" to
              """
              def test_bad(t):
                  w = t.world()
                  p = w.spawn_player("Pat", at = ":start")
                  w.set_skill(p, ":nope", 10)
              """
          ),
        "//zones/a",
      )
    assertContains(unknown.results.single().message!!, "set_skill(skill): unknown skill")
    val high =
      run(
        zone +
          mapOf(
            "zones/a/bad_test.star" to
              """
              def test_bad(t):
                  w = t.world()
                  p = w.spawn_player("Pat", at = ":start")
                  w.set_skill(p, ":zap", 101)
              """
          ),
        "//zones/a",
      )
    assertContains(
      high.results.single().message!!,
      "set_skill(proficiency): must be 0 to 100, got 101",
    )
  }

  @Test
  fun `the w skill verbs fail with the feature refusal when skills is off`() {
    val off = listOf("combat", "track", "leveling")
    for (call in listOf("set_skill(p, \"zap\", 10)", "remove_skill(p, \"zap\")")) {
      val report =
        run(
          zone - "zones/a/skills.star" +
            mapOf(
              "MODULE.gups" to TestWorlds.module("features = [${off.joinToString { "\"$it\"" }}]"),
              "rules/package.star" to TestWorlds.RULES_PACKAGE,
              "rules/combat.star" to TestWorlds.rulesFor(off),
              "zones/a/off_test.star" to
                """
                def test_off(t):
                    w = t.world()
                    p = w.spawn_player("Pat", at = ":start")
                    w.$call
                """,
            ),
          "//zones/a",
        )
      val result = report.results.single()
      assertEquals(Status.ERROR, result.status, call)
      val name = call.substringBefore('(')
      assertContains(result.message!!, "w.$name() needs the skills feature")
    }
  }
}
