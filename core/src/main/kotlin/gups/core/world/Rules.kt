package gups.core.world

import gups.core.HookRef
import gups.core.MOVE_PACE_MIN
import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.DamageType
import gups.core.model.ItemLocation
import gups.core.model.ModifierPoint
import gups.core.model.Trait
import gups.core.model.TraitModifier
import gups.core.rules.RulesContext

// The modifier chain. Each modifiable rules result is the base value from the rules
// module, then the zone's package modifiers, the region modifiers sorted by region name, the
// room's, then the room's live affects (by id), then the actor's traits (in name order),
// affects (by id) and equipped items (in slot order).

/** One computed rules value and its chain, as `@trace rules` shows it. */
class ChainResult<T>(val value: T, val text: String)

private sealed interface ChainStep {
  val source: String

  fun apply(world: World, point: ModifierPoint, c: RulesContext, v: Any?): Any?

  class Script(override val source: String, val ref: HookRef, val affect: Affect? = null) :
    ChainStep {
    override fun apply(world: World, point: ModifierPoint, c: RulesContext, v: Any?): Any? =
      world.scripts.modify(world, point, ref, source, c.withAffect(affect), v)
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
 * The modifiers that apply to [c]'s actor at [point], in chain order, with where each comes from. A
 * defender point reads the target instead of the actor.
 */
private fun World.chainSteps(point: ModifierPoint, c: RulesContext): List<ChainStep> {
  val actor = if (point.defender) c.target else c.requireActor()
  if (actor == null) return emptyList()
  val room = actor.room
  val out = mutableListOf<ChainStep>()
  protos.zones[room.label.pkg]?.modifiers?.get(point)?.let {
    out += ChainStep.Script("zone:${room.label.pkg}", it)
  }
  for (name in room.regions.sorted()) protos.regions[name]?.modifiers?.get(point)?.let {
    out += ChainStep.Script("region:$name", it)
  }
  room.proto.modifiers[point]?.let { out += ChainStep.Script("room:${room.label.name}", it) }
  for (a in room.liveAffects(roomClock)) a.modifiers[point]?.let {
    out += ChainStep.Script("room-affect:${a.name}", it, affect = a)
  }
  for (t in actor.traits) {
    val mod = t.modifiers[point] ?: continue
    out += ChainStep.TraitStep(t, mod)
  }
  for (a in actor.affects.sortedBy { it.id }) a.modifiers[point]?.let {
    out += ChainStep.Script("affect:${a.name}", it, affect = a)
  }
  for (step in actor.heldEffects.modifierSteps) {
    // The swinging-weapon filter is for the attacker's points only: a defender's worn weapons
    // always contribute, so a parry dagger can carry a defend_hit modifier.
    if (
      (point == ModifierPoint.TO_HIT ||
        point == ModifierPoint.DAMAGE ||
        point == ModifierPoint.BONUS_DAMAGE) &&
        c.slot != null &&
        step.item.proto.dice != null &&
        step.item.location is ItemLocation.Equipped &&
        step.item !== c.weapon
    ) {
      continue
    }
    step.modifiers[point]?.let { out += ChainStep.Script(step.source, it, affect = step.affect) }
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

/** Runs each of [steps] in order, appending each to [text]; returns the final value. */
private fun <T> World.runSteps(
  point: ModifierPoint,
  c: RulesContext,
  steps: List<ChainStep>,
  base: T,
  text: StringBuilder,
  show: (T) -> String,
): T {
  var v = base
  for (step in steps) {
    @Suppress("UNCHECKED_CAST")
    v = step.apply(this, point, c, v) as T
    text.append(" → ").append(step.source).append(' ').append(show(v))
    if (point == ModifierPoint.CAST_ALLOWED && v != null) break
  }
  return v
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
  val v = runSteps(point, c, steps, base, text, show)
  return ChainResult(v, text.toString())
}

/**
 * Runs the defender's chain at [point] (a defender point) on [base]. The text is empty when the
 * defender has no step at [point], otherwise " → defender:<point>" and each step.
 */
private fun <T> World.defenderSegment(
  point: ModifierPoint,
  c: RulesContext,
  base: T,
  show: (T) -> String,
): ChainResult<T> {
  val steps = chainSteps(point, c)
  if (steps.isEmpty()) return ChainResult(base, "")
  val text = StringBuilder(" → defender:${point.scriptName}")
  val v = runSteps(point, c, steps, base, text, show)
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

/** The attacker's to_hit chain, then the defender's defend_hit chain. */
fun World.toHitChain(c: RulesContext): ChainResult<Int> {
  val atk = chain(ModifierPoint.TO_HIT, c, rules.toHit(c)) { "$it" }
  val def = defenderSegment(ModifierPoint.DEFEND_HIT, c, atk.value) { "$it" }
  return ChainResult(def.value, atk.text + def.text)
}

fun World.damageChain(c: RulesContext, base: Int = rules.damage(c)): ChainResult<Int> =
  chain(ModifierPoint.DAMAGE, c, base) { "$it" }

/** One typed part of a damage event and the positive vital losses it caused. */
data class DamagePart(val type: DamageType, val losses: Map<String, Int>)

/** A hit's vital losses, its parts and its whole trace line. */
class DamageResult(val losses: Map<String, Int>, val parts: List<DamagePart>, val text: String)

/**
 * A hit or damage effect resolves as the primary part — the attacker's damage chain, the defender's
 * receive_damage chain, mitigate with [type], then the defender's mitigate chain — and the
 * attacker's bonus_damage chain. Each bonus part with an amount above 0, in the schema's damage
 * type order, runs the defender's receive_damage chain, mitigate with its own type, and the
 * defender's mitigate chain, with the damage type set to that part's type. The parts' losses are
 * added per vital, gains included, and the whole is applied as one damage event.
 */
fun World.resolveDamage(c: RulesContext, base: Int, type: DamageType): DamageResult {
  val atk = damageChain(c, base)
  val recv = defenderSegment(ModifierPoint.RECEIVE_DAMAGE, c, atk.value) { "$it" }
  val amount = recv.value.coerceAtLeast(0)
  val losses = rules.mitigate(c, amount, type)
  val mit = defenderSegment(ModifierPoint.MITIGATE, c, losses) { it.show() }
  var text = atk.text + recv.text + " → mitigate ${losses.shown()} $type" + mit.text
  val finals = mutableListOf(type to mit.value)

  val steps = chainSteps(ModifierPoint.BONUS_DAMAGE, c)
  val bonus: Map<String, Int> =
    if (steps.isEmpty()) emptyMap()
    else {
      val bonusText = StringBuilder(" → bonus_damage")
      val bonusMap =
        runSteps<Map<String, Int>>(ModifierPoint.BONUS_DAMAGE, c, steps, emptyMap(), bonusText) {
          v: Map<String, Int> ->
          v.showDamages()
        }
      text += bonusText
      bonusMap
    }
  for (name in schema.damageTypes) {
    val n = bonus[name] ?: continue
    if (n <= 0) continue
    val pc = c.withDamageType(DamageType(name))
    text += " → part $name $n"
    val partRecv = defenderSegment(ModifierPoint.RECEIVE_DAMAGE, pc, n) { "$it" }
    text += partRecv.text
    val partAmount = partRecv.value.coerceAtLeast(0)
    val partLosses = rules.mitigate(pc, partAmount, DamageType(name))
    text += " → mitigate ${partLosses.shown()} $name"
    val partMit = defenderSegment(ModifierPoint.MITIGATE, pc, partLosses) { it.show() }
    text += partMit.text
    finals += DamageType(name) to partMit.value
  }

  val combined = LinkedHashMap<String, Int>()
  for ((_, final) in finals) for ((k, v) in final) combined[k] = (combined[k] ?: 0) + v
  val parts = finals.map { (pType, final) -> DamagePart(pType, final.filterValues { it > 0 }) }
  return DamageResult(combined, parts, text)
}

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

/**
 * Whether c.actor gets c.skill off: the export, its chain, then a roll unless the chance is 100+.
 */
fun World.skillSucceeds(c: RulesContext): Boolean {
  val r = chain(ModifierPoint.SKILL_CHECK, c, rules.skillCheck(c)) { "$it" }
  if (r.value >= 100) {
    traceRules(c.actor, "${r.text} → auto SUCCEED")
    return true
  }
  val roll = rng.int(1, 100)
  val success = roll <= r.value
  traceRules(c.actor, "${r.text} → roll $roll ${if (success) "SUCCEED" else "FAIL"}")
  return success
}

/** The power of c.actor's successful cast of c.skill, clamped to 0 or more. */
fun World.skillPowerChain(c: RulesContext): Int =
  powerChain(ModifierPoint.SKILL_POWER, c, rules.skillPower(c))

/** The power of c.item's effect for c.actor, clamped to 0 or more. */
fun World.itemPowerChain(c: RulesContext): Int =
  powerChain(ModifierPoint.ITEM_POWER, c, rules.itemPower(c))

private fun World.powerChain(point: ModifierPoint, c: RulesContext, base: Int): Int {
  val r = chain(point, c, base) { "$it" }
  return if (r.value < 0) {
    traceRules(c.actor, "${r.text} → clamp 0")
    0
  } else {
    traceRules(c.actor, r.text)
    r.value
  }
}

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

/**
 * The walking pace, in hundredths of a pulse per room, for `c.actor`: the export with its default,
 * then its modifier chain, clamped at `MOVE_PACE_MIN`. While the chain runs, the actor's
 * `move_pace` view field reads 100, so a rule that reads it does not recurse.
 */
fun World.movePaceOf(c: RulesContext): Int {
  val actor = c.actor ?: return 100
  if (actor.movePaceInProgress) return 100
  actor.movePaceInProgress = true
  return try {
    val base = rules.movePace(c) ?: 100
    val r = chain(ModifierPoint.MOVE_PACE, c, base) { "$it" }
    if (r.value < MOVE_PACE_MIN) {
      traceRules(c.actor, "${r.text} → clamp $MOVE_PACE_MIN")
      MOVE_PACE_MIN
    } else {
      traceRules(c.actor, r.text)
      r.value
    }
  } finally {
    actor.movePaceInProgress = false
  }
}

fun World.regenOf(c: RulesContext): Map<String, Int> =
  traced(c, chain(ModifierPoint.REGEN, c, rules.regen(c)) { it.show() })

private fun Map<String, Int>.show(): String =
  entries.joinToString(", ", "{", "}") { "${it.key} ${it.value}" }

/** The losses of a mitigate result as a trace segment: `hp 4`, or `0` when there are none. */
private fun Map<String, Int>.shown(): String =
  if (isEmpty()) "0" else entries.joinToString { "${it.key} ${it.value}" }

/** A `damages(...)` value as a trace segment, as the script constructor prints it. */
private fun Map<String, Int>.showDamages(): String =
  "damages(" + entries.joinToString(", ") { "${it.key} = ${it.value}" } + ")"
