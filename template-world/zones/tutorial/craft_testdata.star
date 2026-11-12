# Fixture for the crafting library tests in craft_test.star: a bare lab whose room, a ground anvil
# and a carried kit are stations, and the items their recipes use.

load("//lib:craft.star", "craft")

item("test_bar", name = "a test bar", plural = "test bars", desc = "A bar kept for the craft tests.",
     keywords = ["bar"], stackable = True)
item("test_wood", name = "a piece of test wood", desc = "Wood kept for the craft tests.",
     keywords = ["wood"], tags = ["wood"])
item("test_hammer", name = "a test hammer", desc = "A hammer kept for the craft tests.",
     keywords = ["hammer"], tags = ["hammer"], charges = 3, empty = "keep")
item("test_mallet", name = "a test mallet", desc = "A mallet that never wears out.",
     keywords = ["mallet"], tags = ["hammer"])
item("test_rod", name = "a test rod", desc = "What the lab makes.", keywords = ["rod"])
item("test_bent_rod", name = "a bent test rod", desc = "What the lab makes badly.", keywords = ["rod"])
item("test_blade", name = "a test blade", desc = "A blade kept for the craft tests.",
     keywords = ["blade"], kind = "melee", wear = "wield",
     variants = {"tempered": ":test_tempered_blade"})
item("test_tempered_blade", name = "a tempered test blade", desc = "A blade the lab tempered.",
     keywords = ["blade"], kind = "melee", wear = "wield")
item("test_coal", name = "a lump of test coal", plural = "lumps of test coal",
     desc = "Coal kept for the craft tests.", keywords = ["coal"], stackable = True)
item("test_nails", name = "a test nail", plural = "test nails", desc = "Nails the lab makes.",
     keywords = ["nail", "nails"], stackable = True)

mob("craft_dummy", name = "a craft dummy", desc = "A dummy to pick fights with.",
    keywords = ["dummy"], vitals = vitals(hp = 1000))

_BAR = "//zones/tutorial:test_bar"

_ROD = craft.recipe("rod",
    name = "a test rod", verb = "forge",
    inputs = [craft.need(_BAR, count = 2, lose = "1d2"), craft.need(tag = "wood", lose = 1)],
    tools = [craft.need(tag = "hammer", charges = 1)],
    skill = "//skills/arcane:mend", difficulty = 10, time = 4, lag = 2,
    failure = craft.failure(lose = "all", make = craft.make("//zones/tutorial:test_bent_rod")),
    qualities = [craft.quality(at = 75, name = "a fine test rod"),
                 craft.quality(at = 95, name = "a masterwork test rod", stat_deltas = {"dex": 1},
                               add_tags = ["masterwork"])],
    result = craft.make("//zones/tutorial:test_rod"))

_PLAIN = craft.recipe("plain_rod", name = "a plain rod", inputs = [craft.need(_BAR)], time = 2,
                      result = craft.make("//zones/tutorial:test_rod"))

_BETTER = craft.recipe("plain_rod", name = "a better rod", inputs = [craft.need(_BAR)], time = 2,
                       result = craft.make("//zones/tutorial:test_rod"))

_SECRET = craft.recipe("secret_rod", verb = "smelt", inputs = [craft.need(_BAR, count = 3)],
                       learned = True, result = craft.make("//zones/tutorial:test_rod"))

_ANVIL_ROD = craft.recipe("anvil_rod", name = "an anvil rod", inputs = [craft.need(_BAR, count = 5)],
                          tools = [craft.need(tag = "anvil_tool", name = "anvil tongs")],
                          result = craft.make("//zones/tutorial:test_rod", count = 2))

_POLISH = craft.recipe("polish", verb = "polish", target = craft.need(kind = "melee"),
                       inputs = [craft.need(tag = "wood")],
                       result = craft.improve(affect = "polished", stat_deltas = {"dex": 1},
                                              add_tags = ["polished"], unless_tag = "polished"))

_COAL = "//zones/tutorial:test_coal"

