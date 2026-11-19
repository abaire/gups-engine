# Milestone 58: Spawn Rules for Mobs and Items

**Prerequisites:** [M57](MILESTONE_57.md) must be finished first (it is: `ExitPicker` exists in
`core/src/main/kotlin/gups/core/world/Actions.kt`). Where line numbers below are wrong, find the symbol by name.

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** [DESIGN_NEEDED.md](DESIGN_NEEDED.md): `## Items` → `### Item placements and one spawn-rule
  model for mobs and items (designed, awaiting a milestone plan)`. Its numbered "Agreed design" items 1–19 are binding;
  this milestone settles everything under "Left for the milestone plan".

- **Design Summary:** one spawn-rule model places both mobs and items. It is kernel (no feature gate).

  - **One rule type.** The prototype a rule names decides whether it places mobs or items.
    - `room(spawns = [spawn(label, ...)])` declares a rule for one room.
    - A top-level `spawner(id, label, rooms = ..., pick = None, ...)` in a `zone` package declares a pool of rooms. A
      room rule is a pool of one.
  - **Counting.** A rule counts only the copies it made itself.
    - **Items** count with `until = "gone"` (the default) until they leave the world.
      - Copies carried by a logged-out player count as **away**.
      - `until = "taken"` releases a copy once its location chain no longer ends in the room it was placed in, checked
        at each world tick.
    - **Mobs** count until they die, are despawned, or get an owner.
    - **A transform** keeps the copy counting. A tombstone is gone.
  - **Respawn modes.**
    - `respawn = "never"`: the first fill only.
    - `respawn = "reset"`: one batch at each of the declaring package's zone resets.
    - `respawn = pulses` or `(min, max)`: one batch per timer. Zone resets no longer refill timed rules.
  - **Cap and batches.** `max` caps the tracked copies, held copies included. `batch` (int or `(min, max)`, default: up
    to `max`) is what one refill adds. The first fill goes straight to `max`.
  - **Ranges.** `respawn`, `batch`, `stack` and `reset_world_ticks` take inclusive `(min, max)` ranges, rolled from the
    world RNG. A rolled deadline is saved.
  - **Picking.** A pool picks each copy's room on its own: even odds for a list, weights for a dict, or a `pick = fn`.
    The picked room is the copy's home.
  - **Held copies.**
    - A copy picked into a dormant room is held. It counts at once and is placed when the room wakes.
    - A copy whose `container` is missing, or that `fits` refuses, is held and retried at each world tick while its room
      is awake.
  - **Reset order.**
    1. The package's `"reset"` rules each add one batch. The order is room rules by room id, in declaration order within
       a room, then the package's `spawner`s in declaration order.
    2. `on_reset` then fires on the package's awake rooms, by ascending id.
    3. A dormant room, when it wakes, places its held copies in hold order, then runs the missed `on_reset` once.
    - Each missed reset adds its own batch.
  - **Identity.** A rule is identified by its `id`, or else by (room, prototype label, position among rules with the
    same label in that room). That identity carries its state across reloads and restarts.
    - The saved state is the binding on each copy's row, plus a `spawners` list in the declaring package's `zone_states`
      row.
    - Persistence `FORMAT_VERSION` goes back to `1`. There are no old saves to read.
  - **`on_place`** fires per copy after its `on_spawn`, with `ctx.spawner` set to the rule handle.
  - **Script surface.**
    - New views:
      - `spawned_by`, on item and actor views.
      - A frozen `spawner` handle value, with fields `id`, `label`, `room` and `rooms`.
    - New verbs:
      - `ctx.spawner_info(rule)` returns `struct(live, held, max, next_at, ran)`.
      - `ctx.refill(rule, count = None)`.
    - Facade counterparts: `PluginSpawner`, `PluginSpawnerInfo`, `spawnedBy`, `spawnerInfo`, `refill`.
  - **Tools.**
    - `w.reset(package = None)` in the harness runs a zone reset now.
    - `@spawners [label]` is a builder command guarded by the `spawners` capability.
  - **Breaking change.** `count` becomes `max` and `respawn_pulses` becomes `respawn`, with no alias once the final
    phase is done. Pre-release: no `api_version` bump and no prototype `version` bumps. Fix in-repo callers instead.
- **Specification Impact:**
  - §3.3: the Room "Spawns" bullet, and the Item and Mob state.
  - §3.4: reload.
  - §3.6: `zone_states` and the payload notes.
  - §4.1: `reset_world_ticks`.
  - §4.2: the example `spawns` line, new `spawner(...)` example, a new **Spawn rules** declaration-rules bullet, and the
    features bullet.
  - §4.4: the `on_place` row.
  - §4.5: context fields, item and actor views, the verb table, and a new **Spawn rules** bullet.
  - §4.9: `@spawners` and the capabilities list.
  - §4.11: starting.
  - §4.12: `w.reset`.
  - §4.15: facade verbs and views.
  - §5.1: world tick.
  - §5.4: **Respawns**.
  - §5.9: waking.
  - §5.11: restore.
- **Decision Record Updates:** None. `docs/DECISIONS.md` has no entries, and this milestone adds none.

### Conventions for every phase

1. Write the tests first, run them and watch them fail, then implement.
2. Comments explain *why*. Never cite spec sections, decision IDs or milestone numbers. All Kotlin warnings are errors.
3. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation.
4. In every phase that changes a script- or plugin-facing name or doc string, regenerate the API files before the
   phase's final `./gradlew check` (an empty diff is fine):
   - `./gradlew :cli:test -Dgups.updateGolden=true` rewrites `cli/src/test/resources/api.golden.json` and
     `cli/src/test/resources/docs-golden/`.
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` rewrites
     `docs/api/api-1.json`.
   - Read the diff: only what the phase names may change.
5. **CHANGELOG:** Phase 1 adds `### Spawn rules` at the top of `## api_version 1 (unreleased)` in `docs/CHANGELOG.md`,
   above `### Trick exits`. Each later phase appends its bullets. Don't bump `api_version`. Don't add prototype
   `version` bumps to any content.
6. Never read or touch `local-world/`. Scratch files go in `local-world-test/`, which you delete when done.
7. Messages given in quotes below are exact: tests assert them.
8. After changing code, run CodeScene `code_health_review` on the changed files and fix any drop. Spawn logic lives in
   the new `core/src/main/kotlin/gups/core/world/Spawners.kt`, not in `World.kt`, which is already long.
9. **The spec text below is approved wording.** Apply it exactly, then run `markdownFormat`, which re-pads tables. Where
   a phase says "replace", the old text must be gone afterwards.
10. **Plugin facade parity:** a new `ctx` verb or view field gets its `PluginWorld` or view counterpart in the same
    phase (`plugin-api/src/test/kotlin/gups/pluginapi/CtxParityTest.kt`). Context *fields* (`CtxFields`) are exempt
    automatically.
11. Every phase ends with `./gradlew check` passing.

### Shared vocabulary (used by every phase)

- **Tracked copies** of a rule are `live + away + held`:
  - `live`: in-world entities bound to the rule.
  - `away`: ids of bound items in a logged-out player's custody, or in a stowed pet's.
  - `held`: picked but not yet placed.
- **Short:** a rule is short when its tracked copies number fewer than `max`.
- **Arming a timer:**
  - Only timed rules have one.
  - When a timed rule is short and has no deadline, it rolls one: `respawnAt = pulse + respawn.pulses.roll(rng)`.
  - A rule that already has a deadline keeps it.
- **One batch:** `rule.batch?.roll(rng) ?: (max - tracked)`, capped at `max - tracked`. A batch never takes a rule above
  `max`.
- **First fill:**
  - Adds `max - tracked` copies, then sets `ran = true`.
  - Then a timed rule that is still short arms its timer. That happens when no pool room could be used.

---

## Phase 1: The rule model, respawn modes and resets (mobs, one room)

### Objective & Scope

- **In Scope:**
  - **New model:**
    - `SpawnRule` gets its new shape. `Roll`, `Respawn` and `Until` are new.
    - `ZoneProto.resetPulses: Int?` becomes `resetTicks: Roll?`.
  - **Refill logic** for mob rules declared in one room:
    - first fill, batches and the three respawn modes;
    - the timer rules, including "zone resets no longer refill timed rules";
    - held copies for dormant rooms;
    - the new reset order and wake order.
  - **Starlark:**
    - `spawn()` accepts `max`, `batch` and `respawn` (int, `(min, max)`, `"never"` or `"reset"`).
    - **Temporarily**, it also accepts `count` (maps to `max`) and `respawn_pulses` (maps to a fixed timed `respawn`).
      Passing an old name together with its new name is a load error.
    - `package(reset_world_ticks = ...)` accepts an int or a `(min, max)` tuple.
  - The Kotlin test fixtures that build `SpawnRule(...)` are updated mechanically (the list is below).
- **Out of Scope:** *Do not implement these; later phases do.*
  - Rule identity, `spawn(id = ...)`, and any persistence change. Saved bindings keep today's `(room, rule index)`
    shape, and only `respawnAt` is saved, until Phase 2.
  - Item rules, `until`, `stack` and `container` (Phase 3).
  - `spawner()` pools and `pick` (Phase 4).
  - Reload carry-over changes beyond compiling (Phase 5).
  - `on_place`, `spawned_by`, the handle, `spawner_info`, `refill`, the facade and `w.reset` (Phase 6).
  - `@spawners` (Phase 7).
  - Removing `count` and `respawn_pulses`, and the content and tutorial edits (Phase 8).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/Rng.kt` (`int` ~35).
  - `core/src/main/kotlin/gups/core/TimeConstants.kt` (`WORLD_TICK_PULSES = 120`).
  - `core/src/main/kotlin/gups/core/world/Owners.kt` (`releaseSpawn` ~85).
  - `core/src/main/kotlin/gups/core/world/Movement.kt` (`destinationFor` ~23: the weighted-roll pattern).
  - `script/src/main/kotlin/gups/script/RoomDeclarations.kt` (`roomSpawns` ~62).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`SpawnRule` ~132, `ZoneProto` ~489)
  - `core/src/main/kotlin/gups/core/world/Spawners.kt` (**new**: `SpawnState`, refill, timers, held copies, resets)
  - `core/src/main/kotlin/gups/core/world/World.kt` (remove `SpawnState` ~98 and `fillSpawn` ~741; `init` ~402,
    `runWorldTick` ~1027, `resetPulse`/`respawnPulse`/`resetZone` ~1062–1131 and `start` ~1145 now call into
    `Spawners.kt`)
  - `core/src/main/kotlin/gups/core/world/Owners.kt` (`releaseSpawn`: arm through the shared helper)
  - `core/src/main/kotlin/gups/core/world/Bubble.kt` (`replayMissedSpawns` ~289)
  - `core/src/main/kotlin/gups/core/world/Restore.kt` (`bindSpawn` cap uses `max`; `fillMissingSpawns` per below)
  - `core/src/main/kotlin/gups/core/world/Reconcile.kt` (compile: `rule.count` → `rule.max`, timer arming, `resetTicks`)
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`spawn` ~251)
  - `script/src/main/kotlin/gups/script/DeclarationSpecs.kt` (`SPAWN_SPEC` ~356)
  - `script/src/main/kotlin/gups/script/PackageDeclarations.kt` (`resetPulses` ~26)
  - `script/src/main/kotlin/gups/script/Packages.kt`, `script/src/main/kotlin/gups/script/Loader.kt` (`resetPulses`
    callers)
  - Mechanical fixture updates:
    `core/src/test/kotlin/gups/core/world/{BubbleTest,CombatTest,DespawnTest,OwnersTest}.kt`,
    `cli/src/test/kotlin/gups/cli/CliTestFixtures.kt`, `persist/src/test/kotlin/gups/persist/PersistFixture.kt`,
    `script/src/test/kotlin/gups/script/{ApiModelTest,LoaderTest}.kt` (`resetPulses`).
  - Tests: new `core/src/test/kotlin/gups/core/world/SpawnRulesTest.kt`, plus
    `script/src/test/kotlin/gups/script/LoaderTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
  - **Tests broken by "resets no longer refill timed rules":** first run `grep -rln "w.run(pulses"
    --include='*_test.star' template-world samples` and the Kotlin harness tests. Known candidates are
    `template-world/zones/tutorial/{tutorial_test,group_test,destroy_test}.star`. Where a test kills or despawns a
    spawned mob and then waits for a zone reset to bring it back, fix the *test* (wait for the rule's timer, `1200`
    pulses plus one world tick, instead). Don't change content `.star` files in this phase.
