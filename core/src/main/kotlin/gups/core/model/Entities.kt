package gups.core.model

import gups.core.AttrValue
import gups.core.EntityId
import gups.core.HookRef
import gups.core.Label
import gups.core.Role
import gups.core.SessionId
import gups.core.world.Action
import gups.core.world.HeldEffects
import gups.core.world.SpawnState
import gups.core.world.TickCommand
import java.util.TreeMap

// Live entities. Static data is read through `proto`; everything mutable lives
// on the instance. Only the world mutates these, on the world thread.

/** Articles ignored when deriving or matching item keywords. */
val KEYWORD_ARTICLES: Set<String> = setOf("a", "an", "the", "some")

private val WORD_SPLIT = Regex("[^a-z0-9]+")

/** The lowercase words of [text], minus articles. */
fun keywordWords(text: String): List<String> = allWords(text).filter { it !in KEYWORD_ARTICLES }

private fun allWords(text: String): List<String> =
  text.lowercase().split(WORD_SPLIT).filter { it.isNotEmpty() }

/**
 * Base class for all live simulation entities.
 *
 * All state mutations must occur strictly on the authoritative world simulation thread. Static
 * metadata is read from the prototype; mutable runtime state is tracked on the instance.
 */
sealed class Entity(
  /** Unique entity identifier within the world simulation. */
  val id: EntityId,
  /** Canonical prototype label from which this entity was instantiated. */
  val protoLabel: Label,
) {
  /** Schema version of the prototype at the time this entity was created or migrated. */
  var protoVersion: Int = 1

  /** Arbitrary script-defined custom attributes stored on this entity. */
  val customAttributes: MutableMap<String, AttrValue> = linkedMapOf()

  /** Active script timers attached to this entity. */
  val timers: MutableList<ScriptTimer> = mutableListOf()

  /** Ref-counted reason strings keeping this entity actively simulated. */
  val activeReasons: MutableSet<String> = linkedSetOf()

  /** Ref-counted reason strings anchoring this entity's location in the simulation bubble. */
  val anchorReasons: MutableSet<String> = linkedSetOf()

  /** Flag indicating whether this entity has modified state requiring persistence capture. */
  var dirty: Boolean = true

  /**
   * True if spawned temporarily via builder debugging commands; debug entities are never persisted.
   */
  var debug: Boolean = false

  /** Display name of the entity. */
  abstract val name: String

  override fun toString(): String = "$name$id"
}

/** Live physical door instance blocking an exit or container opening. */
class Door(proto: DoorProto) {
  /**
   * Current physical state (open, closed, or locked); closed and locked doors cannot be walked
   * through.
   */
  var state: DoorState = proto.state

  /** Prototype label of the key item that locks or unlocks this door, or null if unkeyed. */
  val key: Label? = proto.key

  /** Semantic tags copied from the door prototype. */
  val tags: Set<String> = proto.tags
}

/** Live outgoing exit connecting one room to another. */
class Exit(proto: ExitProto) {
  /** Direction or transition keyword used to traverse this exit. */
  val direction: Direction = proto.direction

  /** Destination room prototype label. */
  val to: Label = proto.to

  /** Slot index on the destination room track where arriving actors land. */
  val entryIndex: Int = proto.entryIndex

  /** Semantic tags copied from the exit prototype. */
  val tags: Set<String> = proto.tags

  /** Physical door blocking this exit, or null if open passage. */
  val door: Door? = proto.door?.let(::Door)

  /** True if this exit cannot be traversed in reverse. */
  val oneWay: Boolean = proto.oneWay

  /** True if this exit can currently be traversed (no door, or door is open). */
  val passable: Boolean
    get() = door == null || door.state == DoorState.OPEN
}

