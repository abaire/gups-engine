# The course rules: every number that gives the world's stats, vitals and damage types a
# meaning lives here. The engine has no built-in ruleset, so every function the rules package
# defines is required, and a function that is missing, fails or returns the wrong thing is
# reported to the instructors while the engine carries on as though it did nothing. Change any
# formula to rebalance the game. Every function must be a top-level def, and helpers start with
# `_`. The package may spread its functions over several files: this one holds combat, and
# perception.star and manipulation.star hold senses and doors. A function lives in one file only.
#
# Stats, vitals and resists are read from the actor by name: ctx.actor.dex, ctx.actor.hp,
# ctx.actor.max_hp, ctx.actor.resists.fire. Results are built with the constructors
# vitals(...) and stats(...), which check their names against MODULE.gups.

def _clamp(n, lo, hi):
    return max(lo, min(hi, n))

def _or(value, default):
    return value if value else default

def equipment_slots():
    # Slot names, in the order the modifier chain visits worn items.
    return ["head", "neck", "body", "arms", "hands", "finger_l", "finger_r",
            "waist", "legs", "feet", "wield", "offhand", "held"]

def slot_families():
    # A family's slots fill in order. wear = "<family>" takes the first free one.
    return {
        "ring": ["finger_l", "finger_r"],
        "hand": ["wield", "offhand"],
    }

def actor_slots(ctx):
    # Every actor has every slot. Return fewer (or a race's extras) based on ctx.actor.
    return equipment_slots()

def slot_cost(ctx):
    # Mighty warriors (str >= 18) can wield two-handed items in one hand.
    if ctx.actor.str >= 18 and ctx.item.slot_cost == 2:
        return 1
    return ctx.item.slot_cost

def starting_stats(ctx):
    # The numbers of a new character or a mob about to spawn (ctx.actor). A player starts with
    # every stat and vital at 0; a mob carries what its prototype declared. A vital's current
    # value starts at its maximum unless `current` says otherwise.
    a = ctx.actor
    if a.is_player:
        return struct(
            stats = stats(str = 10, dex = 10, con = 10, int = 10, wis = 10, armor = 0),
            max = vitals(hp = 20, mana = 20, move = 100),
        )
    return struct(
        stats = stats(
            str = _or(a.base.str, 10),
            dex = _or(a.base.dex, 10),
            con = _or(a.base.con, 10),
            int = _or(a.base.int, 10),
            wis = _or(a.base.wis, 10),
            armor = a.base.armor,
        ),
        max = vitals(
            hp = _or(a.base.max_hp, 10),
            mana = a.base.max_mana,
            move = _or(a.base.max_move, 100),
        ),
    )

def condition(ctx):
    # "ok", "incapacitated" or "dead". Asked after any change to one of the actor's vitals
    # and at every world tick. ctx.actor.below_zero_since("hp") gives time-based rules.
    return "dead" if ctx.actor.hp <= 0 else "ok"

def to_hit(ctx):
    # Percent chance that ctx.attacker's swing hits ctx.defender.
    a = ctx.attacker
    d = ctx.defender
    chance = 60 + (a.dex - d.dex) * 2 + (a.level - d.level) * 3
    # Off-hand attacks are clumsy and take a -10 penalty to hit.
    if ctx.slot == "offhand":
        chance -= 10
    return _clamp(chance, 5, 95)

