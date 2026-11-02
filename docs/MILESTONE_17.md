# Milestone 17: Effects on Items

**Prerequisite:** [M16](MILESTONE_16.md) (item quantities) is implemented. This milestone builds on its end state:
`World.splitItem` and `ctx.split` no longer exist, and `World.moveItem(item, to, count)` and `World.destroy(item,
count)` move or destroy part of a stack. The moved part is a new `Item` that copies the source's attributes, name
override and decay deadline.

**Finding code:** this document names functions and classes, not line numbers. Find them with `grep -n "fun
World.moveItem" core/src/main -r` and similar. A path written `core/.../world/Items.kt` means
`core/src/main/kotlin/gups/core/world/Items.kt`, and the same pattern holds for the other modules. Spec sentences quoted
below are line-wrapped in `SPEC.md`, so search for a short fragment of one, replace the whole sentence, and let
`./gradlew markdownFormat` rewrap it.

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** [DESIGN_NEEDED.md](DESIGN_NEEDED.md), `## Items` → `### Effects on items`.
- **Design Summary:**
  - **Item affects.** Items carry affects of their own, in two kinds:
    - **Prototype affects:** `item(affects=[item_affect(...)])`. Read live from the prototype like `stat_deltas`, with
      no duration, never persisted, and not removable from one instance.
    - **Instance affects:** `ctx.apply_affect(item, ...)`. They can be timed or permanent (`duration=None`), tick, and
      persist.
  - **`applies` scope.** Every item affect has an `applies` scope:
    - `"worn"` (the default) reaches the holder only while the item is equipped.
    - `"carried"` reaches the holder anywhere in its custody tree: inventory, equipment and nested containers.
    - An item affect's stat and sense deltas, modifiers and flags count for the holder while they reach it. They are not
      flags on the item.
  - **Containment filters.** Only a plugin's containment filter can stop a carried affect from reaching the holder
    through a container. The engine itself has no escape hatch.
  - **`HeldEffects` cache.** An eager per-actor cache holds everything items contribute (worn prototype values plus
    reaching item affects). It is rebuilt by `World.refreshHoldings(actor)` on custody changes, item-affect changes,
    item-tag changes, transforms, reloads and restores. Hot readers (`stat`, `sense`, `flags`, `hasFlag`, the modifier
    chain) read the cache. `updateEncumbrance` becomes internal and is called only by `refreshHoldings` and the world
    tick.
  - **Tombstones.** An item whose prototype became a tombstone contributes nothing to its holder. Its instance affects
    and tag changes are kept, and they are saved with it.
  - **Per-instance item tags.** `ctx.add_tag(item, tag)` and `ctx.remove_tag(item, tag)`, plus facade equivalents, store
    `addedTags` and `removedTags` changes over the prototype tags. Effective tags = proto + added − removed.
  - **`transform` and `variants`.** `ctx.transform(item, label)` and facade `transform` swap an instance's prototype in
    place, keeping id, location, count, attributes, instance affects, tag changes, name override, decay and timers.
    `item(variants={"name": ":label"})` is a meaning-free map of related prototypes, so plugins and scripts can find
    "the uncursed version" or "the identified version". This replaces engine `repair` and `identify` primitives, which
    are **not** added.
  - **Item-target skills.**
    - `apply_affect` (with `applies=`) and plugin primitives act on the target item.
    - `sequence` is allowed if it holds only those.
    - `damage`, `heal`, `dispel`, `stun`, `knockback`, `pull` and `summon` in a `target="item"` skill's effect fail the
      load.
    - `PluginEffectCall` gains `targetItem`.
    - `dispel` stays actor-only.
  - **New kernel restriction points:** `REMOVE`, `DROP`, `GIVE` and `PUT`. They are checked only in the command handlers
    (players, and mobs through `act`). Script verbs, facade verbs and engine-internal moves are trusted and bypass them,
    exactly like `WEAR` today.
  - **Curses are a plugin.** `samples/curse-plugin` refuses `remove` of a worn item tagged `cursed`. It refuses `drop`,
    `give` and `put` of an item that *bears* a curse: one tagged `cursed`, or a container holding one that isn't shut
    away behind a `warded` container. `put` is refused only when the destination is outside the actor's own custody;
    moving a cursed item between the actor's own bags is always allowed. A `warded` container that holds the cursed item
    doesn't bear the curse, so it can be dropped, given or put anywhere. It ships `curse.lift()` (transform to
    `variants["uncursed"]`, else remove the tag) and `curse.bestow()`, plus a containment filter that blocks
    curse-tagged items' affects behind containers tagged `warded`.
  - **Visibility.**
    - The item view and `PluginItem` expose `affects`, effective `tags`, `has_tag`, `variants` and (plugin only)
      `contents` and `custodian`.
    - `@stat` on an item lists its affects and tag changes.
    - The GMCP `Char.Affects` package and the `score` command keep listing the actor's own affects only. Item
      enchantments stay hidden from clients.
- **Specification Impact:**
  - §1.6 (Restriction glossary row)
  - §2.2 (source tree)
  - §3.3 (Item, Affect)
  - §3.10 (kernel row)
  - §4.2 (`item()` arguments, declaration rules)
  - §4.3 (targeting, effect primitives)
  - §4.5 (context fields note, item view, verb table and semantics)
  - §4.6 (chain order)
  - §4.9 (`keyring`)
  - §4.15 (registry, facade, seams, sample plugins)
  - §4.16 (`plugins.html` row)
  - §4.19 (restriction points table)
  - §5.9 (wake)
  - §7.4 (M17 row)
- **Decision Record Updates:** None. The old D-123 ("effect primitives act on actors") is no longer in `DECISIONS.md`;
  it lives as §4.3 text, which Phase 7 rewrites. Do not add `DECISIONS.md` entries: the maintainer approved this design,
  so spec text is edited directly.

### Standing rules for every phase

<guardrails>

1. `./gradlew check` must pass at the end of every phase (all Kotlin warnings are errors, the module-graph check
   included). Run every `./gradlew` command outside the sandbox (`BypassSandbox: true`). Do not start the next phase
   until every exit condition of the current phase passes.
2. Whenever a Markdown file changes, run `./gradlew markdownFormat`, then `./gradlew markdownCheck`.
3. Never cite spec sections or decision IDs (`§4.3`, `D-123`, `SPEC`) in Kotlin comments or KDoc. State behavior
   directly.
4. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. `api_version` 1 is unreleased, so additions and changes
   (such as `apply_affect` gaining `applies` and an optional `duration`) both join version 1. A failing `ApiDiffTest`
   means the baseline needs regenerating (rule 5), never a version bump.
5. Every phase that adds or changes script-facing symbols (builtins, verbs, view fields, declaration arguments):
   - describe the symbol where the API model reads it: its `@StarlarkMethod` doc, or its `DeclParam`/`DeclSpec` doc;
   - add bullets under `## api_version 1 (unreleased)` in `docs/CHANGELOG.md`, in a new `### Effects on items`
     subsection placed directly above `### Experience and levels`. Create the subsection in the first phase that needs
     it and keep adding to it;
   - new symbols need no `@ApiSince`: they join `api_version` 1, the current version;
   - if a test fails because a symbol has no example, add one in `script/.../DocExamples.kt`, mirroring the existing
     `apply_affect` entry;
   - **before** the phase's final `./gradlew check`, regenerate all three API files and commit them:
     - `./gradlew :cli:test -Dgups.updateGolden=true` rewrites `cli/src/test/resources/api.golden.json` and
       `cli/src/test/resources/docs-golden/api.json`;
     - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
       checkout, rewrites `docs/api/api-1.json`.
     - `ApiDiffTest` must then pass.
6. No database migrations: the engine is unreleased. New persisted fields get defaults.
7. Never read or touch `local-world/`.

</guardrails>

---

## Phase 1: `HeldEffects` cache and `refreshHoldings`

### Objective & Scope

- **In Scope:** A refactor with no gameplay change.
  - Introduce the per-actor `HeldEffects` cache holding what worn items contribute today (prototype `statDeltas`,
    `senseDeltas`, `modifiers`).
  - Introduce `World.refreshHoldings`, and route all custody call sites through it.
  - Make readers use the cache.
  - Add the empty `containmentFilters` seam.
- **Out of Scope:** item affects, tags, scripts and plugins.
- **One visible difference:** a restored actor's encumbrance is now computed when its items are placed, instead of at
  the next world tick. Nothing else changes.

### Context Working Set

- **Read / Reference Only:**
  - `core/.../model/Entities.kt`: `Actor.stat`, `sense`, `flags`, `hasFlag`, `traitStatDeltas` (the cached-trait
    pattern).
  - `core/.../world/Rules.kt`: `chainSteps`, `equipmentSlots`.
  - `core/.../world/Reconcile.kt`: `applyPrototypes`.
  - `core/.../world/Items.kt`: `spawnItem`, `moveItem`, `destroy`, `decay`, `forgetItemTree`, `holderOf`, `custodian`.
  - `core/src/test/kotlin/gups/core/world/Fixture.kt` (`Fixture`, `T`, `FixedRules`) and `ItemCommandsTest.kt` for how
    core tests build worlds and items.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Holdings.kt` (new)
  - `core/src/main/kotlin/gups/core/model/Entities.kt`
  - `core/src/main/kotlin/gups/core/world/Items.kt`
  - `core/src/main/kotlin/gups/core/world/Rules.kt`
  - `core/src/main/kotlin/gups/core/world/Reconcile.kt`
  - `core/src/main/kotlin/gups/core/world/World.kt` (the `containmentFilters` field only; the world-tick loop that calls
    `updateEncumbrance` for every simulated actor stays as it is)
  - `persist/src/main/kotlin/gups/persist/SqlitePersistence.kt` (`placeItems` only)
  - `core/src/test/kotlin/gups/core/world/HoldingsTest.kt` (new)
- **Do Not Touch:** `script/`, `plugin-api/`, docs, and the rest of `persist/`.

### Specification & Decision Record Changes

- None.

### Implementation Blueprint

- **Signatures & Types.** Put these in `Holdings.kt` (package `gups.core.world`) unless noted. `Entities.kt` already
  imports from `gups.core.world`, so `Actor` can name `HeldEffects`.

  ```kotlin
  /** What an actor's items contribute to it, recomputed by [refreshHoldings]. */
  data class HeldEffects(
    val statDeltas: Map<String, Int> = emptyMap(),
    val senseDeltas: Map<Channel, Int> = emptyMap(),
    val flags: Set<String> = emptySet(),
    /** Chain-step label to the modifiers it contributes, in modifier-chain order. */
    val modifierSteps: List<Pair<String, Modifiers>> = emptyList(),
  ) {
    companion object { val EMPTY = HeldEffects() }
  }

  /** One item affect as seen by the holder: from the prototype or from the instance. */
  class HeldAffect(
    val item: Item,
    val name: String,
    val applies: AffectScope,
    val statDeltas: Map<String, Int>,
    val senseDeltas: Map<Channel, Int>,
    val modifiers: Modifiers,
    val flags: Set<String>,
    /** The instance affect, or null for a prototype affect. */
    val instance: Affect?,
  )

  /** A plugin check that can stop an item affect from reaching its holder through a container. */
  fun interface ContainmentFilter {
    fun blocks(world: World, holder: Actor, container: Item, item: Item, affect: HeldAffect): Boolean
  }

  /** Recomputes everything [a] derives from what it holds: item contributions, then encumbrance. */
  fun World.refreshHoldings(a: Actor)

  /** What [a]'s items contribute right now, computed from scratch. Doesn't change [a]. */
  internal fun World.computeHeld(a: Actor): HeldEffects

  // Entities.kt, on Actor, next to `encumbrance`:
  /** What the actor's items contribute; kept current by `refreshHoldings`. */
  var heldEffects: HeldEffects = HeldEffects.EMPTY
    internal set

  // Entities.kt, next to `class Affect`:
  /** Where an item affect reaches its holder from. */
  enum class AffectScope { WORN, CARRIED }

  // World.kt, next to `pluginEffects`:
  /** Plugin containment filters, in plugin id order then registration order. */
  var containmentFilters: List<ContainmentFilter> = emptyList()
  ```

  `HeldAffect` and `AffectScope` are unused until Phase 2. Declaring them now keeps `ContainmentFilter`'s signature
  final.

- **Algorithmic Steps & Invariants:**

  1. **`computeHeld(a)`:**
     - Iterate equipped items in this order: `equipmentSlots()` first, then any other keys of `a.equipmentIndex` sorted
       by name. Copy the loop header from `chainSteps` exactly.
     - For each item, add `item.proto.statDeltas` and `item.proto.senseDeltas` into the running maps. When
       `item.proto.modifiers` is non-empty, append `"item:${item.protoLabel.name}" to item.proto.modifiers` to
       `modifierSteps`.
     - `flags` stays empty in this phase.
     - Return the `HeldEffects`. Drop map entries whose sum is 0 so equality checks in tests are stable.
  2. **`refreshHoldings(a)`:** `a.heldEffects = computeHeld(a)`, then `updateEncumbrance(a)`.
     - Change `fun World.updateEncumbrance` to `internal fun World.updateEncumbrance`. After this phase its only callers
       are `refreshHoldings` and the world-tick loop in `World.kt`.
  3. **Custody call sites in `Items.kt`.** Replace every `updateEncumbrance(...)` call with `refreshHoldings(...)`,
     keeping the receiver expression. There are six: one in `spawnItem`, two in each branch of `moveItem` (the old
     holder via `holderOf(from)` and the new one via `custodian(...)`), and one in each branch of `destroy`.
     - `decay` needs no change: it moves contents with `moveItem` and removes the item with `destroy`.
     - `forgetItemTree` needs no change: `destroy` calls it and refreshes the holder afterwards, and `removeActor` calls
       it for an actor that is leaving the world.
  4. **`Actor.stat(name)`:** replace `equipmentIndex.values.sumOf { it.proto.statDeltas[name] ?: 0 }` with
     `(heldEffects.statDeltas[name] ?: 0)`. In `sense(channel)`, replace the matching `senseDeltas` sum the same way.
     Update both KDocs to say "item deltas" instead of "worn equipment deltas".
  5. **`Actor.flags` and `hasFlag`:** add `heldEffects.flags` to both: `traitFlags + affects.flatMap { it.flags } +
     heldEffects.flags`, and `flag in traitFlags || affects.any { flag in it.flags } || flag in heldEffects.flags`.
     Update the `hasFlag` KDoc to "True if a trait, an active affect or an item affect reaching the actor carries
     [flag]."
  6. **`chainSteps` in `Rules.kt`:** replace the equipped-item loop (the `val slots = equipmentSlots()` line and the
     `for` loop after it) with:

     Use the loop under **Code: `chainSteps` loop** at the end of this blueprint.

     It stays last, after the affects loop.

  7. **Refresh triggers outside custody:**

     - **Reload.** In `applyPrototypes` (`Reconcile.kt`), add `for (a in actorIndex.values) refreshHoldings(a)` after
       the line `slotCache = null` and before `recomputeBubble()`. It must come after `slotCache = null`, or the
       modifier steps are ordered by the old slot list.
     - **Restore.** `placeItems` in `SqlitePersistence.kt` places every restored item and is called from `restore`,
       `loadCharacter` and `Repro.kt`. At its end, after the `for (row in waiting)` loop and before `return out`, add:

     Use the line under **Code: restore refresh** at the end of this blueprint.

     It must run after the whole placement loop, because `applyItem` (which Phase 5 makes restore item affects and tags)
     runs after `restoreItem`. `refreshHoldings` is public because `persist` is a separate Gradle module and can't call
     `internal` core functions.

  8. **GMCP:** `Char.Affects` in `Gmcp.kt` reads `p.affects` (actor affects only) and must keep doing so; do not switch
     it to `flags` or `heldEffects`, so item enchantments stay hidden from clients.

  9. **Invariant:** `a.heldEffects == computeHeld(a)` for every actor at all times. Every mutation of equipment,
     inventory, container contents, item prototypes, item affects or item tags is followed by `refreshHoldings` on the
     affected custodian. Later phases add such mutations; each one says where its refresh goes.

#### Code: `chainSteps` loop

```kotlin
for ((label, mods) in actor.heldEffects.modifierSteps) mods[point]?.let {
  out += ChainStep.Script(label, it)
}
```

#### Code: restore refresh

```kotlin
for (holder in out.mapNotNull { world.custodian(it) }.distinct()) world.refreshHoldings(holder)
```

### Test & Documentation Requirements

- `HoldingsTest.kt`, using `Fixture` and `ItemProto(...)` values passed through `Fixture(protos = ...)` as
  `ItemCommandsTest` does. After every step of every test, also assert `assertEquals(f.w.computeHeld(p),
  p.heldEffects)`, through a small `assertFresh(p)` helper:
  - wearing an item with `statDeltas` raises `stat()`, and removing it restores it;
  - `sense()` follows a worn item's `senseDeltas`;
  - a worn item's modifiers appear in the chain in slot order, as before (use `RecordingHost.modifiers` and read
    `RecordingHost.calls`, as `ModifierChainTest` does);
  - destroying a worn item clears its contribution;
  - a reload (`f.w.applyPrototypes(...)` with a changed copy of the prototypes) that changes a worn item's prototype
    `statDeltas` updates `stat()`.
- Existing `ModifierChainTest`, `ItemCommandsTest`, `TraitEffectsTest`, `PerceptionTest` and all `persist` tests must
  pass unchanged.
- KDoc on `HeldEffects`, `refreshHoldings`, `computeHeld` and `ContainmentFilter`.

### Exit Conditions

- [] `./gradlew check` passes.
- [] `grep -rn "updateEncumbrance(" core/src/main persist/src/main` shows only its definition, the call in
  `refreshHoldings` and the world-tick loop.
- [] `HoldingsTest` passes.

---

## Phase 2: Item affect model and propagation

### Objective & Scope

- **In Scope:**
  - `Affect.applies`, `Item.affects` (instance), `ItemProto.affects` (prototype, as `ItemAffect`).
  - `computeHeld` collects reaching item affects through the custody tree, applying containment filters.
  - Item flags in dormant catch-up.
  - Spec §3.3 and §4.6 text.
- **Out of Scope:** applying, expiring or ticking instance affects (Phase 3); persistence; scripts; plugins.

### Context Working Set

- **Read / Reference Only:**
  - `core/.../model/Entities.kt` (`Affect`, `Item`, `ItemLocation`)
  - `core/.../world/Holdings.kt` (Phase 1)
  - `core/.../world/Bubble.kt` (`recover`)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Entities.kt`
  - `core/src/main/kotlin/gups/core/model/Protos.kt`
  - `core/src/main/kotlin/gups/core/world/Holdings.kt`
  - `core/src/main/kotlin/gups/core/world/Bubble.kt` (`recover` only)
  - `core/src/test/kotlin/gups/core/world/HoldingsTest.kt`
  - `docs/SPEC.md`
