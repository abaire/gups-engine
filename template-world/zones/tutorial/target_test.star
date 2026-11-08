# Tests for the sample target_order, driven by the probes in target_testdata.star. Every function named test_* is a
# test and gets `t`.

def _world(t):
    return t.world(seed = 1, packages = ["//zones/tutorial"])

def _fights(p):
    return len(p.fighting()) > 0

def test_unknown_strategy_still_attacks(t):
    w = _world(t)
    a = w.spawn_player("Ash", at = ":target_test_room")
    b = w.spawn_player("Bea", at = ":target_test_room")
    w.spawn(":test_picker_odd", at = ":target_test_room")
    w.run(pulses = 20)
    t.true(_fights(a), "the lowest id is attacked")
    t.false(_fights(b), "the other player is left alone")
    t.eq(len(w.diagnostics()), 0, "an unknown strategy is no error")

def test_weakest_is_attacked_first(t):
    w = _world(t)
    a = w.spawn_player("Ash", at = ":target_test_room")
    b = w.spawn_player("Bea", at = ":target_test_room")
    w.damage(b, "hp", pct = 50)
    w.spawn(":test_picker_weak", at = ":target_test_room")
    w.run(pulses = 20)
    t.true(_fights(b), "the wounded player is attacked")
    t.false(_fights(a), "the healthy player is left alone")

def test_delay_spares_a_newcomer(t):
    w = _world(t)
    p = w.spawn_player("Ash", at = ":target_test_hall")
    w.spawn(":test_waiter", at = ":target_test_room")
    w.run(pulses = 8)
    p.do("east")
    w.run(pulses = 4)
    t.false(_fights(p), "not attacked on the first think after entry")
    w.run(pulses = 4)
    t.false(_fights(p), "not attacked before the delay has passed")
    w.run(pulses = 12)
    t.true(_fights(p), "attacked once the delay has passed")

def test_delay_is_not_reset_by_stepping_out_and_back(t):
    w = _world(t)
    p = w.spawn_player("Ash", at = ":target_test_hall")
    w.spawn(":test_waiter", at = ":target_test_room")
    w.run(pulses = 8)
    p.do("east")
    w.run(pulses = 4)
    p.do("west")
    w.run(pulses = 2)
    p.do("east")
    w.run(pulses = 2)
    t.false(_fights(p), "still inside the delay")
    w.run(pulses = 8)
    t.true(_fights(p), "the delay counts from the first entry")

def _setup_switch(t):
    w = _world(t)
    a = w.spawn_player("Ash", at = ":target_test_room")
    b = w.spawn_player("Bea", at = ":target_test_room")
    w.damage(a, "hp", pct = 50)
    mob = w.spawn(":test_switcher", at = ":target_test_room")
    a.do("kill switcher")
    return w, a, b, mob

def test_switcher_turns_on_the_healer(t):
    w, a, b, mob = _setup_switch(t)
    for i in range(3):
        b.do("mend Ash")
    t.eq(mob.primary_target, a, "the mob starts on the one it fights")
    w.run(pulses = 16)
    t.true(_fights(b), "the healer is engaged")
    t.eq(mob.primary_target, b, "the mob turns on the healer")

def test_switcher_doesnt_thrash(t):
    w, a, b, mob = _setup_switch(t)
    for i in range(3):
        b.do("mend Ash")
    w.run(pulses = 16)
    t.eq(mob.primary_target, b, "the mob has turned on the healer")
    a.do("mend Ash")
    w.run(pulses = 8)
    t.eq(mob.primary_target, b, "a weaker event doesn't switch back")

def test_memory_flood_does_not_erase_threat(t):
    w, a, b, mob = _setup_switch(t)
    for i in range(50):
        a.do("say hello")
    t.contains(b.do("recall Ash"), "attack:1", "the attack memory survives the chatter")
    t.contains(b.do("recall Ash"), "say:", "the chatter is merged into one memory")

def _caster(w, name):
    p = w.spawn_player(name, at = ":target_test_room", vitals = vitals(mana = 100))
    w.set_skill(p, "//skills/arcane:pacify", 100)
    return p

# Casts pacify at the brute and returns what the caster saw. The forced rolls are the ones taken during the
# warmup: a missed swing by an engaged brute (a roll of 100) when there is one, then pacify's own 1d100.
def _cast_pacify(w, p, rolls):
    w.force_rng(ints = rolls)
    return p.do("cast pacify brute")

def _last_line(out):
    return out.strip().split("\n")[-1]

def test_pacify_ends_the_fight_and_forgets(t):
    w = _world(t)
    a = _caster(w, "Ash")
    w.spawn(":test_brute", at = ":target_test_room")
    w.run(pulses = 4)
    t.true(_fights(a), "the brute attacks")
    t.contains(_cast_pacify(w, a, [100, 1]), "calms")
    t.false(_fights(a), "the fight is over")
    t.eq(_last_line(a.do("memof brute")), "0", "the brute forgot the caster")
    w.run(pulses = 24)
    t.false(_fights(a), "the brute doesn't attack while pacified")
    w.run(pulses = 30)
    t.true(_fights(a), "the brute attacks again once the affect ends")

def test_pacify_frees_only_the_caster(t):
    w = _world(t)
    a = _caster(w, "Ash")
    b = _caster(w, "Bea")
    w.spawn(":test_brute", at = ":target_test_room")
    a.do("kill brute")
    b.do("kill brute")
    t.contains(_cast_pacify(w, b, []), "calms")
    t.false(_fights(b), "the caster is free")
    t.true(_fights(a), "the brute keeps fighting the other player")
    t.true(int(_last_line(a.do("memof brute"))) > 0, "the brute still remembers the other player")

def test_pacify_can_fail(t):
    w = _world(t)
    a = _caster(w, "Ash")
    w.spawn(":test_brute", at = ":target_test_room")
    w.run(pulses = 4)
    t.contains(_cast_pacify(w, a, [100, 100]), "shrugs off")
    t.true(_fights(a), "the fight goes on")
    t.true(int(_last_line(a.do("memof brute"))) > 0, "the memories stay")

def test_pacify_does_not_start_a_fight(t):
    w = _world(t)
    a = _caster(w, "Ash")
    a.do("hide")
    brute = w.spawn(":test_brute", at = ":target_test_room")
    t.contains(_cast_pacify(w, a, [1]), "calms")
    w.run(pulses = 12)
    t.false(_fights(a), "the caster isn't engaged")
    t.eq(len(brute.fighting()), 0, "the brute isn't engaged")
