# Test fixture for the look tests, driven by look_test.star. The room has no exits, and the mob and
# item deliberately have no desc, to exercise the "nothing special" fallback. This is a test
# fixture, so it loads only when the world is loaded for tests.

room("look_test_room",
    name = "The Look Test Room",
    desc = "A bare room kept for the look tests.",
    flags = ["dead_end_ok"],
)

mob("test_plain_mob",
    name = "the plain mob",
    keywords = ["plain"],
    vitals = vitals(hp = 20),
)

item("test_plain_item",
    name = "a plain stone",
    keywords = ["stone"],
)
