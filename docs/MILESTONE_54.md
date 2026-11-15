# Milestone 54: Removing a Mob Without Killing It

**Prerequisites:** [M53](MILESTONE_53.md) must be finished first. Where line numbers below are wrong, find the symbol by
name.

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** [DESIGN_NEEDED.md](DESIGN_NEEDED.md): `## Mobs` → `### Removing a mob without killing it`.
  A related idea, a hook when an item is destroyed, was split into its own entry (`## Items` → `### A hook when an item
  is destroyed`) and is **not** part of this milestone.

- **Design Summary:**

  - **One engine helper, `World.despawn(mob)`,** takes a mob out of the world without killing it. In order: clear its
    activity, end its engagements (collecting the opponents it frees), fire `on_despawn`, remove it (with everything it
    carries and wears), then fire `on_disengage` with `"stop"` for the freed opponents. This mirrors the order death
    uses in `runDeath` (`core/.../world/Death.kt`), minus the messages, corpse, loot and XP.
  - **A dedicated verb, `despawn(mob)`, on `ctx` and on `PluginWorld`.** Same name on both, added in the same phase, so
    `CtxParityTest` needs no exception.
  - **New hook `on_despawn`** (kernel). It fires on the mob's own hook, then the room's hook, then globally (script
    `global_hook` and plugin `onEvent` listeners). Observers in the room do **not** get it. `ctx.actor` is the mob. It
    fires after the engagements are unlinked and **before** removal, so hooks still see the mob in its room with its
    belongings. No veto. While it runs the mob can't be engaged, damage can't kill it, and despawning it again does
    nothing. `on_death` is **not** reused: global `on_death` listeners, the `DIED`/`KILLED` trait events and the
    standing `kill` event would all treat it as a kill.
  - **Silent.** The verb prints nothing, like the other world-changing `ctx` verbs (doors, `teleport`, `destroy`,
    `spawn`). Content narrates with the existing message verbs.
  - **Belongings are destroyed with the mob.** No `drop` option. Content that wants an item kept moves it first, before
    calling `despawn` or inside `on_despawn`. No `item_events` row is needed: the audit trail only covers items in a
    player's custody, and a mob's items aren't.
  - **Spawn-rule mobs are allowed:** `removeActor` already calls `releaseSpawn`, so the zone respawns them on schedule.
  - **Owned pets are allowed,** with no special handling: removed for good, as at death, and `on_owner_change` does not
    fire.
  - **Errors:** a player, an actor not in the world, or a mob whose death is running.
  - **Retrofit:** a summon fading (`dismissSummon`) and a debug mob expiring (`despawnDebug`) move onto `World.despawn`.
    Both keep the "{actor} fades away." sight line, sent first, and now fire `on_despawn` and `on_disengage` `"stop"`.
    The debug path's raw `echo` becomes the kernel `affect.fades` message through `tellSight`, as the summon uses. A
    **reload** removal (`Reconcile.kt`'s private helper, renamed from `despawn` to `removeMobForReload` in Phase 1 so it
    doesn't clash with the new public `World.despawn`) stays silent and hook-free: it runs mid-reload, while prototypes
    and hooks are being swapped.
  - **Debug mobs at logout:** `quit` (`Sessions.kt`) calls `despawnDebugMobs` before the spawner is removed. Phase 4
    moves that call to after the spawner has left, so the hooks that despawn now fires never see a half-gone player.
- **Specification Impact:** §4.3 (`summon` primitive row), §4.4 (hook table, `on_disengage` paragraph, new `on_despawn`
  paragraph), §4.5 (verb table, new `despawn` bullet), §4.9 (global hooks paragraph, debug mob bullet), §4.15 (facade
  verb list, event listeners bullet), the feature table's kernel row (~816).
- **Decision Record Updates:** None.

### Conventions for every phase

1. Write the tests first, run them and watch them fail, then implement.
2. Comments explain *why*, never cite spec sections or decision IDs. All Kotlin warnings are errors.
3. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation.
4. In every phase, regenerate the API files before the phase's final `./gradlew check` (an empty diff is fine):
   - `./gradlew :cli:test -Dgups.updateGolden=true` rewrites `cli/src/test/resources/api.golden.json` and
     `cli/src/test/resources/docs-golden/`.
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` rewrites
     `docs/api/api-1.json`.
   - Read the diff: only what the phase names may change.
5. CHANGELOG: Phase 1 adds `### Removing a mob without killing it` at the top of `## api_version 1 (unreleased)` in
   `docs/CHANGELOG.md`, above `### Plugin facade parity with ctx`. Each later phase appends its bullets. Don't bump
   `api_version`.
6. Never read or touch `local-world/`. Scratch files go in `local-world-test/`, which you delete when done.
7. Messages given in quotes below are exact: tests assert them.
8. After changing code, run CodeScene `code_health_review` on the changed files and fix any drop.

---

## Phase 1: `World.despawn` and the `on_despawn` hook

### Objective & Scope

- **In Scope:** the engine helper `World.despawn(mob)`, its re-entry guard, `HookName.ON_DESPAWN`, its script hook
  catalog entry, and its registration as a script global hook. Spec text for the hook.
- **Out of Scope:** *Do not implement these; later phases do.* The `ctx.despawn` verb, the facade `despawn`, the plugin
  `onEvent("on_despawn")` listener, and moving the summon and debug paths onto the helper. In `Reconcile.kt`, only
  rename the private helper (see step 7 below); change nothing else there.

### Context Working Set

- **Read / Reference Only:** `core/src/main/kotlin/gups/core/world/Death.kt` (`processDeath`, `runDeath`,
  `wipeActivity`, `fireDeathHooks`: the order to mirror); `core/.../world/World.kt` (`removeActor` ~700, `dying` ~332,
  `removedThisPhase` ~330); `core/.../world/Engagement.kt` (`unlinkAll` ~131, `fireDisengage` ~141);
  `core/.../world/Hooks.kt` (`dispatch` ~412, `dispatchGlobal` ~459, the `ON_OWNER_CHANGE` and `ON_DISENGAGE` entries
  ~133–161 for KDoc style); `script/src/main/kotlin/gups/script/HookCatalog.kt` (`ON_DEATH` ~209 and `ON_DISENGAGE` ~282
  entries); `script/src/test/kotlin/gups/script/GlobalHookTest.kt` (fixture pattern);
  `core/src/test/kotlin/gups/core/world/CombatTest.kt` (`a timed summon fades away…` ~397, `Fixture` usage).
- **Files to Modify / Create:**
  - create `core/src/main/kotlin/gups/core/world/Despawn.kt`
  - `core/src/main/kotlin/gups/core/world/World.kt` (one new set)
  - `core/src/main/kotlin/gups/core/world/Hooks.kt` (enum entry)
  - `core/src/main/kotlin/gups/core/world/Engagement.kt` (`canEngage`)
  - `core/src/main/kotlin/gups/core/world/Death.kt` (`runDeath` guard)
  - `script/src/main/kotlin/gups/script/HookCatalog.kt`
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`GLOBAL_HOOK_NAMES`)
  - `core/src/main/kotlin/gups/core/world/Reconcile.kt` (rename only)
  - create `core/src/test/kotlin/gups/core/world/DespawnTest.kt`
  - create `script/src/test/kotlin/gups/script/DespawnHookTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `plugin-api/`, `Affects.kt` (`dismissSummon`), `DebugSpawns.kt`, `Sessions.kt`, `Ctx*.kt`;
  `Reconcile.kt` beyond the rename.

### Specification & Decision Record Changes

Approved wording. Apply exactly, then run `markdownFormat`.

- §4.4 hook table: insert a row directly after the `on_death` row: ``| `on_despawn` | — | Mob, Room, global (§4.9) |
  `actor` (the mob) |``.
- §4.4 `on_disengage` paragraph: replace ``and `"stop"` otherwise (`stop_combat`, an opponent leaving the room, or a
  quit)`` with ``and `"stop"` otherwise (`stop_combat`, an opponent leaving the room, an opponent despawning (§4.5), or
  a quit)``.
- §4.4: add a new paragraph directly after the `on_disengage` paragraph:

  > **`on_despawn`** fires when a mob leaves the world without dying. `ctx.actor` is the mob. It fires on the mob's own
  > hook, then the room's, then globally; observers in the room don't get it. It runs after the mob's engagements have
  > ended and before it is removed, so the mob is still in its room with everything it carries and wears, and a hook may
  > move items off it. It can't be vetoed and its return value is ignored. While it runs, nothing can engage the mob,
  > damage can't kill it, and despawning it again does nothing. The opponents it freed get `on_disengage` with `"stop"`
  > once it has gone. A mob removed by a reload (§4.11) gets no hook.

- §4.9 **Global hooks** paragraph:
  - In the list of hooks that can be global, insert `` `on_despawn` `` after `` `on_death` ``: "…`on_disengage`,
    `on_death`, `on_despawn` and `on_skill_use` are the hooks that can be global."
  - After the sentence that ends "…or `None`." (the global `on_death` sentence), add: "A global `on_despawn` fires once
    for every mob that despawns, after its own and its room's `on_despawn` hooks, with the same ctx fields; `ctx.self`
    and `ctx.actor` are the mob."
  - In "`on_encumbrance_change`, `on_group_change`, `on_owner_change` and `on_death` belong to the kernel", add
    `on_despawn`: "…`on_owner_change`, `on_death` and `on_despawn` belong to the kernel…".
- Feature table kernel row (~816): after `` pet ownership (`set_owner`, `clear_owner`, `on_owner_change`), `` insert ``
  mob removal (`on_despawn`), ``. Phase 2 adds the verb to it.

### Implementation Blueprint

#### Signatures & Types

```kotlin
// World.kt, next to `dying`
internal val despawning = HashSet<EntityId>()

