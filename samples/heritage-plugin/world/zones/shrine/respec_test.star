# Tests for the respec library, driven by the fixture in shrine_testdata.star. Each fixture
# keeper stands in a room of its own; the content mentor stands in :mentor_hall.

load("//lib:respec.star", "respec")
load("//lib:trainer.star", "trainer")

def _world(t):
    return t.world(seed = 1, packages = ["//zones/shrine"])

def _warrior(w, name = "Tess", at = ":respec_test_room", race = "human", skills = None):
    return w.spawn_player(name, at = at, choices = {"race": race, "class": "warrior"},
                          skills = skills or {})

def _coins(p):
    n = 0
    for i in p.inventory():
        if i.label == "//lib:gold_coin":
            n += i.count
    return n

def _give_coins(w, p, n):
    c = w.ctx()
    made = c.grant_item(p, "//lib:gold_coin", n)
    for v in (made if type(made) == "list" else [made]):
        c.merge_stack(v)

def _keeper(p):
    """The one mob standing in the player's room: the fixture keeper."""
    return [a for a in p.room.actors() if not a.is_player][0]

def _unchanged(t, p, cls, coins):
    t.eq(p.choices["class"], cls)
    t.eq(_coins(p), coins)

def test_menu_lists_options_prices_and_readiness(t):
    w = _world(t)
    p = _warrior(w)
    out = p.do("respec")
    t.contains(out, "the test mentor can change your class (now Warrior):")
    t.contains(out, "  Warrior: Masters of weapons and armor. (That is already your class.)")
    t.contains(out, "  Mage - 10 gold coins: Scholars of arcane destruction.")
    t.contains(out, "  Cleric - 10 gold coins: Holy servants of divine recovery.")
    t.contains(out, "You can change now.")

def test_menu_shows_refusals_and_free_prices(t):
    w = _world(t)
    p = _warrior(w, race = "dwarf")
    t.contains(p.do("respec"), "  Mage: Scholars of arcane destruction. (Mage isn't open to you.)")
    p.do("offer free")
    t.contains(p.do("respec"), "  Cleric - free: Holy servants of divine recovery.")
    p.do("offer refuse")
    t.contains(p.do("respec"), "  Cleric: Holy servants of divine recovery. (The mentor refuses you.)")

def test_respec_changes_class_pays_and_grants_starter_skills(t):
    w = _world(t)
    p = _warrior(w, skills = {"//skills/arcane:spark": 20})
    _give_coins(w, p, 10)
    out = p.do("respec mage")
    t.contains(out, "You pay 10 gold coins.")
    t.contains(out, "Your class is now Mage.")
    t.contains(out, "You learn magic_missile.")
    t.eq(p.choices["class"], "mage")
    t.true(p.has_trait("mage"))
    t.false(p.has_trait("warrior"))
    t.eq(p.skills["//skills/heritage_skills:magic_missile"], 25)
    t.eq(p.skills["//skills/arcane:spark"], 20)   # "keep" leaves every skill
    t.eq(_coins(p), 0)

def test_the_room_sees_the_change(t):
    w = _world(t)
    p = _warrior(w)
    ash = _warrior(w, name = "Ash")
    _give_coins(w, p, 10)
    p.do("respec cleric")
    t.contains(ash.output(), "Tess is changed by the test mentor.")

def test_refusals_change_nothing(t):
    w = _world(t)
    p = _warrior(w, at = ":empty_test_room")
    t.contains(p.do("respec mage"), "There's no one here who can change you.")
    p = _warrior(w, name = "Ash")
    _give_coins(w, p, 10)
    w.ctx().apply_affect(_keeper(p), "stun", 10, flags = ["stunned"])
    t.contains(p.do("respec mage"), "the test mentor can't help you right now.")
    _unchanged(t, p, "warrior", 10)
    w.run(pulses = 20)   # the stun wears off
    t.contains(p.do("respec bard"), "There's nothing like that to become here.")
    t.contains(p.do("respec warrior"), "That is already your class.")
    p.do("offer refuse")
    t.contains(p.do("respec mage"), "The mentor refuses you.")
    _unchanged(t, p, "warrior", 10)
    t.eq(p.skills.get("//skills/heritage_skills:magic_missile"), None)

def test_a_fighting_keeper_refuses(t):
    w = _world(t)
    ash = _warrior(w, name = "Ash")
    p = _warrior(w)
    ash.do("kill mentor")
    t.contains(p.do("respec mage"), "the test mentor is busy fighting.")
    t.eq(p.choices["class"], "warrior")

