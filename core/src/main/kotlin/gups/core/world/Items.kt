package gups.core.world

import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.EmptyAction
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
fun World.moveItem(item: Item, to: ItemLocation, count: Int = item.count): Item =
  holdingAffectEvents {
    relocate(item, to, count)
  }

private fun World.relocate(item: Item, to: ItemLocation, count: Int): Item {
  require(item.id in entities) { "$item is not in the world" }
  require(count in 1..item.count) {
    "move_item: count must be between 1 and ${item.count}, got $count"
  }
  return if (count == item.count) moveWhole(item, to) else moveSplit(item, to, count)
}

private fun World.moveWhole(item: Item, to: ItemLocation): Item {
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
    if (changesPlayers(before, after)) requestImmediate("transfer")
  }
  holderOf(from)?.let { refreshHoldings(it) }
  custodian(item)?.let { refreshHoldings(it) }
  return item
}

private fun World.moveSplit(item: Item, to: ItemLocation, count: Int): Item {
  require(item.proto.stackable) { "${item.name} is not stackable" }
  require(item.location !is ItemLocation.Equipped && to !is ItemLocation.Equipped) {
    "move_item: can't move part of a stack to or from equipment"
  }
  checkTarget(item, to)
  val from = item.location
  val before = custodian(item) as? Player
  val part = splitOff(item, to, count)
  register(part)
  index(part, to)
  val after = custodian(part) as? Player
  // The part is a new id, so it always needs an origin record when a player is involved.
  val involvesPlayer = before != null || after != null
  if (involvesPlayer && !part.debug) itemEvents += ItemEvent(part.id, pulse, from, to, "split")
  if (changesPlayers(before, after)) requestImmediate("transfer")
  holderOf(from)?.let { refreshHoldings(it) }
  custodian(part)?.let { refreshHoldings(it) }
  return part
}

/** True when an item goes from one player's custody to another's. */
private fun changesPlayers(before: Player?, after: Player?): Boolean {
  if (before == null || after == null) return false
  return before !== after
}

/**
 * A new item at [to] holding [count] of [item], with copies of everything else the instance owns;
 * [item] keeps the rest. The part is not yet registered or indexed.
 */
private fun World.splitOff(item: Item, to: ItemLocation, count: Int): Item {
  val part = Item(allocateId(), item.proto, to, count)
  part.debug = item.debug
  part.nameOverride = item.nameOverride
  part.decayAt = item.decayAt
  part.charges = item.charges
  part.customAttributes.putAll(item.customAttributes)
  for (a in item.affects) part.affects += copyAffect(a)
  part.addedTags += item.addedTags
  part.removedTags += item.removedTags
  part.dirty = true
  item.count -= count
  item.dirty = true
  return part
}

private fun World.copyAffect(a: Affect): Affect =
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
    a.maxVitalDeltas,
    a.resistDeltas,
    a.procs,
  )

/** The actor whose load an item at [loc] counts toward, if any. */
private fun World.holderOf(loc: ItemLocation): Actor? =
  when (loc) {
    is ItemLocation.Carried -> actor(loc.actor)
    is ItemLocation.Equipped -> actor(loc.actor)
    is ItemLocation.Contained -> item(loc.container)?.let { custodian(it) }
    is ItemLocation.InRoom -> null
  }

/**
 * Works out [a]'s encumbrance level from `rules.encumbrance_level`, and while it is above 0 the
 * stat penalties from `rules.encumbrance`. The limit and carried weight are asked without the
 * current level or penalties. A change in level or penalties tells the actor by level transition; a
 * change in the penalties reruns the fit check, which tells the wearer about items that stop or
 * start fitting only if [announceFit].
 */
internal fun World.updateEncumbrance(a: Actor, announceFit: Boolean = true) {
  if (!a.inWorld) return
  val was = a.encumbrance
  val wasLevel = a.encumbranceLevel
  askEncumbrance(a)
  val level = a.encumbranceLevel
  if (was != a.encumbrance || wasLevel != level) {
    a.dirty = true
    tellEncumbranceChange(a, wasLevel, level)
  }
  if (was != a.encumbrance) refreshFit(a, announceFit)
  if (wasLevel != level && announceFit)
    dispatchGlobal(
      HookName.ON_ENCUMBRANCE_CHANGE,
      HookContext(actor = a, oldEncumbranceLevel = wasLevel, newEncumbranceLevel = level),
    )
}

/** Sets [a]'s encumbrance level and penalties from the rules, asked without the current ones. */
private fun World.askEncumbrance(a: Actor) {
  guarded("encumbrance of $a") {
    a.encumbrance = emptyMap()
    a.encumbranceLevel = 0
    val limit = rules.carryLimit(RulesContext(this, a))
    val carried = a.carriedWeight()
    val level = rules.encumbranceLevel(RulesContext(this, a, limit = limit, carried = carried))
    // Both results land together, so a failing export leaves level 0 and no penalties.
    val penalties =
      if (level > 0)
        rules.encumbrance(
          RulesContext(this, a, limit = limit, carried = carried, encumbranceLevel = level)
        )
      else emptyMap()
    a.encumbranceLevel = level
    a.encumbrance = penalties
  }
}

