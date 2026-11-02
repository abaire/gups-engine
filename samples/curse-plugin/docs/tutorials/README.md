# Curse Plugin Developer's Guide — Curses, Restrictions & Containment Filters

Welcome to the GUPS Curse Plugin Developer's Guide! This series of tutorials walks you through building, testing, and
integrating a plugin that implements **curses as content**: clinging cursed equipment, warded containers, curse lifting
and bestowing, and containment filtering.

The canonical reference implementation for these guides is
[`samples/curse-plugin/src/main/kotlin/gups/sample/curse/CursePlugin.kt`](../../src/main/kotlin/gups/sample/curse/CursePlugin.kt).
While that source file contains the complete, production-grade implementation, this tutorial series serves as its
conceptual companion: explaining the design philosophy, engine seams, execution model, and testing patterns.

---

## Why Curses Live in Plugins

In GUPS, the engine core is strictly genre-neutral. It powers fantasy dungeon crawls, sci-fi space simulations, modern
mysteries, and academic virtual worlds.

Consequently, the core engine **deliberately does not define curses, uncurse spells, or warded boxes**. Instead, the
engine provides general, orthogonal mechanisms:

* **Item Affects with Scope:** Items can carry affects that apply either when `worn` (equipped) or when `carried`
  anywhere in a character's custody tree.
* **Automatic Custody Propagation:** Carried item affects propagate upwards through nested containers to whoever holds
  them.
* **Item Restriction Points:** Four kernel restriction points (`REMOVE`, `DROP`, `GIVE`, `PUT`) intercept command-driven
  item movements.
* **Containment Filters:** Plugins can register lightweight filters to stop carried affects from propagating past
  specific containers (e.g. lead-lined or rune-warded boxes).
* **Prototype Transforms & Variants:** The engine supports in-place prototype swapping (`ctx.transform` and
  `world.transform`) backed by free-form `variants` mappings.

A curse plugin combines these primitives to create traditional fantasy curse gameplay entirely within plugin space.

---

## Architectural Overview

The relationship between course world content, the engine lifecycle, and the curse plugin is summarized below:

```text
┌────────────────────────────────────────────────────────┐
│               World Content (Starlark)                 │
│  - item("amulet", tags = ["cursed"],                   │
│         affects = [item_affect("woe",                  │
│                     applies = "carried")],             │
│         variants = {"uncursed": ":blessed_amulet"})    │
│  - item("box", tags = ["warded"])                      │
│  - skill("remove_curse", effect = curse.lift())        │
└───────────┬────────────────────────────────┬───────────┘
            │                                │
            ▼ (Content Tags & Variants)      ▼ (Skill Effects: curse.lift, bestow)
┌────────────────────────────────────────────────────────┐
│                     Engine Core                        │
│  - Recomputes heldEffects through custody tree         │
│  - Evaluates containment filters per container         │
│  - Checks restriction points during player commands:   │
│      REMOVE, DROP, GIVE, PUT                           │
│  - Swaps prototypes in place via transformItem()       │
└───────────▲────────────────────────────────▲───────────┘
            │                                │
            │ (Registration & Interception)  │ (Facade Verbs: transform, addTag)
┌───────────┴────────────────────────────────┴───────────┐
│                 Curse Plugin (Kotlin)                  │
│  - Restrictions: REMOVE (worn), DROP, GIVE, PUT        │
│  - Containment Filter: warded blocks cursed affects    │
│  - Effect Primitives: curse.lift(), curse.bestow()     │
│  - Restriction Predicate: bearsCurse(item)             │
└────────────────────────────────────────────────────────┘
```

---

## Tutorial Roadmap

1. **[01. Item Affects & Custody Propagation](01_item_affects_and_custody.md)**
   * `applies = "worn"` vs. `applies = "carried"`
   * The custody tree and how item affects reach the holder
   * Walk-through with an uncursed lucky charm: zero propagation code required
   * Walk-through with a cursed amulet: custody transfers and why containers are not escape hatches by default
2. **[02. Item Restriction Points](02_item_restriction_points.md)**
   * The four kernel points: `REMOVE`, `DROP`, `GIVE`, and `PUT`
   * The command-only rule: why player commands are intercepted while script verbs are trusted
   * Why `REMOVE` checks the item's own tag while `DROP`, `GIVE`, and `PUT` check `bearsCurse`
   * Preventing the wear and wield swap loopholes
3. **[03. Item Effects, Transform & Variants](03_item_effects_transform_and_variants.md)**
   * Item-targeted skills and plugin effect primitives
   * Declaring `variants` on prototypes
   * In-place prototype transformation via `world.transform()`
   * Why clean versions are separate prototypes rather than tag subtractions
   * Bestowing curses dynamically with `curse.bestow()`
4. **[04. Containment Filters](04_containment_filters.md)**
   * Intercepting carried affects behind containers with `addContainmentFilter()`
   * The purity and performance contract for filters
   * Keeping the filter in agreement with the `bearsCurse` helper the restrictions use
   * The worked example: locking a curse away in a warded box and walking away free
5. **[05. Testing the Curse Plugin](05_testing_the_curse_plugin.md)**
   * Fast unit testing with mock facades (`FakeRegistry`, `FakeWorld`, `FakeItem`, `FakeActor`)
   * Verifying the `bearsCurse` decision table
   * End-to-end integration testing with `TestRunner` in temporary fixture worlds
   * Authoring `*_test.star` harness scripts for equipment clinging, warding, lifting, and bestowing
