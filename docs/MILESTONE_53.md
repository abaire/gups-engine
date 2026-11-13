# Milestone 53: Plugin Facade Parity with `ctx`

**Prerequisites:** [M52](MILESTONE_52.md) must be finished first. Where line numbers below are wrong, find the symbol by
name.

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** [DESIGN_NEEDED.md](DESIGN_NEEDED.md): `## Plugins` → ``### The rest of the `ctx` verbs on
  the plugin facade``. Two parts of that entry were split into their own entries and are **not** part of this milestone:
  ``### Plugin timers (`after` and `cancel` on the facade)`` and `### Track positions on the plugin facade`. A related
  gap, `## Mobs` → `### Removing a mob without killing it`, is also its own entry.

- **Design Summary:**

  - **The rule: a facade verb behaves like its `ctx` counterpart.** The facade already follows it (`spawnItem` and
    `moveItem` skip restrictions because `ctx.spawn` and `ctx.move_item` do). Every open question in the source entry is
    answered by applying it:
    - Door and container verbs call `setDoor` / `setContainerState` directly, as `CtxDoors` does: no `before_open`, no
      key named by the plugin, print nothing; locking a door or container without a `key` fails.
    - `spawnMob` makes plain mobs, as `ctx.spawn` does for a mob label: no owner, no leash override, no `spawnedBy`, a
      home room equal to the room it spawned in, and an `on_spawn` that sees only `ctx.room`.
    - `startCombat(a, b)` takes two actors; a plugin is never the attacker. Engagement and contributions follow
      `World.startCombat`.
    - `say` and `emote` speak as a named actor and run at once, like `World.say`/`World.emote`. Only the mob actions
      (`act`, `actMove`, `actAttack`, `actCast`, `moveToward`) queue tick-lane actions.
    - Summon, ownership, leash, group, simulation and standing verbs are facade verbs, mirroring §4.5.
  - **Reads come with the verbs.** Every field of the `ctx` actor, room, item, exit, door, group and rng views gets a
    facade counterpart, so a plugin can check state before it acts (which exits have doors, whether a mob is owned, whom
    an actor fights).
  - **Parity is enforced.** §4.15 gains a "Parity with `ctx`" paragraph (approved wording below), `AGENTS.md` gains one
    line, and a new test, `CtxParityTest` in `:plugin-api`, fails when a `ctx` verb or view field has neither a facade
    counterpart nor a recorded exception with a reason. The exceptions left at the end of the milestone are principled
    (hook-relative, spelled differently, by design) or point at the two split-out `DESIGN_NEEDED` entries or
    `ICEBOX.md`.
  - **Not in scope:** plugin timers, track positions (`index` arguments, `trackIndex`, `bandOf`, `trackSize`, item
    `range`, exit `entryIndex`, `actAdvance`, `actRetreat`), `callHook`, removing mobs, and enforcing "read-only" on the
    worlds that owned-behavior, target-order and chained-think handlers get (today that is a documented convention, and
    the new verbs follow the same convention with no new check).
  - **The weather sample** demonstrates the new verbs: when a storm starts in an outdoor room, the plugin slams and
    locks each keyed door of that room, and unlocks the doors it locked once the storm passes.
- **Specification Impact:** §4.15 (the Facade bullet's verb, query and view lists; the new "Parity with `ctx`"
  paragraph; the **Weather Plugin** sample bullet), plus `AGENTS.md`, `docs/PLUGINS.md` (`## What plugins can touch`),
  `docs/CHANGELOG.md`, and `samples/weather-plugin/README.md`.
- **Decision Record Updates:** None. `docs/DECISIONS.md` has no entry about the facade, `spawnItem` or custody verbs.

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first, run them and watch them fail, then write the code.
   - No spec section numbers or decision ids in comments. Comments explain why, not what.
   - All Kotlin warnings are errors. Identifiers say **skill**, never **spell**.
   - After changing code, run the CodeScene `code_health_review` on the changed files and fix any drop in Code Health;
     run `pre_commit_code_health_safeguard` before finishing a phase. Facade verbs whose parameter lists are fixed by
     this milestone may exceed CodeScene's argument-count limit; that finding is accepted.
2. Do not raise `PluginApi.VERSION` or `EngineInfo.API_VERSION`. The engine is unreleased.
3. **Every new member of a public plugin interface** (`PluginGmcpWorld`, `PluginWorld`, `PluginRoom`, `PluginActor`,
   `PluginItem`, `PluginRng`) gets a default body that throws, so the samples' test fakes keep compiling, mirroring
   `stopTask` at `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt:308`: `= throw
   UnsupportedOperationException("<name> is not supported")`. For a property: `get() = throw
   UnsupportedOperationException("<name> is not supported")`. The engine's implementation
   (`plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`) overrides every one.
4. **Engine-side pattern.** Each verb converts its arguments with the existing helpers in `WorldFacade` (`actorOf`,
   `roomOf`, `itemOf`, `entityOf`, `declared`, `needs`), throws `IllegalArgumentException` (`require`) for bad arguments
   with a message that starts with the facade name (`"openDoor(dir): ..."`), throws `IllegalStateException` via
   `needs(world, Feature.X, "<name>")` when a feature is off, and then calls the same `World` function the `ctx` verb
   calls. Never call into `gups.script.Ctx*`; read those files only to copy the behavior. Add small private helpers
   `mobOf(a, what): Mob` ("must be a mob") and `playerOf(a, what): Player` ("must be a player") next to `actorOf`.
5. **Feature gates match `ctx`.** Where the `ctx` verb or view field is gated (a `read("actor.casting")` style call in
   `script/src/main/kotlin/gups/script/Views.kt`, or the §4.5 **Features** bullet at `docs/SPEC.md` ~1517), the facade
   member calls `needs(...)` with the same feature. `startCombat`, `stopCombat`, `enemiesNear`, `actAttack` need
   `combat`; `actCast`, `isCasting`, `casting` need `skills`; `xp` needs `leveling`.
6. **Where members go.** Reads that change nothing and draw no randomness go on `PluginGmcpWorld` (so GMCP input
   handlers can use them; `GmcpWorldFacade` delegates to `WorldFacade`); verbs and `roll` go on `PluginWorld`. Entity
   reads go on the views.
7. **`CtxParityTest`** (created in Phase 1) has a `PENDING` map of gaps this milestone closes. Each phase removes the
   entries it closes; the test fails on a stale entry, so it tells you when you forgot.
8. CHANGELOG: Phase 1 adds `### Plugin facade parity with ctx` at the top of `## api_version 1 (unreleased)` in
   `docs/CHANGELOG.md`, above `### Respec shrines`. Each later phase appends its bullets.
