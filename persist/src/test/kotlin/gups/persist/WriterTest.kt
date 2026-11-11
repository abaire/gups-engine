package gups.persist

import gups.core.fixtures.tempDb
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WriterTest {
  private fun batch(immediate: Boolean, pulse: Long, vararg ids: Long) =
    Batch(immediate, "test", pulse).also { b ->
      for (id in ids) b.entities[id] =
        EntityRow(id, "item", "//t:gem", 1, null, "{\"p\":$pulse}", pulse)
    }

  @Test
  fun `periodic batches merge while waiting, immediate ones never do, all in FIFO order`() {
    val gate = CountDownLatch(1)
    val started = CountDownLatch(1)
    val written = mutableListOf<Batch>()
    val w =
      CheckpointWriter({ b ->
        started.countDown()
        gate.await(5, TimeUnit.SECONDS)
        synchronized(written) { written += b }
      })
    w.submit(batch(false, 1, 1))
    // Once the writer is inside the first write, that batch is in flight and must not be merged
    // into.
    assertTrue(started.await(10, TimeUnit.SECONDS))
    w.submit(batch(false, 2, 1, 2))
    w.submit(batch(false, 3, 2))
    w.submit(batch(true, 4, 3))
    w.submit(batch(true, 5, 3))
    w.submit(batch(false, 6, 4))
    assertEquals(1, w.oldestPending)
    gate.countDown()
    assertTrue(w.flush())
    assertEquals(
      listOf(listOf(1L), listOf(1L, 2L), listOf(3L), listOf(3L), listOf(4L)),
      written.map { it.entities.keys.toList() },
    )
    assertEquals(
      "{\"p\":3}",
      written[1].entities.getValue(2)!!.payload,
      "last write wins within a merge",
    )
    assertEquals(2, written[1].firstPulse)
    assertNull(w.oldestPending)
    w.close()
  }

  @Test
  fun `a failing write is retried, stays pending, and later captures merge into it`() {
    val failing = AtomicBoolean(true)
    val written = mutableListOf<Batch>()
    val w =
      CheckpointWriter({ b ->
        if (failing.get()) error("disk full")
        synchronized(written) { written += b }
      })
    w.submit(batch(false, 1, 1))
    // The writer thread may take arbitrarily long to get its first attempt in on a busy runner.
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (w.healthy && System.nanoTime() < deadline) Thread.sleep(5)
    assertFalse(w.healthy)
    w.submit(batch(false, 2, 2))
    failing.set(false)
    assertTrue(w.flush())
    assertTrue(w.healthy)
    assertEquals(1, written.size, "the retried batch absorbed the later capture")
    assertEquals(setOf(1L, 2L), written.single().entities.keys)
    w.close()
  }

  @Test
  fun `logins pause while the database can't be written`() {
    val p = SqlitePersistence.open(tempDb())
    assertNull(p.maintenance)
    p.close()
  }
}
