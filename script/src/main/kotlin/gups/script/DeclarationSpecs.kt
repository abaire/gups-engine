package gups.script

import gups.core.model.Band
import gups.core.model.Direction
import gups.core.model.DoorState
import gups.core.model.DormantRecovery
import gups.core.model.ModifierPoint
import gups.core.model.SkillRange
import gups.core.model.TargetKind
import gups.core.model.TrackSize
import gups.core.model.WorldSchema
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkList

/** The declaration specs, constants and effect and behavior names the API model documents. */
internal object DeclarationSpecs {
  private val ROOM_FLAGS = listOf("always_active", "dead_end_ok")
  private val TRACK_SIZES = TrackSize.entries.map { it.name.lowercase() }
  private val DOOR_STATES = DoorState.entries.map { it.name.lowercase() }
  private val BANDS = Band.entries.map { it.word }
  private val DORMANT_MODES = DormantRecovery.entries.map { it.name.lowercase() }
  private val SKILL_RANGES = SkillRange.entries.map { it.name.lowercase() }
  private val TARGET_KINDS = TargetKind.entries.map { it.name.lowercase() }

  /** Constants available to every package file. */
  fun constants(schema: WorldSchema): Map<String, Any> {
    val dirs = StructValue(Direction.STANDARD.associate { it.name to it.name })
    val modifiers = StructValue(ModifierPoint.entries.associate { it.scriptName to it.scriptName })
    val sizes =
      StructValue(TrackSize.entries.associate { it.name.lowercase() to it.name.lowercase() })
    val senses =
      StructValue(
        schema.allChannels.associate { it.name to it.name } +
          mapOf("all" to StarlarkList.immutableCopyOf(schema.allChannels.map { it.name }))
      )
    val doorStates =
      StructValue(DoorState.entries.associate { it.name.lowercase() to it.name.lowercase() })
    val damageTypes = StructValue(schema.damageTypes.associateWith { it })

    return mapOf(
      "DIR" to dirs,
      "MODIFIER" to modifiers,
      "SIZE" to sizes,
      "SENSE" to senses,
      "DOOR" to doorStates,
      "DAMAGE" to damageTypes,
      "SEMI_PERMANENT" to StarlarkInt.of(Long.MAX_VALUE),
    )
  }

  val CONSTANTS: Map<String, Any> = constants(WorldSchema.EMPTY)

  val CONSTANT_SPECS: List<ConstantSpec> =
    listOf(
      ConstantSpec(
        "DIR",
        "struct",
        Starlark.repr(CONSTANTS.getValue("DIR")),
        "Standard exit directions, such as DIR.north and DIR.west.",
      ),
      ConstantSpec(
        "MODIFIER",
        "struct",
        Starlark.repr(CONSTANTS.getValue("MODIFIER")),
        "Rules modifier points, such as MODIFIER.damage and MODIFIER.to_hit.",
      ),
      ConstantSpec(
        "SIZE",
        "struct",
        Starlark.repr(CONSTANTS.getValue("SIZE")),
        "Track size constants for rooms: SIZE.cramped, SIZE.normal, SIZE.large.",
      ),
      ConstantSpec(
        "SENSE",
        "struct",
        Starlark.repr(CONSTANTS.getValue("SENSE")),
        "Perception senses (SENSE.sight, SENSE.sound, declared senses) and SENSE.all.",
      ),
      ConstantSpec(
        "DOOR",
        "struct",
        Starlark.repr(CONSTANTS.getValue("DOOR")),
        "Door states: DOOR.open, DOOR.closed, DOOR.locked.",
      ),
      ConstantSpec(
        "DAMAGE",
        "struct",
        Starlark.repr(CONSTANTS.getValue("DAMAGE")),
        "A struct of the world's declared damage types, such as DAMAGE.fire; a misspelling fails at that line.",
      ),
      ConstantSpec(
        "SEMI_PERMANENT",
        "int",
        Starlark.repr(CONSTANTS.getValue("SEMI_PERMANENT")),
        "A duration (in pulses) that effectively never ends; accepted wherever a timed affect takes a duration.",
      ),
    )

