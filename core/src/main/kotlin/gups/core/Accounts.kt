package gups.core

/**
 * An account (SPEC §3.5, §3.6 `accounts`). It has one role, since the higher roles imply the
 * lower. [pwHash] is a PHC string, or null until the owner redeems a setup code.
 */
data class Account(
    val id: Long,
    /** 3–24 characters from `[a-z0-9_.-]`, unique case-insensitively. */
    val login: String,
    val pwHash: String?,
    val role: Role = Role.PLAYER,
    /** Packages a builder may change, from CODEOWNERS; every sync replaces it (M6 design §4.1). */
    val scope: List<String> = emptyList(),
    /** Packages given by hand with `@role`, for packages CODEOWNERS doesn't cover (D-140). */
    val manualScope: List<String> = emptyList(),
    val githubHandle: String? = null,
    val disabled: Boolean = false,
    /** Wall-clock milliseconds. */
    val createdAt: Long = 0,
) {
    /** Everything a builder of this account may work on. */
    val packages: Set<String> get() = (scope + manualScope).toSortedSet()
}

/** Capabilities an instructor can take away from one account (M6 design §4.4). */
object Capabilities {
    const val GOTO_PLAYER = "goto_player"
    val ALL: List<String> = listOf(GOTO_PLAYER, "goto", "spawn", "stat", "where", "trace", "errors", "gossip")
}

/**
 * One restriction of one capability on one account (M6 design §4.4, §3.6 `restrictions`).
 * Times are wall-clock milliseconds, so restrictions run out while the server is down.
 */
data class Restriction(
    val id: Long,
    val accountId: Long,
    val capability: String,
    /** When it ends; null is permanent. */
    val until: Long?,
    val reason: String?,
    val setBy: Long?,
    val createdAt: Long,
    val liftedBy: Long? = null,
    val liftedAt: Long? = null,
) {
    fun activeAt(now: Long): Boolean = liftedAt == null && (until == null || until > now)
}

/**
 * An invite or setup code (SPEC §3.6 `invites`). Only the code's hash is stored. A setup code
 * is an invite bound to an existing account (M6 design §3.3).
 */
data class Invite(
    val codeHash: String,
    val role: Role,
    /** Wall-clock milliseconds. */
    val expiresAt: Long,
    /** The account a setup code sets up; null for an invite that creates a new account. */
    val accountId: Long? = null,
    /** The account that used the code; null while it is unused. */
    val usedBy: Long? = null,
)

/**
 * Account storage (M6 design §2.3), implemented by `persist` and used by `auth`. Every call may
 * block on the database, so none may run on the world thread. A write returns once it is
 * committed, so an account change survives a crash right after it.
 */
interface AccountStore {
    fun byLogin(login: String): Account?

    fun byId(id: Long): Account?

    /** Every account, ascending id. */
    fun all(): List<Account>

    /** Stores a new account and returns it with its id, or returns null if the login is taken. [Account.id] is ignored. */
    fun create(account: Account): Account?

    /** Stores [account]'s role, both scopes, GitHub handle and disabled flag. The password is never touched. */
    fun update(account: Account)

    /** Sets or clears an account's password and revokes its web session tokens (SPEC §5.12). */
    fun setPassword(id: Long, pwHash: String?)

    fun addInvite(invite: Invite)

    fun invite(codeHash: String): Invite?

    /** Marks an unused invite as used by [accountId]. Returns false if it was already used. */
    fun useInvite(codeHash: String, accountId: Long): Boolean

    /** Revokes every web session token of the account (`@role <account> revoke`). */
    fun revokeTokens(id: Long)

    /** Restrictions, oldest first: all of them, or one account's full history. */
    fun restrictions(accountId: Long? = null): List<Restriction>

    /** Stores a new restriction and returns it with its id. [Restriction.id] is ignored. */
    fun addRestriction(r: Restriction): Restriction

    /** Lifts restriction [id]. */
    fun liftRestriction(id: Long, by: Long?, at: Long)

    /** Stores a web session token's hash (SPEC §3.6 `session_tokens`), dropping those expired at wall time [now]. */
    fun addToken(tokenHash: String, accountId: Long, expiresAt: Long, now: Long)

    /** The account of an unexpired token hash at wall time [now], or null. */
    fun tokenAccount(tokenHash: String, now: Long): Long?

    /** Revokes one token (logout). */
    fun removeToken(tokenHash: String)
}

/** A web login's outcome (M6 design §6.1). [status] is the HTTP status to answer with. */
sealed interface WebLogin {
    data class Ok(val token: String, val expiresAt: Long, val accountId: Long) : WebLogin

    data class Refused(val status: Int, val message: String) : WebLogin
}

/** What a sign-up code is good for (M6 design §6.1). */
sealed interface CodeCheck {
    /** A usable code: [login] is the account a setup code sets up, or null when the new account's login is the user's choice. */
    data class Valid(val login: String?) : CodeCheck

    data class Refused(val status: Int, val message: String) : CodeCheck
}

/**
 * What the web listener needs from accounts (M6 design §6.1), implemented by `auth` so `net`
 * depends only on `core`. Every call may hash or block on the database, so never on the
 * world thread.
 */
interface WebAccounts {
    /** Checks a password, rate-limited like telnet (M6 design §3.2), and issues a 30-day token. */
    fun login(login: String, password: String, remote: String): WebLogin

    /** Says whether a sign-up code is usable and which login it is bound to, rate-limited per IP like failed logins. */
    fun checkCode(code: String, remote: String): CodeCheck

    /**
     * Redeems an instructor's one-time code (M6 design §3.3, §6.1): a setup code sets its
     * account's password, an invite creates [login] (ignored for setup codes). Then a token,
     * as [login] gives.
     */
    fun signup(code: String, login: String?, password: String, remote: String): WebLogin

    /** Revokes [token]. */
    fun logout(token: String)

    /** The account [token] belongs to, or null if it is unknown, expired or revoked. */
    fun accountFor(token: String): Long?
}
