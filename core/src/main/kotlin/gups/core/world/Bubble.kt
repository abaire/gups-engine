package gups.core.world

import gups.core.BUBBLE_RADIUS
import gups.core.EntityId
import gups.core.WORLD_TICK_PULSES
import gups.core.model.Actor
import gups.core.model.DormantRecovery
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Room
import gups.core.rules.RulesContext

// The reality bubble. Rooms within BUBBLE_RADIUS exits of an anchor are simulated;
// every other room is dormant: no timers, AI or combat, and nothing is replayed when it wakes.
// Only worlds with WorldConfig.bubble keep a bubble; in the others every room is simulated.

/** The room [e] is in, following carriers and containers; null if it is nowhere. */
fun World.roomOfEntity(e: Entity): Room? =
  when (e) {
    is Room -> e
    is Actor -> e.room.takeIf { e.inWorld }
    is Item ->
      when (val loc = e.location) {
        is ItemLocation.InRoom -> entity(loc.room) as? Room
        is ItemLocation.Carried -> actor(loc.actor)?.room
        is ItemLocation.Equipped -> actor(loc.actor)?.room
        is ItemLocation.Contained -> item(loc.container)?.let { roomOfEntity(it) }
      }
  }

/**
 * True if [e] is simulated: it is in an active room, it is an anchor, or it has an `active` reason.
 * Players are always anchors.
 */
fun World.isSimulated(e: Entity): Boolean {
  if (!config.bubble || e is Player || e.activeReasons.isNotEmpty() || e.anchorReasons.isNotEmpty())
    return true
  val room = roomOfEntity(e) ?: return false
  return !room.isDormant
}

/**
 * The room [e] projects a bubble from, if it is an anchor: an in-world player or anything with an
 * anchor reason.
 */
private fun World.anchorRoom(e: Entity): Room? =
  when {
    e is Player -> e.room.takeIf { e.inWorld }
    e.anchorReasons.isNotEmpty() -> roomOfEntity(e)
    else -> null
  }

/**
 * Rooms within [BUBBLE_RADIUS] exits of [from], following exits whatever their doors and blocks.
 */
internal fun World.ball(from: Room): List<Room> {
  val seen = LinkedHashMap<Room, Int>()
  seen[from] = 0
  val queue = ArrayDeque(listOf(from))
  while (queue.isNotEmpty()) {
    val r = queue.removeFirst()
    val d = seen.getValue(r)
    if (d == BUBBLE_RADIUS) continue
    for (exit in r.exits.values) {
      val next = roomsByLabel[exit.to] ?: continue
      if (next in seen) continue
      seen[next] = d + 1
      queue.addLast(next)
    }
  }
  return seen.keys.toList()
}

/** The active rooms computed from scratch, for checking the incremental bookkeeping. */
fun World.activeRoomsFromScratch(): Set<Room> {
  val out = HashSet<Room>()
  for (e in entities.values) anchorRoom(e)?.let { out += ball(it) }
  for (r in roomsByLabel.values) if (r.hasFlag("always_active")) out += r
  return out
}

/** Rooms currently simulated. */
val World.activeRooms: Set<Room>
  get() = roomsByLabel.values.filterTo(HashSet()) { !it.isDormant }

private fun World.cover(room: Room, delta: Int, changed: MutableSet<Room>) {
  for (r in ball(room)) {
    val before = coverage[r] ?: 0
    val after = before + delta
    if (after == 0) coverage.remove(r) else coverage[r] = after
    if ((before == 0) != (after == 0)) changed += r
  }
}

/**
 * Phase 6: moves every anchor's coverage to where it is now, by a BFS from its old and new rooms,
 * then puts rooms that lost all coverage to sleep and wakes rooms that gained it. Dormant rooms go
 * to sleep first, then rooms wake, each ascending id.
 */
internal fun World.maintainBubble() {
  if (!config.bubble) return
  val now = HashMap<EntityId, Room>()
  for (e in entities.values) anchorRoom(e)?.let { now[e.id] = it }
  val changed = LinkedHashSet<Room>()
  for (id in anchorRooms.keys + now.keys) {
    val was = anchorRooms[id]
    val is_ = now[id]
    if (was === is_) continue
    if (was != null && was.label in roomsByLabel) cover(was, -1, changed)
    if (is_ != null) cover(is_, +1, changed)
  }
  anchorRooms.clear()
  anchorRooms.putAll(now)
  applyCoverage(changed)
}