  val EFFECT_NAMES: Set<String> =
    setOf(
      "damage",
      "heal",
      "apply_affect",
      "dispel",
      "knockback",
      "pull",
      "stun",
      "summon",
      "sequence",
    )

  val BEHAVIOR_NAMES: Set<String> =
    setOf(
      "wander",
      "aggressive",
      "assist",
      "retarget",
      "flee",
      "guard",
      "follow",
      "pursue",
      "sentinel",
      "scavenge",
      "heel",
      "defend",
    )

  val PACKAGE_SPEC =
    DeclSpec(
      name = "package",
      doc = "Declares the package. Must be called exactly once, in package.star.",
      params =
        listOf(
          DeclParam(
            "api_version",
            "int",
            "The script API version this package targets.",
            required = true,
          ),
          DeclParam(
            "kind",
            "str",
            "Package kind: 'zone', 'lib', 'rules' or 'skills'.",
            required = true,
            allowedValues = PackageKind.entries.map { it.word },
          ),
          DeclParam(
            "title",
            "str",
            "Human-readable title of the package.",
            default = "package path",
          ),
          DeclParam(
            "reset_world_ticks",
            "int",
            "World ticks (30 s each) between zone resets, or None for no periodic reset.",
            default = "None",
          ),
          DeclParam(
            "shared_namespaces",
            "list[str]",
            "Attribute namespaces this privileged (lib or rules) package may write across packages.",
            default = "[]",
          ),
          DeclParam(
            "modifiers",
            "dict[str, function]",
            "Zone-wide rules modifiers by modifier point name.",
            default = "{}",
            allowedValues = POINTS,
          ),
          DeclParam(
            "expected_tombstones",
            "list[label]",
            "Prototype labels intentionally removed in this version, for migration checks.",
            default = "[]",
          ),
          DeclParam(
            "migrate_noop",
            "list[label]",
            "Prototype labels whose version rose without needing a migrate() function.",
            default = "[]",
          ),
        ),
    )

  val ROOM_SPEC =
    DeclSpec(
      name = "room",
      doc = "Declares a room.",
      hookOwner = OwnerKind.ROOM,
      positionalError = "room() takes the declaration name as its only positional argument",
      params =
        listOf(
          DeclParam(
            "id",
            "str",
            "The room's declaration name within the package.",
            positional = true,
            keyword = false,
            required = true,
          ),
          DeclParam("name", "str", "Display name shown to players.", default = "id with spaces"),
          DeclParam("desc", "str", "Room description shown when looking.", default = "''"),
          DeclParam(
            "size",
            "str",
            "Track size: 'cramped' (1 slot), 'normal' (3 slots) or 'large' (6 slots).",
            default = "'normal'",
            allowedValues = TRACK_SIZES,
          ),
          DeclParam(
            "regions",
            "list[str]",
            "Region tags that apply their modifiers in this room.",
            default = "[]",
          ),
          DeclParam(
            "tags",
            "list[str]",
            "Tags for plugin restrictions and scripts.",
            default = "[]",
          ),
          DeclParam(
            "flags",
            "list[str]",
            "Room flags such as 'always_active' or 'dead_end_ok'.",
            default = "[]",
            allowedValues = ROOM_FLAGS,
          ),
          DeclParam(
            "exits",
            "list[exit]",
            "Exits leading out of this room, built with exit(...).",
            default = "[]",
          ),
          DeclParam(
            "spawns",
            "list[spawn]",
            "Mob spawn rules for this room, built with spawn(...).",
            default = "[]",
          ),
          DeclParam(
            "commands",
            "dict[str, function]",
            "Room-local commands mapping verb name to fn(ctx).",
            default = "{}",
          ),
          DeclParam(
            "hooks",
            "dict[str, function]",
            "Custom hooks, by name, run with ctx.call_hook.",
            default = "{}",
          ),
          DeclParam(
            "modifiers",
            "dict[str, function]",
            "Room rules modifiers by modifier point name.",
            default = "{}",
            allowedValues = POINTS,
          ),
          DeclParam(
            "version",
            "int",
            "Prototype schema version for persisted state migration.",
            default = "1",
          ),
        ),
    )

