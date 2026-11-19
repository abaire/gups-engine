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
import gups.core.model.Respawn
import gups.core.model.Room
import gups.core.model.Until

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
 * not accepted: the startup check keeps a database from meeting a different schema. Current values
 * are clamped once restore has placed the actor's items.
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
    v.current = saved.current
    v.belowZeroSince = saved.belowZeroSince
  }
  actor.resists.clear()
  actor.resists.putAll(resists)
  actor.condition = condition
}

/** A saved vital: its current value, maximum and `belowZeroSince`. */
data class VitalState(val current: Int, val max: Int, val belowZeroSince: Long? = null)

/**
 * Works out what restored actor [a] gets from its items, once restore has placed all of them: item
 * contributions, encumbrance, which worn items fit and which the `wear` restriction allows, the
 * untimed equip affects of those that count (timed ones were saved with the actor's affects), and
 * vitals clamped to the result. Nothing is announced, since the wearer was never told these items
 * worked.
 */
fun World.settleRestoredHoldings(a: Actor) {
  forgetActorSlots(a)
  askWearRestriction(a)
  refreshHoldings(a, announceFit = false)
  // Held off so each affect doesn't rerun the fit check against a partial set of equip affects.
  a.refittingSlots = true
  try {
    for ((slot, item) in a.equipmentIndex.toList()) {
      if (a.isActiveSlot(slot)) applyEquipAffects(a, item, untimedOnly = true, fireEvents = false)
    }
  } finally {
    a.refittingSlots = false
  }
  refreshFit(a, announce = false)
}

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
      // Untimed equip affects are rebuilt by settleRestoredHoldings once every item is placed.
      a.equipmentIndex[at.slot] = item
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

/** The room label the spawn rule placed [item] in, or null. */
fun World.spawnHomeOf(item: Item): Label? = item.spawnHome

/**
 * A saved spawn rule binding: [mob] counts toward the mob rule with [key], with no max cap. A rule
 * that now places items doesn't take it.
 */
fun World.bindSpawn(mob: Mob, key: SpawnKey): Boolean {
  val s = spawnState(key) ?: return false
  if (s.isItemRule) return false
  s.live += mob.id
  mob.spawnedBy = s
  return true
}

/**
 * A saved spawn rule binding for [item]. On world start ([fromAway] = false), binds into `live`
 * with no cap. On login ([fromAway] = true), binds only if [item]'s id is in the rule's `away` set,
 * moving it to `live`.
 */
fun World.bindSpawn(item: Item, key: SpawnKey, home: Label?, fromAway: Boolean): Boolean {
  val s = spawnState(key) ?: return false
  if (!s.isItemRule) return false
  if (fromAway && !s.away.remove(item.id)) return false
  s.live += item.id
  item.spawnedBy = s
  item.spawnHome = home ?: s.room?.label
  return true
}

/** Counts a saved player-held item toward its `gone` rule while the player is logged out. */
fun World.bindAwaySpawn(itemId: EntityId, key: SpawnKey) {
  val s = spawnState(key) ?: return
  if (!s.isItemRule || s.until != Until.GONE) return
  s.away += itemId
}

/**
 * Stops counting [ids] as away once their player has logged in; any still away weren't rebound, so
 * their rule must not keep counting them.
 */
fun World.dropAwaySpawns(ids: Collection<EntityId>) {
  val gone = ids.toSet()
  for (s in spawnStates) if (s.away.removeAll(gone)) armTimer(s)
}

/** Saved state for every spawn rule declared in [pkg] that has run. */
fun World.savedSpawners(pkg: String): List<SavedSpawner> =
  spawnStates
    .filter { it.pkg == pkg && it.ran }
    .map { SavedSpawner(it.key, it.respawnAt, it.ran, it.held.toList()) }

/**
 * Restores saved state for one spawn rule; ignores unknown keys. Held copies for rooms that no
 * longer exist are dropped, since nothing could ever place them.
 */
fun World.restoreSpawner(saved: SavedSpawner) {
  val s = spawnState(saved.key) ?: return
  s.respawnAt = saved.at.takeIf { s.rule.respawn is Respawn.Timed }
  s.ran = saved.ran
  s.held.clear()
  saved.held.filterTo(s.held) { it.room in roomsByLabel }
  for (h in saved.held) {
    if (h.seq >= nextHeldSeq) nextHeldSeq = h.seq + 1
  }
}

/**
 * Settles spawn rules after restore: rules that have never run do their first fill, and timed rules
 * that are short with no pending deadline arm one.
 */
fun World.settleRestoredSpawns() {
  for (s in spawnStates) {
    if (!s.ran) {
      firstFill(s)
    } else {
      armTimer(s)
    }
  }
}

/** Zone resets as `package -> pulse`. */
fun World.zoneResetDeadlines(): Map<String, Long> = LinkedHashMap(zoneResets)

fun World.setZoneReset(pkg: String, at: Long) {
  if (pkg in zoneResets) zoneResets[pkg] = at
}
