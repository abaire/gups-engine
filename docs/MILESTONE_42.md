# Milestone 42: Custom Creation Steps and Base-Value Setters

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, heading `### Rerolls and point-buy at character creation` (under
  "Combat and skills").
- **Design Summary:**
  1. **The engine implements neither rerolls nor point-buy.** Both are policy, and policy belongs to the world's
     creation provider plugin. The engine adds only the API a plugin needs to build them. Rolled stats are deliberately
     not an engine feature: they reward luck and encourage reroll farming instead of play. The API still lets a plugin
     author build rolls deterministically, and the docs explain how.
  2. **Base-value setters.** `PluginWorld` gains `setBaseStat(actor, stat, value)`, `setBaseMaxVital(actor, vital,
     value)` and `setBaseResist(actor, type, value)`, each returning `String?` (always `null` today; reserved for
     refusals) and throwing `IllegalArgumentException` for unknown schema names. They work on players and mobs. Lowering
     a maximum caps `current`; raising one leaves `current` alone. After a change the actor's holdings (encumbrance and
     fit) are refreshed and the actor is marked dirty. They fire no event and run no restriction. The getters
     (`baseStat`, `baseMaxVital`, `baseResist`) already exist on `PluginActor`. These setters are also what a future
     stat-trainer plugin will use.
  3. **Custom creation steps.** `PluginCreation.steps: List<PluginCreationStep>` are asked after all choices and before
     the confirmation, for new characters only (never in the missing-choices flow). A step renders its own text
     (`show`), handles every input line except `back` and `cancel` (`input` returns `Stay(message?)` or `Done`), and
     contributes an optional confirmation line (`summary`). The engine owns `back`, `cancel`, the idle limit and held
     input. A provider may have steps and no choices.
  4. **`PluginCreationAttempt`** is the per-attempt handle every step call gets: `name`, `answers`, `seed`,
     `rng(stream)`, a mutable `state` map, and a read-only `preview` actor (the would-be character with its answers,
     traits and `starting_stats` applied, never in the world). Answering any choice (again) clears `state`, the set of
     finished steps and the preview. The attempt survives a name-taken retry.
  5. **`PluginCreation.prepare(world, actor, attempt)`** runs inside the spawn of a new character created through the
     flow: after the traits are worked out and `starting_stats` is applied, before the character enters the world. The
     plugin applies its results there with the setters (and may also grant traits, set skills and write attributes).
     After `prepare`, every vital whose `current` `starting_stats` did not give is refilled to its new effective
     maximum. `created()` is unchanged and still runs after entry.
  6. **Determinism.** The attempt seed is a pure function of the world seed, a key and a count. The key is `a:<account
     id>` for an account login, and `n:<lowercased name>` in `--dev`, the harness and the fuzz walker. The count is the
     account's persisted **creation counter** (`accounts.creations`, 0 for name keys): it starts at 0, goes up by one
     each time the account creates a character, and never goes down, not even when characters are wiped. While a
     character is created through the flow (or through harness/fuzz steps), `rules.starting_stats` draws from a
     creation-only `Rng(attempt.seed)` instead of `world.rng`, so the preview equals the real spawn and logins no longer
     advance `world.rng`. Mob spawns and plain `spawn_player` keep `world.rng`. `attempt.rng(stream)` gives plugins
     independent deterministic streams; `cancel`/`new`, `back` and reconnecting replay the same draws, which is what
     makes reroll farming pointless for a plugin that offers rolls. Because the counter is stored and only grows, a
     `--wipe` or a future character-deletion feature can't make an account replay an earlier seed. The counter is
     increased off the world thread after the character is created; if the server dies before that write commits, the
     account's next creation reuses the seed, which a player can't cause on purpose.
  7. **Protocol.** `Char.Creation` gains `"step": "custom"` with a `custom` object `{"id", "title", "data"}`. The
     confirmation lists each non-null step summary after the answers, as `<title> <summary>`, in the same `{answers}`
     string, and the `confirm` payload gains `summaries`.
  8. **Tests.** The harness gains `spawn_player(..., steps = {step_id: [lines]})`. Fuzz bots run every applicable step,
     answering `done`; an exception from a step or from `prepare` fails the fuzz run.
  9. **Sample and docs.** `samples/heritage-plugin` gains a point-buy `stats` step. `docs/PLUGINS.md` and the heritage
     tutorial document custom steps, the setters, `prepare`, and a prose-plus-sketch section "Adding rolls with
     `attempt.rng`".
- **Specification Impact:** §3.5 (account records gain the creation counter), §3.6 (`accounts.creations` column), §4.6
  (`starting_stats` rng during creation), §4.10 (`Char.Creation`), §4.12 (harness `spawn_player(steps=)`), §4.15 (facade
  setters, `PluginCreation` members), §4.16 (`plugins.html` lists steps), §4.19 (custom steps, attempts, `prepare`),
  §5.12 (creation steps, confirmation, seed), §7.3 (fuzz walker). Also `docs/PLUGINS.md`, `docs/CHANGELOG.md`,
  `docs/api/api-1.json` and golden files where they change.
- **Decision Record Updates:** None. (`docs/DECISIONS.md` has no entry about creation, rerolls or point-buy.)

### Phase map

| Phase |                     Title                      |                           Main files                           |
|-------|------------------------------------------------|----------------------------------------------------------------|
| 1     | Base-value setters                             | `Verbs.kt`, `Facade.kt` (api + host)                           |
| 2     | Creation attempts, creation RNG, preview actor | `Creation.kt`, `RulesEngine.kt`, `World.kt`, script rng wiring |
| 3     | Step types, adapters and registration checks   | `PluginApi.kt`, `Adapters.kt`, `PluginHost.kt`, `Creation.kt`  |
| 4     | Steps in `CreationFlow`                        | `CreationFlow.kt`                                              |
| 5     | Account creation counter                       | core `Accounts.kt`, persist `Database.kt`/`Accounts.kt`        |
| 6     | Login paths and `plugins.html`                 | `Login.kt`, `AccountLogin.kt`, the `plugins.html` generator    |
| 7     | `prepare` and the attempt-seeded spawn         | `World.kt`, host `Facade.kt`, `Login.kt`, `AccountLogin.kt`    |
| 8     | Harness `spawn_player(steps=)` and fuzz walker | `CreationFlow.kt`, `Handles.kt`, `FuzzWalker.kt`               |
| 9     | Heritage point-buy sample, docs, finalization  | `samples/heritage-plugin`, `PLUGINS.md`, `DESIGN_NEEDED.md`    |

### Ground rules for every phase

1. **Re-read before editing.** Other work may have changed the files named here. Locate code by symbol name, never by
   the line numbers quoted in this document.
2. **TDD.** Write the tests named in each phase first, run them and see them fail, then implement.
3. **Spec edits are pre-approved.** The SPEC text given in each phase is approved; apply it directly (adjusting only for
   formatting and cross-references). Do not add `DECISIONS.md` entries for anything this document settles. Log a
   `DECISIONS.md` entry only for a genuinely new gap you have to fill.
4. **No spec citations in code comments** (see `AGENTS.md`).
5. **`PluginApi.VERSION` stays 1** (the engine is unreleased; no migrations or back-compat shims).
6. **New `PluginWorld`, `PluginCreation` and `PluginCreationStep` members get default implementations in the interface**
   where a fake would otherwise stop compiling (sample plugin tests and `plugin-api` tests contain hand-written fakes of
   `PluginWorld`). `WorldFacade` overrides every one of them.
