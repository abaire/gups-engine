# Milestone 57: Trick Exits

**Prerequisites:** [M56](MILESTONE_56.md) must be finished first (it is: `webclient/.../gups/mudlet/mapper.lua` exists).
Where line numbers below are wrong, find the symbol by name.

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** [DESIGN_NEEDED.md](DESIGN_NEEDED.md): `## Perception and the world` → `### Trick exits
  with a varying destination`.

- **Design Summary:** two kinds of trick exit, both kernel (no feature gate).

  - **Retargetable exits** (the tide stair). Every ordinary exit has a *current* destination, which starts as its
    declared `to`.
    - `ctx.set_exit_to(room, dir, to)` changes it, and `to = None` restores the declared `to`.
    - It is saved with the room, the same way modified door states are. A zone reset does **not** restore it; content
      does that from `on_reset`.
    - It is an error on an exit with a door (door sides are paired through the destination) and on a per-crossing exit.
    - It prints nothing. When the value really changes, it clears the `path_to` and inbound-perception caches and
      recomputes the reality bubble. `Room.Info` is a state package, so its change is resent by the existing phase-7
      diff.
    - Everything that reads the exit sees the current destination: walking, views, the facade, perception,
      `wander`/`follow`/`pursue`, leash distance, the bubble and `Room.Info`.
    - Lint checks only the declared `to`.
    - A reload keeps a retarget when the reloaded exit is still single-destination, still has no door, and the target
      room still exists; otherwise the exit goes back to its declared `to`.
  - **Per-crossing exits** (the shifting corridor).
    - Declared with candidates in `to`: `to = [":a", ":b"]` (uniform) or `to = {":a": 3, ":b": 1}` (weights). There must
      be at least 2 distinct labels, every one must resolve to a room, and each weight is a positive int.
    - An optional `pick = fn` top-level function runs once per crossing. Its `ctx` has `self` and `room` (the room being
      left), `actor`, `direction`, `candidates` (the labels, in declared order), `rng` and `pulse`. It returns one of
      the candidate labels (relative or full) or a room view.
      - A return of `None`, a script error, or a return that isn't a candidate falls back to the engine's weighted roll.
      - A return that isn't a candidate is reported as a script error, through the same path as a hook's runtime error.
    - Load errors: a `door=` on a per-crossing exit, and a `pick=` without a list or map `to`.
    - **The crossing.** The destination is picked once, before the guards, and passed through the whole crossing:
      1. guards, then `before_exit` with `ctx.to_room` the picked room, then the `enter` restriction, then
         `before_enter` in the picked room;
      2. then `move_cost`, `move_pace`/debt (with `ctx.exit` the trick exit), the pinned-on-the-way-out cancel, the
         leave and arrive lines, the arrival `look`, `on_exit` (with `to_room` the real room), `on_enter`, and the
         `ROOM_ENTERED` trait event.
      - A refusal denies the crossing, with no reroll. A walk shows the message as today; a flee counts as a failed flee
        as today. A successful flee ends engagement with `"flee"`.
      - The exit has one `entry_index`, clamped per room as today.
    - **Mobs and pathfinding.** `path_to`, `wander` (never picks one), `follow`, `pursue` and leash distance (`within`)
      all skip per-crossing exits. A leashed mob that ends up past one gets the existing go-home proposal, which routes
      over ordinary exits. `flee`, typed commands (`ctx.command`) and `order` can cross one.
    - **Perception.** A per-crossing exit carries no events toward its own room: the inbound index leaves it out, so it
      is never a `via_exit` and never a `sense_cost` `ctx.exit`.
    - **Reality bubble.** A per-crossing exit is an edge to *every* candidate.
    - **Display.** `look` lists the exit normally. `Room.Info` sends `null` for it, so Mudlet draws a stub for a
      standard direction and nothing for a custom name. `mapper.lua` is **unchanged**.
    - **`one_way`.** A per-crossing exit is one-way unless `one_way = False` is declared. When it is one-way, the
      reverse gate skips it. With `one_way = False`, every candidate needs an exit in the opposite direction leading
      back.
    - **Far-side matching.** In the reverse gate, a per-crossing exit counts as the reverse of an ordinary exit when the
      room that ordinary exit starts from is one of its candidates. It counts whatever its own `one_way` is.
    - The dead-end gate counts a per-crossing exit as an exit.
  - **Views and the plugin facade.**
    - `exit.to` is the current destination label, or `None` for a per-crossing exit.
    - New `exit.original_to`: the declared `to`, or `None` for a per-crossing exit.
    - New `exit.candidates`: the labels in declared order, or `[]`.
    - New `exit.weights`: a dict from label to weight, all `1` for a plain list, or `{}`.
    - `exit.one_way` is the effective value.
    - `PluginExit` gains `to: String?`, `originalTo: String?`, `candidates: List<String>` and `weights: Map<String,
      Int>`.
    - `PluginRoom.exits` becomes `Map<String, String?>`.
    - `PluginWorld.setExitTo(room, dir, to: String?)` mirrors the new verb.
    - No `api_version` bump and no prototype `version` bumps anywhere: the engine is pre-release. Fix in-repo callers
      instead.
  - **Template world.** The tutorial zone gets a shifting passage (west of `:gravel`, into two hollows) and a tide stair
    (down from `:gravel`, to `:grotto` or `:drowned_cave`). Both are off the main path. A permanent
    `trick_exits_test.star` tests them, and `docs/WORLD_DESIGN_GUIDE.md` gets a short paragraph.
- **Specification Impact:**
  - §3.3 (Exit)
  - §3.6 (room persistence)
  - §4.2 (the `exit()` example and declaration rules)
  - §4.5 (context fields, room and exit views, the verb table, a new **Exits** bullet)
  - §4.7 (`wander`, leash)
  - §4.10 (`Room.Info`)
  - §4.15 (the facade verb list)
  - §5.5 (room transitions, `flee`)
  - §5.7 (propagation)
  - §5.9 (active set, pathfinding)
  - §7.3 (lint gates)
- **Decision Record Updates:** None. `docs/DECISIONS.md` has no entries, and this milestone adds none.

### Conventions for every phase

1. Write the tests first, run them and watch them fail, then implement.
2. Comments explain *why*. Never cite spec sections or decision IDs. All Kotlin warnings are errors.
3. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation.
4. In every phase that changes a script- or plugin-facing name, regenerate the API files before the phase's final
   `./gradlew check` (an empty diff is fine):
   - `./gradlew :cli:test -Dgups.updateGolden=true` rewrites `cli/src/test/resources/api.golden.json` and
     `cli/src/test/resources/docs-golden/`.
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` rewrites
     `docs/api/api-1.json`.
   - Read the diff: only what the phase names may change.
5. CHANGELOG: Phase 1 adds `### Trick exits` at the top of `## api_version 1 (unreleased)` in `docs/CHANGELOG.md`, above
   `### Mudlet automapper`. Each later phase appends its bullets. Don't bump `api_version`, and don't add prototype
   `version` bumps to any content.
6. Never read or touch `local-world/`. Scratch files go in `local-world-test/`, which you delete when done.
7. Messages given in quotes below are exact: tests assert them.
8. After changing code, run CodeScene `code_health_review` on the changed files and fix any drop.
9. **Spec text below is approved wording.** Apply it exactly, then run `markdownFormat` (it re-pads tables).
10. **Plugin facade parity:** a new `ctx` verb or view field gets its `PluginWorld`/view counterpart in the same phase
    (`plugin-api/src/test/kotlin/gups/pluginapi/CtxParityTest.kt`). Context *fields* (`CtxFields`) are exempt
    automatically.

