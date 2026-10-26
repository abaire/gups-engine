# The rules module: the instructor-owned formulas behind combat, movement and regen.
#
# combat.star exports one function per rules call. The engine calls them
# with a read-only ctx: views of the actors and items involved, ctx.rng and ctx.roll for
# randomness, and ctx.deny for cast_allowed. If a function is missing, raises an error or
# returns something unusable, the engine uses its built-in formula for that call instead, so
# combat keeps working while you experiment.
#
# Zones, regions, rooms, affects and worn items can adjust some results through modifiers
# (to_hit, damage, initiative, cast_allowed, move_cost, regen). Use `@trace rules` in game,
# or w.trace() in a test, to see each result and the chain of modifiers that produced it.

package(
    api_version = 1,
    kind = "rules",
    title = "Course rules",
)
