# Tests for standing, factions, grudges, rate caps, and behavior thresholds.

# The shop opens west to the armory and east to the smithy; flee picks either.
_NEXT_DOOR = {"//zones/tutorial:armory": "east", "//zones/tutorial:smithy": "west"}

def _fled_next_door(t, actor):
    """Asserts actor fled from the shop to a neighbouring room; the direction back."""
    t.true(actor.room.label in _NEXT_DOOR, actor.room.label)
    return _NEXT_DOOR[actor.room.label]

def test_hit_flee_return_attacked_on_sight(t):
    w = t.world(seed = 1)
    ash = w.spawn_player("Ash", at = ":shop")
    # Initial standing is 0; shopkeeper does not attack on sight.
    w.run(pulses = 10)
    t.eq(len(ash.fighting()), 0)

    # Ash attacks the shopkeeper
    ash.do("kill shopkeeper")
    t.true(len(ash.fighting()) > 0)

    # Flee to a neighbouring room
    for _ in range(20):
        if ash.room.label != "//zones/tutorial:shop":
            break
        ash.do("flee")
    back = _fled_next_door(t, ash)
    t.eq(len(ash.fighting()), 0)

    # Return to the shop.
    # Shopkeeper has disposition_below = -40; attack lowered personal standing to -50.
    # Shopkeeper attacks Ash on sight.
    ash.do(back)
    t.eq(ash.room.label, "//zones/tutorial:shop")
    w.run(pulses = 20)
    t.true(len(ash.fighting()) > 0)
    t.contains(ash.output(), "attacks you")

def test_group_healer_smaller_grudge_than_attacker(t):
    w = t.world(seed = 1)
    ash = w.spawn_player("Ash", at = ":shop")
    tess = w.spawn_player("Tess", at = ":shop")
    w.set_skill(tess, "//skills/arcane:soothe", 100)

    # Form a group
    ash.do("invite Tess")
    tess.do("accept")

    # Ash attacks the shopkeeper (grudge -50)
    ash.do("kill shopkeeper")
    t.true(len(ash.fighting()) > 0)

    # Tess heals Ash in combat (assist event gives smaller grudge -10)
    tess.do("cast soothe Ash")

    # Both flee to a neighbouring room
    for _ in range(20):
        if ash.room.label != "//zones/tutorial:shop":
            break
        ash.do("flee")
    for _ in range(20):
        if tess.room.label != "//zones/tutorial:shop":
            break
        tess.do("flee")
    ash_back = _fled_next_door(t, ash)
    tess_back = _fled_next_door(t, tess)

    # Tess returns to the shop first.
    # Tess's grudge is only -10, which is not below -40. Shopkeeper ignores Tess.
    tess.do(tess_back)
    t.eq(tess.room.label, "//zones/tutorial:shop")
    w.run(pulses = 20)
    t.eq(len(tess.fighting()), 0)
    t.contains(tess.do("list"), "the shopkeeper offers:")
    tess.do("west")

    # Ash returns to the shop.
    # Ash's grudge is -50 (< -40). Shopkeeper attacks Ash on sight.
    ash.do(ash_back)
    t.eq(ash.room.label, "//zones/tutorial:shop")
    w.run(pulses = 20)
    t.true(len(ash.fighting()) > 0)
    t.contains(ash.output(), "attacks you")

def test_kill_lowers_faction_standing(t):
    w = t.world(seed = 1)
    killer = w.spawn_player("Killer", at = ":standing_test_room")

    # Neutral starting standing
    t.contains(killer.do("probe townsfolk"), "townsfolk standing=0")

    # Spawn guide (in //lib:townsfolk), weaken them, and let killer land fatal blow
    guide = w.spawn(":guide", at = ":standing_test_room")
    w.damage(target = guide, vital = "hp", amount = 199)
    killer.do("kill guide")
    for _ in range(10):
        if guide.hp <= 0:
            break
        w.run(pulses = 8)

    # Kill lowers townsfolk faction standing to -50
    t.contains(killer.do("probe townsfolk"), "townsfolk standing=-50")

def test_heal_farming_cap_holds(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Healer", at = ":standing_test_room")
    w.set_skill(p, "//skills/arcane:soothe", 100)
    dummy = w.spawn(":training_dummy", at = ":standing_test_room")

    # Heal 1: +5 standing
    w.damage(target = dummy, vital = "hp", amount = 10)
    p.do("cast soothe dummy")
    t.contains(p.do("probe dummy"), "dummy standing=5 gained=5")

    # Heal 2: reaches the +10 cap
    w.damage(target = dummy, vital = "hp", amount = 10)
    p.do("cast soothe dummy")
    t.contains(p.do("probe dummy"), "dummy standing=10 gained=10")

    # Heal 3: rate cap holds, no additional standing gained
    w.damage(target = dummy, vital = "hp", amount = 10)
    p.do("cast soothe dummy")
    t.contains(p.do("probe dummy"), "dummy standing=10 gained=10")

def test_gratitude_never_passes_the_ceiling(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Healer", at = ":standing_test_room")
    w.set_skill(p, "//skills/arcane:soothe", 100)
    dummy = w.spawn(":training_dummy", at = ":standing_test_room")
    w.ctx().adjust_standing(dummy, p, 22)

    # The rate cap would allow +5, but the record stops at the ceiling of 25.
    w.damage(target = dummy, vital = "hp", amount = 10)
    p.do("cast soothe dummy")
    t.contains(p.do("probe dummy"), "dummy standing=25 gained=25")

    w.damage(target = dummy, vital = "hp", amount = 10)
    p.do("cast soothe dummy")
    t.contains(p.do("probe dummy"), "dummy standing=25 gained=25")

def test_donations_to_one_mob_are_rate_capped(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Donor", at = ":standing_test_room")
    w.spawn(":guide", at = ":standing_test_room")

    p.do("donate guide 70")
    t.contains(p.do("probe townsfolk"), "townsfolk standing=7")
    p.do("donate guide 70")
    t.contains(p.do("probe townsfolk"), "townsfolk standing=10")

def test_buff_spamming_guard_never_gets_through(t):
    w = t.world(seed = 1)
    ash = w.spawn_player("Ash", at = ":standing_gate")
    tess = w.spawn_player("Tess", at = ":standing_gate")
    w.set_skill(tess, "//skills/arcane:ward", 100)
    w.spawn(":gate_guard", at = ":standing_gate")

    # A buff counts as help only while the guard is in combat, so Ash starts a fight with it.
    ash.do("kill guard")
    for _ in range(5):
        tess.do("cast ward guard")

    # Each help is +5, but the rate cap and the gratitude ceiling keep Tess far below the
    # guard's disposition_above = 50, so it still blocks her.
    t.contains(tess.do("probe guard"), "guard standing=10")
    out = tess.do("north")
    t.contains(out, "blocks your way")
    t.eq(tess.room.label, "//zones/tutorial:standing_gate")
