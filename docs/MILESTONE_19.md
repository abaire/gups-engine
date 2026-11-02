# Milestone 19: Track-Lag Modifier Points

**Prerequisites:** [M18](MILESTONE_18.md) must be fully implemented first. This milestone touches none of M18's slot or
attack code, but it assumes M18's end state for the modifier chain in `core/src/main/kotlin/gups/core/world/Rules.kt`
(the `HeldEffects` item steps and the weapon-scoped modifiers during swings). Where line numbers below differ after M18,
find the symbol by name.

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** [DESIGN_NEEDED.md](DESIGN_NEEDED.md): `## Combat and skills` → `### Track-lag modifier
  points`.
- **Design Summary:**
  - **One modifier point per lag export.** Four new int modifier points share the names of the rules exports they
    modify: `advance_lag`, `retreat_lag`, `retreat_fail_lag` and `flee_fail_lag`. The engine runs each export's result
    through the acting actor's own modifier chain (zone, regions, room, traits, affects, items, in the usual order), so
    `package(modifiers=)`, `region(modifiers=)`, `room(modifiers=)`, `item(modifiers=)`, `item_affect(modifiers=)`,
    `apply_affect(modifiers=)` and plugin traits can change recovery lag directly.
  - **Same ctx as the export.** Each modifier gets the `ctx` its export gets: `ctx.target` and `ctx.band` for the three
    track points (the actor approached, the threat, or the pinner; the band after the step, or `"melee"` for a failed
    retreat), and `ctx.target == None`, `ctx.band == None` for `flee_fail_lag`.
  - **Feature gating follows the exports.** `advance_lag`, `retreat_lag` and `retreat_fail_lag` need the `track`
    feature; `flee_fail_lag` needs only `combat`. A world without `track` can still name the `flee_fail_lag` modifier
    point.
  - **Clamp only the final value.** Modifiers may return negative values partway through the chain. The chain's final
    value is clamped to 0, and when the clamp changes the value, the trace line ends with ` → clamp 0`. The rules
    exports themselves still must return an int of at least 0 (a negative export result is still a bad return value).
  - **Traced.** Each lag chain is sent to `@trace rules` and the harness trace, like `initiative` and `regen`. For
    example: `rules.advance_lag base=4 → item:tar_walker_boots 2`.
  - **Plugins.** `PluginModifierPoint` gains the four points. Plugin trait modifiers for them take and return `Long`,
    like `to_hit`. `PluginHost` refuses the three track points in a world without `track`, and `flee_fail_lag` in a
    world without `combat`.
  - **Out of scope.** `get_lag` is not a modifier point: its `None` result means "out of reach", which doesn't fit an
    int chain. Encumbrance doesn't change lag in this milestone: the "Encumbrance beyond stats" entry is rewritten to
    point at the new lag points as the place encumbrance lag would plug in. The load-time dry run keeps calling the raw
    exports, with no chain.
  - **Template demo.** The tar pit keeps its `sticky_floor` tag and the `_footing` rules helper (tutorial 02 §7 teaches
    tags with it). A new tutorial item, `tar_walker_boots`, uses `item(modifiers=)` on `advance_lag` and `retreat_lag`
    to cancel the tar pit's extra lag.
- **Specification Impact:**
  - §3.10 (feature table rows for `combat` and `track`)
  - §4.6 (modifier point list, whose chain, value types and clamping, lag export bullet)
  - §5.5 (`advance`, `retreat`, pinned retreat and `flee` lag text)
  - §7.4 (new M19 row)
  - `docs/CHANGELOG.md` under `## api_version 1 (unreleased)`
  - `docs/api/api-1.json` baseline (additions only; rewritten in place, since api_version 1 is unreleased)
- **Decision Record Updates:** None.

---

## Phase 1: Core modifier points, lag chains and the script surface

Adding a `ModifierPoint` entry fails the API model check unless the script-side `ModifierPointSpec`, feature ownership
and API baseline land with it, so this phase lands all of it together. Each edit is small.

### Objective & Scope

