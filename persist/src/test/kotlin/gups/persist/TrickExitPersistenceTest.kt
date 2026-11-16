package gups.persist

import gups.core.Label
import gups.core.fixtures.tempDb
import gups.core.model.Direction
import gups.core.model.DoorProto
import gups.core.model.DoorState
import gups.core.model.ExitProto
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import gups.core.model.ZoneProto
import gups.core.world.setExitTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class TrickExitPersistenceTest {
  private val stair = Label("//t:stair")
  private val grotto = Label("//t:grotto")
  private val cave = Label("//t:cave")

  private fun protos(
    stairExit: ExitProto = ExitProto(Direction.DOWN, grotto),
    includeCave: Boolean = true,
  ): Prototypes {
    val rooms = buildList {
      add(RoomProto(stair, "Tide Stair", exits = listOf(stairExit)))
      add(RoomProto(grotto, "Grotto"))
      if (includeCave) add(RoomProto(cave, "Drowned Cave"))
    }
    return Prototypes(zones = listOf(ZoneProto("t")), rooms = rooms)
  }

  @Test
  fun `a retarget survives save and restore`() {
    val file = tempDb()
    val a = PersistWorld(file, protos())
    a.w.setExitTo(a.w.room(stair), Direction.DOWN, a.w.room(cave))
    a.save()
    a.close()

    val b = PersistWorld(file, protos())
    assertEquals(cave, b.w.room(stair).exits.getValue(Direction.DOWN).to)
    b.close()
  }

  @Test
  fun `a retarget to a room that no longer exists is dropped on restore`() {
    val file = tempDb()
    val a = PersistWorld(file, protos())
    a.w.setExitTo(a.w.room(stair), Direction.DOWN, a.w.room(cave))
    a.save()
    a.close()

    val b = PersistWorld(file, protos(includeCave = false))
    assertEquals(grotto, b.w.room(stair).exits.getValue(Direction.DOWN).to)
    b.close()
  }

  @Test
  fun `a retarget on an exit that now has a door or is per-crossing is dropped on restore`() {
    val file = tempDb()
    val a = PersistWorld(file, protos())
    a.w.setExitTo(a.w.room(stair), Direction.DOWN, a.w.room(cave))
    a.save()
    a.close()

    val bDoor =
      PersistWorld(
        file,
        protos(ExitProto(Direction.DOWN, grotto, door = DoorProto(DoorState.CLOSED))),
      )
    assertEquals(grotto, bDoor.w.room(stair).exits.getValue(Direction.DOWN).to)
    bDoor.close()

    val bCross =
      PersistWorld(
        file,
        protos(
          ExitProto(
            Direction.DOWN,
            to = null,
            candidates = mapOf(grotto to 1, cave to 1),
            oneWay = true,
          )
        ),
      )
    assertEquals(null, bCross.w.room(stair).exits.getValue(Direction.DOWN).to)
    bCross.close()
  }

  @Test
  fun `an unretargeted exit writes no exitTargets entry`() {
    val file = tempDb()
    val a = PersistWorld(file, protos())
    val row = a.w.encode(a.w.room(stair))!!
    val payload = Json.decodeFromString(RoomPayload.serializer(), row.payload)
    assertTrue(payload.exitTargets.isEmpty())
    a.close()
  }
}
