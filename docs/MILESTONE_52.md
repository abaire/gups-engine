# Milestone 52: Respec Shrines

**Prerequisites:** [M51](MILESTONE_51.md) must be finished first. Where line numbers below are wrong, find the symbol by
name.

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** [DESIGN_NEEDED.md](DESIGN_NEEDED.md): `## Plugins` → `### In-game character respec and
  choice changes`.

- **Design Summary:**

  - **The engine stays mechanism-only.** No engine command, no engine currency, cooldown or skill policy. §4.19 already
    says ways to change choices in play are plugin features; this milestone states that the engine also sets no cost,
    cooldown or skill policy, and points at the heritage sample as the reference flow. The template world gains no
    choices: only its generic `//lib:trainer.star` changes.

  - **A respec is a class change, not a fresh character.** Base stats, base maxima and `rules.starting_stats` are never
    touched. What follows a change: trait-derived modifiers and deltas (the engine re-derives them), worn equipment (the
    engine's `wear` re-check makes refused items inactive), skills (the shrine's policy) and standing (an event and a
    hook). Granting or removing traits (blessings, curses) is out of scope: the curse sample and plugin builtins cover
    those.

  - **The split.** The heritage plugin owns everything about choices: turning the player's words into option ids,
    availability, `validate`, applying the change and class starter skills. A Starlark library, `respec.star`, shipped
    in the heritage sample's world tree (not the template world), owns world policy: which choices a shrine changes, the
    price and currency, the cooldown, the skill policy, the keeper's state and every line players read.

  - **Plugin builtins (heritage).**

    |                               Builtin                               |                                           Returns                                            |
    |---------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
    | `heritage.respec_menu(actor, choices, allow_same = False)`          | list of `{"choice", "title", "current", "options": [{"id", "label", "summary", "refusal"}]}` |
    | `heritage.respec_check(actor, phrase, choices, allow_same = False)` | `{"choice", "option", "label", "refusal"}`; pure, changes nothing                            |
    | `heritage.respec(actor, choice, option, allow_same = False)`        | refusal string or `None`; re-checks, then `setChoices`                                       |
    | `heritage.starter_skills(actor)`                                    | list of skill labels it granted                                                              |

    `choices` limits a shrine to the listed choice ids. `allow_same` lets the current option be picked again (the
    `"reset"` fresh-start case). The existing `heritage.choose` stays as it is.

  - **`respec.star`** mirrors `//lib:trainer.star`: `respec.define(...)` plus `respec.open(ctx, keeper, definition)`
    from the keeper's `on_spawn`, and a tick-lane global command `respec`. Every `define` parameter except `id` and
    `choices` falls back to a constant at the top of the file, and those constants are the world-wide defaults:

    ```python
    DEFAULT_SKILLS = "keep"          # "keep" or "reset"
    DEFAULT_COST = 0
    DEFAULT_CURRENCY = "//lib:gold_coin"
    DEFAULT_COOLDOWN_PULSES = 0
    ```
  - **Skill policies.** `"keep"` leaves every skill (multiclass, switch back and keep progress; heritage's `cast` and
    `learn` restrictions already make off-class skills unusable) and refuses the current option. `"reset"` refunds
    recorded practice sessions, removes every skill, and allows the current option (a fresh start in the same class).
    Both end with `heritage.starter_skills`.
  - **Refunds need a ledger.** `//lib:trainer.star` records the sessions spent per skill and gains `trainer.refund(ctx,
    actor, skill = None)`. Skills from use, starter grants or direct `set_skill` calls have no record and refund
    nothing.
  - **Keeper hooks.** `respec_offer` (asked per listed option and once per request; may refuse, or override `cost`,
    `cooldown_pulses` and `skills`) and `respec_done` (after a successful change). Plugin handlers join both through
    `ctx.call_hook`. Standing: after paying, with the `standing` feature, the library raises the world standing event
    `"respec"` on the keeper with `amount` = coins paid; world events have no default deltas, so nothing changes unless
    the world's `standing_change` handles it. Class-specific standing effects belong in `respec_done`.
  - **Order of a `respec <words>` request** (one hook on the world thread, so nothing interleaves): find the keeper →
    `heritage.respec_check` → `respec_offer` → cooldown → coin count → `heritage.respec` → skill policy →
    `heritage.starter_skills` → pay → stamp cooldown → messages → standing event → `respec_done`. Coins are counted
    before the change and taken after it succeeds, so no refund path exists. The cooldown check reads the stored
    `respec_ready_at`, so a `cooldown_pulses` the hook returns only sets the wait after this change.
- **Specification Impact:** §4.9 (the `//lib:trainer.star` paragraph: ledger and `trainer.refund`), §4.15 (the Heritage
  Plugin sample bullet), §4.19 ("Changing choices later": no engine policy, the heritage reference flow), plus
  `docs/PLUGINS.md`, `docs/CHANGELOG.md`, and the heritage sample's README and tutorials.
- **Decision Record Updates:** None. `docs/DECISIONS.md` has no entries that cite §4.19 or respec.

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first, run them and watch them fail, then write the code.
   - No spec section numbers or decision ids in comments, Kotlin or Starlark. Comments explain why, not what.
   - All Kotlin warnings are errors. Identifiers say **skill**, never **spell**.
   - After changing code, run the CodeScene `code_health_review` on the changed files and fix any drop in Code Health;
     run `pre_commit_code_health_safeguard` before finishing a phase. Builtin bindings whose parameter lists are fixed
     by this milestone may exceed CodeScene's argument-count limit; that finding is accepted.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. The engine is unreleased: no migrations, no back-compat
   shims, and existing worlds don't constrain anything.
3. CHANGELOG: add a new `### Respec shrines` subsection at the top of `## api_version 1 (unreleased)` in
   `docs/CHANGELOG.md`, above `### Timed tasks and crafting`. Phase 1 creates it; each later phase appends its bullets.
