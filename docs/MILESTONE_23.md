# Milestone 23: Item Value, Custom Hooks and the Template's Charisma

**Prerequisites:** [M21](MILESTONE_21.md) and [M22](MILESTONE_22.md) must be finished first. This milestone reuses M21's
read-only `PluginGmcpWorld` and plain-value check, and M22's script-to-plain converter (`toGmcp` in
`script/.../Values.kt`). [M24](MILESTONE_24.md) (the shop library) builds on this milestone. Where line numbers below
are wrong, find the symbol by name.

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** [DESIGN_NEEDED.md](DESIGN_NEEDED.md): `## Economy and crafting` → `### Shops`. This
  milestone builds the kernel pieces the shop library needs; [M24](MILESTONE_24.md) builds the library and resolves the
  entry. None of the pieces here is shop-specific.
- **Design Summary:**
  - **`item(value = N)`.** A non-negative int, default 0, on `ItemProto`. It is the base price of one item (one unit of
    a stack). The engine gives it no other meaning and doesn't show it in `look`, `inventory` or `examine`.
    `//engine:corpse` has 0. Scripts read it as `item.value`, plugins as `PluginItem.value`. It is a prototype field, so
    a reload changes it for every instance; nothing is saved per instance.
  - **Custom hooks.** `room`, `mob` and `item` take `hooks = {"name": fn}`. Names match `[a-z][a-z0-9_]*` and may not be
    a catalog hook name (`on_use`, `think`, …); the value is a top-level function, like a `commands` entry.
  - **`ctx.call_hook(entity, name, **args)`** runs the entity's custom hook `name` as `fn(ctx, **args)` with `ctx.self`
    the entity, then each plugin handler registered for `name` (below), and returns the chain's result.
    - The hook runs in its own package's context (attributes, quarantine, step charging), like any hook of that entity's
      prototype. `ctx.args` is `None`; the arguments arrive only as keyword arguments.
    - **Arguments** may be `None`, ints, strings, bools, lists, dicts with string keys, and actor, item and room views,
      nested freely. Views are passed by identity and arrive as fresh views in the hook (or `None` if the entity left
      the world meanwhile). Anything else is a script error in the caller.
    - **Results** may be `None`, ints, strings, bools, lists and dicts with string keys. No views: a hook that returns
      one fails like a hook that raises. The caller gets a fresh, mutable copy.
    - **`None` results:** the entity is gone (`in_world` false), it has no such hook and no plugin handles `name`, the
      hook's package is quarantined, or the hook failed. A failing hook is recorded like any hook error (diagnostics,
      quarantine counting) and doesn't fail the caller.
    - **Nesting:** a custom hook may call `call_hook`. Nested custom hooks share the running event's step budget, and
      more than 8 nested `call_hook` calls is a script error in the caller ("call_hook is nested too deeply").
    - The verb is kernel (no feature) and works on players too (their prototype has no hooks, so only plugin handlers
      run for them).
  - **Plugin handler chain.** `r.addHook(name, doc) { call -> result }` registers a handler for a custom hook name.
    After the entity's own hook (if any), every plugin handler for `name` runs in plugin-id order. Each gets a
    `PluginHookCall` with `world` (the read-only `PluginGmcpWorld`), `entity` (a `PluginRoom`, `PluginActor` or
    `PluginItem`), `name`, `args` (plain values, with views as plugin entity views) and `result` (the result so far,
    plain values), and returns the new result (plain values only). A handler that throws records a `plugin` diagnostic
    and leaves the result as it was. A plugin may register a name once; several plugins may register the same name.
    `plugins.html` lists each plugin's hook handlers.
  - **Charisma.** The template world's `MODULE.gups` gains `stat("cha", label = "Charisma")`, and the template's
    `rules.starting_stats` gives players 10 and mobs their declared value or 10, like the other ability stats.
  - **Out of scope:** a plugin facade `callHook` (logged in `DESIGN_NEEDED` → `### Plugins calling custom hooks`); any
    shop behavior; showing `value` anywhere in game output; per-instance values.
