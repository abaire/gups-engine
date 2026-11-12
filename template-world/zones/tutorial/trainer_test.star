# Tests for the trainer library, driven by the fixture in trainer_testdata.star. Every
# function named test_* is a test and gets `t`. The fixture trainer stands in :trainer_test_room.

def _world(t):
    return t.world(seed = 1, packages = ["//zones/tutorial"])

def test_practice_learns_a_new_skill_at_start(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":trainer_test_room")
    p.do("grant 5")
    t.contains(p.do("practice mend"), "You learn mend from the test trainer. (15%)")
    t.eq(p.skills["//skills/arcane:mend"], 15)
    t.contains(p.do("practice"), "You have 4 practice sessions.")   # spent one

def test_practice_raises_a_known_skill_and_caps(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":trainer_test_room")
    p.do("grant 10")
    t.contains(p.do("practice spark"), "You learn spark from the test trainer. (10%)")
    t.eq(p.skills["//skills/arcane:spark"], 10)
    t.contains(p.do("practice spark"), "You practice spark with the test trainer. (10% → 20%)")
    t.eq(p.skills["//skills/arcane:spark"], 20)
    p.do("grant 7")
    w.set_skill(p, "//skills/arcane:spark", 50)   # at the trainer's up_to
    t.contains(p.do("practice spark"), "You've learned all the test trainer can teach you about spark.")
    t.contains(p.do("practice"), "You have 15 practice sessions.")   # the cap spends nothing

def test_levels_change_the_practice_balance(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":trainer_test_room", level = 5)
    w.award_xp(p, 21000)   # two levels (5 -> 7): +4 practice sessions
    t.contains(p.do("practice"), "You have 4 practice sessions.")
    w.level_down(p, 3)     # three levels (7 -> 4): -6 practice sessions
    t.contains(p.do("practice"), "You owe 2 practice sessions.")
    t.contains(p.do("practice spark"), "you owe 2")   # refused, spends nothing

def test_practice_offer_can_refuse_or_reprice(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":offer_test_room")
    p.do("grant 5")
    p.do("set_offer refuse")
    t.contains(p.do("practice spark"), "The trainer refuses you.")
    t.eq(p.skills.get("//skills/arcane:spark", 0), 0)
    t.contains(p.do("practice"), "You have 5 practice sessions.")   # the refusal spends nothing
    p.do("set_offer dict")
    t.contains(p.do("practice spark"), "You learn spark from the test offer trainer. (30%)")
    t.eq(p.skills["//skills/arcane:spark"], 30)   # the hook's gain
    t.contains(p.do("practice"), "You have 3 practice sessions.")   # the hook's cost

def test_practice_refusals(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":gate")   # no trainer here
    t.contains(p.do("practice spark"), "There's no trainer here.")
    p = w.spawn_player("Ash", at = ":trainer_test_room")
    p.do("stun_trainer")
    t.contains(p.do("practice spark"), "the test trainer can't teach right now.")
    w.run(pulses = 20)   # the stun wears off
    p.do("grant 5")
    t.contains(p.do("practice fizz"), "the test trainer can't teach you that.")

def test_practice_alone_shows_balance_and_teaching(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":trainer_test_room")
    p.do("grant 3")
    p.do("grant 4")
    out = p.do("practice")
    t.contains(out, "You have 7 practice sessions.")
    t.contains(out, "the test trainer teaches:")
    t.contains(out, "spark: not learned, up to 50%")
    t.contains(out, "mend: not learned, up to 40%")
    w.set_skill(p, "//skills/arcane:spark", 20)
    t.contains(p.do("practice"), "spark: 20%, up to 50%")

def test_practice_never_raises_past_the_cap(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":trainer_test_room")
    p.do("grant 2")
    w.set_skill(p, "//skills/arcane:spark", 45)   # one gain of 10 would overshoot up_to = 50
    t.contains(p.do("practice spark"), "You practice spark with the test trainer. (45% → 50%)")
    t.eq(p.skills["//skills/arcane:spark"], 50)

def test_refund_returns_recorded_sessions(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":trainer_test_room")
    p.do("grant 5")
    p.do("practice spark")
    p.do("practice spark")
    p.do("practice mend")
    t.contains(p.do("practice"), "You have 2 practice sessions.")
    t.contains(p.do("refund //skills/arcane:spark"), "Refunded 2.")
    t.contains(p.do("refund"), "Refunded 1.")
    t.contains(p.do("refund"), "Refunded 0.")
    t.contains(p.do("practice"), "You have 5 practice sessions.")

def test_refund_records_hook_costs(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":offer_test_room")
    p.do("grant 5")
    p.do("set_offer dict")
    p.do("practice spark")
    t.contains(p.do("refund"), "Refunded 2.")
    t.contains(p.do("practice"), "You have 5 practice sessions.")

def test_refused_session_records_nothing(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":offer_test_room")
    p.do("grant 5")
    p.do("set_offer refuse")
    p.do("practice spark")
    t.contains(p.do("refund"), "Refunded 0.")
