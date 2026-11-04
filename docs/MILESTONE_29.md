# Milestone 29: Whether a Spell Lands

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, section "Combat and skills", heading `### Whether a spell lands`.

- **Design Summary:**

  - **Enemy spells roll to hit, through the same pipeline as a swing.** For every skill declared `target = "enemy"`, the
    engine rolls once per victim other than the caster. A `range = "room"` skill rolls separately for each victim. The
    chance is computed in this order:

    1. the `to_hit` export, with `ctx.skill` set;
    2. the caster's `to_hit` chain;
    3. the victim's `defend_hit` chain.

    The cast then rolls 1–100 against the result. No new export, modifier point or `ctx` field is added.

  - **Who rolls:** any caster, players through `cast` and mobs through `act_cast`, since both run `CastAction`. Skills
    declared `ally`, `self`, `item` or `none` never roll. This holds even when an `ally` skill applies a hostile
    primitive to someone else: the check depends on the skill's declared `target`, not on what its effect contains.
    `use_effect` items never roll either; they call `applyEffect` with no skill.

  - **The rules `ctx` for a spell's `to_hit` and `defend_hit`:**

    - `actor` and `attacker` are the caster;
    - `defender` and `target` are the victim being rolled for;
    - `skill` is the spell;
    - `band` is the caster–victim band (`None` without `track`);
    - `weapon`, `slot` and `damage_type` are `None`.

    A rule tells the two kinds of attack apart with `ctx.skill`, which is set for a spell and `None` for a swing. The
    way a spell is delivered (touch, ranged, area…) is the skill's free-form `kind` or its `tags`, which the rules
    interpret.

  - **A chance of 100 or more always lands and uses no RNG.** The trace shows `→ auto LAND` instead of a roll. Swings
    are unchanged: they always roll. This keeps seeded tests stable for rulesets that return 100 for spells.

  - **On a miss (a resist) for one victim:**

    - The cost is paid and the full `lag` applies, because the cast itself completed.
    - The "You cast … at …" and room "casts … at …" messages are still sent, before any roll.
    - Combat still starts with that victim (`startCombat` runs before the roll), the same as a missed swing.
    - That victim gets neither the skill's `effect` nor its custom `resolve`.
    - Three new overridable messages in group `skills` are sent: `cast.resisted.self`, `cast.resisted.target` and
      `cast.resisted.room`.
    - `on_cast` still fires once per cast. Its ctx fields are unchanged, and it gains no `landed` field.
  - **Trace:** the line has the same form as a swing's, ending in `LAND` or `RESIST`. For example: `rules.to_hit base=55
    → defender:defend_hit → item:ward 40 → roll 61 RESIST`.
  - **Ownership by several features.** Today `FeatureOwnership` maps each script API symbol to at most one `Feature`.
    This milestone adds a `FeatureRequirement` that is either *any of* or *all of* a set of features, with no nesting.
    The `to_hit` export and the `to_hit` and `defend_hit` modifier points become owned by **`combat` or `skills`**: the
    export is required, and the points allowed, when either feature is on. Plugin trait modifier points `TO_HIT` and
    `DEFEND_HIT` follow the same rule. All-of support is built and tested, but no symbol uses it yet.
  - **Plugin trait modifiers see the skill.** `PluginModifierCall` gains `val skill: PluginSkill? = null`. It is set
    whenever the rules ctx carries a skill: the spell's `to_hit` and `defend_hit`, and the spell `damage(...)` effect's
    `damage`, `receive_damage` and `mitigate` chains.
  - **Rulesets:**
    - Kotlin test doubles and the script test worlds return 100 for spells, so existing tests don't change.
    - The template world gets a real spell branch through a `_spell_hit(ctx)` helper. The template declares no skills,
      so nothing in it changes.
    - The robot world gets a real branch, and its foundry tests are re-checked.
    - The dry run calls `to_hit` with the synthetic skill whenever `skills` is on.
  - **Out of scope:** per-effect saves, partial results such as half damage on a save, skill proficiency mechanics (the
    "Skill proficiency" entry stays open), and a `landed` field on `on_cast`.
- **Specification Impact:**
  - §3.10 (the `combat` row; a new "Shared ownership" bullet);
  - §4.3 (a new "Landing" paragraph; the "Proficiency" sentence);
  - §4.6 (the `to_hit` table row; "Signatures and results"; the dry run paragraph; "Modifier points (v0)"; "Whose
    chain"; "Attack and defense"; the trace paragraph);
  - §4.15 (`PluginModifierCall`);
  - §4.18 if it lists catalog keys (the three `cast.resisted.*` messages);
  - §5.3 (step 5 "Resolve");
  - §5.4 ("Engagement");
  - §7.4 (a new M29 row);
  - `docs/CHANGELOG.md`, `docs/PLUGINS.md`, `template-world/docs/tutorials/03_items_and_equipment.md`;
  - the regenerated API files.
- **Decision Record Updates:** None. No existing D-xxx covers cast resolution or feature ownership. Do not add new
  `D-xxx` entries for anything this document settles: the spec is edited directly as approved here.

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first and see them fail.
   - No spec section numbers or decision ids in comments, Kotlin or Starlark.
   - All Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`. The engine is
   unreleased, so no migrations or back-compat shims are needed.
3. Add CHANGELOG bullets to a new `### Spells that miss` subsection at the top of `## api_version 1 (unreleased)`, above
   the current first subsection. Create it in the first phase that adds a bullet (Phase 1), and have each later phase
   append to it.
