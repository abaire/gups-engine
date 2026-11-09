package gups.pluginapi

import gups.core.Label
import gups.core.world.World
import gups.pluginapi.host.ActorFacade
import gups.pluginapi.host.PluginHost
import gups.pluginapi.host.PluginRegistryImpl
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PluginRulesExportTest {
  private fun registry(id: String = "demo") = PluginRegistryImpl(id, emptyList(), emptySet()) { {} }

  private fun message(block: () -> Unit) =
    assertFailsWith<IllegalArgumentException> { block() }.message

  @Test
  fun `a name must be the plugin id then snake_case`() {
    val r = registry()
    val expected = "addRulesExport(): 'chance' must be 'demo_' then a snake_case name"
    assertEquals(expected, message { r.addRulesExport("chance", "d.", PluginRulesType.INT) })
    assertEquals(
      "addRulesExport(): 'demo_Chance' must be 'demo_' then a snake_case name",
      message { r.addRulesExport("demo_Chance", "d.", PluginRulesType.INT) },
    )
    assertEquals(
      "addRulesExport(): 'other_chance' must be 'demo_' then a snake_case name",
      message { r.addRulesExport("other_chance", "d.", PluginRulesType.INT) },
    )
    r.addRulesExport("demo_chance", "d.", PluginRulesType.INT)
  }

  @Test
  fun `a name may not repeat or be an engine export`() {
    val r = registry()
    r.addRulesExport("demo_chance", "d.", PluginRulesType.INT)
    assertEquals(
      "addRulesExport(): 'demo_chance' is already declared",
      message { r.addRulesExport("demo_chance", "d.", PluginRulesType.BOOL) },
    )
    val leash = registry("leash")
    assertEquals(
      "addRulesExport(): 'leash_return' is an engine rules export",
      message { leash.addRulesExport("leash_return", "d.", PluginRulesType.BOOL) },
    )
  }

  private fun plugin(id: String = "demo", capture: (PluginWorld) -> Unit = {}) =
    object : GupsPlugin {
      override val id = id
      override val pluginApiVersion = PluginApi.VERSION

      override fun register(r: PluginRegistry) {
        r.addRulesExport("${id}_chance", "A chance.", PluginRulesType.INT)
        r.addRulesExport("${id}_ok", "A flag.", PluginRulesType.BOOL)
        r.onWorldStarted(capture)
      }
    }

  @Test
  fun `the host lists declared exports in its docs and wiring`() {
    val host = PluginHost.of(plugin())
    assertEquals(mapOf("demo_chance" to "int", "demo_ok" to "bool"), host.rulesExports)
    val info = host.describe().single().rulesExports
    assertEquals(listOf("demo_chance", "demo_ok"), info.map { it.name })
    assertEquals("A chance.", info.first().doc)
    assertEquals("int", info.first().type)
  }

  private class Scene(val w: World, val facade: PluginWorld)

  private fun scene(rules: String? = null): Scene {
    var facade: PluginWorld? = null
    val host = PluginHost.of(plugin { facade = it })
    val files =
      ZONE +
        mapOf(
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to if (rules == null) TestWorlds.rules() else TestWorlds.rules(rules),
        )
    val w = world(files, plugins = host)
    return Scene(w, assertNotNull(facade))
  }

  private fun Scene.ann() = ActorFacade(w.spawnPlayer("Ann", Label("//zones/z:a")), w)

  @Test
  fun `rules() on an undeclared name throws`() {
    val s = scene()
    assertEquals(
      "rules(): 'demo_other' is not declared by plugin 'demo'",
      message { s.facade.rules("demo_other", emptyMap(), 1L) },
    )
  }

  @Test
  fun `rules() needs a default of the declared type`() {
    val s = scene()
    assertEquals(
      "rules(): default for 'demo_chance' must be an int",
      message { s.facade.rules("demo_chance", emptyMap(), true) },
    )
    assertEquals(
      "rules(): default for 'demo_ok' must be a bool",
      message { s.facade.rules("demo_ok", emptyMap(), 1L) },
    )
  }

  @Test
  fun `rules() returns the default when the world doesn't define the export`() {
    val s = scene()
    assertEquals(7L, s.facade.rules("demo_chance", mapOf("base" to 7L), 7L))
    assertEquals(7L, s.facade.rules("demo_chance", emptyMap(), 7))
    assertEquals(false, s.facade.rules("demo_ok", emptyMap(), false))
  }

  @Test
  fun `rules() returns the world's value, with fields as ctx fields and args`() {
    val s =
      scene(
        """
        def demo_chance(ctx):
            return ctx.base + ctx.args.get("n", 0) + (100 if ctx.actor.name == "Ann" else 0)
        def demo_ok(ctx):
            return ctx.args.get("flag", False)
        """
      )
    val ann = s.ann()
    assertEquals(
      113L,
      s.facade.rules("demo_chance", mapOf("actor" to ann, "base" to 10L, "n" to 3L), 0L),
    )
    assertEquals(true, s.facade.rules("demo_ok", mapOf("flag" to true), false))
  }

  @Test
  fun `rules() returns the default and records a diagnostic when the call fails`() {
    // Valid in the load-time dry run, where base is 0.
    val s = scene("def demo_chance(ctx):\n    return \"x\" if ctx.base else 1")
    assertEquals(9L, s.facade.rules("demo_chance", mapOf("base" to 5L), 9L))
    assertEquals(
      true,
      s.w.diagnostics.any { it.kind == "script_error" && "demo_chance" in it.message },
    )
  }
}
