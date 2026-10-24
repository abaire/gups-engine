package gups.persist

import gups.core.Account
import gups.core.Invite
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One checkpoint (SPEC §5.11): JSON rows captured on the world thread. `null` in [entities]
 * deletes that row. Periodic batches merge, last write wins per entity; immediate ones never
 * merge past one another.
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
    /** Account changes, written before the world rows in the same transaction (D-128). */
    val accounts: MutableList<AccountOp> = mutableListOf(),
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
    }
}

/** One account, password or invite change (SPEC §3.6, M6 design §2.3). */
sealed interface AccountOp {
    /** Inserts [account] unless its login is taken; [result] gets the new id, or null. */
    class Create(val account: Account) : AccountOp {
        val result: CompletableFuture<Long?> = CompletableFuture()
    }

    class Update(val account: Account) : AccountOp

    /** Also revokes the account's session tokens. */
    class SetPassword(val id: Long, val pwHash: String?) : AccountOp

    class AddInvite(val invite: Invite) : AccountOp

    /** [result] is false if the invite was already used. */
    class UseInvite(val codeHash: String, val accountId: Long) : AccountOp {
        val result: CompletableFuture<Boolean> = CompletableFuture()
    }
}

/** How long a failing write waits before it tries again, at most (SPEC §6.4). */
private const val MAX_BACKOFF_MS = 5_000L

/**
 * The only SQLite writer (SPEC §2.5): commits batches in FIFO order, each in one transaction,
 * on its own thread so the world thread never waits (D-096). A failed write stays at the head
 * of the queue and is retried with backoff; while it waits, later periodic captures merge into
 * it.
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
    @Volatile var healthy: Boolean = true
        private set

    @Volatile var lastError: String? = null
        private set

    /** Batches committed so far, for tests and metrics. */
    @Volatile var committed: Long = 0
        private set

    /** Called after each commit with the committed batch, on the writer thread. */
    @Volatile var onCommit: ((Batch) -> Unit)? = null

    private val thread = Thread(::loop, "gups-checkpoint-writer").apply {
        isDaemon = true
        start()
    }

    fun submit(b: Batch) = lock.withLock {
        check(!closing) { "the checkpoint writer is closed" }
        val tail = queue.lastOrNull()
        if (!b.immediate && tail != null && !tail.immediate && tail !== inFlight) tail.merge(b) else queue.addLast(b)
        changed.signalAll()
    }

    /** The capture pulse of the oldest world change not yet committed, or null if all is written. */
    val oldestPending: Long? get() = lock.withLock { queue.filter { it.accounts.isEmpty() }.minOfOrNull { it.firstPulse } }

    /** Batches waiting, including one being written. */
    val backlog: Int get() = lock.withLock { queue.size }

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
            val b = lock.withLock {
                while (queue.isEmpty() && !closing) changed.await()
                if (queue.isEmpty()) return
                queue.first().also { inFlight = it }
            }
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
                onCommit?.invoke(b)
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
