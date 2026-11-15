# Tests for `look <target>`. The guide and the items come from the tutorial itself; the room and
# the mob and item with no desc come from look_testdata.star. Every function named test_* is a
# test and gets `t`.

def _world(t):
    return t.world(seed = 1, packages = ["//zones/tutorial"])

def test_looking_at_a_mob_shows_its_desc(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":hall")
    t.contains(p.do("look guide"), "A patient old adventurer with a lantern.")

def test_looking_at_an_item_on_the_ground_shows_its_desc(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":look_test_room")
    w.spawn(":brass_key", at = ":look_test_room")
    t.contains(p.do("look key"), "A small brass key stamped with an anvil.")

def test_looking_at_a_carried_item_shows_its_desc(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":look_test_room")
    w.spawn(":leather_cap", at = p)
    t.contains(p.do("look cap"), "A scuffed leather cap. It smells of the last person who wore it.")

def test_a_mob_without_a_desc_is_nothing_special(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":look_test_room")
    w.spawn(":test_plain_mob", at = ":look_test_room")
    t.contains(p.do("look plain"), "You see nothing special about the plain mob.")

def test_an_item_without_a_desc_is_nothing_special(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":look_test_room")
    w.spawn(":test_plain_item", at = p)
    t.contains(p.do("look stone"), "You see nothing special about a plain stone.")
