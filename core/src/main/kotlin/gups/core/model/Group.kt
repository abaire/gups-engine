package gups.core.model

/** An in-memory set of players with a leader; session-scoped, never saved. */
class Group(val id: Int, var leader: Player) {
  /**
   * Members in join order; the display order (leader first, then ascending id) is applied by
   * queries.
   */
  val members: LinkedHashSet<Player> = linkedSetOf(leader)

  /** Set once the group is disbanded; a hook may disband it while an earlier change is reported. */
  var dissolved: Boolean = false
    internal set
}

/** A group as seen by the `on_group_change` hook: members leader first, then ascending id. */
data class GroupSnapshot(val id: Int, val leader: Player, val members: List<Player>)
