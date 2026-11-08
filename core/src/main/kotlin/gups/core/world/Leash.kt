package gups.core.world

import gups.core.model.LeashOverride
import gups.core.model.Mob
import gups.core.model.Room
import gups.core.rules.RulesContext

/** The mob's effective leash: its override when it has one, else its prototype's. */
val Mob.leash: Int?
  get() {
    val override = leashOverride ?: return proto.leash
    return override.exits
  }

/** Overrides [mob]'s leash; [exits] null unleashes it. */
fun World.setLeash(mob: Mob, exits: Int?) {
  if (exits != null && exits < 0) throw IllegalArgumentException("leash must be at least 0")
  mob.leashOverride = LeashOverride(exits)
  mob.dirty = true
}

/** Drops [mob]'s leash override so its prototype's leash applies again. */
fun World.resetLeash(mob: Mob) {
  if (mob.leashOverride == null) return
  mob.leashOverride = null
  mob.dirty = true
}

/** Rooms within [radius] exits of [home] through open doors, cached until the graph changes. */
internal fun World.homeArea(home: Room, radius: Int): Set<Room> =
  homeAreaCache.getOrPut(home.id to radius) { within(home, radius) }

/** The rooms [mob]'s leash allows, or null when it is unleashed, owned, or its home is gone. */
internal fun World.leashArea(mob: Mob): Set<Room>? {
  val leash = mob.leash ?: return null
  if (mob.owner != null) return null
  val home = roomOrNull(mob.homeRoom) ?: return null
  return homeArea(home, leash)
}

/**
 * Where a leashed mob belongs: its first `wander` behavior's area, or its home room. Null when
 * [leashArea] is.
 */
internal fun World.homeGround(mob: Mob): Set<Room>? {
  val leash = mob.leash ?: return null
  if (mob.owner != null) return null
  val home = roomOrNull(mob.homeRoom) ?: return null
  val w = mob.proto.behaviors.firstOrNull { it is Wander } as? Wander ?: return setOf(home)
  return homeArea(home, minOf(w.radius, leash))
}

/** Whether [mob] stands past its leash, in a room its leash does not allow. */
internal fun World.isPastLeash(mob: Mob): Boolean = leashArea(mob)?.let { mob.room !in it } ?: false

/**
 * Whether a mob that may go home does so this think. Asks the world's `leash_return` rules export;
 * the default (export undefined, or a failing call) is to go home.
 */
internal fun World.shouldReturn(mob: Mob, away: Long, pastLeash: Boolean): Boolean =
  rules.leashReturn(RulesContext(this, mob, null, away = away, pastLeash = pastLeash)) ?: true

/** The engine's step toward home, or null; also keeps [Mob.awaySince] current. */
internal fun World.returnProposal(mob: Mob): Proposal? {
  val ground = homeGround(mob)
  val home = roomOrNull(mob.homeRoom)
  if (
    ground == null ||
      home == null ||
      mob.isEngaged ||
      mob.room in ground ||
      behaviorsOf(mob).any { it.holdsReturn(this, mob) }
  ) {
    mob.awaySince = null
    return null
  }
  val away = mob.awaySince ?: pulse.also { mob.awaySince = it }
  if (!shouldReturn(mob, pulse - away, isPastLeash(mob))) return null
  val step = pathTo(mob.room, home)?.firstOrNull() ?: return null
  return Proposal(step.name, PRIORITY_RETURN, "leash", MoveAction(step))
}
