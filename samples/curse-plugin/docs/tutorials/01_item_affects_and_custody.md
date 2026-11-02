# 01. Item Affects & Custody Propagation

In traditional text MUDs and RPG engines, items that grant passive bonuses or afflict characters with passive curses
frequently require complex event subscriptions, movement listeners, or manual inventory polling loops. If an item is
placed inside a backpack, or inside a sack inside a chest carried on horseback, custom scripts often struggle to track
who is holding what.

In GUPS, the engine core solves this transparently with **item affects** and **custody tree propagation**. This tutorial
explains how item affects work, how the engine propagates them through containers, and why containers are not escape
hatches for curses unless a plugin explicitly introduces one.

---

## 1. Item Affect Scopes: `worn` vs. `carried`

Every item affect declared on a prototype (`item_affect(...)`) or applied dynamically to an instance
(`ctx.apply_affect(item, ...)`) carries an `applies` scope:

```python
# 1. Worn affect: applies only while equipped in an active slot
item_affect("keen", applies = "worn", stat_deltas = stats(dex = 1))

# 2. Carried affect: applies anywhere in the holder's custody tree
item_affect("luck", applies = "carried", stat_deltas = stats(dex = 1))
```

### A. The `worn` Scope (Default)

An affect with `applies = "worn"` reaches the holder only while the item is actively equipped in a valid slot (e.g.
`wield`, `head`, `neck`, `finger_l`).

* If the character removes the item to their inventory, the affect ceases to apply.
* If the item is stored in a bag or dropped on the ground, the affect ceases to apply.
* Equipment prototypes declaring `stat_deltas` or `modifiers` without an affect scope behave as `worn`.

### B. The `carried` Scope

An affect with `applies = "carried"` reaches the character who ultimately possesses the item anywhere in their custody
tree:

* In the character's direct inventory.
* Worn as equipment (e.g. a charm worn around the neck).
* Inside a container in the character's inventory (e.g. inside a belt pouch).
* Inside nested containers (e.g. inside a small box, inside a sack, inside a worn backpack).

While reaching the holder, an item affect's stat deltas, sense deltas, status flags, and rules modifiers count directly
toward the character's effective attributes. They are not flags on the item; they apply to the holder.

---

## 2. Walk-Through: Uncursed Lucky Charm

To understand the beauty of the engine's custody propagation, consider an uncursed lucky charm defined with `applies =
"carried"`:

```python
item("lucky_charm",
     name = "a lucky charm",
     keywords = ["charm", "lucky"],
     affects = [item_affect("luck", applies = "carried", stat_deltas = stats(dex = 1))])
```

Here is what happens during ordinary player commands:

1. **Pick up the charm:** The player picks up `lucky_charm` into their inventory. The engine recalculates the actor's
   held effects via `refreshHoldings(player)`. The player's effective `dex` rises by 1.
2. **Put the charm in a bag:** The player types `put charm bag`. The charm moves into a bag in the player's inventory.
   Because the bag is still in the player's custody, the engine's carried pass traverses into the bag's contents. The
   player retains the +1 `dex` bonus.
3. **Put the bag in a backpack:** The player types `put bag backpack`. The bag moves into a backpack. The charm is now
   two levels deep in containers. The player still retains the +1 `dex` bonus.
4. **Drop the backpack:** The player types `drop backpack`. The backpack lands on the ground in the room. The player's
   custody over the backpack (and everything inside it) ends immediately. `refreshHoldings(player)` recomputes the
   actor's stats from scratch: the +1 `dex` bonus vanishes instantly.

Notice what did **not** happen: neither the zone writer nor any plugin had to write code to track container transfers,
drop events, or inventory nesting. The engine handles custody propagation automatically.

---

## 3. Walk-Through: The Cursed Amulet

Now consider what happens when the carried item is cursed, such as the `amulet_of_woe` from the Curse Plugin:

```python
item("amulet_of_woe",
     name = "an amulet of woe",
     desc = "A cursed amulet pulsing with dark malice.",
     wear = "neck",
     tags = ["cursed"],
     affects = [item_affect("misfortune", applies = "carried", stat_deltas = stats(dex = -2))],
     variants = {"uncursed": ":amulet_of_warding"})
```

Because `amulet_of_woe` has `applies = "carried"`, its `misfortune` affect afflicts whoever holds it with −2 `dex`.

When the player interacts with containers:

1. **Carrying the amulet:** The amulet is in the player's inventory. The player suffers −2 `dex`.
2. **Moving between own containers:** The player puts the amulet into a `small_bag`, and puts the `small_bag` into a
   `backpack`. Because both containers remain in the player's own custody, the move is permitted by the plugin, and the
   curse follows the player. The player still suffers −2 `dex`.
3. **Attempting to shed the curse:** The player tries to rid themselves of the curse with `drop backpack`. The curse
   plugin's `DROP` restriction intercepts the command. The backpack holds the bag, which holds the cursed amulet, so the
   backpack now **bears the curse**. The command is refused: `It won't budge. It must be cursed!` The backpack stays in
   the player's inventory, and the player remains afflicted with −2 `dex`. Taking the bag back out (`get bag backpack`)
   and trying `drop bag` is refused the same way.
4. **Attempting to stash the backpack in a ground chest:** The player types `put backpack chest`, targeting a container
   sitting on the ground. Because the ground chest is outside the player's custody, the plugin's `PUT` restriction
   refuses the action with the same message.

---

## 4. Why Containers Are Not Escape Hatches

A common trap in RPG design is assuming that placing an undesirable item inside a container should naturally suppress
its effects. In the GUPS engine core, **containers are not an escape hatch**:

* The custody tree evaluates all carried items down to the bottom of every container.
* Hiding a cursed object inside three nested wooden boxes does not insulate the holder from its malady.
* Containers do not block carried affects unless a plugin's **containment filter** explicitly intervenes (e.g. a
  specially warded or lead-lined container, covered in [Tutorial 04](04_containment_filters.md)).

By making custody propagation transparent and consistent, the engine ensures that curses cling reliably, while leaving
room for plugins to introduce specific, thematic counter-mechanics.
