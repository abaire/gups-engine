# Milestone 30: Behavior Trees and Goal-Oriented Planning

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, section "Mobs and AI", heading `### Behavior trees and GOAP`.

- **Design Summary:**

  - **Split by cost.** Sensing (turning world queries into facts) and running a behavior tree are cheap: a few Starlark
    calls, and the host queries are Kotlin anyway. Planning is a search whose cost grows with the problem, and Starlark
    (a tree-walking interpreter under a 50,000-step `think` budget) is one to two orders of magnitude slower than Kotlin
    for that. So:
    - the **search** is a native, pure kernel query, `ctx.plan(...)`, backed by a planner in `core`;
    - **behavior trees, the blackboard and the GOAP leaf** are a Starlark library, `//lib:ai.star`, in the template
      world, where instructors can read and change them;
    - bespoke AI that needs native speed beyond planning goes in a plugin builtin (§4.15); that path already exists and
      only gets documented.
  - **`ctx.plan(facts, actions, goal, max_expansions = 1024) -> [str] | None`** (kernel; no feature):
    - `facts`: dict of fact name (string) → `bool`, `int` or `str`, the mob's sensed state.
    - `actions`: list of dicts with exactly the keys `name` (string, required, unique), `pre` (dict, default `{}`),
      `effects` (dict, default `{}`) and `cost` (int ≥ 1, default 1). Any other key is an error.
    - `goal`: dict of fact → value. It holds when every listed fact equals its value.
    - **Conditions are equality only.** A fact absent from the state matches no precondition or goal entry, until an
      effect sets it. `True` and `1` are different values (type and value must both match).
    - **Search:** uniform-cost search, so the returned plan has the lowest total cost. Ties are broken by fewer steps,
      then by the action indices of the path compared lexicographically (earlier-declared actions win). No RNG.
    - **Result:** the list of action names; `[]` when the goal already holds; `None` when the goal is unreachable or a
      bound ran out. Running out is never an error and never a diagnostic.
    - **Bounds:** `max_expansions` in 1..16,384 (outside is an error); at most 256 actions; at most 64 distinct fact
      names across `facts`, `goal`, and every `pre` and `effects`; plans of at most 32 steps (nodes at depth 32 are not
      expanded); at most `MAX_ACTION_CHECKS` action checks per call (one action tested against one expanded node;
      starting value 262,144, tuned in Phase 1 by measurement so the worst case takes a few milliseconds). With more
      than 16 actions, the action-check cap ends the search before 16,384 expansions can be reached. Exceeding the
      action, fact or argument limits is a script error; exhausting expansions or action checks returns `None`.
    - **Why the action-check cap matters:** time spent in `ctx.plan` counts against the `think` hook's 20 ms wall-clock
      backstop, which is checked at the next verb (`ai.run` always calls one afterwards). A plan that is too slow
      becomes a `wall_clock` diagnostic in production, which the harness can't catch because it turns the wall clock
      off.
    - **Cost:** it runs in Kotlin and spends no Starlark steps. It is a `ctx` verb, so the wall-clock backstop is
      checked once on entry, as for every verb.
  - **`//lib:ai.star`** (template world):
    - Leaves and nodes return `"success"`, `"failure"` or `"running"`. `"running"` means "I acted this cycle and am not
      done"; a parent treats it like success (a sequence stops there and returns `"running"`, a selector returns it).
    - **The tree is re-evaluated from the root on every `think`.** No running-node path is saved. Revisit later if
      needed.
    - **Blackboard:** one dict per mob, stored in the mob's attributes under the lib's namespace (key `ai`, so
      `lib:ai`). Leaves receive it as `bb`, may mutate it, and `ai.run` writes it back once at the end only if it
      changed. It is subject to the 64 KB attribute cap.
    - **`ai.goap(...)`** is a leaf that senses, caches its plan on the blackboard, calls `ctx.plan` again only when the
      next step's preconditions no longer hold or the goal changed, and runs one step per cycle.
    - **`ai.run(ctx, tree)`** is what a `think` hook returns: `"idle"` when the root returned `"success"` or `"running"`
      (the tree claimed the turn; if a leaf queued an action, that action already overrides the top proposal), and
      `None` on `"failure"` (the mob takes its top built-in proposal, such as `flee`).
  - **Demo:** a tutorial-zone squirrel that plans to fetch a pinecone from the tar pit and cache it at home on the
    gravel path, run by a small tree whose first branch hands combat back to the built-in behaviors.
  - **Tutorial 05:** a new section on behavior trees and planning, and a correction: inside `think`, `ctx.target` is not
    the combat opponent (the engine never sets it there); use `ctx.self.fighting()`. The engine is not changed.
  - **Out of scope:** saved running nodes; operators in conditions (`>=`, numeric effects); a Starlark fallback planner;
    a plugin-facade planner; automatic `think_every` staggering; any change to built-in behaviors.