4. In a phase that changes the script API (Phases 2, 4 and 5), regenerate all three API files before the phase's final
   `./gradlew check`:
   - Run `./gradlew :cli:test -Dgups.updateGolden=true`. This rewrites `cli/src/test/resources/api.golden.json` and the
     `cli/src/test/resources/docs-golden/` files.
   - Run `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout. This rewrites `docs/api/api-1.json`.
   - Read the diff. Only the symbols named in that phase's exit conditions may be new or changed. `ApiDiffTest` must
     then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing entry.
5. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation.
6. Never read or touch `local-world/`. Put scratch files in `local-world-test/` and delete it when you are done.

---

## Phase 1: Feature requirements with any-of and all-of

### Objective & Scope

- **In Scope:**
  - A `FeatureRequirement` type in `core`.
  - `FeatureOwnership.of` returns it instead of a `Feature`.
  - Every consumer of `FeatureOwnership.of` and `refused` is updated: export required/forbidden checks, the API model's
    feature filtering and `feature` field, and the docs badge.
  - Refusal messages for multi-feature requirements.
  - Every symbol keeps its single owner. Behavior and API files are unchanged.
- **Out of Scope:** moving any symbol to a shared owner (Phase 2); plugin host modifier feature checks (Phase 2).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Features.kt`: `Feature` (with `.id`) and `Features` (`contains`, `ids`).
  - `script/src/test/kotlin/gups/script/FeatureScriptTest.kt`: the `refused(...)` helper at line 46.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Features.kt` (add `FeatureRequirement`).
  - `script/src/main/kotlin/gups/script/Ownership.kt`.
  - `script/src/main/kotlin/gups/script/Loader.kt`: `missingRulesExports` and `forbiddenRulesExports` (around line 770),
    plus the caller that formats `forbiddenRulesExports`' message (find it with grep).
  - `script/src/main/kotlin/gups/script/ApiModel.kt`: `forFeatures.on` (line 301) and `symbol(path)` (line 880).
  - `cli/src/main/kotlin/gups/cli/DocsRenderer.kt`: `featureBadge` (line 238).
  - Check `script/src/main/kotlin/gups/script/ApiDiff.kt` for reads of the `feature` field, and update it if it reads
    one.
  - Tests: new `core/src/test/kotlin/gups/core/world/FeatureRequirementTest.kt`; new
    `script/src/test/kotlin/gups/script/FeatureOwnershipTest.kt`.
  - `docs/CHANGELOG.md`.
- **Do Not Touch:** the ownership assignments themselves (every `owns(...)` call keeps its feature); `plugin-api/`;
  `core` world logic.

### Specification & Decision Record Changes

- None in this phase. The spec text for shared ownership is added in Phase 2, together with its first use.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// core/src/main/kotlin/gups/core/world/Features.kt

/**
 * The features an API entry needs: any one of [features] when [all] is false, every one of them
 * when it is true. A single-feature requirement is either.
 */
data class FeatureRequirement(val features: Set<Feature>, val all: Boolean = false) {
  init {
    require(features.isNotEmpty()) { "a feature requirement names at least one feature" }
  }

  fun satisfiedBy(enabled: Features): Boolean =
    if (all) features.all { it in enabled } else features.any { it in enabled }

  /** Feature ids in `Feature` declaration order. */
  val ids: List<String>

  /** "combat", "combat or skills", "combat and skills" (declaration order). */
  val text: String

  companion object {
    fun of(feature: Feature): FeatureRequirement
    fun anyOf(vararg features: Feature): FeatureRequirement
    fun allOf(vararg features: Feature): FeatureRequirement
  }
}
```

```kotlin
// script/src/main/kotlin/gups/script/Ownership.kt
object FeatureOwnership {
  /** The requirement of the entry named by [symbol] in `api.json`, or null for the kernel. */
  fun of(symbol: String): FeatureRequirement?

  /** The error for using [name], which needs [req], when it isn't met. */
  internal fun refusal(name: String, req: FeatureRequirement): String

  /** Kept for the existing single-feature call sites. */
  internal fun refusal(name: String, feature: Feature): String =
    refusal(name, FeatureRequirement.of(feature))

  fun refused(symbol: String, name: String, features: Features): String?
}
```

- **Algorithmic Steps & Invariants:**
  1. **`FeatureRequirement`:** `ids` sorts `features` by `Feature.ordinal` and maps them to `id`. `text` is
     `ids.joinToString(if (all) " and " else " or ")`.
  2. **The ownership maps** (`exact`, `subtrees`) store `FeatureRequirement` values. The local `owns(feature, …)` helper
     wraps with `FeatureRequirement.of(feature)`. Add two overloads that take a requirement: `owns(req:
     FeatureRequirement, vararg symbols)` in both builders. Phase 2 uses them.
  3. **`refusal` text:**
     - Single feature: `"$name needs the ${id} feature"`. This is exactly as today, so every existing test and message
       stays the same.
     - Any-of: `"$name needs the combat or skills feature"`.
     - All-of: `"$name needs the combat and skills features"`, with "features" plural.
  4. **`refused`:** `of(symbol)?.takeIf { !it.satisfiedBy(features) }?.let { refusal(name, it) }`.
  5. **`missingRulesExports`:** keep an export when `of(...)` is null or `satisfiedBy(features)`.
  6. **`forbiddenRulesExports`:** returns `List<Pair<String, FeatureRequirement>>`, keeping exports whose requirement is
     not satisfied. Update its caller to use `req.text` where it used `feature.id`. Keep today's message wording for a
     single feature.
  7. **`ApiModel.forFeatures.on`:** `FeatureOwnership.of(symbol).let { it == null || it.satisfiedBy(features) }`.
  8. **`ApiModel.symbol(path)`:**
     - A single-feature requirement still writes `field("feature", id)`, so today's JSON is unchanged.
     - A multi-feature one writes `field("features", ids)` (a JSON array) and `field("features_mode", if (all) "all"
       else "any")`, and no `feature` field.
     - Message symbols are unchanged; they keep their own single `feature`.
  9. **`DocsRenderer.featureBadge`:** a single feature renders as today (`feature: combat`). Multiple features render
     `feature: combat or skills`, or `features: combat and skills` for all-of. Change `badge(...)` to take the
     requirement, or add an overload; the visible text is `req.text`.

