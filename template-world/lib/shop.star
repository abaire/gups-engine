# A shop: a keeper, a stock container (a chest on the ground or a pack it carries), and the rules
# for buying and selling. Any zone can run one:
#
#     load("//lib:shop.star", "shop")
#
#     SHOP = shop.define(id = "smithy",
#                        stock = [shop.stock("//zones/meadow:sword", count = 3, every = 600)],
#                        till = shop.till(count = 500, every = 60, amount = 10))
#
#     def _on_spawn(ctx):
#         shop.open(ctx, ctx.self, SHOP)      # from the keeper's on_spawn
#
# Currency is items: the till and the customer's coins are stacks of the shop's currency (a gold
# coin by default). Stock and the till are real items in the container, so sold goods keep their
# state, a burglar can take the stock, and restocking is lazy and time-based. The keeper's custom
# hook `shop_price` may change any price or refuse any customer.
# A keeper that is fighting, stunned or down doesn't trade.
#
# The definition is stored on the keeper and the container as a custom attribute. Attributes can't
# hold None in a dict or list, so an absent key or till is the empty string and an unlimited stock
# line's count is -1.

def _stock(label, count = None, every = 600, amount = 1):
    """One line of stock: what to keep, and how it restocks over time."""
    if count != None and count < 0:
        fail("stock(count): %d is negative" % count)
    if every < 1:
        fail("stock(every): must be at least 1, got %d" % every)
    if amount < 1:
        fail("stock(amount): must be at least 1, got %d" % amount)
    return {"label": label, "count": count, "every": every, "amount": amount}

def _till(count = 0, every = 600, amount = 1):
    """The coins the shop keeps to pay out, restocked like a stock line."""
    if count < 0:
        fail("till(count): %d is negative" % count)
    if every < 1:
        fail("till(every): must be at least 1, got %d" % every)
    if amount < 1:
        fail("till(amount): must be at least 1, got %d" % amount)
    return {"count": count, "every": every, "amount": amount}

def _absolute(label, what):
    """A label the library's verbs can spawn: an absolute //zones/zone:item."""
    if type(label) != "string":
        fail("%s: %r is not a label string" % (what, label))
    if label.startswith(":"):
        fail("%s: %s is relative; write the full label, for example //zones/tutorial:sword" % (what, label))
    if not label.startswith("//"):
        fail("%s: %s is not an absolute label starting with //" % (what, label))
    return label

def _check_id(id):
    """A shop id: [a-z0-9_]+, unique per world."""
    if not id:
        fail("define(id): must not be empty")
    for ch in id.elems():
        if ch not in "abcdefghijklmnopqrstuvwxyz0123456789_":
            fail("define(id): %r is not made of [a-z0-9_]" % id)
    return id

def _define(id, stock, currency = "//lib:gold_coin", container = "//lib:shop_chest", carried = False,
            key = "//lib:shop_chest_key", till = None, buy_markup = 120, sell_rate = 50,
            capacity = 50, max_weight = 500):
    """A shop description. Keep it in a module constant and pass it to shop.open."""
    _check_id(id)
    if type(stock) != "list":
        fail("define(stock): must be a list of shop.stock lines")
    currency = _absolute(currency, "define(currency)")
    container = _absolute(container, "define(container)")
    if key != None:
        key = _absolute(key, "define(key)")
    if till != None:
        for field in till:
            if field not in ("count", "every", "amount"):
                fail("define(till): unknown field %r" % field)
    lines = []
    for line in stock:
        if type(line) != "dict" or line.get("label") == None:
            fail("define(stock): each line must be a shop.stock line with a label")
        _absolute(line["label"], "define(stock)")
        count = line["count"]
        if count == None:
            count = -1     # unlimited: never stored, spawned on demand
        lines.append({"label": line["label"], "count": count, "every": line["every"],
                      "amount": line["amount"]})
    if buy_markup < 1:
        fail("define(buy_markup): must be at least 1, got %d" % buy_markup)
    if sell_rate < 0 or sell_rate > 100:
        fail("define(sell_rate): must be between 0 and 100, got %d" % sell_rate)
    if capacity < 1:
        fail("define(capacity): must be at least 1, got %d" % capacity)
    if max_weight < 0:
        fail("define(max_weight): must be at least 0, got %d" % max_weight)
    return {
        "id": id, "currency": currency, "container": container, "carried": carried,
        "key": key if key != None else "", "till": till if till != None else "",
        "stock": lines, "buy_markup": buy_markup, "sell_rate": sell_rate,
        "capacity": capacity, "max_weight": max_weight,
    }

