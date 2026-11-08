# Milestone 45: Mob Leashes and Bounded Pursuit

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, section "Perception and the world", heading `### Cost of `pursue`
  and other world-wide searches`.

- **Design Summary:**

  - **`pursue` searches once, within a limit.** `pursue(target, range=10, disposition_below=None, priority=40)`.
    - `range` is in exits from the pursuer's current room, through open doors. `None` means no limit.
    - Each think runs **one** breadth-first search from the pursuer's room instead of one `path_to` per candidate
      anywhere in the world. Exits are visited in declaration order, as in `path_to`. The search crosses only rooms
      inside the mob's leash (below) and stops at `range`.
    - It takes every candidate in the rooms at the first distance that has any, then the lowest id. The first step is
      the first exit of the search-tree path to that candidate's room. Without a leash, this is exactly today's "nearest
      by path length, then lowest id" and the same first step `path_to` gives.
    - The search does not read or fill `pathCache`.
    - Candidates must also be alive (`condition != DEAD`), a bug fix: the in-room check already excludes the dead.
  - **`leash` is a mob attribute, not a behavior argument.**
    - **Prototype field:** `mob(..., leash=None)`. `None` (the default) means no leash. An int is the most exits the mob
      goes from its `home_room`, counted through open doors, the same count `wander` uses. `0` keeps it in its home
      room. A negative value is a load error.
    - **Per-instance override:** `ctx.set_leash(mob, leash)` takes an int ≥ 0 or `None` (unleashed), and
      `ctx.reset_leash(mob)` clears the override so the mob follows its prototype again. Only the override is stored on
      the instance and saved. The prototype value is read live, so a reload that changes `leash` reaches every mob
      without an override, with no reconcile rule. Scripts never need to remember an old value.
    - **Views:** `ctx.self.leash` (the effective value: the override if one is set, else the prototype's; `None` for
      players) and `ctx.self.leash_overridden` (bool; `False` for players).
    - **What respects it:** `wander` (its area becomes `within(home, min(radius, leash))`), `pursue` (it searches only
      inside the leash), and `follow` (while its leader is outside the leash it proposes nothing, so the mob heads home,
      and it keeps the leader under the existing 3-exit rule, resuming if the leader comes back inside the leash).
    - **What ignores it:** `flee`, `heel`, `defend`, every owned mob, and script or verb moves (`act_move`, `teleport`,
      `MoveAction`s enqueued by `think`).
    - Soft "reluctance" is world policy: a world's `think` can veto a step from `ctx.proposals`, reading
      `ctx.self.home_room`. The engine adds no reluctance curve.
  - **Returning home is built in, and goes all the way home.**
    - **Home ground:** with a `wander` behavior (the first one in its list), a leashed mob's home ground is its wander
      area, `within(home, min(radius, leash))`. Without `wander`, it's just its home room.
    - **Conditions:** the mob has a leash (not `None`), has no owner, isn't engaged, its home room exists, and it stands
      outside its home ground.
    - **The proposal:** while the conditions hold, `World.think` adds one engine proposal, the first step of
      `path_to(room, home_room)`. It has priority `PRIORITY_RETURN = 20` and source `"leash"`, and sorts after every
      behavior of equal priority.
    - **No "returning" state:** `aggressive` (60), `pursue` (40) and `follow` (30) outrank the proposal while they have
      a target inside the leash and in range. Once nothing is left to chase, the return wins, and the mob walks back.
      Past its leash, `pursue` and `follow` propose nothing.
    - **The exploit this closes:** a strong player who pulls a temple guardian to its leash edge and steps past it sends
      the guardian straight home. It doesn't stand at the edge leaving the temple open.
    - `sentinel` drops the proposal like any other move, and `think` sees it in `ctx.proposals` and can override it.
    - Unleashed mobs (`leash=None`) never get it. An enraged spectre comes home once `reset_leash` restores its leash.
  - **When to head home is world policy: the new optional rules export `leash_return(ctx) -> bool`** (kernel).
    - **When it's asked:** once per think, only while the return conditions hold, before the proposal is added.
    - **Its ctx:** `ctx.actor` (the mob), `ctx.away` (pulses since the conditions started holding without a break),
      `ctx.past_leash` (whether the mob is past its leash, not just outside its home ground), `room`, `pulse` and `rng`.
    - **Result:** `True` adds the proposal and `False` holds it back this think. The default (export not defined) is
      `True`. Worlds build lingering, posts held longer, or never going back by reading the mob's label, flags, `attrs`
      or affects.
    - **State:** the transient `Mob.awaySince: Long?`. It's set on the first think the conditions hold and cleared on
      any think they don't. It isn't saved, and counts in absolute pulses, so dormant time counts.
  - **`wander` comes home too.** Outside its area (radius or leash, whichever is smaller), `wander` proposes, still
    subject to `chance`, the first step of `path_to(room, home_room)` instead of nothing. This fixes today's stranding
    of a mob that ends up outside its wander radius, leashed or not.
  - **Leash areas are cached.** `World.homeArea(home, n)` caches `within(home, n)` by `(home room id, n)`, and
    `graphChanged()` clears it with `pathCache`.
  - **No plugin API change:** `PluginActor` doesn't show `home_room` today, and plugins have no mob-movement verbs.
  - **Out of scope:** `heel` (one target, unbounded by design); scripts' own `ctx.path_to`; movement speed and catch-up
    (the new `DESIGN_NEEDED` entry "Movement speed and pursuit catch-up", untouched by this milestone).
