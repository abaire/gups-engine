# Milestone 14: Experience, Levels and Level Changes

Gaining a level stops being a bare `level += 1`. Levels become a stepped, reversible change: each level can grant stat
and vital gains through the rules, the engine remembers what every level gave, and a level can be taken away again
(drained by a vampire, lost at death) with exactly those gains unwound. Worlds choose whether earned levels apply at
once or wait for an event such as a trainer. Kill XP and the XP curve move into the rules where they belong, and scripts
get verbs to award, steal and drain experience and levels.

This milestone resolves two gaps in [DESIGN_NEEDED.md](DESIGN_NEEDED.md): `### Level-up gains` and `### Experience
subsystem and scaling`. It opens two new entries there, which are already in that file.

It builds on [M13](MILESTONE_13.md), which must be merged first. It bumps neither `api_version` nor `PluginApi.VERSION`:
the engine is unreleased, so the new export, hook, verbs and fields join `api_version` 1 (§1.3). Nothing here needs a
schema migration or a compatibility shim for old saves: the engine is unreleased.

Read [AGENTS.md](../AGENTS.md) before starting. The source of truth is [SPEC.md](SPEC.md); step 1 applies the wording
below to it. After that, where this file and the spec differ, the spec wins.

|                 Section                  |                                                       What it says about this milestone                                                        |
|------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| **§4.6 Rules Module and Modifier Chain** | The exports table, signatures, the dry run, the template ruleset: `on_level_up`, `on_player_death` `level_loss`, the XP curve and kill scaling |
| **§5.4 Combat Rounds**                   | Death and the "Levelling" bullet                                                                                                               |
| **§3.10 Feature Modules**                | What `leveling` owns                                                                                                                           |
| **§3.9 World Schema**                    | The `level_mode` key                                                                                                                           |
| **§4.4 Hook Catalog**, §4.5 The ctx      | `on_level_change`, `old_level`, `new_level`, the four verbs, `pending_levels`                                                                  |
| §3.3, §3.6                               | `levelGains` on actors and in the payloads                                                                                                     |
| §4.12, §4.15, §4.18                      | Harness verbs, the plugin facade, the engine messages                                                                                          |
| §5.11                                    | Level-down as an immediate-checkpoint trigger                                                                                                  |
| §6.3                                     | The neutral result of `on_level_up`                                                                                                            |
| §4.16, §4.17                             | The API model, `since`, the changelog and the API-change check                                                                                 |
| §7.1, §7.4                               | The tests and the milestone row                                                                                                                |

## Design (settled)

These were decided with the maintainer and are not open.

### Scope

1. **In:** level-up gains; a stepped, reversible level change with a stored gain record; deferred level application;
   script verbs for XP and levels; the `on_level_change` hook; level-difference kill XP, a quadratic curve and
   within-level death XP loss, all in the template rules; rules-driven level loss at death.
2. **Out, tracked in `DESIGN_NEEDED.md`:** group, party, pet and assist XP sharing (step 9 opens the entry), and
   skill-based advancement for worlds without character levels (step 9 opens the entry). A world without character
   levels turns `leveling` off and nothing in the kernel may then assume a `level`.
3. **Not engine concerns.** Level-difference scaling, the curve and who is penalised at death are rules. The engine adds
   no `xp_scale` modifier point, no level cap and no diminishing-returns state.

### Three separate steps

1. **Accrual:** `xp` goes up (a kill, `award_xp`) or down (`take_xp`, death).
2. **Eligibility:** the actor is eligible for level `L + 1` when `xp >= rules.xp_to_level(L)`. `xp_to_level(level)` is
   the **total** XP at which `level` ends, not the XP between levels.
3. **Application:** a level step raises `level` by one. Every level increase goes through one function, `World.levelUp`,
   which is also what `ctx.level_up` calls. In `auto` mode the engine calls it as soon as the actor is eligible; in
   `deferred` mode only a script does.

