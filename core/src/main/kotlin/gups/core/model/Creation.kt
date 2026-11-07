package gups.core.model

import gups.core.Label
import gups.core.Rng
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

data class StepView(val text: String, val gmcp: Map<String, Any?> = emptyMap())

sealed interface StepReply {
  data class Stay(val message: String?) : StepReply

  data object Done : StepReply
}

interface CreationStep {
  val id: String
  val title: String

  fun applies(world: World, attempt: CreationAttempt): Boolean

  fun show(world: World, attempt: CreationAttempt): StepView

  fun input(world: World, attempt: CreationAttempt, line: String): StepReply

  fun summary(world: World, attempt: CreationAttempt): String?
}

/**
 * Character creation lifecycle provider seam implemented by world plugins. At most one creation
 * provider exists per world instance.
 */
interface CreationProvider {
  /** Ordered list of character creation choices presented to players. */
  val choices: List<CreationChoice>

  val steps: List<CreationStep>
    get() = emptyList()

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

  fun prepare(world: World, player: Player, attempt: CreationAttempt) {}

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

  /** A player giving an order to one of its pets; actor is the owner, target the pet. */
  ORDER,
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

private fun fnv1a64(bytes: ByteArray): Long {
  var hash = 0xcbf29ce484222325uL.toLong()
  for (b in bytes) {
    hash = hash xor (b.toLong() and 0xffL)
    hash *= 0x100000001b3L
  }
  return hash
}

private fun splitMix64(z: Long): Long {
  var x = z
  x = (x xor (x ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
  x = (x xor (x ushr 27)) * 0x94D049BB133111EBuL.toLong()
  return x xor (x ushr 31)
}

/**
 * One attempt at creating a character. Its seed depends only on the world seed, [key] and [count],
 * so cancelling and starting again replays the same draws.
 */
class CreationAttempt(var name: String, val key: String, val count: Int, worldSeed: Long) {
  val seed: Long = splitMix64(fnv1a64("$key#$count".toByteArray(Charsets.UTF_8)) xor worldSeed)

  /**
   * Where the character starts when the provider names no start room. The preview is built where
   * the character would start, so rules that read the room see the same one at the spawn.
   */
  var start: Label? = null

  /** Answers given so far, kept in step with the flow. */
  val answers: MutableMap<String, String> = LinkedHashMap()

  /** Free-form plugin state for the steps; cleared when a choice is answered. */
  val state: MutableMap<String, Any?> = LinkedHashMap()

  /** Ids of the custom steps that returned Done; cleared with [state]. */
  val finished: MutableSet<String> = LinkedHashSet()

  /** The would-be character, built on demand; cleared with [state]. */
  var preview: Player? = null

  /** Set by the spawn when the provider's prepare threw; null otherwise. */
  var prepareError: Throwable? = null

  /** The generator `starting_stats` draws from for this attempt. Each call returns a fresh one. */
  fun startingRng(): Rng = Rng(seed)

  /** An independent deterministic stream for plugin draws. Each call returns a fresh one. */
  fun rng(stream: Int): Rng =
    Rng(splitMix64(seed xor ((stream.toLong() + 1) * 0x9E3779B97F4A7C15uL.toLong())))

  /** Clears everything that depends on the answers: state, finished steps and the preview. */
  fun answersChanged() {
    state.clear()
    finished.clear()
    preview = null
  }

  companion object {
    fun accountKey(accountId: Long) = "a:$accountId"

    fun nameKey(name: String) = "n:${name.lowercase()}"
  }
}
