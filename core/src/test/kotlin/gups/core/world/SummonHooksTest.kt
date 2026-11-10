package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Actor
import gups.core.model.Effect
import gups.core.model.Prototypes
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.TargetKind
import gups.core.model.ZoneProto
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummonHooksTest {
  private val summonSkillLabel = Label("//t:summon_dummy")

  private fun summonSkill(fs: FixtureSchema) =
    SkillProto(
      summonSkillLabel,
      kind = "spell",
      cost = mapOf(fs.energy to 5),
      lag = 3,
      warmup = 2,
      range = SkillRange.SELF,
      target = TargetKind.SELF,
      effect = Effect.Summon(T.DUMMY, count = 2),
    )

  private fun protos(fs: FixtureSchema) =
    Prototypes(
      T.rooms,
      T.mobs,
      T.items,
      T.skills + listOf(summonSkill(fs)),
      zones = listOf(ZoneProto("t")),
    )

  @Test
  fun `room before_summon that denies stops summon and tells caster`() {
    val host = RecordingHost()
    host.handlers[HookName.BEFORE_SUMMON] = { _, owner, _ ->
      if (owner.name == "The Hall") HookOutcome.Deny("The air is magically warded.") else null
    }
    val f = Fixture(scripts = host)
    val p = f.player("Ann")

    val result = f.w.summon(p, Effect.Summon(T.DUMMY, count = 2))
    f.run(1)

    assertTrue(result.isEmpty())
    assertContains(f.out(p), "The air is magically warded.")
    assertEquals(0, f.w.actors.count { it.name == "a training dummy" })
  }

  @Test
  fun `room before_summon denial stops a summon spell cast from spawning mobs`() {
    val fs = FixtureSchemas.current
    val host = RecordingHost()
    host.handlers[HookName.BEFORE_SUMMON] = { _, owner, _ ->
      if (owner.name == "The Hall") HookOutcome.Deny("The wards repel your summoning.") else null
    }
    val f = Fixture(scripts = host, protos = protos(fs))
    val p = f.player("Ann")
    p.skills[summonSkillLabel] = 100

    f.input(p, "cast summon_dummy")
    f.run(5)

    assertContains(f.out(p), "The wards repel your summoning.")
    assertEquals(0, f.w.actors.count { it.name == "a training dummy" })
  }

  @Test
  fun `mob observer before_summon sees summonLabel and count`() {
    val host = RecordingHost()
    var observedLabel: Label? = null
    var observedCount: Int? = null
    host.handlers[HookName.BEFORE_SUMMON] = { _, owner, ctx ->
      if (owner.name == "a goblin") {
        observedLabel = ctx.summonLabel
        observedCount = ctx.count
      }
      null
    }
    val f = Fixture(scripts = host)
    val p = f.player("Ann")
    f.mob(T.GOBLIN)

    val result = f.w.summon(p, Effect.Summon(T.DUMMY, count = 3))

    assertEquals(3, result.size)
    assertEquals(T.DUMMY, observedLabel)
    assertEquals(3, observedCount)
  }

  @Test
  fun `on_summon sees targets in spawn order after on_owner_change`() {
    val host = RecordingHost()
    val eventLog = mutableListOf<String>()
    var summonTargets: List<Actor>? = null

    host.handlers[HookName.ON_OWNER_CHANGE] = { _, _, ctx ->
      eventLog += "owner_change:${ctx.actor?.id?.v}:${ctx.event}"
      null
    }
    host.handlers[HookName.ON_SUMMON] = { _, owner, ctx ->
      eventLog += "on_summon:${owner.name}"
      if (summonTargets == null) {
        summonTargets = ctx.mobs
      }
      null
    }

    val f = Fixture(scripts = host)
    val p = f.player("Ann")

    val result = f.w.summon(p, Effect.Summon(T.DUMMY, count = 2))

    assertEquals(2, result.size)
    assertEquals(result, summonTargets)

    val firstOnSummonIndex = eventLog.indexOfFirst { it.startsWith("on_summon:") }
    val lastOwnerChangeIndex = eventLog.indexOfLast { it.startsWith("owner_change:") }
    assertTrue(lastOwnerChangeIndex >= 0, "Expected on_owner_change events")
    assertTrue(
      firstOnSummonIndex > lastOwnerChangeIndex,
      "on_summon must fire after on_owner_change",
    )

    // The newly spawned mobs are in the room, so they observe on_summon too
    for (m in result) {
      assertContains(eventLog, "on_summon:${m.name}")
    }
  }
}