# The container's catalog hooks: a resting container restocks when it is opened or wakes.
def _on_container_open(ctx):
    _catch_up(ctx, ctx.self)

def _on_container_wake(ctx, elapsed_pulses):
    _catch_up(ctx, ctx.self)

item("gold_coin", name = "a gold coin", plural = "gold coins", keywords = ["coin", "gold"],
     stackable = True, weight = 0, value = 1)
item("shop_chest_key", name = "a shop chest key", keywords = ["key"], kind = "key")
item("shop_chest", name = "a stout chest", keywords = ["chest"],
     container = container(capacity = 50, max_weight = 500, state = "closed", key = ":shop_chest_key"),
     on_open = _on_container_open, on_wake = _on_container_wake)
item("shop_pack", name = "a peddler's pack", keywords = ["pack"],
     container = container(capacity = 20, max_weight = 100, state = "closed"),
     on_open = _on_container_open, on_wake = _on_container_wake)

def _shop_of(ctx, entity):
    """The shop definition stored on a keeper or container, or None."""
    return ctx.get_attr(entity, "shop")

def _container_of(ctx, keeper):
    """The keeper's stock container, or None if it has none yet."""
    d = _shop_of(ctx, keeper)
    if d == None:
        return None
    pool = keeper.inventory() if d["carried"] else keeper.room.items()
    for i in pool:
        if i.label != d["container"]:
            continue
        cd = _shop_of(ctx, i)
        if cd != None and cd["id"] == d["id"]:
            return i
    return None

def _keeper_of(ctx, container):
    """The living keeper bound to this container, or None."""
    d = _shop_of(ctx, container)
    if d == None:
        return None
    loc = container.location
    if loc.kind == "carried":
        actor = loc.actor
        if actor == None or actor.is_player or actor.condition != "ok":
            return None
        kd = _shop_of(ctx, actor)
        return actor if (kd != None and kd["id"] == d["id"]) else None
    if loc.kind == "room":
        for a in loc.room.actors():
            if a.is_player or a.condition != "ok":
                continue
            kd = _shop_of(ctx, a)
            if kd != None and kd["id"] == d["id"]:
                return a
    return None

def _count(ctx, container, label):
    """How many of label sit directly in the container."""
    total = 0
    for i in container.contents():
        if i.label == label:
            total += i.count
    return total

def _describe(item):
    """The catalog fields of an item view."""
    plural = item.plural
    if plural == None:
        plural = ""
    return {"name": item.name, "plural": plural, "keywords": item.keywords, "value": item.value}

def _info(ctx, container, label):
    """A label's name, plural, keywords, value, per-item weight and stackability, cached on the
    container. A miss spawns two of the label to read them, so only shop.open and tick-lane
    commands may miss."""
    cache = ctx.get_attr(container, "info", {})
    if label in cache:
        return cache[label]
    # Scripts can't read a prototype's stackable flag: two spawned at once come back as one
    # stack when it is stackable and as a list otherwise.
    made = ctx.spawn(label, container, count = 2)
    if type(made) == "list":
        info = _describe(made[0])
        info["weight"] = made[0].weight
        info["stackable"] = False
        for v in made:
            ctx.destroy(v)
    else:
        info = _describe(made)
        info["weight"] = made.weight // made.count
        info["stackable"] = True
        ctx.destroy(made)
    cache[label] = info
    ctx.set_attr(container, "info", cache)
    return info

def _room_for(ctx, container, d, label, n, pending = None):
    """How many of n more of label fit within the container's capacity and weight limits.
    `pending` is {"weight", "entries", "labels"} for additions not yet made (a projection)."""
    if n <= 0:
        return 0
    info = _info(ctx, container, label)
    weight = 0
    entries = 0
    already_have = False
    for i in container.contents():
        weight += i.weight
        entries += 1
        if i.label == label:
            already_have = True
    if pending != None:
        weight += pending["weight"]
        entries += pending["entries"]
        if label in pending["labels"]:
            already_have = True
    w = info["weight"]
    fit = (d["max_weight"] - weight) // w if w > 0 else n
    if info["stackable"]:
        if not already_have and entries >= d["capacity"]:
            fit = 0
    else:
        fit = min(fit, d["capacity"] - entries)   # each item is its own entry
    return max(0, min(n, fit))

