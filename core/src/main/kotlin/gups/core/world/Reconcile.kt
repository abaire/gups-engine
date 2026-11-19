package gups.core.world

import gups.core.EntityId
import gups.core.model.Channel
import gups.core.model.DoorState
import gups.core.model.Entity
import gups.core.model.Exit
import gups.core.model.ExitProto
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Mob
import gups.core.model.MobProto
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.Respawn
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
  createMissingRooms(next, old, created)
  retireRemovedRooms(next, notes)
  clampTrackIndices()
  val refill = rebindMobs(next, old, notes)
  pruneSkillsAndRecomputeTraits(next)
  reconcileItems(next, notes)
  rebuildSpawns(next)
  retireZoneResets(next)
  invalidateCaches()
  notes += dropOrphanStanding(checkActors = false)
  refreshActorState()
  refillVitals(refill)
  coverage.keys.retainAll(roomsByLabel.values.toSet())
  recomputeBubble()
  return ReconcileReport(created, notes)
}

/**
 * Builds the rooms the new prototype set adds, and rebinds the surviving ones to their new data.
 */
private fun World.createMissingRooms(
  next: Prototypes,
  old: Prototypes,
  created: MutableList<Room>,
) {
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
}

/**
 * Retires the rooms the new prototype set dropped, shifting their players and despawning their
 * mobs. [fallback] is the first room, taken before any removal so it is a stable destination.
 */
private fun World.retireRemovedRooms(next: Prototypes, notes: MutableList<String>) {
  val fallback = roomsByLabel.values.first()
  for (gone in roomsByLabel.values.filter { it.label !in next.rooms }) {
    for (a in gone.actors) when (a) {
      is Player -> shiftOutOfRoom(a, next, fallback)
      is Mob -> removeMobForReload(a)
    }
    for (i in gone.items) removeItem(i)
    roomsByLabel.remove(gone.label)
    entities.remove(gone.id)
    removed += gone.id
    notes += "room ${gone.label} removed"
  }
}

/**
 * Moves a player out of a deleted room, to their recall room if it survives, else the first room.
 */
private fun World.shiftOutOfRoom(a: Player, next: Prototypes, fallback: Room) {
  val to = roomOrNull(a.recallRoom)?.takeIf { it.label in next.rooms } ?: fallback
  if (a.recallRoom !in next.rooms) a.recallRoom = fallback.label
  disengageAll(a, null)
  placeActor(a, to, 0)
  tell(a, KernelMsg.RECONCILE_SHIFT)
  send(a, describeRoom(a))
}

/** Clamps actors and ground items that now sit past a room's (possibly shrunk) track. */
private fun World.clampTrackIndices() {
  for (room in roomsByLabel.values) {
    for (a in room.actors) if (a.trackIndex >= room.trackSize)
      placeActor(a, room, room.trackSize - 1)
    for (i in room.items) i.groundIndex?.let {
      if (it >= room.trackSize) moveItem(i, ItemLocation.InRoom(room.id, room.trackSize - 1))
    }
  }
}

/**
 * Rebinds each live mob to its new prototype, or despawns it if the prototype is gone. Returns the
 * vitals that were full, so the caller can refill them once the new maxima count.
 */
private fun World.rebindMobs(
  next: Prototypes,
  old: Prototypes,
  notes: MutableList<String>,
): MutableMap<Mob, Set<String>> {
  val refill = mutableMapOf<Mob, Set<String>>()
  for (m in actorIndex.values.filterIsInstance<Mob>()) {
    val p = next.mobs[m.protoLabel]
    if (p == null) {
      notes += "mob ${m.protoLabel} ($m) despawned: its prototype is gone"
      removeMobForReload(m)
    } else {
      refill[m] = rebind(m, p, old.mobs[m.protoLabel] ?: m.proto)
    }
  }
  return refill
}

/** Drops skills whose prototype vanished, then re-derives every actor's traits. */
private fun World.pruneSkillsAndRecomputeTraits(next: Prototypes) {
  for (a in actorIndex.values) {
    a.skills.keys.retainAll { it in next.skills }
    a.recomputeTraits(this)
  }
}

