# Milestone 35: Room Enchantments

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, heading `### Enchantments on rooms`.
- **Design Summary:** a script, plugin or player spell can consecrate, ward or curse one room for a while. A room
  carries timed `Affect`s, the same class actors and items use. They contribute modifiers to the modifier chain and add
  or remove tags, and they expire, persist and survive a zone reset like any other affect.
  - **Mechanism:** `Room.affects` is a list of `Affect`. A room affect may carry only `modifiers`, free-form `flags`,
    `add_tags`, `remove_tags`, `tick_every`, `on_tick`, `on_expire`, `expire_msg` and `power`. `stat_deltas`,
    `sense_deltas`, `max_vital_deltas`, `resist_deltas`, `procs` and `applies` on a room are errors.
  - **Effective tags:** a room's tags are its prototype's tags plus the `add_tags` of its live affects, minus their
    `remove_tags`. A tag that is both added and removed is removed. An affect is "live" at a pulse when `appliedAt` is
    not later than it and `expiresAt` is later than it, so an affect that ran out in a dormant room contributes nothing
    before its wake-time cleanup.
  - **Room clock:** the chain and the room's tags read the room's affects "as of" `World.roomClock`, which is the
    current pulse except while a mob's dormant regen catch-up is working out a past segment (below).
  - **Chain:** after the room prototype's modifiers, the room's live affects contribute their modifiers in affect-id
    order, before the actor's traits. The trace step is `room-affect:<name>`. `ctx.affect` is the affect, so a modifier
    can read `ctx.affect.power`.
  - **Replacement:** re-applying a name replaces the old affect only if the new one expires strictly later, as for
    actors and items.
  - **Duration:** a room affect needs a positive duration. There is no `None` (permanent) room affect. A new global
    constant `SEMI_PERMANENT` (`Long.MAX_VALUE`) is accepted as the `duration` of any timed affect (actor, item or room)
    for "effectively forever".
    - To make that value usable, the duration of `apply_affect` widens from `Int` to `Long`. All pulse arithmetic that
      involves it saturates instead of overflowing: `pulse + duration`, scaling by power, and conversion to seconds for
      display.
    - An affect that expires at `Long.MAX_VALUE` is reported to scripts as having no expiry (`expires_at` and
      `remaining` are `None`), as a permanent item affect already is.
  - **Zone reset:** a reset keeps a room's enchantments. A script that wants them gone removes them in `on_reset` with
    `ctx.remove_affect(room, a.name)` over `room.affects`.
  - **Dormancy:** expiry is by absolute pulse and keeps running while the room is dormant. `on_tick` doesn't fire in a
    dormant room and missed ticks aren't replayed. On wake, a room's expired affects are removed first (expiry order,
    then id, firing `on_expire` with `ctx.self` the room), then the mobs' and then the items' affects.
  - **Regen catch-up:** a `dormant_recovery = regen` mob's catch-up is piecewise over the room's affects as well as its
    own. The elapsed time is split at every point where a room affect was applied or expired, and each piece runs
    `rules.regen` and the modifier chain with exactly the room affects that were live then. The room's affects are still
    in place when the catch-up runs, because their wake-time cleanup comes after the mobs recover. A room affect's
    `no_regen` flag has no effect: only the mob's own flags suppress regen.
  - **Persistence:** the room's `RoomPayload` gains `affects`. No migration.
  - **Script API:** `ctx.apply_affect(room, ...)` (with new keywords `add_tags` and `remove_tags`, valid only for a room
    target) and `ctx.remove_affect(room, name)` accept a room. `room.affects` lists the live affects. The `host` of an
    affect view can be a room.
  - **Skills:** a new skill target kind `room`. The skill's effect acts on the caster's room and may use `apply_affect`,
    `dispel`, `sequence` and plugin primitives. A cast goes through the usual cost, `skill_check` and `skill_power`, and
    the room affect stores that `power` and gets its duration scaled by it.
  - **Plugin API:** `PluginEffectCall` gains `targetRoom: PluginRoom?`, set for a `room` skill (where `target` and
    `targetItem` are null), so a plugin primitive can act on a room (weather, light, exits and the like). The plugin API
    version doesn't change.
  - **Not in this milestone:** `add_tag` and `remove_tag` for rooms; `sense_deltas` on rooms; a permanent (`None`) room
    affect; a `ctx.dispel` verb; listing enchantments in `look`; a room affect's `no_regen` flag suppressing mobs'
    regen.
- **Specification Impact:** §3.3 (Room and Affect entities, room affect persistence), §4.2 (room `tags`), §4.3 (skill
  `target`, `apply_affect` duration and tags, `SEMI_PERMANENT`), §4.4 (affect hooks may have a room host), §4.6 (chain
  order), §4.15 (script API), §4.19 (plugin effect call), §5.9 (waking order and regen catch-up), §7.4 (M35 row).
- **Decision Record Updates:** None. `docs/DECISIONS.md` holds no record about rooms; do not add a new `D-xxx`.

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first and see them fail.
   - No spec section numbers or decision ids in comments, Kotlin or Starlark.
   - All Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`. The engine is
   unreleased, so no migrations or back-compat shims are needed.
3. Add CHANGELOG bullets to a new `### Room enchantments` subsection at the top of `## api_version 1 (unreleased)`,
   above whatever subsection is first there now. Create it in the first phase that adds a bullet, and have each later
   phase append to it.
4. In any phase that changes the script API (phases 2, 6 and 7; also phase 8 if a golden or docs test fails), regenerate
   the API files before the phase's final `./gradlew check` if any golden or API test fails:
   - Run `./gradlew :cli:test -Dgups.updateGolden=true`. This rewrites `cli/src/test/resources/api.golden.json` and the
     `cli/src/test/resources/docs-golden/` files.
   - Run `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout. This rewrites `docs/api/api-1.json`.
   - Read the diff. Only the symbols that phase names may be new or changed. `ApiDiffTest` must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing entry.
5. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation.
6. Never read or touch `local-world/`. Put scratch files in `local-world-test/` and delete it when you are done. Do not
   run `gups` commands directly against `template-world/`; copy it first (`cp -R template-world
   local-world-test/world`). The only exception is `gups scenario --world template-world`, which `AGENTS.md` allows.
7. Every phase must leave every existing test passing without editing its expectations, unless the phase says otherwise.
8. Run `./gradlew check` at the end of every phase, not only the tests you added.

---

## Phase 1: Long durations and saturating pulse arithmetic

### Objective & Scope

- **In Scope:**
  - `Effect.ApplyAffect.duration`, `World.applyAffect` and `World.applyItemAffect` take a `Long` duration.
  - Saturating helpers for `pulse + duration`, scaling a duration by power, and pulses to seconds.
  - Every place that turns an affect's remaining pulses into seconds uses the saturating helper, so an affect with a
    huge expiry cannot overflow.
  - The script parser reads a `Long` duration for `apply_affect`.
- **Out of Scope:** *(do not attempt these)* the `SEMI_PERMANENT` constant; anything about rooms; widening the duration
  of `summon`, `stun` or `tick_every`, which stay `Int`; changes to the spec's rules other than the duration type.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/TimeConstants.kt` (`PULSE_MS`)
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (`scaled` ~587, `applyAffect` ~355, `applyItemAffect` ~458,
    `applyItemEffect` ~700, the `Effect.ApplyAffect` branch of the actor effect applier ~655)
  - `core/src/main/kotlin/gups/core/world/Gmcp.kt` (`Char.Affects`, ~157-165)
  - `core/src/main/kotlin/gups/core/world/Tools.kt` (~610 and ~661, affect displays)
  - `persist/src/main/kotlin/gups/persist/EffectCodec.kt` (`duration` ~56, ~144, ~180)
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`affectEffect` ~3187)
  - `core/src/test/kotlin/gups/core/world/ItemAffectsTest.kt` (fixture pattern)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/TimeConstants.kt`
  - `core/src/main/kotlin/gups/core/model/Effects.kt`
  - `core/src/main/kotlin/gups/core/world/Combat.kt`
  - `core/src/main/kotlin/gups/core/world/Gmcp.kt`, `core/src/main/kotlin/gups/core/world/Tools.kt`
  - `persist/src/main/kotlin/gups/persist/EffectCodec.kt`
  - `script/src/main/kotlin/gups/script/Declarations.kt`
  - `core/src/test/kotlin/gups/core/world/SaturatingPulsesTest.kt` (new)
  - `docs/CHANGELOG.md`
- **Do Not Touch:** `template-world/`, `plugin-api/`, `Bubble.kt`, `Rules.kt`, `Entities.kt`.

### Specification & Decision Record Changes

None in this phase. The spec text is written in Phase 2, together with the constant.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // TimeConstants.kt
  /** [a] + [b], or Long.MAX_VALUE if the sum would overflow; both are at least 0. */
  fun saturatingAdd(a: Long, b: Long): Long

  /** Whole seconds in [pulses] pulses, without overflowing for any non-negative value. */
  fun pulsesToSeconds(pulses: Long): Long

  // Combat.kt, next to `scaled`
  /** Like [scaled] for a Long amount: rounded down, never below 1 when positive, saturating. */
  internal fun scaledDuration(amount: Long, power: Int): Long

  // Effects.kt
  data class ApplyAffect(val name: String, val duration: Long?, ...)  // was Int?
  ```

  - `World.applyAffect(... duration: Long ...)` and `World.applyItemAffect(... duration: Long? ...)`.
  - `EffectJson` duration for apply-affect becomes `Long?`. `Summon` keeps `Int?`.