- **In Scope:**
  - The four `ModifierPoint` entries.
  - The lag chain wrappers in core, with the final clamp and tracing, and the five call sites rewired to them.
  - The four `ModifierPointSpec` docs, feature ownership of the four `modifier:` symbols, the regenerated API baseline,
    the CHANGELOG entry, and spec §3.10, §4.6 and §5.5.
  - Kotlin tests in `core` and `script`.
- **Out of Scope:**
  - `PluginModifierPoint` and `PluginHost` (Phase 2). Adding core entries doesn't break `Adapters.kt`: its `when
    (point)` already has an `else -> base` branch, and plugin traits can't name the new points until Phase 2.
  - Template world, tutorials, `DESIGN_NEEDED` (Phase 3).
  - `get_lag` and the dry run: unchanged.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Rules.kt`: `chain`, `traced`, `initiativeOf`, `regenOf` (pattern to mirror).
  - `script/src/main/kotlin/gups/script/Rules.kt`: `baseValue`/`pointValue` (around line 271–295; their `else` branches
    already treat any other point as an int, so they need no change), `MODIFIER_POINTS` (around line 995).
  - `script/src/main/kotlin/gups/script/Declarations.kt`: `modifiers()` (around line 487), which already refuses a point
    whose `modifier:<name>` symbol is owned by a feature that is off. `MODIFIER` constants are built from
    `ModifierPoint.entries`, so they gain the new names automatically.
  - `script/src/main/kotlin/gups/script/ApiModel.kt` around line 678: the check that every `ModifierPoint` has a spec.
  - `core/src/test/kotlin/gups/core/world/ModifierChainTest.kt` (`regen and move cost go through the chain`) and
    `script/src/test/kotlin/gups/script/RulesTest.kt` (`room, region, affect and item modifiers chain in order`, the
    `lagWorld` helper and the `DISTINCT_LAGS` tests around line 611–720) as test patterns.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt`
  - `core/src/main/kotlin/gups/core/world/Rules.kt`
  - `core/src/main/kotlin/gups/core/world/TrackCommands.kt`
  - `core/src/main/kotlin/gups/core/world/CombatCommands.kt`
  - `script/src/main/kotlin/gups/script/Rules.kt`
  - `script/src/main/kotlin/gups/script/Ownership.kt`
  - `core/src/test/kotlin/gups/core/world/ModifierChainTest.kt`
  - `script/src/test/kotlin/gups/script/RulesTest.kt`
  - `cli/src/test/kotlin/gups/cli/DocsWorldTest.kt`
  - `docs/api/api-1.json` (regenerated, never hand-edited), `docs/CHANGELOG.md`, `docs/SPEC.md`
- **Do Not Touch:** `core/src/main/kotlin/gups/core/rules/RulesEngine.kt` (the export interface stays as is),
  `script/src/main/kotlin/gups/script/Rules.kt`'s `StarlarkRules.advanceLag`/`retreatLag`/`retreatFailLag`/
  `fleeFailLag` and the dry run (they keep calling the raw exports), `plugin-api/`, `template-world/`, `samples/`.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§4.6, "Modifier points (v0)" paragraph.** Replace it with:

   > **Modifier points (v0):** `to_hit`, `damage`, `initiative`, `cast_allowed`, `move_cost`, `regen`, `advance_lag`,
   > `retreat_lag`, `retreat_fail_lag`, `flee_fail_lag`. The list grows with `api_version`. `mitigate` and `get_lag` are
   > not modifier points. Modifiers of `move_cost` and `regen` take and return `vitals(...)` values; modifiers of the
   > four lag points take and return pulses as ints, and may go below 0 along the chain, but the chain's final value is
   > clamped to 0. Modifier points of a feature that is off can't be named: the lag points belong to the feature of the
   > export of the same name.

2. **§4.6, "Whose chain".** Change "and the actor's own for `regen` and `initiative`" to "and the actor's own for
   `regen`, `initiative` and the four lag points (the actor that advanced, retreated or failed to flee)".
3. **§4.6, "Signatures and results" bullet** that begins "`advance_lag`, `retreat_lag`, `retreat_fail_lag`,
   `flee_fail_lag` and `get_lag` return pulses". Change its last sentence to: "The lag exports' results go through their
   modifier chains, and the chain's result sets the action's recovery lag; 0 means none. `get_lag` sets the pickup lag
   directly."
4. **§4.6, the modifier-point list.** Add one sentence after the `@trace` example paragraph ("The harness and `@trace`
   print the full chain…"): "A lag chain that ends below 0 prints a final `→ clamp 0` step."
5. **§5.5.** In the `advance` bullet, change "with lag from `rules.advance_lag`" to "with lag from `rules.advance_lag`
   and its modifier chain (§4.6)". Make the same change for `rules.retreat_lag`, for "Failure applies
   `rules.retreat_fail_lag` lag" (→ "Failure applies the lag of `rules.retreat_fail_lag` and its modifier chain") and
   for the `flee` "On failure" bullet (`rules.flee_fail_lag`).
6. **§3.10 feature table.** In the `combat` row, change "`flee_fail_lag`" to "`flee_fail_lag` (the export and its
   modifier point)". In the `track` row, change "`advance_lag`, `retreat_lag`, `retreat_fail_lag`" to "`advance_lag`,
   `retreat_lag`, `retreat_fail_lag` (the exports and their modifier points)".

No `D-xxx` changes.

### Implementation Blueprint

- **Signatures & Types:**

  `core/src/main/kotlin/gups/core/model/Protos.kt`, `enum class ModifierPoint`: append after `REGEN` (move the `;`):

  ```kotlin
  /** Modifies the recovery lag after `advance`. */
  ADVANCE_LAG("advance_lag"),

  /** Modifies the recovery lag after a successful `retreat`. */
  RETREAT_LAG("retreat_lag"),

  /** Modifies the recovery lag after a failed retreat check. */
  RETREAT_FAIL_LAG("retreat_fail_lag"),

  /** Modifies the recovery lag after a failed `flee`. */
  FLEE_FAIL_LAG("flee_fail_lag");
  ```

  `core/src/main/kotlin/gups/core/world/Rules.kt`, new public functions next to `initiativeOf`:

  ```kotlin
  fun World.advanceLagOf(c: RulesContext): Int
  fun World.retreatLagOf(c: RulesContext): Int
  fun World.retreatFailLagOf(c: RulesContext): Int
  fun World.fleeFailLagOf(c: RulesContext): Int
  ```

  and one private helper:

  ```kotlin
  /** Runs a recovery lag through [point]'s chain. Steps may go below 0; the result is at least 0. */
  private fun World.lagOf(point: ModifierPoint, c: RulesContext, base: Int): Int
  ```
- **Algorithmic Steps & Invariants:**
  1. `lagOf`: `val r = chain(point, c, base) { "$it" }`. If `r.value < 0`, trace `"${r.text} → clamp 0"` and return 0;
     otherwise trace `r.text` and return `r.value`. Use `traceRules(c.actor, ...)` directly (as `Combat.kt:108` does),
     not `traced`, so the clamp step is on the same line.
  2. Each public wrapper calls the matching export and passes the result to `lagOf`, for example `advanceLagOf(c) =
     lagOf(ModifierPoint.ADVANCE_LAG, c, rules.advanceLag(c))`.
  3. Rewire the five call sites, keeping their `RulesContext` arguments exactly as they are:
     - `TrackCommands.kt` `AdvanceAction.resolve`: `w.rules.advanceLag(...)` → `w.advanceLagOf(...)`.
     - `TrackCommands.kt` `RetreatAction.resolve`: `w.rules.retreatFailLag(...)` → `w.retreatFailLagOf(...)` and
       `w.rules.retreatLag(...)` → `w.retreatLagOf(...)`.
     - `CombatCommands.kt` `FleeAction.resolve` (both failure returns): `w.rules.fleeFailLag(...)` →
       `w.fleeFailLagOf(...)`.
  4. Invariant: the chain is the actor's (`c.requireActor()` inside `chain`); every call site already passes the acting
     actor as `RulesContext.actor`.
  5. `script/src/main/kotlin/gups/script/Rules.kt`, `MODIFIER_POINTS`: append four `ModifierPointSpec`s after `REGEN`,
     each with `signature = "fn(ctx, base)"` and `valueType = "int"`:
     - `ADVANCE_LAG`: `ctxFields = listOf("actor", "target", "band", "room", "pulse", "rng")`, doc "Adjusts the recovery
       lag in pulses after advance. ctx.target is the actor approached; ctx.band is the band after the step. The final
       result is clamped to 0."
     - `RETREAT_LAG`: same fields, doc "Adjusts the recovery lag after a successful retreat. ctx.target is the threat;
       ctx.band is the band after the step. The final result is clamped to 0."
     - `RETREAT_FAIL_LAG`: same fields, doc "Adjusts the recovery lag after a failed retreat_check. ctx.target is the
       pinner; ctx.band is \"melee\". The final result is clamped to 0."
     - `FLEE_FAIL_LAG`: `ctxFields = listOf("actor", "room", "pulse", "rng")`, doc "Adjusts the recovery lag after a
       failed flee. ctx.target and ctx.band are None. The final result is clamped to 0."

     Leave `since` at its default (1): api_version 1 is unreleased.

  6. `script/src/main/kotlin/gups/script/Ownership.kt`: add `"modifier:flee_fail_lag"` to the existing
     `owns(Feature.COMBAT, "modifier:to_hit", "modifier:damage", "modifier:initiative")` line, and add
     `owns(Feature.TRACK, "modifier:advance_lag", "modifier:retreat_lag", "modifier:retreat_fail_lag")` next to the
     track `rules:` lag line.

  7. Regenerate the API baseline: `./gradlew :cli:installDist`, then run `cli/build/install/gups/bin/gups docs
     --update-baseline` from the engine checkout (the same procedure as M16/M17). Check the diff: four new
     `modifier:*_lag` entries (three with `"feature": "track"`, one with `"feature": "combat"`), the new names in every
     `"allowed_values"` list of modifier point names, and the `MODIFIER` constant's `value`. Nothing else should change.

  8. `docs/CHANGELOG.md`: add a section at the top of `## api_version 1 (unreleased)`:

     - `### Lag modifier points: advance_lag, retreat_lag, retreat_fail_lag and flee_fail_lag`, with bullets: the four
       modifier points (and `MODIFIER.advance_lag` and the others); that they run on the acting actor's chain with the
       export's ctx; that the chain's final value is clamped to 0 while steps may go negative; the feature each needs;
       that `get_lag` is not a modifier point.

