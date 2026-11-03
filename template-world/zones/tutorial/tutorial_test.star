# Tests for the tutorial zone, run by `gups test //zones/tutorial`. They double as worked
# examples of the harness API. Every function named test_* is a test; it gets `t`.
#
#   t.world(seed, packages)  a fresh in-memory world (virtual clock, seeded randomness)
#   w.spawn_player(...)   a connected player handle; p.do("north") returns its output
#   w.run(pulses)         advance game time
#   t.eq / t.ne / t.true / t.false / t.contains / t.not_contains / t.fail

def _world(t):
    # packages defaults to this test's own package; it's spelled out here for the example.
    return t.world(seed = 1, packages = ["//zones/tutorial"])

def test_the_sign_can_be_read(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":gate")
    t.contains(p.do("read sign"), "Go north to meet the guide.")
    t.contains(p.do("read wall"), "nothing like that")

def test_the_guide_greets_once(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":gate")
    t.contains(p.do("north"), "Welcome to the tutorial, Tess!")
    p.do("south")
    t.not_contains(p.do("north"), "Welcome")

def test_the_guide_blocks_the_arena_until_ready(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":hall")
    t.contains(p.do("north"), "The guide blocks your way")
    # A room view compares equal to its label, relative to this package.
    t.eq(p.room, ":hall")
    t.contains(p.do("say I am ready!"), "Then off you go, Tess.")
    p.do("north")
    t.eq(p.room, ":arena")
    # entry_index = -1 put us at the back of the large arena.
    t.eq(p.track_index, 4)

def test_the_key_opens_the_armory(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":hall")
    t.contains(p.do("east"), "closed")
    p.do("say do you have a key?")
    t.true(p.has_item(":brass_key"))
    p.do("unlock east")
    p.do("open east")
    p.do("east")
    t.eq(p.room, ":armory")
    t.contains(p.do("get sword"), "feels light in your hand")
    t.contains(p.do("wield sword"), "You wield a practice sword.")

def test_the_dummy_mends_itself(t):
    w = _world(t)
    dummy = w.room(":arena").actors()[0]
    t.eq(dummy.label, "//zones/tutorial:training_dummy")
    w.damage(dummy, "hp", pct = 60)
    t.eq(dummy.hp, 12)
    w.run(pulses = 8)
    t.eq(dummy.hp, dummy.max_hp)

def test_a_reset_restocks_the_armory(t):
    w = _world(t)
    armory = w.room(":armory")
    t.eq(len(armory.items()), 1)
    p = w.spawn_player("Tess", at = ":armory")
    p.do("get sword")
    t.eq(len(armory.items()), 0)
    w.run(pulses = 1200)
    t.eq(len(armory.items()), 1)

def test_hooks_never_crash_quietly(t):
    # After everything above, nothing in this zone should have logged a script error.
    w = _world(t)
    p = w.spawn_player("Tess", at = ":gate")
    for line in ["read sign", "north", "say ready", "say key", "north", "kill dummy"]:
        p.do(line)
    w.run(pulses = 40)
    t.eq(w.diagnostics(), [])

def test_the_first_steps_quest(t):
    # The quest comes from //lib:quest.star; its progress lives on the player.
    w = _world(t)
    p = w.spawn_player("Tess", at = ":gate")
    t.contains(p.do("quests"), "You have no quests.")
    t.contains(p.do("north"), "New quest: First Steps.")
    t.contains(p.do("quests"), "First Steps: Ask the guide in the hall about a key.")
    t.contains(p.do("say any key for me?"), "Quest updated")
    p.do("unlock east")
    p.do("open east")
    p.do("east")
    t.contains(p.do("get sword"), "Quest updated")
    p.do("wield sword")
    p.do("west")
    p.do("say I'm ready")
    t.contains(p.do("north"), "Quest complete: First Steps!")
    t.contains(p.do("quests"), "First Steps (complete)")
    # The reward: a cap to wear and a draught to drink.
    t.contains(p.do("wear cap"), "You wear a leather cap.")
    t.eq(p.equipped("head").label, "//zones/tutorial:leather_cap")
    t.eq(p.con, 11)

