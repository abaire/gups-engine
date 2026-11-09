# How fast actors walk, and which mobs think again right after a step. A separate file from
# combat.star because it is tuned together with the `speed` stat rather than the combat formulas.
# Both exports are optional: without move_pace everyone walks a room a pulse, and without
# chain_think the engine applies the same rule written out below.

# The behaviors that keep a mob stepping as soon as its pace allows, instead of waiting for its
# next think.
_CHASERS = ["pursue", "follow", "heel", "leash"]

def move_pace(ctx):
    # Hundredths of a pulse per room: a speed of 100 walks a room a pulse, 300 three a pulse.
    # The floor keeps a mob at speed 0 moving at all.
    return 10000 // max(ctx.actor.speed, 10)

def chain_think(ctx):
    # A mob that just stepped after its quarry, leader or owner, or toward home, takes its next
    # step as soon as its pace allows instead of waiting for its next think. Only a step through
    # an exit chains: a move the mob started on its own cadence asks nothing.
    return ctx.exit != None and ctx.behavior in _CHASERS