---

## Phase 1: The exit model and the graph readers

### Objective & Scope

- **In Scope:**
  - `ExitProto` and `Exit` gain the per-crossing fields, and `to` becomes nullable (`null` exactly for a per-crossing
    exit).
  - Every reader of `exit.to` is updated to the agreed rule:
    - **skip** for `path_to`, `within`, `nearestBy`/`expandLevel`, `wander` and perception's inbound index;
    - **every candidate** for the bubble;
    - **`null`** for `Room.Info`, `ExitView.to` and `PluginExit.to`/`PluginRoom.exits`.
  - Load validation of candidates in `World`'s `init`.
  - Walking or fleeing through a per-crossing exit is **temporarily refused** in this phase, as if the exit weren't
    there. Phase 2 replaces that.
- **Out of Scope:** *Do not implement these; later phases do.*
  - Crossing a per-crossing exit (Phase 2).
  - Starlark declaration of candidates, `pick`, and the new view fields `original_to`/`candidates`/`weights` (Phase 3).
  - Lint changes beyond compiling (Phase 4).
  - `set_exit_to`, persistence and reload (Phase 5).
  - Template-world content (Phase 6).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/Rng.kt` (`int`, `choice` ~54).
  - `core/src/main/kotlin/gups/core/world/Actions.kt` (~250: `ThinkFn`, `ZoneCommand`, the `fun interface` seam
    pattern).
  - `core/src/test/kotlin/gups/core/world/Fixture.kt` (~289) and how `GmcpTest.kt`/`BubbleTest.kt` build rooms from
    `ExitProto`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`ExitProto` ~104)
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Exit` ~88)
  - `core/src/main/kotlin/gups/core/world/Actions.kt` (add the `ExitPicker` seam next to `ThinkFn`)
  - `core/src/main/kotlin/gups/core/world/World.kt` (`init` validation ~394)
  - `core/src/main/kotlin/gups/core/world/Verbs.kt` (`passableExits` ~109)
  - `core/src/main/kotlin/gups/core/world/Behaviors.kt` (`openExit` ~101, `addWithinNeighbors` ~128, `openRoom` ~183)
  - `core/src/main/kotlin/gups/core/world/Bubble.kt` (`neighbours` ~79)
  - `core/src/main/kotlin/gups/core/world/Perception.kt` (`inbound` ~66)
  - `core/src/main/kotlin/gups/core/world/Gmcp.kt` (`roomInfoPayload` ~228)
  - `core/src/main/kotlin/gups/core/world/ItemSupport.kt` (`reverseExit` ~130)
  - `core/src/main/kotlin/gups/core/world/DoorActions.kt` (~111)
  - `core/src/main/kotlin/gups/core/world/Movement.kt` (`MoveAction.validate`, `exitDenial`, `transition`: null handling
    only)
  - `core/src/main/kotlin/gups/core/world/CombatCommands.kt` (`FleeAction`: exclude per-crossing exits for now)
  - `script/src/main/kotlin/gups/script/Views.kt` (`ExitView.to` ~671)
  - `script/src/main/kotlin/gups/script/Linter.kt` (compile only: skip exits with `to == null`)
  - `script/src/main/kotlin/gups/script/Declarations.kt` (compile only: unchanged behavior)
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt` (`PluginExit.to` ~1016, `PluginRoom.exits` ~734)
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt` (~280, ~292)
  - `cli/src/main/kotlin/gups/cli/WorldDescriber.kt` (`exitJson` ~159)
  - `harness/src/main/kotlin/gups/harness/FuzzWalker.kt` (~379: no change needed if it walks by direction; verify)
  - tests: `core/src/test/kotlin/gups/core/world/` (new `TrickExitGraphTest.kt`, plus `GmcpTest.kt`)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `mapper.lua`, `app.js`, `persist/`, `Reconcile.kt`, anything in `template-world/`.

### Specification & Decision Record Changes

- §3.3 **Exit and Door**: replace the **Exit** bullet with:

  > - **Exit:** cardinal `direction`; a destination `to` label, or, for an exit whose destination is picked per
  >   crossing, weighted `candidates` and an optional `pick` function (§4.2); `entryIndex` on the destination track
  >   (default `0`, `-1` for end of track); queryable `tags` (e.g. `soundproof`, `sealed`); optional `door`; and the
  >   `oneWay` flag. An ordinary exit's current destination starts as its declared `to` and can be changed at run time
  >   (§4.5 `set_exit_to`).

- §5.7 **Propagation**: after "…so a one-way exit into the source room carries events and one out of it doesn't."
  insert: " An exit whose destination is picked per crossing carries nothing toward its own room, since no single room
  lies behind it: it is never a listener's `via_exit` and never priced by `sense_cost`."

- §5.9 **Active set**: after "Exit tags and door state are ignored when computing it." insert: " An exit whose
  destination is picked per crossing counts as leading to every one of its candidates."

- §5.9 **Pathfinding**: after "…through open doors only: closed and locked doors block." insert: " Exits whose
  destination is picked per crossing are never part of a path."

- §4.7 behaviors table, `wander` row: after "…and never past the mob's leash." insert " It never steps through an exit
  whose destination is picked per crossing." §4.7 **Leash** bullet: after "…the most exits from its home room, through
  open doors, that its own movement goes." insert " Exits whose destination is picked per crossing don't count as a way:
  rooms reached only through one are outside the leash."

- §4.10 **`Room.Info`** bullet: replace "`exits` maps every exit to its destination's `num`, or null when that room
  isn't loaded;" with "`exits` maps every exit to its current destination's `num`, or null when that room isn't loaded
  or the exit's destination is picked per crossing;". Replace "Nothing is hidden: every exit `look` lists is here, with
  its destination." with "Nothing is hidden: every exit `look` lists is here, with its current destination, or null for
  an exit whose destination is picked per crossing."

- CHANGELOG, new subsection at the top of `## api_version 1 (unreleased)`:

  ```markdown
  ### Trick exits

  - An exit may have no single destination: `exit.to` and `PluginExit.to` are `None`/null for an exit whose destination
    is picked per crossing, and `PluginRoom.exits` maps such an exit to null. GMCP `Room.Info` sends null for it.
  ```

### Implementation Blueprint

#### Signatures & Types

