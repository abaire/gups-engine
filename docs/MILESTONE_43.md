# Milestone 43: Skill Advancement Through Use

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, section "Combat and skills", heading `### Skill-based advancement
  (worlds without character levels)`.

- **Design Summary:** the engine reports skill use and leaves the gain policy to Starlark. A sample library in the robot
  world (the sample world without `leveling`) turns uses into proficiency through deterministic progress points and a
  curve the world edits.

  - **New global hook `on_skill_use`** (feature `skills`; global only, like `on_group_change`; attachable to no entity).
    - ctx fields: `self` (the actor), `actor`, `skill` (the skill view), `target` (the target actor, or `None`), `item`
      (an `item` skill's item, or the item a report names, or `None`), `targets` (for a cast, the actors the cast landed
      on, in order; `[]` for `item` and `room` target skills and for reports), `outcome`, `room`, `pulse`, `rng`.
    - `outcome` is one of:
      - `"fail"`: the cast failed `skill_check`. It fires after the `cast.failed.*` messages. Today nothing reports
        this.
      - `"resist"`: an `enemy` cast got off, reached at least one victim, and landed on none of them. It fires after
        `on_cast`.
      - `"success"`: any other cast that got off (including an `enemy` cast that landed on at least one victim, and an
        `enemy` `room`-range cast with nobody to reach). It fires after `on_cast`.
      - `"report"`: a script or plugin reported a use (below).
    - Fires once per resolved cast, players and mobs alike (`act_cast` included), only while the caster is still in the
      world.
    - Does **not** fire for a cast refused by validation, `cast_allowed` or `before_cast`, for a cast that never
      resolves (interrupted, caster or target moved, `cast.moved`), or for procs. Procs never count: a skill proc's
      skill is often the item's, the bearer may not know it, and its chance is the item's. A proc's `resolve` may call
      `ctx.report_skill_use` itself.
    - Without `skills`, `global_hook("on_skill_use", fn)` loads and never fires, like `on_level_change` without
      `leveling`.
  - **New verb `ctx.report_skill_use(actor, skill, target=None, item=None)`** (feature `skills`): fires `on_skill_use`
    with `outcome = "report"` and returns `None`. `skill` is a label string (absolute or relative to the calling
    package) or a skill view; an unknown skill is an error. The engine does not check that the actor knows the skill.
    This is how passive skills (an artificer's skill feeding `item_power`, a weapon skill a rules `to_hit` term reads)
    advance: the engine cannot see which skill a pure rules export read, so the script that knows reports it, for
    example from an item's `on_use`. Not limited to privileged packages, the same as `set_skill`.
  - **Plugins:** `PluginWorld.reportSkillUse(actor, skill, target = null, item = null)`, and `on_skill_use` joins the
    names accepted by `PluginRegistry.onEvent` (added by Milestone 41), with `fields` `skill` (label string), `outcome`,
    `target`, `item` and `targets`.
  - **No new engine state.** Proficiency stays the 0..100 `Int` in `Actor.skills`, changed only through `set_skill` (so
    `learn` restrictions cap any gain policy for free, including by class or character level). Skill progress lives in
    the library's own `lib` namespace.
  - **Character levels and skills mix freely.** `skills` and `leveling` are independent features. A skill that gates on
    the character's level is a `learn` restriction or a library check reading `ctx.actor.level` behind
    `ctx.has_feature("leveling")`. The engine already never reads `level` without `leveling` (every Kotlin read is
    behind a `LEVELING` guard, and the actor view's `level` is an error without it); this milestone states it in §3.10
    and pins it with a test. The template rules' reads of `level` in `to_hit`, `skill_power` and `regen` are template
    choices for a world that has `leveling`.
  - **Sample library `samples/robot-world/lib/skill_gain.star`** (robot world only; the template world is unchanged).
    Its policy is plain Starlark, a sample for worlds to tune:
    - `POINTS = {"fail": 3, "resist": 2, "success": 2, "report": 2}`: points per counted use.
    - `USE_WINDOW_PULSES = 40` (10 s): at most one counted use per skill per window.
    - `_points_needed(skill, prof)`: points from `prof` to `prof + 1`, default `1 + prof * prof // 50` (1 at 1, 51 at
      50, 197 at 99). It gets the skill view so a world can make some skills harder by `tags` or `kind`.
    - The hook skips mobs, skills the actor doesn't know (so a report can't teach), and proficiency 100. It is
      deterministic: no rolls.
  - **Robot-world demo:** a `self_repair` routine (a cheap `self` heal) and a single-use `repair_chip` at `:gate` that
    installs it at 1%.
- **Specification Impact:**
  - §3.10: the `skills` row of the feature table (`on_skill_use`, `report_skill_use`), the "Without `leveling`" bullet,
    and the global-hook exception text in "A feature that is off doesn't exist" if it names hooks.
  - §4.3: one sentence after **Success and power** pointing to `on_skill_use`.
  - §4.4: a new catalog row and an **`on_skill_use`** paragraph.
  - §4.5: the verb table and verb semantics (`report_skill_use`), and the `outcome` and `targets` field descriptions.
  - §4.9: **Global hooks** (the hookable list) and a new `//lib:skill_gain.star` paragraph describing the robot-world
    sample.
  - §4.15: the `PluginWorld` facade (`reportSkillUse`) and the `onEvent` names and fields.
  - §7.4: a new M43 row.
  - `docs/CHANGELOG.md`, `docs/PLUGINS.md` (event list), and the regenerated API files.
- **Decision Record Updates:** None. `docs/DECISIONS.md` holds no live records. Do not add `D-xxx` entries for anything
  this document settles: the spec text below is approved.

### Ground rules for every phase

1. **Ordering and concurrency.** Start only once Milestone 41 is merged: Phase 3 extends its `onEvent` list, and
   Milestone 41 changes `World.dispatchGlobal` to take a subject actor. Another milestone (Milestone 42) is being
   written in parallel and may land first. Always re-read the current `SPEC.md`, `CHANGELOG.md`, `Hooks.kt`,
   `Declarations.kt`, `Ownership.kt`, `PluginHost.kt` and `Facade.kt`, and locate code by symbol name, never by the line
   numbers quoted here. Put the §7.4 row after the highest existing milestone row.
2. **TDD** (`AGENTS.md`): write the tests named in each phase first, run them and see them fail, then implement.
3. **Spec edits are pre-approved.** Apply the SPEC text given in each phase directly. Log a `DECISIONS.md` entry only
   for a genuinely new gap you have to fill.
4. **No spec section numbers or decision ids in code comments**, Kotlin or Starlark.
5. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`. The engine is
   unreleased: no migrations or back-compat shims.
6. **CHANGELOG:** add bullets to a new `### Skill advancement through use` subsection at the top of `## api_version 1
   (unreleased)`, above whatever subsection is first there now. Create it in the first phase that adds a bullet; later
   phases append.
7. **API files.** In any phase that changes the script API, plugin API or sample content, regenerate before the phase's
   final `./gradlew check` if a golden or API test fails:
   - `./gradlew :cli:test -Dgups.updateGolden=true` (rewrites `cli/src/test/resources/api.golden.json` and
     `cli/src/test/resources/docs-golden/`).
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout (rewrites `docs/api/api-1.json`).
   - Read the diff: only the symbols the phase names may be new or changed. `ApiDiffTest` must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing entry.
8. **Gradle** runs outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation. `./gradlew check` must pass with zero warnings.

---

## Phase 1: The `on_skill_use` Hook, Fired by Casts

### Objective & Scope

- **In Scope:**
  - `HookName.ON_SKILL_USE`, its `HookSpec`, its feature ownership, and its place in the `global_hook` allowlist.
  - `HookContext` fields for `outcome` and the landed actors; the ctx fields `outcome` and `targets` read them.
  - `CastAction.resolve` fires the global hook with `"fail"`, `"resist"` or `"success"`.
  - Spec text for the hook and for skills without `leveling`.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):** `ctx.report_skill_use`
  (Phase 2); any plugin change, including `onEvent` and the facade (Phase 3); any robot-world or template-world content
  (Phase 4); firing from procs (never).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt`: `CastAction.resolve` (the `skillSucceeds` failure branch,
    the `targets` loop with `spellLands`, and the closing `w.dispatch(HookName.ON_CAST, ...)`).
  - `core/src/main/kotlin/gups/core/world/Owners.kt` and `Groups.kt`: existing `dispatchGlobal` call sites to mirror.
  - `script/src/test/kotlin/gups/script/GlobalHookTest.kt`, `SpellResolutionTest.kt`, `HookCtxFieldsTest.kt`,
    `FeatureOwnershipTest.kt`: test patterns.
  - SPEC §3.10, §4.3, §4.4, §4.9.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Hooks.kt` (`HookName`, `HookContext`).
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt` (`CastAction.resolve`).
  - `script/src/main/kotlin/gups/script/Declarations.kt` (the `HookSpec` list and `globalHook`'s name check).
  - `script/src/main/kotlin/gups/script/Ctx.kt` (`outcome` field, `targets` field).
  - `script/src/main/kotlin/gups/script/Ownership.kt`.
  - `script/src/test/kotlin/gups/script/SkillUseHookTest.kt` (new).
  - `docs/SPEC.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `Procs.kt`, `Rules.kt`, the rules exports, persistence, GMCP, `plugin-api/`, `template-world/`,
  `samples/`.

### Specification & Decision Record Changes

- **§3.10 feature table, `skills` row:** after `before_cast`/`on_cast_start`/`on_cast`, add "the global `on_skill_use`
  hook,". (Phase 2 adds the verb to the same row.)
- **§3.10, "Without `leveling`" bullet:** append: "The engine never reads an actor's level or XP without `leveling`; a
  rules export or script that reads them gets an error (§4.5). Skills still advance through `set_skill`, from trainers
  or from a use-based library listening to `on_skill_use` (§4.9), and `learn` restrictions cap them."
- **§3.10, "A feature that is off doesn't exist":** wherever the text lists the global hooks that load and never fire
  with their feature off, add `on_skill_use` (without `skills`).
- **§4.3, after the **Success and power** paragraph:** "Every cast that resolves reports a use through the global
  `on_skill_use` hook (§4.4): `"fail"` after a failed `skill_check`, otherwise `"resist"` or `"success"` after
  `on_cast`."
- **§4.4 catalog table:** a row after `on_owner_change`: `| on_skill_use | — | Global only (§4.9) | actor, skill,
  target, item, targets, outcome |`.
- **§4.4, new paragraph after **`on_owner_change`**:**

  "**`on_skill_use`** needs `skills` and is a global hook (§4.9); it can't be attached to an entity. It reports a use of
  a skill so a world can advance skills through use. `ctx.self` and `ctx.actor` are the user, `ctx.skill` the skill,
  `ctx.target` the target actor (or `None`), `ctx.item` an `item` skill's item (or `None`), `ctx.targets` the actors the
  cast landed on, in the order it reached them (`[]` for `item` and `room` target skills), and `ctx.outcome` one of
  `"fail"`, `"resist"`, `"success"` and `"report"`. A cast that resolves fires it once, for players and mobs: `"fail"`
  when `skill_check` fails, after the failure messages; otherwise after `on_cast`, `"resist"` when an `enemy` cast
  reached at least one victim and landed on none, and `"success"` in every other case. A cast refused before it starts
  or one that never resolves (interrupted, or failed because the caster or target moved) fires nothing, and neither do
  procs. A caster no longer in the world when the cast ends fires nothing. `ctx.report_skill_use` (§4.5) fires it with
  `"report"`. The engine gives the event no further meaning: gains are the world's (§4.9). Its return value is ignored."

- **§4.9, **Global hooks**:** add `on_skill_use` to the list of hooks that can be global, and to the sentence about
  features: "In a world without `leveling` (for `on_level_change`), `combat` (for `on_disengage`) or `skills` (for
  `on_skill_use`) the declaration loads and the hook never fires". Merge with the current wording; Milestone 41 may have
  added `on_death` to the same list.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Hooks.kt, HookName, after ON_OWNER_CHANGE
/**
 * Invoked, as a global hook only, once for each use of a skill: a cast that resolves, or a
 * script's or plugin's report.
 *
 * [HookContext.actor] is the user, [HookContext.skill] the skill, [HookContext.target] the target
 * actor, [HookContext.item] the item, [HookContext.landed] the actors a cast landed on, and
 * [HookContext.outcome] "fail", "resist", "success" or "report".
 */
ON_SKILL_USE("on_skill_use"),

// Hooks.kt, HookContext, new fields at the end
/** How a skill use went, for on_skill_use: "fail", "resist", "success" or "report". */
val outcome: String? = null,
/** The actors a cast landed on, for on_skill_use. */
val landed: List<Actor>? = null,
```

```kotlin
// SkillCommands.kt, a private helper beside CastAction (or a member)
private fun World.reportCastUse(
  actor: Actor,
  skill: SkillProto,
  target: Actor?,
  item: Item?,
  outcome: String,
  landed: List<Actor>,
) {
  if (!actor.inWorld) return
  dispatchGlobal(
    HookName.ON_SKILL_USE,
    HookContext(
      actor = actor,
      target = target?.takeIf { it !== actor },
      item = item,
      skill = skill,
      outcome = outcome,
      landed = landed,
    ),
    // pass `actor` as the subject in whatever form Milestone 41 gave dispatchGlobal
  )
}
```

```kotlin
// Declarations.kt HookSpec list, after ON_OWNER_CHANGE
HookSpec(
  hook = HookName.ON_SKILL_USE,
  signature = "fn(ctx)",
  attachTo = emptyList(),
  ctxFields = listOf("self", "actor", "skill", "target", "item", "targets", "outcome", "room", "pulse", "rng"),
  doc = "Global hook: fires for each use of a skill, with ctx.outcome 'fail', 'resist', 'success' or 'report'.",
),
```

- **Algorithmic Steps & Invariants:**
  1. **`Ownership.kt`:** add `owns(Feature.SKILLS, "hook:on_skill_use", "ctx.outcome")` beside the existing
     `Feature.SKILLS` lines. Confirm with a test that `global_hook("on_skill_use", fn)` still **loads** in a world
     without `skills` (the `hook:on_disengage` entry is the model: combat-owned, yet its global declaration loads with
     combat off). If ownership makes the declaration fail, exempt `global_hook` the same way `on_disengage` is exempted.
  2. **`Declarations.globalHook`:** add `on_skill_use` to the accepted names and to the error message's list (keep the
     list in the order of §4.9).
  3. **`Ctx.kt`:**
     - New struct field `outcome`: `hc.outcome ?: Starlark.NONE`. Doc: "How a skill use went, for on_skill_use."
     - `targets`: read `hc.mobs ?: hc.landed ?: extras.targets`, and extend its doc with "or the actors a cast landed
       on, for on_skill_use".
  4. **`CastAction.resolve`:**
     - In the `!w.skillSucceeds(c)` branch, after the `tellSight` and before `return skill.lag`: `w.reportCastUse(actor,
       skill, target, targetItem, "fail", emptyList())`.
     - In the success path, keep `val landed = mutableListOf<Actor>()` and `var reached = 0`. In the `for (each in
       targets)` loop: when `hostile`, increment `reached`; add `each` to `landed` when it is not `null` and the effect
       is applied to it (that is, after the resist `continue`, for every non-null `each`, hostile or not).
     - After the closing `w.dispatch(HookName.ON_CAST, ...)`, compute `outcome = if (skill.target == TargetKind.ENEMY &&
       reached > 0 && landed.isEmpty()) "resist" else "success"` and call `w.reportCastUse(actor, skill, t, onItem,
       outcome, landed)`.
     - Invariant: exactly one `on_skill_use` per resolve call; none from `validate`, `veto`, `begin` or interrupt paths.
  5. **Leveling independence:** no Kotlin change. The test below pins that a world with `skills` and without `leveling`
     casts, fires `on_skill_use`, and lets the hook call `ctx.set_skill`.

### Test & Documentation Requirements

- `script/src/test/kotlin/gups/script/SkillUseHookTest.kt` (mirror the fixtures of `GlobalHookTest.kt` and
  `SpellResolutionTest.kt`; the global hook records `ctx.outcome`, the skill label, the target name and
  `len(ctx.targets)` into an attribute or a list the test reads):
  - A forced-fail `skill_check` (rules returning 0, or `w.force_rng`) fires once with `"fail"`, `targets == []`, and the
    failure messages arrive before the hook's output.
  - A successful `self` heal fires `"success"` with `targets` = [caster].
  - An `enemy` cast that lands fires `"success"` with the victim in `targets`; one that is resisted (forced `to_hit` 0)
    fires `"resist"`.
  - A `room`-range `enemy` cast with two victims, one resisting, fires once with `"success"` and one landed actor.
  - An `item` skill fires `"success"` with `ctx.item` the item and `targets == []`.
  - A cast refused by `before_cast` or `cast_allowed` fires nothing; an interrupted warmup fires nothing.
  - A mob's `act_cast` fires it with the mob as `ctx.actor`.
  - It fires after `on_cast` (a mob `on_cast` observer and the global hook append to the same list; check the order).
  - `global_hook("on_skill_use", fn)` from a zone package fails the load (privileged only); a mob's `hooks` keyword
    naming `on_skill_use` fails the load (not attachable).
  - In a world with `features = ["combat"]`, `global_hook("on_skill_use", fn)` loads and never fires.
  - In a world with `features = ["combat", "skills"]` (no `leveling`), a cast fires the hook, the hook's `ctx.set_skill`
    raises the proficiency, and `ctx.actor.level` read in the hook is an error naming `leveling`.
- Update `HookCtxFieldsTest.kt` and `FeatureOwnershipTest.kt` if they enumerate hooks or owned names.
- CHANGELOG: "The global `on_skill_use` hook (feature `skills`): every cast that resolves reports `"fail"`, `"resist"`
  or `"success"`, so a world can advance skills through use."

### Exit Conditions

- [] `./gradlew :script:test --tests '*SkillUseHookTest*'` passes.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.
- [] SPEC §3.10, §4.3, §4.4 and §4.9 describe exactly what the code does; `grep -n "on_skill_use" docs/SPEC.md` shows
  the catalog row, the paragraph and the global-hook list.

---

## Phase 2: `ctx.report_skill_use`

### Objective & Scope

- **In Scope:** the kernel `World.reportSkillUse` verb, the script verb `ctx.report_skill_use`, its feature ownership,
  spec text and tests.
- **Out of Scope (the agent must not implement these in this phase):** the plugin facade and `onEvent` (Phase 3); a
  harness `w.report_skill_use` (not planned); any library or content (Phase 4); validating that the actor knows the
  skill (never: the library decides).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`: `setSkill`, `removeSkill`, `skillInfo` (argument handling with
    `actorArg`, `skillArg`, `verb(...)`), `actorArg`/item argument helpers used by verbs such as `move_item`.
  - `core/src/main/kotlin/gups/core/world/Verbs.kt`: `World.setSkill` (feature check style).
  - `script/src/test/kotlin/gups/script/SkillVerbsTest.kt`.
  - SPEC §4.5 (verb table "Skills" group and verb semantics).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Verbs.kt` (`World.reportSkillUse`).
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt` (make `reportCastUse` reuse the same dispatch, or leave it
    calling `dispatchGlobal` directly; one dispatch path is preferred).
  - `script/src/main/kotlin/gups/script/Ctx.kt`, `script/src/main/kotlin/gups/script/Ownership.kt`,
    `script/src/main/kotlin/gups/script/DocExamples.kt` (if the API test asks for an example).
  - `script/src/test/kotlin/gups/script/SkillUseHookTest.kt` (extend).
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the API files.
- **Do Not Touch:** `plugin-api/`, `samples/`, `template-world/`, `Procs.kt`.

### Specification & Decision Record Changes

- **§3.10 `skills` row:** extend "the `set_skill`, `remove_skill` and `skill_info` verbs" to "the `set_skill`,
  `remove_skill`, `skill_info` and `report_skill_use` verbs".
- **§4.5 verb table, Skills group:** add `report_skill_use(actor, skill, target=None, item=None)`.
- **§4.5 verb semantics, after `skill_info`:** "`ctx.report_skill_use(actor, skill, target=None, item=None)` reports a
  use of `skill` (a label, absolute or relative to the calling package, or a skill view) by `actor`: it fires the global
  `on_skill_use` hook (§4.4) with `outcome` `"report"`, `ctx.target` and `ctx.item` as given and `ctx.targets` `[]`, and
  returns `None`. It is how a skill the engine doesn't see in use, such as one that only a rules export reads, can
  advance. The actor need not know the skill. An unknown skill, or an actor or target not in the world, is an error.
  Needs `skills`."
- **§4.5 field descriptions** (wherever `ctx` fields are listed): `outcome` (for `on_skill_use`), and `targets` also
  "the actors a cast landed on, for `on_skill_use`".

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Verbs.kt
/** Reports a use of [skill] by [actor] to the global on_skill_use hook, with outcome "report". */
fun World.reportSkillUse(actor: Actor, skill: SkillProto, target: Actor? = null, item: Item? = null)
```

```kotlin
// Ctx.kt
@StarlarkMethod(
  name = "report_skill_use",
  doc = "Reports a use of a skill to the global on_skill_use hook, with outcome 'report'. Needs the skills feature.",
  parameters = [
    Param(name = "actor", named = true, doc = "Actor who used the skill."),
    Param(name = "skill", named = true, doc = "Skill label, or the skill's view."),
    Param(name = "target", named = true, defaultValue = "None", doc = "Target actor, or None."),
    Param(name = "item", named = true, defaultValue = "None", doc = "Item involved, or None."),
  ],
  useStarlarkThread = true,
)
fun reportSkillUse(actor: Any, skill: Any, target: Any, item: Any, thread: StarlarkThread): Any
```

- **Algorithmic Steps & Invariants:**
  1. `World.reportSkillUse`: `check(Feature.SKILLS in features) { "Skills feature is disabled" }` (as `setSkill`);
     `require(actor.inWorld)`; then dispatch `ON_SKILL_USE` with `outcome = "report"`, `landed = emptyList()`, the given
     target and item exactly as given (a report's target may be the actor itself), and the actor as subject.
  2. `Ctx.reportSkillUse`: wrap in `verb("report_skill_use") { ... }`; resolve `actor` with `actorArg`, `skill` with
     `skillArg` and the `world.protos.skills` lookup exactly as `setSkill` does (unknown →
     `fail("report_skill_use(skill): unknown skill '<label>'")`); `target` with the optional-actor helper used by other
     verbs, `item` with the item argument helper; return `Starlark.NONE`.
  3. `Ownership.kt`: add `"ctx.report_skill_use"` to the existing `owns(Feature.SKILLS, "ctx.set_skill", ...)` line.
  4. Recursion is bounded by the existing cascade guard in `dispatchGlobal`; a hook that reports from inside
     `on_skill_use` is dropped with a `cascade` diagnostic past `CASCADE_MAX_DEPTH`. No extra guard.

### Test & Documentation Requirements

- Extend `SkillUseHookTest.kt`:
  - An item `on_use` calling `ctx.report_skill_use(ctx.actor, "//...:skill", item = ctx.item)` fires the global hook
    once with `"report"`, `ctx.item` set and `targets == []`, also when the actor doesn't know the skill.
  - A relative label resolves against the calling package; a skill view works.
  - An unknown label is a script error naming `report_skill_use(skill)`.
  - In a world without `skills`, calling it is an error naming the feature.
  - A global hook that reports from inside `on_skill_use` stops at the cascade limit with a `cascade` diagnostic.
- CHANGELOG: "`ctx.report_skill_use(actor, skill, target=None, item=None)` fires `on_skill_use` with `"report"`, for
  skills that only rules read, such as an artificer's."

### Exit Conditions

- [] `./gradlew :script:test --tests '*SkillUseHookTest*'` passes.
- [] API files regenerated if needed; `ApiDiffTest` passes and the diff names only `report_skill_use`, `outcome` and the
  `on_skill_use` hook.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 3: Plugin Seams

### Objective & Scope

- **In Scope:** `PluginWorld.reportSkillUse`; `on_skill_use` accepted by `PluginRegistry.onEvent`, with its `fields`;
  spec and `docs/PLUGINS.md` text.
- **Out of Scope (the agent must not implement these in this phase):** any sample plugin change; any new
  `PluginRegistry` member; robot-world content (Phase 4).

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt` (`PluginWorld.setSkill`, `PluginSkill`, `PluginItem`).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`: Milestone 41's `onEvent` name validation and the
    private `fieldsOf(w, hook, ctx)`.
  - The `EventListenerTest.kt` Milestone 41 added; the plugin-api facade tests for `setSkill`.
  - SPEC §4.15 (the `PluginWorld` facade list and the **Event listeners** bullet).
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt`, `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`.
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt` (and `PluginRegistryImpl` wherever the `onEvent` name
    list lives).
  - The plugin-api test files for the facade and event listeners (extend).
  - `docs/SPEC.md`, `docs/PLUGINS.md`, `docs/CHANGELOG.md`, the API files.
- **Do Not Touch:** `core/` other than calling the existing `World.reportSkillUse`; `script/`; `samples/`.

### Specification & Decision Record Changes

- **§4.15 `PluginWorld` facade:** beside `setSkill`/`removeSkill`, add "`reportSkillUse(actor, skill, target, item)`
  fires `on_skill_use` with `"report"`, as `ctx.report_skill_use` does (`skills` feature)."
- **§4.15 **Event listeners** bullet:** add `on_skill_use` to the list of names, and to the fields sentence: "`skill`
  (the label), `outcome`, `target` and `item` (or null) and `targets` (a list of actors) for `on_skill_use`". A listener
  for it registers in a world without `skills` and never runs.
- **`docs/PLUGINS.md`:** add `on_skill_use` wherever Milestone 41 lists the `onEvent` names.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Facade.kt, PluginWorld, after removeSkill
/** Reports a use of [skill] by [actor] to on_skill_use, with outcome "report". */
fun reportSkillUse(
  actor: PluginActor,
  skill: String,
  target: PluginActor? = null,
  item: PluginItem? = null,
)
```

- **Algorithmic Steps & Invariants:**
  1. Host `Facade.reportSkillUse`: mirror `setSkill`: `needs(world, Feature.SKILLS, "reportSkillUse")`, parse the label
     (invalid → `IllegalArgumentException("reportSkillUse(skill): invalid skill label '<skill>'")`), require it in
     `world.protos.skills`, resolve `actor`, `target` and `item` with the facade's existing `actorOf`/item helpers, then
     call `world.reportSkillUse`. Before adding a member without a default, `grep -rn ": PluginWorld" --include=*.kt`
     for hand-written fakes outside `plugin-api/src/main`; if any exist, give the interface member a default body that
     throws `UnsupportedOperationException`.
  2. `onEvent` name validation: add `on_skill_use` to the accepted names and the error message's list.
  3. `fieldsOf` for `HookName.ON_SKILL_USE`: `"skill"` to `ctx.skill!!.label.v`, `"outcome"` to `ctx.outcome`,
     `"target"` to the target as an actor facade or null, `"item"` to the item facade or null, `"targets"` to
     `ctx.landed.orEmpty().map { ActorFacade(it, w) }`. Use whichever facade constructors `fieldsOf` already uses.

### Test & Documentation Requirements

- Event-listener test: a plugin listening to `on_skill_use` sees one call per resolved cast with the right `outcome`,
  `skill` and `targets`, after the script global hook; in a world without `skills` the listener registers and never
  runs.
- Facade test: `reportSkillUse` fires a script global hook with `"report"`; unknown and invalid labels throw; without
  `skills` it throws naming the feature.
- `ApiSurfaceTest` (plugin-api) updated if it lists `PluginWorld` members.
- CHANGELOG: "Plugins: `PluginWorld.reportSkillUse`, and `onEvent("on_skill_use", ...)`."

### Exit Conditions

- [] `./gradlew :plugin-api:test` passes.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 4: The Robot-World Sample Library and Demo Routine

### Objective & Scope

- **In Scope:** a `lib` package in `samples/robot-world` with `skill_gain.star`; the `self_repair` routine and the
  `repair_chip` item in `zones/foundry`; Starlark tests; an end-to-end scenario.
- **Out of Scope (the agent must not implement these in this phase):** any template-world change (the template world
  keeps trainers only); any engine change; a GMCP package or command for skill progress; changes to the robot rules'
  `skill_check`/`skill_power`.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/lib/trainer.star` and `template-world/lib/package.star`: library style (header comment, constants at
    the top, `_absolute`-style checks, `ctx.get_attr`/`ctx.set_attr` in the lib's own namespace, `global_hook`).
  - `template-world/zones/tutorial/trainer_test.star`: Starlark test style.
  - `template-world/zones/tutorial/items.star` (the `charges = 1` potion) and a zone item with `on_use`.
  - `samples/robot-world/MODULE.gups`, `rules/combat.star` (`skill_check`, `skill_power`), `zones/foundry/items.star`,
    `rooms.star`, `foundry_test.star`.
  - `AGENTS.md` "End-to-end checks".
- **Files to Modify / Create:**
  - `samples/robot-world/lib/package.star` (new, `kind = "lib"`).
  - `samples/robot-world/lib/skill_gain.star` (new).
  - `samples/robot-world/zones/foundry/items.star` (`self_repair`, `repair_chip`).
  - `samples/robot-world/zones/foundry/rooms.star` (the chip at `:gate`).
  - `samples/robot-world/zones/foundry/skill_gain_test.star` (new).
- **Do Not Touch:** `core/`, `script/`, `plugin-api/`, `template-world/`.

### Specification & Decision Record Changes

- **§4.9, new paragraph after the `//lib:trainer.star` paragraph:** "**The robot world's `//lib:skill_gain.star`** is a
  sample of advancement through use for a world without `leveling`. Its global `on_skill_use` hook gives a player points
  toward the next point of a skill they already know, at most once per skill every `USE_WINDOW_PULSES` (40): `POINTS`
  per outcome (3 for `"fail"`, 2 otherwise). When the points reach `_points_needed(skill, prof)` (by default `1 + prof *
  prof // 50`), it spends them and raises the skill one point with `ctx.set_skill`, once for each cost the points can
  pay, so `learn` restrictions still cap it. Progress is kept in the library's own `lib` namespace. Mobs and skills at
  100 are skipped, and nothing is random. The constants and the curve are Starlark for a world to tune."

### Implementation Blueprint

- **`samples/robot-world/lib/package.star`:**

```python
# Shared libraries for the robot world. lib is a privileged package kind: it may register
# global hooks, such as skill_gain.star's on_skill_use.
package(
    api_version = 1,
    kind = "lib",
    title = "Shared libraries",
)
```

- **`samples/robot-world/lib/skill_gain.star`** (structure; write the body from these steps):

```python
# Skill advancement through use, for a world without character levels. A sample: copy it and
# tune POINTS, USE_WINDOW_PULSES and _points_needed to your world.
#
# Every routine a player invokes reports a use (on_skill_use). A counted use adds POINTS for its
# outcome toward the skill's next point; at most one use per skill counts per USE_WINDOW_PULSES,
# so spamming a cheap routine in a safe room gains little. Raising a skill goes through
# ctx.set_skill, so learn restrictions still cap it. Scripts report uses the engine can't see
# (a skill only rules read) with ctx.report_skill_use.

POINTS = {"fail": 3, "resist": 2, "success": 2, "report": 2}
USE_WINDOW_PULSES = 40

def _points_needed(skill, prof):
    # Points to go from prof to prof + 1. Edit this to reshape advancement;
    # `skill` is the skill view, so tags or kind can make some skills harder.
    return 1 + prof * prof // 50

def _on_skill_use(ctx):
    ...

global_hook("on_skill_use", _on_skill_use)
```

- **Algorithmic Steps & Invariants (`_on_skill_use`):**
  1. `a = ctx.actor`; return if `not a.is_player`.
  2. `label = ctx.skill.label`; `prof = a.skills.get(label)`; return if `prof == None` (unknown skills never gain, so a
     report can't teach) or `prof >= 100`.
  3. Throttle: `nexts = ctx.get_attr(a, "skill_gain_next", {})`; return if `nexts.get(label, 0) > ctx.pulse`. Otherwise
     store `nexts[label] = ctx.pulse + USE_WINDOW_PULSES` (copy the dict before mutating if attribute dicts are frozen,
     as `trainer.star` does) and `ctx.set_attr` it.
  4. Points: `progress = ctx.get_attr(a, "skill_progress", {})`; `points = progress.get(label, 0) +
     POINTS.get(ctx.outcome, 0)`.
  5. While `prof < 100` and `points >= _points_needed(ctx.skill, prof)`: `refusal = ctx.set_skill(a, label, prof + 1)`;
     if `refusal != None`, break (keep the points, tell nothing); else subtract the cost, `prof += 1`, and tell the
     actor "You feel more skilled at <name>. (<prof>%)" with `ctx.send` (or the verb the trainer library uses to tell a
     player), using `ctx.skill.name`.
  6. Store `progress[label] = points` with `ctx.set_attr`. Points left over at 100 are kept and harmless.
  7. Invariants: deterministic (no `ctx.rng`); at most one counted use per skill per window; all state in the lib
     namespace (`skill_gain_next`, `skill_progress`).
- **`self_repair`** in `zones/foundry/items.star`, beside `overload`:

```python
skill("self_repair",
    kind = "routine",
    cost = vitals(power = 5),
    lag = 4,
    target = "self",
    effect = heal(dice = "1d6", vital = "integrity"),
)
```

Check the robot rules' `skill_check`, `cast_allowed` and `interrupt_check` accept `kind = "routine"`; if they branch on
`kind`, use `"spell"` like the other two. - **`repair_chip`**: an item named "a repair chip" (keywords `chip`,
`repair`), `charges = 1`, with an `on_use` hook: if `ctx.actor.skills` already has `":self_repair"`'s absolute label,
send "You already run that routine." and return; otherwise `ctx.set_skill(ctx.actor, ":self_repair", 1)` and, on `None`,
send "The chip installs self_repair. (1%)". Put one at `:gate`, using the same mechanism the template uses for ground
items in a room (a room `on_reset`/`on_spawn` hook calling `ctx.spawn(":repair_chip", ctx.self)` when none is there, as
`template-world/zones/tutorial/rooms.star` does). Make sure the chip is consumed on use (check how `charges = 1`
empties: the template potion's default is `destroy`).

### Test & Documentation Requirements

- `samples/robot-world/zones/foundry/skill_gain_test.star`:
  - `use chip` installs `self_repair` at 1; a second use (new chip spawned by the test) says "You already run that
    routine." and leaves it at its value.
  - One `invoke self_repair` at proficiency 1 raises it to 3 with 0 points left: every point from 0 to 7 costs 1, so a
    success's 2 points pay two costs (1 → 2 → 3).
  - Two invocations inside the same 40-pulse window count once; after `w.run(pulses = 40)` the next one counts.
  - At proficiency 50 (`w.set_skill`), uses add 2 points each and the skill reaches 51 after exactly 26 counted uses
    (`_points_needed(50) = 51`).
  - A forced `skill_check` failure adds 3 points (`w.force_rng` or a proficiency low enough to fail; read
    `skill_progress` through a test helper command or by checking the use count needed).
  - A mob's cast changes nothing; an unknown skill reported with `ctx.report_skill_use` (from a test item's `on_use`)
    doesn't teach it.
  - Proficiency never passes 100.
- Run the existing robot-world tests (`foundry_test.star`), lint and fuzz: they must still pass with the new package.
- End-to-end, per `AGENTS.md`: write `local-world-test/scenario.star` (spawn a player at `:gate`, `get chip`, `use
  chip`, invoke `self_repair` with `w.run(pulses = 40)` between, print the proficiency), run it with
  `cli/build/install/gups/bin/gups scenario --world samples/robot-world --package //zones/foundry
  local-world-test/scenario.star`, read the transcript, then delete `local-world-test/`.
- CHANGELOG: "The robot world has a sample `//lib:skill_gain.star`: deterministic advancement through use with a tunable
  curve, and a `self_repair` routine learned from a repair chip."

### Exit Conditions

- [] `./gradlew :cli:test` passes (it lints, tests and fuzzes the robot world).
- [] The scenario transcript shows the chip install and proficiency rising across windows.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 5: Finalization & Backlog Update

- **§7.4:** add a row after the highest milestone row: `| **M43** | Skill advancement through use: the global
  on_skill_use hook (fired by every resolved cast with "fail", "resist" or "success"), ctx.report_skill_use and
  PluginWorld.reportSkillUse, onEvent("on_skill_use"); the robot world's //lib:skill_gain.star sample with deterministic
  points, a use window and a tunable curve, and its self_repair routine and repair chip (§3.10, §4.3, §4.4, §4.5, §4.9,
  §4.15). |` (format the cell the way neighboring rows are formatted, with backticks).
- **`docs/DESIGN_NEEDED.md`:** under `### Skill-based advancement (worlds without character levels)`, add a first line
  `**Status: completed in Milestone 43.**` followed by one sentence: "The engine fires a global `on_skill_use` hook for
  every resolved cast and for `ctx.report_skill_use`; gain policy is Starlark, sampled by the robot world's
  `//lib:skill_gain.star`; procs don't count." Do not delete the entry.
- Grep SPEC for the old global-hook lists (`only on_level_change, on_encumbrance_change`) and the `onEvent` name list,
  and confirm each now includes `on_skill_use`.
- Run `./gradlew markdownFormat`, then the full suite `./gradlew check` from a clean build.

### Exit Conditions

- [] `docs/DESIGN_NEEDED.md` marks the entry completed without deleting it.
- [] SPEC §3.10, §4.3, §4.4, §4.5, §4.9, §4.15 and §7.4 describe exactly what the code does.
- [] `./gradlew check` passes with zero warnings.
