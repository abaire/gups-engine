# Milestone 17: Effects on Items

**Prerequisite:** [M16](MILESTONE_16.md) (item quantities) must be fully implemented first. This milestone assumes its
end state: `World.splitItem` and `ctx.split` no longer exist, and `World.moveItem(item, to, count)` and
`World.destroy(item, count)` move or destroy part of a stack, the moved part being a new `Item` that copies the source's
attributes, name override and decay deadline. Where line numbers below differ after M16, find the symbol by name.

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
    item-tag changes, reloads and restores. Hot readers (`stat`, `sense`, `flags`, the modifier chain) read the cache.
    `updateEncumbrance` becomes internal and is called only by `refreshHoldings` and the world tick.
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
    (players and mobs via `act`). Script verbs, facade verbs and engine-internal moves are trusted and bypass them,
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
      `contents`.
    - `@stat` on an item lists its affects.
    - GMCP `Char.Status` and `score` keep listing actor affects only.
- **Specification Impact:**
  - §1.6 (Restriction glossary row)
  - §3.3 (Item, Affect)
  - §3.10 (kernel row)
  - §4.2 (`item()` arguments, declaration rules)
  - §4.3 (targeting, effect primitives)
  - §4.5 (context fields note, item view, verb table and semantics)
  - §4.6 (chain order, encumbrance paragraph, equipment paragraph)
  - §4.15 (registry, facade, seams, sample plugins)
  - §4.19 (restriction points table)
  - §5.9 (wake)
  - §7.4 (M17 row)
- **Decision Record Updates:** None. The old D-123 ("effect primitives act on actors") is no longer in `DECISIONS.md`;
  it lives as §4.3 text, which Phase 7 rewrites. Do not add `DECISIONS.md` entries: the maintainer approved this design,
  so spec text is edited directly.

### Standing rules for every phase

<guardrails>

1. `./gradlew check` must pass at the end of every phase (all Kotlin warnings are errors, the module-graph check
   included). Run every `./gradlew` command outside the sandbox (`BypassSandbox: true`).
2. Whenever a Markdown file changes, run `./gradlew markdownFormat`, then `./gradlew markdownCheck`.
3. Never cite spec sections or decision IDs (`§4.3`, `D-123`, `SPEC`) in Kotlin comments or KDoc. State behavior
   directly.
4. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. `api_version` 1 is unreleased, so additions and changes
   (such as `apply_affect` gaining `applies` and an optional `duration`) both join version 1. A failing `ApiDiffTest`
   means the baseline needs regenerating (rule 5), never a version bump.
5. Every phase that adds or changes script-facing symbols (builtins, verbs, view fields, declaration arguments):
   - add bullets under `## api_version 1 (unreleased)` in `docs/CHANGELOG.md`, in a new `### Effects on items`
     subsection placed above `### Experience and levels`;
   - new symbols need no `@ApiSince`: they join `api_version` 1, the current version;
   - if a test demands an example per symbol, add one in `script/.../DocExamples.kt` mirroring the `apply_affect` entry;
   - **before** the phase's final `./gradlew check`, regenerate the golden and baseline files: `./gradlew :cli:test
     -Dgups.updateGolden=true`, then `./gradlew :cli:installDist` and `cli/build/install/gups/bin/gups docs
     --update-baseline` from the engine checkout. `ApiDiffTest` must then pass.
6. No database migrations: the engine is unreleased. New persisted fields get defaults.
7. Never read or touch `local-world/`.

</guardrails>

---

## Phase 1: `HeldEffects` cache and `refreshHoldings`

### Objective & Scope

- **In Scope:** A refactor with no behavior change.
  - Introduce the per-actor `HeldEffects` cache holding what worn items contribute today (prototype `statDeltas`,
    `senseDeltas`, `modifiers`).
  - Introduce `World.refreshHoldings`, and route all custody call sites through it.
  - Make readers use the cache.
  - Add the empty `containmentFilters` seam.
- **Out of Scope:** item affects, tags, persistence, scripts and plugins.

### Context Working Set

- **Read / Reference Only:**
  - `core/.../model/Entities.kt`: `Actor.stat`, `sense`, `flags`, `hasFlag`, `traitStatDeltas` (the cached-trait
    pattern), around lines 340–510.
  - `core/.../world/Rules.kt`: `chainSteps`, `equipmentSlots`.
  - `core/.../world/Reconcile.kt`: `applyPrototypes`.
  - `core/.../world/Restore.kt`: `restorePlayer`, `restoreMob`, `restoreItem`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Holdings.kt` (new)
  - `core/src/main/kotlin/gups/core/model/Entities.kt`
  - `core/src/main/kotlin/gups/core/world/Items.kt`
  - `core/src/main/kotlin/gups/core/world/Rules.kt`
  - `core/src/main/kotlin/gups/core/world/World.kt` (the `containmentFilters` field, and the world-tick call at about
    line 695 stays `updateEncumbrance`)
  - Call sites in `Reconcile.kt` and in the persist restore path (see step 6)
  - `core/src/test/kotlin/gups/core/world/HoldingsTest.kt` (new)
- **Do Not Touch:** `script/`, `persist/` (except a `refreshHoldings` call if restore lives there), `plugin-api/`, docs.

### Specification & Decision Record Changes

- None (no behavior change).

### Implementation Blueprint

- **Signatures & Types** (in `Holdings.kt` unless noted):

  ```kotlin
  /** What an actor's items contribute to it, recomputed by [refreshHoldings]. */
  class HeldEffects(
    val statDeltas: Map<String, Int> = emptyMap(),
    val senseDeltas: Map<Channel, Int> = emptyMap(),
    val flags: Set<String> = emptySet(),
    /** Chain-step label to the modifiers it contributes, in modifier-chain order. */
    val modifierSteps: List<Pair<String, Modifiers>> = emptyList(),
  ) { companion object { val EMPTY = HeldEffects() } }

  // Entities.kt, on Actor:
  var heldEffects: HeldEffects = HeldEffects.EMPTY
    internal set

  /** Recomputes everything [a] derives from what it holds: item contributions, then encumbrance. */
  fun World.refreshHoldings(a: Actor)

  /** Rebuilds [Actor.heldEffects] from [a]'s custody tree. */
  internal fun World.refreshHeld(a: Actor)

  /** A plugin check that can stop an item affect from reaching its holder through a container. */
  fun interface ContainmentFilter {
    fun blocks(world: World, holder: Actor, container: Item, item: Item, affect: HeldAffect): Boolean
  }
  // World.kt:
  var containmentFilters: List<ContainmentFilter> = emptyList()
  ```

  Declare `HeldAffect` in `Holdings.kt` now (Phase 2 starts producing it):

  ```kotlin
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
  enum class AffectScope { WORN, CARRIED }
  ```

  Place `AffectScope` in `core/.../model/Entities.kt` next to `Affect`, since Phase 2 uses it on `Affect`.

