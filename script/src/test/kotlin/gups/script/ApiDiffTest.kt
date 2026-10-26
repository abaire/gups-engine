package gups.script

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiDiffTest {
  private val model = ScriptApi.model(harness = emptyList())
  private val baseline = model.toJson()

  private fun check(current: ScriptApiModel) = ApiDiff.check(baseline, current)

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
  fun `identical model passes, and doc-only changes are not breaking`() {
    assertEquals(emptyList(), check(model))
    assertEquals(emptyList(), check(withActMove { it.copy(doc = "Walks a mob.") }))
    assertEquals(emptyList(), ApiDiff.check(ApiDiff.normalize(baseline), model))
  }

  @Test
  fun `removals without an api_version bump fail`() {
    val noSize =
      model.copy(
        declarations =
          model.declarations.map { d ->
            if (d.name == "room") d.copy(params = d.params.filter { it.name != "size" }) else d
          }
      )
    assertOne(check(noSize), "room.size")
    assertTrue("bumping EngineInfo.API_VERSION" in check(noSize).single())
    // A removed method is reported once, not once per parameter.
    assertOne(check(withCtxMethods { ms -> ms.filter { it.name != "act_move" } }), "ctx.act_move")
  }

  @Test
  fun `renames and changed fields without an api_version bump fail`() {
    // The new name is a valid addition; the old one is a removal.
    assertOne(check(withActMove { it.copy(name = "walk") }), "ctx.act_move")
    val newDefault = withActMove { m ->
      m.copy(params = m.params.map { if (it.name == "dir") it.copy(default = "\"north\"") else it })
    }
    assertOne(check(newDefault), "ctx.act_move.dir")
  }

  @Test
  fun `additions without a bump pass only when marked since the current api_version`() {
    val newMethod = withCtxMethods {
      it + MethodSpec("hover", "Hovers.", emptyList(), since = model.apiVersion)
    }
    assertEquals(emptyList(), check(newMethod))
    val wrongSince = withCtxMethods {
      it + MethodSpec("hover", "Hovers.", emptyList(), since = model.apiVersion + 1)
    }
    assertOne(check(wrongSince), "ctx.hover")
    assertTrue("@ApiSince(${model.apiVersion})" in check(wrongSince).single())
  }

  @Test
  fun `a bumped api_version allows breaking changes`() {
    val bumped =
      model.copy(apiVersion = model.apiVersion + 1, ctx = model.ctx.copy(methods = emptyList()))
    assertEquals(emptyList(), check(bumped))
  }

  @Test
  fun `normalize replaces the engine version`() {
    assertTrue("\"engine_version\": \"<engine>\"" in ApiDiff.normalize(baseline))
    assertEquals(ApiDiff.normalize(baseline), ApiDiff.normalize(ApiDiff.normalize(baseline)))
  }
}