- **Do Not Touch:** `persist/src/main/`, `Views.kt`, `plugin-api/src/main/`, non-test `.star` files in `template-world/`
  and `samples/`.

### Specification & Decision Record Changes

- §4.1, the `reset_world_ticks` line of the example: replace it with `reset_world_ticks=60, # zone reset interval in
  world ticks (30 min), or a (min, max) range; fires on_reset`. Under the paragraph after the code block, add:

  > `reset_world_ticks` is an int of at least 1, or an inclusive `(min, max)` range rolled with even odds after every
  > reset, from the pulse the reset ran on. Leaving it out means the zone never resets.

- §4.2, the `spawns=` line of the `room(...)` example: replace with `spawns=[spawn(":goblin_scout", max=2, respawn=1200,
  at_index=2)], # spawn rules (below)`.
- §4.2 **Declaration rules**: insert after the **Exits picked per crossing** bullet:

  > - **Spawn rules:** `spawn(label, max = 1, batch = None, respawn = 1200, at_index = 0)` keeps up to `max` copies of
  >   the mob `label` in the room. A rule counts only the copies it made itself.
  >   - `respawn` says when it adds more:
  >     - `"never"`: nothing after the first fill;
  >     - `"reset"`: one batch at each of the package's zone resets, and only then;
  >     - pulses, or a `(min, max)` range: one batch when that many pulses have passed since the rule first fell short.
  >       Zone resets don't refill a timed rule.
  >   - `batch` (an int or `(min, max)`; by default as many as are missing) is how many copies one refill adds, never
  >     above `max`. The first fill (when the world starts) always goes straight to `max`.
  >   - **Ranges** are inclusive and rolled with even odds from the world RNG. `min <= max`, `max >= 1`, `batch >= 1`
  >     and pulses `>= 1` are checked at load.

- §3.3, Room, the **Spawns and commands** bullet: replace "mob `spawns` rules" with "`spawns` rules (§4.2)".

- §5.4, the **Respawns** bullet: replace it with:

  > - **Respawns:** a spawn rule (§4.2) is short when it tracks fewer than `max` copies. Copies a refill picks into a
  >   dormant room are *held*: they count at once and are placed when the room wakes (§5.9). A rule's first fill places
  >   its copies whether or not the room is dormant.
  >   - **Timed rules:** a timed rule that is short and has no deadline rolls one. That happens when it first falls
  >     short, and again after a timed batch that leaves it short. Another copy leaving doesn't move a pending deadline.
  >     At the first world tick at or after the deadline, the rule adds one batch.
  >   - **A zone reset:**
  >     1. Every `"reset"` rule of the package adds one batch: room rules by room id, in declaration order within a
  >        room.
  >     2. `on_reset` then fires on the package's rooms that aren't dormant, ascending id.
  >     3. A dormant room of the package is marked, and runs the `on_reset` it missed when it wakes.
  >   - **Starting:** a fresh world fills every rule to `max` when it starts (§4.11).

- §5.1 phase 4 list: replace "Process zone resets and respawns that are due." with "Run the zone resets that are due,
  then the spawn rules' timed batches that are due (§5.4)."

- §5.9 **Waking** list: replace items 4 and 5 with one item, then renumber the rest (6 becomes 5, 7 becomes 6):

  > 4. The room's held spawn copies are placed, in the order they were held, with their hooks (§5.4); then, if the room
  >    missed a zone reset, its `on_reset` runs once.

  In item 7 (now 6), replace "as they were before the respawns" with "as they were before the held copies were placed".

- CHANGELOG, a new subsection at the top of `## api_version 1 (unreleased)`:

  ```markdown
  ### Spawn rules

  - `spawn()` takes `max` (was `count`), `batch` and `respawn` (pulses, a `(min, max)` range, `"never"` or `"reset"`;
    was `respawn_pulses`). Zone resets no longer refill timed rules: a timed rule refills only on its own timer. Before:
    `spawn(":goblin", count = 3, respawn_pulses = 600)`; after: `spawn(":goblin", max = 3, respawn = 600)`.
  - `package(reset_world_ticks = ...)` also takes a `(min, max)` range, rolled after every reset.
  ```

### Implementation Blueprint

#### Signatures & Types

```kotlin
// Protos.kt
/** A fixed value, or an inclusive range rolled with even odds. */
data class Roll(val min: Int, val max: Int) {
  init { require(min <= max) { "a range needs min <= max, got ($min, $max)" } }
  fun roll(rng: Rng): Int = if (min == max) min else rng.int(min, max)
  companion object { fun of(n: Int) = Roll(n, n) }
}

/** When a spawn rule adds copies after its first fill. */
sealed interface Respawn {
  data object Never : Respawn
  data object Reset : Respawn
  data class Timed(val pulses: Roll) : Respawn
}

/** When a placed item stops counting toward its rule. */
enum class Until(val word: String) { GONE("gone"), TAKEN("taken") }

data class SpawnRule(
  /** Prototype label of the mob or item to place. */
  val label: Label,
  /** The rule's own label, from spawn(id=) or spawner(id); null for an unnamed room rule. Set in Phase 2. */
  val id: Label? = null,
  val max: Int = 1,
  /** Copies one refill adds; null adds as many as are missing. */
  val batch: Roll? = null,
  val respawn: Respawn = Respawn.Timed(Roll.of(1200)),
  /** Items only; null means GONE for an item rule. Used from Phase 3. */
  val until: Until? = null,
  /** Stack size of a stackable item copy; null means 1. Used from Phase 3. */
  val stack: Roll? = null,
  /** Container prototype the copy goes into. Used from Phase 3. */
  val container: Label? = null,
  val atIndex: Int = 0,
)

data class ZoneProto(
  val pkg: String,
  val title: String = pkg,
  /** Reset interval in world ticks, or null if the zone never resets. */
  val resetTicks: Roll? = null,
  val modifiers: Modifiers = emptyMap(),
)
```

```kotlin
// Spawners.kt (new)
/** A copy picked into a dormant room (or, from Phase 3, waiting for its container), not yet an entity. */
class HeldCopy internal constructor(val room: Label, val stack: Int?, val seq: Long)

/** The state of one spawn rule. */
class SpawnState internal constructor(val room: Room, val rule: SpawnRule) {
  internal val live = sortedSetOf<EntityId>()
  internal val held = ArrayDeque<HeldCopy>()
  internal var respawnAt: Long? = null
  internal var ran = false
  internal val tracked: Int get() = live.size + held.size
  internal val short: Boolean get() = tracked < rule.max
}

internal fun World.firstFill(s: SpawnState)
internal fun World.addBatch(s: SpawnState)
internal fun World.addCopies(s: SpawnState, n: Int)        // capped at max - tracked
internal fun World.armTimer(s: SpawnState)
internal fun World.releaseCopy(s: SpawnState, id: EntityId)  // removes from live, then arms
internal fun World.timedBatchesDue()                         // the world tick's spawn step
internal fun World.resetZone(pkg: String)                    // moved here from World.kt
internal fun World.placeHeldCopies(room: Room)              // the wake step
```

`World` gets `internal var nextHeldSeq: Long = 1`. Held copies are placed in `seq` order across rules.
`World.zoneResets` stays `pkg -> pulse`.

#### Algorithmic Steps & Invariants

1. **Starlark `spawn()`** (`Declarations.kt` ~251, `SPAWN_SPEC`):
   - Add the params `max` (int, default 1), `batch` (int or tuple, default None) and `respawn` (int, tuple, `"never"` or
     `"reset"`, default 1200).
   - Keep `count` and `respawn_pulses` with docs saying "Deprecated alias of max; removed soon." and "Deprecated alias
     of respawn; removed soon." Passing `count` with `max`, or `respawn_pulses` with `respawn`, fails: `"spawn(): pass
     max or count, not both"` and `"spawn(): pass respawn or respawn_pulses, not both"`.
   - Parse ranges with one helper, `parseRoll(raw, what, minAllowed)`:
     - An int gives `Roll.of(n)`.
     - A 2-tuple of ints gives `Roll(a, b)`.
     - Failures: `"<what>: expected an int or a (min, max) tuple"`, `"<what>: min must not exceed max"`, `"<what>: must
       be at least <minAllowed>"`.
     - `<what>` is, for example, `spawn(respawn)`.
   - Bounds: `max >= 1`, `batch >= 1`, timed pulses `>= 1`. Any other string for `respawn` fails with `"spawn(respawn):
     expected pulses, a (min, max) range, \"never\" or \"reset\""`.
2. **`reset_world_ticks`** (`PackageDeclarations.kt`): takes an int or a tuple via `parseRoll`, `>= 1`. Keep the
   overflow check against `Int.MAX_VALUE / WORLD_TICK_PULSES` on `max`. Store ticks, not pulses: the interval rolled is
   `resetTicks.roll(rng) * WORLD_TICK_PULSES`.
