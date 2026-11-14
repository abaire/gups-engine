package gups.script

import gups.core.Label
import gups.core.Role
import gups.core.WorldControlEvent
import gups.core.model.Effect
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.world.applyEffect
import gups.core.world.despawn
import gups.core.world.handle
import gups.core.world.quit
import gups.core.world.spawnItem
import gups.core.world.startCombat
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DespawnHookTest {
  private val linearCurve =
    """def xp_to_level(ctx, level):
    return 1000 * level
"""

  private fun fixture(
    zone: String,
    lib: String? = null,
    vararg more: Pair<String, String>,
  ): ScriptFixture {
    val files =
      buildMap<String, String> {
        put("zones/z/package.star", zonePkg())
        put("rules/package.star", TestWorlds.RULES_PACKAGE)
        put("rules/combat.star", TestWorlds.rules(linearCurve))
        put("zones/z/a.star", zone)
        if (lib != null) {
          put("lib/package.star", libPkg())
          put("lib/g.star", lib)
        }
        for ((k, v) in more) put(k, v)
      }
    val load = if (lib != null) listOf("zones/z", "lib") else listOf("zones/z")
    return ScriptFixture(files, load = load)
  }

  @Test
  fun `on_despawn runs on mob, room, then global, seeing mob in room with its item, and skips bystanders`() {
    val f =
      fixture(
        zone =
          """
          def _mob_despawn(ctx):
              ctx.echo(ctx.room, "mob:%s:%s:%d" % (ctx.actor.name, ctx.actor.room.name, len(ctx.actor.inventory())))

          def _other_despawn(ctx):
              ctx.echo(ctx.room, "other-ran")

          def _room_despawn(ctx):
              ctx.echo(ctx.room, "room:%s:%s:%d" % (ctx.actor.name, ctx.actor.room.name, len(ctx.actor.inventory())))

          room("start", on_despawn = _room_despawn)
          mob("m", on_despawn = _mob_despawn)
          mob("bystander", on_despawn = _other_despawn)
          item("coin")
          """,
        lib =
          """
          def _global_despawn(ctx):
              ctx.echo(ctx.room, "global:%s:%s:%d" % (ctx.actor.name, ctx.actor.room.name, len(ctx.actor.inventory())))

          global_hook("on_despawn", _global_despawn)
          """,
      )
    val p = f.player()
    f.out(p)
    val m = f.mob("//zones/z:m")
    f.mob("//zones/z:bystander")
    f.w.spawnItem(Label("//zones/z:coin"), ItemLocation.Carried(m.id))

    f.w.despawn(m)
    f.run()
    val lines = f.out(p).lines().filter { it.isNotBlank() }
    assertEquals(listOf("mob:m:start:1", "room:m:start:1", "global:m:start:1"), lines)
    assertFalse("other-ran" in lines)
  }

  @Test
  fun `an on_despawn hook can move an item off the mob to the room to keep it`() {
    val f =
      fixture(
        zone =
          """
          def _save_item(ctx):
              for it in ctx.actor.inventory():
                  ctx.move_item(it, ctx.room)

          room("start")
          mob("m", on_despawn = _save_item)
          item("coin")
          """
      )
    val m = f.mob("//zones/z:m")
    val coin = f.w.spawnItem(Label("//zones/z:coin"), ItemLocation.Carried(m.id))

    f.w.despawn(m)
    f.run()
    assertTrue(
      f.diagnostics().isEmpty(),
      f.diagnostics().joinToString { "${it.kind}: ${it.message}" },
    )
    assertFalse(m.inWorld)
    assertNotNull(f.w.item(coin.id))
    assertEquals(listOf(coin), f.w.room(Label("//zones/z:start")).items)
  }

  @Test
  fun `opponent on_disengage runs with stop after the despawned mob is gone`() {
    val f =
      fixture(
        zone =
          """
          def _disengage(ctx):
              names = [a.name for a in ctx.room.actors()]
              ctx.echo(ctx.room, "disengage:%s:%s:%s" % (ctx.actor.name, ctx.event, "m" in names))

          room("start")
          mob("m")
          mob("opp", on_disengage = _disengage)
          """
      )
    val p = f.player()
    f.out(p)
    val m = f.mob("//zones/z:m")
    val opp = f.mob("//zones/z:opp")
    f.w.startCombat(m, opp)

    f.w.despawn(m)
    f.run()
    val out = f.out(p)
    assertContains(out, "disengage:opp:stop:False")
  }

  @Test
  fun `damaging the mob past 0 hp inside on_despawn produces no corpse and no on_death`() {
    val f =
      fixture(
        zone =
          """
          def _hurt(ctx):
              ctx.damage(ctx.actor, 9999, "fire", "hp")

          def _died(ctx):
              ctx.echo(ctx.room, "on_death-ran")

          room("start")
          mob("m", on_despawn = _hurt, on_death = _died)
          """
      )
    val p = f.player()
    f.out(p)
    val m = f.mob("//zones/z:m")

    f.w.despawn(m)
    f.run()
    assertTrue(
      f.diagnostics().isEmpty(),
      f.diagnostics().joinToString { "${it.kind}: ${it.message}" },
    )
    assertFalse(m.inWorld)
    assertTrue(f.w.room(Label("//zones/z:start")).items.isEmpty())
    assertFalse("on_death-ran" in f.out(p))
  }

  @Test
  fun `start_combat inside on_despawn records a diagnostic and leaves neither actor engaged`() {
    val f =
      fixture(
        zone =
          """
          def _reengage(ctx):
              for a in ctx.room.actors():
                  if a.is_player:
                      ctx.start_combat(ctx.actor, a)

          room("start")
          mob("m", on_despawn = _reengage)
          """
      )
    val p = f.player()
    val m = f.mob("//zones/z:m")

    f.w.despawn(m)
    assertFalse(p.isEngaged)
    assertFalse(m.isEngaged)
    val errs = f.diagnostics("script_error")
    assertTrue(errs.isNotEmpty(), "expected script_error")
    assertContains(errs.first().message, "start_combat(): m is leaving the world")
  }

  @Test
  fun `global_hook accepts on_despawn and rejects on_tick with the full list`() {
    val r =
      ScriptLoader(
          WorldDir(
            gups.script.testing.tempWorld(
              mapOf(
                "lib/package.star" to libPkg(),
                "lib/a.star" to
                  """
                  def f(ctx):
                      pass

                  global_hook("on_despawn", f)
                  global_hook("on_tick", f)
                  """,
              )
            )
          ),
          ScriptLimits.HARNESS,
        )
        .load(listOf("lib"))
    val err = r.errors.first { it.pkg == "lib" }
    assertContains(
      err.message,
      "can't be a global hook; only on_level_change, on_encumbrance_change, on_group_change, on_owner_change, on_disengage, on_death, on_despawn, on_destroy and on_skill_use can",
    )
  }

  @Test
  fun `a timed summon fighting a player fires on_despawn once and player on_disengage with stop`() {
    val f =
      fixture(
        zone =
          """
          def _mob_despawn(ctx):
              ctx.echo(ctx.room, "summon-despawn:%s" % ctx.actor.name)

          def _room_disengage(ctx):
              ctx.echo(ctx.room, "disengage:%s:%s" % (ctx.actor.name, ctx.event))

          room("start", on_disengage = _room_disengage)
          mob("m", on_despawn = _mob_despawn)
          """,
        lib =
          """
          def _global_disengage(ctx):
              if ctx.actor.is_player:
                  ctx.echo(ctx.room, "player-disengage:%s:%s" % (ctx.actor.name, ctx.event))

          global_hook("on_disengage", _global_disengage)
          """,
      )
    val p = f.player("Ann")
    val obs = f.player("Obs")
    f.w.applyEffect(Effect.Summon(Label("//zones/z:m"), count = 1, duration = 20), p, null)
    val summon = f.w.actors.filterIsInstance<Mob>().single()
    f.w.startCombat(summon, p)
    f.out(obs)

    f.run(25)
    assertFalse(summon.inWorld)
    assertTrue(p.fighting.isEmpty())
    val lines = f.out(obs).lines().filter { it.isNotBlank() }
    assertEquals(1, lines.count { it == "summon-despawn:m" }, lines.joinToString("\n"))
    assertContains(lines, "player-disengage:Ann:stop")
  }

  @Test
  fun `a debug mob whose spawner logs out runs on_despawn`() {
    val f =
      fixture(
        zone =
          """
          def _mob_despawn(ctx):
              ctx.echo(ctx.room, "debug-despawn:%s" % ctx.actor.name)

          room("start")
          mob("m", on_despawn = _mob_despawn)
          """
      )
    val b = f.player("Bea")
    b.role = Role.BUILDER
    b.scope = setOf("zones/z")
    b.accountId = 1
    val obs = f.player("Obs")
    f.input(b, "@spawn :m")
    f.run()
    val m = f.w.actors.filterIsInstance<Mob>().single()
    assertTrue(m.debug)
    f.out(obs)

    f.w.quit(b)
    f.run()
    assertFalse(m.inWorld)
    assertContains(f.out(obs), "debug-despawn:m")
  }

  @Test
  fun `a reload that deletes a mob prototype runs no on_despawn`() {
    val startZone =
      """
      def _mob_despawn(ctx):
          ctx.echo(ctx.room, "mob-despawn")

      room("start")
      mob("m", on_despawn = _mob_despawn)
      """
    val f =
      fixture(
        zone = startZone,
        lib =
          """
          def _global_despawn(ctx):
              ctx.echo(ctx.room, "global-despawn")

          global_hook("on_despawn", _global_despawn)
          """,
      )
    val p = f.player("Ann")
    p.role = Role.BUILDER
    val m = f.mob("//zones/z:m")
    f.out(p)

    f.write("zones/z/a.star", "room(\"start\")\n")
    f.w.handle(WorldControlEvent.Reload(listOf("zones/z")))
    f.run()
    assertFalse(m.inWorld)
    val out = f.out(p)
    assertFalse("mob-despawn" in out, out)
    assertFalse("global-despawn" in out, out)
  }
}
