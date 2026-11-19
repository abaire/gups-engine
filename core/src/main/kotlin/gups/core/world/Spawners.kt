package gups.core.world

import gups.core.EntityId
import gups.core.Label
import gups.core.WORLD_TICK_PULSES
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Prototypes
import gups.core.model.Respawn
import gups.core.model.Roll
import gups.core.model.Room
import gups.core.model.SpawnRule
import gups.core.model.SpawnerProto
import gups.core.model.Until

/** A spawn rule's identity across reloads and restarts. */
sealed interface SpawnKey {
  data class Named(val id: Label) : SpawnKey

  data class InRoom(val room: Label, val label: Label, val n: Int) : SpawnKey
}

/** A copy picked into a dormant room or awaiting a container, not yet an entity. */
data class HeldCopy(val room: Label, val stack: Int?, val seq: Long)

/** The state of one spawn rule. */
class SpawnState
internal constructor(
  val key: SpawnKey,
  /**
   * The package that declared the rule: its resets drive `"reset"` mode and its `zone_states` row
   * saves it.
   */
  val pkg: String,
  val rule: SpawnRule,
  val pool: Map<Label, Int>,
  val pick: PoolPicker?,
  /** The declaring room of a room rule; null for a spawner. */
  val room: Room?,
  internal val isItemRule: Boolean,
) {
  internal val live = sortedSetOf<EntityId>()
  internal val away = sortedSetOf<EntityId>()
  internal val held = ArrayDeque<HeldCopy>()
  internal val reported = HashSet<Label>()
  internal var respawnAt: Long? = null
  internal var ran = false

  internal val until: Until
    get() = rule.until ?: Until.GONE

  internal val tracked: Int
    get() = live.size + away.size + held.size

  internal val short: Boolean
    get() = tracked < rule.max
}

/** Snapshot of a spawn rule's current state, for scripts and plugins. */
data class SpawnerInfo(
  val live: Int,
  val held: Int,
  val max: Int,
  val nextAt: Long?,
  val ran: Boolean,
)

/** The state of the spawn rule with [key], or null if no loaded rule has it. */
fun World.spawnState(key: SpawnKey): SpawnState? = spawnStatesByKey[key]

/** Replaces every spawn state, keeping the key index in step. */
internal fun World.replaceSpawnStates(states: List<SpawnState>) {
  spawnStates.clear()
  spawnStates += states
  spawnStatesByKey.clear()
  for (s in states) spawnStatesByKey[s.key] = s
}

/** Current state of the spawn rule with [key]; refreshes `until = "taken"` first. */
fun World.spawnerInfo(key: SpawnKey): SpawnerInfo =
  spawnerInfo(spawnState(key) ?: throw IllegalArgumentException("no spawn rule $key"))

/** Current state of [s]; refreshes `until = "taken"` first. */
internal fun World.spawnerInfo(s: SpawnState): SpawnerInfo {
  if (s.isItemRule && s.until == Until.TAKEN) checkTakenRule(s)
  val nextAt =
    when (s.rule.respawn) {
      is Respawn.Timed -> s.respawnAt
      Respawn.Reset -> if (s.short) zoneResets[s.pkg] else null
      Respawn.Never -> null
    }
  return SpawnerInfo(
    live = s.live.size + s.away.size,
    held = s.held.size,
    max = s.rule.max,
    nextAt = nextAt,
    ran = s.ran,
  )
}

/**
 * Adds one batch (or [count] copies) to the spawn rule with [key], capped at `max`, regardless of
 * its respawn mode. Leaves any pending deadline untouched.
 */
fun World.refillRule(key: SpawnKey, count: Int? = null) {
  val s = spawnState(key) ?: throw IllegalArgumentException("no spawn rule $key")
  if (count != null) {
    require(count >= 1) { "refill(count): must be at least 1" }
    addCopies(s, count)
  } else {
    addBatch(s)
  }
  armTimer(s)
}

/** Runs a zone reset for [pkg] immediately and rolls its next reset from this pulse. */
fun World.resetZoneNow(pkg: String) {
  protos.zones[pkg]?.resetTicks ?: throw IllegalArgumentException("$pkg has no zone reset")
  resetZone(pkg)
}

/** The identity of the spawn rule [e] counts toward, or null. */
fun World.spawnKeyOf(e: Entity): SpawnKey? =
  when (e) {
    is Mob -> e.spawnedBy?.key
    is Item -> e.spawnedBy?.key
    else -> null
  }

/** The prototype label the spawn rule with [key] places. */
fun World.spawnLabelOf(key: SpawnKey): Label =
  spawnState(key)?.rule?.label
    ?: when (key) {
      is SpawnKey.InRoom -> key.label
      is SpawnKey.Named -> error("unknown spawn rule $key")
    }