`xp_to_level` must be strictly increasing: `xp_to_level(L) > xp_to_level(L - 1)` for every `L >= 2`. A value that isn't
is a bad return value (a rules error, §6.3) and stops the loop that found it. A single call never applies more than
`MAX_LEVEL_STEPS = 100` steps, and `pending_levels` never counts past it.

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

`World.levelUp(a): Boolean`, the only code that raises a level. It first checks that the actor is eligible (it has
`pending_levels > 0`); if not it returns `False` and changes nothing. Otherwise:

1. `a.level += 1`.
2. Call `rules.on_level_up(ctx)` with `ctx.actor = a` (the new level already set) and `ctx.rng`. There is no `old_level`
   on this ctx; it is `ctx.actor.level - 1`.
3. Validate the result. It is `None` or a struct with optional `stats` (a `stats(...)`), `max` (a `vitals(...)`) and
   `current` (a `vitals(...)`). Names are checked against the schema. All three are **deltas** and may be negative. A
   rules error counts as an empty result (§6.3): the level is still raised.
4. Add `stats` to the actor's base stats and `max` to its base vital maxima. Add `current` to its current vitals. Cap
   every current vital at its new effective maximum.
5. If `stats` or `max` is not empty, store them as `a.levelGains[a.level]`, replacing any older entry. `current` is not
   stored: a heal can't be taken back.
6. Tell the actor `LevelingMsg.LEVEL_UP`.
7. Ask `condition` once for the vitals that changed (§5.4). A `current` delta that kills is the rules' business.
8. Dispatch `on_level_change` (below) with `old_level = a.level - 1` and `new_level = a.level`.
9. Mark the actor dirty and return `True`.

In `deferred` mode the caller then tells the actor `LevelingMsg.READY` if levels are still pending.

### The level step down

`World.levelDownStep(a)`, the only code that lowers a level; the caller guarantees `a.level >= 2`:

1. Take `a.levelGains.remove(a.level)`, if any. A level with no entry unwinds nothing.
2. Subtract its `stats` from the base stats and its `max` from the base vital maxima.
3. `a.level -= 1`.
4. Cap every current vital at its new effective maximum.
5. **Never owed the level just lost:** `a.xp = min(a.xp, rules.xp_to_level(a.level) - 1)`. This also removes any other
   pending levels.
6. Ask `condition` once for the vitals that changed.
7. Dispatch `on_level_change` with `old_level = a.level + 1` and `new_level = a.level`.
8. Mark the actor dirty.

`World.levelDown(a, count): Int` repeats the step `count` times, stops at level 1, tells the actor
`LevelingMsg.LEVEL_DOWN` once if at least one step ran, asks for an immediate checkpoint for a player, and returns the
steps taken. The rules are never called: the engine subtracts exactly what it stored, so gains that used `ctx.rng`
unwind exactly, and so do gains from a rules package that has since changed.

### Awarding and taking XP

- `World.awardXp(a, amount)` (existing): ignored unless `leveling` is on and `amount > 0`. Adds the XP, tells
  `LevelingMsg.GAIN`. In `auto` mode it then calls `levelUp(a)` until that returns `False`, at most `MAX_LEVEL_STEPS`
  times; levels still pending after the cap wait for the next award. In `deferred` mode it sends `LevelingMsg.READY`
  when `pending_levels` went from 0 to above 0. For a player whose level changed it requests an immediate GMCP
  `Char.Status` once, at the end. The kill path calls it exactly as today (debug mobs and summons award nothing).
- `World.takeXp(a, amount): Long` removes `min(amount, a.xp)`, returns the amount removed, and sends nothing. It never
  changes `level`.
- **Pending levels:** `pending_levels(a)` counts the consecutive `k >= 0` with `a.xp >= xp_to_level(a.level + k)`, up to
  `MAX_LEVEL_STEPS`. It stops at the first `k` for which the threshold is not strictly increasing.
- **READY is stateless and only for `deferred` worlds:** it is sent when an award takes `pending_levels` from 0 to above
  0, and after a `ctx.level_up` that leaves levels pending. It is not repeated by later gains.

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

### The `on_level_change` hook