- **Algorithmic Steps & Invariants:**

  1. `refreshHeld(a)`:
     - Iterate equipped items in `equipmentSlots()` order, then any other slots by name, exactly as `chainSteps` does
       today.
     - For each item, add `item.proto.statDeltas` and `item.proto.senseDeltas` into the maps, and append
       `"item:${item.protoLabel.name}" to item.proto.modifiers` to `modifierSteps` when the modifiers are non-empty.
     - `flags` stays empty in this phase.
     - Assign `a.heldEffects`.
  2. `refreshHoldings(a)`: call `refreshHeld(a)`, then `updateEncumbrance(a)`.
     - Make `updateEncumbrance` `internal`. Its only callers must be `refreshHoldings` and the world-tick loop in
       `World.kt`.
  3. Replace every `updateEncumbrance` call in `Items.kt` with `refreshHoldings`: `spawnItem`, both sides of `moveItem`
     (whole and partial-count paths), and `destroy` (whole and partial-count paths).
     - `decay` and `forgetItemTree` must end up calling it for any custodian too. Check that they go through `destroy`
       or `moveItem`; if not, add the call.
  4. `Actor.stat(name)`: replace `equipmentIndex.values.sumOf { it.proto.statDeltas[name] ?: 0 }` with
     `(heldEffects.statDeltas[name] ?: 0)`. Do the same in `sense(channel)`.
  5. `Actor.flags` and `hasFlag`: also include `heldEffects.flags`. Update the `hasFlag` KDoc.
  6. `chainSteps` in `Rules.kt`: replace the equipped-item loop with `for ((label, mods) in
     actor.heldEffects.modifierSteps) mods[point]?.let { out += ChainStep.Script(label, it) }`. Keep its position (after
     affects).
  7. **Refresh triggers outside custody:**
     - at the end of `applyPrototypes` (reload), call `refreshHoldings` for every actor in `actorIndex`;
     - after a player or mob and its items are restored, call it once per restored actor. Find the place where restore
       finishes placing items (the persist load path that calls `restoreItem`) and call it there, once all items are
       placed.
  8. **GMCP:** `Char.Affects` in `Gmcp.kt` reads `p.affects` (actor affects only) and must keep doing so; do not switch
     it to `flags` or `heldEffects`, so item enchantments stay hidden from clients.
  9. **Invariant:** `heldEffects` always equals what a fresh `refreshHeld` would compute. Every mutation of equipment,
     inventory, container contents or item prototypes is followed by a refresh of the affected custodian.

### Test & Documentation Requirements

- `HoldingsTest.kt`:
  - wearing an item with `statDeltas` raises `stat()` and removing it restores it;
  - `sense()` follows a worn item's `senseDeltas`;
  - a worn item's modifiers appear in the chain in slot order, as before;
  - destroying a worn item clears its contribution;
  - a reload that changes a worn item's prototype `statDeltas` updates `stat()`.
- Existing `ModifierChainTest`, `ItemCommandsTest`, `TraitEffectsTest` and `PerceptionTest` must pass unchanged.
- KDoc on `HeldEffects`, `refreshHoldings` and `ContainmentFilter`.

### Exit Conditions

- [] `./gradlew check` passes.
- [] `grep -rn updateEncumbrance core/src/main` shows only its definition, `refreshHoldings` and the world-tick loop.
- [] `HoldingsTest` passes.

---

## Phase 2: Item affect model and propagation

### Objective & Scope

- **In Scope:**
  - `Affect.applies`, `Item.affects` (instance), `ItemProto.affects` (prototype, as `ItemAffect`).
  - `refreshHeld` collects reaching item affects through the custody tree, applying containment filters.
  - Spec §3.3 and §4.6 text.
- **Out of Scope:** applying, expiring or ticking instance affects (Phase 3); persistence; scripts; plugins.

### Context Working Set

- **Read / Reference Only:**
  - `core/.../model/Entities.kt` (`Affect`, `Item`, `ItemLocation`)
  - `core/.../world/Holdings.kt` (Phase 1)
  - `core/.../world/Rules.kt`
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Entities.kt`
  - `core/src/main/kotlin/gups/core/model/Protos.kt`
  - `core/src/main/kotlin/gups/core/world/Holdings.kt`
  - `core/src/test/kotlin/gups/core/world/HoldingsTest.kt`
  - `docs/SPEC.md`
- **Do Not Touch:** `script/`, `persist/`, `plugin-api/`.

### Specification & Decision Record Changes

- **§3.3, Item bullet list:** add **Affects and tags**: "per-instance `affects` (timed or permanent) and per-instance
  tag changes over the prototype's `tags`; prototype `affects` are read live from the prototype. Each item affect has an
  `applies` scope: `worn` reaches the holder while the item is equipped, `carried` while it is anywhere in the holder's
  custody, including nested containers, unless a plugin containment filter blocks it (§4.15)." (Phase 4 adds the tag and
  variant wording; write only the affects half now.)
- **§3.3, Affect bullet list:** add "**Scope:** item affects carry `applies` (`worn` or `carried`); an item affect's
  stat and sense deltas, flags and modifiers count for the item's holder while it reaches it."
- **§4.6, Chain order:** replace "then its worn items (in `equipment_slots()` order, then any other slots by name). Worn
  items contribute their `item(modifiers=)`;" with "then its items: each worn item in `equipment_slots()` order (then
  any other slots by name) contributes its `item(modifiers=)` and then the modifiers of its item affects (prototype
  affects in declaration order, then instance affects by id); then every other held item's `carried` affects, depth
  first through containers in item-id order;"

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Entities.kt
  class Affect(/* existing */ ..., val applies: AffectScope? = null) // null for actor affects; add as the last param
  // on Item:
  /** Instance affects on this item, in application order. */
  val affects: MutableList<Affect> = mutableListOf()

  // Protos.kt
  /** An affect a prototype declares; read live, never expires, can't be removed from one instance. */
  data class ItemAffect(
    val name: String,
    val applies: AffectScope = AffectScope.WORN,
    val statDeltas: Map<String, Int> = emptyMap(),
    val senseDeltas: Map<Channel, Int> = emptyMap(),
    val modifiers: Modifiers = emptyMap(),
    val flags: Set<String> = emptySet(),
  )
  // ItemProto: val affects: List<ItemAffect> = emptyList()   (after equipAffects)

  // Holdings.kt
  /** Prototype affects in declaration order, then instance affects by id. */
  fun Item.heldAffects(): List<HeldAffect>
  ```