```kotlin
// Protos.kt
data class ExitProto(
  val direction: Direction,
  /** Declared destination, or null for an exit whose destination is picked per crossing. */
  val to: Label?,
  val entryIndex: Int = 0,
  val tags: Set<String> = emptySet(),
  val door: DoorProto? = null,
  /** Effective one-way flag (a per-crossing exit defaults to true). */
  val oneWay: Boolean = false,
  /** Candidate destinations with their weights, in declared order; empty for an ordinary exit. */
  val candidates: Map<Label, Int> = emptyMap(),
  /** Chooses among [candidates] per crossing; null rolls by weight. */
  val pick: ExitPicker? = null,
) {
  init {
    require((to == null) == candidates.isNotEmpty()) { "an exit has either to or candidates" }
  }
}

// Actions.kt, next to ThinkFn
/** A per-crossing exit's `pick` function: the chosen candidate's label, or null to roll by weight. */
fun interface ExitPicker {
  fun pick(world: World, actor: Actor, room: Room, exit: Exit): Label?
}

// Entities.kt
class Exit(proto: ExitProto) {
  val direction: Direction = proto.direction
  /** The declared destination, or null for a per-crossing exit. */
  val originalTo: Label? = proto.to
  /** The current destination, or null for a per-crossing exit. */
  var to: Label? = proto.to
    internal set
  val candidates: Map<Label, Int> = proto.candidates
  val pick: ExitPicker? = proto.pick
  val entryIndex: Int = proto.entryIndex
  val tags: Set<String> = proto.tags
  val door: Door? = proto.door?.let(::Door)
  val oneWay: Boolean = proto.oneWay
  /** True when the destination is picked per crossing. */
  val perCrossing: Boolean get() = candidates.isNotEmpty()
  // keep `passable` as it is
}

// Protos.kt, inside ExitProto (public: script's RoomDeclarations and Linter use it)
/** Every room this exit's declaration can lead to: its `to`, or each candidate. */
fun destinations(): Collection<Label> = to?.let(::listOf) ?: candidates.keys

// Entities.kt, inside Exit: the same for a live exit, from its *current* destination
/** The rooms this exit can lead to now: its current destination, or every candidate. */
fun reachable(): Collection<Label> = to?.let(::listOf) ?: candidates.keys
```

This phase is deliberately larger than the usual 2-5 files. Making `to` nullable breaks every reader at compile time, so
they all have to change together. Each edit is a line or two; don't try to split the phase.

`internal set` on `Exit.to` lets `core` (`World.setExitTo` and `Reconcile`, Phase 5) write it. `persist` is another
module, so Phase 5 adds a public `Exit.restoreTo(label)` for it rather than widening the setter.

#### Algorithmic Steps & Invariants

1. `World` `init` (~394): for each exit, validate every label of `exit.reachable()`. The message for an unknown
   candidate is `"${room.label} exit ${exit.direction} leads to unknown room $label"`, the same as today.
2. `passableExits` (`Verbs.kt` ~109): `filter { it.passable && !it.perCrossing }`, then `room(it.to!!)`.
3. `Behaviors.kt`:
   - `openExit` (wander): return false for `exit.perCrossing` before anything else.
   - `addWithinNeighbors` and `openRoom`: treat a per-crossing exit like an impassable one (skip it).
4. `Bubble.neighbours`: `room.exits.values.flatMap { it.reachable() }.mapNotNull { roomsByLabel[it] }`.
5. `Perception.inbound`: `val to = e.to?.let(roomsByLabel::get) ?: continue`, so per-crossing exits are left out.
6. `Gmcp.roomInfoPayload`: `exits[key] = x.to?.let { protos.roomNumbers[it] }`.
7. `reverseExit` (`ItemSupport.kt`) and `DoorActions.kt` ~111: they are only reached for exits with a door, and a
   per-crossing exit never has one (Phase 3 refuses it at load). Use `exit.to ?: return null` in `reverseExit`, and in
   `DoorActions` pass `exit.to?.let(w::room)`. Check that `makeNoise` accepts a nullable second origin; it does, since
   it is the `alsoAt?` parameter.
8. `Movement.kt`/`CombatCommands.kt`, temporary for this phase:
   - `MoveAction.validate` returns `MOVE_NO_EXIT` for a per-crossing exit.
   - `FleeAction` filters out `perCrossing` exits both in `validate`'s "nowhere" test and in the choice.
   - `exitDenial` and `transition` use `exit.to!!`, which is safe because nothing else reaches them with a per-crossing
     exit yet.
9. `ExitView.to` returns `exit.to?.v ?: Starlark.NONE`. Update its doc to "The current destination room's label, or None
   when it is picked per crossing."
10. `PluginExit.to: String?` and `PluginRoom.exits: Map<String, String?>` (fix the KDoc: "Direction → current
    destination label, or null when it is picked per crossing."). In the host facade, `to = x.to?.v`, and `exits` maps
    to `it.to?.v`. `grep -rn '\.exits\b\|PluginExit' '--include=*.kt' .` (excluding `local-world`) and fix every in-repo
    caller.
11. `WorldDescriber.exitJson`: `put("to", exit.to?.v)` (a JSON null). Candidates come in Phase 3.
12. `Linter`: `val to = exit.to ?: continue` at the top of the reverse loop. Phase 4 replaces this.

### Test & Documentation Requirements

- New `core/src/test/kotlin/gups/core/world/TrickExitGraphTest.kt`, building the rooms directly with `ExitProto(..., to
  = null, candidates = mapOf(a to 1, b to 1), oneWay = true)`:
  - `pathTo` from the corridor to vault A is null when the only way is the per-crossing exit, and goes round when
    another route exists.
  - A `wander` mob in the corridor never proposes the per-crossing direction (seed several runs).
  - `within(corridor, 2)` excludes the vaults.
  - `ball(corridor)` (bubble on) includes both vaults.
  - `inbound(corridor)` doesn't list the per-crossing exit, and an event in vault A isn't perceived in the corridor
    through it.
  - Walking north through it says "You can't go that way." (the temporary behavior).
  - Constructing a `World` whose candidate names an unknown room fails with the message above.
- `GmcpTest`: a per-crossing exit's key maps to `null`.
- Run `grep -rn "PluginRoom\|\.exits\b" plugin-api/src/test cli/src/test` and update the facade tests for the nullable
  types.

### Exit Conditions

- [] `./gradlew :core:test :script:test :plugin-api:test :cli:test` pass.
- [] `./gradlew check` passes with zero warnings, after the golden regeneration (the `PluginExit`/`exits` types and the
  `exit.to` doc change).
- [] `grep -rn "\.to\b" core/src/main/kotlin/gups/core/world/*.kt` shows no `exit.to` use that assumes non-null without
  a reason.

---

## Phase 2: Crossing a per-crossing exit

### Objective & Scope

- **In Scope:**
  - Choosing the destination once per crossing (through `pick`, or else the weighted roll) and passing it through
    `exitDenial`, the deferred transition, `chargeMove` and `transition`, for walking and fleeing.
  - Removing Phase 1's temporary refusals.
- **Out of Scope:** *Do not implement these; later phases do.* Starlark `pick` wiring and declaration (Phase 3, which
  supplies an `ExitPicker`); `set_exit_to` (Phase 5). Don't change pathfinding or behaviors.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Actions.kt` (`execute` ~66: `validate`, then `veto`, then `finish`→`resolve`
    on the same `Action` instance).
  - `core/src/main/kotlin/gups/core/world/Verbs.kt` `actMove` (~147), `KernelCommands.kt` (~24), `Leash.kt` (~80): each
    builds a fresh `MoveAction(dir)` per command.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Movement.kt`
  - `core/src/main/kotlin/gups/core/world/CombatCommands.kt` (`FleeAction`)
  - new `core/src/test/kotlin/gups/core/world/TrickExitCrossingTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `Behaviors.kt`, `Bubble.kt`, `Perception.kt`, `script/`, `plugin-api/`.

### Specification & Decision Record Changes

