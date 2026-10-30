# Milestone 15: Specification Refactoring and KDoc Alignment

`docs/SPEC.md` currently mixes high-level architectural specification with volatile implementation details: hardcoded
constants that have already drifted from the code, verbatim Kotlin interfaces, SQLite column/migration dumps, exact
error strings, and unorganized field blobs (especially in `§3.3 Entities`). In addition, several nested lists were
flattened into single run-on paragraphs by Markdown formatting.

This milestone refactors `docs/SPEC.md` into a high-level architectural and behavioral specification and moves low-level
implementation details, field-by-field contracts, and constant values into KDoc on the authoritative Kotlin source
files.

---

## Strict Guardrails (READ FIRST)

<guardrails> 1. **DO NOT delete information without moving it to KDoc first:** Every constant meaning, entity field
contract, edge case, or invariant removed from `docs/SPEC.md` MUST first be present in KDoc (`/** ... */`) on the
corresponding Kotlin class, property, or function. 2. **DO NOT cite spec sections or decision IDs in Kotlin code:** Per
`AGENTS.md`, KDoc and code comments in `.kt` files must NEVER contain strings like `SPEC`, `§3.3`, `(§5.4)`, or `D-005`.
State the behavior directly. 3. **DO NOT edit `docs/DECISIONS.md`:** The maintainer has approved updating `docs/SPEC.md`
directly for this milestone. Do not add entries to `docs/DECISIONS.md`. 4. **DO NOT change runtime behavior or APIs:**
Do not rename public classes, methods, or Starlark builtins, and do not bump `api_version` or `PluginApi.VERSION`. Only
KDoc comments, `docs/SPEC.md`, and the location of `CORPSE_DECAY_PULSES` (moving it to `TimeConstants.kt`) may change.
5. **DO NOT write inline `- ` list items on a single line:** Always leave a blank line before a Markdown list and put
each `- ` or `1. ` item on its own line with 2-space or 4-space indentation for sub-lists. 6. **Always run Gradle
outside the sandbox (`BypassSandbox: true`):** Every `./gradlew` command must be run with `BypassSandbox: true` so it
can connect to the local Gradle daemon. </guardrails>

---

## Few-Shot Examples (Before & After Pattern)

<examples> <example id="1-time-constants"> <before>

### 3.1 Time Constants

```kotlin
const val PULSE_MS = 250L
const val COMBAT_ROUND_PULSES = 8      // 2.0 s
const val WORLD_TICK_PULSES = 120    // 30.0 s
const val THINK_DEFAULT_PULSES = 4     // 1.0 s; per-mob override 1..N
const val BUBBLE_RADIUS = 3      // exits
const val PERCEPTION_MAX_RADIUS = 1    // exits
const val ACTION_QUEUE_MAX = 10
const val CASCADE_MAX_DEPTH = 8
const val LINKDEAD_PULSES = 720    // 3 min
const val IDLE_QUIT_PULSES = 7200   // 30 min
const val CORPSE_DECAY_PULSES = 1200   // 5 min
```

Game time is `pulse: Long`. Every persisted deadline is an absolute pulse number. Wall-clock time is used only for
logging, backups, and session-token expiry. </before> <after>

### 3.1 Time and Simulation Constants

Game time is a monotonic `pulse: Long` counter. Every persisted in-game deadline (timers, affect expirations, decays,
and respawns) is stored as an absolute pulse number. Wall-clock time is used only for external concerns: logging, backup
timestamps, account restriction expirations, and session-token expiry.

The engine defines its timing and capacity constants in
[TimeConstants.kt](../core/src/main/kotlin/gups/core/TimeConstants.kt):

- **Base heartbeat and cadences:** pulse duration (`PULSE_MS`), combat round interval (`COMBAT_ROUND_PULSES`), world
  tick interval (`WORLD_TICK_PULSES`), and default mob think cadence (`THINK_DEFAULT_PULSES`).
- **Spatial and simulation bounds:** reality bubble and perception propagation radius (`BUBBLE_RADIUS`), per-actor
  tick-lane action queue capacity (`ACTION_QUEUE_MAX`), and synchronous hook cascade depth (`CASCADE_MAX_DEPTH`).
