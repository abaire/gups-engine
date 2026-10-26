package gups.script

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldModuleTest {
  private fun read(text: String?) =
    WorldModule.read(
      WorldDir(tempWorld(if (text == null) emptyMap() else mapOf("MODULE.gups" to text)))
    )

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
    assertContains(
      assertNotNull(read("module(chekpoint_minutes = 5)").second).message,
      "did you mean 'checkpoint_minutes'",
    )
    assertContains(
      assertNotNull(read("module(checkpoint_minutes = 0)").second).message,
      "at least 1",
    )
    assertContains(assertNotNull(read("x = 1").second).message, "must call module(")
    assertContains(read("module(api_version = 2)").first.problems().single(), "pins api_version 2")
  }

  @Test
  fun `command aliases and prefixes are read and checked`() {
    assertEquals(gups.core.world.CommandAliases.NONE, read(null).first.aliases)
    val ok =
      read(
          "module(aliases = {\"l\": \"look\", \"peer\": \"look\"}, prefixes = {\"'\": \"say\", \"%\": \"cast\"})"
        )
        .first
    assertEquals(mapOf("l" to "look", "peer" to "look"), ok.aliases.words)
    assertEquals(mapOf('\'' to "say", '%' to "cast"), ok.aliases.prefixes)
    assertTrue(ok.problems().isEmpty())
    assertContains(
      read("module(aliases = {\"zz\": \"dance\"})").first.problems().single(),
      "MODULE.gups: alias 'zz' names 'dance'",
    )
    assertContains(
      assertNotNull(read("module(prefixes = {\"!!\": \"say\"})").second).message,
      "must be a single character",
    )
  }

  @Test
  fun `plugins are pinned by checksum and checked`() {
    val sum = "sha256:" + "ab".repeat(32)
    val ok = read("module(plugins = {\"weather\": \"$sum\"})").first
    assertEquals(mapOf("weather" to sum), ok.plugins)
    assertTrue(ok.problems().isEmpty())
    assertContains(
      read("module(plugins = {\"Weather\": \"$sum\"})").first.problems().single(),
      "must match [a-z][a-z0-9_]*",
    )
    for (reserved in listOf("room", "ctx", "len", "damage", "t")) {
      assertContains(
        read("module(plugins = {\"$reserved\": \"$sum\"})").first.problems().single(),
        "reserved name",
        message = reserved,
      )
    }
    assertContains(
      read("module(plugins = {\"weather\": \"sha256:AB\"})").first.problems().single(),
      "64 lowercase hex digits",
    )
    assertContains(
      assertNotNull(read("module(plugins = {\"weather\": 1})").second).message,
      "must be a string",
    )
  }

  @Test
  fun `start_room names where new characters start`() {
    assertNull(read(null).first.startRoom)
    assertEquals(
      gups.core.Label("//zones/town:square"),
      read("module(start_room = \"//zones/town:square\")").first.startRoom,
    )
    assertContains(
      assertNotNull(read("module(start_room = \"square\")").second).message,
      "is not a label",
    )
  }

  @Test
  fun `the server configuration file`() {
    val dir =
      tempWorld(
        mapOf(
          "server.gups" to
            """
            server(
                world = "/srv/gups/world",
                telnet = ":4000",
                telnet_tls = ":4443",
                open_signup = True,
                max_characters = 3,
                reserved_names = ["Gandalf"],
                shutdown_timeout = 90,
            )
            """
              .trimIndent(),
          "bad.gups" to "server(max_characters = 0)",
          "no-wait.gups" to "server(shutdown_timeout = 0)",
          "twice.gups" to "server()\nserver()",
        )
      )
    val (c, error) = ServerConfig.read(dir.resolve("server.gups"))
    assertNull(error)
    assertEquals(
      ServerConfig(
        world = "/srv/gups/world",
        telnet = ":4000",
        telnetTls = ":4443",
        openSignup = true,
        maxCharacters = 3,
        reservedNames = listOf("Gandalf"),
        shutdownTimeout = 90,
      ),
      c,
    )
    // The shutdown timeout is configurable, not a fixed 30 s.
    assertEquals(30, ServerConfig().shutdownTimeout)
    assertContains(
      assertNotNull(ServerConfig.read(dir.resolve("no-wait.gups")).second).message,
      "shutdown_timeout",
    )
    assertEquals(ServerConfig(), ServerConfig.read(dir.resolve("empty.gups")).first)
    assertContains(
      assertNotNull(ServerConfig.read(dir.resolve("bad.gups")).second).message,
      "at least 1",
    )
    assertContains(
      assertNotNull(ServerConfig.read(dir.resolve("twice.gups")).second).message,
      "more than once",
    )
    assertContains(
      assertNotNull(ServerConfig.read(dir.resolve("missing.gups")).second).message,
      "no server configuration",
    )
  }
}
