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

/** Descriptor for a command category. */
data class CategoryDef(
  val id: String,
  val title: String =
    id.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
  val order: Int? = null,
  val help: String? = null,
)

/** Standard command category constants for grouping in help output. */
object CommandCategory {
  const val COMMUNICATION = "Communication"
  const val MOVEMENT = "Movement"
  const val INFORMATION = "Information"
  const val ITEMS = "Items"
  const val COMBAT = "Combat"
  const val SKILLS = "Skills"
  const val GAME = "Game"
  const val GENERAL = "General"
  const val SPECIAL = "Special"

  const val DEFAULT_ORDER = 800

  val STANDARD =
    listOf(
      CategoryDef("communication", COMMUNICATION, order = 100),
      CategoryDef("movement", MOVEMENT, order = 200),
      CategoryDef("information", INFORMATION, order = 300),
      CategoryDef("items", ITEMS, order = 400),
      CategoryDef("combat", COMBAT, order = 500),
      CategoryDef("skills", SKILLS, order = 600),
      CategoryDef("game", GAME, order = 700),
      CategoryDef("general", GENERAL, order = DEFAULT_ORDER),
      CategoryDef("special", SPECIAL, order = Int.MAX_VALUE),
    )

  val STANDARD_ORDER = listOf(COMMUNICATION, MOVEMENT, INFORMATION, ITEMS, COMBAT, SKILLS, GAME)
}

/**
 * A command. Only players whose role is at least [role] can use it; to everyone else it doesn't
 * exist.
 */
sealed class CommandDef(
  val name: String,
  val aliases: List<String> = emptyList(),
  val role: Role = Role.PLAYER,
  val category: String =
    if (role != Role.PLAYER || name.startsWith("@")) CommandCategory.SPECIAL
    else CommandCategory.GENERAL,
  val help: String = "",
) {
  abstract val lane: Lane

  fun allows(actor: Actor): Boolean =
    role == Role.PLAYER || (actor is Player && actor.role.atLeast(role))

  open fun detailedHelp(): String = help

  /** This command with [extra] appended to its aliases. */
  internal abstract fun withAliases(extra: List<String>): CommandDef

  /** This command under the name [to]. */
  internal abstract fun renamed(to: String): CommandDef
}

class FastCommand(
  name: String,
  aliases: List<String> = emptyList(),
  role: Role = Role.PLAYER,
  category: String =
    if (role != Role.PLAYER || name.startsWith("@")) CommandCategory.SPECIAL
    else CommandCategory.GENERAL,
  help: String = "",
  val run: (CommandCall) -> Unit,
) : CommandDef(name, aliases, role, category, help) {
  override val lane: Lane
    get() = Lane.FAST

  override fun withAliases(extra: List<String>) =
    FastCommand(name, aliases + extra, role, category, help, run)

  override fun renamed(to: String) = FastCommand(to, aliases, role, category, help, run)
}

/**
 * [plan] parses arguments into an [Action] when the command is popped, or returns null after
 * messaging.
 */