9. In **every** phase, regenerate the API files before the phase's final `./gradlew check` (an empty diff is fine):
   - `./gradlew :cli:test -Dgups.updateGolden=true` rewrites `cli/src/test/resources/api.golden.json` and
     `cli/src/test/resources/docs-golden/`.
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` rewrites
     `docs/api/api-1.json`.
   - Read the diff: only what the phase names may change; `ApiDiffTest` must then pass.
10. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
    blocks kept their indentation.
11. Never read or touch `local-world/`. Scratch files go in `local-world-test/`, which you delete when done.
12. Messages given in quotes below are exact: tests assert them.

---

## Phase 1: The parity rule and `CtxParityTest`

### Objective & Scope

- **In Scope:** `CtxParityTest` with its `EXCEPTIONS` and `PENDING` maps; the §4.15 parity paragraph; the `AGENTS.md`
  line; the CHANGELOG subsection.
- **Out of Scope:** *Do not implement these; later phases do.* Any new facade member. Any change to `ctx`.

### Context Working Set

- **Read / Reference Only:** `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt` (all interfaces);
  `script/src/main/kotlin/gups/script/Ctx.kt` and `Ctx*.kt` (class hierarchy; `CtxFields.kt` holds the hook's context
  fields); `script/src/main/kotlin/gups/script/Views.kt` (`EntityView` ~98, `ActorView` ~140, `RoomView` ~495,
  `ItemView` ~719, `RngView` ~1264); `plugin-api/src/test/kotlin/gups/pluginapi/ApiSurfaceTest.kt` (reflection style).
- **Files to Modify / Create:** create `plugin-api/src/test/kotlin/gups/pluginapi/CtxParityTest.kt`; modify
  `docs/SPEC.md`, `AGENTS.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** any main source file, `samples/`, `docs/DESIGN_NEEDED.md`.

### Specification & Decision Record Changes

- §4.15: insert this paragraph (approved wording) as a standalone paragraph, not a list item: after the "It consists of
  three primary components:" list (whose last item is the ``- **The Facade (`PluginWorld` and `PluginEntity`):**``
  bullet) and before `**Plugin capabilities and lifecycle:**`, with a blank line on each side:

  > **Parity with `ctx`:** every `ctx` verb and every field of the actor, item and room views has a facade counterpart
  > that behaves the same way, named in camelCase (`open_door` → `openDoor`, `home_room` → `homeRoom`). The exceptions
  > are the hook's context fields (`self`, `target`, `args`, …), verbs that only work inside a hook (`deny`), and gaps
  > recorded for later. A change that adds a `ctx` verb or view field adds its facade counterpart in the same change, or
  > records why there is none. A test in `:plugin-api` lists every `ctx` verb and view field and fails on any that has
  > neither a counterpart nor a recorded exception.

- `AGENTS.md`, under `## Writing code`, add this bullet after the TDD bullet (approved wording):

  > - **Plugin facade parity:** when you add or change a `ctx` verb or a view field, add or change its `PluginWorld` or
  >   view counterpart in the same change, or add it with a reason to the exception list in `CtxParityTest`. `./gradlew
  >   check` fails otherwise.

### Implementation Blueprint

#### Signatures & Types

```kotlin
package gups.pluginapi

class CtxParityTest {
  /** A ctx-side Starlark class, the facade types that must cover it, and the key prefix for exceptions. */
  private data class Coverage(val prefix: String, val ctx: Class<*>, val facade: List<Class<*>>)

  private val pairs: List<Coverage> = listOf(
    Coverage("ctx", gups.script.Ctx::class.java, listOf(PluginWorld::class.java, PluginGmcpWorld::class.java)),
    Coverage("actor", gups.script.ActorView::class.java, listOf(PluginActor::class.java)),
    Coverage("room", gups.script.RoomView::class.java, listOf(PluginRoom::class.java)),
    Coverage("item", gups.script.ItemView::class.java, listOf(PluginItem::class.java)),
    Coverage("rng", gups.script.RngView::class.java, listOf(PluginRng::class.java)),
  )

  /** Permanent exceptions: "<prefix>.<starlark name>" to the reason. */
  private val EXCEPTIONS: Map<String, String>

  /** Gaps Milestone 53 closes; each phase removes its entries. Empty and deleted in the last phase. */
  private val PENDING: Map<String, String>

  @Test fun `every ctx verb and view field has a facade counterpart or a recorded exception`()
  @Test fun `no exception names a field that has a counterpart or no longer exists`()
}
```

If `RngView`'s or `ItemView`'s constructor or class isn't public, reflect over them by name with `Class.forName`.

#### Algorithmic Steps & Invariants