### Test & Documentation Requirements

- `core/src/test/kotlin/gups/core/world/ModifierChainTest.kt`: add `` `lag chains run through the actor's modifiers and
  clamp at 0` ``. Mirror `regen and move cost go through the chain`: a `RecordingHost` modifier that subtracts 10 from
  int lag points, attached by an affect to `ADVANCE_LAG`. Assert `f.w.advanceLagOf(RulesContext(f.w, p, other, band =
  Band.NEAR))` is 0 when the fixture rules' base (`FixtureRules.advanceLag` returns 2) minus 10 is negative. Also check
  that the trace ends with `→ clamp 0` (collect lines through `f.w.onRules`, as the existing trace test does). Add a
  second case where the modifier adds 3 and the result is 5 with no clamp step.
- `script/src/test/kotlin/gups/script/RulesTest.kt`:
  - `` `an item's advance_lag and retreat_lag modifiers change the lag` ``: a `lagWorld` variant (or a `world(...)` with
    the lag exports) with an item `item("boots", wear = "<a slot>", modifiers = {"advance_lag": _quick, "retreat_lag":
    _quick})` where `_quick` returns `base - 3`, and `<a slot>` is one the test world's `equipment_slots()` returns (the
    existing sword test uses `"wield"`). Equip it and assert `waitState` is the export's value minus 3. Use
    `DISTINCT_LAGS`, so 7 → 4 and 5 → 2.
  - `` `a room's flee_fail_lag modifier changes a failed flee's lag` ``: `room(..., modifiers = {"flee_fail_lag":
    _slow})` adding 2; a failed flee gives 13.
  - `` `a lag chain that goes below 0 leaves no recovery` ``: an affect modifier returning `base - 100` on
    `retreat_fail_lag` gives `waitState == 0` and no diagnostics.
  - `` `the track lag modifier points need the track feature` ``: in a world without `track` (follow the feature-refusal
    tests in the same file or `FeatureScriptTest.kt`), `room(modifiers = {"advance_lag": ...})` fails the load with
    "…needs the track feature". In the same world, `{"flee_fail_lag": ...}` loads.
