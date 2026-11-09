# Tests for the pace and the pursuit cut-off, through the mobs in pursuit_testdata.star. The
# world is a fresh copy on each call; the chase rooms and mobs live there and nowhere else.

def _world(t):
    return t.world(seed = 1, packages = ["//zones/tutorial"])

def test_pace_views(t):
    w = _world(t)
    cat = w.spawn(":hunting_cat", at = ":chase_r0")
    player = w.spawn_player("Tess", at = ":chase_r0")
    plodder = w.spawn(":goblin_plodder", at = ":chase_r0")
    # A speed of 300 walks three rooms a pulse, 100 walks one, 75 three rooms every four pulses.
    t.eq(cat.move_pace, 33)
    t.eq(player.move_pace, 100)
    t.eq(plodder.move_pace, 133)

def test_fast_hunter_catches_up_before_the_end(t):
    w = _world(t)
    cat = w.spawn(":hunting_cat", at = ":chase_r0")
    player = w.spawn_player("Tess", at = ":chase_r3")
    # The player is three rooms ahead and walks away; the faster hunter closes the gap.
    for _ in range(5):
        if cat.room.label == player.room.label:
            break
        player.do("east")
    t.eq(cat.room.label, player.room.label)
    t.ne(player.room.label, "//zones/tutorial:chase_r7")

def test_slow_pursuer_never_catches_the_walking_player(t):
    w = _world(t)
    plodder = w.spawn(":goblin_plodder", at = ":chase_r0")
    player = w.spawn_player("Tess", at = ":chase_r3")
    # The plodder walks slower than the player, so it never closes the three-room gap.
    for _ in range(4):
        player.do("east")
    t.ne(plodder.room.label, player.room.label)

def test_fast_hunter_cuts_the_player_off(t):
    w = _world(t)
    cat = w.spawn(":hunting_cat", at = ":chase_r0")
    player = w.spawn_player("Tess", at = ":chase_r0")
    w.run(pulses = 4)    # the aggressive hunter engages the player in the room it is in
    # The player keeps trying to leave the room the hunter is in; the faster one stops it.
    out = ""
    for _ in range(8):
        out += player.do("flee") + player.do("east")
        if "cuts you off" in out:
            break
    t.true("cuts you off" in out)

def test_slow_pursuer_never_cuts_the_player_off(t):
    w = _world(t)
    plodder = w.spawn(":goblin_plodder", at = ":chase_r0")
    player = w.spawn_player("Tess", at = ":chase_r0")
    w.run(pulses = 4)    # the aggressive plodder engages the player in the room it is in
    t.true(player in plodder.fighting())
    # The plodder carries the same cut-off, but it is slower than the player, so it never fires.
    out = ""
    for _ in range(8):
        out += player.do("flee") + player.do("east")
    t.false("cuts you off" in out)
