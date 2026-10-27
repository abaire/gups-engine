# The rules module: the instructor-owned formulas behind combat, movement and regen.
#
# combat.star exports one function per rules call. The engine calls them
# with a read-only ctx: views of the actors and items involved, ctx.rng and ctx.roll for
# randomness, and ctx.deny for cast_allowed. There is no built-in ruleset: every export is
# required, and a call that raises an error or returns something unusable is reported to the
# instructors and counts as having done nothing, so nobody is hurt or rewarded by a mistake.
#
# Zones, regions, rooms, affects and worn items can adjust some results through modifiers
# (to_hit, damage, initiative, cast_allowed, move_cost, regen). Use `@trace rules` in game,
# or w.trace() in a test, to see each result and the chain of modifiers that produced it.

package(
    api_version = 1,
    kind = "rules",
    title = "Course rules",
)
