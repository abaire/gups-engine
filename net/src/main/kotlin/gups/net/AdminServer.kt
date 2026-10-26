package gups.net

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What the admin port reports and does (SPEC §4.14), implemented by the server. Calls may block.
 */
interface Admin {
  /** `GET /healthz`: healthy, and the fields of its JSON body. */
  fun health(): Pair<Boolean, Map<String, Any?>>

  /** `GET /metrics`: Prometheus text. */
  fun metrics(): String

  /**
   * `POST /deploy`: starts a deploy of [commit], or the fetched tip of the deploy branch, sending
   * each progress line to [progress] and the final result to [done].
   */
  fun deploy(
    commit: String?,
    progress: (Map<String, Any?>) -> Unit,
    done: (Map<String, Any?>) -> Unit,
  )

  /**
   * `POST /roster`: imports a roster CSV; the HTTP status and body (`login,setup_code` lines, or
   * the problems).
   */
  fun roster(csv: String): Pair<Int, String>

  /** `GET /snapshot`: writes a sanitized database copy to [target]. */
  fun snapshot(target: Path)
}

/**
 * The admin port (SPEC §4.14): plain HTTP on a private address, on Ktor's Netty engine. Every
 * endpoint but `GET /healthz` needs `Authorization: Bearer <token>`, compared in constant time.
 * With no [token] configured, they all answer 403.
 */
class AdminServer(
  private val admin: Admin,
  private val token: String?,
  private val host: String = "127.0.0.1",
  private val port: Int = 9090,
) {
  private var server:
    EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? =
    null

  suspend fun start(): Int {
    val s =
      embeddedServer(
        Netty,
        configure = {
          connector {
            this.host = this@AdminServer.host
            this.port = this@AdminServer.port
          }
        },
      ) {
        routing {
          get("/healthz") {
            val (ok, body) = withContext(Dispatchers.IO) { admin.health() }
            call.respondText(
              toJson(body).toString(),
              ContentType.Application.Json,
              if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            )
          }
          get("/metrics") {
            if (!authorized(call)) return@get
            call.respondText(
              withContext(Dispatchers.IO) { admin.metrics() },
              ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
            )
          }
          post("/deploy") {
            if (!authorized(call)) return@post
            val commit =
              runCatching {
                  Json.parseToJsonElement(call.receiveText().ifBlank { "{}" }).jsonObject["commit"]
                }
                .getOrNull()
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
            val lines = Channel<String>(Channel.UNLIMITED)
            admin.deploy(commit, { lines.trySend(toJson(it).toString()) }) { result ->
              lines.trySend(toJson(result).toString())
              lines.close()
            }
            call.respondTextWriter(ContentType("application", "x-ndjson")) {
              for (line in lines) {
                write(line)
                write("\n")
                flush()
              }
            }
          }
          post("/roster") {
            if (!authorized(call)) return@post
            val csv = call.receiveText()
            val (status, body) = withContext(Dispatchers.IO) { admin.roster(csv) }
            call.respondText(body, ContentType.Text.Plain, HttpStatusCode.fromValue(status))
          }
          get("/snapshot") {
            if (!authorized(call)) return@get
            val file =
              withContext(Dispatchers.IO) {
                val dir = Files.createTempDirectory("gups-snapshot")
                dir.resolve("snapshot.db").also(admin::snapshot)
              }
            try {
              call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"snapshot.db\"",
              )
              call.respondFile(file.toFile())
            } finally {
              Files.deleteIfExists(file)
              Files.deleteIfExists(file.parent)
            }
          }
        }
      }
    s.start(wait = false)
    server = s
    return s.engine.resolvedConnectors().first().port
  }

  fun stop() {
    server?.stop(0, 1000)
  }

  /** Answers 401 or 403 and returns false unless the bearer token matches. */
  private suspend fun authorized(call: ApplicationCall): Boolean {
    val expected =
      token
        ?: run {
          call.respondText(
            "No admin token is configured; set admin_token_file or GUPS_ADMIN_TOKEN_FILE.\n",
            status = HttpStatusCode.Forbidden,
          )
          return false
        }
    val given =
      call.request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ") }
        ?.removePrefix("Bearer ")
        ?.trim()
    if (given == null || !sameToken(given, expected)) {
      call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
      call.respondText("Unauthorized\n", status = HttpStatusCode.Unauthorized)
      return false
    }
    return true
  }

  companion object {
    /** Compares in constant time whatever the lengths, by comparing SHA-256 digests (D-160). */
    fun sameToken(given: String, expected: String): Boolean {
      val md = { s: String ->
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
      }
      return MessageDigest.isEqual(md(given), md(expected))
    }
  }
}