- **Specification Impact:**
  - §3.4: the `mob(...)` example (`leash`) and the persisted mob fields (the leash override).
  - §3.10: the kernel row of the feature table (`set_leash`, `reset_leash`).
  - §4.5: the actor view's **Position** fields, the verb table (a **Leash** row), and the verb semantics (`set_leash`,
    `reset_leash`).
  - §4.6: the optional-exports sentence, the rules `ctx` fields (`away`, `past_leash`), and a new catalog row and
    paragraph for `leash_return`.
  - §4.7: the `wander`, `follow` and `pursue` rows, and a new **Leash** bullet.
  - §5.8: the think cycle's step 1 (the return proposal).
  - `docs/CHANGELOG.md` and the regenerated API files.
- **Decision Record Updates:** None. D-121 and D-124, which the source entry cites, are already folded into §4.7 and
  §5.9. `docs/DECISIONS.md` holds no live records this design touches. Do not add `D-xxx` entries for anything this
  document settles: the spec text below is approved.

### Ground rules for every phase

1. **Ordering and concurrency.** Start only once Milestone 44 (target preference, retargeting and combat memory) is
   merged: it changes `Behaviors.kt`, `BehaviorDescriptors.kt`, `Declarations.kt`, `Entities.kt` and `Views.kt`, and may
   change the shape of the behavior descriptors and `Mob`. Other milestones may also land first. Always re-read the
   current `SPEC.md`, `CHANGELOG.md`, `Behaviors.kt`, `Actions.kt`, `Declarations.kt`, `Ctx.kt`, `Views.kt` and
   `Codec.kt`, and find code by symbol name, never by the line numbers quoted here.
2. **TDD** (`AGENTS.md`): write the tests named in each phase first, run them and see them fail, then implement.
3. **Spec edits are pre-approved.** Apply the SPEC text given in each phase directly. Log a `DECISIONS.md` entry only
   for a genuinely new gap you have to fill.
4. **No spec section numbers or decision ids in code comments**, Kotlin or Starlark.
5. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`. The engine is
   unreleased: no migrations, no save-format back-compat, no deprecation shims.
6. **CHANGELOG:** add bullets to a new `### Mob leashes and bounded pursuit` subsection at the top of `## api_version 1
   (unreleased)`, above whatever subsection is first there now. Create it in the first phase that adds a bullet; later
   phases append.
7. **API files.** In any phase that changes the script API, regenerate before the phase's final `./gradlew check` if a
   golden or API test fails:
   - `./gradlew :cli:test -Dgups.updateGolden=true` (rewrites `cli/src/test/resources/api.golden.json` and
     `cli/src/test/resources/docs-golden/`).
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout (rewrites `docs/api/api-1.json`).
   - Read the diff: only the symbols the phase names may be new or changed. `ApiDiffTest` must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing entry.
8. **Gradle** runs outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation. `./gradlew check` must pass with zero warnings.

---

## Phase 1: Bounded `pursue` Search and `range`

### Objective & Scope

- **In Scope:**
  - The single bounded search that replaces `Pursue.propose`'s per-candidate `pathTo` loop.
  - The `range` argument through the Kotlin data class, the descriptor round trip and the Starlark `pursue(...)`
    builtin.
  - The dead-candidate fix.
  - The §4.7 `pursue` row.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):**
  - Any leash: no `leash` field, no restricting the search to an area. Phase 1's search takes an `allowed` parameter,
    but `Pursue` passes `null`.
  - Changes to `wander`, `follow`, `heel` or `World.think`.
  - Any change to `pathTo`, `searchPath` or `pathCache`.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Verbs.kt`: `pathTo` and `searchPath`, whose visiting order the new search must
    match.
  - `core/src/main/kotlin/gups/core/world/Behaviors.kt`: the private `others()`, `below()` and `within()` helpers.
  - `docs/SPEC.md` §4.7 (the behavior table and the **Disposition** bullet) and §5.9 **Pathfinding**.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Behaviors.kt`
  - `core/src/main/kotlin/gups/core/world/BehaviorDescriptors.kt`
  - `script/src/main/kotlin/gups/script/Declarations.kt`: only the `pursue` builtin.
  - `script/src/main/kotlin/gups/script/DocExamples.kt`: only if the docs test asks for it.
  - `core/src/test/kotlin/gups/core/world/BehaviorTest.kt`
  - `core/src/test/kotlin/gups/core/world/BehaviorDescriptorsTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the API files (ground rule 7).
- **Do Not Touch:** `Verbs.kt`, `World.kt`, `Actions.kt`, `persist/`, `plugin-api/`, `template-world/`.

### Specification & Decision Record Changes

- **§4.7 table, `pursue` row:** signature `pursue(target, range=10, disposition_below=None, priority=40)`. "What it
  does": "Heads for the nearest simulated match within `range` exits (through open doors; `None` for no limit) by path
  length, then lowest id. One search per think, in exit declaration order, so its first step is the one `path_to`
  takes."
- **§4.7, after the **Disposition** bullet,** a new bullet: "**Pursuit range:** `pursue` looks only as far as `range`.
  It asks dispositions only of candidates inside that range, at the nearest distance that has any."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
const val PURSUE_DEFAULT_RANGE = 10

data class Pursue(
  val target: ActorFilter,
  val dispositionBelow: Int? = null,
  val priority: Int = PRIORITY_PURSUE,
  /** Exits searched from the pursuer's room; null searches the whole world. */
  val range: Int? = PURSUE_DEFAULT_RANGE,
) : Behavior

/**
 * The first step toward the nearest room within [range] exits of [from] that holds an actor
 * [accept] takes, with that actor (lowest id among those at the nearest distance). Breadth-first in
 * exit declaration order through passable exits, entering only rooms in [allowed] when it is
 * non-null. [from] itself is not searched.
 */
