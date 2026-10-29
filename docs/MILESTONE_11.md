# Milestone 11: Character Creation, Traits and Restrictions

A world's plugin can ask new characters to make choices at creation (a fantasy term's race and class), give them
permanent traits that follow from those choices, and restrict what a character may wear, cast, learn or enter. The
engine names no choice, option or trait. A world without such a plugin plays exactly as before, which is what later
semesters, with no race or class, rely on.

This milestone builds on [M10](MILESTONE_10.md), which must be merged first. It bumps neither `api_version` nor
`PluginApi.VERSION`: the engine is unreleased, so both stay at 1 while their interfaces change (§1.3, §4.15).

Read [AGENTS.md](../AGENTS.md) before starting. The source of truth is [SPEC.md](SPEC.md):

|                        Section                        |                                                        What it says about this milestone                                                         |
|-------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| **§4.19 Character Creation, Traits and Restrictions** | The whole design: choices, answers, traits and their effects, restrictions, changes, script access                                               |
| **§4.15 Plugins**                                     | `addTrait`, `setCreation`, `addRestriction`, the new types, the facade verbs and views, registration rules, `samples/heritage-plugin`, the guide |
| **§5.12 Sessions and Authentication**                 | The creation steps, missing choices at login, `--dev`                                                                                            |
| §1.6, §1.7                                            | Glossary entries; what stays out of scope                                                                                                        |
| §2.2                                                  | The new sample project                                                                                                                           |
| §3.3 Entities                                         | Actor `grantedTraits` and `traits`, player `choices`; base and effective maxima and resists; `tags`                                              |
| §3.4 Prototypes                                       | Mob prototype traits following a reload                                                                                                          |
| §3.6, §5.11                                           | The player payload; the new immediate-capture trigger                                                                                            |
| §3.8 Invariants                                       | Invariant 3 against the effective maximum                                                                                                        |
| §4.2, §4.3                                            | `tags` on `room`, `item` and `skill`; `traits` on `mob`                                                                                          |
| §4.5 The `ctx` Object                                 | `choices`, `traits`, `has_trait`, `base.max_<vital>`, `base.resists.<type>`, `tags`                                                              |
| §4.6 Rules Module                                     | `starting_stats` after choices; traits in the chain order; the dry run; the `wear` restriction's place                                           |
| §4.9 Commands                                         | `score` and `@stat`                                                                                                                              |
| §4.10 Network Protocol                                | `Char.Creation`, `Char.Traits`, the web client's creation and traits panels                                                                      |
| §4.11 Kotlin Seams                                    | `CreationProvider`, `Trait`, `Restriction`, the shared creation flow, one change function                                                        |
| §4.12 Test Harness API                                | `spawn_player(choices=)`, `set_choices`, `grant_trait`, `remove_trait`                                                                           |
| §4.16 The API Model                                   | New pattern entries; plugin traits, choices and restrictions on `plugins.html`                                                                   |
| §7.1, §7.3, §7.4                                      | Engine tests; the fuzz walker's choices; the milestone row                                                                                       |

If this document and the spec disagree, the spec wins. Log the disagreement in [DECISIONS.md](DECISIONS.md) and point it
out in your summary.

## Scope

In scope:

1. The `core` seams: `CreationProvider`, `Trait`, `TraitModifier`, `Restriction`, `RestrictionPoint`, and
   `World.creation`, `World.traits` and `World.restrictions`.
2. Player `choices`, actor `grantedTraits`, mob prototype `traits`, the derived trait set, and their persistence.
3. Trait effects: effective stats, vital maxima, resists and flags; trait modifiers in the chain.
4. Restriction checks at `wear`, `enter`, `cast` and `learn`.
5. `tags` on rooms, items and skills, in declarations, views and the facade.
6. The creation flow in `core`, driven by `AccountLogin` and the `--dev` login, including missing choices for saved
   characters.
7. The change function, and the facade verbs `setChoices`, `grantTrait`, `removeTrait`, `setSkill` and `removeSkill`.
8. Script views, the rules ctx, `starting_stats` after choices, and the dry run's extra players.
9. `score`, `@stat`, the `login.creation.*` and score messages, `Char.Creation`, `Char.Traits`, and the web client's
   panels.