/** Live simulated room entity. */
class Room(id: EntityId, proto: RoomProto, private val singleSlot: Boolean = false) :
  Entity(id, proto.label) {
  /** Monotonic pulse when the room entered a dormant state, or null while actively simulated. */
  var dormantSince: Long? = null
    internal set

  /** A zone reset passed while the room was dormant; applied when it wakes. */
  internal var resetMissed = false

  /** True if this room is currently outside the reality bubble and dormant. */
  val isDormant: Boolean
    get() = dormantSince != null

  /** Active room prototype definition, hot-swapped during live reloads. */
  var proto: RoomProto = proto
    internal set

  init {
    protoVersion = proto.version
  }

  /** Display name of the room. */
  override val name: String
    get() = proto.name

  /** Canonical prototype label of the room. */
  val label: Label
    get() = proto.label

  /** Number of spatial slots available on the room track (always 1 if the track feature is off). */
  val trackSize: Int
    get() = if (singleSlot) 1 else proto.size.slots

  /** Region names assigned to this room. */
  val regions: Set<String>
    get() = proto.regions

  /** Semantic tags assigned to this room. */
  val tags: Set<String>
    get() = proto.tags

  /** Outgoing exits from this room indexed by direction. */
  var exits: Map<Direction, Exit> =
    proto.exits.associateTo(LinkedHashMap()) { it.direction to Exit(it) }
    internal set

  internal val actorIndex = TreeMap<EntityId, Actor>()
  internal val itemIndex = TreeMap<EntityId, Item>()

  /** All live actors currently inside this room, ordered by ascending entity ID. */
  val actors: List<Actor>
    get() = actorIndex.values.toList()

  /** All loose items lying on the ground in this room, ordered by ascending entity ID. */
  val items: List<Item>
    get() = itemIndex.values.toList()

  /** True if this room's prototype declares the specified behavioral [flag]. */
  fun hasFlag(flag: String): Boolean = flag in proto.flags
}

/** Where an item affect reaches its holder from. */
enum class AffectScope {
  WORN,
  CARRIED,
}

/**
 * Live timed buff, debuff, or status affect applied to an actor.
 *
 * Re-application rule: Applying an affect with the same name as an existing active affect replaces
 * the existing one only if the new [expiresAt] pulse is strictly later. Otherwise, the existing
 * affect remains unchanged so a shorter duration never cuts a longer one short.
 */
class Affect(
  /** Unique affect instance identifier. */
  val id: Long,
  /** Identifying name or label of the affect. */
  val name: String,
  /** Entity ID of the caster or originator of the affect, if known. */
  val source: EntityId?,
  /** Absolute pulse number when this affect was applied. */
  val appliedAt: Long,
  /** Absolute pulse number when this affect will expire. */
  val expiresAt: Long,
  /** Attribute deltas applied to the host actor while active. */
  val statDeltas: Map<String, Int> = emptyMap(),
  /**
   * Status flags applied to the host actor while active (e.g. `stunned`, `silenced`, `no_regen`).
   */
  val flags: Set<String> = emptySet(),
  /** Periodic pulse interval for [onTick] execution, or null if non-periodic. */
  val tickEvery: Int? = null,
  /** Script hook invoked periodically every [tickEvery] pulses. */
  val onTick: HookRef? = null,
  /** Script hook invoked when the affect expires or is dispelled. */
  val onExpire: HookRef? = null,
  /** Narrative message echoed to the host actor when the affect expires. */
  val expireMessage: String = "",
  /** Rules modifiers contributed by this affect to the host actor. */
  val modifiers: Modifiers = emptyMap(),
  /** Item ID of the worn equipment providing this affect, or null for timed spell affects. */
  val wornFrom: EntityId? = null,
  /** Perception sense deltas applied to the host actor while active. */
  val senseDeltas: Map<Channel, Int> = emptyMap(),
)

/**
 * Scheduled callback timer registered by scripts. Exactly one of [due] (while running in an active
 * room) or [remaining] (while paused in a dormant room) is set.
 */
class ScriptTimer(
  /** Unique timer instance identifier. */
  val id: Long,
  /** Script callback function to invoke upon expiration. */
  val fn: HookRef,
  /** Argument values passed to the script callback upon invocation. */
  val args: Map<String, AttrValue>,
  /** Absolute pulse when this timer is due to fire, or null while paused. */
  var due: Long?,
  /** Remaining pulses until execution while paused in a dormant room. */
  var remaining: Int? = null,
)

/** State of an active skill or spell during its warmup casting period. */
class CastState(
  /** Action being prepared. */
  val action: Action,
  /** Absolute pulse when casting commenced. */
  val startedAt: Long,
  /** Absolute pulse when casting warmup will complete. */
  val endsAt: Long,
)

