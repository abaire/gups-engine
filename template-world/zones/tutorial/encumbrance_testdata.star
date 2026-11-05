# Test-only fixtures for the encumbrance test in tutorial_test.star: `gups test` and `gups
# scenario` load this file, and the live game never does. Real content can't name what is
# declared here. The tutorial's real items are all light, so the test needs cargo whose weight
# is a share of the carry limit (str * 10 for a new player).

item("heavy_pack",
    name = "a heavy pack",
    desc = "A canvas pack stuffed with sandbags.",
    weight = 80,
)

item("iron_bar",
    name = "an iron bar",
    desc = "A length of cold, dark iron.",
    weight = 15,
)
