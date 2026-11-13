# Test fixture for the noticing tests, driven by the tests in notice_test.star. It keeps its own
# room and probe mobs out of the tutorial's walkthrough: the room has no exits and the probes never
# wander. This is a test fixture, so it loads only when the world is loaded for tests.

room("notice_test_room",
    name = "The Notice Test Room",
    desc = "A bare room kept for the noticing tests.",
    flags = ["dead_end_ok"],
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
    desc = "A probe mob that senses neither sight nor sound.",
    vitals = vitals(hp = 20),
    senses = {"sight": 0, "sound": 0},
    behaviors = [aggressive(toward = "players")],
)

mob("test_listener",
    name = "the test listener",
    desc = "A probe mob that notices players by sound.",
    vitals = vitals(hp = 20),
    senses = {"sight": 0},
    behaviors = [aggressive(toward = "players")],
)

mob("test_deaf",
    name = "the test deaf",
    desc = "A probe mob that senses no sound.",
    vitals = vitals(hp = 20),
    senses = {"sound": 0},
    behaviors = [aggressive(toward = "players")],
)

mob("test_dozer",
    name = "the test dozer",
    desc = "A probe mob that never looks for players.",
    vitals = vitals(hp = 20),
    behaviors = [aggressive(toward = "players", chance = 0)],
)
