package gups.auth

/** Failures that start a lockout. */
const val LOCKOUT_FAILURES = 5

/** Quiet time after which a key's failures are forgotten. */
const val FAILURE_WINDOW_MS = 15 * 60_000L

const val FIRST_LOCKOUT_MS = 60_000L
const val MAX_LOCKOUT_MS = 15 * 60_000L

/**
 * Failed-login counting per account and per IP. Five failures start a 1-minute lockout, and each
 * further failure doubles it, up to 15 minutes. A key's count resets after 15 minutes without a
 * failure. Counts live in memory only, so a restart clears them. Thread-safe.
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
