# 08. Shops: The `//lib:shop.star` Library

Shops are not an engine feature. The template world ships `//lib:shop.star`, a library in the same shape as
`//lib:quest.star` (tutorials 04 and 05), that turns any mob into a shopkeeper. A shop is a **keeper** bound to a
**stock container** that holds real items, and a set of prices. Stock, the coins the shop pays out with, and even the
goods a player sells back are items you can examine, steal and inspect — because that is what they are.

The tutorial zone's shopkeeper sells copper rings and wooden shields from a locked chest and healing draughts without
limit, and its `shop_price` hook (below) gives customers with high charisma a discount. Load the library the same way
you load quests:

```python
load("//lib:shop.star", "shop")
```

---

## 1. Defining a Shop

A shop is described by one `shop.define(...)` call, kept in a module constant. The keeper's `on_spawn` hands the
constant to `shop.open`, which fills and locks a container in the keeper's room and gives the keeper the key:

```python
SHOP = shop.define(
    id = "shopkeeper",
    stock = [
        shop.stock("//zones/tutorial:copper_ring", count = 3, every = 600),
        shop.stock("//zones/tutorial:wooden_shield", count = 2, every = 600),
        shop.stock("//zones/tutorial:healing_draught"),   # no count: unlimited
    ],
    till = shop.till(count = 100, every = 60, amount = 10),
    buy_markup = 120,    # sells at 120% of item value
    sell_rate = 50,      # buys at 50%
)

def _shop_open(ctx):
    shop.open(ctx, ctx.self, SHOP)

mob("shopkeeper",
    name = "the shopkeeper",
    desc = "A brisk merchant with a chest full of wares.",
    behaviors = [sentinel()],
    on_spawn = _shop_open,
)
```

* **Stock lines:** `shop.stock(label, count=..., every=..., amount=...)`. `count` is how many to keep in the container;
  one is added every `every` pulses, up to `count`. A `count` of `None` is unlimited: the item is never stored and is
  spawned on demand. A `count` of `0` keeps none, but the item is still one the shop buys back by default.
* **Prices start from `item(value=)`:** the customer pays `value * buy_markup / 100`, rounded up, at least 1; the shop
  pays `value * sell_rate / 100` for items it lists. An item with no `value` is worth nothing to the shop.
* **The till** is a stock line too: the coins the shop keeps to pay out with, restocked like stock. A shop cannot pay
  more than its till holds ("the shopkeeper can't afford that").
* **Currency is items.** The default currency is `//lib:gold_coin`; a zone with its own coin names it with `currency =
  "..."`. Buying spends coins the customer carries directly, never coins in a container.

---

## 2. The Stock Container and Burglary

By default a shop's stock sits in a **locked chest** on the ground in the keeper's room (`//lib:shop_chest`), and the
keeper carries the key (`//lib:shop_chest_key`). Because stock is real items in a real container:

* a sold item keeps its state and can be sold back,
* a burglar can `unlock chest`, `open chest` and `get ring from chest`, and
* when the keeper respawns the library **catches the chest up**: each line gains what the time since the last catch-up
  warrants, in one lazy step, only while a keeper is present.

Restocking is time-based rather than instant because the chest's contents are observable. If a burglar takes three rings
at minute one, the shop cannot have three rings again by minute two — players could loot the restock. Instead the line
refills at its declared rate. The catch-up runs when the chest is opened or wakes, and before every `buy`, `sell` and
`value`; `list` projects the same arithmetic and shows the result without applying it.

Every shop that uses `//lib:shop_chest` shares the single `//lib:shop_chest_key` prototype, so any one key opens every
chest. A zone that wants its own key declares its own chest and key:

```python
item("vault_key", name = "a vault key", kind = "key")

def _on_vault_open(ctx):
    shop.catch_up(ctx, ctx.self)

def _on_vault_wake(ctx, elapsed_pulses):
    shop.catch_up(ctx, ctx.self)

item("vault", name = "a vault",
     container = container(capacity = 50, max_weight = 500, state = "closed", key = ":vault_key"),
     on_open = _on_vault_open,
     on_wake = _on_vault_wake)
```

and passes `container = "//zones/mine:vault"` and `key = "//zones/mine:vault_key"` to `shop.define`. Wiring `on_open`
and `on_wake` to `shop.catch_up` is what makes a custom container restock.

### Peddlers

A shop can instead carry its stock: a `shop_pack` in the keeper's inventory. With `carried = True` and `key = None` the
library gives the keeper a pack that never locks. A wandering peddler's pack drops with its corpse and can be looted
like any other body.

