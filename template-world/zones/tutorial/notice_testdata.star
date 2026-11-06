# Test fixture for the noticing tests, driven by the tests in notice_test.star. It keeps its own
# room and probe mobs out of the tutorial's walkthrough: the room has no exits and the probes never
# wander. This is a test fixture, so it loads only when the world is loaded for tests.

# hide: conceal the player for 200 pulses, storing the result as a flag a rule can read.
def _hide(ctx):
    ctx.apply_affect(ctx.actor, "hidden", 200, flags = ["hidden"])
    ctx.send(ctx.actor, "You conceal yourself.")

# reveal: undo a hide.
def _reveal(ctx):
    ctx.remove_affect(ctx.actor, "hidden")
    ctx.send(ctx.actor, "You come back into the open.")

room("notice_test_room",
    name = "The Notice Test Room",
    desc = "A bare room kept for the noticing tests.",
    flags = ["dead_end_ok"],
    commands = {"hide": _hide, "reveal": _reveal},
)

# The probes have a small vital pool and no spawn rules: the tests place them with w.spawn.
mob("test_watcher",
    name = "the test watcher",
    desc = "A probe mob that attacks players it sees.",
    vitals = vitals(hp = 20),
    behaviors = [aggressive(toward = "players")],
)

mob("test_blind",
    name = "the test blind",
    desc = "A probe mob that senses no sight.",
    vitals = vitals(hp = 20),
    senses = {"sight": 0},
    behaviors = [aggressive(toward = "players")],
)

mob("test_listener",
    name = "the test listener",
    desc = "A probe mob that notices players by sound.",
    vitals = vitals(hp = 20),
    behaviors = [aggressive(toward = "players", via = "sound")],
)

mob("test_dozer",
    name = "the test dozer",
    desc = "A probe mob that never looks for players.",
    vitals = vitals(hp = 20),
    behaviors = [aggressive(toward = "players", chance = 0)],
)
