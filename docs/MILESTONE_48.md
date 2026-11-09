# Milestone 48: More Trait Event Callbacks

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, heading `### More trait event callbacks`.

- **Naming convention (applies to every phase):** engine and plugin identifiers say **skill**, never **spell**. This
  milestone adds no skill-named identifiers, but the rule still holds for anything you name.

- **Rules for every phase** (from `AGENTS.md`):

  - Test first: write the tests, run them and watch them fail, then write the code.
  - Do not cite spec sections or decision IDs in code comments. Comments explain why, not what.
  - Run `./gradlew check` outside the sandbox (`BypassSandbox: true`). All Kotlin warnings are errors.
  - After editing any Markdown file, run `./gradlew markdownFormat` (outside the sandbox), then check that code blocks
    inside lists kept their indentation.
  - Never read or touch `local-world/`.
- **Design Summary:**
  - **Today.** `PluginTrait.on` maps a `PluginTraitEvent` to a handler that runs for each bearer of the trait. The only
    event is `SKILL_RECOGNIZED`, which fires once at the end of a cast and only for players.
  - **New events.** Six events are added, each at a place in the engine that already does the work. A handler runs
    synchronously at that place, nested in whatever is running there.

    |      Event      |         Bearer         |             Fires from             |                    Fields                     |
    |-----------------|------------------------|------------------------------------|-----------------------------------------------|
    | `DAMAGED`       | the victim             | `World.damage`, after `on_damage`  | `source`, `amount`, `type`, `losses`, `parts` |
    | `HEALED`        | the target of the heal | `World.heal`                       | `source`, `vital`, `amount`, `requested`      |
    | `KILLED`        | the killer             | death processing, after `on_death` | `victim`                                      |
    | `DIED`          | the victim             | death processing, after `on_death` | `killer`                                      |
    | `LEVEL_CHANGED` | the actor              | both level-step sites              | `old_level`, `new_level`                      |
    | `ROOM_ENTERED`  | the actor that entered | walking, fleeing and `teleport`    | `from_room`, `direction`                      |

  - **Where "inline" lands.** `DAMAGED` and `HEALED` run inside the `damage` or `heal` call that caused them.
    `ROOM_ENTERED` runs where `on_enter` runs: inside `transition` for a walk or flee, and inside the deferred removal
    for a `teleport`. `DIED` and `KILLED` run in death processing, which is itself deferred (`setCondition` schedules it
    with `deferRemoval`), so they never nest inside the `DAMAGED` chain of the blow that killed.

  - **Field types (core side).** Fields are plain values, so the plugin adapter only has to convert actors, rooms and
    ints:

    - Actors are `Actor`, rooms are `Room`, ints are `Int`. `source` and `killer` are `Actor?`.
    - `direction` is `String?`: the exit's `direction.name` (`Direction` is a core value class and must not reach a
      plugin), null for a teleport.
    - `type` is the damage type's name, a `String`. `vital` is a `String`.
    - `losses` is `Map<String, Int>` (vital name to positive loss).
    - `parts` is `List<Map<String, Any?>>`; each part is `mapOf("type" to <String>, "losses" to <Map<String, Int>>)`. No
      core `DamagePart` crosses into the plugin API.
  - **Field types (plugin side).** The adapter turns `Actor` into `PluginActor`, `Room` into `PluginRoom`, `Int` into
    `Long`, and recurses into maps and lists. So a plugin sees `losses` as `Map<String, Long>` and `parts` as
    `List<Map<String, Any?>>` with `losses` a `Map<String, Long>`.
  - **Who can be a bearer.** Any actor with the trait, players and mobs, provided the world is simulating it
    (`isSimulated`) and it is still `inWorld` when its handler is about to run. `SKILL_RECOGNIZED` keeps its own
    players-only reach, because only players recognize skills.
  - **Re-entrancy.** Each handler call is its own entry in the existing cascade guard (`CASCADE_MAX_DEPTH`, 8 today), so
    a handler that deals damage into another `DAMAGED` handler, and so on, stops at the depth cap, and the entry that
    would go deeper is dropped with a `cascade` diagnostic. No new mechanism. This also newly applies to
    `SKILL_RECOGNIZED`: a recognition that happens 8 entries deep is dropped with a diagnostic. In practice casts finish
    at the top level, so nothing changes for existing worlds.
  - **Order for one event.** Trait handlers run right after the entity hooks the engine already dispatches there
    (`dispatch`: script hooks on entities, the room and items). Only death and level change also have global hooks and
    plugin `onEvent` listeners (the only `dispatchGlobal` calls are for `on_death`, `on_disengage`, `on_level_change`
    and `on_owner_change`). For those two the order is: entity hooks, trait handlers, script global hooks, plugin
    listeners. `DAMAGED` and `ROOM_ENTERED` follow `on_damage` and `on_enter` and have nothing after them. `HEALED` has
    no hook to follow. Where two bearers share a site (death), the victim's `DIED` handlers run before the killer's
    `KILLED` handlers. For one bearer, traits run in the order of `Actor.traits`, which `recomputeTraits` sorts by trait
    name.
  - **Relation to procs.** Procs stay as they are: item- and affect-scoped reactions to a melee swing (`hit`, `struck`,
    `avoid`). Trait events are bearer-scoped and cover any damage, not only swings. On a swing, `damage` runs before
    `fireProcs` (`Combat.kt`, the end of the attack resolution), so the victim's `DAMAGED` handlers run before the `HIT`
    and `STRUCK` procs of that swing. A trait that only wants to react to melee hits still uses `DAMAGED`; there is no
    trait-scoped proc.
  - **Placement details:**
    - `DAMAGED` fires only when the damage event has a positive loss (the same condition as `on_damage`), right after
      `on_damage` and before the interrupt check and the condition refresh. A call that only gains fires nothing. The
      handler therefore sees the new vitals but the old condition, exactly as `on_damage` does.
    - `HEALED` fires only from the `heal` verb, and only when the effective amount (after the maximum cap) is above 0.
      Regeneration, `changeVital`, and gains inside `damage` fire nothing. There is no new script hook.
    - `KILLED` fires only when the killer is non-null. A `DIED` handler cannot prevent the death, and damage it deals to
      the victim cannot start a second death (`processDeath` holds the victim in `dying`).
    - `ROOM_ENTERED` mirrors `on_enter`: it fires where `on_enter` fires today and nowhere else (login, spawn and
      respawn placement don't fire it).
    - `LEVEL_CHANGED` fires once per level step, at the two sites that fire `on_level_change`, and only when
      `Feature.LEVELING` is on, the same gate `dispatchGlobal` applies to `on_level_change` listeners. `levelDownStep`
      is public and doesn't check the feature itself, so the gate goes at the trait-event call.
  - **Left out of this milestone:** an affect-gained or affect-lost event, a script-side way for world content to
    declare trait handlers, and player-scoped hooks. The last one stays open in `DESIGN_NEEDED`.
  - **API version.** No `PluginApi.VERSION` bump and no compatibility shim: the engine is unreleased and the CHANGELOG
    lists this under `api_version 1 (unreleased)`.
- **Specification Impact:** the maintainer has approved the exact wording in each phase's "Specification & Decision
  Record Changes" section (2026-11-09). That is the explicit approval `AGENTS.md` asks for: edit `SPEC.md` with that
  wording directly, without asking again and without a `DECISIONS.md` entry. Any wording beyond it still needs approval.
  - Phase 1: the cascade paragraph in the hook-dispatch section (§5.6, "Cascades"), and the global hooks paragraph
    (§4.9) for the new order.
  - Phase 3: the "Trait callbacks" bullet (§4.15), the note under `on_damage` (§4.4), and `docs/PLUGINS.md`.
- **Decision Record Updates:** None. No existing `D-xxx` record mentions trait callbacks. This milestone creates no new
  records.

---

## Phase 1: Core Dispatch Helper, `DAMAGED` and `HEALED`

### Objective & Scope

- **In Scope:**
  - Add `DAMAGED` and `HEALED` to the core `TraitEvent` enum.
  - Add one internal helper that runs one bearer's trait handlers for an event, with the simulation, liveness and
    cascade checks. Move `SKILL_RECOGNIZED` onto it so there is one code path.
  - Fire `DAMAGED` from `World.damage` and `HEALED` from `World.heal`.
  - Update the cascade and global-hook paragraphs of the spec.
- **Out of Scope:** *Do not implement these; later phases do.* The other four events (`KILLED`, `DIED`, `LEVEL_CHANGED`,
  `ROOM_ENTERED`), any change to `plugin-api`, and `PLUGINS.md` and `CHANGELOG.md`.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Hooks.kt`: `withCascadeGuard` (near the end), `dispatchGlobal`.
  - `core/src/main/kotlin/gups/core/world/Bubble.kt`: `isSimulated` (line 38).
  - `core/src/test/kotlin/gups/core/world/CastRecognitionTest.kt`: how a test builds a `Trait` with `handlers`, assigns
    `w.traits`, calls `grantTrait`, and checks `w.diagnostics` (see lines 195-290).
  - `core/src/test/kotlin/gups/core/world/DispatchTest.kt`: how a test asserts a `cascade` diagnostic.
  - `core/src/test/kotlin/gups/core/world/DamageGainsTest.kt` and `VitalsTest.kt`: how a test installs an `on_damage`
    hook.
  - `core/src/test/kotlin/gups/core/world/BubbleTest.kt`: how a test makes a mob dormant.
  - `core/src/test/kotlin/gups/core/world/Fixture.kt`: the shared world fixture.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Creation.kt`: the enum.
  - `core/src/main/kotlin/gups/core/world/Hooks.kt`: the helper. It sits in the same file as `withCascadeGuard`, so that
    function stays `private`.
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt`: `fireSkillRecognized` uses the helper.
  - `core/src/main/kotlin/gups/core/world/Combat.kt`: `damage` (line ~276) and `heal` (line ~322).
  - Create `core/src/test/kotlin/gups/core/world/TraitEventsTest.kt`.
  - `docs/SPEC.md`: two paragraphs (below).
- **Do Not Touch:** `plugin-api/`, `samples/`, `Leveling.kt`, `Movement.kt`, the death code in `Combat.kt`.

### Specification & Decision Record Changes

- In the cascade bullet of the hook-dispatch section (§5.6; the text "Every script entry counts toward it:"), add trait
  handlers to the list of entries that count: "...and timer and affect callbacks, and trait handlers."
- In the global hooks paragraph (§4.9; it ends "Plugin event listeners (§4.15) run after them."), add a sentence before
  that one: "At death and at a level change, the bearers' trait handlers (§4.15) run after the hooks on entities, the
  room and items, and before the global hooks."
- No decision record changes.

### Implementation Blueprint

**Signatures & Types:**

```kotlin
// Creation.kt: extend the enum, keeping the existing KDoc on SKILL_RECOGNIZED
enum class TraitEvent {
  SKILL_RECOGNIZED,

  /**
   * [bearer] took damage with a positive loss. Fields: `source` (Actor or null), `amount` (Int),
   * `type` (damage type name), `losses` (Map<String, Int>) and `parts` (List of maps with `type`
   * and `losses`).
   */
  DAMAGED,

  /**
   * [bearer] gained vital points from `heal`. Fields: `source` (Actor or null), `vital`, `amount`
   * (Int, after the maximum cap, above 0) and `requested` (Int).
   */
  HEALED,
}

// Hooks.kt
/**
 * Runs [bearer]'s handlers for [event], in trait order. Skips a bearer the world isn't simulating
 * or that has left the world. Each handler call is a guarded cascade entry; a throw is recorded as
 * a `plugin` diagnostic and doesn't stop the others.
 */
internal fun World.fireTraitEvent(bearer: Actor, event: TraitEvent, fields: Map<String, Any?>)
```

The `DAMAGED` call in `World.damage` (step 3 below):

```kotlin
fireTraitEvent(
  target,
  TraitEvent.DAMAGED,
  mapOf(
    "source" to source,
    "amount" to total,
    "type" to type.name,
    "losses" to dealt,
    "parts" to parts.map { mapOf("type" to it.type.name, "losses" to it.losses) },
  ),
)
```

**Algorithmic Steps & Invariants:**

1. Implement `fireTraitEvent`: return if `!bearer.inWorld || !isSimulated(bearer)`. Take `val traits = bearer.traits`
   first; the list is immutable and replaced on change, so a handler that grants or removes a trait doesn't disturb the
   loop. For each trait with `trait.handlers[event]`, call it inside `withCascadeGuard("trait '${trait.name}' $event")`.
   Wrap the call in `try`/`catch (e: Exception)` and `diagnose("plugin", "trait '${trait.name}' handler $event threw:
   ${e.message}", e)`, copying the message `fireSkillRecognized` uses today. Before each handler after the first,
   re-check `bearer.inWorld`.
2. In `SkillCommands.kt`, `fireSkillRecognized` keeps its loop over `rec.learnedBy` and its check `!player.inWorld ||
   skill.label in player.skills`. Replace its inner loop over `player.traits` with one call `fireTraitEvent(player,
   TraitEvent.SKILL_RECOGNIZED, fields)`. The `isSimulated` check is always true for a player. The existing
   `CastRecognitionTest` cases (order, throwing handler, a handler that teaches the skill) must stay green unchanged.
3. In `World.damage` (`Combat.kt`), inside the existing `if (total > 0)` block, immediately after the
   `dispatch(HookName.ON_DAMAGE, ...)` call and before `val cast = target.casting`, add the `DAMAGED` call shown above.
4. In `World.heal`, after the existing `source != null` block ends (the function's last statements), add: when
   `effective` is above 0, `fireTraitEvent(target, TraitEvent.HEALED, mapOf("source" to source, "vital" to vital,
   "amount" to effective, "requested" to amount))`. It must run even when `source` is null. `changeVital` has already
   refreshed the condition by then, so the handler sees the new state.
5. Invariant: a `damage` call with only gains, and a `heal` that gains 0 because the vital is full, fire nothing.

### Test & Documentation Requirements

- In `TraitEventsTest.kt`, one test per behavior (write them first):
  - A `DAMAGED` handler fires for the victim with the right `source`, `amount`, `type`, `losses` and `parts`, and
    doesn't fire for the source.
  - `DAMAGED` doesn't fire for a gain-only `damage` call or for a zero-loss call.
  - `DAMAGED` fires after the script `on_damage` hook: install an `on_damage` hook as `DamageGainsTest` does that
    records a marker, and have the handler assert the marker is already there.
  - `HEALED` fires with `amount` capped at the room left under the maximum and `requested` as asked; it doesn't fire
    when the vital is full; it fires with a null `source` when none is given; regeneration (run pulses) doesn't fire it.
  - Re-entrancy: two actors each bear a `DAMAGED` handler that calls `damage` on the other. After one hit, the run
    stops, the world doesn't hang, `w.diagnostics` contains a `cascade` entry, and the total number of handler calls is
    bounded by `CASCADE_MAX_DEPTH`.
  - A throwing handler records a `plugin` diagnostic and later traits' handlers still run.
  - A dormant mob (set up as `BubbleTest.kt` does) with the trait gets no call.
- Update `SPEC.md` as above.

### Exit Conditions

- [] `./gradlew :core:test --tests "gups.core.world.TraitEventsTest"` passes.
- [] `./gradlew :core:test --tests "gups.core.world.CastRecognitionTest"` passes unchanged.
- [] `./gradlew markdownFormat` run; `./gradlew check` passes with zero warnings.
- [] No new comment in the changed code mentions a spec section or a `D-` id.

---

## Phase 2: `KILLED`, `DIED`, `LEVEL_CHANGED` and `ROOM_ENTERED`

### Objective & Scope

- **In Scope:** add the four remaining core events and fire them from the places that fire `on_death`, `on_level_change`
  and `on_enter`.
- **Out of Scope:** *Do not implement these; later phases do.* Any change to `plugin-api`, any Markdown change, and an
  affect-gained or affect-lost event.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Hooks.kt`: `fireTraitEvent` (Phase 1).
  - `core/src/test/kotlin/gups/core/world/TraitEventsTest.kt` (Phase 1): reuse its trait-building helpers.
  - `core/src/test/kotlin/gups/core/world/DisengageTest.kt`: how tests drive kills and flees.
  - `core/src/test/kotlin/gups/core/world/LevelingTest.kt`: how tests enable leveling and drive `awardXp`, `levelUp` and
    `levelDown`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Creation.kt`: enum values.
  - `core/src/main/kotlin/gups/core/world/Combat.kt`: `runDeath` (line ~394).
  - `core/src/main/kotlin/gups/core/world/Leveling.kt`: `levelUp` (line ~130) and `levelDownStep` (line ~239).
  - `core/src/main/kotlin/gups/core/world/Movement.kt`: `transition` (the `on_enter` dispatch at line ~60) and
    `teleport` (line ~108, inside its `deferRemoval` block).
  - `core/src/test/kotlin/gups/core/world/TraitEventsTest.kt`.
- **Do Not Touch:** `plugin-api/`, `SPEC.md`, `damage`, `heal`.

### Specification & Decision Record Changes

- None. The Phase 1 spec text already covers ordering and cascades.

### Implementation Blueprint

**Signatures & Types:**

```kotlin
// Creation.kt, added to TraitEvent
/** [bearer] landed the killing blow on `victim` (Actor). Not fired when there is no killer. */
KILLED,

/** [bearer] died. Field: `killer` (Actor or null). The handler can't prevent the death. */
DIED,

/** [bearer] gained or lost a level, once per step. Fields: `old_level` and `new_level` (Int). */
LEVEL_CHANGED,

/**
 * [bearer] arrived in a room by walking, fleeing or teleporting. Fields: `from_room` (Room) and
 * `direction` (String, or null for a teleport).
 */
ROOM_ENTERED,
```

**Algorithmic Steps & Invariants:**

1. Death (`runDeath`): between `dispatch(HookName.ON_DEATH, ...)` and `dispatchGlobal(HookName.ON_DEATH, ...)`, add
   `fireTraitEvent(victim, TraitEvent.DIED, mapOf("killer" to killer))`, then, only when `killer != null`,
   `fireTraitEvent(killer, TraitEvent.KILLED, mapOf("victim" to victim))`. The victim is still `inWorld` at this point
   (`removedThisPhase` doesn't change `inWorld`), so the helper's liveness check passes. `processDeath` already holds
   the victim in `dying`, so damage dealt by a handler can't start a second death.
2. Leveling: at both sites, after `dispatch(HookName.ON_LEVEL_CHANGE, a, a.room, levelCtx)` and before
   `dispatchGlobal(...)`, add `if (Feature.LEVELING in features) fireTraitEvent(a, TraitEvent.LEVEL_CHANGED,
   mapOf("old_level" to a.level - 1, "new_level" to a.level))` in `levelUp`, and the same with `a.level + 1` as the old
   level in `levelDownStep`. Use the same values the site puts in `levelCtx`; `HookContext`'s level fields are nullable,
   so don't read them back.
3. Movement: there are exactly two `on_enter` dispatches in `core/src/main`, both in `Movement.kt`.
   - In `transition`, after `dispatch(HookName.ON_ENTER, ...)`, add `fireTraitEvent(actor, TraitEvent.ROOM_ENTERED,
     mapOf("from_room" to from, "direction" to exit.direction.name))`. Walking (`Movement.kt`, the `w.transition(actor,
     exit)` call) and fleeing (`CombatCommands.kt`, `w.transition(..., reason = "flee")`) both go through it.
   - In `teleport`, inside the `deferRemoval` block after its `dispatch(HookName.ON_ENTER, ...)`, add the same call with
     `"direction" to null`.
   - `exit.direction` is the core value class `Direction`; pass `.name`, never the `Direction` itself.
4. Invariants: login, spawn and respawn placement call no `ROOM_ENTERED`. A killer or victim that isn't an actor with
   traits costs only an empty loop. With leveling off, `LEVEL_CHANGED` never fires.

### Test & Documentation Requirements

- In `TraitEventsTest.kt`, write first:
  - `DIED` fires for a victim with `killer` set, and with `killer` null for a death with no attacker (e.g., raw `damage`
    with no source).
  - `KILLED` fires for the killer with `victim`; it doesn't fire when there's no killer; a mob killer works.
  - Order at death: the victim's `DIED` runs before the killer's `KILLED`, both after the script `on_death` hook and
    before the script global `on_death` hook.
  - A `DIED` handler that deals damage to its bearer doesn't start a second death: `on_death` fires once.
  - `LEVEL_CHANGED` fires once per step: an `awardXp` that gains two levels gives two calls with consecutive old and new
    levels; a `levelDown` fires too. With leveling off (a `levelDownStep` call), it doesn't fire.
  - `ROOM_ENTERED` fires on a walk with the exit's `direction` as a `String` and the room left; on a flee; on a
    `teleport` with a null `direction` (run the pulse so the deferred removal happens); and doesn't fire on a refused
    move or on login.
- No Markdown changes in this phase.

### Exit Conditions

- [] `./gradlew :core:test --tests "gups.core.world.TraitEventsTest"` passes.
- [] `./gradlew check` passes with zero warnings.
- [] `grep -n "HookName.ON_ENTER" core/src/main/kotlin/gups/core/world/*.kt` lists two dispatch sites, and each is
  followed by one `ROOM_ENTERED` call.

---

## Phase 3: Plugin API, Adapter, Docs and Changelog

### Objective & Scope

- **In Scope:**
  - Mirror the six new events in `PluginTraitEvent`.
  - Teach the adapter to convert the new field types.
  - Document the events in `PLUGINS.md`, the §4.15 spec bullet and the `on_damage` note, and add a CHANGELOG line.
- **Out of Scope:** *Do not implement these.* New `PluginWorld` verbs, an affect event, world-declared handlers, a new
  sample plugin, and any `PluginApi.VERSION` change.

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt`: `PluginWorld.damage` (line 88), `heal` (line 96; it takes no
    source), `teleport` (line 136), `grantTrait` (line 147), `levelUp` (line 205), `PluginRoom` (line 306). These are
    how a handler or a test changes the world.
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`: `RoomFacade` (line 167), `ActorFacade` (line 215).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`: `plainEntity` (line ~690), the recursive conversion
    to copy.
  - `plugin-api/src/test/kotlin/gups/pluginapi/TraitCallbackTest.kt`: the scene helper (lines 29-60).
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`: the enum (line 413).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt`: the `fields` conversion (line ~334).
  - `plugin-api/src/test/kotlin/gups/pluginapi/TraitCallbackTest.kt`.
  - `docs/PLUGINS.md` (the paragraph at line ~334), `docs/SPEC.md` (§4.15 bullet near line 2936, and the note under
    `on_damage` near line 1270), `docs/CHANGELOG.md`.
- **Do Not Touch:** `core/` (finished), `samples/`.

### Specification & Decision Record Changes

- §4.15 "Trait callbacks" bullet: replace the example and field sentence with a list of the seven events (the existing
  one and the six new ones) and their fields as in the design table, using the plugin-side types (`PluginActor`,
  `PluginRoom`, `Long`, `String`, `Map<String, Long>` for `losses`, a list of `type`/`losses` maps for `parts`). Keep:
  "A throw records a `plugin` diagnostic." Add: handlers run synchronously where the event happens, for each simulated
  bearer, players and mobs (except `SKILL_RECOGNIZED`, which is players only); `LEVEL_CHANGED` needs leveling; and a
  handler that causes another event is cut off by the cascade depth cap.
- Under the `on_damage` note ("To react to one's own landed swing, or to being struck, use procs (§4.2): `on_damage`
  fires room-wide for any damage."): append "A plugin trait reacts to its bearer being damaged, by a swing or anything
  else, with the `DAMAGED` trait event (§4.15), which runs before that swing's procs."
- No decision record changes.

### Implementation Blueprint

**Signatures & Types:**

```kotlin
// PluginApi.kt: six new documented values, in this order, after SKILL_RECOGNIZED
enum class PluginTraitEvent {
  SKILL_RECOGNIZED,
  DAMAGED,
  HEALED,
  KILLED,
  DIED,
  LEVEL_CHANGED,
  ROOM_ENTERED,
}
```

Document each value's fields in its KDoc with plugin-side types: `DAMAGED`: `source` (`PluginActor?`), `amount`
(`Long`), `type` (`String`), `losses` (`Map<String, Long>`), `parts` (`List<Map<String, Any?>>`, each with `type` and
`losses`). `HEALED`: `source` (`PluginActor?`), `vital` (`String`), `amount` and `requested` (`Long`). `KILLED`:
`victim` (`PluginActor`). `DIED`: `killer` (`PluginActor?`). `LEVEL_CHANGED`: `old_level`, `new_level` (`Long`).
`ROOM_ENTERED`: `from_room` (`PluginRoom`), `direction` (`String?`).

**Algorithmic Steps & Invariants:**

1. Add the enum values. The adapter maps names with `TraitEvent.valueOf(it.key.name)` and
   `PluginTraitEvent.valueOf(event.name)`, so the six names must match the core enum exactly. A mismatch throws at
   registration.
2. In `Adapters.kt`, replace the `fields.mapValues { ... when (v) { is Actor ...; is Int ...; else -> v } }` block with
   a small private recursive function, for example `private fun plain(v: Any?, w: World): Any?`, shaped like
   `plainEntity` in `PluginHost.kt`:
   - `Actor` becomes `ActorFacade(v, w)`; `Room` becomes `RoomFacade(v, w)`; `Int` becomes `Long`.
   - `Map<*, *>` becomes a map with the same keys and `plain` applied to each value; `List<*>` becomes a list with
     `plain` applied to each element.
   - Anything else is returned as it is.
3. Invariant: a handler never sees a core type (`Actor`, `Room`, `Direction`, `DamagePart`, `DamageType`).

### Test & Documentation Requirements

- In `TraitCallbackTest.kt`, write first, using the existing `scene` helper (extend it, or add a second helper that
  grants a trait to a mob or a player and gives `ann` something to do):
  - `DAMAGED` through a real attack: the victim's handler sees `Long` and `PluginActor` fields, `type` as a `String`,
    `losses` as `Map<String, Long>`, and `parts` as a list of maps whose `losses` are `Map<String, Long>`.
  - `HEALED` through `PluginWorld.heal` from another handler or through a heal skill: `amount` and `requested` are
    `Long`, `source` is null for `PluginWorld.heal`.
  - `DIED` and `KILLED` through a real kill: `killer` and `victim` are `PluginActor`s.
  - `LEVEL_CHANGED` through `PluginWorld.levelUp`, in a world with leveling on: `old_level` and `new_level` are `Long`.
  - `ROOM_ENTERED` through a walk: `from_room` is a `PluginRoom`, `direction` a `String`; through `teleport`:
    `direction` is null.
  - Re-entrancy through the plugin API: two bearers' `DAMAGED` handlers call `call.world.damage` on each other; the run
    ends and `w.diagnostics` has a `cascade` entry.
  - A mob bearer receives a trait event (give the wolf the trait through its prototype or `grantTrait`).
- `PLUGINS.md`: extend the "Traits can also react." paragraph. List the events with a one-line meaning each, state that
  handlers run synchronously where the event happens, right after the entity hooks there (and, at death and level
  change, before the global hooks and plugin listeners), that bearers can be players or mobs, and that a handler chain
  is cut off by the cascade depth. Add a short example of a `DAMAGED` handler. Put the Kotlin example in its own fenced
  block at column 0 (not inside a list), as `AGENTS.md` warns.
- `CHANGELOG.md`: under the existing "### Recognizing skills and trait callbacks" heading in `api_version 1
  (unreleased)`, add one bullet: plugin trait events `DAMAGED`, `HEALED`, `KILLED`, `DIED`, `LEVEL_CHANGED` and
  `ROOM_ENTERED`, run synchronously for players and mobs.
- Run `./gradlew markdownFormat` and check code-block indentation in the files you changed.

### Exit Conditions

- [] `./gradlew :plugin-api:test --tests "gups.pluginapi.TraitCallbackTest"` passes.
- [] `./gradlew markdownFormat` run; `./gradlew markdownCheck` passes.
- [] `./gradlew check` passes with zero warnings, including the module-graph check.
- [] `grep -n "DAMAGED" docs/SPEC.md docs/PLUGINS.md docs/CHANGELOG.md` finds the new text in all three files.

---

## Phase 4: Finalization & Backlog Update

- In `docs/DESIGN_NEEDED.md`, under `### More trait event callbacks`, mark the questions this milestone resolved as
  completed (events, re-entrancy and inline timing, ordering against hooks and listeners, relation to procs), but do not
  delete the entry. Leave the last question ("Should world content get a way to declare traits, or player-scoped
  hooks...") open and in place, and add a line noting that the events and dispatch sites it would build on now exist. If
  the entry has no completed-marker convention, look at how other finished entries in the repository mark completion; if
  there is none, prefix each resolved question with "(Done in Milestone 48)".
- Mark an affect-gained and affect-lost event as not done: add it as a new open line in the same entry, since this
  milestone left it out.
- Run `./gradlew markdownFormat`, then the full suite: `./gradlew check` (outside the sandbox). Confirm no doc or code
  comment mentions behavior this milestone didn't implement.
- Do not delete any entry. Deletion happens in a separate designer review.
