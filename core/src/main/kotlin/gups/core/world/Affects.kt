package gups.core.world

import gups.core.EntityId
import gups.core.HookRef
import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.AffectScope
import gups.core.model.CastState
import gups.core.model.Channel
import gups.core.model.DamageType
import gups.core.model.Effect
import gups.core.model.Item
import gups.core.model.Mob
import gups.core.model.Modifiers
import gups.core.model.Proc
import gups.core.saturatingAdd

/**
 * What an affect is and who gave it: everything [applyAffect] and [applyItemAffect] need to make
 * one, apart from where it goes.
 */
data class AffectSpec(
  val name: String,
  /** Pulses, 64-bit; null only for a permanent item affect. */
  val duration: Long?,
  val statDeltas: Map<String, Int> = emptyMap(),
  val flags: Set<String> = emptySet(),
  val tickEvery: Int? = null,
  val onTick: HookRef? = null,
  val onExpire: HookRef? = null,
  val expireMessage: String = "",
  val source: Actor? = null,
  val modifiers: Modifiers = emptyMap(),
  val senseDeltas: Map<Channel, Int> = emptyMap(),
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  val resistDeltas: Map<DamageType, Int> = emptyMap(),
  val procs: List<Proc> = emptyList(),
  val power: Int = 100,
)

/** The spec this effect describes once [power] has scaled its duration, given by [source]. */
fun Effect.ApplyAffect.toSpec(source: Actor? = null, power: Int = 100) =
  AffectSpec(
    name,
    duration?.let { scaledDuration(it, power) },
    statDeltas,
    flags,
    tickEvery,
    onTick,
    onExpire,
    expireMessage,
    source,
    modifiers,
    senseDeltas,
    maxVitalDeltas,
    resistDeltas,
    procs,
    power,
  )

private fun World.newAffect(
  spec: AffectSpec,
  expiresAt: Long,
  wornFrom: EntityId? = null,
  applies: AffectScope? = null,
) =
  with(spec) {
    Affect(
      allocateHandle(),
      name,
      source?.id,
      pulse,
      expiresAt,
      statDeltas,
      flags,
      tickEvery,
      onTick,
      onExpire,
      expireMessage,
      modifiers,
      wornFrom,
      senseDeltas,
      applies,
      maxVitalDeltas,
      resistDeltas,
      procs,
      power,
    )
  }

/** The affect called [name] that already lasts at least until [expiresAt], if there is one. */
private fun List<Affect>.lastingAs(name: String, expiresAt: Long): Affect? =
  firstOrNull { it.name == name }?.takeIf { it.expiresAt >= expiresAt }

// ---- Actor affects ---------------------------------------------------------------------------

/**
 * The `apply_affect` verb. Re-applying a name replaces the old affect only if the new one would run
 * out later; otherwise the old one stays and is returned, so a short stun or debuff can't cut a
 * longer one short. [hostile] says whether the affect is a bane when the skill behind it doesn't
 * settle that; [wornFrom] ties the affect to an item, and it then lasts until the item comes off.
 */
fun World.applyAffect(
  target: Actor,
  spec: AffectSpec,
  hostile: Boolean? = null,
  wornFrom: EntityId? = null,
): Affect? {
  if (!target.inWorld) return null
  val duration = requireNotNull(spec.duration) { "apply_affect: duration is required for an actor" }
  require(duration >= 1) { "apply_affect: duration must be at least 1" }
  val expiresAt = if (wornFrom != null) Long.MAX_VALUE else saturatingAdd(pulse, duration)
  target.affects.lastingAs(spec.name, expiresAt)?.let {
    return it
  }
  val wasDisabled = target.isDisabled
  target.affects.removeIf { it.name == spec.name }
  val affect = newAffect(spec, expiresAt, wornFrom)
  target.affects += affect
  creditApplication(target, spec.source, affect, hostile)
  target.dirty = true
  if (stunsCaster(spec, target)) interruptCast(target)
  disabledChanged(target, wasDisabled)
  forgetActorSlots(target)
  refreshFit(target)
  clampVitals(target)
  return affect
}

