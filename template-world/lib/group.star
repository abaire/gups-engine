# Player groups: the social policy the engine leaves to a world. The engine holds the group (a
# leader and a set of members) and fires on_group_change; this library adds the commands players
# type and the group chat. It exports nothing: its commands and hooks register in every world that
# includes lib.
#
# `invite` and `accept` form a group; `leave`, `kick`, `leader` and `disband` change it; `group`
# lists it; `gtell` (aliased `gt`) speaks to it. Invites expire after a minute, and only the
# leader kicks, hands over leadership or disbands. The on_disengage hook clears a mob's
# contribution record when it loses its last opponent, so a party that flees and comes back
# starts a fresh fight, and earlier help earns nothing.

INVITE_TTL_PULSES = 240   # one minute

def _in_group(ctx, actor):
    return ctx.group_of(actor) != None

def _member(ctx, actor, keyword):
    # A group member whose name keyword prefixes, or None.
    for m in ctx.group_members(actor):
        if m.name.lower().startswith(keyword.lower()):
            return m
    return None

def _invite_command(ctx):
    me = ctx.self
    if ctx.args == "":
        ctx.send(me, "Invite whom?")
        return
    # The inviter must be alone or the leader of its group.
    if _in_group(ctx, me) and ctx.group_of(me).leader != me:
        ctx.send(me, "Only the group leader invites.")
        return
    target = None
    for a in ctx.self.room.actors():
        if a.is_player and a != me and a.name.lower().startswith(ctx.args.lower()):
            target = a
            break
    if target == None:
        ctx.send(me, "No one like that is here.")
        return
    if _in_group(ctx, target):
        ctx.send(me, "%s is already in a group." % target.name)
        return
    if ctx.get_attr(target, "group_invite_until", 0) > ctx.pulse:
        ctx.send(me, "%s already has an invite." % target.name)
        return
    ctx.set_attr(target, "group_invite_from", me.name)
    ctx.set_attr(target, "group_invite_until", ctx.pulse + INVITE_TTL_PULSES)
    ctx.send(me, "You invite %s to your group." % target.name)
    ctx.send(target, "%s invites you to a group. Type 'accept' to join." % me.name)

def _accept_command(ctx):
    me = ctx.self
    from_name = ctx.get_attr(me, "group_invite_from")
    until = ctx.get_attr(me, "group_invite_until", 0)
    ctx.del_attr(me, "group_invite_from")
    ctx.del_attr(me, "group_invite_until")
    if _in_group(ctx, me):
        ctx.send(me, "You are already in a group.")
        return
    if from_name == None or ctx.pulse > until:
        ctx.send(me, "You have no pending invite.")
        return
    inviter = None
    for a in ctx.self.room.actors():
        if a.is_player and a.name == from_name:
            inviter = a
            break
    if inviter == None:
        ctx.send(me, "The inviter has left the room.")
        return
    ctx.group_add(me, inviter)

def _leave_command(ctx):
    me = ctx.self
    if not _in_group(ctx, me):
        ctx.send(me, "You are not in a group.")
        return
    ctx.group_remove(me)

def _kick_command(ctx):
    me = ctx.self
    g = ctx.group_of(me)
    if g == None:
        ctx.send(me, "You are not in a group.")
        return
    if g.leader != me:
        ctx.send(me, "Only the group leader kicks.")
        return
    if ctx.args == "":
        ctx.send(me, "Kick whom?")
        return
    target = _member(ctx, me, ctx.args)
    if target == None:
        ctx.send(me, "No group member like that.")
        return
    if target == me:
        ctx.send(me, "Type 'leave' to leave the group.")
        return
    ctx.group_remove(target)

def _leader_command(ctx):
    me = ctx.self
    g = ctx.group_of(me)
    if g == None:
        ctx.send(me, "You are not in a group.")
        return
    if g.leader != me:
        ctx.send(me, "Only the group leader hands over leadership.")
        return
    if ctx.args == "":
        ctx.send(me, "Make whom the leader?")
        return
    target = _member(ctx, me, ctx.args)
    if target == None:
        ctx.send(me, "No group member like that.")
        return
    if target == me:
        ctx.send(me, "You already lead the group.")
        return
    ctx.group_set_leader(target)

def _disband_command(ctx):
    me = ctx.self
    g = ctx.group_of(me)
    if g == None:
        ctx.send(me, "You are not in a group.")
        return
    if g.leader != me:
        ctx.send(me, "Only the group leader disbands.")
        return
    ctx.group_disband(me)

def _group_command(ctx):
    me = ctx.self
    g = ctx.group_of(me)
    if g == None:
        ctx.send(me, "You are not in a group.")
        return
    lines = []
    for m in ctx.group_members(me):
        leader = " (leader)" if m == g.leader else ""
        lines.append("%s (level %d) %d%%%s" % (m.name, m.level, m.hp * 100 // m.max_hp, leader))
    ctx.send(me, "\n".join(lines))

def _gtell_command(ctx):
    me = ctx.self
    if not _in_group(ctx, me):
        ctx.send(me, "You are not in a group.")
        return
    if ctx.args == "":
        ctx.send(me, "Tell your group what?")
        return
    text = ctx.args
    for m in ctx.group_members(me):
        ctx.send(m, "[Group] %s: %s" % (me.name, text))
        ctx.gmcp(m, "Group.Chat", {"talker": me.name, "text": text})

def _on_group_change(ctx):
    # Tell the group about the change. On a "leave", the snapshot no longer holds the leaver, so
    # add them back and tell them too.
    who = ctx.actor
    g = ctx.group
    members = list(g.members) if g != None else []
    if ctx.event == "leave" and who not in members:
        members.append(who)
    for m in members:
        if ctx.event == "join":
            ctx.send(m, "%s joins the group." % who.name)
        elif ctx.event == "leave":
            ctx.send(m, "%s leaves the group." % who.name)
        elif ctx.event == "leader":
            ctx.send(m, "%s is now the leader." % who.name)
        elif ctx.event == "disband":
            ctx.send(m, "The group disbands.")

def _on_disengage(ctx):
    # A mob that loses its last opponent forgets who fought it.
    if not ctx.actor.is_player:
        ctx.clear_contributions(ctx.actor)

gmcp_package("Group.Chat", doc = "A group chat line.")
global_hook("on_group_change", _on_group_change)
global_hook("on_disengage", _on_disengage)
global_command("group", _group_command, lane = "fast")
global_command("gtell", _gtell_command, lane = "fast")
global_command("gt", _gtell_command, lane = "fast")
global_command("invite", _invite_command, lane = "tick")
global_command("accept", _accept_command, lane = "tick")
global_command("leave", _leave_command, lane = "tick")
global_command("kick", _kick_command, lane = "tick")
global_command("leader", _leader_command, lane = "tick")
global_command("disband", _disband_command, lane = "tick")
