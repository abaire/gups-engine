package gups.core

/** Account roles, ordered: a higher role implies the lower ones. */
enum class Role {
  PLAYER,
  BUILDER,
  INSTRUCTOR,
  ADMIN;

  fun atLeast(other: Role): Boolean = ordinal >= other.ordinal
}
