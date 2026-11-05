# Milestone 31: Skill Proficiency

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, section "Combat and skills", heading `### Skill proficiency`.

- **Design Summary:** proficiency decides both **whether** a skill succeeds and **how strong** it is. Items get a
  strength of their own, so a world can let a passive skill (an "artificer") strengthen what items do.

  - **Power.** Every effect primitive runs at a *power*, a percent where 100 means "as declared". It comes from one of
    three places:

    - a cast: `rules.skill_power` (feature `skills`);
    - an item: `rules.item_power` (kernel), for a `use_effect` and for procs from an item or one of its item affects;
    - an actor affect's proc: the power stored on that affect, with no export called.

    Everything else runs at 100: hook verbs, `on_use` scripts, `equip_affects`, plugin calls.

  - **What power scales** (done by the engine, rounded down, a positive value never below 1; zero and negative values
    are left alone):

    - the rolled amount of `damage(...)`, before the caster's `damage` chain;
    - the rolled amount of `heal(...)`;
    - `stun(...)` pulses;
    - an `apply_affect(...)` `duration`, on actors and items alike (`None`, meaning permanent, stays `None`);
    - a `summon(...)` `duration`.

    Nothing else is scaled: stat, sense, max-vital and resist deltas, modifiers, `tick_every`, `knockback`/`pull` steps,
    and `summon`/`dispel` counts. Worlds scale those themselves through `ctx.power` or `ctx.affect.power`.

  - **Affects remember their power.** Every `Affect` (actor or item instance affect) stores the `power` of the effect
    that created it, 100 when there was none, and saves it. The affect view shows it as `power`. When `apply_affect`
    keeps an existing affect because it lasts longer, the kept affect keeps its power. When it replaces one, the new
    affect has the new power.

  - **Modifiers see their affect.** A modifier contributed by an actor affect, or by an item's *instance* affect, gets
    that affect as `ctx.affect`. From any other source (zone, region, room, trait, item prototype, prototype item
    affect) `ctx.affect` is `None`. In rules exports it is `None`, except in `item_power` for a proc of an item instance
    affect, where it is the firing affect.

  - **`ctx.power`** is the int power in a skill's `resolve` function (the cast's power, or the proc's power for a skill
    proc), and `None` in every other hook.

  - **New kernel verb `ctx.summon(caster, label, count=1, duration=None) -> list[mob]`.** It behaves like the `summon`
    primitive at power 100: it spawns beside the caster, sets `summonedBy`, and gives each mob a fading `summoned`
    affect when `duration` is given. It lets a `resolve` pick the mob, for example dog, wolf or bear by proficiency.

  - **`rules.skill_check(ctx)`** (`skills`): the percent chance that `ctx.actor` gets `ctx.skill` off. It is rolled once
    per real cast at resolution, after the warmup and before anything else in `resolve`. It runs through
    `rules.skill_check`, then the caster's `skill_check` modifier chain. A result of 100 or more succeeds without a
    roll. Otherwise the engine rolls `rng.int(1, 100)`, and the cast succeeds when the roll is at most the chance.

    - **On failure:**
      - the full cost is spent and the full `lag` returned;
      - no `effect`, no `resolve`, no landing rolls, and no combat is started;
      - `on_cast` doesn't fire, and the usual "done" messages aren't sent;
      - the caster gets `cast.failed.self`, and onlookers by sight get `cast.failed.room`;
      - the trace shows `rules.skill_check base=72 → affect:focus 82 → roll 91 FAIL` (or `→ roll 40 SUCCEED`, or `→ auto
        SUCCEED`).
  - **`rules.skill_power(ctx)`** (`skills`): the cast's power. It is called once per *successful* cast, after
    `skill_check` and before targets are processed, through the caster's `skill_power` chain. Every target of a `room`
    spell gets the same power.
  - **`rules.item_power(ctx)`** (kernel): the power of an item's effect, through the user's or bearer's `item_power`
    chain.
    - **`use`:** `ctx.event = "use"`; `ctx.target` is the use target actor or `None`; `ctx.item` is the item used.
    - **A proc from an item's own `procs` or its item affects:** `ctx.event` is the trigger word (`"hit"`, `"struck"` or
      `"avoid"`); `ctx.target` is the other actor in the swing; `ctx.item` is the item; `ctx.skill` is set for a skill
      proc.
    - **A proc of an item instance affect** (for example the procs of a brand that an `enchant_weapon` cast put on a
      dagger) runs at `affect.power × item_power / 100`, rounded down. Both the enchanter's skill (stored on the affect)
      and the wielder's (through `item_power`) count. For that call only, `item_power` sees the firing affect as
      `ctx.affect`, so a world can override the default (for example `return 100 * 100 // ctx.affect.power` makes brands
      ignore the wielder's bonus).
    - The item's own `procs` and prototype item affects have no stored power, so they run at plain `item_power`, and
      `ctx.affect` is `None`.
  - **Procs never call `skill_check` or `skill_power`.** A skill proc's `effect` and `resolve` run at the proc's power
    (from `item_power`, or the affect's stored power). A plain effect proc runs at the same proc power.
  - **Mob casts (`act_cast`)** use the normal cast pipeline, so they roll `skill_check` and `skill_power` against the
    mob's prototype proficiency.
  - **Bad return values:**
    - all three exports return an int;
    - a failing or bad `skill_check` counts as 0 (the cast fails), like `to_hit`;
    - a failing or bad `skill_power`/`item_power` counts as 100;
    - a negative `skill_power`/`item_power` result from the export is a bad return value;
    - the final value of a power chain is clamped to 0 or more, and modifiers may go below 0 along the way, as the lag
      points do;
    - `skill_check` has no clamp: 0 or less always fails, 100 or more always succeeds.
  - **Plugins:**
    - `PluginEffectCall` gains `power: Int = 100`;
    - `PluginModifierPoint` gains `SKILL_CHECK`, `SKILL_POWER` and `ITEM_POWER`;
    - `PluginModifierCall` gains `item: PluginItem? = null` and `event: String? = null`, so an artificer trait written
      in Kotlin can see the item.
  - **Shipped formulas.** These keep proficiency 100 at today's behavior.

    Template world:

    ```python
    skill_check = clamp(prof + 20, 5, 100)
    skill_power = 50 + prof // 2 + (level - 1) * 2
    item_power = 100
    ```

    Robot world (no `leveling`): the same `skill_check`, `skill_power = 50 + prof // 2`, and `item_power = 100`.

  - **Gaining proficiency** is out of scope. It belongs to "Acquiring skills" and "Skill-based advancement", whose
    `DESIGN_NEEDED` entries already carry notes on what this design leaves them.

- **Specification Impact:**

  - §3.3 (the affect's fields, if listed);
  - §3.10 (feature table: the `skills` row and the kernel row);
  - §4.2 (Use and Procs bullets);
  - §4.3 (replace the "Proficiency" paragraph; the cast resolution text; the primitive table's notes on power);
  - §4.4 (`on_cast` fires only for a cast that got off);
  - §4.5 (affect view `power`; the `summon` verb; `ctx.power` in `resolve`; the hook ctx field list);
  - §4.6 (rules ctx `affect` and `event`; three export rows; modifier points; chain notes; trace format; dry run);
  - §4.15 (plugin modifier points, `PluginModifierCall`, `PluginEffectCall`);
  - §4.18 (`cast.failed.*` messages);
  - §5.3/§5.4 wherever they describe cast resolution or procs;
  - §7.4 (a new M31 row);
  - `docs/CHANGELOG.md`; the template tutorial `03_items_and_equipment.md`; the regenerated API files.
- **Decision Record Updates:** None. `docs/DECISIONS.md` holds no live records. The `D-024` cited by the source entry no
  longer exists. Do not add new `D-xxx` entries for anything this document settles: the spec is edited directly as
  approved here.

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first and see them fail.
   - No spec section numbers or decision ids in comments, Kotlin or Starlark.
   - All Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`. The engine is
   unreleased, so no migrations or back-compat shims are needed. Saved affects without a `power` field may simply decode
   as 100, and nothing more is needed.
3. Add CHANGELOG bullets to a new `### Skill proficiency` subsection at the top of `## api_version 1 (unreleased)`,
   above `### Mob planning`. Create it in the first phase that adds a bullet, and have each later phase append to it.
4. In a phase that changes the script or plugin API (Phases 1–6 may), regenerate all three API files before the phase's
   final `./gradlew check`:
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
   run `gups` commands directly against `template-world/`; copy it first (`cp -R template-world
   local-world-test/world`).
7. Every engine change in one phase must leave every existing test passing without editing its expectations, unless the
   phase says otherwise. Power 100 and a check of 100 must reproduce today's behavior exactly, RNG draws included: **do
   not draw from `rng` when the check is 100 or more**.

---

## Phase 1: Effect power in the kernel

### Objective & Scope

- **In Scope:**

  - a `power` parameter threaded through effect application;
  - the scaling rules;
  - `Affect.power`, saved and restored;
  - the affect view's `power`;
  - `PluginEffectCall.power`.

  Every caller passes 100, so nothing observable changes except `affect.power == 100`.

- **Out of Scope (do not implement these in this phase):** any rules export, `ctx.affect`, `ctx.power`, `ctx.summon`,
  casts or procs computing a power other than 100.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/model/Effects.kt` (the `Effect` variants);
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt:200-296` (`resolve`, `spellLands`);
  - `core/src/main/kotlin/gups/core/world/Procs.kt:103-166`;
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt:930-950` (`use` applying its effect);
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt` (where `PluginEffectCall` is built; search for
    `PluginEffectCall(`).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Combat.kt`: `applyEffect` (line 580), `applyItemEffect` (line 653),
    `applyAffect` (line 354), `applyItemAffect` (line 455).
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Affect`, line 185).
  - `persist/src/main/kotlin/gups/persist/Codec.kt` (the `Affect(` encode/decode near line 376).
  - `script/src/main/kotlin/gups/script/Views.kt` (affect view, line 804).
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt` (`PluginEffectCall`, line 216) and wherever core calls
    `pluginEffects.apply` (search `fun apply(` in `core/src/main/kotlin/gups/core/world/World.kt:118`) and its adapter.
  - Tests: `core/src/test/kotlin/gups/core/world/` (a new `EffectPowerTest.kt`), plus a persist round-trip test next to
    the existing affect codec tests.
- **Do Not Touch:** `script/.../Rules.kt`, `SkillCommands.kt`, `Procs.kt`, `KernelCommands.kt` (callers keep the
  default), any world.

### Specification & Decision Record Changes

- §4.5, the **Affect view** line: add `power` after `applies`. Add one sentence after the line: "`power` is the percent
  strength the affect was applied at (§4.3); 100 when nothing scaled it."
- §3.3: if the live-entity text lists an affect's fields, add `power` (int, default 100) there.
- §4.15: under the plugin effect primitives, document `PluginEffectCall.power` ("the percent power the effect runs at,
  100 outside casts and item effects; the primitive decides what it scales").

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Combat.kt
fun World.applyEffect(
  effect: Effect, caster: Actor, target: Actor?, skill: SkillProto? = null, power: Int = 100,
)
fun World.applyItemEffect(effect: Effect, caster: Actor, item: Item, power: Int = 100)
// applyAffect / applyItemAffect: add a trailing `power: Int = 100` parameter, stored on the Affect.

/** Scales a declared amount by [power] percent: rounded down, never below 1 when it was positive. */
internal fun scaled(amount: Int, power: Int): Int =
  if (amount <= 0) amount else maxOf(1, (amount.toLong() * power / 100).toInt())

// Entities.kt, class Affect: new constructor property, last, with a default
/** Percent strength this affect was applied at; 100 when nothing scaled it. */
val power: Int = 100,

// PluginApi.kt
data class PluginEffectCall(
  ..., val targetItem: PluginItem? = null,
  /** Percent strength the effect runs at; 100 outside casts and item effects. */
  val power: Int = 100,
)
```

- **Algorithmic Steps & Invariants:**
  1. In `applyEffect`, pass `power` down through `Sequence` and to `Plugin`.
     - `Damage`: `resolveDamage(c, scaled(effect.dice.roll(rng), power), effect.type)`. Roll first, then scale; the
       number of RNG draws must not change.
     - `Heal`: `heal(t, effect.vital, scaled(effect.dice.roll(rng), power))`.
     - `Stun`: `applyAffect(..., scaled(effect.pulses, power), ..., power = power)`.
     - `ApplyAffect`: duration `scaled(duration, power)`, `power = power`.
     - `Summon`: the `summoned` affect's duration `scaled(it, power)`. The count is unscaled.
     - `Knockback`, `Pull`, `Dispel` are unchanged.
  2. In `applyItemEffect`: `ApplyAffect` duration is `effect.duration?.let { scaled(it, power) }`; pass `power`. Pass
     `power` to `Plugin` too.
  3. `applyAffect` and `applyItemAffect` store `power` on the new `Affect`. The early return that keeps a longer-lasting
     existing affect is unchanged, so that affect keeps its own power.
  4. Codec: encode `power`. Decode a missing field as 100. Follow how the codec handles another defaulted affect field
     (for example `procs` or `wornFrom`).
  5. Affect view: `@StarlarkMethod(name = "power", structField = true, doc = "Percent strength the affect was applied
     at; 100 when nothing scaled it.")`, read the same way as the view's other fields.
  6. With every caller passing 100, `scaled(x, 100) == x` for every `x`, so behavior is unchanged.

### Test & Documentation Requirements

- `EffectPowerTest.kt`, driving `applyEffect`/`applyItemEffect` directly on a core fixture world (mirror the existing
  core tests that build a `World` from `core/src/testFixtures/.../FixtureRules.kt`):
  - `damage` at power 150 deals 1.5× the fixed roll (use dice like `"10d1"` so the roll is fixed), and at power 0 deals
    at least 1 before mitigation;
  - `heal` scales the same way;
  - `stun` at 200 lasts twice as long;
  - `apply_affect` duration scales, and the affect's `power` is stored;
  - a `summon` with a duration gives the `summoned` affect a scaled duration, and the count is unscaled;
  - an item `apply_affect` with `duration = None` stays permanent at power 300;
  - re-applying a same-named affect that lasts less keeps the first affect and its power;
  - `scaled` unit cases: `(0, 50) → 0`, `(-3, 200) → -3`, `(1, 10) → 1`, `(7, 150) → 10`.
- A persist round-trip: an affect with power 137 survives save and load; an encoded affect without the field decodes to
  100\.
- A script test (in `script/src/test/kotlin/gups/script/`, beside the existing affect-view tests) that
  `ctx.actor.affects[0].power == 100` for an affect applied by a hook verb.
- KDoc on `scaled`, `Affect.power` and `PluginEffectCall.power`.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] The API files are regenerated. The only new symbols are the affect view's `power` and `PluginEffectCall.power`.
- [] No existing test was edited.

---

## Phase 2: `ctx.affect` in modifiers and the `ctx.summon` verb

### Objective & Scope

- **In Scope:**
  - modifiers contributed by an actor affect or an item instance affect see that affect as `ctx.affect`;
  - a new kernel hook verb `ctx.summon(caster, label, count=1, duration=None)`.
- **Out of Scope (do not implement these in this phase):** new rules exports or modifier points; `ctx.power`; any power
  other than 100.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Holdings.kt:12-60, 120-170` (`HeldModifiers`, `HeldAffect.instance`, where
    `modifierSteps` are built);
  - `script/src/main/kotlin/gups/script/Rules.kt:58-200` (`RulesCtx` fields; mirror how `event` and `item` are exposed);
  - `script/src/main/kotlin/gups/script/Ctx.kt:1251-1300` (the `spawn` verb; mirror its argument checking and mob
    spawning);
  - §4.5 verbs table, §4.6 rules ctx paragraph.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt` (`RulesContext`, line 21: add `affect`; also add it to
    `withDamageType` and any other copy helper that lists every field);
  - `core/src/main/kotlin/gups/core/world/Rules.kt` (`ChainStep.Script`, `chainSteps`);
  - `core/src/main/kotlin/gups/core/world/Holdings.kt` (`HeldModifiers` gains `affect: Affect?`);
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (extract `World.summon`);
  - `script/src/main/kotlin/gups/script/Rules.kt` (`RulesCtx.affect`);
  - `script/src/main/kotlin/gups/script/Ctx.kt` (`summon` verb);
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, tests.
- **Do Not Touch:** `SkillCommands.kt`, `Procs.kt`, `KernelCommands.kt`, worlds.

### Specification & Decision Record Changes

- §4.6, rules ctx paragraph: add "`affect` (in a modifier contributed by an actor affect or an item's instance affect,
  that affect's view, so the modifier can read `ctx.affect.power`; `None` everywhere else, including in the exports)".
- §4.5 verbs table, the **Vitals and combat** row: add `summon(caster, label, count=1, duration=None)`. Below the table,
  add a bullet:
  - "**`summon`:** spawns `count` (1 or more) mobs of prototype `label` beside `caster`, each with `summonedBy` set to
    `caster`. With a `duration` (1 or more) each gets a `summoned` affect; when it ends, the mob fades away as for the
    `summon` primitive (§4.3). Returns the mobs as a list. It is not scaled by power."
- §3.10 kernel row: add the `summon` verb next to `apply_affect` and `remove_affect`.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// RulesContext: new last property
/** The affect whose modifier is running, or null outside an affect's modifier. */
val affect: Affect? = null,

// Holdings.kt
data class HeldModifiers(
  val source: String, val item: Item, val modifiers: Modifiers, val affect: Affect? = null,
)

// Rules.kt (core)
class Script(override val source: String, val ref: HookRef, val affect: Affect? = null) : ChainStep

// Combat.kt
/** Spawns [count] mobs of [label] beside [caster] as its summons; [duration] null means they stay. */
fun World.summon(caster: Actor, label: Label, count: Int, duration: Int?, power: Int = 100): List<Mob>
```

- **Algorithmic Steps & Invariants:**
  1. In `chainSteps`, actor affects produce `ChainStep.Script("affect:${a.name}", it, affect = a)`. Held steps pass
     `step.affect`. In `Holdings.add(ha)`, set `affect = ha.instance`, which is null for a prototype item affect. Item
     prototype modifiers pass null.
  2. In `ChainStep.Script.apply`, call `world.scripts.modify(...)` with `c` copied so that `affect` is the step's
     affect. Add a `RulesContext.withAffect(a: Affect?)` copy helper next to `withDamageType`, listing every field
     explicitly the way `withDamageType` does.
  3. `RulesCtx.affect`: a struct field returning the affect view (reuse the class at `Views.kt:804`) or `None`.
  4. Move the body of the `Effect.Summon` branch of `applyEffect` into `World.summon`, and have the branch call it with
     the scaled duration. Behavior is unchanged.
  5. `ctx.summon` verb:
     - check that `caster` is an actor in the world, `label` resolves (relative to the calling package, as `spawn` does)
       to a mob prototype, `count >= 1` and `duration` is `None` or `>= 1`. Each failure is a script error mirroring
       `spawn`'s messages;
     - check the verb is allowed in the calling hook the same way `spawn` is;
     - return a Starlark list of mob views.

### Test & Documentation Requirements

- Script tests:
  - an affect modifier on `defend_hit` that returns `base - ctx.affect.power // 10`, applied by a hook verb, yields
    `base - 10` (power 100);
  - a room modifier at the same point sees `ctx.affect == None`;
  - an item instance affect's modifier sees its affect, and a prototype item affect's modifier sees `None`.
- `ctx.summon` tests:
  - it spawns `count` mobs with `summonedBy` set;
  - with `duration`, the mob fades when the affect ends;
  - an unknown label, a count of 0 and a duration of 0 each raise a script error.
- CHANGELOG bullets: "Modifiers contributed by an affect see it as `ctx.affect` (`ctx.affect.power`)." and "New hook
  verb `ctx.summon(caster, label, count=1, duration=None)`."

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] The API files are regenerated. The only new symbols are `RulesCtx.affect` and `ctx.summon`.
- [] No existing test was edited.

---

## Phase 3: The three rules exports, in every ruleset

### Objective & Scope

- **In Scope:**

  - `skill_check` and `skill_power` (feature `skills`) and `item_power` (kernel) as rules exports: Kotlin `RulesEngine`
    methods, script dispatch, export docs, feature ownership, the dry run;
  - definitions in every rules package: the template, the robot world, the test fixtures, inline test rulesets;
  - test helpers that neutralize the formulas in copied template rules.

  The engine calls the exports **only from the dry run** in this phase.

- **Out of Scope (do not implement these in this phase):** modifier points (Phase 4); casts, `use` or procs calling the
  exports (Phases 5–6); robot-world tests and the tutorial (Phase 7).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt:180-305`. Note that newer methods such as `senseCost` have
    default bodies.
  - `script/src/main/kotlin/gups/script/Rules.kt:491-510` (`intOf`, `nonNegative`), `:555-600` (dispatch), `:743-777`
    (`RulesExport`), `:880-910` (`RULES_EXPORTS` entries), `:1300-1470` (`dryRunRules`).
  - `script/src/main/kotlin/gups/script/Ownership.kt:19`.
  - `script/src/testFixtures/kotlin/gups/script/testing/TestWorlds.kt:67-130, 207-245`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt`;
  - `script/src/main/kotlin/gups/script/Rules.kt`;
  - `script/src/main/kotlin/gups/script/Ownership.kt`;
  - `script/src/testFixtures/kotlin/gups/script/testing/TestWorlds.kt` (`DEFAULTS`, the feature map near line 100,
    `makeSpellsLand`);
  - inline rulesets in `script/src/test/kotlin/gups/script/RulesTest.kt`, `FeatureScriptTest.kt` and
    `TraitScriptTest.kt`, plus any other found by the grep in step 6;
  - `template-world/rules/combat.star` and `template-world/rules/manipulation.star`;
  - `samples/robot-world/rules/combat.star`, and whichever robot-world rules file holds its kernel item exports (for
    `item_power`);
  - `docs/SPEC.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `core/src/main/kotlin/gups/core/world/` (no engine call sites yet).

### Specification & Decision Record Changes

- §4.6 export table, three new rows:
  - after `interrupt_check`:

    ```
    | `skill_check(ctx)` | `skills` | Percent chance that `ctx.actor` gets `ctx.skill` off, rolled once per cast at resolution (§4.3). `ctx.target`, `ctx.item` (an `item` skill's item). |
    | `skill_power(ctx)` | `skills` | Percent strength of a successful cast; 100 is the skill as declared (§4.3). Same fields as `skill_check`. |
    ```
  - after `encumbrance`:

    ```
    | `item_power(ctx)` | kernel | Percent strength of what `ctx.item` does for `ctx.actor`: its `use_effect`, or a proc of the item or its item affects (§4.2). `ctx.event` (`"use"`, or the proc trigger), `ctx.target`, `ctx.skill` (a skill proc). |
    ```
- §4.6 "Signatures and results": add a bullet:
  - "`skill_check`, `skill_power` and `item_power` return ints. A failing `skill_check` counts as 0; a failing
    `skill_power` or `item_power` counts as 100, and a negative result from either is a bad return value. Power is used
    as §4.3 describes."
- §4.6 dry-run paragraph: say that with `skills` on, the dry run also calls `skill_check` and `skill_power` with the
  synthetic skill, and that it always calls `item_power` with no item and `ctx.event = "use"`.
- §3.10: the `skills` row gains `skill_check` and `skill_power` (the exports); the kernel row gains `item_power`.
- §4.3: none yet (Phase 5).

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// RulesEngine.kt: defaults keep Kotlin doubles working unchanged
/** Percent chance that c.actor gets c.skill off; 100 or more always succeeds. */
fun skillCheck(c: RulesContext): Int = 100
/** Percent strength of c.actor's successful cast of c.skill; 100 is as declared. */
fun skillPower(c: RulesContext): Int = 100
/** Percent strength of what c.item does for c.actor (use, or a proc); 100 is as declared. */
fun itemPower(c: RulesContext): Int = 100

// script Rules.kt
enum class RulesExport { ..., SKILL_CHECK("skill_check"), SKILL_POWER("skill_power"), ITEM_POWER("item_power"), }
```

- **Algorithmic Steps & Invariants:**
  1. Script dispatch, mirroring `toHit` and `fleeChance`:

     - `skillCheck = run(SKILL_CHECK, c, ctxArgs(c), convert = intOf(SKILL_CHECK)) ?: 0`;
     - `skillPower = run(SKILL_POWER, …, convert = <int >= 0>) ?: 100`;
     - `itemPower` likewise with `?: 100`.

     For the `>= 0` check, write a converter mirroring `nonNegative` whose failure message is "bad return value" in the
     same wording as other negative-int rejections.

  2. `RULES_EXPORTS`: three `RulesExportSpec` entries with signature, `returnType = "int"`, `ctxFields` and a one-line
     doc:

     - `skill_check`/`skill_power`: `actor`, `target`, `item`, `skill`, `room`, `pulse`, `rng`;
     - `item_power`: `actor`, `target`, `item`, `skill`, `event`, `affect`, `room`, `pulse`, `rng`.
  3. `Ownership.kt`: add `"rules:skill_check"` and `"rules:skill_power"` to the `Feature.SKILLS` entry. `item_power` is
     kernel and needs no entry. Check how kernel exports are listed (if at all) and follow that.
  4. `dryRunRules`: in the `Feature.SKILLS` block call `rules.skillCheck(withSkill)` and `rules.skillPower(withSkill)`.
     For every actor call `rules.itemPower(RulesContext(world, a, other, event = "use"))`. Update the KDoc bullet list
     above the function.
  5. **Template world:** add `skill_check` and `skill_power` to `combat.star` after `cast_allowed`, and `item_power` to
     `manipulation.star`, exactly as in "Ruleset formulas" below.
  6. **Robot world:** in `samples/robot-world/rules/combat.star`, the same `skill_check` and the robot `skill_power`
     from "Ruleset formulas"; in `samples/robot-world/rules/manipulation.star`, the same `item_power`.
  7. **Test fixtures:**
     - `TestWorlds.DEFAULTS` gains `def skill_check(ctx): return 100`, `def skill_power(ctx): return 100` and `def
       item_power(ctx): return 100`;
     - the feature map near line 100 marks `skill_check` and `skill_power` as `setOf("skills")`;
     - `makeSpellsLand` also rewrites top-level `skill_check` and `skill_power` blocks to `return 100`, as it does for
       `_spell_hit`. Update its KDoc and rename nothing.
     - Find every other ruleset with `grep -rln "def cast_allowed\|def door_intensity" --include='*.kt'
       --include='*.star' . | grep -v local-world`. Add `item_power` to each, and `skill_check`/`skill_power` to each
       one with `skills` on, all returning 100.
  8. A world whose `skills` feature is off must *not* define `skill_check`/`skill_power`, so check each inline ruleset's
     features before adding them.
  9. **Tests that run real formulas.** Audit every test that loads the template's or robot world's rules without going
     through `copyWorld`/`makeSpellsLand`, or that passes `spellsAlwaysLand = false`:
     - `cli/src/test/kotlin/gups/cli/CommandsTest.kt`;
     - `script/src/test/kotlin/gups/script/TestWorldsTest.kt`;
     - the three sample plugin tests (`WeatherPluginTest`, `CurseWorldTest`, `HeritagePluginTest`);
     - `harness/src/test/kotlin/gups/harness/` `FuzzWalkerTest`, `ShopWithoutCombatTest`, `DocExamplesTest` and
       `TutorialTest`;
     - any further hit of `grep -rln "spellsAlwaysLand = false\|template-world/rules\|gups.templateWorld\|robot-world"
       --include='*Test.kt' .`.

     For each one that casts a skill at a proficiency below 80, or without knowing it, route its rules through
     `makeSpellsLand`, or give the caster proficiency 100. Do this in this phase, so that Phase 5 edits no existing
     test. `TestWorldsTest` may need a new assertion for the extended `makeSpellsLand` rewrite.

#### Ruleset formulas

Template world, `template-world/rules/combat.star`, after `cast_allowed`:

```python
def skill_check(ctx):
    # Percent chance that ctx.actor gets ctx.skill off. Proficiency 80 and up never fails.
    prof = ctx.actor.skills.get(ctx.skill.label, 0)
    return _clamp(prof + 20, 5, 100)

def skill_power(ctx):
    # Percent strength of ctx.actor's ctx.skill: 100 is the skill as declared.
    # Half from proficiency, plus 2 per level beyond the first.
    prof = ctx.actor.skills.get(ctx.skill.label, 0)
    return 50 + prof // 2 + (ctx.actor.level - 1) * 2
```

Template world, `template-world/rules/manipulation.star` (the robot world's `manipulation.star` gets the same function):

```python
def item_power(ctx):
    # Percent strength of what ctx.item does for ctx.actor: its use_effect (ctx.event "use") or a
    # proc (ctx.event "hit", "struck" or "avoid"). 100 is the item as declared; a world can base it
    # on a skill of the user's, such as an artificer's.
    return 100
```

Robot world, `samples/robot-world/rules/combat.star`: the template's `skill_check` (define `_clamp` there if the file
has no such helper), and:

```python
def skill_power(ctx):
    # Percent strength of ctx.actor's ctx.skill: 100 is the skill as declared. This world has no
    # levels, so proficiency alone decides it.
    prof = ctx.actor.skills.get(ctx.skill.label, 0)
    return 50 + prof // 2
```

### Test & Documentation Requirements

- `RulesTest.kt`:
  - each export is dispatched and its int returned;
  - a negative `skill_power` falls back to 100 with a diagnostic;
  - a script error in `skill_check` gives 0;
  - a missing `item_power` is a load error;
  - defining `skill_check` with `skills` off is a load error naming the feature (mirror the existing `cast_allowed`
    ownership test).
- A dry-run test that a `skill_power` returning a string is reported.
- CHANGELOG: "New rules exports `skill_check` and `skill_power` (`skills`) and `item_power` (kernel). Every ruleset must
  define them; the engine starts calling them in later changes of this release."

### Exit Conditions

- [] `./gradlew check` passes with zero warnings. Robot-world and template `*_test.star` files pass unchanged.
- [] The API files are regenerated. Only the three exports are new.
- [] `grep` finds `item_power` in every rules package, and `skill_check`/`skill_power` in every one with `skills` on.

---

## Phase 4: Modifier points `skill_check`, `skill_power`, `item_power`

### Objective & Scope

- **In Scope:**
  - the three modifier points in core and script, with feature ownership;
  - the `World` chain helpers that run each export plus its chain, with trace text;
  - plugin trait modifier points;
  - `PluginModifierCall.item` and `PluginModifierCall.event`.
- **Out of Scope (do not implement these in this phase):** call sites in casts, `use` and procs (Phases 5–6).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Rules.kt:95-165` (`runSteps`, `chain`, `traced`, `toHitChain`) and `:252-270`
    (`castAllowed`);
  - `core/src/main/kotlin/gups/core/model/Protos.kt:14-70` (`ModifierPoint`);
  - `script/src/main/kotlin/gups/script/Rules.kt:748-760` (script `ModifierPoint`);
  - the lag points' clamp-to-0 handling (search `ADVANCE_LAG` in core `Rules.kt`);
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt` (how `PluginModifierPoint` maps and how
    `PluginModifierCall` is built, including `skill`).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt`;
  - `core/src/main/kotlin/gups/core/world/Rules.kt`;
  - `script/src/main/kotlin/gups/script/Rules.kt` (the modifier-point enum/table and value checks);
  - `script/src/main/kotlin/gups/script/Ownership.kt`;
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt` and `.../host/Adapters.kt`;
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, `docs/PLUGINS.md` (if it lists the plugin modifier points), tests.
- **Do Not Touch:** `SkillCommands.kt`, `Procs.kt`, `KernelCommands.kt`, worlds.

### Specification & Decision Record Changes

- §4.6, **Modifier points (v0)**: add `skill_check`, `skill_power` and `item_power`. Then:
  - in the ownership sentences: "`skill_check` and `skill_power` belong to `skills`; `item_power` is kernel";
  - in the value sentence: "modifiers of the three take and return ints; a power chain's final value is clamped to 0,
    like the lag points";
  - in the whose-chain sentence: "the caster's for `skill_check` and `skill_power`, and the user's or bearer's for
    `item_power`".
- §4.6 tracing paragraph: "`skill_check` ends in `→ roll N SUCCEED`, `→ roll N FAIL` or `→ auto SUCCEED`; the power
  chains are traced as plain chains (`rules.skill_power base=118 → trait:artificer 130`)."
- §4.15: list the three new `PluginModifierPoint` values. Document `PluginModifierCall.item` ("the item whose
  `use_effect` or proc this is, for `ITEM_POWER`; the target item of an `item` skill for `SKILL_CHECK` and
  `SKILL_POWER`; else null") and `PluginModifierCall.event` ("`ctx.event` of the rules call: `use` or a proc trigger for
  `ITEM_POWER`; else null").

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Protos.kt ModifierPoint
/** Modifies the chance that a cast gets off. */
SKILL_CHECK("skill_check"),
/** Modifies the percent strength of a successful cast. */
SKILL_POWER("skill_power"),
/** Modifies the percent strength of an item's use effect or proc. */
ITEM_POWER("item_power"),

// core Rules.kt
/** Whether c.actor gets c.skill off: the export, its chain, then a roll unless the chance is 100+. */
fun World.skillSucceeds(c: RulesContext): Boolean
/** The power of c.actor's successful cast of c.skill, clamped to 0 or more. */
fun World.skillPowerChain(c: RulesContext): Int
/** The power of c.item's effect for c.actor, clamped to 0 or more. */
fun World.itemPowerChain(c: RulesContext): Int

// PluginApi.kt
enum class PluginModifierPoint { ..., SKILL_CHECK, SKILL_POWER, ITEM_POWER }
data class PluginModifierCall(..., val skill: PluginSkill? = null,
  val item: PluginItem? = null, val event: String? = null)
```

- **Algorithmic Steps & Invariants:**
  1. `skillSucceeds`:

     - `val r = chain(SKILL_CHECK, c, rules.skillCheck(c)) { "$it" }`;
     - if `r.value >= 100`, trace `"${r.text} → auto SUCCEED"` and return true **without** drawing from `rng`;
     - else `val roll = rng.int(1, 100)`, trace `"${r.text} → roll $roll SUCCEED|FAIL"` and return `roll <= r.value`.

     Mirror `spellLands` (`SkillCommands.kt:279`).

  2. `skillPowerChain` and `itemPowerChain`: `traced(c, chain(point, c, rules.x(c)) { "$it" })`, then
     `coerceAtLeast(0)`. Apply the clamp the way the lag points do, and keep the trace text consistent with them.

  3. The swinging-weapon filter in `chainSteps` stays limited to `TO_HIT`, `DAMAGE` and `BONUS_DAMAGE`.

  4. Script modifier-point table: register the three names with int values. Ownership: `"modifier:skill_check"` and
     `"modifier:skill_power"` go under `Feature.SKILLS`; `item_power` is kernel.

  5. Plugin adapter: map the three points; fill `item = c.item?.let(::adapt)` and `event = c.event` for every call (they
     are null where unset).

### Test & Documentation Requirements

- Core tests using a Kotlin `RulesEngine` double (`core/src/test/kotlin/gups/core/world/Fixture.kt` pattern):
  - `skillSucceeds` with 100 doesn't touch the RNG (compare `rng` state or the next draw before and after);
  - with 0 it always fails;
  - with 50 and a seeded RNG the trace shows `roll N` and the outcome.
- Script tests:
  - a room `skill_power` modifier adding 20 gives 120;
  - a modifier chain driving `item_power` to −30 gives 0;
  - naming `skill_check` in `package(modifiers=)` with `skills` off is a load error.
- A plugin test (`plugin-api/src/test/...`, mirror the `PluginModifierCall.skill` test added in M29) that a trait
  `ITEM_POWER` modifier sees `item` and `event`.
- CHANGELOG bullet listing the three modifier points and the new plugin fields.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] The API files are regenerated. Only the three points and the two `PluginModifierCall` fields are new.
- [] No existing test was edited.

---

## Phase 5: Casts roll `skill_check` and run at `skill_power`

### Objective & Scope

- **In Scope:**
  - cast resolution rolls `skill_check`: the failure path, messages and trace;
  - successful casts compute `skill_power` and pass it to every `applyEffect`/`applyItemEffect` and to `resolve` as
    `ctx.power`;
  - mob casts follow automatically.
- **Out of Scope (do not implement these in this phase):** procs and `use` (Phase 6); robot-world tests and the tutorial
  (Phase 7).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt` (whole file, 296 lines; `SkillMsg` near line 30);
  - `core/src/main/kotlin/gups/core/world/Actions.kt:151` (`SkillResolver`);
  - the script implementation of `SkillResolver` (search `SkillResolver` in `script/src/main`) and `CtxExtras` in
    `Ctx.kt` (mirror how `commandArgs` is passed into a `Ctx`);
  - §4.18 "Cast resistance entries" (`SPEC.md:2619`) for message wording style.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt`;
  - `core/src/main/kotlin/gups/core/world/Actions.kt` (`SkillResolver.resolve` gains `power: Int`);
  - the script `SkillResolver` implementation (`script/src/main/kotlin/gups/script/Host.kt:808`) and `Ctx.kt`
    (`ctx.power`);
  - `core/src/test/kotlin/gups/core/world/SpellLandingTest.kt:42`, the only test implementing `SkillResolver`. Its
    lambda gains a fifth `_` parameter. This is the one existing-test edit this phase allows.
  - `core/src/main/kotlin/gups/core/world/Procs.kt`, only to pass `power = 100` to `resolve` for now;
  - wherever engine messages are registered for `SkillMsg` (follow `RESISTED_SELF`);
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, tests.
- **Do Not Touch:** `KernelCommands.kt`, the proc power logic (Phase 6), worlds.

### Specification & Decision Record Changes

- §4.3: replace the paragraph "**Proficiency** is stored but not rolled against by the engine. …" with:

  > **Success and power:** when a cast resolves (after any warmup), the engine first asks whether it gets off:
  > `rules.skill_check` with `ctx.skill` set, then the caster's `skill_check` chain (§4.6). A chance of 100 or more
  > succeeds without a roll. On a failure the cost and lag are paid, the caster is told `cast.failed.self` and onlookers
  > `cast.failed.room`, and nothing else happens: no effect, no `resolve`, no landing roll, no engagement and no
  > `on_cast`. A successful cast then gets its **power**, `rules.skill_power` and the caster's `skill_power` chain, a
  > percent where 100 is the skill as declared; every target of the cast shares it. Rules usually base both on the
  > caster's proficiency, `ctx.actor.skills[ctx.skill.label]`.
  >
  > **Power** scales what the effect primitives do: the rolled amount of `damage` (before the `damage` chain) and of
  > `heal`, `stun` pulses, and the `duration` of `apply_affect` and of `summon`, rounded down, a positive value never
  > below 1. Nothing else is scaled. An affect remembers the power it was applied at (`affect.power`), and its modifiers
  > can read it as `ctx.affect.power`. `resolve` gets it as `ctx.power`. Effects outside casts run at 100, except items'
  > (§4.2). Skill procs never roll `skill_check` or `skill_power`.

- §4.3 **Landing:** add at the start: "A cast that passed `skill_check` then rolls to land:". Replace "`ally`, `self`,
  `item` and `none` skills always land." with "`ally`, `self`, `item` and `none` skills always land once they get off."
- §4.5: in the hook ctx field list, add `power` ("in a skill's `resolve`, the cast's power, §4.3").
- §4.18: add after the cast resistance entries: "Cast failure entries include `cast.failed.self` (`"You fail to cast
  {skill}."`, placeholder `skill`) and `cast.failed.room` (`"{actor}'s spell fizzles."`, placeholder `actor`)."
- §4.4, the hook catalog row `before_cast` / `on_cast_start` / `on_cast` (`SPEC.md:1085`), or the text under the table
  if it describes when `on_cast` fires: say that `on_cast` fires only for a cast that got off, never after a failed
  `skill_check`.
- §5.3 (around `SPEC.md:2909-2924`, the action lifecycle) and §5.4: wherever cast resolution is narrated step by step,
  insert the `skill_check` step before landing, and note that a failed check skips the `on_*` hooks of the cast.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
fun interface SkillResolver {
  /** [item] is the target of an `item` skill, else null; [power] is the cast's percent power. */
  fun resolve(world: World, caster: Actor, target: Actor?, item: Item?, power: Int)
}
// SkillMsg: FAILED_SELF ("cast.failed.self"), FAILED_ROOM ("cast.failed.room")
```

- **Algorithmic Steps & Invariants** (in the cast action's `resolve`, `SkillCommands.kt:201`):
  1. Spend the cost (unchanged first line).
  2. `val c = RulesContext(w, actor, target, item = targetItem, skill = skill)`. If `!w.skillSucceeds(c)`:
     - `w.tell(actor, SkillMsg.FAILED_SELF, "skill" to skill.name)`;
     - `w.tellSight(actor.room, "cast", actor, w.msg(SkillMsg.FAILED_ROOM, "actor" to actor.name.cap()), actor)`;
     - `return skill.lag`.

     No "done" messages, no `startCombat`, no `ON_CAST`.

  3. Otherwise `val power = w.skillPowerChain(c)`, then continue exactly as today. Pass `power` to `applyItemEffect`,
     `applyEffect` and `skill.resolve?.resolve(..., power)`.

  4. The script resolver puts `power` into the `Ctx` (through `CtxExtras` or the field the resolver already uses).
     `ctx.power` is a struct field: an int in `resolve`, `None` elsewhere.

  5. `Procs.kt`: pass `power = 100` to `resolve` (Phase 6 replaces it).

  6. Message defaults go wherever `cast.resisted.*` are declared, with the same placeholder checks.

### Test & Documentation Requirements

- Script tests (in `script/src/test/kotlin/gups/script/`, beside the spell-landing tests from M29; their rulesets come
  from `TestWorlds.rulesFor` overrides):
  - `skill_check` returning 0: the caster loses the cost, waits the lag, sees "You fail to cast …", the room sees "…
    spell fizzles."; the enemy target's hp is unchanged, combat isn't started, `on_cast` doesn't fire, and `resolve`
    doesn't run;
  - `skill_check` returning 100: unchanged behavior, with no extra RNG draw (an existing seeded spell test still passes
    untouched);
  - `skill_power` returning 150 with `damage(dice="10d1")`: the target takes 15 before mitigation;
  - an `apply_affect` skill at 200 lasts twice as long, and `affect.power == 200`;
  - `resolve` sees `ctx.power == 150`;
  - an affect modifier reading `ctx.affect.power` gives a bigger bonus at power 200 than at 100 (the levitation case);
  - a `resolve` that picks a mob label from `ctx.actor.skills` and calls `ctx.summon` spawns the expected prototype (the
    druid case);
  - a mob `act_cast` with a failing check fails the same way;
  - the trace shows `rules.skill_check … FAIL` and `rules.skill_power base=…`.
- CHANGELOG: "Casts roll `skill_check` at resolution (failure: `cast.failed.*`, cost and lag paid, nothing else) and run
  at `skill_power`; `resolve` gets `ctx.power`."

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] The API files are regenerated. Only `ctx.power` and the two messages are new.
- [] Existing spell tests pass unedited (fixtures return 100 for both exports), apart from the `SpellLandingTest` lambda
  signature.

---

## Phase 6: Item power for `use` and procs

### Objective & Scope

- **In Scope:**
  - `use` with a `use_effect` runs at `item_power`;
  - procs from an item or its item affects run at `item_power`;
  - procs from an actor affect run at that affect's stored power;
  - skill procs pass that power to `effect` and `resolve`.
- **Out of Scope (do not implement these in this phase):** robot-world tests, tutorial, finalization (Phase 7).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Procs.kt` (whole file);
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt:820-950` (the `use` action).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Procs.kt`;
  - `core/src/main/kotlin/gups/core/world/Holdings.kt` (`HeldProcs` gains `affect: Affect? = null`, set from
    `ha.instance` in `add(ha)`; an item's own `procs` pass null);
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt`;
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, tests.
- **Do Not Touch:** `SkillCommands.kt`, worlds.

### Specification & Decision Record Changes

- §4.2 **Use:** append "The `use_effect` runs at the item's power: `rules.item_power` with `ctx.item` the item,
  `ctx.event = "use"` and `ctx.target` the target actor (or `None`), then the user's `item_power` chain (§4.3, §4.6). An
  `on_use` hook's verbs run at 100."
- §4.2 **Procs:** append "A proc's effects run at a power (§4.3): for a proc of an item or one of its item affects,
  `rules.item_power` with `ctx.item` the item, `ctx.event` the trigger (`hit`, `struck` or `avoid`), `ctx.target` the
  other actor in the swing and `ctx.skill` for a skill proc, then the bearer's `item_power` chain. For a proc of an
  item's instance affect, `ctx.affect` is that affect in this call, and the proc runs at `affect.power × item_power /
  100`, rounded down. For a proc of an actor affect, the power is the one stored on that affect. Procs never roll
  `skill_check` or `skill_power`."
- §4.6 rules ctx paragraph (the `affect` text from Phase 2): replace "`None` everywhere else, including in the exports"
  with "in `item_power` for a proc of an item's instance affect, that affect; `None` everywhere else".

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Procs.kt
private class ProcSource(
  val trace: String, val display: String, val procs: List<Proc>, val stillThere: () -> Boolean,
  /** The item behind an item or item-affect proc, else null. */
  val item: Item? = null,
  /** The affect behind the proc: an actor affect, or an item's instance affect; null otherwise. */
  val affect: Affect? = null,
)
```

- **Algorithmic Steps & Invariants:**
  1. In `fireProcs`, actor-affect sources set `affect = a` and `item = null`. Held sources set `item = step.item` and
     `affect = step.affect`.
  2. In `resolveProcOutcome(bearer, other, src, outcome, trigger)` (add the `trigger` parameter), compute the power only
     after an outcome has been chosen and is about to resolve, after the `cast_allowed` check for a skill proc:
     - `src.item == null && src.affect != null` (actor affect) → `src.affect.power`;
     - `src.item != null` → `val ip = itemPowerChain(RulesContext(this, bearer, other, item = src.item, skill = skill,
       event = trigger.word, affect = src.affect))`, then `src.affect?.let { (it.power.toLong() * ip / 100).toInt() } ?:
       ip`;
     - otherwise 100.

     The chain's modifier steps still see their own affect (or `None`) as `ctx.affect`, as Phase 2 made them. Only the
     export itself sees the firing affect.

     Computing it only for resolving procs keeps proc RNG and trace order unchanged for procs that don't fire.

  3. Pass `power` to `applyEffect(..., skill, power)`, `applyEffect(effect, bearer, recipient, null, power)` and
     `skill.resolve?.resolve(this, bearer, each, null, power)`.

  4. `use`: where the `use_effect` is applied (`KernelCommands.kt:944-946`), compute `val power =
     w.itemPowerChain(RulesContext(w, actor, target, item = item, event = "use"))` once. Pass it to
     `applyItemEffect`/`applyEffect`. Skip the call when there is no `use_effect`.

  5. A proc that runs at power 100 from an item behaves exactly as today, because fixtures return 100.

### Test & Documentation Requirements

- Script tests (beside the M28 proc tests):
  - an item `damage` proc at an `item_power` of 200 doubles the fixed-dice damage;
  - an `item_power` modifier on a permanent affect reading `ctx.actor.skills` (the artificer) raises it;
  - `ctx.event` is `"hit"` for a hit proc and `"use"` for `use`;
  - an item instance affect with a `damage(dice="10d1")` hit proc, applied by an `item` skill cast at power 150, deals
    15 when `item_power` returns 100 and 30 when it returns 200 (the brand case). Its `item_power` sees
    `ctx.affect.name` equal to the brand's name. The same item's own `procs` see `ctx.affect == None`;
  - an actor-affect proc (an `apply_affect(procs=…)` cast at power 150) runs its effect at 150 without calling
    `item_power` (have the test's `item_power` fail loudly if called);
  - a skill proc's `resolve` sees `ctx.power`, and it never calls `skill_check` (make `skill_check` return 0 and show
    the proc still works);
  - a `use_effect` heal at `item_power` 150 heals 1.5×;
  - an `on_use`-only item doesn't call `item_power`.
- CHANGELOG: "Item `use_effect`s and item procs run at `item_power`; procs of an item's instance affect at the affect's
  power × `item_power` / 100 (with `ctx.affect` set in that `item_power` call); actor-affect procs at the affect's
  power."

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] The API files are unchanged (no new symbols). If they changed, explain why in the phase summary.
- [] Existing proc and `use` tests pass unedited.

---

## Phase 7: Robot world, tutorial, and finalization

### Objective & Scope

- **In Scope:**
  - robot-world tests of the real formulas;
  - the tutorial section;
  - §7.4;
  - an end-to-end scenario;
  - marking the `DESIGN_NEEDED` entry completed.
- **Out of Scope (do not implement these in this phase):** engine changes.

### Context Working Set

- **Read / Reference Only:**
  - `samples/robot-world/zones/foundry/` (skills and `foundry_test.star`);
  - `samples/robot-world/rules/combat.star`;
  - §4.3, §7.4.
- **Files to Modify / Create:**
  - `samples/robot-world/zones/foundry/foundry_test.star`;
  - `template-world/docs/tutorials/03_items_and_equipment.md`;
  - `docs/SPEC.md` (§7.4);
  - `docs/DESIGN_NEEDED.md`, `docs/CHANGELOG.md`;
  - scratch only: `local-world-test/`.
- **Do Not Touch:** Kotlin, rules formulas (unless a test shows a formula contradicts this document).

### Specification & Decision Record Changes

- §7.4, new row after M30 (`markdownFormat` pads it):

```markdown
| **M31** | Skill proficiency: `skill_check` (rolled once per cast at resolution; failure pays cost and lag, `cast.failed.*`, nothing else), cast power from `skill_power`, item power from `item_power` for `use_effect` and item procs, item instance affect procs at the affect's power × `item_power` / 100, actor-affect procs at the affect's stored power; power scales damage, heal, stun and affect/summon durations; `affect.power`, `ctx.affect` in modifiers, `ctx.power`, `ctx.summon`; three modifier points and their plugin counterparts; the template's and robot world's formulas (§3.10, §4.2, §4.3, §4.5, §4.6, §4.15, §4.18). |
```

### Implementation Blueprint

1. **Robot-world tests** in `foundry_test.star`, using existing skills (`:mind_lash` is an enemy damage spell;
   `:overload` is the other):
   - a caster at proficiency 0 (check 20): with a seed chosen so the first roll fails, they see "You fail to cast" and
     lose the cost. Find the seed by trying seeds and printing; then hard-code it with a comment saying the roll fails;
   - a caster at proficiency 100 and one at 40 hit the same target with `:mind_lash` (power 100 vs 70). Assert that the
     40 caster's damage is lower. Use the trace or the target's hp; if the spell's dice make one comparison flaky,
     compare sums over several casts with a fixed seed.
2. **Tutorial** `03_items_and_equipment.md`, a new section "Proficiency: success and power" after the spell-landing text
   (fences at column 0, not in lists):
   - `skill_check` and the failure rules;
   - `skill_power` and what power scales;
   - `ctx.affect.power` in a modifier (the levitation example);
   - `ctx.power` and `ctx.summon` in `resolve` (the druid example);
   - `item_power` with an artificer modifier example;
   - procs never roll `skill_check`.

   Keep each example short and consistent with the template formulas.

3. **End-to-end check:**

   - `cp -R template-world local-world-test/world`;
   - add a `skills` package with a small damage spell and a `self` buff, or give the scenario's mob a skill;
   - write `local-world-test/proficiency.star`: a novice (proficiency 10) and an expert (100) each cast several times at
     a training mob with `w.trace()` on, and print the transcript;
   - run `gups scenario --world local-world-test/world --package //zones/tutorial local-world-test/proficiency.star` as
     `AGENTS.md` describes;
   - read the trace: `skill_check` FAILs appear only for the novice, and `skill_power` is 55 vs 100;
   - delete `local-world-test/`.
4. **`docs/DESIGN_NEEDED.md`:** rename the heading to `### Skill proficiency (COMPLETED — M31)` and leave the body in
   place (do not delete it). Leave the notes already added to "Acquiring skills" and "Skill-based advancement" as they
   are.
5. CHANGELOG: "Tutorial 03 covers proficiency: `skill_check`, `skill_power`, `item_power`, `ctx.affect.power`,
   `ctx.power` and `ctx.summon`."

### Test & Documentation Requirements

- The robot-world tests above run in `./gradlew check` (through `:cli:test`).
- Run `./gradlew markdownFormat` and check the tutorial's code blocks kept their indentation.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings. Markdown is formatted.
- [] The `gups scenario` run passed and its trace showed novice failures and the two powers. `local-world-test/` is
  deleted.
- [] The `DESIGN_NEEDED` entry is marked `(COMPLETED — M31)`, not deleted, and the §7.4 row is present.
- [] A final `grep -n "not rolled against" docs/SPEC.md` finds nothing.
