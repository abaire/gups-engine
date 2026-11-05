package gups.script

import gups.core.Label
import gups.core.rules.RulesContext
import gups.core.world.toHitChain
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpellResolutionTest {
  private val rulesPkg = "rules/package.star" to TestWorlds.RULES_PACKAGE

  private fun world(combat: String, rooms: String, vararg more: Pair<String, String>) =
    ScriptFixture(
      mapOf(
        rulesPkg,
        "rules/combat.star" to TestWorlds.rules(combat),
        "zones/z/package.star" to zonePkg(),
        "zones/z/rooms.star" to rooms,
      ) + more,
      load = listOf("zones/z", "rules"),
    )

  @Test
  fun `skill_check returning 0 fails cast early with cost and lag paid but no effects or hooks`() {
    val f =
      world(
        """
        def skill_check(ctx):
            return 0
        def skill_power(ctx):
            return 100
        """
          .trimIndent(),
        """
        def _on_cast(ctx):
            ctx.send(ctx.actor, "HOOK_ON_CAST_RAN")

        def _resolve(ctx):
            ctx.send(ctx.actor, "RESOLVER_RAN")

        room("start", on_cast = _on_cast)
        skill("spark", target = "enemy", cost = vitals(mana = 5), lag = 3, effect = damage(dice = "10d1"), resolve = _resolve)
        mob("rat", vitals = vitals(hp = 100))
        """
          .trimIndent(),
      )
    val p = f.player()
    val initialMana = p.current("mana")
    val bystander = f.player("Bob")
    val rat = f.mob("//zones/z:rat")
    p.skills[Label("//zones/z:spark")] = 100

    f.input(p, "cast spark rat")
    f.run()

    assertEquals(initialMana - 5, p.current("mana"), "cost must be paid")
    assertEquals(100, rat.current("hp"), "target hp must not change")
    assertTrue(p.fighting.isEmpty(), "combat must not start")
    assertTrue(rat.fighting.isEmpty(), "combat must not start")

    val pOut = f.out(p)
    assertContains(pOut, "You fail to cast spark.")
    assertFalse("HOOK_ON_CAST_RAN" in pOut, "on_cast must not fire")
    assertFalse("RESOLVER_RAN" in pOut, "resolve must not run")

    val bOut = f.out(bystander)
    assertContains(bOut, "Ann's spell fizzles.")
  }

  @Test
  fun `skill_power scales damage and resolve sees ctx power`() {
    val f =
      world(
        """
        def skill_check(ctx):
            return 100
        def skill_power(ctx):
            return 150
        def mitigate(ctx, amount, type):
            return vitals(hp = amount)
        """
          .trimIndent(),
        """
        def _resolve(ctx):
            ctx.send(ctx.actor, "POWER:" + str(ctx.power))

        room("start")
        skill("blast", target = "enemy", effect = damage(dice = "10d1"), resolve = _resolve)
        mob("rat", vitals = vitals(hp = 100))
        """
          .trimIndent(),
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    p.skills[Label("//zones/z:blast")] = 100

    f.input(p, "cast blast rat")
    f.run()

    // 10 damage rolled at 150% power is 15 damage
    assertEquals(85, rat.current("hp"))
    val pOut = f.out(p)
    assertContains(pOut, "POWER:150")
  }

  @Test
  fun `apply_affect skill at power 200 lasts twice as long and affect power is 200`() {
    val f =
      world(
        """
        def skill_check(ctx):
            return 100
        def skill_power(ctx):
            return 200
        """
          .trimIndent(),
        """
        def _boost_check(ctx, base):
            return base - ctx.affect.power // 10

        room("start")
        skill("levitate", target = "self", effect = apply_affect(name = "float", duration = 10, modifiers = {"defend_hit": _boost_check}))
        mob("rat", vitals = vitals(hp = 100))
        """
          .trimIndent(),
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    p.skills[Label("//zones/z:levitate")] = 100

    f.input(p, "cast levitate")
    f.run()

    val aff = p.affects.single { it.name == "float" }
    assertEquals(20L, aff.expiresAt - aff.appliedAt, "duration 10 at 200% power should be 20")
    assertEquals(200, aff.power, "affect.power should be 200")

    // Check modifier reading ctx.affect.power on defender's chain: default to_hit is 60, defend_hit
    // subtracts 200 // 10 = 20 -> 40
    val c = RulesContext(f.w, rat, p)
    val toHitResult = f.w.toHitChain(c)
    assertEquals(60 - 20, toHitResult.value)
  }

  @Test
  fun `resolve summoning a mob using ctx summon`() {
    val f =
      world(
        """
        def skill_check(ctx):
            return 100
        def skill_power(ctx):
            return 100
        """
          .trimIndent(),
        """
        def _summon_resolve(ctx):
            ctx.summon(ctx.actor, ":wolf", count = 1)

        room("start")
        skill("call_wolf", target = "self", resolve = _summon_resolve)
        mob("wolf")
        """
          .trimIndent(),
      )
    val p = f.player()
    p.skills[Label("//zones/z:call_wolf")] = 100

    f.input(p, "cast call_wolf")
    f.run()

    val wolves =
      f.w.actors.filterIsInstance<gups.core.model.Mob>().filter {
        it.proto.label == Label("//zones/z:wolf")
      }
    assertEquals(1, wolves.size)
    assertEquals(p.id, wolves.single().summonedBy)
  }

  @Test
  fun `mob act_cast with failing skill_check fails similarly`() {
    val f =
      world(
        """
        def skill_check(ctx):
            return 0
        def skill_power(ctx):
            return 100
        """
          .trimIndent(),
        """
        def _mob_cast(ctx):
            ctx.act_cast(ctx.self, ":curse", ctx.actor)

        room("start")
        skill("curse", target = "enemy", effect = damage(dice = "5d1"))
        mob("caster", skills = {":curse": 100}, on_say = _mob_cast)
        """
          .trimIndent(),
      )
    val p = f.player(hp = 50)
    f.mob("//zones/z:caster")

    f.input(p, "say cast")
    f.run()
    // Mob's cast should have failed
    assertEquals(50, p.current("hp"))
    assertContains(f.out(p), "Caster's spell fizzles.")
  }

  @Test
  fun `rules trace shows skill_check FAIL and skill_power`() {
    val traces = mutableListOf<String>()
    val f =
      world(
        """
        def skill_check(ctx):
            return 0
        def skill_power(ctx):
            return 100
        """
          .trimIndent(),
        """
        room("start")
        skill("spark", target = "self")
        """
          .trimIndent(),
      )
    f.w.onRules = { _, line -> traces += line }
    val p = f.player()
    p.skills[Label("//zones/z:spark")] = 100

    f.input(p, "cast spark")
    f.run()

    assertTrue(traces.any { "rules.skill_check" in it && "FAIL" in it }, traces.toString())
  }
}
