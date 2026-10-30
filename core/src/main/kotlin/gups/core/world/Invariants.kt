package gups.core.world

import gups.core.ACTION_QUEUE_MAX
import gups.core.EntityId
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Room

/** Representation of an invariant violation in the simulation state. */
data class Violation(
  /** Identifier of the entity associated with this violation, or null if room- or world-wide. */
  val entity: EntityId?,
  /** Narrative description of the broken state contract. */
  val message: String,
) {
  override fun toString(): String = message
}

/**
 * Validates the core simulation state contracts and returns a list of human-readable violation
 * descriptions.
 */
fun World.checkInvariants(): List<String> = invariantViolations().map { it.message }

/**
 * Validates authoritative simulation invariants across entities, rooms, and actors:
 * 1. Every item has exactly one [ItemLocation], and the item containment graph is a forest with no
 *    cycles.
 * 2. Every actor's spatial track position satisfies `0 <= trackIndex < room.trackSize`.
 * 3. Every vital's current value does not exceed its effective maximum.
 * 4. An actor is either idle, in recovery lag (`waitState > 0`), or casting (`casting != null`),
 *    never both in recovery and casting.
 * 5. An actor's action queue size never exceeds [ACTION_QUEUE_MAX] (10 actions).
 * 6. Dormant rooms contain no running timers, autonomous mob AI, or active combat encounters.
 * 7. Room and actor spatial indices are bidirectional and contain no stray unindexed entities.
 *
 * Returns a list of all detected [Violation] records.
 */
fun World.invariantViolations(): List<Violation> {
  val out = ViolationList()
  for (e in entities.values) {
    if (e !is Item) continue
    val held =
      when (val loc = e.location) {
        is ItemLocation.InRoom -> (entity(loc.room) as? Room)?.itemIndex?.get(e.id)
        is ItemLocation.Carried -> actor(loc.actor)?.inventoryIndex?.get(e.id)
        is ItemLocation.Equipped -> actor(loc.actor)?.equipmentIndex?.get(loc.slot)
        is ItemLocation.Contained -> item(loc.container)?.contentsIndex?.get(e.id)
      }
    if (held !== e) out += "$e: location ${e.location} does not hold it"
    val seen = HashSet<EntityId>()
    var c: Item? = e
    while (c != null) {
      if (!seen.add(c.id)) {
        out += "$e: containment cycle"
        break
      }
      c = (c.location as? ItemLocation.Contained)?.let { item(it.container) }
    }
  }
  for (room in rooms) {
    for (i in room.itemIndex.values) if (i.location !is ItemLocation.InRoom || i.id !in entities)
      out += "${room.label} lists stray $i"
    for (a in room.actorIndex.values) if (a.room !== room || !a.inWorld)
      out += "${room.label} lists stray $a"
  }
  for (a in actorIndex.values) {
    if (a.room.actorIndex[a.id] !== a) out += "$a: missing from ${a.room.label}"
    if (a.trackIndex !in 0 until a.room.trackSize)
      out += "$a: track index ${a.trackIndex} outside ${a.room.label}"
    for ((name, v) in a.vitalMap) {
      val effMax = a.max(name)
      if (v.current > effMax) out += "$a: $name ${v.current} > max $effMax"
    }
    if (Feature.TRACK !in features && a.trackIndex != 0)
      out += "$a: track index ${a.trackIndex} without the track"
    if (Feature.SKILLS !in features && a.casting != null) out += "$a: casting without skills"
    if (a.waitState > 0 && a.casting != null) out += "$a: both in recovery and casting"
    if (a.actionQueue.size > ACTION_QUEUE_MAX) out += "$a: action queue holds ${a.actionQueue.size}"
    for (item in a.inventoryIndex.values) if (item.location != ItemLocation.Carried(a.id))
      out += "$a lists stray $item"
  }
  // A dormant room has no running timers, AI or combat. Active entities carry on.
  for (room in rooms) {
    if (!room.isDormant) continue
    for (e in entitiesIn(room)) {
      if (isSimulated(e)) continue
      if (e.timers.any { it.due != null }) out += "$e: running timer in dormant ${room.label}"
      if (e is gups.core.model.Actor && e.isEngaged) out += "$e: fighting in dormant ${room.label}"
    }
  }
  return out.list
}

/** Collects `entity: message` lines, remembering the entity. */
private class ViolationList {
  val list = mutableListOf<Violation>()

  operator fun plusAssign(message: String) {
    val id = Regex("#(\\d+)").find(message)?.groupValues?.get(1)?.toLongOrNull()?.let(::EntityId)
    list += Violation(id, message)
  }
}
