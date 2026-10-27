package gups.cli

import com.github.ajalt.clikt.testing.test
import gups.core.Label
import gups.core.OutputSink
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.world.attach
import gups.core.world.handle
import gups.core.world.submit
import gups.pluginapi.GupsPlugin
import gups.pluginapi.testplugins.CharPlugin
import gups.pluginapi.testplugins.CharVitalsPlugin
import gups.pluginapi.testplugins.EchoForecastPlugin
import gups.pluginapi.testplugins.LookPlugin
import gups.pluginapi.testplugins.NorthPlugin
import gups.pluginapi.testplugins.SkyGmcpPlugin
import gups.pluginapi.testplugins.ThrowingPlugin
import gups.pluginapi.testplugins.TidesPlugin
import gups.pluginapi.testplugins.WeatherGmcpPlugin
import gups.pluginapi.testplugins.WeatherPlugin
import gups.pluginapi.testplugins.WeatherRainGmcpPlugin
import gups.pluginapi.testplugins.WrongVersionPlugin
import gups.pluginapi.testplugins.buildPluginJar
import gups.script.WorldDir
import gups.script.testing.TestWorlds
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plugins end to end. */
class PluginIntegrationTest {
  private val zone =
    mapOf(
      "zones/z/package.star" to "package(api_version = 1, kind = \"zone\", title = \"Z\")",
      "zones/z/rooms.star" to
        """
            def entered(ctx):
                w = weather.now(ctx.room, hot = True)
                ctx.send(ctx.actor, "Weather here: %s, hot=%s, asked %d." % (w["size"], w["hot"], w["asked"]))

            room("a", name = "Glade", desc = "A glade.", size = LARGE, exits = [exit("north", ":b")])
            room("b", name = "Cave", desc = "A cave.", flags = ["dead_end_ok"], exits = [exit("south", ":a")], on_enter = entered)
            skill("drench", kind = "spell", target = "none", effect = weather.soak(pulses = 5))
            skill("deluge", kind = "spell", target = "none", effect = sequence(heal(dice = "1d1", vital = "hp"), weather.soak()))
        """,
      "zones/z/z_test.star" to
        """
            def test_builtin_in_a_hook(t):
                w = t.world()
                p = w.spawn_player("Ann", at = ":a")
                t.contains(p.do("north"), "Weather here: normal, hot=True, asked 1.")
                t.contains(p.do("south"), "Glade")
                t.contains(p.do("north"), "asked 2.")

            def test_fast_and_tick_commands(t):
                w = t.world()
                p = w.spawn_player("Ann", at = ":a")
                t.contains(p.do("forecast soon"), "Forecast for Glade: rain. soon")
                t.contains(p.do("rainmake"), "Rain falls.")
                t.eq(w.get_attr(w.room(":a"), "weather:raining"), True)
                t.contains(p.do("tide"), "High tide.")
                p.do("crash")
                t.true([d for d in w.diagnostics() if d.kind == "plugin" and "command 'crash' failed" in d.message])

            def test_effects_in_a_skill_and_a_sequence(t):
                w = t.world()
                p = w.spawn_player("Ann", at = ":a", skills = {":drench": 100, ":deluge": 100})
                p.do("cast drench")
                w.run(2)
                t.eq([a.name for a in p.affects], ["soaked"])
                p.do("cast deluge")
                w.run(2)
                t.eq([a.name for a in p.affects], ["soaked"])

            def test_on_world_started(t):
                w = t.world()
                t.eq(w.get_attr(w.room(":a"), "weather:started"), 0)

            def test_raw_builtin_needs_a_hook(t):
                t.eq(str(weather), "<plugin weather>")
        """,
      "lib/clash/package.star" to "package(api_version = 1, kind = \"lib\")",
      "lib/clash/cmds.star" to
        """
            def _forecast(ctx):
                ctx.send(ctx.actor, "script forecast")

            global_command("forecast", _forecast)
        """,
      "lib/clash/clash_test.star" to
        """
            def test_a_global_command_named_like_a_plugin_command_is_skipped(t):
                w = t.world(packages = ["//lib/clash", "//zones/z"])
                t.true([d for d in w.diagnostics() if d.kind == "load" and "global_command 'forecast' clashes with a plugin command; skipped" in d.message])
                p = w.spawn_player("Ann", at = "//zones/z:a")
                t.contains(p.do("forecast"), "Forecast for Glade")
        """,
    )

  /**
   * A world with [files], and [jars] built and pinned in `MODULE.gups` (a `null` pin uses a wrong
   * checksum).
   */
  private fun pluginWorld(
    files: Map<String, String>,
    jars: Map<String, List<KClass<out GupsPlugin>>>,
    wrong: Set<String> = emptySet(),
    extraPins: Map<String, String> = emptyMap(),
  ): Path {
    val root = tempWorld(files)
    val pins =
      jars.mapValues { (id, classes) ->
        val sum = buildPluginJar(root.resolve("plugins/$id.jar"), *classes.toTypedArray())
        if (id in wrong) "sha256:" + "0".repeat(64) else sum
      } + extraPins
    root
      .resolve("MODULE.gups")
      .toFile()
      .writeText(
        TestWorlds.module(
          "plugins = {" + pins.entries.joinToString(", ") { "\"${it.key}\": \"${it.value}\"" } + "}"
        )
      )
    return root
  }

  private val good =
    mapOf("weather" to listOf(WeatherPlugin::class), "tides" to listOf(TidesPlugin::class))

  @Test
  fun `gups test runs tests that use every extension point`() {
    val root = pluginWorld(zone, good)
    val result = gupsCommand().test(listOf("test", "--world", root.toString(), "//..."))
    assertEquals(0, result.statusCode, result.output)
    assertContains(result.stdout, "6 passed")
  }