/** An action command waiting in an actor's tick-lane queue. */
class QueuedAction(
  /** Tick command handler responsible for executing this action. */
  val command: TickCommand,
  /** Verb entered by the actor. */
  val verb: String,
  /** Argument string passed with the verb. */
  val args: String,
) {
  override fun toString(): String = "$verb $args".trim()
}

/** Base stat and base vital maximum deltas that one level applied, both possibly negative. */
data class LevelGain(
  /** Base stat deltas applied by this level. */
  val stats: Map<String, Int> = emptyMap(),
  /** Base vital maximum deltas applied by this level. */
  val max: Map<String, Int> = emptyMap(),
)

/** Base class for autonomous animated entities (players and mobs). */
sealed class Actor(id: EntityId, protoLabel: Label, room: Room, trackIndex: Int) :
  Entity(id, protoLabel) {
  /** Target keywords recognized by player commands when interacting with this actor. */
  abstract val keywords: List<String>

  /** True if this actor represents a player character. */
  abstract val isPlayer: Boolean

  /** Current room where this actor is located. */
  var room: Room = room
    internal set

  /** Slot position index on the current room's spatial track. */
  var trackIndex: Int = trackIndex
    internal set

  /** Base attribute values declared for this actor; unmapped stats evaluate to 0. */
  val baseStats: MutableMap<String, Int> = LinkedHashMap()
  internal val vitalMap: MutableMap<String, Vital> = LinkedHashMap()

  /** Vitals map in schema order. Changed only through the world. */
  val vitals: Map<String, Vital>
    get() = vitalMap

  /**
   * Life state as `rules.condition` last decided it (`OK`, `INCAPACITATED`, or `DEAD`). Changed
   * only through the world.
   */
  var condition: Condition = Condition.OK
    internal set

  /** Progression level of this actor. */
  var level: Int = 1

  /** Accumulated experience points. */
  var xp: Long = 0

  /**
   * Gains recorded per level reached, so losing a level subtracts exactly what it added. A level
   * with no entry unwinds nothing. Changes to current vitals are never recorded.
   *
   * Each entry belongs to its own level: raising the actor to a level replaces that level's entry,
   * or removes it if the level gave no stat or maximum gains, and never touches another level's.
   */
  val levelGains: MutableMap<Int, LevelGain> = linkedMapOf()

  /** Damage resistance map indexed by damage type. */
  val resists: MutableMap<DamageType, Int> = linkedMapOf()

  /** Skill proficiency ratings indexed by skill prototype label. */
  val skills: MutableMap<Label, Int> = linkedMapOf()

  /** Active status affects currently applied to this actor. */
  val affects: MutableList<Affect> = mutableListOf()

  /** Explicitly granted trait names. */
  var grantedTraits: Set<String> = emptySet()

  /** Effective sorted list of all active traits. */
  var traits: List<Trait> = emptyList()

  /**
   * Base perception sense scores by channel; defaults to 1 for built-ins, 0 for custom channels.
   */
  val baseSenses: MutableMap<Channel, Int> = LinkedHashMap(Channel.BUILTIN_SENSES)

  /** Cached sense deltas aggregated from all active traits. */
  var traitSenseDeltas: Map<Channel, Int> = emptyMap()
    internal set

  /** Cached stat deltas aggregated from all active traits. */
  var traitStatDeltas: Map<String, Int> = emptyMap()
    internal set

  /** Cached vital maximum deltas aggregated from all active traits. */
  var traitMaxVitalDeltas: Map<String, Int> = emptyMap()
    internal set

  /** Cached resistance deltas aggregated from all active traits. */
  var traitResistDeltas: Map<String, Int> = emptyMap()
    internal set

  /** Cached status flags aggregated from all active traits. */
  var traitFlags: Set<String> = emptySet()
    internal set

  /**
   * Recomputes active traits from character creation choices, prototype traits, and granted traits.
   * Caches aggregated trait deltas for stats, senses, vital maximums, resists, and flags, then
   * clamps all current vitals to their newly calculated effective maxima.
   */
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

  /** Queue of pending player or mob actions waiting to execute in the tick lane. */
  val actionQueue: ArrayDeque<QueuedAction> = ArrayDeque()

  /** Pulses of recovery lag remaining before this actor can begin another action. */
  var waitState: Int = 0

  /** Recovery held aside while the actor is stunned or incapacitated, restored when that ends. */
  internal var heldWaitState: Int = 0

  /** The pulse the current disability started, so a paused warmup can be pushed back. */
  internal var heldSince: Long = 0

  /** Active casting state if warming up a skill or spell, or null. */
  var casting: CastState? = null

  /** Set of entity IDs this actor is currently engaged in combat with. */
  val fighting: java.util.TreeSet<EntityId> = java.util.TreeSet()

  /** Entity ID of the actor's primary combat target, or null. */
  var primaryTarget: EntityId? = null

  /** Initiative score rolled for the current combat round, or null if unengaged during rolls. */
  var initiative: Int? = null
    internal set

  /**
   * Entity ID of the last actor who inflicted damage on this actor; that actor receives the kill.
   */
  var lastAttacker: EntityId? = null
    internal set

  /** True while the actor is actively present in the simulation world. */
  var inWorld: Boolean = false
    internal set

  internal val inventoryIndex = TreeMap<EntityId, Item>()
  internal val equipmentIndex = LinkedHashMap<String, Item>()

  /** List of all items carried in inventory, ordered by entity ID. */
  val inventory: List<Item>
    get() = inventoryIndex.values.toList()

  /** Map of equipped items indexed by equipment slot name. */
  val equipment: Map<String, Item>
    get() = LinkedHashMap(equipmentIndex)

  /** Returns the item equipped in [slot], or null if the slot is empty. */
  fun equipped(slot: String): Item? = equipmentIndex[slot]

  /** Stat penalties imposed by carrying weight in excess of the carry limit. */
  var encumbrance: Map<String, Int> = emptyMap()
    internal set

  /** What the actor's items contribute; kept current by `refreshHoldings`. */
  var heldEffects: HeldEffects = HeldEffects.EMPTY
    internal set

  /**
   * Computes the effective value of stat [name]: base value plus trait deltas, active affect
   * deltas, item deltas, and encumbrance penalties.
   */
  fun stat(name: String): Int =
    (baseStats[name] ?: 0) +
      (traitStatDeltas[name] ?: 0) +
      affects.sumOf { it.statDeltas[name] ?: 0 } +
      (heldEffects.statDeltas[name] ?: 0) +
      (encumbrance[name] ?: 0)

  /** Returns the unmodified base value of stat [name]. */
  fun baseStat(name: String): Int = baseStats[name] ?: 0

  /**
   * Computes the effective perception score on [channel]: base sense plus trait deltas, affect
   * deltas, and item deltas, clamped to at least 0.
   */
  fun sense(channel: Channel): Int =
    ((baseSenses[channel] ?: 0) +
        (traitSenseDeltas[channel] ?: 0) +
        affects.sumOf { it.senseDeltas[channel] ?: 0 } +
        (heldEffects.senseDeltas[channel] ?: 0))
      .coerceAtLeast(0)

  /** Returns effective perception scores across all provided [allChannels]. */
  fun effectiveSenses(allChannels: Collection<Channel>): Map<Channel, Int> =
    allChannels.associateWith { sense(it) }

  /** Current value of [vital]. */
  fun current(vital: String): Int = vitalMap[vital]?.current ?: 0

  /**
   * Computes the effective maximum for [vital]: base maximum plus trait deltas, clamped to at
   * least 0.
   */
  fun max(vital: String): Int =
    maxOf(0, (vitalMap[vital]?.max ?: 0) + (traitMaxVitalDeltas[vital] ?: 0))

  /** Returns the unmodified base maximum for [vital]. */
  fun baseMax(vital: String): Int = vitalMap[vital]?.max ?: 0

  /**
   * Computes effective resistance to damage [type] string, combining base resist and trait deltas.
   */
  fun effectiveResist(type: String): Int =
    (resists[DamageType(type)] ?: 0) + (traitResistDeltas[type] ?: 0)

  /** Computes effective resistance to [type], combining base resist and trait deltas. */
  fun effectiveResist(type: DamageType): Int =
    (resists[type] ?: 0) + (traitResistDeltas[type.name] ?: 0)

  /** Effective resistance to damage [type] string. */
  fun resist(type: String): Int = effectiveResist(type)

  /** Effective resistance to damage [type]. */
  fun resist(type: DamageType): Int = effectiveResist(type)

  /** Unmodified base resistance to damage [type] string. */
  fun baseResist(type: String): Int = resists[DamageType(type)] ?: 0

  /** Unmodified base resistance to damage [type]. */
  fun baseResist(type: DamageType): Int = resists[type] ?: 0

  /** True if the actor possesses the trait named [name]. */
  fun hasTrait(name: String): Boolean = traits.any { it.name == name }

  /** All active flags currently on this actor from traits and affects. */
  val flags: Set<String>
    get() = traitFlags + affects.flatMap { it.flags } + heldEffects.flags

  /** True if a trait, an active affect or an item affect reaching the actor carries [flag]. */
  fun hasFlag(flag: String): Boolean =
    flag in traitFlags || affects.any { flag in it.flags } || flag in heldEffects.flags

  /** True if the actor is suffering a stun effect. */
  val isStunned: Boolean
    get() = hasFlag("stunned")

  /** True if the actor's condition is incapacitated. */
  val isIncapacitated: Boolean
    get() = condition == Condition.INCAPACITATED

  /** True if stunned or incapacitated (cannot take actions and holds recovery). */
  val isDisabled: Boolean
    get() = isStunned || isIncapacitated

  /** True if currently engaged in combat with one or more adversaries. */
  val isEngaged: Boolean
    get() = fighting.isNotEmpty()

  /** True if currently warming up a skill or spell. */
  val isCasting: Boolean
    get() = casting != null

  /** True if the actor has no active casting, zero recovery wait, and an empty action queue. */
  val isIdle: Boolean
    get() = casting == null && waitState == 0 && actionQueue.isEmpty()

  /** True if [keyword] matches any keyword of this actor case-insensitively. */
  fun matches(keyword: String): Boolean {
    val k = keyword.lowercase()
    return keywords.any { it.lowercase().startsWith(k) }
  }
}

