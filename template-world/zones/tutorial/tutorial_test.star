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

def test_new_players_have_charisma(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":gate")
    t.eq(p.cha, 10)

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
    t.contains(p.do("use draught"), "You use a healing draught.")
    t.true(p.hp >= 23 and p.hp <= 29)
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

def _footwork(t, room, boots = False, items = []):
    # Returns the recovery lags of one step toward another player and one step back.
    # `items` names test-only items the player carries first, for the encumbrance test.
    w = _world(t)
    p = w.spawn_player("Tess", at = room, index = 0)
    if boots:
        w.spawn(":tar_walker_boots", at = p)
        p.do("wear boots")
        w.run(pulses = 10)
    for label in items:
        w.spawn(label, at = p)
    w.spawn_player("Gus", at = room, index = 2)
    p.do("advance Gus")
    advance = p.wait_state
    w.run(pulses = 10)
    p.do("retreat from Gus")
    retreat = p.wait_state
    return advance, retreat

def _step_cost(p):
    # The move vital one step out of the current room spends: walk out, read the loss, walk
    # back. The guide holds the hall's north exit, so the test walks to the gate.
    before = p.move
    p.do("south")
    cost = before - p.move
    p.do("north")
    return cost

def test_the_tar_pit_slows_footwork(t):
    # The tar_pit's sticky_floor tag doubles the advance and retreat lag in rules/combat.star.
    # do() returns while the lag is still pending, so wait_state reads the full lag.
    t.eq(_footwork(t, ":tar_pit"), (4, 4))
    t.eq(_footwork(t, ":hall"), (2, 2))

def test_tar_walker_boots_cancel_the_tar_pit(t):
    # The boots' advance_lag and retreat_lag modifiers halve the lag only in sticky rooms.
    t.eq(_footwork(t, ":tar_pit", boots = True), (2, 2))
    t.eq(_footwork(t, ":hall", boots = True), (2, 2))

def test_encumbrance_tiers_price_move_and_dex(t):
    # rules/combat.star grades the share of the carry limit (str * 10): level 1 from 75%,
    # level 3 from 90%. Each level costs 1 dex, 1 move per step and 1 recovery pulse on
    # advance and retreat, and dropping the load gives it all back. w.spawn puts the cargo
    # straight into the inventory, so a custody change regrades the level.
    w = _world(t)
    p = w.spawn_player("Tess", at = ":hall")
    t.eq(p.encumbrance_level, 0)
    t.eq(p.dex, 10)
    t.eq(_step_cost(p), 1)
    t.eq(_footwork(t, ":hall"), (2, 2))

    w.spawn(":heavy_pack", at = p)
    t.eq(p.encumbrance_level, 1)
    t.eq(p.dex, 9)
    t.eq(_step_cost(p), 2)
    t.eq(_footwork(t, ":hall", items = [":heavy_pack"]), (3, 3))

    w.spawn(":iron_bar", at = p)
    t.eq(p.encumbrance_level, 3)
    t.eq(p.dex, 7)
    t.eq(_step_cost(p), 4)
    t.eq(_footwork(t, ":hall", items = [":heavy_pack", ":iron_bar"]), (5, 5))

    p.do("drop heavy")
    p.do("drop iron")
    t.eq(p.encumbrance_level, 0)
    t.eq(p.dex, 10)
    t.eq(_step_cost(p), 1)
    w.spawn_player("Gus", at = ":hall", index = 2)
    p.do("advance Gus")
    t.eq(p.wait_state, 2)

def test_heavy_encumbrance_sends_the_flavor_line(t):
    # rules/combat.star registers a global_hook("on_encumbrance_change") that sends a flavor
    # line when an actor's encumbrance level rises into the heavy tiers (3 and up), after the
    # engine's generic "carrying more than you can manage" line. The flavor fires on the way up
    # into 3 and 4, and only then: lightening sends the engine's line but no flavor.
    w = _world(t)
    p = w.spawn_player("Tess", at = ":hall")
    t.eq(p.encumbrance_level, 0)
    w.spawn(":heavy_pack", at = p)
    t.eq(p.encumbrance_level, 1)
    w.spawn(":sack", at = p)
    t.eq(p.encumbrance_level, 2)
    # Rises to level 2 are below the heavy tiers, so the flavor is still silent.
    t.not_contains(p.output(), "Your pack drags at your shoulders")
    w.spawn(":pouch", at = p)
    t.eq(p.encumbrance_level, 3)
    t.contains(p.output(), "Your pack drags at your shoulders")
    w.spawn(":crate", at = p)
    t.eq(p.encumbrance_level, 4)
    t.contains(p.output(), "Your pack drags at your shoulders")
    # Lightening back down to level 2 sends the engine's line, not the flavor.
    p.do("drop crate")
    t.eq(p.encumbrance_level, 3)
    p.do("drop pouch")
    t.eq(p.encumbrance_level, 2)
    t.not_contains(p.output(), "Your pack drags at your shoulders")

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

