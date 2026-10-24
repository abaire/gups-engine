package gups.cli

import com.github.ajalt.clikt.core.UsageError
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The default admin address (M6 design §7.1). */
const val DEFAULT_ADMIN = "127.0.0.1:9090"

/**
 * A running server's admin port, for `gups deploy` and `gups import-roster --admin` (M6 design
 * §3.3, §7.1). The token comes from [tokenFile], or the file `GUPS_ADMIN_TOKEN_FILE` names.
 */
class AdminClient(address: String, tokenFile: File?) {
    private val base: String = "http://" + parseAddress(address).let { (h, p) -> "${if (h == "0.0.0.0") "127.0.0.1" else h}:$p" }
    private val token: String = readSecret(tokenFile, "GUPS_ADMIN_TOKEN_FILE")
        ?: throw UsageError("the admin port needs its token: give --admin-token-file or set GUPS_ADMIN_TOKEN_FILE")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /** POSTs [body] to [path], handing the status and each response line to [line] as they arrive; returns the status. */
    fun post(path: String, body: String, contentType: String, line: (Int, String) -> Unit): Int {
        val req = HttpRequest.newBuilder(URI("$base$path"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val res = try {
            http.send(req, HttpResponse.BodyHandlers.ofLines())
        } catch (e: java.io.IOException) {
            throw UsageError("can't reach the admin port at $base: ${e.message}")
        }
        res.body().use { lines -> lines.forEach { line(res.statusCode(), it) } }
        return res.statusCode()
    }

    /** GETs [path] and saves the response body to [target]; returns the HTTP status. */
    fun download(path: String, target: File): Int {
        val req = HttpRequest.newBuilder(URI("$base$path"))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        val temp = java.nio.file.Files.createTempFile("gups-admin-dl", ".tmp")
        try {
            val res = try {
                http.send(req, HttpResponse.BodyHandlers.ofFile(temp))
            } catch (e: java.io.IOException) {
                throw UsageError("can't reach the admin port at $base: ${e.message}")
            }
            if (res.statusCode() == 200) {
                java.nio.file.Files.move(temp, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            return res.statusCode()
        } finally {
            runCatching { java.nio.file.Files.deleteIfExists(temp) }
        }
    }
}

/** A secret read from [file], or from the file the environment variable [env] names (M6 design §2.1). */
fun readSecret(file: File?, env: String): String? {
    val f = file ?: System.getenv(env)?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
    if (!f.isFile) throw UsageError("no secret file at $f")
    return f.readText().trim().ifEmpty { throw UsageError("$f is empty") }
}