- **Algorithmic Steps & Invariants:**
  1. Write `SaturatingPulsesTest` first: `saturatingAdd(5, Long.MAX_VALUE) == Long.MAX_VALUE`; `saturatingAdd(5, 7) ==
     12`; `pulsesToSeconds(Long.MAX_VALUE)` doesn't throw or go negative and is at least `pulsesToSeconds(1_000_000)`;
     `pulsesToSeconds(4) == 1`; `scaledDuration(Long.MAX_VALUE, 200) == Long.MAX_VALUE`; `scaledDuration(1, 50) == 1`;
     `scaledDuration(200, 50) == 100`; `scaledDuration(10, 0)` follows the same rule as `scaled(10, 0)`.
  2. `saturatingAdd`: compute `r = a + b` and return `Long.MAX_VALUE` when `r < 0` (inputs are non-negative).
  3. `pulsesToSeconds`: if `pulses > Long.MAX_VALUE / PULSE_MS`, return `Long.MAX_VALUE / 1000`; else `pulses * PULSE_MS
     / 1000`.
  4. `scaledDuration`: mirror `scaled`; for `amount <= 0` return it; for `power <= 100` compute `amount * power / 100`;
     for a larger power return `Long.MAX_VALUE` when `amount > Long.MAX_VALUE / power`, else compute directly. The
     result is at least 1.
  5. In `applyAffect` and `applyItemAffect`, replace `pulse + duration` with `saturatingAdd(pulse, duration)`. Keep
     `require(duration >= 1)`.
  6. Wherever the effect applier passes `scaled(duration, power)` to `applyAffect` or `applyItemAffect`, use
     `scaledDuration` instead.
  7. In `Gmcp.kt` (`Char.Affects` `remaining`) and `Tools.kt` (~610) replace `(expiresAt - pulse).coerceAtLeast(0) *
     PULSE_MS / 1000` with `pulsesToSeconds((expiresAt - pulse).coerceAtLeast(0))`. Then run `rg -n "PULSE_MS"
     core/src/main script/src/main net/src/main persist/src/main` and fix any other place that multiplies an affect's
     remaining pulses; leave unrelated uses (idle time, shutdown delay) alone.
  8. In `affectEffect` (`Declarations.kt`), read the duration as a Long. Use the existing int helpers' pattern (`int`,
     `intOrNull`); if there is no `long` helper next to them, add one that accepts a Starlark `StarlarkInt` and fails
     with the same message style when it does not fit a `Long`. Keep the "must be at least 1" check.
  9. Fix every compile error that the type change causes (`rg -n "ApplyAffect\(" --glob '*.kt'` to find constructions
     and tests); do not change any test's expectation.

### Test & Documentation Requirements

- `SaturatingPulsesTest` as above.
- Add to `ItemAffectsTest` or a new `core` test: an actor affect applied with a duration of `Long.MAX_VALUE` has
  `expiresAt == Long.MAX_VALUE`, never expires after many pulses, and `Char.Affects`/the `@inspect`-style display
  doesn't throw or print a negative time.
- `persist` test: an `ApplyAffect` effect with a `Long.MAX_VALUE` duration round-trips through `EffectCodec`.
- CHANGELOG: create `### Room enchantments` and add one bullet: `apply_affect` durations are 64-bit; pulse arithmetic
  saturates.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `rg -n "pulse \+ duration|pulse \+ it\b" core/src/main` finds no unsaturated sum left in the affect code.
- [] An actor affect with `Long.MAX_VALUE` duration applies, persists and displays without error.

---

## Phase 2: The `SEMI_PERMANENT` constant

### Objective & Scope

- **In Scope:** a predeclared global `SEMI_PERMANENT` for scripts, its documentation, and the spec text for 64-bit
  durations.
- **Out of Scope:** *(do not attempt these)* anything about rooms; a `None` duration for actors or rooms; changing which
  targets accept `None`.

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/ApiModel.kt` (`PACKAGE_GLOBALS`, ~226)
  - `script/src/main/kotlin/gups/script/Packages.kt` (how package globals are predeclared)
  - `script/src/main/kotlin/gups/script/DocExamples.kt` (the entry for `"DAMAGE"` ~555, as the pattern)
  - `script/src/test/kotlin/gups/script/M5ScriptTest.kt` or `HookCtxFieldsTest.kt` (a script test that applies an
    affect)
  - `script/src/test/kotlin/gups/script/ScriptFixture.kt`
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/ApiModel.kt` and whichever file predeclares the globals (find it with `rg -n
    "PACKAGE_GLOBALS" script/src/main`)
  - `script/src/main/kotlin/gups/script/DocExamples.kt` (only if a test demands an example)
  - `script/src/test/kotlin/gups/script/SemiPermanentTest.kt` (new)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the regenerated API files
- **Do Not Touch:** `core/`, `persist/`, `template-world/`.

### Specification & Decision Record Changes

In `docs/SPEC.md`:

- §4.3, in the paragraph after the effect table that describes durations and `power` scaling (find it with `rg -n
  "rounded down, a positive value never below 1" docs/SPEC.md`), add: "`SEMI_PERMANENT` is a predeclared constant for a
  duration that effectively never ends (the largest 64-bit integer). It is accepted wherever a timed affect takes a
  duration. Scaling and expiry arithmetic never overflow: a scaled duration stops at the largest 64-bit integer. An
  affect whose expiry is that value reports `expires_at` and `remaining` as `None`, like a permanent item affect."
- §4.3, the `apply_affect` row: the `duration` is "pulses, 64-bit".
- §4.15 constants list (where `DIR`, `DAMAGE` and the other predeclared names are listed, if there is one): add
  `SEMI_PERMANENT`.

### Implementation Blueprint