def _dummy_fight(t, item = None, pulses = 60, mana = 20):
    # Fights the training dummy for a fixed time and returns (hp lost, the player's mana).
    # spawn_player's vitals set the starting and maximum values alike, so the current mana is
    # brought down with the raw damage verb, which runs no modifier chains. The dummy swings
    # back each round, so the fight exercises the full to_hit, defend_hit, damage,
    # receive_damage and mitigate path. w.trace() prints only on a failed test, so the tests
    # assert on vitals.
    w = _world(t)
    p = w.spawn_player("Tess", at = ":arena", vitals = vitals(hp = 100))
    if p.mana > mana:
        w.damage(p, "mana", amount = p.mana - mana)
    if item != None:
        w.spawn(item, at = p)
        # The item's first word is a keyword unique to it: "shadow" and "siphon".
        p.do("wear " + item.split(":")[1].split("_")[0])
    before = p.hp
    p.do("kill dummy")
    w.run(pulses = pulses)
    return before - p.hp, p.mana

def test_shadow_cloak_blurs_and_wards(t):
    # Both runs share seed 1 (via _world) and the same number of pulses, so they are
    # deterministic; that seed lands hits in both, so the cloak's difference is measurable.
    (bare_hp_lost, _) = _dummy_fight(t)
    (cloaked_hp_lost, _) = _dummy_fight(t, item = ":shadow_cloak")
    t.true(bare_hp_lost > 0)
    t.true(cloaked_hp_lost < bare_hp_lost)

def test_siphon_amulet_turns_pain_into_mana(t):
    (hp_lost, mana_with) = _dummy_fight(t, item = ":siphon_amulet", mana = 0)
    (_, mana_without) = _dummy_fight(t, mana = 0)
    t.true(hp_lost > 0)
    # Both runs start at 0 mana and regen the same amount; the amulet's siphon adds more.
    t.true(mana_with > mana_without)

def test_a_mob_can_wear_an_amulet(t):
    # Groundwork for defender-side damage tests, which need a mob (the dummy) to wear an item
    # so its modifiers apply to the mob's own swings. The amulet's +5 max hp shows it took
    # effect, not just that it was moved to the neck slot.
    w = _world(t)
    dummy = w.spawn(":training_dummy", at = ":hall")
    w.spawn(":amulet", at = dummy)
    max_before = dummy.max_hp
    w.act(dummy, "wear amulet")
    w.run(pulses = 4)
    t.eq(dummy.equipped("neck").label, "//zones/tutorial:amulet")
    t.eq(dummy.max_hp, max_before + 5)

def _burning_fight(t, player_gear = []):
    # The dummy wears an ember amulet (fire 1 on every swing it deals) and a flaming sword
    # (slash dice, plus 1d2 fire on its own swings); the player, recording with the part_logger
    # fixture, may wear the gear given. Returns (fire hp lost, slash hp lost) to the player, each
    # recorded by the part its hp came from.
    w = _world(t)
    dummy = w.spawn(":training_dummy", at = ":hall")
    w.spawn(":ember_amulet", at = dummy)
    w.spawn(":flaming_sword", at = dummy)
    w.act(dummy, "wear ember")
    w.act(dummy, "wield flaming")
    w.run(pulses = 4)
    t.eq(dummy.equipped("neck").label, "//zones/tutorial:ember_amulet")
    t.eq(dummy.equipped("wield").label, "//zones/tutorial:flaming_sword")
    p = w.spawn_player("Tess", at = ":hall", index = 0, vitals = vitals(hp = 100))
    w.spawn(":part_logger", at = p)
    p.do("wear logger")
    for gear in player_gear:
        w.spawn(gear[0], at = p)
        p.do("wear " + gear[1])
    p.do("kill dummy")
    w.run(pulses = 40)
    return w.get_attr(p, "hp_fire", 0), w.get_attr(p, "hp_slash", 0)

