# Tests for player groups and shared experience, driven against the template world. The engine
# holds the group and fires on_group_change / on_disengage; //lib/group.star adds the commands
# and the Group.Chat package. xp_share (rules/combat.star) splits a kill's pool among the
# contributors. A pulse is 250 ms and a combat round is 8 pulses, so the fighter's unarmed
# auto-attacks (1-4 damage) take many rounds to drop a victim - plenty of time for the ally
# casts to land. The training dummy mends itself below half hp, so the tests set its "mending"
# attribute (this package's namespace) to keep the fight going, and pre-wound it so the fighter
# lands a small, known damage total. A fresh player has no xp, so a gain is read straight off p.xp.

# A player supporting GMCP state (Char) and group chat (Group) events.
def _player(t, name, at, **kw):
    w = t.world(seed = 1)
    p = w.spawn_player(name, at = at, gmcp = ["Char", "Group"], **kw)
    return w, p

# The kill's experience pool: xp_for_kill, 50 per victim level, scaled by the level gap.
def _pool(victim_level, killer_level):
    diff = victim_level - killer_level
    pct = max(0, min(150, 100 + 20 * diff))
    return 50 * victim_level * pct // 100

# The dummy, with its self-mend suppressed and its hp set to `hp` (a small, known damage total).
def _victim(w, at = ":armory", hp = 6):
    d = w.spawn("//zones/tutorial:training_dummy", at = at)
    w.set_attr(d, "mending", True)
    w.damage(d, "hp", amount = d.max_hp - hp)
    return d

def _group(w, leader, member):
    leader.do("invite %s" % member.name)
    member.do("accept")

# --- Experience split ------------------------------------------------------------------------

def test_party_split(t):
    w, fighter = _player(t, "Tess", ":armory")
    healer = w.spawn_player("Mia", at = ":armory")
    buffer = w.spawn_player("Bo", at = ":armory")
    w.set_skill(healer, "//skills/arcane:soothe", 100)
    w.set_skill(buffer, "//skills/arcane:ward", 100)
    victim = _victim(w, hp = 6)
    L = victim.level
    _group(w, fighter, healer)
    _group(w, fighter, buffer)
    fighter.do("kill dummy")
    buffer.do("cast ward Tess")          # support, while Tess is engaged
    w.damage(fighter, "hp", amount = 2)  # room for the heal: soothe is 2d6, so it lands 2
    healer.do("cast soothe Tess")        # healing, on the dummy's ledger
    w.run(pulses = 400)                  # the fighter finishes the dummy
    for p in (fighter, healer, buffer):
        t.true(p.xp > 0)
    t.eq(fighter.xp + healer.xp + buffer.xp, _pool(L, L))

def test_solo_kill_pays_full_pool(t):
    w, p = _player(t, "Tess", ":armory")
    w.spawn(":practice_sword", at = p)    # the arena halves unarmed damage
    p.do("wield sword")
    victim = _victim(w, hp = 30)
    L = victim.level
    p.do("kill dummy")
    w.run(pulses = 500)
    t.eq(p.xp, _pool(L, L))

def test_out_of_room_member_gets_nothing(t):
    w, fighter = _player(t, "Tess", ":armory")
    member = w.spawn_player("Bo", at = ":armory")
    w.spawn(":practice_sword", at = fighter)
    fighter.do("wield sword")
    victim = _victim(w, hp = 30)
    _group(w, fighter, member)
    member.do("east")                     # the shop: out of the fight's room
    fighter.do("kill dummy")
    w.run(pulses = 500)
    t.eq(member.xp, 0)
    t.eq(fighter.xp, _pool(victim.level, fighter.level))

