package gups.persist

import gups.core.AccountStore
import gups.core.EntityId
import gups.core.Label
import gups.core.WORLD_TICK_PULSES
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.Room
import gups.core.model.SchemaNames
import gups.core.model.StandingChange
import gups.core.model.StandingRecord
import gups.core.model.StandingSubject
import gups.core.world.Boot
import gups.core.world.Diagnostic
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.ItemHistory
import gups.core.world.Persistence
import gups.core.world.SavedCharacter
import gups.core.world.World
import gups.core.world.bindSpawn
import gups.core.world.clampVitals
import gups.core.world.clearInputs
import gups.core.world.custodian
import gups.core.world.drainItemEvents
import gups.core.world.drainRemoved
import gups.core.world.dropOrphanStanding
import gups.core.world.fillMissingSpawns
import gups.core.world.forgetActorSlots
import gups.core.world.pruneStanding
import gups.core.world.recentInputs
import gups.core.world.respawnDeadlines
import gups.core.world.restoreItem
import gups.core.world.restoreMob
import gups.core.world.restorePet
import gups.core.world.restorePlayer
import gups.core.world.setRespawn
import gups.core.world.setZoneReset
import gups.core.world.settleRestoredHoldings
import gups.core.world.tombstone
import gups.core.world.upgrade
import gups.core.world.zoneResetDeadlines
import java.nio.file.Path
import kotlinx.serialization.builtins.ListSerializer

/** What a server wipe removed, for the startup log. */
data class WipeSummary(val backup: Path, val characters: Int, val items: Int)

/** What a restore did, for the startup log. */
data class RestoreReport(
  val restored: Int,
  val dropped: Int,
  val tombstoned: Int,
  val notes: List<String>,
)

/**
 * SQLite persistence engine and state coordinator for a live game world.
 *
 * ## Architecture & Concurrency
 * - **World thread capture:** Entity serialization occurs strictly on the world thread, ensuring
 *   state snapshots are consistent and non-blocking. Serialized batches are dispatched to
 *   [CheckpointWriter] for asynchronous commit.
 * - **In-memory mirror:** An in-memory cache mirrors the last captured row of every world entity
 *   ([entityRows]), player ([playerRows]), and zone state ([zoneRows]). Logins and repro bundles
 *   read exclusively from this mirror, guaranteeing they never block the world thread on disk
 *   operations or miss uncommitted in-flight batches.
 *
 * ## Checkpoint Cadence
 * - **Periodic checkpoints:** Captured at world tick intervals (configured via
 *   `checkpoint_minutes`, default 30). Executes invariant checks before serialization, then
 *   captures all non-debug dirty entities, zone states, metadata pulse, and diagnostics into a
 *   [Batch].
 * - **Immediate captures:** Captured at the end of triggering cascades (player-to-player item
 *   custody changes, level changes, character creation/deletion/choices/traits, and logout). Held
 *   in memory ([held]) until merged into the next periodic or shutdown checkpoint, ensuring both
 *   sides of an exchange commit atomically.
 *
 * ## Startup & Recovery
 * On startup, validates engine schema migrations, loads packages, restores non-player entities from
 * `world_entities`, reconstructs container and inventory hierarchies, drops orphaned instances
 * whose prototypes vanished, and reconciles live entities. Player characters are restored on-demand
 * upon login.
 */
