# Milestone 50: Affect Trait Events

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, heading `### More trait event callbacks` (under `## Plugins`).

- **Rules for every phase** (from `AGENTS.md`):

  - Test first: write the tests, run them and watch them fail, then write the code.
  - Do not cite spec sections or decision IDs in code comments. Comments explain why, not what.
  - Run `./gradlew check` outside the sandbox (`BypassSandbox: true`). All Kotlin warnings are errors.
  - After editing any Markdown file, run `./gradlew markdownFormat` (outside the sandbox), then check that code blocks
    inside lists kept their indentation.
  - After changing code, run the CodeScene `code_health_review` on the changed files and fix any drop in Code Health.
  - Never read or touch `local-world/`.
  - Engine and plugin identifiers say **skill**, never **spell**. This milestone adds no skill-named identifiers.
- **Design Summary:**
  - **Today.** `PluginTrait.on` maps a `PluginTraitEvent` to a handler run for each bearer of the trait, through
    `World.fireTraitEvent` (`core/src/main/kotlin/gups/core/world/Hooks.kt`, line ~499). There are seven events. None
    reports affects.

  - **New events.** Two events, for **actor** affects only. Item affects (on an item) and room affects never fire them;
    they have no bearer.

    |      Event      |         Fires from          |                              When                               |
    |-----------------|-----------------------------|-----------------------------------------------------------------|
    | `AFFECT_GAINED` | `World.applyAffect`         | an affect was put on the actor (new, or a stronger reapply)     |
    | `AFFECT_LOST`   | `World.endAffect` (private) | an affect came off: expiry, `remove_affect`, `dispel`, gear off |

  - **Fields.** Plain values, like the existing events:

    |     Field     | `AFFECT_GAINED` | `AFFECT_LOST` |                                     Core value                                      |
    |---------------|-----------------|---------------|-------------------------------------------------------------------------------------|
    | `name`        | ✓               | ✓             | `String`                                                                            |
    | `flags`       | ✓               | ✓             | `List<String>`, sorted                                                              |
    | `source`      | ✓               | ✓             | `Actor?`: the applier, null when none or no longer in the world                     |
    | `source_item` | ✓               | ✓             | `Item?`: the worn item the affect lasts for (`wornFrom`), null otherwise or if gone |
    | `power`       | ✓               | ✓             | `Int` (100 when nothing scaled it)                                                  |
    | `expires_at`  | ✓               | —             | `Long` absolute pulse, or null when it never runs out (`Long.MAX_VALUE`)            |
    | `replaced`    | ✓               | —             | `Boolean`                                                                           |
    | `reason`      | —               | ✓             | `"expired"` or `"removed"`, the same rule as `on_expire`                            |

    The plugin adapter turns `Actor` into `PluginActor`, `Item` into `PluginItem` and `Int` into `Long`. `expires_at` is
    already a `Long`.

  - **Reapplication.** Re-applying a name that already lasts at least as long changes nothing and fires nothing. A
    stronger reapplication (later expiry) replaces the old affect and fires **only** `AFFECT_GAINED`, with `replaced =
    true`. No `AFFECT_LOST` fires for the replaced affect, just as its `on_expire` doesn't.

  - **Gear.** `equip_affects` entries are real actor affects (named `worn:<item id>:<flag>`), so wearing and removing
    gear fires both events like any other affect, including when an inactive slot becomes active again or goes inactive.
    `source_item` is set only for untimed entries (`wornFrom != null`). Timed entries have `source_item = null`.

  - **Where in the code.**

    - `AFFECT_GAINED` fires last in `applyAffect`, after `clampVitals`, so a handler sees the final state (flags,
      maxima, fit).
    - `AFFECT_LOST` fires in `endAffect` after the expire message and the `on_expire` call, and **before** the
      `summoned` dismissal. Otherwise a summoned mob's handler would be skipped silently, because the dismissal removes
      the mob from the world.
    - `dispel` and `remove_affect` already call `endAffect` once per affect, so they fire one event per affect, in
      removal order.
  - **Silent paths (invariant).** These change an actor's affects and must fire nothing:
    - Restoring saved affects (`persist/.../Codec.kt` writes the list directly; nothing to change).
    - Rebuilding untimed equip affects on restore (`settleRestoredHoldings`): it must pass `fireEvents = false`.
    - The regen catch-up in `Bubble.kt` (`recover`, lines ~336 and ~346), which temporarily swaps the list. Do not touch
      it, and never move event firing onto list mutation.
    - Anything that happens to the actor while its own death is processed (`victim.id in World.dying`): a dead mob's
      gear moving into its corpse, the player respawn `affects.clear()`.
    - An actor leaving the world takes its affects with it, firing nothing.
  - **Who can be a bearer.** As for the other events: any actor carrying the trait, players and mobs, that the world is
    simulating and that is still in the world (the existing checks in `fireTraitEvent`).
  - **Re-entrancy.** Each handler call is already a cascade-guard entry. A handler that re-applies its affect on
    `AFFECT_LOST`, or applies another affect on `AFFECT_GAINED`, stops at `CASCADE_MAX_DEPTH` with a `cascade`
    diagnostic. Nested events fire first. For example, `applyAffect` → `refreshFit` may take a gear affect off before
    the outer `AFFECT_GAINED` fires. No new mechanism.
  - **Out of the whole milestone.** Starlark-declared traits or player-scoped world hooks (the second `DESIGN_NEEDED`
    question) are not designed here. They are closed by pointing to the existing `docs/ICEBOX.md` entry
    "Starlark-defined choices, traits, and restrictions". No global hook, no `onEvent` listener, no new script hook, and
    no change to item or room affects.
