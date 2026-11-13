@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package gups.persist

import gups.core.AttrValue
import gups.core.EntityId
import gups.core.HookRef
import gups.core.Label
import gups.core.Role
import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.AffectScope
import gups.core.model.Channel
import gups.core.model.Condition
import gups.core.model.ContainerProto
import gups.core.model.DamageType
import gups.core.model.DoorState
import gups.core.model.DormantRecovery
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.LeashOverride
import gups.core.model.LevelGain
import gups.core.model.Mob
import gups.core.model.ModifierPoint
import gups.core.model.Player
import gups.core.model.Room
import gups.core.model.ScriptTimer
import gups.core.model.StandingChange
import gups.core.model.StandingRecord
import gups.core.world.VitalState
import gups.core.world.World
import gups.core.world.restoreActorNumbers
import gups.core.world.restoreOwner
import gups.core.world.spawnOf
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Engine entity serialization, JSON delta encoding, and schema versioning.
 *
 * Persisted entities use delta serialization against their prototypes to minimize database row
 * size: instances store only properties that differ from their prototype definitions. Players have
 * no prototype and persist complete state.
 *
 * ## Versioning & Format
 * - `FORMAT_VERSION`: Included in every payload JSON (`format_version`) to manage engine-side
 *   schema evolution. Field names use camelCase except for `format_version`.
 *
 * ## Item Custody & Location Invariant
 * - Item location is recorded exclusively on the item's own row in `world_entities` via
 *   [LocationJson].
 * - Player payloads and container entity payloads never list carried or contained item IDs;
 *   inventories and container contents are reconstructed dynamically on world load. This invariant
 *   guarantees that no partial checkpoint or uncommitted transfer can duplicate an item across
 *   multiple locations.
 *
 * ## Entity Delta Rules
 * - **Rooms ([RoomPayload]):** Persists only door states that differ from prototype defaults (by
 *   direction name), custom attributes, script timers, and active/anchor simulation reasons.
 * - **Mobs ([MobPayload]):** Always persists position (room label, track index), current vitals
 *   (`cur`, and `max` only when overridden, plus `belowZeroSince`), life condition, xp, home room,
 *   spawn binding, summonedBy, owner, and the leash override (stored when set, including an
 *   unleashed override that must not fall back to the prototype). Delta fields (level, stats,
 *   senses, resists, skills, `thinkEvery`, `dormantRecovery`, and `grantedTraits`) are omitted if
 *   they equal prototype defaults, each stored as a whole value rather than per key. Prototype
 *   traits are never stored; only runtime `grantedTraits` are. `levelGains` is stored only when not
 *   empty. Active/anchor reasons, custom attributes, timers, and active affects are also stored.
 * - **Items ([ItemPayload]):** Persists count, decay deadline pulse, display name override,
 *   container state (open, closed, locked), custom attributes, timers, active/anchor reasons,
 *   instance affects, added and removed tags, and the prototype's name, unit weight, stackability,
 *   plural and container settings.
 * - **Tombstones:** When an item's prototype is deleted or modified in course scripts, the item
 *   becomes a tombstone. The `tombstone` field retains the raw JSON payload the item held when its
 *   prototype vanished, preserving its state for inspection. A tombstone restored from storage is
 *   rebuilt with the saved weight, stackability, plural and container settings, so it weighs what
 *   it did and whatever it held is restored inside it.
 * - **Players ([PlayerPayload]):** Persists full state: room label, track index, recall room label,
 *   assigned role, level, xp, vitals, condition, base stats, base senses, base resists, skill
 *   proficiencies, creation choices, granted traits, `levelGains`, custom attributes, timers,
 *   affects, and active/anchor reasons. During restore, traits are evaluated before vitals so
 *   effective vital maximums correctly accommodate trait modifiers.
 * - **Zones ([ZonePayload]):** Persists zone reset deadlines, pending spawn respawn rules with
 *   deadlines, and package quarantine status.
 */
const val FORMAT_VERSION = 3

internal val JSON = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
}

/** A `world_entities` row. Item locations live only here. */
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

@Serializable data class LeashJson(val exits: Int? = null)

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
  /** Modifier functions by point name. */
  val modifiers: Map<String, RefJson> = emptyMap(),
  val senseDeltas: Map<String, Int> = emptyMap(),
  /** "worn" or "carried" for an item affect; null for an actor's own affects. */
  val applies: String? = null,
  /** Vital name to a change in its maximum. */
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  /** Damage type name to a change in resistance. */
  val resistDeltas: Map<String, Int> = emptyMap(),
  val procs: List<ProcJson> = emptyList(),
  val power: Int = 100,
  /** Tags a room affect adds while live; sorted. */
  val addTags: List<String> = emptyList(),
  /** Tags a room affect removes while live; sorted. */
  val removeTags: List<String> = emptyList(),
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

