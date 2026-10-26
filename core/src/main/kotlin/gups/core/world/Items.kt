package gups.core.world

import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.model.Room
import gups.core.rules.RulesContext

// Item custody (SPEC §3.3, §3.8). An item's location lives only on the item; the per-room,
// per-actor and per-container indexes are derived and changed only here.

/** The `spawn` verb for items. */
fun World.spawnItem(label: Label, at: ItemLocation, count: Int = 1, debug: Boolean = false): Item {
  val proto = protos.item(label)
  val item = Item(allocateId(), proto, at, if (proto.stackable) count else 1)
  item.debug = debug
  proto.decayPulses?.let { item.decayAt = pulse + it }
  checkTarget(item, at)
  register(item)
  index(item, at)
  // Debug items are never saved, so they have no audit trail (SPEC §4.9).
  if (custodian(item) is Player && !debug)
    itemEvents += ItemEvent(item.id, pulse, null, at, "spawn")
  custodian(item)?.let { updateEncumbrance(it) }
  invokeOn(item, HookName.ON_SPAWN, HookContext(item = item))
  return item
}

/** The `move_item` verb: the single writer of [Item.location]. */
fun World.moveItem(item: Item, to: ItemLocation) {
  require(item.id in entities) { "$item is not in the world" }
  checkTarget(item, to)
  val before = custodian(item) as? Player
  val from = item.location
  unindex(item)
  item.location = to
  index(item, to)
  item.dirty = true
  val after = custodian(item) as? Player
  if (before !== after && !item.debug) {
    itemEvents += ItemEvent(item.id, pulse, from, to, "move")
    // An item changing hands between players is saved at once, both sides together (SPEC §5.11).
    if (before != null && after != null) requestImmediate("transfer")
  }
  holderOf(from)?.let { updateEncumbrance(it) }
  custodian(item)?.let { updateEncumbrance(it) }
}

/** The actor whose load an item at [loc] counts toward, if any. */
private fun World.holderOf(loc: ItemLocation): Actor? =
  when (loc) {
    is ItemLocation.Carried -> actor(loc.actor)
    is ItemLocation.Equipped -> actor(loc.actor)
    is ItemLocation.Contained -> item(loc.container)?.let { custodian(it) }
    is ItemLocation.InRoom -> null
  }

/**
 * Applies `rules.encumbrance` while [a] carries more than `rules.carry_limit`, and lifts it once
 * they don't (SPEC §4.6). The limit is worked out without the current encumbrance.
 */
fun World.updateEncumbrance(a: Actor) {
  if (!a.inWorld) return
  val was = a.encumbrance
  guarded("encumbrance of $a") {
    a.encumbrance = emptyMap()
    if (a.carriedWeight() > rules.carryLimit(RulesContext(this, a)))
      a.encumbrance = rules.encumbrance(RulesContext(this, a))
  }
  if (was != a.encumbrance) {
    a.dirty = true
    if (was.isEmpty()) send(a, "You are carrying more than you can manage.")
    else if (a.encumbrance.isEmpty()) send(a, "Your load feels manageable again.")
  }
}

/**
 * Merges [item] into a stack of the same prototype at the same location, if there is one with an
 * identical delta (SPEC §3.8 inv. 8): the same attributes and decay deadline, and no timers or name
 * override on either. Returns the stack that now holds it.
 */
fun World.stack(item: Item): Item {
  if (!item.proto.stackable || item.timers.isNotEmpty() || item.nameOverride != null) return item
  val peers =
    when (val loc = item.location) {
      is ItemLocation.InRoom ->
        (entity(loc.room) as? Room)?.itemIndex?.values?.filter { it.groundIndex == loc.trackIndex }
      is ItemLocation.Carried -> actor(loc.actor)?.inventoryIndex?.values
      is ItemLocation.Contained -> item(loc.container)?.contentsIndex?.values
      is ItemLocation.Equipped -> null
    } ?: return item
  val into =
    peers.firstOrNull {
      it !== item &&
        it.protoLabel == item.protoLabel &&
        it.timers.isEmpty() &&
        it.nameOverride == null &&
        it.decayAt == item.decayAt &&
        it.customAttributes == item.customAttributes
    } ?: return item
  into.count += item.count
  into.dirty = true
  destroy(item)
  return into
}

