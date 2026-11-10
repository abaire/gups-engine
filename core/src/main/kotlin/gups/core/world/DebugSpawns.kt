package gups.core.world

import gups.core.model.Item
import gups.core.model.Mob
import gups.core.model.Player

/** Marks [item] and everything inside it as debug, so none of it is saved. */
internal fun markDebug(item: Item) {
  item.debug = true
  for (inner in item.contentsIndex.values) markDebug(inner)
}

/** Debug mobs go when their spawner logs out. */
internal fun World.despawnDebugMobs(spawner: Player) {
  for (m in
    actorIndex.values.filterIsInstance<Mob>().filter {
      it.debug && it.debugOwner == spawner.id
    }) despawnDebug(m)
}

/** Debug mobs past their 30 minutes, or whose spawner is gone, despawn at the world tick. */
internal fun World.expireDebugMobs() {
  for (m in actorIndex.values.filterIsInstance<Mob>()) {
    if (!m.debug) continue
    val owner = m.debugOwner?.let(::actor)
    if ((m.debugUntil ?: Long.MAX_VALUE) <= pulse || owner == null) despawnDebug(m)
  }
}

private fun World.despawnDebug(m: Mob) {
  if (!m.inWorld) return
  disengageAll(m, null)
  val room = m.room
  for (item in m.inventory + m.equipment.values) markDebug(item)
  removeActor(m)
  echo(room, "${m.name.cap()} fades away.")
}