- §5.5 **Room transitions**: add a new bullet after the **Arrivals** bullet:

  > - **Exits picked per crossing:** an exit whose destination is picked per crossing (§4.2) chooses the room once, when
  >   the move is checked: its `pick` function's answer, or else a roll by the candidates' weights. That room is the
  >   destination for the whole crossing: `before_exit` sees it as `ctx.to_room`, the `enter` restriction and
  >   `before_enter` are asked of it, and the actor arrives there at the exit's `entryIndex`, clamped to that room's
  >   track. Otherwise the crossing is an ordinary walk, with its `move_cost`, pace and messages. A refusal denies the
  >   crossing; no other candidate is tried.

- §5.5 **`flee` → On success**: after "The exit is then checked like a walk:" insert "(an exit picked per crossing picks
  its room first)".

- CHANGELOG bullet: "Walking or fleeing through an exit picked per crossing picks the room once, then is an ordinary
  walk to it: the hooks, restriction, cost, pace and arrival all use that room, and a refusal denies the crossing."

### Implementation Blueprint

#### Signatures & Types

```kotlin
// Movement.kt
/**
 * The room [actor] goes to through [exit]: the current destination, or for a per-crossing exit
 * the `pick` answer when it names a candidate, else a roll by weight.
 */
internal fun World.destinationFor(actor: Actor, exit: Exit): Room

internal fun World.exitDenial(actor: Actor, dir: Direction, to: Room): String?
internal fun World.transition(actor: Actor, exit: Exit, to: Room, leave: Msg = KernelMsg.MOVE_LEAVES, reason: String = "stop")

internal class MoveAction(private val dir: Direction) : Action {
  /** The room chosen when the move was checked; the crossing goes there. */
  private var destination: Room? = null
}
```

#### Algorithmic Steps & Invariants

1. `destinationFor`:
   - If `!exit.perCrossing`, return `room(exit.to!!)`.
   - Otherwise call `exit.pick?.pick(this, actor, actor.room, exit)`. If the result is a key of `exit.candidates`,
     return `room(it)`.
   - Otherwise roll: `total = candidates.values.sum()`, `r = rng.int(1, total)`, then walk the candidates in declared
     order, subtracting weights, and return the first room where `r` falls.
   - `pick` handles its own errors and validation (Phase 3), so a non-candidate here is simply ignored. Keep this
     function free of script concerns.
2. `MoveAction`:
   - `validate`: remove Phase 1's per-crossing refusal.
   - `veto`: `val to = w.destinationFor(actor, exit)`, store it in `destination`, then `return w.exitDenial(actor, dir,
     to)`.
   - `resolve`: `val to = destination ?: w.destinationFor(actor, exit)`. The fallback covers callers that skip `veto`;
     `execute` always calls it, but stay safe. Use `to` in the deferred block's `transition`.
   - **Invariant:** `exitDenial` and `transition` see the same `Room` object for one crossing.
3. `exitDenial(actor, dir, to)`: replace `room(from.exits.getValue(dir).to)` with the parameter. Every other line is
   unchanged.
4. `transition(actor, exit, to, …)`: replace `room(exit.to)` with the parameter. `entryIndexFor(exit, to)` already
   clamps per room.
5. `FleeAction.resolve`:
   - Remove the per-crossing filters.
   - After choosing `exit`, `val to = w.destinationFor(actor, exit)`, then `w.exitDenial(actor, exit.direction, to)`.
   - In the deferred block, `w.transition(actor, exit, to, CombatMsg.FLEE_ROOM, reason = "flee")`.
6. `chargeMove` is unchanged: `move_pace` sees `ctx.exit` = the trick exit and `ctx.room` = the room being left.

### Test & Documentation Requirements

`TrickExitCrossingTest.kt`, using `Fixture` and `ExitProto` candidates directly:

- **Roll:** a uniform two-candidate exit lands in a candidate. Over a seeded batch (for example 200 crossings by
  resetting the player), both candidates appear. A `{a: 1, b: 0}`-style test can't exist (weights are positive), so test
  `{a: 9, b: 1}` with a fixed seed and assert that the counts favour `a`.
- **Same room throughout:** a recording `before_exit`/`before_enter` (use the fixture's script-host stub, as other core
  tests do) sees `to_room` equal to the room the actor lands in, and `on_exit`'s `to_room` matches too.
- **`pick`:** an `ExitPicker` returning `b` always lands in `b`. One returning null rolls. One returning a label that
  isn't a candidate rolls.
- **Refusal:** an `enter` restriction or `before_enter` veto in the picked room gives its message, the actor stays, and
  no `move_cost` or debt is charged. Check that no second candidate is tried: the pick runs exactly once.
- **Cost and pace:** `move_cost` is spent and `moveFreeAt` advances, as for an ordinary walk.
- **Pinned on the way out:** the cancellation still applies.
- **Flee:** a successful flee through a per-crossing exit lands in a candidate and fires `on_disengage` with `"flee"`. A
  veto in the picked room counts as a failed flee, with `flee_fail_lag`.
- **`entry_index = -1`:** lands at the back of each candidate's own track.

### Exit Conditions

- [] `./gradlew :core:test` passes, including `TrickExitCrossingTest` and Phase 1's tests with the temporary refusal
  assertion replaced by a crossing assertion.
- [] `./gradlew check` passes with zero warnings.

---

## Phase 3: Declaring trick exits in Starlark, and the new view fields

### Objective & Scope

- **In Scope:**
  - `exit(to = [...] | {...}, pick = fn)`, the `one_way` default, and the load errors.
  - `ScriptExitPicker`.
  - The `ctx.candidates` field.
  - The `ExitView` fields `original_to`, `candidates` and `weights`, the effective `one_way`, and their `PluginExit`
    counterparts.
  - `WorldDescriber` output.
- **Out of Scope:** *Do not implement these; later phases do.* Lint gate changes (Phase 4), `set_exit_to` (Phase 5),
  template content (Phase 6).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/MobDeclarations.kt` (~46: `ScriptThink(hookRef(...), pkg)`).
  - `script/src/main/kotlin/gups/script/Host.kt` (`think` ~413 and `ScriptThink` ~860: the call/Site/CallResult pattern;
    find how a hook's runtime error is reported in `call` and reuse it).
  - `script/src/main/kotlin/gups/script/Values.kt` (`hookRef` ~123).
  - `script/src/main/kotlin/gups/script/RoomDeclarations.kt` (~56: `ctx.ref(e.to, RefKind.ROOM, thread)` cross-package
    loading).
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`exit` ~132)
  - `script/src/main/kotlin/gups/script/DeclarationSpecs.kt` (`EXIT_SPEC` ~288: `to` docs and type, new `pick`, the
    `one_way` doc)
  - `script/src/main/kotlin/gups/script/RoomDeclarations.kt` (reference every candidate)
  - `script/src/main/kotlin/gups/script/Host.kt` (`ScriptExitPicker` and its call)
  - `script/src/main/kotlin/gups/script/Ctx.kt` (`CtxExtras.candidates`)
  - `script/src/main/kotlin/gups/script/CtxFields.kt` (the `candidates` field, after `proposals` ~239)
  - `script/src/main/kotlin/gups/script/Views.kt` (`ExitView`)
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt` (`PluginExit`), and `plugin-api/.../host/Facade.kt` (~288)
  - `cli/src/main/kotlin/gups/cli/WorldDescriber.kt` (`exitJson`)
  - tests: `script/src/test` (the existing declaration tests for `exit()`: find them with `grep -rln "entry_index"
    script/src/test`), plus the facade and `WorldDescriber` tests
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `Linter.kt` (Phase 4), `core/` movement code.

