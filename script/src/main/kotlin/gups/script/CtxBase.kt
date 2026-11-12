package gups.script

import gups.core.model.Actor
import gups.core.model.Direction
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Room
import gups.core.world.HookContext
import gups.core.world.InvariantViolation
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue

/**
 * Shared plumbing of [Ctx]: the hook invocation state, the verb wrapper and argument conversion.
 * [Ctx] is split into a chain of classes only to keep each file small; Starlark sees one `ctx`.
 */
abstract class CtxBase
internal constructor(
  internal val frame: Frame,
  internal val owner: Entity,
  internal val hc: HookContext,
  internal val extras: CtxExtras,
) : StarlarkValue {
  internal val world
    get() = frame.world

  internal fun <T> field(block: () -> T): T {
    frame.check()
    return block()
  }

  /** Every verb: validity, the wall-clock backstop, tracing, and engine errors as script errors. */
  internal fun <T> verb(name: String, detail: String = "", block: () -> T): T {
    frame.check()
    FeatureOwnership.refused("ctx.$name", "ctx.$name()", world.features)?.let { fail(it) }
    frame.checkClock()
    frame.trace("  $name($detail)")
    try {
      return block()
    } catch (e: InvariantViolation) {
      throw e
    } catch (e: IllegalArgumentException) {
      fail("ctx.$name(): ${e.message}")
    } catch (e: IllegalStateException) {
      fail("ctx.$name(): ${e.message}")
    } catch (e: NoSuchElementException) {
      fail("ctx.$name(): ${e.message}")
    }
  }

  internal fun v(e: Entity?): Any = view(frame, e)

  /** A non-zero track [index] needs the track feature. */
  internal fun trackIndex(verb: String, index: Any?) {
    val i = intOrNull(index, "$verb(index)") ?: return
    if (i != 0)
      FeatureOwnership.refused("ctx.$verb.index", "ctx.$verb(index)", world.features)?.let {
        fail(it)
      }
  }

  internal fun pkgOf(thread: StarlarkThread): String = callerFile(thread)?.pkg ?: frame.pkg

  /** The in-world actor that applied the affect whose hook is running, or null. */
  internal fun affectApplier(): Actor? = hc.affect?.source?.let(world::actor)?.takeIf { it.inWorld }

  internal fun actorArg(x: Any?, what: String): Actor {
    val a = (x as? ActorView)?.actor ?: fail("$what: expected an actor, got ${typeName(x)}")
    if (!a.inWorld) fail("$what: ${a.name} is no longer in the world")
    return a
  }

  internal fun mobArg(x: Any?, what: String): Mob =
    actorArg(x, what) as? Mob ?: fail("$what: mob actions apply only to mobs, never players")

  internal fun playerArg(x: Any?, what: String): Player =
    actorArg(x, what) as? Player ?: fail("$what: must be a player")

  /** [actorArg], or null for `None`. */
  internal fun actorOrNull(x: Any?, what: String): Actor? =
    if (isNone(x)) null else actorArg(x, what)

  /** [itemArg], or null for `None`. */
  internal fun itemOrNull(x: Any?, what: String): Item? = if (isNone(x)) null else itemArg(x, what)

  internal fun itemArg(x: Any?, what: String): Item {
    val i = (x as? ItemView)?.item ?: fail("$what: expected an item, got ${typeName(x)}")
    if (world.item(i.id) !== i) fail("$what: ${i.name} is no longer in the world")
    return i
  }

  internal fun roomArg(x: Any?, what: String, thread: StarlarkThread): Room =
    when (x) {
      is RoomView -> x.room
      is String ->
        world.roomOrNull(label(x, pkgOf(thread), what)) ?: fail("$what: there is no room $x")
      else -> fail("$what: expected a room or a room label, got ${typeName(x)}")
    }

  internal fun entityArg(x: Any?, what: String): Entity =
    when (x) {
      is ActorView -> actorArg(x, what)
      is ItemView -> itemArg(x, what)
      is RoomView -> x.room
      else -> fail("$what: expected an actor, room or item, got ${typeName(x)}")
    }

  /** An actor, item or room view whose entity is still in the world; null for a stale view. */
  internal fun hookOwner(x: Any?, what: String): Entity? =
    when (x) {
      is ActorView -> if (x.actor.inWorld) x.actor else null
      is ItemView -> world.item(x.item.id)
      is RoomView -> x.room
      else -> fail("$what: expected an actor, item or room, got ${typeName(x)}")
    }

  internal fun text(x: Any?, what: String): String =
    checkString(str(x, what), what, frame.host.limits)

  internal fun dir(x: Any?, what: String): Direction = Direction.parse(str(x, what))

  /** A destination: a room (ground, at [index]), an actor (carried) or a container. */
  internal fun location(at: Any?, index: Any?, what: String, thread: StarlarkThread): ItemLocation =
    when (at) {
      is ActorView -> ItemLocation.Carried(actorArg(at, what).id)
      is ItemView -> ItemLocation.Contained(itemArg(at, what).id)
      else ->
        ItemLocation.InRoom(roomArg(at, what, thread).id, intOrNull(index, "$what index") ?: 0)
    }
}
