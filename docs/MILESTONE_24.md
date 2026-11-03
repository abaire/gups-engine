# Milestone 24: The Shop Library

**Prerequisites:** [M21](MILESTONE_21.md), [M22](MILESTONE_22.md) and [M23](MILESTONE_23.md) must be finished first.
This milestone uses `gmcp_package`/`ctx.gmcp` (M22), `item(value=)`, custom hooks, `ctx.call_hook` and the template's
`cha` stat (M23). Where line numbers below are wrong, find the symbol by name.

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** [DESIGN_NEEDED.md](DESIGN_NEEDED.md): `## Economy and crafting` → `### Shops`. Crafting
  was split into its own entry (`### Crafting`) and is out of scope.
- **Design Summary:**
  - **A library, not an engine feature.** `template-world/lib/shop.star`, loaded as `load("//lib:shop.star", "shop")`,
    modeled on `//lib:quest.star`. The only engine change in this milestone is a small `ctx.merge_stack` verb (Phase 1),
    which the library needs so coins don't pile up as separate stacks.
  - **Currency is items.** `//lib:gold_coin` is a stackable item (`plural = "gold coins"`, `weight = 0`, `value = 1`). A
    shop names its currency label (default `//lib:gold_coin`), so a zone can use its own. Buying spends coins the
    customer carries directly (not in containers); coins received are merged into one stack.
  - **Stock lives in a stock container.** Each shop has one container item: a locked chest on the ground in the keeper's
    room (`//lib:shop_chest`, key `//lib:shop_chest_key`, which the keeper carries), or a pack the keeper carries
    (`//lib:shop_pack`). Stock is real items in the container, so sold items keep their state and can be bought back, a
    burglar can unlock the chest and take the stock, and a wandering keeper's pack drops with its corpse. Zones may use
    their own container prototypes if they wire `on_open` and `on_wake` to the library's helpers. The library moves
    items with `ctx.move_item`, which ignores locks; it enforces the container's `capacity` and `max_weight` itself.
  - **The till is coins in the same container.** A shop can't pay more than its till holds ("I can't afford that.").
  - **Defining a shop.** A module constant made with `shop.define(...)`, and the keeper's `on_spawn` calls
    `shop.open(ctx, ctx.self, SHOP)`:

    ```python
    SHOP = shop.define(
    id = "smithy",                       # unique per world; binds keeper and container
    currency = "//lib:gold_coin",
    container = "//lib:shop_chest",      # or "//lib:shop_pack", or the zone's own
    carried = False,                     # True: the keeper carries the container
    key = "//lib:shop_chest_key",        # given to the keeper; None for no lock
    stock = [
            shop.stock(":sword", count = 3, every = 600),   # restocks one per 600 pulses, up to 3
            shop.stock(":bread"),                           # count None: unlimited, never stored
            shop.stock(":old_helm", count = 0),             # never restocked, but bought by default
    ],
    till = shop.till(count = 500, every = 60, amount = 10),
    buy_markup = 120,                    # customer pays value * 120 / 100, rounded up, at least 1
    sell_rate = 50,                      # shop pays value * 50 / 100, rounded down
    )
    ```
  - **Binding.** `shop.open` stores the definition on the keeper and on the container (lib attributes). If `carried`, it
    gives the keeper the container unless it already carries one bound to this shop. Otherwise it reuses a ground item
    in the keeper's room with that label and this shop's id, or spawns one, locks it if `key` is set, and gives the
    keeper the key unless it has one. A newly created container is filled to its stock and till targets at once; a
    reused one (a respawned keeper) keeps what it has and catches up.
  - **Restocking is lazy, partial and time-based.** Each stock line (and the till) records its last restock pulse on the
    container. A catch-up adds `floor(elapsed / every) × amount` items (default `amount` 1) up to the line's `count`,
    and advances the last restock pulse only by the intervals used, so the remainder carries over; a line at or above
    its target resets its clock to now, so a full shop doesn't bank time. Capacity and weight limits cap what is added.
    Catch-up runs on every `buy`, `sell` and `value` (`list` only previews it, because it must not change the world),
    when the container is opened (`on_open`), and on its `on_wake`. It applies only while a living keeper bound to the
    container is in its room (chest) or carrying it (pack); time keeps counting otherwise. A chest robbed just after a
    buyout therefore holds little; one left alone is full.
  - **Commands** (global commands of `//lib`):
    - `list` (fast lane): the stock for sale, with prices. Also sends the GMCP event `Shop.Stock`.
    - `buy [<count>] <keyword>` (tick lane): buys from the stock.
    - `sell [<count>] <keyword>` (tick lane): sells a carried, unworn item. A player may offer anything.
    - `value <keyword>` (tick lane): what the keeper would pay for a carried item.

    Each needs a keeper (a mob carrying this library's shop attributes) in the room whose condition is `ok`, and its
    container on the ground in the room or carried by it. With several keepers, the lowest id serves. Otherwise:
    "There's no shop here." or "The shop is closed." Scripts can't test whether the `combat` feature is on, so "busy
    fighting" isn't checked.

  - **Default prices and policy.** Buy price is `value × buy_markup / 100`, rounded up, at least 1. Sell price is `value
    × sell_rate / 100`, rounded down. By default the shop sells only items on its stock list (never the currency, never
    the key), and buys only labels on its stock list (including `count = 0` lines) whose sell price is at least 1.
    Anything else gets a default refusal: "<Keeper> isn't interested in <item>."

  - **The keeper decides (custom hook).** For every listed line, buy and sell, the library calls `ctx.call_hook(keeper,
    "shop_price", action = "list"|"buy"|"sell", customer = player, item = item_or_None, label = label, count = n, price
    = default_price_or_None, refusal = default_refusal_or_None)`. The result:

    - `None`: keep the default (the price, or the refusal);
    - an int of at least 1: the price per item (accepting an item the default would refuse);
    - a string: refuse with that message.

    Anything else is treated as `None` and noted with `print()` (shown in traces). `list` uses the same answer as `buy`,
    so listed prices match; lines refused to this customer are hidden. Plugin handlers for `shop_price` (M23) join
    automatically.

  - **GMCP.** `lib/shop.star` declares `gmcp_package("Shop.Stock", doc = ...)`. `list` sends `{"keeper": name,
    "currency": label, "items": [{"name", "label", "price", "count"}]}` (`count` null for unlimited lines).

  - **Persistence.** No engine trigger. A purchase happens in one command, and the next capture saves every changed
    entity together. §5.11's trigger list drops "a shop purchase, a bank deposit" and "(Shops and banks don't exist
    yet.)".

  - **Template.** A tutorial shopkeeper with a chest: a `shop_price` hook gives 2% off per point of `cha` above 10 when
    buying (at least 1) and pays 2% more per point when selling, and refuses to buy items tagged `stolen`. A tutorial
    page explains shops, burglary and peddlers.

  - **Out of scope:** crafting, haggling, banks, mobs buying from shops, showing `value` in `look`, bounties for corpses
    (corpses record only a display name), a plugin facade `callHook`.