### Test & Documentation Requirements

- **`FeatureRequirementTest.kt` (core):**
  - Any-of is satisfied by either feature and fails with neither.
  - All-of needs both.
  - `text` and `ids` follow declaration order whatever order the arguments come in.
  - An empty set is rejected.
- **`FeatureOwnershipTest.kt` (script):**
  - `of("rules:to_hit")` is `FeatureRequirement.of(COMBAT)`.
  - `of("rules:cast_allowed")` is `of(SKILLS)`.
  - `of("rules:mitigate")` is null.
  - A subtree symbol (`"knockback.steps"`) resolves to `TRACK`.
  - The three forms of `refusal` text exactly.
- **CHANGELOG:** no script-visible change yet. Create the `### Spells that miss` subsection with one bullet: "Script API
  entries can be owned by several features, either any of them or all of them. `api.json` writes `features` and
  `features_mode` for such entries instead of `feature`."

### Exit Conditions

- [] `./gradlew :core:test :script:test :cli:test` passes.
- [] The API files regenerate with no diff, since no symbol has multiple owners yet.
- [] `./gradlew check` passes with zero warnings.

---

## Phase 2: `to_hit` and `defend_hit` owned by `combat` or `skills`

### Objective & Scope

- **In Scope:**
  - The `rules:to_hit`, `modifier:to_hit` and `modifier:defend_hit` symbols become owned by any of `combat` and
    `skills`.
  - The plugin host's trait modifier feature check does the same for `TO_HIT` and `DEFEND_HIT`.
  - The dry run calls `to_hit` with the synthetic skill when `skills` is on.
  - Test worlds with only skills enabled gain a `to_hit`.
  - Spec text, CHANGELOG and API files.
- **Out of Scope:** casting behavior (spells still always land, Phase 4); the spell branches in rulesets (Phase 3).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Rules.kt`: the dry run block around lines 1405–1445 (`withSkill`, the `if
    (Feature.COMBAT in features)` block), and the KDoc list at line 1270.
  - `script/src/test/kotlin/gups/script/FeatureScriptTest.kt`: the existing tests that check which exports a feature
    requires, and `modifier points of a feature need it` (around line 203).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`: `MODIFIER_POINT_FEATURES` (line 171) and where it is
    checked.
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Ownership.kt`.
  - `script/src/main/kotlin/gups/script/Rules.kt` (dry run and its KDoc).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`.
  - `script/src/testFixtures/kotlin/gups/script/testing/TestWorlds.kt`: `OWNED`.
  - Tests: `FeatureScriptTest.kt`, a plugin registration test in `plugin-api/src/test/kotlin/gups/pluginapi/` (follow
    the existing trait-modifier feature tests, which you can find by grepping `needs the combat feature`), and any
    inline test world with `skills` on and `combat` off that now fails to load.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the API files.
- **Do Not Touch:** `core/` world logic, `SkillCommands.kt`, the template world's rules.

### Specification & Decision Record Changes

All in `docs/SPEC.md`:

- **§3.10 `combat` row:**
  - Change "`to_hit`, `initiative`, `flee_chance`, …" to "`initiative`, `flee_chance`, …". `to_hit` moves to the new
    bullet below.
  - Change "the `defend_hit` modifier point;" to nothing.
- **§3.10:** add a bullet after "A feature that is off doesn't exist":

  > - **Shared ownership:** a few entries belong to more than one feature. The `to_hit` export and the `to_hit` and
  >   `defend_hit` modifier points belong to `combat` or `skills`, because auto-attacks and enemy spells both roll them.
  >   Such an entry exists while any one of its features is on. An entry can instead need all of its features, and then
  >   exists only while every one is on. A refusal names them, for example "`to_hit` needs the `combat` or `skills` > >
  >
  >   > feature".

- **§4.6 table, `to_hit` row:**
  - Feature column: "`combat` or `skills`".
  - Returns column: "Hit chance in percent, for an auto-attack swing or an enemy spell (§4.3). `ctx.attacker`,
    `defender`, `weapon`, `band`, `skill`, `damage_type`, `slot`."
- **§4.6 "Modifier points (v0)":** after "the lag points belong to the feature of the export of the same name.", insert
  "`to_hit` and `defend_hit` belong to `combat` or `skills`."
- **§4.6 dry run paragraph:** after "Each actor is called against another (…), unarmed.", insert "With `skills` on,
  `to_hit` is also called once per pair with the synthetic skill, as for an enemy spell."

### Implementation Blueprint