3. **`World` `init`** (~402): build `SpawnState(room, rule)` exactly as today (room order, then declaration order). Seed
   each zone's first reset with a rolled interval: `zoneResets[pkg] = pulse + zone.resetTicks.roll(rng) *
   WORLD_TICK_PULSES`.
4. **`addCopies(s, n, holdIfDormant = true)`:**
   - Compute `k = min(n, s.rule.max - s.tracked)`.
   - Repeat `k` times:
     - If `holdIfDormant && s.room.isDormant`, append `HeldCopy(s.room.label, null, nextHeldSeq++)`.
     - **Why the flag:** `World.init` calls `startDormant()`, so every room is dormant when `start()` runs the first
       fill. Holding there would leave a fresh world with no mobs. The first fill (world start, a restored rule that
       never ran, a new rule at reload) always passes `holdIfDormant = false` and places entities, as `fillSpawn` does
       today. Only timed batches, `"reset"` batches and (Phase 6) `ctx.refill` hold.
     - Otherwise call `spawnMob(rule.label, room, rule.atIndex.coerceIn(0, room.trackSize - 1)) { m -> m.spawnedBy = s;
       s.live += m.id }`. Using `prepare` makes the binding visible before `on_spawn`.
   - A spawn failure inside `guarded` leaves the rule short. Callers arm the timer afterwards.
5. **`addBatch(s)`:** compute the batch size from the shared vocabulary, then `addCopies`.
6. **`firstFill(s)`:** `addCopies(s, s.rule.max, holdIfDormant = false)`, `s.ran = true`, then `armTimer(s)`.
7. **`armTimer(s)`:** only for `Respawn.Timed` with `s.short && s.respawnAt == null`, set `s.respawnAt = pulse +
   pulses.roll(rng)`.
8. **`releaseSpawn(m)`** (`Owners.kt`): `releaseCopy(s, m.id)`, then `m.spawnedBy = null`. `releaseCopy` removes the id
   from `live`, then calls `armTimer`.
9. **The world tick** (`runWorldTick`): replace `resetPulse(); respawnPulse()` with `resetPulse(); timedBatchesDue()`.
   `timedBatchesDue` walks `spawnStates` in order. For each timed `s` with `respawnAt <= pulse`, it sets `respawnAt =
   null`, calls `addBatch(s)`, then `armTimer(s)`, each inside `guarded("respawn of ${s.rule.label}")`. **Dormant rooms
   are not skipped any more:** their copies are held instead.
10. **`resetZone(pkg)`:**
    - Roll and store the next deadline.
    - Mark the package's dormant rooms `resetMissed = true`.
    - For each `s` in `spawnStates` whose `room.label.pkg == pkg` and whose `rule.respawn == Respawn.Reset`, call
      `addBatch(s)`, guarded. Dormant rooms hold.
    - Fire `on_reset` on the package's awake rooms, by ascending id.
    - Timed and `"never"` rules are untouched.
11. **`placeHeldCopies(room)`** (called by `Bubble.replayMissedSpawns` in place of today's body):
    - Collect every held copy across `spawnStates` whose `room == room.label`, sort by `seq`, and place each one. Remove
      it from `held` first, then spawn and add it to `live`, guarded.
    - Then, if `room.resetMissed`, clear the flag and fire `on_reset` once.
    - There is no other refill on wake.
12. **`start()`:** `if (fillSpawnsAtStart) for (s in spawnStates) firstFill(s)`.
13. **`Restore.kt`:**
    - `bindSpawn` caps on `max` for now (Phase 2 removes the cap).
    - `fillMissingSpawns` keeps today's behavior, so §5.11 stays true in this phase: every rule that is short with no
      deadline is topped up to `max` with `addCopies(s, s.rule.max, holdIfDormant = false)`, in every mode. Mark every
      rule `ran = true`.
    - The doc comment says this is interim until saved rule state arrives. Phase 2 replaces the behavior and the spec
      wording together.
14. **`Reconcile.kt`:** compile fixes only. `updateRespawn` keeps `prev.respawnAt`, else calls `armTimer`. Newly created
    rooms' rules get `firstFill`. Carry `ran` and `held` over with `live`.
15. **Invariant:** `tracked <= max` is never broken by a refill. It may be above `max` only through a reload lowering
    `max` (Phase 5).

### Test & Documentation Requirements

- New `core/src/test/kotlin/gups/core/world/SpawnRulesTest.kt` (build `World`s directly, as `BubbleTest` does):
  - The first fill reaches `max` whatever `batch` is.
  - A fresh world with the bubble on and no players (every room dormant) has its spawned mobs as entities after
    `start()`, with nothing held.
  - A timed rule with `batch = Roll.of(1)`: killing two of three starts one timer (rolled once). The second death
    doesn't move it. At the deadline one mob comes back, and a new timer is rolled at once. After the second deadline
    the rule is at `max` and has no timer.
  - A timed rule is **not** refilled by a zone reset.
  - A `Reset` rule refills one batch at the reset and never on a timer.
  - A `Never` rule never refills.
  - A range `respawn = Roll(10, 20)` with `rng.force` lands exactly where forced, on the first world tick at or after
    it.
  - A dormant room: a due timer holds the copy. `tracked` counts it and there's no entity. Waking the room places it,
    with `on_spawn`, before the missed `on_reset`.
  - Two resets missed while dormant add two batches, capped at `max`.
  - `reset_world_ticks` as `Roll(2, 4)` with a forced roll schedules the next reset at that many world ticks after the
    reset pulse.
- `LoaderTest`:
  - `spawn(max, batch, respawn)` parse, both tuple and string forms.
  - Each error message above.
  - `count` / `respawn_pulses` still work.
  - `count` + `max` fails.
  - `reset_world_ticks = (2, 1)` fails.

### Exit Conditions

- [] `./gradlew :core:test :script:test :persist:test :cli:test` pass.
- [] `./gradlew check` passes with zero warnings, after the golden regeneration (the new `spawn` params and their docs,
  `reset_world_ticks`).
- [] `grep -rn "resetPulses\|\.count\b.*rule\|rule\.count" core script persist cli --include='*.kt'` finds no stale use.

---

## Phase 2: Rule identity and saved rule state

### Objective & Scope

- **In Scope:**
  - `SpawnKey`, and `spawn(id = ...)` with its uniqueness check.
  - Saved mob bindings by key.
  - `zone_states` `spawners` (`at`, `ran`, `held`), replacing `respawns`.
  - Restore without the `max` cap.
  - Settling restored rules (first fill for rules that never ran).
  - `FORMAT_VERSION` reset to `1`.
- **Out of Scope:** *Do not implement these; later phases do.*
  - Item bindings, `home`, the away set (Phase 3).
  - Pools (Phase 4).
  - Reload matching by key (Phase 5). Reload keeps its label-based matching until then, but carries `ran` and `held`.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Spawners.kt` (Phase 1).
  - `persist/src/main/kotlin/gups/persist/SqlitePersistence.kt` (`restore` ~230–250, `restoreMobs` ~290,
    `restoreZoneState` ~322, `zoneStates` ~539).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Spawners.kt` (`SpawnKey`, `SpawnState.key`)
  - `core/src/main/kotlin/gups/core/world/Restore.kt` (replace `bindSpawn`, `respawnDeadlines`, `setRespawn`,
    `fillMissingSpawns`, `spawnOf`)
  - `core/src/main/kotlin/gups/core/world/World.kt` (`init`: keys, duplicate-id check)
  - `persist/src/main/kotlin/gups/persist/Codec.kt` (`SpawnJson`, `RespawnJson` → `SpawnerStateJson`, `ZonePayload`,
    `FORMAT_VERSION`, the KDoc)
  - `persist/src/main/kotlin/gups/persist/SqlitePersistence.kt`
  - `script/src/main/kotlin/gups/script/Declarations.kt`, `script/src/main/kotlin/gups/script/DeclarationSpecs.kt` (`id`
    param), `script/src/main/kotlin/gups/script/RoomDeclarations.kt` (duplicate ids within a package)
  - Tests: `persist/src/test/kotlin/gups/persist/` (new `SpawnStatePersistenceTest.kt`), `LoaderTest`, and
    `persist/src/test/kotlin/gups/persist/SchemaPersistenceTest.kt` (~79 asserts `"format_version":3`, now `1`). Run
    `grep -rn "format_version" --include='*.kt' --include='*.json' .` (excluding `local-world` and `build`) for any
    other fixture that hardcodes the number; `Repro.kt` shares the constant.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `Reconcile.kt` beyond compiling, `Views.kt`, `plugin-api/`.

### Specification & Decision Record Changes

- §4.2 **Spawn rules** bullet: append:

  > - **Naming a rule:** `spawn(..., id = "x")` gives a rule the label `//pkg:x`. Without one, a rule is known by its
  >   room, its prototype label and its position among rules with that label in the room. Two rules of a package with
  >   the same id fail its load. A rule keeps its copies, held copies, pending deadline and whether it has run across
  >   reloads and restarts for as long as its label (or, without one, its room, prototype and position) stays the same.

- §3.6 table, the `zone_states` row's last column: replace "reset and respawn deadlines, package quarantine flags" with
  "zone reset deadlines, spawn rule state (pending deadlines, whether each rule has run, held copies), package
  quarantine flags".

- §3.6 payload notes, Mobs: "spawn rule bindings" stays. Add a new sub-bullet after the Mobs one:

  > - Zones store the next reset pulse and, for each spawn rule the package declares that has run, its pending deadline
  >   and its held copies (each with its picked room and rolled stack size), so a restart rolls nothing again.

- §5.11 restore step 3: replace "Saved spawn bindings and respawn deadlines are restored. A rule that is short with no
  respawn pending (for example a new rule) fills at once." with:

  > - Saved spawn bindings, deadlines and held copies are restored, whatever the rule's `max` now is. A rule that has
  >   never run (a new rule) does its first fill. A timed rule that is short with no deadline rolls one.

- CHANGELOG: append under `### Spawn rules`: "- `spawn(id = ...)` names a rule, so its state survives edits that reorder
  a room's rules."

### Implementation Blueprint

#### Signatures & Types

```kotlin
// Spawners.kt
/** A spawn rule's identity across reloads and restarts. */
sealed interface SpawnKey {
  data class Named(val id: Label) : SpawnKey
  data class InRoom(val room: Label, val label: Label, val n: Int) : SpawnKey
}

class SpawnState internal constructor(val key: SpawnKey, val room: Room, val rule: SpawnRule) { /* as Phase 1 */ }

/** What a checkpoint saves of a rule; only rules that have run are saved. */
data class SavedSpawner(val key: SpawnKey, val at: Long?, val ran: Boolean, val held: List<HeldCopy>)

// Restore.kt (public: persist is another module)
fun World.spawnKeyOf(mob: Mob): SpawnKey?
fun World.bindSpawn(mob: Mob, key: SpawnKey): Boolean          // no max cap; false for an unknown key
fun World.savedSpawners(pkg: String): List<SavedSpawner>
fun World.restoreSpawner(saved: SavedSpawner)                  // unknown key: ignored
fun World.settleRestoredSpawns()                               // replaces fillMissingSpawns
```

