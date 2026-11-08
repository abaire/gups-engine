# Fixture entities for skill_gain_test.star. A testdata file loads only in a test world, never
# on a running server.

def _bot_invoke(ctx):
    # The bot invokes its own routine, so on_skill_use fires with a mob as the actor.
    ctx.act_cast(ctx.self, ":self_repair", None)

mob("test_repair_bot",
    name = "a test repair bot",
    keywords = ["repair", "bot"],
    skills = {":self_repair": 1},
    on_say = _bot_invoke,
)

def _report_use(ctx):
    # A skill only rules read: the engine never sees the use, so the script reports it.
    ctx.report_skill_use(ctx.actor, ":overload")

item("test_reporter",
    name = "a test reporter",
    keywords = ["reporter"],
    charges = 1,
    on_use = _report_use,
)