- **Session and entity timeouts:** link-dead grace period (`LINKDEAD_PULSES`), player idle auto-quit timeout
  (`IDLE_QUIT_PULSES`), and corpse decay duration (`CORPSE_DECAY_PULSES`). </after> </example>

<example id="2-entities"> <before> **Actor** (sealed: `Player`, `Mob`) - **Position:** - `room: RoomRef` - `trackIndex:
Int` (always 0 without the `track` feature, §3.10). - **Stats and vitals,** keyed by the names the world's schema
declares (§3.9): - `baseStats: Map<String, Int>`, one entry per declared stat... </before> <after> **Actor**
([`Actor`](../core/src/main/kotlin/gups/core/model/Entities.kt), sealed base of
[`Player`](../core/src/main/kotlin/gups/core/model/Entities.kt) and
[`Mob`](../core/src/main/kotlin/gups/core/model/Entities.kt)):

- **Spatial position:** current `Room` and `trackIndex` (always `0` when the `track` feature is off).
- **Schema-keyed attributes:** base stats, vitals (current, base max, and `belowZeroSince` pulse), damage-type resists,
  per-channel base senses, and life `condition` (`OK`, `INCAPACITATED`, `DEAD`). Effective values combine base values
  with trait, affect, equipment, and encumbrance deltas.
- **Progression:** `level`, `xp`, `levelGains` (recorded per level so level loss unwinds exact stat/vital deltas), and
  `skills` proficiency map.
- **Traits and affects:** permanent `traits` (recomputed and cached whenever choices, prototype traits, or
  `grantedTraits` change) and timed `affects`.
- **Action and combat state:** tick-lane `actionQueue`, recovery `waitState` (and `heldWaitState` while disabled),
  active `casting` state, engaged `fighting` set, `primaryTarget`, round `initiative`, and `lastAttacker`.
- **Inventory and equipment:** carried items, equipped items by slot, and current `encumbrance` stat deltas.
- **Mob-specific state:** prototype reference, `thinkEvery` and `lastThink`, `homeRoom`, `dormantRecovery` mode,
  `leader`, `spawnedBy`, `summonedBy`, and `debugOwner`/`debugUntil`.
- **Player-specific state:** `accountId`, `role` and builder `scope`, active `session` and `linkDeadSince`,
  `lastInputPulse` and `idleExempt`, `recallRoom`, and character creation `choices`. </after> </example> </examples>

---

## Phased Execution Plan

Execute the phases below in exact numerical order. Do **not** skip ahead to the next phase until every checkbox in the
current phase's **Exit Criteria** is satisfied.

---

### Phase 1: Move Misplaced Constants & Enrich KDoc in `core`

#### Tasks

1. **`core/src/main/kotlin/gups/core/TimeConstants.kt`:**
   - Add `const val CORPSE_DECAY_PULSES = 1200` to `TimeConstants.kt` and update `Combat.kt` (or any other file defining
     corpse decay pulses) to use `CORPSE_DECAY_PULSES` from `gups.core`.
   - Add KDoc (`/** ... */`) to every constant in `TimeConstants.kt` explaining what it controls, its unit
     (milliseconds, pulses, exits, or count), and how it relates to game time (`pulse: Long`).
2. **`core/src/main/kotlin/gups/core/AttrValue.kt`, `Role.kt`, `Accounts.kt`, `Rng.kt`:**
   - Add KDoc on `AttrValue` documenting that Starlark `int`, `str`, `bool`, `list`, and string-keyed `dict` convert
     both ways, `None` deletes an attribute, floats/functions/host objects are rejected, and `core` stays free of
     `kotlinx.serialization`.
   - Add KDoc on `Role` (`PLAYER < BUILDER < INSTRUCTOR < ADMIN`), `AccountStore` (immediate checkpoint batches, WAL
     read-only connection, token/code cleanup on password change or disable), and `Rng` (deterministic `XorWow` seeded
     random).
3. **`core/src/main/kotlin/gups/core/model/Protos.kt`:**
   - Ensure every prototype class (`RoomProto`, `ExitProto`, `DoorProto`, `SpawnRule`, `RegionProto`, `MobProto`,
     `LootRule`, `ItemProto`, `ContainerProto`, `EquipAffect`, `SkillProto`, `Interrupt`, `ZoneProto`, `Prototypes`) has
     KDoc describing its purpose, default values, and reserved labels (`//engine:player`, `//engine:corpse`).
