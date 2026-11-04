# Tests for the shop library, driven by the fixture in shop.star. Every function named test_*
# is a test and gets `t`. The fixture shopkeeper stands in :shop_test_room with a locked chest.

load("//lib:shop.star", "shop")

def _world(t):
    return t.world(seed = 1, packages = ["//zones/tutorial"])

def _keeper(w, label = "//zones/tutorial:test_shopkeeper"):
    return [a for a in w.room(":shop_test_room").actors() if a.label == label][0]

def _chest(w):
    return [i for i in w.room(":shop_test_room").items() if i.label == "//lib:shop_chest"][0]

def _contents(item):
    out = {}
    for i in item.contents():
        out[i.label] = out.get(i.label, 0) + i.count
    return out

def test_define_validates(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":shop_test_room")
    p.do("bad_label test")
    t.eq(len(w.diagnostics()), 1)
    t.contains(w.diagnostics()[0].message, "relative")
    p.do("bad_markup test")
    t.eq(len(w.diagnostics()), 2)
    t.contains(w.diagnostics()[1].message, "buy_markup")
    p.do("bad_count test")
    t.eq(len(w.diagnostics()), 3)
    t.contains(w.diagnostics()[2].message, "negative")
    t.contains(p.do("good_define"), "id=s markup=120 rate=50 count=2")

def test_open_spawns_locked_chest_and_gives_key(t):
    w = _world(t)
    keeper = _keeper(w)
    chest = _chest(w)
    t.eq(chest.container_state, "locked")
    t.true(keeper.has_item("//lib:shop_chest_key"))
    contents = _contents(chest)
    t.eq(contents.get("//zones/tutorial:test_sword", 0), 3)
    t.eq(contents.get("//lib:gold_coin", 0), 100)

def test_open_reuses_existing_chest(t):
    w = _world(t)
    room = w.room(":shop_test_room")
    first = _keeper(w)
    chest = _chest(w)
    w.damage(first, "hp", pct = 100)
    second = w.spawn(":test_shopkeeper", at = ":shop_test_room")
    chests = [i for i in room.items() if i.label == "//lib:shop_chest"]
    t.eq(len(chests), 1)
    t.eq(chests[0].id, chest.id)
    t.true(second.has_item("//lib:shop_chest_key"))
    t.eq(_contents(chest).get("//zones/tutorial:test_sword", 0), 3)

def test_open_gives_pack_when_carried(t):
    w = _world(t)
    peddler = w.spawn(":test_pack_keeper", at = ":shop_test_room")
    packs = [i for i in peddler.inventory() if i.label == "//lib:shop_pack"]
    t.eq(len(packs), 1)
    pack = packs[0]
    t.eq(pack.location.kind, "carried")
    t.eq(pack.location.actor.id, peddler.id)
    contents = _contents(pack)
    t.eq(contents.get("//zones/tutorial:test_bread"), 5)
    t.eq(contents.get("//lib:gold_coin", 0), 50)
    t.false(peddler.has_item("//lib:shop_chest_key"))

def test_partial_restock_after_buyout(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":shop_test_room")
    t.eq(_contents(_chest(w)).get("//zones/tutorial:test_sword", 0), 3)
    p.do("drain sword")
    t.eq(_contents(_chest(w)).get("//zones/tutorial:test_sword", 0), 0)
    w.run(pulses = 900)   # 1.5 * every (600): one full interval, the rest carries over
    p.do("restock")
    t.eq(_contents(_chest(w)).get("//zones/tutorial:test_sword", 0), 1)
    w.run(pulses = 300)   # 0.5 * every more completes the second interval
    p.do("restock")
    t.eq(_contents(_chest(w)).get("//zones/tutorial:test_sword", 0), 2)

def test_full_shop_does_not_bank_time(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":shop_test_room")
    w.run(pulses = 6000)   # 10 * every, the line stays full
    p.do("restock")        # full: the clock resets to now, banking no time
    p.do("drain sword")
    p.do("restock")        # nothing accrued while the line was full
    t.eq(_contents(_chest(w)).get("//zones/tutorial:test_sword", 0), 0)

def test_no_restock_without_keeper(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":shop_test_room")
    p.do("drain sword")
    t.eq(_contents(_chest(w)).get("//zones/tutorial:test_sword", 0), 0)
    keeper = _keeper(w)
    w.damage(keeper, "hp", pct = 100)   # the keeper dies; time keeps counting
    w.run(pulses = 900)                # 1.5 * every, under the room's respawn, so no keeper
    p.do("restock")                    # no living keeper: nothing is added, the clock keeps running
    t.eq(_contents(_chest(w)).get("//zones/tutorial:test_sword", 0), 0)
    w.spawn(":test_shopkeeper", at = ":shop_test_room")   # a new keeper binds the chest
    p.do("restock")                    # the next catch-up adds what accrued: 900 // 600 = 1
    t.eq(_contents(_chest(w)).get("//zones/tutorial:test_sword", 0), 1)