4. In **every** phase, regenerate the API files before the phase's final `./gradlew check` (an empty diff is fine):
   - `./gradlew :cli:test -Dgups.updateGolden=true` rewrites `cli/src/test/resources/api.golden.json` and
     `cli/src/test/resources/docs-golden/`.
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout rewrites `docs/api/api-1.json`.
   - Read the diff: only what the phase names may change; `ApiDiffTest` must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring a neighboring symbol's example.
5. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation (see `AGENTS.md` on code blocks in lists).
6. Never read or touch `local-world/`. Scratch scenarios and world copies go in `local-world-test/`, which you delete
   when done.
7. Plain-text messages below are exact: tests assert them, so use them verbatim.

---

## Phase 1: The trainer's practice ledger and `trainer.refund`

### Objective & Scope

- **In Scope:** `//lib:trainer.star` records sessions spent per skill and exports `trainer.refund`; tests; the §4.9
  paragraph; the CHANGELOG subsection.
- **Out of Scope:** *Do not implement these; later phases do.* Anything in `samples/`, any respec code, any change to
  how `practice` prices, gains or refuses a session.

### Context Working Set

- **Read / Reference Only:** `template-world/lib/trainer.star` (`_practices`, `_grant_practices`, `_do_practice` ~163,
  the `trainer = struct(...)` export ~77); `docs/SPEC.md` §4.5 (`del_attr`) and §4.9 (the `**//lib:trainer.star:**`
  paragraph, ~line 2309).
- **Files to Modify:** `template-world/lib/trainer.star`, `template-world/zones/tutorial/trainer_test.star`,
  `template-world/zones/tutorial/trainer_testdata.star` (the fixture trainers and the `grant`/`set_offer` test commands
  live here; add the `refund` command), `docs/SPEC.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** engine Kotlin, `samples/`, other `lib` files, `docs/DESIGN_NEEDED.md`.

### Specification & Decision Record Changes

- §4.9, in the `//lib:trainer.star` paragraph, replace the sentence ending "`trainer.grant_practices(ctx, actor, n)`
  adds more." with:

  > `trainer.grant_practices(ctx, actor, n)` adds more. The library records how many sessions each skill cost, and
  > `trainer.refund(ctx, actor, skill=None)` gives back what was recorded for one skill, or for all of them, clears that
  > record and returns the number refunded; a skill gained any other way has no record and refunds nothing.

### Implementation Blueprint

- **Signatures & Types:**

  ```python
  def _spent(ctx, actor):
    """The actor's ledger: skill label -> sessions spent, as a new mutable dict."""

  def _refund(ctx, actor, skill = None):
    """Gives back the sessions recorded for skill (a label), or for every skill; returns the count."""

  trainer = struct(define = _define, teach = _teach, open = _open,
                   practices = _practices, grant_practices = _grant_practices, refund = _refund)
  ```
- **Algorithmic Steps & Invariants:**
  1. The ledger is the attribute `"practiced"` on the player in the lib's namespace (`ctx.get_attr`/`ctx.set_attr`, as
     `"practices"` is). `_spent` returns `dict(ctx.get_attr(actor, "practiced", {}))` so callers never mutate a stored
     value in place.
  2. In `_do_practice`, right after `_grant_practices(ctx, ctx.self, -cost)` and only when `cost >= 1`: read the ledger,
     add `cost` to `ledger[label]` (starting at 0), and write it back. A refused session records nothing.
  3. `_refund`: `skill` may be a label string or `None`. With a label, take and delete that entry (0 when absent) and
     write the ledger back, or `ctx.del_attr` it when it is now empty; with `None`, sum every entry and clear the ledger
     with `ctx.del_attr(actor, "practiced")`. If the total is above 0, `_grant_practices(ctx, actor, total)`. Return the
     total. It sends nothing and touches no skill.
  4. Update the file's header comment to mention `trainer.refund`.

### Test & Documentation Requirements

- Add to `trainer_test.star` (use the existing fixtures; `p.do("grant N")` adds sessions):
  - `test_refund_returns_recorded_sessions`: practice spark twice and mend once at `:trainer_test_room`;
    `trainer.refund` isn't reachable from the harness directly, so add a fixture command `refund` to
    `trainer_testdata.star`'s test room that calls `trainer.refund(ctx, ctx.self, ctx.args or None)` and sends
    `"Refunded %d."`. Assert `refund //skills/arcane:spark` → `Refunded 2.`, then `refund` → `Refunded 1.`, then
    `refund` → `Refunded 0.`, and the balance is back where it started.
  - `test_refund_records_hook_costs`: at `:offer_test_room` with `set_offer dict` (cost 2), one session, then `refund` →
    `Refunded 2.`.
  - `test_refused_session_records_nothing`: `set_offer refuse`, `practice spark`, `refund` → `Refunded 0.`.
- CHANGELOG: create `### Respec shrines` with the bullet "`//lib:trainer.star` records the sessions spent per skill;
  `trainer.refund(ctx, actor, skill=None)` gives them back."

### Exit Conditions

- [] `./gradlew check` passes; API files regenerated.
- [] The three new tests run and pass as part of `./gradlew check`. Find the Gradle test that runs the template world's
  `*_test.star` files (search `cli/` and `harness/` tests for `template-world`), and confirm in its report that the new
  test names appear.
- [] §4.9 has the new sentence and matches the code.

---

## Phase 2: Heritage respec builtins and starter skills

### Objective & Scope

- **In Scope:** move the creation provider into a named class; a pure `Respec` helper; the builtins `respec_menu`,
  `respec_check`, `respec`, `starter_skills`; `created` granting starter skills for both `newCharacter` values; starter
  skill labels moved to `//skills/heritage_skills:*`; unit tests; the §4.15 bullet.
- **Out of Scope:** *Do not implement these; later phases do.* `respec.star`, any `.star` world content, the sample
  world tree, `HeritageWorldTest`, tutorials and README.

### Context Working Set