def test_flaming_sword_adds_fire(t):
    # A sword's bonus_damage modifier adds a fire part to its own swings; the part_logger fixture
    # on the player records the hp each part takes. (The swing-versus-spell contrast is covered
    # by the engine's script tests; the tutorial has no damage spell of its own.)
    w = _world(t)
    dummy = w.spawn(":training_dummy", at = ":hall")
    w.spawn(":flaming_sword", at = dummy)
    w.act(dummy, "wield flaming")
    w.run(pulses = 4)
    t.eq(dummy.equipped("wield").label, "//zones/tutorial:flaming_sword")
    p = w.spawn_player("Tess", at = ":hall", index = 0, vitals = vitals(hp = 100))
    w.spawn(":part_logger", at = p)
    p.do("wear logger")
    p.do("kill dummy")
    w.run(pulses = 40)
    # The dummy swings the flaming sword: a slash part (its dice) plus a fire part (its bonus).
    t.true(w.get_attr(p, "hp_slash", 0) > 0)
    t.true(w.get_attr(p, "hp_fire", 0) > 0)

def test_oily_rags_double_fire(t):
    # The rags' receive_damage modifier doubles a fire part before armor: the two runs share
    # seed 1 and 40 pulses, so the fire hp with rags is exactly twice the bare fire hp, and the
    # armor turns aside the physical (slash) part at the same time.
    (fire_bare, slash_bare) = _burning_fight(t)
    (fire_rags, slash_rags) = _burning_fight(t, player_gear = [(":oily_rags", "rags")])
    t.true(fire_bare > 0)
    t.eq(fire_rags, 2 * fire_bare)
    t.true(slash_bare > 0)
    t.true(slash_rags < slash_bare)

def test_fire_ignores_armor(t):
    # Armor turns aside physical blows only: the fire part comes through the shield's armor 2
    # untouched, while the slash part is reduced.
    (fire_bare, slash_bare) = _burning_fight(t)
    (fire_shielded, slash_shielded) = _burning_fight(t, player_gear = [(":wooden_shield", "shield")])
    t.true(fire_bare > 0)
    t.eq(fire_shielded, fire_bare)
    t.true(slash_shielded < slash_bare)

def _proc_fight(t, wield = None, wear = None, fractions = []):
    # Fights the training dummy for three rounds with one item in hand or on the body, and
    # returns the world and the player's output. Each proc draws one fraction for its chance
    # (and a second to pick from an outcome table), so forcing fractions decides the first procs.
    w = _world(t)
    p = w.spawn_player("Tess", at = ":arena", vitals = vitals(hp = 100))
    for (label, verb) in [(wield, "wield"), (wear, "wear")]:
        if label != None:
            w.spawn(label, at = p)
            p.do(verb + " " + label.split("_")[-1])
    w.force_rng(fractions = fractions)
    p.do("kill dummy")
    w.run(pulses = 24)
    return w, p.output()

def test_ember_dagger_procs_on_its_own_hits(t):
    (_, ember) = _proc_fight(t, wield = ":ember_dagger", fractions = [0.0])
    t.contains(ember, "Power surges from an ember dagger!")
    (_, plain) = _proc_fight(t, wield = ":dagger", fractions = [0.0])
    t.false("Power surges" in plain)

def test_bramble_vest_rakes_attackers(t):
    (_, out) = _proc_fight(t, wear = ":bramble_vest")
    t.contains(out, "Thorns rake your attacker!")

def test_cracked_wand_picks_one_outcome(t):
    # 0.6 falls past fire (0.05) and fear (0.45) into the stun band, which runs to 0.89.
    (w, out) = _proc_fight(t, wield = ":cracked_wand", fractions = [0.0, 0.6])
    t.contains(out, "A dazzling arc of light leaps from the wand!")
    t.eq(w.diagnostics(), [])

def test_the_mentor_teaches_spark(t):
    # The mentor stands in the arena. A level grants practice sessions, which buy the skill.
    w = _world(t)
    p = w.spawn_player("Tess", at = ":arena")
    w.award_xp(p, 1000)   # one level: +2 practice sessions
    t.contains(p.do("practice spark"), "You learn spark from the mentor. (10%)")
    t.eq(p.skills["//skills/arcane:spark"], 10)
    t.contains(p.do("practice"), "You have 1 practice session")
    t.contains(p.do("skills"), "spark")
