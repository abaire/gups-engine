package gups.script

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldModuleTest {
    private fun read(text: String?) = WorldModule.read(WorldDir(tempWorld(if (text == null) emptyMap() else mapOf("MODULE.gups" to text))))

    @Test
    fun `checkpoint interval defaults to 30 minutes and can be set`() {
        val (none, noError) = read(null)
        assertNull(noError)
        assertEquals(30, none.checkpointMinutes)
        assertEquals(7200, none.checkpointPulses)
        val (set, _) = read("module(api_version = 1, engine = \"9.9.9\", checkpoint_minutes = 5)")
        assertEquals(1200, set.checkpointPulses)
        assertTrue(set.problems().isEmpty())
        assertContains(set.warnings().single(), "pins engine 9.9.9")
    }

    @Test
    fun `bad metadata is reported`() {
        assertContains(assertNotNull(read("module(chekpoint_minutes = 5)").second).message, "did you mean 'checkpoint_minutes'")
        assertContains(assertNotNull(read("module(checkpoint_minutes = 0)").second).message, "at least 1")
        assertContains(assertNotNull(read("x = 1").second).message, "must call module(")
        assertContains(read("module(api_version = 2)").first.problems().single(), "pins api_version 2")
    }

    @Test
    fun `start_room names where new characters start`() {
        assertNull(read(null).first.startRoom)
        assertEquals(gups.core.Label("//zones/town:square"), read("module(start_room = \"//zones/town:square\")").first.startRoom)
        assertContains(assertNotNull(read("module(start_room = \"square\")").second).message, "is not a label")
    }

    @Test
    fun `the server configuration file`() {
        val dir = tempWorld(
            mapOf(
                "server.gups" to """
                server(
                    world = "/srv/gups/world",
                    telnet = ":4000",
                    telnet_tls = ":4443",
                    open_signup = True,
                    max_characters = 3,
                    reserved_names = ["Gandalf"],
                )
                """.trimIndent(),
                "bad.gups" to "server(max_characters = 0)",
                "twice.gups" to "server()\nserver()",
            ),
        )
        val (c, error) = ServerConfig.read(dir.resolve("server.gups"))
        assertNull(error)
        assertEquals(ServerConfig(world = "/srv/gups/world", telnet = ":4000", telnetTls = ":4443", openSignup = true, maxCharacters = 3, reservedNames = listOf("Gandalf")), c)
        assertEquals(emptyList(), c.unused())
        assertEquals(ServerConfig(), ServerConfig.read(dir.resolve("empty.gups")).first)
        assertContains(assertNotNull(ServerConfig.read(dir.resolve("bad.gups")).second).message, "at least 1")
        assertContains(assertNotNull(ServerConfig.read(dir.resolve("twice.gups")).second).message, "more than once")
        assertContains(assertNotNull(ServerConfig.read(dir.resolve("missing.gups")).second).message, "no server configuration")
    }
}