- **Do Not Touch:** `script/`, `persist/`, `plugin-api/`.

### Specification & Decision Record Changes

- **§3.3, Item bullet list** (after the **Equipment and combat** bullet): add the bullet "**Affects and tags:**
  per-instance `affects` (timed or permanent) and per-instance tag changes over the prototype's `tags`; prototype
  `affects` are read live from the prototype. Each item affect has an `applies` scope: `worn` reaches the holder while
  the item is equipped, `carried` while it is anywhere in the holder's custody, including nested containers, unless a
  plugin containment filter blocks it (§4.15). A tombstone contributes nothing." Phase 4 appends the tag and variant
  sentences to this bullet.
- **§3.3, Affect bullet list:** add the bullet "**Scope:** item affects carry `applies` (`worn` or `carried`); an item
  affect's stat and sense deltas, flags and modifiers count for the item's holder while it reaches it."
- **§4.6, Chain order paragraph:** replace "then its worn items (in `equipment_slots()` order, then any other slots by
  name). Worn items contribute their `item(modifiers=)`;" with "then its items: each worn item in `equipment_slots()`
  order (then any other slots by name) contributes its `item(modifiers=)` and then the modifiers of its item affects
  (prototype affects in declaration order, then instance affects by id); then every other held item's `carried` affects,
  depth first through containers in item-id order. Items contribute their `item(modifiers=)` and item affects their
  `modifiers`;". Leave the rest of the paragraph as it is.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Entities.kt, class Affect: add as the LAST constructor parameter, so existing positional calls still compile.
  /** Where an item affect reaches its holder from; null for an actor's own affects. */
  val applies: AffectScope? = null,

  // Entities.kt, class Item, next to `timers`-style state:
  /** Instance affects on this item, in application order. */
  val affects: MutableList<Affect> = mutableListOf()

  // Protos.kt, new class next to EquipAffect:
  /** An affect a prototype declares; read live, never expires, can't be removed from one instance. */
  data class ItemAffect(
    val name: String,
    val applies: AffectScope = AffectScope.WORN,
    val statDeltas: Map<String, Int> = emptyMap(),
    val senseDeltas: Map<Channel, Int> = emptyMap(),
    val modifiers: Modifiers = emptyMap(),
    val flags: Set<String> = emptySet(),
  )

  // Protos.kt, ItemProto: add after `equipAffects`, with KDoc:
  /** Affects every instance carries, read live from the prototype. */
  val affects: List<ItemAffect> = emptyList(),

  // Holdings.kt
  /** Prototype affects in declaration order, then instance affects by id; empty for a tombstone. */
  fun Item.heldAffects(): List<HeldAffect>
  ```

  `ItemProto` is a `data class` whose properties are all named arguments with defaults, so adding `affects` breaks no
  call site. `AffectScope` lives in `gups.core.model`, so `Protos.kt` can use it without an import from `world`.

- **Algorithmic Steps & Invariants:**

  1. **`Item.heldAffects()`:**
     - return `emptyList()` when `proto.kind == TOMBSTONE` (the constant in `Reconcile.kt`);
     - otherwise map each `proto.affects` entry to `HeldAffect(item = this, ..., instance = null)`, then each `affects`
       entry, sorted by `id`, to `HeldAffect(item = this, ..., instance = it)`;
     - an instance affect with `applies == null` is treated as `WORN`.
  2. **`computeHeld(a)`, extending Phase 1.** Collect `flags` into a set, and skip the flag `stunned` (item affects
     never stun; Phases 3, 6 and 7 also refuse it up front).
     - **Worn pass** (same slot order as Phase 1). For each equipped item:
       - add its prototype deltas and modifiers as in Phase 1;
       - then, for each `ha` in `item.heldAffects()` (both scopes reach a worn item's holder), add `ha.statDeltas`,
         `ha.senseDeltas` and `ha.flags`, and when `ha.modifiers` is non-empty append
         `"item:${item.protoLabel.name}:${ha.name}" to ha.modifiers` to `modifierSteps`.
     - **Carried pass.** Define a local function `visit(item: Item, path: List<Item>)`:
       - for each `ha` in `item.heldAffects()` with `ha.applies == AffectScope.CARRIED`: if any container `c` in `path`
         has any filter `f` in `containmentFilters` with `f.blocks(this, a, c, item, ha) == true`, skip `ha`; otherwise
         add it exactly as in the worn pass;
       - then call `visit(inner, path + item)` for each `inner` in `item.contentsIndex.values` (already in id order).
     - Run the carried pass in this order:
       1. for each item in `a.inventoryIndex.values` (id order), `visit(item, emptyList())`;
       2. for each equipped item, in the worn-pass slot order, call `visit(inner, listOf(equipped))` for each `inner` in
          `equipped.contentsIndex.values`. Do not call `visit` on the equipped item itself: its affects were already
          counted in the worn pass.
     - A `worn`-scope affect of an item that isn't equipped never reaches the holder.
  3. **Filters run only inside `computeHeld`**, never on reads. A filter that throws counts as not blocking. Wrap each
     call: `runCatching { f.blocks(this, a, c, item, ha) }.getOrElse { e -> record(Diagnostic(pulse, "plugin",
     "containment filter failed: ${e.message}")); false }`. Find `record` and `Diagnostic` by grepping `World.kt`.
  4. **Item affects never trigger `disabledChanged`**, since they can't carry `stunned`.
  5. **Dormant catch-up:** in `recover` (`Bubble.kt`), change `val suppressed = live.any { "no_regen" in it.flags }` to
     `val suppressed = live.any { "no_regen" in it.flags } || "no_regen" in m.heldEffects.flags`, so a mob holding a
     `no_regen` item recovers like one with a `no_regen` affect.

### Test & Documentation Requirements

- Extend `HoldingsTest.kt`. Build prototypes directly, for example `ItemProto(label, "a charm", listOf("charm"), affects
  = listOf(ItemAffect("luck", AffectScope.CARRIED, statDeltas = mapOf("dex" to 1))))`, and keep calling `assertFresh`
  after every step:
  - a `worn`-scope prototype affect counts while worn and not while only carried;
  - a `carried`-scope affect counts in each of these places: in the inventory, worn, inside a bag in the inventory,
    inside a bag inside a backpack in the inventory, and inside a worn backpack;
  - moving the inner bag to the ground (`f.w.moveItem(bag, ItemLocation.InRoom(...))`) removes the contribution, and so
    does destroying the backpack;
  - an item affect's flag shows in `hasFlag` and `flags`;
  - an item affect's `stunned` flag is ignored (`isStunned` stays false);
  - a tombstone contributes nothing: give a carried item an instance affect (add an `Affect(..., applies =
    AffectScope.CARRIED)` to `item.affects` directly, since `applyItemAffect` arrives in Phase 3), call
    `refreshHoldings`, see it count, then set `item.proto = tombstone(item.proto)`, refresh again, and see it stop
    counting. Testing a prototype affect would prove nothing, because `tombstone(...)` builds a prototype without
    `affects`;
  - a test `ContainmentFilter` set on `f.w.containmentFilters` that blocks when `"lead" in container.proto.tags` blocks
    only affects behind that container, and never an affect of a directly carried or worn item;
  - a filter that throws does not block, and records a `plugin` diagnostic;
  - modifier chain order matches the new §4.6 wording: a worn item's `modifiers`, then its affect modifiers, then
    carried affects' modifiers.
- KDoc on `ItemAffect`, `Item.affects`, `Affect.applies` and `heldAffects`.

### Exit Conditions

- [] `./gradlew check` and `./gradlew markdownCheck` pass.
- [] The new `HoldingsTest` cases pass.

---

## Phase 3: Instance item affect lifecycle

### Objective & Scope

- **In Scope:**
  - Applying and removing instance affects on items.
  - Expiry and ticking while the item is simulated.
  - Catch-up on wake.
  - Stacking rules, and copying affects onto the part a partial-count `moveItem` creates.
- **Out of Scope:** persistence (Phase 5); script verbs (Phase 8).

### Context Working Set

- **Read / Reference Only:**
  - `core/.../world/Combat.kt` (`applyAffect`, `endAffect`, `removeAffects`, `expireAffect`)
  - `core/.../world/World.kt` (`runTimers`, `allocateHandle`)
  - `core/.../world/Bubble.kt` (`wake`, `isSimulated`, `entitiesIn`)
  - `core/.../world/Hooks.kt` (`invokeRef(owner: Entity, ...)`)
  - `core/.../world/KernelCommands.kt` (`mergesInto`, used by `PutAction`)
  - `core/src/test/.../Fixture.kt` (`RecordingHost`, its `refHandlers` and `calls`)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Combat.kt`
  - `core/src/main/kotlin/gups/core/world/World.kt`
  - `core/src/main/kotlin/gups/core/world/Bubble.kt`
  - `core/src/main/kotlin/gups/core/world/Items.kt` (`stack`, a new `canStack`, and the partial-count branch of
    `moveItem`)
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt` (`mergesInto` only)
  - `core/src/test/kotlin/gups/core/world/ItemAffectsTest.kt` (new)
  - `docs/SPEC.md`
- **Do Not Touch:** `script/`, `persist/`, `plugin-api/`.

### Specification & Decision Record Changes

- **§3.3, Affect, Replacement rule bullet:** append "A permanent item affect counts as expiring latest."
- **§5.9 (wake):** in the sentence that says waking removes mobs' run-out affects, add "and the run-out affects of every
  item in the room (on the ground, inside containers, or carried by its mobs), in expiry order".
- **§4.5, Verb semantics, Items bullets:** extend the `move_item` bullet: "A moved part also copies the stack's instance
  affects and tag changes." Add the bullet "An item with instance affects or tag changes never merges into a stack."

### Implementation Blueprint

- **Signatures & Types** (`Combat.kt`, next to `applyAffect`):

  ```kotlin
  /**
   * Puts instance affect [name] on [item]; [duration] null makes it permanent. A same-name affect is
   * replaced only by one that expires later.
   */
  fun World.applyItemAffect(
    item: Item,
    name: String,
    duration: Int?,
    applies: AffectScope = AffectScope.WORN,
    statDeltas: Map<String, Int> = emptyMap(),
    flags: Set<String> = emptySet(),
    tickEvery: Int? = null,
    onTick: HookRef? = null,
    onExpire: HookRef? = null,
    expireMessage: String = "",
    source: Actor? = null,
    modifiers: Modifiers = emptyMap(),
    senseDeltas: Map<Channel, Int> = emptyMap(),
  ): Affect?

  /** Removes instance affect [name] from [item]; true if there was one. */
  fun World.removeItemAffect(item: Item, name: String): Boolean

  internal fun World.expireItemAffect(item: Item, affect: Affect)

  // Items.kt
  /** True if [item] may merge into a stack at all: no timers, name override, affects or tag changes. */
  internal fun Item.canStack(): Boolean
  ```
- **Algorithmic Steps & Invariants:**
  1. **`applyItemAffect`**, mirroring `applyAffect`:
     - return null when `item.id !in entities`;
     - `require(duration == null || duration >= 1) { "apply_affect: duration must be at least 1" }`;
     - `require("stunned" !in flags) { "apply_affect: item affects can't stun" }`;
     - `val expiresAt = duration?.let { pulse + it } ?: Long.MAX_VALUE`;
     - if `item.affects` has an affect with the same name and its `expiresAt >= expiresAt`, return it unchanged;
     - otherwise remove that affect (if any) and add a new `Affect(allocateHandle(), name, source?.id, pulse, expiresAt,
       statDeltas, flags, tickEvery, onTick, onExpire, expireMessage, modifiers, wornFrom = null, senseDeltas, applies =
       applies)`;
     - set `item.dirty = true`, then `custodian(item)?.let { refreshHoldings(it) }`, and return the new affect.
  2. **`removeItemAffect`, `expireItemAffect`** and a private `endItemAffect(item, affect, reason: AffectEndReason)`,
     mirroring `removeAffect`, `expireAffect` and `endAffect`:
     - `endItemAffect` returns at once if `!item.affects.remove(affect)`; otherwise it sets `item.dirty = true` and
       refreshes the custodian;
     - `val why = if (pulse >= affect.expiresAt) AffectEndReason.EXPIRED else reason`;
     - when `why == EXPIRED`, the message is non-empty and `custodian(item)` is non-null, `send(custodian,
       affect.expireMessage)`; with no custodian the message is dropped;
     - `affect.onExpire?.let { invokeRef(item, it, HookContext(affect = affect, affectEndReason = why)) }`, so the
       hook's `ctx.self` is the item;
     - there is no summon handling and no `disabledChanged`.
     - `removeItemAffect(item, name)` ends every affect named `name` with reason `REMOVED` and returns whether any
       matched. `expireItemAffect` uses reason `EXPIRED`.
  3. **`runTimers` (`World.kt`):** add a **separate** loop after the existing actor-affect loop and before
     `due.sortWith(...)`. Do not put it inside the first `for (e in entities.values)` loop, because that loop skips
     entities without timers.

     Use the loop under **Code: item affect timers** at the end of this blueprint.

     All `Due` entries, actor and item, are sorted together by `(due, id)` by the existing `sortWith`.

  4. **`wake(room)` (`Bubble.kt`):** right after the loop that expires mob affects, add:

     Use the code under **Code: waking item affects** at the end of this blueprint.

     `sleepers` comes from `entitiesIn(room)`, which already includes ground items, nested contents and items carried by
     the room's mobs.

  5. **Stacking.** Add `Item.canStack()` in `Items.kt`: `proto.stackable && timers.isEmpty() && nameOverride == null &&
     affects.isEmpty()`. Then:

     - in `stack`, replace the first line's condition with `if (!item.canStack()) return item`, and in the `peers`
       filter replace `it.timers.isEmpty() && it.nameOverride == null` with `it.canStack()`;
     - in `mergesInto` (`KernelCommands.kt`), do the same: its first line becomes `if (!item.canStack()) return false`,
       and its `box.contents.any { ... }` filter uses `it.canStack()`.

     Both must refuse exactly the same merges, or `put` reports room in a full container that `stack` then can't use.
     Phase 4 adds the tag condition inside `canStack`.

  6. **Partial-count `moveItem`:** right after `part.customAttributes.putAll(item.customAttributes)`, copy each instance
     affect: `for (a in item.affects) part.affects += Affect(allocateHandle(), a.name, a.source, a.appliedAt,
     a.expiresAt, a.statDeltas, a.flags, a.tickEvery, a.onTick, a.onExpire, a.expireMessage, a.modifiers, a.wornFrom,
     a.senseDeltas, a.applies)`. Only the id is new. The refresh of both holders already happens at the end of that
     branch.

#### Code: item affect timers

```kotlin
for (e in entities.values) {
  if (e !is Item || e.affects.isEmpty() || !isSimulated(e)) continue
  for (aff in e.affects) {
    val every = aff.tickEvery
    when {
      aff.expiresAt <= pulse -> due += Due(aff.expiresAt, aff.id) { expireItemAffect(e, aff) }
      every != null && aff.onTick != null && (pulse - aff.appliedAt) % every == 0L ->
        due += Due(pulse, aff.id) { invokeRef(e, aff.onTick, HookContext(affect = aff)) }
    }
  }
}
```

#### Code: waking item affects

```kotlin
val expiredOnItems =
  sleepers
    .filterIsInstance<Item>()
    .flatMap { i -> i.affects.filter { it.expiresAt <= pulse }.map { i to it } }
    .sortedWith(compareBy({ it.second.expiresAt }, { it.second.id }))
