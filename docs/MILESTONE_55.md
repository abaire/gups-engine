# Milestone 55: A Hook When an Item Is Destroyed

**Prerequisites:** [M54](MILESTONE_54.md) must be finished first (it is: `core/.../world/Despawn.kt` exists). Where line
numbers below are wrong, find the symbol by name.

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** [DESIGN_NEEDED.md](DESIGN_NEEDED.md): `## Items` → `### A hook when an item is destroyed`.

- **Design Summary:**

  - **New kernel hook `on_destroy`.** It fires on the item, then on the room the item is in, then globally (script
    `global_hook` and a new plugin `onItemEvent` listener). Mobs and other items in the room do **not** get it.
  - **What fires it:** a removal that ends the item's existence:
    - a whole-item `destroy`, from `ctx.destroy` or the facade `destroy`;
    - the last of an item with `empty="destroy"`;
    - decay, including a decaying corpse;
    - the contents of an item destroyed in any of these ways.
  - **What doesn't fire it:**
    - **A partial stack removal** (`destroy(item, count)` below the count, or one use spent off a stackable stack): the
      item survives.
    - **A merge** (`merge_stack`, and the merges `get`/`drop`/`put` do): the absorbed item's quantity lives on.
    - **`transform`:** it keeps the item's id; §4.5 already says no hooks fire.
    - **A reload** that deletes an item or its room: it runs mid-reload while prototypes and hooks are swapped, as M54
      decided for mobs.
    - **A despawned mob's belongings:** despawn makes the mob and its gear stop existing, and its `on_despawn` hook can
      walk the belongings if a world cares.
    - **Death,** which moves the items into the corpse, and **quit**, which saves them.
  - **Fired before removal.** The hooks see the item where it is, with what it holds.
  - **Container-first walk.** The engine fires the destroyed item's hooks (item, room, global), then reads that item's
    contents *as they are now* and visits each in ascending id order, recursively, firing the same three hooks on each.
    A container's hook may move contents out to spare them; they get no hook.
  - **Contents may escape.** An item inside the destroyed item that leaves the tree before its turn, or during its own
    hooks, survives, and its remaining hooks (room, global) are skipped. The destroyed item itself (the *root*) can't
    escape: moving it during its hooks is allowed, but it is still removed.
  - **Guards while a destroy runs:**
    - Putting or spawning anything into an item whose `on_destroy` has already fired, while it is still in the tree, is
      an error: `"<name> is being destroyed"`.
    - Destroying (wholly or partly) an item that is still inside a running destroy's tree, the root included, does
      nothing.
    - Once every hook has run, the root and everything still inside it are removed.
    - An item that escaped and is destroyed later is a new destroy, with its own hooks.
  - **Ctx fields:**
    - `ctx.self`: the hook's owner. That is the item for its own hook and the global hook, and the room for the room's
      hook.
    - `ctx.item`: the item.
    - `ctx.actor`: whoever holds the item at that moment, following containers, or `None` on the ground.
    - `ctx.event`: the cause: `"destroy"` (the verb), `"decay"` or `"empty"`. Contents get the cause of the root.
    - `ctx.room`, `ctx.pulse` and `ctx.rng` are set as for every hook.
    - No veto; the return value is ignored. `destroy` gains no new parameter.
  - **Plugins:** a new registration, `onItemEvent(name, doc, fn: (PluginItemEventCall) -> Unit)`, which accepts only
    `"on_destroy"`. `onEvent` and `PluginEventCall` are unchanged.
  - **Template-world sample:**
    - A generic `salvage` lib command.
    - A `volatile_flask` that explodes when salvaged.
    - A `satchel` with a `no_salvage` brass key that clatters free when the satchel is destroyed.
    - The arena raising a `rotten_ghost` when a corpse rots there.
    - A tutorial test file and a tutorial 06 section.
- **Specification Impact:**
  - §4.5 `destroy` / `merge_stack` bullets.
  - §4.4: the hook table and a new `on_destroy` paragraph.
  - §4.9: the `use` command's `empty` sentence (~2329) and the **Global hooks** paragraph.
  - §4.15: the **Event listeners** bullet, a new **Item event listeners** bullet, and the seams list.
  - §5.4: the corpse decay bullet (~3971).
  - §5.x dormancy wake list ("Items that decayed go", ~4305).
  - The feature table's kernel row (~816).
- **Decision Record Updates:** None.

### Conventions for every phase

1. Write the tests first, run them and watch them fail, then implement.
2. Comments explain *why*, never cite spec sections or decision IDs. All Kotlin warnings are errors.
3. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation.
4. In every phase, regenerate the API files before the phase's final `./gradlew check` (an empty diff is fine):
   - `./gradlew :cli:test -Dgups.updateGolden=true` rewrites `cli/src/test/resources/api.golden.json` and
     `cli/src/test/resources/docs-golden/`.
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` rewrites
     `docs/api/api-1.json`.
   - Read the diff: only what the phase names may change.
5. CHANGELOG: Phase 1 adds `### A hook when an item is destroyed` at the top of `## api_version 1 (unreleased)` in
   `docs/CHANGELOG.md`, above `### Removing a mob without killing it`. Each later phase appends its bullets. Don't bump
   `api_version`.
6. Never read or touch `local-world/`. Scratch files go in `local-world-test/`, which you delete when done.
7. Messages given in quotes below are exact: tests assert them.
8. After changing code, run CodeScene `code_health_review` on the changed files and fix any drop.

---

## Phase 1: The destroy walk and the item and room `on_destroy` hooks

