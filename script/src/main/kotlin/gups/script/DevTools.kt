package gups.script

import gups.core.EntityId
import gups.core.Role
import gups.core.SessionId
import gups.core.model.Player
import gups.core.world.CommandCall
import gups.core.world.FastCommand
import gups.core.world.World
import gups.core.world.findActor
import gups.core.world.findItem
import gups.core.world.send
import gups.core.world.session

/** Trace lines one session gets per pulse before the rest are summarized (SPEC §5.13). */
const val TRACE_LINES_PER_PULSE = 50

/**
 * Builder tools backed by the script host (SPEC §4.9, §5.13): `@trace` streams script
 * activity to the sessions that asked for it, and `@eval` (`--dev` only) runs Starlark with a
 * live ctx. Role checks happen in the command registry.
 */
class DevTools private constructor(private val world: World, private val host: StarlarkHost) {
    private sealed interface Filter {
        fun matches(e: TraceEvent): Boolean

        data object All : Filter {
            override fun matches(e: TraceEvent) = true
        }

        data class Zone(val pkg: String) : Filter {
            override fun matches(e: TraceEvent) = e.pkg == pkg
        }

        data class Owner(val id: EntityId) : Filter {
            override fun matches(e: TraceEvent) = e.owner == id
        }

        data class Hook(val name: String) : Filter {
            override fun matches(e: TraceEvent) = e.hook == name || e.hook.startsWith("$name ")
        }
    }

    private class Sub(val player: Player, val session: SessionId, val filter: Filter) {
        var pulse = -1L
        var sent = 0
        var dropped = 0
    }

    private val subs = LinkedHashMap<Player, Sub>()
    private val previous = host.tracer

    private fun emit(e: TraceEvent) {
        previous?.invoke(e)
        for (sub in subs.values.toList()) {
            if (!sub.player.inWorld || sub.player.session != sub.session || world.session(sub.session) == null) {
                subs.remove(sub.player)
                continue
            }
            if (!sub.filter.matches(e)) continue
            if (sub.pulse != e.pulse) {
                if (sub.dropped > 0) world.send(sub.player, "[trace] ... ${sub.dropped} more lines at pulse ${sub.pulse}")
                sub.pulse = e.pulse
                sub.sent = 0
                sub.dropped = 0
            }
            if (sub.sent++ < TRACE_LINES_PER_PULSE) world.send(sub.player, "[trace] ${e.text}") else sub.dropped++
        }
        if (subs.isEmpty()) host.tracer = previous
    }

    private fun trace(c: CommandCall) {
        val p = c.actor as? Player ?: return
        val words = c.args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val filter: Filter = when (words.firstOrNull()?.lowercase()) {
            null, "all", "on" -> Filter.All
            "zone" -> Filter.Zone(p.room.label.pkg)
            "entity" -> {
                val word = words.drop(1).joinToString(" ").ifEmpty { return world.send(p, "Trace which entity?") }
                val e = if (word == "here") p.room else world.findActor(p, word) ?: world.findItem(p, word, inventory = true, ground = true)
                Filter.Owner((e ?: return world.send(p, "You don't see that here.")).id)
            }
            "hook" -> Filter.Hook(words.getOrNull(1) ?: return world.send(p, "Trace which hook? For example: @trace hook on_enter"))
            "rules" -> return world.send(p, "Rules tracing arrives with the rules module (M5).")
            "off" -> {
                subs.remove(p)?.let { if (it.dropped > 0) world.send(p, "[trace] ... ${it.dropped} more lines at pulse ${it.pulse}") }
                return world.send(p, "Tracing off.")
            }
            else -> return world.send(p, "Usage: @trace [all|zone|entity <target>|hook <name>|rules|off]")
        }
        subs[p] = Sub(p, p.session ?: return, filter)
        host.tracer = ::emit
        world.send(p, "Tracing ${describe(filter)}.")
    }

    private fun describe(f: Filter): String = when (f) {
        Filter.All -> "everything"
        is Filter.Zone -> "//${f.pkg}"
        is Filter.Owner -> "${world.entity(f.id)}"
        is Filter.Hook -> "hook ${f.name}"
    }

    private fun eval(c: CommandCall) {
        if (c.args.isBlank()) return world.send(c.actor, "Usage: @eval <starlark>, with ctx, self and room bound. Example: @eval self.hp")
        val out = host.eval(world, c.actor, c.args)
        if (out.isNotEmpty()) world.send(c.actor, out)
    }

    companion object {
        /** Registers `@trace`, and `@eval` when [eval] is true (`--dev` only). */
        fun install(world: World, host: StarlarkHost, eval: Boolean): DevTools {
            val tools = DevTools(world, host)
            world.commands.registerTool(FastCommand("@trace", role = Role.BUILDER) { tools.trace(it) })
            if (eval) world.commands.registerTool(FastCommand("@eval", role = Role.BUILDER) { tools.eval(it) })
            return tools
        }
    }
}