- **Signatures & Types:** `SEMI_PERMANENT` is an int global, `9223372036854775807`. Mirror how the closest existing
  scalar global is predeclared. If every existing global is a namespace object, add a plain int to the same predeclared
  map and add the name to `PACKAGE_GLOBALS` (or whatever list the API model and the linter use to know a name is
  defined).
- **Algorithmic Steps & Invariants:**
  1. Write `SemiPermanentTest` first. It loads a package with `apply_affect(name = "forever", duration = SEMI_PERMANENT,
     ...)` used as a skill effect and as `ctx.apply_affect(target = mob, name = "forever", duration = SEMI_PERMANENT)`
     and checks the resulting affect has `expires_at == None` and `remaining == None`, is still on the actor after 1000
     pulses, and that a cast at `power` 150 still gives an affect that never ends.
  2. Add the constant. Run the linter test (`LinterTest`) to confirm `SEMI_PERMANENT` isn't flagged as undefined.
  3. Check that item skills still reject `None` on actor targets and that `SEMI_PERMANENT` on an item skill is a normal
     positive duration.

### Test & Documentation Requirements

- `SemiPermanentTest` as above.
- A `DocExamples` entry for `SEMI_PERMANENT` only if the docs test demands one.
- CHANGELOG bullet: the `SEMI_PERMANENT` constant.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `./gradlew markdownFormat markdownCheck` is clean.
- [] A script using `SEMI_PERMANENT` loads without a lint or undefined-name error.

---

## Phase 3: Room affects in the model, the chain, tags and persistence

### Objective & Scope

- **In Scope:**
  - `Affect.addTags` and `Affect.removeTags`.
  - `Room.affects`, `Room.liveAffects`, `Room.tagsAt`, and the removal of `Room.tags`.
  - `World.roomClock`, the pulse that room affects are read "as of" (Phase 5 sets it during a regen catch-up).
  - The room affects' step in the modifier chain.
  - Persisting room affects.
- **Out of Scope:** *(do not attempt these)* applying, removing, ticking or expiring room affects (Phase 4); wake
  handling (Phase 5); any script API (Phase 6); the skill target (Phase 7).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Room` ~111, `Affect` ~185, `Item.tags` ~777)
  - `core/src/main/kotlin/gups/core/world/Rules.kt` (`chainSteps`, ~47-90; `ChainStep.Script`)
  - `persist/src/main/kotlin/gups/persist/Codec.kt` (`AffectJson` ~129, `RoomPayload` ~182, room `encode` ~425-455,
    `affectJson` ~351, `affectFromJson` ~377, `restoreAffects` ~408, `applyRoom` ~575)
  - `persist/src/test/kotlin/gups/persist/ItemAffectsPersistenceTest.kt` (pattern)
  - `script/src/test/kotlin/gups/script/RulesTest.kt` (~1369, a lag export reading `ctx.room.tags`)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Entities.kt`
  - `core/src/main/kotlin/gups/core/world/Rules.kt`
  - `core/src/main/kotlin/gups/core/world/World.kt` (only `roomClock` and `catchUpAt`)
  - `script/src/main/kotlin/gups/script/Views.kt` (only the one line in `RoomView.tags` that stops compiling)
  - `persist/src/main/kotlin/gups/persist/Codec.kt`
  - `core/src/test/kotlin/gups/core/world/RoomAffectsChainTest.kt` (new)
  - `persist/src/test/kotlin/gups/persist/RoomAffectsPersistenceTest.kt` (new)
- **Do Not Touch:** `Combat.kt`, `Bubble.kt`, the rest of `World.kt`, `Declarations.kt`, `Ctx.kt`.

### Specification & Decision Record Changes

In `docs/SPEC.md`:

- §3.3 **Room**: change the "Classification and tags" bullet to "builder `regions`, queryable effective `tags`, and
  frozen prototype `modifiers`", and add a bullet: "**Affects:** timed `affects`, the same `Affect` as actors and items
  have. A room affect carries `modifiers`, `flags`, `addTags`, `removeTags`, hooks and `power`, and no stat, sense,
  vital or resist deltas. A room's effective `tags` are the prototype's, plus the `addTags` of its live affects, minus
  their `removeTags`; a tag that is both added and removed is removed. An affect is live at a pulse when its `appliedAt`
  is not later and its `expiresAt` is later than that pulse."
- §3.3 **Affect**: add `addTags` and `removeTags` to the Mechanics bullet ("used by room affects only").
- §4.2 room `tags`: add "A room's effective tags also include those its affects add (§3.3)."
- §4.6 **Chain order**: after "then room modifiers," insert "then the modifiers of the room's live affects (by affect
  id),". Add `room-affect:<name>` to the trace description.
- §3.3 persistence table (find it with `rg -n "zone_states" docs/SPEC.md`): a room's payload includes its affects.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Entities.kt, class Affect: append after `power`
  /** Tags a room gains while this affect is live; empty for any other host. */
  val addTags: Set<String> = emptySet(),
  /** Tags a room loses while this affect is live; empty for any other host. */
  val removeTags: Set<String> = emptySet(),

  // Entities.kt, class Room: remove `val tags`, add
  /** Timed enchantments on this room, in application order. */
  val affects: MutableList<Affect> = mutableListOf()

  /** The affects live at [pulse]: applied by then and not yet expired, by id. */
  fun liveAffects(pulse: Long): List<Affect>

  /** The prototype's tags plus the live affects' added tags, minus their removed tags. */
  fun tagsAt(pulse: Long): Set<String>
  ```

  ```kotlin
  // World.kt
  /**
   * The pulse that room affects are read as of: the current pulse, or the start of the piece of
   * dormant time a regen catch-up is working out.
   */
  val roomClock: Long
    get() = catchUpAt ?: pulse

  /** Set only by the dormant regen catch-up, and always cleared again. */
  internal var catchUpAt: Long? = null
  ```

  ```kotlin
  // Codec.kt
  data class AffectJson(... val addTags: List<String> = emptyList(),
                            val removeTags: List<String> = emptyList())
  data class RoomPayload(... val affects: List<AffectJson> = emptyList())
  ```
- **Algorithmic Steps & Invariants:**
  1. Write the tests first.
     - `RoomAffectsChainTest`: build a `Room` and call `room.affects += Affect(...)` directly. Check `tagsAt` with an
       added tag, a removed prototype tag, an affect that is both added and removed (removal wins), and an expired
       affect (contributes nothing at its `expiresAt`, counts one pulse earlier) and an affect that is not applied yet
       (`appliedAt` later than the pulse asked about; contributes nothing). Check that a `to_hit` modifier on the room
       affect shows in the chain after the room prototype's and before a trait's, and that two room affects run in id
       order.
     - `RoomAffectsPersistenceTest`: save and reload a world whose room has two affects (one with modifiers and
       `onExpire`, one with tags); the reloaded room has equal `affects` (id, name, expiry, tags, hook refs, `power`).
  2. Remove `Room.tags` and add the members above. Fix every compile error by calling `tagsAt(roomClock)` (the world has
     `roomClock`; `RoomView` has `scope.world.roomClock`). Define `liveAffects` as `appliedAt <= pulse && expiresAt >
     pulse`. Search for hidden users with `rg -n "\.tags" core/src script/src net/src cli/src --glob '*.kt'` and check
     each hit that is on a `Room`.
  3. In `chainSteps` (`Rules.kt`), right after the line that adds `room.proto.modifiers[point]`, add one step per live
     affect, in id order: `for (a in room.liveAffects(roomClock)) a.modifiers[point]?.let { out +=
     ChainStep.Script("room-affect:${a.name}", it, affect = a) }`. Mirror the actor-affect loop below it, including how
     `affect = a` is used.
  4. Persistence: extend `affectJson` and `affectFromJson` with the two tag fields (sorted lists in the JSON). In the
     room branch of `encode`, include `affects = e.affects.map(::affectJson)` and make sure a room whose only
     non-default state is an affect still gets a row (check the room branch for any "nothing to save" short cut and
     include affects in it). In `applyRoom`, clear `room.affects` and restore each with `affectFromJson`, resolving hook
     references the same way `restoreAffects` does for actors (factor the shared part out; do not copy it).
  5. Set `room.dirty = true` wherever a test changes affects; the production setters arrive in Phase 4.

### Test & Documentation Requirements

- The two test files above, plus a `RulesTest` case in `script`: a lag export reads `ctx.room.tags` and sees a tag that
  a room affect added (apply the affect by direct list mutation through the fixture's world access, since the script API
  arrives in Phase 6; if the fixture doesn't allow that, put this test in Phase 6 instead).
- KDoc on every new member.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `rg -n "room\.tags\b" core/src script/src --glob '*.kt'` finds no use of the removed property.
- [] A saved and reloaded room keeps its affects.

---

## Phase 4: Applying, removing, ticking and expiring room affects

### Objective & Scope

- **In Scope:** the world operations for room affects, their ticks and expiry in the live pulse, and the end-of-affect
  behavior.
- **Out of Scope:** *(do not attempt these)* wake handling (Phase 5); the script API (Phase 6); the skill target and
  `dispel` (Phase 7).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (`applyItemAffect` ~458, `removeItemAffect` ~512, `endItemAffect`
    ~527, `AffectEndReason`)
  - `core/src/main/kotlin/gups/core/world/World.kt` (the affect loops in phase 1 of the pulse, ~634-660, `Due`,
    `allocateHandle`, `invokeRef`, `send`)
  - `core/src/main/kotlin/gups/core/world/Bubble.kt` (`isSimulated`, ~38)
  - `core/src/test/kotlin/gups/core/world/ItemAffectsTest.kt` (fixture, hook host pattern, `doLine`)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/RoomAffects.kt` (new)
  - `core/src/main/kotlin/gups/core/world/World.kt` (one loop added)
  - `core/src/test/kotlin/gups/core/world/RoomAffectsTest.kt` (new)
