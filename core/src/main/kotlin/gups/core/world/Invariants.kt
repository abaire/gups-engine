package gups.core.world

import gups.core.ACTION_QUEUE_MAX
import gups.core.EntityId
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Room

/** One broken state contract, and the entity it is about if there is one. */
data class Violation(val entity: EntityId?, val message: String) {
  override fun toString(): String = message
}

/** Checks the state contracts. Returns one line per violation. */
fun World.checkInvariants(): List<String> = invariantViolations().map { it.message }

/** Like [checkInvariants], keeping which entity each violation is about. */
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
    if (a.hp > a.maxHp) out += "$a: hp ${a.hp} > max ${a.maxHp}"
    if (a.mana > a.maxMana) out += "$a: mana ${a.mana} > max ${a.maxMana}"
    if (a.move > a.maxMove) out += "$a: move ${a.move} > max ${a.maxMove}"
    if (a.waitState > 0 && a.casting != null) out += "$a: both in recovery and casting"
    if (a.actionQueue.size > ACTION_QUEUE_MAX) out += "$a: action queue holds ${a.actionQueue.size}"
    for (item in a.inventoryIndex.values) if (item.location != ItemLocation.Carried(a.id))
      out += "$a lists stray $item"
  }
  // A dormant room has no running timers, AI or combat (inv. 7). Active entities carry on.
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
