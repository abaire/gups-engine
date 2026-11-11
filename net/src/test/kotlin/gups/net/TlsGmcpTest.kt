package gups.net

import gups.core.ClientInputEvent
import gups.core.WorldControlEvent
import gups.core.WorldInbox
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private const val IAC = 255
private const val SB = 250
private const val SE = 240

private fun bytes(vararg parts: Any): ByteArray =
  parts
    .flatMap { p ->
      when (p) {
        is Int -> listOf(p.toByte())
        is String -> p.toByteArray().toList()
        else -> error("bad part $p")
      }
    }
    .toByteArray()

private fun gmcpBytes(message: String) = bytes(IAC, SB, 201, message, IAC, SE)

class GmcpTest {
  private fun conn() =
    TelnetConnection {}
      .also { c ->
        c.start()
        c.receive(bytes(IAC, 253, 201)) { _, _ -> }
      }

  @Test
  fun `Core Supports decides which packages a client gets`() {
    val c = conn()
    assertFalse(c.wantsGmcp("Char.Vitals"), "no Core.Supports, no GMCP")
    c.receive(gmcpBytes("Core.Hello {\"client\":\"Mudlet\",\"version\":\"4.17\"}")) { _, _ -> }
    assertEquals("Mudlet 4.17", c.gmcpClient)
    c.receive(gmcpBytes("Core.Supports.Set [\"Char 1\", \"Room.Info 1\"]")) { _, _ -> }
    assertTrue(
      c.wantsGmcp("Char.Vitals") && c.wantsGmcp("Char.Affects") && c.wantsGmcp("Room.Info")
    )
    assertFalse(c.wantsGmcp("Room.Bands"))
    c.receive(gmcpBytes("Core.Supports.Add [\"Room 1\", \"Comm.Channel 1\"]")) { _, _ -> }
    assertTrue(c.wantsGmcp("Room.Bands") && c.wantsGmcp("Comm.Channel.Text"))
    c.receive(gmcpBytes("Core.Supports.Remove [\"Char\"]")) { _, _ -> }
    assertFalse(c.wantsGmcp("Char.Vitals"))
    c.receive(gmcpBytes("Char.Login {\"name\":\"x\"}")) { _, _ -> }
    assertEquals(0, c.errors, "other packages are ignored")
    c.receive(gmcpBytes("Core.Supports.Set [broken")) { _, _ -> }
    assertEquals(1, c.errors)
    c.receive(bytes(IAC, 254, 201)) { _, _ -> }
    assertFalse(c.wantsGmcp("Room.Bands"), "DONT GMCP turns it off")
  }

  @Test
  fun `messages are encoded as JSON with IAC doubled`() {
    val c = conn()
    assertContentEquals(
      gmcpBytes(
        "Char.Vitals {\"hp\":5,\"name\":\"Ann\",\"casting\":null,\"fighting\":[\"a rat\"],\"ok\":true}"
      ),
      c.encodeGmcp(
        "Char.Vitals",
        linkedMapOf(
          "hp" to 5,
          "name" to "Ann",
          "casting" to null,
          "fighting" to listOf("a rat"),
          "ok" to true,
        ),
      ),
    )
    val withIac = c.encodeGmcp("Comm.Channel.Text", mapOf("text" to "ÿ"))
    val body = "Comm.Channel.Text {\"text\":\"ÿ\"}".toByteArray(Charsets.UTF_8)
    assertEquals(body.size + 5 + body.count { it.toInt() and 0xff == IAC }, withIac.size)
  }
}

class TlsTest {
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

