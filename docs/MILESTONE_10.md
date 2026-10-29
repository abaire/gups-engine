# Milestone 10: Engine Feature Modules

The engine becomes a kernel plus four feature modules, `combat`, `track`, `skills` and `leveling`, which a world turns
on or off in `MODULE.gups`. A world can also rename engine commands and override every player-facing engine message, so
a post-apocalyptic term can have no magic, no track, `invoke` instead of `cast`, and "Your implant spins up" instead of
"You begin casting".

This milestone builds on [M9](MILESTONE_9.md), which must be merged first. **M9 and M10 ship in the same engine
release.** Neither bumps `api_version`.

Read [AGENTS.md](../AGENTS.md) before starting. The source of truth is [SPEC.md](SPEC.md):

|           Section            |                           What it says about this milestone                           |
|------------------------------|---------------------------------------------------------------------------------------|
| **§3.10 Feature Modules**    | The whole design: the feature table, what "off" means, renames, the per-database lock |
| §2.3 Course World Repository | `features`, `rename`, `MESSAGES.gups`; aliases and prefixes after renaming            |
| §3.3 Entities                | Which actor, room and item fields belong to which feature                             |
| §3.6, §3.9                   | `meta.world_features`, checked like the schema                                        |
| §3.8 Invariants              | Invariant 4 without `skills`                                                          |
| §4.2–§4.5                    | Feature-owned declarations, arguments, hooks, view fields and verbs                   |
| §4.6 Rules Module            | The "Feature" column: required and forbidden exports; modifier points                 |
| §4.7 Behaviors               | Feature-owned behaviors                                                               |
| §4.9 Commands                | Commands by feature; reserved exit names after renaming                               |
| §4.10 Network Protocol       | `Char.Status` fields and `Room.Bands` by feature; `Char.Schema.features`              |
| §4.15 Plugins                | `requiredFeatures`, `PluginWorld.features`, command names after renaming              |
| §4.16 The API Model          | The `feature` field, `world.html`, `--world` filtering                                |
| **§4.18 Engine Messages**    | The message catalog and `MESSAGES.gups`                                               |
| §5.3–§5.5                    | Casting, combat rounds and the track as features; death stays in the kernel           |
| §5.10 Hot Reload and Deploy  | `MESSAGES.gups` changes refused by deploys                                            |
| §7.1, §7.3, §7.4             | The feature matrix tests; migration check; milestone rows                             |

If this document and the spec disagree, the spec wins. Log the disagreement in [DECISIONS.md](DECISIONS.md) and point it
out in your summary.

## Scope

In scope:

1. `features` in `MODULE.gups`: validation, requirements, storing in `meta.world_features`, and the startup and
   `migrate-check` comparisons.
2. Splitting engine commands by feature, and guarding feature mechanics in `core`.
3. Load-time refusal of feature-owned declarations, arguments, hooks, behaviors, package kinds, rules exports and
   modifier points. Run-time refusal of feature-owned `ctx` verbs and view fields.
4. The world without `track`: one track slot per room, and bands hidden everywhere.
5. `rename` for non-`@` engine commands.
6. The engine message catalog in `core`, every player-facing engine string moved into it, and `MESSAGES.gups`.
7. Plugin `requiredFeatures` and `PluginWorld.features`.
8. Feature-aware docs (`feature` badges, `world.html`, `--world` filtering) and `help`.
9. `samples/robot-world` switched to `features = ["combat", "skills"]` and `rename = {"cast": "invoke"}`, with a
   `MESSAGES.gups`.

