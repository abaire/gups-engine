# Milestone 39: Standing, Factions, and Grudges

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, heading `### Hostility and factions` (under "Mobs and AI").
- **Design Summary:**
  1. **Standing is an integer from -100 (absolute hatred) to 100 (beloved ally).** It answers "what does this holder
     think of this subject". Behaviors, shops, dialogue and any other script may read it. The engine supplies the
     mechanism and a default formula; worlds and plugins set policy.
  2. **New feature module `standing`** (§3.10), with no requirements. Like the other features it is on when a world's
     `MODULE.gups` leaves `features` out. With it off, nothing in this milestone exists: the declarations, `mob`
     argument, rules exports, verbs, behavior arguments, events, saved records and plugin calls all refuse or do
     nothing, following the existing feature-ownership pattern.
  3. **Factions are labels.**
     - **Implicit factions:** every mob prototype is a faction whose label is the prototype's label
       (`//zones/caves:goblin_scout`). It needs no declaration.
     - **Declared factions:** a new content declaration `faction(name, standing = {...}, doc = "")` declares the faction
       `//<package>:<name>`. `standing` maps other faction labels (declared or implicit, written as label references
       with the usual `:name` / `//pkg:name` rules) to that faction's **declared** opinion of them, an int in -100..100.
       Opinions are one-directional. A `faction` declaration whose label equals a mob prototype's label in the same
       package configures that prototype's implicit faction rather than conflicting with it.
     - **Membership:** `mob(factions = [...])` lists faction labels a mob prototype belongs to. When absent, the mob
       belongs to exactly its implicit faction. A player belongs to no faction. The optional rules export
       `factions(ctx)` overrides membership for any actor (for example from the `race` choice, or from a pet's owner),
       and plugins adjust the list after it (`addFactions`).
     - A faction label in `mob(factions=)` or a `faction(standing=)` key that resolves to neither a declared faction nor
       a mob prototype is a load error with a "did you mean" suggestion.
  4. **Standing records.** The engine records adjustments; it never invents them:
     - **Personal record:** a mob's own opinion of one actor (`holder` a mob, `subject` an actor). Stored on the mob and
       saved with it, so it survives restarts and travels with a pet stored with its owner; it dies with the mob.
     - **Faction record:** a faction's opinion of one actor (`subject` an actor) or of another faction (`subject` a
       faction label). Stored on the world and saved in a new `standings` table. Player `EntityId`s are stable across
       logins, so logging out does not clear a grudge.
     - Each record holds an `amount` clamped to -100..100, the pulse of its last change, and a **history** of changes
       `(pulse, amount, event, via)` where `via` is the mob whose event produced the change (or none). History older
       than the world-schema setting `standing_history_pulses` is pruned.
  5. **Default standing formula** (used when the world defines no `standing` export, and offered to it as parts): for
     holder actor `H` and subject actor `S`,
     - `declared` = sum over `h` in factions(H), `s` in factions(S) of faction `h`'s declared opinion of `s`;
     - `faction_to_actor` = sum over `h` in factions(H) of the faction record `h → S`;
     - `faction_to_faction` = sum over `h` in factions(H), `s` in factions(S) of the faction record `h → s`;
     - `personal` = `H`'s personal record of `S` when `H` is a mob, else 0;
     - `total` = clamp(sum of the four, -100, 100). The default `standing` is `total`.
  6. **Rules exports (all optional):**
     - `standing(ctx) -> int`: `ctx.actor` is the holder, `ctx.target` the subject, `ctx.standing_parts` the struct of
       the five values above. The result is clamped to -100..100. Plugins adjust it after (`addStandingModifier`).
     - `standing_change(ctx) -> list[standing_delta]`: called for each **standing event**; returns explicit changes.
       `standing_delta(holder, subject, amount)` is a new constructor: `holder` is a mob (its personal opinion) or a
       faction label; `subject` is an actor or a faction label; `amount` an int.
     - `factions(ctx) -> list[str]`: the factions of `ctx.actor`.
  7. **Standing events.** `ctx.actor` is the actor affected, `ctx.target` the actor who acted. The built-in events are
     listed in [Standing events](#standing-events) below. Worlds and plugins raise their own (theft, quests, bounties,
     donations) with `ctx.standing_event(...)` / `PluginWorld.raiseStandingEvent(...)`; they go through the same export.
     **Pet proxy:** when `ctx.target` is a mob with an in-world owner, the event is raised a second time with
     `ctx.target` the owner and `ctx.pet` the pet.
  8. **Default deltas** (when `standing_change` is not defined, or errors) are listed in
     [Default deltas](#default-deltas) below. The engine applies whatever deltas are returned and imposes no caps.
     **Rate caps are rules policy**, built on the history query `standing_history(holder, subject, since = 0, via =
     None, event = None) -> struct(gained, lost)`, available on the rules ctx and on the script ctx. This closes
     "strike, flee, let a friend heal it, repeat": a world caps how much any single mob (`via = ctx.actor`) or any
     period can raise a faction's opinion. The default `heal`/`help` deltas never raise a personal record above 25, so
     with no export a buff-spammer can't reach a `guard(standing_above = 50)` threshold.
  9. **Behaviors** take optional standing thresholds (data, value equality kept; default `None` = today's behavior). The
     mob is the holder, each candidate that already matches the behavior's filter the subject:
     - `aggressive(..., standing_below=None)` and `pursue(..., standing_below=None)`: only candidates with standing
       strictly below.
     - `assist(..., standing_above=None)`: only allies with standing strictly above, and never a foe with standing
       strictly above.
     - `guard(..., standing_above=None)`: also lets through any actor with standing strictly above.
     - `follow(..., standing_above=None)`: takes and keeps a leader only while its standing is strictly above.
     - The owned-mob safety rule (never targeting the owner's side) is unchanged and still absolute.
  10. **Script verbs and queries (hook ctx):** `ctx.standing(holder, subject) -> int`, `ctx.factions_of(actor)`,
      `ctx.standing_history(...)`, `ctx.standing_event(mob_or_actor, actor, event, amount = 0, item = None)`,
      `ctx.adjust_standing(holder, subject, amount)` (direct write, history event `"adjust"`, no `via`), and
      `ctx.clear_standing(holder, subject = None)`.
  11. **Plugins:** `addStandingModifier`, `addFactions`, and `PluginWorld.standing`, `factionsOf`, `raiseStandingEvent`,
      `adjustStanding`.
  12. **Cost:** `factions` results are cached per actor and forgotten wherever `actor_slots`' cache is forgotten (plus a
      choices change); `standing` results are memoized per (holder, subject) for the current pulse and dropped at the
      next pulse or on any record change.
- **Specification Impact:** §3.3 (mob state), §3.6 (persistence), §3.9 (`standing_history_pulses`), §3.10 (feature table
  and intro), §4.2 (`faction`, `mob(factions=)`), §4.5 (verbs, queries, rules-ctx-free constructors), §4.6 (three
  exports, rules ctx fields), §4.7 (behavior thresholds), §4.15 (plugin seams), a new §5.14 "Standing and Factions",
  §7.4 (a new M39 row).
- **Decision Record Updates:** None. D-027 ("hostile" means "engaged with") and D-121 (behavior filters) are already
  folded into the spec and remain true; standing is a separate concept from engagement.

### Standing events

| `ctx.event` |                                                     Raised when                                                     |          Extra ctx          |
|-------------|---------------------------------------------------------------------------------------------------------------------|-----------------------------|
| `"attack"`  | a fresh engagement starts in which `ctx.target` is the initiator (once per engagement, not per swing)               | —                           |
| `"kill"`    | `ctx.actor` dies and has a killer                                                                                   | —                           |
| `"heal"`    | `ctx.target` heals `ctx.actor` (not itself) for an effective amount above 0                                         | `amount`                    |
| `"help"`    | `ctx.target` applies a helpful (non-bane) affect to `ctx.actor` (not itself) while `ctx.actor` is engaged in combat | `affect`                    |
| `"assist"`  | `ctx.target` heals or helps `helped`, an actor engaged with `ctx.actor`                                             | `helped`, `amount`/`affect` |
| `"give"`    | `ctx.target` gives `ctx.actor` an item                                                                              | `item`, `amount` (count)    |

### Default deltas

|        Event         |                                                             Default deltas                                                              |
|----------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `attack`             | `standing_delta(actor, target, -50)` (personal; only when `ctx.actor` is a mob)                                                         |
| `kill`               | for each `f` in factions(actor): `standing_delta(f, target, -50)`, and for each `g` in factions(target): `standing_delta(f, g, -5)`     |
| `heal`, `help`       | `standing_delta(actor, target, 5)` (personal; only when `ctx.actor` is a mob), reduced so it never raises that personal record above 25 |
| `assist`             | `standing_delta(actor, target, -10)` (personal; only when `ctx.actor` is a mob)                                                         |
| `give`, world events | none                                                                                                                                    |

---

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first and see them fail.
   - No spec section numbers or decision ids in comments, Kotlin or Starlark.
   - All Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. The engine is unreleased: no migrations, no
   format-version bumps, no back-compat shims. A new table is simply created in the schema; a new payload field gets a
   default value.
3. Add CHANGELOG bullets to a new `### Standing and factions` subsection at the top of `## api_version 1 (unreleased)`
   in `docs/CHANGELOG.md`, above the current first subsection. Create it in Phase 1 and have each later phase append.
4. In any phase that changes the script API, the plugin API or template-world content, regenerate the API files before
   the phase's final `./gradlew check` if any golden or API test fails:
   - Run `./gradlew :cli:test -Dgups.updateGolden=true`. This rewrites `cli/src/test/resources/api.golden.json` and the
     `cli/src/test/resources/docs-golden/` files.
   - Run `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout. This rewrites `docs/api/api-1.json`.
   - Read the diff. Only the symbols that phase names may change. `ApiDiffTest` must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing entry (a world-level `module(...)`
     setting example uses that file's `module = "..."` field, as `level_mode` does).
5. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation.
6. Spec text in this milestone is approved by the maintainer through approval of this milestone; edit `docs/SPEC.md`
   exactly as each phase says and nowhere else. Where a phase gives the meaning but not exact wording, write it in the
   style of the surrounding text and keep it short.
7. Locate code by the symbol names given, not by line numbers: the files drift. Where a phase says "mirror", open the
   named precedent first and copy its structure. Line numbers in parentheses are only hints.
8. **Feature ownership** (`script/src/main/kotlin/gups/script/Ownership.kt`, `object FeatureOwnership`). Every symbol
   this milestone adds belongs to `Feature.STANDING`. The behavior arguments are owned like
   `"aggressive.unless_level_above"` is owned by `LEVELING` (the behavior itself keeps its own feature). Record the new
   feature's row in the §3.10 table as each phase adds symbols.
9. Every new Starlark view class (a Kotlin class annotated `@StarlarkBuiltin`) must be added to `ScriptApi.VIEW_CLASSES`
   in `script/src/main/kotlin/gups/script/ApiModel.kt`.
10. Context budget. `docs/SPEC.md`, `Declarations.kt`, `Ctx.kt`, `Rules.kt` (script), `Views.kt`, `KernelCommands.kt`,
    `Combat.kt` and `Codec.kt` are each long. Never read them whole. `grep -n` for the symbol or quoted text a step
    names, then read a window of about 80 lines around it.
11. In Starlark, every engine verb and query is a method on `ctx`. Constructors (`standing_delta`, `faction`) are
    top-level builtins.
12. Standing values are always ints clamped to -100..100 at every boundary the design names: record amounts after each
    change, the default `total`, and the export result.

---

## Phase 1: The `standing` feature, `faction()` declarations, and `mob(factions=)`

### Objective & Scope

- **In Scope:**
  - `Feature.STANDING` (`"standing"`, no requirements) and its place in "leaving `features` out turns all on".
  - `FactionProto` and `Prototypes.factions`; the `faction(...)` builtin; `mob(factions = [...])` stored on `MobProto`.
  - Label resolution and load errors for faction references (with suggestions).
  - `module(standing_history_pulses = 14400)` (int ≥ 1; needs `standing`; not recorded per database).
- **Out of Scope:** *(the agent must not attempt any of these here)* records, the standing formula, rules exports,
  events, verbs, behaviors, persistence, plugins.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Features.kt` (`enum class Feature`).
  - `script/src/main/kotlin/gups/script/Declarations.kt`: the `region` builtin (`REGION_SPEC`, `declName`,
    `ctx.pkg.regionList`) as the pattern for a new declaration kind; the `mob` builtin (grep `name = "mob"`) for how
    `traits` (a list of names) is parsed and stored, and how a label is formed from `declName` for a mob.
  - `script/src/main/kotlin/gups/script/Loader.kt` (grep `regions = ps.flatMap`): how package declarations reach
    `Prototypes`.
  - `script/src/main/kotlin/gups/script/Linter.kt` (grep `declaredRegions`): how a cross-package reference is checked
    after loading, and how suggestions are produced.
  - `script/src/main/kotlin/gups/script/WorldModule.kt` (grep `level_mode`): a feature-gated `module(...)` setting.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Features.kt`
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`FactionProto`, `Prototypes.factions`, `MobProto.factions`)
  - `core/src/main/kotlin/gups/core/world/World.kt` (`standingHistoryPulses`)
  - `script/src/main/kotlin/gups/script/Declarations.kt`, `Loader.kt`, `Linter.kt` (or wherever load-time label checks
    live), `WorldModule.kt`, `Ownership.kt`
  - Tests beside the existing `region` and `level_mode` tests in `script/src/test/kotlin/gups/script/`.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, regenerated API files.
- **Do Not Touch:** `Combat.kt`, `Behaviors.kt`, `persist/`, `plugin-api/`, `template-world/`.

### Specification & Decision Record Changes

- §3.10 intro: "a kernel plus four feature modules" → "five"; the example list gains `"standing"`; "leaving `features`
  out turns all four on, and listing all four" → "all five". Add a table row: `standing` | "Factions and standing: the
  `faction` declaration, `mob(factions=)`, `standing_history_pulses`" (later phases append to this cell) | —.
- §3.9: after the `level_mode` bullet add: "**`standing_history_pulses`:** how long the engine keeps the history of
  standing changes (§5.14), in pulses; default 14400 (one hour). An int of at least 1. It needs the `standing` feature,
  and isn't recorded per database."
- §4.2: add a `faction` declaration entry beside the other content declarations: "`faction(name, standing = {}, doc =
  "")` declares the faction `//<package>:<name>` (§5.14). `standing` maps faction labels to this faction's declared
  opinion of them, ints in -100..100. A label is a declared faction or a mob prototype (each mob prototype is a faction
  of its own). A `faction` with the same label as a mob prototype of its package configures that prototype's faction.
  Needs `standing`." In the `mob(...)` argument list add "`factions = None`: faction labels the mob belongs to; when
  absent, its own prototype label (§5.14)."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Features.kt
STANDING("standing", emptySet()),

// Protos.kt
data class FactionProto(
  val label: Label,
  /** Declared opinion of other factions, by faction label, each in -100..100. */
  val standing: Map<Label, Int> = emptyMap(),
  val doc: String = "",
)
// MobProto: new field
val factions: List<Label>? = null,
// Prototypes: new constructor parameter and map
factions: List<FactionProto> = emptyList(),
val factions: Map<Label, FactionProto> = unique("faction", factions) { it.label }
/** True when [label] names a declared faction or a mob prototype's implicit faction. */
fun isFaction(label: Label): Boolean = label in factions || label in mobs

// World.kt
var standingHistoryPulses: Long = 14_400
```

- **Algorithmic Steps & Invariants:**
  1. `faction` builtin: `contentCtx(thread, "faction")`, `need(Feature.STANDING)` as other feature-gated builtins do;
     form the label exactly as `mob` forms its label from `declName`; parse `standing` as a dict of string → int,
     resolving each key as a label reference relative to the package (same rules as other label references); reject
     values outside -100..100 ("faction(): standing of <label> must be in -100..100"); append to a new
     `ctx.pkg.factionList`.
  2. `mob(factions=)`: list of label strings, resolved relative to the package; empty list allowed (a mob in no
     faction). Needs `standing` (feature-owned argument `"mob.factions"`).
  3. Loader passes all packages' factions into `Prototypes`.
  4. After all packages load, check every `MobProto.factions` entry and every `FactionProto.standing` key with
     `isFaction`; an unknown one is a load error naming the package/file, with a suggestion from known faction labels
     (mirror the region-reference check and its suggestion helper).
  5. `standing_history_pulses`: mirror `level_mode` in `WorldModule.kt` (parse, feature check, pass to `World`).
  6. `FeatureOwnership`: `owns(Feature.STANDING, "faction", "mob.factions", "module.standing_history_pulses")` (use the
     symbol spellings the API model produces; check `api.golden.json` for the exact form of a builtin and a module
     argument).

### Test & Documentation Requirements

- `faction("goblins", standing = {":orcs": -80})` with `faction("orcs")` loads; `Prototypes.factions` has both with the
  right labels; a cross-package `//zones/x:goblin` key resolves to a mob prototype.
- Unknown faction key and unknown `mob(factions=)` entry: load errors with suggestions. Out-of-range standing: error.
- `faction(...)` and `mob(factions=)` in a world without `standing`: refused like other feature-owned symbols.
- `standing_history_pulses = 0` is an error; default is 14400.
- CHANGELOG bullets for the feature, `faction`, `mob(factions=)` and `standing_history_pulses`.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] The tests above pass; existing `region`/`mob` declaration tests still pass.

---

## Phase 2: Standing records and the default formula in core

### Objective & Scope

- **In Scope:** the record model, personal records on `Mob`, faction records on `World`, history and pruning,
  `factionsOf` (default only), the default `standing` computation with parts, applying deltas, and the history query.
  All in core, with Kotlin unit tests.
- **Out of Scope:** *(the agent must not attempt any of these here)* rules exports, events, Starlark verbs, behaviors,
  persistence, plugins.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Contributions.kt` (style of a world-extension subsystem file with a transient
    per-actor map; `ownerOf`).
  - `core/src/main/kotlin/gups/core/model/Entities.kt`: `class Mob` (grep `var leader`), `contributions` on `Actor`.
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (Phase 1 additions).
  - The world pulse loop (grep `fun World.pulse` or `pulse++` in `core/src/main/kotlin/gups/core/world/`) for where to
    clear the per-pulse memo.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Standing.kt` (new: model types)
  - `core/src/main/kotlin/gups/core/world/Standing.kt` (new: world functions)
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Mob.personalStanding`)
  - `core/src/main/kotlin/gups/core/world/World.kt` (`factionStanding`, memo, factions cache)
  - the pulse loop file (clear the memo each pulse)
  - `core/src/test/kotlin/gups/core/world/StandingTest.kt` (new)
  - `docs/SPEC.md` (§3.3, new §5.14), `docs/CHANGELOG.md`
- **Do Not Touch:** `script/`, `persist/`, `plugin-api/`, `Behaviors.kt`, `Combat.kt`.

### Specification & Decision Record Changes

- §3.3 "Mob-specific state" bullet: add "`personalStanding` (the mob's own standing records, saved, §5.14)".
- Add a new section `### 5.14 Standing and Factions` after §5.13, with these bullets (later phases append):
  - "**Standing** is what a holder thinks of a subject, an int from -100 (absolute hatred) to 100 (beloved ally). It is
    the `standing` feature. Implemented in [Standing.kt](../core/src/main/kotlin/gups/core/world/Standing.kt)."
  - "**Factions** are labels: each mob prototype is a faction of its own, and `faction(...)` (§4.2) declares others. A
    mob belongs to its `factions`, or else to its prototype's faction; a player to none. The `factions` rules export
    (§4.6) can say otherwise."
  - "**Records.** The engine keeps a mob's personal records (its opinion of one actor; saved with the mob, gone when it
    dies) and faction records (a faction's opinion of one actor or of another faction; saved with the world). A record's
    amount is clamped to -100..100. Each change is kept in the record's history with its pulse, amount, event and the
    mob whose event caused it, for `standing_history_pulses` (§3.9) and at most 1024 entries per record, oldest dropped
    first."
  - "**Default standing** of holder H toward subject S is the sum, clamped to -100..100, of: `declared` (each faction of
    H's declared opinion of each faction of S), `faction_to_actor` (each faction of H's record of S),
    `faction_to_faction` (each faction of H's record of each faction of S) and `personal` (H's personal record of S, if
    H is a mob)."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// model/Standing.kt