- Catalog row: `on_level_change`, no veto, attaches to `Mob`, `Room` and `Item` (carried or equipped), needs `leveling`.
- Key ctx fields: `actor` (the one whose level changed), `old_level`, `new_level`. `old_level` and `new_level` are two
  new ctx fields, added to the list in §4.5 and to the exact-fields rule; they differ by exactly one.
- Fires only from `levelUp` and `levelDownStep`, once per step, after the gains or the unwinding, the record update and
  the message, so the hook sees the new state. Every cause of a level change (a kill, `award_xp`, `level_up`,
  `level_down`, `level_loss`) therefore fires it exactly once per level, in order (1→2, 2→3 or 4→3, 3→2). `take_xp`
  never fires it.
- Dispatch is the `on_death` shape and the §5.6 order: the actor's own hook if it is a mob, then the room, the other
  actors in the room, the items in the room, then the actor's carried and equipped items (its direct inventory, not the
  contents of containers it carries), then the observers' items. An item or mob without the hook costs a lookup.
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

### The template rules

Everything here is the template ruleset (`template-world/rules/combat.star`) and the Kotlin fixture rulesets, not the
engine:

```python
def xp_for_kill(ctx):
    # 50 per victim level, scaled by how the victim's level compares to the killer's:
    # 5+ levels below pays nothing, 2+ levels above pays 140%, capped at 150%.
    diff = ctx.target.level - ctx.actor.level
    pct = _clamp(100 + 20 * diff, 0, 150)
    return 50 * ctx.target.level * pct // 100

def xp_to_level(level):
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
    floor = xp_to_level(a.level - 1) if a.level > 1 else 0
    return {
        "respawn": a.recall_room,
        "vitals": vitals(hp = 1),
        "xp_loss": (a.xp - floor) // 10,  # 10% of the XP earned within this level
        "level_loss": 0,
    }
```

A comment in `on_player_death` shows the punishing variant: when `xp - xp_loss` falls below the floor, return the
`level_loss` that `xp_to_level` implies. The comment on `xp_to_level` is corrected to say "total". The template world
stays in `auto` mode; the deferred-mode trainer is a doc example and a test world (step 8). Worlds that turn `leveling`
off (`samples/robot-world`) define none of these.

### Failure handling

- `on_level_up` and `xp_to_level` errors are rules errors (§6.3): reported to the instructors and quarantined. A failing
  `on_level_up` is an empty result and the level is still raised. A failing `xp_to_level` returns the neutral value
  `Long.MAX_VALUE`, so no level-up happens.
- `on_level_up` is called by the dry run once for each synthetic actor with its level set to 2, after `starting_stats`;
  the result is validated but not applied. The dry run also calls `xp_to_level` for levels 1 through 50 and fails the
  rules package's load if the values are not strictly increasing.
- Neutral result of `on_level_up`: no gains.

### Persistence

`levelGains` is a map from the level reached to `{stats, max}` (each omitted when empty). It is stored in the player
payload and, when not empty, in the mob delta (like `grantedTraits`). Level-down asks a player's checkpoint to run at
once, as level-up does (§5.11). No migration: the engine is unreleased, so the payload types change directly.

### Messages

New `LevelingMsg` entries, rewordable in `MESSAGES.gups` (§4.18):

|           Id           |            Default text             |   Placeholders   |
|------------------------|-------------------------------------|------------------|
| `leveling.level_down`  | `You have fallen to level {level}.` | `level`, `count` |
| `leveling.ready`       | `You feel ready to advance.`        | none             |
| `leveling.score_ready` | `You are ready to advance.`         | none             |

`score` prints `Level N (X xp)` and, when `pending_levels > 0`, `LevelingMsg.SCORE_READY` on the next line.
`Char.Status` and `who` do not change; clients compare `xp` to `xp_next`.

### Harness

The `w` handle gets `award_xp(actor, amount)`, `take_xp(actor, amount)`, `level_up(actor)` and `level_down(actor,
count=1)`, with the verbs' arguments and return values.

## Where things are today (after M13)

Line numbers drift; search for the names.