- **Algorithmic Steps & Invariants:**
  1. `Item.heldAffects()`: map each `proto.affects` entry to a `HeldAffect(instance = null)`, then each `affects` entry
     (sorted by id) to a `HeldAffect(instance = it)`. An instance affect with `applies == null` is treated as `WORN`.
  2. `refreshHeld(a)`, extending Phase 1:
     - **Worn pass** (same slot order): for each equipped item, add prototype deltas and modifiers as before. Then, for
       each `HeldAffect` of that item (both scopes reach a worn item's holder), add its deltas and flags, and append
       `"item:${item.protoLabel.name}:${ha.name}" to ha.modifiers` when non-empty.
     - **Carried pass:** for each inventory item in id order, call `visit(item, path = emptyList())`. Also, for each
       equipped item that is a container, visit its contents with `path = listOf(equippedItem)`. The equipped item's own
       affects were already counted.
     - **`visit(item, path)`:**
       - for each `HeldAffect` with `applies == CARRIED`, skip it if any container `c` in `path` makes any
         `containmentFilters` entry return `blocks(world, a, c, item, ha) == true`; otherwise add it;
       - then visit each item in `item.contentsIndex.values` (id order) with `path + item`.
     - Worn-scope affects of carried, non-equipped items never reach the holder.
  3. Filters run only during `refreshHeld`, never on reads. A filter that throws counts as not blocking: wrap each call
     in `guarded(...)`, or let the plugin adapter catch it (Phase 10 adapter catches; the core still guards).
  4. **Flags:** an item affect may not carry `stunned`. `refreshHeld` ignores it, and Phase 3 (apply time) and Phase 6
     (load time) reject it. Item affects never trigger `disabledChanged`.

### Test & Documentation Requirements

- Extend `HoldingsTest.kt`, building `ItemProto(affects = ...)` directly:
  - a worn-scope prototype affect counts while worn and not while only carried;
  - a carried-scope affect counts in the inventory, when worn, inside a bag in the inventory, inside a bag inside a
    backpack, and inside a worn backpack;
  - moving the inner bag to the ground removes the contribution, and so does destroying the backpack;
  - an item affect's flag shows in `hasFlag`;
  - a test `ContainmentFilter` that blocks on a container tag (prototype tag for now) blocks only affects behind that
    container, and never a directly carried or worn item;
  - a throwing filter does not block;
  - modifier chain order matches the §4.6 wording.
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
  - `core/.../world/Combat.kt` (`applyAffect`, `endAffect`, `removeAffects`)
  - `core/.../world/World.kt` (`runTimers`)
  - `core/.../world/Bubble.kt` (`wake`, `isSimulated`)
  - `core/.../world/Hooks.kt` (`invokeRef(owner: Entity, ...)`)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Combat.kt`
  - `core/src/main/kotlin/gups/core/world/World.kt`
  - `core/src/main/kotlin/gups/core/world/Bubble.kt`
  - `core/src/main/kotlin/gups/core/world/Items.kt` (`stack`, and the partial-count branch of `moveItem`)
  - `core/src/test/kotlin/gups/core/world/ItemAffectsTest.kt` (new)
  - `docs/SPEC.md`
- **Do Not Touch:** `script/`, `persist/`, `plugin-api/`.

### Specification & Decision Record Changes

- **§3.3, Affect, Replacement rule:** append "A permanent item affect counts as expiring latest."
- **§5.9 (wake):** where waking expires mobs' run-out affects, add "and the run-out affects of items in the room (ground
  items and their contents), in expiry order".
- **§4.5, Verb semantics:** in the Items bullets, add "moving part of a stack copies its instance affects and tag
  changes onto the moved part" and "an item with instance affects or tag changes never merges into a stack".

### Implementation Blueprint

- **Signatures & Types** (`Combat.kt`):

  ```kotlin
  /** Instance affect on [item]; [duration] null is permanent. Same replacement rule as actor affects. */
  fun World.applyItemAffect(
    item: Item, name: String, duration: Int?, applies: AffectScope = AffectScope.WORN,
    statDeltas: Map<String, Int> = emptyMap(), flags: Set<String> = emptySet(),
    tickEvery: Int? = null, onTick: HookRef? = null, onExpire: HookRef? = null,
    expireMessage: String = "", source: Actor? = null, modifiers: Modifiers = emptyMap(),
    senseDeltas: Map<Channel, Int> = emptyMap(),
  ): Affect?
  fun World.removeItemAffect(item: Item, name: String): Boolean
  internal fun World.expireItemAffect(item: Item, affect: Affect)
  ```
- **Algorithmic Steps & Invariants:**
  1. **`applyItemAffect`:**
     - mirror `applyAffect`: return null if `item.id !in entities`; require `duration == null || duration >= 1`; require
       `"stunned" !in flags` ("item affects can't stun");
     - `expiresAt = duration?.let { pulse + it } ?: Long.MAX_VALUE`;
     - keep an existing same-name affect when its `expiresAt >= expiresAt`; otherwise replace it, with id from
       `allocateHandle()` and `applies` set;
     - mark the item dirty, then `custodian(item)?.let { refreshHoldings(it) }`.
  2. **`removeItemAffect` and `expireItemAffect`:**
     - share a private `endItemAffect(item, affect, reason)` mirroring `endAffect`: remove the affect, mark the item
       dirty, refresh the custodian;
     - send `expireMessage` to the custodian when one exists and the affect expired (else drop it);
     - invoke `onExpire` with `invokeRef(item, ref, HookContext(affect = affect, affectEndReason = why))`.
     - There's no summon handling.
  3. **`runTimers`:**
     - inside the existing `entities.values` loop, when `e is Item && e.affects.isNotEmpty() && isSimulated(e)`, add
       `Due` entries for expiry (`expiresAt <= pulse`) and for `onTick` (same modulo rule as actor affects, owner `e`);
     - all `Due` entries stay sorted together by `(due, id)`.
  4. **`wake(room)`:** after the mob affect expiry loop, collect the room's sleeping items and their nested contents,
     gather affects with `expiresAt <= pulse`, sort by `(expiresAt, id)` and call `expireItemAffect` for each, inside
     `guarded`.
  5. **`stack`:** add `it.affects.isEmpty() && item.affects.isEmpty()` to the merge conditions, and an early return when
     `item.affects.isNotEmpty()`. Phase 4 adds the tag-change condition.
  6. **Partial-count `moveItem`:** where it copies `customAttributes` onto `part`, also copy each instance affect onto
     `part` as a new `Affect`. Give it a new id from `allocateHandle()`, keep the same `appliedAt`, `expiresAt` and all
     fields. The refresh of both holders already happens in that branch.

### Test & Documentation Requirements

- `ItemAffectsTest.kt`:
  - a timed carried affect expires at its pulse, drops the holder's stat and fires `on_expire` with `ctx.self` the item
    (use a test `ScriptHost` stub as other core tests do, or assert through a recorded `invokeRef`);
  - a permanent affect never expires;
  - the replacement rule, including permanent beating timed;
  - `on_tick` cadence;
  - an affect on a ground item in a dormant room expires on wake, not before;
  - `stunned` is refused;
  - stacking refuses to merge an item with affects;
  - moving 3 of a 10-arrow stack with an affect copies the affect onto the moved part, with a new id.
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
  - `core/.../world/Reconcile.kt` (`item.proto = p`, ~line 94, for how a proto swap is done)
  - `core/.../world/Combat.kt` (`applyEquipAffects`, `removeEquipAffects`)
  - `core/.../world/Items.kt` (`checkTarget`, `custodian`, `ItemEvent`)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Entities.kt`
  - `core/src/main/kotlin/gups/core/model/Protos.kt`
  - `core/src/main/kotlin/gups/core/world/Verbs.kt`
  - `core/src/main/kotlin/gups/core/world/Items.kt`
  - `core/src/test/kotlin/gups/core/world/ItemTagsTest.kt` (new)
  - `docs/SPEC.md`
- **Do Not Touch:** `script/`, `persist/`, `plugin-api/`.

### Specification & Decision Record Changes

- **§3.3, Item:** complete the Phase 2 bullet with "Effective `tags` are the prototype's plus those added, minus those
  removed, so a reload's prototype tag changes still reach instances. `variants` maps free-form names to related item
  prototypes; `transform` swaps an instance's prototype in place."
- **§4.2, Tags bullet:** replace "They are part of the prototype, so a reload changes them for every instance." with
  "They are part of the prototype, so a reload changes them for every instance; items can also gain or lose tags per
  instance (§4.5)."

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Entities.kt, Item:
  val addedTags: MutableSet<String> = sortedSetOf()
  val removedTags: MutableSet<String> = sortedSetOf()
  val tags: Set<String> get() = (proto.tags + addedTags) - removedTags   // replaces the proto-only getter
  // Protos.kt, ItemProto:
  /** Free-form variant names to related item prototypes; the engine gives the names no meaning. */
  val variants: Map<String, Label> = emptyMap()

  // Verbs.kt
  fun World.addItemTag(item: Item, tag: String): Boolean
  fun World.removeItemTag(item: Item, tag: String): Boolean
  /** Swaps [item]'s prototype for [label] in place. */
  fun World.transformItem(item: Item, label: Label)
  ```
- **Algorithmic Steps & Invariants:**
  1. **Tag validation.** Both tag verbs require `tag` to match `[a-z][a-z0-9_]*` (reuse the tag regex the declarations
     use, or define one in `core`). They return false when the effective tags already (or no longer) include it.
  2. **`addItemTag`:**
     - if `tag in removedTags`, remove it from there;
     - else if `tag !in proto.tags`, add it to `addedTags`.
  3. **`removeItemTag`:** mirror `addItemTag`. In both verbs, mark the item dirty and refresh the custodian when
     anything changed, since containment filters may read tags.
  4. **`transformItem`:**
     - refuse with `require` when any of these hold: the label isn't an item prototype; `count > 1` and the new proto
       isn't stackable; `contentsIndex` isn't empty and the new proto has no container;
     - if equipped, call `removeEquipAffects(wearer, item)`;
     - set `item.proto = newProto` and `item.protoVersion = newProto.version`;
     - if equipped and the new proto has no slot, or `slotFor` wouldn't accept the current slot, `moveItem` it to the
       wearer's inventory; otherwise `applyEquipAffects(wearer, item)`;
     - if the item is a container, rebuild its `ContainerState` only if the code requires it. Today `container` is a
       `val` built from the proto, so if the old and new container fields differ, make `container` a `var` and rebuild
       it, keeping `state`;
     - mark the item dirty;
     - when the custodian is a non-debug `Player`, append `ItemEvent(item.id, pulse, item.location, item.location,
       "transform")`;
     - finally `custodian(item)?.let { refreshHoldings(it) }`. No hooks fire.
  5. **`stack`:** also require both items to have empty `addedTags` and `removedTags`.
  6. **Partial-count `moveItem`:** copy `addedTags` and `removedTags` onto `part`.
  7. `Reconcile` keeps working: it assigns `item.proto` for a reload and must leave `addedTags` and `removedTags` alone.

### Test & Documentation Requirements

- `ItemTagsTest.kt`:
  - effective tags with added and removed tags;
  - removing a prototype tag survives a reload;
  - adding a tag the prototype has later makes no change;
  - an invalid tag is refused;
  - transform keeps the id, slot, attributes and instance affects;
  - transform to a slot-less proto moves the item to the inventory and swaps `equip_affects`;
  - transform refuses a non-stackable target for a stack, and a non-container target for a full container;
  - transform updates the holder's stats immediately;
  - stack and partial-move rules hold for tag changes.

### Exit Conditions

- [] `./gradlew check` and `./gradlew markdownCheck` pass.
- [] `ItemTagsTest` passes.

---

## Phase 5: Persistence and `@stat`

### Objective & Scope

- **In Scope:**
  - Persist instance item affects and tag changes in `ItemPayload`.
  - Refresh holdings after restore (already wired in Phase 1).
  - `@stat` on an item lists its affects and tag changes.
- **Out of Scope:** script and plugin surfaces.

### Context Working Set

- **Read / Reference Only:**
  - `persist/.../Codec.kt` (`AffectJson`, `affectsJson`, `restoreAffects`, `ItemPayload`, `applyItem`)
  - `persist/src/test/.../SensesPersistenceTest.kt` (pattern)
  - `core/.../world/Tools.kt` (around line 609, how affects are listed for actors)
- **Files to Modify / Create:**
  - `persist/src/main/kotlin/gups/persist/Codec.kt`
  - `core/src/main/kotlin/gups/core/world/Tools.kt`
  - `persist/src/test/kotlin/gups/persist/ItemAffectsPersistenceTest.kt` (new)
  - `core/src/test/kotlin/gups/core/world/ToolsTest.kt`
- **Do Not Touch:** `script/`, `plugin-api/`, `SPEC.md` (persistence detail lives in Codec KDoc).

### Specification & Decision Record Changes

- None in SPEC.md. Update the Codec KDoc "Items ([ItemPayload])" bullet to list instance affects and tag changes.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // ItemPayload gains (defaults keep old rows readable):
  val affects: List<AffectJson> = emptyList(),
  val addedTags: List<String> = emptyList(),
  val removedTags: List<String> = emptyList(),
  // AffectJson gains: val applies: String? = null   ("worn" | "carried"; null for actor affects)
  ```
- **Algorithmic Steps & Invariants:**
  1. Serialize item affects with the same per-affect mapping `affectsJson` uses, factored into a helper that takes
     `List<Affect>`, adding `applies`. `expiresAt == Long.MAX_VALUE` round-trips as-is.
  2. In `applyItem`, rebuild `item.affects` from the JSON (mirror `restoreAffects` without the `wornFrom` filter), and
     fill `addedTags` and `removedTags`.
  3. Holdings must be refreshed after items are restored. Phase 1 wired the call; verify it runs after `applyItem`.
  4. **`@stat <item>`:** after the existing item lines, list each `heldAffects()` entry: name, `applies`, `(prototype)`
     or the remaining pulses (or `permanent`), followed by the added and removed tags. Reuse existing message keys or
     add `KernelMsg` entries following the actor-affect listing style.

### Test & Documentation Requirements

- `ItemAffectsPersistenceTest.kt`:
  - a timed and a permanent instance affect on a carried item round-trip, and the restored player's stat reflects the
    carried affect;
  - tag changes round-trip;
  - a payload without the new fields loads.
- `ToolsTest`: `@stat` on an item shows its affects.

### Exit Conditions

- [] `./gradlew check` passes.
- [] The new persistence test passes.

---

## Phase 6: `item_affect`, `item(affects=)` and `item(variants=)` declarations

### Objective & Scope

- **In Scope:** the `item_affect(...)` declaration builtin; the `affects` and `variants` arguments of `item()`; the
  keyring rule counting prototype `affects`; spec §4.2, §3.10 and §4.9.
- **Out of Scope:** skills and effect primitives (Phase 7); ctx verbs and views (Phase 8).

### Context Working Set

- **Read / Reference Only:**
  - `script/.../Declarations.kt`: `item()` (~line 898), `ITEM_SPEC` (~line 2086–2130), `equipAffects` (~line 531),
    `container(...)` and how it is registered, `typed`, `senses`, `modifiers`.
  - `script/.../ApiModel.kt` (how declaration builtins are listed).
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Declarations.kt`
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`isKeyOnly`, which M16 moved onto `ItemProto`)
  - `script/src/test/kotlin/gups/script/ItemEffectsScriptTest.kt` (new)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, `docs/api/api-1.json` (regenerated)
- **Do Not Touch:** `Ctx.kt`, `Views.kt`, `core/.../world/`, `plugin-api/`.

### Specification & Decision Record Changes

- **§4.2 example `item("short_bow", ...)`:** after `modifiers={}, equip_affects=[], # apply while worn (§4.6)` add
  `affects=[], # item_affect(...) entries (§3.3)` and `variants={}, # related prototypes, for transform (§4.5)`.
- **§4.2 Declaration rules, Item slots bullet:** add "`item_affect(name, applies = "worn", stat_deltas = stats(),
  sense_deltas = {}, modifiers = {}, flags = [])` declares a prototype affect. A `worn` affect with `modifiers` needs a
  `wear` slot. A `carried` affect doesn't. `stunned` isn't allowed. `variants` values are item labels, checked at load
  like `loot`; its keys match the tag pattern."
- **§3.10 kernel row:** add "item affects (`item_affect`, item `affects` and `variants`)".
- **§4.9 `keyring` bullet:** add `affects` to the list of purposes that keep an item off the keyring ("no `wear` slot,
  `container`, `commands`, `dice`, `stat_deltas`, `equip_affects`, `affects` or `modifiers`").
- CHANGELOG bullets for `item(affects=)`, `item(variants=)` and `item_affect`.

### Implementation Blueprint

- **Signatures & Types:** `item_affect` returns a small decl value (mirror `ContainerDecl`) wrapping `ItemAffect` from
  Phase 2. `ItemProto.variants: Map<String, Label>` exists from Phase 4.
- **Algorithmic Steps & Invariants:**
  1. **`item_affect(...)` builtin:**
     - a declaration builtin like `container(...)`, registered and listed wherever `container` is;
     - parse `stat_deltas` with `typed(..., TypedKind.STATS, ...)`, `sense_deltas` with `senses(...)`, `modifiers` with
       `modifiers(...)`, `flags` as a string list, and `applies` as a choice of `worn|carried`;
     - fail on `stunned`.
  2. **`item()`:**
     - add `affects` (a list of `item_affect` values; reject other types with "expected item_affect(...)") and
       `variants` (a dict of str to label; check each key against the tag pattern and each label with `ctx.ref(label,
       RefKind.ITEM, thread)`, as `container` keys are checked) to `ITEM_SPEC`;
     - fail when a `worn` affect has `modifiers` and the item has no `wear`.
  3. **Keyring:** add `affects.isEmpty()` to `ItemProto.isKeyOnly`, so a key that carries an affect stays visible in
     `inventory` (a cursed key mustn't hide on the keyring). M16 derives the `no_all` tag from `isKeyOnly`; that keeps
     working unchanged.

### Test & Documentation Requirements

- `ItemEffectsScriptTest.kt` (harness-style, mirror `M5ScriptTest`):
  - `item(affects=[item_affect("luck", applies="carried", stat_deltas=stats(dex=-1))])` counts from inside a bag in the
    backpack and stops when the bag is dropped (move it with `ctx.move_item` in the test);
  - a `variants` label that doesn't exist fails to load, and so does a bad variant name;
  - a `worn` `item_affect` with modifiers on a slot-less item fails to load;
  - `stunned` in `item_affect(flags=)` fails to load;
  - a `kind="key"` item with `affects` is not key-only.

### Exit Conditions

- [] The baseline has been regenerated (standing rule 5), then `./gradlew check` and `./gradlew markdownCheck` pass.
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
  - `script/.../Declarations.kt`: the effect primitives `apply_affect` (~1317–1405), `dispel` (~1409), `effect(...)`
    (~1521), `skill()` (~963).
  - `core/.../world/SkillCommands.kt` (lines 99–240).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Effects.kt` (`Effect.ApplyAffect`)
  - `script/src/main/kotlin/gups/script/Declarations.kt` (the primitive, `affectEffect` ~line 2333, the skill check)
  - `script/src/main/kotlin/gups/script/Ctx.kt` (only the `e.duration` use at ~line 1010; see step 2)
  - `core/src/main/kotlin/gups/core/world/Combat.kt`
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt`
  - `core/src/main/kotlin/gups/core/world/World.kt` (`PluginEffectRunner`)
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt` (`PluginEffectCall`)
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt` (the runner at ~line 464)
  - `script/src/test/kotlin/gups/script/ItemEffectsScriptTest.kt`, `core/src/test/.../PluginSeamsTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, `docs/api/api-1.json` (regenerated)
