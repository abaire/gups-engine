package gups.script

import com.google.common.collect.ImmutableMap
import gups.core.HookRef
import gups.core.Label
import gups.core.model.Prototypes
import gups.core.model.WorldSchema
import gups.core.model.ZoneProto
import gups.core.world.CommandRegistry
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.HookName
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
    )
  }

  fun hooks(label: Label): Map<HookName, HookRef> = hookIndex[label].orEmpty()

  /** The function a [HookRef] names now, or null if its file or function is gone. */
  internal fun function(ref: HookRef): StarlarkFunction? =
    modules[ref.file]?.getGlobal(ref.name) as? StarlarkFunction

  /** Renders every error, one block per error. */
  fun report(): String = errors.joinToString("\n") { it.render() }
}

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
   * `*_test.star` files to evaluate as well, with [testGlobals] as their builtins.
   */
  fun load(
    requested: Collection<String>,
    tests: Collection<Label> = emptyList(),
    testGlobals: Map<String, Any> = emptyMap(),
  ): LoadResult = Session().run(requested, tests, testGlobals)

  private class PkgState(val pkg: ScriptPackage) {
    val errors = mutableListOf<LoadError>()
    val deps = sortedSetOf<String>()
    var headerDone = false
    var contentDone = false
    var steps = 0L
    var startedNanos = 0L

    val failed: Boolean
      get() = errors.isNotEmpty()
  }

  private inner class Session {
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
        val st = header(p) ?: continue
        if (st.contentDone || st.failed) continue
        st.contentDone = true
        for (file in dir.files(p, tests = false)) loadFile(file, st)
        for (ref in st.pkg.refs) dependOn(st, ref.label.pkg)
        findMigrate(st)
      }
    }

    /** One file of the package may define a top-level `migrate`. */
    fun findMigrate(st: PkgState) {
      val defs =
        dir.files(st.pkg.path, tests = false).filter { file ->
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
      if (ctx != null && !ctx.packageDeclared && !st.failed) {
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
      fun err(e: LoadError) {
        if (test) testErrors.getOrPut(file) { mutableListOf() } += e else st.errors += e
        badFiles += file
      }
      val path = dir.fileOf(file)
      if (!path.isRegularFile()) {
        err(LoadError(st.pkg.path, "missing", "no file $path"))
        return null
      }
      val parsed = StarlarkFile.parse(ParserInput.fromString(path.readText(), file.v), FILE_OPTIONS)
      if (!parsed.ok()) {
        parsed.errors().forEach { err(syntaxError(st.pkg.path, it)) }
        return null
      }
      val module = Module.withPredeclared(SEMANTICS, globals)
      module.clientData = FileInfo(file, file.pkg, test)
      val program =
        try {
          Program.compileFile(parsed, module)
        } catch (e: SyntaxError.Exception) {
          e.errors().forEach { err(syntaxError(st.pkg.path, it)) }
          return null
        }
      val loaded = HashMap<String, Module>()
      inProgress += file
      try {
        for (spec in program.loads) {
          val target =
            try {
              loadLabel(spec, file, test)
            } catch (e: EvalException) {
              err(LoadError(st.pkg.path, "load", e.message ?: "bad load", file.v))
              return null
            }
          if (!test) dependOn(st, target.pkg)
          val m = loadFile(target, if (test) null else st)
          if (m == null) {
            err(LoadError(st.pkg.path, "load", "load(\"$spec\"): $target failed to load", file.v))
            return null
          }
          loaded[spec] = m
        }
      } finally {
        inProgress -= file
      }
      val ctx =
        LoadCtx(module.clientData as FileInfo, st.pkg, header, engineVerbs) { checkWall(st) }
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
      } catch (e: EvalException) {
        err(evalError(st.pkg.path, file, e))
        return null
      }
      val clashes = module.globals.keys.filter { it in pluginGlobals }.sorted()
      if (clashes.isNotEmpty()) {
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
        return null
      }
      if (!test && st.pkg.kind == PackageKind.RULES && file.name == RULES_FILE) {
        val forbidden = forbiddenRulesExports(module, features)
        if (forbidden.isNotEmpty()) {
          for ((name, feature) in forbidden) {
            err(
              LoadError(
                st.pkg.path,
                "rules",
                "the rules package defines $name(...), which needs the ${feature.id} feature",
                file.v,
                bindingLine(parsed, name),
              )
            )
          }
          return null
        }
        val missing = missingRulesExports(module, features)
        if (missing.isNotEmpty()) {
          for (name in missing) {
            err(LoadError(st.pkg.path, "rules", "the rules package must define $name(...)", file.v))
          }
          return null
        }
        val unknown =
          unknownRulesExports(module).filter { name ->
            forbiddenRulesExports(module, features).none { it.first == name }
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
          return null
        }
      }
      if (test) testModules[file] = module else modules[file] = module
      return ctx
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
      if (target.name.endsWith(WorldDir.TEST_SUFFIX) && !test)
        fail("load(\"$spec\"): test files can only be loaded by other test files")
      return target
    }

    fun finish(): LoadResult {
      propagate()
      resolveLabels()
      propagate()
      checkNamespaces()
      checkRegions()
      checkCommands()
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

    /** Runs the rules dry run; a rules package that fails it fails, and so do its dependents. */
    fun dryRun() {
      val rules = states[RULES_PACKAGE]?.takeIf { it.contentDone && !it.failed } ?: return
      val problems = dryRunRules(result(), schema, limits, features = features, wiring = plugins)
      if (problems.isEmpty()) return
      rules.errors +=
        problems.map {
          LoadError(RULES_PACKAGE, "rules", "dry run: $it", "//$RULES_PACKAGE:$RULES_FILE")
        }
      propagate()
    }

    val live: List<PkgState>
      get() = states.values.filter { it.contentDone && !it.failed }

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

    /** Every label resolves to a declaration of the right kind. */
    fun resolveLabels() {
      val index = HashMap<Label, RefKind>()
      for (st in live) {
        st.pkg.rooms.forEach { index[it.label] = RefKind.ROOM }
        st.pkg.mobs.forEach { index[it.label] = RefKind.MOB }
        st.pkg.items.forEach { index[it.label] = RefKind.ITEM }
        st.pkg.skills.forEach { index[it.label] = RefKind.SKILL }
      }
      index[Prototypes.CORPSE.label] = RefKind.ITEM
      for (st in live) {
        for (ref in st.pkg.refs) {
          val found = index[ref.label]
          if (found == ref.kind) continue
          val why =
            when {
              found != null -> "${ref.label} is a ${found.word}, not a ${ref.kind.word}"
              !dir.hasPackage(ref.label.pkg) ->
                "${ref.label} doesn't resolve: there is no package //${ref.label.pkg}"
              else ->
                "${ref.label} doesn't resolve: //${ref.label.pkg} declares no ${ref.kind.word} named '${ref.label.name}'"
            }
          st.errors +=
            LoadError(
              st.pkg.path,
              "label",
              why,
              ref.file,
              ref.line,
              ref.column,
              snippet(ref.file, ref.line, ref.column),
            )
        }
      }
    }

    /** Attribute namespaces are unique across the world, plugins' included. */
    fun checkNamespaces() {
      val owners = LinkedHashMap<String, MutableList<String>>()
      for (st in live) {
        owners.getOrPut(st.pkg.namespace) { mutableListOf() } += st.pkg.path
        for (ns in st.pkg.sharedNamespaces) owners.getOrPut(ns) { mutableListOf() } +=
          "${st.pkg.path} (shared)"
      }
      for ((ns, pkgs) in owners) {
        if (pkgs.size < 2) continue
        for (p in pkgs) {
          val path = p.removeSuffix(" (shared)")
          states.getValue(path).errors +=
            LoadError(
              path,
              "namespace",
              "attribute namespace '$ns' is used by more than one package: ${pkgs.joinToString(", ")}",
            )
        }
      }
      // A plugin writes attributes in its id's namespace, so no package may own it.
      for (st in live) {
        for (ns in listOf(st.pkg.namespace) + st.pkg.sharedNamespaces) {
          if (ns in pluginGlobals)
            st.errors +=
              LoadError(
                st.pkg.path,
                "namespace",
                "attribute namespace '$ns' is the namespace of plugin '$ns'",
              )
        }
      }
    }

    /** Region names are world-wide, so only one package may declare each. */
    fun checkRegions() {
      val owners = LinkedHashMap<String, MutableList<String>>()
      for (st in live) for (r in st.pkg.regions) owners.getOrPut(r.name) { mutableListOf() } +=
        st.pkg.path
      for ((name, pkgs) in owners) {
        if (pkgs.size < 2) continue
        for (p in pkgs.distinct()) {
          states.getValue(p).errors +=
            LoadError(
              p,
              "region",
              "region '$name' is declared by more than one package: ${pkgs.joinToString(", ") { "//$it" }}",
            )
        }
      }
    }

    /** Global commands may not shadow engine commands or each other. */
    fun checkCommands() {
      val seen = HashMap<String, String>()
      for (st in live) {
        for (c in st.pkg.globalCommands) {
          val clash =
            when {
              c.name in engineVerbs -> "engine command"
              else -> seen.put(c.name, st.pkg.path)?.let { "global command of //$it" }
            } ?: continue
          st.errors +=
            LoadError(st.pkg.path, "command", "global_command '${c.name}' clashes with the $clash")
        }
      }
      // A global command would shadow an exit of the same name, so the name is reserved
      // (engine verbs are rejected by exit() itself).
      for (st in live) {
        for (room in st.pkg.rooms) {
          for (exit in room.exits) {
            val owner = seen[exit.direction.name] ?: continue
            st.errors +=
              LoadError(
                st.pkg.path,
                "command",
                "exit '${exit.direction.name}' of ${room.label} is a reserved keyword: " +
                  "the global command of //$owner",
              )
          }
        }
      }
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
        snippet(loc.file(), loc.line(), loc.column()),
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
      return LoadError(pkg, kind, message, loc.file(), line, col, snippet(loc.file(), line, col))
    }

    fun snippet(file: String?, line: Int?, column: Int?): String? {
      if (file == null || line == null) return null
      val text = runCatching { dir.sourceLine(Label(file), line) }.getOrNull() ?: return null
      return "    $text" +
        if (column != null && column > 0) "\n    " + " ".repeat(column - 1) + "^" else ""
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

/** The file of a rules package whose functions the engine calls. */
internal const val RULES_FILE = "combat.star"

/** The package the engine reads its rules from. */
const val RULES_PACKAGE = "rules"

/** The [RulesExport]s that [module] doesn't define as functions; every export is required. */
internal fun missingRulesExports(module: Module, features: Features = Features.ALL): List<String> =
  RulesExport.entries
    .filter {
      FeatureOwnership.of("rules:${it.scriptName}").let { f -> f == null || f in features }
    }
    .map { it.scriptName }
    .filter { module.globals[it] !is StarlarkFunction }
    .sorted()

/** Exports of a feature that is off which [module] defines anyway, with the feature. */
internal fun forbiddenRulesExports(
  module: Module,
  features: Features,
): List<Pair<String, Feature>> =
  RulesExport.entries
    .mapNotNull { e ->
      val f = FeatureOwnership.of("rules:${e.scriptName}")?.takeIf { it !in features }
      if (f != null && module.globals[e.scriptName] is StarlarkFunction) e.scriptName to f else null
    }
    .sortedBy { it.first }

/**
 * Public top-level functions of a rules [module] that aren't a [RulesExport], sorted. The engine
 * would never call them, so they're almost always a misspelled export.
 */
internal fun unknownRulesExports(module: Module): List<String> {
  val known = RulesExport.entries.mapTo(HashSet()) { it.scriptName }
  return module.globals
    .filter { (name, v) -> v is StarlarkFunction && !name.startsWith("_") && name !in known }
    .keys
    .sorted()
}