- `core/.../world/Combat.kt`: the XP award on a kill (about 216), the player death branch with `xp_loss` and the respawn
  (about 242), and `awardXp` (about 274).
- `core/.../world/Leveling.kt`: only `LevelingMsg` (`SCORE_LEVEL`, `GAIN`, `LEVEL_UP`).
- `core/.../rules/RulesEngine.kt`: `xpForKill`, `xpToLevel`, `onPlayerDeath` and `PlayerDeathOutcome(xpLoss, ...)`.
- `core/.../model/Entities.kt`: `Actor.level`, `xp` and `grantedTraits` (about 166 to 172).
- `core/.../world/Hooks.kt`: `HookName.ON_DEATH`; `Combat.kt` dispatches it with `dispatch(HookName.ON_DEATH, ...)`.
- `core/.../world/Gmcp.kt` (about 99): `Char.Status` with `xp_next = rules.xpToLevel(level)`; `KernelCommands.kt` (about
  351): the `score` level line.
- `script/.../Rules.kt`: `run(RulesExport.XP_FOR_KILL ...)` and its siblings (about 556 to 573), the `RulesExport` enum
  (about 601), the export descriptions (about 806 to 820), and the dry run (about 1035 and 1063).
- `script/.../Ownership.kt` (about 18 to 20): the `leveling` keys. `script/.../Declarations.kt` (about 260): the hook
  catalog. `script/.../Ctx.kt` (about 714 to 818): verbs such as `damage`, `heal` and `apply_affect`.
  `script/.../Views.kt` (about 147): the actor view `level`.
- `persist/.../Codec.kt` (about 140 to 185, 363 and 434): the player payload and the mob delta.
- `plugin-api/.../Facade.kt` and `host/Facade.kt` (about 29 to 58 and 333 to 376): the facade, with `applyAffect`,
  `grantTrait` and `setSkill`.
- `harness/.../Handles.kt` (about 119 to 173): `spawn_player(level=...)` and the other `w` verbs.
- Tests that call `awardXp` today: `CombatTest`, `FeaturesOffTest`, `FeatureMatrixTest`. Rulesets to update:
  `core/src/testFixtures/.../FixtureRules.kt`, `script/src/testFixtures/.../TestWorlds.kt`,
  `template-world/rules/combat.star`.

## Work plan

Work in this order, and write each step's tests from the spec before or with the code. M14.1 is steps 2 to 4, M14.2 is
steps 5 to 6, M14.3 is steps 7 to 9. Every step ends with `./gradlew check` green and with the template world working.

### 1. Apply the spec changes

The maintainer approved this design, so these edits go straight into `SPEC.md` with no `DECISIONS.md` entry. Anything
else you find unsettled is logged in `DECISIONS.md` as usual, with proposed wording in your summary.

- **§3.3, Leveling bullet:** after "`level`, `xp`." add: "A player or mob also has `levelGains`, a map from a level to
  the stat and maximum-vital deltas that level applied, kept so a lost level can be unwound exactly (§5.4)."
- **§3.6, the `players` row and the Deltas bullet:** add `levelGains` to the player payload; mobs store it when not
  empty, like `grantedTraits`.
- **§3.9:** add the `level_mode` argument and its paragraph: "`level_mode = "auto"` (the default) applies a level as
  soon as the actor has the XP; `"deferred"` only adds XP and waits for `ctx.level_up` (§5.4). It needs the `leveling`
  feature, and isn't recorded per database."
- **§3.10, the `leveling` row:** add `levelGains`, `on_level_up`, `on_level_change`, the `award_xp`, `take_xp`,
  `level_up` and `level_down` verbs and `pending_levels`.
- **§4.4, the hook table**, after `on_death`: `| on_level_change | — | Mob, Room, Item (carried or equipped) | actor,
  old_level, new_level |`. Add after the table: "**`on_level_change`** needs `leveling`. It fires once per level step,
  after the gains or their unwinding, the message and the gain record, so it sees the new state; a loss of three levels
  fires it three times."