- **Specification Impact:** §4.5 (`ctx.merge_stack`), §4.9 (a `//lib:shop.star` paragraph next to `//lib:quest.star`),
  §5.11 (trigger list), §7.4 (new M24 row); `docs/CHANGELOG.md`; template tutorials; the regenerated API files.

- **Decision Record Updates:** None.

### Guardrails for every phase

1. Follow `AGENTS.md`: TDD (write tests first and see them fail), no spec section numbers or decision ids in comments
   (Kotlin or Starlark), all Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`.
3. Add CHANGELOG bullets in a new `### Shops` subsection at the top of `## api_version 1 (unreleased)` (above M23's).
4. After Phase 1's API change, regenerate the API files exactly as M23's guardrail 4 describes. Later phases change only
   Starlark content and docs, so the API files must not change; if `gups docs` output for the template world changes
   (for example a `world.html` GMCP listing from M22), regenerate the affected goldens and read the diff.
5. Gradle commands must run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that
   code blocks kept their indentation.
6. Starlark style: follow `template-world/lib/quest.star` (a header comment explaining use, `_private` helpers, a
   `struct` export at the end, short docstrings). No lambdas or nested functions (Starlark forbids them).
7. Never read or touch `local-world/`.

---

## Phase 1: `ctx.merge_stack`

