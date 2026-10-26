package gups.persist

import gups.core.AttrValue
import gups.core.EntityId
import gups.core.HookRef
import gups.core.Label
import gups.core.Role
import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.Channel
import gups.core.model.DamageType
import gups.core.model.DoorState
import gups.core.model.DormantRecovery
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.ModifierPoint
import gups.core.model.Player
import gups.core.model.Room
import gups.core.model.ScriptTimer
import gups.core.world.World
import gups.core.world.spawnOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

// Row payloads (SPEC §3.4, §3.6). An instance stores its prototype label and version plus only
// what differs from the prototype; players have no prototype and store everything. Every
// payload carries a format_version for engine-side schema evolution.

const val FORMAT_VERSION = 1

internal val JSON = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
}

/** A `world_entities` row. Item locations live only here (SPEC §3.6). */
data class EntityRow(
  val id: Long,
  val kind: String,
  val protoLabel: String,
  val protoVersion: Int,
  val location: String?,
  val payload: String,
  val updatedPulse: Long,
)

/** A `players` row. It never lists items: their rows say where they are. */
data class PlayerRow(
  val id: Long,
  val accountId: Long?,
  val name: String,
  val payload: String,
  val updatedPulse: Long,
)

@Serializable data class RefJson(val file: String, val name: String)

@Serializable
data class TimerJson(
  val id: Long,
  val fn: RefJson,
  val args: JsonObject = JsonObject(emptyMap()),
  val due: Long? = null,
  val remaining: Int? = null,
)

@Serializable
data class AffectJson(
  val id: Long,
  val name: String,
  val source: Long? = null,
  val appliedAt: Long,
  val expiresAt: Long,
  val statDeltas: Map<String, Int> = emptyMap(),
  val flags: List<String> = emptyList(),
  val tickEvery: Int? = null,
  val onTick: RefJson? = null,
  val onExpire: RefJson? = null,
  val expireMessage: String = "",
  /** Modifier functions by point name (SPEC §4.6). */
  val modifiers: Map<String, RefJson> = emptyMap(),
)

/** Where an item is: a room by label (room ids aren't stable for references), or a holder by id. */
@Serializable
data class LocationJson(
  val room: String? = null,
  val index: Int? = null,
  val carried: Long? = null,
  val equipped: Long? = null,
  val slot: String? = null,
  val contained: Long? = null,
)

@Serializable data class SpawnJson(val room: String, val rule: Int)

@Serializable
data class RoomPayload(
  @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
  /** Door states that differ from the prototype, by direction. */
  val doors: Map<String, String> = emptyMap(),
  /** Exit blocks that differ from the prototype, by direction. */
  val blocks: Map<String, List<String>> = emptyMap(),
  val attrs: JsonObject = JsonObject(emptyMap()),
  val timers: List<TimerJson> = emptyList(),
  /** `set_active` and `set_anchor` reasons (SPEC §5.9). */
  val active: List<String> = emptyList(),
  val anchor: List<String> = emptyList(),
)

@Serializable
data class MobPayload(
  @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
  val room: String,
  val index: Int,
  val hp: Int,
  val mana: Int,
  val move: Int,
  val xp: Long = 0,
  val home: String? = null,
  val spawn: SpawnJson? = null,
  // The delta: present only where the instance differs from its prototype.
  val level: Int? = null,
  val maxHp: Int? = null,
  val maxMana: Int? = null,
  val maxMove: Int? = null,
  val armor: Int? = null,
  val stats: Map<String, Int>? = null,
  val resists: Map<String, Int>? = null,
  val skills: Map<String, Int>? = null,
  val thinkEvery: Int? = null,
  val dormantRecovery: String? = null,
  val attrs: JsonObject = JsonObject(emptyMap()),
  val timers: List<TimerJson> = emptyList(),
  val affects: List<AffectJson> = emptyList(),
  val active: List<String> = emptyList(),
  val anchor: List<String> = emptyList(),
)

@Serializable
data class ItemPayload(
  @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
  /** Kept so a tombstone can show what the item was (SPEC §3.4). */
  val name: String,
  val count: Int = 1,
  val decayAt: Long? = null,
  val nameOverride: String? = null,
  val containerState: String? = null,
  val attrs: JsonObject = JsonObject(emptyMap()),
  val timers: List<TimerJson> = emptyList(),
  /** For a tombstone: the payload the item had when its prototype vanished. */
  val tombstone: String? = null,
  val active: List<String> = emptyList(),
  val anchor: List<String> = emptyList(),
)