1. **Starlark names of a class:** every method in the class and its superclasses carrying
   `net.starlark.java.annot.StarlarkMethod`, taking `annotation.name`. For `Ctx`, drop methods whose declaring class is
   `CtxFields` (the hook's context fields).
2. **Facade names:** every public method name of each facade interface and its superinterfaces, normalized: `getFoo` →
   `foo`, `isFoo` kept as `isFoo`, and Kotlin's `$default` bridges ignored.
3. **Match:** a Starlark name `a_b_c` matches when `aBC` (camelCase) is a facade name. `is_player` → `isPlayer` and
   `in_world` → `inWorld` follow the same rule.
4. **Test 1:** collect every `"<prefix>.<name>"` with no match that is in neither map; fail listing them all, one per
   line, with the hint "add a facade counterpart or an entry in EXCEPTIONS with a reason".
5. **Test 2:** fail on any key in either map that now matches or whose name no longer exists on the ctx class, and on a
   key in both maps.
6. **`EXCEPTIONS`** (exact keys; reasons may be reworded but must say why):

   |                                  Key                                  |                                  Reason                                  |
   |-----------------------------------------------------------------------|--------------------------------------------------------------------------|
   | `ctx.deny`                                                            | only valid as a `before_*` hook's return value                           |
   | `ctx.here`, `ctx.find`                                                | relative to the hook's owner; plugins read `room.actors`/`items`/`rooms` |
   | `ctx.grant_item`                                                      | by design: `spawnItem`/`moveItem` with an actor destination              |
   | `ctx.call_hook`                                                       | ICEBOX.md "Plugins calling custom hooks"                                 |
   | `ctx.after`, `ctx.cancel`                                             | DESIGN_NEEDED "Plugin timers"                                            |
   | `ctx.act_advance`, `ctx.act_retreat`                                  | DESIGN_NEEDED "Track positions on the plugin facade"                     |
   | `actor.track_index`, `actor.band_of`, `room.track_size`, `item.range` | DESIGN_NEEDED "Track positions on the plugin facade"                     |
   | `ctx.has_feature`                                                     | spelled `features`                                                       |
   | `ctx.skill_info`                                                      | spelled `skill(label)`                                                   |
   | `ctx.standing_event`                                                  | spelled `raiseStandingEvent`                                             |
   | `actor.attrs`, `room.attrs`, `item.attrs`                             | spelled `getAttr`                                                        |
   | `actor.base`                                                          | spelled `baseStat`, `baseMaxVital`, `baseResist`                         |
   | `actor.resists`                                                       | spelled `resist(type)`                                                   |
   | `actor.equipped`                                                      | spelled `equipment[slot]`                                                |

7. **`PENDING`** (exact keys, reason `"Milestone 53"`): `ctx.` + each of `act`, `act_attack`, `act_cast`, `act_move`,
   `allies_near`, `clear_active`, `clear_anchor`, `clear_contributions`, `clear_owner`, `clear_standing`,
   `close_container`, `close_door`, `emit`, `emote`, `enemies_near`, `group_add`, `group_disband`, `group_members`,
   `group_of`, `group_remove`, `group_set_leader`, `lock_container`, `lock_door`, `move_toward`, `open_container`,
   `open_door`, `path_to`, `plan`, `reset_leash`, `roll`, `say`, `set_active`, `set_anchor`, `set_leash`, `set_owner`,
   `spawn`, `standing_history`, `start_combat`, `stop_combat`, `summon`, `unlock_container`, `unlock_door`; `actor.` +
   each of `below_zero_since`, `carried_weight`, `casting`, `condition`, `encumbrance_level`, `fighting`, `has_flag`,
   `has_item`, `home_room`, `in_world`, `is_casting`, `keywords`, `leash`, `leash_overridden`, `move_pace`, `owner`,
   `primary_target`, `recall_room`, `senses`, `slots`, `wait_state`, `xp`; `room.` + each of `affects`, `desc`, `exit`;
   `item.` + each of `container_state`, `damage_type`, `dice`, `in_world`, `sense_deltas`, `stat_deltas`; `rng.choice`.

8. If Test 1 reports a name not in either list above, it is a gap this plan missed: stop and report it rather than
   guessing a category.

### Test & Documentation Requirements

- The two tests above, passing with the maps as given. Check that they bite: temporarily delete `ctx.say` from `PENDING`
  and confirm Test 1 fails naming it; temporarily add `ctx.send` to `EXCEPTIONS` and confirm Test 2 fails. Restore both.
- CHANGELOG: create `### Plugin facade parity with ctx` with the bullet "The plugin facade now follows a parity rule:
  every `ctx` verb and view field has a facade counterpart or a recorded reason why not, checked by `CtxParityTest`."

### Exit Conditions

- [] `./gradlew :plugin-api:test --tests gups.pluginapi.CtxParityTest` passes.
- [] `./gradlew check` passes outside the sandbox; API files regenerated (empty diff expected).
- [] §4.15 and `AGENTS.md` carry the approved text verbatim.

---

## Phase 2: Doors, containers, exits

### Objective & Scope

- **In Scope:** the eight door and container verbs; `PluginRoom.exit(dir)` with `PluginExit` and `PluginDoor`;
  `PluginItem.containerState`; parity pairs for the exit and door views.
- **Out of Scope:** *Do not implement these; later phases do.* Any other verb or read; the weather sample.

### Context Working Set

- **Read / Reference Only:** `script/src/main/kotlin/gups/script/CtxDoors.kt` (whole file);
  `script/src/main/kotlin/gups/script/Views.kt` (`DoorView` ~553, `ExitView` ~652, `ItemView.container_state`);
  `core/src/main/kotlin/gups/core/world/Verbs.kt` (`setDoor` ~66, `setContainerState` ~487); `docs/SPEC.md` §4.5
  **Doors** and **Containers** (~1590).
- **Files to Modify / Create:** `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt`,
  `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`, create
  `plugin-api/src/test/kotlin/gups/pluginapi/FacadeDoorsTest.kt`, `CtxParityTest.kt`, `ApiSurfaceTest.kt` (add the new
  types), `docs/SPEC.md`, `docs/PLUGINS.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `script/`, `core/`, `samples/`.

### Specification & Decision Record Changes

- §4.15 Facade bullet: in the verb list, after `` `levelDown` `` and before `` `rules` ``, insert `` `openDoor`,
  `closeDoor`, `lockDoor`, `unlockDoor`, `openContainer`, `closeContainer`, `lockContainer`, `unlockContainer` ``. In
  the views list, after `` `PluginSkill` `` add `` `PluginExit`, `PluginDoor` ``. Add the sentence (approved wording):
  "The door and container verbs follow `ctx` (§4.5): they run no hook, take no key and print nothing, and locking fails
  on a door or container without a `key`."

### Implementation Blueprint

#### Signatures & Types

```kotlin
// PluginWorld
fun openDoor(room: PluginRoom, dir: String)
fun closeDoor(room: PluginRoom, dir: String)
fun lockDoor(room: PluginRoom, dir: String)
fun unlockDoor(room: PluginRoom, dir: String)
fun openContainer(item: PluginItem)
fun closeContainer(item: PluginItem)
fun lockContainer(item: PluginItem)
fun unlockContainer(item: PluginItem)

// PluginRoom
/** The exit in direction [dir], or null when there is none. */
fun exit(dir: String): PluginExit?

// PluginItem
/** "open", "closed" or "locked"; null for an item that isn't a container. */
val containerState: String?

/** An exit as a plugin sees it; read when the view is taken. */
data class PluginExit(
  val direction: String,
  val to: String,
  val tags: List<String>,
  val door: PluginDoor?,
  val key: String?,
  val passable: Boolean,
  val oneWay: Boolean,
)

data class PluginDoor(val state: String, val key: String?, val tags: List<String>)
```

`PluginExit` and `PluginDoor` are snapshots (data classes in `gups.pluginapi`), like `PluginItemAffect`; read the door
state again after changing it.

#### Algorithmic Steps & Invariants

1. Door verbs: `roomOf(room, "openDoor(room)")`; parse `dir` with `Direction.parse` (an unknown direction throws
   `IllegalArgumentException` naming `openDoor(dir)`); `room.exits[d]?.door` or throw `"openDoor(): <label> has no door

   <dir>"`; compute the new state exactly as `CtxDoors` does (`closeDoor` keeps a locked door locked, `unlockDoor`
   keeps an open door open) and call `world.setDoor`. `setDoor`'s own `require` for a lock without a key propagates.

2. Container verbs: `itemOf(item, ...)`, require `container != null` (`"openContainer(item): <name> is not a
   container"`), the same state mapping as `CtxDoors.container`, then `world.setContainerState`.

3. `RoomFacade.exit(dir)`: parse the direction (throw on a malformed name); return null when the room has no such exit;
   otherwise fill `PluginExit` from the core `Exit` the way `ExitView` reads it (tags sorted, door tags sorted, door
   state lowercase).