### Objective & Scope

- **In Scope:** a kernel verb exposing `World.stack`.
- **Out of Scope:** any library code.

### Context Working Set

- **Read / Reference Only:** `core/src/main/kotlin/gups/core/world/Items.kt`: `World.stack` (line ~159) and `canStack`;
  `script/src/main/kotlin/gups/script/Ctx.kt`: `destroy` (the item verb pattern).
- **Files to Modify / Create:** `script/src/main/kotlin/gups/script/Ctx.kt`; a script test (follow the existing item
  verb tests: `grep -rln "move_item" script/src/test`); `docs/SPEC.md`; `docs/CHANGELOG.md`; the regenerated API files.
- **Do Not Touch:** `core/` main code (`World.stack` already does the work), `template-world/`.

### Specification & Decision Record Changes

Edit `docs/SPEC.md` §4.5:

1. **Verb table, Items row**: add `` `merge_stack(item)` `` after `` `destroy(item, count=None)` ``.
2. **Item verb semantics**: replace "Verbs never merge stacks." with "Verbs never merge stacks, except
   `merge_stack(item)`, which merges the item into a stack of the same prototype in the same place with identical
   attributes, decay deadline and charges, as `get` does, and returns the view of the stack that now holds it (the item
   itself if nothing matched)."

### Implementation Blueprint

- **Signatures & Types:** `@StarlarkMethod(name = "merge_stack", doc = "Merges an item into an identical stack in the
  same place; returns the stack that holds it.", parameters = [Param(name = "item", named = true, doc = "Item to
  merge.")]) fun mergeStack(item: Any): Any`.
- **Algorithmic Steps & Invariants:** `verb("merge_stack") { v(world.stack(itemArg(item, "merge_stack(item)"))) }` (use
  the item-argument helper the other item verbs use). An equipped item or one that can't stack returns itself.

### Test & Documentation Requirements

- `` `merge_stack joins an identical stack` `` (two stacks of coins in an inventory become one, count summed); ``
  `merge_stack leaves different stacks apart` `` (different attributes); `` `merge_stack returns the item when nothing
  matches` ``.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `ApiDiffTest`.

---

## Phase 2: Library skeleton: prototypes, `define`, `open` and binding

### Objective & Scope

- **In Scope:** `lib/shop.star` with the item prototypes, `shop.define`, `shop.stock`, `shop.till`, `shop.open`, the
  internal lookups (keeper → container, container → keeper) and initial filling; tests.
- **Out of Scope:** restock catch-up (Phase 3), commands and pricing (Phase 4), tutorial content (Phase 5).

### Context Working Set

- **Read / Reference Only:**
  - `template-world/lib/quest.star` and `template-world/lib/package.star` (style, namespace, `global_command`).
  - `template-world/zones/tutorial/` (how prototypes, `on_spawn` hooks and tests are written).
  - §4.5 of `docs/SPEC.md` for the verbs used: `spawn`, `give`, `move_item`, `destroy`, `lock_container`, `set_attr`,
    `get_attr`, `merge_stack`, `room.items()`, `actor.inventory()`, `item.contents()`, `item.location`.
- **Files to Modify / Create:**
  - New `template-world/lib/shop.star`
  - `template-world/lib/package.star` (comment only: mention `shop.star`)
  - Test fixture in the tutorial zone, because test files can't declare content: a room `shop_test_room` (no exits to
    the rest of the tutorial) and a mob `test_shopkeeper` whose `on_spawn` calls `shop.open(ctx, ctx.self, TEST_SHOP)`
    with stock made of existing tutorial items given `value`s. Put them in `template-world/zones/tutorial/shop.star`
    (new file) and keep them out of the tutorial's walkthrough.
  - New `template-world/zones/tutorial/shop_test.star`: every test in this milestone goes here.
- **Do Not Touch:** engine code; other tutorial files (Phase 5); `docs/SPEC.md` (Phase 5).

