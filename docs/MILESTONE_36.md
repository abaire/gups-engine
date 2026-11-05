# Milestone 36: Percent Chances and Perceptive Aggressive Mobs

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, heading `### Aggressive mobs and players' level or visibility`.
  The same file's new entry `### Target preference for aggressive mobs` holds what this milestone defers; leave it open.
- **Design Summary:** two linked changes.
  1. **One idiom for chances.** Every chance in a declaration becomes a whole-number percent from 0 to 100, as the rules
     exports (`to_hit`, `skill_check`, `flee_chance`) and `rng.chance(pct)` already are. Today `wander(chance)`,
     `loot(chance)`, `proc(chance)` and `outcome(weight)` are 0..1 floats. Sub-1% is out of scope; if it is ever needed,
     the base changes (say, per mille) for every chance at once.
  2. **`aggressive` notices its targets.** `aggressive(toward = "players", unless_level_above = None, unless_level_below
     = None, chance = 100, via = "sight")`:
     - `chance` is a percent rolled once per think, before anything else is looked at. A failed roll means the mob does
       not look this cycle and proposes nothing. It meters how often the mob stares; each look is a fresh perception
       check, so a well-hidden player can still be found eventually.
     - On a look, candidates are the simulated, living actors in the room that pass `toward` and the level checks, in
       the existing order (lowest id first). A candidate is **noticed** if `rules.can_perceive` returns true for the mob
       on any channel in `via`. The first noticed candidate is attacked. There is one `can_perceive` call per candidate
       per channel and no other source of extra rolls.
     - The call: `ctx.actor` and `ctx.target` are the mob, `ctx.source` is the candidate, `ctx.room` and
       `ctx.source_room` are the mob's room, `ctx.channel` is the channel, `ctx.event` is `"notice"`, `ctx.intensity` is
       1, `ctx.cost` is 0. The default `can_perceive` therefore passes whenever the mob's sense on that channel is above
       0, so a mob with `senses = {"sight": 0}` and the default `via` attacks nothing.
     - `via` is one channel name or a list of names, each a built-in or declared channel (§3.9), checked at load. It
       lets a world model a creature that can't be hidden from: give it a `scent` or `lifesense` channel, `via =
       "lifesense"`, and a hide effect that only touches `sight` and `sound` never matters to it.
     - `unless_level_below = N` spares actors whose level is below `N`. It belongs to the `leveling` feature, like
       `unless_level_above`.
     - Stealth is the world's business. The engine adds no hidden or invisible concept. Worlds should decide stealth
       when the player hides, store the result on an affect, and have `can_perceive` read it (deterministic), not roll
       in `can_perceive`; `chance` is the knob for how often a mob looks.
  - **Deferred (new `DESIGN_NEEDED` entry):** target preference (`attack_healers_first` and the like), and an attack
    delay after a target arrives.
- **Specification Impact:** §4.2 (procs, outcomes, loot, the example mob), §4.5 (the `goblin_scout` example), §4.6 and
  §5.7 (the `notice` event), §4.7 (the `wander` and `aggressive` rows and bullets), the testing section's `force_rng`
  paragraph, §7.4 (an M36 row). Exact edits are in each phase.
- **Decision Record Updates:** None. `docs/DECISIONS.md` holds no live records; do not add a new `D-xxx`.

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first and see them fail.
   - No spec section numbers or decision ids in comments, Kotlin or Starlark.
   - All Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. The engine is unreleased, so there are no migrations
   and no back-compat shims: old float forms are load errors, not accepted aliases.
3. Add CHANGELOG bullets to a new `### Percent chances and noticing` subsection at the top of `## api_version 1
   (unreleased)`, above whatever subsection is first there now. Create it in Phase 1 and have each later phase append.
   Phases 1 to 3 are breaking: give each a before/after snippet.
4. In any phase that changes the script API or template-world content, regenerate the API files before the phase's final
   `./gradlew check` if any golden or API test fails:
   - Run `./gradlew :cli:test -Dgups.updateGolden=true`. This rewrites `cli/src/test/resources/api.golden.json` and the
     `cli/src/test/resources/docs-golden/` files.
   - Run `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout. This rewrites `docs/api/api-1.json`.
   - Read the diff. Only the symbols that phase names may change. `ApiDiffTest` must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing entry.
5. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation.
6. Spec text in this milestone is approved by the maintainer through approval of this milestone; edit `docs/SPEC.md`
   exactly as each phase says and nowhere else.

---

## Phase 1: Percent integers, step 1: `wander`, and the shared helpers

### Objective & Scope

- **In Scope:**
  - A `percent` argument parser in the script layer and an `Rng.passes` helper in core.
  - `wander(chance)` as a percent integer.
  - The CHANGELOG subsection.
- **Out of Scope:** *(the agent must not attempt any of these here)* `loot`, `proc`, `outcome`, `Rng.fraction`,
  `force_rng`, any `aggressive` change.

### Context Working Set

- **Read / Reference Only:** `core/src/main/kotlin/gups/core/Rng.kt`; `script/src/main/kotlin/gups/script/Values.kt`
  (`int`, `number` helpers); `script/src/main/kotlin/gups/script/Declarations.kt` (the `wander` method and its Starlark
  `Param`, around the "Behaviors" banner); `docs/SPEC.md` §4.7 table and the §4.2 example mob.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/Rng.kt`
  - `core/src/main/kotlin/gups/core/world/Behaviors.kt` (`Wander` only)
  - `script/src/main/kotlin/gups/script/Values.kt`
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`wander` only)
  - `script/src/main/kotlin/gups/script/DocExamples.kt` (the `wander` example)
  - Tests: `core/src/test/kotlin/gups/core/RngTest.kt`, `core/src/test/kotlin/gups/core/world/BehaviorTest.kt`,
    `script/src/test/kotlin/gups/script/LinterTest.kt`, plus a new `script/src/test/kotlin/gups/script/PercentTest.kt`
  - `template-world/zones/tutorial/ai_probes_testdata.star`, `template-world/zones/tutorial/ai_test.star` (comment),
    `template-world/docs/tutorials/05_mobs_and_behaviors.md`, `template-world/docs/tutorials/README.md` if it shows a
    `wander` chance
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the regenerated API files
- **Do Not Touch:** `loot`, `proc`, `outcome`, `Combat.kt`, `Procs.kt`, `persist/`, `harness/`.

### Specification & Decision Record Changes

- `docs/SPEC.md` §4.7 table, the `wander` row: `wander(radius=3, chance=10)`, and "`chance` is the percent probability
  per think". Do the same in the §4.2 example mob (`wander(radius=4, chance=10)`).

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Rng.kt
  /** True with probability [pct] percent. 100 or more passes and 0 or less fails, each without a roll. */
  fun passes(pct: Int): Boolean = pct >= 100 || (pct > 0 && chance(pct))

  // Behaviors.kt
  data class Wander(val radius: Int, val chance: Int) : Behavior

  // Values.kt
  /** A whole percent from 0 to 100; a float is refused with a hint to write 25 for 0.25. */
  internal fun percent(v: Any?, what: String): Int
  ```
- **Algorithmic Steps & Invariants:**
  1. `passes` skips the draw for 0 and 100 so existing queues of forced ints and seeded streams aren't disturbed by
     certain outcomes. A pct in 1..99 draws exactly once through `chance`.
  2. `Wander.propose`: replace `world.rng.fraction() >= chance` with `!world.rng.passes(chance)`. Keep the engaged check
     first, as now.
  3. `percent`: accept a `StarlarkInt` in 0..100, else fail `"$what: must be a whole percent from 0 to 100"`; for a
     `StarlarkFloat` fail with `"$what: must be a whole percent from 0 to 100, not a fraction (write 25 for 0.25)"`.
     Mirror the style of `int` in `Values.kt`.
  4. `wander` declaration: default `"10"`, doc "Percent chance of taking a step on each think, 0 to 100.", parse with
     `percent`. Default radius unchanged.
  5. Replace every `wander(... chance = 1.0)` with `chance = 100` and `0.2`, `0.1` with `20`, `10` in the files above.