- **Specification Impact:**
  - §4.5 (the Queries row of the verbs table; a new `plan` bullet under "Queries");
  - §4.8 (one sentence pointing at `//lib:ai.star`);
  - §2.3 only if it enumerates the template's `lib/` files (it currently names `quest.star`; add `ai.star` if it lists
    more than one);
  - §7.4 (a new M30 row);
  - `docs/CHANGELOG.md`; `template-world/docs/tutorials/05_mobs_and_behaviors.md`; `template-world/lib/package.star`
    (header comment); the regenerated API files.
- **Decision Record Updates:** None. No existing `D-xxx` covers mob AI frameworks or planning. Do not add new `D-xxx`
  entries for anything this document settles: the spec is edited directly as approved here.

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first and see them fail.
   - No spec section numbers or decision ids in comments, Kotlin or Starlark.
   - All Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`. The engine is
   unreleased, so no migrations or back-compat shims are needed.
3. Add CHANGELOG bullets to a new `### Mob planning` subsection at the top of `## api_version 1 (unreleased)`, above the
   current first subsection. Create it in the first phase that adds a bullet (Phase 2), and have each later phase append
   to it.
4. In a phase that changes the script API (Phase 2 only), regenerate all three API files before the phase's final
   `./gradlew check`:
   - Run `./gradlew :cli:test -Dgups.updateGolden=true`. This rewrites `cli/src/test/resources/api.golden.json` and the
     `cli/src/test/resources/docs-golden/` files.
   - Run `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout. This rewrites `docs/api/api-1.json`.
   - Read the diff. Only `ctx.plan` may be new. `ApiDiffTest` must then pass.
   - If a test fails because the new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing entry.
5. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation.
6. Never read or touch `local-world/`. Put scratch files in `local-world-test/` and delete it when you are done. Do not
   run `gups` commands directly against `template-world/`; copy it first (`cp -R template-world
   local-world-test/world`). `./gradlew :cli:test` already runs the template world's `*_test.star` files.

---

## Phase 1: The planner in `core`

### Objective & Scope

- **In Scope:** a pure, deterministic GOAP planner with no Starlark or `World` dependency, and its unit tests.
- **Out of Scope:** the `ctx` verb, value conversion from Starlark, spec text, CHANGELOG (Phase 2).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Behaviors.kt` (style, KDoc tone, how constants are declared).
  - `script/src/main/kotlin/gups/script/Limits.kt` (how limits are documented in KDoc).
- **Files to Modify / Create:**
  - Create `core/src/main/kotlin/gups/core/ai/GoapPlanner.kt`.
  - Create `core/src/test/kotlin/gups/core/ai/GoapPlannerTest.kt`.
- **Do Not Touch:** anything in `script`, `cli`, `docs`, `template-world`.

### Specification & Decision Record Changes

- None. The limits are documented in KDoc on the constants (the spec defers constants to KDoc).

### Implementation Blueprint

- **Signatures & Types** (package `gups.core.ai`):

```kotlin
/** A fact's value: equal only to a value of the same kind and content. */
sealed interface FactValue {
  data class Bool(val v: Boolean) : FactValue
  data class Num(val v: Long) : FactValue
  data class Text(val v: String) : FactValue
}

data class GoapAction(
  val name: String,
  val cost: Int = 1,
  val pre: Map<String, FactValue> = emptyMap(),
  val effects: Map<String, FactValue> = emptyMap(),
)

sealed interface PlanResult {
  /** [steps] is empty when the goal already holds. */
  data class Found(val steps: List<String>, val cost: Long, val expansions: Int) : PlanResult
  /** [exhausted] is true when a bound ran out, false when the goal is unreachable. */
  data class NotFound(val expansions: Int, val exhausted: Boolean) : PlanResult
}

object GoapPlanner {
  const val DEFAULT_EXPANSIONS = 1_024
  const val MAX_EXPANSIONS = 16_384
  const val MAX_ACTIONS = 256
  const val MAX_FACTS = 64
  const val MAX_PLAN_LENGTH = 32
  const val MAX_ACTION_CHECKS = 262_144

  /** Throws IllegalArgumentException for arguments outside the limits. */
  fun plan(
    facts: Map<String, FactValue>,
    actions: List<GoapAction>,
    goal: Map<String, FactValue>,
    maxExpansions: Int = DEFAULT_EXPANSIONS,
  ): PlanResult
}
```

