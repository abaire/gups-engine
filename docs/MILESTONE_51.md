# Milestone 51: Timed Tasks and the Crafting Library

**Prerequisites:** [M50](MILESTONE_50.md) must be finished first. Where line numbers below are wrong, find the symbol by
name.

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** [DESIGN_NEEDED.md](DESIGN_NEEDED.md): `## Economy and crafting` → `### Crafting`.

- **Design Summary:**

  - **Two layers.** Generic engine verbs (timed tasks, a skill roll outside casts, item renaming), exposed to scripts
    and to Kotlin plugins, and a `//lib:craft.star` library built only on those verbs and existing ones. The library
    ships the *mechanism*; the template world ships a small tutorial forge, not a recipe catalog.

  - **The activity slot moves to the kernel.** Today `Actor.casting: CastState?` holds any `Action` whose `warmup > 0`,
    but it belongs to the `skills` feature. It becomes the kernel **activity slot**: `Actor.activity: ActivityState?`.
    An actor either warms up a cast or runs a timed task, never both. Everything that waits for or breaks a cast does
    the same for a task: the action queue waits, `stop`/`clear` cancel it, stuns and damage interrupt it, death, removal
    from the world, dormancy and a mob despawn clear it, and a stun or incapacitation pauses it. Casts stay `skills`;
    the slot, warmups, interrupts and `interrupt_check` become kernel.

  - **Timed tasks (kernel).** The full contract; Phases 2 and 3 implement it.

    ```python
    err = ctx.start_task(actor, pulses, on_done, name = "task", lag = 0,
                         interrupt = {"stun": True, "damage_pct": None},
                         on_stop = None, **args)      # None when started, else a refusal string
    ctx.stop_task(actor)                              # -> bool; cancels with reason "cancelled"
    ```

    - Players and mobs, from any hook or command. `pulses` at least 1, `lag` at least 0. `name` is a non-empty single
      line of at most 80 characters, conventionally a gerund phrase ("eating", "forging an iron dagger"), since the
      engine's lines read "You stop {task}.". `interrupt` takes only the keys `stun` (bool) and `damage_pct` (int 1..100
      or `None`). Extra keyword `args` must be storable, as for `ctx.after`.
    - Refused (returns the refusal message, changes nothing): `task.unable` when the actor is out of the world, its
      condition isn't `ok`, or it is stunned; `task.busy` when it is already casting or running a task, or has recovery
      lag.
    - A tick-lane command that starts a task leaves its actor busy, so the command's own recovery isn't applied (the
      existing `isFree` check in `finish`).
    - `on_done(ctx, **args)` runs once the warmup ends and re-validation passes (in the world, `ok`, not stunned).
      Recovery is then `lag`.
    - `on_stop(ctx, reason, **args)`, if given, runs with `reason` (a string) as its second positional argument:
      - `"interrupted"`: a stun (when `interrupt.stun`), or damage that `interrupt_check` accepts. Costs `lag / 2`.
      - `"cancelled"`: `stop`, `clear` or `stop_task`. No lag.
      - `"invalid"`: re-validation at the end failed. Costs `lag / 2`.
      - `"gone"`: death, removal from the world (quit, link-dead expiry, despawn), dormancy. Best effort, no lag.
    - Callbacks are top-level functions run like timers: `ctx.self` is the starter's `ctx.self`, `ctx.actor` the actor,
      `ctx.task` the task view. A callback whose owner has left the world is skipped. A callback that fails, or whose
      package was reloaded away or quarantined, is a `script` diagnostic and nothing else; the task still ends normally.
    - `rules.interrupt_check` is called with `ctx.skill = None` and `ctx.task` set when a task's actor takes damage. The
      template rule reads `(ctx.skill or ctx.task).interrupt`.
    - Views: `actor.task` is `struct(name, started_at, ends_at, remaining, interrupt)` or `None`; `actor.is_busy` is
      true for a cast or a task. `actor.casting` and `actor.is_casting` keep meaning casts only.
    - `Char.Status` gains a kernel field `"task": {"name", "progress"}` or null. `casting` stays `skills` and reports
      only casts. `@stat` shows the task.
    - Not saved: like casts, a restart drops a task with no callback.
    - The engine sends only `task.interrupted`, `task.invalid` and `stop.task` about a task, plus the refusals the verb
      returns. The caller sends its own begin and finish lines.
  - **Skill rolls outside casts (`skills`).**

    ```python
    r = ctx.skill_roll(actor, skill, item = None, target = None, event = "craft", adjust = 0)
    # r: struct(chance, roll, success, power)
    ```

    `rules.skill_check` and the actor's `skill_check` chain with `ctx.skill` the skill view, `ctx.item`, `ctx.target`
    and `ctx.event` (casts leave it `None`); then `adjust` is added to the chance; a chance of 100 or more succeeds with
    `roll = None`, otherwise one seeded 1..100 draw. On success, `power` is `rules.skill_power` and its chain; on
    failure `power = 0`. No `on_skill_use`, no cost. The actor need not know the skill; an unknown skill is an error.

  - **Item names (kernel).** `ctx.set_item_name(item, name)` sets the existing `Item.nameOverride` (`None` clears it). A
    non-empty single line of at most 80 characters; returns whether the override changed. Keywords don't change.

  - **Plugin API.** `PluginWorld.startTask`/`stopTask`/`skillRoll`/`setItemName`, `PluginActor.task`/`isBusy`, with the
    same semantics. `samples/hunger-plugin` gains `hunger.dine(actor, amount, pulses)`, which eats over a timed task.

  - **The library, `//lib:craft.star`** (`load("//lib:craft.star", "craft")`):

    - **Stations** are any room, ground item or carried item (not worn, not inside a container) with the custom hook
      `craft_recipes(ctx)`, which returns a list of recipes. Zones compose any number of recipe modules there (`return
      basic.SMITHING + advanced.SMITHING`); plugins join with `addHook("craft_recipes", …)`. Within one station a later
      recipe with the same id replaces an earlier one; the library `print`s a line about it to the trace (a library
      can't record engine diagnostics).
    - **Recipes** are plain data (dicts of ints, strings, bools, lists and dicts with no `None`, since they pass through
      `ctx.call_hook` and are stored in task arguments):

    ```python
    craft.recipe("steel_sword",
          name = "a steel sword", verb = "forge",       # doing defaults to "forging"
          inputs = [craft.need("//zones/smithy:steel_bar", count = 2, lose = "1d2"),
                    craft.need(tag = "wood", lose = 1)],
          tools = [craft.need(tag = "hammer", charges = 0)],
          skill = "//skills/crafts:smithing", difficulty = 10,
          time = 20, lag = 4, learned = False,
          failure = craft.failure(lose = "50%", make = None, message = None),
          qualities = [craft.quality(at = 95, name = "a masterwork steel sword",
                                     stat_deltas = {"dex": 2}, add_tags = ["masterwork"])],
          result = craft.make("//zones/smithy:steel_sword", count = 1),
    )
    craft.recipe("hone_edge", verb = "hone", target = craft.need(kind = "melee"),
          inputs = [craft.need(tag = "grit")], time = 10,
          result = craft.improve(affect = "honed", stat_deltas = {"dex": 1}, add_tags = ["honed"],
                                 unless_tag = "honed"))
    ```

    - **Task names:** a recipe's `doing` (default: `verb` minus a trailing `e`, plus `ing`: forge → forging, hone →
      honing, smelt → smelting) names the task `"<doing> <name>"`, so `stop` says "You stop forging a steel sword.".
    - **Matching:** a `need` names exactly one of `label`, `tag`, `kind`; inputs and tools match the crafter's carried
      items only; stacks count; one item never satisfies two needs. The `target` of an `improve` recipe is named by the
      player (`craft hone_edge on dagger`) and may be carried or worn.
    - **Tool charges:** a tool need with `charges = n > 0` matches only a non-stackable item with at least `n` charges
      left, or one with unlimited charges (which spends nothing). The library spends charges with `ctx.set_charges`; a
      tool that reaches 0 stays in the inventory (no `empty` action runs) and no longer matches charged needs until
      something recharges it. `craft.need(..., charges = n)` on an input need fails at load.
    - **Timing:** `craft` checks everything, records the matched item ids (nothing is consumed), and calls
      `ctx.start_task`. On completion the library re-checks the station's reach and that the recorded items are still
      held in the needed counts, then rolls, consumes and makes. A stopped task consumes nothing.
    - **Failure loss:** each input's `lose` is an int (absolute), a dice string (`"1d4-1"`, rolled with `ctx.roll`), a
      percent string (`"50%"`, of the need's `count`, rounded up), `"all"` or `0`, clamped to `0..count`. A need without
      `lose` uses `failure.lose`; an omitted `failure` is `craft.failure(lose = "50%")`. Success always uses every
      need's full `count`. Tool charges are spent either way. `failure.make` gives a flawed product. A failed `improve`
      leaves the target alone.
    - **Quality:** the highest tier whose `at` ≤ the roll's power applies to every made item (or to the improved item):
      `set_item_name` when `name` is given, a permanent item affect named `affect` (default `"quality_<at>"`) when it
      has deltas, and `add_tags`. A recipe with no skill rolls nothing and has power 100.
    - **Difficulty:** `skill_roll(..., adjust = -difficulty)`, or the int a `craft_check` hook returns instead.
    - **Learned recipes:** `learned = True` recipes are listed and usable only for actors who know them;
      `craft.teach(ctx, actor, recipe_id)` and `craft.knows(ctx, actor, recipe_id)` keep the ids in the lib's
      `craft_known` attribute on the actor.
    - **Commands** (global commands of `//lib`): `recipes` (fast lane) lists the recipes in reach and sends GMCP
      `Craft.Recipes`; `craft <recipe> [on <item>]` (tick lane) starts one. `stop` cancels through the engine. Mobs may
      craft through `ctx.act(mob, "craft …")`; `ctx.gmcp` already sends nothing to a mob.
    - **Station hooks**, both via `ctx.call_hook` on the station that offered the recipe:
      - `craft_check(ctx, crafter, recipe, target, adjust)`, once at start: `None` keeps the default, an int replaces
        `adjust`, a string refuses with that message.
      - `craft_done(ctx, crafter, recipe, outcome, items)`, after the craft resolves (`outcome` `"success"` or `"fail"`,
        `items` the views made or improved); the result is ignored.
    - **Skill use:** every completed roll of a recipe that names a skill calls `ctx.report_skill_use`.
    - **GMCP events** (declared with `gmcp_package` in `//lib`; `None` is sent as JSON null):
      - `Craft.Recipes`, sent by `recipes`: `{"recipes": [{"id", "name", "verb", "station", "skill" or null, "time",
        "learned", "target": bool, "ready": bool, "needs": [{"name", "count", "have", "lose"}], "tools": [{"name",
        "have"}]}]}`. `station` is the offering station's name; `ready` means every need is met now; `lose` is the
        need's own value, or `failure.lose` when it has none. Unknown learned recipes are left out.
      - `Craft.Result`, sent when a craft ends: `{"recipe", "outcome": "success"|"fail"|"stopped", "quality" or null,
        "items": [names made or improved], "lost": [{"name", "count"}]}`. `quality` is the reached tier's `name`, or
        `"q<at>"` for a tier without a name, or null.
    - **Without `skills`:** a recipe naming a skill is shown as unavailable and refused ("That recipe needs skills this
      world doesn't have."); recipes without one work.
- **Specification Impact:**
  - §1.6 glossary (tick lane), §3.3 (Actor action state; Item name override), §3.10 (feature table: kernel gains the
    activity slot, warmups, interrupts, `interrupt_check`, tasks, `set_item_name`; `skills` keeps casts and gains
    `skill_roll`), §4.5 (ctx field `task`; actor view `task`, `is_busy`; verbs table and bullets for `start_task`,
    `stop_task`, `skill_roll`, `set_item_name`), §4.6 (`interrupt_check` row and `ctx.task`; `skill_roll` trace), §4.9
    (`stop`; `//lib:craft.star` paragraph), §4.10 (`Char.Status`), §4.15 (plugin verbs), §4.18 (new messages), §5.3
    (activities and tasks), plus `docs/PLUGINS.md` and `docs/CHANGELOG.md`.
- **Decision Record Updates:** None. `docs/DECISIONS.md` has no entries.

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first, run them and watch them fail, then write the code.
   - No spec section numbers or decision ids in comments, Kotlin or Starlark. Comments explain why, not what.
   - All Kotlin warnings are errors. Identifiers say **skill**, never **spell**.
   - After changing code, run the CodeScene `code_health_review` on the changed files and fix any drop in Code Health;
     run `pre_commit_code_health_safeguard` before finishing a phase.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`. The engine is
   unreleased, so no migrations or back-compat shims are needed.
3. CHANGELOG: add a new `### Timed tasks and crafting` subsection at the top of `## api_version 1 (unreleased)` in
   `docs/CHANGELOG.md`, above the current first subsection. Phase 1 creates it; each later phase appends its bullets.
4. In **every** phase, regenerate the three API files before the phase's final `./gradlew check` (an empty diff is
   fine):
   - `./gradlew :cli:test -Dgups.updateGolden=true` rewrites `cli/src/test/resources/api.golden.json` and
     `cli/src/test/resources/docs-golden/`.
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout rewrites `docs/api/api-1.json`.
   - Read the diff: only the symbols named in that phase should be new or changed; `ApiDiffTest` must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring a neighboring verb's example.
5. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation (see `AGENTS.md` on code blocks in lists).
6. Never read or touch `local-world/`. Scratch scenarios and world copies go in `local-world-test/`, which you delete
   when done.

---

## Phase 1: The kernel activity slot (refactor, no new verbs)

### Objective & Scope

- **In Scope:**
  - Rename `CastState` → `ActivityState` and `Actor.casting` → `Actor.activity` everywhere in `core`, `script`, `net`,
    `harness`, `persist` and `plugin-api`. Keep `isCasting`, now meaning "the activity's action is a `Castable`". Add
    `isBusy` (`activity != null`). `isIdle` uses `activity`.
  - Move the slot's ownership from `skills` to the kernel: warmups, `ActivityState`, interrupts and `interrupt_check`
    belong to the kernel; `cast`, the cast action, `cast_allowed`, `skill_check`, `skill_power`, the `cast.*` messages
    and `casting` in `Char.Status` stay `skills`.
  - `stop`/`clear` cancel any activity, not only when `skills` is on.
  - Invariants: "casting without skills" becomes "cast without skills" (an activity whose action is `Castable` while
    `skills` is off); "both in recovery and casting" becomes "both in recovery and busy".
  - Behavior is identical for every existing world and test.
- **Out of Scope:** *Do not implement these; later phases do.* Any timed-task action, `start_task`, `stop_task`,
  `skill_roll`, `set_item_name`, new messages, `Char.Status` changes, plugin API changes, script view additions, and
  anything under `template-world/`.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Actions.kt` (`Action`, `Castable`, `execute`, `advanceCast`, `finish`,
    `interruptCast`, `cancelCast`).
  - `docs/SPEC.md` §3.3 (Actor "Action and combat state"), §3.10 (feature table rows `kernel` and `skills`), §4.6 (rules
    table), §5.3.
- **Files to Modify:**
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`CastState` ~line 260, `Actor.casting` ~448, `isCasting` ~650,
    `isIdle` ~655).
  - Every reference found by `grep -rn "\.casting\b\|CastState" core script net harness persist plugin-api`, at least:
    `world/Actions.kt`, `world/World.kt` (`actionPulse` ~907, `takesExtraMove` ~950), `world/Bubble.kt:220`,
    `world/Reconcile.kt:258`, `world/Death.kt:64`, `world/Affects.kt:131,204,354`, `world/Vitals.kt:140`,
    `world/Combat.kt:178`, `world/Gmcp.kt:145`, `world/StatLines.kt:142`, `world/Invariants.kt:77-78`,
    `world/KernelCommands.kt:183`, `script/Views.kt:367,468`, `harness/Handles.kt:869`, `harness/FuzzWalker.kt:313`.
  - `script/src/main/kotlin/gups/script/Ownership.kt` (~line 36): remove `"rules:interrupt_check"` from the `skills`
    `owns(...)` list (kernel names are unlisted).
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API files.
- **Do Not Touch:** `template-world/`, `samples/`, `docs/DESIGN_NEEDED.md`, the public plugin interfaces
  (`plugin-api/.../Facade.kt`, `PluginApi.kt`).

### Specification & Decision Record Changes

- §3.3 Actor, "Action and combat state": replace "active `casting` state" with "the `activity` slot (a cast's warmup or
  a timed task)".
- §3.10 table: in the `skills` row remove "warmups and `CastState`, interrupts," and "`interrupt_check`,"; in the
  `kernel` row add "the activity slot (warmups, interrupts, the `interrupt_check` export), `stop` and `clear`".
- §4.6 rules table: the `interrupt_check` row's feature column becomes `kernel`.
- §5.3 step 3: the warmup state is the actor's activity; casts are one kind of activity. Last paragraph: "Casts and
  `cast` are the `skills` feature; warmups, interrupts and recovery are kernel."
- CHANGELOG bullet: "The warmup slot is kernel: `stop`/`clear` cancel an activity in every world, and `interrupt_check`
  belongs to the kernel."

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  /** What an actor is in the middle of: a cast's warmup or a timed task. */
  class ActivityState(
    val action: Action,
    val startedAt: Long,
    val endsAt: Long,
    val orderedBy: EntityId? = null,
    val thinkOrigin: ThinkOrigin? = null,
  )
  // Actor
  var activity: ActivityState? = null
  val isBusy: Boolean get() = activity != null
  ```

  `isCasting` must become `activity?.action is Castable`. `Castable` lives in `gups.core.world`; if the module-graph
  check forbids referencing it from `gups.core.model`, replace the member with an extension `val Actor.isCasting` in
  `Actions.kt` and update its callers.

- **Algorithmic Steps & Invariants:**

  1. Rename mechanically; update KDoc where "casting" means the slot.
  2. `KernelCommands.stop` (~183): drop the `Feature.SKILLS in w.features &&` guard; keep `SkillMsg.STOP_CAST` for a
     cast.
  3. `Gmcp.kt` `Char.Status`: `casting` is still sent only with `skills`, and only for a `Castable` activity (null
     otherwise). Today only casts sit in the slot, so output is unchanged.
  4. `Views.kt` `actor.casting` / `is_casting`: unchanged meaning (casts only).
  5. `Invariants.kt`: `if (Feature.SKILLS !in features && a.activity?.action is Castable) out += "$a: cast without
     skills"`; `if (a.waitState > 0 && a.activity != null) out += "$a: both in recovery and busy"`.

### Test & Documentation Requirements

- Existing tests are the regression suite. Update only tests that assert the exact invariant strings or reference the
  renamed symbols.
- Add a Kotlin test next to the existing cast/interrupt tests (find them with `grep -rln "interruptCast\|advanceCast"
  core/src/test`): in a world **without** `skills`, `stop` answers "You have nothing to stop.", no invariant is
  violated, and `isBusy` is false for an idle actor.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings; API files regenerated (only the `interrupt_check` feature badge may
  change).
- [] `grep -rn "CastState\|\.casting\b" core script net harness persist plugin-api` finds nothing but comments about
  casts.
- [] Spec §3.3, §3.10, §4.6 and §5.3 match the code.

---

## Phase 2: Timed tasks in the core

### Objective & Scope

- **In Scope:** the task action, `World.startTask`, `World.stopTask`, end reasons and their callbacks, interrupts with
  `RulesContext.task`, the messages, `Char.Status.task`, `@stat`, Kotlin tests.
- **Out of Scope:** *Do not implement these; later phases do.* Any Starlark verb, ctx field or view (`ctx.start_task`,
  `ctx.task`, `actor.task`), the template world's rules, `skill_roll`, `set_item_name`, plugin API changes.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Actions.kt` (lifecycle), `Hooks.kt` (`HookContext` ~208, `World.invokeRef`
    ~484, `withCascadeGuard`), `Combat.kt` `interruptCastOnDamage` (~177), `Vitals.kt:140`, `Affects.kt` `stunsCaster`
    (~204) and `disabledChanged` (~345).
  - `core/src/main/kotlin/gups/core/world/KernelMessages.kt` (declarations such as `STOP_PLANS` ~88).
  - `core/src/main/kotlin/gups/core/world/World.kt` `removeActor` (~699) and the deferral helpers (`deferRemoval`).
