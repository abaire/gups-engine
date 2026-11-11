package gups.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import gups.core.EngineInfo
import gups.core.world.CommandRegistry
import gups.harness.HarnessApi
import gups.pluginapi.host.PluginChoiceInfo
import gups.pluginapi.host.PluginInfo
import gups.pluginapi.host.PluginMemberInfo
import gups.pluginapi.host.PluginTraitInfo
import gups.script.ApiDiff
import gups.script.LoadResult
import gups.script.MethodSpec
import gups.script.ParamSpec
import gups.script.ScriptApi
import gups.script.ScriptLimits
import gups.script.ScriptLoader
import gups.script.WorldDir
import gups.script.WorldMessages
import gups.script.WorldModule
import java.io.File

class DescribeWorld : CliktCommand(name = "describe-world") {
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)
  val out by
    option("--out", metavar = "PATH", help = "Write the JSON here instead of stdout.").file()

  override fun help(context: Context) = "Describe a world's schema and content as JSON."

  override fun run() {
    out?.let { checkOutFile(it) }
    val dir = WorldDir((world ?: File(".")).toPath())
    val problems = mutableListOf<String>()
    val json = withPlugins(dir) { host -> describeWorld(dir, host, host.describe(), problems) }
    if (json == null) {
      for (p in problems) echo("error: $p", err = true)
      throw ProgramResult(1)
    }
    writeOutput(json)
  }

  private fun checkOutFile(target: File) {
    if (target.isDirectory) throw UsageError("--out: ${target.path} is a directory, give a file")
  }

  private fun writeOutput(json: String) {
    val target = out
    if (target == null) {
      echo(json, trailingNewline = false)
      return
    }
    target.absoluteFile.parentFile?.mkdirs()
    target.writeText(json, Charsets.UTF_8)
  }
}

class Docs : CliktCommand(name = "docs") {
  val json by option("--json", help = "Write the machine-readable API model (api.json).").flag()
  val out by
    option("--out", help = "Output directory (default build/docs), or with --json a .json file.")
      .file()
  val world by
    option("--world", help = "Course world directory; include its plugins.")
      .file(mustExist = true, canBeFile = false)
  val updateBaseline by
    option("--update-baseline", help = "Before a release: write docs/api/api-N.json.").flag()
  val docsDir by
    option(
        "--docs-dir",
        metavar = "DIR",
        help = "The engine's docs directory, for --update-baseline (default: docs).",
      )
      .file(canBeFile = false)
      .default(File("docs"))

  override fun help(context: Context) = "Generate the API reference."

  override fun run() {
    when {
      updateBaseline -> writeBaseline()
      json -> writeJson()
      else -> writeHtml()
    }
  }

  private fun writeHtml() {
    val dir = out ?: File("build/docs")
    if (dir.isFile || dir.name.endsWith(".json"))
      throw UsageError("--out: give a directory for the HTML reference, not ${dir.name}")
    val (plugins, spec) = world?.let(::loadWorldDocs) ?: (emptyList<PluginDocSpec>() to null)
    val files =
      DocsRenderer.render(
        ScriptApi.model(HarnessApi.specs()),
        RenderOptions(plugins = plugins, world = spec),
      )
    dir.mkdirs()
    for ((name, text) in files) dir.resolve(name).writeText(text, Charsets.UTF_8)
    echo("Wrote the API reference (api_version ${EngineInfo.API_VERSION}) to $dir")
  }

  /** The plugins and world description that `--world` adds to the HTML reference. */
  private fun loadWorldDocs(world: File): Pair<List<PluginDocSpec>, WorldDocSpec> {
    val worldDir = WorldDir(world.toPath())
    val module = readModule(worldDir)
    val messages = readMessages(worldDir)
    return loadPlugins(worldDir, module) { echo("warning: $it", err = true) }
      .use { host ->
        val result =
          ScriptLoader(worldDir, ScriptLimits(), plugins = host).load(worldDir.packages())
        host.describe().map(::toDocSpec) to
          worldDocSpec(module, messages, scriptGmcpPackages(result))
      }
  }

  private fun readModule(worldDir: WorldDir): WorldModule {
    val (module, error) = WorldModule.read(worldDir)
    if (error != null) throw UsageError("--world: ${error.render()}")
    module.problems().firstOrNull()?.let { throw UsageError("--world: $it") }
    return module
  }

  private fun readMessages(worldDir: WorldDir): WorldMessages {
    val (messages, error) = WorldMessages.read(worldDir)
    if (error != null) throw UsageError("--world: ${error.render()}")
    messages.problems().firstOrNull()?.let { throw UsageError("--world: $it") }
    return messages
  }

