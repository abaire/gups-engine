package gups.auth

import gups.core.ClientInputEvent
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.loop.WorldLoop
import gups.core.world.World
import gups.core.world.WorldConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WorldThreadTest {
  /** Takes a full second to check a password. */
  private class SlowHasher(private val inner: PasswordHasher) : PasswordHasher {
    val started = CountDownLatch(1)

    @Volatile var thread: Thread? = null

    override fun hash(password: String) = inner.hash(password)

    override fun verify(password: String, hash: String): Boolean {
      thread = Thread.currentThread()
      started.countDown()
      Thread.sleep(1000)
      return inner.verify(password, hash)
    }
  }

  @Test
  fun `a slow password hash never stalls the pulse`() {
    val store = MemoryAccountStore()
    val cheap = Argon2Hasher(memoryKiB = 8, iterations = 1)
    store.create(gups.core.Account(0, "ann", cheap.hash("correct horse")))
    val hasher = SlowHasher(cheap)
    val w = World(testProtos(), config = WorldConfig(seed = 1))
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
      val before = w.pulse
      val deadline = System.nanoTime() + 5_000_000_000
      while ("Your characters" !in sink.transcript && "no characters" !in sink.transcript) {
        check(System.nanoTime() < deadline) { "never logged in:\n${sink.transcript}" }
        Thread.sleep(10)
      }
      val pulsed = w.pulse - before
      // 1 s at 20 ms a pulse is 50 pulses; allow for a slow machine.
      assertTrue(pulsed >= 20, "only $pulsed pulses ran during the hash")
      assertNotSame(worldThread, hasher.thread)
      assertContains(sink.transcript, "Type a name to play")
    } finally {
      loop.stop()
      runner.join(5000)
      pool.shutdownNow()
    }
  }
}