### Specification & Decision Record Changes

None in this phase.

### Implementation Blueprint

- **Declarations** (top level of `shop.star`):

  ```python
  item("gold_coin", name = "a gold coin", plural = "gold coins", keywords = ["coin", "gold"],
   stackable = True, weight = 0, value = 1)
  item("shop_chest_key", name = "a shop chest key", keywords = ["key"], kind = "key")
  item("shop_chest", name = "a stout chest", keywords = ["chest"],
   container = container(capacity = 50, max_weight = 500, state = "closed", key = ":shop_chest_key"),
   on_open = _on_container_open, on_wake = _on_container_wake)
  item("shop_pack", name = "a peddler's pack", keywords = ["pack"],
   container = container(capacity = 20, max_weight = 100, state = "closed"),
   on_open = _on_container_open, on_wake = _on_container_wake)
  ```

  Check the exact `item(...)` and `container(...)` arguments against §4.2 and adjust names if needed. Define the hook
  functions (stubs in this phase that do nothing; Phase 3 fills them) above the declarations.

- **Data shapes** (plain dicts, so they can be stored as attributes):

  - `shop.stock(label, count = None, every = 600, amount = 1)` → `{"label": label, "count": count, "every": every,
    "amount": amount}`. Validate: `count` `None` or ≥ 0; `every` ≥ 1; `amount` ≥ 1; `fail()` with a clear message
    otherwise.
  - `shop.till(count = 0, every = 600, amount = 1)` → the same shape without `label`.
  - `shop.define(id, stock, currency = "//lib:gold_coin", container = "//lib:shop_chest", carried = False, key =
    "//lib:shop_chest_key", till = None, buy_markup = 120, sell_rate = 50, capacity = 50, max_weight = 500)` → a dict
    with every field. Validate `id` (`[a-z0-9_]+`), labels are strings, `buy_markup` ≥ 1, `0 ≤ sell_rate ≤ 100`,
    `capacity` ≥ 1, `max_weight` ≥ 0. **Every label must be absolute** (`//zones/x:sword`): the library's verbs resolve
    relative labels against the library's own package, so `define` fails on one that starts with `:` and says to write
    the full label.
  - `capacity` and `max_weight` are the library's own limits for the stock container (scripts can't read a container
    prototype's limits); they should match the container prototype, and the defaults match `//lib:shop_chest`. For
    `//lib:shop_pack` pass `capacity = 20, max_weight = 100`.
- **Attributes** (lib namespace): keeper `shop` = the definition; container `shop` = the definition and `restock` =
  `{label_or_"$till": last_pulse}`.
