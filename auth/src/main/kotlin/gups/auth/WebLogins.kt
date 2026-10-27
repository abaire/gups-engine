package gups.auth

import gups.core.CodeCheck
import gups.core.WebAccounts
import gups.core.WebLogin
import gups.core.world.LoginMsg
import gups.core.world.Msg
import gups.core.world.render
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Web session tokens last 30 days. */
const val TOKEN_DAYS = 30

private const val DAY_MS = 24 * 60 * 60_000L

/**
 * Logins for the web client: the password check and rate limits of the telnet login, sharing its
 * [limiter], then a random 256-bit token. Only the token's SHA-256 is stored, in `session_tokens`.
 * Every call blocks.
 */
class WebLogins(
  private val accounts: AccountService,
  private val limiter: LoginLimiter,
  /** Wall-clock milliseconds; tokens expire by it. */
  private val clock: () -> Long = System::currentTimeMillis,
  private val random: SecureRandom = SecureRandom(),
  /** The world's `MESSAGES.gups` overrides, read on each refusal so they follow the live world. */
  private val messages: () -> Map<String, String> = { emptyMap() },
) : WebAccounts {
  private val store
    get() = accounts.store

  private val dummyHash: String by lazy { accounts.hasher.hash("no account has this password") }

  override fun login(login: String, password: String, remote: String): WebLogin {
    val name = login.trim().lowercase()
    val accountKey = LoginLimiter.accountKey(name)
    val ipKey = LoginLimiter.ipKey(remote)
    val locked = limiter.lockedFor(accountKey, ipKey)
    // Refused without hashing.
    if (locked > 0)
      return WebLogin.Refused(429, text(LoginMsg.TOO_MANY_LOGINS, "wait" to seconds(locked)))
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
      return WebLogin.Refused(401, text(LoginMsg.WRONG))
    }
    limiter.success(accountKey)
    if (a.disabled) return WebLogin.Refused(403, text(LoginMsg.ACCOUNT_DISABLED))
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

  private val badCode: String
    get() = text(LoginMsg.BAD_CODE)

  private fun text(m: Msg, vararg args: Pair<String, Any?>): String = m.render(messages(), *args)

  private fun seconds(ms: Long): String =
    ((ms + 999) / 1000).let {
      if (it == 1L) text(LoginMsg.ONE_SECOND) else text(LoginMsg.SECONDS, "n" to it)
    }

  /** The usable invite for [code], or why not; bad codes count against [remote]. */
  private fun invite(code: String, remote: String): Result<gups.core.Invite> {
    val ipKey = LoginLimiter.ipKey(remote)
    val locked = limiter.lockedFor(ipKey)
    if (locked > 0)
      return Result.failure(
        Refusal(429, text(LoginMsg.TOO_MANY_ATTEMPTS, "wait" to seconds(locked)))
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
      Names.loginProblem(name, messages())?.let {
        return WebLogin.Refused(400, it)
      }
      if (store.byLogin(name) != null) return WebLogin.Refused(409, text(LoginMsg.LOGIN_TAKEN))
    }
    Names.passwordProblem(password, name, messages())?.let {
      return WebLogin.Refused(400, it)
    }
    return when (val r = accounts.redeem(code, name, password)) {
      is Redeemed.Ok -> issue(r.account.id)
      Redeemed.BadCode -> WebLogin.Refused(404, badCode)
      Redeemed.LoginTaken -> WebLogin.Refused(409, text(LoginMsg.LOGIN_TAKEN))
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
