package gups.script

import com.google.common.collect.ImmutableMap
import gups.core.HookRef
import gups.core.Label
import gups.core.model.Prototypes
import gups.core.model.WorldSchema
import gups.core.model.ZoneProto
import gups.core.model.didYouMean
import gups.core.world.CommandRegistry
import gups.core.world.FeatureRequirement
import gups.core.world.Features
import gups.core.world.HookName
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Module
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkFunction
import net.starlark.java.eval.StarlarkSemantics
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.syntax.AssignmentStatement
import net.starlark.java.syntax.DefStatement
import net.starlark.java.syntax.FileOptions
import net.starlark.java.syntax.Identifier
import net.starlark.java.syntax.LoadStatement
import net.starlark.java.syntax.Location
import net.starlark.java.syntax.ParserInput
import net.starlark.java.syntax.Program
import net.starlark.java.syntax.StarlarkFile
import net.starlark.java.syntax.SyntaxError

internal val SEMANTICS: StarlarkSemantics = StarlarkSemantics.DEFAULT

internal val FILE_OPTIONS: FileOptions =
  FileOptions.builder().requireLoadStatementsFirst(true).allowToplevelRebinding(false).build()

/**
 * The builtins every package file sees under [schema]: declarations, effect primitives, constants
 * and the schema's constructors.
 */
internal fun packageGlobals(
  schema: WorldSchema,
  features: Features = Features.ALL,
  knownTraits: () -> Set<String> = { emptySet() },
): Map<String, Any> {
  val b = ImmutableMap.builder<String, Any>()
  Starlark.addMethods(b, DeclarationBuiltins(schema, features, knownTraits))
  Starlark.addMethods(b, EffectBuiltins(schema, features))
  Starlark.addMethods(b, BehaviorBuiltins(features))
  b.putAll(DeclarationBuiltins.constants(schema))
  b.putAll(schemaGlobals(schema))
  return b.build()
}

/** The builtins under no schema, which is what the API model documents. */
internal val PACKAGE_GLOBALS: Map<String, Any> by lazy { packageGlobals(WorldSchema.EMPTY) }

/**
 * The outcome of loading packages. Loading is all-or-nothing per package: a package with any error,
 * or that depends on one that failed, is left out of [packages] and reported in [errors].
 */
class LoadResult
internal constructor(
  val packages: Map<String, ScriptPackage>,
  val errors: List<LoadError>,
  private val modules: Map<Label, Module>,
  /** Evaluated `*_test.star` modules, for the harness. */
  val testModules: Map<Label, Module>,
  /** Test files that failed to evaluate. Their package still loads. */
  val testErrors: Map<Label, List<LoadError>>,
) {
  val ok: Boolean
    get() = errors.isEmpty()

  /** Packages that failed, each with at least one entry in [errors]. */
  val failed: Set<String>
    get() = errors.mapTo(sortedSetOf()) { it.pkg }

  private val hookIndex: Map<Label, Map<HookName, HookRef>> =
    packages.values.flatMap { it.hooks.entries }.associate { it.key to it.value }

  private val customHookIndex: Map<Label, Map<String, HookRef>> =
    packages.values.flatMap { it.customHooks.entries }.associate { it.key to it.value }

  /** The frozen prototype table of every package that loaded. */
  fun prototypes(): Prototypes {
    val ps = packages.values
    return Prototypes(
      rooms = ps.flatMap { it.rooms },
      mobs = ps.flatMap { it.mobs },
      items = ps.flatMap { it.items },
      skills = ps.flatMap { it.skills },
      zones = ps.map { ZoneProto(it.path, it.title, it.resetPulses, it.modifiers) },
      regions = ps.flatMap { it.regions },
      factions = ps.flatMap { it.factions },
    )
  }

  fun hooks(label: Label): Map<HookName, HookRef> = hookIndex[label].orEmpty()

  /** Custom hooks per prototype label, run with `ctx.call_hook`. */
  fun customHooks(label: Label): Map<String, HookRef> = customHookIndex[label].orEmpty()

  /** The function a [HookRef] names now, or null if its file or function is gone. */
  internal fun function(ref: HookRef): StarlarkFunction? =
    modules[ref.file]?.getGlobal(ref.name) as? StarlarkFunction

  /** Renders every error, one block per error. */
  fun report(): String = errors.joinToString("\n") { it.render() }
}

