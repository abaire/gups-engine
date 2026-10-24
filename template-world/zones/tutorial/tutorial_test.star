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
    w.damage(dummy, pct = 60)
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
