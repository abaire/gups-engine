# Milestone 14: Experience, Levels and Level Changes

Gaining a level stops being a bare `level += 1`. Levels become a stepped, reversible change: each level can grant stat
and vital gains through the rules, the engine remembers what every level gave, and a level can be taken away again
(drained by a vampire, lost at death) with exactly those gains unwound. Worlds choose whether earned levels apply at
once or wait for an event such as a trainer. Kill XP and the XP curve move into the rules where they belong, and scripts
get verbs to award, steal and drain experience and levels.

This milestone resolves two gaps that used to be in [DESIGN_NEEDED.md](DESIGN_NEEDED.md), `Level-up gains` and
`Experience subsystem and scaling`. Both were deleted when this design was approved. It leaves two new entries there:
`Group, party and pet XP sharing` and `Skill-based advancement (worlds without character levels)`.

It builds on [M13](MILESTONE_13.md) and on the [M15](MILESTONE_15.md) spec refactor, both already merged. It bumps
neither `api_version` nor `PluginApi.VERSION`: the engine is unreleased, so the new export, hook, verbs and fields join
`api_version` 1 (§1.3). Nothing here needs a schema migration or a compatibility shim for old saves.

## How to use this document

Read [AGENTS.md](../AGENTS.md) before starting.

- **The spec is already updated.** The M14 wording was applied to [SPEC.md](SPEC.md) before M15 refactored it. Phases 1
  and 2 only check that the wording survived and make one small fix. If this document and the spec disagree, the spec
  wins. The **Design** section below supplies the detail the spec now leaves to KDoc.
- **KDoc carries the detail.** Since M15, SPEC.md describes behavior at a high level and the exact contracts live in
  KDoc on the Kotlin source that implements them. Every phase that adds a class, function, constant or rules export also
  writes its KDoc. The exit condition lists which KDoc that phase must add.
- **No spec citations in code.** KDoc and comments in `.kt` files must never contain `§`, `SPEC` or a decision ID such
  as `D-005`. State the behavior directly.
- **Run the phases in order, one at a time.** Each phase has one task. Do not start a phase until the previous phase's
  exit condition holds. Where a phase lists the files it **Touches**, that list is the scope of its one task, not a
  series of separate steps: the task only ends green once all of them are done.
- **Every phase ends green.** Every exit condition includes the **standing checks** below, even when it does not repeat
  them. A phase that changes an interface also updates every implementer in that same phase. A phase that adds a public
  script or plugin symbol also updates the API files (see **API files** below) in that same phase.
- **Write tests from this document, before or with the code.** Each phase lists the tests that must exist and pass when
  it ends. Name tests by behavior, as the existing ones do. Each bullet is at least one test.
- **Stages.** The §7.4 milestone row names three stages. **M14.1** is phases 3 to 12, **M14.2** is phases 13 to 19, and
  **M14.3** is phases 20 to 25. Phases 1 and 2 check the spec, and phase 26 verifies the whole milestone.

### Standing checks

Run every Gradle command with `BypassSandbox: true`, so that the Gradle client can reach the local daemon.

1. `./gradlew check` exits 0, with no Kotlin warnings and with the template world's tests passing.
2. `grep -rnE "§|\bD-[0-9]{3}\b|(//|\*).*\bSPEC\b" */src/main/` returns 0 matches.
3. If you edited any Markdown file, `./gradlew markdownFormat` has been run.

### API files

When a phase adds or changes a script-facing or plugin-facing symbol (a rules export, a ctx verb or field, a view field,
a hook, a harness verb or a facade member), it also does all of the following:

1. Describe the symbol where the API model reads it: its `@StarlarkMethod` doc, its `RulesExportSpec`, or its entry in
   `script/.../ApiModel.kt`. A new symbol needs no `@ApiSince`, because it joins `api_version` 1, the current version.
2. Regenerate the golden copies with `./gradlew :cli:test -Dgups.updateGolden=true` and review the diff.
3. Regenerate the baseline `docs/api/api-1.json`: run `./gradlew :cli:installDist`, then
   `cli/build/install/gups/bin/gups docs --update-baseline` from the engine checkout (README, **Releasing**).
   `ApiDiffTest` must then pass.
4. Add a line to `docs/CHANGELOG.md` under `## api_version 1 (unreleased)`. Use one `### Experience and levels` heading
   for the whole milestone and keep adding to it.

## Design (settled)

These points were decided with the maintainer and are not open. If something here is not covered and you have to make a
choice, log it in `DECISIONS.md` as usual and propose the wording in your summary.

### Scope

1. **In:**
   - Level-up gains.
   - A stepped, reversible level change with a stored gain record.
   - Deferred level application.
   - Script verbs for XP and levels.
   - The `on_level_change` hook.
   - Level-difference kill XP, a quadratic curve and within-level death XP loss, all in the template rules.
   - Rules-driven level loss at death.
2. **Out, tracked in `DESIGN_NEEDED.md`:** group, party, pet and assist XP sharing, and skill-based advancement for
   worlds without character levels. A world without character levels turns `leveling` off, and nothing in the kernel may
   then assume a `level`.
3. **Not engine concerns.** Level-difference scaling, the curve and who is penalised at death are rules. The engine adds
   no `xp_scale` modifier point, no level cap and no diminishing-returns state.

### Three separate steps

1. **Accrual:** `xp` goes up (a kill, `award_xp`) or down (`take_xp`, death).
2. **Eligibility:** the actor is eligible for level `L + 1` when `xp >= rules.xp_to_level(ctx, L)`, with `ctx.actor` set
   to that actor. `xp_to_level(ctx, level)` is the **total** XP at which `level` ends for that actor, not the XP between
   levels. Because it gets the actor, a world can give each class or race its own curve (phase 16b).
3. **Application:** a level step raises `level` by one. Every level increase goes through one function, `World.levelUp`,
   which is also what `ctx.level_up` calls. In `auto` mode the engine calls it as soon as the actor is eligible; in
   `deferred` mode only a script does.

`xp_to_level` must be strictly increasing for each actor: `xp_to_level(ctx, L) > xp_to_level(ctx, L - 1)` for every `L

> = 2`. A value that isn't is a bad return value (a rules error) and stops the loop that found it. A single call never
> applies more than `MAX_LEVEL_STEPS = 100` steps, and `pending_levels` never counts past it.

### The level mode

`module(level_mode = "auto" | "deferred")` in `MODULE.gups`; the default is `"auto"`. Setting it in a world with
`leveling` off is a load error naming the feature. The mode is not recorded per database (like `channel_priority`), so
changing it needs only a restart.

