package gups.core.world

import gups.core.model.Actor
import gups.core.model.Group
import gups.core.model.GroupSnapshot
import gups.core.model.Mob
import gups.core.model.Player

/** The group [a] is in: a player's own, a pet's owner's, or null when there is none. */
fun World.groupOf(a: Actor): Group? {
  val player =
    when (a) {
      is Player -> a
      is Mob -> ownerOf(a) ?: return null
    }
  val id = player.groupId ?: return null
  return groups[id]
}

/** The members of [a]'s group, leader first then ascending id; empty when it has none. */
fun World.groupMembers(a: Actor): List<Player> {
  val group = groupOf(a) ?: return emptyList()
  return snapshotOf(group).members
}

/**
 * Adds [member] to [to]'s group, creating one with [to] as leader when [to] has none. A member
 * already in the same group is a no-op; one in any other group is an error.
 */
fun World.groupAdd(member: Player, to: Player) {
  require(member.inWorld) { "${member.name} is not in the world" }
  require(to.inWorld) { "${to.name} is not in the world" }
  require(member !== to) { "the member and the group's player must differ" }
  val memberGroup = member.groupId?.let { groups[it] }
  val toGroup = groupOf(to)
  if (memberGroup != null && memberGroup !== toGroup)
    throw IllegalArgumentException("${member.name} is already in a group")
  if (memberGroup != null) return
  val events = mutableListOf<GroupEvent>()
  val group =
    toGroup
      ?: Group(nextGroupId++, to).also {
        groups[it.id] = it
        to.groupId = it.id
        events += GroupEvent("join", to, snapshotOf(it))
      }
  group.members += member
  member.groupId = group.id
  events += GroupEvent("join", member, snapshotOf(group))
  report(group, events)
}

/**
 * Removes [member] from its group, firing "leave" and, when the leader left, "leader" for the
 * lowest-id remaining member; a group left with one member is disbanded.
 */
fun World.groupRemove(member: Player) {
  val group = groupOf(member) ?: return
  member.groupId = null
  group.members -= member
  val events = mutableListOf(GroupEvent("leave", member, snapshotOf(group)))
  if (group.members.isEmpty()) {
    group.dissolved = true
    groups.remove(group.id)
  } else {
    if (group.leader === member) {
      group.leader = group.members.minBy { it.id }
      events += GroupEvent("leader", group.leader, snapshotOf(group))
    }
    if (group.members.size == 1) events += dissolve(group, group.members.single())
  }
  report(group, events)
}

/** Makes [member] the leader of its group; it must be in one and not already lead it. */
fun World.groupSetLeader(member: Player) {
  val group = groupOf(member) ?: throw IllegalArgumentException("${member.name} is not in a group")
  require(group.leader !== member) { "${member.name} already leads the group" }
  group.leader = member
  report(group, listOf(GroupEvent("leader", member, snapshotOf(group))))
}

/** Dissolves [member]'s whole group at once, clearing every member and firing "disband". */
fun World.groupDisband(member: Player) {
  val group = groupOf(member) ?: return
  report(group, listOf(dissolve(group, member)))
}

/** Removes [p] from its group when it leaves the world; covers quit and link-dead expiry. */
internal fun World.leaveGroupOnRemoval(p: Player) {
  if (p.groupId != null) groupRemove(p)
}

/** The group's members leader first, then ascending id. */
private fun snapshotOf(group: Group): GroupSnapshot =
  GroupSnapshot(
    group.id,
    group.leader,
    if (group.leader in group.members) {
      listOf(group.leader) + group.members.filter { it !== group.leader }.sortedBy { it.id }
    } else {
      group.members.sortedBy { it.id }
    },
  )

/** One `on_group_change` call: the event, the player it names, and the group at that step. */
private class GroupEvent(val event: String, val actor: Player, val snapshot: GroupSnapshot)

/** Clears [group]'s members and deletes it; returns the one "disband" event, naming [member]. */
private fun World.dissolve(group: Group, member: Player): GroupEvent {
  val snapshot = snapshotOf(group)
  for (m in group.members) m.groupId = null
  group.dissolved = true
  groups.remove(group.id)
  return GroupEvent("disband", member, snapshot)
}

/**
 * Fires [events] in order once the change is complete, so no hook sees a half-made change. A hook
 * that disbands the group ends the report: what is left describes a group that no longer exists.
 */
private fun World.report(group: Group, events: List<GroupEvent>) {
  val wasDissolved = group.dissolved
  for (e in events) {
    if (!wasDissolved && group.dissolved) return
    dispatchGlobal(
      HookName.ON_GROUP_CHANGE,
      HookContext(actor = e.actor, event = e.event, group = e.snapshot),
    )
  }
}
