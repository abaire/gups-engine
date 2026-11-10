package gups.script

import gups.core.model.Entity
import gups.core.model.GroupSnapshot
import gups.core.world.HookContext
import gups.core.world.groupAdd
import gups.core.world.groupDisband
import gups.core.world.groupMembers
import gups.core.world.groupOf
import gups.core.world.groupRemove
import gups.core.world.groupSetLeader
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark

/** Player groups. */
abstract class CtxGroups
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxStanding(frame, owner, hc, extras) {
  @StarlarkMethod(
    name = "group_add",
    doc = "Adds a player to another player's group, creating one if the other has none.",
    parameters =
      [
        Param(name = "member", named = true, doc = "Player to add."),
        Param(name = "to", named = true, doc = "Player whose group the member joins."),
      ],
  )
  fun groupAdd(member: Any, to: Any): Any =
    verb("group_add") {
      world.groupAdd(playerArg(member, "group_add(member)"), playerArg(to, "group_add(to)"))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "group_remove",
    doc = "Removes a player from its group; a group left with one member is disbanded.",
    parameters = [Param(name = "member", named = true, doc = "Player to remove.")],
  )
  fun groupRemove(member: Any): Any =
    verb("group_remove") {
      world.groupRemove(playerArg(member, "group_remove(member)"))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "group_set_leader",
    doc = "Makes a member the leader of its group.",
    parameters = [Param(name = "member", named = true, doc = "Player to make the leader.")],
  )
  fun groupSetLeader(member: Any): Any =
    verb("group_set_leader") {
      world.groupSetLeader(playerArg(member, "group_set_leader(member)"))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "group_disband",
    doc = "Dissolves a player's whole group at once.",
    parameters = [Param(name = "member", named = true, doc = "Player whose group is dissolved.")],
  )
  fun groupDisband(member: Any): Any =
    verb("group_disband") {
      world.groupDisband(playerArg(member, "group_disband(member)"))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "group_of",
    doc = "The group an actor is in, or None; a pet resolves through its owner.",
    parameters = [Param(name = "actor", named = true, doc = "Actor whose group is wanted.")],
  )
  fun groupOf(actor: Any): Any =
    verb("group_of") {
      val a = actorArg(actor, "group_of(actor)")
      val g = world.groupOf(a) ?: return@verb Starlark.NONE
      GroupView(frame, GroupSnapshot(g.id, g.leader, world.groupMembers(a)))
    }

  @StarlarkMethod(
    name = "group_members",
    doc = "The members of an actor's group, leader first; [] when it has none.",
    parameters =
      [Param(name = "actor", named = true, doc = "Actor whose group members are wanted.")],
  )
  fun groupMembers(actor: Any): Any =
    verb("group_members") {
      val a = actorArg(actor, "group_members(actor)")
      sList(world.groupMembers(a).map { view(frame, it) })
    }
}
