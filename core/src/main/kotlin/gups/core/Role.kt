package gups.core

/**
 * Account roles arranged in strict hierarchical order: `PLAYER < BUILDER < INSTRUCTOR < ADMIN`. A
 * higher role implies and inherits all capabilities of lower roles.
 */
enum class Role {
  /** Regular player role with standard gameplay commands and privileges. */
  PLAYER,

  /**
   * World builder role permitted to use builder tools and edit packages within their assigned
   * scope.
   */
  BUILDER,

  /**
   * Instructor role permitted to manage accounts, issue restrictions, create invite/setup codes,
   * and inspect players.
   */
  INSTRUCTOR,

  /**
   * Full administrative control over server configuration, maintenance, and database operations.
   */
  ADMIN;

  /** True if this role is equal to or higher in authority than [other]. */
  fun atLeast(other: Role): Boolean = ordinal >= other.ordinal
}
