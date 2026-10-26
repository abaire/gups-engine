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
import gups.core.world.gmcp
import gups.core.world.packageOf
import gups.core.world.refuseRestricted
import gups.core.world.send
import gups.core.world.session

/** Trace lines one session gets per pulse before the rest are summarized (SPEC §5.13). */
const val TRACE_LINES_PER_PULSE = 50

/**
 * Builder tools backed by the script host (SPEC §4.9, §5.13): `@trace` streams script activity to
 * the sessions that asked for it, and `@eval` (`--dev` only) runs Starlark with a live ctx. Role
 * checks happen in the command registry.
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

    /** Rules functions, modifiers and each evaluation's chain (SPEC §4.6, §5.13). */
    data object Rules : Filter {
      override fun matches(e: TraceEvent) =
        e.hook == RULES || e.hook.startsWith("rules.") || e.hook.startsWith("modifier ")
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
      if (
        !sub.player.inWorld ||
          sub.player.session != sub.session ||
          world.session(sub.session) == null
      ) {
        subs.remove(sub.player)
        continue
      }
      if (!sub.filter.matches(e) || !visible(sub.player, e)) continue
      if (sub.pulse != e.pulse) {
        if (sub.dropped > 0) line(sub.player, "... ${sub.dropped} more lines at pulse ${sub.pulse}")
        sub.pulse = e.pulse
        sub.sent = 0
        sub.dropped = 0
      }
      if (sub.sent++ < TRACE_LINES_PER_PULSE) line(sub.player, e.text) else sub.dropped++
    }
    if (subs.isEmpty()) {
      host.tracer = previous
      world.onRules = null
    }
  }

  /**
   * Builders see only their own packages' events, and only their own character's rules lines (SPEC
   * §4.9, §5.13). Instructors see everything.
   */
  private fun visible(p: Player, e: TraceEvent): Boolean =
    when {
      p.role.atLeast(Role.INSTRUCTOR) -> true
      e.hook == RULES || e.hook.startsWith("rules.") || e.hook.startsWith("modifier ") ->
        e.owner == p.id
      else -> p.owns(e.pkg)
    }

  /**
   * One trace line: `Gups.Trace` when the client supports it, else a `[trace]` text line (SPEC
   * §4.10).
   */
  private fun line(p: Player, text: String) {
    if (!world.gmcp(p, "Gups.Trace", mapOf("line" to text))) world.send(p, "[trace] $text")
  }

  /** A rules chain line from the world, traced like a script event. */
  private fun emitRules(actor: gups.core.model.Actor?, line: String) =
    emit(TraceEvent(world.pulse, "rules", actor?.id, RULES, line))

  private fun trace(c: CommandCall) {
    val p = c.actor as? Player ?: return
    if (world.refuseRestricted(p, "trace")) return
    val words = c.args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val filter: Filter =
      when (words.firstOrNull()?.lowercase()) {
        null,
        "all",
        "on" -> Filter.All
        "zone" -> {
          val pkg = words.getOrNull(1)?.removePrefix("//") ?: p.room.label.pkg
          if (!p.owns(pkg)) return world.send(p, "That isn't one of your packages.")
          Filter.Zone(pkg)
        }
        "entity" -> {
          val word =
            words.drop(1).joinToString(" ").ifEmpty {
              return world.send(p, "Trace which entity?")
            }
          val e =
            if (word == "here") p.room
            else
              world.findActor(p, word) ?: world.findItem(p, word, inventory = true, ground = true)
          val target = e ?: return world.send(p, "You don't see that here.")
          if (target !== p && world.packageOf(target)?.let(p::owns) != true)
            return world.send(p, "That isn't one of your packages.")
          Filter.Owner(target.id)
        }
        "hook" ->
          Filter.Hook(
            words.getOrNull(1)
              ?: return world.send(p, "Trace which hook? For example: @trace hook on_enter")
          )
        "rules" -> Filter.Rules
        "off" -> {
          subs.remove(p)?.let {
            if (it.dropped > 0)
              world.send(p, "[trace] ... ${it.dropped} more lines at pulse ${it.pulse}")
          }
          return world.send(p, "Tracing off.")
        }
        else ->
          return world.send(
            p,
            "Usage: @trace [all|zone [package]|entity <target>|hook <name>|rules|off]",
          )
      }
    subs[p] = Sub(p, p.session ?: return, filter)
    host.tracer = ::emit
    world.onRules = ::emitRules
    world.send(p, "Tracing ${describe(filter)}.")
  }

  private fun describe(f: Filter): String =
    when (f) {
      Filter.All -> "everything"
      is Filter.Zone -> "//${f.pkg}"
      is Filter.Owner -> "${world.entity(f.id)}"
      is Filter.Hook -> "hook ${f.name}"
      Filter.Rules -> "the rules and their modifier chains"
    }

  private fun eval(c: CommandCall) {
    if (c.args.isBlank())
      return world.send(
        c.actor,
        "Usage: @eval <starlark>, with ctx, self and room bound. Example: @eval self.hp",
      )
    val out = host.eval(world, c.actor, c.args)
    if (out.isNotEmpty()) world.send(c.actor, out)
  }

  companion object {
    private const val RULES = "rules"

    /** Registers `@trace`, and `@eval` when [eval] is true (`--dev` only). */
    fun install(world: World, host: StarlarkHost, eval: Boolean): DevTools {
      val tools = DevTools(world, host)
      world.commands.registerTool(FastCommand("@trace", role = Role.BUILDER) { tools.trace(it) })
      if (eval)
        world.commands.registerTool(FastCommand("@eval", role = Role.BUILDER) { tools.eval(it) })
      return tools
    }
  }
}