```kotlin
// Codec.kt
const val FORMAT_VERSION = 1

@Serializable
data class SpawnJson(
  val id: String? = null,
  val room: String? = null,
  val label: String? = null,
  val n: Int? = null,
  /** Items only, from Phase 3: the room the copy was placed in. */
  val home: String? = null,
)

@Serializable data class HeldJson(val room: String, val stack: Int? = null, val seq: Long)

@Serializable
data class SpawnerStateJson(val key: SpawnJson, val at: Long? = null, val ran: Boolean = true, val held: List<HeldJson> = emptyList())

@Serializable
data class ZonePayload(
  @EncodeDefault @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
  val resetAt: Long? = null,
  val quarantined: Boolean = false,
  val spawners: List<SpawnerStateJson> = emptyList(),
)
```

`SpawnJson` with `id` set encodes `SpawnKey.Named`, and with `room`/`label`/`n` encodes `SpawnKey.InRoom`. Put
`SpawnKey.toJson()` and `SpawnJson.toKey()` in `Codec.kt`.

#### Algorithmic Steps & Invariants

1. **Keys** (`World.init`):
   - With an id, `SpawnKey.Named(rule.id)`.
   - Otherwise `SpawnKey.InRoom(room.label, rule.label, n)`, where `n` counts earlier rules in that room with the same
     label and no id.
   - Two states with the same key fail world construction: `"duplicate spawn rule id ${id}"`. The loader checks
     duplicates first (step 5), so this is a backstop.
2. **Saving** (`SqlitePersistence.zoneStates`): for each zone package, `spawners = world.savedSpawners(pkg)`. Include
   every rule declared in the package with `ran = true`. Mob payloads write `spawn = spawnKeyOf(m)?.toJson()`.
3. **Restoring:**
   - `restoreMobs` binds with `bindSpawn(m, key)`, with no cap. `restoreZoneState` calls `restoreSpawner` per entry,
     setting `respawnAt`, `ran` and `held` (each held entry's `seq`).
   - After everything, `world.nextHeldSeq = max(saved seq) + 1`, then `settleRestoredSpawns()`.
   - `settleRestoredSpawns`: a rule with `!ran` gets `firstFill`. Otherwise a timed rule arms its timer.
4. **`--fresh`** already rewrites zone rows to empty, so every rule starts with `ran = false` and does its first fill.
   Keep that.
5. **Loader:** `spawn(id = "x")` resolves to `//pkg:x`. Track the ids per package across files. A duplicate fails:
   `"spawn(id): //pkg:x is already a spawn rule id"`. Phase 4 adds `spawner` ids to the same set.
6. Delete `RespawnJson`, `respawnDeadlines`, `setRespawn`, `spawnOf` and `fillMissingSpawns`. Update the `Codec.kt`
   KDoc's **Zones** line to "Persists the zone reset deadline, each spawn rule's saved state (deadline, ran flag, held
   copies), and package quarantine status."

### Test & Documentation Requirements

- `SpawnStatePersistenceTest.kt`, with `PersistFixture`'s checkpoint-and-restore loop:
  - A held copy and a pending rolled deadline survive a restart unchanged.
  - A `"never"` rule doesn't fill again after a restart; `--fresh` fills it.
  - A restored binding above a lowered `max` is kept.
  - A rule that is new since the save does its first fill.
  - Two unnamed rules for the same label in a room bind back to the right ones.
  - An unknown saved key is ignored without error.
- `LoaderTest`: a duplicate `spawn(id)` in one package fails with the message above.

### Exit Conditions

- [] `./gradlew :core:test :persist:test :script:test` pass.
- [] `./gradlew check` passes, after the golden regeneration (the new `id` param).
- [] `grep -rn "RespawnJson\|respawnDeadlines\|fillMissingSpawns" --include='*.kt' .` (excluding `local-world`) is
  empty.

---

## Phase 3: Item rules

### Objective & Scope

- **In Scope:**
  - Rules whose label is an item: placement, `stack`, `container` and the container retry, `until` `gone` and `taken`.
  - Every point where an item leaves the world or goes away.
  - Item bindings with `home` on item rows, and rebuilding the away set from player-held rows.
- **Out of Scope:** *Do not implement these; later phases do.*
  - Pools (Phase 4).
  - Reload carry-over by key (Phase 5).
  - Hooks, views and verbs (Phase 6).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Items.kt` (`spawnItem` ~17, `moveItem` ~33, `removeItem` ~270,
    `becomeVariant`, `custodian`).
  - `core/src/main/kotlin/gups/core/world/Destroy.kt`.
  - `core/src/main/kotlin/gups/core/world/Sessions.kt` (logout ~190–220).
  - `script/src/main/kotlin/gups/script/CtxQueries.kt` (`find` scope `"room"`, and `roomOfEntity`: the chain rule).
  - `persist/src/main/kotlin/gups/persist/ItemPlacement.kt`, and in `SqlitePersistence.kt`: `ownerOf` ~355 and
    `loadCharacter` ~365.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Spawners.kt` (item placement, away, the taken check, container retries)
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Item`: `spawnedBy`, `spawnHome`)
  - `core/src/main/kotlin/gups/core/world/Items.kt` (`spawnItem` gets a `prepare` param; `removeItem` releases)
  - `core/src/main/kotlin/gups/core/world/World.kt` (`removeActor`: away versus release; `init` validates item rules)
  - `core/src/main/kotlin/gups/core/world/Reconcile.kt` (tombstoning releases)
  - `core/src/main/kotlin/gups/core/world/Restore.kt` (item bindings, `bindAwaySpawn`)
  - `persist/src/main/kotlin/gups/persist/Codec.kt` (`ItemPayload.spawn`),
    `persist/src/main/kotlin/gups/persist/SqlitePersistence.kt`, `persist/src/main/kotlin/gups/persist/ItemPlacement.kt`
  - `script/src/main/kotlin/gups/script/Declarations.kt`, `DeclarationSpecs.kt`, `RoomDeclarations.kt` (params and load
    checks)
  - Tests: `core/src/test/kotlin/gups/core/world/ItemSpawnRulesTest.kt` (new),
    `persist/src/test/kotlin/gups/persist/SpawnStatePersistenceTest.kt`, `LoaderTest`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `Views.kt`, `plugin-api/`, `harness/`.

### Specification & Decision Record Changes

- §4.2 **Spawn rules** bullet: replace its first sentence ("`spawn(label, max = 1, ...)` keeps up to `max` copies of the
  mob `label` in the room.") with:

  > `spawn(label, id = None, max = 1, batch = None, respawn = 1200, until = None, stack = None, container = None,
  > at_index = 0)` keeps up to `max` copies of the mob or item `label` in the room. Copies from loot, quests,
  > `ctx.spawn` or `@spawn` never count toward a rule.

  Then append these sub-bullets:

  > - **Mobs** count until they leave the world (death, `despawn`) or get an owner (`set_owner`).
  > - **Items:**
  >   - **`until = "gone"`** (the default): an item counts wherever it ends up, a logged-out player's pack included,
  >     until it leaves the world: destroyed, decayed, used up, merged into another stack, or turned into a tombstone.
  >     `transform` keeps it counting.
  >   - **`until = "taken"`:** an item counts while its location chain ends in the room it was placed in, as `find(...,
  >     scope = "room")` counts, checked at each world tick.
  >   - **Stacks:** a copy is one item. `stack` (an int or `(min, max)`, default 1) sets a stackable copy's size; `max`
  >     and `batch` count items, not units. Taking part of a stack splits off a new item that doesn't count. The rest
  >     keeps counting.
  >   - **Containers:** `container = ":chest"` puts the copy into the first container of that prototype lying in the
  >     room (lowest id), whether open, closed or locked. Without one, or when `fits` refuses, the copy is held and
  >     tried again at each world tick while the room isn't dormant. It is never left on the floor instead. `at_index`
  >     is ignored with `container`.
  >   - **Mob rules:** `until`, `stack` and `container` are load errors on a mob rule. `stack` is also a load error on
  >     an item that doesn't stack.

- §3.3 Item: append a bullet:

  > - **Spawn binding:** the spawn rule that placed the item, if any, and the room it placed it in.

- §3.6 payload notes, Items: append "the spawn rule binding and the room the rule placed the item in;" to the persisted
  list. Append to the Zones sub-bullet: "Copies in a logged-out player's custody count toward their rule: restore counts
  them from the player-held item rows without placing them."

- §5.11, the restore bullet from Phase 2: append "Items bound to a rule and held by a saved player count toward it while
  the player is logged out."

- CHANGELOG: append "- Spawn rules can place items: `until`, `stack` and `container`. A copy carried off by a player who
  logs out keeps counting."

### Implementation Blueprint

#### Signatures & Types

```kotlin
// Entities.kt, Item
internal var spawnedBy: SpawnState? = null
/** The room the rule placed this copy in; until = "taken" and the saved binding read it. */
internal var spawnHome: Label? = null

// Spawners.kt
class SpawnState /* ... */ {
  internal val away = sortedSetOf<EntityId>()
  internal val tracked: Int get() = live.size + away.size + held.size
  internal val isItemRule: Boolean // label names an item prototype
  internal val until: Until get() = rule.until ?: Until.GONE
}
internal fun World.checkTakenCopies()                  // every world tick, before timedBatchesDue
internal fun World.retryHeldInAwakeRooms()             // every world tick, after timedBatchesDue
internal fun World.departTree(item: Item, away: Boolean) // release or mark away, recursively

// Items.kt
fun World.spawnItem(label: Label, at: ItemLocation, count: Int = 1, debug: Boolean = false, prepare: (Item) -> Unit = {}): Item

// Restore.kt
fun World.spawnKeyOf(item: Item): SpawnKey?
fun World.spawnHomeOf(item: Item): Label?
fun World.bindSpawn(item: Item, key: SpawnKey, home: Label?, fromAway: Boolean): Boolean
fun World.bindAwaySpawn(itemId: EntityId, key: SpawnKey)
```

#### Algorithmic Steps & Invariants

1. **Load checks** (`spawn()` and `roomSpawns`):
   - **Resolution:** `label` must resolve to a mob or an item. That's checked after every package loads, as spawn labels
     are now. In `World.init`, `protos.mobs[label] ?: protos.items[label]`. An unknown label fails as today.
   - **Mob rules:** `until`, `stack` or `container` on a mob rule fail: `"spawn(until): only item rules have until"`
     (and likewise for `stack` and `container`).
   - **Stack:** `stack` on a non-stackable item fails: `"spawn(stack): <label> doesn't stack"`.
   - **Until:** `until` must be `"gone"` or `"taken"`.
   - **Container:** `container` must resolve to an item prototype with `container(...)`, also checked in `World.init`.
   - **Order:** these checks need the prototype, so they run where the label is resolved.
2. **Placing an item copy**, in `addCopies` and when placing a held one:
   - **Roll:** `stack = rule.stack?.roll(rng)`, only when the copy is first picked. A held copy keeps its rolled
     `stack`.
   - **Dormant room:** hold it.
   - **With `container`:** find `room.items` (the floor) filtered to `protoLabel == container`, lowest id. If there's
     none, or `fits(label, box, stack)` refuses, hold it (`HeldCopy(room, stack, seq)`). Otherwise `spawnItem(label,
     ItemLocation.Contained(box.id), stack ?: 1) { bind }`.
   - **Without one:** `spawnItem(label, ItemLocation.InRoom(room.id, atIndex.coerceIn(0, room.trackSize - 1)), stack ?:
     1) { bind }`.
   - **Bind:** `it.spawnedBy = s; it.spawnHome = room.label; s.live += it.id`, inside `prepare`, so it's set before
     `on_spawn`. Call `prepare` in `spawnItem` right after `register(item)`.
3. **`retryHeldInAwakeRooms()`:** after `timedBatchesDue`. Walk held copies in `seq` order. For each whose room exists
   and isn't dormant, try to place it: success places, a container miss leaves it held. Guarded.
4. **Leaving the world** (release the copy and arm the timer):
   - **`removeItem` (`Items.kt`):** before `forgetItemTree`, call `departTree(item, away = false)`. That covers destroy
     (verb, decay, empty), merges, reload removals and deleted prototypes.
   - **Tombstoning in `Reconcile.kt`:** where an item becomes a tombstone, release its binding.
   - **`removeActor(a, stow)`:**
     - A non-stowed mob: `departTree(item, away = false)` for every carried and worn item.
     - A player, or a stowed pet: `departTree(item, away = true)`.
     - Call it before `forgetItemTree`.
   - **`departTree`:** for every item in the tree with `spawnedBy = s`:
     - When `away && s.until == GONE`, move the id from `live` to `away`. The item keeps `spawnedBy` and `spawnHome`; it
       is about to be forgotten.
     - Otherwise call `releaseCopy(s, id)` and clear `spawnedBy` and `spawnHome`.
   - **Partial destroys and splits:** a partial `destroy` (`reduce`) and a split by `moveItem` don't touch the binding.
     The moved part is a new item without one. Check that the split copies `customAttributes` but **not** `spawnedBy`.
   - **`transform` and `becomeVariant`:** no change. The entity keeps its binding.
5. **`checkTakenCopies()`:** for each item rule with `until == TAKEN`, and each id in `live`, release the id when
   `item(id)` is null or the room its location chain ends in (the same chain `find(scope = "room")` uses: carried or
   worn by an actor in the room, or inside a container that is) isn't `spawnHome`. A `TAKEN` rule never has away copies,
   because step 4 releases them.
6. **Persistence:**
   - **Saving:** `ItemPayload` gets `val spawn: SpawnJson? = null`, written as `spawnKeyOf(item)` plus `home =
     spawnHomeOf(item)`.
   - **World items at restore:** in `placeItems` (used at start and at login), after an item is placed, if its row has
     `spawn`, call `bindSpawn(item, key, home, fromAway = <login>)`.
   - **At start (`fromAway = false`):** bind into `live`, with no cap.
   - **At login (`fromAway = true`):** bind only if the id is in that rule's `away` set. Move it to `live`; otherwise
     leave the item unbound.
     - **Why this matters:** after `--fresh`, or when the rule was removed, the item comes back untracked and the next
       capture writes no binding.
   - **Away rebuild:** in `restore` (not on `--fresh`), after `restoreZoneStates` and before `settleRestoredSpawns`,
     scan `entityRows` for item rows whose `ownerOf(row) != null` (held by a saved player, directly or through a pet)
     that have `spawn` with a `GONE` rule. Call `world.bindAwaySpawn(EntityId(row.id), key)`.
     - Rows whose character is gone have `ownerOf == null` and aren't counted.
     - An unknown key is ignored.
7. **Invariant:** an id is in at most one of `live` and `away` across all rules, and an item's `spawnedBy` is non-null
   exactly when its id is in its rule's `live`. Add both to `invariantViolations()` (only `live`; away ids aren't
   entities).

### Test & Documentation Requirements

- `ItemSpawnRulesTest.kt`:
  - **`gone`:** a copy picked up still counts; destroying it releases it and arms the timer. Decay and empty count as
    gone, and so does merging the copy into another stack. Merging another stack *into* the copy keeps it.
  - **Transform:** `transform` keeps counting.
  - **`taken`:** a copy carried by a mob in the room counts. A player walking out with it releases it at the next world
    tick. Dropping it back before that tick keeps it.
  - **Stacks:** `stack = Roll(3, 5)` with a forced roll gives a stack of that size. Taking 1 leaves the copy counting;
    taking all of it (`taken`) or destroying all of it (`gone`) releases.
  - **Containers:**
    - Placed into the lowest-id chest, closed or locked.
    - With no chest, the copy is held and placed at the first world tick after a chest appears.
    - A full chest holds the copy.
    - A held container copy in a room that goes dormant isn't retried until the room wakes.
  - **Logout:** a copy in a player's inventory at logout moves to away and still counts. The rule doesn't refill.
  - **Mob death:** a mob carrying a copy dies (non-stowed removal): released.
- `SpawnStatePersistenceTest.kt`:
  - A player logs out with a `gone` copy; restart. The rule counts it as away and doesn't refill. At login it's bound
    again.
  - Same, with `--fresh`: the rule fills, and at login the item comes back unbound.
  - A row held by a character whose row is gone isn't counted.
- `LoaderTest`: each load error in step 1.

### Exit Conditions

- [] `./gradlew :core:test :persist:test :script:test` pass.
- [] `./gradlew check` passes, after the golden regeneration (`until`, `stack`, `container`).

---

## Phase 4: `spawner()` pools

### Objective & Scope

- **In Scope:**
  - The top-level `spawner(id, label, rooms, pick = None, ...)` declaration and `SpawnerProto`.
  - Pool picking: list, dict and `pick` fn, with per-copy homes.
  - Pool rooms in other packages, plus missing and quarantined rooms.
  - Spawner ids in the shared id namespace.
  - Reset ordering with spawners.
- **Out of Scope:** *Do not implement these; later phases do.*
  - Reload by key (Phase 5).
  - `ctx.spawner` in the `pick` ctx, and the handle (Phase 6 adds `ctx.spawner` to `pick`'s ctx too).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Declarations.kt`: `exit` (~131), `parseExitCandidates` (~187) and
    `parseExitPick` (~181), the patterns to mirror.
  - `script/src/main/kotlin/gups/script/Host.kt`: `pickExit` (~445), the `pick` call pattern to mirror.
  - `script/src/main/kotlin/gups/script/Packages.kt` (~180, `roomList` etc.).
  - `core/src/main/kotlin/gups/core/world/Movement.kt` (`destinationFor`, the weighted roll).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`SpawnerProto`, `Prototypes.spawners`)
  - `core/src/main/kotlin/gups/core/world/Actions.kt` (`PoolPicker` seam, next to `ExitPicker`)
  - `core/src/main/kotlin/gups/core/world/Spawners.kt` (`SpawnState.pool`, picking, diagnostics)
  - `core/src/main/kotlin/gups/core/world/World.kt` (`init`: build spawner states after room rules)
  - `script/src/main/kotlin/gups/script/Declarations.kt`, `DeclarationSpecs.kt` (`SPAWNER_SPEC`), `Packages.kt`
    (`spawnerList`), `Loader.kt` (pass `spawners` into `Prototypes`; resolve pool labels), `Host.kt` (`pickPoolRoom`),
    `ApiModel.kt` (only if declarations are listed there explicitly)
  - Tests: `core/src/test/kotlin/gups/core/world/SpawnerPoolTest.kt` (new),
    `script/src/test/kotlin/gups/script/SpawnerDeclTest.kt` (new)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `persist/` (keys already cover `Named`), `Views.kt`, `plugin-api/`.

