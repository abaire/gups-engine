package gups.core.world

import gups.core.ClientGmcpEvent
import gups.core.ClientInputEvent
import gups.core.IDLE_QUIT_PULSES
import gups.core.LINKDEAD_PULSES
import gups.core.OutputSink
import gups.core.Role
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Actor
import gups.core.model.Player
import gups.core.model.Room

/** World-side state of one connection. */
class Session
internal constructor(
  val id: SessionId,
  val sink: OutputSink,
  connectedNanos: Long,
  /** Peer address, for example `127.0.0.1`. */
  val remote: String = "local",
  val transport: String = "local",
  /** The account a web session token already proved, if any. */
  val account: Long? = null,
) {
  var player: Player? = null
    internal set

  internal val bucket = TokenBucket(startNanos = connectedNanos)

  /** Caps every raw input line, whatever it parses to, so floods can't bypass [bucket]. */
  internal val lines =
    TokenBucket(capacity = LINE_RATE, refillPerSecond = LINE_RATE, startNanos = connectedNanos)
  internal val drops = ArrayDeque<Long>()
  internal val out = StringBuilder()

  /** The last state payload sent per GMCP package, and events waiting for the next flush. */
  internal val gmcpLast = HashMap<String, Any?>()
  internal val gmcpOut = mutableListOf<Pair<String, Any?>>()
}

private const val DROP_WINDOW_NANOS = 60_000_000_000L
private const val MAX_DROPS = 3
private const val LINE_RATE = 25.0

fun World.session(id: SessionId): Session? = sessions[id]

fun World.handle(event: WorldControlEvent, nowNanos: Long = System.nanoTime()) {
  when (event) {
    is WorldControlEvent.Connect -> {
      require(event.session !in sessions) { "${event.session} is already connected" }
      val s =
        Session(event.session, event.sink, nowNanos, event.remote, event.transport, event.account)
      sessions[event.session] = s
      login?.connected(this, s)
    }
    is WorldControlEvent.Disconnect -> disconnect(event.session)
    is WorldControlEvent.Shutdown -> scheduleShutdown(event.delayPulses, event.reason)
    is WorldControlEvent.Reload -> reload(event.packages)
    is WorldControlEvent.Deploy -> {
      val d = deploys ?: return diagnose("control", "$event: this server can't deploy")
      d.start(event.commit.ifEmpty { null }, {}) {}
    }
    is WorldControlEvent.AuthResult -> {
      // A session that closed meanwhile simply drops its result.
      val s = sessions[event.session] ?: return
      login?.completed(this, s, event.outcome)
      flush(s)
    }
    is WorldControlEvent.Task -> {
      guarded("background task") { event.run(this) }
      flushOutput()
    }
  }
}

/** Binds [player] to [session]. Reconnecting to a link-dead character resumes it. */
fun World.attach(session: SessionId, player: Player) {
  val s = requireNotNull(sessions[session]) { "Unknown $session" }
  require(player.inWorld) { "$player is not in the world" }
  player.session?.let { old -> if (old != session) sessions[old]?.player = null }
  s.player = player
  s.gmcpLast.clear()
  player.session = session
  player.linkDeadSince = null
  player.lastInputPulse = pulse
}

/** Handles one line of input between pulses. */
fun World.handle(event: ClientInputEvent) {
  val s = sessions[event.session] ?: return
  // Floods are dropped silently, before login or parsing, so they cost no reply traffic.
  if (!s.lines.tryTake(event.receivedNanos)) {
    dropped(s, event.receivedNanos)
    return
  }
  val player = s.player
  if (player == null) {
    val handler = login
    if (handler != null) {
      handler.input(this, s, event.line)
      flush(s)
      return
    }
    // Nothing can ever give this session a character, so it must not linger.
    s.out.append(msg(KernelMsg.NO_CHARACTER)).append('\n')
    flush(s)
    s.sink.close(msg(KernelMsg.NO_CHARACTER))
    disconnect(s.id)
    return
  }
  player.lastInputPulse = pulse
  val (verb, _) = parseLine(event.line, commands.aliases.prefixes) ?: return
  val def = commands.resolve(player, verb)
  if (def?.lane == Lane.FAST && !s.bucket.tryTake(event.receivedNanos)) {
    s.out.append(msg(KernelMsg.SLOW_DOWN)).append('\n')
    flush(s)
    dropped(s, event.receivedNanos)
    return
  }
  submit(player, event.line)
  flushImmediate()
  flush(s)
}

/** Handles one inbound GMCP message between pulses. */
fun World.handle(event: ClientGmcpEvent) {
  val s = sessions[event.session] ?: return
  if (!s.lines.tryTake(event.receivedNanos)) {
    dropped(s, event.receivedNanos)
    return
  }
  val player = s.player ?: return
  val input = pluginGmcpIn[event.pkg.lowercase()] ?: return
  if (event.malformed) {
    dropped(s, event.receivedNanos)
    return
  }
  if (!s.bucket.tryTake(event.receivedNanos)) {
    s.out.append(msg(KernelMsg.SLOW_DOWN)).append('\n')
    flush(s)
    dropped(s, event.receivedNanos)
    return
  }
  player.lastInputPulse = pulse
  guarded("GMCP input '${input.pkg}' of $player") { input.handle(player, event.data) }
  flushImmediate()
  flush(s)
}

