# Tests for the crafting library, driven by the fixture in craft_testdata.star. The lab room, a
# ground anvil and a carried kit are stations.

_BAR = "//zones/tutorial:test_bar"

def _world(t):
    return t.world(seed = 1, packages = ["//zones/tutorial"])

def _lab(t, at = ":craft_lab"):
    w = _world(t)
    p = w.spawn_player("Tess", at = at, gmcp = ["Craft"])
    return w, p

def test_constructors_refuse_bad_arguments(t):
    w, p = _lab(t)
    cases = [
        ("need_none", "exactly one of label, tag or kind"),
        ("need_two", "exactly one of label, tag or kind"),
        ("need_relative", "relative"),
        ("need_tag", "need(tag)"),
        ("need_count", "need(count)"),
        ("need_lose", "need(lose)"),
        ("need_pct", "need(lose)"),
        ("make_count", "make(count)"),
        ("improve_empty", "at least one effect"),
        ("improve_applies", "improve(applies)"),
        ("failure_lose", "failure(lose)"),
        ("quality_at", "quality(at)"),
        ("recipe_id", "recipe(id)"),
        ("recipe_time", "recipe(time)"),
        ("recipe_target", "needs a target"),
        ("recipe_make_target", "takes no target"),
        ("recipe_charges", "only tools spend charges"),
        ("recipe_tiers", "two tiers at 5"),
        ("recipe_task_name", "names the craft's task"),
    ]
    for i, (case, message) in enumerate(cases):
        p.do("bad " + case)
        t.eq(len(w.diagnostics()), i + 1)
        t.contains(w.diagnostics()[i].message, message)

def test_recipe_defaults(t):
    w, p = _lab(t)
    t.contains(p.do("check"), "name=hone edge doing=honing/smelting lose=50% tiers=[95, 75]")

def test_nothing_to_craft_with(t):
    w, p = _lab(t, at = ":craft_bare_room")
    t.contains(p.do("recipes"), "There's nothing to craft with here.")

def test_recipes_lists_ready_and_missing(t):
    w, p = _lab(t)
    w.spawn(_BAR, at = p, count = 1)
    w.spawn("//zones/tutorial:test_mallet", at = p)
    out = p.do("recipes")
    t.contains(out, "The Craft Lab:")
    t.contains(out, " rod: a test rod (forge) — still missing: a test bar, a wood")
    t.contains(out, " plain_rod: a better rod (craft) — ready")
    t.true("secret_rod" not in out)
    t.true("polish" not in out)

def test_stations_on_the_ground_and_carried(t):
    w, p = _lab(t)
    w.spawn("//zones/tutorial:test_anvil", at = ":craft_lab")
    w.spawn("//zones/tutorial:test_kit", at = p)
    w.spawn("//zones/tutorial:test_junk", at = p)
    out = p.do("recipes")
    t.contains(out, "a test anvil:\n anvil_rod: an anvil rod (craft) — still missing: 5 test bars, an anvil tongs")
    t.contains(out, "a test kit:\n polish: polish (polish) — still missing: a wood")
    t.eq(out.count("plain_rod"), 1)   # the lab offers it first

def test_charged_tools(t):
    w, p = _lab(t)
    w.spawn(_BAR, at = p, count = 2)
    w.spawn("//zones/tutorial:test_wood", at = p)
    w.spawn("//zones/tutorial:test_hammer", at = p)
    t.contains(p.do("recipes"), " rod: a test rod (forge) — ready")
    p.do("drop hammer")
    t.contains(p.do("recipes"), "still missing: a hammer")

def test_learned_recipes(t):
    w, p = _lab(t)
    t.true("secret_rod" not in p.do("recipes"))
    t.contains(p.do("learn secret_rod"), "learned=True again=False knows=True")
    t.contains(p.do("recipes"), " secret_rod: secret rod (smelt) — still missing: 3 test bars")

def test_recipes_gmcp(t):
    w, p = _lab(t)
    w.spawn(_BAR, at = p, count = 1)
    p.do("recipes")
    msgs = p.gmcp("Craft.Recipes")
    t.eq(len(msgs), 1)
    recipes = msgs[0]["data"]["recipes"]
    t.eq([r["id"] for r in recipes], ["rod", "plain_rod", "slag", "nails", "tap", "temper", "whet"])
    rod = recipes[0]
    t.eq(rod["station"], "The Craft Lab")
    t.eq(rod["skill"], "//skills/arcane:mend")
    t.eq(rod["time"], 4)
    t.eq(rod["learned"], False)
    t.eq(rod["target"], False)
    t.eq(rod["ready"], False)
    t.eq(rod["needs"], [{"name": "a test bar", "count": 2, "have": 1, "lose": "1d2"},
                        {"name": "a wood", "count": 1, "have": 0, "lose": 1}])
    t.eq(rod["tools"], [{"name": "a hammer", "have": False}])
    t.eq(recipes[1]["skill"], None)
    t.eq(recipes[1]["needs"][0]["lose"], "50%")