7. **API files.** In any phase that changes the script or plugin API, regenerate before the phase's final `./gradlew
   check` if a golden or API test fails:
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

## Phase 1: Base-Value Setters on `PluginWorld`

### Objective & Scope

- **In Scope:** `World` verbs that set a base stat, a base vital maximum and a base resist; the three `PluginWorld`
  setters and their `WorldFacade` implementation; tests; §4.15 and `PLUGINS.md` text for the setters.
- **Out of Scope (do not implement these in this phase):** anything about creation (steps, attempts, `prepare`, the
  pre-entry exemption for setters; Phases 2 to 7); Starlark `ctx` or harness equivalents of the setters (not planned);
  events or restriction points for stat changes (not planned).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Verbs.kt`: `grantTrait`, `setSkill` (verb shape, dirty marking).
  - `core/src/main/kotlin/gups/core/world/Vitals.kt`: `clampVitals`.
  - `core/src/main/kotlin/gups/core/world/Holdings.kt`: `refreshHoldings` (encumbrance and fit).
  - `core/src/main/kotlin/gups/core/model/Entities.kt`: `Actor.baseStats`, `vitalMap`, `resists`, `max(name)`,
    `baseStat`, `baseResist`.
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`: `WorldFacade.setSkill`, `actorOf`, and how other verbs
    reject unknown schema names with a spelling suggestion.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Verbs.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt` (the `PluginWorld` interface)
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt` (`WorldFacade`)
  - `plugin-api/src/test/kotlin/gups/pluginapi/FacadeTest.kt`
  - `core/src/test/kotlin/gups/core/world/BaseValuesTest.kt` (new)
  - `docs/SPEC.md` (§4.15), `docs/PLUGINS.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `CreationFlow.kt`, `Login.kt`, `AccountLogin.kt`, the harness, the fuzz walker, the samples.

### Specification & Decision Record Changes

- §4.15, the sentence listing the facade's mutation verbs: add `setBaseStat`, `setBaseMaxVital`, `setBaseResist` after
  `removeSkill`.
- §4.15 "Facade operations": add a bullet after "Skills and leveling" with this text:

> **Base values:** `setBaseStat(actor, stat, value)`, `setBaseMaxVital(actor, vital, value)` and `setBaseResist(actor,
> type, value)` set a player's or mob's base stat, base vital maximum or base resist. Trait, affect and equipment deltas
> still apply on top. Lowering a maximum caps `current` at the new effective maximum; raising one leaves `current` as it
> is. A change refreshes the actor's encumbrance and equipment fit and is saved with the actor. No hook or restriction
> runs: a plugin that sells training (a stat trainer) enforces its own limits. Each returns `null`; an unknown name
> throws as for the other verbs.

### Implementation Blueprint

**Signatures & Types:**

```kotlin
// core/.../world/Verbs.kt
fun World.setBaseStat(actor: Actor, stat: String, value: Int)
fun World.setBaseMaxVital(actor: Actor, vital: String, value: Int)
fun World.setBaseResist(actor: Actor, type: DamageType, value: Int)

// plugin-api/.../Facade.kt, in PluginWorld (default bodies throw UnsupportedOperationException; ground rule 6)
fun setBaseStat(actor: PluginActor, stat: String, value: Int): String?
fun setBaseMaxVital(actor: PluginActor, vital: String, value: Int): String?
fun setBaseResist(actor: PluginActor, type: String, value: Int): String?
```

**Algorithmic Steps & Invariants:**

1. `setBaseStat`: `require(stat in the schema's stats)`; set `actor.baseStats[stat] = value`; then the shared tail (step
   4).
2. `setBaseMaxVital`: `require(vital in actor.vitalMap)`; `require(value >= 0)`; set `actor.vitalMap.getValue(vital).max
   = value`; then the shared tail. Do **not** raise `current`.
3. `setBaseResist`: `require(type in the schema's damage types)`; set `actor.resists[type] = value`; then the shared
   tail.
4. Shared tail, in this order: `refreshHoldings(actor)`, `clampVitals(actor)`, `actor.dirty = true`. `refreshFit` and
   `clampVitals` return early for an actor outside the world; check that `updateEncumbrance` (called by
   `refreshHoldings`) does too, and add the same guard if it doesn't (Phase 7 calls these verbs on a not-yet-entered
   player). Do not call `requestImmediate`: trainers may call these often; the periodic checkpoint saves them.
5. `WorldFacade` setters: validate the name first with the same message style as the other verbs (for example
   `setBaseStat(stat): unknown stat 'strr'` plus the spelling suggestion), resolve the actor with `actorOf(actor,
   "setBaseStat(actor)")`, call the `World` verb, return `null`.

### Test & Documentation Requirements

- `BaseValuesTest.kt`: setting a stat changes `baseStat` and the effective `stat` (which still includes a trait delta);
  lowering `max_hp` below `current` caps `current` and refreshes the condition; raising it leaves `current`; setting
  `str` changes the encumbrance level of a heavily loaded actor (use a small test rules module so the numbers are
  obvious); works for a mob; marks the actor dirty.
- `FacadeTest.kt`: each setter's happy path; an unknown stat, vital or damage type throws `IllegalArgumentException`
  naming it; an actor that left the world throws (as other verbs do).
- `PLUGINS.md`: a short "Base values" subsection near "Modifying choices in play", with a trainer-style example that
  reads `actor.baseStat("str")` and calls `world.setBaseStat(actor, "str", actor.baseStat("str") + 1)` (fence at column
  0, outside any list).
- `CHANGELOG.md`: a new `### Custom creation steps and base values` heading at the top of `api_version 1`, with one
  line: "Plugins can set base stats, base vital maxima and base resists with `setBaseStat`, `setBaseMaxVital` and
  `setBaseResist`."

### Exit Conditions

- [] `./gradlew :core:test :plugin-api:test` passes.
- [] `./gradlew markdownFormat` run; `./gradlew check` passes with zero warnings.
- [] §4.15 lists the three setters and describes them as above.

---

## Phase 2: Creation Attempts, the Creation RNG and the Preview Actor

### Objective & Scope

- **In Scope:** the core `CreationAttempt` type with its seed derivation and RNG streams; letting a rules call use an
  RNG other than `world.rng`; splitting `World.spawnPlayer` so a not-yet-entered player can be built and given its
  starting stats with a chosen RNG; building the preview actor. Pure core and script work with unit tests; nothing in
  the login path changes yet, so the spec does not change in this phase.