- **Algorithmic Steps & Invariants:**
  1. **Validate** (each failure is an `IllegalArgumentException` with a message naming the argument, e.g.
     `"max_expansions must be between 1 and 16384, got 0"`, using the Starlark-facing argument names because Phase 2
     forwards the message):
     - `maxExpansions` in `1..MAX_EXPANSIONS`;
     - `actions.size <= MAX_ACTIONS`;
     - every action name non-empty and unique (`"duplicate action name 'x'"`);
     - every `cost >= 1`;
     - the union of fact names in `facts`, `goal`, every `pre` and every `effects` has at most `MAX_FACTS` entries.
  2. **Intern** fact names to indices `0 until n` in first-seen order (facts, then goal, then each action's pre then
     effects). Intern each fact's distinct values per name to small ints. Represent a state as an `IntArray(n)` with
     `-1` meaning "absent". Compile each action's `pre` and `effects` into parallel `IntArray`s of (fact index, value
     id). Compile the goal the same way.
  3. If the start state satisfies the goal, return `Found(emptyList(), 0, 0)`.
  4. **Uniform-cost search.** A node holds its state, total cost (`Long`), and its path as an `IntArray` of action
     indices. Order the frontier (a `java.util.PriorityQueue`) by cost, then path length, then the path compared
     lexicographically; call this triple a node's **order key**. Keep a `best` map from state (wrap the `IntArray` in a
     small key class using `contentEquals` and `contentHashCode`) to the smallest order key seen for it. Comparing the
     full order key, not just the cost, is what guarantees the documented tie-breaks: a later path of equal cost that is
     shorter or uses earlier actions must replace the stored one.
  5. Pop a node. If its order key isn't the one in `best[state]`, it is stale: skip it without counting it. Otherwise
     count one expansion. If the state satisfies the goal, return `Found`. If the path length is `MAX_PLAN_LENGTH`,
     don't expand it. Otherwise, for each action in declaration order: count one action check; if the checks reach
     `MAX_ACTION_CHECKS`, stop the search with `NotFound(expansions, exhausted = true)`; if every precondition matches,
     apply the effects to a copy of the state; skip the successor if it equals the parent state (an action that changes
     nothing); push it if its order key is smaller than `best[successor]` (or unseen), updating `best`.
  6. When expansions reach `maxExpansions` before a goal is popped, return `NotFound(expansions, exhausted = true)`.
     When the frontier empties, return `NotFound(expansions, exhausted = false)`. A search that skipped successors only
     because of the depth limit counts as exhausted (track a flag when a depth-limited node is popped).
  7. The planner is a pure function: the same inputs give the same output, and it touches no global state.
  8. **Measure and tune `MAX_ACTION_CHECKS`.** Add a timing test that builds the worst case: 256 actions and 64 facts,
     where the action-check cap is what stops the search. Warm up the JIT with a few runs, then print the median time
     with `println`. The test asserts only that the result is `NotFound(exhausted = true)`, not a time, so it can't
     flake on slow CI. If the median is above about 5 ms on the development machine, lower `MAX_ACTION_CHECKS` (to a
     power of two) until it isn't. Record the measured figure and the machine in the constant's KDoc. If the final value
     differs from 262,144, use it everywhere this document says 262,144, including the spec text in Phase 2.

### Test & Documentation Requirements

- `GoapPlannerTest.kt` covers at least:
  - goal already holding → `Found([])`;
  - a three-step chain is found in the right order;
  - the cheaper of two plans wins even when it has more steps;
  - equal-cost plans: the shorter wins; equal cost and length: the one using earlier-declared actions wins, including
    when the winning path reaches a shared intermediate state after the losing one was already stored in `best`;
  - an absent fact matches nothing until an effect sets it;
  - `Bool(true)` does not match `Num(1)`;
  - an action whose effects change nothing is never added to a plan;
  - unreachable goal → `NotFound(exhausted = false)`;
  - a small `maxExpansions` on a solvable problem → `NotFound(exhausted = true)`;
  - a goal needing 33 steps → `NotFound(exhausted = true)`;
  - the action-check bound: 256 no-op-free actions over a wide state space returns `NotFound(exhausted = true)` without
    exceeding the bound (assert via `expansions`);
  - each validation error, and its message;
  - determinism: the same call twice gives equal results.
- KDoc on `GoapPlanner.plan` and each constant explains the semantics above in plain words, including that with more
  than 16 actions the action-check cap ends the search before `MAX_EXPANSIONS` can be reached.
- The worst-case timing test from step 8.

### Exit Conditions

- [] `./gradlew :core:test` passes.
- [] The worst-case timing test prints a median of about 5 ms or less, and the figure is recorded in KDoc.
- [] `./gradlew check` passes with zero warnings. The API files are unchanged.

---

## Phase 2: The `ctx.plan` query

### Objective & Scope

- **In Scope:** `ctx.plan` in `Ctx.kt` with Starlark conversion and validation; script tests; §4.5 text; CHANGELOG; the
  API files and a doc example.
