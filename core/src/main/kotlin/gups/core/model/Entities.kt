package gups.core.model

import gups.core.AttrValue
import gups.core.EntityId
import gups.core.HookRef
import gups.core.Label
import gups.core.Role
import gups.core.SessionId
import gups.core.world.Action
import gups.core.world.SpawnState
import gups.core.world.TickCommand
import java.util.TreeMap

// Live entities. Static data is read through `proto`; everything mutable lives
// on the instance. Only the world mutates these, on the world thread.

sealed class Entity(val id: EntityId, val protoLabel: Label) {
  var protoVersion: Int = 1
  val customAttributes: MutableMap<String, AttrValue> = linkedMapOf()
  val timers: MutableList<ScriptTimer> = mutableListOf()
  val activeReasons: MutableSet<String> = linkedSetOf()
  val anchorReasons: MutableSet<String> = linkedSetOf()
  var dirty: Boolean = true

  /** Spawned by a builder's `@spawn`, or dropped by such a mob: never persisted. */
  var debug: Boolean = false

  abstract val name: String

  override fun toString(): String = "$name$id"
}

class Door(proto: DoorProto) {
  var state: DoorState = proto.state
  val key: Label? = proto.key
  val tags: Set<String> = proto.tags
}

class Exit(proto: ExitProto) {
  val direction: Direction = proto.direction
  val to: Label = proto.to
  val entryIndex: Int = proto.entryIndex
  val tags: Set<String> = proto.tags
  val door: Door? = proto.door?.let(::Door)
  val oneWay: Boolean = proto.oneWay

  /** Closed and locked doors cannot be walked through. */
  val passable: Boolean
    get() = door == null || door.state == DoorState.OPEN
}

class Room(id: EntityId, proto: RoomProto, private val singleSlot: Boolean = false) :
  Entity(id, proto.label) {
  /** The pulse the room went dormant at, or null while it is simulated. */
  var dormantSince: Long? = null
    internal set

  /** A zone reset passed while the room was dormant; applied when it wakes. */
  internal var resetMissed = false

  val isDormant: Boolean
    get() = dormantSince != null

  /** Replaced by a reload. */
  var proto: RoomProto = proto
    internal set

  init {
    protoVersion = proto.version
  }

  override val name: String
    get() = proto.name

  val label: Label
    get() = proto.label

  /** The track's slots; a world without the track feature has one slot in every room. */
  val trackSize: Int
    get() = if (singleSlot) 1 else proto.size.slots

  val regions: Set<String>
    get() = proto.regions

  val tags: Set<String>
    get() = proto.tags

  var exits: Map<Direction, Exit> =
    proto.exits.associateTo(LinkedHashMap()) { it.direction to Exit(it) }
    internal set

  internal val actorIndex = TreeMap<EntityId, Actor>()
  internal val itemIndex = TreeMap<EntityId, Item>()

  /** Actors here, ascending [EntityId]. */
  val actors: List<Actor>
    get() = actorIndex.values.toList()

  /** Items on the ground here, ascending [EntityId]. */
  val items: List<Item>
    get() = itemIndex.values.toList()

  fun hasFlag(flag: String): Boolean = flag in proto.flags
}

/** A timed buff or debuff. */
class Affect(
  val id: Long,
  val name: String,
  val source: EntityId?,
  val appliedAt: Long,
  val expiresAt: Long,
  val statDeltas: Map<String, Int> = emptyMap(),
  val flags: Set<String> = emptySet(),
  val tickEvery: Int? = null,
  val onTick: HookRef? = null,
  val onExpire: HookRef? = null,
  val expireMessage: String = "",
  /** Modifiers that apply to the host while the affect lasts. */
  val modifiers: Modifiers = emptyMap(),
  /** The worn item this lasts as long as, or null for a timed affect. */
  val wornFrom: EntityId? = null,
  val senseDeltas: Map<Channel, Int> = emptyMap(),
)

/** A pending script callback. Exactly one of [due] and [remaining] is set. */
class ScriptTimer(
  val id: Long,
  val fn: HookRef,
  val args: Map<String, AttrValue>,
  var due: Long?,
  var remaining: Int? = null,
)

