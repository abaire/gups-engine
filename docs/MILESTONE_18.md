# Milestone 18: Slot Families, Per-Actor Slots, Slot Costs, Item Attacks and Vital/Resist Deltas

**Prerequisites:** [M16](MILESTONE_16.md) and [M17](MILESTONE_17.md) must be fully implemented first. This milestone
assumes M17's end state:

- the per-actor `HeldEffects` cache, `World.refreshHoldings(actor)` and `refreshHeld` in `core/.../world/Holdings.kt`;
- item affects (`ItemAffect`, `HeldAffect`, `AffectScope`, `World.applyItemAffect`);
- the `REMOVE` restriction point, checked in the `remove` command handler;
- `updateEncumbrance` being internal and called only by `refreshHoldings` and the world tick.

Where line numbers below differ after M16/M17, find the symbol by name.

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** [DESIGN_NEEDED.md](DESIGN_NEEDED.md):
  - `## Combat and skills` → `### Armor from equipment, shields and off-hand weapons`
  - `## Combat and skills` → `### Spell damage modifiers`
  - `## Items` → `### Equipment slots that depend on the actor`
- **Design Summary:**
  - **Vital and resist deltas.** `max_vital_deltas` and `resist_deltas` join `stat_deltas` and `sense_deltas` on
    `item(...)`, `item_affect(...)`, actor affects (`apply_affect`, the `apply_affect` effect primitive) and instance
    item affects. Traits already have both. Item contributions flow through `HeldEffects`; actor affects are summed
    directly. Current vitals are clamped to the effective maximum (and the condition refreshed) after a holdings
    refresh, after an actor affect is applied or ends, after the fit check, and once at the end of restore, so a `+hp`
    item no longer loses its boost on login.
  - **One `damage` chain for all outgoing damage.** No `damage_deltas` field. The spell `damage(...)` primitive now runs
    its rolled amount through the caster's `damage` modifier chain before `mitigate`, with `ctx.skill` set. New
    `ctx.damage_type` (every `damage` chain call and `mitigate`) and `ctx.slot` (auto-attack swings only) let modifiers
    tell what they are modifying. During an auto-attack swing (`ctx.slot != None`), modifiers of an equipped item with
    `dice` (and of its item affects) apply only when that item is `ctx.weapon`; outside swings they apply like any worn
    item, so a staff can boost spells.
  - **Slot families.** A required rules export `slot_families()` returns `dict[str, list[str]]`: family name to ordered
    concrete slots. It replaces the `<wanted>_` prefix matching. `item(wear=...)` names either a family or a concrete
    slot. Validation at load: family names are identifiers not in `equipment_slots()`, every listed slot is in
    `equipment_slots()`, no slot is in two families, no family is empty or has duplicates, and every item's `wear` is a
    known slot or family.
  - **Per-actor slots.** A required rules export `actor_slots(ctx)` returns the concrete slots `ctx.actor` has, which
    must be a subset of `equipment_slots()` (anything else is a bad return value; the engine then uses
    `equipment_slots()`). `equipment_slots()` stays ctx-less: it is the world's superset, used for validation, the lint
    and chain order. The answer is cached on the actor and recomputed after its traits, actor affects or level change,
    on reload and on restore. Mob prototypes declare no slots; rules read `ctx.actor`.
  - **Slot cost.** `item(slot_cost = n)` (default 1, `>= 1`). A required rules export `slot_cost(ctx)` returns the
    effective cost (`>= 1`) of `ctx.item` for `ctx.actor`; the template returns `ctx.item.slot_cost`. An item occupies
    one concrete slot but counts its effective cost against its family's capacity (the number of the family's slots the
    actor has). Cost is ignored for slots outside any family.
  - **Wearing never auto-removes more than one item.** When equipping, the engine works out which items would have to
    come off for the new item to fit. If none, it equips. If exactly one, it swaps it out, but only if the `REMOVE`
    restriction allows removing it; a refusal refuses the whole wear and nothing moves. If two or more, the wear is
    refused with a message naming them. If equally good choices would take off different items (another ring with every
    ring slot full), the wear is refused with "There's no room for X. Remove something first." without listing the
    candidates, since a family can have many slots; the player picks. An item whose effective cost exceeds the family's
    capacity can't be worn at all.
  - **Fit check and inactive items.** When capacity shrinks (a debuff raises a cost, a race change drops a slot), the
    engine never removes anything. Instead `World.refreshFit(actor)` marks items **inactive**: those in slots the actor
    no longer has, and, walking each family in slot order, those whose cost no longer fits. An inactive item stays
    equipped, keeps its weight and slot, and contributes nothing as a worn item: no deltas, no modifiers, no worn-scope
    item affects, no untimed `equip_affects`, no attack. Its `carried`-scope item affects still reach the holder. It is
    shown with `(inactive)` in `equipment`, the wearer is told when it stops and starts working, and the state is
    derived, never persisted. This is curse-safe: a cursed item is never forcibly moved.
  - **Item attacks.** Each combat round, every active equipped item with `dice` swings, in `actor_slots` order, plus one
    unarmed swing when the actor has a `wield` slot and nothing is equipped in it. Each swing is today's auto-attack
    (reach check, `before_attack`, `to_hit` and `damage` chains, trace, messages) with `ctx.weapon` and `ctx.slot` set.
    Shields are ordinary `stat_deltas(armor = ...)` items with no `dice`. Off-hand penalties and block chances are world
    rules reading `ctx.slot`. The `wield` command accepts items whose `wear` is `wield`, the family containing `wield`,
    or another slot of that family.
- **New `DESIGN_NEEDED` entries already filed:** `### Combat procs: items and affects that cast on attack` and `###
  Typed bonus damage on attacks`. Do not resolve them here.
- **Specification Impact:** §3.3 (Item, Affect), §4.2 (`item()`, `item_affect()`), §4.3 (`damage` and `apply_affect`
  primitives), §4.5 (`ctx` fields, item, affect and actor views), §4.6 (rules exports table, signatures, dry run,
  modifier chain "whose chain" and order, encumbrance and equipment paragraphs, auto-attacks), §4.9 (`wear`, `wield`,
  `remove`, `equipment`), §4.15 (plugin facade), §7.4 (M18 row).
- **Decision Record Updates:** None. The maintainer approved this design, so spec text is edited directly. Do not add
  `DECISIONS.md` entries.

---

### Standing rules for every phase

