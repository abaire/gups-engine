# Test-only probe mobs for //lib:ai.star. Each runs a small tree in its think and records what
# ran in its blackboard, so ai_test.star can read it back from the `lib:ai` attribute.

load("//lib:ai.star", "ai")

def _log(bb, msg):
    bb["log"] = bb.get("log", []) + [msg]

def _fail_1(ctx, bb):
    _log(bb, "fail_1")
    return ai.FAILURE

def _succeed_1(ctx, bb):
    _log(bb, "succeed_1")
    return ai.SUCCESS

def _succeed_2(ctx, bb):
    _log(bb, "succeed_2")
    return ai.SUCCESS

def _running_2(ctx, bb):
    _log(bb, "running_2")
    return ai.RUNNING

def _never_3(ctx, bb):
    _log(bb, "never_3")
    return ai.SUCCESS

def _step_1(ctx, bb):
    _log(bb, "step_1")
    return ai.SUCCESS

def _step_2(ctx, bb):
    _log(bb, "step_2")
    return ai.FAILURE if ctx.get_attr(ctx.self, "step_2_fails", False) else ai.SUCCESS

def _failed(ctx, bb):
    _log(bb, "failed")
    return ai.FAILURE

def _idled(ctx, bb):
    _log(bb, "idled")
    return ai.SUCCESS

def _fallback(ctx, bb):
    _log(bb, "fallback")
    return ai.FAILURE

def _bb_once(ctx, bb):
    if "first_cycle" not in bb:
        bb["first_cycle"] = True
    return ai.SUCCESS

def _bad_status(ctx, bb):
    return "invalid_status"

# GOAP probe: the test drives the sensed position and the goal through attributes. Each action
# records its name and moves the sensed position to its effect.
def _goap_sense(ctx, bb):
    return {"at": ctx.get_attr(ctx.self, "sensed_at", "start")}

def _goap_goal(ctx, bb):
    return {"at": ctx.get_attr(ctx.self, "goal_at", "goal")}

def _goap_step(ctx, bb, name, to):
    bb["ran"] = bb.get("ran", []) + [name]
    ctx.set_attr(ctx.self, "sensed_at", to)
    return ai.RUNNING

def _act1(ctx, bb):
    return _goap_step(ctx, bb, "act1", "mid")

def _act2(ctx, bb):
    return _goap_step(ctx, bb, "act2", "goal")

def _act3(ctx, bb):
    return _goap_step(ctx, bb, "act3", "mid")

def _act4(ctx, bb):
    return _goap_step(ctx, bb, "act4", "far")

GOAP_ACTIONS = [
    {"name": "act1", "pre": {"at": "start"}, "effects": {"at": "mid"}, "run": _act1},
    {"name": "act2", "pre": {"at": "mid"}, "effects": {"at": "goal"}, "run": _act2},
    {"name": "act3", "pre": {"at": "elsewhere"}, "effects": {"at": "mid"}, "run": _act3},
    {"name": "act4", "pre": {"at": "goal"}, "effects": {"at": "far"}, "run": _act4},
]

SELECTOR = ai.selector([ai.action(_fail_1), ai.action(_succeed_2), ai.action(_never_3)])
SEQUENCE = ai.sequence([ai.action(_succeed_1), ai.action(_running_2), ai.action(_never_3)])
INVERTER = ai.inverter(ai.action(_fail_1))
STEPS = ai.steps("test_steps", [ai.action(_step_1), ai.action(_step_2)])
FAILING = ai.action(_failed)
SUCCEEDING = ai.action(_idled)
BLACKBOARD = ai.action(_bb_once)
GOAP = ai.selector([
    ai.goap("test_goap", _goap_sense, GOAP_ACTIONS, _goap_goal),
    ai.action(_fallback),
])
BAD_STATUS = ai.action(_bad_status)

def _selector_think(ctx):
    return ai.run(ctx, SELECTOR)

def _sequence_think(ctx):
    return ai.run(ctx, SEQUENCE)

def _inverter_think(ctx):
    return ai.run(ctx, INVERTER)

def _steps_think(ctx):
    return ai.run(ctx, STEPS)

def _failing_think(ctx):
    return ai.run(ctx, FAILING)

def _succeeding_think(ctx):
    return ai.run(ctx, SUCCEEDING)

def _blackboard_think(ctx):
    return ai.run(ctx, BLACKBOARD)

def _goap_think(ctx):
    return ai.run(ctx, GOAP)

def _bad_status_think(ctx):
    return ai.run(ctx, BAD_STATUS)

def _probe_empty_selector(ctx):
    ai.selector([])

def _probe_deep_tree(ctx):
    node = ai.action(_idled)
    for _ in range(16):
        node = ai.inverter(node)

room("ai_probe_room",
    name = "AI Probe Chamber",
    desc = "A blank chamber.",
    size = SIZE.normal,
    commands = {
        "probe_empty": _probe_empty_selector,
        "probe_deep": _probe_deep_tree,
    },
)

def _probe(name, think, behaviors = []):
    mob(
        "test_ai_" + name,
        name = "test " + name,
        desc = "test",
        level = 1,
        vitals = vitals(hp = 20, move = 100),
        think = think,
        think_every = 1,
        behaviors = behaviors,
    )

_probe("selector", _selector_think)
_probe("sequence", _sequence_think, [wander(chance = 100)])
_probe("inverter", _inverter_think, [wander(chance = 100)])
_probe("steps", _steps_think)
_probe("failing", _failing_think, [wander(chance = 100)])
_probe("succeeding", _succeeding_think, [wander(chance = 100)])
_probe("blackboard", _blackboard_think)
_probe("goap", _goap_think)
_probe("bad_status", _bad_status_think)
