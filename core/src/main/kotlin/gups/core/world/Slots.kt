package gups.core.world

import gups.core.model.Actor
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.RestrictionPoint
import gups.core.model.RestrictionRequest
import gups.core.rules.RulesContext

/** The rules' slot families, asked once and again after each reload. */
fun World.slotFamilies(): Map<String, List<String>> {
  slotFamilyCache?.let {
    return it
  }
  val families = rules.slotFamilies()
  slotFamilyCache = families
  return families
}

/** The family containing [slot], or null for a slot in no family. */
fun World.familyOfSlot(slot: String): String? =
  slotFamilies().entries.firstOrNull { slot in it.value }?.key

/** The family [wear] names, or contains, or null for a standalone slot. */
fun World.familyOfWear(wear: String): String? {
  val families = slotFamilies()
  if (wear in families) return wear
  return familyOfSlot(wear)
}

/** [a]'s slots: the rules' answer filtered to `equipmentSlots()`, cached on [a]. */
fun World.actorSlots(a: Actor): List<String> {
  a.slotCache?.let {
    return it
  }
  val allSlots = equipmentSlots()
  // The rules may read the actor's slots while working them out; that read sees every slot
  // instead of asking the rules again without end.
  if (a.askingSlots) return allSlots
  var raw: List<String>? = null
  a.askingSlots = true
  try {
    guarded("actor_slots of ${a.name}") { raw = rules.actorSlots(RulesContext(this, a)) }
  } finally {
    a.askingSlots = false
  }
  val stray = raw?.filter { it !in allSlots }.orEmpty()
  for (slot in stray) {
    diagnose("rules", "actor_slots of ${a.name} returned unknown slot '$slot'")
  }
  val valid = raw?.filter { it in allSlots }.orEmpty()
  val result =
    if (raw == null || (raw.isNotEmpty() && valid.isEmpty())) {
      allSlots
    } else {
      valid
    }
  a.slotCache = result
  return result
}

/** True if [item] is equipped in a slot that doesn't currently fit its wearer. */
fun World.isInactive(item: Item): Boolean {
  val loc = item.location as? ItemLocation.Equipped ?: return false
  val wearer = actor(loc.actor) ?: return false
  return !wearer.isActiveSlot(loc.slot)
}

/** Forgets [a]'s cached slots, so the next [actorSlots] asks the rules again. */
fun World.forgetActorSlots(a: Actor) {
  a.slotCache = null
  forgetFactions(a)
}

/** [family]'s slots that [a] has, in family order. */
fun World.familySlots(a: Actor, family: String): List<String> {
  val familyList = slotFamilies()[family] ?: return emptyList()
  val actorHas = actorSlots(a).toSet()
  return familyList.filter { it in actorHas }
}

/** The effective family cost of [item] worn by [a], at least 1. */
fun World.slotCost(a: Actor, item: Item): Int {
  var cost = item.proto.slotCost
  guarded("slot_cost of ${item.name} for ${a.name}") {
    cost = rules.slotCost(RulesContext(this, a, item = item))
  }
  return cost.coerceAtLeast(1)
}

/**
 * [ambiguous] is true when several equally small choices would take off different items; the wear
 * is then refused and the player picks what to remove.
 */
internal class SlotPlan(val slot: String, val displaced: List<Item>, val ambiguous: Boolean = false)

/**
 * Where [item] would go on [a] and what would have to come off, or null if it can't be worn at all.
 */
internal fun World.planSlot(a: Actor, item: Item): SlotPlan? {
  val wear = item.proto.wear ?: return null
  val family = familyOfWear(wear)
  if (family == null) {
    val actorHas = actorSlots(a)
    if (wear !in actorHas) return null
    return SlotPlan(wear, listOfNotNull(a.equipped(wear)))
  }

  val slots = familySlots(a, family)
  val candidates = if (wear == family) slots else listOf(wear).filter { it in slots }
  if (candidates.isEmpty()) return null
  val cost = slotCost(a, item)
  if (cost > slots.size) return null

  // Each worn item's cost is asked once: the rules may be a script call.
  val wornCosts = slots.mapNotNull { a.equipped(it) }.associateWith { slotCost(a, it) }
  val plans =
    candidates.map { candidate ->
      val displaced = mutableListOf<Item>()
      val occupant = a.equipped(candidate)
      if (occupant != null) displaced.add(occupant)

      var remainingCost = wornCosts.filterKeys { it !in displaced }.values.sum()
      while (remainingCost + cost > slots.size) {
        val nextItem = wornCosts.keys.reversed().firstOrNull { it !in displaced } ?: break
        displaced.add(nextItem)
        remainingCost -= wornCosts.getValue(nextItem)
      }
      SlotPlan(candidate, displaced)
    }

  val minDisplacedSize = plans.minOf { it.displaced.size }
  val best = plans.filter { it.displaced.size == minDisplacedSize }
  if (minDisplacedSize == 0) return best.first()

  val firstSet = best.first().displaced.toSet()
  if (best.all { it.displaced.toSet() == firstSet }) {
    return best.first()
  }

  return SlotPlan(best.first().slot, best.first().displaced, ambiguous = true)
}

