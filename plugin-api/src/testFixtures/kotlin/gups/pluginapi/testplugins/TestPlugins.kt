package gups.pluginapi.testplugins

import gups.pluginapi.GupsPlugin
import gups.pluginapi.PluginApi
import gups.pluginapi.PluginGmcpCall
import gups.pluginapi.PluginLane
import gups.pluginapi.PluginParam
import gups.pluginapi.PluginRegistry
import gups.pluginapi.PluginRole
import gups.pluginapi.PluginViews
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.reflect.KClass
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue

// Plugins for the plugin tests. Each is a top-level public
// class with a no-argument constructor, so a jar's ServiceLoader descriptor can name it.

/** Every extension point. */
class WeatherPlugin : GupsPlugin {
  override val id = "weather"
  override val pluginApiVersion = PluginApi.VERSION
  override val doc = "Weather for outdoor rooms."

  override fun register(r: PluginRegistry) {
    r.addBuiltin(
      "now",
      "Today's weather in a room.",
      listOf(
        PluginParam("room", "room", "The room."),
        PluginParam("hot", "bool", "Whether it is hot.", required = false, defaultValue = false),
      ),
      returnType = "dict",
    ) { w, _, named ->
      val room = PluginViews.room(named["room"]) ?: error("now(room): not a room")
      w.setAttr(room, "asked", ((w.getAttr(room, "asked", 0L) as Long) + 1))
      mapOf(
        "room" to room.label,
        "size" to room.size,
        "hot" to named["hot"],
        "pulse" to w.pulse,
        "asked" to w.getAttr(room, "asked"),
      )
    }
    r.addBuiltin("raw", RawBuiltins(), "A raw value with methods.")
    r.addEffectPrimitive(
      "soak",
      "Soaks the target.",
      listOf(PluginParam("pulses", "int", "How long.", required = false, defaultValue = 3)),
    ) { call ->
      val t = call.target ?: call.caster
      call.world.applyAffect(
        t,
        "soaked",
        (call.args["pulses"] as Long).toInt(),
        flags = setOf("wet"),
      )
    }
    r.addCommand("forecast", PluginLane.FAST, doc = "Shows the weather.") { call ->
      call.world.send(call.actor, "Forecast for ${call.actor.room.name}: rain. ${call.args}".trim())
    }
    r.addCommand("rainmake", PluginLane.TICK, PluginRole.PLAYER, "Makes it rain.") { call ->
      call.world.echo(call.actor.room, "Rain falls.")
      call.world.setAttr(call.actor.room, "raining", true)
    }
    r.addCommand("storm", PluginLane.FAST, PluginRole.BUILDER, "Builders only.") { call ->
      call.world.send(call.actor, "Storm!")
    }
    r.addCommand("crash", PluginLane.FAST, doc = "Throws.") { error("boom") }
    r.addGmcp("Gups.Weather", "The current weather.") { w, actor ->
      mapOf("room" to actor.room.label, "raining" to (w.getAttr(actor.room, "raining") ?: false))
    }
    r.onWorldStarted { w -> w.rooms().firstOrNull()?.let { w.setAttr(it, "started", w.pulse) } }
  }
}

@StarlarkBuiltin(name = "weather_raw", doc = "Raw weather builtins.")
class RawBuiltins : StarlarkValue {
  @StarlarkMethod(
    name = "label",
    doc = "The room's label.",
    parameters = [Param(name = "room", doc = "A room.")],
    useStarlarkThread = true,
  )
  fun label(room: Any, thread: StarlarkThread): String {
    checkNotNull(PluginViews.world(thread)) { "no world" }
    return PluginViews.room(room)?.label ?: "?"
  }

  override fun isImmutable(): Boolean = true
}

/** A second, independent plugin. */
class TidesPlugin : GupsPlugin {
  override val id = "tides"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) {
    r.addBuiltin("level", "The tide level.") { _, _, _ -> 7 }
    r.addCommand("tide", PluginLane.FAST, doc = "Tide.") { call ->
      call.world.send(call.actor, "High tide.")
    }
    r.addGmcp("Gups.Tides", "Tides.") { _, _ -> 1 }
  }
}

class WrongVersionPlugin : GupsPlugin {
  override val id = "oldie"
  override val pluginApiVersion = PluginApi.VERSION + 1

