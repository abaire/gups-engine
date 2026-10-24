package gups.cli

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
