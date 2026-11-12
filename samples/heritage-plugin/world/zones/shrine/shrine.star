# The Hall of Mentors: the guild mentor changes a character's class for 10 gold coins, keeping
# every skill they have learned, and gives clerics a fifth off.
#
# The Altar of Rebirth: the priestess remakes race and class for 100 gold coins once an hour.
# Everything learned is forgotten and its practice sessions come back. She charges five times
# as much to those she dislikes, refuses those she despises, and warms to those she remakes.

load("//lib:respec.star", "respec")

# skills and cooldown_pulses take the library's defaults: "keep" and no wait.
MENTOR = respec.define(id = "mentor", choices = ["class"], cost = 10)

def _mentor_open(ctx):
    respec.open(ctx, ctx.self, MENTOR)

def _mentor_offer(ctx, action, customer, choice, old, new, cost, currency, cooldown_pulses, skills):
    if customer.has_trait("cleric"):
        return {"cost": cost * 8 // 10}
    return None

room("mentor_hall",
    name = "Hall of Mentors",
    desc = "Banners of every guild hang over a long table where the guild mentor keeps court.",
    flags = ["dead_end_ok"],
    spawns = [spawn(":mentor")],
)

mob("mentor",
    name = "the guild mentor",
    keywords = ["mentor"],
    desc = "A grey-haired teacher who has trained warriors, mages and clerics alike.",
    vitals = vitals(hp = 50),
    behaviors = [sentinel()],
    skills = {"//skills/heritage_skills:magic_missile": 100, "//skills/heritage_skills:minor_heal": 100},
    hooks = {"respec_offer": _mentor_offer},
    on_spawn = _mentor_open,
)

REBIRTH = respec.define(id = "rebirth", choices = ["race", "class"], skills = "reset", cost = 100,
                        cooldown_pulses = 14400)

def _altar_open(ctx):
    respec.open(ctx, ctx.self, REBIRTH)

def altar_offer(ctx, action, customer, choice, old, new, cost, currency, cooldown_pulses, skills):
    """The priestess's price follows her disposition toward the customer."""
    if not ctx.has_feature("standing"):
        return None
    d = ctx.disposition(ctx.self, customer)
    if d <= -50:
        return "The priestess turns her back on you."
    if d < 0:
        return {"cost": cost * 5}
    return None

def altar_done(ctx, customer, choice, old, new, skills, cost):
    # She warms to those she remakes.
    ctx.adjust_standing(ctx.self, customer, 10)

room("altar",
    name = "Altar of Rebirth",
    desc = "A white stone altar stands under an open sky; those who kneel here rise remade.",
    flags = ["dead_end_ok"],
    spawns = [spawn(":priestess")],
)

mob("priestess",
    name = "the priestess",
    keywords = ["priestess"],
    desc = "A serene woman in white who remakes those who kneel before her.",
    vitals = vitals(hp = 50),
    behaviors = [sentinel()],
    hooks = {"respec_offer": altar_offer, "respec_done": altar_done},
    on_spawn = _altar_open,
)
