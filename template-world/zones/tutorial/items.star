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
    stat_deltas = stats(con = 1),
)

def _sure_footed(ctx, base):
    # Modifiers see the same ctx as the rules export, so the boots can check the room.
    # In a sticky room they cancel the rules' doubled footwork lag; elsewhere they change nothing.
    return base // 2 if "sticky_floor" in ctx.room.tags else base

item("tar_walker_boots",
    name = "a pair of tar-walker boots",
    keywords = ["boots"],
    desc = "Broad, flat-soled boots that spread your weight over the muck.",
    kind = "armor",
    wear = "feet",
    # Lag modifiers take the pulses the rules export returned and return new pulses.
    modifiers = {
        MODIFIER.advance_lag: _sure_footed,
        MODIFIER.retreat_lag: _sure_footed,
    },
)

item("healing_draught",
    name = "a healing draught",
    keywords = ["draught", "potion"],
    kind = "consumable",     # free-form: the engine gives kind no meaning
    charges = 1,             # used up after one drink; empty = "destroy" is the default
    use_effect = heal(dice = "2d4+6", vital = "hp"),
    value = 30,              # the shop's base price, before its margins
)

item("copper_ring",
    name = "a copper ring",
    desc = "A simple copper band that feels warm to the touch.",
    wear = "ring",
    resist_deltas = resists(fire = 2),
    value = 15,              # the shop buys and sells it
)

item("wooden_shield",
    name = "a wooden shield",
    desc = "A round wooden buckler bound in iron.",
    kind = "armor",
    wear = "offhand",
    stat_deltas = stats(armor = 2),
    value = 20,              # the shop buys and sells it
)

item("dagger",
    name = "a dagger",
    desc = "A sharp iron dagger with a leather-wrapped hilt.",
    kind = "melee",
    wear = "hand",
    dice = "1d4",
    damage_type = "pierce",
)

item("greatsword",
    name = "a greatsword",
    desc = "A massive two-handed sword with a broad double-edged blade.",
    kind = "melee",
    wear = "hand",
    slot_cost = 2,
    dice = "2d6",
    damage_type = "slash",
)

item("amulet",
    name = "an amulet",
    desc = "A carved stone amulet humming with vitality.",
    wear = "neck",
    max_vital_deltas = vitals(hp = 5),
)
