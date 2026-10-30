package gups.cli

import gups.core.WorldControlEvent
import gups.core.world.DeployOutcome
import gups.core.world.Deploys
import gups.core.world.Diagnostic
import gups.core.world.ReloadResult
import gups.core.world.World
import gups.core.world.alert
import gups.persist.Backups
import gups.persist.SqlitePersistence
import gups.script.StarlarkHost
import gups.script.WorldDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Where deploys come from. */
class DeploySettings(
  /** The remote to fetch from: a URL, or null for the working tree's `origin`. */
  val repository: String? = null,
  val branch: String = "main",
  /** The deploy key, for fetching over SSH. */
  val keyFile: Path? = null,
)

/** A deploy step that failed; its message goes to the deploy's errors. */
private class Refused(message: String) : Exception(message)

/**
 * Production hot code deployment pipeline using isolated git worktrees.
 *
 * Deploys run one at a time on an asynchronous background thread:
 *
 * ## Staged Deployment Steps
 * 1. **Concurrency lock:** Ensures only one deployment executes at a time via atomic CAS
 *    ([running]).
 * 2. **Fetch & resolve:** Fetches the deploy branch from the remote repository into a dedicated ref
 *    (`refs/remotes/gups-deploy/<branch>`) and resolves the target commit SHA.
 * 3. **Manifest check:** Verifies `MODULE.gups` and `MESSAGES.gups` remain unchanged; changes to
 *    engine configuration or message catalogs require a server restart and cannot be hot-deployed.
 * 4. **Diff analysis:** Analyzes modified files between the current `worldCommit` and the target
 *    commit, mapping each change to its owning package.
 * 5. **Pre-deploy snapshot:** Takes an automated non-pruned `predeploy` database backup snapshot
 *    before applying changes.
 * 6. **Staging verification:** Checks out the target commit in an isolated temporary git worktree
 *    (`git worktree add --detach`). Loads and dry-runs all packages off-thread; any load or
 *    validation failure aborts the deployment and cleans up the temporary worktree without
 *    modifying the live server.
 * 7. **Atomic world thread swap:** Dispatches a world task to swap script prototypes, reconcile
 *    live in-game entities, and clear quarantine status for reloaded packages.
 * 8. **Live checkout & finalization:** Updates the main working tree (`git checkout --detach
 *    <commit>`), updates `meta.world_commit`, re-reads `CODEOWNERS` into active builder scopes,
 *    triggers a database checkpoint, and notifies online instructors.
 */