/** Recomputes the whole bubble, for example after a reload changed the room graph. */
fun World.recomputeBubble() {
  if (!config.bubble) return
  coverage.clear()
  anchorRooms.clear()
  val scratch = HashSet<Room>()
  for (e in entities.values) {
    val r = anchorRoom(e) ?: continue
    anchorRooms[e.id] = r
    cover(r, +1, scratch)
  }
  applyCoverage(roomsByLabel.values)
}

private fun World.applyCoverage(rooms: Collection<Room>) {
  val sorted = rooms.filter { it.label in roomsByLabel }.sortedBy { it.id }
  for (r in sorted) if (!r.isDormant && !shouldBeActive(r)) sleep(r)
  for (r in sorted) if (r.isDormant && shouldBeActive(r)) wake(r)
}

private fun World.shouldBeActive(r: Room) = (coverage[r] ?: 0) > 0 || r.hasFlag("always_active")

/**
 * Every entity located in [room]: the room, its actors, and items on the ground or held there,
 * transitively.
 */
internal fun World.entitiesIn(room: Room): List<Entity> {
  val out = mutableListOf<Entity>(room)
  fun items(i: Item) {
    out += i
    for (inner in i.contentsIndex.values) items(inner)
  }
  for (i in room.itemIndex.values) items(i)
  for (a in room.actorIndex.values) {
    out += a
    for (i in a.inventoryIndex.values + a.equipmentIndex.values) items(i)
  }
  return out
}

/** Starts every room dormant except `always_active` ones, as a freshly booted world does. */
internal fun World.startDormant() {
  if (!config.bubble) return
  for (r in roomsByLabel.values) if (!r.hasFlag("always_active")) r.dormantSince = pulse
}

/**
 * Pauses the running timers of everything that isn't simulated, for example after saved state with
 * absolute deadlines was restored into dormant rooms. Then forms the bubble.
 */
fun World.settleBubble() {
  if (!config.bubble) return
  for (e in entities.values) if (!isSimulated(e)) pauseTimers(e)
  maintainBubble()
}

private fun World.pauseTimers(e: Entity) {
  for (t in e.timers) {
    val due = t.due ?: continue
    t.remaining = (due - pulse).coerceAtLeast(1).toInt()
    t.due = null
    e.dirty = true
  }
}

private fun World.resumeTimers(e: Entity) {
  for (t in e.timers) {
    val left = t.remaining ?: continue
    t.due = pulse + left
    t.remaining = null
    e.dirty = true
  }
}

/**
 * Keeps [e]'s timers paused or running to match whether it is simulated, after its reasons changed.
 */
internal fun World.syncTimers(e: Entity) {
  if (!config.bubble) return
  if (isSimulated(e)) resumeTimers(e) else pauseTimers(e)
}

/**
 * A room goes dormant: AI, `on_tick` and `on_combat_tick` stop because nothing in it is simulated;
 * timers pause; mobs disengage and drop their plans. Affects keep expiring by their absolute
 * deadlines. Entities with an `active` reason carry on.
 */
internal fun World.sleep(room: Room) {
  room.dormantSince = pulse
  room.dirty = true
  for (e in entitiesIn(room)) {
    if (e.activeReasons.isNotEmpty() || e.anchorReasons.isNotEmpty()) continue
    pauseTimers(e)
    if (e is Mob) {
      disengageAll(e)
      e.actionQueue.clear()
      e.casting = null
    }
  }
}

/**
 * A room wakes: lazy catch-up, never simulation. Timers resume; missed resets, respawns and decay
 * are applied; mobs recover by their `dormant_recovery` and their affects that ran out are removed
 * (`on_expire` in expiry order); then `on_wake` fires on the room, its mobs and its items.
 */
internal fun World.wake(room: Room) {
  val since = room.dormantSince ?: return
  room.dormantSince = null
  room.dirty = true
  val elapsed = pulse - since
  val sleepers =
    entitiesIn(room).filter { it.activeReasons.isEmpty() && it.anchorReasons.isEmpty() }
  for (e in sleepers) resumeTimers(e)
  val mobs = sleepers.filterIsInstance<Mob>()
  for (m in mobs) guarded("dormant recovery of $m") { recover(m, since) }
  for (m in mobs) {
    val expired =
      m.affects.filter { it.expiresAt <= pulse }.sortedWith(compareBy({ it.expiresAt }, { it.id }))
    for (a in expired) guarded("expiry of ${a.name} on $m") { expireAffect(m, a) }
  }
  if (room.resetMissed) {
    room.resetMissed = false
    for (s in spawnStates) if (s.room === room) guarded("reset of ${room.label}") { fillSpawn(s) }
    invokeOn(room, HookName.ON_RESET, HookContext(room = room))
  }
  for (s in spawnStates) {
    val at = s.respawnAt ?: continue
    if (s.room === room && at <= pulse) guarded("respawn in ${room.label}") { fillSpawn(s) }
  }
  for (i in sleepers.filterIsInstance<Item>()) {
    val at = i.decayAt ?: continue
    if (at <= pulse && i.id in entities) guarded("decay of $i") { decay(i) }
  }
  val ctx = { HookContext(room = room, elapsedPulses = elapsed) }
  invokeOn(room, HookName.ON_WAKE, ctx())
  for (m in mobs.sortedBy { it.id }) if (m.inWorld) invokeOn(m, HookName.ON_WAKE, ctx())
  for (i in sleepers.filterIsInstance<Item>().sortedBy { it.id }) if (i.id in entities)
    invokeOn(i, HookName.ON_WAKE, ctx())
}