@Serializable
data class PlayerPayload(
  @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
  val room: String,
  val index: Int,
  val recall: String,
  val role: String = Role.PLAYER.name,
  val level: Int,
  val xp: Long,
  val hp: Int,
  val maxHp: Int,
  val mana: Int,
  val maxMana: Int,
  val move: Int,
  val maxMove: Int,
  val armor: Int,
  val stats: Map<String, Int>,
  val resists: Map<String, Int> = emptyMap(),
  val skills: Map<String, Int> = emptyMap(),
  val attrs: JsonObject = JsonObject(emptyMap()),
  val timers: List<TimerJson> = emptyList(),
  val affects: List<AffectJson> = emptyList(),
  val active: List<String> = emptyList(),
  val anchor: List<String> = emptyList(),
)

@Serializable data class RespawnJson(val room: String, val rule: Int, val at: Long)

/** A `zone_states` payload: reset and respawn deadlines, and quarantine (SPEC §3.6, §6.3). */
@Serializable
data class ZonePayload(
  @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
  val resetAt: Long? = null,
  val quarantined: Boolean = false,
  val respawns: List<RespawnJson> = emptyList(),
)

// ---- Attribute values (SPEC §3.2) ---------------------------------------------------------------

fun AttrValue.toJson(): JsonElement =
  when (this) {
    is AttrValue.I -> JsonPrimitive(v)
    is AttrValue.S -> JsonPrimitive(v)
    is AttrValue.B -> JsonPrimitive(v)
    is AttrValue.L -> JsonArray(v.map { it.toJson() })
    is AttrValue.M -> JsonObject(v.mapValues { it.value.toJson() })
  }

fun JsonElement.toAttr(): AttrValue =
  when (this) {
    is JsonObject -> AttrValue.M(mapValues { it.value.toAttr() })
    is JsonArray -> AttrValue.L(map { it.toAttr() })
    is JsonPrimitive ->
      when {
        isString -> AttrValue.S(content)
        booleanOrNull != null -> AttrValue.B(booleanOrNull!!)
        longOrNull != null -> AttrValue.I(longOrNull!!)
        else -> error("Unreadable attribute value $this")
      }
  }

internal fun attrsJson(attrs: Map<String, AttrValue>) =
  JsonObject(attrs.mapValues { it.value.toJson() })

internal fun JsonObject.toAttrs(): Map<String, AttrValue> = mapValues { it.value.toAttr() }

// ---- Timers and affects
// --------------------------------------------------------------------------

private fun HookRef.json() = RefJson(file.v, name)

private fun RefJson.ref() = HookRef(Label(file), name)

internal fun timersJson(e: Entity) =
  e.timers.map { TimerJson(it.id, it.fn.json(), attrsJson(it.args), it.due, it.remaining) }

internal fun restoreTimers(e: Entity, timers: List<TimerJson>) {
  e.timers.clear()
  for (t in timers) e.timers += ScriptTimer(t.id, t.fn.ref(), t.args.toAttrs(), t.due, t.remaining)
}

internal fun affectsJson(a: Actor) =
  a.affects.map {
    AffectJson(
      it.id,
      it.name,
      it.source?.v,
      it.appliedAt,
      it.expiresAt,
      it.statDeltas,
      it.flags.sorted(),
      it.tickEvery,
      it.onTick?.json(),
      it.onExpire?.json(),
      it.expireMessage,
      it.modifiers.entries.associate { (k, v) -> k.scriptName to v.json() },
    )
  }

internal fun restoreReasons(e: Entity, active: List<String>, anchor: List<String>) {
  e.activeReasons.clear()
  e.activeReasons.addAll(active)
  e.anchorReasons.clear()
  e.anchorReasons.addAll(anchor)
}

internal fun restoreAffects(a: Actor, affects: List<AffectJson>) {
  a.affects.clear()
  for (x in affects) {
    a.affects +=
      Affect(
        x.id,
        x.name,
        x.source?.let(::EntityId),
        x.appliedAt,
        x.expiresAt,
        x.statDeltas,
        x.flags.toSet(),
        x.tickEvery,
        x.onTick?.ref(),
        x.onExpire?.ref(),
        x.expireMessage,
        x.modifiers.entries
          .mapNotNull { (k, v) -> ModifierPoint.byName(k)?.let { it to v.ref() } }
          .toMap(),
      )
  }
}