private fun stunsCaster(spec: AffectSpec, target: Actor): Boolean =
  "stunned" in spec.flags && target.casting?.action?.interrupt?.stun == true

/** Records and announces what [source] did for or against [target] by giving it [affect]. */
private fun World.creditApplication(
  target: Actor,
  source: Actor?,
  affect: Affect,
  hostile: Boolean?,
) {
  if (source == null || source === target) return
  recordSupport(target, source, hostile)
  if (isBane(target, source, hostile)) return
  witness(source.room, source, "help", target = target)
  if (target.isEngaged) {
    raiseStandingEvent(StandingEvent("help", affected = target, actor = source, affect = affect))
  }
  raiseAssists(target, source) { opponent ->
    StandingEvent("assist", affected = opponent, actor = source, affect = affect, helped = target)
  }
}

fun World.removeAffect(target: Actor, name: String): Boolean =
  removeAffects(target) { it.name == name }

/** Ends every affect on [target] that [matches], as removed early; true if any matched. */
private fun World.removeAffects(target: Actor, matches: (Affect) -> Boolean): Boolean {
  val gone = target.affects.filter(matches)
  for (affect in gone) endAffect(target, affect, AffectEndReason.REMOVED)
  return gone.isNotEmpty()
}

/** The `dispel` effect: ends [effect]'s matches on [target], telling it each one ended. */
internal fun World.dispel(target: Actor, effect: Effect.Dispel) {
  val gone = target.affects.filter(effect::matches)
  for (affect in gone.take(effect.count ?: gone.size)) {
    endAffect(target, affect, AffectEndReason.REMOVED, announce = true)
  }
}

internal fun World.expireAffect(a: Actor, affect: Affect) =
  endAffect(a, affect, AffectEndReason.EXPIRED)

/**
 * Takes [affect] off [a] and runs its end: the expire message (on expiry, or always when
 * [announce]), `on_expire` and, for a summon, the banishing. An affect that has reached its expiry
 * pulse counts as expired however it was taken off.
 */
internal fun World.endAffect(
  a: Actor,
  affect: Affect,
  reason: AffectEndReason,
  announce: Boolean = false,
) {
  val wasDisabled = a.isDisabled
  if (!a.affects.remove(affect)) return
  a.dirty = true
  disabledChanged(a, wasDisabled)
  forgetActorSlots(a)
  refreshFit(a)
  clampVitals(a)
  val why = if (pulse >= affect.expiresAt) AffectEndReason.EXPIRED else reason
  val tells = announce || why == AffectEndReason.EXPIRED
  if (tells && affect.expireMessage.isNotEmpty()) send(a, affect.expireMessage)
  affect.onExpire?.let { invokeRef(a, it, HookContext(affect = affect, affectEndReason = why)) }
  if ("summoned" in affect.flags) dismissSummon(a)
}

private fun World.dismissSummon(a: Actor) {
  if (a !is Mob || !a.inWorld) return
  tellSight(a.room, "exit", a, msg(KernelMsg.AFFECT_FADES, "actor" to a.name.cap()), a)
  disengageAll(a, null)
  removeActor(a)
}

// ---- Item affects ----------------------------------------------------------------------------

/**
 * Puts instance affect [spec] on [item], reaching its holder from [applies]; a null duration makes
 * it permanent. A same-name affect is replaced only by one that expires later.
 */
fun World.applyItemAffect(
  item: Item,
  spec: AffectSpec,
  applies: AffectScope = AffectScope.WORN,
): Affect? {
  if (item.id !in entities) return null
  val duration = spec.duration
  require(duration == null || duration >= 1) { "apply_affect: duration must be at least 1" }
  require("stunned" !in spec.flags) { "apply_affect: item affects can't stun" }
  val expiresAt = duration?.let { saturatingAdd(pulse, it) } ?: Long.MAX_VALUE
  item.affects.lastingAs(spec.name, expiresAt)?.let {
    return it
  }
  item.affects.removeIf { it.name == spec.name }
  val affect = newAffect(spec, expiresAt, applies = applies)
  item.affects += affect
  item.dirty = true
  custodian(item)?.let { refreshHoldings(it) }
  return affect
}

