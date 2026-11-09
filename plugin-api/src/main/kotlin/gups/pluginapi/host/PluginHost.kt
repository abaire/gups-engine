package gups.pluginapi.host

import gups.core.Role
import gups.core.model.Actor
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.Room
import gups.core.model.WorldSchema
import gups.core.model.didYouMean
import gups.core.world.Action
import gups.core.world.CategoryDef
import gups.core.world.CommandCategory
import gups.core.world.CommandDef
import gups.core.world.CommandRegistry
import gups.core.world.CustomHookHandler
import gups.core.world.Diagnostic
import gups.core.world.ENGINE_GMCP_PACKAGES
import gups.core.world.EntityRef
import gups.core.world.EventListener
import gups.core.world.FastCommand
import gups.core.world.Feature
import gups.core.world.FeatureRequirement
import gups.core.world.Features
import gups.core.world.GMCP_PACKAGE_NAME
import gups.core.world.GmcpInput
import gups.core.world.HookContext
import gups.core.world.HookName
import gups.core.world.PeriodicWork
import gups.core.world.PluginEffectRunner
import gups.core.world.RESERVED_GMCP_ROOTS
import gups.core.world.TickCommand
import gups.core.world.World
import gups.core.world.gmcpOverlaps
import gups.core.world.gmcpWithin
import gups.core.world.submit
import gups.pluginapi.GupsPlugin
import gups.pluginapi.PluginActor
import gups.pluginapi.PluginApi
import gups.pluginapi.PluginChainThinkCall
import gups.pluginapi.PluginCommandCall
import gups.pluginapi.PluginContainmentCall
import gups.pluginapi.PluginCreation
import gups.pluginapi.PluginDispositionCall
import gups.pluginapi.PluginEffect
import gups.pluginapi.PluginEffectCall
import gups.pluginapi.PluginEntity
import gups.pluginapi.PluginEventCall
import gups.pluginapi.PluginFactionsCall
import gups.pluginapi.PluginGmcpCall
import gups.pluginapi.PluginGmcpWorld
import gups.pluginapi.PluginHookCall
import gups.pluginapi.PluginLane
import gups.pluginapi.PluginModifierPoint
import gups.pluginapi.PluginNamed
import gups.pluginapi.PluginOwnedBehaviorsCall
import gups.pluginapi.PluginParam
import gups.pluginapi.PluginRefused
import gups.pluginapi.PluginRegistry
import gups.pluginapi.PluginRestrictionCall
import gups.pluginapi.PluginRestrictionPoint
import gups.pluginapi.PluginRole
import gups.pluginapi.PluginRulesType
import gups.pluginapi.PluginSchema
import gups.pluginapi.PluginTargetChoice
import gups.pluginapi.PluginTargetOrderCall
import gups.pluginapi.PluginTickCall
import gups.pluginapi.PluginTrait
import gups.pluginapi.PluginWorld
import gups.script.RulesExport
import gups.script.WorldDir
import gups.script.WorldModule
import gups.script.WorldPluginWiring
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ServiceLoader
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import net.starlark.java.eval.StarlarkValue

// Loading plugins and wiring them into a world.

/** What `gups docs` shows about one plugin; `:cli` maps it to its `PluginDocSpec`. */
data class PluginInfo(
  val id: String,
  val doc: String,
  val builtins: List<PluginMemberInfo>,
  val effects: List<PluginMemberInfo>,
  val commands: List<PluginCommandInfo>,
  val gmcp: List<PluginGmcpInfo>,
  val hooks: List<PluginHookInfo> = emptyList(),
  val traits: List<PluginTraitInfo> = emptyList(),
  val restrictions: List<PluginRestrictionInfo> = emptyList(),
  val choices: List<PluginChoiceInfo> = emptyList(),
  val containmentFilters: List<PluginFilterInfo> = emptyList(),
  val ownedBehaviors: List<PluginOwnedBehaviorsInfo> = emptyList(),
  val targetOrders: List<PluginTargetOrderInfo> = emptyList(),
  val chainThinks: List<PluginChainThinkInfo> = emptyList(),
  val dispositionModifiers: List<PluginDispositionModifierInfo> = emptyList(),
  val factions: List<PluginFactionAdjusterInfo> = emptyList(),
  val steps: List<PluginStepInfo> = emptyList(),
  val rulesExports: List<PluginRulesExportInfo> = emptyList(),
)

data class PluginRulesExportInfo(val name: String, val doc: String, val type: String)

data class PluginStepInfo(val id: String, val title: String)

data class PluginFilterInfo(val doc: String)

data class PluginOwnedBehaviorsInfo(val doc: String)

data class PluginTargetOrderInfo(val doc: String)

data class PluginChainThinkInfo(val doc: String)

data class PluginDispositionModifierInfo(val doc: String)

data class PluginFactionAdjusterInfo(val doc: String)

data class PluginTraitInfo(
  val name: String,
  val label: String,
  val doc: String,
  val statDeltas: Map<String, Int> = emptyMap(),
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  val resistDeltas: Map<String, Int> = emptyMap(),
  val flags: Set<String> = emptySet(),
  val modifiers: Set<String> = emptySet(),
  val senseDeltas: Map<String, Int> = emptyMap(),
)

data class PluginRestrictionInfo(val point: String, val doc: String)

data class PluginOptionInfo(
  val id: String,
  val label: String,
  val summary: String,
  val help: String,
  val traits: List<String>,
)

data class PluginChoiceInfo(
  val id: String,
  val title: String,
  val prompt: String,
  val options: List<PluginOptionInfo>,
)

data class PluginMemberInfo(
  val name: String,
  val doc: String,
  val params: List<PluginParam>,
  val returnType: String = "any",
)

data class PluginCommandInfo(
  val name: String,
  val lane: PluginLane,
  val role: PluginRole,
  val doc: String,
  val category: String? = null,
)

data class PluginGmcpInfo(val pkg: String, val doc: String, val kind: String)

data class PluginHookInfo(val name: String, val doc: String)

internal class CategoryReg(val name: String, val title: String, val order: Int?, val help: String?)

internal class CommandReg(
  val name: String,
  val lane: PluginLane,
  val role: PluginRole,
  val doc: String,
  val category: String? = null,
  val run: (PluginCommandCall) -> Unit,
)