def test_define_refuses_bad_arguments(t):
    w = _world(t)
    p = _warrior(w)
    cases = [
        ("id_empty", "define(id): must be a non-empty string"),
        ("id_digit", "must start with a letter a-z"),
        ("id_chars", "is not made of [a-z0-9_]"),
        ("choices_empty", "define(choices): must be a non-empty list of choice ids"),
        ("choices_type", "define(choices): must be a non-empty list of choice ids"),
        ("choices_item", "define(choices): 3 is not a choice id string"),
        ("skills", "define(skills): must be \"keep\" or \"reset\", got \"forget\""),
        ("cost", "define(cost): must be an int >= 0, got -1"),
        ("cooldown", "define(cooldown_pulses): must be an int >= 0, got \"soon\""),
        ("currency", "define(currency): \":gold_coin\" is not an absolute label starting with //"),
    ]
    for i, (case, message) in enumerate(cases):
        p.do("bad " + case)
        t.eq(len(w.diagnostics()), i + 1)
        t.contains(w.diagnostics()[i].message, message)

def test_defaults_fill_what_define_leaves_out(t):
    d = respec.define(id = "plain", choices = ["class"])
    t.eq(d, {"id": "plain", "choices": ["class"], "skills": "keep", "cost": 0,
             "currency": "//lib:gold_coin", "cooldown_pulses": 0})

def test_a_dwarf_cannot_become_a_mage(t):
    w = _world(t)
    p = _warrior(w, race = "dwarf")
    _give_coins(w, p, 10)
    t.contains(p.do("respec mage"), "Mage isn't open to you.")
    _unchanged(t, p, "warrior", 10)

def test_too_few_coins(t):
    w = _world(t)
    p = _warrior(w)
    _give_coins(w, p, 9)
    t.contains(p.do("respec mage"), "You can't afford that.")
    _unchanged(t, p, "warrior", 9)
    t.eq(p.skills.get("//skills/heritage_skills:magic_missile"), None)

def test_a_free_offer_prints_no_payment(t):
    w = _world(t)
    p = _warrior(w)
    p.do("offer free")
    out = p.do("respec mage")
    t.contains(out, "Your class is now Mage.")
    t.not_contains(out, "You pay")

def test_bad_offer_values_are_ignored(t):
    w = _world(t)
    p = _warrior(w)
    for mode in ["junk", "number"]:
        p.do("offer " + mode)
        t.contains(p.do("respec"), "  Mage - 10 gold coins: Scholars of arcane destruction.")
    _give_coins(w, p, 10)
    t.contains(p.do("respec mage"), "You pay 10 gold coins.")
    t.contains(p.do("respec"), "You can change now.")   # the junk cooldown was ignored too

def test_cooldown_refuses_until_it_wears_off(t):
    w = _world(t)
    p = _warrior(w, at = ":cooldown_test_room")
    t.contains(p.do("respec cleric"), "Your class is now Cleric.")
    out = p.do("respec")
    t.contains(out, "You can change again in 2 minutes.")
    t.contains(p.do("respec race elf"), "You must wait 2 more minutes before changing again.")
    t.eq(p.choices["race"], "human")
    w.run(pulses = 480)
    t.contains(p.do("respec race elf"), "Your race is now Elf.")
    t.eq(p.choices["race"], "elf")

def test_one_cooldown_covers_every_shrine(t):
    w = _world(t)
    p = _warrior(w, at = ":cooldown_test_room")
    p.do("respec race elf")
    w.ctx().teleport(p, ":respec_test_room")
    t.eq(p.room, ":respec_test_room")
    _give_coins(w, p, 10)
    t.contains(p.do("respec mage"), "You must wait 2 more minutes before changing again.")
    t.eq(p.choices["class"], "warrior")

def test_a_mage_cannot_keep_using_plate(t):
    w = _world(t)
    p = _warrior(w)
    c = w.ctx()
    t.eq(c.wear(p, c.grant_item(p, ":plate_mail")), None)
    t.ne(p.equipped("body"), None)
    _give_coins(w, p, 10)
    out = p.do("respec mage")
    t.contains(out, "Your class is now Mage.")
    t.contains(out, "Your spellcraft can't work through that much metal.")
    eq = p.do("equipment")
    t.contains(eq, "a suit of plate mail")
    t.contains(eq, "(inactive)")

