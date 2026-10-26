package gups.persist

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    val written = mutableListOf<Batch>()
    val w =
      CheckpointWriter({ b ->
        gate.await(5, TimeUnit.SECONDS)
        synchronized(written) { written += b }
      })
    w.submit(batch(false, 1, 1))
    Thread.sleep(50) // the first batch is now in flight and must not be merged into
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
    var failures = 3
    val written = mutableListOf<Batch>()
    val w =
      CheckpointWriter({ b ->
        if (failures-- > 0) error("disk full")
        written += b
      })
    w.submit(batch(false, 1, 1))
    Thread.sleep(20)
    assertFalse(w.healthy)
    w.submit(batch(false, 2, 2))
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