- Run Gradle with `BypassSandbox: true` (`./gradlew check`, `./gradlew markdownFormat`, `./gradlew markdownCheck`).
- Kotlin warnings are errors. Every phase ends with `./gradlew check` passing.
- No spec section numbers or decision IDs in code comments or KDoc. Explain behavior directly.
- Never read, search or edit `local-world/`.
- The engine is unreleased: no migrations, no back-compat shims, no deprecated aliases.
- After editing any Markdown file, run `./gradlew markdownFormat`.
- Test worlds in `script` tests use the template world's rules via `TestWorlds` (`script/src/testFixtures/.../
  TestWorlds.kt`, reading the `/template-rules/` resource). A rules export added to `template-world/rules/combat.star`
  is therefore seen by every script test world.

---

## Phase 1: Vital and resist deltas on items and affects

### Objective & Scope

- **In Scope:**
  - `maxVitalDeltas` and `resistDeltas` on `ItemProto`, `ItemAffect`, `Affect`, `Effect.ApplyAffect`, `HeldAffect` and
    `HeldEffects`.
  - `Actor.max` and `Actor.effectiveResist` include them.
  - Vital clamping after holdings refresh, actor-affect apply/end, and restore.
  - Persistence of the new `Affect` fields (actor affects and item instance affects).
- **Out of Scope:** Starlark and plugin surface (Phase 6), slots and combat.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/model/Creation.kt` (`Trait`: field naming to mirror)
  - `core/src/main/kotlin/gups/core/world/Restore.kt` (`restoreActorNumbers`, `restoreItem`)
  - `persist/src/main/kotlin/gups/persist/SqlitePersistence.kt` (the restore path and M17's post-restore
    `refreshHoldings` call)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`ItemProto`, `ItemAffect`)
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Affect`, `Actor.max`, `Actor.effectiveResist`,
    `recomputeTraits`)
  - `core/src/main/kotlin/gups/core/model/Effects.kt` (`Effect.ApplyAffect`)
  - `core/src/main/kotlin/gups/core/world/Holdings.kt` (`HeldEffects`, `HeldAffect`, `refreshHeld`, `refreshHoldings`)
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (`applyAffect`, `endAffect`, `applyItemAffect`, `applyEffect`)
  - `core/src/main/kotlin/gups/core/world/Restore.kt`, `core/src/main/kotlin/gups/core/world/Vitals.kt`
  - `clampVitals` call sites after `recomputeTraits`: `core/src/main/kotlin/gups/core/world/Reconcile.kt`,
    `core/src/main/kotlin/gups/core/world/Verbs.kt`, `core/src/main/kotlin/gups/core/world/World.kt`
  - `persist/src/main/kotlin/gups/persist/Codec.kt` (`AffectJson`, `restoreAffects`, `affectsJson`, M17's item-affect
    codec)
  - `persist/src/main/kotlin/gups/persist/SqlitePersistence.kt`, `persist/src/main/kotlin/gups/persist/Repro.kt` (call
    the clamp after restore)
  - Tests: `core/src/test/kotlin/gups/core/world/HoldingsTest.kt`, `core/src/test/kotlin/gups/core/world/VitalsTest.kt`,
    `persist/src/test/kotlin/gups/persist/CodecTest.kt`
  - `docs/SPEC.md`
- **Do Not Touch:** `script/`, `plugin-api/`, slot or combat code.

### Specification & Decision Record Changes

- **§3.3, Item:** where worn items' `stat_deltas` and `sense_deltas` are described, add "`max_vital_deltas` (vital name
  to a change in that vital's maximum) and `resist_deltas` (damage type to a change in resistance) work the same way."
- **§3.3, Affect:** add `max_vital_deltas` and `resist_deltas` to the affect's fields, with the same meaning, for actor
  affects and item affects alike.
- Wherever §3.3 or §4.5 defines effective maximum vitals and effective resists, state they are base + traits + actor
  affects + item contributions (worn items and reaching item affects), the maximum clamped at 0, and that "when an
  effective maximum drops, the current value is lowered to it."

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Protos.kt — ItemProto, after senseDeltas:
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  val resistDeltas: Map<DamageType, Int> = emptyMap(),
  // ItemAffect: add the same two fields after senseDeltas.

  // Entities.kt — Affect: add as the last two params (after M17's `applies`):
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  val resistDeltas: Map<DamageType, Int> = emptyMap(),

  // Effects.kt — Effect.ApplyAffect: add the same two fields after senseDeltas.

  // Holdings.kt
  class HeldEffects(
    /* existing */ ...,
    val maxVitalDeltas: Map<String, Int> = emptyMap(),
    val resistDeltas: Map<DamageType, Int> = emptyMap(),
  )
  // HeldAffect: add maxVitalDeltas and resistDeltas.

  // Vitals.kt
  /** Lowers each of [a]'s current vitals to its effective maximum and refreshes its condition. */
  fun World.clampVitals(a: Actor)
  ```