internal class EffectReg(
  val name: String,
  val doc: String,
  val params: List<PluginParam>,
  val apply: PluginEffect,
)

internal class GmcpReg(
  val pkg: String,
  val doc: String,
  val produce: (PluginWorld, PluginActor) -> Any?,
)

internal class GmcpEventReg(val pkg: String, val doc: String)

internal class GmcpInputReg(val pkg: String, val doc: String, val handle: (PluginGmcpCall) -> Unit)

internal class HookReg(val name: String, val doc: String, val handle: (PluginHookCall) -> Any?)

internal class HookCall(
  override val world: PluginGmcpWorld,
  override val entity: PluginEntity,
  override val name: String,
  override val args: Map<String, Any?>,
  override val result: Any?,
) : PluginHookCall

/** The feature requirement a trait modifier point needs; points not listed need none. */
private val MODIFIER_POINT_FEATURES: Map<PluginModifierPoint, FeatureRequirement> =
  mapOf(
    PluginModifierPoint.TO_HIT to FeatureRequirement.anyOf(Feature.COMBAT, Feature.SKILLS),
    PluginModifierPoint.DEFEND_HIT to FeatureRequirement.anyOf(Feature.COMBAT, Feature.SKILLS),
    PluginModifierPoint.INITIATIVE to FeatureRequirement.of(Feature.COMBAT),
    PluginModifierPoint.FLEE_FAIL_LAG to FeatureRequirement.of(Feature.COMBAT),
    PluginModifierPoint.ADVANCE_LAG to FeatureRequirement.of(Feature.TRACK),
    PluginModifierPoint.RETREAT_LAG to FeatureRequirement.of(Feature.TRACK),
    PluginModifierPoint.RETREAT_FAIL_LAG to FeatureRequirement.of(Feature.TRACK),
    PluginModifierPoint.SKILL_CHECK to FeatureRequirement.of(Feature.SKILLS),
    PluginModifierPoint.SKILL_POWER to FeatureRequirement.of(Feature.SKILLS),
  )

/**
 * Collects one plugin's registrations, validating each as it comes. Any violation throws, so a
 * failed `register` adds nothing. [takenCommands] and [takenGmcp] are the earlier plugins' names.
 */
