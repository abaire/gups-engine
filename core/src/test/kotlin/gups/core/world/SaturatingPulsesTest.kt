package gups.core.world

import gups.core.Role
import gups.core.pulsesToSeconds
import gups.core.saturatingAdd
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SaturatingPulsesTest {
  @Test
  fun `saturatingAdd caps overflow at the largest long`() {
    assertEquals(12L, saturatingAdd(5, 7))
    assertEquals(0L, saturatingAdd(0, 0))
    assertEquals(Long.MAX_VALUE, saturatingAdd(5, Long.MAX_VALUE))
  }

  @Test
  fun `pulsesToSeconds never overflows for huge pulse counts`() {
    assertEquals(0L, pulsesToSeconds(3))
    assertEquals(1L, pulsesToSeconds(4))
    val huge = pulsesToSeconds(Long.MAX_VALUE)
    assertTrue(huge >= 0L)
    assertTrue(huge >= pulsesToSeconds(1_000_000))
  }

  @Test
  fun `scaledDuration mirrors scaled and saturates`() {
    assertEquals(Long.MAX_VALUE, scaledDuration(Long.MAX_VALUE, 200))
    assertEquals(1L, scaledDuration(1, 50))
    assertEquals(100L, scaledDuration(200, 50))
    assertEquals(scaled(10, 0).toLong(), scaledDuration(10, 0))
  }

  @Test
  fun `an actor affect with a 64-bit duration never expires and displays`() {
    val f = Fixture()
    val p =
      f.player("Ann").also {
        it.role = Role.BUILDER
        it.scope = setOf("t")
        it.accountId = 1
      }
    val a = f.w.applyAffect(p, "forever", Long.MAX_VALUE)
    assertEquals(Long.MAX_VALUE, a!!.expiresAt)
    f.run(1000)
    assertEquals(1, p.affects.size, "still on the actor after 1000 pulses")
    @Suppress("UNCHECKED_CAST")
    val affects = f.w.gmcpPayload(p, "Char.Affects") as List<Map<String, Any?>>
    assertEquals(Long.MAX_VALUE / 1000, affects.single()["remaining"])
    f.input(p, "@stat #${p.id.v}")
    val display = f.out(p)
    assertContains(display, "forever (${Long.MAX_VALUE / 1000}s left)")
    assertFalse("-" in display.substringAfter("Affects: ").substringBefore(")"), "no negative time")
  }
}