### Specification & Decision Record Changes

- §4.2 example: after the `exit(DIR.south, to="//zones/forest:clearing", entry_index=-1), # arrive at back` line, insert
  this line, indented to match the other `exit(...)` entries in that list:

  ```python
  exit(DIR.west, to={":vault_a": 3, ":vault_b": 1}, pick=_pick_vault),  # picked per crossing (one-way unless one_way=False)
  ```
- §4.2 **Declaration rules**: add a bullet after **Arguments**:

  > - **Exits picked per crossing:** `to` may be a list of room labels (equally likely) or a dict from label to a
  >   positive int weight, with at least two distinct labels; every label must resolve. Such an exit picks its room each
  >   time someone crosses it (§5.5). `pick = fn` (only with such a `to`) names a top-level function called once per
  >   crossing with `ctx.self` and `ctx.room` the room being left, `ctx.actor`, `ctx.direction` and `ctx.candidates`
  >   (the labels, in declared order); it returns one of the candidates, as a label or a room, or `None` to roll by
  >   weight. An error in it, or an answer that isn't a candidate, is a script error, and the engine rolls instead. Such
  >   an exit can't have a `door`. It is `one_way` unless declared `one_way = False`, which asks the reverse-exit gate
  >   to find a way back from every candidate (§7.3). It is never part of a path (§5.9), carries no perception toward
  >   its own room (§5.7), and `Room.Info` sends no destination for it (§4.10).

- §4.5 **Context fields** list: after `proposals`, insert "`candidates` (an exit's `pick` function: the labels it may
  choose),".

- §4.5 **Room view → Exits** bullet: replace it with:

  > - **Exits:** `exit(dir)`, an exit view with `direction`, `to` (the current destination's label, or `None` for an
  >   exit picked per crossing), `original_to` (the declared `to`, or `None` for an exit picked per crossing),
  >   `candidates` (in declared order, `[]` for any other exit), `weights` (label to weight, `{}` for any other exit),
  >   `entry_index`, `tags`, `one_way` (an exit picked per crossing reports `True` unless declared otherwise),
  >   `passable`, `key` and `door`: a door view (`state`, `key`, `tags`), or `None` without a door.

- CHANGELOG bullets:

  - "`exit(to = [...])` or `exit(to = {label: weight})` declares an exit whose destination is picked per crossing,
    optionally by a `pick = fn` function; such an exit is one-way unless `one_way = False`, and can't have a door."
  - "Exit views gain `original_to`, `candidates` and `weights`; `PluginExit` gains `originalTo`, `candidates` and
    `weights`."

### Implementation Blueprint

#### Signatures & Types

```kotlin
// Host.kt, next to ScriptThink
internal data class ScriptExitPicker(private val ref: HookRef, private val pkg: String) : ExitPicker {
  override fun pick(world: World, actor: Actor, room: Room, exit: Exit): Label?
}

// Ctx.kt, CtxExtras
/** For an exit's pick function: the labels it may choose, in declared order. */
val candidates: List<Label>? = null,

// Facade.kt
data class PluginExit(
  val direction: String,
  val to: String?,
  val originalTo: String?,
  val candidates: List<String>,
  val weights: Map<String, Int>,
  val tags: List<String>,
  val door: PluginDoor?,
  val key: String?,
  val passable: Boolean,
  val oneWay: Boolean,
)
```

#### Algorithmic Steps & Invariants

1. `exit()` parsing (`Declarations.kt`):
   - Read the raw `to`.
     - A string: today's behavior.
     - A Starlark list: each element is a string label, resolved with `label(..., ctx.pkg.path, ...)`, weight 1.
     - A Starlark dict: string label keys and int values.
     - Anything else: `fail("exit(to): expected a room label, a list of labels or a dict of label to weight, got
       ${typeName(it)}")`.
   - For a list or dict:
     - fewer than 2 entries: `fail("exit(to): a list or dict needs at least two rooms")`;
     - a duplicate label after resolution: `fail("exit(to): room $label is listed twice")`;
     - a weight below 1: `fail("exit(to): weight for $label must be a positive int")`;
     - `door` set: `fail("exit(door): an exit whose destination is picked per crossing can't have a door")`.
   - `pick` with a string `to`: `fail("exit(pick): needs a list or dict of rooms in to")`. Otherwise `pick =
     ScriptExitPicker(hookRef(it, d.what("pick")), ctx.pkg.path)` (check `hookRef`'s exact parameters, and the `pkg`
     form `ScriptThink` uses, and mirror them).
   - `oneWay = d.bool("one_way") ?: candidates.isNotEmpty()`.
   - Build `ExitProto(to = null, candidates = LinkedHashMap(...), pick = ..., ...)`.
2. `DeclarationSpecs.EXIT_SPEC`: `to` documents all three forms. Add `pick`, documented as a function: "fn(ctx) choosing
   a destination per crossing, from ctx.candidates". `one_way` documents its default.
3. `RoomDeclarations.kt` ~56: call `ctx.ref` for every label in `e.destinations()`. For a per-crossing exit that's each
   candidate, so cross-package candidates load their packages.
4. `ScriptExitPicker.pick`: delegate to a `Host` method that mirrors `think`:
   - `Site(pkg, room.protoLabel-or-label, "pick", vetoable = false, room.id)`. Check `Site`'s parameter types and use
     what room hooks use.
   - `Ctx(f, room, HookContext(actor = actor, direction = exit.direction, room = room), CtxExtras(candidates =
     exit.candidates.keys.toList()))`.
   - On `CallResult.Ok`:
     - `None`: null.
     - A string: resolve it relative to `pkg`.
     - A room view: its label.
     - If the result isn't a key of `exit.candidates`, report it as a script error, using the same reporting the call
       path uses for a runtime error, with the message `"pick returned $value, which is not one of the exit's
       candidates"`, and return null.
     - Anything else (wrong type): the same error path, with message `"pick must return a candidate label, a room or
       None"`, and null.
   - `Failed` or `Skipped`: null.
5. `CtxFields.candidates`: `extras.candidates?.let { sList(it.map { l -> l.v }) } ?: Starlark.NONE`. Doc: "For an exit's
   pick function: the candidate room labels, in declared order."
6. `ExitView`:
   - `original_to`: `exit.originalTo?.v ?: Starlark.NONE`.
   - `candidates`: `sList(exit.candidates.keys.map { it.v })`.
   - `weights`: a Starlark dict in declared order (find how other views build dicts, for example `attrs`, and mirror
     it).
   - `one_way` already reads `exit.oneWay`, which is now the effective value.
7. Host `Facade.exit(dir)` fills the new `PluginExit` fields the same way.
8. `WorldDescriber.exitJson`: after `to`, add `"candidates"` (an array of labels) and `"weights"` (an object), both
   always present (empty for ordinary exits), so the shape is stable. Key order: `dir`, `to`, `candidates`, `weights`,
   `one_way`, `tags`, `door`.

### Test & Documentation Requirements

- Declaration tests (`script/src/test`):
  - `to = [":a", ":b"]` gives candidates `{a: 1, b: 1}`, `to == null` and `oneWay == true`.
  - `to = {":a": 3, ":b": 1}` gives the weights in order.
  - `one_way = False` is kept.
  - Each `fail` message above, asserted exactly.
  - A cross-package candidate loads its package.
