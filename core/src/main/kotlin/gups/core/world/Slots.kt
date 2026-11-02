package gups.core.world

import gups.core.model.Actor
import gups.core.model.Item
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
  var raw: List<String>? = null
  guarded("actor_slots of ${a.name}") { raw = rules.actorSlots(RulesContext(this, a)) }
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

/** Forgets [a]'s cached slots, so the next [actorSlots] asks the rules again. */
fun World.forgetActorSlots(a: Actor) {
  a.slotCache = null
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
