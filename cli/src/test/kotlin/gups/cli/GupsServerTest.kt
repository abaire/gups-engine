package gups.cli

import com.github.ajalt.clikt.testing.test
import gups.script.WorldDir
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class GupsServerTest {
  private fun copyTemplate(): Path {
    val from = Path.of(System.getProperty("gups.templateWorld"))
    val to = Files.createTempDirectory("gups-dev")
    Files.walk(from).use { paths ->
      for (p in paths) {
        val target = to.resolve(from.relativize(p).toString())
        if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
      }
    }
    return to
  }

  /** Reads until [marker] shows up, or fails after a few seconds. */
  private fun Socket.readUntil(marker: String): String {
    soTimeout = 200
    val out = StringBuilder()
    val buf = ByteArray(8192)
    val deadline = System.nanoTime() + 8_000_000_000
    while (marker !in out) {
      check(System.nanoTime() < deadline) { "never saw '$marker' in:\n$out" }
      try {
        val n = getInputStream().read(buf)
        if (n < 0) break
        out.append(String(buf, 0, n, Charsets.UTF_8))
      } catch (_: java.net.SocketTimeoutException) {}
    }
    return out.toString()
  }

  private fun Socket.line(text: String) = getOutputStream().write("$text\r\n".toByteArray())

  /**
   * Runs [play], adding the server log and what the world and server threads were doing to any
   * failure, so a stall on a slow CI runner can be diagnosed from the report.
   */
  private fun <T> explained(logs: CharSequence, play: () -> T): T =
    try {
      play()
    } catch (e: Throwable) {
      val threads =
        Thread.getAllStackTraces()
          .filterKeys { "DefaultDispatcher" in it.name || it.name.startsWith("gups") }
          .entries
          .sortedBy { it.key.name }
          .joinToString("\n") { (t, stack) ->
            "${t.name} (${t.state})\n" + stack.take(20).joinToString("\n") { "    at $it" }
          }
      throw AssertionError("${e.message}\n--- server log ---\n$logs--- threads ---\n$threads", e)
    }

  /**
   * Runs a dev server, or a --prod one with [prod], on [root] until [play] returns, then shuts it
   * down cleanly.
   */
  private fun withServer(
    root: Path,
    start: gups.core.Label? = null,
    prod: ProdOptions? = null,
    play: (Socket) -> Unit,
  ): String {
    val logs = StringBuffer()
    val server =
      GupsServer(WorldDir(root), port = 0, start = start, prod = prod) {
        logs.append(it).append('\n')
      }
    assertTrue(server.boot(), logs.toString())
    val port = CompletableFuture<Int>()
    val runner = thread { runBlocking(Dispatchers.Default) { server.run { port.complete(it) } } }
    try {
      explained(logs) { Socket("127.0.0.1", port.get(10, TimeUnit.SECONDS)).use(play) }
    } finally {
      server.stop()
      runner.join(10_000)
    }
    return logs.toString()
  }

  @Test
  fun `a dev server serves the API reference on its docs port`() {
    val root = copyTemplate()
    val logs = StringBuffer()
    val server =
      GupsServer(WorldDir(root), port = 0, db = null, docsPort = 0) { logs.append(it).append('\n') }
    assertTrue(server.boot(), logs.toString())
    val port = CompletableFuture<Int>()
    val runner = thread { runBlocking(Dispatchers.Default) { server.run { port.complete(it) } } }
    try {
      explained(logs) {
        val telnet = port.get(10, TimeUnit.SECONDS)
        assertEquals(telnet, server.ports["telnet"])
        val docs = assertNotNull(server.ports["docs"])
        val http = java.net.http.HttpClient.newHttpClient()
        val page =
          http.send(
            java.net.http.HttpRequest.newBuilder(
                java.net.URI("http://127.0.0.1:$docs/docs/hooks.html")
              )
              .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString(),
          )
        assertEquals(200, page.statusCode())
        assertContains(page.body(), "id=\"hook-on_enter\"")
      }
    } finally {
      server.stop()
      runner.join(10_000)
    }
  }

  @Test
  fun `shutdown waits only as long as the configured timeout`() {
    val root = copyTemplate()
    assertEquals(
      SHUTDOWN_WAIT_MS,
      GupsServer(WorldDir(root), port = 0, db = null).shutdownTimeoutMs,
    )
    val server =
      GupsServer(
        WorldDir(root),
        port = 0,
        db = null,
        prod = ProdOptions("127.0.0.1", shutdownTimeoutMs = 200),
      )
    // Never run, so the final checkpoint never finishes: the wait gives up after 200 ms.
    val started = System.nanoTime()
    assertTrue(!server.awaitStopped(), "a server that never ran can't have stopped")
    val waitedMs = (System.nanoTime() - started) / 1_000_000
    assertTrue(waitedMs in 150..5_000, "waited $waitedMs ms for a 200 ms shutdown timeout")
  }

  @Test
  fun `characters and the world survive a restart`() {
    val root = copyTemplate()
    withServer(root) { s ->
      s.readUntil("known?")
      s.line("Tess")
      s.readUntil("Exits: north.")
      s.line("north")
      s.readUntil("Welcome to the tutorial")
      s.line("say key")
      s.readUntil("hands Tess a brass key")
      s.line("quit")
      s.readUntil("Goodbye.")
    }
    val logs =
      withServer(root) { s ->
        s.readUntil("known?")
        s.line("Tess")
        s.readUntil("Welcome back, Tess.")
        s.line("inventory")
        s.readUntil("a brass key")
      }
    assertContains(logs, "Restored")
  }

  @Test
  fun `a script error can be replayed with gups repro`() {
    val root = copyTemplate()
    val oops = root.resolve("zones/oops").also { Files.createDirectories(it) }
    oops.resolve("package.star").writeText("package(api_version = 1, kind = \"zone\")\n")
    oops
      .resolve("rooms.star")
      .writeText(
        """
        def _trip(ctx):
            if ctx.direction == "north":
                fail("the floor gives way")

        room("ledge", exits = [exit("north", ":pit")], before_exit = _trip)
        room("pit", exits = [exit("south", ":ledge")])
        """
          .trimIndent() + "\n"
      )
    withServer(root, start = gups.core.Label("//zones/oops:ledge")) { s ->
      s.readUntil("known?")
      s.line("Tess")
      s.readUntil("Exits: north.")
      s.line("north")
      s.readUntil("Exits: south.")
      s.line("quit")
      s.readUntil("Goodbye.")
    }
    val db = GupsServer.defaultDb(WorldDir(root))
    val id =
      gups.persist.Database.open(db).use { d ->
        d.diagnostics().first { it.kind == "script_error" }.id
      }
    val bundle = root.resolve("bundle.json")
    val result =
      gupsCommand()
        .test(listOf("repro", "$id", "--world", root.toString(), "--save", bundle.toString()))
    assertEquals(0, result.statusCode, result.output)
    assertContains(result.stdout, "Reproduced at pulse")
    assertContains(result.stdout, "the floor gives way")
    val again = gupsCommand().test(listOf("repro", bundle.toString(), "--world", root.toString()))
    assertEquals(0, again.statusCode, again.output)

    val backup = gupsCommand().test(listOf("backup", "--world", root.toString()))
    assertEquals(0, backup.statusCode, backup.output)
    val file = backup.stdout.substringAfter("Backed up to ").trim()
    val restore = gupsCommand().test(listOf("restore", file, "--world", root.toString()))
    assertEquals(0, restore.statusCode, restore.output)
  }

  @Test
  fun `a roster student logs in to a --prod server`() {
    val root = copyTemplate()
    val roster =
      root.resolve("roster.csv").also { it.writeText("login,github_handle\nann,ann-gh\n") }
    val imported =
      gupsCommand().test(listOf("import-roster", roster.toString(), "--world", root.toString()))
    assertEquals(0, imported.statusCode, imported.output)
    val code = imported.stdout.lines()[1].substringAfter("ann,")
    val prod = ProdOptions("127.0.0.1", gups.auth.LoginSettings())
    val logs =
      withServer(root, prod = prod) { s ->
        s.readUntil("invite code)")
        s.line("new")
        s.readUntil("setup code: ")
        s.line(code)
        s.readUntil("Password: ")
        s.line("correct horse")
        s.readUntil("again: ")
        s.line("correct horse")
        s.readUntil("Type a name to play")
        s.line("new")
        s.readUntil("called? ")
        s.line("Tess")
        assertContains(s.readUntil("Exits: north."), "The Tutorial Gate")
        s.line("@eval 1 + 1")
        s.readUntil("Huh?")
        s.line("quit")
        s.readUntil("Goodbye.")
      }
    assertContains(logs, "Loaded")
    withServer(root, prod = prod) { s ->
      s.readUntil("invite code)")
      s.line("ann")
      s.readUntil("Password: ")
      s.line("correct horse")
      s.readUntil("Your characters: Tess.")
      s.line("tess")
      s.readUntil("Welcome back, Tess.")
    }
    // --dev keeps its passwordless local instructor, now a real account.
    withServer(root) { s ->
      s.readUntil("known?")
      s.line("Cleo")
      s.readUntil("Exits: north.")
    }
    gups.persist.Database.open(GupsServer.defaultDb(WorldDir(root))).use { d ->
      gups.persist.SqliteAccountStore(d.file, d::write).use { store ->
        assertEquals(listOf("ann", "instructor"), store.all().map { it.login }.sorted())
        assertEquals(gups.core.Role.INSTRUCTOR, store.byLogin("instructor")!!.role)
      }
    }
  }

  @Test
  fun `builders and instructors get their tools on a --prod server`() {
    val root = copyTemplate()
    root.resolve("CODEOWNERS").writeText("* @nobody-here\n/zones/tutorial/ @Bea-gh\n")
    val db = GupsServer.defaultDb(WorldDir(root))
    gups.persist.Database.open(db).use { d ->
      gups.persist.SqliteAccountStore(db, d::write).use { store ->
        val h = gups.auth.Argon2Hasher(memoryKiB = 8, iterations = 1)
        store.create(
          gups.core.Account(
            0,
            "bea",
            h.hash("correct horse"),
            gups.core.Role.BUILDER,
            githubHandle = "bea-gh",
          )
        )
        store.create(
          gups.core.Account(0, "ida", h.hash("correct horse"), gups.core.Role.INSTRUCTOR)
        )
      }
    }
    val prod = ProdOptions("127.0.0.1")
    fun Socket.enter(login: String, name: String) {
      readUntil("invite code)")
      line(login)
      readUntil("Password: ")
      line("correct horse")
      readUntil("Type a name to play")
      line("new")
      readUntil("called? ")
      line(name)
      readUntil("Exits: north.")
    }
    val logs =
      withServer(root, prod = prod) { s ->
        s.enter("bea", "Bea")
        s.line("@where")
        s.readUntil("//zones/tutorial:gate: ")
        s.line("@stat here")
        s.readUntil("(room, version")
        s.line("@stats")
        s.readUntil("Huh?")
        s.line("quit")
        s.readUntil("Goodbye.")
      }
    assertContains(logs, "CODEOWNERS: 1 accounts' packages changed.")
    assertContains(
      logs,
      "warning: CODEOWNERS owner @nobody-here matches no account's GitHub handle",
    )
    withServer(root, prod = prod) { s ->
      s.enter("ida", "Ida")
      s.line("@who")
      s.readUntil("instructor")
      s.line("@role bea")
      s.readUntil("CODEOWNERS packages: zones/tutorial")
      s.line("@restrict bea spawn 1d testing")
      s.readUntil("bea can't use spawn until")
      s.line("@restrictions bea")
      s.readUntil("set by ida")
      s.line("@reload //zones/tutorial")
      s.readUntil("changes go live with @deploy")
      s.line("@snapshot")
      s.readUntil("Snapshot saved: ")
    }
  }

  @Test
  fun `a student plays over TLS with GMCP vitals`() {
    val root = copyTemplate()
    val db = GupsServer.defaultDb(WorldDir(root))
    gups.persist.Database.open(db).use { d ->
      gups.persist.SqliteAccountStore(db, d::write).use { store ->
        store.create(
          gups.core.Account(
            0,
            "ann",
            gups.auth.Argon2Hasher(memoryKiB = 8, iterations = 1).hash("correct horse"),
          )
        )
      }
    }
    val tls = gups.net.ServerTls.selfSigned(Files.createTempDirectory("tls"))
    val logs = StringBuffer()
    val server =
      GupsServer(
        WorldDir(root),
        port = 0,
        prod = ProdOptions(null, tls = TlsListener("127.0.0.1", 0, tls)),
      ) {
        logs.append(it).append('\n')
      }
    assertTrue(server.boot(), logs.toString())
    val port = CompletableFuture<Int>()
    val runner = thread { runBlocking(Dispatchers.Default) { server.run { port.complete(it) } } }
    try {
      val trustAll =
        arrayOf<javax.net.ssl.TrustManager>(
          object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(
              chain: Array<java.security.cert.X509Certificate>,
              authType: String,
            ) = Unit

            override fun checkServerTrusted(
              chain: Array<java.security.cert.X509Certificate>,
              authType: String,
            ) = Unit

            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
              emptyArray()
          }
        )
      val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
      explained(logs) {
        ctx.socketFactory.createSocket("127.0.0.1", port.get(10, TimeUnit.SECONDS)).use { s ->
          assertEquals(setOf("tls"), server.ports.keys, "no raw telnet unless asked for")
          val iac = 255.toByte()
          fun gmcp(m: String) =
            byteArrayOf(iac, 250.toByte(), 201.toByte()) +
              m.toByteArray() +
              byteArrayOf(iac, 240.toByte())
          s.getOutputStream()
            .write(
              byteArrayOf(iac, 253.toByte(), 201.toByte()) + gmcp("Core.Supports.Set [\"Char 1\"]")
            )
          s.readUntil("invite code)")
          s.line("ann")
          s.readUntil("Password: ")
          s.line("correct horse")
          s.readUntil("Type a name to play")
          s.line("new")
          s.readUntil("called? ")
          s.line("Tess")
          s.readUntil("Exits: north.")
          s.readUntil("Char.Vitals {\"hp\":")
        }
      }
    } finally {
      server.stop()
      runner.join(10_000)
    }
  }

  @Test
  fun `play the tutorial over telnet and see a save reload it`() {
    val root = copyTemplate()
    val logs = StringBuffer()
    val server = GupsServer(WorldDir(root), port = 0) { logs.append(it).append('\n') }
    assertTrue(server.boot(), logs.toString())
    val port = CompletableFuture<Int>()
    val runner = thread { runBlocking(Dispatchers.Default) { server.run { port.complete(it) } } }
    try {
      explained(logs) {
        Socket("127.0.0.1", port.get(10, TimeUnit.SECONDS)).use { s ->
          s.readUntil("known?")
          s.line("Tess")
          assertContains(s.readUntil("Exits: north."), "The Tutorial Gate")
          s.line("@eval room.name")
          s.readUntil("\"The Tutorial Gate\"")

          val rooms = root.resolve("zones/tutorial/rooms.star")
          rooms.writeText(rooms.readText().replace("The Tutorial Gate", "The Rebuilt Gate"))
          s.readUntil("Reloaded //zones/tutorial.")
          s.line("look")
          s.readUntil("The Rebuilt Gate")

          rooms.writeText(rooms.readText().replace("room(\"gate\",", "room(\"gate\""))
          assertContains(s.readUntil("the old version stays live"), "rooms.star")
          s.line("@errors")
          s.readUntil("reload")
        }
      }
    } finally {
      server.stop()
      runner.join(10_000)
    }
    assertContains(logs, "Reloading //zones/tutorial")
  }
}
