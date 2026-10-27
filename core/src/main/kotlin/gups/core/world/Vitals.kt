package gups.core.world

import gups.core.model.Actor
import gups.core.model.Condition
import gups.core.model.Vital
import gups.core.rules.RulesContext

// Vitals and conditions. Every write to a vital's current value goes through [changeVital] or
// [changeVitals], which clamp at the maximum, track `belowZeroSince` and ask the rules for the
// actor's condition.

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
    val next = (v.current.toLong() + delta).coerceIn(Int.MIN_VALUE.toLong(), v.max.toLong()).toInt()
    if (next == v.current) continue
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
  for (v in actor.vitalMap.values) if (v.current < v.max) setVital(v, v.max)
  actor.dirty = true
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
  if (next == was) return
  val wasDisabled = actor.isDisabled
  actor.condition = next
  actor.dirty = true
  when {
    next == Condition.INCAPACITATED -> {
      send(actor, "You are incapacitated.")
      echo(actor.room, "${actor.name.cap()} collapses.", actor)
      if (actor.casting?.action?.interrupt?.stun == true) interruptCast(actor)
    }
    next == Condition.OK && was == Condition.INCAPACITATED -> {
      send(actor, "You come to.")
      echo(actor.room, "${actor.name.cap()} stirs.", actor)
    }
  }
  disabledChanged(actor, wasDisabled)
  if (next == Condition.DEAD) deferRemoval(actor, Deferred.Kind.DEATH) { processDeath(actor) }
}

/** Sets every vital of [actor] to its maximum through [changeVitals], as full healing does. */
fun World.restoreVitals(actor: Actor) {
  changeVitals(actor, actor.vitalMap.mapValues { (_, v) -> v.max - v.current })
}
