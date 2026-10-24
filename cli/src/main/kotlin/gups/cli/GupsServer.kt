package gups.cli

import gups.auth.AccountLogin
import gups.auth.AccountService
import gups.auth.AccountTools
import gups.auth.CodeOwners
import gups.auth.DEFAULT_LADDER
import gups.auth.LoginSettings
import gups.auth.syncScopes
import gups.core.Account
import gups.core.Label
import gups.core.Role
import gups.core.WorldControlEvent
import gups.core.loop.WorldLoop
import gups.core.world.Boot
import gups.core.world.DevLogin
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.core.world.alert
import gups.net.TelnetServer
import gups.persist.Backups
import gups.persist.SqlitePersistence
import gups.script.DevTools
import gups.script.ScriptLimits
import gups.script.ScriptLoader
import gups.script.StarlarkHost
import gups.script.WorldDir
import gups.script.WorldModule
import gups.script.WorldWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Where new characters start unless `--start` or `start_room` says otherwise (D-082, D-138). */
val TUTORIAL_START = Label("//zones/tutorial:gate")

/** How often the server checks whether an hourly or daily backup is due. */
private const val BACKUP_CHECK_MS = 5 * 60_000L

/** How often the `--dev` file watcher looks for changes. */
private const val WATCH_INTERVAL_MS = 100L

/** What `--prod` adds (M6 design §3.5): where raw telnet listens, and the account login's settings. */
class ProdOptions(
    val telnetHost: String,
    val login: LoginSettings = LoginSettings(),
    /** `restriction_ladder` of the server configuration (M6 design §4.4). */
    val ladder: List<String> = DEFAULT_LADDER,
)

/** Password checks, account changes and other slow work run here, never on the world thread (M6 design §2.2). */
private const val BACKGROUND_THREADS = 4

/**
 * A running server (SPEC §2.4). Without [prod] it is the `--dev` profile: raw telnet on
 * localhost, the passwordless local instructor, auto-reload on save and `@eval`. With [prod]
 * it is `--prod`: the account login, telnet on the configured address, and no `@eval`,
 * auto-reload or per-pulse invariant check (M6 design §3.5). Both load every package of the
 * world and keep checkpoints and backups.
 */