def test_out_of_band_member_gets_nothing(t):
    w, fighter = _player(t, "Tess", ":armory")
    w.spawn(":practice_sword", at = fighter)
    fighter.do("wield sword")
    victim = _victim(w, hp = 30)
    member = w.spawn_player("Bo", at = ":armory", level = victim.level + 11)
    _group(w, fighter, member)
    fighter.do("kill dummy")
    w.run(pulses = 500)
    t.eq(member.xp, 0)
    t.eq(fighter.xp, _pool(victim.level, fighter.level))

def test_idle_member_in_room_gets_some(t):
    w, fighter = _player(t, "Tess", ":armory")
    member = w.spawn_player("Bo", at = ":armory", gmcp = ["Char"])
    victim = _victim(w, hp = 1)           # a small damage total, so the idle share is not rounded to 0
    fighter.do("invite Bo")
    member.do("accept")
    fighter.do("kill dummy")
    w.run(pulses = 400)
    t.true(member.xp > 0)
    t.eq(fighter.xp + member.xp, _pool(victim.level, fighter.level))

# --- The invitation flow ---------------------------------------------------------------------

def test_invite_flow(t):
    w, ada = _player(t, "Ada", ":armory")
    bo = w.spawn_player("Bo", at = ":armory")
    cy = w.spawn_player("Cy", at = ":armory")
    # invite and accept: Ada leads.
    ada.do("invite Bo")
    t.contains(bo.output(), "invites you to a group")
    bo.do("accept")
    t.contains(ada.do("group"), "Ada (level 1) 100% (leader)")
    # a second invite while one is pending is refused.
    ada.do("invite Cy")
    t.contains(cy.output(), "invites you to a group")
    t.contains(ada.do("invite Cy"), "already has an invite")
    cy.do("accept")
    # only the leader kicks, hands over leadership or disbands.
    t.contains(bo.do("kick Cy"), "Only the group leader")
    t.contains(bo.do("leader Cy"), "Only the group leader")
    t.contains(bo.do("disband"), "Only the group leader")
    # the leader hands over leadership, then the new leader kicks.
    ada.do("leader Bo")
    t.contains(ada.do("group"), "Bo (level 1) 100% (leader)")
    bo.do("kick Cy")
    t.contains(cy.output(), "leaves the group")
    ada.output()                          # drop the queued notices before listing
    t.eq(len([m for m in ada.do("group").splitlines() if m]), 2)

def test_invite_expires(t):
    w, ada = _player(t, "Ada", ":armory")
    bo = w.spawn_player("Bo", at = ":armory")
    ada.do("invite Bo")
    w.run(pulses = 241)                   # past the one-minute ttl
    t.contains(bo.do("accept"), "no pending invite")
    t.contains(ada.do("group"), "not in a group")

def test_invite_refuses_bad_targets(t):
    w, ada = _player(t, "Ada", ":armory")
    bo = w.spawn_player("Bo", at = ":armory")
    t.contains(ada.do("invite"), "Invite whom?")
    t.contains(ada.do("invite Nobody"), "No one like that")
    ada.do("invite Bo")
    bo.do("accept")
    t.contains(ada.do("invite Bo"), "already in a group")   # Bo is grouped now

def test_leader_commands_refuse_bad_targets(t):
    w, ada = _player(t, "Ada", ":armory")
    bo = w.spawn_player("Bo", at = ":armory")
    _group(w, ada, bo)
    t.contains(ada.do("kick"), "Kick whom?")
    t.contains(ada.do("kick Ada"), "Type 'leave'")
    t.contains(ada.do("leader"), "Make whom the leader?")
    t.contains(ada.do("leader Ada"), "already lead")
    t.contains(ada.do("group"), "Ada (level 1) 100% (leader)")   # nothing changed

def test_accept_while_grouped_is_refused(t):
    w, ada = _player(t, "Ada", ":armory")
    bo = w.spawn_player("Bo", at = ":armory")
    cy = w.spawn_player("Cy", at = ":armory")
    cy.do("invite Bo")                    # Bo holds Cy's invite...
    _group(w, bo, ada)                    # ...and forms a group of his own with Ada
    t.contains(bo.do("accept"), "already in a group")
    t.contains(bo.do("group"), "Bo (level 1) 100% (leader)")
    t.contains(cy.do("group"), "not in a group")