sealed interface StandingSubject {
  data class Of(val actor: EntityId) : StandingSubject
  data class Faction(val label: Label) : StandingSubject
}

data class StandingChange(val pulse: Long, val amount: Int, val event: String, val via: EntityId?)

class StandingRecord {
  var amount: Int = 0          // always clamped to -100..100
  var updatedPulse: Long = 0
  val history: ArrayDeque<StandingChange> = ArrayDeque()
}

data class StandingParts(
  val declared: Int,
  val factionToActor: Int,
  val factionToFaction: Int,
  val personal: Int,
) { val total: Int get() = (declared + factionToActor + factionToFaction + personal).coerceIn(-100, 100) }

data class StandingTotals(val gained: Long, val lost: Long) // lost is a non-negative sum of losses

const val STANDING_MIN = -100
const val STANDING_MAX = 100
const val STANDING_HISTORY_MAX = 1024

// Entities.kt, class Mob
internal val personalStanding: java.util.TreeMap<EntityId, StandingRecord> = java.util.TreeMap()

// World.kt
internal val factionStanding: MutableMap<Pair<Label, StandingSubject>, StandingRecord> = HashMap()
internal val standingMemo: MutableMap<Pair<EntityId, EntityId>, Int> = HashMap()
internal val factionCache: MutableMap<EntityId, List<Label>> = HashMap()

