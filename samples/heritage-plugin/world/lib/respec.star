# A respec shrine: a keeper who changes a character's heritage choices for coins. It needs the
# heritage plugin, whose builtins decide what may be picked and apply the change; this file owns
# the world's policy: which choices a shrine changes, the price, the cooldown and what happens to
# skills. Any zone can run one:
#
#     load("//lib:respec.star", "respec")
#
#     MENTOR = respec.define(id = "mentor", choices = ["class"], cost = 10)
#
#     def _on_spawn(ctx):
#         respec.open(ctx, ctx.self, MENTOR)   # from the keeper's on_spawn
#
# Players type `respec` to see what the keeper here can change and at what price, and
# `respec <option>` (or `respec <choice> <option>`) to change. Every define() parameter but id
# and choices falls back to a DEFAULT_* constant below, so editing those sets the world's
# defaults. One cooldown covers every shrine, so players can't hop between keepers. A keeper
# that is incapacitated, stunned or fighting doesn't serve.
#
# Skill policies: "keep" leaves every skill (heritage's cast and learn restrictions already make
# off-class skills unusable) and refuses the current option. "reset" gives back the practice
# sessions //lib:trainer.star recorded, forgets every skill and allows the current option, a
# fresh start in the same class. Both end with the class's starter skills.
#
# After a change, in a world with the standing feature, the library raises the world standing
# event "respec" on the keeper with amount = the coins paid. World events have no default deltas,
# so nothing changes unless the world's standing_change handles it (as it might "donation").
#
# The keeper's custom hook:
#
#   respec_offer(ctx, action, customer, choice, old, new, cost, currency, cooldown_pulses, skills)
#       Asked for each option the menu lists that the plugin leaves open (action = "list"), and once
#       per request
#       (action = "respec"). old and new are option ids; old is None when the choice is unset.
#       Return a string to refuse, a dict overriding any of cost, cooldown_pulses and skills, or
#       None to keep the terms. A cooldown_pulses it returns sets the wait after this change.
#
#   respec_done(ctx, customer, choice, old, new, skills, cost)
#       Runs after a change has been made and paid for, with the terms that applied. Its return
#       value is ignored. Class-specific effects, such as standing, belong here.

load("//lib:trainer.star", "trainer")

DEFAULT_SKILLS = "keep"          # "keep" or "reset"
DEFAULT_COST = 0
DEFAULT_CURRENCY = "//lib:gold_coin"
DEFAULT_COOLDOWN_PULSES = 0

PULSES_PER_MINUTE = 240

_POLICIES = ["keep", "reset"]

def _check_id(id):
    """A shrine id: [a-z][a-z0-9_]*."""
    if type(id) != "string" or not id:
        fail("define(id): must be a non-empty string")
    if id[0] not in "abcdefghijklmnopqrstuvwxyz":
        fail("define(id): %r must start with a letter a-z" % id)
    for ch in id.elems():
        if ch not in "abcdefghijklmnopqrstuvwxyz0123456789_":
            fail("define(id): %r is not made of [a-z0-9_]" % id)
    return id

def _check_count(value, what):
    if type(value) != "int" or value < 0:
        fail("define(%s): must be an int >= 0, got %r" % (what, value))
    return value

def _check_choices(choices):
    if type(choices) != "list" or not choices:
        fail("define(choices): must be a non-empty list of choice ids")
    for c in choices:
        if type(c) != "string":
            fail("define(choices): %r is not a choice id string" % c)
    return choices

def _check_policy(skills):
    if skills not in _POLICIES:
        fail("define(skills): must be \"keep\" or \"reset\", got %r" % skills)
    return skills

def _check_currency(label):
    if type(label) != "string" or not label.startswith("//"):
        fail("define(currency): %r is not an absolute label starting with //" % label)
    return label

def _or(value, default):
    return default if value == None else value

def _define(id, choices, skills = None, cost = None, currency = None, cooldown_pulses = None):
    """A shrine definition dict; None takes the DEFAULT_* constant."""
    return {
        "id": _check_id(id),
        "choices": _check_choices(choices),
        "skills": _check_policy(_or(skills, DEFAULT_SKILLS)),
        "cost": _check_count(_or(cost, DEFAULT_COST), "cost"),
        "currency": _check_currency(_or(currency, DEFAULT_CURRENCY)),
        "cooldown_pulses": _check_count(_or(cooldown_pulses, DEFAULT_COOLDOWN_PULSES),
                                        "cooldown_pulses"),
    }

