package gups.script

import gups.core.Label
import gups.core.world.SpawnKey
import gups.core.world.World
import gups.core.world.declaringRoomOf
import gups.core.world.poolOf
import gups.core.world.spawnLabelOf
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Printer
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkList
import net.starlark.java.eval.StarlarkValue

/** Frozen handle naming one spawn rule; equality and hashing follow its [SpawnKey]. */
@StarlarkBuiltin(name = "spawner", doc = "A spawn rule.")
class SpawnerHandle
internal constructor(
  val key: SpawnKey,
  private val label: Label,
  private val room: Label?,
  private val rooms: List<Label>,
) : StarlarkValue {
  @StarlarkMethod(
    name = "id",
    structField = true,
    allowReturnNones = true,
    doc = "The rule's label, or None for a room rule without one.",
  )
  fun id(): Any = (key as? SpawnKey.Named)?.id?.v ?: Starlark.NONE

  @StarlarkMethod(name = "label", structField = true, doc = "The prototype label this rule places.")
  fun label(): String = label.v

  @StarlarkMethod(
    name = "room",
    structField = true,
    allowReturnNones = true,
    doc = "The declaring room's label, or None for a spawner.",
  )
  fun room(): Any = room?.v ?: Starlark.NONE

  @StarlarkMethod(
    name = "rooms",
    structField = true,
    doc = "The pool's room labels, in declared order.",
  )
  fun rooms(): StarlarkList<String> = StarlarkList.immutableCopyOf(rooms.map { it.v })

  override fun isImmutable(): Boolean = true

  override fun repr(printer: Printer) {
    when (val k = key) {
      is SpawnKey.Named -> printer.append("spawner(").repr(k.id.v).append(")")
      is SpawnKey.InRoom ->
        printer
          .append("spawner(room=")
          .repr(k.room.v)
          .append(", label=")
          .repr(k.label.v)
          .append(", n=")
          .append(k.n.toString())
          .append(")")
    }
  }

  override fun equals(other: Any?): Boolean = other is SpawnerHandle && other.key == key

  override fun hashCode(): Int = key.hashCode()
}

/** Builds a frozen [SpawnerHandle] for [key] from [world]'s current spawn state. */
internal fun spawnerHandle(world: World, key: SpawnKey): SpawnerHandle =
  SpawnerHandle(
    key = key,
    label = world.spawnLabelOf(key),
    room = world.declaringRoomOf(key),
    rooms = world.poolOf(key),
  )
