package gups.script

import gups.core.world.Feature
import gups.core.world.Features
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `features`, `rename` and `MESSAGES.gups`. */
class WorldFeaturesTest {
  private fun module(extra: String) =
    WorldModule.read(WorldDir(tempWorld(mapOf("MODULE.gups" to TestWorlds.module(extra))))).first

  private fun messages(text: String?): WorldMessages {
    val dir = tempWorld(if (text == null) emptyMap() else mapOf("MESSAGES.gups" to text))
    return WorldMessages.read(WorldDir(dir)).first
  }

  @Test
  fun `features default to all four and keep their order`() {
    assertEquals(Features.ALL, module("").features)
    assertEquals(
      Features.ALL,
      module("features = [\"leveling\", \"skills\", \"track\", \"combat\", \"standing\"]").features,
    )
    val some = module("features = [\"skills\"]")
    assertEquals(listOf("skills"), some.features.ids)
    assertTrue(some.problems().isEmpty())
    assertEquals(emptyList(), module("features = []").features.ids)
  }

  @Test
  fun `an unknown feature stops the server with a suggestion`() {
    val m = module("features = [\"combat\", \"levelling\"]")
    assertContains(m.problems().single(), "unknown feature 'levelling' (did you mean 'leveling'?)")
  }

  @Test
  fun `track without combat is a problem`() {
    assertContains(
      module("features = [\"track\", \"skills\"]").problems().single(),
      "feature 'track' needs the 'combat' feature",
    )
    assertTrue(module("features = [\"track\", \"combat\"]").problems().isEmpty())
  }

  @Test
  fun `renames apply to commands of the world and to no others`() {
    val m =
      module("rename = {\"cast\": \"invoke\", \"kill\": \"attack\"}, aliases = {\"c\": \"invoke\"}")
    assertTrue(m.problems().isEmpty(), m.problems().toString())
    assertTrue("invoke" in m.commandNames && "cast" !in m.commandNames)
    assertTrue("attack" in m.commandNames && "kill" !in m.commandNames)
  }

  @Test
  fun `each bad rename is a problem`() {
    fun problem(rename: String, extra: String = "") =
      module("rename = $rename${if (extra.isEmpty()) "" else ", $extra"}")
        .problems()
        .joinToString("\n")
    assertContains(
      problem("{\"zork\": \"invoke\"}"),
      "'zork' is not an engine command of this world",
    )
    assertContains(problem("{\"@where\": \"loc\"}"), "'@where' is not an engine command")
    assertContains(problem("{\"cast\": \"Invoke\"}"), "must match [a-z][a-z0-9_]*")
    assertContains(problem("{\"cast\": \"1st\"}"), "must match [a-z][a-z0-9_]*")
    assertContains(problem("{\"cast\": \"north\"}"), "'north' is a direction")
    assertContains(problem("{\"cast\": \"look\"}"), "'look' is already an engine command")
    assertContains(
      problem("{\"cast\": \"c\"}", "aliases = {\"c\": \"kill\"}"),
      "'c' is already an alias",
    )
    assertContains(problem("{\"cast\": \"x\", \"kill\": \"x\"}"), "given for more than one command")
    assertContains(
      module("features = [\"combat\"], rename = {\"cast\": \"invoke\"}").problems().single(),
      "'cast' is not an engine command of this world",
    )
  }

  @Test
  fun `aliases and prefixes name commands by their new names`() {
    assertContains(
      module("rename = {\"cast\": \"invoke\"}, aliases = {\"c\": \"cast\"}").problems().single(),
      "alias 'c' names 'cast', which is not an engine command",
    )
    assertContains(
      module("rename = {\"cast\": \"invoke\"}, prefixes = {\"%\": \"cast\"}").problems().single(),
      "prefix '%' names 'cast', which is not an engine command",
    )
    assertTrue(
      module(
          "rename = {\"cast\": \"invoke\"}, aliases = {\"c\": \"invoke\"}, prefixes = {\"%\": \"invoke\"}"
        )
        .problems()
        .isEmpty()
    )
    // An alias can't name a command whose feature is off.
    assertContains(
      module("features = [\"combat\"], aliases = {\"c\": \"cast\"}").problems().single(),
      "alias 'c' names 'cast'",
    )
  }

  private fun load(extra: String, files: Map<String, String>): LoadResult =
    ScriptLoader(
        WorldDir(tempWorld(files + ("MODULE.gups" to TestWorlds.module(extra)))),
        ScriptLimits.HARNESS,
      )
      .load(listOf("lib", "zones/z"))

