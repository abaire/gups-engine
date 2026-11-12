# A walk through the smithy (smithy.star): smelt ore into bars, forge and hone a dagger, learn to
# temper it from the notes, and patch a cap with the repair kit.

_SMITHING = "//skills/crafts:smithing"

def _fine_or_better(w):
    """The dagger a smith with smithing 50 at level 1 forges: power 75 is "fine". Some test runs
    pin skill_power to 100, which reaches "masterwork"."""
    probe = w.spawn_player("Pat", at = ":craft_bare_room")
    w.set_skill(probe, _SMITHING, 50)
    power = int(probe.do("power " + _SMITHING).strip().split(":")[1])
    return "a masterwork iron dagger" if power >= 95 else "a fine iron dagger"

def test_smithy_walkthrough(t):
    w = t.world(seed = 1, packages = ["//zones/tutorial"])
    dagger_name = _fine_or_better(w)
    p = w.spawn_player("Tess", at = ":shop", gmcp = ["Craft"])
    w.set_skill(p, _SMITHING, 50)
    t.contains(p.do("east"), "The Smithy")
    out = p.do("recipes")
    t.contains(out, "The Smithy:")
    t.contains(out, " smelt: an iron bar (smelt) — still missing: 2 lumps of iron ore, a piece of charcoal")
    t.contains(out, "a whetstone:")
    t.true("temper_dagger" not in out)

    p.do("get ore")
    p.do("get charcoal")
    p.do("get hammer")
    t.contains(p.do("craft smelt"), "You smelt an iron bar.")
    t.contains(p.do("craft smelt"), "You smelt an iron bar.")
    t.contains(p.do("craft forge dagger"), "The forge is cold. Stoke it first.")
    t.contains(p.do("stoke"), "You stoke the forge and it roars to life.")
    # chance = 50 + 20 - 10 (difficulty) + 10 (the lit forge) = 70; power 50 + 25 = 75.
    w.force_rng(ints = [70])
    out = p.do("craft forge dagger")
    t.contains(out, "You begin to forge an iron dagger.")
    t.contains(out, "You forge %s." % dagger_name)
    dagger = [i for i in p.inventory() if i.label == "//zones/tutorial:iron_dagger"][0]

    p.do("get grit")
    t.contains(p.do("craft hone on dagger"), "You hone %s." % dagger_name)
    t.true(dagger.has_tag("honed"))
    t.contains(p.do("craft hone on dagger"), "is already honed.")

    p.do("get notes")
    t.contains(p.do("use notes"), "You study the notes and learn how to temper a dagger.")
    w.force_rng(ints = [60])   # chance = 50 + 20 - 20 + 10 = 60
    t.contains(p.do("craft temper on dagger"), "You temper %s." % dagger_name)
    t.eq(dagger.label, "//zones/tutorial:tempered_dagger")

    cap = w.spawn("//zones/tutorial:leather_cap", at = p)
    p.do("get kit")
    p.do("get awl")
    p.do("get leather")
    awl = [i for i in p.inventory() if i.label == "//zones/tutorial:awl"][0]
    t.contains(p.do("craft patch on cap"), "You patch %s." % cap.name)
    t.true(cap.has_tag("patched"))
    t.eq(awl.charges, 4)

    t.contains(p.do("recipes"), " temper_dagger: an iron dagger (temper)")
    t.contains(p.do("cast smithing"), "You practice smithing at a workstation, not by casting.")