// world/Standing.kt
/** A holder of standing: a mob's personal opinion, or a faction's. */
sealed interface StandingHolder {
  data class Personal(val mob: Mob) : StandingHolder
  data class Faction(val label: Label) : StandingHolder
}

fun World.factionsOf(a: Actor): List<Label>          // cached in factionCache; Phase 3 adds the rules export
fun World.forgetFactions(a: Actor)
fun World.standingParts(holder: Actor, subject: Actor): StandingParts
fun World.standing(holder: Actor, subject: Actor): Int // Phase 3 wraps with the export; memoized
fun World.factionStanding(holder: Label, subject: StandingSubject): Int // record amount (+ declared when subject is a faction)
fun World.applyStanding(holder: StandingHolder, subject: StandingSubject, amount: Int, event: String, via: Mob?)
fun World.clearStanding(holder: StandingHolder, subject: StandingSubject?)
fun World.standingHistory(
  holder: StandingHolder, subject: StandingSubject, since: Long, via: EntityId?, event: String?,
): StandingTotals
internal fun World.pruneStanding()                    // drops history older than standingHistoryPulses, and empty records
```

- **Algorithmic Steps & Invariants:**
  1. `factionsOf(a)`: if `Feature.STANDING` is off → `emptyList()`. Players → `emptyList()`. Mobs → `a.proto.factions ?:
     listOf(a.proto.label)`. Cache the result in `factionCache` by id.
  2. `forgetFactions(a)`: remove `a.id` from `factionCache` and clear `standingMemo`. Call it next to every existing
     call of `forgetActorSlots(...)` (grep the call sites: `Restore.kt`, `Reconcile.kt`, `Verbs.kt`), and wherever a
     player's `choices` change (grep `setChoices` in core). On reload, clear `factionCache` entirely.
  3. `standingParts(H, S)`: compute the four sums as in Design Summary item 5, using `protos.factions[h]?.standing[s]`
     for `declared` (0 when absent), `factionStanding[h to Of(S.id)]?.amount` and `factionStanding[h to Faction(s)]
     ?.amount`, and `(H as? Mob)?.personalStanding[S.id]?.amount`.
  4. `standing(H, S)`: memoized in `standingMemo[(H.id, S.id)]`; default value `standingParts(H, S).total`. `H === S`
     returns `STANDING_MAX`.
  5. `applyStanding`: `amount == 0` → no-op. Find or create the record (personal: `mob.personalStanding[subject.actor]`
     — a personal record's subject must be `StandingSubject.Of`, else `IllegalArgumentException`); add the amount,
     clamp, set `updatedPulse = pulse`; append `StandingChange(pulse, amount, event, via?.id)`; drop entries older than
     `pulse - standingHistoryPulses` and trim to `STANDING_HISTORY_MAX` from the front; clear `standingMemo`.
  6. `clearStanding`: remove one record (subject given) or all records of the holder; clear `standingMemo`.
  7. `standingHistory`: over the record's history entries with `pulse >= since`, matching `via`/`event` when given, sum
     positive amounts into `gained` and the absolute values of negative amounts into `lost`. A missing record → `(0,
     0)`.
  8. `pruneStanding`: for every faction record and every in-world mob's personal records, drop expired history; remove a
     record whose `amount == 0` and whose history is empty.
  9. Clear `standingMemo` at the start of every pulse.

### Test & Documentation Requirements

- `StandingTest.kt`: implicit faction default; `mob(factions=)` membership; players in no faction; each of the four
  parts in isolation and combined; clamping of records and of `total`; `applyStanding` history, pruning by age and by
  the 1024 cap; `standingHistory` filters (`since`, `via`, `event`) and `gained`/`lost` separation; memo cleared by a
  change; `H === S` → 100; feature off → empty factions and standing 0.
- CHANGELOG bullet describing standing records and the default formula.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `StandingTest` passes.

---

## Phase 3: The `factions`, `standing` and `standing_change` rules exports

### Objective & Scope

- **In Scope:**
  - Three optional rules exports and their rules ctx fields (`standing_parts`, `amount`, `helped`, `pet`; reuse the
    existing `event`, `item`, `affect`).
  - The `standing_delta(holder, subject, amount)` constructor, callable only inside `standing_change`.
  - Rules-ctx queries `ctx.factions_of(actor)` and `ctx.standing_history(...)`.
  - Core `raiseStandingEvent(...)` with default deltas, delta validation and application, and the pet-proxy rule.
  - Load-time dry runs.
- **Out of Scope:** *(the agent must not attempt any of these here)* wiring built-in events into combat/heal/give (Phase
  4), hook-ctx verbs (Phase 5), behaviors, persistence, plugins.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt`: `class RulesContext`, `fun ownedBehaviors`, `fun xpShare`.
  - `script/src/main/kotlin/gups/script/Rules.kt`: `enum RulesExport` (`XP_SHARE`, `OWNED_BEHAVIORS`), `override fun
    ownedBehaviors` (the `defines(...)` check and the thread-local marker that lets behavior constructors run inside the
    export — mirror it for `standing_delta`), how `contributors` is exposed on the rules ctx, and the dry runs (grep
    `RulesExport.OWNED_BEHAVIORS` near line 1169 and 1595).
  - `docs/MILESTONE_38.md` Phase 3, as a worked precedent for an optional export.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt`
  - `core/src/main/kotlin/gups/core/world/Standing.kt`
  - `script/src/main/kotlin/gups/script/Rules.kt`, `Declarations.kt` (the `standing_delta` builtin), `Ownership.kt`
  - Tests in `script/src/test/kotlin/gups/script/` (mirror the `owned_behaviors` tests) and `StandingTest.kt`.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, regenerated API files.
- **Do Not Touch:** `Combat.kt`, `KernelCommands.kt`, `Behaviors.kt`, `persist/`, `plugin-api/`.

### Specification & Decision Record Changes

- §4.6 intro: "Two exports are optional: ..." → "Five exports are optional: `xp_share`, whose default pays the killer
  the whole pool, `owned_behaviors`, whose default is described in §4.7, and `factions`, `standing` and
  `standing_change`, whose defaults are described in §5.14."
- §4.6 rules ctx paragraph: add "`standing_parts` (in `standing`, a struct with `declared`, `faction_to_actor`,
  `faction_to_faction`, `personal` and `total`), `amount` (in `standing_change`, the healing done or the count given;
  otherwise `None`), `helped` (in an `assist` standing event, the actor helped), `pet` (in a standing event raised for a
  pet's owner, the pet)", and among the queries: "`factions_of(actor)` and `standing_history(...)` (§4.5)".
- §4.6 table rows (Feature `standing`):
  - `factions(ctx)` | "`list[str]`, the faction labels of `ctx.actor` (§5.14)."
  - `standing(ctx)` | "Int in -100..100: what `ctx.actor` thinks of `ctx.target`. `ctx.standing_parts`."
  - `standing_change(ctx)` | "List of `standing_delta(...)`: the changes a standing event makes. `ctx.event`,
    `ctx.target`, and the event's fields (§5.14)."
- §4.6: add a paragraph after `owned_behaviors`: "**Standing exports** (§5.14): `factions` is cached per actor until its
  traits, affects, level or choices change, a reload or a restore. `standing` is asked at most once per holder and
  subject in a pulse; its result is clamped. `standing_change` returns at most 32 deltas. `standing_delta(holder,
  subject, amount)` may be called only inside it: `holder` is a mob (its personal opinion) or a faction label, `subject`
  an actor or a faction label, and a personal opinion's subject must be an actor. A rules error in `factions` or
  `standing` keeps the default; in `standing_change` it applies the default deltas. Inside `standing`, `ctx.standing` is
  not available."
- §5.14: add the standing events table and the default deltas table from the Design Summary (items 7 and 8), the
  pet-proxy sentence, and: "The engine applies the returned deltas and caps nothing itself; a world limits how fast
  standing moves by reading `standing_history`."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// RulesContext: new trailing constructor parameters
val standingParts: StandingParts? = null,
val amount: Long? = null,
val helped: Actor? = null,
val pet: Actor? = null,

// RulesEngine (core rules interface), beside ownedBehaviors; null means "use the default"
fun factions(c: RulesContext): List<Label>? = null
fun standing(c: RulesContext): Int? = null
fun standingChange(c: RulesContext): List<StandingDelta>? = null

// core model
data class StandingDelta(val holder: StandingHolder, val subject: StandingSubject, val amount: Int)

// world/Standing.kt
data class StandingEvent(
  val event: String,
  val affected: Actor,
  val actor: Actor,
  val amount: Long? = null,
  val item: Item? = null,
  val affect: Affect? = null,
  val helped: Actor? = null,
)
fun World.raiseStandingEvent(e: StandingEvent)
internal fun World.defaultStandingDeltas(e: StandingEvent): List<StandingDelta>

val BUILTIN_STANDING_EVENTS = setOf("attack", "kill", "heal", "help", "assist", "give")
```