- **Algorithmic Steps & Invariants:**
  1. `Actor.max(vital)` = `maxOf(0, base + traitMaxVitalDeltas + Σ affects.maxVitalDeltas +
     heldEffects.maxVitalDeltas)`. `effectiveResist(type: DamageType)` = base + `traitResistDeltas[type.name]` + Σ
     affects + `heldEffects.resistDeltas[type]`. The `String` overload delegates to the `DamageType` one. Update KDoc.
  2. `refreshHeld`: wherever it sums prototype and `HeldAffect` `statDeltas`, sum `maxVitalDeltas` and `resistDeltas`
     the same way. `Item.heldAffects()` copies both fields from `ItemAffect` and from instance `Affect`.
  3. `clampVitals(a)`: if `!a.inWorld` return. For each vital, if `current > a.max(name)` set it and mark `a.dirty`.
     Then `refreshCondition(a)` (use whatever `Vitals.kt` already calls after a vital change; mirror `changeVital`).
     Replace the clamp loop at the end of `Actor.recomputeTraits` with nothing: callers that run in the world call
     `clampVitals` (see step 4). `recomputeTraits` must not touch vitals any more, because restore needs the saved
     current values to survive until items are placed.
  4. Call `clampVitals(a)`:
     - at the end of `refreshHoldings(a)`;
     - in `applyAffect` after the affect is added, and in `endAffect` after it is removed;
     - after every in-world `recomputeTraits` call: `Reconcile.kt` (both), `Verbs.kt` (all three), `World.kt` (spawn
       paths, after the actor is in the world);
     - after `applyItemAffect`/`removeItemAffect`/`expireItemAffect` (M17 already calls `refreshHoldings` there; no
       extra call needed if so).
  5. **Restore.** `restoreActorNumbers` sets `v.current = saved.current` without clamping. Update its KDoc ("current
     values are clamped once restore has placed the actor's items"). In the persist restore path, right after M17's
     post-restore `refreshHoldings(a)` call for each restored actor, `clampVitals` already runs via step 4. Confirm that
     an actor restored with no items still gets `refreshHoldings`; if M17 only refreshes actors that hold items, call
     `clampVitals` for every restored actor instead. Do the same in `Repro.kt`.
  6. `applyEffect` for `Effect.ApplyAffect` and the `Affect(...)` constructions in `applyAffect`/`applyItemAffect` pass
     the new fields. `applyAffect` and `applyItemAffect` gain `maxVitalDeltas` and `resistDeltas` parameters (named,
     defaulting to empty, placed last).
  7. **Persistence:** `AffectJson` gains `maxVitalDeltas: Map<String, Int> = emptyMap()` and `resistDeltas: Map<String,
     Int> = emptyMap()`. Encode resist keys by `DamageType.name`; decode with `DamageType(k)`. Use the same JSON type
     for M17's item instance affects so both round-trip.
- **Invariants:** an item or affect that raises a maximum never raises the current value. Removing it lowers the current
  value only if it is now above the maximum.

### Test & Documentation Requirements

- `HoldingsTest.kt`: a worn item with `maxVitalDeltas = mapOf("hp" to 10)` raises `max("hp")`; removing it clamps
  current; a `carried` item affect with `resistDeltas` counts from the inventory; a `worn` one only while equipped.
- `VitalsTest.kt` (or `CombatTest.kt`): an actor affect with `maxVitalDeltas` raises the max and clamps on expiry; one
  with `resistDeltas` changes `effectiveResist`.
- `CodecTest.kt`: actor affects and item instance affects with both maps round-trip; a player saved at `hp` above base
  max while wearing a `+hp` item restores with that `hp` intact.
- KDoc on every new field and on `clampVitals`.

### Exit Conditions

- [] `./gradlew check` passes.
- [] The restore test above passes (it fails before step 5).

---

## Phase 2: One `damage` chain, `ctx.damage_type`, `ctx.slot` and weapon-scoped modifiers

### Objective & Scope

- **In Scope:**
  - `RulesContext.damageType` and `RulesContext.slot`.
  - Spell `damage(...)` through the caster's `damage` chain.
  - Chain steps that know which item they came from, and the weapon-scoping rule.
  - `ctx.damage_type` and `ctx.slot` on the Starlark rules ctx.
- **Out of Scope:** multiple swings (Phase 5). In this phase, the single existing swing sets `slot = "wield"`.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Rules.kt:81` (`chain`), `:42` (`chainSteps`, as rewritten by M17 to read
    `heldEffects.modifierSteps`)
  - `script/src/main/kotlin/gups/script/Rules.kt:100-125` (existing `RulesCtx` `weapon`, `band`, `skill` fields to
    mirror)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt` (`RulesContext`)
  - `core/src/main/kotlin/gups/core/world/Rules.kt`
  - `core/src/main/kotlin/gups/core/world/Holdings.kt` (`modifierSteps` element type)
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (`autoAttack` ctx, `Effect.Damage`)
  - `script/src/main/kotlin/gups/script/Rules.kt` (`RulesCtx`, and `mitigate` ctx)
  - Tests: `core/src/test/kotlin/gups/core/world/ModifierChainTest.kt`, `core/src/test/kotlin/gups/core/world/
    CombatTest.kt`, `script/src/test/kotlin/gups/script/RulesTest.kt`
  - `cli/src/test/resources/api.golden.json`, `docs/api/api-1.json` (new ctx fields)
  - `docs/SPEC.md`
- **Do Not Touch:** slot code, `plugin-api/`.

### Specification & Decision Record Changes

- **§4.6, "The rules `ctx`":** add `damage_type` (the damage type as a string in `damage` chains and `mitigate`,
  otherwise `None`) and `slot` (the slot of the swinging item in an auto-attack swing, `"wield"` for an unarmed swing,
  otherwise `None`).
- **§4.6, "Whose chain":** "the attacker's for `to_hit` and `damage`" becomes "the attacker's for `to_hit` and `damage`
  (the caster is the attacker for spell damage)".
- **§4.6, after "Chain order":** add "**Weapons in swings:** during an auto-attack swing (`ctx.slot` is set), the
  modifiers of a worn item with `dice`, and of its item affects, apply only when that item is `ctx.weapon`. Outside
  swings, worn weapons contribute like any worn item."
- **§4.3:** delete the sentence "Spell `damage(...)` effects don't go through the `damage` modifier point; only
  auto-attacks do (§4.6)." In the §4.6 rules table, change `damage(ctx)`'s description to "Raw auto-attack damage, an
  int; every damage event then runs the `damage` modifier chain."
- **§4.3, `damage(dice, type)` primitive:** "The rolled amount goes through the caster's `damage` modifier chain
  (`ctx.skill` and `ctx.damage_type` set) and then `mitigate`. A negative chain result counts as 0."
- **§4.6, Signatures:** `mitigate` is called with `ctx.damage_type` set to the same type it receives.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // RulesEngine.kt — RulesContext, add as the last two params:
  /** Damage type of the damage being rolled, modified or mitigated, or null. */
  val damageType: DamageType? = null,
  /** Equipment slot of the swinging item in an auto-attack swing, or null outside swings. */
  val slot: String? = null,

  // Holdings.kt — replace `List<Pair<String, Modifiers>>`:
  /** Modifiers one held item contributes, with the item they come from. */
  class HeldModifiers(val source: String, val item: Item, val modifiers: Modifiers)
  val modifierSteps: List<HeldModifiers> = emptyList()

  // Rules.kt
  fun World.damageChain(c: RulesContext, base: Int = rules.damage(c)): ChainResult<Int>
  ```
- **Algorithmic Steps & Invariants:**
  1. `chainSteps(point, actor)` becomes `chainSteps(point, c: RulesContext)` (it needs `c.slot` and `c.weapon`). When
     appending held item steps: if `point` is `TO_HIT` or `DAMAGE`, `c.slot != null`, the step's `item.proto.dice !=
     null`, `item.location is ItemLocation.Equipped`, and `item !== c.weapon`, skip the step. Every other step is
     unchanged.
  2. `Effect.Damage` in `applyEffect`:
     - `val c = RulesContext(this, caster, t, skill = skill, damageType = effect.type)`;
     - `val raw = damageChain(c, effect.dice.roll(rng))`, then `traceRules(caster, raw.text)`;
     - `val losses = rules.mitigate(c, raw.value.coerceAtLeast(0), effect.type)`;
     - `damage(t, losses, effect.type, caster)` as before.
  3. `autoAttack`: build the ctx with `damageType = weapon?.proto?.damageType ?: schema.unarmed` and `slot = "wield"`.
     The `to_hit` chain gets the same ctx. Clamp the `damage` chain result at 0 before `mitigate` if it isn't already.
  4. `RulesCtx` in `script/.../Rules.kt`: add `damage_type` (string or `None`) and `slot` (string or `None`)
     `structField` methods mirroring `weapon`/`band`. Register both in the API model wherever `weapon` and `band` are
     listed, then regenerate the golden files (`./gradlew :cli:test` reports the diff; follow the procedure in
     `ApiCompatibilityTest`/`ApiDiffTest` for updating `api.golden.json` and `docs/api/api-1.json`).

### Test & Documentation Requirements

- `ModifierChainTest.kt`: a worn sword with a `damage` modifier applies to its own swing, not to a swing whose
  `ctx.weapon` is another item with dice (build a second ctx with `slot = "offhand"`), and does apply to spell damage; a
  ring's `damage` modifier applies to all three.
- `CombatTest.kt`: a spell `damage` effect is changed by a room `damage` modifier and by a caster affect's modifier;
  `@trace` text includes the chain.
- `RulesTest.kt`: `ctx.damage_type` is readable in `damage` and `mitigate`; `ctx.slot == "wield"` in `to_hit`.
- KDoc on the new `RulesContext` fields and `HeldModifiers`.

### Exit Conditions

- [] `./gradlew check` passes, including the API compatibility test with the updated golden files.
- [] Spell damage visibly passes through the chain in a trace test.

---

## Phase 3: Required rules exports `slot_families`, `actor_slots` and `slot_cost`

### Objective & Scope

- **In Scope:**
  - `RulesEngine.slotFamilies()`, `actorSlots(c)`, `slotCost(c)` and their Starlark implementations.
  - `RulesExport` entries and specs, dry-run checks, `item(wear=)` validation.
  - `ItemProto.slotCost`, `item(slot_cost=)`, and `ItemView.slot_cost` (needed by the template's `slot_cost`).
  - The template world, robot-world and `FixtureRules` define the three exports.
- **Out of Scope:** using the exports in wear or combat (Phases 4-5).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Loader.kt:699` (`missingRulesExports`)
  - `script/src/main/kotlin/gups/script/Ownership.kt` (the new exports are kernel exports: register no feature)
  - `script/src/main/kotlin/gups/script/Rules.kt:478` (`equipmentSlots` override: pattern for `run(...)`, return
    validation and keeping the last good answer)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt`
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`ItemProto.slotCost`)
  - `core/src/testFixtures/kotlin/gups/core/fixtures/FixtureRules.kt`, `core/src/test/kotlin/gups/core/world/
    Fixture.kt`, `core/src/test/kotlin/gups/core/world/PerceptionTest.kt` (any `RulesEngine` implementations)
  - `script/src/main/kotlin/gups/script/Rules.kt` (`StarlarkRules`, `RulesExport`, `RULES_EXPORTS`, `dryRunRules`)
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`item()` and `ITEM_SPEC`)
  - `script/src/main/kotlin/gups/script/Views.kt` (`ItemView.slot_cost`)
  - `template-world/rules/combat.star`, `samples/robot-world/rules/combat.star`
  - Tests: `script/src/test/kotlin/gups/script/RulesTest.kt`, `script/src/test/kotlin/gups/script/LoaderTest.kt`
  - `cli/src/test/resources/api.golden.json`, `docs/api/api-1.json`, `docs/SPEC.md`
- **Do Not Touch:** `KernelCommands.kt`, `Combat.kt`.

### Specification & Decision Record Changes

- **§4.6 rules table**, after `equipment_slots()`, add three kernel rows:
  - `slot_families()`: "`dict[str, list[str]]`, slot families: a family name to its ordered concrete slots."
  - `actor_slots(ctx)`: "`list[str]`, the slots `ctx.actor` has, a subset of `equipment_slots()`."
  - `slot_cost(ctx)`: "`int >= 1`, how many slots of its family `ctx.item` takes up when `ctx.actor` wears it."
- **§4.6 Signatures and results**, replace the `equipment_slots` bullet with:
  - "`equipment_slots()` and `slot_families()` get no ctx. They are asked once, and again after each reload.
    `equipment_slots()` lists every slot any actor can have, in the order the modifier chain visits worn items."
  - "`slot_families()`: family names are lowercase identifiers that are not slot names; each family lists one or more
    slots of `equipment_slots()`, with no repeats; no slot belongs to two families. A slot in no family stands alone."
  - "`actor_slots(ctx)` returns a subset of `equipment_slots()`. A slot outside it is a bad return value, and the actor
    then has every slot. The answer is kept until the actor's traits, affects or level change, a reload or a restore."
  - "`slot_cost(ctx)` gets `ctx.actor` and `ctx.item`. A result below 1 is a bad return value and counts as the item's
    `slot_cost`."
- **§4.6 dry run:** add "`slot_families()` and its rules above are checked; `actor_slots` runs for each synthetic actor
  and must return a subset of `equipment_slots()`; `slot_cost` runs for each synthetic actor against one item of each
  item prototype that has `wear`."
- **§4.2 `item()`:** add `slot_cost = 1` ("how many slots of its family the item takes up; at least 1") and state that
  `wear` names a slot of `equipment_slots()` or a family of `slot_families()`, checked at load.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // RulesEngine.kt, after equipmentSlots():
  /** Slot family names mapped to their ordered concrete slots. */
  fun slotFamilies(): Map<String, List<String>>
  /** The concrete slots `c.actor` has; a subset of [equipmentSlots]. */
  fun actorSlots(c: RulesContext): List<String>
  /** How many family slots `c.item` takes up when worn by `c.actor`; at least 1. */
  fun slotCost(c: RulesContext): Int

  // Protos.kt — ItemProto, after wear:
  /** How many slots of its family this item takes up when worn; at least 1. */
  val slotCost: Int = 1,
  // and in ItemProto's init (add one if none): require(slotCost >= 1)

  // script Rules.kt
  enum class RulesExport(val scriptName: String) {
    EQUIPMENT_SLOTS("equipment_slots"),
    SLOT_FAMILIES("slot_families"),
    ACTOR_SLOTS("actor_slots"),
    SLOT_COST("slot_cost"),
    /* existing entries */
  }
  ```
- **Algorithmic Steps & Invariants:**
  1. `FixtureRules` (and other test `RulesEngine`s): `slotFamilies() = mapOf("finger" to listOf("finger_l",
     "finger_r"))` if `SLOTS` has both finger slots, else `emptyMap()`; `actorSlots(c) = equipmentSlots()`; `slotCost(c)
     = c.item?.proto?.slotCost ?: 1`. Make them `open` so tests can override.
  2. `StarlarkRules`, mirroring `equipmentSlots()` at line 478:
     - `slotFamilies()`: run `SLOT_FAMILIES` with no ctx; the value must be a dict of string to list of strings. Keep
       the last good answer like `goodSlots`. Fall back to `emptyMap()`.
     - `actorSlots(c)`: run `ACTOR_SLOTS` with the ctx; must be a list of strings. Return it unfiltered: the subset
       check belongs to core (Phase 4) and the dry run. Fall back to `equipmentSlots()`.
     - `slotCost(c)`: run `SLOT_COST`; must be an int. A value below 1 is a bad return value (report it the same way
       `lagOf` reports negatives) and falls back to `c.item!!.proto.slotCost`.
  3. Add a `RulesExportSpec` for each, with params (`[]`, `["ctx"]`, `["ctx"]`), returns, and the doc strings from the
     spec rows above. Do not register them in `Ownership.kt`, so `missingRulesExports` requires them always.
  4. `dryRunRules` (`script/.../Rules.kt:1100`), next to the existing `rules.equipmentSlots()` call:
     - `val slots = rules.equipmentSlots()`; `val families = rules.slotFamilies()`;
     - for each family: name matches `[a-z][a-z0-9_]*`; `name !in slots` ("slot_families: family 'x' is also a slot");
       list non-empty and without duplicates; each slot in `slots` (with `didYouMean`); no slot already claimed by an
       earlier family ("slot 'x' is in both 'a' and 'b'");
     - for every `ItemProto` with `wear != null`: `wear in slots || wear in families`, else "item 'label': unknown wear
       slot or family 'x'" with `didYouMean`;
     - for each synthetic actor: `rules.actorSlots(RulesContext(world, a))` must be a subset of `slots`, else a problem
       naming the actor and the stray slots;
     - for each synthetic actor and each item prototype with `wear`: spawn (or construct, as the dry run does for other
       item checks) one item and call `rules.slotCost(RulesContext(world, a, item = it))`. Add each failure to
       `problems` in the existing style.
  5. `item()` in `Declarations.kt`: add `slot_cost` (`Param(name = "slot_cost", defaultValue = "1", named = true,
     positional = false)`), fail the load if below 1 or if given without `wear`, and pass it to `ItemProto`. Add it to
     `ITEM_SPEC` with a doc string.
  6. `ItemView`: add `slot_cost` (`structField`, int) beside `wear` (`Views.kt:619`).
  7. `template-world/rules/combat.star`: rewrite the `equipment_slots` comment (no more prefix matching) and add:

     the three functions shown in the **Template rules** block below.

  8. `samples/robot-world/rules/combat.star`: add `slot_families()` returning `{}`, the same `actor_slots` and
     `slot_cost`.

  9. Update the API golden files for the new exports, `item(slot_cost=)` and `ItemView.slot_cost`.

  10. Existing tests that relied on prefix matching or on unknown `wear` values: `core/src/test/kotlin/gups/core/world/
      ItemCommandsTest.kt:54` and `core/src/test/kotlin/gups/core/world/CommandPipelineTest.kt:139` (`wear = "finger"`;
      these use `FixtureRules`, whose `finger` family keeps them working), and `script/src/test/kotlin/gups/
      script/LinterTest.kt:268` (`wear = "finger"` against the template rules; change it to `wear = "ring"`). Grep
      `core/src/test`, `script/src/test` and `cli/src/test` for other `wear =` values not in the template's
      `equipment_slots()` or families and fix them.

**Template rules** (`template-world/rules/combat.star`, step 7):

```python
def slot_families():
    # A family's slots fill in order. wear = "<family>" takes the first free one.
    return {
        "ring": ["finger_l", "finger_r"],
        "hand": ["wield", "offhand"],
    }

def actor_slots(ctx):
    # Every actor has every slot. Return fewer (or a race's extras) based on ctx.actor.
    return equipment_slots()

def slot_cost(ctx):
    return ctx.item.slot_cost
```

### Test & Documentation Requirements

- `RulesTest.kt`: a rules package missing any one of the three exports fails to load naming it; each dry-run problem
  above is reported (family colliding with a slot, unknown slot in a family, slot in two families, empty family,
  `actor_slots` returning an unknown slot, `slot_cost` returning 0).
- `LoaderTest.kt`: `item(wear = "ring")` loads with the template rules; `item(wear = "rign")` fails with a did-you-mean
  for `ring`; `slot_cost = 0` fails; `slot_cost` without `wear` fails.
- KDoc on the three `RulesEngine` methods and `ItemProto.slotCost`.

### Exit Conditions

- [] `./gradlew check` passes, including template-world and robot-world tests and the API compatibility test.
- [] `gups lint` (the CLI lint task used by existing tests) passes on both worlds.

---

## Phase 4: Slot resolution, per-actor slot cache, and `wear`/`wield`

### Objective & Scope

- **In Scope:**
  - Core helpers for families, per-actor slots (cached) and effective cost.
  - `wear`/`wield` slot choice, the single-item swap through the `REMOVE` restriction, and refusal naming blockers.
  - Cache invalidation points.
- **Out of Scope:** inactive items and the fit check (Phase 5 builds on this), attacks (Phase 6).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt:725` (`planWear`), `:1183` (`slotFor`), `:405` (`equipment`)
  - M17's `remove` command handler, for how it builds the `REMOVE` `RestrictionRequest`
  - `core/src/main/kotlin/gups/core/world/Reconcile.kt:122` (`slotCache = null`)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Slots.kt` (new)
  - `core/src/main/kotlin/gups/core/world/World.kt` (`slotFamilyCache`)
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Actor.slotCache`)
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt`
  - `core/src/main/kotlin/gups/core/world/KernelMessages.kt`
  - `core/src/main/kotlin/gups/core/world/Reconcile.kt`
  - Invalidation call sites only: `core/src/main/kotlin/gups/core/world/Combat.kt` (`applyAffect`, `endAffect`),
    `core/src/main/kotlin/gups/core/world/Leveling.kt`, `core/src/main/kotlin/gups/core/world/Verbs.kt`, the persist
    restore path (`persist/.../SqlitePersistence.kt`, `persist/.../Repro.kt`)
  - Tests: `core/src/test/kotlin/gups/core/world/SlotsTest.kt` (new), `core/src/test/kotlin/gups/core/world/
    ItemCommandsTest.kt`
  - `docs/SPEC.md`
- **Do Not Touch:** `autoAttack`, `script/`, `plugin-api/`.

### Specification & Decision Record Changes

- **§4.6, Equipment paragraph**, replace the sentences from "Slots come from" to "takes off an item in any slot, worn or
  wielded." with:

  "Slots come from `rules.equipment_slots()`, the slots an actor has from `rules.actor_slots(ctx)`, and slot families
  from `rules.slot_families()`. An item's `wear` names a slot or a family; a family's slots that the actor has are tried
  in order, and the first free one is used. An item takes up `rules.slot_cost(ctx)` of its family's slots, and a family
  holds as much as the number of its slots the actor has. Wearing works out which worn items would have to come off for
  the item to fit: if none, the item is worn; if exactly one, it is taken off first, unless the `remove` restriction
  refuses, which refuses the wear; if more than one, the wear is refused and names them. If equally good choices would
  take off different items, the wear is refused with no list ("There's no room"), and the player removes what they
  choose. An item that costs more than its family holds can't be worn. `wield` takes items whose `wear` is `wield`, the
  family that contains `wield`, or another slot of that family; `wear` takes any item with `wear`; `remove` takes off an
  item in any slot."

- **§4.9, `wear`/`wield`:** reflect the same rules in the command descriptions.

### Implementation Blueprint

- **Signatures & Types** (`Slots.kt` unless noted):

  ```kotlin
  /** The rules' slot families, asked once and again after each reload. */
  fun World.slotFamilies(): Map<String, List<String>>
  /** The family containing [slot], or null for a slot in no family. */
  fun World.familyOfSlot(slot: String): String?
  /** The family [wear] names, or contains, or null for a standalone slot. */
  fun World.familyOfWear(wear: String): String?
  /** [a]'s slots: the rules' answer filtered to `equipmentSlots()`, cached on [a]. */
  fun World.actorSlots(a: Actor): List<String>
  /** Forgets [a]'s cached slots, so the next [actorSlots] asks the rules again. */
  fun World.forgetActorSlots(a: Actor)
  /** [family]'s slots that [a] has, in family order. */
  fun World.familySlots(a: Actor, family: String): List<String>
  /** The effective family cost of [item] worn by [a], at least 1. */
  fun World.slotCost(a: Actor, item: Item): Int
  /** Where [item] would go on [a] and what would have to come off, or null if it can't be worn at all. */
  internal fun World.planSlot(a: Actor, item: Item): SlotPlan?
  /**
   * [ambiguous] is true when several equally small choices would take off different items; the wear is then refused
   * and the player picks what to remove.
   */
  internal class SlotPlan(val slot: String, val displaced: List<Item>, val ambiguous: Boolean = false)

  // World.kt
  internal var slotFamilyCache: Map<String, List<String>>? = null
  // Entities.kt — Actor
  internal var slotCache: List<String>? = null
  ```
- **Algorithmic Steps & Invariants:**
  1. `slotFamilies()`: mirror `equipmentSlots()` in `Rules.kt:68` with `slotFamilyCache`; clear it next to `slotCache =
     null` in `Reconcile.kt`.
  2. `actorSlots(a)`: return `a.slotCache` if set; otherwise ask `rules.actorSlots(RulesContext(this, a))` inside
     `guarded(...)`, keep only slots in `equipmentSlots()` (a stray slot is reported through the rules diagnostic path
     and dropped; if every slot is stray, use `equipmentSlots()`), cache and return.
  3. Invalidate with `forgetActorSlots(a)`: in `recomputeTraits` callers (same sites as Phase 1 step 4), in
     `applyAffect` and `endAffect`, after `levelUp`/`levelDownStep` change `a.level` (`Leveling.kt:93`, `:219`), for
     every actor in `applyPrototypes` (`Reconcile.kt`), and at the end of restore. Phase 5 adds the fit check at the
     same sites; keep the invalidation calls adjacent so Phase 5 can put `refreshFit` beside them.
  4. `slotCost(a, item)`: `rules.slotCost(RulesContext(this, a, item = item))` inside `guarded`, falling back to
     `item.proto.slotCost`, then `coerceAtLeast(1)`. Not cached: stats change it.
  5. `planSlot(a, item)`:
     - `val wear = item.proto.wear ?: return null`;
     - `val family = familyOfWear(wear)`;
     - **standalone slot** (`family == null`): if `wear !in actorSlots(a)` return null; return `SlotPlan(wear,
       listOfNotNull(a.equipped(wear)))`;
     - **family**: `val slots = familySlots(a, family)`; `val candidates = if (wear == family) slots else
       listOf(wear).filter { it in slots }`; if `candidates` is empty return null; `val cost = slotCost(a, item)`; if
       `cost > slots.size` return null;
     - for each candidate in order, compute the displaced set: start with the item already in that candidate slot (if
       any); then, while the family's total cost of remaining worn items (excluding displaced ones) plus `cost` exceeds
       `slots.size`, add the worn family item in the **last** occupied slot not yet displaced;
     - keep the candidates whose displaced set is smallest. If that size is 0, return the first of them. Otherwise, if
       they all displace the same set of items, return the first of them. Otherwise the choice is the player's: return
       the first of them with `ambiguous = true`. Example: every ring slot full and another ring put on gives one
       candidate per ring slot, each displacing a different ring, so it is ambiguous; a greatsword put on over a lone
       sword displaces the sword from either candidate, so it is not.
  6. `planWear` (`KernelCommands.kt:725`):
     - `wield` accepts the item only if `familyOfWear(wear)` equals `familyOfSlot("wield")` (when that is non-null) or
       `wear == "wield"`; otherwise `WEAR_CANT_WIELD`;
     - **validate**: `val plan = w.planSlot(actor, item) ?: return WEAR_CANT_WEAR / WEAR_CANT_WIELD`; if
       `plan.ambiguous` return `WEAR_NO_ROOM`; if `plan.displaced` has two or more items return `WEAR_REMOVE_FIRST` with
       `items` joined by the existing list-joining helper; run the `WEAR` restriction with `slot = plan.slot` as today;
       if `plan.displaced.size == 1`, run the `REMOVE` restriction for that item exactly as M17's `remove` handler
       builds it, and return its refusal if any;
     - **resolve**: re-plan (state may have changed); if the plan differs from what validated (different slot or
       displaced set), refuse with the same messages rather than acting; move the displaced item (if any) to `Carried`,
       telling `WEAR_STOP`; move the item to `Equipped(actor.id, plan.slot)`; use the wield messages when
       `familyOfSlot(plan.slot) == familyOfSlot("wield")` or `plan.slot == "wield"`.
  7. Delete `World.slotFor`. Update the `planWear` KDoc.
  8. `equipment` (`KernelCommands.kt:405`): list items in `actorSlots(actor)` order, then any other occupied slots by
     name.
  9. `KernelMessages.kt`: add `WEAR_REMOVE_FIRST = msg("wear.remove_first", "You'd need to remove {items} first.",
     "items", "items")` and `WEAR_NO_ROOM = msg("wear.no_room", "There's no room for {item}. Remove something first.",
     "item")`.

### Test & Documentation Requirements

- `SlotsTest.kt` with a `FixtureRules` subclass declaring families `ring = [finger_l, finger_r]`, `hand = [wield,
  offhand]`:
  - two rings fill `finger_l` then `finger_r`; a third is refused with `WEAR_NO_ROOM`, and nothing moves; after removing
    either, the third ring goes into the freed slot; with an `actorSlots` override giving four ring slots, a fifth ring
    is refused the same way;
  - a ring with `wear = "finger_r"` (a concrete slot) over a worn `finger_r` ring swaps it out (single displaced);
  - a two-handed item (`slotCost = 2`, `wear = "hand"`) with a sword in `wield` swaps out the sword; with a sword and a
    shield worn, it is refused naming both, and nothing moves;
  - with the greatsword worn, wearing a shield (`wear = "offhand"`) swaps out the greatsword;
  - a `slotCost(c)` override returning 1 for actors with `str >= 18` lets the greatsword and shield be worn together;
  - an `actorSlots` override returning four `hand` slots lets two two-handed items be wielded; an override returning a
    stray slot drops it;
  - an item whose cost exceeds its family's capacity can't be worn;
  - `actorSlots` is cached (the override counts calls) and asked again after `forgetActorSlots`, an affect, and reload.
- `ItemCommandsTest.kt`: a `REMOVE` restriction refusing a worn helmet makes `wear` of another helmet fail with the
  restriction's message and leaves both items where they were; `wield` of a `wear = "hand"` item uses the wield
  messages; `equipment` order follows `actorSlots`.
- KDoc on all new functions.

### Exit Conditions

- [] `./gradlew check` passes.
- [] No reference to prefix slot matching remains (`grep -rn '"\${wanted}_"' core` finds nothing).

---

## Phase 5: The fit check and inactive items

### Objective & Scope

- **In Scope:** `World.refreshFit(actor)`, `Actor.inactiveSlots`, excluding inactive items from `HeldEffects` and
  untimed `equip_affects`, the `(inactive)` display and messages, and every trigger.
- **Out of Scope:** attacks (Phase 6 reads `inactiveSlots`), scripting surface.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Slots.kt` (Phase 4)
  - `core/src/main/kotlin/gups/core/world/Combat.kt:396-410` (`applyEquipAffects`, `removeEquipAffects`)
  - `core/src/main/kotlin/gups/core/world/Items.kt:64` (`updateEncumbrance`)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Slots.kt`
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Actor.inactiveSlots`)
  - `core/src/main/kotlin/gups/core/world/Holdings.kt` (`refreshHeld`, `refreshHoldings`)
  - `core/src/main/kotlin/gups/core/world/Items.kt` (`index`/`unindex` equip affects, `updateEncumbrance`)
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (`applyAffect`/`endAffect` triggers)
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt` (`equipment` display)
  - `core/src/main/kotlin/gups/core/world/KernelMessages.kt`
  - Tests: `core/src/test/kotlin/gups/core/world/SlotsTest.kt`, `core/src/test/kotlin/gups/core/world/ HoldingsTest.kt`
  - `docs/SPEC.md`
- **Do Not Touch:** `script/`, `plugin-api/`, `persist/` (the state is derived and not saved).

### Specification & Decision Record Changes

- **§4.6, Equipment paragraph**, append: "When an actor's slots or costs change so a worn item no longer fits (a slot it
  lost, or a family over capacity, walked in slot order), nothing is taken off. The item becomes inactive: it stays worn
  and keeps its weight, but adds nothing as a worn item (no deltas, modifiers, `worn` item affects or `equip_affects`)
  and does not attack. Its `carried` item affects still reach the holder. It works again as soon as it fits. The wearer
  is told when an item stops and starts working, and `equipment` marks it."
