package gups.script

import gups.core.model.WorldSchema
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `skill(unrecognized=)`: the load checks, the prototype field and the script view. */
class SkillUnrecognizedTest {
  private fun load(skill: String) =
    ScriptLoader(
        WorldDir(
          tempWorld(
            mapOf(
              "zones/z/package.star" to zonePkg(),
              "zones/z/content.star" to "$skill\nroom(\"start\")\n",
            )
          )
        ),
        ScriptLimits.HARNESS,
      )
      .load(listOf("zones/z"))

  private fun ok(skill: String): LoadResult {
    val r = load(skill)
    assertTrue(r.ok, r.report())
    return r
  }

  private fun fails(skill: String, message: String) {
    val r = load(skill)
    assertFalse(r.ok, "expected a load error for: $skill")
    assertContains(r.report(), message)
  }

  @Test
  fun `every key is accepted and lands on the prototype`() {
    val r =
      ok(
        """
        skill("bolt", unrecognized = {
            "begin": "{actor} gestures.",
            "room": "{actor} hurls a bolt at {target}.",
            "victim": "{actor} hurls a bolt at you.",
            "resisted_room": "{target} shrugs off {actor}'s bolt.",
            "resisted_victim": "You shrug off {actor}'s bolt.",
            "noun": "bolt",
        })
        """
          .trimIndent()
      )
    val skill = r.prototypes().skills.values.single()
    assertEquals(
      mapOf(
        "begin" to "{actor} gestures.",
        "room" to "{actor} hurls a bolt at {target}.",
        "victim" to "{actor} hurls a bolt at you.",
        "resisted_room" to "{target} shrugs off {actor}'s bolt.",
        "resisted_victim" to "You shrug off {actor}'s bolt.",
        "noun" to "bolt",
      ),
      skill.unrecognized,
    )
  }

  @Test
  fun `a skill without unrecognized has an empty map`() {
    assertEquals(
      emptyMap(),
      ok("skill(\"plain\")").prototypes().skills.values.single().unrecognized,
    )
  }

  @Test
  fun `an unknown key fails with a suggestion`() {
    fails("skill(\"s\", unrecognized = {\"vicim\": \"x\"})", "did you mean 'victim'")
  }

  @Test
  fun `an empty value fails`() {
    fails("skill(\"s\", unrecognized = {\"room\": \"\"})", "skill(unrecognized[\"room\"])")
  }

  @Test
  fun `a multi-line value fails`() {
    fails("skill(\"s\", unrecognized = {\"room\": \"a\\nb\"})", "skill(unrecognized[\"room\"])")
  }

  @Test
  fun `a non-string value fails`() {
    fails("skill(\"s\", unrecognized = {\"room\": 3})", "skill(unrecognized[\"room\"])")
  }

  @Test
  fun `target on a self skill fails`() {
    fails(
      "skill(\"s\", target = \"self\", unrecognized = {\"room\": \"{actor} at {target}\"})",
      "skill(unrecognized[\"room\"]): {target} needs a skill with a target",
    )
  }

  @Test
  fun `target on none and room skills fails`() {
    for (kind in listOf("none", "room")) {
      fails(
        "skill(\"s\", target = \"$kind\", unrecognized = {\"victim\": \"{target}\"})",
        "skill(unrecognized[\"victim\"]): {target} needs a skill with a target",
      )
    }
  }

  @Test
  fun `target on an enemy skill passes`() {
    ok("skill(\"s\", target = \"enemy\", unrecognized = {\"room\": \"{actor} at {target}\"})")
  }

  @Test
  fun `target in room on a range room skill fails but in resisted_room passes`() {
    fails(
      "skill(\"s\", range = \"room\", unrecognized = {\"room\": \"{actor} at {target}\"})",
      "skill(unrecognized[\"room\"]): {target} needs a skill with a target",
    )
    ok("skill(\"s\", range = \"room\", unrecognized = {\"resisted_room\": \"{target} resists\"})")
  }

  @Test
  fun `an unknown placeholder fails`() {
    fails(
      "skill(\"s\", unrecognized = {\"room\": \"{caster} acts\"})",
      "skill(unrecognized[\"room\"])",
    )
  }

  @Test
  fun `a doubled brace is not a placeholder`() {
    ok("skill(\"s\", unrecognized = {\"room\": \"{actor} says {{caster}}\"})")
  }

  @Test
  fun `noun takes no placeholders`() {
    fails(
      "skill(\"s\", unrecognized = {\"noun\": \"{actor}'s bolt\"})",
      "skill(unrecognized[\"noun\"])",
    )
  }

  @Test
  fun `the view returns the dict sorted by key`() {
    val r =
      ok("skill(\"s\", unrecognized = {\"noun\": \"bolt\", \"begin\": \"{actor} gestures.\"})")
    val view = SkillView(r.prototypes().skills.values.single(), WorldSchema.EMPTY)
    val dict = view.unrecognized() as net.starlark.java.eval.Dict<*, *>
    assertEquals(listOf("begin", "noun"), dict.keys.map { it.toString() })
    assertEquals("bolt", dict["noun"])
  }
}
