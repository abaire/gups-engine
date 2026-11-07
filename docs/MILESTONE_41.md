# Milestone 41: Feature-Sized Plugins

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, heading `### Feature modules and the plugin interface` (under
  "Plugins").
- **Design Summary:**
  1. **Built-in feature modules stay internal.** `combat`, `track`, `skills`, `leveling` and `standing` keep their
     `Feature` enum and guards inside `core`. They are not rebuilt behind the plugin interface: they reach into `World`
     internals (the combat round in `runPulse`, track slots at room construction, XP loss in death handling,
     `Char.Status` fields), and §4.15 forbids `core`/`script` types in plugin signatures. Instead, plugins gain the four
     things a feature-sized plugin (hunger, crafting, a weather system) lacks today.
  2. **Periodic work.** `r.every(pulses, doc) { call -> }`, `pulses >= 8`. Runs on every pulse where `pulse % pulses ==
     0`, after the world-tick block of `runPulse` and before perceptions are delivered, in plugin-id then registration
     order. The call carries the `PluginWorld` and the simulated, in-world actors sorted by id.
  3. **Engine event listeners.** `r.onEvent(name, doc) { call -> }` for the global-hookable events `on_level_change`,
     `on_encumbrance_change`, `on_group_change`, `on_owner_change`, `on_disengage`, and the newly global `on_death`.
     Listeners run after the scripts' global hooks for the same event, in plugin-id then registration order, with a full
     `PluginWorld`. `on_death` becomes global-hookable for scripts too.
  4. **Plugin-provided feature ids.** `GupsPlugin.providedFeatures`. `ctx.has_feature(name)` answers for them;
     `requiredFeatures` may name them; loading resolves dependencies with a static skip cascade before `register` and
     the same cascade after it. A well-formed unknown name now makes `has_feature` return `False`.
  5. **Load-time refusal.** `PluginRegistry` exposes `schema` and `features`, and `r.refuse(reason)` skips the plugin
     like any other failed load check (and cascades to its dependents).
  6. **Sample.** A new `samples/hunger-plugin` demonstrates all four. The weather sample is not touched.
- **Specification Impact:** §3.10 (Feature Modules: plugin-provided features, why built-ins stay internal), §4.4 (hook
  catalog: `on_death` may be global), §4.5 (`has_feature`), §4.9 (Global hooks), §4.15 (Plugins: registry, `GupsPlugin`,
  loading, seams, sample plugins). `docs/PLUGINS.md`, `docs/CHANGELOG.md`, `docs/api/api-1.json`.
- **Decision Record Updates:** None. (`docs/DECISIONS.md` has no entry about feature modules.)

### Ground rules for every phase

1. **Concurrency.** Start this milestone only once Milestone 39 (Standing) and Milestone 40 are merged. Other work may
   also have edited `PluginApi.kt`, `PluginHost.kt` and `CHANGELOG.md` since this document was written: always re-read
   the current file and locate code by symbol name, never by the line numbers quoted here.
2. **TDD.** Write the tests named in each phase first, run them and see them fail, then implement.
3. **Spec edits are pre-approved.** The SPEC text given in each phase is approved; apply it directly. Do not add
   `DECISIONS.md` entries for anything this document settles. Log a `DECISIONS.md` entry only for a genuinely new gap
   you have to fill.
4. **No spec citations in code comments** (see `AGENTS.md`).
5. **`PluginApi.VERSION` stays 1** (the engine is unreleased).
6. **New `PluginRegistry` and `GupsPlugin` members get default implementations in the interface** (as `addCategory`
   does), because the sample plugins' tests and `plugin-api` tests contain hand-written fakes of `PluginRegistry` and
   `GupsPlugin` that must keep compiling. `PluginRegistryImpl` overrides every one of them.
