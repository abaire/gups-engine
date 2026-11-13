package gups.core.world

import gups.core.Label
import gups.core.model.Direction
import gups.core.model.DoorProto
import gups.core.model.DoorState
import gups.core.model.ExitProto
import gups.core.model.RoomProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KeylessDoorTest {
  private val a = Label("//t:cell_a")
  private val b = Label("//t:cell_b")
  private val rooms =
    listOf(
      RoomProto(
        a,
        "Cell A",
        exits = listOf(ExitProto(Direction.NORTH, b, door = DoorProto(DoorState.CLOSED))),
      ),
      RoomProto(
        b,
        "Cell B",
        exits = listOf(ExitProto(Direction.SOUTH, a, door = DoorProto(DoorState.CLOSED))),
      ),
    )
  private val f = Fixture(protos = T.protos(extraRooms = rooms))

  @Test
  fun `locking a door without a key fails and leaves both sides unchanged`() {
    val room = f.w.room(a)
    val e =
      assertFailsWith<IllegalArgumentException> {
        f.w.setDoor(room, Direction.NORTH, DoorState.LOCKED)
      }
    assertEquals("//t:cell_a exit north has no lock", e.message)
    assertEquals(DoorState.CLOSED, room.exits.getValue(Direction.NORTH).door!!.state)
    assertEquals(DoorState.CLOSED, f.w.room(b).exits.getValue(Direction.SOUTH).door!!.state)
  }

  @Test
  fun `locking a keyed door locks both sides`() {
    val hall = f.w.room(T.HALL)
    f.w.setDoor(hall, Direction.SOUTH, DoorState.OPEN)
    f.w.setDoor(hall, Direction.SOUTH, DoorState.LOCKED)
    assertEquals(DoorState.LOCKED, hall.exits.getValue(Direction.SOUTH).door!!.state)
    assertEquals(DoorState.LOCKED, f.w.room(T.VAULT).exits.getValue(Direction.NORTH).door!!.state)
  }
}
