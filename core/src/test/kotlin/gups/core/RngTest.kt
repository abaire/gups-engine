package gups.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RngTest {
  @Test
  fun `forced ints feed int and chance, and must lie in the requested range`() {
    val rng = Rng(1)
    rng.force(ints = listOf(3, 100, 1))
    assertEquals(3, rng.int(1, 6))
    assertFalse(rng.chance(50))
    assertTrue(rng.chance(1))
    rng.force(ints = listOf(7))
    assertFailsWith<IllegalStateException> { rng.int(1, 6) }
  }

  @Test
  fun `passes is certain at the bounds without a draw, and rolls once in between`() {
    val rng = Rng(1)
    // Certain outcomes leave a queued int untouched.
    rng.force(ints = listOf(5))
    assertTrue(rng.passes(100))
    assertTrue(rng.passes(150))
    assertFalse(rng.passes(0))
    assertFalse(rng.passes(-5))
    assertEquals(1, rng.forcedRemaining, "certain chances must not draw")
    // A 1..99 chance draws exactly one int and passes when it is at most the percent.
    rng.clearForced()
    rng.force(ints = listOf(30))
    assertTrue(rng.passes(30))
    rng.force(ints = listOf(31))
    assertFalse(rng.passes(30))
    assertEquals(0, rng.forcedRemaining)
  }
}