7. **API files.** In any phase that changes the script API, regenerate before the phase's final `./gradlew check` if a
   golden or API test fails:
   - Run `./gradlew :cli:test -Dgups.updateGolden=true`. This rewrites `cli/src/test/resources/api.golden.json` and the
     `cli/src/test/resources/docs-golden/` files.
   - Run `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout. This rewrites `docs/api/api-1.json`.
   - Read the diff. Only the symbols that phase names may change. `ApiDiffTest` must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing entry.
8. **Gradle** runs outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation.

---

## Phase 1: Global `on_death` for Scripts

### Objective & Scope

- **In Scope:**
  - `global_hook("on_death", fn)` becomes legal for privileged (lib and rules) packages.
  - The engine fires global `on_death` once per death, for players and mobs, with `ctx.self` the victim, `ctx.actor` the
    killer (or `None`) and `ctx.target` the victim.
  - `dispatchGlobal` gains an explicit subject actor so a global hook's `ctx.self` no longer has to be `ctx.actor`.
- **Out of Scope (do not implement these in this phase; later phases or no phase own them):** any plugin-facing listener
  (Phase 5), any change to `PluginRegistry`, `GupsPlugin` or `PluginHost`, and any new global-hookable name besides
  `on_death`.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Combat.kt`: the death function containing `dispatch(HookName.ON_DEATH, victim,
    room, HookContext(actor = killer, target = victim))`, and the `dispatchGlobal(HookName.ON_DISENGAGE, ctx)` call
    earlier in the file.
  - `core/src/main/kotlin/gups/core/world/Leveling.kt`, `Groups.kt`, `Owners.kt`, `Items.kt`: the other `dispatchGlobal`
    call sites.
  - `script/src/test/kotlin/gups/script/GlobalHookTest.kt`: existing global-hook tests to mirror.
  - SPEC §4.4 (hook catalog row for `on_death`), §4.9 (**Global hooks** paragraph).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Hooks.kt` (`ScriptHost.invokeGlobal`, `World.dispatchGlobal`).
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (fire the global hook on death).
  - `script/src/main/kotlin/gups/script/Host.kt` (`invokeGlobal`).
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`globalHook` name check; `HookSpec` doc for `ON_DEATH`).
  - `script/src/test/kotlin/gups/script/GlobalHookTest.kt`.
  - Every test fake that overrides `invokeGlobal`: at the time of writing
    `core/src/test/kotlin/gups/core/world/Fixture.kt` (`RecordingHost`),
    `script/src/test/kotlin/gups/script/HookCtxFieldsTest.kt` (`Spy`), and possibly
    `script/src/test/kotlin/gups/script/LevelChangeHookTest.kt` and
    `persist/src/test/kotlin/gups/persist/PersistenceTest.kt`. Find them all with `grep -rn 'invokeGlobal'
    --include='*.kt' . | grep -v /build/` and update each to the new signature.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, API files per ground rule 7.
- **Do Not Touch:** `plugin-api/**`, `samples/**`, every other `HookSpec`.

### Specification & Decision Record Changes

- §4.9 **Global hooks**: replace the sentence listing the hooks that can be global with: "`on_level_change`,
  `on_encumbrance_change`, `on_group_change`, `on_owner_change`, `on_disengage` and `on_death` are the hooks that can be
  global." After the `on_disengage` sentence add: "A global `on_death` fires once for every actor that dies, players and
  mobs, after that death's `on_death` hooks on the victim, the room and observers, and before the corpse is made and XP
  is awarded; `ctx.self` and `ctx.target` are the victim and `ctx.actor` the killer, or `None`." Add `on_death` to the
  list of kernel hooks that fire in every world (`on_encumbrance_change`, `on_group_change` and `on_owner_change` belong
  to the kernel...).
- §4.4 hook catalog: the `on_death` row's "Attach to" column becomes `Mob, Room, global (§4.9)`.
- `CHANGELOG.md` under `## api_version 1 (unreleased)`, a new `### Feature-sized plugins` section (later phases append
  to it): "`global_hook("on_death", fn)` fires once per death, after the victim's, room's and observers' `on_death`
  hooks and before the corpse and XP award, with `ctx.self` the victim and `ctx.actor` the killer."

### Implementation Blueprint

- **Signatures & Types:**
  - `ScriptHost.invokeGlobal(world: World, hook: HookName, subject: Actor, ctx: HookContext)` (default body stays
    `Unit`).
  - `internal fun World.dispatchGlobal(hook: HookName, ctx: HookContext, subject: Actor? = ctx.actor)`. When `subject`
    is null, return without dispatching.
- **Algorithmic Steps & Invariants:**
  1. In `Host.invokeGlobal`, drop `val actor = ctx.actor ?: return` and build the `Ctx` with `subject` as its self actor
     (`Ctx(it, subject, ctx, CtxExtras())`); use `subject.id` in the `Site`. Keep the `leveling` gate.
  2. Every existing `dispatchGlobal` call keeps its behavior through the default `subject = ctx.actor`.
  3. In `Combat.kt`, immediately after the `dispatch(HookName.ON_DEATH, ...)` call, add
     `dispatchGlobal(HookName.ON_DEATH, HookContext(actor = killer, target = victim), subject = victim)`. It must come
     before XP is priced (`awards`) and before the corpse is created, so a global hook sees the victim's belongings and
     level as at death.
  4. `on_death` is a kernel hook: no feature gate.
  5. In `Declarations.globalHook`, accept `"on_death"` and update the error message to list all six names in this order:
     `on_level_change, on_encumbrance_change, on_group_change, on_owner_change, on_disengage and on_death`.
  6. Update the `ON_DEATH` `HookSpec` doc to: "Fires when an actor dies; also a global hook (lib and rules packages)."

### Test & Documentation Requirements

- `GlobalHookTest.kt`, new tests:
  - A rules-package `global_hook("on_death", fn)` fires once when a mob is killed by a player: `ctx.self` is the mob,
    `ctx.actor` the player, `ctx.target` the mob.
  - It fires for a player death too, and with `ctx.actor` `None` when no killer exists (death through `condition` with
    no damager, or a verb kill with no actor; use whichever an existing test already sets up).
  - Ordering: a mob `on_death` hook and the global hook both append to a list attribute on the room; the mob's entry
    comes first.
  - It fires in a world with `features = []`.
  - A zone package registering it is still refused (privileged-only rule unchanged).
  - The refusal message for an illegal name lists the six names.
- Existing global-hook tests still pass unchanged.

### Exit Conditions

- [] `./gradlew :core:test :script:test` passes.
- [] `./gradlew check` passes with zero warnings.
- [] `docs/api/api-1.json` diff touches only the `on_death` hook entry (and nothing else) if it changed at all.

---

## Phase 2: Provided Features, Dependency Cascade and `refuse` in the Loader

### Objective & Scope

- **In Scope:**
  - `GupsPlugin.providedFeatures`.
  - `requiredFeatures` may name engine features or features provided by other plugins.
  - Static validation and the skip cascade before `register`; the same cascade after `register`.
  - `PluginRegistry.schema`, `PluginRegistry.features`, `PluginRegistry.refuse(reason)` and the `PluginRefused`
    exception.
  - `PluginHost` exposes the final set of provided features (for Phase 3).
- **Out of Scope (do not implement these in this phase):** `ctx.has_feature` and any `World` field (Phase 3); `every`
  (Phase 4); `onEvent` (Phase 5); version ranges between plugins (not planned); any change to how GMCP package overlap
  between plugins is checked.

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`: `PluginLoader.load`, `missingFeature`,
    `PluginLoader.register`, `PluginRegistryImpl` constructor, `LoadedPlugin`, `PluginHost.warnings`.
  - `script/src/main/kotlin/gups/script/WorldModule.kt` (or wherever `WorldModule.PLUGIN_ID` and `reservedPluginIds`
    live): reuse them for name validation.
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt`: `PluginSchema`, and how `PluginWorld.schema` is built in
    `host/Facade.kt` from a `WorldSchema` (reuse that conversion).
  - `plugin-api/src/testFixtures/kotlin/gups/pluginapi/testplugins/TestPlugins.kt` and `PluginLoaderTest.kt`: how test
    plugins and jars are built.
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`.
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`.
  - `plugin-api/src/test/kotlin/gups/pluginapi/PluginLoaderTest.kt` (and `TestPlugins.kt` if new fixture plugins are
    needed).
  - `plugin-api/src/test/kotlin/gups/pluginapi/ApiSurfaceTest.kt` (add `PluginRefused`).
  - `docs/SPEC.md`.
- **Do Not Touch:** `core/**`, `script/**`, `samples/**`.

### Specification & Decision Record Changes

- §4.15 **Architecture and interfaces**, `GupsPlugin` bullet: after "required feature flags," insert "the features it
  provides (`providedFeatures`),".
- §4.15 **Loading → Checks**: replace "and satisfied `requiredFeatures`" with "and well-formed `providedFeatures`". Then
  add a new bullet after **Checks**:
  - "**Provided features and dependencies:** a provided feature is `[a-z][a-z0-9_]*` and may not equal an engine feature
    id (enabled or not) or a reserved id. When two plugins that passed their checks provide the same feature, the one
    with the larger id is skipped. `requiredFeatures` may name engine features and provided features. A plugin is
    skipped while any feature it requires is neither an enabled engine feature nor provided by a plugin still loading;
    this repeats until nothing more is skipped. `register` then runs in id order, and the same cascade runs once more
    over the plugins whose `register` succeeded, so a plugin whose provider refused or threw is skipped and its
    registrations are discarded. `register` runs once per plugin: a plugin skipped for clashing with an earlier plugin
    stays skipped even if the cascade later drops that earlier plugin. Each skip warns `plugin '<id>': needs the
    '<feature>' feature`, naming the first missing feature alphabetically."
- §4.15 **Plugin capabilities and lifecycle**, new bullet after **Failures at run time**:
  - "**World checks:** `register` can read `r.schema` (the world's stats, vitals and damage types) and `r.features`
    (enabled engine features and every feature provided by a plugin that passed the static checks). A plugin that can't
    run in the world calls `r.refuse(reason)`; it is skipped like a plugin that fails a check (warning `plugin '<id>':
    refused: <reason>`, a `plugin` diagnostic and a `plugin_load` alert), and the cascade then skips its dependents."

### Implementation Blueprint

- **Signatures & Types** (all in `gups.pluginapi`):

```kotlin
interface GupsPlugin {
  // existing members ...

  /**
   * Feature names this plugin provides, `[a-z][a-z0-9_]*`. Scripts test them with
   * `ctx.has_feature` and other plugins may name them in [requiredFeatures].
   */
  val providedFeatures: Set<String>
    get() = emptySet()
}