- **Do Not Touch:** `Views.kt`, facade files, the `item()` declaration.

### Specification & Decision Record Changes

- **§4.3 Targeting, `item` bullet:** replace "Effect primitives act only on actors, so an item skill does its work in
  `resolve`." with "Its `effect` acts on the item: `apply_affect` attaches an item affect (with `applies`), plugin
  primitives receive the item, and `sequence` may combine them. Any other primitive in an item skill's effect fails the
  load."
- **§4.3 primitive table, `apply_affect` row:** add `applies="worn"` to the signature, and to its purpose: "On an `item`
  skill, attaches an item affect; `applies` (`worn` or `carried`) is allowed only there, and `duration` may be `None`
  for a permanent one."
- CHANGELOG bullets for `apply_affect(applies=)`, `duration=None` on item skills, and the item-skill effect rule.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Effects.kt
  data class ApplyAffect(
    val name: String,
    /** Pulses; null only for a permanent item affect. */
    val duration: Int?,
    /* existing fields unchanged */
    val applies: AffectScope? = null, // set only for item-skill effects
  ) : Effect
  // Declarations.kt
  internal fun affectEffect(/* existing params */, allowPermanent: Boolean = false): Effect.ApplyAffect
  // World.kt
  fun interface PluginEffectRunner {
    fun apply(world: World, effect: Effect.Plugin, caster: Actor, target: Actor?, targetItem: Item?)
  }
  // PluginApi.kt
  data class PluginEffectCall(
    val world: PluginWorld, val caster: PluginActor,
    /** Null for `target = "none"` and `target = "item"` skills. */
    val target: PluginActor?,
    val args: Map<String, Any?>,
    /** The target item of an `item` skill, else null. */
    val targetItem: PluginItem? = null,
  )
  // Combat.kt
  /** Applies an item-capable [effect] from [caster] to [item]. */
  fun World.applyItemEffect(effect: Effect, caster: Actor, item: Item, skill: SkillProto? = null)
  ```
- **Algorithmic Steps & Invariants:**
  1. **`affectEffect`:** when `allowPermanent` is true and `duration` is `None`, produce `duration = null`; otherwise
     keep today's "must be at least 1" check. It never sets `applies`.
  2. **Every reader of `ApplyAffect.duration`:** grep `\.duration` and `ApplyAffect` in `core/`, `script/` and
     `plugin-api/` before editing. Known sites: `Combat.kt` `applyEffect` (~line 453) and `Ctx.kt` `apply_affect` (~line
     1010). Actor paths use `requireNotNull(e.duration) { "apply_affect: duration is required for an actor" }`; the
     skill load check guarantees it for skills.
  3. **The `apply_affect` primitive:** add `Param(name = "applies", defaultValue = "None")`; call `affectEffect(...,
     allowPermanent = true)`; parse `applies` (`worn|carried`) itself and `copy(applies = ...)` the result.
  4. **`skill()` load check** (after the effect is parsed):
     - for `target == ITEM`, walk the effect tree (`Sequence` recursively); allow `ApplyAffect` and `Plugin`; fail on
       anything else with `"skill(effect): <primitive> acts on actors; an item skill's effect may use apply_affect and
       plugin primitives"`; also fail on an `ApplyAffect` carrying `stunned`;
     - for non-item targets, fail when any `ApplyAffect` has `applies != null` or `duration == null`.
  5. **`applyItemEffect`:** `Sequence` recurses; `ApplyAffect` calls `applyItemAffect(item, ..., applies =
     effect.applies ?: AffectScope.WORN, source = caster)`; `Plugin` calls `pluginEffects.apply(this, effect, caster,
     null, item)`; anything else is unreachable (`error(...)`).
  6. **`SkillCommands.kt` line ~233:** replace `if (onItem == null) skill.effect?.let { ... }` with: when `onItem` is
     non-null, call `w.applyItemEffect(it, actor, onItem, skill)` if the item is still in the world; else the existing
     actor call.
  7. **Existing `applyEffect`'s Plugin branch:** call `pluginEffects.apply(this, effect, caster, target, null)`.
  8. **The `PluginHost` runner:** take `targetItem` and pass `targetItem?.let { ItemFacade(it) }`.

