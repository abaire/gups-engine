# Milestone 12: Track-Movement Lags in the Rules Module

The recovery lags of `advance`, `retreat` and a failed `flee` stop being fixed engine values and become rules exports,
like every other number the engine plays by. The template world shows why: a tutorial room tagged `sticky_floor` makes
stepping along the track take twice as long, and nothing but `rules/combat.star` knows it.

This milestone builds on [M11](MILESTONE_11.md), which must be merged first. It bumps neither `api_version` nor
`PluginApi.VERSION`: the engine is unreleased, so the new exports join `api_version` 1 (§1.3).

**Prerequisite: room tags.** The example reads `ctx.room.tags`. M11 delivers them (its step 4: `tags = [...]` on `room`,
checked `[a-z][a-z0-9_]*`, stored sorted, read through the room view). Before starting, confirm that `room(tags =
[...])` loads and that the room view the rules ctx hands out (`ctx.room`) has `tags`. If either is missing, stop: room
tags are built as M11 specifies, not reinvented here, and not faked with `regions`.

Read [AGENTS.md](../AGENTS.md) before starting. The source of truth is [SPEC.md](SPEC.md):

|                   Section                    |                                                     What it says about this milestone                                                      |
|----------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| **§4.6 Rules Module and the Modifier Chain** | The exports table, signatures and results, the dry run, the template ruleset, and the "Engine-only rules" paragraph this milestone removes |
| **§5.5 Intra-Room Spatial Model**            | `advance`, `retreat`, pinning and `flee`, and the lags they apply                                                                          |
| **§6.3 Script Failures**                     | Neutral results for the new exports                                                                                                        |
| §3.10 Feature Modules                        | Which exports each feature requires or forbids                                                                                             |
| §4.2 Content Declarations                    | `tags` on `room` (from M11)                                                                                                                |
| §4.16, §4.17                                 | The API model, `since`, the changelog and the API-change check                                                                             |
| §7.4                                         | The milestone row                                                                                                                          |

If this document and the spec disagree, the spec wins. Log the disagreement in [DECISIONS.md](DECISIONS.md) and point it
out in your summary.

## Design (settled)

These were decided with the maintainer and are not open:

1. **Four exports, one per lag.** `advance_lag(ctx)`, `retreat_lag(ctx)` and `retreat_fail_lag(ctx)` belong to `track`;
   `flee_fail_lag(ctx)` belongs to `combat`, because `flee` exists without the track. No combined `track_lag(ctx,
   move)`: a misspelled export is a load error, a misspelled string in an `if` is not.
2. **Required, not optional.** They follow §4.6's rule: every export of an enabled feature must be defined, and an
   export of a disabled feature must not be. There is no engine fallback.
3. **Return contract.** An `int`, the recovery lag in pulses, replacing the action's recovery (not added to anything).
   `0` is allowed and means none. A negative value is a bad return value, like a wrong type. `get_lag` gets the same
   rule for negatives.
4. **Neutral result `0`** for all four, on any run-time error (§6.3): a rules error never traps anyone.
5. **Not modifier points.** Rooms, gear and affects vary these lags through the rules ctx (`ctx.room.tags`,
   `ctx.actor.affects`, `ctx.actor.equipped(...)`, traits). Modifier points for them are a later, additive design (see
   step 8).
6. **The ctx:**

   |       Export       | `ctx.actor` |    `ctx.target` / `ctx.defender`     |         `ctx.band`          |
   |--------------------|-------------|--------------------------------------|-----------------------------|
   | `advance_lag`      | the mover   | the actor being approached           | the band **after** the step |
   | `retreat_lag`      | the mover   | the threat retreated from            | the band **after** the step |
   | `retreat_fail_lag` | the mover   | the pinner whose `retreat_check` won | `"melee"`                   |
   | `flee_fail_lag`    | the fleer   | `None`                               | `None`                      |

   `weapon`, `item` and `skill` are `None`; `deny` is invalid. Both kinds of failed flee (the roll fails, or a guard or
   `before_exit`/`before_enter` refuses the exit) call `flee_fail_lag` the same way, with no reason argument.