/** Dormant vital recovery by `dormant_recovery`. */
private fun World.recover(m: Mob, since: Long) {
  val live = m.affects.filter { it.expiresAt > pulse }
  when (m.dormantRecovery) {
    DormantRecovery.NONE -> Unit
    DormantRecovery.FULL -> {
      val suppressed = live.any { "no_regen" in it.flags }
      if (suppressed) {
        catchUpRegen(m, since)
      } else {
        m.hp = maxOf(m.hp, m.maxHp)
        m.mana = maxOf(m.mana, m.maxMana)
        m.move = maxOf(m.move, m.maxMove)
      }
    }
    DormantRecovery.REGEN -> catchUpRegen(m, since)
  }
  m.dirty = true
}

/** World ticks in `[lo, hi)`. */
private fun ticksIn(lo: Long, hi: Long): Long =
  if (hi <= lo) 0
  else
    Math.floorDiv(hi - 1, WORLD_TICK_PULSES.toLong()) -
      Math.floorDiv(lo - 1, WORLD_TICK_PULSES.toLong())

/**
 * Regen for the world ticks missed since [since], computed piecewise: the time is split at every
 * affect expiry inside it, and each piece regenerates with the affects that were active then,
 * through `rules.regen` and the modifier chain.
 */
internal fun World.catchUpRegen(m: Mob, since: Long) {
  val all = m.affects.toList()
  val cuts = all.map { it.expiresAt }.filter { it in (since + 1)..pulse }.distinct().sorted()
  val bounds = listOf(since + 1) + cuts + listOf(pulse + 1)
  try {
    for (i in 0 until bounds.size - 1) {
      if (m.hp >= m.maxHp && m.mana >= m.maxMana && m.move >= m.maxMove) break
      val lo = bounds[i]
      val hi = bounds[i + 1]
      val n = ticksIn(lo, hi)
      if (n <= 0) continue
      m.affects.clear()
      m.affects += all.filter { it.expiresAt > lo }
      if (m.hasFlag("no_regen")) continue
      val r = regenOf(RulesContext(this, m))
      m.hp = (m.hp + r.hp * n).coerceAtMost(m.maxHp.toLong()).toInt().coerceAtLeast(m.hp)
      m.mana = (m.mana + r.mana * n).coerceAtMost(m.maxMana.toLong()).toInt().coerceAtLeast(m.mana)
      m.move = (m.move + r.move * n).coerceAtMost(m.maxMove.toLong()).toInt().coerceAtLeast(m.move)
    }
  } finally {
    m.affects.clear()
    m.affects += all
  }
}

// ---- Simulation verbs ------------------------------------------------------------------------
// Reasons are ref-counted by name: an entity stops being active or anchored only when its
// last reason is cleared. Anchor changes take effect at the next phase 6.

/** The `set_active` verb. Returns false if [e] already had [reason]. */
fun World.setActive(e: Entity, reason: String): Boolean =
  e.activeReasons.add(reason).also {
    if (it) {
      e.dirty = true
      syncTimers(e)
    }
  }

fun World.clearActive(e: Entity, reason: String): Boolean =
  e.activeReasons.remove(reason).also {
    if (it) {
      e.dirty = true
      syncTimers(e)
    }
  }

fun World.setAnchor(e: Entity, reason: String): Boolean =
  e.anchorReasons.add(reason).also {
    if (it) {
      e.dirty = true
      syncTimers(e)
    }
  }

fun World.clearAnchor(e: Entity, reason: String): Boolean =
  e.anchorReasons.remove(reason).also {
    if (it) {
      e.dirty = true
      syncTimers(e)
    }
  }
