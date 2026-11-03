package gups.pluginapi

import net.starlark.java.eval.StarlarkValue

// The plugin interface. Unstable: no compatibility promises.

/**
 * The plugin interface version.
 *
 * Incremented whenever breaking changes are introduced to the plugin API or facade interfaces.
 * Plugins built against a mismatched version are refused at server startup.
 */
object PluginApi {
  /** The current plugin API version. */
  const val VERSION: Int = 1
}

/**
 * Service provider interface for engine plugins loaded from `plugins/<id>.jar`.
 *
 * Discovered at startup via [java.util.ServiceLoader]. Plugins can register built-in Starlark
 * functions, custom engine commands, GMCP state providers, traits, character creation steps, and
 * action restrictions.
 *
 * ## Lifecycle & Validation Rules
 * - **Identifier format:** [id] must match `[a-z][a-z0-9_]*`, must be unique across all installed
 *   plugins, must not collide with reserved engine builtins, and must match the key declared in
 *   `MODULE.gups`.
 * - **API version:** [pluginApiVersion] must strictly match [PluginApi.VERSION].
 * - **Required features:** [requiredFeatures] specifies engine features (`combat`, `track`,
 *   `skills`, `leveling`) that must be active in the world. If any required feature is disabled,
 *   the plugin is skipped with a warning.
 * - **Atomic registration:** [register] is executed atomically against [PluginRegistry]. If
 *   registration throws an exception or violates a constraint, all contributions from the plugin
 *   are discarded.
 */
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

/**
 * Registration coordinator passed to [GupsPlugin.register].
 *
 * Provides builder methods to attach plugin capabilities to the engine:
 * - Built-in functions with automatic Starlark-to-Kotlin argument binding and type validation.
 * - Player and staff commands on either fast or tick lanes.
 * - Custom command categories.
 * - Skill effect primitives for scripting sequences.
 * - GMCP out-of-band JSON packages.
 * - Character creation choices and permanent traits.
 * - Action restrictions.
 *
 * Registrations are validated incrementally: any conflict (such as duplicate command names or
 * shadowing engine commands) immediately aborts registration.
 */
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

  /** A GMCP event package (at least two dot-separated parts), sent with PluginWorld.gmcp. */
  fun addGmcpEvent(pkg: String, doc: String)

  /**
   * Receives client GMCP messages for exactly [pkg] (ignoring case). An input package may not use
   * the reserved `Core` root or overlap an engine package or another plugin's package; it may share
   * this plugin's own state and event tree.
   */
  fun onGmcp(pkg: String, doc: String, handle: (PluginGmcpCall) -> Unit)

  /**
   * Joins the handler chain of custom hook [name]: after the entity's own hook, `ctx.call_hook`
   * runs every plugin's handler for the name in plugin-id order. A handler gets a read-only
   * [PluginGmcpWorld], the entity, the arguments and the result so far, all as plain values
   * (entities as entity views), and returns the new result as plain values. A handler that throws
   * records a `plugin` diagnostic and leaves the result unchanged. Names follow the custom hook
   * rules; a plugin registers a name once.
   */
  fun addHook(name: String, doc: String, handle: (PluginHookCall) -> Any?)

  fun onWorldStarted(fn: (PluginWorld) -> Unit)

  fun addTrait(trait: PluginTrait)

  fun setCreation(creation: PluginCreation)

  fun addRestriction(
    point: PluginRestrictionPoint,
    doc: String,
    check: (PluginRestrictionCall) -> String?,
  )

  /**
   * A check that can stop a carried item affect from reaching its holder through a container.
   * [blocks] runs for each container on the path to the affect's item and returns true to stop the
   * affect there; worn and directly carried items are never filtered. The engine caches the result
   * and recomputes it only when custody, item affects, item tags or prototypes change, so [blocks]
   * may read only the container's and item's label, kind, tags and affects, and must be fast and
   * side-effect free. A filter that throws records a `plugin` diagnostic and doesn't block.
   */
  fun addContainmentFilter(doc: String, blocks: (PluginContainmentCall) -> Boolean)
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

/** One GMCP message from a player's client. */
interface PluginGmcpCall {
  val world: PluginGmcpWorld
  val actor: PluginActor
  /** The package as registered. */
  val pkg: String
  /** The body as plain values: String, Long, Double, Boolean, List, Map<String, Any?> or null. */
  val data: Any?

  /**
   * Runs [line] as if the player typed it, and returns whether it was accepted. May be called at
   * most once per message.
   */
  fun command(line: String): Boolean
}

/** One run of a plugin's handler for a custom hook. */
interface PluginHookCall {
  val world: PluginGmcpWorld
  /** The room, actor or item whose hook is running. */
  val entity: PluginEntity
  val name: String
  /** Arguments as plain values; entities appear as PluginRoom, PluginActor or PluginItem. */
  val args: Map<String, Any?>
  /** The result so far: the entity's own hook's, or the previous handler's. */
  val result: Any?
}

data class PluginEffectCall(
  val world: PluginWorld,
  val caster: PluginActor,
  /** Null for `target = "none"` and `target = "item"` skills. */
  val target: PluginActor?,
  val args: Map<String, Any?>,
  /** The target item of an `item` skill, else null. */
  val targetItem: PluginItem? = null,
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
  ADVANCE_LAG,
  RETREAT_LAG,
  RETREAT_FAIL_LAG,
  FLEE_FAIL_LAG,
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
  REMOVE,
  DROP,
  GIVE,
  PUT,
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

/**
 * One containment filter question: does [container] stop [item]'s [affect] from reaching [holder]?
 * Read only the container's and item's label, kind, tags and affects.
 */
data class PluginContainmentCall(
  val world: PluginWorld,
  val holder: PluginActor,
  val container: PluginItem,
  val item: PluginItem,
  val affect: PluginItemAffect,
)