for ((i, a) in expiredOnItems) guarded("expiry of ${a.name} on $i") { expireItemAffect(i, a) }
```

### Test & Documentation Requirements

- `ItemAffectsTest.kt`, using `Fixture(scripts = host)` with `val host = RecordingHost()`. For hook assertions, register
  `host.refHandlers["gone"] = { _, owner, ctx -> ... }` and check `host.calls`. Use `f.run(n)` or `f.runTo(pulse)` to
  advance time.
  - a timed `carried` affect expires at its pulse: the holder's stat drops, and `on_expire` (a `HookRef` named `gone`)
    runs with `owner` the item and `ctx.affectEndReason == EXPIRED`;
  - a permanent affect (`duration = null`) never expires (run 1000 pulses);
  - the replacement rule: a longer timed affect replaces a shorter one, a shorter one doesn't replace a longer one, and
    a timed one doesn't replace a permanent one;
  - `on_tick` runs every `tickEvery` pulses;
  - with `Fixture(bubble = true)`, an affect on an item on the ground in a dormant room doesn't expire while the room is
    dormant, and expires (firing `on_expire`) when a player walks in and the room wakes;
  - `stunned` in `flags` throws `IllegalArgumentException`;
  - `stack` doesn't merge an item that has an affect into a matching stack, and `put` into a full container holding a
    matching stack is refused when the item has an affect;
  - moving 3 of a 10-arrow stack that has an affect gives the moved part a copy of the affect with a new id and the same
    `expiresAt`.
- KDoc on the new functions.

### Exit Conditions

- [] `./gradlew check` and `./gradlew markdownCheck` pass.
- [] `ItemAffectsTest` passes.

---

## Phase 4: Item tags, `variants` and `transform` in core

### Objective & Scope

- **In Scope:**
  - Per-instance tag changes and effective tags.
  - `ItemProto.variants`.
  - The `transformItem` world verb.
  - Stacking and partial-count moves for tag changes.
- **Out of Scope:** script and plugin surfaces; persistence (Phase 5).

### Context Working Set

- **Read / Reference Only:**
  - `core/.../world/Reconcile.kt` (`applyPrototypes`, which swaps `item.proto` on a reload)
  - `core/.../world/Combat.kt` (`applyEquipAffects`, `removeEquipAffects`)
  - `core/.../world/Items.kt` (`index`, `unindex`, `custodian`, `ItemEvent` uses)
  - `core/.../world/KernelCommands.kt` (`slotFor`)
  - `script/.../Declarations.kt` (`TAG_REGEX`, `parseTags`)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Entities.kt`
  - `core/src/main/kotlin/gups/core/model/Protos.kt`
  - `core/src/main/kotlin/gups/core/world/Verbs.kt`
  - `core/src/main/kotlin/gups/core/world/Items.kt`
  - `script/src/main/kotlin/gups/script/Declarations.kt` (only to replace the private `TAG_REGEX` with the core one)
  - `core/src/test/kotlin/gups/core/world/ItemTagsTest.kt` (new)
  - `docs/SPEC.md`
- **Do Not Touch:** the rest of `script/`, `persist/`, `plugin-api/`.

### Specification & Decision Record Changes

- **§3.3, Item, the "Affects and tags" bullet from Phase 2:** append "Effective `tags` are the prototype's plus those
  added, minus those removed, so a reload's prototype tag changes still reach instances. `variants` maps free-form names
  to related item prototypes; `transform` swaps an instance's prototype in place."
