# Pursuit helpers for a fast hunter. A world's own copy: tune the odds and the message to taste.
#
#     load("//lib:pursuit.star", "cut_off")
#
#     mob("hunting_cat", ..., before_exit = cut_off)
#
# `cut_off` is a `before_exit` observer: when someone the hunter is fighting tries to leave the
# room, a faster hunter may stop them dead.

def cut_off(ctx):
    # Only cut off someone the hunter actually fights, and only when the hunter is the faster of
    # the two. The wider the pace gap, the likelier the cut-off, capped at 90%.
    me, them = ctx.self, ctx.actor
    if them == me or them not in me.fighting():
        return None
    if me.move_pace >= them.move_pace:
        return None
    chance = min(90, 100 - 100 * me.move_pace // them.move_pace)
    if ctx.roll("1d100") <= chance:
        return ctx.deny("%s cuts you off." % me.name.capitalize())
    return None