### Test & Documentation Requirements

- Extend `ItemEffectsScriptTest.kt`:
  - an `enchant` item skill with `apply_affect("keen", 50, applies="worn", stat_deltas=stats(dex=1))` raises dex when
    the item is worn and stops at expiry;
  - `duration=None` on an item skill makes a permanent affect; `duration=None` on an enemy skill fails to load;
  - `damage(...)` and `dispel(...)` on an item skill fail to load with the message;
  - `applies=` on an enemy skill fails to load.
- A `PluginSeamsTest` case: the plugin effect runner receives `targetItem` for an item skill and `target` is null.

### Exit Conditions

- [] The baseline has been regenerated (standing rule 5), then `./gradlew check` and `./gradlew markdownCheck` pass.
- [] `ItemEffectsScriptTest` and `PluginSeamsTest` pass.

---

## Phase 8: `ctx` verbs and views for items

### Objective & Scope

- **In Scope:**
  - `ctx.apply_affect` and `ctx.remove_affect` accept items.
  - New `ctx.add_tag`, `ctx.remove_tag` and `ctx.transform`.
  - Item view fields `affects`, effective `tags`, `has_tag` and `variants`.
  - `AffectView` on items.
- **Out of Scope:** plugin facade (Phase 10).

### Context Working Set

- **Read / Reference Only:**
  - `script/.../Ctx.kt` (`apply_affect`/`remove_affect`, ~line 913–1040; the `actorArg` helper; the item verbs that take
    an item argument)
  - `script/.../Views.kt` (item view ~line 600–720; `AffectView` ~line 689)
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`
  - `script/src/main/kotlin/gups/script/Views.kt`
  - `script/src/test/kotlin/gups/script/ItemEffectsScriptTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `core/` (all core verbs exist), `plugin-api/`.

### Specification & Decision Record Changes

