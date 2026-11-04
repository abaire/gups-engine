# Milestone 26: Defender-Side Modifiers in Combat

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, section "Combat and skills", heading `### Defender-side modifiers
  in combat`.

- **Design Summary:**

  - **Three new modifier points that run on the defender's chain.** Today every `to_hit` and `damage` modifier runs on
    the attacker's chain. Those points keep that meaning unchanged. The defender gets its own points:

    |      Point       |              Value               |                                                          Where it runs                                                          | Feature  |
    |------------------|----------------------------------|---------------------------------------------------------------------------------------------------------------------------------|----------|
    | `defend_hit`     | int (percent)                    | auto-attack swings only: `to_hit` export → attacker's `to_hit` chain → defender's `defend_hit` chain → roll                     | `combat` |
    | `receive_damage` | int                              | swings and the `damage(...)` effect: raw → attacker's `damage` chain → defender's `receive_damage` chain → floor 0 → `mitigate` | kernel   |
    | `mitigate`       | `vitals(...)` (the vital losses) | after the `mitigate` export: its result → defender's `mitigate` chain → applied                                                 | kernel   |

    `receive_damage` changes how hard a blow lands, before armor and resists. A `mitigate` modifier changes what the
    blow costs: it can route a share of hp loss to another vital, or turn it into a gain.

  - **The defender's chain uses the full standard order.** That is zone `package(modifiers=)`, regions by name, room,
    the defender's traits, its affects, its worn items in `equipment_slots()` order, then `carried` item affects. Zone,
    region and room are those of the defender's room. Combat is same-room, so these are also the attacker's. Nothing is
    counted twice, because these points are distinct from `to_hit` and `damage`.

  - **The swing weapon filter applies only to the attacker.** Today, during a swing, a worn item with `dice` contributes
    only when it is `ctx.weapon`. On the defender's points that filter doesn't apply: every active worn item
    contributes, weapons included, so a parry dagger can carry a `defend_hit` modifier.

  - **The Starlark ctx is unchanged.** On every point, `ctx.attacker` (and `ctx.actor`) is the swinger or caster and
    `ctx.defender` (and `ctx.target`) is the actor hit. `weapon`, `slot`, `band`, `skill` and `damage_type` are the same
    as in the attacker's chain. Defender modifiers read their bearer as `ctx.defender`.

  - **Plugin trait modifiers see their bearer as `actor`.** On the three defender points, `PluginModifierCall.actor` is
    the defender (the trait's bearer), `other` the attacker, `weapon` the attacker's weapon, and `room` the bearer's
    room. On every other point it is unchanged.

  - **Negative losses are gains.** `World.damage` stops dropping negative entries. Each one raises that vital, up to its
    effective maximum, and never lowers it. Gains never set `lastAttacker`. `on_damage`'s `amount` and `losses`, and
    `interrupt_check`'s amount, count only positive losses. An event with no positive loss fires no `on_damage` and runs
    no interrupt check, but still applies its gains and asks `condition` once. The raw `ctx.damage` verb still refuses
    negative amounts.

  - **Trace.** A defender chain continues the same trace line after a `defender:<point>` step, and the step is left out
    when the defender has no modifier at that point. Existing traces are unchanged for defenders without modifiers.
    Example: `rules.to_hit base=62 → room:fog 52 → defender:defend_hit → affect:blur 42 → roll 44 HIT`.

  - **Not applied:**

    - the raw `ctx.damage` verb runs none of the chains;
    - spells have no to-hit roll, so `defend_hit` runs only on auto-attack swings.
  - **Out of scope:**
    - to-hit rolls for spells (`### Whether a spell lands`);
    - procs, thorns and retaliation (`### Combat procs`);
    - typed bonus damage (`### Typed bonus damage on attacks`);
    - the ownership of the `damage` modifier point. That landed separately: the point is now kernel-owned and the
      `damage(ctx)` export stays with `combat`. This milestone builds on that, which is why `receive_damage` and
      `mitigate` are kernel-owned like `damage`, and `defend_hit` is `combat`-owned like `to_hit`.
- **Specification Impact:**
  - §3.10 (feature table: kernel and `combat` rows);
  - §4.3 (`damage(dice, type)` primitive row);
  - §4.6 ("Signatures and results" `mitigate` bullet, "Modifier points (v0)", "Whose chain", a new "Attack and defense"
    paragraph, "Weapons in swings", the trace paragraph);
  - §4.4 (the `on_damage` paragraph);
  - §4.19 (the trait "Modifiers" bullet);
  - §5.4 ("Resolution" steps 2 and 3);
  - §7.4 (new M26 row);
  - `docs/CHANGELOG.md`, `docs/PLUGINS.md` and `template-world/docs/tutorials/03_items_and_equipment.md`;
  - the regenerated API files.
- **Decision Record Updates:** None. The attacker-only rule (formerly D-111) is already part of §4.6 "Whose chain". This
  milestone edits that spec text directly.

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first and see them fail.
   - No spec section numbers or decision ids in comments, Kotlin or Starlark.
   - All Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`. The engine is
   unreleased, so no migrations or back-compat shims are needed.
3. Add CHANGELOG bullets in a new `### Defender-side modifiers` subsection at the top of `## api_version 1
   (unreleased)`, above the current first subsection ("The `damage` modifier point belongs to the kernel"). Create the
   subsection in Phase 1, and have each later phase append its bullets to it.
4. In a phase that changes the script API (Phases 2 and 3), regenerate all three API files before the phase's final
   `./gradlew check`:
   - Run `./gradlew :cli:test -Dgups.updateGolden=true`. It rewrites `cli/src/test/resources/api.golden.json` and the
     `cli/src/test/resources/docs-golden/` files.
   - Run `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout. This rewrites `docs/api/api-1.json`.
   - Read the diff. Only the symbols named in that phase's exit conditions should be new or changed, and `ApiDiffTest`
     must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing modifier-point example there.
5. Gradle commands must run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that
   code blocks kept their indentation.
6. Never read or touch `local-world/`.

---

## Phase 1: Negative vital losses are gains

### Objective & Scope

- **In Scope:**
  - In `World.damage`, apply negative loss entries as gains.
  - Make the positive-only counting for `on_damage` and the interrupt check explicit.
  - Tests, spec text and a CHANGELOG bullet.
- **Out of Scope:** any modifier point; `ctx.damage` (it already refuses negative amounts); `heal`; `mitigate`'s call
  sites.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Vitals.kt`: `applyVitalDeltas` (line 27). It caps at the effective maximum and
    sets `lastAttacker` only for negative deltas from another actor.
  - `core/src/main/kotlin/gups/core/world/Combat.kt`: `refreshCondition` and `interruptCast` usage inside
    `World.damage`.
  - `core/src/test/kotlin/gups/core/world/Fixture.kt`: `Fixture`, `FixedRules` (it has an overridable `mitigate`),
    `RecordingHost`.
  - `script/src/main/kotlin/gups/script/Schema.kt`: `typed(...)` and `vitals(...)` (`build`) already accept negative
    values. The `mitigate` export's result (`vitalsOf` in `script/.../Rules.kt`) therefore passes negatives through
    unchanged. No script change is needed.
  - `script/src/test/kotlin/gups/script/RulesTest.kt`: the `world(...)` helper and `quietCombat` (line 756).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (`World.damage`, around line 189).
  - `core/src/test/kotlin/gups/core/world/DamageGainsTest.kt` (new).
  - `script/src/test/kotlin/gups/script/RulesTest.kt` (one new test).
  - `docs/SPEC.md` (§4.4 `on_damage` paragraph, §4.6 "Signatures and results", §5.4 "Resolution" step 3).
  - `docs/CHANGELOG.md`.
- **Do Not Touch:** `Vitals.kt`, `Ctx.kt` (the `ctx.damage` verb), `Schema.kt`, `Rules.kt` (core and script).

### Specification & Decision Record Changes

- In §4.6, in the "Signatures and results" bullet starting "`mitigate` is called for auto-attacks…", replace "Each loss
  is subtracted with no floor." with:

  > Each loss is subtracted with no floor. A negative loss is a gain: it raises that vital up to its maximum and never
  > lowers it. `on_damage`'s `amount` and `losses` and `interrupt_check`'s `amount` count only the positive losses; an
  > event with no positive loss fires no `on_damage` and runs no interrupt check, but still applies its gains.

- In §4.4, in the `**on_damage:**` paragraph, replace "`ctx.amount` is the total of the vital losses and `ctx.losses`
  the losses themselves" with "`ctx.amount` is the total of the positive vital losses and `ctx.losses` those losses (a
  gain, §4.6, is left out)". Then replace "It fires once per damage event, after the losses are applied." with "It fires
  once per damage event with a positive loss, after the losses and gains are applied."
- In §5.4 "Resolution" step 3, replace "Every loss is subtracted, `on_damage` fires once, the interrupt check runs, and
  then `condition` is asked once for the damage event" with "Every loss is subtracted and every gain added; if any loss
  is positive, `on_damage` fires once and the interrupt check runs; then `condition` is asked once for the damage
  event". Phase 3 later rewrites only the sentence before this one.

### Implementation Blueprint

- **Signatures & Types:** the signature of `World.damage(target: Actor, losses: Map<String, Int>, type: DamageType,
  source: Actor? = null)` is unchanged. Update its KDoc to describe gains.
- **Algorithmic Steps & Invariants:**
  1. If `!target.inWorld`, return.
  2. `dealt = losses.filterValues { it > 0 }` and `total = dealt.values.sum()`, as today.
  3. `gains = losses.filterValues { it < 0 }`. For each gain, compute the delta as `min(-loss, max(0, max(name) -
     current))`, so a gain never lowers a vital that is above its maximum. Drop zero deltas.
  4. If `dealt` and the gain deltas are both empty, return. This keeps today's no-op for all-zero results.
  5. Call `applyVitalDeltas` once with `dealt.mapValues { -it.value } + gainDeltas`. Gains are positive deltas, so they
     never set `lastAttacker`.
  6. Only if `total > 0`: fire `ON_DAMAGE` with `amount = total` and `losses = dealt`, then run the interrupt check
     exactly as today.
  7. Call `refreshCondition(target)` once, whichever branch ran.

### Test & Documentation Requirements

In `DamageGainsTest.kt`, write these tests with `Fixture` and `RecordingHost` handlers on `on_damage`:

- `mapOf("hp" to 3, "mana" to -2)` lowers hp by 3 and raises mana by 2. `on_damage` fires once with `amount == 3` and
  `losses == {hp: 3}`.
- A gain is capped at the effective maximum: mana at max − 1, gain 5 → max.
- An all-gain map (`mapOf("mana" to -2)`) raises mana, fires no `on_damage` and leaves `lastAttacker` unchanged.
- A casting target hit with only gains is not interrupted. Mirror an existing interrupt test in `Combat` or `Skills`
  tests for the setup.
- An all-zero map changes nothing and fires nothing (a regression check).

In `RulesTest.kt`, add a test where a Starlark `mitigate` export returns `vitals(hp = amount, mana = -1)`. After a hit,
the defender's mana rises by 1. Set the defender's mana below its maximum first.

Add a CHANGELOG bullet: "A negative entry in `mitigate`'s result is now a gain to that vital, capped at its maximum,
instead of being ignored; `on_damage` and `interrupt_check` count only the losses."

### Exit Conditions

- [] `./gradlew :core:test` passes, including `DamageGainsTest`.
- [] `./gradlew check` passes with zero warnings.
- [] The §4.6 text matches the behavior, and `markdownFormat` has been run.

---

## Phase 2: The `defend_hit` modifier point

### Objective & Scope

- **In Scope:**
  - The `DEFEND_HIT` point and the `defender` flag on `ModifierPoint`.
  - Defender-subject chain assembly in `chainSteps`.
  - `toHitChain` composing the attacker's and defender's chains.
  - The script registration (`MODIFIER_POINTS`, ownership under `combat`).
  - Spec, CHANGELOG and API files.
- **Out of Scope:** `receive_damage` and `mitigate` points (Phase 3); `PluginModifierPoint` and the plugin adapter
  (Phase 4); template-world content (Phase 5).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/test/kotlin/gups/core/world/ModifierChainTest.kt`: the `protos(...)` helper, `RecordingHost.modifiers` and
    `mod(name)`, and the chain-order test at line 62 as the pattern.
  - `script/src/test/kotlin/gups/script/RulesTest.kt`: the `world(...)` helper and the item-modifier tests around line
    949.
  - `script/src/test/kotlin/gups/script/FeatureScriptTest.kt`: `modifier points of a feature need it` (line 203).
  - `script/src/main/kotlin/gups/script/ApiModel.kt` (line 678): every `ModifierPoint` entry must have a
    `MODIFIER_POINTS` spec, and its `ctxFields` must exist on the rules ctx.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`ModifierPoint`).
  - `core/src/main/kotlin/gups/core/world/Rules.kt` (`chainSteps`, `chain`, `toHitChain`).
  - `script/src/main/kotlin/gups/script/Rules.kt` (`MODIFIER_POINTS`).
  - `script/src/main/kotlin/gups/script/Ownership.kt`.
  - `core/src/test/kotlin/gups/core/world/DefenderModifierTest.kt` (new).
  - Tests in `script/src/test/kotlin/gups/script/RulesTest.kt` and `FeatureScriptTest.kt`.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the three API files.
