# Test fixture for the respec library. Each keeper stands alone in a room with no exits, because
# the respec command serves the first keeper it finds in a room. The tests in respec_test.star
# drive them. This is a test fixture, so it loads only when the world is loaded for tests.

load("//lib:respec.star", "respec")
load("//lib:trainer.star", "trainer")
load(":shrine.star", "REBIRTH", "altar_done", "altar_offer")

TEST_MENTOR = respec.define(id = "test_mentor", choices = ["class"], cost = 10)
COOLDOWN = respec.define(id = "cooldown_test", choices = ["race", "class"], cost = 0,
                         cooldown_pulses = 480)

# The altar's terms without its cooldown, so one test can change twice.
TEST_REBIRTH = respec.define(id = "test_rebirth", choices = REBIRTH["choices"],
                             skills = REBIRTH["skills"], cost = REBIRTH["cost"],
                             currency = REBIRTH["currency"], cooldown_pulses = 0)
KEEP = respec.define(id = "keep_test", choices = ["class"], skills = "reset", cost = 0)
REBIRTH_TRAINER = trainer.define(id = "rebirth_test_trainer",
                                 teaches = [trainer.teach("//skills/arcane:spark", up_to = 50, gain = 10)])

def _test_rebirth_open(ctx):
    respec.open(ctx, ctx.self, TEST_REBIRTH)

def _keep_open(ctx):
    respec.open(ctx, ctx.self, KEEP)

def _rebirth_trainer_open(ctx):
    trainer.open(ctx, ctx.self, REBIRTH_TRAINER)

def _keep_offer(ctx, action, customer, choice, old, new, cost, currency, cooldown_pulses, skills):
    return {"skills": "keep"}

def _test_mentor_open(ctx):
    respec.open(ctx, ctx.self, TEST_MENTOR)

def _cooldown_open(ctx):
    respec.open(ctx, ctx.self, COOLDOWN)

def _test_offer(ctx, action, customer, choice, old, new, cost, currency, cooldown_pulses, skills):
    # Refuse, or make the change free, by the mode the test set.
    mode = ctx.get_attr(ctx.self, "offer_mode", "default")
    if mode == "refuse":
        return "The mentor refuses you."
    if mode == "free":
        return {"cost": 0}
    if mode == "junk":
        return {"cost": -5, "cooldown_pulses": "soon", "skills": "forget"}
    if mode == "number":
        return 42
    return None

def _keeper(ctx):
    """The respec keeper in the room. The library keeps its mark in its own namespace."""
    for a in ctx.room.actors():
        if not a.is_player and ctx.get_attr(a, "lib:respec") != None:
            return a
    return None

# coins <n>: gives the player n gold coins.
def _coins(ctx):
    made = ctx.give(ctx.actor, "//lib:gold_coin", int(ctx.args))
    for v in (made if type(made) == "list" else [made]):
        ctx.merge_stack(v)
    ctx.send(ctx.actor, "You get %s gold coins." % ctx.args)

# grant <n>: adds n practice sessions.
def _grant(ctx):
    trainer.grant_practices(ctx, ctx.actor, int(ctx.args))
    ctx.send(ctx.actor, "You now have %d practice sessions." % trainer.practices(ctx, ctx.actor))

# stun_keeper: stuns the keeper here for 10 pulses.
def _stun_keeper(ctx):
    keeper = _keeper(ctx)
    ctx.apply_affect(keeper, "stun", 10, flags = ["stunned"])
    ctx.send(ctx.actor, "You stun %s." % keeper.name)

# offer <mode>: sets the keeper's respec_offer behavior (refuse | free | junk | number | default).
def _offer(ctx):
    ctx.set_attr(_keeper(ctx), "offer_mode", ctx.args)
    ctx.send(ctx.actor, "The offer mode is now %s." % ctx.args)

# armor: gives the player a suit of plate mail and puts it on them.
def _armor(ctx):
    refusal = ctx.wear(ctx.actor, ctx.give(ctx.actor, ":plate_mail"))
    ctx.send(ctx.actor, refusal or "You are buckled into a suit of plate mail.")

# bad <case>: calls respec.define with one bad argument, so the load-time checks can be tested.
_BAD_DEFINES = {
    "id_empty": {"id": ""},
    "id_digit": {"id": "1st"},
    "id_chars": {"id": "bad id"},
    "choices_empty": {"choices": []},
    "choices_type": {"choices": "class"},
    "choices_item": {"choices": ["class", 3]},
    "skills": {"skills": "forget"},
    "cost": {"cost": -1},
    "cooldown": {"cooldown_pulses": "soon"},
    "currency": {"currency": ":gold_coin"},
}

