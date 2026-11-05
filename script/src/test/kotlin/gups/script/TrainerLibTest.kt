package gups.script

import gups.core.Label
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import gups.script.testing.TestWorlds
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The template world's trainer library, run in worlds the `.star` tests can't build. */
class TrainerLibTest {
  private val trainerLib: String =
    Files.readString(TestWorlds.templateWorld.resolve("lib/trainer.star"))

  private val zap = Label("//zones/z:zap")

  private fun fixture(features: String? = null, offer: String = "return None"): ScriptFixture {
    val module =
      if (features == null) TestWorlds.MODULE else TestWorlds.module("features = [$features]")
    val mobSkills = if (features == null) "skills = {\"//zones/z:zap\": 100}," else ""
    val enabled = features?.split(",")?.map { it.trim().trim('"') }
    return ScriptFixture(
      mapOf(
        "MODULE.gups" to module,
        "lib/package.star" to libPkg(),
        "lib/trainer.star" to trainerLib,
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to
          (if (enabled == null) TestWorlds.rules() else TestWorlds.rulesFor(enabled)),
        "zones/z/package.star" to zonePkg(),
        "zones/z/skills.star" to (if (features == null) "skill(\"zap\", kind = \"spell\")" else ""),
        "zones/z/a.star" to
          """
          load("//lib:trainer.star", "trainer")

          TEACHER = trainer.define(id = "teacher",
                                   teaches = [trainer.teach("//zones/z:zap", up_to = 50, gain = 10)])

          def _offer(ctx, customer, skill, current, gain, cost, refusal):
              $offer

          def _open(ctx):
              trainer.open(ctx, ctx.self, TEACHER)

          def _grant(ctx):
              trainer.grant_practices(ctx, ctx.actor, 5)

          room("start", commands = {"grant": _grant}, spawns = [spawn(":teacher")])
          mob("teacher", name = "the teacher", $mobSkills
              hooks = {"practice_offer": _offer}, on_spawn = _open)
          """,
      ),
      load = listOf("zones/z", "lib"),
    )
  }

  private fun ScriptFixture.say(p: gups.core.model.Player, line: String): String {
    input(p, line)
    run(1)
    return out(p)
  }

  @Test
  fun `practice in a world without skills says so and raises no error`() {
    val f = fixture(features = "\"combat\", \"track\", \"leveling\"")
    val p = f.player()
    assertContains(f.say(p, "practice"), "There's nothing to practice here.")
    assertContains(f.say(p, "practice zap"), "There's nothing to practice here.")
    assertEquals(emptyList(), f.diagnostics("script_error"))
  }

  @Test
  fun `an invalid practice_offer return is ignored and the defaults apply`() {
    for (bad in
      listOf(
        "return 42",
        "return {\"gain\": 0}",
        "return {\"cost\": -1}",
        "return {\"gain\": \"x\"}",
      )) {
      val f = fixture(offer = bad)
      val p = f.player()
      f.say(p, "grant")
      assertContains(
        f.say(p, "practice zap"),
        "You learn zap from the teacher. (10%)",
        message = bad,
      )
      assertEquals(10, p.skills[zap], bad)
      assertContains(f.say(p, "practice"), "You have 4 practice sessions.", message = bad)
    }
  }

  @Test
  fun `a learn refusal is sent to the player and spends nothing`() {
    val f = fixture()
    val p = f.player()
    f.say(p, "grant")
    f.w.restrictions =
      mapOf(RestrictionPoint.LEARN to listOf(Restriction { _, _ -> "Not for warriors." }))
    assertContains(f.say(p, "practice zap"), "Not for warriors.")
    assertNull(p.skills[zap])
    f.w.restrictions = mapOf()
    assertContains(f.say(p, "practice"), "You have 5 practice sessions.")
  }
}
