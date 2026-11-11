package gups.script

import gups.core.EntityId
import gups.core.Label
import gups.core.model.DamageType
import gups.core.model.ItemLocation
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.ScriptHost
import gups.core.world.awardXp
import gups.core.world.changeVital
import gups.core.world.damage
import gups.core.world.levelUp
import gups.core.world.moveItem
import gups.core.world.restoreItem
import gups.core.world.settleRestoredHoldings
import gups.core.world.spawnItem
import gups.script.testing.TestWorlds
import gups.script.testing.tempWorld
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalHookTest {
  private fun without(vararg off: Feature): Features =
    Features(
      Feature.entries.toSet() -
        off.toSet() -
        Feature.entries.filter { f -> f.requires.any { it in off } }.toSet()
    )

  /** The `id` of a feature set, for a MODULE.gups features argument. */
  private fun idsOf(features: Features): String = features.ids.joinToString(", ") { "\"$it\"" }

  private val linearCurve =
    """def xp_to_level(ctx, level):
    return 1000 * level
"""

  private fun fixture(
    vararg files: Pair<String, String>,
    load: List<String> = listOf("zones/z"),
    wrap: (ScriptHost) -> ScriptHost = { it },
  ): ScriptFixture =
    ScriptFixture(
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(linearCurve),
      ) + files,
      load = load,
      wrap = wrap,
    )

  private fun load(
    files: Map<String, String>,
    vararg pkgs: String = arrayOf("zones/z"),
  ): LoadResult = ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(pkgs.toList())

  private fun only(r: LoadResult, pkg: String): LoadError {
    val errs = r.errors.filter { it.pkg == pkg }
    assertTrue(errs.isNotEmpty(), "expected an error for $pkg, got: ${r.report()}")
    return errs.first()
  }

  /** The `tag:old->new` lines in [out], in the order they were sent. */
  private fun changes(out: String, tag: String): List<String> =
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
      load(
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
      only(r, "zones/z").message,
      "only lib and rules packages may register global hooks",
    )
  }

  @Test
  fun `an unaccepted name fails to load, listing the seven global hook names`() {
    val r =
      load(
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
      only(r, "lib").message,
      "can't be a global hook; only on_level_change, on_encumbrance_change, on_group_change, on_owner_change, on_disengage, on_death and on_skill_use can",
    )
  }

  @Test
  fun `the same hook and function twice in one package fails to load`() {
    val r =
      load(
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
    assertContains(only(r, "lib").message, "already registered")
  }

  @Test
  fun `without leveling the declaration loads and nothing fires`() {
    val features = without(Feature.LEVELING)
    val f =
      ScriptFixture(
        mapOf(
          "MODULE.gups" to TestWorlds.module("features = [${idsOf(features)}]"),
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
        "rules/combat.star" to TestWorlds.rules(linearCurve),
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
            linearCurve,
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
    assertEquals(listOf("enc:0->1", "enc:1->2"), changes(f.out(p), "enc"))
    assertEquals(2, p.encumbranceLevel)
    assertEquals(-6, p.encumbrance["dex"], "hp 50: -1 - 5")

    // Lower hp, not the load: the penalty changes but the level holds, at the world tick.
    f.w.changeVital(p, "hp", -30)
    assertEquals(20, p.current("hp"), "hp should be 20 after the change")
    f.run(120)
    val out = f.out(p)
    assertEquals(emptyList(), changes(out, "enc"), "a same-level stat change must not fire:\n$out")
    assertEquals(-3, p.encumbrance["dex"], "hp 20: -1 - 2")

    f.w.moveItem(kit, ItemLocation.InRoom(p.room.id, 0))
    f.w.moveItem(bag, ItemLocation.InRoom(p.room.id, 0))
    f.run()
    assertEquals(listOf("enc:2->1", "enc:1->0"), changes(f.out(p), "enc"))
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
    val features = without(Feature.LEVELING)
    val f =
      ScriptFixture(
        mapOf(
          "MODULE.gups" to TestWorlds.module("features = [${idsOf(features)}]"),
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
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            def f(ctx):
                pass

            global_hook("on_encumbrance_change", f)
            """,
        )
      )
    assertContains(
      only(r, "zones/z").message,
      "only lib and rules packages may register global hooks",
    )
  }

  @Test
  fun `a mob can't attach on_encumbrance_change, the hook is global`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            def f(ctx):
                pass

            mob("m", on_encumbrance_change = f)
            """,
        )
      )
    assertContains(
      only(r, "zones/z").message,
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
        "rules/combat.star" to TestWorlds.rules(linearCurve),
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
          "MODULE.gups" to TestWorlds.module("features = [${idsOf(features)}]"),
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
      load(
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
      only(r, "zones/z").message,
      "only lib and rules packages may register global hooks",
    )
  }
}