/** What the load has evaluated about one package, and the errors and progress it made. */
internal class PkgState(val pkg: ScriptPackage) {
  val errors = mutableListOf<LoadError>()
  val deps = sortedSetOf<String>()
  var headerDone = false
  var contentDone = false
  var steps = 0L
  var startedNanos = 0L

  val failed: Boolean
    get() = errors.isNotEmpty()
}

/** The packages of [states] whose content evaluated and that made no error. */
internal fun livePackages(states: Map<String, PkgState>): List<PkgState> =
  states.values.filter { it.contentDone && !it.failed }

/**
 * Evaluates packages into frozen prototypes. Loading a package also loads every package it refers
 * to by label or `load()`.
 */
class ScriptLoader(
  val dir: WorldDir,
  private val limits: ScriptLimits = ScriptLimits(),
  /** Receives `print()` output from top-level code. */
  private val printSink: (String) -> Unit = {},
  /** Plugins providing globals, traits, creation, and restrictions. */
  val plugins: WorldPluginWiring = WorldPluginWiring.NONE,
  /** Plugin namespaces by id, predeclared in every package and test file. */
  val pluginGlobals: Map<String, Any> = plugins.globals,
  val knownTraits: () -> Set<String> = { plugins.traits },
  /** GMCP packages plugins registered, which script packages may not overlap. */
  val knownGmcp: () -> Set<String> = { plugins.gmcpPackages },
  /**
   * Whether packages include their `*_testdata.star` fixture files. Only test runs set it; every
   * other load leaves fixtures out, so content that names one fails to resolve.
   */
  val fixtures: Boolean = false,
) {
  /** The world's `MODULE.gups`; a world without a valid one has an empty schema. */
  val module: WorldModule by lazy { WorldModule.read(dir).first }

  val schema: WorldSchema
    get() = module.schema

  /** The builtins of every package, test and `@eval` file of this world. */
  internal val globals: Map<String, Any> by lazy {
    packageGlobals(schema, features, knownTraits) + pluginGlobals
  }

  val features: Features
    get() = module.features

  /**
   * Engine command names and the world's aliases for them, which no global command or exit may
   * take.
   */
  private val engineVerbs: Set<String> by lazy { engineVerbs(module) }

  /**
   * Loads [requested] (package paths such as `zones/tutorial`) and their dependencies. [tests] are
   * `*_test.star` files to evaluate as well, with [testGlobals] as their builtins. A test label in
   * [testSources] is read from that path instead of the world, so a file kept elsewhere can run as
   * if it sat in the label's package.
   */
  fun load(
    requested: Collection<String>,
    tests: Collection<Label> = emptyList(),
    testGlobals: Map<String, Any> = emptyMap(),
    testSources: Map<Label, Path> = emptyMap(),
  ): LoadResult = Session(testSources).run(requested, tests, testGlobals)

  private inner class Session(private val testSources: Map<Label, Path>) {
    val states = LinkedHashMap<String, PkgState>()
    val modules = HashMap<Label, Module>()
    val testModules = LinkedHashMap<Label, Module>()
    val testErrors = LinkedHashMap<Label, MutableList<LoadError>>()

    /** Files that failed to evaluate, so dependents get one clear error. */
    val badFiles = HashSet<Label>()
    val inProgress = LinkedHashSet<Label>()
    val queue = ArrayDeque<String>()
    val requestedMissing = mutableListOf<LoadError>()

    fun run(
      requested: Collection<String>,
      tests: Collection<Label>,
      testGlobals: Map<String, Any>,
    ): LoadResult {
      for (p in requested.distinct()) {
        if (dir.hasPackage(p)) {
          queue += p
        } else {
          requestedMissing +=
            LoadError(
              p,
              "missing",
              "no package //$p (expected ${dir.packageDir(p)}/${WorldDir.PACKAGE_FILE})",
            )
        }
      }
      drain()
      for (t in tests) {
        evalFile(t, schemaGlobals(schema) + testGlobals + pluginGlobals, test = true)
        drain()
      }
      return finish()
    }

    fun drain() {
      while (queue.isNotEmpty()) {
        val p = queue.removeFirst()
        val st = header(p)
        if (st == null) continue
        if (st.contentDone || st.failed) continue
        st.contentDone = true
        loadContent(st)
      }
    }

    /** Evaluates a package's files and follows its label references into other packages. */
    private fun loadContent(st: PkgState) {
      for (file in contentFiles(st.pkg.path)) loadFile(file, st)
      for (ref in st.pkg.refs) dependOn(st, ref.label.pkg)
      for (ref in st.pkg.factionRefs) dependOn(st, ref.label.pkg)
      findMigrate(st)
      if (st.pkg.kind == PackageKind.RULES) findRulesExports(st)
    }

    fun contentFiles(pkg: String): List<Label> = dir.files(pkg, tests = false, fixtures = fixtures)

    /** One file of the package may define a top-level `migrate`. */
    fun findMigrate(st: PkgState) {
      val defs =
        contentFiles(st.pkg.path).filter { file ->
          val m = modules[file]
          (m?.globals?.get("migrate") as? StarlarkFunction)?.module === m
        }
      if (defs.size > 1)
        st.errors +=
          LoadError(
            st.pkg.path,
            "migrate",
            "migrate() is defined in more than one file: ${defs.joinToString(", ")}",
          )
      defs.singleOrNull()?.let { st.pkg.migrate = HookRef(it, "migrate") }
    }

    /**
     * Finds the file that defines each rules export. Any non-test file of the package may define
     * some of them, but not the same one twice; every export the enabled features need must exist.
     */
    fun findRulesExports(st: PkgState) {
      val files = contentFiles(st.pkg.path)
      val names = RulesExport.entries.map { it.scriptName } + plugins.rulesExports.keys.sorted()
      for (name in names) {
        val defs =
          files.filter { file ->
            val m = modules[file]
            (m?.globals?.get(name) as? StarlarkFunction)?.module === m
          }
        if (defs.size > 1)
          st.errors +=
            LoadError(
              st.pkg.path,
              "rules",
              "$name() is defined in more than one file: ${defs.joinToString(", ")}",
            )
        defs.singleOrNull()?.let { st.pkg.rulesExports[name] = HookRef(it, name) }
      }
      if (st.failed) return
      for (name in missingRulesExports(st.pkg.rulesExports.keys, features)) st.errors +=
        LoadError(st.pkg.path, "rules", "the rules package must define $name(...)")
    }

    fun dependOn(st: PkgState, dep: String) {
      if (dep == st.pkg.path || dep == "engine") return
      st.deps += dep
      if (dir.hasPackage(dep) && states[dep]?.contentDone != true) queue += dep
    }

    /** Evaluates `package.star` once; null if the package does not exist. */
    fun header(p: String): PkgState? {
      states[p]?.let {
        return it
      }
      if (!dir.hasPackage(p)) return null
      val st = PkgState(ScriptPackage(p))
      states[p] = st
      st.startedNanos = System.nanoTime()
      val file = Label("//$p:${WorldDir.PACKAGE_FILE}")
      val ctx = evalFile(file, globals, header = true)
      st.headerDone = true
      if (ctx == null) return st
      if (!ctx.packageDeclared && !st.failed) {
        st.errors += LoadError(p, "package", "package.star must call package(...)", file.v, 1, 1)
      }
      return st
    }

    /** Makes sure [file] is evaluated, on behalf of [requester]. Null if it failed. */
    fun loadFile(file: Label, requester: PkgState?): Module? {
      modules[file]?.let {
        return it
      }
      val fail = { message: String ->
        requester?.errors?.add(LoadError(requester.pkg.path, "load", message))
        null
      }
      if (file in badFiles) return fail("$file failed to load")
      if (file in inProgress)
        return fail(
          "load cycle: " + (inProgress.dropWhile { it != file } + file).joinToString(" -> ")
        )
      val owner = header(file.pkg) ?: return fail("no package //${file.pkg} for $file")
      if (owner.failed) return fail("//${file.pkg} failed to load")
      evalFile(file, globals)
      return modules[file] ?: fail("$file failed to load")
    }

    /**
     * Parses, resolves and runs one file, then freezes it. Errors are charged to the file's
     * package, or to [testErrors] for a test file. Returns the file's load context.
     */
    fun evalFile(
      file: Label,
      globals: Map<String, Any>,
      header: Boolean = false,
      test: Boolean = false,
    ): LoadCtx? {
      val st = states.getValue(file.pkg).takeUnless { test } ?: header(file.pkg)!!
      val err = { e: LoadError -> record(file, test, e) }
      val parsed = parseFile(file, st, sourceOf(file, test), err) ?: return null
      val module = Module.withPredeclared(SEMANTICS, globals)
      module.clientData = FileInfo(file, file.pkg, test)
      val program = compileFile(parsed, module, st, err) ?: return null
      val loaded = HashMap<String, Module>()
      inProgress += file
      val loadsOk =
        try {
          evalLoads(program, loaded, file, st, test, err)
        } finally {
          inProgress -= file
        }
      if (!loadsOk) return null
      val ctx =
        LoadCtx(module.clientData as FileInfo, st.pkg, header, engineVerbs) { checkWall(st) }
      if (!runFile(program, module, loaded, ctx, st, file, test, err)) return null
      if (!checkTopLevelBindings(file, parsed, module, st, test, err)) return null
      if (test) testModules[file] = module else modules[file] = module
      return ctx
    }

    /** The path a file's source reads from: a test file may be kept outside the world. */
    private fun sourceOf(file: Label, test: Boolean): Path =
      (if (test) testSources[file] else null) ?: dir.fileOf(file)

    /** Charges [e] to the file's package, or to [testErrors] for a test file, and marks it bad. */
    private fun record(file: Label, test: Boolean, e: LoadError) {
      if (test) testErrors.getOrPut(file) { mutableListOf() } += e
      else states.getValue(file.pkg).errors += e
      badFiles += file
    }

    /** Reads and parses a file's source; null after charging a missing or syntax error. */
    private fun parseFile(
      file: Label,
      st: PkgState,
      source: Path,
      err: (LoadError) -> Unit,
    ): StarlarkFile? {
      if (!source.isRegularFile()) {
        err(LoadError(st.pkg.path, "missing", "no file $source"))
        return null
      }
      val parsed =
        StarlarkFile.parse(ParserInput.fromString(source.readText(), file.v), FILE_OPTIONS)
      if (!parsed.ok()) {
        parsed.errors().forEach { err(syntaxError(st.pkg.path, it)) }
        return null
      }
      return parsed
    }

    private fun compileFile(
      parsed: StarlarkFile,
      module: Module,
      st: PkgState,
      err: (LoadError) -> Unit,
    ): Program? =
      try {
        Program.compileFile(parsed, module)
      } catch (e: SyntaxError.Exception) {
        e.errors().forEach { err(syntaxError(st.pkg.path, it)) }
        null
      }

    /** Resolves and loads every `load()` of [program] into [loaded]; false on a failed load. */
    private fun evalLoads(
      program: Program,
      loaded: HashMap<String, Module>,
      file: Label,
      st: PkgState,
      test: Boolean,
      err: (LoadError) -> Unit,
    ): Boolean {
      for (spec in program.loads) {
        val target =
          try {
            loadLabel(spec, file, test)
          } catch (e: EvalException) {
            err(LoadError(st.pkg.path, "load", e.message ?: "bad load", file.v))
            return false
          }
        if (!test) dependOn(st, target.pkg)
        val m = loadFile(target, if (test) null else st)
        if (m == null) {
          err(LoadError(st.pkg.path, "load", "load(\"$spec\"): $target failed to load", file.v))
          return false
        }
        loaded[spec] = m
      }
      return true
    }

    /**
     * Runs [program] under the load limits of the loading package. Test files run under a throwaway
     * context whose step and wall-clock counters never fire.
     */
    private fun runFile(
      program: Program,
      module: Module,
      loaded: Map<String, Module>,
      ctx: LoadCtx,
      st: PkgState,
      file: Label,
      test: Boolean,
      err: (LoadError) -> Unit,
    ): Boolean {
      try {
        Mutability.create(file.v).use { mu ->
          val thread = StarlarkThread(mu, SEMANTICS)
          thread.setLoader { loaded[it] }
          thread.setPrintHandler { _, msg -> printSink(msg) }
          if (!test) {
            thread.setThreadLocal(LoadCtx::class.java, ctx)
            thread.setMaxExecutionSteps((limits.loadSteps - st.steps).coerceAtLeast(1))
          } else {
            thread.setThreadLocal(
              LoadCtx::class.java,
              LoadCtx(module.clientData as FileInfo, st.pkg, header = false, engineVerbs) {},
            )
          }
          try {
            Starlark.execFileProgram(program, module, thread)
          } finally {
            if (!test) st.steps += thread.executedSteps
          }
        }
        return true
      } catch (e: EvalException) {
        err(evalError(st.pkg.path, file, e))
        return false
      }
    }

    /** Rejects top-level names a plugin owns and rules exports the features forbid. */
    private fun checkTopLevelBindings(
      file: Label,
      parsed: StarlarkFile,
      module: Module,
      st: PkgState,
      test: Boolean,
      err: (LoadError) -> Unit,
    ): Boolean =
      checkPluginClashes(file, parsed, module, st, err) &&
        checkRulesExports(file, parsed, module, st, test, err)

    private fun checkPluginClashes(
      file: Label,
      parsed: StarlarkFile,
      module: Module,
      st: PkgState,
      err: (LoadError) -> Unit,
    ): Boolean {
      val clashes = module.globals.keys.filter { it in pluginGlobals }.sorted()
      if (clashes.isEmpty()) return true
      for (name in clashes) {
        err(
          LoadError(
            st.pkg.path,
            "plugin",
            "cannot define '$name': it is the namespace of plugin '$name'",
            file.v,
            bindingLine(parsed, name),
          )
        )
      }
      return false
    }

    private fun checkRulesExports(
      file: Label,
      parsed: StarlarkFile,
      module: Module,
      st: PkgState,
      test: Boolean,
      err: (LoadError) -> Unit,
    ): Boolean {
      if (test || st.pkg.kind != PackageKind.RULES) return true
      val forbidden = forbiddenRulesExports(module, features)
      if (forbidden.isNotEmpty()) {
        for ((name, req) in forbidden) {
          err(
            LoadError(
              st.pkg.path,
              "rules",
              "the rules package defines $name(...), which needs the ${req.text} ${req.noun}",
              file.v,
              bindingLine(parsed, name),
            )
          )
        }
        return false
      }
      val unknown =
        unknownRulesExports(module, plugins.rulesExports.keys).filter { name ->
          forbidden.none { it.first == name }
        }
      if (unknown.isNotEmpty()) {
        for (name in unknown) {
          err(
            LoadError(
              st.pkg.path,
              "rules",
              "'$name' is not a rules export; prefix helpers with '_'",
              file.v,
              bindingLine(parsed, name),
            )
          )
        }
        return false
      }
      return true
    }

    fun checkWall(st: PkgState) {
      if (limits.wallClock && System.nanoTime() - st.startedNanos > limits.loadWallNanos) {
        limitFail(
          "wall_clock",
          "loading //${st.pkg.path} took longer than ${limits.loadWallNanos / 1_000_000} ms",
        )
      }
    }

    fun loadLabel(spec: String, from: Label, test: Boolean): Label {
      val target = label(spec, from.pkg, "load(\"$spec\")")
      if (!target.name.endsWith(".star")) fail("load(\"$spec\"): load labels name a .star file")
      if (target.name == WorldDir.PACKAGE_FILE)
        fail("load(\"$spec\"): package.star can't be loaded")
      if (!test && target.name.endsWith(WorldDir.TEST_SUFFIX))
        fail("load(\"$spec\"): test files can only be loaded by other test files")
      if (WorldDir.isFixture(target.name) && !WorldDir.isFixture(from.name)) {
        if (!test)
          fail(
            "load(\"$spec\"): test fixture files can only be loaded by test files and other fixture files"
          )
      }
      return target
    }

    fun finish(): LoadResult {
      val checks = LoadChecks(dir, states, pluginGlobals, knownGmcp, engineVerbs, fixtures)
      propagate()
      checks.resolveLabels()
      checks.checkFactions()
      propagate()
      checks.checkNamespaces()
      checks.checkRegions()
      checks.checkGmcp()
      checks.checkCommands()
      propagate()
      dryRun()
      return result()
    }

    fun result(): LoadResult =
      LoadResult(
        live.associate { it.pkg.path to it.pkg },
        requestedMissing + states.values.flatMap { it.errors },
        modules,
        testModules,
        testErrors,
      )

    /**
     * Runs the rules dry run; a rules package that fails it fails, and so do its dependents. Then
     * checks every item's `wear` against the slots the rules named, failing the item's package.
     */
    fun dryRun() {
      val rules = states[RULES_PACKAGE]?.takeIf { it.contentDone && !it.failed } ?: return
      val run = dryRunRules(result(), schema, limits, DryRunEnv(features, plugins))
      val exports = rules.pkg.rulesExports
      rules.errors +=
        run.problems.map { problem ->
          // A problem starts with the rules function it came from; point at the file defining it.
          val export = Regex("^rules\\.(\\w+)").find(problem)?.groupValues?.get(1)
          LoadError(RULES_PACKAGE, "rules", "dry run: $problem", exports[export]?.file?.v)
        }
      run.wearSlots?.let(::checkWear)
      propagate()
    }

    /** Each item's `wear` names a slot or slot family of [wearSlots]. */
    fun checkWear(wearSlots: WearSlots) {
      for (st in live) {
        for (ref in st.pkg.wearRefs) {
          if (ref.wear in wearSlots.names) continue
          val families =
            if (wearSlots.families.isEmpty()) "none" else wearSlots.families.keys.joinToString(", ")
          st.errors +=
            LoadError(
              st.pkg.path,
              "slot",
              "item ${ref.item}: ${ref.param} = '${ref.wear}' is not an equipment slot or slot family${didYouMean(ref.wear, wearSlots.names)}; slots are ${wearSlots.slots.joinToString(", ")}; families are $families",
              ref.file,
              ref.line,
              ref.column,
              snippet(dir, ref.file, ref.line, ref.column),
            )
        }
      }
    }

    val live: List<PkgState>
      get() = livePackages(states)

    /** A package fails if anything it depends on failed. */
    fun propagate() {
      do {
        var changed = false
        for (st in live) {
          val bad = st.deps.firstOrNull { states[it]?.failed == true } ?: continue
          st.errors +=
            LoadError(st.pkg.path, "dependency", "depends on //$bad, which failed to load")
          changed = true
        }
      } while (changed)
    }

    fun syntaxError(pkg: String, e: SyntaxError): LoadError {
      val loc = e.location()
      return LoadError(
        pkg,
        "syntax",
        e.message(),
        loc.file(),
        loc.line(),
        loc.column(),
        snippet(dir, loc.file(), loc.line(), loc.column()),
      )
    }

    fun evalError(pkg: String, file: Label, e: EvalException): LoadError {
      val loc = innermost(e) ?: Location.fromFile(file.v)
      val kind =
        when {
          e.cause is ApiVersionMismatch -> "api_version"
          e.cause is LimitExceeded -> (e.cause as LimitExceeded).kind
          isStepLimit(e) -> "step_limit"
          else -> "eval"
        }
      val message =
        if (kind == "step_limit")
          "top-level evaluation of //$pkg exceeded ${limits.loadSteps} steps"
        else e.message ?: "error"
      val line = loc.line().takeIf { it > 0 }
      val col = loc.column().takeIf { it > 0 }
      return LoadError(
        pkg,
        kind,
        message,
        loc.file(),
        line,
        col,
        snippet(dir, loc.file(), line, col),
      )
    }
  }
}

