package gups.persist

import gups.core.EntityId
import gups.core.Label
import gups.core.world.InputRecord
import gups.core.world.World
import gups.core.world.restoreMob
import gups.core.world.restorePlayer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReproCtx(val summary: String)

@Serializable
data class RowJson(val id: Long, val kind: String, val protoLabel: String, val protoVersion: Int, val location: String? = null, val payload: String) {
    constructor(r: EntityRow) : this(r.id, r.kind, r.protoLabel, r.protoVersion, r.location, r.payload)

    fun row() = EntityRow(id, kind, protoLabel, protoVersion, location, payload, 0)
}

@Serializable
data class PlayerRowJson(val id: Long, val name: String, val payload: String)

@Serializable
data class InputJson(val pulse: Long, val actor: Long, val actorName: String, val room: String, val line: String)

/**
 * What `gups repro` replays (SPEC §6.2): the failing room as of the last checkpoint, and the
 * inputs that followed, up to the failure.
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
    val maxId: Long get() = (entities.map { it.id } + players.map { it.id }).maxOrNull() ?: 0

    val records: List<InputRecord> get() = inputs.map { InputRecord(it.pulse, EntityId(it.actor), it.actorName, Label(it.room), it.line) }

    companion object {
        fun parse(json: String): ReproBundle = JSON.decodeFromString(serializer(), json)
    }

    fun toJson(): String = JSON.encodeToString(serializer(), this)
}

/**
 * Puts a bundle's entities into [world], built with ids starting above [ReproBundle.maxId].
 * Players come back without a connection. Returns notes about anything that couldn't be placed.
 */
fun restoreBundle(world: World, bundle: ReproBundle): List<String> {
    val notes = mutableListOf<String>()
    val rows = bundle.entities.map { it.row() }
    for (row in rows.filter { it.kind == "room" }) {
        world.roomOrNull(Label(row.protoLabel))?.let { applyRoom(it, JSON.decodeFromString(RoomPayload.serializer(), row.payload)) }
    }
    for (row in rows.filter { it.kind == "mob" }) {
        val p = JSON.decodeFromString(MobPayload.serializer(), row.payload)
        val proto = world.protos.mobs[Label(row.protoLabel)]
        val room = world.roomOrNull(Label(p.room))
        if (proto == null || room == null) {
            notes += "mob ${row.protoLabel} #${row.id} can't be restored: its prototype or room is gone"
            continue
        }
        applyMob(world.restoreMob(EntityId(row.id), proto, room, p.index), p)
    }
    for (pl in bundle.players) {
        val p = JSON.decodeFromString(PlayerPayload.serializer(), pl.payload)
        val room = world.roomOrNull(Label(p.room)) ?: continue
        applyPlayer(world.restorePlayer(EntityId(pl.id), pl.name, room, p.index, room.label), p)
    }
    placeItems(world, rows.filter { it.kind == "item" }, tombstones = true, LinkedHashSet(), notes)
    return notes
}
