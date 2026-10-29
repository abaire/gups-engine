package gups.core.world

import gups.core.EntityId
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Mob
import gups.core.model.MobProto
import gups.core.model.Player
import gups.core.model.Room

// Rebuilding saved state into a world made with a restoring Boot. These
// fire no hooks: restored entities already exist; `on_load` fires once restoring is done.

private fun World.claim(id: EntityId) {
  require(id !in entities) { "Entity $id already exists" }
  if (id.v >= nextEntityId) nextEntityId = id.v + 1
}

fun World.restorePlayer(
  id: EntityId,
  name: String,
  room: Room,
  index: Int,
  recall: Label,
  accountId: Long? = null,
): Player {
  claim(id)
  require(players.none { it.name.equals(name, ignoreCase = true) }) {
    "A player named $name is already in the world"
  }
  val p = Player(id, name, room, index.coerceIn(0, room.trackSize - 1), recall, accountId)
  initVitals(p)
  p.lastInputPulse = pulse
  register(p)
  actorIndex[p.id] = p
  p.inWorld = true
  placeActor(p, room, p.trackIndex)
  return p
}

/** A mob with its prototype's stats; the caller overlays the saved delta. */
fun World.restoreMob(id: EntityId, proto: MobProto, room: Room, index: Int): Mob {
  claim(id)
  val m = Mob(id, proto, room, index.coerceIn(0, room.trackSize - 1))
  declareMob(m, proto)
  register(m)
  actorIndex[m.id] = m
  m.inWorld = true
  placeActor(m, room, m.trackIndex)
  return m
}

/**
 * Overwrites [actor]'s numbers with saved ones. A vital or stat the schema no longer declares is
 * not accepted: the startup check keeps a database from meeting a different schema. [actor]'s
 * traits must already be set, so a current value above the base maximum survives when a trait
 * raises it.
 */
fun World.restoreActorNumbers(
  actor: Actor,
  stats: Map<String, Int>,
  vitals: Map<String, VitalState>,
  resists: Map<DamageType, Int>,
  condition: Condition,
) {
  actor.baseStats.clear()
  actor.baseStats.putAll(stats)
  for ((name, saved) in vitals) {
    val v = requireNotNull(actor.vitalMap[name]) { "${actor.name} has no vital $name" }
    v.max = saved.max
    v.current = saved.current.coerceAtMost(actor.max(name))
    v.belowZeroSince = saved.belowZeroSince
  }
  actor.resists.clear()
  actor.resists.putAll(resists)
  actor.condition = condition
}

/** A saved vital: its current value, maximum and `belowZeroSince`. */
data class VitalState(val current: Int, val max: Int, val belowZeroSince: Long? = null)

/** An item at [at], whose holder must already exist (restore holders and containers first). */
fun World.restoreItem(id: EntityId, proto: ItemProto, at: ItemLocation, count: Int): Item {
  claim(id)
  val item = Item(id, proto, at, count)
  when (at) {
    is ItemLocation.InRoom -> {
      val room = requireNotNull(entity(at.room) as? Room) { "No room ${at.room}" }
      require(at.trackIndex in 0 until room.trackSize) {
        "Track index ${at.trackIndex} outside ${room.label}"
      }
      room.itemIndex[item.id] = item
    }
    is ItemLocation.Carried ->
      requireNotNull(actor(at.actor)) { "No actor ${at.actor}" }.inventoryIndex[item.id] = item
    is ItemLocation.Equipped -> {
      val a = requireNotNull(actor(at.actor)) { "No actor ${at.actor}" }
      require(a.equipmentIndex[at.slot] == null) {
        "${a.name} already wears something in ${at.slot}"
      }
      a.equipmentIndex[at.slot] = item
      // Untimed equip affects aren't saved; they are rebuilt from what is worn.
      applyEquipAffects(a, item)
    }
    is ItemLocation.Contained -> {
      val c = requireNotNull(item(at.container)) { "No container ${at.container}" }
      requireNotNull(c.container) { "$c is not a container" }
      c.contentsIndex[item.id] = item
    }
  }
  register(item)
  return item
}

/** A saved spawn rule binding: [mob] counts toward rule [ruleIndex] of [room]. */
fun World.bindSpawn(mob: Mob, room: Room, ruleIndex: Int): Boolean {
  val s = spawnStates.filter { it.room === room }.getOrNull(ruleIndex) ?: return false
  if (s.live.size >= s.rule.count) return false
  s.live += mob.id
  mob.spawnedBy = s
  return true
}

/** Pending respawns as `(room, rule index) -> pulse`, for zone state. */
fun World.respawnDeadlines(): Map<Pair<Label, Int>, Long> {
  val out = LinkedHashMap<Pair<Label, Int>, Long>()
  for (room in roomsByLabel.values) {
    spawnStates
      .filter { it.room === room }
      .forEachIndexed { i, s -> s.respawnAt?.let { out[room.label to i] = it } }
  }
  return out
}

fun World.setRespawn(room: Room, ruleIndex: Int, at: Long) {
  spawnStates.filter { it.room === room }.getOrNull(ruleIndex)?.respawnAt = at
}

/** Fills spawn rules that are short and have no respawn pending, after a restore. */
fun World.fillMissingSpawns() {
  for (s in spawnStates) if (s.live.size < s.rule.count && s.respawnAt == null) fillSpawn(s)
}

/** Zone resets as `package -> pulse`. */
fun World.zoneResetDeadlines(): Map<String, Long> = LinkedHashMap(zoneResets)

fun World.setZoneReset(pkg: String, at: Long) {
  if (pkg in zoneResets) zoneResets[pkg] = at
}

/** The spawn rule a mob counts toward, as `(room, rule index)`. */
fun World.spawnOf(mob: Mob): Pair<Label, Int>? {
  val s = mob.spawnedBy ?: return null
  return s.room.label to spawnStates.filter { it.room === s.room }.indexOf(s)
}