- **§3.3, Item:** note that an equipped item may be inactive, and that this is worked out again rather than saved.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Entities.kt — Actor
  /** Worn slots whose items don't fit the actor's current slots or capacity; worked out, not saved. */
  var inactiveSlots: Set<String> = emptySet()
    internal set
  internal var refittingSlots = false
  /** True if [slot] holds an item that counts as worn. */
  fun isActiveSlot(slot: String): Boolean = slot !in inactiveSlots

  // Slots.kt
  /** Recomputes which of [a]'s worn items fit, and applies the change. */
  fun World.refreshFit(a: Actor)

  // KernelMessages.kt
  val ITEM_INACTIVE = msg("wear.inactive", "{item} no longer fits you, and stops working.", "item")
  val ITEM_ACTIVE = msg("wear.active", "{item} fits you again.", "item")
  val EQUIPMENT_INACTIVE = msg("equipment.inactive", "  <{slot}> {item} (inactive)", "slot,item", "item")
  ```
- **Algorithmic Steps & Invariants:**
  1. `refreshFit(a)`: return if `!a.inWorld` or `a.refittingSlots`. Set `a.refittingSlots = true` in a `try/finally`.
     Repeat up to `a.equipmentIndex.size + 1` passes, stopping when a pass changes nothing:
     - `val slots = actorSlots(a).toSet()`;
     - `next` = every worn slot not in `slots`;
     - for each family in `slotFamilies()` order: walk `familySlots(a, family)` in order, keeping `used = 0`; for each
       slot holding an item, `val cost = slotCost(a, item)`; if `used + cost > capacity` add the slot to `next`, else
       `used += cost`;
     - if `next == a.inactiveSlots` stop; otherwise apply the change (step 2) and continue. Passes repeat because an
       item going inactive can lower stats that `slot_cost` reads. The bound guarantees an end; whatever the last pass
       produced stands.
  2. Applying a change from `old` to `next`:
     - for each slot newly inactive: `removeEquipAffects(a, item)`, tell `ITEM_INACTIVE`;
     - for each slot newly active: re-apply only the item's **untimed** `equip_affects` (the ones `removeEquipAffects`
       took off; add an `untimedOnly` parameter to `applyEquipAffects` or a sibling function), tell `ITEM_ACTIVE`.
       Re-activation must never re-grant a timed `equip_affects` entry;
     - `a.inactiveSlots = next`; `a.dirty = true`; `refreshHeld(a)`; `clampVitals(a)`.
  3. `refreshHeld` (M17): skip the worn-item pass (prototype deltas, modifiers, and each item's affects) for items in
     `a.inactiveSlots`. Still visit an inactive item's `carried`-scope affects and, if it is a container, its contents,
     as the carried pass does for inventory items.
  4. `Items.kt` `index`: for `Equipped`, call `applyEquipAffects` only when the slot is active. `unindex`: when removing
     from an inactive slot, skip `removeEquipAffects` and remove the slot from `inactiveSlots`.
  5. **Triggers.** Call `refreshFit(a)`:
     - at the end of `refreshHoldings(a)` (after `refreshHeld` and `updateEncumbrance`);
     - in `updateEncumbrance` when `was != a.encumbrance`, for the world-tick path;
     - beside every `forgetActorSlots` call added in Phase 4 (traits, actor affects, level, reload, restore).
       `refreshFit` itself calls `applyEquipAffects`/`removeEquipAffects`, which call `applyAffect`/`endAffect`, which
       call `forgetActorSlots` and `refreshFit`: the `refittingSlots` guard stops that re-entry. Clearing the actor's
       slot cache there is harmless.
  6. `equipment`: use `EQUIPMENT_INACTIVE` for inactive slots.
  7. `planSlot` (Phase 4) treats an inactive item like any worn item when computing displaced items: it still occupies
     its slot.
- **Invariants:** `refreshFit` never moves an item. `inactiveSlots` only ever names slots in `a.equipmentIndex`.

### Test & Documentation Requirements

- `SlotsTest.kt`:
  - strong actor wears greatsword (cost 1 via override) and a shield with `armor = 3`; a *Weakness* affect lowering
    `str` makes the shield (later in family order) inactive: `armor` drops, the item is still equipped, the wearer is
    told; the affect expiring makes it active again;
  - an `actorSlots` override that drops `finger_r` after a trait is granted makes that ring inactive, and its untimed
    `equip_affects` flag disappears; the ring's `carried` item affect still counts;
  - a `REMOVE` restriction (curse) never matters to the fit check: the cursed item becomes inactive, not removed;
  - a stat oscillation case (an item whose deltas raise the very stat its own fit depends on) terminates;
  - removing an inactive item clears its slot from `inactiveSlots`.
- `HoldingsTest.kt`: an inactive item contributes no `statDeltas`, `maxVitalDeltas`, modifiers or worn-scope affects.
- `ItemCommandsTest.kt` (or `SlotsTest.kt`): `equipment` shows `(inactive)`.
- KDoc on `inactiveSlots` and `refreshFit`.

### Exit Conditions

- [] `./gradlew check` passes.
- [] Grep confirms no code path moves an equipped item to `Carried` except `remove`, `wear`'s single swap, and M17's
  existing moves (death, transform to a slot-less proto, destroy).

---

## Phase 6: Every active item with dice attacks

### Objective & Scope

- **In Scope:** `autoAttack` swings once per active equipped item with `dice`, plus one unarmed swing; each swing has
  its own reach check, hook, chains and messages.
- **Out of Scope:** procs, typed bonus damage (filed in `DESIGN_NEEDED`).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Combat.kt:80-150` (current `autoAttack`)
  - `core/src/main/kotlin/gups/core/world/Slots.kt` (`actorSlots`, `inactiveSlots`)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Combat.kt`
  - Tests: `core/src/test/kotlin/gups/core/world/CombatTest.kt`, `core/src/test/kotlin/gups/core/world/TrackTest.kt`
    (reach per weapon)
  - `docs/SPEC.md`
- **Do Not Touch:** rules exports, `script/`.

### Specification & Decision Record Changes

- **§5.4 Combat Rounds, "Auto-attacks", "Range" and "Resolution" bullets:** replace "each engaged actor auto-attacks one
  target" and the "wielded weapon" wording in Range and Resolution with per-swing wording, adding: "Each round, every
  worn, active item with `dice` makes one auto-attack, in the order of the actor's slots, and an actor with a `wield`
  slot holding nothing makes one unarmed attack. Each attack has its own reach check, `before_attack`, `to_hit` and
  `damage` chains (with `ctx.weapon` and `ctx.slot` set) and messages. The attacks stop as soon as the attacker or
  target can no longer fight."
- **§3.3, Item:** "An item with `dice` is a weapon wherever it is worn: an amulet with `dice` attacks from the neck."

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  internal fun World.autoAttack(a: Actor)
  /** One auto-attack swing by [a] at [target] with [weapon] (null for unarmed) from [slot]. */
  private fun World.swing(a: Actor, target: Actor, weapon: Item?, slot: String)
  ```
