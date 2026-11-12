# Test fixture for the trainer library. It keeps its own rooms and trainers out of the
# tutorial's walkthrough: the rooms have no exits and the trainers never wander. The tests in
# trainer_test.star drive them; the rest of the zone ignores them. This is a test fixture, so it
# loads only when the world is loaded for tests.

load("//lib:trainer.star", "trainer")

TEST_TRAINER = trainer.define(
    id = "test_trainer",
    teaches = [
        trainer.teach("//skills/arcane:spark", up_to = 50, gain = 10),
        trainer.teach("//skills/arcane:mend", up_to = 40, gain = 10, start = 15),
    ],
)

# A trainer whose practice_offer hook answers by an attribute the tests set.
OFFER_TRAINER = trainer.define(
    id = "test_offer_trainer",
    teaches = [trainer.teach("//skills/arcane:spark", up_to = 50, gain = 10)],
)

def _test_trainer_open(ctx):
    trainer.open(ctx, ctx.self, TEST_TRAINER)

def _test_offer_open(ctx):
    trainer.open(ctx, ctx.self, OFFER_TRAINER)

def _offer(ctx, customer, skill, current, gain, cost, refusal):
    # The keeper's custom hook: refuse, or reprice the session, by the mode the test set.
    mode = ctx.get_attr(ctx.self, "offer_mode", "default")
    if mode == "refuse":
        return "The trainer refuses you."
    if mode == "dict":
        return {"gain": 30, "cost": 2}
    return None

# grant <n>: adds n practice sessions to the player, so the tests set a balance exactly.
def _grant(ctx):
    n = int(ctx.args) if ctx.args != "" else 1
    trainer.grant_practices(ctx, ctx.actor, n)
    ctx.send(ctx.actor, "You now have %d practice sessions." % trainer.practices(ctx, ctx.actor))

# stun_trainer: stuns the fixture trainer for 10 pulses.
def _stun_trainer(ctx):
    for a in ctx.room.actors():
        if a.label == "//zones/tutorial:test_trainer":
            ctx.apply_affect(a, "stun", 10, flags = ["stunned"])
            ctx.send(ctx.actor, "You stun the trainer.")
            return
    ctx.send(ctx.actor, "The trainer is not here.")

# set_offer <mode>: sets the offer trainer's practice_offer behavior (refuse | dict | default).
def _set_offer(ctx):
    for a in ctx.room.actors():
        if a.label == "//zones/tutorial:test_offer_trainer":
            ctx.set_attr(a, "offer_mode", ctx.args)
            ctx.send(ctx.actor, "The offer mode is now %s." % ctx.args)
            return
    ctx.send(ctx.actor, "The trainer is not here.")

# refund [<skill label>]: gives back the sessions recorded for one skill, or for all of them.
def _refund(ctx):
    n = trainer.refund(ctx, ctx.actor, ctx.args or None)
    ctx.send(ctx.actor, "Refunded %d." % n)

room("trainer_test_room",
    name = "The Trainer Test Room",
    desc = "A bare room kept for the trainer tests.",
    flags = ["dead_end_ok"],
    commands = {"grant": _grant, "stun_trainer": _stun_trainer, "refund": _refund},
    spawns = [spawn(":test_trainer")],
)

room("offer_test_room",
    name = "The Offer Test Room",
    desc = "A bare room kept for the practice_offer hook tests.",
    flags = ["dead_end_ok"],
    commands = {"grant": _grant, "set_offer": _set_offer, "refund": _refund},
    spawns = [spawn(":test_offer_trainer")],
)

mob("test_trainer",
    name = "the test trainer",
    desc = "A trainer kept for the trainer tests.",
    vitals = vitals(hp = 50),
    behaviors = [sentinel()],
    skills = {"//skills/arcane:spark": 100, "//skills/arcane:mend": 100},
    on_spawn = _test_trainer_open,
)

mob("test_offer_trainer",
    name = "the test offer trainer",
    desc = "A trainer kept for the practice_offer hook tests.",
    vitals = vitals(hp = 50),
    behaviors = [sentinel()],
    skills = {"//skills/arcane:spark": 100, "//skills/arcane:mend": 100},
    hooks = {"practice_offer": _offer},
    on_spawn = _test_offer_open,
)