- **Algorithmic Steps & Invariants:**
  1. **`Ownership.kt`:**
     - Remove `"rules:to_hit"` from the `Feature.COMBAT` rules list, and `"modifier:to_hit"` and `"modifier:defend_hit"`
       from the combat modifier list.
     - Add `owns(FeatureRequirement.anyOf(Feature.COMBAT, Feature.SKILLS), "rules:to_hit", "modifier:to_hit",
       "modifier:defend_hit")`.
     - `rules_ctx.*` fields are unchanged.
  2. **`PluginHost.kt`:**
     - Change `MODIFIER_POINT_FEATURES` to `Map<PluginModifierPoint, FeatureRequirement>`, with `TO_HIT` and
       `DEFEND_HIT` mapped to `anyOf(COMBAT, SKILLS)` and the rest wrapped with `FeatureRequirement.of`.
     - The check becomes `req.satisfiedBy(features)`.
     - Its error text uses `req.text`, with the wording otherwise the same.
  3. **Dry run (`Rules.kt`):**
     - Leave `rules.toHit(c)` in the `COMBAT` block.
     - In the `SKILLS` block, add `rules.toHit(withSkill)` before `castAllowed`.
     - Update the KDoc bullets at line 1270 to match.
     - `withSkill` already exists. Leave its synthetic skill as it is. If its `target` is not `ENEMY`, it doesn't
       matter: the dry run never runs the cast path.
  4. **`TestWorlds.OWNED`:**
     - Change the map to `Map<String, Set<String>>`, meaning "kept when any listed feature is enabled".
     - Set `"to_hit" to setOf("combat", "skills")`. Every other entry becomes a single-element set.
     - Update the filter in `rulesFor` to `OWNED[name].let { it == null || it.any { f -> f in enabled } }`.
  5. **Skills-only test worlds:** run `./gradlew :script:test :harness:test :cli:test :plugin-api:test` and the samples'
     tests. Any world with `skills` on and `combat` off now fails with a missing `to_hit`; add one. Worlds built with
     `TestWorlds.rulesFor` get it from step 4. Inline rule strings need `def to_hit(ctx):\n return 100`.

### Test & Documentation Requirements

- **`FeatureScriptTest.kt`:**
  - With `skills` on and `combat` off, a rules package without `to_hit` is a load error reporting the missing export.
  - With both off, defining `to_hit` is a forbidden export, and the message names "combat or skills".
  - With only `combat` on, `to_hit` is still required.
  - A `defend_hit` modifier loads in a world with only `skills`. With neither feature, it fails with `needs the combat
    or skills feature`. Extend `modifier points of a feature need it` or add a sibling test. The `refused` helper takes
    one `Feature`, so for the shared case either generalize the helper or assert directly.
- **Dry run:** a skills-only rules package whose `to_hit` fails when `ctx.skill` is set (for example `return
  ctx.skill.nope`) fails the load.
- **Plugin host:** a plugin trait with a `TO_HIT` modifier registers in a skills-only world and is refused with both
  features off.
- **CHANGELOG bullet:** "`to_hit` (the export) and the `to_hit` and `defend_hit` modifier points now belong to `combat`
  or `skills`. A world with `skills` and without `combat` must define `to_hit`, and the dry run calls it with a
  synthetic skill whenever `skills` is on."

### Exit Conditions

- [] `./gradlew :script:test :plugin-api:test :harness:test :cli:test` passes.
- [] The API files are regenerated. The diff changes only `rules:to_hit`, `modifier:to_hit` and `modifier:defend_hit`
  (`feature` replaced by `features` and `features_mode`), plus the docs golden badges. `ApiDiffTest` passes.
- [] `./gradlew check` passes with zero warnings.

---

## Phase 3: Spell branches in rulesets and test doubles

### Objective & Scope

- **In Scope:** give every `to_hit` a spell branch before the engine starts passing spells to it, so Phase 4 changes no
  existing test:

  - the template world's real `_spell_hit` helper;
  - the script test worlds' override of it to 100;
  - `TestWorlds.copyWorld` doing the same by default;
  - Kotlin `RulesEngine` test doubles returning 100 for a skill;
  - inline Starlark `to_hit`s in tests that cast enemy spells.

  These branches are dead code until Phase 4.