4. `ItemFacade.containerState`: `item.container?.state?.name?.lowercase()`.

5. Parity: add `Coverage("exit", ExitView, [PluginExit])` and `Coverage("door", DoorView, [PluginDoor])`. Add
   `"exit.entry_index"` to `EXCEPTIONS` (track entry). Remove from `PENDING`: the eight `ctx.*_door` / `*_container`
   keys, `room.exit`, `item.container_state`.

### Test & Documentation Requirements

- `FacadeDoorsTest.kt`, using `world(files = ...)` from `Fixture.kt` with a zone whose rooms `a` and `b` share a keyed
  door (`door = door(state = DOOR.closed, key = ":key")`) and a second unkeyed door, plus a keyed chest item and an
  unkeyed box. Tests:
  - `lockDoor` locks both sides (check `room("…:b").exit("south")!!.door!!.state == "locked"`), `unlockDoor` leaves it
    `"closed"`, `openDoor` on a locked door opens it, `closeDoor` keeps a locked door locked.
  - `lockDoor` on the unkeyed door throws `IllegalArgumentException`; a direction with no door throws with `"has no
    door"`; `exit("up")` is null.
  - No hook runs: give the room a `before_open` that denies; `openDoor` still opens.
  - The four container verbs and `containerState`, including null for a non-container and the lock-without-key error.
- `PLUGINS.md` `## What plugins can touch`: add a bullet **doors and containers:** naming the eight verbs, `exit(dir)`
  and `containerState`, and the rule sentence from the spec change.
- CHANGELOG bullet: the new verbs and reads.

### Exit Conditions

- [] `./gradlew :plugin-api:test` passes, including `FacadeDoorsTest` and `CtxParityTest`.
- [] `./gradlew check` passes; API files regenerated.

---

## Phase 3: Messaging, simulation, standing, dice

### Objective & Scope

- **In Scope:** `say`, `emote`, `emit`; `setActive`, `clearActive`, `setAnchor`, `clearAnchor`; `clearStanding`,
  `standingHistory`; `roll`; `PluginRng.choice`; `heal`'s `source`; `PluginRoom.desc` and `PluginRoom.affects`.
- **Out of Scope:** *Do not implement these; later phases do.* Mob, combat and group verbs; other actor/item reads.

### Context Working Set

- **Read / Reference Only:** `script/src/main/kotlin/gups/script/CtxMessaging.kt` (`say` ~29, `emote` ~44, `emit` ~180);
  `CtxSimulation.kt` (`set_active` ~39 and the shared `reason` helper); `CtxStanding.kt` (`standing_history` ~83,
  `clear_standing` ~221, `toStandingHolder`/`toStandingSubject`); `CtxQueries.kt` (`roll` ~158); `CtxCombat.kt` (`heal`
  ~67); `Views.kt` (`RngView.choice`, `RoomView.desc`/`affects`); host `Facade.kt` (`standing`/`adjustStanding`
  conversion of `holder: Any`, which `clearStanding` and `standingHistory` reuse).
- **Files to Modify / Create:** both `Facade.kt` files, create `plugin-api/src/test/kotlin/gups/pluginapi/
  FacadeMessagingTest.kt`, `CtxParityTest.kt`, `ApiSurfaceTest.kt` (`PluginStandingTotals`), the four samples' test
  fakes that override `heal` (`samples/*/src/test/...`: `WeatherPluginTest.kt`, `BlueMagePluginTest.kt`, heritage
  `FakeWorld.kt`, `CursePluginTest.kt`), `docs/SPEC.md`, `docs/PLUGINS.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `script/`, `core/`.

### Specification & Decision Record Changes

- §4.15 Facade bullet verb list: add `` `say`, `emote`, `emit`, `setActive`, `clearActive`, `setAnchor`, `clearAnchor`,
  `clearStanding`, `roll` `` after the Phase 2 names; in the query list add `` `standingHistory` ``; and note that `rng`
  has `choice(list)`. Change "`heal`" wording nowhere else; the list already has it.

### Implementation Blueprint

#### Signatures & Types

```kotlin
// PluginWorld
fun heal(target: PluginActor, amount: Int, vital: String, source: PluginActor? = null)  // changed
fun say(speaker: PluginActor, text: String)
fun emote(actor: PluginActor, text: String)
/** Raises a perceivable event in [room]; an intensity of 0 or less emits nothing. */
fun emit(
  room: PluginRoom,
  channel: String,
  event: String,
  intensity: Int = 1,
  text: String? = null,
  actor: PluginActor? = null,
)
fun setActive(entity: PluginEntity, reason: String)
fun clearActive(entity: PluginEntity, reason: String)
fun setAnchor(entity: PluginEntity, reason: String)
fun clearAnchor(entity: PluginEntity, reason: String)
/** [holder] and [subject] as for [standing]; a null subject clears every record of the holder. */
fun clearStanding(holder: Any, subject: Any? = null)
/** Rolls dice such as "2d6+1" with the world's RNG. */
fun roll(dice: String): Int

// PluginGmcpWorld
fun standingHistory(
  holder: Any,
  subject: Any,
  since: Long,
  via: PluginActor? = null,
  event: String? = null,
): PluginStandingTotals

data class PluginStandingTotals(val gained: Long, val lost: Long)

// PluginRng
/** One element of [items], uniformly, as `ctx.rng.choice`; an empty list throws. */
fun <T> choice(items: List<T>): T

