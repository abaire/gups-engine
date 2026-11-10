package gups.script

import com.google.common.collect.ImmutableMap
import gups.core.HookRef
import gups.core.Label
import gups.core.model.Direction
import gups.core.model.Prototypes
import gups.core.model.WorldSchema
import gups.core.model.ZoneProto
import gups.core.model.didYouMean
import gups.core.world.CommandRegistry
import gups.core.world.FeatureRequirement
import gups.core.world.Features
import gups.core.world.HookName
import gups.core.world.gmcpOverlaps
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
import net.starlark.java.syntax.CallExpression
import net.starlark.java.syntax.DefStatement
import net.starlark.java.syntax.FileOptions
import net.starlark.java.syntax.Identifier
import net.starlark.java.syntax.LoadStatement
import net.starlark.java.syntax.Location
import net.starlark.java.syntax.NodeVisitor
import net.starlark.java.syntax.ParserInput
import net.starlark.java.syntax.Program
import net.starlark.java.syntax.StarlarkFile
import net.starlark.java.syntax.StringLiteral
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
        val st = header(p) ?: continue
        if (st.contentDone || st.failed) continue
        st.contentDone = true
        for (file in contentFiles(p)) loadFile(file, st)
        for (ref in st.pkg.refs) dependOn(st, ref.label.pkg)
        for (ref in st.pkg.factionRefs) dependOn(st, ref.label.pkg)
        findMigrate(st)
        if (st.pkg.kind == PackageKind.RULES) findRulesExports(st)
      }
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
      val path = (if (test) testSources[file] else null) ?: dir.fileOf(file)
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
      if (!test && st.pkg.kind == PackageKind.RULES) {
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
          return null
        }
        val unknown =
          unknownRulesExports(module, plugins.rulesExports.keys).filter { name ->
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
      if (WorldDir.isFixture(target.name) && !test && !WorldDir.isFixture(from.name))
        fail(
          "load(\"$spec\"): test fixture files can only be loaded by test files and other fixture files"
        )
      return target
    }

    fun finish(): LoadResult {
      propagate()
      resolveLabels()
      checkFactions()
      propagate()
      checkNamespaces()
      checkRegions()
      checkGmcp()
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
              snippet(ref.file, ref.line, ref.column),
            )
        }
      }
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
                "${ref.label} doesn't resolve: //${ref.label.pkg} declares no ${ref.kind.word} named '${ref.label.name}'" +
                  fixtureNote(ref.label)
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

    /** Every faction reference in mob(factions=) or faction(standing=) resolves. */
    fun checkFactions() {
      val declaredFactions = live.flatMap { it.pkg.factions.map { f -> f.label } }.toSet()
      val mobFactions = live.flatMap { it.pkg.mobs.map { m -> m.label } }.toSet()
      val knownFactionLabels = declaredFactions + mobFactions
      val knownStrings = knownFactionLabels.map { it.v }
      for (st in live) {
        for (ref in st.pkg.factionRefs) {
          if (ref.label in knownFactionLabels) continue
          val why =
            "${ref.label} doesn't resolve: it is neither a declared faction nor a mob prototype${didYouMean(ref.label.v, knownStrings)}"
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

    private val fixtureDecls = HashMap<String, Map<String, Label>>()

    /**
     * When fixtures are left out, says which fixture file of the label's package declares the name,
     * found by scanning those files for declaration calls without evaluating them.
     */
    fun fixtureNote(label: Label): String {
      if (fixtures) return ""
      val decls =
        fixtureDecls.getOrPut(label.pkg) {
          dir
            .files(label.pkg, tests = false, fixtures = true)
            .filter { WorldDir.isFixture(it.name) }
            .flatMap { file -> declaredNames(file).map { it to file } }
            .toMap()
        }
      val file = decls[label.name] ?: return ""
      return " (it is declared in $file, a test fixture file, which loads only for gups test and gups scenario)"
    }

    /** The names [file]'s `room`, `mob`, `item` and `skill` calls declare, wherever they are. */
    fun declaredNames(file: Label): List<String> {
      val text = runCatching { dir.fileOf(file).readText() }.getOrNull() ?: return emptyList()
      val parsed = StarlarkFile.parse(ParserInput.fromString(text, file.v), FILE_OPTIONS)
      val kinds = RefKind.entries.mapTo(HashSet()) { it.word }
      val names = mutableListOf<String>()
      parsed.accept(
        object : NodeVisitor() {
          override fun visit(node: CallExpression) {
            val fn = node.function as? Identifier
            val first = node.arguments.firstOrNull()?.takeIf { it.name == null }?.value
            if (fn != null && fn.name in kinds && first is StringLiteral) names += first.value
            super.visit(node)
          }
        }
      )
      return names
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

    /** Declared GMCP packages may not overlap across packages or with a plugin's. */
    fun checkGmcp() {
      val decls = live.flatMap { st -> st.pkg.gmcpPackages.map { it.name to st.pkg.path } }
      for (i in decls.indices) {
        for (j in (i + 1) until decls.size) {
          val (a, pa) = decls[i]
          val (b, pb) = decls[j]
          if (pa == pb || !gmcpOverlaps(a, b)) continue
          states.getValue(pa).errors +=
            LoadError(pa, "gmcp", "GMCP package '$a' of //$pa overlaps '$b' of //$pb")
          states.getValue(pb).errors +=
            LoadError(pb, "gmcp", "GMCP package '$b' of //$pb overlaps '$a' of //$pa")
        }
      }
      val plugin = knownGmcp()
      for (st in live) {
        for (decl in st.pkg.gmcpPackages) {
          plugin
            .firstOrNull { gmcpOverlaps(decl.name, it) }
            ?.let {
              st.errors +=
                LoadError(
                  st.pkg.path,
                  "gmcp",
                  "GMCP package '${decl.name}' overlaps the plugin package '$it'",
                )
            }
        }
      }
    }

    /** Global commands may not shadow engine commands, directions or each other. */
    fun checkCommands() {
      val seen = HashMap<String, String>()
      for (st in live) {
        for (c in st.pkg.globalCommands) {
          val clash =
            when {
              c.name in engineVerbs -> "engine command"
              Direction.parse(c.name) in Direction.STANDARD -> "direction"
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
