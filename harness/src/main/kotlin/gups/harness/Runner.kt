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
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkFunction
import net.starlark.java.eval.StarlarkSemantics
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.syntax.Location

/** Steps one test function may spend in its own code; pulses it runs don't count. */
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
  private val loader = ScriptLoader(dir, ScriptLimits.HARNESS, pluginGlobals = plugins.globals)
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
      val files =
        dir.files(pkg, tests = true).filter { f ->
          wanted.any { it.target == null || it.target == f.name || !it.target.endsWith(".star") }
        }
      if (files.isEmpty()) continue
      for (r in runPackage(pkg, files, wanted)) {
        results += r
        listener(r)
      }
    }
    if (results.isEmpty() && problems.isEmpty()) problems += "no tests found"
    return TestReport(results, problems)
  }

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

  private fun runTest(pkg: String, file: Label, name: String, fn: StarlarkFunction): TestResult {
    val t =
      TestCtx(
        pkg,
        ::loadWorld,
        listOf(pkg),
        plugins = plugins,
        settings = settings,
        schema = loader.schema,
      )
    val printed = StringBuilder()
    var status = Status.PASS
    var message: String? = null
    var location: String? = null
    Mutability.create(name).use { mu ->
      val thread = StarlarkThread(mu, StarlarkSemantics.DEFAULT)
      thread.setMaxExecutionSteps(TEST_STEPS)
      thread.setPrintHandler { _, msg -> printed.append(msg).append('\n') }
      try {
        Starlark.call(thread, fn, listOf(t), emptyMap())
      } catch (e: EvalException) {
        status =
          if (generateSequence<Throwable>(e) { it.cause }.any { it is AssertionFailed }) Status.FAIL
          else Status.ERROR
        message =
          if (e.message?.contains("too many steps") == true)
            "the test's own code exceeded $TEST_STEPS steps"
          else e.message
        location =
          e.callStack
            .lastOrNull { it.location != Location.BUILTIN && it.location.line() > 0 }
            ?.location
            ?.let { "${it.file()}:${it.line()}" }
      } catch (e: InvariantViolation) {
        status = Status.ERROR
        message = e.message
      } catch (e: RuntimeException) {
        status = Status.ERROR
        message = "engine exception: $e"
      }
    }
    val trace = t.worlds.mapNotNull { it.traceText() }.joinToString("\n").ifEmpty { null }
    val warnings =
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
    return TestResult(
      file,
      name,
      status,
      message,
      location,
      trace.takeIf { status != Status.PASS },
      printed.toString(),
      warnings,
    )
  }
}

/** Human-readable report lines, as `gups test` prints them. */
fun TestResult.render(): String = buildString {
  append("  ").append(status.name.padEnd(5)).append(' ').append(name)
  if (location != null) append("  (").append(location).append(')')
  message?.let { m -> append('\n').append(m.lines().joinToString("\n") { "        $it" }) }
  if (status != Status.PASS && printed.isNotEmpty()) {
    append("\n        printed:\n")
      .append(printed.trimEnd().lines().joinToString("\n") { "          $it" })
  }
  trace?.let { append("\n        trace:\n").append(it.lines().joinToString("\n") { "      $it" }) }
  for (w in warnings) append("\n        warning: ").append(w)
}
