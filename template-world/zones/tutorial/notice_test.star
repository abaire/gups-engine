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

def test_a_blind_mob_never_attacks_a_visible_player(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":notice_test_room")
    w.spawn(":test_blind", at = ":notice_test_room")
    w.run(pulses = 20)
    t.eq(len(p.fighting()), 0, "sight 0 notices nothing")

def test_a_listener_attacks_a_hidden_player(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":notice_test_room")
    p.do("hide")
    w.spawn(":test_listener", at = ":notice_test_room")   # sound is not hidden, so it still notices
    w.run(pulses = 20)
    t.true(len(p.fighting()) > 0, "the listener notices by sound despite the hide")

def test_a_mob_with_chance_0_never_attacks(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":notice_test_room")
    w.spawn(":test_dozer", at = ":notice_test_room")
    w.run(pulses = 20)
    t.eq(len(p.fighting()), 0, "chance 0 never looks")