- **Algorithmic Steps & Invariants:**
  1. `autoAttack(a)` keeps today's preamble unchanged: the attack-while-casting check, `isIncapacitated`, `opponentsOf`,
     primary-target selection.
  2. Build the swings: for each `slot` in `actorSlots(a)`, then any other worn slots by name: if the slot is active and
     holds an item with `proto.dice != null`, add `(item, slot)`. If `"wield" in actorSlots(a)` and `a.equipped("wield")
     == null`, add `(null, "wield")` at the position of `wield` in that order. An inactive item in `wield` means no
     unarmed swing.
  3. For each swing: stop if `!a.inWorld`, `a.isIncapacitated`, `!canAct(a)`, the target is no longer in
     `opponentsOf(a)`, or the target's condition is dead. Otherwise call `swing(a, target, weapon, slot)`.
  4. `swing`: move today's body from `val reach = ...` to the final `damage(target, losses, type, a)` into it, using
     `weapon` and building the ctx with `slot = slot` and `damageType = weapon?.proto?.damageType ?: schema.unarmed`.
     The out-of-reach message, `BEFORE_ATTACK` dispatch (with `weapon`), `toHitChain`, roll, trace, miss/hit messages
     and `damageChain` stay exactly as today, per swing.

### Test & Documentation Requirements

