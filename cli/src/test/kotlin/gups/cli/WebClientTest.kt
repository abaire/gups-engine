package gups.cli

import gups.script.WorldDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIf
import org.openqa.selenium.By
import org.openqa.selenium.Keys
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.firefox.FirefoxOptions
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebClientTest {
    private val pw = "correct horse"

    private fun copyTemplate(): Path {
        val from = Path.of(System.getProperty("gups.templateWorld"))
        val to = Files.createTempDirectory("gups-web")
        Files.walk(from).use { paths ->
            for (p in paths) {
                val target = to.resolve(from.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
            }
        }
        return to
    }

    /** Runs a --prod server with only HTTPS (self-signed) until [play] returns; [play] also gets an invite code. */
    private fun withWebServer(play: (base: String, invite: String) -> Unit) {
        val root = copyTemplate()
        val db = GupsServer.defaultDb(WorldDir(root))
        val invite = gups.persist.Database.open(db).use { d ->
            gups.persist.SqliteAccountStore(db, d::write).use { store ->
                store.create(gups.core.Account(0, "ann", gups.auth.Argon2Hasher(memoryKiB = 8, iterations = 1).hash(pw)))
                gups.auth.AccountService(store).createInvite(gups.core.Role.PLAYER)
            }
        }
        val tls = gups.net.ServerTls.selfSigned(Files.createTempDirectory("tls"))
        val logs = StringBuffer()
        val server = GupsServer(WorldDir(root), port = 0, prod = ProdOptions(null, web = TlsListener("127.0.0.1", 0, tls))) { logs.append(it).append('\n') }
        assertTrue(server.boot(), logs.toString())
        val port = CompletableFuture<Int>()
        val runner = thread { runBlocking(Dispatchers.Default) { server.run { port.complete(it) } } }
        try {
            play("https://127.0.0.1:${port.get(10, TimeUnit.SECONDS)}", invite)
        } finally {
            server.stop()
            runner.join(10_000)
        }
    }

    private val trustAll: SSLContext = SSLContext.getInstance("TLS").apply {
        init(
            null,
            arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                },
            ),
            null,
        )
    }

    private class Frames : WebSocket.Listener {
        val got = LinkedBlockingQueue<String>()
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

        fun until(marker: String): String {
            val seen = StringBuilder()
            while (marker !in seen) seen.append(assertNotNull(got.poll(8, TimeUnit.SECONDS), "never saw '$marker' in:\n$seen")).append('\n')
            return seen.toString()
        }
    }

    @Test
    fun `a frame-level session through the WebSocket`() = withWebServer { base, _ ->
        val http = HttpClient.newBuilder().sslContext(trustAll).build()
        val index = http.send(HttpRequest.newBuilder(URI("$base/")).build(), HttpResponse.BodyHandlers.ofString()).body()
        val prefix = Regex("/assets/[0-9a-f]+").find(index)!!.value
        for (file in listOf("app.js", "markup.js", "panels.js", "style.css")) {
            assertEquals(200, http.send(HttpRequest.newBuilder(URI("$base$prefix/$file")).build(), HttpResponse.BodyHandlers.discarding()).statusCode(), file)
        }
        val login = http.send(
            HttpRequest.newBuilder(URI("$base/api/login")).POST(HttpRequest.BodyPublishers.ofString("""{"login":"ann","password":"$pw"}""")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, login.statusCode(), login.body())
        val token = Regex("\"token\":\"([^\"]+)\"").find(login.body())!!.groupValues[1]
        val frames = Frames()
        val ws = http.newWebSocketBuilder().buildAsync(URI(base.replace("https", "wss") + "/ws"), frames).get(5, TimeUnit.SECONDS)
        ws.sendText("""{"t":"auth","d":"$token"}""", true).get()
        ws.sendText("""{"t":"gmcp","p":"Core.Supports.Set","d":["Char 1","Room 1"]}""", true).get()
        assertContains(frames.until("Type a name to play"), "You have no characters yet.")
        ws.sendText("""{"t":"input","d":"new"}""", true).get()
        frames.until("""{"t":"prompt","d":"What will your new character be called? "}""")
        ws.sendText("""{"t":"input","d":"Tess"}""", true).get()
        frames.until("Exits: north.")
        assertContains(frames.until("\"p\":\"Char.Vitals\""), "\"maxhp\":")
        frames.until("\"p\":\"Room.Info\"")
        ws.sendText("""{"t":"input","d":"north"}""", true).get()
        frames.until("Welcome to the tutorial")
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get()
    }

    /**
     * The real client in headless Firefox (M6 design §6.3): on Linux x64 CI, where Firefox is
     * installed, or anywhere with `-Dgups.browserTest=true`.
     */
    @Test
    @EnabledIf("browserTest")
    fun `a student signs up, logs in again, moves and sees vitals in a browser`() = withWebServer { base, invite ->
        val options = FirefoxOptions().addArguments("-headless").setAcceptInsecureCerts(true)
        val driver = FirefoxDriver(options)
        try {
            val wait = WebDriverWait(driver, Duration.ofSeconds(20))
            driver.get("$base/")
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("login")))
            // Sign up with the instructor's one-time code.
            driver.findElement(By.id("to-signup")).click()
            driver.findElement(By.id("signup-code")).sendKeys(invite, Keys.ENTER)
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("signup-login"))).sendKeys("tess")
            driver.findElement(By.id("signup-password")).sendKeys(pw)
            driver.findElement(By.id("signup-again")).sendKeys(pw, Keys.ENTER)
            wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("output"), "You have no characters yet."))
            // Log out, then in with the new password.
            driver.findElement(By.id("logout")).click()
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("login")))
            driver.findElement(By.id("login-name")).sendKeys("tess")
            driver.findElement(By.id("login-password")).sendKeys(pw, Keys.ENTER)
            wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("output"), "Type a name to play"))
            val input = driver.findElement(By.id("input"))
            input.sendKeys("new", Keys.ENTER)
            wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("prompt"), "called?"))
            input.sendKeys("Tess", Keys.ENTER)
            wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("output"), "Exits: north."))
            wait.until(ExpectedConditions.attributeContains(By.cssSelector(".bar.hp"), "data-value", "/"))
            input.sendKeys("north", Keys.ENTER)
            wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("output"), "Welcome to the tutorial"))
            assertTrue(driver.findElement(By.id("room-name")).text.isNotEmpty())
        } finally {
            driver.quit()
        }
    }

    companion object {
        @JvmStatic
        fun browserTest(): Boolean = System.getProperty("gups.browserTest") == "true" ||
            (System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64") && System.getenv("CI") == "true")
    }
}
