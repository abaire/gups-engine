package gups.core.world

import gups.core.ACTION_QUEUE_MAX
import gups.core.Role
import gups.core.model.Actor
import gups.core.model.Direction
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.QueuedAction

/** Fast-lane commands run immediately; tick-lane commands queue for action pulses. */
enum class Lane {
  FAST,
  TICK,
}

data class CommandCall(val world: World, val actor: Actor, val verb: String, val args: String)

/**
 * A command. Only players whose role is at least [role] can use it; to everyone else it doesn't
 * exist.
 */
sealed class CommandDef(val name: String, val aliases: List<String>, val role: Role) {
  abstract val lane: Lane

  fun allows(actor: Actor): Boolean =
    role == Role.PLAYER || (actor is Player && actor.role.atLeast(role))

  /** This command with [extra] appended to its aliases. */
  internal abstract fun withAliases(extra: List<String>): CommandDef
}

class FastCommand(
  name: String,
  aliases: List<String> = emptyList(),
  role: Role = Role.PLAYER,
  val run: (CommandCall) -> Unit,
) : CommandDef(name, aliases, role) {
  override val lane: Lane
    get() = Lane.FAST

  override fun withAliases(extra: List<String>) = FastCommand(name, aliases + extra, role, run)
}

/**
 * [plan] parses arguments into an [Action] when the command is popped, or returns null after
 * messaging.
 */
class TickCommand(
  name: String,
  aliases: List<String> = emptyList(),
  role: Role = Role.PLAYER,
  val plan: (CommandCall) -> Action?,
) : CommandDef(name, aliases, role) {
  override val lane: Lane
    get() = Lane.TICK

  override fun withAliases(extra: List<String>) = TickCommand(name, aliases + extra, role, plan)
}

/**
 * A world's shorthands for engine commands, from `MODULE.gups`: [words] maps an alias such as `l`
 * to a command name, and [prefixes] maps a single character such as `'` to the command that the
 * rest of the line is passed to.
 */
data class CommandAliases(
  val words: Map<String, String> = emptyMap(),
  val prefixes: Map<Char, String> = emptyMap(),
) {
  /** Why these aliases can't be used, such as an alias naming a command that doesn't exist. */
  fun problems(): List<String> = buildList {
    val engine = EngineCommands.all().map { it.name }.filter { !it.startsWith("@") }.toSet()
    for ((alias, target) in words.toSortedMap()) {
      when {
        !WORD.matches(alias) -> add("alias '$alias' must match [a-z][a-z0-9_]*")
        alias in engine -> add("alias '$alias' is already an engine command")
        Direction.parse(alias) in Direction.STANDARD -> add("alias '$alias' is a direction")
        target !in engine -> add("alias '$alias' names '$target', which is not an engine command")
      }
    }
    for ((prefix, target) in prefixes.toSortedMap()) {
      when {
        prefix.isLetterOrDigit() || prefix.isWhitespace() || prefix == '@' ->
          add("prefix '$prefix' must not be a letter, digit, space or @")
        target !in engine -> add("prefix '$prefix' names '$target', which is not an engine command")
      }
    }
  }

  companion object {
    val NONE = CommandAliases()
    private val WORD = Regex("[a-z][a-z0-9_]*")
  }
}

/**
 * Verb lookup: engine commands (with the world's aliases, including exits), global commands, then
 * the room's and nearby items' `commands`. Exact names and aliases win over unique prefixes; a
 * prefix of the names or aliases of more than one command resolves to nothing.
 */