- **Edge cases to test:** `chance = 0` never moves and draws nothing; `100` always moves and draws nothing; `50` draws
  one int (use `w.rng.force(ints = ...)`); `0.5`, `-1`, `101`, `True` and `"5"` each fail the load with the messages
  above.

### Test & Documentation Requirements

- `RngTest`: `passes` at 0, 100, 150 and -5 draws nothing; at 30 it draws once (forced int 30 passes, 31 fails).
- `BehaviorTest`: `Wander(radius = 2, chance = 100)`; add a test that `chance = 0` never leaves the room.
- `PercentTest`: the rejection messages for `wander`.
- Update the tutorial prose at `05_mobs_and_behaviors.md` (the `wander(radius=4, chance=0.1)` snippet and its comment
  "10% chance to step per idle pulse" stays true).
- CHANGELOG, with a before/after: `wander(chance = 0.1)` → `wander(chance = 10)`.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `rg -n "chance *= *[01]\.[0-9]|chance *= *1\.0" template-world samples docs/SPEC.md` finds no `wander` chance left.
- [] A load with `wander(chance = 0.25)` fails with the percent hint.

---

## Phase 2: Percent integers, step 2: `loot`

### Objective & Scope

- **In Scope:** `loot(chance)` and `LootRule.chance` as percent integers, and the corpse loot roll.
- **Out of Scope:** *(do not attempt)* `proc`, `outcome`, `Rng.fraction` removal, `aggressive`.

### Context Working Set

- **Read / Reference Only:** `core/src/main/kotlin/gups/core/world/Combat.kt` around line 300 (the loot loop);
  `docs/SPEC.md` §4.2 "Loot" bullet.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`LootRule`)
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (the loot roll)
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`loot` method and `LOOT_SPEC`, two doc strings)
  - `script/src/main/kotlin/gups/script/DocExamples.kt`
  - Tests: `script/src/test/kotlin/gups/script/LoaderTest.kt` (the `loot(":key", chance = 0.5)` case), any core test
    that builds a `LootRule` (`rg -n "LootRule" core/src/test`), and `PercentTest`
  - `template-world/zones/tutorial/mobs.star` and tutorial 05 if they declare a loot chance
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, regenerated API files
- **Do Not Touch:** `Procs.kt`, `Effects.kt`, `persist/`.

### Specification & Decision Record Changes