- **Do Not Touch:** `Combat.kt` (`swing` already calls `toHitChain` and traces `chance.text`, so it needs no change);
  `plugin-api/`; `template-world/`.

### Specification & Decision Record Changes

All in `docs/SPEC.md`:

- **§3.10, `combat` row:** replace "the `damage` export;" with "the `damage` export; the `defend_hit` modifier point;".
- **§4.6 "Modifier points (v0)":** add `defend_hit` after `damage` in the list.
- **§4.6 "Whose chain":** after the first sentence, insert:

  > The defender's for `defend_hit`: these points run the chain of the actor being hit, with zone, regions and room
  > those of that actor's room. The rules `ctx` is the same as on the attacker's chain: `ctx.attacker` and `ctx.actor`
  > are still the attacker, and `ctx.defender` is the actor whose modifiers run.

- **§4.6 new paragraph, right after "Chain order":**

  > **Attack and defense:** an auto-attack's hit chance is the `to_hit` export, then the attacker's `to_hit` chain, then
  > the defender's `defend_hit` chain, which takes the attacker's result as its base; the swing rolls against the final
  > value. Spells have no to-hit roll and run no `defend_hit` chain.

- **§4.6 "Weapons in swings":** append "The rule is for the swinging attacker only: in a defender's chain every active
  worn item contributes, weapons included."