- **Specification Impact:**
  - §4.15 "Trait callbacks" bullet: two new events and their fields (Phase 2).
  - §4.19 "Effects of traits" → "Callbacks" bullet: replace the outdated "The one event is `skill_recognized`" lead-in
    (Phase 2).
  - `docs/PLUGINS.md` trait events list and `docs/CHANGELOG.md` (Phase 2).
- **Decision Record Updates:** None. `docs/DECISIONS.md` has no entries.

---

## Phase 1: Core `AFFECT_GAINED` and `AFFECT_LOST`

### Objective & Scope

- **In Scope:**
  - Add `AFFECT_GAINED` and `AFFECT_LOST` to the core `TraitEvent` enum.
  - Fire them from `applyAffect` and `endAffect` with the fields above.
  - Suppress them on the restore rebuild and for an actor whose death is being processed.
  - Core tests in a new test file.
- **Out of Scope:** *Do not implement these; Phase 2 does.* Any change under `plugin-api/`, `samples/`, `docs/SPEC.md`,
  `docs/PLUGINS.md` or `docs/CHANGELOG.md`. Plugins can't register the new events until Phase 2, so no spec text changes
  here. Also out of scope for the whole milestone: item and room affect events, global hooks, script hooks.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Hooks.kt`: `fireTraitEvent` (line ~499) and `AffectEndReason` (line ~199).
  - `core/src/main/kotlin/gups/core/world/Death.kt`: `processDeath` and the `dying` set it maintains; `dropBelongings`
    and `respawnPlayer`.
  - `core/src/main/kotlin/gups/core/world/World.kt`: `dying` (line ~332), `actor(id)` (line ~408), `item(id)` (line
    ~410).
  - `core/src/main/kotlin/gups/core/model/Entities.kt`: `class Affect` (line ~195): `source`, `expiresAt`, `flags`,
    `wornFrom`, `power`.
  - `core/src/main/kotlin/gups/core/world/Slots.kt`: `refreshFit` (line ~164) calls `applyEquipAffects` /
    `removeEquipAffects`; returns early while `refittingSlots`.
  - `core/src/main/kotlin/gups/core/world/Bubble.kt`: `wake` and `expireMissedAffects` (wake-time expiry goes through
    `expireAffect`, after `dormantSince` is cleared, so it fires normally).
  - `core/src/test/kotlin/gups/core/world/TraitEventsTest.kt`: the `Call` record, the `trait(...)` and `of(...)` helpers
    (lines 15-37), and the dormant-mob and cascade tests to mirror.
  - `core/src/test/kotlin/gups/core/world/Fixture.kt`: `Fixture`, `RecordingHost` (`refHandlers` runs an affect's
    `on_expire` by function name and records `"ref:<name>@<pulse>"` in `calls`).
  - `core/src/test/kotlin/gups/core/world/ItemCommandsTest.kt`: the `cap` and `helm` prototypes with `equipAffects`
    (lines ~66-82) and how a test wears an item; `settleRestoredHoldings` use (line ~503).
  - `core/src/test/kotlin/gups/core/world/GroupTest.kt`: `w.summon(owner, Effect.Summon(T.DUMMY, 1, null))` (line ~383).
  - `core/src/test/kotlin/gups/core/world/CombatTest.kt`: `applyEffect(Effect.Dispel(name = ...), p, p)` (line ~347).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Creation.kt`: the `TraitEvent` enum (line ~114).
  - `core/src/main/kotlin/gups/core/world/Affects.kt`: `applyAffect`, `endAffect`, `applyEquipAffects`, plus two private
    helpers.
  - `core/src/main/kotlin/gups/core/world/Restore.kt`: `settleRestoredHoldings` (line ~92).
  - Create `core/src/test/kotlin/gups/core/world/AffectTraitEventsTest.kt`.
