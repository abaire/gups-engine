package gups.auth

import gups.core.Account
import gups.core.ClientInputEvent
import gups.core.Label
import gups.core.PULSE_MS
import gups.core.Role
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.CreationAttempt
import gups.core.model.Player
import gups.core.world.CreationFlow
import gups.core.world.CreationOutcome
import gups.core.world.Diagnostic
import gups.core.world.KernelMsg
import gups.core.world.LoginHandler
import gups.core.world.LoginMsg
import gups.core.world.Session
import gups.core.world.World
import gups.core.world.choiceChanges
import gups.core.world.choicesRefusal
import gups.core.world.closeSession
import gups.core.world.creationStartRoom
import gups.core.world.enterGame
import gups.core.world.flush
import gups.core.world.handle
import gups.core.world.hasMissingChoices
import gups.core.world.msg
import gups.core.world.send
import gups.core.world.sendSession
import gups.core.world.session
import gups.core.world.setChoices
import gups.core.world.takeOver
import gups.core.world.unload
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Account login settings. */
data class LoginSettings(
  /** A new login can be made at the prompt without a code. */
  val openSignup: Boolean = false,
  /** Characters per account; instructors and admins have no limit. */
  val maxCharacters: Int = 5,
  /** Character names refused on top of [Names.BUILTIN_RESERVED] and the command names. */
  val reservedNames: Set<String> = emptySet(),
  /** Idle time at any login prompt that closes the connection. */
  val idleTimeoutPulses: Long = 60_000 / PULSE_MS,
  /** Wrong passwords (and bad codes) one connection may send before it is closed. */
  val maxWrongPasswords: Int = 3,
)

/** Lines a session may type ahead while a password check runs. */
private const val MAX_HELD_LINES = 20

/** The default login prompt; a world may reword it. */
val LOGIN_PROMPT: String
  get() = LoginMsg.PROMPT.default

/**
 * The account login of `--prod`: login and password, invite and setup codes, then character
 * selection and creation. A small state machine per session on the world thread. Anything that
 * touches the [AccountService] (the database, Argon2) runs on [background], and its result comes
 * back through [post] as a [WorldControlEvent.AuthResult]; until then the session's input is held.
 */