def test_till_restocks(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":shop_test_room")
    p.do("drain coin")
    t.eq(_contents(_chest(w)).get("//lib:gold_coin", 0), 0)
    w.run(pulses = 600)   # 10 * the till's every (60), amount 10: enough to refill
    p.do("restock")
    t.eq(_contents(_chest(w)).get("//lib:gold_coin", 0), 100)

# --- Phase 4: commands, pricing, the keeper hook and GMCP -------------------------------------

def _shop_player(t, at = ":shop_test_room"):
    w = _world(t)
    p = w.spawn_player("Tess", at = at, gmcp = ["Shop"])
    return w, p

def _coins(p):
    total = 0
    for i in p.inventory():
        if i.label == "//lib:gold_coin":
            total += i.count
    return total

def _carried_count(p, label):
    total = 0
    for i in p.inventory():
        if i.label == label:
            total += i.count
    return total

def test_list_shows_prices_and_sends_gmcp(t):
    w, p = _shop_player(t)
    out = p.do("list")
    t.contains(out, "offers")
    t.contains(out, "a test sword")
    t.contains(out, "60 gold coins")
    msgs = p.gmcp("Shop")
    t.eq(len(msgs), 1)
    t.eq(msgs[0]["package"], "Shop.Stock")
    data = msgs[0]["data"]
    t.eq(data["keeper"], "the test shopkeeper")
    t.eq(data["currency"], "//lib:gold_coin")
    labels = [i["label"] for i in data["items"]]
    t.true("//zones/tutorial:test_sword" in labels)
    sword = [i for i in data["items"] if i["label"] == "//zones/tutorial:test_sword"][0]
    t.eq(sword["price"], 60)
    t.eq(sword["count"], 3)

def test_buy_moves_coins_and_goods(t):
    w, p = _shop_player(t)
    w.spawn("//lib:gold_coin", at = p, count = 100)
    out = p.do("buy sword")
    t.contains(out, "You buy 1 a test sword for 60 gold coins.")
    t.eq(_carried_count(p, "//zones/tutorial:test_sword"), 1)
    t.eq(_coins(p), 40)
    t.eq(_contents(_chest(w)).get("//lib:gold_coin", 0), 160)   # 100 till + 60 paid

def test_buy_unlimited_spawns(t):
    w, p = _shop_player(t)
    w.spawn("//lib:gold_coin", at = p, count = 100)
    out = p.do("buy bread")
    t.contains(out, "You buy 1 a loaf of bread for 6 gold coins.")
    t.eq(_carried_count(p, "//zones/tutorial:test_bread"), 1)
    out = p.do("buy 2 LOAF  bread")   # every word starts some keyword, in any case
    t.contains(out, "You buy 2 a loaf of bread for 12 gold coins.")
    t.eq(_carried_count(p, "//zones/tutorial:test_bread"), 3)
    t.eq(len([i for i in p.inventory() if i.label == "//zones/tutorial:test_bread"]), 1)

def test_buy_more_than_stocked_is_capped(t):
    w, p = _shop_player(t)
    w.spawn("//lib:gold_coin", at = p, count = 1000)
    out = p.do("buy 5 sword")   # only 3 are stored: buys those
    t.contains(out, "only has 3")
    t.contains(out, "You buy 3 a test sword for 180 gold coins.")
    t.eq(_carried_count(p, "//zones/tutorial:test_sword"), 3)
    t.eq(_coins(p), 820)
    out = p.do("buy sword")   # sold out: the line isn't offered
    t.contains(out, "doesn't sell that")
    t.not_contains(p.do("list"), "a test sword")

def test_cannot_afford(t):
    w, p = _shop_player(t)
    w.spawn("//lib:gold_coin", at = p, count = 10)   # a sword costs 60
    out = p.do("buy sword")
    t.contains(out, "You can't afford that.")
    t.eq(_carried_count(p, "//zones/tutorial:test_sword"), 0)

def test_sell_listed_item(t):
    w, p = _shop_player(t)
    w.spawn("//zones/tutorial:test_sword", at = p)
    out = p.do("sell sword")
    t.contains(out, "You sell 1 a test sword for 25 gold coins.")   # 50 * 50 / 100
    t.eq(_coins(p), 25)
    t.eq(_carried_count(p, "//zones/tutorial:test_sword"), 0)

def test_sell_unlisted_item_refused_by_default(t):
    w, p = _shop_player(t)
    w.spawn("//lib:gold_coin", at = p, count = 5)
    out = p.do("sell coin")
    t.contains(out, "isn't interested")
    t.eq(_coins(p), 5)

