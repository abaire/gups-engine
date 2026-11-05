# Milestone 34: The `on_encumbrance_change` Global Hook

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** none. This is the follow-up that `docs/MILESTONE_32.md` deliberately left out ("Not in
  this milestone: an `on_encumbrance_change` hook"), made possible by the `global_hook` mechanism of
  `docs/MILESTONE_33.md`. There is no `DESIGN_NEEDED` entry to mark done.

- **Prerequisites (check first, stop and report if either is missing):**

  - M32 is implemented: `rg -n "encumbranceLevel" core/src/main/kotlin/gups/core/world/Items.kt` finds the level code in
    `updateEncumbrance`.
  - M33 Phase 4 is implemented: `rg -n "dispatchGlobal|GlobalHookDecl" core/src/main script/src/main` finds both.
- **Design Summary:** a world's `lib` or `rules` package can register a hook that runs whenever any actor's encumbrance
  level changes, so a world can add per-tier flavor, effects or bookkeeping that the engine's generic messages don't.
  - **`global_hook("on_encumbrance_change", fn)`** is accepted from privileged packages, exactly like
    `global_hook("on_level_change", fn)` (M33). It is a kernel hook: no feature needs to be on, and it is never inert.
  - **It is global only.** It can't be attached to a mob, room or item (`mob(on_encumbrance_change=...)` is an error).
    Per-owner hooks for players are a separate design.
  - **When it fires:** in `updateEncumbrance`, once for each change of `Actor.encumbranceLevel` (any change, up or down,
    including to and from 0), for every actor, players and mobs. It fires:
    - after the actor has been told (`encumbrance.on`/`up`/`down`/`off`), after the stat deltas are in place, and after
      the fit check (`refreshFit`), so the hook sees the final state;
    - **not** when the level is first computed during a restore (`announceFit = false`), because a returning character's
      saved load isn't a change. It does fire after a reload's reconciliation and at world ticks, as the messages do.
    - It fires on the level, not on the stat deltas: a change of deltas at the same level fires nothing.
  - **ctx:** `ctx.self` and `ctx.actor` are the actor. New read-only fields `ctx.old_encumbrance_level` and
    `ctx.new_encumbrance_level` (ints). Every other field is `None`, as for any hook.
  - **Order and errors:** as for the global `on_level_change`: package label order, then registration order; script
    errors, limits and quarantine are keyed to the registering package; an error doesn't stop the level update. The
    return value is ignored; the hook can't veto.
  - **Re-entry:** the hook may move or destroy items, which recomputes the level and can fire the hook again. The
    existing cascade guard (`CASCADE_MAX_DEPTH`) bounds that; nothing else is added.
  - **The template world** gets a small example in its `rules` package that tells the actor a flavor line at heavy
    levels, and the tutorial mentions it.
- **Specification Impact:** §4.4 (catalog row and paragraph), §4.9 ("Global hooks" paragraph from M33), §7.4 (M34 row).
- **Decision Record Updates:** None. `docs/DECISIONS.md` holds no live records; do not add a new `D-xxx`.

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first and see them fail.
   - No spec section numbers or decision ids in comments, Kotlin or Starlark.
   - All Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`. The engine is
   unreleased, so no migrations or back-compat shims are needed.
3. Add CHANGELOG bullets to a new `### Encumbrance hook` subsection at the top of `## api_version 1 (unreleased)`, above
   whatever subsection is first there now. Create it in the first phase that adds a bullet, and have each later phase
   append to it.
4. In any phase that changes the script API or template-world content (both phases), regenerate all three API files
   before the phase's final `./gradlew check` if any golden or API test fails:
   - Run `./gradlew :cli:test -Dgups.updateGolden=true`. This rewrites `cli/src/test/resources/api.golden.json` and the
     `cli/src/test/resources/docs-golden/` files.
   - Run `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout. This rewrites `docs/api/api-1.json`.
   - Read the diff. Only the symbols and content that phase names may be new or changed. `ApiDiffTest` must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing entry.
5. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation.
6. Never read or touch `local-world/`. Put scratch files in `local-world-test/` and delete it when you are done. Do not
   run `gups` commands directly against `template-world/`; copy it first (`cp -R template-world
   local-world-test/world`). The only exception is `gups scenario --world template-world`, which `AGENTS.md` allows.
7. Every phase must leave every existing test passing without editing its expectations, unless the phase says otherwise.

---

## Phase 1: The hook, its ctx fields and the firing

### Objective & Scope

- **In Scope:**
  - A `HookName`, two `HookContext` fields and two `ctx` fields.
  - A catalog entry that attaches to nothing (global only).
  - `global_hook` accepting the new name.
  - Firing from `updateEncumbrance`.
  - Tests, spec text, changelog, API files.
- **Out of Scope:** *(do not attempt these)* attaching the hook to mobs, rooms, items or players; plugin listeners; a
  veto; the template world; any other global hook name; any change to messages or to how levels are computed.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Items.kt` (`updateEncumbrance`, ~128)
  - `core/src/main/kotlin/gups/core/world/Hooks.kt` (`HookName`, `HookContext` ~150-200, `dispatchGlobal` from M33,
    `withCascadeGuard`)
  - `core/src/main/kotlin/gups/core/world/Leveling.kt` (the `dispatchGlobal` call sites M33 added, as the pattern)
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`HookCatalog` ~115, the `HookSpec` for `ON_LEVEL_CHANGE` ~297,
    the `global_hook` declaration from M33)
  - `script/src/main/kotlin/gups/script/ApiModel.kt` (~128-140, how a hook keyword is validated against `attach`)
  - `script/src/main/kotlin/gups/script/Ctx.kt` (`old_level` at ~372, as the template for a nullable int field)
  - `script/src/test/kotlin/gups/script/GlobalHookTest.kt` (M33; copy its setup)
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Hooks.kt`
  - `core/src/main/kotlin/gups/core/world/Items.kt`
  - `script/src/main/kotlin/gups/script/Declarations.kt`
  - `script/src/main/kotlin/gups/script/Ctx.kt`
  - `script/src/test/kotlin/gups/script/GlobalHookTest.kt` (extend; update the M33 refusal message assertion)
  - `script/src/main/kotlin/gups/script/DocExamples.kt` (only if a test demands an example)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the regenerated API files
- **Do Not Touch:** `template-world/`, `samples/`, `plugin-api/`, `Leveling.kt`, pickup code.

### Specification & Decision Record Changes

In `docs/SPEC.md`:

- §4.4 catalog table, add a row after `on_level_change`:

  ```markdown
  | `on_encumbrance_change`  | — | Global only (§4.9) | `actor`, `old_encumbrance_level`, `new_encumbrance_level` |
  ```

  Keep the table's column widths aligned the way the formatter leaves it.

- §4.4, after the `on_level_change` paragraph, add:

  ```markdown
  **`on_encumbrance_change`** is a global hook (§4.9) and can't be attached to an entity. It fires once for each change
  of an actor's encumbrance level (§4.6), up or down and to or from 0, for players and mobs, after the actor has been
  told, its stat deltas are set and the fit of its worn items is checked. It doesn't fire when a restored character's
  level is first worked out. A change of stat deltas at the same level fires nothing. `ctx.self` and `ctx.actor` are the
  actor; `ctx.old_encumbrance_level` and `ctx.new_encumbrance_level` are the levels. Its return value is ignored.
  ```
- §4.9 "Global hooks" paragraph (from M33): replace "`on_level_change` is the only hook that can be global." with
  "`on_level_change` and `on_encumbrance_change` are the hooks that can be global." and add: "`on_encumbrance_change`
  belongs to the kernel, so it fires in every world."
- §4.4 context-fields text that lists the fields the engine may set: add the two new field names if that list is
  explicit.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Hooks.kt, HookName, after ON_LEVEL_CHANGE
  /**
   * Invoked, as a global hook only, once for each change of an actor's encumbrance level.
   *
   * [HookContext.actor] is the actor, [HookContext.oldEncumbranceLevel] and
   * [HookContext.newEncumbranceLevel] are the levels. It fires after the actor has been told and
   * its fit has been rechecked.
   */
  ON_ENCUMBRANCE_CHANGE("on_encumbrance_change"),

  // HookContext, next to oldLevel/newLevel
  /** The actor's encumbrance level before the change, for `on_encumbrance_change`. */
  val oldEncumbranceLevel: Int? = null,
  /** The actor's encumbrance level after the change, for `on_encumbrance_change`. */
  val newEncumbranceLevel: Int? = null,
  ```

  ```kotlin
  // Declarations.kt, HookCatalog
  HookSpec(
    hook = HookName.ON_ENCUMBRANCE_CHANGE,
    signature = "fn(ctx)",
    attachTo = emptyList(),
    ctxFields = listOf("self", "actor", "old_encumbrance_level", "new_encumbrance_level", "room", "pulse", "rng"),
    doc = "Global hook: fires once for each change of an actor's encumbrance level.",
  ),
  ```

  `Ctx.kt`: two `structField` members, `old_encumbrance_level` and `new_encumbrance_level`, each
  `hc.<field>?.let(::sInt) ?: Starlark.NONE`, with docs "For on_encumbrance_change: ...", mirroring `old_level`.