# Every failure loss form but dice (the rod has those): the default 50%, "all" and 0.
_SLAG = craft.recipe("slag", verb = "smelt",
    inputs = [craft.need(_BAR, count = 3), craft.need(tag = "wood", lose = "all"),
              craft.need(_COAL, lose = 0)],
    skill = "//skills/arcane:mend",
    failure = craft.failure(message = "The mix runs to slag."),
    result = craft.make("//zones/tutorial:test_rod"))

_NAILS = craft.recipe("nails", inputs = [craft.need(_BAR)], time = 2,
                      result = craft.make("//zones/tutorial:test_nails", count = 3))

_TAP = craft.recipe("tap", inputs = [craft.need(_BAR)],
                    tools = [craft.need(tag = "hammer", charges = 3)], time = 2,
                    result = craft.make("//zones/tutorial:test_nails"))

_TEMPER = craft.recipe("temper", verb = "temper",
                       target = craft.need(label = "//zones/tutorial:test_blade"),
                       inputs = [craft.need(_COAL)], time = 2,
                       result = craft.improve(variant = "tempered", add_tags = ["tempered"]))

# The target also fits the input: the blade being whetted must not be spent as its own ingredient.
_WHET = craft.recipe("whet", verb = "whet",
                     target = craft.need(label = "//zones/tutorial:test_blade"),
                     inputs = [craft.need("//zones/tutorial:test_blade")], time = 2,
                     result = craft.improve(add_tags = ["whetted"], unless_tag = "whetted"))

def _lab_recipes(ctx):
    return [_ROD, _PLAIN, _SECRET, _BETTER, _SLAG, _NAILS, _TAP, _TEMPER, _WHET]

# The lab's craft_check refuses everything once closed and adds its bonus to the rod.
def _lab_check(ctx, crafter, recipe, target, adjust):
    if ctx.get_attr(ctx.self, "closed", False):
        return "The lab is closed."
    if recipe == "rod":
        return adjust + ctx.get_attr(ctx.self, "bonus", 0)
    return None

def _lab_done(ctx, crafter, recipe, outcome, items):
    ctx.set_attr(ctx.self, "last_done", "%s:%s:%s" % (recipe, outcome, [i.name for i in items]))

def _anvil_recipes(ctx):
    return [_ANVIL_ROD, _PLAIN]

def _kit_recipes(ctx):
    return [_POLISH]

def _broken_recipes(ctx):
    return "not a list"

item("test_anvil", name = "a test anvil", desc = "An anvil kept for the craft tests.",
     keywords = ["anvil"], weight = 500, hooks = {"craft_recipes": _anvil_recipes})
item("test_kit", name = "a test kit", desc = "A kit kept for the craft tests.", keywords = ["kit"],
     hooks = {"craft_recipes": _kit_recipes})
item("test_junk", name = "a test junk box", desc = "A station whose hook returns junk.",
     keywords = ["junk"], hooks = {"craft_recipes": _broken_recipes})

# bad <case>: builds one bad recipe piece at run time, so its error is a script diagnostic the
# tests can read. Anything else shows the usage.
def _bad(ctx):
    case = ctx.args
    if case == "need_none":
        craft.need()
    elif case == "need_two":
        craft.need(_BAR, tag = "wood")
    elif case == "need_relative":
        craft.need(":test_bar")
    elif case == "need_tag":
        craft.need(tag = "Wood")
    elif case == "need_count":
        craft.need(_BAR, count = 0)
    elif case == "need_lose":
        craft.need(_BAR, lose = "lots")
    elif case == "need_pct":
        craft.need(_BAR, lose = "150%")
    elif case == "make_count":
        craft.make(_BAR, count = 0)
    elif case == "improve_empty":
        craft.improve(affect = "nothing")
    elif case == "improve_applies":
        craft.improve(applies = "held", add_tags = ["x"])
    elif case == "failure_lose":
        craft.failure(lose = None)
    elif case == "quality_at":
        craft.quality(at = 0)
    elif case == "recipe_id":
        craft.recipe("Bad Id", result = craft.make(_BAR))
    elif case == "recipe_time":
        craft.recipe("r", result = craft.make(_BAR), time = 0)
    elif case == "recipe_target":
        craft.recipe("r", result = craft.improve(add_tags = ["x"]))
    elif case == "recipe_make_target":
        craft.recipe("r", result = craft.make(_BAR), target = craft.need(kind = "melee"))
    elif case == "recipe_charges":
        craft.recipe("r", result = craft.make(_BAR), inputs = [craft.need(_BAR, charges = 1)])
    elif case == "recipe_tiers":
        craft.recipe("r", result = craft.make(_BAR),
                     qualities = [craft.quality(at = 5), craft.quality(at = 5)])
    elif case == "recipe_task_name":
        craft.recipe("r", result = craft.make(_BAR), verb = "reinforce", name = "a " + "very " * 13 + "long name")
    else:
        ctx.send(ctx.actor, "Usage: bad <case>")