  val EXIT_SPEC =
    DeclSpec(
      name = "exit",
      doc = "An exit, for room(exits=[...]).",
      positionalError = "exit() takes a direction and an optional destination",
      params =
        listOf(
          DeclParam(
            "direction",
            "str",
            "Exit direction name, such as 'north', 'up' or a custom word.",
            positional = true,
            keyword = false,
            required = true,
          ),
          DeclParam(
            "to",
            "label",
            "Destination room label.",
            positional = true,
            keyword = true,
            required = true,
          ),
          DeclParam(
            "entry_index",
            "int",
            "Track index in the destination room where travellers arrive, or -1 for the back.",
            default = "0",
          ),
          DeclParam(
            "tags",
            "list[str]",
            "Tags the rules read, such as 'soundproof' or 'sealed', to price sensing across the exit.",
            default = "[]",
          ),
          DeclParam(
            "door",
            "door",
            "Optional door on this exit, built with door(...).",
            default = "None",
          ),
          DeclParam(
            "one_way",
            "bool",
            "True if this exit intentionally has no return exit.",
            default = "False",
          ),
        ),
    )

  val DOOR_SPEC =
    DeclSpec(
      name = "door",
      doc = "A door, for exit(door=...).",
      params =
        listOf(
          DeclParam(
            "state",
            "str",
            "Initial door state: 'open', 'closed' or 'locked'.",
            default = "'closed'",
            allowedValues = DOOR_STATES,
          ),
          DeclParam(
            "key",
            "label",
            "Item prototype label required to lock or unlock the door.",
            default = "None",
          ),
          DeclParam(
            "tags",
            "list[str]",
            "Tags the rules read, such as 'heavy', 'creaky' or 'transparent', to price sensing and muffling.",
            default = "[]",
          ),
        ),
    )

  val SPAWN_SPEC =
    DeclSpec(
      name = "spawn",
      doc = "A spawn rule, for room(spawns=[...]).",
      positionalError = "spawn() takes the mob's label as its only positional argument",
      params =
        listOf(
          DeclParam(
            "label",
            "label",
            "Mob prototype label to spawn.",
            positional = true,
            keyword = false,
            required = true,
          ),
          DeclParam(
            "count",
            "int",
            "Maximum number of instances maintained by this rule.",
            default = "1",
          ),
          DeclParam(
            "respawn_pulses",
            "int",
            "Pulses before a slain mob respawns.",
            default = "1200",
          ),
          DeclParam(
            "at_index",
            "int",
            "Track index in the room where the mob spawns.",
            default = "0",
          ),
        ),
    )

  val LOOT_SPEC =
    DeclSpec(
      name = "loot",
      doc = "A loot roll, for mob(loot=[...]). chance is a percent from 0 to 100.",
      positionalError = "loot() takes the item's label as its only positional argument",
      params =
        listOf(
          DeclParam(
            "label",
            "label",
            "Item prototype label dropped on death.",
            positional = true,
            keyword = false,
            required = true,
          ),
          DeclParam("chance", "int", "Drop chance in percent, 0 to 100.", default = "100"),
          DeclParam(
            "count",
            "int",
            "Number of items dropped when the roll succeeds.",
            default = "1",
          ),
        ),
    )

  val CONTAINER_SPEC =
    DeclSpec(
      name = "container",
      doc = "Container settings, for item(container=...).",
      params =
        listOf(
          DeclParam(
            "capacity",
            "int",
            "Maximum number of items the container holds.",
            default = "10",
          ),
          DeclParam(
            "max_weight",
            "int",
            "Maximum total weight the container holds.",
            default = "100",
          ),
          DeclParam(
            "state",
            "str",
            "Initial container state: 'open', 'closed' or 'locked'.",
            default = "'open'",
            allowedValues = DOOR_STATES,
          ),
          DeclParam(
            "key",
            "label",
            "Item prototype label required to lock or unlock the container.",
            default = "None",
          ),
        ),
    )