- **Read / Reference Only:** `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt` (`PluginActor.choices`, `skills`,
  `PluginWorld.setChoices`, `setSkill`); `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt` (`PluginCreation`,
  `PluginChoice`, `PluginOption`, `PluginParam`, `addBuiltin` ~99); `plugin-api/.../host/Values.kt` `fromPlain` (Kotlin
  `List` and `Map<String, *>` return as Starlark lists and dicts); `core/.../world/Verbs.kt` `setChoices` ~239 (the
  engine re-checks availability and `validate` and is authoritative).
- **Files to Modify / Create:**
  - `samples/heritage-plugin/src/main/kotlin/gups/sample/heritage/HeritagePlugin.kt`
  - `samples/heritage-plugin/src/main/kotlin/gups/sample/heritage/HeritageCreation.kt` (new)
  - `samples/heritage-plugin/src/main/kotlin/gups/sample/heritage/Respec.kt` (new)
  - `samples/heritage-plugin/src/test/kotlin/gups/sample/heritage/RespecTest.kt` (new)
  - `samples/heritage-plugin/src/test/kotlin/gups/sample/heritage/FakeEntities.kt`, `FakeWorld.kt` (make `skills`
    configurable on `FakeActor`; record `setSkill` calls in `FakeWorld`)
  - `samples/heritage-plugin/src/test/kotlin/gups/sample/heritage/HeritagePluginTest.kt` (the fixture's skills package
    and the `//skills:magic_missile` assertion move to `//skills/heritage_skills:*`)
  - `samples/heritage-plugin/docs/tutorials/02_character_creation_provider.md`,
    `03_operational_restrictions_and_tags.md`, `05_testing_creation_and_traits.md`, and `docs/PLUGINS.md` (step 8)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `plugin-api/`, `core/`, `script/`, `template-world/`.

### Specification & Decision Record Changes

- §4.15, the **Heritage Plugin** sample bullet: replace "class skill assignments on creation (`created`)," with "class
  starter skills granted by `created` and after a respec," and append before the final period: ", and respec builtins
  (`heritage.respec_menu`, `respec_check`, `respec` and `starter_skills`)". Phase 3 extends this bullet.

### Implementation Blueprint

#### Signatures & Types

```kotlin
// HeritageCreation.kt
open class HeritageCreation : PluginCreation {
  // choices (open, so a test can override it), steps, available, validate, startRoom, prepare:
  // moved verbatim from the anonymous object
  override fun created(world: PluginWorld, actor: PluginActor, newCharacter: Boolean) {
    grantStarterSkills(world, actor)
  }

  /** Grants the current class's starter skills the actor lacks; returns the labels granted. */
  fun grantStarterSkills(world: PluginWorld, actor: PluginActor): List<String>

  companion object {
    const val STARTER_PROFICIENCY = 25
    val STARTER_SKILLS: Map<String, List<String>> =
      mapOf(
        "mage" to listOf("//skills/heritage_skills:magic_missile"),
        "cleric" to listOf("//skills/heritage_skills:minor_heal"),
      )
  }
}

// Respec.kt
data class RespecOption(val id: String, val label: String, val summary: String, val refusal: String?)

data class RespecChoice(
  val choice: String,
  val title: String,
  val current: String?,
  val options: List<RespecOption>,
)

data class RespecMatch(val choice: String?, val option: String?, val label: String?, val refusal: String?)

class Respec(private val creation: HeritageCreation) {
  fun refusal(world: PluginWorld, actor: PluginActor, choice: String, option: String, allowSame: Boolean): String?

  fun menu(world: PluginWorld, actor: PluginActor, choices: List<String>, allowSame: Boolean): List<RespecChoice>

  fun check(
    world: PluginWorld,
    actor: PluginActor,
    phrase: String,
    choices: List<String>,
    allowSame: Boolean,
  ): RespecMatch

  fun apply(world: PluginWorld, actor: PluginActor, choice: String, option: String, allowSame: Boolean): String?
}
```

Each data class gets a `toPlain(): Map<String, Any?>` (keys exactly as in the Overview's table; `options` a list of
maps) for the builtins to return.

