package gups.core.world

import gups.core.Label
import gups.core.model.Actor
import gups.core.model.AffectScope
import gups.core.model.Effect
import gups.core.model.Item
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.SkillProto
import gups.core.model.TargetKind
import gups.core.rules.RulesContext

/** The skill an effect comes from, if any, and the percentage its amounts are scaled by. */
data class EffectContext(val skill: SkillProto? = null, val power: Int = 100)

/**
 * Scales a declared amount by [power] percent: rounded down, never below 1 when it was positive.
 */
internal fun scaled(amount: Int, power: Int): Int =
  if (amount <= 0) amount else maxOf(1, (amount.toLong() * power / 100).toInt())

/**
 * Like [scaled] for a Long amount: rounded down, never below 1 when positive, and stopping at
 * Long.MAX_VALUE. A duration of Long.MAX_VALUE means "never ends" and is never scaled.
 */
internal fun scaledDuration(amount: Long, power: Int): Long {
  if (amount <= 0 || amount == Long.MAX_VALUE) return amount
  if (power <= 0) return 1L
  // amount * power / 100, split so the intermediate product can't overflow.
  val q = amount / 100
  val r = amount % 100
  if (q > (Long.MAX_VALUE - r * power / 100) / power) return Long.MAX_VALUE
  return maxOf(1L, q * power + r * power / 100)
}

// ---- Summons ---------------------------------------------------------------------------------

/**
 * Spawns [effect]'s mobs beside [caster] as its summons; a null duration means they stay. [power]
 * scales how long they last.
 */
fun World.summon(caster: Actor, effect: Effect.Summon, power: Int = 100): List<Mob> {
  val duration = effect.duration
  require(effect.count >= 1) { "summon: count must be at least 1" }
  require(duration == null || duration >= 1) { "summon: duration must be at least 1" }
  if (isSummonDenied(caster, effect.label, effect.count)) return emptyList()
  val owning = caster as? Player
  val mobs = List(effect.count) { spawnSummon(caster, effect, owning, power) }
  if (owning != null) announceOwned(mobs, owning)
  if (mobs.isNotEmpty()) {
    dispatch(HookName.ON_SUMMON, caster, caster.room, HookContext(actor = caster, mobs = mobs))
  }
  return mobs
}

/** Asks `before_summon`; a denial is told to [caster] and stops the summoning. */
private fun World.isSummonDenied(caster: Actor, label: Label, count: Int): Boolean {
  val denial =
    dispatch(
      HookName.BEFORE_SUMMON,
      caster,
      caster.room,
      HookContext(actor = caster, summonLabel = label, count = count),
    ) ?: return false
  send(caster, denial)
  return true
}

private fun World.spawnSummon(
  caster: Actor,
  effect: Effect.Summon,
  owning: Player?,
  power: Int,
): Mob {
  val mob =
    spawnMob(effect.label, caster.room, caster.trackIndex) { fresh ->
      fresh.summonedBy = caster.id
      if (owning != null && owning.inWorld) fresh.owner = owning.id
    }
  val keeper = owning?.takeIf { mob.owner != null && mob.inWorld }
  if (keeper != null) assignOwner(mob, keeper)
  effect.duration?.let { bindSummon(mob, caster, it, power) }
  return mob
}

/** Makes [mob] a summon that is banished after [duration] pulses, scaled by [power]. */
private fun World.bindSummon(mob: Mob, caster: Actor, duration: Int, power: Int) {
  val spec =
    AffectSpec(
      "summoned",
      scaled(duration, power).toLong(),
      flags = setOf("summoned"),
      source = caster,
      power = power,
    )
  applyAffect(mob, spec)
}

private fun World.announceOwned(mobs: List<Mob>, owning: Player) {
  for (mob in mobs.sortedBy { it.id }) {
    if (mob.inWorld && mob.owner == owning.id) announceOwner(mob, owning, "set")
  }
}

// ---- Effects ---------------------------------------------------------------------------------