- **§4.5, context fields:** add `old_level`, `new_level`. After the existing verbs add exactly:

  ```
  - `award_xp(actor, amount)`: gives `actor` `amount` experience (an int of at least 1) as a kill does: the gain
    message, then level steps as the world's `level_mode` says (§5.4). Returns `None`.
  - `take_xp(actor, amount)`: removes up to `amount` experience (at least 1), never below 0, and never changes the
    level. Sends nothing. Returns the amount removed.
  - `level_up(actor)`: applies one pending level (§5.4). Returns `True`, or `False` with no change if none is
    pending. In `auto` mode the engine calls it for each owed level.
  - `level_down(actor, count=1)`: takes up to `count` levels (at least 1) from `actor`, unwinding the gains they
    gave, and stops at level 1. Returns the number of levels taken.
  All four need `leveling`, and `actor` may be a player or a mob.
  ```

  In the actor view list add exactly: "`pending_levels`: how many levels the actor has the XP for and has not been given
  (§5.4). Reading it without `leveling` is an error, like `level`."

- **§4.6, the exports table**, after `xp_for_kill, xp_to_level`, add exactly: `| on_level_up(ctx) | leveling | None or
  struct(stats, max, current): deltas. |`. Change the `on_player_death` result to `{"respawn": label, "vitals":
  vitals(...), "xp_loss": int, "level_loss": int}`.

- **§4.6, signatures and results:** add exactly:

  ```
  - `on_level_up(ctx)`: `ctx.actor` is the actor, with its new `level` already set. The result is `None` or a
    `struct` with any of `stats`, `max` and `current`, built with `stats(...)` and `vitals(...)`. All are deltas and
    may be negative. `stats` and `max` change the base values and are remembered for that level so that losing it
    takes them back (§5.4); `current` is added to the current vitals, which are then capped at the new maxima, and
    is not remembered.
  - `xp_to_level(level)` is the total XP at which `level` ends. `xp_to_level(level + 1)` must be greater than
    `xp_to_level(level)`; a value that is not is a bad return value.
  - `on_player_death`: `level_loss` (default 0) is the number of levels the player loses after the respawn. It is
    allowed only with the `leveling` feature, like `xp_loss`. The engine keeps `xp` at 0 or more and `level` at 1 or
    more and enforces no other floor.
  ```

  Replace the existing `xp_loss` clause of the `on_player_death` bullet with "`xp_loss` is taken from `xp` before the
  respawn".

- **§4.6, the dry run:** after "`mitigate` once per declared damage type." add exactly: "`on_level_up` runs once for
  each synthetic actor as if it had just reached level 2, and its result is checked but not applied; `xp_to_level` runs
  for levels 1 to 50 and must strictly increase."

- **§4.6, the template table:** replace the `xp_for_kill`, `xp_to_level` and `on_player_death` rows with exactly:

  ```
  | `xp_for_kill`     | `50 × victim level`, times `clamp(100 + 20 × (victim level − killer level), 0, 150)` percent |
  | `xp_to_level`     | `500 × level × (level + 1)`                                                                  |
  | `on_level_up`     | `max` and `current` `hp` +`5 + con/4`, `mana` +`2 + wis/8`, `move` +5; no stat gains         |
  | `on_player_death` | respawn at the recall room with `hp` 1, lose 10% of the XP earned within the level; no level loss |
  ```
- **§4.12, the `w` row:** add `award_xp(actor, amount)`, `take_xp(actor, amount)`, `level_up(actor)`, `level_down(actor,
  count=1)`.
- **§4.15, the facade:** add exactly these members to `PluginWorld`, and `val pendingLevels: Int` to `PluginActor`:

  ```kotlin
  fun awardXp(actor: PluginActor, amount: Long)
  fun takeXp(actor: PluginActor, amount: Long): Long
  fun levelUp(actor: PluginActor): Boolean
  fun levelDown(actor: PluginActor, count: Int = 1): Int
  ```

  and, in the prose: "These throw `IllegalStateException` naming the feature when `leveling` is off, as `level` does,
  and `IllegalArgumentException` when `amount` or `count` is below 1."