Out of scope (don't build):

- A registration interface for features, or making features pluggable. Features are an enabled set with guards (see step
  2). Unifying them with plugins is a separate design entry.
- World-declared events on declared channels.
- Changing features or the schema without `--wipe`.
- Translating builder and instructor `@` tool output.
- An `api_version` or `PluginApi.VERSION` bump.

## Where things are today (after M9)

- `core/src/main/kotlin/gups/core/world/EngineCommands.kt` (about 1,000 lines): every engine command. `all()` lists
  them, and `planKill`, `planCast`, `planAdvance`, `planRetreat` and the cast action are here.
- `core/src/main/kotlin/gups/core/world/Commands.kt`: `CommandDef`, `CommandAliases` (`problems()` reads
  `EngineCommands.all()`), and the "Huh?" path.
- `core/src/main/kotlin/gups/core/world/World.kt`: `step()` runs the phases. The initiative roll comes before phase 2,
  auto-attacks are phase 3, and regen and the world tick are phase 4.
- `core/src/main/kotlin/gups/core/world/Combat.kt`, `Actions.kt`, `Movement.kt` and `Behaviors.kt`: combat, casting, the
  track, and the built-in behaviors.
- `core/src/main/kotlin/gups/core/model/Spatial.kt`: track sizes and bands.
- `core/src/main/kotlin/gups/core/world/Gmcp.kt`: `Char.Status`, `Room.Bands` and `Char.Schema`.
- `core/src/main/kotlin/gups/core/world/Tools.kt`: `@where`, `@stat`.
- `auth/src/main/kotlin/gups/auth/AccountLogin.kt` and `Names.kt`: login and menu text, and reserved character names
  (these read the command names).
- `script/src/main/kotlin/gups/script/WorldModule.kt`: `MODULE.gups`.
- `script/src/main/kotlin/gups/script/Declarations.kt`, `Views.kt`, `Ctx.kt`, `Rules.kt`, `Loader.kt` and `Linter.kt`:
  declarations, views, verbs, rules exports, package kinds and the reserved-exit-name check.
- `script/src/main/kotlin/gups/script/ApiModel.kt` and `DocExamples.kt`: the API model and its examples.
- `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt` and `host/PluginHost.kt`: plugin checks and command
  registration.
- `cli/src/main/kotlin/gups/cli/DocsRenderer.kt`: the HTML reference. `MigrationChecker.kt`: gate 5. `GupsServer.kt` and
  `Deployer.kt`: startup and the deploy checks on `MODULE.gups`.

## Work plan

Work in this order. Write each step's tests from the spec before or with the code. Don't work backward from what the
code happens to do.

### 1. Re-read the spec and refine it if needed

Read every section listed above, then the code. If the spec leaves something unsettled that you need, don't edit
`SPEC.md`. Instead:

- Log your choice in `DECISIONS.md` (next free `D-NNN`, status `open`).
- Write the exact proposed SPEC wording in your final summary, for the maintainers to approve.

Things that probably need a decision entry:

- The full ownership list: which commands, hooks, declaration arguments, verbs, view fields, rules exports, modifier
  points, behaviors and GMCP fields belong to which feature. §3.10 gives the table; anything it doesn't name (for
  example `ctx.allies_near`, `wait_state`, the `summon` primitive's `summonedBy` XP rule) needs an entry.
- How `look` lists actors and items without bands.
- What `ctx.actor.fighting()` is without `combat` (refused like other feature fields, per §3.10).
- How a mob's `skills` and a player's skills persist and display without `skills` (they can't exist, since `--wipe` is
  required to turn it off).
- The catalog's id naming scheme and its grouping, and which placeholders are required.
- Whether text that gets assembled (`look` room listings, `score` and `who` layouts) is one template per line or per
  fragment. Prefer per line, with fragments as placeholders.
- Whether the robot world's warning-level "id of a feature that is off" in `MESSAGES.gups` should become an error.

### 2. The feature set in `core`

```kotlin
enum class Feature(val id: String, val requires: Set<Feature>) {
    COMBAT("combat", emptySet()),
    TRACK("track", setOf(COMBAT)),
    SKILLS("skills", emptySet()),
    LEVELING("leveling", emptySet()),
}
class Features(val enabled: Set<Feature>) { operator fun contains(f: Feature): Boolean }
```

`World` holds `features` (`WorldConfig` or a constructor argument). It defaults to all four in tests, so existing tests
are untouched until they opt in.

`WorldModule` parses `features = [...]` (all four when omitted). An unknown id, or a feature whose requirement is off,
is a `problems()` entry that stops the server.

Store `meta.world_features` alongside `world_schema`, written on the first start and by `--wipe`. Compare it at startup
and in `gups migrate-check`, with the same error style as M9.

### 3. Split commands and guard mechanics

- **Commands:** split `EngineCommands` into `KernelCommands`, `CombatCommands`, `TrackCommands` and `SkillCommands`
  (separate files), each a list of `CommandDef`.
  - Build the world's command registry from the kernel plus the enabled features, then apply renames (step 5).
  - `CommandAliases.problems()`, reserved exit names, reserved character names (`auth`) and `help` read the registry,
    never `EngineCommands.all()`.
- **Guards** (`if (Feature.X in world.features)`):
  - `COMBAT`: the initiative roll and phase 3; engagement in hostile skills, in `damage` and `stun` primitives and in
    `start_combat`; the `quit` refusal; the `aggressive`, `assist` and `flee` behaviors.
  - `TRACK`: room `trackSize` is forced to 1 when it's off. Entry index, knockback and pull, pinning, `get_lag`, and
    `look`'s band grouping are guarded. Keep one code path: with one slot every band computation yields `MELEE`. Don't
    fork the combat code.
  - `SKILLS`: casting, `CastState`, interrupts, cost payment.
  - `LEVELING`: XP awards, the level-up loop, XP loss at death, and level and XP in `score`, `who` and `@stat`.
- **GMCP:** drop `Char.Status`'s feature fields and `Room.Bands` when their feature is off. `Char.Schema.features` lists
  the enabled ids.
- **Invariants:** the invariant check asserts `casting == null` without `SKILLS` and `trackIndex == 0` without `TRACK`.

### 4. Load-time and run-time refusals in `script`

- **The loader** receives `Features`. A feature-owned declaration (`skill`), package kind (`skills`), declaration
  argument (`size`, `entry_index`, `at_index`, `range`, mob `skills`, `level`), hook, behavior (`aggressive`, `assist`,
  `flee`, and `aggressive(unless_level_above)` without `leveling`) or modifier point fails its package's load with
  "`<name>` needs the `<feature>` feature".
  - Skill `range`: `melee`/`near`/`far` need `track`. Without it `range` may be omitted (any one target in the room),
    `self` or `room`.
- **Rules exports:** with a feature off, its exports are forbidden: defining one is a load error for the rules package,
  naming the feature. With it on, they're required (M9's missing-export check, now filtered by feature). The dry run
  calls only enabled exports. `on_player_death`'s `xp_loss` without `leveling` is a rules error.
