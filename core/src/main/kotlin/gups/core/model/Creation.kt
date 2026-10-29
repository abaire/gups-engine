package gups.core.model

import gups.core.Label
import gups.core.rules.RulesContext
import gups.core.world.World

data class CreationOption(
  val id: String,
  val label: String,
  val summary: String,
  val help: String = "",
  val traits: List<String> = emptyList(),
)

data class CreationChoice(
  val id: String,
  val title: String,
  val prompt: String = "Choose your ${title.lowercase()}:",
  val options: List<CreationOption>,
)

interface CreationProvider {
  val choices: List<CreationChoice>

  fun available(world: World, choice: String, answers: Map<String, String>): List<String>

  fun validate(world: World, answers: Map<String, String>): String?

  fun startRoom(world: World, answers: Map<String, String>): Label?

  fun created(world: World, player: Player, newCharacter: Boolean)
}

class Trait(
  val name: String,
  val label: String = name.replaceFirstChar { it.uppercase() },
  val doc: String = "",
  val statDeltas: Map<String, Int> = emptyMap(),
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  val resistDeltas: Map<String, Int> = emptyMap(),
  val flags: Set<String> = emptySet(),
  val modifiers: Map<ModifierPoint, TraitModifier> = emptyMap(),
)

fun interface TraitModifier {
  fun modify(c: RulesContext, base: Any): Any
}

enum class RestrictionPoint {
  WEAR,
  ENTER,
  CAST,
  LEARN,
}

data class RestrictionRequest(
  val point: RestrictionPoint,
  val actor: Actor,
  val item: Item? = null,
  val slot: String? = null,
  val room: Room? = null,
  val from: Room? = null,
  val direction: Direction? = null,
  val skill: SkillProto? = null,
  val target: Entity? = null,
  val proficiency: Int? = null,
)

fun interface Restriction {
  fun check(world: World, r: RestrictionRequest): String?
}