- **§4.18:** add the three messages in "Messages" above to the catalog, with the same ids, texts and placeholders.

- **§5.4, Death, the player bullet:** after "applies `rules.on_player_death`" insert ", takes its `xp_loss` from `xp`
  (never below 0)"; at the end of the bullet add "After the respawn the player loses `level_loss` levels."

- **§5.4, Levelling:** replace the bullet with exactly:

  ```
  - **Levelling** (the `leveling` feature): XP is gained from kills and `award_xp` and lost to `take_xp` and
    `on_player_death`. An actor is owed level `L + 1` once `xp >= xp_to_level(L)`. In `auto` mode (`level_mode`, §3.9)
    the engine calls `level_up` for each owed level at once. In `deferred` mode it tells the player "You feel ready to advance." and
    applies a level only when `level_up` is called. Applying a level raises `level` by one, calls `on_level_up`, adds its
    `stats` and `max` to the base values and remembers them in `levelGains`, adds its `current` to the vitals, tells the
    actor, and fires `on_level_change`. Losing a level (`level_down`, or `level_loss` at death) subtracts the remembered
    gains of that level, lowers `level`, caps the vitals at the new maxima, sets `xp` to at most one below
    `xp_to_level(level)`, and fires `on_level_change`; the rules are not called. A level with nothing remembered
    unwinds nothing. One call applies at most 100 levels.
  ```
- **§5.11:** add "a level lost" to the list of immediate-checkpoint triggers.
- **§6.3:** add the row `| on_level_up | no gains |` to the neutral-result table.
- **§7.1:** add exactly: "- **Levels:** the level steps and their gain records, including unwinding random gains;
  deferred mode and the ready notice; the four verbs and their feature gate; `on_level_change` ordering and cascade
  depth; `level_loss` at death; the dry-run checks; persistence of `levelGains`; the template's kill scaling, curve and
  death loss."
- **§7.4:** add the row `| **M14** | Experience and levels, built in three stages: **M14.1** the reversible level step,
  `on_level_up`, the gain record, `level_loss` and the template's curve, kill scaling and death loss; **M14.2**
  `level_mode`, the `award_xp`, `take_xp`, `level_up` and `level_down` verbs, `pending_levels` and the harness verbs;
  **M14.3** the `on_level_change` hook, the `score` line and the template examples (§3.9, §4.4, §4.6, §5.4). |`

### 2. M14.1 `core`: the level steps

1. `Actor` gets `levelGains: MutableMap<Int, LevelGain>`; `LevelGain(stats: Map<String, Int>, max: Map<String, Int>)`
   (`Entities.kt`).
2. `Leveling.kt` (it exists and holds `LevelingMsg`) also holds `levelUp`, `levelDownStep`, `levelDown`, `takeXp`,
   `pendingLevels` and `MAX_LEVEL_STEPS`. Move `awardXp` out of `Combat.kt` and rewrite it as above.
3. `RulesEngine.kt`: add `onLevelUp(c): LevelGain?` (abstract, like the other methods) and `levelLoss: Int` to
   `PlayerDeathOutcome`. Update every Kotlin ruleset: `FixtureRules.kt` and the rulesets in `TestWorlds.kt`.
4. `Combat.kt`: the death order above. Delete the stale "no hook for them" wording.
5. `LevelingMsg`: `LEVEL_DOWN`, `READY`, `SCORE_READY`.

**Exit condition:** `./gradlew :core:test` passes with zero warnings.

### 3. M14.1 `script`: the export and the dry run

1. `Rules.kt`: `RulesExport.ON_LEVEL_UP("on_level_up")`, parsed into `LevelGain` with schema-checked `stats`, `max` and
   `current`; `on_player_death` parses `level_loss` (int `>= 0`, refused without `leveling`, like `xp_loss`).
2. `Ownership.kt`: `owns(Feature.LEVELING, "rules:on_level_up")`.
3. The dry run (`Rules.kt` near the `xpToLevel(1)` call): the `on_level_up` and `xp_to_level` checks; the neutral result
   for `on_level_up`.
