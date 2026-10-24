package gups.cli

import gups.core.Label
import gups.core.WorldControlEvent
import gups.core.loop.WorldLoop
import gups.core.rules.DefaultRules
import gups.core.world.Boot
import gups.core.world.DevLogin
import gups.core.world.World
import gups.core.world.WorldConfig
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Where `--dev` puts new characters unless `--start` says otherwise (D-082). */
val TUTORIAL_START = Label("//zones/tutorial:gate")

/** How often the server checks whether an hourly or daily backup is due. */
private const val BACKUP_CHECK_MS = 5 * 60_000L

/** How often the `--dev` file watcher looks for changes. */
private const val WATCH_INTERVAL_MS = 100L

/**
 * The `--dev` profile (SPEC §2.4): every package of the world, raw telnet on localhost, the
 * passwordless local instructor, auto-reload on save, `@eval`, and checkpoints and backups
 * as in production.
 */
class DevServer(
    private val dir: WorldDir,
    private val port: Int = 4000,
    private val start: Label? = null,
    /** The database; null runs without saving anything (tests). */
    private val db: Path? = defaultDb(dir),
    private val fresh: Boolean = false,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    private var persistence: SqlitePersistence? = null
    private val stopped = CountDownLatch(1)
    lateinit var world: World
        private set
    private lateinit var loop: WorldLoop
    private val telnet by lazy { TelnetServer(loop, "127.0.0.1", port) }

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
        val at = start ?: TUTORIAL_START.takeIf { it in protos.rooms } ?: protos.rooms.keys.minBy { it.v }
        if (at !in protos.rooms) {
            log("--start $at is not a room.")
            return false
        }
        val host = StarlarkHost(result, ScriptLimits(), loader)
        val saved = db?.let { SqlitePersistence.open(it, log) }
        persistence = saved
        val boot = saved?.boot(protos, fresh) ?: Boot()
        world = World(protos, DefaultRules(), host, WorldConfig(seed = System.nanoTime(), checkInvariants = true, checkpointPulses = module.checkpointPulses), boot)
        host.install(world)
        if (saved != null) {
            val report = saved.restore(world, fresh)
            report.notes.forEach(log)
            log("Restored ${report.restored} entities from $db at pulse ${world.pulse}" + if (report.dropped > 0) "; dropped ${report.dropped}." else ".")
        }
        DevTools.install(world, host, eval = true)
        host.alerts = { log("alert: $it") }
        world.login = DevLogin(at)
        world.onDiagnostic = { d ->
            val where = listOfNotNull(d.file, d.line?.toString()).joinToString(":")
            log("[${d.kind}] ${if (where.isEmpty()) "" else "$where: "}${d.message}")
        }
        world.fireOnLoad()
        if (saved != null) world.checkpointNow("boot")
        loop = WorldLoop(world)
        log("Loaded ${result.packages.size} of ${packages.size} packages; new characters start in $at; checkpoints every ${module.checkpointMinutes} min.")
        return true
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
        val watching = launch(Dispatchers.IO) {
            while (isActive) {
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
        /** `--db` defaults to a hidden directory in the world, which the package scan skips (D-099). */
        fun defaultDb(dir: WorldDir): Path = dir.root.resolve(".gups").resolve("world.db")
    }
}