- **Algorithmic Steps & Invariants:**
  1. **Refactor first, behavior unchanged:** move the anonymous `PluginCreation` into `HeritageCreation`; `register`
     calls `r.setCreation(creation)` with one instance it also hands to `Respec`. Keep `RACE_OPTIONS` and
     `CLASS_OPTIONS` where they are. Existing tests must pass before anything else changes.
  2. **`grantStarterSkills`:** for each label in `STARTER_SKILLS[actor.choices["class"]]` (none for no class or
     warrior): skip it if `label in actor.skills`; otherwise call `world.setSkill(actor, label, STARTER_PROFICIENCY)`
     inside `try`/`catch (IllegalArgumentException)` (an undefined skill is skipped, as today); add the label to the
     result only when it returns `null` (a `learn` refusal is skipped silently). `created` now runs it for every
     `newCharacter` value, which also fixes the login missing-choice flow.
  3. **`refusal`:** `choice` and `option` must be defined by `creation` (else `IllegalArgumentException("unknown choice
     '<id>'")` / `"unknown option '<id>' for <choice>"`).
     - If `actor.choices[choice] == option` and not `allowSame` → `"That is already your <title lowercased>."`
     - `candidate = actor.choices + (choice to option)`. Walk `creation.choices` in declaration order with `before`
       (answers of earlier choices only). For each choice `c`: `avail = creation.available(world, c.id, before)`; `a =
       candidate[c.id]`. If `c.id == choice` and `option !in avail` → `"<option label> isn't open to you."`. If `c.id !=
       choice`, `a != null` and `a !in avail` → `"That would leave your <c.title lowercased> (<label of a>)
       unavailable."`. Then add `a` to `before` when it is non-null.
     - Then `creation.validate(world, candidate)` → its message if non-null.
     - Otherwise `null`.
  4. **`menu`:** for each id in `choices`, in the order given: `RespecChoice(id, title, actor.choices[id], options)`
     where `options` lists every option of that choice in declaration order with `refusal(...)` filled in.
  5. **`check`** (case-insensitive, deterministic):
     - `words` = `phrase.trim().lowercase()` split on whitespace. None → `RespecMatch(null, null, null, "Change
       what?")`.
     - `scope` = the choices named in `choices`. If `words.size >= 2`, the choices in `scope` whose id or lowercased
       title starts with `words[0]`: when exactly one, `scope` becomes that one and the option phrase is the remaining
       words joined by one space; otherwise the option phrase is all the words joined.
     - With one word, the option phrase is that word.
     - Candidates: options in `scope` whose id or lowercased label equals the phrase; if there are none, those whose id
       or lowercased label starts with it.
     - None → `"There's nothing like that to become here."`. More than one → `"Which do you mean: <list>?"`, where
       `<list>` is the labels joined by `", "`, each followed by ` (<title lowercased>)` when `scope` has more than one
       choice.
     - Exactly one → `RespecMatch(choice, option, label, refusal(...))`.
  6. **`apply`:** `refusal(...)` non-null → return it. If `actor.choices[choice] == option` (an allowed same pick),
     return `null` without calling `setChoices`. Otherwise return `world.setChoices(actor, mapOf(choice to option))`.
  7. **Builtins** in `register`, following `heritage.choose` (read positional arguments by index; a missing optional
     argument means its default):
     - Every builtin first requires `actor` to be a `PluginActor` and a player
       (`IllegalArgumentException("heritage.<name>(actor): expected a player")`); `choices` must be a list of strings
       (`"heritage.<name>(choices): expected a list of choice ids"`); `allow_same` is a `Boolean`, default `false`.
     - `respec_menu` → `menu(...).map { it.toPlain() }`; `respec_check` → `check(...).toPlain()`; `respec` →
       `apply(...)`; `starter_skills(actor)` → `creation.grantStarterSkills(world, actor)`.
     - Each `PluginParam` doc says what the Overview's table says; `returnType` is `"list"`, `"dict"`, `"str"` and
       `"list"`.
  8. **Docs that quote this code:**
     - Tutorials 02, 03 and 05 and `docs/PLUGINS.md` quote `//skills:magic_missile` / `minor_heal`. Update those labels
       to `//skills/heritage_skills:*`.
     - Tutorial 02 (~140 and ~230) and `docs/PLUGINS.md` (~425) also quote the old `created` body with its `when`.
       Replace each excerpt with the new shape: `created` calling `grantStarterSkills`, and the `STARTER_SKILLS` map.
     - In tutorial 02, change the prose that says starter skills are given only to new characters.
     - Change nothing else in these files yet. Run `./gradlew markdownFormat` and check that the code blocks kept their
       indentation.

### Test & Documentation Requirements

- `RespecTest.kt` (unit, with `FakeWorld` and `FakeActor`; `FakeWorld.available`/`validate` come from the real
  `HeritageCreation`):
  - `refusal`: same option refused / allowed with `allowSame`; dwarf → mage `"Mage isn't open to you."`; an elf mage
    changing race to dwarf → `"That would leave your class (Mage) unavailable."`; a human warrior → mage allowed.
  - `menu`: options in declaration order with the right refusals; `current` is the actor's answer.
  - `check`: `"mage"`, `"Mag"`, `"class mage"`, `"cl mage"` all match class/mage; `""` → `"Change what?"`; `"xyz"` → no
    match; a phrase scoped to a choice not in `choices` doesn't match.
  - Ambiguity: the real options share no prefix. So make `HeritageCreation` `open` with `open val choices`, and in the
    test override `choices` so that class also has an option `elementalist` (label "Elementalist"). Then `"el"` over
    `["race", "class"]` gives `"Which do you mean: Elf (race), Elementalist (class)?"`. `"class el"` matches
    Elementalist alone, with `refusal` `"Elementalist isn't open to you."`, because the real `available()` doesn't list
    it.
  - `apply`: calls `setChoices` with exactly one change; a refusal calls nothing; a same pick with `allowSame` calls
    nothing and returns `null`.
  - `grantStarterSkills`: grants for mage, nothing for warrior, skips a known skill, skips a `learn` refusal
    (`FakeWorld.setSkill` returning a message), and `created(..., newCharacter = false)` grants too.
- `HeritagePluginTest`: the fixture skills package becomes `skills/heritage_skills/` (`package.star` + `skills.star`),
  and its assertion reads `//skills/heritage_skills:magic_missile`.
- CHANGELOG bullet: "`samples/heritage-plugin`: `heritage.respec_menu`, `respec_check`, `respec` and `starter_skills`;
  `created` grants class starter skills for re-picked characters too; starter skills are `//skills/heritage_skills:*`."

### Exit Conditions

- [] `./gradlew :samples:heritage-plugin:test` passes, then `./gradlew check`; API files regenerated.
- [] `RespecTest` covers every refusal string above.
- [] §4.15's heritage bullet matches the code.

---

## Phase 3: The sample world tree, `respec.star` with the `keep` policy, and the class mentor

### Objective & Scope

- **In Scope:** `samples/heritage-plugin/world/` (copied over a template-world copy by tests): the
  `//skills/heritage_skills` package, `lib/respec.star` with `define`, `open`, the menu, the full request flow for
  `"keep"`, the `respec_offer` hook and the cooldown; the `//zones/shrine` zone with a class mentor; `*_test.star`
  tests; `HeritageWorldTest` running them; the §4.19 paragraph.
- **Out of Scope:** *Do not implement these; Phase 4 does.* The `"reset"` policy's refund and skill removal (accept
  `"reset"` in `define` but leave a `fail("reset is not implemented yet")` at the point it would run), the standing
  event, `respec_done`, the rebirth altar. No tutorial or README text.

### Context Working Set