/**
 * Reconciles every live item against the new prototype set: data and charges follow a surviving
 * prototype, a carried item whose prototype vanished becomes a tombstone, and a ground item is
 * destroyed.
 */
private fun World.reconcileItems(next: Prototypes, notes: MutableList<String>) {
  for (item in entities.values.filterIsInstance<Item>().sortedBy { it.id }) {
    if (item.id !in entities) continue
    val p = next.items[item.protoLabel]
    when {
      p != null -> applyNewItemProto(item, p)
      item.proto.kind == TOMBSTONE -> Unit
      custodian(item) is Player -> {
        item.proto = tombstone(item.proto)
        item.spawnedBy?.let { s ->
          releaseCopy(s, item.id)
          item.unbindSpawn()
        }
        notes += "item ${item.protoLabel} ($item) became a tombstone"
      }
      else -> {
        notes += "item ${item.protoLabel} ($item) despawned: its prototype is gone"
        removeItem(item)
      }
    }
    item.dirty = true
  }
}

/** Keeps zone resets only for zones that still exist, seeding a first reset for a new interval. */
private fun World.retireZoneResets(next: Prototypes) {
  zoneResets.keys.retainAll(next.zones.keys)
  for (zone in next.zones.values) {
    val ticks = zone.resetTicks
    if (ticks == null) zoneResets.remove(zone.pkg)
    else if (zone.pkg !in zoneResets) zoneResets[zone.pkg] = nextZoneReset(ticks)
  }
}

/** Forgets everything derived from the room graph, before it is worked out again. */
private fun World.invalidateCaches() {
  graphChanged()
  inboundCache = null
  slotCache = null
  slotFamilyCache = null
  factionCache.clear()
  dispositionMemo.clear()
}

/** Re-asks each actor for its slots, wear restriction and what it can now carry. */
private fun World.refreshActorState() {
  for (a in actorIndex.values) {
    forgetActorSlots(a)
    askWearRestriction(a)
    refreshHoldings(a)
  }
}

/**
 * Refills a mob's vitals that were full, now that the new traits and items count towards maxima.
 */
private fun World.refillVitals(refill: Map<Mob, Set<String>>) {
  for ((m, names) in refill) if (m.inWorld) {
    for (name in names) m.vitalMap.getValue(name).current = m.max(name)
    m.dirty = true
  }
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

/**
 * Applies a surviving item's new prototype: updated charges, the new data, and a version migrate.
 */
private fun World.applyNewItemProto(item: Item, p: ItemProto) {
  val nextCharges = nextChargesFor(item, p)
  if (item.charges != nextCharges) {
    item.charges = nextCharges
    item.dirty = true
  }
  item.proto = p
  upgrade(item, p.version)
}

/** The charges an item should have after its prototype is swapped, or null for unlimited. */
private fun nextChargesFor(item: Item, p: ItemProto): Int? =
  when {
    p.charges == null -> null
    p.stackable -> p.charges
    item.proto.charges == null -> p.charges
    else -> minOf(item.charges ?: p.charges, p.charges)
  }

/** Disengages a mob, clears its action and removes it from its room. */
private fun World.removeMobForReload(m: Mob) {
  disengageAll(m, null)
  endActivity(m, TaskEnd.GONE)
  m.actionQueue.clear()
  removeActor(m)
}

/**
 * Rebinds [room] to [p]. A door on an exit the old prototype also set keeps the state play changed
 * it to, so a lock the player set survives the reload.
 */
private fun World.rebind(room: Room, p: RoomProto, was: RoomProto?) {
  val oldExits = room.exits
  room.proto = p
  upgrade(room, p.version)
  room.exits =
    p.exits.associateTo(LinkedHashMap()) { ep ->
      val exit = Exit(ep)
      val live = oldExits[ep.direction]
      if (live != null) {
        val wasProto = was?.exits?.firstOrNull { it.direction == ep.direction }
        if (wasProto != null) preserveDoorState(exit, live, wasProto)
        preserveExitTarget(exit, live)
      }
      ep.direction to exit
    }
  room.dirty = true
}

/**
 * Keeps a door the player changed in play: the live state beats the new prototype's, except that a
 * lock the new prototype has no key for is undone.
 */
private fun preserveDoorState(exit: Exit, live: Exit, wasProto: ExitProto) {
  val door = exit.door ?: return
  val liveDoor = live.door ?: return
  if (liveDoor.state != wasProto.door?.state)
    door.state =
      if (liveDoor.state == DoorState.LOCKED && door.key == null) DoorState.CLOSED
      else liveDoor.state
}

/**
 * Keeps a retargeted exit destination while the exit stays single-destination, doorless, and valid.
 */
private fun World.preserveExitTarget(exit: Exit, live: Exit) {
  val target = live.to ?: return
  if (target == live.originalTo) return
  if (exit.perCrossing || exit.door != null) return
  if (target in protos.rooms) exit.to = target
}

/** Moves [e] to prototype [version], running the package's `migrate` if it went up. */
fun World.upgrade(e: Entity, version: Int) {
  if (e.protoVersion < version)
    guarded("migrate of $e") { scripts.migrate(this, e, e.protoVersion) }
  e.protoVersion = version
}

/** A field the instance hasn't changed from its old prototype follows the new one. */
private fun <T> follow(live: T, was: T, now: T): T = if (live == was) now else live

/** Adopts the new prototype's value for a field the instance never changed from the old one. */
private fun <K, V> refreshIfUnchanged(live: MutableMap<K, V>, was: Map<K, V>, now: Map<K, V>) {
  if (live == was) {
    live.clear()
    live.putAll(now)
  }
}

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
  refreshIfUnchanged(
    m.baseSenses,
    Channel.BUILTIN_SENSES + was.senses,
    Channel.BUILTIN_SENSES + p.senses,
  )
  refreshIfUnchanged(m.resists, was.resists, p.resists)
  refreshIfUnchanged(m.skills, was.skills, p.skills)
  m.thinkEvery = follow(m.thinkEvery, was.thinkEvery, p.thinkEvery)
  m.dormantRecovery = follow(m.dormantRecovery, was.dormantRecovery, p.dormantRecovery)
  m.dirty = true
  return full
}