internal class PluginRegistryImpl(
  private val pluginId: String,
  private val takenCommands: List<CommandDef>,
  private val takenGmcp: Set<String>,
  private val takenTraits: Set<String> = emptySet(),
  private val hasCreation: Boolean = false,
  private val worldSchema: WorldSchema = WorldSchema.EMPTY,
  private val engineFeatures: Features = Features.ALL,
  /** Enabled engine features plus every feature provided by a plugin that is loading. */
  override val features: Set<String> = emptySet(),
  /** The world's engine commands, renames applied, which a plugin command may not shadow. */
  commandCheck: CommandRegistry = CommandRegistry(),
  private val log: (World) -> (String) -> Unit,
) : PluginRegistry {
  override val schema: PluginSchema
    get() =
      worldSchema.let { s ->
        PluginSchema(
          s.stats.map { PluginNamed(it.name, it.label) },
          s.vitals.map { PluginNamed(it.name, it.label) },
          s.damageTypes,
        )
      }

  val members = LinkedHashMap<String, Any>()
  val builtinInfo = mutableListOf<PluginMemberInfo>()
  val effects = mutableListOf<EffectReg>()
  val categories = mutableListOf<CategoryReg>()
  val commands = mutableListOf<CommandReg>()
  val gmcp = mutableListOf<GmcpReg>()
  val gmcpEvents = mutableListOf<GmcpEventReg>()
  val gmcpInputs = mutableListOf<GmcpInputReg>()
  val hooks = mutableListOf<HookReg>()
  val started = mutableListOf<(PluginWorld) -> Unit>()
  val periodic = mutableListOf<Triple<Int, String, (PluginTickCall) -> Unit>>()
  val events = mutableListOf<Triple<String, String, (PluginEventCall) -> Unit>>()
  val traits = mutableMapOf<String, PluginTrait>()
  val rulesExports = LinkedHashMap<String, PluginRulesExportInfo>()
  private var _creation: PluginCreation? = null
  val creation: PluginCreation?
    get() = _creation

  val restrictions =
    mutableMapOf<
      PluginRestrictionPoint,
      MutableList<Pair<String, (PluginRestrictionCall) -> String?>>,
    >()
  val filters = mutableListOf<Pair<String, (PluginContainmentCall) -> Boolean>>()
  val ownedBehaviors =
    mutableListOf<Pair<String, (PluginOwnedBehaviorsCall) -> List<Map<String, Any?>>>>()
  val targetOrders = mutableListOf<Pair<String, (PluginTargetOrderCall) -> PluginTargetChoice>>()
  val chainThinks = mutableListOf<Pair<String, (PluginChainThinkCall) -> Boolean>>()
  val dispositionModifiers = mutableListOf<Pair<String, (PluginDispositionCall) -> Int>>()
  val factionAdjusters = mutableListOf<Pair<String, (PluginFactionsCall) -> List<String>>>()
  private val commandCheck = commandCheck.also { r -> takenCommands.forEach(r::registerPlugin) }

  private fun member(name: String, value: Any) {
    require(MEMBER.matches(name)) { "member name '$name' must match [a-z][a-z0-9_]*" }
    require(name !in members) { "member '$name' is registered twice" }
    members[name] = value
  }

  override fun addBuiltin(name: String, value: StarlarkValue, doc: String) {
    member(name, value)
    builtinInfo += PluginMemberInfo(name, doc, emptyList())
  }

  override fun addBuiltin(
    name: String,
    doc: String,
    params: List<PluginParam>,
    returnType: String,
    call: (world: PluginWorld, positional: List<Any?>, named: Map<String, Any?>) -> Any?,
  ) {
    checkParams(name, params)
    member(name, PluginBuiltin(pluginId, name, params, log, call))
    builtinInfo += PluginMemberInfo(name, doc, params, returnType)
  }

  override fun addCategory(name: String, title: String, order: Int?, help: String?) {
    require(name.isNotBlank() && name.none { it.isWhitespace() }) {
      "category name '$name' must not contain whitespace"
    }
    categories += CategoryReg(name, title, order, help)
  }

  override fun addCommand(
    name: String,
    lane: PluginLane,
    role: PluginRole,
    doc: String,
    run: (PluginCommandCall) -> Unit,
  ) {
    registerCommand(name, lane, role, doc, category = null, run)
  }

  override fun addCommand(
    name: String,
    lane: PluginLane,
    role: PluginRole,
    doc: String,
    category: String,
    run: (PluginCommandCall) -> Unit,
  ) {
    registerCommand(name, lane, role, doc, category = category, run)
  }

  private fun registerCommand(
    name: String,
    lane: PluginLane,
    role: PluginRole,
    doc: String,
    category: String?,
    run: (PluginCommandCall) -> Unit,
  ) {
    require(name.isNotBlank() && name.none { it.isWhitespace() }) {
      "command name '$name' must be one word"
    }
    require(name == name.lowercase()) { "command name '$name' must be lowercase" }
    // `@` words resolve only to engine tool commands, so a plugin's could never run.
    require(!name.startsWith("@")) {
      "command name '$name' can't start with @: those are engine tool commands"
    }
    try {
      commandCheck.registerPlugin(FastCommand(name) {})
    } catch (e: IllegalArgumentException) {
      throw IllegalArgumentException("command '$name': ${e.message}")
    }
    commands += CommandReg(name, lane, role, doc, category, run)
  }

  override fun addEffectPrimitive(
    name: String,
    doc: String,
    params: List<PluginParam>,
    apply: PluginEffect,
  ) {
    checkParams(name, params)
    member(name, PluginEffectPrimitive(pluginId, name, params))
    effects += EffectReg(name, doc, params, apply)
  }

  override fun addGmcp(pkg: String, doc: String, produce: (PluginWorld, PluginActor) -> Any?) {
    checkOutbound(pkg)
    gmcp += GmcpReg(pkg, doc, produce)
  }

  override fun addGmcpEvent(pkg: String, doc: String) {
    checkOutbound(pkg)
    gmcpEvents += GmcpEventReg(pkg, doc)
  }

  override fun onGmcp(pkg: String, doc: String, handle: (PluginGmcpCall) -> Unit) {
    require(GMCP_PACKAGE_NAME.matches(pkg)) {
      "GMCP package '$pkg' needs at least two dot-separated parts, such as Gups.Weather"
    }
    RESERVED_GMCP_ROOTS.firstOrNull { gmcpWithin(pkg, it) || gmcpWithin(it, pkg) }
      ?.let { throw IllegalArgumentException("GMCP package '$pkg' uses the reserved root Core") }
    ENGINE_GMCP_PACKAGES.firstOrNull { gmcpOverlaps(pkg, it) }
      ?.let { throw IllegalArgumentException("GMCP package '$pkg' overlaps the engine's $it") }
    takenGmcp
      .firstOrNull { it.equals(pkg, true) }
      ?.let { throw IllegalArgumentException("GMCP package '$pkg' is already registered") }
    takenGmcp
      .firstOrNull { gmcpOverlaps(pkg, it) }
      ?.let { throw IllegalArgumentException("GMCP package '$pkg' overlaps another plugin's $it") }
    require(gmcpInputs.none { it.pkg.equals(pkg, true) }) {
      "GMCP package '$pkg' is already registered"
    }
    gmcpInputs += GmcpInputReg(pkg, doc, handle)
  }

  override fun addHook(name: String, doc: String, handle: (PluginHookCall) -> Any?) {
    require(MEMBER.matches(name)) { "hook name '$name' must match [a-z][a-z0-9_]*" }
    if (name in CATALOG_HOOKS) throw IllegalArgumentException("'$name' is a catalog hook")
    require(hooks.none { it.name == name }) { "hook '$name' is already registered" }
    hooks += HookReg(name, doc, handle)
  }

  /** Every check on an outbound GMCP package name, shared by [addGmcp] and [addGmcpEvent]. */
  private fun checkOutbound(pkg: String) {
    require(GMCP_PACKAGE_NAME.matches(pkg)) {
      "GMCP package '$pkg' needs at least two dot-separated parts, such as Gups.Weather"
    }
    // Clients subscribe by prefix, so a package may not equal, contain or nest in a taken one.
    RESERVED_GMCP_ROOTS.firstOrNull { gmcpWithin(pkg, it) || gmcpWithin(it, pkg) }
      ?.let { throw IllegalArgumentException("GMCP package '$pkg' uses the reserved root Core") }
    ENGINE_GMCP_PACKAGES.firstOrNull { gmcpOverlaps(pkg, it) }
      ?.let { throw IllegalArgumentException("GMCP package '$pkg' overlaps the engine's $it") }
    require(
      gmcp.none { it.pkg.equals(pkg, true) } && gmcpEvents.none { it.pkg.equals(pkg, true) }
    ) {
      "GMCP package '$pkg' is already registered"
    }
    takenGmcp
      .firstOrNull { it.equals(pkg, true) }
      ?.let { throw IllegalArgumentException("GMCP package '$pkg' is already registered") }
    takenGmcp
      .firstOrNull { gmcpOverlaps(pkg, it) }
      ?.let { throw IllegalArgumentException("GMCP package '$pkg' overlaps another plugin's $it") }
    (gmcp.map { it.pkg } + gmcpEvents.map { it.pkg })
      .firstOrNull { gmcpOverlaps(pkg, it) }
      ?.let { throw IllegalArgumentException("GMCP package '$pkg' overlaps this plugin's $it") }
  }

  override fun onWorldStarted(fn: (PluginWorld) -> Unit) {
    started += fn
  }

  override fun every(pulses: Int, doc: String, fn: (PluginTickCall) -> Unit) {
    require(pulses >= 8) { "every(): pulses must be at least 8, got $pulses" }
    periodic += Triple(pulses, doc, fn)
  }

  override fun onEvent(name: String, doc: String, fn: (PluginEventCall) -> Unit) {
    require(name in EVENT_NAMES) {
      "onEvent(): '$name' is not an event; expected one of ${EVENT_NAMES.joinToString(", ")}"
    }
    events += Triple(name, doc, fn)
  }

  override fun addRulesExport(name: String, doc: String, type: PluginRulesType) {
    require(Regex("${Regex.escape(pluginId)}_[a-z][a-z0-9_]*").matches(name)) {
      "addRulesExport(): '$name' must be '${pluginId}_' then a snake_case name"
    }
    require(RulesExport.entries.none { it.scriptName == name }) {
      "addRulesExport(): '$name' is an engine rules export"
    }
    require(name !in rulesExports) { "addRulesExport(): '$name' is already declared" }
    rulesExports[name] = PluginRulesExportInfo(name, doc, type.name.lowercase())
  }

  override fun addTrait(trait: PluginTrait) {
    require(MEMBER.matches(trait.name)) { "trait name '${trait.name}' must match [a-z][a-z0-9_]*" }
    require(trait.name !in traits && trait.name !in takenTraits) {
      "trait '${trait.name}' is already registered"
    }
    for (s in trait.statDeltas.keys) {
      require(s in worldSchema.statNames) {
        "unknown stat '$s'${didYouMean(s, worldSchema.statNames)}"
      }
    }
    val channelNames = worldSchema.allChannels.map { it.name }
    for (c in trait.senseDeltas.keys) {
      require(c in channelNames) { "unknown channel '$c'${didYouMean(c, channelNames)}" }
    }
    for (v in trait.maxVitalDeltas.keys) {
      require(v in worldSchema.vitalNames) {
        "unknown vital '$v'${didYouMean(v, worldSchema.vitalNames)}"
      }
    }
    for (d in trait.resistDeltas.keys) {
      require(d in worldSchema.damageTypes) {
        "unknown damage type '$d'${didYouMean(d, worldSchema.damageTypes)}"
      }
    }
    require("stunned" !in trait.flags) { "trait '${trait.name}': flags cannot contain 'stunned'" }
    for (p in trait.modifiers.keys) {
      val needed = MODIFIER_POINT_FEATURES[p] ?: continue
      require(needed.satisfiedBy(engineFeatures)) {
        "trait '${trait.name}': modifier point $p needs the ${needed.text} ${needed.noun}"
      }
    }
    traits[trait.name] = trait
  }

  override fun setCreation(creation: PluginCreation) {
    require(this.creation == null) { "setCreation can only be called once" }
    require(!hasCreation) { "a creation provider is already registered" }
    this._creation = creation
  }

  override fun addRestriction(
    point: PluginRestrictionPoint,
    doc: String,
    check: (PluginRestrictionCall) -> String?,
  ) {
    if (point in setOf(PluginRestrictionPoint.CAST, PluginRestrictionPoint.LEARN)) {
      require(Feature.SKILLS in engineFeatures) {
        "restriction point $point needs the skills feature"
      }
    }
    restrictions.getOrPut(point) { mutableListOf() }.add(doc to check)
  }

  override fun addContainmentFilter(doc: String, blocks: (PluginContainmentCall) -> Boolean) {
    filters.add(doc to blocks)
  }

  override fun addOwnedBehaviors(
    doc: String,
    adjust: (PluginOwnedBehaviorsCall) -> List<Map<String, Any?>>,
  ) {
    ownedBehaviors.add(doc to adjust)
  }

  override fun addTargetOrder(doc: String, choose: (PluginTargetOrderCall) -> PluginTargetChoice) {
    targetOrders.add(doc to choose)
  }

  override fun addChainThink(doc: String, decide: (PluginChainThinkCall) -> Boolean) {
    chainThinks.add(doc to decide)
  }

  override fun addDispositionModifier(doc: String, adjust: (PluginDispositionCall) -> Int) {
    dispositionModifiers.add(doc to adjust)
  }

  override fun addFactions(doc: String, adjust: (PluginFactionsCall) -> List<String>) {
    factionAdjusters.add(doc to adjust)
  }

  private fun checkParams(name: String, params: List<PluginParam>) {
    val names = params.map { it.name }
    require(names.distinct().size == names.size) { "'$name' has duplicate parameter names" }
    for (p in params) require(MEMBER.matches(p.name)) {
      "'$name' parameter '${p.name}' must match [a-z][a-z0-9_]*"
    }
  }

  companion object {
    val MEMBER = Regex("[a-z][a-z0-9_]*")

    /** The script names the engine fires itself; a custom hook may not use one. */
    val CATALOG_HOOKS = HookName.entries.mapTo(HashSet()) { it.scriptName }

    /** The global-hookable engine events a plugin may listen to, in declaration order. */
    val EVENT_NAMES =
      listOf(
          HookName.ON_LEVEL_CHANGE,
          HookName.ON_ENCUMBRANCE_CHANGE,
          HookName.ON_GROUP_CHANGE,
          HookName.ON_OWNER_CHANGE,
          HookName.ON_DISENGAGE,
          HookName.ON_DEATH,
          HookName.ON_SKILL_USE,
        )
        .map { it.scriptName }
  }
}

