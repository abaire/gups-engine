package gups.pluginapi.host

import gups.core.model.WorldSchema
import gups.core.world.CommandRegistry
import gups.core.world.FastCommand
import gups.core.world.Feature
import gups.core.world.Features
import gups.pluginapi.GupsPlugin
import gups.pluginapi.PluginApi
import gups.pluginapi.PluginChoice
import gups.pluginapi.PluginCreation
import gups.pluginapi.PluginRefused
import gups.script.WorldDir
import gups.script.WorldModule
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ServiceLoader
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

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
    return register(
      survivors,
      log,
      warnings,
      EngineContext(module.features, module.rename, module.schema),
    )
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
    val unique = dropClashingProviders(found, warn)
    val dependency =
      FeatureDependency<Pair<GupsPlugin, URLClassLoader>>(
        required = { it.first.requiredFeatures },
        provided = { it.first.providedFeatures },
        id = { it.first.id },
        close = { it.second.close() },
      )
    return dropUnsatisfied(unique, engine.ids, dependency, warn)
  }

  private fun dropClashingProviders(
    found: List<Pair<GupsPlugin, URLClassLoader>>,
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
    return survivors
  }

  /** How to read a plugin-like entry's feature needs and release it when dropped. */
  private class FeatureDependency<T>(
    val required: (T) -> Set<String>,
    val provided: (T) -> Set<String>,
    val id: (T) -> String,
    val close: (T) -> Unit,
  )

  /**
   * Repeatedly drops entries needing a feature that neither the engine nor a surviving entry
   * provides, until none is dropped; each drop is warned about and [close]d.
   */
  private fun <T> dropUnsatisfied(
    entries: List<T>,
    engineIds: Collection<String>,
    dependency: FeatureDependency<T>,
    warn: (String) -> Unit,
  ): List<T> {
    var kept = entries
    while (true) {
      val available = engineIds + kept.flatMap(dependency.provided).toSet()
      val (ok, dropped) =
        kept.partition { missingAmong(dependency.required(it), available) == null }
      for (e in dropped) {
        val missing = missingAmong(dependency.required(e), available)
        warn("plugin '${dependency.id(e)}': needs the '$missing' feature")
        runCatching { dependency.close(e) }
      }
      if (dropped.isEmpty()) return ok
      kept = ok
    }
  }

  /** The first feature in [required] that is not in [available], sorted. */
  private fun missingAmong(required: Set<String>, available: Collection<String>): String? =
    required.sorted().firstOrNull { it !in available }

  /** What the engine fixes for a world: its features, command renames and schema. */
  internal class EngineContext(
    val features: Features = Features.ALL,
    val rename: Map<String, String> = emptyMap(),
    val schema: WorldSchema = WorldSchema.EMPTY,
  )

  /** Runs each plugin's `register` in id order; a throwing, clashing or refusing one is skipped. */
  internal fun register(
    plugins: List<Pair<GupsPlugin, URLClassLoader?>>,
    log: (String) -> Unit,
    warnings: MutableList<String> = mutableListOf(),
    engine: EngineContext = EngineContext(),
  ): PluginHost {
    val loaded = mutableListOf<LoadedPlugin>()
    val allFeatures =
      (engine.features.ids + plugins.flatMap { it.first.providedFeatures }.toSet()).toSet()
    for ((plugin, loader) in plugins.sortedBy { it.first.id }) {
      val reg = newRegistry(plugin, loaded, engine, allFeatures)
      try {
        plugin.register(reg)
        reg.creation?.let { validateCreation(it, reg) }
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
        skipped("plugin '${plugin.id}': refused: ${e.reason}", loader, warnings, log)
      } catch (e: Throwable) {
        skipped("plugin '${plugin.id}': register failed: ${e.message ?: e}", loader, warnings, log)
      }
    }
    // A provider that refused or threw no longer provides its features, so its dependents drop.
    // Clashes are not revisited: a plugin skipped for clashing stays skipped.
    val kept =
      dropUnsatisfied(
        loaded.toList(),
        engine.features.ids,
        FeatureDependency(
          required = { it.requiredFeatures },
          provided = { it.providedFeatures },
          id = { it.id },
          close = { it.loader?.close() },
        ),
      ) {
        warnings += it
        log(it)
      }
    return PluginHost(kept, warnings.toList(), log)
  }

  private fun skipped(
    text: String,
    loader: URLClassLoader?,
    warnings: MutableList<String>,
    log: (String) -> Unit,
  ) {
    warnings += text
    log(text)
    runCatching { loader?.close() }
  }

  private fun newRegistry(
    plugin: GupsPlugin,
    loaded: List<LoadedPlugin>,
    engine: EngineContext,
    allFeatures: Set<String>,
  ) =
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
      worldSchema = engine.schema,
      engineFeatures = engine.features,
      features = allFeatures,
      log = { w -> Facades.logOf(w) },
      commandCheck = CommandRegistry(features = engine.features, rename = engine.rename),
    )

  private val RESERVED_OPTION_IDS = setOf("back", "cancel", "help", "yes", "no")

  private fun validateCreation(c: PluginCreation, reg: PluginRegistryImpl) {
    require(c.choices.isNotEmpty() || c.steps.isNotEmpty()) {
      "creation provider must declare at least one choice or step"
    }
    val choiceIds = mutableSetOf<String>()
    for (choice in c.choices) {
      require(PluginRegistryImpl.MEMBER.matches(choice.id)) {
        "choice id '${choice.id}' must match [a-z][a-z0-9_]*"
      }
      require(choiceIds.add(choice.id)) { "duplicate choice id '${choice.id}'" }
      validateOptions(choice, reg)
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

  private fun validateOptions(choice: PluginChoice, reg: PluginRegistryImpl) {
    require(choice.options.isNotEmpty()) {
      "choice '${choice.id}' must declare at least one option"
    }
    val optionIds = mutableSetOf<String>()
    for (opt in choice.options) {
      require(PluginRegistryImpl.MEMBER.matches(opt.id)) {
        "option id '${opt.id}' in choice '${choice.id}' must match [a-z][a-z0-9_]*"
      }
      require(opt.id !in RESERVED_OPTION_IDS) {
        "option id '${opt.id}' in choice '${choice.id}' is reserved"
      }
      require(optionIds.add(opt.id)) { "duplicate option id '${opt.id}' in choice '${choice.id}'" }
      for (t in opt.traits) {
        require(t in reg.traits) {
          "option '${opt.id}' in choice '${choice.id}' names trait '$t', which is not registered by this plugin"
        }
      }
    }
  }

  fun sha256(file: Path): String =
    "sha256:" +
      MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") {
        "%02x".format(it)
      }
}