/** Live player character entity. */
class Player(
  id: EntityId,
  override val name: String,
  room: Room,
  trackIndex: Int,
  /** Default room label where this player respawns or returns upon recall. */
  var recallRoom: Label,
  /** ID of the owning account, or null for guests or characters without an account. */
  var accountId: Long? = null,
) : Actor(id, Prototypes.PLAYER, room, trackIndex) {
  override val keywords: List<String> = listOf(name.lowercase())
  override val isPlayer: Boolean
    get() = true

  /** Connected session ID, or null while link-dead. */
  var session: SessionId? = null
    internal set

  /** Monotonic pulse when the session disconnected, or null while connected. */
  var linkDeadSince: Long? = null
    internal set

  /** Monotonic pulse when the player last submitted command input. */
  var lastInputPulse: Long = 0
    internal set

  /** True if this player is exempt from the idle auto-quit timeout, as instructors are. */
  var idleExempt: Boolean = false

  /** Player's selected character creation choices: choice ID -> option ID. */
  val choices: MutableMap<String, String> = LinkedHashMap()

  /** The account's authority role gating builder and administrative commands. */
  var role: Role = Role.PLAYER

  /** Content packages a builder is authorized to edit. */
  var scope: Set<String> = emptySet()

  /**
   * True if this player is authorized to use builder tools on package [pkg]. Granted to
   * instructors, admins, and builders whose scope includes the package.
   */
  fun owns(pkg: String): Boolean =
    role.atLeast(Role.INSTRUCTOR) || (role.atLeast(Role.BUILDER) && pkg in scope)
}

