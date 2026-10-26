package gups.cli

import gups.auth.AccountLogin
import gups.auth.AccountService
import gups.auth.AccountTools
import gups.auth.CodeOwners
import gups.auth.DEFAULT_LADDER
import gups.auth.LoginLimiter
import gups.auth.LoginSettings
import gups.auth.WebLogins
import gups.auth.applyAccount
import gups.auth.syncScopes
import gups.core.Account
import gups.core.Label
import gups.core.Role
import gups.core.WorldControlEvent
import gups.core.loop.WorldLoop
import gups.core.world.Boot
import gups.core.world.DevLogin
import gups.core.world.Diagnostic
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.core.world.alert
import gups.harness.HarnessApi
import gups.net.CERT_CHECK_INTERVAL
import gups.net.DocsServer
import gups.net.ServerTls
import gups.net.TelnetServer
import gups.net.WebAssets
import gups.net.WebServer
import gups.persist.Backups
import gups.persist.SqlitePersistence
import gups.pluginapi.host.PluginHost
import gups.script.DevTools
import gups.script.ScriptApi
import gups.script.ScriptLimits
import gups.script.ScriptLoader
import gups.script.StarlarkHost
import gups.script.WorldDir
import gups.script.WorldModule
import gups.script.WorldWatcher
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Where new characters start unless `--start` or `start_room` says otherwise (D-082, D-138). */
val TUTORIAL_START = Label("//zones/tutorial:gate")

/** How often the server checks whether an hourly or daily backup is due. */
private const val BACKUP_CHECK_MS = 5 * 60_000L

/** How often the `--dev` file watcher looks for changes. */
private const val WATCH_INTERVAL_MS = 100L

/** What `--prod` adds (SPEC §2.4): where raw telnet listens, and the account login's settings. */
class ProdOptions(
  /** Where raw telnet listens, on [GupsServer]'s port; null for none. */
  val telnetHost: String?,
  val login: LoginSettings = LoginSettings(),
  /** `restriction_ladder` of the server configuration (SPEC §4.9). */
  val ladder: List<String> = DEFAULT_LADDER,
  /** Telnet over TLS (SPEC §4.10). */
  val tls: TlsListener? = null,
  /** HTTPS with the web client and WebSocket (SPEC §4.10). */
  val web: TlsListener? = null,
  /** The admin port (SPEC §4.14); null for none. */
  val admin: AdminListener? = null,
  /** Where deploys fetch from (SPEC §5.10). */
  val deploy: DeploySettings = DeploySettings(),
  /** Start even if the working tree isn't at `meta.world_commit` (SPEC §2.4). */
  val forceCommit: Boolean = false,
  /** How long a shutdown waits for the final checkpoint (`shutdown_timeout`, SPEC §5.11). */
  val shutdownTimeoutMs: Long = SHUTDOWN_WAIT_MS,
)

/** Where the admin port listens, and its bearer token (null: only `/healthz` works). */
class AdminListener(val host: String, val port: Int, val token: String?)

/** `/healthz` fails once the world thread hasn't pulsed for this long (SPEC §4.14). */
private const val STALL_NANOS = 5_000_000_000L

/** Where telnet over TLS listens, and its certificate. */
class TlsListener(val host: String, val port: Int, val tls: ServerTls)

/**
 * Password checks, account changes and other slow work run here, never on the world thread (SPEC
 * §2.5).
 */
private const val BACKGROUND_THREADS = 4

/**
 * A running server (SPEC §2.4). Without [prod] it is the `--dev` profile: raw telnet on localhost,
 * the passwordless local instructor, auto-reload on save and `@eval`. With [prod] it is `--prod`:
 * the account login, telnet on the configured address, and no `@eval`, auto-reload or per-pulse
 * invariant check (SPEC §2.4). Both load every package of the world and keep checkpoints and
 * backups.
 */