def test_gtell_without_text_asks_for_it(t):
    w, ada = _player(t, "Ada", ":armory")
    bo = w.spawn_player("Bo", at = ":armory")
    t.contains(ada.do("gtell"), "not in a group")
    _group(w, ada, bo)
    t.contains(ada.do("gtell"), "Tell your group what?")

# --- Group chat and state --------------------------------------------------------------------

def test_gtell_reaches_the_group(t):
    w, ada = _player(t, "Ada", ":armory")
    bo = w.spawn_player("Bo", at = ":armory", gmcp = ["Char", "Group"])
    cy = w.spawn_player("Cy", at = ":armory", gmcp = ["Char", "Group"])
    dee = w.spawn_player("Dee", at = ":armory")             # not in the group
    _group(w, ada, bo)
    _group(w, ada, cy)
    spoken = ada.do("gtell hello")
    t.contains(bo.output(), "[Group] Ada: hello")
    t.contains(cy.output(), "[Group] Ada: hello")
    t.contains(spoken, "[Group] Ada: hello")                # the speaker is a member too
    t.false("hello" in dee.output())
    for p in (bo, cy):
        msgs = p.gmcp("Group.Chat")
        t.eq(len(msgs), 1)
        t.eq(msgs[0]["data"]["talker"], "Ada")
        t.eq(msgs[0]["data"]["text"], "hello")

def test_gtell_stops_for_a_quitter(t):
    w, ada = _player(t, "Ada", ":armory")
    bo = w.spawn_player("Bo", at = ":armory", gmcp = ["Char", "Group"])
    cy = w.spawn_player("Cy", at = ":armory")
    _group(w, ada, bo)
    _group(w, ada, cy)
    bo.do("quit")
    bo.output()
    spoken = ada.do("gtell after")
    t.false("after" in bo.output())
    t.true("after" in spoken)
    t.contains(cy.output(), "[Group] Ada: after")

def test_char_group_shows_the_group(t):
    w, p = _player(t, "Tess", ":armory")
    bo = w.spawn_player("Bo", at = ":armory")
    w.run(pulses = 2)
    t.eq(p.gmcp("Char.Group")[-1]["data"], None)
    _group(w, p, bo)
    w.run(pulses = 2)
    data = p.gmcp("Char.Group")[-1]["data"]
    t.eq(data["leader"], "Tess")
    t.eq(sorted([m["name"] for m in data["members"]]), ["Bo", "Tess"])
    p.do("leave")
    w.run(pulses = 2)
    t.eq(p.gmcp("Char.Group")[-1]["data"], None)

# --- Disengagement clears the ledger ---------------------------------------------------------

def test_flee_clears_the_old_contributors(t):
    w, killer = _player(t, "Tess", ":armory")
    healer = w.spawn_player("Mia", at = ":armory")
    w.set_skill(healer, "//skills/arcane:soothe", 100)
    victim = w.spawn("//zones/tutorial:mentor", at = ":armory")   # fights back, level 1
    _group(w, killer, healer)
    killer.do("kill mentor")
    w.damage(killer, "hp", amount = 2)
    healer.do("cast soothe Tess")          # Mia is on the mentor's ledger
    w.force_rng(ints = [1])                # the flee roll succeeds
    killer.do("flee")                      # the mentor's last opponent leaves: ledger cleared
    t.eq(len(killer.fighting()), 0)
    healer.do("east")                      # Mia leaves the room, so she can't idle-share
    killer.do("west")
    w.damage(victim, "hp", amount = victim.max_hp - 6)
    killer.do("kill mentor")               # a fresh fight: only Tess's new damage counts
    w.run(pulses = 1200)
    t.eq(healer.xp, 0)
    t.eq(killer.xp, _pool(victim.level, killer.level))
