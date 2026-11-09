package gups.core.world

import gups.core.BUBBLE_RADIUS
import gups.core.EntityId
import gups.core.model.Actor
import gups.core.model.Channel
import gups.core.model.Exit
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Room
import gups.core.rules.RulesContext
import java.util.PriorityQueue

// Perception. Events are queued as they happen and spread through the room graph in phase 5 of
// the pulse, losing strength on each exit they cross.

/** The initial intensity of the sound of a combat round, a combat start and a death. */
internal const val COMBAT_INTENSITY = 2

/** The initial intensity of a `shout`. */
internal const val SHOUT_INTENSITY = 3

/**
 * One perceivable event in [room]: `say`, `emote`, `shout`, `combat_start`, `combat`, `death`,
 * `enter`, `exit`, a door or container `open`, `close`, `lock` or `unlock`, or one a script emits.
 * [alsoAt] is a second room it starts in, at the same strength: the other side of a door. With
 * [tellRoom], players in [room] are sent `rules.perceive_message` too, instead of being told as it
 * happens.
 */
internal class Perception(
  val event: String,
  val channel: Channel,
  val room: Room,
  val actor: Actor?,
  val text: String?,
  val intensity: Int,
  val alsoAt: Room? = null,
  val tellRoom: Boolean = false,
)

/** Queues an event for phase 5. Whispers and tells are never perceived, so they never get here. */
fun World.perceive(
  event: String,
  channel: Channel,
  room: Room,
  actor: Actor?,
  text: String? = null,
  intensity: Int = 1,
  alsoAt: Room? = null,
  tellRoom: Boolean = false,
) {
  if (intensity <= 0) return
  perceptions +=
    Perception(
      event,
      channel,
      room,
      actor,
      text,
      intensity,
      alsoAt?.takeIf { it !== room },
      tellRoom,
    )
}

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
 * A room an event reaches: its cheapest total [cost], the exit of [room] leading back along that
 * path, and the [origin] room the path starts from.
 */
private class Reach(val room: Room, val cost: Int, val via: Exit?, val origin: Room)

/**
 * The rooms [ev] reaches within [BUBBLE_RADIUS] exits, cheapest path first. Every hop is priced by
 * `rules.sense_cost` for the room the signal enters and its exit back toward the source; a negative
 * price blocks the hop. [prices] remembers the answers for one delivery pass. An event with two
 * origins starts in both at cost 0, and each room is reached from whichever is cheaper.
 *
 * A room's reach is its cheapest path among those of at most [BUBBLE_RADIUS] exits, so a cheaper
 * path that is too long never hides a dearer one that fits. The search therefore keeps the best
 * cost per room and number of exits used.
 */
private fun World.spread(
  ev: Perception,
  prices: MutableMap<Triple<EntityId, String, String>, Int>,
): List<Reach> {
  class Node(val room: Room, val cost: Int, val hops: Int, val via: Exit?, val origin: Room)
  val best = HashMap<Room, IntArray>()
  fun costs(room: Room) = best.getOrPut(room) { IntArray(BUBBLE_RADIUS + 1) { Int.MAX_VALUE } }
  val queue = PriorityQueue<Node>(compareBy<Node> { it.cost }.thenBy { it.hops })
  for (o in listOfNotNull(ev.room, ev.alsoAt)) {
    costs(o)[0] = 0
    queue += Node(o, 0, 0, null, o)
  }
  val seen = HashSet<Room>()
  val out = mutableListOf<Reach>()
  while (queue.isNotEmpty()) {
    val node = queue.poll()
    if (node.cost > costs(node.room)[node.hops]) continue
    if (seen.add(node.room)) out += Reach(node.room, node.cost, node.via, node.origin)
    if (node.hops == BUBBLE_RADIUS) continue
    for ((neighbour, back) in inbound(node.room)) {
      val key = Triple(neighbour.id, back.direction.name, ev.channel.name)
      val step =
        prices.getOrPut(key) {
          rules.senseCost(RulesContext(this, room = neighbour, exit = back, channel = ev.channel))
        }
      if (step < 0) continue
      val next = node.cost + step
      val hops = node.hops + 1
      val known = costs(neighbour)
      // A path that is no cheaper than one with the same or fewer exits can never do better.
      if ((0..hops).any { known[it] <= next }) continue
      known[hops] = next
      queue += Node(neighbour, next, hops, back, node.origin)
    }
  }
  return out
}

/**
 * How a player perceives an event: on [channel]; [text] is perceive_message's text for a world
 * channel.
 */
internal data class Perceived(val channel: Channel, val text: String?)

/**
 * The players of [room] (except [exclude]) who would be told an event rendered on [channels], with
 * the first channel each passes can_perceive for and that has text. Sends nothing.
 *
 * Channels are tried strongest first, in the world's perception order. A channel counts when
 * `rules.can_perceive` lets the player notice it and there is text for it: the channel is in
 * [channels], or it is a channel the world declares and `rules.perceive_message` returns something
 * for it (kept in [Perceived.text]), with null or empty meaning nothing to say.
 */