/** One plugin that loaded, with what it registered. */
internal class LoadedPlugin(
  val id: String,
  val doc: String,
  val reg: PluginRegistryImpl,
  val loader: URLClassLoader?,
  val requiredFeatures: Set<String> = emptySet(),
  val providedFeatures: Set<String> = emptySet(),
)

/**
 * Host coordinator managing loaded plugins and wiring them into the live engine simulation.
 *
 * ## Responsibilities
 * - Manages plugin lifecycle and classloader isolation for loaded plugin jars.
 * - Exposes plugin members as top-level Starlark package globals under `<pluginId>.<name>`.
 * - Wires plugin commands into engine fast or tick command registries.
 * - Registers GMCP package providers, skill effect primitives, traits, character creation flows,
 *   and restriction hooks.
 * - Dispatches world start lifecycle notifications to registered plugin callbacks.
 */
class PluginHost
internal constructor(
  private val plugins: List<LoadedPlugin>,
  /** Why plugins were skipped or jars ignored, each starting `plugin '<id>': ` or `plugins/`. */
  val warnings: List<String>,
  private val log: (String) -> Unit,
) : WorldPluginWiring, AutoCloseable {
  override val globals: Map<String, Any> =
    plugins.associate { it.id to PluginNamespace(it.id, it.reg.members) }

  override val traits: Set<String>
    get() = plugins.flatMap { it.reg.traits.keys }.toSet()

  override val gmcpPackages: Set<String>
    get() =
      plugins
        .flatMap { p ->
          p.reg.gmcp.map { it.pkg } +
            p.reg.gmcpEvents.map { it.pkg } +
            p.reg.gmcpInputs.map { it.pkg }
        }
        .toSet()

  override val rulesExports: Map<String, String>
    get() =
      plugins.flatMap { it.reg.rulesExports.values }.associate { it.name to it.type }.toSortedMap()

  /** The ids that loaded, sorted. */
  val ids: List<String>
    get() = plugins.map { it.id }

  /** The features provided by the plugins that loaded; scripts test them with `ctx.has_feature`. */
  val providedFeatures: Set<String>
    get() = plugins.flatMap { it.providedFeatures }.toSet()

  private fun facade(world: World, id: String) = WorldFacade(world, id, log)

  private fun guard(world: World, id: String, what: String, block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      world.record(Diagnostic(world.pulse, "plugin", "plugin '$id': $what failed: $e", e))
    }
  }

  /** The event's ctx fields as plain values, per hook. Ints are Longs; actors are PluginActors. */
  private fun fieldsOf(w: World, hook: HookName, ctx: HookContext): Map<String, Any?> =
    when (hook) {
      HookName.ON_LEVEL_CHANGE ->
        mapOf("old_level" to ctx.oldLevel?.toLong(), "new_level" to ctx.newLevel?.toLong())
      HookName.ON_ENCUMBRANCE_CHANGE ->
        mapOf(
          "old_encumbrance_level" to ctx.oldEncumbranceLevel?.toLong(),
          "new_encumbrance_level" to ctx.newEncumbranceLevel?.toLong(),
        )
      HookName.ON_GROUP_CHANGE -> {
        val g = ctx.group!!
        mapOf(
          "event" to ctx.event,
          "group" to
            mapOf(
              "id" to g.id.toLong(),
              "leader" to ActorFacade(g.leader, w),
              "members" to g.members.map { ActorFacade(it, w) },
            ),
        )
      }
      HookName.ON_OWNER_CHANGE ->
        mapOf("target" to ctx.target?.let { ActorFacade(it, w) }, "event" to ctx.event)
      HookName.ON_DISENGAGE -> mapOf("event" to ctx.event)
      HookName.ON_DEATH -> mapOf("killer" to ctx.actor?.let { ActorFacade(it, w) })
      HookName.ON_SKILL_USE ->
        mapOf(
          "skill" to ctx.skill!!.label.v,
          "outcome" to ctx.outcome,
          "target" to ctx.target?.let { ActorFacade(it, w) },
          "item" to ctx.item?.let { ItemFacade(it, w) },
          "targets" to ctx.landed.orEmpty().map { ActorFacade(it, w) },
        )
      else -> emptyMap()
    }

  /** One handler run: read-only world, wrapped entity, plain arguments (views as facades). */
  private fun hookCall(
    w: World,
    id: String,
    entity: Entity,
    name: String,
    args: Map<String, Any?>,
    result: Any?,
  ): PluginHookCall =
    HookCall(
      GmcpWorldFacade(facade(w, id)),
      wrap(w, entity),
      name,
      args.mapValues { (k, v) -> plainEntity(w, v) },
      result,
    )

  private fun wrap(w: World, e: Entity): PluginEntity =
    when (e) {
      is Room -> RoomFacade(e, w)
      is Item -> ItemFacade(e, w)
      else -> ActorFacade(e as Actor, w)
    }

  /** [v] with every [EntityRef], nested, replaced by the entity's facade, or null if gone. */
  private fun plainEntity(w: World, v: Any?): Any? =
    when (v) {
      is EntityRef -> w.entity(v.id)?.let { wrap(w, it) }
      is List<*> -> v.map { plainEntity(w, it) }
      is Map<*, *> -> v.entries.associate { (k, x) -> (k as String) to plainEntity(w, x) }
      else -> v
    }

  override fun install(world: World) {
    Facades.registerLog(world, log)
    Facades.registerRulesExports(
      world,
      plugins.associate { p -> p.id to p.reg.rulesExports.values.associate { it.name to it.type } },
    )
    Facades.registerGmcp(
      world,
      plugins.associate { p -> p.id to p.reg.gmcp.map { it.pkg } + p.reg.gmcpEvents.map { it.pkg } },
    )

    val creationPlugin = plugins.firstOrNull { it.reg.creation != null }
    world.creation =
      creationPlugin?.let { CreationProviderAdapter(world, it.id, it.reg.creation!!) }

    world.traits =
      plugins.flatMap { p -> p.reg.traits.values.map { it.name to traitAdapter(p.id, it) } }.toMap()

    val allRestrictions =
      mutableMapOf<gups.core.model.RestrictionPoint, MutableList<gups.core.model.Restriction>>()
    for (point in gups.core.model.RestrictionPoint.entries) {
      allRestrictions[point] = mutableListOf()
    }
    for (p in plugins) {
      for ((point, list) in p.reg.restrictions) {
        val corePoint = gups.core.model.RestrictionPoint.valueOf(point.name)
        for ((_, check) in list) {
          allRestrictions[corePoint]!!.add(restrictionAdapter(world, p.id, corePoint, check))
        }
      }
    }
    world.restrictions = allRestrictions

    val allFilters = mutableListOf<gups.core.world.ContainmentFilter>()
    for (p in plugins) {
      for ((_, check) in p.reg.filters) {
        allFilters.add(containmentFilterAdapter(world, p.id, check))
      }
    }
    world.containmentFilters = allFilters

    val allAdjusters = mutableListOf<gups.core.world.OwnedBehaviorAdjuster>()
    for (p in plugins) {
      for ((_, adjust) in p.reg.ownedBehaviors) {
        allAdjusters.add(ownedBehaviorsAdapter(world, p.id, adjust))
      }
    }
    world.ownedBehaviorAdjusters = allAdjusters

    val allTargetOrderAdjusters = mutableListOf<gups.core.world.TargetOrderAdjuster>()
    for (p in plugins) {
      for ((_, choose) in p.reg.targetOrders) {
        allTargetOrderAdjusters.add(targetOrderAdapter(world, p.id, choose))
      }
    }
    world.targetOrderAdjusters = allTargetOrderAdjusters

    val allChainThinkAdjusters = mutableListOf<gups.core.world.ChainThinkAdjuster>()
    for (p in plugins) {
      for ((_, decide) in p.reg.chainThinks) {
        allChainThinkAdjusters.add(chainThinkAdapter(world, p.id, decide))
      }
    }
    world.chainThinkAdjusters = allChainThinkAdjusters

    val allDispositionModifiers = mutableListOf<gups.core.world.DispositionModifier>()
    for (p in plugins) {
      for ((_, adjust) in p.reg.dispositionModifiers) {
        allDispositionModifiers.add(dispositionModifierAdapter(world, p.id, adjust))
      }
    }
    world.dispositionModifiers = allDispositionModifiers

    val allFactionAdjusters = mutableListOf<gups.core.world.FactionAdjuster>()
    for (p in plugins) {
      for ((_, adjust) in p.reg.factionAdjusters) {
        allFactionAdjusters.add(factionAdjusterAdapter(world, p.id, adjust))
      }
    }
    world.factionAdjusters = allFactionAdjusters

    world.pluginPeriodic =
      plugins.flatMap { p ->
        p.reg.periodic.map { (pulses, _, fn) ->
          PeriodicWork(pulses) { w, actors ->
            guard(w, p.id, "every($pulses)") {
              fn(PluginTickCall(facade(w, p.id), actors.map { ActorFacade(it, w) }))
            }
          }
        }
      }

    for (p in plugins) {
      for (cat in p.reg.categories) {
        world.commands.registerCategory(
          CategoryDef(id = cat.name, title = cat.title, order = cat.order, help = cat.help)
        )
      }
      for (c in p.reg.commands) {
        val role = Role.valueOf(c.role.name)
        val cat =
          if (role != Role.PLAYER || c.name.startsWith("@")) CommandCategory.SPECIAL
          else c.category ?: CommandCategory.GENERAL
        fun run(w: World, actor: Actor, args: String) =
          guard(w, p.id, "command '${c.name}'") {
            c.run(PluginCommandCall(facade(w, p.id), ActorFacade(actor, w), args))
          }
        world.commands.registerPlugin(
          when (c.lane) {
            PluginLane.FAST ->
              FastCommand(c.name, role = role, category = cat, help = c.doc) { call ->
                run(call.world, call.actor, call.args)
              }
            PluginLane.TICK ->
              TickCommand(c.name, role = role, category = cat, help = c.doc) { call ->
                object : Action {
                  override fun validate(w: World, actor: Actor): String? = null

                  override fun resolve(w: World, actor: Actor): Int {
                    run(w, actor, call.args)
                    return 0
                  }
                }
              }
          }
        )
      }
      for (g in p.reg.gmcp) {
        // core records a failing producer and skips it for the pulse.
        world.pluginGmcp[g.pkg] = { player ->
          g.produce(facade(world, p.id), ActorFacade(player, world))
        }
      }
      for (g in p.reg.gmcpInputs) {
        world.pluginGmcpIn[g.pkg.lowercase()] =
          GmcpInput(g.pkg) { player, data ->
            guard(world, p.id, "GMCP input '${g.pkg}'") {
              g.handle(
                GmcpInputCall(
                  GmcpWorldFacade(facade(world, p.id)),
                  ActorFacade(player, world),
                  g.pkg,
                  data,
                ) {
                  world.submit(player, it)
                }
              )
            }
          }
      }
    }
    world.pluginHooks.clear()
    world.pluginFeatures = providedFeatures
    for (p in plugins) {
      for (h in p.reg.hooks) {
        world.pluginHooks.getOrPut(h.name) { mutableListOf() } +=
          CustomHookHandler { w, entity, args, result ->
            var out = result
            guard(w, p.id, "hook '${h.name}'") {
              out =
                toHookResult(
                  h.handle(hookCall(w, p.id, entity, h.name, args, result)),
                  "hook '${h.name}'(result)",
                )
            }
            out
          }
      }
    }
    world.pluginEvents.clear()
    for (p in plugins) {
      for ((name, _, fn) in p.reg.events) {
        val hook = HookName.entries.first { it.scriptName == name }
        world.pluginEvents.getOrPut(hook) { mutableListOf() } += EventListener { w, subject, ctx ->
          guard(w, p.id, "onEvent '$name'") {
            fn(
              PluginEventCall(
                facade(w, p.id),
                name,
                ActorFacade(subject, w),
                fieldsOf(w, hook, ctx),
              )
            )
          }
        }
      }
    }
    val effects =
      plugins.flatMap { p -> p.reg.effects.map { (p.id to it.name) to (p.id to it) } }.toMap()
    world.pluginEffects =
      PluginEffectRunner { w, effect, caster, target, targetItem, targetRoom, power ->
        val (id, e) =
          effects[effect.pluginId to effect.name]
            ?: run {
              w.record(
                Diagnostic(
                  w.pulse,
                  "plugin",
                  "no plugin effect ${effect.pluginId}.${effect.name} is loaded",
                )
              )
              return@PluginEffectRunner
            }
        guard(w, id, "effect '${e.name}'") {
          e.apply.apply(
            PluginEffectCall(
              facade(w, id),
              ActorFacade(caster, w),
              target?.let { ActorFacade(it, w) },
              effect.args,
              targetItem = targetItem?.let { ItemFacade(it, w) },
              power = power,
              targetRoom = targetRoom?.let { RoomFacade(it, w) },
            )
          )
        }
      }
  }

  override fun started(world: World) {
    for (p in plugins) for (fn in p.reg.started) guard(world, p.id, "onWorldStarted") {
      fn(facade(world, p.id))
    }
  }

  fun describe(): List<PluginInfo> =
    plugins.map { p ->
      PluginInfo(
        id = p.id,
        doc = p.doc,
        builtins = p.reg.builtinInfo.sortedBy { it.name },
        effects =
          p.reg.effects
            .map { PluginMemberInfo(it.name, it.doc, it.params, "effect") }
            .sortedBy { it.name },
        commands =
          p.reg.commands
            .map { PluginCommandInfo(it.name, it.lane, it.role, it.doc, it.category) }
            .sortedBy { it.name },
        gmcp =
          (p.reg.gmcp.map { PluginGmcpInfo(it.pkg, it.doc, "state") } +
              p.reg.gmcpEvents.map { PluginGmcpInfo(it.pkg, it.doc, "event") } +
              p.reg.gmcpInputs.map { PluginGmcpInfo(it.pkg, it.doc, "input") })
            .sortedWith(compareBy({ it.pkg }, { it.kind })),
        hooks = p.reg.hooks.map { PluginHookInfo(it.name, it.doc) }.sortedBy { it.name },
        traits =
          p.reg.traits.values
            .map {
              PluginTraitInfo(
                name = it.name,
                label = it.label,
                doc = it.doc,
                statDeltas = it.statDeltas,
                maxVitalDeltas = it.maxVitalDeltas,
                resistDeltas = it.resistDeltas,
                flags = it.flags,
                modifiers = it.modifiers.keys.map { m -> m.name }.toSet(),
                senseDeltas = it.senseDeltas,
              )
            }
            .sortedBy { it.name },
        rulesExports = p.reg.rulesExports.values.sortedBy { it.name },
        restrictions =
          p.reg.restrictions.flatMap { (point, list) ->
            list.map { (doc, _) -> PluginRestrictionInfo(point.name.lowercase(), doc) }
          },
        containmentFilters = p.reg.filters.map { (doc, _) -> PluginFilterInfo(doc) },
        ownedBehaviors = p.reg.ownedBehaviors.map { (doc, _) -> PluginOwnedBehaviorsInfo(doc) },
        targetOrders = p.reg.targetOrders.map { (doc, _) -> PluginTargetOrderInfo(doc) },
        chainThinks = p.reg.chainThinks.map { (doc, _) -> PluginChainThinkInfo(doc) },
        dispositionModifiers =
          p.reg.dispositionModifiers.map { (doc, _) -> PluginDispositionModifierInfo(doc) },
        factions = p.reg.factionAdjusters.map { (doc, _) -> PluginFactionAdjusterInfo(doc) },
        choices =
          p.reg.creation?.choices?.map { c ->
            PluginChoiceInfo(
              id = c.id,
              title = c.title,
              prompt = c.prompt,
              options =
                c.options.map { o -> PluginOptionInfo(o.id, o.label, o.summary, o.help, o.traits) },
            )
          } ?: emptyList(),
        steps =
          p.reg.creation?.steps?.map { s -> PluginStepInfo(id = s.id, title = s.title) }
            ?: emptyList(),
      )
    }

  override fun close() {
    for (p in plugins) runCatching { p.loader?.close() }
  }

  companion object {
    /** A host with these plugins, already constructed, for tests (no jars, no class loaders). */
    fun of(
      vararg plugins: GupsPlugin,
      schema: WorldSchema = WorldSchema.EMPTY,
      features: Features = Features.ALL,
      log: (String) -> Unit = {},
    ): PluginHost =
      PluginLoader.register(
        plugins.map { it to null },
        log,
        engineFeatures = features,
        worldSchema = schema,
      )
  }
}

