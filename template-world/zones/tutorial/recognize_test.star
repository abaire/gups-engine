# Tests for recognizing a skill by watching it cast. The mentor casts spark at Tess; onlookers
# who don't know the skill either read it from their own Intelligence and Wisdom or see an
# anonymous line.

def _scene(t, seed):
    w = t.world(seed = seed, packages = ["//zones/tutorial"])
    mentor = w.spawn("//zones/tutorial:mentor", at = ":arena")
    target = w.spawn_player("Tess", at = ":arena", stats = stats(int = 3, wis = 3))
    dull = w.spawn_player("Dull", at = ":arena", stats = stats(int = 3, wis = 3))
    keen = w.spawn_player("Keen", at = ":arena", stats = stats(int = 18, wis = 18))
    return (w, mentor, target, dull, keen)

def test_unrecognized_spark_is_anonymous(t):
    (w, mentor, target, dull, _) = _scene(t, 1)
    w.act(mentor, "cast spark Tess")
    w.run(pulses = 4)
    t.contains(dull.output(), "The mentor's fiery spark hits Tess.")
    t.contains(target.output(), "The mentor's fiery spark hits you!")

def test_recognized_spark_is_named(t):
    # Keen's chance is 50%; this seed's roll succeeds.
    (w, mentor, target, _, keen) = _scene(t, 2)
    w.act(mentor, "cast spark Tess")
    w.run(pulses = 4)
    out = keen.output()
    t.contains(out, "The mentor casts spark at Tess.")
    t.false("fiery spark hits" in out)

def test_a_player_who_knows_spark_always_sees_its_name(t):
    (w, mentor, _, dull, _) = _scene(t, 1)
    w.set_skill(dull, "//skills/arcane:spark", 10)
    w.act(mentor, "cast spark Tess")
    w.run(pulses = 4)
    t.contains(dull.output(), "The mentor casts spark at Tess.")