- **Views and `ctx`:** feature-owned fields and verbs raise "`<name>` needs the `<feature>` feature". That covers
  `track_index`, `band_of`, `level`, `xp`, `skills`, `casting`, `is_casting`, `fighting`, `start_combat`, `stop_combat`,
  `enemies_near`, `act_attack`, `act_cast`, `act_advance`, `act_retreat`, and the `index` arguments.
  - The rules `ctx.band` is `None` without `track`.

### 5. Renames

- `WorldModule` parses `rename = {"old": "new"}`, validated per §3.10:
  - the old name must be an engine command of the world
  - the new name must match `[a-z][a-z0-9_]*` and must not be a direction, another command or an alias
- The registry exposes each command under its new name only. Aliases and prefixes in `MODULE.gups` name commands by
  their new names. The template keeps `c` → `cast`; the robot world uses `invoke`.
- A freed old name is available to `global_command` and plugin commands.
- Mob `act(mob, "cast ...")` command lines use the world's names. Behavior proposals' `commandLine` uses the renamed
  word.

### 6. The message catalog

- Add a `Messages` catalog to `core`, for example `core/world/Messages.kt`. Each entry `Msg(id, feature?, placeholders,
  required, default)` is defined in Kotlin, next to its feature, with the default text the spec gives.
- `World.msg(id, vararg args)` formats the world's text, which is the override or the default.
- Move every player-facing engine string to the catalog: the kernel, the four features, and `auth`'s login and menu text
  (`auth` reads the catalog through `core`). `@` tool output stays as it is.
  - A test fails if a player-facing `send`/`echo` in `core` or `auth` uses a string literal instead of a catalog entry.
    Use a source scan or an `OutputSink` capture across a scripted session, whichever is reliable; log the choice.
- Add a reader for `MESSAGES.gups` in `script` (like `WorldModule`), read at startup only. It stops the server on:
  - an unknown id (with a suggestion)
  - an unknown placeholder
  - a missing required placeholder
  - a non-string value

  An id of a feature that is off is a warning.

- Deploys: refuse a deploy that changes `MESSAGES.gups`, exactly as for `MODULE.gups`.

### 7. Plugins

- Add `GupsPlugin.requiredFeatures` (default empty) and `PluginWorld.features`. The host checks requirements in its
  checks list (§4.15) with the error `plugin '<id>': needs the '<feature>' feature`.
- Facade members of features that are off throw `IllegalStateException` naming the feature (`PluginActor.level` without
  `leveling`).
- Plugin command clashes are checked against the renamed registry.
- Update `docs/PLUGINS.md` and the sample plugin (declare nothing, or what it really needs).

### 8. Docs, `help`, samples

