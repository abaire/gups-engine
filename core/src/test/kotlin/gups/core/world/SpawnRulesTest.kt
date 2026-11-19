package gups.core.world

import gups.core.Label
import gups.core.WORLD_TICK_PULSES
import gups.core.fixtures.FixtureSchemas
import gups.core.model.ItemProto
import gups.core.model.Mob
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.Respawn
import gups.core.model.Roll
import gups.core.model.RoomProto
import gups.core.model.SpawnRule
import gups.core.model.ZoneProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpawnRulesTest {
  private val roomLabel = Label("//s:room")
  private val mobLabel = Label("//s:goblin")
  private val gemLabel = Label("//s:gem")

  private fun protos(spawns: List<SpawnRule>, resetTicks: Roll? = null): Prototypes =
    Prototypes(
      items = listOf(ItemProto(gemLabel, "a gem", listOf("gem"))),
      rooms = listOf(RoomProto(roomLabel, "Arena", spawns = spawns)),
      mobs =
        listOf(
          MobProto(
            mobLabel,
            "a goblin",
            listOf("goblin"),
            vitals = mapOf(FixtureSchemas.current.health to 10),
          )
        ),
      zones = listOf(ZoneProto("s", resetTicks = resetTicks)),
    )

  private fun mobsIn(f: Fixture): List<Mob> =
    f.w.room(roomLabel).actors.filterIsInstance<Mob>().filter { it.protoLabel == mobLabel }

  private fun kill(f: Fixture, mob: Mob) {
    f.hurt(mob, 1000)
  }

  @Test
  fun `first fill reaches max regardless of batch`() {
    val f =
      Fixture(
        protos =
          protos(
            listOf(
              SpawnRule(
                mobLabel,
                max = 4,
                batch = Roll.of(1),
                respawn = Respawn.Timed(Roll.of(120)),
              )
            )
          )
      )
    assertEquals(4, mobsIn(f).size)
    val state = f.w.spawnStates.single()
    assertTrue(state.ran)
    assertEquals(4, state.tracked)
    assertNull(state.respawnAt)
  }

  @Test
  fun `fresh world with bubble on and no players has spawned mobs as entities with nothing held`() {
    val f =
      Fixture(
        protos = protos(listOf(SpawnRule(mobLabel, max = 3, respawn = Respawn.Reset))),
        bubble = true,
      )
    val room = f.w.room(roomLabel)
    assertTrue(room.isDormant)
    assertEquals(3, mobsIn(f).size)
    val state = f.w.spawnStates.single()
    assertEquals(3, state.live.size)
    assertTrue(state.held.isEmpty())
    assertEquals(3, state.tracked)
  }

  @Test
  fun `timed rule with batch 1 rolls one timer, ignores second death, refills one per deadline`() {
    val f =
      Fixture(
        protos =
          protos(
            listOf(
              SpawnRule(
                mobLabel,
                max = 3,
                batch = Roll.of(1),
                respawn = Respawn.Timed(Roll.of(120)),
              )
            )
          )
      )
    val state = f.w.spawnStates.single()
    val initial = mobsIn(f)
    assertEquals(3, initial.size)

    // First death starts the timer at pulse 0 + 120 = 120.
    kill(f, initial[0])
    assertEquals(2, mobsIn(f).size)
    assertEquals(120L, state.respawnAt)

    // Advance partway, then kill a second mob: the pending deadline must not move.
    f.runTo(60)
    kill(f, initial[1])
    assertEquals(1, mobsIn(f).size)
    assertEquals(120L, state.respawnAt)

    // At pulse 120 (a world tick), one mob comes back and a new timer is rolled at once (120 + 120
    // = 240).
    f.runTo(120)
    assertEquals(2, mobsIn(f).size)
    assertEquals(240L, state.respawnAt)

    // At pulse 240, the third mob comes back and the rule is at max with no timer.
    f.runTo(240)
    assertEquals(3, mobsIn(f).size)
    assertNull(state.respawnAt)
  }

  @Test
  fun `timed rule is not refilled by a zone reset`() {
    val f =
      Fixture(
        protos =
          protos(
            spawns = listOf(SpawnRule(mobLabel, max = 2, respawn = Respawn.Timed(Roll.of(1200)))),
            resetTicks = Roll.of(1),
          )
      )
    val state = f.w.spawnStates.single()
    kill(f, mobsIn(f).first())
    assertEquals(1, mobsIn(f).size)
    assertEquals(1200L, state.respawnAt)

    // Zone reset fires at pulse 120; timed rule must not refill.
    f.runTo(WORLD_TICK_PULSES.toLong())
    assertEquals(1, mobsIn(f).size)
    assertEquals(1200L, state.respawnAt)

    // Timed rule refills on its own deadline.
    f.runTo(1200)
    assertEquals(2, mobsIn(f).size)
    assertNull(state.respawnAt)
  }

  @Test
  fun `reset rule refills one batch at zone reset and never on a timer`() {
    val f =
      Fixture(
        protos =
          protos(
            spawns =
              listOf(SpawnRule(mobLabel, max = 3, batch = Roll.of(1), respawn = Respawn.Reset)),
            resetTicks = Roll.of(2),
          )
      )
    val state = f.w.spawnStates.single()
    val initial = mobsIn(f)
    kill(f, initial[0])
    kill(f, initial[1])
    assertEquals(1, mobsIn(f).size)
    assertNull(state.respawnAt)

    // World tick 1 (pulse 120): no reset yet.
    f.runTo(WORLD_TICK_PULSES.toLong())
    assertEquals(1, mobsIn(f).size)
    assertNull(state.respawnAt)

    // World tick 2 (pulse 240): reset adds one batch (1 mob).
    f.runTo(2L * WORLD_TICK_PULSES)
    assertEquals(2, mobsIn(f).size)
    assertNull(state.respawnAt)

    // World tick 4 (pulse 480): second reset brings it to max.
    f.runTo(4L * WORLD_TICK_PULSES)
    assertEquals(3, mobsIn(f).size)
    assertNull(state.respawnAt)
  }

  @Test
  fun `never rule never refills`() {
    val f =
      Fixture(
        protos =
          protos(
            spawns = listOf(SpawnRule(mobLabel, max = 2, respawn = Respawn.Never)),
            resetTicks = Roll.of(1),
          )
      )
    val state = f.w.spawnStates.single()
    kill(f, mobsIn(f).first())
    assertEquals(1, mobsIn(f).size)
    assertNull(state.respawnAt)

    f.runTo(10L * WORLD_TICK_PULSES)
    assertEquals(1, mobsIn(f).size)
    assertNull(state.respawnAt)
  }

  @Test
  fun `timed range with forced rng lands on first world tick at or after deadline`() {
    val f =
      Fixture(
        protos = protos(listOf(SpawnRule(mobLabel, max = 1, respawn = Respawn.Timed(Roll(10, 20)))))
      )
    val state = f.w.spawnStates.single()
    f.w.rng.force(listOf(15))
    kill(f, mobsIn(f).single())
    assertEquals(15L, state.respawnAt)

    // Before the world tick (pulse 119): still waiting for the world tick.
    f.runTo(119)
    assertEquals(0, mobsIn(f).size)
    assertEquals(15L, state.respawnAt)

    // First world tick at or after 15 is pulse 120.
    f.runTo(120)
    assertEquals(1, mobsIn(f).size)
    assertNull(state.respawnAt)
  }

  @Test
  fun `dormant room holds due timer copy and wakes placing it with on_spawn before missed on_reset`() {
    val host = RecordingHost()
    val events = mutableListOf<String>()
    host.handlers[HookName.ON_SPAWN] = { _, owner, _ ->
      if (owner is Mob) events += "spawn:${owner.name}"
      null
    }
    host.handlers[HookName.ON_RESET] = { _, owner, _ ->
      events += "reset:${owner.name}"
      null
    }
    val f =
      Fixture(
        protos =
          protos(
            spawns = listOf(SpawnRule(mobLabel, max = 1, respawn = Respawn.Timed(Roll.of(100)))),
            resetTicks = Roll.of(2),
          ),
        scripts = host,
        bubble = true,
      )
    val state = f.w.spawnStates.single()
    events.clear()

    kill(f, mobsIn(f).single())
    assertEquals(0, mobsIn(f).size)
    assertEquals(100L, state.respawnAt)

    // Advance past both the timer (100 -> tick 120) and the zone reset (240) while dormant.
    f.runTo(240)
    assertEquals(0, mobsIn(f).size, "held copy is not an entity while dormant")
    assertEquals(1, state.held.size)
    assertEquals(1, state.tracked)
    assertNull(state.respawnAt)
    assertTrue(events.isEmpty(), "neither on_spawn nor on_reset fires while dormant")

    // Waking the room places the held copy (firing on_spawn) before the missed on_reset.
    f.player("Ann", at = roomLabel)
    f.run()
    assertEquals(1, mobsIn(f).size)
    assertTrue(state.held.isEmpty())
    assertEquals(listOf("spawn:a goblin", "reset:Arena"), events)
  }

  @Test
  fun `two resets missed while dormant add two batches capped at max`() {
    val f =
      Fixture(
        protos =
          protos(
            spawns =
              listOf(SpawnRule(mobLabel, max = 3, batch = Roll.of(2), respawn = Respawn.Reset)),
            resetTicks = Roll.of(1),
          ),
        bubble = true,
      )
    val state = f.w.spawnStates.single()
    for (m in mobsIn(f)) kill(f, m)
    assertEquals(0, mobsIn(f).size)

    // Two resets while dormant: first adds 2 held, second adds 1 (capped at max 3).
    f.runTo(2L * WORLD_TICK_PULSES)
    assertEquals(0, mobsIn(f).size)
    assertEquals(3, state.held.size)
    assertEquals(3, state.tracked)

    // Wake places all 3.
    f.player("Ann", at = roomLabel)
    f.run()
    assertEquals(3, mobsIn(f).size)
    assertTrue(state.held.isEmpty())
  }

  @Test
  fun `reset_world_ticks range with forced roll schedules next reset at rolled ticks after reset pulse`() {
    val f = Fixture(protos = protos(spawns = emptyList(), resetTicks = Roll(2, 4)))
    val firstAt = f.w.zoneResetDeadlines().getValue("s")
    assertTrue(
      firstAt in (2L * WORLD_TICK_PULSES)..(4L * WORLD_TICK_PULSES),
      "initial reset $firstAt should be in 2..4 world ticks",
    )

    f.w.rng.force(listOf(3))
    f.runTo(firstAt)
    assertEquals(firstAt + 3L * WORLD_TICK_PULSES, f.w.zoneResetDeadlines().getValue("s"))
  }

  @Test
  fun `a reload that stops a rule being timed drops its pending deadline`() {
    val id = Label("//s:goblins")
    fun rule(respawn: Respawn) = SpawnRule(mobLabel, id = id, max = 1, respawn = respawn)
    val f = Fixture(protos = protos(listOf(rule(Respawn.Timed(Roll.of(100))))))
    kill(f, mobsIn(f).single())
    assertEquals(f.w.pulse + 100, f.w.spawnStates.single().respawnAt)

    f.w.applyPrototypes(protos(listOf(rule(Respawn.Reset))))
    assertNull(f.w.spawnStates.single().respawnAt)

    // Switching back to timed rolls a fresh deadline instead of reviving the old one.
    f.runTo(500)
    f.w.applyPrototypes(protos(listOf(rule(Respawn.Timed(Roll.of(100))))))
    assertEquals(f.w.pulse + 100, f.w.spawnStates.single().respawnAt)
  }

  @Test
  fun `a reload that turns a mob rule into an item rule lets the mobs go`() {
    val id = Label("//s:x")
    fun rule(label: Label) =
      SpawnRule(label, id = id, max = 1, respawn = Respawn.Timed(Roll.of(100)))
    val f = Fixture(protos = protos(listOf(rule(mobLabel))))
    val goblin = mobsIn(f).single()

    f.w.applyPrototypes(protos(listOf(rule(gemLabel))))
    assertNull(f.w.spawnKeyOf(goblin))
    assertEquals(emptyList(), f.w.invariantViolations().map { it.message })
    val state = f.w.spawnStates.single()
    assertEquals(0, state.tracked)
    assertTrue(state.ran)
    assertEquals(f.w.pulse + 100, state.respawnAt)
  }
}