### Specification & Decision Record Changes

- §4.2 example block, after the `region(...)` line, add:

  ```python
  spawner("rare_sword", ":rare_sword", rooms={":vault": 1, "//zones/forest:hollow": 3},  # a pool of rooms
          respawn=(14400, 43200))  # 1-3 hours after the last copy left
  ```
- §4.2 **Spawn rules** bullet: append:

  > - **Pools:** `spawner(id, label, rooms, pick = None, ...)`, a top-level declaration of a `zone` package, takes the
  >   same arguments as `spawn` and keeps its copies across a pool of rooms. It is the rule `//pkg:id`, in the same
  >   namespace as `spawn(id = ...)`.
  >   - `rooms` is a list of room labels (equally likely) or a dict from label to a positive int weight. Rooms may
  >     belong to any package, and every label must resolve.
  >   - **Picking:** each copy of a batch picks its room on its own, and that room is its home, for `until = "taken"`
  >     and for a mob's home room. A room that already holds a copy isn't avoided.
  >   - **`pick = fn`:** names a top-level function called once per copy, with `ctx.candidates` the usable rooms' labels
  >     in declared order, and `ctx.self` and `ctx.room` the first of them. It returns one of them, as a label or a
  >     room, or `None` to roll by weight. An error in it, or an answer that isn't a candidate, is a script error, and
  >     the engine rolls instead.
  >   - **Unusable rooms:** a room that doesn't exist or whose package is quarantined isn't picked, and is reported
  >     once. A pool with no usable room places nothing.
  >   - **Resets:** a `"reset"` pool refills at its own package's resets, after the package's room rules, in declaration
  >     order. `at_index` is clamped to each room's track.

- §5.4 **Respawns**, the reset sub-item: replace "room rules by room id, in declaration order within a room." with "room
  rules by room id, in declaration order within a room, then the package's `spawner`s in declaration order."

- §4.2 **Features** bullet: no change (`at_index` already listed).

- CHANGELOG: append "- `spawner(id, label, rooms, ...)` keeps a rule's copies across a pool of rooms, picked by weight
  or by a `pick` function."

### Implementation Blueprint

#### Signatures & Types

```kotlin
// Protos.kt
data class SpawnerProto(
  /** The rule's label, `//pkg:id`. */
  val id: Label,
  /** The shared settings; rule.id == id. */
  val rule: SpawnRule,
  /** Pool rooms with weights, in declared order. */
  val rooms: Map<Label, Int>,
  val pick: PoolPicker? = null,
)
// Prototypes(..., spawners: List<SpawnerProto> = emptyList()); val spawners: List<SpawnerProto> (declaration order, package order)

// Actions.kt
/** A spawner's `pick` function: a candidate's label, or null to roll by weight. */
fun interface PoolPicker {
  fun pick(world: World, rule: SpawnKey, candidates: List<Label>): Label?
}

