package gups.net

import gups.core.ClientInputEvent
import gups.core.WorldControlEvent
import gups.core.WorldInbox
import kotlinx.coroutines.runBlocking
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class TelnetServerTest {
    private class Inbox : WorldInbox {
        val events = LinkedBlockingQueue<Any>()

        override fun submit(event: ClientInputEvent) {
            events += event
        }

        override fun submit(event: WorldControlEvent) {
            events += event
        }

        fun next(): Any = assertNotNull(events.poll(5, TimeUnit.SECONDS), "no event arrived")
    }

    private fun Socket.readAll(): String {
        soTimeout = 300
        val out = StringBuilder()
        val buf = ByteArray(65536)
        try {
            while (true) {
                val n = getInputStream().read(buf)
                if (n < 0) break
                out.append(String(buf, 0, n, Charsets.ISO_8859_1))
            }
        } catch (_: java.net.SocketTimeoutException) {
        }
        return out.toString()
    }

    @Test
    fun `lines reach the world and output reaches the client`() {
        val inbox = Inbox()
        val server = TelnetServer(inbox, port = 0)
        val port = runBlocking { server.start() }
        try {
            Socket("127.0.0.1", port).use { s ->
                val connect = assertIs<WorldControlEvent.Connect>(inbox.next())
                assertEquals("127.0.0.1", connect.remote)
                assertEquals("telnet", connect.transport)
                s.getOutputStream().write("look\r\n${"y".repeat(600)}\r\n".toByteArray())
                assertEquals("look", assertIs<ClientInputEvent>(inbox.next()).line)
                assertEquals(MAX_LINE_BYTES, assertIs<ClientInputEvent>(inbox.next()).line.length)
                connect.sink.send("Hello\nthere")
                connect.sink.prompt("> ")
                val got = s.readAll()
                assertContains(got, "cut short")
                assertContains(got, "Hello\r\nthere> ")
                connect.sink.close("Goodbye.")
                assertContains(s.readAll(), "Goodbye.")
                assertIs<WorldControlEvent.Disconnect>(inbox.next())
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a client that stops reading is dropped as too slow`() {
        val inbox = Inbox()
        val server = TelnetServer(inbox, port = 0)
        val port = runBlocking { server.start() }
        try {
            Socket("127.0.0.1", port).use { s ->
                s.receiveBufferSize = 4096
                val connect = assertIs<WorldControlEvent.Connect>(inbox.next())
                val chunk = "z".repeat(64 * 1024)
                repeat(200) { connect.sink.send(chunk) }
                assertIs<WorldControlEvent.Disconnect>(inbox.next())
            }
        } finally {
            server.stop()
        }
    }
}