- Host test: a `pick` returning `":b"` lands in `b`. One returning `None` rolls. One that raises rolls, and the error is
  reported. One returning `":nowhere"` rolls and reports the "not one of the exit's candidates" message.
  `ctx.candidates` lists both labels.
- View tests: `exit.to` is `None`, and `original_to`, `candidates` and `weights` are correct for both kinds. For an
  ordinary exit, `original_to == to`, `candidates == []` and `weights == {}`.
- `CtxParityTest` passes without new exceptions (the view fields have `PluginExit` counterparts; `candidates` is a ctx
  field).
- `WorldDescriber` golden/test updated.

### Exit Conditions

- [] `./gradlew :script:test :plugin-api:test :cli:test` pass.
- [] `./gradlew check` passes with zero warnings, after the golden and API-baseline regeneration (expect `pick`,
  `candidates`, `original_to`, `weights` and the `PluginExit` fields in the diff).
- [] A scratch scenario in `local-world-test/` (copy `template-world` to `local-world-test/world` and add a
  two-candidate exit) walks through it and lands in a candidate. Delete it afterwards.

---

## Phase 4: Lint gates

### Objective & Scope

- **In Scope:** the reverse-exit and dead-end gates for per-crossing exits, including far-side matching.
- **Out of Scope:** *Do not implement these; later phases do.* Any lint for runtime retargets (there is none by design);
  `set_exit_to`; template content.

### Context Working Set

- **Read / Reference Only:** `ExitProto` (Phase 1/3 fields).
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Linter.kt` (the reverse loop ~162)
  - the linter's tests (find them with `grep -rln "has no reverse exit" script/src/test cli/src/test`)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** everything else.

### Specification & Decision Record Changes

- §7.3 **Lint**: replace "- Every exit has a reverse unless `one_way`, and doors match on both sides (error)." with:

  > - Every exit has a reverse unless `one_way`, and doors match on both sides (error). An exit picked per crossing
  >   needs a reverse from each candidate when it is declared `one_way = False`, and is otherwise one-way. It also
  >   counts as the reverse of an exit leading into its room from any of its candidates. Only an exit's declared `to` is
  >   checked, never one set at run time.

- §7.3 **Lint**, the `--base` bullet: replace "`name`, `desc`, `keywords`, hooks, `commands`, `think` and `resolve`."
  with "`name`, `desc`, `keywords`, hooks, `commands`, `think`, `resolve` and an exit's `pick`."

- CHANGELOG bullet: "The reverse-exit gate checks every candidate of an exit picked per crossing that is declared
  `one_way = False`, and accepts such an exit as the way back from any of its candidates."

### Implementation Blueprint

#### Algorithmic Steps & Invariants

1. Replace Phase 1's `?: continue` with a loop over the destinations to check:
   - `if (exit.oneWay) continue` (unchanged; a per-crossing exit without `one_way = False` is one-way).
   - `val targets = exit.to?.let(::listOf) ?: exit.candidates.keys.toList()`.
   - For each `target`, run today's checks (no opposite direction; missing reverse; doors not matching) with
     `exit.to`/`${exit.to}` replaced by `target` in the lookups and messages.
2. The reverse match becomes `targetRoom.exits.firstOrNull { it.direction == opp && (it.to == room.label || room.label
   in it.candidates) }`.
   - This gives far-side matching: vault A's ordinary `south` to the corridor finds the corridor's per-crossing `north`
     as its reverse when vault A is a candidate. It works whatever that exit's `one_way` is.
   - The door comparison stays: a per-crossing exit has no door, so a door on the ordinary side reports "doors do not
     match", which is correct.
3. Dead-end: unchanged code. `room.exits.isEmpty()` already counts per-crossing exits. Add a test that proves it.
4. Keep the messages' wording. Only the substituted label changes. Extract a helper (for example `checkReverse(room,
   exit, target, findings)`) to keep CodeScene's complexity down.
5. The `--base` version gate (`withoutPresentation`, `Linter.kt` ~251): `pick` is code, like `think`, so it must not
   count as a declaration change. In `RoomProto.withoutPresentation()`, also clear `exits = exits.map { it.copy(pick =
   null) }`.

### Test & Documentation Requirements

- Linter tests:
  - A one-way-by-default per-crossing exit with no returns lints clean.
  - The same exit with `one_way = False` and a missing return from `b` reports `has no reverse exit in //…:b leading
    'south' back to …`.
  - Ordinary returns from each candidate to the corridor are accepted through far-side matching, with no error on the
    candidates' exits.
  - An ordinary return with a door against a per-crossing exit reports "doors do not match".
  - `--base`: a room whose only change is the function named by an exit's `pick` needs no version bump. A room whose
    candidates changed is reported as today.
  - A room whose only exit is per-crossing is not a dead end.

### Exit Conditions

- [] `./gradlew :script:test` passes.
- [] `./gradlew check` passes with zero warnings. `gups lint //...` on `template-world` is still clean.

---

## Phase 5: Retargeting with `set_exit_to`, persistence and reload

### Objective & Scope

- **In Scope:**
  - `World.setExitTo`.
  - The `ctx.set_exit_to` verb and `PluginWorld.setExitTo`.
  - Cache invalidation and the bubble recompute.
  - Saving and restoring retargets.
  - Keeping retargets across a reload.
- **Out of Scope:** *Do not implement these.* Timed retargets, automatic restore on zone reset, perception or messages
  on a retarget, and template content (Phase 6).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Verbs.kt` `setDoor` (~66: the silent-verb pattern, `room.dirty`,
    `graphChanged()`).
  - `script/src/main/kotlin/gups/script/CtxDoors.kt` (~17: `door(...)`, `roomArg`, `dir`, `verb(name)`).
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt` `openDoor` (~418) and its host override (`host/Facade.kt`
    ~1159).
  - `core/src/main/kotlin/gups/core/world/Bubble.kt` `recomputeBubble` (~126).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Verbs.kt` (`setExitTo`)
  - `core/src/main/kotlin/gups/core/world/World.kt` (`bubbleGraphChanged`) and `Bubble.kt` (`maintainBubble`)
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Exit.restoreTo`)
  - `core/src/main/kotlin/gups/core/world/Reconcile.kt` (`rebind` ~267)
  - `script/src/main/kotlin/gups/script/CtxDoors.kt` (the `set_exit_to` verb)
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt` and `host/Facade.kt`
  - `persist/src/main/kotlin/gups/persist/Codec.kt` (`RoomPayload` ~193, `encode` ~462, `applyRoom` ~629)
  - `persist/src/main/kotlin/gups/persist/SqlitePersistence.kt` (~279) and `Repro.kt` (~94): the `applyRoom` callers
  - tests in `core`, `script`, `plugin-api` and `persist`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** movement, behaviors, perception and `Gmcp.kt` (they already read `exit.to`), and `mapper.lua`.

### Specification & Decision Record Changes

- §4.5 verb table, **Doors and exits** row: append ", `set_exit_to(room, dir, to)`".
- §4.5: add a bullet after **Doors**:

  > - **Exits:** `set_exit_to(room, dir, to)` makes an ordinary exit lead to `to`, a room or label, and `to = None`
  >   restores its declared destination. It prints nothing. Everything that reads the exit follows the new destination
  >   at once: walking, views, perception, pathfinding, mob movement, the reality bubble and `Room.Info`. The change is
  >   saved with the room; a zone reset doesn't undo it, so content that wants that calls it from `on_reset`. A reload
  >   keeps it while the exit still has a single destination and no door and the room still exists, and otherwise
  >   restores the declared destination. It is an error on an exit with a door, since a door's two sides are paired
  >   through the destination, and on an exit picked per crossing. Kernel.

- §3.6, the **Rooms** payload bullet: replace "Rooms store modified door states, custom attributes, running timers, and
  affects." with "Rooms store modified door states, retargeted exit destinations (`set_exit_to`), custom attributes,
  running timers, and affects." After "…is restored closed." add: " A retarget to a room that no longer exists, or on an
  exit that now has a door or is picked per crossing, is dropped."

- §4.15: in the facade verb list, next to the door verbs, add `setExitTo`, and add the sentence " `setExitTo` follows
  `set_exit_to`." after "`teleport` returns the refusal or veto, or null once the actor has moved." (find the door
  verbs' mention there first and mirror its placement).

- CHANGELOG bullet: "New verb `set_exit_to(room, dir, to)` (and `PluginWorld.setExitTo`) changes where an ordinary exit
  leads until set back with `None`; the change is saved and survives zone resets."

### Implementation Blueprint

#### Signatures & Types

```kotlin
// Verbs.kt
/**
 * Points [room]'s exit [dir] at [to], or back at its declared destination when [to] is null.
 * Silent. Fails on an exit with a door or one picked per crossing.
 */
