package gups.net

import gups.core.ClientGmcpEvent
import gups.core.ClientInputEvent
import gups.core.WebAccounts
import gups.core.WebLogin
import gups.core.WorldControlEvent
import gups.core.WorldInbox
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.security.cert.X509Certificate
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Waits for the server to process a Core.Supports frame, which is handled asynchronously. */
private fun awaitSupported(sink: gups.core.OutputSink, pkg: String) {
  val deadline = System.nanoTime() + 8_000_000_000
  while (!sink.wantsGmcp(pkg)) {
    check(System.nanoTime() < deadline) { "server never registered support for $pkg" }
    Thread.sleep(10)
  }
}

class WebServerTest {
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

  /** One account, `ann`/`secret`, whose tokens can be revoked. */
  private class Accounts : WebAccounts {
    val tokens = HashMap<String, Long>()
    var issued = 0

    @Synchronized
    override fun login(login: String, password: String, remote: String): WebLogin =
      when {
        password == "slow down" -> WebLogin.Refused(429, "Too many failed logins.")
        login != "ann" || password != "secret" -> WebLogin.Refused(401, "Wrong login or password.")
        else ->
          WebLogin.Ok("token${++issued}", System.currentTimeMillis() + 60_000, 7).also {
            tokens[it.token] = 7
          }
      }

    @Synchronized
    override fun checkCode(code: String, remote: String): gups.core.CodeCheck =
      when (code) {
        "SETUP" -> gups.core.CodeCheck.Valid("ann")
        "INVITE" -> gups.core.CodeCheck.Valid(null)
        else -> gups.core.CodeCheck.Refused(404, "That code isn't valid, or it has expired.")
      }

    @Synchronized
    override fun signup(code: String, login: String?, password: String, remote: String): WebLogin =
      when {
        code != "INVITE" -> WebLogin.Refused(404, "That code isn't valid, or it has expired.")
        login == "taken" -> WebLogin.Refused(409, "That login is taken.")
        else ->
          WebLogin.Ok("signup${++issued}", System.currentTimeMillis() + 60_000, 8).also {
            tokens[it.token] = 8
          }
      }

    @Synchronized
    override fun logout(token: String) {
      tokens.remove(token)
    }

    @Synchronized override fun accountFor(token: String): Long? = tokens[token]
  }

  /** A WebSocket client that queues the text frames it gets. */
  private class Frames : WebSocket.Listener {
    val got = LinkedBlockingQueue<String>()
    val closed = LinkedBlockingQueue<Int>()
    private val partial = StringBuilder()

    override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
      partial.append(data)
      if (last) {
        got += partial.toString()
        partial.setLength(0)
      }
      ws.request(1)
      return null
    }

