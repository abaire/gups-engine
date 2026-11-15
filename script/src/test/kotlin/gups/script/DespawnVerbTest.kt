package gups.script

import gups.core.Label
import gups.core.model.DamageType
import gups.core.world.damage
import gups.core.world.despawn
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DESPAWN_ZONE: String =
  """
  def _dismiss(ctx):
      res = ctx.despawn(ctx.here("wolf"))
      ctx.send(ctx.actor, "res:%s" % res)

  def _despawn_player(ctx):
      ctx.despawn(ctx.actor)

  def _despawn_twice(ctx):
      w = ctx.here("wolf")
      ctx.despawn(w)
      ctx.despawn(w)

  def _despawn_silent(ctx):
      ctx.despawn(ctx.here("wolf"))

  def _on_death_despawn(ctx):
      ctx.despawn(ctx.self)

  def _on_despawn_reenter(ctx):
      ctx.echo(ctx.room, "despawn-hook-ran")
      ctx.despawn(ctx.self)

  room("start",
      name = "A room",
      desc = "A room.",
      flags = ["dead_end_ok"],
      commands = {
          "dismiss": _dismiss,
          "bad_player": _despawn_player,
          "twice": _despawn_twice,
          "silent": _despawn_silent,
      })
  mob("wolf", name = "a wolf", keywords = ["wolf"], vitals = vitals(hp = 10))
  mob("dying_wolf", name = "a wolf", keywords = ["wolf"], vitals = vitals(hp = 10), on_death = _on_death_despawn)
  mob("reenter_wolf", name = "a wolf", keywords = ["wolf"], vitals = vitals(hp = 10), on_despawn = _on_despawn_reenter)
  """

class DespawnVerbTest {
  private fun world(): ScriptFixture =
    ScriptFixture(
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(),
        "zones/z/a.star" to DESPAWN_ZONE,
      ),
      load = listOf("zones/z"),
    )

  @Test
  fun `despawn removes a mob, returns None, and prints nothing`() {
    val f = world()
    val ann = f.player("Ann")
    val bob = f.player("Bob")
    val wolf = f.w.spawnMob(Label("//zones/z:wolf"), ann.room)
    f.out(ann)
    f.out(bob)

    f.input(ann, "silent")
    f.run(4)
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics("script_error").toString())
    assertFalse(wolf.inWorld)
    assertEquals("", f.out(ann).trim())
    assertEquals("", f.out(bob).trim())

    val wolf2 = f.w.spawnMob(Label("//zones/z:wolf"), ann.room)
    f.input(ann, "dismiss")
    f.run(4)
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics("script_error").toString())
    assertFalse(wolf2.inWorld)
    assertContains(f.out(ann), "res:None")
  }

  @Test
  fun `despawn on a player fails with must be a mob`() {
    val f = world()
    val ann = f.player("Ann")
    f.input(ann, "bad_player")
    f.run(4)
    val errs = f.diagnostics("script_error")
    assertTrue(errs.isNotEmpty(), "expected script_error")
    assertContains(errs.first().message, "despawn(mob): must be a mob")
  }

  @Test
  fun `despawn on the same view twice fails the second time`() {
    val f = world()
    val ann = f.player("Ann")
    f.w.spawnMob(Label("//zones/z:wolf"), ann.room)
    f.input(ann, "twice")
    f.run(4)
    val errs = f.diagnostics("script_error")
    assertTrue(errs.isNotEmpty(), "expected script_error")
    assertContains(errs.first().message, "despawn(mob): a wolf is not in the world")
  }

  @Test
  fun `despawn from the mob's own on_death hook fails with is dying`() {
    val f = world()
    val ann = f.player("Ann")
    val wolf = f.w.spawnMob(Label("//zones/z:dying_wolf"), ann.room)
    f.w.damage(wolf, mapOf("hp" to 100), DamageType("fire"))
    f.run(4)
    val errs = f.diagnostics("script_error")
    assertTrue(errs.isNotEmpty(), "expected script_error")
    assertContains(errs.first().message, "despawn(mob): a wolf is dying")
  }

  @Test
  fun `despawn from the mob's own on_despawn hook is a no-op and runs the hook once`() {
    val f = world()
    val ann = f.player("Ann")
    val wolf = f.w.spawnMob(Label("//zones/z:reenter_wolf"), ann.room)
    f.out(ann)
    f.w.despawn(wolf)
    f.run(4)
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics("script_error").toString())
    assertFalse(wolf.inWorld)
    val lines = f.out(ann).lines().filter { it == "despawn-hook-ran" }
    assertEquals(listOf("despawn-hook-ran"), lines)
  }
}