  @Test
  fun `failing and clashing plugins are skipped and the server starts with the rest`() {
    val files =
      zone +
        mapOf(
          "zones/t/package.star" to "package(api_version = 1, kind = \"zone\", title = \"T\")",
          "zones/t/rooms.star" to
            "room(\"pool\", name = \"Pool\", desc = \"x\" * tides.level(), flags = [\"dead_end_ok\"])",
          "zones/bad/package.star" to "package(api_version = 1, kind = \"zone\", title = \"Bad\")",
          "zones/bad/x.star" to
            "weather = 1\nroom(\"x\", name = \"X\", desc = \"X.\", flags = [\"dead_end_ok\"])",
        )
    val root =
      pluginWorld(
        files,
        good +
          mapOf(
            "oldie" to listOf(WrongVersionPlugin::class),
            "thrower" to listOf(ThrowingPlugin::class),
            "looker" to listOf(LookPlugin::class),
            "norther" to listOf(NorthPlugin::class),
            "zforecast" to listOf(EchoForecastPlugin::class),
            "zvitals" to listOf(CharVitalsPlugin::class),
            "zchar" to listOf(CharPlugin::class),
            "zweather" to listOf(WeatherGmcpPlugin::class),
            "zrain" to listOf(WeatherRainGmcpPlugin::class),
            "zsky" to listOf(SkyGmcpPlugin::class),
          ),
        wrong = setOf("tides"),
        extraPins = mapOf("gone" to "sha256:" + "1".repeat(64)),
      )
    val logs = StringBuffer()
    val server = GupsServer(WorldDir(root), port = 0, db = null) { logs.append(it).append('\n') }
    assertTrue(server.boot(), logs.toString())
    val log = logs.toString()
    for (id in
      listOf(
        "tides",
        "oldie",
        "thrower",
        "looker",
        "norther",
        "zforecast",
        "zvitals",
        "zchar",
        "zweather",
        "zrain",
        "zsky",
        "gone",
      )) {
      assertContains(log, "warning: plugin '$id': ", message = log)
    }
    assertContains(log, "checksum mismatch for plugins/tides.jar")
    assertContains(log, "'look' clashes with an engine command")
    assertContains(log, "'north' clashes with a direction")
    assertContains(log, "'forecast' is already registered")
    assertContains(log, "GMCP package 'Char.Vitals' overlaps")
    assertContains(log, "GMCP package 'Char' needs at least two dot-separated parts")
    assertContains(log, "GMCP package 'Gups.Weather' is already registered")
    assertContains(log, "GMCP package 'Gups.Weather.Rain' overlaps another plugin's Gups.Weather")
    assertContains(log, "GMCP package 'Gups.Sky.Moon' overlaps this plugin's Gups.Sky")
    assertContains(log, "name 'tides' is not defined")
    assertContains(log, "cannot define 'weather': it is the namespace of plugin 'weather'")
    val w = server.world
    assertTrue(
      w.diagnostics.count { it.kind == "plugin" } >= 10,
      w.diagnostics.joinToString("\n") { it.message },
    )
    assertFalse(w.rooms.any { it.label.pkg == "zones/t" || it.label.pkg == "zones/bad" })

    // The weather plugin works in the server's world, GMCP included.
    val sink = Sink(setOf("Gups"))
    val ann = w.spawnPlayer("Ann", Label("//zones/z:a"))
    w.handle(WorldControlEvent.Connect(SessionId(1), sink))
    w.attach(SessionId(1), ann)
    w.runPulse()
    assertEquals(
      listOf("Gups.Weather" to mapOf("room" to "//zones/z:a", "raining" to false)),
      sink.gmcp.toList(),
    )
    sink.gmcp.clear()
    w.runPulse()
    assertEquals(emptyList(), sink.gmcp.toList())
    w.submit(ann, "rainmake")
    w.runPulses(3)
    assertEquals(
      listOf("Gups.Weather" to mapOf("room" to "//zones/z:a", "raining" to true)),
      sink.gmcp.toList(),
    )
    w.submit(ann, "forecast")
    w.runPulse()
    assertContains(sink.text.toString(), "Forecast for Glade: rain.")
    w.submit(ann, "storm")
    w.runPulse()
    assertFalse("Storm!" in sink.text, "storm is for builders")
  }

  @Test
  fun `a reserved plugin id stops the server`() {
    val root = pluginWorld(zone, mapOf("room" to listOf(WeatherPlugin::class)))
    val logs = StringBuffer()
    assertFalse(
      GupsServer(WorldDir(root), port = 0, db = null) { logs.append(it).append('\n') }.boot()
    )
    assertContains(logs.toString(), "plugin id 'room' is a reserved name")
  }

  @Test
  fun `docs --world lists the plugins`() {
    val root = pluginWorld(zone, good)
    val out = java.nio.file.Files.createTempDirectory("gups-plugin-docs")
    val result =
      gupsCommand().test(listOf("docs", "--world", root.toString(), "--out", out.toString()))
    assertEquals(0, result.statusCode, result.output)
    val page = out.resolve("plugins.html").toFile().readText()
    assertContains(page, "id=\"plugin-weather\"")
    assertContains(page, "id=\"plugin-tides.tide\"")
    assertContains(page, "Gups.Weather")
  }

  private class Sink(private val supports: Set<String>) : OutputSink {
    val gmcp = mutableListOf<Pair<String, Any?>>()
    val text = StringBuilder()

    override fun send(text: String) {
      this.text.append(text)
    }

    override fun gmcp(pkg: String, data: Any?) {
      gmcp += pkg to data
    }

    override fun wantsGmcp(pkg: String) = supports.any { pkg == it || pkg.startsWith("$it.") }

    override fun close(reason: String) = Unit
  }
}
