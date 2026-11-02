package gups.core.world

import gups.core.model.Channel
import gups.core.model.Entity
import gups.core.model.Exit
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Mob
import gups.core.model.MobProto
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.Room
import gups.core.model.RoomProto

/** What a prototype swap did to live instances, for the reload report. */
data class ReconcileReport(val created: List<Room>, val notes: List<String>)

/**
 * Swaps in [next] after a reload and reconciles live instances against it.
 * - **Rooms** keep their identity and contents. Their static data comes from the new prototype.
 *   Door states and exit blocks the old prototype set are taken from the new one; ones changed in
 *   play are kept.
 * - **Mobs** keep their state, except for a field still equal to its old prototype value, which
 *   takes the new value. That is the instance delta, computed on the spot.
 * - **Deleted prototypes:** mobs and items on the ground are despawned. Items in a player's custody
 *   become inert tombstones. Players in a deleted room move to their recall room, or else the first
 *   room.
 */
fun World.applyPrototypes(next: Prototypes): ReconcileReport {
  val old = protos
  protos = next
  val notes = mutableListOf<String>()
  val created = mutableListOf<Room>()

  for ((label, p) in next.rooms) {
    val room = roomsByLabel[label]
    if (room != null) rebind(room, p, old.rooms[label])
    else
      created +=
        Room(allocateId(), p, Feature.TRACK !in features).also {
          register(it)
          roomsByLabel[label] = it
        }
  }
  val fallback = roomsByLabel.values.first()
  for (gone in roomsByLabel.values.filter { it.label !in next.rooms }) {
    for (a in gone.actors) {
      when (a) {
        is Player -> {
          val to = roomOrNull(a.recallRoom)?.takeIf { it.label in next.rooms } ?: fallback
          if (a.recallRoom !in next.rooms) a.recallRoom = fallback.label
          disengageAll(a)
          placeActor(a, to, 0)
          tell(a, KernelMsg.RECONCILE_SHIFT)
          send(a, describeRoom(a))
        }
        is Mob -> despawn(a)
      }
    }
    for (i in gone.items) destroy(i)
    roomsByLabel.remove(gone.label)
    entities.remove(gone.id)
    removed += gone.id
    notes += "room ${gone.label} removed"
  }
  for (room in roomsByLabel.values) {
    for (a in room.actors) if (a.trackIndex >= room.trackSize)
      placeActor(a, room, room.trackSize - 1)
    for (i in room.items) i.groundIndex?.let {
      if (it >= room.trackSize) moveItem(i, ItemLocation.InRoom(room.id, room.trackSize - 1))
    }
  }

  val refill = mutableMapOf<Mob, Set<String>>()
  for (m in actorIndex.values.filterIsInstance<Mob>()) {
    val p = next.mobs[m.protoLabel]
    if (p == null) {
      notes += "mob ${m.protoLabel} ($m) despawned: its prototype is gone"
      despawn(m)
    } else {
      refill[m] = rebind(m, p, old.mobs[m.protoLabel] ?: m.proto)
    }
  }
  for (a in actorIndex.values) {
    a.skills.keys.retainAll { it in next.skills }
    a.recomputeTraits(this)
  }

  for (item in entities.values.filterIsInstance<Item>().sortedBy { it.id }) {
    if (item.id !in entities) continue
    val p = next.items[item.protoLabel]
    when {
      p != null -> {
        item.proto = p
        upgrade(item, p.version)
      }
      item.proto.kind == TOMBSTONE -> Unit
      custodian(item) is Player -> {
        item.proto = tombstone(item.proto)
        notes += "item ${item.protoLabel} ($item) became a tombstone"
      }
      else -> {
        notes += "item ${item.protoLabel} ($item) despawned: its prototype is gone"
        destroy(item)
      }
    }
    item.dirty = true
  }

  rebuildSpawns()
  for (room in created) for (s in spawnStates) if (s.room === room) fillSpawn(s)

  zoneResets.keys.retainAll(next.zones.keys)
  for (zone in next.zones.values) {
    val every = zone.resetPulses
    if (every == null) zoneResets.remove(zone.pkg)
    else zoneResets.putIfAbsent(zone.pkg, pulse + every)
  }
  // The room graph may have changed: paths, neighbours and the bubble are worked out again.
  graphChanged()
  inboundCache = null
  slotCache = null
  slotFamilyCache = null
  for (a in actorIndex.values) {
    forgetActorSlots(a)
    askWearRestriction(a)
    refreshHoldings(a)
  }
  // Refilled only now that the new traits and item prototypes count towards the maxima.
  for ((m, names) in refill) if (m.inWorld) {
    for (name in names) m.vitalMap.getValue(name).current = m.max(name)
    m.dirty = true
  }
  coverage.keys.retainAll(roomsByLabel.values.toSet())
  recomputeBubble()
  return ReconcileReport(created, notes)
}

