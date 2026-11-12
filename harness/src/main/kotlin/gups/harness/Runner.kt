package gups.harness

import gups.core.Label
import gups.core.world.InvariantViolation
import gups.script.LoadResult
import gups.script.ScriptLimits
import gups.script.ScriptLoader
import gups.script.WorldDir
import gups.script.WorldMessages
import gups.script.WorldModule
import gups.script.WorldPluginWiring
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkFunction
import net.starlark.java.eval.StarlarkSemantics
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.syntax.Location

/**
 * Maximum Starlark bytecode steps a test function is permitted to execute: 10,000,000 steps.
 *
 * This deterministic budget applies strictly to the test function's own instructions; simulation
 * pulses advanced by test calls (such as `w.pulse()` or `p.do(...)`) run under their own distinct
 * engine limits and do not deplete this step budget.
 */
const val TEST_STEPS = 10_000_000L

/**
 * A `gups test` label pattern: `//...` (everything), `//zones/...` (a package and those below it),
 * `//zones/forest` (one package), or `//zones/forest:target` where target is a test file
 * (`forest_test.star`) or one test function (`test_gate`).
 */
class LabelPattern(val text: String) {
  private val base: String
  private val recursive: Boolean

  /** A test file or function name, or null for all tests. */
  val target: String?

  init {
    require(text.startsWith("//")) { "'$text' is not a label pattern; patterns start with //" }
    val body = text.removePrefix("//")
    target = body.substringAfter(':', "").ifEmpty { null }
    val path = body.substringBefore(':')
    recursive = path == "..." || path.endsWith("/...")
    base = path.removeSuffix("...").removeSuffix("/")
    require(!(recursive && target != null)) { "'$text': a recursive pattern can't name a target" }
  }

  fun matches(pkg: String): Boolean =
    if (recursive) base.isEmpty() || pkg == base || pkg.startsWith("$base/") else pkg == base

  /** Whether a test file may hold what this pattern selects. */
  internal fun wantsFile(file: Label): Boolean =
    target == null || target == file.name || !target.endsWith(".star")

  fun selects(file: Label, test: String): Boolean =
    target == null || target == file.name || target == test

  override fun toString(): String = text
}

enum class Status {
  PASS,
  FAIL,
  ERROR,
}

/** The outcome of one test function, or of a test file that failed to load. */
data class TestResult(
  val file: Label,
  val name: String,
  val status: Status,
  val message: String? = null,
  /** `file:line` where it failed. */
  val location: String? = null,
  val trace: String? = null,
  val printed: String = "",
  /** Script diagnostics logged in the test's worlds, which don't fail the test themselves. */
  val warnings: List<String> = emptyList(),
  /** A scenario's commands, output and printed lines, in order; empty for ordinary tests. */
  val transcript: String = "",
)

class TestReport(val results: List<TestResult>, val problems: List<String>) {
  val passed: Int
    get() = results.count { it.status == Status.PASS }

  val failed: Int
    get() = results.count { it.status == Status.FAIL }

  val errors: Int
    get() = results.count { it.status == Status.ERROR }

  val ok: Boolean
    get() = problems.isEmpty() && results.isNotEmpty() && results.all { it.status == Status.PASS }

  fun summary(): String = "$passed passed, $failed failed, $errors errors"
}

/**
 * Runs `*_test.star` files against headless in-memory worlds with a virtual clock and seeded RNG:
 * no network, no SQLite, no wall clock.
 */
