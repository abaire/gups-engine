package gups.script

import gups.core.CapturingSink
import gups.core.ClientInputEvent
import gups.core.Label
import gups.core.PULSE_MS
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.rules.StartingStats
import gups.core.world.ScriptHost
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.core.world.attach
import gups.core.world.handle
import gups.script.testing.TestWorlds
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** `package.star` for a zone named after its last path segment. */
fun zonePkg(extra: String = "") =
  "package(api_version = 1, kind = \"zone\", title = \"Test zone\"$extra)\n"

fun libPkg(extra: String = "") = "package(api_version = 1, kind = \"lib\"$extra)\n"

/** Writes [files] (paths relative to the world root) into a fresh temporary world. */
fun tempWorld(files: Map<String, String>): Path {
  val root = Files.createTempDirectory("gups-world")
  for ((path, text) in TestWorlds.withDefaults(files)) {
    val p = root.resolve(path)
    p.parent.createDirectories()
    p.writeText(text.trimIndent() + "\n")
  }
  return root
}

/** Loads a world from [files] and runs it headless with a [StarlarkHost], as the harness does. */
class ScriptFixture(
  files: Map<String, String>,
  load: List<String> = listOf("zones/z"),
  val limits: ScriptLimits = ScriptLimits.HARNESS,
  seed: Long = 1,
  bubble: Boolean = false,
  /** Wraps the host the world calls, for tests that watch hook calls. */
  wrap: (ScriptHost) -> ScriptHost = { it },
) {
  val root: Path = tempWorld(files)
  val loader = ScriptLoader(WorldDir(root), limits)
  val result: LoadResult =
    loader.load((load + RULES_PACKAGE).distinct().filter { WorldDir(root).hasPackage(it) })
  val host: StarlarkHost by lazy { StarlarkHost(result, limits, loader) }
  val w: World by lazy {
    check(result.ok) { "load failed:\n" + result.report() }
    World(
        result.prototypes(),
        loader.schema,
        host.rules(),
        wrap(host),
        loader.module.config(
          WorldConfig(seed = seed, checkInvariants = true, strict = true, bubble = bubble),
          WorldMessages.read(WorldDir(root)).first,
        ),
        levelMode = loader.module.levelMode,
        standingHistoryPulses = loader.module.standingHistoryPulses,
      )
      .also {
        host.install(it)
        it.start()
      }
  }
  private val sinks = mutableMapOf<Player, CapturingSink>()
  private var nextSession = 1L
  private var nanos = 0L

  fun player(name: String = "Ann", at: String = "//zones/z:start", hp: Int = 50): Player {
    val p =
      w.spawnPlayer(
        name,
        Label(at),
        overrides = StartingStats(max = mapOf("hp" to hp), current = mapOf("hp" to hp)),
      )
    val id = SessionId(nextSession++)
    val sink = CapturingSink()
    w.handle(WorldControlEvent.Connect(id, sink), nanos)
    w.attach(id, p)
    sinks[p] = sink
    return p
  }

  fun mob(label: String, at: String = "//zones/z:start"): Mob =
    w.spawnMob(Label(label), w.room(Label(at)))

  fun input(p: Player, line: String) {
    nanos += 1_000_000_000
    w.handle(ClientInputEvent(p.session!!, line, nanos))
  }

  /** Output since the last call. */
  fun out(p: Player): String = sinks.getValue(p).drain()

  fun run(pulses: Int = 1) =
    repeat(pulses) {
      w.runPulse()
      nanos += PULSE_MS * 1_000_000
    }

  /** Rewrites a world file, as a builder saving in their editor would. */
  fun write(path: String, text: String) = root.resolve(path).writeText(text.trimIndent() + "\n")

  fun diagnostics(kind: String? = null) = w.diagnostics.filter { kind == null || it.kind == kind }
}
