package gups.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import gups.core.Label
import gups.core.model.Prototypes
import gups.harness.FuzzWalker
import gups.harness.TestRunner
import gups.harness.render
import gups.persist.buildReproBundle
import gups.pluginapi.host.PluginHost
import gups.script.Linter
import gups.script.ScriptLimits
import gups.script.ScriptLoader
import gups.script.WorldDir
import java.io.File
import java.nio.file.Files

/** Exit status of `gups test` when a test fails or a pattern matches nothing. */
const val EXIT_TESTS_FAILED = 1

class TestCmd : CliktCommand(name = "test") {
  val patterns by argument("label-pattern").multiple(required = true)
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)

  override fun help(context: Context) = "Run *_test.star tests, e.g. //zones/goblin_caves/..."

  override fun run() {
    val dir = WorldDir((world ?: File(".")).toPath())
    var file: Label? = null
    val report =
      withPlugins(dir) { plugins ->
        TestRunner(dir, plugins).run(patterns) { r ->
          if (r.file != file) {
            file = r.file
            echo(r.file.v)
          }
          echo(r.render())
        }
      }
    for (p in report.problems) echo("gups test: $p", err = true)
    if (report.results.isNotEmpty()) echo("\n" + report.summary())
    if (!report.ok) throw ProgramResult(EXIT_TESTS_FAILED)
  }
}

class ScenarioCmd : CliktCommand(name = "scenario") {
  val file by argument("file.star").file(mustExist = true, canBeDir = false)
  val pkg by
    option(
        "--package",
        metavar = "//PKG",
        help = "Package the file runs as if it were in, e.g. //zones/tutorial.",
      )
      .required()
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)

  override fun help(context: Context) =
    "Run a test file kept outside the world and print what its players typed and received."

  override fun run() {
    val dir = WorldDir((world ?: File(".")).toPath())
    val report =
      withPlugins(dir) { plugins ->
        TestRunner(dir, plugins).scenario(file.toPath(), pkg) { r ->
          echo(r.render(transcript = true))
        }
      }
    for (p in report.problems) echo("gups scenario: $p", err = true)
    if (report.results.isNotEmpty()) echo("\n" + report.summary())
    if (!report.ok) throw ProgramResult(EXIT_TESTS_FAILED)
  }
}

class Lint : CliktCommand(name = "lint") {
  val patterns by argument("label-pattern").multiple(required = false)
  val base by
    option(
      "--base",
      metavar = "REF",
      help = "Base git ref to compare prototype declarations against.",
    )
  val format by
    option("--format", help = "Output format: text or github.")
      .choice("text", "github")
      .default("text")
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)

  override fun help(context: Context) = "Run static checks on course world packages (gate 2)."

  override fun run() =
    withPlugins(WorldDir((world ?: File(".")).toPath())) { plugins -> lint(plugins) }

  private fun lint(plugins: PluginHost) {
    val dir = WorldDir((world ?: File(".")).toPath())
    val basePrototypes = base?.let { basePrototypes(dir, it, plugins) }
    val report = Linter(dir, basePrototypes, plugins).lint(patterns.ifEmpty { listOf("//...") })
    printReport(report)
    appendStepSummary(report)
    if (!report.ok) throw ProgramResult(1)
  }

  /** The prototypes of [ref], loaded from a throwaway worktree of the world's repository. */
  private fun basePrototypes(dir: WorldDir, ref: String, plugins: PluginHost): Prototypes {
    val git = Git(dir.root)
    if (!git.isWorkTree()) throw UsageError("--base requires the world to be a git working tree")
    val commit =
      try {
        git.commit(ref)
      } catch (e: GitException) {
        throw UsageError("failed to load base commit '$ref': ${e.message}")
      }
    val staging = Files.createTempDirectory("gups-lint-base")
    try {
      git.run("worktree", "add", "--detach", "--quiet", staging.toString(), commit)
      return loadPrototypes(WorldDir(staging), ref, plugins)
    } finally {
      runCatching { git.run("worktree", "remove", "--force", staging.toString()) }
      runCatching { Files.deleteIfExists(staging) }
    }
  }

  private fun loadPrototypes(baseDir: WorldDir, ref: String, plugins: PluginHost): Prototypes {
    val loader =
      ScriptLoader(
        baseDir,
        ScriptLimits(),
        pluginGlobals = plugins.globals,
        knownGmcp = { plugins.gmcpPackages },
      )
    val result = loader.load(baseDir.packages())
    if (!result.ok) {
      val errs = result.errors.joinToString("\n") { it.render() }
      throw UsageError("failed to load base commit '$ref':\n$errs")
    }
    return result.prototypes()
  }

  private fun printReport(report: Linter.Report) {
    if (format == "github") {
      for (f in report.findings) echo(f.renderGithub())
      return
    }
    for (f in report.findings) echo(f.renderText())
    if (report.findings.isNotEmpty()) echo("\n" + report.summary())
  }

  private fun appendStepSummary(report: Linter.Report) {
    System.getenv("GITHUB_STEP_SUMMARY")
      ?.takeIf { it.isNotBlank() }
      ?.let { path -> runCatching { File(path).appendText(report.renderMarkdown()) } }
  }
}

class Fuzz : CliktCommand(name = "fuzz") {
  val patterns by argument("label-pattern").multiple(required = true)
  val pulses by
    option("--pulses", metavar = "N", help = "Number of pulses to run (default 10000).")
      .int()
      .default(10000)
  val seed by
    option("--seed", metavar = "S", help = "Random seed (default 42).").long().default(42L)
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)

  override fun help(context: Context) = "Run the fuzz walker against changed zones (gate 4)."

  override fun run() {
    val dir = WorldDir((world ?: File(".")).toPath())
    val outcome =
      withPlugins(dir) { plugins ->
        FuzzWalker(dir, plugins).run(patterns, pulses, seed) { p ->
          if (pulses >= 2000) echo("... pulse $p / $pulses")
        }
      }
    if (!outcome.ok) {
      reportFailure(outcome)
      throw ProgramResult(1)
    }
    echo(
      "Fuzz walker completed ${outcome.pulsesRun} pulses with ${outcome.bots} bots across ${outcome.zones.size} zones. 0 errors."
    )
  }

  private fun reportFailure(outcome: FuzzWalker.Outcome) {
    echo("gups fuzz failed: ${outcome.failureMessage}", err = true)
    val failPulse = outcome.failedPulse ?: outcome.pulsesRun.toLong()
    echo(
      "Failed at pulse $failPulse. Reproduce with: gups fuzz --seed $seed --pulses $failPulse ${patterns.joinToString(" ")}"
    )
    val w = outcome.world
    val d = outcome.diagnostic
    if (d != null && w != null) {
      echo("Repro bundle:")
      echo(buildReproBundle(w, d, outcome.botStartRooms).toJson())
    }
  }
}