- **Read / Reference Only:** `template-world/lib/trainer.star` (header comment, `_define`/`_check_id` validation,
  `_trainer_at`/`_trainer_here`, `_interpret_offer`, `global_command` registration); `template-world/lib/shop.star`
  (`_coins_in` ~467, the coin-moving loop in `_buy_command` ~588, `_currency_word` ~526);
  `template-world/lib/package.star` (`lib` is privileged and may register global commands);
  `template-world/zones/tutorial/trainer_testdata.star` (fixture style and test-only room commands);
  `HeritageWorldTest.worldDir` and its `TestRunner` use; `samples/heritage-plugin/build.gradle.kts` (`tasks.test` inputs
  and system properties); `template-world/MODULE.gups` ("1200 pulses is 5 minutes": 240 pulses a minute).
- **Files to Modify / Create:**
  - `samples/heritage-plugin/world/skills/heritage_skills/package.star`, `skills.star` (new)
  - `samples/heritage-plugin/world/lib/respec.star` (new)
  - `samples/heritage-plugin/world/zones/shrine/package.star`, `shrine.star`, `shrine_testdata.star`, `respec_test.star`
    (new)
  - `samples/heritage-plugin/src/test/kotlin/gups/sample/heritage/HeritageWorldTest.kt`
  - `samples/heritage-plugin/build.gradle.kts`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `template-world/` (the shrine never goes there), engine modules, `HeritagePlugin.kt` (except a bug
  the new tests prove, with a unit test).

### Specification & Decision Record Changes

- §4.15, the **Heritage Plugin** bullet: append before the final period ", which the sample's `respec.star` shrine
  library (in its `world/` tree) builds on".
- §4.19, in **Changing choices later**, after the sentence ending "are plugin features.", insert:

  > The engine sets no price, cooldown or skill policy for a change and never re-runs creation steps; those are world
  > policy. The heritage sample shows one way to do it: shrine keepers in content, built with its `respec.star` library
  > on its respec builtins, which charge coins, apply a cooldown and keep the skills a character has.

### Implementation Blueprint

- **Skills package** `//skills/heritage_skills` (not `//skills/heritage`: a package's attribute namespace is its last
  path segment, and no package may take a plugin id's namespace): `package(api_version = 1, kind = "skills", title =
  "Heritage")`; `skill("magic_missile", kind = "spell", target = "enemy", tags = ["arcane"], ...)` and
  `skill("minor_heal", kind = "spell", target = "self", tags = ["divine"], ...)`, with a cost, lag and effect like
  `spark` and `mend` in `template-world/skills/arcane/spells.star`.

- **`respec.star` API:**

  ```python
  DEFAULT_SKILLS = "keep"
  DEFAULT_COST = 0
  DEFAULT_CURRENCY = "//lib:gold_coin"
  DEFAULT_COOLDOWN_PULSES = 0
  PULSES_PER_MINUTE = 240

  def _define(id, choices, skills = None, cost = None, currency = None, cooldown_pulses = None):
    """A shrine definition dict; None takes the DEFAULT_* constant."""
  def _open(ctx, keeper, definition):
    """Marks keeper as serving definition. Called from the keeper's on_spawn."""
  def _ready_at(ctx, actor):
    """The pulse from which actor may change again (0 when never changed)."""

  respec = struct(define = _define, open = _open, ready_at = _ready_at)
  global_command("respec", _respec_command, lane = "tick")
  ```

  - `define` fails at load (`fail(...)`) for: an id not matching `[a-z][a-z0-9_]*` (copy `_check_id`), `choices` not a
    non-empty list of strings, `skills` not `"keep"`/`"reset"`, `cost` or `cooldown_pulses` not an int ≥ 0, `currency`
    not an absolute label string. It returns `{"id", "choices", "skills", "cost", "currency", "cooldown_pulses"}`.
  - `open` stores the definition in the keeper's `"respec"` attribute (lib namespace), as `trainer.open` does.
  - The cooldown is one attribute per player, `"respec_ready_at"` (lib namespace), shared by every shrine so players
    can't hop between keepers.
  - The header comment shows usage (as `trainer.star`'s does), says the file needs the heritage plugin, and lists both
    hooks with their fields and returns.
- **`respec_offer` hook.** `ctx.call_hook(keeper, "respec_offer", action = ..., customer = ..., choice = ..., old = ...,
  new = ..., cost = ..., currency = ..., cooldown_pulses = ..., skills = ...)`; `action` is `"list"` or `"respec"`.
  `_interpret_offer(r, terms)` takes `terms = {"cost", "cooldown_pulses", "skills"}` and returns `(terms, refusal)`: a
  string is a refusal; a dict may override `cost` (int ≥ 0), `cooldown_pulses` (int ≥ 0) and `skills`
  (`"keep"`/`"reset"`), each bad value printed (`print("respec_offer %s %r ...; ignored")`) and ignored; `None` keeps
  the terms; anything else is printed and ignored. Mirror `trainer.star`'s `_interpret_offer`.
- **`_price(ctx, cost, currency)`:** `"free"` for 0. Otherwise `"%d %s"` with the currency word, which comes from
  `ctx.item_info(currency)`: its `plural` when it has one, else its `name`, as `shop.star`'s `_currency_word` does (so
  the gold coin gives `"10 gold coins"`, and also `"1 gold coins"`, the same as shops). If `item_info` returns `None`,
  use the label.
- **`_respec_command`, keeper lookup:** like `_trainer_at`/`_trainer_here`, finding a non-player in the room with a
  `"respec"` attribute.
  - None → `"There's no one here who can change you."`
  - Not `ok` or stunned → `"%s can't help you right now." % keeper.name`
  - Fighting (only with `combat`) → `"%s is busy fighting." % keeper.name`
- **`respec` with no arguments (the menu):**
  1. Find the keeper (refusals as above).
  2. `menu = heritage.respec_menu(ctx.self, d["choices"], d["skills"] == "reset")`.
  3. For each choice entry, send a header `"%s can change your %s (now %s):" % (keeper.name, title.lower(), <current
     label or "none">)`, then one line per option:
     - Each option line starts with two spaces.
     - Allowed: two spaces, then `<label> - <price>: <summary>`. The price comes from `respec_offer` with `action =
       "list"` for that option.
     - Refused by `refusal` or by the hook: two spaces, then `<label>: <summary> (<refusal>)`.
  4. Last line: `"You can change now."`, or `"You can change again in %d minutes." % ceil(remaining /
     PULSES_PER_MINUTE)` while `ctx.pulse < ready_at`.
- **`respec <words>` (a request), steps in order.** Every refusal is sent to the player and ends the command with
  nothing changed or paid.
  1. Find the keeper.
  2. `c = heritage.respec_check(ctx.self, ctx.args, d["choices"], d["skills"] == "reset")`. If `c["refusal"]` is set,
     send it. Capture `old = ctx.self.choices.get(c["choice"])` now; the hooks and messages use it.
  3. Run `respec_offer` with `action = "respec"` and `old`, and interpret it. A refusal is sent. The returned terms
     (`cost`, `cooldown_pulses`, `skills`) are the ones every later step uses.
  4. While `ctx.pulse < _ready_at(ctx, ctx.self)`: `"You must wait %d more minutes before changing again."` (minutes
     rounded up). This reads the stored pulse, so the hook's `cooldown_pulses` only sets the wait after this change.
  5. Same-option check: if the hook switched `skills` to `"keep"` and `c["option"]` is the current option, send `"That
     is already your %s." % <title lowercased>`.
  6. If the carried coins (a `_coins_in` copy) are fewer than `cost`: `"You can't afford that."`
  7. `err = heritage.respec(ctx.self, c["choice"], c["option"], skills == "reset")`. If `err` is set, send it.
  8. Skill policy: under `"keep"`, nothing. Under `"reset"`, Phase 3 stops at the `fail(...)` placeholder.
  9. `granted = heritage.starter_skills(ctx.self)`.
  10. Pay: move `cost` coins from the player's inventory to the keeper. Use the `move_item(..., count = n)` +
      `merge_stack` loop from `_buy_command`, with the keeper as the destination.
  11. `ctx.set_attr(ctx.self, "respec_ready_at", ctx.pulse + cooldown_pulses)`.
  12. Messages, in this order:
      - `"You pay %s." % _price(...)`, only when the cost is above 0.
      - `"Your %s is now %s." % (title.lower(), c["label"])`.
      - `"You learn %s." % ctx.skill_info(label).name` for each label in `granted`.
      - Echo `"%s is changed by %s." % (ctx.self.name, keeper.name)` to the room, excluding the player.
  13. Phase 4 adds the standing event and `respec_done` here.
