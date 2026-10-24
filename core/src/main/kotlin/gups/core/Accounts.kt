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
    /** Packages a builder may change, filled by CODEOWNERS sync (M6.2). */
    val scope: List<String> = emptyList(),
    val githubHandle: String? = null,
    val disabled: Boolean = false,
    /** Wall-clock milliseconds. */
    val createdAt: Long = 0,
)

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

    /** Stores [account]'s role, scope, GitHub handle and disabled flag. The password is never touched. */
    fun update(account: Account)

    /** Sets or clears an account's password and revokes its web session tokens (SPEC §5.12). */
    fun setPassword(id: Long, pwHash: String?)

    fun addInvite(invite: Invite)

    fun invite(codeHash: String): Invite?

    /** Marks an unused invite as used by [accountId]. Returns false if it was already used. */
    fun useInvite(codeHash: String, accountId: Long): Boolean
}
