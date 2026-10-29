# Milestone 9: Configurable Actor Schema

Each world declares its own stats, vitals, damage types and extra perception channels in `MODULE.gups`, and the engine
stops naming any of them. Every number that gives them meaning (starting values, damage, costs, regeneration, death)
comes from the rules package, which becomes required. There is no built-in Kotlin ruleset anymore. Actors gain a life
condition (`ok`, `incapacitated`, `dead`) that the rules decide, so any vital can incapacitate or kill.

Read [AGENTS.md](../AGENTS.md) before starting. The source of truth is [SPEC.md](SPEC.md):

|               Section                |                         What it says about this milestone                          |
|--------------------------------------|------------------------------------------------------------------------------------|
| §2.3 Course World Repository         | `MODULE.gups` is required; its new arguments                                       |
| §2.4 Runtime Profiles                | `--wipe`                                                                           |
| §3.3 Entities                        | Actor stats, vitals (`current`, `max`, `belowZeroSince`), resists, condition       |
| §3.4 Prototypes, Instances, Versions | Mob reconciliation of vital maxima and stats                                       |
| §3.6 Persistence Schema              | `meta.world_schema`; payloads keyed by schema names                                |
| §3.8 Invariants                      | Invariant 3 (`current ≤ max` for every vital)                                      |
| **§3.9 World Schema**                | The whole design: declarations, names, constructors, checks, the per-database lock |
| §4.2 Content Declarations            | Mob `stats`/`vitals`/`resists`, `stat_deltas`, `DAMAGE`, `ALL`                     |
| §4.3 Skills and Spells               | `cost`, the `damage`/`heal` primitives, `interrupt`                                |
| §4.4 Hook Catalog                    | `on_damage`'s `amount` and `losses`                                                |
| §4.5 The `ctx` Object                | Schema view fields, `condition`, `below_zero_since`, the `damage`/`heal` verbs     |
| **§4.6 Rules Module**                | Required package, new and changed exports, the dry run, the template ruleset       |
| §4.7 Behaviors                       | `flee(below = vitals(...))`                                                        |
| §4.9 Commands                        | `score`; the incapacitated refusal                                                 |
| §4.10 Network Protocol               | `prompt`, `Char.Schema`, `Char.Vitals`                                             |
| §4.12 Test Harness API               | `spawn_player` and `w.damage` signatures                                           |
| §4.15 Plugins                        | The facade by name, `schema`, the `damage`/`heal` verbs                            |
| §4.16 The API Model                  | Schema pattern entries, constructors                                               |
| §5.4 Combat Rounds                   | Conditions, incapacitation, death from any vital, respawn vitals, mortally wounded |
| §5.5 Intra-Room Spatial Model        | `move_cost` as vitals                                                              |
| §5.7 Adjacent-Room Perception        | Built-in channels are `sight`, `sound`, `noise`                                    |
| §5.12 Sessions and Authentication    | New characters get `starting_stats`                                                |
| **§6.3 Quarantine**                  | Rules package exception: load failures, neutral results, never quarantined         |
| §7.1, §7.3                           | Schema tests; the fuzz walker's healing rule; migration-check gate                 |

If this document and the spec disagree, the spec wins. Log the disagreement in [DECISIONS.md](DECISIONS.md) and point it
out in your summary.

## Scope

In scope:

1. Parsing and validating `stats`, `vitals`, `damage_types`, `unarmed_damage_type`, `channels` and `prompt` in
   `MODULE.gups`. Making the file and the schema arguments required.
2. A generic actor model: stats, vitals and resists keyed by schema names, plus `condition` and `belowZeroSince`.
   Removing every hardcoded `hp`/`mana`/`move`/`armor`/stat name from `core`, `script`, `persist`, `net`, `harness`,
   `plugin-api` and the web client.
3. The typed constructors `vitals(...)`, `stats(...)`, `resists(...)` and the `DAMAGE` struct. Schema-checked
   declarations, view fields and verbs.