fun World.setExitTo(room: Room, dir: Direction, to: Room?)

// CtxDoors.kt
@StarlarkMethod(name = "set_exit_to", doc = "Points the exit in direction dir at room to; None restores its declared destination.", ...)
fun setExitTo(room: Any, dir: Any, to: Any, thread: StarlarkThread): Any

// Facade.kt (PluginWorld)
/** Points [room]'s exit [dir] at the room labelled [to], or back at its declared destination when null. */
fun setExitTo(room: PluginRoom, dir: String, to: String?): Unit =
  throw UnsupportedOperationException("setExitTo is not supported")

// Entities.kt, on Exit: the only way code outside core sets a destination
/** Restores a saved retarget; callers have checked [label] names a loaded room. */
fun restoreTo(label: Label) {
  require(!perCrossing && door == null) { "exit $direction can't be retargeted" }
  to = label
}

// Codec.kt, RoomPayload
/** Exit destinations changed with set_exit_to, by direction. */
val exitTargets: Map<String, String> = emptyMap(),
```

#### Algorithmic Steps & Invariants

1. `setExitTo`:
   - `exit = requireNotNull(room.exits[dir]) { "${room.label} has no exit $dir" }`.
   - `require(!exit.perCrossing) { "${room.label} exit $dir is picked per crossing and can't be retargeted" }`.
   - `require(exit.door == null) { "${room.label} exit $dir has a door and can't be retargeted" }`.
   - `val label = to?.label ?: exit.originalTo!!`. If `label == exit.to`, return: no churn, which matters because a tide
     script may call this every tick.
   - Otherwise: `exit.to = label`, `room.dirty = true`, `graphChanged()`, `inboundCache = null`, and set a new `internal
     var bubbleGraphChanged = false` on `World` to true.
   - **Don't recompute the bubble here.** `set_exit_to` runs inside hooks (the demo's `on_tick`, for one), and waking or
     sleeping rooms there would run `on_wake` and spawn replays while the tick loop is iterating entities.
     `maintainBubble()` (`Bubble.kt:108`) runs at a fixed point in every pulse (`World.kt` ~790, after perception
     delivery). At its top, when `bubbleGraphChanged` is set, clear the flag, call `recomputeBubble()` and return. The
     active set catches up within the same pulse.
2. Ctx verb (`CtxDoors.kt`):
   - `verb("set_exit_to") { … }`. Resolve `room` with `roomArg`, `dir` with `dir(...)`, and `to` as `None` → null, else
     a room view or label resolved like `roomArg` does (reuse `roomArg(to, "set_exit_to(to)", thread)`).
   - Turn the `IllegalArgumentException`s into `fail(...)` with the message `"set_exit_to(): ${message}"`, or check
     first and `fail` with the same texts.
   - Fast-lane commands may call it (as door verbs can); no lane gating.
3. Host facade: `setExitTo` resolves `to` with the world's `roomOrNull(Label(it))`. Use `requireNotNull` with "unknown
   room $to" for an unknown label, and delegate to `World.setExitTo`.
4. Persistence (`Codec.kt`):
   - `encode` for `Room` sets `exitTargets = e.exits.values.filter { it.to != it.originalTo }.associate {
     it.direction.name to it.to!!.v }`.
   - `applyRoom(room, p, knownRoom: (Label) -> Boolean)`: for each entry, find the exit by direction name. Skip it when
     the exit is missing, is `perCrossing`, has a door, or `!knownRoom(Label(v))`. Otherwise call
     `exit.restoreTo(Label(v))`.
   - Both callers pass `{ world.roomOrNull(it) != null }`.
   - Restore runs before the world's caches and the bubble are built (check where `recomputeBubble` or `startDormant` is
     called after restore). If any cache could already be filled at that point, call `graphChanged()`, set `inboundCache
     = null` and call `recomputeBubble()` after the room restore loop.
   - No `FORMAT_VERSION` change: the field has a default, so old payloads decode.
5. Reload (`Reconcile.rebind`): after building each new `Exit(ep)`, take the live exit `old = oldExits[ep.direction]`.
   If `old != null && old.to != old.originalTo && !exit.perCrossing && exit.door == null && old.to in roomsByLabel` (use
   the post-reload room table), set `exit.to = old.to`. Reconcile already calls `recomputeBubble()` (~51); check that
   `graphChanged()` and `inboundCache = null` also run after a reload (`Reconcile.kt` ~191) and leave them.

### Test & Documentation Requirements

- Core `TrickExitRetargetTest.kt`:
  - After `setExitTo(stair, DOWN, cave)`:
    - a walk down lands in the cave;
    - `pathTo` follows the new destination;
    - `ball` includes the cave. With the bubble on, the active set includes the cave after the next pulse's bubble
      maintenance. A `set_exit_to` called from an `on_tick` hook doesn't wake or sleep any room until then.
    - `inbound(cave)` lists the stair exit;
    - `Room.Info` for a player at the stair maps `d` to the cave's `num`, and the phase-7 diff resends it.
  - `setExitTo(..., null)` restores the declared room.
  - Setting the same value twice doesn't clear `pathCache` (fill it first, then check it's still filled).
  - It fails on a door exit and on a per-crossing exit, with the messages above.
- Script test: `ctx.set_exit_to(room, "down", ":cave")` and `None`, the label and room-view forms of `to`, and the error
  messages.
- Plugin test: `setExitTo` works and fails the same way. `CtxParityTest` passes without exceptions.
- Persistence test (`persist/src/test`, next to the existing door-state round-trip test; find it with `grep -rln "doors"
  persist/src/test`):
  - a retarget survives save and restore;
  - one naming a room that no longer exists is dropped;
  - an exit that now has a door drops it;
  - an unretargeted exit writes no `exitTargets` entry.
- Reload test (next to the existing reload door-preservation test; `grep -rln "preserveDoorState\|keeps.*door"
  core/src/test`): a retarget survives a reload that keeps the exit, and is dropped when the reload adds a door or makes
  the exit per-crossing.

### Exit Conditions

- [] `./gradlew :core:test :script:test :plugin-api:test :persist:test` pass.
- [] `./gradlew check` passes with zero warnings, after the golden and API-baseline regeneration (`set_exit_to`,
  `setExitTo`).

---

## Phase 6: Template world demo, guide, and finalization

### Objective & Scope

- **In Scope:**
  - The tutorial zone's shifting passage and tide stair.
  - A permanent `trick_exits_test.star`.
  - A guide paragraph.
  - An end-to-end scenario check.
  - The backlog bookkeeping.
- **Out of Scope:** *Do not implement these.* Any change to `mapper.lua`, a follower-lands-with-leader rule, perception
  through per-crossing exits, and timed retargets. None of them is part of this milestone.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/zones/tutorial/rooms.star` (`:gravel` ~90; `:tar_pit` ~72).
  - `template-world/zones/tutorial/pursuit_test.star` (test idioms: `t.world()`, `spawn_player`, `p.do`, `room.label`,
    `w.run(pulses = n)`).
  - `docs/SPEC.md` §4.12 (harness API).
