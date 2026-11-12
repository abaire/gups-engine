# The smithy: a crafting workshop east of the shop. Three kinds of station show how
# //lib:craft.star finds recipes (docs/tutorials/12_crafting.md):
#
#   * the room itself, whose forge offers smithing_recipes.star's BASIC and ADVANCED recipes;
#   * the whetstone, a heavy item on the floor offering `hone`;
#   * the repair kit, an item the crafter carries offering `patch`.
#
# Type `recipes` to see what you can make, `craft smelt` or `craft forge dagger` to make it, and
# `craft hone on dagger` to improve something you hold. The forge must be stoked first.

load("//lib:craft.star", "craft")
load("//lib:trainer.star", "trainer")
load(":smithing_recipes.star", "ADVANCED", "BASIC")

_Z = "//zones/tutorial:"

# ---- Materials and tools -----------------------------------------------------------------------

item("iron_ore", name = "a lump of iron ore", plural = "lumps of iron ore",
     desc = "A rusty lump of ore, heavy for its size.", keywords = ["ore", "iron"],
     stackable = True, tags = ["ore"], value = 1)
item("charcoal", name = "a piece of charcoal", plural = "pieces of charcoal",
     desc = "Black, light and crumbly: forge fuel.", keywords = ["charcoal"],
     stackable = True, tags = ["fuel"], value = 1)
item("iron_bar", name = "an iron bar", plural = "iron bars", desc = "A bar of smelted iron.",
     keywords = ["bar", "iron"], stackable = True, tags = ["metal"], value = 4)
item("grit", name = "a pinch of grit", plural = "pinches of grit",
     desc = "Fine abrasive sand for sharpening.", keywords = ["grit", "sand"], stackable = True,
     tags = ["grit"])
item("leather_scrap", name = "a leather scrap", plural = "leather scraps",
     desc = "An offcut of tanned leather.", keywords = ["leather", "scrap"], stackable = True,
     tags = ["leather"])
item("smith_hammer", name = "a smith's hammer", desc = "A heavy hammer with a worn handle.",
     keywords = ["hammer"], tags = ["hammer"], weight = 3)
# Every patch spends one of the awl's charges. At 0 it stays in the pack, but no longer matches a
# need that asks for charges, until something recharges it.
item("awl", name = "an awl", desc = "A pointed tool for punching leather.", keywords = ["awl"],
     tags = ["awl"], charges = 5, empty = "keep")

# ---- Products ----------------------------------------------------------------------------------

item("iron_dagger", name = "an iron dagger", desc = "A plain, serviceable dagger.",
     keywords = ["dagger", "iron"], kind = "melee", wear = "wield", dice = "1d4", value = 12,
     variants = {"tempered": ":tempered_dagger"})
item("tempered_dagger", name = "a tempered dagger", desc = "A dagger with a blue-grey sheen.",
     keywords = ["dagger", "tempered"], kind = "melee", wear = "wield", dice = "1d5", value = 20)
item("bent_dagger", name = "a bent dagger", desc = "A dagger that came out of the forge crooked.",
     keywords = ["dagger", "bent"], kind = "melee", wear = "wield", dice = "1d2", value = 2)

# ---- Stations on items -------------------------------------------------------------------------

_HONE = craft.recipe("hone",
    name = "an edge", verb = "hone", target = craft.need(kind = "melee"),
    inputs = [craft.need(tag = "grit", name = "pinch of grit")], time = 6,
    result = craft.improve(affect = "honed", stat_deltas = {"dex": 1}, add_tags = ["honed"],
                           unless_tag = "honed"),
)

_PATCH = craft.recipe("patch",
    name = "a patch", verb = "patch", target = craft.need(kind = "armor"),
    inputs = [craft.need(_Z + "leather_scrap")],
    tools = [craft.need(tag = "awl", charges = 1)], time = 6,
    result = craft.improve(affect = "patched", stat_deltas = {"armor": 1}, add_tags = ["patched"],
                           unless_tag = "patched"),
)

def _whetstone_recipes(ctx):
    return [_HONE]

def _kit_recipes(ctx):
    return [_PATCH]

