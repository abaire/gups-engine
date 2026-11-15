package gups.script

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.world.spawnItem
import gups.script.testing.TestWorlds
import gups.script.testing.tempWorld
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DestroyHookTest {
  private val linearCurve =
    """def xp_to_level(ctx, level):
    return 1000 * level
"""

  private fun fixture(zone: String, lib: String? = null): ScriptFixture {
    val files = buildMap {
      put("zones/z/package.star", zonePkg())
      put("rules/package.star", TestWorlds.RULES_PACKAGE)
      put("rules/combat.star", TestWorlds.rules(linearCurve))
      put("zones/z/a.star", zone)
      if (lib != null) {
        put("lib/package.star", libPkg())
        put("lib/hooks.star", lib)
      }
    }
    val load = if (lib != null) listOf("lib", "zones/z") else listOf("zones/z")
    return ScriptFixture(files, load = load)
  }

  @Test
  fun `item and room on_destroy run in order with expected ctx fields`() {
    val f =
      fixture(
        zone =
          """
          def _item_destroy(ctx):
              ctx.echo(ctx.room, "item:%s:%s:%s:%s" % (ctx.self.name, ctx.item.name, ctx.event, ctx.actor.name))

          def _room_destroy(ctx):
              ctx.echo(ctx.room, "room:%s:%s:%s:%s" % (ctx.self.name, ctx.item.name, ctx.event, ctx.actor.name))

          def _do_destroy(ctx):
              it = ctx.actor.inventory()[0]
              ctx.destroy(it)

          room("start", on_destroy = _room_destroy, commands = {"zap_item": _do_destroy})
          item("relic", on_destroy = _item_destroy)
          """
      )
    val p = f.player()
    f.out(p)
    val relic = f.w.spawnItem(Label("//zones/z:relic"), ItemLocation.Carried(p.id))

    f.input(p, "zap_item")
    f.run(1)

    assertNull(f.w.item(relic.id))
    val lines = f.out(p).lines().filter { it.isNotBlank() }
    assertEquals(
      listOf(
        "item:${relic.name}:${relic.name}:destroy:${p.name}",
        "room:start:${relic.name}:destroy:${p.name}",
      ),
      lines,
    )
  }

  @Test
  fun `ctx destroyed_root is the item itself for the root and the container for its contents`() {
    val f =
      fixture(
        zone =
          """
          def _relic_destroy(ctx):
              if ctx.destroyed_root == ctx.self:
                  ctx.echo(ctx.room, "root:%s" % ctx.self.name)
              else:
                  ctx.echo(ctx.room, "inside:%s" % ctx.destroyed_root.name)

          def _zap(ctx):
              ctx.destroy(ctx.actor.inventory()[0])

          room("start", commands = {"zap": _zap})
          item("satchel", container = container())
          item("relic", on_destroy = _relic_destroy)
          """
      )
    val p = f.player()
    f.out(p)
    val satchel = f.w.spawnItem(Label("//zones/z:satchel"), ItemLocation.Carried(p.id))
    val inner = f.w.spawnItem(Label("//zones/z:relic"), ItemLocation.Contained(satchel.id))

    f.input(p, "zap")
    f.run(1)
    val alone = f.w.spawnItem(Label("//zones/z:relic"), ItemLocation.Carried(p.id))
    f.input(p, "zap")
    f.run(1)

    assertNull(f.w.item(inner.id))
    assertNull(f.w.item(alone.id))
    assertEquals(
      listOf("inside:${satchel.name}", "root:${alone.name}"),
      f.out(p).lines().filter { it.isNotBlank() },
    )
  }

  @Test
  fun `on_destroy on a mob prototype fails to load`() {
    val result =
      ScriptLoader(
          WorldDir(
            tempWorld(
              mapOf(
                "zones/z/package.star" to zonePkg(),
                "zones/z/a.star" to
                  """
                  def _h(ctx):
                      pass

                  room("start")
                  mob("m", on_destroy = _h)
                  """
                    .trimIndent(),
              )
            )
          ),
          ScriptLimits.HARNESS,
        )
        .load(listOf("zones/z"))
    assertTrue(
      result.errors.any { "mob(): hook 'on_destroy' can't be attached to a mob" in it.message },
      result.errors.joinToString { it.message },
    )
  }

  @Test
  fun `item hook moving ctx self to ctx room from destroyed satchel saves it`() {
    val f =
      fixture(
        zone =
          """
          def _escape(ctx):
              ctx.move_item(ctx.self, ctx.room)

          def _salvage(ctx):
              bag = ctx.actor.inventory()[0]
              ctx.destroy(bag)

          room("start", commands = {"salvage": _salvage})
          item("satchel", container = container())
          item("brass_key", on_destroy = _escape)
          """
      )
    val p = f.player()
    val satchel = f.w.spawnItem(Label("//zones/z:satchel"), ItemLocation.Carried(p.id))
    val key = f.w.spawnItem(Label("//zones/z:brass_key"), ItemLocation.Contained(satchel.id))

    f.input(p, "salvage")
    f.run(1)

    assertTrue(
      f.diagnostics().isEmpty(),
      f.diagnostics().joinToString { "${it.kind}: ${it.message}" },
    )
    assertNull(f.w.item(satchel.id))
    assertNotNull(f.w.item(key.id))
    assertEquals(ItemLocation.InRoom(p.room.id, 0), key.location)
  }

  @Test
  fun `global_hook on_destroy runs once per destroyed item after item and room hooks`() {
    val f =
      fixture(
        zone =
          """
          def _item_destroy(ctx):
              ctx.echo(ctx.room, "item:%s:%s:%s:%s" % (ctx.self.name, ctx.item.name, ctx.event, ctx.actor.name))

          def _room_destroy(ctx):
              ctx.echo(ctx.room, "room:%s:%s:%s:%s" % (ctx.self.name, ctx.item.name, ctx.event, ctx.actor.name))

          def _do_destroy(ctx):
              it = ctx.actor.inventory()[0]
              ctx.destroy(it)

          room("start", on_destroy = _room_destroy, commands = {"zap_item": _do_destroy})
          item("relic", on_destroy = _item_destroy)
          """,
        lib =
          """
          def _global_destroy(ctx):
              ctx.echo(ctx.room, "global:%s:%s:%s:%s" % (ctx.self.name, ctx.item.name, ctx.event, ctx.actor.name))

          global_hook("on_destroy", _global_destroy)
          """,
      )
    val p = f.player()
    f.out(p)
    val relic = f.w.spawnItem(Label("//zones/z:relic"), ItemLocation.Carried(p.id))

    f.input(p, "zap_item")
    f.run(1)

    assertNull(f.w.item(relic.id))
    val lines = f.out(p).lines().filter { it.isNotBlank() }
    assertEquals(
      listOf(
        "item:${relic.name}:${relic.name}:destroy:${p.name}",
        "room:start:${relic.name}:destroy:${p.name}",
        "global:${relic.name}:${relic.name}:destroy:${p.name}",
      ),
      lines,
    )
  }

  @Test
  fun `global_hook on_destroy skips an item that escaped its container`() {
    val f =
      fixture(
        zone =
          """
          def _escape(ctx):
              ctx.move_item(ctx.self, ctx.room)

          def _salvage(ctx):
              bag = ctx.actor.inventory()[0]
              ctx.destroy(bag)

          room("start", commands = {"salvage": _salvage})
          item("satchel", container = container())
          item("brass_key", on_destroy = _escape)
          """,
        lib =
          """
          def _global_destroy(ctx):
              ctx.echo(ctx.room, "global:%s" % ctx.item.name)

          global_hook("on_destroy", _global_destroy)
          """,
      )
    val p = f.player()
    f.out(p)
    val satchel = f.w.spawnItem(Label("//zones/z:satchel"), ItemLocation.Carried(p.id))
    val key = f.w.spawnItem(Label("//zones/z:brass_key"), ItemLocation.Contained(satchel.id))

    f.input(p, "salvage")
    f.run(1)

    assertNull(f.w.item(satchel.id))
    assertNotNull(f.w.item(key.id))
    val lines = f.out(p).lines().filter { it.isNotBlank() }
    assertEquals(listOf("global:${satchel.name}"), lines)
  }

  @Test
  fun `global_hook on_destroy that raises records a script diagnostic and destroys the item`() {
    val f =
      fixture(
        zone =
          """
          def _do_destroy(ctx):
              it = ctx.actor.inventory()[0]
              ctx.destroy(it)

          room("start", commands = {"zap_item": _do_destroy})
          item("relic")
          """,
        lib =
          """
          def _boom(ctx):
              fail("boom")

          global_hook("on_destroy", _boom)
          """,
      )
    val p = f.player()
    val relic = f.w.spawnItem(Label("//zones/z:relic"), ItemLocation.Carried(p.id))

    f.input(p, "zap_item")
    f.run(1)

    assertNull(f.w.item(relic.id))
    assertTrue(
      f.diagnostics().any { it.kind in setOf("script", "script_error") && "boom" in it.message },
      f.diagnostics().joinToString { "${it.kind}: ${it.message}" },
    )
  }

  @Test
  fun `global_hook on_destroy in a zone package fails to load`() {
    val result =
      ScriptLoader(
          WorldDir(
            tempWorld(
              mapOf(
                "zones/z/package.star" to zonePkg(),
                "zones/z/a.star" to
                  """
                  def _h(ctx):
                      pass

                  room("start")
                  global_hook("on_destroy", _h)
                  """
                    .trimIndent(),
              )
            )
          ),
          ScriptLimits.HARNESS,
        )
        .load(listOf("zones/z"))
    val err = result.errors.firstOrNull { it.pkg == "zones/z" }
    assertNotNull(err, result.report())
    assertContains(err.message, "only lib and rules packages may register global hooks")
  }
}
