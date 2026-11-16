package gups.core.world

import gups.core.Label
import gups.core.WORLD_TICK_PULSES
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Direction
import gups.core.model.DoorProto
import gups.core.model.DoorState
import gups.core.model.ExitProto
import gups.core.model.Prototypes
import gups.core.model.Room
import gups.core.model.RoomProto
import gups.core.model.ZoneProto
import gups.core.model.mapNumberHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class TrickExitRetargetTest(private val fs: FixtureSchema) {
  init {
    FixtureSchemas.current = fs
  }

  @BeforeEach
  fun useSchema() {
    FixtureSchemas.current = fs
  }

  @AfterEach
  fun resetSchema() {
    FixtureSchemas.current = FixtureSchemas.FANTASY
  }

  private val stair = Label("//z:stair")
  private val grotto = Label("//z:grotto")
  private val cave = Label("//z:cave")
  private val lockedRoom = Label("//z:locked_room")
  private val crossRoom = Label("//z:cross")

  private fun protos() =
    Prototypes(
      zones = listOf(ZoneProto("z")),
      rooms =
        listOf(
          RoomProto(
            stair,
            "Tide Stair",
            exits =
              listOf(
                ExitProto(Direction.DOWN, grotto),
                ExitProto(Direction.NORTH, lockedRoom, door = DoorProto(DoorState.CLOSED)),
              ),
          ),
          RoomProto(grotto, "Grotto"),
          RoomProto(cave, "Drowned Cave"),
          RoomProto(lockedRoom, "Locked Room"),
          RoomProto(
            crossRoom,
            "Cross Room",
            exits =
              listOf(
                ExitProto(
                  Direction.WEST,
                  to = null,
                  candidates = mapOf(grotto to 1, cave to 1),
                  oneWay = true,
                )
              ),
          ),
        ),
    )

  @Test
  fun `setExitTo redirects walk, pathTo, ball, inbound, Room_Info, and restores on null`() {
    val f = Fixture(protos = protos())
    val (p, sink) = f.gmcpPlayer("Ann", at = stair)
    f.run()
    sink.take()

    val stairRoom = f.w.room(stair)
    val grottoRoom = f.w.room(grotto)
    val caveRoom = f.w.room(cave)

    // Initially DOWN leads to grotto.
    assertEquals(listOf(Direction.DOWN), f.w.pathTo(stairRoom, grottoRoom))
    assertNull(f.w.pathTo(stairRoom, caveRoom))
    assertTrue(grottoRoom in f.w.ball(stairRoom))
    assertFalse(caveRoom in f.w.ball(stairRoom))
    assertTrue(f.w.inbound(caveRoom).isEmpty())

    // Retarget DOWN to cave.
    f.w.setExitTo(stairRoom, Direction.DOWN, caveRoom)
    assertEquals(cave, stairRoom.exits.getValue(Direction.DOWN).to)
    assertEquals(grotto, stairRoom.exits.getValue(Direction.DOWN).originalTo)
    assertTrue(stairRoom.dirty)

    assertEquals(listOf(Direction.DOWN), f.w.pathTo(stairRoom, caveRoom))
    assertNull(f.w.pathTo(stairRoom, grottoRoom))
    assertTrue(caveRoom in f.w.ball(stairRoom))
    assertFalse(grottoRoom in f.w.ball(stairRoom))
    assertEquals(
      listOf(stairRoom to stairRoom.exits.getValue(Direction.DOWN)),
      f.w.inbound(caveRoom),
    )
    assertTrue(f.w.inbound(grottoRoom).isEmpty())

    // Phase-7 diff resends Room.Info with the new destination num.
    f.run()
    @Suppress("UNCHECKED_CAST")
    val pkg = sink.take().single { it.first == "Room.Info" }.second as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val exitsMap = pkg["exits"] as Map<String, Long?>
    assertEquals(mapNumberHash(cave.v), exitsMap["d"])

    // Walking down lands in cave.
    f.input(p, "down")
    f.run(2)
    assertEquals(cave, p.room.label)

    // Restoring with null points DOWN back at grotto.
    f.w.setExitTo(stairRoom, Direction.DOWN, null)
    assertEquals(grotto, stairRoom.exits.getValue(Direction.DOWN).to)
    assertEquals(listOf(Direction.DOWN), f.w.pathTo(stairRoom, grottoRoom))
  }

  @Test
  fun `bubble updates on pulse maintenance and not inside on_tick`() {
    val host = RecordingHost()
    var dormantDuringOnTick: Boolean? = null
    host.handlers[HookName.ON_TICK] = { world, owner, _ ->
      if (owner is Room && owner.label == stair) {
        val caveRoom = world.room(cave)
        world.setExitTo(owner, Direction.DOWN, caveRoom)
        dormantDuringOnTick = caveRoom.isDormant
      }
      null
    }
    val f = Fixture(protos = protos(), scripts = host, bubble = true)
    f.player("Ann", at = stair)
    f.run(1)
    assertTrue(f.w.room(cave).isDormant)
    assertFalse(f.w.room(grotto).isDormant)

    // Advance to the world tick pulse.
    val remaining = (WORLD_TICK_PULSES - (f.w.pulse % WORLD_TICK_PULSES)).toInt()
    f.run(remaining)

    assertEquals(true, dormantDuringOnTick, "on_tick should not wake cave mid-tick")
    assertFalse(f.w.room(cave).isDormant, "cave should be active after pulse bubble maintenance")
    assertTrue(f.w.room(grotto).isDormant, "grotto should go dormant after losing coverage")
  }

  @Test
  fun `setting the same destination twice does not clear pathCache`() {
    val f = Fixture(protos = protos())
    val stairRoom = f.w.room(stair)
    val caveRoom = f.w.room(cave)
    f.w.setExitTo(stairRoom, Direction.DOWN, caveRoom)
    assertEquals(listOf(Direction.DOWN), f.w.pathTo(stairRoom, caveRoom))
    assertTrue(f.w.pathCache.isNotEmpty())

    f.w.setExitTo(stairRoom, Direction.DOWN, caveRoom)
    assertTrue(f.w.pathCache.isNotEmpty(), "no-op setExitTo must not clear pathCache")
  }

  @Test
  fun `setExitTo fails on a door exit, per-crossing exit, and missing direction`() {
    val f = Fixture(protos = protos())
    val stairRoom = f.w.room(stair)
    val caveRoom = f.w.room(cave)

    val doorErr =
      assertFailsWith<IllegalArgumentException> {
        f.w.setExitTo(stairRoom, Direction.NORTH, caveRoom)
      }
    assertEquals("//z:stair exit north has a door and can't be retargeted", doorErr.message)

    val crossErr =
      assertFailsWith<IllegalArgumentException> {
        f.w.setExitTo(f.w.room(crossRoom), Direction.WEST, caveRoom)
      }
    assertEquals(
      "//z:cross exit west is picked per crossing and can't be retargeted",
      crossErr.message,
    )

    val missingErr =
      assertFailsWith<IllegalArgumentException> {
        f.w.setExitTo(stairRoom, Direction.EAST, caveRoom)
      }
    assertEquals("//z:stair has no exit east", missingErr.message)
  }
}
