package gups.core

/** One line of player input, as it reaches the world (SPEC §2.5, §4.11). */
data class ClientInputEvent(val session: SessionId, val line: String, val receivedNanos: Long)

/** Non-input events posted to the world (SPEC §4.11). */
sealed interface WorldControlEvent {
    data class Connect(val session: SessionId, val sink: OutputSink) : WorldControlEvent
    data class Disconnect(val session: SessionId) : WorldControlEvent
    data class Reload(val labels: List<Label>) : WorldControlEvent
    data class Deploy(val commit: String) : WorldControlEvent
    data class Shutdown(val delayPulses: Long, val reason: String) : WorldControlEvent
}

/**
 * Per-session output (SPEC §4.11). Implementations must never block the world thread.
 * GMCP output is added with the GMCP packages in M6.
 */
interface OutputSink {
    fun send(text: String)

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
