# 04. Containment Filters

As established in [Tutorial 01](01_item_affects_and_custody.md), the engine's custody propagation ensures that carried
item affects permeate ordinary containers: putting a cursed relic into a leather sack or cloth backpack does not protect
the holder from its influence.

However, many fantasy settings feature specialized containers designed specifically to isolate magical or cursed
emanations: lead-lined reliquaries, rune-inscribed boxes, or warded urns.

To support this mechanic without hardcoding container types into core engine code, GUPS provides **containment
filters**. This tutorial explains how containment filters operate, their strict purity contract, and how the Curse
Plugin keeps its restrictions and its filter in agreement.

---

## 1. Registering Containment Filters

Plugins register containment filters using `r.addContainmentFilter(doc) { call -> ... }`:

```kotlin
r.addContainmentFilter("Blocks carried affects behind warded containers.") { call ->
  call.container.hasTag("warded") && call.item.hasTag("cursed")
}
```

The filter lambda receives a `PluginContainmentCall` containing:

* `call.world`: The plugin world facade.
* `call.holder`: The actor who currently holds the custody tree.
* `call.container`: An intermediate container on the path between the holder and the item.
* `call.item`: The item carrying the affect.
* `call.affect`: The `PluginItemAffect` under evaluation.

### How the Engine Evaluates Filters

When `refreshHoldings(actor)` runs, the engine traverses the actor's inventory and equipped gear:

1. For every carried affect on an item nested inside one or more containers, the engine checks every registered filter
   against each container along the custody path.
2. If any filter returns `true`, propagation is blocked: the affect does not reach the holder, and its stat deltas,
   sense deltas, flags, and modifiers are skipped.
3. Filters never see worn or directly carried items: only affects behind at least one container are filtered.

---

## 2. The Purity and Performance Contract

Because `refreshHoldings` is invoked on custody changes, inventory transfers, item tagging, and prototype updates,
containment filters are executed frequently.

Therefore, containment filters must adhere to a strict **purity contract**:

* **Read Only:** A filter should inspect only immutable or cached properties: `container.hasTag()`, `item.hasTag()`,
  `affect.name`, or `item.kind`.
* **No Side Effects:** A filter must never mutate world state, send messages, change inventories, or apply effects.
* **Fail Open:** If a filter throws an exception, the engine catches it, logs a `"plugin"` diagnostic, and treats the
  filter as returning `false` (does not block). The game keeps running.

---

## 3. The Shared Predicate: `bearsCurse`

A critical architectural pitfall in curse mechanics is inconsistency between what hurts the player and what binds the
player. If a warded container successfully isolates a curse's stat penalties, the player should also be able to drop
that container and walk away! If the filter and the restrictions disagree, the player could find themselves unable to
drop a box that isn't harming them, or suffering from a curse they can easily discard.

In `CursePlugin.kt`, the restrictions call the `bearsCurse` predicate, and the filter encodes the same rule: a curse
stops at the first `warded` container on its path. The filter checks one container at a time, so it doesn't call
`bearsCurse`, but the two must always agree:

```kotlin
/**
 * True if [item] carries a curse to whoever holds it: it is cursed, or holds one not shut away by a
 * ward.
 *
 * A cursed item is always cursed, even when tagged warded itself. A warded container that isn't
 * cursed never bears a curse, whatever it holds, because the ward stops propagation.
 */
internal fun bearsCurse(item: PluginItem): Boolean =
  item.hasTag("cursed") || (!item.hasTag("warded") && item.contents.any(::bearsCurse))
```

### The `bearsCurse` Truth Table

Notice how wards interact with curses recursively:

|               Configuration               | `bearsCurse` |                      Why                      |
|-------------------------------------------|--------------|-----------------------------------------------|
| Plain sword                               | `false`      | Not cursed, no contents.                      |
| Cursed amulet                             | `true`       | Tagged `cursed`.                              |
| Plain bag holding cursed amulet           | `true`       | Bag has cursed content and is not warded.     |
| Plain backpack holding bag holding amulet | `true`       | Propagates through multiple plain containers. |
| Warded box holding cursed amulet          | `false`      | The `warded` tag stops recursive propagation! |
| Plain sack holding warded box with amulet | `false`      | Ward stops the curse at the box level.        |
| Cursed warded box                         | `true`       | A cursed box is always cursed, ward or not.   |

Because `DROP`, `GIVE`, and `PUT` all check `bearsCurse`, the rule holds everywhere: **a ward stops the curse for both
passive affects and inventory restrictions.**

---

## 4. Worked Example: Walking Away Free

Here is the complete sequence of events when a player cleverly utilizes a warded container:

1. **The affliction:** The player carries `amulet_of_woe`. Because it has `applies = "carried"`, the player's `dex` is
   reduced by 2.
2. **Attempting to drop:** The player tries `drop amulet`. The `DROP` restriction checks `bearsCurse(amulet)`, which is
   `true`. The action is refused: `"It won't budge. It must be cursed!"`
3. **Using a warded container:** The player picks up a `lead_box` tagged `warded`.
4. **Isolating the relic:** The player commands: `put amulet box`
   * The destination (`lead_box`) is in the player's own inventory, so the `PUT` restriction allows it.
   * `refreshHoldings(player)` recomputes held affects. The containment filter checks the container:
     `container.hasTag("warded") && item.hasTag("cursed")` is `true`!
   * The `-2 dex` affect is blocked. The player's `dex` returns to normal!
5. **Walking away free:** The player commands: `drop box`
   * The `DROP` restriction evaluates `bearsCurse(lead_box)`.
   * Because `lead_box` is tagged `warded` and not cursed itself, `bearsCurse` returns `false`.
   * The drop succeeds: `"You drop a lead box."`
   * The box lands on the floor. The player walks into the next room, completely unburdened and free of the curse!