// PluginRoom
val desc: String
/** Names of the room's live affects, in id order. */
val affects: List<String>
```

`heal` changes signature: the samples' fakes that override it gain the `source` parameter (they stay `TODO()`).

#### Algorithmic Steps & Invariants

1. `say`/`emote`: `actorOf`, then `world.say`/`world.emote`. Text validation is whatever `World` does; don't add more.
2. `emit`: validate `channel` against `world.schema.allChannels` with `declared(...)` (suggestion on a typo), require a
   non-blank `event`, and when `intensity > 0` call `world.perceive(event, Channel(channel), roomOf(room), actor?.let {
   actorOf(it) }, text, intensity)`, mirroring `CtxMessaging.emit`.
3. Simulation verbs: `entityOf`, require a non-blank `reason`, call `world.setActive` etc. Return nothing, as `ctx`
   does. Copy any reason validation `CtxSimulation.reason` does.
4. `clearStanding`/`standingHistory`: convert `holder`/`subject` exactly as the existing `standing`/`adjustStanding`
   implementations do (extract a private helper if those two duplicate the conversion); `since` is coerced to at least
   0; `via` must be a mob. Call `world.clearStanding` / `world.standingHistory`. Both need `standing` if
   `standing`/`adjustStanding` check it.
5. `roll`: parse with `gups.core` `Dice.parse` (rethrow its `IllegalArgumentException` prefixed `"roll(): "`), roll with
   `world.rng`.
6. `RngFacade.choice`: require non-empty (`"choice(): the list is empty"`), then the core `Rng.choice`, so a draw
   matches `ctx.rng.choice` for the same seed.
7. `heal`: pass `source?.let { actorOf(it, "heal(source)") }` to `world.heal`.
8. `RoomFacade.desc` and `affects`: as `RoomView` reads them (`liveAffects(world.roomClock)`, names).
9. Parity: remove from `PENDING` `ctx.say`, `ctx.emote`, `ctx.emit`, `ctx.set_active`, `ctx.clear_active`,
   `ctx.set_anchor`, `ctx.clear_anchor`, `ctx.clear_standing`, `ctx.standing_history`, `ctx.roll`, `rng.choice`,
   `room.desc`, `room.affects`.

### Test & Documentation Requirements

- `FacadeMessagingTest.kt` (real `world()` fixture, a player in room `a`, a mob that records `on_say`/`on_perceive` in
  an attribute): `say` fires `on_say` and the player's output contains the line `World.say` produces; `emote` reaches
  the room; `emit` with intensity 0 delivers nothing and with an unknown channel throws with a suggestion;
  `setAnchor`/`clearAnchor` change `world.isSimulated` of a mob in an empty far room (with `bubble = true`);
  `clearStanding` removes a record `adjustStanding` made; `standingHistory` sums gains and losses; `roll("2d6+1")` is in
  3..13 and an invalid expression throws; `rng.choice` equals what a second world with the same seed draws through
  `ctx.rng.choice` (or simply that two facade draws from same-seeded worlds match); `heal` with a `source` credits the
  source's contribution (compare with `FacadeTest`'s damage-source test if one exists).
- `PLUGINS.md`: add a **messaging and simulation:** bullet and extend **reads** with `standingHistory`, `rng.choice`,
  `PluginRoom.desc` and `affects`; mention `heal(..., source)`.
- CHANGELOG bullets, including "Implementers of `PluginWorld` must add `source: PluginActor? = null` to `heal`."

### Exit Conditions

- [] `./gradlew :plugin-api:test` and every `samples/*` test task pass.
- [] `./gradlew check` passes; API files regenerated.

---

## Phase 4: Mobs: spawning, actions, summons, owners and leashes

### Objective & Scope

- **In Scope:** `spawnMob`; `act`, `actMove`, `actAttack`, `actCast`, `moveToward`; `summon`; `setOwner`, `clearOwner`,
  `setLeash`, `resetLeash`; `PluginActor.owner`, `homeRoom`, `leash`, `leashOverridden`.
- **Out of Scope:** *Do not implement these; later phases do.* Combat, groups, other reads. Track-aware movement
  (`actAdvance`, `actRetreat`, an `index` for `spawnMob`). Removing mobs.

### Context Working Set

- **Read / Reference Only:** `script/src/main/kotlin/gups/script/CtxItems.kt` (`spawn` ~50, the mob branch);
  `CtxMobActions.kt` (whole file; ignore `acted`'s `frame.acted`, a think-only detail); `CtxAffects.kt` (`summon` ~279);
  `CtxSimulation.kt` (`set_owner` ~68 through `reset_leash`); `Views.kt` (`owner` ~163, `home_room` ~400, `leash` ~407,
  `leash_overridden` ~414); `core/.../world/World.kt` (`spawnMob` ~608); `core/.../world/EffectApplication.kt` (`summon`
  ~43); host `Facade.kt` (`spawnItem` ~689, `itemLabel` ~779).
- **Files to Modify / Create:** both `Facade.kt` files, create
  `plugin-api/src/test/kotlin/gups/pluginapi/FacadeMobsTest.kt`, `CtxParityTest.kt`, `docs/SPEC.md`, `docs/PLUGINS.md`,
  `docs/CHANGELOG.md`.
- **Do Not Touch:** `script/`, `core/`, `samples/`.

### Specification & Decision Record Changes

- §4.15 Facade bullet verb list: add `` `spawnMob`, `act`, `actMove`, `actAttack`, `actCast`, `moveToward`, `summon`,
  `setOwner`, `clearOwner`, `setLeash`, `resetLeash` ``. Replace (approved wording) the custody sentence "`spawnItem`
  returns a list: one stack for a stackable label, and `count` items otherwise." with: "`spawnItem` returns a list: one
  stack for a stackable label, and `count` items otherwise. `spawnMob(label, room, count)` makes `count` mobs as
  `ctx.spawn` does for a mob label and returns them: no owner, no leash override and no spawn rule, a home room equal to
  `room`, at track index 0. The mob actions queue tick-lane actions for a mob, never a player, and return whether the
  action was queued."

### Implementation Blueprint

#### Signatures & Types

```kotlin
// PluginWorld
fun spawnMob(label: String, room: PluginRoom, count: Int = 1): List<PluginActor>
fun act(mob: PluginActor, commandLine: String): Boolean
fun actMove(mob: PluginActor, dir: String): Boolean
fun actAttack(mob: PluginActor, target: PluginActor): Boolean
fun actCast(mob: PluginActor, skill: String, target: PluginActor? = null): Boolean
fun moveToward(mob: PluginActor, dir: String): Boolean
fun moveToward(mob: PluginActor, room: PluginRoom): Boolean
fun moveToward(mob: PluginActor, target: PluginActor): Boolean
/** Spawns mobs beside [caster] as `ctx.summon` does; a `before_summon` veto returns an empty list. */
fun summon(caster: PluginActor, label: String, count: Int = 1, duration: Int? = null): List<PluginActor>
fun setOwner(mob: PluginActor, player: PluginActor): Boolean
fun clearOwner(mob: PluginActor): Boolean
/** [leash] at least 0, or null to unleash. */
fun setLeash(mob: PluginActor, leash: Int?)
fun resetLeash(mob: PluginActor)

// PluginActor
val owner: PluginActor?
val homeRoom: PluginRoom?
/** A mob's effective leash; null for no leash or for a player. */
val leash: Int?
val leashOverridden: Boolean
```

#### Algorithmic Steps & Invariants

1. `spawnMob`: an absolute mob label (add `mobLabel` mirroring `itemLabel`; message `"spawnMob(label): '<label>' is not
   a mob"`), `count >= 1`, `roomOf`, then `List(count) { world.spawnMob(l, r, 0) }` wrapped in `ActorFacade`.
   `spawnItem` keeps refusing mob labels.