/** Kind of the inert items left when a carried item's prototype is deleted. */
const val TOMBSTONE = "tombstone"

/** The inert prototype an item falls back to when its own is deleted. */
fun tombstone(was: ItemProto) =
  ItemProto(
    label = was.label,
    name = was.name,
    keywords = was.keywords,
    desc = "A lifeless husk of something that no longer exists. You can drop it.",
    kind = TOMBSTONE,
    weight = was.weight,
    stackable = was.stackable,
    container = was.container,
    plural = was.plural,
  )

private fun World.despawn(m: Mob) {
  disengageAll(m)
  m.casting = null
  m.actionQueue.clear()
  removeActor(m)
}

private fun World.rebind(room: Room, p: RoomProto, was: RoomProto?) {
  val oldExits = room.exits
  room.proto = p
  upgrade(room, p.version)
  room.exits =
    p.exits.associateTo(LinkedHashMap()) { ep ->
      val exit = Exit(ep)
      val live = oldExits[ep.direction]
      val wasProto = was?.exits?.firstOrNull { it.direction == ep.direction }
      if (live != null && wasProto != null) {
        val door = exit.door
        val liveDoor = live.door
        if (door != null && liveDoor != null && liveDoor.state != wasProto.door?.state)
          door.state = liveDoor.state
      }
      ep.direction to exit
    }
  room.dirty = true
}

/** Moves [e] to prototype [version], running the package's `migrate` if it went up. */
fun World.upgrade(e: Entity, version: Int) {
  if (e.protoVersion < version)
    guarded("migrate of $e") { scripts.migrate(this, e, e.protoVersion) }
  e.protoVersion = version
}

/** A field the instance hasn't changed from its old prototype follows the new one. */
private fun <T> follow(live: T, was: T, now: T): T = if (live == was) now else live

/** Rebinds [m] to [p] and returns the vitals that were full, for the caller to refill. */
private fun World.rebind(m: Mob, p: MobProto, was: MobProto): Set<String> {
  m.proto = p
  upgrade(m, p.version)
  m.level = follow(m.level, was.level, p.level)
  // Fullness is judged against the old traits and items.
  val full = m.vitalMap.filter { (name, v) -> v.current == m.max(name) }.keys
  for ((name, v) in m.vitalMap) v.max = follow(v.max, was.vitals[name] ?: 0, p.vitals[name] ?: 0)
  m.recomputeTraits(this)
  for (stat in p.stats.keys + was.stats.keys) {
    m.baseStats[stat] = follow(m.baseStats[stat], was.stats[stat], p.stats[stat]) ?: 0
  }
  if (m.baseSenses == Channel.BUILTIN_SENSES + was.senses) {
    m.baseSenses.clear()
    m.baseSenses.putAll(Channel.BUILTIN_SENSES + p.senses)
  }
  if (m.resists == was.resists) {
    m.resists.clear()
    m.resists.putAll(p.resists)
  }
  if (m.skills == was.skills) {
    m.skills.clear()
    m.skills.putAll(p.skills)
  }
  m.thinkEvery = follow(m.thinkEvery, was.thinkEvery, p.thinkEvery)
  m.dormantRecovery = follow(m.dormantRecovery, was.dormantRecovery, p.dormantRecovery)
  m.dirty = true
  return full
}

/** New spawn states from the rooms' current rules; live mobs carry over by label, in order. */
private fun World.rebuildSpawns() {
  val old = spawnStates.toList()
  spawnStates.clear()
  val claimed = HashSet<SpawnState>()
  for (room in roomsByLabel.values) {
    for (rule in room.proto.spawns) {
      val s = SpawnState(room, rule)
      val prev =
        old.firstOrNull { it.room === room && it.rule.label == rule.label && it !in claimed }
      if (prev != null) {
        claimed += prev
        for (id in prev.live) (actor(id) as? Mob)?.let { m ->
          s.live += id
          m.spawnedBy = s
        }
        s.respawnAt =
          prev.respawnAt ?: if (s.live.size < rule.count) pulse + rule.respawnPulses else null
      }
      spawnStates += s
    }
  }
  for (s in old) if (s !in claimed) for (id in s.live) (actor(id) as? Mob)?.spawnedBy = null
}
