package gups.script

import gups.core.model.DamageType
import gups.core.world.Features
import gups.core.world.damage
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalDeathHookTest {
  private fun deathFixture(
    zone: String,
    hook: String,
    load: List<String> = listOf("zones/z", "lib"),
    vararg more: Pair<String, String>,
  ): ScriptFixture =
    ScriptFixture(
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(GLOBAL_HOOK_LINEAR_CURVE),
        "zones/z/a.star" to zone,
        "lib/package.star" to libPkg(),
        "lib/g.star" to hook,
      ) + more,
      load = load,
    )

  @Test
  fun `a lib's global on_death fires once for a mob killed by a player, self the victim actor the killer`() {
    val f =
      deathFixture(
        "room(\"start\")\nmob(\"m\")\n",
        """
        def _g(ctx):
            ctx.echo(ctx.self.room, "g:%s:%s:%s" % (ctx.self.name, ctx.actor.name if ctx.actor else "None", ctx.target.name))

        global_hook("on_death", _g)
        """,
      )
    val p = f.player()
    f.out(p)
    val mob = f.mob("//zones/z:m")
    f.w.damage(mob, mapOf("hp" to 100), DamageType("fire"), source = p)
    f.run()
    val out = f.out(p)
    assertContains(out, "g:m:Ann:m")
    assertEquals(1, Regex("\\bg:m:Ann:m\\b").findAll(out).count(), "must fire once:\n$out")
  }

  @Test
  fun `the global on_death fires with no killer and ctx actor None`() {
    val f =
      deathFixture(
        "room(\"start\")\nmob(\"m\")\n",
        """
        def _g(ctx):
            ctx.echo(ctx.self.room, "g:%s:%s" % (ctx.self.name, ctx.actor.name if ctx.actor else "None"))

        global_hook("on_death", _g)
        """,
      )
    val p = f.player()
    f.out(p)
    val mob = f.mob("//zones/z:m")
    f.w.damage(mob, mapOf("hp" to 100), DamageType("fire"))
    f.run()
    assertContains(f.out(p), "g:m:None")
  }

  @Test
  fun `the global on_death fires for a player death too`() {
    val f =
      deathFixture(
        "room(\"start\")\n",
        """
        def _g(ctx):
            ctx.echo(ctx.self.room, "g:%s:%s" % (ctx.self.name, ctx.actor.name if ctx.actor else "None"))

        global_hook("on_death", _g)
        """,
      )
    val p = f.player(hp = 10)
    f.out(p)
    f.w.damage(p, mapOf("hp" to 100), DamageType("fire"))
    f.run()
    assertContains(f.out(p), "g:Ann:None")
  }

  @Test
  fun `a mob's own on_death hook runs before the global on_death`() {
    val f =
      deathFixture(
        """
        def _local(ctx):
            ctx.echo(ctx.self.room, "l:%s" % ctx.self.name)
        room("start")
        mob("m", on_death = _local)
        """,
        """
        def _g(ctx):
            ctx.echo(ctx.self.room, "g:%s" % ctx.self.name)

        global_hook("on_death", _g)
        """,
      )
    val p = f.player()
    f.out(p)
    val mob = f.mob("//zones/z:m")
    f.w.damage(mob, mapOf("hp" to 100), DamageType("fire"), source = p)
    f.run()
    val out = f.out(p)
    assertTrue(
      out.indexOf("l:m") in 0 until out.indexOf("g:m"),
      "the mob's on_death must run before the global one:\n$out",
    )
  }

  @Test
  fun `the global on_death fires in a world with no features at all`() {
    val features = Features(emptySet())
    val f =
      ScriptFixture(
        mapOf(
          "MODULE.gups" to TestWorlds.module("features = [${featureIds(features)}]"),
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to TestWorlds.rulesFor(features.ids),
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "room(\"start\")\nmob(\"m\")\n",
          "lib/package.star" to libPkg(),
          "lib/g.star" to
            """
            def _g(ctx):
                ctx.echo(ctx.self.room, "g:%s" % ctx.self.name)

            global_hook("on_death", _g)
            """,
        ),
        load = listOf("zones/z", "lib"),
      )
    val p = f.player()
    f.out(p)
    val mob = f.mob("//zones/z:m")
    f.w.damage(mob, mapOf("hp" to 100), DamageType("fire"), source = p)
    f.run()
    assertContains(f.out(p), "g:m")
  }

  @Test
  fun `a zone registering on_death is still refused`() {
    val r =
      loadZonePackage(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            def f(ctx):
                pass

            global_hook("on_death", f)
            """,
        )
      )
    assertContains(
      onlyPackageError(r, "zones/z").message,
      "only lib and rules packages may register global hooks",
    )
  }
}