- **Do Not Touch:** `Combat.kt`, `Bubble.kt`, `Entities.kt`, `script/`, `persist/`.

### Specification & Decision Record Changes

In `docs/SPEC.md`:

- §3.3 **Affect** Scope bullet: add "A room affect's expire message goes to every actor in the room when it expires or
  is dispelled; `on_expire` and `on_tick` run with the room as `self`."
- §4.4 catalog rows `affect on_tick` and `affect on_expire`: the `self` column reads "`self` (host: an actor, an item or
  a room)".
- §5.1 or the pulse-phase list that mentions affect expiry (find it with `rg -n "expiry" docs/SPEC.md`): add that room
  affects expire and tick in the same phase as actor and item affects, for rooms that are simulated.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // RoomAffects.kt
  /**
   * Puts timed affect [name] on [room] for [duration] pulses; a same-name affect is replaced only
   * by one that expires later, in which case the old one is returned.
   */
  fun World.applyRoomAffect(
    room: Room,
    name: String,
    duration: Long,
    flags: Set<String> = emptySet(),
    tickEvery: Int? = null,
    onTick: HookRef? = null,
    onExpire: HookRef? = null,
    expireMessage: String = "",
    source: Actor? = null,
    modifiers: Modifiers = emptyMap(),
    addTags: Set<String> = emptySet(),
    removeTags: Set<String> = emptySet(),
    power: Int = 100,
  ): Affect?

  /** Removes room affect [name]; true if there was one. */
  fun World.removeRoomAffect(room: Room, name: String): Boolean

  internal fun World.expireRoomAffect(room: Room, affect: Affect)

  /** Ends [affect]: tells the room on expiry or when [announce], then runs `on_expire`. */
  internal fun World.endRoomAffect(room: Room, affect: Affect, reason: AffectEndReason, announce: Boolean = false)
  ```
- **Algorithmic Steps & Invariants:**
  1. Write `RoomAffectsTest` first (fixture as in `ItemAffectsTest`):
     - apply gives an affect with `expiresAt == pulse + duration`; `Long.MAX_VALUE` duration gives `Long.MAX_VALUE`;
     - duration below 1 throws `IllegalArgumentException`;
     - a shorter same-name re-apply returns the old affect and changes nothing; a longer one replaces it (new id);
     - after `duration` pulses the affect is gone, `on_expire` ran once with `reason == "expired"` and `ctx.self` the
       room, and the expire message reached a player in the room and a player in another room did not get it;
     - `removeRoomAffect` returns true, runs `on_expire` with reason `"removed"`, and sends no message;
     - `tick_every = 2` with `on_tick` fires at the right pulses and stops at expiry;
     - a room that is dormant (no players near it) doesn't tick, and an expired affect there isn't removed until Phase 5
       (assert only that nothing throws and that `on_tick` count didn't change);
     - `room.dirty` is true after each change.
  2. `applyRoomAffect`: `require(duration >= 1)`; `expiresAt = saturatingAdd(pulse, duration)`; keep the old affect if
     `existing.expiresAt >= expiresAt`; otherwise remove same-name affects, build the `Affect` (id from
     `allocateHandle()`, `appliedAt = pulse`, the room affect fields, `source = source?.id`, the other delta fields left
     at their defaults, `applies = null`), add it, set `room.dirty = true`, return it. Mirror `applyItemAffect`.
  3. `removeRoomAffect`: end each same-name affect with `AffectEndReason.REMOVED`.
  4. `endRoomAffect`: return if the affect isn't in `room.affects`; remove it; `room.dirty = true`; `why = if (pulse >=
     affect.expiresAt) EXPIRED else reason`; if `(announce || why == EXPIRED)` and the message is not empty, `send` it
     to each actor in `room.actors`, ascending id; then `affect.onExpire?.let { invokeRef(room, it, HookContext(room =
     room, affect = affect, affectEndReason = why)) }`.
  5. In the phase that handles affects (`World.kt` ~634-660), add a loop after the items loop that mirrors it for
     `entities.values` that are `Room` and `isSimulated(e)`. Expiry: `aff.expiresAt <= pulse -> Due(aff.expiresAt,
     aff.id) { expireRoomAffect(e, aff) }`. Tick: the same condition as for items, with the guard that the affect is
     still on the room when the hook runs. Verify that `isSimulated` returns true for an awake room and for a dormant
     room with an active reason; add a test for the second case.
  6. Keep `forgetActorSlots`/`refreshFit`-style recomputes out: nothing cached depends on room affects. Confirm with `rg
     -n "modifier|chain" core/src/main/kotlin/gups/core/world/*.kt` that no cache of chain results exists; if one does,
     invalidate it in `applyRoomAffect` and `endRoomAffect`.

### Test & Documentation Requirements

- `RoomAffectsTest` as above. KDoc on the new functions.
- CHANGELOG bullet: rooms can carry timed affects (tags, modifiers, hooks).

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] A room affect applied with a 3-pulse duration is gone after 3 pulses and its hook ran once.
- [] A room affect applied with `Long.MAX_VALUE` is still there after 1000 pulses.

---

## Phase 5: Dormancy, wake and the regen catch-up

### Objective & Scope

- **In Scope:**
  - Room affects that run out while the room is dormant are removed on wake, first in the wake order.
  - A `dormant_recovery = regen` mob's catch-up (and the `full` mode's fall-back to it) is piecewise over the room's
    affects as well as the mob's own.
- **Out of Scope:** *(do not attempt these)* replaying missed ticks; a room affect's `no_regen` flag suppressing regen;
  any change to how mob or item affects wake.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Bubble.kt` (`wake`, ~222-265; `recover`, `ticksIn` and `catchUpRegen`,
    ~267-322)
  - `core/src/main/kotlin/gups/core/world/Rules.kt` (`chainSteps`, which already reads `roomClock` after Phase 3)
  - `core/src/main/kotlin/gups/core/world/RoomAffects.kt` (Phase 4)
  - `core/src/test/kotlin/gups/core/world/BubbleTest.kt` (how a test sends a room to sleep and wakes it, and the
    existing piecewise regen test for a `no_regen` affect)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Bubble.kt`
  - `core/src/test/kotlin/gups/core/world/RoomAffectsWakeTest.kt` (new)
  - `core/src/test/kotlin/gups/core/world/RoomAffectsRegenTest.kt` (new)
- **Do Not Touch:** `RoomAffects.kt`, `World.kt`, `Rules.kt`, `script/`, `persist/`.

### Specification & Decision Record Changes

In `docs/SPEC.md`:

- §5.9 **Going dormant**, the line about affects: add "Room affects behave the same: they keep running out while the
  room is dormant, and their ticks don't run."
- §5.9 **Waking**, step 3: change to "Affects that ran out are removed, firing `on_expire` in expiry order, then id:
  first the room's own affects, then each mob's own affects, mob by mob, then the affects of every item in the room (on
  the ground, inside containers, or carried by its mobs)."
- §5.9 **Waking**, step 4: unchanged; note that a missed reset runs after the room's expired affects are gone.
- §5.9 **Catch-up is piecewise**: change to "The elapsed time is split at every affect expiry that fell inside it, at
  every point where one of the room's affects was applied or expired, and regen is computed per segment with the mob's
  affects and the room's affects that were active in that segment." Add: "A room affect counts as active at a tick when
  its `appliedAt` is not later than the tick and its `expiresAt` is later than it. The room's expired affects are still
  in place when mobs recover, because they are removed in step 3. A room affect's `no_regen` flag does nothing: only the
  mob's own `no_regen` suppresses regen."

### Implementation Blueprint

- **Signatures & Types:** none new. `World.catchUpAt` and `World.roomClock` exist from Phase 3.
- **Algorithmic Steps & Invariants:**
  1. Write `RoomAffectsWakeTest` first:
     - a room with a 10-pulse affect and `on_expire` goes dormant, 20 pulses pass, a player enters: the affect is gone,
       `on_expire` ran exactly once with `reason == "expired"` before the room's `on_wake` hook and before the room's
       `on_reset` if a reset was missed;
     - order: record calls from a room affect's `on_expire`, a mob affect's `on_expire` and an item affect's `on_expire`
       that all expired during dormancy; the room's comes first, then the mob's, then the item's;
     - an affect that has not yet expired survives the wake and keeps its `expiresAt`;
     - during dormancy `room.tagsAt(roomClock)` already excludes the expired affect;
     - a `SEMI_PERMANENT` room affect survives wake;
     - a room affect with `tick_every` doesn't fire `on_tick` while dormant and isn't replayed on wake.
  2. Write `RoomAffectsRegenTest` first. Use a fixture mob with `dormant_recovery = regen`, a rules `regen` that gives a
     fixed amount per world tick, and a room affect whose `regen` modifier halves it. Let `T` be the number of world
     ticks in the dormant time, and compute the expected vitals by hand in the test:
     - the affect expires halfway through the dormancy: the first half regenerates at the halved rate, the second half
       at the normal rate (the old behavior would give the normal rate for all of it; assert that it doesn't);
     - the affect was applied (with `applyRoomAffect` on the dormant room) halfway through the dormancy and is still
       live at wake: only the second half is halved;
     - the affect covers the whole dormancy and is still live: all of it is halved;
     - the affect expired before the room went dormant: nothing is halved;
     - two room affects overlapping partly give three segments with the right product of modifiers;
     - a mob with its own affect expiring inside the same span still splits correctly (cuts from both sources merge);
     - a `full`-mode mob with a mob `no_regen` affect that falls back to catch-up gets the same room-aware result;
     - a room affect with the `no_regen` flag does not suppress regen;
     - `World.catchUpAt` is `null` again after the wake, also when a rules export throws.
  3. In `wake`, in step 3 before the mobs' loop, add: `if (room.activeReasons.isEmpty() &&
     room.anchorReasons.isEmpty())` then take `room.affects.filter { it.expiresAt <= pulse }.sortedWith(compareBy({
     it.expiresAt }, { it.id }))` and call `guarded("expiry of ${a.name} on $room") { expireRoomAffect(room, a) }` for
     each. The guard on reasons mirrors `sleepers`: a room with an active or anchor reason was never paused, so it
     expired its affects live.
  4. Confirm by reading `wake` that the order is: timers resume, mobs recover, room affects, mob affects, item affects,
     missed reset, respawns, decay, `on_wake`. Mobs must recover before the room's affects are removed, because the
     catch-up needs them. Do not reorder anything else.
  5. In `catchUpRegen`, build the cut points from both sources: `val room = m.room`, `val roomAll =
     room.affects.toList()`, and `cuts = (all.map { it.expiresAt } + roomAll.flatMap { listOf(it.appliedAt,
     it.expiresAt) }).filter { it in (since + 1)..pulse }.distinct().sorted()`. For each segment `[lo, hi)`, before
     calling `regenOf`, set `catchUpAt = lo`; the chain then reads the room's affects as of `lo` (applied by then and
     not expired), exactly as the mob's own affects are filtered by `expiresAt > lo`. Leave the existing swap of
     `m.affects` as it is. Clear `catchUpAt = null` in the existing `finally` block, which also restores `m.affects`.
  6. Nothing else may read `catchUpAt`. In particular the early `break` when every vital is full, the `no_regen` flag
     test (`m.hasFlag`) and `ticksIn` are unchanged.
  7. The `hi` of a segment and the tick that falls on a cut belong to the segment that starts at the cut, as for mob
     affects (an affect expiring at `e` is inactive from the tick at `e` on; one applied at `a` is active from the tick
     at `a` on).

### Test & Documentation Requirements

- `RoomAffectsWakeTest` and `RoomAffectsRegenTest` as above. Existing `BubbleTest` cases stay unchanged and green.
- CHANGELOG bullets: room affects expire while dormant and are removed first on wake; regen catch-up accounts for room
  affects piece by piece.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] The ordering test passes, and `BubbleTest` is unchanged and green.
- [] The half-expired room affect case regenerates at the halved rate for exactly the first half.

---

## Phase 6: Script API

### Objective & Scope

- **In Scope:**
  - `ctx.apply_affect(room, ...)` and `ctx.remove_affect(room, name)`.
  - New `add_tags` and `remove_tags` keywords on `apply_affect` (the effect primitive and the `ctx` verb).
  - `room.affects` and `room.tags` reflecting affects.
  - The load and call errors for actor-only fields on a room.
- **Out of Scope:** *(do not attempt these)* the `room` skill target and `dispel` on rooms (Phase 7); `has_affect`;
  `ctx.dispel`; `add_tag`/`remove_tag` for rooms; the harness's `w.` API unless it already accepts item targets for
  `apply_affect` (then mirror it for a room, nothing more).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Ctx.kt` (`apply_affect` ~1132-1315, `remove_affect` ~1320-1342)
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`affectEffect` ~3187, the `apply_affect` declaration ~1810 and
    ~1928, `TAG_REGEX` and `parseTags` ~566)
  - `script/src/main/kotlin/gups/script/Views.kt` (`RoomView` ~425, `AffectView` ~811, `ActorView.affects` ~287)
  - `core/src/main/kotlin/gups/core/model/Effects.kt` (`ApplyAffect`)
  - `script/src/test/kotlin/gups/script/ItemEffectsScriptTest.kt` and `ScriptFixture.kt` (script test pattern)
  - `harness/src/main` (`rg -n "apply_affect" harness/src`)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Effects.kt` (two new `ApplyAffect` fields)
  - `persist/src/main/kotlin/gups/persist/EffectCodec.kt` (the same two fields)
  - `script/src/main/kotlin/gups/script/Declarations.kt`
  - `script/src/main/kotlin/gups/script/Ctx.kt`
  - `script/src/main/kotlin/gups/script/Views.kt`
  - `script/src/test/kotlin/gups/script/RoomAffectScriptTest.kt` (new)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API files
- **Do Not Touch:** `plugin-api/`, `template-world/`, `core/src/main/kotlin/gups/core/world/` (the world operations are
  done).

### Specification & Decision Record Changes

In `docs/SPEC.md`:

- §4.3 `apply_affect` signature row: append `add_tags=[], remove_tags=[]`, and add to the description: "On a room
  (`ctx.apply_affect(room, ...)` only; a room is not a skill target until the `room` target below), only `modifiers`,
  `flags`, `tick_every`, `on_tick`, `on_expire`, `expire_msg`, `add_tags` and `remove_tags` apply, and the duration is
  required. `add_tags` and `remove_tags` are an error for any other target."
- §4.15 context verbs table, the "Vitals and combat" row: change `remove_affect(target, name)` text "(target: an actor,
  an item or a room)" and `apply_affect(target, ...)` likewise.
- §4.15 Room view: add "`affects` (the live affects, by id); `tags` are the effective tags (§3.3)".
- §4.15 Affect view: `host` "an actor, an item or a room".
- §4.15 or §3.3: for the cross-zone rule on room affects, nothing is added; a script may apply an affect to any room it
  can name, as it may move items anywhere.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Effects.kt, ApplyAffect: append
  /** Tags a room gains while the affect lasts; used only on a room. */
  val addTags: Set<String> = emptySet(),
  val removeTags: Set<String> = emptySet(),
  ```

  ```python
  # Starlark
  ctx.apply_affect(target = room, name = "consecrated", duration = 1200,
                   modifiers = {...}, flags = ["holy"], add_tags = ["sanctified"],
                   remove_tags = ["cursed"], tick_every = 20, on_tick = fn,
                   on_expire = fn, expire_msg = "The light fades.")
  ctx.remove_affect(target = room, name = "consecrated")
  room.affects  # list of affect views, by id
  ```
- **Algorithmic Steps & Invariants:**
  1. Write `RoomAffectScriptTest` first:
     - `ctx.apply_affect(room, ...)` returns an affect view whose `host` is the room, and `room.tags` shows the added
       tag and hides the removed one;
     - a `to_hit` modifier on the room affect changes a swing in that room and not in the next room;
     - `SEMI_PERMANENT` gives `expires_at == None` and `remaining == None`;
     - `ctx.remove_affect(room, name)` returns True the first time and False the second;
     - errors, each with the exact message listed in step 4: `stat_deltas`, `sense_deltas`, `max_vital_deltas`,
       `resist_deltas`, `procs`, `applies` and a `None` duration on a room; `add_tags` on an actor and on an item; an
       invalid tag in `add_tags`;
     - `on_tick` and `on_expire` hooks run with `ctx.self` the room (use `ctx.self.label` in the hook);
     - re-applying with a shorter duration returns the existing affect;
     - `room.affects` after a reload is the same.
  2. Add the two fields to `Effect.ApplyAffect` and `EffectCodec` (sorted lists in JSON). Extend `affectEffect` with two
     parameters `addTags` and `removeTags`; parse them with the same string-list and `TAG_REGEX` validation `parseTags`
     uses, failing with `"$fn(add_tags): '<t>' is not a valid tag"` (and `remove_tags`). Add both keywords, default
     empty, to the `apply_affect` primitive declaration and to the `ctx.apply_affect` verb, and pass them through.
  3. In the `ctx.apply_affect` verb, add a branch before the actor branch: `if (target is RoomView)`. It calls
     `world.applyRoomAffect(room, e.name, d, e.flags, e.tickEvery, e.onTick, e.onExpire, e.expireMessage, owner as?
     Actor, e.modifiers, e.addTags, e.removeTags)` with `d = e.duration ?: fail(...)`, and returns `AffectView(frame,
     room, a)` or `None`. Add a `roomArg` helper next to `itemArg`/`actorArg` if needed. In the item and actor branches
     fail when `e.addTags` or `e.removeTags` is not empty.
  4. Exact messages (`fail(...)`):
     - `apply_affect(duration): a room's affect needs a duration`
     - `apply_affect(stat_deltas): a room's affect can't change stats` (likewise `sense_deltas`, `max_vital_deltas`,
       `resist_deltas`: "can't change senses", "can't change maximum vitals", "can't change resists")
     - `apply_affect(procs): a room's affect can't have procs`
     - `apply_affect(applies): only item affects take applies` (the existing text)
     - `apply_affect(add_tags): only a room's affect can add tags` (and `remove_tags`: `... can remove tags`) Check that
       the actor-only fields are non-empty (not merely present) before failing.
  5. `ctx.remove_affect`: add the `RoomView` branch calling `world.removeRoomAffect`; update the doc strings of both
     verbs ("Actor, item or room").
  6. `RoomView`: change `tags()` to `room.tagsAt(scope.world.pulse)`; add `affects` as a `structField` returning
     `sList(room.liveAffects(scope.world.pulse).map { AffectView(scope, room, it) })`. Read the existing `read { }`
     helper first and use it the same way the other members do.
  7. Update the `AffectView` doc strings only if they say "actor".
  8. If the harness exposes `apply_affect`/`remove_affect` on `w.` for items, add the room case the same way; otherwise
     leave `harness/` alone.

