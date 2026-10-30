package gups.auth

import gups.core.model.Direction
import gups.core.world.LoginMsg
import gups.core.world.World
import gups.core.world.render

/**
 * Validation rules and reserved word dictionaries for account logins, character names, and
 * passwords.
 *
 * ## Login Rules
 * - Logins must consist of 3 to 24 characters matching `[a-z0-9_.-]`.
 * - Compared case-insensitively.
 * - Reserved logins: `"new"` (reserved for registration flow at login prompt).
 *
 * ## Character Name Rules & Reserved Names
 * - Names must consist of 2 to 16 letters `[A-Za-z]`, automatically formatted in title case.
 * - Cannot collide with [BUILTIN_RESERVED] engine words (`"self"`, `"me"`, `"all"`, `"someone"`,
 *   `"here"`, `"new"`, `"password"`, `"quit"`, `"guest"`, `"instructor"`, `"admin"`).
 * - Cannot collide with standard cardinal or ordinal directions or their short abbreviations (`n`,
 *   `s`, `e`, `w`, etc.).
 * - Cannot collide with world command names or aliases.
 * - Cannot collide with keywords or name tokens of any loaded mob prototype, ensuring players
 *   cannot impersonate NPCs.
 *
 * ## Password Policy
 * - Minimum length: 10 characters ([MIN_PASSWORD]).
 * - Cannot be identical to the account login name (case-insensitive).
 */
object Names {
  private val LOGIN = Regex("[a-z0-9_.-]{3,24}")
  private val CHARACTER = Regex("[A-Za-z]{2,16}")

  /** Minimum allowable character length for user passwords (10 characters). */
  const val MIN_PASSWORD = 10

  /**
   * Words no character may be called: engine words, the words of the login menus, and every
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
  fun loginProblem(login: String, messages: Map<String, String> = emptyMap()): String? =
    when {
      !LOGIN.matches(login) -> LoginMsg.LOGIN_INVALID.render(messages)
      login in RESERVED_LOGINS -> LoginMsg.LOGIN_RESERVED.render(messages)
      else -> null
    }

  /** [input] as a character name, capitalized like "Ann", or null if it isn't 2 to 16 letters. */
  fun characterName(input: String): String? =
    input.trim().takeIf { CHARACTER.matches(it) }?.lowercase()?.replaceFirstChar { it.uppercase() }

  /**
   * Every reserved character name in [world], lowercase: the built-in list, [extra], the world's
   * command names, and the keywords and name words of every mob prototype, so no player can pass
   * for an NPC.
   */
  fun reserved(world: World, extra: Collection<String>): Set<String> = buildSet {
    addAll(BUILTIN_RESERVED)
    extra.forEach { add(it.lowercase()) }
    val c = world.commands
    for (def in c.engineCommands + c.globalCommands + c.toolCommands) {
      for (n in listOf(def.name) + def.aliases) add(n.removePrefix("@").lowercase())
    }
    for (mob in world.protos.mobs.values) {
      mob.keywords.forEach { add(it.lowercase()) }
      mob.name.split(Regex("[^A-Za-z]+")).filter { it.isNotEmpty() }.forEach { add(it.lowercase()) }
    }
  }

  /** Why [password] is too weak for [login], or null if it will do. */
  fun passwordProblem(
    password: String,
    login: String,
    messages: Map<String, String> = emptyMap(),
  ): String? =
    when {
      password.length < MIN_PASSWORD ->
        LoginMsg.PASSWORD_SHORT.render(messages, "min" to MIN_PASSWORD)
      password.equals(login, ignoreCase = true) -> LoginMsg.PASSWORD_IS_LOGIN.render(messages)
      else -> null
    }
}
