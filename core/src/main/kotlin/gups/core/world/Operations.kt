package gups.core.world

import gups.core.COMBAT_ROUND_PULSES
import gups.core.PULSE_MS
import gups.core.Restriction
import gups.core.Role
import gups.core.WorldControlEvent
import gups.core.model.Player
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A minute of pulses: the window of `@stats`, overrun alerts and the engine-error alert. */
const val MINUTE_PULSES = 60_000L / PULSE_MS

/** Owner alerts are sent at most once per package per 10 seconds (M6 design §4.5). */
const val OWNER_ALERT_PULSES = 10_000L / PULSE_MS

/** Each alert kind is repeated at most once a minute (M6 design §4.5). */
const val ALERT_REPEAT_PULSES = MINUTE_PULSES

/** More engine exceptions than this in a minute raise an instructor alert (SPEC §6.4). */
const val ENGINE_ERRORS_PER_MINUTE = 100

/** Diagnostic kinds that are a script's fault, for owner alerts. */
private val SCRIPT_KINDS = setOf("script_error", "step_limit", "wall_clock", "missing")

/**
 * Durations of the last minute of pulses (`@stats`, `/metrics`, M6 design §4.3, §4.5), and
 * which of them overran their 250 ms. Written by the world loop, read on the world thread.
 */
class PulseStats(private val size: Int = MINUTE_PULSES.toInt()) {
    private val nanos = LongArray(size)
    private val overran = BooleanArray(size)
    private var next = 0

    /** Pulses recorded, up to [size]. */
    var count = 0
        private set

    fun record(durationNanos: Long, overrun: Boolean) {
        nanos[next] = durationNanos
        overran[next] = overrun
        next = (next + 1) % size
        if (count < size) count++
    }

    /** The [q] quantile (0–1) of the recorded durations, in milliseconds. */
    fun quantileMs(q: Double): Double {
        if (count == 0) return 0.0
        val sorted = nanos.copyOf(count).sorted()
        return sorted[((count - 1) * q).toInt()] / 1e6
    }

    val overruns: Int get() = (0 until count).count { overran[it] }

    /** The share of recorded pulses that overran, once a full minute is recorded; else 0. */
    val overrunFraction: Double get() = if (count < size) 0.0 else overruns.toDouble() / count
}

/** A restriction in effect for [player], or null (M6 design §4.4). Instructors and admins are never restricted. */
fun World.restriction(player: Player, capability: String): Restriction? {
    if (player.role.atLeast(Role.INSTRUCTOR)) return null
    val account = player.accountId ?: return null
    val now = wallClock()
    return restrictions.firstOrNull { it.accountId == account && it.capability == capability && it.activeAt(now) }
}

/**
 * True, after telling [player] "You can't do that right now." (and when a timed restriction
 * ends), if [capability] is taken away from them.
 */
fun World.refuseRestricted(player: Player, capability: String): Boolean {
    val r = restriction(player, capability) ?: return false
    send(player, "You can't do that right now." + (r.until?.let { " That changes ${wallTime(it)}." } ?: ""))
    return true
}

private val WALL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

/** A wall-clock time for players and instructors, in UTC. */
fun wallTime(ms: Long): String = WALL.format(Instant.ofEpochMilli(ms))

/**
 * Runs [work] off the world thread on [World.background], then [then] with its result back on
 * the world thread through [World.post] (M6 design §2.2). A world without them (headless
 * tests) runs both at once.
 */
fun <T> World.later(work: () -> T, then: World.(Result<T>) -> Unit) {
    val exec = background
    val deliver = post
    if (exec == null || deliver == null) return then(runCatching(work))
    exec.execute {
        val r = runCatching(work)
        deliver(WorldControlEvent.Task { w -> w.then(r) })
    }
}

/** Sends [p]'s queued output now, for work finished by [later]. */
internal fun World.flushPlayer(p: Player) {
    p.session?.let(sessions::get)?.let(::flush)
}

/**
 * An instructor alert (M6 design §4.5): to every online instructor and admin, and to
 * [World.onAlert] (the server log). The same [kind] is repeated at most once a minute; the
 * log always gets it.
 */
fun World.alert(kind: String, message: String) {
    onAlert?.invoke(message)
    val last = alertTimes[kind]
    if (last != null && pulse - last < ALERT_REPEAT_PULSES) return
    alertTimes[kind] = pulse
    for (p in players) if (p.role.atLeast(Role.INSTRUCTOR)) send(p, "{yellow}[alert]{/} $message")
}