### Objective & Scope

- **In Scope:**
  - `HookName.ON_DESTROY` and its catalog entry (attachable to items and rooms).
  - A silent internal removal, `World.removeItem`, for merges and reloads.
  - A firing `World.destroy` that walks the tree container-first and fires the item's and the room's hooks.
  - The guards.
  - The `"decay"` and `"empty"` causes.
  - Spec text for all of the above.
- **Out of Scope:** *Do not implement these; later phases do.* The global `on_destroy` (script `global_hook`), the
  plugin `onItemEvent` listener, and every template-world change. Don't touch despawn (`Despawn.kt`, `removeActor`):
  despawned belongings stay silent.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Despawn.kt`: the re-entry guard and dispatch pattern to mirror.
  - `core/.../world/Hooks.kt`: `HookName` (`ON_DESPAWN` ~110 for KDoc style), `HookContext` (~216: `item`, `actor`,
    `room`, `event`), `dispatch` (~420), `invokeOn`.
  - `core/.../world/Bubble.kt`: `roomOfEntity` (~23) and the wake decay loop (~250).
  - `core/.../world/World.kt`: `dying` / `despawning` (~332), `decayPulse` (~1062).
  - `core/.../world/Persistence.kt`: `markRemoved` (~157).
  - `script/src/test/kotlin/gups/script/DespawnHookTest.kt`: the fixture pattern for script hook tests.
  - `core/src/test/kotlin/gups/core/world/ChargesTest.kt`: the fixture for charges.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Items.kt` (`destroy` → `removeItem`, `stack`, `spendCharge`, `decay`,
    `checkTarget`)
  - create `core/src/main/kotlin/gups/core/world/Destroy.kt`
  - `core/src/main/kotlin/gups/core/world/World.kt` (two new sets)
  - `core/src/main/kotlin/gups/core/world/Hooks.kt` (enum entry)
  - `core/src/main/kotlin/gups/core/world/Reconcile.kt` (two `destroy` calls → `removeItem`)
  - `script/src/main/kotlin/gups/script/HookCatalog.kt`
  - `script/src/main/kotlin/gups/script/CtxItems.kt` (the `destroy` doc string only)
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt` (the `destroy` KDoc only)
  - create `core/src/test/kotlin/gups/core/world/DestroyTest.kt`
  - create `script/src/test/kotlin/gups/script/DestroyHookTest.kt`
  - `script/src/test/kotlin/gups/script/HookCtxFieldsTest.kt` if it enumerates catalog hooks
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `Despawn.kt`, `Death.kt`, `removeActor`, `ScriptHost.invokeGlobal`, `Declarations.kt`,
  `plugin-api/.../host/`, `template-world/`.

### Specification & Decision Record Changes

Approved wording. Apply exactly, then run `markdownFormat`.

- §4.4 hook table: insert a row directly after the `on_despawn` row: ``| `on_destroy` | — | Item, Room | `item`, `actor`
  (the holder), `event` (`"destroy"`, `"decay"` or `"empty"`) |``. (Phase 2 adds "global (§4.9)" to its attach column.)

- §4.4: add a new paragraph directly after the `on_despawn` paragraph:

  > **`on_destroy`** fires when an item stops existing: a whole-item `destroy` (§4.5), the last of an item whose `empty`
  > is `"destroy"` (§4.9), or decay. `ctx.item` is the item, `ctx.actor` whoever holds it, following containers (`None`
  > on the ground), and `ctx.event` the cause: `"destroy"`, `"empty"` or `"decay"`. It fires on the item, then on the
  > room it is in; observers in the room don't get it. It runs before anything is removed, so the item is still where it
  > was, holding what it held. A destroyed item's contents go with it and get the hook too, container first: once an
  > item's hooks have run, the items inside it *then* are visited in ascending id order, each with the cause of the item
  > whose destruction began it. An item inside that is moved out of the destroyed item before its turn, or by its own
  > hooks, survives, and its remaining hooks are skipped. The destroyed item itself is removed wherever it ends up.
  > While the hooks run, putting or spawning anything into an item whose hooks have already run is an error, and
  > destroying an item still inside the destroyed one does nothing. Removing part of a stack, merging stacks
  > (`merge_stack`, §4.5), `transform`, a reload (§4.11), a mob's death (its belongings go to the corpse) and a despawn
  > (its belongings go with the mob) fire nothing. It can't be vetoed and its return value is ignored.

- §4.5 `destroy` bullet: replace "`destroy(item, count=None)` removes the item and everything inside it, or, when
  `count` is less than the stack's count, removes that many from the stack." with "`destroy(item, count=None)` removes
  the item and everything inside it, firing `on_destroy` (§4.4) with `"destroy"` on each before any of them goes, or,
  when `count` is less than the stack's count, removes that many from the stack and fires nothing."
- §4.5 `merge_stack` bullet: after "…(the item itself if nothing matched)." insert "The item absorbed into the stack
  fires no `on_destroy`."
- §4.9 `use` command, the `empty` sentence (~2329, "…item's `empty` action: destroy it, keep it at 0 charges, or
  `transform` it into the named variant."): change "destroy it" to "destroy it (`on_destroy` with `"empty"`, §4.4; for a
  stack, only the last one fires it)".
- §5.4 corpse decay bullet (~3971): replace "Decay drops a container's contents where it lay." with "Decay drops a
  container's contents where it lay, then the container fires `on_destroy` with `"decay"` (§4.4); the dropped contents
  don't. A container that decays while carried or inside another takes its contents with it, and they get the hook too."
- Dormancy wake list item 6 (~4305): change "Items that decayed go." to "Items that decayed go, firing `on_destroy`
  (§4.4)."
- Feature table kernel row (~816): after `` mob removal (`despawn`, `on_despawn`), `` insert `` item destruction
  (`on_destroy`), ``.

### Implementation Blueprint

#### Signatures & Types

```kotlin
// Hooks.kt, HookName, directly after ON_DESPAWN
/**
 * Invoked when an item stops existing, on the item, then the room it is in, before anything is
 * removed. A destroyed container's contents get it too, container first.
 */
