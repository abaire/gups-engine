# Items. An item's label is //zones/tutorial:<name>; within this package ":<name>" is enough.

load("//lib:quest.star", "quest")
load(":helpers.star", "FIRST_STEPS")

item("brass_key",
    name = "a brass key",
    # Keywords are what players type: "get key". They default to the words of the name,
    # minus articles, so this line only makes the default explicit.
    keywords = ["brass", "key"],
    desc = "A small brass key stamped with an anvil.",
)

def _on_get_sword(ctx):
    # Item hooks run with ctx.self as the item and ctx.actor as whoever is acting on it.
    # ctx.send writes to one player; mobs and link-dead players see nothing.
    ctx.send(ctx.actor, "The practice sword feels light in your hand.")
    quest.advance(ctx, ctx.actor, FIRST_STEPS, from_step = 1)

item("practice_sword",
    name = "a practice sword",
    desc = "A blunt wooden sword, good for nothing but practice.",
    kind = "melee",          # melee | ranged | armor | consumable | anything you like
    wield = "wield",         # the equipment slot: `wield sword` puts it here
    dice = "1d6",            # weapon damage
    damage_type = "bludgeon",
    weight = 3,
    # Hooks are attached by catalog name. A typo such as on_gett is a load error that
    # suggests the right name.
    on_get = _on_get_sword,
)

item("leather_cap",
    name = "a leather cap",
    desc = "A scuffed leather cap. It smells of the last person who wore it.",
    kind = "armor",
    wear = "head",           # `wear cap`; slot names come from the rules' equipment_slots()
    stat_deltas = {"con": 1},
)

def _drink(ctx):
    # on_use runs for `use draught`. Return ctx.deny(msg) from a before_use hook to refuse.
    # The engine doesn't use items up by itself: the hook destroys the draught.
    ctx.heal(ctx.actor, 10)
    ctx.send(ctx.actor, "You drink the draught. Warmth spreads through you.")
    ctx.destroy(ctx.self)

item("healing_draught",
    name = "a healing draught",
    keywords = ["draught", "potion"],
    kind = "consumable",
    on_use = _drink,
)
