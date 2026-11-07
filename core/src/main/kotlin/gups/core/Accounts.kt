package gups.core

/**
 * An account identity.
 *
 * An account holds exactly one [role]; higher roles imply lower ones in the hierarchy. [pwHash] is
 * an Argon2id password hash in PHC format, or null until the owner redeems a setup code.
 */
data class Account(
  /** Unique monotonic account identifier. */
  val id: Long,
  /** 3–24 characters from `[a-z0-9_.-]`, unique case-insensitively and stored lowercase. */
  val login: String,
  /** Password hash string, or null if password authentication has not been configured. */
  val pwHash: String?,
  /** Assigned authority role, defaulting to [Role.PLAYER]. */
  val role: Role = Role.PLAYER,
  /** Packages a builder may change, populated from CODEOWNERS files during reload/sync. */
  val scope: List<String> = emptyList(),
  /**
   * Packages granted manually via administrative commands for packages not covered by CODEOWNERS.
   */
  val manualScope: List<String> = emptyList(),
  /** Optional linked GitHub username. */
  val githubHandle: String? = null,
  /** True if the account has been suspended or disabled. */
  val disabled: Boolean = false,
  /** Account ID of the instructor or administrator who disabled this account. */
  val disabledBy: Long? = null,
  /** Wall-clock millisecond timestamp when the account was disabled. */
  val disabledAt: Long? = null,
  /** Free-form reason recorded when the account was disabled. */
  val disabledReason: String? = null,
  /** Wall-clock millisecond timestamp when the account was created. */
  val createdAt: Long = 0,
  /** How many characters this account has ever created; never decreases. */
  val creations: Int = 0,
) {
  /** Combined set of all package paths this account is authorized to modify. */
  val packages: Set<String>
    get() = (scope + manualScope).toSortedSet()
}

/** Specific capabilities that can be restricted on an account by an instructor. */
object Capabilities {
  /** Permission to teleport to another player character. */
  const val GOTO_PLAYER = "goto_player"

  /** Complete list of restrictable capability names. */
  val ALL: List<String> =
    listOf(GOTO_PLAYER, "goto", "spawn", "stat", "where", "trace", "errors", "gossip")
}

/**
 * One restriction on an account capability. Timestamps use wall-clock milliseconds so temporary
 * restrictions continue to elapse while the server is down.
 */
data class Restriction(
  /** Unique restriction identifier. */
  val id: Long,
  /** Account ID subject to the restriction. */
  val accountId: Long,
  /** Name of the restricted capability. */
  val capability: String,
  /** Wall-clock millisecond expiration timestamp, or null for indefinite/permanent restrictions. */
  val until: Long?,
  /** Recorded justification for the restriction. */
  val reason: String?,
  /**
   * Account ID of the instructor who imposed the restriction, or null if set by system automation.
   */
  val setBy: Long?,
  /** Wall-clock millisecond timestamp when the restriction was created. */
  val createdAt: Long,
  /** Account ID of the instructor who lifted the restriction early, or null if not lifted. */
  val liftedBy: Long? = null,
  /** Wall-clock millisecond timestamp when the restriction was lifted. */
  val liftedAt: Long? = null,
) {
  /** True if this restriction is currently in effect at the given [now] wall-clock timestamp. */
  fun activeAt(now: Long): Boolean = liftedAt == null && (until == null || until > now)
}

/**
 * An invite code or setup code. Only the code's hash is stored. A setup code is an invite bound to
 * an existing account to let the user claim and password-protect it.
 */
data class Invite(
  /** SHA-256 hash of the invite or setup code. */
  val codeHash: String,
  /** Role granted when redeeming this code. */
  val role: Role,
  /** Wall-clock millisecond expiration timestamp. */
  val expiresAt: Long,
  /** The account a setup code configures, or null for an invite code creating a new account. */
  val accountId: Long? = null,
  /** Account ID that redeemed the code, or null while still unused. */
  val usedBy: Long? = null,
)

/**
 * Account storage interface, implemented by the persistence module and used by authentication.
 *
 * Concurrency and transaction contract:
 * - Every method may block on database I/O, so no call may execute on the world loop thread.
 * - Writes are committed in immediate checkpoint batches so account modifications survive crashes
 *   immediately.
 * - Readers use a read-only WAL connection for concurrent non-blocking reads.
 * - Disabling an account updates records, quits active sessions, and deletes session tokens and
 *   unused setup codes.
 * - Setting or clearing a password revokes all existing web session tokens for security.
 */