- **Out of Scope:** `ai.star`, the tutorial zone, tutorials (Phases 3 to 5).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/ai/GoapPlanner.kt` (Phase 1).
  - `script/src/main/kotlin/gups/script/Values.kt` (`dict`, `list`, `str`, `int`, `isNone`, `fail`).
  - `script/src/main/kotlin/gups/script/Ctx.kt`: the `verb` helper (around line 120) and the `path_to`, `roll` and
    `has_feature` queries (around lines 1880–1935); mirror their shape.
  - `script/src/test/kotlin/gups/script/FeatureScriptTest.kt`: `featureProbe` and `runtimeError` (around lines 490 and
    620) show how a script test runs a body in a hook and reads its output or error; `ScriptFixture.kt`.
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`.
  - Create `script/src/test/kotlin/gups/script/PlanVerbTest.kt`.
  - `script/src/main/kotlin/gups/script/DocExamples.kt`.
  - `docs/SPEC.md` (§4.5), `docs/CHANGELOG.md`.
  - The API files (Guardrail 4).
- **Do Not Touch:** `core` (except fixing a Phase 1 bug the new tests expose), `Host.kt`, `Behaviors.kt`,
  `template-world`.

### Specification & Decision Record Changes

- §4.5, verbs table, **Queries** row: append `` `plan(facts, actions, goal, max_expansions=1024) -> [name] | None` ``
  after `has_feature(name) -> bool`.
- §4.5, under **Verb semantics** → **Queries**, add a bullet after the `path_to` bullet:

  > `plan(facts, actions, goal, max_expansions=1024)` is goal-oriented planning over data. `facts` and `goal` map fact
  > names to `bool`, `int` or `str` values; each action is a dict with `name` (unique), `pre` and `effects` (fact dicts,
  > default empty) and `cost` (an int of at least 1, default 1). Conditions are equality only, and a fact that is absent
  > matches nothing until an effect sets it. It returns the names of the cheapest plan (ties go to fewer steps, then to
  > earlier actions), `[]` when the goal already holds, and `None` when the goal is unreachable or the search runs out
  > of bounds: `max_expansions` (1 to 16,384), 32 steps per plan, and 262,144 action checks (one action tested at one
  > search node), which with more than 16 actions ends the search before `max_expansions` does. It is deterministic,
  > runs in the engine and spends no script steps. At most 256 actions and 64 distinct facts; a malformed argument is an
  > error. `//lib:ai.star` in the template builds on it.

### Implementation Blueprint

- **Signatures & Types** (in `Ctx`, next to `has_feature`):

```kotlin
@StarlarkMethod(
  name = "plan",
  doc = "Goal-oriented planning: the cheapest list of action names that reaches goal from facts, [] if it already holds, None if unreachable or out of bounds.",
  parameters = [
    Param(name = "facts", named = true, doc = "Dict of fact name to bool, int or str."),
    Param(name = "actions", named = true, doc = "List of dicts with name, pre, effects and cost."),
    Param(name = "goal", named = true, doc = "Dict of fact name to the value it must have."),
    Param(name = "max_expansions", named = true, defaultValue = "1024", doc = "Search nodes to expand at most, 1 to 16384."),
  ],
)
fun plan(facts: Any, actions: Any, goal: Any, maxExpansions: Any): Any
```

- **Algorithmic Steps & Invariants:**
  1. Wrap the body in `verb("plan")` so the feature check (none), wall-clock check, trace and error mapping apply; an
     `IllegalArgumentException` from the planner becomes `ctx.plan(): <message>`.
  2. Convert `facts` and `goal` with a private helper `factMap(v, what)`: `dict(v, what)`, then each value: a Starlark
     `Boolean` → `FactValue.Bool` (check `Boolean` before int), a `StarlarkInt` → `FactValue.Num` (via `long(...)`), a
     `String` → `FactValue.Text`; anything else fails with `"ctx.plan(<what>): fact '<k>' must be a bool, int or str,
     got <type>"`.
  3. Convert `actions` with `list(...)`; each element `dict(...)`; reject keys other than `name`, `pre`, `effects`,
     `cost` (`"unknown key 'run' in action 'x'"`); `name` via `str`; `pre`/`effects` via `factMap` (missing or `None` →
     empty); `cost` via `int` (missing or `None` → 1).
  4. `max_expansions` via `int(...)`.
  5. Call `GoapPlanner.plan`. `Found` → `sList(steps)`; `NotFound` → `Starlark.NONE`.
  6. No new `CtxExtras`, no `Frame` state, no feature ownership entry (it's kernel; confirm `FeatureOwnership` needs no
     registration for kernel symbols, as `has_feature` has none).

### Test & Documentation Requirements

