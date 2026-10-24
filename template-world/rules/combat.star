# The course rules. These start out identical to the engine's built-in Kotlin ruleset, so
# change any of them to rebalance the game. Every function must be a top-level def.

def _clamp(n, lo, hi):
    return max(lo, min(hi, n))

def equipment_slots():
    # Slot names, in the order the modifier chain visits worn items. An item declares its slot
    # with wear = "<slot>"; wear = "finger" fits the first free finger_* slot.
    return ["head", "neck", "body", "arms", "hands", "finger_l", "finger_r",
            "waist", "legs", "feet", "wield", "offhand", "held"]

def to_hit(ctx):
    # Percent chance that ctx.attacker's swing hits ctx.defender.
    a = ctx.attacker
    d = ctx.defender
    return _clamp(60 + (a.stats["dex"] - d.stats["dex"]) * 2 + (a.level - d.level) * 3, 5, 95)

def damage(ctx):
    # Raw damage of one auto-attack, before mitigate.
    dice = ctx.weapon.dice if ctx.weapon and ctx.weapon.dice else "1d4"
    return max(1, ctx.roll(dice) + (ctx.attacker.stats["str"] - 10) // 2)

def mitigate(ctx, amount, type):
    # What ctx.defender actually takes: armor first, then resistance to the damage type.
    if amount <= 0:
        return 0
    d = ctx.defender
    after_armor = max(1, amount - d.armor // 2)
    resist = d.resists.get(type, 0)
    return max(0, after_armor * (100 - resist) // 100)

def initiative(ctx):
    # Higher acts first in a combat round. Add ctx.rng.int(1, 6) for a rolled initiative.
    return ctx.actor.stats["dex"] + ctx.actor.level

def cast_allowed(ctx):
    # None allows the cast; ctx.deny("why") refuses it before any cost is paid.
    return None

def interrupt_check(ctx, amount):
    # Whether `amount` damage breaks ctx.actor's spell (ctx.skill is the spell being cast).
    pct = ctx.skill.interrupt["damage_pct"] if ctx.skill else None
    if pct == None:
        return False
    return amount * 100 >= pct * max(1, ctx.actor.max_hp)

def retreat_check(ctx):
    # Percent chance to step away while ctx.target stands on the same spot.
    pinner = ctx.target.stats["dex"] if ctx.target else 10
    return _clamp(50 + (ctx.actor.stats["dex"] - pinner) * 3, 5, 95)

def flee_chance(ctx):
    return _clamp(40 + (ctx.actor.stats["dex"] - 10) * 2, 5, 95)

def move_cost(ctx):
    # Move points for walking out of ctx.room.
    return 1

def regen(ctx):
    # Vitals restored every world tick (30 seconds).
    a = ctx.actor
    return {
        "hp": 1 + a.stats["con"] // 4 + a.level // 2,
        "mana": 1 + (a.stats["int"] + a.stats["wis"]) // 8,
        "move": 2 + a.stats["dex"] // 4,
    }

def carry_limit(ctx):
    return ctx.actor.stats["str"] * 10

def encumbrance(ctx):
    # Stat changes while carrying more than carry_limit.
    return {"dex": -2}

def get_lag(ctx, band):
    # Extra lag for picking up ctx.item at this band; None means it's out of reach.
    if band == "melee":
        return 0
    if band == "near":
        return 2
    return None

def xp_for_kill(ctx):
    # XP ctx.actor earns for killing ctx.target.
    return 50 * ctx.target.level

def xp_to_level(level):
    # XP needed to go from `level` to the next one.
    return 1000 * level

def on_player_death(ctx):
    # Where a dead player (ctx.actor) wakes up, and the XP it costs them.
    return {"respawn": ctx.actor.recall_room, "xp_loss": ctx.actor.xp // 10}
