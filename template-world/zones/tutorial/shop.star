# Test fixture for the shop library. It keeps its own room, keeper and stock out of the
# tutorial's walkthrough: the room has no exits and the keeper never wanders. The tests in
# shop_test.star drive it; the rest of the zone ignores it.

load("//lib:shop.star", "shop")

item("test_sword", name = "a test sword", kind = "melee", wield = "wield", dice = "1d6",
     damage_type = "slash", weight = 3, value = 50)
item("test_bread", name = "a loaf of bread", plural = "loaves of bread", stackable = True,
     weight = 1, value = 5)
item("test_axe", name = "a test axe", keywords = ["axe"], kind = "melee", wield = "wield",
     dice = "1d6", damage_type = "slash", weight = 4, value = 40)
item("stolen_ring", name = "a stolen ring", keywords = ["ring"], tags = ["stolen"], value = 15)

# A chest shop: the keeper stands in the room and the chest sits on the ground, locked.
TEST_SHOP = shop.define(
    id = "test_shop",
    stock = [
        shop.stock("//zones/tutorial:test_sword", count = 3, every = 600),
        shop.stock("//zones/tutorial:test_bread"),   # unlimited: spawned on demand, never stored
        shop.stock("//zones/tutorial:test_axe", count = 0),   # listed but never held: sell-only
    ],
    till = shop.till(count = 100, every = 60, amount = 10),
    buy_markup = 120,
    sell_rate = 50,
)

# A carried shop: the peddler carries the pack and has no key, since a pack has no lock.
TEST_PACK_SHOP = shop.define(
    id = "test_pack_shop",
    container = "//lib:shop_pack",
    carried = True,
    key = None,
    stock = [shop.stock("//zones/tutorial:test_bread", count = 5, every = 600)],
    till = shop.till(count = 50),
    capacity = 20,
    max_weight = 100,
)

# A shop whose container holds only three entries: unstacked swords each take one.
SMALL_SHOP = shop.define(
    id = "small_shop",
    stock = [shop.stock("//zones/tutorial:test_sword", count = 5, every = 600)],
    till = shop.till(count = 10),
    capacity = 3,
)

def _test_shop_open(ctx):
    shop.open(ctx, ctx.self, TEST_SHOP)

def _test_pack_open(ctx):
    shop.open(ctx, ctx.self, TEST_PACK_SHOP)

def _test_small_open(ctx):
    shop.open(ctx, ctx.self, SMALL_SHOP)

# Commands that exercise shop.define's validation. Each bad one errors the hook, which the
# tests read back through w.diagnostics(); good_define shows a valid definition's fields.
# They take a "test" argument: a bare name (as the fuzz walker would type it) shows the usage
# instead of raising, so the deliberate script error stays test-only.
def _bad_label(ctx):
    if ctx.args != "test":
        ctx.send(ctx.actor, "Usage: bad_label test")
        return
    shop.define(id = "s", stock = [shop.stock(":sword")])

def _bad_markup(ctx):
    if ctx.args != "test":
        ctx.send(ctx.actor, "Usage: bad_markup test")
        return
    shop.define(id = "s", stock = [shop.stock("//zones/z:sword")], buy_markup = 0)

def _bad_count(ctx):
    if ctx.args != "test":
        ctx.send(ctx.actor, "Usage: bad_count test")
        return
    shop.define(id = "s", stock = [shop.stock("//zones/z:sword", count = -1)])

def _good_define(ctx):
    d = shop.define(id = "s", stock = [shop.stock("//zones/z:sword", count = 2)])
    ctx.send(ctx.actor, "id=%s markup=%d rate=%d count=%d" % (d["id"], d["buy_markup"],
                                                             d["sell_rate"], d["stock"][0]["count"]))

# drain <keyword>: throws away the chest's contents whose keywords match <keyword>, so the
# restock tests can empty a line. restock: runs the library's catch-up on the chest. The chest
# is usually on the floor, but a player may carry it, so the commands look there too.
def _find_chest(ctx):
    for i in ctx.room.items():
        if i.label == "//lib:shop_chest":
            return i
    for a in ctx.room.actors():
        for i in a.inventory():
            if i.label == "//lib:shop_chest":
                return i
    return None

