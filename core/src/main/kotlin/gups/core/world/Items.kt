package gups.core.world

import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Room

// Item custody (SPEC §3.3, §3.8). An item's location lives only on the item; the per-room,
// per-actor and per-container indexes are derived and changed only here.

/** The `spawn` verb for items. */
fun World.spawnItem(label: Label, at: ItemLocation, count: Int = 1): Item {
    val proto = protos.item(label)
    val item = Item(allocateId(), proto, at, if (proto.stackable) count else 1)
    proto.decayPulses?.let { item.decayAt = pulse + it }
    checkTarget(item, at)
    register(item)
    index(item, at)
    invokeOn(item, HookName.ON_SPAWN, HookContext(item = item))
    return item
}

/** The `move_item` verb: the single writer of [Item.location]. */
fun World.moveItem(item: Item, to: ItemLocation) {
    require(item.id in entities) { "$item is not in the world" }
    checkTarget(item, to)
    unindex(item)
    item.location = to
    index(item, to)
    item.dirty = true
}

/** The `destroy` verb: removes [item] and everything inside it. */
fun World.destroy(item: Item) {
    unindex(item)
    forgetItemTree(item)
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
            require(to.trackIndex in 0 until room.trackSize) { "Track index ${to.trackIndex} outside ${room.label}" }
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
        is ItemLocation.Equipped -> actor(at.actor)!!.equipmentIndex[at.slot] = item
        is ItemLocation.Contained -> item(at.container)!!.contentsIndex[item.id] = item
    }
}

private fun World.unindex(item: Item) {
    when (val at = item.location) {
        is ItemLocation.InRoom -> (entity(at.room) as? Room)?.itemIndex?.remove(item.id)
        is ItemLocation.Carried -> actor(at.actor)?.inventoryIndex?.remove(item.id)
        is ItemLocation.Equipped -> actor(at.actor)?.equipmentIndex?.remove(at.slot)
        is ItemLocation.Contained -> item(at.container)?.contentsIndex?.remove(item.id)
    }
}

/** Track index of an item on the ground, or null if it is not on the ground. */
val Item.groundIndex: Int? get() = (location as? ItemLocation.InRoom)?.trackIndex

/** Total carried weight, inventory and equipment. */
fun Actor.carriedWeight(): Int = inventoryIndex.values.sumOf { it.weight } + equipmentIndex.values.sumOf { it.weight }