// Hooks.kt, HookName, directly after ON_DEATH
/**
 * Invoked when a mob leaves the world without dying, on the mob, then its room, then globally.
 *
 * [HookContext.actor] is the mob. It runs before the mob is removed, so the mob still holds its
 * belongings; the opponents it freed hear `on_disengage` with "stop" afterwards.
 */
ON_DESPAWN("on_despawn"),

// Despawn.kt
/** True while [a]'s `on_despawn` hooks run, before it is removed. */
fun World.isDespawning(a: Actor): Boolean

/**
 * Takes [mob] out of the world without killing it: no corpse, loot or XP. Its belongings go with
 * it, and a spawn rule tracking it respawns it on schedule. Calling it again while its
 * `on_despawn` hooks run does nothing.
 */
fun World.despawn(mob: Mob)
```

#### Algorithmic Steps & Invariants

1. `World.despawn(mob)`:
   1. `require(mob.inWorld) { "despawn: ${mob.name} is not in the world" }`.
   2. `require(!isDying(mob)) { "despawn: ${mob.name} is dying" }`.
   3. `if (!despawning.add(mob.id)) return` — re-entry from an `on_despawn` hook is a no-op.
   4. In `try { … } finally { despawning.remove(mob.id) }`:
      1. `val room = mob.room`.
      2. Clear activity exactly as `wipeActivity` in `Death.kt` does (contributions, `endActivity(mob, TaskEnd.GONE)`,
         action queue, wait state fields). Make `wipeActivity` `internal` and call it rather than copying it.
      3. `val freed = unlinkAll(mob) - mob`.
      4. `val ctx = HookContext(actor = mob)`. Fire `dispatch(HookName.ON_DESPAWN, mob, null, ctx)`, then
         `dispatch(HookName.ON_DESPAWN, room, null, ctx)`, then `dispatchGlobal(HookName.ON_DESPAWN, ctx)`. Passing
         `room = null` to `dispatch` keeps observers and items out, so only the mob and the room run their hook.
      5. If a hook moved the mob to another room (`teleport`), that's fine: `removeActor` uses the mob's current room.
         If `!mob.inWorld` here (should not happen given the guards), return.
      6. `removeActor(mob)` — this releases the spawn rule, marks the mob and its items removed, and adds the mob to
         `removedThisPhase`, so a combat-round tier skips it.
      7. `fireDisengage(freed, "stop")`.
2. `Engagement.kt` `canEngage`: also refuse when either actor `isDespawning`. Update the KDoc's reason to cover both.
3. `Death.kt` `runDeath`: at the top, `if (isDespawning(victim)) return` — damage dealt by an `on_despawn` hook can't
   start a death for a mob that is about to vanish. Leave its condition as it is; the mob is removed moments later.
4. `HookCatalog.kt`: add a `HookSpec` after `ON_DEATH`: `attachTo = listOf(M, R)`, `ctxFields = listOf("self", "actor",
   "room", "pulse", "rng")`, `doc = "Fires when a mob leaves the world without dying, before it goes; also a global hook
   (lib and rules packages)."`.
