package gups.core.model

import gups.core.HookRef
import gups.core.Label
import gups.core.THINK_DEFAULT_PULSES
import gups.core.world.Behavior
import gups.core.world.SkillResolver
import gups.core.world.ThinkFn
import gups.core.world.ZoneCommand

/** The pattern every tag, flag-like name and variant name must match. */
val TAG_REGEX = Regex("[a-z][a-z0-9_]*")

// Immutable prototypes. The `script` module produces them by evaluating
// packages; Kotlin tests build them directly.

/** The hook points in the rules evaluation chain where entities can apply custom modifiers. */
enum class ModifierPoint(
  /** Starlark hook name for this modifier point. */
  val scriptName: String
) {
  /** Modifies the auto-attack or skill to-hit calculation. */
  TO_HIT("to_hit"),

  /** Modifies the raw damage rolled during combat resolution. */
  DAMAGE("damage"),

  /** Modifies the initiative rolled at the start of a combat round. */
  INITIATIVE("initiative"),

  /** Modifies whether skill or spell casting is permitted. */
  CAST_ALLOWED("cast_allowed"),

  /** Modifies the vital costs required to move between rooms. */
  MOVE_COST("move_cost"),

  /** Modifies vital recovery or loss applied during periodic world ticks. */
  REGEN("regen");

  companion object {
    /** Finds a [ModifierPoint] by its script hook name, or returns null if not recognized. */
    fun byName(name: String): ModifierPoint? = entries.firstOrNull { it.scriptName == name }
  }
}

/** Mapping of modifier points to top-level script hook references `fn(ctx, base)`. */
typealias Modifiers = Map<ModifierPoint, HookRef>

/** Prototype definition for a door obstructing an exit or container opening. */
data class DoorProto(
  /** Initial physical state of the door, defaulting to [DoorState.CLOSED]. */
  val state: DoorState = DoorState.CLOSED,
  /** Prototype label of the key item required to lock or unlock this door, or null if unkeyed. */
  val key: Label? = null,
  /** Semantic tags inspected by game rules (e.g. `secret`, `sturdy`). */
  val tags: Set<String> = emptySet(),
)

/** Prototype definition for an exit leading from one room to another. */
data class ExitProto(
  /** Compass direction or movement transition for this exit. */
  val direction: Direction,
  /** Prototype label of the destination room. */
  val to: Label,
  /** Arrival track index in the destination room; `-1` defaults to the back slot. */
  val entryIndex: Int = 0,
  /** Free-form tags inspected by rules and perception (e.g. `soundproof`). */
  val tags: Set<String> = emptySet(),
  /** Door blocking this exit, or null if open passage. */
  val door: DoorProto? = null,
  /** True if this exit cannot be traversed in reverse. */
  val oneWay: Boolean = false,
)

/** Rule governing periodic spawning of mobs into a room. */
data class SpawnRule(
  /** Prototype label of the mob to spawn. */
  val label: Label,
  /** Maximum number of live instances spawned by this rule concurrently, defaulting to 1. */
  val count: Int = 1,
  /** Interval in game pulses between respawn attempts, defaulting to 1200 (5 minutes). */
  val respawnPulses: Int = 1200,
  /** Track index in the room where spawned mobs appear. */
  val atIndex: Int = 0,
)

/** Immutable prototype defining the static properties of a room. */
data class RoomProto(
  /** Canonical label of the room (e.g. `//zones/forest:clearing`). */
  val label: Label,
  /** Display name of the room. */
  val name: String,
  /** Long-form description shown when looking at the room. */
  val desc: String = "",
  /** Spatial track capacity configuration, defaulting to [TrackSize.NORMAL]. */
  val size: TrackSize = TrackSize.NORMAL,
  /** Region names this room belongs to. */
  val regions: Set<String> = emptySet(),
  /** Semantic tags inspected by scripts and rules. */
  val tags: Set<String> = emptySet(),
  /** Behavioral flags (e.g. `always_active`, `dead_end_ok`). */
  val flags: Set<String> = emptySet(),
  /** Outgoing exits declared from this room. */
  val exits: List<ExitProto> = emptyList(),
  /** Mob spawning rules evaluated by this room. */
  val spawns: List<SpawnRule> = emptyList(),
  /** Room-specific custom verbs executed in the tick lane. */
  val commands: Map<String, ZoneCommand> = emptyMap(),
  /** Environmental rules modifiers applied by this room. */
  val modifiers: Modifiers = emptyMap(),
  /** Content schema version of this room prototype, defaulting to 1. */
  val version: Int = 1,
)

/**
 * A builder-applied regional tag applied across rooms and zones that contributes environmental
 * modifiers.
 */