/** Live non-player mobile entity. */
class Mob(
  id: EntityId,
  /** Active mob prototype definition. */
  var proto: MobProto,
  room: Room,
  trackIndex: Int,
) : Actor(id, proto.label, room, trackIndex) {
  init {
    protoVersion = proto.version
  }

  override val name: String
    get() = proto.name

  override val keywords: List<String>
    get() = proto.keywords

  override val isPlayer: Boolean
    get() = false

  /** AI thinking cadence in game pulses. */
  var thinkEvery: Int = proto.thinkEvery

  /** Absolute pulse when this mob last executed AI thinking. */
  var lastThink: Long = Long.MIN_VALUE / 2
    internal set

  /** Home room label where this mob was originally spawned. */
  var homeRoom: Label = room.label

  /** Vital recovery behavior when waking from dormancy. */
  var dormantRecovery: DormantRecovery = proto.dormantRecovery

  /** Leader actor ID that this mob follows, or null if unled. */
  var leader: EntityId? = null
    internal set

  internal var spawnedBy: SpawnState? = null

  /**
   * Actor ID that summoned this mob, or null if it wasn't summoned. A summoned mob gives no XP or
   * loot.
   */
  var summonedBy: EntityId? = null

  /** Player entity ID who spawned this mob via debug commands, or null. */
  var debugOwner: EntityId? = null
    internal set

  /** Absolute pulse when this debug mob will despawn automatically, or null. */
  var debugUntil: Long? = null
    internal set
}