- `docs/SPEC.md` §4.2: the example `loot(":rusty_key", chance=25)` and the Loot bullet: "a loot `chance` is a percent
  from 0 to 100".

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  data class LootRule(val label: Label, val chance: Int = 100, val count: Int = 1)
  ```

  The `LOOT_SPEC` `DeclParam("chance", "int", "Drop chance in percent, 0 to 100.", default = "100")`.

- **Algorithmic Steps & Invariants:**

  1. `Combat.kt`: `if (rng.passes(rule.chance))` in place of `rng.fraction() < rule.chance`.
  2. The `loot` declaration reads `percent(d["chance"] ...)`, with the default 100 when absent. Mirror how `d.int` is
     used for `count` in the same block; if the `d` helper has no `percent`, parse the raw value with the Phase 1
     `percent`.
  3. A `LootRule` with a chance outside 0..100 can't be built from script; add `init { require(chance in 0..100) }`.
- **Edge cases to test:** 100 always drops and draws nothing; 0 never drops; 50 draws once; `0.5` and `101` fail the
  load.

### Test & Documentation Requirements

- Core: a mob with `LootRule(label, chance = 50)` drops on forced int 50 and not on 51.
- Script: the rejection messages for `loot`.
- Tutorial 05 loot snippet to `chance=25`.
- CHANGELOG before/after: `loot(":x", chance = 0.25)` → `loot(":x", chance = 25)`.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `rg -n "loot\\(.*chance *= *0\\." . --glob '!docs/MILESTONE_*' --glob '!docs/CHANGELOG.md'` finds nothing.

---

## Phase 3: Percent integers, step 3: `proc`, `outcome`, and removing the fraction RNG

### Objective & Scope

- **In Scope:**
  - `proc(chance)` and `outcome(weight)` as percent integers, their rolls, the persistence codec, the template world's
    procs, and the tutorial tests that force fractions.
  - Removing `Rng.fraction`, `forcedFractions`, `force(fractions=)` and `w.force_rng(fractions=)`, which have no users
    after this phase.
- **Out of Scope:** *(do not attempt)* `aggressive`; a new probability type; converting `affect` `power` (it is already
  a percent integer).

### Context Working Set

- **Read / Reference Only:** `core/src/main/kotlin/gups/core/world/Procs.kt` (lines about 60 to 115, the roll and the
  outcome selection); `core/src/main/kotlin/gups/core/model/Effects.kt` (`ProcOutcome`, `Proc`);
  `script/src/main/kotlin/gups/script/Declarations.kt` (`outcome`, `proc`, `OUTCOME_SPEC`, `PROC_SPEC`);
  `persist/src/main/kotlin/gups/persist/EffectCodec.kt`; `harness/src/main/kotlin/gups/harness/Handles.kt` (`forceRng`);
  `template-world/zones/tutorial/tutorial_test.star` (`_proc_fight` and its callers near line 487).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Effects.kt`, `core/src/main/kotlin/gups/core/world/Procs.kt`,
    `core/src/main/kotlin/gups/core/Rng.kt`
  - `script/src/main/kotlin/gups/script/Declarations.kt`, `script/src/main/kotlin/gups/script/DocExamples.kt`
  - `persist/src/main/kotlin/gups/persist/EffectCodec.kt`
  - `harness/src/main/kotlin/gups/harness/Handles.kt`
  - Tests: `core/src/test/kotlin/gups/core/RngTest.kt`, `core/src/test/kotlin/gups/core/world/ProcsTest.kt`,
    `script/src/test/kotlin/gups/script/ProcScriptTest.kt`,
    `persist/src/test/kotlin/gups/persist/ProcPersistenceTest.kt`, `harness/src/test/kotlin/gups/harness/RunnerTest.kt`,
    `script/src/test/kotlin/gups/script/PercentTest.kt`
  - `template-world/zones/tutorial/items.star` (every `proc(... chance = ...)` and `outcome(<float>, ...)`),
    `template-world/zones/tutorial/tutorial_test.star`, `template-world/docs/tutorials/03_items_and_equipment.md`, and
    any sample world that declares a proc (`rg -n "proc\\(|outcome\\(" samples template-world`)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, regenerated API files
- **Do Not Touch:** `Behaviors.kt`, `Combat.kt`, `Declarations.kt` outside `outcome`/`proc`/their specs.

### Specification & Decision Record Changes

- `docs/SPEC.md` §4.2 Procs bullet: `proc(on, chance = 100, ...)`; "`chance` is a percent from 0 to 100"; outcomes: "the
  weights are percents whose total is at most 100, and the rest is 'nothing happens'"; the example snippets
  (`outcome(40, ...)` and the like).