7. **Dry run.** Each defined lag export is called once per actor pair, like the other exports: the track ones with the
   pair's other actor as `target` at band `"melee"` (every synthetic actor stands at index 0), `flee_fail_lag` with no
   target.

8. **Kotlin.** The four `RulesEngine` methods lose their default bodies and become abstract, like every other rules
   method.

9. **Shipped rulesets.** The template's advance and retreat lags double in a room tagged `sticky_floor`; everything else
   is the old constants: advance 2, retreat 2, failed retreat 4, failed flee 4. A failed retreat is not doubled: the
   actor never moved. Every other ruleset uses the plain constants, so no existing timing changes.

## Scope

In scope:

1. The four exports in `StarlarkRules`, their `RulesExport` and `RulesExportSpec` entries, and the negative-value check
   for them and `get_lag`.
2. Abstract `RulesEngine` methods; `ctx.band` set on the three track calls.
3. Every shipped rules package and Kotlin test ruleset gains the exports.
4. The `tar_pit` room, its harness test, and a tutorial section.
5. The spec changes below, the API model, baselines and changelog.
6. `DESIGN_NEEDED.md`: remove the entry; add the modifier-points entry.

Out of scope (don't build):

- Modifier points for any lag.
- Encumbrance affecting lag (the "Encumbrance beyond stats" entry in [DESIGN_NEEDED.md](DESIGN_NEEDED.md)).
- A reason argument for `flee_fail_lag`, or any lag for a successful flee or a walk.
- Room tags themselves (M11), or using `regions` as tags.
- Retuning any formula beyond the `sticky_floor` doubling.
- An `api_version` or `PluginApi.VERSION` bump.

## Where things are today (after M11)

- `core/src/main/kotlin/gups/core/rules/RulesEngine.kt`: `advanceLag`, `retreatLag`, `retreatFailLag`, `fleeFailLag`,
  with default bodies `2`, `2`, `4`, `4` under the comment "Engine-side settings, not Starlark exports in api_version
  1". `RulesContext` takes an explicit `band`.
- `core/src/main/kotlin/gups/core/world/TrackCommands.kt`: `AdvanceAction.resolve` and `RetreatAction.resolve` call the
  track lags after `placeActor` (or on the failed `retreat_check`), without a `band`.
- `core/src/main/kotlin/gups/core/world/CombatCommands.kt`: `FleeAction.resolve` calls `fleeFailLag(RulesContext(w,
  actor))` on a failed roll and on an exit denial.
- `core/src/main/kotlin/gups/core/world/Actions.kt`: `finish` applies `maxOf(waitState, lag)`, which is why a negative
  lag today quietly acts as 0.
- `core/src/testFixtures/kotlin/gups/core/fixtures/FixtureRules.kt`: the Kotlin test ruleset.
- `script/src/main/kotlin/gups/script/Rules.kt`: `RulesCtx`, `StarlarkRules` (`run`, `intOf`, `getLag`), the
  `RulesExport` enum, `RULES_EXPORTS`, and the dry run.
- `script/src/main/kotlin/gups/script/Ownership.kt`: feature ownership of exports.
- `script/src/testFixtures/kotlin/gups/script/testing/TestWorlds.kt`, `RulesTest.kt`, `FeatureScriptTest.kt`,
  `HostTest.kt`, `DocExamples.kt`: rules packages written inline.
- `template-world/rules/combat.star`, `samples/robot-world/rules/combat.star` (`combat` and `skills` only, so it gains
  just `flee_fail_lag`).
- `template-world/zones/tutorial/rooms.star`, `tutorial_test.star`, and
  `template-world/docs/tutorials/02_rooms_and_navigation.md`.
- `local-world/` is a local copy of the template. If it's kept in the repository, bring its `rules/combat.star` in line
  with the template's; otherwise leave it.

## Work plan

Work in this order. Write each step's tests from the spec before or with the code.

### 1. Check the prerequisite and apply the spec changes

Confirm room tags as described at the top. Then apply the approved wording below to `SPEC.md`. This design was approved
by the maintainer, so these edits go straight into the spec, with no `DECISIONS.md` entry. Anything else you find
unsettled is logged in `DECISIONS.md` as usual, with proposed wording in your summary.

**§4.6, the exports table**, after the `get_lag` row:

```
| `advance_lag(ctx)` | `track` | Recovery lag in pulses after `advance`. `ctx.target` is the actor approached; `ctx.band` is the band after the step. |
| `retreat_lag(ctx)` | `track` | Recovery lag after a successful `retreat`. `ctx.target` is the threat; `ctx.band` is the band after the step. |
| `retreat_fail_lag(ctx)` | `track` | Recovery lag after a failed `retreat_check`. `ctx.target` is the pinner; `ctx.band` is `"melee"`. |
| `flee_fail_lag(ctx)` | `combat` | Recovery lag after a failed `flee`, whether the roll failed or the exit refused. `ctx.target` is `None`. |
```

**§4.6, the rules ctx paragraph:** "`target` (the other actor, if any: the defender, the pinner, the killed or the
killer)" becomes "`target` (the other actor, if any: the defender, the pinner, the killed or the killer, or the actor
advanced on or retreated from)".

