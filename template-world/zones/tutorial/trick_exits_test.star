# Tests for the tutorial zone's trick exits on :gravel (west shifting passage, down tide stair).

# Matches TIDE_PULSES in rooms.star and WORLD_TICK_PULSES (120) in core TimeConstants.kt.
TIDE_PULSES = 240
WORLD_TICK_PULSES = 120

def _world(t, seed = 1):
    return t.world(seed = seed, packages = ["//zones/tutorial"])

def test_shifting_passage_lands_in_a_hollow(t):
    hollows = ["//zones/tutorial:hollow_a", "//zones/tutorial:hollow_b"]
    seen = []
    for seed in range(1, 11):
        w = _world(t, seed = seed)
        p = w.spawn_player("Tess", at = ":gravel")
        p.do("west")
        t.true(p.room.label in hollows)
        if p.room.label not in seen:
            seen.append(p.room.label)
    t.eq(len(seen), 2)

def test_hollow_walks_back(t):
    for hollow in (":hollow_a", ":hollow_b"):
        w = _world(t)
        p = w.spawn_player("Tess", at = hollow)
        p.do("east")
        t.eq(p.room.label, "//zones/tutorial:gravel")

def test_passage_view(t):
    w = _world(t)
    ex = w.room(":gravel").exit("west")
    t.eq(ex.to, None)
    t.eq(ex.candidates, ["//zones/tutorial:hollow_a", "//zones/tutorial:hollow_b"])
    t.true(ex.one_way)

def test_tide_stair(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":gravel")
    p.do("down")
    t.eq(p.room.label, "//zones/tutorial:grotto")
    p.do("up")
    t.eq(p.room.label, "//zones/tutorial:gravel")

    # on_tick fires on world ticks (every 120 pulses), so the flip happens by 240 + 120 pulses.
    w.run(pulses = TIDE_PULSES + WORLD_TICK_PULSES)
    ex = w.room(":gravel").exit("down")
    t.eq(ex.to, "//zones/tutorial:drowned_cave")
    t.eq(ex.original_to, "//zones/tutorial:grotto")

    p.do("down")
    t.eq(p.room.label, "//zones/tutorial:drowned_cave")