ON_DESTROY("on_destroy"),

// World.kt, next to `despawning`
/** Roots of the destroys whose `on_destroy` hooks are running right now. */
internal val destroyRoots = HashSet<EntityId>()

/** Items whose `on_destroy` hooks have run in a destroy that hasn't finished. */
internal val destroyNotified = HashSet<EntityId>()

// Destroy.kt
/** Why an item is destroyed: `ctx.event` of its `on_destroy`. */
enum class DestroyCause(val word: String) {
  VERB("destroy"),
  DECAY("decay"),
  EMPTY("empty"),
}

/** True while [item] is the root of, or still inside the root of, a destroy whose hooks run. */
fun World.isBeingDestroyed(item: Item): Boolean

/**
 * The `destroy` verb: removes [item] and everything inside it, firing `on_destroy` on each first,
 * or removes only [count] of a stack, firing nothing. Does nothing to an item already inside a
 * running destroy.
 */
fun World.destroy(item: Item, count: Int = item.count, cause: DestroyCause = DestroyCause.VERB)

// Items.kt: the old whole-item body of `destroy`, renamed
/** Removes [item] and everything inside it at once, firing no hooks: merges and reloads. */
internal fun World.removeItem(item: Item)
```

#### Algorithmic Steps & Invariants

1. **`Items.kt`:**
   1. Rename the whole-item branch of today's `World.destroy` into `internal fun World.removeItem(item: Item)`. It keeps
      the `require(item.id in entities)`, the player `ItemEvent(… "destroy")` audit row, `unindex`, `markRemoved`,
      `forgetItemTree` and `refreshHoldings`, all unchanged.
   2. Move the partial-stack branch into `Destroy.kt`'s `destroy` (below), unchanged ("reduce" audit row included).
   3. Delete the old `destroy` from `Items.kt`.
   4. `stack()`: replace `destroy(item)` with `removeItem(item)`.
   5. `spendCharge`: `EmptyAction.Destroy -> destroy(item, 1, DestroyCause.EMPTY)`. A stack with a count above 1 takes
      the partial path and fires nothing, which is intended.
   6. `becomeVariant`: leave `destroy(item, 1)` as it is (a partial removal of a stack with a count above 1, so it fires
      nothing), but return at the top if `isBeingDestroyed(item)`. Otherwise the new `destroy` early return would skip
      the removal while `spawnItem` still made the variant part.
   7. `decay`: replace `destroy(item)` with `destroy(item, cause = DestroyCause.DECAY)`. The drop of ground contents
      before it stays first.
   8. `checkTarget`, `is ItemLocation.Contained` branch: after the cycle check, add `require(!(c0.id in destroyNotified
      && isBeingDestroyed(c0))) { "${c0.name} is being destroyed" }`, where `c0` is the target container (capture it
      before the `while` loop reuses `c`). This covers `spawnItem`, `moveWhole` and `moveSplit`, all of which call
      `checkTarget`.
2. **`Reconcile.kt`:** the two `destroy(...)` calls (~86 in `retireRemovedRooms`, ~165 in `reconcileItems`) become
   `removeItem(...)`. Nothing else changes.
3. **`Destroy.kt`, `isBeingDestroyed(item)`:** walk from `item` up its `ItemLocation.Contained` chain
   (`item(loc.container)`), including `item` itself. Return true if any of them has an id in `destroyRoots`.
4. **`Destroy.kt`, `destroy(item, count, cause)`:**
   1. `require(item.id in entities) { "$item is not in the world" }` and the existing count `require` (message
      unchanged: `"destroy: count must be between 1 and ${item.count}, got $count"`).
   2. `if (isBeingDestroyed(item)) return`. This covers both partial and whole removals.
   3. If `count < item.count`, run the partial branch moved from `Items.kt` and return.
   4. `destroyRoots += item.id`, `val notified = HashSet<EntityId>()`.
   5. `try { visit(item, item, cause, notified); if (item.id in entities) removeItem(item) } finally { destroyRoots -=
      item.id; destroyNotified -= notified }`.
5. **`Destroy.kt`, `private fun World.visit(root: Item, x: Item, cause: DestroyCause, notified:
   MutableSet<EntityId>)`:**
   1. Return if `x.id !in entities`, if `x.id in notified` (it re-entered the tree after its turn: it is removed with
      the tree without a second hook), or if `!inTree(root, x)`.
   2. `notified += x.id`; `destroyNotified += x.id`.
   3. `fireDestroyHooks(root, x, cause)` (below).
   4. `for (inner in x.contentsIndex.values.sortedBy { it.id }.toList()) visit(root, inner, cause, notified)`. This
      reads the contents **after** `x`'s hooks ran.
6. **`inTree(root, x)`:** `x === root`, or `x`'s `Contained` chain reaches `root`.
7. **`fireDestroyHooks(root, x, cause)`:**
   1. `val room = roomOfEntity(x)`.
   2. `val ctx = HookContext(item = x, actor = custodian(x), room = room, event = cause.word)`.
   3. `dispatch(HookName.ON_DESTROY, x, null, ctx)`. Passing `room = null` keeps observers and other items out, as in
      `Despawn.kt`.
   4. If `x !== root && !inTree(root, x)` (it escaped), return.
   5. `room?.let { dispatch(HookName.ON_DESTROY, it, null, ctx) }`.
   6. Leave a clearly marked spot after the room dispatch: Phase 2 adds the global dispatch there, behind the same
      escape check.
8. The root's own hooks may move the root. That's allowed: `removeItem` unindexes from wherever it is. A hook that
   removes the root's holder from the world (a despawn, say) can make the root vanish first; step 4.5's `in entities`
   check covers that.
9. **`HookCatalog.kt`:** add a `HookSpec` after `ON_DESPAWN`:
   - `signature = "fn(ctx)"`, `attachTo = listOf(I, R)`
   - `ctxFields = listOf("self", "item", "actor", "event", "room", "pulse", "rng")`
   - `doc = "Fires when an item stops existing (destroy, decay or a last charge), before it goes; container first."`
   - Phase 2 extends the doc.
10. Update the `ctx.destroy` `@StarlarkMethod` doc (`CtxItems.kt` ~200): "Removes an item and everything inside it,
    firing on_destroy on each first, or count of a stack, firing nothing."
11. Update the facade `destroy` KDoc (`plugin-api/.../Facade.kt` ~213) to the same meaning.

### Test & Documentation Requirements

- **`DestroyTest.kt`** (core, `Fixture`, with a recording `ScriptHost` that logs `(owner, hook, ctx.item, ctx.event,
  ctx.actor)` for `ON_DESTROY`, as `Fixture.kt`'s script host double allows):
  - A whole `destroy` of a carried item records the item, then the room, with event `"destroy"` and actor the holder.
    Afterwards the item is gone.
  - An item on the ground: actor is null.
  - A partial `destroy(stack, 2)` of a stack of 5 records nothing and leaves 3.
  - `stack()` merging two items records nothing.
  - A bag holding A and B (A's id is lower): the order is bag, room, A, room, B, room. All three are gone.
  - Nested: chest ⊃ bag ⊃ key. The order is chest, bag, key.
  - Escapes:
    - A hook on the bag (fired first) moves A to the room. A gets no hook and survives on the floor, while B is
      destroyed.
    - A's own hook moves A to the room. The room hook for A is skipped and A survives.
  - Root:
    - The root's own hook moves the root to the floor. It is still removed.
    - A hook calling `destroy(root)` or `destroy(B)` while the walk runs is a no-op, and each item is recorded once.
  - Refusals:
    - A bag-hook `moveItem(other, Contained(bag))` throws `IllegalArgumentException("<bag name> is being destroyed")`.
    - `spawnItem(label, Contained(bag))` from the bag's hook throws the same.
    - Spawning into B before B's turn (from the bag's hook) succeeds, and that item is visited and destroyed after B.
  - Decay:
    - A decaying ground container drops its contents and records only itself, with `"decay"`.
    - A carried decaying container records itself and its contents with `"decay"`.
    - Decay on wake in a dormant room records `"decay"` (use the bubble fixture from the existing wake tests).
  - Charges:
    - A non-stackable item with `charges = 1` and `empty = destroy`, after `spendCharge`, records `"empty"`.
    - A stack of 3 with `charges = 1` records nothing until its last.
  - Despawn and reload record nothing:
    - `despawn(mob)` with a carried item.
    - A reload that drops an item prototype (pattern in `ReloadLockedDoorTest.kt` or an existing reconcile test).
- **`DestroyHookTest.kt`** (script, fixture pattern from `DespawnHookTest`):
  - An item `on_destroy` and a room `on_destroy` both run for `ctx.destroy(item)`, item first, with `ctx.self` the
    owner, `ctx.item` the item, `ctx.event == "destroy"`, `ctx.actor` the holder.
  - `on_destroy` on a mob prototype fails to load (catalog attach check), with the catalog's error message.
  - An item hook calling `ctx.move_item(ctx.self, ctx.room)` from inside a destroyed satchel saves the item: it is on
    the floor afterwards.
- `HookCtxFieldsTest.kt`: if it enumerates catalog hooks, add `on_destroy` with the fields above.
- `CHANGELOG.md`: create the subsection. Bullets:
  - "New hook `on_destroy` fires on an item that stops existing (a whole `destroy`, decay, or a last charge), then its
    room, before anything is removed; a destroyed container's contents get it too, container first, and may escape by
    moving out."
  - "Merges, partial stack removals, `transform`, reloads, death and despawn fire no `on_destroy`."

### Exit Conditions

- [] `./gradlew :core:test :script:test` passes.
- [] `./gradlew check` passes outside the sandbox; API files regenerated (the diff adds `on_destroy` to the hook catalog
  and the changed `destroy` docs only).
- [] `grep -n "destroy(" core/src/main/kotlin/gups/core/world/Reconcile.kt` finds no call to `destroy(`, only
  `removeItem(`.

---

## Phase 2: The global script `on_destroy`

### Objective & Scope

- **In Scope:** `global_hook("on_destroy", fn)` in lib and rules packages, with `ctx.self` the item; widening the script
  host's global-hook subject from `Actor` to `Entity`; spec text.
- **Out of Scope:** *Do not implement these; later phases do.* The plugin `onItemEvent` listener and template-world
  content. Don't touch `EventListener`, `pluginEvents` or `PluginEventCall`.

### Context Working Set

- **Read / Reference Only:**
  - `core/.../world/Hooks.kt`: `dispatchGlobal` (~471).
  - `script/src/main/kotlin/gups/script/Host.kt`: `invokeGlobal` (~361); `Ctx` takes an `Entity` owner already.
  - `script/src/test/kotlin/gups/script/GlobalHookTest.kt`: the lib-package fixture.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Hooks.kt` (`ScriptHost.invokeGlobal` signature; new `dispatchGlobalItem`)
  - `core/src/main/kotlin/gups/core/world/Destroy.kt` (call it)
  - `script/src/main/kotlin/gups/script/Host.kt`
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`GLOBAL_HOOK_NAMES`)
  - `script/src/main/kotlin/gups/script/HookCatalog.kt` (the `ON_DESTROY` doc)
  - Test doubles overriding `invokeGlobal`: `core/src/test/kotlin/gups/core/world/Fixture.kt` (~231),
    `core/src/test/kotlin/gups/core/world/TraitEventsTest.kt` (~264),
    `script/src/test/kotlin/gups/script/HookCtxFieldsTest.kt` (~43). Find all with `grep -rn "override fun
    invokeGlobal"`.
  - `script/src/test/kotlin/gups/script/DestroyHookTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `plugin-api/`, `template-world/`, the actor `dispatchGlobal` callers.

### Specification & Decision Record Changes

- §4.4 hook table `on_destroy` row: attach column becomes `Item, Room, global (§4.9)`.
- §4.4 `on_destroy` paragraph:
  - Change "It fires on the item, then on the room it is in; observers in the room don't get it." to "It fires on the
    item, then on the room it is in, then globally; observers in the room don't get it."
  - Change "and its remaining hooks are skipped" to "and its remaining hooks, the global one included, are skipped".
- §4.9 **Global hooks** paragraph:
  - Change the first sentence's "fires for every actor, wherever it is" to "fires for every actor (or, for `on_destroy`,
    every item), wherever it is".
  - Insert `` `on_destroy` `` after `` `on_despawn` `` in the list of hooks that can be global.
  - After the sentence ending "…`ctx.self` and `ctx.actor` are the mob." add: "A global `on_destroy` fires once for
    every item destroyed, after its own and its room's `on_destroy` hooks, with the same ctx fields; `ctx.self` and
    `ctx.item` are the item."
  - In "`on_encumbrance_change`, `on_group_change`, `on_owner_change`, `on_death` and `on_despawn` belong to the
    kernel", add `on_destroy`: "…`on_death`, `on_despawn` and `on_destroy` belong to the kernel…".

### Implementation Blueprint

```kotlin
// Hooks.kt, ScriptHost
fun invokeGlobal(world: World, hook: HookName, subject: Entity, ctx: HookContext) = Unit

// Hooks.kt, next to dispatchGlobal
/** Runs every global [hook] about [item], with [item] as `ctx.self`. */
internal fun World.dispatchGlobalItem(hook: HookName, ctx: HookContext, item: Item)
```

1. Widen `ScriptHost.invokeGlobal`'s `subject` to `Entity`.
   - `dispatchGlobal` still passes an `Actor`, which is fine.
   - Update `Host.kt`'s override. Its body already uses only `subject.id` and passes `subject` to `Ctx` as the owner.
   - Update every test double's signature. Where a double reads actor members off `subject`, cast with `as? Actor`.
2. `dispatchGlobalItem`: `withCascadeGuard(hook.scriptName) { try { scripts.invokeGlobal(this, hook, item, ctx) } catch
   (e: Exception) { diagnose("script", "${hook.scriptName} failed: ${e.message}", e) } }`, the same as the script half
   of `dispatchGlobal`. Phase 3 adds the plugin loop.
3. `Destroy.kt` `fireDestroyHooks`: after the room dispatch, `if (x !== root && !inTree(root, x)) return`, then
   `dispatchGlobalItem(HookName.ON_DESTROY, ctx, x)`.
4. `Declarations.kt` `GLOBAL_HOOK_NAMES`: insert `"on_destroy"` after `"on_despawn"`. Fix any test asserting the old
   full list message.
5. `HookCatalog.kt` `ON_DESTROY` doc: append "; also a global hook (lib and rules packages)".

### Test & Documentation Requirements

- `DestroyHookTest.kt`:
  - A lib package `global_hook("on_destroy", fn)` runs once per destroyed item, after the item's and the room's hooks
    (record order), with `ctx.self` and `ctx.item` the item and the same `event`/`actor`.
  - For a satchel holding a key whose own hook moves it out, the global hook runs for the satchel only.
  - A global hook that raises records a `script` diagnostic, and the item is still destroyed.
  - `global_hook("on_destroy", fn)` in a zone package fails as other global hooks do.
- CHANGELOG bullet: "`on_destroy` can be a global hook: `global_hook("on_destroy", fn)` runs for every destroyed item,
  with `ctx.self` the item."

### Exit Conditions

- [] `./gradlew :core:test :script:test` passes.
- [] `./gradlew check` passes outside the sandbox; API files regenerated.

---

## Phase 3: The plugin `onItemEvent` listener

### Objective & Scope

- **In Scope:** `PluginRegistry.onItemEvent`, `PluginItemEventCall`, the core seam (`ItemEventListener`,
  `World.pluginItemEvents`), host wiring, spec and `PLUGINS.md`.
- **Out of Scope:** *Do not implement these; a later phase does.* Template-world content and sample plugins. Don't
  change `onEvent`, `PluginEventCall` or `EVENT_NAMES`.

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`: `installEvents` (~328), `fieldsOf` (~93), `guard`,
    `facade`, `ItemFacade` and `ActorFacade` construction.
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginRegistryImpl.kt`: `onEvent` (~316), `events` (~143),
    `EVENT_NAMES` (~426).
  - `plugin-api/src/test/kotlin/gups/pluginapi/EventListenerTest.kt`: the test pattern.
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt` (registry method, data class)
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginRegistryImpl.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`
  - `core/src/main/kotlin/gups/core/world/Hooks.kt` (`ItemEventListener`, plugin loop in `dispatchGlobalItem`)
  - `core/src/main/kotlin/gups/core/world/World.kt` (`pluginItemEvents`)
  - create `plugin-api/src/test/kotlin/gups/pluginapi/ItemEventListenerTest.kt`
  - `plugin-api/src/test/kotlin/gups/pluginapi/ApiSurfaceTest.kt` if it pins the public surface
  - `docs/SPEC.md`, `docs/PLUGINS.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `script/`, `template-world/`, `samples/`.

### Specification & Decision Record Changes

- §4.15: add a bullet directly after **Event listeners**:

  > - **Item event listeners:** `onItemEvent(name, doc, fn)` listens to an engine event about an item; `on_destroy` is
  >   the only one, and any other name fails `register`. A listener runs each time the event's global hooks run, after
  >   them, in plugin-id then registration order, and can't veto or change the event. The call carries a `PluginWorld`,
  >   the event name, `item` (a `PluginItem`, still in the world) and `fields`: `actor`, the holder as a `PluginActor`
  >   (or null on the ground), and `event`, the cause (`"destroy"`, `"decay"` or `"empty"`). A listener runs only for an
  >   item whose global hooks run, so an item that escaped its container's destruction doesn't reach it. A throw records
  >   a `plugin` diagnostic, and the item is still destroyed.

- §4.15 seams list: after the `World.pluginEvents` bullet add: "- `World.pluginItemEvents`: plugin item event listeners
  by hook name, run by `dispatchGlobalItem` after the script global hooks."

### Implementation Blueprint

```kotlin
// PluginApi.kt, PluginRegistry, directly after onEvent
/**
 * Listens to an engine event about an item, after the scripts' global hooks for it; it can't veto
 * or change the event. Only on_destroy; any other name fails register.
 */
fun onItemEvent(name: String, doc: String, fn: (PluginItemEventCall) -> Unit) {}

// PluginApi.kt, after PluginEventCall
/** One run of a plugin's item event listener, after the scripts' global hooks. */
data class PluginItemEventCall(
  val world: PluginWorld,
  /** The event: on_destroy. */
  val event: String,
  /** The item the event is about, still in the world. */
  val item: PluginItem,
  /** The event's ctx fields as plain values: actor (the holder, or null) and event (the cause). */
  val fields: Map<String, Any?>,
)

// Hooks.kt, next to EventListener
/** A plugin listener for an item event: the item and its context. */
fun interface ItemEventListener {
  fun handle(world: World, item: Item, ctx: HookContext)
}

// World.kt, next to pluginEvents
val pluginItemEvents: MutableMap<HookName, MutableList<ItemEventListener>> = LinkedHashMap()
```

1. `PluginRegistryImpl`:
   - Add `val itemEvents = mutableListOf<Triple<String, String, (PluginItemEventCall) -> Unit>>()`.
   - Add `override fun onItemEvent`, which requires `name in ITEM_EVENT_NAMES` with the message `"onItemEvent(): '$name'
     is not an item event; expected one of ${ITEM_EVENT_NAMES.joinToString(", ")}"`.
   - Add `ITEM_EVENT_NAMES = listOf(HookName.ON_DESTROY).map { it.scriptName }` next to `EVENT_NAMES`.
2. `PluginHost`: add `installItemEvents(world)`, mirroring `installEvents`, and call it wherever `installEvents` is
   called.
   - It clears `world.pluginItemEvents`.
   - For each plugin and each `itemEvents` entry, it adds an `ItemEventListener` that, inside `guard(w, p.id,
     "onItemEvent '$name'")`, calls `fn(PluginItemEventCall(facade(w, p.id), name, ItemFacade(item, w), mapOf("actor" to
     ctx.actor?.let { ActorFacade(it, w) }, "event" to ctx.event)))`.
   - Use the `ItemFacade` constructor as other host code does; find it with `grep -n "ItemFacade(" PluginHost.kt
     Facade.kt`.
3. `dispatchGlobalItem` (Phase 2): after the script call, inside the same cascade guard, loop over
   `pluginItemEvents[hook].orEmpty()`, each in its own `try`, calling `diagnose("plugin", "${hook.scriptName} listener
   failed: ${e.message}", e)` on a throw, as `dispatchGlobal` does.
4. If plugin-api has a documentation or registration-introspection list of registry methods (search for `"onEvent"` in
   `plugin-api/src/main` and `cli/src/main`), add `onItemEvent` beside it.

### Test & Documentation Requirements

- `ItemEventListenerTest.kt`:
  - A plugin's `onItemEvent("on_destroy")` runs once per destroyed item, after a script `global_hook("on_destroy")`
    (record order).
  - `item.inWorld` is true inside the listener; `fields["actor"]` is the holder (or null on the ground) and
    `fields["event"]` is `"destroy"`, `"decay"` or `"empty"`.
  - A listener that throws records a `plugin` diagnostic, and the item is still gone.
  - `onItemEvent("on_death", …)` fails `register` with the message above.
  - `onEvent("on_destroy", …)` still fails `register` (not an actor event).
- `PLUGINS.md`: add `### Item event listeners` after `### Engine event listeners`. Say what §4.15 says, plus the example
  in "The `PLUGINS.md` example" below. Place the example's code fence at column 0, outside any list.
- CHANGELOG bullet: "Plugins can listen to `on_destroy` with the new `onItemEvent`, whose call carries the item."

#### The `PLUGINS.md` example

Adapt member names (`hasTag`, `name`, the logger) to what `PluginItem` and the registry actually offer.

```kotlin
r.onItemEvent("on_destroy", "Tracks unique artifacts.") { call ->
    if (call.item.hasTag("artifact")) log.info("artifact ${call.item.name} lost (${call.fields["event"]})")
}
```

### Exit Conditions

- [] `./gradlew :plugin-api:test` passes, including `CtxParityTest` and `ApiSurfaceTest`.
- [] `./gradlew check` passes outside the sandbox; API files regenerated (the diff adds `onItemEvent` and
  `PluginItemEventCall` only).

---

## Phase 4: Template-world salvage, flask, satchel and rotten ghost

### Objective & Scope

- **In Scope:**
  - `template-world/lib/salvage.star`, a global `salvage` command.
  - In the tutorial zone: a `volatile_flask` on the armory floor; a `satchel` holding a spare `brass_key` on the hall
    floor; `brass_key` gains `no_salvage` and an `on_destroy`; the arena's room `on_destroy` raises a `rotten_ghost`.
  - `zones/tutorial/destroy_test.star`.
  - A new tutorial 06 section.
  - The satchel is a floor item, not something the guide hands out. Doors need their key carried or worn
    (`Actor.hasKey`, `core/.../world/ItemSupport.kt` ~125), and a key inside a carried satchel doesn't count. So the
    guide keeps granting the `brass_key` directly, and the tutorial's first steps don't change.
- **Out of Scope:** *Do not implement these.* Engine code (Phases 1–3 are done), salvage yields or tables (salvage gives
  nothing back), sample plugins, and changes to the guide's `key` dialogue.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/lib/group.star` (`global_command` at file end) and `template-world/lib/shop.star` (`_sell_target`
    ~524, `_sell_command` ~559): the command and item-lookup pattern.
  - `template-world/zones/tutorial/rooms.star`: `_stock_armory` ~92, `_stock_tar_pit` ~58, the `hall` (~39) and `arena`
    (~150) rooms.
  - `template-world/zones/tutorial/mobs.star` (`familiar` ~179) and `items.star` (`brass_key` ~6).
  - `template-world/zones/tutorial/tutorial_test.star`: harness usage.
  - `template-world/MODULE.gups` (`damage_type("fire")`).
  - SPEC §4.5 item view fields (~1406: `location`, `contents()`, `has_tag`). `item.location.kind` is `"room"`,
    `"carried"`, `"equipped"` or `"contained"` (`script/.../Views.kt` `LocationView` ~910).
  - Room view `actors()` and `items()` (SPEC ~1399). Mob views have `label`.
  - `ctx.damage(target, amount, type, vital, source=None)` (SPEC verb table ~1425).
  - `ctx.spawn` returns the item view, which a second `ctx.spawn` accepts as a container target (`lib/shop.star` ~175,
    ~232).
  - Harness world handle (`harness/src/main/kotlin/gups/harness/Handles.kt`): `w.spawn(label, at)` (~385) and
    `w.damage(target, vital, amount, …)` (~463).