- **§4.2, Tags bullet:** replace "They are part of the prototype, so a reload changes them for every instance." with
  "They are part of the prototype, so a reload changes them for every instance; items can also gain or lose tags per
  instance (§4.5)."

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Protos.kt, top level:
  /** The pattern every tag, flag-like name and variant name must match. */
  val TAG_REGEX = Regex("[a-z][a-z0-9_]*")

  // Entities.kt, Item:
  /** Tags this instance has gained over its prototype's. */
  val addedTags: MutableSet<String> = sortedSetOf()
  /** Prototype tags this instance has lost. */
  val removedTags: MutableSet<String> = sortedSetOf()
  /** Effective tags: the prototype's plus [addedTags], minus [removedTags]. */
  val tags: Set<String>
    get() = (proto.tags + addedTags) - removedTags   // replaces the proto-only getter

  // Entities.kt, Item: `val container` becomes
  var container: ContainerState? = proto.container?.let(::ContainerState)
    internal set

  // Protos.kt, ItemProto, after `affects`:
  /** Free-form variant names to related item prototypes; the engine gives the names no meaning. */
  val variants: Map<String, Label> = emptyMap(),

  // Verbs.kt
  /** Adds [tag] to [item]'s effective tags; false if it already had it. */
  fun World.addItemTag(item: Item, tag: String): Boolean
  /** Removes [tag] from [item]'s effective tags; false if it didn't have it. */
  fun World.removeItemTag(item: Item, tag: String): Boolean
  /** Swaps [item]'s prototype for [label] in place, keeping everything the instance owns. */
  fun World.transformItem(item: Item, label: Label)
  ```

  In `Declarations.kt`, delete `private val TAG_REGEX = ...` and import `gups.core.model.TAG_REGEX` instead.
  `Item.inBareAll` (`KernelCommands.kt`) already reads `tags`, so `add_tag(item, "no_all")` keeps one item out of bare
  `all` with no further change.

- **Algorithmic Steps & Invariants:**

  1. **Tag validation.** Both tag verbs start with `require(item.id in entities) { "$item is not in the world" }` and
     `require(TAG_REGEX.matches(tag)) { "'$tag' is not a valid tag" }`.
  2. **`addItemTag`:** return false if `tag in item.tags`. Otherwise, if `tag in removedTags` remove it from
     `removedTags`, else add it to `addedTags`. Then do step 4 and return true.
  3. **`removeItemTag`:** return false if `tag !in item.tags`. Otherwise, if `tag in addedTags` remove it from
     `addedTags`, else add it to `removedTags`. Then do step 4 and return true.
  4. After any change: `item.dirty = true`, then `custodian(item)?.let { refreshHoldings(it) }`, because containment
     filters may read tags.
  5. **`transformItem`**, in exactly this order:
     1. `require(item.id in entities) { "$item is not in the world" }`. `val newProto = protos.items[label]`, and
        `requireNotNull(newProto) { "transform: $label is not an item" }`.
     2. Refuse with `require(...)`, before changing anything, when any of these hold:
        - `item.count > 1 && !newProto.stackable` ("transform: a stack can't become a non-stackable item");
        - `item.contentsIndex.isNotEmpty() && newProto.container == null` ("transform: a container with contents must
          stay a container");
        - `newProto.container != null && (item.contentsIndex.size > newProto.container.capacity ||
          item.contentsIndex.values.sumOf { it.weight } > newProto.container.maxWeight)` ("transform: the contents don't
          fit").
     3. `val loc = item.location as? ItemLocation.Equipped`, `val wearer = loc?.let { actor(it.actor) }`. If `wearer !=
        null`, decide whether the item still fits: `val wear = newProto.wear` and `val fits = wear != null && (loc.slot
        == wear || loc.slot.startsWith("${wear}_"))`.
     4. If `wearer != null && !fits`: call `moveItem(item, ItemLocation.Carried(wearer.id))` **now**, while the old
        prototype is still set, so `unindex` removes the old `equip_affects`.
     5. Else if `wearer != null` (it fits): call `removeEquipAffects(wearer, item)` **now**, while the old prototype is
        still set.
     6. Swap: `item.proto = newProto` and `item.protoVersion = newProto.version`.
     7. Container: `val old = item.container`. Set `item.container = newProto.container?.let { ContainerState(it) }`,
        then `old?.let { o -> item.container?.state = o.state }` so an open box stays open.
     8. If the item is still equipped (case 5): `applyEquipAffects(wearer, item)`.
     9. `item.dirty = true`. When `custodian(item)` is a `Player` and `!item.debug`, append `ItemEvent(item.id, pulse,
        item.location, item.location, "transform")` to `itemEvents`.
     10. `custodian(item)?.let { refreshHoldings(it) }`. No hooks fire.

     The instance keeps its id, location (unless moved in step 4), count, `customAttributes`, `affects`, `addedTags`,
     `removedTags`, `nameOverride`, `decayAt` and `timers`.

  6. **`canStack`:** add `&& addedTags.isEmpty() && removedTags.isEmpty()`. `stack` and `mergesInto` pick it up.

  7. **Partial-count `moveItem`:** next to the Phase 3 affect copy, add `part.addedTags += item.addedTags` and
     `part.removedTags += item.removedTags`.

  8. **Reload:** `applyPrototypes` assigns `item.proto` and must leave `addedTags` and `removedTags` alone. No change
     there is needed; the reload test below checks it.

### Test & Documentation Requirements

- `ItemTagsTest.kt`:
  - effective tags with an added and a removed tag;
  - a removed prototype tag stays removed across a reload;
  - adding a tag the prototype already has returns false and changes nothing;
  - `addItemTag(item, "Bad Tag")` throws `IllegalArgumentException`;
  - transform keeps the id, slot, attributes, instance affects and tag changes;
  - transform of a worn item to a slot-less prototype moves it to the inventory, and the old `equip_affects` flag is
    gone from the wearer;
  - transform of a worn item to a prototype with the same slot keeps it worn and swaps `equip_affects` (old flag gone,
    new flag present);
  - transform refuses a non-stackable target for a stack of 2, a non-container target for a container with contents, and
    a container target too small for the contents; in each case nothing changes;
  - transform updates the holder's `stat()` at once, and `computeHeld` still matches `heldEffects`;
  - `stack` doesn't merge an item with tag changes, and a part moved off a stack with tag changes has the same changes.

### Exit Conditions

- [] `./gradlew check` and `./gradlew markdownCheck` pass.
- [] `ItemTagsTest` passes.

---

## Phase 5: Persistence and `@stat`

### Objective & Scope

- **In Scope:**
  - Persist instance item affects and tag changes in `ItemPayload`.
  - Verify holdings are refreshed after restore (wired in Phase 1).
  - `@stat` on an item lists its affects and tag changes.
- **Out of Scope:** script and plugin surfaces.

### Context Working Set

- **Read / Reference Only:**
  - `persist/.../Codec.kt` (`AffectJson`, `affectsJson`, `restoreAffects`, `ItemPayload`, `World.encode` for `is Item`,
    `applyItem`)
  - `persist/src/test/.../SensesPersistenceTest.kt` and `PersistFixture.kt` (pattern)
  - `core/.../world/Tools.kt` (the `@stat` text: the actor `Affects:` line and the `is Item ->` block)
- **Files to Modify / Create:**
  - `persist/src/main/kotlin/gups/persist/Codec.kt`
  - `core/src/main/kotlin/gups/core/world/Tools.kt`
  - `persist/src/test/kotlin/gups/persist/ItemAffectsPersistenceTest.kt` (new)
  - `core/src/test/kotlin/gups/core/world/ToolsTest.kt`
- **Do Not Touch:** `script/`, `plugin-api/`, `SPEC.md` (persistence detail lives in the Codec KDoc).

### Specification & Decision Record Changes

- None in `SPEC.md`. In the Codec file KDoc, extend the "**Items ([ItemPayload]):**" bullet's list with "instance
  affects, added and removed tags".

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // ItemPayload gains, after `anchor` (defaults keep old rows readable):
  val affects: List<AffectJson> = emptyList(),
  val addedTags: List<String> = emptyList(),
  val removedTags: List<String> = emptyList(),

  // AffectJson gains, last:
  /** "worn" or "carried" for an item affect; null for an actor's own affects. */
  val applies: String? = null,

  // Codec.kt, new helpers that the actor functions also use:
  internal fun affectJson(a: Affect): AffectJson
  internal fun affectFromJson(x: AffectJson): Affect
  ```
- **Algorithmic Steps & Invariants:**
  1. Move the per-affect body of `affectsJson`'s `.map { ... }` into `affectJson(a)`, adding `applies =
     a.applies?.name?.lowercase()`. `affectsJson(a: Actor)` becomes `a.affects.filter { it.wornFrom == null
     }.map(::affectJson)`. `expiresAt == Long.MAX_VALUE` round-trips as is.
  2. Move the `Affect(...)` construction in `restoreAffects` into `affectFromJson(x)`, adding `applies = x.applies?.let
     { AffectScope.valueOf(it.uppercase()) }`. `restoreAffects` keeps its `wornFrom` filter and calls `affectFromJson`.
  3. In `World.encode`'s `is Item ->` branch, add `affects = e.affects.map(::affectJson)`, `addedTags =
     e.addedTags.toList()` and `removedTags = e.removedTags.toList()` to the `ItemPayload(...)` call.
  4. In `applyItem`, add `item.affects.clear()`, `item.affects += p.affects.map(::affectFromJson)`,
     `item.addedTags.clear()`, `item.addedTags += p.addedTags`, `item.removedTags.clear()` and `item.removedTags +=
     p.removedTags`.
  5. Confirm the Phase 1 `refreshHoldings` loop at the end of `placeItems` runs after every `applyItem`. The round-trip
     test below fails if it doesn't.
  6. **`@stat <item>`** (`Tools.kt`, the `is Item ->` block): after the existing `Tags:` line, add:
     - `Affects: none`, or `Affects: ` followed by one entry per `e.heldAffects()`, joined by `", "`. Each entry is
       `"<name> (<applies>, <when><flags>)"`, where `<applies>` is `worn` or `carried`; `<when>` is `prototype` for a
       prototype affect, `permanent` when `expiresAt == Long.MAX_VALUE`, else `"${(expiresAt - pulse).coerceAtLeast(0)
       * PULSE_MS / 1000}s left"` (the actor line's formula); and `<flags>` is `""` or `"; " + flags.sorted()
         .joinToString()`. Example: `Affects: misfortune (carried, prototype), keen (worn, 30s left; glowing)`.
     - when either set is non-empty, `Tag changes: ` followed by `+tag` for each added tag and then `-tag` for each
       removed tag, sorted, joined by `", "`. Example: `Tag changes: +cursed, -shiny`.
     - The `Tags:` line already shows effective tags, because `e.tags` is effective since Phase 4.

### Test & Documentation Requirements

- `ItemAffectsPersistenceTest.kt`, following `SensesPersistenceTest`:
  - a timed and a permanent instance affect on a carried item round-trip with the same id, `applies`, `expiresAt` and
    deltas, and the restored player's `stat()` includes the `carried` affect without running a pulse;
  - added and removed tags round-trip;
  - an item payload JSON without the new fields decodes, with empty affects and tags.
- `ToolsTest`: `@stat` on an item with a prototype affect, a timed instance affect and a tag change shows the exact
  `Affects:` and `Tag changes:` lines.

### Exit Conditions

- [] `./gradlew check` passes.
- [] The new persistence and `ToolsTest` cases pass.

---

## Phase 6: `item_affect`, `item(affects=)` and `item(variants=)` declarations

### Objective & Scope

- **In Scope:** the `item_affect(...)` declaration builtin; the `affects` and `variants` arguments of `item()`; the
  keyring rule counting prototype `affects` and each item's own instance affects and added tags; spec §4.2, §3.10 and
  §4.9.
- **Out of Scope:** skills and effect primitives (Phase 7); ctx verbs and views (Phase 8).

### Context Working Set

- **Read / Reference Only:**
  - `script/.../Declarations.kt`: `ContainerDecl`, `container(...)` and `CONTAINER_SPEC` (the pattern to copy),
    `item(...)`, `ITEM_SPEC`, `DECLARATION_SPECS`, `equipAffects`, `typed`, `senses`, `modifiers`, `parseTags`.
  - `core/.../world/KernelCommands.kt`: `ItemProto.isKeyOnly` (near the end of the file) and `Item.inBareAll`.
  - `script/src/test/.../M5ScriptTest.kt` and `ScriptFixture.kt` (how script tests load packages and drive a world).
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Declarations.kt`
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt` (`isKeyOnly`, the new `onKeyring`, and its three callers
    only)
  - `core/src/test/kotlin/gups/core/world/ItemCommandsTest.kt`
  - `script/src/test/kotlin/gups/script/ItemEffectsScriptTest.kt` (new)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the three API files (standing rule 5)
- **Do Not Touch:** `Ctx.kt`, `Views.kt`, the rest of `core/`, `plugin-api/`.

### Specification & Decision Record Changes

- **§4.2 example `item("short_bow", ...)`:** after the line `modifiers={}, equip_affects=[], # apply while worn (§4.6)`
  add the lines `affects=[], # item_affect(...) entries (§3.3)` and `variants={}, # related prototypes, for transform
  (§4.5)`, aligned like their neighbours.
- **§4.2 Declaration rules, Item slots bullet:** append "`item_affect(name, applies = "worn", stat_deltas = stats(),
  sense_deltas = {}, modifiers = {}, flags = [])` declares a prototype affect. A `worn` affect with `modifiers` needs a
  `wear` slot; a `carried` one doesn't. `stunned` isn't allowed. `variants` keys match the tag pattern and its values
  are item labels, checked at load like `loot`."
- **§3.10 kernel row:** after "items and equipment," add "item affects (`item_affect`, item `affects` and `variants`),".
- **§4.9 `keyring` bullet:** change the list "(no `wear` slot, `container`, `commands`, `dice`, `stat_deltas`,
  `equip_affects` or `modifiers`)" to "(no `wear` slot, `container`, `commands`, `dice`, `stat_deltas`, `sense_deltas`,
  `equip_affects`, `affects` or `modifiers`)", and append to that sentence ", and that haven't gained instance affects
  or tags (a key cursed in play shows in `inventory`)". `sense_deltas` was already checked by the code but missing from
  the text.
- **CHANGELOG:** bullets for `item_affect(...)`, `item(affects=)` and `item(variants=)`, and one saying a key that gains
  instance affects or tags in play leaves the keyring and shows in `inventory` until they're gone.

### Implementation Blueprint

- **Signatures & Types** (`Declarations.kt`):

  ```kotlin
  // next to ContainerDecl:
  internal class ItemAffectDecl(val affect: ItemAffect) : DeclValue("item_affect")

  // a @StarlarkMethod(name = "item_affect", ..., extraPositionals = Param(name = "args"),
  // extraKeywords = Param(name = "kwargs"), useStarlarkThread = true) builtin right after container(...).
  // It takes a positional name, so bind it like loot(...) does: ITEM_AFFECT_SPEC.bind(args, kwargs, ...).
  // container(...) binds kwargs only and is the wrong model for the binding.

  // companion: a new ITEM_AFFECT_SPEC next to CONTAINER_SPEC, with DeclParams
  //   name (str, positional = true, keyword = false, required = true, shaped like ITEM_SPEC's `id`), applies (str, default 'worn', allowedValues = listOf("worn", "carried")),
  //   stat_deltas (stats, default stats()), sense_deltas (dict[str, int], default {}),
  //   modifiers (dict[str, function], default {}), flags (list[str], default []).
  // Add ITEM_AFFECT_SPEC to DECLARATION_SPECS right after CONTAINER_SPEC.

  // ITEM_SPEC gains two DeclParams after equip_affects:
  //   affects  (list[item_affect], default [], "Affects every instance carries, built with item_affect(...).")
  //   variants (dict[str, str], default {}, "Related item prototypes by free-form name, for transform.")
  ```
- **Algorithmic Steps & Invariants:**
  1. **`item_affect(...)` builtin:**
     - parse `stat_deltas` with `typed(d["stat_deltas"], TypedKind.STATS, d.what("stat_deltas"))`, `sense_deltas` with
       `senses(d["sense_deltas"], d.what("sense_deltas"), schema, true)`, `modifiers` with `modifiers(d["modifiers"],
       d.what("modifiers"), features)`, `flags` with `d.strList("flags")` checked against `TAG_REGEX` (as `parseTags`
       does), and `applies` with `d.choice("applies")`;
     - fail with `"item_affect(flags): 'stunned' isn't allowed; item affects can't stun"` when `stunned` is in `flags`;
     - fail with `"item_affect(name): can't be blank"` on a blank name;
     - return `ItemAffectDecl(ItemAffect(...))`.
  2. **`item()`:**
     - `affects`: each element must be an `ItemAffectDecl` (else fail with `"item(affects): expected item_affect(...),
       got <type>"`, using `typeName`); duplicate names fail with `"item(affects): '<name>' appears twice"`;
     - `variants`: a dict; each key must match `TAG_REGEX` (else `"item(variants): '<key>' is not a valid variant
       name"`); each value is parsed with `label(it, ctx.pkg.path, d.what("variants"))` and checked with `ctx.ref(it,
       RefKind.ITEM, thread)`, as `container?.key` is checked;
     - pass both into `ItemProto(...)`;
     - extend the existing "needs a wear slot" check: also fail when `proto.wear == null` and any `affects` entry has
       `applies == WORN` and non-empty `modifiers`, with `"item(): a worn item_affect with modifiers needs a wear
       slot"`.
  3. **Keyring.** A cursed key mustn't hide on the keyring, whether the curse comes from its prototype or was put on
     this one key in play (for example by `curse.bestow()` in Phase 11, which adds the instance tag `cursed`). In
     `ItemProto.isKeyOnly`, add `affects.isEmpty() &&` and add `affects` to its KDoc list.
  4. **Item-level keyring check.** Right after `isKeyOnly`, add `internal val Item.onKeyring: Boolean get() =
     proto.isKeyOnly && affects.isEmpty() && addedTags.isEmpty()`, with the KDoc "True if [this] goes on the keyring:
     its prototype is key-only and this instance has gained no affects or tags of its own." `removedTags` doesn't count,
     because losing a tag doesn't give a key a purpose. Prototype `tags` don't count either, as today.
  5. **Callers.** Replace the three item uses of `proto.isKeyOnly` with `onKeyring`: in `inventory` write `filterNot {
     it.onKeyring }`, in `keyring` write `filter { it.onKeyring }`, and in `Item.inBareAll` write `!onKeyring`. After
     this, only `onKeyring` reads `isKeyOnly`. Bare `all` therefore picks up such a key too. The keyring is recomputed
     on every command, so a key returns to it as soon as its last instance affect expires or its added tag is removed;
     there's nothing to refresh.

