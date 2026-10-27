package gups.persist

import gups.core.EntityId
import gups.core.Label
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.world.Diagnostic
import gups.core.world.InputRecord
import gups.core.world.World
import gups.core.world.recentInputs
import gups.core.world.restoreMob
import gups.core.world.restorePlayer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class ReproCtx(val summary: String)

@Serializable
data class RowJson(
  val id: Long,
  val kind: String,
  val protoLabel: String,
  val protoVersion: Int,
  val location: String? = null,
  val payload: String,
) {
  constructor(
    r: EntityRow
  ) : this(r.id, r.kind, r.protoLabel, r.protoVersion, r.location, r.payload)

  fun row() = EntityRow(id, kind, protoLabel, protoVersion, location, payload, 0)
}

@Serializable data class PlayerRowJson(val id: Long, val name: String, val payload: String)

@Serializable
data class InputJson(
  val pulse: Long,
  val actor: Long,
  val actorName: String,
  val room: String,
  val line: String,
)

/**
 * What `gups repro` replays: the failing room as of the last checkpoint, and the inputs that
 * followed, up to the failure.
 */
@Serializable
data class ReproBundle(
  @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
  val seed: Long,
  /** When the failure happened. */
  val pulse: Long,
  /** The checkpoint the entities come from. */
  val checkpointPulse: Long,
  val pkg: String,
  val room: String,
  val hook: String? = null,
  val message: String,
  val entities: List<RowJson> = emptyList(),
  val players: List<PlayerRowJson> = emptyList(),
  val inputs: List<InputJson> = emptyList(),
) {
  /** The largest id in the bundle, so a replay world can start its own ids above it. */
  val maxId: Long
    get() = (entities.map { it.id } + players.map { it.id }).maxOrNull() ?: 0

  val records: List<InputRecord>
    get() =
      inputs.map {
        InputRecord(it.pulse, EntityId(it.actor), it.actorName, Label(it.room), it.line)
      }

  companion object {
    fun parse(json: String): ReproBundle = JSON.decodeFromString(serializer(), json)
  }

  fun toJson(): String = JSON.encodeToString(serializer(), this)
}

/**
 * Puts a bundle's entities into [world], built with ids starting above [ReproBundle.maxId]. Players
 * come back without a connection. Returns notes about anything that couldn't be placed.
 */
fun restoreBundle(world: World, bundle: ReproBundle): List<String> {
  val notes = mutableListOf<String>()
  val rows = bundle.entities.map { it.row() }
  for (row in rows.filter { it.kind == "room" }) {
    world.roomOrNull(Label(row.protoLabel))?.let {
      applyRoom(it, JSON.decodeFromString(RoomPayload.serializer(), row.payload))
    }
  }
  for (row in rows.filter { it.kind == "mob" }) {
    val p = JSON.decodeFromString(MobPayload.serializer(), row.payload)
    val proto = world.protos.mobs[Label(row.protoLabel)]
    val room = world.roomOrNull(Label(p.room))
    if (proto == null || room == null) {
      notes += "mob ${row.protoLabel} #${row.id} can't be restored: its prototype or room is gone"
      continue
    }
    applyMob(world, world.restoreMob(EntityId(row.id), proto, room, p.index), p)
  }
  for (pl in bundle.players) {
    val p = JSON.decodeFromString(PlayerPayload.serializer(), pl.payload)
    val room = world.roomOrNull(Label(p.room)) ?: continue
    applyPlayer(world, world.restorePlayer(EntityId(pl.id), pl.name, room, p.index, room.label), p)
  }
  placeItems(world, rows.filter { it.kind == "item" }, tombstones = true, LinkedHashSet(), notes)
  return notes
}

/**
 * Builds a repro bundle from a live [world] for [d]. When [playerStartRooms] is provided, sets
 * player starting rooms to their pulse-0 positions and preserves all inputs so room-transition
 * failures reproduce cleanly.
 */
fun buildReproBundle(
  world: World,
  d: Diagnostic,
  playerStartRooms: Map<EntityId, Label> = emptyMap(),
): ReproBundle {
  val roomLabel = d.room ?: world.rooms.firstOrNull()?.label
  val room = roomLabel?.let { world.roomOrNull(it) }
  val roomV = roomLabel?.v ?: "//unknown:room"
  val pkg = d.pkg ?: (roomLabel?.pkg ?: "")
  val roomRow = room?.let { world.encode(it) }?.let(::RowJson)

  val mobs = world.actors.filterIsInstance<Mob>().filter { it.room.label == roomLabel }
  val mobRows = mobs.mapNotNull { world.encode(it) }.map(::RowJson)

  val players =
    if (playerStartRooms.isNotEmpty()) world.players
    else world.players.filter { it.room.label == roomLabel }
  val playerRows =
    players.map {
      val encoded = world.encodePlayer(it)
      val payload =
        if (playerStartRooms.containsKey(it.id)) {
          val decoded = JSON.decodeFromString(PlayerPayload.serializer(), encoded.payload)
          JSON.encodeToString(
            PlayerPayload.serializer(),
            decoded.copy(room = playerStartRooms.getValue(it.id).v, index = 0),
          )
        } else {
          encoded.payload
        }
      PlayerRowJson(encoded.id, encoded.name, payload)
    }

  val holders = (mobs.map { it.id } + players.map { it.id }).toSet()
  val items =
    world.allEntities.filterIsInstance<Item>().filter { item ->
      val loc = item.location
      room?.id != null && loc is ItemLocation.InRoom && loc.room == room.id ||
        loc is ItemLocation.Carried && loc.actor in holders ||
        loc is ItemLocation.Equipped && loc.actor in holders
    }
  val itemRows = items.mapNotNull { world.encode(it) }.map(::RowJson)

  val inputs =
    world.recentInputs.map { InputJson(it.pulse, it.actor.v, it.actorName, it.room.v, it.line) }

  return ReproBundle(
    seed = d.seed ?: world.config.seed,
    pulse = d.pulse,
    checkpointPulse = 0,
    pkg = pkg,
    room = roomV,
    hook = d.hook,
    message = d.message,
    entities = listOfNotNull(roomRow) + mobRows + itemRows,
    players = playerRows,
    inputs = inputs,
  )
}
