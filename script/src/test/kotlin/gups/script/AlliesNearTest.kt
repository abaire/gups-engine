package gups.script

import gups.core.Label
import gups.core.model.Effect
import gups.core.world.groupAdd
import gups.core.world.summon
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertTrue

/** Room command that reports `allies_near`, so the group clause can be observed. */
private const val ALLIES_ZONE: String =
  """
  def _allies(ctx):
      allies = ctx.allies_near(ctx.actor, "far")
      ctx.send(ctx.actor, "allies %s" % ",".join([a.name for a in allies]))

  room("start",
      name = "A room",
      desc = "A room.",
      flags = ["dead_end_ok"],
      commands = {"allies": _allies})
  mob("helper", vitals = vitals(hp = 10))
  mob("stray", vitals = vitals(hp = 10))
  """

class AlliesNearTest {
  private fun fixture(vararg files: Pair<String, String>): ScriptFixture =
    ScriptFixture(
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "lib/package.star" to libPkg(),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
      ) + files,
      load = listOf("zones/z", "lib"),
    )

  @Test
  fun `group members and a member's pet are allies, and the same side alone is unchanged`() {
    val f = fixture("zones/z/a.star" to ALLIES_ZONE, "rules/combat.star" to TestWorlds.rules())
    val ann = f.player("Ann")
    val quinn = f.player("Quinn")
    f.w.summon(quinn, Effect.Summon(Label("//zones/z:helper"), 1, null))
    f.w.spawnMob(Label("//zones/z:stray"), ann.room)
    f.input(ann, "allies")
    f.run(1)
    val before = f.out(ann)
    assertTrue(before.contains("Quinn"), "a same-side player is an ally: $before")
    assertTrue(!before.contains("helper"), "a member's pet is not an ally without a group: $before")
    assertTrue(!before.contains("stray"), "a non-grouped mob is not an ally: $before")
    f.w.groupAdd(quinn, ann)
    f.input(ann, "allies")
    f.run(1)
    val after = f.out(ann)
    assertTrue(after.contains("Quinn"), after)
    assertTrue(after.contains("helper"), "a member's pet is an ally once grouped: $after")
    assertTrue(!after.contains("stray"), "a non-grouped mob is still not an ally: $after")
  }
}
