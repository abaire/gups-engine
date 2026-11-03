package gups.core.world

import gups.core.HookRef
import gups.core.model.Actor
import gups.core.model.ItemLocation
import gups.core.model.ModifierPoint
import gups.core.model.Trait
import gups.core.model.TraitModifier
import gups.core.rules.RulesContext

// The modifier chain. Each modifiable rules result is the base value from the rules
// module, then the zone's package modifiers, the region modifiers sorted by region name, the
// room's, then the actor's traits (in name order), affects (by id) and equipped items (in slot
// order).

/** One computed rules value and its chain, as `@trace rules` shows it. */
class ChainResult<T>(val value: T, val text: String)

private sealed interface ChainStep {
  val source: String

  fun apply(world: World, point: ModifierPoint, c: RulesContext, v: Any?): Any?

  class Script(override val source: String, val ref: HookRef) : ChainStep {
    override fun apply(world: World, point: ModifierPoint, c: RulesContext, v: Any?): Any? =
      world.scripts.modify(world, point, ref, source, c, v)
  }

  class TraitStep(val trait: Trait, val mod: TraitModifier) : ChainStep {
    override val source: String = "trait:${trait.name}"

    override fun apply(world: World, point: ModifierPoint, c: RulesContext, v: Any?): Any? =
      try {
        mod.modify(c, v ?: "")
      } catch (e: Exception) {
        world.diagnose("plugin", "trait ${trait.name} modifier failed: ${e.message}", e)
        v
      }
  }
}

/**
 * The modifiers that apply to [c]'s actor at [point], in chain order, with where each comes from.
 */
private fun World.chainSteps(point: ModifierPoint, c: RulesContext): List<ChainStep> {
  val actor = c.requireActor()
  val room = actor.room
  val out = mutableListOf<ChainStep>()
  protos.zones[room.label.pkg]?.modifiers?.get(point)?.let {
    out += ChainStep.Script("zone:${room.label.pkg}", it)
  }
  for (name in room.regions.sorted()) protos.regions[name]?.modifiers?.get(point)?.let {
    out += ChainStep.Script("region:$name", it)
  }
  room.proto.modifiers[point]?.let { out += ChainStep.Script("room:${room.label.name}", it) }
  for (t in actor.traits) {
    val mod = t.modifiers[point] ?: continue
    out += ChainStep.TraitStep(t, mod)
  }
  for (a in actor.affects.sortedBy { it.id }) a.modifiers[point]?.let {
    out += ChainStep.Script("affect:${a.name}", it)
  }
  for (step in actor.heldEffects.modifierSteps) {
    if (
      (point == ModifierPoint.TO_HIT || point == ModifierPoint.DAMAGE) &&
        c.slot != null &&
        step.item.proto.dice != null &&
        step.item.location is ItemLocation.Equipped &&
        step.item !== c.weapon
    ) {
      continue
    }
    step.modifiers[point]?.let { out += ChainStep.Script(step.source, it) }
  }
  return out
}

/** The rules module's equipment slots, asked once and again after each reload. */
fun World.equipmentSlots(): List<String> {
  slotCache?.let {
    return it
  }
  val slots = rules.equipmentSlots()
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
  val steps = chainSteps(point, c)
  val text = StringBuilder("rules.${point.scriptName} base=${show(base)}")
  var v = base
  for (step in steps) {
    @Suppress("UNCHECKED_CAST")
    v = step.apply(this, point, c, v) as T
    text.append(" → ").append(step.source).append(' ').append(show(v))
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

fun World.damageChain(c: RulesContext, base: Int = rules.damage(c)): ChainResult<Int> =
  chain(ModifierPoint.DAMAGE, c, base) { "$it" }

fun World.initiativeOf(c: RulesContext): Int =
  traced(c, chain(ModifierPoint.INITIATIVE, c, rules.initiative(c)) { "$it" })

fun World.advanceLagOf(c: RulesContext): Int =
  lagOf(ModifierPoint.ADVANCE_LAG, c, rules.advanceLag(c))

fun World.retreatLagOf(c: RulesContext): Int =
  lagOf(ModifierPoint.RETREAT_LAG, c, rules.retreatLag(c))

fun World.retreatFailLagOf(c: RulesContext): Int =
  lagOf(ModifierPoint.RETREAT_FAIL_LAG, c, rules.retreatFailLag(c))

fun World.fleeFailLagOf(c: RulesContext): Int =
  lagOf(ModifierPoint.FLEE_FAIL_LAG, c, rules.fleeFailLag(c))

/** Runs a recovery lag through [point]'s chain. Steps may go below 0; the result is at least 0. */
private fun World.lagOf(point: ModifierPoint, c: RulesContext, base: Int): Int {
  val r = chain(point, c, base) { "$it" }
  return if (r.value < 0) {
    traceRules(c.actor, "${r.text} → clamp 0")
    0
  } else {
    traceRules(c.actor, r.text)
    r.value
  }
}

/** Null to allow, or the denial message. */
fun World.castAllowed(c: RulesContext): String? {
  val req =
    gups.core.model.RestrictionRequest(
      point = gups.core.model.RestrictionPoint.CAST,
      actor = c.requireActor(),
      skill = c.skill,
      target = c.target,
    )
  val refused = restrictionRefusal(gups.core.model.RestrictionPoint.CAST, req)
  if (refused != null) return refused
  return traced(
    c,
    chain<String?>(ModifierPoint.CAST_ALLOWED, c, rules.castAllowed(c)) {
      it?.let { m -> "deny('$m')" } ?: "allow"
    },
  )
}

fun World.moveCost(c: RulesContext): Map<String, Int> =
  chain(ModifierPoint.MOVE_COST, c, rules.moveCost(c)) { it.show() }.value

fun World.regenOf(c: RulesContext): Map<String, Int> =
  traced(c, chain(ModifierPoint.REGEN, c, rules.regen(c)) { it.show() })

private fun Map<String, Int>.show(): String =
  entries.joinToString(", ", "{", "}") { "${it.key} ${it.value}" }
