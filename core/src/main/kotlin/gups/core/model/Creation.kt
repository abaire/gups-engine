package gups.core.model

import gups.core.Label
import gups.core.rules.RulesContext
import gups.core.world.World

/** An selectable option within a character creation choice. */
data class CreationOption(
  /** Unique option identifier within the choice. */
  val id: String,
  /** User-facing display title of the option. */
  val label: String,
  /** Brief summary description of the option. */
  val summary: String,
  /** Detailed help text explaining the option's lore or mechanical impact. */
  val help: String = "",
  /** Trait names granted to the character when this option is selected. */
  val traits: List<String> = emptyList(),
)

/** A discrete prompt or decision step presented to players during character creation. */
data class CreationChoice(
  /** Unique identifier of the choice step. */
  val id: String,
  /** User-facing category title (e.g. `Class`, `Ancestry`). */
  val title: String,
  /** Prompt text displayed when requesting selection. */
  val prompt: String = "Choose your ${title.lowercase()}:",
  /** List of available options for this choice. */
  val options: List<CreationOption>,
)

/**
 * Character creation lifecycle provider seam implemented by world plugins. At most one creation
 * provider exists per world instance.
 */
interface CreationProvider {
  /** Ordered list of character creation choices presented to players. */
  val choices: List<CreationChoice>

  /** Returns option IDs currently available for [choice] given previously selected [answers]. */
  fun available(world: World, choice: String, answers: Map<String, String>): List<String>

  /**
   * Validates the completed set of creation [answers]; returns null if valid, or a refusal message.
   */
  fun validate(world: World, answers: Map<String, String>): String?

  /**
   * Returns the starting room label for a character with [answers], or null to use the default
   * world start.
   */
  fun startRoom(world: World, answers: Map<String, String>): Label?

  /**
   * Callback invoked when character creation concludes; [newCharacter] is false when updating
   * missing choices.
   */
  fun created(world: World, player: Player, newCharacter: Boolean)
}

/** A permanent trait that modifies an actor's stats, vitals, senses, or rules calculations. */
class Trait(
  /** Unique internal identifier name of the trait. */
  val name: String,
  /** User-facing display label of the trait. */
  val label: String = name.replaceFirstChar { it.uppercase() },
  /** Documentation explaining the trait's effects. */
  val doc: String = "",
  /** Attribute deltas contributed by this trait. */
  val statDeltas: Map<String, Int> = emptyMap(),
  /** Vital maximum deltas contributed by this trait. */
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  /** Damage resistance deltas contributed by this trait. */
  val resistDeltas: Map<String, Int> = emptyMap(),
  /** Behavioral flags contributed by this trait. */
  val flags: Set<String> = emptySet(),
  /** Rules modifiers contributed by this trait. */
  val modifiers: Map<ModifierPoint, TraitModifier> = emptyMap(),
  /** Perception sense deltas contributed by this trait. */
  val senseDeltas: Map<Channel, Int> = emptyMap(),
)

/** Modifier callback contributed by a [Trait] modifying a rules calculation. */
fun interface TraitModifier {
  /** Applies trait modification to [base] value within [c] context. */
  fun modify(c: RulesContext, base: Any): Any
}

/** Interception points where world restrictions can prevent gameplay actions. */
enum class RestrictionPoint {
  /**
   * Equipping an item into an equipment slot, and the re-check of worn items after a change of
   * choices or traits, a reload or a restore, where a refusal makes the item inactive.
   */
  WEAR,

  /** Entering a room through an exit. */
  ENTER,

  /** Initiating a skill or spell cast. */
  CAST,

  /** Learning or upgrading a skill. */
  LEARN,

  /**
   * Taking a worn item off by command: `remove`, and the item a `wear` or `wield` would swap out of
   * an occupied slot (including a full slot family). Trusted moves (verbs, death, `transform`)
   * don't run it.
   */
  REMOVE,

  /** Dropping a carried item by command. Trusted moves (verbs, death, decay) don't run it. */
  DROP,

  /** Giving a carried item by command to another actor. Trusted moves (verbs) don't run it. */
  GIVE,

  /** Putting a carried item by command into a container. Trusted moves (verbs) don't run it. */
  PUT,
}

/** Interception context passed when evaluating a gameplay [Restriction]. */
data class RestrictionRequest(
  /** Interception point being evaluated. */
  val point: RestrictionPoint,
  /** Actor attempting the restricted action. */
  val actor: Actor,
  /** Item being equipped, removed, dropped, given or put, or null. */
  val item: Item? = null,
  /** Equipment slot target, or null. */
  val slot: String? = null,
  /** Destination room being entered, or null. */
  val room: Room? = null,
  /** Origin room when traversing exits, or null. */
  val from: Room? = null,
  /** Direction traversed, or null. */
  val direction: Direction? = null,
  /** Skill or spell being cast or learned, or null. */
  val skill: SkillProto? = null,
  /**
   * Target entity of the skill or action (the receiver of a give, the container of a put), or null.
   */
  val target: Entity? = null,
  /** Proficiency rating when learning a skill, or null. */
  val proficiency: Int? = null,
)

/** Rule restriction check registered by plugins or world configuration. */
fun interface Restriction {
  /**
   * Evaluates whether [r] is permitted; returns null to permit or a refusal message string to deny.
   */
  fun check(world: World, r: RestrictionRequest): String?
}
