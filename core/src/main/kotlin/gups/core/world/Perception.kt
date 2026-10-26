package gups.core.world

import gups.core.EntityId
import gups.core.model.Actor
import gups.core.model.Channel
import gups.core.model.Exit
import gups.core.model.Mob
import gups.core.model.Room
import gups.core.model.Sense

// Adjacent-room perception. Events are queued as they happen and delivered to
// listening mobs' `on_nearby` in phase 5 of the pulse.

/**
 * One perceivable event in [room]: `say`, `emote`, `shout`, `combat_start`, `combat`, `death`,
 * `enter` or `exit`.
 */
internal class Perception(
  val event: String,
  val channel: Channel,
  val room: Room,
  val actor: Actor?,
  val text: String?,
)

/** Queues an event for phase 5. Whispers and tells are never perceived, so they never get here. */
internal fun World.perceive(
  event: String,
  channel: Channel,
  room: Room,
  actor: Actor?,
  text: String? = null,
) {
  perceptions += Perception(event, channel, room, actor, text)
}

/** The sense that picks up [channel] from a neighbouring room. */
private fun senseFor(channel: Channel): Sense =
  if (channel == Channel.SIGHT) Sense.SIGHT else Sense.HEARING

/** Exits leading into each room, by the room they start from; rebuilt after a reload. */
internal fun World.inbound(room: Room): List<Pair<Room, Exit>> {
  val index =
    inboundCache
      ?: HashMap<Room, MutableList<Pair<Room, Exit>>>().also { idx ->
        for (r in roomsByLabel.values) {
          for (e in r.exits.values) {
            val to = roomsByLabel[e.to] ?: continue
            if (to !== r) idx.getOrPut(to) { mutableListOf() } += r to e
          }
        }
        inboundCache = idx
      }
  return index[room].orEmpty()
}

/**
 * Phase 5: delivers this pulse's events to simulated mobs that have an `on_nearby` hook. A mob
 * hears events in its own room (with no `via_exit`), and in rooms its own exits lead to when its
 * sense for the channel reaches 1 and that exit's effective blocks let the channel through. Events
 * of one type from one room reach a mob at most once a pulse. Events raised by the hooks themselves
 * are delivered the next pulse.
 */
internal fun World.deliverPerceptions() {
  if (perceptions.isEmpty()) return
  val events = perceptions.toList()
  perceptions.clear()
  class Delivery(val mob: Mob, val ev: Perception, val via: Exit?)
  val out = LinkedHashMap<Triple<EntityId, String, EntityId>, Delivery>()
  fun offer(mob: Mob, ev: Perception, via: Exit?) {
    if (
      mob === ev.actor ||
        !mob.inWorld ||
        !isSimulated(mob) ||
        !scripts.hasHook(mob, HookName.ON_NEARBY)
    )
      return
    out.putIfAbsent(Triple(mob.id, ev.event, ev.room.id), Delivery(mob, ev, via))
  }
  for (ev in events) {
    for (a in ev.room.actorIndex.values) if (a is Mob) offer(a, ev, null)
    for ((from, exit) in inbound(ev.room)) {
      if (ev.channel in exit.effectiveSensesBlocked) continue
      for (a in from.actorIndex.values) {
        if (a is Mob && (a.proto.senses[senseFor(ev.channel)] ?: 0) >= 1) offer(a, ev, exit)
      }
    }
  }
  for (d in out.values) {
    if (!d.mob.inWorld) continue
    val ctx =
      HookContext(
        actor = d.ev.actor?.takeIf { it.inWorld },
        room = d.mob.room,
        text = d.ev.text,
        event = d.ev.event,
        sourceRoom = d.ev.room,
        viaExit = d.via?.direction,
      )
    invokeOn(d.mob, HookName.ON_NEARBY, ctx)
  }
}