// Spawners.kt
class SpawnState internal constructor(
  val key: SpawnKey,
  /** The package that declared the rule: its resets drive "reset" mode and its zone_states row saves it. */
  val pkg: String,
  val rule: SpawnRule,
  val pool: Map<Label, Int>,            // a room rule: mapOf(room to 1)
  val pick: PoolPicker?,
  /** The declaring room of a room rule; null for a spawner. */
  val room: Room?,
)
internal fun World.pickRoom(s: SpawnState): Room?   // null: no usable room
```

Replace every `s.room` use from earlier phases with the picked room. A mob copy's home is its spawn room:
`spawnMob(label, picked, ...)` already sets `homeRoom` to `picked`.

#### Algorithmic Steps & Invariants

1. **Declaration** (`Declarations.kt`, `SPAWNER_SPEC`):
   - **Arguments:** `id` is positional and required, `label` is required (positional second or by keyword), and `rooms`
     is required. `pick`, and the same optional params as `spawn` (minus none), are by keyword.
   - **Package kind:** only in `zone` packages: `"spawner(): only zone packages declare spawners"`.
   - **Rooms:** parse `rooms` like `parseExitCandidates`, with these differences:
     - At least **1** label (a pool of one is allowed).
     - Weights are positive ints.
     - Duplicate labels fail: `"spawner(rooms): //pkg:x is listed twice"`.
   - **Labels:** add every room label to `ctx.pkg.refs`, so unresolved labels fail after every package loads.
   - **`pick`:** parse it like `parseExitPick` into a `ScriptPoolPicker(ref, pkg)`.
   - **Shared checks:** the shared parser from Phases 1–3 applies the `spawn` param checks.
   - **Ids:** the id joins the package's id set from Phase 2.
2. **`World.init`:**
   - After the room rules, add a `SpawnState` per `protos.spawners` entry, with key `Named(id)`, `pkg = id.pkg`, `room =
     null` and `pool = rooms`.
   - Keep unknown pool rooms (a missing package at reload): `pickRoom` skips them.
   - Validate `label` and `container` as in Phase 3.
3. **`pickRoom(s)`:**
   - **Usable rooms:** pool labels, in order, that exist and whose package isn't in `scripts.quarantinedPackages`.
     - **Reaching the scripts:** core sees `World.scripts`. Use whatever accessor `SqlitePersistence.zoneStates` uses.
   - **Reporting:** for each unusable label not yet reported for this rule, `diagnose("engine", "spawner ${key}: room
     $label is missing or quarantined", null)` once. Keep a `reported: MutableSet<Label>` on `SpawnState`.
   - **No usable room:** return null. The caller adds no copy for this pick, so the rule stays short.
   - **`pick` fn:** if `s.pick != null`, call it with the usable labels. Accept the answer only if it's among them.
   - **Roll:** otherwise roll by weight over the usable rooms with `rng.int(1, total)`, mirroring `destinationFor`.
   - A room rule's pool is its one room, so it never calls `pick`.
4. **Each copy picks on its own:** in `addCopies`, call `pickRoom` per copy.
5. **Resets:** `resetZone(pkg)` iterates `spawnStates` filtered by `s.pkg == pkg` and `Respawn.Reset`, in list order,
   which is room rules (room id order) then spawners (declaration order). Dormant pool rooms in other packages hold.
6. **`Host.pickPoolRoom`:** mirror `pickExit`. The site is `Site(pkg, id, "pick", vetoable = false, ...)`, using the
   first usable pool room as the owner (`Ctx` needs a non-null owner entity). The ctx's `self` and `room` are that room,
   and it has `candidates`, `rng` and `pulse`. Say so in the KDoc.
   - **Answers:** a return that isn't a candidate reports `"pick returned $value, which is not one of the spawner's
     rooms"`; `None` rolls.

### Test & Documentation Requirements

- `SpawnerPoolTest.kt`:
  - **Picking:** weights with a forced roll pick the expected room. A copy's mob `homeRoom` is the picked room. Each
    copy of a batch of 3 picks on its own.
  - **Quarantine:** a quarantined package's room is skipped, with exactly one diagnostic across several picks.
  - **No usable room:** an empty usable set adds nothing, and a timed rule re-arms.
  - **Dormant rooms:** a dormant pool room in another package holds the copy, and it is placed when that room wakes.
  - **Resets:** a `"reset"` spawner refills after the package's room rules, in declaration order.
- `SpawnerDeclTest.kt`:
  - Load errors: outside a zone package, an empty `rooms`, a duplicate room, an unresolved label, an id that clashes
    with a `spawn(id)`.
  - `pick`: returns a candidate, returns `None`, returns a bogus label (script error, then a roll).

### Exit Conditions

- [] `./gradlew :core:test :script:test` pass.
- [] `./gradlew check` passes, after the golden regeneration (the `spawner` declaration).

---

## Phase 5: Reload carry-over by identity

### Objective & Scope

- **In Scope:** `Reconcile.rebuildSpawns` matches rules by `SpawnKey` and carries live, away and held copies, the
  deadline and `ran`, whatever settings changed. It releases the copies of removed rules and gives new rules their first
  fill.
- **Out of Scope:** *Do not implement these; later phases do.* Everything in Phase 6 and later.

### Context Working Set

- **Read / Reference Only:** `core/src/main/kotlin/gups/core/world/Spawners.kt`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Reconcile.kt` (`refillCreatedSpawns` ~173, `rebuildSpawns` ~352,
    `findCarryOver`, `carryOverMobs`, `updateRespawn`, `releaseOrphans`)
  - `core/src/main/kotlin/gups/core/world/Spawners.kt` (a `buildSpawnStates(protos)` used by both `init` and reload)
  - Tests: `script/src/test/kotlin/gups/script/ReloadTest.kt`
  - `docs/SPEC.md`
- **Do Not Touch:** `persist/`, `script/src/main/`.

### Specification & Decision Record Changes

- §3.4 **Live reconciliation**: replace "Spawn rules carry their live mobs over by label. New rooms fill their spawns,
  then get `on_load`." with:

  > - Spawn rules carry their state over by label (or, without one, by room, prototype and position; §4.2): tracked,
  >   held and away copies, the pending deadline and whether the rule has run, whatever settings changed. Lowering `max`
  >   removes nothing; the rule just doesn't refill until it is below the new cap. A changed timer applies from the next
  >   deadline. A copy whose room left the pool stays and keeps counting. A held copy whose room no longer exists is
  >   dropped.
  > - A rule the reload removed lets go of its copies, which stay in the world uncounted, and drops its held copies. A
  >   new rule does its first fill. New rooms then get `on_load`.

### Implementation Blueprint

#### Algorithmic Steps & Invariants

1. `buildSpawnStates(protos)` (moved out of `World.init`) builds the fresh list in the canonical order. `init` and
   `rebuildSpawns` both use it.
2. **`rebuildSpawns`:**
   - Build `old.associateBy { it.key }`.
   - For each new state `s` whose key has a previous state `p`:
     - Move `p.live`, `p.away`, `p.held`, `p.respawnAt` and `p.ran` into `s`.
     - Re-point `spawnedBy` on each live mob and item to `s`.
     - Drop held copies whose `room` isn't in `roomsByLabel`.
     - If `s` is short and timed with no deadline, `armTimer(s)`. Don't roll a new deadline when one is pending.
   - New keys: collect them.
   - Removed keys: for each live entity, clear `spawnedBy` (and `spawnHome`), and drop `away` and `held`.
3. Then call `firstFill` on every new state (new rooms' rules included). Then `on_load` on created rooms, as today.
4. **Mob `homeRoom`:** a mob whose home room left the pool keeps its `homeRoom`. Nothing moves.

### Test & Documentation Requirements

- `ReloadTest` (script-level reload):
  - Lowering `max` from 3 to 1 with 3 alive keeps 3 and arms no refill. After 2 die there's still no refill; the third's
    death arms the timer.
  - Changing `respawn` from 100 to 5000 while a deadline is pending keeps the old deadline, and the next one uses 5000.
  - Removing a rule: its mobs stay, `spawnedBy` is cleared, and the reload makes no refill.
  - Adding `id = "x"` to an unnamed rule makes a new rule: the old copies are released and the new one fills.
  - Reordering two rules with different labels in a room carries both.
  - A held copy carries over; one whose room was deleted is dropped and the timer arms.

### Exit Conditions

- [] `./gradlew :core:test :script:test` pass.
- [] `./gradlew check` passes.

---

## Phase 6: `on_place`, `spawned_by`, the handle, `spawner_info`, `refill`, the facade and `w.reset`

### Objective & Scope

- **In Scope:**
  - The `on_place` param and hook call.
  - The Starlark `spawner` handle.
  - `ctx.spawner`, plus `ctx.spawner` in a pool's `pick` ctx.
  - `spawned_by` on item and actor views.
  - `ctx.spawner_info` and `ctx.refill`.
  - The plugin facade counterparts.
  - `w.reset(package = None)`.
- **Out of Scope:** *Do not implement these; later phases do.* `@spawners` (Phase 7) and content or tutorial edits
  (Phase 8).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Host.kt` (`pickExit`).
  - `script/src/main/kotlin/gups/script/CtxFields.kt` (`candidates` ~248, the pattern for a field from `CtxExtras`).
  - `script/src/main/kotlin/gups/script/CtxQueries.kt` (`item_info`, the `*_info` verb pattern and struct building).
  - `script/src/main/kotlin/gups/script/Views.kt` (`ActorView` `owner` ~163, `ItemView` ~746).
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt` (`PluginWorld` ~118, `PluginActor` ~749, `PluginItem` ~907).
  - `harness/src/main/kotlin/gups/harness/Handles.kt` (`run` ~427, `ctx` ~530).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Actions.kt` (`PlaceHook` seam)
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`SpawnRule.onPlace`)
  - `core/src/main/kotlin/gups/core/world/Spawners.kt` (call `on_place`; public `spawnerInfo`, `refillRule`,
    `spawnState(key)`, `resetZoneNow(pkg)`)
  - `script/src/main/kotlin/gups/script/SpawnerHandle.kt` (**new**)
  - `script/src/main/kotlin/gups/script/Ctx.kt` (`CtxExtras.spawner`), `CtxFields.kt` (`spawner`), `CtxSimulation.kt`
    (`spawner_info`, `refill`), `Views.kt` (`spawned_by` ×2), `Host.kt` (`placed`), `Declarations.kt` and
    `DeclarationSpecs.kt` (`on_place` on both declarations), `HookCatalog.kt` (only if hook-like params are listed
    there; otherwise none)
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt`, `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`
  - `harness/src/main/kotlin/gups/harness/Handles.kt`
  - Tests: `script/src/test/kotlin/gups/script/SpawnerScriptTest.kt` (new),
    `plugin-api/src/test/kotlin/gups/pluginapi/FacadeSpawnersTest.kt` (new),
    `harness/src/test/kotlin/gups/harness/ScenarioTest.kt` or a new `HarnessResetTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `persist/`, `BuilderTools.kt`, `template-world/`.

### Specification & Decision Record Changes

- §4.2 **Spawn rules** bullet: in the `spawn(...)` signature from Phase 3, insert `, on_place = None` before the closing
  `)`. Append:

  > - **`on_place = fn`** (on `spawn` and `spawner`) names a top-level function called once per copy, after the copy is
  >   placed and its own `on_spawn` hooks have run. `ctx.self` and `ctx.item` (or `ctx.actor`) are the copy, `ctx.room`
  >   its room and `ctx.spawner` the rule. A held copy runs it when it is finally placed.