- `cli/src/test/kotlin/gups/cli/DocsWorldTest.kt`: next to the existing loop over `advance_lag`, `retreat_lag` and
  `retreat_fail_lag` (around line 74), which checks `id="rules-<name>"`, add the same checks for the modifier points.
  The docs renderer's anchors are `symbol.replace(':', '-')`, so a modifier point is `id="modifier-<name>"`. Robot-world
  docs (no `track`) leave out `id="modifier-advance_lag"`, `id="modifier-retreat_lag"` and
  `id="modifier-retreat_fail_lag"` and contain `id="modifier-flee_fail_lag"`; the all-features docs contain all four.
  Match by anchor, never by bare name: the `MODIFIER` constant's value names every point in every world.
- KDoc: the new `ModifierPoint` entries and `lagOf` as above. No spec citations in comments.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including the API model check and the API-change check against the
  regenerated `docs/api/api-1.json`.
- [] `./gradlew :core:test --tests '*ModifierChainTest*'` and `./gradlew :script:test --tests '*RulesTest*'` pass,
  including the new cases.
- [] `./gradlew markdownFormat` has been run, and `./gradlew markdownCheck` passes.
- [] `grep -n "advance_lag" docs/SPEC.md` shows the updated §3.10, §4.6 and §5.5 wording.

