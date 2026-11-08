# Skill advancement through use, for a world without character levels. A sample: copy it and
# tune POINTS, USE_WINDOW_PULSES and _points_needed to your world.
#
# Every routine a player invokes reports a use (on_skill_use). A counted use adds POINTS for its
# outcome toward the skill's next point; at most one use per skill counts per USE_WINDOW_PULSES,
# so spamming a cheap routine in a safe room gains little. Raising a skill goes through
# ctx.set_skill, so learn restrictions still cap it. Scripts report uses the engine can't see
# (a skill only rules read) with ctx.report_skill_use.

POINTS = {"fail": 3, "resist": 2, "success": 2, "report": 2}
USE_WINDOW_PULSES = 40

def _points_needed(skill, prof):
    # Points to go from prof to prof + 1. Edit this to reshape advancement;
    # `skill` is the skill view, so tags or kind can make some skills harder.
    return 1 + prof * prof // 50

def _on_skill_use(ctx):
    a = ctx.actor
    if not a.is_player:
        return
    label = ctx.skill.label
    prof = a.skills.get(label)
    if prof == None or prof >= 100:
        return
    nexts = ctx.get_attr(a, "skill_gain_next", {})
    if nexts.get(label, 0) > ctx.pulse:
        return   # this window's counted use already happened
    nexts[label] = ctx.pulse + USE_WINDOW_PULSES
    ctx.set_attr(a, "skill_gain_next", nexts)
    progress = ctx.get_attr(a, "skill_progress", {})
    points = progress.get(label, 0) + POINTS.get(ctx.outcome, 0)
    # Each raise spends at least one point and raises prof, so at most 100 iterations.
    for _ in range(100):
        if prof >= 100:
            break
        cost = _points_needed(ctx.skill, prof)
        if points < cost:
            break
        refusal = ctx.set_skill(a, label, prof + 1)
        if refusal != None:
            break   # a learn restriction caps the skill; keep the points
        points -= cost
        prof += 1
        ctx.send(a, "You feel more skilled at %s. (%d%%)" % (ctx.skill.name, prof))
    progress[label] = points
    ctx.set_attr(a, "skill_progress", progress)

global_hook("on_skill_use", _on_skill_use)
