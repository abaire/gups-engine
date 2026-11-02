# 02. Item Restriction Points

In game design, curses must cling. A cursed ring should resist being slipped off a finger; a cursed broadsword should
not be easily discarded in a bush; and a cursed idol should not be casually handed off to an unsuspecting squire.

To enforce these rules cleanly without cluttering core command logic with genre-specific curse exceptions, the GUPS
engine introduces **item restriction points**. This tutorial covers the four kernel points, the command-only rule, and
how the Curse Plugin uses them.

---

## 1. The Four Item Restriction Points

Plugins register restriction checks using `r.addRestriction(point, doc) { call -> ... }`:

|  Point   |                                                   Checked When                                                    |          Subject (`call.item`)          |
|----------|-------------------------------------------------------------------------------------------------------------------|-----------------------------------------|
| `REMOVE` | `remove` (and aliases), plus any item `wear` or `wield` would swap out of an occupied slot.                       | The equipped item being unequipped.     |
| `DROP`   | `drop` (and aliases), evaluated per item stack before the item moves or drop hooks fire.                          | The item being dropped.                 |
| `GIVE`   | `give` (and aliases), evaluated per item stack before the item moves to the receiver (`call.target`).             | The item being transferred.             |
| `PUT`    | `put` (and aliases), evaluated per item stack before the item moves into a container destination (`call.target`). | The item being placed into a container. |

When any check returns a non-null string, the action is refused, the message is sent to the actor, and no action lag or
wait state is charged.

---

## 2. The Command-Only Rule & Trusted Verbs

A foundational architectural rule in GUPS is that **restrictions guard player and mob commands only**:

* **Commands:** When a player enters `remove ring`, `drop sword`, `give bag bob`, or `put coin chest`, or when a mob
  executes a command line via `act`, the engine runs the registered restriction points.
* **Script & Facade Verbs Are Trusted:** Verbs invoked by Starlark scripts (`ctx.move_item`, `ctx.give`, `ctx.destroy`,
  `ctx.transform`) and plugin facade methods (`world.transform`, `world.addTag`) **do not run restrictions**.
* **Engine-Internal Movements Are Trusted:** Character death (dropping inventory), item decay, the re-check that takes
  off equipment after a choice change, and prototype transforms bypass restrictions entirely.

### Why Trusted Verbs Matter: The Temple Priest

Consider a quest where an afflicted player seeks out an NPC priest at a local temple to cleanse a cursed amulet.

The player can't simply `give amulet priest`: the `GIVE` restriction refuses the command before the amulet moves, so the
priest's `on_give` hook never runs. Instead, the priest listens for a request and takes the amulet with a script verb.
Script verbs are trusted, so the move goes through:

```python
def _priest_hears(ctx):
    # ctx.self is the priest, ctx.actor is whoever spoke, ctx.text is what they said.
    if "cleanse" not in ctx.text.lower():
        return
    for item in ctx.actor.inventory():
        if item.has_tag("cursed"):
            ctx.send(ctx.actor, "The priest lifts the cursed relic from you and begins an incantation...")
            # Trusted verb: moves the item regardless of restrictions.
            ctx.move_item(item, ctx.self)

mob("priest", name = "a priest", keywords = ["priest"], on_say = _priest_hears)
```

The player can't drop or give the amulet through the `drop` or `give` commands, but the quest script can relieve them of
it cleanly.

---

## 3. Direct Tags vs. `bearsCurse`: Why `REMOVE` is Different

In `CursePlugin.kt`, you will notice an important distinction between `REMOVE` and the other three restriction points:

```kotlin
// REMOVE checks only the item's own tag:
r.addRestriction(PluginRestrictionPoint.REMOVE, ...) { call ->
  if (call.item?.hasTag("cursed") == true) "It won't budge. It must be cursed!" else null
}

// DROP and GIVE check bearsCurse:
r.addRestriction(PluginRestrictionPoint.DROP, ...) { call ->
  if (call.item?.let(::bearsCurse) == true) "It won't budge. It must be cursed!" else null
}
```

Why does `REMOVE` check only `call.item.hasTag("cursed")` instead of `bearsCurse`?

### The Worn Backpack Example

Suppose a character wears a `backpack` (equipped in the `held` slot), and inside that backpack lies the cursed
`amulet_of_woe`.

* Does the backpack bear a curse? **Yes**, because it contains a cursed item.
* Should the character be allowed to `remove backpack`? **Yes!**
  * Taking off a worn container moves it into the character's direct inventory.
  * The backpack—and the cursed amulet inside it—**never leaves the character's custody**.
  * The carried affect (`-2 dex`) continues to afflict the character unabated.
  * Refusing to let the player take off their backpack would add frustration without protecting the curse.

What `REMOVE` actually protects is the cursed item's own **`worn` affects**. If a player could remove a cursed helmet or
cursed sword, its `worn` affects would cease the instant it landed in the inventory. That is the classic fantasy trope:
*the cursed helm clings to your head and cannot be removed*.

Therefore:

* `REMOVE` refuses only items that are **directly tagged `cursed`**.
* `DROP`, `GIVE`, and `PUT` refuse any item that **bears a curse**, preventing the curse from escaping the character's
  custody.

---

## 4. Preventing the Wear and Wield Swap Loophole

In many RPGs, a player equipped with a clinging cursed sword might attempt a clever workaround: `wield dagger`.

In an engine without unequip checks on swaps, equipping the dagger might silently displace the cursed sword into the
inventory, bypassing the `remove` command entirely!

The GUPS engine closes this loophole automatically:

1. When a player commands `wear <item>` or `wield <item>`, the engine determines the target slot via `slotFor`.
2. If that slot is already occupied, the engine executes the **`REMOVE` restriction check on the current occupant**
   before moving any item.
3. If the check refuses (e.g. the currently wielded weapon has the `cursed` tag), the swap is vetoed:
   * The cursed sword remains wielded.
   * The dagger remains in the inventory.
   * The refusal message is printed: `"It won't budge. It must be cursed!"`
   * No lag is charged.

### Slot Families: Finger Rings

The same logic applies to slot families like `finger` (`finger_l`, `finger_r`):

* If `finger_l` holds a cursed ring and `finger_r` is open, typing `wear plain` (a keyword only the plain ring has)
  equips the plain ring to `finger_r` without touching the cursed ring.
* If both fingers are full and `finger_l` holds the cursed ring, attempting to wear a third ring tries to swap out the
  first finger. The engine checks `REMOVE` on the cursed ring, catches the curse, and refuses the command.
