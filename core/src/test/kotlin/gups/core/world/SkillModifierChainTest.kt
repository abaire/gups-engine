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

  @Test
  fun `skillSucceeds with 0 always fails`() {
    val host = RecordingHost()
    host.modifiers["zero"] = { _, _, _ -> 0 }
    val f =
      Fixture(
        rules = FixedRules(),
        scripts = host,
        protos =
          Prototypes(
            listOf(T.rooms[0].copy(modifiers = mapOf(ModifierPoint.SKILL_CHECK to mod("zero")))) +
              T.rooms.drop(1),
            T.mobs,
            T.items,
            T.skills,
          ),
      )
    val p = f.player("Ann")
    val traces = mutableListOf<String>()
    f.w.onRules = { _, line -> traces += line }

    val c = RulesContext(f.w, p, skill = skill)
    val success = f.w.skillSucceeds(c)
    assertFalse(success)
    assertTrue(traces.any { "FAIL" in it }, traces.toString())
  }

  @Test
  fun `skillSucceeds with 50 and seeded RNG records roll in trace and outcome`() {
    val host = RecordingHost()
    host.modifiers["half"] = { _, _, _ -> 50 }
    val f =
      Fixture(
        rules = FixedRules(),
        scripts = host,
        protos =
          Prototypes(
            listOf(T.rooms[0].copy(modifiers = mapOf(ModifierPoint.SKILL_CHECK to mod("half")))) +
              T.rooms.drop(1),
            T.mobs,
            T.items,
            T.skills,
          ),
      )
    val p = f.player("Ann")
    val traces = mutableListOf<String>()
    f.w.onRules = { _, line -> traces += line }

    f.w.rng.force(ints = listOf(42))
    val c = RulesContext(f.w, p, skill = skill)
    val success = f.w.skillSucceeds(c)
    assertTrue(success)
    assertTrue(traces.any { "roll 42 SUCCEED" in it }, traces.toString())
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