- **Out of Scope (do not implement these in this phase):** plugin step types (Phase 3); `CreationFlow` (Phase 4); login
  paths (Phase 6); `prepare`, the spawn path that uses an attempt, and the §4.6 spec text (Phase 7); harness and fuzz
  (Phase 8).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/Rng.kt`.
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt`: `RulesContext` (its `rng` getter returns `world.rng`).
  - `script/src/main/kotlin/gups/script/Views.kt`: `ViewScope`, `RngView` (uses `scope.world.rng`).
  - `script/src/main/kotlin/gups/script/Host.kt`: `Frame` (implements `ViewScope`).
  - `script/src/main/kotlin/gups/script/Rules.kt`: the rules `ctx` `rng` field, `roll()` (uses `c.world.rng`), and the
    function that runs a rules export, to see where a rules call's `Frame` is created.
  - `core/src/main/kotlin/gups/core/world/World.kt`: `spawnPlayer`, `startingStatsOf`, `applyStartingStats`,
    `initVitals`, `allocateId`, `config.seed`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Creation.kt` (add `CreationAttempt`)
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt` (`RulesContext` gets an optional rng)
  - `script/src/main/kotlin/gups/script/Views.kt`, `Host.kt`, `Rules.kt` (use the call's rng)
  - `core/src/main/kotlin/gups/core/world/World.kt` (split `spawnPlayer`, add `previewPlayer`)
  - `core/src/test/kotlin/gups/core/world/CreationAttemptTest.kt` (new)
- **Do Not Touch:** `CreationFlow.kt`, `Login.kt`, `AccountLogin.kt`, `plugin-api`, the harness, the samples,
  `docs/SPEC.md`.

### Specification & Decision Record Changes

- None in this phase.

### Implementation Blueprint

**Signatures & Types:**

```kotlin
// core/.../model/Creation.kt
/**
 * One attempt at creating a character. Its seed depends only on the world seed, [key] and [count],
 * so cancelling and starting again replays the same draws.
 */
class CreationAttempt(var name: String, val key: String, val count: Int, worldSeed: Long) {
  val seed: Long
  /** Answers given so far, kept in step with the flow. */
  val answers: MutableMap<String, String> = LinkedHashMap()
  /** Free-form plugin state for the steps; cleared when a choice is answered. */
  val state: MutableMap<String, Any?> = LinkedHashMap()
  /** Ids of the custom steps that returned Done; cleared with [state]. */
  val finished: MutableSet<String> = LinkedHashSet()
  /** The would-be character, built on demand; cleared with [state]. */
  var preview: Player? = null
  /** Set by the spawn when the provider's prepare threw; null otherwise. */
  var prepareError: Throwable? = null

  /** The generator `starting_stats` draws from for this attempt. Each call returns a fresh one. */
  fun startingRng(): Rng = Rng(seed)

  /** An independent deterministic stream for plugin draws. Each call returns a fresh one. */
  fun rng(stream: Int): Rng

  /** Clears everything that depends on the answers: state, finished steps and the preview. */
  fun answersChanged()

  companion object {
    fun accountKey(accountId: Long) = "a:$accountId"
    fun nameKey(name: String) = "n:${name.lowercase()}"
  }
}
```

**Algorithmic Steps & Invariants:**

1. `seed`: FNV-1a 64 over the UTF-8 bytes of `"$key#$count"`, XOR `worldSeed`, then one SplitMix64 finalizer step.
   `rng(stream)`: `Rng(splitMix64(seed xor ((stream.toLong() + 1) * 0x9E3779B97F4A7C15uL.toLong())))`. Put `fnv1a64` and
   `splitMix64` as private top-level functions in `Creation.kt`. The KDoc says the derivation is stable (a test pins a
   value). `name` is a `var` because a name-taken retry renames the attempt (Phase 6); it is not part of the seed.
2. `RulesContext`: add a constructor parameter `rng: Rng? = null`, last in the list so existing call sites compile,
   stored privately; the existing `val rng` getter returns it when set, else `world.rng`.
3. Script side: add `val rng: Rng get() = world.rng` to `ViewScope`. In `Frame`, add an overridable `var rng: Rng`
   defaulting to `world.rng`; where a rules export's `Frame` is created in `Rules.kt`, set it from `c.rng`. Change
   `RngView` to use `scope.rng`, and the rules `roll()` to use `c.rng`. `ctx.roll` in `Ctx.kt` (hooks) stays on
   `world.rng`.
4. `World.kt`: extract from `spawnPlayer` an internal builder:

```kotlin
/** A player with its answers, traits and starting stats, not yet in the world, and the starting_stats result. */
internal fun buildPlayer(
  id: EntityId, name: String, at: Room, level: Int, index: Int, accountId: Long?,
  choices: Map<String, String>, startingRng: Rng?,
): Pair<Player, StartingStats>
```

5. `buildPlayer` does everything `spawnPlayer` does today up to and including `applyStartingStats(p, startingStatsOf(p,
   startingRng))`, and returns the player with the `StartingStats` result (Phase 7 needs `result.current.keys`).
   `startingStatsOf(a, rng: Rng? = null)` passes `rng` into `RulesContext`. `spawnPlayer` keeps its signature and
   behaviour by calling `buildPlayer(allocateId(), ..., startingRng = null)` and then the rest (overrides, settle,
   enter, fit, clamp).
6. Add `fun World.previewPlayer(attempt: CreationAttempt, at: Label): Player`. It returns `attempt.preview` when set;
   otherwise it calls `buildPlayer(PREVIEW_ID, attempt.name, room(at), 1, 0, null, attempt.answers,
   attempt.startingRng())`, stores the player in `attempt.preview` and returns it. `PREVIEW_ID = EntityId(0)`; confirm
   that ids start at 1 in `allocateId`, and pick another unused value if not. The preview is never registered, never
   entered and never allocates an id. Do not call `settleNewCondition` for it.

### Test & Documentation Requirements

- `CreationAttemptTest.kt`:
  - The same `(key, count, worldSeed)` gives the same `seed`; changing any one gives a different seed. Pin one literal
    seed value so the derivation can't drift silently.
  - `rng(0)` and `rng(1)` differ; `rng(3)` called twice yields identical sequences.
  - `answersChanged()` clears `state`, `finished` and `preview`.
  - With a rules module whose `starting_stats` uses `ctx.rng.int(1, 1000)` for a player stat (a tiny rules package in
    the test fixture, installed the way existing core tests install rules): two previews from fresh attempts with the
    same seed give the same stat; building a preview leaves `world.rng`'s next draw unchanged (compare with a twin
    world); `world.players` doesn't contain the preview.
- Plain `spawnPlayer` still draws from `world.rng`: the existing tests cover this; run them.

### Exit Conditions

- [] `./gradlew :core:test :script:test` passes; pre-existing tests are unchanged and green.
- [] `./gradlew check` passes with zero warnings.

---

## Phase 3: Step Types, Adapters and Registration Checks

### Objective & Scope

- **In Scope:** the plugin types `PluginCreationStep`, `PluginStepView`, `PluginStepReply`, `PluginCreationAttempt`,
  `PluginCreation.steps` and `PluginCreation.prepare` (declared with a no-op default; called only from Phase 7); their
  core mirrors on `CreationProvider`; the adapters that bridge them; registration validation of step ids. No behaviour
  visible to players changes, so the spec does not change in this phase.
- **Out of Scope (do not implement these in this phase):** running steps in `CreationFlow` (Phase 4); login paths and
  `plugins.html` (Phase 6); calling `prepare` (Phase 7); harness and fuzz (Phase 8).

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`: `setCreation`, and wherever choice and option ids
    are validated (search for the reserved words `back` and `cancel` near creation).
  - `plugin-api/src/test/kotlin/gups/pluginapi/CreationRegistrationTest.kt` (how registration errors are asserted).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`: `ActorFacade`, `WorldFacade`, `RngFacade`.
  - `core/src/main/kotlin/gups/core/world/CreationFlow.kt`: `creationStartRoom`.
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`
  - `core/src/main/kotlin/gups/core/model/Creation.kt`
  - `plugin-api/src/test/kotlin/gups/pluginapi/CreationRegistrationTest.kt`
- **Do Not Touch:** `CreationFlow.kt` (except reading), `Login.kt`, `AccountLogin.kt`, `World.kt`, the harness, the
  samples, `docs/SPEC.md`.

### Specification & Decision Record Changes

- None in this phase.

### Implementation Blueprint

**Signatures & Types (plugin API, `PluginApi.kt`):**

```kotlin
interface PluginCreation {
  // ...existing members unchanged...
  val steps: List<PluginCreationStep> get() = emptyList()
  fun prepare(world: PluginWorld, actor: PluginActor, attempt: PluginCreationAttempt) {}
}

interface PluginCreationStep {
  val id: String
  val title: String
  fun applies(world: PluginWorld, attempt: PluginCreationAttempt): Boolean = true
  fun show(world: PluginWorld, attempt: PluginCreationAttempt): PluginStepView
  fun input(world: PluginWorld, attempt: PluginCreationAttempt, line: String): PluginStepReply
  fun summary(world: PluginWorld, attempt: PluginCreationAttempt): String? = null
}

data class PluginStepView(val text: String, val gmcp: Map<String, Any?> = emptyMap())

sealed interface PluginStepReply {
  data class Stay(val message: String? = null) : PluginStepReply
  data object Done : PluginStepReply
}

interface PluginCreationAttempt {
  val name: String
  val answers: Map<String, String>
  val seed: Long
  val state: MutableMap<String, Any?>
  val preview: PluginActor
  fun rng(stream: Int): PluginRng
}
```

**Core mirrors (`Creation.kt`):**

```kotlin
interface CreationProvider {
  // ...existing members unchanged...
  val steps: List<CreationStep> get() = emptyList()
  fun prepare(world: World, player: Player, attempt: CreationAttempt) {}
}

interface CreationStep {
  val id: String
  val title: String
  fun applies(world: World, attempt: CreationAttempt): Boolean
  fun show(world: World, attempt: CreationAttempt): StepView
  fun input(world: World, attempt: CreationAttempt, line: String): StepReply
  fun summary(world: World, attempt: CreationAttempt): String?
}

data class StepView(val text: String, val gmcp: Map<String, Any?> = emptyMap())

