package gups.auth

/** Number of consecutive failed attempts required to trigger a login lockout (5 failures). */
const val LOCKOUT_FAILURES = 5

/** Inactivity window after which recorded failures are discarded: 15 minutes (900,000 ms). */
const val FAILURE_WINDOW_MS = 15 * 60_000L

/** Initial lockout duration upon reaching [LOCKOUT_FAILURES]: 1 minute (60,000 ms). */
const val FIRST_LOCKOUT_MS = 60_000L

/** Maximum lockout duration ceiling: 15 minutes (900,000 ms). */
const val MAX_LOCKOUT_MS = 15 * 60_000L

/**
 * Thread-safe rate limiter tracking failed authentication attempts per account login and per client
 * IP.
 *
 * ## Lockout Rules & Backoff Ladder
 * - Failures are tracked independently for account keys (`account:<login>`) and client IP keys
 *   (`ip:<remote>`).
 * - Reaching 5 failures ([LOCKOUT_FAILURES]) initiates a 1-minute lockout ([FIRST_LOCKOUT_MS]).
 * - Each additional failure after 5 doubles the lockout period (2 min, 4 min, 8 min), capping at a
 *   15-minute maximum lockout ([MAX_LOCKOUT_MS]).
 * - Quiet period: A key's failure count and lockout expire after 15 minutes without any failed
 *   attempts ([FAILURE_WINDOW_MS]).
 * - Successful authentication: An authenticated login clears the failure record for that account;
 *   the client IP's failure count remains unaffected to deter multi-account credential stuffing
 *   from a single host.
 * - Volatility: Failure records reside strictly in memory and are discarded upon server restart.
 */
class LoginLimiter(private val clock: () -> Long = System::currentTimeMillis) {
  private class Record(var failures: Int, var lastFailure: Long, var lockedUntil: Long)

  private val records = HashMap<String, Record>()

  /** Milliseconds until every one of [keys] may try again; 0 if none is locked out. */
  @Synchronized
  fun lockedFor(vararg keys: String): Long {
    val now = clock()
    return keys.maxOf { k -> live(k, now)?.let { (it.lockedUntil - now).coerceAtLeast(0) } ?: 0 }
  }

  /** Records one failure against each of [keys]. */
  @Synchronized
  fun failure(vararg keys: String) {
    val now = clock()
    for (k in keys) {
      val r = live(k, now) ?: Record(0, now, 0).also { records[k] = it }
      r.failures++
      r.lastFailure = now
      if (r.failures >= LOCKOUT_FAILURES) {
        val doublings = (r.failures - LOCKOUT_FAILURES).coerceAtMost(10)
        r.lockedUntil = now + (FIRST_LOCKOUT_MS shl doublings).coerceAtMost(MAX_LOCKOUT_MS)
      }
    }
    if (records.size > PRUNE_AT)
      records.entries.removeIf { now - it.value.lastFailure > FAILURE_WINDOW_MS }
  }

  /** A successful login clears the account's count; the IP's stays. */
  @Synchronized
  fun success(accountKey: String) {
    records.remove(accountKey)
  }

  /** Failures counted against [key] right now. */
  @Synchronized fun failures(key: String): Int = live(key, clock())?.failures ?: 0

  private fun live(key: String, now: Long): Record? {
    val r = records[key] ?: return null
    if (now - r.lastFailure > FAILURE_WINDOW_MS) {
      records.remove(key)
      return null
    }
    return r
  }

  companion object {
    private const val PRUNE_AT = 10_000

    fun accountKey(login: String) = "account:${login.lowercase()}"

    fun ipKey(remote: String) = "ip:$remote"
  }
}