  private val trustAll =
    arrayOf<TrustManager>(
      object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
      }
    )

  private fun client(port: Int, protocol: String = "TLSv1.3"): SSLSocket {
    val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
    return (ctx.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).apply {
      enabledProtocols = arrayOf(protocol)
      soTimeout = 200
      startHandshake()
    }
  }

  /**
   * Reads exactly [count] bytes, waiting up to several seconds for slow arrivals. Anything the
   * server sends beyond what a test expects stays queued and surfaces in the next read.
   */
  private fun SSLSocket.read(count: Int): List<Int> {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(count)
    val deadline = System.nanoTime() + 8_000_000_000
    while (out.size() < count) {
      check(System.nanoTime() < deadline) {
        "wanted $count bytes, got ${out.toByteArray().toList()}"
      }
      try {
        val n = inputStream.read(buf, 0, count - out.size())
        check(n >= 0) { "connection closed after ${out.toByteArray().toList()}" }
        out.write(buf, 0, n)
      } catch (_: java.net.SocketTimeoutException) {}
    }
    return out.toByteArray().map { it.toInt() and 0xff }
  }

  private fun SSLSocket.expect(expected: List<Int>, message: String? = null) {
    assertEquals(expected, read(expected.size), message)
  }

  private fun SSLSocket.send(vararg parts: Any) {
    outputStream.write(bytes(*parts))
    outputStream.flush()
  }

  private fun serve(tls: ServerTls, block: (Inbox, Int) -> Unit) {
    val inbox = Inbox()
    val server = TelnetServer(inbox, port = 0, tls = tls)
    val port = runBlocking { server.start() }
    try {
      block(inbox, port)
    } finally {
      server.stop()
    }
  }

  private val opening =
    listOf(IAC, 253, 24, IAC, 253, 31, IAC, 251, 25, IAC, 251, 201, IAC, 251, 42)

  @Test
  fun `a Mudlet session over TLS negotiates and gets GMCP`() {
    serve(ServerTls.selfSigned(Files.createTempDirectory("tls"))) { inbox, port ->
      client(port).use { s ->
        val connect = assertIs<WorldControlEvent.Connect>(inbox.next())
        assertEquals("tls", connect.transport)
        s.expect(opening)
        // Mudlet: WILL TTYPE, WILL NAWS, DO EOR, DO GMCP, DO CHARSET, its window size, Core.Hello
        // and Core.Supports.Set.
        s.send(
          IAC,
          251,
          24,
          IAC,
          251,
          31,
          IAC,
          253,
          25,
          IAC,
          253,
          201,
          IAC,
          253,
          42,
          IAC,
          SB,
          31,
          0,
          120,
          0,
          40,
          IAC,
          SE,
        )
        s.send(
          *gmcpBytes("Core.Hello {\"client\":\"Mudlet\",\"version\":\"4.17.2\"}")
            .map { it.toInt() and 0xff }
            .toTypedArray()
        )
        s.send(
          *gmcpBytes("Core.Supports.Set [\"Char 1\",\"Room 1\",\"Comm.Channel 1\"]")
            .map { it.toInt() and 0xff }
            .toTypedArray()
        )
        s.expect(
          listOf(IAC, SB, 24, 1, IAC, SE) +
            listOf(IAC, SB, 42, 1) +
            ";UTF-8".map { it.code } +
            listOf(IAC, SE)
        )
        s.send(IAC, SB, 24, 0, "MUDLET", IAC, SE)
        s.expect(listOf(IAC, SB, 24, 1, IAC, SE), "asks again for MTTS")
        assertTrue(connect.sink.wantsGmcp("Char.Vitals"))
        assertFalse(connect.sink.wantsGmcp("Gups.Trace"))
        connect.sink.gmcp("Char.Vitals", mapOf("hp" to 20))
        connect.sink.gmcp("Gups.Trace", mapOf("line" to "not supported, not sent"))
        connect.sink.prompt("> ")
        s.expect(
          gmcpBytes("Char.Vitals {\"hp\":20}").map { it.toInt() and 0xff } +
            listOf('>'.code, ' '.code, IAC, 239)
        )
        s.send("look\r\n")
        assertEquals("look", assertIs<ClientInputEvent>(inbox.next()).line)
      }
      assertIs<WorldControlEvent.Disconnect>(inbox.next())
    }
  }

  @Test
  fun `a TinTin++ session over TLS 1-2 without Core Supports gets no GMCP`() {
    serve(ServerTls.selfSigned(Files.createTempDirectory("tls"))) { inbox, port ->
      client(port, "TLSv1.2").use { s ->
        val connect = assertIs<WorldControlEvent.Connect>(inbox.next())
        s.expect(opening)
        // TinTin++: WILL TTYPE, WILL NAWS, DONT EOR, DO GMCP, DONT CHARSET.
        s.send(IAC, 251, 24, IAC, 251, 31, IAC, 254, 25, IAC, 253, 201, IAC, 254, 42)
        s.expect(listOf(IAC, SB, 24, 1, IAC, SE))
        s.send(
          IAC,
          SB,
          24,
          0,
          "TINTIN++",
          IAC,
          SE,
          IAC,
          SB,
          24,
          0,
          "XTERM-256COLOR",
          IAC,
          SE,
          IAC,
          SB,
          24,
          0,
          "MTTS 137",
          IAC,
          SE,
        )
        s.expect(listOf(IAC, SB, 24, 1, IAC, SE, IAC, SB, 24, 1, IAC, SE))
        assertFalse(connect.sink.wantsGmcp("Char.Vitals"))
        connect.sink.gmcp("Char.Vitals", mapOf("hp" to 20))
        connect.sink.prompt("> ")
        s.expect(listOf('>'.code, ' '.code, IAC, 249), "no GMCP; GA without EOR")
      }
    }
  }

  @Test
  fun `old protocols are refused and renewed certificates are picked up`() {
    val dir = Files.createTempDirectory("tls")
    val tls = ServerTls.selfSigned(dir)
    serve(tls) { _, port ->
      assertFailsWith<javax.net.ssl.SSLException> { client(port, "TLSv1.1").close() }
      val first =
        client(port).use { (it.session.peerCertificates[0] as X509Certificate).serialNumber }
      assertFalse(tls.reloadIfChanged(), "unchanged files")
      ServerTls.selfSigned(dir)
      val later = FileTime.from(Instant.now().plusSeconds(5))
      Files.setLastModifiedTime(tls.cert, later)
      Files.setLastModifiedTime(tls.key, later)
      assertTrue(tls.reloadIfChanged())
      val second =
        client(port).use { (it.session.peerCertificates[0] as X509Certificate).serialNumber }
      assertNotEquals(first, second)

      Files.writeString(tls.key, "not a key")
      Files.setLastModifiedTime(tls.key, FileTime.from(Instant.now().plusSeconds(10)))
      assertFailsWith<Exception> { tls.reloadIfChanged() }
      val kept =
        client(port).use { (it.session.peerCertificates[0] as X509Certificate).serialNumber }
      assertEquals(second, kept, "a bad pair keeps the old certificate")
    }
  }
}