- **Files to Modify / Create:**
  - Create `core/src/main/kotlin/gups/core/world/Tasks.kt`.
  - Modify `Actions.kt`, `World.kt` (`removeActor`), `Death.kt` (`wipeActivity`), `Bubble.kt` (`sleep`), `Reconcile.kt`
    (`despawn`), `Combat.kt`, `Vitals.kt`/`Affects.kt` (stun interrupt), `Gmcp.kt`, `StatLines.kt`, `KernelMessages.kt`,
    `KernelCommands.kt` (`stop`), `Hooks.kt` (`HookContext.task`), the `RulesContext` class in
    `core/src/main/kotlin/gups/core/rules/` (new field `task`).
  - Create `core/src/test/kotlin/gups/core/world/TasksTest.kt`.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API files.
- **Do Not Touch:** `script/`, `plugin-api/`, `samples/`, `template-world/`.

### Specification & Decision Record Changes

- §3.10 kernel row: add "timed tasks".
- §4.9 `stop`/`clear`: "cancel a cast or a timed task".
- §4.10 `Char.Status`: add `"task": {"name", "progress"} or null` (kernel); `casting` reports casts only.
- §4.18: the five new message ids below.
- §5.3: a paragraph "**Timed tasks.**" A task is an activity started directly (never queued) by a script or plugin. It
  has no veto phase and no cost. It follows steps 3, 4 and 6 with these ends: completion (re-validation passes; the
  callback runs; recovery `lag`), interrupted (stun or `interrupt_check`; `lag / 2`), cancelled (`stop`, `clear`, the
  stop verb; no lag), invalid (re-validation fails; `lag / 2`), gone (death, removal, dormancy; no lag). Not saved.
