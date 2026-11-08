# Tests for noticing, driven by the probes in notice_testdata.star. A probe is placed in
# :notice_test_room and the player hides or reveals there. Every function named test_* is a test
# and gets `t`.

def _world(t):
    return t.world(seed = 1, packages = ["//zones/tutorial"])

def test_a_hidden_player_is_ignored_by_a_watcher_until_revealed(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":notice_test_room")
    p.do("hide")
    w.spawn(":test_watcher", at = ":notice_test_room")   # placed after the hide, so it can't attack mid-command
    w.run(pulses = 20)
    t.eq(len(p.fighting()), 0, "the watcher can't see the hidden player")
    p.do("reveal")
    w.run(pulses = 20)
    t.true(len(p.fighting()) > 0, "the watcher attacks once the player is visible")

def test_a_mob_without_senses_never_attacks(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":notice_test_room")
    w.spawn(":test_blind", at = ":notice_test_room")
    w.run(pulses = 20)
    t.eq(len(p.fighting()), 0, "sight 0 and sound 0 notice nothing")

def test_a_blind_mob_hears_a_visible_player(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":notice_test_room")
    w.spawn(":test_listener", at = ":notice_test_room")
    w.run(pulses = 20)
    t.true(len(p.fighting()) > 0, "a blind mob hears an unhidden player")

def test_a_deaf_mob_sees_a_visible_player(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":notice_test_room")
    w.spawn(":test_deaf", at = ":notice_test_room")
    w.run(pulses = 20)
    t.true(len(p.fighting()) > 0, "a deaf mob sees a visible player")

def test_a_listener_ignores_a_silent_hidden_player(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":notice_test_room")
    p.do("hide")
    w.spawn(":test_listener", at = ":notice_test_room")
    w.run(pulses = 20)
    t.eq(len(p.fighting()), 0, "the listener ignores a silent hidden player")

def test_a_hidden_player_who_walks_in_is_not_heard(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":target_test_room")
    p.do("hide")
    p.do("west")
    w.spawn(":test_listener", at = ":target_test_room")
    w.run(pulses = 20)
    p.do("east")
    w.run(pulses = 20)
    t.eq(len(p.fighting()), 0, "a hidden player moves silently")

def test_a_mob_with_chance_0_never_attacks(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":notice_test_room")
    w.spawn(":test_dozer", at = ":notice_test_room")
    w.run(pulses = 20)
    t.eq(len(p.fighting()), 0, "chance 0 never looks")

def test_a_hidden_player_who_speaks_is_heard(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":notice_test_room")
    p.do("hide")
    w.spawn(":test_listener", at = ":notice_test_room")
    w.run(pulses = 20)
    t.eq(len(p.fighting()), 0, "the listener ignores a silent hidden player")
    p.do("say hello")
    w.run(pulses = 8)
    t.true(len(p.fighting()) > 0, "the listener attacks a hidden player it just heard")

def test_a_listener_ignores_noise_it_did_not_hear(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":notice_test_room")
    p.do("hide")
    p.do("say hello")
    w.run(pulses = 20)
    w.spawn(":test_listener", at = ":notice_test_room")
    w.run(pulses = 20)
    t.eq(len(p.fighting()), 0, "only noise the mob itself heard counts")