def _add(ctx, container, label, n):
    """Spawns n of label into the container and merges them into the matching stack."""
    made = ctx.spawn(label, container, count = n)
    if type(made) == "list":
        for v in made:
            ctx.merge_stack(v)
    else:
        ctx.merge_stack(made)

def _restock_amount(ctx, container, d, label, count, every, amount, have, last, now, pending = None):
    """How many of the line the time since `last` warrants, within the container's limits."""
    if have >= count:
        return 0
    steps = (now - last) // every
    if steps == 0:
        return 0
    return _room_for(ctx, container, d, label, min(steps * amount, count - have), pending)

def _catch_up(ctx, container):
    """Restocks each line the time warrants, while a keeper is present. Never removes items."""
    d = _shop_of(ctx, container)
    if d == None:
        return
    if _keeper_of(ctx, container) == None:
        return
    now = ctx.pulse
    restock = ctx.get_attr(container, "restock", {})
    lines = []
    for line in d["stock"]:
        if line["count"] > 0:
            lines.append([line["label"], line["label"], line["count"], line["every"], line["amount"]])
    if d["till"] and d["till"]["count"] > 0:
        lines.append(["$till", d["currency"], d["till"]["count"], d["till"]["every"], d["till"]["amount"]])
    for entry in lines:
        key, label, count, every, amount = entry[0], entry[1], entry[2], entry[3], entry[4]
        have = _count(ctx, container, label)
        if have >= count:
            restock[key] = now
            continue
        last = restock.get(key, now)
        n = _restock_amount(ctx, container, d, label, count, every, amount, have, last, now)
        if n > 0:
            _add(ctx, container, label, n)
        if have + n >= count:
            restock[key] = now
        else:
            restock[key] = last + (now - last) // every * every
    ctx.set_attr(container, "restock", restock)

def _projected(ctx, container, d):
    """The counts each line would hold after a catch-up, without changing anything."""
    out = {}
    now = ctx.pulse
    restock = ctx.get_attr(container, "restock", {})
    # Each line's additions take space the next line would see after a real catch-up.
    pending = {"weight": 0, "entries": 0, "labels": {}}
    for line in d["stock"]:
        if line["count"] > 0:
            label = line["label"]
            have = _count(ctx, container, label)
            n = _restock_amount(ctx, container, d, label, line["count"], line["every"],
                                line["amount"], have, restock.get(label, now), now, pending)
            _pend(ctx, container, pending, label, have, n)
            out[label] = have + n
    if d["till"] and d["till"]["count"] > 0:
        label = d["currency"]
        have = _count(ctx, container, label)
        n = _restock_amount(ctx, container, d, label, d["till"]["count"], d["till"]["every"],
                            d["till"]["amount"], have, restock.get("$till", now), now, pending)
        out[label] = have + n
    return out

def _pend(ctx, container, pending, label, have, n):
    """Records n projected additions of label in `pending`."""
    if n <= 0:
        return
    info = _info(ctx, container, label)
    pending["weight"] += info["weight"] * n
    if not info["stackable"]:
        pending["entries"] += n
    elif have == 0 and label not in pending["labels"]:
        pending["entries"] += 1
    pending["labels"][label] = True

