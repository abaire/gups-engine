package gups.core.world

import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.model.Room
import gups.core.rules.RulesContext

// Item custody. An item's location lives only on the item; the per-room,
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
  // Debug items are never saved, so they have no audit trail.
  if (custodian(item) is Player && !debug)
    itemEvents += ItemEvent(item.id, pulse, null, at, "spawn")
  custodian(item)?.let { refreshHoldings(it) }
  invokeOn(item, HookName.ON_SPAWN, HookContext(item = item))
  return item
}

/**
 * The `move_item` verb, and the single writer of [Item.location]: moves [count] of [item] (by
 * default the whole stack) to [to] and returns the item now at [to]. Moving part of a stack leaves
 * the rest in place and creates a new item with the same attributes, name override, debug flag,
 * decay deadline and instance affects. A moved part does not fire `on_spawn`, because no new
 * content was created.
 */
fun World.moveItem(item: Item, to: ItemLocation, count: Int = item.count): Item {
  require(item.id in entities) { "$item is not in the world" }
  require(count in 1..item.count) {
    "move_item: count must be between 1 and ${item.count}, got $count"
  }
  if (count == item.count) {
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
      // An item changing hands between players is saved at once, both sides together.
      if (before != null && after != null) requestImmediate("transfer")
    }
    holderOf(from)?.let { refreshHoldings(it) }
    custodian(item)?.let { refreshHoldings(it) }
    return item
  }
  require(item.proto.stackable) { "${item.name} is not stackable" }
  require(item.location !is ItemLocation.Equipped && to !is ItemLocation.Equipped) {
    "move_item: can't move part of a stack to or from equipment"
  }
  checkTarget(item, to)
  val from = item.location
  val before = custodian(item) as? Player
  val part = Item(allocateId(), item.proto, to, count)
  part.debug = item.debug
  part.nameOverride = item.nameOverride
  part.decayAt = item.decayAt
  part.customAttributes.putAll(item.customAttributes)
  for (a in item.affects) part.affects +=
    Affect(
      allocateHandle(),
      a.name,
      a.source,
      a.appliedAt,
      a.expiresAt,
      a.statDeltas,
      a.flags,
      a.tickEvery,
      a.onTick,
      a.onExpire,
      a.expireMessage,
      a.modifiers,
      a.wornFrom,
      a.senseDeltas,
      a.applies,
    )
  part.addedTags += item.addedTags
  part.removedTags += item.removedTags
  part.dirty = true
  item.count -= count
  item.dirty = true
  register(part)
  index(part, to)
  val after = custodian(part) as? Player
  // The part is a new id, so it always needs an origin record when a player is involved.
  if ((before != null || after != null) && !part.debug)
    itemEvents += ItemEvent(part.id, pulse, from, to, "split")
  if (before != null && after != null && before !== after) requestImmediate("transfer")
  holderOf(from)?.let { refreshHoldings(it) }
  custodian(part)?.let { refreshHoldings(it) }
  return part
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
 * they don't. The limit is worked out without the current encumbrance.
 */
internal fun World.updateEncumbrance(a: Actor) {
  if (!a.inWorld) return
  val was = a.encumbrance
  guarded("encumbrance of $a") {
    a.encumbrance = emptyMap()
    if (a.carriedWeight() > rules.carryLimit(RulesContext(this, a)))
      a.encumbrance = rules.encumbrance(RulesContext(this, a))
  }
  if (was != a.encumbrance) {
    a.dirty = true
    if (was.isEmpty()) tell(a, KernelMsg.ENCUMBERED)
    else if (a.encumbrance.isEmpty()) tell(a, KernelMsg.UNENCUMBERED)
  }
}

/**
 * True if [item] may merge into a stack at all: no timers, name override, affects or tag changes.
 */
internal fun Item.canStack(): Boolean =
  proto.stackable &&
    timers.isEmpty() &&
    nameOverride == null &&
    affects.isEmpty() &&
    addedTags.isEmpty() &&
    removedTags.isEmpty()

/**
 * Merges [item] into a stack of the same prototype at the same location, if there is one with an
 * identical delta: the same attributes and decay deadline, and one that can stack. Returns the
 * stack that now holds it.
 */
fun World.stack(item: Item): Item {
  if (!item.canStack()) return item
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
        it.canStack() &&
        it.decayAt == item.decayAt &&
        it.customAttributes == item.customAttributes
    } ?: return item
  into.count += item.count
  into.dirty = true
  destroy(item)
  return into
}

/**
 * The `destroy` verb: removes [item] and everything inside it, or only [count] of a stack when
 * [count] is less than its count.
 */
fun World.destroy(item: Item, count: Int = item.count) {
  require(item.id in entities) { "$item is not in the world" }
  require(count in 1..item.count) {
    "destroy: count must be between 1 and ${item.count}, got $count"
  }
  if (count == item.count) {
    val holder = custodian(item)
    if (holder is Player && !item.debug)
      itemEvents += ItemEvent(item.id, pulse, item.location, null, "destroy")
    unindex(item)
    markRemoved(item)
    forgetItemTree(item)
    holder?.let { refreshHoldings(it) }
    return
  }
  val holder = custodian(item)
  if (holder is Player && !item.debug)
    itemEvents += ItemEvent(item.id, pulse, item.location, item.location, "reduce")
  item.count -= count
  item.dirty = true
  holder?.let { refreshHoldings(it) }
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
    entity(loc.room)?.let { room ->
      tellSight(room as Room, "decay", null, msg(KernelMsg.DECAYS, "item" to item.name))
    }
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