  /** Freezes the current API as the baseline of api_version N. */
  private fun writeBaseline() {
    val otherOptions = listOf(json, out != null, world != null)
    if (otherOptions.any { it })
      throw UsageError("--update-baseline takes no --json, --out or --world")
    val n = EngineInfo.API_VERSION
    val file = docsDir.resolve("api").also { it.mkdirs() }.resolve("api-$n.json")
    file.writeText(ApiDiff.normalize(ScriptApi.model(HarnessApi.specs()).toJson()), Charsets.UTF_8)
    echo("Wrote $file")
  }

  private fun writeJson() {
    if (world != null)
      throw UsageError("--world is for the HTML reference; api.json never includes plugins")
    val text = ScriptApi.model(HarnessApi.specs()).toJson()
    val dest = out
    if (dest == null) {
      echo(text, trailingNewline = false)
      return
    }
    jsonTarget(dest).writeText(text, Charsets.UTF_8)
  }

  /** A .json path is the file itself; anything else is a directory. */
  private fun jsonTarget(dest: File): File =
    when {
      dest.name.endsWith(".json") -> dest.also { it.absoluteFile.parentFile?.mkdirs() }
      dest.isFile -> throw UsageError("--out: $dest is a file; give a directory or a .json path")
      '.' in dest.name ->
        throw UsageError("--out: give a directory or a .json path, not ${dest.name}")
      else -> dest.also { it.mkdirs() }.resolve("api.json")
    }
}

/** What `plugins.html` shows about a plugin. */
internal fun toDocSpec(p: PluginInfo): PluginDocSpec {
  fun method(m: PluginMemberInfo) =
    MethodSpec(
      m.name,
      m.doc,
      m.params.map {
        ParamSpec(
          it.name,
          positional = true,
          named = true,
          default = if (it.required) null else it.defaultValue.toString(),
          doc = it.doc,
        )
      },
    )
  return PluginDocSpec(
    id = p.id,
    doc = p.doc,
    builtins = p.builtins.map(::method),
    effects = p.effects.map(::method),
    commands =
      p.commands.map {
        PluginCommandDocSpec(it.name, it.lane.name.lowercase(), it.role.name.lowercase(), it.doc)
      },
    gmcp = p.gmcp.map { PluginGmcpDocSpec(it.pkg, it.doc, it.kind) },
    hooks = p.hooks.map { PluginHookDocSpec(it.name, it.doc) },
    traits = p.traits.map(::toTraitDocSpec),
    restrictions = p.restrictions.map { PluginRestrictionDocSpec(it.point, it.doc) },
    containmentFilters = p.containmentFilters.map { PluginFilterDocSpec(it.doc) },
    ownedBehaviors = p.ownedBehaviors.map { PluginOwnedBehaviorsDocSpec(it.doc) },
    dispositionModifiers = p.dispositionModifiers.map { PluginDispositionModifierDocSpec(it.doc) },
    factions = p.factions.map { PluginFactionAdjusterDocSpec(it.doc) },
    choices = p.choices.map(::toChoiceDocSpec),
    steps = p.steps.map { PluginStepDocSpec(it.id, it.title) },
    rulesExports = p.rulesExports.map { PluginRulesExportDocSpec(it.name, it.doc, it.type) },
  )
}

private fun toTraitDocSpec(t: PluginTraitInfo) =
  PluginTraitDocSpec(
    t.name,
    t.label,
    t.doc,
    t.statDeltas,
    t.maxVitalDeltas,
    t.resistDeltas,
    t.flags,
    t.modifiers,
  )

private fun toChoiceDocSpec(c: PluginChoiceInfo) =
  PluginChoiceDocSpec(
    c.id,
    c.title,
    c.prompt,
    c.options.map { o -> PluginOptionDocSpec(o.id, o.label, o.summary, o.help, o.traits) },
  )

/** GMCP packages declared by the packages that loaded, for `world.html`. */
internal fun scriptGmcpPackages(result: LoadResult): List<ScriptGmcpDocSpec> =
  result.packages
    .filterKeys { it !in result.failed }
    .values
    .flatMap { it.gmcpPackages }
    .map { ScriptGmcpDocSpec(it.name, it.doc, it.pkg) }

/** What `world.html` and the feature filter of `gups docs --world` need from a world. */
fun worldDocSpec(
  module: WorldModule,
  messages: WorldMessages,
  gmcp: List<ScriptGmcpDocSpec> = emptyList(),
): WorldDocSpec =
  WorldDocSpec(
    module.schema,
    module.features,
    module.rename,
    CommandRegistry.describe(module.features, module.rename),
    messages.texts,
    gmcp,
  )