def _open(ctx, keeper, definition):
    """Binds keeper to its stock container, filling and locking a new one. Returns the container."""
    d = definition
    ctx.set_attr(keeper, "shop", d)
    container = _container_of(ctx, keeper)
    fresh = container == None
    if fresh:
        if d["carried"]:
            container = ctx.give(keeper, d["container"])
        else:
            container = ctx.spawn(d["container"], keeper.room)
    # A reused container takes the current definition and catalog, so a reload's new stock
    # lines and values apply once the keeper respawns.
    ctx.set_attr(container, "shop", d)
    ctx.set_attr(container, "info", {})
    for line in d["stock"]:
        _info(ctx, container, line["label"])
    _info(ctx, container, d["currency"])
    # A fresh container's restock clocks start now; a reused one keeps its own and catches up.
    restock = ctx.get_attr(container, "restock", {})
    if not restock:
        restock = {}
        for line in d["stock"]:
            restock[line["label"]] = ctx.pulse
        if d["till"]:
            restock["$till"] = ctx.pulse
        ctx.set_attr(container, "restock", restock)
    if fresh:
        for line in d["stock"]:
            if line["count"] > 0:
                n = _room_for(ctx, container, d, line["label"], line["count"])
                if n > 0:
                    _add(ctx, container, line["label"], n)
        if d["till"] and d["till"]["count"] > 0:
            n = _room_for(ctx, container, d, d["currency"], d["till"]["count"])
            if n > 0:
                _add(ctx, container, d["currency"], n)
        if d["key"]:
            ctx.lock_container(container)
    if d["key"]:
        has_key = False
        for i in keeper.inventory():
            if i.label == d["key"]:
                has_key = True
                break
        if not has_key:
            ctx.give(keeper, d["key"])
    return container

# --- Commands: list, buy, sell, value ----------------------------------------------------------

def _is_int(s):
    if s == "":
        return False
    for ch in s.elems():
        if ch not in "0123456789":
            return False
    return True

def _words(s):
    return [w for w in s.split(" ") if w != ""]

def _parse(args):
    """'3 sword' -> (3, 'sword'); 'sword' -> (1, 'sword'); '' -> None. A count must be >= 1."""
    words = _words(args.lower())
    if not words:
        return None
    if len(words) >= 2 and _is_int(words[0]):
        count = int(words[0])
        if count < 1:
            return None
        return (count, " ".join(words[1:]))
    return (1, " ".join(words))