10. The plugin API additions (still `PluginApi.VERSION` 1), the host adapters and registration checks.
11. `samples/heritage-plugin`, its tests, and the "Character creation" part of `docs/PLUGINS.md`.
12. The harness additions and the fuzz walker's choices.
13. Docs: the API model, `plugins.html`, `CHANGELOG.md` and the baselines.

Out of scope (don't build):

- Rerolls or point-buy at creation.
- Choices, traits or restrictions defined in Starlark. Content only names plugin traits on mob prototypes.
- Rerolls and point-buy (a [DESIGN_NEEDED.md](DESIGN_NEEDED.md) entry).
- In-game ways to change choices (shrines, trainers, a `respec` command). The engine provides only the verbs; the
  sample's `heritage.choose` builtin is as far as it goes.
- A player-facing `learn`/`practice` flow, a script verb that teaches skills, or anything else from the "Acquiring
  skills" entry in [DESIGN_NEEDED.md](DESIGN_NEEDED.md).
- Resist deltas on items or affects, and `cast_allowed` modifiers on traits.
- Using the heritage plugin in `template-world`.
- An `api_version` or `PluginApi.VERSION` bump.

## Where things are today (after M10)

- `core/src/main/kotlin/gups/core/model/Entities.kt`: `Actor`, `Player`, `Mob`. `stat(name)` sums base, affect,
  equipment and encumbrance deltas; `max(vital)` reads `Vital.max`; `hasFlag` reads affects only.
- `core/src/main/kotlin/gups/core/model/Schema.kt`: `Vital` (`current`, `max`, `belowZeroSince`). About 28 reads of
  `.max` across 17 files in `core`, `script` and `plugin-api` decide caps and displays; each needs to choose base or
  effective.
- `core/src/main/kotlin/gups/core/world/World.kt`: `spawnPlayer` and `startingStatsOf`. `Reconcile.kt` and `Restore.kt`
  rebuild players and items.
- `core/src/main/kotlin/gups/core/world/Rules.kt` and `core/rules/RulesEngine.kt`: the rules calls and the modifier
  chain; `SkillCommands.kt` asks `castAllowed`.
- `core/src/main/kotlin/gups/core/world/Movement.kt` and `Hooks.kt`: `before_enter` dispatch.
- `core/src/main/kotlin/gups/core/world/KernelCommands.kt`: `wear`, `wield`, `score`. `Tools.kt`: `@stat`.
- `core/src/main/kotlin/gups/core/world/Login.kt`: the `--dev` login. `LoginMessages.kt` and `KernelMessages.kt`: the
  catalog entries for login and play.
- `core/src/main/kotlin/gups/core/world/Gmcp.kt`: the state packages and their cache.
- `auth/src/main/kotlin/gups/auth/AccountLogin.kt`: the account login state machine (`Step`, `Conn`, `menuChoice`,
  `play`, `createCharacter`, `enter`).
- `persist/src/main/kotlin/gups/persist/Codec.kt`: player payloads and `format_version`.
- `script/src/main/kotlin/gups/script/Declarations.kt`, `Views.kt`, `Ctx.kt`, `Rules.kt`: declarations, views, the rules
  ctx and the dry run. `ApiModel.kt`, `DocExamples.kt`: the model and its examples.
- `harness/src/main/kotlin/gups/harness/Handles.kt` (`spawn_player`) and `FuzzWalker.kt`.
- `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`, `Facade.kt`, and `host/PluginHost.kt`, `host/Facade.kt`: the
  plugin interface, the facade and the host.
- `cli/src/main/kotlin/gups/cli/DocsRenderer.kt`: `plugins.html`.
- `webclient/src/main/resources/gups/webclient/app.js`, `panels.js`, `style.css`: the web client.
- `samples/weather-plugin`: the existing sample, a model for the new one's layout, Gradle setup and tests.

## Work plan

Work in this order. Write each step's tests from the spec before or with the code. Don't work backward from what the
code happens to do.

### 1. Re-read the spec and refine it if needed

Read every section listed above, then the code. If the spec leaves something unsettled that you need, don't edit
`SPEC.md`. Instead:

- Log your choice in `DECISIONS.md` (next free `D-NNN`, status `open`).
- Write the exact proposed SPEC wording in your final summary, for the maintainers to approve.

Things that probably need a decision entry:

- The exact `login.creation.*` message ids, their placeholders and which are required, and the `score` lines' ids.
- How a refused `enter` behaves for `flee`, `wander` and `pursue` (the spec says "as a `before_enter` veto"; write down
  what that is today).
- Whether `@goto` runs `before_enter` (and so `enter` restrictions); follow the code and log it.
- Where exactly the `cast` restriction runs in the cast lifecycle, given every place `castAllowed` is asked (first
  validation, re-validation, mob `act_cast`).
- The text `@stat` prints for traits and answers.
- How the effective maximum is cached (see step 3).

### 2. `core` seams and player state

Add the §4.11 types to `core` (a new `core/model/Creation.kt` or similar). `World` gets `creation: CreationProvider?`,
`traits: Map<String, Trait>` and `restrictions: Map<RestrictionPoint, List<Restriction>>`, empty by default, so every
existing test is untouched until it opts in.

`Actor` gets `grantedTraits: Set<String>` (as saved, unknown ones included) and a derived `traits: List<Trait>`, in name
order without repeats: for a player, the known option traits of its known answers; for a mob, its prototype's `traits`;
plus known granted traits for both. `Player` also gets `choices: Map<String, String>`. Recompute `traits` on load, on
spawn, on every change and on reload, and never lazily from inside a hot path. `RestrictionRequest` is the core
counterpart of `PluginRestrictionCall`.

`MobProto` gets `traits: Set<String>`. The `mob(traits = [...])` argument is checked at load against the traits the
world's plugins registered, with a suggestion for a misspelling, so the plugins must be registered before packages load
in every tool (they already are; check `gups lint`'s path). Reconciliation replaces prototype traits on reload and keeps
granted ones.

Persistence: add `choices` and `grantedTraits` to the player payload in `Codec.kt`, and `grantedTraits` (only when not
empty) to the mob delta; bump the payload `format_version`, and read older payloads as empty. Vital `max` and resists
stay base values. No SQL migration.

### 3. Trait effects

- **Stats:** `Actor.stat(name)` adds trait deltas, for players and mobs.
- **Vitals:** keep `Vital.max` as the base maximum (consider renaming it `baseMax` so every caller has to choose).
  `Actor.max(vital)` becomes the effective maximum: base plus trait deltas, at least 0. Audit every `.max` read: caps
  (`heal`, `regen`, `on_player_death`, `spawn_player` overrides, reconciliation), displays (`score`, `look`, the prompt,
  GMCP) and the invariant check use the effective value; persistence and `@stat`'s base column use the base. Losing a
  trait caps `current`; gaining one leaves it.
  - Cache the per-actor sums of trait deltas (stats, maxima, resists, flags) when `traits` is recomputed, so `stat()`
    and `max()` stay cheap. Log the design.
- **Resists:** base map plus trait deltas, read by `mitigate`, views and the facade.
- **Flags:** `hasFlag` includes trait flags.
- **Modifiers:** in the chain, after room modifiers and before affects, in trait name order, labelled `trait:<name>` in
  the trace. A `TraitModifier` that throws or returns the wrong type leaves the value and records a `plugin` diagnostic
  (the adapter does the checking, step 9; `core` just treats a thrown exception the same way).
- **Invariant 3** is checked against the effective maximum.

### 4. Tags

`tags = [...]` on `room`, `item` and `skill`: checked `[a-z][a-z0-9_]*` at load, stored sorted without repeats on the
prototypes, read through the room, item and skill views and the facade. They follow the prototype on reload. Add
declaration docs and examples.

### 5. Restrictions

One `World` function runs the checks for a point and returns the first refusal: plugin id order, then registration
order; a thrown exception records a `plugin` diagnostic and allows.

- `wear`: in `wear` and `wield` (and their renames), once the slot is known and before anything moves. A refusal sends
  the message and costs no lag.
- `enter`: wherever `before_enter` is dispatched, just before it, with the destination, the origin and the direction
  (`null` for `teleport`). A refusal stops the move as a veto does.
- `cast`: just before every `castAllowed` call, with the skill and its target.
- `learn`: from the change function's `setSkill` only.
- Players see the message; mobs just fail. Staff aren't exempt.

### 6. The change function

One `World` function applies a set of answer changes, or a trait grant or removal, as §4.19 says: check availability in
choice order with the new answers, then the provider's validation; refuse with no effect, or set, recompute traits, cap
vitals, re-check worn items against `wear` (moving refused ones to the inventory with the message), and request an
immediate capture with cause `choices`. Trait grants and removals work on mobs too, which are only marked dirty; a
prototype trait can't be removed. It never runs `starting_stats`. The facade, the harness and the missing-choice flow
all call it; nothing else changes choices.

`setSkill`/`removeSkill` live beside it (the `skills` feature): `setSkill` validates the label and proficiency, runs
`learn`, then adds or replaces the skill.

### 7. The creation flow

Add one step machine in `core` (for example `core/world/CreationFlow.kt`) over a session and a name, with
`start(newCharacter, answers)`, `input(line)`, and an outcome callback (`Created(answers)`, `Completed(answers)` for a
saved character, `Cancelled`, `BackToName`). Both `AccountLogin` and `Login.kt` drive it; neither duplicates its logic.

- Steps: one per choice with an available option given the answers so far; then the confirmation. Re-evaluate
  availability after every answer, and clear the answers after a re-answered choice.
- Matching: a number, an id, a label (case-insensitive), or a unique prefix of an id or label. `help`, `help <name>`,
  `back`, `cancel`, and at the confirmation `yes`/`y`.
- Text from the catalog (`login.creation.*`), option lists padded as §5.12 shows.
- `Char.Creation` after each prompt, and `null` once when the flow ends. It's an event, so it bypasses the GMCP cache,
  and it is the only package sent before a character exists; check `OutputSink.wantsGmcp` as usual.
- Prompts count for the 60-second idle rule. Lines held while background work is pending replay into the flow as they do
  today.
- Provider failures: a `plugin` diagnostic, the §4.19 message, and back to the menu.

**`AccountLogin`:** after the name is accepted, start the flow when `world.creation` is set (otherwise create at once,
as today). On `Created`: validate (already done in the flow), re-check the name (a taken name returns to the name prompt
keeping the answers), the character limit and maintenance; then spawn with the answers set, run `starting_stats`
(current = effective max where it gives none), place the character in the provider's start room or the world's, enter,
request the `create` capture, and call `created(newCharacter = true)`. For a saved character chosen at the menu that has
a missing choice: send the "has choices to make" line and start the flow with its answers; on `Completed` apply them
through the change function, enter as usual, and call `created(newCharacter = false)`. A takeover skips this.

**`Login.kt` (`--dev`):** the same for new characters and saved ones with missing choices; `cancel` asks for a name
again.

### 8. Scripts, rules and the dry run

- Actor views (and the rules ctx's): `choices`, `traits`, `has_trait(name)`, `base.max_<vital>`, `base.resists.<type>`;
  `max_<vital>` and `resists.<type>` become effective.
- `starting_stats` runs after a player's answers and traits, or a mob's prototype traits, are set. The dry run's mobs
  carry their prototype traits, and it adds one synthetic player per option, built as §4.6 says.
- Room, item and skill views: `tags`.

### 9. Plugins

- `PluginApi.VERSION` stays 1. Update the version wording in `docs/PLUGINS.md` to match §4.15 (no bump before the first
  release). Add the §4.15 types, `PluginRegistry.addTrait`, `setCreation` and `addRestriction`, the facade verbs and the
  new view members (`role`, `flags`, `choices`, `traits`, `grantedTraits`, `hasTrait`, `skills`, base and effective
  maxima and resists; item `kind`, `wear`, `weight`, `tags`; room `tags`; `PluginSkill`).
- Registration checks, all atomic with the rest of `register`: trait names and uniqueness across plugins, schema names
  in deltas, no `stunned`, modifier points of features that are off, the choice and option rules of §4.19, option traits
  registered by the same plugin, at most one creation provider per world, `CAST`/`LEARN` needing `skills`.
- Host adapters (`gups.pluginapi.host`) from plugin objects to the `core` seams, filled in by
  `WorldPluginWiring.install`. They convert views, catch exceptions as §4.19 says, check modifier results (`Long` for
  the three combat points, a vital map with only declared vitals for `MOVE_COST`/`REGEN`), and drop unknown option ids
  from `available` with a diagnostic.
- Keep the reflection test green: no `core` or `script` type in the public surface.
- `samples/weather-plugin` needs no change; it must still build and pass.

### 10. Players see it

- `score`: effective numbers, then `<title>: <label>` per known answer and `Traits: …`, only when there are any. With no
  choices or traits the output is byte-identical to M10.
- `@stat`: base and effective columns, saved answers and granted traits (unknown marked), and the source of each
  effective trait; `tags` on rooms, items and skills.
- `Char.Traits`, a state package, only in worlds with a provider or a trait.
- Web client: the creation panel and the traits panel (§4.10), plain ES modules with JSDoc, no build step. Extend the
  headless browser test to create a character through the panel buttons when the fixture world pins the heritage jar.

### 11. `samples/heritage-plugin`

Build it as §4.15's table says, alongside `samples/weather-plugin`: `:samples:heritage-plugin`, `heritage.jar`,
depending only on `:plugin-api`, requiring `combat` and `skills`. Its tests: unit tests against fakes, a fixture world
(a `heavy_armor` item, `arcane` and `divine` skills including `magic_missile` and `minor_heal`, a `guild_mage` room,
wandering mobs with `traits = ["mage"]` and `traits = ["warrior"]`) with `*_test.star` tests through the harness, and a
telnet creation-flow test with the jar pinned.

Write the "Character creation" part of `docs/PLUGINS.md` with `HeritagePlugin` as the running example, and extend the
guide test so every Kotlin block appears in `WeatherPlugin.kt` or `HeritagePlugin.kt`.

### 12. Harness and fuzz

- `spawn_player(..., choices = {...})`, `w.set_choices`, `w.grant_trait`, `w.remove_trait`, and the player handle
  fields, as §4.12 says. Add them to the API model with examples.
- The fuzz walker draws complete answers per bot with the seeded RNG (§7.3), so fuzz output stays deterministic for a
  seed.

### 13. Docs and wrap-up

- **API model:** the new view fields and pattern entries, `tags` parameters, the harness members. Regenerate the golden
  `api.json` and `docs/api/api-1.json`. No `api_version` bump.
- **`DocsRenderer`:** traits, restrictions and the provider's choices on `plugins.html`, with the §4.16 anchors.
- **`docs/CHANGELOG.md`:** entries for `tags`, the new view fields, effective maxima and resists, `starting_stats`
  running after choices, mob `traits`, and the harness additions.
- Make sure no code comment cites spec sections or decision IDs. Run `./gradlew check`.

## Tests

Name tests by behavior, as the existing ones do. Each item below is at least one test.

**Creation flow** (core, with a Kotlin fixture provider; then over `AccountLogin`, the `--dev` login and a WebSocket
session): - A new character answers every choice by number, by id, by label and by unique prefix; an ambiguous prefix
and an unknown answer re-prompt. - `help <name>` and `help` alone; `back` from every step, including from the first
choice to the name prompt with answers kept; `cancel` from every step creates nothing. - Re-answering an earlier choice
clears later answers and changes what is offered. - A choice with no available option is skipped. - A validation refusal
returns to the last choice with the message. - A name taken during the steps returns to the name prompt, keeping the
answers. - The idle timeout closes the connection mid-flow. - The new character starts in the provider's start room, or
the default for an unknown label (with a diagnostic), has its answers and traits, and `created` ran after entering. - A
provider that throws sends the player back to the menu with the message and a diagnostic. - A saved character whose
option was removed, and one saved before the plugin existed, are asked only their missing choices at login; `cancel`
doesn't enter; a takeover isn't asked. - `Char.Creation` payloads at each step and at `confirm`, then `null` once;
nothing for clients without `Char`. - Without a provider, the telnet transcript is byte-identical to M10's.

**Traits** (core and script): - Stat, max-vital and resist deltas show in effective values and views, base values don't
move, and `mitigate` uses the effective resist. - Losing a trait caps `current`; gaining one leaves it; invariant 3
holds every pulse. - Trait flags count for `has_flag`, `silenced` and `no_regen`. - Mob prototype traits: their effects
on the mob's stats, maxima and resists; an unknown or misspelled trait name failing the package's load with a suggestion
(in the server and `gups lint`); prototype traits following a reload while granted ones stay; a trait granted to a mob
surviving a restart; a prototype trait refusing removal. - Trait modifiers sit between room modifiers and affects, in
name order, and show as `trait:<name>` in `@trace`; a throwing or wrong-typed one leaves the value and records a
diagnostic. - `starting_stats` reads `ctx.actor.choices`, and its results are base values with trait deltas on top. -
The dry run runs `starting_stats` once per option and catches an error reached only by one option. - Answers and granted
traits survive a restart; an unknown granted trait is kept and ignored; `@stat` marks it.

**Restrictions:** - Each point refuses with its message and no lag, and allows when the check allows. - `wear` refuses
before anything is swapped out. - `enter` runs before `before_enter` and covers walking, fleeing and `teleport`. -
`cast` runs before `cast_allowed`; `learn` runs in `setSkill`. - Checks see mobs and can allow them; a check reading the
role lets a builder through. - A throwing check allows and records a diagnostic. - Several plugins' checks run in id
order and the first refusal wins.

**Changes:** - `setChoices` refuses an unavailable option and a validation refusal with no effect, and applies several
changes as one. - A change recomputes traits, caps vitals, moves refused worn items to the inventory with the message,
doesn't rerun `starting_stats`, and triggers an immediate capture. - Removing an answer makes the choice missing at the
next login. - `grantTrait` and `removeTrait`, including the refusals (already granted, option trait).

**Plugins:** - Every registration rule is refused with a message and skips only that plugin: bad or duplicate trait
names (across plugins too), undeclared schema names, `stunned`, a combat modifier point without `combat`, bad choice or
option ids, reserved option ids, an option naming another plugin's trait, a second creation provider, `CAST`/`LEARN`
without `skills`. - The new facade members, including `IllegalArgumentException` for unknown ids and mobs. - No `core`
or `script` type in the public surface.

**Tags and docs:** - `tags` load, sort, deduplicate and reject bad words, and follow a reload. - `plugins.html` lists
traits, restrictions and choices with their anchors. - The golden model.

**Samples:** - `samples/heritage-plugin`: its unit, harness and telnet tests, including a `traits = ["mage"]` mob
wandering in and out of the mage guild while a warrior mob is kept out. - `samples/weather-plugin` and
`samples/robot-world` still pass, unchanged. - The fuzz walker runs a world with the heritage jar pinned,
deterministically for a seed.

## Success criteria

- [] Every test above exists and passes.
- [] `./gradlew check` passes, including the module-graph check, with no Kotlin warnings. `auth` still depends only on
  `core`, and `samples/heritage-plugin` only on `:plugin-api`.
- [] A world without a creation provider or traits (the template world, the robot world) gives byte-identical output to
  M10's for the same seed and inputs, over telnet and GMCP.
- [] A fixture world with `heritage.jar` pinned can create an elf mage over telnet and through the web client's panel,
  refuses a dwarf mage, and enforces each restriction.
- [] `docs/api/api-1.json`, the golden `api.json`, `CHANGELOG.md` and `docs/PLUGINS.md` are updated, with neither
  `api_version` nor `PluginApi.VERSION` bumped.
- [] Every interpretation the spec didn't settle is logged in `DECISIONS.md`, and any proposed SPEC wording is in your
  summary. `SPEC.md` is unedited.
- [] No code comment cites spec sections or decision IDs.
