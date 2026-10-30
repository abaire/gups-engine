package gups.core

import kotlin.random.Random

/**
 * The simulation's authoritative source of pseudo-randomness.
 *
 * Wraps `kotlin.random.Random(seed)`, which uses the deterministic XorWow generator. The same seed
 * and sequence of method invocations produce identical results on every platform, ensuring
 * reproducible simulation runs and test replays.
 */
class Rng(seed: Long) {
  private val random = Random(seed)

  /** Returns a uniformly distributed pseudo-random integer in `[lo, hi]`, inclusive. */
  fun int(lo: Int, hi: Int): Int {
    require(lo <= hi) { "rng.int($lo, $hi): lo must not exceed hi" }
    return random.nextInt(lo, hi + 1)
  }

  /** Returns true with probability [pct] percent (1..100). */
  fun chance(pct: Int): Boolean = int(1, 100) <= pct

  /**
   * Returns a uniformly distributed pseudo-random double in the half-open interval `[0.0, 1.0)`.
   */
  fun fraction(): Double = random.nextDouble()

  /** Returns a uniformly chosen random element from non-empty [items]. */
  fun <T> choice(items: List<T>): T {
    require(items.isNotEmpty()) { "rng.choice of an empty list" }
    return items[random.nextInt(items.size)]
  }
}