def test_sell_count_zero_line_accepted(t):
    w, p = _shop_player(t)
    w.spawn("//zones/tutorial:test_axe", at = p)   # listed with count 0: sellable by default
    out = p.do("sell axe")
    t.contains(out, "You sell 1 a test axe for 20 gold coins.")   # 40 * 50 / 100
    t.eq(_coins(p), 20)

def test_till_limits_sales(t):
    w, p = _shop_player(t)
    w.spawn("//zones/tutorial:test_sword", at = p)
    p.do("drain coin")   # empty the till
    out = p.do("sell sword")
    t.contains(out, "can't afford")
    t.eq(_carried_count(p, "//zones/tutorial:test_sword"), 1)

def test_value_reports_price(t):
    w, p = _shop_player(t)
    w.spawn("//zones/tutorial:test_sword", at = p)
    out = p.do("value sword")
    t.contains(out, "would pay 25 gold coins for a test sword.")
    t.eq(_carried_count(p, "//zones/tutorial:test_sword"), 1)   # still carried

def test_hook_changes_price_and_refuses(t):
    w, p = _shop_player(t, at = ":hook_test_room")
    out = p.do("list")
    t.contains(out, "a test sword")
    t.not_contains(out, "bread")   # the hook refuses the bread line
    msgs = p.gmcp("Shop")
    labels = [i["label"] for i in msgs[0]["data"]["items"]]
    t.true("//zones/tutorial:test_sword" in labels)
    t.false("//zones/tutorial:test_bread" in labels)
    sword = [i for i in msgs[0]["data"]["items"] if i["label"] == "//zones/tutorial:test_sword"][0]
    t.eq(sword["price"], 7)
    w.spawn("//lib:gold_coin", at = p, count = 100)
    out = p.do("buy sword")
    t.contains(out, "for 7 gold coins.")   # the hook's price, not the default 60

def test_hook_can_accept_unlisted_item(t):
    w, p = _shop_player(t, at = ":hook_test_room")
    w.spawn("//zones/tutorial:test_axe", at = p)
    out = p.do("sell axe")   # unlisted, but the hook prices it at 2
    t.contains(out, "You sell 1 a test axe for 2 gold coins.")
    t.eq(_coins(p), 2)
    t.contains(p.do("list"), "a test axe")   # the shop now sells what it bought

def test_closed_without_container(t):
    w, p = _shop_player(t)
    p.do("get chest")   # the keeper is here, but its chest isn't on the ground
    t.eq(_carried_count(p, "//lib:shop_chest"), 1)
    out = p.do("list")
    t.contains(out, "The shop is closed.")

def test_stunned_keeper_does_not_trade(t):
    w, p = _shop_player(t)
    w.spawn("//lib:gold_coin", at = p, count = 100)
    w.spawn("//zones/tutorial:test_axe", at = p)
    p.do("stun_keeper")
    refusal = "the test shopkeeper is in no state to trade."
    t.contains(p.do("list"), refusal)
    t.eq(len(p.gmcp("Shop")), 0)
    t.contains(p.do("buy sword"), refusal)
    t.contains(p.do("sell axe"), refusal)
    t.contains(p.do("value axe"), refusal)
    t.eq(_coins(p), 100)
    t.eq(_carried_count(p, "//zones/tutorial:test_sword"), 0)
    t.eq(_carried_count(p, "//zones/tutorial:test_axe"), 1)
    w.run(pulses = 20)   # the stun wears off
    t.contains(p.do("list"), "offers")
    t.contains(p.do("buy sword"), "You buy 1 a test sword")

def test_fighting_keeper_refuses_everyone(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":shop_test_room", gmcp = ["Shop"])
    w.spawn("//lib:gold_coin", at = p, count = 100)
    w.spawn("//zones/tutorial:test_axe", at = p)
    q = w.spawn_player("Ash", at = ":shop_test_room", gmcp = ["Shop"])
    w.spawn("//lib:gold_coin", at = q, count = 100)
    q.do("fight_keeper")
    refusal = "the test shopkeeper is busy fighting."
    for a in (p, q):
        t.contains(a.do("list"), refusal)
        t.contains(a.do("buy sword"), refusal)
        t.contains(a.do("sell axe"), refusal)
        t.contains(a.do("value axe"), refusal)
    t.eq(len(p.gmcp("Shop")), 0)   # list sends no Shop GMCP while the keeper fights
    t.eq(_coins(p), 100)
    t.eq(_carried_count(p, "//zones/tutorial:test_sword"), 0)
    t.eq(_carried_count(p, "//zones/tutorial:test_axe"), 1)
    q.do("calm_keeper")
    t.contains(p.do("list"), "offers")
    t.contains(p.do("buy sword"), "You buy 1 a test sword")
    t.contains(q.do("buy sword"), "You buy 1 a test sword")   # the former attacker trades too

