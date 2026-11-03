package gups.pluginapi

import gups.pluginapi.host.PluginHost
import gups.pluginapi.host.PluginLoader
import gups.pluginapi.host.PluginNamespace
import gups.pluginapi.host.PluginRegistryImpl
import gups.pluginapi.testplugins.CoreEventPlugin
import gups.pluginapi.testplugins.CoreGmcpPlugin
import gups.pluginapi.testplugins.LookPlugin
import gups.pluginapi.testplugins.MisnamedPlugin
import gups.pluginapi.testplugins.ShopEventClashPlugin
import gups.pluginapi.testplugins.ShopGmcpPlugin
import gups.pluginapi.testplugins.ShopInputRepeatPlugin
import gups.pluginapi.testplugins.SkyEventGmcpPlugin
import gups.pluginapi.testplugins.ThrowingPlugin
import gups.pluginapi.testplugins.TidesPlugin
import gups.pluginapi.testplugins.WeatherCaseGmcpPlugin
import gups.pluginapi.testplugins.WeatherEventClashPlugin
import gups.pluginapi.testplugins.WeatherInputClashPlugin
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
    val pins =
      linkedMapOf(
        "weather" to buildPluginJar(plugins.resolve("weather.jar"), WeatherPlugin::class),
        "tides" to
          "sha256:" +
            "0".repeat(64).also {
              buildPluginJar(plugins.resolve("tides.jar"), TidesPlugin::class)
            },
        "gone" to "sha256:" + "1".repeat(64),
        "two" to
          buildPluginJar(plugins.resolve("two.jar"), TidesPlugin::class, WrongVersionPlugin::class),
        "misnamed" to buildPluginJar(plugins.resolve("misnamed.jar"), MisnamedPlugin::class),
        "oldie" to buildPluginJar(plugins.resolve("oldie.jar"), WrongVersionPlugin::class),
        "thrower" to buildPluginJar(plugins.resolve("thrower.jar"), ThrowingPlugin::class),
        "looker" to buildPluginJar(plugins.resolve("looker.jar"), LookPlugin::class),
      )
    buildPluginJar(plugins.resolve("stray.jar"), TidesPlugin::class)
    val logged = mutableListOf<String>()
    PluginLoader.load(WorldDir(root), WorldModule(plugins = pins), log = { logged += it }).use {
      host ->
      assertEquals(listOf("weather"), host.ids)
      assertEquals(logged, host.warnings)
      val expected =
        listOf(
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
      assertEquals(
        "plugin 'weather' has no member 'nope'",
        ns.getErrorMessageForUnknownField("nope"),
      )
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
  fun `GMCP names are compared ignoring case`() {
    val pins =
      linkedMapOf(
        "weather" to buildPluginJar(plugins.resolve("weather.jar"), WeatherPlugin::class),
        "zweathercase" to
          buildPluginJar(plugins.resolve("zweathercase.jar"), WeatherCaseGmcpPlugin::class),
      )
    PluginLoader.load(WorldDir(root), WorldModule(plugins = pins), log = {}).use { host ->
      assertEquals(listOf("weather"), host.ids)
      assertTrue(host.warnings.any { "GMCP package 'Gups.weather' is already registered" in it })
    }
  }

  @Test
  fun `no plugin may use the Core root`() {
    val pins =
      linkedMapOf(
        "zcore" to buildPluginJar(plugins.resolve("zcore.jar"), CoreGmcpPlugin::class),
        "zcoreevent" to buildPluginJar(plugins.resolve("zcoreevent.jar"), CoreEventPlugin::class),
      )
    PluginLoader.load(WorldDir(root), WorldModule(plugins = pins), log = {}).use { host ->
      assertEquals(emptyList(), host.ids)
      assertTrue(host.warnings.any { "GMCP package 'Core.Shop' uses the reserved root Core" in it })
      assertTrue(
        host.warnings.any { "GMCP package 'Core.Weather' uses the reserved root Core" in it }
      )
    }
  }

  @Test
  fun `an event package may not overlap another plugin's state package`() {
    val pins =
      linkedMapOf(
        "weather" to buildPluginJar(plugins.resolve("weather.jar"), WeatherPlugin::class),
        "zweatherevent" to
          buildPluginJar(plugins.resolve("zweatherevent.jar"), WeatherEventClashPlugin::class),
      )
    PluginLoader.load(WorldDir(root), WorldModule(plugins = pins), log = {}).use { host ->
      assertEquals(listOf("weather"), host.ids)
      assertTrue(
        host.warnings.any {
          "GMCP package 'Gups.Weather.Forecast' overlaps another plugin's Gups.Weather" in it
        }
      )
    }
  }

  @Test
  fun `a plugin's event and state packages may not nest`() {
    val pins =
      linkedMapOf(
        "zskyevent" to buildPluginJar(plugins.resolve("zskyevent.jar"), SkyEventGmcpPlugin::class)
      )
    PluginLoader.load(WorldDir(root), WorldModule(plugins = pins), log = {}).use { host ->
      assertEquals(emptyList(), host.ids)
      assertTrue(
        host.warnings.any { "GMCP package 'Gups.Sky.Moon' overlaps this plugin's Gups.Sky" in it }
      )
    }
  }

  @Test
  fun `an input package may nest in the plugin's own tree`() {
    val pins =
      linkedMapOf("shop" to buildPluginJar(plugins.resolve("shop.jar"), ShopGmcpPlugin::class))
    PluginLoader.load(WorldDir(root), WorldModule(plugins = pins), log = {}).use { host ->
      assertEquals(listOf("shop"), host.ids)
      assertEquals(emptyList(), host.warnings)
    }
  }

  @Test
  fun `an input package may not nest in another plugin's package`() {
    val pins =
      linkedMapOf(
        "weather" to buildPluginJar(plugins.resolve("weather.jar"), WeatherPlugin::class),
        "zweatherinput" to
          buildPluginJar(plugins.resolve("zweatherinput.jar"), WeatherInputClashPlugin::class),
      )
    PluginLoader.load(WorldDir(root), WorldModule(plugins = pins), log = {}).use { host ->
      assertEquals(listOf("weather"), host.ids)
      assertTrue(
        host.warnings.any {
          "GMCP package 'Gups.Weather.Buy' overlaps another plugin's Gups.Weather" in it
        }
      )
    }
  }

  @Test
  fun `an outbound package may not overlap another plugin's input package`() {
    val pins =
      linkedMapOf(
        "shop" to buildPluginJar(plugins.resolve("shop.jar"), ShopGmcpPlugin::class),
        "zshopevent" to
          buildPluginJar(plugins.resolve("zshopevent.jar"), ShopEventClashPlugin::class),
      )
    PluginLoader.load(WorldDir(root), WorldModule(plugins = pins), log = {}).use { host ->
      assertEquals(listOf("shop"), host.ids)
      assertTrue(
        host.warnings.any {
          "GMCP package 'Shop.Buy.Done' overlaps another plugin's Shop.Buy" in it
        }
      )
    }
  }

  @Test
  fun `an input package may not repeat`() {
    val pins =
      linkedMapOf(
        "zshoprepeat" to
          buildPluginJar(plugins.resolve("zshoprepeat.jar"), ShopInputRepeatPlugin::class)
      )
    PluginLoader.load(WorldDir(root), WorldModule(plugins = pins), log = {}).use { host ->
      assertEquals(emptyList(), host.ids)
      assertTrue(host.warnings.any { "GMCP package 'Shop.Buy' is already registered" in it })
    }
  }

  @Test
  fun `plugin commands may not start with @`() {
    val r = PluginRegistryImpl("weather", emptyList(), emptySet()) { {} }
    val e =
      assertFailsWith<IllegalArgumentException> {
        r.addCommand("@weather", PluginLane.FAST, doc = "Tool.") {}
      }
    assertEquals(
      "command name '@weather' can't start with @: those are engine tool commands",
      e.message,
    )
  }

  @Test
  fun `custom hook names are validated`() {
    val r = PluginRegistryImpl("weather", emptyList(), emptySet()) { {} }
    assertEquals(
      "hook name 'Bad-Name' must match [a-z][a-z0-9_]*",
      assertFailsWith<IllegalArgumentException> { r.addHook("Bad-Name", "d.") {} }.message,
    )
    assertEquals(
      "'on_use' is a catalog hook",
      assertFailsWith<IllegalArgumentException> { r.addHook("on_use", "d.") {} }.message,
    )
    r.addHook("shop_price", "d.") {}
    assertEquals(
      "hook 'shop_price' is already registered",
      assertFailsWith<IllegalArgumentException> { r.addHook("shop_price", "d.") {} }.message,
    )
  }

  @Test
  fun `two plugins may register the same custom hook name`() {
    fun plugin(id: String) =
      object : GupsPlugin {
        override val id = id
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addHook("shop_price", "d.") {}
        }
      }
    val host = PluginHost.of(plugin("aaa"), plugin("zzz"))
    assertEquals(listOf("aaa", "zzz"), host.ids)
    assertEquals(2, host.describe().count { p -> p.hooks.any { it.name == "shop_price" } })
  }
}