### Test & Documentation Requirements

- `ItemEffectsScriptTest.kt`, mirroring `M5ScriptTest`'s package-loading setup:
  - `item("charm", affects=[item_affect("luck", applies="carried", stat_deltas=stats(dex=-1))])` counts from inside a
    bag inside a backpack in the player's inventory, and stops counting when the bag is moved to the ground with
    `ctx.move_item` from a test command or with `world.moveItem` from Kotlin;
  - each of these fails to load with its message: a `variants` label that doesn't exist; a variant name `"Bad"`; a
    `worn` `item_affect` with modifiers on a slot-less item; `stunned` in `item_affect(flags=)`; a non-`item_affect`
    value in `affects`;
  - a `kind="key"` item with `affects` is listed by `inventory`, not by `keyring`.
- `ItemCommandsTest.kt`, using the existing `bareKey` prototype, `fixture()` and `carry`:
  - `a key that gains a tag leaves the keyring until the tag is removed`: carry `bareKey`; `f.w.addItemTag(key,
    "cursed")`; `inventory` contains `"an iron key"` and `keyring` contains `"Your keyring is empty."`; bare `drop all`
    drops it (assert `key.location` is `InRoom`). Pick it up again with `get key`, then `f.w.removeItemTag(key,
    "cursed")`; `keyring` contains `"Your keyring holds:"` and `"an iron key"` and `inventory` doesn't contain `"an iron
    key"`.
  - `a key that gains an affect leaves the keyring until it expires`: carry `bareKey`; `f.w.applyItemAffect(key, "glow",
    duration = 1, applies = AffectScope.CARRIED)`; `keyring` contains `"Your keyring is empty."`; advance the world with
    `f.run(2)`; `keyring` contains `"Your keyring holds:"` and `"an iron key"`.

### Exit Conditions

- [] The API files have been regenerated (standing rule 5), then `./gradlew check` and `./gradlew markdownCheck` pass.
- [] `ItemEffectsScriptTest` passes.

---

## Phase 7: Item-target skill effects

### Objective & Scope

- **In Scope:** `applies=` and `duration=None` on the `apply_affect` effect primitive; the load check for item-target
  skill effects; applying effects to the target item; the core plugin-effect runner signature and
  `PluginEffectCall.targetItem`; spec §4.3.
- **Out of Scope:** ctx verbs and views (Phase 8); plugin facade item verbs (Phase 10).

### Context Working Set

- **Read / Reference Only:**
  - `script/.../Declarations.kt`: the effect primitive `apply_affect`, `affectEffect`, `dispel`, `sequence`, `skill()`
    and its `effectRefs` helper.
  - `core/.../world/SkillCommands.kt`: the cast action's `resolve`, which loops `for (each in targets)`.
  - `core/.../world/Combat.kt`: `applyEffect`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Effects.kt` (`Effect.ApplyAffect`)
  - `script/src/main/kotlin/gups/script/Declarations.kt` (the primitive, `affectEffect`, the skill check)
  - `script/src/main/kotlin/gups/script/Ctx.kt` (only the `e.duration` argument in `ctx.apply_affect`; see step 2)
  - `core/src/main/kotlin/gups/core/world/Combat.kt`
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt`
  - `core/src/main/kotlin/gups/core/world/World.kt` (`PluginEffectRunner` and the default `pluginEffects` lambda)
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt` (`PluginEffectCall`)
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt` (the runner in `install`)
  - `core/src/test/kotlin/gups/core/world/PluginSeamsTest.kt` (its existing `PluginEffectRunner { w, e, caster, target
    -> ... }` lambda, plus a new case)
  - `script/src/test/kotlin/gups/script/ItemEffectsScriptTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the three API files
- **Do Not Touch:** `Views.kt`, facade files, the `item()` declaration.

### Specification & Decision Record Changes

- **§4.3 Targeting, `item` bullet:** replace "Effect primitives act only on actors, so an item skill does its work in
  `resolve`." with "Its `effect` acts on the item: `apply_affect` attaches an item affect (with `applies`), plugin
  primitives receive the item, and `sequence` may combine them. Any other primitive in an item skill's effect fails the
  load." Keep the sentence after it ("There, and in the cast hooks, the item is `ctx.target`.").
- **§4.3 primitive table, `apply_affect` row:** add `applies="worn"` to the end of the signature, and append to its
  purpose: "On an `item` skill, attaches an item affect; `applies` (`worn` or `carried`) is allowed only there, and
  `duration` may be `None` for a permanent one."
- **CHANGELOG:** bullets for `apply_affect(applies=)`, `duration=None` on item skills, and the item-skill effect rule.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Effects.kt
  data class ApplyAffect(
    val name: String,
    /** Pulses; null only for a permanent item affect. */
    val duration: Int?,
    /* existing fields unchanged */
    /** Set only on an item skill's effect. */
    val applies: AffectScope? = null,   // last
  ) : Effect

  // Declarations.kt: new last parameter
  internal fun affectEffect(/* existing params */, allowPermanent: Boolean = false): Effect.ApplyAffect

  // World.kt
  fun interface PluginEffectRunner {
    fun apply(world: World, effect: Effect.Plugin, caster: Actor, target: Actor?, targetItem: Item?)
  }
  // and the default: PluginEffectRunner { _, _, _, _, _ -> }

  // PluginApi.kt
  data class PluginEffectCall(
    val world: PluginWorld,
    val caster: PluginActor,
    /** Null for `target = "none"` and `target = "item"` skills. */
    val target: PluginActor?,
    val args: Map<String, Any?>,
    /** The target item of an `item` skill, else null. */
    val targetItem: PluginItem? = null,
  )

  // Combat.kt
  /** Applies an item skill's [effect] from [caster] to [item]. */
  fun World.applyItemEffect(effect: Effect, caster: Actor, item: Item)
  ```
- **Algorithmic Steps & Invariants:**
  1. **`affectEffect`:** when `allowPermanent` is true and `duration` is Starlark `None` (`isNone(duration)`), use `d =
     null`; otherwise keep today's `int(...)` parse and the "must be at least 1" check. It never sets `applies`.
  2. **Every reader of `ApplyAffect.duration`.** Run `grep -rn "\.duration" core/src/main script/src/main
     plugin-api/src/main` before editing. Today there are two readers: `applyEffect` in `Combat.kt` (the
     `Effect.ApplyAffect` branch; the `effect.duration?.let` in the `Summon` branch is a different class) and
     `ctx.apply_affect` in `Ctx.kt`. In both, pass `requireNotNull(e.duration) { "apply_affect: duration is required for
     an actor" }`. The skill load check (step 4) guarantees it for skills; `ctx.apply_affect` still calls `affectEffect`
     without `allowPermanent` in this phase.
  3. **The `apply_affect` primitive:** add `Param(name = "applies", named = true, defaultValue = "None", doc = "...")`
     as the **last** parameter, and a matching last Kotlin parameter `applies: Any`. Call `affectEffect(...,
     allowPermanent = true)`. Parse `applies` itself: `None` stays null; otherwise `choice(str(applies,
     "apply_affect(applies)"), "apply_affect(applies)", listOf("worn", "carried"))` mapped to `AffectScope`. Return
     `effect("apply_affect", e.copy(applies = scope))`.
  4. **`skill()` load check**, added right after `proto.effect?.let { effectRefs(it, ctx, thread) }`:
     - Write a private helper `primitiveName(e: Effect): String` returning `damage`, `heal`, `apply_affect`, `dispel`,
       `knockback`, `pull`, `stun`, `summon`, `sequence` or `<plugin id>.<name>`, and a helper that flattens `Sequence`
       recursively into its leaves.
     - When `proto.target == TargetKind.ITEM`, for each leaf: `ApplyAffect` and `Plugin` are allowed; anything else
       fails with `"skill(effect): <primitive> acts on actors; an item skill's effect may use apply_affect and plugin
       primitives"`. An `ApplyAffect` with `"stunned" in flags` fails with `"skill(effect): item affects can't stun"`.
     - Otherwise, an `ApplyAffect` leaf with `applies != null` fails with `"skill(effect): apply_affect(applies) is only
       for item skills"`, and one with `duration == null` fails with `"skill(effect): apply_affect(duration) can be None
       only on an item skill"`.
  5. **`applyItemEffect`:** `when (effect)`: `Sequence` recurses over `effect.effects`; `ApplyAffect` calls
     `applyItemAffect(item, effect.name, effect.duration, effect.applies ?: AffectScope.WORN, effect.statDeltas,
     effect.flags, effect.tickEvery, effect.onTick, effect.onExpire, effect.expireMessage, caster, effect.modifiers,
     effect.senseDeltas)`; `Plugin` calls `pluginEffects.apply(this, effect, caster, null, item)`; anything else is
     `error("${effect::class.simpleName} can't act on an item")` (the load check makes it unreachable).
  6. **`SkillCommands.kt` resolve:** the item effect runs **once**, not once per entry of `targets`. Just before `for
     (each in targets)`, add `if (onItem != null && onItem.id in w.entities) skill.effect?.let { w.applyItemEffect(it,
     actor, onItem) }`. Inside the loop, keep `if (onItem == null) skill.effect?.let { w.applyEffect(it, actor, each,
     skill) }` and update its comment to "An item skill's effect already ran on the item."
  7. **`applyEffect`'s Plugin branch:** `pluginEffects.apply(this, effect, caster, target, null)`.
  8. **The `PluginHost` runner:** its lambda takes a fifth parameter `targetItem` and passes `targetItem =
     targetItem?.let { ItemFacade(it) }` to `PluginEffectCall`. (Phase 10 adds a world argument to `ItemFacade`.)
  9. **`PluginSeamsTest`:** update the existing `PluginEffectRunner { w, e, caster, target -> ... }` lambda to take a
     fifth `_` parameter.

### Test & Documentation Requirements

- Extend `ItemEffectsScriptTest.kt`:
  - an item skill `enchant` with `effect=apply_affect("keen", 50, applies="worn", stat_deltas=stats(dex=1))` cast on a
    carried sword puts `keen` on the sword; dex rises once the sword is worn and drops after 50 pulses;
  - `duration=None` on an item skill makes a permanent affect (`expiresAt == Long.MAX_VALUE`); `duration=None` on an
    `enemy` skill fails to load with the message;
  - `damage(...)`, and `sequence(apply_affect(...), dispel(...))`, on an item skill fail to load with the message naming
    `damage` and `dispel`;
  - `applies="carried"` on an `enemy` skill fails to load with the message.
- `PluginSeamsTest`: for an item skill whose effect is `Effect.Plugin`, the runner receives the item as `targetItem` and
  `target` is null, and it is called once even with `range = SkillRange.ROOM`.

### Exit Conditions

- [] The API files have been regenerated (standing rule 5), then `./gradlew check` and `./gradlew markdownCheck` pass.
- [] `ItemEffectsScriptTest` and `PluginSeamsTest` pass.

---

## Phase 8: `ctx` verbs and views for items

### Objective & Scope

- **In Scope:**
  - `ctx.apply_affect` and `ctx.remove_affect` accept items.
  - New `ctx.add_tag`, `ctx.remove_tag` and `ctx.transform`.
  - Item view fields `affects`, `has_tag` and `variants`; `tags` is effective.
  - `AffectView` on items, and a view for prototype affects.
  - The actor view's `flags` includes item flags.
- **Out of Scope:** plugin facade (Phase 10).

### Context Working Set

- **Read / Reference Only:**
  - `script/.../Ctx.kt`: `applyAffect`, `removeAffect`, the private `actorArg` helper, and the item verbs (`spawn`,
    `move_item`, `destroy`) for how an item argument and a label are taken.
  - `script/.../Views.kt`: `ActorView.flags`, `ItemView`, `AffectView`, the `view(scope, entity)` helper.
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`
  - `script/src/main/kotlin/gups/script/Views.kt`
  - `script/src/main/kotlin/gups/script/ApiModel.kt` (`ScriptApi.VIEW_CLASSES` only)
  - `script/src/test/kotlin/gups/script/ItemEffectsScriptTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the three API files
- **Do Not Touch:** `core/` (all core verbs exist), `plugin-api/`.

### Specification & Decision Record Changes

- **§4.5 Context fields:** after "`affect` (affect hooks)" add "(for an item affect's hooks, `self` is the item)".
- **§4.5 Item view:** add `affects`, `has_tag(tag)` and `variants` to the field list before `in_world`, and append the
  sentence "`tags` are the effective tags."
- **§4.5 Verb table:**
  - in the `Vitals and combat` row, write `apply_affect(target, ...)` and `remove_affect(target, name)` as
    `apply_affect(target, ...)` and `remove_affect(target, name)` "(target: an actor or an item)";
  - add `add_tag(item, tag)`, `remove_tag(item, tag)` and `transform(item, label)` to the end of the Items row.
