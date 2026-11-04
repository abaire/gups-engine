package gups.core

import kotlin.random.Random

/**
 * The simulation's authoritative source of pseudo-randomness.
 *
 * Wraps `kotlin.random.Random(seed)`, which uses the deterministic XorWow generator. The same seed
 * and sequence of method invocations produce identical results on every platform, ensuring
 * reproducible simulation runs and test replays.
 *
 * Tests can queue forced results with [force]: each later [int] (and so [chance]) or [fraction]
 * call takes the next queued value of its kind before falling back to the seeded stream. A forced
 * draw doesn't advance the seeded stream.
 */
class Rng(seed: Long) {
  private val random = Random(seed)
  private val forcedInts = ArrayDeque<Int>()
  private val forcedFractions = ArrayDeque<Double>()

  /** How many forced values are still queued. */
  val forcedRemaining: Int
    get() = forcedInts.size + forcedFractions.size

  /** Queues [ints] and [fractions] after any values still queued. */
  fun force(ints: List<Int> = emptyList(), fractions: List<Double> = emptyList()) {
    for (f in fractions) {
      require(f >= 0.0 && f < 1.0) { "rng: a forced fraction must be in [0, 1), got $f" }
    }
    forcedInts.addAll(ints)
    forcedFractions.addAll(fractions)
  }

  /** Drops every forced value still queued. */
  fun clearForced() {
    forcedInts.clear()
    forcedFractions.clear()
  }

  /** Returns a uniformly distributed pseudo-random integer in `[lo, hi]`, inclusive. */
  fun int(lo: Int, hi: Int): Int {
    require(lo <= hi) { "rng.int($lo, $hi): lo must not exceed hi" }
    forcedInts.removeFirstOrNull()?.let {
      check(it in lo..hi) { "rng.int($lo, $hi): the forced value $it is out of range" }
      return it
    }
    return random.nextInt(lo, hi + 1)
  }

  /** Returns true with probability [pct] percent (1..100). */
  fun chance(pct: Int): Boolean = int(1, 100) <= pct

  /**
   * Returns a uniformly distributed pseudo-random double in the half-open interval `[0.0, 1.0)`.
   */
  fun fraction(): Double = forcedFractions.removeFirstOrNull() ?: random.nextDouble()

  /** Returns a uniformly chosen random element from non-empty [items]. */
  fun <T> choice(items: List<T>): T {
    require(items.isNotEmpty()) { "rng.choice of an empty list" }
    return items[random.nextInt(items.size)]
  }
}
