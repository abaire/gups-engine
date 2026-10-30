package gups.pluginapi

import net.starlark.java.eval.StarlarkValue

// The plugin interface. Unstable: no compatibility promises.

object PluginApi {
  /**
   * Changes whenever this interface or the facade changes; plugins built for another version are
   * refused.
   */
  const val VERSION: Int = 1
}

/** A plugin, found through `ServiceLoader` in `plugins/<id>.jar`. */
interface GupsPlugin {
  /** `[a-z][a-z0-9_]*`, unique, and the id `MODULE.gups` pins the jar under. */
  val id: String

  /** Must equal [PluginApi.VERSION]. */
  val pluginApiVersion: Int

  val doc: String
    get() = ""

  /**
   * Engine features (`combat`, `track`, `skills`, `leveling`) the world must have on. A plugin
   * whose features are off is skipped with an error.
   */
  val requiredFeatures: Set<String>
    get() = emptySet()

  /**
   * Registers everything the plugin adds. Registration is atomic: if it throws, the plugin adds
   * nothing.
   */
  fun register(r: PluginRegistry)
}

interface PluginRegistry {
  /** Installs [value] as `<id>.<name>` as is. */
  fun addBuiltin(name: String, value: StarlarkValue, doc: String)

  /**
   * A builtin `<id>.<name>(...)` whose arguments are bound to [params] and converted to plain
   * values.
   */
  fun addBuiltin(
    name: String,
    doc: String,
    params: List<PluginParam> = emptyList(),
    returnType: String = "any",
    call: (world: PluginWorld, positional: List<Any?>, named: Map<String, Any?>) -> Any?,
  )

  fun addCommand(
    name: String,
    lane: PluginLane,
    role: PluginRole = PluginRole.PLAYER,
    doc: String,
    run: (PluginCommandCall) -> Unit,
  )

  fun addCommand(
    name: String,
    lane: PluginLane,
    role: PluginRole = PluginRole.PLAYER,
    doc: String,
    category: String,
    run: (PluginCommandCall) -> Unit,
  ) {
    addCommand(name, lane, role, doc, run)
  }

  /** Declares a custom command category with optional display title, sort weight, and help. */
  fun addCategory(name: String, title: String = name, order: Int? = null, help: String? = null) {}

  /** An effect primitive `<id>.<name>(...)` for `skill(effect = …)` and `sequence([...])`. */
  fun addEffectPrimitive(name: String, doc: String, params: List<PluginParam>, apply: PluginEffect)

  /** A GMCP state package (at least two dot-separated parts), sent in phase 7 when it changes. */
  fun addGmcp(pkg: String, doc: String, produce: (PluginWorld, PluginActor) -> Any?)

  fun onWorldStarted(fn: (PluginWorld) -> Unit)

  fun addTrait(trait: PluginTrait)

  fun setCreation(creation: PluginCreation)

  fun addRestriction(
    point: PluginRestrictionPoint,
    doc: String,
    check: (PluginRestrictionCall) -> String?,
  )
}

/** Mirrors core's command lanes. */
enum class PluginLane {
  FAST,
  TICK,
}

/** Mirrors core's roles. */
enum class PluginRole {
  PLAYER,
  BUILDER,
  INSTRUCTOR,
  ADMIN,
}

data class PluginParam(
  val name: String,
  /** Documentation only, for example "int" or "room". */
  val type: String,
  val doc: String,
  val required: Boolean = true,
  val defaultValue: Any? = null,
)

data class PluginCommandCall(val world: PluginWorld, val actor: PluginActor, val args: String)

data class PluginEffectCall(
  val world: PluginWorld,
  val caster: PluginActor,
  /** Null for `target = "none"` skills. */
  val target: PluginActor?,
  val args: Map<String, Any?>,
)

fun interface PluginEffect {
  fun apply(call: PluginEffectCall)
}

// Character creation, traits and restrictions.
data class PluginOption(
  val id: String,
  val label: String,
  val summary: String,
  val help: String = "",
  val traits: List<String> = emptyList(),
)

data class PluginChoice(
  val id: String,
  val title: String,
  val options: List<PluginOption>,
  val prompt: String = "Choose your ${title.lowercase()}:",
)

interface PluginCreation {
  val choices: List<PluginChoice>

  fun available(world: PluginWorld, choice: String, answers: Map<String, String>): List<String> =
    choices.first { it.id == choice }.options.map { it.id }

  fun validate(world: PluginWorld, answers: Map<String, String>): String? = null

  fun startRoom(world: PluginWorld, answers: Map<String, String>): String? = null

  fun created(world: PluginWorld, actor: PluginActor, newCharacter: Boolean) {}
}

data class PluginTrait(
  val name: String,
  val label: String = name.replaceFirstChar { it.uppercase() },
  val doc: String = "",
  val statDeltas: Map<String, Int> = emptyMap(),
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  val resistDeltas: Map<String, Int> = emptyMap(),
  val flags: Set<String> = emptySet(),
  val modifiers: Map<PluginModifierPoint, PluginModifier> = emptyMap(),
  /** Sense changes by channel name, built-in or world-declared. */
  val senseDeltas: Map<String, Int> = emptyMap(),
)

enum class PluginModifierPoint {
  TO_HIT,
  DAMAGE,
  INITIATIVE,
  MOVE_COST,
  REGEN,
}

data class PluginModifierCall(
  val world: PluginWorld,
  val point: PluginModifierPoint,
  val actor: PluginActor,
  val other: PluginActor?,
  val weapon: PluginItem?,
  val room: PluginRoom,
)

fun interface PluginModifier {
  fun modify(call: PluginModifierCall, base: Any): Any
}

enum class PluginRestrictionPoint {
  WEAR,
  ENTER,
  CAST,
  LEARN,
}

data class PluginRestrictionCall(
  val world: PluginWorld,
  val point: PluginRestrictionPoint,
  val actor: PluginActor,
  val item: PluginItem? = null,
  val slot: String? = null,
  val room: PluginRoom? = null,
  val from: PluginRoom? = null,
  val direction: String? = null,
  val skill: PluginSkill? = null,
  val target: PluginEntity? = null,
  val proficiency: Int? = null,
)