interface AccountStore {
  /** Finds an account by case-insensitive [login] name. */
  fun byLogin(login: String): Account?

  /** Finds an account by unique [id]. */
  fun byId(id: Long): Account?

  /** Returns all accounts ordered by ascending ID. */
  fun all(): List<Account>

  /**
   * Stores a new account and returns it with its generated ID, or returns null if the login is
   * taken. Any ID in [account] is ignored during creation.
   */
  fun create(account: Account): Account?

  /**
   * Updates an existing account's role, scopes, GitHub handle, and disabled status. The password
   * hash is not modified by this method.
   */
  fun update(account: Account)

  /** Adds one to account [id]'s creation counter. */
  fun countCreation(id: Long)

  /**
   * Disables or enables account [id] in one immediate database transaction. Disabling stores the
   * disabled record, revokes all web session tokens, and cleans up unused setup codes. Enabling
   * clears the disabled record. Returns false if the account was already in the requested state.
   */
  fun setDisabled(id: Long, disabled: Boolean, by: Long?, at: Long, reason: String?): Boolean

  /** Sets or clears an account's password hash and revokes all active web session tokens. */
  fun setPassword(id: Long, pwHash: String?)

  /** Persists a new invite or setup code record. */
  fun addInvite(invite: Invite)

  /** Looks up an invite or setup code by its [codeHash]. */
  fun invite(codeHash: String): Invite?

  /** Marks an unused invite code as redeemed by [accountId]. Returns false if already used. */
  fun useInvite(codeHash: String, accountId: Long): Boolean

  /** Revokes every web session token associated with account [id]. */
  fun revokeTokens(id: Long)

  /** Returns restrictions ordered oldest first, optionally filtered to a single [accountId]. */
  fun restrictions(accountId: Long? = null): List<Restriction>

  /** Stores a new restriction and returns it with its generated ID. Any existing ID is ignored. */
  fun addRestriction(r: Restriction): Restriction

  /** Lifts restriction [id], recording who lifted it and the wall-clock timestamp. */
  fun liftRestriction(id: Long, by: Long?, at: Long)

  /** Stores a web session token hash and prunes tokens expired at wall-clock time [now]. */
  fun addToken(tokenHash: String, accountId: Long, expiresAt: Long, now: Long)

  /**
   * Returns the account ID for a valid, unexpired [tokenHash] at wall-clock time [now], or null.
   */
  fun tokenAccount(tokenHash: String, now: Long): Long?

  /** Revokes a single web session token by its hash. */
  fun removeToken(tokenHash: String)
}

/** Result of an HTTP web login attempt. */
sealed interface WebLogin {
  /** Successful login returning an issued bearer [token], expiry timestamp, and [accountId]. */
  data class Ok(val token: String, val expiresAt: Long, val accountId: Long) : WebLogin

  /** Refused login attempt with an HTTP [status] code and diagnostic [message]. */
  data class Refused(val status: Int, val message: String) : WebLogin
}

/** Result of checking the validity of an invite or setup code. */
sealed interface CodeCheck {
  /**
   * A valid, redeemable code. [login] is the bound login for a setup code, or null for open
   * invites.
   */
  data class Valid(val login: String?) : CodeCheck

  /** An invalid or unusable code with HTTP [status] and failure [message]. */
  data class Refused(val status: Int, val message: String) : CodeCheck
}

/**
 * Account authentication and authorization seam required by HTTP listeners. Implemented by the
 * authentication module so the network module depends only on core seams. Methods may perform
 * hashing or database queries and must never run on the world loop thread.
 */
interface WebAccounts {
  /**
   * Verifies account credentials, enforces rate limits, and issues a 30-day session token on
   * success.
   */
  fun login(login: String, password: String, remote: String): WebLogin

  /**
   * Validates whether an invite or setup code is usable and returns its bound login if applicable.
   */
  fun checkCode(code: String, remote: String): CodeCheck

  /**
   * Redeems an invite or setup code. For setup codes, sets the account password. For invites,
   * creates an account with [login]. Issues a web session token on success.
   */
  fun signup(code: String, login: String?, password: String, remote: String): WebLogin

  /** Revokes an active web session [token]. */
  fun logout(token: String)

  /** Returns the account ID associated with [token], or null if invalid, expired, or revoked. */
  fun accountFor(token: String): Long?
}