- **`//zones/shrine`:**
  - `shrine.star` (content): room `mentor_hall` ("Hall of Mentors"), with a sentinel mob `mentor` ("the guild mentor")
    spawned there.
    - Its `on_spawn` calls `respec.open(ctx, ctx.self, MENTOR)` with `MENTOR = respec.define(id = "mentor", choices =
      ["class"], cost = 10)`. `skills` and `cooldown_pulses` take the defaults: `"keep"` and 0.
    - Its `respec_offer` hook gives a 20% discount (`{"cost": cost * 8 // 10}`) to a customer who has the trait
      `cleric`, and returns `None` otherwise.
  - `shrine_testdata.star` (fixture; no exits, sentinel keepers, in the style of `trainer_testdata.star`). The keeper
    lookup takes the first respec keeper in a room, so each fixture keeper gets its own room:
    - `respec_test_room` with a `test_mentor` keeper: `respec.define(id = "test_mentor", choices = ["class"], cost =
      10)`. Its `respec_offer` reads the keeper attribute `offer_mode`: `"refuse"` → `"The mentor refuses you."`,
      `"free"` → `{"cost": 0}`, anything else → `None`.
    - `cooldown_test_room` with a `cooldown_keeper`: `respec.define(id = "cooldown_test", choices = ["race", "class"],
      cost = 0, cooldown_pulses = 480)`.
    - An item `plate_mail` ("a suit of plate mail"): body armor with `tags = ["heavy_armor"]`. Copy the `wear` slot and
      `kind` from a worn armor item in `template-world/zones/tutorial`.
    - One `FIXTURE_COMMANDS` dict, given to every fixture room (room commands work only in their own room):
      - `coins N`: `ctx.give(ctx.self, "//lib:gold_coin", N)`, merging the result as `_buy_command` does.
      - `grant N`: `trainer.grant_practices(ctx, ctx.self, N)`.
      - `stun_keeper`: stuns the respec keeper here (copy `_stun_trainer`).
      - `offer MODE`: sets `offer_mode` on the respec keeper here.
      - `armor`: gives the player a `plate_mail` and puts it on with `ctx.wear` (added for this; scripts had no verb to
        wear an item).
- **`HeritageWorldTest`:**
  - Add `private fun shrineWorld(): WorldDir`. It is `worldDir()` plus a recursive copy of
    `System.getProperty("gups.sample.heritageWorld")` over the copy's root (directories merge, and `lib/respec.star`
    joins the template's `lib` package).
  - Add a test that runs `TestRunner(dir, plugins).run(listOf("//zones/shrine"))` and asserts `failed == 0`, `errors ==
    0` and `passed > 0`, with the same failure messages as the existing creation test.
- **`build.gradle.kts`:** in `tasks.test`, add the sample world directory as an input
  (`inputs.files(fileTree(...)).withPropertyName("heritageWorld")`) and `systemProperty("gups.sample.heritageWorld",
  <its absolute path>)`.

### Test & Documentation Requirements