2. Mob actions: `mobOf(mob, "act(mob)")` (a player throws `"act(mob): must be a mob"`), then the `World` function the
   `CtxMobActions` verb calls, returning its Boolean. `actCast` resolves an absolute skill label from
   `world.protos.skills` (`"actCast(skill): there is no skill <label>"`). `moveToward` mirrors
   `CtxMobActions.moveToward` branch by branch: direction string, room, actor (same room → `actAdvance` unless already
   at the same index).
3. `summon`: absolute mob label, `count >= 1`, `duration` null or `>= 1`, then `world.summon(actorOf(caster),
   Effect.Summon(l, count, duration))`.
4. Owner and leash verbs: copy the argument checks from `CtxSimulation` (`setOwner(player)` must be an in-world player;
   `setLeash(leash)` must be null or at least 0).
5. Reads: `owner` as `ActorView.owner` reads it (the in-world player or null); `homeRoom` the mob's home room or a
   player's null (match `ActorView.home_room`); `leash`/`leashOverridden` as `ActorView`.
6. Parity: remove `ctx.spawn` from `PENDING` and add it to `EXCEPTIONS` as "spelled `spawnItem` and `spawnMob`". Remove
   from `PENDING` `ctx.act`, `ctx.act_move`, `ctx.act_attack`, `ctx.act_cast`, `ctx.move_toward`, `ctx.summon`,
   `ctx.set_owner`, `ctx.clear_owner`, `ctx.set_leash`, `ctx.reset_leash`, `actor.owner`, `actor.home_room`,
   `actor.leash`, `actor.leash_overridden`.

### Test & Documentation Requirements

- `FacadeMobsTest.kt` (real `world()`):
  - `spawnMob(":wolf"…)` with an absolute label: count 3 returns 3 mobs in the room, each with `owner == null`,
    `homeRoom` the room, and `leashOverridden == false`; a mob's `on_spawn` runs (record via an attribute) and sees the
    room; an item label throws; `count = 0` throws.
  - `actMove` on a mob returns true and the mob is in the next room after `world` runs a pulse; on a player throws.
  - `act(mob, "say hi")` queues and the room hears it after a pulse.
  - `actCast` with an unknown skill throws; with the `skills` feature off throws `IllegalStateException`.
  - `summon` with a player caster sets the owner; with `duration = 2` the summon fades after its affect ends.
  - `setOwner`/`clearOwner` return values and `owner`; `setLeash(m, 2)` → `leash == 2`, `leashOverridden`; `resetLeash`
    drops it; `setLeash(m, -1)` throws.
- `PLUGINS.md`: a **mobs:** bullet with the verbs and reads and the `spawnMob` sentence from the spec change.
- CHANGELOG bullets.

### Exit Conditions

- [] `./gradlew :plugin-api:test` passes.
- [] `./gradlew check` passes; API files regenerated.

---

## Phase 5: Combat, groups and world queries

### Objective & Scope

- **In Scope:** `startCombat`, `stopCombat`, `clearContributions`, `enemiesNear`, `alliesNear`, `pathTo`, `plan`; the
  four group verbs, `groupOf` with `PluginGroup`, `groupMembers`; `PluginActor.fighting` and `primaryTarget`; the group
  parity pair.
- **Out of Scope:** *Do not implement these; later phases do.* The remaining actor and item reads.

### Context Working Set

- **Read / Reference Only:** `script/src/main/kotlin/gups/script/CtxCombat.kt` (`start_combat` ~177 to
  `clear_contributions` ~221); `CtxQueries.kt` (`near` ~85, `enemies_near`, `allies_near`, `path_to` ~137, `plan` ~229
  and `factMap`); `CtxGroups.kt` (whole file); `Views.kt` (`fighting` ~344, `primary_target` ~351, `GroupView` ~629);
  `core/.../ai/GoapPlanner.kt` (`GoapAction`, `FactValue`, `plan`, which validates its own limits);
  `core/.../world/Verbs.kt` (`pathTo` ~85).
- **Files to Modify / Create:** both `Facade.kt` files, create
  `plugin-api/src/test/kotlin/gups/pluginapi/FacadeCombatGroupsTest.kt`, `CtxParityTest.kt`, `ApiSurfaceTest.kt`
  (`PluginGroup`, `PluginPlanAction`), `docs/SPEC.md`, `docs/PLUGINS.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `script/`, `core/`, `samples/`.

### Specification & Decision Record Changes

- §4.15 Facade bullet verb list: add `` `startCombat`, `stopCombat`, `clearContributions`, `groupAdd`, `groupRemove`,
  `groupSetLeader`, `groupDisband` ``; query list: add `` `enemiesNear`, `alliesNear`, `pathTo`, `plan`, `groupOf`,
  `groupMembers` ``; views: add `` `PluginGroup` ``.

### Implementation Blueprint

#### Signatures & Types

```kotlin
// PluginWorld
fun startCombat(a: PluginActor, b: PluginActor)
fun stopCombat(a: PluginActor, b: PluginActor? = null)
fun clearContributions(victim: PluginActor, contributor: PluginActor? = null)
fun groupAdd(member: PluginActor, to: PluginActor)
fun groupRemove(member: PluginActor)
fun groupSetLeader(member: PluginActor)
fun groupDisband(member: PluginActor)

// PluginGmcpWorld
/** Opponents of [actor] in its room within [band] ("melee", "near" or "far"), by id. */
fun enemiesNear(actor: PluginActor, band: String): List<PluginActor>
fun alliesNear(actor: PluginActor, band: String): List<PluginActor>
/** Directions of the shortest passable walk; empty when [from] is [to], null when unreachable. */
fun pathTo(from: PluginRoom, to: PluginRoom): List<String>?
/** Goal-oriented planning as `ctx.plan`; fact values are Boolean, Int/Long or String. */
fun plan(
  facts: Map<String, Any>,
  actions: List<PluginPlanAction>,
  goal: Map<String, Any>,
  maxExpansions: Int = 1024,
): List<String>?
fun groupOf(actor: PluginActor): PluginGroup?
fun groupMembers(actor: PluginActor): List<PluginActor>

data class PluginPlanAction(
  val name: String,
  val pre: Map<String, Any> = emptyMap(),
  val effects: Map<String, Any> = emptyMap(),
  val cost: Int = 1,
)

data class PluginGroup(val id: Long, val leader: PluginActor, val members: List<PluginActor>)