- **§4.5 Context fields:** after the `affect (affect hooks)` mention, note "for an item affect's hooks, `self` is the
  item".
- **§4.5 Item view:** append `affects`, `has_tag(tag)` and `variants` to the field list, and note "`tags` are the
  effective tags".
- **§4.5 Verb table:**
  - change the `Vitals and combat` row's `apply_affect(target, ...)`/`remove_affect(target, name)` to say a target may
    be an actor or an item;
  - add `add_tag(item, tag)`, `remove_tag(item, tag)` and `transform(item, label)` to the Items row.
- **§4.5 Verb semantics:** extend the `apply_affect` bullet with "On an item it takes `applies` (`worn` or `carried`,
  default `worn`) and allows `duration=None` (permanent); `applies` on an actor is an error; item affects can't carry
  `stunned`." Add an Items sub-bullet for `transform`: "keeps the item's id, location, count, attributes, affects, tag
  changes, name and timers; refuses a stack into a non-stackable prototype and a non-empty container into a
  non-container; an equipped item that no longer fits its slot moves to the inventory; no hooks fire." Add "`add_tag`
  and `remove_tag` return whether the effective tags changed."

### Implementation Blueprint

- **Signatures & Types:**
  - `ctx.apply_affect` gains `Param(name = "applies", named = true, defaultValue = "None")`, with `duration` allowing
    `None` for items: call `affectEffect(..., allowPermanent = true)` (Phase 7) and reject a `None` duration on the
    actor path. Dispatch on the target type:
    - an item view goes to `world.applyItemAffect(...)` and returns an `AffectView` hosted on the item;
    - an actor goes down the existing path, raising if `applies` isn't `None`.
  - `ctx.remove_affect(target, name)` dispatches the same way.
  - `ctx.add_tag(item, tag) -> bool`, `ctx.remove_tag(item, tag) -> bool`, `ctx.transform(item, label) -> None`. The
    label is resolved the way `spawn` resolves item labels (relative `:label` against the owner's package).
  - `AffectView`'s `host: Actor` becomes `host: Entity`. Add a `structField` `applies` (`None` for actor affects) and
    `expires_at` (`None` when permanent). Keep existing fields working for actor hosts.
  - Item view:
    - `affects` returns a list of item-affect views for `heldAffects()`. Prototype entries are a read-only view with
      `name`, `applies`, `expires_at = None` and `source = "proto"`; instance entries are `AffectView`s with `source =
      "instance"`;
    - `has_tag(tag)` and `variants` (dict of name to label string) are new;
    - `tags` reads `item.tags`, which is already effective after Phase 4.
- **Algorithmic Steps & Invariants:**
  1. Every new verb goes through `verb("name") { ... }` so errors become script errors.
  2. Arguments are validated before any change (tag pattern, item in world, label kind).

### Test & Documentation Requirements

- Extend `ItemEffectsScriptTest`:
  - `ctx.apply_affect(item, ..., duration=None, applies="carried")` and `remove_affect(item, ...)`;
  - `applies` on an actor raises;
  - an item affect's `on_expire` sees `ctx.self` as the item;
  - `add_tag` and `remove_tag` return values, and `ctx.item.tags`;
  - `transform` via `variants["uncursed"]`, after which the holder's stat changes;
  - the item view's `affects` lists prototype and instance affects with the right `source`.
- Doc examples as required by the standing rules.

### Exit Conditions

- [] `./gradlew check` and `./gradlew markdownCheck` pass; the baseline has been rewritten.
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
  - `core/.../world/Verbs.kt` (`restrictionRefusal`, ~line 156)
  - `core/.../world/KernelCommands.kt`:
    - the wear check (~line 740–762, the pattern to mirror);
    - `planRemove` (~line 830);
    - the drop, give and put planners (~lines 595, 644, 704)
  - `plugin-api/.../host/PluginHost.kt` (~line 329 and 407)
  - `plugin-api/.../host/Adapters.kt` (~line 190)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Creation.kt`
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`
  - `core/src/test/kotlin/gups/core/world/RestrictionsTest.kt`
  - `docs/SPEC.md`, `docs/PLUGINS.md`
- **Do Not Touch:** `script/`, `Facade.kt`.

### Specification & Decision Record Changes

- **§1.6 Restriction row:** "A plugin check that can refuse wearing, removing, dropping, giving or putting an item,
  entering, casting or learning (§4.19)."
- **§4.19:** "at four points" becomes "at eight points". Add these table rows (all `kernel`):

  |  Point   |                                                        Checked                                                         |          Subject           |
  |----------|------------------------------------------------------------------------------------------------------------------------|----------------------------|
  | `remove` | `remove` (and its renames), and every item `wear` or `wield` (and their renames) would swap out, before anything moves | the item and its slot      |
  | `drop`   | `drop`, before the item moves                                                                                          | the item                   |
  | `give`   | `give`, before the item moves                                                                                          | the item and the receiver  |
  | `put`    | `put`, before the item moves                                                                                           | the item and the container |

  Add the bullet: "`remove` runs wherever a command would take a worn item off, so no command can swap out an item the
  check refuses: `remove` itself, and `wear` or `wield` into an occupied slot, including a slot family whose slots are
  all full, where the item that would be swapped out is checked. A refusal there refuses the whole `wear`, and nothing
  moves. `drop`, `give` and `put` only ever take carried items, so a worn item must pass `remove` before any of them can
  move it."

  Add the bullet: "Item restrictions guard commands only, for players and for mobs acting through commands. Script and
  facade verbs (`move_item`, `give`, `destroy`, `transform`) and the engine's own moves (death, decay, the re-check
  after a choice change) don't run them."

- **§4.15:** update the `PluginRestrictionPoint` lists to the eight points.

- **`PLUGINS.md`:** in the restrictions section, list the new points and the command-only rule.

### Implementation Blueprint

- **Signatures & Types:**
  - `RestrictionPoint` gains `REMOVE`, `DROP`, `GIVE` and `PUT`, with KDoc. `REMOVE`'s KDoc must say: "Taking a worn
    item off by command: `remove`, and the item a `wear` or `wield` would swap out of an occupied slot (including a full
    slot family). Trusted moves (verbs, death, the re-check after a choice change, `transform`) don't run it."
  - `PluginRestrictionPoint` gains the same names, in the same order (the adapters map by `name`).
  - `RestrictionRequest.target` carries the receiving actor (`GIVE`) or the container item (`PUT`); `slot` carries the
    slot for `REMOVE`.
- **Algorithmic Steps & Invariants:**
  1. In each planner's `validate`, after its existing checks, build a `RestrictionRequest`, call
     `w.restrictionRefusal(point, req)`, and return a non-null refusal, mirroring the wear check.
  2. **Wear and wield swap:** the `wear` and `wield` planners share the validate at ~line 740. After `slotFor` picks the
     slot, when `actor.equipped(slot)` is non-null, run `REMOVE` for that occupant (with its slot) before the `WEAR`
     check, and return its refusal. `slotFor` returns a free family slot when there is one, so a free `finger_2` is used
     without touching a cursed ring on `finger_1`; only when every family slot is full does it return the first, and
     then that occupant is checked. Do not make `slotFor` skip refused occupants: a refusal must be visible, not
     silently routed around.
  3. **Audit every unequip path** before finishing: `grep -n "ItemLocation.Carried(actor.id)" KernelCommands.kt`, and
     every `moveItem` whose source is `ItemLocation.Equipped`. Each one reached from a player or mob command must be
     either `planRemove` or the wear/wield swap, and both now run `REMOVE`. Confirm that `drop`, `give`, `put` and M16's
     `all` forms reject equipped items before any restriction (`NOT_CARRYING`).
  4. **Counts and `all` (from M16):** `drop`, `give` and `put` resolve several stacks and run one `before_*` veto per
     stack. Run the restriction for each stack right before that stack's veto. A refused stack is treated exactly like a
     vetoed one, using M16's handling.
  5. Make sure the `PluginHost` feature gating (line ~329) doesn't treat the new points as `skills`-only.

