package gups.script

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiDiffTest {
  private val model = ScriptApi.model(harness = emptyList())
  private val baseline = model.toJson()
  // A fixed changelog, so entries added to docs/CHANGELOG.md don't change these cases.
  private val realChangelog =
    Changelog.parse("# Changelog\n\n## api_version 1\n\nInitial (unstable) script API.\n")
  private val frozen = realChangelog.toJson(model.apiVersion)

  private fun entry(title: String, kind: String, symbol: String, replacement: String? = null) =
    buildString {
      append("### ")
        .append(title)
        .append("\n- kind: ")
        .append(kind)
        .append("\n- symbol: ")
        .append(symbol)
        .append('\n')
      replacement?.let { append("- replacement: ").append(it).append('\n') }
      append("\nSummary of ").append(title).append(".\n\n")
    }

  /**
   * The real changelog with [unreleased] entries under version 2 and [appended] after version 1's.
   */
  private fun changelog(unreleased: String = "", appended: String = ""): Changelog =
    Changelog.parse(
      "# Changelog\n\n" +
        (if (unreleased.isEmpty()) "" else "## api_version 2 (unreleased)\n\n$unreleased") +
        "## api_version 1\n\nInitial (unstable) script API.\n\n$appended"
    )

  private fun check(current: ScriptApiModel, c: Changelog = realChangelog) =
    ApiDiff.check(baseline, frozen, current, c)

  private fun withCtxMethods(f: (List<MethodSpec>) -> List<MethodSpec>) =
    model.copy(ctx = model.ctx.copy(methods = f(model.ctx.methods)))

  private fun withActMove(f: (MethodSpec) -> MethodSpec) = withCtxMethods { ms ->
    ms.map { if (it.name == "act_move") f(it) else it }
  }

  private fun assertOne(problems: List<String>, symbol: String) {
    assertEquals(1, problems.size, problems.joinToString("\n"))
    assertTrue(symbol in problems.single(), problems.single())
  }

  @Test
  fun `identical model passes, and doc-only changes need no entry`() {
    assertEquals(emptyList(), check(model))
    assertEquals(emptyList(), check(withActMove { it.copy(doc = "Walks a mob.") }))
    assertEquals(
      emptyList(),
      ApiDiff.check(ApiDiff.normalize(baseline), frozen, model, realChangelog),
    )
  }

  @Test
  fun `removals need an entry`() {
    val noSize =
      model.copy(
        declarations =
          model.declarations.map { d ->
            if (d.name == "room") d.copy(params = d.params.filter { it.name != "size" }) else d
          }
      )
    assertOne(check(noSize), "room.size")
    assertEquals(
      emptyList(),
      check(noSize, changelog(unreleased = entry("Removed size", "removed", "room.size"))),
    )
    assertOne(check(withCtxMethods { ms -> ms.filter { it.name != "act_move" } }), "ctx.act_move")
  }

  @Test
  fun `additions need an added entry, unreleased or appended to the current version`() {
    val newParam = withActMove {
      it.copy(
        params =
          it.params +
            ParamSpec("speed", positional = false, named = true, default = "1", doc = "Speed.")
      )
    }
    assertOne(check(newParam), "ctx.act_move.speed")
    val added = entry("Added speed", "added", "ctx.act_move.speed")
    assertEquals(emptyList(), check(newParam, changelog(unreleased = added)))
    assertEquals(emptyList(), check(newParam, changelog(appended = added)))

    val newMethod = withCtxMethods {
      it +
        MethodSpec(
          "hover",
          "Hovers.",
          listOf(ParamSpec("mob", positional = true, named = true, doc = "Mob.")),
        )
    }
    assertOne(check(newMethod), "ctx.hover")
    assertEquals(
      emptyList(),
      check(newMethod, changelog(appended = entry("Added hover", "added", "ctx.hover"))),
    )
  }

  @Test
  fun `a rename covers the old and new symbols and their parameters`() {
    val renamed = withActMove { it.copy(name = "walk") }
    assertEquals(2, check(renamed).size, check(renamed).joinToString("\n"))
    assertEquals(
      emptyList(),
      check(
        renamed,
        changelog(unreleased = entry("Renamed", "renamed", "ctx.act_move", "ctx.walk")),
      ),
    )
    val wrongTarget =
      check(renamed, changelog(unreleased = entry("Renamed", "renamed", "ctx.act_move", "ctx.run")))
    assertTrue(wrongTarget.any { "not a current symbol" in it }, wrongTarget.joinToString("\n"))
  }

  @Test
  fun `changed fields need a signature or behavior entry`() {
    val newDefault = withActMove { m ->
      m.copy(params = m.params.map { if (it.name == "dir") it.copy(default = "\"north\"") else it })
    }
    assertOne(check(newDefault), "ctx.act_move.dir")
    assertEquals(
      emptyList(),
      check(newDefault, changelog(unreleased = entry("Default dir", "signature", "ctx.act_move"))),
    )
    assertEquals(
      emptyList(),
      check(
        newDefault,
        changelog(unreleased = entry("Default dir", "behavior", "ctx.act_move.dir")),
      ),
    )
    // A signature entry also covers parameters added beneath it.
    val both = withActMove { m ->
      m.copy(
        params = m.params + ParamSpec("speed", positional = false, named = true, doc = "Speed.")
      )
    }
    assertEquals(
      emptyList(),
      check(both, changelog(unreleased = entry("New signature", "signature", "ctx.act_move"))),
    )
  }

  @Test
  fun `released sections are frozen`() {
    val breaking = check(model, changelog(appended = entry("Removed size", "removed", "room.size")))
    assertTrue(
      breaking.any { "go under `## api_version 2 (unreleased)`" in it },
      breaking.joinToString("\n"),
    )

    val history = changelog(appended = entry("Added hover", "added", "ctx.hover"))
    val newMethod = withCtxMethods { it + MethodSpec("hover", "Hovers.", emptyList()) }
    val frozenWithEntry = history.toJson(1)
    assertEquals(
      emptyList(),
      ApiDiff.check(newMethod.toJson(), frozenWithEntry, newMethod, history),
    )
    val edited =
      Changelog.parse(
        "# Changelog\n\n## api_version 1\n\nInitial (unstable) script API.\n\n" +
          entry("Added hover!", "added", "ctx.hover")
      )
    val problems = ApiDiff.check(newMethod.toJson(), frozenWithEntry, newMethod, edited)
    assertTrue(
      problems.any { "released entry 'Added hover' of api_version 1 was edited or removed" in it },
      problems.joinToString("\n"),
    )
  }

  @Test
  fun `stale and misplaced entries are problems`() {
    val stale = check(model, changelog(unreleased = entry("Removed size", "removed", "room.size")))
    assertOne(stale, "room.size")
    assertTrue("matches no API difference" in stale.single())
    assertEquals(
      emptyList(),
      check(model, changelog(unreleased = entry("Wake later", "behavior", "hook:on_wake"))),
    )
    assertOne(
      check(model, changelog(unreleased = entry("Gone", "behavior", "ctx.nope"))),
      "ctx.nope",
    )
    val wrongVersion =
      ApiDiff.check(
        baseline,
        frozen,
        model,
        Changelog.parse(
          "## api_version 3 (unreleased)\n\n" +
            entry("Wake", "behavior", "hook:on_wake") +
            "## api_version 1\n\nInitial (unstable) script API.\n"
        ),
      )
    assertOne(wrongVersion, "api_version 3")
  }

  @Test
  fun `normalize replaces the engine version`() {
    assertTrue("\"engine_version\": \"<engine>\"" in ApiDiff.normalize(baseline))
    assertEquals(ApiDiff.normalize(baseline), ApiDiff.normalize(ApiDiff.normalize(baseline)))
  }
}
