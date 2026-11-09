# Tests for //lib:ai.star, through the test_ai_ probes in ai_probes_testdata.star, and for the
# foraging squirrel in forager.star.

def _world(t):
    return t.world(seed = 1, packages = ["//zones/tutorial"])

def _bb(m):
    return m.attrs.get("lib:ai", {})

def _verbs(w, label):
    """The verbs the traced hooks of `label` called, in order, from w.trace_lines()."""
    verbs = []
    mine = False
    for line in w.trace_lines():
        text = line.split("] ", 1)[1]
        if not text.startswith("  "):
            mine = (" on " + label + " ") in text
        elif mine and "(" in text:
            verbs.append(text.strip().split("(")[0])
    return verbs

def test_selector_runs_until_the_first_child_that_does_not_fail(t):
    w = _world(t)
    m = w.spawn(":test_ai_selector", at = ":arena")
    w.run(pulses = 1)
    t.eq(_bb(m).get("log"), ["fail_1", "succeed_2"])

def test_sequence_stops_at_running_and_claims_the_turn(t):
    w = _world(t)
    m = w.spawn(":test_ai_sequence", at = ":gate")
    w.run(pulses = 2)
    t.eq(_bb(m).get("log"), ["succeed_1", "running_2", "succeed_1", "running_2"])

    # "running" makes ai.run return "idle", so wander(chance = 100) never moves it.
    t.eq(m.room.label, "//zones/tutorial:gate")

def test_inverter_turns_failure_into_success(t):
    w = _world(t)
    m = w.spawn(":test_ai_inverter", at = ":gate")
    w.run(pulses = 2)
    t.eq(_bb(m).get("log"), ["fail_1", "fail_1"])
    t.eq(m.room.label, "//zones/tutorial:gate")

def test_steps_advances_one_step_per_cycle(t):
    w = _world(t)
    m = w.spawn(":test_ai_steps", at = ":arena")
    w.run(pulses = 1)
    t.eq(_bb(m).get("log"), ["step_1"])
    t.eq(_bb(m)["steps"]["test_steps"], 1)

    # Succeeding past the end starts over.
    w.run(pulses = 1)
    t.eq(_bb(m).get("log"), ["step_1", "step_2"])
    t.eq(_bb(m)["steps"]["test_steps"], 0)

def test_steps_resets_on_failure(t):
    w = _world(t)
    m = w.spawn(":test_ai_steps", at = ":arena")
    w.run(pulses = 1)
    w.set_attr(m, "step_2_fails", True)
    w.run(pulses = 1)
    t.eq(_bb(m)["steps"]["test_steps"], 0)
    w.run(pulses = 1)
    t.eq(_bb(m).get("log"), ["step_1", "step_2", "step_1"])

def test_run_hands_the_turn_back_on_failure(t):
    w = _world(t)

    # Failure makes ai.run return None, so the top built-in proposal (wander) runs in the think's
    # own pulse; one pulse moves the mob one room. Two would let the back-and-forth wander return
    # it to the gate.
    failing = w.spawn(":test_ai_failing", at = ":gate")
    w.run(pulses = 1)
    t.ne(failing.room.label, "//zones/tutorial:gate")

    # Success makes it return "idle", which suppresses the proposal.
    succeeding = w.spawn(":test_ai_succeeding", at = ":gate")
    w.run(pulses = 1)
    t.eq(succeeding.room.label, "//zones/tutorial:gate")

def test_blackboard_persists_and_is_saved_only_when_it_changes(t):
    w = _world(t)
    m = w.spawn(":test_ai_blackboard", at = ":arena")
    w.trace()
    w.run(pulses = 1)
    t.eq(_bb(m), {"first_cycle": True})
    t.eq(_verbs(w, "//zones/tutorial:test_ai_blackboard"), ["get_attr", "set_attr"])

    w.run(pulses = 2)
    t.eq(_bb(m), {"first_cycle": True})
    t.eq(_verbs(w, "//zones/tutorial:test_ai_blackboard"), ["get_attr", "set_attr", "get_attr", "get_attr"])

def test_goap_runs_one_step_per_cycle_from_its_cached_plan(t):
    w = _world(t)
    m = w.spawn(":test_ai_goap", at = ":arena")
    w.run(pulses = 1)
    t.eq(_bb(m)["ran"], ["act1"])
    t.eq(_bb(m)["goap"]["test_goap"], {"goal": {"at": "goal"}, "steps": ["act1", "act2"]})

    # act1's effect now holds, so the cached plan moves on to act2 without replanning.
    w.trace()
    w.run(pulses = 1)
    t.eq(_bb(m)["ran"], ["act1", "act2"])
    t.eq(_bb(m)["goap"]["test_goap"]["steps"], ["act2"])
    t.true("plan" not in _verbs(w, "//zones/tutorial:test_ai_goap"))

    # The goal holds: the leaf succeeds, drops its plan and runs nothing more.
    w.run(pulses = 2)
    t.eq(_bb(m)["ran"], ["act1", "act2"])
    t.true("test_goap" not in _bb(m).get("goap", {}))

