package gups.script

import gups.core.Label
import gups.core.world.startCombat
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A room whose `on_disengage` hook clears the freed actor's ledger and reports the event. */
private const val DISENGAGE_ZONE: String =
  """
  def _disengage(ctx):
      ctx.clear_contributions(ctx.actor)
      ctx.send(ctx.actor, "disengaged %s" % ctx.event)

  def _cease(ctx):
      ctx.stop_combat(ctx.actor)

  room("start",
      name = "A room",
      desc = "A room.",
      flags = ["dead_end_ok"],
      on_disengage = _disengage,
      commands = {"cease": _cease})
  mob("goblin", vitals = vitals(hp = 20))
  """

class DisengageScriptTest {
  private fun fixture(vararg files: Pair<String, String>): ScriptFixture =
    ScriptFixture(
      mapOf("zones/z/package.star" to zonePkg(), "rules/package.star" to TestWorlds.RULES_PACKAGE) +
        files,
      load = listOf("zones/z"),
    )

  @Test
  fun `an on_disengage hook can clear contributions without a script error`() {
    val f = fixture("zones/z/a.star" to DISENGAGE_ZONE, "rules/combat.star" to TestWorlds.rules())
    val ann = f.player("Ann")
    val goblin = f.w.spawnMob(Label("//zones/z:goblin"), ann.room)
    f.w.startCombat(ann, goblin)
    f.input(ann, "cease")
    f.run(1)
    val out = f.out(ann)
    assertTrue(out.contains("disengaged stop"), "the hook fired for the freed actor: $out")
    assertTrue(
      f.diagnostics("script_error").isEmpty(),
      f.diagnostics("script_error").joinToString { it.message },
    )
  }

  @Test
  fun `start_combat with a dying actor is a script error naming it`() {
    val zone =
      """
      def _avenge(ctx):
          ctx.start_combat(ctx.actor, ctx.self)

      room("start", name = "A room", desc = "A room.", flags = ["dead_end_ok"])
      mob("goblin", vitals = vitals(hp = 1), on_death = _avenge)
      """
        .trimIndent()
    val f = fixture("zones/z/a.star" to zone, "rules/combat.star" to TestWorlds.rules())
    val ann = f.player("Ann")
    val goblin = f.w.spawnMob(Label("//zones/z:goblin"), ann.room)
    f.w.startCombat(ann, goblin)
    f.run(60)
    assertFalse(goblin.inWorld, "the goblin died")
    assertFalse(ann.isEngaged, "nothing re-engaged Ann with the dead goblin")
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(errors.any { "is dying" in it }, errors.toString())
  }
}