4. Removing the built-in Kotlin ruleset. The rules package becomes required; add `starting_stats` and `condition`;
   change `mitigate`, `move_cost`, `regen`, `encumbrance` and `on_player_death` to their new results. Add the load-time
   dry run and the neutral results.
5. Conditions: incapacitation and death decided by `rules.condition`, including deaths outside combat.
6. Persistence keyed by name, `meta.world_schema`, the startup schema check, and `--wipe`.
7. `Char.Schema`, the schema-driven `Char.Vitals`, `prompt` and `score`. The web client draws bars from `Char.Schema`.
8. The plugin facade by name. Update the sample plugin and `docs/PLUGINS.md`.
9. The template world's new `MODULE.gups` and `rules/combat.star`, and updated tutorial content and tests.
10. A new sample world, `samples/robot-world`, loaded, tested and fuzzed in CI.

Out of scope (don't build):

- Feature modules, `features`, `rename`, `MESSAGES.gups` and the message catalog. Those are [M10](MILESTONE_10.md). In
  M9 every mechanic stays on. `level`/`xp` stay ordinary engine fields, and engine messages stay hardcoded strings,
  apart from the new ones this milestone adds.
- Any player choice at character creation.
- Verbs that change stats or maxima after creation (level-up gains, training).
- Resist changes from items or affects (`resist_deltas`).
- Migrating saved data across a schema change. A changed schema refuses to start; `--wipe` is the only path.
- An `api_version` or `PluginApi.VERSION` bump. Nothing is released, so both stay at 1. Regenerate `docs/api/api-1.json`
  and the golden `api.json` instead (step 10).

## Where things are today

- `core/src/main/kotlin/gups/core/model/Entities.kt`: `Actor` with typed `hp/maxHp/mana/maxMana/move/maxMove/armor`,
  `baseStats` seeded from `DEFAULT_STATS`, and `stat(name)`.
- `core/src/main/kotlin/gups/core/model/Protos.kt`: `MobProto` (`hp = 10`, `mana = 0`, `move = 100`),
  `STAT_NAMES`/`DEFAULT_STATS`, the `Regen` keys, `ModifierPoint`.
- `core/src/main/kotlin/gups/core/model/Spatial.kt`: `Channel` (with `SPELL`) and `DamageType` (with built-in
  constants).
- `core/src/main/kotlin/gups/core/rules/RulesEngine.kt` and `DefaultRules.kt`: the `RulesEngine` interface and the
  built-in Kotlin ruleset.
- `core/src/main/kotlin/gups/core/world/Rules.kt`: how the world calls the rules, including the fallback.
- `core/src/main/kotlin/gups/core/world/World.kt`: player and mob creation (sets maxima), regen in phase 4, death.
- `core/src/main/kotlin/gups/core/world/Combat.kt`: auto-attacks, `mitigate`, death and respawn.
- `core/src/main/kotlin/gups/core/world/Movement.kt`: `move_cost`, "You are too exhausted."
- `core/src/main/kotlin/gups/core/world/Actions.kt` and `EngineCommands.kt`: skill cost validation and payment, `score`,
  `healthWord`.
- `core/src/main/kotlin/gups/core/world/Gmcp.kt` and `Sessions.kt`: `Char.Vitals` and the prompt.
- `core/src/main/kotlin/gups/core/world/Bubble.kt`: dormant recovery (`full`, `regen`).
- `core/src/main/kotlin/gups/core/world/Reconcile.kt` and `Restore.kt`: mob reconciliation and restore.
- `core/src/main/kotlin/gups/core/world/Invariants.kt`: invariant 3.
- `script/src/main/kotlin/gups/script/WorldModule.kt`: `MODULE.gups` parsing (`SPEC`, `problems()`).
- `script/src/main/kotlin/gups/script/Declarations.kt`: `mob`, `item`, `skill`, `stat_deltas`, effect primitives,
  behaviors.
- `script/src/main/kotlin/gups/script/Views.kt` and `Ctx.kt`: actor views and verbs.
- `script/src/main/kotlin/gups/script/Rules.kt`: `StarlarkRules`, `RulesCtx`, `regenValue`, the fallback to
  `DefaultRules`.
- `script/src/main/kotlin/gups/script/ApiModel.kt`: the API model.
- `persist/src/main/kotlin/gups/persist/Codec.kt`: player and mob payloads.
- `persist/src/main/kotlin/gups/persist/Database.kt`: `meta` keys.
- `harness/src/main/kotlin/gups/harness/Handles.kt` and `FuzzWalker.kt`: `spawn_player`, `w.damage`, the walker's
  healing.
- `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt` and `host/Facade.kt`: `PluginActor`, `PluginWorld.damage/heal`.
- `samples/weather-plugin`: the sample plugin and its tests (`WeatherPluginTest` fakes `maxHp`).
- `webclient/src/main/resources/gups/webclient/panels.js`: the vitals bars.
- `template-world/MODULE.gups` and `template-world/rules/combat.star`: the template. `combat.star` already mirrors
  `DefaultRules` and reads `stats["dex"]`.
- `cli/src/main/kotlin/gups/cli/`: `serve` flags (`--fresh`), `migrate-check`, and the `docs` command.
- Tests that use `DefaultRules`: `core/.../world/Fixture.kt`, `InitiativeTest.kt`, `script/.../RulesTest.kt`,
  `persist/.../PersistFixture.kt`, `cli/.../CliTestFixtures.kt`.

## Work plan

Work in this order. Write each step's tests from the spec before or with the code. Don't work backward from what the
code happens to do. `./gradlew check` won't pass between steps 2 and 7; keep the tree compiling, and get it green before
step 8.

### 1. Re-read the spec and refine it if needed

Read every section listed above, then the code listed under "Where things are today". If the spec leaves something
unsettled that you need, don't edit `SPEC.md`. Instead:

- Log your choice in `DECISIONS.md` (next free `D-NNN`, status `open`).
- Write the exact proposed SPEC wording in your final summary, for the maintainers to approve.

Things that probably need a decision entry:

- The exact list of reserved schema names (every actor view field, plus `base`, `resists`, `condition`,
  `below_zero_since`). Generate it from the view class, not by hand, and say so.
- The `belowZeroSince` rule for a vital that is exactly 0: the spec says "below 0", so 0 clears it. Keep that, and test
  it.
- How the dry run builds its synthetic actors: their room, weapon and target. For example, a fixture room outside the
  world, an unarmed attacker and a defender from the same `starting_stats` result. Also which band it passes to
  `get_lag`: one call per band.
- What `ctx.damage` with `vital` but a type the schema doesn't declare reports.
- The wording of the startup schema-mismatch error and of the `--wipe` summary.
- The incapacitated messages for onlookers in `look` (if any) and for `healthWord`.

### 2. The schema model in `core`

- Add a `WorldSchema` value in `core` (for example `core/model/Schema.kt`):
  - ordered `stats` and `vitals`, each `name` + `label`
  - ordered `damageTypes`, plus `unarmedDamageType`
  - `channels` (declared only)
  - `prompt`
  - helpers: `hasStat`, `hasVital`, `hasDamageType`, and `suggest(name)` (reuse the existing spelling-suggestion helper)
  - the canonical name lists that `meta.world_schema` stores
- Turn `Channel` from an enum into a value class: built-in `sight`, `sound` and `noise`, plus declared names. Remove
  `SPELL`. `DamageType` loses its built-in constants.
- `Actor`:
  - Replace the typed vitals with `vitals: Map<String, Vital>`, where `class Vital(var current: Int, var max: Int, var
    belowZeroSince: Long?)`.
  - Replace `armor` with an ordinary stat.
  - Add `condition: Condition` (`OK`, `INCAPACITATED`, `DEAD`).
  - Make `baseStats` start empty. A world sets every declared stat through `starting_stats`; an undeclared stat reads as
    0\.
- Every write to a vital goes through one world function, for example `World.changeVital(actor, name, delta, source)`.
  It:
  - clamps at the maximum
  - updates `belowZeroSince`
  - marks the actor dirty
  - asks `condition` (step 4)

  Make the fields' setters `internal`, so nothing else can bypass it.

- `World` holds the schema (`WorldConfig` or a constructor argument).

- `MobProto`: `stats: Map<String, Int>`, `vitals: Map<String, Int>` (maxima) and `resists: Map<String, Int>`, all
  defaulting to empty. Remove `hp`/`mana`/`move`/`armor`.

- Delete `STAT_NAMES` and `DEFAULT_STATS`.

### 3. `MODULE.gups` and declarations in `script`

- `WorldModule`:
  - Parse `stats`, `vitals` (`stat(name, label)`/`vital(name, label)` or plain strings), `damage_types`,
    `unarmed_damage_type`, `channels` and `prompt`.
  - Make the file and the schema arguments required.
  - Report every §3.9 naming rule as a `problems()` entry, so a problem stops the server.
  - Add `stat` and `vital` to the API model as settings builtins.
- Predeclare `vitals`, `stats`, `resists` and `DAMAGE` in every package and test file. The constructor values:
  - are immutable
  - read back like structs (`cost.mana`)
  - compare by value, so the version-bump lint keeps working
  - refuse unknown keywords with a suggestion
  - refuse non-int values
- Everywhere a schema-keyed dict was accepted, accept only the constructor. A plain dict fails with "use stats(...)" (or
  `vitals`/`resists`). That covers:
  - mob `stats`/`vitals`/`resists` (and remove the `hp`/`armor` arguments)
  - item and affect `stat_deltas`
  - `apply_affect`
  - skill `cost`
  - `flee(below = ...)`
- Check item `damage_type`, the `damage(dice, type)` primitive, `heal(dice, vital)`, channels in `senses_blocked`,
  `set_exit_senses_blocked` and skill `channels` against the schema at load. `ALL` becomes the built-in channels plus
  the declared ones.
- The `flee` behavior takes `below = vitals(...)`. It flees when any listed vital is at or below that percentage of its
  maximum.

### 4. The rules package

- Make `RulesEngine` match §4.6. New methods: `startingStats`, `condition`. Changed results:
  - `mitigate`, `moveCost` and `regen` return `Map<String, Int>`
  - `encumbrance` returns stat deltas
  - `onPlayerDeath` adds `vitals`
  - `interruptCheck` gets the total loss
- Delete `DefaultRules` from `core/src/main`. Move a Kotlin ruleset for engine tests into `core` test fixtures, using
  Gradle's `java-test-fixtures` plugin, and point every test listed above at it. Give it two schemas:
  - `FixtureSchemas.FANTASY` (today's names)
  - `FixtureSchemas.ROBOT` (`integrity`/`power`/`rads`, stats `might`/`agility`/`tech`, damage types `kinetic`/`energy`,
    no `hp` or `mana`)

  Add a `FixtureRules(schema)` that works for both.

- `StarlarkRules`:

  - Missing required exports are a load error for the rules package, naming each one.
  - Remove every fallback to Kotlin.
  - Convert results through the constructors and check names against the schema.
  - On a run-time error, return the neutral result from §6.3, write a `script_error` diagnostic and raise a rate-limited
    `rules_error` alert. That alert replaces `rules_fallback`; rename it everywhere, including the alert docs.
  - Never quarantine the rules package.
- **The dry run:** after the rules package loads (startup, `ScriptHost.reload`, deploy staging, `gups lint`), call every
  export against synthetic actors, as §4.6 describes. A failure fails the rules package's load. At startup that stops
  the server; in a reload or deploy the old rules stay live.
- **Calling `starting_stats`:** from player creation (§5.12) and every mob spawn (spawn rules, `ctx.spawn`, `summon`,
  `@spawn`), before the actor enters the world and before `on_spawn`. Missing `current` values default to the maximum.
- **Asking `condition`:** after every vital change, through the single function from step 2, and for every simulated
  actor in phase 4 after regen. Then act on the result:
  - `INCAPACITATED`: the treatment in §5.4. Reuse the stun machinery for "can't act", held recovery and paused or
    interrupted casts, but don't add a `stunned` affect.
  - `DEAD`: the existing death path, still deferred to the end of the tier in phases 2 and 3, where it re-asks
    `condition` before processing.
  - A rules error keeps the previous condition.
- **Death outside combat:** a vital dropping in phase 1 (an affect's `on_tick`), phase 4 (regen) or from a hook must
  kill too. Make sure death processing doesn't assume an engagement.
- **Respawn:** set the vitals `on_player_death` returns and condition `OK`, then ask `condition`. If it is still `DEAD`,
  treat that as a rules error and set every vital to its maximum.

### 5. Combat, movement, skills and regen against the schema

- **Auto-attack:**
  1. `to_hit`
  2. `damage`
  3. `mitigate(ctx, amount, weapon damage type or unarmed_damage_type)`
  4. subtract each loss through `changeVital`
  5. fire `on_damage` once, with `amount` = total and `losses` = a `vitals(...)` value
- **The `damage(dice, type)` primitive:** the same path from `mitigate` on.
- **The `ctx.damage(target, amount, type, vital, source)` verb:** raw; one loss, no `mitigate`.
- **`heal`** (the primitive and the verb) names its vital.
- **Skill costs:**
  - validated when every named vital's `current` is at least the cost, refused otherwise with "You don't have enough
    <label>."
  - paid at resolve through `changeVital`
  - `cast_allowed` sees the cost as `ctx.skill.cost`
- **Movement:** spends `move_cost`'s vitals. If any vital can't cover its cost, refuse with "You are too exhausted."
- **Regen** (phase 4, and dormant catch-up in `Bubble.kt`): add the `regen` result per vital, capped at the maximum,
  through `changeVital`.
- **Dormant `full` recovery:** sets each vital to its maximum.
- **Interrupts:** `interrupt_check` gets the total loss. The engine reads only `interrupt.stun` itself.
- **Tick-lane refusal:** an incapacitated actor's tick-lane commands are refused with "You can't do that while
  incapacitated."; fast-lane commands still work.

### 6. Views, verbs and the harness

- **Actor view:**
  - Generate the schema fields: each stat (effective), `base.<stat>`, each vital, `max_<vital>`, and `resists.<type>`.
  - Add `condition` and `below_zero_since(name)`.
  - An undeclared field raises an error with a suggestion.
  - Remove the old `stats` dict, `armor`, and the typed vital fields that are no longer schema names. With the
    template's schema, `ctx.actor.hp`, `max_hp`, `dex` and `armor` still work.
- **Rules `ctx`:** uses the same views. `ctx.band` is unchanged in M9.
- **Harness:** `spawn_player(name, at, level, vitals, stats, skills, index)` (the constructors, overriding
  `starting_stats`), and `w.damage(target, vital, amount=None, pct=None, type)`.
- **Fuzz walker:** applies the healing rule from §7.3, gate 4.

### 7. Persistence, `--wipe` and the startup check

- **`Codec.kt`:**
  - Store stats, vitals (`{"cur", "max"}`, plus `"belowZeroSince"` when set) and resists as objects keyed by name, and
    the condition (players and mobs).
  - Raise `format_version`. No conversion of old payloads: refusing to read an older `format_version` with a clear error
    is fine.
  - Mob deltas compare with the prototype's declared values.
- **`meta.world_schema`:** written on the first start and by `--wipe`. Any later start compares it with `MODULE.gups`.
  On a difference, refuse to start, list the added and removed names per kind, and point to `--wipe`. Labels, `prompt`
  and `unarmed_damage_type` aren't compared.
- **`--wipe` for `gups serve`:**
  1. take a `prewipe` backup (add the kind to `Backups.kt`)
  2. delete every `players` row and every item row, and `item_events`
  3. ignore saved world state as `--fresh` does
  4. record the current schema
  5. keep accounts, restrictions, `session_tokens` and `invites`

  It is mutually compatible with `--fresh` (implies it).

- **`gups migrate-check`:** fails when the snapshot's `world_schema` differs from the PR's.

- **Sanitized snapshots:** keep `world_schema`.

### 8. Clients and output

- **Prompt:** generated from `prompt`. The default is `<` + `{v}v` per vital + `> `.
- **`Char.Vitals`:** `{"<v>": cur, "max<v>": max}` in schema order.
- **`Char.Schema`:** sent once when a session takes a character, and when the client adds support for it (§4.10). Add
  the package to the GMCP tests and the docs model. Its `features` field lists all four ids in M9.
- **`score`:** lists stats and vitals by label in schema order, then resists by damage type, then level and XP.
- **`@stat`:** shows the same in its own format, plus condition and `belowZeroSince`.
- **`healthWord`** (`look`'s condition words): its source is the template's "health" idea, so tie it to `condition`
  (`incapacitated`, `dead`) and otherwise to the lowest vital fraction. Log the choice as a decision.
- **Web client (`panels.js`):** draw one bar per vital from `Char.Schema` (label, then `cur/max`), in schema order. Keep
  it plain ES modules with JSDoc.

### 9. Plugins

- Make `PluginActor`, `PluginWorld.damage/heal`, `PluginWorld.schema` (`PluginSchema`, `PluginNamed(name, label)`) and
  the undeclared-name errors match §4.15. Keep `level`.
- Update `samples/weather-plugin` (main and tests) and `docs/PLUGINS.md`. The reflection test that keeps `core`/`script`
  types out of `gups.pluginapi` must still pass.

### 10. Template world, sample world, docs

- **`template-world/MODULE.gups`:** the fantasy schema from §3.9, with a comment per argument in the file's existing
  teaching style.
- **`template-world/rules/combat.star`:** implement every export in §4.6's template ruleset table, including
  `starting_stats`, `condition` and the new result types. Read stats as `ctx.actor.dex`. Update its header comment
  (there's no built-in ruleset to mirror).
- **Tutorial zone and its tests:** mechanical updates (`hp =` → `vitals = vitals(hp = ...)`, `stat_deltas = stats(...)`,
  `heal(dice, "hp")`, `flee(below = ...)`, `w.damage(..., vital = "hp")`). Behavior must not change: the existing
  assertions should pass as they are.
- **`samples/robot-world`:** a course world with the robot schema from §3.9 and every feature on (M10 turns some off).
  Give it its own `rules/combat.star` and a zone of 6–10 rooms with tests showing:
  - psychic damage going to `sanity`
  - `life` or `sanity` at 0 incapacitating, and −10 killing
  - `rads` killing after 60 pulses at or above a threshold (use `below_zero_since` on a complementary vital, or a
    threshold, whichever reads better)
  - a randomized `starting_stats`

  Add a CI step (or a `check` dependency) that runs `gups lint`, `gups test` and `gups fuzz` on it.

- **API model:** the pattern entries for schema fields, the constructors, `DAMAGE`, `Char.Schema`, the rules exports'
  new signatures and feature-free docs. Every new entry needs a doc example (§4.16).

- **API baseline:** regenerate the golden `api.json` and `docs/api/api-1.json` (`gups docs --update-baseline`). No
  version bump.

- **`docs/CHANGELOG.md`:** entries under `api_version 1 (unreleased)` with before/after snippets: the schema arguments,
  the constructors, the actor view fields, the removed mob arguments, `heal`/`damage` signatures, `flee(below)`, the
  rules exports (required, `starting_stats`, `condition`, result types), `on_damage.losses`, `Char.Schema`, and
  `--wipe`.

- **`README.md`** and the template `README.md`, where they mention stats, rules or `--fresh`.

- Make sure no code comment cites spec sections or decision IDs. Run `./gradlew check`.

## Tests

Name tests by behavior, as the existing ones do. Each item below is at least one test.

**core** (fixture rules and both fixture schemas, unless stated): - Every vitals-related test in `CombatTest`,
`ActionLifecycleTest`, `InitiativeTest`, `BubbleTest`, `GmcpTest` and `ItemCommandsTest` runs parameterized over
`FANTASY` and `ROBOT`. - `changeVital`: - clamps at the maximum and allows negatives - sets and clears `belowZeroSince`,
with 0 counting as not below - asks `condition` exactly once per change - Incapacitation: - no actions or auto-attacks -
a cast is interrupted - recovery is held and restored - the queue is kept - it can still be targeted and stays engaged -
it regenerates - the messages at start and end - Death from a non-first vital (`sanity`), from regen (negative `rads`
regen), and from an affect tick, all outside combat. The killer is the last damager. - Time-based death: `condition`
using `below_zero_since` fires on the right world tick. - Mortally wounded: a same-tier heal of the killing vital saves
the actor (re-asked at the end of the tier). - Respawn uses `on_player_death`'s vitals. A still-dead result sets maxima
and records a rules error. - `mitigate` returning losses on two vitals: both are applied, and `on_damage` sees the total
and the breakdown. - Skill cost on a non-first vital: refused when short, with the vital's label in the message, and
paid at resolve. - `move_cost` on two vitals. - Dormant `full` and `regen` recovery over the robot schema. -
`starting_stats` for a player, and for a mob overriding prototype values; a missing `current` equals the maximum.

**script:** - `MODULE.gups`, one test per problem: - a missing file - a missing argument - a bad name - a duplicate
across stats and vitals - a reserved view name - a vital starting with `max` - a `max_<vital>` stat - an undeclared
`unarmed_damage_type` - a channel equal to a built-in - an unknown prompt placeholder - Constructors: - an unknown
keyword, with a suggestion - a non-int value - value equality - a plain dict refused with the constructor's name - a
struct read - Load-time checks: a misspelled stat in `stat_deltas`, a vital in `cost`, a damage type on an item and in
`damage(...)`, and a channel in `senses_blocked`. Each fails its package, naming the declaration. - View fields: - every
schema field - `base.` - `resists.` - a misspelled field suggesting the right one - `condition` - `below_zero_since` -
Rules: - a missing required export fails the rules package, naming it - the dry run catches a misspelled vital in
`mitigate` and a wrong return type in `regen` - the startup failure stops the world build - a failing reload keeps the
old rules - Neutral results: one test per export in §6.3's table. Each writes a `script_error` diagnostic and raises a
`rules_error` alert, and the rules package is never quarantined after 21 errors.

**persist / cli:** - A round trip of a player and a mob under the robot schema. - A changed schema refuses to start,
listing the changes. - A labels-only change starts. - `--wipe` removes characters and items, keeps accounts,
restrictions and tokens, takes a `prewipe` backup, and records the new schema. - `migrate-check` fails on a schema
difference.

**net / webclient:** - `Char.Schema` is sent once per character and on a later `Core.Supports.Add`. - `Char.Vitals` keys
follow the schema. - The prompt follows `prompt`. - The headless browser test sees one bar per vital with its label.

**plugin-api / samples:** - Facade reads by name. - An undeclared name gives the error with a suggestion. - `schema`
contents. - `damage`/`heal` with a vital. - The weather sample's tests still pass.

**template-world / samples/robot-world:** - The tutorial tests pass unchanged apart from the mechanical updates. - The
robot world's tests pass, and `gups lint` and `gups fuzz` pass on it.

## Success criteria

- [] Every test above exists and passes.
- [] `./gradlew check` passes, including the module-graph check, with no Kotlin warnings.
- [] `grep` finds no `maxHp`, `maxMana`, `maxMove`, `STAT_NAMES`, `DEFAULT_STATS`, `DefaultRules` or `Channel.SPELL` in
  any `src/main` outside test fixtures. No engine code outside the template and samples names `hp`, `mana`, `move`,
  `armor` or a stat.
- [] The engine starts, plays and fuzzes both the template world and `samples/robot-world`.
- [] `docs/api/api-1.json`, the golden `api.json` and `CHANGELOG.md` are updated, with no `api_version` or
  `PluginApi.VERSION` bump.
- [] Every interpretation the spec didn't settle is logged in `DECISIONS.md`, and any proposed SPEC wording is in your
  summary. `SPEC.md` is unedited.
- [] No code comment cites spec sections or decision IDs.
