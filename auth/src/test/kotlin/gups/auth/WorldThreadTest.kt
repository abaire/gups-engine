package gups.auth

import gups.core.ClientInputEvent
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.fixtures.FixtureRules
import gups.core.fixtures.FixtureSchemas
import gups.core.loop.WorldLoop
import gups.core.world.World
import gups.core.world.WorldConfig
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WorldThreadTest {
  /** Blocks every password check until the test releases it. */
  private class SlowHasher(private val inner: PasswordHasher) : PasswordHasher {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)

    @Volatile var thread: Thread? = null

    override fun hash(password: String) = inner.hash(password)

    override fun verify(password: String, hash: String): Boolean {
      thread = Thread.currentThread()
      started.countDown()
      // The timeout only keeps a failed test from wedging the pool forever.
      release.await(30, TimeUnit.SECONDS)
      return inner.verify(password, hash)
    }
  }

  /** Reads the pulse on the world thread, which is the only place it is safe to read. */
  private fun pulseCount(loop: WorldLoop): Long {
    val pulse = CompletableFuture<Long>()
    loop.submit(WorldControlEvent.Task { pulse.complete(it.pulse) })
    return pulse.get(5, TimeUnit.SECONDS)
  }

  @Test
  fun `a slow password hash never stalls the pulse`() {
    val store = MemoryAccountStore()
    val cheap = Argon2Hasher(memoryKiB = 8, iterations = 1)
    store.create(gups.core.Account(0, "ann", cheap.hash("correct horse")))
    val hasher = SlowHasher(cheap)
    val w =
      World(
        testProtos(),
        FixtureSchemas.FANTASY.schema,
        FixtureRules(),
        config = WorldConfig(seed = 1),
      )
    w.persistence = MemoryPersistence()
    val loop = WorldLoop(w, pulseNanos = 20_000_000)
    val pool = Executors.newFixedThreadPool(2)
    w.login = AccountLogin(AccountService(store, hasher), HALL, pool, loop::submit)
    lateinit var worldThread: Thread
    val runner = thread {
      worldThread = Thread.currentThread()
      runBlocking { loop.run() }
    }
    try {
      val sink = RecordingSink()
      val id = SessionId(1)
      loop.submit(WorldControlEvent.Connect(id, sink, "10.0.0.1", "telnet"))
      loop.submit(ClientInputEvent(id, "ann", 0))
      loop.submit(ClientInputEvent(id, "correct horse", 0))
      assertTrue(hasher.started.await(5, TimeUnit.SECONDS))
      val before = pulseCount(loop)
      val advance = System.nanoTime() + 10_000_000_000
      while (pulseCount(loop) < before + 5) {
        check(System.nanoTime() < advance) { "the world stopped pulsing during the hash" }
        Thread.sleep(5)
      }
      assertFalse("Your characters" in sink.transcript || "no characters" in sink.transcript)
      hasher.release.countDown()
      val deadline = System.nanoTime() + 5_000_000_000
      while ("Your characters" !in sink.transcript && "no characters" !in sink.transcript) {
        check(System.nanoTime() < deadline) { "never logged in:\n${sink.transcript}" }
        Thread.sleep(10)
      }
      assertNotSame(worldThread, hasher.thread)
      assertContains(sink.transcript, "Type a name to play")
    } finally {
      hasher.release.countDown()
      loop.stop()
      runner.join(5000)
      assertFalse(runner.isAlive, "the world loop thread never exited")
      pool.shutdownNow()
    }
  }
}
