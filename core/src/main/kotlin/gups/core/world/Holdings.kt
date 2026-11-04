package gups.core.world

import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.AffectScope
import gups.core.model.Channel
import gups.core.model.DamageType
import gups.core.model.Item
import gups.core.model.Modifiers
import gups.core.model.Proc

/** What an actor's items contribute to it, recomputed by [refreshHoldings]. */
data class HeldEffects(
  val statDeltas: Map<String, Int> = emptyMap(),
  val senseDeltas: Map<Channel, Int> = emptyMap(),
  /** Summed changes to vital maximums, by vital name. */
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  /** Summed changes to resistances, by damage type. */
  val resistDeltas: Map<DamageType, Int> = emptyMap(),
  val flags: Set<String> = emptySet(),
  /** The modifiers each held item contributes, in modifier-chain order. */
  val modifierSteps: List<HeldModifiers> = emptyList(),
  val procSteps: List<HeldProcs> = emptyList(),
) {
  companion object {
    val EMPTY = HeldEffects()
  }
}

/** Modifiers one held item contributes, with the item they come from. */
data class HeldModifiers(val source: String, val item: Item, val modifiers: Modifiers)

/** Procs one held item contributes, with the item they come from. */
data class HeldProcs(val source: String, val item: Item, val procs: List<Proc>)

/** One item affect as seen by the holder: from the prototype or from the instance. */
class HeldAffect(
  val item: Item,
  val name: String,
  val applies: AffectScope,
  val statDeltas: Map<String, Int>,
  val senseDeltas: Map<Channel, Int>,
  /** Changes to the holder's vital maximums, by vital name. */
  val maxVitalDeltas: Map<String, Int>,
  /** Changes to the holder's resistances, by damage type. */
  val resistDeltas: Map<DamageType, Int>,
  val modifiers: Modifiers,
  val flags: Set<String>,
  /** The instance affect, or null for a prototype affect. */
  val instance: Affect?,
  val procs: List<Proc> = emptyList(),
)

/** A plugin check that can stop an item affect from reaching its holder through a container. */
fun interface ContainmentFilter {
  fun blocks(world: World, holder: Actor, container: Item, item: Item, affect: HeldAffect): Boolean
}

/** Recomputes what [a]'s held items contribute to it. */
fun World.refreshHeld(a: Actor) {
  a.heldEffects = computeHeld(a)
}

/**
 * Recomputes everything [a] derives from what it holds: item contributions, encumbrance, and fit.
 * The wearer is told about items that stop or start fitting only if [announceFit].
 */
fun World.refreshHoldings(a: Actor, announceFit: Boolean = true) {
  refreshHeld(a)
  updateEncumbrance(a, announceFit)
  clampVitals(a)
  refreshFit(a, announceFit)
}

/** Prototype affects in declaration order, then instance affects by id; empty for a tombstone. */
fun Item.heldAffects(): List<HeldAffect> =
  if (proto.kind == TOMBSTONE) emptyList() else adminAffectsList()

/**
 * Prototype affects in declaration order, then instance affects by id, even on a tombstone, whose
 * kept instance affects reach no one but still show what the item was. For admin display only:
 * entries may not be in play, so anything that applies, propagates or exposes affects to scripts or
 * plugins must use [heldAffects].
 */
internal fun Item.adminAffectsList(): List<HeldAffect> {
  val out = mutableListOf<HeldAffect>()
  for (a in proto.affects) out +=
    HeldAffect(
      this,
      a.name,
      a.applies,
      a.statDeltas,
      a.senseDeltas,
      a.maxVitalDeltas,
      a.resistDeltas,
      a.modifiers,
      a.flags,
      null,
      a.procs,
    )
  for (a in affects.sortedBy { it.id }) out +=
    HeldAffect(
      this,
      a.name,
      a.applies ?: AffectScope.WORN,
      a.statDeltas,
      a.senseDeltas,
      a.maxVitalDeltas,
      a.resistDeltas,
      a.modifiers,
      a.flags,
      a,
      a.procs,
    )
  return out
}

/** What [a]'s items contribute right now, computed from scratch. Doesn't change [a]. */
internal fun World.computeHeld(a: Actor): HeldEffects {
  val statDeltas = mutableMapOf<String, Int>()
  val senseDeltas = mutableMapOf<Channel, Int>()
  val maxVitalDeltas = mutableMapOf<String, Int>()
  val resistDeltas = mutableMapOf<DamageType, Int>()
  val flags = mutableSetOf<String>()
  val modifierSteps = mutableListOf<HeldModifiers>()
  val procSteps = mutableListOf<HeldProcs>()
  fun add(ha: HeldAffect) {
    for ((stat, delta) in ha.statDeltas) statDeltas[stat] = (statDeltas[stat] ?: 0) + delta
    for ((channel, delta) in ha.senseDeltas) senseDeltas[channel] =
      (senseDeltas[channel] ?: 0) + delta
    for ((vital, delta) in ha.maxVitalDeltas) maxVitalDeltas[vital] =
      (maxVitalDeltas[vital] ?: 0) + delta
    for ((type, delta) in ha.resistDeltas) resistDeltas[type] = (resistDeltas[type] ?: 0) + delta
    flags += ha.flags - "stunned"
    if (ha.modifiers.isNotEmpty())
      modifierSteps +=
        HeldModifiers("item:${ha.item.protoLabel.name}:${ha.name}", ha.item, ha.modifiers)
    if (ha.procs.isNotEmpty())
      procSteps += HeldProcs("item:${ha.item.protoLabel.name}:${ha.name}", ha.item, ha.procs)
  }
  val slots = equipmentSlots()
  val worn =
    (slots + (a.equipmentIndex.keys - slots.toSet()).sorted()).mapNotNull { slot ->
      a.equipmentIndex[slot]?.let { slot to it }
    }
  for ((slot, item) in worn) {
    if (a.isActiveSlot(slot)) {
      for ((stat, delta) in item.proto.statDeltas) statDeltas[stat] =
        (statDeltas[stat] ?: 0) + delta
      for ((channel, delta) in item.proto.senseDeltas) senseDeltas[channel] =
        (senseDeltas[channel] ?: 0) + delta
      for ((vital, delta) in item.proto.maxVitalDeltas) maxVitalDeltas[vital] =
        (maxVitalDeltas[vital] ?: 0) + delta
      for ((type, delta) in item.proto.resistDeltas) resistDeltas[type] =
        (resistDeltas[type] ?: 0) + delta
      if (item.proto.modifiers.isNotEmpty())
        modifierSteps += HeldModifiers("item:${item.protoLabel.name}", item, item.proto.modifiers)
      if (item.proto.procs.isNotEmpty())
        procSteps += HeldProcs("item:${item.protoLabel.name}", item, item.proto.procs)
      for (ha in item.heldAffects()) add(ha)
    }
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
  for ((slot, item) in worn) {
    if (a.isActiveSlot(slot)) {
      for (inner in item.contentsIndex.values) visit(inner, listOf(item))
    } else {
      visit(item, emptyList())
    }
  }
  return HeldEffects(
    statDeltas = statDeltas.filterValues { it != 0 },
    senseDeltas = senseDeltas.filterValues { it != 0 },
    maxVitalDeltas = maxVitalDeltas.filterValues { it != 0 },
    resistDeltas = resistDeltas.filterValues { it != 0 },
    flags = flags,
    modifierSteps = modifierSteps,
    procSteps = procSteps,
  )
}
