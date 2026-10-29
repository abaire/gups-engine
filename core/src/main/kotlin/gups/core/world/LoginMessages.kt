package gups.core.world

/** Login and character-menu text, which `auth` reads through the catalog. */
object LoginMsg : MsgGroup(null) {
  val WELCOME = msg("login.welcome", "Welcome to GUPS.")
  val PROMPT =
    msg(
      "login.prompt",
      "By what name do you wish to be known? (login, or \"new\" with an invite code) ",
    )
  val ACCOUNT_GONE = msg("login.account_gone", "That account no longer exists.")
  val ACCOUNT_DISABLED = msg("login.account_disabled", "This account is disabled.")
  val ACCOUNT_NOW_DISABLED = msg("login.account_now_disabled", "This account has been disabled.")
  val TOO_SLOW = msg("login.too_slow", "You took too long. Goodbye.")
  val PASSWORD_PROMPT = msg("login.password_prompt", "Password: ")
  val PASSWORD_AGAIN = msg("login.password_again", "Password again: ")
  val NEW_PASSWORD_PROMPT = msg("login.new_password_prompt", "New password: ")
  val NEW_PASSWORD_AGAIN = msg("login.new_password_again", "New password again: ")
  val OLD_PASSWORD_PROMPT = msg("login.old_password_prompt", "Current password: ")
  val TOO_MANY_LOGINS =
    msg("login.too_many_logins", "Too many failed logins. Try again in {wait}.", "wait")
  val WRONG = msg("login.wrong", "Wrong login or password.")
  val TOO_MANY_WRONG = msg("login.too_many_wrong", "Too many wrong passwords. Goodbye.")
  val CODE_PROMPT_OPEN =
    msg("login.code_prompt_open", "Invite code (or press Enter to sign up without one): ")
  val CODE_PROMPT = msg("login.code_prompt", "Invite or setup code: ")
  val NEW_LOGIN_PROMPT =
    msg("login.new_login_prompt", "Choose a login (3 to 24 characters: a-z, 0-9, _ . -): ")
  val TOO_MANY_ATTEMPTS =
    msg("login.too_many_attempts", "Too many failed attempts. Try again in {wait}.", "wait")
  val BAD_CODE = msg("login.bad_code", "That code isn't valid, or it has expired.")
  val SETUP_CODE =
    msg(
      "login.setup_code",
      "This code sets up the account '{login}'. Choose a password of at least {min} characters.",
      "login,min",
    )
  val LOGIN_TAKEN = msg("login.login_taken", "That login is taken.")
  val CHOOSE_PASSWORD =
    msg("login.choose_password", "Choose a password of at least {min} characters.", "min")
  val MISMATCH = msg("login.mismatch", "The passwords don't match.")
  val ACCOUNT_READY = msg("login.account_ready", "Your account '{login}' is ready.", "login")
  val CODE_USED = msg("login.code_used", "That code was used or ran out in the meantime.")
  val LOGIN_TAKEN_MEANWHILE =
    msg("login.login_taken_meanwhile", "Someone took that login in the meantime.")
  val NO_CHARACTERS = msg("login.no_characters", "You have no characters yet.")
  val CHARACTERS = msg("login.characters", "Your characters: {names}.", "names", "names")
  val MENU =
    msg(
      "login.menu",
      "{characters}  Type a name to play, \"new\" to create, or \"password\" to change yours.",
      "characters",
    )
  val MENU_PROMPT = msg("login.menu_prompt", "> ")
  val NAME_PROMPT = msg("login.name_prompt", "What will your new character be called? ")
  val NO_SUCH_CHARACTER =
    msg("login.no_such_character", "You have no character called {name}.", "name")
  val ALREADY_PLAYING = msg("login.already_playing", "You're already playing {name}.", "name")
  val CANT_FIND = msg("login.cant_find", "{name} can't be found.", "name")
  val CHARACTER_LIMIT =
    msg(
      "login.character_limit",
      "You already have {count} characters, the most an account may have.",
      "count",
    )
  val NAME_INVALID = msg("login.name_invalid", "Names are 2 to 16 letters.")
  val NAME_RESERVED = msg("login.name_reserved", "That name is reserved.")
  val NAME_TAKEN = msg("login.name_taken", "That name is taken.")
  val PASSWORD_CHANGED = msg("login.password_changed", "Your password has been changed.")
  val NOT_CURRENT = msg("login.not_current", "That isn't your current password.")
  val ERROR = msg("login.error", "Something went wrong on the server. Please try again.")
  val ONE_SECOND = msg("login.one_second", "1 second")
  val SECONDS = msg("login.seconds", "{n} seconds", "n")
  val LOGIN_INVALID =
    msg(
      "login.login_invalid",
      "Logins are 3 to 24 characters: lowercase letters, digits, '_', '.' and '-'.",
    )
  val LOGIN_RESERVED = msg("login.login_reserved", "That login is reserved.")
  val PASSWORD_SHORT =
    msg("login.password_short", "Passwords need at least {min} characters.", "min")
  val PASSWORD_IS_LOGIN = msg("login.password_is_login", "Your password can't be your login.")
  val DEV_LOCAL_ONLY =
    msg(
      "login.dev.local_only",
      "This development server only accepts connections from this machine.",
    )
  val DEV_WELCOME =
    msg("login.dev.welcome", "Welcome to GUPS (development server). You are the local instructor.")
  val DEV_PROMPT = msg("login.dev.prompt", "By what name do you wish to be known? ")
  val TOO_MUCH_INPUT = msg("session.too_much_input", "Too much input.")

  val CREATION_HINT =
    msg(
      "login.creation.hint",
      "Type a number or a name, \"help <name>\" to learn more, \"back\" or \"cancel\".",
    )
  val CREATION_PROMPT = msg("login.creation.prompt", "> ")
  val CREATION_NO_SUCH_OPTION =
    msg("login.creation.no_such_option", "There's no {answer} to choose here.", "answer")
  val CREATION_CONFIRM =
    msg(
      "login.creation.confirm",
      "{name}: {answers}. Create this character? (yes, back or cancel)",
      "name,answers",
    )
  val CREATION_PROVIDER_FAILED =
    msg(
      "login.creation.provider_failed",
      "Character creation isn't working right now; please tell an instructor.",
    )
  val CREATION_HAS_CHOICES =
    msg("login.creation.has_choices", "{name} has choices to make before playing.", "name")
}

/** The text of [this] in [world]'s words, or its default when there is no world. */
fun Msg.render(world: World?, vararg args: Pair<String, Any?>): String =
  world?.msg(this, *args) ?: render(emptyMap(), *args)

/** The text of [this] with the world's [overrides] (`MESSAGES.gups` texts by id) applied. */
fun Msg.render(overrides: Map<String, String>, vararg args: Pair<String, Any?>): String =
  Messages.fill(overrides[id] ?: default, args.associate { (k, v) -> k to v.toString() })
