mob("rust_hound",
    name = "a rust hound",
    keywords = ["hound", "rust"],
    stats = stats(might = 12, agility = 12, plating = 2),
    vitals = vitals(integrity = 20, sanity = 8, power = 60),
    behaviors = [aggressive(toward = "players"), flee(below = vitals(integrity = 20))],
)

mob("welder_drone",
    name = "a welder drone",
    keywords = ["drone", "welder"],
    stats = stats(tech = 14, plating = 4),
    vitals = vitals(integrity = 30, sanity = 10, power = 80),
    resists = resists(energy = 25),
)

# Its whispers go straight to the mind.
def _whisper(ctx):
    for a in ctx.room.actors():
        if a.is_player:
            ctx.damage(a, 3, DAMAGE.psychic, "sanity", source = ctx.self)

def _tick(ctx):
    _whisper(ctx)

mob("whisperer",
    name = "a whisperer",
    keywords = ["whisperer"],
    vitals = vitals(integrity = 25, sanity = 40, power = 40),
    on_tick = _tick,
)
