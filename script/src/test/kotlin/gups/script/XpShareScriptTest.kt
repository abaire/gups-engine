package gups.script

import gups.core.world.startCombat
import gups.script.testing.TestWorlds
import gups.script.testing.tempWorld
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XpShareScriptTest {
  private fun fixture(vararg files: Pair<String, String>): ScriptFixture =
    ScriptFixture(
      mapOf("zones/z/package.star" to zonePkg(), "rules/package.star" to TestWorlds.RULES_PACKAGE) +
        files
    )

  private val zone =
    """
    room("start", name = "A room", desc = "A room.", flags = ["dead_end_ok"])
    mob("victim", vitals = vitals(hp = 10))
    """
      .trimIndent()

  private val combat =
    """
    def to_hit(ctx):
        return 100

    def damage(ctx):
        return 10

    def xp_for_kill(ctx):
        return 1000
    """
      .trimIndent()

  @Test
  fun `xp_share pays the killer from a dict keyed by the actor`() {
    val rules = "$combat\n\ndef xp_share(ctx):\n    return {ctx.actor: 700}\n"
    val f = fixture("zones/z/a.star" to zone, "rules/combat.star" to TestWorlds.rules(rules))
    val p = f.player("Ann")
    val victim = f.mob("//zones/z:victim")
    f.w.startCombat(p, victim)
    f.run(60)
    assertFalse(victim.inWorld, "the victim died")
    assertEquals(700L, p.xp, "xp_share paid the killer 700 of the 1000 pool")
  }

  @Test
  fun `the pool and the contributors are exposed to the formula`() {
    val rules =
      "$combat\n" +
        "def xp_share(ctx):\n" +
        "    base = 100 if ctx.xp == 1000 else 0\n" +
        "    extra = 50 if ctx.contributors[0].in_room else 0\n" +
        "    return {ctx.actor: base + extra}\n"
    val f = fixture("zones/z/a.star" to zone, "rules/combat.star" to TestWorlds.rules(rules))
    val p = f.player("Ann")
    val victim = f.mob("//zones/z:victim")
    f.w.startCombat(p, victim)
    f.run(60)
    assertEquals(150L, p.xp, "ctx.xp was 1000 and the killer's in_room was True")
  }

  @Test
  fun `a negative entry falls back to the default and records a rules error`() {
    // Only the real pool of 1000 gets the bad value, so the load-time dry run (a pool of 100)
    // passes.
    val rules =
      "$combat\n\ndef xp_share(ctx):\n    return {ctx.actor: -5 if ctx.xp == 1000 else ctx.xp}\n"
    val f = fixture("zones/z/a.star" to zone, "rules/combat.star" to TestWorlds.rules(rules))
    val p = f.player("Ann")
    val victim = f.mob("//zones/z:victim")
    f.w.startCombat(p, victim)
    f.run(60)
    assertFalse(victim.inWorld)
    assertEquals(1000L, p.xp, "the fallback pays the killer the whole pool")
    assertTrue(f.diagnostics("script_error").isNotEmpty(), "a rules error is recorded")
  }

  private fun loadWith(rules: String): LoadResult {
    val files =
      mapOf(
        "MODULE.gups" to TestWorlds.module(),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(rules),
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to zone,
      )
    return ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(listOf("rules"))
  }

  @Test
  fun `the dry run catches an xp_share that returns the wrong type`() {
    val r = loadWith("$combat\n\ndef xp_share(ctx):\n    return ctx.xp\n")
    assertTrue("rules" in r.failed, r.report())
    assertContains(r.errors.single { it.kind == "rules" }.message, "must be a dict")
  }

  @Test
  fun `the dry run splits a pool of 100 between two contributors`() {
    // Fails unless the dry run passes the pool and a damager and a healer, neither the victim.
    val rules =
      "$combat\n\ndef xp_share(ctx):\n" +
        "    c = ctx.contributors\n" +
        "    if ctx.xp != 100 or len(c) != 2:\n" +
        "        fail('pool %s, %d contributors' % (ctx.xp, len(c)))\n" +
        "    if sorted([x.damage for x in c]) != [0, 2] or sorted([x.healing for x in c]) != [0, 1]:\n" +
        "        fail('unexpected contributions')\n" +
        "    if ctx.target in [x.actor for x in c] or not c[0].in_room or not c[1].in_room:\n" +
        "        fail('bad contributors')\n" +
        "    return {x.actor: ctx.xp // 2 for x in c}\n"
    val r = loadWith(rules)
    assertFalse("rules" in r.failed, r.report())
  }

  @Test
  fun `the export is optional, so a world that omits it loads`() {
    val files =
      mapOf(
        "MODULE.gups" to TestWorlds.module(),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(combat),
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to zone,
      )
    val r = ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(listOf("rules"))
    assertTrue(r.errors.none { "must define xp_share" in it.message }, r.report())
  }
}
