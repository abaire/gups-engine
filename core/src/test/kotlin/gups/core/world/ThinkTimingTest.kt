package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchemas
import gups.core.model.MobProto
import gups.core.model.Prototypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A mob's think runs the action it chooses in the same pulse, so mob actions happen one pulse
 * sooner than they did when a think only queued its action for the next pulse.
 */
class ThinkTimingTest {
  private fun corridor(mob: MobProto): Fixture {
    val c = C.protos(n = 10)
    return Fixture(
      protos =
        Prototypes(
          c.rooms.values.toList(),
          c.mobs.values.toList() + mob,
          zones = c.zones.values.toList(),
        )
    )
  }

  private fun walker(
    name: String,
    vararg behaviors: Behavior,
    think: ThinkFn? = null,
    every: Int = 1,
  ) =
    MobProto(
      Label("//c:$name"),
      name,
      listOf(name),
      vitals = mapOf(FixtureSchemas.current.health to 40),
      behaviors = behaviors.toList(),
      think = think,
      thinkEvery = every,
    )

  @Test
  fun `a wander mob changes rooms on the pulse of its think`() {
    val f = corridor(walker("walker", Wander(radius = 2, chance = 100), every = 4))
    val m = f.w.spawnMob(Label("//c:walker"), f.w.room(C.r(5)))
    var prev = m.room.label
    var movedPulse = -1L
    var thinkAtMove = -1L
    repeat(40) {
      f.run()
      if (m.room.label != prev && movedPulse < 0) {
        movedPulse = f.w.pulse
        thinkAtMove = m.lastThink
      }
      prev = m.room.label
    }
    assertTrue(movedPulse > 0, "the walker moved")
    assertEquals(movedPulse, thinkAtMove, "the move lands on the think's pulse")
  }

  @Test
  fun `a pursue mob covers one room per pulse`() {
    val f = corridor(walker("hunter", Pursue(ActorFilter("players"))))
    val m = f.w.spawnMob(Label("//c:hunter"), f.w.room(C.r(0)))
    f.player("Ann", at = C.r(6))
    f.run()
    assertEquals(C.r(1), m.room.label, "one room on the first pulse")
    f.run(5)
    assertEquals(C.r(6), m.room.label, "one room per pulse, reaching the player")
  }

  @Test
  fun `an aggressive mob engages on the pulse it thinks`() {
    val f = corridor(walker("orc", Aggressive(ActorFilter("players"))))
    val m = f.w.spawnMob(Label("//c:orc"), f.w.room(C.r(3)))
    f.player("Ann", at = C.r(3))
    var engagedPulse = -1L
    var thinkAtEngage = -1L
    repeat(10) {
      f.run()
      if (engagedPulse < 0 && m.fighting.isNotEmpty()) {
        engagedPulse = f.w.pulse
        thinkAtEngage = m.lastThink
      }
    }
    assertTrue(engagedPulse > 0, "the orc engaged")
    assertEquals(engagedPulse, thinkAtEngage, "the engagement lands on the think's pulse")
  }

  @Test
  fun `a think that queues two moves runs the first now and the second next pulse`() {
    val think = ThinkFn { w, mob, _ ->
      w.act(mob, "east")
      w.act(mob, "east")
      ThinkResult.Idle
    }
    val f = corridor(walker("walker", think = think, every = 2))
    val m = f.w.spawnMob(Label("//c:walker"), f.w.room(C.r(0)))
    f.run()
    assertEquals(C.r(1), m.room.label, "the first queued move runs on the think's pulse")
    f.run()
    assertEquals(C.r(2), m.room.label, "the second runs on the next pulse")
  }

  @Test
  fun `a heel pet keeps within two rooms of a walking owner`() {
    val f = corridor(walker("pet", Heel()))
    val m = f.w.spawnMob(Label("//c:pet"), f.w.room(C.r(0)))
    val owner = f.player("Ann", at = C.r(0))
    f.w.setOwner(m, owner)
    fun idx(label: Label) = label.name.drop(1).toInt()
    var maxGap = 0
    repeat(8) {
      f.input(owner, "east")
      f.run()
      maxGap = maxOf(maxGap, idx(owner.room.label) - idx(m.room.label))
    }
    assertTrue(maxGap <= 2, "the pet stays within two rooms of the owner; the gap was $maxGap")
  }
}
