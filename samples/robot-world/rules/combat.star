# The robot world's rules. Three vitals can end a robot's turn: integrity and sanity at 0
# incapacitate it and at -10 destroy it, and radiation destroys it a minute after the chassis
# has absorbed more than it can take.

def _clamp(n, lo, hi):
    return max(lo, min(hi, n))

def equipment_slots():
    return ["head", "torso", "arms", "legs", "core", "wield"]

def starting_stats(ctx):
    # Players are built with a little variation: each rolls its integrity and sanity.
    a = ctx.actor
    if a.is_player:
        return struct(
            stats = stats(might = 10, agility = 10, tech = 10, grit = 10, plating = 2),
            max = vitals(
                integrity = 25 + ctx.rng.int(0, 10),
                sanity = 20 + ctx.rng.int(0, 5),
                power = 100,
                rads = 100,
            ),
        )
    # A mob keeps what its prototype declared, and gets a default for the rest.
    return struct(
        stats = stats(
            might = a.base.might or 10,
            agility = a.base.agility or 10,
            tech = a.base.tech or 10,
            grit = a.base.grit or 10,
            plating = a.base.plating,
        ),
        max = vitals(
            integrity = a.max_integrity or 15,
            sanity = a.max_sanity or 10,
            power = a.max_power or 50,
            rads = a.max_rads or 100,
        ),
    )

def condition(ctx):
    a = ctx.actor
    if a.integrity <= -10 or a.sanity <= -10:
        return "dead"
    # Radiation is a margin: once it is below zero the chassis has a minute left.
    since = a.below_zero_since("rads")
    if since != None and ctx.pulse - since >= 60:
        return "dead"
    if a.integrity <= 0 or a.sanity <= 0:
        return "incapacitated"
    return "ok"

def to_hit(ctx):
    a = ctx.attacker
    d = ctx.defender
    return _clamp(55 + (a.agility - d.agility) * 2 + (a.level - d.level) * 3, 5, 95)

def damage(ctx):
    dice = ctx.weapon.dice if ctx.weapon and ctx.weapon.dice else "1d4"
    return max(1, ctx.roll(dice) + (ctx.attacker.might - 10) // 2)

def mitigate(ctx, amount, type):
    # Where damage lands: psychic damage goes to the mind, the rest to the chassis.
    if amount <= 0:
        return vitals()
    d = ctx.defender
    resist = getattr(d.resists, type)
    if type == "psychic":
        felt = max(1, amount - d.grit // 4)
        return vitals(sanity = max(0, felt * (100 - resist) // 100))
    soaked = amount - d.plating // 2 if type == "kinetic" else amount
    return vitals(integrity = max(0, max(1, soaked) * (100 - resist) // 100))

def initiative(ctx):
    return ctx.actor.agility + ctx.actor.level

def cast_allowed(ctx):
    return None

def interrupt_check(ctx, amount):
    pct = ctx.skill.interrupt["damage_pct"] if ctx.skill else None
    if pct == None:
        return False
    return amount * 100 >= pct * max(1, ctx.actor.max_integrity)

def retreat_check(ctx):
    pinner = ctx.target.agility if ctx.target else 10
    return _clamp(50 + (ctx.actor.agility - pinner) * 3, 5, 95)

def flee_chance(ctx):
    return _clamp(40 + (ctx.actor.agility - 10) * 2, 5, 95)

def move_cost(ctx):
    return vitals(power = 1)

def regen(ctx):
    # Power trickles back, repairs are slow, and the mind recovers when it is not broken.
    a = ctx.actor
    return vitals(
        integrity = a.tech // 5,
        sanity = 1 + a.grit // 5,
        power = 3 + a.tech // 4,
    )

def carry_limit(ctx):
    return ctx.actor.might * 10

def encumbrance(ctx):
    return stats(agility = -2)

def get_lag(ctx, band):
    if band == "melee":
        return 0
    if band == "near":
        return 2
    return None

def xp_for_kill(ctx):
    return 40 * ctx.target.level

def xp_to_level(level):
    return 800 * level

def on_player_death(ctx):
    return {
        "respawn": ctx.actor.recall_room,
        "vitals": vitals(integrity = 5, sanity = 5),
        "xp_loss": ctx.actor.xp // 10,
    }