# check: the defaults a recipe fills in.
def _check(ctx):
    r = craft.recipe("hone_edge", verb = "hone", target = craft.need(kind = "melee"),
                     result = craft.improve(add_tags = ["honed"]))
    s = craft.recipe("smelt_ore", verb = "smelt", result = craft.make(_BAR))
    ctx.send(ctx.actor, "name=%s doing=%s/%s lose=%s tiers=%s" % (
        r["name"], r["doing"], s["doing"], r["failure"]["lose"], [q["at"] for q in _ROD["qualities"]]))

def _close(ctx):
    ctx.set_attr(ctx.room, "closed", True)

def _bonus(ctx):
    ctx.set_attr(ctx.room, "bonus", int(ctx.args))

def _last_done(ctx):
    ctx.send(ctx.actor, "DONE:%s" % ctx.get_attr(ctx.room, "last_done", ""))

# stop_later and drop_later: a timer stops whoever is crafting here, or takes their bars, so a test
# can act on a craft in progress while the crafter's own command waits for it.
def _stop_all(ctx):
    for a in ctx.self.actors():
        ctx.stop_task(a)

def _strip_all(ctx):
    for a in ctx.self.actors():
        for i in a.inventory():
            if i.label == _BAR:
                ctx.move_item(i, ctx.self)

def _stop_later(ctx):
    ctx.after(2, _stop_all)

def _drop_later(ctx):
    ctx.after(2, _strip_all)

def _learn(ctx):
    first = craft.teach(ctx, ctx.actor, ctx.args)
    again = craft.teach(ctx, ctx.actor, ctx.args)
    ctx.send(ctx.actor, "learned=%s again=%s knows=%s" % (first, again, craft.knows(ctx, ctx.actor, ctx.args)))

# Worlds copied for other tests pin skill_check and skill_power to 100, so the lab applies the
# template's formulas itself: the craft tests' chances and powers hold under either ruleset.
def _lab_chance(ctx, base):
    return max(5, min(100, ctx.actor.skills.get(ctx.skill.label, 0) + 20))

def _lab_power(ctx, base):
    return 50 + ctx.actor.skills.get(ctx.skill.label, 0) // 2 + (ctx.actor.level - 1) * 2

room("craft_lab",
    name = "The Craft Lab",
    desc = "A bare room kept for the crafting tests.",
    flags = ["dead_end_ok"],
    hooks = {"craft_recipes": _lab_recipes, "craft_check": _lab_check, "craft_done": _lab_done},
    modifiers = {MODIFIER.skill_check: _lab_chance, MODIFIER.skill_power: _lab_power},
    commands = {"bad": _bad, "check": _check, "learn": _learn, "shut": _close, "bonus": _bonus,
                "lastdone": _last_done, "stop_later": _stop_later, "drop_later": _drop_later},
)

# power <skill>: the actor's skill power under this world's rules, which some test runs pin to 100,
# so a test can expect the quality tier that power reaches.
def _power(ctx):
    ctx.send(ctx.actor, "POWER:%d" % ctx.skill_roll(ctx.actor, ctx.args, adjust = 100).power)

room("craft_bare_room",
    name = "The Bare Craft Room",
    desc = "A room with no stations, kept for the crafting tests.",
    flags = ["dead_end_ok"],
    commands = {"power": _power},
)