/** The pool room labels of the spawn rule with [key], in declared order. */
fun World.poolOf(key: SpawnKey): List<Label> =
  spawnState(key)?.pool?.keys?.toList()
    ?: when (key) {
      is SpawnKey.Named -> emptyList()
      is SpawnKey.InRoom -> listOf(key.room)
    }

/** The declaring room label of the spawn rule with [key], or null for a top-level spawner. */
fun World.declaringRoomOf(key: SpawnKey): Label? =
  spawnState(key)?.room?.label ?: (key as? SpawnKey.InRoom)?.room

/** What a checkpoint saves of a rule; only rules that have run are saved. */
data class SavedSpawner(
  val key: SpawnKey,
  val at: Long?,
  val ran: Boolean,
  val held: List<HeldCopy>,
)

/**
 * Validates [rule] against loaded prototypes and returns true if it names an item prototype, false
 * if it names a mob prototype.
 */
internal fun World.validateSpawnRule(rule: SpawnRule): Boolean {
  val mob = protos.mobs[rule.label]
  val item = protos.items[rule.label]
  require(mob != null || item != null) { "Unknown spawn prototype ${rule.label}" }
  if (mob != null) {
    require(rule.until == null) { "spawn(until): only item rules have until" }
    require(rule.stack == null) { "spawn(stack): only item rules have stack" }
    require(rule.container == null) { "spawn(container): only item rules have container" }
    return false
  }
  if (rule.stack != null) {
    require(item!!.stackable) { "spawn(stack): ${rule.label} doesn't stack" }
  }
  rule.container?.let { c ->
    val cp = protos.items[c]
    require(cp?.container != null) { "spawn(container): $c is not a container" }
  }
  return true
}

/**
 * Computes the identity key for [rule] declared in [room], advancing [seenUnnamed] for unnamed
 * rules.
 */
internal fun spawnKey(room: Label, rule: SpawnRule, seenUnnamed: MutableMap<Label, Int>): SpawnKey {
  rule.id?.let {
    return SpawnKey.Named(it)
  }
  val n = seenUnnamed.getOrDefault(rule.label, 0)
  seenUnnamed[rule.label] = n + 1
  return SpawnKey.InRoom(room, rule.label, n)
}

/** Rolls the next zone reset pulse from [ticks] world ticks after the current pulse. */
internal fun World.nextZoneReset(ticks: Roll): Long =
  pulse + ticks.roll(rng).toLong() * WORLD_TICK_PULSES

/** Fills [s] up to `max` immediately, marks it run, and arms its timer if still short. */
internal fun World.firstFill(s: SpawnState) {
  addCopies(s, s.rule.max, holdIfDormant = false)
  s.ran = true
  armTimer(s)
}

/** Adds one batch of copies to [s], capped at the missing count. */
internal fun World.addBatch(s: SpawnState) {
  val missing = s.rule.max - s.tracked
  if (missing <= 0) return
  val n = s.rule.batch?.roll(rng) ?: missing
  addCopies(s, n)
}

/** Builds the initial spawn states for every room rule and every top-level spawner. */
internal fun World.initSpawns() {
  replaceSpawnStates(buildSpawnStates(protos))
}

/** Builds fresh spawn states in canonical order: room rules first, then top-level spawners. */
internal fun World.buildSpawnStates(protos: Prototypes): List<SpawnState> {
  val out = ArrayList<SpawnState>()
  val seenKeys = HashSet<SpawnKey>()
  for (room in roomsByLabel.values) buildRoomSpawns(room, seenKeys, out)
  for (sp in protos.spawners) buildPoolSpawner(sp, seenKeys, out)
  return out
}

private fun World.buildRoomSpawns(
  room: Room,
  seenKeys: MutableSet<SpawnKey>,
  out: MutableList<SpawnState>,
) {
  val seenUnnamed = HashMap<Label, Int>()
  for (rule in room.proto.spawns) {
    val isItem = validateSpawnRule(rule)
    val key = spawnKey(room.label, rule, seenUnnamed)
    require(seenKeys.add(key)) { "duplicate spawn rule id ${(key as SpawnKey.Named).id}" }
    out += roomSpawnState(key, room, rule, isItem)
  }
}

private fun roomSpawnState(key: SpawnKey, room: Room, rule: SpawnRule, isItem: Boolean) =
  SpawnState(
    key = key,
    pkg = room.label.pkg,
    rule = rule,
    pool = mapOf(room.label to 1),
    pick = null,
    room = room,
    isItemRule = isItem,
  )