internal fun World.nearestBy(
  from: Room,
  range: Int?,
  allowed: Set<Room>?,
  accept: (Actor) -> Boolean,
): Pair<Actor, Direction>?
```

- **Algorithmic Steps & Invariants:**
  1. `nearestBy` (in `Behaviors.kt`, next to `within`): keep `firstStep = HashMap<Room, Direction>()`, a `seen` set
     seeded with `from`, and a current-level list seeded with `[from]`. For depth `d = 1, 2, …` while the level is
     non-empty and `range == null || d <= range`:
     - Build the next level by walking the current level in order and each room's `exits.values` in order. Skip an exit
       that isn't `passable`, whose target room is missing (`roomOrNull`), already `seen`, or (with `allowed`) not in
       `allowed`. Mark it seen and record `firstStep[next] = if (room === from) exit.direction else
       firstStep.getValue(room)`.
     - Then scan the next level's rooms for actors with `accept(it)`. If any exist, return the lowest-id one with its
       room's `firstStep`.
     - Otherwise continue with the next level. Return `null` when the levels run out.
     - This is exactly `searchPath`'s FIFO discovery order, so with `allowed == null` the first step equals
       `pathTo(from, target.room)!!.first()`.
  2. `Pursue.propose`:
     - Keep the `isEngaged` and in-room early returns as they are.
     - Call `world.nearestBy(mob.room, range, null) { … }`. The accept predicate checks, in this order (cheap first,
       disposition last): `it !== mob && it.inWorld && it.condition != Condition.DEAD && target.matches(it) &&
       world.isSimulated(it) && world.below(mob, it, dispositionBelow)`.
     - Build the `Proposal` from the returned direction as today.
  3. `BehaviorDescriptors.kt`:
     - **Descriptor:** add `range` to the `pursue` entry's key set. **Always** emit the key: `put("range",
       range?.toLong())`, so a `null` (unlimited) range is written as an explicit null. Don't mirror
       `disposition_below`, which leaves the key out when it's null, because its null is its default and `range`'s
       isn't.
     - **Parse:** check presence with `"range" in d`, not `intArg`, which can't tell a missing key from an explicit
       null. A missing key gives `PURSUE_DEFAULT_RANGE`, and a key present with value `null` gives `null` (unlimited).
       Anything else must be a `Long` ≥ 1, or throw `IllegalArgumentException("range must be at least 1")`.
     - The round trip must give back an equal `Pursue`.
  4. `Declarations.kt` `pursue` builtin: add `Param(name = "range", named = true, defaultValue = "10", doc = "Exits
     searched from the mob's room; None searches the whole world.")`.
     - `None` gives `null`. A non-int or a value below 1 fails with `"pursue(range): must be at least 1"`.
     - Update the builtin's `doc` to "Behavior: heads for the nearest matching actor within range exits."
     - Pass `range` to `Pursue`.

### Test & Documentation Requirements

- `BehaviorTest`: keep `follow trails its leader and pursue paths across the world` passing (8-room chain, target 6
  away, inside the default range). Add:
  - `pursue ignores matches beyond its range`: chain of 8 rooms, `Pursue(players, range = 3)`, player 6 away. After 60
    pulses the hunter hasn't moved. With `range = 6` it reaches the player.
  - `pursue with no range crosses the world`: `range = null`, a chain longer than 10, the player at the far end, and the
    hunter arrives.
  - `pursue picks the nearest, then the lowest id`: two players at the same distance by different exits. The first step
    leads to the lower-id one. A third player who is nearer wins over both.
  - `pursue skips dead candidates`: the only player in range is `DEAD` (set the condition through the fixture's existing
    helper), and the hunter doesn't move.
  - `pursue's first step matches path_to`: a small graph with two equal-length routes. Assert the proposed direction
    equals `world.pathTo(from, target.room)!!.first()`.
  - `pursue stays local however many players are online` (the regression guard for the cost problem): a chain with a
    hunter `Pursue(players, range = 3)` and 20 players all more than 3 exits away. Clear `world.pathCache` first. After
    40 pulses, `pathCache` is still empty (the search never calls `pathTo`), and the hunter hasn't moved.
- `BehaviorDescriptorsTest`: round trip `Pursue` with the default range, `range = 3` and `range = null`. Assert the
  `range = null` descriptor holds the key `range` with a null value. A descriptor without `range` parses to range 10,
  and one with `range = 0` throws.
- `BehaviorDispositionTest` must pass unchanged. It checks only which candidate is pursued, not how often disposition is
  asked.
- CHANGELOG bullet: "`pursue` takes `range` (default 10 exits, `None` for no limit) and searches once per think instead
  of pathing to every match in the world; it no longer considers dead actors."

### Exit Conditions

- [] `./gradlew :core:test --tests '*BehaviorTest*' --tests '*BehaviorDescriptorsTest*' --tests
  '*BehaviorDispositionTest*'` passes.
- [] `./gradlew :script:test` passes.
- [] API files regenerated if needed, and the diff names only `pursue`'s `range` parameter and doc text.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 2: Leash State in Core and Persistence

### Objective & Scope

- **In Scope:**
  - `MobProto.leash`, `Mob.leashOverride`, the effective `Mob.leash`.
  - The kernel verbs `World.setLeash` and `World.resetLeash`.
  - Saving and restoring the override.
- **Out of Scope (the agent must not implement these in this phase):**
  - Any Starlark surface (`mob(leash=)`, `ctx.set_leash`, views: Phase 3).
  - Any behavior or think change (Phase 4).
  - Spec text other than the §3.4 persistence sentence.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Reconcile.kt`: how `thinkEvery` follows the prototype. The leash needs **no**
    reconcile line, because the prototype value is read live.
  - `core/src/main/kotlin/gups/core/world/Owners.kt`: `setOwner` / `clearOwner`, a kernel verb pattern that marks the
    mob `dirty`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`MobProto`)
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Mob`)
  - `core/src/main/kotlin/gups/core/world/Leash.kt` (new)
  - `persist/src/main/kotlin/gups/persist/Codec.kt` (`MobPayload`, its encoder and `applyMob`)
  - `core/src/test/kotlin/gups/core/world/LeashStateTest.kt` (new)
  - `persist/src/test/kotlin/gups/persist/CodecTest.kt` or `PersistenceTest.kt` (whichever holds mob round trips)
  - `docs/SPEC.md` (§3.4 persistence sentence only)