- **Out of Scope:** engine changes; the robot world (Phase 6); the tutorial doc (Phase 6).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/testFixtures/kotlin/gups/script/testing/TestWorlds.kt`: `DEFAULTS`, `merged`, `blocks`, `rulesFor`,
    `copyWorld` (line 184).
  - The tests that cast spells:
    - `script/src/test/kotlin/gups/script/`: `RulesTest`, `FeatureScriptTest`, `ItemEffectsScriptTest`,
      `HookCtxFieldsTest`, `WorldFeaturesTest`, `HostTest`, `WorldModuleTest`;
    - `cli/src/test/kotlin/gups/cli/`: `FeatureStartupTest`, `DocsWorldTest`, `PluginIntegrationTest`;
    - `plugin-api/src/test/kotlin/gups/pluginapi/`: `CreationRegistrationTest`, `PluginLoaderTest`;
    - `samples/weather-plugin/.../WeatherPluginTest.kt` and `samples/curse-plugin/.../CurseWorldTest.kt`.
- **Files to Modify / Create:**
  - `template-world/rules/combat.star`.
  - `script/src/testFixtures/kotlin/gups/script/testing/TestWorlds.kt`.
  - `core/src/testFixtures/kotlin/gups/core/fixtures/FixtureRules.kt`.
  - Any other Kotlin `RulesEngine` double whose `toHit` doesn't already return 100 by default. Check with `grep -rn
    "override fun toHit" --include='*.kt'`. `core/src/test/kotlin/gups/core/world/Fixture.kt` already defaults `toHit`
    to 100; leave it.
  - Inline `def to_hit` in `RulesTest.kt`, `LevelChangeHookTest.kt` and `LevelingVerbsTest.kt`, only where that test
    world casts an enemy spell.
  - `samples/weather-plugin/src/test/kotlin/gups/sample/weather/WeatherPluginTest.kt` (around line 445) and
    `samples/curse-plugin/src/test/kotlin/gups/sample/curse/CurseWorldTest.kt` (around line 39). Both copy
    `template-world/rules/*` file by file into a temp world and don't go through `copyWorld`, so they get the real
    `_spell_hit`. After the copy, apply the same rewrite as `copyWorld` through a shared public helper,
    `TestWorlds.makeSpellsLand(rulesDir: Path)`, which `copyWorld` also uses.
    - If a sample's test classpath lacks `script`'s `testFixtures`, add
      `testImplementation(testFixtures(project(":script")))` to that sample's build file, the way
      `samples/heritage-plugin` already uses `TestWorlds`.
    - Before finishing the phase, grep every other test for `gups.templateWorld` and `template-world/rules` to find any
      test that loads the template's rules directly and casts an enemy skill. Route each one through the helper too.
- **Do Not Touch:** `core/src/main`, `script/src/main`, `samples/robot-world/`.

### Specification & Decision Record Changes

- None. §4.6 already points to the template's `combat.star` for the reference formulas.

### Implementation Blueprint

- **Template world `combat.star`:**

```python
def _spell_hit(ctx):
    # Percent chance that ctx.attacker's spell (ctx.skill) takes hold on ctx.defender.
    # A skill's free-form kind says how it is delivered: an "area" spell can't be dodged.
    if ctx.skill.kind == "area":
        return 100
    return _clamp(70 + (ctx.attacker.int - ctx.defender.wis) * 2, 5, 95)

def to_hit(ctx):
    # Percent chance that ctx.attacker's swing, or enemy spell when ctx.skill is set, hits
    # ctx.defender.
    if ctx.skill:
        return _spell_hit(ctx)
    ...  # the existing swing formula, unchanged
```

- **Algorithmic Steps & Invariants:**
  1. **Template:** place `_spell_hit` directly above `to_hit`. The spell formula must not read `level`, because
     `TestWorlds.rulesFor` strips `level` terms by text when `leveling` is off.
  2. **`TestWorlds.DEFAULTS`:** append `def _spell_hit(ctx):\n return 100\n`. Check that `blocks()` splits `_`-prefixed
     top-level defs the same way it splits exports, so the override replaces the template's helper. If it doesn't, make
     it.
  3. **`TestWorlds.makeSpellsLand(rulesDir: Path)` and `copyWorld`:**
     - `makeSpellsLand` rewrites the `_spell_hit` block of every `.star` file in `rulesDir` that defines one, so it
       returns 100. Reuse `blocks()`. A file without `_spell_hit` is left unchanged.
     - `copyWorld` gains a parameter `spellsAlwaysLand: Boolean = true`. When it is true, it calls
       `makeSpellsLand(to.resolve("rules"))` after copying.
     - KDoc: the default keeps plugin and integration tests independent of spell resistance; pass `false` to test it.
  4. **`FixtureRules.toHit`:** the first line is `if (c.skill != null) return 100`.
  5. **Inline Starlark `to_hit`s:** add `if ctx.skill:\n return 100` as the first statement, only in test worlds that
     cast an enemy spell.

### Test & Documentation Requirements

- **`TestWorlds`:** a test, in whatever test class exercises `TestWorlds` today or a new
  `script/src/test/kotlin/gups/script/TestWorldsTest.kt`, that:
  - checks `rules()` contains `def _spell_hit(ctx):\n return 100`;
  - checks `copyWorld()` rewrites `_spell_hit` and `copyWorld(spellsAlwaysLand = false)` does not.
- **Template dry run:** `gups lint` and the template's load already run the dry run with the synthetic skill (Phase 2),
  which covers `_spell_hit`'s reads of `int`, `wis` and `kind`.
- No CHANGELOG bullet; this phase has no script-visible engine change.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings. Behavior is unchanged: no test outside the new `TestWorlds` test
  changed its expectations.
- [] `cli/build/install/gups/bin/gups lint --world template-world` reports no problems (after `./gradlew
  :cli:installDist`).

---

## Phase 4: Enemy spells roll to land

### Objective & Scope

- **In Scope:**
  - The per-victim land roll in `CastAction.resolve`.
  - A `World.spellLands` helper with tracing.
  - The three `cast.resisted.*` messages.
  - `RulesEngine.toHit` KDoc.
  - Core and script tests.
  - Spec, CHANGELOG and API files (the message catalog).
- **Out of Scope:** `PluginModifierCall.skill` (Phase 5); content and tutorials (Phase 6).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Combat.kt` `swing()` (around lines 120–175): the roll, the trace line, and the
    `tell`/`tellPerceivers` message pattern.
  - `core/src/main/kotlin/gups/core/world/Rules.kt`: `toHitChain` (line 156), `traceRules`.
  - `core/src/main/kotlin/gups/core/model/Band.kt` (or wherever `Band.between` lives) and `World.indexOf`.
  - `core/src/test/kotlin/gups/core/world/DefenderModifierTest.kt` and `ModifierChainTest.kt`: the `protos(...)`,
    `RecordingHost` and modifier helpers for giving an actor a `defend_hit` modifier that returns 0.
  - `core/src/test/kotlin/gups/core/world/ActionLifecycleTest.kt`: how casts are driven in tests.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt` (`SkillMsg`, `CastAction.resolve`, the new helper).
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt` (KDoc of `toHit` only).
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (KDoc of `ModifierPoint.TO_HIT` and `DEFEND_HIT` only).
  - `script/src/main/kotlin/gups/script/Rules.kt`: only the `doc` strings of the `TO_HIT` `RulesExportSpec` (around line
    821) and the `TO_HIT` and `DEFEND_HIT` entries of `MODIFIER_POINTS` (around line 1076).
  - New `core/src/test/kotlin/gups/core/world/SpellLandingTest.kt`.
  - `script/src/test/kotlin/gups/script/RulesTest.kt` (one Starlark end-to-end test).
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the API files.
- **Do Not Touch:** `Combat.kt` (`swing` is unchanged), `Actions.kt`, `KernelCommands.kt` (`use_effect` never rolls),
  `plugin-api/`.

### Specification & Decision Record Changes

All in `docs/SPEC.md`:

- **§4.3:** add a paragraph after "Targeting":

  > **Landing:** an `enemy` skill rolls to land on each actor it reaches other than the caster, one roll per victim for
  > a `room`-range skill. The chance is `rules.to_hit` with `ctx.skill` set, then the caster's `to_hit` chain, then the
  > victim's `defend_hit` chain (§4.6). A chance of 100 or more lands without a roll. A victim it doesn't land on
  > resists: that victim gets neither the `effect` nor `resolve`, and the caster, the victim and the room are told. The
  > cost and lag are paid either way, and the victim is still engaged (§5.4). `ally`, `self`, `item` and `none` skills
  > always land.

- **§4.3 "Proficiency":** replace the sentence with "**Proficiency** is stored but not rolled against by the engine.
  Rules functions can read it from `ctx.actor.skills`, for example in `to_hit` when `ctx.skill` is set."
- **§4.6 rules `ctx` paragraph:** in the description of `skill`, make clear it is set in `to_hit` and `defend_hit` for
  an enemy spell's roll, where `weapon`, `slot` and `damage_type` are `None`.
- **§4.6 "Whose chain":** change "the attacker's for `to_hit` and `damage` (the caster is the attacker for spell
  damage)" to "the attacker's for `to_hit` and `damage` (the caster is the attacker for an enemy spell's roll and for
  spell damage)".
- **§4.6 "Attack and defense":** replace "Spells have no to-hit roll and run no `defend_hit` chain." with "An `enemy`
  spell rolls the same way against each victim, with `ctx.skill` set and no weapon or slot. A chance of 100 or more
  lands without a roll (§4.3)."
- **§4.6 trace paragraph:** append "A spell's roll ends in `LAND` or `RESIST`, or in `→ auto LAND` when the chance is
  100 or more."
- **§5.3 step 5 "Resolve":** after "Apply the `effect`, or call `resolve(ctx)`.", insert "For an `enemy` skill, each
  victim first rolls to land (§4.3); a victim that resists gets neither."
- **§5.4 "Engagement":** change "a hostile skill landing" to "a hostile skill cast at it (an `enemy` skill engages its
  victim whether or not it lands)".
- **§4.18:** if it lists catalog keys or groups, add the three `cast.resisted.*` keys to the `skills` group.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// SkillCommands.kt, in SkillMsg (mirror DONE_TARGET / DONE_VICTIM / ROOM_TARGET for the
// placeholder and required-placeholder arguments):
val RESISTED_SELF =
  msg("cast.resisted.self", "{target} resists your {skill}.", "skill,target", "skill")
val RESISTED_TARGET =
  msg("cast.resisted.target", "You resist {actor}'s {skill}.", "actor,skill", "skill")
val RESISTED_ROOM =
  msg("cast.resisted.room", "{target} resists {actor}'s {skill}.", "actor,skill,target", "skill")

/**
 * Whether [caster]'s enemy [skill] lands on [victim]: to_hit with the skill set, the caster's
 * to_hit chain, then the victim's defend_hit chain. A chance of 100 or more lands without a roll.
 */
