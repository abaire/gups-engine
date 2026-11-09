# Milestone 46: Movement Pace and Pursuit Catch-Up

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, section "Mobs and AI", heading `### Movement speed and pursuit
  catch-up`.

- **The problem today:**

  - A player walking exit to exit moves one room per pulse, because walking leaves no recovery lag.
  - A mob thinks on one pulse and runs the action it chose on the next pulse. `World.actionPulse` does one thing per
    pulse, and `think()` only adds the action to the queue. A `think_every = 4` mob therefore moves one room every 4
    pulses, and a `think_every = 1` mob one room every 2 pulses.
  - No pursuer can catch a player who keeps moving, and nothing can be faster than a player.
- **Design Summary:**
  - **Fractional walking pace, for every actor.**
    - The new kernel rules export `move_pace(ctx)` returns hundredths of a pulse per room, and it has a modifier point
      of the same name.
    - A world without the export gets 100: one room per pulse, which is today's speed.
    - The chain's final value is clamped to at least `MOVE_PACE_MIN = 100 / MOVES_PER_PULSE_MAX` (25), so nothing makes
      more than `MOVES_PER_PULSE_MAX = 4` moves in a pulse.
  - **Movement debt.**
    - Each actor keeps a transient `moveFreeAt`, an absolute time in hundredths of a pulse, which is not saved. Its debt
      at pulse `p` is `max(0, moveFreeAt − p × 100)`.
    - A successful walk or `flee` sets `moveFreeAt = max(moveFreeAt, pulse × 100) + pace`.
    - An actor may start a move only while its debt is below 100. Fractions carry over, so a pace of 150 averages two
      rooms every three pulses.
    - The debt holds back only moves (walking, `act_move`, ordered moves and `flee`), never other actions.
    - `teleport` and other relocations a script makes add no debt.
  - **More than one move per pulse.**
    - After the normal action pass of phase 2, up to `MOVES_PER_PULSE_MAX − 1` extra move rounds run. Each is its own
      `runTiers` call, so the deferred transitions of one round finish before the next round starts.
    - An actor takes part in an extra round only if it moved earlier in this pulse, has no recovery lag, isn't casting,
      and has debt below 100. Then it either takes its queued move, or, as a mob with a chained think due and an empty
      queue, thinks.
    - Every extra step is a complete, ordinary room move: guards, `before_exit`, `before_enter`, doors, `move_cost`,
      perception, `on_exit` and `on_enter`. The rooms in between behave exactly as if walked one per pulse.
    - Players keep moving one room per pulse at a pace of 100.
    - An actor whose step this pulse was anything other than a move (a non-move action, recovery, casting or idling)
      takes no part in extra rounds for the rest of the pulse.
  - **A walk pinned before it lands is cancelled.** Inside an initiative tier, a walk's transition is deferred to the
    end of the tier. If a hostile pinned the walker in the same tier (it attacked and shares the walker's track index),
    the walk is cancelled: the walker stays, pays no `move_cost` and no movement debt, and gets the pinned message.
    Entity ids no longer decide whether an attacked walker escapes. `flee` is never cancelled this way, because it
    ignores pinning. Without the `track` feature nothing pins, so nothing is cancelled, matching how walking already
    works there.
  - **Every mob think acts in the same pulse.** After a think, the first queued action starts at once, in the same step
    of the action pulse, including in an extra move round. A non-move action started in an extra round ends that mob's
    rounds for the pulse.
  - **Chained thinks, decided in the world's rules.**
    - After a tick-lane action chosen by a mob's think finishes, the engine asks the new kernel rules export
      `chain_think(ctx)`, then each plugin `addChainThink` handler in plugin-id order.
    - `True` makes a chained think due: the mob thinks again as soon as it is idle and its debt is below 100, ignoring
      `think_every`.
    - `ctx` carries:
      - `behavior`: the source of the proposal that ran, such as `"pursue"`, `"leash"`, or `"think"` for a `think`
        override;
      - `command`;
      - `exit`: the exit the action went through, or `None`;
      - `chain`: how many chained thinks in a row led to this action.
    - Without the export, an action that went through an exit, with source `pursue`, `follow`, `heel` or `leash`,
      chains.
    - Any action can chain, but only moves can repeat within one pulse.
    - The question is asked after a move's transition lands, so `ctx.room` is the room arrived in. A cancelled walk asks
      nothing.
    - A fast pursuer that reaches its quarry in an extra round attacks in that same round. A walk the quarry makes in
      the same tier is then cancelled, and any later walk is pinned, so the quarry is caught.
  - **Contested exits (option C) stay world-level.** A mob's `before_exit` observer can already deny an exit. Actor
    views gain `move_pace`, so the hook can compare speeds. The template world ships a sample of this in a test package.
  - **Template world:**
    - A `speed` stat, where 100 is a player.
    - `move_pace = 10000 // max(speed, 10)`.
    - `chain_think` with the default rule written out.
    - The contested-exit sample.
    - Updated tutorials.
- **Specification Impact:**
  - §3.1: the new constant.
  - §4.5: the `move_pace` view field.
  - §4.6: the two exports, the rules `ctx` fields `command` and `chain`, the wider meaning of `behavior` and `exit`, the
    modifier-point list and the whose-chain list.
  - §4.7: catching up.
  - §4.8: the same-pulse note.
  - §4.15: `addChainThink`.
  - §5.1: phase 2, the movement hold and the extra move rounds.
  - §5.5: room transitions, pace, `flee`, and walks cancelled by a same-tier pin.
  - §5.8: acting in the same pulse and chained thinks.
- **Decision Record Updates:** None. `docs/DECISIONS.md` has no open entry on walking lag, think cadence or
  `MoveAction`.

### Ground rules for every phase

1. **Ordering.** Milestone 45 (leashes) is merged. Other milestones may also land first, so always re-read the current
   `SPEC.md`, `CHANGELOG.md`, `World.kt`, `Actions.kt`, `Movement.kt`, `Behaviors.kt`, `RulesEngine.kt` and the script
   `Rules.kt`. Find code by symbol name, never by the line numbers quoted here.
2. **TDD** (`AGENTS.md`): write the tests named in each phase first, run them and see them fail, then implement.
3. **Spec edits are pre-approved.** Apply the SPEC text given in each phase directly, adjusting only the surrounding
   wording needed to make it fit. Log a `DECISIONS.md` entry only for a genuinely new gap you have to fill.
4. **No spec section numbers or decision ids in code comments**, Kotlin or Starlark.
5. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`. The engine is
   unreleased: no migrations, no save-format back-compat, no deprecation shims.
6. **CHANGELOG:** add bullets to a new `### Movement pace and pursuit catch-up` subsection at the top of `## api_version
   1 (unreleased)`, above `### Mob leashes and bounded pursuit` (or whatever subsection is first by then). Create it in
   the first phase that adds a bullet; later phases append.
7. **API files.** In any phase that changes the script or plugin API, regenerate before the phase's final `./gradlew
   check` if a golden or API test fails:
   - `./gradlew :cli:test -Dgups.updateGolden=true`, which rewrites `cli/src/test/resources/api.golden.json` and
     `cli/src/test/resources/docs-golden/`.
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout, which rewrites `docs/api/api-1.json`.
   - Read the diff: only the symbols the phase names may be new or changed. `ApiDiffTest` must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing entry.
8. **Gradle** runs outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation. `./gradlew check` must pass with zero warnings.
9. **Pulse arithmetic used in this document.** Phase 2 runs once per pulse. A move started at pulse `p` with pace `P`,
   by an actor whose `moveFreeAt ≤ p × 100`, sets `moveFreeAt = p × 100 + P`. The worked timings in the tests below
   follow from that rule. If a test disagrees, recheck the arithmetic before changing the rule.

---

## Phase 1: The `move_pace` Export, Its Modifier Point and View

### Objective & Scope

- **In Scope:**
  - The constants `MOVES_PER_PULSE_MAX` and `MOVE_PACE_MIN`.
  - The optional kernel export `move_pace(ctx)` with default 100.
  - The `MOVE_PACE` modifier point: core, script and plugin.
  - `World.movePaceOf(c)` with the clamp and tracing.
  - The actor-view field `move_pace`.
  - The load-time dry run.
  - Spec text for §3.1, §4.5 and §4.6.
- **Out of Scope (the agent must not implement these in this phase):**
  - Any use of the pace by movement: no movement debt, no hold, no extra rounds. Those are Phase 2. After this phase,
    walking behaves exactly as before.
  - Any template-world rules change (Phase 6).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Rules.kt`: `advanceLagOf`, `lagOf` (the chain-then-clamp-then-trace pattern),
    and `moveCost` (the mover's chain, run with the room being left).
  - `script/src/main/kotlin/gups/script/Rules.kt`, to mirror end to end:
    - the optional export `leash_return`: its `RulesExport` entry, its `RulesExportSpec` with `hasDefault = true`, its
      `defines(...)`-guarded override, and its load-time dry run;
    - the int conversion used by `advance_lag` (`lagOf(RulesExport.ADVANCE_LAG)`);
    - the `ModifierPoint.ADVANCE_LAG` modifier-point spec entry.
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt`: the `when (point)` branch that handles the int points.
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`: the modifier-point feature-requirement map. Kernel
    points such as `MOVE_COST` show how a point with no feature is handled.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/TimeConstants.kt`
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`ModifierPoint`)
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt`
  - `core/src/main/kotlin/gups/core/world/Rules.kt`
  - `script/src/main/kotlin/gups/script/Rules.kt`
  - `script/src/main/kotlin/gups/script/Views.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt` (`PluginModifierPoint`)
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt`, and `PluginHost.kt` only if its feature map needs an
    entry.
  - `core/src/test/kotlin/gups/core/world/MovePaceTest.kt` (new)
  - `script/src/test/kotlin/gups/script/MovePaceRulesTest.kt` (new)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, `script/src/main/kotlin/gups/script/DocExamples.kt` if asked for, and the API
    files.
- **Do Not Touch:** `Movement.kt`, `Actions.kt`, `World.kt`, `CombatCommands.kt`, `persist/`, `template-world/`.

### Specification & Decision Record Changes

- **§3.1, the "Spatial and simulation bounds" bullet:** after "synchronous hook cascade depth (`CASCADE_MAX_DEPTH`)"
  insert ", and the most room transitions an actor makes in one pulse (`MOVES_PER_PULSE_MAX`)".

- **§4.5 actor view, the **State** bullet:** after `wait_state` append ", `move_pace` (what `rules.move_pace` and its
  modifier chain give the actor now, asked with `ctx.exit` `None`; 100 without the export)".

- **§4.6 optional-exports sentence:** add `move_pace` with the clause "`move_pace`, whose default is 100 (§5.5)".
  Recount the number word ("Seven exports are optional") as the list stands when you edit it.

- **§4.6 rules `ctx` fields:** the `exit` field gets the clause "(in `move_pace`, the exit being taken, or `None` when
  the view field asks)". If `exit` isn't described in the list yet, add it in that form.

- **§4.6 catalog table:** add a row after `move_cost`. Match the table's padding; `markdownFormat` realigns it.

  ```
  | `move_pace(ctx)` | kernel | `int`, hundredths of a pulse per room for `ctx.actor` walking out of `ctx.room` through `ctx.exit` (§5.5). |
  ```
- **§4.6 modifier points:**
  - Add `move_pace` to the "Modifier points (v0)" list after `move_cost`.
  - In the sentence on what modifiers take and return, add "modifiers of `move_pace` take and return ints, and its
    chain's final value is clamped to at least `100 / MOVES_PER_PULSE_MAX` (25)".
  - In **Whose chain**, change "the mover's for `move_cost` (from the room being left)" to "the mover's for `move_cost`
    and `move_pace` (from the room being left)".
- **§4.6, new paragraph after the **`leash_return`** paragraph:**

  > **`move_pace`**: asked whenever a script reads an actor's `move_pace` view field, with `ctx.exit` `None`; while the
  > export or its chain is running, that view field reads 100, so a rule can't recurse into itself. 100 is one room per
  > pulse, 200 is one room every two pulses, and 50 is two rooms per pulse. Without the export the base is 100. A
  > failing call, or a result that isn't an int, counts as 100. A world reads speed from a stat, a tag or an affect;
  > haste and slowing effects belong in the modifier chain. When defined, the load-time dry run calls it with the
  > throwaway mob and checks that it returns an int.

### Implementation Blueprint

- **Signatures & Types:**
  - `TimeConstants.kt`, after `ACTION_QUEUE_MAX`, each with KDoc in the file's style:
    - `const val MOVES_PER_PULSE_MAX = 4`
    - `const val MOVE_PACE_MIN = 100 / MOVES_PER_PULSE_MAX`
  - `ModifierPoint.MOVE_PACE("move_pace")`, placed after `MOVE_COST`.
  - `RulesEngine`: `fun movePace(c: RulesContext): Int? = null`. `null` means the default, 100.
  - Core `Rules.kt`: `fun World.movePaceOf(c: RulesContext): Int`.
  - Script `Rules.kt`:
    - `RulesExport.MOVE_PACE("move_pace")`;
    - `RulesExportSpec(export = RulesExport.MOVE_PACE, signature = "move_pace(ctx)", returnType = "int", ctxFields =
      listOf("actor", "room", "exit", "pulse", "rng"), doc = "Hundredths of a pulse per room for ctx.actor walking
      through ctx.exit; 100 is one room per pulse.", hasDefault = true)`;
    - a modifier-point spec entry for `MOVE_PACE`, with int base and int return, mirroring `ADVANCE_LAG`.
  - `PluginModifierPoint.MOVE_PACE`, mapped in `Adapters.kt` in the int branch, next to `ADVANCE_LAG`.
  - Actor view in `Views.kt`: `move_pace` (`structField`, int, doc "Hundredths of a pulse per room this actor walks at
    now; 100 is one room per pulse."), computed as `world.movePaceOf(RulesContext(world, actor))`.
- **Algorithmic Steps & Invariants:**
  1. `movePaceOf(c)`:
     - Take `base = rules.movePace(c) ?: 100`.
     - Run `chain(ModifierPoint.MOVE_PACE, c, base) { "$it" }`.
     - Clamp the final value to at least `MOVE_PACE_MIN`. When the clamp applies, trace `"<text> → clamp
       $MOVE_PACE_MIN"`, as `lagOf` does for 0; otherwise trace the text as is.
     - Callers build the context as `RulesContext(world, actor, room = from, exit = exit)` for a transition and
       `RulesContext(world, actor)` for the view.
  2. Script override: `if (!defines(RulesExport.MOVE_PACE)) return null`, then `run(...)` with int conversion. If the
     call fails or returns a non-int, record the usual rules diagnostic and return `null`, so the default 100 applies.
     Check how `run` reports failures for `leash_return`, mirror it, and test it.
  3. The dry run, when the export is defined: call it once with the throwaway mob, `exit = None`, and check that the
     result is an int. Place it next to the `leash_return` dry run.
  4. The modifier point belongs to the kernel: no `Ownership.kt` entry and no feature requirement, like `move_cost`.
  5. **No recursion:** while `movePaceOf` runs for an actor, that actor's `move_pace` view field reads 100 instead of
     calling `movePaceOf` again. Mirror how the `encumbrance_level` view reads 0 while its own rule runs: find that
     guard in `Views.kt` or core and use the same mechanism, such as a per-actor in-progress flag set in a
     `try`/`finally`.

### Test & Documentation Requirements

- `MovePaceTest` (core, Fixture rules):
  - Without an override, `movePaceOf` is 100.
  - A `FixedRules` subclass returning 300 gives 300.
  - A modifier on the room (through `RecordingHost.modifiers`) that subtracts 1000 clamps to 25. The trace (`w.onRules`)
    contains `clamp 25`.
  - The chain runs with the mover's room as `c.room` when the context names `room = from`.
- `MovePaceRulesTest` (script):
  - `def move_pace(ctx): return 250` makes `ctx.self.move_pace`, printed from a zone command, read `250`.
  - With no export, the view reads `100`.
  - A room modifier `MODIFIER.move_pace` that halves the base reads `125`.
  - `return "x"` and `fail("x")` both give `100` and record a rules diagnostic.
  - An export that returns `ctx.actor.move_pace + 50` gives `150`, with no recursion.
  - The dry run rejects `return "x"` at load. Mirror how `LeashReturnRulesTest` asserts load-time errors, if it does.
- CHANGELOG bullet: "New optional rules export `move_pace(ctx)` and modifier point `move_pace`: hundredths of a pulse
  per room (default 100). Actor views gain `move_pace`."

### Exit Conditions

- [] `./gradlew :core:test --tests '*MovePaceTest*'` passes.
- [] `./gradlew :script:test --tests '*MovePaceRulesTest*'` passes.
- [] API files regenerated. The diff names only `move_pace` (the export, the modifier point and the view field) and
  `PluginModifierPoint.MOVE_PACE`.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.
- [] Walking timing is unchanged: the existing movement tests pass untouched.

---

## Phase 2: Movement Debt and Extra Move Rounds

### Objective & Scope

- **In Scope:**
  - Transient movement state on `Actor`.
  - Movement debt charged by walking and successful `flee`.
  - The hold on queued moves while the debt is 100 or more.
  - The extra move rounds in phase 2.
  - Marking which queued commands are moves.
  - Cancelling a walk that a hostile pins in the same initiative tier.
  - Spec text for §5.1 and §5.5.
- **Out of Scope (the agent must not implement these in this phase):**
  - Any change to mob thinking: same-pulse acting is Phase 3, and chained thinks are Phase 4. In this phase, mobs take
    part in extra rounds only through moves already at the head of their queue.
  - Template-world changes.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/World.kt`:
    - `runPulse`, the phase order;
    - `actionPulse`;
    - `runTiers`, `deferRemoval` and `commitDeferred`. Moves inside a tier are deferred to the tier's end, which is why
      each extra round is its own `runTiers` call.
  - `core/src/main/kotlin/gups/core/world/Actions.kt`: `runQueued`, `execute` and `finish`.
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt`: `move(dir)`.
  - `core/src/main/kotlin/gups/core/world/Verbs.kt`: `enqueue`.
  - `core/src/main/kotlin/gups/core/world/Combat.kt`: `isPinned`, which is true only with the `track` feature.
  - `core/src/test/kotlin/gups/core/world/Fixture.kt`: `FixedRules`, `C.protos(n = …)` corridors, `run` and `runTo`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Actor`)
  - `core/src/main/kotlin/gups/core/world/Commands.kt` (`TickCommand`)
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt`
  - `core/src/main/kotlin/gups/core/world/CombatCommands.kt` (the `flee` command and `FleeAction`)
  - `core/src/main/kotlin/gups/core/world/Movement.kt` (`MoveAction`, and a new `chargeMove`)
  - `core/src/main/kotlin/gups/core/world/Verbs.kt` (`enqueue`)
  - `core/src/main/kotlin/gups/core/world/World.kt` (`runPulse`, `actionPulse`, and a new `runMoveRounds`)
  - `core/src/test/kotlin/gups/core/world/MovementPaceTest.kt` (new)
  - `docs/SPEC.md` and `docs/CHANGELOG.md`
- **Do Not Touch:** `persist/`, because the new fields are transient and never saved; `script/`; `plugin-api/`;
  `template-world/`; `Behaviors.kt`.

### Specification & Decision Record Changes

**§5.5 Room transitions.** Replace the bullet "Non-combat movement costs one action plus the vitals `rules.move_cost`
returns (§4.6). The action is the action pulse itself: walking leaves no recovery lag." with these two bullets:

> - Non-combat movement costs one action plus the vitals `rules.move_cost` returns (§4.6). Walking leaves no recovery
>   lag, so an actor can attack or cast the moment it arrives.
> - **Pace:** each successful walk, and each successful `flee`, adds `rules.move_pace` (§4.6) to the mover's *movement
>   debt*, in hundredths of a pulse. The debt drops by 100 every pulse, never below 0. The actor may start another move
>   only while its debt is below 100 (§5.1). A pace of 100 is one room per pulse, 200 one every two pulses, and 25 four
>   per pulse, the most there can be (`MOVES_PER_PULSE_MAX`). Fractions carry over, so a pace of 150 averages two rooms
>   every three pulses. Each move is a complete transition: guards, `before_exit`, `before_enter`, `move_cost`,
>   perception, `on_exit` and `on_enter` apply to every room passed. The debt holds back only moves, never other
>   actions, and it isn't saved. Moving with `teleport` adds none.

**§5.5 **Pinning and exits**, a new bullet after "A pinned actor must `retreat` first, or `flee`.":**

> - **Pinned on the way out:** a walk leaves its room at the end of its initiative tier (§5.4.1), and it is checked
>   against pinning again then. If a hostile pinned the walker in the meantime (by attacking it in the same tier and
>   sharing its index), the walk is cancelled: the walker stays, spends no `move_cost`, adds no movement debt, and is
>   told it is pinned. Entity ids therefore never decide whether an attacked walker gets away. `flee` is never cancelled
>   this way.

**§4.6 `move_pace` paragraph (added in Phase 1):** after its first sentence insert "It is also asked once for every room
transition that adds movement debt (§5.5), from the room being left, with `ctx.exit` the exit taken."

**§5.5 `flee`, the **On success** bullet:** append "A successful flee adds movement debt like a walk, and a queued
`flee` waits for the debt like a walk."

**§5.1 phase 2, the **Action pulse** bullets.** Replace "Otherwise, if the queue is non-empty, pop the head and execute
it." with:

> - Otherwise, if the queue is non-empty, pop the head and execute it, unless the head is a move (a direction or `flee`)
>   and the actor's movement debt is 100 or more (§5.5): then it waits, and the actor does nothing this pulse.

After the bullet list, before the paragraph on initiative tiers, add:

> - **Extra moves:** after every actor has had its step, up to `MOVES_PER_PULSE_MAX − 1` extra rounds run. Each round
>   runs in initiative order with its own tiers. An actor takes part in a round when it moved earlier in this pulse, is
>   not casting, has no recovery lag and has movement debt below 100, and the head of its queue is a move: it takes that
>   move. The rounds stop early when one has no participants.

### Implementation Blueprint

- **Signatures & Types:**
  - `Actor` in `Entities.kt`, transient and excluded from save: check that `persist/` serializes fields explicitly, so
    nothing there needs changing.
    - `var moveFreeAt: Long = 0`, in hundredths of a pulse;
    - `var lastMovePulse: Long = Long.MIN_VALUE / 2`;
    - `var lastMoveExit: Exit? = null`;
    - `var lastNonMovePulse: Long = Long.MIN_VALUE / 2`, the last pulse whose step ran a non-move action.
  - `TickCommand(..., val moves: Boolean = false, val plan: ...)`. Add the new parameter before `plan`, so trailing
    lambdas keep working.
  - `World.moveDebt(a: Actor): Long = maxOf(0L, a.moveFreeAt - pulse * 100)`
  - `World.canMove(a: Actor): Boolean = moveDebt(a) < 100`
  - In `Movement.kt`: `internal fun World.chargeMove(actor: Actor, exit: Exit)`. It runs `movePaceOf(RulesContext(this,
    actor, room = actor.room, exit = exit))`, then sets `actor.moveFreeAt = maxOf(actor.moveFreeAt, pulse * 100) +
    pace`, `actor.lastMovePulse = pulse` and `actor.lastMoveExit = exit`.
  - In `World.kt`: `private fun runMoveRounds()`.
- **Algorithmic Steps & Invariants:**
  1. **Marking moves:**
     - `KernelCommands.move(dir)` builds its `TickCommand` with `moves = true`, and so does the `flee` command in
       `CombatCommands.kt`.
     - `World.enqueue(actor, verb, action)` builds `TickCommand(verb, moves = action is MoveAction || action ===
       FleeAction) { action }`.
  2. **Charging happens when the move lands, inside the deferred op:**
     - `MoveAction.resolve` keeps its `exit` lookup and then only calls `w.deferRemoval(actor, TRANSITION) { … }`.
       Inside the op, in this order:
       1. If `!actor.inWorld`, return.
       2. If `w.isPinned(actor)`, send `w.msg(TrackMsg.PINNED)` and return. The walk is cancelled: no vitals, no debt,
          no transition.
       3. `w.changeVitals(actor, w.moveCost(RulesContext(w, actor)).mapValues { -it.value })`.
       4. `w.chargeMove(actor, exit)`, while the actor is still in the room being left.
       5. `w.transition(actor, exit)`.
     - When no tier is running, `deferRemoval` runs the op at once, so the order is the same. The pin check is then
       trivially false, because validation already refused a pinned walk.
     - `FleeAction.resolve`: on success only, call `w.chargeMove(actor, exit)` inside its deferred op, before
       `transition`. Never check pinning there. A failed or denied flee charges nothing.
     - `move_cost` affordability is still checked in `validate`, so the charge in the op uses the cost asked at that
       point. Don't re-check affordability.
  3. **`finish` clears `lastMoveExit`:** set `actor.lastMoveExit = null` just before `action.resolve(...)`, so the field
     names the exit only for the action that just resolved. Phase 4 reads it.
  4. **The hold:** in `actionPulse`, the queue branch becomes "if the head's `command.moves` is true and `!canMove(a)`,
     do nothing; else pop and `runQueued`". A held head also blocks the mob think branch, because the queue isn't empty.
  5. **Extra rounds:** in `runPulse`, right after `runTiers(..., ::actionPulse)`, call `runMoveRounds()`.
     - Repeat at most `MOVES_PER_PULSE_MAX - 1` times.
     - **Recording non-moves:** in `runQueued`, after the order check passes, if `!queued.command.moves`, set
       `actor.lastNonMovePulse = pulse`.
     - Each round, `participants = actorIndex.values.filter { isSimulated(it) && it.inWorld && it.lastMovePulse == pulse
       && it.lastNonMovePulse != pulse && it.casting == null && it.waitState == 0 && canMove(it) &&
       it.actionQueue.firstOrNull()?.command?.moves == true }`.
     - If the list is empty, stop. Otherwise `runTiers(participants) { a -> runQueued(a, a.actionQueue.removeFirst())
       }`.
     - Initiative is the one rolled for this pulse, if any. Don't roll again.
  6. **Invariants:**
     - An actor whose step this pulse wasn't a move never joins an extra round. That covers recovering, casting, a
       non-move action, or idling.
     - A move that fails validation or a veto leaves `lastMovePulse` alone. A blocked actor therefore stops for this
       pulse, but only if that failed move was its first step; when it already moved, the next round checks the next
       head again. That is acceptable, because a failed validation pops the queued action.
     - With no `move_pace` export, a single queued move per pulse behaves exactly as before. A second queued move can
       never run in the same pulse, because the debt after one move at pace 100 is exactly 100.

### Test & Documentation Requirements

`MovementPaceTest` (core). Use a corridor of rooms `C.r(0..9)`, a player at `C.r(0)` queuing moves (`f.player(...)` plus
the Fixture's input helper), and `FixedRules` overriding `movePace` per test.

- **Default:** 5 queued `east` commands take one room per pulse: the player is at `r1` after the first pulse and `r5`
  after five.
- **Pace 200:** rooms are reached on the 1st, 3rd and 5th pulses.
- **Pace 150:** rooms are reached on pulses 1, 2, 4, 5 and 7, counted from the first pulse.
- **Pace 50:** 4 queued moves finish within 2 pulses, two per pulse.
- **Pace 10:** clamped to 25, so 8 queued moves finish within 2 pulses.
- **Each step is complete:**
  - At pace 25, with a guard (a `Guard` behavior mob) on the exit out of `r2`, the player stops in `r2` and gets the
    guard's message.
  - `move_cost` is spent twice (assert the vital).
  - An onlooker mob or player in `r1` perceives both the arrival and the departure. Assert through the perception or
    recording path the existing movement tests use.
- **The debt holds only moves:** at pace 300, a player who walks one room and has a non-move tick action queued next
  runs it on the very next pulse. Use any non-move tick command available in Fixture, such as `get`.
- **No extra round after a non-move:** at pace 50, with a queue of `[get x, east]`, `get` runs on pulse 1 and `east` on
  pulse 2, not on pulse 1.
- **Flee:** at pace 200, a successful flee (`FixedRules(flee = 100)`) followed by a queued `east` waits one pulse. A
  failed flee adds no debt.
- **`teleport` adds no debt.**
- **Pinned on the way out** (enable `track` and `combat`, with all actors at index 0):
  - Player A queues `east`, and player B queues `kill A`, for the same pulse. Both are unengaged, so they share the last
    tier.
  - Run it twice, once with A's id lower than B's and once higher. Both times A is still in `r0` afterwards, has its
    `move_cost` vital unchanged, has `moveDebt == 0`, and received the pinned message.
  - The same setup with `flee` in place of `east`, where A is already fighting a third actor C: the flee leaves the
    room. Use `FixedRules(flee = 100)`.
  - Without the `track` feature, A's walk goes through.
- CHANGELOG bullet: "Walking pace: each move adds `move_pace` to a movement debt, and an actor moves only while the debt
  is below 100. Fast actors make up to four complete moves a pulse."

### Exit Conditions

- [] `./gradlew :core:test --tests '*MovementPaceTest*'` passes.
- [] `./gradlew :core:test` passes with no changes to the expectations of existing tests.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 3: Mob Thinks Act in the Same Pulse

### Objective & Scope

- **In Scope:**
  - After a think, the first queued action starts at once.
  - Updating existing tests whose expected pulse counts change because mobs now act one pulse sooner.
  - Spec text for §5.8, §4.8 and §5.1.
- **Out of Scope (the agent must not implement these in this phase):**
  - Chained thinks (Phase 4).
  - Any change to `think_every`, to behaviors' proposals, or to the order of proposals.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/World.kt`: `actionPulse`.
  - `core/src/main/kotlin/gups/core/world/Commands.kt`: `act` and `submit`. A fast-lane command runs right away, and a
    tick command is queued.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Actions.kt` (`think`)
  - `core/src/main/kotlin/gups/core/world/World.kt` (`actionPulse`)
  - `core/src/test/kotlin/gups/core/world/ThinkTimingTest.kt` (new)
  - Existing tests across `core/src/test`, `script/src/test`, `harness/src/test`, `cli/src/test` and
    `template-world/**/*_test.star`, only where pulse expectations shift (see step 3).
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and `template-world/docs/tutorials/05_mobs_and_behaviors.md` if it states the
    old think-then-act timing.
- **Do Not Touch:** `Movement.kt`, `Behaviors.kt`, `persist/`, `plugin-api/`.

### Specification & Decision Record Changes

**§5.8 think cycle.** After step 4 ("Without `think`, the top proposal is enqueued."), add a new step:

> 5. The first action the think queued starts at once, in the same step of the action pulse, as if popped from the queue
>    (§5.1). A move still waits while the mob's movement debt is 100 or more (§5.5). Further actions `think` queued wait
>    their turn.

**§4.8, the first bullet:** append "The first queued action runs in the same pulse as the think (§5.8)."

**§5.1 phase 2:** change "Otherwise, if the actor is a mob whose think cadence is due, run the think cycle (§5.8)." to
"Otherwise, if the actor is a mob whose think cadence is due, run the think cycle (§5.8) and start the action it chose."

### Implementation Blueprint

- **Algorithmic Steps & Invariants:**
  1. In `World.think(mob)`, after the `when (result)` block: if `mob.inWorld`, `mob.casting == null`, `mob.waitState ==
     0` and the queue is non-empty, check the head. If `head.command.moves && !canMove(mob)`, leave it queued. Otherwise
     pop it and call `runQueued(mob, head)`.
  2. Fast-lane commands that `think` runs through `ctx.act` have already executed inside `submit`. Nothing changes for
     them.
  3. **Shifting tests:** run the whole suite. For each failure, decide whether it is purely the one-pulse shift: a mob
     action, move, attack or cast now lands one pulse earlier.
     - If it is, update the expected pulse, room or count, and keep the test's intent.
     - If a test fails for any other reason, stop and investigate. Don't loosen assertions.
     - List every test you changed in the phase summary.
     - The likely candidates are `BehaviorTest`, `LeashBehaviorTest`, `OwnedBehaviorsTest`, `ActionLifecycleTest`,
       `BehaviorDispositionTest`, the script `Leash*` tests, and the template's `ai_test.star`, `pet_test.star`,
       `target_test.star` and `notice_test.star`.

### Test & Documentation Requirements

- `ThinkTimingTest` (core):
  - A `think_every = 4` mob with `Wander(chance = 100)` in a corridor changes rooms on the same pulse as its think. Read
    `mob.lastThink` and the pulse at which `mob.room` changed.
  - A mob with `think_every = 1` and `Pursue` covers one room per pulse after its first think. It previously took two
    pulses per room.
  - An `aggressive` mob that notices a player engages it on the pulse of the think.
  - A `think` that queues two tick commands runs the first in the think's pulse and the second on the next pulse.
  - A `heel` pet with `think_every = 1` whose owner walks one room per pulse stays within two rooms of the owner, and is
    in the owner's room within 2 pulses of the owner stopping. A pet's think in the same tier as its owner's move still
    sees the owner in the old room, because transitions are deferred to the end of the tier.
- Tutorial 05: if it says a mob acts on the pulse after it thinks, correct it. State that a think's action starts at
  once.
- CHANGELOG bullet (breaking timing): "A mob's think now runs the action it chooses in the same pulse, so mob actions
  happen one pulse sooner than before."

### Exit Conditions

- [] `./gradlew :core:test --tests '*ThinkTimingTest*'` passes.
- [] `./gradlew check` passes with zero warnings, with every changed existing test listed in the phase summary.
- [] `./gradlew markdownFormat` was run after the Markdown edits.

---

## Phase 4: Chained Thinks and the `chain_think` Export

### Objective & Scope

- **In Scope:**
  - Recording which think chose each queued action.
  - Asking `rules.chain_think` when such an action finishes.
  - The chained-think due state and how it counts.
  - Running chained thinks in the main pass and in the extra move rounds.
  - The optional kernel export `chain_think(ctx)`, and the rules `ctx` fields `command` and `chain`.
  - Spec text for §4.6, §4.7 and §5.8.
- **Out of Scope (the agent must not implement these in this phase):**
  - The plugin handler `addChainThink` (Phase 5). Leave a `chainThinkAdjusters` list out for now.
  - Template rules (Phase 6).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Leash.kt` (`shouldReturn`), as the pattern for asking an optional bool export
    with a default.
  - `core/src/main/kotlin/gups/core/world/Behaviors.kt`: `Proposal.source` values and the `Sentinel` filter.
  - `script/src/main/kotlin/gups/script/Rules.kt`: the `leash_return` export end to end, as in Phase 1. For the existing
    `behavior` rules-ctx field, see how `target_order` sets it.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Entities.kt`: `QueuedAction`, `CastState` and `Mob`.
  - `core/src/main/kotlin/gups/core/world/World.kt`: `Deferred.Kind.CHAIN`, as well as the items listed below.
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt`: `RulesContext.command` and `RulesContext.chain`, plus
    `chainThink`.
  - `core/src/main/kotlin/gups/core/world/Actions.kt`: `think`, `runQueued`, `execute`, `advanceCast` and `finish`.
  - `core/src/main/kotlin/gups/core/world/World.kt`: `actionPulse` and `runMoveRounds`.
  - `script/src/main/kotlin/gups/script/Rules.kt`
  - `core/src/test/kotlin/gups/core/world/ChainThinkTest.kt` (new)
  - `script/src/test/kotlin/gups/script/ChainThinkRulesTest.kt` (new)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, `DocExamples.kt` if asked for, and the API files.
- **Do Not Touch:** `Movement.kt`, except to read it; `persist/`; `plugin-api/`; `template-world/`.

### Specification & Decision Record Changes

- **§4.6 optional-exports sentence:** add "`chain_think`, whose default is described in §5.8", and recount.

- **§4.6 rules `ctx` fields:**

  - Extend `behavior`: "(in `target_order`, the behavior asking: …; in `chain_think`, the source of the proposal that
    ran: a behavior name such as `"pursue"`, `"leash"` for the return home, or `"think"` for an action `think` chose;
    otherwise `None`)".
  - Extend `exit`: "(…; in `chain_think`, the exit the action went through, or `None`)".
  - Add "`command` (in `chain_think`, the command line of the action that finished; otherwise `None`)" and "`chain` (in
    `chain_think`, how many chained thinks in a row led to that action, 0 after an ordinary think; otherwise `None`)".
- **§4.6 catalog table**, a row after `leash_return`:

  ```
  | `chain_think(ctx)` | kernel | `bool`. `True` lets `ctx.actor`, a mob, think again as soon as it can act, ignoring `think_every` (§5.8 **Chained thinks**). |
  ```
- **§4.6, new paragraph after the **`move_pace`** paragraph:**

  > **`chain_think`**: asked when a tick-lane action that a mob's think chose finishes (§5.8 **Chained thinks**).
  > Without the export, the answer is `True` when `ctx.exit` isn't `None` and `ctx.behavior` is `"pursue"`, `"follow"`,
  > `"heel"` or `"leash"`, so chasers and mobs walking home keep moving at their pace. A world can chain other actions,
  > for example looting after a kill, or limit chains with `ctx.chain`. It is asked once a move's transition has landed,
  > at the end of its initiative tier, so `ctx.room` and `ctx.actor.room` are the room arrived in. A walk cancelled
  > because the mob was pinned on the way out (§5.5) asks nothing. A failing call, or a result that isn't a bool, counts
  > as the default. Plugins' `addChainThink` handlers then adjust the answer (§4.15). When defined, the load-time dry
  > run calls it with the throwaway mob, `behavior = "pursue"`, `command = "north"`, `exit = None` and `chain = 0`, and
  > checks that it returns a bool.

- **§5.8, a new bullet after **Scope**:**

  > - **Chained thinks:** when a tick-lane action that a mob's think chose finishes, after any warmup, the engine asks
  >   `rules.chain_think` (§4.6). `True` makes a *chained think* due: the mob thinks again as soon as it is idle and its
  >   movement debt is below 100, ignoring `think_every`. That can be in an extra move round of the same pulse (§5.1),
  >   where the action it chooses starts at once as in any think (a non-move action started there ends the mob's extra
  >   rounds for the pulse), or on a later pulse. A failed or denied action, an interrupted cast, an action `think` ran
  >   in the fast lane, or `False` asks nothing further: the mob is back on its cadence, counted from its last think. A
  >   chained think is an ordinary think: every behavior proposes, the return home is added, and `think` runs.

- **§5.8 think cycle, the opening sentence:** change "and `pulse − lastThink ≥ thinkEvery`" to "and either `pulse −
  lastThink ≥ thinkEvery` or a chained think is due and its movement debt is below 100".

- **§5.1 phase 2:**

  - Change "a mob whose think cadence is due" to "a mob whose think cadence is due, or which has a chained think due".
  - In the **Extra moves** bullet, before "The rounds stop early", add: "A mob with an empty queue and a chained think
    due also takes part: it thinks, and the action it chose starts in that round. A non-move action started in a round
    ends the actor's rounds for the pulse."
- **§4.7, a new bullet after **Going home**:**

  > - **Catching up:** a mob that moves for `pursue`, `follow` or `heel`, or walks home, keeps walking at its pace
  >   rather than its think cadence (§5.8 **Chained thinks**). A pursuer with a lower `move_pace` than its quarry gains
  >   on it, and a pet as fast as its owner doesn't fall further behind.

### Implementation Blueprint

- **Signatures & Types:**
  - `data class ThinkOrigin(val source: String, val command: String, val moves: Boolean)` in `Entities.kt`, next to
    `QueuedAction`. `moves` copies the queued command's `moves` flag.
  - `World.Deferred.Kind.CHAIN`, a new kind after `TRANSITION`.
  - `QueuedAction`: `var thinkOrigin: ThinkOrigin? = null`, mirroring `orderedBy`.
  - `CastState`: add `val thinkOrigin: ThinkOrigin? = null` as its last parameter.
  - `Mob`: `var chainDue: Boolean = false` and `var chainCount: Int = 0`. Both are transient and not saved.
  - `RulesContext`: `val command: String? = null` and `val chain: Int? = null`, with KDoc in the style of `away`. Keep
    every copy helper complete, such as `withDamageType`.
  - `RulesEngine.chainThink(c: RulesContext): Boolean? = null`. `null` means the engine default.
  - `execute(actor, action, orderedBy: EntityId? = null, origin: ThinkOrigin? = null)`
  - `finish(actor, action, origin: ThinkOrigin?)`
  - `internal fun World.askChain(mob: Mob, origin: ThinkOrigin)`, in `Actions.kt`.
  - `internal val CHAIN_DEFAULT_SOURCES = setOf("pursue", "follow", "heel", "leash")`
  - Script: `RulesExport.CHAIN_THINK("chain_think")`, plus a `RulesExportSpec(signature = "chain_think(ctx)", returnType
    = "bool", ctxFields = listOf("actor", "behavior", "command", "exit", "chain", "room", "pulse", "rng"), doc =
    "Whether ctx.actor, a mob, thinks again as soon as it can act.", hasDefault = true)`. Add the `command` and `chain`
    fields to the rules `ctx` view.
- **Algorithmic Steps & Invariants:**
  1. **Tagging, in `think`:**
     - Note `chained = mob.chainDue`, then set `mob.chainDue = false` and `mob.chainCount = if (chained) mob.chainCount
       + 1 else 0`.
     - After the `when (result)`, if the queue became non-empty during this think (it was empty before), set
       `queue.first().thinkOrigin`.
     - The origin's source is the accepted proposal's `source` for `Accept`, and `"think"` for `Act`. Its command is
       `queue.first().toString()`, and `moves` is `queue.first().command.moves`.
     - `Idle` tags nothing.
  2. **Threading the origin:** `runQueued` passes `queued.thinkOrigin` into `execute`. `execute` stores it in
     `CastState` when a warmup starts, and passes it to `finish` for an instant action. `advanceCast` passes
     `cast.thinkOrigin` to `finish` once re-validation passes. The interrupt path and the failed re-validation path ask
     nothing.
  3. **Asking after the move lands:** in `finish`, after the recovery lag is set, `if (actor is Mob && origin != null &&
     actor.inWorld && actor.casting == null) deferRemoval(actor, Deferred.Kind.CHAIN) { askChain(actor, origin) }`.
     - Inside a tier, the op runs in `commitDeferred` after the same actor's `TRANSITION` op, because ops are ordered by
       actor and then by sequence. The walk has landed, or been cancelled, by then.
     - Outside a tier, `deferRemoval` runs both ops at once, in the same order.
     - `commitDeferred` already skips an actor's non-death ops when it died, so a dead mob is never asked.
  4. **`askChain`:**
     - If `!mob.inWorld`, return. If `origin.moves && mob.lastMoveExit == null`, the move was cancelled or the flee
       failed: return without asking, which leaves `chainDue` false.
     - Build `RulesContext(this, mob, room = mob.room, exit = mob.lastMoveExit, behavior = origin.source, command =
       origin.command, chain = mob.chainCount)`.
     - The default is `mob.lastMoveExit != null && origin.source in CHAIN_DEFAULT_SOURCES`.
     - The answer is `rules.chainThink(c) ?: default`.
     - Set `mob.chainDue = answer`, and trace it like other rules calls.
     - `lastMoveExit` is the arrival exit only when this action moved, because `finish` cleared it before `resolve` and
       the landed move's `chargeMove` set it. `ctx.room` is therefore the room arrived in.
  5. **The main pass:** in `actionPulse`, the think branch becomes `a is Mob && ((a.chainDue && canMove(a)) || pulse -
     a.lastThink >= a.thinkEvery) -> think(a)`.
  6. **Extra rounds:**
     - A mob is also a participant when `it is Mob && it.chainDue && it.actionQueue.isEmpty() && it.lastMovePulse ==
       pulse && it.lastNonMovePulse != pulse && it.casting == null && it.waitState == 0 && canMove(it)`.
     - For such a mob the round calls `think(mob)`. Phase 3's same-pulse start applies unchanged: the chosen action
       starts at once, move or not. A non-move goes through `runQueued`, which sets `lastNonMovePulse`, so the mob takes
       no further rounds this pulse.
  7. **Invariants:**
     - A chain can't loop within a pulse. A non-move action ends the actor's extra rounds (`lastNonMovePulse == pulse`),
       and the rounds are capped.
     - A mob that thinks for any reason clears `chainDue` first, so a failed or denied action leaves it `false`.
     - `ctx.act`'s fast-lane commands never get an origin.
     - Script override: `if (!defines(...)) return null`. A non-bool or failing call records the rules diagnostic and
       returns `null`, so the default applies. Mirror `leashReturn`.

### Test & Documentation Requirements

- `ChainThinkTest` (core, a 10-room corridor, a player walking east one room per pulse from `r3` with queued moves):
  - **Catch-up:**
    - A `Pursue` mob at `r0` with pace 50 (`FixedRules` override for mobs) and `think_every = 4` reaches the player's
      room before the player reaches `r9`.
    - With `chainThink` overridden to `false`, it never catches up: assert it is still behind when the player stops at
      `r9`, for the same pulse.
  - **Equal pace doesn't fall further behind:** a `Heel` pet at pace 100 with `think_every = 4` has a gap to its walking
    owner that never grows beyond `think_every + 1` rooms once its first chained move has run. It reaches the owner's
    room within `think_every + 1` pulses of the owner stopping. Equal pace holds the gap; it doesn't close it.
  - **Wander doesn't chain by default:** a `Wander(chance = 100)` mob with `think_every = 4` moves only on its cadence
    thinks.
  - **Ask arguments:** an override recording `c.behavior`, `c.command`, `c.exit` and `c.chain` sees `pursue`, `east`,
    the exit, and `chain` going 0, 1, 2… across consecutive chained moves.
  - **No ask on failure:** a chaser whose move a `Guard` denies isn't asked, and returns to its cadence.
  - **A warmup action is asked at completion:** use a mob `think` override that casts a warmup skill. The ask comes once
    the cast resolves, with `exit` null. If a skill fixture is too heavy, test through `execute` with a fake `Action`
    whose `warmup = 2`.
  - **The quarry is caught and stays caught** (`track` and `combat` on):
    - Use a pace-50 mob with `Aggressive(toward = "players")` and `Pursue`, and a player walking east with moves queued.
    - When the mob reaches the player's room in an extra round, its chained think starts `kill` in that same round.
    - On the next pulse the player's queued walk is refused as pinned, and the two are still engaged.
    - Run it once with the mob's id lower than the player's and once higher. The outcome is the same.
  - **A chained non-move ends the rounds:** with an override that always returns `true`, a mob whose `kill` started in
    an extra round takes no further extra round that pulse.
  - **`ctx.room` is the arrival room:** the recording override sees `c.room` equal to the room the mob just entered.
  - **A cancelled walk asks nothing:** a chaser whose walk is cancelled because another actor pinned it in the same tier
    isn't asked, and returns to its cadence.
- `ChainThinkRulesTest` (script):
  - A rules `chain_think` returning `ctx.chain < 2` limits a pace-50 pursuer to three consecutive moves before it
    returns to its cadence.
  - Without the export, a pursuer chains.
  - A `return 3` or `fail("x")` export falls back to the default and records a diagnostic.
  - `ctx.behavior` reads `"think"` for a mob whose `think` returns `ctx.act(ctx.self, "east")`.
- CHANGELOG bullet: "Chained thinks: the optional rules export `chain_think(ctx)` lets a mob think again as soon as its
  action finishes (`ctx.behavior`, `ctx.command`, `ctx.exit`, `ctx.chain`). By default, `pursue`, `follow`, `heel` and
  walking home chain, so pursuers catch up at their `move_pace`."

### Exit Conditions

- [] `./gradlew :core:test --tests '*ChainThinkTest*'` passes.
- [] `./gradlew :script:test --tests '*ChainThinkRulesTest*'` passes.
- [] API files regenerated. The diff names only `chain_think`, `command` and `chain`.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 5: The `addChainThink` Plugin Handler

### Objective & Scope

- **In Scope:**
  - A core adjuster list run after the export.
  - The plugin API method, its call data class and its adapter.
  - `PLUGINS.md` and §4.15.
- **Out of Scope (the agent must not implement these in this phase):** any change to when the engine asks, or to the
  default.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Behaviors.kt`: `TargetOrderCall`, `TargetOrderAdjuster`, and the adjuster loop
    with its error handling in `chooseTarget`.
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`: `addTargetOrder` and `PluginTargetOrderCall`.
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`: `targetOrders`, the wiring loop that sets
    `world.targetOrderAdjusters`, and the plugin-info listing (`PluginTargetOrderInfo`).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt`: `targetOrderAdapter`.
  - The existing plugin target-order test in `plugin-api/src/test`; find it by searching for `addTargetOrder`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Actions.kt`: `ChainThinkCall`, `ChainThinkAdjuster`, and the loop in
    `askChain`.
  - `core/src/main/kotlin/gups/core/world/World.kt`: `var chainThinkAdjusters: List<ChainThinkAdjuster> = emptyList()`.
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt`
  - `plugin-api/src/test/kotlin/.../PluginChainThinkTest.kt` (new, beside the target-order test)
  - `docs/PLUGINS.md`, `docs/SPEC.md`, `docs/CHANGELOG.md`, and the API files.
- **Do Not Touch:** `script/` (except regenerated docs), `template-world/`, `persist/`.

### Specification & Decision Record Changes

- **§4.15:** in the registration list that names `addTargetOrder`, add "chained thinks (`addChainThink`)".
- **§4.15:** a new bullet after **Target order (`addTargetOrder`)**:

  > - **Chained thinks (`addChainThink`):** adjusts the `chain_think` answer (§5.8) after the world's export, in
  >   plugin-id order. A handler gets a read-only `PluginWorld`, the mob, the proposal source, the command line, the
  >   exit's direction name (or null), the chain count and the answer so far, and returns the new answer. A handler that
  >   throws records a `plugin` diagnostic and keeps the answer so far. Must be fast and side-effect free.

- **§4.6 `chain_think` paragraph:** it already says plugins adjust the answer. Check that the wording matches.

- **`PLUGINS.md`:** a short section after the `addTargetOrder` section, with a 6-to-10-line Kotlin example: a plugin
  that chains every action of mobs whose label matches `*_hound`.

### Implementation Blueprint

- **Signatures & Types:**
  - `class ChainThinkCall(val mob: Mob, val source: String, val command: String, val exit: Exit?, val chain: Int)`
  - `fun interface ChainThinkAdjuster { fun adjust(world: World, call: ChainThinkCall, chained: Boolean): Boolean }`
  - `PluginApi`: `fun addChainThink(doc: String, decide: (PluginChainThinkCall) -> Boolean)`, with KDoc mirroring
    `addTargetOrder`.
  - `data class PluginChainThinkCall(val world: PluginWorld, val mob: PluginActor, val source: String, val command:
    String, val exit: String?, val chain: Int, val chained: Boolean)`
  - `PluginHost`: `val chainThinks = mutableListOf<Pair<String, (PluginChainThinkCall) -> Boolean>>()`, the override,
    the wiring loop that sets `world.chainThinkAdjusters`, and a plugin-info entry if target orders have one.
- **Algorithmic Steps & Invariants:**
  1. In `askChain`, after `rules.chainThink(c) ?: default`, fold over `chainThinkAdjusters`. On an exception, record a
     `plugin` diagnostic in the style of `chooseTarget`, and keep the answer so far.
  2. The adapter converts `exit` to its direction name, and builds the `PluginWorld` and `PluginActor` the way
     `targetOrderAdapter` does.

### Test & Documentation Requirements

- `PluginChainThinkTest`, mirroring the target-order plugin test:
  - A handler returning `true` makes a `Wander` mob chain.
  - A handler returning `chained` unchanged keeps the default.
  - A throwing handler records a diagnostic and keeps the answer.
  - Two plugins run in plugin-id order: the second sees the first's answer as `chained`.
- CHANGELOG bullet: "Plugins can adjust chained thinks with `addChainThink`."

### Exit Conditions

- [] `./gradlew :plugin-api:test --tests '*PluginChainThinkTest*'` passes.
- [] API files regenerated, if plugin API docs are part of them. The diff names only `addChainThink` and
  `PluginChainThinkCall`.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 6: Template World Pace, Chaining and Contested Exits

### Objective & Scope

- **In Scope:**
  - The template's `speed` stat.
  - `starting_stats`, which defaults speed to 100.
  - The `move_pace` and `chain_think` exports.
  - A contested-exit sample with test data and a test.
  - Tutorial updates.
- **Out of Scope (the agent must not implement these in this phase):**
  - Adding fast mobs to the live tutorial zone's rooms. The sample mobs live only in a `*_testdata.star` file.
  - Any engine change.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/rules/combat.star`: `starting_stats`, `move_cost` and `_or`.
  - `template-world/rules/targeting.star`, for the style of a rules file with module constants.
  - `template-world/zones/tutorial/ai_probes_testdata.star` and `ai_test.star`, for the test-data and test pattern.
  - §4.4 (hook catalog: `before_exit` on a Mob observer) and §4.5 (the actor view: `fighting()` and `move_pace`).
  - The room `before_exit=_guard_check` example in §4.1 and §4.2, for how a hook denies.
- **Files to Modify / Create:**
  - `template-world/MODULE.gups`: add `stat("speed", label = "Speed")` after `cha`.
  - `template-world/rules/combat.star`: `starting_stats`.
  - `template-world/rules/movement.star` (new): `move_pace` and `chain_think`. Also update the file list in the comments
    of `rules/package.star`.
  - `template-world/lib/pursuit.star` (new): `cut_off(ctx)`.
  - `template-world/zones/tutorial/pursuit_testdata.star` (new) and `pursuit_test.star` (new).
  - `template-world/docs/tutorials/05_mobs_and_behaviors.md` and the tutorial that documents `MODIFIER.move_cost`
    (`02_rooms_and_navigation.md`).
  - `samples/robot-world` only if a shared rules check fails without it. Optional exports need nothing there.
- **Do Not Touch:** `core/`, `script/`, `plugin-api/`.

### Specification & Decision Record Changes

- None in `SPEC.md`. The §4.6 template-ruleset paragraph lists files: add `movement.star` to its file list if it
  enumerates them.

### Implementation Blueprint

- **Template code:** the rules functions and the `cut_off` helper:

```python
# rules/movement.star
_CHASERS = ["pursue", "follow", "heel", "leash"]

def move_pace(ctx):
    # Hundredths of a pulse per room: a speed of 100 walks a room a pulse, 300 three a pulse.
    return 10000 // max(ctx.actor.speed, 10)

def chain_think(ctx):
    # A mob that just stepped after its quarry, leader or owner, or toward home, takes its
    # next step as soon as its pace allows instead of waiting for its next think.
    return ctx.exit != None and ctx.behavior in _CHASERS
```

```python
# lib/pursuit.star
def cut_off(ctx):
    # A before_exit for a fast hunter: someone it is fighting who is slower may be cut off.
    me, them = ctx.self, ctx.actor
    if them == me or them not in me.fighting():
        return None
    if me.move_pace >= them.move_pace:
        return None
    chance = min(90, 100 - 100 * me.move_pace // them.move_pace)
    if ctx.roll("1d100") <= chance:
        return ctx.deny("%s cuts you off." % me.name.capitalize())  # use the deny form §4.4 gives
    return None
```

- **Algorithmic Steps & Invariants:**
  1. `starting_stats`:
     - Players get `speed = 100` in their `stats(...)`.
     - Mobs get `speed = _or(a.base.speed, 100)`.
     - Check the exact `fighting()`, `roll` and `deny` spellings against §4.5 and §4.4, and adjust the sample to match.
       The code above shows the intended logic, not necessarily the exact API.
  2. `pursuit_testdata.star`:
     - A short corridor of rooms.
     - A `hunting_cat`: `stats(speed = 300)`, behaviors `aggressive(toward = "players")` and `pursue("players")`,
       `before_exit = cut_off` as a mob observer.
     - A `goblin_plodder`: `stats(speed = 75)`, `pursue("players")`.
  3. Check every existing template test still passes with the new stat. Score or `Char.Vitals` output may now list
     Speed; update those expectations.

### Test & Documentation Requirements

- `pursuit_test.star`:
  - **Catch-up:**
    - A player 3 rooms ahead of the cat walks away, one move per `do`.
    - The cat reaches the player's room before the player reaches the corridor's end.
    - The plodder never reaches the player while the player keeps walking.
  - **Cut-off:**
    - A player fighting the cat tries `flee` and then a walk, repeatedly with a fixed seed.
    - At least one attempt gets "cuts you off."
    - With the plodder in the cat's place, no attempt ever does.
  - **Pace views:** `cat.move_pace == 33`, a player's is `100`, and the plodder's is `133`.
- Tutorial 05:
  - A short section on speed: the `speed` stat, `move_pace` and chained thinks.
  - Haste as an affect that adds `speed`, shown as an `apply_affect(..., stat_deltas = stats(speed = 50))` snippet.
- Tutorial 02: add `MODIFIER.move_pace` next to `MODIFIER.move_cost`.

### Exit Conditions

- [] The template's `*_test.star` files, including `pursuit_test.star`, pass under `./gradlew check`: the engine's test
  suite checks `template-world/` on every run. To run them alone, `./gradlew :cli:installDist`, copy the template to
  `local-world-test/world` (never run against `template-world/` directly, README), then `cli/build/install/gups/bin/gups
  test --world local-world-test/world //zones/tutorial/...`.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 7: Finalization & Backlog Update

- **End-to-end check** (`AGENTS.md` **End-to-end checks**):
  - Copy the template world into `local-world-test/world`.
  - Add a fast hunter to a tutorial room: `speed = 300`, `aggressive` and `pursue`, `before_exit = cut_off`.
  - Write `local-world-test/scenario.star`:
    - A player engages the hunter, then flees, retrying until the flee succeeds.
    - The player keeps walking.
    - Assert that the hunter reaches the player's room within a few rooms of the flee, attacks, and that the player's
      next walk is refused as pinned: the player is caught and stays caught.
    - Print the transcript.
  - Repeat with the hunter at `speed = 75`, and assert that it falls behind.
  - Delete `local-world-test/` afterwards.
- **Backlog:**
  - In `docs/DESIGN_NEEDED.md`, under `### Movement speed and pursuit catch-up`, add this as the first line of the entry
    body: "**Status:** completed in Milestone 46 (pending designer review)."
  - Do not delete the entry.
- **Final pass:**
  - Confirm that `docs/CHANGELOG.md`'s `### Movement pace and pursuit catch-up` subsection holds the Phase 1 to 5
    bullets.
  - Confirm that §3.1, §4.5, §4.6, §4.7, §4.8, §4.15, §5.1, §5.5 and §5.8 match the implemented behavior.
  - Run `./gradlew markdownFormat`, then `./gradlew check`. Both must pass with zero warnings.
