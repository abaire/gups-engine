package gups.auth

import gups.core.CodeCheck
import gups.core.WebAccounts
import gups.core.WebLogin
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Web session tokens last 30 days (SPEC §5.12). */
const val TOKEN_DAYS = 30

private const val DAY_MS = 24 * 60 * 60_000L

/**
 * Logins for the web client (SPEC §4.10, D-153): the password check and rate limits of the telnet
 * login, sharing its [limiter], then a random 256-bit token. Only the token's SHA-256 is stored, in
 * `session_tokens`. Every call blocks.
 */
class WebLogins(
  private val accounts: AccountService,
  private val limiter: LoginLimiter,
  /** Wall-clock milliseconds; tokens expire by it. */
  private val clock: () -> Long = System::currentTimeMillis,
  private val random: SecureRandom = SecureRandom(),
) : WebAccounts {
  private val store
    get() = accounts.store

  private val dummyHash: String by lazy { accounts.hasher.hash("no account has this password") }

  override fun login(login: String, password: String, remote: String): WebLogin {
    val name = login.trim().lowercase()
    val accountKey = LoginLimiter.accountKey(name)
    val ipKey = LoginLimiter.ipKey(remote)
    val locked = limiter.lockedFor(accountKey, ipKey)
    // Refused without hashing (SPEC §5.12).
    if (locked > 0)
      return WebLogin.Refused(
        429,
        "Too many failed logins. Try again in ${(locked + 999) / 1000} seconds.",
      )
    val a = store.byLogin(name)
    val hash = a?.pwHash
    val ok =
      if (hash == null) {
        accounts.hasher.verify(password, dummyHash)
        false
      } else {
        accounts.hasher.verify(password, hash)
      }
    if (a == null || !ok) {
      limiter.failure(accountKey, ipKey)
      return WebLogin.Refused(401, "Wrong login or password.")
    }
    limiter.success(accountKey)
    if (a.disabled) return WebLogin.Refused(403, "This account is disabled.")
    return issue(a.id)
  }

  private fun issue(accountId: Long): WebLogin.Ok {
    val token =
      Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    val now = clock()
    val expires = now + TOKEN_DAYS * DAY_MS
    store.addToken(hash(token), accountId, expires, now)
    return WebLogin.Ok(token, expires, accountId)
  }

  private val badCode = "That code isn't valid, or it has expired."

  /** The usable invite for [code], or why not; bad codes count against [remote] (D-132). */
  private fun invite(code: String, remote: String): Result<gups.core.Invite> {
    val ipKey = LoginLimiter.ipKey(remote)
    val locked = limiter.lockedFor(ipKey)
    if (locked > 0)
      return Result.failure(
        Refusal(429, "Too many failed attempts. Try again in ${(locked + 999) / 1000} seconds.")
      )
    val invite = accounts.validInvite(code)
    if (invite == null) {
      limiter.failure(ipKey)
      return Result.failure(Refusal(404, badCode))
    }
    return Result.success(invite)
  }

  private class Refusal(val status: Int, override val message: String) : Exception(message)

  override fun checkCode(code: String, remote: String): CodeCheck =
    invite(code, remote)
      .fold(
        { i -> CodeCheck.Valid(i.accountId?.let { store.byId(it)?.login }) },
        { e -> (e as Refusal).let { CodeCheck.Refused(it.status, it.message) } },
      )

  override fun signup(code: String, login: String?, password: String, remote: String): WebLogin {
    val invite =
      invite(code, remote).getOrElse { e ->
        return (e as Refusal).let { WebLogin.Refused(it.status, it.message) }
      }
    val bound = invite.accountId?.let { store.byId(it) ?: return WebLogin.Refused(404, badCode) }
    val name = bound?.login ?: login?.trim()?.lowercase().orEmpty()
    if (bound == null) {
      Names.loginProblem(name)?.let {
        return WebLogin.Refused(400, it)
      }
      if (store.byLogin(name) != null) return WebLogin.Refused(409, "That login is taken.")
    }
    Names.passwordProblem(password, name)?.let {
      return WebLogin.Refused(400, it)
    }
    return when (val r = accounts.redeem(code, name, password)) {
      is Redeemed.Ok -> issue(r.account.id)
      Redeemed.BadCode -> WebLogin.Refused(404, badCode)
      Redeemed.LoginTaken -> WebLogin.Refused(409, "That login is taken.")
    }
  }

  override fun logout(token: String) = store.removeToken(hash(token))

  override fun accountFor(token: String): Long? {
    if (token.isEmpty() || token.length > 100) return null
    val id = store.tokenAccount(hash(token), clock()) ?: return null
    return id.takeIf { store.byId(it)?.disabled == false }
  }

  companion object {
    /** The stored form of a token: SHA-256 as lowercase hex. */
    fun hash(token: String): String =
      MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") {
        "%02x".format(it)
      }
  }
}