class GupsServer(
    private val dir: WorldDir,
    private val port: Int = 4000,
    private val start: Label? = null,
    /** The database; null runs without saving anything (`--dev` tests only). */
    private val db: Path? = defaultDb(dir),
    private val fresh: Boolean = false,
    private val prod: ProdOptions? = null,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    private var persistence: SqlitePersistence? = null
    private val background = Dispatchers.IO.limitedParallelism(BACKGROUND_THREADS).asExecutor()

    /** The world's CODEOWNERS as last read (M6 design §4.1). */
    @Volatile var codeOwners: CodeOwners = CodeOwners.EMPTY
        private set
    private val stopped = CountDownLatch(1)
    lateinit var world: World
        private set
    private lateinit var loop: WorldLoop
    private val telnet by lazy { TelnetServer(loop, prod?.telnetHost ?: "127.0.0.1", port) }

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
        val loader = ScriptLoader(dir, ScriptLimits())
        val result = loader.load(packages)
        for (e in result.errors) log("load error: ${e.render()}")
        val protos = result.prototypes()
        if (protos.rooms.isEmpty()) {
            log("No package loaded any rooms; nothing to play.")
            return false
        }
        val at = start ?: module.startRoom ?: TUTORIAL_START.takeIf { it in protos.rooms } ?: protos.rooms.keys.minBy { it.v }
        if (at !in protos.rooms) {
            log("${if (start != null) "--start" else "MODULE.gups start_room"} $at is not a room.")
            return false
        }
        if (prod != null && db == null) {
            log("--prod needs a database.")
            return false
        }
        val host = StarlarkHost(result, ScriptLimits(), loader)
        val saved = db?.let { SqlitePersistence.open(it, log) }
        persistence = saved
        val boot = saved?.boot(protos, fresh) ?: Boot()
        // --prod checks invariants only at world ticks, before capture (M6 design §3.5).
        val config = WorldConfig(
            seed = System.nanoTime(),
            checkInvariants = prod == null,
            checkpointPulses = module.checkpointPulses,
            bubble = true,
            production = prod != null,
        )
        world = World(protos, host.rules(), host, config, boot)
        host.install(world)
        if (saved != null) {
            val report = saved.restore(world, fresh)
            report.notes.forEach(log)
            log("Restored ${report.restored} entities from $db at pulse ${world.pulse}" + if (report.dropped > 0) "; dropped ${report.dropped}." else ".")
        }
        DevTools.install(world, host, eval = prod == null)
        // Script alerts (quarantine, rules fallback) reach online instructors too (M6 design §4.5).
        host.alerts = { world.alert(it, it) }
        world.onAlert = { log("alert: $it") }
        world.onDiagnostic = { d ->
            val where = listOfNotNull(d.file, d.line?.toString()).joinToString(":")
            log("[${d.kind}] ${if (where.isEmpty()) "" else "$where: "}${d.message}")
        }
        world.fireOnLoad()
        if (saved != null) world.checkpointNow("boot")
        loop = WorldLoop(world)
        world.background = background
        world.post = loop::submit
        saved?.accounts?.let { accounts ->
            syncCodeOwners(accounts, packages)
            world.restrictions = accounts.restrictions()
            AccountTools.install(world, AccountService(accounts), prod?.ladder ?: DEFAULT_LADDER) { codeOwners }
        }
        world.login = if (prod != null) {
            val accounts = saved!!.accounts
            if (accounts.all().isEmpty()) log("There are no accounts yet. Create some with gups import-roster or gups invite.")
            AccountLogin(AccountService(accounts), at, background, loop::submit, prod.login)
        } else {
            // The local instructor is a real account once there is a database (M6 design §3.4).
            val instructor = saved?.accounts?.let { a ->
                a.byLogin(DEV_ACCOUNT) ?: a.create(Account(0, DEV_ACCOUNT, null, Role.INSTRUCTOR, createdAt = System.currentTimeMillis()))
            }
            DevLogin(at, instructor?.id)
        }
        log("Loaded ${result.packages.size} of ${packages.size} packages; new characters start in $at; checkpoints every ${module.checkpointMinutes} min.")
        return true
    }

    /**
     * Reads CODEOWNERS and replaces every account's builder scope (M6 design §4.1). Runs at
     * startup; deploys (M6.5) will run it again.
     */
    private fun syncCodeOwners(accounts: gups.core.AccountStore, packages: List<String>) {
        val file = CodeOwners.LOCATIONS.map { dir.root.resolve(it) }.firstOrNull { java.nio.file.Files.isRegularFile(it) }
        codeOwners = file?.let { CodeOwners.parse(java.nio.file.Files.readString(it), dir.root.relativize(it).toString()) } ?: CodeOwners.EMPTY
        val report = syncScopes(accounts, codeOwners, packages)
        report.warnings.forEach { log("warning: $it") }
        log(if (file == null) "No CODEOWNERS file; builders have only the packages given with @role." else "CODEOWNERS: ${report.changed.size} accounts' packages changed.")
    }

    /** Runs until [stop]. [ready] gets the bound port once the listener is up. */
    suspend fun run(ready: (Int) -> Unit = {}) = coroutineScope {
        val running = launch(WorldLoop.worldDispatcher) {
            loop.run()
            // Shutdown ends with a final checkpoint (SPEC §5.11).
            if (persistence != null) world.checkpointNow("shutdown")
        }
        val bound = telnet.start()
        val watcher = WorldWatcher(dir)
        // --prod changes only through deploys (SPEC §1.4, §5.10).
        val watching = launch(Dispatchers.IO) {
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
        val backingUp = launch(Dispatchers.IO) {
            while (isActive && backups != null) {
                runCatching { backups.runDue() }.onFailure { log("backup failed: $it") }.getOrNull()?.forEach { log("Backup: $it") }
                delay(BACKUP_CHECK_MS)
            }
        }
        ready(bound)
        running.join()
        watching.cancel()
        backingUp.cancel()
        telnet.stop()
        persistence?.close()?.let { ok -> if (!ok) log("Some changes could not be saved before shutdown.") }
        stopped.countDown()
    }

    fun stop() = loop.stop()

    /** Waits for [run] to finish its final checkpoint, for the JVM shutdown hook. */
    fun awaitStopped(timeoutMs: Long): Boolean = stopped.await(timeoutMs, TimeUnit.MILLISECONDS)

    companion object {
        /** The `--dev` local instructor's account (M6 design §3.4). */
        const val DEV_ACCOUNT = "instructor"

        /** `--db` defaults to a hidden directory in the world, which the package scan skips (D-099). */
        fun defaultDb(dir: WorldDir): Path = dir.root.resolve(".gups").resolve("world.db")
    }
}