class TickCommand(
  name: String,
  aliases: List<String> = emptyList(),
  role: Role = Role.PLAYER,
  category: String =
    if (role != Role.PLAYER || name.startsWith("@")) CommandCategory.SPECIAL
    else CommandCategory.GENERAL,
  help: String = "",
  val plan: (CommandCall) -> Action?,
) : CommandDef(name, aliases, role, category, help) {
  override val lane: Lane
    get() = Lane.TICK

  override fun withAliases(extra: List<String>) =
    TickCommand(name, aliases + extra, role, category, help, plan)

  override fun renamed(to: String) = TickCommand(to, aliases, role, category, help, plan)
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
  /**
   * Why these aliases can't be used, such as an alias naming a command that doesn't exist. [engine]
   * is the world's command names, renames applied.
   */
  fun problems(
    engine: Set<String> = CommandRegistry.names(Features.ALL, emptyMap())
  ): List<String> = buildList {
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

/** An engine command as the docs list it: its [name] in the world, and the built-in one. */
data class CommandInfo(val name: String, val builtIn: String, val lane: Lane, val feature: Feature?)

/**
 * Verb lookup: engine commands (with the world's aliases, including exits), global commands, then
 * the room's and nearby items' `commands`. Exact names and aliases win over unique prefixes; a
 * prefix of the names or aliases of more than one command resolves to nothing.
 */
class CommandRegistry(
  /** The world's aliases; [CommandAliases.problems] must be empty. */
  val aliases: CommandAliases = CommandAliases.NONE,
  /** The features the world has on; only their commands exist. */
  val features: Features = Features.ALL,
  /** Engine command renames, old name to new; [renameProblems] must be empty. */
  val rename: Map<String, String> = emptyMap(),
) {
  private val engine: List<CommandDef> =
    base(features).map { def ->
      val named = rename[def.name]?.let(def::renamed) ?: def
      val extra = aliases.words.filterValues { it == named.name }.keys.sorted()
      if (extra.isEmpty()) named else named.withAliases(extra)
    }
  private val global = mutableListOf<CommandDef>()
  private val tools = mutableListOf<CommandDef>()
  private val plugins = mutableListOf<CommandDef>()
  private val moves = HashMap<Direction, TickCommand>()
  private val categories = mutableMapOf<String, CategoryDef>()

  init {
    for (cat in CommandCategory.STANDARD) {
      categories[cat.id.lowercase()] = cat
      categories[cat.title.lowercase()] = cat
    }
  }

  /** Registers a category descriptor with its display title, sort weight, and optional help. */
  fun registerCategory(category: CategoryDef) {
    categories[category.id.lowercase()] = category
    categories[category.title.lowercase()] = category
  }

  /** Looks up a category descriptor by its identifier or display title. */
  fun category(nameOrId: String): CategoryDef? = categories[nameOrId.lowercase()]

  /**
   * Looks up a category descriptor, or creates an unweighted default descriptor if unregistered.
   */
  fun categoryOrImplicit(nameOrId: String): CategoryDef =
    categories.getOrPut(nameOrId.lowercase()) {
      CategoryDef(
        id = nameOrId.lowercase(),
        title =
          nameOrId.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
      )
    }

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

  /** The name [builtIn] goes by in this world: its rename, or itself. */
  fun nameOf(builtIn: String): String = rename[builtIn] ?: builtIn

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
      return moves.getOrPut(dir) { KernelCommands.move(dir) }
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

  companion object {
    /** Each group of engine commands with the feature that owns it, or null for the kernel. */
    private fun groups(): List<Pair<Feature?, List<CommandDef>>> =
      listOf(
        null to KernelCommands.all(),
        Feature.COMBAT to CombatCommands.all(),
        Feature.TRACK to TrackCommands.all(),
        Feature.SKILLS to SkillCommands.all(),
      )

    /** The command groups of the kernel and the enabled [features]. */
    private fun enabled(features: Features) =
      groups().filter { (feature, _) -> feature == null || feature in features }

    /** The commands of the kernel and the enabled [features], under their built-in names. */
    internal fun base(features: Features): List<CommandDef> =
      enabled(features).flatMap { it.second }

    /** Names of the non-`@` engine commands of a world with [features], [rename]s applied. */
    fun names(features: Features, rename: Map<String, String>): Set<String> =
      base(features)
        .map { it.name }
        .filter { !it.startsWith("@") }
        .mapTo(LinkedHashSet()) { rename[it] ?: it }

    /** The non-`@` engine commands of a world, renames applied, for the docs. */
    fun describe(features: Features, rename: Map<String, String>): List<CommandInfo> =
      enabled(features).flatMap { (feature, defs) ->
        defs
          .filter { !it.name.startsWith("@") }
          .map { d -> CommandInfo(rename[d.name] ?: d.name, d.name, d.lane, feature) }
      }

    /** Names of the non-`@` engine commands before renaming. */
    fun builtInNames(features: Features): Set<String> = names(features, emptyMap())

    private val WORD = Regex("[a-z][a-z0-9_]*")

    /** Why [rename] can't be applied to a world with [features] and [aliases]. */
    fun renameProblems(
      features: Features,
      rename: Map<String, String>,
      aliases: CommandAliases = CommandAliases.NONE,
    ): List<String> = buildList {
      val builtIn = builtInNames(features)
      val targets = rename.values.groupingBy { it }.eachCount()
      for ((old, new) in rename.toSortedMap()) {
        when {
          old !in builtIn -> add("rename '$old' is not an engine command of this world")
          !WORD.matches(new) -> add("rename '$old' to '$new': must match [a-z][a-z0-9_]*")
          Direction.parse(new) in Direction.STANDARD ->
            add("rename '$old' to '$new': '$new' is a direction")
          new in builtIn -> add("rename '$old' to '$new': '$new' is already an engine command")
          new in aliases.words -> add("rename '$old' to '$new': '$new' is already an alias")
          targets.getValue(new) > 1 -> add("rename to '$new' is given for more than one command")
        }
      }
    }
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
          return if (here) null else w.msg(KernelMsg.NOTHING_HAPPENS)
        }

        override fun resolve(w: World, actor: Actor): Int {
          try {
            handler.run(w, actor, call.args, owner)
          } catch (e: Exception) {
            // Players see only this for a failed zone command.
            w.diagnose("script", "command '$verb' on $owner failed: ${e.message}", e)
            w.tell(actor, KernelMsg.NOTHING_HAPPENS)
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
    tell(actor, KernelMsg.HUH)
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
        tell(actor, KernelMsg.INCAPACITATED)
        return false
      }
      if (actor.actionQueue.size >= ACTION_QUEUE_MAX) {
        tell(actor, KernelMsg.QUEUE_FULL)
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
