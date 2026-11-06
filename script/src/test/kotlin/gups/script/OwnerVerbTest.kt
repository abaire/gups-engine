package gups.script

import gups.core.Label
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertTrue

private const val OWNER_ZONE: String =
  """
  def _adopt(ctx):
      ok = ctx.set_owner(ctx.here("wolf"), ctx.actor)
      ctx.send(ctx.actor, "adopt %s" % ok)

  def _release(ctx):
      ok = ctx.clear_owner(ctx.here("wolf"))
      ctx.send(ctx.actor, "release %s" % ok)

  def _who(ctx):
      o = ctx.here("wolf").owner
      ctx.send(ctx.actor, "owner %s" % (o.name if o else "none"))

  def _bad_mob(ctx):
      ctx.set_owner(ctx.actor, ctx.actor)

  def _bad_player(ctx):
      ctx.set_owner(ctx.here("wolf"), ctx.here("wolf"))

  room("start",
      name = "A room",
      desc = "A room.",
      flags = ["dead_end_ok"],
      commands = {
          "adopt": _adopt,
          "release": _release,
          "whose": _who,
          "bad_mob": _bad_mob,
          "bad_player": _bad_player,
      })
  mob("wolf", name = "a wolf", keywords = ["wolf"], vitals = vitals(hp = 10))
  """

private const val OWNER_LIB: String =
  """
  def _note(ctx):
      ctx.send(ctx.target, "changed %s %s" % (ctx.event, ctx.actor.name))

  global_hook("on_owner_change", _note)
  """

class OwnerVerbTest {
  private fun world(): ScriptFixture =
    ScriptFixture(
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "lib/package.star" to libPkg(),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(),
        "zones/z/a.star" to OWNER_ZONE,
        "lib/owner.star" to OWNER_LIB,
      ),
      load = listOf("zones/z", "lib"),
    )

  @Test
  fun `set_owner and clear_owner change the owner, the view shows it, and the hook fires`() {
    val f = world()
    val ann = f.player("Ann")
    f.w.spawnMob(Label("//zones/z:wolf"), ann.room)
    f.input(ann, "whose")
    f.input(ann, "adopt")
    f.input(ann, "adopt")
    f.input(ann, "whose")
    f.input(ann, "release")
    f.input(ann, "release")
    f.input(ann, "whose")
    f.run(8)
    val out = f.out(ann)
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics("script_error").toString())
    val lines =
      out.lines().filter {
        it.startsWith("owner ") ||
          it.startsWith("adopt") ||
          it.startsWith("release") ||
          it.startsWith("changed")
      }
    assertTrue(
      lines ==
        listOf(
          "owner none",
          "changed set a wolf",
          "adopt True",
          "adopt False",
          "owner Ann",
          "changed clear a wolf",
          "release True",
          "release False",
          "owner none",
        ),
      lines.toString(),
    )
  }

  @Test
  fun `bad arguments are script errors naming the verb`() {
    val f = world()
    val ann = f.player("Ann")
    f.w.spawnMob(Label("//zones/z:wolf"), ann.room)
    f.input(ann, "bad_mob")
    f.run(1)
    f.input(ann, "bad_player")
    f.run(1)
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(errors.any { "set_owner(mob): must be a mob" in it }, errors.toString())
    assertTrue(
      errors.any { "set_owner(player): must be a player in the world" in it },
      errors.toString(),
    )
  }
}