private fun World.tellEncumbranceChange(a: Actor, wasLevel: Int, level: Int) {
  when {
    wasLevel == 0 && level > 0 -> tell(a, KernelMsg.ENCUMBERED)
    level == 0 && wasLevel > 0 -> tell(a, KernelMsg.UNENCUMBERED)
    level > wasLevel -> tell(a, KernelMsg.ENCUMBRANCE_UP, "level" to level, "was" to wasLevel)
    level < wasLevel -> tell(a, KernelMsg.ENCUMBRANCE_DOWN, "level" to level, "was" to wasLevel)
  }
}

/** The longest name `set_item_name` accepts. */
const val ITEM_NAME_MAX = 80

/** A non-empty single line of at most [max] characters: what an item's or a task's name must be. */
fun isShortLine(s: String, max: Int): Boolean =
  s.isNotBlank() && s.length <= max && s.none { it == '\n' || it == '\r' }

/**
 * The `set_item_name` verb: [name] replaces [item]'s prototype name, or null clears the override.
 * Keywords stay; a renamed item already stays out of stacks. Returns whether the name changed.
 */
fun World.setItemName(item: Item, name: String?): Boolean {
  if (name != null) {
    require(isShortLine(name, ITEM_NAME_MAX)) {
      "name must be a non-empty single line of at most $ITEM_NAME_MAX characters"
    }
  }
  if (item.nameOverride == name) return false
  item.nameOverride = name
  item.dirty = true
  return true
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
  val into = stackPeers(item)?.firstOrNull { it !== item && it.stacksWith(item) } ?: return item
  into.count += item.count
  into.dirty = true
  destroy(item)
  return into
}

/** The items at [item]'s location it could stack with, or null where nothing stacks. */
private fun World.stackPeers(item: Item): Collection<Item>? =
  when (val loc = item.location) {
    is ItemLocation.InRoom ->
      (entity(loc.room) as? Room)?.itemIndex?.values?.filter { it.groundIndex == loc.trackIndex }
    is ItemLocation.Carried -> actor(loc.actor)?.inventoryIndex?.values
    is ItemLocation.Contained -> item(loc.container)?.contentsIndex?.values
    is ItemLocation.Equipped -> null
  }

private fun Item.stacksWith(other: Item): Boolean =
  protoLabel == other.protoLabel &&
    canStack() &&
    decayAt == other.decayAt &&
    charges == other.charges &&
    customAttributes == other.customAttributes

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

/**
 * The `set_charges` verb: sets [item]'s remaining uses to [charges], between 0 and its prototype's
 * charges. Refuses unlimited and stackable items. Setting 0 doesn't run the empty action.
 */
fun World.setCharges(item: Item, charges: Int) {
  require(item.id in entities) { "$item is not in the world" }
  val max = requireNotNull(item.proto.charges) { "set_charges: ${item.name} has unlimited uses" }
  require(!item.proto.stackable) { "set_charges: ${item.name} is stackable" }
  require(charges in 0..max) { "set_charges: charges must be between 0 and $max, got $charges" }
  item.charges = charges
  item.dirty = true
}

/** Spends one use of [item] after a `use`, running its empty action when that was the last. */
internal fun World.spendCharge(item: Item) {
  if (item.id !in entities || item.charges == null) return
  if (!item.proto.stackable) {
    item.charges = item.charges!! - 1
    item.dirty = true
    if (item.charges!! > 0) return
  }
  when (val empty = item.proto.empty) {
    EmptyAction.Destroy -> destroy(item, 1)
    EmptyAction.Keep -> Unit
    is EmptyAction.Variant -> becomeVariant(item, item.proto.variants.getValue(empty.name))
  }
}

/** Turns one of [item] into [label]: the item itself, or one split off a stack and restacked. */
private fun World.becomeVariant(item: Item, label: Label) {
  if (item.count == 1) return transformItem(item, label)
  val loc = item.location
  val thatLocation = (loc as? ItemLocation.Equipped)?.let { ItemLocation.Carried(it.actor) } ?: loc
  destroy(item, 1)
  val part = spawnItem(label, thatLocation, debug = item.debug)
  part.customAttributes.putAll(item.customAttributes)
  stack(part)
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
      if (wearer.isActiveSlot(at.slot)) {
        applyEquipAffects(wearer, item)
      }
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
        val active = wearer.isActiveSlot(at.slot)
        wearer.equipmentIndex.remove(at.slot)
        wearer.wearRefusals -= at.slot
        if (active) {
          removeEquipAffects(wearer, item)
        } else {
          wearer.inactiveSlots = wearer.inactiveSlots - at.slot
        }
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
