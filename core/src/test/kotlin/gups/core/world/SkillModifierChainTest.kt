package gups.core.world

import gups.core.Label
import gups.core.model.ModifierPoint
import gups.core.model.Prototypes
import gups.core.model.SkillProto
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillModifierChainTest {
  private val skill = SkillProto(Label("//t:spark"))

  @Test
  fun `skillSucceeds with 100 or more succeeds and does not touch RNG`() {
    val f = Fixture(rules = FixedRules())
    val p = f.player("Ann")
    val traces = mutableListOf<String>()
    f.w.onRules = { _, line -> traces += line }

    val c = RulesContext(f.w, p, skill = skill)
    f.w.rng.force(ints = listOf(99))

    val success = f.w.skillSucceeds(c)
    assertTrue(success)
    assertEquals(1, f.w.rng.forcedRemaining, "skillSucceeds at 100+ must not draw from RNG")
    assertTrue(traces.any { "auto SUCCEED" in it }, traces.toString())
  }

  /**
   * A room `skill_check` modifier that answers [chance], then whether Ann's skill succeeds with the
   * RNG forced to [forced], and the rules trace.
   */
  private fun succeedsWithModifier(chance: Int, forced: Int?): Pair<Boolean, List<String>> {
    val host = RecordingHost()
    host.modifiers["fixed"] = { _, _, _ -> chance }
    val f =
      Fixture(
        rules = FixedRules(),
        scripts = host,
        protos =
          Prototypes(
            listOf(T.rooms[0].copy(modifiers = mapOf(ModifierPoint.SKILL_CHECK to mod("fixed")))) +
              T.rooms.drop(1),
            T.mobs,
            T.items,
            T.skills,
          ),
      )
    val p = f.player("Ann")
    val traces = mutableListOf<String>()
    f.w.onRules = { _, line -> traces += line }
    forced?.let { f.w.rng.force(ints = listOf(it)) }
    return f.w.skillSucceeds(RulesContext(f.w, p, skill = skill)) to traces
  }

  @Test
  fun `skillSucceeds with 0 always fails`() {
    val (success, traces) = succeedsWithModifier(0, null)
    assertFalse(success)
    assertTrue(traces.any { "FAIL" in it }, traces.toString())
  }

  @Test
  fun `skillSucceeds with 50 and seeded RNG records roll in trace and outcome`() {
    val (success, traces) = succeedsWithModifier(50, 42)
    assertTrue(success)
    assertTrue(traces.any { "roll 42 SUCCEED" in it }, traces.toString())
  }

  /** Rules whose `skill_check` is [chance]. */
  private class Chance(val chance: Int) : FixedRules() {
    override fun skillCheck(c: RulesContext): Int = chance
  }

  private fun rollAt(chance: Int, adjust: Int, forced: Int): Triple<SkillRoll, Int, List<String>> {
    val f = Fixture(rules = Chance(chance))
    val p = f.player("Ann")
    val traces = mutableListOf<String>()
    f.w.onRules = { _, line -> traces += line }
    f.w.rng.force(ints = listOf(forced))
    val r = f.w.skillRoll(RulesContext(f.w, p, skill = skill), adjust)
    return Triple(r, f.w.rng.forcedRemaining, traces)
  }

  @Test
  fun `skillRoll draws once and reports the chance, the roll and the outcome`() {
    val (hit, _, traces) = rollAt(50, 0, 42)
    assertEquals(SkillRoll(50, 42, true), hit)
    assertTrue(traces.any { "roll 42 SUCCEED" in it }, traces.toString())
    assertFalse(traces.any { "adjust" in it }, "no adjust is traced when it is 0")
    assertEquals(SkillRoll(50, 51, false), rollAt(50, 0, 51).first)
  }

  @Test
  fun `skillRoll adds adjust to the chance, and 100 or more draws nothing`() {
    val (r, left, traces) = rollAt(80, 25, 99)
    assertEquals(SkillRoll(105, null, true), r)
    assertEquals(1, left, "no draw at 100+")
    assertTrue(traces.any { "adjust +25 → auto SUCCEED" in it }, traces.toString())
    val (low, _, lowTraces) = rollAt(10, -15, 1)
    assertEquals(SkillRoll(-5, 1, false), low)
    assertTrue(lowTraces.any { "adjust -15 → roll 1 FAIL" in it }, lowTraces.toString())
  }

  @Test
  fun `skillPowerChain and itemPowerChain clamp negative values to 0 and record clamp trace`() {
    val host = RecordingHost()
    host.modifiers["sub"] = { _, _, base -> (base as Int) - 200 }
    val f =
      Fixture(
        rules = FixedRules(),
        scripts = host,
        protos =
          Prototypes(
            listOf(
              T.rooms[0].copy(
                modifiers =
                  mapOf(
                    ModifierPoint.SKILL_POWER to mod("sub"),
                    ModifierPoint.ITEM_POWER to mod("sub"),
                  )
              )
            ) + T.rooms.drop(1),
            T.mobs,
            T.items,
            T.skills,
          ),
      )
    val p = f.player("Ann")
    val traces = mutableListOf<String>()
    f.w.onRules = { _, line -> traces += line }

    val c = RulesContext(f.w, p, skill = skill)
    val power = f.w.skillPowerChain(c)
    assertEquals(0, power)
    assertTrue(traces.any { "clamp 0" in it }, traces.toString())

    traces.clear()
    val itemPower = f.w.itemPowerChain(c)
    assertEquals(0, itemPower)
    assertTrue(traces.any { "clamp 0" in it }, traces.toString())
  }
}