# ---- craft ---------------------------------------------------------------------------------------

_MEND = "//skills/arcane:mend"
_COAL = "//zones/tutorial:test_coal"

def _rod_kit(w, p, bars = 2):
    w.spawn(_BAR, at = p, count = bars)
    w.spawn("//zones/tutorial:test_wood", at = p)
    return w.spawn("//zones/tutorial:test_hammer", at = p)

def _count(p, label):
    total = 0
    for i in p.inventory():
        if i.label == label:
            total += i.count
    return total

def _named(p, name):
    return [i for i in p.inventory() if i.name == name]

def _result(p):
    msgs = p.gmcp("Craft.Result")
    return msgs[-1]["data"] if msgs else None

def test_craft_refusals(t):
    w, p = _lab(t)
    t.contains(p.do("craft"), "Craft what? Type `recipes` to see what you can make here.")
    t.contains(p.do("craft nothing"), "You don't know how to make that here.")
    t.contains(p.do("craft secret_rod"), "You don't know how to make that here.")
    t.contains(p.do("craft better"), "You still need a test bar.")
    t.contains(p.do("craft rod on blade"), "That recipe doesn't work on an item.")
    t.contains(p.do("craft temper"), "Temper what?")
    t.contains(p.do("craft temper on blade"), "You aren't carrying that.")
    w.spawn("//zones/tutorial:test_wood", at = p)
    t.contains(p.do("craft temper on wood"), "You can't temper a piece of test wood.")

def test_fighting_refusal(t):
    w, p = _lab(t)
    w.spawn("//zones/tutorial:craft_dummy", at = ":craft_lab")
    p.do("kill dummy")
    t.contains(p.do("craft plain_rod"), "You can't do that while fighting.")

def test_make_success_reaches_the_fine_tier(t):
    w, p = _lab(t)
    w.set_skill(p, _MEND, 50)
    hammer = _rod_kit(w, p, bars = 3)
    w.force_rng(ints = [60])   # chance 50 + 20 - 10 = 60; power 50 + 25 = 75
    out = p.do("craft rod")
    t.contains(out, "You begin to forge a test rod.")
    t.contains(out, "You forge a fine test rod.")
    t.eq(_count(p, _BAR), 1)
    t.eq(_count(p, "//zones/tutorial:test_wood"), 0)
    t.eq(len(_named(p, "a fine test rod")), 1)
    t.eq(hammer.charges, 2)
    t.eq(_result(p), {"recipe": "rod", "outcome": "success", "quality": "a fine test rod",
                      "items": ["a fine test rod"], "lost": []})

def test_masterwork_tier_names_tags_and_enhances(t):
    w, p = _lab(t)
    p = w.spawn_player("Mae", at = ":craft_lab", level = 11, gmcp = ["Craft"])
    w.set_skill(p, _MEND, 50)
    _rod_kit(w, p)
    w.force_rng(ints = [60])   # power 50 + 25 + 20 = 95
    t.contains(p.do("craft rod"), "You forge a masterwork test rod.")
    rod = _named(p, "a masterwork test rod")[0]
    t.true(rod.has_tag("masterwork"))
    t.eq([a.name for a in rod.affects], ["quality_95"])

def test_no_tier_below_75(t):
    w, p = _lab(t)
    _rod_kit(w, p)
    w.force_rng(ints = [10])   # prof 0: chance 20 - 10 = 10; power 50
    t.contains(p.do("craft rod"), "You forge a test rod.")
    t.eq(_result(p)["quality"], None)

def test_skillless_product_stacks(t):
    w, p = _lab(t)
    w.spawn(_BAR, at = p)
    w.spawn("//zones/tutorial:test_nails", at = p, count = 2)
    t.contains(p.do("craft nails"), "You craft 3 test nails.")
    nails = [i for i in p.inventory() if i.label == "//zones/tutorial:test_nails"]
    t.eq([n.count for n in nails], [5])

def test_failure_loses_dice_and_ints_and_makes_the_flawed_product(t):
    w, p = _lab(t)
    w.set_skill(p, _MEND, 50)
    hammer = _rod_kit(w, p)
    w.force_rng(ints = [61, 2])   # the roll fails at 60; 1d2 loses 2 bars
    out = p.do("craft rod")
    t.contains(out, "You fail to forge a test rod. You ruin 2 test bars, a piece of test wood.")
    t.eq(_count(p, _BAR), 0)
    t.eq(len(_named(p, "a bent test rod")), 1)
    t.eq(hammer.charges, 2, "tools spend charges either way")
    t.eq(_result(p), {"recipe": "rod", "outcome": "fail", "quality": None,
                      "items": ["a bent test rod"],
                      "lost": [{"name": "a test bar", "count": 2},
                               {"name": "a piece of test wood", "count": 1}]})

