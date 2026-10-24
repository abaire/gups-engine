package gups.auth

import gups.core.Account
import gups.core.ClientInputEvent
import gups.core.Label
import gups.core.PULSE_MS
import gups.core.Role
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Player
import gups.core.world.Diagnostic
import gups.core.world.LoginHandler
import gups.core.world.Session
import gups.core.world.World
import gups.core.world.closeSession
import gups.core.world.enterGame
import gups.core.world.flush
import gups.core.world.handle
import gups.core.world.sendSession
import gups.core.world.session
import gups.core.world.takeOver
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Account login settings (M6 design §2.1, §3.1, §3.4). */
data class LoginSettings(
    /** A new login can be made at the prompt without a code (SPEC §1.7). */
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

const val LOGIN_PROMPT = "By what name do you wish to be known? (login, or \"new\" with an invite code) "

/**
 * The account login of `--prod` (SPEC §5.12, M6 design §3.4, D-129, D-130): login and
 * password, invite and setup codes, then character selection and creation. A small state
 * machine per session on the world thread. Anything that touches the [AccountService] (the
 * database, Argon2) runs on [background], and its result comes back through [post] as a
 * [WorldControlEvent.AuthResult]; until then the session's input is held.
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
    }

    /** What [post] carries back: the work's continuation, for the connection that started it. */
    private class Done(val conn: Conn, val then: (World, Session) -> Unit)

    private val conns = HashMap<SessionId, Conn>()

    /** Hashed for unknown logins, so a wrong login costs as much as a wrong password (M6 design §3.2). */
    private val dummyHash: String by lazy { accounts.hasher.hash("no account has this password") }

    override fun connected(world: World, session: Session) {
        val c = Conn(world.pulse)
        conns[session.id] = c
        world.sendSession(session, "Welcome to GUPS.")
        ask(world, session, c, Step.LOGIN, LOGIN_PROMPT)
    }

    override fun disconnected(world: World, session: SessionId) {
        conns.remove(session)
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
            world.closeSession(s, "You took too long. Goodbye.")
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
                val problem = Names.passwordProblem(line, c.login)
                if (problem != null) {
                    world.sendSession(session, problem)
                    ask(world, session, c, Step.NEW_PASSWORD, "Password: ")
                } else {
                    c.newPassword = line
                    ask(world, session, c, Step.NEW_PASSWORD_AGAIN, "Password again: ")
                }
            }
            Step.NEW_PASSWORD_AGAIN -> redeem(world, session, c, line)
            Step.MENU -> menuChoice(world, session, c, text)
            Step.NAME -> createCharacter(world, session, c, text)
            Step.OLD_PASSWORD -> {
                c.oldPassword = line
                ask(world, session, c, Step.CHANGE_PASSWORD, "New password: ")
            }
            Step.CHANGE_PASSWORD -> {
                val problem = Names.passwordProblem(line, c.login)
                if (problem != null) {
                    world.sendSession(session, problem)
                    ask(world, session, c, Step.CHANGE_PASSWORD, "New password: ")
                } else {
                    c.newPassword = line
                    ask(world, session, c, Step.CHANGE_PASSWORD_AGAIN, "New password again: ")
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
                session.player != null -> world.handle(ClientInputEvent(session.id, line, System.nanoTime()))
                conns[session.id] === c -> input(world, session, line)
                else -> break
            }
        }
    }

    // ---- Logging in --------------------------------------------------------------------------

    private fun login(world: World, s: Session, c: Conn, text: String) {
        when {
            text.isEmpty() -> ask(world, s, c, Step.LOGIN, LOGIN_PROMPT)
            text.equals("new", ignoreCase = true) -> askCode(world, s, c)
            else -> {
                c.login = text.lowercase()
                ask(world, s, c, Step.PASSWORD, "Password: ")
            }
        }
    }

    private fun password(world: World, s: Session, c: Conn, password: String) {
        val accountKey = LoginLimiter.accountKey(c.login)
        val ipKey = LoginLimiter.ipKey(s.remote)
        val locked = limiter.lockedFor(accountKey, ipKey)
        // Refused without hashing, so a locked-out attacker can't spend the server's CPU.
        if (locked > 0) return strike(world, s, c, "Too many failed logins. Try again in ${seconds(locked)}.")
        val login = c.login
        later(world, s, c, {
            val a = accounts.store.byLogin(login)
            val hash = a?.pwHash
            if (hash == null) {
                accounts.hasher.verify(password, dummyHash)
                null
            } else {
                a.takeIf { accounts.hasher.verify(password, hash) }
            }
        }) { w, session, a ->
            when {
                a == null -> {
                    limiter.failure(accountKey, ipKey)
                    strike(w, session, c, "Wrong login or password.")
                }
                a.disabled -> {
                    w.sendSession(session, "This account is disabled.")
                    w.closeSession(session, "Goodbye.")
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
        if (c.strikes >= settings.maxWrongPasswords) return world.closeSession(s, "Too many wrong passwords. Goodbye.")
        c.account = null
        ask(world, s, c, Step.LOGIN, LOGIN_PROMPT)
    }

    // ---- New accounts ------------------------------------------------------------------------

    private fun askCode(world: World, s: Session, c: Conn) =
        ask(world, s, c, Step.CODE, if (settings.openSignup) "Invite code (or press Enter to sign up without one): " else "Invite or setup code: ")

    private fun askNewLogin(world: World, s: Session, c: Conn) =
        ask(world, s, c, Step.NEW_LOGIN, "Choose a login (3 to 24 characters: a-z, 0-9, _ . -): ")

    private fun code(world: World, s: Session, c: Conn, text: String) {
        if (text.isEmpty()) {
            if (!settings.openSignup) return ask(world, s, c, Step.LOGIN, LOGIN_PROMPT)
            c.code = null
            return askNewLogin(world, s, c)
        }
        val ipKey = LoginLimiter.ipKey(s.remote)
        val locked = limiter.lockedFor(ipKey)
        if (locked > 0) return strike(world, s, c, "Too many failed attempts. Try again in ${seconds(locked)}.")
        val code = Codes.normalize(text)
        if (code == null) {
            limiter.failure(ipKey)
            return strike(world, s, c, "That code isn't valid, or it has expired.")
        }
        later(world, s, c, {
            val invite = accounts.validInvite(code)
            invite to invite?.accountId?.let { accounts.store.byId(it) }
        }) { w, session, (invite, bound) ->
            when {
                invite == null || (invite.accountId != null && bound == null) -> {
                    limiter.failure(ipKey)
                    strike(w, session, c, "That code isn't valid, or it has expired.")
                }
                bound != null -> {
                    c.code = code
                    c.login = bound.login
                    w.sendSession(session, "This code sets up the account '${bound.login}'. Choose a password of at least ${Names.MIN_PASSWORD} characters.")
                    ask(w, session, c, Step.NEW_PASSWORD, "Password: ")
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
        Names.loginProblem(login)?.let { problem ->
            world.sendSession(s, problem)
            return askNewLogin(world, s, c)
        }
        later(world, s, c, { accounts.store.byLogin(login) != null }) { w, session, taken ->
            if (taken) {
                w.sendSession(session, "That login is taken.")
                return@later askNewLogin(w, session, c)
            }
            c.login = login
            w.sendSession(session, "Choose a password of at least ${Names.MIN_PASSWORD} characters.")
            ask(w, session, c, Step.NEW_PASSWORD, "Password: ")
        }
    }

    private fun redeem(world: World, s: Session, c: Conn, again: String) {
        if (again != c.newPassword) {
            world.sendSession(s, "The passwords don't match.")
            return ask(world, s, c, Step.NEW_PASSWORD, "Password: ")
        }
        val (code, login, password) = Triple(c.code, c.login, c.newPassword)
        c.newPassword = ""
        later(world, s, c, { accounts.redeem(code, login, password) }) { w, session, r ->
            when (r) {
                is Redeemed.Ok -> {
                    c.account = r.account
                    c.login = r.account.login
                    w.sendSession(session, "Your account '${r.account.login}' is ready.")
                    menu(w, session, c)
                }
                Redeemed.BadCode -> {
                    w.sendSession(session, "That code was used or ran out in the meantime.")
                    ask(w, session, c, Step.LOGIN, LOGIN_PROMPT)
                }
                Redeemed.LoginTaken -> {
                    w.sendSession(session, "Someone took that login in the meantime.")
                    askNewLogin(w, session, c)
                }
            }
        }
    }

    // ---- The character menu ------------------------------------------------------------------

    private fun menu(world: World, s: Session, c: Conn) {
        val names = characters(world, c.account!!)
        val list = if (names.isEmpty()) "You have no characters yet." else "Your characters: ${names.joinToString(", ")}."
        world.sendSession(s, "$list  Type a name to play, \"new\" to create, or \"password\" to change yours.")
        ask(world, s, c, Step.MENU, "> ")
    }

    private fun again(world: World, s: Session, c: Conn, message: String) {
        world.sendSession(s, message)
        menu(world, s, c)
    }

    /** The account's characters: saved ones, and any in the world not saved yet. */
    private fun characters(world: World, a: Account): List<String> =
        (world.persistence.characters(a.id) + world.players.filter { it.accountId == a.id }.map { it.name }).distinctBy { it.lowercase() }

    /** The account's character in the world, link-dead included: an account plays one at a time (Q9). */
    private fun playing(world: World, a: Account): Player? = world.players.firstOrNull { it.accountId == a.id }

    private fun menuChoice(world: World, s: Session, c: Conn, text: String) {
        val a = c.account!!
        when {
            text.isEmpty() -> menu(world, s, c)
            text.equals("new", ignoreCase = true) -> {
                val why = cantCreate(world, a) ?: return ask(world, s, c, Step.NAME, "What will your new character be called? ")
                again(world, s, c, why)
            }
            text.equals("password", ignoreCase = true) -> ask(world, s, c, Step.OLD_PASSWORD, "Current password: ")
            text.equals("quit", ignoreCase = true) -> world.closeSession(s, "Goodbye.")
            else -> play(world, s, c, a, text)
        }
    }

    private fun play(world: World, s: Session, c: Conn, a: Account, text: String) {
        val name = characters(world, a).firstOrNull { it.equals(text, ignoreCase = true) }
            ?: return again(world, s, c, "You have no character called ${text.take(16)}.")
        val other = playing(world, a)
        if (other != null && !other.name.equals(name, ignoreCase = true)) return again(world, s, c, "You're already playing ${other.name}.")
        if (other != null) {
            world.takeOver(other)
            return enter(world, s, c, other, null)
        }
        world.persistence.maintenance?.let { why ->
            world.sendSession(s, why)
            return world.closeSession(s, "maintenance")
        }
        val p = world.persistence.loadCharacter(world, name) ?: return again(world, s, c, "$name can't be found.")
        enter(world, s, c, p, "Welcome back, ${p.name}.")
    }

    private fun cantCreate(world: World, a: Account): String? {
        playing(world, a)?.let { return "You're already playing ${it.name}." }
        val count = characters(world, a).size
        if (!a.role.atLeast(Role.INSTRUCTOR) && count >= settings.maxCharacters) {
            return "You already have $count characters, the most an account may have."
        }
        return null
    }

    private fun createCharacter(world: World, s: Session, c: Conn, text: String) {
        val a = c.account!!
        if (text.isEmpty()) return menu(world, s, c)
        val retry = { message: String ->
            world.sendSession(s, message)
            ask(world, s, c, Step.NAME, "What will your new character be called? ")
        }
        val name = Names.characterName(text) ?: return retry("Names are 2 to 16 letters.")
        if (name.lowercase() in Names.reserved(world, settings.reservedNames)) return retry("That name is reserved.")
        if (world.players.any { it.name.equals(name, ignoreCase = true) } || world.persistence.savedCharacter(name) != null) {
            return retry("That name is taken.")
        }
        cantCreate(world, a)?.let { return again(world, s, c, it) }
        world.persistence.maintenance?.let { why ->
            world.sendSession(s, why)
            return world.closeSession(s, "maintenance")
        }
        val p = world.spawnPlayer(name, start, accountId = a.id)
        world.requestImmediate("create")
        enter(world, s, c, p, "Welcome, $name.")
    }

    private fun enter(world: World, s: Session, c: Conn, p: Player, welcome: String?) {
        val a = c.account!!
        p.role = a.role
        p.scope = a.packages
        // Idle quit applies to everyone below instructor (SPEC §5.12).
        p.idleExempt = a.role.atLeast(Role.INSTRUCTOR)
        conns.remove(s.id)
        world.enterGame(s, p, welcome)
    }

    private fun changePassword(world: World, s: Session, c: Conn, again: String) {
        if (again != c.newPassword) {
            world.sendSession(s, "The passwords don't match.")
            return ask(world, s, c, Step.CHANGE_PASSWORD, "New password: ")
        }
        val a = c.account!!
        val accountKey = LoginLimiter.accountKey(a.login)
        val ipKey = LoginLimiter.ipKey(s.remote)
        val (old, new) = c.oldPassword to c.newPassword
        c.oldPassword = ""
        c.newPassword = ""
        val locked = limiter.lockedFor(accountKey, ipKey)
        if (locked > 0) return again(world, s, c, "Too many failed logins. Try again in ${seconds(locked)}.")
        later(world, s, c, {
            val hash = accounts.store.byId(a.id)?.pwHash
            if (hash == null || !accounts.hasher.verify(old, hash)) {
                false
            } else {
                accounts.store.setPassword(a.id, accounts.hasher.hash(new))
                true
            }
        }) { w, session, changed ->
            if (changed) return@later again(w, session, c, "Your password has been changed.")
            limiter.failure(accountKey, ipKey)
            c.strikes++
            if (c.strikes >= settings.maxWrongPasswords) {
                w.sendSession(session, "That isn't your current password.")
                return@later w.closeSession(session, "Too many wrong passwords. Goodbye.")
            }
            again(w, session, c, "That isn't your current password.")
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
     * session's input in between (M6 design §2.2). A failure is logged and sends the session
     * back to the start of where it was.
     */
    private fun <T> later(world: World, s: Session, c: Conn, work: () -> T, then: (World, Session, T) -> Unit) {
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
                    ),
                )
            }
        } catch (e: RejectedExecutionException) {
            c.pending = false
            failed(world, s, c, step, e)
        }
    }

    private fun failed(world: World, s: Session, c: Conn, step: Step, e: Throwable) {
        world.record(Diagnostic(world.pulse, "engine", "login of ${s.id} failed at $step: $e", e))
        world.sendSession(s, "Something went wrong on the server. Please try again.")
        if (c.account != null) menu(world, s, c) else ask(world, s, c, Step.LOGIN, LOGIN_PROMPT)
    }

    private fun seconds(ms: Long): String = ((ms + 999) / 1000).let { if (it == 1L) "1 second" else "$it seconds" }
}