class SqlitePersistence private constructor(val db: Database, private val log: (String) -> Unit) :
  Persistence {
  val writer = CheckpointWriter(db::write, log)

  private val entityRows = HashMap<Long, EntityRow>()

  /** The owner of each saved mob row that has one, so lookups don't decode every mob payload. */
  private val petOwners = HashMap<Long, Long>()
  private val playerRows = HashMap<Long, PlayerRow>()
  private val zoneRows = HashMap<String, String>()
  private var lastCapturePulse = 0L
  private val pendingDiagnostics = mutableListOf<DiagnosticRow>()
  private val pendingDeletes = LinkedHashSet<Long>()

  /** Immediate captures waiting for the next periodic checkpoint. */
  private var held: Batch? = null
  private var alerted = false

  /**
   * The course repository commit the live world was loaded from (`meta.world_commit`), saved with
   * every checkpoint. Set by deploys and at first startup.
   */
  @Volatile var worldCommit: String? = null

  /**
   * The schema names this database was started with (`meta.world_schema`), as JSON; null before the
   * first start.
   */
  @Volatile var worldSchema: String? = null

  /**
   * The features this database was started with (`meta.world_features`), as comma-separated ids;
   * null before the first start, and for databases that predate feature modules.
   */
  @Volatile var worldFeatures: String? = null

  init {
    worldCommit = db.meta()["world_commit"]
    worldSchema = db.meta()["world_schema"]
    worldFeatures = db.meta()["world_features"]
    db.entityRows().forEach(::putRow)
    db.playerRows().forEach { playerRows[it.id] = it }
    zoneRows.putAll(db.zoneStates())
    lastCapturePulse = db.meta()["pulse"]?.toLongOrNull() ?: 0
  }

  // ---- Startup --------------------------------------------------------------------------------

  /**
   * Compares [names] with the schema this database was started with. The first start records it.
   * Returns why the server must not start on this data, or null.
   */
  fun checkSchema(names: SchemaNames): String? {
    val saved = worldSchema
    if (saved == null) {
      recordSchema(names)
      return null
    }
    return SchemaRecord.mismatch(saved, names)
  }

  /**
   * Compares [features] with the ones this database was started with. The first start records them;
   * a database from before feature modules ran with all four. Call before [checkSchema], which
   * records the schema. Returns why the server must not start on this data, or null.
   */
  fun checkFeatures(features: Features): String? {
    val saved = worldFeatures
    if (saved == null) {
      if (worldSchema != null)
        return FeatureRecord.mismatch(FeatureRecord.encode(Features.ALL), features).also {
          if (it == null) recordFeatures(features)
        }
      recordFeatures(features)
      return null
    }
    return FeatureRecord.mismatch(saved, features)
  }

  private fun recordFeatures(features: Features) {
    val text = FeatureRecord.encode(features)
    db.setMeta("world_features", text)
    worldFeatures = text
  }

  private fun recordSchema(names: SchemaNames) {
    val text = SchemaRecord.encode(names)
    db.setMeta("world_schema", text)
    worldSchema = text
  }

  /**
   * A server wipe: takes a `prewipe` backup, deletes every character and item, and records [names].
   * Accounts, restrictions, session tokens and invites stay. The saved world state is dropped by
   * restoring with `fresh`.
   */
  fun wipe(names: SchemaNames, features: Features = Features.ALL): WipeSummary {
    val backup = backup(Backups.Kind.PREWIPE)
    val summary =
      WipeSummary(backup, playerRows.size, entityRows.values.count { it.kind == "item" })
    db.wipeCharacters()
    playerRows.clear()
    entityRows.values.removeIf { it.kind == "item" }
    recordSchema(names)
    recordFeatures(features)
    return summary
  }

  /**
   * How to build the world: saved pulse and id counters, and saved room ids. With [fresh] the world
   * boots purely from its definitions, with counters kept so ids never clash with saved characters
   * and their items.
   */
  fun boot(protos: Prototypes, fresh: Boolean): Boot {
    val meta = db.meta()
    val maxSaved = maxOf(entityRows.keys.maxOrNull() ?: 0, playerRows.keys.maxOrNull() ?: 0)
    val roomIds =
      if (fresh) {
        emptyMap()
      } else {
        entityRows.values
          .filter { it.kind == "room" && Label(it.protoLabel) in protos.rooms }
          .associate { Label(it.protoLabel) to EntityId(it.id) }
      }
    return Boot(
      pulse = meta["pulse"]?.toLong() ?: 0,
      nextEntityId = maxOf(meta["next_entity_id"]?.toLong() ?: 1, maxSaved + 1),
      nextHandle = meta["next_handle"]?.toLong() ?: 1,
      roomIds = roomIds,
      fillSpawns = fresh,
    )
  }

  /**
   * Restores saved world state into [world], built with [boot], and attaches this persistence to
   * it. Players and the items they own stay in the database until each player logs in. With
   * [fresh], all other saved world state is discarded.
   */
  fun restore(world: World, fresh: Boolean): RestoreReport {
    world.persistence = this
    val notes = mutableListOf<String>()
    var restored = 0
    val owned =
      entityRows.values
        .filter { (it.kind == "item" && ownerOf(it) != null) || it.id in petOwners }
        .mapTo(HashSet()) { it.id }
    val world0 = entityRows.values.filter { it.id !in owned }.sortedBy { it.id }
    // Held pets whose owner's character is gone, and what they hold, go even on a fresh start:
    // nobody can ever log in to bring them back.
    val orphans = petOwners.filterValues { it !in playerRows }
    val goneOwners = orphans.values.toSet()
    val deleted = LinkedHashSet<Long>()
    for (id in orphans.keys.sorted()) {
      deleted += id
      notes += "pet ${entityRows.getValue(id).protoLabel} #$id dropped: its owner is gone"
    }
    if (goneOwners.isNotEmpty()) {
      for (row in entityRows.values) {
        if (row.kind == "item" && ownerOf(row)?.let { it in goneOwners } == true) deleted += row.id
      }
    }
    if (fresh) {
      pendingDeletes += world0.map { it.id } + deleted
      for (pkg in zoneRows.keys) zoneRows[pkg] = ""
      notes += "--fresh: discarded ${world0.size} saved world rows"
      return RestoreReport(0, world0.size, 0, notes)
    }
    for (row in world0.filter { it.kind == "room" }) {
      val room = world.roomOrNull(Label(row.protoLabel))
      if (room == null || room.id.v != row.id) {
        deleted += row.id
        continue
      }
      applyRoom(room, JSON.decodeFromString(RoomPayload.serializer(), row.payload))
      room.protoVersion = row.protoVersion
      world.upgrade(room, room.proto.version)
      room.dirty = room.protoVersion != row.protoVersion
      restored++
    }
    for (row in world0.filter { it.kind == "mob" }) {
      val p = JSON.decodeFromString(MobPayload.serializer(), row.payload)
      val proto = world.protos.mobs[Label(row.protoLabel)]
      val room = world.roomOrNull(Label(p.room))
      if (proto == null || room == null) {
        deleted += row.id
        notes +=
          "mob ${row.protoLabel} #${row.id} dropped: its ${if (proto == null) "prototype" else "room ${p.room}"} is gone"
        continue
      }
      val m = world.restoreMob(EntityId(row.id), proto, room, p.index)
      applyMob(world, m, p)
      p.spawn?.let { s -> world.roomOrNull(Label(s.room))?.let { world.bindSpawn(m, it, s.rule) } }
      m.protoVersion = row.protoVersion
      world.upgrade(m, proto.version)
      m.dirty = m.protoVersion != row.protoVersion
      restored++
    }
    val placed =
      placeItems(world, world0.filter { it.kind == "item" }, tombstones = false, deleted, notes)
    restored += placed.size
    for (a in world.actors) {
      world.forgetActorSlots(a)
      world.clampVitals(a)
    }
    for ((pkg, json) in zoneRows) {
      if (json.isEmpty()) continue
      val zone = pkg in world.protos.zones
      // Non-zone packages (libraries, rules) keep a row only for their quarantine.
      if (!zone && pkg !in world.scripts.packages) continue
      val z = JSON.decodeFromString(ZonePayload.serializer(), json)
      if (zone) {
        z.resetAt?.let { world.setZoneReset(pkg, it) }
        for (r in z.respawns) world.roomOrNull(Label(r.room))?.let {
          world.setRespawn(it, r.rule, r.at)
        }
      }
      if (z.quarantined) world.scripts.quarantine(world, pkg, "as it was at the last checkpoint")
    }
    world.fillMissingSpawns()
    pendingDeletes += deleted
    if (!fresh && Feature.STANDING in world.features) {
      val rows = db.standings()
      world.factionStanding.clear()
      for (row in rows) {
        val holder = Label(row.holder)
        val subject =
          if (row.subject.startsWith("actor:")) {
            StandingSubject.Of(EntityId(row.subject.removePrefix("actor:").toLong()))
          } else {
            StandingSubject.Faction(Label(row.subject))
          }
        val record = StandingRecord()
        record.amount = row.amount
        record.updatedPulse = row.updatedPulse
        val historyList =
          JSON.decodeFromString(ListSerializer(StandingChangeJson.serializer()), row.historyJson)
        for (h in historyList) {
          record.history.add(StandingChange(h.pulse, h.amount, h.event, h.via?.let(::EntityId)))
        }
        world.factionStanding[holder to subject] = record
      }
      notes += world.dropOrphanStanding(checkActors = true)
    }
    return RestoreReport(restored, deleted.size, 0, notes)
  }

  private fun putRow(row: EntityRow) {
    entityRows[row.id] = row
    val owner =
      if (row.kind == "mob") JSON.decodeFromString(MobPayload.serializer(), row.payload).owner
      else null
    if (owner != null) petOwners[row.id] = owner else petOwners.remove(row.id)
  }

  /**
   * The player whose custody an item row's location chain ends in, by id: directly, or through a
   * pet of theirs.
   */
  private fun ownerOf(row: EntityRow, seen: Int = 0): Long? {
    val loc =
      row.location?.let { JSON.decodeFromString(LocationJson.serializer(), it) } ?: return null
    val holder = loc.carried ?: loc.equipped
    if (holder != null) {
      if (holder in playerRows) return holder
      return petOwners[holder]
    }
    val c = loc.contained ?: return null
    if (seen > 64) return null
    return entityRows[c]?.let { ownerOf(it, seen + 1) }
  }

  override fun loadCharacter(world: World, name: String): Player? {
    val row =
      playerRows.values.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
    val p = JSON.decodeFromString(PlayerPayload.serializer(), row.payload)
    val room =
      world.roomOrNull(Label(p.room)) ?: world.roomOrNull(Label(p.recall)) ?: world.rooms.first()
    val recall = Label(p.recall).takeIf { world.roomOrNull(it) != null } ?: room.label
    val player =
      world.restorePlayer(EntityId(row.id), row.name, room, p.index, recall, row.accountId)
    applyPlayer(world, player, p)
    val notes = mutableListOf<String>()
    val deleted = LinkedHashSet<Long>()

    val petRows =
      petOwners.filterValues { it == row.id }.keys.sorted().map { entityRows.getValue(it) }

    val restoredPets = mutableListOf<Mob>()
    for (mobRow in petRows) {
      val proto = world.protos.mobs[Label(mobRow.protoLabel)]
      if (proto == null) {
        deleted += mobRow.id
        notes += "pet ${mobRow.protoLabel} #${mobRow.id} dropped: its prototype is gone"
        continue
      }
      val m = world.restoreMob(EntityId(mobRow.id), proto, room, player.trackIndex)
      val payload = JSON.decodeFromString(MobPayload.serializer(), mobRow.payload)
      applyMob(world, m, payload)
      m.protoVersion = mobRow.protoVersion
      world.upgrade(m, proto.version)
      m.dirty = m.protoVersion != mobRow.protoVersion
      world.restorePet(m, player)
      restoredPets += m
    }

    placeItems(
      world,
      entityRows.values.filter { it.kind == "item" && ownerOf(it) == row.id },
      tombstones = true,
      deleted,
      notes,
    )
    world.forgetActorSlots(player)
    world.clampVitals(player)
    for (m in restoredPets) {
      world.forgetActorSlots(m)
      world.clampVitals(m)
    }
    pendingDeletes += deleted
    notes.forEach(log)
    player.dirty = room.label.v != p.room
    return player
  }

  // ---- Checkpoints ----------------------------------------------------------------------------

  override fun checkpoint(world: World, cause: String, immediate: Boolean) {
    val b = Batch(immediate, cause, world.pulse)
    val (nextId, nextHandle) = world.nextIds
    b.meta["pulse"] = world.pulse.toString()
    b.meta["next_entity_id"] = nextId.toString()
    b.meta["next_handle"] = nextHandle.toString()
    worldCommit?.let { b.meta["world_commit"] = it }
    worldSchema?.let { b.meta["world_schema"] = it }
    worldFeatures?.let { b.meta["world_features"] = it }
    val excluded = world.checkpointExclusions
    for (e in world.allEntities) {
      // Debug spawns are never saved.
      if (!e.dirty || e.id in excluded || e.debug) continue
      if (e is Player) {
        world.encodePlayer(e).also {
          b.players[it.id] = it
          playerRows[it.id] = it
        }
      } else {
        val row = world.encode(e)
        if (row != null) {
          b.entities[row.id] = row
          putRow(row)
        }
      }
      e.dirty = false
    }
    for (id in world.drainRemoved().map { it.v } + pendingDeletes) {
      if (id in entityRows || id in b.entities) {
        b.entities[id] = null
        entityRows.remove(id)
        petOwners.remove(id)
      }
    }
    pendingDeletes.clear()
    val wall = System.currentTimeMillis()
    for (ev in world.drainItemEvents()) {
      b.events +=
        ItemEventRow(
          ev.item.v,
          ev.pulse,
          wall,
          ev.from?.let {
            JSON.encodeToString(LocationJson.serializer(), world.locationJsonOrRoomless(it))
          },
          ev.to?.let {
            JSON.encodeToString(LocationJson.serializer(), world.locationJsonOrRoomless(it))
          },
          ev.cause,
        )
    }
    if (!immediate) {
      for ((pkg, json) in zoneStates(world)) {
        if (zoneRows[pkg] != json) {
          b.zones[pkg] = json
          zoneRows[pkg] = json
        }
      }
      if (Feature.STANDING in world.features) {
        world.pruneStanding()
        world.dropOrphanStanding(checkActors = true)
        b.standings =
          world.factionStanding.map { (key, record) ->
            val (holder, subject) = key
            val subjectStr =
              when (subject) {
                is StandingSubject.Of -> "actor:${subject.actor.v}"
                is StandingSubject.Faction -> subject.label.v
              }
            val historyList =
              record.history.map { StandingChangeJson(it.pulse, it.amount, it.event, it.via?.v) }
            val historyJson =
              JSON.encodeToString(ListSerializer(StandingChangeJson.serializer()), historyList)
            StandingRow(holder.v, subjectStr, record.amount, record.updatedPulse, historyJson)
          }
      }
      lastCapturePulse = world.pulse
      world.clearInputs()
    }
    b.diagnostics += pendingDiagnostics
    pendingDiagnostics.clear()
    if (immediate) {
      // Captured now, so both sides of a transfer are in one batch; written with the next
      // periodic checkpoint to keep disk writes rare.
      held?.merge(b) ?: run { held = b }
      return
    }
    writer.submit(combined(b))
    val oldest = writer.oldestPending
    if (oldest != null && world.pulse - oldest > 3L * WORLD_TICK_PULSES) {
      if (!alerted) {
        alerted = true
        world.record(
          Diagnostic(
            world.pulse,
            "checkpoint",
            "changes since pulse $oldest are still not saved: ${writer.lastError ?: "the writer is behind"}",
          )
        )
      }
    } else {
      alerted = false
    }
  }

  private fun World.locationJsonOrRoomless(loc: ItemLocation): LocationJson =
    if (loc is ItemLocation.InRoom && entity(loc.room) !is Room)
      LocationJson(index = loc.trackIndex)
    else locationJson(loc)

  private fun zoneStates(world: World): Map<String, String> {
    val resets = world.zoneResetDeadlines()
    val respawns = world.respawnDeadlines()
    val quarantined = world.scripts.quarantinedPackages
    return (world.protos.zones.keys +
        quarantined +
        zoneRows.keys.filter { it.isNotEmpty() && zoneRows[it]!!.isNotEmpty() })
      .associateWith { pkg ->
        JSON.encodeToString(
          ZonePayload.serializer(),
          ZonePayload(
            resetAt = resets[pkg],
            quarantined = pkg in quarantined,
            respawns =
              respawns
                .filterKeys { it.first.pkg == pkg }
                .map { (k, at) -> RespawnJson(k.first.v, k.second, at) },
          ),
        )
      }
  }

  override fun characters(accountId: Long): List<String> =
    playerRows.values.filter { it.accountId == accountId }.sortedBy { it.id }.map { it.name }

  override fun savedCharacter(name: String): SavedCharacter? =
    playerRows.values
      .firstOrNull { it.name.equals(name, ignoreCase = true) }
      ?.let { SavedCharacter(it.name, it.accountId) }

  override fun isSavedActor(id: EntityId): Boolean =
    id.v in playerRows || entityRows[id.v]?.kind == "mob"

  private val accountStore = lazy { SqliteAccountStore.through(db.file, writer) }

  /** Account storage over this database; account writes join the checkpoint writer's queue. */
  override val accounts: AccountStore
    get() = accountStore.value

  override val backlog: Int
    get() = writer.backlog

  /** Waits for what is queued, then takes a `manual` snapshot (`@snapshot`). */
  override fun backup(): String = backup(Backups.Kind.MANUAL).toString()

  /** Waits for what is queued, then takes a snapshot of [kind], such as `predeploy`. Blocks. */
  fun backup(kind: Backups.Kind): Path {
    writer.flush()
    return Backups(db).snapshot(kind)
  }

  /** A sanitized copy of the database at [target], for the CI migration check. Blocks. */
  fun sanitizedCopy(target: Path): Path {
    writer.flush()
    return db.sanitizedCopy(target)
  }

  private val reads = lazy { readOnlyConnection(db.file) }

  override fun itemHistory(itemId: Long): List<ItemHistory> =
    synchronized(reads) {
      reads.value
        .prepareStatement(
          "SELECT pulse, wall_time, from_json, to_json, cause FROM item_events WHERE item_id = ? ORDER BY id"
        )
        .use { st ->
          st.setLong(1, itemId)
          st.executeQuery().use { rs ->
            buildList {
              while (rs.next()) add(
                ItemHistory(
                  rs.getLong(1),
                  rs.getLong(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getString(5),
                )
              )
            }
          }
        }
    }

  override val maintenance: String?
    get() =
      if (writer.healthy) null
      else
        "The world can't save right now, so new logins are paused. Please try again in a few minutes."

  // ---- Diagnostics -----------------------------------------------------------------------------

  override fun diagnostic(world: World, d: Diagnostic) {
    pendingDiagnostics +=
      DiagnosticRow(
        pulse = d.pulse,
        wallTime = System.currentTimeMillis(),
        pkg = d.pkg,
        label = d.label?.v,
        hook = d.hook,
        file = d.file,
        line = d.line,
        kind = d.kind,
        message = d.message,
        stack = d.stack ?: d.cause?.stackTraceToString(),
        ctxJson = d.ctx?.let { JSON.encodeToString(ReproCtx.serializer(), ReproCtx(it)) },
        seed = d.seed,
        reproJson = bundle(world, d)?.let { JSON.encodeToString(ReproBundle.serializer(), it) },
      )
  }

  /**
   * A repro bundle: the failing room's entities as of the last checkpoint, and the inputs of actors
   * in the failing package's rooms since then.
   */
  private fun bundle(world: World, d: Diagnostic): ReproBundle? {
    val room = d.room ?: return null
    val pkg = d.pkg ?: return null
    val roomRow = entityRows.values.firstOrNull { it.kind == "room" && it.protoLabel == room.v }
    val mobs =
      entityRows.values.filter {
        it.kind == "mob" &&
          JSON.decodeFromString(MobPayload.serializer(), it.payload).room == room.v
      }
    val players =
      playerRows.values.filter {
        JSON.decodeFromString(PlayerPayload.serializer(), it.payload).room == room.v
      }
    val holders = (mobs.map { it.id } + players.map { it.id }).toMutableSet()
    val items = mutableListOf<EntityRow>()
    var grew = true
    while (grew) {
      grew = false
      for (row in entityRows.values) {
        if (row.kind != "item" || items.any { it.id == row.id }) continue
        val loc = JSON.decodeFromString(LocationJson.serializer(), row.location ?: "{}")
        val inRoom =
          loc.room == room.v ||
            (loc.carried ?: loc.equipped ?: loc.contained)?.let { it in holders } == true
        if (inRoom) {
          items += row
          holders += row.id
          grew = true
        }
      }
    }
    return ReproBundle(
      seed = d.seed ?: world.config.seed,
      pulse = d.pulse,
      checkpointPulse = lastCapturePulse,
      pkg = pkg,
      room = room.v,
      hook = d.hook,
      message = d.message,
      entities = (listOfNotNull(roomRow) + mobs + items).map(::RowJson),
      players = players.map { PlayerRowJson(it.id, it.name, it.payload) },
      inputs =
        world.recentInputs
          .filter { it.room.pkg == pkg }
          .map { InputJson(it.pulse, it.actor.v, it.actorName, it.room.v, it.line) },
    )
  }

  /** [b] with any held immediate captures in front of it, oldest first. */
  private fun combined(b: Batch): Batch {
    val h = held ?: return b
    held = null
    return Batch(false, b.cause, h.firstPulse).also {
      it.merge(h)
      it.merge(b)
    }
  }

  /** Commits everything captured so far, held captures included, and closes the database. */
  fun close(timeoutMs: Long = 30_000): Boolean {
    held?.let { writer.submit(combined(Batch(false, "close", it.firstPulse))) }
    val ok = writer.close(timeoutMs)
    if (accountStore.isInitialized()) accountStore.value.close()
    if (reads.isInitialized()) synchronized(reads) { reads.value.close() }
    db.close()
    return ok
  }

  companion object {
    fun open(file: Path, log: (String) -> Unit = {}): SqlitePersistence =
      SqlitePersistence(Database.open(file), log)
  }
}

/**
 * Restores item rows whose holders exist, containers before contents. Rows that can't be placed, or
 * whose prototype is gone (unless [tombstones]), are deleted.
 */
internal fun placeItems(
  world: World,
  rows: List<EntityRow>,
  tombstones: Boolean,
  deleted: MutableSet<Long>,
  notes: MutableList<String>,
): List<Item> {
  val waiting = rows.sortedBy { it.id }.toMutableList()
  val out = mutableListOf<Item>()
  val ids = rows.mapTo(HashSet()) { it.id }
  var progress = true
  while (progress && waiting.isNotEmpty()) {
    progress = false
    val it = waiting.iterator()
    while (it.hasNext()) {
      val row = it.next()
      val loc = JSON.decodeFromString(LocationJson.serializer(), row.location ?: "{}")
      val at: ItemLocation? =
        when {
          loc.room != null ->
            world.roomOrNull(Label(loc.room))?.let { r ->
              ItemLocation.InRoom(r.id, (loc.index ?: 0).coerceIn(0, r.trackSize - 1))
            }
          loc.carried != null ->
            world.actor(EntityId(loc.carried))?.let { a -> ItemLocation.Carried(a.id) }
          loc.equipped != null ->
            world.actor(EntityId(loc.equipped))?.let { a ->
              ItemLocation.Equipped(a.id, loc.slot ?: "held")
            }
          loc.contained != null ->
            world.item(EntityId(loc.contained))?.let { c -> ItemLocation.Contained(c.id) }
          else -> null
        }
      if (at == null) {
        // Wait for a container that is still to be placed; otherwise give up on it.
        if (
          loc.contained != null &&
            loc.contained in ids &&
            waiting.any { w -> w.id == loc.contained }
        )
          continue
        it.remove()
        deleted += row.id
        notes += "item ${row.protoLabel} #${row.id} dropped: where it was is gone"
        progress = true
        continue
      }
      val payload = JSON.decodeFromString(ItemPayload.serializer(), row.payload)
      val known = world.protos.items[Label(row.protoLabel)]
      val proto: ItemProto =
        when {
          known != null && payload.tombstone == null -> known
          tombstones ->
            tombstone(
              ItemProto(Label(row.protoLabel), payload.name, emptyList()).let { p ->
                p.copy(
                  keywords =
                    payload.name.lowercase().split(' ').filter { w ->
                      w.isNotEmpty() && w !in setOf("a", "an", "the")
                    },
                  weight = payload.weight,
                  stackable = payload.stackable,
                  container = payload.container?.let(::containerFromJson),
                  plural = payload.plural,
                )
              }
            )
          else -> {
            it.remove()
            deleted += row.id
            notes += "item ${row.protoLabel} #${row.id} despawned: its prototype is gone"
            progress = true
            continue
          }
        }
      val item = world.restoreItem(EntityId(row.id), proto, at, payload.count)
      applyItem(item, payload)
      if (known == null || payload.tombstone != null) {
        item.savedPayload = payload.tombstone ?: row.payload
        if (payload.tombstone == null)
          notes += "item ${row.protoLabel} #${row.id} is now a tombstone"
      } else {
        item.protoVersion = row.protoVersion
        world.upgrade(item, known.version)
      }
      item.dirty = known == null || item.protoVersion != row.protoVersion
      out += item
      it.remove()
      progress = true
    }
  }
  for (row in waiting) {
    deleted += row.id
    notes += "item ${row.protoLabel} #${row.id} dropped: its container is gone"
  }
  for (holder in out.mapNotNull { world.custodian(it) }.distinct()) world.settleRestoredHoldings(
    holder
  )
  return out
}