/**
 * A vital as saved. A mob stores [max] only where it differs from its prototype's declared maximum;
 * a player always stores it.
 */
@Serializable
data class VitalJson(val cur: Int, val max: Int? = null, val belowZeroSince: Long? = null)

/**
 * Stat and vital maximum deltas recorded for a single level step. [stats] and [max] are omitted
 * when empty.
 */
@Serializable
data class LevelGainJson(val stats: Map<String, Int>? = null, val max: Map<String, Int>? = null)

@Serializable
data class RoomPayload(
  @EncodeDefault @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
  /** Door states that differ from the prototype, by direction. */
  val doors: Map<String, String> = emptyMap(),
  val attrs: JsonObject = JsonObject(emptyMap()),
  val timers: List<TimerJson> = emptyList(),
  /** `set_active` and `set_anchor` reasons. */
  val active: List<String> = emptyList(),
  val anchor: List<String> = emptyList(),
  /** Timed enchantments on the room. */
  val affects: List<AffectJson> = emptyList(),
)

@Serializable
data class MobPayload(
  @EncodeDefault @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
  val room: String,
  val index: Int,
  val vitals: Map<String, VitalJson> = emptyMap(),
  val condition: String = "ok",
  val xp: Long = 0,
  val home: String? = null,
  val spawn: SpawnJson? = null,
  val summonedBy: Long? = null,
  val owner: Long? = null,
  // The delta: present only where the instance differs from its prototype.
  val level: Int? = null,
  val stats: Map<String, Int>? = null,
  val senses: Map<String, Int>? = null,
  val resists: Map<String, Int>? = null,
  val skills: Map<String, Int>? = null,
  val thinkEvery: Int? = null,
  val dormantRecovery: String? = null,
  val leash: LeashJson? = null,
  val grantedTraits: List<String>? = null,
  val levelGains: Map<Int, LevelGainJson>? = null,
  val attrs: JsonObject = JsonObject(emptyMap()),
  val timers: List<TimerJson> = emptyList(),
  val affects: List<AffectJson> = emptyList(),
  val active: List<String> = emptyList(),
  val anchor: List<String> = emptyList(),
  @SerialName("personal_standing") val personalStanding: Map<Long, StandingRecordJson>? = null,
)

@Serializable
data class StandingChangeJson(
  val pulse: Long,
  val amount: Int,
  val event: String,
  val via: Long? = null,
)

@Serializable
data class StandingRecordJson(
  val amount: Int,
  @SerialName("updated_pulse") val updatedPulse: Long,
  val history: List<StandingChangeJson> = emptyList(),
)

/** A container prototype's settings, kept so a tombstone can still hold what it held. */
@Serializable
data class ContainerJson(
  val capacity: Int,
  val maxWeight: Int,
  /** The initial state: "open", "closed" or "locked". */
  val state: String,
  val key: String? = null,
)

@Serializable
data class ItemPayload(
  @EncodeDefault @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
  /** Kept so a tombstone can show what the item was. */
  val name: String,
  val count: Int = 1,
  val charges: Int? = null,
  val decayAt: Long? = null,
  val nameOverride: String? = null,
  val containerState: String? = null,
  val attrs: JsonObject = JsonObject(emptyMap()),
  val timers: List<TimerJson> = emptyList(),
  /** For a tombstone: the payload the item had when its prototype vanished. */
  val tombstone: String? = null,
  val active: List<String> = emptyList(),
  val anchor: List<String> = emptyList(),
  /** Instance affects on this item, with their applies scope. */
  val affects: List<AffectJson> = emptyList(),
  /** Tags this instance has gained over its prototype's. */
  val addedTags: List<String> = emptyList(),
  /** Prototype tags this instance has lost. */
  val removedTags: List<String> = emptyList(),
  /** The prototype's container settings, if it is a container. */
  val container: ContainerJson? = null,
  /** The prototype's unit weight, kept so a tombstone weighs what the item did. */
  val weight: Int = 1,
  /** Whether the prototype stacks, kept so a tombstone stack still merges and splits. */
  val stackable: Boolean = false,
  /** The prototype's plural name, kept so a tombstone stack still reads the same. */
  val plural: String? = null,
)

