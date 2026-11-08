# Tests for //lib:skill_gain.star: deterministic advancement through use. The fixtures in
# skill_gain_testdata.star stand in for a second chip, a casting bot and a report-only skill.

def test_a_chip_installs_self_repair_once(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate")
    p.do("get chip")
    t.contains(p.do("use chip"), "The chip installs self_repair. (1%)")
    t.eq(p.skills["//zones/foundry:self_repair"], 1)
    w.spawn(":repair_chip", at = ":gate")
    p.do("get chip")
    t.contains(p.do("use chip"), "You already run that routine.")
    t.eq(p.skills["//zones/foundry:self_repair"], 1)

def test_an_invoke_at_one_raises_to_three_with_no_points_left(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate", skills = {":self_repair": 1})
    w.force_rng(ints = [1, 1])   # the check passes; the heal rolls 1
    out = p.do("invoke self_repair")
    t.contains(out, "You feel more skilled at self_repair. (2%)")
    t.contains(out, "You feel more skilled at self_repair. (3%)")
    t.eq(p.skills["//zones/foundry:self_repair"], 3)

def test_one_counted_use_per_skill_per_window(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate", skills = {":self_repair": 1})
    w.force_rng(ints = [1, 1, 1, 1, 1, 1])   # each invoke draws a check and a heal
    p.do("invoke self_repair")
    t.eq(p.skills["//zones/foundry:self_repair"], 3)
    p.do("invoke self_repair")   # inside the window: not counted
    t.eq(p.skills["//zones/foundry:self_repair"], 3)
    w.run(pulses = 40)
    p.do("invoke self_repair")   # the next window: counted, two more points
    t.eq(p.skills["//zones/foundry:self_repair"], 5)

def test_a_skilled_robot_reaches_fifty_one_after_twenty_six_counted_uses(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate", skills = {":self_repair": 50})
    for use in range(1, 27):
        if use > 1:
            w.run(pulses = 40)
        w.force_rng(ints = [1, 1])
        p.do("invoke self_repair")
        if use < 26:
            t.eq(p.skills["//zones/foundry:self_repair"], 50, "after %d counted uses" % use)
    t.eq(p.skills["//zones/foundry:self_repair"], 51)

def test_a_failed_invoke_adds_three_points(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate", skills = {":self_repair": 1})
    w.force_rng(ints = [100])   # a 100 is above any chance below 100, so the check fails
    out = p.do("invoke self_repair")
    t.contains(out, "Your self_repair routine crashes.")
    # Three points, not two: a fail pays three raises of cost 1, to prof 4.
    t.contains(out, "You feel more skilled at self_repair. (4%)")
    t.eq(p.skills["//zones/foundry:self_repair"], 4)

def test_a_mob_invoke_changes_nothing(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate", skills = {":self_repair": 1})
    bot = w.spawn(":test_repair_bot", at = ":gate")
    w.force_rng(ints = [1, 1])
    p.do("say anything")   # the bot's on_say invokes self_repair
    w.run(pulses = 2)
    t.contains(p.output(), "A test repair bot invokes self_repair.")
    t.eq(bot.skills["//zones/foundry:self_repair"], 1)
    t.eq(p.skills["//zones/foundry:self_repair"], 1)

def test_reporting_an_unknown_skill_teaches_nothing(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate")   # knows no skills
    w.spawn(":test_reporter", at = ":gate")
    p.do("get reporter")
    out = p.do("use reporter")
    t.eq(w.diagnostics(), [])   # the report ran without an error
    t.false("//zones/foundry:overload" in p.skills)
    t.false("more skilled" in out)

def test_advancement_stops_at_one_hundred(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Ann", at = ":gate", skills = {":self_repair": 100})
    w.force_rng(ints = [1, 1])
    out = p.do("invoke self_repair")
    t.eq(p.skills["//zones/foundry:self_repair"], 100)
    t.false("more skilled" in out)
