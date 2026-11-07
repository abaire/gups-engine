# Course rules: how standing events change standing records.
#
# Standing records run from -100 (hatred) to 100 (ally). For each standing event (an attack,
# a kill, a heal, a gift, or a world event such as a theft) the engine calls standing_change,
# which returns the changes to make. ctx.actor is the actor affected and ctx.target the actor
# who acted. The engine caps nothing itself, so the gains below are limited here: gratitude
# never lifts a mob's personal record above GRATITUDE_CEILING, and no one gains more than
# RATE_CAP with one mob, or with a faction through one mob, in a STANDING_WINDOW.

# Matches the world's standing_history_pulses; history older than that is gone anyway.
STANDING_WINDOW = 14400
RATE_CAP = 10
GRATITUDE_CEILING = 25

def _personal_gain(ctx, mob, subject, amount):
    # Only gratitude counts toward the rate cap; a quest reward or a gift doesn't use it up.
    since = ctx.pulse - STANDING_WINDOW
    recent = 0
    for event in ("heal", "help"):
        recent += ctx.standing_history(mob, subject, since = since, event = event).gained
    headroom = GRATITUDE_CEILING - ctx.standing(mob, subject)
    return min(amount, headroom, RATE_CAP - recent)

def _faction_gain(ctx, faction, subject, amount):
    recent = ctx.standing_history(
        faction,
        subject,
        since = ctx.pulse - STANDING_WINDOW,
        via = ctx.actor,
    ).gained
    return min(amount, RATE_CAP - recent)

def standing_change(ctx):
    # The defaults, with personal changes only for a mob: a player keeps no personal records.
    if ctx.event == "attack":
        if ctx.actor.is_player:
            return []
        return [standing_delta(ctx.actor, ctx.target, -50)]

    if ctx.event == "kill":
        deltas = []
        killer_factions = ctx.factions_of(ctx.target)
        for f in ctx.factions_of(ctx.actor):
            deltas.append(standing_delta(f, ctx.target, -50))
            for g in killer_factions:
                deltas.append(standing_delta(f, g, -5))
        return deltas

    if ctx.event == "assist":
        if ctx.actor.is_player:
            return []
        return [standing_delta(ctx.actor, ctx.target, -10)]

    if ctx.event in ("heal", "help"):
        if ctx.actor.is_player:
            return []
        gain = _personal_gain(ctx, ctx.actor, ctx.target, 5)
        return [standing_delta(ctx.actor, ctx.target, gain)] if gain > 0 else []

    # World events, raised with ctx.standing_event(...).
    if ctx.event == "theft":
        deltas = []
        if not ctx.actor.is_player:
            deltas.append(standing_delta(ctx.actor, ctx.target, -25))
        for f in ctx.factions_of(ctx.actor):
            deltas.append(standing_delta(f, ctx.target, -25))
        return deltas

    if ctx.event == "donation":
        # One point with each of the mob's factions per 10 coins, rate-capped per mob so a donor
        # can't buy a faction's favor by giving coins to the same mob over and over.
        deltas = []
        if ctx.actor.is_player:
            return deltas
        for f in ctx.factions_of(ctx.actor):
            gain = _faction_gain(ctx, f, ctx.target, ctx.amount // 10)
            if gain > 0:
                deltas.append(standing_delta(f, ctx.target, gain))
        return deltas

    return []