@Serializable
data class PlayerPayload(
  @EncodeDefault @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
  val room: String,
  val index: Int,
  val recall: String,
  val role: String = Role.PLAYER.name,
  val level: Int,
  val xp: Long,
  val vitals: Map<String, VitalJson>,
  val condition: String = "ok",
  val stats: Map<String, Int>,
  /** Base senses by channel name; a payload without them keeps the built-in defaults. */
  val senses: Map<String, Int> = emptyMap(),
  val resists: Map<String, Int> = emptyMap(),
  val skills: Map<String, Int> = emptyMap(),
  val choices: Map<String, String> = emptyMap(),
  val grantedTraits: List<String> = emptyList(),
  val levelGains: Map<Int, LevelGainJson> = emptyMap(),
  val attrs: JsonObject = JsonObject(emptyMap()),
  val timers: List<TimerJson> = emptyList(),
  val affects: List<AffectJson> = emptyList(),
  val active: List<String> = emptyList(),
  val anchor: List<String> = emptyList(),
)

@Serializable data class RespawnJson(val room: String, val rule: Int, val at: Long)

/** A `zone_states` payload: reset and respawn deadlines, and quarantine. */
@Serializable
data class ZonePayload(
  @EncodeDefault @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
  val resetAt: Long? = null,
  val quarantined: Boolean = false,
  val respawns: List<RespawnJson> = emptyList(),
)

// ---- Attribute values
// ----------------------------------------------------------------------------

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

internal fun HookRef.json() = RefJson(file.v, name)

internal fun RefJson.ref() = HookRef(Label(file), name)

private fun LevelGain.toJson(): LevelGainJson =
  LevelGainJson(stats = stats.takeIf { it.isNotEmpty() }, max = max.takeIf { it.isNotEmpty() })

private fun LevelGainJson.toLevelGain(): LevelGain =
  LevelGain(stats = stats ?: emptyMap(), max = max ?: emptyMap())

internal fun timersJson(e: Entity) =
  e.timers.map { TimerJson(it.id, it.fn.json(), attrsJson(it.args), it.due, it.remaining) }

internal fun restoreTimers(e: Entity, timers: List<TimerJson>) {
  e.timers.clear()
  for (t in timers) e.timers += ScriptTimer(t.id, t.fn.ref(), t.args.toAttrs(), t.due, t.remaining)
}

internal fun affectJson(a: Affect): AffectJson =
  AffectJson(
    a.id,
    a.name,
    a.source?.v,
    a.appliedAt,
    a.expiresAt,
    a.statDeltas,
    a.flags.sorted(),
    a.tickEvery,
    a.onTick?.json(),
    a.onExpire?.json(),
    a.expireMessage,
    a.modifiers.entries.associate { (k, v) -> k.scriptName to v.json() },
    a.senseDeltas.entries.associate { (k, v) -> k.name to v },
    a.applies?.name?.lowercase(),
    maxVitalDeltas = a.maxVitalDeltas,
    resistDeltas = a.resistDeltas.entries.associate { (k, v) -> k.name to v },
    procs = a.procs.map { it.json() },
    power = a.power,
    addTags = a.addTags.sorted(),
    removeTags = a.removeTags.sorted(),
  )

internal fun affectsJson(a: Actor) =
  // Worn items' affects are rebuilt when the item is restored, not saved.
  a.affects.filter { it.wornFrom == null }.map(::affectJson)

internal fun affectFromJson(x: AffectJson): Affect =
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
    senseDeltas = x.senseDeltas.mapKeys { Channel(it.key) },
    applies = x.applies?.let { AffectScope.valueOf(it.uppercase()) },
    maxVitalDeltas = x.maxVitalDeltas,
    resistDeltas = x.resistDeltas.mapKeys { DamageType(it.key) },
    procs = x.procs.map { it.toProc() },
    power = x.power,
    addTags = x.addTags.toSet(),
    removeTags = x.removeTags.toSet(),
  )

internal fun restoreReasons(e: Entity, active: List<String>, anchor: List<String>) {
  e.activeReasons.clear()
  e.activeReasons.addAll(active)
  e.anchorReasons.clear()
  e.anchorReasons.addAll(anchor)
}