- **Specification Impact:** §3.3 (item fields: `value`), §3.9 (the template schema example: `cha`), §4.2 (item example
  and declaration rules: `value`, `hooks`), §4.4 (custom hooks), §4.5 (item view `value`, the `call_hook` verb), §4.15
  (`PluginItem.value`, `addHook`, `PluginHookCall`), §4.16 (`plugins.html` row), §7.4 (new M23 row); `docs/PLUGINS.md`;
  `docs/CHANGELOG.md`; template tutorial 06; the regenerated API files.
- **Decision Record Updates:** None.

### Guardrails for every phase

1. Follow `AGENTS.md`: TDD (write tests first and see them fail), no spec section numbers or decision ids in code
   comments, all Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`.
3. Add CHANGELOG bullets in a new `### Item value and custom hooks` subsection at the top of `## api_version 1
   (unreleased)` (above M22's). Create it in the first phase that needs it.
4. In any phase that changes the script API (a declaration parameter, a view field, a `ctx` verb), regenerate all three
   API files **before** the phase's final `./gradlew check`:
   - `./gradlew :cli:test -Dgups.updateGolden=true` rewrites `cli/src/test/resources/api.golden.json` and the
     `cli/src/test/resources/docs-golden/` files;
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout, rewrites `docs/api/api-1.json`;
   - read the diff: only the symbols this phase adds or changes should differ. `ApiDiffTest` must then pass.
   - if a test fails because a new symbol has no example, add one in `script/.../DocExamples.kt`, mirroring an existing
     entry.
5. Gradle commands must run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that
   code blocks kept their indentation.
6. No database migrations; `value` is a prototype field and is never saved.
7. Never read or touch `local-world/`.

---

## Phase 1: `item(value = N)`

### Objective & Scope

- **In Scope:** the prototype field, the declaration argument, the script item view field, the plugin item view field.
- **Out of Scope:** anything that uses the value.

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Declarations.kt`: `item(...)` (around lines 929–1065) and `ITEM_SPEC` (around
    line 2228); how `weight` is parsed and checked is the pattern.
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (line ~273) and wherever `//engine:corpse`'s `ItemProto` is built
    (`grep -rn "engine:corpse" core/src/main`), to confirm it gets the default 0.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`ItemProto`)
  - `script/src/main/kotlin/gups/script/Declarations.kt`
  - `script/src/main/kotlin/gups/script/Views.kt` (`ItemView`)
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt` (`PluginItem`), `plugin-api/.../host/Facade.kt` (`ItemFacade`)
  - Tests: `script/src/test/kotlin/gups/script/LoaderTest.kt`, a view test (find where `item.weight` is tested: `grep
    -rn "\.weight" script/src/test | head`), `plugin-api/src/test/kotlin/gups/pluginapi/FacadeTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API files
- **Do Not Touch:** `persist/`, `harness/`, `template-world/`.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§3.3, Item, "Quantities and decay" bullet**: append "An item prototype's `value` (default 0) is the base price of
   one item; the engine gives it no other meaning."
2. **§4.2 example**: in `item("short_bow", ...)`, change `name="a short bow", plural=None, kind="ranged", range="far",
   weight=2,` to `name="a short bow", plural=None, kind="ranged", range="far", weight=2, value=30,`.
3. **§4.2 declaration rules**, after the **Charges** bullet, add: "- **Value:** `value` is an item's base price, an int
   of at least 0 (default 0). The engine gives it no meaning; libraries such as shops read it. `//engine:corpse` has 0."
4. **§4.5 Item view**: add `value` after `weight`.
5. **§4.15 Read-only views**: add `value` to the list of `PluginItem` fields.

### Implementation Blueprint

- **Signatures & Types:** `ItemProto`: `/** Base price of one item; the engine gives it no other meaning. */ val value:
  Int = 0,` after `weight`. `PluginItem`: `/** Base price of one item, from its prototype. */ val value: Int`.
  `ItemView`: a `value` struct field returning `StarlarkInt`.
- **Algorithmic Steps & Invariants:**
  1. `item()`: `val value = d.int("value") ?: 0`; `if (value < 0) fail("item(value): must be at least 0")`. Add `value`
     to `ITEM_SPEC` with a doc ("Base price of one item; 0 by default.").
  2. Views and facades read `item.proto.value`.
  3. CHANGELOG bullet. Regenerate the API files (expect `item.value` and the `value` declaration parameter only).

### Test & Documentation Requirements