private fun World.buildPoolSpawner(
  sp: SpawnerProto,
  seenKeys: MutableSet<SpawnKey>,
  out: MutableList<SpawnState>,
) {
  val isItem = validateSpawnRule(sp.rule)
  val key = SpawnKey.Named(sp.id)
  require(seenKeys.add(key)) { "duplicate spawn rule id ${sp.id}" }
  out +=
    SpawnState(
      key = key,
      pkg = sp.id.pkg,
      rule = sp.rule,
      pool = sp.rooms,
      pick = sp.pick,
      room = null,
      isItemRule = isItem,
    )
}

/**
 * Picks a usable room from [s]'s pool for one copy, or returns null when no room is usable. A room
 * rule always uses its declaring room; for a pool, missing or quarantined rooms are skipped and
 * diagnosed once per rule.
 */
internal fun World.pickRoom(s: SpawnState): Room? {
  s.room?.let {
    return it
  }
  val usable = usablePoolRooms(s)
  if (usable.isEmpty()) return null
  val labels = usable.map { it.first }
  val chosen = s.pick?.pick(this, s.key, labels)
  if (chosen != null && chosen in labels) return room(chosen)
  return room(rollPoolRoom(usable))
}

private fun World.rollPoolRoom(usable: List<Pair<Label, Int>>): Label {
  if (usable.size == 1) return usable[0].first
  var r = rng.int(1, usable.sumOf { it.second })
  for ((label, weight) in usable) {
    r -= weight
    if (r <= 0) return label
  }
  return usable.last().first
}

private fun World.usablePoolRooms(s: SpawnState): List<Pair<Label, Int>> {
  val quarantined = scripts.quarantinedPackages
  val usable = ArrayList<Pair<Label, Int>>(s.pool.size)
  for ((label, weight) in s.pool) {
    if (roomOrNull(label) != null && label.pkg !in quarantined) {
      usable += label to weight
    } else if (s.reported.add(label)) {
      diagnose("engine", "spawner ${s.key}: room $label is missing or quarantined", null)
    }
  }
  return usable
}

/** Adds up to [n] copies to [s], never exceeding `max`. */
internal fun World.addCopies(s: SpawnState, n: Int, holdIfDormant: Boolean = true) {
  val count = minOf(n, s.rule.max - s.tracked)
  if (count <= 0) return
  repeat(count) {
    val picked = pickRoom(s) ?: return@repeat
    val stack = if (s.isItemRule) s.rule.stack?.roll(rng) else null
    if (holdIfDormant && picked.isDormant) {
      s.held.addLast(HeldCopy(picked.label, stack, nextHeldSeq++))
    } else if (!placeCopyOrHold(s, picked, stack)) {
      s.held.addLast(HeldCopy(picked.label, stack, nextHeldSeq++))
    }
  }
}

/** Places a new copy in [room] immediately, or returns false if its container isn't ready. */
private fun World.placeCopyOrHold(s: SpawnState, room: Room, stack: Int?): Boolean {
  if (!s.isItemRule) {
    placeMobCopy(s, room)
    return true
  }
  val count = stack ?: 1
  val loc = itemPlacementLocation(s, room, count) ?: return false
  placeItemCopy(s, room, loc, count)
  return true
}

/**
 * Resolves where an item copy should be placed in [room], or null if its target container is
 * missing/full.
 */
private fun World.itemPlacementLocation(s: SpawnState, room: Room, count: Int): ItemLocation? {
  val containerLabel = s.rule.container
  if (containerLabel != null) {
    val box =
      room.items.firstOrNull { it.protoLabel == containerLabel && it.container != null }
        ?: return null
    if (fits(s.rule.label, box, count) < count) return null
    return ItemLocation.Contained(box.id)
  }
  val idx = s.rule.atIndex.coerceIn(0, room.trackSize - 1)
  return ItemLocation.InRoom(room.id, idx)
}

private fun World.placeMobCopy(s: SpawnState, room: Room) {
  val idx = s.rule.atIndex.coerceIn(0, room.trackSize - 1)
  val mob =
    spawnMob(s.rule.label, room, idx) { m ->
      m.spawnedBy = s
      s.live += m.id
    }
  callOnPlace(s, mob, room)
}

private fun World.placeItemCopy(s: SpawnState, room: Room, loc: ItemLocation, count: Int) {
  val item =
    spawnItem(s.rule.label, loc, count) { item ->
      item.spawnedBy = s
      item.spawnHome = room.label
      s.live += item.id
    }
  callOnPlace(s, item, room)
}

private fun World.callOnPlace(s: SpawnState, copy: Entity, room: Room) {
  val hook = s.rule.onPlace ?: return
  guarded("on_place of ${s.rule.label}") { hook.placed(this, copy, room, s.key) }
}

/** Arms a timed rule's deadline when it is short and has no deadline pending. */
internal fun World.armTimer(s: SpawnState) {
  val timed = s.rule.respawn as? Respawn.Timed ?: return
  if (s.short && s.respawnAt == null) {
    s.respawnAt = pulse + timed.pulses.roll(rng)
  }
}