def _open(ctx, keeper, definition):
    """Marks keeper as serving definition. Called from the keeper's on_spawn."""
    ctx.set_attr(keeper, "respec", definition)

def _ready_at(ctx, actor):
    """The pulse from which actor may change again (0 when never changed)."""
    return ctx.get_attr(actor, "respec_ready_at", 0)

respec = struct(define = _define, open = _open, ready_at = _ready_at)

# --- The respec command -----------------------------------------------------------------------

def _keeper_at(ctx):
    """The serving keeper and its definition in the player's room, or None. No message."""
    for a in ctx.self.room.actors():
        if a.is_player:
            continue
        d = ctx.get_attr(a, "respec")
        if d != None:
            return (a, d)
    return None

def _keeper_here(ctx):
    """The serving keeper and its definition, or None with a refusal sent to the player."""
    found = _keeper_at(ctx)
    if found == None:
        ctx.send(ctx.self, "There's no one here who can change you.")
        return None
    keeper = found[0]
    if keeper.condition != "ok" or keeper.has_flag("stunned"):
        ctx.send(ctx.self, "%s can't help you right now." % keeper.name)
        return None
    # fighting() is an error in a world without the combat feature.
    if ctx.has_feature("combat") and keeper.fighting():
        ctx.send(ctx.self, "%s is busy fighting." % keeper.name)
        return None
    return found

def _terms(d):
    return {"cost": d["cost"], "cooldown_pulses": d["cooldown_pulses"], "skills": d["skills"]}

def _interpret_offer(r, terms):
    """The keeper's respec_offer return: (terms, refusal), the terms kept on None."""
    if type(r) == "string":
        return (terms, r)
    if type(r) == "dict":
        terms = dict(terms)
        for key in ["cost", "cooldown_pulses"]:
            if key in r:
                v = r[key]
                if type(v) == "int" and v >= 0:
                    terms[key] = v
                else:
                    print("respec_offer %s %r is not an int >= 0; ignored" % (key, v))
        if "skills" in r:
            v = r["skills"]
            if v in _POLICIES:
                terms["skills"] = v
            else:
                print("respec_offer skills %r is not \"keep\" or \"reset\"; ignored" % v)
        return (terms, None)
    if r != None:
        print("respec_offer returned %r; ignored" % r)
    return (terms, None)

def _ask(ctx, keeper, d, action, change):
    """Runs respec_offer for change = (choice, old, new); returns (terms, refusal)."""
    terms = _terms(d)
    r = ctx.call_hook(keeper, "respec_offer", action = action, customer = ctx.self,
                      choice = change[0], old = change[1], new = change[2], cost = terms["cost"],
                      currency = d["currency"], cooldown_pulses = terms["cooldown_pulses"],
                      skills = terms["skills"])
    return _interpret_offer(r, terms)

def _price(ctx, cost, currency):
    """What a change costs, in words: "free", or the count and the currency's plural."""
    if cost == 0:
        return "free"
    info = ctx.item_info(currency)
    if info == None:
        word = currency
    else:
        word = info.plural if info.plural != None else info.name
    return "%d %s" % (cost, word)

def _minutes(pulses):
    return (pulses + PULSES_PER_MINUTE - 1) // PULSES_PER_MINUTE

def _coins_in(actor, currency):
    total = 0
    for i in actor.inventory():
        if i.label == currency:
            total += i.count
    return total

def _pay(ctx, keeper, currency, cost):
    need = cost
    for i in ctx.self.inventory():
        if i.label != currency or need <= 0:
            continue
        n = min(i.count, need)
        ctx.merge_stack(ctx.move_item(i, keeper, count = n))
        need -= n

def _option_line(ctx, keeper, d, entry, option):
    refusal = option["refusal"]
    if refusal == None:
        terms, refusal = _ask(ctx, keeper, d, "list", (entry["choice"], entry["current"], option["id"]))
    if refusal != None:
        return "  %s: %s (%s)" % (option["label"], option["summary"], refusal)
    return "  %s - %s: %s" % (option["label"], _price(ctx, terms["cost"], d["currency"]),
                              option["summary"])

def _current_label(entry):
    for o in entry["options"]:
        if o["id"] == entry["current"]:
            return o["label"]
    return "none"

