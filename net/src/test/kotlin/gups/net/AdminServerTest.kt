package gups.net

import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminServerTest {
    private class FakeAdmin : Admin {
        @Volatile var healthy = true
        val rosters = mutableListOf<String>()

        override fun health() = healthy to mapOf("pulse" to 12L, "uptime_s" to 3L, "writer_healthy" to healthy)

        override fun metrics() = "gups_pulse_overruns_total 0.0\n"

        override fun deploy(commit: String?, progress: (Map<String, Any?>) -> Unit, done: (Map<String, Any?>) -> Unit) {
            Thread {
                progress(mapOf("progress" to "Fetching main."))
                progress(mapOf("progress" to "Loading."))
                done(mapOf("ok" to true, "commit" to (commit ?: "tip"), "packages" to listOf("zones/a"), "errors" to emptyList<String>()))
            }.start()
        }

        override fun roster(csv: String): Pair<Int, String> {
            rosters += csv
            return 200 to "login,setup_code\nann,AAAAA-BBBBB\n"
        }

        override fun snapshot(target: Path) {
            Files.writeString(target, "sqlite bytes")
        }
    }

    private fun serve(token: String? = "s3cret", block: (String, FakeAdmin, HttpClient) -> Unit) {
        val admin = FakeAdmin()
        val server = AdminServer(admin, token, port = 0)
        val port = runBlocking { server.start() }
        try {
            block("http://127.0.0.1:$port", admin, HttpClient.newHttpClient())
        } finally {
            server.stop()
        }
    }

    private fun HttpClient.call(method: String, url: String, body: String = "", token: String? = null): HttpResponse<String> =
        send(
            HttpRequest.newBuilder(URI(url)).method(method, HttpRequest.BodyPublishers.ofString(body)).apply { token?.let { header("Authorization", "Bearer $it") } }.build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `every endpoint but healthz needs the bearer token`() = serve { base, admin, http ->
        for ((method, path) in listOf("GET" to "/metrics", "POST" to "/deploy", "POST" to "/roster", "GET" to "/snapshot")) {
            assertEquals(401, http.call(method, "$base$path").statusCode(), "$path without a token")
            assertEquals(401, http.call(method, "$base$path", token = "s3cre").statusCode(), "$path with a wrong token")
        }
        val health = http.call("GET", "$base/healthz")
        assertEquals(200 to """{"pulse":12,"uptime_s":3,"writer_healthy":true}""", health.statusCode() to health.body())
        admin.healthy = false
        assertEquals(503, http.call("GET", "$base/healthz").statusCode())
        assertEquals("gups_pulse_overruns_total 0.0\n", http.call("GET", "$base/metrics", token = "s3cret").body())
    }

    @Test
    fun `without a configured token only healthz works`() = serve(token = null) { base, _, http ->
        assertEquals(403, http.call("GET", "$base/metrics", token = "anything").statusCode())
        assertEquals(200, http.call("GET", "$base/healthz").statusCode())
    }

    @Test
    fun `deploy streams NDJSON, roster and snapshot pass through`() = serve { base, admin, http ->
        val deploy = http.call("POST", "$base/deploy", """{"commit":"abc"}""", token = "s3cret")
        assertEquals(200, deploy.statusCode())
        assertContains(deploy.headers().firstValue("Content-Type").get(), "application/x-ndjson")
        assertEquals(
            listOf("""{"progress":"Fetching main."}""", """{"progress":"Loading."}""", """{"ok":true,"commit":"abc","packages":["zones/a"],"errors":[]}"""),
            deploy.body().trim().lines(),
        )
        assertContains(http.call("POST", "$base/deploy", "", token = "s3cret").body(), "\"commit\":\"tip\"")
        assertEquals("login,setup_code\nann,AAAAA-BBBBB\n", http.call("POST", "$base/roster", "login\nann\n", token = "s3cret").body())
        assertEquals(listOf("login\nann\n"), admin.rosters)
        val snap = http.call("GET", "$base/snapshot", token = "s3cret")
        assertEquals("sqlite bytes", snap.body())
        assertContains(snap.headers().firstValue("Content-Disposition").get(), "snapshot.db")
    }

    @Test
    fun `tokens are compared through their digests`() {
        assertTrue(AdminServer.sameToken("abc", "abc"))
        assertFalse(AdminServer.sameToken("abc", "abd"))
        assertFalse(AdminServer.sameToken("ab", "abc"), "a prefix doesn't match")
        assertFalse(AdminServer.sameToken("", "abc"))
    }
}
