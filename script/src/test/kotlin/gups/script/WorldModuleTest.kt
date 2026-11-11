package gups.script

import gups.core.fixtures.TempFiles
import gups.core.model.WorldSchema
import gups.core.world.LevelMode
import gups.script.testing.TestWorlds
import gups.script.testing.tempWorld
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

  /** `module(...)` with a valid schema and [extra] arguments. */
  private fun mod(extra: String = "", schema: String = SCHEMA) =
    "module(api_version = 1, $schema${if (extra.isEmpty()) "" else ", $extra"})"

  private fun problems(schema: String): List<String> = read(mod(schema = schema)).first.problems()

  @Test
  fun `checkpoint interval defaults to 30 minutes and can be set`() {
    val (none, noError) = read(mod())
    assertNull(noError)
    assertEquals(30, none.checkpointMinutes)
    assertEquals(7200, none.checkpointPulses)
    val (set, _) = read(mod("engine = \"9.9.9\", checkpoint_minutes = 5"))
    assertEquals(1200, set.checkpointPulses)
    assertTrue(set.problems().isEmpty())
    assertContains(set.warnings().single(), "pins engine 9.9.9")
  }

  @Test
  fun `a world without a MODULE gups file doesn't start`() {
    val error = assertNotNull(WorldModule.read(WorldDir(TempFiles.dir("gups-bare"))).second)
    assertContains(error.message, "MODULE.gups is required")
  }

  @Test
  fun `bad metadata is reported`() {
    assertContains(
      assertNotNull(read(mod("chekpoint_minutes = 5")).second).message,
      "did you mean 'checkpoint_minutes'",
    )
    assertContains(assertNotNull(read(mod("checkpoint_minutes = 0")).second).message, "at least 1")
    assertContains(assertNotNull(read("x = 1").second).message, "must call module(")
    assertContains(
      read("module(api_version = 2, $SCHEMA)").first.problems().single(),
      "pins api_version 2",
    )
  }

  @Test
  fun `the schema arguments are required`() {
    for (missing in listOf("stats", "vitals", "damage_types", "unarmed_damage_type")) {
      val args =
        mapOf(
          "stats" to "stats = [\"str\"]",
          "vitals" to "vitals = [\"hp\"]",
          "damage_types" to "damage_types = [\"fire\"]",
          "unarmed_damage_type" to "unarmed_damage_type = \"fire\"",
        )
      val text = "module(api_version = 1, ${(args - missing).values.joinToString()})"
      assertContains(assertNotNull(read(text).second).message, "'$missing' is required")
    }
  }

  @Test
  fun `the schema is read in declaration order with labels`() {
    val schema =
      read(
          mod(
            "channels = [\"psionic\"], prompt = \"<{hp}/{max_hp}> \"",
            schema =
              "stats = [stat(\"str\", label = \"Strength\"), \"dex\"], vitals = [vital(\"hp\", label = \"Hit points\"), \"mana\"], damage_types = [\"fire\", \"cold\"], unarmed_damage_type = \"cold\"",
          )
        )
        .first
        .schema
    assertEquals(
      listOf("str" to "Strength", "dex" to "Dex"),
      schema.stats.map { it.name to it.label },
    )
    assertEquals(
      listOf("hp" to "Hit points", "mana" to "Mana"),
      schema.vitals.map { it.name to it.label },
    )
    assertEquals(listOf("fire", "cold"), schema.damageTypes)
    assertEquals("cold", schema.unarmedDamageType)
    assertEquals(listOf("psionic"), schema.channels)
    assertEquals("<{hp}/{max_hp}> ", schema.promptTemplate)
    assertEquals(
      "<hp mana> ".replace("hp", "{hp}hp").replace("mana", "{mana}mana"),
      WorldSchema(schema.stats, schema.vitals, schema.damageTypes, "fire").promptTemplate,
    )
  }

  @Test
  fun `damage types mix names and damage_type calls, in declaration order`() {
    val schema =
      read(
          mod(
            schema =
              "stats = [\"str\"], vitals = [\"hp\"], damage_types = [\"cold\", damage_type(\"fire\", label = \"Flame\", adjective = \"fiery\"), damage_type(\"poison\")], unarmed_damage_type = \"fire\""
          )
        )
        .first
        .schema
    assertEquals(listOf("cold", "fire", "poison"), schema.damageTypes)
    assertEquals(listOf("Cold", "Flame", "Poison"), schema.damageTypes.map(schema::damageTypeLabel))
    assertEquals(listOf(null, "fiery", null), schema.damageTypes.map(schema::damageTypeAdjective))
  }

  @Test
  fun `an adjective must be a non-empty single line`() {
    for (adjective in listOf("\"\"", "\"fire\" + \"\\n\" + \"ice\"")) {
      assertContains(
        assertNotNull(
            read(
                mod(
                  schema =
                    "stats = [\"str\"], vitals = [\"hp\"], damage_types = [damage_type(\"fire\", adjective = $adjective)], unarmed_damage_type = \"fire\""
                )
              )
              .second
          )
          .message,
        "damage_type(adjective): must be a non-empty single line",
      )
    }
  }

  @Test
  fun `a damage type that is neither a name nor a damage_type fails to load`() {
    assertContains(
      assertNotNull(
          read(
              mod(
                schema =
                  "stats = [\"str\"], vitals = [\"hp\"], damage_types = [1], unarmed_damage_type = \"fire\""
              )
            )
            .second
        )
        .message,
      "module(damage_types): expected a name or damage_type(...), got int",
    )
  }

  @Test
  fun `labels and adjectives don't change the recorded schema names`() {
    val schema =
      read(
          mod(
            schema =
              "stats = [\"str\"], vitals = [\"hp\"], damage_types = [damage_type(\"fire\", label = \"Flame\", adjective = \"fiery\"), \"cold\"], unarmed_damage_type = \"fire\""
          )
        )
        .first
        .schema
    assertEquals(listOf("fire", "cold"), schema.names.damageTypes)
    assertEquals(listOf("str"), schema.names.stats)
    assertEquals(listOf("hp"), schema.names.vitals)
  }

  @Test
  fun `a bad name is a problem`() {
    assertContains(
      problems(
          "stats = [\"Str\"], vitals = [\"hp\"], damage_types = [\"fire\"], unarmed_damage_type = \"fire\""
        )
        .single(),
      "stat name 'Str' must match [a-z][a-z0-9_]*",
    )
  }

  @Test
  fun `a name declared among both stats and vitals is a problem`() {
    assertContains(
      problems(
          "stats = [\"hp\"], vitals = [\"hp\"], damage_types = [\"fire\"], unarmed_damage_type = \"fire\""
        )
        .single(),
      "'hp' is declared more than once",
    )
  }

  @Test
  fun `a name that is an actor view field is a problem`() {
    for (reserved in listOf("name", "room", "level", "base", "resists", "condition", "affects")) {
      assertContains(
        problems(
            "stats = [\"$reserved\"], vitals = [\"hp\"], damage_types = [\"fire\"], unarmed_damage_type = \"fire\""
          )
          .single(),
        "'$reserved' is reserved",
      )
    }
  }

  @Test
  fun `a vital name may not start with max`() {
    assertContains(
      problems(
          "stats = [\"str\"], vitals = [\"maxim\"], damage_types = [\"fire\"], unarmed_damage_type = \"fire\""
        )
        .single(),
      "may not start with 'max'",
    )
  }

  @Test
  fun `a stat named max_ and a vital clashes with the vital's maximum`() {
    assertContains(
      problems(
          "stats = [\"max_hp\"], vitals = [\"hp\"], damage_types = [\"fire\"], unarmed_damage_type = \"fire\""
        )
        .single(),
      "clashes with the maximum of vital 'hp'",
    )
  }

  @Test
  fun `the unarmed damage type must be declared`() {
    assertContains(
      problems(
          "stats = [\"str\"], vitals = [\"hp\"], damage_types = [\"fire\"], unarmed_damage_type = \"fier\""
        )
        .single(),
      "unarmed_damage_type 'fier' is not a declared damage type (did you mean 'fire'?)",
    )
  }

  @Test
  fun `a channel may not repeat a built-in one`() {
    assertContains(
      read(mod("channels = [\"sound\"]")).first.problems().single(),
      "channel 'sound' is built in",
    )
  }

  @Test
  fun `channel_priority may rank built-in and declared channels, and unlisted ones follow`() {
    val schema =
      read(mod("channels = [\"psionic\"], channel_priority = [\"psionic\", \"sound\"]"))
        .first
        .schema
    assertEquals(listOf("psionic", "sound", "sight"), schema.perceptionOrder.map { it.name })
    assertEquals(
      listOf("sight", "sound", "psionic"),
      read(mod("channels = [\"psionic\"]")).first.schema.perceptionOrder.map { it.name },
    )
  }

  @Test
  fun `channel_priority rejects unknown and repeated channels`() {
    assertContains(
      read(mod("channels = [\"psionic\"], channel_priority = [\"psionc\"]"))
        .first
        .problems()
        .single(),
      "channel_priority names 'psionc', which is not a channel (did you mean 'psionic'?)",
    )
    assertContains(
      read(mod("channel_priority = [\"sight\", \"sight\"]")).first.problems().single(),
      "channel 'sight' is ranked more than once",
    )
  }

  @Test
  fun `an unknown prompt placeholder is a problem`() {
    assertContains(
      read(mod("prompt = \"<{hp} {mna}> \"")).first.problems().single(),
      "prompt placeholder '{mna}' is not a declared vital (did you mean 'mana'?)",
    )
  }

  @Test
  fun `command aliases and prefixes are read and checked`() {
    assertEquals(gups.core.world.CommandAliases.NONE, read(mod()).first.aliases)
    val ok =
      read(
          mod(
            "aliases = {\"l\": \"look\", \"peer\": \"look\"}, prefixes = {\"'\": \"say\", \"%\": \"cast\"}"
          )
        )
        .first
    assertEquals(mapOf("l" to "look", "peer" to "look"), ok.aliases.words)
    assertEquals(mapOf('\'' to "say", '%' to "cast"), ok.aliases.prefixes)
    assertTrue(ok.problems().isEmpty())
    assertContains(
      read(mod("aliases = {\"zz\": \"dance\"}")).first.problems().single(),
      "MODULE.gups: alias 'zz' names 'dance'",
    )
    assertContains(
      assertNotNull(read(mod("prefixes = {\"!!\": \"say\"}")).second).message,
      "must be a single character",
    )
  }

  @Test
  fun `plugins are pinned by checksum and checked`() {
    val sum = "sha256:" + "ab".repeat(32)
    val ok = read(mod("plugins = {\"weather\": \"$sum\"}")).first
    assertEquals(mapOf("weather" to sum), ok.plugins)
    assertTrue(ok.problems().isEmpty())
    assertContains(
      read(mod("plugins = {\"Weather\": \"$sum\"}")).first.problems().single(),
      "must match [a-z][a-z0-9_]*",
    )
    for (reserved in listOf("room", "ctx", "len", "damage", "t", "vitals", "stats")) {
      assertContains(
        read(mod("plugins = {\"$reserved\": \"$sum\"}")).first.problems().single(),
        "reserved name",
        message = reserved,
      )
    }
    assertContains(
      read(mod("plugins = {\"weather\": \"sha256:AB\"}")).first.problems().single(),
      "64 lowercase hex digits",
    )
    assertContains(
      assertNotNull(read(mod("plugins = {\"weather\": 1}")).second).message,
      "must be a string",
    )
  }

  @Test
  fun `start_room names where new characters start`() {
    assertNull(read(mod()).first.startRoom)
    assertEquals(
      gups.core.Label("//zones/town:square"),
      read(mod("start_room = \"//zones/town:square\"")).first.startRoom,
    )
    assertContains(
      assertNotNull(read(mod("start_room = \"square\"")).second).message,
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

  @Test
  fun `level_mode defaults to auto and can be set to deferred`() {
    val (autoDefault, errDefault) = read(mod())
    assertNull(errDefault)
    assertEquals(LevelMode.AUTO, autoDefault.levelMode)

    val (autoExplicit, errAuto) = read(mod("level_mode = \"auto\""))
    assertNull(errAuto)
    assertEquals(LevelMode.AUTO, autoExplicit.levelMode)

    val (deferred, errDeferred) = read(mod("level_mode = \"deferred\""))
    assertNull(errDeferred)
    assertEquals(LevelMode.DEFERRED, deferred.levelMode)
  }

  @Test
  fun `an unknown level_mode fails to load`() {
    val error = assertNotNull(read(mod("level_mode = \"instant\"")).second)
    assertContains(error.message, "module(level_mode)")
    assertContains(error.message, "'instant' is not one of auto, deferred")
  }

  @Test
  fun `level_mode without leveling fails to load naming the feature`() {
    val errDeferred =
      assertNotNull(read(mod("features = [\"combat\"], level_mode = \"deferred\"")).second)
    assertContains(errDeferred.message, "module(level_mode) needs the leveling feature")

    val errAuto = assertNotNull(read(mod("features = [\"combat\"], level_mode = \"auto\"")).second)
    assertContains(errAuto.message, "module(level_mode) needs the leveling feature")

    val (withoutLevelingDefault, noErr) = read(mod("features = [\"combat\"]"))
    assertNull(noErr)
    assertEquals(LevelMode.AUTO, withoutLevelingDefault.levelMode)
  }

  @Test
  fun `world receives level_mode from module`() {
    val fAuto =
      ScriptFixture(
        mapOf("MODULE.gups" to TestWorlds.module("level_mode = \"auto\"")),
        listOf("zones/z"),
      )
    assertEquals(LevelMode.AUTO, fAuto.w.levelMode)

    val fDeferred =
      ScriptFixture(
        mapOf("MODULE.gups" to TestWorlds.module("level_mode = \"deferred\"")),
        listOf("zones/z"),
      )
    assertEquals(LevelMode.DEFERRED, fDeferred.w.levelMode)
  }

  @Test
  fun `standing_history_pulses defaults to 14400 and can be set`() {
    val (defaultMod, errDefault) = read(mod())
    assertNull(errDefault)
    assertEquals(14_400L, defaultMod.standingHistoryPulses)

    val (customMod, errCustom) = read(mod("standing_history_pulses = 7200"))
    assertNull(errCustom)
    assertEquals(7_200L, customMod.standingHistoryPulses)
  }

  @Test
  fun `standing_history_pulses must be at least 1`() {
    val error = assertNotNull(read(mod("standing_history_pulses = 0")).second)
    assertContains(error.message, "module(standing_history_pulses): must be at least 1")
  }

  @Test
  fun `standing_history_pulses without standing fails to load naming the feature`() {
    val error =
      assertNotNull(read(mod("features = [\"combat\"], standing_history_pulses = 7200")).second)
    assertContains(error.message, "module(standing_history_pulses) needs the standing feature")
  }

  @Test
  fun `world receives standing_history_pulses from module`() {
    val fDefault = ScriptFixture(mapOf("MODULE.gups" to TestWorlds.module("")), listOf("zones/z"))
    assertEquals(14_400L, fDefault.w.standingHistoryPulses)

    val fCustom =
      ScriptFixture(
        mapOf("MODULE.gups" to TestWorlds.module("standing_history_pulses = 7200")),
        listOf("zones/z"),
      )
    assertEquals(7_200L, fCustom.w.standingHistoryPulses)
  }

  private companion object {
    const val SCHEMA =
      "stats = [\"str\"], vitals = [\"hp\", \"mana\"], damage_types = [\"fire\"], unarmed_damage_type = \"fire\""
  }
}
