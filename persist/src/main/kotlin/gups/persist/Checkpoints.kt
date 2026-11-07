package gups.persist

import gups.core.Account
import gups.core.Invite
import gups.core.Restriction
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One checkpoint batch containing serialized database rows captured on the world thread.
 *
 * ## Capture Cadence: Periodic vs. Immediate
 * - **Periodic checkpoints:** Captured on world tick intervals (governed by `checkpoint_minutes` in
 *   `MODULE.gups`, defaulting to 30 minutes). Verifies engine invariants before serialization, then
 *   sweeps all dirty entities, zone states, pending diagnostics, and engine metadata.
 * - **Immediate captures:** Triggered at the completion of high-value or state-critical action
 *   cascades: player-to-player item custody transfers, character level gains or losses, creation
 *   and deletion, choice and trait modifications, and player logout or quit. Immediate captures are
 *   buffered in memory and committed in the same atomic transaction as the next periodic or
 *   shutdown checkpoint. Capturing both sides of an item transfer simultaneously prevents
 *   duplication across server crashes.
 *
 * ## Batch Merging
 * Queued periodic batches merge in the writer's FIFO queue: subsequent writes overwrite earlier
 * entity rows (last write wins per entity ID). Immediate captures merge into subsequent captures
 * unless the target batch is actively committing. Account operations ([AccountOp]) are committed
 * strictly in order and never merge.
 *
 * Setting `null` in [entities] signifies entity deletion (tombstone).
 */
class Batch(
  val immediate: Boolean,
  val cause: String,
  /** The pulse of the oldest capture merged into this batch. */
  val firstPulse: Long,
  val meta: LinkedHashMap<String, String> = LinkedHashMap(),
  val entities: LinkedHashMap<Long, EntityRow?> = LinkedHashMap(),
  val players: LinkedHashMap<Long, PlayerRow> = LinkedHashMap(),
  val zones: LinkedHashMap<String, String> = LinkedHashMap(),
  val events: MutableList<ItemEventRow> = mutableListOf(),
  val diagnostics: MutableList<DiagnosticRow> = mutableListOf(),
  /** Account changes, written before the world rows in the same transaction. */
  val accounts: MutableList<AccountOp> = mutableListOf(),
  var standings: List<StandingRow>? = null,
) {
  /** Completed once this batch is committed. */
  val done: CompletableFuture<Unit> = CompletableFuture()

  internal fun merge(next: Batch) {
    require(next.accounts.isEmpty()) { "account batches never merge" }
    meta.putAll(next.meta)
    entities.putAll(next.entities)
    players.putAll(next.players)
    zones.putAll(next.zones)
    events += next.events
    diagnostics += next.diagnostics
    next.standings?.let { standings = it }
  }
}

/** One account, password or invite change. */
sealed interface AccountOp {
  /** Inserts [account] unless its login is taken; [result] gets the new id, or null. */
  class Create(val account: Account) : AccountOp {
    val result: CompletableFuture<Long?> = CompletableFuture()
  }

  class Update(val account: Account) : AccountOp

  class CountCreation(val id: Long) : AccountOp

  /** Also revokes the account's session tokens. */
  class SetPassword(val id: Long, val pwHash: String?) : AccountOp

  class AddInvite(val invite: Invite) : AccountOp

  class RevokeTokens(val id: Long) : AccountOp

  /** Also deletes tokens that expired before [now]. */
  class AddToken(val tokenHash: String, val accountId: Long, val expiresAt: Long, val now: Long) :
    AccountOp

  class RemoveToken(val tokenHash: String) : AccountOp

  /** [result] gets the new restriction's id. */
  class AddRestriction(val restriction: Restriction) : AccountOp {
    val result: CompletableFuture<Long> = CompletableFuture()
  }

  class LiftRestriction(val id: Long, val by: Long?, val at: Long) : AccountOp

  /** [result] is false if the invite was already used. */
  class UseInvite(val codeHash: String, val accountId: Long) : AccountOp {
    val result: CompletableFuture<Boolean> = CompletableFuture()
  }

  /** Disables or enables account [id]. [result] is false if it already had that state. */
  class SetDisabled(
    val id: Long,
    val disabled: Boolean,
    val by: Long?,
    val at: Long,
    val reason: String?,
  ) : AccountOp {
    val result: CompletableFuture<Boolean> = CompletableFuture()
  }
}