- **`auto`:** `awardXp` calls `levelUp` while the actor is eligible, so kills, `award_xp` and scripts share one path.
- **`deferred`:** `awardXp` only adds XP. When it takes `pending_levels` from 0 to more than 0, the actor is told
  `LevelingMsg.READY`. Levels are applied only when a script calls `ctx.level_up`.
- The engine adds no `train` or `rest` command. A trainer, an inn or a shrine is a script (a command, a hook or a
  plugin) that reads `pending_levels` and calls `level_up`.

### The level step up

`World.levelUp(a): Boolean` is the only code that raises a level. It first checks that the actor is eligible (it has
`pending_levels > 0`). If not, it returns `false` and changes nothing. Otherwise:

1. `a.level += 1`.
2. Call `rules.on_level_up(ctx)` with `ctx.actor = a` (the new level already set) and `ctx.rng`. There is no `old_level`
   on this ctx; it is `ctx.actor.level - 1`.
3. Validate the result. It is `None` or a struct with optional `stats` (a `stats(...)`), `max` (a `vitals(...)`) and
   `current` (a `vitals(...)`). Names are checked against the schema. All three are **deltas** and may be negative. A
   rules error counts as an empty result: the level is still raised.
4. Add `stats` to the actor's base stats and `max` to its base vital maxima. Add `current` to its current vitals. Cap
   every current vital at its new effective maximum.
5. If `stats` or `max` is not empty, store them as `a.levelGains[a.level]`, replacing any older entry. `current` is not
   stored: a heal can't be taken back.
6. Tell the actor `LevelingMsg.LEVEL_UP`.
7. Ask `condition` once for the vitals that changed (§5.4). A `current` delta that kills is the rules' business.
8. Dispatch `on_level_change` (below) with `old_level = a.level - 1` and `new_level = a.level`.
9. Mark the actor dirty and return `true`.

In `deferred` mode the caller then tells the actor `LevelingMsg.READY` if levels are still pending.

### The level step down

`World.levelDownStep(a)` is the only code that lowers a level. The caller guarantees `a.level >= 2`.

1. Take `a.levelGains.remove(a.level)`, if any. A level with no entry unwinds nothing.
2. Subtract its `stats` from the base stats and its `max` from the base vital maxima.
3. `a.level -= 1`.
4. Cap every current vital at its new effective maximum.
5. **Never owed the level just lost:** `a.xp = min(a.xp, rules.xp_to_level(ctx, a.level) - 1)`. This also removes any
   other pending levels.
6. Ask `condition` once for the vitals that changed.
7. Dispatch `on_level_change` with `old_level = a.level + 1` and `new_level = a.level`.
8. Mark the actor dirty.

`World.levelDown(a, count): Int` repeats the step `count` times and stops at level 1. If at least one step ran, it tells
the actor `LevelingMsg.LEVEL_DOWN` once and asks for an immediate checkpoint for a player. It returns the number of
steps taken. The rules are never called: the engine subtracts exactly what it stored, so gains that used `ctx.rng`
unwind exactly, and so do gains from a rules package that has since changed.

### Awarding and taking XP

- `World.awardXp(a, amount)` (it exists today): ignored unless `leveling` is on and `amount > 0`. It adds the XP and
  tells `LevelingMsg.GAIN`.
  - In `auto` mode it then calls `levelUp(a)` until that returns `false`, at most `MAX_LEVEL_STEPS` times. Levels still
    pending after the cap wait for the next award.
  - In `deferred` mode it sends `LevelingMsg.READY` when `pending_levels` went from 0 to above 0.
  - For a player whose level changed it requests an immediate GMCP `Char.Status` once, at the end.
  - The kill path calls it exactly as today (debug mobs and summons award nothing).
- `World.takeXp(a, amount): Long` removes `min(amount, a.xp)`, returns the amount removed, and sends nothing. It never
  changes `level`.
- **Pending levels:** `pendingLevels(a)` counts the consecutive `k >= 0` with `a.xp >= xp_to_level(ctx, a.level + k)`,
  up to `MAX_LEVEL_STEPS`. It stops at the first `k` for which the threshold is not strictly increasing.
- **READY is stateless and only for `deferred` worlds:** it is sent when an award takes `pending_levels` from 0 to above
  0, and after a `ctx.level_up` that leaves levels pending. Later gains do not repeat it.

### Script verbs and fields

All need `leveling` and fail with the usual feature refusal naming it. The target is any actor (player or mob, debug
included). Each takes the same arguments in the plugin facade.

