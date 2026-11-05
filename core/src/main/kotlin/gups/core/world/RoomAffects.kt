package gups.core.world

import gups.core.HookRef
import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.Effect
import gups.core.model.Modifiers
import gups.core.model.Room
import gups.core.saturatingAdd

// Room affects: timed enchantments on a room, applied and ended from this file, ticked and
// expired by the world's timer phase. A room's effective tags are the prototype's plus the live
// affects' added tags minus their removed tags.

/**
 * Puts timed affect [name] on [room] for [duration] pulses; a same-name affect is replaced only by
 * one that expires later, and the surviving affect is returned.
 */
fun World.applyRoomAffect(
  room: Room,
  name: String,
  duration: Long,
  flags: Set<String> = emptySet(),
  tickEvery: Int? = null,
  onTick: HookRef? = null,
  onExpire: HookRef? = null,
  expireMessage: String = "",
  source: Actor? = null,
  modifiers: Modifiers = emptyMap(),
  addTags: Set<String> = emptySet(),
  removeTags: Set<String> = emptySet(),
  power: Int = 100,
): Affect? {
  require(duration >= 1) { "apply_affect: duration must be at least 1" }
  val expiresAt = saturatingAdd(pulse, duration)
  room.affects.firstOrNull { it.name == name }?.let { if (it.expiresAt >= expiresAt) return it }
  room.affects.removeIf { it.name == name }
  val affect =
    Affect(
      allocateHandle(),
      name,
      source?.id,
      pulse,
      expiresAt,
      emptyMap(),
      flags,
      tickEvery,
      onTick,
      onExpire,
      expireMessage,
      modifiers,
      null,
      senseDeltas = emptyMap(),
      applies = null,
      maxVitalDeltas = emptyMap(),
      resistDeltas = emptyMap(),
      procs = emptyList(),
      power = power,
      addTags = addTags,
      removeTags = removeTags,
    )
  room.affects += affect
  room.dirty = true
  return affect
}

/** Removes room affect [name]; true if there was one. */
fun World.removeRoomAffect(room: Room, name: String): Boolean {
  val gone = room.affects.filter { it.name == name }
  for (affect in gone) endRoomAffect(room, affect, AffectEndReason.REMOVED)
  return gone.isNotEmpty()
}

/** Applies a room skill's [effect] from [caster] to [room] at [power] percent. */
fun World.applyRoomEffect(effect: Effect, caster: Actor, room: Room, power: Int = 100) {
  when (effect) {
    is Effect.Sequence -> effect.effects.forEach { applyRoomEffect(it, caster, room, power) }
    is Effect.ApplyAffect ->
      applyRoomAffect(
        room,
        effect.name,
        scaledDuration(
          requireNotNull(effect.duration) { "apply_affect: a room's affect needs a duration" },
          power,
        ),
        effect.flags,
        effect.tickEvery,
        effect.onTick,
        effect.onExpire,
        effect.expireMessage,
        caster,
        effect.modifiers,
        effect.addTags,
        effect.removeTags,
        power = power,
      )
    is Effect.Dispel -> {
      val gone = room.affects.filter(effect::matches)
      for (affect in gone.take(effect.count ?: gone.size)) endRoomAffect(
        room,
        affect,
        AffectEndReason.REMOVED,
        announce = true,
      )
    }
    is Effect.Plugin -> pluginEffects.apply(this, effect, caster, null, null, room, power)
    // Load-time checks reject the actor-only primitives on a room skill; this guards direct Kotlin
    // use.
    else -> Unit
  }
}

internal fun World.expireRoomAffect(room: Room, affect: Affect) =
  endRoomAffect(room, affect, AffectEndReason.EXPIRED)

/**
 * Ends [affect]: tells the room on expiry or when [announce], then runs `on_expire`, whose
 * `ctx.self` is the room. An affect that has reached its expiry pulse counts as expired however it
 * was taken off.
 */
internal fun World.endRoomAffect(
  room: Room,
  affect: Affect,
  reason: AffectEndReason,
  announce: Boolean = false,
) {
  if (!room.affects.remove(affect)) return
  room.dirty = true
  val why = if (pulse >= affect.expiresAt) AffectEndReason.EXPIRED else reason
  if ((announce || why == AffectEndReason.EXPIRED) && affect.expireMessage.isNotEmpty())
    for (a in room.actors) send(a, affect.expireMessage)
  affect.onExpire?.let {
    invokeRef(room, it, HookContext(room = room, affect = affect, affectEndReason = why))
  }
}