- **Algorithmic Steps & Invariants:**
  1. Script `RulesExport`: `FACTIONS("factions")`, `STANDING("standing")`, `STANDING_CHANGE("standing_change")`,
     optional and feature `standing`, everywhere `OWNED_BEHAVIORS` is special-cased as optional.
  2. `factions`: must return a list of strings; each is resolved as an absolute label (`//pkg:name`) or relative to the
     rules package, and checked with `protos.isFaction`; an unknown label is a rules error naming it. Core `factionsOf`
     calls `rules.factions(...)` first, falling back to the Phase 2 default on null.
  3. `standing`: return value must be an int; clamp. Core `standing(H, S)` builds `RulesContext(world, actor = H, target
     = S, standingParts = standingParts(H, S))` and uses `rules.standing(...) ?: parts.total`, still memoized.
  4. `standing_delta` builtin: only valid while the `standing_change` marker is set (mirror the owned-behaviors marker;
     other call sites fail with "standing_delta() can only be used in rules.standing_change"). It converts its arguments
     immediately: a mob view → `StandingHolder.Personal`; a player view as holder → error "standing_delta(): a player
     has no personal standing; name a faction"; a string → resolved faction label (`isFaction` checked); `subject` an
     actor view → `Of(id)` or a string → `Faction`; a personal holder with a faction subject → error. Returns an opaque
     `StandingDeltaValue` Starlark value wrapping `StandingDelta` (register it in `ScriptApi.VIEW_CLASSES` if it is a
     `@StarlarkBuiltin` class).
  5. `standing_change`: must return a list of `StandingDeltaValue`, at most 32; otherwise rules error → null (default).
  6. `raiseStandingEvent(e)`: no-op if `Feature.STANDING` is off, if `e.affected === e.actor`, or if either is not in
     the world. Run once for `e`; then, if `e.actor` is a `Mob` with `ownerOf(e.actor) != null` and that owner is not
     `e.affected`, run again with `actor = owner` and `pet = e.actor`. Each run: `RulesContext(world, actor = affected,
     target = actor, event, amount, item, affect, helped, pet)`; deltas = `rules.standingChange(ctx) ?:
     defaultStandingDeltas(e)`; apply each with `applyStanding(holder, subject, amount, e.event, via = affected as?
     Mob)`.
  7. `defaultStandingDeltas`: exactly the Design Summary item 8 table; the personal entries are skipped when `affected`
     is not a `Mob`. In the pet-proxy run the defaults are the same, with `target` the owner.
  8. Rules ctx queries `factions_of(actor)` (list of label strings) and `standing_history(holder, subject, since = 0,
     via = None, event = None)` → `struct(gained, lost)`: argument conversion as in step 4 (a mob or faction label as
     holder, an actor or faction label as subject, `via` a mob or `None`).
  9. Dry runs (only when `standing` is on and the export is defined): `factions` with the dry-run mob and a player;
     `standing` with both orders; `standing_change` once per built-in event with the dry-run mob affected and a player
     acting; check return types.
  10. `FeatureOwnership`: `"rules:factions"`, `"rules:standing"`, `"rules:standing_change"`, `"standing_delta"`, and the
      ctx fields.