sealed interface StepReply {
  data class Stay(val message: String?) : StepReply
  data object Done : StepReply
}
```

Core step methods let exceptions propagate; the flow (Phase 4) catches them.

**Algorithmic Steps & Invariants:**

1. `CreationStepAdapter(world, pluginId, step) : CreationStep` in `Adapters.kt`, one per plugin step, created by
   `CreationProviderAdapter.steps`. Each method wraps exceptions as `CreationProviderAdapter.available` does:
   `IllegalStateException("plugin '$pluginId': step '<id>' <method> failed: $e", e)`.
2. An internal `AttemptFacade(attempt: CreationAttempt, world: World) : PluginCreationAttempt`:
   - `name`, `answers` (read-only view of `attempt.answers`), `seed` and `state` read through to the attempt.
   - `preview` is `ActorFacade(world.previewPlayer(attempt, start), world)`, where `start` is
     `world.creationStartRoom(attempt.answers, <world start room>)`, falling back to the world start room if that
     throws. The preview's room only matters to rules that read it.
   - `rng(stream)` returns a `PluginRng` over `attempt.rng(stream)` (mirror `RngFacade`, but over a given `Rng`). Keep
     one `Rng` per stream per `AttemptFacade` so successive draws advance; a new facade starts the streams again.
3. `CreationProviderAdapter.prepare(world, player, attempt)` calls `creation.prepare(facade, ActorFacade(player, world),
   AttemptFacade(attempt, world))`, wrapping exceptions like `available`.
4. Registration validation, next to the existing choice and option checks: every step id matches `[a-z][a-z0-9_]*`; step
   ids are unique; no step id equals a choice id; no `title` is blank. Failures are the same kind of registration error
   the choice checks raise.

### Test & Documentation Requirements

- `CreationRegistrationTest.kt`: a bad step id, a duplicate step id, a step id equal to a choice id, and a blank title
  are each rejected; a valid provider with steps and no choices registers.
- An adapter test (in `CreationRegistrationTest.kt` or a new `CreationStepAdapterTest.kt`): a plugin step's `show`,
  `input` and `summary` reach the plugin through the adapter; a throwing step surfaces as `IllegalStateException` naming
  the plugin and step; `AttemptFacade.rng(0)` draws match `attempt.rng(0)`; `preview` has the answers' traits.
- Regenerate API files (ground rule 7): the `PluginApi` surface changes.

### Exit Conditions

- [] `./gradlew :core:test :plugin-api:test :cli:test` passes.
- [] `./gradlew check` passes with zero warnings.

---

## Phase 4: Steps in `CreationFlow`

### Objective & Scope

- **In Scope:** running custom steps in `CreationFlow` after the choices and before the confirmation (new characters
  only); `back`, `cancel`, re-answer clearing; confirmation summaries; `Char.Creation` GMCP; carrying a
  `CreationAttempt` in the flow and in `CreationOutcome`; the spec text for steps and attempts.
- **Out of Scope (do not implement these in this phase):** building attempts in the login paths and `plugins.html`
  (Phase 6; in this phase both login paths pass a minimal attempt so they compile, see step 9); calling `prepare` and
  using the attempt's RNG at the real spawn (Phase 7); harness and fuzz (Phase 8).

### Context Working Set

- **Read / Reference Only:**
  - Phase 2's `CreationAttempt` and `previewPlayer`; Phase 3's `CreationStep`, `StepView`, `StepReply`.
  - `LoginMsg` (wherever `CREATION_CONFIRM` is defined) and the message catalog.
  - `core/src/test/kotlin/gups/core/world/CreationFlowTest.kt` (fixture and fake provider patterns).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/CreationFlow.kt`
  - `core/src/test/kotlin/gups/core/world/CreationFlowTest.kt`
  - `core/src/main/kotlin/gups/core/world/Login.kt` and `auth/src/main/kotlin/gups/auth/AccountLogin.kt` (only the
    minimal compile change of step 9)
  - `docs/SPEC.md` (§4.10, §4.19, §5.12)
- **Do Not Touch:** `World.spawnPlayer`, `plugin-api`, the harness, the fuzz walker, the samples.

### Specification & Decision Record Changes

- §4.19: after the "Choices" list and before "Answers on the character", add a **Custom steps** list with these bullets
  (one paragraph each):

> A provider may also declare **custom steps**, in order. A step has an id (`[a-z][a-z0-9_]*`, unique among the steps
> and not equal to any choice id) and a `title` ("Stats"). Steps are asked after the last choice and before the
> confirmation, only when a new character is created, never for a saved character's missing choices. A provider may have
> steps and no choices.
>
> The provider says whether a step applies to the attempt; a step that doesn't is skipped. A step shows its own text and
> handles each line the player types, except `back` and `cancel`, which the engine handles as for choices. On each line
> the step either stays (optionally with a message, after which its text is shown again) or is done. A step may give a
> one-line summary for the confirmation.
>
> Every step call gets the **attempt**: the character's name, the answers, a seed, deterministic random streams derived
> from it, a state map the steps share, and a **preview**: the would-be character with its answers, traits and
> `rules.starting_stats` applied, not in the world. Answering a choice, even with the same option, clears the state, the
> steps already done and the preview.
>
> The engine has no rolls or point-buy of its own: a provider builds them from steps. A step that throws records a
> `plugin` diagnostic and is handled as any provider failure.

- §5.12, the "Creation steps" bullet: change "one prompt per choice that has an available option, in order, then a
  confirmation" to "one prompt per choice that has an available option, in order, then each custom step that applies
  (§4.19), then a confirmation". In its sub-bullets:
  - In the `back` bullet, add: "`back` from the first custom step goes to the last choice asked (as `back` from the
    first choice when none was asked); `back` at the confirmation goes to the last custom step that applies, else the
    last choice."
  - The confirmation bullet's first sentence becomes: "The confirmation lists every answer as `<title> <label>`, in
    choice order, then each custom step's summary as `<title> <summary>`, separated by ", "."
- §4.10, the `Char.Creation` row: the payload becomes `{"name", "step": "choice"/"custom"/"confirm", "choice": {...} or
  null, "custom": {"id", "title", "data"} or null, "answers": [...], "summaries": [{"step", "title", "text"}]}`, or
  `null`. In the `Char.Creation` payload notes, add: "`custom.data` is the object the step supplies (empty when none);
  `summaries` is empty except at `confirm`."

### Implementation Blueprint

**Signatures & Types:**

```kotlin
class CreationFlow(
  private val world: World,
  private val session: Session,
  private val name: String,
  private val newCharacter: Boolean,
  private val attempt: CreationAttempt,
  private val onOutcome: (CreationOutcome) -> Unit,
)

sealed interface CreationOutcome {
  data class Created(val attempt: CreationAttempt) : CreationOutcome   // answers = attempt.answers
  data class Completed(val answers: Map<String, String>) : CreationOutcome
  object Cancelled : CreationOutcome
  data class BackToName(val attempt: CreationAttempt) : CreationOutcome
  object ProviderFailed : CreationOutcome
}
```

**Algorithmic Steps & Invariants:**

1. `answers` becomes `attempt.answers` (keep a property named `answers` so existing code and tests read the same).
2. `Step` gains `data class Custom(val stepId: String)`. `history` holds choice ids and step ids (disjoint by Phase 3's
   validation).
3. Whenever a choice is answered (the match branch of `handleChoiceInput`) or answers are cleared from a choice
   (`reask`, `back`), call `attempt.answersChanged()`.
4. `advance()`: after the choices loop, instead of going straight to `promptConfirm()`, and only when `newCharacter`,
   walk `provider.steps` in order. Skip a step whose `applies` is false (an exception ends the flow with a `plugin`
   diagnostic and `ProviderFailed`) or whose id is in `attempt.finished`. Show the first remaining one:
   `history.add(step.id)`, then `showStep(step)`. If none remains, `promptConfirm()`.
5. `showStep(step, message: String? = null)`: set `currentStep = Step.Custom(id)`; send `message` first when set; call
   `show` (an exception → diagnostic and `ProviderFailed`); send `view.text`, flush, prompt with `CREATION_PROMPT`; send
   GMCP `{"name", "step": "custom", "choice": null, "custom": {"id", "title", "data": view.gmcp}, "answers":
   buildAnswersGmcp(answers), "summaries": []}`. Add `"custom": null, "summaries": []` to the existing choice payload,
   and `"custom": null` plus the summaries (step 7) to the confirm payload.
6. `handleCustomInput(stepId, text)`:
   - `cancel` → `Cancelled`.
   - `back` → `history.removeLast()` (this step). If `history` is still non-empty, pop the previous entry: a step id →
     remove it from `attempt.finished` and `showStep` it; a choice id → `reask(it)`. If `history` is empty, behave as
     `back` from the first choice (`BackToName` for a new character).
   - Anything else → `input` (an exception → `ProviderFailed`). `Stay(m)` → `showStep(step, m)`. `Done` → add the id to
     `attempt.finished`, then `advance()`.