// PluginActor
/** Actors engaged with this one, by id. */
val fighting: List<PluginActor>
val primaryTarget: PluginActor?
```

#### Algorithmic Steps & Invariants

1. Combat verbs: `needs(world, Feature.COMBAT, "startCombat")` etc., `actorOf`, then `world.startCombat(a, b)` /
   `world.stopCombat(a, b)` / `world.clearContributions(v, c)`. Copy any extra argument checks from `CtxCombat` (for
   example, an actor fighting itself).
2. `enemiesNear`/`alliesNear`: copy `CtxQueries.near` and its two filters into a private helper; parse `band` with
   `Band.parse`, failing with the allowed words. `enemiesNear` needs `combat`; check whether `alliesNear` is gated in
   `ctx` and match it.
3. `pathTo`: `world.pathTo(roomOf(from), roomOf(to))?.map { it.name }`.
4. `plan`: convert fact values (`Boolean` → `FactValue.Bool`, `Int`/`Long` → `FactValue.Num`, `String` →
   `FactValue.Text`, anything else throws `"plan(facts): fact '<k>' must be a Boolean, Int, Long or String"`), build
   `GoapAction`s, call `GoapPlanner.plan`, map `Found` to its steps and `NotFound` to null. `GoapPlanner` throws for its
   limits; let that propagate.
5. Group verbs: `playerOf(...)`, then the `World` group functions. `groupOf`: `world.groupOf(a)` → `PluginGroup(g.id,
   leader facade, world.groupMembers(a) as facades)`, matching `CtxGroups.groupOf`'s leader-first member order.
6. Reads: `fighting` as `ActorView.fighting` (ids → actors in the world, sorted by id); `primaryTarget` as
   `ActorView.primary_target`. Gate them as `ActorView` gates them.
7. Parity: add `Coverage("group", GroupView, [PluginGroup])`. Remove from `PENDING` the seven combat and query keys
   (`ctx.start_combat`, `ctx.stop_combat`, `ctx.clear_contributions`, `ctx.enemies_near`, `ctx.allies_near`,
   `ctx.path_to`, `ctx.plan`), the six `ctx.group_*` keys, `actor.fighting`, `actor.primary_target`.

### Test & Documentation Requirements

- `FacadeCombatGroupsTest.kt` (real `world()`): `startCombat(player, wolf)` makes each appear in the other's `fighting`,
  `primaryTarget` set; `enemiesNear(player, "far")` contains the wolf; `stopCombat(player)` clears both; with `combat`
  off `startCombat` throws `IllegalStateException`; `clearContributions` empties a record a `damage` with `source` made
  (read it back the way an existing test does, or through a death's XP split if that is the only observable); `pathTo(a,
  b) == listOf("north")`, `pathTo(a, a)` is empty, an unreachable room gives null; `plan` finds a two-step plan, returns
  `[]` when the goal holds, null when unreachable, and throws for a float fact; group verbs with two players
  (`groupAdd`, `groupOf` leader and members, `groupSetLeader`, `groupRemove`, `groupDisband`); a mob argument to
  `groupAdd` throws.
- `PLUGINS.md`: a **combat and groups:** bullet, and the new reads.
- CHANGELOG bullets.

### Exit Conditions

- [] `./gradlew :plugin-api:test` passes.
- [] `./gradlew check` passes; API files regenerated.

---

## Phase 6: The remaining actor and item reads

### Objective & Scope

- **In Scope:** on `PluginActor`: `inWorld`, `keywords`, `xp`, `condition`, `senses`, `belowZeroSince(vital)`,
  `carriedWeight`, `encumbranceLevel`, `isCasting`, `casting`, `hasFlag(flag)`, `hasItem(label)`, `slots`, `waitState`,
  `movePace`, `recallRoom`; on `PluginItem`: `inWorld`, `dice`, `damageType`, `statDeltas`, `senseDeltas`. After this
  phase `PENDING` is empty and is deleted.
- **Out of Scope:** *Do not implement these; later phases do.* Track reads; the weather sample.

### Context Working Set

- **Read / Reference Only:** `script/src/main/kotlin/gups/script/Views.kt`: each field's `ActorView`/`ItemView` method
  (`in_world` ~149, `keywords` ~156, `xp` ~172, `condition` ~185, `senses` ~199, `below_zero_since` ~223, `slots` ~315,
  `has_item` ~333, `is_casting` ~364, `wait_state` ~388, `move_pace` ~392, `recall_room` ~421, `has_flag` ~437,
  `carried_weight` ~470, `encumbrance_level` ~479, `casting` ~485; `ItemView` `keywords` ~736, `in_world` ~776, `dice`
  ~835, `damage_type` ~841, `stat_deltas` ~867, `sense_deltas` ~873). Host `Facade.kt` `ActorFacade` ~245, `ItemFacade`
  ~339, `SkillFacade` ~427.
- **Files to Modify / Create:** both `Facade.kt` files, create
  `plugin-api/src/test/kotlin/gups/pluginapi/FacadeReadsTest.kt`, `CtxParityTest.kt`, `docs/SPEC.md`, `docs/PLUGINS.md`,
  `docs/CHANGELOG.md`.
- **Do Not Touch:** `script/`, `core/`, `samples/`.

### Specification & Decision Record Changes

- §4.15 Facade bullet: after "read-only views of live entities (…)", add (approved wording): "The views carry the fields
  of the `ctx` actor, room and item views (§4.5), named in camelCase, except the track fields."

### Implementation Blueprint

#### Signatures & Types

```kotlin
// PluginActor
val inWorld: Boolean
val keywords: List<String>
val xp: Long                        // needs leveling
/** "ok", "incapacitated" or "dead". */
val condition: String
/** Effective score per declared channel. */
val senses: Map<String, Int>
/** The pulse [vital] last dropped below 0, or null. */
fun belowZeroSince(vital: String): Long?
val carriedWeight: Int
val encumbranceLevel: Int
val isCasting: Boolean              // needs skills
val casting: PluginSkill?           // needs skills
fun hasFlag(flag: String): Boolean
/** Whether the actor carries or wears an item of prototype [label]. */
fun hasItem(label: String): Boolean
val slots: List<String>
val waitState: Int
/** Hundredths of a pulse per room; 100 is one room per pulse. */
val movePace: Int
val recallRoom: PluginRoom?

