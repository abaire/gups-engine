package gups.script

import gups.core.rules.RulesContext
import gups.core.world.World
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class DeclaringWiring(override val rulesExports: Map<String, String>) : WorldPluginWiring {
  override val globals: Map<String, Any> = emptyMap()

  override fun install(world: World) = Unit

  override fun started(world: World) = Unit
}

class PluginRulesExportTest {
  private val declared = DeclaringWiring(mapOf("demo_chance" to "int", "demo_ok" to "bool"))

  private fun world(rules: String, wiring: WorldPluginWiring = declared) =
    ScriptFixture(
      mapOf(
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(rules),
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to
          "room(\"start\")\nmob(\"rat\", vitals = vitals(hp = 10))\nskill(\"spark\", effect = damage(dice = \"1d1\"))",
      ),
      load = listOf("zones/z", "rules"),
      wiring = wiring,
    )

  private fun ScriptFixture.call(
    name: String,
    type: String,
    base: Any? = 0L,
    args: Map<String, Any?> = emptyMap(),
  ): Any? {
    val p = w.players.firstOrNull() ?: player()
    return w.rules.pluginExport(
      name,
      type,
      RulesContext(w, p, p, source = mob("//zones/z:rat"), base = base, args = args),
    )
  }

  @Test
  fun `a declared export the rules define loads`() {
    val f = world("def demo_chance(ctx):\n    return ctx.base + 5")
    assertTrue(f.result.ok, f.result.report())
  }

  @Test
  fun `an export no plugin declares is not a rules export`() {
    val f = world("def demo_chance(ctx):\n    return 1", wiring = WorldPluginWiring.NONE)
    assertTrue(
      f.result.errors.any { "'demo_chance' is not a rules export" in it.message },
      f.result.report(),
    )
  }

  @Test
  fun `pluginExport returns the int and is null when undefined`() {
    val f = world("def demo_chance(ctx):\n    return ctx.base + 5")
    assertEquals(15L, f.call("demo_chance", "int", base = 10L))
    assertNull(f.call("demo_ok", "bool", base = false))
  }

  @Test
  fun `a bool export returns the bool`() {
    val f = world("def demo_ok(ctx):\n    return not ctx.base")
    assertEquals(true, f.call("demo_ok", "bool", base = false))
  }

  @Test
  fun `a wrong type returns null and records a script error`() {
    // Valid in the load-time dry run, where base is 0; wrong for any other base.
    val f = world("def demo_chance(ctx):\n    return \"x\" if ctx.base else 1")
    assertNull(f.call("demo_chance", "int", base = 3L))
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(errors.any { "demo_chance" in it && "must be an int" in it }, errors.toString())
  }

  @Test
  fun `a bool is not an int`() {
    val f = world("def demo_chance(ctx):\n    return True if ctx.base else 1")
    assertNull(f.call("demo_chance", "int", base = 1L))
  }

  @Test
  fun `base and args read back, with actors as views`() {
    val f =
      world(
        """
        def demo_chance(ctx):
            if "who" in ctx.args and ctx.args["who"].name != ctx.actor.name:
                return -1
            return ctx.base * 100 + ctx.args.get("n", 0) + len(ctx.args.get("tags", [])) + (7 if ctx.args.get("flag") else 0)
        """
      )
    val p = f.player()
    val got =
      f.w.rules.pluginExport(
        "demo_chance",
        "int",
        RulesContext(
          f.w,
          p,
          p,
          base = 2L,
          args = mapOf("who" to p, "n" to 3L, "tags" to listOf("a", "b"), "flag" to true),
        ),
      )
    assertEquals(212L, got)
  }

  @Test
  fun `base and args are None outside plugin exports`() {
    val f = world("def recognizes_skill(ctx):\n    return ctx.base == None and ctx.args == None")
    val p = f.player()
    val spark = f.w.protos.skills.values.firstOrNull()
    assertEquals(
      true,
      f.w.rules.recognizesSkill(
        RulesContext(f.w, p, p, source = f.mob("//zones/z:rat"), skill = spark)
      ),
    )
  }

  @Test
  fun `the dry run calls each defined export once with base zero or false and empty args`() {
    val ok =
      world(
        """
        def demo_chance(ctx):
            if not ctx.actor.is_player or ctx.source.is_player or ctx.base != 0 or len(ctx.args) != 0:
                fail("wrong dry run call")
            return 1
        def demo_ok(ctx):
            if ctx.base != False or len(ctx.args) != 0:
                fail("wrong dry run call")
            return True
        """
      )
    assertTrue(ok.result.ok, ok.result.report())

    val boom = world("def demo_chance(ctx):\n    fail(\"boom\")")
    assertEquals(
      1,
      boom.result.errors.count { "dry run" in it.message && "demo_chance" in it.message },
      boom.result.report(),
    )
  }
}
