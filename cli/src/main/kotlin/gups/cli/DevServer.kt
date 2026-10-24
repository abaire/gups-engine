package gups.cli

import gups.core.Label
import gups.core.WorldControlEvent
import gups.core.loop.WorldLoop
import gups.core.rules.DefaultRules
import gups.core.world.DevLogin
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.net.TelnetServer
import gups.script.DevTools
import gups.script.ScriptLimits
import gups.script.ScriptLoader
import gups.script.StarlarkHost
import gups.script.WorldDir
import gups.script.WorldWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Where `--dev` puts new characters unless `--start` says otherwise (D-082). */
val TUTORIAL_START = Label("//zones/tutorial:gate")

/** How often the `--dev` file watcher looks for changes. */
private const val WATCH_INTERVAL_MS = 100L

/**
 * The `--dev` profile (SPEC §2.4): every package of the world, raw telnet on localhost, the
 * passwordless local instructor, auto-reload on save, and `@eval`. Checkpoints arrive in M4.
 */
class DevServer(
    private val dir: WorldDir,
    private val port: Int = 4000,
    private val start: Label? = null,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
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
        world = World(protos, DefaultRules(), host, WorldConfig(seed = System.nanoTime(), checkInvariants = true))
        host.install(world)
        DevTools.install(world, host, eval = true)
        host.alerts = { log("alert: $it") }
        world.login = DevLogin(at)
        world.onDiagnostic = { d ->
            val where = listOfNotNull(d.file, d.line?.toString()).joinToString(":")
            log("[${d.kind}] ${if (where.isEmpty()) "" else "$where: "}${d.message}")
        }
        world.fireOnLoad()
        loop = WorldLoop(world)
        log("Loaded ${result.packages.size} of ${packages.size} packages; new characters start in $at.")
        return true
    }

    /** Runs until [stop]. [ready] gets the bound port once the listener is up. */
    suspend fun run(ready: (Int) -> Unit = {}) = coroutineScope {
        val running = launch(WorldLoop.worldDispatcher) { loop.run() }
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
        ready(bound)
        running.join()
        watching.cancel()
        telnet.stop()
    }

    fun stop() = loop.stop()
}