  val ITEM_AFFECT_SPEC =
    DeclSpec(
      name = "item_affect",
      doc = "An affect an item carries, for item(affects=[...]).",
      positionalError = "item_affect() takes the affect name as its only positional argument",
      params =
        listOf(
          DeclParam(
            "name",
            "str",
            "The affect's name.",
            positional = true,
            keyword = false,
            required = true,
          ),
          DeclParam(
            "applies",
            "str",
            "Where the affect reaches its holder: 'worn' or 'carried'.",
            default = "'worn'",
            allowedValues = listOf("worn", "carried"),
          ),
          DeclParam(
            "stat_deltas",
            "stats",
            "Stat adjustments applied while the affect reaches the holder, built with stats(...).",
            default = "stats()",
          ),
          DeclParam(
            "sense_deltas",
            "dict[str, int]",
            "Sense score changes by channel while the affect reaches the holder.",
            default = "{}",
          ),
          DeclParam(
            "max_vital_deltas",
            "vitals",
            "Vital maximum adjustments applied while the affect reaches the holder, built with vitals(...).",
            default = "vitals()",
          ),
          DeclParam(
            "resist_deltas",
            "resists",
            "Damage resistance adjustments applied while the affect reaches the holder, built with resists(...).",
            default = "resists()",
          ),
          DeclParam(
            "modifiers",
            "dict[str, function]",
            "Rules modifiers active while the affect reaches the holder.",
            default = "{}",
            allowedValues = POINTS,
          ),
          DeclParam(
            "flags",
            "list[str]",
            "Status flags applied to the holder while the affect reaches it.",
            default = "[]",
          ),
          DeclParam(
            "procs",
            "list[proc]",
            "Combat procs that fire during auto-attack swings.",
            default = "[]",
          ),
        ),
    )

  val OUTCOME_SPEC =
    DeclSpec(
      name = "outcome",
      doc = "A weighted outcome in a proc's outcome table.",
      positionalError = "outcome() takes the weight as its only positional argument",
      params =
        listOf(
          DeclParam(
            "weight",
            "int",
            "Probability weight, a percent from 0 to 100.",
            positional = true,
            default = "100",
          ),
          DeclParam(
            "skill",
            "label",
            "Skill or spell triggered on this outcome.",
            default = "None",
          ),
          DeclParam(
            "effect",
            "effect",
            "Effect primitive applied on this outcome.",
            default = "None",
          ),
          DeclParam(
            "target",
            "str",
            "Target for the outcome: 'enemy', 'self' or 'none'.",
            default = "None",
            allowedValues = listOf("enemy", "self", "none"),
          ),
          DeclParam(
            "message",
            "str",
            "Message sent to the bearer instead of combat.proc.self.",
            default = "None",
          ),
        ),
    )

  val PROC_SPEC =
    DeclSpec(
      name = "proc",
      doc = "A combat proc that fires during auto-attack swings.",
      positionalError = "proc() takes 'on' as its only positional argument",
      params =
        listOf(
          DeclParam(
            "on",
            "str",
            "When the proc fires: 'hit', 'struck' or 'avoid'.",
            positional = true,
            keyword = true,
            required = true,
            allowedValues = listOf("hit", "struck", "avoid"),
          ),
          DeclParam("chance", "int", "Chance of firing, a percent from 0 to 100.", default = "100"),
          DeclParam("skill", "label", "Skill or spell triggered by the proc.", default = "None"),
          DeclParam("effect", "effect", "Effect primitive applied by the proc.", default = "None"),
          DeclParam(
            "target",
            "str",
            "Target for the outcome: 'enemy', 'self' or 'none'.",
            default = "None",
            allowedValues = listOf("enemy", "self", "none"),
          ),
          DeclParam(
            "message",
            "str",
            "Message sent to the bearer instead of combat.proc.self.",
            default = "None",
          ),
          DeclParam("outcomes", "list[outcome]", "Weighted table of outcomes.", default = "None"),
        ),
    )