data class RegionProto(
  /** Unique region identifier name. */
  val name: String,
  /** Package that defined this region prototype. */
  val pkg: String,
  /** Environmental rules modifiers contributed by this region. */
  val modifiers: Modifiers = emptyMap(),
)

/** Probability rule for mob corpse loot generation. */
data class LootRule(
  /** Prototype label of the item to drop. */
  val label: Label,
  /** Probability of dropping this item between 0.0 and 1.0, defaulting to 1.0 (100%). */
  val chance: Double = 1.0,
  /** Quantity of the item to drop, defaulting to 1. */
  val count: Int = 1,
)

/** Immutable prototype defining the static properties and capabilities of a mob. */
data class MobProto(
  /** Canonical label of the mob prototype (e.g. `//zones/forest:goblin`). */
  val label: Label,
  /** Display name of the mob. */
  val name: String,
  /** Keywords used by players to target this mob in commands. */
  val keywords: List<String>,
  /** Long-form description shown when inspecting the mob. */
  val desc: String = "",
  /** Progression level of the mob, defaulting to 1. */
  val level: Int = 1,
  /** Base stat values declared for this mob. */
  val stats: Map<String, Int> = emptyMap(),
  /** Maximum vital values declared for this mob. */
  val vitals: Map<String, Int> = emptyMap(),
  /** Damage-type resistance values declared for this mob. */
  val resists: Map<DamageType, Int> = emptyMap(),
  /** Skill proficiencies known by this mob. */
  val skills: Map<Label, Int> = emptyMap(),
  /** Perception sense modifiers declared per channel. */
  val senses: Map<Channel, Int> = emptyMap(),
  /** Built-in autonomous behaviors executed in declaration order. */
  val behaviors: List<Behavior> = emptyList(),
  /** Custom script thinking callback, or null for default behaviors only. */
  val think: ThinkFn? = null,
  /** AI thinking cadence in game pulses, defaulting to [THINK_DEFAULT_PULSES] (4 pulses = 1.0s). */
  val thinkEvery: Int = THINK_DEFAULT_PULSES,
  /**
   * Vital recovery behavior when waking from a dormant state, defaulting to [DormantRecovery.FULL].
   */
  val dormantRecovery: DormantRecovery = DormantRecovery.FULL,
  /** Loot generation rules evaluated when the mob is killed. */
  val loot: List<LootRule> = emptyList(),
  /** Innate traits granted to mobs created from this prototype. */
  val traits: Set<String> = emptySet(),
  /** Content schema version of this mob prototype, defaulting to 1. */
  val version: Int = 1,
)

/** Prototype definition for an item capable of containing other items. */
data class ContainerProto(
  /** Maximum number of distinct items that can be contained, defaulting to 10. */
  val capacity: Int = 10,
  /** Maximum combined weight of contents in weight units, defaulting to 100. */
  val maxWeight: Int = 100,
  /** Initial physical state of the container, defaulting to [DoorState.OPEN]. */
  val state: DoorState = DoorState.OPEN,
  /**
   * Prototype label of the key item required to lock or unlock this container, or null if unkeyed.
   */
  val key: Label? = null,
)