**§4.6, signatures and results**, a new bullet after the `get_lag` one:

```
- `advance_lag`, `retreat_lag`, `retreat_fail_lag`, `flee_fail_lag` and `get_lag` return
  pulses, an int of at least 0; a negative value is a bad return value. The lag exports
  set the action's recovery lag, and 0 means none.
```

**§4.6, the dry run:** after "`get_lag` runs once per band," insert "the three track lag exports see the pair's other
actor as `ctx.target` at band `melee`, `flee_fail_lag` sees no target,".

**§4.6, "Engine-only rules":** delete the paragraph.

**§4.6, the template ruleset table**, after `get_lag`:

```
| `advance_lag` | 2, doubled in a room tagged `sticky_floor` |
| `retreat_lag` | 2, doubled in a room tagged `sticky_floor` |
| `retreat_fail_lag` | 4 |
| `flee_fail_lag` | 4 |
```

**§5.5:** - `advance`: "with lag set by rules (default 2)" becomes "with lag from `rules.advance_lag`". - `retreat`:
"with lag set by rules (default 2)" becomes "with lag from `rules.retreat_lag`". - When pinned: "Failure applies extra
lag (default 4) and no movement." becomes "Failure applies `rules.retreat_fail_lag` lag and no movement." - `flee`, on
failure: "lag (default 4)" becomes "lag from `rules.flee_fail_lag`".

**§6.3, the neutral-result table:** the `get_lag` row becomes `| `get_lag`, `advance_lag`, `retreat_lag`,
`retreat_fail_lag`, `flee_fail_lag` | 0 |`.

**§7.4:** add the row `| **M12** | Track-movement lags as rules exports: `advance_lag`, `retreat_lag`,
`retreat_fail_lag`, `flee_fail_lag`; the template's `sticky_floor` tar pit (§4.6, §5.5, §6.3). |`

### 2. `core`

- Remove the default bodies of the four `RulesEngine` methods and the "Engine-side settings" comment. Give each a
  one-line KDoc in the style of its neighbours, naming what `RulesContext.target` is.
- `AdvanceAction` and `RetreatAction` pass the band between actor and target, measured after `placeActor`, to the lag
  call. The failed retreat passes `Band.MELEE`. `FleeAction` is unchanged.
- `FixtureRules` implements the four methods with 2, 2, 4 and 4. Existing core tests must pass with no timing changes.

