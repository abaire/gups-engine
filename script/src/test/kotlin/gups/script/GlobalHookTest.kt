package gups.script

import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.ScriptHost
import gups.core.world.awardXp
import gups.core.world.levelUp
import gups.script.testing.TestWorlds
import gups.script.testing.tempWorld
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal val GLOBAL_HOOK_LINEAR_CURVE =
  """def xp_to_level(ctx, level):
    return 1000 * level
"""

internal fun loadZonePackage(
  files: Map<String, String>,
  vararg pkgs: String = arrayOf("zones/z"),
): LoadResult = ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(pkgs.toList())

internal fun onlyPackageError(r: LoadResult, pkg: String): LoadError {
  val errs = r.errors.filter { it.pkg == pkg }
  assertTrue(errs.isNotEmpty(), "expected an error for $pkg, got: ${r.report()}")
  return errs.first()
}

internal fun assertZoneHookError(zoneStar: String, expectedMsg: String) {
  val r = loadZonePackage(mapOf("zones/z/package.star" to zonePkg(), "zones/z/a.star" to zoneStar))
  assertContains(onlyPackageError(r, "zones/z").message, expectedMsg)
}

internal fun withoutFeatures(vararg off: Feature): Features =
  Features(
    Feature.entries.toSet() -
      off.toSet() -
      Feature.entries.filter { f -> f.requires.any { it in off } }.toSet()
  )

internal fun featureIds(features: Features): String = features.ids.joinToString(", ") { "\"$it\"" }

internal fun hookChanges(out: String, tag: String): List<String> =
  Regex("\\b$tag:\\d+->\\d+").findAll(out).map { it.value }.toList()