- `CombatTest.kt`:
  - a sword in `wield` and a dagger in `offhand` give two swings per round, with `ctx.slot` `wield` then `offhand`
    (assert via a recording `toHit` override);
  - a shield in `offhand` (no dice) gives one swing;
  - an empty `wield` gives one unarmed swing with `ctx.weapon == null`; an amulet with `dice` worn at `neck` adds a
    swing with `ctx.slot == "neck"`;
  - an inactive weapon does not swing and suppresses the unarmed swing;
  - if the first swing kills the target, no further swing happens;
  - a `before_attack` veto on one swing does not stop the next.
- `TrackTest.kt`: a melee dagger out of reach is skipped with the out-of-reach message while a ranged weapon in another
  slot still swings.

### Exit Conditions

- [] `./gradlew check` passes.
- [] Existing single-weapon combat tests pass unchanged.

---

## Phase 7: Script and plugin surface for the new fields

### Objective & Scope

- **In Scope:** `max_vital_deltas` and `resist_deltas` on `item()`, `item_affect()`, `apply_affect` (declaration effect
  and `ctx.apply_affect` for actors and items), item/affect views, `PluginItem`, plugin `applyAffect` /
  `applyItemAffect`; `inactive` on the item view and `PluginItem`; `slots` on the actor view; changelog and API golden
  files.