### 3. `script`

- `RulesExport`: `ADVANCE_LAG("advance_lag")`, `RETREAT_LAG("retreat_lag")`, `RETREAT_FAIL_LAG("retreat_fail_lag")`,
  `FLEE_FAIL_LAG("flee_fail_lag")`.
- `RULES_EXPORTS`: one `RulesExportSpec` each, with features `TRACK`, `TRACK`, `TRACK`, `COMBAT`, signatures
  `advance_lag(ctx)` etc., and docs taken from the spec table. The existing required/forbidden-by-feature check and the
  docs generator must pick them up with no special cases; if they don't, fix the general mechanism, not these four.
- `StarlarkRules`: the four overrides, each `run(..., ctxArgs(c), convert = lagOf(export)) ?: 0`. `lagOf` is like
  `intOf` and fails with "must be at least 0" for a negative value. `getLag` uses the same check for an int result
  (`None` still means out of reach).
- The dry run calls the new exports as design point 7 says, through the same loop as the other exports where possible.

### 4. Shipped and test rulesets

- `template-world/rules/combat.star`:

  ```python
  def _footing(ctx):
    # Rooms tagged "sticky_floor" make every step take twice as long.
    return 2 if "sticky_floor" in ctx.room.tags else 1

  def advance_lag(ctx):
    # Recovery pulses after stepping toward ctx.target.
    return 2 * _footing(ctx)

  def retreat_lag(ctx):
    # Recovery pulses after stepping away from ctx.target.
    return 2 * _footing(ctx)

  def retreat_fail_lag(ctx):
    # Recovery pulses after ctx.target, the pinner, holds you in place.
    return 4

  def flee_fail_lag(ctx):
    return 4
  ```

  Place them after `get_lag`, matching the file's comment style.

- `samples/robot-world/rules/combat.star`: `flee_fail_lag` returning 4 only (no `track`).

- `TestWorlds.kt` and every inline rules package in `script` tests and `DocExamples.kt`: the plain constants, defined
  only for the features each world enables.

### 5. The tar pit

In `template-world/zones/tutorial/rooms.star`:

- `room("tar_pit", name = "The Tar Pit", desc = "Black tar clings to your boots; every step is an effort.", size =
  SIZE.normal, flags = ["dead_end_ok"], tags = ["sticky_floor"], exits = [exit(DIR.east, to = ":hall")])`, with a
  comment saying the engine gives tags no meaning and that `rules/combat.star` (`_footing`) doubles the advance and
  retreat lag here.
- Add `exit(DIR.west, to = ":tar_pit")` to `hall`, and the room to the map comment at the top of the file.
- No spawns, so zone resets and existing tests are unaffected.

In `tutorial_test.star`, `test_the_tar_pit_slows_footwork`:

- Two players in `tar_pit` at indices 0 and 2. `advance` on the other gives `p.wait_state == 4`. After the lag runs out,
  `retreat from` gives 4 again.
- The same two steps in `hall` give 2.
- Before writing the assertions, check whether `p.do` returns while recovery is still pending (so `p.wait_state` reads
  the full lag) or after it has run down. If it's the latter, measure with `w.pulse` around the step instead, and log
  what you found in `DECISIONS.md`.

In `template-world/docs/tutorials/02_rooms_and_navigation.md`, a new section "7. Room Tags and the Rules Module" before
"Reference Links": the tar pit declaration, the `_footing` snippet, and the point that a tag means only what the world's
rules or plugins make of it. Since the rules package is world-wide, a tag such as `sticky_floor` is a contract between
the rules and every zone that uses it.

### 6. Docs

- **API model:** the four `rules:*` symbols with `since = 1`. Regenerate the golden `api.json` and
  `docs/api/api-1.json`. No `api_version` bump.