### Test & Documentation Requirements

- Script tests: each export defined vs. absent; `ctx.standing_parts` values; `standing` returning 500 → clamped to 100;
  a non-int → default with a rules diagnostic; `factions` returning an unknown label → diagnostic, default kept;
  `standing_change` returning 33 deltas → default deltas applied; `standing_delta` outside the export → error; player as
  personal holder → error.
- Core tests (`StandingTest.kt`) for `raiseStandingEvent`: each built-in event's defaults; pet proxy (owner charged,
  `ctx.pet` set); self events ignored; feature off → no-op.
- CHANGELOG bullets for the exports, `standing_delta`, and the rules ctx fields.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] The tests above pass; existing `owned_behaviors` and `xp_share` tests still pass.

---

## Phase 4: Built-in standing events

### Objective & Scope

- **In Scope:** raising `attack`, `kill`, `heal`, `help`, `assist` and `give` from the engine paths named below.
- **Out of Scope:** *(the agent must not attempt any of these here)* hook-ctx verbs, behaviors, persistence, plugins,
  any change to engagement or the contribution ledger's own behavior.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Contributions.kt`: `recordHealing`, `recordSupport` (their bane/help decision
    is the one to mirror).
  - `core/src/main/kotlin/gups/core/world/Combat.kt`: `startCombat` (the `fresh` branch and the owner-coupling call
    `startCombat(owner, foe)`), `World.heal`, the `recordSupport` call in the affect-application function,
    `processDeath` / `runDeath` (where `on_death` hooks fire and where the killer is determined).
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt`: `class GiveAction` (`resolve`, after `moveItem`).
  - Callers of `startCombat` (all pass the initiator first): `SkillCommands.kt`, `Procs.kt`, `CombatCommands.kt`,
    `Combat.kt`, `script/.../Ctx.kt` (`startCombat`).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Combat.kt`
  - `core/src/main/kotlin/gups/core/world/Contributions.kt` (or the callers, whichever keeps the bane decision in one
    place)
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt` (`GiveAction` only)
  - `core/src/test/kotlin/gups/core/world/StandingEventsTest.kt` (new)
  - `docs/SPEC.md` (§5.14, §5.4 one sentence), `docs/CHANGELOG.md`
- **Do Not Touch:** `script/`, `persist/`, `plugin-api/`, `Behaviors.kt`.

### Specification & Decision Record Changes

- §5.4 "Engagement" bullets: append one sentence to the first bullet: "With the `standing` feature, a fresh engagement
  raises the `attack` standing event on the actor engaged, naming the actor that started it (§5.14)."
- §5.14: add "**When built-in events are raised.** `attack`: when an engagement starts, on the actor engaged, by the
  actor whose action started it (a `kill`, a hostile skill or primitive, a proc, a behavior, `ctx.start_combat`'s first
  argument); an owner drawn into its pet's fight raises nothing for that. `kill`: after the victim's `on_death` hooks,
  when it has a killer. `heal`: for each heal with a source other than the target whose effective amount is above 0.
  `help`: for each affect a source applies to another actor that counts as help, by the rule that decides bane or help
  for the contribution record (§5.4), only while that actor is engaged in combat; a buff outside combat changes no
  standing. `assist`: on each opponent of the actor healed or helped, other than the source. `give`: on the receiver,
  once per item moved by `give`."

### Implementation Blueprint

- **Signatures & Types:** no new public types. Add a private parameter to `startCombat` only if needed to skip the
  owner-coupling call, e.g. `internal fun World.startCombat(a: Actor, b: Actor, coupled: Boolean)` with the public
  `startCombat(a, b)` delegating with `coupled = false`.
- **Algorithmic Steps & Invariants:**
  1. `startCombat`: inside `if (fresh)`, after the existing perception and coupling code, and only when `!coupled`:
     `raiseStandingEvent(StandingEvent("attack", affected = b, actor = a))`. The coupling recursion passes `coupled =
     true`.
  2. `runDeath`: after the victim's `on_death` hooks and before the corpse, with the same `killer` the death already
     computes: if non-null and not the victim, `raiseStandingEvent(StandingEvent("kill", affected = victim, actor =
     killer))`. The victim is still in the world at that point (verify; if not, raise it at the last point where it is).
  3. `World.heal`: when `source != null && source !== target && effective > 0`: `raiseStandingEvent("heal", affected =
     target, actor = source, amount = effective)`; then for each `opponentsOf(target)` other than `source`:
     `raiseStandingEvent("assist", affected = opp, actor = source, amount = effective, helped = target)`. Place it after
     `recordHealing`.
  4. Affect application: where `recordSupport(target, source, hostile)` is called, compute `bane` the same way
     `recordSupport` does (extract a shared `private fun World.isBane(target, source, hostile): Boolean` so both use one
     rule). When not bane: `help` on `target` with `affect` **only if `target.isEngaged`** (a buff on an actor out of
     combat raises no `help`, so buff-spamming an idle mob earns nothing), and `assist` on each opponent of `target`
     other than `source`, with `affect` and `helped = target`.
  5. `GiveAction.resolve`: after the successful `moveItem` (after the messages), `w.raiseStandingEvent("give", affected
     = target, actor = actor, item = moved, amount = n)`.
  6. Order of events must be deterministic: opponents in ascending id.

### Test & Documentation Requirements

- `StandingEventsTest.kt` (default deltas, no rules exports):
  - A player `kill`s a mob → the mob's personal record of the player is -50 after the first engagement only (a second
    round adds nothing).
  - A mob's `aggressive` attack on a player raises `attack` on the player (no personal record; no error).
  - Pet attacks a mob → mob's personal record of the pet and of the owner are both -50; the owner-coupling engagement
    adds nothing more.
  - Killing a mob of faction F → faction record F → killer is -50, and F → each killer faction -5.
  - Healing a mob → +5 personal, and repeated heals never raise that record above 25; healing a player who is fighting a
    mob → that mob's personal record of the healer -10; healing a full-health mob → nothing.
  - A helpful affect on a groupmate fighting a mob → `assist` -10 on that mob; a bane → no `help`.
  - A helpful affect on a mob that is not engaged → no `help` event and no record; the same affect on an engaged mob →
    `help` +5.
  - `give` raises the event (no default change) — verify in core with a test double of the rules engine that records the
    `standing_change` calls (no `script/` changes in this phase).
- CHANGELOG bullet (behavior change: mobs now remember attackers when `standing` is on).

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `StandingEventsTest` passes; all existing combat, contribution and give tests still pass.

---

## Phase 5: Hook-ctx verbs and queries

### Objective & Scope

- **In Scope:** `ctx.standing`, `ctx.factions_of`, `ctx.standing_history`, `ctx.standing_event`, `ctx.adjust_standing`,
  `ctx.clear_standing` on the hook ctx.
- **Out of Scope:** *(the agent must not attempt any of these here)* behaviors, persistence, plugins, template world.

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`: `clearContributions` (a verb with an actor argument and an optional
    second one), `alliesNear` (a query), `startCombat` (argument conversion helpers `actorArg`), and how a verb is
    feature-gated.
  - Phase 3's argument conversion for holder/subject (reuse it; move it to a shared helper if it lives in `Rules.kt`).
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`, `Ownership.kt`, `DocExamples.kt`
  - Tests in `script/src/test/kotlin/gups/script/` (mirror `clear_contributions` tests)
  - `docs/SPEC.md` (§4.5), `docs/CHANGELOG.md`, regenerated API files
- **Do Not Touch:** `core/` (except a missing helper Phase 2/3 should have provided), `persist/`, `plugin-api/`.

### Specification & Decision Record Changes

- §4.5 verbs table, "Vitals and combat" row or a new "Standing" row: `standing_event(affected, actor, event, amount=0,
  item=None)`, `adjust_standing(holder, subject, amount)`, `clear_standing(holder, subject=None)`.
- §4.5 "Queries" row: add `standing(holder, subject) -> int`, `factions_of(actor) -> [str]`, `standing_history(holder,
  subject, since=0, via=None, event=None) -> struct(gained, lost)`.
- §4.5 bullets: "**Standing** (§5.14): `holder` is an actor or a faction label, `subject` an actor or a faction label.
  `standing` of two actors runs the `standing` export; with a faction label on either side it is the declared opinion
  plus the faction record (0 for a personal holder toward a faction). `standing_event` raises a standing event through
  `standing_change`; its `event` matches `[a-z][a-z0-9_]*` and is not a built-in event name. `adjust_standing` changes a
  record directly, with history event `"adjust"` and no mob; a personal record needs a mob holder and an actor subject.
  `clear_standing` removes one record, or with no subject every record of the holder."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Ctx.kt (StarlarkMethod names in snake_case)
fun standing(holder: Any, subject: Any): Any            // StarlarkInt
fun factionsOf(actor: Any): Any                         // list of str
fun standingHistory(holder: Any, subject: Any, since: Any, via: Any, event: Any): Any // struct(gained, lost)
fun standingEvent(affected: Any, actor: Any, event: Any, amount: Any, item: Any): Any // None
fun adjustStanding(holder: Any, subject: Any, amount: Any): Any                      // None
fun clearStanding(holder: Any, subject: Any): Any                                    // None
```