4. **`core/src/main/kotlin/gups/core/model/Entities.kt`:**
   - Add KDoc on `Entity`, `Door`, `Exit`, `Room`, `Affect`, `ScriptTimer`, `CastState`, `QueuedAction`, `Actor`,
     `Player`, `Mob`, `ItemLocation`, `ContainerState`, and `Item`.
   - Document on `Affect` that re-applying an affect with the same name replaces the existing one only if the new
     `expiresAt` is strictly later.
   - Document on `Actor` how `recomputeTraits` caches trait deltas and caps current vitals at effective maxima, and how
     `stat()`, `max()`, `resist()`, and `sense()` compute effective values.
5. **`core/src/main/kotlin/gups/core/Seams.kt`, `rules/RulesEngine.kt`, `model/Creation.kt`, and `world/*`:**
   - Ensure `ClientInputEvent`, `WorldControlEvent`, `WorldInbox`, `OutputSink`, `ScriptHost` (`Hooks.kt`),
     `RulesEngine` (`RulesEngine.kt`), `Persistence` (`Persistence.kt`), `LoginHandler` (`Login.kt`), `WebAccounts`
     (`Accounts.kt`), `CreationProvider` / `Trait` / `Restriction` (`Creation.kt`), and `Invariants.kt` have thorough
     KDoc covering all behavioral contracts in `SPEC.md` §3.8 and §4.11.

#### Phase 1 Exit Criteria

- [x] `CORPSE_DECAY_PULSES` is declared in `core/src/main/kotlin/gups/core/TimeConstants.kt` with KDoc.
- [x] Every `const val` in `TimeConstants.kt` has a `/** ... */` KDoc comment.
- [x] Every class and public property/function in `Entities.kt`, `Protos.kt`, and `Seams.kt` has clear KDoc.
- [x] `grep -rnE "§|\bD-[0-9]{3}\b|(//|\*).*\bSPEC\b" core/src/main/` returns **0 matches**.
- [x] `./gradlew :core:test` (run with `BypassSandbox: true`) exits `0` with zero Kotlin warnings.

---

### Phase 2: Enrich KDoc in `script`, `persist`, `net`, `auth`, `plugin-api`, `harness`, and `cli`

#### Tasks

1. **`script` module (`script/src/main/kotlin/gups/script/`):**
   - `Limits.kt`: Add KDoc documenting the exact step limits (`50_000` hook/command, `200_000` rules, `5_000_000` load,
     `1_000_000` `@eval`), wall-clock backstops (`20 ms`, `2 s`, `1 s`), allocation caps (`1_048_576` chars, `131_072`
     elements, `64 KB` custom attributes), and package charging rules.
   - `Rules.kt`: Add KDoc documenting the rules dry run (synthetic actors, room/door setup, `xp_to_level` 1..50 check)
     and the neutral fallback return values when a rules export throws or returns an invalid value.
   - `WorldModule.kt`, `WorldMessages.kt`, `ServerConfig.kt`: Add KDoc summarizing `MODULE.gups`, `MESSAGES.gups`, and
     `server.gups` settings and validation rules.
2. **`persist` module (`persist/src/main/kotlin/gups/persist/`):**
   - `Database.kt`: Add KDoc on the file/class summarizing all 10 SQLite tables (`meta`, `accounts`, `session_tokens`,
     `invites`, `restrictions`, `players`, `world_entities`, `zone_states`, `item_events`, `diagnostics`), WAL settings
     (`synchronous = NORMAL`, `busy_timeout = 5000`), and migrations.
   - `Codec.kt`: Add KDoc summarizing the JSON delta rules (what rooms, mobs, items, and players store,
     `format_version`, and tombstone payloads).
   - `Checkpoints.kt`, `SqlitePersistence.kt`, `Backups.kt`: Add KDoc covering periodic vs. immediate checkpoint
     captures, batch merging, retry backoff (50 ms to 5 s), the in-memory mirror, and backup retention (`hourly` 48,
     `daily` 14, `manual`, `predeploy`, `prewipe`).
