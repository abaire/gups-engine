package gups.script

import gups.core.model.DoorState
import gups.core.model.Entity
import gups.core.world.HookContext
import gups.core.world.setContainerState
import gups.core.world.setDoor
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread

/** Doors, exits and containers. */
abstract class CtxDoors
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxItems(frame, owner, hc, extras) {
  internal fun door(
    room: Any,
    dir: Any,
    thread: StarlarkThread,
    name: String,
    to: (DoorState) -> DoorState,
  ): Any =
    verb(name) {
      val r = roomArg(room, "$name(room)", thread)
      val d = dir(dir, "$name(dir)")
      val door = r.exits[d]?.door ?: fail("$name(): ${r.label} has no door $d")
      world.setDoor(r, d, to(door.state))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "open_door",
    doc = "Opens the door in direction dir, even if locked.",
    parameters =
      [
        Param(name = "room", named = true, doc = "Room, or room label, that has the exit."),
        Param(name = "dir", named = true, doc = "Direction of the exit."),
      ],
    useStarlarkThread = true,
  )
  fun openDoor(room: Any, dir: Any, thread: StarlarkThread): Any =
    door(room, dir, thread, "open_door") { DoorState.OPEN }

  @StarlarkMethod(
    name = "close_door",
    doc = "Closes an open door in direction dir.",
    parameters =
      [
        Param(name = "room", named = true, doc = "Room, or room label, that has the exit."),
        Param(name = "dir", named = true, doc = "Direction of the exit."),
      ],
    useStarlarkThread = true,
  )
  fun closeDoor(room: Any, dir: Any, thread: StarlarkThread): Any =
    door(room, dir, thread, "close_door") { if (it == DoorState.LOCKED) it else DoorState.CLOSED }

  @StarlarkMethod(
    name = "lock_door",
    doc = "Closes and locks the door.",
    parameters =
      [
        Param(name = "room", named = true, doc = "Room, or room label, that has the exit."),
        Param(name = "dir", named = true, doc = "Direction of the exit."),
      ],
    useStarlarkThread = true,
  )
  fun lockDoor(room: Any, dir: Any, thread: StarlarkThread): Any =
    door(room, dir, thread, "lock_door") { DoorState.LOCKED }

  @StarlarkMethod(
    name = "unlock_door",
    doc = "Unlocks the door, leaving it closed.",
    parameters =
      [
        Param(name = "room", named = true, doc = "Room, or room label, that has the exit."),
        Param(name = "dir", named = true, doc = "Direction of the exit."),
      ],
    useStarlarkThread = true,
  )
  fun unlockDoor(room: Any, dir: Any, thread: StarlarkThread): Any =
    door(room, dir, thread, "unlock_door") { if (it == DoorState.LOCKED) DoorState.CLOSED else it }

  internal fun container(item: Any, name: String, to: (DoorState) -> DoorState): Any =
    verb(name) {
      val i = itemArg(item, "$name(item)")
      val box = i.container ?: fail("$name(item): ${i.name} is not a container")
      world.setContainerState(i, to(box.state))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "open_container",
    doc = "Opens the container, even if locked.",
    parameters = [Param(name = "item", named = true, doc = "Container item to open.")],
  )
  fun openContainer(item: Any): Any = container(item, "open_container") { DoorState.OPEN }

  @StarlarkMethod(
    name = "close_container",
    doc = "Closes an open container; a locked one stays locked.",
    parameters = [Param(name = "item", named = true, doc = "Container item to close.")],
  )
  fun closeContainer(item: Any): Any =
    container(item, "close_container") { if (it == DoorState.LOCKED) it else DoorState.CLOSED }

  @StarlarkMethod(
    name = "lock_container",
    doc =
      "Closes and locks the container. Only a container whose prototype names a key has a lock.",
    parameters = [Param(name = "item", named = true, doc = "Container item to lock.")],
  )
  fun lockContainer(item: Any): Any = container(item, "lock_container") { DoorState.LOCKED }

  @StarlarkMethod(
    name = "unlock_container",
    doc = "Unlocks the container, leaving it closed.",
    parameters = [Param(name = "item", named = true, doc = "Container item to unlock.")],
  )
  fun unlockContainer(item: Any): Any =
    container(item, "unlock_container") { if (it == DoorState.LOCKED) DoorState.CLOSED else it }
}