- **Do Not Touch:** `Bubble.kt` (the regen catch-up must stay silent as is), `Hooks.kt` (`fireTraitEvent` keeps its
  signature and checks), `Death.kt`, `RoomAffects.kt`, the item-affect functions in `Affects.kt` (`applyItemAffect`,
  `removeItemAffect`, `endItemAffect`), `persist/`, `plugin-api/`, `samples/`, all docs.

### Specification & Decision Record Changes

- None in this phase.

### Implementation Blueprint

**Signatures & Types:**

```kotlin
// Creation.kt: append to enum class TraitEvent, after ROOM_ENTERED
  /**
   * An affect was put on the bearer: a new one, or a reapplication that lasts longer and replaced
   * the old one. Fields: `name`, `flags` (sorted List<String>), `source` (Actor or null),
   * `source_item` (the worn Item the affect lasts for, or null), `power` (Int), `expires_at` (Long,
   * or null when it never runs out) and `replaced` (Boolean).
   */
  AFFECT_GAINED,

  /**
   * An affect came off the bearer: it expired or was removed. Not fired for one a reapplication
   * replaced. Fields: `name`, `flags`, `source`, `source_item`, `power` and `reason` (`expired` or
   * `removed`).
   */
  AFFECT_LOST,
```

```kotlin
// Affects.kt: the changed public/internal signatures (new parameters last, defaulted)
fun World.applyAffect(
  target: Actor,
  spec: AffectSpec,
  hostile: Boolean? = null,
  wornFrom: EntityId? = null,
  fireEvents: Boolean = true,
): Affect?

internal fun World.applyEquipAffects(
  wearer: Actor,
  item: Item,
  untimedOnly: Boolean = false,
  fireEvents: Boolean = true,
)

// Affects.kt: new private helpers
/** The fields both affect events carry. */
private fun World.affectEventFields(affect: Affect): Map<String, Any?>

/** Fires an affect event for [bearer], except while its own death is being processed. */
private fun World.fireAffectEvent(bearer: Actor, event: TraitEvent, fields: Map<String, Any?>)
```

**Algorithmic Steps & Invariants:**

1. `affectEventFields(affect)` returns, in this order:

   - `"name"` to `affect.name`
   - `"flags"` to `affect.flags.sorted()`
   - `"source"` to `affect.source?.let(::actor)?.takeIf { it.inWorld }`
   - `"source_item"` to `affect.wornFrom?.let(::item)`
   - `"power"` to `affect.power`

   `actor` and `item` are the `World` lookups in `World.kt`.