- CHANGELOG bullets: the new messages and `Char.Status.task`.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Tasks.kt
  enum class TaskEnd(val word: String) {
    INTERRUPTED("interrupted"), CANCELLED("cancelled"), INVALID("invalid"), GONE("gone"),
  }

  /** A snapshot of a task, for callbacks and rules. */
  data class TaskInfo(
    val name: String, val startedAt: Long, val endsAt: Long, val lag: Int, val interrupt: Interrupt,
  )

  /** What a task does when it ends; the script host and the plugin host implement it. */
  interface TaskCallbacks {
    fun done(world: World, actor: Actor, task: TaskInfo)
    fun stopped(world: World, actor: Actor, task: TaskInfo, reason: TaskEnd)
  }

  class TaskAction(
    val name: String,
    override val warmup: Int,
    override val lag: Int,
    override val interrupt: Interrupt,
    val callbacks: TaskCallbacks,
  ) : Action {
    lateinit var info: TaskInfo                               // set by startTask
    override fun validate(w: World, actor: Actor): String?   // null if in world, ok and not stunned,
                                                              // else w.msg(KernelMsg.TASK_INVALID, "task" to name)
    override fun resolve(w: World, actor: Actor): Int        // runs callbacks.done guarded; returns lag
  }

  /** Null when started, else the refusal for the actor. */
  fun World.startTask(
    actor: Actor, pulses: Int, name: String, lag: Int, interrupt: Interrupt, callbacks: TaskCallbacks,
  ): String?
  fun World.stopTask(actor: Actor): Boolean                       // CANCELLED; false when no task
  fun World.taskOf(actor: Actor): TaskInfo?                       // the activity's task, else null
  internal fun World.endActivity(actor: Actor, reason: TaskEnd)   // clears the slot; a task runs stopped()

  // KernelMessages.kt (KernelMsg)
  val TASK_BUSY = msg("task.busy", "You're busy.")
  val TASK_UNABLE = msg("task.unable", "You can't do that right now.")
  val TASK_INTERRUPTED = msg("task.interrupted", "You are interrupted!")   // placeholder task (optional)
  val TASK_INVALID = msg("task.invalid", "You can't finish {task}.")      // placeholder task
  val STOP_TASK = msg("stop.task", "You stop {task}.")                     // placeholder task
  ```

  `HookContext` gains `val task: TaskInfo? = null`; `RulesContext` gains `val task: TaskInfo? = null`.

- **Algorithmic Steps & Invariants:**

  1. `startTask` validates `pulses ≥ 1`, `lag ≥ 0`, `name` non-blank, single line, ≤ 80 characters, and
     `interrupt.damagePct` null or 1..100 (`IllegalArgumentException` otherwise). Refuse with `TASK_UNABLE` if
     `!actor.inWorld`, its condition isn't OK, or it is stunned (the same stunned check action validation uses); with
     `TASK_BUSY` if `actor.isBusy || actor.waitState > 0`. Otherwise set `actor.activity = ActivityState(action, pulse,
     pulse + pulses)`, mark dirty, return null. It never touches the action queue.
  2. Completion uses the existing `advanceCast` → `validate` → `finish` path. In `advanceCast`'s failed-validation
     branch, after sending the message and applying `lag / 2`, call `stopped(..., INVALID)` for a `TaskAction`.
  3. `interruptCast` (rename to `interruptActivity` if Code Health prefers): for a `TaskAction` send `TASK_INTERRUPTED`
     instead of `SkillMsg.INTERRUPTED`, apply `lag / 2`, then call `stopped(..., INTERRUPTED)`.
  4. `interruptCastOnDamage` (`Combat.kt`): `RulesContext(this, target, source, skill = castable skill or null, task =
     taskOf(target))`. The stun paths (`Vitals.kt:140`, `Affects.kt` `stunsCaster`) already read
     `activity.action.interrupt.stun`; keep them.
  5. `cancelCast` (used by `stop`/`clear`): for a task, `KernelCommands.stop` replies `STOP_TASK` (with `task`) and the
     task gets `stopped(..., CANCELLED)`; no lag. `stopTask` does the same without the reply.
  6. **GONE**, no lag, best effort:
     - `wipeActivity` (`Death.kt`): call `endActivity(victim, GONE)` instead of assigning null.
     - `removeActor` (`World.kt`): `endActivity(a, GONE)` must be the **first** statement, while the actor is still in
       the world, since the callback's owner is often the actor itself.
     - `sleep` (`Bubble.kt`): collect the mobs whose activity is a task while iterating, clear their slots in the loop,
       and call `stopped(..., GONE)` for each **after** the loop, so no callback runs while `entitiesIn(room)` is being
       iterated.
     - `despawn` (`Reconcile.kt`): `endActivity(m, GONE)` before the mob is removed.
  7. Every callback runs inside `withCascadeGuard` and catches exceptions; the script host records `script` and the
     plugin host `plugin` diagnostics (the callbacks themselves do this, mirroring `World.invokeRef`). Always clear the
     slot **before** calling `done`/`stopped`, so a callback may start a new task.
  8. The disabled pause (`disabledChanged`) already pushes `endsAt` for any activity; keep it.
  9. `Char.Status` (`Gmcp.kt`): always send `task` (kernel): `{"name", "progress"}` for a `TaskAction` activity, else
     null; compute progress like `casting`.
  10. `StatLines.kt`: "task <name> until pulse N" for a task; "casting until pulse N" for a cast.

### Test & Documentation Requirements

- `TasksTest.kt` (mirror the setup of the existing cast/interrupt tests), using a recording `TaskCallbacks`:
  - completes after `pulses`, calls `done` once, then applies `lag`;
  - refusals: out of world, incapacitated, stunned, already casting, already running a task, in recovery;
  - stun interrupts when `stun = true` and not when false; damage interrupts per `interrupt_check`, which sees `task`;
    reason `INTERRUPTED` and `lag / 2`;
  - `stop` → `CANCELLED`, "You stop <name>.", no lag; `stopTask` returns false when idle;
  - death, `removeActor`, a mob in a room going dormant, a despawn → `GONE`;
  - the queue waits during a task; a `done` callback that starts another task works; a throwing callback leaves the
    world consistent;
  - a world without `skills` runs tasks; `Char.Status.task` progress; invariants hold.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings; API files regenerated (only the new messages and `Char.Status.task`
  changed).
- [] Spec §3.10, §4.9, §4.10, §4.18 and §5.3 match the code.

---

## Phase 3: Timed tasks in scripts

### Objective & Scope

- **In Scope:** `ctx.start_task`, `ctx.stop_task`, the ctx field `task` (callbacks and rules), the actor view fields
  `task` and `is_busy`, the script host's `TaskCallbacks`, the template `interrupt_check`, and the tutorial 06 section.
- **Out of Scope:** *Do not implement these; later phases do.* `skill_roll`, `set_item_name`, plugin API changes, the
  craft library.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Tasks.kt` (Phase 2).
  - `script/src/main/kotlin/gups/script/CtxState.kt` `after` (~125): argument checks, `hookRef`, `toAttr`.
  - `script/src/main/kotlin/gups/script/Host.kt` `invokeRef` (~284): how a timer callback is called with kwargs.
  - `script/src/main/kotlin/gups/script/SkillDeclarations.kt` (~67): `interrupt` dict parsing.
  - `script/src/main/kotlin/gups/script/CtxFields.kt` (`elapsed_pulses` ~240) and `Views.kt` (actor `casting` ~468,
    skill view `interrupt` ~1208).