  override fun register(r: PluginRegistry) = Unit
}

class ThrowingPlugin : GupsPlugin {
  override val id = "thrower"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) {
    r.addBuiltin("fine", "Registered before the failure.") { _, _, _ -> 1 }
    error("register exploded")
  }
}

/** Registers `look`, an engine command. */
class LookPlugin : GupsPlugin {
  override val id = "looker"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) =
    r.addCommand("look", PluginLane.FAST, doc = "Clash.") {}
}

/** Registers `north`, a direction. */
class NorthPlugin : GupsPlugin {
  override val id = "norther"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) =
    r.addCommand("north", PluginLane.FAST, doc = "Clash.") {}
}

/** Registers `forecast`, the weather plugin's command; loads after it (ids sort). */
class EchoForecastPlugin : GupsPlugin {
  override val id = "zforecast"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) =
    r.addCommand("forecast", PluginLane.FAST, doc = "Clash.") {}
}

/** Base for plugins that register one GMCP package that clashes. */
abstract class GmcpClashPlugin(override val id: String, private val pkg: String) : GupsPlugin {
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) = r.addGmcp(pkg, "Clash.") { _, _ -> null }
}

class CharVitalsPlugin : GmcpClashPlugin("zvitals", "Char.Vitals")

class CharPlugin : GmcpClashPlugin("zchar", "Char")

class WeatherGmcpPlugin : GmcpClashPlugin("zweather", "Gups.Weather")

/** Registers `Gups.Weather.Rain`, which nests in the weather plugin's package. */
class WeatherRainGmcpPlugin : GmcpClashPlugin("zrain", "Gups.Weather.Rain")

/** Registers `Gups.weather`, which clashes with `Gups.Weather` ignoring case. */
class WeatherCaseGmcpPlugin : GmcpClashPlugin("zweathercase", "Gups.weather")

/** Registers `Core.Shop`, which may never be used. */
class CoreGmcpPlugin : GmcpClashPlugin("zcore", "Core.Shop")

/** Declares a `Core.Weather` event package, which may never be used. */
class CoreEventPlugin : GupsPlugin {
  override val id = "zcoreevent"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) = r.addGmcpEvent("Core.Weather", "Clash.")
}

/** Declares an event package that nests in the weather plugin's state package. */
class WeatherEventClashPlugin : GupsPlugin {
  override val id = "zweatherevent"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) = r.addGmcpEvent("Gups.Weather.Forecast", "Clash.")
}

/** Registers `Gups.Sky` and then a `Gups.Sky.Moon` event, which nests in its own package. */
class SkyEventGmcpPlugin : GupsPlugin {
  override val id = "zskyevent"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) {
    r.addGmcp("Gups.Sky", "Sky.") { _, _ -> null }
    r.addGmcpEvent("Gups.Sky.Moon", "Clash.")
  }
}

/** Registers `Gups.Sky` and then `Gups.Sky.Moon`, which nests in its own first package. */
class SkyGmcpPlugin : GupsPlugin {
  override val id = "zsky"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) {
    r.addGmcp("Gups.Sky", "Sky.") { _, _ -> null }
    r.addGmcp("Gups.Sky.Moon", "Clash.") { _, _ -> null }
  }
}

/** Its id isn't the one MODULE.gups pins. */
class MisnamedPlugin : GupsPlugin {
  override val id = "somethingelse"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) = Unit
}

/** Registers a Shop state package, a `Shop.Buy` input handler, a `Shop.Stock` event and a `buy`. */
class ShopGmcpPlugin : GupsPlugin {
  override val id = "shop"
  override val pluginApiVersion = PluginApi.VERSION

  companion object {
    val calls = mutableListOf<Pair<String, Any?>>()
    var lastCall: PluginGmcpCall? = null
    var twiceError: IllegalStateException? = null
  }