- **Out of Scope:** sample worlds (Phase 8).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Schema.kt` (`vitals(...)`, `resists(...)`, `TypedKind`)
  - Existing handling of `stat_deltas` and `sense_deltas` in each file below: mirror it exactly.
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`item()`, `ITEM_SPEC`, `item_affect`, `applyAffect` at
    `:1377`)
  - `script/src/main/kotlin/gups/script/Ctx.kt` (`applyAffect` at `:975`)
  - `script/src/main/kotlin/gups/script/Views.kt` (`ItemView` at `:547`, `AffectView` at `:689`, actor view)
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt`, `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`
  - Tests: `script/src/test/kotlin/gups/script/LoaderTest.kt`, `script/src/test/kotlin/gups/script/M5ScriptTest.kt` (or
    the existing affect script tests), `plugin-api` facade tests
  - `cli/src/test/resources/api.golden.json`, `docs/api/api-1.json`, `docs/CHANGELOG.md`, `docs/SPEC.md`
- **Do Not Touch:** `core/` (except fixing a signature mismatch the facade reveals).

### Specification & Decision Record Changes

- **§4.2:** `item(...)` and `item_affect(...)` gain `max_vital_deltas = vitals(...)` and `resist_deltas = resists(...)`.
- **§4.3 / §4.5:** `apply_affect(...)` (effect and verb, actor and item targets) gains the same two arguments.
- **§4.5, views:** item view gains `slot_cost` (Phase 3), `max_vital_deltas`, `resist_deltas` and `inactive`; affect
  view gains `max_vital_deltas` and `resist_deltas`; actor view gains `slots` (its `actor_slots` answer).
