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
 * - **Required features:** [requiredFeatures] names engine features (`combat`, `track`, `skills`,
 *   `leveling`, `standing`) or features other plugins provide ([providedFeatures]). If any required
 *   feature is neither enabled nor provided by a plugin that loads, the plugin is skipped with a
 *   warning.
 * - **Provided features:** [providedFeatures] are well-formed feature names this plugin offers;
 *   scripts test them with `ctx.has_feature` and other plugins may name them in [requiredFeatures].
 *   They may not equal an engine feature id or a reserved id.
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
   * Features the world must have: engine features (`combat`, `track`, `skills`, `leveling`,
   * `standing`) or features other plugins provide. A plugin whose features are unavailable is
   * skipped with a warning.
   */
  val requiredFeatures: Set<String>
    get() = emptySet()

  /**
   * Feature names this plugin provides, `[a-z][a-z0-9_]*`. Scripts test them with `ctx.has_feature`
   * and other plugins may name them in [requiredFeatures].
   */
  val providedFeatures: Set<String>
    get() = emptySet()

  /**
   * Registers everything the plugin adds. Registration is atomic: if it throws, the plugin adds
   * nothing.
   */
  fun register(r: PluginRegistry)
}

/** Thrown by [PluginRegistry.refuse]; the loader skips the plugin with [reason]. */
class PluginRefused(val reason: String) : RuntimeException(reason)

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

  /** Runs [fn] every [pulses] pulses (at least 8), after the world tick's work. */
  fun every(pulses: Int, doc: String, fn: (PluginTickCall) -> Unit) {}

  /**
   * Listens to a global-hookable engine event; runs after the scripts' global hooks and can't veto
   * or change the event. One of on_level_change, on_encumbrance_change, on_group_change,
   * on_owner_change, on_disengage and on_death; any other name fails register.
   */
  fun onEvent(name: String, doc: String, fn: (PluginEventCall) -> Unit) {}

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

  /**
   * Adjusts the owned behavior list of a mob after the world's owned_behaviors rules export, in
   * plugin-id order.
   *
   * [adjust] receives the mob, its owner (or null while offline), and the behavior list so far as
   * plain descriptors, and returns the replacement list of descriptors. An invalid descriptor or an
   * exception thrown by [adjust] logs a diagnostic and leaves the list unchanged.
   */
  fun addOwnedBehaviors(doc: String, adjust: (PluginOwnedBehaviorsCall) -> List<Map<String, Any?>>)

  /** Adjusts a disposition after the world's disposition rules export, in plugin-id order. */
  fun addDispositionModifier(doc: String, adjust: (PluginDispositionCall) -> Int)

  /** Adjusts an actor's faction labels after the factions rules export, in plugin-id order. */
  fun addFactions(doc: String, adjust: (PluginFactionsCall) -> List<String>)

  /** The world's declared stats, vitals and damage types. */
  val schema: PluginSchema
    get() = PluginSchema(emptyList(), emptyList(), emptyList())

  /** Enabled engine features plus every feature provided by a plugin that is loading. */
  val features: Set<String>
    get() = emptySet()

  /** Refuses this world: the plugin is skipped and adds nothing. */
  fun refuse(reason: String): Nothing = throw PluginRefused(reason)
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

/** One run of a plugin's periodic work. */
data class PluginTickCall(
  val world: PluginWorld,
  /** Simulated actors in the world, sorted by id. */
  val actors: List<PluginActor>,
)

