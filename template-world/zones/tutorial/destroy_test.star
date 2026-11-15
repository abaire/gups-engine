# Tests for the salvage command and on_destroy hooks in the tutorial zone.

def _world(t):
    return t.world(seed = 1)

def test_salvage_requires_argument_and_carried_item(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":armory")
    t.contains(p.do("salvage"), "Salvage what?")
    t.contains(p.do("salvage flask"), "You aren't carrying that.")

def test_salvage_volatile_flask_burns_salvager(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":armory")
    observer = w.spawn_player("Ash", at = ":armory")
    p.do("get flask")
    hp_before = p.hp
    out = p.do("salvage flask")
    t.contains(out, "You break down a volatile flask.")
    t.contains(out, "The volatile flask bursts in a gout of flame!")
    t.true(p.hp < hp_before)
    obs_out = observer.output()
    t.contains(obs_out, "Tess breaks down a volatile flask.")
    t.contains(obs_out, "The volatile flask bursts in a gout of flame!")

def test_salvage_refuses_no_salvage_key(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":hall")
    w.spawn(":brass_key", at = p)
    t.contains(p.do("salvage key"), "You can't salvage a brass key.")

def test_salvage_satchel_frees_brass_key(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":hall")
    p.do("get satchel")
    out = p.do("salvage satchel")
    t.contains(out, "You break down a traveler's satchel.")
    t.contains(out, "The brass key clatters free.")
    hall_items = w.room(":hall").items()
    t.eq([i.label for i in hall_items if i.label == "//zones/tutorial:satchel"], [])
    t.eq(len([i for i in hall_items if i.label == "//zones/tutorial:brass_key"]), 1)

def test_corpse_decay_in_arena_raises_one_rotten_ghost(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":arena")
    g1 = w.spawn(":goblin", at = ":arena")
    g2 = w.spawn(":goblin", at = ":arena")
    w.damage(g1, "hp", amount = 100)
    w.damage(g2, "hp", amount = 100)
    w.run(pulses = 1205)
    t.contains(p.output(), "A rotten ghost rises from the rotting corpse.")
    ghosts = [a for a in w.room(":arena").actors() if a.label == "//zones/tutorial:rotten_ghost"]
    t.eq(len(ghosts), 1)
