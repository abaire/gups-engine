package gups.core.world

import gups.core.model.Player

/** `help`: the commands grouped by category, or the details of one command or category. */
internal object HelpCommand {
  private val CATEGORY_ORDER =
    compareBy<CategoryDef>(
      { if (it.id.equals(CommandCategory.SPECIAL, ignoreCase = true)) 1 else 0 },
      { it.order ?: CommandCategory.DEFAULT_ORDER },
      { it.title.lowercase() },
    )

  fun help(c: CommandCall) {
    val target = c.args.trim()
    c.world.send(c.actor, if (target.isEmpty()) helpIndex(c) else detailedHelp(c, target))
  }

  private fun World.allCommandDefs(): List<CommandDef> =
    (commands.engineCommands +
        commands.globalCommands +
        commands.pluginCommands +
        commands.toolCommands)
      .distinct()

  private fun helpIndex(c: CommandCall): String {
    val w = c.world
    val byCategory =
      w.allCommandDefs()
        .filter { it.allows(c.actor) }
        .groupBy { w.commands.categoryOrImplicit(it.category) }
    val movement = w.commands.categoryOrImplicit(CommandCategory.MOVEMENT)
    val categories =
      (byCategory.keys + (if (c.actor is Player) setOf(movement) else emptySet()))
        .distinct()
        .sortedWith(CATEGORY_ORDER)
    return categories
      .mapNotNull { cat -> categoryLine(cat, byCategory[cat].orEmpty().map { it.name }.sorted()) }
      .joinToString("\n")
  }

  /** The index line for [cat], or null when it has no commands to list. */
  private fun categoryLine(cat: CategoryDef, names: List<String>): String? {
    val listed = names.joinToString(" ")
    return when {
      cat.id.equals(CommandCategory.MOVEMENT, ignoreCase = true) ->
        if (names.isNotEmpty()) "${cat.title}: $listed, and any exit name"
        else "${cat.title}: and any exit name"
      names.isNotEmpty() -> "${cat.title}: $listed"
      else -> null
    }
  }

  private fun detailedHelp(c: CommandCall, target: String): String {
    val word = target.substringBefore(' ').lowercase()
    return commandHelp(c, word)
      ?: categoryHelp(c, target, word)
      ?: "No help available for '$target'."
  }

  private fun commandHelp(c: CommandCall, word: String): String? {
    val commands = c.world.commands
    val prefixCommand = if (word.length == 1) commands.aliases.prefixes[word[0]] else null
    val def =
      prefixCommand?.let { commands.resolve(c.actor, it) }
        ?: commands.resolve(c.actor, word)
        ?: (if (!word.startsWith("@")) commands.resolve(c.actor, "@$word") else null)
        ?: return null
    val worldAliases = commands.aliases.words.filterValues { it == def.name }.keys
    val worldPrefixes =
      commands.aliases.prefixes.filterValues { it == def.name }.keys.map { it.toString() }
    val allAliases = (def.aliases + worldAliases + worldPrefixes).distinct().sorted()
    val aliasStr = if (allAliases.isEmpty()) "none" else allAliases.joinToString(", ")
    val details = def.detailedHelp().trim()
    return buildString {
      appendLine("Command: ${def.name}")
      appendLine("Aliases: $aliasStr")
      if (details.isNotEmpty()) {
        appendLine()
        append(details)
      }
    }
  }

  private fun categoryHelp(c: CommandCall, target: String, word: String): String? {
    val commands = c.world.commands
    val cat = commands.category(target) ?: commands.category(word) ?: return null
    val help = cat.help
    if (help.isNullOrBlank()) return null
    val inCategory =
      c.world.allCommandDefs().filter {
        it.category.equals(cat.id, ignoreCase = true) ||
          it.category.equals(cat.title, ignoreCase = true)
      }
    val allowed = inCategory.filter { it.allows(c.actor) }
    if (inCategory.isNotEmpty() && allowed.isEmpty()) return null
    val names = allowed.map { it.name }.sorted()
    return buildString {
      appendLine("Category: ${cat.title}")
      if (names.isNotEmpty()) appendLine("Commands: ${names.joinToString(" ")}")
      appendLine()
      append(help.trim())
    }
  }
}
