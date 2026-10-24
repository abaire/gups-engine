package gups.core.world

import gups.core.ClientInputEvent
import gups.core.IDLE_QUIT_PULSES
import gups.core.LINKDEAD_PULSES
import gups.core.OutputSink
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Actor
import gups.core.model.Player
import gups.core.model.Room

/** World-side state of one connection. */
class Session internal constructor(val id: SessionId, val sink: OutputSink, connectedNanos: Long) {
    var player: Player? = null
        internal set
    internal val bucket = TokenBucket(startNanos = connectedNanos)
    internal val drops = ArrayDeque<Long>()
    internal val out = StringBuilder()
}

private const val DROP_WINDOW_NANOS = 60_000_000_000L
private const val MAX_DROPS = 3

fun World.session(id: SessionId): Session? = sessions[id]

fun World.handle(event: WorldControlEvent, nowNanos: Long = System.nanoTime()) {
    when (event) {
        is WorldControlEvent.Connect -> {
            require(event.session !in sessions) { "${event.session} is already connected" }
            sessions[event.session] = Session(event.session, event.sink, nowNanos)
        }
        is WorldControlEvent.Disconnect -> disconnect(event.session)
        is WorldControlEvent.Shutdown -> {
            shutdownAt = pulse + event.delayPulses
            for (p in players) send(p, "The world is shutting down: ${event.reason}")
        }
        // Hot reload and deploy need the script host (M2) and the deploy pipeline (M6).
        is WorldControlEvent.Reload, is WorldControlEvent.Deploy ->
            diagnose("control", "$event is not supported by this engine build")
    }
}

/** Binds [player] to [session]. Reconnecting to a link-dead character resumes it (SPEC §5.12). */
fun World.attach(session: SessionId, player: Player) {
    val s = requireNotNull(sessions[session]) { "Unknown $session" }
    require(player.inWorld) { "$player is not in the world" }
    player.session?.let { old -> if (old != session) sessions[old]?.player = null }
    s.player = player
    player.session = session
    player.linkDeadSince = null
    player.lastInputPulse = pulse
}

/** Handles one line of input between pulses (SPEC §5.1, §5.2). */
fun World.handle(event: ClientInputEvent) {
    val s = sessions[event.session] ?: return
    val player = s.player
    if (player == null) {
        s.out.append("You are not playing a character.\n")
        flush(s)
        return
    }
    player.lastInputPulse = pulse
    val (verb, _) = parseLine(event.line) ?: return
    val def = commands.resolve(player, verb)
    if (def?.lane == Lane.FAST && !s.bucket.tryTake(event.receivedNanos)) {
        s.out.append("Slow down.\n")
        s.drops.addLast(event.receivedNanos)
        while (s.drops.isNotEmpty() && event.receivedNanos - s.drops.first() > DROP_WINDOW_NANOS) s.drops.removeFirst()
        flush(s)
        if (s.drops.size > MAX_DROPS) {
            s.sink.close("Too much input.")
            disconnect(s.id)
        }
        return
    }
    submit(player, event.line)
    flush(s)
}

/** The connection dropped: the character goes link-dead and keeps auto-attacking (SPEC §5.12). */
fun World.disconnect(id: SessionId) {
    val s = sessions.remove(id) ?: return
    val p = s.player ?: return
    if (p.session != id) return
    p.session = null
    p.linkDeadSince = pulse
    p.actionQueue.clear()
    echo(p.room, "${p.name} has lost their link.", p)
}

/** Removes [player] from the world. Persistence takes its immediate checkpoint here from M4. */
fun World.quit(player: Player, message: String = "Goodbye.") {
    if (!player.inWorld) return
    send(player, message)
    player.session?.let { id ->
        sessions[id]?.let { s ->
            flush(s)
            s.player = null
            s.sink.close(message)
            sessions.remove(id)
        }
    }
    player.session = null
    disengageAll(player)
    val room = player.room
    removeActor(player)
    echo(room, "${player.name} has left the game.")
}

/** Link-dead and idle auto-quit (SPEC §5.12). */
internal fun World.expireSessions() {
    for (p in players) {
        val dead = p.linkDeadSince
        when {
            dead != null && pulse - dead >= LINKDEAD_PULSES -> quit(p)
            dead == null && !p.idleExempt && pulse - p.lastInputPulse >= IDLE_QUIT_PULSES ->
                quit(p, "You have been idle too long. Goodbye.")
        }
    }
}

// ---- Output ----------------------------------------------------------------------------------

/** Sends one line to [actor]. Mobs and link-dead players see nothing. */
fun World.send(actor: Actor, text: String) {
    val p = actor as? Player ?: return
    val s = p.session?.let(sessions::get) ?: return
    s.out.append(text).append('\n')
}

/** Sends one line to every player in [room] except [exclude]. */
fun World.echo(room: Room, text: String, vararg exclude: Actor) {
    for (a in room.actorIndex.values) if (exclude.none { it === a }) send(a, text)
}

internal fun World.flush(s: Session) {
    if (s.out.isEmpty()) return
    val text = s.out.toString()
    s.out.setLength(0)
    s.sink.send(text)
}

/** Phase 7 (SPEC §5.1). GMCP deltas join this flush in M6. */
internal fun World.flushOutput() {
    for (s in sessions.values.toList()) flush(s)
}

internal fun String.cap(): String = replaceFirstChar { it.uppercase() }