- **§4.5 Verb semantics:**
  - extend the `apply_affect` bullet: "On an item it takes `applies` (`worn` or `carried`, default `worn`) and allows
    `duration=None` (permanent); `applies` on an actor is an error; item affects can't carry `stunned`."
  - add an Items sub-bullet: "`transform(item, label)` keeps the item's id, location, count, attributes, affects, tag
    changes, name and timers; it refuses a stack into a non-stackable prototype and a container whose contents wouldn't
    fit; an equipped item that no longer fits its slot moves to the inventory; no hooks fire."
  - add an Items sub-bullet: "`add_tag` and `remove_tag` return whether the effective tags changed."
- **CHANGELOG:** bullets for each new verb and view field, and one for "`affect.expires_at` and `affect.remaining` are
  `None` for an affect that never expires, including untimed `equip_affects`".

### Implementation Blueprint

- **`ctx.apply_affect`:**
  - change the `target` param doc to "Actor or item to receive the affect."; change `duration`'s doc to "Duration in
    pulses; None makes an item affect permanent.";
  - add `Param(name = "applies", named = true, defaultValue = "None", doc = "Item affects only: 'worn' or 'carried'.")`
    as the **last** parameter, with a matching last Kotlin parameter;
  - call `affectEffect(..., allowPermanent = true)` once, then dispatch on `target`:
    - an `ItemView`: check `scope.world.item(view.item.id) === view.item` (else raise "apply_affect(target): the item is
      no longer in the world"); parse `applies` as in Phase 7 (default `WORN`); call `world.applyItemAffect(item,
      e.name, e.duration, scope, e.statDeltas, e.flags, e.tickEvery, e.onTick, e.onExpire, e.expireMessage, owner as?
      Actor, e.modifiers, e.senseDeltas)`; return `AffectView(frame, item, it)` or `None`;
    - anything else: `actorArg(target, "apply_affect(target)")`; raise `"apply_affect(applies): only item affects take
      applies"` if `applies` isn't `None`, and `"apply_affect(duration): an actor's affect needs a duration"` if
      `e.duration` is null; then the existing path.
- **`ctx.remove_affect(target, name)`:** an `ItemView` goes to `world.removeItemAffect`, anything else to the existing
  actor path. Update the `target` doc.
- **New verbs**, each written as `verb("name") { ... }` so errors become script errors, and each validating every
  argument before changing anything:
  - `ctx.add_tag(item, tag) -> bool` and `ctx.remove_tag(item, tag) -> bool`: `world.addItemTag` /
    `world.removeItemTag`;
  - `ctx.transform(item, label) -> None`: resolve `label` the way `spawn` resolves an item label (a relative `":label"`
    is resolved against the owner's package), require it to name an item prototype, then `world.transformItem`.
- **`AffectView`:**
  - change `private val host: Actor` to `private val host: Entity`;
  - `host` returns `view(scope, host)`, so it is an actor view or an item view; update its doc to "The actor or item
    carrying the affect.";
  - `expires_at` and `remaining` return `None` when `affect.expiresAt == Long.MAX_VALUE`, and their `@StarlarkMethod`s
    gain `allowReturnNones = true`;
  - add `applies` (`structField`, `allowReturnNones = true`): `"worn"`, `"carried"`, or `None` for an actor's own
    affect;
  - add `origin` (`structField`): `"instance"`. The existing `source` field (who applied it) keeps its meaning, so don't
    reuse that name.
- **`ProtoAffectView`** (new, `@StarlarkBuiltin(name = "proto_affect", doc = "A read-only view of an affect an item's
  prototype declares.")`): fields `name`, `applies`, `flags`, `stat_deltas`, `sense_deltas` and `host` (the item view),
  with the same docs and value types as `AffectView`; `origin` returns `"proto"`; `id`, `expires_at`, `remaining` and
  `source` return `None`. Don't name the builtin `item_affect`: that name is the declaration builtin.
- **`ItemView`:**
  - `affects` (`structField`): a list with one entry per `item.heldAffects()`: a `ProtoAffectView` when `instance ==
    null`, else `AffectView(scope, item, instance)`;
  - `has_tag(tag)` (method): `tag in item.tags`;
  - `variants` (`structField`): a dict of name to label string (`label.v`);
  - `tags` already reads `item.tags`, which is effective after Phase 4. Change its doc to "Effective tags: the
    prototype's plus those added, minus those removed, sorted."
- **`ctx.affect`** (the `affect()` context field in `Ctx.kt`): today it builds `AffectView(frame, owner as? Actor ?:
  return@field Starlark.NONE, a)`, so an item affect's hooks would see `ctx.affect == None`. Build the view whenever the
  owner is an `Actor` or an `Item`: `AffectView(frame, owner as? Actor ?: owner as? Item ?: return@field Starlark.NONE,
  a)`.
- **`ScriptApi.VIEW_CLASSES`** (`ApiModel.kt`) lists every view class explicitly: add `ProtoAffectView::class.java`
  right after `AffectView::class.java`, or it is missing from the API model and the golden files.
- **`ActorView.flags`:** replace the body with `sList(actor.flags.sorted())`, so flags from item affects reach scripts.
  Update its doc to "Flags from traits, affects and item affects, sorted."; update `has_flag`'s doc the same way.

### Test & Documentation Requirements

- Extend `ItemEffectsScriptTest`:
  - `ctx.apply_affect(item, "glow", None, applies="carried", stat_deltas=stats(dex=1))` raises the holder's dex, and
    `ctx.remove_affect(item, "glow")` returns `True` and drops it;
  - `ctx.apply_affect(actor, "x", 5, applies="worn")` raises the script error;
  - `ctx.apply_affect(actor, "x", None)` raises the script error;
  - an item affect's `on_expire` sees `ctx.self` as the item and `ctx.affect.name` as the affect's name;
  - `add_tag` returns `True` then `False` for the same tag, `remove_tag` returns `True` then `False`, and `item.tags`
    and `item.has_tag` follow;
  - `ctx.transform(item, item.variants["uncursed"])` keeps `item.id`, and the holder's stat changes to the new
    prototype's;
  - `item.affects` lists a prototype affect with `origin == "proto"` and `expires_at == None`, then an instance affect
    with `origin == "instance"`;
  - an actor holding an item with a prototype affect flag `glowing` has `"glowing" in actor.flags` and
    `actor.has_flag("glowing")`.
- Doc examples as required by standing rule 5.

### Exit Conditions

- [] The API files have been regenerated (standing rule 5), then `./gradlew check` and `./gradlew markdownCheck` pass.
- [] The new script tests pass.

---

## Phase 9: `REMOVE`, `DROP`, `GIVE` and `PUT` restriction points

### Objective & Scope

- **In Scope:**
  - Four kernel restriction points, in core and the plugin API.
  - Checks in the command handlers only.
  - The wear-swap check.
- **Out of Scope:** the facade (Phase 10); the curse plugin (Phase 11).

### Context Working Set

- **Read / Reference Only:**
  - `core/.../model/Creation.kt` (`RestrictionPoint`, `RestrictionRequest`)
  - `core/.../world/Verbs.kt` (`restrictionRefusal`)
  - `core/.../world/KernelCommands.kt`:
    - `planWear`: its `validate` runs the `WEAR` check; this is the pattern to mirror;
    - `planRemove`;
    - `planDrop` (its `resolve` loop), `GiveAction.resolve` and `PutAction.resolve`;
    - `slotFor`.
  - `plugin-api/.../host/PluginHost.kt` (`addRestriction`, and the restriction wiring in `install`)
  - `plugin-api/.../host/Adapters.kt` (`restrictionAdapter`)
  - `core/src/test/.../RestrictionsTest.kt` (existing wear tests: how a `Restriction` is registered on `f.w`)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Creation.kt`
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`
  - `core/src/test/kotlin/gups/core/world/RestrictionsTest.kt`
  - `core/src/test/kotlin/gups/core/world/PluginSeamsTest.kt` (or `plugin-api/src/test/.../FacadeTest.kt`, wherever an
    existing test reaches a plugin restriction)
  - `docs/SPEC.md`, `docs/PLUGINS.md`
- **Do Not Touch:** `script/`, the facade files.

### Specification & Decision Record Changes

- **§1.6 Restriction row:** "A plugin check that can refuse wearing, removing, dropping, giving or putting an item,
  entering, casting or learning (§4.19)."
- **§4.19:** "at four points" becomes "at eight points". Add these rows to the restriction points table (all `kernel`):

  |  Point   |                                                        Checked                                                         |          Subject           |
  |----------|------------------------------------------------------------------------------------------------------------------------|----------------------------|
  | `remove` | `remove` (and its renames), and every item `wear` or `wield` (and their renames) would swap out, before anything moves | the item and its slot      |
  | `drop`   | `drop`, per stack, before `before_drop` and before the item moves                                                      | the item                   |
  | `give`   | `give`, per stack, before the item moves                                                                               | the item and the receiver  |
  | `put`    | `put`, per stack, before the item moves                                                                                | the item and the container |

  Add the bullet: "`remove` runs wherever a command would take a worn item off, so no command can swap out an item the
  check refuses: `remove` itself, and `wear` or `wield` into an occupied slot, including a slot family whose slots are
  all full, where the item that would be swapped out is checked. A refusal there refuses the whole `wear`, and nothing
  moves. `drop`, `give` and `put` only ever take carried items, so a worn item must pass `remove` before any of them can
  move it. When `drop`, `give` or `put` names several stacks (a count or `all`), each stack is checked on its own, and a
  refused stack is skipped with the refusal message while the others move."

  Add the bullet: "Item restrictions guard commands only, for players and for mobs acting through commands. Script and
  facade verbs (`move_item`, `give`, `destroy`, `transform`) and the engine's own moves (death, decay, the re-check
  after a choice change) don't run them."

- **§4.15:** in the `PluginRestrictionPoint` list ("(`PluginRestrictionPoint.WEAR`, `ENTER`, `CAST`, `LEARN`)"), add
  `REMOVE`, `DROP`, `GIVE` and `PUT`.

- **`PLUGINS.md`**, the `#### Restrictions` section: list the eight points in the sentence "Restrictions intercept
  operations (...)", and add a paragraph with the command-only rule.

### Implementation Blueprint

- **Signatures & Types:**
  - `RestrictionPoint` gains `REMOVE`, `DROP`, `GIVE` and `PUT`, in that order after `LEARN`, each with KDoc. `REMOVE`'s
    KDoc: "Taking a worn item off by command: `remove`, and the item a `wear` or `wield` would swap out of an occupied
    slot (including a full slot family). Trusted moves (verbs, death, the re-check after a choice change, `transform`)
    don't run it."
  - `PluginRestrictionPoint` gains the same four names in the same order. The adapters map points by `name`, so the
    names must match exactly.
  - `RestrictionRequest` needs no new fields: `item` carries the item, `slot` the slot for `REMOVE`, and `target` the
    receiving actor (`GIVE`) or the container item (`PUT`). Update the KDoc of `item` to "Item being equipped, removed,
    dropped, given or put, or null." and of `target` to "Target entity of the skill or action (the receiver of a give,
    the container of a put), or null."