- `docs/SPEC.md` testing section: `w.force_rng(ints=[])` queues the results of later rolls: each `rng.int` (and so
  `rng.chance`, and every engine chance such as a proc's, loot's or `wander`'s) takes the next queued int. Delete the
  sentence about fractions.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  data class ProcOutcome(val weight: Int, val skill: Label? = null, val effect: Effect? = null,
                         val target: ProcTarget? = null, val message: String? = null)
  // init: require(weight in 0..100)
  data class Proc(val on: ProcTrigger, val chance: Int, val outcomes: List<ProcOutcome>)
  // init: require(chance in 0..100); require(outcomes.sumOf { it.weight } <= 100)
  ```

  `ProcJson.chance: Int` and `OutcomeJson.weight: Int` in `EffectCodec.kt`. `Rng` loses `fraction`, `forcedFractions`
  and the `fractions` parameter of `force`. `forceRng` loses its `fractions` parameter and doc.

- **Algorithmic Steps & Invariants:**

  1. `Procs.kt`: replace `val r = rng.fraction(); if (r >= proc.chance)` with a roll that records what it rolled for the
     trace: `val rolled = proc.chance in 1..99`; `val r = if (rolled) rng.int(1, 100) else 0`; `val fired = if (rolled)
     r <= proc.chance else proc.chance >= 100`. A chance of 100 or more passes and 0 fails, each without a draw. Trace
     text: `roll $r` when rolled, `auto` when not (replace the `"%.2f"` formatting everywhere in the function, including
     the "nothing" and result lines).
  2. Outcome selection: keep the single-outcome shortcut as `outcomes.size == 1 && weight >= 100`. Otherwise `val p =
     rng.int(1, 100)` and walk the outcomes with a running `total += o.weight`; the first with `p <= total` wins; none
     means "nothing". This preserves "weights total at most 100, the rest is nothing".
  3. Declarations: `outcome` default weight 100, `proc` default chance 100; parse with `percent`; the total check is
     `total > 100` with the message `proc(outcomes): weights add up to more than 100`. The single-effect/skill proc
     builds `ProcOutcome(weight = 100, ...)`. Update the `DeclParam` types to `"int"` and their docs (lines about 2444,
     2566, and the proc chance param).
  4. `EffectCodec`: change the two field types and the mapping; no migration of saved data (pre-release).
  5. Update the Kotlin and Starlark tests: where they force `fractions = [0.0]`, force ints instead. A fraction `0.0`
     meant "always procs" and `0.6` meant "second roll lands in the 0.6 band"; translate to `ints = [1]` (first roll 1
     is at or below any nonzero chance) and, for a two-outcome table whose first weight is 40, `ints = [1, 60]`. Work
     out each case by reading its outcome table; do not guess.
  6. `ProcsTest` "outcome tables chance 0 makes only one roll": with `passes` semantics chance 0 makes no draw. Rename
     the test to match and assert no proc and an untouched forced-int queue.
- **Edge cases to test:** chance 100 and 0 draw nothing; chance 40 draws one int; an outcome table with weights `40, 30`
  picks the first for forced 40, the second for 41 to 70, and nothing for 71; weights `60, 50` fail the load; `0.25` and
  `1.0` as chance or weight fail the load with the percent hint (note `1.0` used to mean 100%, so the message must not
  accept it).

### Test & Documentation Requirements

- Update `RngTest` to delete the fraction tests and keep a test that ints and `passes` interact as designed.
- `RunnerTest`: delete `test_bad_fraction`; keep the int cases.
- Tutorial 03 proc snippets: `chance = 25`.
- CHANGELOG before/after for `proc(chance = 0.25)` → `proc(chance = 25)`, `outcome(0.4, ...)` → `outcome(40, ...)`, and
  "`w.force_rng(fractions=...)` is removed; force ints".

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `rg -n "fraction\\(|forcedFractions|fractions" core/src harness/src script/src template-world samples docs/SPEC.md`
  finds no engine fraction RNG left (a result in an unrelated name such as `overrunFraction` is fine).
- [] `rg -n "chance *= *0\\.|outcome\\(0\\." template-world samples` finds nothing.

---

## Phase 4: `aggressive`: `chance`, `via`, `unless_level_below`

### Objective & Scope

- **In Scope:**
  - The new `Aggressive` fields, behavior and declaration; the `notice` event; load validation of `via`; the `leveling`
    ownership of `unless_level_below`.
  - Unit and script tests, `DocExamples`, SPEC text.
- **Out of Scope:** *(do not attempt)* target ordering, attack delay, any hide/stealth mechanism in the engine, template
  world content (Phase 5), changes to `assist`, `follow`, `pursue`.

### Context Working Set

- **Read / Reference Only:** `core/src/main/kotlin/gups/core/world/Behaviors.kt` (`Aggressive`, `World.others`,
  `ActorFilter`); `core/src/main/kotlin/gups/core/world/Perception.kt` lines 150 to 180 (how `RulesContext` is built for
  an in-room `canPerceive`; mirror it); `core/src/main/kotlin/gups/core/rules/RulesEngine.kt` (`canPerceive` default);
  `core/src/main/kotlin/gups/core/model/Schema.kt` (`hasChannel`, `allChannels`);
  `script/src/main/kotlin/gups/script/Declarations.kt` (`aggressive`, and how `senses(...)` at line 481 validates
  channel names against `schema`); `script/src/main/kotlin/gups/script/Ownership.kt` line 32.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Behaviors.kt`
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`aggressive`),
    `script/src/main/kotlin/gups/script/Ownership.kt`, `script/src/main/kotlin/gups/script/DocExamples.kt`
  - Tests: `core/src/test/kotlin/gups/core/world/BehaviorTest.kt`,
    `script/src/test/kotlin/gups/script/FeatureScriptTest.kt` and a new
    `script/src/test/kotlin/gups/script/AggressiveScriptTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, regenerated API files
- **Do Not Touch:** the template world, `Perception.kt` (read only), the other behaviors.

### Specification & Decision Record Changes

- `docs/SPEC.md` §4.7 table row: `aggressive(toward="players", unless_level_above=None, unless_level_below=None,
  chance=100, via="sight")`, priority 60: "Looks for a target on each think with probability `chance` percent. Attacks
  the lowest-id matching target it notices. `combat` feature; the level arguments also need `leveling`."
- §4.7 bullets (add after "Whom they mean"):
  - **Noticing:** "`aggressive` asks `rules.can_perceive` (§4.6) once per candidate and channel in `via`, as the mob,
    about event `notice` with intensity 1 and the candidate as `ctx.source`. A candidate is noticed if any channel
    passes. Stealth and invisibility are world rules: decide them when the actor hides, keep the result on an affect,
    and read it in `can_perceive`. Rolling inside `can_perceive` gives the mob a fresh roll on every look; `chance` sets
    how often it looks."
  - **`via`:** "one channel or a list, each built in or declared (§3.9). A mob that senses a channel nothing else hides
    on can't be hidden from."
- §5.7 event table: a row `notice` — channel the `via` channel — intensity 1 — "asked by `aggressive`; never propagated
  and never sent to `on_perceive`". §4.6 Perception exports: add `notice` to the events `can_perceive` can be asked
  about.
- §4.5 example mob `goblin_scout`: change `senses={"sound": 3, "sight": 0}` to `senses={"sound": 3}`, and its comment to
  "merged over sight/sound = 1".

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  data class Aggressive(
    val toward: ActorFilter,
    val unlessLevelAbove: Int? = null,
    val unlessLevelBelow: Int? = null,
    val chance: Int = 100,
    val via: List<Channel> = listOf(Channel.SIGHT),
  ) : Behavior
  ```

  Starlark: `aggressive(toward = "players", unless_level_above = None, unless_level_below = None, chance = 100, via =
  "sight")`; `via` takes a string or a list of strings.