/** An action in its warmup. */
class CastState(val action: Action, val startedAt: Long, val endsAt: Long)

/** A tick-lane command waiting in an actor's queue. Parsed into an [Action] when popped. */
class QueuedAction(val command: TickCommand, val verb: String, val args: String) {
  override fun toString(): String = "$verb $args".trim()
}

sealed class Actor(id: EntityId, protoLabel: Label, room: Room, trackIndex: Int) :
  Entity(id, protoLabel) {
  abstract val keywords: List<String>
  abstract val isPlayer: Boolean

  var room: Room = room
    internal set

  var trackIndex: Int = trackIndex
    internal set

  /** One entry per stat the actor has a value for; a declared stat without one reads as 0. */
  val baseStats: MutableMap<String, Int> = LinkedHashMap()
  internal val vitalMap: MutableMap<String, Vital> = LinkedHashMap()

  /** The actor's vitals, in schema order. Changed only through the world. */
  val vitals: Map<String, Vital>
    get() = vitalMap

  /** As `rules.condition` last decided it; changed only through the world. */
  var condition: Condition = Condition.OK
    internal set

  var level: Int = 1
  var xp: Long = 0
  val resists: MutableMap<DamageType, Int> = linkedMapOf()
  val skills: MutableMap<Label, Int> = linkedMapOf()
  val affects: MutableList<Affect> = mutableListOf()

  var grantedTraits: Set<String> = emptySet()
  var traits: List<Trait> = emptyList()

  /** Base sense per channel; the built-in channels default to 1, custom ones to 0. */
  val baseSenses: MutableMap<Channel, Int> = LinkedHashMap(Channel.BUILTIN_SENSES)

  var traitSenseDeltas: Map<Channel, Int> = emptyMap()
    internal set

  var traitStatDeltas: Map<String, Int> = emptyMap()
    internal set

  var traitMaxVitalDeltas: Map<String, Int> = emptyMap()
    internal set

  var traitResistDeltas: Map<String, Int> = emptyMap()
    internal set

  var traitFlags: Set<String> = emptySet()
    internal set

  fun recomputeTraits(world: gups.core.world.World) {
    val traitList = mutableListOf<Trait>()
    if (this is Player) {
      world.creation?.choices?.forEach { choice ->
        val optId = choices[choice.id]
        val opt = choice.options.firstOrNull { it.id == optId }
        opt?.traits?.forEach { tName -> world.traits[tName]?.let { traitList.add(it) } }
      }
    } else if (this is Mob) {
      proto.traits.forEach { tName -> world.traits[tName]?.let { traitList.add(it) } }
    }
    grantedTraits.forEach { tName -> world.traits[tName]?.let { traitList.add(it) } }
    traits = traitList.distinctBy { it.name }.sortedBy { it.name }

    val statDeltas = mutableMapOf<String, Int>()
    val senseDeltas = mutableMapOf<Channel, Int>()
    val maxVitalDeltas = mutableMapOf<String, Int>()
    val resistDeltas = mutableMapOf<String, Int>()
    val flags = mutableSetOf<String>()

    for (t in traits) {
      for ((s, d) in t.statDeltas) statDeltas[s] = (statDeltas[s] ?: 0) + d
      for ((c, d) in t.senseDeltas) senseDeltas[c] = (senseDeltas[c] ?: 0) + d
      for ((v, d) in t.maxVitalDeltas) maxVitalDeltas[v] = (maxVitalDeltas[v] ?: 0) + d
      for ((r, d) in t.resistDeltas) resistDeltas[r] = (resistDeltas[r] ?: 0) + d
      flags.addAll(t.flags)
    }

    traitStatDeltas = statDeltas
    traitSenseDeltas = senseDeltas
    traitMaxVitalDeltas = maxVitalDeltas
    traitResistDeltas = resistDeltas
    traitFlags = flags

    for ((name, v) in vitalMap) {
      val effMax = max(name)
      if (v.current > effMax) v.current = effMax
    }
  }

  val actionQueue: ArrayDeque<QueuedAction> = ArrayDeque()
  var waitState: Int = 0

  /** Recovery held aside while the actor is stunned or incapacitated, restored when that ends. */
  internal var heldWaitState: Int = 0

  /** The pulse the current disability started, so a paused warmup can be pushed back. */
  internal var heldSince: Long = 0

  var casting: CastState? = null
  val fighting: java.util.TreeSet<EntityId> = java.util.TreeSet()
  var primaryTarget: EntityId? = null

  /** This round's initiative, or null if the actor was not engaged when it was rolled. */
  var initiative: Int? = null
    internal set

  /** Who last damaged this actor; receives the kill. */
  var lastAttacker: EntityId? = null
    internal set

  /** False once the actor has left the world (death of a mob, quit). */
  var inWorld: Boolean = false
    internal set

  internal val inventoryIndex = TreeMap<EntityId, Item>()
  internal val equipmentIndex = LinkedHashMap<String, Item>()

  val inventory: List<Item>
    get() = inventoryIndex.values.toList()

  val equipment: Map<String, Item>
    get() = LinkedHashMap(equipmentIndex)

  fun equipped(slot: String): Item? = equipmentIndex[slot]

  /**
   * Stat deltas from `rules.encumbrance` while carrying more than `rules.carry_limit`; kept by the
   * world.
   */
  var encumbrance: Map<String, Int> = emptyMap()
    internal set

  /** Effective attribute: base plus trait, affect, equipment and encumbrance deltas. */
  fun stat(name: String): Int =
    (baseStats[name] ?: 0) +
      (traitStatDeltas[name] ?: 0) +
      affects.sumOf { it.statDeltas[name] ?: 0 } +
      equipmentIndex.values.sumOf { it.proto.statDeltas[name] ?: 0 } +
      (encumbrance[name] ?: 0)

  fun baseStat(name: String): Int = baseStats[name] ?: 0

  /** Effective sense on [channel]: base plus trait, affect and equipment deltas, never below 0. */
  fun sense(channel: Channel): Int =
    ((baseSenses[channel] ?: 0) +
        (traitSenseDeltas[channel] ?: 0) +
        affects.sumOf { it.senseDeltas[channel] ?: 0 } +
        equipmentIndex.values.sumOf { it.proto.senseDeltas[channel] ?: 0 })
      .coerceAtLeast(0)

  fun effectiveSenses(allChannels: Collection<Channel>): Map<Channel, Int> =
    allChannels.associateWith { sense(it) }

  fun current(vital: String): Int = vitalMap[vital]?.current ?: 0

  fun max(vital: String): Int =
    maxOf(0, (vitalMap[vital]?.max ?: 0) + (traitMaxVitalDeltas[vital] ?: 0))

  fun baseMax(vital: String): Int = vitalMap[vital]?.max ?: 0

  fun effectiveResist(type: String): Int =
    (resists[DamageType(type)] ?: 0) + (traitResistDeltas[type] ?: 0)

  fun effectiveResist(type: DamageType): Int =
    (resists[type] ?: 0) + (traitResistDeltas[type.name] ?: 0)

  fun resist(type: String): Int = effectiveResist(type)

  fun resist(type: DamageType): Int = effectiveResist(type)

  fun baseResist(type: String): Int = resists[DamageType(type)] ?: 0

  fun baseResist(type: DamageType): Int = resists[type] ?: 0

  fun hasTrait(name: String): Boolean = traits.any { it.name == name }

  /** All active flags on this actor from traits and affects. */
  val flags: Set<String>
    get() = traitFlags + affects.flatMap { it.flags }

  /** True if a trait or an affect carries [flag], including those applied by worn items. */
  fun hasFlag(flag: String): Boolean = flag in traitFlags || affects.any { flag in it.flags }

  val isStunned: Boolean
    get() = hasFlag("stunned")

  val isIncapacitated: Boolean
    get() = condition == Condition.INCAPACITATED

  /** Stunned or incapacitated: takes no actions and holds its recovery. */
  val isDisabled: Boolean
    get() = isStunned || isIncapacitated

  val isEngaged: Boolean
    get() = fighting.isNotEmpty()

  val isCasting: Boolean
    get() = casting != null

  val isIdle: Boolean
    get() = casting == null && waitState == 0 && actionQueue.isEmpty()

  fun matches(keyword: String): Boolean {
    val k = keyword.lowercase()
    return keywords.any { it.lowercase().startsWith(k) }
  }
}

