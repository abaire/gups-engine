package gups.core

/** One line of player input, as it reaches the world (SPEC §2.5, §4.11). */
data class ClientInputEvent(val session: SessionId, val line: String, val receivedNanos: Long)

/** Non-input events posted to the world (SPEC §4.11). */
sealed interface WorldControlEvent {
    /** [remote] is the peer address, for example `127.0.0.1`; [transport] is `telnet`, `tls` or `ws`. */
    data class Connect(
        val session: SessionId,
        val sink: OutputSink,
        val remote: String = "local",
        val transport: String = "local",
    ) : WorldControlEvent
    data class Disconnect(val session: SessionId) : WorldControlEvent
    /** Re-evaluates and swaps in these packages, by path such as `zones/forest` (D-079). */
    data class Reload(val packages: List<String>) : WorldControlEvent
    data class Deploy(val commit: String) : WorldControlEvent
    data class Shutdown(val delayPulses: Long, val reason: String) : WorldControlEvent

    /**
     * Slow login work for [session], such as a password hash, finished off the world thread
     * (M6 design §2.2). The world hands [outcome] back to the `LoginHandler` that started it.
     */
    data class AuthResult(val session: SessionId, val outcome: Any) : WorldControlEvent

    /** The rest of some slow work, such as an account change, to run on the world thread (D-142). */
    class Task(val run: (gups.core.world.World) -> Unit) : WorldControlEvent
}

/** Where listeners deliver input and connection events; thread-safe. In practice a `WorldLoop`. */
interface WorldInbox {
    fun submit(event: ClientInputEvent)

    fun submit(event: WorldControlEvent)
}

/**
 * Per-session output (SPEC §4.11). Implementations must never block the world thread.
 * GMCP output is added with the GMCP packages in M6.
 */
interface OutputSink {
    fun send(text: String)

    /**
     * Sends a prompt after a burst of output. Telnet ends it with EOR or GA so clients can tell
     * it apart from text; headless sinks ignore prompts (D-083).
     */
    fun prompt(text: String) = Unit

    /**
     * Asks the client to stop echoing typed input, or to start again, around a password prompt
     * (M6 design §3.4). Telnet sends `IAC WILL ECHO` / `IAC WONT ECHO`; headless sinks ignore it.
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
    val transcript: String get() = all.toString()
}
