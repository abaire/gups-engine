package gups.core.model

import kotlin.math.abs

/** Relative distance on a room's track, as seen by a viewer (SPEC §5.5). */
enum class Band {
  MELEE,
  NEAR,
  FAR;

  val word: String
    get() = name.lowercase()

  /** True if something at [other] distance is within this band's reach. */
  fun reaches(other: Band): Boolean = other.ordinal <= ordinal

  companion object {
    fun between(a: Int, b: Int): Band =
      when (abs(a - b)) {
        0 -> MELEE
        1 -> NEAR
        else -> FAR
      }

    fun parse(word: String): Band = valueOf(word.uppercase())
  }
}

/** A room's track size (SPEC §3.3). */
enum class TrackSize(val slots: Int) {
  CRAMPED(1),
  NORMAL(3),
  LARGE(5),
}

/** Perception channels an exit may block (SPEC §3.3, §5.7). */
enum class Channel {
  SIGHT,
  SOUND,
  NOISE,
  SPELL;

  companion object {
    val ALL: Set<Channel> = entries.toSet()
  }
}

enum class DoorState {
  OPEN,
  CLOSED,
  LOCKED,
}

enum class Sense {
  HEARING,
  SIGHT,
}

enum class DormantRecovery {
  FULL,
  REGEN,
  NONE,
}

/** An exit name. The standard compass and vertical directions have aliases and opposites. */
@JvmInline
value class Direction(val name: String) {
  val opposite: Direction?
    get() = OPPOSITES[name]?.let(::Direction)

  override fun toString(): String = name

  companion object {
    val NORTH = Direction("north")
    val EAST = Direction("east")
    val SOUTH = Direction("south")
    val WEST = Direction("west")
    val UP = Direction("up")
    val DOWN = Direction("down")
    val NORTHEAST = Direction("northeast")
    val NORTHWEST = Direction("northwest")
    val SOUTHEAST = Direction("southeast")
    val SOUTHWEST = Direction("southwest")

    val STANDARD: List<Direction> =
      listOf(NORTH, EAST, SOUTH, WEST, UP, DOWN, NORTHEAST, NORTHWEST, SOUTHEAST, SOUTHWEST)

    private val ALIASES =
      mapOf(
        "n" to "north",
        "e" to "east",
        "s" to "south",
        "w" to "west",
        "u" to "up",
        "d" to "down",
        "ne" to "northeast",
        "nw" to "northwest",
        "se" to "southeast",
        "sw" to "southwest",
      )

    private val OPPOSITES =
      mapOf(
        "north" to "south",
        "south" to "north",
        "east" to "west",
        "west" to "east",
        "up" to "down",
        "down" to "up",
        "northeast" to "southwest",
        "southwest" to "northeast",
        "northwest" to "southeast",
        "southeast" to "northwest",
      )

    /** Resolves aliases such as `n`; any other word is taken as a custom exit name. */
    fun parse(word: String): Direction = Direction(ALIASES[word.lowercase()] ?: word.lowercase())
  }
}

/** A damage type such as `fire` or `pierce`. Free-form; the rules interpret it. */
@JvmInline
value class DamageType(val name: String) {
  override fun toString(): String = name

  companion object {
    val BLUDGEON = DamageType("bludgeon")
    val SLASH = DamageType("slash")
    val PIERCE = DamageType("pierce")
    val FIRE = DamageType("fire")
  }
}