  override fun register(r: PluginRegistry) {
    r.addGmcp("Shop.Stocks", "Shop stocks.") { _, _ -> null }
    r.addGmcpEvent("Shop.Stock", "A purchase happened.")
    r.onGmcp("Shop.Buy", "Buy an item.") { call ->
      calls += call.pkg to call.data
      when (call.data) {
        "cmd" -> call.command("buy")
        "twice" -> {
          call.command("buy")
          try {
            call.command("buy")
          } catch (e: IllegalStateException) {
            twiceError = e
          }
        }
        "reply" -> call.world.gmcp(call.actor, "Shop.Stock", mapOf("item" to "sword"))
        "expose" -> lastCall = call
        "throw" -> error("gmcp handler exploded")
        else -> Unit
      }
    }
    // An input package may share the plugin's own state tree.
    r.onGmcp("Shop.Stocks.Buy", "Buy a stock item.") {}
    r.addCommand("buy", PluginLane.TICK, doc = "Buys.") { call ->
      call.world.echo(call.actor.room, "Bought.")
    }
  }
}

/** Declares an input package that nests in the weather plugin's state package. */
class WeatherInputClashPlugin : GupsPlugin {
  override val id = "zweatherinput"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) = r.onGmcp("Gups.Weather.Buy", "Clash.") {}
}

/** Declares an event package that nests in the shop plugin's `Shop.Buy` input package. */
class ShopEventClashPlugin : GupsPlugin {
  override val id = "zshopevent"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) = r.addGmcpEvent("Shop.Buy.Done", "Clash.")
}

/** Declares the same input package twice. */
class ShopInputRepeatPlugin : GupsPlugin {
  override val id = "zshoprepeat"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) {
    r.onGmcp("Shop.Buy", "Clash.") {}
    r.onGmcp("Shop.Buy", "Clash again.") {}
  }
}

/**
 * Writes [jar] with a `META-INF/services` descriptor naming [classes] and their class files, and
 * returns its `sha256:` checksum. The descriptor exists only in the jar.
 */
fun buildPluginJar(jar: Path, vararg classes: KClass<out GupsPlugin>): String {
  Files.createDirectories(jar.parent)
  JarOutputStream(Files.newOutputStream(jar)).use { out ->
    out.putNextEntry(JarEntry("META-INF/services/${GupsPlugin::class.java.name}"))
    out.write(classes.joinToString("\n", postfix = "\n") { it.java.name }.toByteArray())
    out.closeEntry()
    for (c in classes) {
      val path = c.java.name.replace('.', '/') + ".class"
      out.putNextEntry(JarEntry(path))
      out.write(c.java.classLoader.getResourceAsStream(path)!!.use { it.readBytes() })
      out.closeEntry()
    }
  }
  val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))
  return "sha256:" + digest.joinToString("") { "%02x".format(it) }
}

/** Needs the `skills` feature. */
class CastyPlugin : GupsPlugin {
  override val id = "casty"
  override val pluginApiVersion = PluginApi.VERSION
  override val requiredFeatures = setOf("skills")

  override fun register(r: PluginRegistry) =
    r.addCommand("channel", PluginLane.FAST, doc = "Needs skills.") {}
}

/** Needs a feature that does not exist. */
class BogusFeaturePlugin : GupsPlugin {
  override val id = "bogus"
  override val pluginApiVersion = PluginApi.VERSION
  override val requiredFeatures = setOf("telepathy")

  override fun register(r: PluginRegistry) = Unit
}

/** Registers `cast`, which only a world without the engine's `cast` can have. */
class CastClashPlugin : GupsPlugin {
  override val id = "caster"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) = r.addCommand("cast", PluginLane.FAST, doc = "Mine.") {}
}

/** Registers `invoke`, which a world that renames `cast` to `invoke` has for itself. */
class InvokeClashPlugin : GupsPlugin {
  override val id = "invoker"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) =
    r.addCommand("invoke", PluginLane.FAST, doc = "Mine.") {}
}

/** Reads the level of the actor who runs `lvl`. */
class LevelReaderPlugin : GupsPlugin {
  override val id = "lvl"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) =
    r.addCommand("lvl", PluginLane.FAST, doc = "Level.") { call ->
      call.world.send(call.actor, "level ${call.actor.level}")
    }
}

/** Provides the `hunger` feature. */
class HungerProvider : GupsPlugin {
  override val id = "a"
  override val pluginApiVersion = PluginApi.VERSION
  override val providedFeatures = setOf("hunger")

  override fun register(r: PluginRegistry) = Unit
}