class Deployer(
  private val worldDir: Path,
  private val settings: DeploySettings,
  private val world: World,
  private val host: StarlarkHost,
  private val persistence: SqlitePersistence,
  /** Posts to the world thread. */
  private val post: (WorldControlEvent) -> Unit,
  /** Re-reads CODEOWNERS from the live tree once it is at the new commit. */
  private val syncCodeOwners: () -> Unit,
  private val log: (String) -> Unit,
) : Deploys {
  private val git = Git(worldDir, settings.keyFile)
  private val running = AtomicBoolean(false)

  /** The ref fetched commits land on, so the working tree's own branches are never touched. */
  private val fetched = "refs/remotes/gups-deploy/${settings.branch}"

  override fun start(commit: String?, progress: (String) -> Unit, done: (DeployOutcome) -> Unit) {
    if (!running.compareAndSet(false, true)) {
      return done(
        DeployOutcome(
          false,
          commit,
          emptyList(),
          listOf("Another deploy is running; try again when it finishes."),
        )
      )
    }
    thread(name = "gups-deploy", isDaemon = true) {
      val outcome =
        try {
          deploy(commit, progress)
        } catch (e: Exception) {
          val why =
            if (e is Refused || e is GitException) e.message ?: e.toString()
            else "the deploy crashed: $e"
          failed(commit, listOf(why))
        } finally {
          running.set(false)
        }
      done(outcome)
    }
  }

  /** True while a deploy runs. */
  val busy: Boolean
    get() = running.get()

  private fun deploy(commit: String?, progress: (String) -> Unit): DeployOutcome {
    progress("Fetching ${settings.branch} from ${settings.repository ?: "origin"}.")
    git.run(
      "fetch",
      "--no-tags",
      "--quiet",
      settings.repository ?: "origin",
      "+refs/heads/${settings.branch}:$fetched",
    )
    val tip = git.commit(fetched)
    val target =
      if (commit == null) {
        tip
      } else {
        // Only the deploy branch is fetched, so a commit the server doesn't know isn't on it.
        runCatching { git.commit(commit) }
          .getOrElse { throw Refused("$commit is not on ${settings.branch}.") }
      }
    // Any commit deployed must be on the deploy branch.
    if (!git.isAncestor(target, tip)) throw Refused("$target is not on ${settings.branch}.")
    val dirty = git.dirtyFiles()
    if (dirty.isNotEmpty())
      throw Refused("The live working tree has changes nobody committed: ${dirty.joinToString()}.")
    val from = persistence.worldCommit ?: git.head()
    if (target == from) progress("$target is already live; reloading it anyway.")
    val files = git.changedFiles(from, target)
    // An engine upgrade needs a restart with the new image.
    if ("MODULE.gups" in files)
      throw Refused(
        "MODULE.gups changed between $from and $target: that needs a restart with the matching engine."
      )
    if ("MESSAGES.gups" in files)
      throw Refused(
        "MESSAGES.gups changed between $from and $target: message changes need a restart."
      )
    if (files.any { it.startsWith("plugins/") })
      throw Refused("plugins/ changed between $from and $target: plugin changes need a restart.")

    val staging = Files.createTempDirectory("gups-deploy").resolve("tree")
    try {
      git.run("worktree", "add", "--detach", "--quiet", staging.toString(), target)
      val dir = WorldDir(staging)
      val packages = dir.packages()
      val changed = changedPackages(files, packages + host.packages)
      progress("Changed packages: ${changed.joinToString { "//$it" }.ifEmpty { "none" }}.")
      progress("Taking a pre-deploy snapshot.")
      val snapshot = persistence.backup(Backups.Kind.PREDEPLOY)
      progress("Snapshot saved: $snapshot")
      progress("Loading ${packages.size} packages.")
      val staged = host.stage(dir)
      if (!staged.ok)
        return failed(
          target,
          listOf("The load failed; the old version stays live.") + staged.errors,
        )

      val swapped = CompletableFuture<ReloadResult>()
      post(
        WorldControlEvent.Task { w ->
          swapped.complete(
            runCatching { host.swap(w, staged, changed) }
              .getOrElse { ReloadResult(false, listOf("The swap failed: $it")) }
          )
        }
      )
      val result = swapped.get(5, TimeUnit.MINUTES)
      if (!result.ok) return failed(target, result.messages)
      progress("Swapped in. Moving the working tree to $target.")
      git.run("checkout", "--quiet", "--detach", target)
      persistence.worldCommit = target
      syncCodeOwners()
      post(
        WorldControlEvent.Task { w ->
          w.checkpointNow("deploy")
          w.alert(
            "deploy:$target",
            "Deployed $target: ${changed.joinToString { "//$it" }.ifEmpty { "no packages changed" }}.",
          )
        }
      )
      result.messages.drop(1).forEach(progress)
      log("Deployed $target (${changed.size} packages changed).")
      return DeployOutcome(true, target, changed, emptyList())
    } finally {
      runCatching { git.run("worktree", "remove", "--force", staging.toString()) }
      runCatching { Files.deleteIfExists(staging.parent) }
    }
  }

  /** A failed deploy: the world is unchanged; instructors and `@errors` hear why. */
  private fun failed(commit: String?, errors: List<String>): DeployOutcome {
    log("Deploy of ${commit ?: "the branch tip"} failed: ${errors.joinToString("; ")}")
    post(
      WorldControlEvent.Task { w ->
        w.record(
          Diagnostic(
            w.pulse,
            "deploy",
            "deploy of ${commit ?: "the branch tip"} failed:\n" + errors.joinToString("\n"),
          )
        )
        w.alert(
          "deploy-failed:$commit",
          "The deploy of ${commit ?: "the branch tip"} failed: ${errors.first()}",
        )
      }
    )
    return DeployOutcome(false, commit, emptyList(), errors)
  }

  companion object {
    /** The packages [files] fall in: for each, the deepest package directory containing it. */
    fun changedPackages(files: List<String>, packages: Collection<String>): List<String> =
      files
        .mapNotNull { f -> packages.filter { f.startsWith("$it/") }.maxByOrNull { it.length } }
        .distinct()
        .sorted()
  }
}