class GlobalHookTest {
  private fun fixture(
    vararg files: Pair<String, String>,
    load: List<String> = listOf("zones/z"),
    wrap: (ScriptHost) -> ScriptHost = { it },
  ): ScriptFixture =
    ScriptFixture(
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(GLOBAL_HOOK_LINEAR_CURVE),
      ) + files,
      load = load,
      wrap = wrap,
    )

  private fun loadZonePackage(
    files: Map<String, String>,
    vararg pkgs: String = arrayOf("zones/z"),
  ): LoadResult = ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(pkgs.toList())

  private fun onlyPackageError(r: LoadResult, pkg: String): LoadError {
    val errs = r.errors.filter { it.pkg == pkg }
    assertTrue(errs.isNotEmpty(), "expected an error for $pkg, got: ${r.report()}")
    return errs.first()
  }

  /** The `tag:old->new` lines in [out], in the order they were sent. */
  private fun hookChanges(out: String, tag: String): List<String> =
    Regex("\\b$tag:\\d+->\\d+").findAll(out).map { it.value }.toList()

  @Test
  fun `a lib's global hook sees a player's level-up, with old and new level and its own self`() {
    val f =
      fixture(
        "zones/z/a.star" to "room(\"start\")",
        "lib/package.star" to libPkg(),
        "lib/g.star" to
          """
          def _step(ctx):
              ctx.send(ctx.actor, "g:%s->%s:%s" % (ctx.old_level, ctx.new_level, ctx.self.name))

          global_hook("on_level_change", _step)
          """,
        load = listOf("zones/z", "lib"),
      )
    val p = f.player()
    f.out(p)
    f.w.awardXp(p, 2500)
    f.run()
    assertEquals(3, p.level)
    val out = f.out(p)
    assertContains(out, "g:1->2:Ann")
    assertContains(out, "g:2->3:Ann")
  }

  @Test
  fun `a three-level loss fires it three times, each after that step's local hook`() {
    val f =
      fixture(
        "zones/z/a.star" to
          """
          def _local(ctx):
              ctx.send(ctx.actor, "l:%s->%s" % (ctx.old_level, ctx.new_level))

          def _drain(ctx):
              ctx.level_down(ctx.actor, 3)

          room("start", on_level_change = _local, commands = {"drain": _drain})
          """,
        "lib/package.star" to libPkg(),
        "lib/g.star" to
          """
          def _g(ctx):
              ctx.send(ctx.actor, "g:%s->%s" % (ctx.old_level, ctx.new_level))

          global_hook("on_level_change", _g)
          """,
        load = listOf("zones/z", "lib"),
      )
    val p = f.player()
    p.level = 4
    f.out(p)

    f.input(p, "drain")
    f.run()
    assertEquals(1, p.level)
    val out = f.out(p)
    val order =
      listOf("l:4->3", "g:4->3", "l:3->2", "g:3->2", "l:2->1", "g:2->1")
        .map { it to out.indexOf(it) }
        .onEach { assertTrue(it.second >= 0, "missing ${it.first} in:\n$out") }
    assertEquals(order.map { it.second }, order.map { it.second }.sorted(), "out of order:\n$out")
  }

  @Test
  fun `a mob's level change fires it too`() {
    val f =
      fixture(
        "zones/z/a.star" to
          """
          room("start")
          mob("m")
          """,
        "lib/package.star" to libPkg(),
        "lib/g.star" to
          """
          def _g(ctx):
              ctx.echo(ctx.actor.room, "g:%s->%s" % (ctx.old_level, ctx.new_level))

          global_hook("on_level_change", _g)
          """,
        load = listOf("zones/z", "lib"),
      )
    val p = f.player()
    f.out(p)
    val mob = f.mob("//zones/z:m")
    mob.xp = 1000L

    assertTrue(f.w.levelUp(mob))
    f.run()
    assertContains(f.out(p), "g:1->2")
  }

  @Test
  fun `two packages fire in label order`() {
    val f =
      fixture(
        "zones/z/a.star" to "room(\"start\")",
        "lib/package.star" to libPkg(),
        "lib/g.star" to
          """
          def _a(ctx):
              ctx.echo(ctx.actor.room, "alpha")

          global_hook("on_level_change", _a)
          """,
        "libb/package.star" to libPkg(),
        "libb/g.star" to
          """
          def _b(ctx):
              ctx.echo(ctx.actor.room, "beta")

          global_hook("on_level_change", _b)
          """,
        load = listOf("zones/z", "lib", "libb"),
      )
    val p = f.player()
    f.out(p)
    f.w.awardXp(p, 1000)
    f.run()
    val out = f.out(p)
    assertTrue(
      out.indexOf("alpha") in 0 until out.indexOf("beta"),
      "lib must fire before libb:\n$out",
    )
  }

  @Test
  fun `a zone declaring it fails to load`() {
    val r =
      loadZonePackage(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            def f(ctx):
                pass

            global_hook("on_level_change", f)
            """,
        )
      )
    assertContains(
      onlyPackageError(r, "zones/z").message,
      "only lib and rules packages may register global hooks",
    )
  }

  @Test
  fun `an unaccepted name fails to load, listing the nine global hook names`() {
    val r =
      loadZonePackage(
        mapOf(
          "lib/package.star" to libPkg(),
          "lib/a.star" to
            """
            def f(ctx):
                pass

            global_hook("on_enter", f)
            """,
        ),
        "lib",
      )
    assertContains(
      onlyPackageError(r, "lib").message,
      "can't be a global hook; only on_level_change, on_encumbrance_change, on_group_change, on_owner_change, on_disengage, on_death, on_despawn, on_destroy and on_skill_use can",
    )
  }

  @Test
  fun `the same hook and function twice in one package fails to load`() {
    val r =
      loadZonePackage(
        mapOf(
          "lib/package.star" to libPkg(),
          "lib/a.star" to
            """
            def f(ctx):
                pass

            global_hook("on_level_change", f)
            global_hook("on_level_change", f)
            """,
        ),
        "lib",
      )
    assertContains(onlyPackageError(r, "lib").message, "already registered")
  }

  @Test
  fun `without leveling the declaration loads and nothing fires`() {
    val features = withoutFeatures(Feature.LEVELING)
    val f =
      ScriptFixture(
        mapOf(
          "MODULE.gups" to TestWorlds.module("features = [${featureIds(features)}]"),
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to TestWorlds.rulesFor(features.ids),
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "room(\"start\")",
          "lib/package.star" to libPkg(),
          "lib/g.star" to
            """
            def _g(ctx):
                ctx.send(ctx.actor, "fired")

            global_hook("on_level_change", _g)
            """,
        ),
        load = listOf("zones/z", "lib"),
      )
    val p = f.player()
    f.out(p)
    f.w.awardXp(p, 10_000)
    f.run(2)
    assertTrue("fired" !in f.out(p), "the global hook must not fire without leveling")
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `a failing global hook records a diagnostic and doesn't stop the level step`() {
    val f =
      fixture(
        "zones/z/a.star" to
          """
          def _local(ctx):
              ctx.send(ctx.actor, "l:%s->%s" % (ctx.old_level, ctx.new_level))

          room("start", on_level_change = _local)
          """,
        "lib/package.star" to libPkg(),
        "lib/g.star" to
          """
          def _g(ctx):
              fail("boom")

          global_hook("on_level_change", _g)
          """,
        load = listOf("zones/z", "lib"),
      )
    val p = f.player()
    f.out(p)
    f.w.awardXp(p, 1000)
    f.run()
    assertEquals(2, p.level)
    val out = f.out(p)
    assertContains(out, "You have reached level 2!")
    assertContains(out, "l:1->2")
    assertTrue(
      f.diagnostics().any { "global_hook" in it.message && "boom" in it.message },
      "no diagnostic for the failing global hook:\n${f.diagnostics()}",
    )
  }

  @Test
  fun `a quarantined package's global hook is skipped`() {
    val f =
      fixture(
        "zones/z/a.star" to
          """
          def _local(ctx):
              ctx.send(ctx.actor, "l:%s->%s" % (ctx.old_level, ctx.new_level))

          room("start", on_level_change = _local)
          """,
        "lib/package.star" to libPkg(),
        "lib/g.star" to
          """
          def _g(ctx):
              ctx.send(ctx.actor, "g:%s->%s" % (ctx.old_level, ctx.new_level))

          global_hook("on_level_change", _g)
          """,
        load = listOf("zones/z", "lib"),
      )
    val p = f.player()
    f.out(p)
    f.host.quarantine(f.w, "lib", "test")
    f.w.awardXp(p, 1000)
    f.run()
    val out = f.out(p)
    assertContains(out, "l:1->2")
    assertTrue(!out.contains("g:1->2"), "the quarantined package must not fire:\n$out")
  }

  // ---- on_encumbrance_change -------------------------------------------------------------------

  /** A lib registering only the encumbrance hook, with the items its tests load. */
}