internal fun World.spellLands(caster: Actor, victim: Actor, skill: SkillProto): Boolean
```

- **Algorithmic Steps & Invariants:**
  1. **`spellLands`:**
     - Build `RulesContext(this, caster, victim, band = Band.between(indexOf(caster), indexOf(victim)), skill = skill)`.
       Leave `weapon`, `slot` and `damageType` null.
     - Compute `val chance = toHitChain(c)`.
     - If `chance.value >= 100`: `traceRules(caster, "${chance.text} → auto LAND")` and return true.
     - Otherwise `val roll = rng.int(1, 100)`, then `traceRules(caster, "${chance.text} → roll $roll ${if (roll >
       chance.value) "RESIST" else "LAND"}")`, and return `roll <= chance.value`.
  2. **`CastAction.resolve` loop** (lines 226–236). Keep the order exactly:
     - `startCombat` for an enemy skill and a victim other than the caster, as today.
     - Then, when `skill.target == TargetKind.ENEMY && each != null && each !== actor && !w.spellLands(actor, each,
       skill)`: send the resisted messages and `continue`, skipping both `effect` and `resolve` for this victim.
     - Otherwise apply `effect` and `resolve` as today.
  3. **Resisted messages:**
     - `w.send(actor, w.msg(RESISTED_SELF, "target" to each.name.cap(), "skill" to skill.name))`.
     - `w.tell(each, RESISTED_TARGET, "actor" to actor.name.cap(), "skill" to skill.name)`.
     - `w.tellSight(actor.room, "cast", actor, w.msg(RESISTED_ROOM, …), actor, each)`, mirroring the existing
       `tellSight` call above the loop.
  4. **Everything else stays the same:** the cost is paid first, the DONE and ROOM messages are sent before the loop,
     `ON_CAST` fires after the loop, and the method returns `skill.lag`.
  5. **`RulesEngine.toHit` KDoc:** "Calculates the hit chance in percent for an auto-attack swing, or for an enemy spell
     when `c.skill` is set (then `c.weapon` and `c.slot` are null)."
  6. **Stale docs that say only swings roll:**
     - `ModifierPoint.TO_HIT` KDoc: "Modifies the hit chance of an auto-attack swing or an enemy spell."
     - `ModifierPoint.DEFEND_HIT` KDoc: "Adjusts the hit chance of an auto-attack or enemy spell on the defender's
       chain, after the attacker's to_hit chain."
     - The `TO_HIT` export doc: "Hit chance in percent before modifiers, for an auto-attack swing or an enemy spell
       (ctx.skill set, no weapon or slot)."
     - The `TO_HIT` modifier doc: "Adjusts hit chance in percent, for swings and enemy spells."
     - The `DEFEND_HIT` modifier doc: replace "auto-attack's" with "auto-attack's or enemy spell's".