  val MOB_SPEC =
    DeclSpec(
      name = "mob",
      doc = "Declares a mob prototype.",
      hookOwner = OwnerKind.MOB,
      positionalError = "mob() takes the declaration name as its only positional argument",
      params =
        listOf(
          DeclParam(
            "id",
            "str",
            "The mob's declaration name within the package.",
            positional = true,
            keyword = false,
            required = true,
          ),
          DeclParam("name", "str", "Display name shown to players.", default = "id with spaces"),
          DeclParam(
            "keywords",
            "list[str]",
            "Words players can use to target this mob.",
            default = "words of name",
          ),
          DeclParam("desc", "str", "Description shown when looking at the mob.", default = "''"),
          DeclParam("level", "int", "Mob level.", default = "1"),
          DeclParam(
            "stats",
            "stats",
            "Base stats the mob starts from, built with stats(...).",
            default = "stats()",
          ),
          DeclParam(
            "vitals",
            "vitals",
            "Maximum vitals the mob starts from, built with vitals(...).",
            default = "vitals()",
          ),
          DeclParam(
            "resists",
            "resists",
            "Damage resistance percentage by damage type, built with resists(...).",
            default = "resists()",
          ),
          DeclParam(
            "skills",
            "dict[label, int]",
            "Known skills mapping skill label to proficiency (0..100).",
            default = "{}",
          ),
          DeclParam(
            "senses",
            "dict[str, int]",
            "Base sense score by channel, merged over sight and sound at 1; 0 senses nothing on that channel.",
            default = "{}",
          ),
          DeclParam(
            "memory_pulses",
            "int",
            "Combat memory window in pulses, overriding module(memory_pulses); 0 keeps none, None uses the world's.",
            default = "None",
          ),
          DeclParam(
            "think",
            "function",
            "Custom AI function fn(ctx) run on each think cycle.",
            default = "None",
          ),
          DeclParam("think_every", "int", "Pulses between AI think cycles.", default = "4"),
          DeclParam(
            "leash",
            "int | None",
            "Most exits from its home room the mob's own movement goes; None for no leash.",
            default = "None",
          ),
          DeclParam(
            "hooks",
            "dict[str, function]",
            "Custom hooks, by name, run with ctx.call_hook.",
            default = "{}",
          ),
          DeclParam(
            "behaviors",
            "list[behavior]",
            "Built-in behaviors such as wander(...) or aggressive(...).",
            default = "[]",
          ),
          DeclParam(
            "dormant_recovery",
            "str",
            "How the mob recovers when its room wakes from dormancy.",
            default = "'full'",
            allowedValues = DORMANT_MODES,
          ),
          DeclParam(
            "loot",
            "list[loot]",
            "Loot rules rolled when the mob dies, built with loot(...).",
            default = "[]",
          ),
          DeclParam(
            "traits",
            "list[str]",
            "Traits defined by the world's plugins.",
            default = "[]",
          ),
          DeclParam(
            "factions",
            "list[label]",
            "Faction labels the mob belongs to; when absent, its own prototype label.",
            default = "None",
          ),
          DeclParam(
            "version",
            "int",
            "Prototype schema version for persisted state migration.",
            default = "1",
          ),
        ),
    )