/** Immutable prototype defining the static properties of an item. */
data class ItemProto(
  /** Canonical label of the item prototype (e.g. `//zones/forest:iron_sword`). */
  val label: Label,
  /** Display name of the item. */
  val name: String,
  /** Keywords used by players to target this item in commands. */
  val keywords: List<String>,
  /** Long-form description shown when inspecting the item. */
  val desc: String = "",
  /**
   * Item category classification (e.g. `weapon`, `armor`, `consumable`, `corpse`), defaulting to
   * `misc`.
   */
  val kind: String = "misc",
  /** Combat engagement range band for weapon items, defaulting to [Band.MELEE]. */
  val range: Band = Band.MELEE,
  /** Weight of a single unit of this item, defaulting to 1. */
  val weight: Int = 1,
  /** Equipment slot this item occupies when worn, or null if not wearable. */
  val wear: String? = null,
  /** How many slots of its family this item takes up when worn; at least 1. */
  val slotCost: Int = 1,
  /** Weapon damage dice notation, or null for non-weapons. */
  val dice: Dice? = null,
  /** Damage type inflicted by this weapon, or null to inherit unarmed damage. */
  val damageType: DamageType? = null,
  /** Attribute modifiers applied to the bearer while equipped. */
  val statDeltas: Map<String, Int> = emptyMap(),
  /** Perception sense modifiers applied to the bearer while equipped. */
  val senseDeltas: Map<Channel, Int> = emptyMap(),
  /** Vital maximum modifiers applied to the bearer while equipped. */
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  /** Damage resistance modifiers applied to the bearer while equipped. */
  val resistDeltas: Map<DamageType, Int> = emptyMap(),
  /** Free-form semantic tags inspected by scripts and rules. */
  val tags: Set<String> = emptySet(),
  /** True if multiple units can merge into a single item stack. */
  val stackable: Boolean = false,
  /** Ground decay lifetime in pulses, or null if the item persists indefinitely on the ground. */
  val decayPulses: Int? = null,
  /** Container capabilities if this item can hold other items, or null. */
  val container: ContainerProto? = null,
  /** Custom item verbs executed in the tick lane. */
  val commands: Map<String, ZoneCommand> = emptyMap(),
  /** Passive affects applied to the bearer while this item remains equipped. */
  val equipAffects: List<EquipAffect> = emptyList(),
  /** Affects every instance carries, read live from the prototype. */
  val affects: List<ItemAffect> = emptyList(),
  /** Free-form variant names to related item prototypes; the engine gives the names no meaning. */
  val variants: Map<String, Label> = emptyMap(),
  /** Rules modifiers contributed to the bearer while this item is equipped. */
  val modifiers: Modifiers = emptyMap(),
  /** Content schema version of this item prototype, defaulting to 1. */
  val version: Int = 1,
  /** Display name for stacks of 2 or more, such as `gold coins`, or null to show `name (count)`. */
  val plural: String? = null,
) {
  init {
    require(slotCost >= 1) { "slotCost must be >= 1, was $slotCost" }
  }

  /**
   * The lowercase words a player can name this item by: each keyword whole and split into words,
   * plus the words of [plural].
   */
  val matchWords: List<String> by lazy {
    (keywords.flatMap { listOf(it.lowercase()) + keywordWords(it) } +
        plural?.let(::keywordWords).orEmpty())
      .distinct()
  }
}

/** Target distance ranges supported by skills and spells. */
enum class SkillRange {
  /** Effective against targets in the same room at melee band. */
  MELEE,

  /** Effective against targets at near engagement band. */
  NEAR,

  /** Effective against targets at far engagement band. */
  FAR,

  /** Targets the caster directly. */
  SELF,

  /** Affects all valid targets within the caster's room. */
  ROOM,
}

/** Targeting categories accepted by skills and spells. */
enum class TargetKind {
  /** Targets hostile actors. */
  ENEMY,

  /** Targets friendly or allied actors. */
  ALLY,

  /** Targets the caster alone. */
  SELF,

  /** Targets an item in inventory or in the room. */
  ITEM,

  /** Requires no specific target. */
  NONE,
}

/** Passive affect definition granted while an item remains equipped. */
data class EquipAffect(
  /** Status flag applied to the wearer (e.g. `no_regen`, `infravision`). */
  val flag: String,
  /** Optional temporary duration in pulses, or null to last until unequipped. */
  val pulses: Int? = null,
)

/** An affect a prototype declares; read live, never expires, can't be removed from one instance. */
data class ItemAffect(
  /** Name of the affect. */
  val name: String,
  /** Where the affect reaches its holder from; [AffectScope.WORN] by default. */
  val applies: AffectScope = AffectScope.WORN,
  /** Attribute deltas counted for the holder while the affect reaches it. */
  val statDeltas: Map<String, Int> = emptyMap(),
  /** Perception sense deltas counted for the holder while the affect reaches it. */
  val senseDeltas: Map<Channel, Int> = emptyMap(),
  /** Vital maximum deltas counted for the holder while the affect reaches it. */
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  /** Damage resistance deltas counted for the holder while the affect reaches it. */
  val resistDeltas: Map<DamageType, Int> = emptyMap(),
  /** Rules modifiers contributed by this affect to the holder. */
  val modifiers: Modifiers = emptyMap(),
  /** Status flags counted for the holder while the affect reaches it. */
  val flags: Set<String> = emptySet(),
)

/** Interrupt conditions that break an in-progress skill or spell cast. */
data class Interrupt(
  /**
   * Percentage of maximum vital lost in a single blow that interrupts casting, or null if damage
   * does not interrupt.
   */
  val damagePct: Int? = null,
  /** Whether suffering a stun effect interrupts casting, defaulting to true. */
  val stun: Boolean = true,
) {
  companion object {
    /** Interrupt configuration that is never broken by damage or stuns. */
    val NONE = Interrupt(damagePct = null, stun = false)
  }
}