def _drain(ctx):
    chest = _find_chest(ctx)
    if chest == None:
        ctx.send(ctx.actor, "The chest is not here.")
        return
    word = ctx.args
    for i in chest.contents():
        if word in i.keywords:
            ctx.destroy(i)
    ctx.send(ctx.actor, "Drained %s from the chest." % word)

def _restock(ctx):
    chest = _find_chest(ctx)
    if chest == None:
        ctx.send(ctx.actor, "The chest is not here.")
        return
    shop.catch_up(ctx, chest)
    ctx.send(ctx.actor, "The chest catches up.")

# stun_keeper: stuns the fixture shopkeeper for 10 pulses.
def _stun_keeper(ctx):
    for a in ctx.room.actors():
        if a.label == "//zones/tutorial:test_shopkeeper":
            ctx.apply_affect(a, "stun", 10, flags = ["stunned"])
            ctx.send(ctx.actor, "You stun the keeper.")
            return
    ctx.send(ctx.actor, "The keeper is not here.")

# fight_keeper: engages the fixture shopkeeper in combat with the player.
def _fight_keeper(ctx):
    for a in ctx.room.actors():
        if a.label == "//zones/tutorial:test_shopkeeper":
            ctx.start_combat(ctx.actor, a)
            ctx.send(ctx.actor, "You attack the keeper.")
            return
    ctx.send(ctx.actor, "The keeper is not here.")

# calm_keeper: ends the fixture shopkeeper's fights.
def _calm_keeper(ctx):
    for a in ctx.room.actors():
        if a.label == "//zones/tutorial:test_shopkeeper":
            ctx.stop_combat(a)
            ctx.send(ctx.actor, "The keeper calms down.")
            return
    ctx.send(ctx.actor, "The keeper is not here.")

room("shop_test_room",
    name = "The Shop Test Room",
    desc = "A bare room kept for the shop tests.",
    flags = ["dead_end_ok"],
    commands = {"bad_label": _bad_label, "bad_markup": _bad_markup, "bad_count": _bad_count,
                "good_define": _good_define, "drain": _drain, "restock": _restock,
                "stun_keeper": _stun_keeper, "fight_keeper": _fight_keeper,
                "calm_keeper": _calm_keeper},
    spawns = [spawn(":test_shopkeeper")],
)

mob("test_shopkeeper",
    name = "the test shopkeeper",
    desc = "A shopkeeper kept for the shop tests.",
    vitals = vitals(hp = 50),
    behaviors = [sentinel()],
    on_spawn = _test_shop_open,
)

mob("test_small_keeper",
    name = "the test small shopkeeper",
    desc = "A shopkeeper with a cramped chest, kept for the shop tests.",
    vitals = vitals(hp = 50),
    behaviors = [sentinel()],
    on_spawn = _test_small_open,
)

mob("test_pack_keeper",
    name = "the test peddler",
    desc = "A wandering peddler kept for the shop tests.",
    vitals = vitals(hp = 50),
    on_spawn = _test_pack_open,
)

# A second shop whose keeper prices and refuses through the `shop_price` hook: it sells the
# sword at a flat 7, never sells bread, and will buy a player's axe (unlisted here) at 2.
HOOK_SHOP = shop.define(
    id = "hook_shop",
    stock = [
        shop.stock("//zones/tutorial:test_sword", count = 3, every = 600),
        shop.stock("//zones/tutorial:test_bread", count = 5, every = 600),
    ],
    till = shop.till(count = 100, every = 60, amount = 10),
    buy_markup = 120,
    sell_rate = 50,
)

def _hag_price(ctx, action, customer, item, label, count, price, refusal):
    if action == "sell" and item != None and item.label == "//zones/tutorial:test_axe":
        return 2
    if label == "//zones/tutorial:test_sword":
        return 7
    if label == "//zones/tutorial:test_bread":
        return "No bread for you."
    return None

def _test_hag_open(ctx):
    shop.open(ctx, ctx.self, HOOK_SHOP)

room("hook_test_room",
    name = "The Hag's Hut",
    desc = "A dim hut kept for the shop hook tests.",
    flags = ["dead_end_ok"],
    spawns = [spawn(":test_hag")],
)

mob("test_hag",
    name = "the test hag",
    desc = "A hag kept for the shop hook tests.",
    vitals = vitals(hp = 50),
    behaviors = [sentinel()],
    hooks = {"shop_price": _hag_price},
    on_spawn = _test_hag_open,
)
