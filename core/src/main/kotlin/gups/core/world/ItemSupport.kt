package gups.core.world

import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Exit
import gups.core.model.Item
import gups.core.model.ItemProto
import gups.core.model.RestrictionRequest
import gups.core.model.Room
import gups.core.rules.RulesContext

/** How many of the [n] wanted units of [item] fit within [free] weight units. */
internal fun fitWithin(free: Int, item: Item, n: Int): Int =
  if (item.proto.stackable) {
    if (item.proto.weight <= 0) n else (free / item.proto.weight).coerceAtLeast(0)
  } else {
    if (item.weight <= free) 1 else 0
  }

/** How many of the [n] wanted units of [item] fit within [actor]'s remaining carry limit. */
internal fun World.carryFit(actor: Actor, item: Item, n: Int): Int =
  fitWithin(
    rules.carryLimit(RulesContext(this, actor, item = item)) - actor.carriedWeight(),
    item,
    n,
  )

/** True if [item] would merge into a stack in [box], taking no new slot. */
internal fun World.mergesInto(item: Item, box: Item): Boolean {
  if (!item.canStack()) return false
  return box.contents.any {
    it !== item &&
      it.protoLabel == item.protoLabel &&
      it.canStack() &&
      it.decayAt == item.decayAt &&
      it.charges == item.charges &&
      it.customAttributes == item.customAttributes
  }
}

/**
 * Null if [actor] can reach [item] (carried, or on the ground within `get_lag` reach), else why
 * not. Without the track everything is within reach.
 */
internal fun World.reach(actor: Actor, item: Item): String? {
  if (Feature.TRACK !in features) return null
  val index = item.groundIndex ?: return null
  val band = Band.between(indexOf(actor), index)
  return if (rules.getLag(RulesContext(this, actor, item = item, band = band), band) == null)
    msg(KernelMsg.CANT_REACH, "item" to item.name)
  else null
}

/**
 * True if this item does nothing but open locks: `kind = "key"` and no slot, container, commands,
 * dice, wear effects or affects. Such keys go on the keyring instead of the inventory listing.
 */
internal val ItemProto.isKeyOnly: Boolean
  get() = onlyOpensLocks()

private fun ItemProto.onlyOpensLocks() =
  kind == "key" && wear == null && container == null && hasNoUses() && hasNoEffects()

private fun ItemProto.hasNoUses() = commands.isEmpty() && dice == null

private fun ItemProto.hasNoEffects() =
  statDeltas.isEmpty() &&
    senseDeltas.isEmpty() &&
    equipAffects.isEmpty() &&
    affects.isEmpty() &&
    modifiers.isEmpty()

/**
 * True if [this] goes on the keyring: its prototype is key-only and this instance has gained no
 * affects or tags of its own.
 */
internal val Item.onKeyring: Boolean
  get() = isPlainKey()

private fun Item.isPlainKey() = proto.isKeyOnly && affects.isEmpty() && addedTags.isEmpty()

/** True if [this] carries or wears an item of prototype [key], keyring keys included. */
internal fun Actor.hasKey(key: Label): Boolean =
  inventory.any { it.protoLabel == key } || equipment.values.any { it.protoLabel == key }

/** The other side of [exit], if it leads back here with a door. */
internal fun World.reverseExit(room: Room, exit: Exit): Pair<Room, Exit>? {
  val other = room(exit.to)
  val back =
    other.exits.values.firstOrNull { it.to == room.label && it.door != null } ?: return null
  return other to back
}

/** Finds an actor in [viewer]'s room by keyword; `2.goblin` picks the second match. */
fun World.findActor(viewer: Actor, keyword: String): Actor? {
  val (n, word) = keyword.ordinal()
  if (word == "self" || word == "me") return viewer
  val room = roomOf(viewer)
  // Dormant actors are inert to simulated ones.
  return room.actorIndex.values
    .filter { it !== viewer && isPresent(it, room) && it.matches(word) && isSimulated(it) }
    .getOrNull(n - 1)
}

fun World.findItem(viewer: Actor, keyword: String, inventory: Boolean, ground: Boolean): Item? {
  if (keyword.isBlank()) return null
  val (n, word) = keyword.ordinal()
  val pool = buildList {
    if (inventory) addAll(viewer.inventory)
    if (ground) addAll(roomOf(viewer).items)
  }
  return pool.filter { it.matches(word) }.getOrNull(n - 1)
}

/** Why `rules.restrictions` refuses [request], or null if it allows it. */
internal fun World.refusalFor(request: RestrictionRequest): String? =
  restrictionRefusal(request.point, request)
