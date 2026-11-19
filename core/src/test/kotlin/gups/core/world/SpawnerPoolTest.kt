package gups.core.world

import gups.core.Label
import gups.core.WORLD_TICK_PULSES
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Mob
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.Respawn
import gups.core.model.Roll
import gups.core.model.RoomProto
import gups.core.model.SpawnRule
import gups.core.model.SpawnerProto
import gups.core.model.ZoneProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpawnerPoolTest {
  private val roomA = Label("//s:a")
  private val roomB = Label("//s:b")
  private val otherRoom = Label("//other:c")
  private val mobLabel = Label("//s:goblin")
  private val mob2Label = Label("//s:orc")

  private fun goblin(label: Label = mobLabel) =
    MobProto(
      label,
      "a ${label.name}",
      listOf(label.name),
      vitals = mapOf(FixtureSchemas.current.health to 10),
    )

  private fun mobsIn(f: Fixture, room: Label, proto: Label = mobLabel): List<Mob> =
    f.w.room(room).actors.filterIsInstance<Mob>().filter { it.protoLabel == proto }

  @Test
  fun `weighted pool with forced rolls picks expected rooms and sets mob homeRoom per copy`() {
    val spawnerId = Label("//s:patrol")
    val protos =
      Prototypes(
        rooms = listOf(RoomProto(roomA, "Room A"), RoomProto(roomB, "Room B")),
        mobs = listOf(goblin()),
        zones = listOf(ZoneProto("s")),
        spawners =
          listOf(
            SpawnerProto(
              id = spawnerId,
              rule =
                SpawnRule(
                  label = mobLabel,
                  id = spawnerId,
                  max = 3,
                  respawn = Respawn.Timed(Roll.of(120)),
                ),
              rooms = linkedMapOf(roomA to 1, roomB to 3),
            )
          ),
      )
    val f = Fixture(protos = protos)
    // Kill all 3 initial mobs so we can force the next batch's rolls cleanly.
    val initial = f.w.actors.filterIsInstance<Mob>().toList()
    assertEquals(3, initial.size)
    // Force rolls: 1 -> roomA (weight 1), 2 -> roomB (weight 3), 4 -> roomB (weight 3).
    f.w.rng.force(listOf(1, 2, 4))
    for (m in initial) f.hurt(m, 1000)

    f.runTo(120)
    val inA = mobsIn(f, roomA)
    val inB = mobsIn(f, roomB)
    assertEquals(1, inA.size)
    assertEquals(2, inB.size)
    assertEquals(roomA, inA.single().homeRoom)
    for (m in inB) assertEquals(roomB, m.homeRoom)
  }

  @Test
  fun `quarantined package room is skipped with one diagnostic across multiple picks`() {
    val spawnerId = Label("//s:cross")
    val quarantined = mutableSetOf("other")
    val scripts =
      object : ScriptHost by ScriptHost.NONE {
        override val quarantinedPackages: Set<String>
          get() = quarantined
      }
    val protos =
      Prototypes(
        rooms = listOf(RoomProto(roomA, "Room A"), RoomProto(otherRoom, "Other Room")),
        mobs = listOf(goblin()),
        zones = listOf(ZoneProto("s"), ZoneProto("other")),
        spawners =
          listOf(
            SpawnerProto(
              id = spawnerId,
              rule =
                SpawnRule(
                  label = mobLabel,
                  id = spawnerId,
                  max = 3,
                  respawn = Respawn.Timed(Roll.of(120)),
                ),
              rooms = linkedMapOf(roomA to 1, otherRoom to 10),
            )
          ),
      )
    val f = Fixture(protos = protos, scripts = scripts)
    // All 3 copies must land in roomA; otherRoom is skipped.
    assertEquals(3, mobsIn(f, roomA).size)
    assertEquals(0, mobsIn(f, otherRoom).size)

    // Kill one and let it respawn; still only one diagnostic for the quarantined room.
    f.hurt(mobsIn(f, roomA).first(), 1000)
    f.runTo(120)
    assertEquals(3, mobsIn(f, roomA).size)
    assertEquals(0, mobsIn(f, otherRoom).size)

    val diags = f.w.diagnostics.filter { it.message.contains("missing or quarantined") }
    assertEquals(1, diags.size, diags.toString())
    assertTrue(diags.single().message.contains(otherRoom.v), diags.single().message)
  }

  @Test
  fun `pool with no usable rooms adds nothing and re-arms timed rule`() {
    val spawnerId = Label("//s:stranded")
    val quarantined = mutableSetOf("other")
    val scripts =
      object : ScriptHost by ScriptHost.NONE {
        override val quarantinedPackages: Set<String>
          get() = quarantined
      }
    val protos =
      Prototypes(
        rooms = listOf(RoomProto(otherRoom, "Other Room")),
        mobs = listOf(goblin()),
        zones = listOf(ZoneProto("s"), ZoneProto("other")),
        spawners =
          listOf(
            SpawnerProto(
              id = spawnerId,
              rule =
                SpawnRule(
                  label = mobLabel,
                  id = spawnerId,
                  max = 2,
                  respawn = Respawn.Timed(Roll.of(120)),
                ),
              rooms = linkedMapOf(otherRoom to 1),
            )
          ),
      )
    val f = Fixture(protos = protos, scripts = scripts)
    val state = f.w.spawnStates.single()
    assertEquals(0, state.tracked)
    assertTrue(state.ran)
    assertEquals(120L, state.respawnAt)

    // Still quarantined at pulse 120: adds nothing, re-arms for 240.
    f.runTo(120)
    assertEquals(0, state.tracked)
    assertEquals(240L, state.respawnAt)

    // Lift quarantine before 240: next timed batch fills both copies.
    quarantined.clear()
    f.runTo(240)
    assertEquals(2, mobsIn(f, otherRoom).size)
    assertEquals(2, state.tracked)
  }

  @Test
  fun `dormant pool room in another package holds copy and places it on wake`() {
    val spawnerId = Label("//s:remote")
    val protos =
      Prototypes(
        rooms = listOf(RoomProto(roomA, "Room A"), RoomProto(otherRoom, "Other Room")),
        mobs = listOf(goblin()),
        zones = listOf(ZoneProto("s", resetTicks = Roll.of(1)), ZoneProto("other")),
        spawners =
          listOf(
            SpawnerProto(
              id = spawnerId,
              rule = SpawnRule(label = mobLabel, id = spawnerId, max = 1, respawn = Respawn.Reset),
              rooms = linkedMapOf(otherRoom to 1),
            )
          ),
      )
    val f = Fixture(protos = protos, bubble = true)
    // First fill placed the mob even while dormant; kill it to trigger a reset refill.
    val initial = mobsIn(f, otherRoom).single()
    f.hurt(initial, 1000)
    assertEquals(0, mobsIn(f, otherRoom).size)
    assertTrue(f.w.room(otherRoom).isDormant)

    // Zone s resets after 1 world tick; otherRoom is dormant so the copy is held.
    f.runTo(WORLD_TICK_PULSES.toLong())
    val state = f.w.spawnStates.single()
    assertEquals(0, state.live.size)
    assertEquals(1, state.held.size)
    assertEquals(otherRoom, state.held.single().room)
    assertEquals(0, mobsIn(f, otherRoom).size)

    // A player entering otherRoom wakes it and places the held copy.
    f.player("Ada", at = otherRoom)
    f.run()
    assertEquals(1, state.live.size)
    assertTrue(state.held.isEmpty())
    val placed = mobsIn(f, otherRoom).single()
    assertEquals(otherRoom, placed.homeRoom)
  }

  @Test
  fun `reset spawners refill after package room rules in declaration order`() {
    val spawner1 = Label("//s:first_pool")
    val spawner2 = Label("//s:second_pool")
    val placed = mutableListOf<Label>()
    val host =
      RecordingHost().apply {
        handlers[HookName.ON_SPAWN] = { _, owner, _ ->
          if (owner is Mob) placed += owner.protoLabel
          null
        }
      }
    val protos =
      Prototypes(
        rooms =
          listOf(
            RoomProto(
              roomA,
              "Room A",
              spawns = listOf(SpawnRule(mobLabel, max = 1, respawn = Respawn.Reset)),
            )
          ),
        mobs = listOf(goblin(mobLabel), goblin(mob2Label)),
        zones = listOf(ZoneProto("s", resetTicks = Roll.of(1))),
        spawners =
          listOf(
            SpawnerProto(
              id = spawner1,
              rule = SpawnRule(label = mob2Label, id = spawner1, max = 1, respawn = Respawn.Reset),
              rooms = linkedMapOf(roomA to 1),
            ),
            SpawnerProto(
              id = spawner2,
              rule = SpawnRule(label = mobLabel, id = spawner2, max = 1, respawn = Respawn.Reset),
              rooms = linkedMapOf(roomA to 1),
            ),
          ),
      )
    val f = Fixture(protos = protos, scripts = host)
    assertEquals(3, f.w.actors.filterIsInstance<Mob>().size)
    for (m in f.w.actors.filterIsInstance<Mob>().toList()) f.hurt(m, 1000)
    placed.clear()

    f.runTo(WORLD_TICK_PULSES.toLong())
    assertEquals(listOf(mobLabel, mob2Label, mobLabel), placed)
    assertNotNull(f.w.spawnStates.firstOrNull { it.key == SpawnKey.Named(spawner1) })
  }
}