/** The `destroy` verb: removes [item] and everything inside it. */
fun World.destroy(item: Item) {
  val holder = custodian(item)
  if (holder is Player) itemEvents += ItemEvent(item.id, pulse, item.location, null, "destroy")
  unindex(item)
  markRemoved(item)
  forgetItemTree(item)
  holder?.let { updateEncumbrance(it) }
}

/** The actor whose custody [item] ends in, following containers; null for items on the ground. */
fun World.custodian(item: Item): Actor? =
  when (val loc = item.location) {
    is ItemLocation.Carried -> actor(loc.actor)
    is ItemLocation.Equipped -> actor(loc.actor)
    is ItemLocation.Contained -> item(loc.container)?.let { custodian(it) }
    is ItemLocation.InRoom -> null
  }

/** Decay drops a container's contents where it lay, then removes it. */
internal fun World.decay(item: Item) {
  val loc = item.location
  if (loc is ItemLocation.InRoom) {
    for (inner in item.contents) moveItem(inner, loc)
    entity(loc.room)?.let { room -> echo(room as Room, "${item.name.cap()} crumbles to dust.") }
  }
  destroy(item)
}

/** Forgets [item] and its contents without touching the holder's index. */
internal fun World.forgetItemTree(item: Item) {
  for (inner in item.contentsIndex.values.toList()) forgetItemTree(inner)
  entities.remove(item.id)
}

private fun World.checkTarget(item: Item, to: ItemLocation) {
  when (to) {
    is ItemLocation.InRoom -> {
      val room = requireNotNull(entity(to.room) as? Room) { "No room ${to.room}" }
      require(to.trackIndex in 0 until room.trackSize) {
        "Track index ${to.trackIndex} outside ${room.label}"
      }
    }
    is ItemLocation.Carried -> requireNotNull(actor(to.actor)) { "No actor ${to.actor}" }
    is ItemLocation.Equipped -> {
      val a = requireNotNull(actor(to.actor)) { "No actor ${to.actor}" }
      val worn = a.equipmentIndex[to.slot]
      require(worn == null || worn === item) { "${a.name} already wears something in ${to.slot}" }
    }
    is ItemLocation.Contained -> {
      var c: Item? = requireNotNull(item(to.container)) { "No container ${to.container}" }
      requireNotNull(c!!.container) { "$c is not a container" }
      while (c != null) {
        require(c !== item) { "Putting $item into ${to.container} would make a containment cycle" }
        c = (c.location as? ItemLocation.Contained)?.let { item(it.container) }
      }
    }
  }
}

private fun World.index(item: Item, at: ItemLocation) {
  when (at) {
    is ItemLocation.InRoom -> (entity(at.room) as Room).itemIndex[item.id] = item
    is ItemLocation.Carried -> actor(at.actor)!!.inventoryIndex[item.id] = item
    is ItemLocation.Equipped -> {
      val wearer = actor(at.actor)!!
      wearer.equipmentIndex[at.slot] = item
      applyEquipAffects(wearer, item)
    }
    is ItemLocation.Contained -> item(at.container)!!.contentsIndex[item.id] = item
  }
}

private fun World.unindex(item: Item) {
  when (val at = item.location) {
    is ItemLocation.InRoom -> (entity(at.room) as? Room)?.itemIndex?.remove(item.id)
    is ItemLocation.Carried -> actor(at.actor)?.inventoryIndex?.remove(item.id)
    is ItemLocation.Equipped ->
      actor(at.actor)?.let { wearer ->
        wearer.equipmentIndex.remove(at.slot)
        removeEquipAffects(wearer, item)
      }
    is ItemLocation.Contained -> item(at.container)?.contentsIndex?.remove(item.id)
  }
}

/** Track index of an item on the ground, or null if it is not on the ground. */
val Item.groundIndex: Int?
  get() = (location as? ItemLocation.InRoom)?.trackIndex

/** Total carried weight, inventory and equipment. */
fun Actor.carriedWeight(): Int =
  inventoryIndex.values.sumOf { it.weight } + equipmentIndex.values.sumOf { it.weight }

internal fun markDirtyTree(item: Item) {
  item.dirty = true
  for (inner in item.contentsIndex.values) markDirtyTree(inner)
}