  val ITEM_SPEC =
    DeclSpec(
      name = "item",
      doc = "Declares an item prototype.",
      hookOwner = OwnerKind.ITEM,
      positionalError = "item() takes the declaration name as its only positional argument",
      params =
        listOf(
          DeclParam(
            "id",
            "str",
            "The item's declaration name within the package.",
            positional = true,
            keyword = false,
            required = true,
          ),
          DeclParam("name", "str", "Display name shown to players.", default = "id with spaces"),
          DeclParam(
            "plural",
            "str",
            "Display name for stacks of 2 or more, such as 'gold coins'.",
            default = "None",
          ),
          DeclParam(
            "keywords",
            "list[str]",
            "Words players can use to target this item.",
            default = "words of name",
          ),
          DeclParam("desc", "str", "Description shown when examining the item.", default = "''"),
          DeclParam(
            "kind",
            "str",
            "Item category, such as 'misc', 'melee', 'ranged', 'armor' or 'key'.",
            default = "'misc'",
          ),
          DeclParam(
            "range",
            "str",
            "Weapon reach band: 'melee', 'near' or 'far'.",
            default = "'melee'",
            allowedValues = BANDS,
          ),
          DeclParam("weight", "int", "Weight of one item.", default = "1"),
          DeclParam("value", "int", "Base price of one item; 0 by default.", default = "0"),
          DeclParam(
            "wear",
            "str",
            "Equipment slot or slot family the item is worn in.",
            default = "None",
          ),
          DeclParam("wield", "str", "Alias for wear, for weapons.", default = "None"),
          DeclParam(
            "slot_cost",
            "int",
            "How many slots of its family the item takes up; at least 1.",
            default = "1",
          ),
          DeclParam("dice", "dice", "Weapon damage dice such as '1d6+1'.", default = "None"),
          DeclParam(
            "damage_type",
            "str",
            "Weapon damage type, a declared damage type such as DAMAGE.slash.",
            default = "unarmed_damage_type",
          ),
          DeclParam(
            "stat_deltas",
            "stats",
            "Stat adjustments applied while the item is worn, built with stats(...).",
            default = "stats()",
          ),
          DeclParam(
            "sense_deltas",
            "dict[str, int]",
            "Sense score changes by channel while the item is worn.",
            default = "{}",
          ),
          DeclParam(
            "max_vital_deltas",
            "vitals",
            "Vital maximum adjustments applied while the item is worn, built with vitals(...).",
            default = "vitals()",
          ),
          DeclParam(
            "resist_deltas",
            "resists",
            "Damage resistance adjustments applied while the item is worn, built with resists(...).",
            default = "resists()",
          ),
          DeclParam(
            "tags",
            "list[str]",
            "Tags for plugin restrictions and scripts.",
            default = "[]",
          ),
          DeclParam(
            "stackable",
            "bool",
            "True if identical items merge into a stack.",
            default = "False",
          ),
          DeclParam(
            "decay_pulses",
            "int",
            "Pulses before the item decays, or None for permanent items.",
            default = "None",
          ),
          DeclParam(
            "container",
            "container",
            "Container settings, built with container(...).",
            default = "None",
          ),
          DeclParam(
            "commands",
            "dict[str, function]",
            "Item-local commands mapping verb name to fn(ctx).",
            default = "{}",
          ),
          DeclParam(
            "hooks",
            "dict[str, function]",
            "Custom hooks, by name, run with ctx.call_hook.",
            default = "{}",
          ),
          DeclParam(
            "equip_affects",
            "list[str | dict]",
            "Affects applied when the item is worn: a flag held while worn, or " +
              "{'flag': str, 'pulses': int} for a timed one. 'stunned' must be timed.",
            default = "[]",
          ),
          DeclParam(
            "affects",
            "list[item_affect]",
            "Affects every instance carries, built with item_affect(...).",
            default = "[]",
          ),
          DeclParam(
            "variants",
            "dict[str, str]",
            "Related item prototypes by free-form name, for transform.",
            default = "{}",
          ),
          DeclParam(
            "charges",
            "int",
            "Uses before the item is spent, at least 1; None for unlimited.",
            default = "None",
          ),
          DeclParam(
            "empty",
            "str",
            "After the last charge: 'destroy', 'keep', or the name of one of the item's variants.",
            default = "'destroy'",
          ),
          DeclParam(
            "use_effect",
            "effect",
            "Effect primitive that use applies, with the user as caster.",
            default = "None",
          ),
          DeclParam(
            "use_target",
            "str",
            "Whom use_effect acts on: 'enemy', 'ally', 'self', 'item' or 'none'.",
            default = "'self'",
            allowedValues = TARGET_KINDS,
          ),
          DeclParam(
            "use_lag",
            "int",
            "Recovery lag in pulses after a use; at least 0.",
            default = "0",
          ),
          DeclParam(
            "modifiers",
            "dict[str, function]",
            "Rules modifiers active while the item is worn.",
            default = "{}",
            allowedValues = POINTS,
          ),
          DeclParam(
            "procs",
            "list[proc]",
            "Combat procs that fire during auto-attack swings.",
            default = "[]",
          ),
          DeclParam(
            "version",
            "int",
            "Prototype schema version for persisted state migration.",
            default = "1",
          ),
        ),
    )

