package gups.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RngTest {
  @Test
  fun `forced fractions are returned in order, then the seeded stream resumes`() {
    val seeded = Rng(7)
    val expected = listOf(seeded.fraction(), seeded.fraction())
    val rng = Rng(7)
    rng.force(fractions = listOf(0.25, 0.0))
    assertEquals(0.25, rng.fraction())
    assertEquals(0.0, rng.fraction())
    assertEquals(expected, listOf(rng.fraction(), rng.fraction()))
  }

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
  fun `forced fractions must lie in 0 inclusive to 1 exclusive`() {
    assertFailsWith<IllegalArgumentException> { Rng(1).force(fractions = listOf(1.0)) }
    assertFailsWith<IllegalArgumentException> { Rng(1).force(fractions = listOf(-0.1)) }
  }

  @Test
  fun `forcing appends to what is still queued and clearForced drops it`() {
    val rng = Rng(1)
    rng.force(fractions = listOf(0.1))
    rng.force(fractions = listOf(0.2))
    assertEquals(0.1, rng.fraction())
    assertEquals(0.2, rng.fraction())
    rng.force(fractions = listOf(0.3), ints = listOf(2))
    assertEquals(2, rng.forcedRemaining)
    rng.clearForced()
    assertEquals(0, rng.forcedRemaining)
  }
}