- **§4.6 trace paragraph:** append:

  > A defender's chain continues the same line after a `defender:<point>` step, for example `rules.to_hit base=62 →
  > region:null_field 31 → defender:defend_hit → affect:blur 21 → roll 44 HIT`; the step is left out when the defender
  > has no modifier at that point.

- **§5.4 "Resolution" step 2:** "Roll `rng.int(1,100)` against `to_hit` (through the attacker's `to_hit` chain and then
  the defender's `defend_hit` chain, §4.6)."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Protos.kt
enum class ModifierPoint(
  val scriptName: String,
  /** True when the point runs on the defender's chain (the rules ctx's target) rather than the actor's. */
  val defender: Boolean = false,
) {
  TO_HIT("to_hit"),
  DAMAGE("damage"),
  /** Adjusts an auto-attack's hit chance on the defender's chain, after the attacker's to_hit chain. */
  DEFEND_HIT("defend_hit", defender = true),
  INITIATIVE("initiative"),
  // … the rest unchanged
}

// core Rules.kt
/** The attacker's to_hit chain, then the defender's defend_hit chain. */
fun World.toHitChain(c: RulesContext): ChainResult<Int>

/**
 * Runs the defender's chain at [point] (a defender point) on [base]. The text is empty when
 * the defender has no step at [point], otherwise " → defender:<point>" and each step.
 */
private fun <T> World.defenderSegment(
  point: ModifierPoint,
  c: RulesContext,
  base: T,
  show: (T) -> String,
): ChainResult<T>
```