### Test & Documentation Requirements

- `RoomAffectScriptTest` as above.
- The Phase 3 `RulesTest` case that was deferred, if any.
- CHANGELOG bullets: rooms accepted by `ctx.apply_affect` and `ctx.remove_affect`; `add_tags` and `remove_tags`;
  `room.affects`; `room.tags` includes affect tags.
- A `DocExamples` entry for the two new keywords only if the docs test demands one.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings; `ApiDiffTest` passes after regeneration.
- [] The script test for each error message passes with the exact text.
- [] Applying a room affect from a hook and seeing its tag from a rules export works end to end (`gups scenario` check,
  below).
- [] End-to-end check: copy the template world to `local-world-test/world`, add a tiny package with an `on_enter` hook
  calling `ctx.apply_affect(room, "ward", 20, add_tags = ["warded"])`, then run `gups scenario --world
  local-world-test/world` with a scenario that enters the room, checks `room.tags` through a rules export or a `t.eq`,
  runs 25 pulses and checks the tag is gone. Delete `local-world-test/` afterwards.

---

## Phase 7: The `room` skill target

### Objective & Scope

- **In Scope:** `TargetKind.ROOM`, its declaration, its checks, its casting and its effect on the caster's room.
- **Out of Scope:** *(do not attempt these)* plugin primitives on a room (Phase 8); `room` as an item `use_target`;
  `apply_affect(applies = ...)`; an AI or mob change; the template world.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`TargetKind` ~351, `SkillRange` ~330-346, `SkillProto` ~420)
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt` (`chooseTarget` ~143, every use of `targetItem`)
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (`applyItemEffect` ~700-760, the actor effect applier ~645-690)
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt` (the `when (target kind)` blocks ~856-890 and ~944)
  - `core/src/main/kotlin/gups/core/world/Procs.kt` (~125-140)
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`checkEffectTarget` ~1370-1410, the skill declaration
    ~1335-1366, `use_target` ~1213, `TARGET_KINDS` ~2076)
  - `script/src/test/kotlin/gups/script/ItemEffectsScriptTest.kt`, `SkillVerbsTest.kt` (cast test patterns)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt`
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt`
  - `core/src/main/kotlin/gups/core/world/RoomAffects.kt` (`applyRoomEffect`)
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt`, `core/src/main/kotlin/gups/core/world/Procs.kt` (only the
    exhaustive `when` branches the compiler asks for)
  - `script/src/main/kotlin/gups/script/Declarations.kt`
  - `core/src/test/kotlin/gups/core/world/RoomSkillTest.kt` and
    `script/src/test/kotlin/gups/script/RoomSkillScriptTest.kt` (new)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API files
- **Do Not Touch:** `plugin-api/`, `template-world/`, `Bubble.kt`.

### Specification & Decision Record Changes

In `docs/SPEC.md`:

- §4.3 skill `target` text (find it with `rg -n "target=" docs/SPEC.md` and the Targeting list): add the kind `room`.
  Add a bullet under **Targeting**: "A `room` skill acts on the caster's room and needs no keyword. Its `effect` may use
  only `apply_affect`, `dispel` and `sequence`; anything else fails the load. `apply_affect` attaches a room affect (the
  room rules of §3.3 apply, and `applies`, `procs` and the actor deltas are load errors); `dispel` removes the room's
  matching affects. The target kind `room` is not the `range` value `room`: `range` is ignored for a `room` target."
  Phase 8 widens the sentence about the effect; write it as it stands here for now.
- §4.3 power paragraph: a room affect's duration is scaled by the cast's power and the affect remembers it.
- §4.3 `dispel` row: "On a `room` skill it removes the room's matching affects."

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Protos.kt, enum TargetKind, append
  /** Targets the caster's room. */
  ROOM,

  // RoomAffects.kt
  /** Applies a room skill's [effect] from [caster] to [room] at [power] percent. */
  fun World.applyRoomEffect(effect: Effect, caster: Actor, room: Room, power: Int = 100)
  ```