internal fun World.perceiversOf(
  room: Room,
  event: String,
  source: Actor?,
  channels: Set<Channel>,
  intensity: Int = 1,
  eventText: String? = null,
  vararg exclude: Actor,
): Map<Player, Perceived> {
  val out = LinkedHashMap<Player, Perceived>()
  for (a in room.actorIndex.values) {
    if (a !is Player || exclude.any { it === a }) continue
    for (channel in schema.perceptionOrder) {
      val c =
        RulesContext(
          this,
          actor = a,
          target = a,
          source = source,
          room = room,
          sourceRoom = room,
          channel = channel,
          event = event,
          intensity = intensity,
          cost = 0,
          text = eventText,
        )
      if (!rules.canPerceive(c)) continue
      if (channel in channels) {
        out[a] = Perceived(channel, null)
        break
      }
      if (channel in Channel.BUILT_IN) continue
      val line = rules.perceiveMessage(c)
      if (line.isNullOrEmpty()) continue
      out[a] = Perceived(channel, line)
      break
    }
  }
  return out
}

/**
 * Tells each player in [room] except [exclude] about [event] as it happens, with the first thing
 * they perceive, and returns the channel each was told through. The built-in commands tell their
 * own room immediately so reactions appear in order. See [perceiversOf] for who is told and how.
 */
internal fun World.tellPerceivers(
  room: Room,
  event: String,
  source: Actor?,
  renderings: Map<Channel, String>,
  intensity: Int = 1,
  eventText: String? = null,
  vararg exclude: Actor,
): Map<Player, Channel> {
  val told = LinkedHashMap<Player, Channel>()
  for ((p, seen) in
    perceiversOf(room, event, source, renderings.keys, intensity, eventText, *exclude)) {
    send(p, renderings[seen.channel] ?: seen.text.orEmpty())
    told[p] = seen.channel
  }
  return told
}

/** [tellPerceivers] for an event that is only seen: it has text on `sight` and no sound. */
internal fun World.tellSight(
  room: Room,
  event: String,
  source: Actor?,
  line: String,
  vararg exclude: Actor,
) {
  tellPerceivers(room, event, source, mapOf(Channel.SIGHT to line), exclude = exclude)
}

/**
 * Phase 5: spreads this pulse's events from their rooms, and tells each simulated actor that
 * `rules.can_perceive` lets notice one. A mob with an `on_perceive` hook runs it; a player in
 * another room is sent `rules.perceive_message`, and a shout's `Comm.Channel.Text` carries that
 * same text (the room of the event itself is told as it happens, unless the event has `tellRoom`).
 * One actor gets an event of one type from one room at most once a pulse, the strongest copy.
 * Events raised by the hooks themselves are delivered the next pulse.
 */
internal fun World.deliverPerceptions() {
  if (perceptions.isEmpty()) return
  val events = perceptions.toList()
  perceptions.clear()
  class Delivery(val actor: Actor, val ev: Perception, val reach: Reach)
  val out = LinkedHashMap<Triple<EntityId, String, EntityId>, Delivery>()
  val prices = HashMap<Triple<EntityId, String, String>, Int>()
  for (ev in events) {
    for (reach in spread(ev, prices)) {
      for (a in reach.room.actorIndex.values) {
        if (a === ev.actor || !a.inWorld || !isSimulated(a)) continue
        val key = Triple(a.id, ev.event, ev.room.id)
        val held = out[key]
        if (held == null || ev.intensity - reach.cost > held.ev.intensity - held.reach.cost)
          out[key] = Delivery(a, ev, reach)
      }
    }
  }
  for (d in out.values) {
    val a = d.actor
    val ev = d.ev
    if (!a.inWorld) continue
    // Only ask the rules when the answer would be used.
    val runsHook = a is Mob && scripts.hasHook(a, HookName.ON_PERCEIVE)
    val isTold = a is Player && (d.reach.via != null || ev.tellRoom)
    val records =
      a is Mob &&
        ev.actor != null &&
        memoryPulsesOf(a) > 0 &&
        !(ev.event == "enter" && d.reach.origin === a.room)
    if (!runsHook && !isTold && !records) continue
    val source = ev.actor?.takeIf { it.inWorld }
    val c =
      RulesContext(
        this,
        actor = a,
        target = a,
        source = source,
        room = a.room,
        sourceRoom = d.reach.origin,
        viaExit = d.reach.via?.direction,
        channel = ev.channel,
        event = ev.event,
        intensity = ev.intensity,
        cost = d.reach.cost,
        text = ev.text,
      )
    if (!rules.canPerceive(c)) continue
    if (records) remember(a, ev.actor, ev.event, channel = ev.channel)
    if (runsHook) {
      invokeOn(
        a,
        HookName.ON_PERCEIVE,
        HookContext(
          actor = source,
          room = a.room,
          text = ev.text,
          event = ev.event,
          channel = ev.channel.name,
          intensity = ev.intensity,
          cost = d.reach.cost,
          sourceRoom = d.reach.origin,
          viaExit = d.reach.via?.direction,
        ),
      )
    }
    if (isTold) {
      val msg = rules.perceiveMessage(c)
      if (!msg.isNullOrEmpty()) {
        send(a, msg)
        if (ev.event == "shout" && ev.actor != null) channel("shout", ev.actor, msg, setOf(a))
      }
    }
  }
}