- `respec_test.star` (`t.world(seed = 1, packages = ["//zones/shrine"])`; spawn players with `choices = {...}`; give
  coins with the fixture command):
  - The menu at the mentor shows the class options, prices, the current class refused as `(That is already your
    class.)`, and `You can change now.`
  - `respec mage` for a human warrior with 10 coins:
    - `You pay 10 gold coins.`, `Your class is now Mage.` and `You learn magic_missile.`. A skill's name is its
      identifier (`skill()` takes no display name), the same name players type for `practice` and `cast`.
    - `p.choices["class"] == "mage"`, the coins are gone, and `p.skills["//skills/heritage_skills:magic_missile"] ==
      25`.
    - A skill the player knew before is still known (`"keep"`).
  - Refusals, each leaving coins, choices and skills unchanged:
    - no keeper;
    - stunned mentor;
    - `respec` + nothing matching;
    - the same class;
    - a dwarf asking for mage (`Mage isn't open to you.`);
    - too few coins (`You can't afford that.`);
    - `offer refuse`.
  - Cooldown at the cooldown keeper:
    - A change, then a second change is refused with `You must wait 2 more minutes before changing again.`
    - `w.run(pulses = 480)`, then the second change works.
  - `offer free` makes the change cost nothing and prints no `You pay` line.
  - A warrior who ran `armor` (so wears `plate_mail`, tagged `heavy_armor`) becomes a mage.
    - The `respec` output contains `Your spellcraft can't work through that much metal.`
    - `equipment` still lists the plate mail, marked `(inactive)`.
- CHANGELOG bullet: "The heritage sample ships `world/` content: `lib/respec.star` (shrine keepers, the `respec`
  command, `respec_offer`, cooldowns, world-wide defaults) and a `//zones/shrine` example."

### Exit Conditions

- [] `./gradlew :samples:heritage-plugin:test` passes, then `./gradlew check`; API files regenerated.
- [] Every message string in this phase appears in a passing assertion.
- [] §4.19 has the new paragraph and matches the code.

---

## Phase 4: The `reset` policy, standing and the rebirth altar

### Objective & Scope

- **In Scope:** the `"reset"` skill policy (refund, remove, then starter skills), the `"respec"` standing event, the
  `respec_done` hook, and a rebirth altar keeper with a standing-based price; tests.
- **Out of Scope:** *Do not implement these; Phase 5 does.* Tutorials, README, `PLUGINS.md`, the end-to-end scenario.

### Context Working Set

- **Read / Reference Only:** `samples/heritage-plugin/world/lib/respec.star` (from Phase 3);
  `template-world/lib/trainer.star` (`trainer.refund` from Phase 1); `docs/SPEC.md` §4.5 (`remove_skill`,
  `standing_event`, `adjust_standing`, `disposition`, `has_feature`) and §5.14 (world events have no default deltas);
  `template-world/rules/standing.star` (`standing_change`: an unknown world event falls through to no deltas).
- **Files to Modify / Create:** `samples/heritage-plugin/world/lib/respec.star`, `world/zones/shrine/shrine.star`,
  `shrine_testdata.star`, `respec_test.star`, `docs/SPEC.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `template-world/`, the heritage Kotlin sources (except a bug the new tests prove, with a unit test).

### Specification & Decision Record Changes

- §4.19: in the paragraph Phase 3 added, replace "keep the skills a character has" with "keep or reset skills, refunding
  practice sessions through the trainer library".

### Implementation Blueprint

- `respec.star` loads `load("//lib:trainer.star", "trainer")`.
- **Step 8 under `"reset"`** (replacing the Phase 3 `fail`):
  1. `refunded = trainer.refund(ctx, ctx.self)`.
  2. Remove every skill: `for label in list(ctx.self.skills): ctx.remove_skill(ctx.self, label)`.
  3. Then step 9 grants starter skills as before.
  4. Messages, after the `Your %s is now %s.` line and before the `You learn` lines: `"You forget everything you had
     learned."`, then `"%d practice sessions are returned to you." % refunded` when `refunded > 0`.
- **After step 12:**
  - Standing: `if ctx.has_feature("standing"): ctx.standing_event(keeper, ctx.self, "respec", amount = cost)`. The
    keeper is the one affected and the customer the one who acted.
  - Then `ctx.call_hook(keeper, "respec_done", customer = ctx.self, choice = c["choice"], old = <option before>, new =
    c["option"], skills = skills, cost = cost)`. Its return value is ignored.
- **Rebirth altar** in `shrine.star`:
  - A room `altar` ("Altar of Rebirth") with a sentinel mob `priestess` ("the priestess").
  - `REBIRTH = respec.define(id = "rebirth", choices = ["race", "class"], skills = "reset", cost = 100, cooldown_pulses
    = 14400)`.
  - Its `respec_offer`:
    - Without the `standing` feature → `None`.
    - Otherwise `d = ctx.disposition(ctx.self, customer)`.
      - `d <= -50` → `"The priestess turns her back on you."`
      - `d < 0` → `{"cost": cost * 5}`.
      - Otherwise `None`.
  - Its `respec_done`: `ctx.adjust_standing(ctx.self, customer, 10)` (she warms to those she remakes).
- **Fixture additions** in `shrine_testdata.star`:
  - A `rebirth_test_room` with:
    - a `test_priestess` keeper: `REBIRTH`'s terms but `cooldown_pulses = 0`, with the altar's `respec_offer` and
      `respec_done`;
    - a trainer keeper: `trainer.open` with a `trainer.define(id = "rebirth_test_trainer", teaches =
      [trainer.teach("//skills/arcane:spark", up_to = 50, gain = 10)])`, so `practice spark` works in the room. It is a
      different attribute from the respec keeper's, so the two lookups don't collide.
  - A `keep_test_room` with a keeper whose definition says `skills = "reset"` and whose `respec_offer` returns
    `{"skills": "keep"}`.
  - Add to `FIXTURE_COMMANDS`:
    - `scorn N`: `ctx.adjust_standing(<the respec keeper here>, ctx.self, -N)`.
    - `mood`: sends `"Disposition: %d"` of that keeper toward the player.