- **Algorithmic Steps & Invariants:**
  1. Write the tests first.
     - `RoomSkillTest` (Kotlin protos): a `ROOM` skill cast by a player puts an affect on the caster's room only; a
       player in another room sees nothing; power 150 gives a 1.5× duration; the affect's `power` is 150; the cast pays
       its cost and lag; a failed `skill_check` leaves the room unchanged; `dispel` with a name removes the matching
       affect and sends its `expire_msg` to everyone in the room; `dispel(all = True, count = 1)` removes only the
       oldest.
     - `RoomSkillScriptTest`: `skill("consecrate", target = "room", effect = apply_affect(...))` loads; `damage(...)` as
       the effect fails with `skill(effect): damage acts on actors; a room skill may use apply_affect, dispel and
       sequence`; a plugin primitive in a `room` skill fails with the same sentence naming it; `apply_affect` with
       `stat_deltas` or `procs` or `applies` fails the load; `target = "room"` in `item(use_target = ...)` fails the
       load with `item(use_target): 'room' is not a valid use target`; a `sequence` of two `apply_affect`s works; the
       skill appears in the `skills` command with its target.
  2. Add `ROOM` at the end of `TargetKind`. Let the compiler find every exhaustive `when` and handle each:
     - `SkillCommands.chooseTarget`: `TargetKind.ROOM -> null` with the caster's room remembered like `targetItem` (a
       private `targetRoom` field set to `caster.room`); nothing is asked of the player and extra words are ignored, as
       for `NONE`. Follow `targetItem` through the rest of `SkillCommands.kt` and add the room branch wherever the item
       branch picks how the effect is applied: call `w.applyRoomEffect(effect, caster, room, power)`.
     - In the cast completion code (the block that builds `onItem`, sends the done messages, and loops over `targets`):
       add an `onRoom` beside `onItem`; run `applyRoomEffect` once before the loop; and skip the loop's `applyEffect(it,
       actor, each, ...)` when `onRoom != null`, as it is skipped for an item. The cast messages fall back to the `t ==
       null` self and room messages ("You cast ...", "<Name> casts ..."); add no new message. A `resolve` function of a
       `room` skill gets `ctx.target` and `ctx.item` as `None` and reads the room from `ctx.room`.
     - `KernelCommands.kt` and `Procs.kt`: `ROOM` behaves like `NONE` (a proc outcome of a room skill has no target;
       these branches should be unreachable in practice but must not throw).
  3. `applyRoomEffect`: mirror `applyItemEffect`. `Sequence` recurses; `ApplyAffect` calls `applyRoomAffect(room,
     effect.name, scaledDuration(requireNotNull(effect.duration), power), effect.flags, effect.tickEvery, effect.onTick,
     effect.onExpire, effect.expireMessage, caster, effect.modifiers, effect.addTags, effect.removeTags, power)`;
     `Dispel` takes `room.affects.filter(effect::matches)` (the `intrinsic` rule is inside `matches`), keeps the first
     `count ?: size` by application order (the list order), and ends each with `endRoomAffect(room, a,
     AffectEndReason.REMOVED, announce = true)`; every other effect is a no-op (the load check rejects them, so this
     only guards direct Kotlin use).
  4. `checkEffectTarget` (`Declarations.kt`): add a `TargetKind.ROOM` branch. For each leaf: `ApplyAffect` fails with
     the room messages of Phase 6 (stat, sense, vital, resist deltas, procs and applies non-empty; duration `None`),
     `Dispel` and `Sequence` pass, anything else (`Plugin` included) fails with `"$what: ${primitiveName(leaf)} acts on
     actors; a room skill may use apply_affect, dispel and sequence"`. Pass `add_tags`/`remove_tags` through the
     validation already done at parse time.
  5. Make sure a non-room skill whose effect has `add_tags` or `remove_tags` fails the load with `"$what:
     apply_affect(add_tags) is only for room skills"` (extend the existing actor/item branches).
  6. `use_target`: reject `room` at load with the message in step 1.
  7. Check `rg -n "TargetKind\." core/src script/src net/src cli/src harness/src --glob '*.kt'` for a `when` on skill
     targets elsewhere (for example a help or `skills` listing) and add a `room` case; the compiler will flag exhaustive
     ones, but also read any `else` branch that could mislabel it.

