# Behavior trees and goal-oriented planning for mob think hooks.
#
#     load("//lib:ai.star", "ai")
#
#     TREE = ai.selector([
#         ai.sequence([ai.condition(_hurt), ai.action(_call_for_help)]),
#         ai.goap("chores", _sense, CHORES, {"chores_done": True}),
#     ])
#
#     def _think(ctx):
#         return ai.run(ctx, TREE)
#
# Every node returns ai.SUCCESS, ai.FAILURE or ai.RUNNING. RUNNING means "I acted this cycle and am
# not done"; parents treat it like success, so a sequence stops there and a selector returns it.
#
# The tree is evaluated from the root on every think. Nothing about which node was running is
# kept between cycles, so anything that must carry over goes in the blackboard: one dict per mob,
# handed to every leaf as `bb`, saved in the mob's `lib:ai` attribute (and so subject to the
# attribute size cap). ai.steps and ai.goap keep their own progress there.
#
# ai.run returns "idle" when the tree succeeded or is running, so the tree has claimed the turn,
# and None when it failed, so the mob takes its top built-in proposal (flee, wander, ...).

SUCCESS = "success"
FAILURE = "failure"
RUNNING = "running"
_STATUSES = [SUCCESS, FAILURE, RUNNING]

_MAX_DEPTH = 16
_MAX_NODES = 256

# Each node is visited at most twice per tick (entering it and resuming it after a child), so a
# tree within _MAX_NODES always finishes inside this many loop iterations.
_MAX_TICKS = 1024

def _is_callable(fn):
    return type(fn) in ["function", "builtin_function_or_method"]

def _is_node(v):
    return type(v) == "dict" and "kind" in v

def _node(what, kind, children, fields):
    depth = 1
    size = 1
    for c in children:
        if not _is_node(c):
            fail("%s: each child must be a node from ai.*" % what)
        depth = max(depth, 1 + c["depth"])
        size = size + c["size"]
    if depth > _MAX_DEPTH:
        fail("%s: the tree is deeper than %d levels" % (what, _MAX_DEPTH))
    if size > _MAX_NODES:
        fail("%s: the tree has more than %d nodes" % (what, _MAX_NODES))
    node = {"kind": kind, "children": list(children), "depth": depth, "size": size}
    node.update(fields)
    return node

def _composite(what, kind, children, fields = {}):
    if type(children) != "list" or not children:
        fail("%s: children must be a non-empty list" % what)
    return _node(what, kind, children, fields)

def _leaf(what, kind, fn):
    if not _is_callable(fn):
        fail("%s: fn must be a function" % what)
    return _node(what, kind, [], {"fn": fn})

def _check_id(what, id):
    if type(id) != "string" or not id:
        fail("%s: id must be a non-empty string" % what)

def _selector(children):
    return _composite("ai.selector", "selector", children)

def _sequence(children):
    return _composite("ai.sequence", "sequence", children)

def _condition(fn):
    return _leaf("ai.condition", "condition", fn)

def _action(fn):
    return _leaf("ai.action", "action", fn)

def _inverter(child):
    return _node("ai.inverter", "inverter", [child], {})

def _steps(id, children):
    _check_id("ai.steps", id)
    return _composite("ai.steps", "steps", children, {"id": id})

def _goap(id, sense, actions, goal, max_expansions = 1024):
    _check_id("ai.goap", id)
    if not _is_callable(sense):
        fail("ai.goap: sense must be a function")
    if type(actions) != "list" or not actions:
        fail("ai.goap: actions must be a non-empty list")
    if not (type(goal) == "dict" or _is_callable(goal)):
        fail("ai.goap: goal must be a dict or a function")

    # ctx.plan gets the actions without `run`; built once here, not on every tick.
    plan_actions = []
    by_name = {}
    runs = {}
    for a in actions:
        if type(a) != "dict" or type(a.get("name")) != "string":
            fail("ai.goap: each action must be a dict with a string 'name'")
        if not _is_callable(a.get("run")):
            fail("ai.goap: action '%s' needs a 'run' function" % a["name"])
        p = {k: v for k, v in a.items() if k != "run"}
        plan_actions.append(p)
        by_name[a["name"]] = p
        runs[a["name"]] = a["run"]

    return _node("ai.goap", "goap", [], {
        "id": id,
        "sense": sense,
        "plan_actions": plan_actions,
        "by_name": by_name,
        "runs": runs,
        "goal": goal,
        "max_expansions": max_expansions,
    })

def _holds(conds, facts):
    for k, v in conds.items():
        if k not in facts or facts[k] != v:
            return False
    return True

def _drop_plan(bb, id):
    plans = bb.get("goap", {})
    if id in plans:
        plans.pop(id)
        if not plans:
            bb.pop("goap")

