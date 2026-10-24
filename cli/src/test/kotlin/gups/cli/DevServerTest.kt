package gups.cli

import com.github.ajalt.clikt.testing.test
import gups.script.WorldDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertTrue

class DevServerTest {
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
            } catch (_: java.net.SocketTimeoutException) {
            }
        }
        return out.toString()
    }

    private fun Socket.line(text: String) = getOutputStream().write("$text\r\n".toByteArray())

    /** Runs a dev server on [root] until [play] returns, then shuts it down cleanly. */
    private fun withServer(root: Path, start: gups.core.Label? = null, play: (Socket) -> Unit): String {
        val logs = StringBuffer()
        val server = DevServer(WorldDir(root), port = 0, start = start) { logs.append(it).append('\n') }
        assertTrue(server.boot(), logs.toString())
        val port = CompletableFuture<Int>()
        val runner = thread { runBlocking(Dispatchers.Default) { server.run { port.complete(it) } } }
        try {
            Socket("127.0.0.1", port.get(10, TimeUnit.SECONDS)).use(play)
        } finally {
            server.stop()
            runner.join(10_000)
        }
        return logs.toString()
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
        val logs = withServer(root) { s ->
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
        oops.resolve("rooms.star").writeText(
            """
            def _trip(ctx):
                if ctx.direction == "north":
                    fail("the floor gives way")

            room("ledge", exits = [exit("north", ":pit")], before_exit = _trip)
            room("pit", exits = [exit("south", ":ledge")])
            """.trimIndent() + "\n",
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
        val db = DevServer.defaultDb(WorldDir(root))
        val id = gups.persist.Database.open(db).use { d -> d.diagnostics().first { it.kind == "script_error" }.id }
        val bundle = root.resolve("bundle.json")
        val result = gupsCommand().test(listOf("repro", "$id", "--world", root.toString(), "--save", bundle.toString()))
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
    fun `play the tutorial over telnet and see a save reload it`() {
        val root = copyTemplate()
        val logs = StringBuffer()
        val server = DevServer(WorldDir(root), port = 0) { logs.append(it).append('\n') }
        assertTrue(server.boot(), logs.toString())
        val port = CompletableFuture<Int>()
        val runner = thread { runBlocking(Dispatchers.Default) { server.run { port.complete(it) } } }
        try {
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
        } finally {
            server.stop()
            runner.join(10_000)
        }
        assertContains(logs, "Reloading //zones/tutorial")
    }
}