- **`docs/CHANGELOG.md`**, in `api_version 1 (unreleased)`: "Added `advance_lag`, `retreat_lag`, `retreat_fail_lag` and
  `flee_fail_lag`", saying they're required with their feature, with a before/after snippet: before, nothing in
  `combat.star`; after, the four constant functions. Mention that `get_lag` now rejects negative values.

### 7. Wrap-up

- **`docs/DESIGN_NEEDED.md`:** delete "Track-movement lags in the rules module". Under "Combat and skills", add:

  ```
  ### Track-lag modifier points
  **Spec:** §4.6 (modifier points, chain order), §5.5.
  **Today:** `advance_lag`, `retreat_lag`, `retreat_fail_lag` and `flee_fail_lag` are
  plain rules exports. Rooms, gear and affects change them only through the rules module
  reading the ctx (for example `ctx.room.tags`).
  **Questions:**
  - Should they become modifier points, so `room(modifiers=)`, `item(modifiers=)` and
    affects can change them directly?
  - One `track_lag` point with an action word, or one point per export?
  - Does `flee_fail_lag` get a point without `track`?
  - How does this relate to encumbrance changing lag ("Encumbrance beyond stats")?
  ```
- Make sure no code comment cites spec sections or decision IDs. Run `./gradlew check`.

## Tests

Name tests by behavior, as the existing ones do. Each item below is at least one test.

**Values reach the action** (`RulesTest`, with a rules package returning distinct values, for example 7, 5, 9 and 11): -
`advance` leaves `wait_state` 7; a successful `retreat` 5; a failed `retreat` (a `retreat_check` of 0) 9; a failed flee
roll (a `flee_chance` of 0) 11; a flee refused by a `before_exit` hook 11. - `0` from an export leaves no recovery lag.

**The ctx:** - `advance_lag` sees the approached actor as `ctx.target` and the band after the step (`"melee"` when the
step closes the gap, `"near"` from two apart on a large track). - `retreat_lag` sees the threat and the band after the
step; `retreat_fail_lag` sees the pinner and `"melee"`; `flee_fail_lag` sees `ctx.target == None` and `ctx.band ==
None`. - `ctx.room.tags` is readable from a lag export.

**Errors:** - A negative value, a wrong type, or a `fail()` from each export gives `wait_state` 0, one `script_error`
diagnostic against the rules package, and a `rules_error` alert. - `get_lag` returning a negative value does the same,
with its neutral 0; `None` is still out of reach.

**Features** (`FeatureScriptTest`): - With `track` on, a rules package missing any of the three track lag exports fails
to load with the usual diagnostic; with `track` off, defining one fails to load. - The same for `flee_fail_lag` and
`combat`.

**Dry run:** - A track lag export that fails only when `ctx.band == "melee"` fails the rules package's load. -
`flee_fail_lag` reading `ctx.target.name` fails the dry run.

**Core:** - Existing `TrackCommands` and `CombatCommands` tests pass unchanged against `FixtureRules`.

**Template world:** - `test_the_tar_pit_slows_footwork`, and every existing tutorial test, pass. - `gups lint` is clean
for `template-world` and `samples/robot-world`.

**API:** - The golden model; `ApiDiffTest` passes against the regenerated `api-1.json`.

## Success criteria

- [] Room tags from M11 were present before work began.
- [] Every test above exists and passes.
- [] `./gradlew check` passes, including the module-graph check, with no Kotlin warnings.
- [] Worlds without a `sticky_floor` room give byte-identical output to M11's for the same seed and inputs.
- [] `SPEC.md` carries exactly the approved wording from step 1; `CHANGELOG.md`, `docs/api/api-1.json` and the golden
  `api.json` are updated, with neither `api_version` nor `PluginApi.VERSION` bumped.
- [] `DESIGN_NEEDED.md` no longer has the track-lag entry and has the modifier-points entry.
- [] Every interpretation the spec didn't settle is logged in `DECISIONS.md`.
- [] No code comment cites spec sections or decision IDs.
