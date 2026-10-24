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
    t.eq(p.stats["con"], 11)

def test_a_draught_heals_and_is_used_up(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":gate", hp = 30)
    w.damage(p, amount = 15)
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

def test_the_arena_only_wakes_with_someone_near(t):
    # bubble = True simulates only rooms near players, as the server does. The dummy's timer
    # waits while nobody is around.
    w = t.world(seed = 1, bubble = True)
    dummy = w.room(":arena").actors()[0]
    w.damage(dummy, pct = 60)
    w.run(pulses = 40)
    t.eq(dummy.hp, 12)
    w.spawn_player("Tess", at = ":hall")
    w.run(pulses = 10)
    t.eq(dummy.hp, dummy.max_hp)
