package gups.cli

import gups.core.PULSE_MS
import gups.core.world.Diagnostic
import gups.core.world.World
import gups.core.world.activeRooms
import gups.core.world.isSimulated
import gups.core.world.session
import gups.persist.CheckpointWriter
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * World state for gauges, published by the world thread every [SNAPSHOT_PULSES] so scrapes never
 * touch the world.
 */
private class Snapshot(
  val quarantined: Int = 0,
  val bubbleRooms: Int = 0,
  val bubbleEntities: Int = 0,
  val sessions: Map<String, Int> = emptyMap(),
  val steps: Map<String, Long> = emptyMap(),
)

/** Gauges are refreshed once a second of game time. */
private const val SNAPSHOT_PULSES = 1000 / PULSE_MS

/** Transports a server can have, so each `gups_sessions` series exists from the start. */
private val TRANSPORTS = listOf("telnet", "tls", "ws", "local")

/**
 * Prometheus telemetry and instrumentation registry powered by Micrometer.
 *
 * Scrapes read exclusively from thread-safe meters and an immutable [Snapshot] published by the
 * world thread once per second of game time ([SNAPSHOT_PULSES] = 4 pulses at 250 ms/pulse),
 * eliminating synchronization locks during metrics export.
 *
 * ## Published Prometheus Metrics
 * - `gups_pulse_duration_seconds` (meter `gups.pulse.duration`): Percentile histogram timer tracking elapsed execution time per world
 *   pulse.
 * - `gups_pulse_overruns_total` (meter `gups.pulse.overruns`): Monotonic counter tracking pulse intervals skipped when world processing
 *   exceeds 250 ms.
 * - `gups_world_input_queue_depth` (meter `gups.world.input.queue.depth`): Gauge tracking client input commands waiting in the world
 *   inbox.
 * - `gups_checkpoint_duration_seconds` (meter `gups.checkpoint.duration`): Percentile histogram timer measuring duration of SQLite transaction
 *   commits.
 * - `gups_checkpoint_entities` (meter `gups.checkpoint.entities`): Gauge tracking entity and player row counts in the most recent
 *   committed checkpoint.
 * - `gups_checkpoint_backlog` (meter `gups.checkpoint.backlog`): Gauge tracking pending checkpoint batches queued for the writer
 *   thread.
 * - `gups_quarantined_packages` (meter `gups.quarantined.packages`): Gauge counting packages currently quarantined due to excessive
 *   runtime errors.
 * - `gups_bubble_rooms` (meter `gups.bubble.rooms`): Gauge tracking actively simulated rooms inside the reality bubble.
 * - `gups_bubble_entities` (meter `gups.bubble.entities`): Gauge tracking simulated entities inside active rooms.
 * - `gups_sessions` (meter `gups.sessions`): Gauge tracking active client connections, tagged with `transport` (`telnet`,
 *   `tls`, `ws`, `local`).
 * - `gups_script_steps_total` (meter `gups.script.steps`): Function counter tracking Starlark bytecode steps executed, tagged by
 *   `package`.
 * - `gups_script_errors_total` (meter `gups.script.errors`): Counter tracking script failures, tagged by `package` and `kind`
 *   (`script_error`, `step_limit`, `wall_clock`, `alloc_limit`, `missing`).
 */
class Metrics(private val world: World, writer: CheckpointWriter?, overruns: () -> Long) {
  val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

  @Volatile private var snapshot = Snapshot()
  private val stepCounters = ConcurrentHashMap.newKeySet<String>()
  @Volatile private var lastEntities = 0

  private val pulse: Timer =
    Timer.builder("gups.pulse.duration")
      .description("Time to run one pulse")
      .publishPercentileHistogram()
      .minimumExpectedValue(Duration.ofNanos(100_000))
      .maximumExpectedValue(Duration.ofSeconds(2))
      .register(registry)

  private val checkpoint: Timer =
    Timer.builder("gups.checkpoint.duration")
      .description("Time to commit one checkpoint batch")
      .publishPercentileHistogram()
      .register(registry)

  init {
    FunctionCounter.builder("gups.pulse.overruns", overruns) { it().toDouble() }
      .description("Pulse boundaries skipped because a pulse ran long")
      .register(registry)
    Gauge.builder("gups.world.input.queue.depth", world) { it.queueDepth().toDouble() }
      .description("Input lines waiting for the world thread")
      .register(registry)
    Gauge.builder("gups.quarantined.packages", this) { it.snapshot.quarantined.toDouble() }
      .register(registry)
    Gauge.builder("gups.bubble.rooms", this) { it.snapshot.bubbleRooms.toDouble() }
      .description("Rooms being simulated")
      .register(registry)
    Gauge.builder("gups.bubble.entities", this) { it.snapshot.bubbleEntities.toDouble() }
      .description("Entities being simulated")
      .register(registry)
    for (t in TRANSPORTS) {
      Gauge.builder("gups.sessions", this) { it.snapshot.sessions[t]?.toDouble() ?: 0.0 }
        .tag("transport", t)
        .register(registry)
    }
    Gauge.builder("gups.checkpoint.entities", this) { it.lastEntities.toDouble() }
      .description("Rows in the last committed checkpoint batch")
      .register(registry)
    Gauge.builder("gups.checkpoint.backlog", writer) { it?.backlog?.toDouble() ?: 0.0 }
      .description("Batches waiting for the database writer")
      .register(registry)
    for (pkg in world.scripts.packages) {
      stepsOf(pkg)
      errors(pkg, "script_error")
    }
    writer?.onCommit = { b, nanos ->
      checkpoint.record(nanos, TimeUnit.NANOSECONDS)
      lastEntities = b.entities.size + b.players.size
    }
  }

  private fun stepsOf(pkg: String) {
    if (!stepCounters.add(pkg)) return
    FunctionCounter.builder("gups.script.steps", this) { it.snapshot.steps[pkg]?.toDouble() ?: 0.0 }
      .description("Script steps used by a package")
      .tag("package", pkg)
      .register(registry)
  }

  private fun errors(pkg: String, kind: String): Counter =
    Counter.builder("gups.script.errors")
      .description("Script failures by package and kind")
      .tags("package", pkg, "kind", kind)
      .register(registry)

  /** Call on the world thread after each pulse. */
  fun pulsed(nanos: Long) {
    pulse.record(nanos, TimeUnit.NANOSECONDS)
    if (world.pulse % SNAPSHOT_PULSES != 0L) return
    val steps = world.scripts.stepsByPackage
    steps.keys.forEach(::stepsOf)
    snapshot =
      Snapshot(
        quarantined = world.scripts.quarantinedPackages.size,
        bubbleRooms = if (world.config.bubble) world.activeRooms.size else world.rooms.size,
        bubbleEntities = world.allEntities.count { world.isSimulated(it) },
        sessions =
          world.players
            .mapNotNull { p -> p.session?.let { world.transportOf(it) } }
            .groupingBy { it }
            .eachCount(),
        steps = steps,
      )
  }

  /** Call on the world thread for each diagnostic. */
  fun diagnostic(d: Diagnostic) {
    val pkg = d.pkg ?: return
    if (d.kind in setOf("script_error", "step_limit", "wall_clock", "alloc_limit", "missing"))
      errors(pkg, d.kind).increment()
  }

  fun scrape(): String = registry.scrape()
}

/** The transport of a session, for the sessions gauge. */
private fun World.transportOf(id: gups.core.SessionId): String? = session(id)?.transport