### Test & Documentation Requirements

- **`SpellLandingTest.kt` (core),** using the core `Fixture` with a test `skill(... target = ENEMY, effect =
  damage(...))`:
  - With the default fixture, the spell lands and damages, and no RNG is drawn for the roll. Compare `rng` state, or the
    trace's `→ auto LAND`.
  - A victim with a `defend_hit` modifier returning 0:
    - takes no damage, and its `resolve` (a recording `SkillResolver`) doesn't run;
    - the caster's cost is paid and `waitState >= lag`;
    - both are engaged with each other;
    - the caster gets `"… resists your …"`, the victim `"You resist …"`, and a bystander the room line;
    - `on_cast` fires once.
  - A `to_hit` modifier on the caster sees `c.skill` set and `c.weapon`/`c.slot` null. Record them inside the modifier.
  - A `room`-range enemy skill against two victims where only one has the 0 modifier: one is damaged and one resists.
  - An `ally` skill with a `damage` effect on another actor never consults `to_hit` (a recording modifier is not
    called).
  - The trace contains `→ defender:defend_hit →` and ends in `RESIST`.
- **`RulesTest.kt` (script):** a Starlark world whose `to_hit` returns 0 when `ctx.skill` is set (override `_spell_hit`
  through `TestWorlds.rules(...)`). A player's enemy `damage` spell at a mob leaves the mob's hp unchanged and prints
  the resisted line.
- **CHANGELOG bullets:**
  - "Enemy spells can miss. An `enemy` skill rolls `to_hit` (with `ctx.skill` set and no weapon or slot), the caster's
    `to_hit` chain and the victim's `defend_hit` chain, once per victim. A victim it doesn't land on gets neither the
    effect nor `resolve`, but the cost, lag and engagement still happen. A chance of 100 or more lands without a roll.
    `@trace rules` ends the line with `LAND`, `RESIST` or `auto LAND`."
  - "Added the messages `cast.resisted.self`, `cast.resisted.target` and `cast.resisted.room`."

### Exit Conditions

- [] `./gradlew :core:test :script:test` passes.
- [] The full `./gradlew check` passes with no other test's expectations changed. A failure outside the new tests means
  some ruleset still lacks a spell branch; fix it the Phase 3 way.
- [] The API files are regenerated. The diff adds only the three `message:cast.resisted.*` entries, plus the doc text of
  `rules:to_hit`, `modifier:to_hit` and `modifier:defend_hit` from step 6. `ApiDiffTest` passes.

---

## Phase 5: Plugin trait modifiers see the skill

### Objective & Scope

- **In Scope:** `PluginModifierCall.skill`, set from the rules ctx; `docs/PLUGINS.md`; §4.15; CHANGELOG.
- **Out of Scope:** the heritage sample's content; new plugin modifier points.

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt` (`SkillFacade`, line 333).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt` line 219, an existing `SkillFacade(it)` use.
  - `plugin-api/src/test/kotlin/gups/pluginapi/TraitModifierTest.kt`: patterns for driving a trait modifier.
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt` (`PluginModifierCall`).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt` (the `PluginModifierCall(...)` construction at line
    122).
  - `plugin-api/src/test/kotlin/gups/pluginapi/TraitModifierTest.kt`.
  - `docs/PLUGINS.md`, `docs/SPEC.md` (§4.15), `docs/CHANGELOG.md`.
  - The plugin API baseline or docs golden if `./gradlew check` reports one; follow the failing test's instructions.
- **Do Not Touch:** `core/`, `script/`.

### Specification & Decision Record Changes

- **§4.15:** where `PluginModifierCall`'s fields are described, add "`skill`, the skill being cast (a `PluginSkill`)
  when the rules call has one: an enemy spell's `to_hit` and `defend_hit`, and a spell's `damage`, `receive_damage` and
  `mitigate` chains; otherwise null". If §4.15 doesn't list the fields, add this sentence next to its mention of trait
  modifiers.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
