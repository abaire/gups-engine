package gups.core.model

import gups.core.HookRef
import gups.core.Label
import gups.core.Rng

/** A dice expression such as `4d6` or `1d8+2`. */
data class Dice(val count: Int, val sides: Int, val bonus: Int = 0) {
    init {
        require(count >= 0 && sides >= 1) { "Bad dice $this" }
    }

    fun roll(rng: Rng): Int = (1..count).sumOf { rng.int(1, sides) } + bonus

    override fun toString(): String = "${count}d$sides" + when {
        bonus > 0 -> "+$bonus"
        bonus < 0 -> "$bonus"
        else -> ""
    }

    companion object {
        private val PATTERN = Regex("(\\d+)d(\\d+)([+-]\\d+)?")

        fun parse(text: String): Dice {
            val m = requireNotNull(PATTERN.matchEntire(text.trim())) { "Malformed dice '$text'" }
            return Dice(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { "0" }.toInt())
        }
    }
}

/** The v0 effect primitives (SPEC §4.3). */
sealed interface Effect {
    data class Damage(val dice: Dice, val type: DamageType) : Effect

    data class Heal(val dice: Dice) : Effect

    data class ApplyAffect(
        val name: String,
        val duration: Int,
        val statDeltas: Map<String, Int> = emptyMap(),
        val flags: Set<String> = emptySet(),
        val tickEvery: Int? = null,
        val onTick: HookRef? = null,
        val onExpire: HookRef? = null,
        val expireMessage: String = "",
    ) : Effect

    /** Removes affects whose name matches [filter]; a trailing `*` matches a prefix. */
    data class Dispel(val filter: String) : Effect

    data class Knockback(val steps: Int) : Effect

    data class Pull(val steps: Int) : Effect

    data class Stun(val pulses: Int) : Effect

    data class Summon(val label: Label, val count: Int = 1, val duration: Int? = null) : Effect

    data class Sequence(val effects: List<Effect>) : Effect {
        constructor(vararg effects: Effect) : this(effects.toList())
    }
}
