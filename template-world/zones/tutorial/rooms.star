# Rooms and exits. Map:
#
#                 arena (LARGE)
#                   |
#    armory ==== hall (LARGE) ---- (the guide waits here)
#   (locked door)   |
#                 gate  <- start here
#
# Each room's `size` sets its track: CRAMPED (1 position), NORMAL (3) or LARGE (5). Combat
# range depends on how far apart actors stand on it.

load("//lib:quest.star", "quest")
load(":helpers.star", "FIRST_STEPS", "greeting")

def _read(ctx):
    # A zone command: `read sign` runs this in the tick lane, like a normal action.
    # ctx.args is the text after the verb.
    if ctx.args in ("", "sign"):
        ctx.send(ctx.actor, "The sign reads: 'Go north to meet the guide. Type `help` for commands.'")
    else:
        ctx.send(ctx.actor, "There is nothing like that to read here.")

room("gate",
    name = "The Tutorial Gate",
    desc = "A wooden gate stands open to the north. A sign is nailed to the post.",
    size = NORMAL,
    flags = ["dead_end_ok"],
    exits = [exit("north", to = ":hall")],
    commands = {"read": _read},
)

def _hall_enter(ctx):
    # on_enter fires on the room after an actor arrives. Greet each player only once.
    if ctx.actor.is_player and not ctx.get_attr(ctx.actor, "greeted", False):
        ctx.set_attr(ctx.actor, "greeted", True)
        ctx.send(ctx.actor, greeting(ctx.actor.name))
        # A library helper: it stores the quest in the player's attributes and tells them.
        quest.start(ctx, ctx.actor, FIRST_STEPS)

room("hall",
    name = "The Hall of Lessons",
    desc = "A long, bright hall. Doors lead east and north; the gate is south.",
    size = LARGE,
    exits = [
        exit("south", to = ":gate"),
        # Arrive at the far end of the arena: entry_index -1 means the back of the track.
        exit("north", to = ":arena", entry_index = -1),
        # A locked door. Doors on both sides of a passage open and close together.
        exit("east", to = ":armory", door = door(state = "locked", key = ":brass_key")),
    ],
    # Spawns refill on zone reset, and respawn_pulses after the last one leaves the world.
    spawns = [spawn(":guide", at_index = 2)],
    on_enter = _hall_enter,
)

def _stock_armory(ctx):
    # on_load runs once when the world starts; on_reset at every zone reset. Both keep a
    # practice sword on the armory floor without ever stacking up duplicates.
    # (ctx.find(":practice_sword") would also count swords carried by players in the room.)
    on_floor = [i for i in ctx.self.items() if i.label.endswith(":practice_sword")]
    if not on_floor:
        ctx.spawn(":practice_sword", ctx.self)

room("armory",
    name = "The Armory",
    desc = "A cramped closet of racks, mostly empty.",
    size = CRAMPED,
    flags = ["dead_end_ok"],
    exits = [exit("west", to = ":hall", door = door(state = "locked", key = ":brass_key"))],
    on_load = _stock_armory,
    on_reset = _stock_armory,
)

def _blunted(ctx, base):
    # A modifier gets the rules ctx and the value so far, and returns the new value. This one
    # halves every auto-attack's damage. ctx is read-only: modifiers adjust numbers, nothing else.
    return max(1, base // 2)

# A region is a tag rooms carry (room(regions = [...])); it may span zones. Its modifiers
# apply in every room that carries it. Points: to_hit, damage, initiative, cast_allowed,
# move_cost, regen. `@trace rules` shows each modifier at work.
region("practice_grounds",
    modifiers = {"damage": _blunted},
)

def _arena_enter(ctx):
    w = ctx.actor.equipped("wield")
    sword = w != None and w.label.endswith(":practice_sword")
    if ctx.actor.is_player and sword and quest.step(ctx, ctx.actor, FIRST_STEPS) == 2:
        quest.complete(ctx, ctx.actor, FIRST_STEPS)
        ctx.give(ctx.actor, ":leather_cap")
        ctx.give(ctx.actor, ":healing_draught")
        ctx.send(ctx.actor, "The guide calls from the hall: 'Well done! Take these, you've earned them.'")

room("arena",
    name = "The Practice Arena",
    desc = "Sand, a few benches, and a battered training dummy at the far end.",
    size = LARGE,
    flags = ["dead_end_ok"],
    regions = ["practice_grounds"],
    exits = [exit("south", to = ":hall")],
    spawns = [spawn(":training_dummy", at_index = 0)],
    on_enter = _arena_enter,
)