- **Algorithmic Steps & Invariants:**
  1. **`remove`** (`planRemove`'s `validate`): after the existing not-using check, build `RestrictionRequest(point =
     RestrictionPoint.REMOVE, actor = actor, item = item, slot = (item.location as ItemLocation.Equipped).slot)`, call
     `w.restrictionRefusal(RestrictionPoint.REMOVE, req)`, and return the refusal if non-null. A refusal in `validate`
     charges no lag and moves nothing, as `wear`'s does.
  2. **Wear and wield swap** (`planWear`'s `validate`, shared by `wear` and `wield`): after `slotFor` picks `slot` and
     **before** the `WEAR` check, if `actor.equipped(slot)` is non-null, run the `REMOVE` check for that occupant with
     `slot = slot` and return its refusal. `slotFor` returns a free family slot when there is one, so a free `finger_r`
     is used without touching a cursed ring on `finger_l`; only when every family slot is full does it return the first,
     and then that occupant is checked. Do not make `slotFor` skip refused occupants: a refusal must be visible, not
     silently routed around.
  3. **`drop`, `give` and `put`** check in `resolve`, once per stack, because they resolve several stacks when given a
     count or `all`. Do **not** add checks to their `validate`. In each `for ((item, wanted) in matchItems(...))` loop:
     - **`drop`** (`planDrop`): right after `if (item.location != ItemLocation.Carried(actor.id)) continue` and before
       the `BEFORE_DROP` dispatch, run the `DROP` check with `item = item`;
     - **`give`** (`GiveAction.resolve`): right after its location check, before `carryFit`, run the `GIVE` check with
       `item = item, target = target`;
     - **`put`** (`PutAction.resolve`): right after its location check, before the capacity check, run the `PUT` check
       with `item = item, target = box`.
     - On a refusal: `w.send(actor, refusal)` and `continue`, exactly as `drop` handles a `before_drop` veto. These
       commands already return 0 lag.
  4. **Audit every unequip path** before finishing:
     - `grep -n "ItemLocation.Carried(actor.id)" core/src/main/kotlin/gups/core/world/KernelCommands.kt`, and look at
       every `moveItem` whose item may be `Equipped`. Each one reached from a player or mob command must be either
       `planRemove` or the wear/wield swap, which both now run `REMOVE`.
     - Confirm that `drop`, `give` and `put` (including M16's count and `all` forms) only match `actor.inventory`
       through `matchItems`, so an equipped item is never moved and the command reports `NOT_CARRYING` before any
       restriction runs.
  5. **`addRestriction`** in `PluginHost` requires `skills` only for `CAST` and `LEARN`. Leave that set unchanged, so
     the four new points need no feature.

### Test & Documentation Requirements

- `RestrictionsTest.kt`, with a test `Restriction` that refuses with `"Nope."` when `"t" in r.item?.tags.orEmpty()`,
  registered on the point under test as the existing wear tests do. Equipment slots come from `FixedRules`; the
  fixture's ring family is `finger_l` and `finger_r` (see `ItemCommandsTest`).
  - each of `remove`, `drop`, `give` and `put` of a tagged item is refused with `"Nope."`, nothing moves, and
    `p.waitState` stays 0;
  - `wear` of a new helmet into a head slot holding a tagged helmet is refused at `REMOVE`, and nothing moves;
  - `wield` of a dagger while a tagged sword is wielded is refused: the sword stays wielded, the dagger stays in the
    inventory, and `p.waitState` stays 0;
  - the same through a renamed command (`Fixture(rename = mapOf("remove" to "doff"))`, then `doff sword`), and through a
    mob's `f.w.act(mob, "wield dagger")`;
  - slot family: with `finger_l` holding a tagged ring and `finger_r` free, `wear ring` puts the plain ring on
    `finger_r`; with both full and the tagged ring on `finger_l`, `wear ring` is refused and nothing moves;
  - `drop all` with a tagged and an untagged item drops the untagged one and reports `"Nope."` for the other;
  - `drop`, `give` and `put` of an equipped item fail with the not-carrying message and never reach the restriction
    (assert the restriction lambda wasn't called);
  - a mob's `f.w.act(mob, "drop x")` is refused;
  - `f.w.moveItem` and `f.w.destroy` of a tagged item bypass the restriction.
- A plugin seam test: a plugin restriction registered at `PluginRestrictionPoint.DROP` is reached by `drop`.

### Exit Conditions

- [] `./gradlew check` and `./gradlew markdownCheck` pass.
- [] The new restriction tests pass.

---

## Phase 10: Plugin facade for item affects, tags, transform and containment filters

### Objective & Scope

- **In Scope:**
  - `PluginItem` fields: `affects`, effective `tags`, `hasTag`, `variants`, `contents` and `custodian`.
  - `PluginWorld` verbs: `applyItemAffect`, `removeItemAffect`, `addTag`, `removeTag` and `transform`.
  - `addContainmentFilter` and its adapter into `World.containmentFilters`.
  - Listing filters on `plugins.html`.
- **Out of Scope:** the curse plugin.

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/.../host/Adapters.kt` (`restrictionAdapter`: catch, `Diagnostic`, allow)
  - `plugin-api/.../host/PluginHost.kt` (`addRestriction`, `install`, `describe`, `PluginInfo`, `PluginRestrictionInfo`)
  - `plugin-api/.../host/Facade.kt` (`EntityFacade`, `ItemFacade`, `WorldFacade.applyAffect`, `entityOf`)
  - `cli/src/main/kotlin/gups/cli/DocsRenderer.kt` (`PluginRestrictionDocSpec`, the restrictions block of the plugin
    page) and `cli/src/main/kotlin/gups/cli/Commands.kt` (where `PluginInfo` is mapped to the doc spec)
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt`
  - `cli/src/main/kotlin/gups/cli/DocsRenderer.kt`, `cli/src/main/kotlin/gups/cli/Commands.kt`
  - `plugin-api/src/test/kotlin/gups/pluginapi/FacadeTest.kt`,
    `plugin-api/src/test/kotlin/gups/pluginapi/ApiSurfaceTest.kt`
  - `samples/heritage-plugin/src/test/kotlin/gups/sample/heritage/HeritagePluginTest.kt` (its `FakeItem` and `FakeWorld`
    implement `PluginItem` and `PluginWorld`, so they must implement the new members; see step 8)
  - `cli/src/test/kotlin/gups/cli/DocsRendererTest.kt`
  - `docs/SPEC.md`, `docs/PLUGINS.md`
- **Do Not Touch:** `core/` (all core verbs exist), `script/`, and heritage's main sources.

### Specification & Decision Record Changes

- **§4.15 `PluginRegistry` bullet:** add "containment filters (`addContainmentFilter`)" to the list of what plugins can
  register.
- **§4.15 Facade verbs list:** add `applyItemAffect`, `removeItemAffect`, `addTag`, `removeTag` and `transform`.
- **§4.15 capabilities:** add a bullet: "**Containment filters (`addContainmentFilter`):** called while the engine works
  out which carried item affects reach a holder, once per container on the path and affect behind it; returning `true`
  stops that affect at that container. Filters may read only the container's and item's label, kind, tags and affects
  (the state whose changes trigger a recompute). They never see worn or directly carried items. A filter that throws
  records a `plugin` diagnostic and doesn't block."
- **§4.15 Effect primitives bullet:** add "On an `item` skill, `targetItem` is the item and `target` is null."
- **§4.15 Seams in `core` and `script`:** add `World.containmentFilters`, populated by `WorldPluginWiring.install`.
- **§4.16 `plugins.html` row:** add "containment filters (doc)".
- **`PLUGINS.md`:** sections for item affects, tags and `transform` through the facade, `targetItem`, and containment
  filters, each with a short Kotlin example.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Facade.kt (public API)
  interface PluginItem : PluginEntity {
    /* existing members unchanged except tags' KDoc */
    /** Effective tags, sorted: the prototype's plus those added, minus those removed. */
    val tags: List<String>          // a plain val, as today: PluginEntity has no `tags`
    fun hasTag(tag: String): Boolean
    /** Variant names to item labels. */
    val variants: Map<String, String>
    val affects: List<PluginItemAffect>
    /** Items inside this container, ascending id. */
    val contents: List<PluginItem>
    /** The actor whose custody this item ends in, following containers; null on the ground. */
    val custodian: PluginActor?
  }

  data class PluginItemAffect(
    val name: String,
    /** "worn" or "carried". */
    val applies: String,
    val flags: List<String>,
    val statDeltas: Map<String, Int>,
    val senseDeltas: Map<String, Int>,
    /** "proto" or "instance". */
    val origin: String,
    /** Absolute pulse, or null when permanent or from the prototype. */
    val expiresAt: Long?,
  )

  // PluginWorld, next to applyAffect, mirroring it:
  fun applyItemAffect(
    item: PluginItem,
    name: String,
    duration: Int?,
    applies: String = "worn",
    statDeltas: Map<String, Int> = emptyMap(),
    flags: Set<String> = emptySet(),
    senseDeltas: Map<String, Int> = emptyMap(),
  )
  fun removeItemAffect(item: PluginItem, name: String): Boolean
  fun addTag(item: PluginItem, tag: String): Boolean
  fun removeTag(item: PluginItem, tag: String): Boolean
  fun transform(item: PluginItem, label: String)

  // PluginApi.kt
  data class PluginContainmentCall(
    val world: PluginWorld,
    val holder: PluginActor,
    val container: PluginItem,
    val item: PluginItem,
    val affect: PluginItemAffect,
  )
  // PluginRegistry, next to addRestriction:
  fun addContainmentFilter(doc: String, blocks: (PluginContainmentCall) -> Boolean)

  // host/PluginHost.kt
  data class PluginFilterInfo(val doc: String)
  // PluginInfo gains, last: val containmentFilters: List<PluginFilterInfo> = emptyList()
  ```
- **Algorithmic Steps & Invariants:**
  1. **`ItemFacade` needs the world** for `custodian`, `contents` and `affects` (remaining time). Change it to `internal
     class ItemFacade(val item: Item, private val world: World)`, matching `ActorFacade`. Update every call site; find
     them with `grep -rn "ItemFacade(" plugin-api/src/main`. Today there are three in `Adapters.kt` (the rules context
     weapon, the restriction `item`, the restriction `target`), where the world is the adapter's `w`; one in
     `Facades.item` in `host/Facade.kt`, which becomes `ItemFacade(value.item, value.scope.world)` as `Facades.actor`
     does for `ActorView`; and the Phase 7 effect runner in `PluginHost.kt`, where it is the runner's `w`. Equality is
     unchanged: `EntityFacade.equals` compares the entity.
  2. **`ItemFacade` members:** `tags` returns `item.tags.sorted()` (effective); `hasTag(t) = t in item.tags`; `variants
     = item.proto.variants.mapValues { it.value.v }`; `contents = item.contents.map { ItemFacade(it, world) }`;
     `custodian = world.custodian(item)?.let { ActorFacade(it, world) }`; `affects =
     item.heldAffects().map(::toPlugin)`, where a private `toPlugin(ha: HeldAffect)` fills `origin` with `"proto"` when
     `ha.instance == null`, else `"instance"`, and `expiresAt` with `ha.instance?.expiresAt?.takeIf { it !=
     Long.MAX_VALUE }`.
  3. **`WorldFacade` verbs** mirror `applyAffect`:
     - a private `itemOf(i: PluginItem, what: String): Item` like `actorOf`: not an `ItemFacade` → "not an item of this
       world"; `world.item(item.id) !== item` → "is no longer in the world";
     - `applyItemAffect` validates the name and stat names as `applyAffect` does, `require(duration == null || duration

     > = 1)`, maps `applies` with `AffectScope.valueOf(applies.uppercase())` after checking it is `worn` or `carried`,
     > maps `senseDeltas` keys to `Channel`, then calls `world.applyItemAffect`;

     - `transform` parses `label` with `Label(label)` and requires `world.protos.items` to contain it
       ("transform(label): '<label>' is not an item"), then `world.transformItem`;
     - `addTag`, `removeTag` and `removeItemAffect` call the core verbs directly.
  4. **Registration:** `addContainmentFilter` stores `doc to blocks` in a new `filters` list on the registry, like
     `restrictions`. No feature is required.
  5. **`install(world)`:** set `world.containmentFilters` to one adapter per registered filter, in plugin id order and
     then registration order. Each adapter is a `ContainmentFilter { w, holder, container, item, ha -> ... }` that
     builds a `PluginContainmentCall` (with `ItemFacade(..., w)`, `ActorFacade(holder, w)` and `toPlugin(ha)`) and wraps
     the plugin call in `try { ... } catch (e: Throwable) { w.record(Diagnostic(w.pulse, "plugin", ...)); false }`,
     exactly like `restrictionAdapter`.
  6. **`describe()`:** fill `containmentFilters` with `PluginFilterInfo(doc)` per filter. In `cli`, add
     `PluginFilterDocSpec(val doc: String)` next to `PluginRestrictionDocSpec`, a `containmentFilters` list on the
     plugin doc spec, the mapping in `Commands.kt` next to the restrictions mapping, and render a "Containment filters"
     block right after the restrictions block, in the same style.
  7. **`ApiSurfaceTest`:** add `PluginItemAffect::class` and `PluginContainmentCall::class` to its `classes` list.
  8. **Test fakes.** Find every class that implements `PluginItem` or `PluginWorld` outside `host/`: `grep -rnE ":
     (PluginItem|PluginWorld)\b" --include="*.kt" . | grep -v /build/`. Today these are `FakeItem` and `FakeWorld` in
     `HeritagePluginTest.kt`. Give `FakeItem` the new members with harmless values (`hasTag(t) = t in tags`, empty
     `variants`, `affects` and `contents`, `custodian = null`) and give `FakeWorld` the five new verbs as `TODO("not
     used")`, matching how it stubs other verbs it doesn't need.

### Test & Documentation Requirements

- `FacadeTest.kt` (plugin facade tests live there):
  - a test plugin's filter blocks a `carried` affect behind a container tagged `lead` and not elsewhere;
  - a throwing filter records a `plugin` diagnostic and doesn't block;
  - facade `addTag`/`removeTag` on the container refresh the holder: the stat flips as the filter's answer flips;
  - `transform` through the facade swaps the prototype and keeps the id;
  - `PluginItem.affects` shows a prototype entry and an instance entry with the right `origin`;
  - `PluginItem.custodian` is null on the ground, and is the actor for a carried item, a worn item, and an item inside a
    bag inside a worn backpack.
- `DocsRendererTest`: a plugin page lists a filter's doc under "Containment filters".
- `ApiSurfaceTest` passes with the two new classes.

### Exit Conditions

- [] `./gradlew check` and `./gradlew markdownCheck` pass.
- [] The new plugin tests pass.

---

## Phase 11: `samples/curse-plugin`

### Objective & Scope

- **In Scope:** the sample plugin, its README, its tests, Gradle registration and the §4.15 sample list.
- **Out of Scope:** tutorials (Phase 12).

### Context Working Set

- **Read / Reference Only:**
  - `samples/heritage-plugin/`: `build.gradle.kts`, `src/plugin/META-INF/services/gups.pluginapi.GupsPlugin`,
    `HeritagePlugin.kt`, `HeritagePluginTest.kt` (its unit tests with a recording registry, and the test `the built
    heritage jar runs through TestRunner in fixture world`, which writes a temporary world and a `*_test.star` file
    driving players with `p.do("...")`), and the README structure.
  - `template-world/rules/combat.star` (`equipment_slots()`: `neck`, `body`, `finger_l`, `finger_r`, `wield`, `held` and
    others).
  - `settings.gradle.kts`.
- **Files to Modify / Create:**
  - `samples/curse-plugin/build.gradle.kts` (copy heritage's; jar name `curse.jar`, system property
    `gups.sample.curseJar`)
  - `samples/curse-plugin/README.md`
  - `samples/curse-plugin/src/main/kotlin/gups/sample/curse/CursePlugin.kt`
  - `samples/curse-plugin/src/plugin/META-INF/services/gups.pluginapi.GupsPlugin` (one line:
    `gups.sample.curse.CursePlugin`)
  - `samples/curse-plugin/src/test/kotlin/gups/sample/curse/CursePluginTest.kt`
  - `samples/curse-plugin/src/test/kotlin/gups/sample/curse/CurseWorldTest.kt`
  - `settings.gradle.kts` (add `"samples:curse-plugin",` after `"samples:heritage-plugin",`)
  - `docs/SPEC.md`
- **Do Not Touch:** engine modules.

### Specification & Decision Record Changes