7. Confirmation:
   - `back` at the confirmation goes to the last applicable step: remove it from `attempt.finished` and `showStep` it.
     Without one, today's `lastAsked()` behaviour applies.
   - The `{answers}` string appends `"${step.title} $summary"` for each applicable step, in order, whose `summary` is
     non-null (an exception → `ProviderFailed`).
   - The GMCP `summaries` list has the same entries as `{"step", "title", "text"}`.
8. The `yes` branch ends with `Created(attempt)` for a new character, and `Completed(answers)` otherwise, as today.
9. Minimal compile change in `Login.kt` and `AccountLogin.kt`: where they construct `CreationFlow` with
   `initialAnswers`, build `CreationAttempt(name, CreationAttempt.nameKey(name), 0, world.config.seed)` (or whatever the
   world was seeded with; `world.rng` is `Rng(config.seed)`), copy the kept answers into it, and pass it. Read answers
   from `outcome.attempt.answers` where they read `outcome.answers` today. Phase 6 replaces this with the real keys and
   retry handling.

### Test & Documentation Requirements

- `CreationFlowTest.kt`, with a fake provider declaring one choice and two steps:
  - After the last choice, step 1's text and the GMCP `custom` payload are sent.
  - `Stay("No.")` sends "No." and then the text again; `Done` moves to step 2, then to the confirmation.
  - The confirmation includes the summaries after the answers; the GMCP confirm payload has `summaries`.
  - `back` from step 1 returns to the choice; answering it (with the same option) clears `state` and `finished`, and
    step 1 runs again. `back` from the confirmation returns to step 2 with step 1 still finished.
  - A step whose `applies` is false is skipped.
  - A throwing `show`, `input` or `summary` ends with `ProviderFailed` and a `plugin` diagnostic.
  - A provider with steps and no choices goes straight to the steps.
  - `newCharacter = false` (missing choices) never shows steps.
- Existing `CreationFlowTest`, `auth` creation tests and `LoginTest` still pass unchanged in behaviour.

### Exit Conditions

- [] `./gradlew :core:test :auth:test` passes.
- [] `./gradlew markdownFormat` run; `./gradlew check` passes with zero warnings.
- [] §4.10, §4.19 and §5.12 describe custom steps as implemented, and say nothing about `prepare` yet.

---

## Phase 5: Account Creation Counter

### Objective & Scope

- **In Scope:** a persisted, never-decreasing per-account counter of characters created: the `accounts.creations`
  column, `Account.creations`, and an `AccountStore.countCreation(id)` write. Storage only; nothing reads it for seeds
  yet.
- **Out of Scope (do not implement these in this phase):** using the counter in the login path or incrementing it on
  creation (Phase 6); any player-facing display of it (not planned); character deletion (not planned).

### Context Working Set

- **Read / Reference Only:**
  - `persist/src/main/kotlin/gups/persist/Checkpoints.kt`: `AccountOp` (one class per account write).
  - `persist/src/main/kotlin/gups/persist/Database.kt`: `MIGRATIONS` (the schema is declared as this ordered list),
    `writeAccounts` (one branch per `AccountOp`), and the KDoc list of tables and migrations near the top of the file.
  - `persist/src/main/kotlin/gups/persist/Accounts.kt`: `SqliteAccountStore` (`submit`, `accounts(where)`, the `SELECT`
    column list).
  - `persist/src/test/kotlin/gups/persist/AccountStoreTest.kt` (test patterns).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/Accounts.kt` (`Account.creations`, `AccountStore.countCreation`)
  - `persist/src/main/kotlin/gups/persist/Checkpoints.kt` (`AccountOp.CountCreation`)
  - `persist/src/main/kotlin/gups/persist/Database.kt` (schema entry, write branch)
  - `persist/src/main/kotlin/gups/persist/Accounts.kt` (read the column, implement `countCreation`)
  - every other `AccountStore` implementation, including test fakes (find them by searching for `AccountStore` in
    `auth/src/main`, `auth/src/test`, `core/src/main` and `cli/src/main`)
  - `persist/src/test/kotlin/gups/persist/AccountStoreTest.kt`
  - `docs/SPEC.md` (§3.5, §3.6)
- **Do Not Touch:** `AccountLogin.kt`, `Login.kt`, `CreationFlow.kt`, `World.kt`, `plugin-api`, the harness, the
  samples.

### Specification & Decision Record Changes

- §3.5, the "Account records" bullet: after "and `createdAt`" add ", and `creations`, the number of characters the
  account has ever created. It only grows: a wipe (§2.4) keeps it, so a new character never reuses an earlier creation
  seed (§4.19)."
- §3.6, the `accounts` row of the table: add `creations` after `created_at` in the columns, and add "`creations` counts
  characters ever created and never decreases" to the notes.

### Implementation Blueprint

**Signatures & Types:**

```kotlin
// core/.../Accounts.kt
data class Account(
  // ...existing fields...
  /** How many characters this account has ever created; never decreases. */
  val creations: Int = 0,
)

interface AccountStore {
  // ...existing members...
  /** Adds one to account [id]'s creation counter. */
  fun countCreation(id: Long)
}