/** Owner alerts and the engine-error alert, for each diagnostic as it is recorded (M6 design §4.5, SPEC §6.4). */
internal fun World.alertFor(d: Diagnostic) {
    val pkg = d.pkg
    if (d.kind in SCRIPT_KINDS && pkg != null) {
        val last = ownerAlertTimes[pkg]
        if (last == null || pulse - last >= OWNER_ALERT_PULSES) {
            val owners = players.filter { it.role == Role.BUILDER && pkg in it.scope }
            if (owners.isNotEmpty()) {
                ownerAlertTimes[pkg] = pulse
                val where = listOfNotNull(d.file, d.line?.toString()).joinToString(":").ifEmpty { "//$pkg" }
                for (p in owners) send(p, "{red}[error]{/} $where: ${d.message.lineSequence().first()} (@errors $pkg)")
            }
        }
    }
    if (d.kind == "engine") {
        engineErrors.addLast(pulse)
        while (engineErrors.isNotEmpty() && pulse - engineErrors.first() >= MINUTE_PULSES) engineErrors.removeFirst()
        if (engineErrors.size > ENGINE_ERRORS_PER_MINUTE) alert("engine_errors", "${engineErrors.size} engine exceptions in the last minute; see @errors and the server log")
    }
    if (d.kind == "checkpoint") alert("checkpoint", d.message)
}

/**
 * Called by the world loop after each pulse: samples the scripts' step counts every combat
 * round, and alerts when more than 10% of the last minute's pulses overran, naming the
 * package that used the most steps in that minute (M6 design §4.5).
 */
fun World.checkOverruns() {
    if (pulse % COMBAT_ROUND_PULSES == 0L) {
        stepSamples.addLast(pulse to scripts.stepsByPackage)
        while (stepSamples.size > 1 && pulse - stepSamples.first().first > MINUTE_PULSES) stepSamples.removeFirst()
    }
    val fraction = pulseStats.overrunFraction
    if (fraction <= 0.1) return
    val busiest = busiestPackage()
    alert(
        "overrun",
        "${"%.0f".format(fraction * 100)}% of pulses in the last minute overran" +
            (busiest?.let { (pkg, steps) -> "; //$pkg used the most script steps ($steps)" } ?: ""),
    )
}

/** The package that used the most script steps over the sampled minute, with that count. */
fun World.busiestPackage(): Pair<String, Long>? {
    val first = stepSamples.firstOrNull()?.second ?: return null
    val now = scripts.stepsByPackage
    return now.map { (pkg, n) -> pkg to n - (first[pkg] ?: 0) }.filter { it.second > 0 }.maxByOrNull { it.second }
}

/**
 * Shuts the world down in [delayPulses] (`@shutdown`, M6 design §4.3), warning players 60,
 * 30, 10 and 5 seconds before. The world loop then stops and takes the final checkpoint.
 */
fun World.scheduleShutdown(delayPulses: Long, reason: String) {
    shutdownAt = pulse + delayPulses.coerceAtLeast(0)
    shutdownReason = reason
    val seconds = delayPulses * PULSE_MS / 1000
    val text = if (seconds <= 0) "The world is shutting down: $reason" else "The world will shut down in ${seconds}s: $reason"
    for (p in players) send(p, text)
    flushOutput()
}

/** The shutdown countdown warnings, once per pulse. */
internal fun World.shutdownWarnings() {
    val at = shutdownAt ?: return
    val left = (at - pulse) * PULSE_MS
    if (left > 0 && left / 1000 in SHUTDOWN_WARNINGS && left % 1000 == 0L) {
        for (p in players) send(p, "The world shuts down in ${left / 1000} seconds: ${shutdownReason ?: "shutdown"}")
    }
}

private val SHUTDOWN_WARNINGS = setOf(60L, 30L, 10L, 5L)

/**
 * Starts a production deploy (M6 design §7.2), implemented by the server. [start] returns at
 * once; [progress] gets each step's line off the world thread, and [done] the outcome.
 */
fun interface Deploys {
    fun start(commit: String?, progress: (String) -> Unit, done: (DeployOutcome) -> Unit)
}

/** How a deploy ended (M6 design §7.1 `POST /deploy`). */
data class DeployOutcome(val ok: Boolean, val commit: String?, val packages: List<String>, val errors: List<String>)