/** Requires the `hunger` feature and registers `eat`. */
class HungerConsumer : GupsPlugin {
  override val id = "b"
  override val pluginApiVersion = PluginApi.VERSION
  override val requiredFeatures = setOf("hunger")

  override fun register(r: PluginRegistry) = r.addCommand("eat", PluginLane.FAST, doc = "Eats.") {}
}

/** Requires what `a` provides and provides what `c` requires. */
class HungerChainB : GupsPlugin {
  override val id = "b"
  override val pluginApiVersion = PluginApi.VERSION
  override val requiredFeatures = setOf("hunger")
  override val providedFeatures = setOf("thirst")

  override fun register(r: PluginRegistry) = Unit
}

/** Requires the `thirst` feature the chain's middle plugin provides. */
class HungerChainC : GupsPlugin {
  override val id = "c"
  override val pluginApiVersion = PluginApi.VERSION
  override val requiredFeatures = setOf("thirst")

  override fun register(r: PluginRegistry) = Unit
}

/** Provides `hunger`, like the `a` plugin, so the two clash. */
class HungerProviderB : GupsPlugin {
  override val id = "b"
  override val pluginApiVersion = PluginApi.VERSION
  override val providedFeatures = setOf("hunger")

  override fun register(r: PluginRegistry) = Unit
}

/** Provides an engine feature id. */
class CombatProvider : GupsPlugin {
  override val id = "a"
  override val pluginApiVersion = PluginApi.VERSION
  override val providedFeatures = setOf("combat")

  override fun register(r: PluginRegistry) = Unit
}

/** Provides a reserved id. */
class CtxProvider : GupsPlugin {
  override val id = "a"
  override val pluginApiVersion = PluginApi.VERSION
  override val providedFeatures = setOf("ctx")

  override fun register(r: PluginRegistry) = Unit
}

/** Provides a malformed name. */
class BadNameProvider : GupsPlugin {
  override val id = "a"
  override val pluginApiVersion = PluginApi.VERSION
  override val providedFeatures = setOf("Bad-Name")

  override fun register(r: PluginRegistry) = Unit
}

/** Registers a command and a builtin, then refuses a world without a satiety vital. */
class RefusingHungerProvider : GupsPlugin {
  override val id = "a"
  override val pluginApiVersion = PluginApi.VERSION
  override val providedFeatures = setOf("hunger")

  override fun register(r: PluginRegistry) {
    r.addCommand("grub", PluginLane.FAST, doc = "Grub.") {}
    r.addBuiltin("grubbed", "A builtin.") { _, _, _ -> 1 }
    if (r.schema.vitals.none { it.name == "satiety" }) r.refuse("no satiety vital")
  }
}

/** Captures at registration time what `r.schema` and `r.features` show. */
class SchemaReader : GupsPlugin {
  override val id = "a"
  override val pluginApiVersion = PluginApi.VERSION
  override val providedFeatures = setOf("hunger")

  companion object {
    val seenVitals = mutableListOf<String>()
    val seenFeatures = mutableSetOf<String>()
  }

  override fun register(r: PluginRegistry) {
    seenVitals.clear()
    seenVitals += r.schema.vitals.map { it.name }
    seenFeatures.clear()
    seenFeatures += r.features
  }
}

/** Requires `hunger` and registers `eat`; loads first of the three. */
class EatConsumer : GupsPlugin {
  override val id = "a"
  override val pluginApiVersion = PluginApi.VERSION
  override val requiredFeatures = setOf("hunger")

  override fun register(r: PluginRegistry) = r.addCommand("eat", PluginLane.FAST, doc = "Eats.") {}
}

/** Registers `eat` too, clashing with the earlier plugin. */
class EatClash : GupsPlugin {
  override val id = "b"
  override val pluginApiVersion = PluginApi.VERSION

  override fun register(r: PluginRegistry) = r.addCommand("eat", PluginLane.FAST, doc = "Eats.") {}
}

/** Provides `hunger` and then refuses the world. */
class RefusingHungerZ : GupsPlugin {
  override val id = "z"
  override val pluginApiVersion = PluginApi.VERSION
  override val providedFeatures = setOf("hunger")

  override fun register(r: PluginRegistry) = r.refuse("no satiety vital")
}