/**
 * True if an item whose `wear` is [wear] may sit in [slot]: the slot itself, or one of its family.
 */
fun World.wearFits(wear: String, slot: String): Boolean =
  slot == wear || slotFamilies()[wear]?.contains(slot) == true

/**
 * Recomputes which of [a]'s worn items fit the actor's current slots and capacity, and applies the
 * change.
 *
 * Items in slots the actor no longer has, or in families exceeding capacity when walked in family
 * slot order, are marked inactive. Inactive items remain equipped and keep their weight, but
 * contribute nothing as worn items (no deltas, modifiers, worn-scope item affects or equip
 * affects). When capacity permits them to fit again, their untimed equip affects are restored and
 * they become active again.
 *
 * Passes repeat because an item going inactive can change what the rules' slot answers read, up to
 * a bound; the last pass stands. The wearer is told only about the net change across all passes,
 * and only if [announce].
 */
fun World.refreshFit(a: Actor, announce: Boolean = true) = holdingAffectEvents {
  refit(a, announce)
}

private fun World.refit(a: Actor, announce: Boolean) {
  if (!a.inWorld || a.refittingSlots) return
  val initial = a.inactiveSlots
  var changed = false
  a.refittingSlots = true
  try {
    var passes = a.equipmentIndex.size + 1
    while (passes-- > 0) {
      val next = unfitSlots(a)
      if (next == a.inactiveSlots) break
      val old = a.inactiveSlots
      a.inactiveSlots = next
      a.dirty = true
      changed = true
      for (slot in next - old) a.equipped(slot)?.let { removeEquipAffects(a, it) }
      for (slot in old - next) a.equipped(slot)?.let {
        applyEquipAffects(a, it, untimedOnly = true)
      }
      refreshHeld(a)
      updateEncumbrance(a)
    }
  } finally {
    a.refittingSlots = false
  }
  if (!changed) return
  clampVitals(a)
  if (!announce) return
  for (slot in a.inactiveSlots - initial) a.equipped(slot)?.let {
    val refusal = a.wearRefusals[slot]
    if (refusal != null) send(a, refusal) else tell(a, KernelMsg.ITEM_INACTIVE, "item" to it.name)
  }
  for (slot in initial - a.inactiveSlots) a.equipped(slot)?.let {
    tell(a, KernelMsg.ITEM_ACTIVE, "item" to it.name)
  }
}

/**
 * Asks the `wear` restriction again about each of [a]'s worn items, after a change the restriction
 * may read (choices, traits, a reload or a restore, or a change to the effective tags, prototype or
 * item affects of something [a] wears), and reruns the fit check. A refused item becomes inactive
 * rather than coming off, and stays inactive until a later re-check allows it; the wearer is sent
 * the refusal if [announce].
 */
fun World.recheckWornItems(a: Actor, announce: Boolean = true) {
  askWearRestriction(a)
  refreshFit(a, announce)
}

/**
 * Refreshes whoever holds [item] after a change to its effective tags, prototype or affects, and
 * re-asks the `wear` restriction about everything its wearer has on when [item] is worn.
 */
internal fun World.itemChanged(item: Item) {
  val holder = custodian(item) ?: return
  if (item.location is ItemLocation.Equipped) askWearRestriction(holder)
  refreshHoldings(holder)
}

/** Records which of [a]'s worn items the `wear` restriction refuses now, without refitting. */
internal fun World.askWearRestriction(a: Actor) {
  a.wearRefusals =
    a.equipmentIndex.entries
      .mapNotNull { (slot, item) ->
        val req =
          RestrictionRequest(point = RestrictionPoint.WEAR, actor = a, item = item, slot = slot)
        restrictionRefusal(RestrictionPoint.WEAR, req)?.let { slot to it }
      }
      .toMap()
}

/**
 * [a]'s worn slots that don't count: slots it lacks, items the `wear` restriction refused, then
 * family overflow in family slot order.
 */
private fun World.unfitSlots(a: Actor): Set<String> {
  val slots = actorSlots(a).toSet()
  val unfit =
    a.equipmentIndex.keys.filterTo(LinkedHashSet()) { it !in slots || it in a.wearRefusals }
  for (family in slotFamilies().keys) {
    val familySlots = familySlots(a, family)
    var used = 0
    for (slot in familySlots) {
      val item = a.equipped(slot) ?: continue
      val cost = slotCost(a, item)
      if (used + cost > familySlots.size) unfit += slot else used += cost
    }
  }
  return unfit
}
