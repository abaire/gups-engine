# Test-only fixtures for standing and faction tests in standing_test.star: `gups test`
# and `gups scenario` load this file, and the live game never does. Real content can't
# name what is declared here.

def _probe_standing(ctx):
    # Syntax: probe <target>
    # Reports standing and history between target/faction and ctx.actor
    arg = ctx.args.strip()
    if arg == "townsfolk":
        s = ctx.standing("//lib:townsfolk", ctx.actor)
        h = ctx.standing_history("//lib:townsfolk", ctx.actor, since = 0)
        ctx.send(ctx.actor, "townsfolk standing=%d gained=%d" % (s, h.gained))
        return
    if arg == "goblins":
        s = ctx.standing("//lib:goblins", ctx.actor)
        h = ctx.standing_history("//lib:goblins", ctx.actor, since = 0)
        ctx.send(ctx.actor, "goblins standing=%d gained=%d" % (s, h.gained))
        return
    for a in ctx.room.actors():
        if a.name == arg or a.label.endswith(":" + arg) or arg in a.keywords:
            s = ctx.standing(a, ctx.actor)
            h = ctx.standing_history(a, ctx.actor, since = 0)
            ctx.send(ctx.actor, "%s standing=%d gained=%d" % (arg, s, h.gained))
            return
    ctx.send(ctx.actor, "probe: not found '%s'" % arg)

def _find(ctx, arg):
    for a in ctx.room.actors():
        if a.name == arg or a.label.endswith(":" + arg) or arg in a.keywords:
            return a
    return None

def _donate(ctx):
    # Syntax: donate <mob> <coins>
    words = ctx.args.strip().split(" ")
    ctx.standing_event(_find(ctx, words[0]), ctx.actor, "donation", amount = int(words[1]))

room("standing_test_room",
    name = "The Standing Test Chamber",
    desc = "A sealed chamber for standing tests.",
    flags = ["dead_end_ok"],
    commands = {"probe": _probe_standing, "donate": _donate},
)

room("standing_gate",
    name = "The Standing Test Gate",
    desc = "A gate into the standing test chamber.",
    exits = [exit(DIR.north, to = ":standing_test_room")],
    commands = {"probe": _probe_standing},
)
