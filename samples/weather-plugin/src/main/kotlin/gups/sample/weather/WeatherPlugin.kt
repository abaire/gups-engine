package gups.sample.weather

import gups.pluginapi.GupsPlugin
import gups.pluginapi.PluginApi
import gups.pluginapi.PluginLane
import gups.pluginapi.PluginParam
import gups.pluginapi.PluginRegistry
import gups.pluginapi.PluginRole
import gups.pluginapi.PluginRoom
import gups.pluginapi.PluginWorld

/**
 * The sample plugin (SPEC_MILESTONE7.md §6.1): deterministic weather by region, using every
 * extension point. A room is outdoors when it carries a region tag; its region is the first tag.
 */
class WeatherPlugin : GupsPlugin {
    override val id = "weather"
    override val pluginApiVersion = PluginApi.VERSION
    override val doc = "Deterministic weather by region: a sample of every plugin extension point."

    override fun register(r: PluginRegistry) {
        r.addBuiltin(
            name = "now",
            doc = "The weather in a room: 'clear', 'rain' or 'storm'. Indoor rooms are always 'clear'.",
            params = listOf(PluginParam("room", "room", "The room to look at.")),
            returnType = "str",
        ) { world, pos, _ ->
            val room = pos[0] as? PluginRoom ?: throw IllegalArgumentException("weather.now(room): expected a room")
            stateFor(world, room)
        }
        r.addCommand("weather", PluginLane.FAST, PluginRole.PLAYER, "Describes the weather where you stand.") { call ->
            val room = call.actor.room
            call.world.send(call.actor, if (room.regions.isEmpty()) "You are indoors." else "The weather here is ${stateFor(call.world, room)}.")
        }
        r.addEffectPrimitive(
            name = "soak",
            doc = "Soaks the target: dex -1 for the given number of pulses.",
            params = listOf(PluginParam("pulses", "int", "How long the target stays soaked.", required = false, defaultValue = 2L)),
        ) { call ->
            val target = call.target ?: return@addEffectPrimitive
            val pulses = (call.args["pulses"] as Number).toInt()
            call.world.applyAffect(target, "soaked", duration = pulses, statDeltas = mapOf("dex" to -1))
        }
        r.addGmcp("Gups.Weather", "The weather where the player stands.") { world, actor ->
            mapOf("state" to stateFor(world, actor.room), "region" to actor.room.regions.minOrNull())
        }
        r.onWorldStarted { world ->
            val regions = world.regions()
            world.log("weather: tracking ${regions.size} region(s): ${regions.joinToString(", ")}")
        }
    }

    companion object {
        private val STATES = listOf("clear", "rain", "storm")

        /** §6.1: a pure function of the seed, the one-minute window and the room's region. */
        fun stateFor(world: PluginWorld, room: PluginRoom): String {
            val region = room.regions.minOrNull() ?: return "clear"
            var z = world.seed xor (world.pulse / 240L) * -0x61c8864680b583ebL xor region.hashCode().toLong()
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            return STATES[((z ushr 1) % STATES.size).toInt()]
        }
    }
}
