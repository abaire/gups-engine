package gups.script

import gups.core.EntityId
import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.world.Feature
import gups.core.world.changeVital
import gups.core.world.moveItem
import gups.core.world.restoreItem
import gups.core.world.settleRestoredHoldings
import gups.core.world.spawnItem
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalEncumbranceHookTest {
  private fun encFixture(
    items: String,
    hook: String,
    load: List<String> = listOf("zones/z", "lib"),
    vararg more: Pair<String, String>,
  ): ScriptFixture =
    ScriptFixture(
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(GLOBAL_HOOK_LINEAR_CURVE),
        "zones/z/a.star" to "room(\"start\")\n$items",
        "lib/package.star" to libPkg(),
        "lib/g.star" to hook,
      ) + more,
      load = load,
    )

  @Test
  fun `a lib's on_encumbrance_change hook sees a 0 to 1 load, after the engine's line`() {
    val f =
      encFixture(
        "item(\"bag\", weight = 80)\n",
        """
        def _e(ctx):
            ctx.send(ctx.actor, "enc:%s->%s:%s" % (ctx.old_encumbrance_level, ctx.new_encumbrance_level, ctx.self.name))

        global_hook("on_encumbrance_change", _e)
        """,
      )
    val p = f.player()
    f.out(p)
    f.w.spawnItem(Label("//zones/z:bag"), ItemLocation.Carried(p.id))
    f.run()
    assertEquals(1, p.encumbranceLevel)
    val out = f.out(p)
    assertContains(out, "enc:0->1:Ann")
    assertTrue(
      out.indexOf("You are carrying more than you can manage.") in
        0 until out.indexOf("enc:0->1:Ann"),
      "the engine's line must come first:\n$out",
    )
  }

  @Test
  fun `loading further and lightening fire it each time, and a stat change at the same level does not`() {
    val f =
      encFixture(
        "item(\"bag\", weight = 80)\nitem(\"kit\", weight = 5)\n",
        """
        def _e(ctx):
            ctx.send(ctx.actor, "enc:%s->%s" % (ctx.old_encumbrance_level, ctx.new_encumbrance_level))

        global_hook("on_encumbrance_change", _e)
        """,
        listOf("zones/z", "lib"),
        "rules/combat.star" to
          TestWorlds.rules(
            GLOBAL_HOOK_LINEAR_CURVE,
            """
            def encumbrance(ctx):
                # Varies with hp, so the penalty changes without a level change.
                return stats(dex = -1 - (ctx.actor.hp // 10))
            """,
          ),
      )
    val p = f.player()
    f.out(p)

    val bag = f.w.spawnItem(Label("//zones/z:bag"), ItemLocation.Carried(p.id))
    val kit = f.w.spawnItem(Label("//zones/z:kit"), ItemLocation.Carried(p.id))
    f.run()
    assertEquals(listOf("enc:0->1", "enc:1->2"), hookChanges(f.out(p), "enc"))
    assertEquals(2, p.encumbranceLevel)
    assertEquals(-6, p.encumbrance["dex"], "hp 50: -1 - 5")

    // Lower hp, not the load: the penalty changes but the level holds, at the world tick.
    f.w.changeVital(p, "hp", -30)
    assertEquals(20, p.current("hp"), "hp should be 20 after the change")
    f.run(120)
    val out = f.out(p)
    assertEquals(
      emptyList(),
      hookChanges(out, "enc"),
      "a same-level stat change must not fire:\n$out",
    )
    assertEquals(-3, p.encumbrance["dex"], "hp 20: -1 - 2")

    f.w.moveItem(kit, ItemLocation.InRoom(p.room.id, 0))
    f.w.moveItem(bag, ItemLocation.InRoom(p.room.id, 0))
    f.run()
    assertEquals(listOf("enc:2->1", "enc:1->0"), hookChanges(f.out(p), "enc"))
    assertEquals(0, p.encumbranceLevel)
  }

  @Test
  fun `a mob's encumbrance change fires it too`() {
    val f =
      encFixture(
        "item(\"ore\", weight = 200)\nmob(\"m\")\n",
        """
        def _e(ctx):
            ctx.echo(ctx.actor.room, "enc:%s->%s" % (ctx.old_encumbrance_level, ctx.new_encumbrance_level))

        global_hook("on_encumbrance_change", _e)
        """,
      )
    val p = f.player()
    f.out(p)
    val mob = f.mob("//zones/z:m")
    f.w.spawnItem(Label("//zones/z:ore"), ItemLocation.Carried(mob.id))
    f.run()
    assertTrue(mob.encumbranceLevel > 0, "the mob must be encumbered")
    assertContains(f.out(p), "enc:0->${mob.encumbranceLevel}")
  }

  @Test
  fun `without leveling the encumbrance hook fires, but on_level_change does not`() {
    val features = withoutFeatures(Feature.LEVELING)
    val f =
      ScriptFixture(
        mapOf(
          "MODULE.gups" to TestWorlds.module("features = [${featureIds(features)}]"),
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to TestWorlds.rulesFor(features.ids),
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "room(\"start\")\nitem(\"bag\", weight = 80)\n",
          "lib/package.star" to libPkg(),
          "lib/g.star" to
            """
            def _e(ctx):
                ctx.send(ctx.actor, "enc:%s->%s" % (ctx.old_encumbrance_level, ctx.new_encumbrance_level))

            def _l(ctx):
                ctx.send(ctx.actor, "lvl:%s->%s" % (ctx.old_level, ctx.new_level))

            global_hook("on_encumbrance_change", _e)
            global_hook("on_level_change", _l)
            """,
        ),
        load = listOf("zones/z", "lib"),
      )
    val p = f.player()
    f.out(p)
    f.w.spawnItem(Label("//zones/z:bag"), ItemLocation.Carried(p.id))
    f.run()
    val out = f.out(p)
    assertContains(out, "enc:0->1")
    assertTrue(!out.contains("lvl:"), "on_level_change must not fire without leveling:\n$out")
  }

  @Test
  fun `a restore recomputes the level without firing the hook`() {
    val f =
      encFixture(
        "item(\"bag\", weight = 80)\n",
        """
        def _e(ctx):
            ctx.send(ctx.actor, "enc:%s->%s" % (ctx.old_encumbrance_level, ctx.new_encumbrance_level))

        global_hook("on_encumbrance_change", _e)
        """,
      )
    val p = f.player()
    f.out(p)
    // A returning character's saved load is placed, then worked out silently.
    f.w.restoreItem(
      EntityId(999_999_999L),
      f.w.protos.item(Label("//zones/z:bag")),
      ItemLocation.Carried(p.id),
      1,
    )
    f.w.settleRestoredHoldings(p)
    f.run()
    val out = f.out(p)
    assertEquals(1, p.encumbranceLevel, "restore recomputes the level from the load")
    assertTrue(!out.contains("enc:"), "the restore must not fire the hook:\n$out")
  }

  @Test
  fun `two packages fire the encumbrance hook in label order`() {
    val f =
      encFixture(
        "item(\"bag\", weight = 80)\n",
        """
        def _a(ctx):
            ctx.echo(ctx.actor.room, "alpha")

        global_hook("on_encumbrance_change", _a)
        """,
        listOf("zones/z", "lib", "libb"),
        "libb/package.star" to libPkg(),
        "libb/g.star" to
          """
          def _b(ctx):
              ctx.echo(ctx.actor.room, "beta")

          global_hook("on_encumbrance_change", _b)
          """,
      )
    val p = f.player()
    f.out(p)
    f.w.spawnItem(Label("//zones/z:bag"), ItemLocation.Carried(p.id))
    f.run()
    val out = f.out(p)
    assertTrue(
      out.indexOf("alpha") in 0 until out.indexOf("beta"),
      "lib must fire before libb:\n$out",
    )
  }

  @Test
  fun `a zone declaring on_encumbrance_change fails to load`() {
    assertZoneHookError(
      """
      def f(ctx):
          pass

      global_hook("on_encumbrance_change", f)
      """,
      "only lib and rules packages may register global hooks",
    )
  }

  @Test
  fun `a mob can't attach on_encumbrance_change, the hook is global`() {
    assertZoneHookError(
      """
      def f(ctx):
          pass

      mob("m", on_encumbrance_change = f)
      """,
      "hook 'on_encumbrance_change' is global; register it with global_hook()",
    )
  }

  @Test
  fun `a hook that re-encumbers is cut off at the cascade guard without a hang`() {
    val f =
      encFixture(
        "item(\"bag\", weight = 80)\n",
        """
        def _e(ctx):
            # Drop what we carry, then re-add a load, so the level keeps changing.
            items = ctx.actor.inventory()
            if items:
                ctx.move_item(items[0], ctx.actor.room)
            ctx.spawn("//zones/z:bag", at = ctx.actor)

        global_hook("on_encumbrance_change", _e)
        """,
      )
    val p = f.player()
    f.out(p)
    f.w.spawnItem(Label("//zones/z:bag"), ItemLocation.Carried(p.id))
    f.run()
    assertTrue(p.inWorld, "the actor must survive the re-entry")
    val cascade = f.diagnostics("cascade")
    assertTrue(
      cascade.any { "on_encumbrance_change" in it.message },
      "expected a cascade diagnostic:\n${f.diagnostics()}",
    )
  }

  @Test
  fun `a failing encumbrance hook records a diagnostic and leaves the level intact`() {
    val f =
      encFixture(
        "item(\"bag\", weight = 80)\n",
        """
        def _e(ctx):
            fail("boom")

        global_hook("on_encumbrance_change", _e)
        """,
      )
    val p = f.player()
    f.out(p)
    f.w.spawnItem(Label("//zones/z:bag"), ItemLocation.Carried(p.id))
    f.run()
    assertEquals(1, p.encumbranceLevel, "the level update must survive the failing hook")
    assertContains(f.out(p), "You are carrying more than you can manage.")
    assertTrue(
      f.diagnostics().any { "global_hook" in it.message && "boom" in it.message },
      "no diagnostic for the failing hook:\n${f.diagnostics()}",
    )
  }

  @Test
  fun `a quarantined package's encumbrance hook is skipped`() {
    val f =
      encFixture(
        "item(\"bag\", weight = 80)\n",
        """
        def _e(ctx):
            ctx.send(ctx.actor, "enc:%s->%s" % (ctx.old_encumbrance_level, ctx.new_encumbrance_level))

        global_hook("on_encumbrance_change", _e)
        """,
      )
    val p = f.player()
    f.out(p)
    f.host.quarantine(f.w, "lib", "test")
    f.w.spawnItem(Label("//zones/z:bag"), ItemLocation.Carried(p.id))
    f.run()
    assertEquals(1, p.encumbranceLevel)
    val out = f.out(p)
    assertContains(out, "You are carrying more than you can manage.")
    assertTrue(!out.contains("enc:"), "the quarantined package must not fire:\n$out")
  }

  // ---- on_death ------------------------------------------------------------------------------

  /** A world with [zone] content and a lib registering the global `on_death` [hook]. */
}
