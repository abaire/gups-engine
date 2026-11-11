package gups.net

import gups.core.ClientGmcpEvent
import gups.core.ClientInputEvent
import gups.core.WorldControlEvent
import gups.core.WorldInbox
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TelnetServerTest {
  private fun raw(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }

  private class Inbox : WorldInbox {
    val events = LinkedBlockingQueue<Any>()

    override fun submit(event: ClientInputEvent) {
      events += event
    }

    override fun submit(event: ClientGmcpEvent) {
      events += event
    }

    override fun submit(event: WorldControlEvent) {
      events += event
    }

    fun next(): Any = assertNotNull(events.poll(5, TimeUnit.SECONDS), "no event arrived")
  }

  /** Reads until [marker] shows up, or fails after a few seconds. */
  private fun Socket.readUntil(marker: String): String {
    soTimeout = 200
    val out = StringBuilder()
    val buf = ByteArray(65536)
    val deadline = System.nanoTime() + 8_000_000_000
    while (marker !in out) {
      check(System.nanoTime() < deadline) { "never saw '$marker' in:\n$out" }
      try {
        val n = getInputStream().read(buf)
        if (n < 0) break
        out.append(String(buf, 0, n, Charsets.ISO_8859_1))
      } catch (_: java.net.SocketTimeoutException) {}
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
        val got = s.readUntil("Hello\r\nthere> ")
        assertContains(got, "cut short")
        connect.sink.close("Goodbye.")
        assertContains(s.readUntil("Goodbye."), "Goodbye.")
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

  @Test
  fun `non-Core GMCP is submitted to the inbox in order with lines`() {
    val inbox = Inbox()
    val server = TelnetServer(inbox, port = 0)
    val port = runBlocking { server.start() }
    try {
      Socket("127.0.0.1", port).use { s ->
        assertIs<WorldControlEvent.Connect>(inbox.next())
        s.getOutputStream().write(raw(255, 253, 201))
        s.getOutputStream().flush()
        s.getOutputStream().write("look\r\n".toByteArray())
        s.getOutputStream().flush()
        s.getOutputStream()
          .write(raw(255, 250, 201) + "Shop.Buy {\"item\":\"sword\"}".toByteArray() + raw(255, 240))
        s.getOutputStream().flush()
        assertEquals("look", assertIs<ClientInputEvent>(inbox.next()).line)
        val gmcpEvent = assertIs<ClientGmcpEvent>(inbox.next())
        assertEquals("Shop.Buy", gmcpEvent.pkg)
        assertEquals(mapOf("item" to "sword"), gmcpEvent.data)
        assertFalse(gmcpEvent.malformed)
      }
    } finally {
      server.stop()
    }
  }

  @Test
  fun `a malformed GMCP body arrives marked malformed and the connection stays open`() {
    val inbox = Inbox()
    val server = TelnetServer(inbox, port = 0)
    val port = runBlocking { server.start() }
    try {
      Socket("127.0.0.1", port).use { s ->
        val connect = assertIs<WorldControlEvent.Connect>(inbox.next())
        s.getOutputStream().write(raw(255, 253, 201))
        s.getOutputStream().flush()
        s.getOutputStream()
          .write(raw(255, 250, 201) + "Shop.Buy {bad".toByteArray() + raw(255, 240))
        s.getOutputStream().flush()
        val gmcpEvent = assertIs<ClientGmcpEvent>(inbox.next())
        assertTrue(gmcpEvent.malformed)
        assertNull(gmcpEvent.data)
        connect.sink.send("still here")
        assertContains(s.readUntil("still here"), "still here")
      }
    } finally {
      server.stop()
    }
  }
}