2. `fireAffectEvent`: `if (bearer.id in dying) return`, then `fireTraitEvent(bearer, event, fields)`. `fireTraitEvent`
   already skips a bearer that is unsimulated or out of the world.

3. `applyAffect`:

   - Keep the early returns (`!target.inWorld`, and the `lastingAs` block that returns the existing affect). They fire
     nothing.
   - Change `target.affects.removeIf { it.name == spec.name }` to `val replaced = target.affects.removeIf { ... }`.
   - After `clampVitals(target)`, and before `return affect`: `if (fireEvents) fireAffectEvent(target,
     TraitEvent.AFFECT_GAINED, affectEventFields(affect) + mapOf("expires_at" to affect.expiresAt.takeIf { it !=
     Long.MAX_VALUE }, "replaced" to replaced))`.
4. `endAffect`:
   - Insert one call after the `affect.onExpire?.let { invokeRef(...) }` line and before `if ("summoned" in
     affect.flags) dismissSummon(a)`: `fireAffectEvent(a, TraitEvent.AFFECT_LOST, affectEventFields(affect) + ("reason"
     to why.name.lowercase()))`.
   - `why` is the existing local that already turns a removal on or after the expiry pulse into `EXPIRED`.
   - Keep `endAffect`'s signature unchanged.
5. `applyEquipAffects`: pass `fireEvents = fireEvents` to both `applyAffect` calls. `removeEquipAffects` needs no
   change. A restore never removes equip affects, and death is covered by the `dying` check.