- **Do Not Touch:** `Behaviors.kt`, `Actions.kt`, `script/`, `plugin-api/`.

### Specification & Decision Record Changes

- **§3.4 JSON payloads, the "Mobs store …" bullet:** after "`summonedBy`, `owner`," insert "a leash override (§4.5
  `set_leash`),".

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Protos.kt, MobProto, after dormantRecovery:
/** Most exits from its home room the mob's own movement goes; null for no leash. */
val leash: Int? = null,

// Entities.kt (package gups.core.model), next to Mob:
/** A per-instance leash; [exits] null means unleashed. */
data class LeashOverride(val exits: Int?)

// Entities.kt, Mob, after dormantRecovery:
/** A script's leash override; null when the prototype's leash applies. */
var leashOverride: LeashOverride? = null

// Leash.kt
package gups.core.world

/** The mob's effective leash: its override when it has one, else its prototype's. */
val Mob.leash: Int?
  get() = leashOverride.let { if (it != null) it.exits else proto.leash }

/** Overrides [mob]'s leash; [exits] null unleashes it. */
fun World.setLeash(mob: Mob, exits: Int?)

/** Drops [mob]'s leash override so its prototype's leash applies again. */
fun World.resetLeash(mob: Mob)

// Codec.kt
@Serializable data class LeashJson(val exits: Int? = null)
// MobPayload, after dormantRecovery:
val leash: LeashJson? = null,
```

- **Algorithmic Steps & Invariants:**
  1. `setLeash` requires `exits == null || exits >= 0`, else throws `IllegalArgumentException("leash must be at least
     0")`. It sets `mob.leashOverride = LeashOverride(exits)` and `mob.dirty = true`. `resetLeash` sets it to `null` and
     marks dirty only if it was non-null. Both return `Unit`.
  2. Never write `leashOverride?.exits ?: proto.leash`: an override of `null` (unleashed) must not fall back to the
     prototype.
  3. Codec encode: `leash = m.leashOverride?.let { LeashJson(it.exits) }`. Decode in `applyMob`: `m.leashOverride =
     p.leash?.let { LeashOverride(it.exits) }`. Check how the codec's `Json` instance treats nulls (`explicitNulls`,
     `encodeDefaults`). `LeashJson(null)` must survive as a present object, so an unleashed override isn't lost.
  4. Mirror where `thinkEvery` is placed in the payload and in the KDoc listing at the top of `Codec.kt`. Add the leash
     override to that list as "stored when set".

### Test & Documentation Requirements

- `LeashStateTest` (core, mirror an existing small `Fixture` test):
  - **Defaults:** a proto without a leash gives `mob.leash == null`.
  - **Prototype value:** a proto `leash = 4` gives `4`.
  - **Override:** `setLeash(m, 2)` gives `2`, `setLeash(m, null)` gives `null` even with proto `4`, and `resetLeash(m)`
    gives `4` again.
  - **Validation:** `setLeash(m, -1)` throws.
  - **Reload:** swapping the prototype for one with `leash = 6` (use the reconcile path the existing reload tests use)
    reaches a mob without an override, and leaves a mob with `setLeash(m, 2)` at `2`.
- Persistence: round trip three mobs (no override, override `3`, override `null`). After restore, each has the same
  `leashOverride` (`null`, `LeashOverride(3)`, `LeashOverride(null)`).
- No CHANGELOG bullet yet (Phase 3 adds the script-facing one).

### Exit Conditions

- [] `./gradlew :core:test --tests '*LeashStateTest*'` and `./gradlew :persist:test` pass.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 3: Leash in Starlark (Declaration, Verbs, View)

### Objective & Scope

- **In Scope:**
  - `mob(leash=)`, `ctx.set_leash`, `ctx.reset_leash`, and the actor-view fields `leash` and `leash_overridden`.
  - The matching spec text and doc examples.
- **Out of Scope (the agent must not implement these in this phase):** any effect of the leash on behaviors or on
  `World.think` (Phase 4).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`: `setOwner` / `clearOwner`, the verb pattern (`verb(name) { … }`, the
    `ActorView` unwrap, `fail(...)`).
  - `script/src/test/kotlin/gups/script/OwnerVerbTest.kt`: the test pattern to mirror.
  - `core/src/main/kotlin/gups/core/world/Leash.kt` from Phase 2.
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Declarations.kt`: `mob(...)` parsing, its validation, and its `DeclParam` list
    (next to `think_every`).
  - `script/src/main/kotlin/gups/script/Ctx.kt`
  - `script/src/main/kotlin/gups/script/Views.kt`: next to `home_room`.
  - `script/src/main/kotlin/gups/script/DocExamples.kt`
  - `script/src/test/kotlin/gups/script/LeashVerbTest.kt` (new)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the API files.
- **Do Not Touch:** `Behaviors.kt`, `Actions.kt`, `persist/`, `plugin-api/`, `template-world/`.

### Specification & Decision Record Changes

- **§3.4 `mob(...)` example:** after `dormant_recovery="full", # …` add the line `leash=None, # most exits from home its
  own movement goes; None = no leash (§4.7)`.
- **§3.10 feature table, kernel row:** after "pet ownership (`set_owner`, `clear_owner`, `on_owner_change`)," insert
  "mob leashes (`set_leash`, `reset_leash`),".
- **§4.5 actor view, **Position**:** after `home_room` insert "`leash` (a mob's effective leash, `None` for no leash or
  for a player), `leash_overridden`".
