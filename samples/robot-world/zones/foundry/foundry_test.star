def test_players_are_built_with_some_variation(t):
    w = t.world(seed = 7)
    maxima = [w.spawn_player("Bot%d" % i, at = ":gate").max_integrity for i in range(8)]
    t.true(min(maxima) >= 25)
    t.true(max(maxima) <= 35)
    t.true(len({m: True for m in maxima}) > 1)

def test_a_mob_keeps_the_numbers_its_prototype_declares(t):
    w = t.world(seed = 1)
    hound = w.spawn(":rust_hound", at = ":scrapyard")
    t.eq(hound.max_integrity, 20)
    t.eq(hound.integrity, 20)
    t.eq(hound.max_rads, 100)
    t.eq(hound.plating, 2)
    t.eq(hound.base.agility, 12)

def test_psychic_damage_goes_to_sanity(t):
    # The seed is chosen so the routine lands.
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate", skills = {":mind_lash": 100})
    drone = w.spawn(":welder_drone", at = ":gate")
    t.contains(p.do("invoke mind_lash drone"), "You invoke mind_lash")
    t.true(drone.sanity < drone.max_sanity)
    t.eq(drone.integrity, drone.max_integrity)

def test_energy_damage_goes_to_integrity_less_the_resistance(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate", skills = {":overload": 100})
    drone = w.spawn(":welder_drone", at = ":gate")
    t.contains(p.do("invoke overload drone"), "You invoke overload")
    t.true(drone.integrity < drone.max_integrity)
    t.eq(drone.sanity, drone.max_sanity)
    t.eq(drone.resists.energy, 25)

def test_a_skill_cost_comes_out_of_power(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate", skills = {":overload": 100}, vitals = vitals(power = 12))
    w.spawn(":welder_drone", at = ":gate")
    t.contains(p.do("invoke overload drone"), "Your routine needs more Power.")
    p2 = w.spawn_player("Bea", at = ":gate", skills = {":overload": 100})
    p2.do("invoke overload drone")
    t.eq(p2.power, p2.max_power - 15)

def test_integrity_or_sanity_at_zero_incapacitates(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate")
    w.damage(p, "integrity", amount = p.integrity)
    t.eq(p.condition, "incapacitated")
    t.contains(p.do("east"), "You can't do that while incapacitated.")
    t.contains(p.do("look"), "The Foundry Gate")
    w.run(pulses = 120)
    t.eq(p.condition, "ok")

    q = w.spawn_player("Bea", at = ":gate")
    w.damage(q, "sanity", amount = q.sanity)
    t.eq(q.condition, "incapacitated")

def test_minus_ten_destroys(t):
    w = t.world(seed = 1)
    a = w.spawn(":welder_drone", at = ":gate")
    w.damage(a, "integrity", amount = a.integrity + 10)
    t.false(a.in_world)

    b = w.spawn(":welder_drone", at = ":gate")
    w.damage(b, "sanity", amount = b.sanity + 10)
    t.false(b.in_world)

    c = w.spawn(":welder_drone", at = ":gate")
    w.damage(c, "integrity", amount = c.integrity + 9)
    t.true(c.in_world)
    t.eq(c.condition, "incapacitated")

def test_radiation_destroys_a_minute_after_the_margin_runs_out(t):
    w = t.world(seed = 1)
    drone = w.spawn(":welder_drone", at = ":gate")
    w.run(pulses = 100)
    w.damage(drone, "rads", amount = drone.rads + 1)
    t.eq(drone.below_zero_since("rads"), 100)
    w.run(pulses = 20)
    t.true(drone.in_world)
    t.eq(drone.condition, "ok")
    w.run(pulses = 120)
    t.false(drone.in_world)

def test_the_whisperer_attacks_the_mind(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate")
    w.spawn(":whisperer", at = ":gate")
    sanity = p.sanity
    w.run(pulses = 119)
    t.eq(p.sanity, sanity)
    w.run(pulses = 1)
    t.eq(p.sanity, sanity - 3)

def test_invoke_replaces_cast_and_has_its_own_words(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate", skills = {":mind_lash": 100})
    w.spawn(":welder_drone", at = ":gate")
    out = p.do("invoke mind_lash drone")
    t.contains(out, "Your implant spins up mind_lash.")
    t.contains(out, "You invoke mind_lash at a welder drone.")
    t.contains(p.do("invoke"), "Invoke what?")

def test_the_alias_and_prefix_use_the_new_name(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate", skills = {":overload": 100})
    w.spawn(":welder_drone", at = ":gate")
    t.contains(p.do("x overload drone"), "You invoke overload at a welder drone.")
    q = w.spawn_player("Bea", at = ":gate", skills = {":overload": 100})
    t.contains(q.do("% overload drone"), "You invoke overload at a welder drone.")

def test_cast_and_the_track_commands_do_not_exist(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate", skills = {":overload": 100})
    w.spawn(":welder_drone", at = ":gate")
    t.contains(p.do("cast overload drone"), "Huh?")
    t.contains(p.do("advance drone"), "Huh?")
    t.contains(p.do("retreat"), "Huh?")

def test_the_world_reskins_combat_and_conditions(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate")
    drone = w.spawn(":welder_drone", at = ":gate")
    t.contains(p.do("kill drone"), "You open fire on a welder drone!")
    t.contains(p.do("quit"), "You can't disconnect mid-firefight!")
    w.damage(p, "integrity", amount = p.integrity)
    t.contains(p.output(), "Your systems fail and you power down.")

def test_routine_can_be_shrugged_off(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate", skills = {":mind_lash": 100})
    drone = w.spawn(":welder_drone", at = ":gate")
    # A roll of 100 is above any chance _spell_hit can give, so the routine is shrugged off.
    w.force_rng(ints = [100])
    sanity = drone.sanity
    out = p.do("invoke mind_lash drone")
    t.contains(out, "A welder drone shrugs off your mind_lash.")
    t.eq(drone.sanity, sanity)

def test_failed_skill_check_costs_power_and_emits_failure(t):
    # Seed 1: the d100 roll for skill_check fails for proficiency 0 (check 20).
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate", skills = {":mind_lash": 0})
    drone = w.spawn(":welder_drone", at = ":gate")
    sanity = drone.sanity
    power = p.power
    out = p.do("invoke mind_lash drone")
    t.contains(out, "You fail to cast mind_lash.")
    t.eq(p.power, power - 10)
    t.eq(drone.sanity, sanity)

def test_skill_power_scales_damage_with_proficiency(t):
    # A caster at proficiency 100 (power 100) and one at 40 (power 70) cast :mind_lash at identical targets.
    # Seed 1 gives identical base damage rolls, and the power 100 cast deals more damage.
    w1 = t.world(seed = 1)
    expert = w1.spawn_player("Ann", at = ":gate", skills = {":mind_lash": 100})
    drone1 = w1.spawn(":welder_drone", at = ":gate")
    expert.do("invoke mind_lash drone")
    damage_expert = drone1.max_sanity - drone1.sanity

    w2 = t.world(seed = 1)
    apprentice = w2.spawn_player("Bea", at = ":gate", skills = {":mind_lash": 40})
    drone2 = w2.spawn(":welder_drone", at = ":gate")
    apprentice.do("invoke mind_lash drone")
    damage_apprentice = drone2.max_sanity - drone2.sanity

    t.true(damage_expert > damage_apprentice)