class GupsServer(
  private val dir: WorldDir,
  private val port: Int = 4000,
  private val start: Label? = null,
  /** The database; null runs without saving anything (`--dev` tests only). */
  private val db: Path? = defaultDb(dir),
  private val fresh: Boolean = false,
  private val prod: ProdOptions? = null,
  /** `--dev`: the plain-HTTP port of the API reference on 127.0.0.1; null serves none. */
  private val docsPort: Int? = null,
  private val log: (String) -> Unit = { System.err.println(it) },
) {
  private var persistence: SqlitePersistence? = null

  /** How long a shutdown waits for the final checkpoint to commit (SPEC §5.11). */
  val shutdownTimeoutMs: Long = prod?.shutdownTimeoutMs ?: SHUTDOWN_WAIT_MS

  /** The world's plugins, loaded once at boot (SPEC §4.15); closed when the server stops. */
  private var plugins: PluginHost? = null
  private var metrics: Metrics? = null
  private val startedNanos = System.nanoTime()

  @Volatile private var lastPulseNanos = System.nanoTime()

  @Volatile private var lastPulse = 0L
  private val background = Dispatchers.IO.limitedParallelism(BACKGROUND_THREADS).asExecutor()

  /** The world's CODEOWNERS as last read (SPEC §3.5). */
  @Volatile
  var codeOwners: CodeOwners = CodeOwners.EMPTY
    private set

  private val stopped = CountDownLatch(1)
  lateinit var world: World
    private set

  private lateinit var loop: WorldLoop
  private val sessionIds = java.util.concurrent.atomic.AtomicLong(1)

  /**
   * The listeners: raw telnet unless `--prod` turned it off, and telnet over TLS when configured.
   */
  private val listeners by lazy {
    listOfNotNull(
      (if (prod == null) "127.0.0.1" else prod.telnetHost)?.let {
        TelnetServer(loop, it, port, sessionIds)
      },
      prod?.tls?.let { TelnetServer(loop, it.host, it.port, sessionIds, it.tls) },
    )
  }

  /** Failed logins are counted once for telnet and the web (SPEC §4.10). */
  private val limiter = LoginLimiter()

  private val webServer by lazy {
    prod?.web?.let { w ->
      WebServer(
        loop,
        WebLogins(AccountService(persistence!!.accounts), limiter),
        WebAssets.fromClasspath(),
        w.host,
        w.port,
        sessionIds,
        w.tls,
        docs = docs,
      )
    }
  }

  /** The API reference, rendered once at boot and served at `/docs/` (SPEC §4.16). */
  private var docs: Map<String, ByteArray> = emptyMap()

  private val docsServer by lazy {
    docsPort?.takeIf { prod == null }?.let { DocsServer(docs, "127.0.0.1", it) }
  }

  /**
   * Bound ports by transport (`telnet`, `tls`, `ws`, `admin`, `docs`), once [run] has started the
   * listeners.
   */
  @Volatile
  var ports: Map<String, Int> = emptyMap()
    private set

  /** Loads the world. Returns false, after logging why, if there is nothing to play. */
  fun boot(): Boolean {
    val packages = dir.packages()
    if (packages.isEmpty()) {
      log("No packages under ${dir.root}. Point --world at a course world directory.")
      return false
    }
    val (module, moduleError) = WorldModule.read(dir)
    if (moduleError != null) {
      log("error: ${moduleError.render()}")
      return false
    }
    module.problems().forEach { log("error: $it") }
    if (module.problems().isNotEmpty()) return false
    module.warnings().forEach { log("warning: $it") }
    val plugins = loadPlugins(dir, module) { log("warning: $it") }.also { plugins = it }
    docs =
      DocsRenderer.render(
          ScriptApi.model(HarnessApi.specs()),
          plugins = plugins.describe().map(::toDocSpec),
        )
        .mapValues { it.value.toByteArray(Charsets.UTF_8) }
    // Packages that use a skipped plugin fail to load and are left out, as any failed package is.
    val loader = ScriptLoader(dir, ScriptLimits(), pluginGlobals = plugins.globals)
    val result = loader.load(packages)
    for (e in result.errors) log("load error: ${e.render()}")
    val protos = result.prototypes()
    if (protos.rooms.isEmpty()) {
      log("No package loaded any rooms; nothing to play.")
      return false
    }
    val at =
      start
        ?: module.startRoom
        ?: TUTORIAL_START.takeIf { it in protos.rooms }
        ?: protos.rooms.keys.minBy { it.v }
    if (at !in protos.rooms) {
      log("${if (start != null) "--start" else "MODULE.gups start_room"} $at is not a room.")
      return false
    }
    if (prod != null && db == null) {
      log("--prod needs a database.")
      return false
    }
    if (prod != null && !Git.available()) {
      log("--prod needs the git binary for deploys; install git.")
      return false
    }
    val host = StarlarkHost(result, ScriptLimits(), loader)
    val saved = db?.let { SqlitePersistence.open(it, log) }
    persistence = saved
    if (prod != null && !checkCommit(saved!!)) {
      saved.close()
      return false
    }
    val boot = saved?.boot(protos, fresh) ?: Boot()
    // --prod checks invariants only at world ticks, before capture (SPEC §2.4).
    val config =
      WorldConfig(
        seed = System.nanoTime(),
        checkInvariants = prod == null,
        checkpointPulses = module.checkpointPulses,
        bubble = true,
        production = prod != null,
        aliases = module.aliases,
      )
    world = World(protos, host.rules(), host, config, boot)
    plugins.install(world)
    host.install(world)
    if (saved != null) {
      val report = saved.restore(world, fresh)
      report.notes.forEach(log)
      log(
        "Restored ${report.restored} entities from $db at pulse ${world.pulse}" +
          if (report.dropped > 0) "; dropped ${report.dropped}." else "."
      )
    }
    DevTools.install(world, host, eval = prod == null)
    // Script alerts (quarantine, rules fallback) reach online instructors too (SPEC §6.2).
    host.alerts = { world.alert(it, it) }
    world.onAlert = { log("alert: $it") }
    world.onDiagnostic = { d ->
      val where = listOfNotNull(d.file, d.line?.toString()).joinToString(":")
      log("[${d.kind}] ${if (where.isEmpty()) "" else "$where: "}${d.message}")
      metrics?.diagnostic(d)
    }
    for (w in plugins.warnings) {
      world.record(Diagnostic(world.pulse, "plugin", w))
      world.alert("plugin_load", w)
    }
    world.fireOnLoad()
    plugins.started(world)
    if (saved != null) world.checkpointNow("boot")
    loop = WorldLoop(world)
    world.background = background
    world.post = loop::submit
    val m = Metrics(world, saved?.writer) { loop.overruns }
    metrics = m
    world.onPulse = { nanos ->
      lastPulseNanos = System.nanoTime()
      lastPulse = world.pulse
      m.pulsed(nanos)
    }
    if (prod != null && saved != null && Git(dir.root).isWorkTree()) {
      world.deploys =
        Deployer(
          dir.root,
          prod.deploy,
          world,
          host,
          saved,
          loop::submit,
          { resyncCodeOwners() },
          log,
        )
    } else if (prod != null) {
      log("warning: ${dir.root} isn't a git working tree, so this server can't deploy.")
    }
    saved?.accounts?.let { accounts ->
      syncCodeOwners(accounts, packages)
      world.restrictions = accounts.restrictions()
      AccountTools.install(world, AccountService(accounts), prod?.ladder ?: DEFAULT_LADDER) {
        codeOwners
      }
    }
    world.login =
      if (prod != null) {
        val accounts = saved!!.accounts
        if (accounts.all().isEmpty())
          log("There are no accounts yet. Create some with gups import-roster or gups invite.")
        AccountLogin(AccountService(accounts), at, background, loop::submit, prod.login, limiter)
      } else {
        // The local instructor is a real account once there is a database (SPEC §5.12).
        val instructor =
          saved?.accounts?.let { a ->
            a.byLogin(DEV_ACCOUNT)
              ?: a.create(
                Account(
                  0,
                  DEV_ACCOUNT,
                  null,
                  Role.INSTRUCTOR,
                  createdAt = System.currentTimeMillis(),
                )
              )
          }
        DevLogin(at, instructor?.id)
      }
    log(
      "Loaded ${result.packages.size} of ${packages.size} packages; new characters start in $at; checkpoints every ${module.checkpointMinutes} min."
    )
    return true
  }

  /**
   * Reads CODEOWNERS and replaces every account's builder scope (SPEC §3.5). Runs at startup;
   * deploys (M6.5) will run it again.
   */
  private fun syncCodeOwners(
    accounts: gups.core.AccountStore,
    packages: List<String>,
  ): gups.auth.SyncReport {
    val file =
      CodeOwners.LOCATIONS.map { dir.root.resolve(it) }
        .firstOrNull { java.nio.file.Files.isRegularFile(it) }
    codeOwners =
      file?.let {
        CodeOwners.parse(java.nio.file.Files.readString(it), dir.root.relativize(it).toString())
      } ?: CodeOwners.EMPTY
    val report = syncScopes(accounts, codeOwners, packages)
    report.warnings.forEach { log("warning: $it") }
    log(
      if (file == null) "No CODEOWNERS file; builders have only the packages given with @role."
      else "CODEOWNERS: ${report.changed.size} accounts' packages changed."
    )
    return report
  }

  /**
   * After a deploy: CODEOWNERS again, from the live tree, with the new scopes on characters in play
   * (SPEC §3.5).
   */
  private fun resyncCodeOwners() {
    val accounts = persistence?.accounts ?: return
    val report = syncCodeOwners(accounts, dir.packages())
    loop.submit(WorldControlEvent.Task { w -> report.changed.forEach { w.applyAccount(it) } })
  }

  /**
   * `--prod` refuses to start unless the working tree is at `meta.world_commit` and has no
   * uncommitted changes, which would mean someone edited the live world by hand (SPEC §7.2 step 7).
   * `--force-commit` adopts what is there. A first start records the commit.
   */
  private fun checkCommit(saved: SqlitePersistence): Boolean {
    val git = Git(dir.root)
    if (!git.isWorkTree()) return true
    val head =
      runCatching { git.head() }
        .getOrElse {
          log("warning: ${dir.root} has no commits yet, so deploys can't start from it.")
          return true
        }
    val dirty = git.dirtyFiles()
    val recorded = saved.worldCommit
    val force = prod?.forceCommit == true
    if (!force && dirty.isNotEmpty()) {
      log(
        "The world has changes nobody committed (${dirty.joinToString()}). Commit or discard them, or start with --force-commit."
      )
      return false
    }
    if (!force && recorded != null && recorded != head) {
      log(
        "The world is at $head, but the database was saved at $recorded. Check out $recorded, or start with --force-commit to adopt $head."
      )
      return false
    }
    if (recorded != head) log("Recording world commit $head.")
    saved.worldCommit = head
    return true
  }

  /** `/healthz` and the other admin endpoints (SPEC §4.14). */
  private val admin =
    object : gups.net.Admin {
      override fun health(): Pair<Boolean, Map<String, Any?>> {
        val writerOk = persistence?.writer?.healthy ?: true
        val stalled = System.nanoTime() - lastPulseNanos > STALL_NANOS
        return (writerOk && !stalled) to
          mapOf(
            "pulse" to lastPulse,
            "uptime_s" to (System.nanoTime() - startedNanos) / 1_000_000_000,
            "writer_healthy" to writerOk,
          )
      }

      override fun metrics(): String = metrics?.scrape().orEmpty()

      override fun deploy(
        commit: String?,
        progress: (Map<String, Any?>) -> Unit,
        done: (Map<String, Any?>) -> Unit,
      ) {
        val d =
          world.deploys
            ?: return done(
              mapOf(
                "ok" to false,
                "commit" to commit,
                "packages" to emptyList<String>(),
                "errors" to listOf("This server can't deploy."),
              )
            )
        d.start(commit, { progress(mapOf("progress" to it)) }) { o ->
          done(
            mapOf(
              "ok" to o.ok,
              "commit" to o.commit,
              "packages" to o.packages,
              "errors" to o.errors,
            )
          )
        }
      }

      override fun roster(csv: String): Pair<Int, String> {
        val accounts = persistence?.accounts ?: return 503 to "This server has no database.\n"
        val (entries, problems) = gups.auth.Roster.parse(csv)
        if (problems.isNotEmpty()) return 400 to problems.joinToString("\n", postfix = "\n")
        val r = AccountService(accounts).importRoster(entries)
        return 200 to
          (listOf("login,setup_code") + r.codes.map { (l, c) -> "$l,$c" }).joinToString(
            "\n",
            postfix = "\n",
          )
      }

      override fun snapshot(target: Path) {
        requireNotNull(persistence) { "this server has no database" }.sanitizedCopy(target)
      }
    }

  private val adminServer by lazy {
    prod?.admin?.let { gups.net.AdminServer(admin, it.token, it.host, it.port) }
  }

  /**
   * Runs until [stop]. [ready] gets the first listener's bound port once all are up; [ports] has
   * each.
   */
  suspend fun run(ready: (Int) -> Unit = {}) = coroutineScope {
    val running =
      launch(WorldLoop.worldDispatcher) {
        loop.run()
        // Shutdown ends with a final checkpoint (SPEC §5.11).
        if (persistence != null) world.checkpointNow("shutdown")
      }
    ports =
      listeners.associate { it.transport to it.start() } +
        listOfNotNull(
          webServer?.let { "ws" to it.start() },
          adminServer?.let { "admin" to it.start() },
        ) +
        // Last, so ready() still gets the telnet port.
        listOfNotNull(docsServer?.let { "docs" to it.start() })
    val certs = prod?.tls?.tls ?: prod?.web?.tls
    // Renewed certificates are picked up without a restart (SPEC §4.10).
    val certChecks =
      launch(Dispatchers.IO) {
        while (isActive && certs != null) {
          delay(CERT_CHECK_INTERVAL.toMillis())
          runCatching {
              if (certs.reloadIfChanged()) log("Reloaded the TLS certificate from ${certs.cert}.")
            }
            .onFailure {
              log("The TLS certificate didn't reload; keeping the old one: ${it.message}")
            }
        }
      }
    val watcher = WorldWatcher(dir)
    // --prod changes only through deploys (SPEC §1.4, §5.10).
    val watching =
      launch(Dispatchers.IO) {
        while (isActive && prod == null) {
          delay(WATCH_INTERVAL_MS)
          val changed = watcher.poll()
          if (changed.isNotEmpty()) {
            log("Reloading ${changed.joinToString(", ") { "//$it" }}")
            loop.submit(WorldControlEvent.Reload(changed))
          }
        }
      }
    val backups = persistence?.let { Backups(it.db) }
    val backingUp =
      launch(Dispatchers.IO) {
        while (isActive && backups != null) {
          runCatching { backups.runDue() }
            .onFailure { log("backup failed: $it") }
            .getOrNull()
            ?.forEach { log("Backup: $it") }
          delay(BACKUP_CHECK_MS)
        }
      }
    ready(ports.values.first())
    running.join()
    watching.cancel()
    backingUp.cancel()
    certChecks.cancel()
    listeners.forEach { it.stop() }
    webServer?.stop()
    adminServer?.stop()
    docsServer?.stop()
    persistence?.close(shutdownTimeoutMs)?.let { ok ->
      if (!ok) log("Some changes could not be saved before shutdown.")
    }
    plugins?.close()
    stopped.countDown()
  }

  fun stop() = loop.stop()

  /**
   * Waits up to [shutdownTimeoutMs] for [run] to finish its final checkpoint, for the JVM shutdown
   * hook.
   */
  fun awaitStopped(): Boolean = stopped.await(shutdownTimeoutMs, TimeUnit.MILLISECONDS)

  companion object {
    /** The `--dev` local instructor's account (SPEC §5.12). */
    const val DEV_ACCOUNT = "instructor"

    /** `--db` defaults to a hidden directory in the world, which the package scan skips (D-099). */
    fun defaultDb(dir: WorldDir): Path = dir.root.resolve(".gups").resolve("world.db")
  }
}