// ---- Encoding (world thread) -------------------------------------------------------------------

internal fun World.locationJson(loc: ItemLocation): LocationJson =
  when (loc) {
    is ItemLocation.InRoom ->
      LocationJson(room = (entity(loc.room) as Room).label.v, index = loc.trackIndex)
    is ItemLocation.Carried -> LocationJson(carried = loc.actor.v)
    is ItemLocation.Equipped -> LocationJson(equipped = loc.actor.v, slot = loc.slot)
    is ItemLocation.Contained -> LocationJson(contained = loc.container.v)
  }

private fun <T> delta(live: T, proto: T): T? = if (live == proto) null else live

internal fun World.encode(e: Entity): EntityRow? =
  when (e) {
    is Room -> {
      val p =
        RoomPayload(
          doors =
            e.exits.values
              .mapNotNull { x ->
                val state = x.door?.state ?: return@mapNotNull null
                val was = e.proto.exits.first { it.direction == x.direction }.door?.state
                if (state == was) null else x.direction.name to state.name.lowercase()
              }
              .toMap(),
          blocks =
            e.exits.values
              .mapNotNull { x ->
                val was = e.proto.exits.first { it.direction == x.direction }.blocks
                if (x.blocks == was) null
                else x.direction.name to x.blocks.map { it.name.lowercase() }.sorted()
              }
              .toMap(),
          attrs = attrsJson(e.customAttributes),
          timers = timersJson(e),
          active = e.activeReasons.toList(),
          anchor = e.anchorReasons.toList(),
        )
      EntityRow(
        e.id.v,
        "room",
        e.protoLabel.v,
        e.protoVersion,
        null,
        JSON.encodeToString(RoomPayload.serializer(), p),
        pulse,
      )
    }
    is Mob -> {
      val p = e.proto
      val payload =
        MobPayload(
          room = e.room.label.v,
          index = e.trackIndex,
          hp = e.hp,
          mana = e.mana,
          move = e.move,
          xp = e.xp,
          home = e.homeRoom.v.takeIf { e.homeRoom != e.room.label },
          spawn = spawnOf(e)?.let { (room, rule) -> SpawnJson(room.v, rule) },
          level = delta(e.level, p.level),
          maxHp = delta(e.maxHp, p.hp),
          maxMana = delta(e.maxMana, p.mana),
          maxMove = delta(e.maxMove, p.move),
          armor = delta(e.armor, p.armor),
          stats = delta(e.baseStats.toMap(), p.stats),
          resists = delta(e.resists.mapKeys { it.key.name }, p.resists.mapKeys { it.key.name }),
          skills = delta(e.skills.mapKeys { it.key.v }, p.skills.mapKeys { it.key.v }),
          thinkEvery = delta(e.thinkEvery, p.thinkEvery),
          dormantRecovery = delta(e.dormantRecovery, p.dormantRecovery)?.name?.lowercase(),
          attrs = attrsJson(e.customAttributes),
          timers = timersJson(e),
          affects = affectsJson(e),
          active = e.activeReasons.toList(),
          anchor = e.anchorReasons.toList(),
        )
      EntityRow(
        e.id.v,
        "mob",
        e.protoLabel.v,
        e.protoVersion,
        null,
        JSON.encodeToString(MobPayload.serializer(), payload),
        pulse,
      )
    }
    is Item -> {
      if (e.debug) {
        null
      } else {
        val payload =
          ItemPayload(
            name = e.proto.name,
            count = e.count,
            decayAt = e.decayAt,
            nameOverride = e.nameOverride,
            containerState =
              e.container?.state?.takeIf { it != e.proto.container?.state }?.name?.lowercase(),
            attrs = attrsJson(e.customAttributes),
            timers = timersJson(e),
            tombstone = e.savedPayload,
            active = e.activeReasons.toList(),
            anchor = e.anchorReasons.toList(),
          )
        EntityRow(
          e.id.v,
          "item",
          e.protoLabel.v,
          e.protoVersion,
          JSON.encodeToString(LocationJson.serializer(), locationJson(e.location)),
          JSON.encodeToString(ItemPayload.serializer(), payload),
          pulse,
        )
      }
    }
    is Player -> null
  }