- **Files to Modify / Create:**
  - create `template-world/lib/salvage.star`
  - `template-world/lib/package.star` (comment)
  - `template-world/zones/tutorial/items.star`, `rooms.star`, `mobs.star`
  - create `template-world/zones/tutorial/destroy_test.star`
  - `template-world/docs/tutorials/06_hooks_and_interactivity.md`
  - `template-world/docs/tutorials/README.md` if it lists sections
  - `docs/CHANGELOG.md`
- **Do Not Touch:** `core/`, `script/`, `plugin-api/`, `samples/`, `docs/SPEC.md`.

### Specification & Decision Record Changes

- None.

### Implementation Blueprint

1. **`lib/salvage.star`:**
   - `global_command("salvage", _salvage_command, lane = "tick")`.
   - `_salvage_command(ctx)`:
     - With no args, `ctx.send(ctx.actor, "Salvage what?")`.
     - Find the first item in `ctx.actor.inventory()` whose `keywords` contain the argument, as `_sell_target` does.
       Worn items aren't in the inventory, so they're never salvaged.
     - None: `"You aren't carrying that."`.
     - `item.has_tag("no_salvage")`: `"You can't salvage <item name>."`.
     - Otherwise send `"You break down <item name>."` to the actor and `"<Actor> breaks down <item name>."` to the room
       (use the room-message verb the lib already uses for third-person lines; exclude the actor), then
       `ctx.destroy(item)`.
     - Read the item's name before destroying it.
     - Salvage never looks inside containers. Add a top comment saying so: the contents' own `on_destroy` decides what
       happens to them.
   - `lib/package.star` comment: add "salvage.star exports nothing to load: its `salvage` command registers in every
     world that includes lib." next to the group.star note.