def _show_menu(ctx, keeper, d):
    lines = []
    for entry in heritage.respec_menu(ctx.self, d["choices"], d["skills"] == "reset"):
        lines.append("%s can change your %s (now %s):" % (keeper.name, entry["title"].lower(),
                                                          _current_label(entry)))
        for option in entry["options"]:
            lines.append(_option_line(ctx, keeper, d, entry, option))
    remaining = _ready_at(ctx, ctx.self) - ctx.pulse
    if remaining > 0:
        lines.append("You can change again in %d minutes." % _minutes(remaining))
    else:
        lines.append("You can change now.")
    ctx.send(ctx.self, "\n".join(lines))

def _title(ctx, choice):
    return heritage.respec_menu(ctx.self, [choice], True)[0]["title"].lower()

def _refusal(ctx, keeper, d, c, old):
    """Every check before anything changes: (terms, refusal)."""
    terms, refusal = _ask(ctx, keeper, d, "respec", (c["choice"], old, c["option"]))
    if refusal != None:
        return (terms, refusal)
    remaining = _ready_at(ctx, ctx.self) - ctx.pulse
    if remaining > 0:
        return (terms, "You must wait %d more minutes before changing again." % _minutes(remaining))
    # A hook that switched a "reset" shrine to "keep" also takes back the same-option pick.
    if terms["skills"] == "keep" and c["option"] == old:
        return (terms, "That is already your %s." % _title(ctx, c["choice"]))
    if _coins_in(ctx.self, d["currency"]) < terms["cost"]:
        return (terms, "You can't afford that.")
    return (terms, None)

def _apply_skill_policy(ctx, terms):
    """Under "reset", refunds and forgets every skill; returns the lines to tell the player."""
    if terms["skills"] != "reset":
        return []
    refunded = trainer.refund(ctx, ctx.self)
    for label in list(ctx.self.skills):
        ctx.remove_skill(ctx.self, label)
    lines = ["You forget everything you had learned."]
    if refunded > 0:
        lines.append("%d practice sessions are returned to you." % refunded)
    return lines

def _finish(ctx, keeper, c, old, terms):
    """What follows a paid change: the standing event, then the keeper's respec_done hook."""
    if ctx.has_feature("standing"):
        ctx.standing_event(keeper, ctx.self, "respec", amount = terms["cost"])
    ctx.call_hook(keeper, "respec_done", customer = ctx.self, choice = c["choice"], old = old,
                  new = c["option"], skills = terms["skills"], cost = terms["cost"])

def _request(ctx, keeper, d):
    c = heritage.respec_check(ctx.self, ctx.args, d["choices"], d["skills"] == "reset")
    if c["refusal"] != None:
        ctx.send(ctx.self, c["refusal"])
        return
    old = ctx.self.choices.get(c["choice"])
    terms, refusal = _refusal(ctx, keeper, d, c, old)
    if refusal != None:
        ctx.send(ctx.self, refusal)
        return
    # Coins are counted above and taken only once the change has gone through, so a change the
    # engine refuses needs no refund.
    err = heritage.respec(ctx.self, c["choice"], c["option"], terms["skills"] == "reset")
    if err != None:
        ctx.send(ctx.self, err)
        return
    forgot = _apply_skill_policy(ctx, terms)
    granted = heritage.starter_skills(ctx.self)
    _pay(ctx, keeper, d["currency"], terms["cost"])
    ctx.set_attr(ctx.self, "respec_ready_at", ctx.pulse + terms["cooldown_pulses"])
    lines = []
    if terms["cost"] > 0:
        lines.append("You pay %s." % _price(ctx, terms["cost"], d["currency"]))
    lines.append("Your %s is now %s." % (_title(ctx, c["choice"]), c["label"]))
    lines.extend(forgot)
    for label in granted:
        lines.append("You learn %s." % ctx.skill_info(label).name)
    ctx.send(ctx.self, "\n".join(lines))
    ctx.echo(ctx.self.room, "%s is changed by %s." % (ctx.self.name, keeper.name),
             exclude = [ctx.self])
    _finish(ctx, keeper, c, old, terms)

def _respec_command(ctx):
    found = _keeper_here(ctx)
    if found == None:
        return
    keeper, d = found
    if ctx.args == "":
        _show_menu(ctx, keeper, d)
    else:
        _request(ctx, keeper, d)

global_command("respec", _respec_command, lane = "tick")