### Test & Documentation Requirements

- `RestrictionsTest.kt`: a test `Restriction` refusing items with tag `t`:
  - each of `remove`, `drop`, `give` and `put` is refused with the message and no lag;
  - `wear` into a slot holding a refused item is refused, and nothing moves;
  - `wield` of a new weapon while the wielded weapon is refused at `remove` is refused: the old weapon stays wielded,
    the new one stays in the inventory, and no lag is charged;
  - the same through a renamed command (`rename = {"remove": "unwield"}`), and through a mob's `act(mob, "wield x")`;
  - slot family: with `finger_1` holding a refused ring and `finger_2` free, `wear ring` uses `finger_2`; with both full
    and `finger_1` refused, `wear ring` is refused and nothing moves;
  - `drop`, `give` and `put` of an equipped item fail with the not-carrying message, never reaching a restriction;
  - a mob's `act(mob, "drop x")` is refused;
  - `moveItem` and `destroy` bypass it.
- `PluginSeamsTest`: a plugin restriction registered at `DROP` is reached.

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
  - `plugins.html` listing of filters.
- **Out of Scope:** the curse plugin.

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/.../host/Adapters.kt` (the restriction adapter pattern: catch, `Diagnostic`, allow)
  - `plugin-api/.../host/PluginHost.kt` (`install`, `describe`, `PluginInfo`)
  - `script/.../DevTools.kt` or wherever `plugins.html` renders restrictions (grep `restrictions` in `script/`)
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt` (and `Adapters.kt` for the filter adapter)
  - The `plugins.html` renderer
  - `core/src/test/kotlin/gups/core/world/PluginSeamsTest.kt` (or the plugin-api tests where facade tests live)
  - `docs/SPEC.md`, `docs/PLUGINS.md`
- **Do Not Touch:** `core/` (except tests), `script/` verbs.

### Specification & Decision Record Changes

- **§4.15 PluginRegistry bullet:** add "containment filters (`addContainmentFilter`)".
- **§4.15 Facade verbs list:** add `applyItemAffect`, `removeItemAffect`, `addTag`, `removeTag` and `transform`.
- **§4.15 capabilities:** add a bullet: "**Containment filters (`addContainmentFilter`):** called while the engine works
  out which carried item affects reach a holder, once per container on the path and affect behind it; returning `true`
  stops that affect at that container. Filters may read only the container's and item's label, kind, tags and affects
  (the state whose changes trigger a recompute). They never see worn or directly carried items. A filter that throws
  records a `plugin` diagnostic and doesn't block."