- §4.4 table: add a row after `on_spawn`:

  > | `on_place` | — | Spawn rule (`spawn`, `spawner`) | `self` and `item` or `actor` (the copy), `room`, `spawner` |

- §4.5 **Context fields**: after "`candidates` (an exit's `pick` function: the labels it may choose)," insert "`spawner`
  (the rule's handle, in `on_place` and a spawner's `pick`),". Change the `candidates` description to "(an exit's or a
  spawner's `pick` function: the labels it may choose)".
- §4.5 **Actor view**, **Ownership** bullet: append "`spawned_by`, the handle of the spawn rule that placed the mob, or
  `None`."
- §4.5 **Item view**: after "`in_world`" insert ", `spawned_by` (the handle of the spawn rule that placed the item, or
  `None`)".
- §4.5 verb table: add a row after `Mobs`: group `Spawn rules`, verbs "`spawner_info(rule)`, `refill(rule,
  count=None)`". Leave the `Mobs` row as it is.
- §4.5: after the **`despawn`** bullet, add:

  > - **Spawn rules:** `rule` is a rule's label (`":vault_key"` or `"//pkg:vault_key"`) or its handle.
  >   - **The handle** is a value of type `spawner`. It has the fields:
  >
  >     - `id`: the rule's label, or `None` for a room rule without one;
  >     - `label`: the prototype it places;
  >     - `room`: the declaring room, or `None` for a `spawner`;
  >     - `rooms`: the pool's labels, in declared order.
  >
  >     It prints as `spawner("//pkg:id")`, or `spawner(room="//pkg:room", label="//pkg:proto", n=0)` for a room rule
  >     without a label. Two handles are equal when they name the same rule, and a handle names the same rule across
  >     reloads.
  >
  >   - **`ctx.spawner_info(rule)`** returns `struct(live, held, max, next_at, ran)`:
  >
  >     - `live`: copies it tracks, those in a logged-out player's custody included;
  >     - `held`: copies waiting for a dormant room or a container;
  >     - `next_at`: the pulse of its next refill of its own: a timed rule's deadline, or for a `"reset"` rule that is
  >       below `max` the package's next reset; otherwise `None`;
  >     - `ran`: whether it has done its first fill.
  >   - **`ctx.refill(rule, count = None)`** adds one batch now, or `count` copies, capped by `max`, through the usual
  >     picking, holding and `on_place`, whatever the rule's `respawn`. A pending timer is left alone. It returns
  >     `None`.
  >   - **Errors:** a label or handle that names no rule is an error for both verbs. Kernel.

- §4.12 the `w` row: insert "`reset(package=None)`," after "`run(pulses)`,". Add a bullet after the `w.ctx` bullet:

  > - **`w.reset(package = None)`** runs a zone reset of `package` (by default the test's own) now, exactly as a due one
  >   runs (§5.4), and rolls the next from this pulse. A package without `reset_world_ticks` is an error.

- §4.15 facade verbs: add `spawnerInfo` and `refill` to the verb list after `despawn`. Add a sentence: "`spawnerInfo`
  and `refill` take a rule's full label or a `PluginSpawner`, and follow `ctx.spawner_info` and `ctx.refill`. A
  `PluginSpawner` has the handle's `id`, `label`, `room` and `rooms`, plus `n`, the position a room rule without a label
  has among the room's rules for the same prototype." Views list: add `PluginSpawner`, `PluginSpawnerInfo`.

- CHANGELOG: append "- `on_place` on spawn rules; `spawned_by` on item and actor views; `ctx.spawner_info`,
  `ctx.refill`, and their facade counterparts `spawnerInfo` and `refill`; `PluginItem.spawnedBy` and
  `PluginActor.spawnedBy`. Tests can run a zone reset with `w.reset()`."

### Implementation Blueprint

#### Signatures & Types

```kotlin
// Actions.kt
/** A spawn rule's `on_place` function, called once a copy is placed. */
fun interface PlaceHook {
  fun placed(world: World, copy: Entity, room: Room, rule: SpawnKey)
}

// Spawners.kt (public)
data class SpawnerInfo(val live: Int, val held: Int, val max: Int, val nextAt: Long?, val ran: Boolean)
fun World.spawnState(key: SpawnKey): SpawnState?
fun World.spawnerInfo(key: SpawnKey): SpawnerInfo          // IllegalArgumentException("no spawn rule $key")
fun World.refillRule(key: SpawnKey, count: Int? = null)
fun World.resetZoneNow(pkg: String)                         // IllegalArgumentException("$pkg has no zone reset")
fun World.spawnKeyOf(e: Entity): SpawnKey?                   // merge the mob and item overloads
fun World.poolOf(key: SpawnKey): List<Label>
fun World.declaringRoomOf(key: SpawnKey): Label?
```

```kotlin
// SpawnerHandle.kt
@StarlarkBuiltin(name = "spawner", doc = "A spawn rule.")
class SpawnerHandle internal constructor(val key: SpawnKey, private val label: Label, private val room: Label?, private val rooms: List<Label>) : StarlarkValue {
  @StarlarkMethod(name = "id", structField = true, allowReturnNones = true) fun id(): Any
  @StarlarkMethod(name = "label", structField = true) fun label(): String
  @StarlarkMethod(name = "room", structField = true, allowReturnNones = true) fun room(): Any
  @StarlarkMethod(name = "rooms", structField = true) fun rooms(): StarlarkList<String>
  override fun isImmutable() = true
  override fun repr(printer: Printer)   // the forms in the spec text
  override fun equals(other: Any?) = other is SpawnerHandle && other.key == key
  override fun hashCode() = key.hashCode()
}
```

```kotlin
// plugin-api Facade.kt
data class PluginSpawner(
  val id: String?,
  val label: String,
  val room: String?,
  val rooms: List<String>,
  /** Position among the room's rules with the same label; ignored when [id] is set. */
  val n: Int = 0,
)
data class PluginSpawnerInfo(val live: Int, val held: Int, val max: Int, val nextAt: Long?, val ran: Boolean)
// PluginWorld
fun spawnerInfo(rule: String): PluginSpawnerInfo
fun spawnerInfo(rule: PluginSpawner): PluginSpawnerInfo
fun refill(rule: String, count: Int? = null)
fun refill(rule: PluginSpawner, count: Int? = null)
// PluginActor, PluginItem
val spawnedBy: PluginSpawner?
```

The host resolves a `PluginSpawner` to `SpawnKey.Named(id)` when `id` is set, else to `SpawnKey.InRoom(room, label, n)`.

#### Algorithmic Steps & Invariants

1. **`on_place`:**
   - **Parsing:** on both declarations, parse `on_place` with `hookRef` into `ScriptPlaceHook(ref, pkg)`, stored as
     `SpawnRule.onPlace: PlaceHook?`. The load-time check is `hookRef`'s, the same check every hook reference gets.
     There's no dry-run call: the rules dry run is for rules exports only.
   - **Call order:** in `Spawners.kt`, right after a copy is placed and its `on_spawn` has returned (both for fresh
     copies and for held ones), call `rule.onPlace?.placed(this, copy, room, s.key)`, guarded.
   - **Script side:** `Host.placed` mirrors `pickExit`, with `Site(pkg, <rule label or room label>, "on_place", vetoable
     = false, copy.id)`.
     - The ctx owner is the copy.
     - The `HookContext` has `room = room`, plus `item = copy` for an item or `actor = copy` for a mob.
     - `CtxExtras.spawner = handleFor(key)`.
2. **Context field:** `CtxExtras.spawner: SpawnerHandle? = null`. `CtxFields.spawner` returns it or `None`. Pass it in
   `pickPoolRoom` too.
3. **Views:** `ItemView.spawned_by` and `ActorView.spawned_by` return `world.spawnKeyOf(entity)?.let(::handleFor) ?:
   None`. A player gives `None`.
4. **`spawner_info(rule)` and `refill(rule, count = None)`** (`CtxSimulation.kt`):
   - **Resolving `rule`:** a string goes through `label(value, pkg)` to `SpawnKey.Named`, and a `SpawnerHandle` uses its
     `key`. Anything else is an error. An unknown key fails with `"spawner_info: no spawn rule <printed>"` or `"refill:
     no spawn rule <printed>"`.
   - **`spawner_info`:** `spawnerInfo` first runs `checkTakenCopies()` for that rule, so `live` is current. It returns a
     Starlark `struct` built the way `item_info` builds its result.
   - **`next_at`:**
     - Timed: `respawnAt`.
     - Reset: `zoneResets[pkg]` while short, else `None`.
     - Never: `None`.
   - **`refill`:** `count` must be `>= 1` when given. `refillRule` calls `addCopies(s, count ?: batchOf(s))`, then
     `armTimer(s)`. It never clears or re-rolls a pending deadline, and works in every mode.
