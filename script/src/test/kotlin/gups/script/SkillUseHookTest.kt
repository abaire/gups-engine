package gups.script

import gups.core.Label
import gups.core.model.DamageType
import gups.core.model.ItemLocation
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.damage
import gups.core.world.spawnItem
import gups.core.world.teleport
import gups.script.testing.TestWorlds
import gups.script.testing.tempWorld
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The global `on_skill_use` hook: every cast that resolves reports "fail", "resist" or "success"
 * once, with the user, the skill, the target and the landed actors, after the cast's own messages
 * and `on_cast`. Refused, interrupted and procs fire nothing.
 */
class SkillUseHookTest {
  private fun without(vararg off: Feature): Features =
    Features(
      Feature.entries.toSet() -
        off.toSet() -
        Feature.entries.filter { f -> f.requires.any { it in off } }.toSet()
    )

  /** The `id` of a feature set, for a MODULE.gups features argument. */
  private fun idsOf(features: Features): String = features.ids.joinToString(", ") { "\"$it\"" }

  /** The global hook the fixture lib registers: one log line per use, in the order it ran. */
  private val HOOK =
    """
    def _log(ctx):
        ctx.echo(ctx.actor.room, "USE:%s:%s:%s:%s:%d" % (ctx.outcome, ctx.skill.label, ctx.self.name, ctx.target.name if ctx.target else "None", len(ctx.targets)))

    global_hook("on_skill_use", _log)
    """
      .trimIndent()