def _buy_price(d, value):
    return max(1, (value * d["buy_markup"] + 99) // 100)

def _sell_price(d, value):
    return value * d["sell_rate"] // 100

def _kw_match(keywords, phrase):
    """True if every word of the phrase starts one of the keywords."""
    for w in _words(phrase):
        found = False
        for kw in keywords:
            if kw.lower().startswith(w):
                found = True
                break
        if not found:
            return False
    return True

def _listed(d, label):
    for line in d["stock"]:
        if line["label"] == label:
            return True
    return False

def _ask(ctx, keeper, action, customer, item, label, count, price, refusal):
    """The keeper's shop_price hook: (price, refusal), with the default kept on None."""
    r = ctx.call_hook(keeper, "shop_price", action = action, customer = customer, item = item,
                      label = label, count = count, price = price, refusal = refusal)
    if type(r) == "int" and r >= 1:
        return (r, None)
    if type(r) == "string":
        return (None, r)
    if r != None:
        print("shop_price returned %r; ignored" % r)
    return (price, refusal)

def _shop_here(ctx, apply):
    """The serving keeper and its container in the customer's room, or None with a message."""
    keeper = None
    for a in ctx.self.room.actors():
        if a.is_player:
            continue
        if _shop_of(ctx, a) != None:
            keeper = a
            break
    if keeper == None:
        ctx.send(ctx.self, "There's no shop here.")
        return None
    if keeper.condition != "ok":
        ctx.send(ctx.self, "The shop is closed.")
        return None
    # A stun or a fight only stops trading: the keeper is still present, so restocking goes on.
    if keeper.has_flag("stunned"):
        ctx.send(ctx.self, "%s is in no state to trade." % keeper.name)
        return None
    # fighting() is an error in a world without the combat feature.
    if ctx.has_feature("combat") and keeper.fighting():
        ctx.send(ctx.self, "%s is busy fighting." % keeper.name)
        return None
    container = _container_of(ctx, keeper)
    if container == None:
        ctx.send(ctx.self, "The shop is closed.")
        return None
    if apply:
        _catch_up(ctx, container)
    return (keeper, container, _shop_of(ctx, keeper))

def _first_in(container, label):
    for i in container.contents():
        if i.label == label:
            return i
    return None

def _deliver(ctx, container, customer, label, count):
    """Moves count items of label from the container to the customer, merging each."""
    need = count
    for i in list(container.contents()):
        if i.label != label or need <= 0:
            continue
        n = min(i.count, need)
        ctx.merge_stack(ctx.move_item(i, customer, count = n))
        need -= n

def _coins_in(holder, currency):
    """The currency coins an actor carries, or a container holds."""
    total = 0
    pool = holder.inventory() if type(holder) == "actor" else holder.contents()
    for i in pool:
        if i.label == currency:
            total += i.count
    return total

def _offer(label, item, count, price, info):
    return {"label": label, "item": item, "count": count, "price": price,
            "name": info["name"], "plural": info["plural"], "keywords": info["keywords"],
            "value": info["value"]}

def _offers(ctx, keeper, container, d, customer, projected):
    """What the keeper sells to this customer: the listed lines, plus anything else it holds.
    Stored lines with nothing in stock aren't offered."""
    offers = []
    projected_counts = _projected(ctx, container, d) if projected else {}
    listed = {}
    for line in d["stock"]:
        label = line["label"]
        listed[label] = True
        if label == d["currency"] or label == d["key"]:
            continue
        info = _info(ctx, container, label)
        if line["count"] == -1:
            count = None
            item = None
        else:
            item = _first_in(container, label)
            if projected:
                count = projected_counts.get(label, _count(ctx, container, label))
            else:
                count = _count(ctx, container, label)
            if count <= 0:
                continue
        price = _buy_price(d, info["value"])
        price, refusal = _ask(ctx, keeper, "list", customer, item, label, 1, price, None)
        if refusal != None:
            continue
        offers.append(_offer(label, item, count, price, info))
    held = {}
    for i in container.contents():
        held[i.label] = True
    for label in held:
        if label in listed or label == d["currency"] or label == d["key"]:
            continue
        # Read from the held item rather than the catalog: a catalog miss would spawn, and
        # list must not change the world.
        item = _first_in(container, label)
        info = _describe(item)
        price = _buy_price(d, info["value"])
        price, refusal = _ask(ctx, keeper, "list", customer, item, label, 1, price, None)
        if refusal != None:
            continue
        offers.append(_offer(label, item, _count(ctx, container, label), price, info))
    return offers

def _currency_word(ctx, container, d):
    c = _info(ctx, container, d["currency"])
    return c["plural"] if c["plural"] != "" else c["name"]

def _list_command(ctx):
    shop = _shop_here(ctx, False)
    if shop == None:
        return
    keeper, container, d = shop
    offers = _offers(ctx, keeper, container, d, ctx.self, True)
    if not offers:
        ctx.send(ctx.self, "%s has nothing for sale." % keeper.name)
    else:
        cur = _currency_word(ctx, container, d)
        lines = ["%s offers:" % keeper.name]
        for o in offers:
            line = " %s %d %s" % (o["name"], o["price"], cur)
            if o["count"] != None:
                line += " (%d left)" % o["count"]
            lines.append(line)
        ctx.send(ctx.self, "\n".join(lines))
    items = []
    for o in offers:
        items.append({"name": o["name"], "label": o["label"], "price": o["price"],
                      "count": o["count"]})
    ctx.gmcp(ctx.self, "Shop.Stock", {"keeper": keeper.name, "currency": d["currency"],
                                      "items": items})

def _buy_command(ctx):
    parsed = _parse(ctx.args)
    if parsed == None:
        ctx.send(ctx.self, "Buy what?")
        return
    count, keyword = parsed
    shop = _shop_here(ctx, True)
    if shop == None:
        return
    keeper, container, d = shop
    offer = None
    for o in _offers(ctx, keeper, container, d, ctx.self, False):
        if _kw_match(o["keywords"], keyword):
            offer = o
            break
    if offer == None:
        ctx.send(ctx.self, "%s doesn't sell that." % keeper.name)
        return
    if offer["count"] != None and count > offer["count"]:
        ctx.send(ctx.self, "%s only has %d." % (keeper.name, offer["count"]))
        count = offer["count"]
    # The hook sees the library's default price, as it did on list: asking it with the
    # already-adjusted list price would apply a multiplicative discount twice.
    base = _buy_price(d, offer["value"])
    price, refusal = _ask(ctx, keeper, "buy", ctx.self, offer["item"], offer["label"], count, base, None)
    if refusal != None:
        ctx.send(ctx.self, refusal)
        return
    total = price * count
    cur = _currency_word(ctx, container, d)
    if _coins_in(ctx.self, d["currency"]) < total:
        ctx.send(ctx.self, "You can't afford that.")
        return
    need = total
    for i in ctx.self.inventory():
        if i.label != d["currency"] or need <= 0:
            continue
        n = min(i.count, need)
        ctx.merge_stack(ctx.move_item(i, container, count = n))
        need -= n
    if offer["count"] != None:
        _deliver(ctx, container, ctx.self, offer["label"], count)
    else:
        made = ctx.give(ctx.self, offer["label"], count)
        if type(made) == "list":
            for v in made:
                ctx.merge_stack(v)
        else:
            ctx.merge_stack(made)
    ctx.send(ctx.self, "You buy %d %s for %d %s." % (count, offer["name"], total, cur))
    ctx.echo(ctx.self.room, "%s buys something from %s." % (ctx.self.name, keeper.name),
             exclude = [ctx.self])

def _sell_target(ctx, keyword):
    for i in ctx.self.inventory():
        if _kw_match(i.keywords, keyword):
            return i
    return None

def _sell_default(keeper, d, item):
    """The default (price, refusal) for selling item: listed lines at their sell price."""
    if _listed(d, item.label) and _sell_price(d, item.value) >= 1:
        return (_sell_price(d, item.value), None)
    return (None, "%s isn't interested in %s." % (keeper.name, item.name))

def _value_command(ctx):
    parsed = _parse(ctx.args)
    if parsed == None:
        ctx.send(ctx.self, "Value what?")
        return
    count, keyword = parsed
    shop = _shop_here(ctx, True)
    if shop == None:
        return
    keeper, container, d = shop
    item = _sell_target(ctx, keyword)
    if item == None:
        ctx.send(ctx.self, "You aren't carrying that.")
        return
    count = min(count, item.count)
    price, refusal = _sell_default(keeper, d, item)
    price, refusal = _ask(ctx, keeper, "sell", ctx.self, item, item.label, count, price, refusal)
    if refusal != None:
        ctx.send(ctx.self, refusal)
        return
    ctx.send(ctx.self, "%s would pay %d %s for %s." % (keeper.name, price,
                                                      _currency_word(ctx, container, d), item.name))

def _sell_command(ctx):
    parsed = _parse(ctx.args)
    if parsed == None:
        ctx.send(ctx.self, "Sell what?")
        return
    count, keyword = parsed
    shop = _shop_here(ctx, True)
    if shop == None:
        return
    keeper, container, d = shop
    item = _sell_target(ctx, keyword)
    if item == None:
        ctx.send(ctx.self, "You aren't carrying that.")
        return
    count = min(count, item.count)
    price, refusal = _sell_default(keeper, d, item)
    price, refusal = _ask(ctx, keeper, "sell", ctx.self, item, item.label, count, price, refusal)
    if refusal != None:
        ctx.send(ctx.self, refusal)
        return
    total = price * count
    cur = _currency_word(ctx, container, d)
    if _coins_in(container, d["currency"]) < total:
        ctx.send(ctx.self, "%s can't afford that." % keeper.name)
        return
    if _room_for(ctx, container, d, item.label, count) < count:
        ctx.send(ctx.self, "%s has no room for that." % keeper.name)
        return
    ctx.merge_stack(ctx.move_item(item, container, count = count))
    need = total
    for i in container.contents():
        if i.label != d["currency"] or need <= 0:
            continue
        n = min(i.count, need)
        ctx.merge_stack(ctx.move_item(i, ctx.self, count = n))
        need -= n
    ctx.send(ctx.self, "You sell %d %s for %d %s." % (count, item.name, total, cur))

shop = struct(define = _define, stock = _stock, till = _till, open = _open, catch_up = _catch_up)

gmcp_package("Shop.Stock", doc = "What the shopkeeper here sells, with prices, sent by list.")
global_command("list", _list_command, lane = "fast")
global_command("buy", _buy_command, lane = "tick")
global_command("sell", _sell_command, lane = "tick")
global_command("value", _value_command, lane = "tick")
