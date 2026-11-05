# Milestone 33: Acquiring Skills

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, section "Combat and skills", heading `### Acquiring skills`.

- **Design Summary:** the engine gets the primitives that let scripts set skills, a global level hook, and a way for
  players to see their skills. The practice flow itself is a template library, `//lib:trainer.star`, built the same way
  as `//lib:shop.star`.

  - **Script verbs and query (`skills` feature):**
    - `ctx.set_skill(actor, skill, proficiency)` returns `None` or the refusal message. It wraps the existing
      `World.setSkill` (`core/src/main/kotlin/gups/core/world/Verbs.kt:262`), so it runs the `learn` restriction and
      checks that the proficiency is in 0..100.
    - `ctx.remove_skill(actor, skill)` returns a bool, whether the actor knew the skill. It wraps `World.removeSkill`.
    - `ctx.skill_info(label)` returns the existing read-only skill view (`SkillView`: `label`, `name`, `kind`, `tags`,
      …) for a skill label, or `None` when no such skill is loaded. Today a script can only get a skill view as
      `ctx.skill` inside a cast, so the trainer library needs this to show names.
    - In all three, `skill`/`label` is a label string (absolute, or relative to the calling package) or a skill view.
    - The harness gets matching `w.set_skill` and `w.remove_skill`.
    - The verbs are not limited to privileged packages, the same as `award_xp`.
    - `learn` runs on every set, including raising a skill the actor already knows. This is how a class plugin caps
      proficiency, for example refusing warriors above 30 in a spell.
  - **`global_hook(name, fn)`:**
    - Privileged (`lib`, `rules`) packages may declare it; a zone declaring it is a load error. It mirrors
      `global_command`.
    - The only accepted name for now is `on_level_change`. Any other name fails the load, with the message
      "global_hook(): '<name>' can't be a global hook; only on_level_change can".
    - It fires once per level step, for every actor, players and mobs, after the local `on_level_change` dispatch for
      that step. It gets the same ctx fields (`actor`, `old_level`, `new_level`), with `ctx.self` set to the actor.
    - Order: package path ascending, then registration order within a package.
    - Script errors, limits and quarantine work as for any hook, keyed to the registering package.
    - **Feature off:** in a world without `leveling`, `global_hook("on_level_change", fn)` **loads and never fires**.
      This is a stated exception to the §3.10 rule that a hook of a feature that is off fails the load, so a shared
      library can serve worlds with and without `leveling`.
  - **Seeing skills (`skills` feature):**
    - A fast-lane engine command, **`skills`** (category `SKILLS`, renameable), lists the actor's skills sorted by skill
      name as `<name> (<kind>) <proficiency>%`. With none it says "You don't know any skills.". The lines are engine
      messages: `skills.header`, `skills.line`, `skills.none`.
    - A GMCP state package, **`Char.Skills`**, sends `{"skills": [{"label", "name", "kind", "proficiency"}]}`, sorted by
      name. It is recomputed in phase 7 and sent when it changes, like `Char.Traits`, and only when `skills` is on.
    - The web client gets a Skills panel.
  - **`//lib:trainer.star`** (template world):
    - `trainer.define(id, teaches)`, `trainer.teach(label, up_to, gain, start=None)` and `trainer.open(ctx, keeper,
      definition)` (called from the keeper's `on_spawn`).
    - `trainer.practices(ctx, actor)` returns the balance and `trainer.grant_practices(ctx, actor, n)` changes it.
    - A `PRACTICES_PER_LEVEL = 2` constant.
    - The library declares `global_hook("on_level_change", _on_level_change)` and `global_command("practice", ..., lane
      = "tick")`.
    - **Balance:** one int, `practices`, in the library's own `lib` namespace on the actor. It starts at 0.
    - **Level changes:** the global hook adds `PRACTICES_PER_LEVEL` for each level gained and subtracts it for each
      level lost. The balance **may go negative**, so losing and regaining a level is net zero and dying can't be used
      to farm points.
    - **Without `skills`**, `practice` replies "There's nothing to practice here." and touches no skill state, so a
      world that turns `skills` off doesn't pile script errors onto `//lib`.
    - **`practice` with no argument** shows the balance ("You have N practice sessions.", or "You owe N practice
      sessions."). With a trainer in the room, it also lists what that trainer teaches: the skill name (from
      `ctx.skill_info`), your current proficiency, and the trainer's `up_to`.
    - **`practice <skill>`:**
      1. Find the serving keeper and refuse as the shop does: no trainer here; trainer not `ok`, stunned, or fighting
         (only while `ctx.has_feature("combat")`).
      2. Match `<skill>` against the trainer's lines by prefix of the skill's `name`; with no match, "<Keeper> can't
         teach you that.".
      3. `current` is the actor's proficiency, or `None` when the skill is unknown. If `current >= up_to`, refuse:
         "You've learned all <keeper> can teach you about <skill>.".
      4. The default `gain` is the line's `start` (falling back to `gain`) when the skill is unknown, otherwise the
         line's `gain`. The default `cost` is 1.
      5. Ask the keeper's custom hook `practice_offer(ctx, customer, skill, current, gain, cost, refusal)` through
         `ctx.call_hook`. A `None` return keeps the defaults. A string refuses. A dict may replace `gain` and/or `cost`
         (ints, `gain >= 1`, `cost >= 0`). Anything else is printed and ignored, as `shop._ask` does.
      6. Refuse if `cost >= 1` and the balance is below `cost`: "You need N practice sessions; you have M." (or "you owe
         M").
      7. `new = min((current or 0) + gain, up_to, 100)`. Call `ctx.set_skill(actor, label, new)`. A refusal string is
         sent to the player and **spends nothing**.
      8. On success, subtract `cost` and tell the player "You practice <skill> with <keeper>. (<old>% → <new>%)" (or
         "You learn <skill> from <keeper>. (<new>%)" for a new skill). Onlookers see "<actor> practices with <keeper>.".
    - **Cap:** the effective cap is `min(up_to, 100)`, and `learn` restrictions may refuse anything below it.
  - **Shipped content:** a `template-world/skills/arcane/` package with two spells, `spark` (an `enemy` damage spell)
    and `mend` (a `self` heal), and a tutorial `mentor` mob that teaches `spark` to 50 and `mend` to 40, with a
    commented `practice_offer` example. The robot world does not change.
  - **Out of scope:** gaining skill through use (the "Skill-based advancement" entry), a plugin (Kotlin) counterpart to
    `global_hook`, any other global hook name, a rules export for practice, and point-buy at creation.
- **Specification Impact:**
  - §3.10: the feature table (`skills` row: the verbs, `skill_info`, `skills` command, `Char.Skills`; `leveling` row:
    global `on_level_change` hooks) and the global-hook exception in "A feature that is off doesn't exist";
  - §4.4: the `on_level_change` paragraph (global hooks fire after the local ones);
  - §4.5: verb table (a "Skills" group), the Queries row (`skill_info`), and verb semantics;
  - §4.9: the `skills` command, a new "Global hooks" paragraph after "Global commands", and a `//lib:trainer.star`
    paragraph after the shop library's;
  - §4.10: the `Char.Skills` package row;
  - §4.12: `w.set_skill` and `w.remove_skill` in the `w` row;
  - §4.18: the `skills.*` messages, if the catalog lists messages by group;
  - §4.19: the `learn` restriction row;
  - §7.4: a new M33 row;
  - `docs/CHANGELOG.md`; a new template tutorial `09_trainers.md`; the regenerated API files.
- **Decision Record Updates:** None. `docs/DECISIONS.md` holds no live records, and none touch skills. Do not add new
  `D-xxx` entries for anything this document settles: the spec is edited directly as approved here.
- **Ordering:** `docs/MILESTONE_32.md` (progressive encumbrance) comes first. If it hasn't landed, its §7.4 M32 row
  won't be there yet; add the M33 row after M31 anyway, and don't touch the encumbrance spec text.

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first and see them fail.
   - No spec section numbers or decision ids in comments, Kotlin or Starlark.
   - All Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`. The engine is
   unreleased, so no migrations or back-compat shims are needed.
3. Add CHANGELOG bullets to a new `### Acquiring skills` subsection at the top of `## api_version 1 (unreleased)`, above
   whatever subsection is first there now. Create it in the first phase that adds a bullet, and have each later phase
   append to it.
4. In every phase that changes the script API or template-world content (Phases 1, 2, 4, 5 and 6), regenerate all three
   API files before the phase's final `./gradlew check` if any golden or API test fails:
   - Run `./gradlew :cli:test -Dgups.updateGolden=true`. This rewrites `cli/src/test/resources/api.golden.json` and the
     `cli/src/test/resources/docs-golden/` files.
   - Run `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout. This rewrites `docs/api/api-1.json`.
   - Read the diff. Only the symbols, commands and content that phase names may be new or changed. `ApiDiffTest` must
     then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing entry.
5. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation.
6. Never read or touch `local-world/`. Put scratch files in `local-world-test/` and delete it when you are done. Do not
   run `gups` commands directly against `template-world/`; copy it first (`cp -R template-world
   local-world-test/world`). The only exception is `gups scenario --world template-world`, which `AGENTS.md` allows.
7. Every phase must leave every existing test passing without editing its expectations, unless the phase says otherwise.
   A test that lists commands, GMCP packages or API symbols may need its expected list extended by exactly the names the
   phase adds; nothing else may change. Before adding the names `skills`, `practice` and `Char.Skills`, run `rg -n
   '"skills"|"practice"|Char\.Skills' samples template-world */src/test` and check for a clash (a command, an exit, or a
   test typing a prefix that becomes ambiguous). If one turns up, stop and report it.

---

## Phase 1: `ctx.set_skill`, `ctx.remove_skill`, `ctx.skill_info` and the harness verbs

### Objective & Scope

- **In Scope:**
  - `ctx.set_skill`, `ctx.remove_skill`, `ctx.skill_info`;
  - `w.set_skill` and `w.remove_skill`;
  - their ownership by `skills`;
  - the spec text, including `learn` covering the script verb and every raise.
- **Out of Scope (do not implement these in this phase):** the `skills` command, `Char.Skills`, `global_hook`, the
  trainer library, any change to `World.setSkill`'s behavior.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Verbs.kt:262-288` (`setSkill`, `removeSkill`);
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt:584-605` (the facade's argument checks, to mirror);
  - `script/src/main/kotlin/gups/script/Ctx.kt:929-947` (`award_xp`, the verb pattern) and `:276` (how `ctx.skill`
    builds a `SkillView`);
  - `script/src/main/kotlin/gups/script/Views.kt:1011` (`SkillView`);
  - `harness/src/main/kotlin/gups/harness/Handles.kt:572-600` (`grant_trait`, `remove_trait`);
  - `script/src/test/kotlin/gups/script/LevelingVerbsTest.kt` (test layout to mirror).
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`;
  - `script/src/main/kotlin/gups/script/Ownership.kt`;
  - `harness/src/main/kotlin/gups/harness/Handles.kt`;
  - `script/src/main/kotlin/gups/script/DocExamples.kt` (only if the API tests ask for examples);
  - new `script/src/test/kotlin/gups/script/SkillVerbsTest.kt`; a harness test next to the existing `grant_trait` tests
    (search `harness/src/test` for `grant_trait`);
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the API files.
- **Do Not Touch:** `core/` (the world functions already do the work), `plugin-api/`, any world.

### Specification & Decision Record Changes

- §4.5 verb table: add a row after "Leveling":

  ```markdown
  | Skills | `set_skill(actor, skill, proficiency)`, `remove_skill(actor, skill)` |
  ```
- §4.5 verb table, "Queries" row: add `skill_info(label) -> skill | None`.
- §4.5 verb semantics: add a bullet after `summon`:

  ```markdown
  - **`set_skill`, `remove_skill` and `skill_info`:** `set_skill` gives `actor` the skill `skill` (a label or skill
    view) at `proficiency` (0 to 100), or changes the proficiency it has. It first asks the `learn` restrictions
    (§4.19) and returns the refusal message, changing nothing, or `None` once the skill is set. `remove_skill` forgets
    the skill and returns whether the actor knew it; it asks no restriction. An unknown skill or an out-of-range
    proficiency is a script error. `skill_info(label)` returns the skill's view (as `ctx.skill` in a cast), or `None`
    when no loaded skill has that label.
  ```
- §4.5 "Features" bullet: add "`set_skill`, `remove_skill` and `skill_info` need `skills`".
- §4.12 `w` row: add `set_skill(actor, skill, proficiency)` and `remove_skill(actor, skill)` after `remove_trait`.
- §4.19 restriction table, `learn` row, "Checked" column: replace "a plugin setting a skill on an actor (§4.15)" with
  "every setting of a skill on an actor: a plugin's `setSkill` (§4.15), `ctx.set_skill` and `w.set_skill`, whether the
  actor learns the skill or already knows it". The Subject stays "the skill and the proficiency".
- §3.10 table, `skills` row: add "the `set_skill`, `remove_skill` and `skill_info` verbs" after `act_cast`.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Ctx.kt
@StarlarkMethod(name = "set_skill", doc = "...", parameters = [
  Param(name = "actor", named = true), Param(name = "skill", named = true),
  Param(name = "proficiency", named = true)])
fun setSkill(actor: Any, skill: Any, proficiency: Any): Any   // None or refusal String

@StarlarkMethod(name = "remove_skill", ...)
fun removeSkill(actor: Any, skill: Any): Any                  // Boolean

@StarlarkMethod(name = "skill_info", ...)
fun skillInfo(label: Any): Any                                // SkillView or None
```

- **Algorithmic Steps & Invariants:**
  1. Wrap `set_skill` and `remove_skill` in `verb("...") { ... }` like `award_xp`. `skill_info` is a query: mirror how
     `ctx.has_feature` or `ctx.find` is declared, not `verb`, if those differ. Resolve the actor with `actorArg`.
  2. A shared private helper resolves the skill argument: a `SkillView` gives its `skill.label`; a string is parsed and
     resolved relative to the calling package the way other label arguments in `Ctx.kt` are (find the helper used by
     `give` or `transform` and reuse it). For `set_skill`/`remove_skill` a label with no loaded skill fails with
     "set_skill(skill): unknown skill '<label>'"; for `skill_info` it returns `None` (a malformed label is still an
     error).
  3. Check `proficiency` is an int in 0..100, failing with "set_skill(proficiency): must be 0 to 100, got N".
  4. Call `world.setSkill(a, label, n)`. Return the refusal string, or `Starlark.NONE`.
  5. `remove_skill` returns `world.removeSkill(a, label)` as a Starlark bool.
  6. `skill_info` returns `SkillView(proto, world.schema)`, built as at `Ctx.kt:276`.
  7. Feature gating: add `owns(Feature.SKILLS, "ctx.set_skill", "ctx.remove_skill", "ctx.skill_info")` and
     `owns(Feature.SKILLS, "w.set_skill", "w.remove_skill")` in `Ownership.kt`, next to the existing `skills` lines. The
     harness verbs call the same feature check `award_xp` uses (`requireFeature`).
  8. The harness verbs mirror `grant_trait`: same argument handling, same return conventions.

### Test & Documentation Requirements

- `SkillVerbsTest.kt`:
  - setting a new skill and raising a known one changes `actor.skills`, and the actor is marked dirty;
  - a skill view works as the `skill` argument;
  - a `learn` restriction refusing returns its message and leaves the proficiency unchanged, for both a new and a known
    skill;
  - an unknown skill label, and a proficiency of -1 or 101, are script errors naming the argument;
  - `remove_skill` returns `True` and then `False`;
  - `skill_info` returns a view whose `name` and `kind` match, and `None` for an unknown label;
  - in a world without `skills`, each verb gives the feature error other feature-owned verbs give (mirror
    `FeatureScriptTest.kt`).
- A harness test for `w.set_skill` and `w.remove_skill`.
- CHANGELOG: "`ctx.set_skill`, `ctx.remove_skill` and `ctx.skill_info`, and the harness's `w.set_skill` and
  `w.remove_skill`. Setting a skill runs the `learn` restrictions, also when it raises a skill the actor knows."

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] The new tests pass, and the API diff shows only the five new symbols.
- [] §4.5, §4.12, §4.19 and §3.10 carry the text above.

---

## Phase 2: The `skills` command and `Char.Skills`

### Objective & Scope

- **In Scope:**
  - the `skills` command and its three messages;
  - the `Char.Skills` GMCP state package;
  - spec text.
- **Out of Scope (do not implement these in this phase):** the web client panel (Phase 3), `global_hook`, the trainer
  library.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt:68-74` and `:351` (`score`, a fast command pattern);
  - `core/src/main/kotlin/gups/core/world/KernelMessages.kt:60-70` (`SCORE_*` messages);
  - `core/src/main/kotlin/gups/core/world/Messages.kt` (the `msg(...)` signature);
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`SkillProto`: `label`, `name`, `kind`).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt` (`SkillMsg`, `SkillCommands.all()`);
  - `core/src/main/kotlin/gups/core/world/Gmcp.kt` (`STATE_PACKAGES`, `gmcpDeltas`, `gmcpPayload`);
  - tests: `core/src/test/kotlin/gups/core/world/GmcpTest.kt` and a new
    `core/src/test/kotlin/gups/core/world/SkillsCommandTest.kt`;
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the API files (the message catalog and command list are in the docs golden).
- **Do Not Touch:** `webclient/`, `script/`, `harness/`.

### Specification & Decision Record Changes

- §4.9, the player command list (find where `score` is listed): add `skills` (`skills` feature). After the `score`
  bullet, add:

  ```markdown
  - **`skills`** (`skills` feature) lists the skills the character knows, sorted by name, each with its kind and
    proficiency, or says it knows none. The lines are engine messages (§4.18). Clients read the same list from
    `Char.Skills` (§4.10).
  ```
- §4.10 GMCP table: add a row after `Char.Traits`:

  ```markdown
  | `Char.Skills` | `{"skills": [{"label", "name", "kind", "proficiency"}]}`, sorted by name; only with `skills` | When changed |
  ```
- §4.9 `score` bullet, last sentence: add `Char.Skills` to the packages clients read.
- §3.10 table, `skills` row: add "the `skills` command, `Char.Skills`".
- §4.18: if the catalog lists message keys by group, add `skills.header`, `skills.line`, `skills.none` to the `skills`
  group.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// SkillMsg
val SKILLS_HEADER = msg("skills.header", "You know these skills:")
val SKILLS_LINE = msg("skills.line", "  {skill} ({kind}) {proficiency}%", "skill,kind,proficiency", "skill")
val SKILLS_NONE = msg("skills.none", "You don't know any skills.")
```

- **Algorithmic Steps & Invariants:**
  1. Add `FastCommand("skills", category = CommandCategory.SKILLS, help = "List the skills you know and how
     well.\nUsage: skills")` to `SkillCommands.all()`. Check the `msg(...)` signature in `Messages.kt` before copying
     the argument shape above; mirror how `cast.begin.self` marks `skill` for highlighting.
  2. The command reads `c.actor.skills`. Skip labels whose prototype is missing from `world.protos.skills`. Sort by the
     prototype's `name`, then label. With none, tell `SKILLS_NONE`; otherwise `SKILLS_HEADER` and one `SKILLS_LINE`
     each.
  3. Add `"Char.Skills"` to `STATE_PACKAGES` after `"Char.Traits"`. In `gmcpDeltas`, skip it when `Feature.SKILLS !in
     features`, the way `Room.Bands` skips without `TRACK`.
  4. `gmcpPayload(p, "Char.Skills")` builds `mapOf("skills" to list)`, each entry `mapOf("label" to label.v, "name" to
     …, "kind" to …, "proficiency" to Int)`, in the same order as the command.
  5. `ENGINE_GMCP_PACKAGES` picks it up from `STATE_PACKAGES` automatically.

### Test & Documentation Requirements

- `SkillsCommandTest.kt`: sorted output with two skills; the none message; "Huh?" in a world without `skills`.
- `GmcpTest.kt`: a client supporting `Char` gets `Char.Skills` at its first delta; a `setSkill` change sends a new
  payload at the next phase 7; an unchanged list sends nothing; no package without `skills`.
- CHANGELOG: "The `skills` command and the `Char.Skills` GMCP package."

### Exit Conditions

- [] `./gradlew check` passes with zero warnings. The API diff shows only the new command, messages and package.
- [] The new tests pass.

---

## Phase 3: Web client Skills panel

### Objective & Scope

- **In Scope:** a Skills panel drawn from `Char.Skills`, hidden when empty.
- **Out of Scope (do not implement these in this phase):** any server change.

### Context Working Set

- **Read / Reference Only:**
  - `webclient/src/main/resources/gups/webclient/panels.js:167` (`drawTraits`);
  - `cli/src/test/kotlin/gups/cli/WebClientTest.kt`.
- **Files to Modify / Create:**
  - `webclient/src/main/resources/gups/webclient/index.html` (a `skills-panel` with a `skills-list`, after
    `traits-panel` at line 44);
  - `webclient/src/main/resources/gups/webclient/panels.js` (`export function drawSkills(panel, list, data)`);
  - `webclient/src/main/resources/gups/webclient/app.js` (the import at line 8, and `case "Char.Skills"` after
    `Char.Traits` at line 81);
  - `webclient/src/main/resources/gups/webclient/style.css` (reuse the `.traits-*` rules for `.skills-*`);
  - `cli/src/test/kotlin/gups/cli/WebClientTest.kt`, if it asserts on panels.
- **Do Not Touch:** Kotlin outside the test.

### Specification & Decision Record Changes

- None, unless §4.10 or §2.2 lists the web client's panels by name; if so, add Skills there.

### Implementation Blueprint

1. `drawSkills` mirrors `drawTraits`: clear the list, one `<li>` per skill showing `name` and `proficiency%`, with
   `kind` as the title attribute; set `panel.hidden = data.skills.length === 0`. Build the text with `textContent`,
   never `innerHTML`.
2. Leave `Core.Supports.Set` alone: `"Char 1"` already covers `Char.Skills`.

### Test & Documentation Requirements

- Extend `WebClientTest.kt` to check the served page has `skills-panel`, if it already checks other panel ids.
- The panel can't be exercised by hand until a player can gain a skill in play; Phase 6 covers that.

### Exit Conditions

- [] `./gradlew check` passes.

---

## Phase 4: `global_hook`

### Objective & Scope

- **In Scope:**
  - the `global_hook` declaration for privileged packages, accepting only `on_level_change`;
  - firing it after each level step's local dispatch;
  - the feature-off exception;
  - spec text.
- **Out of Scope (do not implement these in this phase):** other hook names, plugin listeners, the trainer library.

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Declarations.kt:1652-1674` (`global_command`);
  - `script/src/main/kotlin/gups/script/Host.kt:166-200` (`install`) and `:345-371` (`runCommand`, the `call` pattern);
  - `script/src/main/kotlin/gups/script/ApiModel.kt:137` (hook feature refusal) and `DocExamples.kt:41`;
  - `core/src/main/kotlin/gups/core/world/Hooks.kt:238-380` (`ScriptHost`, `dispatch`, `invokeOn`, `withCascadeGuard`);
  - `script/src/test/kotlin/gups/script/LevelChangeHookTest.kt`.
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Packages.kt` (a `GlobalHookDecl` and list, next to `GlobalCommandDecl` at line
    124 and `commandList` at 163);
  - `script/src/main/kotlin/gups/script/Declarations.kt`;
  - `script/src/main/kotlin/gups/script/Host.kt`;
  - `script/src/main/kotlin/gups/script/DocExamples.kt` (`LIB_SYMBOLS` and an example);
  - `core/src/main/kotlin/gups/core/world/Hooks.kt` (`ScriptHost.invokeGlobal`, `World.dispatchGlobal`);
  - `core/src/main/kotlin/gups/core/world/Leveling.kt` (the two `dispatch(HookName.ON_LEVEL_CHANGE, ...)` sites, near
    lines 127 and 240);
  - new `script/src/test/kotlin/gups/script/GlobalHookTest.kt`;
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the API files.
- **Do Not Touch:** `template-world/`, `plugin-api/`, the death path in `Combat.kt` (it already reaches the level step).

### Specification & Decision Record Changes

- §4.9, after the "Global commands" paragraph:

  ```markdown
  **Global hooks:** Privileged packages may register a hook that fires for every actor, wherever it is:
  `global_hook("on_level_change", fn)`. `on_level_change` is the only hook that can be global. It fires once per level
  step for every actor, players and mobs, after that step's `on_level_change` hooks on mobs, rooms and items, with the
  same ctx fields; `ctx.self` is the actor. Global hooks run in package label order, then in the order they were
  registered. In a world without `leveling` the declaration loads and the hook never fires, so a library can serve
  worlds with and without it.
  ```
- §4.4, the `on_level_change` paragraph: append "Global `on_level_change` hooks (§4.9) fire after these, once per
  step.".
- §3.10, "A feature that is off doesn't exist": after the bullet "Its declarations, declaration arguments, hooks,
  behaviors and package kinds fail their package's load…", add "The exception is `global_hook` (§4.9): one naming a hook
  of a feature that is off loads and never fires.".
- §3.10 table, `leveling` row: add "global `on_level_change` hooks".

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Packages.kt
data class GlobalHookDecl(val hook: String, val fn: HookRef, val pkg: String)
// in the package state, next to commandList / globalCommands:
internal val hookList = mutableListOf<GlobalHookDecl>()
val globalHooks: List<GlobalHookDecl> get() = hookList

// Hooks.kt, in ScriptHost
/** Runs every global [hook] registered by loaded packages, with [ctx]. */
fun invokeGlobal(world: World, hook: HookName, ctx: HookContext) = Unit

// Hooks.kt
internal fun World.dispatchGlobal(hook: HookName, ctx: HookContext)
```

- **Algorithmic Steps & Invariants:**
  1. **Declaration:** `global_hook(name, fn)` mirrors `globalCommand`: the same `contentCtx`, spec binding and
     privileged check, with the message "global_hook(): only lib and rules packages may register global hooks".
     - Accept only `name == "on_level_change"`; otherwise fail with the message in the Overview.
     - Do **not** run the `FeatureOwnership` hook refusal for it; that is the feature-off exception.
     - The same `(name, fn)` pair twice in one package is an error ("already registered").
     - Append a `GlobalHookDecl`.
  2. **Host:** `invokeGlobal` returns at once unless `Feature.LEVELING in world.features`.
     - Otherwise iterate `scripts.packages.values` sorted by path, and each package's `globalHooks` whose `hook ==
       hook.scriptName`, in list order.
     - Read the live packages on each call, so a reload's changes apply at once.
     - For each, build `Site(pkg, null, "global_hook '${hook.scriptName}'", vetoable = false, actor.id)` and call
       `call(world, site, ref, { Ctx(it, actor, ctx) })`, where `actor = ctx.actor!!` is also the `Ctx` owner, so
       `ctx.self` is the actor. Check `Site`'s and `Ctx`'s constructors against `runCommand` before copying.
     - Failures follow the same path as a hook: `call` records the error and counts it toward quarantine. `Skipped` is
       ignored.
  3. **Core:** `World.dispatchGlobal` wraps `scripts.invokeGlobal` in `withCascadeGuard(hook.scriptName)` and a
     try/catch that records a `script` diagnostic, mirroring `invokeOn`.
  4. **Leveling:** after each of the two `dispatch(HookName.ON_LEVEL_CHANGE, ...)` calls in `Leveling.kt`, call
     `dispatchGlobal(HookName.ON_LEVEL_CHANGE, sameCtx)`. Keep the rest of the step order unchanged.
  5. Add `"global_hook"` to `LIB_SYMBOLS` in `DocExamples.kt`, with an example modeled on the `global_command` one.

### Test & Documentation Requirements

- `GlobalHookTest.kt` (mirror `LevelChangeHookTest.kt`'s setup):
  - a lib's global hook sees a player's level-up in a room with no hooks, with `old_level`, `new_level` and `ctx.self`;
  - a three-level loss fires it three times, each after that step's local hook (record the order in an attribute list);
  - a mob's level change fires it too;
  - two packages fire in label order;
  - a zone declaring it fails to load; an unaccepted name fails to load;
  - in a world without `leveling`, the declaration loads and nothing fires (use the feature setup from
    `FeatureScriptTest.kt`);
  - a failing global hook records a diagnostic and doesn't stop the level step;
  - a quarantined package's global hook is skipped.
- CHANGELOG: "`global_hook(\"on_level_change\", fn)` for `lib` and `rules` packages: fires for every actor's level
  step."

### Exit Conditions

- [] `./gradlew check` passes with zero warnings. The API diff shows only `global_hook`.
- [] All existing `on_level_change` tests pass unchanged.

---

## Phase 5: `//lib:trainer.star` and the template's skills package

### Objective & Scope

- **In Scope:**
  - the library, including practice points and the `practice` command;
  - `template-world/skills/arcane/` with `spark` and `mend`;
  - library tests;
  - the spec paragraph.
- **Out of Scope (do not implement these in this phase):** the tutorial mentor (Phase 6), the robot world, engine code.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/lib/shop.star` (the whole file: `_define`, `_open`, `_ask` at 408, `_shop_here` at 420, the command
    functions, the `global_command` lines at 683);
  - `template-world/lib/quest.star:20-30` (lib-namespace attributes);
  - `template-world/zones/tutorial/shop.star` and `shop_test.star` (test structure);
  - `template-world/rules/combat.star:121-131` (`skill_check` and `skill_power`, for the spells' balance);
  - `template-world/MODULE.gups` (declared damage types and vitals);
  - §4.3 (`skill(...)`), and in §4.9 the shop paragraph starting "**`//lib:shop.star`:**".
- **Files to Modify / Create:**
  - new `template-world/lib/trainer.star`;
  - `template-world/lib/package.star` (add `trainer` to the `load()` comment);
  - new `template-world/skills/arcane/package.star` (kind `skills`) and `template-world/skills/arcane/spells.star`;
  - new `template-world/zones/tutorial/trainer_test.star` (it may declare test-only mobs, as `shop.star` does);
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the API/docs golden if Guardrail 4's tests fail.
- **Do Not Touch:** Kotlin, `samples/robot-world/`, `template-world/rules/`.

### Specification & Decision Record Changes

- §4.9, after the `//lib:shop.star` paragraph:

  ```markdown
  **`//lib:trainer.star`:** `trainer.define(id, teaches)` describes a trainer, `trainer.teach(label, up_to, gain,
  start=None)` one skill it teaches, and `trainer.open(ctx, keeper, trainer)` (from the keeper's `on_spawn`) makes the
  keeper teach it. Players spend practice sessions with `practice <skill>`, which raises the skill by `gain` (a skill
  they don't know starts at `start`, or `gain`), never past the trainer's `up_to`; `practice` alone shows the balance
  and what the trainer here teaches. Every level gained gives `PRACTICES_PER_LEVEL` sessions and every level lost takes
  them back, through a global `on_level_change` hook, so the balance can be negative; `trainer.grant_practices(ctx,
  actor, n)` adds more. The keeper's custom hook `practice_offer` may change the gain or cost or refuse, and plugin
  handlers for it join in (§4.15). The skill is set with `ctx.set_skill`, so `learn` restrictions apply, and a refused
  session costs nothing. A trainer that is incapacitated, stunned or fighting doesn't teach.
  ```

### Implementation Blueprint

- **Signatures & Types:**

```python
PRACTICES_PER_LEVEL = 2

def _teach(label, up_to, gain, start = None): ...   # dict; checks 1 <= up_to <= 100, gain >= 1, start in 1..up_to
def _define(id, teaches): ...                        # dict {"id", "teaches": [...]}; id checked like shop's _check_id
def _open(ctx, keeper, definition): ...              # ctx.set_attr(keeper, "trainer", definition)
def _practices(ctx, actor): ...                      # ctx.get_attr(actor, "practices", 0)
def _grant_practices(ctx, actor, n): ...             # int n, may be negative; returns the new balance

trainer = struct(define = _define, teach = _teach, open = _open,
                 practices = _practices, grant_practices = _grant_practices)

global_hook("on_level_change", _on_level_change)
global_command("practice", _practice_command, lane = "tick")
```

- **Algorithmic Steps & Invariants:**
  1. Labels in `teach` must be absolute (copy the shop's `_absolute` checks into this file, since libraries don't share
     private helpers).
  2. `_on_level_change(ctx)`: `_grant_practices(ctx, ctx.actor, (ctx.new_level - ctx.old_level) * PRACTICES_PER_LEVEL)`.
     Players and mobs alike; it is harmless on mobs.
  3. `_practice_command(ctx)` first returns "There's nothing to practice here." when `not ctx.has_feature("skills")`.
  4. `_trainer_here(ctx)` mirrors `_shop_here`: the first non-player actor in the room with a `trainer` attribute. The
     refusals are "There's no trainer here.", "<Keeper> can't teach right now." when its condition isn't `ok` or it is
     stunned, and "<Keeper> is busy fighting." when `ctx.has_feature("combat")` and it is fighting.
  5. The rest of `_practice_command` follows the Overview's steps 1–8 exactly. Each line's display name is
     `ctx.skill_info(line["label"]).name`. A line whose skill isn't loaded (`skill_info` returns `None`) is left out of
     the listing and never matches, and the library prints a note naming the label.
  6. `practice_offer` is called with `ctx.call_hook(keeper, "practice_offer", customer=..., skill=label,
     current=current, gain=gain, cost=cost, refusal=None)`. Interpret the return as the Overview says; mirror `_ask`.
  7. `spells.star`, checking §4.3 for required arguments:
     - `skill("spark", kind="spell", cost=vitals(mana=8), warmup=0, lag=4, range="near", target="enemy",
       channels=["spell", "sound"], effect=damage(dice="2d4", type=<a damage type declared in MODULE.gups>))`;
     - `skill("mend", kind="spell", cost=vitals(mana=10), lag=4, range="self", target="self", effect=heal(dice="2d6",
       vital="hp"))`.

### Test & Documentation Requirements

- `trainer_test.star` uses a test-only keeper with `trainer.open` in its `on_spawn`, and loads the skills through the
  keeper's `skills = {...}` so the package loads. Cover:
  - learning a new skill sets it to `start`, spends one point, and prints the learn message;
  - practicing a known skill adds `gain`, capped at `up_to`; at the cap it refuses and spends nothing;
  - `w.level_up` twice gives 4 points; spending none and then `w.level_down` three times leaves −2, and `practice`
    refuses with the "owe" message;
  - `practice_offer` returning a string refuses; returning `{"gain": 30, "cost": 2}` applies both;
  - no trainer here, a stunned trainer and an unknown skill each give their message;
  - `trainer.grant_practices` adds to the balance, and `practice` alone shows it;
  - a `learn` refusal spends nothing, if the harness can register a restriction from a test. Otherwise skip this case
    and say so in the phase report (Phase 1 covers the verb).
- CHANGELOG: "`//lib:trainer.star`: trainers, practice sessions earned per level, `practice`, and the `practice_offer`
  hook. The template world has a `//skills/arcane` package with `spark` and `mend`."

### Exit Conditions

- [] `./gradlew check` passes (the template's Starlark tests run in it).
- [] `trainer_test.star` passes, and `shop_test.star` and `tutorial_test.star` pass unchanged.

---

## Phase 6: The tutorial mentor, documentation, and finalization

### Objective & Scope

- **In Scope:**
  - a `mentor` mob in the tutorial zone;
  - the new tutorial chapter;
  - §7.4;
  - an end-to-end scenario and a manual web client check;
  - marking the `DESIGN_NEEDED` entry completed.
- **Out of Scope (do not implement these in this phase):** engine or library changes. If the scenario shows a bug, fix
  it with a test and note it in the report.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/zones/tutorial/mobs.star:120-140` (the shopkeeper);
  - `template-world/zones/tutorial/rooms.star` (pick a room for the mentor);
  - `template-world/docs/tutorials/08_shops.md` (chapter style).
- **Files to Modify / Create:**
  - `template-world/zones/tutorial/mobs.star` (and `rooms.star` only if a spawn is declared there);
  - `template-world/zones/tutorial/tutorial_test.star` (one test: the mentor teaches `spark`);
  - new `template-world/docs/tutorials/09_trainers.md`; `template-world/docs/tutorials/README.md`;
  - `docs/SPEC.md` (§7.4), `docs/DESIGN_NEEDED.md`, `docs/CHANGELOG.md`;
  - the API/docs golden if Guardrail 4's tests fail;
  - scratch only: `local-world-test/`.
- **Do Not Touch:** Kotlin, `template-world/lib/`, `samples/`.

### Specification & Decision Record Changes

- §7.4, a new row after the last milestone row (`markdownFormat` pads it):

```markdown
| **M33** | Acquiring skills: `ctx.set_skill`, `ctx.remove_skill` and `ctx.skill_info` (and `w.set_skill`/`w.remove_skill`), with `learn` run on every set; `global_hook("on_level_change", fn)` for privileged packages, loaded but inert without `leveling`; the `skills` command and `Char.Skills`, with a web client panel; `//lib:trainer.star` with practice sessions per level (a balance that can go negative), `practice` and the `practice_offer` hook; the template's `//skills/arcane` and tutorial mentor (§3.10, §4.4, §4.5, §4.9, §4.10, §4.12, §4.19). |
```

### Implementation Blueprint

1. **Mentor:**
   - `MENTOR = trainer.define(id = "mentor", teaches = [trainer.teach("//skills/arcane:spark", up_to = 50, gain = 10),
     trainer.teach("//skills/arcane:mend", up_to = 40, gain = 10)])`;
   - `mob("mentor", name = "the mentor", ..., behaviors = [sentinel()], skills = {"//skills/arcane:spark": 100,
     "//skills/arcane:mend": 100}, hooks = {"practice_offer": _mentor_offer}, on_spawn = _mentor_open)`;
   - `_mentor_offer` charges 2 sessions above 30, with a comment explaining the hook;
   - spawn it in a room near the guide or the shop.
2. **Tutorial `09_trainers.md`:** what skills are; `skills`; `practice`; how points come from levels and
   `grant_practices`; writing a trainer with `trainer.define`, `teach` and `open`; `practice_offer`; `learn`
   restrictions from plugins as the class cap; a test example. Fences at column 0, not in lists. Link it from the
   tutorials README.
3. **End-to-end check:**
   - run `./gradlew :cli:installDist`;
   - write `local-world-test/trainer.star`: spawn a player in the mentor's room, `w.level_up` once, `practice`,
     `practice spark` twice, `skills`, then `cast spark` at the training dummy, and print the transcript;
   - run `cli/build/install/gups/bin/gups scenario --world template-world --package //zones/tutorial
     local-world-test/trainer.star`;
   - check the balance, the proficiency rising to 20, `skills` listing it, and the cast going through the normal
     pipeline.
4. **Web client check:** with `gups serve --dev` against `local-world-test/world` (a copy of the template), log in, gain
   a level if the dev profile allows (otherwise skip and say so), practice with the mentor, and confirm the Skills panel
   appears. If this can't be driven without a browser, say so in the phase report. Then delete `local-world-test/`.
5. **`docs/DESIGN_NEEDED.md`:** rename the heading to `### Acquiring skills (COMPLETED — M33)` and leave the body in
   place (do not delete it). Leave "Skill-based advancement" as it is.
6. CHANGELOG: "The tutorial zone's mentor teaches `spark` and `mend`; tutorial 09 covers trainers."

### Test & Documentation Requirements

- The new `tutorial_test.star` test runs in `./gradlew check`.
- Run `./gradlew markdownFormat` and check the tutorial's code blocks kept their indentation.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings. Markdown is formatted.
- [] The `gups scenario` run passed as described, and `local-world-test/` is deleted.
- [] The `DESIGN_NEEDED` entry is marked `(COMPLETED — M33)`, not deleted, and the §7.4 row is present.
- [] `grep -n "a plugin setting a skill on an actor" docs/SPEC.md` finds nothing.