- **Algorithmic Steps & Invariants:**
  1. **`chainSteps(point, c)`:**
     - The subject is `if (point.defender) c.target else c.requireActor()`. A defender point with no `c.target` returns
       an empty list.
     - Replace every use of `actor` (and `actor.room`) in the function body with the subject.
     - Leave the weapon-filter condition unchanged. It already applies only to `TO_HIT` and `DAMAGE`, so defender points
       never skip a worn weapon. Add a one-line comment saying a defender's worn weapons always contribute.
  2. **`chain(...)`** is unchanged. `defenderSegment` reuses the same step loop. Factor the loop into a private helper
     that takes a `StringBuilder` prefix, so both functions share it. Don't duplicate the loop.
  3. **`defenderSegment`:**
     - Get `steps = chainSteps(point, c)`.
     - If `steps` is empty, return `ChainResult(base, "")`.
     - Otherwise the text starts with `" → defender:${point.scriptName}"`. Then, for each step, apply it and append `" →
       ${step.source} ${show(v)}"`.
  4. **`toHitChain(c)`:** `atk = chain(TO_HIT, c, rules.toHit(c)) { "$it" }`, then `def = defenderSegment(DEFEND_HIT, c,
     atk.value) { "$it" }`, and return `ChainResult(def.value, atk.text + def.text)`.
  5. **`MODIFIER_POINTS`** (script `Rules.kt`): add a `DEFEND_HIT` spec. Use `signature = "fn(ctx, base)"`, `valueType =
     "int"`, and the same `ctxFields` as `TO_HIT`, which already include `"damage_type"` and `"slot"`. Doc: "Adjusts an
     auto-attack's hit chance on the defender's chain, after the attacker's to_hit chain. ctx.defender is the actor
     whose modifiers run; ctx.attacker is still the swinger."
  6. **`Ownership.kt`:** add `"modifier:defend_hit"` to the one-line `owns(Feature.COMBAT, "modifier:to_hit",
     "modifier:initiative", "modifier:flee_fail_lag")` call. `"modifier:damage"` is no longer in that list, since it is
     kernel-owned now. Leave it out.
  7. **Nothing else.** `baseValue` and `pointValue` already treat unlisted points as ints, and `Declarations.kt`
     (`POINTS`, the `MODIFIER` constant) and the persistence codec read `ModifierPoint.entries` by name, so none of them
     changes. Every `when (point)` over `ModifierPoint` in main sources (`script/.../Rules.kt` lines 294 and 313,
     `plugin-api/.../Adapters.kt` line 138) has an `else` branch, so the new entry compiles without touching them.

### Test & Documentation Requirements

- **`DefenderModifierTest.kt` (core):**
  - The defender's `defend_hit` steps run after the attacker's `to_hit` steps, in full-chain order. Give the room a
    `DEFEND_HIT` modifier, plus a defender affect and a defender worn item, and assert the `host.calls` order and the
    value.
  - A defender's worn weapon (an `ItemProto` with `dice`, in `wield`) contributes to `defend_hit` during a swing
    (`RulesContext` with `slot = "wield"` and a different `weapon`).
  - The trace text contains `→ defender:defend_hit →` when a step exists, and is identical to today's when none does.
  - An attacker's own `defend_hit` modifier is not consulted when it attacks, and a defender's `to_hit` modifier is not
    consulted when it is hit.
  - Through `startCombat` and `runTo`, a defender `defend_hit` modifier returning 0 makes every swing miss.
- **`RulesTest.kt` (script):** a Starlark item with `modifiers = {"defend_hit": _evade}`, worn by a mob, that reads
  `ctx.defender` and `ctx.attacker` and lowers the chance to 0. A player's swing then misses (follow the item-modifier
  pattern around line 949).
- **`FeatureScriptTest.kt`:** add a `refused(Feature.COMBAT, …"defend_hit"…)` case to `modifier points of a feature need
  it`.
