package gups.auth

import gups.core.model.Direction
import gups.core.world.World

/** Login, character-name and password rules (SPEC §3.5, §5.12). */
object Names {
  private val LOGIN = Regex("[a-z0-9_.-]{3,24}")
  private val CHARACTER = Regex("[A-Za-z]{2,16}")

  const val MIN_PASSWORD = 10

  /**
   * Words no character may be called (D-136): engine words, the words of the login menus, and every
   * direction with its abbreviation. Command names are added per world.
   */
  val BUILTIN_RESERVED: Set<String> = buildSet {
    addAll(
      listOf(
        "self",
        "me",
        "all",
        "someone",
        "here",
        "new",
        "password",
        "quit",
        "guest",
        "instructor",
        "admin",
      )
    )
    for (d in Direction.STANDARD) add(d.name)
    addAll(listOf("n", "e", "s", "w", "u", "d", "ne", "nw", "se", "sw"))
  }

  /** Logins the login prompt itself uses. */
  private val RESERVED_LOGINS = setOf("new")

  /** Why [login] can't be used, or null if it can. Logins are compared in lowercase. */
  fun loginProblem(login: String): String? =
    when {
      !LOGIN.matches(login) ->
        "Logins are 3 to 24 characters: lowercase letters, digits, '_', '.' and '-'."
      login in RESERVED_LOGINS -> "That login is reserved."
      else -> null
    }

  /** [input] as a character name, capitalized like "Ann", or null if it isn't 2 to 16 letters. */
  fun characterName(input: String): String? =
    input.trim().takeIf { CHARACTER.matches(it) }?.lowercase()?.replaceFirstChar { it.uppercase() }

  /**
   * Every reserved character name in [world], lowercase: the built-in list, [extra], and the
   * world's command names.
   */
  fun reserved(world: World, extra: Collection<String>): Set<String> = buildSet {
    addAll(BUILTIN_RESERVED)
    extra.forEach { add(it.lowercase()) }
    val c = world.commands
    for (def in c.engineCommands + c.globalCommands + c.toolCommands) {
      for (n in listOf(def.name) + def.aliases) add(n.removePrefix("@").lowercase())
    }
  }

  /** Why [password] is too weak for [login], or null if it will do. */
  fun passwordProblem(password: String, login: String): String? =
    when {
      password.length < MIN_PASSWORD -> "Passwords need at least $MIN_PASSWORD characters."
      password.equals(login, ignoreCase = true) -> "Your password can't be your login."
      else -> null
    }
}