- **Files to Modify / Create:**
  - Create `script/src/main/kotlin/gups/script/CtxTasks.kt`, structured like `CtxSkills.kt`, and wire it in wherever
    `CtxSkills` is included in the ctx object.
  - Modify `Host.kt` (positional `reason` for `on_stop`), `CtxFields.kt` (`task`), `Views.kt` (`task`, `is_busy`), the
    rules ctx mapping in `script/src/main/kotlin/gups/script/Rules.kt` (expose `ctx.task` wherever `RulesContext` fields
    such as `affect` are exposed).
  - `template-world/rules/combat.star` (`interrupt_check`),
    `template-world/docs/tutorials/06_hooks_and_interactivity.md`.
  - A script test next to the existing ctx verb tests (find one with `grep -rln "report_skill_use" script/src/test`).
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API files.
- **Do Not Touch:** `core/` (except a bug the new tests prove), `plugin-api/`, `samples/`, `template-world/lib/`,
  `template-world/zones/`.

### Specification & Decision Record Changes

- §4.5 ctx fields: add `task` ("the task view, in a timed task's callbacks and in `interrupt_check` for a task").
- §4.5 actor view, State: add `task` and `is_busy`.
- §4.5 verbs table: new row `| Tasks | start_task(actor, pulses, on_done, name="task", lag=0, interrupt={"stun": True,
  "damage_pct": None}, on_stop=None, **args) -> str or None, stop_task(actor) -> bool |`, and a bullet **`start_task`
  and `stop_task`** carrying the contract in the Design Summary (refusals, callbacks and their ctx, reasons and lag,
  interrupts, not saved, kernel).
- §4.6: `interrupt_check` gets `ctx.task` for a task, with `ctx.skill` `None`.
- §3.10 kernel row: list `start_task`, `stop_task`, `actor.task`, `is_busy`.
- CHANGELOG bullets for the verbs, field and views, and the template `interrupt_check` change.

### Implementation Blueprint

- **Algorithmic Steps & Invariants:**
  1. `ctx.start_task(actor, pulses, on_done, name = "task", lag = 0, interrupt = None, on_stop = None, **args)`: parse
     like `after`: `hookRef` for `on_done` and `on_stop`; `toAttr` for each extra kwarg (`None` is an error, as for
     timers). `interrupt` is `None` (default `Interrupt()`) or a dict with only `stun` (bool) and `damage_pct` (int
     1..100 or `None`). Call `world.startTask(...)` with a `ScriptTaskCallbacks(ownerId, doneRef, stopRef, args)`.
     Return the refusal string or `None`. Argument errors are script errors naming `start_task(<arg>)`.
  2. `ScriptTaskCallbacks.done`/`stopped`: look the owner up by id; skip silently if it left the world. Call the host
     with `HookContext(actor = actor, args = args, task = info)`. For `stopped`, pass `reason.word` as the second
     positional argument: extend `Host.invokeRef`/`call` with an optional positional list if it has none. A missing
     function (package reloaded away) or a quarantined package is the host's usual `script` diagnostic.
  3. `ctx.stop_task(actor)` returns `world.stopTask(a)`.
  4. `ctx.task`: in a task callback the snapshot from `HookContext.task`; in rules, `RulesContext.task`. Both render as
     `struct(name, started_at, ends_at, remaining, interrupt)` with `remaining = max(0, ends_at - pulse)` and
     `interrupt` the dict shape the skill view's `interrupt` returns. `None` elsewhere.
  5. Views: `actor.task` builds the same struct from `world.taskOf(actor)` or `None`; `actor.is_busy`.
  6. `template-world/rules/combat.star`: replace `interrupt_check` with the code in **Phase 3 template rule** below.

#### Phase 3 template rule

```python
def interrupt_check(ctx, amount):
    # Whether `amount`, the total vital loss of a damage event, breaks what ctx.actor is doing:
    # the skill being cast, or the timed task.
    what = ctx.skill or ctx.task
    pct = what.interrupt["damage_pct"] if what else None
    if pct == None:
        return False
    return amount * 100 >= pct * max(1, ctx.actor.max_hp)
```

### Test & Documentation Requirements

- Script test: `ctx.start_task` from a global command and from a timer; kwargs reach both callbacks; `on_stop` gets
  `reason` for `stop` and a stun; `ctx.task` inside callbacks; a refusal string while busy; `pulses = 0`, a bad
  `interrupt` key and an unstorable kwarg are errors; `actor.task`/`is_busy`; a test rules `interrupt_check` sees
  `ctx.task`.