class Player(
  id: EntityId,
  override val name: String,
  room: Room,
  trackIndex: Int,
  var recallRoom: Label,
  /** The owning account; null for characters without one. */
  var accountId: Long? = null,
) : Actor(id, Prototypes.PLAYER, room, trackIndex) {
  override val keywords: List<String> = listOf(name.lowercase())
  override val isPlayer: Boolean
    get() = true

  /** Null while link-dead. */
  var session: SessionId? = null
    internal set

  var linkDeadSince: Long? = null
    internal set

  var lastInputPulse: Long = 0
    internal set

  /** Instructors are exempt from the idle auto-quit. */
  var idleExempt: Boolean = false

  /** Player's character creation choices: choice name -> option id. */
  val choices: MutableMap<String, String> = LinkedHashMap()

  /** The account's role, which gates builder and instructor commands. */
  var role: Role = Role.PLAYER

  /** The packages a builder may work on; ignored for instructors and admins. */
  var scope: Set<String> = emptySet()

  /**
   * True if this player may use builder tools on package [pkg]: an instructor, or a builder whose
   * scope has it.
   */
  fun owns(pkg: String): Boolean =
    role.atLeast(Role.INSTRUCTOR) || (role.atLeast(Role.BUILDER) && pkg in scope)
}

class Mob(id: EntityId, var proto: MobProto, room: Room, trackIndex: Int) :
  Actor(id, proto.label, room, trackIndex) {
  init {
    protoVersion = proto.version
  }

  override val name: String
    get() = proto.name

  override val keywords: List<String>
    get() = proto.keywords

  override val isPlayer: Boolean
    get() = false

  var thinkEvery: Int = proto.thinkEvery
  var lastThink: Long = Long.MIN_VALUE / 2
    internal set

  /** Where it was spawned; restored from a checkpoint. */
  var homeRoom: Label = room.label
  var dormantRecovery: DormantRecovery = proto.dormantRecovery

  /** Whom a `follow` behavior is following, once it has seen a match. */
  var leader: EntityId? = null
    internal set

  internal var spawnedBy: SpawnState? = null

  /** Who summoned it, or null if it wasn't summoned. It gives no XP or loot. */
  var summonedBy: EntityId? = null

  /** For a debug mob: the player who spawned it, and the pulse it despawns at. */
  var debugOwner: EntityId? = null
    internal set

  var debugUntil: Long? = null
    internal set
}

