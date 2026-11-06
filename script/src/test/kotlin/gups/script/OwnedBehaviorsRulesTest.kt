package gups.script

import gups.core.Label
import gups.core.Role
import gups.core.WorldControlEvent
import gups.core.world.Defend
import gups.core.world.Flee
import gups.core.world.Heel
import gups.core.world.behaviorsOf
import gups.core.world.handle
import gups.core.world.setOwner
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OwnedBehaviorsRulesTest {
  private val zone =
    """
    def _bad(ctx):
        heel()

    room("start", name = "A room", desc = "A room.", flags = ["dead_end_ok"],
         commands = {"badheel": _bad})
    mob("wolf", name = "a wolf", keywords = ["wolf"], vitals = vitals(hp = 10),
        behaviors = [wander(), flee(below = vitals(hp = 50))])
    """
      .trimIndent()

  private fun fixture(rules: String? = null): ScriptFixture =
    ScriptFixture(
      buildMap {
        put("zones/z/package.star", zonePkg())
        put("zones/z/a.star", zone)
        put("rules/package.star", TestWorlds.RULES_PACKAGE)
        put("rules/combat.star", if (rules == null) TestWorlds.rules() else TestWorlds.rules(rules))
      }
    )

  private val export =
    """
    def owned_behaviors(ctx):
        if len(ctx.behaviors) != 2 or ctx.target == None or ctx.actor.name != "a wolf":
            return []
        return [heel(priority = 70), flee(below = vitals(hp = 10))]
    """
      .trimIndent()

  @Test
  fun `a world's owned_behaviors replaces the default list`() {
    val f = fixture(export)
    val ann = f.player()
    val wolf = f.w.spawnMob(Label("//zones/z:wolf"), ann.room)
    f.w.setOwner(wolf, ann)
    assertEquals(listOf(Heel(70), Flee(mapOf("hp" to 10))), f.w.behaviorsOf(wolf))
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics("script_error").toString())
  }

  @Test
  fun `the world without the export loads and keeps the default`() {
    val f = fixture()
    val ann = f.player()
    val wolf = f.w.spawnMob(Label("//zones/z:wolf"), ann.room)
    f.w.setOwner(wolf, ann)
    assertEquals(listOf(Flee(mapOf("hp" to 50)), Heel(), Defend()), f.w.behaviorsOf(wolf))
  }

  @Test
  fun `a bad return value keeps the default and records a diagnostic`() {
    val f =
      fixture("def owned_behaviors(ctx):\n    return 5 if ctx.target.name == \"Ann\" else []\n")
    val ann = f.player()
    val wolf = f.w.spawnMob(Label("//zones/z:wolf"), ann.room)
    f.w.setOwner(wolf, ann)
    assertEquals(listOf(Flee(mapOf("hp" to 50)), Heel(), Defend()), f.w.behaviorsOf(wolf))
    assertTrue(f.diagnostics("script_error").any { "owned_behaviors" in it.message })
  }

  @Test
  fun `a list with a non-behavior is rejected`() {
    val f =
      fixture(
        "def owned_behaviors(ctx):\n    return [heel(), 3] if ctx.target.name == \"Ann\" else []\n"
      )
    val ann = f.player()
    val wolf = f.w.spawnMob(Label("//zones/z:wolf"), ann.room)
    f.w.setOwner(wolf, ann)
    assertEquals(listOf(Flee(mapOf("hp" to 50)), Heel(), Defend()), f.w.behaviorsOf(wolf))
    assertTrue(f.diagnostics("script_error").any { "elements must be behaviors" in it.message })
  }

  @Test
  fun `a behavior constructor in an ordinary hook is an error`() {
    val f = fixture()
    val ann = f.player()
    f.input(ann, "badheel")
    f.run(1)
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(
      errors.any {
        "can only be used in a package's .star files or in rules.owned_behaviors" in it
      },
      errors.toString(),
    )
  }

  @Test
  fun `the dry run fails the load on a non-list result`() {
    val files =
      mapOf(
        "MODULE.gups" to TestWorlds.module(),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules("def owned_behaviors(ctx):\n    return 5\n"),
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to zone,
      )
    val r =
      ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS)
        .load(listOf("rules", "zones/z"))
    assertTrue("rules" in r.failed, r.report())
    assertContains(r.errors.single { it.kind == "rules" }.message, "owned_behaviors")
  }

  @Test
  fun `a reload that changes the export changes an owned mob's list`() {
    val f = fixture(export)
    val ann = f.player()
    ann.role = Role.BUILDER
    val wolf = f.w.spawnMob(Label("//zones/z:wolf"), ann.room)
    f.w.setOwner(wolf, ann)
    assertEquals(Heel(70), f.w.behaviorsOf(wolf).first())
    f.write(
      "rules/combat.star",
      TestWorlds.rules("def owned_behaviors(ctx):\n    return [heel(priority = 5)]\n"),
    )
    f.w.handle(WorldControlEvent.Reload(listOf("rules")))
    assertEquals(listOf(Heel(5)), f.w.behaviorsOf(wolf))
  }
}