class CommandRegistry(
  /** The world's aliases; [CommandAliases.problems] must be empty. */
  val aliases: CommandAliases = CommandAliases.NONE
) {
  private val engine: List<CommandDef> =
    EngineCommands.all().map { def ->
      val extra = aliases.words.filterValues { it == def.name }.keys.sorted()
      if (extra.isEmpty()) def else def.withAliases(extra)
    }
  private val global = mutableListOf<CommandDef>()
  private val tools = mutableListOf<CommandDef>()
  private val plugins = mutableListOf<CommandDef>()
  private val moves = HashMap<Direction, TickCommand>()

  /** Registers a global command, as privileged packages do with `global_command`. */
  fun registerGlobal(command: CommandDef) {
    require(!isPluginCommand(command.name)) {
      "Global command ${command.name} clashes with a plugin command"
    }
    require(global.none { it.name == command.name }) {
      "Global command ${command.name} already registered"
    }
    global += command
  }

  /**
   * Registers a plugin command. It may not shadow an engine command, a direction, or another
   * plugin's command; reloads keep it.
   */
  fun registerPlugin(command: CommandDef) {
    for (word in listOf(command.name) + command.aliases) {
      val w = word.lowercase()
      require(engine.none { it.name == w || w in it.aliases }) {
        "plugin command '$w' clashes with an engine command"
      }
      require(Direction.parse(w) !in Direction.STANDARD) {
        "plugin command '$w' clashes with a direction"
      }
      require(plugins.none { it.name == w || w in it.aliases }) {
        "plugin command '$w' is already registered"
      }
    }
    plugins += command
  }

  fun isPluginCommand(name: String): Boolean = plugins.any { it.name == name || name in it.aliases }

  /** Drops every global command, before a reload registers them again. */
  fun clearGlobals() = global.clear()

  /**
   * Registers a builder or instructor `@` command that the engine provides outside `core`, such as
   * `@trace` and `@eval` from the script host.
   */
  fun registerTool(command: CommandDef) {
    require(command.name.startsWith("@")) { "Tool commands start with @: ${command.name}" }
    require((engine + tools).none { it.name == command.name }) {
      "Tool command ${command.name} already registered"
    }
    tools += command
  }

  val engineCommands: List<CommandDef>
    get() = engine

  val globalCommands: List<CommandDef>
    get() = global

  val pluginCommands: List<CommandDef>
    get() = plugins

  val toolCommands: List<CommandDef>
    get() = (engine + tools).filter { it.name.startsWith("@") }

  fun resolve(actor: Actor, verb: String): CommandDef? {
    val v = verb.lowercase()
    if (v.startsWith("@")) {
      val all = (engine + tools).filter { it.name.startsWith("@") && it.allows(actor) }
      return exact(all, v) ?: prefix(all, v)
    }
    exact(engine, v)?.let {
      return it
    }
    val dir = Direction.parse(v)
    if (dir in actor.room.exits || dir in Direction.STANDARD)
      return moves.getOrPut(dir) { EngineCommands.move(dir) }
    exact(global, v)?.let {
      return it
    }
    // A plugin command above the actor's role doesn't exist for them.
    val usable = plugins.filter { it.allows(actor) }
    exact(usable, v)?.let {
      return it
    }
    actor.room.proto.commands[v]?.let {
      return zoneCommand(v, actor.room, it)
    }
    // Equipped items, then inventory, then the ground; contents of containers grant nothing.
    val layers =
      listOf(actor.equipmentIndex.values, actor.inventoryIndex.values, actor.room.itemIndex.values)
    for (layer in layers) {
      for (item in layer.sortedBy { it.id }) item.proto.commands[v]?.let {
        return zoneCommand(v, item, it)
      }
    }
    return prefix((engine + global + usable).filter { !it.name.startsWith("@") }, v)
  }

  private fun exact(defs: List<CommandDef>, v: String) =
    defs.firstOrNull { it.name == v || v in it.aliases }

  /** The one command whose name or an alias starts with [v], or null if none or several do. */
  private fun prefix(defs: List<CommandDef>, v: String) =
    defs
      .filter { d -> (listOf(d.name) + d.aliases).any { it.startsWith(v) } }
      .distinct()
      .singleOrNull()

  private fun zoneCommand(verb: String, owner: Entity, handler: ZoneCommand) =
    TickCommand(verb) { call ->
      object : Action {
        override fun validate(w: World, actor: Actor): String? {
          val here =
            when (owner) {
              is Item -> w.isPresent(owner, w.roomOf(actor))
              else -> w.roomOf(actor) === owner
            }
          return if (here) null else "Nothing happens."
        }

        override fun resolve(w: World, actor: Actor): Int {
          try {
            handler.run(w, actor, call.args, owner)
          } catch (e: Exception) {
            // Players see only this for a failed zone command.
            w.diagnose("script", "command '$verb' on $owner failed: ${e.message}", e)
            w.send(actor, "Nothing happens.")
          }
          return 0
        }
      }
    }
}

/**
 * Splits `verb args`; a leading character in [prefixes] (such as `'` for `say`) stands for its
 * command.
 */
internal fun parseLine(
  line: String,
  prefixes: Map<Char, String> = emptyMap(),
): Pair<String, String>? {
  val t = line.trim()
  if (t.isEmpty()) return null
  prefixes[t[0]]?.let {
    return it to t.substring(1).trim()
  }
  val space = t.indexOfFirst { it.isWhitespace() }
  return if (space < 0) t to "" else t.substring(0, space) to t.substring(space + 1).trim()
}

/** Classifies and admits one line for [actor]: fast-lane runs now, tick-lane queues. */
fun World.submit(actor: Actor, line: String): Boolean {
  val (verb, args) = parseLine(line, commands.aliases.prefixes) ?: return false
  val def = commands.resolve(actor, verb)
  if (def == null) {
    send(actor, "Huh?")
    return false
  }
  // Tick-lane commands and says (never tells) feed repro bundles.
  if (def is TickCommand || def.name == "say")
    inputs += InputRecord(pulse, actor.id, actor.name, actor.room.label, line)
  when (def) {
    is FastCommand ->
      guarded("command '$verb' of $actor") { def.run(CommandCall(this, actor, verb, args)) }
    is TickCommand -> {
      if (actor.isIncapacitated) {
        send(actor, "You can't do that while incapacitated.")
        return false
      }
      if (actor.actionQueue.size >= ACTION_QUEUE_MAX) {
        send(actor, "You can't plan that far ahead.")
        return false
      }
      actor.actionQueue.addLast(QueuedAction(def, verb, args))
    }
  }
  return true
}

/** The `act` verb: a mob issues a command line exactly as a player would. */
fun World.act(mob: Mob, line: String): Boolean = submit(mob, line)

/** Per-session fast-lane rate limit: capacity 20, refilling 5 tokens/s. */
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