  val SKILL_SPEC =
    DeclSpec(
      name = "skill",
      doc = "Declares a skill or spell.",
      positionalError = "skill() takes the declaration name as its only positional argument",
      params =
        listOf(
          DeclParam(
            "id",
            "str",
            "The skill's declaration name within the package.",
            positional = true,
            keyword = false,
            required = true,
          ),
          DeclParam(
            "kind",
            "str",
            "Skill category, such as 'spell' or 'skill'.",
            default = "'skill'",
          ),
          DeclParam(
            "cost",
            "vitals",
            "Vitals spent on cast, built with vitals(...).",
            default = "vitals()",
          ),
          DeclParam(
            "warmup",
            "int",
            "Pulses of casting warmup before the skill resolves.",
            default = "0",
          ),
          DeclParam(
            "lag",
            "int",
            "Recovery wait-state pulses after the skill resolves.",
            default = "0",
          ),
          DeclParam(
            "range",
            "str",
            "Maximum target range.",
            default = "'melee'",
            allowedValues = SKILL_RANGES,
          ),
          DeclParam(
            "target",
            "str",
            "Kind of target the skill accepts.",
            default = "'enemy'",
            allowedValues = TARGET_KINDS,
          ),
          DeclParam(
            "channels",
            "list[str]",
            "Channels required and emitted when casting.",
            default = "[]",
          ),
          DeclParam(
            "interrupt",
            "dict[str, any]",
            "Warmup interruption rules ('damage_pct', 'stun').",
            default = "{'damage_pct': None, 'stun': True}",
            allowedValues = listOf("damage_pct", "stun"),
          ),
          DeclParam(
            "attack_while_casting",
            "bool",
            "True if auto-attacks continue during warmup.",
            default = "False",
          ),
          DeclParam(
            "effect",
            "effect",
            "Declarative effect primitive applied when the skill resolves.",
            default = "None",
          ),
          DeclParam(
            "resolve",
            "function",
            "Custom function fn(ctx) run when the skill resolves.",
            default = "None",
          ),
          DeclParam(
            "tags",
            "list[str]",
            "Tags for plugin restrictions and scripts.",
            default = "[]",
          ),
          DeclParam(
            "unrecognized",
            "dict[str, str]",
            "Lines for players who don't recognize the skill: begin, room, victim, resisted_room, resisted_victim, noun.",
            default = "{}",
          ),
        ),
    )

  val REGION_SPEC =
    DeclSpec(
      name = "region",
      doc =
        "Declares the modifiers of a region, a tag rooms carry with room(regions=[...]). Region names are world-wide.",
      positionalError = "region() takes the declaration name as its only positional argument",
      params =
        listOf(
          DeclParam(
            "id",
            "str",
            "World-wide region tag name.",
            positional = true,
            keyword = false,
            required = true,
          ),
          DeclParam(
            "modifiers",
            "dict[str, function]",
            "Rules modifiers active in rooms carrying this region tag.",
            default = "{}",
            allowedValues = POINTS,
          ),
        ),
    )

  val FACTION_SPEC =
    DeclSpec(
      name = "faction",
      doc = "Declares a faction and its declared opinion of other factions.",
      positionalError = "faction() takes the declaration name as its only positional argument",
      params =
        listOf(
          DeclParam(
            "id",
            "str",
            "The faction's declaration name within the package.",
            positional = true,
            keyword = false,
            required = true,
          ),
          DeclParam(
            "standing",
            "dict[label, int]",
            "Declared opinion of other factions, by faction label, each in -100..100.",
            default = "{}",
          ),
          DeclParam("doc", "str", "Documentation describing the faction.", default = "''"),
        ),
    )