def test_goap_keeps_its_plan_while_the_next_step_can_run(t):
    w = _world(t)
    m = w.spawn(":test_ai_goap", at = ":arena")
    w.run(pulses = 1)

    # Pushed back to the start: act1's effect no longer holds but its precondition does.
    w.set_attr(m, "sensed_at", "start")
    w.run(pulses = 1)
    t.eq(_bb(m)["ran"], ["act1", "act1"])
    t.eq(_bb(m)["goap"]["test_goap"]["steps"], ["act1", "act2"])

def test_goap_replans_when_the_next_step_cannot_run(t):
    w = _world(t)
    m = w.spawn(":test_ai_goap", at = ":arena")
    w.run(pulses = 1)
    w.set_attr(m, "sensed_at", "elsewhere")
    w.trace()
    w.run(pulses = 1)
    t.true("plan" in _verbs(w, "//zones/tutorial:test_ai_goap"))
    t.eq(_bb(m)["ran"], ["act1", "act3"])
    t.eq(_bb(m)["goap"]["test_goap"]["steps"], ["act3", "act2"])

def test_goap_replans_when_the_goal_changes(t):
    w = _world(t)
    m = w.spawn(":test_ai_goap", at = ":arena")
    w.run(pulses = 1)
    w.set_attr(m, "goal_at", "far")
    w.run(pulses = 1)
    t.eq(_bb(m)["goap"]["test_goap"], {"goal": {"at": "far"}, "steps": ["act2", "act4"]})

def test_goap_fails_when_no_plan_exists(t):
    w = _world(t)
    m = w.spawn(":test_ai_goap", at = ":arena")
    w.run(pulses = 1)
    w.set_attr(m, "sensed_at", "nowhere")
    w.run(pulses = 1)
    t.eq(_bb(m)["ran"], ["act1"])
    t.eq(_bb(m)["log"], ["fallback"])
    t.true("test_goap" not in _bb(m).get("goap", {}))

def test_construction_errors(t):
    w = _world(t)
    p = w.spawn_player("Tester", at = ":ai_probe_room")

    p.do("probe_empty")
    t.contains(w.diagnostics()[-1].message, "ai.selector: children must be a non-empty list")

    p.do("probe_deep")
    t.contains(w.diagnostics()[-1].message, "deeper than 16")

    w.spawn(":test_ai_bad_status", at = ":arena")
    w.run(pulses = 1)
    t.contains(w.diagnostics()[-1].message, "invalid status")

def _squirrel(w):
    return [m for m in w.room(":gravel").actors() if m.label == "//zones/tutorial:squirrel"][0]

def _pinecones(w, room):
    return [i for i in w.room(room).items() if i.label == "//zones/tutorial:pinecone"]

def test_squirrel_caches_a_pinecone(t):
    w = _world(t)
    sq = _squirrel(w)
    t.eq(len(_pinecones(w, ":tar_pit")), 1)
    w.run(pulses = 80)
    t.eq(sq.room.label, "//zones/tutorial:gravel")
    t.eq(len(_pinecones(w, ":gravel")), 1)
    t.eq(_pinecones(w, ":tar_pit"), [])

def test_squirrel_replans_when_the_pinecone_moves(t):
    w = _world(t)
    sq = _squirrel(w)
    p = w.spawn_player("Tess", at = ":tar_pit")
    for _ in range(20):
        if "goap" in sq.attrs.get("lib:ai", {}):
            break
        w.run(pulses = 1)
    t.eq(sq.attrs["lib:ai"]["goap"]["cache"]["steps"][-1], "drop_pinecone")

    p.do("get pinecone")
    p.do("east")
    p.do("drop pinecone")
    w.run(pulses = 120)
    t.eq(sq.room.label, "//zones/tutorial:gravel")
    t.eq(len(_pinecones(w, ":gravel")), 1)
    t.eq(_pinecones(w, ":hall"), [])

def test_squirrel_idles_with_nothing_to_fetch(t):
    w = _world(t)
    sq = _squirrel(w)
    p = w.spawn_player("Tess", at = ":tar_pit")
    p.do("get pinecone")

    # It may have set off before the pinecone was taken. Once a think sees no pinecone, no plan
    # reaches the goal, the tree fails, and the squirrel has no built-in movement.
    w.run(pulses = 8)
    room = sq.room.label
    w.run(pulses = 40)
    t.eq(sq.room.label, room)
    t.true("goap" not in sq.attrs.get("lib:ai", {}))
    t.eq(_pinecones(w, ":gravel"), [])

def test_squirrel_flees_when_hurt(t):
    w = _world(t)
    sq = _squirrel(w)
    p = w.spawn_player("Ash", at = ":gravel")
    p.do("kill squirrel")
    t.true(len(sq.fighting()) > 0)
    w.damage(sq, "hp", pct = 60)

    # The tree fails while fighting, so the built-in flee proposal runs on each think until a
    # flee roll succeeds.
    for _ in range(10):
        if sq.room.label != "//zones/tutorial:gravel":
            break
        w.run(pulses = 4)
    t.eq(sq.condition, "ok")
    t.ne(sq.room.label, "//zones/tutorial:gravel")
