package gups.core

import kotlin.random.Random

/**
 * The world's only source of randomness (SPEC §4.5). Seeded, so the same seed and inputs
 * produce the same draws on every platform.
 */
class Rng(seed: Long) {
    private val random = Random(seed)

    /** A uniform int in `lo..hi`, inclusive. */
    fun int(lo: Int, hi: Int): Int {
        require(lo <= hi) { "rng.int($lo, $hi): lo must not exceed hi" }
        return random.nextInt(lo, hi + 1)
    }

    /** True with probability [pct] percent. */
    fun chance(pct: Int): Boolean = int(1, 100) <= pct

    /** A uniform double in `[0, 1)`. */
    fun fraction(): Double = random.nextDouble()

    fun <T> choice(items: List<T>): T {
        require(items.isNotEmpty()) { "rng.choice of an empty list" }
        return items[random.nextInt(items.size)]
    }
}
