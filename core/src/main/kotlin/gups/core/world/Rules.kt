package gups.core.world

import gups.core.HookRef
import gups.core.model.Actor
import gups.core.model.ModifierPoint
import gups.core.rules.Regen
import gups.core.rules.RulesContext

// The modifier chain (SPEC §4.6). Each modifiable rules result is the base value from the rules
// module, then the zone's package modifiers, the region modifiers sorted by region name, the
// room's, then the actor's affects (by id) and equipped items (in slot order).

/** One computed rules value and its chain, as `@trace rules` shows it. */
class ChainResult<T>(val value: T, val text: String)

/** The modifiers that apply to [actor] at [point], in chain order, with where each comes from. */
fun World.modifierChain(point: ModifierPoint, actor: Actor): List<Pair<String, HookRef>> {
  val room = actor.room
  val out = mutableListOf<Pair<String, HookRef>>()
  protos.zones[room.label.pkg]?.modifiers?.get(point)?.let { out += "zone:${room.label.pkg}" to it }
  for (name in room.regions.sorted()) protos.regions[name]?.modifiers?.get(point)?.let {
    out += "region:$name" to it
  }
  room.proto.modifiers[point]?.let { out += "room:${room.label.name}" to it }
  for (a in actor.affects.sortedBy { it.id }) a.modifiers[point]?.let {
    out += "affect:${a.name}" to it
  }
  val slots = equipmentSlots()
  for (slot in slots + (actor.equipmentIndex.keys - slots.toSet()).sorted()) {
    val item = actor.equipmentIndex[slot] ?: continue
    item.proto.modifiers[point]?.let { out += "item:${item.protoLabel.name}" to it }
  }
  return out
}

/** The rules module's equipment slots, asked once and again after each reload (D-113). */
fun World.equipmentSlots(): List<String> {
  slotCache?.let {
    return it
  }
  val slots =
    try {
      rules.equipmentSlots()
    } catch (e: Exception) {
      if (config.strict) throw e
      diagnose("rules", "equipment_slots failed: ${e.message}", e)
      gups.core.rules.DefaultRules.DEFAULT_SLOTS
    }
  slotCache = slots
  return slots
}

/**
 * Runs [base] through the chain for [point] of [c]'s actor. A cast_allowed chain stops at the first
 * denial. Modifiers that fail leave the value as it was (the host reports them).
 */
private fun <T> World.chain(
  point: ModifierPoint,
  c: RulesContext,
  base: T,
  show: (T) -> String,
): ChainResult<T> {
  val links = modifierChain(point, c.actor)
  val text = StringBuilder("rules.${point.scriptName} base=${show(base)}")
  var v = base
  for ((source, ref) in links) {
    @Suppress("UNCHECKED_CAST")
    v = scripts.modify(this, point, ref, source, c, v) as T
    text.append(" → ").append(source).append(' ').append(show(v))
    if (point == ModifierPoint.CAST_ALLOWED && v != null) break
  }
  return ChainResult(v, text.toString())
}

/** Sends one line to whoever traces rules (`@trace rules`, the harness trace). */
fun World.traceRules(actor: Actor?, line: String) {
  onRules?.invoke(actor, line)
}

private fun <T> World.traced(c: RulesContext, r: ChainResult<T>): T {
  traceRules(c.actor, r.text)
  return r.value
}

fun World.toHitChain(c: RulesContext): ChainResult<Int> =
  chain(ModifierPoint.TO_HIT, c, rules.toHit(c)) { "$it" }

fun World.damageChain(c: RulesContext): ChainResult<Int> =
  chain(ModifierPoint.DAMAGE, c, rules.damage(c)) { "$it" }

fun World.initiativeOf(c: RulesContext): Int =
  traced(c, chain(ModifierPoint.INITIATIVE, c, rules.initiative(c)) { "$it" })

/** Null to allow, or the denial message. */
fun World.castAllowed(c: RulesContext): String? =
  traced(
    c,
    chain<String?>(ModifierPoint.CAST_ALLOWED, c, rules.castAllowed(c)) {
      it?.let { m -> "deny('$m')" } ?: "allow"
    },
  )

fun World.moveCost(c: RulesContext): Int =
  chain(ModifierPoint.MOVE_COST, c, rules.moveCost(c)) { "$it" }.value

fun World.regenOf(c: RulesContext): Regen =
  traced(
    c,
    chain(ModifierPoint.REGEN, c, rules.regen(c)) {
      "{hp ${it.hp}, mana ${it.mana}, move ${it.move}}"
    },
  )