- **§4.15 Sample plugins:** add, after the Heritage Plugin bullet, "**Curse Plugin
  ([samples/curse-plugin](../samples/curse-plugin/README.md)):** curses as content. It refuses `remove` of a worn item
  tagged `cursed`, so a cursed item clings and its `worn` affects can't be shed by taking it off; removing a worn
  container that only holds a cursed item is allowed, because nothing leaves the wearer's custody. It refuses `drop`,
  `give` and `put` of an item bearing a curse (tagged `cursed`, or a container holding one that isn't behind a `warded`
  container), `put` only when the destination is outside the actor's custody; adds `curse.lift()` and `curse.bestow()`
  primitives for item skills; and registers a containment filter that stops cursed items' carried affects behind
  containers tagged `warded`."
- **§2.2 source tree:** change "sample plugins: weather-plugin and heritage-plugin, race and class (§4.15)" to "sample
  plugins: weather-plugin, heritage-plugin (race and class) and curse-plugin (§4.15)".

### Implementation Blueprint

- **Plugin:** `id = "curse"`, `pluginApiVersion = PluginApi.VERSION`, `requiredFeatures = emptySet()`, `doc = "Curses as
  content: clinging items, warded containers, lift and bestow."`. Its two primitives are only usable inside a skill, so
  a world needs `skills` to use them, but the restrictions and filter work without it.
  1. **The one predicate.** All checks and the filter share one helper, so they can never disagree:

     Use `bearsCurse` under **Code: `bearsCurse`** at the end of this blueprint.

     A cursed item is always cursed, even when tagged `warded` itself. A `warded` container that isn't cursed never
     bears a curse, whatever it holds, because the ward stops propagation.

  2. **Restrictions** (message `"It won't budge. It must be cursed!"`; mobs are treated like players):

     - `REMOVE`: refuse when `call.item?.hasTag("cursed") == true`. This deliberately checks the item's own tag, not
       `bearsCurse`. Taking a worn item off only moves it to the inventory, so it never leaves the wearer's custody, and
       carried curses inside it keep applying; refusing a worn backpack that merely holds a cursed amulet would gain
       nothing. What `REMOVE` protects is the cursed item's own `worn` affects, which would end the moment it came off:
       the classic "the cursed item clings to you". The engine runs this check for `wear`/`wield` swaps too, so wielding
       a new weapon can't swap out a cursed one. Put this reasoning in the KDoc of the `REMOVE` check and of
       `bearsCurse`, without spec or decision citations.
     - `DROP`, `GIVE`: refuse when `call.item?.let(::bearsCurse) == true`.
     - `PUT`: refuse when `call.item?.let(::bearsCurse) == true` **and** the destination `(call.target as PluginItem)`
       has `custodian?.id != call.actor.id` (this includes `null`, a container on the ground). A move that stays within
       the actor's own custody never sheds the curse, since carried affects follow the custody tree, so it is always
       allowed. Putting the cursed amulet into a `warded` bag in one's own inventory is how a player shuts the curse
       away.
     - Every other case returns null (allow).
  3. **Effect primitives** (`addEffectPrimitive`, no params):
     - `lift`: when `call.targetItem == null`, do nothing; else if `item.variants["uncursed"]` is non-null,
       `call.world.transform(item, it)`; else `call.world.removeTag(item, "cursed")`.
     - `bestow`: when `call.targetItem != null`, `call.world.addTag(item, "cursed")`.
  4. **Containment filter** (`addContainmentFilter`): block when `call.container.hasTag("warded") &&
     call.item.hasTag("cursed")`. This matches `bearsCurse`: a curse stops at the first ward on its path.

#### Code: `bearsCurse`

```kotlin
/** True if [item] carries a curse to whoever holds it: it is cursed, or holds one not shut away by a ward. */
private fun bearsCurse(item: PluginItem): Boolean =
  item.hasTag("cursed") || (!item.hasTag("warded") && item.contents.any(::bearsCurse))
```

### Test & Documentation Requirements

- `CursePluginTest` (unit tests against a recording registry and hand-built facades, as `HeritagePluginTest`'s unit
  tests do), each case its own test. Write a `FakeItem(tags, contents = emptyList(), custodian = null)` implementing
  `PluginItem`, where `hasTag(t) = t in tags`, so a nested bag is `FakeItem(listOf(), contents =
  listOf(FakeItem(listOf("cursed"))))`, and a `FakeActor` whose `id` the `PUT` check compares against `custodian?.id`:
  - `bearsCurse` decision table: plain item (false), cursed item (true), bag holding a cursed item (true), bag in a bag
    holding one (true), `warded` box holding one (false), plain bag holding a `warded` box holding one (false), cursed
    `warded` box (true);
  - `REMOVE`: a worn cursed item is refused; a worn plain item is allowed; a worn backpack holding a cursed amulet is
    allowed; a worn item that is both cursed and `warded` is refused;
  - `DROP` and `GIVE`: refused for the cursed item and for a bag holding it, allowed for a `warded` box holding it;
  - `PUT` within custody: the cursed item into a bag in the actor's inventory, into a bag inside a worn backpack, and
    into a `warded` box in the inventory are all allowed;
  - `PUT` out of custody: the cursed item into a container on the ground, and into a container another actor carries,
    are refused; a bag holding the cursed item into a ground chest is refused; a `warded` box holding the cursed item
    into a ground chest is allowed;
  - `lift` with and without a variant; `bestow`; the filter decision table.
- `CurseWorldTest`, end to end through the built jar, modelled on `the built heritage jar runs through TestRunner in
  fixture world`: write a temporary world with the template `MODULE.gups` (with `curse` pinned), the template `rules/`,
  a `skills` package for the two skills below, and a zone `zones/curse_zone` with `content.star` and `curse_test.star`.
  Each numbered scenario below is its own `def test_...(t):` in `curse_test.star`; check stats with `p.dex` and output
  with `t.contains(p.do("..."), "...")`.
  1. Content:
     - `amulet_of_woe` (`wear="neck"`, `tags=["cursed"]`, `affects=[item_affect("misfortune", applies="carried",
       stat_deltas=stats(dex=-2))]`, `variants={"uncursed": ":amulet_of_warding"}`);
     - `amulet_of_warding` (`wear="neck"`, `affects=[item_affect("warding", applies="worn",
       stat_deltas=stats(dex=1))]`);
     - `small_bag` (`container=container()`) and `backpack` (`wear="held"`, `container=container()`);
     - `lead_box` (`container=container()`, `tags=["warded"]`) and `chest` (`container=container()`, spawned on the
       ground);
     - `cursed_sword` (`wear="wield"`, `tags=["cursed"]`, `affects=[item_affect("drain", stat_deltas=stats(dex=-1))]`)
       and `dagger` (`wear="wield"`);
     - `cursed_ring` (`wear="finger"`, `tags=["cursed"]`) and `plain_ring` (`wear="finger"`);
     - a mob `priest` in the starting room;
     - skills `remove_curse` (`target="item"`, `effect=curse.lift()`) and `bestow_curse` (`target="item"`,
       `effect=curse.bestow()`), given to the player with `spawn_player(..., skills={...})`.
  2. **Propagation, by commands:** with the amulet, bag and backpack in the inventory, `put amulet bag` and then `put
     bag backpack` are allowed, and `p.dex` is 2 below its base at each step.
  3. **Can't shed it:** `drop amulet`, `drop bag`, `drop backpack` and `give backpack priest` are refused with the
     message; `put backpack chest` is refused. `p.dex` stays 2 below base.
  4. **Warded box:** `put amulet box` (the box in the inventory) is allowed and restores `p.dex`. Then `drop box` is
     allowed. In separate tests, `put box chest` and `give box priest` are allowed instead. Once the box is out of the
     player's custody, `p.dex` is at base and the player can walk away.
  5. **Ward inside a bag:** with the amulet in the lead box inside the backpack, `drop backpack` is allowed.
  6. **Removing a worn bag isn't shedding:** with the amulet in the backpack and the backpack worn, `remove backpack` is
     allowed, the backpack lands in the inventory, and `p.dex` stays 2 below base; `drop backpack` is then refused.
  7. **No swap loophole:** with `cursed_sword` wielded, `wield dagger` is refused: the sword stays wielded, the dagger
     stays in the inventory, and `p.dex` stays 1 below base. `remove sword` is refused too. After `cast remove_curse
     sword` (no variant, so only the tag goes), `wield dagger` works and the sword moves to the inventory.
  8. **Clinging ring in a slot family:** with `cursed_ring` on `finger_l` and `finger_r` free, `wear ring` (naming the
     plain ring by a keyword only it has) puts it on `finger_r`. With both fingers full and the cursed ring on
     `finger_l`, wearing a third ring is refused.
  9. **Lift:** `cast remove_curse amulet` on the worn amulet turns it into `amulet_of_warding`: same item id, still
     worn, `p.dex` 1 above base. Then `remove amulet` works.
  10. **Bestow:** `cast bestow_curse dagger` on a carried dagger makes `drop dagger` refused.
- README following heritage's structure: purpose, layout, build and pin, extension points used.

### Exit Conditions

- [] `./gradlew check` passes, including the new module's tests.
- [] `./gradlew markdownCheck` passes.

---

## Phase 12: Tutorials and documentation

### Objective & Scope

- **In Scope:**
  - The curse plugin tutorial series.
  - The template-world items tutorial section.
  - `PLUGINS.md` cross-links.
- **Out of Scope:** code changes (only fix doc examples if a doc test runs them).

### Context Working Set

- **Read / Reference Only:**
  - `samples/heritage-plugin/docs/tutorials/` (style, and the README roadmap)
  - `template-world/docs/tutorials/03_items_and_equipment.md`
  - `samples/curse-plugin/` (Phase 11)
- **Files to Modify / Create:**
  - `samples/curse-plugin/docs/tutorials/README.md`
  - `samples/curse-plugin/docs/tutorials/01_item_affects_and_custody.md`
  - `samples/curse-plugin/docs/tutorials/02_item_restriction_points.md`
  - `samples/curse-plugin/docs/tutorials/03_item_effects_transform_and_variants.md`
  - `samples/curse-plugin/docs/tutorials/04_containment_filters.md`
  - `samples/curse-plugin/docs/tutorials/05_testing_the_curse_plugin.md`
  - `template-world/docs/tutorials/03_items_and_equipment.md`
  - `samples/curse-plugin/README.md` (link the tutorials), `docs/PLUGINS.md` (link the sample)
- **Do Not Touch:** Kotlin sources.

### Specification & Decision Record Changes

- None.

### Implementation Blueprint

- **Tutorial 01:**
  - `applies="worn"` versus `"carried"`;
  - the propagation walk-through with an uncursed `lucky_charm` (`applies="carried"`, dex +1): `put charm bag`, `put bag
    backpack` keep the bonus, and `drop bag` ends it, showing that no propagation code is needed;
  - the same walk-through with the cursed amulet: `put` between the player's own bags is allowed and the curse follows,
    but `drop bag` and `put backpack chest` are refused, because the bag now bears the curse;
  - why containers aren't an escape hatch unless a plugin adds one.
- **02:** the four new points; the command-only rule and why verbs are trusted (a priest's `on_give` can take the
  amulet). Why `REMOVE` checks only the worn item's own `cursed` tag while `DROP`, `GIVE` and `PUT` use `bearsCurse`
  (the worn backpack example), and how the engine runs `REMOVE` for `wear`/`wield` swaps, so the cursed sword can't be
  swapped away.
- **03:** `curse.lift()` with `variants` and `transform`; why the clean version is a separate prototype (for example, a
  cursed amulet with dex −2 lifts into a blessed one with dex +1, not into the same prototype minus a tag); `bestow`.
- **04:** the `warded` filter; the purity contract (read only label, kind, tags, affects). The `bearsCurse` helper
  shared by the restrictions and the filter, so "the ward stops the curse" holds for both stats and drops. The worked
  example: `put amulet box`, then `drop box`, walking away free.
- **05:** the test structure from `CurseWorldTest`.
- **Template-world `03_items_and_equipment.md`:** add a section covering `item_affect` and prototype `affects`,
  `ctx.apply_affect(item, ...)` and `remove_affect`, `add_tag`/`remove_tag`, and `variants`/`transform`, with an
  `enchant_weapon` item skill example (`apply_affect("keen", 600, applies="worn", stat_deltas=stats(dex=1))`). Every
  snippet must load against the template schema: its stats are `str`, `dex`, `con`, `int`, `wis` and `armor`, and its
  slots are those of `template-world/rules/combat.star`.

### Test & Documentation Requirements

- Markdown only; keep snippets consistent with the Phase 11 tests.

### Exit Conditions

- [] `./gradlew markdownFormat` then `./gradlew markdownCheck` pass.
- [] `./gradlew check` passes.

---

## Phase 13: Finalization & Backlog Update

- **§7.4:** add the row "**M17** | Effects on items: item affects with `worn`/`carried` scope through the custody tree,
  the per-actor held-effects cache, per-instance item tags, `variants` and `transform`, item-target skill effects, the
  `remove`, `drop`, `give` and `put` restriction points, plugin containment filters and item facade verbs,
  `samples/curse-plugin` (§3.3, §4.2, §4.3, §4.5, §4.6, §4.15, §4.19)." after the M16 row.
- **`docs/DESIGN_NEEDED.md`:** follow the convention M16 used. Rename the heading `### Effects on items` to `### Effects
  on items (COMPLETED — M17)` and leave its text. Leave `### Enchantments on rooms` open.
- Re-read the whole `docs/CHANGELOG.md` `### Effects on items` subsection against the final API, then regenerate the
  three API files once more (standing rule 5).
- Run `./gradlew markdownFormat`, `./gradlew markdownCheck` and `./gradlew check`; all must pass.
- Confirm that `grep -rnE "§|SPEC\.md|SPEC §|\bD-[0-9]{3}\b" --include="*.kt" core script persist plugin-api samples cli
  | grep -v /build/` prints nothing, so no Kotlin file contains a spec or decision citation. (A plain `SPEC` search is
  useless: it matches `ITEM_SPEC` and the other declaration specs.)
