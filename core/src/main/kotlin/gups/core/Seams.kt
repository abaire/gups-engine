package gups.core

import gups.core.world.gmcpWithin

/** Input from a connected client session, in the order it arrived. */
sealed interface ClientEvent {
  val session: SessionId
  val receivedNanos: Long
}

/** A line of raw text input received from a connected client session. */
data class ClientInputEvent(
  /** Identifier of the sending client session. */
  override val session: SessionId,
  /** Raw text line submitted by the client (UTF-8, capped at 512 bytes). */
  val line: String,
  /** Monotonic nanosecond timestamp when the input was received by the network listener. */
  override val receivedNanos: Long,
) : ClientEvent

/** A GMCP message from a client, for a package other than `Core.*`. */
data class ClientGmcpEvent(
  override val session: SessionId,
  /** The package as the client spelled it. */
  val pkg: String,
  /** The body as plain values; null when [malformed]. */
  val data: Any?,
  /** True when the body was too large, too deep or not JSON. */
  val malformed: Boolean,
  override val receivedNanos: Long,
) : ClientEvent

/**
 * Non-input control events posted from listeners or background tasks to the world simulation loop.
 */
sealed interface WorldControlEvent {
  /** Signals a newly connected client session. */
  data class Connect(
    /** Identifier assigned to the new session. */
    val session: SessionId,
    /** Output sink used to deliver messages back to this client. */
    val sink: OutputSink,
    /** Peer network address (e.g. `127.0.0.1` or `local`). */
    val remote: String = "local",
    /** Network protocol transport name (`telnet`, `tls`, or `ws`). */
    val transport: String = "local",
    /** Account ID if pre-authenticated via session token (e.g. WebSocket), or null. */
    val account: Long? = null,
  ) : WorldControlEvent

  /** Signals that a client session has disconnected. */
  data class Disconnect(
    /** Identifier of the disconnected session. */
    val session: SessionId
  ) : WorldControlEvent

  /** Requests hot-reloading and re-evaluation of content packages. */
  data class Reload(
    /** Content package paths to reload (e.g. `zones/forest`). */
    val packages: List<String>
  ) : WorldControlEvent

  /** Triggers a live deployment by checking out and applying a git commit. */
  data class Deploy(
    /** Git commit SHA or ref to deploy. */
    val commit: String
  ) : WorldControlEvent

  /** Schedules a graceful server shutdown. */
  data class Shutdown(
    /** Pulses remaining until the simulation stops. */
    val delayPulses: Long,
    /** Narrative reason displayed to players and logged. */
    val reason: String,
  ) : WorldControlEvent

  /** Asynchronous authentication or background task result delivered to the world loop. */
  data class AuthResult(
    /** Identifier of the session for which authentication was performed. */
    val session: SessionId,
    /** Outcome payload returned to the active login handler. */
    val outcome: Any,
  ) : WorldControlEvent

  /**
   * Arbitrary deferred continuation task to run safely on the authoritative world simulation
   * thread.
   */
  class Task(
    /** Work unit to execute against the live world instance. */
    val run: (gups.core.world.World) -> Unit
  ) : WorldControlEvent
}

/**
 * Thread-safe incoming event queue interface connecting external listeners to the world simulation
 * loop.
 */
interface WorldInbox {
  /** Submits a client command line into the simulation event queue. */
  fun submit(event: ClientInputEvent)

  /** Submits one inbound GMCP message; inboxes that predate it drop them. */
  fun submit(event: ClientGmcpEvent) = Unit

  /** Submits a lifecycle or control event into the simulation event queue. */
  fun submit(event: WorldControlEvent)
}

/**
 * Output sink abstraction for delivering text, GMCP packets, and session control commands to a
 * client. Implementations must buffer asynchronously and never block the world simulation thread.
 */
interface OutputSink {
  /** Transmits text output to the client. */
  fun send(text: String)

  /**
   * Transmits a structured GMCP message packet. [data] is a plain primitive, list, map, or null;
   * encoded by the listener.
   */
  fun gmcp(pkg: String, data: Any?) = Unit

  /** True if the client negotiated support for GMCP package [pkg] via `Core.Supports`. */
  fun wantsGmcp(pkg: String): Boolean = false

  /**
   * Sends a prompt after a burst of output. Telnet listeners terminate prompts with IAC EOR or GA;
   * headless sinks ignore prompts.
   */
  fun prompt(text: String) = Unit

  /**
   * Instructs the client terminal to hide or show typed input echo (e.g. around password prompts).
   */
  fun hideInput(hidden: Boolean) = Unit

  /** Terminates the client connection with a diagnostic [reason]. */
  fun close(reason: String)
}

/**
 * An [OutputSink] that records all delivered output in memory, used for tests and headless worlds.
 */
class CapturingSink(
  /** GMCP packages this sink supports, as a client's `Core.Supports` would list them. */
  private val supports: Set<String> = emptySet()
) : OutputSink {
  private val buffer = StringBuilder()
  private val all = StringBuilder()
  private val gmcpMessages = mutableListOf<Pair<String, Any?>>()

  /** Reason provided when the session was closed, or null while still open. */
  var closedReason: String? = null
    private set

  override fun send(text: String) {
    buffer.append(text)
    all.append(text)
  }

  override fun gmcp(pkg: String, data: Any?) {
    gmcpMessages += pkg to data
  }

  override fun wantsGmcp(pkg: String): Boolean = supports.any { gmcpWithin(pkg, it) }

  override fun close(reason: String) {
    closedReason = reason
  }

  /** Drains and returns all buffered output captured since the previous drain call. */
  fun drain(): String = buffer.toString().also { buffer.setLength(0) }

  /** Complete historical transcript of all text sent to this sink. */
  val transcript: String
    get() = all.toString()

  /** Drains and returns the GMCP messages received since the previous call. */
  fun drainGmcp(): List<Pair<String, Any?>> = gmcpMessages.toList().also { gmcpMessages.clear() }
}