- **§4.15:** `PluginItem` gains `maxVitalDeltas`, `resistDeltas`, `slotCost`, `inactive`; `applyAffect` and
  `applyItemAffect` gain `maxVitalDeltas` and `resistDeltas`.

### Implementation Blueprint

- **Algorithmic Steps & Invariants:**
  1. Parse `max_vital_deltas` with `typed(v, TypedKind.VITALS, ...)` and `resist_deltas` with `typed(v,
     TypedKind.RESISTS, ...)` mapped to `DamageType` keys, everywhere `stat_deltas` is parsed today. `None` means empty.
  2. `item()`: an item with either map but no `wear` fails the load, joining the existing check at `Declarations.kt:945`
     for `equip_affects`/`modifiers` without `wear`.
  3. Views return the maps as Starlark dicts of string to int, as `stat_deltas` does. `ItemView.inactive` is true when
     the item is equipped in a slot in its wearer's `inactiveSlots`. The actor view's `slots` calls `actorSlots`.
  4. Plugin facades validate keys against `schema.vitalNames` / `schema.damageTypes` as they do for stats, and pass the
     maps through.
  5. `docs/CHANGELOG.md`, under the current `api_version` heading: one entry listing `slot_families()`,
     `actor_slots(ctx)`, `slot_cost(ctx)`, `item(slot_cost=)`, `max_vital_deltas`, `resist_deltas`, `ctx.damage_type`,
     `ctx.slot`, item view `inactive`, actor view `slots`, spell damage through the `damage` chain, and attacks from
     every item with `dice`.

### Test & Documentation Requirements

- `LoaderTest.kt`: unknown vital or damage type in either map fails with did-you-mean; either map without `wear` fails.
- Script runtime test: `ctx.apply_affect(actor, ..., max_vital_deltas = vitals(hp = 5))` raises `hp` max; the views
  expose the fields; `item.inactive` flips with a weakness affect in the template rules extended by an override.
- Plugin facade test: `applyAffect` with `resistDeltas` changes `effectiveResist`.

### Exit Conditions

- [] `./gradlew check` passes, including the API compatibility test.

---

## Phase 8: Sample worlds, spec wrap-up and backlog

### Objective & Scope

- **In Scope:** template-world demonstrations, spec consistency pass, M18 row, `DESIGN_NEEDED` bookkeeping.

### Context Working Set

- **Files to Modify / Create:**
  - `template-world/rules/combat.star`, `template-world/zones/tutorial/items.star`, `template-world/zones/tutorial/
    tutorial_test.star`
  - `samples/robot-world/rules/combat.star` (only if a test needs it)
  - `docs/SPEC.md`, `docs/DESIGN_NEEDED.md`, `docs/MILESTONE_18.md`
- **Do Not Touch:** engine code, unless a test exposes a bug in an earlier phase (fix it there and note it).

### Implementation Blueprint

1. `template-world/rules/combat.star`: in `to_hit`, give `ctx.slot == "offhand"` swings a clear penalty (for example `-
   10` to the chance), with a comment. In `slot_cost`, let actors with `str >= 18` wield `slot_cost == 2` items at cost
   1, with a comment.
2. `template-world/zones/tutorial/items.star`: add a ring (`wear = "ring"`, `resist_deltas = resists(fire = 2)`), a
   wooden shield (`wear = "offhand"`, `kind = "armor"`, `stat_deltas = stats(armor = 2)`), a dagger (`wear = "hand"`,
   `dice`), a greatsword (`wear = "hand"`, `slot_cost = 2`, `dice`) and an amulet with `max_vital_deltas = vitals(hp =
   5)`. Place them in tutorial rooms or a shop as fits the zone.
3. `tutorial_test.star`: dual-wield gives two attacks; the greatsword swap behaviour; the ring's fire resist; the amulet
   raising max hp.
4. `docs/SPEC.md`: read §3.3, §4.2, §4.3, §4.5, §4.6, §4.9 and §4.15 end to end for leftover wording about prefix slot
   families, "only `wield` attacks", "`wield` only takes items whose slot is `wield`", or items/affects lacking
   vital/resist deltas, and fix it. Add the **M18** row to the §7.4 milestone table summarizing this milestone.
5. `docs/DESIGN_NEEDED.md`: mark these entries as completed by M18 (do not delete them): `### Armor from equipment,
   shields and off-hand weapons`, `### Spell damage modifiers`, `### Equipment slots that depend on the actor`. Leave
   `### Combat procs: items and affects that cast on attack` and `### Typed bonus damage on attacks` open.
6. Run `./gradlew markdownFormat`.

### Exit Conditions

- [] `./gradlew check` passes, including `markdownCheck`, template-world and robot-world tests, and the API
  compatibility test.
- [] The three resolved `DESIGN_NEEDED` entries are marked completed; the two new ones remain open.
