package gups.core.world

import gups.core.Role
import gups.core.pulsesToSeconds
import gups.core.saturatingAdd
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
  fun `scaledDuration saturates only when the scaled result overflows`() {
    val big = Long.MAX_VALUE / 120
    assertEquals(big / 100 * 150 + big % 100 * 150 / 100, scaledDuration(big, 150))
    assertEquals((Long.MAX_VALUE - 1) / 2, scaledDuration(Long.MAX_VALUE - 1, 50))
    assertEquals(Long.MAX_VALUE, scaledDuration(Long.MAX_VALUE / 2, 300))
  }

  @Test
  fun `a SEMI_PERMANENT duration is never scaled`() {
    assertEquals(Long.MAX_VALUE, scaledDuration(Long.MAX_VALUE, 50))
    assertEquals(Long.MAX_VALUE, scaledDuration(Long.MAX_VALUE, 1))
    assertEquals(Long.MAX_VALUE, scaledDuration(Long.MAX_VALUE, 0))
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
    val a = f.w.applyAffect(p, AffectSpec("forever", Long.MAX_VALUE))
    assertEquals(Long.MAX_VALUE, a!!.expiresAt)
    f.run(1000)
    assertEquals(1, p.affects.size, "still on the actor after 1000 pulses")
    @Suppress("UNCHECKED_CAST")
    val affects = f.w.gmcpPayload(p, "Char.Affects") as List<Map<String, Any?>>
    assertTrue(affects.single().containsKey("remaining"))
    assertNull(affects.single()["remaining"], "a never-ending affect has no remaining time")
    f.input(p, "@stat #${p.id.v}")
    val display = f.out(p)
    assertContains(display, "forever (permanent)")
    assertFalse("-" in display.substringAfter("Affects: ").substringBefore(")"), "no negative time")
  }
}