# Too heavy to carry: it is a station only where it stands.
item("whetstone", name = "a whetstone", desc = "A grinding wheel on a heavy wooden frame.",
     keywords = ["whetstone", "wheel"], weight = 500,
     hooks = {"craft_recipes": _whetstone_recipes})
item("repair_kit", name = "a repair kit", desc = "A leather roll of needles and thread.",
     keywords = ["kit", "repair"], hooks = {"craft_recipes": _kit_recipes})

def _read_notes(ctx):
    # Learned recipes are hidden until craft.teach records them on the actor.
    if craft.teach(ctx, ctx.actor, "temper_dagger"):
        ctx.send(ctx.actor, "You study the notes and learn how to temper a dagger.")
    else:
        ctx.send(ctx.actor, "The notes tell you nothing you don't already know.")
    ctx.send(ctx.actor, "The brittle notes crumble to dust.")
    ctx.destroy(ctx.self)

item("tempering_notes", name = "some tempering notes", desc = "Scorched pages of a smith's notes.",
     keywords = ["notes", "tempering"], on_use = _read_notes)

# ---- The forge ---------------------------------------------------------------------------------

def _forge_recipes(ctx):
    # A station may offer any number of recipe modules.
    return BASIC + ADVANCED

def _forge_check(ctx, crafter, recipe, target, adjust):
    # Run once when a craft starts. None keeps the library's default, a string refuses with it,
    # and an int replaces the roll's adjustment (minus the recipe's difficulty).
    if recipe not in ("forge_dagger", "temper_dagger"):
        return None
    if not ctx.get_attr(ctx.self, "forge_lit", False):
        return "The forge is cold. Stoke it first."
    return adjust + 10    # a roaring forge makes the work easier

def _cool(ctx):
    ctx.del_attr(ctx.self, "forge_lit")
    ctx.echo(ctx.self, "The forge's fire dies down to embers.")

def _stoke(ctx):
    fuel = [i for i in ctx.actor.inventory() if i.label == _Z + "charcoal"]
    if not fuel:
        ctx.send(ctx.actor, "You need charcoal to stoke the forge.")
        return
    ctx.destroy(fuel[0], count = 1)
    if not ctx.get_attr(ctx.room, "forge_lit", False):
        ctx.after(300, _cool)
    ctx.set_attr(ctx.room, "forge_lit", True)
    ctx.send(ctx.actor, "You stoke the forge and it roars to life.")
    ctx.echo(ctx.room, "%s stokes the forge." % ctx.actor.name, exclude = [ctx.actor])

# What the smithy keeps on hand, restocked on load and on each reset when it has run out.
_STOCK = [("iron_ore", 4), ("charcoal", 4), ("grit", 2), ("leather_scrap", 2), ("smith_hammer", 1),
          ("whetstone", 1), ("repair_kit", 1), ("awl", 1), ("tempering_notes", 1)]

def _stock_smithy(ctx):
    for name, count in _STOCK:
        if not [i for i in ctx.self.items() if i.label == _Z + name]:
            ctx.spawn(":" + name, ctx.self, count = count)

room("smithy",
    name = "The Smithy",
    desc = "Heat rolls off a stone forge. A whetstone stands in the corner, and racks of tools line the walls.",
    size = SIZE.normal,
    exits = [exit(DIR.west, to = ":shop")],
    spawns = [spawn(":smith")],
    hooks = {"craft_recipes": _forge_recipes, "craft_check": _forge_check},
    commands = {"stoke": _stoke},
    on_load = _stock_smithy,
    on_reset = _stock_smithy,
)

# ---- The smith ---------------------------------------------------------------------------------

SMITH = trainer.define(
    id = "smith",
    teaches = [trainer.teach("//skills/crafts:smithing", up_to = 50, gain = 10)],
)

def _smith_open(ctx):
    trainer.open(ctx, ctx.self, SMITH)

mob("smith",
    name = "the smith",
    keywords = ["smith"],
    desc = "A broad-shouldered smith in a scorched apron, hammer in hand.",
    vitals = vitals(hp = 80),
    factions = ["//lib:townsfolk"],
    behaviors = [sentinel()],
    skills = {"//skills/crafts:smithing": 100},
    on_spawn = _smith_open,
)
