# Quests: a small shared library any zone can use.
#
#     load("//lib:quest.star", "quest")
#
#     FIND_KEY = quest.define("find_key", "The Lost Key", [
#         "Ask the guide about the key.",
#         "Bring the key back to the gate.",
#     ])
#
#     def _on_say(ctx):
#         quest.start(ctx, ctx.actor, FIND_KEY)       # shows "New quest: The Lost Key"
#         ...
#         quest.advance(ctx, ctx.actor, FIND_KEY)     # on to the next step; past the last, done
#
# Players type `quests` to see theirs. Progress lives in each player's attributes, which this
# library writes in its own "lib" namespace: other packages read it only through these helpers.

def _define(id, title, steps):
    """A quest description: a unique id, a title, and the text of each step in order."""
    if not steps:
        steps = ["Complete the quest."]
    return {"id": id, "title": title, "steps": list(steps)}

def _all(ctx, actor):
    return ctx.get_attr(actor, "quests", {})

def _save(ctx, actor, quests):
    ctx.set_attr(actor, "quests", quests)

def _progress(ctx, actor, q):
    """This actor's record of quest q, or None if they never started it."""
    return _all(ctx, actor).get(q["id"])

def _start(ctx, actor, q):
    """Starts q for actor at its first step. False if they already have it (or finished it)."""
    quests = _all(ctx, actor)
    if q["id"] in quests:
        return False
    quests[q["id"]] = {"title": q["title"], "steps": q["steps"], "step": 0, "done": False}
    _save(ctx, actor, quests)
    ctx.send(actor, "New quest: %s. %s" % (q["title"], q["steps"][0]))
    return True

def _step(ctx, actor, q):
    """The index of actor's current step of q: None if not started, len(steps) once done."""
    p = _progress(ctx, actor, q)
    if p == None:
        return None
    return len(p["steps"]) if p["done"] else p["step"]

def _is_active(ctx, actor, q):
    p = _progress(ctx, actor, q)
    return p != None and not p["done"]

def _is_done(ctx, actor, q):
    p = _progress(ctx, actor, q)
    return p != None and p["done"]

def _complete(ctx, actor, q):
    """Finishes q for actor, wherever they are in it. False if it isn't active."""
    quests = _all(ctx, actor)
    p = quests.get(q["id"])
    if p == None or p["done"]:
        return False
    p["done"] = True
    _save(ctx, actor, quests)
    ctx.send(actor, "Quest complete: %s!" % q["title"])
    return True

def _advance(ctx, actor, q, from_step = None):
    """
    Moves actor to the next step of q, completing it after the last. With from_step, only
    advances if actor is on step from_step, so a hook can't skip steps. Returns whether anything changed.
    """
    quests = _all(ctx, actor)
    p = quests.get(q["id"])
    if p == None or p["done"] or (from_step != None and p["step"] != from_step):
        return False
    if p["step"] + 1 >= len(p["steps"]):
        return _complete(ctx, actor, q)
    p["step"] += 1
    _save(ctx, actor, quests)
    ctx.send(actor, "Quest updated: %s. %s" % (q["title"], p["steps"][p["step"]]))
    return True

quest = struct(
    define = _define,
    start = _start,
    advance = _advance,
    complete = _complete,
    step = _step,
    is_active = _is_active,
    is_done = _is_done,
)

def _quests_command(ctx):
    # A global command: ctx.self is the player who typed `quests`.
    quests = _all(ctx, ctx.self)
    if not quests:
        ctx.send(ctx.self, "You have no quests.")
        return
    lines = ["Your quests:"]
    for id in sorted(quests.keys()):
        p = quests[id]
        if p["done"]:
            lines.append("  %s (complete)" % p["title"])
        else:
            lines.append("  %s: %s" % (p["title"], p["steps"][p["step"]]))
    ctx.send(ctx.self, "\n".join(lines))

global_command("quests", _quests_command, lane = "fast")