2. **`items.star`:**
   - `brass_key`: add `tags = ["no_salvage"]` and `on_destroy = _key_destroyed`.
     - `_key_destroyed` acts only when `ctx.self.location.kind == "contained"`. When the key itself is the destroyed
       item, it can't escape, so the hook does nothing.
     - It then calls `ctx.move_item(ctx.self, ctx.room)` and sends the room "The brass key clatters free." (sight or
       `ctx.echo`, matching nearby examples).
     - A comment explains why: a lost key would lock the armory for good, and only items *inside* a destroyed item can
       escape.
   - `satchel`: "a traveler's satchel", keywords `["satchel"]`, an open `container(capacity = 5, max_weight = 50, state
     = "open")`, mirroring the container syntax in `shop.star`.
   - `volatile_flask`: "a volatile flask", keywords `["volatile", "flask"]`, `on_destroy = _flask_bursts`.
     - `_flask_bursts`: if `ctx.event == "destroy"` and `ctx.actor != None`, send the room "The volatile flask bursts in
       a gout of flame!" and `ctx.damage(ctx.actor, 8, DAMAGE.fire, "hp")`. Check the vital name against
       `template-world/MODULE.gups`.
     - Decay and a spent charge do nothing; a comment says that's what `ctx.event` is for.
3. **`rooms.star`:**
   - `_stock_armory` also keeps one `volatile_flask` on the floor (same "if none on floor, spawn" pattern).
   - Add `_stock_hall` (`on_load` and `on_reset` of `hall`): if no satchel is on the floor, `s = ctx.spawn(":satchel",
     ctx.self)` then `ctx.spawn(":brass_key", s)`.
   - `arena`: `on_destroy = _arena_destroy`.
     - It acts only when `ctx.item.label == "//engine:corpse"` and `ctx.event == "decay"`, and no `rotten_ghost` is
       already in the room (`[a for a in ctx.self.actors() if a.label.endswith(":rotten_ghost")]` is empty).
     - Then it sends a sight line, "A rotten ghost rises from the rotting corpse.", and `ctx.spawn(":rotten_ghost",
       ctx.self)`.
     - A comment explains that the corpse is the engine's own prototype, so a room hook (or a global hook) is the only
       way to react to it.