/** The live form of saved affects; actors and rooms restore through it. */
internal fun restoreAffects(affects: List<AffectJson>): List<Affect> = affects.map(::affectFromJson)

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
          attrs = attrsJson(e.customAttributes),
          timers = timersJson(e),
          active = e.activeReasons.toList(),
          anchor = e.anchorReasons.toList(),
          affects = e.affects.map(::affectJson),
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
          vitals =
            e.vitals.mapValues { (name, v) ->
              VitalJson(v.current, delta(v.max, p.vitals[name] ?: 0), v.belowZeroSince)
            },
          condition = e.condition.word,
          xp = e.xp,
          home = e.homeRoom.v.takeIf { e.homeRoom != e.room.label },
          spawn = spawnOf(e)?.let { (room, rule) -> SpawnJson(room.v, rule) },
          summonedBy = e.summonedBy?.v,
          owner = e.owner?.v,
          level = delta(e.level, p.level),
          stats = delta(e.baseStats.toMap(), p.stats),
          senses =
            delta(
              e.baseSenses.mapKeys { it.key.name },
              (Channel.BUILTIN_SENSES + p.senses).mapKeys { it.key.name },
            ),
          resists = delta(e.resists.mapKeys { it.key.name }, p.resists.mapKeys { it.key.name }),
          skills = delta(e.skills.mapKeys { it.key.v }, p.skills.mapKeys { it.key.v }),
          thinkEvery = delta(e.thinkEvery, p.thinkEvery),
          dormantRecovery = delta(e.dormantRecovery, p.dormantRecovery)?.name?.lowercase(),
          leash = e.leashOverride?.let { LeashJson(it.exits) },
          grantedTraits = e.grantedTraits.toList().sorted().takeIf { it.isNotEmpty() },
          levelGains =
            e.levelGains.toSortedMap().mapValues { it.value.toJson() }.takeIf { it.isNotEmpty() },
          attrs = attrsJson(e.customAttributes),
          timers = timersJson(e),
          affects = affectsJson(e),
          active = e.activeReasons.toList(),
          anchor = e.anchorReasons.toList(),
          personalStanding =
            e.personalStanding
              .takeIf { it.isNotEmpty() }
              ?.map { (k, v) ->
                k.v to
                  StandingRecordJson(
                    v.amount,
                    v.updatedPulse,
                    v.history.map { StandingChangeJson(it.pulse, it.amount, it.event, it.via?.v) },
                  )
              }
              ?.toMap(),
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
            charges = e.charges,
            decayAt = e.decayAt,
            nameOverride = e.nameOverride,
            containerState =
              e.container?.state?.takeIf { it != e.proto.container?.state }?.name?.lowercase(),
            attrs = attrsJson(e.customAttributes),
            timers = timersJson(e),
            tombstone = e.savedPayload,
            active = e.activeReasons.toList(),
            anchor = e.anchorReasons.toList(),
            affects = e.affects.map(::affectJson),
            addedTags = e.addedTags.toList(),
            removedTags = e.removedTags.toList(),
            container = e.proto.container?.let(::containerJson),
            weight = e.proto.weight,
            stackable = e.proto.stackable,
            plural = e.proto.plural,
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
      vitals = p.vitals.mapValues { (_, v) -> VitalJson(v.current, v.max, v.belowZeroSince) },
      condition = p.condition.word,
      stats = p.baseStats.toMap(),
      senses = p.baseSenses.mapKeys { it.key.name },
      resists = p.resists.mapKeys { it.key.name },
      skills = p.skills.mapKeys { it.key.v },
      choices = p.choices.toMap(),
      grantedTraits = p.grantedTraits.toList().sorted(),
      levelGains = p.levelGains.toSortedMap().mapValues { it.value.toJson() },
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

// ---- Overlaying a delta
// ---------------------------------------------------------------------------

/** A saved lock stays only if the prototype still has a key for it. */
private fun restoredState(saved: String, key: Label?): DoorState =
  DoorState.valueOf(saved.uppercase()).let {
    if (it == DoorState.LOCKED && key == null) DoorState.CLOSED else it
  }

internal fun applyRoom(room: Room, p: RoomPayload) {
  for ((dir, state) in p.doors) room.exits.values
    .firstOrNull { it.direction.name == dir }
    ?.door
    ?.let { it.state = restoredState(state, it.key) }
  room.customAttributes.clear()
  room.customAttributes.putAll(p.attrs.toAttrs())
  restoreTimers(room, p.timers)
  restoreReasons(room, p.active, p.anchor)
  room.affects.clear()
  room.affects += restoreAffects(p.affects)
}