object PluginLoader {
  /**
   * Loads the plugins `MODULE.gups` pins, sorted by id. Each one that fails a check is skipped with
   * a warning (to [log] and [PluginHost.warnings]); the others still load.
   */
  fun load(
    dir: WorldDir,
    module: WorldModule,
    parent: ClassLoader = GupsPlugin::class.java.classLoader,
    log: (String) -> Unit = {},
  ): PluginHost {
    val warnings = mutableListOf<String>()
    fun warn(text: String) {
      warnings += text
      log(text)
    }
    val pluginsDir = dir.root.resolve("plugins")
    if (Files.isDirectory(pluginsDir)) {
      Files.list(pluginsDir)
        .use { s -> s.filter { it.name.endsWith(".jar") }.map { it.name }.toList() }
        .sorted()
        .forEach { name ->
          if (name.removeSuffix(".jar") !in module.plugins)
            warn("plugins/$name is not pinned in MODULE.gups; ignoring it")
        }
    }
    val found = mutableListOf<Pair<GupsPlugin, URLClassLoader>>()
    for ((id, checksum) in module.plugins.toSortedMap()) {
      val jar = pluginsDir.resolve("$id.jar")
      val skip = { why: String -> warn("plugin '$id': $why") }
      if (!WorldModule.PLUGIN_ID.matches(id) || id in WorldModule.reservedPluginIds) {
        skip("'$id' is not a valid plugin id")
        continue
      }
      if (!jar.isRegularFile()) {
        skip("plugins/$id.jar is missing")
        continue
      }
      val actual = sha256(jar)
      if (actual != checksum) {
        skip("checksum mismatch for plugins/$id.jar (expected $checksum, got $actual)")
        continue
      }
      val loader = URLClassLoader("plugin-$id", arrayOf(jar.toUri().toURL()), parent)
      val plugin =
        try {
          val providers = ServiceLoader.load(GupsPlugin::class.java, loader).stream().toList()
          if (providers.size != 1) {
            skip("plugins/$id.jar has ${providers.size} GupsPlugin providers; it needs exactly one")
            loader.close()
            continue
          }
          providers.single().get()
        } catch (e: Throwable) {
          skip("couldn't instantiate its GupsPlugin: $e")
          loader.close()
          continue
        }
      val badProvided =
        plugin.providedFeatures.firstOrNull {
          !WorldModule.PLUGIN_ID.matches(it) ||
            Feature.byId(it) != null ||
            it in WorldModule.reservedPluginIds
        }
      when {
        plugin.id != id ->
          skip("the jar's plugin id is '${plugin.id}', but MODULE.gups pins it as '$id'")
        plugin.pluginApiVersion != PluginApi.VERSION ->
          skip(
            "built for plugin API version ${plugin.pluginApiVersion}; this engine has version ${PluginApi.VERSION}"
          )
        badProvided != null -> skip("provides invalid feature '$badProvided'")
        else -> {
          found += plugin to loader
          continue
        }
      }
      loader.close()
    }
    val survivors = resolve(found, module.features, ::warn)
    val host = register(survivors, log, warnings, module.features, module.rename, module.schema)
    return host
  }