def _bad(ctx):
    args = {"id": "x", "choices": ["class"]}
    args.update(_BAD_DEFINES[ctx.args])
    respec.define(**args)

# scorn <n>: lowers the keeper's standing with the player by n.
def _scorn(ctx):
    ctx.adjust_standing(_keeper(ctx), ctx.actor, -int(ctx.args))
    ctx.send(ctx.actor, "You scorn the keeper.")

# mood: shows the keeper's disposition toward the player.
def _mood(ctx):
    ctx.send(ctx.actor, "Disposition: %d" % ctx.disposition(_keeper(ctx), ctx.actor))

# goto <room>: moves the player to another fixture room; they have no exits.
def _goto(ctx):
    ctx.teleport(ctx.actor, ctx.args)

FIXTURE_COMMANDS = {
    "goto": _goto,
    "scorn": _scorn,
    "mood": _mood,
    "bad": _bad,
    "coins": _coins,
    "grant": _grant,
    "stun_keeper": _stun_keeper,
    "offer": _offer,
    "armor": _armor,
}

room("respec_test_room",
    name = "The Respec Test Room",
    desc = "A bare room kept for the respec tests.",
    flags = ["dead_end_ok"],
    commands = FIXTURE_COMMANDS,
    spawns = [spawn(":test_mentor")],
)

room("empty_test_room",
    name = "The Empty Test Room",
    desc = "A bare room with no keeper in it.",
    flags = ["dead_end_ok"],
)

room("cooldown_test_room",
    name = "The Cooldown Test Room",
    desc = "A bare room kept for the respec cooldown tests.",
    flags = ["dead_end_ok"],
    commands = FIXTURE_COMMANDS,
    spawns = [spawn(":cooldown_keeper")],
)

room("rebirth_test_room",
    name = "The Rebirth Test Room",
    desc = "A bare room kept for the rebirth tests, with a trainer beside the priestess.",
    flags = ["dead_end_ok"],
    commands = FIXTURE_COMMANDS,
    spawns = [spawn(":test_priestess"), spawn(":rebirth_test_trainer")],
)

room("keep_test_room",
    name = "The Keep Test Room",
    desc = "A bare room kept for the respec_offer skill-policy tests.",
    flags = ["dead_end_ok"],
    commands = FIXTURE_COMMANDS,
    spawns = [spawn(":keep_keeper")],
)

mob("test_priestess",
    name = "the test priestess",
    desc = "A keeper kept for the rebirth tests.",
    vitals = vitals(hp = 50),
    behaviors = [sentinel()],
    hooks = {"respec_offer": altar_offer, "respec_done": altar_done},
    on_spawn = _test_rebirth_open,
)

mob("rebirth_test_trainer",
    name = "the rebirth test trainer",
    desc = "A trainer kept for the rebirth tests.",
    vitals = vitals(hp = 50),
    behaviors = [sentinel()],
    skills = {"//skills/arcane:spark": 100},
    on_spawn = _rebirth_trainer_open,
)

mob("keep_keeper",
    name = "the keep keeper",
    desc = "A keeper whose hook switches a reset shrine to keep.",
    vitals = vitals(hp = 50),
    behaviors = [sentinel()],
    hooks = {"respec_offer": _keep_offer},
    on_spawn = _keep_open,
)

mob("test_mentor",
    name = "the test mentor",
    desc = "A keeper kept for the respec tests.",
    vitals = vitals(hp = 50),
    behaviors = [sentinel()],
    # It knows spark, so the tests can give a player a skill outside the heritage set.
    skills = {"//skills/arcane:spark": 100},
    hooks = {"respec_offer": _test_offer},
    on_spawn = _test_mentor_open,
)

mob("cooldown_keeper",
    name = "the cooldown keeper",
    desc = "A keeper kept for the respec cooldown tests.",
    vitals = vitals(hp = 50),
    behaviors = [sentinel()],
    on_spawn = _cooldown_open,
)

item("plate_mail",
    name = "a suit of plate mail",
    keywords = ["plate", "mail"],
    desc = "Overlapping steel plates, heavy enough to smother any spell.",
    kind = "armor",
    wear = "body",
    tags = ["heavy_armor"],
)
