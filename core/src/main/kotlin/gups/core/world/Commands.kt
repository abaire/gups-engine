package gups.core.world

import gups.core.ACTION_QUEUE_MAX
import gups.core.model.Actor
import gups.core.model.Direction
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.Mob
import gups.core.model.QueuedAction

/** Fast-lane commands run immediately; tick-lane commands queue for action pulses (SPEC §5.2). */
enum class Lane { FAST, TICK }

data class CommandCall(val world: World, val actor: Actor, val verb: String, val args: String)

sealed class CommandDef(val name: String, val aliases: List<String>) {
    abstract val lane: Lane
}

class FastCommand(name: String, aliases: List<String> = emptyList(), val run: (CommandCall) -> Unit) :
    CommandDef(name, aliases) {
    override val lane: Lane get() = Lane.FAST
}

/** [plan] parses arguments into an [Action] when the command is popped, or returns null after messaging. */
class TickCommand(name: String, aliases: List<String> = emptyList(), val plan: (CommandCall) -> Action?) :
    CommandDef(name, aliases) {
    override val lane: Lane get() = Lane.TICK
}

/**
 * Verb lookup (SPEC §5.2): engine commands (including exits), global commands, then the
 * room's and nearby items' `commands`. Exact names and aliases win over unique prefixes.
 */
class CommandRegistry {
    private val engine: List<CommandDef> = EngineCommands.all()
    private val global = mutableListOf<CommandDef>()
    private val moves = HashMap<Direction, TickCommand>()

    /** Registers a global command, as privileged packages do with `global_command` (SPEC §4.9). */
    fun registerGlobal(command: CommandDef) {
        require(global.none { it.name == command.name }) { "Global command ${command.name} already registered" }
        global += command
    }

    val engineCommands: List<CommandDef> get() = engine
    val globalCommands: List<CommandDef> get() = global

    fun resolve(actor: Actor, verb: String): CommandDef? {
        val v = verb.lowercase()
        exact(engine, v)?.let { return it }
        val dir = Direction.parse(v)
        if (dir in actor.room.exits || dir in Direction.STANDARD) return moves.getOrPut(dir) { EngineCommands.move(dir) }
        exact(global, v)?.let { return it }
        actor.room.proto.commands[v]?.let { return zoneCommand(v, actor.room, it) }
        val items = (actor.inventoryIndex.values + actor.equipmentIndex.values + actor.room.itemIndex.values).sortedBy { it.id }
        for (item in items) item.proto.commands[v]?.let { return zoneCommand(v, item, it) }
        return prefix(engine, v) ?: prefix(global, v)
    }

    private fun exact(defs: List<CommandDef>, v: String) = defs.firstOrNull { it.name == v || v in it.aliases }

    private fun prefix(defs: List<CommandDef>, v: String) = defs.firstOrNull { it.name.startsWith(v) }

    private fun zoneCommand(verb: String, owner: Entity, handler: ZoneCommand) = TickCommand(verb) { call ->
        object : Action {
            override fun validate(w: World, actor: Actor): String? {
                val here = when (owner) {
                    is Item -> w.isPresent(owner, w.roomOf(actor))
                    else -> w.roomOf(actor) === owner
                }
                return if (here) null else "Nothing happens."
            }

            override fun resolve(w: World, actor: Actor): Int {
                try {
                    handler.run(w, actor, call.args, owner)
                } catch (e: Exception) {
                    // Players see only this for a failed zone command (SPEC §6.2).
                    w.diagnose("script", "command '$verb' on $owner failed: ${e.message}", e)
                    w.send(actor, "Nothing happens.")
                }
                return 0
            }
        }
    }
}

/** Splits `verb args`; a leading `'` is shorthand for `say`. */
internal fun parseLine(line: String): Pair<String, String>? {
    val t = line.trim()
    if (t.isEmpty()) return null
    if (t.startsWith("'")) return "say" to t.substring(1).trim()
    val space = t.indexOfFirst { it.isWhitespace() }
    return if (space < 0) t to "" else t.substring(0, space) to t.substring(space + 1).trim()
}

/** Classifies and admits one line for [actor]: fast-lane runs now, tick-lane queues. */
fun World.submit(actor: Actor, line: String): Boolean {
    val (verb, args) = parseLine(line) ?: return false
    val def = commands.resolve(actor, verb)
    if (def == null) {
        send(actor, "Huh?")
        return false
    }
    when (def) {
        is FastCommand -> guarded("command '$verb' of $actor") { def.run(CommandCall(this, actor, verb, args)) }
        is TickCommand -> {
            if (actor.actionQueue.size >= ACTION_QUEUE_MAX) {
                send(actor, "You can't plan that far ahead.")
                return false
            }
            actor.actionQueue.addLast(QueuedAction(def, verb, args))
        }
    }
    return true
}

/** The `act` verb: a mob issues a command line exactly as a player would (SPEC §4.5, §5.8). */
fun World.act(mob: Mob, line: String): Boolean = submit(mob, line)

/** Per-session fast-lane rate limit: capacity 20, refilling 5 tokens/s (SPEC §5.2). */
class TokenBucket(
    private val capacity: Double = 20.0,
    private val refillPerSecond: Double = 5.0,
    startNanos: Long,
) {
    private var tokens = capacity
    private var lastNanos = startNanos

    fun tryTake(nowNanos: Long): Boolean {
        if (nowNanos > lastNanos) {
            tokens = minOf(capacity, tokens + (nowNanos - lastNanos) / 1e9 * refillPerSecond)
            lastNanos = nowNanos
        }
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}
