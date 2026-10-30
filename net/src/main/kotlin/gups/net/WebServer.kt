package gups.net

import gups.core.ClientInputEvent
import gups.core.CodeCheck
import gups.core.OutputSink
import gups.core.SessionId
import gups.core.WebAccounts
import gups.core.WebLogin
import gups.core.WorldControlEvent
import gups.core.WorldInbox
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** The web session cookie. */
const val SESSION_COOKIE = "gups_session"

/** How long a socket without the cookie has to send its `auth` frame. */
private val AUTH_WAIT = 30.seconds

/** How often live sockets' tokens are checked for expiry and revocation. */
val TOKEN_CHECK_INTERVAL: Duration = Duration.ofMinutes(1)

/**
 * The web client's files, served unchanged: `index.html` at `/`, and every other file under
 * `/assets/<hash>/`, where the hash covers all of them, so a new engine version never mixes with
 * old cached files.
 */
class WebAssets(private val files: Map<String, ByteArray>) {
  val hash: String =
    MessageDigest.getInstance("SHA-256").let { md ->
      for ((name, bytes) in files.toSortedMap()) {
        md.update(name.toByteArray())
        md.update(bytes)
      }
      md.digest().take(8).joinToString("") { "%02x".format(it) }
    }

  /** `index.html` with `%ASSETS%` replaced by the asset prefix. */
  val index: String =
    String(files["index.html"] ?: "<!doctype html><p>No web client.".toByteArray())
      .replace("%ASSETS%", "/assets/$hash")

  operator fun get(name: String): ByteArray? = files[name]?.takeIf { name != "index.html" }

  companion object {
    /**
     * The files listed in `<root>files.txt`, read through [loader], as the `webclient` module
     * packages them.
     */
    fun fromClasspath(
      loader: ClassLoader = WebAssets::class.java.classLoader,
      root: String = "gups/webclient/",
    ): WebAssets {
      val list =
        loader.getResourceAsStream("${root}files.txt")?.use { String(it.readBytes()) }
          ?: return WebAssets(emptyMap())
      val names = list.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
      return WebAssets(
        names.associateWith { n ->
          requireNotNull(loader.getResourceAsStream(root + n)) { "missing web client file $n" }
            .use { it.readBytes() }
        }
      )
    }

    fun contentType(name: String): ContentType =
      when (name.substringAfterLast('.')) {
        "js" -> ContentType.Text.JavaScript
        "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
        "html" -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
        "json" -> ContentType.Application.Json.withCharset(Charsets.UTF_8)
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "ico" -> ContentType("image", "x-icon")
        else -> ContentType.Application.OctetStream
      }
  }
}

/**
 * The API reference's routes: `/docs` redirects to `/docs/` so the pages' relative links resolve,
 * `/docs/` is `index.html`, and `/docs/<file>` is one of [docs].
 */
fun Route.mountDocs(docs: Map<String, ByteArray>) {
  suspend fun RoutingCall.file(name: String) {
    val bytes = docs[name] ?: return respondText("Not found", status = HttpStatusCode.NotFound)
    response.header(HttpHeaders.CacheControl, "no-cache")
    respondBytes(bytes, WebAssets.contentType(name))
  }
  // Ktor may match /docs/ to the /docs route too, so the path tells them apart.
  get("/docs") {
    if (call.request.path().endsWith("/")) call.file("index.html")
    else call.respondRedirect("/docs/", permanent = false)
  }
  get("/docs/") { call.file("index.html") }
  get("/docs/{name}") { call.file(call.parameters["name"].orEmpty()) }
}

/** A plain-HTTP listener serving only the API reference, for `serve --dev`. */
class DocsServer(
  private val docs: Map<String, ByteArray>,
  private val host: String,
  private val port: Int,
) {
  private var server:
    EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? =
    null

  /** Binds and starts serving. Returns the bound port (useful with port 0). */
  suspend fun start(): Int {
    val s =
      embeddedServer(
        Netty,
        configure = {
          connector {
            this.host = this@DocsServer.host
            this.port = this@DocsServer.port
          }
        },
      ) {
        routing { mountDocs(docs) }
      }
    s.start(wait = false)
    server = s
    return s.engine.resolvedConnectors().first().port
  }

  fun stop() {
    server?.stop(0, 1000)
  }
}