- Tutorial 06: a short section "Timed tasks with `ctx.start_task`" after the timers section, with a 10–15 line example
  (a `pray` room command that takes 8 pulses and heals on `on_done`), explaining `stop`, interruptions and `on_stop`.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings; API files regenerated (only `start_task`, `stop_task`, `ctx.task`,
  `actor.task` and `actor.is_busy` new).
- [] A `gups scenario` in `local-world-test/` shows a player starting a task from a scratch room command and `stop`
  cancelling one.
- [] Spec §3.10, §4.5 and §4.6 match the code.

---

## Phase 4: `skill_roll` and `set_item_name`

### Objective & Scope

- **In Scope:** core `World.skillRoll` and `World.setItemName`; script `ctx.skill_roll` and `ctx.set_item_name`; spec
  and CHANGELOG.
- **Out of Scope:** *Do not implement these; later phases do.* Plugin API, the library, the tutorial.

### Context Working Set

- **Read / Reference Only:** `core/.../world/Rules.kt` `skillSucceeds` (~248), `skillPowerChain` (~261), `traceRules`;
  `script/.../CtxSkills.kt` `report_skill_use` (~81) for argument parsing; `core/.../model/Entities.kt`
  `Item.nameOverride` (~864) and `Death.kt:129` (its one writer).
- **Files to Modify / Create:** `core/.../world/Rules.kt`, `core/.../world/Items.kt` (add `setItemName` beside the other
  item verbs), `script/.../CtxSkills.kt`, `script/.../CtxItems.kt`, `script/.../Ownership.kt` (`ctx.skill_roll` belongs
  to `skills`), tests in `core/src/test` and `script/src/test`, `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API
  files.
- **Do Not Touch:** `plugin-api/`, `template-world/`, `samples/`.

### Specification & Decision Record Changes

- §4.5 Skills row: add `skill_roll(actor, skill, item=None, target=None, event="craft", adjust=0) -> struct(chance,
  roll, success, power)` and a bullet with the semantics in the Design Summary (needs `skills`).
- §4.5 Items row: add `set_item_name(item, name)` and a bullet (single line, 1..80 characters, `None` clears, returns
  whether it changed, keywords unchanged, an override already keeps an item out of stacks).
- §3.3 Item "Quantities and decay": "a script may set a name override with `set_item_name`".
- §3.10: `skill_roll` in the `skills` row; `set_item_name` in the kernel row.
- §4.6 trace paragraph: `skill_roll` traces like `skill_check`, with ` adjust ±N` before the arrow when `adjust ≠ 0`.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  data class SkillRoll(val chance: Int, val roll: Int?, val success: Boolean)
  fun World.skillRoll(c: RulesContext, adjust: Int = 0): SkillRoll
  fun World.skillSucceeds(c: RulesContext): Boolean = skillRoll(c).success   // callers unchanged
  fun World.setItemName(item: Item, name: String?): Boolean
  ```
- **Algorithmic Steps & Invariants:**
  1. `skillRoll`: run the chain as `skillSucceeds` does today, add `adjust` to the chain's value, then the 100+ rule or
     one `rng.int(1, 100)` draw, tracing as today plus ` adjust ±N` when non-zero. Casts pass 0, so their traces and RNG
     draws are unchanged.
  2. `ctx.skill_roll`: `RulesContext(world, actor, target, skill = proto, item = item, event = event)`; `event` matches
     `[a-z][a-z0-9_]*`; on success `power = skillPowerChain(c)`, else 0. Return `struct(chance, roll, success, power)`
     with `roll` `None` when nothing was drawn.
  3. `setItemName`: `null` clears; otherwise require non-blank, no `\n`/`\r`, length ≤ 80 (`IllegalArgumentException`,
     surfaced as a script error naming `set_item_name(name)`). Return whether `nameOverride` changed; mark dirty.

### Test & Documentation Requirements

- Core: `skillRoll` with forced RNG results (see how existing `skillSucceeds` tests force draws): success, failure,
  `adjust` pushing a chance to 100+ (no draw) and below 1; cast behavior unchanged.
- Script: `ctx.skill_roll` fields; a test rules `skill_check` sees `ctx.event`; unknown skill error; needs `skills`.
  `ctx.set_item_name` sets and clears; rejects a multi-line name and 81 characters; a renamed item no longer merges into
  a plain stack; keywords still match; the name survives a save/restore round trip (extend an existing persistence
  round-trip test).

### Exit Conditions

- [] `./gradlew check` passes with zero warnings; API files regenerated (only `skill_roll` and `set_item_name` new).
- [] Existing cast traces in golden tests are unchanged.

---

## Phase 5: Plugin API and the hunger sample

### Objective & Scope

- **In Scope:** `PluginWorld.startTask`, `stopTask`, `skillRoll`, `setItemName`; `PluginActor.task`, `isBusy`; the new
  types; host facade implementations; `docs/PLUGINS.md`; `samples/hunger-plugin` `hunger.dine`.
- **Out of Scope:** *Do not implement these; later phases do.* The craft library and tutorial content.

### Context Working Set

- **Read / Reference Only:** `plugin-api/.../Facade.kt` (`reportSkillUse` ~161 default-throwing pattern, `PluginActor`
  ~318), `plugin-api/.../PluginApi.kt` (`PluginTickCall` ~263 call-object pattern), `plugin-api/.../host/Facade.kt`
  (`reportSkillUse` ~673, `actorOf`, `itemOf`, `needs(world, Feature.SKILLS, ...)`), `core/.../world/Tasks.kt`,
  `samples/hunger-plugin/src/main/kotlin/gups/sample/hunger/HungerPlugin.kt` (`eat` builtin ~59).