- **§4.5 verb table:** a new row after **Pets**: `| Leash | `set_leash(mob, leash)`, `reset_leash(mob)` |` (match the
  table's column padding; `markdownFormat` realigns it).
- **§4.5 verb semantics, after the `set_owner` and `clear_owner` bullet:**

  > - **`set_leash` and `reset_leash`:** `set_leash` overrides a mob's leash (§4.7) with an int of at least 0, or with
  >   `None` to unleash it. The override is saved with the mob and outlives reloads. `reset_leash` drops the override,
  >   so the mob's prototype `leash` applies again, including a value a later reload brings. Both return `None`.

### Implementation Blueprint

- **Signatures & Types:**
  - `mob(..., leash=None)`: `leash = d.int("leash")` (an explicit `None` gives `null`, the same as omitting it). After
    the `think_every` check: `if (proto.leash != null && proto.leash < 0) fail("mob(leash): must be at least 0")`.
    `DeclParam("leash", "int | None", "Most exits from its home room the mob's own movement goes; None for no leash.",
    default = "None")`.
  - `ctx.set_leash(mob, leash)`: both params named and required (`leash` may be `None`). The body is `verb("set_leash")
    { … world.setLeash(m, n); Starlark.NONE }`. Errors:
    - `"set_leash(mob): must be a mob"`.
    - `"set_leash(leash): must be an int of at least 0 or None"`, for a non-int or a negative value.
  - `ctx.reset_leash(mob)`: `verb("reset_leash")`, the same mob check with its own message, returns `None`.
  - Views: `leash` (`structField`, doc "A mob's effective leash in exits from home; None for no leash or a player.")
    reads `(actor as? Mob)?.leash` as a Starlark int or `NONE`. `leash_overridden` (`structField`) gives `(actor as?
    Mob)?.leashOverride != null`.
- **Algorithmic Steps & Invariants:**
  1. Neither verb is gated behind a feature, and neither is limited to privileged packages, the same as `set_owner`.
  2. `DocExamples.kt`: add `"set_leash"` / `"reset_leash"` examples. One `on_hit`-style hook unleashes, and another
     resets. Mirror the `set_owner` entry.

### Test & Documentation Requirements

- `LeashVerbTest` (mirror `OwnerVerbTest`; zone commands print `ctx.here("wolf").leash` and `.leash_overridden`):
  - A wolf declared `leash = 4` prints `4 False`.
  - `set_leash(wolf, 1)` prints `1 True`, `set_leash(wolf, None)` prints `None True`, and `reset_leash(wolf)` prints `4
    False`.
  - `set_leash` on a player fails, and `set_leash(wolf, -1)` and `set_leash(wolf, "x")` fail with the messages above.
  - A player's `.leash` is `None` and `.leash_overridden` is `False`.
  - Loading `mob(..., leash = -1)` fails with `mob(leash): must be at least 0`.
- CHANGELOG bullet: "Mobs have a `leash`: `mob(leash=)` (default `None`), the `set_leash` / `reset_leash` verbs, and the
  `leash` / `leash_overridden` view fields."

### Exit Conditions

- [] `./gradlew :script:test --tests '*LeashVerbTest*'` passes.
- [] API files regenerated, and the diff names only `leash` (mob param), `set_leash`, `reset_leash`, `leash` and
  `leash_overridden`.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 4: Behaviors Respect the Leash, and the Return Home

### Objective & Scope

- **In Scope:**
  - The `homeArea` cache and home ground.
  - The leash in `wander`, `pursue` and `follow`, and `wander`'s homeward step.
  - The engine's return proposal in `World.think`, with `Mob.awaySince` tracking. It always returns: there is no rules
    gate yet.
  - §4.7 and §5.8 text.
- **Out of Scope (the agent must not implement these in this phase):**
  - The `leash_return` rules export (Phase 5). Leave one clearly named seam (`shouldReturn`, below) that returns `true`.
  - Any change to `flee`, `heel`, `defend`, `guard`, `aggressive`, `assist` or `scavenge`.
  - Owned behavior lists, script moves, movement speed, think cadence, and a plugin API.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Verbs.kt` (`pathTo`).
  - `core/src/main/kotlin/gups/core/world/Owners.kt` (`behaviorsOf`).
  - `core/src/main/kotlin/gups/core/world/Leash.kt`.
  - `docs/SPEC.md` §4.7 and §5.8.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Behaviors.kt`: `Wander`, `Pursue`, `Follow`, `PRIORITY_RETURN`.
  - `core/src/main/kotlin/gups/core/world/Leash.kt`: `homeArea`, `leashArea`, `homeGround`, `isPastLeash`,
    `returnProposal`, `shouldReturn`.
  - `core/src/main/kotlin/gups/core/model/Entities.kt`: `Mob.awaySince`.
  - `core/src/main/kotlin/gups/core/world/World.kt`: the `homeAreaCache` field, cleared in `graphChanged()`.
  - `core/src/main/kotlin/gups/core/world/Actions.kt`: `World.think`.
  - `core/src/test/kotlin/gups/core/world/LeashBehaviorTest.kt` (new)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `Verbs.kt`, `Owners.kt`, `Rules.kt`, `RulesEngine.kt`, `script/`, `persist/`, `plugin-api/`.

### Specification & Decision Record Changes

- **§4.7 table, `wander` row:** "Moves randomly within `radius` exits of the home room, counted through open doors, and
  never past the mob's leash. Outside that area it steps toward home instead. `chance` is the percent probability per
  think."
- **§4.7 table, `follow` row:** append "While its leader is outside the mob's leash it proposes nothing, and it follows
  again if the leader comes back inside it."
- **§4.7 table, `pursue` row:** append "It searches only rooms inside the mob's leash."
- **§4.7, a new bullet after **Owned mobs**:**

  > - **Leash:** a mob's `leash` (§3.4, §4.5 `set_leash`) is the most exits from its home room, through open doors, that
  >   its own movement goes. `wander`, `follow` and `pursue` stay inside it; past it, `follow` and `pursue` propose
  >   nothing. `flee`, `heel`, `defend`, owned mobs and moves a script makes ignore it. A leash of `None` (the default)
  >   never limits anything.
  > - **Going home:** a leashed mob's *home ground* is its first `wander` behavior's area, or its home room if it has no
  >   `wander`. A leashed mob with no owner that isn't engaged and stands outside its home ground gets an engine
  >   proposal at priority 20 (source `leash`): one step toward home. Whatever outranks it (fighting, `pursue`,
  >   `follow`) runs first, so a mob walks home once it has nothing left to chase. `sentinel` removes the proposal and
  >   `think` can override it. Reluctance to stray is world policy: a `think` can veto steps by distance from
  >   `home_room`.

- **§5.8 think cycle, step 1:** append "Then, for a leashed mob with no owner that isn't engaged and is outside its home
  ground, the engine's return-home proposal (§4.7 **Going home**), which sorts after the behaviors."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
const val PRIORITY_RETURN = 20

// Entities.kt, Mob, next to leashOverride:
/** The pulse since which the mob has been eligible to go home, or null. Not saved. */
var awaySince: Long? = null

// World.kt, next to pathCache:
internal val homeAreaCache = HashMap<Pair<EntityId, Int>, Set<Room>>()
// graphChanged() also calls homeAreaCache.clear()

// Leash.kt
/** Rooms within [radius] exits of [home] through open doors, cached until the graph changes. */
internal fun World.homeArea(home: Room, radius: Int): Set<Room>

/** The rooms [mob]'s leash allows, or null when it is unleashed, owned, or its home is gone. */
internal fun World.leashArea(mob: Mob): Set<Room>?

/** Where a leashed mob belongs: its first wander's area, else its home room. Null when leashArea is. */
internal fun World.homeGround(mob: Mob): Set<Room>?

internal fun World.isPastLeash(mob: Mob): Boolean // leashArea(mob)?.let { mob.room !in it } ?: false

/** Whether a mob that may go home does so this think. Phase 5 asks the rules here. */
internal fun World.shouldReturn(mob: Mob, away: Long, pastLeash: Boolean): Boolean = true

/** The engine's step toward home, or null; also keeps [Mob.awaySince] current. */
internal fun World.returnProposal(mob: Mob): Proposal?
```

- **Algorithmic Steps & Invariants:**
  1. `homeArea`: `homeAreaCache.getOrPut(home.id to radius) { within(home, radius) }`.
  2. `leashArea(mob)`: `null` if `mob.owner != null`, if `mob.leash == null`, or if `roomOrNull(mob.homeRoom) == null`.
     Otherwise `homeArea(home, mob.leash!!)`.
  3. `homeGround(mob)`: `null` if `leashArea(mob)` is null. Otherwise, let `w = mob.proto.behaviors.firstOrNull { it is
     Wander } as Wander?`. Return `homeArea(home, minOf(w.radius, mob.leash!!))` when `w != null`, else `setOf(home)`.
     Use the prototype list deliberately: owned mobs already returned `null`.
  4. `returnProposal(mob)`:
     - Let `ground = homeGround(mob)`. If `ground == null || mob.isEngaged || mob.room in ground`, set `mob.awaySince =
       null` and return `null`.
     - Otherwise set `mob.awaySince = mob.awaySince ?: pulse`.
     - If `!shouldReturn(mob, pulse - mob.awaySince!!, isPastLeash(mob))`, return `null`. **Keep** `awaySince`: a held
       return keeps counting.
     - Then `val step = pathTo(mob.room, home)?.firstOrNull() ?: return null` and `Proposal(step.name, PRIORITY_RETURN,
       "leash", MoveAction(step))`.
     - A mob with the conditions holding on its first think has `away == 0`.
  5. `World.think` (`Actions.kt`): after building the `(index, proposal)` pairs from behaviors, and **before** the
     `Sentinel` filter, append `returnProposal(mob)?.let { behaviors.size to it }`. Wrap it in the same `try` / diagnose
     handling the behaviors use, so an exception is logged as an engine diagnostic and proposes nothing. The existing
     sort then puts it after equal-priority behaviors. It runs for every thinking mob, so `awaySince` is cleared as soon
     as a mob is home, engaged or owned.
  6. `Wander.propose`:
     - `val home = world.roomOrNull(mob.homeRoom) ?: mob.room`.
     - `val r = world.leashArea(mob)?.let { minOf(radius, mob.leash!!) } ?: radius`.
     - `val area = world.homeArea(home, r)`, which replaces the uncached `within` call.
     - Keep the `isEngaged` and `chance` checks first, in their current order, so the RNG draws stay the same.
     - If `mob.room !in area`: step to `world.pathTo(mob.room, home)?.firstOrNull()` with source `"wander"` at wander's
       priority, or return `null` if there's no path.
     - Otherwise, choose from exits as today, filtering by `area`.
     - **Keep:** with no leash and the mob inside its radius, the choices and RNG draws must equal today's (`wander
       stays within its radius of home` and `wander with chance 0 never steps and draws nothing` must still pass
       unchanged).
  7. `Pursue.propose`:
     - `val area = world.leashArea(mob)`.
     - If `area != null && mob.room !in area`, return `null`.
     - Pass `area` as `nearestBy`'s `allowed`.
  8. `Follow.propose`:
     - After the leader checks and before pathing: if `world.isPastLeash(mob)`, return `null` (keep `mob.leader`).
     - Before pathing, let `area = world.leashArea(mob)`. If `area != null && leader.room !in area`, return `null` (keep
       the leader). Checking the leader's room, not just the next step, keeps the mob from bouncing between following
       out and the return proposal stepping back.
     - The existing `BUBBLE_RADIUS` give-up rule is unchanged.
  9. `Heel`, `Defend`, `Flee` and owned lists are untouched. `leashArea` already returns `null` for owned mobs, so
     nothing changes for pets.

### Test & Documentation Requirements

- `LeashBehaviorTest` (core; use the `C.protos(n = …)` chain from `BehaviorTest`, home at `C.r(0)`):
  - **Pursue in leash:** `Pursue(players)` with proto `leash = 3` and a player at `C.r(6)`. The hunter never goes past
    `C.r(3)`. A player at `C.r(2)` is reached.
  - **All the way home (the temple-guardian case):** `Pursue(players)`, `leash = 3`, no `wander`. A player draws the
    hunter to `C.r(3)`, then moves to `C.r(7)`. The hunter walks back to `C.r(0)` and stays there.
  - **Home ground with wander:** `leash = 4`, `Wander(radius = 1, chance = 0)`, placed at `C.r(4)`. The mob walks back
    to `C.r(1)` and stops: it's then inside its home ground, and the return proposal is gone.
  - **Chase outranks return:** a hunter outside its home ground with a player inside its leash and range pursues the
    player and doesn't step home.
  - **Return with `sentinel`:** a mob with `Sentinel` placed at `C.r(5)` stays there.
  - **Engaged:** with `combat`, an engaged mob outside its home ground gets no return proposal, and `awaySince` is null.
    Assert through a `think` function recording `proposals`, mirroring `think sees proposals sorted by priority and can
    override them`.
  - **Proposal order:** a mob outside its home ground with `Wander(3, 100)` and a `think` recorder sees the `"leash"`
    proposal at priority 20 first and `"wander"` second, both toward home.
  - **Wander:** with `leash = 1` and `Wander(5, 100)`, it never leaves `within(home, 1)`. With no leash, a mob placed
    outside its radius steps back toward home and never gets a `"leash"` proposal.
  - **Follow:** with `leash = 2`, the mob follows a player to `C.r(2)`. When the player steps to `C.r(3)` (past the
    leash, still within 3 exits), the mob walks back to `C.r(0)` without bouncing and keeps `leader`. When the player
    returns to `C.r(1)`, it follows again.
  - **Override and reset:** `setLeash(m, null)` lets a pursuer cross the chain with no `"leash"` proposal. After
    `resetLeash(m)` (proto `leash = 2`) it walks all the way to `C.r(0)`.
  - **Owned mobs:** a mob with `leash = 1` given an owner follows its owner past the leash (heel) and gets no `"leash"`
    proposal.
  - **`awaySince`:** set on the first think outside the home ground, unchanged on later thinks, null again once home.
  - **Cache:** `setDoor` closing a door on the chain changes `homeArea` (the cache is cleared).
- Existing `BehaviorTest`, `OwnedBehaviorsTest` and `BehaviorDispositionTest` pass unchanged.
- CHANGELOG bullets:
  - "`wander`, `follow` and `pursue` stay within a mob's leash."
  - "A leashed mob outside its home ground walks all the way home once nothing outranks it (an engine proposal at
    priority 20, source `leash`)."
  - "`wander` outside its radius now heads home instead of standing still."

### Exit Conditions

- [] `./gradlew :core:test --tests '*LeashBehaviorTest*' --tests '*BehaviorTest*' --tests '*OwnedBehaviorsTest*'`
  passes.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.
- [] §4.7 and §5.8 describe exactly what the code does. `grep -n "leash" docs/SPEC.md` shows §3.4, §3.10, §4.5, §4.7 and
  §5.8.

---

## Phase 5: The `leash_return` Rules Export

### Objective & Scope

- **In Scope:**
  - The optional kernel export `leash_return(ctx) -> bool`, its two rules-ctx fields `away` and `past_leash`, and its
    call from `shouldReturn`.
  - The §4.6 text.
  - Script tests.
- **Out of Scope (the agent must not implement these in this phase):**
  - A modifier point for `leash_return`.
  - A template-world or sample-world implementation of the export (worlds opt in; the template defines none).
  - Any change to the return conditions or priorities from Phase 4.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Owners.kt`: how `rules.ownedBehaviors(c)` is called and a `null` result falls
    back.
  - `script/src/main/kotlin/gups/script/Rules.kt`, to mirror the optional export `owned_behaviors` end to end:
    - `override fun ownedBehaviors` (the `defines(...)` guard and `run(...)`).
    - `RulesExport.OWNED_BEHAVIORS`.
    - Its `RulesExportSpec` entry (`hasDefault = true`).
    - The `behaviors` field on the rules `ctx` view.
    - The load-time dry run that calls it when defined.
  - `docs/SPEC.md` §4.6: the optional-exports sentence, the rules `ctx` field list and the export catalog table.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt`:
    - `RulesContext.away: Long? = null` and `RulesContext.pastLeash: Boolean? = null`, with KDoc in the style of
      `behaviors`.
    - `fun leashReturn(c: RulesContext): Boolean? = null`.
    - Keep `withDamageType` and any other copy helper complete, so the new fields are carried over.
  - `core/src/main/kotlin/gups/core/world/Leash.kt`: `shouldReturn`.
  - `script/src/main/kotlin/gups/script/Rules.kt`: the export enum, spec, override, ctx fields and dry run.
  - `script/src/main/kotlin/gups/script/DocExamples.kt`, if the docs test asks for it.
  - `script/src/test/kotlin/gups/script/LeashReturnRulesTest.kt` (new)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the API files.
- **Do Not Touch:** `Behaviors.kt`, `Actions.kt`, `persist/`, `plugin-api/`, `template-world/`.

### Specification & Decision Record Changes

- **§4.6 optional exports sentence:** "Five exports are optional: `xp_share`, …, `owned_behaviors`, whose default is
  described in §4.7, and `factions`, …" becomes "Six exports are optional: `xp_share`, …, `owned_behaviors` and
  `leash_return`, whose defaults are described in §4.7, and `factions`, …". Recount the list as it stands when you edit
  it, since another milestone may have changed it.
- **§4.6 rules `ctx` fields:** after `behaviors (…)` add "`away` and `past_leash` (in `leash_return`: pulses since the
  mob became eligible to go home, and whether it is past its leash; `None` elsewhere),".
- **§4.6 catalog table:** a row after `owned_behaviors`: `| leash_return(ctx) | kernel | True to send ctx.actor, a
  leashed mob outside its home ground, home this think (§4.7 **Going home**); False to hold it there. |` (match the
  table's padding; `markdownFormat` realigns it).
- **§4.6, after the **`owned_behaviors`** paragraph:**

  > **`leash_return`**: asked once per think for a mob that could go home (§4.7 **Going home**), before the engine adds
  > its return proposal. `ctx.away` counts pulses since the mob became eligible, without a break (fighting, getting an
  > owner, or reaching its home ground resets it; holding it back with `False` doesn't). `ctx.past_leash` tells a mob
  > past its leash from one only outside its home ground. Without the export, mobs go home at once. A world makes a mob
  > linger (`return ctx.away >= 40`), hold a post longer, or never go back, by reading its label, flags, `attrs` or
  > affects. A failing call counts as `True`, so a broken rule can't strand mobs.

- **§4.7 **Going home** bullet (added in Phase 4):** append "The world's `rules.leash_return` (§4.6) can hold the step
  back."

### Implementation Blueprint

- **Signatures & Types:**
  - `RulesEngine.leashReturn(c: RulesContext): Boolean? = null`. `null` means the default, which is `true`.
  - `RulesExport.LEASH_RETURN("leash_return")`.
  - `RulesExportSpec(export = RulesExport.LEASH_RETURN, signature = "leash_return(ctx)", returnType = "bool", ctxFields
    = listOf("actor", "away", "past_leash", "room", "pulse", "rng"), doc = "Whether a leashed mob outside its home
    ground heads home this think.", hasDefault = true)`.
  - Rules `ctx` view: `away` (int or `None`) and `past_leash` (bool or `None`), mirroring the `behaviors` field.
- **Algorithmic Steps & Invariants:**
  1. `shouldReturn(mob, away, pastLeash)`: `rules.leashReturn(RulesContext(this, mob, null, away = away, pastLeash =
     pastLeash)) ?: true`. Construct the context the way `Owners.kt` does for `ownedBehaviors`.
  2. Script override: `if (!defines(RulesExport.LEASH_RETURN)) return null`, then `run(...)`. The result must be a
     Starlark bool, else fail with `"rules.leash_return return value: must be a bool, got <type>"`. Mirror the error
     style of `ownedBehaviors`.
  3. **Failure:** a failure inside the export must not strand mobs. Wherever `run` already turns a failing export into a
     diagnostic and a fallback, make the fallback for this export `null` (so the default `true` applies). If `run`
     rethrows instead, catch in `shouldReturn`, `diagnose("rules", …)`, and return `true`. Check which one applies, and
     test it.
  4. **Load-time dry run:** when the export is defined, call it once with the first available mob, `away = 0` and
     `pastLeash = false`, next to the `owned_behaviors` dry run.
  5. It is traced like other rules calls (`@trace rules`): no extra work if `run` already records it.

### Test & Documentation Requirements

- `LeashReturnRulesTest` (script; a zone with a chain of rooms and a leashed `pursue` mob, rules defining
  `leash_return`):
  - **Linger:** with `def leash_return(ctx): return ctx.away >= 20`, the mob waits about 20 pulses outside its home
    ground (assert it hasn't moved after 12 pulses), then walks home.
  - **Never return:** with `return False`, the mob stays put indefinitely.
  - **Per-mob policy:** with `return not ctx.actor.has_flag("stubborn")`, a flagged mob stays, and an unflagged twin
    goes home.
  - **`past_leash`:** the export records `ctx.past_leash` (`True` for a mob placed past its leash, `False` for one only
    outside its home ground).
  - **Default:** a world without the export sends the mob home at once, the same as Phase 4.
  - **Bad return value:** `return 3` fails with the bool message, and the mob still goes home.
  - **Error:** `fail("x")` inside the export is diagnosed, and the mob still goes home.
- CHANGELOG bullet: "New optional rules export `leash_return(ctx)` decides when a leashed mob heads home (`ctx.away`,
  `ctx.past_leash`)."

### Exit Conditions

- [] `./gradlew :script:test --tests '*LeashReturnRulesTest*'` passes.
- [] API files regenerated, and the diff names only `leash_return`, `away` and `past_leash`.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 6: Finalization & Backlog Update

- **End-to-end check** (`AGENTS.md` **End-to-end checks**):
  - Copy the template world into `local-world-test/world`. Give one tutorial mob `leash = 2` and `pursue("players")`, no
    `wander`. Write a `local-world-test/scenario.star` that walks a player away from it, past its leash and then out of
    its range.
  - Assert that the mob stops 2 exits from home while the player is past the leash, then walks all the way back to its
    home room.
  - Repeat with a rules file defining `leash_return` as `return ctx.away >= 20`, and assert the mob lingers before
    walking home.
  - Delete `local-world-test/` afterwards.
- **Backlog:** in `docs/DESIGN_NEEDED.md`, under `### Cost of `pursue` and other world-wide searches`, add as the first
  line of the entry body: "**Status:** completed in Milestone 45 (pending designer review)." Do not delete the entry. Do
  not touch the "Movement speed and pursuit catch-up" entry.
- **Final pass:**
  - Confirm `docs/CHANGELOG.md`'s `### Mob leashes and bounded pursuit` subsection holds the Phase 1, 3, 4 and 5
    bullets.
  - Run `./gradlew markdownFormat`, then `./gradlew check`. Both must pass with zero warnings.