/**
 * HTTPS and WebSocket web client server running on Ktor's Netty engine.
 *
 * ## WebSocket Protocol & Framing
 * - **Session mapping:** Each WebSocket connection corresponds to a single world session.
 * - **Frame format:** Text frames containing exactly one JSON message object.
 *     - Server-to-client frames: `text` (game output text), `prompt` (current prompt line), `hide`
 *       (masking input for passwords), `gmcp` (out-of-band GMCP payload), and `error` (fatal or
 *       validation error message).
 *     - Client-to-server frames: `auth` (bearer session token), `input` (raw player command line),
 *       and `gmcp` (client package data).
 * - **Frame size cap:** 64 KB maximum frame size.
 * - **Ping & keepalive:** 30-second ping interval ([WebSockets] `pingPeriod`) and 60-second
 *   connection timeout (`timeout`). Unauthenticated sockets must deliver an `auth` frame within 30
 *   seconds ([AUTH_WAIT]).
 * - **Token verification:** Active sockets are audited against session token revocations every 60
 *   seconds ([TOKEN_CHECK_INTERVAL]).
 *
 * ## HTTP Endpoints
 * - `GET /`: Serves the single-page web client `index.html` (`Cache-Control: no-cache`).
 * - `GET /assets/{hash}/{name...}`: Serves hashed static client assets with immutable caching
 *   (`max-age=31536000, immutable`).
 * - `POST /api/login`: Authenticates login credentials, returning JSON authentication status and
 *   setting the `gups_session` cookie.
 * - `POST /api/code`: Validates invite or setup code validity before registration.
 * - `POST /api/signup`: Registers a new account using a valid invite code and establishes the
 *   initial password.
 * - `POST /api/logout`: Revokes the current session token in the database and clears the session
 *   cookie.
 * - `GET /ws`: WebSocket endpoint upgrading connections to interactive game sessions.
 * - `GET /docs/`: Serves offline HTML API reference documentation when configured.
 */