3. **`net` and `auth` modules (`net/src/main/kotlin/gups/net/`, `auth/src/main/kotlin/gups/auth/`):**
   - `Telnet.kt`, `Ansi.kt`, `Tls.kt`, `WebServer.kt`, `AdminServer.kt`: Add KDoc covering telnet option negotiation,
     512-byte input line cap, 256 KB output buffer cap, color depth downgrade, WebSocket frame format and 30s/60s ping
     timeouts, HTTP login/signup endpoints, and admin bearer token SHA-256 verification.
   - `Passwords.kt`, `LoginLimiter.kt`, `Codes.kt`, `Names.kt`, `CodeOwners.kt`: Add KDoc covering Argon2id parameters,
     dummy hash timing defense, login/IP lockout rules, invite/setup code alphabet (`XXXXX-XXXXX`), reserved character
     names, and CODEOWNERS matching rules.
4. **`plugin-api`, `harness`, and `cli` modules:**
   - `PluginApi.kt`, `Facade.kt`, `PluginHost.kt`: Verify KDoc documents plugin registration checks, Starlark argument
     conversions, and facade verb rules.
   - `Runner.kt`, `Handles.kt`, `FuzzWalker.kt`, `Deployer.kt`, `DocsRenderer.kt`, `Metrics.kt`: Verify KDoc documents
     the harness step limit (`10_000_000`), `p.do()` 1000-pulse cap, fuzz walker bot rules, git worktree deploy steps,
     and Prometheus metric names.

#### Phase 2 Exit Criteria

- [x] `Limits.kt`, `Rules.kt`, `Database.kt`, `Codec.kt`, `Checkpoints.kt`, `Telnet.kt`, `WebServer.kt`,
  `AdminServer.kt`, `Passwords.kt`, and `LoginLimiter.kt` all have class/file-level KDoc covering the constants and
  schemas that will be removed from `docs/SPEC.md`.
- [x] `grep -rnE "§|\bD-[0-9]{3}\b|(//|\*).*\bSPEC\b" script/src/main/ persist/src/main/ net/src/main/ auth/src/main/
  plugin-api/src/main/ harness/src/main/ cli/src/main/` returns **0 matches**.
- [x] `./gradlew testClasses` (run with `BypassSandbox: true`) exits `0` with zero Kotlin warnings.

---

### Phase 3: Refactor `docs/SPEC.md` Header and Sections 1, 2, and 3

#### Tasks