- **API model:** add a `feature` field to every feature-owned entry, plus the message catalog list (§4.16).
- **`DocsRenderer`:** add `feature:` badges and `world.html`. With `--world`, leave out entries of features that are
  off, and show commands by their renamed names.
- **`help`:** lists only the world's commands, under their renamed names.
- **`samples/robot-world`:** `features = ["combat", "skills"]`, `rename = {"cast": "invoke"}`, aliases and prefixes
  using `invoke`, a `MESSAGES.gups` reskinning the cast, condition and combat lines, and no `size`, `range`, `level` or
  band-dependent content. Its tests cover `invoke`, the reskinned text, and "Huh?" for `advance` and `cast`.
- **`template-world/MODULE.gups`:** add a commented `features` line listing all four, and a commented `rename` example.
- **`docs/CHANGELOG.md`:** entries for `features`, `rename`, `MESSAGES.gups`, the feature-owned refusals, and
  `requiredFeatures`.
- **API baseline:** regenerate the golden `api.json` and `docs/api/api-1.json`. No version bump.
- Make sure no code comment cites spec sections or decision IDs. Run `./gradlew check`.

## Tests

Name tests by behavior, as the existing ones do. Each item below is at least one test.

**`FeatureMatrixTest`** (core, fixture rules filtered by feature): for each of the 12 valid feature sets, a scripted
scenario. A player walks two rooms, gets, wears and uses an item, and is brought to death through `condition` by a raw
`damage` verb. Then, for each enabled feature:

- `combat`: a fight to the death with XP when `leveling` is on
- `track`: advance and retreat
- `skills`: a cast with warmup
- `leveling`: a level-up

It runs with the invariant check every pulse, and the scenario output is deterministic for a seed.

**Per feature, when off** (core and script): - Its commands give "Huh?" and are missing from `help`. - Each owned
declaration, argument, hook, behavior, package kind and modifier point fails to load, naming the feature. - Its exports
are forbidden in the rules package, and required when on. - Its view fields and verbs raise the feature error. - Its
GMCP package or fields are absent. - Without `track`: every actor and item is at index 0, `look` shows no bands,
`@where` is "Huh?", and an omitted skill `range` targets anyone in the room. - Without `combat`: a damaging skill lowers
vitals and can kill with no engagement, the killer is credited, and `quit` isn't refused. - Without `leveling`: no XP on
a kill, and `xp_loss` is refused.

**`MODULE.gups` / startup / cli:** - An unknown feature, and `track` without `combat`, stop the server. - A changed
feature set refuses to start until `--wipe`, and `migrate-check` fails. - Renames: the old name is "Huh?", the new one
works, aliases and prefixes use it, reserved exit and character names follow it, a freed name is usable by a
`global_command`, and each bad rename stops the server. - A deploy changing `MESSAGES.gups` is refused.

**Messages:** - Every catalog id has a default, and its default's placeholders match its declaration. - An override
changes the text in play. - Each `MESSAGES.gups` problem stops the server with a suggestion. - An id of a feature that
is off warns. - No player-facing literal remains (the check from step 6).

**Plugins:** - A plugin requiring a feature that is off is skipped with the error, and the others load. -
`PluginWorld.features`. - `PluginActor.level` throws without `leveling`. - A plugin command may take a renamed-away
name, and can't take a new one.

**Docs:** - `feature` fields in `api.json`. - `world.html` with and without `--world`. - Filtered pages and renamed
commands under `--world`. - The golden model.

**samples/robot-world:** lint, tests and fuzz pass with its features, renames and messages.

## Success criteria

- [] Every test above exists and passes.
- [] `./gradlew check` passes, including the module-graph check, with no Kotlin warnings. `auth` still depends only on
  `core`.
- [] With every feature on and no `rename` or `MESSAGES.gups`, the template world's tutorial tests pass with no
  assertion changes, and its output is byte-identical to M9's for the same seed and inputs.
- [] `samples/robot-world` runs with only `combat` and `skills`, under its renames and messages.
- [] No player-facing engine string is sent outside the message catalog.
- [] `docs/api/api-1.json`, the golden `api.json` and `CHANGELOG.md` are updated, with no `api_version` or
  `PluginApi.VERSION` bump.
- [] Every interpretation the spec didn't settle is logged in `DECISIONS.md`, and any proposed SPEC wording is in your
  summary. `SPEC.md` is unedited.
- [] No code comment cites spec sections or decision IDs.
