package gups.core.world

import gups.core.model.Actor
import gups.core.model.Condition
import gups.core.model.Vital
import gups.core.rules.RulesContext

// Vitals and conditions. Every write to a vital's current value goes through [changeVital] or
// [changeVitals], which clamp at the maximum, track `belowZeroSince` and ask the rules for the
// actor's condition.

/** Vitals never go this low in a sane world; crossing it is diagnosed. */
private const val VITAL_SANITY_FLOOR = -65535

/** Adds [delta] to [vital], up to its maximum and with no floor, then settles the condition. */
fun World.changeVital(actor: Actor, vital: String, delta: Int, source: Actor? = null) {
  changeVitals(actor, mapOf(vital to delta), source)
}

/** Applies several vital changes as one event, asking for the condition once afterwards. */
fun World.changeVitals(actor: Actor, deltas: Map<String, Int>, source: Actor? = null) {
  applyVitalDeltas(actor, deltas, source)
  refreshCondition(actor)
}

/** The changes of [changeVitals], leaving the condition to the caller. */
internal fun World.applyVitalDeltas(actor: Actor, deltas: Map<String, Int>, source: Actor?) {
  for ((name, delta) in deltas) {
    val v =
      actor.vitalMap[name] ?: throw IllegalArgumentException("${actor.name} has no vital $name")
    if (delta == 0) continue
    val effMax = actor.max(name)
    val next =
      (v.current.toLong() + delta).coerceIn(Int.MIN_VALUE.toLong(), effMax.toLong()).toInt()
    if (next == v.current) continue
    if (next < VITAL_SANITY_FLOOR && v.current >= VITAL_SANITY_FLOOR)
      diagnose(
        "engine",
        "${actor.name}'s $name fell to $next, below $VITAL_SANITY_FLOOR; a rules or script " +
          "result is likely wrong",
      )
    setVital(v, next)
    actor.dirty = true
    if (delta < 0 && source != null && source !== actor) actor.lastAttacker = source.id
  }
}

private fun World.setVital(v: Vital, next: Int) {
  v.belowZeroSince =
    when {
      next >= 0 -> null
      v.belowZeroSince != null -> v.belowZeroSince
      else -> pulse
    }
  v.current = next
}

/** Sets every vital of [actor] to its maximum, as dormant recovery does. */
internal fun World.fillVitals(actor: Actor) {
  for ((name, v) in actor.vitalMap) {
    val effMax = actor.max(name)
    if (v.current < effMax) setVital(v, effMax)
  }
  actor.dirty = true
}

/**
 * Lowers every vital of [actor] that is above its effective maximum to that maximum, as after a
 * maximum falls. Returns whether any vital changed; the condition is left to the caller.
 */
internal fun World.capVitals(actor: Actor): Boolean {
  var changed = false
  for ((name, v) in actor.vitalMap) {
    val effMax = actor.max(name)
    if (v.current > effMax) {
      setVital(v, effMax)
      changed = true
    }
  }
  if (changed) actor.dirty = true
  return changed
}

/** Gives [actor] one [Vital] per declared vital, all at 0, for a new actor. */
internal fun World.initVitals(actor: Actor) {
  actor.vitalMap.clear()
  for (name in schema.vitalNames) actor.vitalMap[name] = Vital(0, 0)
}

/** What `rules.condition` says about [actor]; a rules error keeps the previous condition. */
internal fun World.askCondition(actor: Actor): Condition =
  rules.condition(RulesContext(this, actor))

/** Asks `rules.condition` and acts on a change. */
internal fun World.refreshCondition(actor: Actor) {
  if (!actor.inWorld) return
  setCondition(actor, askCondition(actor))
}

internal fun World.setCondition(actor: Actor, next: Condition) {
  val was = actor.condition
  if (next == was) {
    // A death not yet processed, as for an actor restored dead, is taken up again.
    if (next == Condition.DEAD) deferRemoval(actor, Deferred.Kind.DEATH) { processDeath(actor) }
    return
  }
  val wasDisabled = actor.isDisabled
  actor.condition = next
  actor.dirty = true
  when {
    next == Condition.INCAPACITATED -> {
      tell(actor, KernelMsg.CONDITION_DOWN)
      tellSight(
        actor.room,
        "incapacitated",
        actor,
        msg(KernelMsg.CONDITION_DOWN_ROOM, "actor" to actor.name.cap()),
        actor,
      )
      if (actor.casting?.action?.interrupt?.stun == true) interruptCast(actor)
    }
    next == Condition.OK && was == Condition.INCAPACITATED -> {
      tell(actor, KernelMsg.CONDITION_UP)
      tellSight(
        actor.room,
        "recovered",
        actor,
        msg(KernelMsg.CONDITION_UP_ROOM, "actor" to actor.name.cap()),
        actor,
      )
    }
  }
  disabledChanged(actor, wasDisabled)
  if (next == Condition.DEAD) deferRemoval(actor, Deferred.Kind.DEATH) { processDeath(actor) }
}

/**
 * Sets [actor]'s condition quietly, for an actor that was just given its vitals at a spawn or
 * respawn. A dead result is an error in [what]: it is diagnosed and the vitals are filled.
 */
internal fun World.settleNewCondition(actor: Actor, what: String) {
  if (askCondition(actor) == Condition.DEAD) {
    diagnose("rules", "$what left ${actor.name} dead; vitals set to their maxima")
    fillVitals(actor)
  }
  val wasDisabled = actor.isDisabled
  actor.condition = askCondition(actor)
  disabledChanged(actor, wasDisabled)
}

/** Sets every vital of [actor] to its maximum through [changeVitals], as full healing does. */
fun World.restoreVitals(actor: Actor) {
  changeVitals(actor, actor.vitalMap.mapValues { (name, v) -> actor.max(name) - v.current })
}