/** How long a failing write waits before it tries again, at most. */
private const val MAX_BACKOFF_MS = 5_000L

/**
 * The dedicated SQLite checkpoint writer.
 *
 * Runs on its own background thread to ensure disk I/O and SQLite locking never stall the world
 * loop.
 *
 * ## Commit & Merging Behavior
 * - Commits queued batches in strict FIFO order, each within a single atomic database transaction.
 * - Periodic batches merge into the last queued batch (last write wins per entity) as long as it is
 *   not actively being committed by the worker thread.
 *
 * ## Retry Backoff & Unhealthy State
 * - A failed transaction remains at the head of the queue and retries with exponential backoff
 *   starting at 50 ms, doubling on successive errors up to 5,000 ms ([MAX_BACKOFF_MS]).
 * - Subsequent periodic captures continue merging into the retrying batch while backoff delays
 *   elapse.
 * - Any commit failure marks [healthy] as `false`, causing the engine to refuse new logins until
 *   writes resume.
 * - [SqlitePersistence] logs and alerts a `checkpoint` diagnostic when a batch stays uncommitted
 *   for more than three world ticks.
 */
class CheckpointWriter(
  /** Commits one batch in one transaction; [Database.write] in production. */
  private val write: (Batch) -> Unit,
  private val log: (String) -> Unit = {},
) {
  private val lock = ReentrantLock()
  private val changed = lock.newCondition()
  private val queue = ArrayDeque<Batch>()
  private var inFlight: Batch? = null
  private var closing = false

  /** False after a failed write, until one succeeds. */
  @Volatile
  var healthy: Boolean = true
    private set

  @Volatile
  var lastError: String? = null
    private set

  /** Batches committed so far, for tests and metrics. */
  @Volatile
  var committed: Long = 0
    private set

  /**
   * Called after each commit with the committed batch and how long it took in nanoseconds, on the
   * writer thread.
   */
  @Volatile var onCommit: ((Batch, Long) -> Unit)? = null

  private val thread =
    Thread(::loop, "gups-checkpoint-writer").apply {
      isDaemon = true
      start()
    }

  fun submit(b: Batch) =
    lock.withLock {
      check(!closing) { "the checkpoint writer is closed" }
      val tail = queue.lastOrNull()
      if (!b.immediate && tail != null && !tail.immediate && tail !== inFlight) tail.merge(b)
      else queue.addLast(b)
      changed.signalAll()
    }

  /** The capture pulse of the oldest world change not yet committed, or null if all is written. */
  val oldestPending: Long?
    get() = lock.withLock { queue.filter { it.accounts.isEmpty() }.minOfOrNull { it.firstPulse } }

  /** Batches waiting, including one being written. */
  val backlog: Int
    get() = lock.withLock { queue.size }

  /** Waits until everything submitted so far is committed, or [timeoutMs] passes. */
  fun flush(timeoutMs: Long = 30_000): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    lock.withLock {
      while (queue.isNotEmpty()) {
        val left = deadline - System.currentTimeMillis()
        if (left <= 0) return false
        changed.await(left, TimeUnit.MILLISECONDS)
      }
    }
    return true
  }

  /** Commits what is queued, then stops. */
  fun close(timeoutMs: Long = 30_000): Boolean {
    val done = flush(timeoutMs)
    lock.withLock {
      closing = true
      changed.signalAll()
    }
    thread.join(timeoutMs)
    return done
  }

  private fun loop() {
    var backoff = 50L
    while (true) {
      val b =
        lock.withLock {
          while (queue.isEmpty() && !closing) changed.await()
          if (queue.isEmpty()) return
          queue.first().also { inFlight = it }
        }
      val started = System.nanoTime()
      try {
        write(b)
        lock.withLock {
          queue.removeFirst()
          inFlight = null
          committed++
          changed.signalAll()
        }
        healthy = true
        lastError = null
        backoff = 50L
        onCommit?.invoke(b, System.nanoTime() - started)
      } catch (e: Exception) {
        lock.withLock { inFlight = null }
        if (healthy) log("checkpoint write failed, will retry: $e")
        healthy = false
        lastError = e.toString()
        if (closing) return
        Thread.sleep(backoff)
        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
      }
    }
  }
}
