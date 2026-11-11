package gups.script

import gups.core.Label
import gups.core.rules.RulesContext
import gups.core.world.Feature
import gups.core.world.Features
import gups.script.testing.TestWorlds
import gups.script.testing.tempWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ZONE: String =
  """
  room("start")
  mob("rat", vitals = vitals(hp = 100))
  skill("spark", kind = "spell", tags = ["blue"], target = "enemy", effect = damage(dice = "1d1"))
  """

class RecognizesSkillRulesTest {
  /** The template defines the export; the tests of an undefined export need rules without it. */
  private fun withoutRecognizesSkill(rules: String): String =
    rules.replace(Regex("(?m)^def recognizes_skill\\(ctx\\):\\n(?:[ \\t]+.*\\n|\\n)*"), "")

  private fun world(export: String? = null): ScriptFixture =
    ScriptFixture(
      mapOf(
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to
          if (export == null) withoutRecognizesSkill(TestWorlds.rules())
          else TestWorlds.rules(export),
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to ZONE,
      ),
      load = listOf("zones/z", "rules"),
    )

  private fun ScriptFixture.ask(): Boolean? {
    val viewer = player()
    val caster = mob("//zones/z:rat")
    val spark = w.protos.skills.getValue(Label("//zones/z:spark"))
    return w.rules.recognizesSkill(
      RulesContext(w, viewer, viewer, source = caster, skill = spark, castTarget = viewer)
    )
  }

  @Test
  fun `a world without the export gets null`() {
    assertNull(world().ask())
  }

  @Test
  fun `True and False pass through`() {
    assertEquals(true, world("def recognizes_skill(ctx):\n    return True").ask())
    assertEquals(false, world("def recognizes_skill(ctx):\n    return False").ask())
  }

  @Test
  fun `the ctx carries the onlooker, caster, skill and cast target`() {
    val f =
      world(
        """
        def recognizes_skill(ctx):
            return (ctx.actor.name == ctx.target.name and ctx.source.is_player == False and
                    ctx.skill.label == "//zones/z:spark" and "blue" in ctx.skill.tags and
                    ctx.cast_target != None and ctx.cast_target.name == ctx.actor.name)
        """
      )
    assertEquals(true, f.ask())
  }

  @Test
  fun `cast_target is None when the cast has no target`() {
    val f =
      world(
        """
        def recognizes_skill(ctx):
            return ctx.cast_target == None
        """
      )
    val viewer = f.player()
    val caster = f.mob("//zones/z:rat")
    val spark = f.w.protos.skills.getValue(Label("//zones/z:spark"))
    assertEquals(
      true,
      f.w.rules.recognizesSkill(
        RulesContext(f.w, viewer, viewer, source = caster, skill = spark, castTarget = null)
      ),
    )
  }

  @Test
  fun `a non-bool result is a script error and counts as no answer`() {
    // Valid in the load-time dry run, where the mob is the caster; wrong for a player caster.
    val f = world("def recognizes_skill(ctx):\n    return 5 if ctx.source.is_player else True")
    val viewer = f.player()
    val other = f.w.spawnPlayer("Other", Label("//zones/z:start"))
    val spark = f.w.protos.skills.getValue(Label("//zones/z:spark"))
    val answer =
      f.w.rules.recognizesSkill(RulesContext(f.w, viewer, viewer, source = other, skill = spark))
    assertNull(answer)
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(errors.any { "recognizes_skill" in it && "must be a bool" in it }, errors.toString())
  }

  @Test
  fun `the dry run calls it with a player onlooker and a mob caster`() {
    val ok =
      world(
        "def recognizes_skill(ctx):\n" +
          "    if not ctx.actor.is_player or ctx.source.is_player:\n" +
          "        fail(\"wrong dry run actors\")\n" +
          "    return True"
      )
    assertTrue(ok.result.ok, ok.result.report())

    val boom =
      ScriptFixture(
        mapOf(
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to TestWorlds.rules("def recognizes_skill(ctx):\n    fail(\"boom\")"),
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to ZONE,
        ),
        load = listOf("zones/z", "rules"),
      )
    val errors = boom.result.errors.filter { "recognizes_skill" in it.message }
    assertEquals(1, errors.size, boom.result.report())
  }

  @Test
  fun `defining it with skills off is a load error`() {
    val off = Features(Feature.entries.toSet() - Feature.SKILLS)
    val ids = off.ids.joinToString(", ") { "\"$it\"" }
    val files =
      mapOf(
        "MODULE.gups" to TestWorlds.module("features = [$ids]"),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to
          TestWorlds.rulesFor(off.ids) + "\ndef recognizes_skill(ctx):\n    return True\n",
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to "room(\"start\")",
      )
    val r = ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(listOf("rules"))
    assertTrue(
      r.errors.any {
        "defines recognizes_skill(...)" in it.message && "needs the skills feature" in it.message
      },
      r.report(),
    )
  }
}