def _cached_steps(node, cache, goal, facts):
    """The cached plan with its finished steps dropped, or None when it can't be followed."""
    if type(cache) != "dict" or cache.get("goal") != goal:
        return None
    steps = list(cache.get("steps", []))
    by_name = node["by_name"]
    for _ in range(len(steps)):
        if steps[0] not in by_name or not _holds(by_name[steps[0]].get("effects", {}), facts):
            break
        steps.pop(0)
    if not steps or steps[0] not in by_name:
        return None
    if not _holds(by_name[steps[0]].get("pre", {}), facts):
        return None
    return steps

def _tick_goap(ctx, bb, node):
    id = node["id"]
    facts = node["sense"](ctx, bb)
    if type(facts) != "dict":
        fail("ai.goap: sense must return a dict, got %s" % type(facts))
    goal = node["goal"]
    if _is_callable(goal):
        goal = goal(ctx, bb)
        if type(goal) != "dict":
            fail("ai.goap: goal must return a dict, got %s" % type(goal))

    if _holds(goal, facts):
        _drop_plan(bb, id)
        return SUCCESS

    steps = _cached_steps(node, bb.get("goap", {}).get(id), goal, facts)
    if steps == None:
        steps = ctx.plan(facts, node["plan_actions"], goal, node["max_expansions"])
        if not steps:
            _drop_plan(bb, id)
            return FAILURE if steps == None else SUCCESS

    status = node["runs"][steps[0]](ctx, bb)
    if status not in _STATUSES:
        fail("ai.goap: action '%s' returned an invalid status: %r" % (steps[0], status))
    if status == FAILURE:
        _drop_plan(bb, id)
        return FAILURE

    # The step stays first until the facts show its effects, so it runs again next cycle if it
    # needs several (walking a path, for instance).
    if "goap" not in bb:
        bb["goap"] = {}
    bb["goap"][id] = {"goal": goal, "steps": steps}
    return RUNNING

def _leave(kind, node, top, status, bb):
    """The status a composite returns once its child finished with `status`, or None to run its next child."""
    if kind == "selector" or kind == "sequence":
        stop = FAILURE if kind == "selector" else SUCCESS
        if status != stop:
            return status
        if top[1] + 1 < len(node["children"]):
            top[1] = top[1] + 1
            return None
        return status
    if kind == "inverter":
        if status == SUCCESS:
            return FAILURE
        if status == FAILURE:
            return SUCCESS
        return status

    # steps
    if "steps" not in bb:
        bb["steps"] = {}
    if status == RUNNING:
        return RUNNING
    if status == FAILURE:
        bb["steps"][node["id"]] = 0
        return FAILURE
    nxt = top[1] + 1
    if nxt >= len(node["children"]):
        bb["steps"][node["id"]] = 0
        return SUCCESS
    bb["steps"][node["id"]] = nxt
    return RUNNING

def _run_leaf(ctx, bb, node):
    kind = node["kind"]
    if kind == "condition":
        return SUCCESS if node["fn"](ctx, bb) else FAILURE
    if kind == "goap":
        return _tick_goap(ctx, bb, node)
    status = node["fn"](ctx, bb)
    if status not in _STATUSES:
        fail("ai.action: invalid status %r; return ai.SUCCESS, ai.FAILURE or ai.RUNNING" % status)
    return status

def _first_child(bb, node):
    if node["kind"] != "steps":
        return 0
    i = bb.get("steps", {}).get(node["id"], 0)
    if type(i) != "int" or i < 0 or i >= len(node["children"]):
        return 0
    return i

def _tick(ctx, tree, bb):
    # Starlark has neither recursion nor while loops, so the tree is walked with an explicit stack
    # of [node, current child index] frames inside a bounded for loop.
    stack = []
    status = None
    if tree["children"]:
        stack.append([tree, _first_child(bb, tree)])
    else:
        status = _run_leaf(ctx, bb, tree)

    for _ in range(_MAX_TICKS):
        if not stack:
            return status
        top = stack[-1]
        node = top[0]
        if status != None:
            status = _leave(node["kind"], node, top, status, bb)
            if status != None:
                stack.pop()
            continue
        child = node["children"][top[1]]
        if child["children"]:
            stack.append([child, _first_child(bb, child)])
        else:
            status = _run_leaf(ctx, bb, child)

    fail("ai.run: tree too large")

def _run(ctx, tree):
    if not _is_node(tree):
        fail("ai.run: tree must be a node from ai.*")
    bb = ctx.get_attr(ctx.self, "ai", {})
    before = repr(bb)
    status = _tick(ctx, tree, bb)
    if repr(bb) != before:
        ctx.set_attr(ctx.self, "ai", bb)
    return "idle" if status != FAILURE else None

ai = struct(
    SUCCESS = SUCCESS,
    FAILURE = FAILURE,
    RUNNING = RUNNING,
    selector = _selector,
    sequence = _sequence,
    condition = _condition,
    action = _action,
    inverter = _inverter,
    steps = _steps,
    goap = _goap,
    run = _run,
)