6. `Restore.kt` `settleRestoredHoldings`: change the loop's call to `applyEquipAffects(a, item, untimedOnly = true,
   fireEvents = false)`. Restored actors are already `inWorld` there, so without this every login and boot would fire
   `AFFECT_GAINED` for worn gear.
7. Invariants:
   - No event fires for item affects or room affects.
   - No event fires from list writes outside `applyAffect` and `endAffect`.
   - A reapplication fires at most one event.

### Test & Documentation Requirements

Write the tests first, in `AffectTraitEventsTest.kt`. Copy the small `Call`, `trait(...)` and `of(...)` helpers from
`TraitEventsTest.kt`. Name each test as a behavior sentence, as that file does. One test per behavior:

- `AFFECT_GAINED` fires once for a new affect, with every field:
  - `name`
  - `flags`, sorted (give two flags out of order)
  - `source` (another player)
  - `source_item` null
  - `power` 100
  - `expires_at` equal to `pulse + duration`
  - `replaced = false`
- A reapplication that doesn't last longer (equal and shorter) fires nothing.
- A longer reapplication fires exactly one `AFFECT_GAINED` with `replaced = true` and no `AFFECT_LOST`.
- The `AFFECT_GAINED` handler sees the final state. Apply an affect with a flag; inside the handler assert
  `bearer.hasFlag(flag)`.
- Expiry fires `AFFECT_LOST` with `reason = "expired"` after `on_expire`:
  - Use `RecordingHost.refHandlers` to record a marker in the `on_expire` ref, and assert from the trait handler that
    the marker is set.
  - Run pulses with `f.run(...)` past the expiry.
- `removeAffect` before expiry fires `AFFECT_LOST` with `reason = "removed"`.
- A dispel of two matching affects fires two `AFFECT_LOST` events in removal order. Use
  `applyEffect(Effect.Dispel(...))` as `CombatTest` does.
- A summoned mob (from `w.summon`) whose `summoned` affect is removed gets `AFFECT_LOST` before it leaves the world.
  Grant the trait to the summoned mob, call `removeAffect(pet, "summoned")`, and assert the call happened and that
  `pet.inWorld` was true inside the handler.
- Gear:
  - Wearing an item with an untimed `EquipAffect` fires `AFFECT_GAINED` with `source_item` equal to that item and
    `expires_at` null.
  - Removing it fires `AFFECT_LOST` with the same `source_item` and `reason = "removed"`.
  - A timed `EquipAffect` fires with `source_item` null.
  - Reuse prototypes shaped like `cap` and `helm` from `ItemCommandsTest`.
- `settleRestoredHoldings` on a player wearing an item with an untimed equip affect fires nothing. Clear the player's
  affects first, as a restore would, then call it.
- A mob that dies wearing an item with an untimed equip affect gets no `AFFECT_LOST` while its gear moves into the
  corpse.
- A dormant mob with the trait gets no call when an affect is applied to it. Mirror `a dormant mob with the trait gets
  no call` in `TraitEventsTest`.
- An affect loop is cut off at the cascade depth:
  - Register one trait with two handlers. On `AFFECT_GAINED` it calls `w.removeAffect(bearer, name)`. On `AFFECT_LOST`
    it calls `w.applyAffect(bearer, AffectSpec(name, 10))`.
  - Apply the affect once.
  - Assert that the call returns, that `w.diagnostics` contains a `cascade` entry, and that the number of handler calls
    is at most `2 * CASCADE_MAX_DEPTH`.
- An affect that ran out while its mob's room was dormant fires `AFFECT_LOST` with `reason = "expired"` when the room
  wakes. Set up the dormant room as in the `TraitEventsTest` dormant-mob test, then wake it by bringing a player into
  the room or calling the wake path the way `BubbleTest.kt` does.
- No KDoc changes beyond the enum entries above and the new parameters (`fireEvents`: "false when rebuilding affects
  that already existed, so nothing reports them as new").

### Exit Conditions

- [] `./gradlew :core:test --tests "gups.core.world.AffectTraitEventsTest"` passes.
- [] `./gradlew :core:test --tests "gups.core.world.TraitEventsTest"` and `--tests "gups.core.world.ItemCommandsTest"`
  pass unchanged.
- [] `./gradlew check` passes with zero warnings, including the module-graph check.
- [] CodeScene Code Health of `Affects.kt` and `Restore.kt` did not drop.
- [] No new comment mentions a spec section or a `D-` id.

---

## Phase 2: Plugin API, Adapter and Docs

### Objective & Scope

- **In Scope:**
  - `PluginTraitEvent.AFFECT_GAINED` and `AFFECT_LOST` with KDoc.
  - The adapter converts `Item` fields to `PluginItem`.
  - Plugin-level tests.
  - SPEC §4.15 and §4.19, `PLUGINS.md`, `CHANGELOG.md`.
- **Out of Scope:** *Do not implement these.* Any change in `core/` (Phase 1 is done), `samples/` (no sample uses the
  new events), `docs/DESIGN_NEEDED.md` and `docs/ICEBOX.md` (Phase 3), a `PluginAffect` view type, delta fields, and
  `onEvent` listeners or global hooks for affects.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/model/Creation.kt`: the Phase 1 enum entries (copy the field descriptions).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`: `ItemFacade` and `ActorFacade` constructors, and
    `applyAffect` / `removeAffect` (line ~504), which call core `applyAffect` / `removeAffect`.
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt`: `PluginWorld.applyAffect` (line ~98), `removeAffect`,
    `PluginItem`.
  - `plugin-api/src/test/kotlin/gups/pluginapi/TraitCallbackTest.kt`: `scene(...)`, the `recorder(...)` helper and the
    `HEALED through PluginWorld heal...` test (line ~176) as the pattern for driving an event through `PluginWorld`.
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`: `enum class PluginTraitEvent` (line ~413).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt`: `plainField` (line ~365).
  - `plugin-api/src/test/kotlin/gups/pluginapi/TraitCallbackTest.kt`.
  - `docs/SPEC.md`, `docs/PLUGINS.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `core/`, `samples/`, `persist/`, `docs/DESIGN_NEEDED.md`, `docs/ICEBOX.md`, `docs/DECISIONS.md`.

### Specification & Decision Record Changes

**§4.15, "Trait callbacks" bullet** (`docs/SPEC.md` line ~2955):

- In the sentence "The call carries a `PluginWorld`, which may change the world, along with the event, the `bearer` and
  `fields`.", leave the text as is.
- In the lead-in "The events and their fields, with ints as `Long`:", leave the text as is.
- After the `ROOM_ENTERED` item, append the three items below. In `SPEC.md` they are nested items of the "Trait
  callbacks" bullet, so indent each by two spaces (continuation lines by four), exactly like the existing `ROOM_ENTERED`
  item. The block below is shown unindented only because the Markdown formatter flattens it.

```markdown
- `AFFECT_GAINED`: an affect was put on the bearer, new or replacing a same-name one that ran out sooner (a
  reapplication that doesn't last longer fires nothing). Fields: `name`, `flags` (a sorted list of `String`s),
  `source` (`PluginActor` or null), `source_item` (the `PluginItem` the affect lasts while it is worn, or null),
  `power`, `expires_at` (the absolute pulse, or null when it never runs out) and `replaced` (a `Boolean`). It runs once
  the affect is in place.
- `AFFECT_LOST`: an affect came off the bearer, by expiry, `remove_affect`, `dispel` or its item coming off. Fields:
  `name`, `flags`, `source`, `source_item`, `power` and `reason` (`"expired"` or `"removed"`, as for `on_expire`). It
  runs after the affect's `on_expire`. A replaced affect fires no `AFFECT_LOST`.
- Both are for an actor's own affects, including those its worn items give it (§4.2 `equip_affects`), not affects on
  items or rooms. `source` and `source_item` are null once they have left the world. Neither fires when a saved actor is
  restored, for changes to an actor's affects while its own death is processed, or for the regen catch-up of a dormant
  mob. Affects that ran out while a room slept fire `AFFECT_LOST` when it wakes.
```

**§4.19, "Callbacks" bullet** (`docs/SPEC.md` line ~3445). In `SPEC.md` this bullet is nested two spaces deep
(continuation lines four); the blocks below are shown unindented. Replace:

```markdown
- **Callbacks:** a trait may handle events of its bearer. Unlike modifiers, a handler may change the world, through
  the plugin facade (§4.15). The one event is `skill_recognized`:
```

with:

```markdown
- **Callbacks:** a trait may handle events of its bearer. Unlike modifiers, a handler may change the world, through
  the plugin facade (§4.15), which lists the events and their fields. `skill_recognized` works as follows:
```

Leave the sub-bullets under it unchanged.

**No decision record changes.**

### Implementation Blueprint

**Signatures & Types:**

```kotlin
// PluginApi.kt: append to enum class PluginTraitEvent, after ROOM_ENTERED
  /**
   * An affect was put on the bearer: a new one, or a longer reapplication that replaced the old one.
   * Fields: `name`, `flags` (`List<String>`, sorted), `source` ([PluginActor] or null), `source_item`
   * ([PluginItem] the affect lasts while it is worn, or null), `power` ([Long]), `expires_at` ([Long],
   * or null when it never runs out) and `replaced` ([Boolean]).
   */
  AFFECT_GAINED,

  /**
   * An affect came off the bearer: it expired, was removed or dispelled, or its item came off. Not
   * fired for one a reapplication replaced. Fields: `name`, `flags`, `source`, `source_item`, `power`
   * and `reason` (`expired` or `removed`).
   */
  AFFECT_LOST,
```

```kotlin
// Adapters.kt: plainField gains one branch (import gups.core.model.Item and the host ItemFacade)
    is Item -> ItemFacade(v, w)
```

**Algorithmic Steps & Invariants:**

1. Add the two enum entries. `traitAdapter` maps by name (`TraitEvent.valueOf(it.key.name)`), so no other adapter change
   is needed. The names must match the core enum exactly.
2. Add the `is Item` branch to `plainField`, before the `Map` branch. Check the `ItemFacade` constructor signature in
   `host/Facade.kt` and match how `PluginHost.kt` (line ~145) builds one.
3. Invariant: existing events' field conversion is unchanged.

### Test & Documentation Requirements

- In `TraitCallbackTest.kt` (write first; use `scene(recorder(rec, ...))`):
  - `AFFECT_GAINED and AFFECT_LOST through PluginWorld applyAffect and removeAffect carry plugin typed fields`:
    - From inside a handler, or after the scene is set up, use the plugin facade to apply an affect to `bea`, then
      remove it.
    - Assert the event order is GAINED then LOST, `power` is a `Long`, `expires_at` is a `Long`, `replaced` is `false`,
      `source_item` is null and `reason` is `"removed"`.
  - `AFFECT_GAINED through wearing gear carries a PluginItem source_item`:
    - Add an item to the class's shared `files` map, for example in a new `zones/z/items.star`: `item("cap", name = "a
      cap", desc = "A cap.", keywords = ["cap"], wear = "held", equip_affects = ["no_regen"])`. An item with
      `equip_affects` needs a `wear` slot, or the package fails to load. `held` is the slot `FacadeTest` already uses.
    - Spawn it carried by `bea` (`w.spawnItem(Label("//zones/z:cap"), ItemLocation.Carried(bea.id))`), then call
      `w.submit(bea, "wear cap")` and run a few pulses, as `Scene.cast()` does.
    - Assert `assertIs<PluginItem>(fields["source_item"])` and `expires_at` null.
- `docs/PLUGINS.md`:
  - In the trait events list (after `ROOM_ENTERED`, line ~347), add `AFFECT_GAINED` and `AFFECT_LOST` with their fields
    in the same style as the existing items. Mention `PluginItem` in the sentence "where ints are `Long`, actors are
    `PluginActor` and rooms are `PluginRoom`", making it "..., rooms are `PluginRoom` and items are `PluginItem`".
  - Add one sentence to the paragraph that starts "Handlers run synchronously": "A replaced affect fires only
    `AFFECT_GAINED`, with `replaced` true, and restoring a saved actor fires neither."
- `docs/CHANGELOG.md`, under `## api_version 1 (unreleased)` → `### Recognizing skills and trait callbacks`, after the
  line listing the M48 events: `- Plugin trait events AFFECT_GAINED and AFFECT_LOST report a bearer's own affects,
  including those its worn gear gives it.` (format the event names as code).
- Apply the SPEC changes above.

### Exit Conditions

- [] `./gradlew :plugin-api:test --tests "gups.pluginapi.TraitCallbackTest"` passes.
- [] `./gradlew markdownFormat` run; `./gradlew markdownCheck` passes, and the code blocks in lists kept their
  indentation.
- [] `./gradlew check` passes with zero warnings, including the module-graph check.
- [] `grep -n "AFFECT_GAINED" docs/SPEC.md docs/PLUGINS.md docs/CHANGELOG.md` finds the new text in all three files.
- [] `grep -n "The one event is" docs/SPEC.md` finds nothing.

---

## Phase 3: Finalization & Backlog Update

- In `docs/DESIGN_NEEDED.md`, under `### More trait event callbacks`:
  - Prefix the affect question ("Should there be affect-gained and affect-lost trait events...") with "(Done in
    Milestone 50)".
  - Update the **Today** list of events to include `AFFECT_GAINED` and `AFFECT_LOST`.
  - Prefix the world-content question ("Should world content get a way to declare traits...") with "(Moved to ICEBOX:
    Starlark-defined choices, traits, and restrictions)".
  - Do not delete the entry. Deletion happens in a separate designer review.
- In `docs/ICEBOX.md`, under `### Starlark-defined choices, traits, and restrictions`, add one question: "- Should world
  content get player-scoped hooks or Starlark trait handlers for the trait events (`DAMAGED`, `AFFECT_GAINED` and the
  others), so a behavior doesn't need a plugin?" Add `§4.15` to that entry's **Spec:** list if it isn't there.
- Run `./gradlew markdownFormat`, then the full suite, `./gradlew check` (outside the sandbox). Confirm that no doc or
  code comment describes behavior this milestone didn't implement: item or room affect events, or Starlark trait
  handlers.
- Run the CodeScene `pre_commit_code_health_safeguard` and stop only when it passes.