  val GMCP_PACKAGE_SPEC =
    DeclSpec(
      name = "gmcp_package",
      doc =
        "Declares a GMCP package this package sends with ctx.gmcp. lib and rules packages only.",
      positionalError = "gmcp_package() takes the package name as its only positional argument",
      params =
        listOf(
          DeclParam(
            "name",
            "str",
            "The GMCP package name, two or more dot-separated parts.",
            positional = true,
            keyword = false,
            required = true,
          ),
          DeclParam("doc", "str", "Shown in the docs.", default = "\"\""),
        ),
    )

  val GLOBAL_COMMAND_SPEC =
    DeclSpec(
      name = "global_command",
      doc = "Registers a command available everywhere. lib and rules packages only.",
      positionalError = "global_command() takes name, fn and an optional lane",
      params =
        listOf(
          DeclParam(
            "name",
            "str",
            "Command verb players type.",
            positional = true,
            keyword = true,
            required = true,
          ),
          DeclParam(
            "fn",
            "function",
            "Top-level handler function fn(ctx).",
            positional = true,
            keyword = true,
            required = true,
          ),
          DeclParam(
            "lane",
            "str",
            "Execution lane: 'fast' (immediate) or 'tick' (queued on the actor).",
            positional = true,
            keyword = true,
            default = "'fast'",
            allowedValues = listOf("fast", "tick"),
          ),
        ),
    )

  val GLOBAL_HOOK_SPEC =
    DeclSpec(
      name = "global_hook",
      doc =
        "Registers a hook that fires for every actor, wherever it is. lib and rules packages only.",
      positionalError = "global_hook() takes the hook name and fn",
      params =
        listOf(
          DeclParam(
            "name",
            "str",
            "The hook to run globally: 'on_level_change', 'on_encumbrance_change', 'on_group_change', 'on_owner_change', 'on_disengage', 'on_death' or 'on_skill_use'.",
            positional = true,
            keyword = true,
            required = true,
          ),
          DeclParam(
            "fn",
            "function",
            "Top-level handler function fn(ctx).",
            positional = true,
            keyword = true,
            required = true,
          ),
        ),
    )

  val STRUCT_SPEC =
    DeclSpec(
      name = "struct",
      doc =
        "An immutable record of named fields, for example a library's exported functions: struct(start = _start).",
      extraKeywords = true,
      params = emptyList(),
    )

  val STANDING_DELTA_SPEC =
    DeclSpec(
      name = "standing_delta",
      doc = "Builds a standing delta for rules.standing_change.",
      params =
        listOf(
          DeclParam(
            "holder",
            "Actor | str",
            "Mob or faction label holding the opinion.",
            positional = true,
            required = true,
          ),
          DeclParam(
            "subject",
            "Actor | str",
            "Actor or faction label the opinion is about.",
            positional = true,
            required = true,
          ),
          DeclParam("amount", "int", "Change in standing.", positional = true, required = true),
        ),
    )

  val DECLARATION_SPECS: List<DeclSpec> =
    listOf(
      PACKAGE_SPEC,
      ROOM_SPEC,
      EXIT_SPEC,
      DOOR_SPEC,
      SPAWN_SPEC,
      LOOT_SPEC,
      CONTAINER_SPEC,
      ITEM_AFFECT_SPEC,
      PROC_SPEC,
      OUTCOME_SPEC,
      MOB_SPEC,
      ITEM_SPEC,
      SKILL_SPEC,
      REGION_SPEC,
      FACTION_SPEC,
      GMCP_PACKAGE_SPEC,
      GLOBAL_COMMAND_SPEC,
      GLOBAL_HOOK_SPEC,
      STRUCT_SPEC,
      STANDING_DELTA_SPEC,
    ) + SCHEMA_SPECS
}