### Test & Documentation Requirements

- `respec_test.star` additions:
  - **Reset to another class.** In `rebirth_test_room`, a human mage gets `grant 2`, `practice spark` twice, and `coins
    100`. They are spawned with `skills = {"//skills/heritage_skills:magic_missile": 25}`, or keep the starter skill
    from creation. Then they do `respec cleric`.
    - Output: `You forget everything you had learned.`, `2 practice sessions are returned to you.` and `You learn
      minor_heal.`.
    - Skills are exactly `{"//skills/heritage_skills:minor_heal": 25}`, the balance is up by 2, and the coins are gone.
  - **Reset to the same class.** `respec class mage` for a mage at the altar is allowed: skills are reset and
    `magic_missile` is back at 25.
  - **Race change at the altar.**
    - A human mage's `respec race elf` changes the race.
    - An elf mage's `respec race dwarf` is refused with `That would leave your class (Mage) unavailable.`
  - **Standing price.** After `scorn 20` the price is 500 (`You can't afford that.` with 100 coins). After `scorn 60`
    the request is refused with `The priestess turns her back on you.`
  - **`respec_done`.** After a successful respec, `mood` shows the disposition up by 10.
  - **The hook can switch policy.** At `keep_test_room`, a change keeps every skill even though the definition says
    `"reset"`.
- CHANGELOG bullet: "`respec.star`: the `reset` skill policy (refund through `trainer.refund`, forget, starter skills),
  the `"respec"` standing event, the `respec_done` hook, and a rebirth altar example."

### Exit Conditions

- [] `./gradlew :samples:heritage-plugin:test` passes, then `./gradlew check`; API files regenerated.
- [] Every new message string appears in a passing assertion.

---

## Phase 5: Documentation, end-to-end check and finalization

### Objective & Scope

- **In Scope:** heritage tutorial 04, the heritage README, `docs/PLUGINS.md`, an end-to-end `gups scenario` run, and
  bookkeeping.
- **Out of Scope:** *Do not change behavior in this phase.* Fix only bugs the scenario proves, each with a test.

### Context Working Set

- **Read / Reference Only:** everything Phases 2–4 created; `AGENTS.md` "End-to-end checks".
- **Files to Modify:** `samples/heritage-plugin/docs/tutorials/04_runtime_choice_changes_and_builtins.md`,
  `samples/heritage-plugin/docs/tutorials/README.md` (roadmap entry 04's bullets), `samples/heritage-plugin/README.md`
  (feature list, directory layout gains `world/`, tutorial 04 summary), `docs/PLUGINS.md` (the heritage section near the
  `heritage.choose` example ~540: a short "Respec builtins" subsection), `docs/CHANGELOG.md`.
- **Do Not Touch:** engine modules, `template-world/`.

### Specification & Decision Record Changes

- None. Re-read §4.9, §4.15 and §4.19 against the code.

### Implementation Blueprint

- **Tutorial 04:**
  - Keep sections 1, 2 and 6.
  - Rewrite section 3, "Clearing choices": it still works, but point to the shrine flow for in-session changes.
  - Replace the section 4 example with a new section, "A respec shrine". It covers:
    - the split between plugin and library;
    - the four builtins, with their return shapes;
    - `respec.define` and the `DEFAULT_*` constants as the world-wide defaults;
    - the menu and the request flow, with the exact order and why coins are counted before and taken after;
    - `keep` against `reset`, and the trainer ledger;
    - the `respec_offer` and `respec_done` hooks, with the altar's standing example;
    - the `"respec"` standing event and how a world's `standing_change` can treat it like `donation`;
    - how to copy `world/` into a world and pin the jar.
  - Keep section 5, "The heritage command".
  - Add a reference links section.
- **`docs/PLUGINS.md`:** a short subsection naming the four builtins and linking tutorial 04, after the
  `heritage.choose` example.
- Run `./gradlew markdownFormat` and check that code blocks kept their indentation.

### Test & Documentation Requirements

- **End-to-end scenario:**
  1. `./gradlew :samples:heritage-plugin:jar :cli:installDist`.
  2. `cp -R template-world local-world-test/world`.
  3. Copy `samples/heritage-plugin/world/` over it.
  4. Copy `samples/heritage-plugin/build/libs/heritage.jar` to `local-world-test/world/plugins/`.
  5. Pin it in `local-world-test/world/MODULE.gups` as `plugins = {"heritage": "sha256:<hash>"}`; get the hash with
     `shasum -a 256`.
  6. Write `local-world-test/scenario.star`. `gups scenario` loads `*_testdata.star`, so the fixture commands work.
     - A human warrior is spawned at `:respec_test_room` and gets `coins 10`.
     - `respec`, then `respec mage`; then `skills` lists magic_missile.
     - The same player is moved (`w.spawn_player` a second player instead if moving isn't available) to
       `:rebirth_test_room`, gets `coins 100` and does `respec cleric`.
  7. Run `cli/build/install/gups/bin/gups scenario --world local-world-test/world --package //zones/shrine
     local-world-test/scenario.star` and read the transcript.

### Exit Conditions

- [] The scenario passes, and its transcript shows the menu, the payment, the class change, the starter skill and the
  reset messages.
- [] `./gradlew check` and `./gradlew markdownCheck` pass outside the sandbox; API files regenerated.

---

## Phase 6: Finalization & Backlog Update

- In `docs/DESIGN_NEEDED.md`, mark the entry completed without deleting it: change its heading line from `### In-game
  character respec and choice changes` to exactly `### In-game character respec and choice changes (Done in Milestone
  52)`.
- Run `./gradlew check` and `./gradlew markdownCheck` outside the sandbox; both must pass.
- Run `pre_commit_code_health_safeguard`; it must pass.
- Re-read the Overview's Specification Impact list and confirm each section was updated; confirm the CHANGELOG
  subsection `### Respec shrines` covers every phase.
- Delete `local-world-test/`.
