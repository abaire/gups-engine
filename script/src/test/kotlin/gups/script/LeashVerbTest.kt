package gups.script

import gups.core.Label
import gups.script.testing.TestWorlds
import gups.script.testing.tempWorld
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

private const val LEASH_ZONE: String =
  """
  def _print(ctx):
      w = ctx.here("wolf")
      ctx.send(ctx.actor, "wolf %s %s" % (w.leash, w.leash_overridden))

  def _mine(ctx):
      ctx.send(ctx.actor, "me %s %s" % (ctx.actor.leash, ctx.actor.leash_overridden))

  def _set(ctx):
      ctx.set_leash(ctx.here("wolf"), 1)
      w = ctx.here("wolf")
      ctx.send(ctx.actor, "wolf %s %s" % (w.leash, w.leash_overridden))

  def _unleash(ctx):
      ctx.set_leash(ctx.here("wolf"), None)
      w = ctx.here("wolf")
      ctx.send(ctx.actor, "wolf %s %s" % (w.leash, w.leash_overridden))

  def _reset(ctx):
      ctx.reset_leash(ctx.here("wolf"))
      w = ctx.here("wolf")
      ctx.send(ctx.actor, "wolf %s %s" % (w.leash, w.leash_overridden))

  def _bad_mob(ctx):
      ctx.set_leash(ctx.actor, 1)

  def _neg(ctx):
      ctx.set_leash(ctx.here("wolf"), -1)

  def _str(ctx):
      ctx.set_leash(ctx.here("wolf"), "x")

  room("start",
      name = "A cave",
      desc = "A cave.",
      flags = ["dead_end_ok"],
      commands = {
          "print": _print,
          "mine": _mine,
          "set": _set,
          "unleash": _unleash,
          "reset": _reset,
          "bad_mob": _bad_mob,
          "neg": _neg,
          "str": _str,
      })
  mob("wolf", name = "a wolf", keywords = ["wolf"], vitals = vitals(hp = 10), leash = 4)
  """

class LeashVerbTest {
  private fun world(): ScriptFixture =
    ScriptFixture(
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "lib/package.star" to libPkg(),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(),
        "zones/z/a.star" to LEASH_ZONE,
      ),
      load = listOf("zones/z", "lib"),
    )

  @Test
  fun `the view shows the prototype leash and the verbs change, unleash and restore it`() {
    val f = world()
    val ann = f.player("Ann")
    f.w.spawnMob(Label("//zones/z:wolf"), ann.room)
    f.input(ann, "print")
    f.input(ann, "set")
    f.input(ann, "unleash")
    f.input(ann, "reset")
    f.input(ann, "mine")
    f.run(8)
    val out = f.out(ann)
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics("script_error").toString())
    val lines = out.lines().filter { it.startsWith("wolf ") || it.startsWith("me ") }
    assertTrue(
      lines ==
        listOf("wolf 4 False", "wolf 1 True", "wolf None True", "wolf 4 False", "me None False"),
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
    f.input(ann, "neg")
    f.run(1)
    f.input(ann, "str")
    f.run(1)
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(errors.any { "set_leash(mob): must be a mob" in it }, errors.toString())
    assertTrue(
      errors.any { "set_leash(leash): must be an int of at least 0 or None" in it },
      errors.toString(),
    )
  }

  @Test
  fun `loading a negative leash fails`() {
    val files =
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to
          """
          room("cave", name = "A cave", desc = "A cave.", flags = ["dead_end_ok"])
          mob("wolf", name = "a wolf", keywords = ["wolf"], vitals = vitals(hp = 10), leash = -1)
          """,
      )
    val r = ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(listOf("zones/z"))
    assertContains(r.report(), "mob(leash): must be at least 0")
  }
}