1. **Header ([docs/SPEC.md](SPEC.md#L1-L9)):**
   - Remove the `Status: v0, implemented through milestone M7.` milestone marker; it goes stale and isn't maintained.
2. **§1 & §2 ([docs/SPEC.md](SPEC.md#L11-L352)):**
   - In **§1.5**, keep the platform/stack overview and the Starlark parser rule (no `lambda` or nested `def`), and link
     to [libs.versions.toml](../gradle/libs.versions.toml) instead of hardcoding library versions.
   - In **§2.2**, simplify the release artifacts bullet by removing the low-level `jdeps` and reflective module list
     (`jdk.crypto.ec`, etc.) and pointing to [build.gradle.kts](../build.gradle.kts).
   - In **§2.3**, keep the directory layout, package loading rules, and high-level purpose of `MODULE.gups`,
     `MESSAGES.gups`, and `CODEOWNERS`; replace the character-by-character regex and default-alias lists with links to
     [WorldModule.kt](../script/src/main/kotlin/gups/script/WorldModule.kt),
     [WorldMessages.kt](../script/src/main/kotlin/gups/script/WorldMessages.kt), and
     [CodeOwners.kt](../auth/src/main/kotlin/gups/auth/CodeOwners.kt).
   - In **§2.4**, fix the collapsed `- Listeners: ... - Admin port: ...` paragraph (lines 316–324) into a properly
     indented Markdown bullet list, and link to [ServerConfig.kt](../script/src/main/kotlin/gups/script/ServerConfig.kt)
     and [Commands.kt](../cli/src/main/kotlin/gups/cli/Commands.kt).
   - In **§2.5**, describe the threading model (single world thread, dedicated SQLite checkpoint writer thread,
     background pool for slow hashing/git tasks, dev file watcher) without hardcoding Kotlin dispatcher expressions, and
     link to [WorldLoop.kt](../core/src/main/kotlin/gups/core/loop/WorldLoop.kt),
     [Checkpoints.kt](../persist/src/main/kotlin/gups/persist/Checkpoints.kt), and
     [Watcher.kt](../script/src/main/kotlin/gups/script/Watcher.kt).
3. **§3.1 Time Constants ([docs/SPEC.md](SPEC.md#L357-L374)):**
   - Replace §3.1 with the high-level description and link to
     [TimeConstants.kt](../core/src/main/kotlin/gups/core/TimeConstants.kt) shown in **Example 1** above.
4. **§3.2 Script-Compatible Values ([docs/SPEC.md](SPEC.md#L376-L394)):**
   - Remove the `sealed interface AttrValue` Kotlin block. State what Starlark types can be stored as custom attributes,
     that `None` deletes an attribute, and that `core` has no `kotlinx.serialization` dependency, linking to
     [AttrValue.kt](../core/src/main/kotlin/gups/core/AttrValue.kt) and
     [Values.kt](../script/src/main/kotlin/gups/script/Values.kt).
5. **§3.3 Entities ([docs/SPEC.md](SPEC.md#L395-L502)):**
   - Replace the entire section:
     - Remove the `sealed class Entity` and `@Serializable sealed interface ItemLocation` code blocks and the collapsed
       run-on paragraphs.
     - Clearly separate **Prototypes** ([Protos.kt](../core/src/main/kotlin/gups/core/model/Protos.kt)) from **Live
       Entities** ([Entities.kt](../core/src/main/kotlin/gups/core/model/Entities.kt)).
     - Format `Entity`, `Room`, `Exit`, `Door`, `Actor` (`Player`, `Mob`), `Item` (`ItemLocation`, `ContainerState`),
       `Affect`, `ScriptTimer`, `HookRef`, and `Rng` with clean nested bullet lists (as shown in **Example 2**) that
       describe high-level responsibilities and point to KDoc in
       [Protos.kt](../core/src/main/kotlin/gups/core/model/Protos.kt) and
       [Entities.kt](../core/src/main/kotlin/gups/core/model/Entities.kt).
6. **§3.5 & §3.6 Accounts, Roles, and Persistence Schema ([docs/SPEC.md](SPEC.md#L541-L633)):**
   - Remove the `enum class Role` code block, SQL query snippets (`INSERT OR IGNORE`, `UPDATE ...`), migration numbers
     (`migration 2/3/4`), and low-level JSON key names (`"cur"`, `"max"`).
   - Keep the high-level role hierarchy, character naming rules, CODEOWNERS vs. manual builder scopes, account store
     concurrency model, single-location item storage invariant, and delta-vs-full persistence concepts, linking to
     [Role.kt](../core/src/main/kotlin/gups/core/Role.kt), [Accounts.kt](../core/src/main/kotlin/gups/core/Accounts.kt),
     [Database.kt](../persist/src/main/kotlin/gups/persist/Database.kt), and
     [Codec.kt](../persist/src/main/kotlin/gups/persist/Codec.kt).
7. **§3.9 World Schema ([docs/SPEC.md](SPEC.md#L664-L757)):**
   - Keep the concept, the two example `MODULE.gups` blocks, the typed constructors table (`vitals`, `stats`,
     `resists`), and the rule that schema names are fixed per database unless wiped with `--wipe`.
   - Remove the exact edit-distance formula (lines 719–721) and verbatim CLI refusal strings (lines 752–757), linking to
     [Schema.kt](../core/src/main/kotlin/gups/core/model/Schema.kt) and
     [SchemaRecord.kt](../persist/src/main/kotlin/gups/persist/SchemaRecord.kt).

#### Phase 3 Exit Criteria

- [x] `docs/SPEC.md` §§1–3 contain **no** Kotlin `sealed class Entity`, `sealed interface AttrValue`, `sealed interface
  ItemLocation`, `enum class Role`, or `const val PULSE_MS` code blocks.
- [x] `docs/SPEC.md` §§1–3 contain **no** occurrences of `PERCEPTION_MAX_RADIUS`, `RoomRef`, `SessionRef`, `Map<Sense`,
  or `@Serializable`.
- [x] All lists in `docs/SPEC.md` §§1–3 are properly formatted with one item per line (no inline ` - ` chains).
- [x] `./gradlew markdownFormat && ./gradlew markdownCheck` (with `BypassSandbox: true`) exits `0`.

---

### Phase 4: Refactor `docs/SPEC.md` Section 4 (Interfaces, Commands, Network, Seams, and Plugins)

#### Tasks

1. **§4.4, §4.5, and §4.6 (Hooks, `ctx`, and Rules Module):**
   - Fix all collapsed inline `- ` bullet lists in §4.4 (`Timing:`), §4.5 (`Views:`, `Actor view:`, `Room view:`, `Verb
     semantics:`), and §4.6 (`Perception exports:`, `Signatures and results:`).
   - Merge the duplicate `**Actor view:**` heading on line 1080 (`senses` and `base.senses`) into the main `Actor view`
     bullet list in §4.5.
   - In §4.6, remove the 23-row **Template ruleset** formula table (lines 1258–1286) and replace it with a short
     paragraph pointing to [combat.star](../template-world/rules/combat.star),
     [perception.star](../template-world/rules/perception.star), and
     [manipulation.star](../template-world/rules/manipulation.star) in `template-world/rules/`.
   - Add links in §§4.1–4.8 to [ApiModel.kt](../script/src/main/kotlin/gups/script/ApiModel.kt),
     [Declarations.kt](../script/src/main/kotlin/gups/script/Declarations.kt),
     [Ctx.kt](../script/src/main/kotlin/gups/script/Ctx.kt), [Views.kt](../script/src/main/kotlin/gups/script/Views.kt),
     [Rules.kt](../script/src/main/kotlin/gups/script/Rules.kt), and
     [Behaviors.kt](../core/src/main/kotlin/gups/core/world/Behaviors.kt).
2. **§4.9 Commands ([docs/SPEC.md](SPEC.md#L1370-L1543)):**
   - Fix the collapsed inline `- ` bullet lists under `Player commands, tick lane:`, `Item and combat command details:`,
     `Help and categories:`, and `@role:`.
   - Condense the verbatim error-string permutations under `@role` (lines 1495–1515) into a concise behavioral summary
     and link to [AccountTools.kt](../auth/src/main/kotlin/gups/auth/AccountTools.kt),
     [Commands.kt](../core/src/main/kotlin/gups/core/world/Commands.kt),
     [KernelCommands.kt](../core/src/main/kotlin/gups/core/world/KernelCommands.kt), and
     [Tools.kt](../core/src/main/kotlin/gups/core/world/Tools.kt).
3. **§4.10 Network Protocol ([docs/SPEC.md](SPEC.md#L1545-L1714)):**
   - Keep the listener overview, telnet options, color markup, GMCP package table, WebSocket framing, and HTTP endpoint
     table, and link to [Telnet.kt](../net/src/main/kotlin/gups/net/Telnet.kt),
     [Tls.kt](../net/src/main/kotlin/gups/net/Tls.kt), [Ansi.kt](../net/src/main/kotlin/gups/net/Ansi.kt),
     [WebServer.kt](../net/src/main/kotlin/gups/net/WebServer.kt), and
     [Gmcp.kt](../core/src/main/kotlin/gups/core/world/Gmcp.kt) for low-level framing/cookie/caching details.
4. **§4.11 Kotlin Seams ([docs/SPEC.md](SPEC.md#L1715-L1843)):**
   - Remove both large Kotlin code blocks (lines 1717–1770 and 1778–1810).
   - Replace them with a clean Markdown table listing each seam (`ClientInputEvent` / `WorldControlEvent` /
     `WorldInbox`, `ScriptHost`, `RulesEngine`, `Persistence`, `OutputSink`, `LoginHandler`, `WebAccounts`,
     `CreationProvider` / `Trait` / `Restriction`), its responsibility, and a link to its source file
     ([Seams.kt](../core/src/main/kotlin/gups/core/Seams.kt),
     [Hooks.kt](../core/src/main/kotlin/gups/core/world/Hooks.kt),
     [RulesEngine.kt](../core/src/main/kotlin/gups/core/rules/RulesEngine.kt),
     [Persistence.kt](../core/src/main/kotlin/gups/core/world/Persistence.kt),
     [Login.kt](../core/src/main/kotlin/gups/core/world/Login.kt),
     [Accounts.kt](../core/src/main/kotlin/gups/core/Accounts.kt),
     [Creation.kt](../core/src/main/kotlin/gups/core/model/Creation.kt)).
   - Retain the bullet points explaining the `World` initialization and `World.start()` lifecycle.
5. **§4.15 Plugins ([docs/SPEC.md](SPEC.md#L2002-L2375)):**
   - Remove the two large Kotlin code blocks (`object PluginApi ...` on lines 2010–2144 and `fun send(...)` on lines
     2212–2245) and fix the collapsed inline `- ` lists on lines 2259–2327.
   - Replace the code blocks with a concise architectural overview of `GupsPlugin`, `PluginRegistry`, and the
     `PluginWorld` / `PluginEntity` facade, linking to
     [PluginApi.kt](../plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt),
     [Facade.kt](../plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt),
     [PluginViews.kt](../plugin-api/src/main/kotlin/gups/pluginapi/PluginViews.kt),
     [PluginHost.kt](../plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt), and [PLUGINS.md](PLUGINS.md).
   - Condense the `samples/weather-plugin` and `samples/heritage-plugin` subsections to brief summaries linking to
     [samples/weather-plugin](../samples/weather-plugin/README.md) and
     [samples/heritage-plugin](../samples/heritage-plugin/README.md).
6. **§4.16–§4.19 API Model, Changelog, Engine Messages, and Character Creation ([docs/SPEC.md](SPEC.md#L2376-L2626)):**
   - Fix all collapsed inline `- ` bullet lists in §4.16 (lines 2379–2401) and §4.19 (lines 2528–2575, 2603–2621).
   - Link §4.16–§4.17 to [ApiModel.kt](../script/src/main/kotlin/gups/script/ApiModel.kt),
     [ApiDiff.kt](../script/src/main/kotlin/gups/script/ApiDiff.kt), and
     [DocsRenderer.kt](../cli/src/main/kotlin/gups/cli/DocsRenderer.kt).
   - Link §4.18 to [Messages.kt](../core/src/main/kotlin/gups/core/world/Messages.kt).

#### Phase 4 Exit Criteria

- [x] `docs/SPEC.md` §4 contains **no** `interface ScriptHost`, `interface GupsPlugin`, or `interface PluginRegistry`
  Kotlin code blocks.
- [x] `docs/SPEC.md` §4.6 no longer contains the hardcoded Template Ruleset formula table, and instead links to
  `template-world/rules/`.
- [x] All collapsed inline `- ` lists in §4.4, §4.5, §4.6, §4.9, §4.15, §4.16, and §4.19 are formatted as proper
  multi-line Markdown lists.
- [x] `./gradlew markdownFormat && ./gradlew markdownCheck` (with `BypassSandbox: true`) exits `0`.

---

### Phase 5: Refactor `docs/SPEC.md` Sections 5, 6, and 7 & Run Full Verification

#### Tasks

1. **§5 Core Logic, Control Flow & Edge Cases ([docs/SPEC.md](SPEC.md#L2629-L3400)):**
   - In **§5.1 World Loop**, fix the broken list formatting of Phases 1–7 (lines 2640–2659) so all 7 phases (`1.
     Timers`, `2. Action pulse`, `3. Combat round`, `4. World tick`, `5. Deferred perception`, `6. Bubble maintenance`,
     `7. Output flush`) are properly indented, top-level numbered items under `At each pulse boundary`.
   - Replace hardcoded pulse/time numbers (`pulse % 8 == 0`, `pulse % 120 == 0`, `BUBBLE_RADIUS = 3`, `3 minutes`, `30
     minutes`) with references to the named constants in
     [TimeConstants.kt](../core/src/main/kotlin/gups/core/TimeConstants.kt).
   - Add links to the corresponding implementation files in each subsection:
     - §5.1 → [WorldLoop.kt](../core/src/main/kotlin/gups/core/loop/WorldLoop.kt),
       [World.kt](../core/src/main/kotlin/gups/core/world/World.kt)
     - §5.2 & §5.3 → [Commands.kt](../core/src/main/kotlin/gups/core/world/Commands.kt),
       [Actions.kt](../core/src/main/kotlin/gups/core/world/Actions.kt)
     - §5.4 → [Combat.kt](../core/src/main/kotlin/gups/core/world/Combat.kt),
       [Vitals.kt](../core/src/main/kotlin/gups/core/world/Vitals.kt),
       [Leveling.kt](../core/src/main/kotlin/gups/core/world/Leveling.kt)
     - §5.5 → [Spatial.kt](../core/src/main/kotlin/gups/core/model/Spatial.kt),
       [Movement.kt](../core/src/main/kotlin/gups/core/world/Movement.kt)
     - §5.6 → [Hooks.kt](../core/src/main/kotlin/gups/core/world/Hooks.kt)
     - §5.7 → [Perception.kt](../core/src/main/kotlin/gups/core/world/Perception.kt)
     - §5.8 → [Behaviors.kt](../core/src/main/kotlin/gups/core/world/Behaviors.kt)
     - §5.9 → [Bubble.kt](../core/src/main/kotlin/gups/core/world/Bubble.kt)
     - §5.10 → [Reconcile.kt](../core/src/main/kotlin/gups/core/world/Reconcile.kt),
       [Deployer.kt](../cli/src/main/kotlin/gups/cli/Deployer.kt)
     - §5.11 → [Checkpoints.kt](../persist/src/main/kotlin/gups/persist/Checkpoints.kt),
       [SqlitePersistence.kt](../persist/src/main/kotlin/gups/persist/SqlitePersistence.kt),
       [Restore.kt](../core/src/main/kotlin/gups/core/world/Restore.kt),
       [Backups.kt](../persist/src/main/kotlin/gups/persist/Backups.kt)
     - §5.12 → [AccountLogin.kt](../auth/src/main/kotlin/gups/auth/AccountLogin.kt),
       [CreationFlow.kt](../core/src/main/kotlin/gups/core/world/CreationFlow.kt),
       [Passwords.kt](../auth/src/main/kotlin/gups/auth/Passwords.kt),
       [LoginLimiter.kt](../auth/src/main/kotlin/gups/auth/LoginLimiter.kt)
     - §5.13 → [DevTools.kt](../script/src/main/kotlin/gups/script/DevTools.kt),
       [Tools.kt](../core/src/main/kotlin/gups/core/world/Tools.kt)
2. **§6 Error Handling & Invariants ([docs/SPEC.md](SPEC.md#L3403-L3538)):**
   - In **§6.1 Script Resource Limits**, replace the hardcoded step/time table and allocation numbers with a high-level
     description of deterministic step budgets, wall-clock backstops, boundary allocation checks, and package charging,
     linking to [Limits.kt](../script/src/main/kotlin/gups/script/Limits.kt).
   - In **§6.2 Script Error Handling**, fix the collapsed inline `- ` list on lines 3456–3463.
   - In **§6.3 Quarantine**, replace the 20-row neutral-result table with a summary of the safe-neutral-fallback
     principle (errors never hurt, kill, reward, or trap actors) and link to
     [Rules.kt](../script/src/main/kotlin/gups/script/Rules.kt) and
     [Host.kt](../script/src/main/kotlin/gups/script/Host.kt).
   - In **§6.5 Invariant Enforcement**, link to [Invariants.kt](../core/src/main/kotlin/gups/core/world/Invariants.kt).
3. **§7 Verification & Test Plan ([docs/SPEC.md](SPEC.md#L3541-L3720)):**
   - Condense **§7.1 Engine Tests** from a long list of per-milestone test cases into a concise summary of the test
     categories (deterministic unit and simulation tests, `FeatureMatrixTest` across all 12 valid feature combinations,
     property-based item custody tests, SIGKILL crash-consistency tests, API model/golden/baseline checks, and browser
     smoke tests).
   - Add **M15** to the **§7.4 Implementation Milestones** table: `| **M15** | Specification refactoring and KDoc
     alignment: high-level architectural spec in SPEC.md with implementation details, constants, entity fields, seams,
     and limits documented in KDoc. |`

#### Phase 5 Exit Criteria

- [x] Phases 1 through 7 in `docs/SPEC.md` §5.1 render as a 7-item numbered list.
- [x] `docs/SPEC.md` §6.1 and §6.3 link to `Limits.kt` and `Rules.kt` instead of duplicating threshold tables and the
  20-row neutral-result table.
- [x] `docs/SPEC.md` §7.4 includes the **M15** row.
- [x] `grep -rnE "§|\bD-[0-9]{3}\b|(//|\*).*\bSPEC\b" */src/main/` returns **0 matches**.
- [x] `./gradlew markdownFormat` followed by `./gradlew check` (run with `BypassSandbox: true`) exits `0` with zero
  warnings and all tests passing.
