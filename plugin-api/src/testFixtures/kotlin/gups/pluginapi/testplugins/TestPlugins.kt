package gups.pluginapi.testplugins

import gups.pluginapi.GupsPlugin
import gups.pluginapi.PluginApi
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

// Plugins for the plugin tests (SPEC §4.15). Each is a top-level public
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

/** Nests a package inside the weather plugin's `Gups.Weather`. */
class WeatherRainGmcpPlugin : GmcpClashPlugin("zrain", "Gups.Weather.Rain")

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

/**
 * Writes [jar] with a `META-INF/services` descriptor naming [classes] and their class files, and
 * returns its `sha256:` checksum (§5.5 chunk 5). The descriptor exists only in the jar.
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
