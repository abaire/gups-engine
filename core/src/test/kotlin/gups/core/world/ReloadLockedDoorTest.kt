package gups.core.world

import gups.core.Label
import gups.core.model.Direction
import gups.core.model.DoorProto
import gups.core.model.DoorState
import gups.core.model.ExitProto
import gups.core.model.RoomProto
import kotlin.test.Test
import kotlin.test.assertEquals

class ReloadLockedDoorTest {
  private val a = Label("//t:cell_a")
  private val b = Label("//t:cell_b")

  private fun rooms(key: Label?) =
    listOf(
      RoomProto(
        a,
        "Cell A",
        exits = listOf(ExitProto(Direction.NORTH, b, door = DoorProto(DoorState.CLOSED, key = key))),
      ),
      RoomProto(
        b,
        "Cell B",
        exits = listOf(ExitProto(Direction.SOUTH, a, door = DoorProto(DoorState.CLOSED, key = key))),
      ),
    )

  private fun fixture() = Fixture(protos = T.protos(extraRooms = rooms(T.KEY)))

  private fun Fixture.reloadWith(key: Label?) {
    w.applyPrototypes(T.protos(extraRooms = rooms(key)))
  }

  private fun Fixture.state(room: Label, dir: Direction) =
    w.room(room).exits.getValue(dir).door!!.state

  @Test
  fun `a door locked in play comes back closed when its prototype drops the key`() {
    val f = fixture()
    f.w.setDoor(f.w.room(a), Direction.NORTH, DoorState.LOCKED)
    f.reloadWith(null)
    assertEquals(DoorState.CLOSED, f.state(a, Direction.NORTH))
    assertEquals(DoorState.CLOSED, f.state(b, Direction.SOUTH))
  }

  @Test
  fun `a door locked in play stays locked when its prototype keeps the key`() {
    val f = fixture()
    f.w.setDoor(f.w.room(a), Direction.NORTH, DoorState.LOCKED)
    f.reloadWith(T.KEY)
    assertEquals(DoorState.LOCKED, f.state(a, Direction.NORTH))
    assertEquals(DoorState.LOCKED, f.state(b, Direction.SOUTH))
  }
}