class WebServer(
  private val inbox: WorldInbox,
  private val web: WebAccounts,
  private val assets: WebAssets,
  private val host: String = "127.0.0.1",
  private val port: Int = 443,
  private val sessionIds: AtomicLong = AtomicLong(1),
  private val tls: ServerTls? = null,
  private val tokenCheck: Duration = TOKEN_CHECK_INTERVAL,
  /** The API reference's files, served at `/docs/` with no login. */
  private val docs: Map<String, ByteArray> = emptyMap(),
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val live = ConcurrentHashMap.newKeySet<Socket>()
  private var server:
    EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? =
    null

  /** Sockets open now, for tests and `@stats`. */
  val sockets: Int
    get() = live.size

  /** Binds and starts serving. Returns the bound port (useful with port 0). */
  suspend fun start(): Int {
    val s =
      embeddedServer(
        Netty,
        configure = {
          connector {
            this.host = this@WebServer.host
            this.port = this@WebServer.port
          }
          tls?.let { t ->
            channelPipelineConfig = { addFirst("tls", t.context().newHandler(channel().alloc())) }
          }
        },
      ) {
        install(WebSockets) {
          pingPeriod = 30.seconds
          timeout = 60.seconds
          maxFrameSize = 64L * 1024
        }
        routing {
          get("/") {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondText(assets.index, ContentType.Text.Html)
          }
          get("/assets/{hash}/{name...}") {
            val name = call.parameters.getAll("name")?.joinToString("/").orEmpty()
            val bytes =
              assets[name].takeIf { call.parameters["hash"] == assets.hash }
                ?: return@get call.respondText("Not found", status = HttpStatusCode.NotFound)
            call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
            call.respondBytes(bytes, WebAssets.contentType(name))
          }
          post("/api/login") { login(call) }
          post("/api/code") { checkCode(call) }
          post("/api/signup") { signup(call) }
          post("/api/logout") { logout(call) }
          webSocket("/ws") { serve(this) }
          if (docs.isNotEmpty()) mountDocs(docs)
        }
      }
    s.start(wait = false)
    server = s
    scope.launch {
      while (isActive) {
        delay(tokenCheck.toMillis())
        checkTokens()
      }
    }
    return s.engine.resolvedConnectors().first().port
  }

  fun stop() {
    scope.cancel()
    server?.stop(0, 1000)
  }

  // ---- HTTP --------------------------------------------------------------------------------

  private fun json(vararg pairs: Pair<String, Any?>) = toJson(mapOf(*pairs)).toString()

  private suspend fun login(call: ApplicationCall) {
    val body = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
    val login = body?.string("login")
    val password = body?.string("password")
    if (login == null || password == null) {
      return call.respondText(
        json("error" to "Send {\"login\": ..., \"password\": ...}."),
        ContentType.Application.Json,
        HttpStatusCode.BadRequest,
      )
    }
    val remote = call.request.local.remoteAddress
    answer(call, withContext(Dispatchers.IO) { web.login(login, password, remote) })
  }

  /** A token and its cookie, or the refusal with its status. */
  private suspend fun answer(call: ApplicationCall, r: WebLogin) {
    when (r) {
      is WebLogin.Ok -> {
        val maxAge = (r.expiresAt - System.currentTimeMillis()) / 1000
        // Set by hand: Ktor sees plain HTTP behind our own TLS handler and refuses Secure cookies.
        call.response.header(
          HttpHeaders.SetCookie,
          "$SESSION_COOKIE=${r.token}; Path=/; Max-Age=$maxAge; HttpOnly; Secure; SameSite=Strict",
        )
        call.respondText(
          json("token" to r.token, "expires_at" to r.expiresAt),
          ContentType.Application.Json,
        )
      }
      is WebLogin.Refused ->
        call.respondText(
          json("error" to r.message),
          ContentType.Application.Json,
          HttpStatusCode.fromValue(r.status),
        )
    }
  }

  /** `{"code"}`: whether a sign-up code is usable, and the login it is bound to, if any. */
  private suspend fun checkCode(call: ApplicationCall) {
    val code =
      runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }
        .getOrNull()
        ?.string("code")
        ?: return call.respondText(
          json("error" to "Send {\"code\": ...}."),
          ContentType.Application.Json,
          HttpStatusCode.BadRequest,
        )
    val remote = call.request.local.remoteAddress
    when (val r = withContext(Dispatchers.IO) { web.checkCode(code, remote) }) {
      is CodeCheck.Valid -> call.respondText(json("login" to r.login), ContentType.Application.Json)
      is CodeCheck.Refused ->
        call.respondText(
          json("error" to r.message),
          ContentType.Application.Json,
          HttpStatusCode.fromValue(r.status),
        )
    }
  }

  /**
   * `{"code", "login", "password"}`: redeems an instructor's code and logs in, like `/api/login`.
   */
  private suspend fun signup(call: ApplicationCall) {
    val body = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
    val code = body?.string("code")
    val password = body?.string("password")
    if (code == null || password == null) {
      return call.respondText(
        json("error" to "Send {\"code\": ..., \"login\": ..., \"password\": ...}."),
        ContentType.Application.Json,
        HttpStatusCode.BadRequest,
      )
    }
    val remote = call.request.local.remoteAddress
    answer(
      call,
      withContext(Dispatchers.IO) { web.signup(code, body.string("login"), password, remote) },
    )
  }

  private suspend fun logout(call: ApplicationCall) {
    val token =
      call.request.cookies[SESSION_COOKIE]
        ?: call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        ?: runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject.string("token") }
          .getOrNull()
    if (token != null) {
      withContext(Dispatchers.IO) { web.logout(token) }
      for (s in live) if (s.token == token) s.end("You have logged out.")
    }
    call.response.header(
      HttpHeaders.SetCookie,
      "$SESSION_COOKIE=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict",
    )
    call.respondText(json("ok" to true), ContentType.Application.Json)
  }

  /**
   * Ends every socket whose token expired or was revoked (logout, a password change, `@role
   * revoke`).
   */
  internal suspend fun checkTokens() {
    for (s in live.toList()) {
      val still =
        withContext(Dispatchers.IO) {
          runCatching { web.accountFor(s.token) }.getOrDefault(s.account)
        }
      if (still != s.account) s.end("Your session has ended. Please log in again.")
    }
  }

  // ---- WebSocket ---------------------------------------------------------------------------

  private sealed interface Out {
    class Message(val json: String) : Out

    class End(val reason: String) : Out
  }

  /** One authenticated socket and its world session. */
  private inner class Socket(val token: String, val account: Long) {
    val id = SessionId(sessionIds.getAndIncrement())
    val out = Channel<Out>(Channel.UNLIMITED)
    val buffered = AtomicLong()
    val ended = AtomicBoolean(false)
    val errors = AtomicInteger()

    @Volatile var supports: Set<String> = emptySet()

    fun put(json: String) {
      if (ended.get()) return
      if (buffered.addAndGet(json.length.toLong()) > OUTPUT_BUFFER_MAX) {
        // A slow client: drop it; the character goes link-dead.
        end("Too slow.")
        return
      }
      out.trySend(Out.Message(json))
    }

    /** Tells the client why with an `error` frame, then closes once the queue drains. */
    fun end(reason: String) {
      if (!ended.compareAndSet(false, true)) return
      out.trySend(Out.Message(json("t" to "error", "d" to reason)))
      out.trySend(Out.End(reason))
    }

    val sink =
      object : OutputSink {
        override fun send(text: String) = put(json("t" to "text", "d" to text))

        override fun prompt(text: String) = put(json("t" to "prompt", "d" to text))

        override fun hideInput(hidden: Boolean) = put(json("t" to "hide", "d" to hidden))

        override fun gmcp(pkg: String, data: Any?) {
          if (wantsGmcp(pkg)) put(toJson(mapOf("t" to "gmcp", "p" to pkg, "d" to data)).toString())
        }

        override fun wantsGmcp(pkg: String): Boolean = supportsGmcp(supports, pkg)

        override fun close(reason: String) {
          if (ended.get()) return
          put(json("t" to "text", "d" to reason + "\n"))
          if (ended.compareAndSet(false, true)) out.trySend(Out.End(reason))
        }
      }

    fun receive(text: String) {
      val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
      when (o?.string("t")) {
        "input" -> {
          val raw = o.string("d") ?: return bad()
          val line = raw.filter { it >= ' ' || it == '\t' }
          val bytes = line.toByteArray(Charsets.UTF_8)
          val cut =
            if (bytes.size > MAX_LINE_BYTES)
              String(bytes, 0, MAX_LINE_BYTES, Charsets.UTF_8).trimEnd('�')
            else line
          if (cut.length < line.length)
            sink.send("Your line was longer than $MAX_LINE_BYTES bytes and was cut short.\n")
          inbox.submit(ClientInputEvent(id, cut, System.nanoTime()))
        }
        "gmcp" -> supports = updateSupports(supports, o.string("p") ?: return bad(), o["d"])
        "auth" -> Unit
        else -> bad()
      }
    }

    private fun bad() {
      if (errors.incrementAndGet() >= MAX_PROTOCOL_ERRORS) end("Too many malformed messages.")
    }
  }

  private suspend fun serve(ws: DefaultWebSocketServerSession) {
    val token =
      ws.call.request.cookies[SESSION_COOKIE]
        ?: withTimeoutOrNull(AUTH_WAIT) {
          for (frame in ws.incoming) {
            if (frame !is Frame.Text) continue
            val o = runCatching { Json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull()
            return@withTimeoutOrNull if (o?.string("t") == "auth") o.string("d") else null
          }
          null
        }
    val account =
      token?.let { t ->
        withContext(Dispatchers.IO) { runCatching { web.accountFor(t) }.getOrNull() }
      }
    if (token == null || account == null) {
      ws.outgoing.send(
        Frame.Text(
          json("t" to "error", "d" to "Please log in first; your session may have expired.")
        )
      )
      ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "not logged in"))
      return
    }
    val s = Socket(token, account)
    live += s
    val writer =
      scope.launch {
        for (o in s.out) {
          when (o) {
            is Out.Message -> {
              ws.outgoing.send(Frame.Text(o.json))
              s.buffered.addAndGet(-o.json.length.toLong())
            }
            is Out.End -> {
              ws.close(CloseReason(CloseReason.Codes.NORMAL, o.reason.take(100)))
              break
            }
          }
        }
      }
    try {
      inbox.submit(
        WorldControlEvent.Connect(s.id, s.sink, ws.call.request.local.remoteAddress, "ws", account)
      )
      for (frame in ws.incoming) {
        if (frame is Frame.Text) s.receive(frame.readText())
      }
    } catch (_: Exception) {
      // The peer went away: a dropped link.
    } finally {
      live -= s
      s.ended.set(true)
      s.out.close()
      inbox.submit(WorldControlEvent.Disconnect(s.id))
      writer.join()
    }
  }
}

private fun JsonObject.string(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