|          Verb or field           |                                                                                              Meaning                                                                                              |
|----------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ctx.award_xp(actor, amount)`    | `amount >= 1` (else a script error). The kill path: gain message, then steps by mode. Returns `None`.                                                                                             |
| `ctx.take_xp(actor, amount)`     | `amount >= 1`. Removes up to `amount`, never below 0, never changes `level`, sends nothing. Returns the amount actually removed, so a theft is `got = ctx.take_xp(v, 500); ctx.award_xp(t, got)`. |
| `ctx.level_up(actor)`            | `World.levelUp`: applies one pending level and returns `True`, or returns `False`, changing nothing, if none is pending. Works in both modes.                                                     |
| `ctx.level_down(actor, count=1)` | `count >= 1`. `World.levelDown`; returns the levels actually lost (stops at 1). Works in both modes.                                                                                              |
| `actor.pending_levels`           | Read-only int on the actor view. Reading it without `leveling` is an error, like `level`.                                                                                                         |

Not added: `set_xp`, `set_level`, a `drain_level` effect primitive, a script hook for XP changes. Halving is `take_xp(a,
a.xp // 2)` and zeroing is `take_xp(a, a.xp)`.

The plugin facade gets exactly these members on `PluginWorld`, and `val pendingLevels: Int` on `PluginActor`:

```kotlin
fun awardXp(actor: PluginActor, amount: Long)
fun takeXp(actor: PluginActor, amount: Long): Long
fun levelUp(actor: PluginActor): Boolean
fun levelDown(actor: PluginActor, count: Int = 1): Int
```

They throw `IllegalStateException` naming the feature when `leveling` is off, as `level` does, and
`IllegalArgumentException` when `amount` or `count` is below 1.

The harness `w` handle gets `award_xp(actor, amount)`, `take_xp(actor, amount)`, `level_up(actor)` and
`level_down(actor, count=1)`, with the verbs' arguments and return values.

### The `on_level_change` hook

- Catalog row: `on_level_change`, no veto, attaches to `Mob`, `Room` and `Item` (carried or equipped), needs `leveling`.
- Key ctx fields: `actor` (the one whose level changed), `old_level`, `new_level`. `old_level` and `new_level` are two
  new ctx fields; they differ by exactly one, and are `None` for every other hook.
- It fires only from `levelUp` and `levelDownStep`, once per step, after the gains or the unwinding, the record update
  and the message, so the hook sees the new state. Every cause of a level change (a kill, `award_xp`, `level_up`,
  `level_down`, `level_loss`) therefore fires it exactly once per level, in order (1→2, 2→3 or 4→3, 3→2). `take_xp`
  never fires it.
- Dispatch has the `on_death` shape and the §5.6 order:
  1. The actor's own hook, if it is a mob.
  2. The room.
  3. The other actors in the room.
  4. The items in the room.
  5. The actor's carried and equipped items (its direct inventory, not the contents of containers it carries).
  6. The observers' items.

  An item or mob without the hook costs only a lookup.

- Cascades follow §5.6: depth 8 and a shared step budget.

### Death

`on_player_death(ctx)` may return `{"respawn", "vitals", "xp_loss", "level_loss"}`. `level_loss` is an int `>= 0`
(default 0), allowed only with `leveling` (like `xp_loss`). The engine order for a player is:

1. Messages, `on_death`, the corpse and the inventory, as today.
2. `outcome = rules.on_player_death(ctx)`; `xp = max(0, xp - xp_loss)`.
3. Clear affects, set the condition `ok`, set the respawn vitals (capped at the maxima as they are now), place the
   player in the respawn room, tell the room and send the room description, as today.
4. If `level_loss > 0`: `levelDown(player, level_loss)`. This runs after the respawn so the hooks hear it in the respawn
   room, from a live actor, and the vitals are capped at the lowered maxima.

The engine enforces no XP floor: it keeps `xp >= 0` and `level >= 1` and nothing else. A level loss applies the "never
owed the level just lost" rule of the step down.

### Failure handling

- `on_level_up` and `xp_to_level` errors are rules errors: reported to the instructors, with the neutral result used for
  that call.
  - A failing `on_level_up` gives **no gains**, and the level is still raised.
  - A failing `xp_to_level` gives `Long.MAX_VALUE` (it already does), so no level-up happens.
  - A failing `on_player_death` gives **no level loss**, as well as no XP loss.
- The dry run calls `on_level_up` once for each synthetic actor with its level set to 2, after `starting_stats`. The
  result is validated but not applied. The dry run also calls `xp_to_level` for levels 1 through 50 for each synthetic
  actor and fails the rules package's load if any actor's values are not strictly increasing.

### Persistence

`levelGains` is a map from the level reached to `{stats, max}` (each omitted when empty). It is stored in the player
payload and, when not empty, in the mob delta (like `grantedTraits`). A lost level asks for a player's checkpoint at
once, as a level-up does (§5.11). No migration: the payload types change directly.

### Messages

New `LevelingMsg` entries, rewordable in `MESSAGES.gups` (§4.18):

|           Id           |            Default text             |   Placeholders   |
|------------------------|-------------------------------------|------------------|
| `leveling.level_down`  | `You have fallen to level {level}.` | `level`, `count` |
| `leveling.ready`       | `You feel ready to advance.`        | none             |
| `leveling.score_ready` | `You are ready to advance.`         | none             |

`score` prints `Level N (X xp)` and, when `pending_levels > 0`, `LevelingMsg.SCORE_READY` on the next line.
`Char.Status` and `who` do not change; clients compare `xp` to `xp_next`.

### The template rules

These belong in the template ruleset (`template-world/rules/combat.star`) and the Kotlin fixture rulesets, not the
engine:

```python
def xp_for_kill(ctx):
    # 50 per victim level, scaled by how the victim's level compares to the killer's:
    # 5+ levels below pays nothing, 2+ levels above pays 140%, capped at 150%.
    diff = ctx.target.level - ctx.actor.level
    pct = _clamp(100 + 20 * diff, 0, 150)
    return 50 * ctx.target.level * pct // 100

def xp_to_level(ctx, level):
    # Total XP at which `level` ends: 1000, 3000, 6000, 10000... Each level costs 1000 * level
    # more than the last, so 20 kills of an equal-level mob earn a level at any level.
    return 500 * level * (level + 1)

def on_level_up(ctx):
    # Deterministic gains; heal by the same amount so the new points are usable.
    a = ctx.actor
    gain = vitals(hp = 5 + a.con // 4, mana = 2 + a.wis // 8, move = 5)
    return struct(max = gain, current = gain)

def on_player_death(ctx):
    a = ctx.actor
    floor = xp_to_level(ctx, a.level - 1) if a.level > 1 else 0
    return {
        "respawn": a.recall_room,
        "vitals": vitals(hp = 1),
        "xp_loss": max(0, (a.xp - floor) // 10),  # 10% of the XP earned within this level
        "level_loss": 0,
    }
```

A comment in `on_player_death` shows the punishing variant: when `xp - xp_loss` falls below the floor, return the
`level_loss` that `xp_to_level` implies. The template world stays in `auto` mode. The deferred-mode trainer is a doc
example and a test world (phase 24). Worlds that turn `leveling` off (`samples/robot-world`) define none of these.

## Where things are today

Line numbers drift; search for the names.

- `core/.../world/Combat.kt`: the XP award on a kill (about line 216), the player death branch with `xp_loss` and the
  respawn (about 240), and `World.awardXp` (about 273).
- `core/.../world/Leveling.kt`: only `LevelingMsg` (`SCORE_LEVEL`, `GAIN`, `LEVEL_UP`).
- `core/.../rules/RulesEngine.kt`: `xpForKill`, `xpToLevel` and `onPlayerDeath` on the `RulesEngine` interface (about
  173 to 181), and `PlayerDeathOutcome(respawn, vitals, xpLoss)` (about 92). The KDoc on `xpToLevel` says "required to
  reach [level]", which is wrong: it is the total XP at which `level` ends.
- `core/.../model/Entities.kt`: `Actor.level`, `xp` and `grantedTraits` (about 271 to 287).
- `core/.../world/Hooks.kt`: `HookName.ON_DEATH` (about 89); `Combat.kt` dispatches it with `dispatch(HookName.ON_DEATH,
  ...)` (about 211).
- `core/.../world/Gmcp.kt` (about 102): `Char.Status` with `xp_next = rules.xpToLevel(level)`.
  `core/.../world/KernelCommands.kt` (about 352): the `score` level line.
- `script/.../Rules.kt`:
  - The `StarlarkRules` KDoc with the `## Neutral Fallback Return Values` list (about 307).
  - `run(RulesExport.XP_FOR_KILL ...)` and its siblings (about 588).
  - The `RulesExport` enum (about 633) and the `RulesExportSpec` descriptions (about 838).
  - `dryRunRules` and its KDoc (about 995 to 1100). The KDoc already claims the `xp_to_level` 1 to 50 check, but the
    code only calls `rules.xpToLevel(1)` (about 1094). Phase 11 brings the code up to the KDoc.
- `script/.../Ownership.kt` (about 18 to 20): the `leveling` keys, such as `mob.level`, `view:actor.level` and
  `rules:xp_for_kill`.
- `script/.../Declarations.kt` (about 260): the hook catalog entry for `ON_DEATH`.
- `script/.../Ctx.kt` (about 755 to 910): verbs such as `heal` and `apply_affect`. `script/.../Views.kt` (about 147):
  the actor view `level`.
- `script/.../WorldModule.kt`: `MODULE.gups` parsing; `channel_priority` (about 187, 315 and 463) is the model for a
  module argument.
- `persist/.../Codec.kt`: the `## Entity Delta Rules` KDoc (about 60), and `grantedTraits` in the mob delta and the
  player payload (about 191, 233, 417, 482, 526 and 562).
- `plugin-api/.../Facade.kt`: `PluginWorld` (`applyAffect` about 45, `grantTrait` about 69, `setSkill` about 74) and
  `PluginActor` (about 143). `plugin-api/.../host/Facade.kt`: their implementations (about 182 and 333 to 376).
- `harness/.../Handles.kt` (about 125 to 180): `spawn_player(level=...)` and the other `w` verbs.
- Tests that call `awardXp` today: `CombatTest`, `FeaturesOffTest`, `FeatureMatrixTest`.
- Rulesets to update: `core/src/testFixtures/.../FixtureRules.kt`, `FixedRules` in `core/src/test/.../Fixture.kt`, the
  rulesets in `script/src/testFixtures/.../TestWorlds.kt`, and `template-world/rules/combat.star`.
- Doc examples: `script/.../DocExamples.kt`. The tutorial zone: `template-world/zones/tutorial/`.

## Phases

Each phase has one **Task**. A **Touches** list names the files that one task has to change to end green. It is scope,
not a sequence of separate steps.

### Phase 1: Verify the spec

**Task:** Confirm that SPEC.md still carries the M14 wording after the M15 refactor, and restore any item that is
missing.

**Checklist** (search SPEC.md for the quoted names):

- §3.3, Actor, **Progression**: `levelGains`.
- §3.6, the `players` row: `levelGains`.
- §3.9: the `level_mode` bullet.
- §3.10, the `leveling` row: `levelGains`, `on_level_up`, `on_level_change`, the four verbs and `pending_levels`.
- §4.4: the `on_level_change` row, and the `on_level_change` paragraph after the table.
- §4.5: `old_level` and `new_level` in the context fields; `pending_levels` in the **Leveling** bullet of the actor
  view; the **Leveling** row of the verb table; the `award_xp`, `take_xp`, `level_up` and `level_down` bullets.
- §4.6: the `on_level_up` export row; `level_loss` in the `on_player_death` row; the `level_loss`, `on_level_up` and
  `xp_to_level` bullets under **Signatures and results**; the `on_level_up` and `xp_to_level` sentence in **The dry
  run**.
- §4.12: the four verbs in the `w` row.
- §4.15: `awardXp`, `takeXp`, `levelUp` and `levelDown` in the facade verb list and in **Skills and leveling**.
- §4.18: `leveling.level_down`, `leveling.ready` and `leveling.score_ready`.
- §5.4: `level_loss` in the player death bullet, and the **Levelling** bullet.
- §5.11: "a level lost" among the immediate-capture triggers.
- §7.1: "reversible leveling steps" and the **Levels** bullet.
- §7.4: the **M14** row.

A missing item is restored from the **Design** section, kept as short as the surrounding refactored text. Do not re-add
anything M15 moved to KDoc: the template formula table, the §6.3 neutral table, the §4.15 Kotlin code blocks or the
per-milestone §7.1 lists. Later phases put those details in KDoc. The maintainer approved this design, so these edits go
straight into SPEC.md with no `DECISIONS.md` entry.

**Exit condition:** every checklist item is present in SPEC.md, and `./gradlew markdownCheck` exits 0.

### Phase 2: Add level loss to the §6.3 fallback sentence

**Task:** In SPEC.md §6.3, **Run time**, change "player deaths fall back to safe respawns at effective vital maxima
without XP loss" to "player deaths fall back to safe respawns at effective vital maxima without XP or level loss".

**Exit condition:** §6.3 has the new wording, and `./gradlew markdownFormat` then `./gradlew markdownCheck` exits 0.

### Phase 3: Add the gain record to the actor

**Task:** Add `LevelGain(stats: Map<String, Int>, max: Map<String, Int>)` and `Actor.levelGains: MutableMap<Int,
LevelGain>` (empty by default) in `core/.../model/Entities.kt`. Nothing writes to it yet.

**KDoc:**

- `LevelGain`: the base stat and base vital maximum deltas that one level applied, both possibly negative.
- `Actor.levelGains`: keyed by the level reached; kept so that losing a level subtracts exactly what it added; a level
  with no entry unwinds nothing; `current` vital changes are never recorded.

**Exit condition:** the standing checks pass.

### Phase 4: Persist the gain record

**Task:** Store `levelGains` in the player payload and, when not empty, in the mob delta in `persist/.../Codec.kt`,
following the pattern `grantedTraits` uses. Each `LevelGain` stores `stats` and `max`, each omitted when empty.

**KDoc:** in the `## Entity Delta Rules` KDoc of `Codec.kt`, add `levelGains` to the mob delta fields ("omitted when
empty") and to what the player payload stores.

**Tests:**

- `levelGains` round-trips for a player.
- `levelGains` round-trips for a mob, and an empty map writes nothing to the mob delta.

**Exit condition:** the tests above exist and pass, and the standing checks pass.

### Phase 5: Add the `on_level_up` rules export

**Task:** Add `on_level_up` to the rules seam and define it in every ruleset that has `leveling` on. Nothing calls it
yet. This is one change because a rules package with `leveling` on that lacks the export fails to load.

The seam is `fun onLevelUp(c: RulesContext): LevelUpResult?` on `RulesEngine`, abstract like the other methods.
`LevelUpResult(stats, max, current)` holds three name-to-delta maps and sits next to `PlayerDeathOutcome`. Its `stats`
and `max` become the `LevelGain` that `levelUp` records; `current` is never recorded. `StarlarkRules` parses `None` or a
`struct` with optional `stats` (`stats(...)`), `max` and `current` (`vitals(...)`), with schema-checked names; a rules
error gives `null`.

**Touches:**

- `core/.../rules/RulesEngine.kt`: the method and `LevelUpResult`. Also fix the KDoc of `xpToLevel` to "the total XP at
  which [level] ends".
- `core/src/testFixtures/.../FixtureRules.kt` and `FixedRules` in `core/src/test/.../Fixture.kt`: return `null` unless a
  test needs gains. Delegating rulesets (`RulesEngine by base`, as in `PerceptionTest`) need no change.
- `script/.../Rules.kt`: `RulesExport.ON_LEVEL_UP("on_level_up")`, its `RulesExportSpec` and the `StarlarkRules`
  implementation.
- `script/.../Ownership.kt`: `owns(Feature.LEVELING, "rules:on_level_up")`.
- `script/src/test/.../FeatureScriptTest.kt`: add `on_level_up` to the `Feature.LEVELING` export list.
- `script/src/testFixtures/.../TestWorlds.kt`: the default rules gain an `on_level_up` that returns `None`, so worlds
  built with `TestWorlds.rules(...)` (as in `RunnerTest`) keep loading.
- `template-world/rules/combat.star`: `on_level_up` from **The template rules**.
- Any other rules package with `leveling` on. Find them with `grep -rln xp_to_level --include='*.star' --include='*.kt'
  .` (skipping `local-world/`). `samples/robot-world` and the other worlds with `leveling` off stay unchanged.
- The **API files**.

**KDoc:**

- `RulesEngine.onLevelUp` and `LevelUpResult`: `ctx.actor` already has its new level; all three fields are deltas;
  `stats` and `max` are recorded in `levelGains` and `current` is not; null means no gains.
- `StarlarkRules`, `## Neutral Fallback Return Values`: add "`on_level_up`: no gains (the level is still raised)."

**Tests:**

- `on_level_up` returning a struct with an undeclared name is a rules error that gives no gains.
- A world with `leveling` off that defines `on_level_up` fails to load naming the feature.

**Exit condition:** the tests above exist and pass, and the standing checks pass.

### Phase 6: The level step up

**Task:** Add `MAX_LEVEL_STEPS = 100`, `World.pendingLevels(a): Int` and `World.levelUp(a): Boolean` to
`core/.../world/Leveling.kt`, as in **The level step up**, leaving out step 8 (the hook does not exist yet). Nothing
calls them outside tests yet.

**KDoc:**

- `MAX_LEVEL_STEPS`: the most level steps one call applies, and the most `pendingLevels` counts.
- `pendingLevels`: the counting rule, including the stop at a threshold that does not strictly increase.
- `levelUp`: the order from **The level step up**, as prose; it is the only code that raises a level.

**Tests** (a new `LevelingTest`, with a fixture ruleset):

- `on_level_up`'s `stats`, `max` and `current` deltas apply, `current` is capped at the new maximum, and the record
  holds `stats` and `max` only.
- A rules error in `on_level_up` still raises the level, applies nothing and records nothing.
- `levelUp` with nothing pending returns `false` and changes nothing.
- `pendingLevels` stops at a non-increasing `xp_to_level` with one `rules_error` alert, and is 0 when the threshold is
  never reached.

**Exit condition:** the tests above exist and pass, and the standing checks pass.

### Phase 7: Route XP awards through the step up

**Task:** Move `World.awardXp` from `Combat.kt` to `Leveling.kt` and rewrite it as in **Awarding and taking XP**, `auto`
branch only (every world behaves as `auto` until phase 15). It loops `levelUp` up to `MAX_LEVEL_STEPS` times and sends
one GMCP `Char.Status` refresh at the end. Update the existing tests that relied on the old `level += 1`.

**KDoc:** `awardXp`: what it ignores, and that it applies owed levels up to `MAX_LEVEL_STEPS`.

**Tests:**

- A kill that crosses two thresholds applies two steps in order and sends two `LEVEL_UP` messages and one GMCP refresh.
- An award that owes more than 100 levels applies 100, and the next award applies more.

**Exit condition:** the tests above exist and pass, and the standing checks pass.

### Phase 8: The level step down and taking XP

**Task:** Add `World.levelDownStep`, `World.levelDown`, `World.takeXp` and `LevelingMsg.LEVEL_DOWN` to `Leveling.kt`, as
in **The level step down** and **Awarding and taking XP**, leaving out the hook dispatch. `levelDown` asks for an
immediate checkpoint for a player, as a level-up does.

**KDoc:**

- `levelDownStep`: the order from **The level step down**, as prose, including "never owed the level just lost"; it is
  the only code that lowers a level.
- `levelDown`: it stops at level 1, sends `LEVEL_DOWN` once, checkpoints a player and never calls the rules.
- `takeXp`: it never goes below 0, never changes the level and sends nothing.

**Tests:**

- Gains that used `ctx.rng` unwind exactly: stats and maxima equal their values before the level-up.
- A level with no record unwinds nothing and still drops.
- `xp` is clamped to `xp_to_level(level) - 1`, and any other pending levels disappear.
- `levelDown(count = 3)` from level 2 stops at 1 and returns 1.
- `levelDown` sends `LEVEL_DOWN` once for several steps.
- A mob unwinds like a player.
- `takeXp` returns the amount actually removed, never goes below 0 and never changes the level.
- A lost level is saved by an immediate checkpoint.

**Exit condition:** the tests above exist and pass, and the standing checks pass.

### Phase 9: Parse `level_loss`

**Task:** Add `PlayerDeathOutcome.levelLoss: Int = 0` in `RulesEngine.kt` and parse `level_loss` (an int `>= 0`) from
`on_player_death` in `script/.../Rules.kt`, refused without `leveling` like `xp_loss`. Update the `on_player_death`
`RulesExportSpec` and, since its description changes, the **API files**. Nothing applies it yet.

**KDoc:**

- `PlayerDeathOutcome.levelLoss`: the number of levels lost after the respawn.
- `StarlarkRules`, `## Neutral Fallback Return Values`: the `on_player_death` entry gains "and no level loss".

**Tests:**

- `level_loss` without `leveling` is refused at load.
- A negative `level_loss` is a bad return value.

**Exit condition:** the tests above exist and pass, and the standing checks pass.

### Phase 10: Apply level loss at death

**Task:** In `Combat.kt`, follow the player death order in **Death**: call `levelDown(player, outcome.levelLoss)` after
the respawn. Delete the stale "no hook for them" wording.

**Tests:**

- `level_loss` lowers the level after the respawn, in the respawn room, and the respawn vitals are capped at the lowered
  maxima.
- `xp_loss` larger than `xp` leaves 0.

**Exit condition:** the tests above exist and pass, and the standing checks pass.

### Phase 11: Dry-run checks for leveling

**Task:** In `dryRunRules` (`Rules.kt`), replace the single `rules.xpToLevel(1)` call with the two checks in **Failure
handling**: `xp_to_level` for levels 1 to 50 must strictly increase, and `on_level_up` runs once per synthetic actor
with its level set to 2, validated but not applied.

**KDoc:** the `## Export Verification` list of `dryRunRules` adds the `on_level_up` call. Its `xp_to_level` bullet is
already correct.

**Tests:**

- An `on_level_up` that fails for a level-2 actor fails the rules package's load.
- An `xp_to_level` that is not strictly increasing in 1 to 50 fails it.
- The dry run applies nothing: the synthetic actors' stats are unchanged after it.

**Exit condition:** the tests above exist and pass, and the standing checks pass.

### Phase 12: The template's XP rules

**Task:** In `template-world/rules/combat.star`, replace `xp_for_kill`, `xp_to_level` and `on_player_death` with the
versions in **The template rules**, including the corrected "Total XP" comment on `xp_to_level`, the commented punishing
variant in `on_player_death`, and a `_clamp` helper if the file has none. Update the template world's tests for the new
XP numbers.

**Tests:**

- Kill XP: 5 levels below pays 0, equal pays `50 * level`, 2 levels above pays 140%, 4 above pays 150%.
- `xp_to_level` gives 1000, 3000 and 6000, and is strictly increasing to level 50.
- Dying with 1500 XP at level 2 loses 50, and dying at level 1 with 100 XP loses 10.
- `gups lint` is clean for the template world and `samples/robot-world`.

**Exit condition:** the tests above exist and pass, the template world's other tests change only in their XP numbers,
and the standing checks pass. This ends **M14.1**.

### Phase 13: Add the level mode to the world

**Task:** Add `enum class LevelMode { AUTO, DEFERRED }` in `core` and a `levelMode` on the world, defaulting to `AUTO`,
so that tests can set it. Nothing reads it yet.

**KDoc:** `LevelMode` and its two values, as in **The level mode**.

**Exit condition:** the standing checks pass.

### Phase 14: Parse `level_mode`

**Task:** Parse `module(level_mode = "auto" | "deferred")` in `script/.../WorldModule.kt` and pass it to the world. An
unknown value is a load error, and setting it with `leveling` off is a load error naming the feature. It is not recorded
per database. Register `level_mode` with `Feature.LEVELING` in `Ownership.kt`, using the key syntax the file uses for
module arguments.

**KDoc:** add `level_mode` to the `WorldModule.kt` KDoc that lists the `MODULE.gups` settings.

**Tests:**

- `level_mode = "deferred"` and `"auto"` load, and a missing `level_mode` is `auto`.
- An unknown value fails to load.
- `level_mode` without `leveling` fails to load naming the feature.

**Exit condition:** the tests above exist and pass, and the standing checks pass.

### Phase 15: Deferred awards and the ready notice

**Task:** Add the `deferred` branch to `awardXp` and `LevelingMsg.READY`, as in **Awarding and taking XP**, together
with `World.levelUpAndNotify(a): Boolean`, which calls `levelUp` and then, in `deferred` mode, sends `READY` if levels
are still pending. When the level was raised and the actor is a player, it also requests an immediate GMCP `Char.Status`
refresh, as `awardXp` and `levelDown` do. The verbs and the facade call this function.

**KDoc:** `awardXp` describes both modes; `levelUpAndNotify` says when it sends `READY`, that `READY` is stateless, and
that it refreshes a player's `Char.Status` at once.

**Tests** (Kotlin, calling `World` functions directly):

- XP crossing a threshold sends `READY` once, a second award does not repeat it, and the level does not change.
- `levelUpAndNotify` applies one level and sends `READY` again only if another is pending.
- `levelUpAndNotify` sends one immediate GMCP `Char.Status` refresh for a player when it raises the level, and none when
  nothing is pending.

**Exit condition:** the tests above exist and pass, and the standing checks pass.

### Phase 16: The `pending_levels` view field

**Task:** Add the read-only `pending_levels` field to the actor view in `script/.../Views.kt`, returning
`World.pendingLevels`. Register it in `Ownership.kt` (`view:actor.pending_levels`) and update the **API files**.

**KDoc:** the field's `@StarlarkMethod` doc: how many levels the actor has the XP for and has not been given.

**Tests:**

- `pending_levels` counts the owed levels in `deferred` mode, and is 0 after an `auto` award.
- Reading it without `leveling` fails with the feature refusal (`FeaturesOffTest`, `FeatureMatrixTest`).

**Exit condition:** the tests above exist and pass, and the standing checks pass.

### Phase 16a: Throttle the non-increasing `xp_to_level` alert

Added after the phase 16 review. The phase is numbered 16a so that the later phase numbers stay as they are.

`countPendingLevels` calls `diagnose` and `alert("rules_error", ...)` directly when a threshold does not strictly
increase. Every other rules failure goes through `StarlarkRules.fault`, which alerts at most once per
`quarantineWindowPulses` per export. Since phase 16 any script can read `pending_levels` as often as it likes, so a
broken `xp_to_level` would raise an alert on every read.

**Task:** The monotonicity check lives in `core`, so it can't call `StarlarkRules.fault`. Add a throttled `World`
helper, for example `rulesFault(key, message)`, that records the diagnostic and raises the `rules_error` alert at most
once per window per key. Route `countPendingLevels` through it with the key `xp_to_level`. Use the same window that
`StarlarkRules.fault` uses. If that window is a script `Limits` value that `core` can't see, move it to the world
config, or pass it in when the host is installed.

Each `pending_levels` read calls `xp_to_level` once per pending level, plus once. That is one call in `auto` mode and a
few in `deferred` mode, so the result is not cached. Add caching only if profiling shows a need. Caching keyed by level
would be wrong anyway once phase 16b makes the curve depend on the actor.

**KDoc:**

- The throttled `World` helper: what it throttles and the window.
- `pendingLevels`: a non-increasing threshold alerts at most once per window.
- The `pending_levels` `@StarlarkMethod` doc: a read costs one `xp_to_level` call per pending level, plus one.

**Tests:**

- `pendingLevels` on a non-increasing `xp_to_level`, called repeatedly within one window, raises one `rules_error`
  alert, and raises another after the window passes. Update the phase 6 test if it depends on the old behavior.

**Exit condition:** the test above exists and passes, and the standing checks pass.

### Phase 16b: Give `xp_to_level` the actor

Added after the phase 16 review. Some worlds need a different XP curve per class or race, and `xp_to_level(level)` gets
no ctx, so it cannot tell actors apart. This phase changes the export to `xp_to_level(ctx, level)`, with the actor as
`ctx.actor`. Every engine caller already has an actor. Phases 5 to 12 are merged, so this phase reworks their code.

**SPEC.md:** the maintainer approved the wording below. Apply it in this phase, with no `DECISIONS.md` entry.

**Task:** Change the export and its Kotlin seam, `RulesEngine.xpToLevel(level: Int)`, to `xpToLevel(c: RulesContext,
level: Int)`, and update every caller and implementer:

- **Level arithmetic:** `countPendingLevels`, `levelUp` (through it) and `levelDownStep`'s "never owed the level just
  lost" clamp, all in `core/.../world/Leveling.kt`. Each passes `RulesContext(this, a)`.
- **GMCP:** `Char.Status` `xp_next` in `core/.../world/Gmcp.kt`.
- **`StarlarkRules.xpToLevel` in `script/.../Rules.kt`:** pass `ctxArgs(c, sInt(level))`. Change the `RulesExportSpec`
  signature to `xp_to_level(ctx, level)`.
- **`dryRunRules`:** run the 1 to 50 check once for each synthetic actor, not once in total. The check fails the load if
  any actor's curve does not strictly increase, and the error names that actor.
- **Rulesets:** `FixtureRules` (`xpToLevelOf` receives the actor too), `FixedRules`, `TestWorlds`,
  `template-world/rules/combat.star` (both the definition and the calls in `on_player_death`, including the commented
  punishing variant), `RunnerTest`'s inline rules, and any other rules package found with `grep -rln xp_to_level`.
- **`RulesCtx` in `script/.../Rules.kt`:** `rng`, `roll` and `pulse` fail when the call site is `rules.xp_to_level`, the
  same way `deny` checks `frame.site.hook`. The error says that `xp_to_level` must give the same answer every time it is
  asked. Keep the restricted exports in one set in `Rules.kt`, so that a later export can join it. The check follows the
  engine's call site: a rules function that calls `xp_to_level` directly with its own ctx is not covered, and that is
  accepted, because the engine's level arithmetic uses only its own calls.
- **The Design section of this document** is already updated to the new signature.
- The **API files**.

**Contract:**

- `ctx.actor` is the actor whose curve is asked about. `ctx.actor.level` is its current level, which may differ from
  `level`: the engine also asks about the levels above and below it.
- The result must depend only on the actor and `level`, because it is called often and with levels other than the
  actor's own. `ctx.rng`, `ctx.roll` and `ctx.pulse` are not available in it, and reading them is a rules error. Because
  the dry run calls the export, a curve that reads them fails the rules package's load.
- Strict increase is a property of one actor's curve: `xp_to_level(ctx, L) > xp_to_level(ctx, L - 1)` for the same
  actor.
- If the curve changes for an actor, for example when its class changes, the engine does not re-evaluate past levels.
  The new curve applies at the next award, read or step: the actor may be owed levels at once, or owed none until it
  catches up. Its `levelGains` are untouched.

**KDoc:**

- `RulesEngine.xpToLevel`: the contract above.
- `StarlarkRules`, `## Neutral Fallback Return Values`: no change.
- `dryRunRules`, `## Export Verification`: the 1 to 50 check runs per synthetic actor.
- `RulesCtx`: its class KDoc ("It has the call's fields and `rng`") and the `rng`, `roll` and `pulse` `@StarlarkMethod`
  docs say that they are not available in `xp_to_level`. Because these docs change, update the **API files**.

**Tests:**

- A ruleset whose `xp_to_level` depends on an actor attribute owes different levels to two actors with the same XP, and
  `Char.Status` `xp_next` differs between them.
- The dry run fails a curve that strictly increases for one synthetic actor and not for another, naming the second.
- Lowering a level clamps `xp` using that actor's own curve.
- Reading `ctx.rng`, `ctx.roll` or `ctx.pulse` in `xp_to_level` fails the rules package's load at the dry run, and the
  error says why. The same fields still work in another export, such as `on_level_up`.
- Update the existing `xp_to_level` tests (`RulesTest`, `LevelingTest`, `CombatTest`, the template tests) to the new
  signature. Their numbers do not change.

**SPEC.md wording (approved):**

- §4.6 export table: `` `xp_for_kill(ctx)`, `xp_to_level(ctx, level)` `` | `leveling` | Ints.
- §4.6 **Signatures and results**, first bullet: replace "`equipment_slots()` and `xp_to_level(level)` get no ctx." with
  "`equipment_slots()` gets no ctx, and `xp_to_level(ctx, level)` gets the level it is asked about as `level`."
- §4.6 **Signatures and results**, the `xp_to_level` bullet becomes: "`xp_to_level(ctx, level)` is the total XP at which
  `level` ends for `ctx.actor`, so a world can give each actor its own curve influenced by attributes. Note that
  `ctx.actor.level` may differ from `level`. The answer must depend only on the actor and `level`. `xp_to_level(ctx,
  level + 1)` must be greater than `xp_to_level(ctx, level)` for the same actor; a value that is not is a bad return
  value, and no level is owed past it (§5.4)."
- §4.6 **The dry run**: "`xp_to_level` runs for levels 1 to 50 and must strictly increase" becomes "`xp_to_level` runs
  for levels 1 to 50 for each actor and must strictly increase for each".
- §5.4 **Levelling**: "An actor is owed level `L + 1` once `xp >= xp_to_level(L)`, unless `xp_to_level(L)` is not
  greater than `xp_to_level(L - 1)`." becomes "An actor is owed level `L + 1` once `xp` reaches its own
  `xp_to_level(ctx, L)`, unless that is not greater than its `xp_to_level(ctx, L - 1)`."

**Exit condition:** SPEC.md has the approved wording, the tests above exist and pass, and the standing checks pass.

### Phase 17: The four ctx verbs

**Task:** Add `award_xp`, `take_xp`, `level_up` and `level_down` to `script/.../Ctx.kt`, next to `heal` and `damage`, as
in **Script verbs and fields**. Each refuses without `leveling`, checks that `amount` or `count` is at least 1, and
calls the matching `World` function; `level_up` calls `levelUpAndNotify`. Register the four verbs in `Ownership.kt` and
update the **API files**.

**KDoc:** each verb's `@StarlarkMethod` doc, with its arguments, return value and the feature it needs.

**Tests:**

- `award_xp` rejects 0 and negatives.
- `take_xp` returns the amount actually removed.
- `level_down` returns the levels actually lost.
- In `deferred` mode, `ctx.level_up` returns `True`, applies one level, and sends `READY` only if another is pending.
- A kill, `award_xp` and a script's `level_up` all raise levels through `World.levelUp`.
- Every verb fails with the feature refusal when `leveling` is off (`FeaturesOffTest`, `FeatureMatrixTest`).

**Exit condition:** the tests above exist and pass, and the standing checks pass.

### Phase 18: The plugin facade

**Task:** Add the four members to `PluginWorld` and `val pendingLevels: Int` to `PluginActor` in
`plugin-api/.../Facade.kt`, exactly as in **Script verbs and fields**, and implement them in
`plugin-api/.../host/Facade.kt` over the same `World` functions the verbs use. Do not bump `PluginApi.VERSION`. Update
the **API files** if the API model covers the facade.

**KDoc:** each member: what it does, its return value, `IllegalStateException` naming the feature when `leveling` is
off, and `IllegalArgumentException` when `amount` or `count` is below 1. `pendingLevels` throws like `level`.

**Tests:**

- The facade methods give the same results as the verbs.
- They throw like `level` without `leveling`, and reject `amount` or `count` below 1.

**Exit condition:** the tests above exist and pass, and the standing checks pass.

### Phase 19: The harness verbs

**Task:** Add `award_xp(actor, amount)`, `take_xp(actor, amount)`, `level_up(actor)` and `level_down(actor, count=1)` to
the `w` handle in `harness/.../Handles.kt`, with the verbs' arguments and return values, and update the **API files**.

**KDoc:** each method's `@StarlarkMethod` doc.

**Tests:**

- A harness test drives each of the four `w` verbs and checks the level and XP.

**Exit condition:** the test above exists and passes, and the standing checks pass. This ends **M14.2**.

### Phase 20: Declare the `on_level_change` hook

**Task:** Declare the `on_level_change` hook and its ctx fields without firing it.

**Touches:**

- `core/.../world/Hooks.kt`: `HookName.ON_LEVEL_CHANGE("on_level_change")`, and `oldLevel` and `newLevel` on
  `HookContext`.
- `script/.../Declarations.kt`, near `ON_DEATH`: the catalog entry, attachable to `Mob`, `Room` and `Item`, needing
  `leveling`.
- `script/.../Ctx.kt`: the `old_level` and `new_level` ctx fields, `None` for every other hook.
- `script/.../Ownership.kt`: the hook, with `Feature.LEVELING`.
- The **API files**.

**KDoc:** `HookName.ON_LEVEL_CHANGE`: when it fires and what `oldLevel` and `newLevel` hold.

**Tests:**

- A mob, a room and an item can declare `on_level_change`, and another kind of entity can't.
- Declaring it without `leveling` fails to load naming the feature.

**Exit condition:** the tests above exist and pass, and the standing checks pass.

### Phase 21: Fire `on_level_change`

**Task:** Call `dispatch(HookName.ON_LEVEL_CHANGE, ...)` from `levelUp` (step 8) and `levelDownStep` (step 7) only, so
that it reaches carried as well as equipped items. Check first how `dispatch` treats an item's owner. If it limits some
hooks to equipped items, add a per-hook flag and leave the other hooks alone.

**KDoc:** update `levelUp` and `levelDownStep` to include the dispatch.

**Tests:**

- It fires once per step, in order, for a kill, `award_xp`, `level_up`, `level_down` and `level_loss`, and never for
  `take_xp` or a plain XP gain.
- It reaches the actor's own mob hook, the room, observers and the actor's carried and equipped items, in the order of
  **The `on_level_change` hook**.
- A hook that calls `award_xp` is cut off at depth 8 without a hang.

**Exit condition:** the tests above exist and pass, and the standing checks pass.

### Phase 22: The `score` ready line

**Task:** Add `LevelingMsg.SCORE_READY` and have `score` (`KernelCommands.kt`) print it on the line after the level line
when `pendingLevels > 0`.

**Tests:**

- `score` shows the ready line only while a level is pending.

**Exit condition:** the test above exists and passes, and the standing checks pass.

### Phase 23: A tutorial observer hook

**Task:** In `template-world/zones/tutorial/`, give one mob an `on_level_change` hook that reacts when a player in its
room reaches a new level, with a test in `tutorial_test.star`.

**Exit condition:** the new tutorial test passes, `gups lint` is clean for the template world, and the standing checks
pass.

### Phase 24: The deferred-mode trainer example

**Task:** Add a deferred-mode example to `script/.../DocExamples.kt` (a world with `level_mode = "deferred"` and a
trainer command in a room that reads `ctx.actor.pending_levels` and calls `ctx.level_up(ctx.actor)`), and a test world
built from it in `script/src/testFixtures/.../TestWorlds.kt`.

**Tests** (with that test world):

- A player who earns a level is told `READY`, uses the trainer, gains the level, and sees the `score` ready line go
  away.
- The trainer with nothing pending changes nothing.

**Exit condition:** the tests above exist and pass, `./gradlew :cli:test` (including `DocsWorldTest`) passes, and the
standing checks pass.

### Phase 25: The vampire example

**Task:** Add a vampire example to `script/.../DocExamples.kt`: a skill `resolve` that uses `take_xp` to steal XP and
`level_down` to drain a level.

**Exit condition:** `./gradlew :cli:test` (including `DocsWorldTest`) passes, and the standing checks pass. This ends
**M14.3**.

### Phase 26: Verify the milestone

**Task:** Confirm that the milestone is complete, fixing anything that is not.

**Exit condition:**

- `docs/DESIGN_NEEDED.md` has no `Level-up gains` or `Experience subsystem and scaling` entry, and still has `Group,
  party and pet XP sharing` and `Skill-based advancement (worlds without character levels)`.
- `README.md` and the tutorial pages say nothing about levels or XP that this milestone made untrue.
- `docs/CHANGELOG.md` has one `### Experience and levels` section covering the export, `level_loss`, `level_mode`, the
  verbs, `pending_levels`, the hook, the facade and the template's new formulas.
- `./gradlew markdownFormat` has been run, and the standing checks pass.
- Every item in **Success criteria** holds.

## Success criteria

- [] Every test named in phases 4 to 24 exists and passes.
- [] `./gradlew check` passes, including the module-graph check, with no Kotlin warnings.
- [] SPEC.md has every item in the phase 1 checklist, and §6.3 says "without XP or level loss".
- [] The KDoc named in each phase is written, and no `.kt` file under `src/main` cites a spec section or decision ID.
- [] `CHANGELOG.md`, `docs/api/api-1.json` and the golden `api.json` are updated, and neither `api_version` nor
  `PluginApi.VERSION` is bumped.
- [] `DESIGN_NEEDED.md` has neither resolved entry and keeps the two new ones.
- [] A world with `leveling` off behaves as before and defines none of the new exports.