5. `Declarations.kt` `GLOBAL_HOOK_NAMES`: insert `"on_despawn"` after `"on_death"`. The error message for a bad name is
   built from the list, so it updates itself; fix any test that asserts the old full message.
6. Don't fire trait events, standing events or `on_owner_change`.
7. `Reconcile.kt`: rename `private fun World.despawn(m: Mob)` (~257) to `removeMobForReload` and update its two callers
   (~84, ~131). Without this, the private and the new public `World.despawn(Mob)` in the same package clash. Behavior
   stays identical: no hooks, no messages.

### Test & Documentation Requirements

- `DespawnTest.kt` (core, `Fixture` as in `CombatTest`):
  - A mob spawned by a spawn rule: after `despawn`, `!inWorld`, the room has no items (no corpse), and the rule has a
    respawn deadline; running past it refills the rule.
  - A mob carrying an item and wearing one: both are gone from the world (not on the floor).
  - A mob fighting a player: after `despawn`, the player's `fighting` is empty.
  - An owned pet: `despawn` removes it; `petsOf(owner)` is empty.
  - `despawn` on a mob not in the world throws `IllegalArgumentException` with `"despawn: <name> is not in the world"`.
  - XP: a player fighting the mob gains 0 XP.
- `DespawnHookTest.kt` (script, fixture pattern from `GlobalHookTest`; call `world.despawn(mob)` from Kotlin):
  - The mob's own `on_despawn`, the room's `on_despawn` and a `global_hook("on_despawn", …)` in a lib package each run
    once, in that order (record via a list attribute or `send`), each seeing `ctx.actor` as the mob and the mob still in
    the room holding its item.
  - Another mob in the room with an `on_despawn` hook does **not** run it.
  - An `on_despawn` hook that `ctx.move_item`s the mob's item to the room keeps the item on the floor afterwards.
  - An opponent's `on_disengage` runs with `ctx.event == "stop"`, after the mob is gone (`ctx.actor` is the opponent;
    the hook records that the despawned mob's view is no longer in the room).
  - An `on_despawn` hook that `ctx.damage`s the mob for more than its hp produces no corpse and no `on_death`. (The
    re-entry no-op is tested in Phase 2, once `ctx.despawn` exists.)
  - An `on_despawn` hook that calls `ctx.start_combat(mob, player)` leaves neither engaged (`World.startCombat` returns
    early through `canEngage`). Phase 2 turns this into an error; it updates this test.
  - `global_hook("on_despawn", fn)` loads; `global_hook("on_tick", fn)` still fails with the list in its message.