/**
 * Rebuilds spawn states from [next], carrying state over by key, releasing removed rules' copies,
 * and doing a first fill on every newly added rule.
 */
private fun World.rebuildSpawns(next: Prototypes) {
  val oldByKey = spawnStates.associateBy { it.key }
  val fresh = buildSpawnStates(next)
  replaceSpawnStates(fresh)
  val (kept, added) = fresh.partition { it.key in oldByKey }
  for (s in kept) carryOverSpawn(oldByKey.getValue(s.key), s)
  val keptKeys = kept.mapTo(HashSet(kept.size)) { it.key }
  oldByKey.filterKeys { it !in keptKeys }.values.forEach(::releaseRemovedSpawn)
  added.forEach(::firstFill)
}

/** Moves a carried-over rule's tracked copies, deadline and run flag into [s]. */
private fun World.carryOverSpawn(prev: SpawnState, s: SpawnState) {
  s.ran = prev.ran
  s.away.addAll(prev.away)
  prev.held.filterTo(s.held) { it.room in roomsByLabel }
  prev.live.forEach { rebindLiveCopy(it, s) }
  s.respawnAt = prev.respawnAt.takeIf { s.rule.respawn is Respawn.Timed }
  armTimer(s)
}

/** Re-points a carried copy at [s]; a copy of the other kind than [s] places is let go instead. */
private fun World.rebindLiveCopy(id: EntityId, s: SpawnState) {
  val e = entity(id)
  when {
    e is Mob && !s.isItemRule -> e.spawnedBy = s
    e is Item && s.isItemRule -> e.spawnedBy = s
    else -> return clearSpawnBinding(id)
  }
  s.live += id
}

/** Releases a removed rule's live copies and drops its away and held copies. */
private fun World.releaseRemovedSpawn(prev: SpawnState) {
  prev.live.forEach(::clearSpawnBinding)
  prev.away.clear()
  prev.held.clear()
}

private fun World.clearSpawnBinding(id: EntityId) {
  when (val e = entity(id)) {
    is Mob -> e.spawnedBy = null
    is Item -> e.unbindSpawn()
    else -> Unit
  }
}
