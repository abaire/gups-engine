# Milestone 32: Progressive Encumbrance

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, heading `### Encumbrance beyond stats` (under the section that
  precedes "Perception and the world").

- **Design Summary:** encumbrance becomes a world-defined *level* (an int, 0 or more) that rules, modifiers and scripts
  can read. The engine knows nothing about what a level means: how many levels there are, where they start, and what
  they cost all belong to the world.

  - **`carry_limit` is unchanged.** It stays the hard cap that `get`, `give` and mob scavenging already enforce
    (`carryFit` in `KernelCommands.kt`, `canScavenge` in `Behaviors.kt`). Do not touch those paths. Levels are the
    graded penalty *below and above* that cap.
  - **New kernel rules export `encumbrance_level(ctx) -> int`.** Must return an int of at least 0 (0 means
    unencumbered); a negative value is a bad return value. Every ruleset must define it. It sees `ctx.actor` (race,
    class, traits, affects, stats), plus the new read-only `ctx.limit` (the actor's `carry_limit`, asked without an
    item) and `ctx.carried` (carried and worn weight, containers' contents included). So a world can write "dwarves
    start suffering at 90%" or "a pack mule never goes above 1".
  - **The engine stores the level** on the actor (`Actor.encumbranceLevel`) and shows it as read-only
    `ctx.actor.encumbrance_level`. It is derived, so it is not saved; it is recomputed on restore like
    `Actor.encumbrance`. Recompute points are unchanged: custody changes and every world tick.
  - **`rules.encumbrance(ctx)` stays a stats-only export** and is now called only while the level is above 0. It gets
    `ctx.encumbrance_level` (the new level; `None` in every other export). Its result still fills `Actor.encumbrance`.
  - **Move cost and lag are not engine behavior.** A world reads `ctx.actor.encumbrance_level` in `move_cost`, in any
    lag export it has, or in a modifier on any modifier point (trait, item, affect, room, zone). The engine adds no
    modifier points. A world without `track` simply has no lag points. The template world's exports show one way.
  - **While the export runs, the actor's level reads 0.** The engine clears the stored level and the stat deltas before
    asking, so the export can't see its own previous answer.
  - **Messages.** On a level change the actor is told: `encumbrance.on` (0 to above 0, existing text), `encumbrance.up`
    (a higher nonzero level), `encumbrance.down` (a lower nonzero level) and `encumbrance.off` (back to 0, existing
    text). `up` and `down` take `{level}` and `{was}`. Worlds reword them through the normal message overrides. A change
    in stat deltas with the same level tells nothing.
  - **Not in this milestone:** an `on_encumbrance_change` hook (players have no hooks), a movement restriction at a top
    level (a world can price movement out with `move_cost`), and any change to pickup refusal.
- **Specification Impact:** §4.6 (export table, `ctx` fields, the `encumbrance` return notes, the Encumbrance paragraph,
  kernel feature table row), §4.18 or wherever the actor view fields are listed (`encumbrance_level`), §7.4 (milestone
  row), and the template tutorial text that mentions encumbrance.
- **Decision Record Updates:** None. D-115 is not in `docs/DECISIONS.md` any more (it was folded into the spec), so
  there is nothing to edit there. Do not add a new `D-xxx`.

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first and see them fail.
   - No spec section numbers or decision ids in comments, Kotlin or Starlark.
   - All Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`. The engine is
   unreleased, so no migrations or back-compat shims are needed.
3. Add CHANGELOG bullets to a new `### Progressive encumbrance` subsection at the top of `## api_version 1
   (unreleased)`, above the first existing `###` subsection. Create it in the first phase that adds a bullet, and have
   each later phase append to it.
4. In any phase that changes a message, a script symbol or the plugin API (Phases 1 and 2 may), regenerate all three API
   files before the phase's final `./gradlew check`:
   - Run `./gradlew :cli:test -Dgups.updateGolden=true`. This rewrites `cli/src/test/resources/api.golden.json` and the
     `cli/src/test/resources/docs-golden/` files.
   - Run `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout. This rewrites `docs/api/api-1.json`.
   - Read the diff. Only the symbols that phase names may be new or changed. `ApiDiffTest` must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing entry.
5. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation.
6. Never read or touch `local-world/`. Put scratch files in `local-world-test/` and delete it when you are done. Do not
   commit anything from it.

---

## Phase 1: Core model, level computation and messages

### Objective & Scope

- **In Scope:**
  - `RulesEngine.encumbranceLevel`, the three new `RulesContext` fields, `Actor.encumbranceLevel`.
  - Rewrite `World.updateEncumbrance` to compute the level and then the stat deltas, and to tell level changes.
  - Four kernel messages.
  - Fixture rules and core tests.
  - The SPEC Encumbrance paragraph.
- **Out of Scope:** *(do not attempt these; later phases own them)* the script layer (`Rules.kt`, `Views.kt`, `Ctx.kt`),
  the template and robot worlds, API golden files, the changelog. Do not change `carryFit`, `canScavenge`, `refreshFit`
  or anything about pickup refusal.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Holdings.kt` (lines ~70-85, who calls `updateEncumbrance`)
  - `core/src/main/kotlin/gups/core/world/Slots.kt` (~182) and `World.kt` (~749), the other callers
  - `core/src/main/kotlin/gups/core/world/KernelMessages.kt` (the `msg(...)` helper and the params argument, as in
    `LOOK_HEALTH` at line 24)
  - `docs/SPEC.md` around the **Encumbrance:** paragraph (~line 1605)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt`
  - `core/src/main/kotlin/gups/core/model/Entities.kt`
  - `core/src/main/kotlin/gups/core/world/Items.kt`
  - `core/src/main/kotlin/gups/core/world/KernelMessages.kt`
  - `core/src/testFixtures/kotlin/gups/core/fixtures/FixtureRules.kt`
  - `core/src/test/kotlin/gups/core/world/ItemCommandsTest.kt`
  - `docs/SPEC.md` (Encumbrance paragraph only)
- **Do Not Touch:** `script/`, `template-world/`, `samples/`, `plugin-api/`, `cli/`, pickup code.

### Specification & Decision Record Changes

Replace the **Encumbrance:** paragraph in `docs/SPEC.md` with:

> **Encumbrance:** `rules.encumbrance_level` gives an actor's encumbrance level, 0 or more, from `ctx.carried` (carried
> and worn weight, containers' contents included) and `ctx.limit` (`rules.carry_limit` asked without an item), and
> whatever else of the actor the world reads. The engine gives no meaning to a level; worlds read it as
> `ctx.actor.encumbrance_level` in rules exports and modifiers. While the level is above 0, `rules.encumbrance` supplies
> stat deltas (with `ctx.encumbrance_level` set). Both are recomputed when an actor's custody changes (spawn, move,
> destroy) and at every world tick, since stats can change the limit. While they are being computed the actor's level
> reads 0 and no deltas apply. The actor is told when the level rises from 0 (`encumbrance.on`), rises again
> (`encumbrance.up`), falls but stays above 0 (`encumbrance.down`) and returns to 0 (`encumbrance.off`). `carry_limit`
> itself still caps what `get`, `give` and scavenging will take.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // RulesEngine.kt
  /** The actor's encumbrance level, 0 or more; 0 means unencumbered. */
  fun encumbranceLevel(c: RulesContext): Int

  // RulesContext (add after `affect`, all default null, and carry them in withDamageType)
  /** The actor's carry limit asked without an item, in encumbrance_level and encumbrance calls. */
  val limit: Int? = null,
  /** Carried and worn weight, in encumbrance_level and encumbrance calls. */
  val carried: Int? = null,
  /** The level just computed, in encumbrance calls. */
  val encumbranceLevel: Int? = null,

  // Entities.kt, next to `encumbrance`
  /** Level of encumbrance from `rules.encumbrance_level`; 0 when unencumbered. Not saved. */
  var encumbranceLevel: Int = 0
    internal set

  // KernelMessages.kt, next to ENCUMBERED / UNENCUMBERED
  val ENCUMBRANCE_UP = msg("encumbrance.up", "Your load grows heavier.", "level,was")
  val ENCUMBRANCE_DOWN = msg("encumbrance.down", "Your load grows lighter.", "level,was")
  ```

  Update the KDoc on `RulesEngine.carryLimit` ("before encumbrance penalties apply" becomes "the most the actor can pick
  up") and on `RulesEngine.encumbrance` ("stat penalties for the current encumbrance level"). Update the KDoc on
  `Actor.encumbrance` likewise.

- **Algorithmic Steps & Invariants:** rewrite `updateEncumbrance` in `Items.kt:128`, keeping the `inWorld` guard and the
  `guarded("encumbrance of $a")` wrapper:

  1. Remember `wasLevel = a.encumbranceLevel` and `was = a.encumbrance`.
  2. Inside `guarded`: set `a.encumbrance = emptyMap()` and `a.encumbranceLevel = 0`; then `limit =
     rules.carryLimit(RulesContext(this, a))`, `carried = a.carriedWeight()`; `level =
     rules.encumbranceLevel(RulesContext(this, a, limit = limit, carried = carried))`; set `a.encumbranceLevel = level`;
     if `level > 0`, set `a.encumbrance = rules.encumbrance(RulesContext(this, a, limit = limit, carried = carried,
     encumbranceLevel = level))`. If the rules throw, `guarded` leaves level 0 and no deltas, as now.
  3. Changed means `was != a.encumbrance || wasLevel != a.encumbranceLevel`. If changed: `a.dirty = true`.
  4. Tell, by level only: `wasLevel == 0 && level > 0` gives `ENCUMBERED`; `level == 0 && wasLevel > 0` gives
     `UNENCUMBERED`; `level > wasLevel` (both above 0) gives `ENCUMBRANCE_UP`; `level < wasLevel` (both above 0) gives
     `ENCUMBRANCE_DOWN`. Pass `"level" to level, "was" to wasLevel` (follow how other messages pass params in
     `World.tell`).
  5. Call `refreshFit(a, announceFit)` only when `a.encumbrance` itself changed (stat deltas can change slot capacity),
     as the old code did on any change.
  6. `FixtureRules.encumbranceLevel`: `1` if `c.carried!! > c.limit!!` else `0`, so existing fixtures keep their old
     behavior. Add an optional lambda override like the other `...Of` fixtures in that file so tests can install a
     tiered formula.

### Test & Documentation Requirements

Write tests first (project rule), in `ItemCommandsTest.kt`: - The existing test at line ~391 and the one at ~533 must
pass unchanged (they exercise level 1 through the fixture). - New: a tiered fixture formula (0 below 75% of the limit, 1
from 75, 2 from 85, 3 from 90, ratio of `ctx.carried`/`ctx.limit`). Loading an actor step by step produces, in order,
`encumbrance.on`, `.up`, `.up`; dropping gives `.down`, `.down`, `.off`. `p.encumbranceLevel` is 1, 2, 3, then 0. - New:
the level reads 0 inside `encumbranceLevel` and `encumbrance` is not called at level 0 (count calls in the override). -
New: a strength debuff that lowers the limit raises the level at the next tick. - New: the level is not saved. A restore
recomputes it. - Message override: overriding `encumbrance.up` changes the text. - KDoc on every new public member.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings (regenerate the API files first if the new messages changed them, per
  guardrail 4).
- [] `./gradlew markdownFormat markdownCheck` passes.
- [] The new tiered-load test passes, and the two old encumbrance tests pass unmodified.
- [] `git diff` shows no change to `carryFit` or `canScavenge`.

---

## Phase 2: Script layer (export, ctx fields, actor view)

### Objective & Scope

- **In Scope:**
  - The `encumbrance_level` rules export, with its spec entry, typing, neutral default and dry-run check.
  - `ctx.limit`, `ctx.carried`, `ctx.encumbrance_level` on the rules ctx.
  - `actor.encumbrance_level` on the actor view.
  - SPEC export table, field and view text.
  - The regenerated API files and the CHANGELOG bullets for the new export, ctx fields, view field and messages
    (guardrails 3 and 4).
- **Out of Scope:** *(do not attempt these)* the template and robot worlds' `.star` files, tutorials, core changes.

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Rules.kt`: `carryLimit` at ~641 and `encumbrance` at ~645 (override pattern),
    `RulesExport` enum (~785), the `RulesExportSpec` list (~985-1000), the neutral-defaults KDoc (~405-425), the dry run
    (~1394 and ~1525)
  - `script/src/main/kotlin/gups/script/Rules.kt` `RulesCtx` (~60-130, the `item` field at ~114 is the template for a
    nullable field)
  - `script/src/main/kotlin/gups/script/Views.kt`: `level` at ~152 and `carried_weight` at ~400
  - `script/src/main/kotlin/gups/script/Schema.kt` ~188 (a doc string mentioning the `encumbrance` result)
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Rules.kt`
  - `script/src/main/kotlin/gups/script/Views.kt`
  - `script/src/test/kotlin/gups/script/RulesTest.kt`
  - `script/src/main/kotlin/gups/script/DocExamples.kt` (only if a new symbol needs an example)
  - `docs/SPEC.md`
  - `docs/CHANGELOG.md`, `cli/src/test/resources/api.golden.json`, `cli/src/test/resources/docs-golden/*`,
    `docs/api/api-1.json` (regenerated, guardrail 4)
- **Do Not Touch:** `core/` (except to compile against Phase 1), `template-world/`, `samples/`.

### Specification & Decision Record Changes

In `docs/SPEC.md`: - Export table (~line 1382): add after `carry_limit`: `| encumbrance_level(ctx) | kernel | Int, 0 or
more; 0 means unencumbered. |`, and change the `encumbrance(ctx)` row to `stats(...)`, deltas applied while the level is
above 0. - Return-value notes (~1431-1470): add: "`encumbrance_level` returns an int of at least 0; a negative value is
a bad return value. `encumbrance` runs only while the level is above 0." - The `ctx` field list for rules exports: add
`limit` and `carried` (`encumbrance_level`, `encumbrance`) and `encumbrance_level` (`encumbrance`); say each is `None`
elsewhere and that `carry_limit`'s ctx is unchanged. - Actor view **State** list (~line 1204): add `encumbrance_level`
("the level `rules.encumbrance_level` last gave; 0 while that rule is running"). - Kernel feature row (~line 777): add
`encumbrance_level` before `encumbrance`. - Dry run description: say it also calls `encumbrance_level`.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Rules.kt: RulesExport enum, before ENCUMBRANCE
  ENCUMBRANCE_LEVEL("encumbrance_level"),

  // RulesExportSpec list
  RulesExportSpec(
    export = RulesExport.ENCUMBRANCE_LEVEL,
    signature = "encumbrance_level(ctx)",
    returnType = "int",
    ctxFields = listOf("actor", "limit", "carried", "room", "pulse", "rng"),
    doc = "The actor's encumbrance level, 0 for none; the world decides what the levels mean.",
  ),
  // the ENCUMBRANCE spec: ctxFields = listOf("actor", "limit", "carried", "encumbrance_level", "room", "pulse", "rng"),
  //   doc = "Stat deltas applied while the encumbrance level is above 0."

  // Rules class
  override fun encumbranceLevel(c: RulesContext): Int =
    run(RulesExport.ENCUMBRANCE_LEVEL, c, ctxArgs(c), convert = lagOf(RulesExport.ENCUMBRANCE_LEVEL)) ?: 0
  ```

  `RulesCtx`: three `structField` members `limit`, `carried`, `encumbrance_level`, each returning `None` when the
  `RulesContext` field is null, mirroring `item` at line 114. They are readable only in the exports whose spec lists
  them (the `ctxFields` list drives that; follow whatever check `item` uses). `ActorView` (`Views.kt`): add
  `encumbrance_level` after `carried_weight`, as `read("actor.encumbrance_level") { sInt(actor.encumbranceLevel) }`.
  `lagOf` already rejects negatives with the standard message; reuse it. Update the neutral-defaults KDoc list with
  `encumbrance_level: 0`.

- **Algorithmic Steps & Invariants:**

  1. Add the enum entry, the spec, the override and the ctx fields.
  2. The dry run (~1525): call `rules.encumbranceLevel(RulesContext(world, a, limit = 100, carried = 50))` and
     `rules.encumbrance(RulesContext(world, a, limit = 100, carried = 150, encumbranceLevel = 1))` next to the existing
     `rules.encumbrance(c)` call, and update the KDoc at ~1394.
  3. A ruleset missing `encumbrance_level` fails the package load with the usual "missing export" diagnostic, like the
     other required kernel exports. No special case.

### Test & Documentation Requirements

In `RulesTest.kt`, write tests first and mirror the existing `Case(...)` table (~line 424): - A case for
`encumbrance_level` (`ctx`, body `return 2`). - `ctx.limit`, `ctx.carried` and `ctx.encumbrance_level` read correctly in
their exports, and an error naming the field when read in another export. - A negative return is rejected with the
lag-style message ("must be at least 0"). - `ctx.actor.encumbrance_level` reads 0 inside `encumbrance_level` and the
stored level elsewhere (use a modifier or `move_cost` export to read it after the world has recomputed). - A ruleset
without `encumbrance_level` fails to load. - The Kotlin-vs-script equivalence at ~line 1633 gets an `encumbranceLevel`
line.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, after regenerating the API files (guardrail 4). The regenerated diff
  shows only `encumbrance_level` (export), `limit`, `carried` and `encumbrance_level` (ctx fields), `encumbrance_level`
  (actor view) and the two messages.
- [] `./gradlew markdownFormat markdownCheck` passes.
- [] The export, field and view tests above pass.

---

## Phase 3: Template and robot worlds, tutorial

### Objective & Scope

- **In Scope:**
  - Add `encumbrance_level` to `template-world` and `samples/robot-world` so they keep working.
  - The template's tiered formula plus a move and lag penalty (as an example, in its own exports).
  - The tutorial text, the tutorial test, and one CHANGELOG bullet about the template and robot world formulas.
- **Out of Scope:** *(do not attempt these)* engine changes, new engine modifier points, new messages, hooks.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/rules/combat.star` lines ~140-215: `move_cost`, `carry_limit`, `encumbrance`, `_footing`,
    `advance_lag`, `retreat_lag`
  - `samples/robot-world/rules/combat.star` ~130-135
  - `template-world/zones/tutorial/tutorial_test.star` (how `test_tar_walker_boots_cancel_the_tar_pit` loads items and
    checks lag)
  - `template-world/docs/tutorials/` (grep `encumbrance`),
    `samples/heritage-plugin/docs/tutorials/01_traits_and_effective_attributes.md` (mentions encumbrance)
- **Files to Modify / Create:**
  - `template-world/rules/combat.star`
  - `samples/robot-world/rules/combat.star`
  - `template-world/zones/tutorial/tutorial_test.star`
  - the template tutorial markdown that discusses encumbrance (and the heritage tutorial line if it describes the old
    behavior)
  - `docs/CHANGELOG.md` (append one bullet to the Phase 2 subsection)
- **Do Not Touch:** engine Kotlin, `docs/SPEC.md` except as the tutorial references it.

### Specification & Decision Record Changes

None beyond Phase 2. If the template tutorial or the SPEC §3.10 text lists the template's `encumbrance` behavior as "dex
-2 over the limit", reword it to the tiered form. Do not edit SPEC text for a template detail the SPEC doesn't already
state.

### Implementation Blueprint

- **Signatures & Types:** Starlark, in `template-world/rules/combat.star`:

  Write the two functions shown under "Template formulas" below.

  `move_cost`: add `ctx.actor.encumbrance_level` to the `move` cost. `advance_lag` and `retreat_lag`: add
  `ctx.actor.encumbrance_level` pulses (keep `_footing`). Choose numbers that keep existing tutorial tests passing at
  level 0. In `samples/robot-world/rules/combat.star`: add `encumbrance_level` returning `1` if `ctx.carried >
  ctx.limit` else `0`, leaving `encumbrance` as is, so the robot world behaves as before.

- **Algorithmic Steps & Invariants:**

  1. Write the tutorial test first: with `str`-based limit, load an actor to 80% (level 1) and check `move_cost` and
     `advance_lag` rise by 1, then to 95% (level 3) and check dex falls by 3 and costs rise by 3, then drop and check
     everything returns to the base values. Use the pattern in `test_tar_walker_boots_cancel_the_tar_pit`.
  2. Make the `.star` edits until it passes.
  3. Update the tutorial markdown: explain `carry_limit` as the pickup cap, `encumbrance_level` as the world's own
     tiers, and show the move or lag pattern, including a modifier-based variant (`MODIFIER.move_cost` reading
     `ctx.actor.encumbrance_level`) as the way for a trait or item to change it.
  4. Append to the `### Progressive encumbrance` CHANGELOG subsection: the template and robot worlds now define
     `encumbrance_level`, and existing rulesets must add it, with a before/after snippet (`return 1 if ctx.carried >
     ctx.limit else 0` keeps the old behavior). Phase 3 changes no API symbol, so the API files need no regeneration;
     the final `./gradlew check` confirms it.

### Template formulas

For `template-world/rules/combat.star`:

```python
def encumbrance_level(ctx):
    # Tiers by share of the carry limit; a world is free to use race, class or anything on ctx.actor.
    pct = ctx.carried * 100 // ctx.limit if ctx.limit > 0 else 100
    if pct >= 100: return 4
    if pct >= 90: return 3
    if pct >= 85: return 2
    if pct >= 75: return 1
    return 0

def encumbrance(ctx):
    # Heavier tiers cost more dexterity.
    return stats(dex = -ctx.encumbrance_level)
```

### Test & Documentation Requirements

- The tutorial test above runs in the template world's test harness.
- Run each other bundled world's own tests (`samples/*`) to confirm they still load.
- Run `./gradlew markdownFormat`.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including the module-graph check and markdown check.
- [] `git status` shows no change to `api.golden.json`, `docs-golden/` or `docs/api/api-1.json`.
- [] The tutorial test shows level 0, 1 and 3 behavior and the return to base values.

---

## Phase 4: Finalization & Backlog Update

- Add the **M32** row to `docs/SPEC.md` §7.4 after M31, in the same style: "Progressive encumbrance: `encumbrance_level`
  rules export, `ctx.limit`/`ctx.carried`/`ctx.encumbrance_level`, `actor.encumbrance_level`, `encumbrance` only above
  level 0, `encumbrance.up`/`down` messages, the template's and robot world's formulas (§4.6, §4.18, §5.5)."
- Mark the `### Encumbrance beyond stats` entry in `docs/DESIGN_NEEDED.md` as completed (for example a leading "**Done
  in M32.**" line). Do not delete it.
- Run the full test suite (`./gradlew check`) and `./gradlew markdownFormat markdownCheck` and confirm everything is
  green.
