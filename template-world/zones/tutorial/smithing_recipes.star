# The smithy's recipes, kept apart from the room so other stations can offer them too: a station's
# craft_recipes hook returns any mix of recipe lists (BASIC + ADVANCED). See
# docs/tutorials/12_crafting.md.

load("//lib:craft.star", "craft")

_Z = "//zones/tutorial:"
_SMITHING = "//skills/crafts:smithing"

BASIC = [
    # No skill: smelting always works, but a failure can't happen either.
    craft.recipe("smelt",
        name = "an iron bar", verb = "smelt",
        inputs = [craft.need(_Z + "iron_ore", count = 2, lose = "1d2"),
                  craft.need(_Z + "charcoal", lose = 1)],
        time = 6,
        result = craft.make(_Z + "iron_bar"),
    ),
    # A smithing roll: a failure loses half the bars and leaves a bent dagger; a strong roll
    # reaches a quality tier, which renames the dagger and may enhance it.
    craft.recipe("forge_dagger",
        name = "an iron dagger", verb = "forge",
        inputs = [craft.need(_Z + "iron_bar", count = 2, lose = "50%")],
        tools = [craft.need(tag = "hammer")],
        skill = _SMITHING, difficulty = 10, time = 12, lag = 2,
        failure = craft.failure(make = craft.make(_Z + "bent_dagger")),
        qualities = [
            craft.quality(at = 95, name = "a masterwork iron dagger", stat_deltas = {"dex": 1},
                          add_tags = ["masterwork"]),
            craft.quality(at = 75, name = "a fine iron dagger"),
        ],
        result = craft.make(_Z + "iron_dagger"),
    ),
]

ADVANCED = [
    # Learned from the tempering notes. It improves a dagger the smith holds into its tempered
    # variant rather than making a new item.
    craft.recipe("temper_dagger",
        name = "an iron dagger", verb = "temper", learned = True,
        target = craft.need(label = _Z + "iron_dagger"),
        inputs = [craft.need(_Z + "charcoal")],
        skill = _SMITHING, difficulty = 20, time = 8,
        result = craft.improve(variant = "tempered", add_tags = ["tempered"],
                               unless_tag = "tempered"),
    ),
]