  private val renamed =
    mapOf(
      "lib/package.star" to libPkg(),
      "lib/a.star" to "def f(ctx):\n    pass\nglobal_command(\"cast\", f)",
      "zones/z/package.star" to zonePkg(),
      "zones/z/a.star" to "room(\"start\")",
    )
  private val exitNamed =
    renamed + ("zones/z/a.star" to "room(\"start\", exits = [exit(\"cast\", \":start\")])") -
      "lib/a.star" + ("lib/a.star" to "x = 1")

  @Test
  fun `a renamed-away name is free for global commands and exits`() {
    val before = load("", renamed)
    assertContains(
      before.errors.first { it.pkg == "lib" }.message,
      "'cast' clashes with the engine command",
    )
    assertContains(
      load("", exitNamed).errors.first { it.pkg == "zones/z" }.message,
      "'cast' is a reserved keyword",
    )
    for (files in listOf(renamed, exitNamed)) {
      val after = load("rename = {\"cast\": \"invoke\"}", files)
      assertTrue(after.ok, after.report())
    }
  }

  @Test
  fun `the new name is reserved for global commands and exits`() {
    val files =
      mapOf(
        "lib/package.star" to libPkg(),
        "lib/a.star" to "def f(ctx):\n    pass\nglobal_command(\"invoke\", f)",
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to "room(\"start\", exits = [exit(\"invoke\", \":start\")])",
      )
    val r = load("rename = {\"cast\": \"invoke\"}", files)
    assertContains(
      r.errors.first { it.pkg == "lib" }.message,
      "'invoke' clashes with the engine command",
    )
    assertContains(r.errors.first { it.pkg == "zones/z" }.message, "'invoke' is a reserved keyword")
  }

  @Test
  fun `a command of a feature that is off is free for global commands`() {
    for (files in listOf(renamed, exitNamed)) {
      val r = load("features = [\"combat\"]", files)
      assertTrue(r.ok, r.report())
    }
  }

  // ---- MESSAGES.gups -------------------------------------------------------------------------

  @Test
  fun `a world without MESSAGES gups has no overrides`() {
    val (m, error) = WorldMessages.read(WorldDir(tempWorld(emptyMap())))
    assertNull(error)
    assertTrue(m.texts.isEmpty() && m.problems().isEmpty())
  }

  @Test
  fun `overrides are read and checked against the catalog`() {
    val m =
      messages(
        "messages({\"cast.begin.known\": \"{actor} whirs through {skill}.\", \"common.huh\": \"Pardon?\"})"
      )
    assertEquals(2, m.texts.size)
    assertTrue(m.problems().isEmpty(), m.problems().toString())
  }

  @Test
  fun `an unknown id is a problem with a suggestion`() {
    assertContains(
      messages("messages({\"cast.begin.knwn\": \"x\"})").problems().single(),
      "unknown message 'cast.begin.knwn' (did you mean 'cast.begin.known'?)",
    )
  }

  @Test
  fun `an unknown placeholder is a problem with a suggestion`() {
    assertContains(
      messages("messages({\"cast.begin.known\": \"{actor} casts {skil}.\"})")
        .problems()
        .joinToString("\n"),
      "unknown placeholder {skil} (did you mean 'skill'?)",
    )
  }

  @Test
  fun `leaving out a required placeholder is a problem`() {
    assertContains(
      messages("messages({\"say.self\": \"You speak.\"})").problems().single(),
      "missing required placeholder {text}",
    )
  }

  @Test
  fun `color markup and escaped braces are not placeholders`() {
    assertTrue(
      messages("messages({\"say.self\": \"{red}You{/} say {{braces}}: {text}\"})")
        .problems()
        .isEmpty()
    )
  }

  @Test
  fun `a text that is not a string is an error`() {
    val dir = tempWorld(mapOf("MESSAGES.gups" to "messages({\"common.huh\": 5})"))
    assertContains(
      assertNotNull(WorldMessages.read(WorldDir(dir)).second).message,
      "the text of 'common.huh' must be a string",
    )
  }

  @Test
  fun `a file without the messages call is an error`() {
    val dir = tempWorld(mapOf("MESSAGES.gups" to "x = 1"))
    assertContains(
      assertNotNull(WorldMessages.read(WorldDir(dir)).second).message,
      "must call messages(",
    )
  }

  @Test
  fun `an id of a feature that is off is a warning`() {
    val m = messages("messages({\"cast.what\": \"Invoke what?\", \"common.huh\": \"Pardon?\"})")
    assertTrue(m.problems().isEmpty())
    val warnings = m.warnings(Features(setOf(Feature.COMBAT)))
    assertContains(warnings.single(), "'cast.what' belongs to the 'skills' feature, which is off")
    assertTrue(m.warnings(Features.ALL).isEmpty())
  }
}