internal fun World.encodePlayer(p: Player): PlayerRow {
  val payload =
    PlayerPayload(
      room = p.room.label.v,
      index = p.trackIndex,
      recall = p.recallRoom.v,
      role = p.role.name,
      level = p.level,
      xp = p.xp,
      hp = p.hp,
      maxHp = p.maxHp,
      mana = p.mana,
      maxMana = p.maxMana,
      move = p.move,
      maxMove = p.maxMove,
      armor = p.armor,
      stats = p.baseStats.toMap(),
      resists = p.resists.mapKeys { it.key.name },
      skills = p.skills.mapKeys { it.key.v },
      attrs = attrsJson(p.customAttributes),
      timers = timersJson(p),
      affects = affectsJson(p),
      active = p.activeReasons.toList(),
      anchor = p.anchorReasons.toList(),
    )
  return PlayerRow(
    p.id.v,
    p.accountId,
    p.name,
    JSON.encodeToString(PlayerPayload.serializer(), payload),
    pulse,
  )
}

// ---- Overlaying a delta (SPEC §3.4 step 3) ------------------------------------------------------

internal fun applyRoom(room: Room, p: RoomPayload) {
  for ((dir, state) in p.doors) room.exits.values
    .firstOrNull { it.direction.name == dir }
    ?.door
    ?.state = DoorState.valueOf(state.uppercase())
  for ((dir, blocks) in p.blocks) {
    room.exits.values.firstOrNull { it.direction.name == dir }?.blocks =
      blocks.mapTo(LinkedHashSet()) { Channel.valueOf(it.uppercase()) }
  }
  room.customAttributes.clear()
  room.customAttributes.putAll(p.attrs.toAttrs())
  restoreTimers(room, p.timers)
  restoreReasons(room, p.active, p.anchor)
}

internal fun applyMob(m: Mob, p: MobPayload) {
  p.level?.let { m.level = it }
  p.maxHp?.let { m.maxHp = it }
  p.maxMana?.let { m.maxMana = it }
  p.maxMove?.let { m.maxMove = it }
  p.armor?.let { m.armor = it }
  p.stats?.let {
    m.baseStats.clear()
    m.baseStats.putAll(it)
  }
  p.resists?.let { r ->
    m.resists.clear()
    m.resists.putAll(r.mapKeys { DamageType(it.key) })
  }
  p.skills?.let { s ->
    m.skills.clear()
    m.skills.putAll(s.mapKeys { Label(it.key) })
  }
  p.thinkEvery?.let { m.thinkEvery = it }
  p.dormantRecovery?.let { m.dormantRecovery = DormantRecovery.valueOf(it.uppercase()) }
  p.home?.let { m.homeRoom = Label(it) }
  m.hp = p.hp.coerceAtMost(m.maxHp)
  m.mana = p.mana.coerceAtMost(m.maxMana)
  m.move = p.move.coerceAtMost(m.maxMove)
  m.xp = p.xp
  m.customAttributes.clear()
  m.customAttributes.putAll(p.attrs.toAttrs())
  restoreTimers(m, p.timers)
  restoreAffects(m, p.affects)
  restoreReasons(m, p.active, p.anchor)
}

internal fun applyItem(item: Item, p: ItemPayload) {
  p.decayAt?.let { item.decayAt = it }
  item.nameOverride = p.nameOverride
  p.containerState?.let { s -> item.container?.state = DoorState.valueOf(s.uppercase()) }
  item.customAttributes.clear()
  item.customAttributes.putAll(p.attrs.toAttrs())
  restoreTimers(item, p.timers)
  restoreReasons(item, p.active, p.anchor)
}

internal fun applyPlayer(pl: Player, p: PlayerPayload) {
  pl.role = Role.valueOf(p.role)
  pl.level = p.level
  pl.xp = p.xp
  pl.maxHp = p.maxHp
  pl.hp = p.hp.coerceAtMost(p.maxHp)
  pl.maxMana = p.maxMana
  pl.mana = p.mana.coerceAtMost(p.maxMana)
  pl.maxMove = p.maxMove
  pl.move = p.move.coerceAtMost(p.maxMove)
  pl.armor = p.armor
  pl.baseStats.clear()
  pl.baseStats.putAll(p.stats)
  pl.resists.clear()
  pl.resists.putAll(p.resists.mapKeys { DamageType(it.key) })
  pl.skills.clear()
  pl.skills.putAll(p.skills.mapKeys { Label(it.key) })
  pl.customAttributes.clear()
  pl.customAttributes.putAll(p.attrs.toAttrs())
  restoreTimers(pl, p.timers)
  restoreAffects(pl, p.affects)
  restoreReasons(pl, p.active, p.anchor)
}