/** Records a dropped line; more than [MAX_DROPS] within a minute disconnects. */
private fun World.dropped(s: Session, nowNanos: Long) {
  s.drops.addLast(nowNanos)
  while (s.drops.isNotEmpty() && nowNanos - s.drops.first() > DROP_WINDOW_NANOS) s.drops
    .removeFirst()
  if (s.drops.size > MAX_DROPS) {
    s.sink.close(msg(LoginMsg.TOO_MUCH_INPUT))
    disconnect(s.id)
  }
}

/** The connection dropped: the character goes link-dead and keeps auto-attacking. */
fun World.disconnect(id: SessionId) {
  val s = sessions.remove(id) ?: return
  login?.disconnected(this, id)
  val p = s.player ?: return
  if (p.session != id) return
  p.session = null
  p.linkDeadSince = pulse
  p.actionQueue.clear()
  echo(p.room, msg(KernelMsg.LINK_LOST, "name" to p.name), p)
}

/** Removes [player] from the world. Persistence takes its immediate checkpoint here. */
fun World.quit(player: Player, message: String = msg(KernelMsg.QUIT_GOODBYE)) {
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
  // The opponents it frees hear of it after it has left, so a hook can't re-engage it.
  val freed = unlinkAll(player)
  despawnDebugMobs(player)
  // Logout saves the character and everything it carries, at once.
  player.dirty = true
  for (item in player.inventory + player.equipment.values) markDirtyTree(item)
  immediateCause = null
  guarded("checkpoint") { persistence.checkpoint(this, "quit", immediate = true) }
  val room = player.room
  removeActor(player)
  echo(room, msg(KernelMsg.QUIT_LEFT, "name" to player.name))
  fireDisengage(freed, "stop")
}

/**
 * Takes [player], loaded for a login that hasn't entered it, back out of the world without saving
 * it, so its saved row stays as it was.
 */
fun World.unload(player: Player) {
  if (player.inWorld && player.session == null) removeActor(player)
}

/** Link-dead and idle auto-quit. */
internal fun World.expireSessions() {
  login?.let { guarded("login timeouts") { it.pulse(this) } }
  for (p in players) {
    val dead = p.linkDeadSince
    when {
      dead != null && pulse - dead >= LINKDEAD_PULSES -> quit(p)
      dead == null && !p.idleExempt && pulse - p.lastInputPulse >= IDLE_QUIT_PULSES ->
        quit(p, msg(KernelMsg.QUIT_IDLE))
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

/** Sends [s] its queued output, then its prompt if it is playing. */
fun World.flush(s: Session) {
  if (s.out.isEmpty() && s.gmcpOut.isEmpty()) return
  val text = s.out.toString()
  s.out.setLength(0)
  if (text.isNotEmpty()) s.sink.send(text)
  // GMCP follows the text it goes with.
  for ((pkg, data) in s.gmcpOut) s.sink.gmcp(pkg, data)
  s.gmcpOut.clear()
  if (text.isNotEmpty()) s.player?.let { s.sink.prompt(promptFor(it)) }
}

/** The prompt shown after each burst of output: the world's template with [p]'s vitals in it. */
fun World.promptFor(p: Player): String =
  PROMPT_PLACEHOLDER.replace(schema.promptTemplate) { m ->
    val name = m.groupValues[1]
    val vital = name.removePrefix("max_")
    when {
      name in schema.vitalNames -> p.current(name).toString()
      name.startsWith("max_") && vital in schema.vitalNames -> p.max(vital).toString()
      else -> m.value
    }
  }

/** The `{vital}` and `{max_vital}` placeholders of a prompt template. */
val PROMPT_PLACEHOLDER = Regex("\\{([a-z][a-z0-9_]*)}")

/** Queues [text] for a session that may have no character yet, such as during login. */
fun World.sendSession(s: Session, text: String) {
  s.out.append(text).append('\n')
}

/** Ends [s] from the world side, without making a character link-dead. */
fun World.closeSession(s: Session, reason: String) {
  flush(s)
  s.player?.let { p -> if (p.session == s.id) p.session = null }
  s.player = null
  sessions.remove(s.id)
  login?.disconnected(this, s.id)
  s.sink.close(reason)
}

/**
 * Reloads packages through the script host, then tells builders and instructors how it went. A
 * failed reload keeps the old version live.
 */
internal fun World.reload(packages: List<String>) {
  val result =
    try {
      scripts.reload(this, packages)
    } catch (e: InvariantViolation) {
      throw e
    } catch (e: Exception) {
      if (config.strict) throw e
      ReloadResult(false, listOf("reload of ${packages.joinToString()} crashed: $e"))
    }
  if (!result.ok) record(Diagnostic(pulse, "reload", result.messages.joinToString("\n")))
  // A successful reload ends with a checkpoint.
  if (result.ok) checkpointNow("reload")
  for (p in players) if (p.role.atLeast(Role.BUILDER)) result.messages.forEach { send(p, it) }
  flushOutput()
}

/** Phase 7: text, then GMCP events and state deltas. The harness also flushes after input. */
fun World.flushOutput() {
  for (s in sessions.values.toList()) {
    guarded("GMCP for ${s.id}") { gmcpDeltas(s) }
    flush(s)
  }
}

internal fun String.cap(): String = replaceFirstChar { it.uppercase() }
