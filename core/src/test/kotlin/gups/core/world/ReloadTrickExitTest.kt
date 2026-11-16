package gups.core.world

import gups.core.Label
import gups.core.model.Direction
import gups.core.model.DoorProto
import gups.core.model.DoorState
import gups.core.model.ExitProto
import gups.core.model.RoomProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReloadTrickExitTest {
  private val stair = Label("//t:stair")
  private val grotto = Label("//t:grotto")
  private val cave = Label("//t:cave")

  private fun rooms(stairExit: ExitProto, includeCave: Boolean = true) = buildList {
    add(RoomProto(stair, "Tide Stair", exits = listOf(stairExit)))
    add(RoomProto(grotto, "Grotto"))
    if (includeCave) add(RoomProto(cave, "Drowned Cave"))
  }

  private fun fixture() =
    Fixture(protos = T.protos(extraRooms = rooms(ExitProto(Direction.DOWN, grotto))))

  @Test
  fun `a retarget survives a reload that keeps the exit and target room`() {
    val f = fixture()
    f.w.setExitTo(f.w.room(stair), Direction.DOWN, f.w.room(cave))
    f.w.applyPrototypes(T.protos(extraRooms = rooms(ExitProto(Direction.DOWN, grotto))))
    assertEquals(cave, f.w.room(stair).exits.getValue(Direction.DOWN).to)
  }

  @Test
  fun `a retarget is dropped when a reload adds a door to the exit`() {
    val f = fixture()
    f.w.setExitTo(f.w.room(stair), Direction.DOWN, f.w.room(cave))
    f.w.applyPrototypes(
      T.protos(
        extraRooms = rooms(ExitProto(Direction.DOWN, grotto, door = DoorProto(DoorState.CLOSED)))
      )
    )
    assertEquals(grotto, f.w.room(stair).exits.getValue(Direction.DOWN).to)
  }

  @Test
  fun `a retarget is dropped when a reload makes the exit per-crossing`() {
    val f = fixture()
    f.w.setExitTo(f.w.room(stair), Direction.DOWN, f.w.room(cave))
    f.w.applyPrototypes(
      T.protos(
        extraRooms =
          rooms(
            ExitProto(
              Direction.DOWN,
              to = null,
              candidates = mapOf(grotto to 1, cave to 1),
              oneWay = true,
            )
          )
      )
    )
    assertNull(f.w.room(stair).exits.getValue(Direction.DOWN).to)
  }

  @Test
  fun `a retarget is dropped when the target room is removed on reload`() {
    val f = fixture()
    f.w.setExitTo(f.w.room(stair), Direction.DOWN, f.w.room(cave))
    f.w.applyPrototypes(
      T.protos(extraRooms = rooms(ExitProto(Direction.DOWN, grotto), includeCave = false))
    )
    assertEquals(grotto, f.w.room(stair).exits.getValue(Direction.DOWN).to)
  }
}