- **CHANGELOG bullets:**
  - "Added the `defend_hit` modifier point (`MODIFIER.defend_hit`, `combat` feature): it runs on the defender's chain
    (zone, regions, room, then the defender's traits, affects and every active worn item, weapons included) after the
    attacker's `to_hit` chain, with the same `ctx`; `@trace rules` shows it after a `defender:defend_hit` step."

### Exit Conditions

- [] `./gradlew :core:test :script:test` passes.
- [] The API files are regenerated. The diff adds only `modifier:defend_hit` (with its ctx fields) and
  `MODIFIER.defend_hit`. `ApiDiffTest` passes.
- [] `./gradlew check` passes with zero warnings.

---

## Phase 3: The `receive_damage` and `mitigate` modifier points

### Objective & Scope

- **In Scope:**
  - The `RECEIVE_DAMAGE` and `MITIGATE` points.
  - One shared damage-resolution helper, used by both the swing and the `damage(...)` effect.
  - Vitals conversion for `mitigate` modifiers.
  - Spec, CHANGELOG and API files.
- **Out of Scope:** plugins (Phase 4); template-world content (Phase 5); the `damage` point's ownership.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Rules.kt` after Phase 2: `defenderSegment`, `damageChain`, and `Map<String,
    Int>.show()`.
  - `script/src/main/kotlin/gups/script/Rules.kt`: `baseValue` / `pointValue` (lines 293–319), with the `MOVE_COST` /
    `REGEN` vitals branches as the pattern.
  - `DefenderModifierTest.kt` from Phase 2.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt`.
  - `core/src/main/kotlin/gups/core/world/Rules.kt`.
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (`swing`, lines 160–165; `Effect.Damage` in `applyEffect`, lines
    576–582).
  - `script/src/main/kotlin/gups/script/Rules.kt`.
  - `DefenderModifierTest.kt` and `RulesTest.kt`.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the API files.
- **Do Not Touch:** `Ownership.kt` (both points belong to the kernel, and an unowned symbol is kernel); `World.damage`
  (Phase 1 is final); `plugin-api/`.

### Specification & Decision Record Changes

- **§3.10, kernel row:** replace "the `damage` modifier point;" with "the `damage`, `receive_damage` and `mitigate`
  modifier points;".
- **§4.3, `damage(dice, type)` row:** replace "The rolled amount goes through the caster's `damage` modifier chain
  (`ctx.skill` and `ctx.damage_type` set) and then `mitigate`. A negative chain result counts as 0." with:

  > The rolled amount goes through the caster's `damage` modifier chain (`ctx.skill` and `ctx.damage_type` set) and the
  > target's `receive_damage` chain; a negative result counts as 0. `mitigate` and then the target's `mitigate` chain
  > turn it into vital losses (§4.6).

- **§4.6 "Modifier points (v0)":**
  - Add `receive_damage` and `mitigate` after `defend_hit`.
  - Replace "`mitigate` and `get_lag` are not modifier points." with "`get_lag` is not a modifier point. The `mitigate`
    modifier point adjusts the `mitigate` export's result; it is not a second export."
  - Change "Modifiers of `move_cost` and `regen` take and return `vitals(...)` values" to "Modifiers of `move_cost`,
    `regen` and `mitigate` take and return `vitals(...)` values".
  - Replace the kernel-exception sentences ("The `damage` point is the exception: … `ctx.band` are `None` in it.") with:

    > The `damage`, `receive_damage` and `mitigate` points are the exception: they belong to the kernel, because the
    > `damage(...)` effect primitive runs their chains with any features, while the `damage` export belongs to `combat`.
    > Without `combat`, these chains run only for the effect primitive, so `ctx.weapon`, `ctx.slot` and `ctx.band` are
    > `None` in them.

- **§4.6 "Whose chain":** change "The defender's for `defend_hit`:" (added in Phase 2) to "The defender's for
  `defend_hit`, `receive_damage` and `mitigate` (the target of a `damage(...)` effect is the defender; the caster is the
  attacker):".
- **§4.6 "Attack and defense":** append:

  > The damage of a hit or of a `damage(...)` effect is the raw amount (the `damage` export, or the effect's dice), then
  > the attacker's `damage` chain, then the defender's `receive_damage` chain; a negative result counts as 0. `mitigate`
  > turns that amount into vital losses, the defender's `mitigate` chain adjusts them, and the result is applied. A
  > `receive_damage` modifier changes the blow before armor and resists; a `mitigate` modifier changes what it costs,
  > for example moving part of an `hp` loss to `mana` or turning it into a gain. The raw `ctx.damage` verb runs none of
  > these chains.

- **§4.6 trace paragraph:** after the Phase 2 example, add "and `rules.damage base=6 → defender:receive_damage →
  item:ward 1 → mitigate hp 1 slash → defender:mitigate → item:siphon {hp 1, mana -1}`".
- **§5.4 "Resolution" step 3:** replace "On a hit, compute `damage` (chain), then `mitigate` with the weapon's damage
  type (or the world's `unarmed_damage_type`), then subtract the vital losses it returns." with "On a hit, compute
  `damage` (the attacker's chain, then the defender's `receive_damage` chain), then `mitigate` with the weapon's damage
  type (or the world's `unarmed_damage_type`) and the defender's `mitigate` chain, then apply the vital losses (§4.6)."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Protos.kt, after DEFEND_HIT
/** Adjusts the raw damage of a hit or damage effect on the defender's chain, before mitigate. */
RECEIVE_DAMAGE("receive_damage", defender = true),
/** Adjusts the vital losses mitigate returned, on the defender's chain. */
MITIGATE("mitigate", defender = true),

// core Rules.kt
/** A hit's vital losses and its whole trace line. */
class DamageResult(val losses: Map<String, Int>, val text: String)

/**
 * The attacker's damage chain on [base], the defender's receive_damage chain, mitigate with
 * [type], then the defender's mitigate chain.
 */
fun World.resolveDamage(c: RulesContext, base: Int, type: DamageType): DamageResult
```

- **Algorithmic Steps & Invariants:**
  1. **`resolveDamage`:**
     - `atk = damageChain(c, base)`.
     - `recv = defenderSegment(RECEIVE_DAMAGE, c, atk.value) { "$it" }`.
     - `amount = recv.value.coerceAtLeast(0)`.
     - `losses = rules.mitigate(c, amount, type)`.
     - `mit = defenderSegment(MITIGATE, c, losses) { it.show() }`.
     - `text = atk.text + recv.text + " → mitigate ${losses.entries.joinToString { "${it.key} ${it.value}" }} $type" +
       mit.text`. Keep exactly today's swing format for the mitigate step, so existing trace assertions still match.
     - Return `DamageResult(mit.value, text)`.
  2. **`swing`:** replace the `damageChain` / `rules.mitigate` / `traceRules` lines with `val hit = resolveDamage(ctx,
     rules.damage(ctx), type)` and `traceRules(a, hit.text)`. Then pass `hit.losses` to `damage(target, …)`. Messages
     and their order are unchanged.
  3. **`Effect.Damage`:** replace the body with `val hit = resolveDamage(c, effect.dice.roll(rng), effect.type)`,
     `traceRules(caster, hit.text)` and `damage(t, hit.losses, effect.type, caster)`. The spell trace line now also
     shows the mitigate step. If a test asserts the old spell trace with an exact match or `endsWith`, update it.
  4. **Remove or keep `damageChain`:** if `damageChain` is no longer called outside `resolveDamage`, make it private.
  5. **`baseValue` / `pointValue`** (script `Rules.kt`): add `ModifierPoint.MITIGATE` to the `MOVE_COST, REGEN` branches
     of both. `RECEIVE_DAMAGE` falls through to the int branch. A vitals modifier result naming an undeclared vital
     already fails through `typed(...)`, which leaves the value as it was.
  6. **`MODIFIER_POINTS`:**
     - Add a `RECEIVE_DAMAGE` spec: `valueType = "int"`, the ctx fields of `DAMAGE`. Doc: "Adjusts the raw damage of a
       hit or damage(...) effect on the defender's chain, after the attacker's damage chain and before mitigate. A
       negative result counts as 0."
     - Add a `MITIGATE` spec: `valueType = "vitals"`, the same ctx fields. Doc: "Adjusts the vital losses mitigate
       returned, on the defender's chain. A negative loss is a gain to that vital."
  7. **Invariant:** a defender-point chain with no modifiers returns its base unchanged and adds no trace text. Worlds
     without defender modifiers behave and trace exactly as before, apart from the spell trace's new mitigate step.

### Test & Documentation Requirements

- **`DefenderModifierTest.kt`:**
  - **`receive_damage` on a swing:** a defender `RECEIVE_DAMAGE` modifier subtracting 3 from a 5-point hit (with
    `FixedRules(damage = 5)` and a pass-through `mitigate`) deals 2.
  - **Floor at 0:** a modifier returning −10 deals nothing and fires no `on_damage`.
  - **`receive_damage` on the effect:** it also applies to a `damage(...)` effect. Use `applyEffect` with
    `Effect.Damage`, mirroring an existing effect test.
  - **`mitigate` routing:** a defender `MITIGATE` modifier returning `{hp: base.hp - 1, mana: -1}` lowers hp by one less
    and raises mana by 1. This depends on Phase 1.
  - **Chain order:** the attacker's `damage` steps run before the defender's `receive_damage` steps, and the defender's
    `mitigate` steps run after `rules.mitigate`. Assert with `host.calls`, including a room-level `RECEIVE_DAMAGE`
    modifier.
  - **Trace:** the trace contains `→ defender:receive_damage →` and `→ defender:mitigate →` only when steps exist.
  - **Raw verb:** `World.damage` called directly (the raw verb's path) runs no chain.
- **`RulesTest.kt`:**
  - A Starlark worn item with `{"mitigate": _siphon}` that returns `vitals(hp = base.hp - 1, mana = base.mana - 1)`.
    After a player's hit, the defender's hp drops by one less and its mana rises by 1, capped at its maximum.
  - A `{"receive_damage": _ward}` modifier.
  - A modifier returning the wrong type leaves the value unchanged and records a `script_error`.
- **Kernel ownership:** in `FeatureScriptTest.kt`, mirror the two tests the `damage` ownership change added:
  - `the damage modifier point needs no feature` becomes `the receive_damage and mitigate modifier points need no
    feature`. Load each with `Features(emptySet())`.
  - `a damage modifier changes use_effect damage without combat` becomes a combat-off `use wand dummy` test.
    - Its room has `receive_damage`, which returns `base - 3`, and its dummy wears an item with `mitigate`, which
      returns `vitals(hp = base.hp, mana = -1)`. Give the test schema a `mana` vital, or route to another vital
      `TestWorlds` declares.
    - Assert the dummy's hp and the routed vital, and that `ctx.weapon`, `ctx.slot` and `ctx.band` are `None` in both
      modifiers.
- **CHANGELOG bullet:** "Added the kernel modifier points `receive_damage` (int, the defender's chain after the
  attacker's `damage` chain and before `mitigate`) and `mitigate` (`vitals(...)`, the defender's chain after the
  `mitigate` export), for auto-attacks and the `damage(...)` effect; the `damage(...)` effect's trace now ends with the
  `mitigate` step."

### Exit Conditions

- [] `./gradlew :core:test :script:test` passes.
- [] The API files are regenerated. The diff adds only `modifier:receive_damage`, `modifier:mitigate` and their
  `MODIFIER` constants. `ApiDiffTest` passes.
- [] `./gradlew check` passes with zero warnings.

---

## Phase 4: Plugin trait modifiers on defender points

### Objective & Scope

- **In Scope:**
  - `PluginModifierPoint` entries for the three points.
  - The plugin registration feature gate: `DEFEND_HIT` needs `combat`, and `RECEIVE_DAMAGE` and `MITIGATE` need no
    feature, like `DAMAGE`.
  - The adapter maps `actor` to the bearer on defender points.
  - Value conversion for the three points.
  - Tests, the §4.19 text, `docs/PLUGINS.md` and CHANGELOG.
- **Out of Scope:** any new `PluginModifierCall` field; the sample plugins' content.

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/src/test/kotlin/gups/pluginapi/TraitModifierTest.kt`, the pattern: `traitAdapter("heritage", …)` with a
    `TO_HIT` and a `REGEN` modifier.
  - `core/src/main/kotlin/gups/core/model/Creation.kt` (line 87, `TraitModifier`).
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt` (`PluginModifierPoint`, and the KDoc of
    `PluginModifierCall`).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt` (`traitAdapter`, around line 111).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt` (`MODIFIER_POINT_FEATURES`, around line 171).
  - `plugin-api/src/test/kotlin/gups/pluginapi/TraitModifierTest.kt`.
  - `plugin-api/src/test/kotlin/gups/pluginapi/CreationRegistrationTest.kt` (the feature-gate tests around line 138).
  - `docs/SPEC.md`, `docs/PLUGINS.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** core and script modules; `samples/`.

### Specification & Decision Record Changes

- **§4.19, "Modifiers" bullet under "Effects of traits":** replace its first sentence with:

  > At the `to_hit`, `defend_hit` and `initiative` points (`combat` feature) and `damage`, `receive_damage`, `mitigate`,
  > `move_cost` and `regen`. On `defend_hit`, `receive_damage` and `mitigate` the trait's bearer is the defender: the
  > plugin call's `actor` is the defender and `other` the attacker.

  The current sentence is "At the `to_hit` and `initiative` points (`combat` feature) and `damage`, `move_cost` and
  `regen`." Leave the rest of the bullet unchanged.

- **`docs/PLUGINS.md`, `#### Traits`:** add one sentence and a short example: a `PluginModifierPoint.DEFEND_HIT`
  modifier reading `call.actor` as the bearer.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
enum class PluginModifierPoint {
  TO_HIT,
  DAMAGE,
  DEFEND_HIT,
  RECEIVE_DAMAGE,
  MITIGATE,
  INITIATIVE,
  MOVE_COST,
  REGEN,
  ADVANCE_LAG,
  RETREAT_LAG,
  RETREAT_FAIL_LAG,
  FLEE_FAIL_LAG,
}
```

The names must equal the core `ModifierPoint` names, because `traitAdapter` maps them with `ModifierPoint.valueOf`. Add
KDoc on `PluginModifierCall.actor` and `other`: "The trait's bearer and its opponent. On `DEFEND_HIT`, `RECEIVE_DAMAGE`
and `MITIGATE` the bearer is the defender and `other` the attacker."

- **Algorithmic Steps & Invariants:**
  1. In `traitAdapter`:
     - Compute `bearer = if (point.defender) c.target else c.requireActor()` and `opponent = if (point.defender)
       c.requireActor() else c.target`.
     - If `bearer` is null, return `base`.
     - Build `PluginModifierCall(actor = ActorFacade(bearer, w), other = opponent?.let { ActorFacade(it, w) }, room =
       RoomFacade(bearer.room, w), …)`.
  2. In the value `when`, add `DEFEND_HIT` and `RECEIVE_DAMAGE` to the int branch (with `TO_HIT`, `DAMAGE`, …) and
     `MITIGATE` to the `MOVE_COST, REGEN` vitals branch.
  3. In `PluginHost.kt`'s `MODIFIER_POINT_FEATURES`, add `PluginModifierPoint.DEFEND_HIT to Feature.COMBAT`. Don't list
     `RECEIVE_DAMAGE` or `MITIGATE`: an unlisted point needs no feature, as `DAMAGE` does now.
  4. **Invariant:** non-defender points keep exactly today's `actor`, `other` and `room`.

### Test & Documentation Requirements

- **`TraitModifierTest.kt`:**
  - A trait with a `DEFEND_HIT` modifier sees `call.actor` as the defender and `call.other` as the attacker. Assert on
    names, and check that the returned int changes the chain value.
  - A `MITIGATE` modifier receives and returns a vital map, and an invalid map is refused with a `plugin` diagnostic.
  - A `TO_HIT` modifier still sees the attacker as `actor` (a regression check).
- **`CreationRegistrationTest.kt`:**
  - Add `PluginModifierPoint.DEFEND_HIT` to the list of points refused without `combat`.
  - Add `RECEIVE_DAMAGE` and `MITIGATE` to the `brisk` trait that registers with `noCombat`.
  - Update its message to "regen, damage, receive_damage and mitigate need no feature".
- **CHANGELOG bullet:** "Plugin traits can declare `DEFEND_HIT`, `RECEIVE_DAMAGE` and `MITIGATE` modifiers; on these the
  `PluginModifierCall`'s `actor` is the trait's bearer, the defender."

### Exit Conditions

- [] `./gradlew :plugin-api:test` and the `samples/` plugin tests pass.
- [] `./gradlew check` passes with zero warnings. The script API files are unchanged; if the plugin docs golden changes,
  regenerate it and read the diff.

---

## Phase 5: Template-world examples, tutorial, and finalization

### Objective & Scope

- **In Scope:**
  - Two tutorial items showing the new points, with zone tests.
  - The tutorial doc.
  - The region comment that lists points.
  - The §7.4 row.
  - Marking the `DESIGN_NEEDED` entry completed.
  - A full-suite run and an end-to-end check.
- **Out of Scope:** any engine change. If a test here exposes an engine bug, fix it in the module that has it, with a
  test, and say so in the summary.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/zones/tutorial/items.star`: `tar_walker_boots` and `_sure_footed` as the modifier-item pattern, and
    `copper_ring` / `amulet`.
  - `template-world/zones/tutorial/tutorial_test.star`: `_world(t)`, `test_ring_fire_resist` and the `_footwork` helper
    patterns.
  - `template-world/rules/combat.star`: `to_hit`, `damage`, `mitigate`. Players start with `hp 20` and `mana 20`.
  - `AGENTS.md` "End-to-end checks" (`gups scenario`).
- **Files to Modify / Create:**
  - `template-world/zones/tutorial/items.star`.
  - `template-world/zones/tutorial/tutorial_test.star`.
  - `template-world/zones/tutorial/rooms.star`: only the region comment "Points: to_hit, damage, …". Optionally, also
    place the new items where the armory or shop stock is defined, if that's an obvious spot.
  - `template-world/docs/tutorials/03_items_and_equipment.md`.
  - `docs/SPEC.md` (§7.4), `docs/DESIGN_NEEDED.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** core, script and plugin-api sources; `rules/combat.star` formulas.

### Specification & Decision Record Changes

- **§7.4:** add a row after M25:

  > | **M26** | Defender-side modifiers: the `defend_hit` (`combat`), `receive_damage` and `mitigate` (kernel) modifier
  > points on the defender's full chain, worn weapons included; negative `mitigate` losses are gains; plugin trait
  > modifiers see their bearer as `actor`; the tutorial's `shadow_cloak` and `siphon_amulet` (§3.10, §4.3, §4.6, §4.19,
  > §5.4). |

- **`docs/DESIGN_NEEDED.md`:** rename the heading to `### Defender-side modifiers in combat (COMPLETED — M26)`,
  following M24 and M25's entries, and leave the body in place. Don't touch the new "Ownership of the `damage` modifier
  point" entry.

### Implementation Blueprint

Two new tutorial items in `items.star`:

1. **`shadow_cloak`** (wear `"body"`; no tutorial item uses that slot today):
   - `defend_hit`: `_blur` returns `base - 15`. A comment says it runs on the wearer's chain when they are hit, and that
     `ctx.defender` is the wearer.
   - `receive_damage`: `_ward` returns `max(0, base - 1)`. A comment says this is before armor and resists.
2. **`siphon_amulet`** (wear `"neck"`; the tutorial's `amulet` also uses `neck`, so each test wears only one of them):
   - `mitigate`: `_siphon` moves `base.hp // 10`, at least 1 when `base.hp > 0`, out of `hp` and returns it as a `mana`
     gain: `vitals(hp = base.hp - moved, mana = base.mana - moved)`.
   - A comment explains that a negative loss is a gain, capped at the maximum.
3. **Style:** follow the file's style, with short comments, `_private` helpers and no lambdas or nested functions.
4. **`rooms.star`:** update the region comment's list of points to include the new ones.

### Test & Documentation Requirements

- **`tutorial_test.star`:**
  - **How the fights work:** `w.trace()` only prints its record when a test fails, so assert on vitals instead.
    `w.damage` is the raw verb and runs no chains. The damage source is the training dummy. Engagement is symmetric, so
    after `p.do("kill dummy")` the dummy makes unarmed swings back at the player each round. These run the full
    `to_hit`/`defend_hit` and `damage`/`receive_damage`/`mitigate` path.
  - **Helper:** add `_dummy_fight(t, item = None, pulses = 60, mana = 20)`, mirroring `_footwork`. It:
    - builds `_world(t)` with a fixed seed;
    - spawns a player at `:arena` with high hp so they survive, and the given current mana, using `spawn_player`'s
      `vitals` parameter (check its doc for whether it sets current or maximum values);
    - wears `item` if one is given;
    - runs `p.do("kill dummy")` and `w.run(pulses = pulses)`;
    - returns `(hp lost, p.mana)`.
  - `test_shadow_cloak_blurs_and_wards`: hp lost with the cloak is strictly less than without it, at the same seed. Both
    runs are deterministic. If the chosen seed gives no hits at all, pick another, and say in a comment that both runs
    use it.
  - `test_siphon_amulet_turns_pain_into_mana`: start mana at 0 and wear the amulet. After the fight, hp lost is greater
    than 0 and mana is greater than 0. Without the amulet, mana stays 0 (allow for `regen`: if the template's `regen`
    restores mana, compare against the amulet-less run's mana instead of 0).
- **`03_items_and_equipment.md`:** add a subsection under "B. Dynamic Rules Modifiers" on defender modifiers:
  - the three points and where they run;
  - that `ctx.defender` is the wearer;
  - that a defender's worn weapons count;
  - the cloak and amulet snippets.

  Keep fenced code blocks at column 0 (see the AGENTS.md list-indentation note).

- **CHANGELOG bullet:** "The tutorial zone has a `shadow_cloak` (`defend_hit` and `receive_damage`) and a
  `siphon_amulet` (`mitigate` moving a tenth of hp loss into a mana gain)."

- If `gups docs` output for the template world changes, regenerate the affected goldens and read the diff.

### Exit Conditions

- [] `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups test --world template-world
  //zones/tutorial/...` passes.
- [] An end-to-end `gups scenario` in the scratchpad runs: a player wears the cloak and the amulet, types `kill dummy`,
  and the fight runs for several rounds. The check is that the player's mana rose and no `script_error` diagnostics
  appeared (`w.diagnostics()`). If the scenario player may use `@trace rules`, also confirm that the trace output shows
  `defender:` steps.
- [] `./gradlew markdownFormat`, then `./gradlew check`, passes with zero warnings.
- [] The `DESIGN_NEEDED` heading is marked `(COMPLETED — M26)` and the entry body is still present.
- [] The §3.10, §4.3, §4.6, §4.19, §5.4 and §7.4 text matches the implemented behavior.