5. **`w.reset(package = None)`** (`Handles.kt`): resolve the package (default: the calling test file's package), then
   `world.resetZoneNow(pkg)`. That's `resetZone(pkg)` itself, so the next reset is rolled from the current pulse.
6. **Facade host:**
   - `spawnerInfo`/`refill` resolve a label with `Label(rule)`, which must be a full label, or a `PluginSpawner` through
     its fields. Both call the same core functions.
   - `spawnedBy` maps `spawnKeyOf` to `PluginSpawner`.
   - Parity: `ctx.spawner_info` ↔ `spawnerInfo`, `ctx.refill` ↔ `refill`, `item.spawned_by`/`actor.spawned_by` ↔
     `spawnedBy`. The parity test matches names camel-cased, so no exception entries are needed. Confirm it passes.

### Test & Documentation Requirements

- `SpawnerScriptTest.kt`:
  - **`on_place` order:** it runs after the copy's `on_spawn`, with `ctx.self`, `ctx.item`/`ctx.actor`, `ctx.room` and
    `ctx.spawner`. A held copy runs it on wake.
  - **Handles:**
    - `spawned_by == ctx.spawner`.
    - The handle prints in both forms.
    - Handles are equal across a reload.
    - A handle is usable as a dict key.
  - **`spawner_info`:** all five fields across `never`, `reset` and timed rules. `held` counts a dormant pick, and
    `live` counts an away copy.
  - **`refill`:**
    - It brings back a destroyed `"never"` key and leaves `ran` true.
    - `refill(r, 5)` on `max = 2` adds up to the cap.
    - A pending deadline stays put.
    - An unknown rule errors with the message above.
- `FacadeSpawnersTest.kt`:
  - `spawnerInfo` and `refill` by label and by `PluginSpawner`.
  - `spawnedBy` on a placed item and mob, `null` on others.
- **Harness:**
  - `w.reset()` refills a `"reset"` rule and fires `on_reset`.
  - With a pool room dormant, its copy is held. Walking a player in places it and then runs the missed `on_reset`.
  - `w.reset("//zones/none")` errors.

### Exit Conditions

- [] `./gradlew :script:test :plugin-api:test :harness:test` pass.
- [] `./gradlew check` passes, including `CtxParityTest`, after the golden regeneration.

---

## Phase 7: `@spawners`

### Objective & Scope

- **In Scope:** the builder command, the `spawners` capability, help text, spec rows.
- **Out of Scope:** *Do not implement these; later phases do.* Everything in Phase 8.

### Context Working Set

- **Read / Reference Only:** `core/src/main/kotlin/gups/core/world/BuilderTools.kt` (`where` ~158, `NOT_YOURS`,
  `labelArg`), `core/src/main/kotlin/gups/core/Accounts.kt` (`Capabilities` ~45).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/BuilderTools.kt`, `core/src/main/kotlin/gups/core/Accounts.kt` (`SPAWNERS`)
  - Tests: `core/src/test/kotlin/gups/core/world/ToolsTest.kt` (or a new `SpawnersCommandTest.kt`), `HelpCommandTest.kt`
  - `docs/SPEC.md`
- **Do Not Touch:** everything else.

### Specification & Decision Record Changes

- §4.9 builder table: add a row after `@where`:

  > | `@spawners [label]` | Show the spawn rules of your room, or one rule and its copies. |

- §4.9, after the **`@spawn`** bullet and its sub-bullets, add:

  > - **`@spawners`:** lists every rule declared in the builder's room or with the room in its pool, one line each: the
  >   rule's handle, `live/max`, held copies, its `respawn`, and the pulses until `next_at` (§4.5). With a label, it
  >   shows that rule and then each copy in the world (id, name, room) and each copy away with a logged-out player. Only
  >   rules of the builder's packages are shown.

- §4.9 **Capabilities**: insert `spawners` after `spawn`.

### Implementation Blueprint

#### Algorithmic Steps & Invariants

1. Register `staffCommand("@spawners", Role.BUILDER, "Show spawn rules here, or one rule's copies.\nUsage: @spawners
   [label]", ::spawners)`. Check `refuseRestricted(p, "spawners")` first.
2. **Without args:**
   - `if (!p.owns(room.label.pkg)) return w.send(p, NOT_YOURS)`.
   - The rules are those whose declaring room is this room or whose pool contains `room.label`, filtered to
     `p.owns(s.pkg)`, in `spawnStates` order.
   - With none, send `"No spawn rules here."`.
   - Otherwise one line per rule: `"<repr> <live>/<max>, <held> held, <mode>, <next>"`.
     - `<repr>` is the handle's printed form.
     - `<mode>` is `never`, `reset`, `every <n> pulses`, or `every <a>-<b> pulses`.
     - `<next>` is `next in <k> pulses` (`next_at - pulse`, at least 0), or `nothing pending`, or for `never` `ran`/`not
       run`.
3. **With a label:**
   - **Resolving:** resolve with `labelArg(arg, p)` to `SpawnKey.Named`. An unknown rule sends `"No spawn rule
     <label>."`. One not owned sends `NOT_YOURS`.
   - **Output:** the rule's line, then `" #<id> <name> in <room label>"` per live copy (ascending id), `" #<id> away"`
     per away id, and `" held for <room label>"` per held copy.
4. Test every message, plus the ownership refusal.

### Test & Documentation Requirements

- **The command:** both forms, a pool rule listed in a pool room, ownership, the restriction refusal (`@restrict` with
  `spawners`).
- **Help:** `HelpCommandTest` expects `@spawners` among the special commands.

### Exit Conditions

- [] `./gradlew :core:test` passes.
- [] `./gradlew check` passes (golden regeneration if the help or command lists are in the golden files).

---

## Phase 8: Remove the old kwargs, update every caller, template-world demo, and finalization

### Objective & Scope

- **In Scope:**
  - Remove `count` and `respawn_pulses` from `spawn()`.
  - Rewrite every in-repo caller, tutorial and doc example.
  - Convert the tutorial's restocking hooks to rules, and add a pool example with a permanent test.
  - World design guide paragraph, golden files and the CHANGELOG.
  - Mark the `DESIGN_NEEDED` entry done.
- **Out of Scope:** *Do not implement these.* Any new engine behavior. A world-wide limit on copies of a prototype (the
  next `DESIGN_NEEDED` entry).

### Context Working Set

- **Read / Reference Only:** `template-world/zones/tutorial/rooms.star` (`_stock_hall` ~39, `_stock_tar_pit` ~65,
  `_stock_armory` ~144), `template-world/zones/tutorial/tutorial_test.star` (`test_a_reset_restocks_the_armory` ~65).
- **Files to Modify / Create** (the full list of known callers. The grep in the exit conditions is the authority):
  - `script/src/main/kotlin/gups/script/Declarations.kt`, `DeclarationSpecs.kt`, `DocExamples.kt`
  - `template-world/zones/tutorial/rooms.star`, `template-world/zones/tutorial/tutorial_test.star`, new
    `template-world/zones/tutorial/spawn_rules_test.star`
  - `template-world/docs/tutorials/03_items_and_equipment.md` (restocking on reset), `05_mobs_and_behaviors.md` (section
    6), and `06_hooks_and_interactivity.md` / `07_testing_your_zone.md` where they show `spawns`, `on_reset` restocking
    or respawn timing
  - `samples/robot-world/zones/foundry/rooms.star`,
    `samples/heritage-plugin/world/zones/shrine/{shrine,shrine_testdata}.star`
  - `script/src/test/kotlin/gups/script/LoaderTest.kt` (drop the alias tests; add the removal errors),
    `plugin-api/src/test/kotlin/gups/pluginapi/FacadeMobsTest.kt` and any Kotlin test whose embedded Starlark uses
    `count=` or `respawn_pulses=` in `spawn(`
  - `docs/WORLD_DESIGN_GUIDE.md`, `docs/SPEC.md` (verify that no `count=`/`respawn_pulses=` spawn example is left),
    `docs/CHANGELOG.md`, `docs/DESIGN_NEEDED.md`
  - `cli/src/test/resources/api.golden.json`, `cli/src/test/resources/docs-golden/`, `docs/api/api-1.json` (regenerated)
- **Do Not Touch:** `core/`, `persist/`, `plugin-api/src/main/`, older `docs/MILESTONE_*.md` files.

### Specification & Decision Record Changes

- None beyond verifying that no spec example still uses `count=` or `respawn_pulses=` with `spawn(`.
- CHANGELOG: under `### Spawn rules`, replace the first bullet's wording "(was `count`)" and "(was `respawn_pulses`)" so
  the bullet ends: "`count` and `respawn_pulses` are gone."

### Implementation Blueprint

#### Algorithmic Steps & Invariants

1. **Removing the old kwargs:** delete the two params and their mapping. Unknown-argument errors now give a spelling
   suggestion, as for any unknown argument. Add a `LoaderTest` case asserting `spawn(":x", count = 2)` fails as an
   unknown argument.
2. **Rewriting callers:** `count = n` → `max = n`, `respawn_pulses = n` → `respawn = n`. Check each rule that had no
   `respawn_pulses`: it was refilled at every reset before. If its test or tutorial text relies on a reset refilling it,
   use `respawn = "reset"`, or keep the default timer and fix the test to wait for the timer. State which in the commit
   message.
3. **Template-world conversions** (`rooms.star`):
   - **Hall:** replace `_stock_hall` (`on_load`) with `spawns = [spawn(":satchel", respawn = "never"),
     spawn(":brass_key", respawn = "never", container = ":satchel")]`. The satchel rule comes first, so it is placed
     first in the first fill.
   - **Tar pit:** replace `_stock_tar_pit` (`on_load` and `on_reset`) with `spawn(":pinecone", respawn = "reset", until
     = "taken")`.
   - **Armory:** replace `_stock_armory` with two rules, `spawn(":practice_sword", respawn = "reset", until = "taken")`
     and `spawn(":volatile_flask", respawn = "reset", until = "taken")`.
   - **Existing hooks:** keep `on_reset`/`on_load` only where other logic remains (`_stock_arena`, `_tide`). Update the
     comments to explain the rule instead of the hook.
   - **Pool example:** at the end of `rooms.star`, add `spawner("stray_ring", ":copper_ring", rooms = {":gravel": 3,
     ":tar_pit": 1}, respawn = (2400, 4800))`, with a one-line comment: "One ring lies in one of these rooms; 10–20
     minutes after it leaves the world, another turns up."
4. **`spawn_rules_test.star`:**
   - The brass key is placed once, inside the satchel, and isn't placed again after `get` plus `w.reset()`.
   - The armory sword is back after being carried out and `w.reset()`.
   - The stray ring: `w.force_rng` picks `:tar_pit`. Destroying the ring and running `w.run(pulses = 4800 + 120)` brings
     one back, and `spawner_info(":stray_ring").live == 1`.
   - Update `test_a_reset_restocks_the_armory` to use `w.reset()`.
5. **`WORLD_DESIGN_GUIDE.md`:** next to the `spawns = [...]` table row (~541), add a short paragraph naming the respawn
   modes, `until`, `container`, `spawner` pools and `on_place`. Add one sentence: "To make a copy stop counting when it
   changes, destroy it and spawn the new item; `transform` keeps it counting."
6. **Tutorials:**
   - `05_mobs_and_behaviors.md` section 6: explain `max`, `batch`, timed versus `"reset"` versus `"never"`, and that
     resets don't refill timed rules.
   - `03_items_and_equipment.md`: replace the `on_reset` restocking example with the armory rule.
   - Run every code snippet that's a complete file through `gups lint` mentally against the new signatures.

### Test & Documentation Requirements

- `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups test --world template-world` passes.
- The same for `samples/robot-world` and the heritage sample world, as their Gradle tests run them.
- **End-to-end check:** in `local-world-test/scenario.star`, a player takes the brass key from the satchel and walks
  out. `w.reset()`, then the player looks in the satchel: it is empty. Run it with: `cli/build/install/gups/bin/gups
  scenario --world template-world --package //zones/tutorial local-world-test/scenario.star`. Delete `local-world-test/`
  afterwards.

### Exit Conditions

- [] `grep -rnE "spawn\([^)]*\b(count|respawn_pulses) *=" --include='*.star' --include='*.md' --include='*.kt' .`
  (excluding `local-world/`, `build/` and `docs/MILESTONE_*.md`) returns nothing.
- [] `grep -rn "respawn_pulses\|respawnPulses" --include='*.kt' --include='*.star' --include='*.json' .` (same
  exclusions) returns nothing.
- [] The golden files and `docs/api/api-1.json` are regenerated, and the diff shows only the removed params and changed
  docs.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

### Finalization & Backlog Update

- In `docs/DESIGN_NEEDED.md`, rename the heading `### Item placements and one spawn-rule model for mobs and items
  (designed, awaiting a milestone plan)` to `### Item placements and one spawn-rule model for mobs and items (Done in
  Milestone 58)`. Leave the entry's text in place; the designer's review deletes it.
- Run `./gradlew check` one last time, and CodeScene `pre_commit_code_health_safeguard` on the milestone's changes.