/** Releases [id] from [s] and arms its timer if applicable. */
internal fun World.releaseCopy(s: SpawnState, id: EntityId) {
  s.live.remove(id)
  armTimer(s)
}

/**
 * Departing an item tree from the world: moves `gone` items to `away` when [away] is true (player
 * logout or stowed pet), otherwise releases them and clears their bindings.
 */
internal fun World.departTree(item: Item, away: Boolean) {
  for (inner in item.contentsIndex.values.toList()) departTree(inner, away)
  val s = item.spawnedBy ?: return
  if (away && s.until == Until.GONE) {
    s.live.remove(item.id)
    s.away.add(item.id)
  } else {
    releaseCopy(s, item.id)
    item.unbindSpawn()
  }
}

/** Clears [this] item's spawn binding and home. */
internal fun Item.unbindSpawn() {
  spawnedBy = null
  spawnHome = null
}

/** Releases `until = "taken"` copies whose location chain no longer ends in their home room. */
internal fun World.checkTakenCopies() {
  for (s in spawnStates) {
    if (s.isItemRule && s.until == Until.TAKEN) checkTakenRule(s)
  }
}

private fun World.checkTakenRule(s: SpawnState) {
  for (id in s.live.toList()) {
    val item = item(id)
    if (item != null && isInHomeRoom(item)) continue
    releaseCopy(s, id)
    item?.unbindSpawn()
  }
}

private fun World.isInHomeRoom(item: Item): Boolean {
  val home = item.spawnHome ?: return false
  return roomOfEntity(item)?.label == home
}

/** Runs timed spawn batches whose deadline has arrived. */
internal fun World.timedBatchesDue() {
  for (s in spawnStates) {
    if (s.rule.respawn !is Respawn.Timed) continue
    val at = s.respawnAt ?: continue
    if (at > pulse) continue
    s.respawnAt = null
    guarded("respawn of ${s.rule.label}") { addBatch(s) }
    armTimer(s)
  }
}

/** Retries held copies whose target room exists and is awake, in hold order. */
internal fun World.retryHeldInAwakeRooms() {
  val due = spawnStates.flatMap { s -> s.held.map { s to it } }.sortedBy { it.second.seq }
  for ((s, copy) in due) {
    if (copy !in s.held) continue
    val room = roomOrNull(copy.room) ?: continue
    if (room.isDormant) continue
    tryPlaceHeldCopy(s, copy, room)
  }
}

/**
 * Runs a zone reset for [pkg]: schedules the next reset, marks dormant rooms, refills `"reset"`
 * room rules by ascending room id then spawners in declaration order, then fires `on_reset` on
 * awake rooms by ascending id.
 */
internal fun World.resetZone(pkg: String) {
  val ticks = protos.zones.getValue(pkg).resetTicks ?: return
  zoneResets[pkg] = nextZoneReset(ticks)
  val rooms = roomsByLabel.values.filter { it.label.pkg == pkg }
  for (room in rooms) if (room.isDormant) room.resetMissed = true
  val pkgResetRules = spawnStates.filter { it.pkg == pkg && it.rule.respawn == Respawn.Reset }
  val resetRules =
    pkgResetRules.filter { it.room != null }.sortedBy { it.room!!.id } +
      pkgResetRules.filter { it.room == null }
  for (s in resetRules) {
    guarded("reset of ${s.rule.label}") { addBatch(s) }
  }
  for (room in rooms.filter { !it.isDormant }.sortedBy { it.id }) {
    invokeOn(room, HookName.ON_RESET, HookContext(room = room))
  }
}

/** Places every held copy targeting [room] in hold order, then runs a missed `on_reset` once. */
internal fun World.placeHeldCopies(room: Room) {
  val due =
    spawnStates
      .flatMap { s -> s.held.filter { it.room == room.label }.map { s to it } }
      .sortedBy { it.second.seq }
  for ((s, copy) in due) {
    if (copy !in s.held) continue
    tryPlaceHeldCopy(s, copy, room)
  }
  if (room.resetMissed) {
    room.resetMissed = false
    invokeOn(room, HookName.ON_RESET, HookContext(room = room))
  }
}

/** Places a held copy in [room] if its destination is ready; container misses leave it held. */
private fun World.tryPlaceHeldCopy(s: SpawnState, copy: HeldCopy, room: Room) {
  if (!s.isItemRule) {
    s.held.remove(copy)
    guarded("respawn of ${s.rule.label}") { placeMobCopy(s, room) }
    armTimer(s)
    return
  }
  val count = copy.stack ?: 1
  val loc = itemPlacementLocation(s, room, count) ?: return
  s.held.remove(copy)
  guarded("respawn of ${s.rule.label}") { placeItemCopy(s, room, loc, count) }
  armTimer(s)
}
