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

/**
 * What a fit question is about: an [item], or a prototype with no instance yet, as `fits` asks of a
 * label. [weight] is the whole stack's, contents included.
 */
internal class Fitting(val proto: ItemProto, val weight: Int, val item: Item? = null) {
  constructor(item: Item) : this(item.proto, item.weight, item)

  /** True if this would join a stack in [box], taking no new entry. */
  fun mergesInto(w: World, box: Item): Boolean =
    if (item != null) w.mergesInto(item, box) else w.protoMergesInto(proto, box)
}

/** How many of the [n] wanted units of [what] fit within [free] weight units. */
internal fun fitWithin(free: Int, what: Fitting, n: Int): Int =
  if (what.proto.stackable || what.item == null) {
    if (what.proto.weight <= 0) n else (free / what.proto.weight).coerceAtLeast(0)
  } else {
    if (what.weight <= free) 1 else 0
  }

/** The carry limit of [actor] asked about [what], which the rules see as `item` and `item_info`. */
internal fun World.carryLimitFor(actor: Actor, what: Fitting): Int =
  rules.carryLimit(RulesContext(this, actor, item = what.item, itemProto = what.proto))

/** How many of the [n] wanted units of [what] fit within [actor]'s remaining carry limit. */
internal fun World.carryFit(actor: Actor, what: Fitting, n: Int): Int =
  fitWithin(carryLimitFor(actor, what) - actor.carriedWeight(), what, n)

/** What the weight and the entries of [box] leave for [what]; the limits `put` enforces. */
internal class BoxRoom(private val w: World, private val box: Item, private val what: Fitting) {
  private val state = requireNotNull(box.container) { "${box.name} is not a container" }

  /** How many of the [n] wanted units the box's free weight takes. */
  fun byWeight(n: Int): Int =
    minOf(n, fitWithin(state.maxWeight - box.contents.sumOf { it.weight }, what, n))

  /** How many of the [n] wanted units the box's free entries take: all of a stack that merges. */
  fun byEntries(n: Int): Int {
    if (what.mergesInto(w, box)) return n
    val free = (state.capacity - box.contents.size).coerceAtLeast(0)
    return if (what.proto.stackable) (if (free > 0) n else 0) else minOf(n, free)
  }

  fun fit(n: Int): Int = minOf(byEntries(n), byWeight(n))
}

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

/** True if [box] holds a stack a fresh [proto] spawn would merge into. */
private fun World.protoMergesInto(proto: ItemProto, box: Item): Boolean =
  proto.stackable &&
    proto.decayPulses == null &&
    box.contents.any {
      it.protoLabel == proto.label &&
        it.canStack() &&
        it.decayAt == null &&
        it.charges == proto.charges &&
        it.customAttributes.isEmpty()
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