- **Algorithmic Steps & Invariants:**
  1. Holder conversion: actor view → if subject is an actor and holder is any actor, `standing` uses `World.standing`;
     for `adjust_standing`/`clear_standing`/`standing_history` a holder actor must be a mob (`StandingHolder.Personal`),
     else error "a player has no personal standing; name a faction". A string → faction label (relative to the calling
     package, `isFaction` checked, error with suggestion).
  2. `standing(holder, subject)` with a faction on either side: `World.factionStanding(label, subject)` (declared +
     record) for faction→faction or faction→actor; actor→faction returns the sum over the actor's factions of their view
     of that faction, clamped. Document these cases in the method doc.
  3. `standing_event`: event name check; reserved names error "standing_event(): '<name>' is a built-in event"; `amount`
     int ≥ 0; `item` an item view or `None`; calls `World.raiseStandingEvent`.
  4. `adjust_standing`: `amount` int; calls `applyStanding(..., event = "adjust", via = null)`.
  5. All six need `Feature.STANDING` (`FeatureOwnership` entries `"ctx.standing"`, etc.). They are not available on the
     rules ctx except `factions_of` and `standing_history` (Phase 3).

### Test & Documentation Requirements

- Starlark tests for each method: values, error messages, feature-off refusal, faction-label forms (relative and
  absolute), a world-defined `"theft"` event reaching `standing_change` with `ctx.event == "theft"` and `ctx.item`.
- A `DocExamples.kt` entry per new method.
- CHANGELOG bullets.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] The tests above pass.

---

## Phase 6: Standing thresholds on behaviors

### Objective & Scope

- **In Scope:** `standing_below` on `aggressive` and `pursue`; `standing_above` on `assist`, `guard` and `follow`; the
  builtins' arguments; behavior descriptors for the `addOwnedBehaviors` seam.
- **Out of Scope:** *(the agent must not attempt any of these here)* target preference ordering, any change to the
  owned-mob safety rule, persistence, other plugin seams.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Behaviors.kt` (all of it; it is short): `Aggressive`, `Assist`, `Guard`,
    `Follow`, `Pursue`.
  - `script/src/main/kotlin/gups/script/Declarations.kt`: builtins `aggressive`, `assist`, `guard`, `follow`, `pursue`
    (grep `name = "aggressive"` etc.); how `unless_level_above` is declared and feature-owned.
  - `core/src/main/kotlin/gups/core/world/BehaviorDescriptors.kt` (`descriptor`, `behaviorOf`).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Behaviors.kt`
  - `core/src/main/kotlin/gups/core/world/BehaviorDescriptors.kt`
  - `script/src/main/kotlin/gups/script/Declarations.kt`, `Ownership.kt`, `DocExamples.kt` if an example is required
  - Tests: the existing behavior tests in `core/src/test/kotlin/gups/core/world/` (grep `class BehaviorsTest` or
    similar), `BehaviorDescriptorsTest.kt`.
  - `docs/SPEC.md` (§4.7, §3.10 row), `docs/CHANGELOG.md`, regenerated API files.
- **Do Not Touch:** `Combat.kt`, `persist/`, `Rules.kt`, `Ctx.kt`.

### Specification & Decision Record Changes

- §4.7 table: update the signatures: `aggressive(..., standing_below=None, priority=60)`, `assist(allies,
  standing_above=None, priority=50)`, `guard(exit, allies="mobs", standing_above=None)`, `follow(target,
  standing_above=None, priority=30)`, `pursue(target, standing_below=None, priority=40)`.
- §4.7: add a bullet after "Whom they mean": "**Standing** (the `standing` feature, §5.14): `standing_below = n` keeps
  only candidates the mob's standing toward is below `n`; `standing_above = n` only those it is above `n`. `assist` with
  it joins only for such allies, and never against a foe above `n`; `guard` with it also lets through anyone above `n`;
  `follow` with it drops a leader that falls to `n` or below. The standing is asked once per candidate that matches the
  behavior's other filters. The owned-mob rule above still applies."
- §3.10 `standing` row: add "the `standing_below` and `standing_above` behavior arguments".

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
data class Aggressive(..., val standingBelow: Int? = null, val priority: Int = PRIORITY_AGGRESSIVE)
data class Assist(val allies: ActorFilter, val standingAbove: Int? = null, val priority: Int = PRIORITY_ASSIST)
data class Guard(val exit: Direction, val allies: ActorFilter = ActorFilter("mobs"), val standingAbove: Int? = null)
data class Follow(val target: ActorFilter, val standingAbove: Int? = null, val priority: Int = PRIORITY_FOLLOW)
data class Pursue(val target: ActorFilter, val standingBelow: Int? = null, val priority: Int = PRIORITY_PURSUE)

