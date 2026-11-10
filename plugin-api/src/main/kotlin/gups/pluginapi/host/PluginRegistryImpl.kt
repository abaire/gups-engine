package gups.pluginapi.host

import gups.core.model.WorldSchema
import gups.core.model.didYouMean
import gups.core.world.CommandDef
import gups.core.world.CommandRegistry
import gups.core.world.ENGINE_GMCP_PACKAGES
import gups.core.world.FastCommand
import gups.core.world.Feature
import gups.core.world.FeatureRequirement
import gups.core.world.Features
import gups.core.world.GMCP_PACKAGE_NAME
import gups.core.world.HookName
import gups.core.world.RESERVED_GMCP_ROOTS
import gups.core.world.World
import gups.core.world.gmcpOverlaps
import gups.core.world.gmcpWithin
import gups.pluginapi.PluginActor
import gups.pluginapi.PluginChainThinkCall
import gups.pluginapi.PluginCommandCall
import gups.pluginapi.PluginContainmentCall
import gups.pluginapi.PluginCreation
import gups.pluginapi.PluginDispositionCall
import gups.pluginapi.PluginEffect
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
import java.net.URLClassLoader
import kotlin.io.path.name
import net.starlark.java.eval.StarlarkValue

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
    registerCommand(CommandReg(name, lane, role, doc, null, run))
  }

  override fun addCommand(
    name: String,
    lane: PluginLane,
    role: PluginRole,
    doc: String,
    category: String,
    run: (PluginCommandCall) -> Unit,
  ) {
    registerCommand(CommandReg(name, lane, role, doc, category, run))
  }

  private fun registerCommand(reg: CommandReg) {
    val name = reg.name
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
    commands += reg
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
    checkTraitSchema(trait)
    require("stunned" !in trait.flags) { "trait '${trait.name}': flags cannot contain 'stunned'" }
    checkModifierPoints(trait)
    traits[trait.name] = trait
  }

  private fun checkKnown(kind: String, names: Set<String>, known: Collection<String>) {
    for (n in names) require(n in known) { "unknown $kind '$n'${didYouMean(n, known)}" }
  }

  private fun checkTraitSchema(trait: PluginTrait) {
    checkKnown("stat", trait.statDeltas.keys, worldSchema.statNames)
    checkKnown("channel", trait.senseDeltas.keys, worldSchema.allChannels.map { it.name })
    checkKnown("vital", trait.maxVitalDeltas.keys, worldSchema.vitalNames)
    checkKnown("damage type", trait.resistDeltas.keys, worldSchema.damageTypes)
  }

  private fun checkModifierPoints(trait: PluginTrait) {
    for (p in trait.modifiers.keys) {
      val needed = MODIFIER_POINT_FEATURES[p] ?: continue
      require(needed.satisfiedBy(engineFeatures)) {
        "trait '${trait.name}': modifier point $p needs the ${needed.text} ${needed.noun}"
      }
    }
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
