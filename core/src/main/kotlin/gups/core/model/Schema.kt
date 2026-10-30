package gups.core.model

/** A declared stat or vital: the name scripts use and the label players see. */
data class SchemaName(val name: String, val label: String)

/** The life state of an actor, as `rules.condition` last decided it. */
enum class Condition {
  OK,
  INCAPACITATED,
  DEAD;

  val word: String
    get() = name.lowercase()

  companion object {
    fun parse(word: String): Condition? = entries.firstOrNull { it.word == word }
  }
}

/** One vital of an actor. Written only through the world, which keeps the dependent state. */
class Vital internal constructor(current: Int, max: Int, belowZeroSince: Long? = null) {
  var current: Int = current
    internal set

  var max: Int = max
    internal set

  /** The pulse [current] last dropped below 0; null while it is 0 or more. */
  var belowZeroSince: Long? = belowZeroSince
    internal set

  override fun toString(): String = "$current/$max"
}

/** The names that [WorldSchema] records per database and compares at each start. */
data class SchemaNames(
  val stats: List<String>,
  val vitals: List<String>,
  val damageTypes: List<String>,
  val channels: List<String>,
)

/**
 * What a world declares in `MODULE.gups`: the names its actors are measured by. The engine names no
 * stat, vital or damage type itself.
 */
data class WorldSchema(
  val stats: List<SchemaName>,
  val vitals: List<SchemaName>,
  val damageTypes: List<String>,
  val unarmedDamageType: String,
  /** Channels the world declares, beyond the built-in ones. */
  val channels: List<String> = emptyList(),
  /** The prompt template, or null for the default. */
  val prompt: String? = null,
  /**
   * Channels from strongest to weakest for text that is told as it happens; see [perceptionOrder].
   */
  val channelPriority: List<String> = emptyList(),
) {
  val statNames: List<String> = stats.map { it.name }
  val vitalNames: List<String> = vitals.map { it.name }

  fun hasStat(name: String): Boolean = name in statNames

  fun hasVital(name: String): Boolean = name in vitalNames

  fun hasDamageType(name: String): Boolean = name in damageTypes

  fun hasChannel(name: String): Boolean =
    name in channels || Channel.BUILT_IN.any { it.name == name }

  /** Every channel a skill or exit may name: the built-in ones, then the declared ones. */
  val allChannels: List<Channel>
    get() = Channel.BUILT_IN + channels.map(::Channel)

  /**
   * Every channel from the one whose rendering of an event a player is told first to the last:
   * [channelPriority], then the channels it leaves out in their usual order.
   */
  val perceptionOrder: List<Channel>
    get() = (channelPriority.map(::Channel) + allChannels).distinct()

  fun statLabel(name: String): String = stats.first { it.name == name }.label

  fun vitalLabel(name: String): String = vitals.first { it.name == name }.label

  val unarmed: DamageType
    get() = DamageType(unarmedDamageType)

  val names: SchemaNames
    get() = SchemaNames(statNames, vitalNames, damageTypes, channels)

  /** The prompt template: [prompt], or `<` then `{v}v` for each vital, then `> `. */
  val promptTemplate: String
    get() = prompt ?: vitalNames.joinToString(" ", "<", "> ") { "{$it}$it" }

  /** The ` (did you mean 'x'?)` hint for a misspelled [name] among [known], or an empty string. */
  fun suggest(name: String, known: Collection<String>): String = didYouMean(name, known)

  companion object {
    /** A schema with no declarations, for code that has no world. */
    val EMPTY = WorldSchema(emptyList(), emptyList(), emptyList(), "")
  }
}

/** Formats the closest of [known] to [name] as a suggestion, or returns an empty string. */
fun didYouMean(name: String, known: Collection<String>): String {
  val best =
    known
      .minByOrNull { editDistance(name, it) }
      ?.takeIf { editDistance(name, it) <= maxOf(1, (name.length + 1) / 3) } ?: return ""
  return " (did you mean '$best'?)"
}

/** Optimal string alignment distance: insertions, deletions, substitutions and swaps. */
private fun editDistance(a: String, b: String): Int {
  val d = Array(a.length + 1) { IntArray(b.length + 1) }
  for (i in 0..a.length) d[i][0] = i
  for (j in 0..b.length) d[0][j] = j
  for (i in 1..a.length) {
    for (j in 1..b.length) {
      val cost = if (a[i - 1] == b[j - 1]) 0 else 1
      d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
      if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1])
        d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
    }
  }
  return d[a.length][b.length]
}