- **Algorithmic Steps & Invariants:**

  1. **Catalog and attach check.** Because `attachTo` is empty, the existing check in `ApiModel.kt` (~134) already
     rejects `mob(on_encumbrance_change=...)`, `room(...)` and `item(...)`. Confirm that with a test; if its message
     doesn't say the hook is global-only, special-case an empty `attachTo` to fail with "<name>(): hook '<key>' is
     global; register it with global_hook()". Make sure the API model and docs renderer cope with a hook that attaches
     to nothing (the docs table should show "Global only"); fix the renderer if it assumes at least one owner.
  2. **`global_hook` accepts the name.** In the M33 declaration code, change the accepted names from the single
     `on_level_change` to the set `{on_level_change, on_encumbrance_change}`, and change the refusal to "global_hook():
     '<name>' can't be a global hook; only on_level_change and on_encumbrance_change can". Skip the feature-ownership
     refusal for both (as M33 does). `invokeGlobal`'s early return must stay tied to `Feature.LEVELING` **only for
     `ON_LEVEL_CHANGE`**: `ON_ENCUMBRANCE_CHANGE` always runs.
  3. **Fire.** In `updateEncumbrance` (`Items.kt`), after the tell and after `refreshFit`, add:

     Write the call shown under "Firing snippet" below. Use the real `dispatchGlobal` signature from M33 (its parameters
     may differ from this sketch). `announceFit` is `false` only for restore, which is the case that must not fire.

  4. `Ctx` for a global hook has `ctx.self` set to the actor (M33's `invokeGlobal` does this); `room` is the actor's
     room.

### Firing snippet

For `updateEncumbrance` in `Items.kt`:

```kotlin
if (wasLevel != a.encumbranceLevel && announceFit)
    dispatchGlobal(
        HookName.ON_ENCUMBRANCE_CHANGE,
        HookContext(
            actor = a,
            oldEncumbranceLevel = wasLevel,
            newEncumbranceLevel = a.encumbranceLevel,
        ),
    )
```

### Test & Documentation Requirements

Write tests first, in `GlobalHookTest.kt`, reusing its setup and a fixture or script rules that return a tiered level
(model on the M32 tests in `ItemCommandsTest.kt`):

- A lib's `global_hook("on_encumbrance_change", fn)` sees a player loaded from level 0 to 1: `old_encumbrance_level` 0,
  `new_encumbrance_level` 1, `ctx.self` the player, and it fires after the "You are carrying more than you can manage."
  line (record the order with an attribute list, as the M33 order test does).
- Loading further (1 to 2) and lightening (2 to 1, 1 to 0) fire it each time with the right values; a change of stat
  deltas at the same level does not.
- A mob's level change fires it too.
- It fires in a world without `leveling` (use the setup from `FeatureScriptTest.kt`), and the M33 test that a
  `leveling`-less world never fires `on_level_change` still passes.
- Restore: a character saved while encumbered logs in and the hook does not fire.
- Two packages fire in label order; a zone declaring it fails to load; `global_hook("on_nonsense", fn)` fails with the
  new two-name message (update the M33 assertion for the old message to the new text, nothing else).
- `mob(on_encumbrance_change = fn)` fails to load with the global-only error.
- A hook that drops an item (re-entry) terminates, bounded by the cascade guard; a failing hook records a diagnostic and
  leaves the level update intact; a quarantined package's hook is skipped.
- CHANGELOG bullet: "`global_hook(\"on_encumbrance_change\", fn)` for `lib` and `rules` packages: fires once for each
  change of an actor's encumbrance level, with `ctx.old_encumbrance_level` and `ctx.new_encumbrance_level`."
- KDoc on every new member.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, after regenerating the API files (guardrail 4). The diff shows only
  the new hook, its two `ctx` fields and the `global_hook` text.
- [] `./gradlew markdownFormat markdownCheck` passes.
- [] All existing `on_level_change`, global hook and encumbrance tests pass; the only edited expectation is the M33
  refusal message.

---

## Phase 2: Template example, tutorial and finalization

### Objective & Scope

- **In Scope:**
  - A small `global_hook("on_encumbrance_change", ...)` in the template world's `rules` package.
  - A tutorial paragraph.
  - The tutorial test.
  - The §7.4 roadmap row.
- **Out of Scope:** *(do not attempt these)* engine changes, new hook names, changes to the template's
  `encumbrance_level` formula or messages.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/rules/combat.star` (the M32 `encumbrance_level` and `encumbrance` functions)
  - `template-world/lib/` (how an existing library declares `global_hook("on_level_change", ...)` and `global_command`,
    if M33's trainer is present; otherwise `docs/MILESTONE_33.md` around line 554)
  - `template-world/zones/tutorial/tutorial_test.star` (the M32 encumbrance test, for how to load an actor to a level)
  - the template tutorial markdown that discusses encumbrance (grep `encumbrance_level` in `template-world/docs/`)
  - `ctx.tell` or whichever verb sends a line to one actor (grep the `Messaging` row in the verb table of `docs/SPEC.md`
    §4.5)
- **Files to Modify / Create:**
  - `template-world/rules/combat.star` (or a new `template-world/rules/encumbrance.star` if `rules` packages are split
    by file; follow how the other rules files are loaded)
  - `template-world/zones/tutorial/tutorial_test.star`
  - the template tutorial markdown that discusses encumbrance
  - `docs/SPEC.md` (§7.4 only), `docs/CHANGELOG.md`
- **Do Not Touch:** engine Kotlin, `samples/` worlds, the API files unless guardrail 4 requires regeneration.

### Specification & Decision Record Changes

Add after the M33 row in `docs/SPEC.md` §7.4, in the same style:

> **M34** | The `on_encumbrance_change` global hook: `global_hook("on_encumbrance_change", fn)` for privileged packages,
> fired once per encumbrance level change for every actor, `ctx.old_encumbrance_level` and `ctx.new_encumbrance_level`,
> the template's flavor example (§4.4, §4.9).

### Implementation Blueprint

- **Signatures & Types:** Starlark, in the template's rules package:

  ```python
  def _encumbrance_flavor(ctx):
    # Extra flavor for the heavy tiers; the engine already sent its generic line.
    if ctx.new_encumbrance_level >= 3 and ctx.new_encumbrance_level > ctx.old_encumbrance_level:
          ctx.tell(ctx.actor, "Your pack drags at your shoulders, and every step is a labor.")

  global_hook("on_encumbrance_change", _encumbrance_flavor)
  ```

  Use the real message verb's name and signature from the SPEC verb table; if it differs from `ctx.tell(actor, text)`,
  adapt. The `rules` package may need the declaration in a file that is loaded at the top level of the package; follow
  where the template's other top-level declarations in `rules/` live.

- **Algorithmic Steps & Invariants:**

  1. Write the tutorial test first: load a tutorial actor from level 2 to level 3 with the M32 test's technique, and
     assert that the flavor line appears once, that going from 3 to 4 sends it too (new above old, 4 is at least 3), and
     that lightening to 2 sends nothing. Use `p.do(...)`/`p.output()` as the existing tutorial tests do.
  2. Add the hook until it passes.
  3. Add a short tutorial paragraph next to the encumbrance material: the engine's generic `encumbrance.up`/`down`
     lines, and `global_hook("on_encumbrance_change", fn)` for per-tier flavor or effects, with the example above.
  4. Append a changelog bullet: "The template world adds a flavor line at heavy encumbrance levels through the new
     hook."
  5. Add the §7.4 row.

### Test & Documentation Requirements

- The tutorial test above runs in the template world's harness; also run the other bundled worlds' tests (`samples/*`)
  to confirm they load (they declare no such hook and are unaffected).
- Run `./gradlew markdownFormat` and check code block indentation.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including the module-graph check and markdown check.
- [] The tutorial test shows the flavor line at levels 3 and 4 only on the way up.
- [] `git status` shows no change to the API files beyond what guardrail 4 required for template content (normally
  none).

---

## Finalization

- Run `./gradlew check` and `./gradlew markdownFormat markdownCheck` one last time and confirm they are green.
- There is no `DESIGN_NEEDED` entry to mark. Do not edit `docs/DESIGN_NEEDED.md`.