/** Thrown by [PluginRegistry.refuse]; the loader skips the plugin with [reason]. */
class PluginRefused(val reason: String) : RuntimeException(reason)

interface PluginRegistry {
  // existing members ...

  /** The world's declared stats, vitals and damage types. */
  val schema: PluginSchema
    get() = PluginSchema(emptyList(), emptyList(), emptyList())

  /** Enabled engine features plus every feature provided by a plugin that is loading. */
  val features: Set<String>
    get() = emptySet()

  /** Refuses this world: the plugin is skipped and adds nothing. */
  fun refuse(reason: String): Nothing = throw PluginRefused(reason)
}
```

- Update the `requiredFeatures` KDoc (and the "Lifecycle & Validation Rules" KDoc on `GupsPlugin`) to say it may name
  engine features or features other plugins provide.
- `PluginRegistryImpl` gains constructor parameters `schema: PluginSchema` (converted from the `WorldSchema` it already
  receives) and `allFeatures: Set<String>`, overriding `schema` and `features`.
- `PluginHost` gains `val providedFeatures: Set<String>`: the union of `providedFeatures` of the plugins in its final
  `loaded` list.
- **Algorithmic Steps & Invariants:**
  1. In `PluginLoader.load`, remove the `missingFeature` check from the per-plugin `when` (keep id, version and provider
     checks). Add a check that every `providedFeatures` name matches `WorldModule.PLUGIN_ID`, has `Feature.byId(name) ==
     null`, and is not in `WorldModule.reservedPluginIds`; a failure skips with `provides invalid feature '<name>'`.
  2. After the per-jar loop, pass `found` (and `module.features`) to a new internal function `resolve(found, engine:
     Features, warn): List<...>`:
     1. Walk `found` in id order, tracking `providedBy: Map<String, String>`. A plugin providing a name already in
        `providedBy` is skipped: `plugin '<id>': feature '<name>' is already provided by plugin '<other>'`.
     2. Loop: `available = engine.ids + providedFeatures of survivors`. Skip every survivor with a required feature not
        in `available` (`needs the '<first missing, sorted>' feature`). Repeat until a pass skips nothing.
     3. Close the class loader of every skipped plugin.
  3. `register(...)` receives the survivors. Every `PluginRegistryImpl` gets the same `allFeatures` = engine ids ∪
     survivors' provided features.
  4. In `register`, catch `PluginRefused` separately: warning `plugin '<id>': refused: <reason>`. Other throwables keep
     the existing `register failed:` text.
  5. After the register loop, run step 2.2 again over the `loaded` list (only the registry's provided features from
     succeeded plugins count). Drop the skipped `LoadedPlugin`s from the list before building `PluginHost`, and close
     their loaders. Nothing has touched a `World` yet, so discarding is just dropping them from the list.
  6. **Clashes are not revisited.** `register` checks commands, GMCP packages, traits and the single creation provider
     against the plugins registered before it. A plugin skipped for clashing with an earlier plugin stays skipped even
     if the post-register cascade later drops that earlier plugin. `register` runs at most once per plugin instance;
     never re-run it.
  7. Warnings keep their current form, so diagnostics and `plugin_load` alerts follow from the existing path that turns
     `PluginHost.warnings` into them. Verify that path covers the new warnings; do not add a second one.
  8. `missingFeature` becomes the helper used by step 2.2 (sorted, first missing).

### Test & Documentation Requirements

- `PluginLoaderTest.kt`, new tests (build fixture plugins the way existing tests do):
  - A plugin requiring an engine feature that is off is still skipped with the existing message.
  - `b` requires `hunger`, `a` provides it: both load, `host.providedFeatures == setOf("hunger")`.
  - `b` requires `hunger`, nobody provides it: `b` skipped with `needs the 'hunger' feature`.
  - A chain `c` → `b` → `a` where `a` fails its checksum: all three skipped, each with its own warning.
  - Two plugins providing `hunger`: the larger id is skipped with the "already provided" message.
  - Provided name `combat`, `ctx`, or `Bad-Name`: skipped with `provides invalid feature`.
  - `a` calls `r.refuse("no satiety vital")`: skipped with `plugin 'a': refused: no satiety vital`, and its commands and
    builtins are absent; dependent `b` is then skipped and its registrations discarded.
  - `r.schema` lists the world's vitals; `r.features` contains the enabled engine features and `hunger`.
  - Clash then cascade: `a` requires `hunger` provided by `z`; `a` and `b` both register command `eat`; `z` refuses. `b`
    is skipped for the clash, `a` is skipped by the cascade, and `b` stays skipped.
- `ApiSurfaceTest`: add `PluginRefused::class`.

### Exit Conditions

- [] `./gradlew :plugin-api:test` passes.
- [] `./gradlew check` passes with zero warnings (the sample plugins' fakes still compile).
- [] SPEC §4.15 carries the text above.

---

## Phase 3: `ctx.has_feature` Knows Plugin Features

### Objective & Scope

- **In Scope:** `World.pluginFeatures`; `PluginHost.install` fills it; `ctx.has_feature` returns true for enabled engine
  features and for provided plugin features, false for any other well-formed name, and still errors on a malformed name.
- **Out of Scope (do not implement these in this phase):** recording plugin features in `meta.world_features`; listing
  them in `gups docs --world`; any change to `FeatureOwnership` or engine `Feature` guards.

### Context Working Set

- **Read / Reference Only:** `script/src/main/kotlin/gups/script/Ctx.kt` (`hasFeature`);
  `script/src/test/kotlin/gups/script/FeatureScriptTest.kt`; `PluginHost.install`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/World.kt` (new field next to `pluginHooks`).
  - `script/src/main/kotlin/gups/script/Ctx.kt`.
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt` (`install`).
  - `script/src/test/kotlin/gups/script/FeatureScriptTest.kt`, a new test in
    `plugin-api/src/test/.../HookHandlerTest.kt` or a new `ProvidedFeatureTest.kt` in the same package.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, API files per ground rule 7.
- **Do Not Touch:** `persist/**`, `samples/**`.

### Specification & Decision Record Changes

- §4.5, the **Features** bullet: replace "it returns whether the world has the feature `name` on (`"combat"`, `"track"`,
  `"skills"` or `"leveling"`), and any other name raises an error." with "it returns whether the world has the feature
  `name`: an engine feature that is on (`"combat"`, `"track"`, `"skills"`, `"leveling"` or `"standing"`), or a feature a
  loaded plugin provides (§4.15). Any other name of the form `[a-z][a-z0-9_]*` returns `False`; a malformed name raises
  an error."
- §3.10: the bullet "Scripts can test for a feature with `ctx.has_feature(name)`..." gains: "Plugins can provide
  features of their own (§4.15), which `has_feature` reports the same way." Then add a closing bullet: "**Built-in
  features are not plugins:** they reach into the world's own state and phases, so they stay inside the engine; a plugin
  adds a feature through the plugin interface instead (§4.15). A plugin's feature is not recorded in
  `meta.world_features`; adding or removing a plugin needs only a restart."
- `CHANGELOG.md`, `### Feature-sized plugins`: "`ctx.has_feature(name)` also reports features provided by plugins, and
  returns `False` (instead of raising an error) for a well-formed name that is not a known feature." Give a before/after
  snippet: `has_feature("hunger")` raised before, returns `False` now when no plugin provides it.

### Implementation Blueprint

- **Signatures & Types:** in `World`: `/** Features provided by loaded plugins. */ var pluginFeatures: Set<String> =
  emptySet()`.
- **Algorithmic Steps & Invariants:**
  1. `PluginHost.install(world)` sets `world.pluginFeatures = providedFeatures` (Phase 2's property).
  2. `Ctx.hasFeature`: read the string; if it doesn't match `[a-z][a-z0-9_]*`, fail with `has_feature(): '<id>' is not a
     feature name`. Otherwise return `Feature.byId(id)?.let { it in world.features } ?: (id in world.pluginFeatures)`.
  3. Update the `@StarlarkMethod` doc to "Whether the world has the named feature: an engine feature that is on, or one
     a loaded plugin provides."

### Test & Documentation Requirements

- `FeatureScriptTest.kt`: `has_feature("hunger")` is `False` in a plain world; `has_feature("Bad-Name")` errors;
  `has_feature("standing")` follows the enabled set.
- plugin-api test: a world built with a fixture plugin providing `hunger` answers `True` to `ctx.has_feature("hunger")`
  from a script hook (mirror how `HookHandlerTest` builds a world with plugins).

### Exit Conditions

- [] `./gradlew :script:test :plugin-api:test` passes.
- [] `./gradlew check` passes with zero warnings.
- [] The API baseline diff, if any, touches only `ctx.has_feature`'s doc.

---

## Phase 4: Periodic Work (`r.every`)

### Objective & Scope

- **In Scope:** `PluginRegistry.every`, `PluginTickCall`, a `core` seam in `World`, and its call in `runPulse`.
- **Out of Scope (do not implement these in this phase):** one-shot timers or timers that survive a restart; per-actor
  callbacks; phases other than the one described; any change to `runWorldTick`.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/World.kt`: `runPulse`, `runWorldTick`, `isSimulated`, `guarded`,
    `ownedBehaviorAdjusters` (seam pattern).
  - `core/src/main/kotlin/gups/core/TimeConstants.kt` (`COMBAT_ROUND_PULSES`, `WORLD_TICK_PULSES`).
  - `PluginHost.kt`: `install`, `started`, `guard`, `facade(world, id)`, `ActorFacade`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/World.kt` (seam and call).
  - New `core/src/main/kotlin/gups/core/world/PluginSeams.kt` only if `World.kt` has no natural place for the `fun
    interface`; otherwise declare it beside `OwnedBehaviorAdjuster` in its existing file.
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`, `host/PluginHost.kt`.
  - New `plugin-api/src/test/kotlin/gups/pluginapi/PeriodicWorkTest.kt`; `ApiSurfaceTest.kt`.
  - `docs/SPEC.md`.