// PluginItem
val inWorld: Boolean
/** Weapon damage dice such as "1d6", or null. */
val dice: String?
val damageType: String
val statDeltas: Map<String, Int>
val senseDeltas: Map<String, Int>
```

Use the types the `ctx` field returns (an int field → `Int` unless it can exceed it; XP and pulses → `Long`). If
`PluginItem` already has `keywords`, don't add it again.

#### Algorithmic Steps & Invariants

1. Each getter reads current state on every access, as the existing views do, and computes exactly what its
   `ActorView`/`ItemView` method computes, with the same feature gate. `movePace` builds the same `RulesContext` the
   view does (`ctx.exit` absent). `hasItem` takes an absolute label. `belowZeroSince` validates the vital name with
   `declared(...)`.
2. `inWorld` on a stale view returns false rather than throwing (it is how a plugin checks staleness); every other
   getter keeps the existing stale-view behavior.
3. Parity: remove the remaining `PENDING` keys, then delete the `PENDING` map and its references; Test 2 now checks only
   `EXCEPTIONS`.

### Test & Documentation Requirements

- `FacadeReadsTest.kt` (real `world()`): for a player and a mob, each read against what a scripted hook reads through
  `ctx` in the same world (store the `ctx` value in an attribute from a command or hook and compare), covering at least
  `condition`, `senses`, `slots`, `carriedWeight` after picking up an item, `hasItem`, `hasFlag`, `waitState`,
  `movePace`, `recallRoom`; `inWorld` false after the mob dies; `xp` throws `IllegalStateException` with `leveling` off;
  an item's `dice`, `damageType`, `statDeltas`, `senseDeltas` against its prototype.
- `PLUGINS.md` **views:** bullet: list the new fields, or say "the views carry the fields of the `ctx` views in
  camelCase, except the track fields" and name the exceptions.
- CHANGELOG bullet.

### Exit Conditions

- [] `CtxParityTest` passes with no `PENDING` map; its remaining `EXCEPTIONS` are exactly the Phase 1 table plus
  `exit.entry_index` and `ctx.spawn`.
- [] `./gradlew check` passes; API files regenerated.

---

## Phase 7: The weather sample locks doors in storms

### Objective & Scope

- **In Scope:** an `every` handler in `WeatherPlugin` that locks and unlocks outdoor keyed doors; unit tests with the
  sample's fakes; an integration case in the built-jar test; the sample README.
- **Out of Scope:** *Do not implement these.* The tutorial series under `samples/weather-plugin/docs/tutorials/`; any
  engine change.

### Context Working Set

- **Read / Reference Only:** `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt` (`every` ~158, `PluginTickCall`);
  `template-world/zones/tutorial/rooms.star:51` (door syntax).
- **Files to Modify:** `samples/weather-plugin/src/main/kotlin/gups/sample/weather/WeatherPlugin.kt`,
  `samples/weather-plugin/src/test/kotlin/gups/sample/weather/WeatherPluginTest.kt`, `samples/weather-plugin/README.md`,
  `docs/SPEC.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `plugin-api/src/main`, `core/`, `script/`, the tutorials.

### Specification & Decision Record Changes

- §4.15 **Weather Plugin** bullet (~3256): add (approved wording) "and an `every` handler that slams and locks keyed
  outdoor doors when a storm starts and unlocks them when it passes" to the list of what the sample shows.

### Implementation Blueprint

#### Algorithmic Steps & Invariants

1. Register `r.every(WINDOW.toInt(), "Storms slam and lock outdoor doors; they unlock when the storm passes.") { call ->
   stormDoors(call.world) }`. `WINDOW` is 240, so the handler runs on each window boundary, when the weather can change.
2. `stormDoors(world: PluginWorld)` runs two passes over `world.rooms()` (sorted by label), so a door shared by two
   rooms is never unlocked after being locked in the same run:
   - **Unlock pass:** for each room whose `stateFor` is not `"storm"` and that has the attribute `"locked"` (a list of
     direction names, in the plugin's namespace): for each listed direction whose door is still `"locked"`,
     `unlockDoor`; then `delAttr(room, "locked")`. Send nothing.
   - **Lock pass:** for each outdoor room (`regions` not empty) whose `stateFor` is `"storm"`: for each direction in
     `exits.keys.sorted()`, take `room.exit(dir)?.door`; skip a door with no `key` or already `"locked"`. If it was
     `"open"`, `echo(room, "The storm slams the door to the $dir shut.")`. Then `lockDoor(room, dir)` and add `dir` to
     the room's `"locked"` list (`setAttr`) unless it is already listed, since a storm can last several windows. Doors
     the plugin didn't lock are never unlocked by it.
3. Keep `WeatherPlugin` pure Kotlin against the facade; no engine types.

### Test & Documentation Requirements

- Unit tests in `WeatherPluginTest.kt`: make `FakeRegistry` capture `every` registrations (override `every` and store
  `pulses` and `fn`; it is a no-op default today). Extend the fake `PluginWorld`/`PluginRoom` with what the handler uses
  (`rooms`, `exit`, `lockDoor`, `unlockDoor`, `echo`, `getAttr`, `setAttr`, `delAttr`), recording calls. Pick a seed and
  window in the test by searching with `WeatherPlugin.stateFor` for a window where the region is `"storm"` and the next
  window isn't. Cases: the handler is registered with 240 pulses; a storm locks a keyed open door and echoes the exact
  message; a keyed closed door is locked silently; an unkeyed door and an already-locked door are left alone; after the
  storm only the doors the plugin locked are unlocked; an indoor room is never touched.
- Integration: in `the built jar runs through TestRunner`, give the field a keyed door to the cabin (`exit("south",
  ":cabin", door = door(state = DOOR.open, key = ":cabin_key"))`, with a `cabin_key` item, and the matching door on the
  cabin's exit). The Kotlin test computes, with `stateFor` and the world seed the harness uses, how many pulses until
  the first storm window, and writes them into a new `valley_test.star` case: `test_a_storm_locks_the_field_door` runs
  to that window (plus one pulse) with `w.run(pulses = N)` and asserts that `open south` from the field gives the
  engine's locked-door refusal (look it up in the kernel messages). Check how the harness's `t.world(seed = ...)` seed
  and starting pulse relate to `PluginWorld.seed` and `pulse` before computing `N`; update the expected result count
  from 3 to 4.
- README: add a bullet under the extension points: "**Periodic work:** an `every` handler that slams and locks keyed
  outdoor doors when a storm starts, and unlocks them when it passes."
- CHANGELOG bullet: "`samples/weather-plugin` locks keyed outdoor doors during storms, using the new facade door verbs."

### Exit Conditions

- [] `./gradlew :samples:weather-plugin:test` passes, including the new integration case.
- [] `./gradlew check` passes; API files regenerated.

---

## Phase 8: Finalization & Backlog Update

- In `docs/DESIGN_NEEDED.md`, mark the entry completed without deleting it: change its heading line from ``### The rest
  of the `ctx` verbs on the plugin facade`` to exactly ``### The rest of the `ctx` verbs on the plugin facade (Done in
  Milestone 53)``. Leave the three related entries (`Plugin timers`, `Track positions on the plugin facade`, `Removing a
  mob without killing it`) unchanged.
- Re-read the §4.15 Facade bullet and `PLUGINS.md` `## What plugins can touch` against `plugin-api/.../Facade.kt`: every
  public member is named in both, and nothing named is missing from the code.
- Confirm the CHANGELOG subsection `### Plugin facade parity with ctx` has a bullet for every phase.
- Run `./gradlew check` and `./gradlew markdownCheck` outside the sandbox; both must pass.
- Run `pre_commit_code_health_safeguard`; it must pass.
- Delete `local-world-test/` if it exists.