class TestRunner(
  private val dir: WorldDir,
  private val plugins: WorldPluginWiring = WorldPluginWiring.NONE,
) {
  /** Tests and their worlds see each package's fixture files. */
  private val loader = ScriptLoader(dir, ScriptLimits.HARNESS, plugins = plugins, fixtures = true)
  private val cache = HashMap<List<String>, LoadResult>()

  /** The rules package is always loaded implicitly when the world has one. */
  private val implicit: List<String> = listOf("rules").filter(dir::hasPackage)

  private val settings =
    WorldModule.read(dir).first.config(messages = WorldMessages.read(dir).first)

  private fun loadWorld(pkgs: List<String>): LoadResult {
    val key = (pkgs + implicit).distinct().sorted()
    return cache.getOrPut(key) { loader.load(key) }
  }

  /** Runs every test matching [patterns], reporting each result to [listener] as it finishes. */
  fun run(patterns: List<String>, listener: (TestResult) -> Unit = {}): TestReport {
    val problems = mutableListOf<String>()
    problems += moduleProblems(dir)
    val parsed =
      patterns.mapNotNull { p ->
        runCatching { LabelPattern(p) }.onFailure { problems += it.message ?: p }.getOrNull()
      }
    val all = dir.packages()
    val results = mutableListOf<TestResult>()
    for (pattern in parsed) if (all.none(pattern::matches))
      problems += "no package matches $pattern"
    for (pkg in all) {
      val wanted = parsed.filter { it.matches(pkg) }
      if (wanted.isEmpty()) continue
      val files = dir.files(pkg, tests = true).filter { f -> wanted.any { it.wantsFile(f) } }
      if (files.isEmpty()) continue
      for (r in runPackage(pkg, files, wanted)) {
        results += r
        listener(r)
      }
    }
    if (results.isEmpty() && problems.isEmpty()) problems += "no tests found"
    return TestReport(results, problems)
  }

  /**
   * Runs the `test_*` functions of [file], which may live outside the world, as if it sat in the
   * package [pkgLabel] (such as `//zones/tutorial`): its relative labels and default `t.world()`
   * load resolve there. Each result carries a transcript of what the players typed and received.
   */
  fun scenario(file: Path, pkgLabel: String, listener: (TestResult) -> Unit = {}): TestReport {
    val problems = mutableListOf<String>()
    problems += moduleProblems(dir)
    val pkg = pkgLabel.removePrefix("//")
    problems += scenarioProblems(file, pkgLabel, pkg)
    val label = if (problems.isEmpty()) scenarioLabel(file, pkg, problems) else null
    if (label == null) return TestReport(emptyList(), problems)
    val loaded =
      loader.load(listOf(pkg) + implicit, tests = listOf(label), testSources = mapOf(label to file))
    val results = mutableListOf<TestResult>()
    loaded.testErrors[label]?.let { errs ->
      val r = TestResult(label, "(load)", Status.ERROR, errs.joinToString("\n") { it.render() })
      listener(r)
      return TestReport(listOf(r), problems)
    }
    val module = loaded.testModules[label]
    for ((fnName, fn) in module?.globals.orEmpty()) {
      if (!fnName.startsWith("test_") || fn !is StarlarkFunction) continue
      val r = runTest(pkg, label, fnName, fn, Transcript())
      results += r
      listener(r)
    }
    if (results.isEmpty()) problems += "$file has no test_ functions"
    return TestReport(results, problems)
  }

  private fun scenarioProblems(file: Path, pkgLabel: String, pkg: String): List<String> =
    listOfNotNull(
      when {
        !pkgLabel.startsWith("//") || ':' in pkgLabel || pkg.isEmpty() ->
          "'$pkgLabel' is not a package label such as //zones/forest"
        !dir.hasPackage(pkg) -> "no package //$pkg in ${dir.root}"
        else -> null
      },
      "$file is not a .star file"
        .takeIf {
          file.fileName?.toString().orEmpty().endsWith(".star").not() || !file.isRegularFile()
        },
    )

  private fun scenarioLabel(file: Path, pkg: String, problems: MutableList<String>): Label? =
    runCatching { Label("//$pkg:${file.fileName?.toString().orEmpty()}") }
      .onFailure { problems += "$file: ${it.message}" }
      .getOrNull()

  private fun runPackage(
    pkg: String,
    files: List<Label>,
    wanted: List<LabelPattern>,
  ): List<TestResult> {
    val loaded = loader.load(listOf(pkg) + implicit, tests = files)
    val out = mutableListOf<TestResult>()
    for (file in files) {
      loaded.testErrors[file]?.let { errs ->
        out += TestResult(file, "(load)", Status.ERROR, errs.joinToString("\n") { it.render() })
        return@let
      }
      val module = loaded.testModules[file] ?: continue
      for ((name, fn) in module.globals) {
        if (!name.startsWith("test_") || fn !is StarlarkFunction) continue
        if (wanted.none { it.selects(file, name) }) continue
        out += runTest(pkg, file, name, fn)
      }
    }
    return out
  }

  private fun runTest(
    pkg: String,
    file: Label,
    name: String,
    fn: StarlarkFunction,
    transcript: Transcript? = null,
  ): TestResult {
    val t =
      TestCtx(
        pkg,
        ::loadWorld,
        listOf(pkg),
        plugins = plugins,
        settings = settings,
        schema = loader.schema,
        levelMode = loader.module.levelMode,
        standingHistoryPulses = loader.module.standingHistoryPulses,
        memoryPulses = loader.module.memoryPulses,
        transcript = transcript,
      )
    val printed = StringBuilder()
    val outcome = execute(name, fn, t, printed, transcript)
    val trace = t.worlds.mapNotNull { it.traceText() }.joinToString("\n").ifEmpty { null }
    return TestResult(
      file,
      name,
      outcome.status,
      outcome.message,
      outcome.location,
      // A scenario is run to be read, so its trace is kept even when it passes.
      trace.takeIf { outcome.status != Status.PASS || transcript != null },
      printed.toString(),
      warnings(t),
      transcript?.toString().orEmpty(),
    )
  }

  private class Outcome(
    val status: Status,
    val message: String? = null,
    val location: String? = null,
  )

  private fun execute(
    name: String,
    fn: StarlarkFunction,
    t: TestCtx,
    printed: StringBuilder,
    transcript: Transcript?,
  ): Outcome =
    Mutability.create(name).use { mu ->
      val thread = StarlarkThread(mu, StarlarkSemantics.DEFAULT)
      thread.setMaxExecutionSteps(TEST_STEPS)
      thread.setPrintHandler { _, msg ->
        printed.append(msg).append('\n')
        transcript?.printed(msg)
      }
      try {
        Starlark.call(thread, fn, listOf(t), emptyMap())
        Outcome(Status.PASS)
      } catch (e: EvalException) {
        evalOutcome(e)
      } catch (e: InvariantViolation) {
        Outcome(Status.ERROR, e.message)
      } catch (e: RuntimeException) {
        Outcome(Status.ERROR, "engine exception: $e")
      }
    }

  private fun evalOutcome(e: EvalException): Outcome {
    val failed = generateSequence<Throwable>(e) { it.cause }.any { it is AssertionFailed }
    val message =
      if (e.message?.contains("too many steps") == true)
        "the test's own code exceeded $TEST_STEPS steps"
      else e.message
    val location =
      e.callStack
        .lastOrNull { it.location != Location.BUILTIN && it.location.line() > 0 }
        ?.location
        ?.let { "${it.file()}:${it.line()}" }
    return Outcome(if (failed) Status.FAIL else Status.ERROR, message, location)
  }

  private fun warnings(t: TestCtx): List<String> =
    t.worlds.flatMap { w ->
      w.world.diagnostics
        .filter { it.pkg != null }
        .map { d ->
          listOfNotNull(
              d.file?.let { f -> d.line?.let { "$f:$it" } ?: f },
              "[${d.kind}] ${d.message}",
            )
            .joinToString(": ")
        }
    }
}

/**
 * Human-readable report lines, as `gups test` prints them. With [transcript], a scenario's
 * transcript comes first, in full, whether the test passed or not; it already holds what was
 * printed.
 */
fun TestResult.render(transcript: Boolean = false): String = buildString {
  if (transcript && this@render.transcript.isNotEmpty()) {
    append("--- ").append(name).append('\n').append(this@render.transcript)
  }
  append("  ").append(status.name.padEnd(5)).append(' ').append(name)
  if (location != null) append("  (").append(location).append(')')
  message?.let { m -> append('\n').append(m.lines().joinToString("\n") { "        $it" }) }
  if (!transcript && status != Status.PASS && printed.isNotEmpty()) {
    append("\n        printed:\n")
      .append(printed.trimEnd().lines().joinToString("\n") { "          $it" })
  }
  trace?.let { append("\n        trace:\n").append(it.lines().joinToString("\n") { "      $it" }) }
  for (w in warnings) append("\n        warning: ").append(w)
}
