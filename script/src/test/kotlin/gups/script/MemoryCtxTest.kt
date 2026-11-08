package gups.script

import gups.core.model.Channel
import gups.core.rules.RulesContext
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.TargetChoice
import gups.core.world.forget
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryCtxTest {
  private val zone =
    """
    skill("zap")
    mob("guard", name = "a guard", keywords = ["guard"], vitals = vitals(hp = 50))
    mob("goldfish", name = "a goldfish", keywords = ["goldfish"], memory_pulses = 0)

    def _roundtrip(ctx):
        guard = ctx.here("guard")
        ctx.remember(
            mob = guard,
            subject = ctx.actor,
            event = "custom_hit",
            skill = ":zap",
            amount = 12,
            target = guard,
        )
        ctx.remember(
            mob = guard,
            subject = ctx.actor,
            event = "custom_hit",
            skill = ":zap",
            amount = 8,
            target = guard,
        )
        m = ctx.memories(guard)[0]
        ctx.send(
            ctx.actor,
            "sub=%s ev=%s sk=%s tgt=%s fp=%d p=%d cnt=%d amt=%d ch=%s" % (
                m.subject.name, m.event, m.skill, m.target.name,
                m.first_pulse, m.pulse, m.count, m.amount, str(m.channel),
            ),
        )

    def _filters_and_order(ctx):
        guard = ctx.here("guard")
        bob = ctx.here("Bob")
        if ctx.args == "step1":
            ctx.remember(guard, ctx.actor, "taunt", amount = 1)
        elif ctx.args == "step2":
            ctx.remember(guard, bob, "wave", amount = 2)
        elif ctx.args == "query":
            all_m = ctx.memories(guard)
            by_sub = ctx.memories(guard, subject = ctx.actor)
            by_ev = ctx.memories(guard, event = "wave")
            by_since = ctx.memories(guard, since = all_m[0].pulse)
            ctx.send(
                ctx.actor,
                "order=%s sub=%d ev=%d since=%d" % (
                    ",".join([x.event for x in all_m]),
                    len(by_sub),
                    len(by_ev),
                    len(by_since),
                ),
            )

    def _departed(ctx):
        guard = ctx.here("guard")
        if ctx.args == "rec":
            bob = ctx.here("Bob")
            ctx.remember(guard, bob, "spotted")
        else:
            m = ctx.memories(guard, event = "spotted")[0]
            ctx.send(ctx.actor, "sub=%s" % str(m.subject))

    def _window_zero(ctx):
        fish = ctx.here("goldfish")
        ctx.remember(fish, ctx.actor, "splash")
        ctx.send(ctx.actor, "fish_mem=%d" % len(ctx.memories(fish)))

    def _forget_test(ctx):
        guard = ctx.here("guard")
        bob = ctx.here("Bob")
        ctx.forget(guard)
        ctx.remember(guard, ctx.actor, "taunt")
        ctx.remember(guard, ctx.actor, "wave")
        ctx.remember(guard, bob, "taunt")
        n_sub = ctx.forget(guard, subject = ctx.actor)
        rem1 = len(ctx.memories(guard))
        ctx.remember(guard, ctx.actor, "taunt")
        ctx.remember(guard, bob, "wave")
        n_ev = ctx.forget(guard, event = "taunt")
        rem2 = len(ctx.memories(guard))
        n_all = ctx.forget(guard)
        rem3 = len(ctx.memories(guard))
        ctx.send(
            ctx.actor,
            "n_sub=%d rem1=%d n_ev=%d rem2=%d n_all=%d rem3=%d" % (
                n_sub, rem1, n_ev, rem2, n_all, rem3,
            ),
        )

    def _bad_event(ctx):
        ctx.remember(ctx.here("guard"), ctx.actor, "Bad-Event")

    def _bad_amount(ctx):
        ctx.remember(ctx.here("guard"), ctx.actor, "taunt", amount = -1)

    def _bad_skill(ctx):
        ctx.remember(ctx.here("guard"), ctx.actor, "taunt", skill = ":no_such_skill")

    def _bad_mob_remember(ctx):
        ctx.remember(ctx.actor, ctx.actor, "taunt")

    def _bad_mob_memories(ctx):
        ctx.memories(ctx.actor)

    def _bad_mob_forget(ctx):
        ctx.forget(ctx.actor)

    def _grudge(ctx):
        ctx.remember(ctx.here("guard"), ctx.here("Bob"), "grudge", amount = 50)

    room("start", name = "Start", desc = "Start.", flags = ["dead_end_ok"],
         commands = {
             "roundtrip": _roundtrip,
             "filter": _filters_and_order,
             "departed": _departed,
             "winzero": _window_zero,
             "forget_test": _forget_test,
             "bad_event": _bad_event,
             "bad_amount": _bad_amount,
             "bad_skill": _bad_skill,
             "bad_mob_remember": _bad_mob_remember,
             "bad_mob_memories": _bad_mob_memories,
             "bad_mob_forget": _bad_mob_forget,
             "grudge": _grudge,
         })
    """
      .trimIndent()

  private fun fixture(
    rulesExtra: String = "",
    module: String = TestWorlds.module("memory_pulses = 600"),
    zoneCode: String = zone,
  ): ScriptFixture =
    ScriptFixture(
      buildMap {
        put("MODULE.gups", module)
        put("zones/z/package.star", zonePkg())
        put("zones/z/a.star", zoneCode)
        put("rules/package.star", TestWorlds.RULES_PACKAGE)
        put("rules/combat.star", TestWorlds.rules(rulesExtra))
      }
    )

  @Test
  fun `remember then memories round-trips every field`() {
    val f = fixture()
    val alice = f.player("Alice")
    f.mob("//zones/z:guard")
    f.run(1)

    f.input(alice, "roundtrip")
    f.run(1)
    assertContains(
      f.out(alice),
      "sub=Alice ev=custom_hit sk=//zones/z:zap tgt=a guard fp=2 p=2 cnt=2 amt=20 ch=None",
    )
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `filters and newest-first order work`() {
    val f = fixture()
    val alice = f.player("Alice")
    f.player("Bob")
    val guard = f.mob("//zones/z:guard")
    f.run(1)
    f.w.forget(guard)

    f.input(alice, "filter step1")
    f.run(1)
    f.input(alice, "filter step2")
    f.run(1)
    f.input(alice, "filter query")
    f.run(1)
    assertContains(f.out(alice), "order=wave,taunt sub=1 ev=1 since=1")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `departed subject shows as None`() {
    val f = fixture()
    val alice = f.player("Alice")
    val bob = f.player("Bob")
    val guard = f.mob("//zones/z:guard")
    f.run(1)
    f.w.forget(guard)

    f.input(alice, "departed rec")
    f.run(1)
    f.input(bob, "quit")
    f.run(1)

    f.input(alice, "departed check")
    f.run(1)
    assertContains(f.out(alice), "sub=None")
  }

  @Test
  fun `remember on a mob with window 0 is a no-op`() {
    val f = fixture()
    val alice = f.player("Alice")
    f.mob("//zones/z:goldfish")
    f.run(1)

    f.input(alice, "winzero")
    f.run(1)
    assertContains(f.out(alice), "fish_mem=0")
  }

  @Test
  fun `forget filters by subject, event, or clears all`() {
    val f = fixture()
    val alice = f.player("Alice")
    f.player("Bob")
    f.mob("//zones/z:guard")
    f.run(1)

    f.input(alice, "forget_test")
    f.run(1)
    assertContains(f.out(alice), "n_sub=2 rem1=1 n_ev=2 rem2=1 n_all=1 rem3=0")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `bad event, negative amount, unknown skill, player mob, and skill without skills feature fail`() {
    val f = fixture()
    val alice = f.player("Alice")
    f.mob("//zones/z:guard")
    f.run(1)

    for (cmd in
      listOf(
        "bad_event",
        "bad_amount",
        "bad_skill",
        "bad_mob_remember",
        "bad_mob_memories",
        "bad_mob_forget",
      )) {
      f.input(alice, cmd)
      f.run(1)
    }
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(
      errors.any { "remember(event): must match [a-z][a-z0-9_]*" in it },
      errors.toString(),
    )
    assertTrue(errors.any { "remember(amount): must be non-negative" in it }, errors.toString())
    assertTrue(errors.any { "remember(skill): unknown skill" in it }, errors.toString())
    assertTrue(
      errors.any { "remember(mob): mob actions apply only to mobs" in it },
      errors.toString(),
    )
    assertTrue(
      errors.any { "memories(mob): mob actions apply only to mobs" in it },
      errors.toString(),
    )
    assertTrue(
      errors.any { "forget(mob): mob actions apply only to mobs" in it },
      errors.toString(),
    )

    // Passing skill when skills feature is off is an error
    val noSkills = Features(Feature.entries.toSet() - Feature.SKILLS)
    val fNoSkills =
      ScriptFixture(
        mapOf(
          "MODULE.gups" to
            TestWorlds.module(
              "features = [${noSkills.ids.joinToString(", ") { "\"$it\"" }}], memory_pulses = 600"
            ),
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to TestWorlds.rulesFor(noSkills.ids),
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            mob("guard", name = "a guard", keywords = ["guard"])
            def _try_skill(ctx):
                ctx.remember(ctx.here("guard"), ctx.actor, "cast", skill = ":zap")
            room("start", commands = {"try_skill": _try_skill})
            """
              .trimIndent(),
        )
      )
    val p = fNoSkills.player("Alice")
    fNoSkills.mob("//zones/z:guard")
    fNoSkills.input(p, "try_skill")
    fNoSkills.run(1)
    val noSkillErrors = fNoSkills.diagnostics("script_error").map { it.message }
    assertTrue(
      noSkillErrors.any { "remember(skill) needs the skills feature" in it },
      noSkillErrors.toString(),
    )
  }

  @Test
  fun `rules ctx memories sees memories in target_order and returns empty list for player in can_perceive`() {
    val rulesExtra =
      """
      def target_order(ctx):
          by_id = {c.id: c for c in ctx.candidates}
          scored = []
          for c in ctx.candidates:
              mems = ctx.memories(subject = c, event = "grudge")
              amt = mems[0].amount if len(mems) > 0 else 0
              scored.append((-amt, c.id))
          return by_id[sorted(scored)[0][1]]

      def can_perceive(ctx):
          if ctx.actor.is_player and len(ctx.memories()) != 0:
              return False
          return True
      """
        .trimIndent()
    val f2 = fixture(rulesExtra = rulesExtra)
    val a2 = f2.player("Alice")
    val b2 = f2.player("Bob")
    val g2 = f2.mob("//zones/z:guard")
    f2.run(1)
    f2.input(a2, "grudge")
    f2.run(1)

    val ordered =
      f2.w.rules.targetOrder(
        RulesContext(world = f2.w, actor = g2, behavior = "aggressive", candidates = listOf(a2, b2))
      )
    assertEquals<TargetChoice>(TargetChoice.Pick(b2), ordered)

    val canPerceive =
      f2.w.rules.canPerceive(
        RulesContext(
          world = f2.w,
          actor = a2,
          room = a2.room,
          sourceRoom = a2.room,
          channel = Channel.SOUND,
          event = "say",
          intensity = 5,
          cost = 0,
        )
      )
    assertTrue(canPerceive)
    assertTrue(f2.diagnostics().isEmpty(), f2.diagnostics().toString())
  }

  @Test
  fun `without combat ctx memories remember forget and rules_ctx memories are refused`() {
    val noCombat =
      Features(
        Feature.entries.toSet() -
          Feature.COMBAT -
          Feature.entries.filter { Feature.COMBAT in it.requires }.toSet()
      )
    val f =
      ScriptFixture(
        mapOf(
          "MODULE.gups" to
            TestWorlds.module("features = [${noCombat.ids.joinToString(", ") { "\"$it\"" }}]"),
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to TestWorlds.rulesFor(noCombat.ids),
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            mob("guard", name = "a guard", keywords = ["guard"])
            def _m(ctx):
                ctx.memories(ctx.here("guard"))
            def _r(ctx):
                ctx.remember(ctx.here("guard"), ctx.actor, "taunt")
            def _f(ctx):
                ctx.forget(ctx.here("guard"))
            room("start", commands = {"m": _m, "r": _r, "f": _f})
            """
              .trimIndent(),
        )
      )
    val p = f.player("Alice")
    f.mob("//zones/z:guard")
    for (cmd in listOf("m", "r", "f")) {
      f.input(p, cmd)
      f.run(1)
    }
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(errors.any { "ctx.memories() needs the combat feature" in it }, errors.toString())
    assertTrue(errors.any { "ctx.remember() needs the combat feature" in it }, errors.toString())
    assertTrue(errors.any { "ctx.forget() needs the combat feature" in it }, errors.toString())

    // Also verify rules_ctx.memories is refused without combat during dry-run
    val fBadRules =
      ScriptFixture(
        mapOf(
          "MODULE.gups" to
            TestWorlds.module("features = [${noCombat.ids.joinToString(", ") { "\"$it\"" }}]"),
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to
            TestWorlds.rulesFor(
              noCombat.ids,
              """
              def can_perceive(ctx):
                  _ = ctx.memories()
                  return True
              """
                .trimIndent(),
            ),
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "room(\"start\")",
        )
      )
    assertTrue(
      fBadRules.result.errors.any { "ctx.memories() needs the combat feature" in it.message },
      fBadRules.result.report(),
    )
  }
}