def test_fighting_keeper_keeps_restocking(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":shop_test_room")
    q = w.spawn_player("Ash", at = ":shop_test_room")
    p.do("drain sword")
    t.eq(_contents(_chest(w)).get("//zones/tutorial:test_sword", 0), 0)
    w.run(pulses = 600)   # one full interval (every = 600); no fight yet
    q.do("fight_keeper")
    t.true(_keeper(w).fighting())   # the keeper is engaged
    p.do("restock")   # catch-up runs while the keeper is present, fight or no fight
    t.eq(_contents(_chest(w)).get("//zones/tutorial:test_sword", 0), 1)
    q.do("calm_keeper")

def test_keeper_trades_again_after_attacker_flees(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":shop")
    q = w.spawn_player("Ash", at = ":shop")
    keeper = [a for a in w.room(":shop").actors() if a.label == "//zones/tutorial:shopkeeper"][0]
    q.do("kill shopkeeper")
    t.contains(p.do("list"), "the shopkeeper is busy fighting.")
    for _ in range(20):   # flee can fail; the seed makes the number of tries fixed
        if q.room.label != "//zones/tutorial:shop":
            break
        q.do("flee")
    t.eq(q.room.label, "//zones/tutorial:armory")   # the shop's only exit
    t.eq(len(keeper.fighting()), 0)
    t.contains(p.do("list"), "the shopkeeper offers:")
    t.contains(q.do("list"), "There's no shop here.")   # the attacker is next door
    q.do("east")
    t.contains(q.do("list"), "the shopkeeper offers:")

def test_capacity_counts_each_unstacked_item(t):
    w = _world(t)
    keeper = w.spawn(":test_small_keeper", at = ":shop_test_room")
    chest = [i for i in w.room(":shop_test_room").items()
             if i.label == "//lib:shop_chest" and i.id != _chest(w).id][0]
    contents = _contents(chest)
    t.eq(contents.get("//zones/tutorial:test_sword", 0), 3)   # three entries, one sword each
    t.eq(contents.get("//lib:gold_coin", 0), 0)               # no entry left for the till

def test_no_shop_here(t):
    w, p = _shop_player(t, at = ":gate")   # the gate has no keeper
    out = p.do("list")
    t.contains(out, "There's no shop here.")

# --- Phase 5: the tutorial zone's shopkeeper ---------------------------------------------------
# The shopkeeper stands in the shop room off the armory. It sells copper rings and wooden
# shields from a locked chest and healing draughts without limit. Its `shop_price` hook
# discounts buyers and pays more to customers with charisma above 10, and never buys stolen
# goods.

def _shop_chest(w):
    return [i for i in w.room(":shop").items() if i.label == "//lib:shop_chest"][0]

def test_shopkeeper_sells_with_charisma_discount(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":shop", gmcp = ["Shop"], stats = stats(cha = 15))
    w.spawn("//lib:gold_coin", at = p, count = 100)
    out = p.do("list")
    t.contains(out, "a copper ring 16 gold coins (3 left)")   # 18 discounted by 10%
    q = w.spawn_player("Max", at = ":shop")   # default charisma: no discount
    t.contains(q.do("list"), "a copper ring 18 gold coins (3 left)")
    out = p.do("buy ring")
    t.contains(out, "You buy 1 a copper ring for 16 gold coins.")
    t.eq(_coins(p), 84)
    t.eq(_carried_count(p, "//zones/tutorial:copper_ring"), 1)

def test_shopkeeper_refuses_stolen_goods(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":shop")
    w.spawn("//zones/tutorial:stolen_ring", at = p)   # tagged "stolen" by its prototype
    out = p.do("sell ring")
    t.contains(out, "I don't deal in stolen goods.")
    t.true(p.has_item(":stolen_ring"))   # still carried, unsold

def test_burglary_takes_stock(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":shop", gmcp = ["Shop"])
    w.spawn("//lib:shop_chest_key", at = p)
    p.do("unlock chest")
    p.do("open chest")
    p.do("get 3 ring from chest")   # all the stored rings
    t.eq(_carried_count(p, "//zones/tutorial:copper_ring"), 3)
    out = p.do("list")   # the keeper's stock is gone, so the line isn't offered
    t.not_contains(out, "a copper ring")
    t.contains(out, "a wooden shield")
    msgs = p.gmcp("Shop")
    labels = [i["label"] for i in msgs[0]["data"]["items"]]
    t.false("//zones/tutorial:copper_ring" in labels)