- `PlanVerbTest.kt` (mirror the probe pattern in `FeatureScriptTest.kt`):
  - a small plan returns the expected list of names, and `[]` when the goal holds;
  - `None` for an unreachable goal and for `max_expansions = 1` on a two-step problem, with **no diagnostic recorded**;
  - bool vs int: `{"a": True}` doesn't satisfy `{"a": 1}`;
  - errors: a float fact value, a list fact value, an unknown action key, a duplicate name, `cost = 0`, `max_expansions
    = 0` and `16385`, 257 actions, 65 facts; each raises a script error whose message starts with `ctx.plan(`;
  - **step cost:** in a mob `think` (or a hook) under the default limits, a call with about 16 actions that expands
    about 10,000 nodes and returns a plan (not `None`) completes without a `step_limit` diagnostic, showing the search
    spends no script steps. Keep the action count at 16 or fewer, so the action-check cap can't end the search first and
    make the test pass trivially;
  - determinism: two worlds with different seeds return the same plan.
- `DocExamples.kt`: add an entry keyed `"ctx.plan"`, mirroring the `"ctx.level_up"` and `"ctx.merge_stack"` entries. Its
  `pkg` declares a room whose hook calls `ctx.plan` on a three-action table and sends the result, and its `test` body
  checks the three-step result. `harness/src/test/kotlin/gups/harness/DocExamplesTest.kt` runs it.
- CHANGELOG, new `### Mob planning` subsection: "Added `ctx.plan(facts, actions, goal, max_expansions=1024)`: native,
  deterministic goal-oriented planning over equality facts. It returns the cheapest list of action names, `[]` when the
  goal holds, or `None` when unreachable or out of bounds, and spends no script steps."

### Exit Conditions

- [] `./gradlew :core:test :script:test` passes.
- [] The API files are regenerated; the only new symbol is `ctx.plan`; `ApiDiffTest` passes.
- [] `./gradlew check` passes with zero warnings. Markdown is formatted.

---

## Phase 3: `//lib:ai.star`

### Objective & Scope

