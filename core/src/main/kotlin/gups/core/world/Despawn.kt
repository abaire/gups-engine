package gups.core.world

import gups.core.model.Actor
import gups.core.model.Mob

/** True while [a]'s `on_despawn` hooks run, before it is removed. */
fun World.isDespawning(a: Actor): Boolean = a.id in despawning

/** True while [a]'s death or despawn runs, so it is about to leave the world. */
internal fun World.isLeaving(a: Actor): Boolean = isDying(a) || isDespawning(a)

/**
 * Takes [mob] out of the world without killing it: no corpse, loot or XP. Its belongings go with
 * it, and a spawn rule tracking it respawns it on schedule. Calling it again while its `on_despawn`
 * hooks run does nothing.
 */
fun World.despawn(mob: Mob) {
  require(mob.inWorld) { "despawn: ${mob.name} is not in the world" }
  require(!isDying(mob)) { "despawn: ${mob.name} is dying" }
  if (!despawning.add(mob.id)) return
  try {
    val room = mob.room
    wipeActivity(mob)
    val freed = unlinkAll(mob) - mob
    val ctx = HookContext(actor = mob)
    // Pass room = null so observers and items in the room don't run on_despawn.
    dispatch(HookName.ON_DESPAWN, mob, null, ctx)
    dispatch(HookName.ON_DESPAWN, room, null, ctx)
    dispatchGlobal(HookName.ON_DESPAWN, ctx)
    if (mob.inWorld) removeActor(mob)
    // Even if a hook already took the mob out, the opponents it freed are owed their notice.
    fireDisengage(freed, "stop")
  } finally {
    despawning.remove(mob.id)
  }
}

/**
 * Shows [mob]'s room that it fades away, then despawns it. A mob whose death or despawn is already
 * running is left to that.
 */
internal fun World.fadeAway(mob: Mob) {
  if (!mob.inWorld || isLeaving(mob)) return
  tellSight(mob.room, "exit", mob, msg(KernelMsg.AFFECT_FADES, "actor" to mob.name.cap()), mob)
  despawn(mob)
}