private fun World.below(mob: Mob, a: Actor, n: Int?): Boolean = n == null || standing(mob, a) < n
private fun World.above(mob: Mob, a: Actor, n: Int?): Boolean = n == null || standing(mob, a) > n
```

- **Algorithmic Steps & Invariants:**
  1. `Aggressive.propose`: add `world.below(mob, it, standingBelow)` after `toward.matches(it) && levelOk(it) &&
     !world.isOwnerSide(mob, it)` and **before** `world.notices(...)` (cheaper than perception rolls; order of the other
     checks is unchanged).
  2. `Assist.propose`: skip an ally unless `above(mob, ally, standingAbove)`; exclude foes with `standingAbove != null
     && standing(mob, foe) > standingAbove`.
  3. `Guard.denies`: an actor passes when `allies.matches(actor) || (standingAbove != null && standing(guard, actor) >
     standingAbove)`.
  4. `Follow.propose`: an existing leader failing `above(...)` is dropped (`mob.leader = null`, return null); picking a
     new leader requires `above(...)`.
  5. `Pursue.propose`: filter candidates with `below(mob, t, standingBelow)` before path search; the in-room early
     return (`world.others(mob).any { target.matches(it) }`) uses the same filter.
  6. With `Feature.STANDING` off the arguments can't be given (declaration refuses them), so `standing` is never asked.
  7. Builtins: add the arguments as optional ints in -100..100 (error otherwise); feature-owned as
     `"aggressive.standing_below"`, `"pursue.standing_below"`, `"assist.standing_above"`, `"guard.standing_above"`,
     `"follow.standing_above"`.
  8. Descriptors: add the keys (`Long` values, omitted when null) and round-trip them.

### Test & Documentation Requirements

- Behavior tests: an `aggressive(toward = "all", standing_below = -40)` mob ignores a neutral player and attacks one it
  holds a -50 personal record against; `pursue` likewise; `assist` won't join for a disliked ally; `guard` lets a
  well-liked player through; `follow` drops a leader whose standing falls. Value equality: two behaviors differing only
  in a threshold are not equal.
- Descriptor round trip with the new keys.
- CHANGELOG bullets.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] Behavior and descriptor tests pass; existing behavior tests unchanged.

---

## Phase 7: Saving standing

### Objective & Scope

- **In Scope:** a `standings` table for faction records; personal records in the mob payload; restore; pruning at each
  checkpoint; dropping records whose holder faction or subject no longer exists.
- **Out of Scope:** *(the agent must not attempt any of these here)* plugin seams, template world.

### Context Working Set

- **Read / Reference Only:**
  - `persist/src/main/kotlin/gups/persist/Database.kt` (the `CREATE TABLE` list; `zone_states` is the closest shape).
  - `persist/src/main/kotlin/gups/persist/SqlitePersistence.kt`: `restore`, `checkpoint`, how `zone_states` is written
    and read.
  - `persist/src/main/kotlin/gups/persist/Codec.kt`: the mob payload (grep `owner` or `summonedBy` in the mob encoder
    and `applyMob`/decoder) — add `personal_standing` the same way.
  - The persistence tests (grep `zone_states` in `persist/src/test/`).
- **Files to Modify / Create:**
  - `persist/src/main/kotlin/gups/persist/Database.kt`, `SqlitePersistence.kt`, `Codec.kt`
  - `core/src/main/kotlin/gups/core/world/Standing.kt` (`pruneStanding` already exists; add `dropOrphanStanding()`)
  - Tests in `persist/src/test/kotlin/gups/persist/`
  - `docs/SPEC.md` (§3.6), `docs/CHANGELOG.md`
- **Do Not Touch:** `script/`, `plugin-api/`, `Behaviors.kt`.

### Specification & Decision Record Changes

- §3.6 table: add row `standings` | `holder`, `subject`, `amount`, `updated_pulse`, `history_json` | "faction standing
  records (§5.14); `subject` is `actor:<id>` or a faction label; history older than `standing_history_pulses` is pruned
  at each checkpoint".
- §3.6 "Mobs store ..." bullet: add "personal standing records with their history".
- §5.14: add "On restore, and after a reload, records whose faction holder, faction subject or actor subject no longer
  exists (a faction or prototype removed, a character deleted) are dropped with a note in the log."

### Implementation Blueprint

- **Signatures & Types:**

```sql
CREATE TABLE standings (holder TEXT NOT NULL, subject TEXT NOT NULL, amount INTEGER NOT NULL,
  updated_pulse INTEGER NOT NULL, history_json TEXT NOT NULL, PRIMARY KEY (holder, subject))
```

```kotlin
// history_json and the mob payload's personal_standing entries:
// [{"pulse": Long, "amount": Int, "event": String, "via": Long?}, ...]
internal fun World.dropOrphanStanding(): List<String> // returns notes
```

- **Algorithmic Steps & Invariants:**
  1. Checkpoint: call `pruneStanding()` and drop faction records whose actor subject is neither in the world nor a saved
     player (a mob that has since died), then rewrite the `standings` table inside the checkpoint transaction (delete
     all, insert all; the table is small). Mirror how `zone_states` is written in the same transaction.
  2. Mob payload: encode `personalStanding` as a map from subject id to `{amount, updated_pulse, history}`; omit when
     empty. Decode into `Mob.personalStanding`. Pets stored with their owner use the same payload, so they keep it.
  3. Restore: read `standings` into `World.factionStanding`, then `dropOrphanStanding()`: a faction holder or faction
     subject failing `protos.isFaction`, or an actor subject that is neither a world entity nor a saved player row, is
     dropped and noted. Also call it after a successful reload (faction subjects/holders only; actor subjects are
     unaffected by a reload).
  4. No format-version bump (guardrail 2).

### Test & Documentation Requirements

- Round trip: faction records (actor and faction subjects) and personal records with history survive checkpoint +
  restore; history pruned at checkpoint; a stored pet keeps its personal records; a removed faction's records are
  dropped with a note; a deleted character's records are dropped.
- CHANGELOG bullet.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] Persistence tests pass, existing ones unchanged.

---

## Phase 8: Plugin seams and facade

### Objective & Scope

- **In Scope:** `addStandingModifier`, `addFactions`, and `PluginWorld.standing`, `factionsOf`, `raiseStandingEvent`,
  `adjustStanding`; host wiring; docs listing; fake registrars updated.
- **Out of Scope:** *(the agent must not attempt any of these here)* template world, any other seam.

### Context Working Set

- **Read / Reference Only:**
  - `docs/MILESTONE_38.md` Phase 4 (the `addOwnedBehaviors` seam), as the worked precedent.
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt` (`addOwnedBehaviors`, `PluginOwnedBehaviorsCall`).
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt` (`PluginWorld`, e.g. `setSkill`, `awardXp`).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt` (how adjusters are stored, assigned to the world,
    listed for docs, and how a throwing handler becomes a `plugin` diagnostic; the facade implementation).
  - `cli/src/main/kotlin/gups/cli/Commands.kt` (plugin docs spec listing).
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`, `Facade.kt`, `host/PluginHost.kt` (and the facade
    implementation file if separate)
  - `core/src/main/kotlin/gups/core/world/World.kt` (`standingModifiers`, `factionAdjusters`),
    `core/src/main/kotlin/gups/core/world/Standing.kt`
  - `cli/src/main/kotlin/gups/cli/Commands.kt`
  - every fake `PluginRegistrar` (find with `grep -rln "addOwnedBehaviors" --include='*.kt' .`, excluding `build/`)
  - Host tests in `plugin-api/src/test/`
  - `docs/SPEC.md` (§4.15), `docs/PLUGINS.md`, `docs/CHANGELOG.md`, regenerated API files
- **Do Not Touch:** `script/` Rules or Ctx, `persist/`, `Behaviors.kt`.

### Specification & Decision Record Changes

- §4.15 seam summary sentence: add "standing modifiers (`addStandingModifier`) and faction lists (`addFactions`)".
- §4.15, after the "Owned behaviors" bullet: "**Standing (`addStandingModifier`, `addFactions`):** `addStandingModifier`
  adjusts the result of `standing` (§5.14) after the rules export, in plugin-id order; a handler gets a read-only
  `PluginWorld`, the holder, the subject and the value so far, and returns an int, which is clamped. `addFactions`
  adjusts an actor's faction labels after the `factions` export, in plugin-id order; an unknown label is dropped with a
  `plugin` diagnostic. A handler that throws records a `plugin` diagnostic and leaves the value unchanged. `PluginWorld`
  has `standing(holder, subject)`, `factionsOf(actor)`, `raiseStandingEvent(affected, actor, event, amount, item)` and
  `adjustStanding(holder, subject, amount)`, with the rules of the matching `ctx` methods (§4.5); a holder or subject is
  a `PluginActor` or a faction label string."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// PluginApi.kt (PluginRegistry)
fun addStandingModifier(doc: String, adjust: (PluginStandingCall) -> Int)
fun addFactions(doc: String, adjust: (PluginFactionsCall) -> List<String>)

data class PluginStandingCall(
  val world: PluginWorld, val holder: PluginActor, val subject: PluginActor, val standing: Int,
)
data class PluginFactionsCall(val world: PluginWorld, val actor: PluginActor, val factions: List<String>)

// Facade.kt (PluginWorld); holder/subject: PluginActor or String (faction label)
fun standing(holder: Any, subject: Any): Int
fun factionsOf(actor: PluginActor): List<String>
fun raiseStandingEvent(affected: PluginActor, actor: PluginActor, event: String, amount: Long = 0, item: PluginItem? = null)
fun adjustStanding(holder: Any, subject: Any, amount: Int)

// core World.kt, mirroring OwnedBehaviorAdjuster
fun interface StandingModifier { fun adjust(world: World, holder: Actor, subject: Actor, value: Int): Int }
fun interface FactionAdjuster { fun adjust(world: World, actor: Actor, factions: List<Label>): List<Label> }
var standingModifiers: List<StandingModifier> = emptyList()
var factionAdjusters: List<FactionAdjuster> = emptyList()
```