4. **`mobs.star`:** `rotten_ghost`: "a rotten ghost", keywords `["rotten", "ghost"]`, a short desc, `vitals = vitals(hp
   = 10)`, `behaviors = [wander()]`, no spawn rule.
5. Check `tutorial_test.star` and the other tutorial tests for counts of hall, armory or arena items or actors that the
   new stock changes, and update them.

### Test & Documentation Requirements

- **`destroy_test.star`:**
  - Salvaging the flask while carrying it shows the burst line, and the salvager's hp drops.
  - A flask destroyed by anything other than the verb doesn't hurt anyone. Test this through decay if a test-only item
    with `decay_pulses` is easy to add to a `*_testdata.star` file; otherwise skip it.
  - `salvage key` is refused with the `no_salvage` message.
  - In the hall, `get satchel` then `salvage satchel` shows "The brass key clatters free.". The key is then on the hall
    floor and the satchel is gone.
  - Corpse decay:
    - Spawn a goblin in the arena with `w.spawn(":goblin", at = ":arena")` and kill it with `w.damage(goblin, "hp",
      amount = …)` for more than its hp. Don't use the training dummy: `_dummy_hit` mends it.
    - `w.run(pulses = …)` past `CORPSE_DECAY_PULSES` (in `core/src/main/kotlin/gups/core/TimeConstants.kt`). A player in
      the arena sees the ghost line, and a `rotten_ghost` is in the arena.
    - A second goblin's corpse decaying doesn't add a second ghost.