/** Applies [effect] from [caster] to [target] (null for `target = "none"` skills). */
fun World.applyEffect(
  effect: Effect,
  caster: Actor,
  target: Actor?,
  context: EffectContext = EffectContext(),
) {
  when (effect) {
    is Effect.Sequence -> effect.effects.forEach { applyEffect(it, caster, target, context) }
    is Effect.Plugin -> pluginEffects.apply(this, effect, caster, target, null, null, context.power)
    is Effect.Summon -> summon(caster, effect, context.power)
    else -> if (target != null) applyToTarget(effect, caster, target, context)
  }
}

private fun World.applyToTarget(
  effect: Effect,
  caster: Actor,
  target: Actor,
  context: EffectContext,
) {
  if (!target.inWorld) return
  // Harmful primitives engage, so a stun or shove can't be used on a mob without aggro.
  if (effect.isHarmful() && target !== caster) startCombat(caster, target)
  val power = context.power
  when (effect) {
    is Effect.Damage -> strike(effect, caster, target, context)
    is Effect.Heal ->
      heal(target, effect.vital, scaled(effect.dice.roll(rng), power), source = caster)
    is Effect.ApplyAffect ->
      // A skill's affect is a bane exactly when the skill targets an enemy.
      applyAffect(
        target,
        effect.toSpec(caster, power),
        hostile = context.skill?.let { it.target == TargetKind.ENEMY },
      )
    is Effect.Dispel -> dispel(target, effect)
    is Effect.Knockback ->
      if (Feature.TRACK in features) shove(target, awayFrom = caster, steps = effect.steps)
    is Effect.Pull ->
      if (Feature.TRACK in features) pull(target, toward = caster, steps = effect.steps)
    // A stun always is a bane.
    is Effect.Stun ->
      applyAffect(
        target,
        AffectSpec(
          "stun",
          scaled(effect.pulses, power).toLong(),
          flags = setOf("stunned"),
          source = caster,
          power = power,
        ),
        hostile = true,
      )
    is Effect.Sequence,
    is Effect.Summon,
    is Effect.Plugin -> Unit
  }
}

private fun Effect.isHarmful(): Boolean =
  when (this) {
    is Effect.Damage,
    is Effect.Stun,
    is Effect.Knockback,
    is Effect.Pull -> true
    else -> false
  }

private fun World.strike(
  effect: Effect.Damage,
  caster: Actor,
  target: Actor,
  context: EffectContext,
) {
  val c = RulesContext(this, caster, target, skill = context.skill, damageType = effect.type)
  val hit = resolveDamage(c, scaled(effect.dice.roll(rng), context.power), effect.type)
  traceRules(caster, hit.text)
  damage(target, hit.losses, effect.type, caster, hit.parts)
}

/** Applies an item skill's [effect] from [caster] to [item]. */
fun World.applyItemEffect(effect: Effect, caster: Actor, item: Item, power: Int = 100) {
  when (effect) {
    is Effect.Sequence -> effect.effects.forEach { applyItemEffect(it, caster, item, power) }
    is Effect.ApplyAffect ->
      applyItemAffect(item, effect.toSpec(caster, power), effect.applies ?: AffectScope.WORN)
    is Effect.Plugin -> pluginEffects.apply(this, effect, caster, null, item, null, power)
    else -> error("${effect::class.simpleName} can't act on an item")
  }
}

// ---- Track movement --------------------------------------------------------------------------

/** Pushes [t] along the track away from [awayFrom]; track movement applies immediately. */
internal fun World.shove(t: Actor, awayFrom: Actor, steps: Int) {
  val dir =
    when {
      t.trackIndex > awayFrom.trackIndex -> 1
      t.trackIndex < awayFrom.trackIndex -> -1
      t.room.trackSize - 1 - t.trackIndex >= t.trackIndex -> 1
      else -> -1
    }
  placeActor(t, t.room, t.trackIndex + dir * steps)
}

internal fun World.pull(t: Actor, toward: Actor, steps: Int) {
  val gap = toward.trackIndex - t.trackIndex
  val moved = gap.coerceIn(-steps, steps)
  placeActor(t, t.room, t.trackIndex + moved)
}