- **Helpers** (private, top level):
  - `_shop_of(ctx, entity)`: `ctx.get_attr(entity, "shop")`.
  - `_container_of(ctx, keeper)`: if `carried`, the first item in `keeper.inventory()` with `label == container` and
    `shop.id == id`; else the first item in `keeper.room.items()` with those. `None` if absent.
  - `_keeper_of(ctx, container)`: if the container's location is an actor (carried) and that actor is a mob with this
    shop id and `condition == "ok"`, that actor; if it is on the ground, the lowest-id mob in its room with this shop id
    and `condition == "ok"`; else `None`. (Read `item.location`'s shape from §4.5 / `Views.kt`.)
  - `_count(ctx, container, label)`: total count of items with that label directly in the container.
  - `_room_for(ctx, container, d, label, n)`: how many of `n` fit within the definition's `capacity` (number of items in
    the container, a stack counting as one) and `max_weight` (sum of `weight × count` of the contents), using the
    label's weight from `_info`.
  - `_info(ctx, container, label)`: `{"name", "plural", "keywords", "value", "weight"}` for a label, cached in the
    container's `info` attribute. On a miss: take it from an item with that label already in the container, else
    `ctx.spawn(label, container)`, read the view, and `ctx.destroy` it. `shop.open` clears the cache and then fills it
    for every stock label and the currency, so the fast-lane `list` never misses (a miss there would spawn, which repro
    bundles don't replay; see Phase 4). A reload's new values are picked up when the keeper respawns.
  - `_add(ctx, container, label, n)`: `ctx.spawn(label, container, count = n)`, which returns one view (a stack) or a
    list of views; call `ctx.merge_stack` on each.
- **`shop.open(ctx, keeper, definition)`:**
  1. `ctx.set_attr(keeper, "shop", definition)`.
  2. Find the container with `_container_of`. If none: carried → `ctx.give(keeper, container_label)`; ground →
     `ctx.spawn(container_label, keeper.room)`. Set its `shop` attr, set `restock` to now for every line, fill every
     line with a `count` to its target and the till to its `count` (respecting capacity), and lock it if `key` is set
     (`ctx.lock_container`).
  3. If `key` is set and the keeper doesn't carry one, `ctx.give(keeper, key)`.
  4. Return the container view.
- **Export:** `shop = struct(define = _define, stock = _stock, till = _till, open = _open, ...)`; Phase 3 adds
  `catch_up`, Phase 4 adds nothing public beyond commands.

### Test & Documentation Requirements

- `template-world/zones/tutorial/shop_test.star`, using the fixture room and keeper. Cases:
  - `test_define_validates` (relative labels, bad rates, negative counts fail).
  - `test_open_spawns_locked_chest_and_gives_key` (ground container appears in the keeper's room, locked; keeper carries
    the key; stock and till filled).
  - `test_open_reuses_existing_chest` (spawn a second keeper bound to the same id after the first is destroyed: no new
    chest; contents kept).
  - `test_open_gives_pack_when_carried`.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including the template world's Starlark tests.
- [] `grep -n "def _open" template-world/lib/shop.star` finds the function.

---

## Phase 3: Restock catch-up

### Objective & Scope

- **In Scope:** `shop.catch_up(ctx, container)`, the container `on_open`/`on_wake` hooks, partial restocking with
  carried-over remainders, the bound-keeper rule; tests.
- **Out of Scope:** commands (Phase 4).

### Context Working Set

- **Read / Reference Only:** `template-world/lib/shop.star` (Phase 2); §4.4 (`on_open`, `on_wake(ctx, elapsed_pulses)`
  signatures).
- **Files to Modify / Create:** `template-world/lib/shop.star`, `template-world/zones/tutorial/shop_test.star`.
- **Do Not Touch:** engine code.

### Specification & Decision Record Changes

None.

### Implementation Blueprint

- **`_catch_up(ctx, container)`:**
  1. `d = _shop_of(ctx, container)`; return if `None`. If `_keeper_of(ctx, container)` is `None`, return without
     changing anything (time keeps counting).
  2. `now = ctx.pulse`; `restock = ctx.get_attr(container, "restock", {})`.
  3. For each stock line with a non-`None`, positive `count`, and for the till (key `"$till"`, label = currency) when
     its `count` > 0:
     - `have = _count(...)`. If `have >= count`: `restock[key] = now`; continue.
     - `last = restock.get(key, now)`; `steps = (now - last) // every`; if `steps == 0`: continue.
     - `n = min(steps * amount, count - have)`, then `n = _room_for(ctx, container, d, label, n)`; `_add(...)` if `n >
       0`.
     - `restock[key] = last + steps * every` (remainder carried), or `now` if the line is now full.
  4. `ctx.set_attr(container, "restock", restock)`.
- **Hooks:** `_on_container_open(ctx)` → `_catch_up(ctx, ctx.self)`; `_on_container_wake(ctx, elapsed_pulses)` →
  `_catch_up(ctx, ctx.self)`. Export `catch_up = _catch_up` so zones with their own containers can wire it.
- **Invariant:** catch-up is idempotent within a pulse and never removes items.

### Test & Documentation Requirements

- `test_partial_restock_after_buyout`: empty a line by destroying its items, advance the clock 1.5 × `every`
  (`w.advance(...)` or the harness's equivalent), open the chest: exactly one item is back; advance 0.5 × `every` more
  and catch up again: a second item (the remainder carried over).
- `test_full_shop_does_not_bank_time`: a full line, advance 10 × `every`, remove all, catch up immediately: nothing
  added.
- `test_no_restock_without_keeper`: destroy the keeper, advance, open the chest: nothing added; spawn a keeper bound to
  it: the next catch-up adds what accrued (up to the target).
- `test_till_restocks`.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including the template world's Starlark tests.

---

## Phase 4: Commands, pricing, the keeper hook and GMCP

### Objective & Scope

- **In Scope:** `list`, `buy`, `sell`, `value`; prices; the `shop_price` hook contract; coins; the till; `Shop.Stock`.
- **Out of Scope:** tutorial content and spec text (Phase 5).

### Context Working Set

- **Read / Reference Only:** `template-world/lib/quest.star` (`global_command` use, message style);
  `template-world/lib/shop.star`; §4.9 for how engine item commands match keywords (reproduce the simple form: every
  word of the phrase must match the start of one of the item's keywords).
- **Files to Modify / Create:** `template-world/lib/shop.star`, `template-world/zones/tutorial/shop_test.star`.
- **Do Not Touch:** engine code.

### Specification & Decision Record Changes

None (Phase 5 writes the §4.9 paragraph).

### Implementation Blueprint

- **Registration:** `global_command("list", _list_command, lane = "fast")`; `global_command("buy", _buy_command, lane =
  "tick")`; same for `sell` and `value`. `gmcp_package("Shop.Stock", doc = "What the shopkeeper here sells, with prices,
  sent by list.")`.
- **The customer is `ctx.self`.** In a global command `ctx.self` is the player who typed it (as in `quest.star`'s
  `_quests_command`); use it everywhere below where "customer" or "the player" appears.
- **Common front (`_shop_here(ctx, apply)`):** the lowest-id mob in `ctx.self.room` with a `shop` attr → keeper, else
  send "There's no shop here." If `keeper.condition != "ok"` or `_container_of` is `None` → "The shop is closed." Then,
  if `apply`, `_catch_up(ctx, container)`. Return `(keeper, container, d)` or `None`. `buy`, `sell` and `value` pass
  `apply = True`.
- **`list` changes nothing.** It is fast-lane, and fast-lane commands aren't recorded in repro bundles, so it must not
  spawn, move or destroy anything (that would shift entity ids and run `on_spawn` hooks a replay skips). It passes
  `apply = False` and uses `_projected(ctx, container, d)`: the same arithmetic as `_catch_up` (Phase 3), returning
  `{label: count}` as it would be after a catch-up, without changing anything. The next `buy` applies the real catch-up
  first, so what `list` shows is what `buy` finds. Extract the arithmetic into one pure helper that both `_catch_up` and
  `_projected` call.
- **Argument parsing (`_parse(args)`):** `"3 sword"` → `(3, "sword")`; `"sword"` → `(1, "sword")`; empty → `None` (send
  "Buy what?" / "Sell what?" / "Value what?"). A count must be ≥ 1.
- **Prices:** `_buy_price(d, value) = max(1, (value * d["buy_markup"] + 99) // 100)`; `_sell_price(d, value) = value
  * d["sell_rate"] // 100`.
- **The hook (`_ask(ctx, keeper, action, customer, item, label, count, price, refusal)`):** `r = ctx.call_hook(keeper,
  "shop_price", action = action, customer = customer, item = item, label = label, count = count, price = price, refusal
  = refusal)`. `type(r) == "int" and r >= 1` → `(r, None)`; `type(r) == "string"` → `(None, r)`; `r == None` → `(price,
  refusal)`; otherwise `print("shop_price returned %r; ignored" % r)` and `(price, refusal)`.
- **Sellable lines (`_offers(ctx, keeper, container, d, customer)`):** for each stock line except the currency and the
  key: stored lines offer that label with the count from the container (or from `_projected` for `list`), `item` = the
  first stored instance or `None` if there is none yet; unlimited lines offer the label with `item = None`. Names and
  values come from `_info`. Items in the container whose label isn't on the list (bought from players) are offered too,
  one offer per label. Default price from the value; ask the hook with `action = "list"`; skip refusals.
- **`list`:** `_shop_here`; build offers; send "<Keeper> offers:" then one line per offer: ` <name> <price> <currency
  plural or name>` plus ` (<n> left)` for stored lines; or "<Keeper> has nothing for sale." Also `ctx.gmcp(ctx.self,
  "Shop.Stock", {...})` (its return value ignored).
- **`buy`:** parse; find the offer whose name/keywords match; none → "<Keeper> doesn't sell that." Ask the hook with
  `action = "buy"` and the default price; refusal → send it. Total = price × count; cap `count` at what is stored for
  stored lines ("<Keeper> only has N."). Count the customer's carried coins of the currency (directly in inventory);
  short → "You can't afford that." Pay: move coins from the customer's stacks into the container (`move_item` with
  `count`, across stacks as needed), `merge_stack` in the container. Deliver: stored → `move_item(item, customer, count
  = n)` then `merge_stack`; unlimited → `ctx.give(customer, label, n)` then `merge_stack` each result. Messages: "You
  buy <n> <name> for <total> <currency>." and to the room "<Customer> buys something from <Keeper>." (use
  `ctx.echo(room, text, exclude = [customer])`).
- **`sell` / `value`:** find a carried (inventory, not worn) item matching the keyword; none → "You aren't carrying
  that." Default: label on the stock list (any count, including 0) and `_sell_price(...) >= 1` → that price; else
  refusal "<Keeper> isn't interested in <item>.". Ask the hook with `action = "sell"`. `value` sends "<Keeper> would pay
  <price> <currency> for <item>." or the refusal, and stops. `sell`: cap `count` to the stack; total = price × count;
  till coins in the container < total → "<Keeper> can't afford that."; else move the items into the container
  (`move_item`, then `merge_stack`), move `total` coins from the container to the customer (`merge_stack`), and send
  "You sell <n> <name> for <total> <currency>.". The container's capacity and weight limits apply to what the shop takes
  in: if it can't fit, "<Keeper> has no room for that."
- **Invariants:** coins and goods change hands in one command; no coins are created or destroyed by `buy`/`sell` (except
  unlimited stock spawned for a sale); every refusal leaves everything unchanged.

### Test & Documentation Requirements

`template-world/zones/tutorial/shop_test.star` (a player spawned with `gmcp = ["Shop"]` where GMCP is asserted):

- `test_list_shows_prices_and_sends_gmcp` (`p.gmcp("Shop")` has one `Shop.Stock` with the items).
- `test_buy_moves_coins_and_goods` (coins to the till, item to the player, one coin stack each side).
- `test_buy_unlimited_spawns`; `test_buy_more_than_stocked_is_refused`; `test_cannot_afford`.
- `test_sell_listed_item`; `test_sell_unlisted_item_refused_by_default`; `test_sell_count_zero_line_accepted`;
  `test_till_limits_sales`; `test_value_reports_price`.
- `test_hook_changes_price_and_refuses` (a keeper whose `shop_price` returns an int for one label and a string for
  another; list hides the refused line; buy uses the hook price).
- `test_hook_can_accept_unlisted_item` (returns a price for `action == "sell"` on an unlisted label).
- `test_closed_when_keeper_incapacitated` and `test_no_shop_here`.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including the template world's Starlark tests.

---

## Phase 5: Tutorial shop, spec text and finalization

### Objective & Scope

- **In Scope:** a shopkeeper in the tutorial zone with a charisma-based `shop_price` hook; a tutorial page; the §4.9
  paragraph, §5.11 wording and §7.4 row; the `DESIGN_NEEDED` entry marked completed.
- **Out of Scope:** anything in the engine.

### Context Working Set

- **Read / Reference Only:** `template-world/zones/tutorial/*.star`, `template-world/docs/tutorials/README.md` and an
  existing tutorial page for tone and structure.
- **Files to Modify / Create:**
  - `template-world/zones/tutorial/mobs.star`, `rooms.star`, `items.star` (whichever hold the new content), and
    `shop_test.star`
  - New `template-world/docs/tutorials/08_shops.md`; `template-world/docs/tutorials/README.md` (index)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, `docs/DESIGN_NEEDED.md`
- **Do Not Touch:** engine code; other zones.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§4.9**, after the `//lib:quest.star` paragraph, add:

   **`//lib:shop.star`:** `shop.define(id, stock, ...)` describes a shop and `shop.open(ctx, keeper, shop)` (from the
   keeper's `on_spawn`) binds a keeper to a stock container: a locked chest in its room or a pack it carries. Stock and
   the till (coins of the shop's currency, `//lib:gold_coin` by default) are real items in the container, restocked
   lazily over time while a keeper is present. Players use `list`, `buy`, `sell` and `value`. Prices start from
   `item(value=)`; the keeper's custom hook `shop_price` (§4.4) may change any price or refuse any customer, and plugin
   handlers for it join in (§4.15). `list` also sends the GMCP event `Shop.Stock`.

2. **§5.11, "Triggers"**: delete "a shop purchase, a bank deposit," and the sentence "(Shops and banks don't exist
   yet.)".

3. **§7.4**: add the row below after the M23 row.

```markdown
| **M24**   | The shop library: `//lib:shop.star` with coin currency, stock containers (chest or pack) holding real stock and the till, lazy partial restocking, `list`/`buy`/`sell`/`value`, the `shop_price` custom hook, `Shop.Stock` GMCP, `ctx.merge_stack`; the tutorial shop (§4.5, §4.9, §5.11). |
```

### Implementation Blueprint

1. Tutorial zone: a room (or an existing one, if fitting) with a shopkeeper mob whose `on_spawn` calls `shop.open(ctx,
   ctx.self, SHOP)`, and whose `hooks = {"shop_price": _shop_price}`. Stock: a couple of existing tutorial items (give
   them `value`s) plus one unlimited consumable. `_shop_price(ctx, action, customer, item, label, count, price,
   refusal)`:
   - `action == "sell"` and `item != None and item.has_tag("stolen")` → return `"I don't deal in stolen goods."`.
   - `price == None` → return `None`.
   - `bonus = max(0, customer.cha - 10) * 2`; buying (`"list"`/`"buy"`) → `max(1, price * (100 - bonus) // 100)`;
     selling → `price * (100 + bonus) // 100`. Comment it in the tutorial zone's teaching style.
2. Don't give new characters coins. Tests give players coins with the harness's item-spawning helper (find it in §4.12
   or `harness/.../Handles.kt`; the tests from Phases 2–4 already need it).
3. `08_shops.md`: defining a shop, the stock container and keys, burglary (unlock and open the chest; why restocking is
   time-based), peddlers (`carried = True`, the pack drops on death), the `shop_price` hook with the charisma example
   (and that it must not change the world when `action == "list"`, since `list` isn't replayed), plugins joining the
   hook, `Shop.Stock` GMCP, and that every shop using `//lib:shop_chest` shares `//lib:shop_chest_key` (a zone wanting
   its own key declares its own chest and key and wires `on_open`/`on_wake` to `shop.catch_up`). Keep code blocks out of
   nested lists.
4. `DESIGN_NEEDED.md`: change `### Shops` to `### Shops (COMPLETED — M24)`. Do not delete it. Leave `### Crafting` and
   `### Plugins calling custom hooks` untouched.
5. Run `./gradlew markdownFormat`; regenerate goldens if the template's docs output changed (guardrail 4).

### Test & Documentation Requirements

- Tutorial tests: `test_shopkeeper_sells_with_charisma_discount` (a player with `cha = 15` pays 10% less than the
  default price); `test_shopkeeper_refuses_stolen_goods`; `test_burglary_takes_stock` (a player with the key unlocks and
  opens the chest and takes an item; `list` no longer offers it).

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `markdownCheck` and the template world's tests.
- [] `grep -n "COMPLETED — M24" docs/DESIGN_NEEDED.md` shows the entry.
- [] `grep -n "Shops and banks don't exist yet" docs/SPEC.md` returns nothing.
- [] End to end in `--dev`: `list`, `buy`, `sell` and `value` work at the tutorial shopkeeper, and a GMCP-capable client
  receives `Shop.Stock` on `list`.