def test_the_guild_mentor_discounts_clerics(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":mentor_hall", choices = {"race": "human", "class": "cleric"})
    t.contains(p.do("respec"), "  Mage - 8 gold coins: Scholars of arcane destruction.")
    ash = _warrior(w, name = "Ash", at = ":mentor_hall")
    t.contains(ash.do("respec"), "  Mage - 10 gold coins: Scholars of arcane destruction.")

# --- The reset policy, standing and the rebirth altar -----------------------------------------

def _mage(w, name = "Tess", at = ":rebirth_test_room", race = "human"):
    return w.spawn_player(name, at = at, choices = {"race": race, "class": "mage"})

def test_reset_to_another_class_refunds_and_forgets(t):
    w = _world(t)
    p = _mage(w)
    t.eq(p.skills["//skills/heritage_skills:magic_missile"], 25)   # from creation
    trainer.grant_practices(w.ctx(), p, 2)
    p.do("practice spark")
    p.do("practice spark")
    t.contains(p.do("practice"), "You have 0 practice sessions.")
    _give_coins(w, p, 100)
    out = p.do("respec cleric")
    t.contains(out, "You pay 100 gold coins.\nYour class is now Cleric.\nYou forget everything you had learned.\n" +
               "2 practice sessions are returned to you.\nYou learn minor_heal.")
    t.eq(p.skills, {"//skills/heritage_skills:minor_heal": 25})
    t.contains(p.do("practice"), "You have 2 practice sessions.")
    t.eq(_coins(p), 0)

def test_reset_with_nothing_practiced_returns_nothing(t):
    w = _world(t)
    p = _mage(w)
    _give_coins(w, p, 100)
    out = p.do("respec cleric")
    t.contains(out, "You forget everything you had learned.")
    t.not_contains(out, "returned to you")

def test_reset_to_the_same_class_starts_over(t):
    w = _world(t)
    p = _mage(w)
    w.set_skill(p, "//skills/heritage_skills:magic_missile", 60)
    _give_coins(w, p, 100)
    out = p.do("respec class mage")
    t.contains(out, "Your class is now Mage.")
    t.contains(out, "You forget everything you had learned.")
    t.contains(out, "You learn magic_missile.")
    t.eq(p.skills, {"//skills/heritage_skills:magic_missile": 25})

def test_the_altar_changes_race(t):
    w = _world(t)
    p = _mage(w)
    _give_coins(w, p, 100)
    t.contains(p.do("respec race elf"), "Your race is now Elf.")
    t.eq(p.choices["race"], "elf")
    t.true(p.has_trait("elf"))
    elf = _mage(w, name = "Ash", race = "elf")
    _give_coins(w, elf, 100)
    t.contains(elf.do("respec race dwarf"), "That would leave your class (Mage) unavailable.")
    t.eq(elf.choices["race"], "elf")
    t.eq(_coins(elf), 100)

def test_the_priestess_prices_by_standing(t):
    w = _world(t)
    p = _mage(w)
    _give_coins(w, p, 100)
    w.ctx().adjust_standing(_keeper(p), p, -20)
    t.contains(p.do("respec"), "  Cleric - 500 gold coins: Holy servants of divine recovery.")
    t.contains(p.do("respec cleric"), "You can't afford that.")
    w.ctx().adjust_standing(_keeper(p), p, -60)
    t.contains(p.do("respec cleric"), "The priestess turns her back on you.")
    t.eq(p.choices["class"], "mage")
    t.eq(_coins(p), 100)

def test_respec_done_warms_the_priestess(t):
    w = _world(t)
    p = _mage(w)
    _give_coins(w, p, 100)
    t.eq(w.ctx().disposition(_keeper(p), p), 0)
    p.do("respec cleric")
    t.eq(w.ctx().disposition(_keeper(p), p), 10)

def test_the_hook_can_switch_reset_to_keep(t):
    w = _world(t)
    p = _mage(w, at = ":keep_test_room")
    t.contains(p.do("respec mage"), "That is already your class.")
    out = p.do("respec cleric")
    t.contains(out, "Your class is now Cleric.")
    t.not_contains(out, "You forget")
    t.eq(p.skills["//skills/heritage_skills:magic_missile"], 25)
    t.eq(p.skills["//skills/heritage_skills:minor_heal"], 25)