- **Algorithmic Steps & Invariants:**

  1. `propose`: keep `if (Feature.COMBAT !in world.features || mob.isEngaged) return null`.
  2. Then `if (!world.rng.passes(chance)) return null` (Phase 1 helper: no draw at 100).
  3. `val target = world.others(mob).firstOrNull { toward.matches(it) && levelOk(it) && world.notices(mob, it, via) }`,
     where `levelOk` is `(unlessLevelAbove == null || it.level <= unlessLevelAbove) && (unlessLevelBelow == null ||
     it.level >= unlessLevelBelow)`. Check the level and `toward` tests before perception so `can_perceive` is not
     called for candidates that can't be targets.
  4. Add `private fun World.notices(mob: Mob, who: Actor, via: List<Channel>): Boolean = via.any { ch ->
     rules.canPerceive(RulesContext(this, actor = mob, target = mob, source = who, room = mob.room, sourceRoom =
     mob.room, channel = ch, event = "notice", intensity = 1, cost = 0)) }`. Mirror the argument names used at
     `Perception.kt:150-165`; use whichever accessor `Perception.kt` uses for `rules`.
  5. The proposal is unchanged: `kill <keyword>`, priority 60, `KillAction(target)`.
  6. Declaration: `need("aggressive.unless_level_below", "aggressive(unless_level_below)")` when it isn't None; add
     `"aggressive.unless_level_below"` to the `LEVELING` `owns(...)` line in `Ownership.kt`. Parse `chance` with
     `percent` (default 100). Parse `via` with: a string becomes a one-item list; a list of strings is used as is; empty
     fails (`aggressive(via): needs at least one channel`); duplicates fail; each name must satisfy `schema.hasChannel`,
     else fail with the same wording the `senses(...)` validator uses for an unknown channel. Include the declared names
     in the message if the validator does.
  7. Behaviors are data values with value equality; `via` is a `List<Channel>`, so the version-bump lint compares it
     correctly. Add a `LinterTest` case that a change of `chance` or `via` is seen as a content change.