/** Engine command names and [aliases] for them, which no global command or exit may take. */
internal fun engineVerbs(module: WorldModule): Set<String> =
  CommandRegistry(module.aliases, module.features, module.rename)
    .engineCommands
    .flatMap { listOf(it.name) + it.aliases }
    .toSet()

/** The innermost Starlark (not builtin) frame of an error's stack. */
internal fun innermost(e: EvalException): Location? =
  e.callStack.lastOrNull { it.location != Location.BUILTIN && it.location.line() > 0 }?.location

internal fun isStepLimit(e: EvalException): Boolean = e.message?.contains("too many steps") == true

/** A source line with a caret under [column], for error messages; null if the line is gone. */
internal fun snippet(dir: WorldDir, file: String?, line: Int?, column: Int?): String? {
  if (file == null || line == null) return null
  val text = runCatching { dir.sourceLine(Label(file), line) }.getOrNull() ?: return null
  return "    $text" +
    if (column != null && column > 0) "\n    " + " ".repeat(column - 1) + "^" else ""
}

/** The line of the first top-level statement of [file] that binds [name], for clash errors. */
private fun bindingLine(file: StarlarkFile, name: String): Int? =
  file.statements
    .firstOrNull { st ->
      when (st) {
        is AssignmentStatement -> (st.lhs as? Identifier)?.name == name
        is DefStatement -> st.identifier.name == name
        is LoadStatement -> st.bindings.any { it.localName.name == name }
        else -> false
      }
    }
    ?.startLocation
    ?.line()