data class PluginModifierCall(
  val world: PluginWorld,
  val point: PluginModifierPoint,
  val actor: PluginActor,
  val other: PluginActor?,
  val weapon: PluginItem?,
  val room: PluginRoom,
  /** The skill being cast when this rules call has one (an enemy spell's roll, a spell's damage), else null. */
  val skill: PluginSkill? = null,
)
```

- **Algorithmic Steps & Invariants:**
  1. In `Adapters.kt`, pass `skill = c.skill?.let { SkillFacade(it) }`. That's the only change there.

### Test & Documentation Requirements

- **`TraitModifierTest.kt`:**
  - A trait `DEFEND_HIT` modifier records `call.skill`. It is the cast skill's `label`/`kind` when an enemy spell rolls
    against the bearer, and null for an auto-attack swing.
  - A trait `DEFEND_HIT` modifier returning 0 when `call.skill?.kind == "spell"` makes the bearer resist. This is "elves
    resist charm" in miniature.
- **`docs/PLUGINS.md`:** in the trait modifiers section, document `skill` with a short Kotlin snippet. Keep the snippet
  outside lists, or within the formatter's indentation limit.
- **CHANGELOG bullet:** "`PluginModifierCall` has `skill`, the skill being cast when the rules call has one, so a trait
  modifier can tell a spell from a swing."

### Exit Conditions

- [] `./gradlew :plugin-api:test` passes.
- [] `./gradlew check` passes with zero warnings. The script API files are unchanged. If a plugin API golden or baseline
  changes, its diff adds only `PluginModifierCall.skill`.

---

## Phase 6: Robot world, tutorial, and finalization

### Objective & Scope

- **In Scope:**
  - The robot world's spell branch and resisted messages, and its foundry tests.
  - The template tutorial note.
  - An end-to-end scenario.
  - The §7.4 row.
  - Marking the `DESIGN_NEEDED` entry completed.
  - A full-suite run.
- **Out of Scope:** any engine change. If a test here exposes an engine bug, fix it in the module that has it, with a
  test, and say so in your summary.

### Context Working Set

- **Read / Reference Only:**
  - `samples/robot-world/MODULE.gups` (stats `might`, `agility`, `tech`, `grit`, `plating`).
  - `samples/robot-world/zones/foundry/items.star` (`mind_lash`, `overload`).
  - `cli/build.gradle.kts` around line 50: how the robot world's tests run.
  - `AGENTS.md` "End-to-end checks".
- **Files to Modify / Create:**
  - `samples/robot-world/rules/combat.star`.
  - `samples/robot-world/MESSAGES.gups`.
  - `samples/robot-world/zones/foundry/foundry_test.star`.
  - `template-world/docs/tutorials/03_items_and_equipment.md` (or the tutorial that covers rules and `to_hit`; pick the
    one that already discusses `defend_hit`).
  - `docs/SPEC.md` (§7.4), `docs/DESIGN_NEEDED.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** engine sources; the template's `combat.star` formulas (already done in Phase 3).

### Specification & Decision Record Changes

- **§7.4:** add a row after the last milestone row present (M26, or a later one if M27/M28 have landed):

  > | **M29** | Whether a spell lands: `enemy` skills roll `to_hit` (with `ctx.skill`), the caster's `to_hit` chain and
  > the victim's `defend_hit` chain per victim, and a resisted victim gets no effect or `resolve`; `cast.resisted.*`
  > messages; `to_hit` and `defend_hit` owned by `combat` or `skills`, with any-of and all-of feature ownership;
  > `PluginModifierCall.skill`; the template's and robot world's spell formulas (§3.10, §4.3, §4.6, §4.15, §5.3, §5.4).
  > |

- **`docs/DESIGN_NEEDED.md`:** rename the heading to `### Whether a spell lands (COMPLETED — M29)` and leave the body in
  place. Don't edit the "Skill proficiency" or "Recognizing an unfamiliar spell" entries.

### Implementation Blueprint

1. **Robot world `combat.star`:** add a `_spell_hit(ctx)` helper returning `_clamp(65 + (ctx.attacker.tech -
   ctx.defender.grit) * 2, 5, 95)`, with a one-line comment that routines are resisted by grit. Then add `if ctx.skill:
   return _spell_hit(ctx)` as the first statement of `to_hit`.
2. **Robot world `MESSAGES.gups`:** in the casting block, add `"cast.resisted.self": "{target} shrugs off your
   {skill}."`, `"cast.resisted.target": "You shrug off {actor}'s {skill}."` and `"cast.resisted.room": "{target} shrugs
   off {actor}'s {skill}."`.
3. **`foundry_test.star`:** run the robot world's tests the way `cli/build.gradle.kts` does.
   - For each test that now fails, decide whether it depends on the spell landing.
   - If it does, pick a seed where it lands, and say in a comment that the seed is chosen so the routine lands.
   - Add `test_routine_can_be_shrugged_off`: find a seed where `mind_lash` is resisted, and assert the shrug-off line
     and that the drone's `sanity` (or whatever vital `psychic` hits in `mitigate`) is unchanged.
4. **Tutorial:** add a short subsection, "Spells that miss", next to the `defend_hit` material. Cover:
   - `to_hit` sees `ctx.skill` for enemy spells;
   - the template's `_spell_hit` and the `kind == "area"` example;
   - `defend_hit` items such as `shadow_cloak` now also blur spells unless they check `ctx.skill`;
   - returning 100 means always land.

   Keep code fences out of lists, or within the formatter's indentation limit.

5. **End-to-end check:** a scenario can't change the rules partway through a run, so use two runs:

   - Copy the template world twice: `local-world-test/world_resist`, with `_spell_hit` returning 0, and
     `local-world-test/world_land`, with `_spell_hit` returning 100.
   - In both copies, add the same enemy `damage` skill to the tutorial zone.
   - Write one scenario, `local-world-test/spell_scenario.star`: a player who knows the skill casts it at the training
     dummy, the scenario prints the dummy's hp before and after, and it calls `w.trace()` first.
   - Run it with `gups scenario` against each world, as `AGENTS.md` describes.
   - Check that the resist run shows the resisted line, unchanged hp and `RESIST` in the trace, and that the land run
     shows lower hp and `auto LAND`.
   - Delete `local-world-test/` afterwards.

### Test & Documentation Requirements

- The robot world tests pass with the new test included.
- CHANGELOG bullet: "The template's `to_hit` has a spell branch (`_spell_hit`: `int` against `wis`, and `area` spells
  always land), and the robot world's routines are resisted by `grit`."

### Exit Conditions

- [] `./gradlew check` passes with zero warnings. Markdown is formatted (`./gradlew markdownFormat`, then code blocks
  checked).
- [] The robot world's tests pass, including `test_routine_can_be_shrugged_off`.
- [] The `gups scenario` end-to-end check passed and its trace showed `RESIST` and `auto LAND`. `local-world-test/` is
  deleted.
- [] The `DESIGN_NEEDED` entry is marked `(COMPLETED — M29)`, not deleted, and the §7.4 row is present.