- **Edge cases to test (Kotlin, in `BehaviorTest`, using a `FixedRules` subclass that overrides `canPerceive`):**
  - Default rules: a mob with sight 1 attacks as before; a mob with sight 0 and default `via` does not; the same mob
    with `via = [SOUND]` and sound 1 does.
  - A rules double that returns false for `source` with a flag and true otherwise: the mob skips the flagged player and
    attacks a second player in the room (lowest-id noticed, not lowest-id overall).
  - `chance = 0` never attacks and draws nothing; `chance = 50` with forced int 50 looks and with 51 doesn't (the mob
    stays idle for that think and attacks on a later one).
  - `unless_level_below = 5` spares a level 4 player and attacks a level 5 player. Both bounds together form a band.
  - `canPerceive` is not called for a candidate `toward` or the levels exclude (count calls in the double).
  - With `via = [A, B]` the call order is A then B, and stops at the first pass.
- **Script tests:** `via = "nope"` fails with the unknown-channel message; `via = []` and `via = ["sight", "sight"]`
  fail; `chance = 0.5` fails with the percent hint; `unless_level_below` without `leveling` fails with the same refusal
  shape as `unless_level_above`; a declared channel loads. `FeatureScriptTest` already covers `unless_level_above`
  without `leveling`; extend it for `unless_level_below`.

### Test & Documentation Requirements

- Update `DocExamples.kt` so the `aggressive` example shows `chance` and `via` (`aggressive(toward = "players",
  unless_level_above = 5, chance = 50, via = ["sight", "sound"])`).