/** Removes instance affect [name] from [item]; true if there was one. */
fun World.removeItemAffect(item: Item, name: String): Boolean {
  val gone = item.affects.filter { it.name == name }
  for (affect in gone) endItemAffect(item, affect, AffectEndReason.REMOVED)
  return gone.isNotEmpty()
}

internal fun World.expireItemAffect(item: Item, affect: Affect) =
  endItemAffect(item, affect, AffectEndReason.EXPIRED)

/**
 * Takes [affect] off [item] and runs its end: the expire message to the custodian on expiry, and
 * `on_expire`, whose `ctx.self` is the item. An affect that has reached its expiry pulse counts as
 * expired however it was taken off. With no custodian the message is dropped. An item that has left
 * the world took its affects with it, so nothing runs for them.
 */
private fun World.endItemAffect(item: Item, affect: Affect, reason: AffectEndReason) {
  if (item.id !in entities || !item.affects.remove(affect)) return
  item.dirty = true
  custodian(item)?.let { refreshHoldings(it) }
  val why = if (pulse >= affect.expiresAt) AffectEndReason.EXPIRED else reason
  if (why == AffectEndReason.EXPIRED && affect.expireMessage.isNotEmpty())
    custodian(item)?.let { send(it, affect.expireMessage) }
  affect.onExpire?.let { invokeRef(item, it, HookContext(affect = affect, affectEndReason = why)) }
}

// ---- Disabling and equipment -----------------------------------------------------------------

/**
 * Clears [a]'s recovery when a stun or incapacitation starts and puts it back when the last one
 * ends, so recovery neither counts down nor runs out meanwhile. Any lag from a cast that broke is
 * part of what is put back. A cast that didn't break has its warmup pushed back by the length of
 * the interruption, so it stays paused.
 */
internal fun World.disabledChanged(a: Actor, wasDisabled: Boolean) {
  val disabled = a.isDisabled
  if (disabled && !wasDisabled) {
    a.heldWaitState = a.waitState
    a.heldSince = pulse
    a.waitState = 0
  } else if (wasDisabled && !disabled) {
    a.waitState = maxOf(a.waitState, a.heldWaitState)
    a.heldWaitState = 0
    a.casting?.let { c ->
      a.casting = CastState(c.action, c.startedAt, c.endsAt + (pulse - a.heldSince), c.orderedBy)
    }
  }
}

/**
 * Applies [item]'s `equip_affects` to [wearer] as it is equipped, through [applyAffect] like any
 * other affect. Untimed entries last until [removeEquipAffects]; timed ones count down. If
 * [untimedOnly] is true, timed entries are skipped (used when an inactive item becomes active
 * again).
 */
internal fun World.applyEquipAffects(wearer: Actor, item: Item, untimedOnly: Boolean = false) {
  for (e in item.proto.equipAffects) {
    if (untimedOnly && e.pulses != null) continue
    val name = equipAffectName(item, e.flag)
    if (e.pulses != null) {
      applyAffect(wearer, AffectSpec(name, e.pulses.toLong(), flags = setOf(e.flag)))
    } else {
      applyAffect(wearer, AffectSpec(name, 1, flags = setOf(e.flag)), wornFrom = item.id)
    }
  }
}

/** Removes the untimed `equip_affects` of [item] from [wearer] as it comes off. */
internal fun World.removeEquipAffects(wearer: Actor, item: Item) {
  for (e in item.proto.equipAffects) {
    if (e.pulses == null) removeAffect(wearer, equipAffectName(item, e.flag))
  }
}

private fun equipAffectName(item: Item, flag: String) = "worn:${item.id.v}:$flag"