- `` `item value defaults to 0 and must not be negative` `` (loader); `` `item views show value` `` (script, with a
  stack: the view shows the per-item value); `` `PluginItem shows value` `` (plugin-api); a corpse's value is 0.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `ApiDiffTest`.
- [] `grep -n '"value"' docs/api/api-1.json` finds the item declaration parameter and the view field.

---

## Phase 2: Charisma in the template world

### Objective & Scope

- **In Scope:** `cha` in the template's schema and starting stats; the spec's template schema example; any goldens that
  print the template schema.
- **Out of Scope:** anything that reads `cha` (M24's tutorial shop).

### Context Working Set

- **Read / Reference Only:** `template-world/rules/combat.star`, `starting_stats` (line ~41).
- **Files to Modify / Create:**
  - `template-world/MODULE.gups`
  - `template-world/rules/combat.star`
  - Goldens and tests that show the template schema: find them with `grep -rln '"wis"\|Wisdom' cli/src/test
    harness/src/test core/src/test script/src/test net/src/test webclient 2>/dev/null` and check each one is about the
    template world before changing it.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `samples/` worlds, engine code.

### Specification & Decision Record Changes

Edit `docs/SPEC.md` §3.9, the template example: change `stat("wis", label="Wisdom"), "armor",` to `stat("wis",
label="Wisdom"), stat("cha", label="Charisma"), "armor",`. If §4.6 shows the template's `starting_stats`, add `cha`
there the same way.

### Implementation Blueprint

- **Algorithmic Steps & Invariants:**
  1. `MODULE.gups`: add `stat("cha", label = "Charisma")` after `wis`, in the file's existing style.
  2. `starting_stats`: players `cha = 10`; mobs `cha = _or(a.base.cha, 10)`, next to `wis` in both branches.
  3. Update the template's comment block if it lists the stats. Regenerate goldens (guardrail 4) if any changed and read
     the diff: only `cha` should appear.
  4. CHANGELOG bullet under the subsection: "The template world declares `cha` (Charisma); existing template-based
     worlds need `--wipe` to pick it up."

### Test & Documentation Requirements

- The template's own tests (`template-world/zones/tutorial/tutorial_test.star`) still pass. Add one assertion there that
  a spawned player has `cha == 10` (`t.eq(p.cha, 10)` or the file's equivalent).

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including the template world's Starlark tests.
- [] `grep -n 'cha' template-world/MODULE.gups` shows the stat.

---

## Phase 3: Declaring custom hooks

### Objective & Scope

- **In Scope:** the `hooks` argument on `room`, `mob` and `item`; validation; storage per prototype label; host lookup.
- **Out of Scope:** calling them (Phase 4); plugins (Phase 5).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Declarations.kt`: `commands(...)` (line ~475) as the parsing pattern;
    `HookCatalog.specs` (line ~111) for the catalog names; where `hookMap` entries are added (`grep -n "hookMap"
    script/src/main/kotlin/gups/script/*.kt`).
  - `script/src/main/kotlin/gups/script/Packages.kt`: `ScriptPackage.hooks` / `hookMap` (line ~193).
  - `script/src/main/kotlin/gups/script/Host.kt`: `scripts.hooks(label)` and `hasHook` (line ~304).
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Declarations.kt`
  - `script/src/main/kotlin/gups/script/Packages.kt`
  - The class behind `scripts` in `Host.kt` (where `hooks(label)` is defined) to add `customHooks(label)`
  - New `script/src/test/kotlin/gups/script/CustomHooksTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API files
- **Do Not Touch:** `core/`, `plugin-api/`.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§4.2 example**: add `hooks={}, # custom hooks, run with ctx.call_hook (§4.4)` to the `mob(...)` example after
   `think_every=4,`, and `hooks={},` to the `item(...)` example next to `commands={}`.
2. **§4.4**, after "**Catalog enforcement:**", add:
   - **Custom hooks:** `room`, `mob` and `item` take `hooks = {"name": fn}`, hooks of the builder's own naming. Names
     match `[a-z][a-z0-9_]*` and may not be catalog names; each value is a top-level function. The engine never fires
     them: scripts run them with `ctx.call_hook` (§4.5), which also runs any plugin handlers for the name (§4.15).

### Implementation Blueprint

- **Signatures & Types:**
  - `Packages.kt`: `internal val customHookMap = HashMap<Label, Map<String, HookRef>>()` and `/** Custom hooks per
    prototype label. */ val customHooks: Map<Label, Map<String, HookRef>> get() = customHookMap`.
  - `Declarations.kt`: `private fun customHooks(v: Any?, what: String): Map<String, HookRef>`.
  - The `scripts` holder: `fun customHooks(label: Label): Map<String, HookRef>`, mirroring `hooks(label)`.
- **Algorithmic Steps & Invariants:**
  1. `customHooks`: `None` → empty. Otherwise a dict; each key lowercased must match `[a-z][a-z0-9_]*` ("'$k' is not a
     valid hook name") and must not be any `HookCatalog.specs` script name ("'$k' is a catalog hook; pass it as $k=...
     instead"); each value goes through `hookRef(fn, "$what[\"$k\"]")`, so it must be a top-level function.
  2. `room()`, `mob()` and `item()` read `d["hooks"]` and store the map under the declaration's label in
     `ctx.pkg.customHookMap` (only when not empty). Add `hooks` to `ROOM_SPEC`, `MOB_SPEC` and `ITEM_SPEC` with the doc
     "Custom hooks, by name, run with ctx.call_hook."
  3. Invariant: custom hooks don't change `hasHook` or any catalog dispatch.
  4. CHANGELOG bullet. Regenerate the API files (expect the three `hooks` parameters).

### Test & Documentation Requirements

- `CustomHooksTest.kt`: `` `a mob declares custom hooks` `` (the host's `customHooks(label)` has the name); `` `custom
  hook names are validated` `` (`"Bad-Name"`); `` `catalog names are refused` `` (`"on_use"`, message suggests the
  keyword); `` `values must be top-level functions` ``; rooms and items too.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `ApiDiffTest`.
- [] `./gradlew :script:test --tests '*CustomHooksTest*'` passes.

---

## Phase 4: `ctx.call_hook` and the core handler chain

### Objective & Scope

- **In Scope:** the verb; argument and result conversion; the core table of plugin handlers (empty until Phase 5);
  nesting limit.
- **Out of Scope:** the plugin registry (Phase 5).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Host.kt`: `invokeRef` (line ~280, the timer pattern: `fn(ctx, **args)`), `call`
    (line ~607, the nested-call stack and step sharing), `chargeTo` (line ~251).
  - `script/src/main/kotlin/gups/script/Values.kt`: `toGmcp` (M22), `fromAttr`.
  - `script/src/main/kotlin/gups/script/Ctx.kt`: `verb()`, `view(frame, e)` / `v(e)`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/World.kt` and `core/src/main/kotlin/gups/core/world/Hooks.kt`
  - `script/src/main/kotlin/gups/script/Values.kt`
  - `script/src/main/kotlin/gups/script/Host.kt`
  - `script/src/main/kotlin/gups/script/Ctx.kt`
  - Tests: `script/src/test/kotlin/gups/script/CustomHooksTest.kt`,
    `script/src/test/kotlin/gups/script/GmcpValuesTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API files
- **Do Not Touch:** `plugin-api/`, `net/`, `harness/`.

### Specification & Decision Record Changes

Edit `docs/SPEC.md` §4.5:

1. **Verb table**: add a row after the State row whose group is `Hooks` and whose verbs cell is ``` `call_hook(entity,
   name, **args)` ```.
2. **Verb semantics**, add a bullet after **Attributes**:
   - **`call_hook`:** runs `entity`'s custom hook `name` (§4.4) as `fn(ctx, **args)` with `ctx.self` the entity, in the
     hook's own package, then every plugin handler for `name` (§4.15), and returns the result: a fresh copy of `None`,
     ints, strings, bools, lists and dicts. Arguments may also hold actor, item and room views, which the hook receives
     as its own views (`None` if the entity left the world). It returns `None` when the entity is gone, nothing handles
     the name, the package is quarantined or the hook fails; a failing hook is recorded as usual and doesn't fail the
     caller. A hook that returns a view fails. More than 8 nested `call_hook`s is an error.

### Implementation Blueprint

- **Signatures & Types:**

  `Hooks.kt` (core):

  ```kotlin
  /** A plain value standing for an entity in custom hook arguments. */
  data class EntityRef(val id: EntityId)

  /** One plugin handler for a custom hook: (entity, args, result so far) -> new result. */
  fun interface CustomHookHandler {
    fun handle(world: World, entity: Entity, args: Map<String, Any?>, result: Any?): Any?
  }
  ```

  `ScriptHost` (core, `Hooks.kt`), with a default so `ScriptHost.NONE` and test hosts compile:

  ```kotlin
  /**
   * Runs [owner]'s custom hook [name] with [args] (plain values and [EntityRef]s) and returns its
   * result as plain values, or null when it has none, is quarantined or fails.
   */
  fun invokeCustom(world: World, owner: Entity, name: String, args: Map<String, Any?>): Any? = null
  ```

  `World.kt`: `/** Plugin handlers per custom hook name, in plugin-id order. */ val pluginHooks: MutableMap<String,
  MutableList<CustomHookHandler>> = LinkedHashMap()` and

  ```kotlin
  /** Runs [entity]'s custom hook [name], then the plugin handlers for it, and returns the result. */
  fun World.callHook(entity: Entity, name: String, args: Map<String, Any?>): Any?
  ```

  `Values.kt` (script): split M22's `toGmcp` into `internal fun toPlain(v: Any?, what: String, limits: ScriptLimits,
  views: Boolean = false): Any?` (no size cap; with `views = true`, an actor, item or room view becomes
  `EntityRef(it.id)`) and `toGmcp` = `toPlain` plus the 16 KB check, unchanged in behavior. Add `internal fun
  fromPlain(v: Any?, mu: Mutability?, frame: Frame?): Any` (null → `None`, `Long` → `StarlarkInt`, `List` → list, `Map`
  → dict, `EntityRef` → `view(frame, entity)` or `None` if gone, mirroring `fromAttr`).

- **Algorithmic Steps & Invariants:**

  1. `World.callHook`: `if (entity.id !in entities) return null`; `var result = scripts.invokeCustom(this, entity, name,
     args)`; then for each handler in `pluginHooks[name].orEmpty()`, `result = handler.handle(this, entity, args,
     result)` (Phase 5 wraps each handler in its own `guard`). Return `result`. (Use whatever field holds the
     `ScriptHost` on `World`.)
  2. `StarlarkHost.invokeCustom`: `val ref = scripts.customHooks(owner.protoLabel)[name] ?: return null`. Build a
     `Site(owner.protoLabel.pkg, owner.protoLabel, "hook $name", vetoable = false, owner.id)` like `invokeHook`, then
     `call(world, site, ref, { Ctx(it, owner, HookContext(), CtxExtras()) }, kwargs = { f -> args.mapValues {
     fromPlain(it.value, f.thread.mutability(), f) as Any } })`. Use the empty `HookContext` constructor that timers
     use, so `ctx.args` is `None`. On `CallResult.Ok`, convert the value with `toPlain(value, "hook $name return value",
     limits)` (no views); a conversion failure is reported like a hook failure (use `report(...)`) and gives null.
     `Skipped`/`Failed` give null.
  3. `Ctx.callHook`: `@StarlarkMethod(name = "call_hook", extraKeywords = Param(name = "args"), useStarlarkThread =
     true, …)` taking `entity` and `name`. In `verb("call_hook", name)`: resolve `entity` as an actor, item or room view
     (a stale view gives `None`, like a gone entity); validate `name` against `[a-z][a-z0-9_]*`; convert each kwarg with
     `toPlain(..., views = true)`; enforce depth with a host counter (`customDepth`, incremented around the call in a
     `try/finally`; over 8 → `fail("call_hook is nested too deeply")`); call `world.callHook`; return `fromPlain(result,
     thread.mutability(), frame)`.
  4. Kernel verb: no `Ownership.kt` line.
  5. CHANGELOG bullet. Regenerate the API files (expect `ctx.call_hook`).

### Test & Documentation Requirements

- `CustomHooksTest.kt` (with `ScriptFixture`; a zone mob with `hooks = {"price": _price}` and a room hook calling
  `ctx.call_hook(mob, "price", item = ctx.item, base = 10)`):
  - `` `call_hook runs the entity's hook with keyword arguments and returns its result` ``.
  - `` `views passed as arguments arrive as views` `` (the hook reads `item.value`).
  - `` `the hook runs in its own package` `` (it writes an attribute; the key is in the mob's package namespace).
  - `` `missing hooks, gone entities and failing hooks return None` `` (and the failure is recorded as a diagnostic).
  - `` `returning a view fails the hook` ``.
  - `` `the result is a mutable copy` ``.
  - `` `nesting deeper than 8 is an error` `` (a hook that calls itself).
  - `` `steps are shared with the caller` `` (a hook loop that spends the budget fails, as for other nested calls).
- `GmcpValuesTest.kt`: existing `toGmcp` tests still pass; add `toPlain` with `views = true`.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `ApiDiffTest`.
- [] `./gradlew :script:test --tests '*CustomHooksTest*'` passes.

---

## Phase 5: Plugin hook handlers

### Objective & Scope

- **In Scope:** `PluginRegistry.addHook`, `PluginHookCall`, installing handlers into `World.pluginHooks`, the
  `plugins.html` listing, `docs/PLUGINS.md`.
- **Out of Scope:** a facade `callHook`.

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`: the M21 GMCP input install (handlers wrapped in
    `guard`, `GmcpWorldFacade`), `MEMBER` regex, `describe` and `PluginGmcpInfo`.
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`: `RoomFacade`, `ActorFacade`, `ItemFacade`, and M21's
    plain-value check in `WorldFacade.gmcp`.
  - `cli/src/main/kotlin/gups/cli/DocsRenderer.kt` (the plugin GMCP table, around line 574) and
    `cli/src/main/kotlin/gups/cli/Commands.kt` (line ~803).
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt` (shared plain-value check, entity wrapping)
  - `cli/src/main/kotlin/gups/cli/DocsRenderer.kt`, `cli/src/main/kotlin/gups/cli/Commands.kt`
  - Tests: new `plugin-api/src/test/kotlin/gups/pluginapi/HookHandlerTest.kt`, `PluginLoaderTest.kt`,
    `ApiSurfaceTest.kt`; the `plugins.html` golden
  - `docs/SPEC.md`, `docs/PLUGINS.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `script/` main code, `core/` main code.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§4.15 "`PluginRegistry`" bullet**: add "custom hook handlers (`addHook`)".
2. **§4.15**, after the GMCP input bullet (M21), add:
   - **Custom hook handlers:** `addHook(name, doc, handle)` joins the chain for a custom hook name (§4.4): after the
     entity's own hook, `ctx.call_hook` runs every plugin's handler for the name in plugin-id order. A handler gets a
     read-only `PluginGmcpWorld`, the entity, the arguments and the result so far, all as plain values (entities as
     entity views), and returns the new result as plain values. A handler that throws records a `plugin` diagnostic and
     leaves the result unchanged. Names follow the custom hook rules; a plugin registers a name once.
3. **§4.15 "Failures at run time"**: add "custom hook handlers".
4. **§4.16 `plugins.html` row**: add "custom hook handlers" to the listed items.

### Implementation Blueprint

- **Signatures & Types** (`PluginApi.kt`):

  ```kotlin
  /** One run of a plugin's handler for a custom hook. */
  interface PluginHookCall {
    val world: PluginGmcpWorld
    /** The room, actor or item whose hook is running. */
    val entity: PluginEntity
    val name: String
    /** Arguments as plain values; entities appear as PluginRoom, PluginActor or PluginItem. */
    val args: Map<String, Any?>
    /** The result so far: the entity's own hook's, or the previous handler's. */
    val result: Any?
  }

  /** Joins the handler chain of custom hook [name]; returns the new result as plain values. */
  fun addHook(name: String, doc: String, handle: (PluginHookCall) -> Any?)
  ```

  `PluginHost.kt`: `data class HookReg(val name: String, val doc: String, val handle: (PluginHookCall) -> Any?)`; `data
  class PluginHookInfo(val name: String, val doc: String)` in the plugin description; `PluginHookDocSpec` in
  `DocsRenderer.kt`.

- **Algorithmic Steps & Invariants:**

  1. `addHook`: `require(MEMBER.matches(name))`; refuse catalog names (core `HookName.entries.map { it.scriptName }`)
     with "'$name' is a catalog hook"; refuse a second registration of the same name by this plugin.
  2. Install, after the GMCP input loop, in plugin-id order (the order plugins are already installed in):
     `world.pluginHooks.getOrPut(h.name) { mutableListOf() } += CustomHookHandler { w, entity, args, result -> var out =
     result; guard(w, p.id, "hook '${h.name}'") { out = checkPlain(h.handle(call(w, entity, args, result))) }; out }`.
     `call(...)` builds a private `PluginHookCall` with `world = GmcpWorldFacade(facade(w, p.id))`, `entity` wrapped by
     kind (`Room` → `RoomFacade`, `Actor` → `ActorFacade`, `Item` → `ItemFacade`), and `args` with every `EntityRef`
     (nested too) replaced by the wrapped entity (or null if gone). `checkPlain` is M21's plain-value check, moved to a
     shared function if it isn't one already; a non-plain result throws inside `guard`, so the result stays unchanged.
     Plugin results that contain entity views are not plain and are refused the same way.
  3. If a reload or plugin swap clears `pluginGmcpIn`, clear and refill `pluginHooks` the same way.
  4. Docs: `describe` lists hook handlers sorted by name; `DocsRenderer` adds a `<h3>Custom hook handlers</h3>` table
     (`Hook`, `Doc`) after the GMCP table, mirroring its markup.
  5. `docs/PLUGINS.md`: a `### Custom hook handlers` section with a short example (a faction plugin raising `shop_price`
     results for enemies) and the rules above. Keep code blocks out of lists.
  6. CHANGELOG bullets; regenerate the docs goldens (guardrail 4; the script API files should not change).

### Test & Documentation Requirements

- `HookHandlerTest.kt` (plugin-api `Fixture` plus a script package, or a core-only world whose `ScriptHost` returns a
  fixed result from `invokeCustom`):
  - `` `handlers run after the entity's hook, in plugin-id order, each seeing the previous result` ``.
  - `` `a handler runs for an entity without its own hook` `` (result starts as null).
  - `` `entity arguments arrive as plugin views` ``.
  - `` `a throwing handler records a diagnostic and keeps the result` ``.
  - `` `a non-plain result is refused and keeps the result` ``.
  - `` `the handler's world can't change anything` `` (cast to `PluginWorld` fails).
- `PluginLoaderTest.kt`: invalid names, catalog names and duplicate names are refused; two plugins may register the same
  name.
- `ApiSurfaceTest.kt`: add `PluginHookCall::class`.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `./gradlew :plugin-api:test --tests '*HookHandlerTest*'` passes.

---

## Phase 6: Tutorial and finalization

### Objective & Scope

- **In Scope:** a short custom-hooks section in template tutorial 06; the §7.4 row.
- **Out of Scope:** shops (M24). The `### Shops` entry stays open until M24.

### Context Working Set

- **Files to Modify / Create:** `template-world/docs/tutorials/06_hooks_and_interactivity.md`, `docs/SPEC.md`,
  `docs/CHANGELOG.md`.
- **Do Not Touch:** engine code.

### Specification & Decision Record Changes

Edit `docs/SPEC.md` §7.4: add the row below after the last milestone row.

```markdown
| **M23**   | Item value and custom hooks: `item(value=)`, `hooks = {...}` on rooms, mobs and items, `ctx.call_hook` with plain-value arguments and results, the plugin handler chain (`addHook`, `PluginHookCall`), `cha` in the template schema (§3.3, §3.9, §4.2, §4.4, §4.5, §4.15, §4.16). |
```

### Implementation Blueprint

1. Tutorial 06: add a section "Custom hooks" after the catalog material. Show a mob with `hooks = {"greeting":
   _greeting}` and a room command that calls `ctx.call_hook(mob, "greeting", visitor = ctx.actor)` and sends the
   returned text. Explain: the engine never fires custom hooks; arguments may include views; results may not; `None`
   when missing or failing; plugins can add handlers. Keep code blocks out of nested lists (see `AGENTS.md`).
2. Run `./gradlew markdownFormat`.

### Test & Documentation Requirements

- None beyond the full suite.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `markdownCheck` and the template world's tests.
- [] `grep -n "M23" docs/SPEC.md` shows the §7.4 row.