def damage(ctx):
    # Raw damage of one auto-attack, before mitigate.
    dice = ctx.weapon.dice if ctx.weapon and ctx.weapon.dice else "1d4"
    return max(1, ctx.roll(dice) + (ctx.attacker.str - 10) // 2)

def mitigate(ctx, amount, type):
    # The vital losses that `amount` damage of `type` causes ctx.defender: armor first, then
    # resistance to the damage type. Name more vitals to spread damage around.
    if amount <= 0:
        return vitals()
    d = ctx.defender
    after_armor = max(1, amount - d.armor // 2)
    resist = getattr(d.resists, type)
    return vitals(hp = max(0, after_armor * (100 - resist) // 100))

def initiative(ctx):
    # Higher acts first in a combat round. Add ctx.rng.int(1, 6) for a rolled initiative.
    return ctx.actor.dex + ctx.actor.level

def cast_allowed(ctx):
    # None allows the cast; ctx.deny("why") refuses it before any cost is paid.
    return None

def interrupt_check(ctx, amount):
    # Whether `amount`, the total vital loss of a damage event, breaks ctx.actor's spell
    # (ctx.skill is the spell being cast).
    pct = ctx.skill.interrupt["damage_pct"] if ctx.skill else None
    if pct == None:
        return False
    return amount * 100 >= pct * max(1, ctx.actor.max_hp)

def retreat_check(ctx):
    # Percent chance to step away while ctx.target stands on the same spot.
    pinner = ctx.target.dex if ctx.target else 10
    return _clamp(50 + (ctx.actor.dex - pinner) * 3, 5, 95)

def flee_chance(ctx):
    return _clamp(40 + (ctx.actor.dex - 10) * 2, 5, 95)

def move_cost(ctx):
    # The vitals spent for walking out of ctx.room.
    return vitals(move = 1)

def regen(ctx):
    # What each vital gains every world tick (30 seconds); a negative number loses.
    a = ctx.actor
    return vitals(
        hp = 1 + a.con // 4 + a.level // 2,
        mana = 1 + (a.int + a.wis) // 8,
        move = 2 + a.dex // 4,
    )

def carry_limit(ctx):
    return ctx.actor.str * 10

def encumbrance(ctx):
    # Stat changes while carrying more than carry_limit.
    return stats(dex = -2)

def get_lag(ctx, band):
    # Extra lag for picking up ctx.item at this band; None means it's out of reach.
    if band == "melee":
        return 0
    if band == "near":
        return 2
    return None

def _footing(ctx):
    # Rooms tagged "sticky_floor" make every step take twice as long.
    return 2 if "sticky_floor" in ctx.room.tags else 1

def advance_lag(ctx):
    # Recovery pulses after stepping toward ctx.target.
    return 2 * _footing(ctx)

def retreat_lag(ctx):
    # Recovery pulses after stepping away from ctx.target.
    return 2 * _footing(ctx)

def retreat_fail_lag(ctx):
    # Recovery pulses after ctx.target, the pinner, holds you in place.
    return 4

def flee_fail_lag(ctx):
    # Recovery pulses after a flee that fails, by the roll or at the exit.
    return 4

def xp_for_kill(ctx):
    # 50 per victim level, scaled by how the victim's level compares to the killer's:
    # 5+ levels below pays nothing, 2+ levels above pays 140%, capped at 150%.
    diff = ctx.target.level - ctx.actor.level
    pct = _clamp(100 + 20 * diff, 0, 150)
    return 50 * ctx.target.level * pct // 100

def xp_to_level(ctx, level):
    # Total XP at which `level` ends: 1000, 3000, 6000, 10000... Each level costs 1000 * level
    # more than the last, so 20 kills of an equal-level mob earn a level at any level.
    return 500 * level * (level + 1)

def on_level_up(ctx):
    # Deterministic gains; heal by the same amount so the new points are usable.
    a = ctx.actor
    gain = vitals(hp = 5 + a.con // 4, mana = 2 + a.wis // 8, move = 5)
    return struct(max = gain, current = gain)

def on_player_death(ctx):
    # Where a dead player (ctx.actor) wakes up, with which vitals, and the XP it costs them.
    a = ctx.actor
    floor = xp_to_level(ctx, a.level - 1) if a.level > 1 else 0
    # 10% of the XP earned within this level. XP can sit below the floor (taken by a script, or
    # a character created above level 1), so the loss never goes negative.
    xp_loss = max(0, (a.xp - floor) // 10)
    level_loss = 0
    # A punishing variant takes 10% of all XP and drops each level the rest no longer pays for:
    #     xp_loss = a.xp // 10
    #     for _ in range(a.level - 1):
    #         if a.xp - xp_loss >= xp_to_level(ctx, a.level - 1 - level_loss):
    #             break
    #         level_loss += 1
    return {
        "respawn": a.recall_room,
        "vitals": vitals(hp = 1),
        "xp_loss": xp_loss,
        "level_loss": level_loss,
    }