/** One run of a plugin's engine event listener, after the scripts' global hooks. */
data class PluginEventCall(
  val world: PluginWorld,
  /** The event: on_level_change, on_encumbrance_change, ... or on_death. */
  val event: String,
  /** The actor the event is about: the global hook's ctx.self. */
  val actor: PluginActor,
  /** The event's ctx fields as plain values. */
  val fields: Map<String, Any?>,
)

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
  /** Null for `target = "none"`, `target = "item"` and `target = "room"` skills. */
  val target: PluginActor?,
  val args: Map<String, Any?>,
  /** The target item of an `item` skill, else null. */
  val targetItem: PluginItem? = null,
  /** Percent strength the effect runs at; 100 outside casts and item effects. */
  val power: Int = 100,
  /** The caster's room for a `target = "room"` skill, else null. */
  val targetRoom: PluginRoom? = null,
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

  val steps: List<PluginCreationStep>
    get() = emptyList()

  fun available(world: PluginWorld, choice: String, answers: Map<String, String>): List<String> =
    choices.first { it.id == choice }.options.map { it.id }

  fun validate(world: PluginWorld, answers: Map<String, String>): String? = null

  fun startRoom(world: PluginWorld, answers: Map<String, String>): String? = null

  fun prepare(world: PluginWorld, actor: PluginActor, attempt: PluginCreationAttempt) {}

  fun created(world: PluginWorld, actor: PluginActor, newCharacter: Boolean) {}
}

interface PluginCreationStep {
  val id: String
  val title: String

  fun applies(world: PluginWorld, attempt: PluginCreationAttempt): Boolean = true

  fun show(world: PluginWorld, attempt: PluginCreationAttempt): PluginStepView

  fun input(world: PluginWorld, attempt: PluginCreationAttempt, line: String): PluginStepReply

  fun summary(world: PluginWorld, attempt: PluginCreationAttempt): String? = null
}

data class PluginStepView(val text: String, val gmcp: Map<String, Any?> = emptyMap())

sealed interface PluginStepReply {
  data class Stay(val message: String? = null) : PluginStepReply

  data object Done : PluginStepReply
}

interface PluginCreationAttempt {
  val name: String
  val answers: Map<String, String>
  val seed: Long
  val state: MutableMap<String, Any?>
  val preview: PluginActor

  fun rng(stream: Int): PluginRng
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
  BONUS_DAMAGE,
  DEFEND_HIT,
  RECEIVE_DAMAGE,
  MITIGATE,
  INITIATIVE,
  MOVE_COST,
  REGEN,
  ADVANCE_LAG,
  RETREAT_LAG,
  RETREAT_FAIL_LAG,
  FLEE_FAIL_LAG,
  SKILL_CHECK,
  SKILL_POWER,
  ITEM_POWER,
}

data class PluginModifierCall(
  val world: PluginWorld,
  val point: PluginModifierPoint,
  /**
   * The trait's bearer. On `DEFEND_HIT`, `RECEIVE_DAMAGE` and `MITIGATE` the bearer is the
   * defender.
   */
  val actor: PluginActor,
  /** The bearer's opponent. On `DEFEND_HIT`, `RECEIVE_DAMAGE` and `MITIGATE` it is the attacker. */
  val other: PluginActor?,
  val weapon: PluginItem?,
  val room: PluginRoom,
  /**
   * The skill being cast when this rules call has one (an enemy spell's roll, a spell's damage),
   * else null.
   */
  val skill: PluginSkill? = null,
  /**
   * The item whose use_effect or proc this is, for `ITEM_POWER`; the target item of an `item` skill
   * for `SKILL_CHECK` and `SKILL_POWER`; else null.
   */
  val item: PluginItem? = null,
  /** `ctx.event` of the rules call: `use` or a proc trigger for `ITEM_POWER`; else null. */
  val event: String? = null,
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
  ORDER,
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

data class PluginOwnedBehaviorsCall(
  val world: PluginWorld,
  val mob: PluginActor,
  val owner: PluginActor?,
  val behaviors: List<Map<String, Any?>>,
)

data class PluginDispositionCall(
  val world: PluginWorld,
  val holder: PluginActor,
  val subject: PluginActor,
  val disposition: Int,
)

data class PluginFactionsCall(
  val world: PluginWorld,
  val actor: PluginActor,
  val factions: List<String>,
)
