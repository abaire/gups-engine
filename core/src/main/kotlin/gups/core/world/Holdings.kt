package gups.core.world

import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.AffectScope
import gups.core.model.Channel
import gups.core.model.Item
import gups.core.model.Modifiers

/** What an actor's items contribute to it, recomputed by [refreshHoldings]. */
data class HeldEffects(
  val statDeltas: Map<String, Int> = emptyMap(),
  val senseDeltas: Map<Channel, Int> = emptyMap(),
  val flags: Set<String> = emptySet(),
  /** Chain-step label to the modifiers it contributes, in modifier-chain order. */
  val modifierSteps: List<Pair<String, Modifiers>> = emptyList(),
) {
  companion object {
    val EMPTY = HeldEffects()
  }
}

/** One item affect as seen by the holder: from the prototype or from the instance. */
class HeldAffect(
  val item: Item,
  val name: String,
  val applies: AffectScope,
  val statDeltas: Map<String, Int>,
  val senseDeltas: Map<Channel, Int>,
  val modifiers: Modifiers,
  val flags: Set<String>,
  /** The instance affect, or null for a prototype affect. */
  val instance: Affect?,
)

/** A plugin check that can stop an item affect from reaching its holder through a container. */
fun interface ContainmentFilter {
  fun blocks(world: World, holder: Actor, container: Item, item: Item, affect: HeldAffect): Boolean
}

/** Recomputes everything [a] derives from what it holds: item contributions, then encumbrance. */
fun World.refreshHoldings(a: Actor) {
  a.heldEffects = computeHeld(a)
  updateEncumbrance(a)
}

/** What [a]'s items contribute right now, computed from scratch. Doesn't change [a]. */
internal fun World.computeHeld(a: Actor): HeldEffects {
  val slots = equipmentSlots()
  val statDeltas = mutableMapOf<String, Int>()
  val senseDeltas = mutableMapOf<Channel, Int>()
  val modifierSteps = mutableListOf<Pair<String, Modifiers>>()
  for (slot in slots + (a.equipmentIndex.keys - slots.toSet()).sorted()) {
    val item = a.equipmentIndex[slot] ?: continue
    for ((stat, delta) in item.proto.statDeltas) statDeltas[stat] = (statDeltas[stat] ?: 0) + delta
    for ((channel, delta) in item.proto.senseDeltas) senseDeltas[channel] =
      (senseDeltas[channel] ?: 0) + delta
    if (item.proto.modifiers.isNotEmpty())
      modifierSteps += "item:${item.protoLabel.name}" to item.proto.modifiers
  }
  return HeldEffects(
    statDeltas = statDeltas.filterValues { it != 0 },
    senseDeltas = senseDeltas.filterValues { it != 0 },
    flags = emptySet(),
    modifierSteps = modifierSteps,
  )
}