- **Algorithmic Steps & Invariants:**
  1. Core: `standing(H, S)` = clamp(fold of `standingModifiers` over the export/default value), still memoized.
     `factionsOf(a)` = fold of `factionAdjusters` over the export/default list, unknown labels removed, still cached.
  2. Host: mirror the `addOwnedBehaviors` adapter (store, assign on world start, convert, catch and diagnose, list for
     docs). The facade methods throw `IllegalArgumentException` with the same messages as the ctx methods, and
     `IllegalStateException` when `standing` is off.
  3. Update every fake registrar so all modules compile.

### Test & Documentation Requirements

- A plugin adding +30 changes behavior thresholds' outcome; two plugins apply in id order; a throwing plugin leaves the
  value unchanged with a diagnostic; `addFactions` adding a declared faction changes `declared` standing; an unknown
  label is dropped with a diagnostic; facade `raiseStandingEvent` reaches `standing_change`.
- `docs/PLUGINS.md` seam list and a short example; CHANGELOG bullets (plugin API).

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including the sample plugins.
- [] Host tests pass.

---

## Phase 9: Template-world reference, end-to-end check, and finalization

### Objective & Scope

- **In Scope:**
  - A `//lib/factions` package in `template-world/lib/` (or `template-world/lib/factions.star` if the template keeps lib
    as one package — check `template-world/lib/package.star` and follow its layout) declaring two factions with declared
    relations (for example townsfolk and goblins, -80 each way) and putting the tutorial's mobs in them with
    `mob(factions=)`.
  - A reference `standing_change` in the template rules (a new `template-world/rules/standing.star`): the default
    deltas, plus a cap of +10 gained per faction per mob (`via = ctx.actor`) and of +10 personal gains per mob, each per
    `standing_history_pulses`, using `ctx.standing_history`, and a `"theft"` example branch.
  - At least one ordinary tutorial mob carrying `aggressive(toward = "all", standing_below = -40)` alongside its
    existing behaviors.
  - A `*_test.star` in the tutorial zone covering: hit, flee, return → attacked on sight; a group healer gets a smaller
    grudge than the attacker; a kill lowers the killer's faction standing with the victim's faction; the heal-farming
    cap holds; a player spamming a helpful buff on a `guard(..., standing_above = 50)` mob never gets through.
  - A template tutorial page (next number after the existing ones in `template-world/docs/tutorials/`) on standing and
    factions.
  - Finalization bookkeeping.
- **Out of Scope:** *(the agent must not attempt any of these here)* engine code changes (fix any bug found in an
  earlier phase's files with a test, and say so in the CHANGELOG), target preference, friendly fire.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/lib/group.star` and `template-world/docs/tutorials/10_groups_and_shared_experience.md` (style of a
    lib reference and its tutorial).
  - `template-world/rules/` (how the rules package splits files).
  - `template-world/zones/tutorial/` (mobs and existing `*_test.star` files).
  - `AGENTS.md` "End-to-end checks" for `gups scenario`.
- **Files to Modify / Create:**
  - `template-world/lib/` (factions), `template-world/rules/standing.star`, tutorial zone mob declarations and a new
    `*_test.star`, a new tutorial page, `template-world/README.md` if it lists tutorials
  - `docs/DESIGN_NEEDED.md`, `docs/SPEC.md` (§7.4), `docs/CHANGELOG.md`
- **Do Not Touch:** `core/`, `script/`, `persist/`, `plugin-api/` (except a bug fix as above).

### Specification & Decision Record Changes

- §7.4: add row `| **M39** | The `standing` feature: factions (`faction`, `mob(factions=)`, the `factions` export),
  standing records with history, the `standing` and `standing_change` exports and `standing_delta`, built-in and world
  standing events, the standing verbs and queries, behavior standing thresholds, saved standing, the
  `addStandingModifier` and `addFactions` plugin seams, and the template-world reference. |`
- `docs/DESIGN_NEEDED.md`:
  - Mark `### Hostility and factions` as completed: append ` (completed in M39)` to the heading and add a first line
    under it, "**Completed:** designed and built in [MILESTONE_39.md](MILESTONE_39.md).". Do not delete the entry.
  - In `### Friendly fire and area-of-effect harm to allies`, append to its questions: "- Should the ally rule ask
    `standing` (§5.14), for example treating an actor the attacker's standing toward is above a threshold as an ally?"

### Implementation Blueprint

- **Algorithmic Steps & Invariants:**
  1. Write the `*_test.star` first and see it fail against the template before the content changes.
  2. The cap in `standing_change`: for each positive faction delta `f → subject`, compute `ctx.standing_history(f,
     subject, since = ctx.pulse - <window>, via = ctx.actor).gained` and reduce the delta so the total stays ≤ 10. Read
     the window from a module-level constant in the rules file equal to the world's `standing_history_pulses` (or less).
  3. Scenario check: write a scenario in `local-world-test/` per `AGENTS.md` that walks the hit-flee-return exploit with
     two players (attacker and healer) and confirm the transcript; then delete `local-world-test/`.

### Test & Documentation Requirements

- The tutorial-zone `*_test.star` passes under `./gradlew check` (template-world tests run there).
- Tutorial page explains factions, standing, events, the cap pattern and the behavior thresholds, with the template's
  own code as examples.
- CHANGELOG: a closing bullet pointing at the template reference.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings (including `markdownCheck` and template-world tests).
- [] The scenario run shows the hit-and-run player attacked on sight on return.
- [] `docs/DESIGN_NEEDED.md` marks "Hostility and factions" completed (not deleted) and the Friendly fire note is added.
- [] Full suite green; spec §3.6, §3.9, §3.10, §4.2, §4.5, §4.6, §4.7, §4.15, §5.4, §5.14 and §7.4 match the
  implementation.
