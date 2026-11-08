# A sample target_order for behaviors that carry `prefer` (aggressive, assist, retarget). The engine gives
# `prefer` no meaning; this file reads it as a strategy name, a list of them, or a dict of settings.
#
#   prefer = "weakest"                                lowest hit points as a share of maximum
#   prefer = "strongest"                              highest share
#   prefer = "hated"                                  lowest disposition
#   prefer = "threat"                                 most threat from the mob's combat memory
#   prefer = ["weakest", "hated"]                     each later name breaks the earlier ties
#   prefer = {"order": "threat", "delay": 12, ...}    see _settings
#
# It is a sample for worlds to tune: copy it and change the numbers, or the whole policy.

# How many pulses it takes a memory's threat to fade to nothing (30 seconds).
THREAT_PULSES = 120

# What each remembered event is worth per point of amount (or per occurrence when it has no amount).
DEFAULT_WEIGHTS = {"attack": 10, "damage": 1, "cast": 20, "cast_start": 5, "heal": 1, "help": 10, "kill": 50}

# The percent by which a challenger's threat must beat the current target's before retarget switches.
DEFAULT_SWITCH_MARGIN = 25

# Pulses retarget keeps a new target before it may switch again.
DEFAULT_MIN_DWELL = 8

def target_order(ctx):
    s = _settings(ctx.prefer, ctx.behavior)
    order = s["order"]
    delay = s["delay"]
    entries = []
    for i, c in enumerate(ctx.candidates):
        # A delay spares newcomers; it never applies to opponents retarget is already fighting.
        if delay > 0 and ctx.behavior != "retarget":
            arrived = _arrived(ctx, c)
            if arrived != None and ctx.pulse - arrived < delay:
                continue
        key = []
        for name in order:
            if name == "weakest":
                key.append(c.hp * 100 // max(1, c.max_hp))
            elif name == "strongest":
                key.append(-(c.hp * 100 // max(1, c.max_hp)))
            elif name == "hated":
                key.append(ctx.dispositions[i] if ctx.dispositions != None else 0)
            elif name == "threat":
                key.append(-_threat(ctx, c, s["weights"]))
        key.append(c.id)
        entries.append((key, c))
    if not entries:
        return None
    best = sorted(entries)[0][1]
    current = ctx.current
    if ctx.behavior == "retarget" and current != None and current in ctx.candidates:
        if ctx.pulse - ctx.current_since < s["min_dwell"]:
            return current
        if "threat" in order and _threat(ctx, best, s["weights"]) * 100 <= _threat(ctx, current, s["weights"]) * (100 + s["switch_margin"]):
            return current
    return best

def _settings(prefer, behavior):
    default_order = ["threat"] if behavior == "retarget" else []
    if type(prefer) == "string":
        return _result([prefer], {}, 0, DEFAULT_SWITCH_MARGIN, DEFAULT_MIN_DWELL)
    if type(prefer) == "list":
        return _result(prefer, {}, 0, DEFAULT_SWITCH_MARGIN, DEFAULT_MIN_DWELL)
    if type(prefer) == "dict":
        order = prefer.get("order", default_order)
        if type(order) == "string":
            order = [order]
        return _result(
            order,
            prefer.get("weights", {}),
            prefer.get("delay", 0),
            prefer.get("switch_margin", DEFAULT_SWITCH_MARGIN),
            prefer.get("min_dwell", DEFAULT_MIN_DWELL),
        )
    fail("target_order: prefer must be a name, a list of names or a dict, got " + type(prefer))

def _result(order, weights, delay, switch_margin, min_dwell):
    merged = dict(DEFAULT_WEIGHTS)
    merged.update(weights)
    return {"order": order, "weights": merged, "delay": delay, "switch_margin": switch_margin, "min_dwell": min_dwell}

def _threat(ctx, actor, weights):
    total = 0
    for m in ctx.memories(subject = actor):
        w = weights.get(m.event, 0)
        if w == 0:
            continue
        base = w * (m.amount if m.amount > 0 else m.count)
        age = ctx.pulse - m.pulse
        total += base * max(0, THREAT_PULSES - age) // THREAT_PULSES
    return total

def _arrived(ctx, actor):
    # The first enter the mob remembers of the actor, so stepping out and back in doesn't reset a
    # delay, or the mob's own latest arrival if that is later.
    found = []
    entered = ctx.memories(subject = actor, event = "enter")
    if entered:
        found.append(entered[0].first_pulse)
    arrive = ctx.memories(subject = ctx.actor, event = "arrive")
    if arrive:
        found.append(arrive[0].pulse)
    return max(found) if found else None