### Test & Documentation Requirements

- The two test files above.
- CHANGELOG bullets: the `room` skill target; `dispel` on a room.
- A `DocExamples` entry only if the docs test demands one.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings; `ApiDiffTest` passes after regeneration.
- [] A player casting a `room` skill enchants only the room they stand in, and a second player entering sees the
  modifier's effect.
- [] End-to-end check with `gups scenario`: copy the template world to `local-world-test/world`, add a `ward` skill with
  `target = "room"` and a `to_hit` modifier through a small package, give it to a player, cast it, check a swing in that
  room is changed and that after the affect runs out it isn't. Delete `local-world-test/` afterwards.

---

## Phase 8: Plugin primitives on a room

### Objective & Scope

- **In Scope:**
  - `PluginEffectCall.targetRoom`, set when a plugin primitive runs in a `room` skill.
  - The plugin effect runner carrying the room from the world to the plugin host.
  - `room` skills accepting plugin primitives.
- **Out of Scope:** *(do not attempt these)* new built-in plugin primitives; changes to the sample plugins' behavior;
  any other new field on `PluginEffectCall`; raising `PluginApi.VERSION`; plugin primitives in `item(use_effect=...)` of
  a room target (`room` is not a valid `use_target`).

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt` (`PluginEffectCall` ~216, `PluginEffect` ~228,
    `PluginRoom`)
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt` (the `PluginEffectRunner` lambda, ~655-675, and the
    room facade class: `rg -n "class .*Facade" plugin-api/src/main`)
  - `core/src/main/kotlin/gups/core/world/World.kt` (`PluginEffectRunner` ~120, `pluginEffects` ~200)
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (the two `pluginEffects.apply` calls, ~631 and ~728)
  - `core/src/main/kotlin/gups/core/world/RoomAffects.kt` (`applyRoomEffect` from Phase 7)
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`checkEffectTarget`)
  - `plugin-api/src/test/kotlin/gups/pluginapi/ApiSurfaceTest.kt`, and the existing plugin-host tests that register an
    effect (`rg -n "targetItem" plugin-api/src/test samples`)
  - `samples/curse-plugin/src/test/kotlin/gups/sample/curse/CursePluginTest.kt` (how an item-only primitive treats a
    call with no item, ~565 and ~588)
  - `docs/PLUGINS.md` (`rg -n "PluginEffectCall|targetItem" docs`)
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`
  - `core/src/main/kotlin/gups/core/world/World.kt` (`PluginEffectRunner` and its default value only)
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (the two existing call sites pass `null`)
  - `core/src/main/kotlin/gups/core/world/RoomAffects.kt`
  - `script/src/main/kotlin/gups/script/Declarations.kt`
  - `plugin-api/src/test/kotlin/gups/pluginapi/RoomEffectTest.kt` (new; name it after the existing host test pattern)
  - `script/src/test/kotlin/gups/script/RoomSkillScriptTest.kt` (the plugin-primitive expectation changes)
  - `docs/SPEC.md`, `docs/PLUGINS.md` (if it describes the call), `docs/CHANGELOG.md`, and any regenerated API files
- **Do Not Touch:** `samples/` main code, `template-world/`, `Bubble.kt`, `PluginApi.VERSION`.

### Specification & Decision Record Changes

In `docs/SPEC.md`:

- §4.19 (find it with `rg -n "targetItem" docs/SPEC.md`): where the plugin effect call is described, add that
  `targetRoom` is the caster's room for a `room` skill and null otherwise, and that for a `room` skill `target` and
  `targetItem` are null. A primitive written for actor or item targets sees a call without its target and should ignore
  it or report the problem as it does when handed no item.
- §4.3 Targeting bullet for `room` skills (from Phase 7): the `effect` "may use `apply_affect`, `dispel`, `sequence` and
  plugin primitives; anything else fails the load".

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // PluginApi.kt
  data class PluginEffectCall(
    val world: PluginWorld,
    val caster: PluginActor,
    /** Null for `target = "none"`, `"item"` and `"room"` skills. */
    val target: PluginActor?,
    val args: Map<String, Any?>,
    /** The target item of an `item` skill, else null. */
    val targetItem: PluginItem? = null,
    /** Percent strength the effect runs at; 100 outside casts and item effects. */
    val power: Int = 100,
    /** The caster's room for a `room` skill, else null. */
    val targetRoom: PluginRoom? = null,
  )

  // World.kt
  fun interface PluginEffectRunner {
    fun apply(
    world: World, effect: Effect.Plugin, caster: Actor,
    target: Actor?, targetItem: Item?, targetRoom: Room?, power: Int,
    )
  }
  ```