  /** A world with a casting zone, the default rules and a lib registering the hook. */
  private fun fixture(
    rules: String = "",
    zone: String,
    vararg more: Pair<String, String>,
  ): ScriptFixture =
    ScriptFixture(
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(rules),
        "zones/z/a.star" to zone,
        "lib/package.star" to libPkg(),
        "lib/g.star" to HOOK,
      ) + more,
      load = listOf("zones/z", "lib"),
    )

  /** The `USE:` lines of [out], in the order they arrived. */
  private fun uses(out: String): List<String> =
    Regex("\\bUSE:[^\\s]*").findAll(out).map { it.value }.toList()

  @Test
  fun `a failed skill_check fires once with fail and no landed actors, after the failure messages`() {
    val f =
      fixture(
        """
        def skill_check(ctx):
            return 0
        """,
        """
        room("start")
        skill("zap", target = "enemy", effect = damage(dice = "1d2"))
        mob("rat", vitals = vitals(hp = 100))
        """,
      )
    val p = f.player()
    f.out(p)
    p.skills[Label("//zones/z:zap")] = 100
    f.mob("//zones/z:rat")
    f.input(p, "cast zap rat")
    f.run()
    val out = f.out(p)
    assertContains(out, "You fail to cast zap.")
    assertContains(out, "USE:fail://zones/z:zap:Ann:rat:0")
    assertEquals(
      1,
      uses(out).count { it == "USE:fail://zones/z:zap:Ann:rat:0" },
      "must fire once:\n$out",
    )
    assertTrue(
      out.indexOf("You fail to cast zap.") in 0 until out.indexOf("USE:fail"),
      "the failure messages must come before the hook:\n$out",
    )
  }

  @Test
  fun `a successful self heal fires success with the caster as the only landed actor`() {
    val f =
      fixture(
        "",
        """
        room("start")
        skill("mend", target = "self", effect = heal(dice = "2d4", vital = "hp"))
        """,
      )
    val p = f.player(hp = 10)
    f.out(p)
    p.skills[Label("//zones/z:mend")] = 100
    f.input(p, "cast mend")
    f.run()
    val out = f.out(p)
    assertContains(out, "USE:success://zones/z:mend:Ann:None:1")
    assertEquals(1, uses(out).size, "must fire once:\n$out")
  }

  @Test
  fun `an enemy cast that lands fires success with the victim, and a resisted one fires resist`() {
    val f =
      fixture(
        "",
        """
        room("start")
        skill("zap", target = "enemy", effect = damage(dice = "1d2"))
        mob("rat", vitals = vitals(hp = 100))
        """,
      )
    val p = f.player()
    f.out(p)
    p.skills[Label("//zones/z:zap")] = 100
    val rat = f.mob("//zones/z:rat")
    f.input(p, "cast zap rat")
    f.run()
    assertContains(f.out(p), "USE:success://zones/z:zap:Ann:rat:1")

    // A zero chance never lands, so the same cast is a resisted use.
    val resisted =
      fixture(
        """
        def to_hit(ctx):
            return 0
        """,
        """
        room("start")
        skill("zap", target = "enemy", effect = damage(dice = "1d2"))
        mob("rat", vitals = vitals(hp = 100))
        """,
      )
    val q = resisted.player()
    q.skills[Label("//zones/z:zap")] = 100
    val victim = resisted.mob("//zones/z:rat")
    assertEquals(100, victim.current("hp"))
    resisted.out(q)
    resisted.input(q, "cast zap rat")
    resisted.run()
    val out = resisted.out(q)
    assertContains(out, "Rat resists your zap.")
    assertContains(out, "USE:resist://zones/z:zap:Ann:rat:0")
    assertEquals(100, victim.current("hp"), "a resisted cast must not change the victim")
  }

  @Test
  fun `a room-range enemy cast fires once, success when one of two victims lands and the landed actor is the only one in targets`() {
    val f =
      fixture(
        """
        def to_hit(ctx):
            if ctx.skill:
                return 100 if ctx.defender.name == "wolf" else 0
            return 50
        """,
        """
        room("start")
        skill("blast", target = "enemy", range = "room", effect = damage(dice = "1d2"))
        mob("wolf", vitals = vitals(hp = 100))
        mob("rat", vitals = vitals(hp = 100))
        """,
      )
    val p = f.player()
    f.out(p)
    p.skills[Label("//zones/z:blast")] = 100
    val wolf = f.mob("//zones/z:wolf")
    val rat = f.mob("//zones/z:rat")
    f.input(p, "cast blast wolf")
    f.run()
    val out = f.out(p)
    assertContains(out, "USE:success://zones/z:blast:Ann:wolf:1")
    assertEquals(1, uses(out).size, "must fire once, not per victim:\n$out")
    assertTrue(wolf.current("hp") < 100, "the landing victim takes damage")
    assertEquals(100, rat.current("hp"), "the resisting victim takes none")
  }

  @Test
  fun `an item skill fires success with the item and no landed actors`() {
    val f =
      fixture(
        "",
        """
        room("start")
        item("stick", keywords = ["stick"])
        skill("touch", target = "item", effect = apply_affect(name = "marked", duration = 5, applies = "carried"))
        """,
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:stick"), ItemLocation.Carried(p.id))
    p.skills[Label("//zones/z:touch")] = 100
    f.out(p)
    f.input(p, "cast touch stick")
    f.run()
    val out = f.out(p)
    assertContains(out, "USE:success://zones/z:touch:Ann:None:0")
    assertEquals(1, uses(out).size, "must fire once:\n$out")
  }

  @Test
  fun `a cast refused by before_cast or cast_allowed fires nothing, and neither does an interrupted warmup`() {
    val veto =
      fixture(
        "",
        """
        def _veto(ctx):
            return ctx.deny("no casting allowed")

        room("start", before_cast = _veto)
        skill("zap", target = "enemy", effect = damage(dice = "1d2"))
        mob("rat", vitals = vitals(hp = 100))
        """,
      )
    val p = veto.player()
    p.skills[Label("//zones/z:zap")] = 100
    veto.mob("//zones/z:rat")
    veto.out(p)
    veto.input(p, "cast zap rat")
    veto.run()
    assertContains(veto.out(p), "no casting allowed")
    assertTrue(uses(veto.out(p)).isEmpty(), "a vetoed cast must not fire:\n${veto.out(p)}")

    val refused =
      fixture(
        """
        def cast_allowed(ctx):
            return ctx.deny("the air resists")
        """,
        """
        room("start")
        skill("zap", target = "enemy", effect = damage(dice = "1d2"))
        mob("rat", vitals = vitals(hp = 100))
        """,
      )
    val q = refused.player()
    q.skills[Label("//zones/z:zap")] = 100
    refused.mob("//zones/z:rat")
    refused.out(q)
    refused.input(q, "cast zap rat")
    refused.run()
    assertContains(refused.out(q), "the air resists")
    assertTrue(uses(refused.out(q)).isEmpty(), "a refused cast must not fire:\n${refused.out(q)}")

    // A warmup that breaks on damage never resolves, so it reports nothing.
    val broken =
      fixture(
        "",
        """
        room("start")
        skill("slow_burn", target = "enemy", warmup = 2, interrupt = {"damage_pct": 1}, effect = damage(dice = "1d2"))
        mob("rat", vitals = vitals(hp = 100))
        """,
      )
    val r = broken.player(hp = 50)
    r.skills[Label("//zones/z:slow_burn")] = 100
    val rat = broken.mob("//zones/z:rat")
    broken.out(r)
    broken.input(r, "cast slow_burn rat")
    broken.run(1)
    broken.w.damage(r, mapOf("hp" to 40), DamageType("fire"))
    broken.run()
    assertContains(broken.out(r), "Your concentration is broken!")
    assertTrue(
      uses(broken.out(r)).isEmpty(),
      "an interrupted cast must not fire:\n${broken.out(r)}",
    )
    assertEquals(100, rat.current("hp"))
  }

  @Test
  fun `a cast whose target leaves during the warmup fires nothing`() {
    val f =
      fixture(
        "",
        """
        room("start")
        room("elsewhere")
        skill("slow_burn", target = "enemy", warmup = 2, effect = damage(dice = "1d2"))
        mob("rat", vitals = vitals(hp = 100))
        """,
      )
    val p = f.player()
    p.skills[Label("//zones/z:slow_burn")] = 100
    val rat = f.mob("//zones/z:rat")
    f.out(p)
    f.input(p, "cast slow_burn rat")
    f.run(1)
    f.w.teleport(rat, f.w.room(Label("//zones/z:elsewhere")))
    f.run(16)
    val out = f.out(p)
    assertContains(out, "isn't here")
    assertTrue(uses(out).isEmpty(), "a cast that never resolves must not fire:\n$out")
  }

  @Test
  fun `a skill proc fires nothing, even when its bearer knows the skill`() {
    val f =
      fixture(
        "",
        """
        def _zap(ctx):
            ctx.echo(ctx.actor.room, "PROC-RAN")

        room("start")
        skill("zap", target = "enemy", resolve = _zap)
        item("staff", wear = "wield", dice = "1d4", procs = [proc(on = "hit", skill = ":zap")])
        mob("dummy", vitals = vitals(hp = 1000))
        """,
      )
    val p = f.player()
    p.skills[Label("//zones/z:zap")] = 100
    f.mob("//zones/z:dummy")
    f.w.spawnItem(Label("//zones/z:staff"), ItemLocation.Equipped(p.id, "wield"))
    f.out(p)
    f.input(p, "kill dummy")
    f.run(16)
    val out = f.out(p)
    assertContains(out, "PROC-RAN")
    assertTrue(uses(out).isEmpty(), "a proc must not fire on_skill_use:\n$out")
  }

  @Test
  fun `a mob's act_cast fires with the mob as the actor`() {
    val f =
      fixture(
        "",
        """
        def _cast(ctx):
            ctx.act_cast(ctx.self, ":zap", ctx.actor)

        room("start")
        skill("zap", target = "enemy", effect = damage(dice = "1d2"))
        mob("caster", skills = {":zap": 100}, on_say = _cast)
        """,
      )
    val p = f.player(hp = 50)
    f.mob("//zones/z:caster")
    f.out(p)
    f.input(p, "say anything")
    f.run()
    assertContains(f.out(p), "USE:success://zones/z:zap:caster:Ann:1")
  }

  @Test
  fun `the global hook runs after on_cast`() {
    val f =
      fixture(
        "",
        """
        def _observer(ctx):
            ctx.send(ctx.actor, "MOB_ON_CAST")

        room("start")
        skill("zap", target = "enemy", effect = damage(dice = "1d2"))
        mob("rat", on_cast = _observer, vitals = vitals(hp = 100))
        """,
      )
    val p = f.player()
    p.skills[Label("//zones/z:zap")] = 100
    f.mob("//zones/z:rat")
    f.out(p)
    f.input(p, "cast zap rat")
    f.run()
    val out = f.out(p)
    assertTrue(
      out.indexOf("MOB_ON_CAST") in 0 until out.indexOf("USE:success"),
      "the global hook must run after on_cast:\n$out",
    )
  }

  @Test
  fun `a zone declaring on_skill_use fails to load, and a mob can't attach the hook`() {
    val r =
      ScriptLoader(
          WorldDir(
            tempWorld(
              mapOf(
                "zones/z/package.star" to zonePkg(),
                "zones/z/a.star" to
                  """
                  def f(ctx):
                      pass

                  global_hook("on_skill_use", f)
                  """,
              )
            )
          ),
          ScriptLimits.HARNESS,
        )
        .load(listOf("zones/z"))
    assertContains(
      r.errors.filter { it.pkg == "zones/z" }.first().message,
      "only lib and rules packages may register global hooks",
      message = "all errors: ${r.errors.map { it.pkg to it.message }}",
    )

    val attach =
      ScriptLoader(
          WorldDir(
            tempWorld(
              mapOf(
                "zones/z/package.star" to zonePkg(),
                "zones/z/a.star" to
                  """
                  def f(ctx):
                      pass

                  mob("m", on_skill_use = f)
                  """,
              )
            )
          ),
          ScriptLimits.HARNESS,
        )
        .load(listOf("zones/z"))
    assertContains(
      attach.errors.filter { it.pkg == "zones/z" }.first().message,
      "hook 'on_skill_use' is global; register it with global_hook()",
      message = "all errors: ${attach.errors.map { it.pkg to it.message }}",
    )
  }

  @Test
  fun `without skills the declaration loads and nothing fires`() {
    val features = without(Feature.SKILLS)
    val f =
      ScriptFixture(
        mapOf(
          "MODULE.gups" to TestWorlds.module("features = [${idsOf(features)}]"),
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to TestWorlds.rulesFor(features.ids),
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "room(\"start\")",
          "lib/package.star" to libPkg(),
          "lib/g.star" to
            """
            def _use(ctx):
                ctx.send(ctx.actor, "FIRED")

            global_hook("on_skill_use", _use)
            """,
        ),
        load = listOf("zones/z", "lib"),
      )
    val p = f.player()
    f.out(p)
    f.run(2)
    assertFalse("FIRED" in f.out(p), "the hook must not fire without skills")
    assertTrue(f.diagnostics("script_error").isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `without leveling the hook fires, set_skill raises the skill, and reading level names the feature`() {
    val features = without(Feature.LEVELING)
    val f =
      ScriptFixture(
        mapOf(
          "MODULE.gups" to TestWorlds.module("features = [${idsOf(features)}]"),
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to TestWorlds.rulesFor(features.ids),
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            room("start")
            skill("mend", target = "self", effect = heal(dice = "2d4", vital = "hp"))
            """,
          "lib/package.star" to libPkg(),
          "lib/g.star" to
            """
            def _use(ctx):
                label = ctx.skill.label
                ctx.set_skill(ctx.actor, label, ctx.actor.skills[label] + 1)
                ctx.send(ctx.actor, "GAINED:%d" % ctx.actor.skills[label])
                ctx.send(ctx.actor, "LEVEL:%d" % ctx.actor.level)

            global_hook("on_skill_use", _use)
            """,
        ),
        load = listOf("zones/z", "lib"),
      )
    val p = f.player()
    p.skills[Label("//zones/z:mend")] = 1
    f.out(p)
    f.input(p, "cast mend")
    f.run()
    assertContains(f.out(p), "GAINED:2")
    assertEquals(2, p.skills[Label("//zones/z:mend")], "the hook's set_skill must raise the skill")
    assertTrue(
      f.diagnostics("script_error").any { "leveling feature" in it.message },
      "reading level without leveling must name the feature:\n${f.diagnostics()}",
    )
  }

  // ---- ctx.report_skill_use ------------------------------------------------------------------

  /** A chip that reports its bearer's use of a skill, however the skill is named. */
  private fun reportZone(report: String) =
    """
    def _report(ctx):
        $report

    room("start")
    skill("zap", target = "enemy", effect = damage(dice = "1d2"))
    item("chip", on_use = _report)
    """

  @Test
  fun `an item on_use report fires report with the item and no landed actors, even if the actor doesn't know the skill`() {
    val f = fixture("", reportZone("ctx.report_skill_use(ctx.actor, \":zap\", item = ctx.item)"))
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:chip"), ItemLocation.Carried(p.id))
    f.out(p)
    f.input(p, "use chip")
    f.run()
    val out = f.out(p)
    assertContains(out, "USE:report://zones/z:zap:Ann:None:0")
    assertEquals(1, uses(out).size, "must fire once:\n$out")
  }

  @Test
  fun `a skill view is accepted by report_skill_use`() {
    val f =
      fixture(
        "",
        reportZone("ctx.report_skill_use(ctx.actor, ctx.skill_info(\":zap\"), item = ctx.item)"),
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:chip"), ItemLocation.Carried(p.id))
    f.out(p)
    f.input(p, "use chip")
    f.run()
    assertContains(f.out(p), "USE:report://zones/z:zap:Ann:None:0")
  }

  @Test
  fun `an unknown skill label is a script error naming report_skill_use(skill)`() {
    val f =
      fixture(
        "",
        """
        def _report(ctx):
            ctx.report_skill_use(ctx.actor, ":nope")

        room("start", commands = {"rep": _report})
        skill("zap", target = "enemy", effect = damage(dice = "1d2"))
        """,
      )
    val p = f.player()
    f.out(p)
    f.input(p, "rep")
    f.run()
    assertTrue(
      f.diagnostics("script_error").any {
        "report_skill_use(skill)" in it.message && "unknown skill" in it.message
      },
      "no error naming the unknown skill:\n${f.diagnostics()}",
    )
  }

  @Test
  fun `without skills report_skill_use is an error naming the feature`() {
    val features = without(Feature.SKILLS)
    val f =
      ScriptFixture(
        mapOf(
          "MODULE.gups" to TestWorlds.module("features = [${idsOf(features)}]"),
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to TestWorlds.rulesFor(features.ids),
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            def _report(ctx):
                ctx.report_skill_use(ctx.self, ":zap")

            room("start", commands = {"rep": _report})
            """,
        ),
        load = listOf("zones/z"),
      )
    val p = f.player()
    f.out(p)
    f.input(p, "rep")
    f.run()
    assertTrue(
      f.diagnostics("script_error").any { "skills" in it.message },
      "the error must name the skills feature:\n${f.diagnostics()}",
    )
  }

  @Test
  fun `a hook that reports from inside on_skill_use stops at the cascade limit with a diagnostic`() {
    val f =
      fixture(
        "",
        """
        room("start")
        skill("zap", target = "self", effect = heal(dice = "1d2", vital = "hp"))
        """,
        "lib/g.star" to
          """
          def _loop(ctx):
              ctx.report_skill_use(ctx.actor, ctx.skill.label)

          global_hook("on_skill_use", _loop)
          """,
      )
    val p = f.player()
    p.skills[Label("//zones/z:zap")] = 100
    f.out(p)
    f.input(p, "cast zap")
    f.run()
    assertTrue(p.inWorld, "the actor must survive the cascade")
    assertTrue(
      f.diagnostics("cascade").any { "on_skill_use" in it.message },
      "expected a cascade diagnostic:\n${f.diagnostics()}",
    )
  }
}