- **Files to Modify / Create:**
  - `template-world/zones/tutorial/rooms.star`
  - new `template-world/zones/tutorial/trick_exits_test.star`
  - `docs/WORLD_DESIGN_GUIDE.md` (near the "Preventing Cross-Zone Exit Lint Deadlocks" bullets, ~437)
  - `docs/DESIGN_NEEDED.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `core/`, `script/`, `plugin-api/`, `persist/`, `mapper.lua`.

### Specification & Decision Record Changes

- None in `SPEC.md`. CHANGELOG bullet: "The template's tutorial zone has a shifting passage west of the gravel path and
  a tide stair down from it."

### Implementation Blueprint

1. `rooms.star`, `:gravel`:
   - `exits` becomes:

     ```python
     exits = [
         exit(DIR.east, to = ":tar_pit"),
         exit(DIR.west, to = [":hollow_a", ":hollow_b"]),
         exit(DIR.down, to = ":grotto"),
     ],
     ```
   - Add `on_tick = _tide`, `on_reset = _tide` and `on_wake = _tide`. `on_wake` matters because a player who wakes a
     dormant `:gravel` and goes down before the next world tick would otherwise use a stale tide. Keep `on_enter =
     _crunch`.
2. New rooms, each with a short `desc` that tells a player what's going on:
   - `hollow_a` ("A Mossy Hollow") and `hollow_b` ("A Fern Hollow"): `exits = [exit(DIR.east, to = ":gravel")]`. Their
     return is accepted by far-side matching. Mention in `desc` that the path behind seems to shift.
   - `grotto` ("A Dry Grotto"): `exits = [exit(DIR.up, to = ":gravel")]`.
   - `drowned_cave` ("A Drowned Cave"): `exits = [exit(DIR.up, to = ":gravel", one_way = True)]`. `:gravel`'s declared
     `down` names `:grotto`, so lint can't see this return. Comment why.
3. `_tide(ctx)` with `TIDE_PULSES = 240`, and a short comment that the stair is the template's example of a retargetable
   exit:

   ```python
   def _tide(ctx):
       high = (ctx.pulse // TIDE_PULSES) % 2 == 1
       ctx.set_exit_to(ctx.self, "down", ":drowned_cave" if high else None)
   ```

   It sets the stair from the pulse on every tick and reset. `set_exit_to` does nothing when the value is unchanged.
   Comment the shifting passage too: "one-way unless one_way = False; each hollow walks back east".

4. Check that `on_tick`, `on_reset` and `on_wake` are attachable to rooms (§4.4: `on_tick` any, `on_reset` Room,
   `on_wake` Room) and that `ctx.self` is the room there.

5. `trick_exits_test.star`:

   - `test_shifting_passage_lands_in_a_hollow`: spawn at `:gravel`, `p.do("west")`, and assert the room is one of the
     two hollows. Repeat with several seeds (`t.world(seed = n)`), and assert that each hollow is reached at least once
     across seeds 1..10. If a seed range doesn't cover both, pick seeds that do and comment them.
   - `test_hollow_walks_back`: from a hollow, `east` returns to `:gravel`.
   - `test_passage_view`: `exit("west").to` is `None`, `candidates` lists both, and `one_way` is `True`.
   - `test_tide_stair`:
     - At pulse 0, `down` reaches `:grotto`.
     - Return to `:gravel`, then run the world until the stair flips. `on_tick` fires only on world ticks, so the flip
       lands at the first tick at or after pulse 240, not at 240 itself. Either `w.run(pulses = TIDE_PULSES +
       WORLD_TICK_PULSES)` (read the constant's value from `core/.../TimeConstants.kt` and comment it), or poll in small
       `w.run` steps until the gravel room's `exit("down").to` is the cave, with an upper bound.
     - Then `down` reaches `:drowned_cave`. In that state, the `down` exit's `to` is the cave and `original_to` is
       `:grotto`.
6. `WORLD_DESIGN_GUIDE.md`: one paragraph titled **Trick exits**. Retarget an ordinary exit with `set_exit_to` for
   destinations driven by world state (tides, timers, quests), restored from `on_reset` if wanted. Use `to = [...]` or
   `{label: weight}` (optionally `pick`) for a room picked per crossing. Per-crossing exits are one-way unless `one_way
   = False`, can't have doors, are skipped by mob pathing and perception, and show as an unmapped stub in Mudlet. Point
   at the tutorial's gravel path.

### Test & Documentation Requirements

- `gups test //zones/tutorial/...` passes, including the existing tests. Some may enumerate `:gravel`'s exits or the
  squirrel's wandering; fix their expectations only where the new exits legitimately change them, and say so in the
  commit.
- End-to-end check: copy `template-world` to `local-world-test/world`, write `local-world-test/trick.star` per
  AGENTS.md:
  - two players at `:gravel` walk west several times (returning east each time);
  - one goes `down`, then `w.run(...)` past a tide change, then `down` again;
  - print `p.gmcp("Room.Info")` at `:gravel` for a player spawned with `gmcp = ["Room"]`, and check `exits.w` is null
    and `exits.d` is the current destination's `num`.

  Run it with:

  ```bash
  ./gradlew :cli:installDist
  cli/build/install/gups/bin/gups scenario --world local-world-test/world --package //zones/tutorial local-world-test/trick.star
  ```

### Finalization & Backlog Update

- In `docs/DESIGN_NEEDED.md`, mark the entry completed without deleting it: change the heading `### Trick exits with a
  varying destination` to exactly `### Trick exits with a varying destination (Done in Milestone 57)`.
- Re-read §3.3, §3.6, §4.2, §4.5, §4.7, §4.10, §4.15, §5.5, §5.7, §5.9 and §7.3 against the code. Every name, default,
  error message and rule must match.
- Confirm the CHANGELOG subsection `### Trick exits` has a bullet for every phase.
- Run `./gradlew check` and `./gradlew markdownCheck` outside the sandbox; both must pass.
- Run `pre_commit_code_health_safeguard`; it must pass.
- Delete `local-world-test/`.
