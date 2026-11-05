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

  override fun toString(): String =
    "${count}d$sides" +
      when {
        bonus > 0 -> "+$bonus"
        bonus < 0 -> "$bonus"
        else -> ""
      }

  companion object {
    private val PATTERN = Regex("(\\d+)d(\\d+)([+-]\\d+)?")

    fun parse(text: String): Dice {
      val m = requireNotNull(PATTERN.matchEntire(text.trim())) { "Malformed dice '$text'" }
      return Dice(
        m.groupValues[1].toInt(),
        m.groupValues[2].toInt(),
        m.groupValues[3].ifEmpty { "0" }.toInt(),
      )
    }
  }
}

/** The v0 effect primitives. */
sealed interface Effect {
  data class Damage(val dice: Dice, val type: DamageType) : Effect

  data class Heal(val dice: Dice, val vital: String) : Effect

  data class ApplyAffect(
    val name: String,
    /** Pulses, 64-bit; null only for a permanent item affect. */
    val duration: Long?,
    val statDeltas: Map<String, Int> = emptyMap(),
    val flags: Set<String> = emptySet(),
    val tickEvery: Int? = null,
    val onTick: HookRef? = null,
    val onExpire: HookRef? = null,
    val expireMessage: String = "",
    val modifiers: Modifiers = emptyMap(),
    val senseDeltas: Map<Channel, Int> = emptyMap(),
    /** Vital name to a change in that vital's maximum while the affect lasts. */
    val maxVitalDeltas: Map<String, Int> = emptyMap(),
    /** Damage type to a change in resistance while the affect lasts. */
    val resistDeltas: Map<DamageType, Int> = emptyMap(),
    /** Set only on an item skill's effect. */
    val applies: AffectScope? = null,
    val procs: List<Proc> = emptyList(),
    /** Tags a room gains while the affect lasts; used only on a room. */
    val addTags: Set<String> = emptySet(),
    val removeTags: Set<String> = emptySet(),
  ) : Effect

  /**
   * Removes the target's affects that match every given criterion: [name] (exact, or a prefix when
   * it ends in `*`), any of [flags], or anything when [all]. At most [count] are removed, in the
   * order they were applied; `null` removes every match. Affects flagged `intrinsic` are never
   * removed.
   */
  data class Dispel(
    val name: String? = null,
    val flags: Set<String> = emptySet(),
    val all: Boolean = false,
    val count: Int? = null,
  ) : Effect {
    init {
      require(name != null || flags.isNotEmpty() || all) { "dispel needs a name, a flag or all" }
      require(count == null || count >= 1) { "dispel count must be at least 1" }
    }

    /** Whether this dispel would remove [affect], ignoring [count]. */
    fun matches(affect: Affect): Boolean =
      "intrinsic" !in affect.flags &&
        (name == null ||
          if (name.endsWith("*")) affect.name.startsWith(name.dropLast(1))
          else affect.name == name) &&
        (flags.isEmpty() || flags.any { it in affect.flags })
  }

  data class Knockback(val steps: Int) : Effect

  data class Pull(val steps: Int) : Effect

  data class Stun(val pulses: Int) : Effect

  data class Summon(val label: Label, val count: Int = 1, val duration: Int? = null) : Effect

  data class Sequence(val effects: List<Effect>) : Effect {
    constructor(vararg effects: Effect) : this(effects.toList())
  }

  /**
   * An effect primitive a plugin adds. [args] holds only plain values (String, Long, Double,
   * Boolean, null, List, Map), so prototypes still compare by value.
   */
  data class Plugin(val pluginId: String, val name: String, val args: Map<String, Any?>) : Effect
}

/** When a proc fires during an auto-attack swing. */
enum class ProcTrigger(val word: String) {
  /** The attacker's procs, after a landed swing's damage. */
  HIT("hit"),

  /** The defender's procs, right after the attacker's for the same hit. */
  STRUCK("struck"),

  /** The defender's procs, when a swing at it misses. */
  AVOID("avoid");

  companion object {
    fun byWord(w: String): ProcTrigger? = entries.firstOrNull { it.word == w }
  }
}

/** Whom a proc outcome lands on. */
enum class ProcTarget(val word: String) {
  /** The other actor in the swing. */
  ENEMY("enemy"),

  /** The proc's bearer. */
  SELF("self"),

  /** No actor (a `target = "none"` skill). */
  NONE("none");

  companion object {
    fun byWord(w: String): ProcTarget? = entries.firstOrNull { it.word == w }
  }
}

/**
 * One possible result of a proc. With neither [skill] nor [effect] it does nothing. A null [target]
 * follows the skill's own target, or means [ProcTarget.ENEMY] for an effect.
 */
data class ProcOutcome(
  val weight: Double,
  val skill: Label? = null,
  val effect: Effect? = null,
  val target: ProcTarget? = null,
  val message: String? = null,
) {
  init {
    require(weight in 0.0..1.0) { "outcome weight must be between 0 and 1" }
    require(skill == null || effect == null) { "an outcome takes a skill or an effect, not both" }
  }
}

/** A chance, on [on], to resolve one of [outcomes] for the bearer. */
data class Proc(val on: ProcTrigger, val chance: Double, val outcomes: List<ProcOutcome>) {
  init {
    require(chance in 0.0..1.0) { "proc chance must be between 0 and 1" }
    require(outcomes.isNotEmpty()) { "a proc needs at least one outcome" }
    require(outcomes.sumOf { it.weight } <= 1.0 + 1e-9) {
      "proc outcome weights add up to more than 1"
    }
  }
}