class PluginFeatureTest {
  private val root = Files.createTempDirectory("gups-plugins")
  private val plugins = root.resolve("plugins")

  private fun pin(id: String, vararg classes: kotlin.reflect.KClass<out GupsPlugin>) =
    id to buildPluginJar(plugins.resolve("$id.jar"), *classes)

  private fun load(module: WorldModule, log: (String) -> Unit = {}) =
    PluginLoader.load(WorldDir(root), module, log = log)

  @Test
  fun `a plugin whose required feature is off is skipped with the error and the others load`() {
    val pins =
      linkedMapOf(
        pin("weather", WeatherPlugin::class),
        pin("casty", gups.pluginapi.testplugins.CastyPlugin::class),
        pin("bogus", gups.pluginapi.testplugins.BogusFeaturePlugin::class),
      )
    load(WorldModule(plugins = pins, featureIds = listOf("combat"))).use { host ->
      assertEquals(listOf("weather"), host.ids)
      assertEquals(
        listOf(
          "plugin 'bogus': needs the 'telepathy' feature",
          "plugin 'casty': needs the 'skills' feature",
        ),
        host.warnings.sorted(),
      )
    }
    load(WorldModule(plugins = pins, featureIds = listOf("skills", "combat"))).use { host ->
      assertEquals(listOf("casty", "weather"), host.ids.sorted())
    }
    load(
        WorldModule(
          plugins = linkedMapOf(pin("casty", gups.pluginapi.testplugins.CastyPlugin::class))
        )
      )
      .use { assertEquals(listOf("casty"), it.ids, "every feature is on by default") }
  }