4. `persist`: `levelGains` in the player payload and the mob delta (`Codec.kt`).

**Exit condition:** `./gradlew :script:test :persist:test` passes.

### 4. M14.1 shipped rulesets and docs

1. `template-world/rules/combat.star`: the four functions above, with the corrected `xp_to_level` comment and the
   commented punishing variant.
2. `samples/robot-world` and every test world with `leveling` off: unchanged. Test worlds with it on define
   `on_level_up`.
3. API docs: the model, the golden `api.json`, `docs/api/api-1.json` (`gups docs --update-baseline`), and a
   `CHANGELOG.md` entry under `api_version 1 (unreleased)`. New symbols get `since = 1`.

**Exit condition:** `./gradlew check` passes and the template world's tests are unchanged apart from the new XP numbers.

### 5. M14.2 `core` and `script`: the mode and the verbs

1. `WorldModule.kt`/`Schema.kt`: parse `level_mode` (`"auto"` or `"deferred"`), the load error without `leveling`, and
   pass the mode to the world.
2. `awardXp`: the deferred branch and `READY`.
3. `Ctx.kt`: the four verbs, next to `heal` and `damage`; `Views.kt`: `pending_levels` on the actor view; both with the
   feature refusal.
4. `Ownership.kt`: register the four verbs, `pending_levels`, `level_mode` and `on_level_change` with
   `Feature.LEVELING`, using the key syntax already there (`mob.level`, `view:actor.level`, `rules:xp_for_kill`); read
   the file for how verbs, module arguments and hooks are keyed.
5. `KernelCommands.kt`: the `score` ready line.

**Exit condition:** `./gradlew :core:test :script:test` passes.

### 6. M14.2 plugins and harness

1. `plugin-api`: `PluginWorld.awardXp`, `takeXp`, `levelUp`, `levelDown`, `PluginActor.pendingLevels`, implemented in
   `host/Facade.kt` over the same `World` functions. No `PluginApi.VERSION` bump.
2. `harness/Handles.kt`: the four `w` verbs.
3. Update the API model, golden and baseline again.

**Exit condition:** `./gradlew :plugin-api:test :harness:test` passes.

### 7. M14.3 the hook

1. `Hooks.kt`: `HookName.ON_LEVEL_CHANGE`, and `oldLevel` and `newLevel` on `HookContext`.
2. `Declarations.kt` (near `ON_DEATH`): the catalog entry, attachable to `Mob`, `Room` and `Item`, needing `leveling`.
   Check how `dispatch` treats an item's owner: the hook must reach carried items as well as equipped ones. If
   `dispatch` limits some hooks to equipped items, add a per-hook flag and leave the others alone.
3. `Ctx.kt`: the `old_level` and `new_level` fields, `None` for every other hook.
4. Call `dispatch(ON_LEVEL_CHANGE, ...)` from `levelUp` and `levelDownStep` only.

**Exit condition:** `./gradlew :core:test :script:test` passes.

### 8. M14.3 template and tutorial

1. One observer hook in the tutorial zone: a mob that reacts when a player in its room reaches a new level, with a test.
2. A deferred-mode example in `DocExamples.kt`: a trainer command in a room that reads `ctx.actor.pending_levels` and
   calls `ctx.level_up(ctx.actor)`, shown with `level_mode = "deferred"`. A test world in
   `script/src/testFixtures/.../TestWorlds.kt` uses it for the tests below.
3. A vampire example in `DocExamples.kt`: a skill `resolve` using `take_xp` and `level_down`.

**Exit condition:** `./gradlew :cli:test` (including `DocsWorldTest`) passes.

### 9. Wrap-up

- **`docs/DESIGN_NEEDED.md`:** "Level-up gains" and "Experience subsystem and scaling" were already deleted when this
  design was approved. Confirm they are gone. The entries "Group, party and pet XP sharing" and "Skill-based advancement
  (worlds without character levels)" stay.
- Update `README.md` or the tutorial pages only if they mention levels.
- Run `./gradlew markdownFormat`, then `./gradlew check`.
- Make sure no code comment cites spec sections or decision IDs.

