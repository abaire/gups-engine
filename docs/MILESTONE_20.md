# Milestone 20: Consumables, Charges and Declarative Use

**Prerequisites:** the `use`-from-the-ground change (the `planUse` edit in `KernelCommands.kt`, its §4.9 wording and the
`` `use reaches items on the ground, but not out of reach` `` test in `ItemCommandsTest.kt`) must be committed first;
check with `grep -n "ground = true" core/src/main/kotlin/gups/core/world/KernelCommands.kt` inside `planUse`. This
milestone doesn't depend on [M19](MILESTONE_19.md) and touches none of its files except `docs/SPEC.md`,
`docs/CHANGELOG.md` and the regenerated API files. If M19 lands first, keep its entries and add this milestone's next to
them. Where line numbers below are wrong, find the symbol by name.

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** [DESIGN_NEEDED.md](DESIGN_NEEDED.md): `## Items` → `### Consumables and charges`.
- **Already done (before this milestone):** `use` finds items worn, then carried, then on the ground within `get_lag`
  reach (`planUse` in `core/src/main/kotlin/gups/core/world/KernelCommands.kt`). §4.9 already says so. This milestone
  builds on that.
- **Design Summary:**
  - **Charges, not `kind`.** `item(charges=N)` (`None` by default, meaning unlimited) gives an item a number of uses.
    `kind` stays free-form; the engine gives `"consumable"` no meaning. A potion is `charges=1`.
  - **Per-instance state.** Each item instance carries its own `charges`, starting at the prototype's value when it
    spawns. Charges are saved with the item (no migration: the engine is unreleased), copied when a stack splits, and
    part of the stack-merge equality check. `transform` gives the item the new prototype's `charges` (full). A reload
    that gives a prototype `charges` fills existing instances; one that removes them makes instances unlimited; one that
    changes the number keeps each instance's count, capped at the new maximum.
  - **Stacks.** A stackable item may only have `charges=1` and can't use `empty="keep"` (load errors). Using one item of
    a stack spends that one item.
  - **Order of a use:** validation (refuses an item with 0 charges with the `use.empty` message) → `before_use` (may
    veto) → `use_effect` → `on_use` → spend one charge → `empty` action if that was the last → `use_lag`. A vetoed or
    refused use costs nothing.
  - **Spending.** The charge is spent only if the item is still in the world after `on_use`, wherever it now is. A hook
    that already destroyed the item (as today's template draught does) means nothing is spent and nothing fails.
  - **`item(empty=...)`**, default `"destroy"`: what happens when the last charge is spent. `"destroy"` removes the item
    (one item of a stack). `"keep"` leaves it at 0 charges, unusable until recharged. Any other value names one of the
    item's own `variants`, and the item is transformed into it (one item of a stack splits off first). `empty` without
    `charges` fails at load. `"destroy"` and `"keep"` always mean the action, even if a variant has that name.
  - **Recharging.** `ctx.set_charges(item, charges)` and the plugin facade's `setCharges` set an instance's charges to
    any value from 0 to the prototype's `charges`. They refuse unlimited items and stackable items. Setting 0 doesn't
    run `empty`; only a use does.
  - **Reading.** The script `ItemView` gets `charges` and `max_charges` (both `None` when unlimited). `PluginItem` gets
    `charges` and `maxCharges`. Charges aren't shown in `look`, `inventory` or `examine` in this milestone.
  - **Effect primitives move to the kernel.** `damage`, `heal`, `apply_affect`, `dispel`, `stun`, `summon` and
    `sequence` stop belonging to `skills`. `knockback` and `pull` stay with `track`. `skill()`, `cast` and everything
    else in the `skills` row stay where they are. Without `combat`, a `damage` effect still lowers vitals but engages
    nobody (`startCombat` already returns early).
  - **`item(use_effect=..., use_target=..., use_lag=N)`.** `use_effect` is one effect primitive (or `sequence`).
    `use_target` reuses the skill target words and defaults, and defaults to `"self"`:
    - `self`: the user. A target word is refused with `use.self_only`.
    - `ally`: the named actor in the room, or the user when no word is given.
    - `enemy`: the named actor in the room, or the user's primary target, or `use.at_whom`.
    - `item`: a named item, worn, carried or on the ground; `use.on_what` without a word. The effect may use only
      `apply_affect` (no `stunned`), plugin primitives and `sequence`, checked at load like an item skill.
    - `none`: the effect runs with no target; any word is ignored.

    There's no range check: `use` already reaches any actor in the room. `use_target` without `use_effect` fails at
    load. The user is the effect's caster, so `damage` runs through the user's `damage` chain with `ctx.skill == None`,
    and harmful effects engage like a skill's.

  - **Hooks and messages.** An item is usable if it has an `on_use` hook or a `use_effect`. When it has a `use_effect`,
    `ctx.target` in `before_use`/`on_use` is the resolved target (actor or item). Items with no `use_effect` resolve
    their target exactly as today. When an item has a `use_effect` and no `on_use` hook, the engine sends `use.done`
    ("You use {item}.") to the user and `use.done.room` ("{actor} uses {item}.") to onlookers by sight, before the
    effect. With an `on_use` hook, the hook does its own messaging.

  - **`use_lag`** (default 0, at least 0) sets the user's recovery lag after a completed use, like a skill's `lag`. It
    applies to every usable item, hook-only ones included. It's kernel, not `combat`. It isn't a modifier point.

  - **Plugins.** `PluginItem.charges`, `PluginItem.maxCharges` and `Facade.setCharges`. `PluginApi.VERSION` stays 1:
    api_version 1 is unreleased, as in M17.

  - **Template.** `healing_draught` becomes `charges=1, use_effect=heal(...)` with no hook. Tutorials 03 and 06 teach
    `charges`, `empty`, `use_effect`, `use_target` and `use_lag`. Tutorial 06 keeps a hook example: a lever on the
    ground that opens a door.

  - **Out of scope:** mobs using items (`act_use`), showing charges in `look`/`inventory`, a `use_lag` modifier point,
    range bands on `use`, a `drink`/`eat` command.

- **Specification Impact:**

  - §3.3 (Item fields: per-instance `charges`)
  - §3.10 (feature table: effect primitives move from `skills` to the kernel; the "Without `skills`" bullet)
  - §4.2 (the `item(...)` example and declaration rules: `charges`, `empty`, `use_effect`, `use_target`, `use_lag`)
  - §4.3 (the sentence that puts the whole section under `skills`; effect primitives usable outside skills)
  - §4.5 (`ctx.set_charges`, `item.charges`, `item.max_charges`)
  - §4.9 (`use`: charges, order, `use_effect`, targets, `use_lag`, messages; drop "The engine never uses an item up")
  - §4.15 (plugin item facade: charges)
  - §4.18 (new `use.*` message ids, listed by default text in §4.9)
  - §7.4 (new M20 row)
  - `docs/CHANGELOG.md` under `## api_version 1 (unreleased)`, `docs/PLUGINS.md`
  - `docs/api/api-1.json` and the two `cli` golden files (regenerated, never hand-edited)
- **Decision Record Updates:** None. D-114 ("the engine never uses an item up") was already folded into §4.9; Phase 2
  rewrites that sentence.

### Guardrails for every phase

1. Follow `AGENTS.md`: no spec section numbers or decision ids in code comments; all Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`.
3. Add CHANGELOG bullets in a new `### Consumables and charges` subsection at the top of `## api_version 1 (unreleased)`
   (above any M19 subsection). Create it in the first phase that needs it and keep adding to it.
4. In any phase that changes the script API (a declaration parameter, a view field, a `ctx` verb, a message id, feature
   ownership), regenerate all three API files **before** the phase's final `./gradlew check`:
   - `./gradlew :cli:test -Dgups.updateGolden=true` rewrites `cli/src/test/resources/api.golden.json` and
     `cli/src/test/resources/docs-golden/api.json`;
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout, rewrites `docs/api/api-1.json`;
   - read the diff: only the symbols this phase adds or changes should differ. `ApiDiffTest` must then pass.
   - if a test fails because a new symbol has no example, add one in `script/.../DocExamples.kt`, mirroring an existing
     entry.
5. Gradle commands must run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat`.
6. No database migrations. New persisted fields get defaults.
7. Never read or touch `local-world/`.

---

## Phase 1: Effect primitives move to the kernel

### Objective & Scope

- **In Scope:** feature ownership of `damage`, `heal`, `apply_affect`, `dispel`, `stun`, `summon` and `sequence` moves
  from `skills` to the kernel; tests and spec follow.
- **Out of Scope:** anything about items or `use`. Nothing calls the primitives outside skills yet, apart from
  `ctx.apply_affect` and the `damage`/`heal` verbs, which are already kernel.

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Declarations.kt`: `effect(name, effect)` (around line 1661) calls `need(name)`,
    which asks `FeatureOwnership.refused`. It needs no change.
  - `script/src/main/kotlin/gups/script/ApiModel.kt`: reads `FeatureOwnership.of` for each symbol's `"feature"`.
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Ownership.kt`
  - `script/src/test/kotlin/gups/script/FeatureScriptTest.kt` (and any other test that asserts "damage() needs the
    skills feature" or similar: `grep -rn "needs the skills feature" script/src/test cli/src/test harness/src/test`)
  - `cli/src/test/kotlin/gups/cli/DocsWorldTest.kt` if it asserts the primitives are missing from a no-`skills` world's
    docs
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the three regenerated API files
- **Do Not Touch:** `core/`, `plugin-api/`, `template-world/`, `samples/`.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§3.10 feature table.** Kernel row: after "the `damage` and `heal` verbs and `mitigate`", add "; the effect
   primitives `damage`, `heal`, `apply_affect`, `dispel`, `stun`, `summon` and `sequence`". `skills` row: delete ", the
   effect primitives `damage`, `heal`, `apply_affect`, `dispel`, `stun`, `summon` and `sequence`".
2. **§3.10, the "Without `skills`" bullet.** Replace with: "**Without `skills`,** `use` and item `on_use` hooks are the
   way to act on things, and the effect primitives still work; affects still come from `ctx.apply_affect`, effect
   primitives and `equip_affects`." Phase 5 adds `item(use_effect=...)` to this bullet.
3. **§4.3, first line.** Replace "Everything in this section is the `skills` feature (§3.10)." with "Skills, `cast` and
   everything about casting are the `skills` feature (§3.10). The effect primitives are kernel: skills, hooks and
   `item(use_effect=...)` all use them. `knockback` and `pull` need `track`."

No `D-xxx` changes.

### Implementation Blueprint

- **Signatures & Types:** none new.
- **Algorithmic Steps & Invariants:**
  1. `Ownership.kt`, `subtrees`: delete the line `owns(Feature.SKILLS, "damage", "heal", "apply_affect", "dispel",
     "stun", "summon", "sequence")`. Keep `owns(Feature.TRACK, "knockback", "pull")` and `owns(Feature.SKILLS, "skill",
     "view:skill")`.
  2. Invariant: `FeatureOwnership.of("damage")` and `of("damage.dice")` now return `null` (kernel). `ctx.damage`,
     `ctx.heal` and `ctx.apply_affect` are different symbols (`ctx.` prefix) and stay kernel.
  3. Update the tests that expected the refusal, then add the CHANGELOG subsection with the bullet "The effect
     primitives `damage`, `heal`, `apply_affect`, `dispel`, `stun`, `summon` and `sequence` no longer need the `skills`
     feature; `knockback` and `pull` still need `track`."
  4. Regenerate the API files (guardrail 4). Expect only `"feature": "skills"` to disappear from those seven entries and
     their parameters.

### Test & Documentation Requirements

- `FeatureScriptTest.kt`: add `` `effect primitives work in a world without skills` ``. A world with `features =
  ["combat"]` loads a package whose `on_use` hook builds `heal(dice = "1d4", vital = "hp")` at top level (a module
  constant), without a load error. Also assert that `knockback(steps = 1)` in a world without `track` still fails with
  "needs the track feature".

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `ApiDiffTest` against the regenerated baseline.
- [] `./gradlew :script:test --tests '*FeatureScriptTest*'` passes, including the new case.
- [] `grep -n '"feature": "skills"' docs/api/api-1.json` shows no `damage`, `heal`, `apply_affect`, `dispel`, `stun`,
  `summon` or `sequence` entry.

---

## Phase 2: Charges in core: state, lifecycle, persistence and spending

### Objective & Scope

- **In Scope:** `ItemProto.charges` and `ItemProto.empty`; `Item.charges`; spawn, split, merge, transform, reload and
  persistence; `World.setCharges`; spending and `empty` in `planUse`; the `use.empty` message.
- **Out of Scope:** the Starlark `item(charges=, empty=)` arguments, views and `ctx.set_charges` (Phase 3);
  `use_effect`, targets and `use_lag` (Phases 4–5); plugins (Phase 6).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Items.kt`: `spawnItem` (line 16), `moveItem`'s split (around line 60–105),
    `canStack`, `stack` (around line 142–176), `destroy` (around line 183).
  - `core/src/main/kotlin/gups/core/world/Verbs.kt`: `transformItem` (around line 320).
  - `core/src/main/kotlin/gups/core/world/Reconcile.kt`: the item loop (around line 90).
  - `persist/src/main/kotlin/gups/persist/Codec.kt`: `ItemPayload` (around line 230), the writer (around line 500) and
    `applyItem` (around line 625).
  - `persist/src/test/kotlin/gups/persist/ItemAffectsPersistenceTest.kt` (round-trip pattern).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt`
  - `core/src/main/kotlin/gups/core/model/Entities.kt`
  - `core/src/main/kotlin/gups/core/world/Items.kt`
  - `core/src/main/kotlin/gups/core/world/Verbs.kt`
  - `core/src/main/kotlin/gups/core/world/Reconcile.kt`
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt` (`planUse`, `mergesInto`)
  - `core/src/main/kotlin/gups/core/world/KernelMessages.kt`
  - `persist/src/main/kotlin/gups/persist/Codec.kt`
  - New `core/src/test/kotlin/gups/core/world/ChargesTest.kt`; new
    `persist/src/test/kotlin/gups/persist/ChargesPersistenceTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API files (the new message id is an API symbol)
- **Do Not Touch:** `script/` main code, `plugin-api/`, `template-world/`.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§3.3, Item, "Quantities and decay" bullet.** Append: "An item whose prototype has `charges` carries its own
   `charges`, the uses it has left, starting at the prototype's value; a split stack's new part copies it, and stacks
   merge only with equal charges. `transform` gives the item the new prototype's `charges`. A reload that adds `charges`
   to a prototype fills its instances, one that removes them makes them unlimited, and one that changes the number caps
   each instance at the new value."
2. **§4.9, the `use` bullet.** Replace its last sentence ("The engine never uses an item up; `on_use` destroys it if it
   should be.") with: "An item with `charges` and none left is refused (\"There are no charges left in {item}.\"). After
   `on_use`, if the item is still in the world, the use spends one charge, or one item of a stack; the last charge runs
   the item's `empty` action: destroy it, keep it at 0 charges, or `transform` it into the named variant. An item
   without `charges` is never used up; `on_use` destroys it if it should be."

### Implementation Blueprint

- **Signatures & Types:**

  `Protos.kt`, next to `ItemProto`:

  ```kotlin
  /** What happens to an item when its last charge is spent. */
  sealed interface EmptyAction {
    /** Removes the item, or one item of a stack. */
    data object Destroy : EmptyAction

    /** Leaves the item at 0 charges until it is recharged. */
    data object Keep : EmptyAction

    /** Transforms the item into its variant named [name]. */
    data class Variant(val name: String) : EmptyAction
  }
  ```

  `ItemProto`, new fields after `plural` (keep KDoc style):

  ```kotlin
  /** Uses before the item is spent, or null for unlimited. */
  val charges: Int? = null,
  /** What happens when the last charge is spent; ignored without [charges]. */
  val empty: EmptyAction = EmptyAction.Destroy,
  ```

  `Entities.kt`, `class Item`, after `count`:

  ```kotlin
  /** Uses this instance has left, or null when its prototype has unlimited uses. */
  var charges: Int? = proto.charges
  ```

  `Items.kt`:

  ```kotlin
  /**
   * The `set_charges` verb: sets [item]'s remaining uses to [charges], between 0 and its
   * prototype's charges. Refuses unlimited and stackable items. Setting 0 doesn't run the empty action.
   */
  fun World.setCharges(item: Item, charges: Int)

  /** Spends one use of [item] after a `use`, running its empty action when that was the last. */
  internal fun World.spendCharge(item: Item)
  ```

  `KernelMessages.kt`, next to `USE_CANT`:

  ```kotlin
  val USE_EMPTY = msg("use.empty", "There are no charges left in {item}.", "item")
  ```
- **Algorithmic Steps & Invariants:**
  1. **Spawn:** the `Item` property initializer covers `spawnItem` and every other constructor call. Check each `Item(`
     constructor call in `core/` and `persist/` (`grep -rn "Item(allocateId()\|= Item(" core persist`) and set `charges`
     explicitly where a copy is made (step 2).
  2. **Split:** in `moveItem`'s split path, after `part.decayAt = item.decayAt`, add `part.charges = item.charges`.
  3. **Merge:** in `stack()` and in `KernelCommands.kt`'s `mergesInto`, add `it.charges == item.charges` to the peer
     predicate, next to `it.decayAt == item.decayAt`.
  4. **Transform:** in `transformItem`, after `if (!newProto.stackable) item.count = 1`, add `item.charges =
     newProto.charges`.
  5. **Reload:** in `Reconcile.kt`'s `p != null` branch, before `item.proto = p`, compute the new charges from the old
     prototype `item.proto` and the new one `p`:
     - `p.charges == null` → `null`;
     - `p.stackable` → `p.charges`;
     - `item.proto.charges == null` → `p.charges`;
     - otherwise `minOf(item.charges ?: p.charges, p.charges)`.

     Set `item.dirty = true` when the value changed.

  6. **`setCharges`:** `require(item.id in entities)`; `val max = requireNotNull(item.proto.charges) { "set_charges:
     ${item.name} has unlimited uses" }`; `require(!item.proto.stackable) { "set_charges: ${item.name} is stackable" }`;
     `require(charges in 0..max) { "set_charges: charges must be between 0 and $max, got $charges" }`; assign; `dirty =
     true`. Mirror the `require` style of `destroy`.

  7. **`spendCharge`:** return at once if `item.id !in entities` or `item.charges == null`.

     - Stackable (always `charges == 1`): one unit is spent, so go straight to the empty action for one item.
     - Otherwise `item.charges = item.charges!! - 1`, `dirty = true`; if it is now greater than 0, return.
     - Empty action:
       - `Destroy` → `destroy(item, 1)`.
       - `Keep` → nothing.
       - `Variant(name)` → `val label = item.proto.variants.getValue(name)` (the loader guarantees it). If `item.count
         == 1`, call `transformItem(item, label)`. Otherwise: remember `item.location` (use `ItemLocation.Carried(a)` if
         it is `Equipped(a, _)`, since part of a stack can't be equipped), `destroy(item, 1)`, `val part =
         spawnItem(label, thatLocation)`, copy `item.customAttributes` into `part`, then `stack(part)`.
  8. **`planUse`:**
     - In `validate`, after the hook check, add `if (item.charges == 0) return w.msg(KernelMsg.USE_EMPTY, "item" to
       item.name)`. Validation runs before `veto`, so `before_use` never sees an empty item.
     - In `resolve`, after `w.dispatch(HookName.ON_USE, ...)`, add `if (w.item(item.id) === item) w.spendCharge(item)`.
       Keep `return 0` (Phase 4 adds lag).
  9. **Persistence:** `ItemPayload` gets `val charges: Int? = null` after `count`. The writer sets `charges =
     e.charges`. `applyItem` sets `item.charges = if (item.proto.charges == null) null else p.charges ?:
     item.proto.charges`. Invariant: a payload saved before this milestone (no `charges`) restores a full item.
  10. Add the CHANGELOG bullet for the `use.empty` message and spending (wording from the §4.9 change). Regenerate the
      API files (guardrail 4): expect only `message:use.empty`.

### Test & Documentation Requirements

- `ChargesTest.kt` (new, `core`). Build protos in the test the way `ItemCommandsTest` does (`extra` list,
  `Fixture(scripts = host, protos = Prototypes(T.rooms, T.mobs, T.items + extra, T.skills))`, a `RecordingHost` with an
  `ON_USE` handler). Prototypes: `wand` (`charges = 3`, `empty = Keep`), `potion` (`charges = 1`, `stackable = true`),
  `flask` (`charges = 2`, `empty = Variant("empty")`, `variants = mapOf("empty" to emptyFlask)`), `emptyFlask` (no
  charges, no hook), `lamp` (no charges). Cases:
  - `` `each use spends a charge and an empty kept item is refused` ``: three uses take the wand to 0; the fourth says
    "There are no charges left in a wand." and doesn't fire `on_use` (count handler calls).
  - `` `using one of a stack spends one item` ``: a stack of 3 potions becomes 2.
  - `` `the last charge transforms into the empty variant` ``: two uses of `flask` leave an item with `protoLabel ==
    emptyFlask` and `charges == null`.
  - `` `a variant split off a stack keeps the rest` ``: a stackable `charges = 1` prototype with a variant, stack of 2:
    one use leaves 1 of the original and 1 variant in the inventory.
  - `` `a hook that destroys the item spends nothing` ``: `on_use` handler calls `w.destroy(item)`; no exception, no
    diagnostic.
  - `` `a vetoed use spends nothing` ``: `before_use` denies; charges unchanged.
  - `` `items without charges are never used up` ``: `lamp` used 5 times stays.
  - `` `set_charges refuses unlimited, stackable and out-of-range values` ``:
    `assertFailsWith<IllegalArgumentException>`.
  - `` `split copies charges and different charges don't merge` ``: with a stackable `charges = 1` prototype, moving 1
    of a stack of 3 to the ground gives a part with `charges == 1`. Then set that part's `charges` field directly to 0
    and move it back into the inventory: `stack()` leaves it as a separate item (the inventory holds two items of that
    prototype).
  - `` `transform and reload reset or cap charges` ``: `transformItem(wand, otherWandWith5)` gives 5; a reload that
    changes `wand` to `charges = 2` caps an instance with 3 left to 2; one that removes `charges` makes it `null`.
    Follow an existing reload test for how to swap prototypes (`grep -rn "reconcile\|reload(" core/src/test/kotlin |
    head`).
- `ChargesPersistenceTest.kt` (new, `persist`): mirror `ItemAffectsPersistenceTest`. A wand with 1 charge left
  round-trips with `charges == 1`; a payload JSON without `charges` restores a full item.
- `ItemCommandsTest.kt`: the existing `use` tests must still pass unchanged.
- KDoc on every new symbol, as above.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `./gradlew :core:test --tests '*ChargesTest*'` and `./gradlew :persist:test --tests '*ChargesPersistenceTest*'`
  pass.
- [] `grep -n "never uses an item up" docs/SPEC.md` returns nothing.

---

## Phase 3: Charges in Starlark: declaration, views and `ctx.set_charges`

### Objective & Scope

- **In Scope:** `item(charges=, empty=)` with load checks; `ItemView.charges` and `max_charges`; `ctx.set_charges`.
- **Out of Scope:** `use_effect`, `use_target`, `use_lag`; plugins; template content.

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Declarations.kt`: `item(...)` (around line 929–1024) and `ITEM_SPEC` (around
    line 2228). The `variants` parsing shows the "did you mean"-free validation style; `suggest`-style helpers exist
    elsewhere in the file (`grep -n "did you mean" Declarations.kt`).
  - `script/src/main/kotlin/gups/script/Ctx.kt`: `destroy` (around line 1223) as the verb pattern.
  - `script/src/main/kotlin/gups/script/Views.kt`: `ItemView` (around line 551).
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Declarations.kt`
  - `script/src/main/kotlin/gups/script/Views.kt`
  - `script/src/main/kotlin/gups/script/Ctx.kt`
  - `script/src/test/kotlin/gups/script/LoaderTest.kt` (load checks) and a new
    `script/src/test/kotlin/gups/script/ChargesScriptTest.kt` (behavior through Starlark)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API files
- **Do Not Touch:** `core/` main code (Phase 2 provides `setCharges`), `plugin-api/`, `template-world/`.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§4.2, the `item("short_bow", ...)` example.** Change the line `commands={}, on_use=None,` to:

   ```python
   commands={}, on_use=None,
   charges=None, empty="destroy",  # uses, and what the last one does (§4.9)
   ```
2. **§4.2, declaration rules.** Add a bullet after "**Item slots:**":

   > - **Charges:** `charges` is the number of uses an item has, at least 1, or `None` for unlimited. `empty` says what
   >   happens when the last is spent: `"destroy"` (the default), `"keep"` (the item stays at 0 charges), or the name of
   >   one of the item's `variants`, which it is transformed into. `empty` needs `charges`. A stackable item may only
   >   have `charges = 1` and can't `keep`.

3. **§4.5.** In the verbs list, next to `destroy`, add `set_charges(item, charges)`: "sets an item's remaining uses,
   from 0 to its prototype's `charges`; unlimited and stackable items are refused, and 0 doesn't run `empty`." In the
   item view fields (or the sentence that points to `Views.kt` for them), mention `charges` and `max_charges`, `None`
   when unlimited.

### Implementation Blueprint

- **Signatures & Types:**
  - `ITEM_SPEC.params`, after `variants`, two `DeclParam`s in the multi-line style of their neighbours:
    - `"charges"`, type `"int"`, doc "Uses before the item is spent, at least 1; None for unlimited.", default `"None"`.
    - `"empty"`, type `"str"`, doc "After the last charge: 'destroy', 'keep', or the name of one of the item's
      variants.", default `"'destroy'"`.
  - `ItemView`, two struct fields in the style of `plural` (`structField = true, allowReturnNones = true`):
    - `charges`, doc "Uses left, or None when unlimited.", body `read { item.charges?.let(::sInt) }`.
    - `max_charges` (Kotlin `maxCharges()`), doc "The prototype's charges, or None when unlimited.", body `read {
      item.proto.charges?.let(::sInt) }`.
  - `Ctx.kt`, after `destroy`, a verb in the same `@StarlarkMethod` style: `name = "set_charges"`, doc "Sets an item's
    remaining uses, from 0 to its prototype's charges.", parameters `item` ("Item with charges.") and `charges` ("Uses
    left; 0 doesn't run the empty action."), both `named = true`, no defaults; Kotlin signature `fun setCharges(item:
    Any, charges: Any): Any`.
- **Algorithmic Steps & Invariants:**
  1. In `item(...)`, parse `charges = d.int("charges")` and the raw `empty = d.str("empty")`.
  2. Load checks, each with `fail(...)`, in this order:
     - `charges != null && charges < 1` → `"item(charges): must be at least 1"`.
     - `empty != null && charges == null` → `"item(empty): needs charges"`.
     - stackable and `charges != null && charges != 1` → `"item(charges): a stackable item can only have 1 charge"`.
     - stackable and `empty == "keep"` → `"item(empty): a stackable item can't keep an empty item"`.
     - `empty` not `"destroy"`, not `"keep"` and not a key of `variants` → `"item(empty): '<x>' is not 'destroy', 'keep'
       or one of this item's variants"`.
  3. Map to `EmptyAction`: `null`/`"destroy"` → `Destroy`, `"keep"` → `Keep`, else `Variant(empty)`. Pass `charges` and
     `empty` to `ItemProto`.
  4. `set_charges`: mirror `destroy`'s shape: `verb("set_charges") { val i = itemArg(item, "set_charges(item)"); val n =
     int(charges, "set_charges(charges)"); ...; world.setCharges(i, n); Starlark.NONE }`. Turn each `setCharges`
     precondition into a Starlark `fail(...)` with the same text before calling it, so scripts get a clean error, not a
     Kotlin exception.
  5. CHANGELOG bullets: `item(charges=, empty=)`, `item.charges`/`item.max_charges`, `ctx.set_charges`. Regenerate the
     API files (guardrail 4).

### Test & Documentation Requirements

- `LoaderTest.kt`: one case per load check in step 2, asserting the message; and a passing case with `charges = 3, empty
  = "spent", variants = {"spent": ":spent_wand"}`.
- `ChargesScriptTest.kt`: follow an existing script-world test that runs commands (for example `M5ScriptTest`'s `on_use`
  case: `grep -n "on_use" script/src/test/kotlin/gups/script/M5ScriptTest.kt`). Cases:
  - a `charges = 2, empty = "keep"` wand whose `on_use` sends `item.charges` (the value before spending) and
    `item.max_charges`; two uses print "2/2" then "1/2"; the third is refused with "There are no charges left in".
  - a hook calls `ctx.set_charges(ctx.item, 5)` on a `charges = 3` item and fails with "between 0 and 3".
  - `ctx.set_charges` on an unlimited item fails with "unlimited".
- KDoc on new members; DeclParam docs are the user-facing docs.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `ApiDiffTest`.
- [] `./gradlew :script:test --tests '*ChargesScriptTest*' --tests '*LoaderTest*'` passes.
- [] The regenerated `docs/api/api-1.json` diff shows only `item.charges`, `item.empty`, `view:item.charges`,
  `view:item.max_charges` and `ctx.set_charges` additions (names as the API model spells them).

---

## Phase 4: `use_effect`, `use_target` and `use_lag` in core

### Objective & Scope

- **In Scope:** the three `ItemProto` fields; target resolution, effect application, messages and lag in `planUse`.
- **Out of Scope:** the Starlark arguments and load checks (Phase 5); plugins; template.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt`: `CastAction.chooseTarget` (around line 100–128) and the
    `targetItem` check in `validate` (around line 135) — mirror both.
  - `core/src/main/kotlin/gups/core/world/Combat.kt`: `applyEffect` and `applyItemEffect` (around line 512–600).
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt`: `planWear`'s `tellSight` room message (around line 740) as
    the onlooker message pattern.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt`
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt` (`planUse`)
  - `core/src/main/kotlin/gups/core/world/KernelMessages.kt`
  - New `core/src/test/kotlin/gups/core/world/UseEffectTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API files (new message ids)
- **Do Not Touch:** `Combat.kt` (`applyEffect` is used as is), `SkillCommands.kt`, `script/`, `plugin-api/`.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`, §4.9, the `use` bullet. Replace the whole bullet (keeping Phase 2's charges sentences) with:

> - **`use <item> [target]`:** the item is worn, carried, or on the ground within `get_lag` reach (in that order of
>   search), and must have an `on_use` hook or a `use_effect` (§4.2) ("You can't use that." otherwise). An item with
>   `charges` and none left is refused ("There are no charges left in {item}."). Without a `use_effect`, the target is
>   an actor in the room, else an item. With one, `use_target` picks it as for skills (§4.3) without a range check:
>   `self` is the user (a target word is refused: "You can only use {item} on yourself."); `ally` the named actor or the
>   user; `enemy` the named actor or the user's primary target ("Use {item} on whom?"); `item` a named item worn,
>   carried or on the ground ("Use {item} on what?"); `none` nothing. The order is: `before_use` (can veto), the
>   `use_effect` with the user as caster, `on_use`, then, if the item is still in the world, one charge spent (or one
>   item of a stack) and on the last, the item's `empty` action: destroy it, keep it at 0 charges, or `transform` it
>   into the named variant. An item without `charges` is never used up; `on_use` destroys it if it should be. A use that
>   isn't refused or vetoed sets the user's recovery lag to `use_lag`. When the item has a `use_effect` and no `on_use`
>   hook, the user sees "You use {item}." and onlookers "{actor} uses {item}." before the effect.

### Implementation Blueprint

- **Signatures & Types:**

  `ItemProto`, after `empty`:

  ```kotlin
  /** Effect a `use` applies, with the user as caster, or null. */
  val useEffect: Effect? = null,
  /** Whom [useEffect] acts on, chosen as for a skill's target. */
  val useTarget: TargetKind = TargetKind.SELF,
  /** Recovery lag in pulses after a use. */
  val useLag: Int = 0,
  ```

  `KernelMessages.kt`:

  ```kotlin
  val USE_SELF_ONLY = msg("use.self_only", "You can only use {item} on yourself.", "item")
  val USE_AT_WHOM = msg("use.at_whom", "Use {item} on whom?", "item")
  val USE_ON_WHAT = msg("use.on_what", "Use {item} on what?", "item")
  val USE_DONE = msg("use.done", "You use {item}.", "item")
  val USE_DONE_ROOM = msg("use.done.room", "{actor} uses {item}.", "actor,item")
  ```

  Check the exact `msg(...)` signature and placeholder-list format against `WEAR_ROOM` before writing these.

- **Algorithmic Steps & Invariants:**

  1. In `planUse`, after the item is found, branch on `item.proto.useEffect`:
     - `null`: target resolution stays exactly as it is now.
     - not `null`: resolve by `item.proto.useTarget`, refusing at plan time with `w.tell(...)` and `return null` like
       the existing refusals:
       - `SELF` → a word given → `USE_SELF_ONLY`; target = `c.actor`.
       - `ALLY` → `word?.let { w.findActor(c.actor, it) ?: refuse NOT_HERE } ?: c.actor`.
       - `ENEMY` → `word?.let { findActor ?: refuse NOT_HERE } ?: c.actor.primaryTarget?.let(w::actor) ?: refuse
         USE_AT_WHOM`.
       - `ITEM` → no word → `USE_ON_WHAT`; `targetItem = equipment match ?: w.findItem(c.actor, word, inventory = true,
         ground = true) ?: refuse DONT_SEE`; target actor = `null`.
       - `NONE` → no target; the word is ignored.
  2. Capture `val lag = item.proto.useLag` and `val announce = item.proto.useEffect != null && !w.scripts.hasHook(item,
     HookName.ON_USE)` at plan time, so a transform or destroy during the use can't change them.
  3. `validate`: replace the hook check with `if (!w.scripts.hasHook(item, HookName.ON_USE) && item.proto.useEffect ==
     null) return USE_CANT`. Keep the existing target-actor presence check. For an `ITEM` target, add the same check as
     `CastAction.validate` for `targetItem` (still in the world and present in the room or held).
  4. `resolve`, in order:
     1. If `announce`: `w.tell(actor, USE_DONE, "item" to item.name)` and the onlooker line with `w.tellSight(room,
        "use", actor, w.msg(USE_DONE_ROOM, "actor" to actor.name.cap(), "item" to item.name), actor)`, mirroring
        `planWear`.
     2. Apply the effect: `ITEM` → `targetItem?.let { w.applyItemEffect(effect, actor, it) }`; otherwise
        `w.applyEffect(effect, actor, target)` (`target` is `null` for `NONE`; `applyEffect` handles `summon` and plugin
        primitives without one).
     3. `w.dispatch(HookName.ON_USE, ...)` as now.
     4. `if (w.item(item.id) === item) w.spendCharge(item)` (Phase 2).
     5. `return lag`.
  5. Invariant: an item with no `use_effect`, no `charges` and `use_lag = 0` behaves exactly as before this milestone.
  6. CHANGELOG bullets for the five messages. Regenerate the API files (guardrail 4): expect only the five
     `message:use.*` additions.

### Test & Documentation Requirements

- `UseEffectTest.kt` (new, `core`), protos built in the test with `Effect` values directly (`Effect.Heal(Dice(1, 1, 4),
  "hp")` heals exactly 5). Cases:
  - `` `a self effect heals the user and announces it without a hook` ``: output has "You use a salve."; an onlooker in
    the room sees "Ann uses a salve."; hp up by 5.
  - `` `a self item refuses a target word` ``.
  - `` `an ally effect defaults to the user and can name another actor` ``.
  - `` `an enemy effect defaults to the primary target and asks whom otherwise` ``: with no fight → "Use a wand on
    whom?"; with `startCombat` → damage lands on the goblin.
  - `` `an item-target effect applies an item affect` ``: `Effect.ApplyAffect("glow", 10, applies =
    AffectScope.CARRIED)` on a named item; no word → "Use a polish on what?".
  - `` `a none effect summons` ``.
  - `` `with an on_use hook the engine sends no use message` ``.
  - `` `use_lag sets the recovery lag, and a veto costs none` ``: `useLag = 3` → `p.waitState == 3`; a vetoed use leaves
    0.
  - `` `the effect runs before on_use and the charge after it` ``: the `ON_USE` handler records the user's hp (already
    healed) and the item's charges (not yet spent).
  - Without `combat`: build the fixture with features lacking `combat` (find how `FeatureTest`/`Fixture` take features)
    and check an `ENEMY` damage effect lowers hp without engaging.
- KDoc on the new fields and messages.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `ApiDiffTest`.
- [] `./gradlew :core:test --tests '*UseEffectTest*' --tests '*ItemCommandsTest*' --tests '*ChargesTest*'` passes.
- [] `grep -n "use_target" docs/SPEC.md` shows the new §4.9 bullet.

---

## Phase 5: `use_effect`, `use_target` and `use_lag` in Starlark

### Objective & Scope

- **In Scope:** the three `item(...)` arguments, their load checks, and a shared effect-target check with `skill()`.
- **Out of Scope:** plugins, template content.

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Declarations.kt`: `skill(...)` effect handling (around line 1060–1100:
    `effectValue`, `effectRefs`, `flattenLeaves`, the `TargetKind.ITEM` leaf checks, and the `applies`/`duration` checks
    for actor skills); `SKILL_SPEC`'s `target` param for `allowedValues`.
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Declarations.kt`
  - `script/src/test/kotlin/gups/script/LoaderTest.kt`, `script/src/test/kotlin/gups/script/ChargesScriptTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API files
- **Do Not Touch:** `core/`, `plugin-api/`, `template-world/`.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§4.2 example.** After the `charges=None, empty="destroy",` line add:

   ```python
   use_effect=None, use_target="self", use_lag=0,  # declarative use (§4.9)
   ```
2. **§3.10, the "Without `skills`" bullet.** Change "and the effect primitives still work" to "and the effect primitives
   still work, in hooks and in `item(use_effect=...)`".
3. **§4.2 declaration rules.** Extend the **Charges** bullet, or add a **Use** bullet after it:

   > - **Use:** `use_effect` is an effect primitive (§4.3) that `use` applies with the user as caster. `use_target`
   >   (`enemy`, `ally`, `self`, `item` or `none`; `self` by default) needs a `use_effect`; an `item` target allows only
   >   `apply_affect` (without `stunned`), plugin primitives and `sequence`, as for item skills, and only it may give
   >   `apply_affect` an `applies` or a `None` duration. `use_lag` is the recovery lag in pulses after a use, at least
   >   0\.

### Implementation Blueprint

- **Signatures & Types:**
  - `ITEM_SPEC.params`, after `empty`, three `DeclParam`s in the style of their neighbours:
    - `"use_effect"`, the same type string `SKILL_SPEC` uses for `effect`, doc "Effect primitive that use applies, with
      the user as caster.", default `"None"`.
    - `"use_target"`, type `"str"`, doc "Whom use_effect acts on: 'enemy', 'ally', 'self', 'item' or 'none'.", default
      `"'self'"`, `allowedValues` the same list `SKILL_SPEC`'s `target` uses.
    - `"use_lag"`, type `"int"`, doc "Recovery lag in pulses after a use; at least 0.", default `"0"`.
  - A new private helper, extracted from `skill()`'s effect block, with KDoc "Fails the load if [effect]'s primitives
    don't fit [target]; [what] prefixes the message and [subject] names the effect in the item-target message.":
    `private fun checkEffectTarget(effect: Effect, target: TargetKind, what: String, subject: String)`.
- **Algorithmic Steps & Invariants:**
  1. Move the body of `skill()`'s `proto.effect?.let { eff -> ... }` leaf checks into `checkEffectTarget`, replacing the
     literal `"skill(effect)"` prefixes with `what`. Call it from `skill()` with `what = "skill(effect)"`. Skill load
     messages must not change by a single character; the existing skill tests prove it. Give the helper a fourth
     parameter, `subject: String`, for the noun in the item-target message: `skill()` passes `"an item skill's effect"`
     (today's text) and `item()` passes `"an item use_effect"`.
  2. In `item(...)`: `val useEffect = d["use_effect"]?.let { effectValue(it, d.what("use_effect")) }`; `useEffect?.let {
     effectRefs(it, ctx, thread) }`; `val useTarget = d.choice("use_target")?.let { TargetKind.valueOf(it.uppercase())
     }`; `val useLag = d.int("use_lag") ?: 0`.
  3. Load checks: `useTarget != null && useEffect == null` → `"item(use_target): needs use_effect"`; `useLag < 0` →
     `"item(use_lag): can't be negative"`; `useEffect != null` → `checkEffectTarget(useEffect, useTarget ?:
     TargetKind.SELF, "item(use_effect)", "an item use_effect")`.
  4. Pass `useEffect`, `useTarget ?: TargetKind.SELF` and `useLag` to `ItemProto`.
  5. `effectValue` already calls `need(...)` for `knockback`/`pull`, so a `use_effect = knockback(1)` in a world without
     `track` fails with the feature message.
  6. CHANGELOG bullets for the three arguments. Regenerate the API files (guardrail 4).

### Test & Documentation Requirements

- `LoaderTest.kt`: `use_target` without `use_effect`; negative `use_lag`; `use_target = "item"` with `heal(...)` fails
  naming `heal`; `use_target = "self"` with `apply_affect(..., applies = "worn")` fails; a valid `use_effect =
  sequence(heal(...), apply_affect(...))` loads.
- `ChargesScriptTest.kt`: a `charges = 1, use_effect = heal(dice = "1d1+4", vital = "hp")` potion with no hook heals 5,
  prints "You use a potion.", and is gone; a `use_target = "enemy"` wand with `damage(...)` damages a named mob; a
  `use_lag = 2` item sets the actor's wait state (read it the way other script tests read lag).
- `FeatureScriptTest.kt`: in a world with `features = []` (kernel only), a `use_effect = heal(...)` item loads and heals
  through `use`.
- Existing skill tests must pass unchanged.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `ApiDiffTest`.
- [] `./gradlew :script:test` passes, including every existing `skill()` load-error test.
- [] The kernel-only `FeatureScriptTest` case passes, matching the updated §3.10 "Without `skills`" bullet.

---

## Phase 6: Charges on the plugin facade

### Objective & Scope

- **In Scope:** `PluginItem.charges`, `PluginItem.maxCharges`, `Facade.setCharges`, their host implementations, tests
  and docs.
- **Out of Scope:** plugin effects or hooks for `use`.

### Context Working Set

- **Read / Reference Only:** `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt` `transform`/`addTag` (around
  line 452–465) and the host `PluginItem` implementation (around line 266, `override val variants`).
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`
  - `plugin-api/src/test/kotlin/gups/pluginapi/FacadeTest.kt`, and `ApiSurfaceTest.kt` if it pins the surface
  - `docs/PLUGINS.md` (section "Item affects, tags and transform"), `docs/SPEC.md` §4.15, `docs/CHANGELOG.md`
- **Do Not Touch:** `core/`, `script/`, `samples/`.

### Specification & Decision Record Changes

- §4.15: where the item facade verbs are listed (`addTag`, `removeTag`, `transform`), add `setCharges`, and add
  `charges`/`maxCharges` to the item view fields if they are listed there. Same rules as `ctx.set_charges`.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // interface PluginItem, after variants:
  /** Uses left, or null when the item has unlimited uses. */
  val charges: Int?
  /** The prototype's charges, or null when unlimited. */
  val maxCharges: Int?

  // interface Facade, after transform:
  /** Sets [item]'s remaining uses, from 0 to its prototype's charges; 0 doesn't run its empty action. */
  fun setCharges(item: PluginItem, charges: Int)
  ```
- **Algorithmic Steps & Invariants:**
  1. Host `PluginItem`: `override val charges get() = item.charges`, `override val maxCharges get() =
     item.proto.charges`, following how `variants` reads the live item.
  2. Host `setCharges`: `world.setCharges(itemOf(item, "set_charges(item)"), charges)`. Its `require`s give
     `IllegalArgumentException`, which is how the other facade verbs report misuse.
  3. `PluginApi.VERSION` stays 1.

### Test & Documentation Requirements

- `FacadeTest.kt`: reading `charges`/`maxCharges` on a charged and an unlimited item; `setCharges` within range works;
  out of range, unlimited and stackable throw `IllegalArgumentException`.
- `docs/PLUGINS.md`: one bullet under "Item affects, tags and transform" with a two-line example (`if (item.charges ==
  0) call.world.setCharges(item, item.maxCharges!!)`), kept at list-safe indentation (see `AGENTS.md`).
- CHANGELOG bullet.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `./gradlew :plugin-api:test` passes.

---

## Phase 7: Template, tutorials and finalization

### Objective & Scope

- **In Scope:** the template draught, tutorials 03 and 06, the §7.4 row, the `DESIGN_NEEDED` entry.
- **Out of Scope:** new template rooms or items beyond the draught.

### Context Working Set

- **Read / Reference Only:** `template-world/zones/tutorial/rooms.star` (line ~119 gives the draught).
- **Files to Modify / Create:**
  - `template-world/zones/tutorial/items.star`
  - `template-world/zones/tutorial/tutorial_test.star`
  - `template-world/docs/tutorials/03_items_and_equipment.md`
  - `template-world/docs/tutorials/06_hooks_and_interactivity.md`
  - `docs/SPEC.md` (§7.4), `docs/DESIGN_NEEDED.md`
- **Do Not Touch:** engine code.

### Specification & Decision Record Changes

- §7.4: add a row after the last existing one:

  > | **M20** | Consumables and charges: per-instance `charges` with `empty` (destroy, keep or a variant) and
  > `set_charges`; declarative `use_effect`, `use_target` and `use_lag`; effect primitives move to the kernel; `use`
  > reaches ground items; plugin facade charges (§3.3, §3.10, §4.2, §4.3, §4.5, §4.9, §4.15). |

  Match the table's existing column formatting; `markdownFormat` realigns it.

### Implementation Blueprint

1. `items.star`: replace `_drink` and the `healing_draught` hook with:

   ```python
   item("healing_draught",
    name = "a healing draught",
    keywords = ["draught", "potion"],
    kind = "consumable",     # free-form: the engine gives kind no meaning
    charges = 1,             # used up after one drink; empty = "destroy" is the default
    use_effect = heal(dice = "2d4+6", vital = "hp"),
   )
   ```

   Keep the comment style of the file. If a comment elsewhere in the file says the engine never uses items up, update
   it.

2. `tutorial_test.star`, `test_a_draught_heals_and_is_used_up`: the heal is now 8–14, so assert `t.contains(p.do("use
   draught"), "You use a healing draught.")`, `t.true(p.hp >= 23 and p.hp <= 29)`, and keep `t.false(p.has_item(...))`.

3. Tutorial 03: rewrite the healing-draught section to the declarative version. Explain `charges`, `empty` (with a short
   wand example: `charges = 3, empty = "keep"`, and an `empty = "spent"` variant example), `use_effect`, `use_target`
   (the five words in one short list) and `use_lag`. Remove "or `drink <item>`" from the `on_use` line (there is no
   `drink` command).

4. Tutorial 06: add a short example of a hook-only lever on the ground: an item with no `charges` whose `on_use` calls
   `ctx.open_door(...)` on a room exit, and mention that `use` finds ground items within reach. Keep the existing
   `before_use` interceptor row. Keep code blocks out of nested lists or within the 4-space limit (`AGENTS.md`).

5. `DESIGN_NEEDED.md`: change the heading `### Consumables and charges` to `### Consumables and charges (COMPLETED —
   M20)`, matching the other completed entries. Do not delete it.

6. Run `./gradlew markdownFormat` and check code blocks kept their indentation.

### Test & Documentation Requirements

- The template test above. Run the template world's tests the way CI does (`./gradlew check` includes them; find the
  task with `grep -rn "template-world" build.gradle.kts */build.gradle.kts` if a narrower command is wanted).

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including the template world's Starlark tests and `markdownCheck`.
- [] `grep -n "COMPLETED — M20" docs/DESIGN_NEEDED.md` shows the entry.
- [] `grep -n "M20" docs/SPEC.md` shows the §7.4 row.
- [] End-to-end: in a `--dev` server or a harness test, `use draught` heals and removes it, and a wand with `charges =
  2, empty = "keep"` is refused on the third use.