- `CHANGELOG.md`: create the subsection; bullet: "New hook `on_despawn` fires on a mob that leaves the world without
  dying, then its room, then globally, before the mob is removed."

### Exit Conditions

- [] `./gradlew :core:test :script:test` passes.
- [] `./gradlew check` passes outside the sandbox; API files regenerated (the hook catalog adds `on_despawn`).
- [] `grep -n "on_despawn" docs/SPEC.md` shows the table row, the paragraph, and the §4.9 additions.

---

## Phase 2: The `despawn` verb on `ctx` and `PluginWorld`

### Objective & Scope

- **In Scope:** `ctx.despawn(mob)`, its `DocExamples` entry, `PluginWorld.despawn(mob)` and its engine implementation,
  `ctx.start_combat`/facade `startCombat` refusing a despawning actor, spec and docs.
- **Out of Scope:** *Do not implement these; later phases do.* The plugin `onEvent("on_despawn")` listener, the summon
  and debug retrofit.

### Context Working Set

- **Read / Reference Only:** `script/src/main/kotlin/gups/script/CtxSimulation.kt` (`set_owner` ~68, `clear_owner` ~87:
  the verb pattern); `script/src/main/kotlin/gups/script/DocExamples.kt` (`"ctx.set_owner"` ~1034);
  `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt` (`setOwner` ~547, `clearOwner` ~551);
  `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt` (`actorOf` ~650, `mobOf` ~658, `setOwner` ~1329,
  `startCombat` ~1350); `plugin-api/src/test/kotlin/gups/pluginapi/FacadeMobsTest.kt` (fixture);
  `plugin-api/src/test/kotlin/gups/pluginapi/CtxParityTest.kt`.