- **In Scope:** the behavior-tree and GOAP library; test-only probe mobs; node-semantics tests.
- **Out of Scope:** the squirrel, the pinecone and room changes (Phase 4); tutorial prose, §4.8, §7.4 (Phase 5). No
  Kotlin changes.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/lib/quest.star` and `template-world/lib/shop.star` (library style: a header comment with usage, `_`
    private functions, an exported `struct`-like namespace at the bottom; mirror how `quest` is exported).
  - `template-world/zones/tutorial/shop.star` (how test-only `test_` mobs live in the zone) and `shop_test.star`
    (harness usage).
  - §4.5 (verbs `act`, `act_move`, `path_to`, `plan`, `get_attr`/`set_attr`), §4.8, §4.12, §3.7.
- **Files to Modify / Create:**
  - Create `template-world/lib/ai.star`.
  - `template-world/lib/package.star` (add `ai` to the header comment's list).
  - Create `template-world/zones/tutorial/ai_probes.star` (test-only probe mobs prefixed `test_ai_`, each with a small
    tree in its `think`, recording what ran in its own attributes). No spawns: tests place them with `w.spawn`.
  - Create `template-world/zones/tutorial/ai_test.star`.
  - `docs/CHANGELOG.md`.
- **Do Not Touch:** Kotlin sources, `docs/SPEC.md`, `samples/robot-world`, `rooms.star`, `mobs.star`, existing tests.

### Specification & Decision Record Changes

- None in the spec. CHANGELOG bullet below.

### Implementation Blueprint

- **Public API of `ai.star`** (exported as `ai`, loaded with `load("//lib:ai.star", "ai")`):

```python
ai.SUCCESS, ai.FAILURE, ai.RUNNING           # "success", "failure", "running"
ai.selector(children)                        # first child not "failure" wins; all fail → "failure"
ai.sequence(children)                        # runs children while "success"; stops at the first other status
ai.condition(fn)                             # fn(ctx, bb) -> bool; True → "success", False → "failure"
ai.action(fn)                                # fn(ctx, bb) -> status
ai.inverter(child)                           # swaps "success" and "failure"; "running" unchanged
ai.steps(id, children)                       # ordered steps across cycles; progress at bb["steps"][id]
ai.goap(id, sense, actions, goal, max_expansions = 1024)
ai.run(ctx, tree)                            # call from think: returns "idle" or None
```

- **Algorithmic Steps & Invariants:**
  1. **Nodes are plain dicts** built at load time (`{"kind": "selector", "children": [...]}` etc.), so trees are frozen
     module globals and cost nothing to build per cycle. Validate arguments at construction with `fail(...)` (non-empty
     children, callables, a status in `ai.action` results: an unknown status fails the hook).
  2. **`ai.run(ctx, tree)`:** `bb = ctx.get_attr(ctx.self, "ai", {})`; keep `before = repr(bb)` (or a deep copy);
     `status = _tick(ctx, tree, bb)`; if `bb` changed, `ctx.set_attr(ctx.self, "ai", bb)`; return `"idle"` for
     `"success"`/`"running"`, `None` for `"failure"`. Because `ai.star` is the innermost running function's file, the
     key is stored in the lib namespace (§3.7).
  3. **`_tick`** dispatches on `kind`. Recommended: compile each tree at construction into a flat list of nodes with
     child index lists, and evaluate with an explicit stack of `(node, next_child_index)` frames. **Starlark has no
     `while` loop and no recursion**, so drive the stack with `for _ in range(_MAX_TICKS):` and `break` when the stack
     empties. Set `_MAX_TICKS = 1024`, and if the loop runs out, `fail("ai.run: tree too large")`. Cap trees at
     construction at a depth of 16 and 256 nodes, each with a clear `fail`. Write this first and test it in isolation;
     it is the riskiest part.
  4. **`ai.steps(id, children)`:** reads `i = bb.get("steps", {}).get(id, 0)`; ticks child `i`; on `"success"` advances
     `i` (and if past the end, resets to 0 and returns `"success"`); on `"running"` returns `"running"`; on `"failure"`
     resets to 0 and returns `"failure"`.
  5. **`ai.goap(id, sense, actions, goal, max_expansions)`:**
     - At construction: each action is a dict with the `ctx.plan` keys plus `run` (`fn(ctx, bb) -> status`). Build
       `plan_actions` (the same dicts without `run`) and a `name → run` dict once. `goal` is a dict, or a function
       `fn(ctx, bb) -> dict`.
     - Each tick:
       1. `facts = sense(ctx, bb)`; `g = goal(ctx, bb)` if callable else `goal`.
       2. If every `g` entry holds in `facts`: drop `bb["goap"][id]`; return `"success"`.
       3. `cache = bb.get("goap", {}).get(id)`. If `cache` exists and `cache["goal"] == g`: drop leading steps whose
          `effects` all hold in `facts`; if steps remain and the first step's `pre` all hold, use them. Otherwise
          replan.
       4. Replan: `steps = ctx.plan(facts, plan_actions, g, max_expansions)`. `None` → drop the cache, return
          `"failure"`. `[]` → drop the cache, return `"success"`.
       5. Run the first step's `run(ctx, bb)`. On `"failure"` drop the cache and return `"failure"`. Otherwise store
          `{"goal": g, "steps": steps}` at `bb["goap"][id]` and return `"running"`.
     - Keep the cache to plain dicts, lists and strings so it is storable.

### Test & Documentation Requirements

- `ai_test.star` (harness, `t.world(seed = 1, packages = ["//zones/tutorial"])` as `tutorial_test.star` does), using the
  `test_ai_` probes:
  - selector picks the first non-failure child; sequence stops at `"running"`; inverter;
  - `ai.steps` advances one step per cycle and resets on failure;
  - `ai.run` returns `None` on failure (the probe's built-in proposal runs; give that probe `wander(chance = 1.0)` and
    check it moved) and `"idle"` otherwise;
  - the blackboard persists across cycles and is not rewritten when unchanged;
  - `ai.goap` on a probe whose actions only record their names: it runs one step per cycle in plan order, keeps its
    cached plan while the next step's preconditions hold, replans when a test changes a sensed attribute, and returns
    `"failure"` when `ctx.plan` returns `None`;
  - construction errors: an empty selector, a tree deeper than 16, an unknown status from an action.
- CHANGELOG bullet: "The template has `//lib:ai.star`: behavior trees (`selector`, `sequence`, `condition`, `action`,
  `inverter`, `steps`) re-evaluated from the root each `think`, a per-mob blackboard, and an `ai.goap` leaf that caches
  `ctx.plan` results."

### Exit Conditions