def test_a_draught_heals_and_is_used_up(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":gate", vitals = vitals(hp = 30))
    w.damage(p, "hp", amount = 15)
    w.spawn(":healing_draught", at = p)
    t.contains(p.do("use draught"), "Warmth spreads through you.")
    t.eq(p.hp, 25)
    t.false(p.has_item(":healing_draught"))

def test_the_guide_hears_sparring_next_door(t):
    w = _world(t)
    listener = w.spawn_player("Gus", at = ":hall")
    p = w.spawn_player("Tess", at = ":arena")
    p.do("kill dummy")
    t.contains(listener.output(), "I hear someone sparring in the arena. Good!")

def test_the_gravel_crunches_where_it_is_heard(t):
    w = _world(t)
    guide_room = w.spawn_player("Gus", at = ":hall")
    next_door = w.spawn_player("Wen", at = ":tar_pit")
    walker = w.spawn_player("Walt", at = ":tar_pit")
    walker.do("west")
    # A player next door is shown the emit's text; the guide, two rooms away, still hears it.
    t.contains(next_door.output(), "Gravel crunches loudly somewhere to the west.")
    t.contains(guide_room.output(), "Someone is crunching about on the gravel to the west.")

def test_large_rooms_muffle_sound(t):
    # rules/perception.star charges an extra point of sound for entering a large room.
    # A shout (intensity 3) from the gate reaches the large hall (cost 2) but not the large arena
    # beyond it (cost 4), though both are listeners with the same senses.
    w = _world(t)
    shouter = w.spawn_player("Sam", at = ":gate")
    in_hall = w.spawn_player("Hal", at = ":hall")
    in_arena = w.spawn_player("Ari", at = ":arena")
    shouter.do("shout hello")
    w.run(pulses = 2)
    t.contains(in_hall.output(), "Someone shouts from the south")
    t.not_contains(in_arena.output(), "Someone shouts")

def test_the_gravel_is_too_far_for_the_gate(t):
    w = _world(t)
    far = w.spawn_player("Fay", at = ":gate")
    walker = w.spawn_player("Walt", at = ":tar_pit")
    walker.do("west")
    t.not_contains(far.output(), "Gravel")

def test_the_creaky_armory_door_is_heard_at_the_gate(t):
    w = _world(t)
    listener = w.spawn_player("Gus", at = ":gate")
    p = w.spawn_player("Tess", at = ":hall")
    p.do("say do you have a key?")
    p.do("unlock east")
    t.not_contains(listener.output(), "You hear something")
    p.do("open east")
    t.contains(listener.output(), "You hear something open to the north.")

def test_the_arena_only_wakes_with_someone_near(t):
    # bubble = True simulates only rooms near players, as the server does. The dummy's timer
    # waits while nobody is around.
    w = t.world(seed = 1, bubble = True)
    dummy = w.room(":arena").actors()[0]
    w.damage(dummy, "hp", pct = 60)
    w.run(pulses = 40)
    t.eq(dummy.hp, 12)
    w.spawn_player("Tess", at = ":hall")
    w.run(pulses = 10)
    t.eq(dummy.hp, dummy.max_hp)

def _footwork(t, room):
    # Returns the recovery lags of one step toward another player and one step back.
    w = _world(t)
    p = w.spawn_player("Tess", at = room, index = 0)
    w.spawn_player("Gus", at = room, index = 2)
    p.do("advance Gus")
    advance = p.wait_state
    w.run(pulses = 10)
    p.do("retreat from Gus")
    retreat = p.wait_state
    return advance, retreat

def test_the_tar_pit_slows_footwork(t):
    # The tar_pit's sticky_floor tag doubles the advance and retreat lag in rules/combat.star.
    # do() returns while the lag is still pending, so wait_state reads the full lag.
    t.eq(_footwork(t, ":tar_pit"), (4, 4))
    t.eq(_footwork(t, ":hall"), (2, 2))

