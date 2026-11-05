# A trainer: a keeper that teaches skills for practice sessions. Any zone can run one:
#
#     load("//lib:trainer.star", "trainer")
#
#     MENTOR = trainer.define(id = "mentor",
#                             teaches = [trainer.teach("//skills/arcane:spark", up_to = 50, gain = 10)])
#
#     def _on_spawn(ctx):
#         trainer.open(ctx, ctx.self, MENTOR)   # from the keeper's on_spawn
#
# Players type `practice <skill>` to spend a session raising the skill, and `practice` alone to
# see their balance and what the trainer here teaches. Practice sessions come from changing level:
# every level gained grants PRACTICES_PER_LEVEL and every level lost takes them back, through a
# global on_level_change hook, so the balance can go negative and dying can't farm points. A
# keeper that is incapacitated, stunned or fighting doesn't teach. The keeper's custom hook
# `practice_offer` may change the gain or cost or refuse a session.

PRACTICES_PER_LEVEL = 2

def _absolute(label, what):
    """A skill label the library can set: an absolute //package:skill."""
    if type(label) != "string":
        fail("%s: %r is not a label string" % (what, label))
    if label.startswith(":"):
        fail("%s: %s is relative; write the full label, for example //skills/arcane:spark" % (what, label))
    if not label.startswith("//"):
        fail("%s: %s is not an absolute label starting with //" % (what, label))
    return label

def _check_id(id):
    """A trainer id: [a-z0-9_]+, unique per world."""
    if not id:
        fail("define(id): must not be empty")
    for ch in id.elems():
        if ch not in "abcdefghijklmnopqrstuvwxyz0123456789_":
            fail("define(id): %r is not made of [a-z0-9_]" % id)
    return id

def _teach(label, up_to, gain, start = None):
    """One skill the trainer teaches: the line's fields and their checks."""
    label = _absolute(label, "teach(label)")
    if up_to < 1 or up_to > 100:
        fail("teach(up_to): must be 1 to 100, got %d" % up_to)
    if gain < 1:
        fail("teach(gain): must be at least 1, got %d" % gain)
    if start != None and (start < 1 or start > up_to):
        fail("teach(start): must be 1 to up_to (%d), got %d" % (up_to, start))
    # 0 stands for "no start": attributes can't hold None in a dict.
    return {"label": label, "up_to": up_to, "gain": gain, "start": start if start != None else 0}

def _define(id, teaches):
    """A trainer description. Keep it in a module constant and pass it to trainer.open."""
    _check_id(id)
    if type(teaches) != "list":
        fail("define(teaches): must be a list of trainer.teach lines")
    lines = []
    for line in teaches:
        if type(line) != "dict" or line.get("label") == None:
            fail("define(teaches): each line must be a trainer.teach line with a label")
        lines.append(line)
    return {"id": id, "teaches": lines}

def _open(ctx, keeper, definition):
    """Marks keeper as a trainer that teaches definition. Called from the keeper's on_spawn."""
    ctx.set_attr(keeper, "trainer", definition)

def _practices(ctx, actor):
    """The actor's practice balance, which may be negative."""
    return ctx.get_attr(actor, "practices", 0)

def _grant_practices(ctx, actor, n):
    """Adds n (which may be negative) to the actor's balance and returns the new total."""
    balance = _practices(ctx, actor) + n
    ctx.set_attr(actor, "practices", balance)
    return balance

trainer = struct(define = _define, teach = _teach, open = _open,
                 practices = _practices, grant_practices = _grant_practices)

# --- The global level hook and the practice command ------------------------------------------

def _on_level_change(ctx):
    # Fires for every actor's level step: grants or takes back the sessions for the change.
    _grant_practices(ctx, ctx.actor, (ctx.new_level - ctx.old_level) * PRACTICES_PER_LEVEL)

def _trainer_at(ctx):
    """The serving keeper and its definition in the player's room, or None. No message."""
    for a in ctx.self.room.actors():
        if a.is_player:
            continue
        if ctx.get_attr(a, "trainer") != None:
            return (a, ctx.get_attr(a, "trainer"))
    return None

def _trainer_here(ctx):
    """The serving keeper and its definition, or None with a refusal sent to the player."""
    found = _trainer_at(ctx)
    if found == None:
        ctx.send(ctx.self, "There's no trainer here.")
        return None
    keeper = found[0]
    if keeper.condition != "ok" or keeper.has_flag("stunned"):
        ctx.send(ctx.self, "%s can't teach right now." % keeper.name)
        return None
    # fighting() is an error in a world without the combat feature.
    if ctx.has_feature("combat") and keeper.fighting():
        ctx.send(ctx.self, "%s is busy fighting." % keeper.name)
        return None
    return found

