package gups.pluginapi

import gups.pluginapi.host.PluginLoader
import gups.pluginapi.host.PluginNamespace
import gups.pluginapi.host.PluginRegistryImpl
import gups.pluginapi.testplugins.LookPlugin
import gups.pluginapi.testplugins.MisnamedPlugin
import gups.pluginapi.testplugins.ThrowingPlugin
import gups.pluginapi.testplugins.TidesPlugin
import gups.pluginapi.testplugins.WeatherPlugin
import gups.pluginapi.testplugins.WrongVersionPlugin
import gups.pluginapi.testplugins.buildPluginJar
import gups.script.WorldDir
import gups.script.WorldModule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginLoaderTest {
    private val root = Files.createTempDirectory("gups-plugins")
    private val plugins = root.resolve("plugins")

    @Test
    fun `each failing plugin is skipped while the valid one loads`() {
        val pins = linkedMapOf(
            "weather" to buildPluginJar(plugins.resolve("weather.jar"), WeatherPlugin::class),
            "tides" to "sha256:" + "0".repeat(64).also { buildPluginJar(plugins.resolve("tides.jar"), TidesPlugin::class) },
            "gone" to "sha256:" + "1".repeat(64),
            "two" to buildPluginJar(plugins.resolve("two.jar"), TidesPlugin::class, WrongVersionPlugin::class),
            "misnamed" to buildPluginJar(plugins.resolve("misnamed.jar"), MisnamedPlugin::class),
            "oldie" to buildPluginJar(plugins.resolve("oldie.jar"), WrongVersionPlugin::class),
            "thrower" to buildPluginJar(plugins.resolve("thrower.jar"), ThrowingPlugin::class),
            "looker" to buildPluginJar(plugins.resolve("looker.jar"), LookPlugin::class),
        )
        buildPluginJar(plugins.resolve("stray.jar"), TidesPlugin::class)
        val logged = mutableListOf<String>()
        PluginLoader.load(WorldDir(root), WorldModule(plugins = pins), log = { logged += it }).use { host ->
            assertEquals(listOf("weather"), host.ids)
            assertEquals(logged, host.warnings)
            val expected = listOf(
                "plugins/stray.jar is not pinned in MODULE.gups; ignoring it",
                "plugin 'gone': plugins/gone.jar is missing",
                "plugin 'looker': register failed: command 'look': plugin command 'look' clashes with an engine command",
                "plugin 'misnamed': the jar's plugin id is 'somethingelse', but MODULE.gups pins it as 'misnamed'",
                "plugin 'oldie': built for plugin API version ${PluginApi.VERSION + 1}; this engine has version ${PluginApi.VERSION}",
                "plugin 'thrower': register failed: register exploded",
                "plugin 'tides': checksum mismatch for plugins/tides.jar (expected sha256:${"0".repeat(64)}, got ${PluginLoader.sha256(plugins.resolve("tides.jar"))})",
                "plugin 'two': plugins/two.jar has 2 GupsPlugin providers; it needs exactly one",
            )
            assertEquals(expected.sorted(), host.warnings.sorted())
            val ns = host.globals.getValue("weather") as PluginNamespace
            assertEquals(listOf("now", "raw", "soak"), ns.fieldNames.toList())
            assertTrue(ns.isImmutable)
            assertEquals("plugin 'weather' has no member 'nope'", ns.getErrorMessageForUnknownField("nope"))
            val info = host.describe().single()
            assertEquals(listOf("crash", "forecast", "rainmake", "storm"), info.commands.map { it.name })
            assertEquals(listOf("Gups.Weather"), info.gmcp.map { it.pkg })
        }
    }

    @Test
    fun `no plugins, no warnings`() {
        PluginLoader.load(WorldDir(root), WorldModule()).use { assertEquals(emptyMap(), it.globals) }
    }

    @Test
    fun `plugin commands may not start with @`() {
        val r = PluginRegistryImpl("weather", emptyList(), emptySet()) { { } }
        val e = assertFailsWith<IllegalArgumentException> { r.addCommand("@weather", PluginLane.FAST, doc = "Tool.") {} }
        assertEquals("command name '@weather' can't start with @: those are engine tool commands", e.message)
    }
}