internal fun applyMob(world: World, m: Mob, p: MobPayload) {
  p.level?.let { m.level = it }
  p.senses?.let { s ->
    m.baseSenses.clear()
    m.baseSenses.putAll(s.mapKeys { Channel(it.key) })
  }
  p.skills?.let { s ->
    m.skills.clear()
    m.skills.putAll(s.mapKeys { Label(it.key) })
  }
  p.thinkEvery?.let { m.thinkEvery = it }
  p.dormantRecovery?.let { m.dormantRecovery = DormantRecovery.valueOf(it.uppercase()) }
  m.leashOverride = p.leash?.let { LeashOverride(it.exits) }
  p.home?.let { m.homeRoom = Label(it) }
  m.summonedBy = p.summonedBy?.let(::EntityId)
  m.grantedTraits = p.grantedTraits?.toSet() ?: emptySet()
  m.recomputeTraits(world)
  m.levelGains.clear()
  p.levelGains?.let { gains -> m.levelGains.putAll(gains.mapValues { it.value.toLevelGain() }) }
  world.restoreActorNumbers(
    m,
    stats = p.stats ?: m.baseStats.toMap(),
    vitals =
      p.vitals.mapValues { (name, v) ->
        VitalState(v.cur, v.max ?: m.baseMax(name), v.belowZeroSince)
      },
    resists = p.resists?.mapKeys { DamageType(it.key) } ?: m.resists.toMap(),
    condition = Condition.parse(p.condition) ?: Condition.OK,
  )
  m.xp = p.xp
  m.customAttributes.clear()
  m.customAttributes.putAll(p.attrs.toAttrs())
  restoreTimers(m, p.timers)
  m.affects.removeIf { it.wornFrom == null }
  m.affects += restoreAffects(p.affects)
  restoreReasons(m, p.active, p.anchor)
  world.restoreOwner(m, p.owner?.let(::EntityId))
  m.personalStanding.clear()
  p.personalStanding?.forEach { (id, r) ->
    val record = StandingRecord()
    record.amount = r.amount
    record.updatedPulse = r.updatedPulse
    r.history.forEach { h ->
      record.history.add(StandingChange(h.pulse, h.amount, h.event, h.via?.let(::EntityId)))
    }
    m.personalStanding[EntityId(id)] = record
  }
}

internal fun containerJson(c: ContainerProto) =
  ContainerJson(c.capacity, c.maxWeight, c.state.name.lowercase(), c.key?.v)

internal fun containerFromJson(c: ContainerJson) =
  ContainerProto(
    c.capacity,
    c.maxWeight,
    DoorState.valueOf(c.state.uppercase()),
    c.key?.let(::Label),
  )

internal fun applyItem(item: Item, p: ItemPayload) {
  val max = item.proto.charges
  // The prototype may have changed since the save, so a restore follows the same rules as a reload.
  item.charges =
    when {
      max == null -> null
      item.proto.stackable -> max
      else -> minOf(p.charges ?: max, max)
    }
  p.decayAt?.let { item.decayAt = it }
  item.nameOverride = p.nameOverride
  p.containerState?.let { s -> item.container?.let { it.state = restoredState(s, it.key) } }
  item.customAttributes.clear()
  item.customAttributes.putAll(p.attrs.toAttrs())
  restoreTimers(item, p.timers)
  restoreReasons(item, p.active, p.anchor)
  item.affects.clear()
  item.affects += p.affects.map(::affectFromJson)
  item.addedTags.clear()
  item.addedTags += p.addedTags
  item.removedTags.clear()
  item.removedTags += p.removedTags
}

internal fun applyPlayer(world: World, pl: Player, p: PlayerPayload) {
  pl.role = Role.valueOf(p.role)
  pl.level = p.level
  pl.xp = p.xp
  pl.choices.clear()
  pl.choices.putAll(p.choices)
  pl.grantedTraits = p.grantedTraits.toSet()
  pl.recomputeTraits(world)
  pl.baseSenses.putAll(p.senses.mapKeys { Channel(it.key) })
  world.restoreActorNumbers(
    pl,
    stats = p.stats,
    vitals = p.vitals.mapValues { (_, v) -> VitalState(v.cur, v.max ?: v.cur, v.belowZeroSince) },
    resists = p.resists.mapKeys { DamageType(it.key) },
    condition = Condition.parse(p.condition) ?: Condition.OK,
  )
  pl.skills.clear()
  pl.skills.putAll(p.skills.mapKeys { Label(it.key) })
  pl.levelGains.clear()
  pl.levelGains.putAll(p.levelGains.mapValues { it.value.toLevelGain() })
  pl.customAttributes.clear()
  pl.customAttributes.putAll(p.attrs.toAttrs())
  restoreTimers(pl, p.timers)
  pl.affects.removeIf { it.wornFrom == null }
  pl.affects += restoreAffects(p.affects)
  restoreReasons(pl, p.active, p.anchor)
}