---

## Phase 2: Plugin trait modifiers for the lag points

### Objective & Scope

- **In Scope:** `PluginModifierPoint` gains the four points; the trait adapter converts them as ints; `PluginHost`
  refuses them by feature; tests; plugin docs.
- **Out of Scope:** Any core or script change. Sample plugins (`samples/heritage-plugin`) don't need to use the new
  points.

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt` `traitAdapter` (around line 113–176).
  - `plugin-api/src/test/kotlin/gups/pluginapi/CreationRegistrationTest.kt` `` `a combat modifier point needs the combat
    feature` `` (around line 136), the test pattern to mirror.
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`
  - `plugin-api/src/test/kotlin/gups/pluginapi/CreationRegistrationTest.kt`
  - A trait-modifier test next to existing adapter tests (find with `grep -rln "traitAdapter\|PluginTrait("
    plugin-api/src/test`)
  - `docs/PLUGINS.md` and `docs/CHANGELOG.md`, only if they list the plugin modifier points (`grep -n
    "PluginModifierPoint\|MOVE_COST" docs/PLUGINS.md`)
- **Do Not Touch:** `core/`, `script/`, `template-world/`.

### Specification & Decision Record Changes

- None in `SPEC.md` (§4.15 doesn't list the points by name; confirm with `grep -n "MOVE_COST\|PluginModifierPoint"
  docs/SPEC.md`, and if it does, add the four names there).

### Implementation Blueprint

- **Signatures & Types:** `PluginApi.kt`:

  ```kotlin
  enum class PluginModifierPoint {
    TO_HIT,
    DAMAGE,
    INITIATIVE,
    MOVE_COST,
    REGEN,
    ADVANCE_LAG,
    RETREAT_LAG,
    RETREAT_FAIL_LAG,
    FLEE_FAIL_LAG,
  }
  ```

  The names must equal the `ModifierPoint` names: the adapter maps them with `valueOf(name)`.

- **Algorithmic Steps & Invariants:**

  1. `Adapters.kt` `traitAdapter`: add `ModifierPoint.ADVANCE_LAG`, `RETREAT_LAG`, `RETREAT_FAIL_LAG` and
     `FLEE_FAIL_LAG` to the existing `TO_HIT, DAMAGE, INITIATIVE` branch (base passed as `Long`, a `Long` result coerced
     to `Int`, anything else refused). The final clamp to 0 stays in core's `lagOf`, so a trait may return a negative
     value.
  2. `PluginHost.kt` (around line 304–318), in the per-trait validation: keep the `combat` check and add
     `PluginModifierPoint.FLEE_FAIL_LAG` to its set. Add a second check with the same shape: `ADVANCE_LAG`,
     `RETREAT_LAG` and `RETREAT_FAIL_LAG` require `Feature.TRACK in features`, with the message `"trait '${trait.name}':
     modifier point $p needs the track feature"`.

### Test & Documentation Requirements

- `CreationRegistrationTest.kt`:
  - Extend `` `a combat modifier point needs the combat feature` `` to include `FLEE_FAIL_LAG`.
  - Add `` `a track lag modifier point needs the track feature` ``: in a world with every feature but `TRACK`, each of
    the three track points is refused with the message above, and `FLEE_FAIL_LAG` is accepted.
- An adapter test: a trait whose `ADVANCE_LAG` modifier returns `base + 3L` changes the actor's advance lag by 3 (use
  `World.advanceLagOf` from Phase 1, or run an `advance` and read `waitState`, whichever the existing adapter tests make
  easier). A modifier returning a `String` is refused with a `plugin` diagnostic and the value stays the same.
