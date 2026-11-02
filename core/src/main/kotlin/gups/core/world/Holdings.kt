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

/** Prototype affects in declaration order, then instance affects by id; empty for a tombstone. */
fun Item.heldAffects(): List<HeldAffect> {
  if (proto.kind == TOMBSTONE) return emptyList()
  val out = mutableListOf<HeldAffect>()
  for (a in proto.affects) out +=
    HeldAffect(this, a.name, a.applies, a.statDeltas, a.senseDeltas, a.modifiers, a.flags, null)
  for (a in affects.sortedBy { it.id }) out +=
    HeldAffect(
      this,
      a.name,
      a.applies ?: AffectScope.WORN,
      a.statDeltas,
      a.senseDeltas,
      a.modifiers,
      a.flags,
      a,
    )
  return out
}

/** What [a]'s items contribute right now, computed from scratch. Doesn't change [a]. */
internal fun World.computeHeld(a: Actor): HeldEffects {
  val statDeltas = mutableMapOf<String, Int>()
  val senseDeltas = mutableMapOf<Channel, Int>()
  val flags = mutableSetOf<String>()
  val modifierSteps = mutableListOf<Pair<String, Modifiers>>()
  fun add(ha: HeldAffect) {
    for ((stat, delta) in ha.statDeltas) statDeltas[stat] = (statDeltas[stat] ?: 0) + delta
    for ((channel, delta) in ha.senseDeltas) senseDeltas[channel] =
      (senseDeltas[channel] ?: 0) + delta
    flags += ha.flags - "stunned"
    if (ha.modifiers.isNotEmpty())
      modifierSteps += "item:${ha.item.protoLabel.name}:${ha.name}" to ha.modifiers
  }
  val slots = equipmentSlots()
  val worn =
    (slots + (a.equipmentIndex.keys - slots.toSet()).sorted()).mapNotNull { slot ->
      a.equipmentIndex[slot]
    }
  for (item in worn) {
    for ((stat, delta) in item.proto.statDeltas) statDeltas[stat] = (statDeltas[stat] ?: 0) + delta
    for ((channel, delta) in item.proto.senseDeltas) senseDeltas[channel] =
      (senseDeltas[channel] ?: 0) + delta
    if (item.proto.modifiers.isNotEmpty())
      modifierSteps += "item:${item.protoLabel.name}" to item.proto.modifiers
    for (ha in item.heldAffects()) add(ha)
  }
  fun blocks(f: ContainmentFilter, container: Item, inner: Item, ha: HeldAffect): Boolean =
    runCatching { f.blocks(this, a, container, inner, ha) }
      .getOrElse { e ->
        record(Diagnostic(pulse, "plugin", "containment filter failed: ${e.message}"))
        false
      }
  fun visit(item: Item, path: List<Item>) {
    for (ha in item.heldAffects()) {
      if (ha.applies != AffectScope.CARRIED) continue
      if (path.any { c -> containmentFilters.any { f -> blocks(f, c, item, ha) } }) continue
      add(ha)
    }
    for (inner in item.contentsIndex.values) visit(inner, path + item)
  }
  for (item in a.inventoryIndex.values) visit(item, emptyList())
  for (item in worn) for (inner in item.contentsIndex.values) visit(inner, listOf(item))
  return HeldEffects(
    statDeltas = statDeltas.filterValues { it != 0 },
    senseDeltas = senseDeltas.filterValues { it != 0 },
    flags = flags,
    modifierSteps = modifierSteps,
  )
}
