package gups.pluginapi.host

import gups.core.Role
import gups.core.model.Actor
import gups.core.model.WorldSchema
import gups.core.model.didYouMean
import gups.core.world.Action
import gups.core.world.CategoryDef
import gups.core.world.CommandCategory
import gups.core.world.CommandDef
import gups.core.world.CommandRegistry
import gups.core.world.Diagnostic
import gups.core.world.ENGINE_GMCP_PACKAGES
import gups.core.world.FastCommand
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.PluginEffectRunner
import gups.core.world.TickCommand
import gups.core.world.World
import gups.pluginapi.GupsPlugin
import gups.pluginapi.PluginActor
import gups.pluginapi.PluginApi
import gups.pluginapi.PluginCommandCall
import gups.pluginapi.PluginCreation
import gups.pluginapi.PluginEffect
import gups.pluginapi.PluginEffectCall
import gups.pluginapi.PluginLane
import gups.pluginapi.PluginModifierPoint
import gups.pluginapi.PluginParam
import gups.pluginapi.PluginRegistry
import gups.pluginapi.PluginRestrictionCall
import gups.pluginapi.PluginRestrictionPoint
import gups.pluginapi.PluginRole
import gups.pluginapi.PluginTrait
import gups.pluginapi.PluginWorld
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
  val traits: List<PluginTraitInfo> = emptyList(),
  val restrictions: List<PluginRestrictionInfo> = emptyList(),
  val choices: List<PluginChoiceInfo> = emptyList(),
)

data class PluginTraitInfo(
  val name: String,
  val label: String,
  val doc: String,
  val statDeltas: Map<String, Int> = emptyMap(),
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  val resistDeltas: Map<String, Int> = emptyMap(),
  val flags: Set<String> = emptySet(),
  val modifiers: Set<String> = emptySet(),
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

data class PluginGmcpInfo(val pkg: String, val doc: String)

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
  private val schema: WorldSchema = WorldSchema.EMPTY,
  private val features: Features = Features.ALL,
  /** The world's engine commands, renames applied, which a plugin command may not shadow. */
  commandCheck: CommandRegistry = CommandRegistry(),
  private val log: (World) -> (String) -> Unit,
) : PluginRegistry {
  val members = LinkedHashMap<String, Any>()
  val builtinInfo = mutableListOf<PluginMemberInfo>()
  val effects = mutableListOf<EffectReg>()
  val categories = mutableListOf<CategoryReg>()
  val commands = mutableListOf<CommandReg>()
  val gmcp = mutableListOf<GmcpReg>()
  val started = mutableListOf<(PluginWorld) -> Unit>()
  val traits = mutableMapOf<String, PluginTrait>()
  private var _creation: PluginCreation? = null
  val creation: PluginCreation?
    get() = _creation

  val restrictions =
    mutableMapOf<
      PluginRestrictionPoint,
      MutableList<Pair<String, (PluginRestrictionCall) -> String?>>,
    >()
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
    require(GMCP.matches(pkg)) {
      "GMCP package '$pkg' needs at least two dot-separated parts, such as Gups.Weather"
    }
    // Clients subscribe by prefix, so a package may not equal, contain or nest in a taken one.
    fun overlaps(t: String) = pkg == t || pkg.startsWith("$t.") || t.startsWith("$pkg.")
    ENGINE_GMCP_PACKAGES.firstOrNull { overlaps(it) }
      ?.let { throw IllegalArgumentException("GMCP package '$pkg' overlaps the engine's $it") }
    require(pkg !in takenGmcp && gmcp.none { it.pkg == pkg }) {
      "GMCP package '$pkg' is already registered"
    }
    takenGmcp
      .firstOrNull { overlaps(it) }
      ?.let { throw IllegalArgumentException("GMCP package '$pkg' overlaps another plugin's $it") }
    gmcp
      .firstOrNull { overlaps(it.pkg) }
      ?.let {
        throw IllegalArgumentException("GMCP package '$pkg' overlaps this plugin's ${it.pkg}")
      }
    gmcp += GmcpReg(pkg, doc, produce)
  }

  override fun onWorldStarted(fn: (PluginWorld) -> Unit) {
    started += fn
  }

  override fun addTrait(trait: PluginTrait) {
    require(MEMBER.matches(trait.name)) { "trait name '${trait.name}' must match [a-z][a-z0-9_]*" }
    require(trait.name !in traits && trait.name !in takenTraits) {
      "trait '${trait.name}' is already registered"
    }
    for (s in trait.statDeltas.keys) {
      require(s in schema.statNames) { "unknown stat '$s'${didYouMean(s, schema.statNames)}" }
    }
    for (v in trait.maxVitalDeltas.keys) {
      require(v in schema.vitalNames) { "unknown vital '$v'${didYouMean(v, schema.vitalNames)}" }
    }
    for (d in trait.resistDeltas.keys) {
      require(d in schema.damageTypes) {
        "unknown damage type '$d'${didYouMean(d, schema.damageTypes)}"
      }
    }
    require("stunned" !in trait.flags) { "trait '${trait.name}': flags cannot contain 'stunned'" }
    for (p in trait.modifiers.keys) {
      if (
        p in
          setOf(
            PluginModifierPoint.TO_HIT,
            PluginModifierPoint.DAMAGE,
            PluginModifierPoint.INITIATIVE,
          )
      ) {
        require(Feature.COMBAT in features) {
          "trait '${trait.name}': modifier point $p needs the combat feature"
        }
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
      require(Feature.SKILLS in features) { "restriction point $point needs the skills feature" }
    }
    restrictions.getOrPut(point) { mutableListOf() }.add(doc to check)
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
    private val GMCP = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
  }
}