```python
PEDDLER_SHOP = shop.define(
    id = "peddler",
    container = "//lib:shop_pack",
    carried = True,
    key = None,
    stock = [shop.stock("//zones/meadow:bread", count = 5, every = 600)],
    till = shop.till(count = 50),
    capacity = 20,
    max_weight = 100,
)
```

---

## 3. Playing a Shop

In the keeper's room a player types `list`, `buy`, `sell` or `value`:

```text
> list
the shopkeeper offers:
 a copper ring 18 gold coins (3 left)
 a wooden shield 24 gold coins (2 left)
 a healing draught 36 gold coins
```

`list` sends the same data to a GMCP-capable client as the event `Shop.Stock`: the keeper's name, the currency label,
and one entry per line with the item's name, label, price and count (`null` for unlimited stock). A client can render a
shop window from it without parsing any text.

* **`buy <count> <item>`** moves coins from the customer into the till and items out of the container in one command.
  Asking for more than the shop holds buys what it has ("the shopkeeper only has 2."). A line with nothing in stock
  isn't offered. A refusal (no match, or "you can't afford that") changes nothing.
* **`sell <count> <item>`** is the reverse, capped at what the till holds and at the container's capacity and weight.
* **`value <item>`** reports what the keeper would pay without moving anything.
* **The keeper must be fit to trade.** If the keeper is incapacitated or its container is missing, every command answers
  "The shop is closed."; if it is stunned, "the shopkeeper is in no state to trade." until the stun wears off. A stunned
  keeper still counts as present, so its stock keeps restocking. A keeper that is fighting answers every customer, its
  attacker included, with "the shopkeeper is busy fighting." until the fight ends, and its stock keeps restocking
  meanwhile. The keeper bears no grudge afterwards. To give it one, have the keeper's `on_damage` hook record
  `ctx.actor` in an attribute and refuse that customer from `shop_price`.
* **`list` changes nothing.** It runs in the fast lane, which is not replayed, so the library projects the stock the
  next catch-up would hold and shows that, without spawning or moving a thing.

A library that must run in worlds with and without a feature checks `ctx.has_feature("combat")` before touching combat
fields, as the shop does.

---

## 4. The `shop_price` Hook

The keeper's prices are a suggestion. A keeper can declare a custom hook `shop_price` (tutorial 06, section 8) that the
library asks before it settles any price. The hook receives the action (`"list"`, `"buy"` or `"sell"`), the customer,
the item (`None` for an unlimited line), the label, the count, the library's default price, and a refusal message (for
sells the keeper is not interested in). Returning an **int** overrides the price, returning a **string** refuses the
transaction with that message, and returning **None** keeps the default.

The tutorial shopkeeper flatters customers: each point of charisma above 10 is worth 2%, off the price for buying and
added to the price for selling. It also refuses to buy anything tagged `"stolen"`:

```python
def _shop_price(ctx, action, customer, item, label, count, price, refusal):
    if action == "sell" and item != None and item.has_tag("stolen"):
        return "I don't deal in stolen goods."
    if price == None:
        return None     # keep the library's refusal
    bonus = max(0, customer.cha - 10) * 2
    if action in ("list", "buy"):
        return max(1, price * (100 - bonus) // 100)
    return price * (100 + bonus) // 100
```

and the mob carries `hooks = {"shop_price": _shop_price}`. Two contracts matter:

* **`list` must not change the world.** `list` runs in the fast lane and is not part of a reproducible replay, so a hook
  that spawns, moves or destroys anything when `action == "list"` would shift entity ids that a replay never sees. Price
  and refuse, but do not mutate.
* **The hook sees the library's default price** for every action, so a discount like the one above is applied exactly
  once whether the customer lists, buys or is asked the value.

Plugins join the same hook: a plugin registers a handler for the name `shop_price` with
`PluginRegistry.addHook("shop_price", doc, handle)` (tutorial 06, section 8), and the library's `ctx.call_hook` runs the
keeper's own hook first and then every plugin handler in plugin-id order, each able to change the price or the refusal.
A tax plugin, for example, can mark up every sale in the world without any zone noticing.

---

## Reference Links

* **Reference Implementation:** [`template-world/lib/shop.star`](../../lib/shop.star), and the shopkeeper in
  [`zones/tutorial/mobs.star`](../../zones/tutorial/mobs.star)
* **Custom Hooks:** tutorial 06, section 8
* **Specification:** [SPEC.md §4.9: Commands and Global Commands](../../../docs/SPEC.md)
* **HTML Documentation:** `world.html` lists the GMCP packages the world's packages declare, including `Shop.Stock`.