sealed interface ItemLocation {
  data class InRoom(val room: EntityId, val trackIndex: Int) : ItemLocation

  data class Carried(val actor: EntityId) : ItemLocation

  data class Equipped(val actor: EntityId, val slot: String) : ItemLocation

  data class Contained(val container: EntityId) : ItemLocation
}

class ContainerState(proto: ContainerProto) {
  val capacity: Int = proto.capacity
  val maxWeight: Int = proto.maxWeight
  var state: DoorState = proto.state
  val key: Label? = proto.key
}

class Item(id: EntityId, var proto: ItemProto, location: ItemLocation, var count: Int = 1) :
  Entity(id, proto.label) {
  init {
    protoVersion = proto.version
  }

  /** Changed only through the world's item verbs, which keep every index consistent. */
  var location: ItemLocation = location
    internal set

  var nameOverride: String? = null
  override val name: String
    get() = nameOverride ?: proto.name

  val keywords: List<String>
    get() = proto.keywords

  val kind: String
    get() = proto.kind

  val tags: Set<String>
    get() = proto.tags

  val range: Band
    get() = proto.range

  val weight: Int
    get() = proto.weight * count + contentsIndex.values.sumOf { it.weight }

  val container: ContainerState? = proto.container?.let(::ContainerState)
  var decayAt: Long? = null

  /** For a tombstone: the payload the item was last saved with, kept as is. */
  var savedPayload: String? = null

  internal val contentsIndex = TreeMap<EntityId, Item>()
  val contents: List<Item>
    get() = contentsIndex.values.toList()

  fun matches(keyword: String): Boolean {
    val k = keyword.lowercase()
    return keywords.any { it.lowercase().startsWith(k) }
  }
}