- **Tutorial 06:** add `## 9. Reacting to Destruction: \`on_destroy\`` before `## Reference Links`, with the three
  examples.
  - **Explain:** what fires the hook and what doesn't, the cause in `ctx.event`, and the holder in `ctx.actor`.
  - **The walk:** container first, and contents escape by moving out.
  - **The ghost:** why it uses a room hook.
  - **Formatting:** keep code blocks out of lists, or within the list indentation limit in `AGENTS.md`.
- CHANGELOG bullet: "The template world gains a `salvage` command, and its tutorial zone shows `on_destroy` with an
  exploding flask, a key that escapes a salvaged satchel, and a ghost that rises from a rotting corpse."

### Exit Conditions

- [] `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups test --world template-world
  //zones/tutorial/...` passes.
- [] `./gradlew check` passes outside the sandbox (template-world tests run in it); API files regenerated (empty diff
  expected).

---

## Phase 5: Finalization & Backlog Update

- End-to-end check with `gups scenario` (see `AGENTS.md`):
  - Write `local-world-test/destroy_scenario.star` against `template-world`.
  - A player takes the hall satchel and salvages it, and the key lands on the floor. Unlock the armory with that key,
    take the flask, salvage it and take fire damage.
  - In the arena, spawn and kill a goblin, then run past corpse decay; the ghost rises.
  - Read the transcript for each line.
- In `docs/DESIGN_NEEDED.md`, mark the entry completed without deleting it: change the heading `### A hook when an item
  is destroyed` to exactly `### A hook when an item is destroyed (Done in Milestone 55)`.
- Re-read §4.4's `on_destroy` row and paragraph, §4.9's global hooks paragraph and §4.15's item listener bullet against
  the code. Hook targets, ctx fields, the cause words and the order must match.
- Confirm the CHANGELOG subsection `### A hook when an item is destroyed` has a bullet for every phase.
- Run `./gradlew check` and `./gradlew markdownCheck` outside the sandbox; both must pass.
- Run `pre_commit_code_health_safeguard`; it must pass.
- Delete `local-world-test/`.