## Tests

Name tests by behavior, as the existing ones do. Each item is at least one test.

**The step up** (`CombatTest`/new `LevelingTest`, with a fixture ruleset): - `on_level_up`'s `stats`, `max` and
`current` deltas apply, `current` is capped at the new maximum, and the record holds `stats` and `max` only. - A kill
that crosses two thresholds applies two steps in order and sends two `LEVEL_UP` messages and one GMCP refresh. - A rules
error in `on_level_up` still raises the level, applies nothing and records nothing. - A non-increasing `xp_to_level`
stops the loop with one `rules_error` alert, and a value that never reaches the threshold applies nothing.

**The step down:** - Gains that used `ctx.rng` unwind exactly (stats and maxima equal their values before the level-up).
- A level with no record unwinds nothing and still drops. - `xp` is clamped to `xp_to_level(level) - 1` and any other
pending levels disappear. - `level_down(count=3)` from level 2 stops at 1 and returns 1. - A mob unwinds like a player.

**Death:** - `level_loss` lowers the level after the respawn, in the respawn room, and the respawn vitals are capped at
the lowered maxima. - `xp_loss` larger than `xp` leaves 0. - `level_loss` without `leveling` is refused at load. - The
template: dying with 1500 XP at level 2 loses 50, and dying at level 1 with 100 XP loses 10.

**Template rules:** - Kill XP: 5 levels below pays 0, equal pays `50 * level`, 2 levels above pays 140%, 4 above pays
150%. - `xp_to_level` gives 1000, 3000, 6000 and is strictly increasing to level 50. - `gups lint` is clean for the
template world and `samples/robot-world`.

**Auto mode:** - A kill, `award_xp` and a script's `level_up` all raise levels through `World.levelUp`: each level fires
`on_level_change` exactly once. - An award that owes more than 100 levels applies 100, and the next award applies more.

**Deferred mode** (the test world): - XP crossing a threshold sends `READY` once; a second award does not repeat it; the
level does not change. - `ctx.level_up` returns `True`, applies one level, and sends `READY` again only if another is
pending. - `level_up` with nothing pending returns `False` and changes nothing. - `score` shows the ready line only
while a level is pending. - `level_mode` without `leveling` fails to load naming the feature.

**Verbs:** - `award_xp` rejects 0 and negatives; `take_xp` returns the amount actually removed and never goes below 0 or
changes the level; `level_down` fires `LEVEL_DOWN` once for several steps. - Every verb and `pending_levels` fail with
the feature refusal when `leveling` is off (`FeaturesOffTest`, `FeatureMatrixTest`). - The plugin facade methods match,
and throw like `level` without `leveling`.

**The hook:** - Fires once per step, in order, for a kill, `award_xp`, `level_up`, `level_down` and `level_loss`; never
for `take_xp` or a plain XP gain. - Reaches the actor's own mob hook, the room, observers and the actor's carried and
equipped items, in the §5.6 order. - A hook that calls `award_xp` is cut off at depth 8 without a hang.

**Persistence:** - `levelGains` round-trips for a player and a mob. - A level lost is saved by an immediate checkpoint.

**Dry run:** - `on_level_up` that fails for a level-2 actor fails the rules package's load. - A `xp_to_level` that is
not strictly increasing in 1..50 fails it.

**API:** - The golden model; `ApiDiffTest` passes against the regenerated `api-1.json`; `since = 1` on the new symbols.

## Success criteria

- [] Every test above exists and passes.
- [] `./gradlew check` passes, including the module-graph check, with no Kotlin warnings.
- [] `SPEC.md` carries exactly the wording from step 1, `CHANGELOG.md`, `docs/api/api-1.json` and the golden `api.json`
  are updated, and neither `api_version` nor `PluginApi.VERSION` is bumped.
- [] `DESIGN_NEEDED.md` no longer has the two resolved entries and keeps the two new ones.
- [] A world with `leveling` off behaves as before and defines none of the new exports.
- [] No code comment cites spec sections or decision IDs.
