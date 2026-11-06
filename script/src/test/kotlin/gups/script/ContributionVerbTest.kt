package gups.script

import gups.core.Label
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A poison whose on_tick damages its host without naming a source. */
private const val POISON_ZONE: String =
  """
  room("start", name = "The Pit", desc = "A dank pit.", flags = ["dead_end_ok"])

  def _tick(ctx):
      ctx.damage(ctx.self, 100, DAMAGE.bludgeon, "hp")

  skill("poison",
      kind = "spell",
      target = "enemy",
      effect = apply_affect(name = "poison", duration = 20, tick_every = 1, on_tick = _tick),
  )
  mob("victim", vitals = vitals(hp = 30))
  """

class ContributionVerbTest {
  private fun fixture(vararg files: Pair<String, String>): ScriptFixture =
    ScriptFixture(
      mapOf("zones/z/package.star" to zonePkg(), "rules/package.star" to TestWorlds.RULES_PACKAGE) +
        files
    )

  @Test
  fun `an affect tick that names no source is credited to the applier as the killer`() {
    val f =
      fixture(
        "zones/z/a.star" to POISON_ZONE,
        "rules/combat.star" to TestWorlds.rules("def xp_for_kill(ctx):\n    return 500"),
      )
    val caster = f.player("Ann")
    caster.skills[Label("//zones/z:poison")] = 100
    val victim = f.mob("//zones/z:victim")
    f.input(caster, "cast poison victim")
    f.run(60)
    assertFalse(victim.inWorld, "the poison tick killed the victim")
    assertEquals(caster.id, victim.lastAttacker, "the applier is the killer")
    assertEquals(500L, caster.xp, "the applier is paid the kill")
  }

  @Test
  fun `clear_contributions and a sourced heal run without a script error`() {
    val zone =
      """
      def _clear(ctx):
          ctx.clear_contributions(ctx.actor)

      def _heal(ctx):
          ctx.heal(ctx.actor, 10, "hp", source = ctx.actor)

      def _clear_one(ctx):
          ctx.clear_contributions(ctx.actor, ctx.actor)

      room("start",
          name = "A room",
          desc = "A room.",
          flags = ["dead_end_ok"],
          commands = {"clear": _clear, "heal": _heal, "clear_one": _clear_one})
      """
    val f = fixture("zones/z/a.star" to zone)
    val p = f.player("Ann")
    f.input(p, "clear")
    f.input(p, "heal")
    f.input(p, "clear_one")
    f.run(3)
    assertTrue(
      f.diagnostics("script_error").isEmpty(),
      f.diagnostics("script_error").joinToString { it.message },
    )
  }
}
