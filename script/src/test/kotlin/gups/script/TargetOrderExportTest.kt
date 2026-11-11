package gups.script

import gups.core.AttrValue
import gups.core.Label
import gups.core.world.Aggressive
import gups.core.world.startCombat
import gups.script.testing.TestWorlds
import gups.script.testing.tempWorld
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TargetOrderExportTest {
  private fun fixture(
    zone: String,
    targeting: String? = null,
    moduleExtra: String = "",
  ): ScriptFixture =
    ScriptFixture(
      buildMap {
        if (moduleExtra.isNotEmpty()) {
          put("MODULE.gups", TestWorlds.module(moduleExtra))
        }
        put("zones/z/package.star", zonePkg())
        put("zones/z/a.star", zone)
        put("rules/package.star", TestWorlds.RULES_PACKAGE)
        put("rules/combat.star", TestWorlds.rules())
        if (targeting != null) {
          put("rules/targeting.star", targeting)
        }
      }
    )

  private fun load(code: String): LoadResult =
    ScriptLoader(
        WorldDir(
          tempWorld(
            mapOf(
              "zones/z/package.star" to zonePkg(),
              "zones/z/content.star" to "room(\"start\")\n${code.trimIndent()}",
            )
          )
        ),
        ScriptLimits.HARNESS,
      )
      .load(listOf("zones/z"))

  @Test
  fun `ctx fields have documented values with standing on and off`() {
    val zone =
      """
      faction("orcs", standing = {":humans": -40})
      faction("humans")

      mob("orc", name = "an orc", keywords = ["orc"], factions = [":orcs"], vitals = vitals(hp = 100),
          behaviors = [aggressive(prefer = {"mode": "last", "tags": ["a", "b"], "ok": True, "n": 3})])
      mob("goblin", name = "a goblin", keywords = ["goblin"], vitals = vitals(hp = 100))
      mob("helper", name = "a helper", keywords = ["helper"], vitals = vitals(hp = 100),
          behaviors = [assist(allies = "goblin", prefer = "focus")])
      room("start", name = "Start", desc = "Start.", flags = ["dead_end_ok"])
      """
        .trimIndent()
    val rules =
      """
      def factions(ctx):
          if ctx.actor.is_player:
              return ["//zones/z:humans"]
          return ["//zones/z:orcs"]

      def target_order(ctx):
          _ = ctx.rng.chance(100)
          if ctx.room.label not in ["//zones/z:start", "//engine:dry_run"]:
              fail("wrong room: %s" % ctx.room.label)
          if ctx.pulse < 0:
              fail("bad pulse")
          if ctx.behavior == "aggressive":
              if ctx.prefer != "dry_run" and (ctx.prefer["mode"] != "last" or ctx.prefer["n"] != 3 or ctx.prefer["ok"] != True):
                  fail("bad prefer: %s" % ctx.prefer)
              if ctx.current != None or ctx.current_since != None or ctx.ally != None:
                  fail("unexpected current/ally in aggressive")
              if ctx.prefer != "dry_run" and ctx.dispositions != [-40, -40]:
                  fail("unexpected dispositions: %s" % ctx.dispositions)
              return ctx.candidates[-1]
          if ctx.behavior == "assist":
              if ctx.prefer != "focus":
                  fail("bad assist prefer")
              if ctx.ally == None or ctx.ally.name != "a goblin":
                  fail("bad ally")
              if ctx.current == None or ctx.current_since != None:
                  fail("bad current/current_since: %s / %s" % (ctx.current, ctx.current_since))
              return ctx.candidates[0]
          return None
      """
        .trimIndent()

    val f = fixture(zone, rules)
    val p1 = f.player("Ann")
    val p2 = f.player("Bob")
    val orc = f.w.spawnMob(Label("//zones/z:orc"), p1.room)
    orc.proto.behaviors.single().propose(f.w, orc)!!.action!!.resolve(f.w, orc)
    assertTrue(f.diagnostics("script_error").isEmpty(), "${f.diagnostics("script_error")}")
    assertEquals(p2.id, orc.primaryTarget)

    val goblin = f.w.spawnMob(Label("//zones/z:goblin"), p1.room)
    val helper = f.w.spawnMob(Label("//zones/z:helper"), p1.room)
    f.w.runPulses(9)
    f.w.startCombat(p2, goblin)
    f.w.startCombat(p1, goblin)
    helper.proto.behaviors.single().propose(f.w, helper)!!.action!!.resolve(f.w, helper)
    assertTrue(f.diagnostics("script_error").isEmpty(), "${f.diagnostics("script_error")}")
    assertEquals(p1.id, helper.primaryTarget)

    // Without Feature.STANDING, ctx.dispositions is None.
    val zoneNoStanding =
      """
      mob("orc", name = "an orc", keywords = ["orc"], vitals = vitals(hp = 100),
          behaviors = [aggressive(prefer = "check_none")])
      room("start", name = "Start", desc = "Start.", flags = ["dead_end_ok"])
      """
        .trimIndent()
    val rulesNoStanding =
      """
      def target_order(ctx):
          if ctx.dispositions != None:
              fail("expected None dispositions when standing is off, got %s" % ctx.dispositions)
          return ctx.candidates[-1]
      """
        .trimIndent()
    val fNoStanding =
      fixture(
        zoneNoStanding,
        rulesNoStanding,
        moduleExtra = "features = [\"combat\", \"skills\", \"track\", \"leveling\"]",
      )
    val np1 = fNoStanding.player("Ann")
    val np2 = fNoStanding.player("Bob")
    val orc2 = fNoStanding.w.spawnMob(Label("//zones/z:orc"), np1.room)
    orc2.proto.behaviors
      .single()
      .propose(fNoStanding.w, orc2)!!
      .action!!
      .resolve(fNoStanding.w, orc2)
    assertTrue(fNoStanding.diagnostics("script_error").isEmpty())
    assertEquals(np2.id, orc2.primaryTarget)
    assertTrue(np1 !== np2)
  }

  @Test
  fun `returning non-candidate or string logs rules diagnostic and falls back`() {
    val zone =
      """
      mob("bystander", name = "a bystander", keywords = ["bystander"], vitals = vitals(hp = 100))
      mob("orc_bad_actor", name = "an orc", keywords = ["orc"], vitals = vitals(hp = 100),
          behaviors = [aggressive(prefer = "bystander")])
      mob("orc_bad_type", name = "an orc", keywords = ["orc"], vitals = vitals(hp = 100),
          behaviors = [aggressive(prefer = "string")])
      room("start", name = "Start", desc = "Start.", flags = ["dead_end_ok"])
      """
        .trimIndent()
    val rules =
      """
      def target_order(ctx):
          if ctx.prefer == "bystander":
              for a in ctx.room.actors():
                  if a.name == "a bystander":
                      return a
          if ctx.prefer == "string":
              return "not_an_actor"
          return ctx.candidates[0]
      """
        .trimIndent()
    val f = fixture(zone, rules)
    val p1 = f.player("Ann")
    val p2 = f.player("Bob")
    f.w.spawnMob(Label("//zones/z:bystander"), p1.room)
    val orc1 = f.w.spawnMob(Label("//zones/z:orc_bad_actor"), p1.room)
    orc1.proto.behaviors.single().propose(f.w, orc1)!!.action!!.resolve(f.w, orc1)
    assertEquals(p1.id, orc1.primaryTarget)
    assertEquals(1, f.diagnostics("script_error").size)
    assertContains(f.diagnostics("script_error").single().message, "not in ctx.candidates")

    val orc2 = f.w.spawnMob(Label("//zones/z:orc_bad_type"), p1.room)
    orc2.proto.behaviors.single().propose(f.w, orc2)!!.action!!.resolve(f.w, orc2)
    assertEquals(p1.id, orc2.primaryTarget)
    assertEquals(2, f.diagnostics("script_error").size)
    assertTrue(p1 !== p2)
  }

  @Test
  fun `prefer round-trips nested dict and equality distinguishes values`() {
    val zone =
      """
      mob("m1", behaviors = [aggressive(prefer = {"a": 1, "b": [True, "x", {"c": 2}]})])
      mob("m2", behaviors = [aggressive(prefer = {"a": 1, "b": [True, "x", {"c": 2}]})])
      mob("m3", behaviors = [aggressive(prefer = {"a": 2, "b": [True, "x", {"c": 2}]})])
      room("start", name = "Start", desc = "Start.", flags = ["dead_end_ok"])
      """
        .trimIndent()
    val f = fixture(zone)
    val b1 = f.w.protos.mobs.getValue(Label("//zones/z:m1")).behaviors.single() as Aggressive
    val b2 = f.w.protos.mobs.getValue(Label("//zones/z:m2")).behaviors.single() as Aggressive
    val b3 = f.w.protos.mobs.getValue(Label("//zones/z:m3")).behaviors.single() as Aggressive
    assertEquals(b1, b2)
    assertNotEquals(b1, b3)
    assertEquals(
      AttrValue.M(
        mapOf(
          "a" to AttrValue.I(1),
          "b" to
            AttrValue.L(
              listOf(AttrValue.B(true), AttrValue.S("x"), AttrValue.M(mapOf("c" to AttrValue.I(2))))
            ),
        )
      ),
      b1.prefer,
    )
  }

  @Test
  fun `prefer rejects float 5-deep value and 65 values`() {
    val floatRes = load("mob(\"orc\", behaviors = [aggressive(prefer = 1.5)])")
    assertFalse(floatRes.ok)
    assertContains(floatRes.report(), "aggressive(prefer)")

    val deepRes = load("mob(\"orc\", behaviors = [aggressive(prefer = [[[[[1]]]]])])")
    assertFalse(deepRes.ok)
    assertContains(deepRes.report(), "at most 4 deep")

    val items64 = (1..64).joinToString(", ")
    val bigRes = load("mob(\"orc\", behaviors = [aggressive(prefer = [$items64])])")
    assertFalse(bigRes.ok)
    assertContains(bigRes.report(), "at most 64 values")
  }

  @Test
  fun `retarget requires prefer and passes behavior retarget and current_since`() {
    val missingPrefer = load("mob(\"orc\", behaviors = [retarget()])")
    assertFalse(missingPrefer.ok)
    assertContains(
      missingPrefer.report(),
      "retarget(prefer): required; retarget never switches without it",
    )

    val zone =
      """
      mob("orc", name = "an orc", keywords = ["orc"], vitals = vitals(hp = 100),
          behaviors = [retarget(prefer = "last")])
      room("start", name = "Start", desc = "Start.", flags = ["dead_end_ok"])
      """
        .trimIndent()
    val rules =
      """
      def target_order(ctx):
          if ctx.behavior == "retarget":
              if ctx.prefer != "last":
                  fail("bad prefer: %s" % ctx.prefer)
              if ctx.current == None or ctx.current_since != 7:
                  fail("bad current/current_since: %s / %s" % (ctx.current, ctx.current_since))
              return ctx.candidates[-1]
          return ctx.candidates[0]
      """
        .trimIndent()
    val f = fixture(zone, rules)
    val p1 = f.player("Ann")
    val p2 = f.player("Bob")
    val orc = f.w.spawnMob(Label("//zones/z:orc"), p1.room)
    f.w.runPulses(7)
    f.w.startCombat(p1, orc)
    orc.proto.behaviors.single().propose(f.w, orc)!!.action!!.resolve(f.w, orc)
    assertTrue(f.diagnostics("script_error").isEmpty(), "${f.diagnostics("script_error")}")
    assertEquals(p2.id, orc.primaryTarget)
  }
}