def test_the_guide_congratulates_on_level_up(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":hall")
    w.award_xp(p, 1000)
    t.contains(p.output(), "Well done on reaching level 2, Tess!")

    w.level_down(p)
    t.not_contains(p.output(), "Well done")

    # The guide hears its own level changes too, and only congratulates players.
    guide = [a for a in w.room(":hall").actors() if a.label == "//zones/tutorial:guide"][0]
    w.award_xp(guide, 55000)
    t.eq(guide.level, 11)
    t.not_contains(p.output(), "Well done")

def _swings_in_one_round(w, p):
    # Drops earlier output, then runs to the next combat round (every 8 pulses) and counts
    # the player's swings in it.
    p.output()
    w.run(pulses = 8 - w.pulse % 8)
    return len([line for line in p.output().split("\n") if "You hit" in line or "You miss" in line])

def test_dual_wield_gives_two_attacks(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":arena")
    w.spawn(":practice_sword", at = p)
    p.do("wield sword")
    p.do("kill dummy")
    t.eq(_swings_in_one_round(w, p), 1)

    w = _world(t)
    p = w.spawn_player("Tess", at = ":arena")
    w.spawn(":dagger", at = p)
    w.spawn(":practice_sword", at = p)
    p.do("wield sword")
    t.contains(p.do("wield dagger"), "You wield a dagger.")
    t.eq(p.equipped("wield").label, "//zones/tutorial:practice_sword")
    t.eq(p.equipped("offhand").label, "//zones/tutorial:dagger")
    p.do("kill dummy")
    t.eq(_swings_in_one_round(w, p), 2)

def _carried(p):
    return [i.label for i in p.inventory()]

def test_greatsword_swap_behaviour(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":arena")
    w.spawn(":greatsword", at = p)
    w.spawn(":wooden_shield", at = p)
    p.do("wield greatsword")
    t.eq(p.equipped("wield").label, "//zones/tutorial:greatsword")
    # The greatsword fills both hands, so the shield swaps it out.
    t.contains(p.do("wear shield"), "You stop using a greatsword.")
    t.eq(p.equipped("offhand").label, "//zones/tutorial:wooden_shield")
    t.eq(p.equipped("wield"), None)
    t.true("//zones/tutorial:greatsword" in _carried(p))

    strong = w.spawn_player("Hercules", at = ":arena", stats = stats(str = 18))
    w.spawn(":greatsword", at = strong)
    w.spawn(":wooden_shield", at = strong)
    strong.do("wield greatsword")
    strong.do("wear shield")
    t.eq(strong.equipped("wield").label, "//zones/tutorial:greatsword")
    t.eq(strong.equipped("offhand").label, "//zones/tutorial:wooden_shield")

def test_greatsword_wont_take_off_two_items(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":arena")
    w.spawn(":practice_sword", at = p)
    w.spawn(":wooden_shield", at = p)
    w.spawn(":greatsword", at = p)
    p.do("wield sword")
    p.do("wear shield")
    out = p.do("wield greatsword")
    t.contains(out, "You'd need to remove")
    t.contains(out, "a practice sword")
    t.contains(out, "a wooden shield")
    t.eq(p.equipped("wield").label, "//zones/tutorial:practice_sword")
    t.eq(p.equipped("offhand").label, "//zones/tutorial:wooden_shield")
    t.true("//zones/tutorial:greatsword" in _carried(p))

def test_ring_fire_resist(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":hall")
    w.spawn(":copper_ring", at = p)
    t.eq(p.resists.fire, 0)
    p.do("wear ring")
    t.eq(p.equipped("finger_l").label, "//zones/tutorial:copper_ring")
    t.eq(p.resists.fire, 2)
    p.do("remove ring")
    t.eq(p.resists.fire, 0)

def test_amulet_max_hp(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":hall")
    w.spawn(":amulet", at = p)
    t.eq(p.max_hp, 20)
    p.do("wear amulet")
    t.eq(p.equipped("neck").label, "//zones/tutorial:amulet")
    t.eq(p.max_hp, 25)
    p.do("remove amulet")
    t.eq(p.max_hp, 20)