  @Test
  fun `a plugin command may take a renamed-away name and can't take the new one`() {
    val caster = pin("caster", gups.pluginapi.testplugins.CastClashPlugin::class)
    val invoker = pin("invoker", gups.pluginapi.testplugins.InvokeClashPlugin::class)
    val plain = WorldModule(plugins = linkedMapOf(caster, invoker))
    load(plain).use { host ->
      assertEquals(listOf("invoker"), host.ids)
      assertTrue(
        host.warnings.any {
          "command 'cast': plugin command 'cast' clashes with an engine command" in it
        }
      )
    }
    val renamed = plain.copy(rename = mapOf("cast" to "invoke"))
    load(renamed).use { host ->
      assertEquals(listOf("caster"), host.ids)
      assertTrue(
        host.warnings.single().contains("plugin command 'invoke' clashes with an engine command"),
        host.warnings.toString(),
      )
    }
    // A command of a feature that is off is free too.
    load(plain.copy(featureIds = listOf("combat"))).use { host ->
      assertEquals(listOf("caster"), host.ids.take(1))
    }
  }

  @Test
  fun `plugins can register custom categories and commands with categories`() {
    val plugin =
      object : GupsPlugin {
        override val id = "crafting"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addCategory("trades", title = "Tradeskills", order = 550, help = "Crafting help.")
          r.addCommand("smith", PluginLane.FAST, PluginRole.PLAYER, "Smithing.", "trades") {}
        }
      }
    val host = gups.pluginapi.host.PluginHost.of(plugin)
    val w = world(plugins = host)

    val cat = w.commands.category("trades")
    assertTrue(cat != null)
    assertEquals("Tradeskills", cat.title)
    assertEquals(550, cat.order)
    assertEquals("Crafting help.", cat.help)

    val cmd = w.commands.pluginCommands.first { it.name == "smith" }
    assertEquals("trades", cmd.category)
  }
}