def test_failure_default_percent_all_and_zero(t):
    w, p = _lab(t)
    w.spawn(_BAR, at = p, count = 3)
    w.spawn("//zones/tutorial:test_wood", at = p)
    w.spawn(_COAL, at = p)
    w.force_rng(ints = [100])
    t.contains(p.do("craft slag"), "The mix runs to slag. You ruin 2 test bars, a piece of test wood.")
    t.eq(_count(p, _BAR), 1)
    t.eq(_count(p, _COAL), 1)

def test_tool_charges_run_out_and_unlimited_tools_spend_nothing(t):
    w, p = _lab(t)
    w.spawn(_BAR, at = p, count = 2)
    hammer = w.spawn("//zones/tutorial:test_hammer", at = p)
    p.do("craft tap")
    t.eq(hammer.charges, 0)
    t.contains(p.do("recipes"), " tap: tap (craft) — still missing: a hammer")
    mallet = w.spawn("//zones/tutorial:test_mallet", at = p)
    t.contains(p.do("craft tap"), "You craft a test nail.")
    t.eq(mallet.charges, None)

def test_improve_with_an_affect_tags_and_unless_tag(t):
    w, p = _lab(t)
    w.spawn("//zones/tutorial:test_kit", at = p)
    w.spawn("//zones/tutorial:test_wood", at = p, count = 1)
    w.spawn("//zones/tutorial:test_wood", at = p, count = 1)
    blade = w.spawn("//zones/tutorial:test_blade", at = p)
    p.do("wield blade")
    t.contains(p.do("craft polish on blade"), "You polish a test blade.")
    t.true(blade.has_tag("polished"))
    t.eq([a.name for a in blade.affects], ["polished"])
    t.contains(p.do("craft polish on blade"), "A test blade is already polished.")
    t.eq(_count(p, "//zones/tutorial:test_wood"), 1)

def test_improve_with_a_variant(t):
    w, p = _lab(t)
    w.spawn(_COAL, at = p)
    blade = w.spawn("//zones/tutorial:test_blade", at = p)
    t.contains(p.do("craft temper on blade"), "You temper a tempered test blade.")
    t.eq(blade.label, "//zones/tutorial:test_tempered_blade")
    t.true(blade.has_tag("tempered"))
    t.eq(_result(p)["items"], ["a tempered test blade"])

def test_a_target_is_not_spent_as_its_own_input(t):
    w, p = _lab(t)
    w.spawn("//zones/tutorial:test_blade", at = p)
    t.contains(p.do("craft whet on blade"), "You still need a test blade.")
    t.eq(_count(p, "//zones/tutorial:test_blade"), 1)
    w.spawn("//zones/tutorial:test_blade", at = p)
    t.contains(p.do("craft whet on blade"), "You whet a test blade.")
    left = [i for i in p.inventory() if i.label == "//zones/tutorial:test_blade"]
    t.eq(len(left), 1)
    t.true(left[0].has_tag("whetted"))

def test_learned_recipe_crafts_once_taught(t):
    w, p = _lab(t)
    w.spawn(_BAR, at = p, count = 3)
    p.do("learn secret_rod")
    t.contains(p.do("craft secret_rod"), "You smelt a test rod.")

def test_craft_check_refuses_and_adjusts(t):
    w, p = _lab(t)
    _rod_kit(w, p)
    w.set_attr(p.room, "bonus", 40)
    w.force_rng(ints = [50])   # prof 0: chance 20 - 10 + 40 = 50
    t.contains(p.do("craft rod"), "You forge a test rod.")
    t.contains(p.do("lastdone"), "DONE:rod:success:[\"a test rod\"]")
    w.set_attr(p.room, "closed", True)
    w.spawn(_BAR, at = p)
    t.contains(p.do("craft plain_rod"), "The lab is closed.")
    t.eq(_count(p, _BAR), 1)

def test_craft_done_hears_a_failure(t):
    w, p = _lab(t)
    w.spawn(_BAR, at = p, count = 3)
    w.spawn("//zones/tutorial:test_wood", at = p)
    w.spawn(_COAL, at = p)
    w.force_rng(ints = [100])
    p.do("craft slag")
    t.contains(p.do("lastdone"), "DONE:slag:fail:[]")

def test_a_stopped_craft_consumes_nothing(t):
    w, p = _lab(t)
    w.spawn(_BAR, at = p)
    p.do("stop_later")
    p.do("craft plain_rod")
    t.eq(_count(p, _BAR), 1)
    t.eq(len(_named(p, "a test rod")), 0)
    t.eq(_result(p), {"recipe": "plain_rod", "outcome": "stopped", "quality": None, "items": [],
                      "lost": []})

def test_items_gone_mid_craft(t):
    w, p = _lab(t)
    w.spawn(_BAR, at = p)
    p.do("drop_later")
    t.contains(p.do("craft plain_rod"), "You no longer have what you need.")
    t.eq(_result(p)["outcome"], "stopped")