def _interpret_offer(r, gain, cost):
    """The keeper's practice_offer return: (gain, cost, refusal), the default kept on None."""
    if type(r) == "string":
        return (gain, cost, r)
    if type(r) == "dict":
        if "gain" in r:
            g = r["gain"]
            if type(g) == "int" and g >= 1:
                gain = g
            else:
                print("practice_offer gain %r is not an int >= 1; ignored" % g)
        if "cost" in r:
            c = r["cost"]
            if type(c) == "int" and c >= 0:
                cost = c
            else:
                print("practice_offer cost %r is not an int >= 0; ignored" % c)
        return (gain, cost, None)
    if r != None:
        print("practice_offer returned %r; ignored" % r)
    return (gain, cost, None)

def _match_line(ctx, definition, phrase):
    """The teaches line whose skill name phrase prefixes, or None."""
    phrase = phrase.lower()
    for line in definition["teaches"]:
        info = ctx.skill_info(line["label"])
        if info == None:
            continue   # an unloaded skill is neither listed nor matched
        if info.name.lower().startswith(phrase):
            return line
    return None

def _show_balance(ctx, balance):
    if balance >= 0:
        ctx.send(ctx.self, "You have %d practice sessions." % balance)
    else:
        ctx.send(ctx.self, "You owe %d practice sessions." % -balance)

def _list_teaches(ctx, keeper, definition):
    """The skills the trainer teaches, with the player's current proficiency and the cap."""
    lines = ["%s teaches:" % keeper.name]
    for line in definition["teaches"]:
        info = ctx.skill_info(line["label"])
        if info == None:
            print("trainer %s teaches %s, which is not loaded" % (keeper.name, line["label"]))
            continue
        current = ctx.self.skills.get(line["label"])
        learned = "%d%%" % current if current != None else "not learned"
        lines.append("  %s: %s, up to %d%%" % (info.name, learned, line["up_to"]))
    ctx.send(ctx.self, "\n".join(lines))

def _do_practice(ctx, keeper, definition, line, balance):
    label = line["label"]
    name = ctx.skill_info(label).name
    current = ctx.self.skills.get(label)
    up_to = min(line["up_to"], 100)
    if current != None and current >= up_to:
        ctx.send(ctx.self, "You've learned all %s can teach you about %s." % (keeper.name, name))
        return
    # A skill the player doesn't know starts at the line's start, falling back to its gain.
    gain = line["start"] if (line["start"] > 0 and current == None) else line["gain"]
    cost = 1
    r = ctx.call_hook(keeper, "practice_offer", customer = ctx.self, skill = label,
                      current = current, gain = gain, cost = cost, refusal = None)
    gain, cost, refusal = _interpret_offer(r, gain, cost)
    if refusal != None:
        ctx.send(ctx.self, refusal)
        return
    if cost >= 1 and balance < cost:
        if balance >= 0:
            ctx.send(ctx.self, "You need %d practice sessions; you have %d." % (cost, balance))
        else:
            ctx.send(ctx.self, "You need %d practice sessions; you owe %d." % (cost, -balance))
        return
    new = min((current if current != None else 0) + gain, up_to, 100)
    refusal = ctx.set_skill(ctx.self, label, new)
    if refusal != None:
        ctx.send(ctx.self, refusal)
        return   # a refused session spends nothing
    _grant_practices(ctx, ctx.self, -cost)
    if current == None:
        ctx.send(ctx.self, "You learn %s from %s. (%d%%)" % (name, keeper.name, new))
    else:
        ctx.send(ctx.self, "You practice %s with %s. (%d%% → %d%%)" % (name, keeper.name, current, new))
    ctx.echo(ctx.self.room, "%s practices with %s." % (ctx.self.name, keeper.name), exclude = [ctx.self])

def _practice_command(ctx):
    if not ctx.has_feature("skills"):
        ctx.send(ctx.self, "There's nothing to practice here.")
        return
    balance = _practices(ctx, ctx.self)
    if ctx.args == "":
        _show_balance(ctx, balance)
        found = _trainer_at(ctx)
        if found != None:
            _list_teaches(ctx, found[0], found[1])
        return
    found = _trainer_here(ctx)
    if found == None:
        return
    keeper, definition = found
    line = _match_line(ctx, definition, ctx.args)
    if line == None:
        ctx.send(ctx.self, "%s can't teach you that." % keeper.name)
        return
    _do_practice(ctx, keeper, definition, line, balance)

global_hook("on_level_change", _on_level_change)
global_command("practice", _practice_command, lane = "tick")