- **Files to Modify / Create:** `CtxSimulation.kt`, `CtxCombat.kt` (`start_combat` ~192), `DocExamples.kt`, both
  `Facade.kt` files, a ctx test (add to `script/src/test/kotlin/gups/script/OwnerVerbTest.kt`, or create
  `DespawnVerbTest.kt` next to it), `FacadeMobsTest.kt`, `docs/SPEC.md`, `docs/PLUGINS.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `core/` (Phase 1 is done), `PluginRegistryImpl.kt`, `PluginHost.kt`, `Affects.kt`, `DebugSpawns.kt`.

### Specification & Decision Record Changes

- §4.5 verb table: add a row directly after the `Pets` row: ``| Mobs | `despawn(mob)` |``.
- Feature table kernel row (~816): change `` mob removal (`on_despawn`), `` to `` mob removal (`despawn`, `on_despawn`),
  ``.
- §4.4 `on_despawn` paragraph (Phase 1): change "fires when a mob leaves the world without dying." to "fires when a mob
  leaves the world without dying (`despawn`, §4.5)."
- §4.5 verb semantics: add a bullet directly after the `summon` bullet:

  > - **`despawn`:** `ctx.despawn(mob)` takes `mob` out of the world without killing it and returns `None`. Its
  >   engagements end and its action, task and queue are cleared; `on_despawn` (§4.4) fires; the mob and everything it
  >   carries and wears leave the world; then the opponents it freed get `on_disengage` with `"stop"`. Nothing is
  >   printed, no corpse is made, no loot is rolled and no XP is awarded: content that wants a message sends one, and
  >   content that wants to keep an item moves it off the mob first. A mob a spawn rule tracks leaves its rule, which
  >   respawns it on schedule (§5.4). An owned pet is gone for good, as at death, and `on_owner_change` doesn't fire. A
  >   player, an actor not in the world, or a mob whose death is running is an error; despawning a mob whose
  >   `on_despawn` hooks are running does nothing. Kernel.

- §4.15 Facade bullet: add `` `despawn` `` to the verb list after `` `clearOwner` ``. After the sentence that ends "…at
  track index 0." (the `spawnMob` sentence) add: "`despawn(mob)` removes a mob as `ctx.despawn` does."

### Implementation Blueprint

#### Signatures & Types

```kotlin
// CtxSimulation.kt, after clear_owner
@StarlarkMethod(
  name = "despawn",
  doc =
    "Takes a mob out of the world without killing it: no corpse, loot or XP, and nothing printed. What it carries and wears goes with it; on_despawn fires first. Returns None.",
  parameters = [Param(name = "mob", named = true, doc = "The mob to remove.")],
)
fun despawn(mob: Any): Any

// plugin-api Facade.kt (PluginWorld), after clearOwner
/**
 * Takes [mob] out of the world without killing it, as `ctx.despawn` does: no corpse, loot or XP,
 * nothing printed, and its belongings go with it. A player, a mob not in the world, or a dying mob
 * is an error.
 */
