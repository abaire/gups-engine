# The foraging squirrel: a small demonstration of //lib:ai.star (see tutorial 05).
#
# It lives on the gravel path. Every think it runs a behavior tree from the root:
#
#   1. While it is fighting, the tree fails on purpose. ai.run then returns None and the mob takes
#      its top built-in proposal, here `flee` once it is badly hurt.
#   2. Otherwise a GOAP leaf senses a few facts, asks ctx.plan how to get a pinecone lying
#      somewhere else in the zone (the tar pit keeps one) cached on the floor at home, and carries
#      out one step of that plan per cycle. The plan is cached on the blackboard and only replanned
#      when the next step can no longer run.
#
# Once a pinecone lies at home the goal holds and the squirrel idles, even after the tar pit
# restocks. That is enough for a demonstration; a hoarder would sense how many it has cached.

load("//lib:ai.star", "ai")

item("pinecone",
    name = "a pinecone",
    keywords = ["pinecone", "cone"],
    desc = "A woody, brown pinecone with tight scales.",
    weight = 1,
    value = 1,
)

def _on_floor(item):
    return item.location.kind == "room"

def _sense(ctx, bb):
    # Sensing turns world queries into plain facts. Comparisons and searches happen here, once
    # per cycle, so the planner only ever sees True or False.
    home = ctx.self.home_room
    here = ctx.self.room.label
    ground = [i for i in ctx.find(":pinecone", scope = "zone") if _on_floor(i)]
    away = [i.location.room.label for i in ground if i.location.room.label != home]

    # Head for a pinecone in this room if there is one, otherwise the first one found.
    if away:
        bb["target"] = here if here in away else away[0]
    elif "target" in bb:
        bb.pop("target")

    return {
        "at_home": here == home,
        "has_pinecone": ctx.self.has_item(":pinecone"),
        "pinecone_here": here in away,
        "pinecone_known": len(away) > 0,
        "cached": len([i for i in ground if i.location.room.label == home]) > 0,
    }

def _step_toward(ctx, room):
    path = ctx.path_to(room) if room else None
    if path == None:
        return ai.FAILURE
    if path:
        ctx.act_move(ctx.self, path[0])
    return ai.RUNNING

def _walk_to_pinecone(ctx, bb):
    return _step_toward(ctx, bb.get("target"))

def _take_pinecone(ctx, bb):
    ctx.act(ctx.self, "get pinecone")
    return ai.RUNNING

def _go_home(ctx, bb):
    return _step_toward(ctx, ctx.self.home_room)

def _drop_pinecone(ctx, bb):
    ctx.act(ctx.self, "drop pinecone")
    return ai.RUNNING

# What each step needs and what it makes true. ctx.plan chains them into the cheapest sequence
# that makes the goal true; `run` is what the squirrel does for that step this cycle.
ACTIONS = [
    {
        "name": "walk_to_pinecone",
        "pre": {"pinecone_known": True, "has_pinecone": False},
        "effects": {"pinecone_here": True},
        "run": _walk_to_pinecone,
    },
    {
        "name": "take_pinecone",
        "pre": {"pinecone_here": True, "has_pinecone": False},
        "effects": {"has_pinecone": True, "pinecone_here": False},
        "run": _take_pinecone,
    },
    {
        "name": "go_home",
        "pre": {"has_pinecone": True, "at_home": False},
        "effects": {"at_home": True},
        "run": _go_home,
    },
    {
        "name": "drop_pinecone",
        "pre": {"has_pinecone": True, "at_home": True},
        "effects": {"has_pinecone": False, "cached": True},
        "run": _drop_pinecone,
    },
]

def _fighting(ctx, bb):
    return ctx.has_feature("combat") and len(ctx.self.fighting()) > 0

# Not fighting, then forage. While fighting the inverter fails the sequence, so the whole tree
# fails and the built-in behaviors decide.
TREE = ai.sequence([
    ai.inverter(ai.condition(_fighting)),
    ai.goap("cache", _sense, ACTIONS, {"cached": True}),
])

def _think(ctx):
    return ai.run(ctx, TREE)

mob("squirrel",
    name = "a foraging squirrel",
    keywords = ["squirrel"],
    desc = "A bushy-tailed squirrel with sharp, busy eyes.",
    level = 1,
    vitals = vitals(hp = 20, move = 50),
    behaviors = [flee(below = vitals(hp = 50))],
    think = _think,
    think_every = 4,
)