/** Immutable prototype defining the properties, costs, and effects of an active skill or spell. */
data class SkillProto(
  /** Canonical label of the skill prototype (e.g. `//skills/magic:fireball`). */
  val label: Label,
  /** Skill category (e.g. `skill`, `spell`), defaulting to `skill`. */
  val kind: String = "skill",
  /** Vital costs deducted upon initiation, keyed by declared vital names. */
  val cost: Map<String, Int> = emptyMap(),
  /** Warmup delay in pulses before the skill resolves, defaulting to 0. */
  val warmup: Int = 0,
  /** Action recovery lag in pulses applied to the user after resolution, defaulting to 0. */
  val lag: Int = 0,
  /** Operational range required for target acquisition, defaulting to [SkillRange.MELEE]. */
  val range: SkillRange = SkillRange.MELEE,
  /** Valid targeting kind for this skill, defaulting to [TargetKind.ENEMY]. */
  val target: TargetKind = TargetKind.ENEMY,
  /** Perception channels required to invoke or perceive this skill. */
  val channels: Set<Channel> = emptySet(),
  /** Conditions under which in-progress warmup is interrupted. */
  val interrupt: Interrupt = Interrupt(),
  /** Whether the caster continues regular auto-attacks while warming up this skill. */
  val attackWhileCasting: Boolean = false,
  /** Built-in status or damage effect applied on resolution, or null. */
  val effect: Effect? = null,
  /** Custom script resolution callback, or null for default effects. */
  val resolve: SkillResolver? = null,
  /** Free-form semantic tags inspected by rules and scripts. */
  val tags: Set<String> = emptySet(),
) {
  /** Short unqualified name of the skill derived from its label. */
  val name: String
    get() = label.name
}

/** Package-level configuration for a zone of rooms. */
data class ZoneProto(
  /** Package directory path of the zone (e.g. `zones/forest`). */
  val pkg: String,
  /** User-facing display title of the zone, defaulting to [pkg]. */
  val title: String = pkg,
  /** Periodic reset interval in game pulses, or null if the zone does not reset automatically. */
  val resetPulses: Int? = null,
  /** Environmental rules modifiers applied across all rooms in the zone. */
  val modifiers: Modifiers = emptyMap(),
)

/**
 * Authoritative lookup table of all loaded entity prototypes. Prototype labels are unique per
 * prototype kind.
 */
class Prototypes(
  rooms: List<RoomProto> = emptyList(),
  mobs: List<MobProto> = emptyList(),
  items: List<ItemProto> = emptyList(),
  skills: List<SkillProto> = emptyList(),
  zones: List<ZoneProto> = emptyList(),
  regions: List<RegionProto> = emptyList(),
) {
  /** Zone prototypes indexed by package path. */
  val zones: Map<String, ZoneProto> = zones.associateBy { it.pkg }

  /** Worldwide region prototypes indexed by region name. */
  val regions: Map<String, RegionProto> =
    LinkedHashMap<String, RegionProto>().also { out ->
      for (r in regions) require(out.put(r.name, r) == null) { "Duplicate region ${r.name}" }
    }

  /** Room prototypes indexed by canonical label. */
  val rooms: Map<Label, RoomProto> = unique("room", rooms) { it.label }

  /** Mob prototypes indexed by canonical label. */
  val mobs: Map<Label, MobProto> = unique("mob", mobs) { it.label }

  /** Item prototypes indexed by canonical label, including the reserved [CORPSE] prototype. */
  val items: Map<Label, ItemProto> = unique("item", items + CORPSE) { it.label }

  /** Skill prototypes indexed by canonical label. */
  val skills: Map<Label, SkillProto> = unique("skill", skills) { it.label }

  /** Looks up a mob prototype by [label], throwing [NoSuchElementException] if absent. */
  fun mob(label: Label): MobProto =
    mobs[label] ?: throw NoSuchElementException("No mob prototype $label")

  /** Looks up an item prototype by [label], throwing [NoSuchElementException] if absent. */
  fun item(label: Label): ItemProto =
    items[label] ?: throw NoSuchElementException("No item prototype $label")

  /** Looks up a skill prototype by [label], throwing [NoSuchElementException] if absent. */
  fun skill(label: Label): SkillProto =
    skills[label] ?: throw NoSuchElementException("No skill $label")

  companion object {
    /** Reserved prototype label representing all player characters (`//engine:player`). */
    val PLAYER: Label = Label("//engine:player")

    /** Built-in prototype for player and mob corpse containers (`//engine:corpse`). */
    val CORPSE =
      ItemProto(
        label = Label("//engine:corpse"),
        name = "a corpse",
        keywords = listOf("corpse"),
        kind = "corpse",
        weight = 100,
        container = ContainerProto(capacity = 100, maxWeight = 10_000),
        tags = setOf("no_all"),
      )

    private fun <T> unique(kind: String, protos: List<T>, label: (T) -> Label): Map<Label, T> {
      val out = LinkedHashMap<Label, T>()
      for (p in protos) require(out.put(label(p), p) == null) {
        "Duplicate $kind prototype ${label(p)}"
      }
      return out
    }
  }
}
