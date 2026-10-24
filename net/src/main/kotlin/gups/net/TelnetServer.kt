package gups.net

import gups.core.ClientInputEvent
import gups.core.OutputSink
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.WorldInbox
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.toJavaAddress
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A client whose unsent output passes this many bytes is disconnected (SPEC §4.10). */
const val OUTPUT_BUFFER_MAX = 256 * 1024

/**
 * The raw telnet listener (SPEC §4.10): `--dev` binds it to `127.0.0.1:4000`. All socket I/O
 * runs on `Dispatchers.IO`; the world only ever sees [ClientInputEvent]s and per-session
 * [OutputSink]s that never block it (SPEC §2.5).
 */
class TelnetServer(
    private val inbox: WorldInbox,
    private val host: String = "127.0.0.1",
    private val port: Int = 4000,
    /** Shared with other listeners so session ids never collide. */
    private val sessionIds: AtomicLong = AtomicLong(1),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var selector: SelectorManager? = null
    private var server: ServerSocket? = null

    /** Binds and starts accepting. Returns the bound port (useful with port 0). */
    suspend fun start(): Int {
        val sel = SelectorManager(Dispatchers.IO)
        selector = sel
        val s = aSocket(sel).tcp().bind(host, port)
        server = s
        scope.launch {
            while (isActive) {
                val socket = try {
                    s.accept()
                } catch (_: Exception) {
                    break
                }
                launch { serve(socket) }
            }
        }
        return (s.localAddress as InetSocketAddress).port
    }

    fun stop() {
        runCatching { server?.close() }
        scope.cancel()
        runCatching { selector?.close() }
    }

    private suspend fun serve(socket: Socket) {
        val id = SessionId(sessionIds.getAndIncrement())
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = false)
        val outbox = Channel<ByteArray>(Channel.UNLIMITED)
        val buffered = AtomicLong()
        val closed = AtomicBoolean(false)
        val enqueue = { bytes: ByteArray ->
            if (!closed.get()) {
                if (buffered.addAndGet(bytes.size.toLong()) > OUTPUT_BUFFER_MAX) {
                    // A slow client: drop it; the character goes link-dead (SPEC §6.4).
                    closed.set(true)
                    outbox.close()
                    inbox.submit(WorldControlEvent.Disconnect(id))
                } else {
                    outbox.trySend(bytes)
                }
            }
        }
        val conn = TelnetConnection { enqueue(it) }
        val sink = object : OutputSink {
            override fun send(text: String) = enqueue(conn.encodeText(text))

            override fun prompt(text: String) = enqueue(conn.encodePrompt(text))

            override fun hideInput(hidden: Boolean) {
                val bytes = conn.encodeEcho(hidden)
                if (bytes.isNotEmpty()) enqueue(bytes)
            }

            override fun close(reason: String) {
                enqueue(conn.encodeText(reason + "\n"))
                if (closed.compareAndSet(false, true)) outbox.close()
            }
        }
        val writer: Job = scope.launch {
            try {
                for (first in outbox) {
                    // Write everything queued so far, then flush once.
                    var bytes: ByteArray? = first
                    while (bytes != null) {
                        output.writeFully(bytes)
                        buffered.addAndGet(-bytes.size.toLong())
                        bytes = outbox.tryReceive().getOrNull()
                    }
                    output.flush()
                }
            } catch (_: Exception) {
                // The peer went away; the reader notices too.
            } finally {
                runCatching { socket.close() }
            }
        }
        conn.start()
        val remote = (socket.remoteAddress.toJavaAddress() as? java.net.InetSocketAddress)?.address?.hostAddress ?: "unknown"
        inbox.submit(WorldControlEvent.Connect(id, sink, remote, "telnet"))
        val buf = ByteArray(4096)
        try {
            while (!closed.get()) {
                val n = input.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                conn.receive(buf.copyOf(n)) { line, truncated ->
                    if (truncated) sink.send("Your line was longer than $MAX_LINE_BYTES bytes and was cut short.\n")
                    inbox.submit(ClientInputEvent(id, line, System.nanoTime()))
                }
                if (conn.errors >= MAX_PROTOCOL_ERRORS) {
                    sink.close("Too many malformed telnet sequences.")
                    break
                }
            }
        } catch (_: Exception) {
            // Connection reset and the like: treated as a dropped link.
        } finally {
            inbox.submit(WorldControlEvent.Disconnect(id))
            if (closed.compareAndSet(false, true)) outbox.close()
            writer.join()
        }
    }
}