- **Files to Modify / Create:** `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt`, `PluginApi.kt` (new types),
  `host/Facade.kt` (implementations; `ActorFacade.task`/`isBusy`), a new
  `plugin-api/src/test/kotlin/gups/pluginapi/PluginTaskTest.kt` (mirror `TraitCallbackTest.kt`'s `scene(...)` setup),
  `HungerPlugin.kt`, `samples/hunger-plugin/src/test/kotlin/gups/sample/hunger/HungerWorldTest.kt`,
  `samples/hunger-plugin/README.md`, `docs/PLUGINS.md`, `docs/SPEC.md` §4.15, `docs/CHANGELOG.md`, the regenerated API
  files.
- **Do Not Touch:** `template-world/`, `script/`, `core/` (except a bug the new tests prove).

### Specification & Decision Record Changes

- §4.15 plugin verbs: add `startTask`, `stopTask`, `skillRoll` (needs `skills`), `setItemName`, and `PluginActor.task`
  and `isBusy`, "with the same rules as the script verbs".
- `docs/PLUGINS.md`: the verbs list (~line 67) and a "Timed tasks" section with a short example.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // PluginWorld
  fun startTask(
    actor: PluginActor, pulses: Int, name: String, lag: Int = 0,
    interrupt: PluginTaskInterrupt = PluginTaskInterrupt(),
    onStop: ((PluginTaskStop) -> Unit)? = null,
    onDone: (PluginTaskCall) -> Unit,
  ): String? = throw UnsupportedOperationException("startTask is not supported")
  fun stopTask(actor: PluginActor): Boolean =
    throw UnsupportedOperationException("stopTask is not supported")
  fun skillRoll(
    actor: PluginActor, skill: String, item: PluginItem? = null, target: PluginActor? = null,
    event: String = "craft", adjust: Int = 0,
  ): PluginSkillRoll = throw UnsupportedOperationException("skillRoll is not supported")
  fun setItemName(item: PluginItem, name: String?): Boolean =
    throw UnsupportedOperationException("setItemName is not supported")

  // PluginActor
  val task: PluginTask? get() = null
  val isBusy: Boolean get() = false

  data class PluginTaskInterrupt(val stun: Boolean = true, val damagePct: Int? = null)
  interface PluginTask {
    val name: String
    val startedAt: Long
    val endsAt: Long
    val remaining: Long
    val interrupt: PluginTaskInterrupt
  }
  data class PluginTaskCall(val world: PluginWorld, val actor: PluginActor, val task: PluginTask)
  data class PluginTaskStop(
    val world: PluginWorld, val actor: PluginActor, val task: PluginTask, val reason: PluginTaskEnd,
  )
  enum class PluginTaskEnd { INTERRUPTED, CANCELLED, INVALID, GONE }
  data class PluginSkillRoll(val chance: Int, val roll: Int?, val success: Boolean, val power: Int)
  ```
- **Algorithmic Steps & Invariants:**
  1. Host `startTask` validates like `World.startTask` and wraps the lambdas in a `TaskCallbacks` whose calls build a
     fresh world facade, as other host callbacks do; a throw records a `plugin` diagnostic.
  2. Script and plugin tasks share the slot: `stopTask` cancels either kind, and `task` reports either kind.
  3. `skillRoll` mirrors `ctx.skill_roll` (`needs(world, Feature.SKILLS, "skillRoll")`); `setItemName` calls
     `World.setItemName`.
  4. Hunger sample: a builtin `hunger.dine(actor, amount, pulses)` calls `startTask(actor, pulses, "eating")`; `onDone`
     lowers hunger like `eat`. It returns the refusal string or None.

### Test & Documentation Requirements

- `PluginTaskTest`: done after `pulses`; `onStop` reasons for `stop`, a stun and death; refusal while busy; a throwing
  callback records a `plugin` diagnostic; a script `ctx.stop_task` cancels a plugin task; `skillRoll` without `skills`
  throws naming the feature; `setItemName`.
- `HungerWorldTest`: `dine` lowers hunger only after the pulses, and `stop` leaves hunger unchanged.

### Exit Conditions

- [] `./gradlew check` passes; API files regenerated (the plugin docs page lists the new verbs).
- [] `docs/PLUGINS.md` and the hunger sample README describe tasks and `dine`.

---

## Phase 6: `//lib:craft.star`: recipes, stations and `recipes`

### Objective & Scope

- **In Scope:** the library file with its constructors and load-time checks, station discovery, learned recipes
  (`teach`, `knows`), the availability matcher, the `recipes` command, both GMCP package declarations and the
  `Craft.Recipes` event, and a test fixture.
- **Out of Scope:** *Do not implement these; Phase 7 does.* The `craft` command, tasks, rolling, consuming, quality,
  `improve` effects, `craft_check`, `craft_done`, sending `Craft.Result`, the tutorial smithy.

### Context Working Set

- **Read / Reference Only:** `template-world/lib/shop.star` (whole file: `_absolute`, `_check_id`, `_kw_match`, the
  `list` command, `ctx.gmcp`, `gmcp_package`, the closing `shop = struct(...)`), `template-world/lib/trainer.star`
  (`_teach` validation), `template-world/zones/tutorial/shop.star` and `shop_test.star` (fixture pattern), the Design
  Summary of this milestone.
- **Files to Modify / Create:** create `template-world/lib/craft.star`; modify `template-world/lib/package.star` (header
  comment); create `template-world/zones/tutorial/craft_testdata.star` and
  `template-world/zones/tutorial/craft_test.star`.
- **Do Not Touch:** engine code, other `lib` files, `rooms.star`.

### Specification & Decision Record Changes

- None in this phase; the spec paragraph lands in Phase 8 with the finished library.

### Implementation Blueprint

- **Exports:** `craft = struct(recipe = _recipe, need = _need, make = _make, improve = _improve, failure = _failure,
  quality = _quality, teach = _teach, knows = _knows)`.
- **Constructors.** Each `fail(...)`s naming the function and argument, as `shop.star` does. Every stored value is
  plain; an absent value is `""`, `0`, `[]` or `{}`, never `None`.
  - `_need(label = None, tag = None, kind = None, count = 1, lose = None, charges = 0)` → `{"label", "tag", "kind",
    "count", "lose", "charges"}`. Exactly one of label/tag/kind; label absolute (`_absolute`); tag and kind match
    `[a-z][a-z0-9_]*`; `count ≥ 1`; `charges ≥ 0`. `lose` is `None` (stored `""`: use the recipe default), an int ≥ 0,
    `"all"`, `"N%"` with N in 0..100, or dice matching `[0-9]*d[0-9]+([+-][0-9]+)?` (check by hand; Starlark has no
    regex). Anything else fails, naming the need's label, tag or kind.
  - `_make(label, count = 1)` → `{"kind": "make", "label", "count"}`.
  - `_improve(affect = "", applies = "worn", stat_deltas = {}, sense_deltas = {}, max_vital_deltas = {}, resist_deltas =
    {}, flags = [], add_tags = [], remove_tags = [], variant = "", unless_tag = "")` → `{"kind": "improve", ...}`. At
    least one effect (deltas, flags, tags or variant). Deltas are plain dicts of ints; `applies` is `worn` or `carried`.
  - `_failure(lose = "50%", make = None, message = "")`; `lose` takes the `need` forms except `None`; `make` is a
    `_make` result or `None` (stored `{}`).
  - `_quality(at, name = "", affect = "", stat_deltas = {}, sense_deltas = {}, max_vital_deltas = {}, resist_deltas =
    {}, add_tags = [])`; `at ≥ 1`; `name`, if given, a single line ≤ 80 characters.
  - `_recipe(id, result, name = "", verb = "craft", doing = "", inputs = [], tools = [], target = None, skill = "",
    difficulty = 0, time = 10, lag = 0, learned = False, failure = None, qualities = [])`:
    - `id` uses `_check_id`'s character set; `name` defaults to the id with `_` → space; `verb` is a lowercase word;
      `doing` defaults to `verb` minus a trailing `e`, plus `ing`.
    - `time ≥ 1`, `lag ≥ 0`, `difficulty ≥ 0`; `skill` absolute or `""`.
    - `target` (a `need`) is required for an `improve` result and refused for `make`; stored `{}` when absent. Input
      needs with `charges > 0` fail; tool needs ignore `lose`.
    - `qualities` sorted by `at`, highest first; a repeated `at` fails. `failure` defaults to `_failure()`.
- **Stations** (`_stations(ctx, actor)`): visit the actor's room, then ground items (`room.items()`), then carried items
  (`actor.inventory()`, which holds neither worn items nor container contents). For each, `ctx.call_hook(entity,
  "craft_recipes")`; skip `None`; a value that isn't a list of dicts each with an `"id"` is skipped with a `print`.
  Within one station a later duplicate id replaces an earlier one, with a `print`. Return a list of `(entity, {id:
  recipe})` in visiting order. Across stations, the first station offering an id wins.
- **Learned:** `_teach(ctx, actor, id)` adds `id` to `ctx.get_attr(actor, "craft_known", [])` (sorted, no repeats) and
  returns whether it was new; `_knows(ctx, actor, id)` checks it. Unknown `learned` recipes are hidden everywhere.
- **Availability** (`_match(ctx, actor, recipe)` → `struct(ready, reserved, missing)`), used by `recipes` and Phase 7:
  greedy over carried items, inputs first, then tools; an item used by one need can't serve another; a stack contributes
  up to its count; a tool need with `charges = n > 0` takes only a non-stackable item with `charges ≥ n` or unlimited
  charges (`charges == None`). `reserved` is a list of `{"group": "input"|"tool", "need": index, "id": item_id, "take":
  n}`; `missing` lists `(need, short)` pairs. A recipe naming a skill in a world without `skills` (`not
  ctx.has_feature("skills")`) is never ready.
- **`recipes`** (`global_command("recipes", _recipes_command, lane = "fast")`): with no station, "There's nothing to
  craft with here."; otherwise a heading line per station (its name) and one line per recipe: ` <id>: <name> (<verb>)`
  then ` — ready`, ` — missing: 1 iron bar, a hammer` or ` — needs the skills feature`. Then `ctx.gmcp(ctx.actor,
  "Craft.Recipes", payload)` exactly as in the Design Summary, converting `""` to `None` for `skill` at send time
  (`ctx.gmcp` sends `None` as null and does nothing for mobs).
- Declarations at the end of the file: `gmcp_package("Craft.Recipes", doc = "Recipes the crafter can reach, sent by
  recipes.")` and `gmcp_package("Craft.Result", doc = "How a craft ended, sent when it finishes or stops.")`.

### Test & Documentation Requirements

- `craft_testdata.star`: an exit-less room `craft_lab` with a `craft_recipes` hook, a ground station item, a carried kit
  item, test items (a stackable bar, a tagged wood item, a hammer with `charges = 3`, a hammer with unlimited charges),
  and recipes covering every constructor and both result kinds.
- `craft_test.star`: each constructor's bad arguments fail (use the pattern `shop_test.star` uses for `shop.define`);
  `recipes` with nothing, with ready and missing recipes, the duplicate-id rule, hidden learned recipes and `teach`
  revealing one; the `Craft.Recipes` payload via `p.gmcp("Craft.Recipes")`, including `"skill": None`.
- `lib/package.star` header: add `load("//lib:craft.star", "craft") # crafting stations and recipes`.

### Exit Conditions

- [] `./gradlew check` passes (tutorial package tests run in it); API files regenerated (only the two `Craft.*` packages
  and the `recipes` command new).
- [] `cli/build/install/gups/bin/gups lint --world template-world` reports nothing new.

---

## Phase 7: `//lib:craft.star`: `craft`, outcomes, hooks and `Craft.Result`

### Objective & Scope

- **In Scope:** the `craft` command, the timed task, completion (re-checks, roll, consumption, failure loss, products,
  improvements, quality), `on_stop`, `craft_check`, `craft_done`, skill reporting, `Craft.Result`, and a no-`skills`
  Kotlin test.
- **Out of Scope:** *Do not implement these; Phase 8 does.* The tutorial smithy, the crafts skill package, tutorial
  chapter 12 and the spec paragraph.

### Context Working Set

- **Read / Reference Only:** `template-world/lib/craft.star` (Phase 6), `template-world/lib/shop.star` (the `buy`
  command's parsing and refusals), `template-world/rules/combat.star` (`skill_check` and `skill_power` formulas),
  `harness/src/test/kotlin/gups/harness/ShopWithoutCombatTest.kt`, and the spec §4.5 bullets for `start_task`,
  `skill_roll`, `set_item_name`, `apply_affect` on items, `transform`, `destroy`, `give`, `merge_stack`, `set_charges`,
  `report_skill_use`, `call_hook`.
- **Files to Modify / Create:** `template-world/lib/craft.star`, `template-world/zones/tutorial/craft_testdata.star`,
  `template-world/zones/tutorial/craft_test.star`; create
  `harness/src/test/kotlin/gups/harness/CraftWithoutSkillsTest.kt`.
- **Do Not Touch:** engine code, `rooms.star`, other `lib` files.

### Implementation Blueprint

- **`craft <recipe> [on <item>]`** (`global_command("craft", _craft_command, lane = "tick")`):
  1. Empty args: "Craft what? Type `recipes` to see what you can make here."
  2. Split on the last `" on "` for the target keyword. Find the recipe among the stations by id, else by a prefix match
     on its name's words (`_kw_match` style). Not found, or `learned` and unknown: "You don't know how to make that
     here."
  3. `ctx.has_feature("combat") and ctx.actor.fighting()`: "You can't do that while fighting."
  4. Names a skill but `skills` is off: "That recipe needs skills this world doesn't have."
  5. `improve`: without a target keyword, "<Verb> what?" (capitalized verb); match carried then worn items by keyword;
     the target must satisfy the recipe's `target` need, else "You can't <verb> <item>."; with `unless_tag` present on
     it, "<Item> is already <unless_tag>."; when the `improve` names a `variant` the target doesn't list in
     `target.variants`, "You can't <verb> <item>.". A `make` recipe given `on`: "That recipe doesn't work on an item."
  6. `_match`; not ready: "You still need <missing list>."
  7. `adjust = -difficulty`; `res = ctx.call_hook(station, "craft_check", crafter = actor, recipe = id, target =
     target_or_None, adjust = adjust)`: a string refuses with it, an int replaces `adjust`, anything else keeps it.
  8. `err = ctx.start_task(actor, recipe["time"], _on_done, name = doing + " " + name, lag = recipe["lag"], on_stop =
     _on_stop, recipe = recipe, station = station_ref, reserved = reserved, target_id = target.id or 0, adjust =
     adjust)` where `station_ref` is `{"kind": "room", "label": room.label}` or `{"kind": "item", "id": item.id}`. Send
     a refusal as is. Otherwise tell the actor "You begin to <verb> <name>." and echo "<Actor> begins to <verb> <name>."
     to the room, excluding the actor.
- **`_on_done(ctx, recipe, station, reserved, target_id, adjust)`:**
  1. Re-check: the station is in reach (the room's label equals the actor's room, or the item id is on the ground there
     or carried), each reserved item is still carried with at least `take` (and a tool's charges still cover its need),
     and the target is still carried or worn. Otherwise "You no longer have what you need.", `Craft.Result` with
     `"stopped"`, and return.
  2. Roll: no skill → success, power 100. Otherwise `r = ctx.skill_roll(actor, skill, item = target, event = "craft",
     adjust = adjust)`.
  3. Spend tool charges in both outcomes: for each tool need with `charges = n > 0` whose item has limited charges,
     `ctx.set_charges(item, item.charges - n)`.
  4. Success:
     - Destroy each input's full `count` from its reserved items (`ctx.destroy(item, count = take)`).
     - `make`: `ctx.give(actor, label, count)`; when no quality tier applies, `ctx.merge_stack` each result.
     - `improve`: build typed deltas (`stats(**d)`, `vitals(**d)`, `resists(**d)`; `sense_deltas` stays a dict) and
       `ctx.apply_affect(target, affect or "craft_" + id, None, applies = ..., ...)` when there are deltas or flags;
       `ctx.add_tag`/`ctx.remove_tag`; `ctx.transform(target, target.variants[variant])` when `variant` is set (a
       variant the item lacks is a refusal at start, step 5).
     - Quality: the first tier (highest first) with `at ≤ r.power`: `ctx.set_item_name` on each made or improved item
       when it has a `name`; a permanent item affect named `affect` or `"quality_<at>"` when it has deltas; its tags.
     - Message: "You <verb> <final item name>."
  5. Failure: per input need, the loss from its `lose` (or `failure.lose`): an int → itself; `"all"` → `count`; `"N%"` →
     `(count * N + 99) // 100`; dice → `ctx.roll(expr)`; then clamp to `0..count`. Destroy that many from the reserved
     items in order. `failure.make` gives the flawed product. Message: `failure.message` or "You fail to <verb>
     <name>.", plus " You ruin <list>." when anything was lost.
  6. When the recipe names a skill: `ctx.report_skill_use(actor, skill, item = product_or_target)` (the first made item,
     or the target; `None` when nothing was made).
  7. `ctx.call_hook(station, "craft_done", crafter = actor, recipe = id, outcome = "success"|"fail", items = [views])`,
     resolving the station again (skip if it is gone).
  8. `Craft.Result` as in the Design Summary; `quality` `None` when no tier applied.
- **`_on_stop(ctx, reason, **args)`:** for `"interrupted"`, `"cancelled"` and `"invalid"` send `Craft.Result` with
  `"stopped"` (the engine already sent its line); `"gone"` sends nothing.
- **Template formulas to use in tests** (`rules/combat.star`): chance = `clamp(prof + 20, 5, 100)` + `adjust`; power =
  `50 + prof // 2 + (level - 1) * 2`. Spawn crafters with `w.spawn_player(..., level = L, skills = {...})` and state the
  expected chance and power in a comment next to each assertion. Use `w.force_rng` to fix the roll and dice draws.

### Test & Documentation Requirements

- `craft_testdata.star`: add fixture tiers reachable by the formula, for example `at = 75` (prof 50, level 1 → 75) and
  `at = 95` (prof 50, level 11 → 95), plus a test global `on_skill_use` hook counting reports in an attribute.
- `craft_test.star`: make success (inputs gone, product given and stacked); failure with each `lose` form and the
  default; a flawed product; tool charges spent, a tool at 0 no longer matching, an unlimited tool spending nothing;
  both quality tiers and no tier (prof 0 → power 50); `improve` with an affect, tags, a variant and `unless_tag`; a
  missing or wrong target; learned gating; `craft_check` refusing and adjusting; `craft_done` with the outcome; `stop`
  mid-craft consumes nothing; items dropped mid-craft → "You no longer have what you need."; the fighting refusal;
  `Craft.Result` payloads; `on_skill_use` reports.
- `CraftWithoutSkillsTest.kt` (mirror `ShopWithoutCombatTest.kt`): a world with `features = ["combat"]` that copies
  `lib/package.star` and `lib/craft.star`; a skill-less recipe crafts at power 100 with no error; a skill recipe is
  refused with the message; no diagnostic names a missing feature.

### Exit Conditions

- [] `./gradlew check` passes; API files regenerated (only the `craft` command new).
- [] A `gups scenario` in `local-world-test/` crafts, fails and stops a craft at the test lab, and the transcript
  matches the messages above.

---

## Phase 8: Tutorial smithy, crafting skill, docs and spec

### Objective & Scope

- **In Scope:** the `crafts` skills package, the template `cast_allowed`, the tutorial smithy and recipe module, a
  walkthrough test, tutorial chapter 12, the tutorials README roadmap, the spec `//lib:craft.star` paragraph, CHANGELOG.
- **Out of Scope:** *Do not implement these; they are not part of this milestone.* More recipes than listed, mob
  crafting AI, a recipe catalog, inbound GMCP for crafting.

### Context Working Set

- **Read / Reference Only:** `template-world/zones/tutorial/rooms.star` (map comment, `_stock_tar_pit` item placement
  ~61, the `shop` room ~112), `template-world/zones/tutorial/mobs.star` and `trainer_testdata.star` (`trainer.open` from
  `on_spawn`), `template-world/docs/tutorials/08_shops.md` and `09_trainers.md` (chapter structure),
  `template-world/skills/arcane/` (skills package layout).
- **Files to Modify / Create:**
  - Create `template-world/skills/crafts/package.star` (`package(api_version = 1, kind = "skills", title = "Crafts")`)
    and `template-world/skills/crafts/crafts.star`: `skill("smithing", kind = "craft", target = "none", range = "self",
    tags = ["craft"])` (adjust `range` if the loader requires otherwise).
  - `template-world/rules/combat.star` `cast_allowed`: deny `ctx.skill.kind == "craft"` with "You practice <skill name>
    at a workstation, not by casting."
  - Create `template-world/zones/tutorial/smithy.star` and `template-world/zones/tutorial/smithing_recipes.star`; modify
    `rooms.star` (an east exit from `shop` to `:smithy`, and the map comment).
  - Create `template-world/zones/tutorial/smithy_test.star`.
  - Create `template-world/docs/tutorials/12_crafting.md`; modify `template-world/docs/tutorials/README.md`.
  - `docs/SPEC.md` §4.9 (after the `//lib:trainer.star` paragraph), `docs/CHANGELOG.md`, the regenerated API files.
- **Do Not Touch:** engine code, `lib/craft.star` (except bugs the new tests prove, each with a test).

### Specification & Decision Record Changes

- §4.9, a new paragraph after `//lib:trainer.star`, one paragraph like the shop's:

  > **`//lib:craft.star`:** a station is a room, a ground item or a carried item whose custom hook `craft_recipes`
  > returns recipes built with `craft.recipe(...)`. `recipes` lists those in reach and sends `Craft.Recipes`; `craft
  > <recipe> [on <item>]` runs a timed task (`start_task`), rolls `skill_roll` when the recipe names a skill, then
  > consumes the inputs and makes an item (`craft.make`) or improves the target (`craft.improve`). A failure loses each
  > input's `lose`; quality tiers rename and enhance the result by the roll's power. The station's custom hooks
  > `craft_check` and `craft_done` adjust or refuse a craft and hear its outcome; plugin handlers join both. Learned
  > recipes need `craft.teach`. `Craft.Result` reports how a craft ended.

### Implementation Blueprint

- **`smithing_recipes.star`** exports `BASIC` and `ADVANCED` (absolute `//zones/tutorial:` labels):
  - `smelt` (BASIC, verb "smelt"): inputs `iron_ore` ×2 (`lose = "1d2"`) and `charcoal` (`lose = 1`); makes `iron_bar`;
    no skill; time 6.
  - `forge_dagger` (BASIC, verb "forge"): inputs `iron_bar` ×2 (`lose = "50%"`); tool `tag = "hammer"`; skill
    `//skills/crafts:smithing`, difficulty 10, time 12, lag 2; `failure = craft.failure(make = craft.make(":bent_dagger"
    as an absolute label))`; qualities `at = 95` ("a masterwork iron dagger", `stat_deltas = {"dex": 1}`, tag
    `masterwork`) and `at = 75` ("a fine iron dagger"). With the template formulas: prof 50 at level 1 gives power 75
    ("fine"); prof 50 at level 11 gives 95 ("masterwork"); prof 0 gives 50 (plain).
  - `temper_dagger` (ADVANCED, `learned = True`, verb "temper"): target `need(label = iron_dagger)`, input `charcoal`;
    `improve(variant = "tempered", add_tags = ["tempered"], unless_tag = "tempered")`; skill smithing, difficulty 20.
- **`smithy.star`:**
  - Items: `iron_ore`, `charcoal`, `iron_bar`, `grit`, `leather_scrap` (stackable, tagged); `smith_hammer` (tags
    `["hammer"]`); `iron_dagger` (melee, `wield`, `dice = "1d4"`, `variants = {"tempered": ":tempered_dagger"}`),
    `tempered_dagger`, `bent_dagger`; `whetstone` (a heavy ground station, `hooks = {"craft_recipes":
    _whetstone_recipes}`, offering `hone`: target `need(kind = "melee")`, input `need(tag = "grit")`, `improve(affect =
    "honed", stat_deltas = {"dex": 1}, add_tags = ["honed"], unless_tag = "honed")`); `repair_kit` (a carried kit
    station offering `patch`: target `need(kind = "armor")`, input `leather_scrap`, tool `need(tag = "awl", charges =
    1)`, `improve(affect = "patched", stat_deltas = {"armor": 1}, add_tags = ["patched"], unless_tag = "patched")`);
    `awl` (tag `awl`, `charges = 5`, `empty = "keep"`); `tempering_notes` (its `on_use` calls `craft.teach(ctx,
    ctx.actor, "temper_dagger")`, tells the player, then destroys the notes).
  - Room `smithy` (exit west to `:shop`), `hooks = {"craft_recipes": _forge_recipes, "craft_check": _forge_check}`,
    `commands = {"stoke": _stoke}`, and `on_load`/`on_reset` stocking ore, charcoal, a hammer, the whetstone, grit,
    leather scraps, a repair kit, an awl and the notes when absent (mirror `_stock_tar_pit`).
  - `_forge_recipes(ctx)`: `return smithing_recipes.BASIC + smithing_recipes.ADVANCED`.
  - `_stoke`: needs a carried charcoal; destroys one; sets the room attribute `forge_lit` to True and `ctx.after(300,
    _cool)`, which clears it and echoes that the forge cools.
  - `_forge_check(ctx, crafter, recipe, target, adjust)`: `forge_dagger` and `temper_dagger` are refused with "The forge
    is cold. Stoke it first." unless lit, and get `adjust + 10` when lit; other recipes return `None`.
  - Mob `smith` spawned in the smithy (sentinel), whose `on_spawn` calls `trainer.open` teaching
    `//skills/crafts:smithing` up to 50.
- **`smithy_test.star`:** a walkthrough at the smithy: pick up ore and charcoal, `stoke`, `smelt` twice, `forge dagger`
  (a level 1 player with smithing 50 and a forced roll: chance 70, power 75 → "a fine iron dagger"), `hone dagger` at
  the whetstone, use the notes, `temper` the dagger into the tempered variant, `patch` with the repair kit (an awl
  charge spent), `recipes` output, and `cast smithing` refused.
- **`12_crafting.md`** (structured like 08/09): 1. Stations and `craft_recipes` (composing recipe modules, plugins
  joining); 2. Writing recipes (`need`, `make`, `improve`, time, lag, `doing`); 3. Failure and the `lose` forms; 4.
  Skills, difficulty and quality tiers (`skill_roll`, the template formulas, `report_skill_use`, trainers); 5. Tools and
  charges; 6. The station hooks; 7. Learned recipes; 8. GMCP; 9. Testing a recipe; 10. Under the hood: `ctx.start_task`,
  `ctx.skill_roll`, `ctx.set_item_name` (linking tutorial 06). End with a Reference Links section.
- README roadmap: entry 12 with 4–5 bullets, matching entries 08–11.

### Test & Documentation Requirements

- `smithy_test.star` as above. The existing tutorial tests still pass; update any test that lists the shop's exits or
  the zone's rooms.
- `./gradlew markdownFormat`, then check code blocks in lists.

### Exit Conditions

- [] `./gradlew check` passes; API files regenerated.
- [] `gups scenario --world template-world --package //zones/tutorial` with a scenario in `local-world-test/` walks from
  the gate to the smithy and forges a dagger.
- [] Spec §4.9 has the `//lib:craft.star` paragraph, and every section in the Overview's Specification Impact matches
  the code.

---

## Phase 9: Finalization & Backlog Update

- In `docs/DESIGN_NEEDED.md`, mark the entry completed without deleting it: change its heading line from `### Crafting`
  to exactly `### Crafting (Done in Milestone 51)`.
- Run `./gradlew check` and `./gradlew markdownCheck` outside the sandbox; both must pass.
- Run `pre_commit_code_health_safeguard`; it must pass.
- Re-read the Overview's Specification Impact list and confirm each section was updated; confirm the CHANGELOG
  subsection `### Timed tasks and crafting` covers every phase.
- Delete `local-world-test/`.