    override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
      closed += statusCode
      return null
    }

    fun next(): String = assertNotNull(got.poll(5, TimeUnit.SECONDS), "no frame arrived")

    /** Frames until one contains [marker]. */
    fun until(marker: String): List<String> {
      val seen = mutableListOf<String>()
      while (seen.none { marker in it }) seen += next()
      return seen
    }
  }

  private val assets =
    WebAssets(
      mapOf(
        "index.html" to "<script src=\"%ASSETS%/app.js\"></script>".toByteArray(),
        "app.js" to "console.log(1)".toByteArray(),
      )
    )

  private val trustAll: SSLContext =
    SSLContext.getInstance("TLS").apply {
      init(
        null,
        arrayOf<TrustManager>(
          object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
          }
        ),
        null,
      )
    }

  private fun serve(
    tls: ServerTls? = null,
    block: (Inbox, Accounts, WebServer, String, HttpClient) -> Unit,
  ) {
    val inbox = Inbox()
    val accounts = Accounts()
    val server = WebServer(inbox, accounts, assets, port = 0, tls = tls)
    val port = runBlocking { server.start() }
    val http = HttpClient.newBuilder().sslContext(trustAll).build()
    try {
      block(
        inbox,
        accounts,
        server,
        "${if (tls != null) "https" else "http"}://127.0.0.1:$port",
        http,
      )
    } finally {
      server.stop()
    }
  }

  private fun HttpClient.post(
    url: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
  ): HttpResponse<String> =
    send(
      HttpRequest.newBuilder(URI(url))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .apply { headers.forEach(::header) }
        .build(),
      HttpResponse.BodyHandlers.ofString(),
    )

  private fun HttpClient.get(url: String): HttpResponse<String> =
    send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofString())

  private fun HttpClient.socket(base: String, cookie: String? = null): Pair<WebSocket, Frames> {
    val frames = Frames()
    val ws =
      newWebSocketBuilder()
        .apply { cookie?.let { header("Cookie", "$SESSION_COOKIE=$it") } }
        .buildAsync(URI(base.replace("http", "ws") + "/ws"), frames)
        .get(5, TimeUnit.SECONDS)
    return ws to frames
  }

  @Test
  fun `the client is served with a content hash in every asset URL`() =
    serve { _, _, _, base, http ->
      val index = http.get("$base/")
      assertEquals(200, index.statusCode())
      assertEquals("no-cache", index.headers().firstValue("Cache-Control").get())
      assertEquals("<script src=\"/assets/${assets.hash}/app.js\"></script>", index.body())
      val js = http.get("$base/assets/${assets.hash}/app.js")
      assertEquals("console.log(1)", js.body())
      assertContains(js.headers().firstValue("Content-Type").get(), "javascript")
      assertContains(js.headers().firstValue("Cache-Control").get(), "immutable")
      assertEquals(
        404,
        http.get("$base/assets/0000/app.js").statusCode(),
        "an old hash isn't served",
      )
      assertEquals(404, http.get("$base/assets/${assets.hash}/index.html").statusCode())
      assertTrue(WebAssets(mapOf("index.html" to byteArrayOf(1))).hash != assets.hash)
    }

  @Test
  fun `logging in sets a strict cookie, and refusals keep their status`() =
    serve { _, _, _, base, http ->
      val ok = http.post("$base/api/login", """{"login":"ann","password":"secret"}""")
      assertEquals(200, ok.statusCode())
      assertContains(ok.body(), "\"token\":\"token1\"")
      assertContains(ok.body(), "\"expires_at\":")
      val cookie = ok.headers().firstValue("Set-Cookie").get()
      for (part in
        listOf(
          "$SESSION_COOKIE=token1",
          "HttpOnly",
          "Secure",
          "SameSite=Strict",
          "Path=/",
        )) assertContains(cookie, part)
      val wrong = http.post("$base/api/login", """{"login":"ann","password":"nope"}""")
      assertEquals(
        401 to """{"error":"Wrong login or password."}""",
        wrong.statusCode() to wrong.body(),
      )
      assertEquals(
        429,
        http.post("$base/api/login", """{"login":"ann","password":"slow down"}""").statusCode(),
      )
      assertEquals(400, http.post("$base/api/login", "not json").statusCode())
    }

  @Test
  fun `signing up checks the code, then logs in like a login`() = serve { _, _, _, base, http ->
    val bound = http.post("$base/api/code", """{"code":"SETUP"}""")
    assertEquals(200 to """{"login":"ann"}""", bound.statusCode() to bound.body())
    assertEquals("""{"login":null}""", http.post("$base/api/code", """{"code":"INVITE"}""").body())
    assertEquals(404, http.post("$base/api/code", """{"code":"NOPE"}""").statusCode())
    assertEquals(400, http.post("$base/api/code", "{}").statusCode())
    val ok =
      http.post(
        "$base/api/signup",
        """{"code":"INVITE","login":"bea","password":"correct horse"}""",
      )
    assertEquals(200, ok.statusCode())
    assertContains(ok.body(), "\"token\":\"signup1\"")
    assertContains(ok.headers().firstValue("Set-Cookie").get(), "$SESSION_COOKIE=signup1")
    assertEquals(
      409,
      http
        .post(
          "$base/api/signup",
          """{"code":"INVITE","login":"taken","password":"correct horse"}""",
        )
        .statusCode(),
    )
    assertEquals(400, http.post("$base/api/signup", """{"code":"INVITE"}""").statusCode())
  }

  @Test
  fun `a socket authenticates by first frame, then carries input, text, prompts and GMCP`() =
    serve { inbox, _, _, base, http ->
      val (anon, anonFrames) = http.socket(base)
      anon.sendText("""{"t":"input","d":"look"}""", true)
      assertEquals(
        """{"t":"error","d":"Please log in first; your session may have expired."}""",
        anonFrames.next(),
      )
      assertNotNull(anonFrames.closed.poll(5, TimeUnit.SECONDS))

      http.post("$base/api/login", """{"login":"ann","password":"secret"}""")
      val (ws, frames) = http.socket(base)
      ws.sendText("""{"t":"auth","d":"token1"}""", true)
      val connect = assertIs<WorldControlEvent.Connect>(inbox.next())
      assertEquals("ws" to 7L, connect.transport to connect.account)
      ws.sendText("""{"t":"input","d":"say hi\u0007"}""", true)
      assertEquals("say hi", assertIs<ClientInputEvent>(inbox.next()).line)
      ws.sendText("""{"t":"input","d":"${"y".repeat(600)}"}""", true)
      assertEquals(MAX_LINE_BYTES, assertIs<ClientInputEvent>(inbox.next()).line.length)
      assertContains(frames.next(), "cut short")
      connect.sink.send("{red}Hello{/}")
      connect.sink.prompt("> ")
      connect.sink.hideInput(true)
      connect.sink.gmcp("Char.Vitals", mapOf("hp" to 1))
      assertEquals(
        listOf(
          """{"t":"text","d":"{red}Hello{/}"}""",
          """{"t":"prompt","d":"> "}""",
          """{"t":"hide","d":true}""",
        ),
        List(3) { frames.next() },
      )
      ws.sendText("""{"t":"gmcp","p":"Core.Supports.Set","d":["Char 1"]}""", true)
      awaitSupported(connect.sink, "Char.Vitals")
      connect.sink.gmcp("Room.Info", mapOf("name" to "x"))
      connect.sink.gmcp("Char.Vitals", mapOf("hp" to 2))
      assertEquals(
        """{"t":"gmcp","p":"Char.Vitals","d":{"hp":2}}""",
        frames.next(),
        "only supported packages, after Core.Supports",
      )
      connect.sink.close("Goodbye.")
      assertEquals("""{"t":"text","d":"Goodbye.\n"}""", frames.next())
      assertEquals(1000, frames.closed.poll(5, TimeUnit.SECONDS))
      assertIs<WorldControlEvent.Disconnect>(inbox.next())
    }

  @Test
  fun `a gmcp frame for another package is submitted`() = serve { inbox, _, _, base, http ->
    http.post("$base/api/login", """{"login":"ann","password":"secret"}""")
    val (ws, _) = http.socket(base, cookie = "token1")
    assertIs<WorldControlEvent.Connect>(inbox.next())
    ws.sendText("""{"t":"gmcp","p":"Shop.Buy","d":{"item":"sword"}}""", true)
    val gmcpEvent = assertIs<ClientGmcpEvent>(inbox.next())
    assertEquals("Shop.Buy", gmcpEvent.pkg)
    assertEquals(mapOf("item" to "sword"), gmcpEvent.data)
    assertFalse(gmcpEvent.malformed)
    ws.sendText("""{"t":"gmcp","p":"Shop.Buy","d":null}""", true)
    assertEquals(null, assertIs<ClientGmcpEvent>(inbox.next()).data)
  }

  @Test
  fun `Core Supports frames still update supports`() = serve { inbox, _, _, base, http ->
    http.post("$base/api/login", """{"login":"ann","password":"secret"}""")
    val (ws, frames) = http.socket(base, cookie = "token1")
    val connect = assertIs<WorldControlEvent.Connect>(inbox.next())
    ws.sendText("""{"t":"gmcp","p":"Core.Supports.Set","d":["Char"]}""", true)
    awaitSupported(connect.sink, "Char.Vitals")
    connect.sink.gmcp("Char.Vitals", mapOf("hp" to 1))
    assertEquals("""{"t":"gmcp","p":"Char.Vitals","d":{"hp":1}}""", frames.next())
  }

  @Test
  fun `revoked tokens and logout close live sockets`() =
    serve { inbox, accounts, server, base, http ->
      http.post("$base/api/login", """{"login":"ann","password":"secret"}""")
      http.post("$base/api/login", """{"login":"ann","password":"secret"}""")
      val (_, first) = http.socket(base, cookie = "token1")
      assertIs<WorldControlEvent.Connect>(inbox.next())
      val (_, second) = http.socket(base, cookie = "token2")
      assertIs<WorldControlEvent.Connect>(inbox.next())
      assertEquals(2, server.sockets)

      // A password change or @role revoke: the next check closes the socket.
      synchronized(accounts) { accounts.tokens.remove("token1") }
      runBlocking { server.checkTokens() }
      assertEquals(
        """{"t":"error","d":"Your session has ended. Please log in again."}""",
        first.next(),
      )
      assertNotNull(first.closed.poll(5, TimeUnit.SECONDS))
      assertIs<WorldControlEvent.Disconnect>(inbox.next())

      val out = http.post("$base/api/logout", "", mapOf("Cookie" to "$SESSION_COOKIE=token2"))
      assertEquals(200, out.statusCode())
      assertContains(out.headers().firstValue("Set-Cookie").get(), "Max-Age=0")
      assertEquals("""{"t":"error","d":"You have logged out."}""", second.next())
      assertIs<WorldControlEvent.Disconnect>(inbox.next())
      assertEquals(emptyMap(), synchronized(accounts) { accounts.tokens.toMap() })
    }

  @Test
  fun `HTTPS and WSS run through the shared certificate`() =
    serve(ServerTls.selfSigned(Files.createTempDirectory("tls"))) { inbox, _, _, base, http ->
      assertTrue(base.startsWith("https://"))
      assertEquals(200, http.get("$base/").statusCode())
      http.post("$base/api/login", """{"login":"ann","password":"secret"}""")
      val (ws, frames) = http.socket(base, cookie = "token1")
      assertIs<WorldControlEvent.Connect>(inbox.next()).sink.send("over TLS")
      assertEquals("""{"t":"text","d":"over TLS"}""", frames.next())
      ws.abort()
      assertIs<WorldControlEvent.Disconnect>(inbox.next())
    }
}