  /**
   * Resolves provided features and required-feature dependencies among [found], in id order. A
   * plugin providing a feature an earlier one already provides, or requiring a feature no survivor
   * provides, is skipped with a warning and its loader closed. Returns the survivors.
   */
  private fun resolve(
    found: List<Pair<GupsPlugin, URLClassLoader>>,
    engine: Features,
    warn: (String) -> Unit,
  ): List<Pair<GupsPlugin, URLClassLoader>> {
    val providedBy = mutableMapOf<String, String>()
    val survivors = mutableListOf<Pair<GupsPlugin, URLClassLoader>>()
    for ((plugin, loader) in found.sortedBy { it.first.id }) {
      val clash = plugin.providedFeatures.firstOrNull { it in providedBy }
      if (clash != null) {
        warn(
          "plugin '${plugin.id}': feature '$clash' is already provided by plugin '${providedBy[clash]}'"
        )
        loader.close()
        continue
      }
      plugin.providedFeatures.forEach { providedBy[it] = plugin.id }
      survivors += plugin to loader
    }
    var skippedAny = true
    while (skippedAny) {
      skippedAny = false
      val available = engine.ids + survivors.flatMap { it.first.providedFeatures }.toSet()
      val kept = mutableListOf<Pair<GupsPlugin, URLClassLoader>>()
      for ((plugin, loader) in survivors) {
        val missing = missingAmong(plugin.requiredFeatures, available)
        if (missing != null) {
          warn("plugin '${plugin.id}': needs the '$missing' feature")
          loader.close()
          skippedAny = true
        } else {
          kept += plugin to loader
        }
      }
      survivors.clear()
      survivors += kept
    }
    return survivors
  }