fun despawn(mob: PluginActor): Unit = throw UnsupportedOperationException("despawn is not supported")
```

#### Algorithmic Steps & Invariants

1. `ctx.despawn`: inside `verb("despawn")`:
   - `val m = (mob as? ActorView)?.actor as? Mob ?: fail("despawn(mob): must be a mob")`.
   - `if (!m.inWorld) fail("despawn(mob): ${m.name} is not in the world")`.
   - `if (world.isDying(m)) fail("despawn(mob): ${m.name} is dying")`.
   - `world.despawn(m)`; return `Starlark.NONE`.
2. Host `WorldFacade.despawn`: `val m = mobOf(mob, "despawn(mob)")` (throws for players and for a mob no longer in the
   world, via `actorOf`); `require(!world.isDying(m)) { "despawn(mob): ${m.name} is dying" }`; `world.despawn(m)`.
3. `ctx.start_combat` (`CtxCombat.kt` ~192) and facade `startCombat` (~1356): next to the `isDying` check, add `if
   (world.isDespawning(d)) fail("start_combat(): ${d.name} is leaving the world")` /
   `IllegalArgumentException("startCombat(): ${d.name} is leaving the world")`.
4. `DocExamples.kt`: add `"ctx.despawn"` after `"ctx.clear_owner"`, mirroring the `set_owner` example: a room command
   `dismiss` that sends "The wolf slinks away." to the room with `ctx.echo` and then calls
   `ctx.despawn(ctx.here("wolf"))`; the test asserts the text and that the wolf is no longer among the room's actors
   (list the room's actors through the harness; don't read fields of the removed wolf's view, which may raise). Run the
   example through `ApiModelTest`.
5. `CtxParityTest`: no change expected (`ctx.despawn` ↔ `despawn`). If the test reports a gap, the facade name is wrong.

### Test & Documentation Requirements

- ctx tests: `despawn` of a wolf removes it and returns `None`; a player argument fails with `"despawn(mob): must be a
  mob"`; despawning the same view twice fails the second time with `"despawn(mob): a wolf is not in the world"`; a
  `despawn` call from the mob's own `on_death` hook fails with `"despawn(mob): a wolf is dying"`; nothing is sent to
  players in the room; a mob whose own `on_despawn` calls `ctx.despawn(ctx.self)` is removed once, with no error and the
  hook run once.
- Update Phase 1's `DespawnHookTest` case for `ctx.start_combat` inside `on_despawn`: it now records a script diagnostic
  containing `"start_combat(): <name> is leaving the world"`, and still leaves neither engaged.
- `FacadeMobsTest.kt`: `despawn(mob)` removes it (`PluginActor.inWorld` is false, and the room's `actors` no longer
  lists it); a player throws `IllegalArgumentException("despawn(mob): must be a mob")`; a second call throws; a
  spawn-rule mob is respawned after its deadline.
- `PLUGINS.md`, `## What plugins can touch` → the **mobs:** bullet: add `despawn(mob)` with one sentence: "removes a mob
  as `ctx.despawn` does: no corpse, loot or XP, nothing printed, and its belongings go with it."
- CHANGELOG bullets: "`ctx.despawn(mob)` takes a mob out of the world without killing it…" and "The plugin facade gains
  `despawn(mob)`, following `ctx.despawn`."

### Exit Conditions

- [] `./gradlew :script:test :plugin-api:test` passes, including `CtxParityTest` and `ApiModelTest`.
- [] `./gradlew check` passes outside the sandbox; API files regenerated (the diff adds `ctx.despawn` and the facade
  member only).

---

## Phase 3: Plugin event listener for `on_despawn`

### Objective & Scope

- **In Scope:** `onEvent("on_despawn", …)` for plugins, with no extra fields; spec and `PLUGINS.md`.
- **Out of Scope:** *Do not implement these; later phases do.* The summon and debug retrofit.

### Context Working Set

- **Read / Reference Only:** `core/.../world/Hooks.kt` `dispatchGlobal` (listeners already run after script global hooks
  for any `HookName`); `plugin-api/src/test/kotlin/gups/pluginapi/EventListenerTest.kt` (pattern for an
  `on_owner_change` or `on_death` listener test).
