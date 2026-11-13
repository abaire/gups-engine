# Test fixture for the target_order tests, driven by target_test.star. The two rooms lead only to each other, and the
# probes never wander. This is a test fixture, so it loads only when the world is loaded for tests.

# recall <name>: tell the player how the switcher remembers the named actor, as "<event>:<count>" entries.
def _recall(ctx):
    mob = ctx.here("switcher")
    target = ctx.target
    if mob == None or target == None:
        ctx.send(ctx.actor, "Nobody to recall.")
        return
    entries = ["{}:{}".format(m.event, m.count) for m in ctx.memories(mob, subject = target)]
    ctx.send(ctx.actor, "memories: " + ", ".join(entries))

# memof <mob keyword>: tell the player how many things the named mob remembers about them.
def _memof(ctx):
    mob = ctx.here(ctx.args)
    if mob == None:
        ctx.send(ctx.actor, "No such mob.")
        return
    ctx.send(ctx.actor, str(len(ctx.memories(mob, subject = ctx.actor))))

room("target_test_hall",
    name = "The Target Test Hall",
    desc = "A bare hall kept for the target order tests.",
    flags = ["dead_end_ok"],
    exits = [exit(DIR.east, to = ":target_test_room")],
)

room("target_test_room",
    name = "The Target Test Room",
    desc = "A bare room kept for the target order tests.",
    flags = ["dead_end_ok"],
    exits = [exit(DIR.west, to = ":target_test_hall")],
    commands = {"recall": _recall, "memof": _memof},
)

mob("test_picker_weak",
    name = "the weakest picker",
    keywords = ["picker"],
    desc = "A probe mob that attacks the weakest player.",
    vitals = vitals(hp = 20),
    behaviors = [aggressive(toward = "players", prefer = "weakest")],
)

mob("test_picker_odd",
    name = "the odd picker",
    keywords = ["picker"],
    desc = "A probe mob with a strategy name nobody knows.",
    vitals = vitals(hp = 20),
    behaviors = [aggressive(toward = "players", prefer = "nonsense")],
)

mob("test_waiter",
    name = "the test waiter",
    keywords = ["waiter"],
    desc = "A probe mob that spares newcomers for a while.",
    vitals = vitals(hp = 20),
    behaviors = [aggressive(toward = "players", prefer = {"delay": 12})],
)

mob("test_switcher",
    name = "the test switcher",
    keywords = ["switcher"],
    desc = "A probe mob that turns on whoever threatens it most.",
    vitals = vitals(hp = 200),
    behaviors = [retarget(prefer = {
        "order": "threat",
        "weights": {"attack": 0, "damage": 1, "heal": 10},
        "switch_margin": 25,
        "min_dwell": 8,
    })],
)

mob("test_brute",
    name = "the test brute",
    keywords = ["brute"],
    desc = "A probe mob that attacks any player it notices.",
    vitals = vitals(hp = 200),
    level = 1,
    behaviors = [aggressive(toward = "players")],
)