- **§4.15 Effect primitives bullet:** add "On an `item` skill, `targetItem` is the item and `target` is `None`."
- **§4.15 Seams:** add `World.containmentFilters`, populated by `WorldPluginWiring.install(world)`.
- **§4.16 `plugins.html` row:** add "containment filters (doc)".
- **`PLUGINS.md`:** sections for item affects, tags and `transform` via the facade, `targetItem`, and containment
  filters.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Facade.kt
  interface PluginItem : PluginEntity {
    /* existing */
    /** Effective tags: the prototype's plus added, minus removed. */
    override val tags: List<String>   // keep the type; now effective
    fun hasTag(tag: String): Boolean
    val variants: Map<String, String>
    val affects: List<PluginItemAffect>
    val contents: List<PluginItem>
    /** The actor whose custody this item ends in, following containers; null on the ground. */
    val custodian: PluginActor?
  }
  data class PluginItemAffect(
    val name: String, val applies: String, val flags: List<String>,
    val statDeltas: Map<String, Int>, val senseDeltas: Map<String, Int>,
    /** "proto" or "instance". */ val source: String,
    /** Absolute pulse, or null when permanent or from the prototype. */ val expiresAt: Long?,
  )
  // PluginWorld
  fun applyItemAffect(item: PluginItem, name: String, duration: Int?, applies: String = "worn",
                      statDeltas: Map<String, Int> = emptyMap(), flags: List<String> = emptyList(),
                      senseDeltas: Map<String, Int> = emptyMap()): Boolean
  fun removeItemAffect(item: PluginItem, name: String): Boolean
  fun addTag(item: PluginItem, tag: String): Boolean
  fun removeTag(item: PluginItem, tag: String): Boolean
  fun transform(item: PluginItem, label: String)
  // PluginApi.kt
  data class PluginContainmentCall(
    val world: PluginWorld, val holder: PluginActor, val container: PluginItem,
    val item: PluginItem, val affect: PluginItemAffect,
  )
  // PluginRegistry
  fun addContainmentFilter(doc: String, blocks: (PluginContainmentCall) -> Boolean)
  ```
- **Algorithmic Steps & Invariants:**
  1. Facade verbs mirror existing ones (for example `applyAffect`):
     - fail when the item left the world;
     - validate schema names with suggestions;
     - convert `applies` strings to `AffectScope`.
  2. `install(world)`:
     - set `world.containmentFilters` to adapters in plugin id order, then registration order;
     - each adapter builds a `PluginContainmentCall` and catches `Throwable` → `Diagnostic(..., "plugin", ...)` →
       returns false, exactly like the restriction adapter.
  3. `describe()`: add `containmentFilters: List<PluginMemberInfo>` (or a small info class with `doc`) to `PluginInfo`,
     defaulting to empty, and render it on `plugins.html` after restrictions.

### Test & Documentation Requirements

- Plugin seam tests:
  - a test plugin's filter blocks a carried affect behind a tagged container and not elsewhere;
  - a throwing filter records a diagnostic and doesn't block;
  - facade `addTag`/`removeTag` refresh the holder (a filter reading the container tag flips the result);
  - `transform` through the facade;
  - `PluginItem.affects` shows both sources.
  - `PluginItem.custodian` (built on `World.custodian`) is null on the ground and is the actor for a carried, worn, and
    nested-in-a-worn-backpack item.
- `DevToolsTest` (or the `plugins.html` test) lists a filter's doc.

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
  - `samples/heritage-plugin/` (layout, `build.gradle.kts`, `src/plugin/META-INF/services/gups.pluginapi.GupsPlugin`,
    `HeritagePluginTest.kt`, `HeritageWorldTest.kt`, README structure)
  - `settings.gradle.kts`
- **Files to Modify / Create:**
  - `samples/curse-plugin/build.gradle.kts`
  - `samples/curse-plugin/README.md`
  - `samples/curse-plugin/src/main/kotlin/gups/sample/curse/CursePlugin.kt`
  - `samples/curse-plugin/src/plugin/META-INF/services/gups.pluginapi.GupsPlugin`
  - `samples/curse-plugin/src/test/kotlin/gups/sample/curse/CursePluginTest.kt`
  - `samples/curse-plugin/src/test/kotlin/gups/sample/curse/CurseWorldTest.kt`
  - `settings.gradle.kts` (add `"samples:curse-plugin"`)
  - `docs/SPEC.md`
- **Do Not Touch:** engine modules.

### Specification & Decision Record Changes

- **§4.15 Sample plugins:** add "**Curse Plugin ([samples/curse-plugin](../samples/curse-plugin/README.md)):** curses as
  content. It refuses `remove` of a worn item tagged `cursed`, so a cursed item clings and its `worn` affects can't be
  shed by taking it off; removing a worn container that only holds a cursed item is allowed, because nothing leaves the
  wearer's custody. It refuses `drop`, `give` and `put` of an item bearing a curse (tagged `cursed`, or a container
  holding one that isn't behind a `warded` container), `put` only when the destination is outside the actor's custody;
  adds `curse.lift()` and `curse.bestow()` primitives for item skills; and registers a containment filter that stops
  cursed items' carried affects behind containers tagged `warded`."

### Implementation Blueprint

- **Plugin** (`id = "curse"`, `requiredFeatures = emptySet()`, kernel only, though its primitives need `skills` to be
  used in a skill):
  1. **The one predicate.** All checks and the filter share one helper, so they can never disagree:

     ```kotlin
     /** True if [item] carries a curse to whoever holds it: it is cursed, or holds one not shut away by a ward. */
     private fun bearsCurse(item: PluginItem): Boolean =
     item.hasTag("cursed") || (!item.hasTag("warded") && item.contents.any(::bearsCurse))
     ```

     A cursed item is always cursed, even when tagged `warded` itself. A `warded` container that isn't cursed never
     bears a curse, whatever it holds, because the ward stops propagation.

  2. **Restrictions** (message `"It won't budge. It must be cursed!"`; mobs are treated like players):

     - `REMOVE`: refuse when `call.item?.hasTag("cursed") == true`. This deliberately checks the item's own tag, not
       `bearsCurse`. Taking a worn item off only moves it to the inventory, so it never leaves the wearer's custody, and
       carried curses inside it keep applying; refusing a worn backpack that merely holds a cursed amulet would gain
       nothing. What `REMOVE` protects is the cursed item's own `worn` affects, which would end the moment it came off:
       the classic "the cursed item clings to you". The engine runs this check for `wear`/`wield` swaps too, so wielding
       a new weapon can't swap out a cursed one. Put this reasoning in the KDoc of the `REMOVE` check and of
       `bearsCurse`, without spec or decision citations;
     - `DROP`, `GIVE`: refuse when `bearsCurse(call.item)`;
     - `PUT`: refuse when `bearsCurse(call.item)` **and** the destination container `(call.target as PluginItem)` has a
       `custodian` other than `call.actor` (including null, a container on the ground). A move that stays within the
       actor's own custody never sheds the curse, since carried affects follow the custody tree, so it is always
       allowed. Putting the cursed amulet into a `warded` bag in one's own inventory is how a player shuts the curse
       away;
     - everything else allows.
  3. **Effect primitives** (`addEffectPrimitive`):
  4. **Effect primitives** (`addEffectPrimitive`):
     - `lift()`: no args; with `call.targetItem == null`, do nothing; else if `item.variants["uncursed"]` exists,
       `world.transform(item, it)`, else `world.removeTag(item, "cursed")`;
     - `bestow()`: `world.addTag(item, "cursed")`.
  5. **Containment filter:** block when `container.hasTag("warded") && item.hasTag("cursed")`. This matches
     `bearsCurse`: a curse stops at the first ward on its path.

### Test & Documentation Requirements

- `CursePluginTest`, with each case its own test:
  - `bearsCurse` decision table: plain item (false), cursed item (true), bag holding a cursed item (true), bag in a bag
    holding one (true), `warded` box holding one (false), plain bag holding a `warded` box holding one (false), cursed
    `warded` box (true);
  - `REMOVE`: a worn cursed item is refused; a worn plain item is allowed; a worn backpack holding a cursed amulet is
    allowed; a worn item that is both cursed and `warded` is refused;
  - `DROP` and `GIVE`: refused for the cursed item and for a bag holding it, allowed for a `warded` box holding it;
  - `PUT` within custody: cursed item into a bag in the actor's inventory, into a bag inside a worn backpack, and into a
    `warded` box in the inventory are all allowed;
  - `PUT` out of custody: cursed item into a container on the ground, and into a container another actor carries, are
    refused; a bag holding the cursed item into a ground chest is refused; a `warded` box holding the cursed item into a
    ground chest is allowed;
  - `lift` with and without a variant; `bestow`; the filter decision table.
- `CurseWorldTest` (end to end, mirror `HeritageWorldTest`):
  1. Items:
     - `amulet_of_woe` (`wear="neck"`, `tags=["cursed"]`, `affects=[item_affect("misfortune", applies="carried",
       stat_deltas=stats(dex=-2))]`, `variants={"uncursed": ":amulet_of_warding"}`);
     - `amulet_of_warding` (`affects=[item_affect("warding", applies="worn", stat_deltas=stats(dex=1))]`);
     - `small_bag` and `backpack` containers;
     - `lead_box` (container, `tags=["warded"]`).
  2. **Propagation, by commands:** `put amulet bag`, then `put bag backpack`, all in the player's own inventory, are
     allowed, and dex stays −2 at each step.
  3. **Can't shed it:** `drop amulet`, `drop bag`, `drop backpack` and `give backpack <mob>` are refused; with a `chest`
     container on the ground, `put backpack chest` is refused. Dex stays −2.
  4. **Warded box:** `put amulet lead_box` (the box in the player's inventory) is allowed and restores dex. Then `drop
     lead_box` is allowed, and so are `put lead_box chest` and `give lead_box <mob>` in a variant of the test. Once
     dropped, the player's dex is back to normal and the player can walk away.
  5. **Ward inside a bag:** with the amulet in the lead box inside the backpack, `drop backpack` is allowed.
  6. **Removing a worn bag isn't shedding:** with the amulet in the backpack and the backpack worn, `remove backpack` is
     allowed, the backpack lands in the inventory, and dex stays −2; `drop backpack` is then refused.
  7. **No swap loophole:** item `cursed_sword` (`wear="wield"`, `tags=["cursed"]`, a `worn` `item_affect` with dex −1)
     is wielded. `wield dagger` is refused: the sword stays wielded, the dagger stays in the inventory, and dex stays
     reduced. `remove sword` is refused too. After `curse.lift()` (no variant, so only the tag goes), `wield dagger`
     works and the sword moves to the inventory.
  8. **Clinging ring in a slot family:** with a cursed ring on `finger_1` and `finger_2` free, `wear ring` puts the
     plain ring on `finger_2`. With both fingers full and the cursed ring first, `wear ring` is refused.
  9. **Lift:** an item skill `remove_curse` with `effect=curse.lift()` turns the worn amulet into `amulet_of_warding`
     (same item id, still worn, dex +1), after which `remove` works.
  10. **Bestow:** `bestow` on a plain dagger makes it undroppable.
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
  - `samples/heritage-plugin/docs/tutorials/` (style and README roadmap)
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
- **03:** `curse.lift()` with `variants` and `transform`; why the clean version is a separate prototype (the luck −2
  versus luck +10 example); `bestow`.
- **04:** the `warded` filter; the purity contract (read only label, kind, tags, affects). The `bearsCurse` helper
  shared by the restrictions and the filter, so "the ward stops the curse" holds for both stats and drops. The worked
  example: `put amulet lead_box`, then `drop lead_box`, walking away free.
- **05:** the test structure from `CurseWorldTest`.
- **Template-world `03_items_and_equipment.md`:** add a section covering `item_affect` and prototype `affects`,
  `ctx.apply_affect(item, ...)` and `remove_affect`, `add_tag`/`remove_tag`, and `variants`/`transform`, with an
  `enchant_weapon` item skill example (`apply_affect("keen", 600, applies="worn", stat_deltas=stats(dex=1))`). Every
  snippet must load against the template schema (its stats: `str`, `dex`, `con`, `int`, `wis`, `armor`).

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
  `samples/curse-plugin` (§3.3, §4.2, §4.3, §4.5, §4.6, §4.15, §4.19)."
- **`docs/DESIGN_NEEDED.md`:** mark `### Effects on items` as completed. Do **not** delete it: append `**Status:**
  completed in [M17](MILESTONE_17.md); awaiting designer review.` to the entry. Leave `### Enchantments on rooms` open.
- Re-read the entire `docs/CHANGELOG.md` `### Effects on items` subsection against the final API, and rerun `gups docs
  --update-baseline`.
- Run `./gradlew markdownFormat`, `./gradlew markdownCheck` and `./gradlew check`; all must pass.
- Confirm by grep that no Kotlin file under `core/`, `script/`, `persist/`, `plugin-api/` or `samples/` contains `SPEC`,
  `§` or `D-1`.