- [] `./gradlew :cli:test` passes (it runs the template world's tests, including `ai_test.star`, and its lint).
- [] With a copy (`cp -R template-world local-world-test/world`), `cli/build/install/gups/bin/gups test --world
  local-world-test/world //zones/tutorial/...` and `gups lint --world local-world-test/world` pass (after `./gradlew
  :cli:installDist`). Delete the copy.
- [] `./gradlew check` passes with zero warnings; the API files are unchanged.

---

## Phase 4: The squirrel

### Objective & Scope

- **In Scope:** a tutorial-zone demo mob and item built on `ai.star`; their placement; their tests.
- **Out of Scope:** changes to `ai.star` other than fixing a bug the squirrel exposes (add a probe test for it in
  `ai_test.star` if so); tutorial prose (Phase 5).

### Context Working Set

- **Read / Reference Only:**
  - `template-world/lib/ai.star` (Phase 3).
  - `template-world/zones/tutorial/mobs.star` (mob declarations, comment tone).
  - `template-world/zones/tutorial/tutorial_test.star`: `test_the_gravel_crunches_where_it_is_heard`,
    `test_the_gravel_is_too_far_for_the_gate` and `_footwork` / `test_the_tar_pit_slows_footwork` use the two rooms the
    demo lives in. They check sounds and `wait_state`, not room contents, so they should be unaffected.
- **Files to Modify / Create:**
  - Create `template-world/zones/tutorial/forager.star` (the `pinecone` item, the `squirrel` mob and its tree).
  - `template-world/zones/tutorial/rooms.star` (`tar_pit`: `on_load` and `on_reset` keep one pinecone on its floor,
    mirroring `_stock_armory`; `gravel`: `spawns = [spawn(":squirrel")]`).
  - `template-world/zones/tutorial/ai_test.star` (append the squirrel tests).
  - `docs/CHANGELOG.md`.
- **Do Not Touch:** Kotlin sources, `docs/SPEC.md`, `samples/robot-world`. Existing tutorial tests must still pass. If
  one fails only because it asserts a room's listed contents and now sees the squirrel or the pinecone, update that
  expectation. For any other failure, change the new content (placement, timing), not the test.

### Specification & Decision Record Changes

- None in the spec. CHANGELOG bullet below.

### Implementation Blueprint

- **Algorithmic Steps & Invariants:**
  1. **The squirrel** (`forager.star`):
     - `item("pinecone", ...)`: small, light, carriable.
     - `mob("squirrel", ...)`: no built-in behaviors except `flee(below = vitals(hp = 50))`; `think = _squirrel_think`,
       `think_every = 4`.
     - Sense: `at_home` (its room is its `home_room`); `has_pinecone` (`ctx.self.has_item(":pinecone")`);
       `pinecone_here` (a pinecone on the floor of its current room that isn't home); `pinecone_known` (some pinecone on
       the floor of a zone room other than home, using `ctx.find(":pinecone", scope = "zone")` and filtering to ground
       items); `cached` (a pinecone on the home floor). Store the target room's label in `bb["target"]` when known.
     - GOAP actions (each with `run`): `walk_to_pinecone` (pre `pinecone_known: True, has_pinecone: False`; effects
       `pinecone_here: True`; run: first step of `ctx.path_to(bb["target"])` via `ctx.act_move`), `take_pinecone` (pre
       `pinecone_here: True, has_pinecone: False`; effects `has_pinecone: True, pinecone_here: False`; run: `ctx.act(
       ctx.self, "get pinecone")`), `go_home` (pre `has_pinecone: True, at_home: False`; effects `at_home: True`; run:
       first step toward `ctx.self.home_room`), `drop_pinecone` (pre `has_pinecone: True, at_home: True`; effects
       `has_pinecone: False, cached: True`; run: `ctx.act(ctx.self, "drop pinecone")`). Goal `{"cached": True}`. Use the
       command names through the world's command words as other mob code in the tutorial does, if it does.
     - Tree: `ai.selector([ ai.sequence([ai.condition(_fighting), ai.action(_yield)]), ai.goap("cache", ...) ])`, where
       `_fighting` is `len(ctx.self.fighting()) > 0` (guard with `ctx.has_feature("combat")`) and `_yield` returns
       `"failure"` so the whole tree fails and the built-in `flee` proposal runs. Comment the file in the tutorial's
       teaching tone.
     - The squirrel must not disturb the shop, armory or guide tests: it lives on the gravel path and only collects
       pinecones.
     - `ctx.find(":pinecone", scope = "zone")` returns item views in ascending id order, including carried ones; keep
       those whose `location` is a room (see the item view's `location` in §4.5).
     - Once a pinecone is cached at home the goal holds and the squirrel idles, even if the tar pit restocks. That is
       acceptable for the demo; say so in a comment.

### Test & Documentation Requirements

- Append to `ai_test.star`:
  - `test_squirrel_caches_a_pinecone`: locate the squirrel on `:gravel`, run enough pulses (`w.run(pulses = ...)`), and
    assert a pinecone lies on the gravel floor and none in the tar pit;
  - `test_squirrel_replans_when_the_pinecone_moves`: after it plans, a player picks the pinecone up and drops it in
    `:hall`; the squirrel still caches it;
  - `test_squirrel_idles_with_nothing_to_fetch`: destroy the tar pit's pinecone first; the tree fails and the squirrel
    stays put (it has no built-in movement behaviors);
  - `test_squirrel_flees_when_hurt`: `w.damage(squirrel, "hp", pct = 60)` while it fights; it leaves the room (use
    `bubble = False`, the default).
- CHANGELOG bullet (append): "The tutorial zone's squirrel uses `//lib:ai.star` to fetch pinecones from the tar pit."

### Exit Conditions

- [] `./gradlew :cli:test` passes, including every existing tutorial test.
- [] The same copied-world `gups test` and `gups lint` runs as Phase 3 pass. Delete the copy.
- [] `./gradlew check` passes with zero warnings; the API files are unchanged.

---

## Phase 5: Tutorial, spec pointers, and finalization

### Objective & Scope

- **In Scope:** the tutorial 05 section and correction; §4.8; §2.3 if needed; §7.4; an end-to-end scenario; marking the
  `DESIGN_NEEDED` entry completed.
- **Out of Scope:** engine and library behavior changes.

### Context Working Set

- **Read / Reference Only:** `template-world/lib/ai.star`, `template-world/zones/tutorial/forager.star` (Phases 3 and
  4); `docs/PLUGINS.md` (the builtins section, to link to); §4.8, §2.3, §7.4.
- **Files to Modify / Create:**
  - `template-world/docs/tutorials/05_mobs_and_behaviors.md`.
  - `docs/SPEC.md` (§4.8, §7.4, and §2.3 only if it lists the template's lib files).
  - `docs/DESIGN_NEEDED.md`, `docs/CHANGELOG.md`.
  - Scratch only: `local-world-test/`.
- **Do Not Touch:** Kotlin, `ai.star`, `forager.star`.

### Specification & Decision Record Changes

- §4.8, after the bullets: "The template world's `//lib:ai.star` builds behavior trees, a per-mob blackboard and
  goal-oriented planning (through `ctx.plan`) on top of `think`."
- §7.4, new row after the last one (`markdownFormat` pads it to the table's column widths):

```markdown
| **M30** | Behavior trees and planning: the native `ctx.plan` query (uniform-cost GOAP over equality facts, bounded and deterministic, no script steps), the template's `//lib:ai.star` (trees re-evaluated each `think`, a blackboard, a caching `goap` leaf) and the tutorial's squirrel (§4.5, §4.8). |
```

### Implementation Blueprint

1. **Tutorial 05, section 3 correction:** replace the `_shaman_think` example's use of `ctx.target` with `foes =
   ctx.self.fighting()` and `foes[0]`, fix the comment, and replace the "Combat Awareness" bullet with: "Within `think`,
   `ctx.self.fighting()` lists the mob's opponents; `ctx.target` is not set." Also fix the example's verbs if they don't
   exist (`ctx.cast` is not a verb; the mob verb is `ctx.act_cast(mob, skill, target)`; check §4.5).
2. **New tutorial section** "Behavior trees and planning" between "Custom AI" and "Spawns". Cover, briefly and with one
   short example each (fences at column 0, not in lists):
   - loading `//lib:ai.star`, the node kinds, statuses, and `return ai.run(ctx, TREE)` from `think`;
   - why the tree restarts from the root each cycle, and the blackboard (and its 64 KB cap) for anything that must carry
     over; `ai.steps` for strict sequences;
   - GOAP: sensing facts once per cycle into booleans (`"hp_low": True`, not comparisons), the action table, the goal,
     plan caching, and pointing at the squirrel in `forager.star`;
   - cost: trees and sensing spend script steps; `ctx.plan` spends none but has bounds and returns `None` when they run
     out; spread many planning mobs with different `think_every` values; avoid world-wide queries every cycle;
   - beyond this: bespoke AI that must run natively belongs in a plugin builtin (link `docs/PLUGINS.md`).
3. Update the tutorial's "Reference Links" to mention `lib/ai.star` and `zones/tutorial/forager.star`.
4. **End-to-end check:** `cp -R template-world local-world-test/world`; write `local-world-test/squirrel.star` with one
   test that spawns a player at `:gravel`, runs pulses, prints the squirrel's room each few pulses, and asserts the
   pinecone ends on the gravel floor; run it with `gups scenario --world local-world-test/world --package
   //zones/tutorial local-world-test/squirrel.star` as `AGENTS.md` describes. Read the transcript to confirm the
   squirrel walks east, takes the pinecone, returns and drops it. Delete `local-world-test/`.
5. **`docs/DESIGN_NEEDED.md`:** rename the heading to `### Behavior trees and GOAP (COMPLETED — M30)` and leave the body
   in place (do not delete it).
6. CHANGELOG bullet: "Tutorial 05 covers behavior trees and planning, and no longer says `ctx.target` is the opponent
   inside `think` (use `ctx.self.fighting()`)."

### Test & Documentation Requirements

- No new automated tests; the scenario is the end-to-end check.
- Run `./gradlew markdownFormat` and check the tutorial's code blocks kept their indentation.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings. Markdown is formatted.
- [] The `gups scenario` run passed and its transcript showed the squirrel's round trip. `local-world-test/` is deleted.
- [] The `DESIGN_NEEDED` entry is marked `(COMPLETED — M30)`, not deleted, and the §7.4 row is present.
