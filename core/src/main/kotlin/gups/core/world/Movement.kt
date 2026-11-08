package gups.core.world

import gups.core.model.Actor
import gups.core.model.Channel
import gups.core.model.Direction
import gups.core.model.Exit
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Room
import gups.core.rules.RulesContext

// Room transitions and the tactical track.

/** The arrival index for [exit] in [to]: `-1` maps to the back; clamped to the track. */
fun entryIndexFor(exit: Exit, to: Room): Int =
  (if (exit.entryIndex < 0) to.trackSize - 1 else exit.entryIndex).coerceIn(0, to.trackSize - 1)

/**
 * Moves [actor] through [exit]: ends its engagements, places it at the entry index, and fires
 * `on_disengage` with [reason] for whoever that freed, then `on_exit`/`on_enter`. Callers inside an
 * initiative tier go through [deferRemoval].
 */
internal fun World.transition(
  actor: Actor,
  exit: Exit,
  leave: Msg = KernelMsg.MOVE_LEAVES,
  reason: String = "stop",
) {
  if (!actor.inWorld) return
  val from = actor.room
  val to = room(exit.to)
  val freed = unlinkAll(actor)
  tellPerceivers(
    from,
    "exit",
    actor,
    mapOf(Channel.SIGHT to msg(leave, "actor" to actor.name.cap(), "direction" to exit.direction)),
    exclude = arrayOf(actor),
  )
  placeActor(actor, to, entryIndexFor(exit, to))
  if (actor is Mob) remember(actor, actor, "arrive")
  tellPerceivers(
    to,
    "enter",
    actor,
    mapOf(Channel.SIGHT to msg(KernelMsg.MOVE_ARRIVES, "actor" to actor.name.cap())),
    exclude = arrayOf(actor),
  )
  if (actor is Player) send(actor, describeRoom(actor))
  perceive("exit", Channel.SIGHT, from, actor)
  perceive("enter", Channel.SIGHT, to, actor)
  witness(to, actor, "enter")
  fireDisengage(freed, reason)
  dispatch(
    HookName.ON_EXIT,
    null,
    from,
    HookContext(actor = actor, direction = exit.direction, toRoom = to),
  )
  dispatch(
    HookName.ON_ENTER,
    null,
    to,
    HookContext(actor = actor, direction = exit.direction, fromRoom = from),
  )
}

/** The `teleport` verb: engine-validated, fires the exit and enter hooks. */
fun World.teleport(actor: Actor, to: Room, index: Int? = null) {
  val from = actor.room
  val req =
    gups.core.model.RestrictionRequest(
      point = gups.core.model.RestrictionPoint.ENTER,
      actor = actor,
      room = to,
      from = from,
      direction = null,
    )
  val refused = restrictionRefusal(gups.core.model.RestrictionPoint.ENTER, req)
  if (refused != null) {
    if (actor is Player) send(actor, refused)
    return
  }
  deferRemoval(actor, Deferred.Kind.TRANSITION) {
    if (!actor.inWorld) return@deferRemoval
    val from = actor.room
    val freed = unlinkAll(actor)
    tellPerceivers(
      from,
      "exit",
      actor,
      mapOf(Channel.SIGHT to msg(KernelMsg.TELEPORT_VANISHES, "actor" to actor.name.cap())),
      exclude = arrayOf(actor),
    )
    placeActor(actor, to, index ?: 0)
    if (actor is Mob) remember(actor, actor, "arrive")
    tellPerceivers(
      to,
      "enter",
      actor,
      mapOf(Channel.SIGHT to msg(KernelMsg.TELEPORT_APPEARS, "actor" to actor.name.cap())),
      exclude = arrayOf(actor),
    )
    if (actor is Player) send(actor, describeRoom(actor))
    perceive("exit", Channel.SIGHT, from, actor)
    perceive("enter", Channel.SIGHT, to, actor)
    witness(to, actor, "enter")
    fireDisengage(freed, "stop")
    dispatch(HookName.ON_EXIT, null, from, HookContext(actor = actor, toRoom = to))
    dispatch(HookName.ON_ENTER, null, to, HookContext(actor = actor, fromRoom = from))
  }
}

/**
 * Guard denial, then `before_exit` on the room [actor] is in and `before_enter` on the room through
 * [dir]; null to allow, else the denial message. Walking and fleeing both check it.
 */
internal fun World.exitDenial(actor: Actor, dir: Direction): String? {
  val from = roomOf(actor)
  val to = room(from.exits.getValue(dir).to)
  val req =
    gups.core.model.RestrictionRequest(
      point = gups.core.model.RestrictionPoint.ENTER,
      actor = actor,
      room = to,
      from = from,
      direction = dir,
    )
  // Guards are built-in before_exit observers.
  return guardDenial(actor, from, dir)
    ?: dispatch(
      HookName.BEFORE_EXIT,
      null,
      from,
      HookContext(actor = actor, direction = dir, toRoom = to),
    )
    ?: restrictionRefusal(gups.core.model.RestrictionPoint.ENTER, req)
    ?: dispatch(
      HookName.BEFORE_ENTER,
      null,
      to,
      HookContext(actor = actor, direction = dir, fromRoom = from),
    )
}

/** Walking through an exit: one action plus the vitals `move_cost` spends. */
internal class MoveAction(private val dir: Direction) : Action {
  override fun validate(w: World, actor: Actor): String? {
    val exit = w.roomOf(actor).exits[dir] ?: return w.msg(KernelMsg.MOVE_NO_EXIT)
    if (!exit.passable) return w.msg(KernelMsg.MOVE_CLOSED, "direction" to dir)
    if (w.isPinned(actor)) return w.msg(TrackMsg.PINNED)
    if (w.moveCost(RulesContext(w, actor)).any { (vital, cost) -> actor.current(vital) < cost })
      return w.msg(KernelMsg.MOVE_EXHAUSTED)
    return null
  }

  override fun veto(w: World, actor: Actor): String? = w.exitDenial(actor, dir)

  override fun resolve(w: World, actor: Actor): Int {
    val exit = w.roomOf(actor).exits.getValue(dir)
    w.changeVitals(actor, w.moveCost(RulesContext(w, actor)).mapValues { -it.value })
    w.deferRemoval(actor, Deferred.Kind.TRANSITION) { w.transition(actor, exit) }
    return 0
  }
}