/** The package the engine reads its rules from. */
const val RULES_PACKAGE = "rules"

/**
 * The [RulesExport]s not in [defined] and with no engine default; every export the enabled features
 * need is required.
 */
internal fun missingRulesExports(
  defined: Collection<String>,
  features: Features = Features.ALL,
): List<String> =
  RulesExport.entries
    .filterNot { e -> RULES_EXPORTS.single { spec -> spec.export == e }.hasDefault }
    .filter {
      FeatureOwnership.of("rules:${it.scriptName}").let { req ->
        req == null || req.satisfiedBy(features)
      }
    }
    .map { it.scriptName }
    .filter { it !in defined }
    .sorted()

/** Exports of a feature that is off which [module] defines anyway, with the feature requirement. */
internal fun forbiddenRulesExports(
  module: Module,
  features: Features,
): List<Pair<String, FeatureRequirement>> =
  RulesExport.entries
    .mapNotNull { e ->
      val req = FeatureOwnership.of("rules:${e.scriptName}")?.takeIf { !it.satisfiedBy(features) }
      if (req != null && module.globals[e.scriptName] is StarlarkFunction) e.scriptName to req
      else null
    }
    .sortedBy { it.first }

/**
 * Public top-level functions of a rules [module] that aren't a [RulesExport], sorted. The engine
 * would never call them, so they're almost always a misspelled export.
 */
internal fun unknownRulesExports(module: Module, extra: Set<String> = emptySet()): List<String> {
  val known = RulesExport.entries.mapTo(HashSet()) { it.scriptName } + extra
  return module.globals
    .filter { (name, v) -> v is StarlarkFunction && !name.startsWith("_") && name !in known }
    .keys
    .sorted()
}