/**
 * Authoritative location of an item in the simulation. Invariant: Every item has exactly one
 * location, and containment chains contain no cycles.
 */
sealed interface ItemLocation {
  /** Item is lying on the ground in [room] at spatial slot [trackIndex]. */
  data class InRoom(val room: EntityId, val trackIndex: Int) : ItemLocation

  /** Item is carried in the inventory of [actor]. */
  data class Carried(val actor: EntityId) : ItemLocation

  /** Item is equipped on [actor] in equipment [slot]. */
  data class Equipped(val actor: EntityId, val slot: String) : ItemLocation

  /** Item is contained inside another [container] item. */
  data class Contained(val container: EntityId) : ItemLocation
}

/** Live mutable physical state of a container item. */
class ContainerState(proto: ContainerProto) {
  /** Maximum number of items that can be contained. */
  val capacity: Int = proto.capacity

  /** Maximum combined weight of contents in weight units. */
  val maxWeight: Int = proto.maxWeight

  /** Physical open/closed/locked door state of the container. */
  var state: DoorState = proto.state

  /** Prototype label of the key item required to lock or unlock this container, or null. */
  val key: Label? = proto.key
}

/** Live item instance. */
class Item(
  id: EntityId,
  /** Active prototype definition of this item. */
  var proto: ItemProto,
  location: ItemLocation,
  /** Quantity of items represented in this stack, defaulting to 1. */
  var count: Int = 1,
) : Entity(id, proto.label) {
  init {
    protoVersion = proto.version
  }

  /**
   * Current authoritative location of this item in the simulation. Changed only through the world's
   * item verbs, which keep every location index consistent.
   */
  var location: ItemLocation = location
    internal set

  /** Optional customized name overriding the prototype display name. */
  var nameOverride: String? = null

  /** Display name of the item. */
  override val name: String
    get() = nameOverride ?: proto.name

  /** Target keywords used to match this item in player commands. */
  val keywords: List<String>
    get() = proto.keywords

  /** Category classification of the item. */
  val kind: String
    get() = proto.kind

  /** Semantic tags assigned to this item. */
  val tags: Set<String>
    get() = proto.tags

  /** Engagement range band for weapon attacks with this item. */
  val range: Band
    get() = proto.range

  /** Total weight of this item stack including the weight of all contained items. */
  val weight: Int
    get() = proto.weight * count + contentsIndex.values.sumOf { it.weight }

  /** Mutable container state if this item functions as a container, or null. */
  val container: ContainerState? = proto.container?.let(::ContainerState)

  /** Absolute pulse when this ground item will decay and vanish, or null. */
  var decayAt: Long? = null

  /** Saved serialization payload preserved for tombstone items when their prototype was deleted. */
  var savedPayload: String? = null

  internal val contentsIndex = TreeMap<EntityId, Item>()

  /** List of items held inside this container, ordered by entity ID. */
  val contents: List<Item>
    get() = contentsIndex.values.toList()

  /**
   * How players see [amount] of this item (by default the whole stack): [name] for one, `"<amount>
   * <plural>"` when the prototype has a plural and there is no [nameOverride], else `"<name>
   * (<amount>)"`.
   */
  fun describe(amount: Int = count): String =
    when {
      amount <= 1 -> name
      nameOverride == null && proto.plural != null -> "$amount ${proto.plural}"
      else -> "$name ($amount)"
    }

  /**
   * True if every word of [phrase] is the start of one of this item's keywords, keyword words or
   * plural words, ignoring case. Articles are dropped unless the phrase is nothing but articles, so
   * `an` still abbreviates `anvil`.
   */
  fun matches(phrase: String): Boolean {
    val words = keywordWords(phrase).ifEmpty { allWords(phrase) }
    if (words.isEmpty()) return false
    return words.all { w -> proto.matchWords.any { it.startsWith(w) } }
  }
}
