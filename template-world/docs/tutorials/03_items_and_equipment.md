# 03. Items, Equipment, and Containers

Items populate the world as weapons, armor, quest keys, consumables, and containers. This tutorial covers how items are
declared, equipped, interacted with through hooks, and stored.

---

## 1. Declaring an Item

Items are declared with `item()`. At minimum, an item specifies its label identifier, a display name, and a description:

```python
# zones/tutorial/items.star
item("brass_key",
     name="a brass key",
     # Keywords determine what players type: "get key" or "examine brass"
     keywords=["brass", "key"],
     desc="A small brass key stamped with an anvil.",
     )
```

### Keyword Matching Rules

* When a player types `get key`, the engine matches `key` against the item's `keywords`.
* By default, `keywords` automatically defaults to the words in `name` minus common articles (`"a"`, `"an"`, `"the"`).
* You can explicitly supply `keywords` to provide synonyms (e.g. `keywords = ["draught", "potion", "flask"]`).

---

## 2. Weapons and Combat Gear

Weapons are configured using combat properties that the world's rules module evaluates during combat rounds:

```python
item("practice_sword",
     name="a practice sword",
     desc="A blunt wooden sword, good for nothing but practice.",
     kind="melee",  # melee | ranged | armor | consumable
     wield="wield",  # equipment slot (aliased to wear)
     dice="1d6",  # weapon damage dice
     damage_type="bludgeon",  # must match a declared damage type in MODULE.gups
     weight=3,
     )
```

* **Slots:** `wield = "wield"` specifies the hand slot. Players equip it by typing `wield sword`.
* **Damage Types:** The `damage_type` must match one of the types declared in `MODULE.gups` (or `DAMAGE.bludgeon`). If
  omitted, it defaults to the world's `unarmed_damage_type`.
* **Ranged Weapons:** Set `kind = "ranged"` and assign a `range` (`"near"` or `"far"`).

---

## 3. Armor, Stat Deltas, and Item Modifiers

Items worn by a character can alter their capabilities in two distinct ways:

### A. Static Stat Deltas: `stat_deltas`

`stat_deltas` modifies character base stats (such as `str`, `dex`, `con`, `armor`) while equipped:

```python
item("leather_cap",
     name="a leather cap",
     desc="A scuffed leather cap. It smells of the last person who wore it.",
     kind="armor",
     wear="head",  # `wear cap`; slots come from rules' equipment_slots()
     stat_deltas=stats(con=1),
     )
```

* **Slot Allocation:** `wear = "head"` places the item in the head slot. If you assign a slot family like
  `wear = "finger"`, the engine equips it to the first open finger slot (`finger_1`, `finger_2`).
* **Stat Deltas:** `stat_deltas = stats(...)` modifies the wearer's stats as long as the item remains equipped. When
  removed, the bonus is automatically subtracted.

### B. Dynamic Rules Modifiers: `modifiers`

Items can also attach dynamic rules modifiers that participate directly in the rules calculation chain (such as boosting damage, hit chance, or initiative) while equipped:

```python
def _heavy_strike(ctx, base):
    # Adds +2 raw damage to attacks made by the wearer
    return base + 2

item("spiked_gauntlets",
     name="spiked gauntlets",
     desc="Iron gauntlets lined with wicked studs.",
     wear="hands",
     modifiers={
         MODIFIER.damage: _heavy_strike,
     },
     )
```

* **Execution:** Item modifiers run as the final stage of the modifier chain (after zone, region, room, and affect modifiers), evaluated in `equipment_slots()` order.
* **Inspecting:** Just like room modifiers, equipped item modifiers can be traced during combat with `@trace rules`.

---

## 4. Consumables and Item Hooks

You can attach logic to items using lifecycle hooks. In item hooks, `ctx.self` is the item entity, and `ctx.actor` is
the character interacting with it:

```python
def _drink(ctx):
    # ctx.heal restores vital pools (e.g. "hp", "mana")
    ctx.heal(ctx.actor, 10, "hp")
    ctx.send(ctx.actor, "You drink the draught. Warmth spreads through you.")

    # Consumables do not disappear automatically: destroy it explicitly
    ctx.destroy(ctx.self)


item("healing_draught",
     name="a healing draught",
     keywords=["draught", "potion"],
     desc="A ceramic vial filled with glowing green liquid.",
     on_use=_drink,
     )
```

### Common Item Hooks:

* `on_use`: Runs when a player types `use <item>` or `drink <item>`.
* `before_use`: Can return `ctx.deny("Reason")` to prevent usage (e.g., if already at full health).
* `on_get`: Fires when a player picks up the item from a room or container.
* `on_drop`: Fires when a player drops the item into a room.

---

## 5. Containers

An item can act as a container (chest, backpack, sack) by providing `container()`:

```python
item("satchel",
     name="a leather satchel",
     desc="A sturdy travel bag with brass buckles.",
     container=container(
         capacity=10,  # Maximum number of individual items
         max_weight=20,  # Maximum total weight of contents
         state="closed",  # "open", "closed", or "locked"
         key=None,  # Optional item label required to unlock
     ),
     )
```

Players can `open satchel`, `put sword in satchel`, and `look in satchel`.

---

## 6. Spawning and Item Custody

Items are created and transferred through several mechanisms:

* **Giving directly to an actor:**
  ```python
  ctx.give(ctx.actor, ":brass_key")
  ```
  Spawns a new instance of `:brass_key` straight into the player's inventory (used in quests and NPC rewards).
* **Placing in a room at reset:**
  Rooms can restock items during their `on_reset` hook:
  ```python
  def _restock_armory(ctx):
      if not ctx.room_has_item(":practice_sword"):
          ctx.spawn(":practice_sword", at = ctx.self)
  ```
* **Custody Logging:** Every item transfer between rooms, containers, and players is recorded in SQLite (`item_events`)
  to prevent item duplication or loss.

---

## Reference Links

* **Reference Implementation:** [`template-world/zones/tutorial/items.star`](../../zones/tutorial/items.star)
* **Specification:** [SPEC.md §4.2: Content Declarations](../../../docs/SPEC.md#L979)
  and [§3.6: Persistence Schema](../../../docs/SPEC.md#L713)
* **HTML Documentation:** `declarations.html#item` and `declarations.html#container` in `gups docs`.