- **Files to Modify / Create:** `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginRegistryImpl.kt` (`EVENT_NAMES`
  ~427), `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt` (event fields `when` ~95), the `onEvent` KDoc in
  `plugin-api/src/main/kotlin/gups/pluginapi/` (find it with `grep -rn "fun onEvent"`), `EventListenerTest.kt`,
  `docs/SPEC.md`, `docs/PLUGINS.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `core/`, `script/`.

### Specification & Decision Record Changes

- §4.15 **Event listeners** bullet: change the list to "`on_level_change`, `on_encumbrance_change`, `on_group_change`,
  `on_owner_change`, `on_disengage`, `on_death`, `on_despawn` and `on_skill_use`", and in the fields sentence, after
  "`killer` (or null) for `on_death`;" insert "none for `on_despawn`, whose `actor` is the mob, still in the world;".

### Implementation Blueprint

1. `EVENT_NAMES`: insert `HookName.ON_DESPAWN` after `HookName.ON_DEATH`.
2. `PluginHost` fields `when`: add `HookName.ON_DESPAWN -> emptyMap()` explicitly (not via `else`), so the mapping is
   visibly decided.
3. Update the `onEvent` KDoc's list of event names if it has one, and any "seven events" wording (it becomes eight).

### Test & Documentation Requirements

- `EventListenerTest.kt`: a plugin listening to `on_despawn` runs once when `world.despawn(mob)` is called, after a
  script `global_hook("on_despawn")` (record order), with `actor` the mob (`inWorld` still true, still holding its item)
  and empty `fields`. A listener that throws records a `plugin` diagnostic and the mob is still removed.
- `PLUGINS.md` `### Engine event listeners`: "The seven events" → "The eight events", add "`on_despawn` (no fields; the
  mob is still in the world, holding its belongings)" after `on_death`.
- CHANGELOG bullet: "Plugins can listen to `on_despawn` with `onEvent`."

### Exit Conditions

- [] `./gradlew :plugin-api:test` passes.
- [] `./gradlew check` passes outside the sandbox; API files regenerated.

---

## Phase 4: Summon fades and debug expiry use `World.despawn`

### Objective & Scope

- **In Scope:** `dismissSummon` (`Affects.kt` ~277) and `despawnDebug` (`DebugSpawns.kt` ~30) call `World.despawn`, so
  both fire `on_despawn` and `on_disengage` `"stop"`; the debug path's message moves to the kernel `affect.fades`
  message through `tellSight`; `quit` despawns debug mobs after the spawner has left. Spec text.
- **Out of Scope:** *Do not implement these.* `Reconcile.kt`'s `removeMobForReload` stays exactly as it is (silent,
  hook-free; it runs mid-reload). Don't touch logout stowing of pets (`stowPets`), which is not a despawn.

### Context Working Set

- **Read / Reference Only:** `core/.../world/Despawn.kt` (Phase 1); `core/.../world/KernelMessages.kt` (`AFFECT_FADES`
  ~181); `core/src/test/kotlin/gups/core/world/CombatTest.kt` (`a timed summon fades away…` ~397);
  `core/src/test/kotlin/gups/core/world/ToolsTest.kt` (`debug spawns are never saved…` ~123).
- **Files to Modify / Create:** `core/src/main/kotlin/gups/core/world/Affects.kt`,
  `core/src/main/kotlin/gups/core/world/DebugSpawns.kt`, `core/src/main/kotlin/gups/core/world/Sessions.kt` (`quit`
  ~176), `CombatTest.kt`, `ToolsTest.kt`, `script/src/test/kotlin/gups/script/DespawnHookTest.kt`, `docs/SPEC.md`,
  `docs/CHANGELOG.md`.
- **Do Not Touch:** `Reconcile.kt`, `plugin-api/`, `Ctx*.kt`.

### Specification & Decision Record Changes

- §4.3 `summon` primitive row: replace exactly this sentence: "With a `duration`, each gets a `summoned` affect; when it
  expires or is removed, the mob fades away, disengages and leaves the world." with: "With a `duration`, each gets a
  `summoned` affect; when it expires or is removed, the room sees the mob fade away and it is despawned as by `despawn`
  (§4.5): `on_despawn` fires and its opponents get `on_disengage` with `"stop"`." Leave the rest of the row unchanged.
- §5.7 sight-channel table (~4153): in the first column's list, change "a summon fading" to "a summon or debug mob
  fading".
- §4.9 builder `@spawn` bullet: change "A debug mob despawns at the first world tick after its spawner is gone, or after
  30 minutes." to "A debug mob fades away as a summon does (§4.3), and is despawned as by `despawn` (§4.5), at the first
  world tick after its spawner is gone, or after 30 minutes. It also goes when its spawner logs out."
- §4.4 `on_despawn` paragraph (Phases 1–2): change "fires when a mob leaves the world without dying (`despawn`, §4.5)"
  to "fires when a mob leaves the world without dying: `despawn` (§4.5), a summon fading (§4.3) or a debug mob expiring
  (§4.9)".

### Implementation Blueprint

