# Test-only fixtures for the encumbrance tests in tutorial_test.star: `gups test` and `gups
# scenario` load this file, and the live game never does. Real content can't name what is
# declared here. The tutorial's real items are all light, so the tests need cargo whose weight
# is a share of the carry limit (str * 10 for a new player): heavy_pack + sack is 88, level 2;
# add pouch, 94, level 3; add crate, 104, level 4.

item("heavy_pack",
    name = "a heavy pack",
    desc = "A canvas pack stuffed with sandbags.",
    weight = 80,
)

item("iron_weight",
    name = "an iron bar",
    desc = "A length of cold, dark iron.",
    weight = 15,
)

item("sack",
    name = "a burlap sack",
    desc = "A sack of iron shavings, all grit and weight.",
    weight = 8,
)

item("pouch",
    name = "a lead pouch",
    desc = "A pouch of lead shot, heavy for its size.",
    weight = 6,
)

item("crate",
    name = "an iron crate",
    desc = "A crate rivetted shut with iron fittings.",
    weight = 10,
)