  /** The first feature in [required] that is not in [available], sorted. */
  private fun missingAmong(required: Set<String>, available: Collection<String>): String? =
    required.sorted().firstOrNull { it !in available }

  /** Runs each plugin's `register` in id order; a throwing, clashing or refusing one is skipped. */
  internal fun register(
    plugins: List<Pair<GupsPlugin, URLClassLoader?>>,
    log: (String) -> Unit,
    warnings: MutableList<String> = mutableListOf(),
    engineFeatures: Features = Features.ALL,
    rename: Map<String, String> = emptyMap(),
    worldSchema: WorldSchema = WorldSchema.EMPTY,
  ): PluginHost {
    val loaded = mutableListOf<LoadedPlugin>()
    val logOf: (World) -> (String) -> Unit = { w -> Facades.logOf(w) }
    val allFeatures =
      (engineFeatures.ids + plugins.flatMap { it.first.providedFeatures }.toSet()).toSet()
    for ((plugin, loader) in plugins.sortedBy { it.first.id }) {
      val reg =
        PluginRegistryImpl(
          plugin.id,
          takenCommands = loaded.flatMap { p -> p.reg.commands.map { FastCommand(it.name) {} } },
          takenGmcp =
            loaded
              .flatMap { p ->
                p.reg.gmcp.map { it.pkg } +
                  p.reg.gmcpEvents.map { it.pkg } +
                  p.reg.gmcpInputs.map { it.pkg }
              }
              .toSet(),
          takenTraits = loaded.flatMap { p -> p.reg.traits.keys }.toSet(),
          hasCreation = loaded.any { it.reg.creation != null },
          worldSchema = worldSchema,
          engineFeatures = engineFeatures,
          features = allFeatures,
          log = logOf,
          commandCheck = CommandRegistry(features = engineFeatures, rename = rename),
        )
      try {
        plugin.register(reg)
        reg.creation?.let { c ->
          require(c.choices.isNotEmpty() || c.steps.isNotEmpty()) {
            "creation provider must declare at least one choice or step"
          }
          val choiceIds = mutableSetOf<String>()
          val reserved = setOf("back", "cancel", "help", "yes", "no")
          for (choice in c.choices) {
            require(PluginRegistryImpl.MEMBER.matches(choice.id)) {
              "choice id '${choice.id}' must match [a-z][a-z0-9_]*"
            }
            require(choiceIds.add(choice.id)) { "duplicate choice id '${choice.id}'" }
            require(choice.options.isNotEmpty()) {
              "choice '${choice.id}' must declare at least one option"
            }
            val optionIds = mutableSetOf<String>()
            for (opt in choice.options) {
              require(PluginRegistryImpl.MEMBER.matches(opt.id)) {
                "option id '${opt.id}' in choice '${choice.id}' must match [a-z][a-z0-9_]*"
              }
              require(opt.id !in reserved) {
                "option id '${opt.id}' in choice '${choice.id}' is reserved"
              }
              require(optionIds.add(opt.id)) {
                "duplicate option id '${opt.id}' in choice '${choice.id}'"
              }
              for (t in opt.traits) {
                require(t in reg.traits) {
                  "option '${opt.id}' in choice '${choice.id}' names trait '$t', which is not registered by this plugin"
                }
              }
            }
          }
          val stepIds = mutableSetOf<String>()
          for (step in c.steps) {
            require(PluginRegistryImpl.MEMBER.matches(step.id)) {
              "step id '${step.id}' must match [a-z][a-z0-9_]*"
            }
            require(stepIds.add(step.id)) { "duplicate step id '${step.id}'" }
            require(step.id !in choiceIds) { "step id '${step.id}' equals a choice id" }
            require(step.title.isNotBlank()) { "step '${step.id}' title cannot be blank" }
          }
        }
        loaded +=
          LoadedPlugin(
            plugin.id,
            plugin.doc,
            reg,
            loader,
            plugin.requiredFeatures,
            plugin.providedFeatures,
          )
      } catch (e: PluginRefused) {
        val text = "plugin '${plugin.id}': refused: ${e.reason}"
        warnings += text
        log(text)
        runCatching { loader?.close() }
      } catch (e: Throwable) {
        val text = "plugin '${plugin.id}': register failed: ${e.message ?: e}"
        warnings += text
        log(text)
        runCatching { loader?.close() }
      }
    }
    // A provider that refused or threw no longer provides its features, so its dependents drop.
    // Clashes are not revisited: a plugin skipped for clashing stays skipped.
    var skippedAny = true
    while (skippedAny) {
      skippedAny = false
      val available = engineFeatures.ids + loaded.flatMap { it.providedFeatures }.toSet()
      val kept = mutableListOf<LoadedPlugin>()
      for (lp in loaded) {
        val missing = missingAmong(lp.requiredFeatures, available)
        if (missing != null) {
          val text = "plugin '${lp.id}': needs the '$missing' feature"
          warnings += text
          log(text)
          runCatching { lp.loader?.close() }
          skippedAny = true
        } else {
          kept += lp
        }
      }
      loaded.clear()
      loaded += kept
    }
    return PluginHost(loaded, warnings.toList(), log)
  }

  fun sha256(file: Path): String =
    "sha256:" +
      MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") {
        "%02x".format(it)
      }
}