class AccountLogin(
  private val accounts: AccountService,
  private val start: Label,
  private val background: Executor,
  /** Delivers events to the world thread; a `WorldLoop`'s `submit` in a server. */
  private val post: (WorldControlEvent) -> Unit,
  private val settings: LoginSettings = LoginSettings(),
  private val limiter: LoginLimiter = LoginLimiter(),
) : LoginHandler {
  private enum class Step(val hidden: Boolean = false) {
    LOGIN,
    PASSWORD(hidden = true),
    CODE,
    NEW_LOGIN,
    NEW_PASSWORD(hidden = true),
    NEW_PASSWORD_AGAIN(hidden = true),
    MENU,
    NAME,
    CREATION,
    OLD_PASSWORD(hidden = true),
    CHANGE_PASSWORD(hidden = true),
    CHANGE_PASSWORD_AGAIN(hidden = true),
  }

  private class Conn(var lastActivity: Long) {
    var step = Step.LOGIN
    var login = ""
    var account: Account? = null

    /** The code being redeemed; null with open signup. */
    var code: String? = null

    /** The current password while changing it. */
    var oldPassword = ""
    var newPassword = ""
    var strikes = 0
    var pending = false
    val held = ArrayDeque<String>()
    var flow: CreationFlow? = null
    /** Attempt kept while a new character goes back to the name prompt or retries a taken name. */
    var attempt: CreationAttempt? = null
  }

  /** What [post] carries back: the work's continuation, for the connection that started it. */
  private class Done(val conn: Conn, val then: (World, Session) -> Unit)

  private val conns = HashMap<SessionId, Conn>()
  private val disabledAccounts = HashSet<Long>()

  /** Hashed for unknown logins, so a wrong login costs as much as a wrong password. */
  private val dummyHash: String by lazy { accounts.hasher.hash("no account has this password") }

  override fun connected(world: World, session: Session) {
    val c = Conn(world.pulse)
    conns[session.id] = c
    world.sendSession(session, world.msg(LoginMsg.WELCOME))
    val proven = session.account ?: return ask(world, session, c, Step.LOGIN, loginPrompt(world))
    // A web session token already proved the account: straight to the characters.
    later(world, session, c, { accounts.store.byId(proven) }) { w, s, a ->
      if (a == null || a.disabled || a.id in disabledAccounts) {
        w.sendSession(s, w.msg(if (a == null) LoginMsg.ACCOUNT_GONE else LoginMsg.ACCOUNT_DISABLED))
        return@later w.closeSession(s, w.msg(KernelMsg.QUIT_GOODBYE))
      }
      c.account = a
      c.login = a.login
      menu(w, s, c)
    }
  }

  override fun disconnected(world: World, session: SessionId) {
    conns.remove(session)
  }

  override fun accountDisabled(world: World, accountId: Long) {
    disabledAccounts.add(accountId)
    for ((id, c) in conns.toList()) {
      if (c.account?.id == accountId) {
        val s = world.session(id)
        if (s != null) {
          if (c.step.hidden) s.sink.hideInput(false)
          world.sendSession(s, world.msg(LoginMsg.ACCOUNT_NOW_DISABLED))
          world.closeSession(s, world.msg(KernelMsg.QUIT_GOODBYE))
        }
      }
    }
  }

  override fun accountEnabled(world: World, accountId: Long) {
    disabledAccounts.remove(accountId)
  }

  override fun pulse(world: World) {
    for ((id, c) in conns.toList()) {
      if (c.pending || world.pulse - c.lastActivity < settings.idleTimeoutPulses) continue
      val s = world.session(id)
      if (s == null) {
        conns.remove(id)
        continue
      }
      if (c.step.hidden) s.sink.hideInput(false)
      world.closeSession(s, world.msg(LoginMsg.TOO_SLOW))
    }
  }

  override fun input(world: World, session: Session, line: String) {
    val c = conns[session.id] ?: return
    if (c.pending) {
      if (c.held.size < MAX_HELD_LINES) c.held.addLast(line)
      return
    }
    c.lastActivity = world.pulse
    if (c.step.hidden) {
      session.sink.hideInput(false)
      // The client didn't echo the Enter either.
      world.sendSession(session, "")
    }
    val text = line.trim()
    when (c.step) {
      Step.LOGIN -> login(world, session, c, text)
      Step.PASSWORD -> password(world, session, c, line)
      Step.CODE -> code(world, session, c, text)
      Step.NEW_LOGIN -> newLogin(world, session, c, text)
      Step.NEW_PASSWORD -> {
        val problem = Names.passwordProblem(line, c.login, world.config.messages)
        if (problem != null) {
          world.sendSession(session, problem)
          ask(world, session, c, Step.NEW_PASSWORD, world.msg(LoginMsg.PASSWORD_PROMPT))
        } else {
          c.newPassword = line
          ask(world, session, c, Step.NEW_PASSWORD_AGAIN, world.msg(LoginMsg.PASSWORD_AGAIN))
        }
      }
      Step.NEW_PASSWORD_AGAIN -> redeem(world, session, c, line)
      Step.MENU -> menuChoice(world, session, c, text)
      Step.NAME -> createCharacter(world, session, c, text)
      Step.CREATION -> c.flow?.input(line)
      Step.OLD_PASSWORD -> {
        c.oldPassword = line
        ask(world, session, c, Step.CHANGE_PASSWORD, world.msg(LoginMsg.NEW_PASSWORD_PROMPT))
      }
      Step.CHANGE_PASSWORD -> {
        val problem = Names.passwordProblem(line, c.login, world.config.messages)
        if (problem != null) {
          world.sendSession(session, problem)
          ask(world, session, c, Step.CHANGE_PASSWORD, world.msg(LoginMsg.NEW_PASSWORD_PROMPT))
        } else {
          c.newPassword = line
          ask(world, session, c, Step.CHANGE_PASSWORD_AGAIN, world.msg(LoginMsg.NEW_PASSWORD_AGAIN))
        }
      }
      Step.CHANGE_PASSWORD_AGAIN -> changePassword(world, session, c, line)
    }
  }

  override fun completed(world: World, session: Session, outcome: Any) {
    val done = outcome as? Done ?: return
    val c = conns[session.id]
    if (c == null || c !== done.conn) return
    c.pending = false
    c.lastActivity = world.pulse
    done.then(world, session)
    // Lines typed while the work ran, in order: to the login, or to the game once playing.
    while (c.held.isNotEmpty() && !c.pending) {
      val line = c.held.removeFirst()
      when {
        session.player != null ->
          world.handle(ClientInputEvent(session.id, line, System.nanoTime()))
        conns[session.id] === c -> input(world, session, line)
        else -> break
      }
    }
  }

  // ---- Logging in --------------------------------------------------------------------------

  private fun login(world: World, s: Session, c: Conn, text: String) {
    when {
      text.isEmpty() -> ask(world, s, c, Step.LOGIN, loginPrompt(world))
      text.equals("new", ignoreCase = true) -> askCode(world, s, c)
      else -> {
        c.login = text.lowercase()
        ask(world, s, c, Step.PASSWORD, world.msg(LoginMsg.PASSWORD_PROMPT))
      }
    }
  }

  private fun password(world: World, s: Session, c: Conn, password: String) {
    val accountKey = LoginLimiter.accountKey(c.login)
    val ipKey = LoginLimiter.ipKey(s.remote)
    val locked = limiter.lockedFor(accountKey, ipKey)
    // Refused without hashing, so a locked-out attacker can't spend the server's CPU.
    if (locked > 0)
      return strike(
        world,
        s,
        c,
        world.msg(LoginMsg.TOO_MANY_LOGINS, "wait" to seconds(world, locked)),
      )
    val login = c.login
    later(
      world,
      s,
      c,
      {
        val a = accounts.store.byLogin(login)
        val hash = a?.pwHash
        if (hash == null) {
          accounts.hasher.verify(password, dummyHash)
          null
        } else {
          a.takeIf { accounts.hasher.verify(password, hash) }
        }
      },
    ) { w, session, a ->
      when {
        a == null -> {
          limiter.failure(accountKey, ipKey)
          strike(w, session, c, w.msg(LoginMsg.WRONG))
        }
        a.disabled || a.id in disabledAccounts -> {
          w.sendSession(session, w.msg(LoginMsg.ACCOUNT_DISABLED))
          w.closeSession(session, w.msg(KernelMsg.QUIT_GOODBYE))
        }
        else -> {
          limiter.success(accountKey)
          c.account = a
          c.login = a.login
          menu(w, session, c)
        }
      }
    }
  }

  /** A wrong password or code: [message], then the login prompt, or the door after too many. */
  private fun strike(world: World, s: Session, c: Conn, message: String) {
    c.strikes++
    world.sendSession(s, message)
    if (c.strikes >= settings.maxWrongPasswords)
      return world.closeSession(s, world.msg(LoginMsg.TOO_MANY_WRONG))
    c.account = null
    ask(world, s, c, Step.LOGIN, loginPrompt(world))
  }

  // ---- New accounts ------------------------------------------------------------------------

  private fun askCode(world: World, s: Session, c: Conn) =
    ask(
      world,
      s,
      c,
      Step.CODE,
      world.msg(if (settings.openSignup) LoginMsg.CODE_PROMPT_OPEN else LoginMsg.CODE_PROMPT),
    )

  private fun askNewLogin(world: World, s: Session, c: Conn) =
    ask(world, s, c, Step.NEW_LOGIN, world.msg(LoginMsg.NEW_LOGIN_PROMPT))

  private fun code(world: World, s: Session, c: Conn, text: String) {
    if (text.isEmpty()) {
      if (!settings.openSignup) return ask(world, s, c, Step.LOGIN, loginPrompt(world))
      c.code = null
      return askNewLogin(world, s, c)
    }
    val ipKey = LoginLimiter.ipKey(s.remote)
    val locked = limiter.lockedFor(ipKey)
    if (locked > 0)
      return strike(
        world,
        s,
        c,
        world.msg(LoginMsg.TOO_MANY_ATTEMPTS, "wait" to seconds(world, locked)),
      )
    val code = Codes.normalize(text)
    if (code == null) {
      limiter.failure(ipKey)
      return strike(world, s, c, world.msg(LoginMsg.BAD_CODE))
    }
    later(
      world,
      s,
      c,
      {
        val invite = accounts.validInvite(code)
        invite to invite?.accountId?.let { accounts.store.byId(it) }
      },
    ) { w, session, (invite, bound) ->
      when {
        invite == null || (invite.accountId != null && bound == null) -> {
          limiter.failure(ipKey)
          strike(w, session, c, w.msg(LoginMsg.BAD_CODE))
        }
        bound != null -> {
          c.code = code
          c.login = bound.login
          w.sendSession(
            session,
            w.msg(LoginMsg.SETUP_CODE, "login" to bound.login, "min" to Names.MIN_PASSWORD),
          )
          ask(w, session, c, Step.NEW_PASSWORD, w.msg(LoginMsg.PASSWORD_PROMPT))
        }
        else -> {
          c.code = code
          askNewLogin(w, session, c)
        }
      }
    }
  }

  private fun newLogin(world: World, s: Session, c: Conn, text: String) {
    val login = text.lowercase()
    Names.loginProblem(login, world.config.messages)?.let { problem ->
      world.sendSession(s, problem)
      return askNewLogin(world, s, c)
    }
    later(world, s, c, { accounts.store.byLogin(login) != null }) { w, session, taken ->
      if (taken) {
        w.sendSession(session, w.msg(LoginMsg.LOGIN_TAKEN))
        return@later askNewLogin(w, session, c)
      }
      c.login = login
      w.sendSession(session, w.msg(LoginMsg.CHOOSE_PASSWORD, "min" to Names.MIN_PASSWORD))
      ask(w, session, c, Step.NEW_PASSWORD, w.msg(LoginMsg.PASSWORD_PROMPT))
    }
  }

  private fun redeem(world: World, s: Session, c: Conn, again: String) {
    if (again != c.newPassword) {
      world.sendSession(s, world.msg(LoginMsg.MISMATCH))
      return ask(world, s, c, Step.NEW_PASSWORD, world.msg(LoginMsg.PASSWORD_PROMPT))
    }
    val (code, login, password) = Triple(c.code, c.login, c.newPassword)
    c.newPassword = ""
    later(world, s, c, { accounts.redeem(code, login, password) }) { w, session, r ->
      when (r) {
        is Redeemed.Ok -> {
          if (r.account.disabled || r.account.id in disabledAccounts) {
            w.sendSession(session, w.msg(LoginMsg.ACCOUNT_DISABLED))
            w.closeSession(session, w.msg(KernelMsg.QUIT_GOODBYE))
            return@later
          }
          c.account = r.account
          c.login = r.account.login
          w.sendSession(session, w.msg(LoginMsg.ACCOUNT_READY, "login" to r.account.login))
          menu(w, session, c)
        }
        Redeemed.BadCode -> {
          w.sendSession(session, w.msg(LoginMsg.CODE_USED))
          ask(w, session, c, Step.LOGIN, loginPrompt(world))
        }
        Redeemed.LoginTaken -> {
          w.sendSession(session, w.msg(LoginMsg.LOGIN_TAKEN_MEANWHILE))
          askNewLogin(w, session, c)
        }
      }
    }
  }

  // ---- The character menu ------------------------------------------------------------------

  private fun menu(world: World, s: Session, c: Conn) {
    val names = characters(world, c.account!!)
    val list =
      if (names.isEmpty()) world.msg(LoginMsg.NO_CHARACTERS)
      else world.msg(LoginMsg.CHARACTERS, "names" to names.joinToString(", "))
    world.sendSession(s, world.msg(LoginMsg.MENU, "characters" to list))
    ask(world, s, c, Step.MENU, world.msg(LoginMsg.MENU_PROMPT))
  }

  private fun again(world: World, s: Session, c: Conn, message: String) {
    world.sendSession(s, message)
    menu(world, s, c)
  }

  /** The account's characters: saved ones, and any in the world not saved yet. */
  private fun characters(world: World, a: Account): List<String> =
    (world.persistence.characters(a.id) +
        world.players.filter { it.accountId == a.id }.map { it.name })
      .distinctBy { it.lowercase() }

  /** The account's character in the world, link-dead included: an account plays one at a time. */
  private fun playing(world: World, a: Account): Player? =
    world.players.firstOrNull { it.accountId == a.id }

  private fun menuChoice(world: World, s: Session, c: Conn, text: String) {
    val a = c.account!!
    when {
      text.isEmpty() -> menu(world, s, c)
      text.equals("new", ignoreCase = true) -> {
        val why =
          cantCreate(world, a)
            ?: return ask(world, s, c, Step.NAME, world.msg(LoginMsg.NAME_PROMPT))
        again(world, s, c, why)
      }
      text.equals("password", ignoreCase = true) ->
        ask(world, s, c, Step.OLD_PASSWORD, world.msg(LoginMsg.OLD_PASSWORD_PROMPT))
      text.equals("quit", ignoreCase = true) ->
        world.closeSession(s, world.msg(KernelMsg.QUIT_GOODBYE))
      else -> play(world, s, c, a, text)
    }
  }

  private fun play(world: World, s: Session, c: Conn, a: Account, text: String) {
    val name =
      characters(world, a).firstOrNull { it.equals(text, ignoreCase = true) }
        ?: return again(world, s, c, world.msg(LoginMsg.NO_SUCH_CHARACTER, "name" to text.take(16)))
    val other = playing(world, a)
    if (other != null && !other.name.equals(name, ignoreCase = true))
      return again(world, s, c, world.msg(LoginMsg.ALREADY_PLAYING, "name" to other.name))
    if (other != null) {
      world.takeOver(other)
      return enter(world, s, c, other, null)
    }
    world.persistence.maintenance?.let { why ->
      world.sendSession(s, why)
      return world.closeSession(s, "maintenance")
    }
    val p =
      world.persistence.loadCharacter(world, name)
        ?: return again(world, s, c, world.msg(LoginMsg.CANT_FIND, "name" to name))
    if (!world.hasMissingChoices(p))
      return enter(world, s, c, p, world.msg(KernelMsg.WELCOME_BACK, "name" to p.name))
    // The character stays out of the world while its choices are made, so nothing can reach it
    // and a cancel leaves no trace; it is loaded again once they are confirmed.
    world.unload(p)
    world.sendSession(s, world.msg(LoginMsg.CREATION_HAS_CHOICES, "name" to p.name))
    val attempt =
      CreationAttempt(p.name, CreationAttempt.accountKey(a.id), a.creations, world.config.seed)
        .apply { answers.putAll(p.choices) }
    startFlow(world, s, c, p.name, newCharacter = false, attempt) { outcome ->
      if (outcome !is CreationOutcome.Completed) return@startFlow menu(world, s, c)
      playing(world, a)?.let {
        return@startFlow again(world, s, c, world.msg(LoginMsg.ALREADY_PLAYING, "name" to it.name))
      }
      world.persistence.maintenance?.let { why ->
        world.sendSession(s, why)
        return@startFlow world.closeSession(s, "maintenance")
      }
      val q =
        world.persistence.loadCharacter(world, name)
          ?: return@startFlow again(world, s, c, world.msg(LoginMsg.CANT_FIND, "name" to name))
      val changes = world.choiceChanges(q, outcome.answers)
      world.choicesRefusal(q, changes)?.let {
        world.unload(q)
        return@startFlow again(world, s, c, it)
      }
      enter(world, s, c, q, world.msg(KernelMsg.WELCOME_BACK, "name" to q.name))
      world.setChoices(q, changes)?.let { world.send(q, it) }
      world.creation?.created(world, q, newCharacter = false)
    }
  }

  /** Runs the creation flow on [c]; [onOutcome] sees how it ended, with the flow already gone. */
  private fun startFlow(
    world: World,
    s: Session,
    c: Conn,
    name: String,
    newCharacter: Boolean,
    attempt: CreationAttempt,
    onOutcome: (CreationOutcome) -> Unit,
  ) {
    c.step = Step.CREATION
    attempt.start = start
    val flow =
      CreationFlow(world, s, name, newCharacter, attempt) { outcome ->
        c.flow = null
        onOutcome(outcome)
      }
    c.flow = flow
    flow.start()
  }

  private fun cantCreate(world: World, a: Account): String? {
    playing(world, a)?.let {
      return world.msg(LoginMsg.ALREADY_PLAYING, "name" to it.name)
    }
    val count = characters(world, a).size
    if (!a.role.atLeast(Role.INSTRUCTOR) && count >= settings.maxCharacters) {
      return world.msg(LoginMsg.CHARACTER_LIMIT, "count" to count)
    }
    return null
  }

  private fun createCharacter(world: World, s: Session, c: Conn, text: String) {
    val a = c.account!!
    if (text.isEmpty()) return menu(world, s, c)
    val retry = { message: String ->
      world.sendSession(s, message)
      ask(world, s, c, Step.NAME, world.msg(LoginMsg.NAME_PROMPT))
    }
    val name = Names.characterName(text) ?: return retry(world.msg(LoginMsg.NAME_INVALID))
    if (name.lowercase() in Names.reserved(world, settings.reservedNames))
      return retry(world.msg(LoginMsg.NAME_RESERVED))
    if (
      world.players.any { it.name.equals(name, ignoreCase = true) } ||
        world.persistence.savedCharacter(name) != null
    ) {
      return retry(world.msg(LoginMsg.NAME_TAKEN))
    }
    cantCreate(world, a)?.let {
      return again(world, s, c, it)
    }
    world.persistence.maintenance?.let { why ->
      world.sendSession(s, why)
      return world.closeSession(s, "maintenance")
    }
    if (world.creation == null) {
      val p = world.spawnPlayer(name, start, accountId = a.id)
      world.requestImmediate("create")
      countCreation(world, c)
      return enter(world, s, c, p, world.msg(KernelMsg.WELCOME_NEW, "name" to name))
    }
    val attempt =
      c.attempt?.apply { this.name = name }
        ?: CreationAttempt(name, CreationAttempt.accountKey(a.id), a.creations, world.config.seed)
    c.attempt = null
    startFlow(world, s, c, name, newCharacter = true, attempt) { outcome ->
      when (outcome) {
        is CreationOutcome.Created -> created(world, s, c, a, name, outcome.attempt)
        is CreationOutcome.BackToName -> {
          c.attempt = outcome.attempt
          ask(world, s, c, Step.NAME, world.msg(LoginMsg.NAME_PROMPT))
        }
        else -> menu(world, s, c)
      }
    }
  }

  /** A confirmed new character: the checks of [createCharacter] again, as time has passed. */
  private fun created(
    world: World,
    s: Session,
    c: Conn,
    a: Account,
    name: String,
    attempt: CreationAttempt,
  ) {
    if (
      world.players.any { it.name.equals(name, ignoreCase = true) } ||
        world.persistence.savedCharacter(name) != null
    ) {
      c.attempt = attempt
      world.sendSession(s, world.msg(LoginMsg.NAME_TAKEN))
      return ask(world, s, c, Step.NAME, world.msg(LoginMsg.NAME_PROMPT))
    }
    cantCreate(world, a)?.let {
      return again(world, s, c, it)
    }
    world.persistence.maintenance?.let { why ->
      world.sendSession(s, why)
      return world.closeSession(s, "maintenance")
    }
    val room =
      try {
        world.creationStartRoom(attempt.answers, start)
      } catch (e: Exception) {
        world.diagnose("plugin", "creation provider startRoom() failed: ${e.message}", e)
        return again(world, s, c, world.msg(LoginMsg.CREATION_PROVIDER_FAILED))
      }
    val p =
      world.spawnPlayer(name, room, accountId = a.id, choices = attempt.answers, attempt = attempt)
    world.requestImmediate("create")
    countCreation(world, c)
    enter(world, s, c, p, world.msg(KernelMsg.WELCOME_NEW, "name" to name))
    world.creation?.created(world, p, newCharacter = true)
  }

  /**
   * Counts a new character: the connection's copy of the account at once, so a second creation on
   * it gets the next seed, and the stored counter in the background, as nothing waits for it.
   */
  private fun countCreation(world: World, c: Conn) {
    val a = c.account!!
    c.account = a.copy(creations = a.creations + 1)
    try {
      background.execute {
        try {
          accounts.store.countCreation(a.id)
        } catch (e: Exception) {
          post(
            WorldControlEvent.Task { w ->
              w.record(Diagnostic(w.pulse, "engine", "countCreation(${a.id}) failed: $e", e))
            }
          )
        }
      }
    } catch (e: RejectedExecutionException) {
      world.record(Diagnostic(world.pulse, "engine", "countCreation(${a.id}) rejected: $e", e))
    }
  }

  private fun enter(world: World, s: Session, c: Conn, p: Player, welcome: String?) {
    val a = c.account!!
    p.role = a.role
    p.scope = a.packages
    p.idleExempt = a.role.atLeast(Role.INSTRUCTOR)
    conns.remove(s.id)
    world.enterGame(s, p, welcome)
  }

  private fun changePassword(world: World, s: Session, c: Conn, again: String) {
    if (again != c.newPassword) {
      world.sendSession(s, world.msg(LoginMsg.MISMATCH))
      return ask(world, s, c, Step.CHANGE_PASSWORD, world.msg(LoginMsg.NEW_PASSWORD_PROMPT))
    }
    val a = c.account!!
    val accountKey = LoginLimiter.accountKey(a.login)
    val ipKey = LoginLimiter.ipKey(s.remote)
    val (old, new) = c.oldPassword to c.newPassword
    c.oldPassword = ""
    c.newPassword = ""
    val locked = limiter.lockedFor(accountKey, ipKey)
    if (locked > 0)
      return again(
        world,
        s,
        c,
        world.msg(LoginMsg.TOO_MANY_LOGINS, "wait" to seconds(world, locked)),
      )
    later(
      world,
      s,
      c,
      {
        val hash = accounts.store.byId(a.id)?.pwHash
        if (hash == null || !accounts.hasher.verify(old, hash)) {
          false
        } else {
          accounts.store.setPassword(a.id, accounts.hasher.hash(new))
          true
        }
      },
    ) { w, session, changed ->
      if (changed) return@later again(w, session, c, w.msg(LoginMsg.PASSWORD_CHANGED))
      limiter.failure(accountKey, ipKey)
      c.strikes++
      if (c.strikes >= settings.maxWrongPasswords) {
        w.sendSession(session, w.msg(LoginMsg.NOT_CURRENT))
        return@later w.closeSession(session, w.msg(LoginMsg.TOO_MANY_WRONG))
      }
      again(w, session, c, w.msg(LoginMsg.NOT_CURRENT))
    }
  }

  // ---- Plumbing ----------------------------------------------------------------------------

  private fun ask(world: World, s: Session, c: Conn, step: Step, prompt: String) {
    c.step = step
    world.flush(s)
    if (step.hidden) s.sink.hideInput(true)
    s.sink.prompt(prompt)
  }

  /**
   * Runs [work] on [background] and [then] on the world thread with its result, holding the
   * session's input in between. A failure is logged and sends the session back to the start of
   * where it was.
   */
  private fun <T> later(
    world: World,
    s: Session,
    c: Conn,
    work: () -> T,
    then: (World, Session, T) -> Unit,
  ) {
    c.pending = true
    val id = s.id
    val step = c.step
    try {
      background.execute {
        val result = runCatching(work)
        post(
          WorldControlEvent.AuthResult(
            id,
            Done(c) { w, session ->
              result.fold({ then(w, session, it) }) { e -> failed(w, session, c, step, e) }
            },
          )
        )
      }
    } catch (e: RejectedExecutionException) {
      c.pending = false
      failed(world, s, c, step, e)
    }
  }

  private fun failed(world: World, s: Session, c: Conn, step: Step, e: Throwable) {
    world.record(Diagnostic(world.pulse, "engine", "login of ${s.id} failed at $step: $e", e))
    world.sendSession(s, world.msg(LoginMsg.ERROR))
    if (c.account != null) menu(world, s, c) else ask(world, s, c, Step.LOGIN, loginPrompt(world))
  }

  private fun seconds(world: World, ms: Long): String =
    ((ms + 999) / 1000).let {
      if (it == 1L) world.msg(LoginMsg.ONE_SECOND) else world.msg(LoginMsg.SECONDS, "n" to it)
    }

  private fun loginPrompt(world: World): String = world.msg(LoginMsg.PROMPT)
}