- CHANGELOG: new arguments, the `notice` event, "`aggressive` now asks `can_perceive` as the mob, so a mob with `senses
  = {"sight": 0}` no longer attacks unless `via` names a channel it senses".

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `./gradlew :script:test --tests '*AggressiveScriptTest*' :core:test --tests '*BehaviorTest*'` passes.
- [] `rg -n "goblin_scout" -A8 docs/SPEC.md` shows the scout's `senses` without `sight: 0`.

---

## Phase 5: Template world example, tutorial, and tests

### Objective & Scope

- **In Scope:** a deterministic `hidden` check for the `notice` event in the template's `can_perceive`, a tutorial
  section, and a test zone that exercises hidden players, a blind mob, a listening mob and the `chance` gate.
- **Out of Scope:** *(do not attempt)* a player-facing `hide` skill or command, any change to engine code, any change to
  the channels the template declares.

### Context Working Set

- **Read / Reference Only:** `template-world/rules/perception.star` (`can_perceive`);
  `template-world/zones/tutorial/trainer_testdata.star` (the `commands = {...}` room pattern and `ctx.apply_affect(...,
  flags = [...])`) and `trainer_test.star` (the test pattern); `template-world/zones/tutorial/ai_test.star` (the
  `_world(t)` helper); `template-world/docs/tutorials/05_mobs_and_behaviors.md`; actor view `has_flag(flag)`
  (`docs/SPEC.md` §4.4 actor views).
- **Files to Modify / Create:**
  - `template-world/rules/perception.star`
  - Create `template-world/zones/tutorial/notice_testdata.star` and `template-world/zones/tutorial/notice_test.star`
  - `template-world/docs/tutorials/05_mobs_and_behaviors.md`
  - `docs/CHANGELOG.md`; regenerated API/golden files only if a test demands it
- **Do Not Touch:** engine Kotlin, `samples/`, the schema.

### Specification & Decision Record Changes

- None beyond Phase 4's. The CHANGELOG bullet: "The template world's `can_perceive` hides actors that carry the `hidden`
  flag from a `notice` on `sight`."

### Implementation Blueprint

- **Algorithmic Steps & Invariants:**
  1. In `can_perceive`, before the sense check: `if ctx.event == "notice" and ctx.channel == "sight" and ctx.source and
     ctx.source.has_flag("hidden"): return False`. Add a comment: stealth is decided when the actor hides and stored as
     a flag on an affect; this function only reads it, and does not roll.
  2. `notice_testdata.star`: a room `notice_test_room` (flag `dead_end_ok`, like the trainer rooms) with the commands
     `hide` (applies `ctx.apply_affect(ctx.actor, "hidden", 200, flags = ["hidden"])` and tells the actor) and `reveal`
     (removes it with `ctx.remove_affect(ctx.actor, "hidden")`; check the exact verb name in §4.4). Probe mobs, all with
     a tiny vital pool and no spawn rules (tests place them with `w.spawn`):
     - `test_watcher`: `aggressive(toward = "players")`.
     - `test_blind`: `aggressive(toward = "players")` with `senses = {"sight": 0}`.
     - `test_listener`: `aggressive(toward = "players", via = "sound")`.
     - `test_dozer`: `aggressive(toward = "players", chance = 0)`.
  3. `notice_test.star`, each test using `t.world(seed = 1, packages = ["//zones/tutorial"])`:
     - A hidden player is ignored by `test_watcher` over several pulses (`w.run(pulses = 20)`), and after `reveal` the
       mob attacks (the player's `fighting` is non-empty, using the view the other tests use).
     - `test_blind` never attacks a visible player.
     - `test_listener` attacks a hidden player.
     - `test_dozer` never attacks. Order the steps so the player hides before the mob is spawned (`w.spawn(...)` after
       the `hide` command), since a mob already in the room could attack during the command.
  4. Tutorial 05: update the behaviors list to show the new `aggressive` arguments, and add a short section "Noticing
     and hiding": the `notice` event, one `can_perceive` snippet reading a flag, the advice to decide stealth at hide
     time (not to roll in `can_perceive`), `chance` as the stare rate, and `via` with a note that a world may declare a
     channel such as `scent` (§3.9) for creatures that can't be hidden from. Say plainly that the template ships no
     `hide` command.
- **Edge cases:** the mob and player must be in the same room for the check; `has_flag` on a view whose actor has no
  such flag returns false.

### Test & Documentation Requirements

- The tests above are the requirement. Run them with `gups scenario` once by hand if a test is hard to reason about (see
  `AGENTS.md`), then delete anything under `local-world-test/`.
- Run `./gradlew markdownFormat` after the tutorial edit and check that code blocks kept their indentation.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including the template-world test run.
- [] `./gradlew :cli:test` passes (docs and golden up to date).
- [] The new tests fail if the `hidden` branch is removed from `perception.star` (try it once, then restore it).

---

## Phase 6: Finalization & Backlog Update

- In `docs/DESIGN_NEEDED.md`, mark the `### Aggressive mobs and players' level or visibility` entry as completed (do not
  delete it): add a line "Resolved by Milestone 36." directly under its heading, in the same style any other resolved
  entry in that file uses. Leave `### Target preference for aggressive mobs` open.
- In `docs/SPEC.md` §7.4, add a row after M35's: **M36** — "Percent chances (`wander`, `loot`, `proc`, `outcome` are
  whole percents and the fraction RNG is gone) and perceptive `aggressive` mobs: `chance`, `via` and
  `unless_level_below`, the `notice` event through `rules.can_perceive`, the template's `hidden` example (§4.2, §4.5,
  §4.6, §4.7, §5.7)." Match the length and style of the neighbouring rows.
- Run `./gradlew markdownFormat`, then the full `./gradlew check` and confirm no golden or API drift remains (`./gradlew
  :cli:test`).
- Do not add a `D-xxx` record and do not delete any `DESIGN_NEEDED` entry.