// persist/.../Checkpoints.kt
class CountCreation(val id: Long) : AccountOp
```

**Algorithmic Steps & Invariants:**

1. Schema: append the next entry to `MIGRATIONS`, `Migration(5, listOf("ALTER TABLE accounts ADD COLUMN creations
   INTEGER NOT NULL DEFAULT 0"))` (use the next free number if the list has grown), with a one-line comment like the
   others. Add it to the KDoc list of migrations and to the `accounts` columns in the KDoc table list. No data
   back-fill.
2. `writeAccounts`: `is AccountOp.CountCreation -> UPDATE accounts SET creations = creations + 1 WHERE id = ?`.
3. `SqliteAccountStore.accounts(where)`: add `creations` to the `SELECT` list and read it into `Account.creations`.
   `countCreation(id)` is `submit(AccountOp.CountCreation(id))`, like `setPassword`.
4. `AccountOp.Update` must keep not writing `creations` (it writes only the columns it lists today), so an `Update` from
   a stale `Account` copy can never lower the counter. Leave a short comment on the `UPDATE` statement saying so.
5. Every other `AccountStore` implementation increments its in-memory copy (fakes can keep a `creations` map or replace
   the stored `Account` with `copy(creations = creations + 1)`).

### Test & Documentation Requirements

- `AccountStoreTest.kt`: a new account reads `creations = 0`; `countCreation` twice reads 2; an `update(account)` with a
  stale copy (`creations = 0`) leaves it at 2; a database created before the new migration (follow how the file tests
  earlier migrations, if it does) gets the column with 0.

### Exit Conditions

- [] `./gradlew :core:test :persist:test :auth:test :cli:test` passes.
- [] `./gradlew markdownFormat` run; `./gradlew check` passes with zero warnings.
- [] §3.5 and §3.6 describe the counter.

---

## Phase 6: Login Paths and `plugins.html`

### Objective & Scope

- **In Scope:** building attempts with their real keys and counts in both login paths; keeping the attempt across
  `BackToName` and the name-taken retry; the missing-choices path; listing custom steps on `plugins.html`.
- **Out of Scope (do not implement these in this phase):** `prepare` and the attempt-seeded spawn (Phase 7; creation
  still spawns with `spawnPlayer(..., choices = attempt.answers)` exactly as today); harness and fuzz (Phase 8).

### Context Working Set

- **Read / Reference Only:**
  - `auth/src/main/kotlin/gups/auth/AccountLogin.kt`: `characters(world, a)`, `createCharacter`, `created`, `startFlow`,
    the `Conn` fields (`account`, `answers`, `flow`), `accounts.store`, and the `background` executor.
  - Phase 5's `Account.creations` and `AccountStore.countCreation`.
  - `core/src/main/kotlin/gups/core/world/Login.kt` (dev): `startFlow`, the new-character branch.
  - The `plugins.html` generator: search the `cli` and `script` modules for where the creation provider's choices and
    options are listed.
  - `auth/src/test/kotlin/gups/auth/CreationStepsTest.kt`, `CreationLoginTest.kt`, `LoginFixture.kt`.
- **Files to Modify / Create:**
  - `auth/src/main/kotlin/gups/auth/AccountLogin.kt`
  - `core/src/main/kotlin/gups/core/world/Login.kt`
  - the `plugins.html` generator file and its golden file (ground rule 7)
  - `auth/src/test/kotlin/gups/auth/CreationStepsTest.kt`
  - `docs/SPEC.md` (§4.16, §4.19, §5.12)
- **Do Not Touch:** `CreationFlow.kt`, `World.kt`, `plugin-api`, the harness, the samples.

### Specification & Decision Record Changes

- §4.16, the `plugins.html` row: "for the creation provider, its choices and options (title, labels, summaries, help and
  granted traits) and its custom steps (id and title)".
- §5.12, the "Creation steps" bullet: add a sub-bullet: "On a name-taken retry, the answers and the attempt (§4.19) are
  kept, so steps already done aren't asked again."
- §4.19, the **Custom steps** list: insert after the bullet about the attempt (the one starting "Every step call gets
  the **attempt**"):

> The seed depends only on the world seed, the account (or, without one, the character's name) and the account's
> creation counter (§3.5), which goes up by one each time the account creates a character. Cancelling and starting
> again, going back, or reconnecting gives the same seed, so a plugin that rolls numbers can't be farmed by restarting,
> and no later character of the account gets an earlier seed again.

### Implementation Blueprint

**Algorithmic Steps & Invariants:**

1. `AccountLogin`: replace `Conn.answers` with `Conn.attempt: CreationAttempt?`.
   - In `createCharacter`, take the kept attempt if there is one (set its `name` to the new name), else build
     `CreationAttempt(name, CreationAttempt.accountKey(a.id), c.account.creations, <world seed>)`. Clear `c.attempt`,
     then start the flow with it.
   - When the character has been created (the `Created` branch, after the spawn succeeds), increase the counter: set
     `c.account = c.account.copy(creations = c.account.creations + 1)` on the world thread, and run
     `accounts.store.countCreation(a.id)` on `background` (`background.execute { ... }`, logging a failure as a
     diagnostic as `later` does). Don't use `later`: the player is already in the world, and nothing waits for the
     write. The in-memory copy covers a second creation on the same connection before the write commits; a new login
     reads the committed value.
   - On `BackToName(attempt)`, keep it in `c.attempt`.
   - In `created()`, on the name-taken retry, keep `c.attempt` (instead of `c.answers`).
   - The missing-choices path builds an attempt the same way (it never runs steps; the attempt just carries the
     answers).
   - Everywhere `outcome.answers` was read for a `Created` outcome, read `outcome.attempt.answers`.
2. `Login.kt` (dev): key `CreationAttempt.nameKey(name)`, count 0; on a failed or cancelled flow the attempt is dropped
   (the dev prompt asks for a name again, and the same name gives the same seed anyway).
3. `plugins.html`: after the choices table for the creation provider, list the custom steps (id and title) in
   declaration order, or nothing when there are none. Mirror the markup used for choices.

### Test & Documentation Requirements

- `CreationStepsTest.kt` (auth), with a fixture provider that has one step:
  - An end-to-end telnet-style run through the step reaches the confirmation and creates the character.
  - `cancel` and then `new` gives the same `attempt.seed` (capture it from the step via a recording fake).
  - A second character on the same account gets a different seed from the first, and the account's `creations` read back
    from the store is 1 after the first creation and 2 after the second.
  - With an account whose stored `creations` is 3 (set it up through the store's fake), the attempt's `count` is 3.
  - A name-taken retry keeps finished steps: the player picks a new name and goes straight to the confirmation.
- The `plugins.html` golden shows a step for a fixture provider that declares one.

### Exit Conditions

- [] `./gradlew :core:test :auth:test :cli:test` passes.
- [] `./gradlew markdownFormat` run; `./gradlew check` passes with zero warnings.
- [] §4.16, §4.19 (the seed bullet) and §5.12 match the behaviour.

---

## Phase 7: `prepare` and the Attempt-Seeded Spawn

### Objective & Scope

- **In Scope:** creating a character from a `CreationAttempt`: build it with the attempt's `startingRng()`, call the
  provider's `prepare` before entry, refill unspecified vitals, then enter; recording a `prepare` failure on the
  attempt; letting the setters and a few other facade calls act on the character while it is being prepared; wiring both
  login paths to the new spawn; the spec text for `prepare` and the creation RNG.
- **Out of Scope (do not implement these in this phase):** harness and fuzz (Phase 8); the heritage sample (Phase 9).

### Context Working Set

- **Read / Reference Only:** Phase 2's `buildPlayer`; Phase 3's `CreationProvider.prepare` and adapter; Phase 6's login
  wiring; `plugin-api/.../host/Facade.kt` `actorOf`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/World.kt` (`spawnPlayer` gains an `attempt` parameter; `preparing` field)
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt` (the pre-entry exemption)
  - `core/src/main/kotlin/gups/core/world/Login.kt`, `auth/src/main/kotlin/gups/auth/AccountLogin.kt` (pass the attempt
    to `spawnPlayer`)
  - `core/src/test/kotlin/gups/core/world/CreationSpawnTest.kt` (new),
    `plugin-api/src/test/kotlin/gups/pluginapi/FacadeTest.kt`, `auth/src/test/kotlin/gups/auth/CreationStepsTest.kt`
  - `docs/SPEC.md` (§4.6, §4.15, §4.19, §5.12), `docs/PLUGINS.md`
- **Do Not Touch:** the step logic in `CreationFlow.kt`, the harness, the fuzz walker, the samples.

### Specification & Decision Record Changes

- §4.6, the `starting_stats(ctx)` bullet that ends "It runs before the actor enters the world, so it can randomize with
  `ctx.rng`.": append

> For a character created through the creation steps (§5.12), `ctx.rng` (and `ctx.roll`) draw from a generator seeded by
> the creation attempt (§4.19), not from the world's, so the stats shown during creation are the ones the character gets
> and creating a character doesn't change the world's later rolls. Mobs, and players spawned any other way, use the
> world's generator.

- §4.19, the **Custom steps** list: insert before the "The engine has no rolls or point-buy of its own" bullet:

> Before a new character created through the steps enters the world, the provider's `prepare` runs with the character
> and the attempt (§4.15), after its traits and `rules.starting_stats` are applied. A vital that `starting_stats` gave
> no `current` for is then filled to its effective maximum. A `prepare` that throws records a `plugin` diagnostic, and
> the character is created with the numbers it had. `created` still runs after the character enters.

and change that bullet's first sentence to "The engine has no rolls or point-buy of its own: a provider builds them from
steps, `prepare` and the base-value setters (§4.15)." - §5.12, the bullet "The character is built with its answers set,
so its traits apply; then `rules.starting_stats` runs (§4.6). A vital `starting_stats` gives no `current` for starts at
its effective maximum. The character enters the world, and then the provider's `created` callback runs (§4.15)."
becomes:

> The character is built with its answers set, so its traits apply; then `rules.starting_stats` runs (§4.6), drawing
> from the attempt's generator; then the provider's `prepare` runs (§4.19). A vital `starting_stats` gives no `current`
> for starts at its effective maximum after `prepare`. The character enters the world, and then the provider's `created`
> callback runs (§4.15).

- §4.15: where `PluginCreation` is described (search "creation definitions (`PluginCreation`)"), add "custom steps
  (`PluginCreationStep`) and the `prepare` callback". Append to the "Base values" bullet: "While `prepare` runs, the
  character it is given is not yet in the world, but the base-value setters, `grantTrait`, `removeTrait`, `setSkill`,
  `removeSkill`, `setAttr` and `delAttr` accept it. Every other verb refuses it as an actor that isn't in the world."

### Implementation Blueprint

**Signatures & Types:**

```kotlin
// World.kt
/** The player `prepare` is running for, which some facade verbs accept before it enters. */
var preparing: Player? = null
  internal set

fun spawnPlayer(
  name: String, at: Label, level: Int = 1, overrides: StartingStats = StartingStats(), index: Int = 0,
  accountId: Long? = null, choices: Map<String, String> = emptyMap(),
  attempt: CreationAttempt? = null,
): Player
```

**Algorithmic Steps & Invariants (`spawnPlayer` with `attempt != null`):**

1. `require(choices.isEmpty() || choices == attempt.answers)`; use `attempt.answers` as the choices.
2. `val (p, result) = buildPlayer(allocateId(), ..., startingRng = attempt.startingRng())`.
3. `attempt.prepareError = null`; `preparing = p`; then `try { creation?.prepare(this, p, attempt) } catch (e:
   Exception) { attempt.prepareError = e; diagnose("plugin", "creation provider prepare() failed: ${e.message}", e) }
   finally { preparing = null }`. A failed `prepare` doesn't stop the creation.
4. For each vital whose name is not in `result.current.keys`, set `current` to `p.max(name)` (the effective maximum).
   Vitals the result gave a `current` for are only capped (the existing `clampVitals` later does that).
5. Continue exactly as today: apply `overrides` with `keepCurrent = true`, `settleNewCondition`, `enter`,
   `forgetActorSlots`, `refreshFit`, `clampVitals`.
6. With `attempt == null`, behaviour is unchanged (`world.rng`, no `prepare`).

**Facade:** add `preparedActorOf(a, what)`. It accepts an actor that is not in the world when `world.preparing ===
actor`, and otherwise behaves like `actorOf`. Use it only in `setBaseStat`, `setBaseMaxVital`, `setBaseResist`,
`grantTrait`, `removeTrait`, `setSkill`, `removeSkill`, `setAttr` and `delAttr`; every other verb keeps `actorOf`. Check
that each of those `World` verbs is safe on a not-yet-entered actor: `clampVitals` and `refreshFit` return early,
`recheckWornItems` on an actor with no items does nothing, and `requestImmediate` is harmless.

**Login paths:** both `Created` handlers spawn with `spawnPlayer(..., attempt = outcome.attempt)` (through
`Login.create` and `AccountLogin.created`). The no-provider paths keep calling `spawnPlayer` without an attempt.

### Test & Documentation Requirements

- `CreationSpawnTest.kt`, with a fake provider whose `prepare` raises `str` by 3 and `max_hp` by 10 through the `World`
  verbs:
  - The spawned player has the raised values, and `current hp` equals the new effective maximum.
  - A `starting_stats` that returns `current = vitals(rads = 0)` keeps `rads` at 0 after `prepare`.
  - With a randomizing `starting_stats`, the spawned stats equal the attempt's preview stats.
  - A throwing `prepare` sets `attempt.prepareError`, records a `plugin` diagnostic, and the character is still created.
  - `world.rng` is in the same state as in a twin world where no character was created.
- `FacadeTest.kt`: during `prepare`, `setBaseStat` and `grantTrait` work, while `teleport` and `damage` throw. After
  `prepare` returns, the setters reject the same not-in-world actor object.
- `CreationStepsTest.kt` (auth): an account creation through a step whose `prepare` applies values from `state` ends
  with those base stats saved (reload the character and read them back).
- `PLUGINS.md`: document `prepare` with a short example in the creation provider section.

### Exit Conditions

- [] `./gradlew :core:test :auth:test :plugin-api:test` passes.
- [] `./gradlew markdownFormat` run; `./gradlew check` passes with zero warnings.
- [] Creating a character through the flow no longer advances `world.rng` (covered by the test above).
- [] §4.6, §4.15, §4.19 and §5.12 describe `prepare` and the creation RNG as implemented.

---

## Phase 8: Harness `spawn_player(steps=)` and Fuzz Walker Support

### Objective & Scope

- **In Scope:** a headless step runner shared by the harness and the fuzz walker; `spawn_player(..., steps = {...})`;
  fuzz bots running every applicable step with `done`, failing the run on a step or `prepare` exception.
- **Out of Scope (do not implement these in this phase):** a bot-input hook on steps (rejected design); the heritage
  sample (Phase 9).

### Context Working Set

- **Read / Reference Only:** `CreationFlow.kt` (step semantics to mirror); Phase 7's `spawnPlayer(attempt=)` and
  `CreationAttempt.prepareError`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/CreationFlow.kt` (add the headless runner as a top-level function)
  - `harness/src/main/kotlin/gups/harness/Handles.kt` (`spawn_player`)
  - `harness/src/main/kotlin/gups/harness/FuzzWalker.kt` (bot spawn and respawn)
  - tests: `harness/src/test/kotlin/gups/harness/FuzzWalkerTest.kt`, the harness `spawn_player` tests (search
    `harness/src/test` for `spawn_player(` with `choices`), `core/src/test/kotlin/gups/core/world/CreationFlowTest.kt`
  - `docs/SPEC.md` (§4.12, §7.3)
- **Do Not Touch:** login paths, the interactive logic of `CreationFlow`, the samples.

### Specification & Decision Record Changes

- §4.12, after the `spawn_player(..., choices = ...)` bullet, add:

> `spawn_player(..., steps = {"stats": ["str +2", "done"]})` runs the creation provider's custom steps (§4.19) that
> apply, in order, giving each step its listed lines (none when the dict doesn't name it), then creates the character as
> the creation steps do: `starting_stats` draws from the attempt's generator (keyed by the player's name) and the
> provider's `prepare` runs before the player enters. The test fails if a named step doesn't apply, if a step isn't done
> after its lines, or if a step or `prepare` throws. Without `steps`, no step or `prepare` runs.

- §4.12, the `w` row of the harness table: `spawn_player(name, at, level, vitals, stats, skills, index, choices,
  gmcp=None, steps=None)`.
- §7.3, World smoke, the fuzz walker bullet: after "and a respawned bot keeps them." insert "After its answers, each bot
  runs every custom step that applies, answering only `done`, and is created through `prepare` (§4.19); a respawned bot
  runs them again with the same attempt, so it gets the same numbers." In the failure sentence, add "or any exception
  from a creation step or `prepare`".

### Implementation Blueprint

**Signatures & Types (`CreationFlow.kt`):**

```kotlin
sealed interface HeadlessSteps {
  data object Finished : HeadlessSteps
  data class Unfinished(val stepId: String) : HeadlessSteps
  data class Failed(val stepId: String, val error: Throwable) : HeadlessSteps
}

/**
 * Runs every applicable custom step of the provider for [attempt] without a session, feeding each
 * the lines [linesFor] gives it. Stops at the first step that isn't done after its lines.
 */
fun World.runStepsHeadless(attempt: CreationAttempt, linesFor: (stepId: String) -> List<String>): HeadlessSteps
```

**Algorithmic Steps & Invariants:**

1. `runStepsHeadless`: for each `creation.steps` in order, call `applies` (an exception → `Failed`) and skip the step if
   false. Call `show` once (an exception → `Failed`; output discarded). For each line, call `input` (an exception →
   `Failed`): `Done` → add the id to `attempt.finished` and go to the next step; `Stay` → next line. Lines exhausted
   without `Done` → `Unfinished(id)`. `back` and `cancel` are not interpreted here; they are just lines. When every step
   is done or skipped, return `Finished`.
2. Harness `spawn_player`: add `Param(name = "steps", named = true, defaultValue = "None", doc = "Lines for the creation
   provider's custom steps by step id, or None to skip the steps.")`. When it is not `None`:
   - Require a provider, else `evalFail("spawn_player(steps): world has no character creation provider")`.
   - After the existing choice validation, build `CreationAttempt(name, CreationAttempt.nameKey(name), 0, <world seed>)`
     with `answers` set to the validated choices.
   - Every named step id must exist and apply, else `evalFail("spawn_player(steps): step '<id>' doesn't apply")`.
   - Run `runStepsHeadless`. `Unfinished` → `evalFail("spawn_player(steps): step '<id>' isn't done after its lines")`;
     `Failed` → `evalFail("spawn_player(steps): step '<id>' failed: <message>")`.
   - Spawn with `spawnPlayer(..., attempt = attempt)` (overrides as today). If `attempt.prepareError` is set afterwards,
     `evalFail("spawn_player(steps): prepare failed: <message>")`.
3. Fuzz walker: keep a `MutableMap<String, CreationAttempt>` by bot name. After `drawBotChoices`, if the provider has
   steps, build the attempt (`nameKey`, count 0, answers = the drawn choices) and run `runStepsHeadless(attempt) {
   listOf("done") }`. `Failed` fails the run immediately with a message naming the step and the exception, and prints
   the rerun command as other failures do; `Unfinished` is fine. Spawn with `attempt = attempt`; a set
   `attempt.prepareError` fails the run the same way. On respawn, call `attempt.answersChanged()`, rerun the steps the
   same way and spawn with the same attempt (deterministic, so the same numbers).

### Test & Documentation Requirements

- `CreationFlowTest.kt`: `runStepsHeadless` returns `Finished`, `Unfinished` and `Failed` in the matching cases;
  non-applicable steps are skipped.
- Harness tests: `steps=` applies `prepare`'s values; an unknown or non-applicable step id fails; an unfinished step
  fails; a throwing step fails; a throwing `prepare` fails; without `steps`, `prepare` doesn't run and `world.rng` is
  used as before.
- `FuzzWalkerTest.kt`: with a provider whose step finishes on `done` and whose `prepare` sets a stat, bots have that
  stat; with a step whose `input` throws, the run fails and names the step.
- Regenerate API files (ground rule 7): `spawn_player` gains a parameter.

### Exit Conditions

- [] `./gradlew :core:test :harness:test :cli:test` passes.
- [] `./gradlew markdownFormat` run; `./gradlew check` passes with zero warnings.
- [] §4.12 and §7.3 describe `steps=` and the fuzz behaviour.

---

## Phase 9: Heritage Point-Buy Sample, Docs, and Finalization

### Objective & Scope

- **In Scope:** a point-buy `stats` step in `samples/heritage-plugin`; its unit and world tests; the heritage tutorial
  and `docs/PLUGINS.md` sections, including "Adding rolls with `attempt.rng`"; the CHANGELOG; marking the
  `DESIGN_NEEDED` entry completed.
- **Out of Scope (do not implement these in this phase):** any roll or reroll code in the sample or the engine (rolls
  are documentation only); engine features (if a bug turns up, fix it with a test, but add nothing new).

### Context Working Set

- **Read / Reference Only:** the APIs of Phases 1 to 8;
  `samples/heritage-plugin/src/main/kotlin/gups/sample/heritage/HeritagePlugin.kt` (the existing `setCreation` object);
  `template-world/rules/combat.star` `starting_stats` (players start at 10 in each of `str dex con int wis cha`).
- **Files to Modify / Create:**
  - `samples/heritage-plugin/src/main/kotlin/gups/sample/heritage/StatsStep.kt` (new; wire it into the existing
    `PluginCreation` object's `steps` and `prepare` in `HeritagePlugin.kt`)
  - `samples/heritage-plugin/src/test/kotlin/gups/sample/heritage/HeritagePluginTest.kt`, `HeritageWorldTest.kt`
  - `samples/heritage-plugin/docs/tutorials/02_character_creation_provider.md`,
    `samples/heritage-plugin/docs/tutorials/05_testing_creation_and_traits.md`, `samples/heritage-plugin/README.md`
  - `docs/PLUGINS.md`, `docs/CHANGELOG.md`, `docs/DESIGN_NEEDED.md`
- **Do Not Touch:** engine modules, except to fix a bug found here.

### Specification & Decision Record Changes

- None beyond what earlier phases applied. Re-read §4.19 and §5.12 against the final behaviour and fix any mismatch.

### Implementation Blueprint

The `stats` step is a teaching sample: keep it under about 120 lines and commented for students.

1. `id = "stats"`, `title = "Stats"`. Constants: `BUDGET = 6`; `FLOOR = -2` and `CAP = 4` (how far a single stat may
   move from its preview value); `STATS = listOf("str", "dex", "con", "int", "wis", "cha")`. Each +1 costs 1 point; each
   −1 refunds 1 point.
2. `attempt.state["spend"]` is a `Map<String, Int>` of deltas (absent means all zero).
3. `show`: one line per stat, such as ` Str 10 → 12 (+2)`, computed as `attempt.preview.baseStat(stat) + delta`; then
   `Points left: N. Type '<stat> +N', '<stat> -N', 'reset' or 'done'. Unspent points are lost.` The `gmcp` map is
   `{"budget", "left", "stats": [{"name", "base", "delta"}]}`.
4. `input`:
   - Parse `^(\w+)\s*([+-]\d+)$`, case-insensitive, matching the stat by a unique prefix of its name in `STATS`. Reject
     an unknown stat, a move past `FLOOR` or `CAP`, or overspending, with a `Stay` message.
   - `reset` clears the spend. `done` returns `Done`. `help` returns `Stay` with a short explanation. Anything else
     returns `Stay("Type '<stat> +N', '<stat> -N', 'reset' or 'done'.")`.
5. `summary`: `Str 12 Dex 10 Con 10 Int 13 Wis 10 Cha 10`, using the final values.
6. `prepare` (on the provider): for each non-zero delta, `world.setBaseStat(actor, stat, actor.baseStat(stat) + delta)`.
   Read `actor.baseStat` here, not the preview, so the result stays right even if `starting_stats` changes.

**Docs:**

- Tutorial `02_character_creation_provider.md`:
  - A new section "9. Custom Steps: Point-Buy" walking through the step, `attempt.state`, `attempt.preview`, `prepare`
    and the confirmation summary. Update the telnet transcript to include the stats step.
  - A new section "10. Adding Rolls with `attempt.rng`": prose plus a Kotlin sketch of 25 lines or fewer, not shipped
    code. Cover: draw from `attempt.rng(stream)` and keep the results in `state`; number the streams (`rng(0)` for the
    first roll, `rng(1)` for the first reroll, and so on) and keep a reroll count and limit in `state`; why `cancel`
    then `new`, `back` and reconnecting replay the same sequence (the seed depends only on the world seed, the account
    and its creation counter), so rerolls can't be farmed; apply the chosen numbers in `prepare` with `setBaseStat` and
    `setBaseMaxVital`; and a short design caveat that luck-based stats reward rerolling over playing, which is why the
    engine and the sample don't ship them.
- Tutorial `05_testing_creation_and_traits.md`: an example harness test using `spawn_player(..., choices = {...}, steps
  = {"stats": ["str +2", "dex -1", "done"]})` that asserts the base stats.
- `docs/PLUGINS.md`, "Creation provider": subsections "Custom steps", "`prepare`" (extend Phase 7's text if needed), and
  "Adding rolls with `attempt.rng`" (a shorter version of the tutorial section that points to it). Fences at column 0,
  outside lists.
- `samples/heritage-plugin/README.md`: one bullet for the point-buy step.
- `CHANGELOG.md`, under `### Custom creation steps and base values`, add:
  - "Creation providers can declare custom steps (`PluginCreationStep`) shown after the choices, with a per-attempt
    seed, random streams, shared state and a preview actor, and a `prepare` callback that runs before a new character
    enters the world."
  - "`spawn_player(steps = {...})` runs a world's custom creation steps in tests."
  - "`starting_stats` draws from a per-attempt generator while a character is created through the creation steps."
- `DESIGN_NEEDED.md`: under `### Rerolls and point-buy at character creation`, add a first line `**Status: completed in
  Milestone 42.**` followed by one sentence: "The engine provides custom creation steps, a per-attempt seed and preview,
  a `prepare` callback and base-value setters; point-buy is a plugin feature (demonstrated in the heritage sample) and
  rolls are left to plugins." Do not delete the entry.

### Test & Documentation Requirements

- `HeritagePluginTest.kt` (fake facades, as the existing tests use): parsing (`str +2`, `STR+1`, `wis -1`, the prefix
  `co +1`); overspending refused; cap and floor refused; `reset`; `done`; the summary text; `prepare` calls
  `setBaseStat` with the right values.
- `HeritageWorldTest.kt`: in the fixture world, create an elf mage with `steps = {"stats": ["str +2", "int +3",
  "done"]}` (a `*_test.star` or Kotlin, matching the file's existing style) and check base `str` 12 and base `int` 13,
  with effective `int` including the mage trait's +2. If the file already drives an interactive creation, check that the
  stats text and the summary appear in the confirmation.
- End-to-end check: with the template world and the heritage plugin jar (following the existing `HeritageWorldTest`
  setup), run the fuzz walker for a few hundred pulses and confirm it passes.

### Exit Conditions

- [] `./gradlew :samples:heritage-plugin:test` passes.
- [] `./gradlew markdownFormat` run; code blocks kept their indentation.
- [] `./gradlew check` passes with zero warnings from a clean build.
- [] `docs/DESIGN_NEEDED.md` marks the entry completed without deleting it.
- [] SPEC §3.5, §3.6, §4.6, §4.10, §4.12, §4.15, §4.16, §4.19, §5.12 and §7.3 describe exactly what the code does. Grep
  SPEC for "then a confirmation" and for `"step": "choice"/"confirm"` and confirm the old wording is gone.