- **Do Not Touch:** `script/**` (except if the module-graph check requires nothing there; it shouldn't), `samples/**`.

### Specification & Decision Record Changes

- §4.15 **Architecture and interfaces**, `PluginRegistry` bullet: add "periodic work (`every`)," after "world start
  callbacks (`onWorldStarted`),".
- §4.15 **Plugin capabilities and lifecycle**, new bullet after **Custom hook handlers**:
  - "**Periodic work:** `every(pulses, doc, fn)` runs `fn` on each pulse where `pulse % pulses == 0`; `pulses` is at
    least 8 (one combat round). It runs after the world tick's work (§5.1 phase order) and before perceptions are
    delivered, in plugin-id order then registration order. The call carries a `PluginWorld` and `actors`, every
    simulated actor in the world sorted by id, so dormant mobs are left alone as regen leaves them. A plugin may
    register several. A throw records a `plugin` diagnostic; the next run happens as scheduled."
- §4.15 **Failures at run time**: add "periodic work," to the list.
- §4.15 **Seams in `core` and `script`**: add a bullet "`World.pluginPeriodic`: plugin periodic work, each with its
  period, run by `runPulse`."
- If §5.1 lists the pulse phases in prose, add plugin periodic work after the world tick there too, with the same
  wording.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// core
/** Plugin work that runs every [period] pulses. */
class PeriodicWork(val period: Int, val run: (World, List<Actor>) -> Unit)

// World
/** Plugin periodic work, in plugin id order then registration order. */
var pluginPeriodic: List<PeriodicWork> = emptyList()

// gups.pluginapi
data class PluginTickCall(
  val world: PluginWorld,
  /** Simulated actors in the world, sorted by id. */
  val actors: List<PluginActor>,
)

interface PluginRegistry {
  // ...
  /** Runs [fn] every [pulses] pulses (at least 8), after the world tick's work. */
  fun every(pulses: Int, doc: String, fn: (PluginTickCall) -> Unit) {}
}
```

- **Algorithmic Steps & Invariants:**
  1. `PluginRegistryImpl.every` validates `pulses >= 8` (`require`, message `every(): pulses must be at least 8, got
     <n>`), so a bad value fails `register` and skips the plugin. It stores `(pulses, doc, fn)` in a new list
     `periodic`.
  2. `PluginHost.install` sets `world.pluginPeriodic` to one `PeriodicWork` per entry, in plugin-id then registration
     order. Its `run` wraps the call in `guard(world, p.id, "every(<pulses>)")`, builds `PluginTickCall(facade(w, p.id),
     actors.map { ActorFacade(it, w) })`.
  3. In `World.runPulse`, directly after `if (pulse % WORLD_TICK_PULSES == 0L) runWorldTick()`, add a private
     `runPluginPeriodic()`:
     - Find the entries due this pulse (`pulse % period == 0L`). If none, return without building the actor list.
     - Otherwise build `actorIndex.values.filter { isSimulated(it) && it.inWorld }.sortedBy { it.id }` once and run each
       due entry with it, inside `guarded("plugin periodic work") { ... }`.
     - Then `flushImmediate()`, so output a plugin sent reaches players before perceptions.
  4. The plugin layer catches throws itself (step 2); `guarded` is a second net that must never be the one firing in
     tests.

### Test & Documentation Requirements

- `PeriodicWorkTest.kt` (mirror world/plugin construction in `HookHandlerTest`):
  - `every(8)` runs on pulses 8, 16, 24 and not between; two registrations in one plugin run in registration order;
    plugin `a` runs before plugin `b` on the same pulse.
  - `actors` excludes a mob in a dormant room and includes players, sorted by id.
  - `every(7)` makes `register` fail and the plugin is skipped with a warning.
  - A throwing callback records a `plugin` diagnostic naming `every(8)` and still runs next time.
  - A callback that sends text to a player: the text arrives in that pulse's output.
- `ApiSurfaceTest`: add `PluginTickCall::class`.

### Exit Conditions

- [] `./gradlew :core:test :plugin-api:test` passes.
- [] `./gradlew check` passes with zero warnings, including the module-graph check.
- [] A world with no plugins runs `runPulse` without allocating the actor list (by inspection: early return).

---

## Phase 5: Engine Event Listeners (`r.onEvent`)

### Objective & Scope

- **In Scope:** `PluginRegistry.onEvent`, `PluginEventCall`, a `core` seam, and dispatch after the script global hooks
  for the six global-hookable events.
- **Out of Scope (do not implement these in this phase):** vetoing or changing outcomes from a listener; events other
  than the six; a plugin calling custom hooks (ICEBOX "Plugins calling custom hooks"); script-side pub/sub
  (DESIGN_NEEDED "Global and cross-zone event subscriptions").

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Hooks.kt`: `HookContext`, `dispatchGlobal` (as changed in Phase 1),
    `withCascadeGuard`, `CustomHookHandler`.
  - `core/src/main/kotlin/gups/core/model/Group.kt`: `GroupSnapshot(id, leader, members)`.
  - `PluginHost.kt`: `hookCall`, `wrap`, `plainEntity`, `guard`.
  - SPEC §4.4 rows and `HookSpec.ctxFields` in `Declarations.kt` for the six hooks.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Hooks.kt` (seam type and dispatch), `World.kt` (field).
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`, `host/PluginHost.kt`.
  - New `plugin-api/src/test/kotlin/gups/pluginapi/EventListenerTest.kt`; `ApiSurfaceTest.kt`.
  - `docs/SPEC.md`.
- **Do Not Touch:** `script/**`, `samples/**`.

### Specification & Decision Record Changes

- §4.15 **Architecture and interfaces**, `PluginRegistry` bullet: add "engine event listeners (`onEvent`)," after
  "periodic work (`every`),".
- §4.15 **Plugin capabilities and lifecycle**, new bullet after **Periodic work**:
  - "**Event listeners:** `onEvent(name, doc, fn)` listens to one of the events that can be global hooks (§4.9):
    `on_level_change`, `on_encumbrance_change`, `on_group_change`, `on_owner_change`, `on_disengage` and `on_death`. Any
    other name fails `register`. A listener runs each time the event's global hooks run, after them, in plugin-id then
    registration order, and can't veto or change the event. The call carries a `PluginWorld`, the event name, `actor`
    (the global hook's `ctx.self`) and `fields`, the event's `ctx` fields as plain values: `old_level` and `new_level`;
    `old_encumbrance_level` and `new_encumbrance_level`; `event` and `group` (a map with `id`, `leader` and `members`);
    `target` and `event`; `event`; and `killer` (or null) for `on_death`. Ints are `Long`s and actors `PluginActor`s. A
    listener for an event whose feature is off registers and never runs. A throw records a `plugin` diagnostic."
- §4.9 **Global hooks**: append "Plugin event listeners (§4.15) run after them."
- §4.15 **Failures at run time** and **Seams**: add "event listeners," and a seam bullet "`World.pluginEvents`: plugin
  event listeners by hook name, run by `dispatchGlobal` after the script global hooks."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// core, beside CustomHookHandler
/** A plugin listener for a global-hookable event. */
fun interface EventListener {
  fun handle(world: World, subject: Actor, ctx: HookContext)
}

// World
/** Plugin event listeners per global-hookable hook, in plugin id then registration order. */
val pluginEvents: MutableMap<HookName, MutableList<EventListener>> = LinkedHashMap()

// gups.pluginapi
data class PluginEventCall(
  val world: PluginWorld,
  /** The event: on_level_change, on_encumbrance_change, ... or on_death. */
  val event: String,
  /** The actor the event is about: the global hook's ctx.self. */
  val actor: PluginActor,
  /** The event's ctx fields as plain values. */
  val fields: Map<String, Any?>,
)

interface PluginRegistry {
  // ...
  /** Listens to a global-hookable engine event; runs after the scripts' global hooks. */
  fun onEvent(name: String, doc: String, fn: (PluginEventCall) -> Unit) {}
}
```

- **Algorithmic Steps & Invariants:**
  1. `PluginRegistryImpl.onEvent` validates `name` against the six names (`require`, message `onEvent(): '<name>' is not
     an event; expected one of on_level_change, on_encumbrance_change, on_group_change, on_owner_change, on_disengage,
     on_death`) and stores it.
  2. `World.dispatchGlobal(hook, ctx, subject)`: inside the existing `withCascadeGuard`, after the
     `scripts.invokeGlobal` try/catch, run `pluginEvents[hook]` in order, each in its own try/catch recording a `plugin`
     diagnostic as a backstop. Keep the `leveling` gate semantics: `on_level_change` is only dispatched when `leveling`
     is on, so listeners for it never run otherwise. (Check: `Host.invokeGlobal` gates it in the script layer; add the
     same gate in `dispatchGlobal` before running plugin listeners.)
  3. `PluginHost.install` clears and fills `world.pluginEvents` from every plugin's listeners, mapping the name to
     `HookName` by `scriptName`. Each adapter wraps in `guard(w, p.id, "onEvent '<name>'")` and builds
     `PluginEventCall(facade(w, p.id), name, ActorFacade(subject, w), fieldsOf(w, hook, ctx))`.
  4. `fieldsOf` (private in `PluginHost`) by hook:
     - `ON_LEVEL_CHANGE`: `old_level`, `new_level` as `Long`.
     - `ON_ENCUMBRANCE_CHANGE`: `old_encumbrance_level`, `new_encumbrance_level` as `Long`.
     - `ON_GROUP_CHANGE`: `event`; `group` = `mapOf("id" to id.toLong(), "leader" to ActorFacade(leader), "members" to
       members.map { ActorFacade(it) })`.
     - `ON_OWNER_CHANGE`: `target` (owner actor or null), `event`.
     - `ON_DISENGAGE`: `event`.
     - `ON_DEATH`: `killer` = `ctx.actor` as an actor view, or null.
  5. Listeners get a full `PluginWorld` (they may change the world, as script global hooks may); the cascade guard
     already bounds recursion.
  6. At the global `on_death` point the victim is still in the world (removal and respawn come later), so facade verbs
     such as `setAttr` on `call.actor` succeed: the facade's liveness check is `actor.inWorld`. A test below pins this
     down for a dying mob.

### Test & Documentation Requirements

- `EventListenerTest.kt`:
  - `on_death`: killing a mob runs the listener once with `actor` the mob and `fields["killer"]` the player; it runs
    after a rules-package `global_hook("on_death")` (both append to a shared room attribute; check order).
  - `on_level_change` fields are `Long`s; no call in a world without `leveling`, and registration still succeeds there.
  - `on_group_change` carries `group.leader` and `group.members` as `PluginActor`s.
  - Plugin-id order across two plugins; registration order within one.
  - `onEvent("on_tick", ...)` fails `register` and the plugin is skipped.
  - A throwing listener records a `plugin` diagnostic and the next listener still runs.
  - A listener calling `world.setAttr(actor, "seen", true)` persists on the actor.
  - An `on_death` listener calling `world.setAttr(call.actor, "x", 1L)` on a dying mob succeeds (no diagnostic).
- `ApiSurfaceTest`: add `PluginEventCall::class`.

### Exit Conditions

- [] `./gradlew :core:test :plugin-api:test` passes.
- [] `./gradlew check` passes with zero warnings.

---

## Phase 6: Hunger Sample Plugin, Guide, and Finalization

### Objective & Scope

- **In Scope:** `samples/hunger-plugin` (plugin, service descriptor, README, tests), `settings.gradle.kts`, the guide in
  `docs/PLUGINS.md`, SPEC §4.15 **Sample plugins**, the CHANGELOG plugin note, marking the DESIGN_NEEDED entry done.
- **Out of Scope (do not implement these in this phase):** a multi-chapter tutorial under `docs/tutorials`; any change
  to `samples/weather-plugin`, `heritage-plugin` or `curse-plugin`; adding the hunger plugin to `template-world`'s
  `MODULE.gups`.

### Context Working Set

- **Read / Reference Only:** `samples/curse-plugin/` (layout, `build.gradle.kts`, `CurseWorldTest.kt` for a world test
  loading the built jar), `samples/curse-plugin/README.md` (README shape), `docs/PLUGINS.md` (section style).
- **Files to Modify / Create:**
  - `samples/hunger-plugin/build.gradle.kts` (copy curse's, renaming `curse` → `hunger` and the system property to
    `gups.sample.hungerJar`).
  - `samples/hunger-plugin/src/plugin/META-INF/services/gups.pluginapi.GupsPlugin` (one line:
    `gups.sample.hunger.HungerPlugin`).
  - `samples/hunger-plugin/src/main/kotlin/gups/sample/hunger/HungerPlugin.kt`.
  - `samples/hunger-plugin/src/test/kotlin/gups/sample/hunger/HungerWorldTest.kt`.
  - `samples/hunger-plugin/README.md`.
  - `settings.gradle.kts` (add `"samples:hunger-plugin"`).
  - `docs/PLUGINS.md`, `docs/SPEC.md`, `docs/CHANGELOG.md`, `docs/DESIGN_NEEDED.md`.
- **Do Not Touch:** `core/**`, `script/**`, `plugin-api/src/main/**`, other samples.

### Specification & Decision Record Changes

- §4.15 **Sample plugins**: add a bullet: "**Hunger Plugin
  ([samples/hunger-plugin](../samples/hunger-plugin/README.md)):** a feature-sized plugin. It provides the `hunger`
  feature, refuses worlds without an `hp` vital, raises each simulated player's hunger every world tick with
  `every(120)` and warns them as it grows, resets it from an `on_death` event listener, and adds a `hunger.eat(actor,
  amount)` builtin for item `on_use` hooks."
- §4.15 **The guide**: add "and feature-sized plugins (periodic work, event listeners, provided features and world
  checks) with `HungerPlugin`."

### Implementation Blueprint

- **Plugin** (`HungerPlugin : GupsPlugin`):
  - `id = "hunger"`, `pluginApiVersion = PluginApi.VERSION`, `providedFeatures = setOf("hunger")`, a one-line `doc`.
  - `register(r)`:
    1. If `r.schema.vitals` has no vital named `hp`, `r.refuse("needs an 'hp' vital")`. If `r.schema.damageTypes` is
       empty, `r.refuse("needs a damage type")`.
    2. `r.every(120, "Raises hunger once per world tick.") { call -> ... }`: for each actor in `call.actors` that is a
       player (`PluginActor.isPlayer`), read `hunger` (default `0L`), add 1 capped at 10, write it back with `setAttr`,
       and at 5 and at 10 send "You are getting hungry." / "You are starving." At 10, also `damage` the actor by 1 (use
       the facade's `damage` verb as the weather sample's effect does; pick a damage type from
       `call.world.schema.damageTypes.first()`).
    3. `r.onEvent("on_death", "Death resets hunger.") { call -> call.world.setAttr(call.actor, "hunger", null) }`.
    4. `r.addBuiltin` `eat(actor, amount)` (mirror the weather sample's `addBuiltin` with `PluginParam`s): lowers hunger
       by `amount`, not below 0, and returns the new value.
  - Keep it short (under ~120 lines) and commented for students: it is a teaching sample.
- **README:** what it shows (one bullet per extension point), how to build the jar, how to pin it in `MODULE.gups`, and
  a two-line `on_use` example calling `hunger.eat(ctx.actor, 3)` guarded by `if ctx.has_feature("hunger")`.
- **`PLUGINS.md`:** a new section "Feature-sized plugins" with subsections for `providedFeatures` and
  `requiredFeatures`, `r.schema`/`r.features`/`r.refuse`, `every`, and `onEvent`, using `HungerPlugin` snippets (fences
  at column 0, outside lists). Mention why built-in features are not plugins (one paragraph, matching §3.10's new
  bullet).
- **CHANGELOG:** in `### Feature-sized plugins`, one line: "Plugins can provide features, refuse a world at load, run
  periodic work and listen to engine events; see `docs/PLUGINS.md`."
- **DESIGN_NEEDED:** under `### Feature modules and the plugin interface`, add a first line `**Status: completed in
  Milestone 41.**` followed by a one-sentence summary of the answer (built-ins stay internal; plugins gained
  `providedFeatures`, `refuse`, `every`, `onEvent`). Do not delete the entry.

### Test & Documentation Requirements

- `HungerWorldTest.kt` (mirror `CurseWorldTest`: load the built jar into a copy of the template world):
  - The plugin loads; a script `ctx.has_feature("hunger")` is `True`.
  - First run pulses until `pulse % 120 == 0`, so the count is not off by one; then after 5 × 120 more pulses a player's
    `hunger:hunger` has risen by 5 (from 0 to 5) and they saw "You are getting hungry."
  - Killing the player (or letting starvation kill a low-hp player) clears the attribute.
  - `hunger.eat` lowers it and never below 0.
  - In a temp world whose `MODULE.gups` has no `hp` vital, the plugin is skipped with `refused: needs an 'hp' vital`.

### Exit Conditions

- [] `./gradlew :samples:hunger-plugin:test` passes.
- [] `./gradlew markdownFormat` run; `./gradlew check` passes with zero warnings.
- [] `docs/DESIGN_NEEDED.md` marks the entry completed without deleting it.
- [] SPEC §3.10, §4.4, §4.5, §4.9 and §4.15 describe exactly what the code does; grep SPEC for "any other name raises an
  error" and for the old five-name global hook list and confirm neither remains.
- [] Full suite: `./gradlew check` from a clean build.
