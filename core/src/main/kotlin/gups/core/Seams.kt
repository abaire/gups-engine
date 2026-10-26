package gups.core

/** One line of player input, as it reaches the world. */
data class ClientInputEvent(val session: SessionId, val line: String, val receivedNanos: Long)

/** Non-input events posted to the world. */
sealed interface WorldControlEvent {
  /**
   * [remote] is the peer address, for example `127.0.0.1`; [transport] is `telnet`, `tls` or `ws`.
   * A web socket that authenticated with a session token carries its [account], so the login starts
   * at character selection.
   */
  data class Connect(
    val session: SessionId,
    val sink: OutputSink,
    val remote: String = "local",
    val transport: String = "local",
    val account: Long? = null,
  ) : WorldControlEvent

  data class Disconnect(val session: SessionId) : WorldControlEvent

  /** Re-evaluates and swaps in these packages, by path such as `zones/forest`. */
  data class Reload(val packages: List<String>) : WorldControlEvent

  data class Deploy(val commit: String) : WorldControlEvent

  data class Shutdown(val delayPulses: Long, val reason: String) : WorldControlEvent

  /**
   * Slow login work for [session], such as a password hash, finished off the world thread. The
   * world hands [outcome] back to the `LoginHandler` that started it.
   */
  data class AuthResult(val session: SessionId, val outcome: Any) : WorldControlEvent

  /** The rest of some slow work, such as an account change, to run on the world thread. */
  class Task(val run: (gups.core.world.World) -> Unit) : WorldControlEvent
}

/** Where listeners deliver input and connection events; thread-safe. In practice a `WorldLoop`. */
interface WorldInbox {
  fun submit(event: ClientInputEvent)

  fun submit(event: WorldControlEvent)
}

/** Per-session output. Implementations must never block the world thread. */
interface OutputSink {
  fun send(text: String)

  /**
   * Sends one GMCP message. [data] is a plain value: `String`, a number, `Boolean`, `List`,
   * `Map<String, *>` or null; the listener encodes it.
   */
  fun gmcp(pkg: String, data: Any?) = Unit

  /** True if the client asked for GMCP package [pkg] with `Core.Supports`. */
  fun wantsGmcp(pkg: String): Boolean = false

  /**
   * Sends a prompt after a burst of output. Telnet ends it with EOR or GA so clients can tell it
   * apart from text; headless sinks ignore prompts.
   */
  fun prompt(text: String) = Unit

  /**
   * Asks the client to stop echoing typed input, or to start again, around a password prompt.
   * Telnet sends `IAC WILL ECHO` / `IAC WONT ECHO`; headless sinks ignore it.
   */
  fun hideInput(hidden: Boolean) = Unit

  /** Ends the session, for example after `quit` or a rate-limit disconnect. */
  fun close(reason: String)
}

/** An [OutputSink] that records everything, for headless worlds and tests. */
class CapturingSink : OutputSink {
  private val buffer = StringBuilder()
  private val all = StringBuilder()

  /** Why the world closed this session, or null while it is open. */
  var closedReason: String? = null
    private set

  override fun send(text: String) {
    buffer.append(text)
    all.append(text)
  }

  override fun close(reason: String) {
    closedReason = reason
  }

  /** Returns the output received since the last drain. */
  fun drain(): String = buffer.toString().also { buffer.setLength(0) }

  /** Everything ever received. */
  val transcript: String
    get() = all.toString()
}
