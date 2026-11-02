# 03. Item Effects, Transform & Variants

When a player casts a blessing or uncurse ritual upon an item, what should happen?

In some games, an uncurse spell simply strips the `"cursed"` flag. But in a rich game world, lifting a curse often
reveals the item's true nature: an obsidian skull turns into an alabaster statuette; a rusted blade transforms into a
gleaming holy sword; or a cursed amulet of woe (`dex -2`) transforms into a blessed amulet of warding (`dex +1`).

The GUPS engine supports this cleanly through **item-targeted skill effects**, prototype **`variants`**, and the
**`transform`** verb. This tutorial explains how the Curse Plugin implements `curse.lift()` and `curse.bestow()`.

---

## 1. Item-Targeted Skills & Effect Primitives

In Starlark, skills can declare `target = "item"`. When cast, their effect acts directly upon the target item rather
than an actor:

```python
# skills/skills.star
skill("remove_curse",
      kind = "spell",
      target = "item",
      effect = curse.lift())

skill("bestow_curse",
      kind = "spell",
      target = "item",
      effect = curse.bestow())
```

When a player types `cast remove_curse amulet`, the engine finds the target item in the player's custody or room, runs
the cast warmup, and calls the effect primitive with `call.targetItem` pointing to the item facade.

---

## 2. Declaring Prototype Variants

Item prototypes can declare a `variants` mapping: a dictionary of free-form names to related item prototype labels:

```python
# zones/curse_zone/content.star
item("amulet_of_woe",
     name = "an amulet of woe",
     desc = "A cursed amulet radiating cold despair.",
     wear = "neck",
     tags = ["cursed"],
     affects = [item_affect("misfortune", applies = "carried", stat_deltas = stats(dex = -2))],
     # Maps the semantic variant "uncursed" to the cleansed prototype:
     variants = {"uncursed": ":amulet_of_warding"})

item("amulet_of_warding",
     name = "an amulet of warding",
     desc = "A holy amulet carved from pearl.",
     wear = "neck",
     affects = [item_affect("warding", applies = "worn", stat_deltas = stats(dex = 1))])
```

The engine gives the keys in `variants` no built-in meaning: keys like `"uncursed"`, `"identified"`, or `"broken"` are
agreed upon between content scripts and plugins.

---

## 3. Implementing `curse.lift()`

In `CursePlugin.kt`, the `lift` primitive checks whether the item declares an `"uncursed"` variant:

```kotlin
r.addEffectPrimitive(
  name = "lift",
  doc = "Lifts a curse from an item: transforms to its uncursed variant, or removes the cursed tag.",
  params = emptyList(),
) { call ->
  val item = call.targetItem ?: return@addEffectPrimitive
  val clean = item.variants["uncursed"]
  if (clean != null) {
    call.world.transform(item, clean)
  } else {
    call.world.removeTag(item, "cursed")
  }
}
```

Notice the branch:

1. **With a variant:** If `item.variants["uncursed"]` exists, the plugin calls `world.transform(item, clean)`.
2. **Without a variant:** If no variant is defined (e.g. an ordinary sword that was cursed dynamically in play), it
   simply removes the instance tag via `world.removeTag(item, "cursed")`.

---

## 4. Why Clean Versions Are Separate Prototypes

Why should an uncursed item transform into a separate prototype instead of simply removing a tag?

### A. Total Attribute Swaps

A cursed item often has completely different attributes from its uncursed counterpart:

* The cursed `amulet_of_woe` carries a passive penalty: `dex -2`.
* The uncursed `amulet_of_warding` grants a worn bonus: `dex +1`.
* It may have different names, descriptions, resale values, weight, or wear slots.

Attempting to script all these differences by manually adjusting instance deltas is fragile and tedious. A separate
prototype cleanly encapsulates the uncursed item's entire design.

### B. Clean Tag Stacking

In GUPS, an item with instance tag changes never merges into a stack.

If an item's prototype has `tags = ["cursed"]`, calling `world.removeTag(item, "cursed")` records an instance tag delta:
`removedTags += "cursed"`. Even if the item is cleansed, it permanently bears that tag difference, preventing it from
stacking with other uncursed items of the same type.

By using `world.transform(item, item.variants["uncursed"]!!)`, the instance adopts the clean prototype directly without
carrying spurious tag changes.

### C. What `transform` Preserves

When `world.transform` (or `ctx.transform`) swaps an item's prototype in place:

* **Preserved:** The item instance retains its unique `id`, location, count, custom attributes, instance affects, tag
  changes, name override, decay and timers.
* **Slot Re-check:** If the item was equipped, the engine verifies that the new prototype fits the current slot:
  * If it fits (e.g. `amulet_of_woe` to `amulet_of_warding` both use `neck`), it remains equipped in place and swaps
    `equip_affects` seamlessly.
  * If the new prototype does not fit the slot (e.g. transformed into a slot-less trinket), the engine moves it safely
    to the character's inventory.

---

## 5. Bestowing Curses: `curse.bestow()`

The complementary primitive is `curse.bestow()`:

```kotlin
r.addEffectPrimitive(
  name = "bestow",
  doc = "Bestows a curse on an item by tagging it cursed.",
  params = emptyList(),
) { call ->
  val item = call.targetItem ?: return@addEffectPrimitive
  call.world.addTag(item, "cursed")
}
```

When cast on any item (such as a plain steel dagger):

1. `call.world.addTag(item, "cursed")` adds `"cursed"` to the item's effective tags.
2. The item immediately begins bearing a curse (`bearsCurse(dagger) == true`).
3. If the item is carried, typing `drop dagger` or `give dagger bob` is instantly refused: `"It won't budge. It must be
   cursed!"`
4. If the item was a bare key on the keyring, gaining an instance tag moves it to the player's main inventory until the
   curse is lifted.