```kotlin
// Affects.kt: the new dismissSummon
private fun World.dismissSummon(a: Actor) {
  if (a !is Mob || !a.inWorld || isDying(a) || isDespawning(a)) return
  tellSight(a.room, "exit", a, msg(KernelMsg.AFFECT_FADES, "actor" to a.name.cap()), a)
  despawn(a)
}
```

1. `Affects.kt` `dismissSummon(a)`: replace it with the snippet above. A summon whose death is already running is left
   to the death, which removes it.

2. `DebugSpawns.kt` `despawnDebug(m)`: guard `!m.inWorld || isDying(m) || isDespawning(m)` → return; mark the mob's
   items debug (keep the existing loop) **before** the message; send the `AFFECT_FADES` line with `tellSight(room,
   "exit", m, …, m)` exactly as `dismissSummon` does; then `despawn(m)`. Remove the raw `echo` and the direct
   `disengageAll`/`removeActor` calls.

3. Both paths now clear activity and the action queue (via `World.despawn`); before, they didn't. That's intended.

4. `despawnDebugMobs` iterates a filtered copy already; keep it a copy, since hooks can change `actorIndex`.

5. `Sessions.kt` `quit`: move `despawnDebugMobs(player)` from before the checkpoint to after `echo(room,
   msg(KernelMsg.QUIT_LEFT, …))` and before `fireDisengage(freed, "stop")`. The spawner is then fully gone when the
   debug mobs' `on_despawn` hooks run. `despawnDebugMobs` matches on `debugOwner == spawner.id`, which still works after
   removal. Debug mobs are never saved, so leaving them out of the checkpoint changes nothing.

### Test & Documentation Requirements

- `CombatTest` `a timed summon fades away…`: keep its assertions (disengaged player, no corpse); add that the player
  receives the fade line ("A goblin fades away." or the fixture's goblin name). Hook behavior is tested in the script
  module below.
- `ToolsTest` debug test: the line players see is unchanged ("<Name> fades away."); assert it is now delivered through
  sight (a blind observer in the room doesn't see it, if the fixture supports blindness; otherwise skip that check).
- `DespawnHookTest.kt` (script): a summon with a `duration` fighting a player — when its affect expires, the summon's
  `on_despawn` runs once and the player's `on_disengage` runs with `"stop"`. A debug mob whose spawner logs out runs its
  `on_despawn`. A reload that deletes a mob's prototype runs **no** `on_despawn` (use the reload pattern in
  `script/src/test/kotlin/gups/script/ReloadTest.kt`).
- CHANGELOG bullet: "A summon fading and a debug mob expiring now fire `on_despawn`, and their opponents get
  `on_disengage` with `"stop"`. A debug mob's fade line is now a sight message."

### Exit Conditions

- [] `./gradlew :core:test :script:test` passes.
- [] `./gradlew check` passes outside the sandbox; API files regenerated (empty diff expected).
- [] `grep -n "removeActor" core/src/main/kotlin/gups/core/world/Affects.kt
  core/src/main/kotlin/gups/core/world/DebugSpawns.kt` finds nothing.

---

## Phase 5: Finalization & Backlog Update

- End-to-end check with `gups scenario` (see `AGENTS.md`): copy `template-world` to `local-world-test/world`, add a room
  command or a lib `global_hook("on_despawn")` that sends a line, spawn a mob with `ctx.spawn` and `despawn` it from a
  command; confirm in the transcript that no corpse appears, the player in the room sees only the content's line, and a
  fight with that mob ends.
- In `docs/DESIGN_NEEDED.md`, mark the entry completed without deleting it: change the heading `### Removing a mob
  without killing it` to exactly `### Removing a mob without killing it (Done in Milestone 54)`. Leave `### A hook when
  an item is destroyed` unchanged.
- Re-read the §4.15 Facade bullet and `PLUGINS.md` against `plugin-api/.../Facade.kt`: `despawn` is named in both.
- Confirm the CHANGELOG subsection `### Removing a mob without killing it` has a bullet for every phase.
- Run `./gradlew check` and `./gradlew markdownCheck` outside the sandbox; both must pass.
- Run `pre_commit_code_health_safeguard`; it must pass.
- Delete `local-world-test/`.