- KDoc on `PluginModifierPoint`, if it has any, lists the value type of the new points (`Long`).

### Exit Conditions

- [] `./gradlew :plugin-api:test` passes, including the new tests.
- [] `./gradlew check` passes with zero warnings (the module-graph check included).

---

## Phase 3: Template demo, encumbrance backlog entry and finalization

### Objective & Scope

- **In Scope:** the `tar_walker_boots` tutorial item and its test, tutorial 03 text, the rewritten "Encumbrance beyond
  stats" entry, marking the source entry completed, the §7.4 M19 row, a final spec consistency pass.
- **Out of Scope:** changing the tar pit, `_footing` or tutorial 02; `samples/robot-world` (it has no `track` feature
  and its `flee_fail_lag` export doesn't change); any engine code unless a test exposes a bug from an earlier phase (fix
  it in that phase's files and say so in the commit).

### Context Working Set

- **Read / Reference Only:**
  - `template-world/rules/combat.star` (`_footing`, `advance_lag`, `retreat_lag`; `"feet"` is in `equipment_slots()`).
  - `template-world/zones/tutorial/rooms.star` (the `tar_pit` room and its comment).
  - `template-world/zones/tutorial/tutorial_test.star`: `_footwork` and `test_the_tar_pit_slows_footwork` (around line
    164–186); `w.spawn(":healing_draught", at = p)` (around line 105) gives an item to a player.
- **Files to Modify / Create:**
  - `template-world/zones/tutorial/items.star`
  - `template-world/zones/tutorial/tutorial_test.star`
  - `template-world/docs/tutorials/03_items_and_equipment.md`
  - `docs/DESIGN_NEEDED.md`
  - `docs/SPEC.md` (§7.4 row only, plus any leftovers found in the consistency pass)
- **Do Not Touch:** `template-world/rules/`, `template-world/zones/tutorial/rooms.star`,
  `template-world/docs/tutorials/02_rooms_and_navigation.md`, engine code.

### Specification & Decision Record Changes

- §7.4 milestone table: add a row after the last one:

  `| **M19** | Track-lag modifier points: advance_lag, retreat_lag, retreat_fail_lag and flee_fail_lag as modifier
  points on the acting actor's chain, the final value clamped to 0; plugin trait modifiers for them; the template's
  tar_walker_boots (§4.6, §4.15, §5.5). |` (format each name in backticks like the M12 row, and let `markdownFormat`
  realign the table).

- Consistency pass: `grep -n "lag" docs/SPEC.md` and check that no sentence still says the lag exports alone set the
  recovery lag (§4.6 table rows for the four exports may stay as they are; they describe the exports).

### Implementation Blueprint

#### Step 1: the boots

In `template-world/zones/tutorial/items.star`, after `leather_cap`, add:

```python
def _sure_footed(ctx, base):
    # Modifiers see the same ctx as the rules export, so the boots can check the room.
    # In a sticky room they cancel the rules' doubled footwork lag; elsewhere they change nothing.
    return base // 2 if "sticky_floor" in ctx.room.tags else base

item("tar_walker_boots",
    name = "a pair of tar-walker boots",
    keywords = ["boots"],
    desc = "Broad, flat-soled boots that spread your weight over the muck.",
    kind = "armor",
    wear = "feet",
    # Lag modifiers take the pulses the rules export returned and return new pulses.
    modifiers = {
        MODIFIER.advance_lag: _sure_footed,
        MODIFIER.retreat_lag: _sure_footed,
    },
)
```

Don't place the boots in a room or give them as a reward unless a natural spot exists. The test spawns them.

#### Step 2: the tutorial test

In `template-world/zones/tutorial/tutorial_test.star`, give `_footwork(t, room)` an optional `boots = False` parameter.
When it's true, spawn the boots on Tess (`w.spawn(":tar_walker_boots", at = p)`) and `p.do("wear boots")` before the
advance, then `w.run(pulses = 10)` so the wear's lag passes. Add:

```python
def test_tar_walker_boots_cancel_the_tar_pit(t):
    # The boots' advance_lag and retreat_lag modifiers halve the lag only in sticky rooms.
    t.eq(_footwork(t, ":tar_pit", boots = True), (2, 2))
    t.eq(_footwork(t, ":hall", boots = True), (2, 2))
```

`test_the_tar_pit_slows_footwork` stays unchanged and still passes with `(4, 4)` and `(2, 2)`.

#### Step 3: tutorial 03

In `template-world/docs/tutorials/03_items_and_equipment.md`, section `### B. Dynamic Rules Modifiers: modifiers`, after
the `spiked_gauntlets` example and its bullets, add a short paragraph and the `tar_walker_boots` snippet. Say that
modifiers can change recovery lag (`MODIFIER.advance_lag`, `retreat_lag`, `retreat_fail_lag`, `flee_fail_lag`), that
they see the export's `ctx` (here `ctx.room`), and that the final lag is never below 0. Mention that the tar pit's lag
comes from its `sticky_floor` tag (tutorial 02 §7) and that these boots cancel it. Update the "Execution" bullet's list
of examples if it names the points.

#### Step 4: `DESIGN_NEEDED`

In `docs/DESIGN_NEEDED.md`:

- Mark the source entry completed by renaming its heading to `### Track-lag modifier points (COMPLETED — M19)`,
  following the existing `### Effects on items (COMPLETED — M17)` precedent. Do not delete the entry or change its body.
- Rewrite the body of `### Encumbrance beyond stats` (same heading, same section) so this question is clearly revisited.
  Use the text in the next block.

**Spec:** §4.6 (`carry_limit`, `encumbrance`, the modifier chain). **Today:** encumbrance is a set of stat deltas only
(D-115). It doesn't affect move cost, lag or the ability to move. Since M19, recovery lag has modifier points
(`advance_lag`, `retreat_lag`, `retreat_fail_lag`, `flee_fail_lag`) alongside `move_cost`, but nothing lets encumbrance
reach them: rules and modifiers can't tell that an actor is over its carry limit (there is no `encumbered` actor view
field), and `encumbrance(ctx)` can only return stats. **Questions:** - Should actors expose a read-only `encumbered`
view field, so rules exports and modifiers can add lag or move cost while it is set? - Or should being over the limit
apply an engine-managed affect that carries `modifiers` (and its stat deltas), so encumbrance uses the ordinary chain? -
Or should `encumbrance` return move-cost and lag changes directly? - Should movement be forbidden past a hard limit, and
is that a second rules export or part of the same result?

#### Step 5: format

Run `./gradlew markdownFormat`, then check that code blocks in lists kept their indentation (see `AGENTS.md`).

### Test & Documentation Requirements

- `tutorial_test.star`: the new test above. The existing template-world tests still pass.
- Tutorial 03 updated as above. The tutorial README lists topics per tutorial: if tutorial 03's entry lists modifier
  examples, add "lag modifiers".

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `markdownCheck`, the template-world and robot-world tests,
  and the API-change check.
- [] `test_tar_walker_boots_cancel_the_tar_pit` and `test_the_tar_pit_slows_footwork` pass.
- [] `docs/DESIGN_NEEDED.md` has `### Track-lag modifier points (COMPLETED — M19)`, with its body intact. `###
  Encumbrance beyond stats` has the rewritten text naming the lag modifier points and the missing `encumbered` signal.
- [] §7.4 has the M19 row.
- [] `@trace rules` in a manual or harness run shows a line like `rules.advance_lag base=4 → item:tar_walker_boots 2`
  for Tess in the tar pit wearing the boots (`w.trace(True)` in a scratch test; don't commit it if it's noisy).