/** One plugin that loaded, with what it registered. */
internal class LoadedPlugin(
  val id: String,
  val doc: String,
  val reg: PluginRegistryImpl,
  val loader: URLClassLoader?,
)

/**
 * The world's loaded plugins: their script globals, and the commands, effects, GMCP producers and
 * start callbacks [install] and [started] wire into a world.
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

  /** The ids that loaded, sorted. */
  val ids: List<String>
    get() = plugins.map { it.id }

  private fun facade(world: World, id: String) = WorldFacade(world, id, log)

  private fun guard(world: World, id: String, what: String, block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      world.record(Diagnostic(world.pulse, "plugin", "plugin '$id': $what failed: $e", e))
    }
  }

  override fun install(world: World) {
    Facades.registerLog(world, log)

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
    }
    val effects =
      plugins.flatMap { p -> p.reg.effects.map { (p.id to it.name) to (p.id to it) } }.toMap()
    world.pluginEffects = PluginEffectRunner { w, effect, caster, target ->
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
        gmcp = p.reg.gmcp.map { PluginGmcpInfo(it.pkg, it.doc) }.sortedBy { it.pkg },
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
              )
            }
            .sortedBy { it.name },
        restrictions =
          p.reg.restrictions.flatMap { (point, list) ->
            list.map { (doc, _) -> PluginRestrictionInfo(point.name.lowercase(), doc) }
          },
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
      PluginLoader.register(plugins.map { it to null }, log, schema = schema, features = features)
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
      val missing = missingFeature(plugin, module.features)
      when {
        plugin.id != id ->
          skip("the jar's plugin id is '${plugin.id}', but MODULE.gups pins it as '$id'")
        plugin.pluginApiVersion != PluginApi.VERSION ->
          skip(
            "built for plugin API version ${plugin.pluginApiVersion}; this engine has version ${PluginApi.VERSION}"
          )
        missing != null -> skip("needs the '$missing' feature")
        else -> {
          found += plugin to loader
          continue
        }
      }
      loader.close()
    }
    val host = register(found, log, warnings, module.features, module.rename, module.schema)
    return host
  }

  /** The first feature [plugin] requires that [features] lacks, whether unknown or off. */
  private fun missingFeature(plugin: GupsPlugin, features: Features): String? =
    plugin.requiredFeatures.sorted().firstOrNull { id ->
      Feature.byId(id).let { it == null || it !in features }
    }

  /** Runs each plugin's `register` in id order; a throwing or clashing one is skipped. */
  internal fun register(
    plugins: List<Pair<GupsPlugin, URLClassLoader?>>,
    log: (String) -> Unit,
    warnings: MutableList<String> = mutableListOf(),
    features: Features = Features.ALL,
    rename: Map<String, String> = emptyMap(),
    schema: WorldSchema = WorldSchema.EMPTY,
  ): PluginHost {
    val loaded = mutableListOf<LoadedPlugin>()
    val logOf: (World) -> (String) -> Unit = { w -> Facades.logOf(w) }
    for ((plugin, loader) in plugins.sortedBy { it.first.id }) {
      val reg =
        PluginRegistryImpl(
          plugin.id,
          takenCommands = loaded.flatMap { p -> p.reg.commands.map { FastCommand(it.name) {} } },
          takenGmcp = loaded.flatMap { p -> p.reg.gmcp.map { it.pkg } }.toSet(),
          takenTraits = loaded.flatMap { p -> p.reg.traits.keys }.toSet(),
          hasCreation = loaded.any { it.reg.creation != null },
          schema = schema,
          features = features,
          log = logOf,
          commandCheck = CommandRegistry(features = features, rename = rename),
        )
      try {
        plugin.register(reg)
        reg.creation?.let { c ->
          require(c.choices.isNotEmpty()) { "creation provider must declare at least one choice" }
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
        }
        loaded += LoadedPlugin(plugin.id, plugin.doc, reg, loader)
      } catch (e: Throwable) {
        val text = "plugin '${plugin.id}': register failed: ${e.message ?: e}"
        warnings += text
        log(text)
        runCatching { loader?.close() }
      }
    }
    return PluginHost(loaded, warnings.toList(), log)
  }

  fun sha256(file: Path): String =
    "sha256:" +
      MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") {
        "%02x".format(it)
      }
}