- **Algorithmic Steps & Invariants:**
  1. Write the tests first.
     - `RoomEffectTest`: register a plugin effect that records its `PluginEffectCall`. A `room` skill whose effect is
       that primitive, cast by a player, calls it once with `target == null`, `targetItem == null`, `targetRoom` the
       caster's room (check its label), the caster, the `args` and `power` (a cast at power 150 gives 150). A `sequence`
       of the primitive and an `apply_affect` runs both, in order. The same primitive in an actor skill still gets
       `targetRoom == null` and its target. A plugin effect that throws is guarded like any other (a diagnostic, the
       cast goes on).
     - `RoomSkillScriptTest`: a `room` skill with a plugin primitive now loads (change the earlier expectation that it
       fails); `damage(...)` still fails with the message below.
     - `ApiSurfaceTest` stays green: `PluginEffectCall` mentions no `core` or `script` type.
  2. Add the field last in `PluginEffectCall`, with a default, so existing construction sites (the sample plugins' tests
     call the constructor positionally up to `args`) keep compiling.
  3. Add the `targetRoom: Room?` parameter to `PluginEffectRunner.apply` before `power`; update the default no-op lambda
     in `World.kt` (`{ _, _, _, _, _, _ -> }` gains a placeholder) and the two calls in `Combat.kt` to pass `null`.
  4. In `PluginHost`, accept `targetRoom` in the lambda and pass `targetRoom = targetRoom?.let { <room facade>(it, w) }`
     to `PluginEffectCall`, using the same facade class the plugin API already uses for rooms elsewhere (for hook
     calls).
  5. In `applyRoomEffect`, add `is Effect.Plugin -> pluginEffects.apply(this, effect, caster, null, null, room, power)`.
  6. In `checkEffectTarget`, let `Effect.Plugin` pass for `TargetKind.ROOM`, and change the message for other leaves to
     `"$what: ${primitiveName(leaf)} acts on actors; a room skill may use apply_affect, dispel, sequence and plugin
     primitives"`. Update the Phase 7 test that asserts the old text.
  7. `PluginApi.VERSION` stays 1 and nothing gets `@ApiSince` (the engine is unreleased). Check whether any plugin API
     golden or docs file lists `PluginEffectCall`'s members (`rg -n "PluginEffectCall" cli docs plugin-api --glob
     '!*.kt'`) and regenerate it as guardrail 4 says.
  8. Run the sample plugin modules' tests (`./gradlew :samples:weather-plugin:test :samples:curse-plugin:test`, or
     whatever `./gradlew check` runs for them) and confirm that they pass unchanged.

### Test & Documentation Requirements

- `RoomEffectTest` and the updated `RoomSkillScriptTest` case.
- `docs/PLUGINS.md`: if it documents `PluginEffectCall`, add `targetRoom` and what a primitive should do when it gets a
  room call and handles only actors or items.
- CHANGELOG bullet: plugin primitives can run in `room` skills and read `PluginEffectCall.targetRoom`.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including the sample plugin modules.
- [] A plugin primitive used as a `room` skill's effect sees the caster's room in `targetRoom` and null in `target` and
  `targetItem`.
- [] No existing plugin test needed an edited expectation.

---

## Phase 9: Finalization & Backlog Update

- **Spec bookkeeping:** `docs/SPEC.md` §7.4 table, add a row after M34, in the style of the other rows (one line, ending
  with the sections touched): "**M35** | Room enchantments: timed `Affect`s on rooms with modifiers and tag changes,
  `apply_affect` and `remove_affect` on a room, `room.affects`, 64-bit affect durations and `SEMI_PERMANENT`, the `room`
  skill target and plugin primitives on a room (§3.3, §4.2, §4.3, §4.4, §4.6, §4.15, §4.19, §5.9)." Run `rg -n "M34"
  docs/SPEC.md` to find the row to copy the column widths from.
- **Backlog:** in `docs/DESIGN_NEEDED.md`, rename `### Enchantments on rooms` to `### Enchantments on rooms (COMPLETED —
  M35)` and leave the body. Do not delete it.
- **Spec consistency pass:** `rg -n "room.*tags|Room.*tags" docs/SPEC.md` and check each hit still says the right thing
  about effective tags; check that the §4.6 chain order sentence, the §5.9 wake order and the §3.3 Room bullet agree.
- Run `./gradlew markdownFormat markdownCheck`, then check that code blocks in the edited Markdown kept their
  indentation.
- Run the full verification: `./gradlew check`.
- Confirm that `local-world-test/` is deleted and nothing from it is staged.
