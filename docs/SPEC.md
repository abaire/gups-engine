# GUPS Engine — Architecture & Technical Specification

Status: v0 design, ready for implementation. Script API: `api_version = 1` (unstable).

---

## 1. Objective & Scope

### 1.1 Vision

GUPS is a text MUD engine built as a teaching platform for an open university course with
students of mixed skill levels. It preserves old-school text MUD pacing (pulse-driven
actions, combat rounds, recovery lag). It gives students a sandboxed, deterministic
scripting environment for building content. Its world logic runs on a single thread, so
there is no thread-synchronization overhead anywhere in the game simulation.

### 1.2 Audience Tiers

| Tier | Works in | Typical work |
|---|---|---|
| Novice builder | Starlark zone packages (`//zones/...`) | Rooms, mobs, items, dialogue, simple quests, configuring built-in behaviors |
| Advanced builder | Starlark `think` hooks, `resolve` hooks, shared libraries (`//lib/...`), the rules module (`//rules/...`), global skills (`//skills/...`) | Custom AI, custom spell effects, rebalancing, new libraries, GMCP-driven client UIs |
| Engine developer | Kotlin, `.jar` plugins against `plugin-api` | New core mechanics, new host builtins, engine internals |

The novice builder is the primary audience and drives the design. The engine-developer path is
documented but carries no compatibility promises.

### 1.3 API Stability Policy

- The Starlark API is explicitly **unstable**. Old scripts may break when the engine is
  updated.
- Every package declares the `api_version` it targets. The engine supports exactly one
  `api_version` at a time in v0. A package that declares a different version fails to
  load with a structured diagnostic that names the version gap and links to the changelog.
- Each engine release ships generated API docs, a changelog of breaking changes, and a
  `MIGRATING.md` with before/after snippets.
- Migrating legacy zones with a local LLM, fed the changelog and the failing zone, is an
  intended course exercise. Migration artifacts are designed to be machine-readable.
- The API will be stabilized over time. Stabilization is out of scope for v0.

### 1.4 Deployment Model (Hybrid)

1. Students develop locally: `gups serve --dev` runs a single-user server with
   auto-reload on save, an `@eval` REPL, and a local instructor account.
2. Content reaches the shared course server **only** through a pull request to the
   course world repository. CI runs the headless harness and the lint gates, and an
   instructor reviews and merges.
3. After the merge, the course server snapshots its database and hot-reloads the changed
   packages.
4. The shared server has no live in-game code editing.

### 1.5 Platform, Scale, Stack

- **Platforms:** Linux on ARM64 and x86_64, and macOS on Apple Silicon (ARM64) only.
- **Scale:** Hundreds of concurrent connections. Memory is not a design constraint; the
  whole world graph lives in memory.
- **Engine core:** Kotlin (JVM), `kotlinx.coroutines`, Ktor/Netty raw non-blocking sockets.
- **Scripting:** `net.starlark.java`, the canonical Bazel JVM implementation of Starlark.
- **Persistence:** SQLite in WAL mode, used as a hybrid document store. Payloads are
  `kotlinx.serialization` JSON.
- **Build:** Gradle with the Kotlin DSL. Releases are per-platform `jlink` runtime images
  plus a Docker image.

### 1.6 Glossary

| Term | Meaning |
|---|---|
| Pulse | The base heartbeat, 250 ms. The pulse counter (`Long`, monotonic, persisted) *is* game time. |
| Fast lane | Commands executed immediately, bypassing the action queue and lag. |
| Tick lane | Commands queued and executed on action pulses, subject to warmup and lag. |
| Package | A directory of `.star` files with a `package.star`, addressed as `//path/to/dir`. |
| Zone | A package of kind `zone`. It is the unit of authoring, ownership, hot reload, and fault quarantine. It is **not** a performance or memory partition. |
| Label | The Bazel-style address of a declaration: `//zones/forest:clearing`, or `:clearing` within the same package. |
| Prototype | An immutable, frozen template produced by evaluating a package's top level. |
| Instance | A live entity created from a prototype. It stores the prototype label, the prototype version, and a delta. |
| Region | A builder-applied tag on a set of rooms. It can span zones and is used for rule modifiers. |
| Track | A room's 1D tactical position axis, with indices `0 .. trackSize-1`. |
| Band | Relative distance as seen by a viewer: `MELEE` (0), `NEAR` (1), `FAR` (2 or more). |
| Reality bubble | The set of rooms currently simulated. Everything outside it is dormant. |
| Active / anchor | Per-entity flags that keep an entity simulated outside the bubble (active), or make it project a bubble of its own (anchor). |
| Affect | A timed buff or debuff attached to an actor or item. |

### 1.7 Out of Scope for v0 (Deferred)

- In-game OLC editing.
- Live `@reload` by builders on the shared server.
- Player groups and parties.
- Mail, boards, and extra channels beyond `gossip`.
- Shops and crafting. These will ship later as `//lib` libraries, not as engine features.
- Cross-room projectiles and ranged combat between rooms.
- Behavior trees and GOAP. Students may build these in `think`.
- Transactional (rollback-capable) script execution.
- Global and cross-zone event subscriptions, beyond adjacent-room perception.
- MSDP and MXP.
- Multi-server sharding, distributed tracing, external log aggregation.
- Open self-signup. It is designed for, but disabled by default.
- Supporting multiple `api_version`s at once.

---

## 2. Target Files, Modules & Boundaries

### 2.1 Repositories

There are two repositories. The working tree is laid out as:

```
gups-project/
  SPEC.md                 (this file; moves into gups-engine/ once that repo exists)
  gups-engine/            engine repository
  <course-world>/         course world repository (one per course term, from a template)
```

### 2.2 `gups-engine` (Kotlin, Gradle multi-module)

```
gups-engine/
  core/        world model, entities, pulse scheduler, command pipeline, action lifecycle,
               combat rounds, tactical track, event dispatch, rules chain, reality bubble,
               perception, pathfinding
  script/      Starlark host: package loader, labels, api_version check, prototype freezing,
               ctx object and views, builtins, effect primitives, behaviors, step/alloc limits
  persist/     SQLite/WAL access, schema migrations, checkpoint capture and writer,
               reconciliation, backups, item custody log, diagnostics store
  net/         Ktor/Netty listeners (telnet, telnet+TLS, WebSocket+TLS), telnet option
               negotiation, line framing, ANSI rendering, GMCP, per-session output buffers
  auth/        accounts, Argon2id hashing, login rate limiting, invites, roster import,
               session tokens, roles, CODEOWNERS sync
  harness/     headless in-memory world, virtual clock, Starlark test runner, fuzz walker,
               repro runner
  plugin-api/  extension points for .jar plugins (@StarlarkBuiltin host objects, new
               commands, new effect primitives); unstable, documented
  cli/         the `gups` binary
  webclient/   static browser client (TypeScript), bundled into the jar as resources
  docs/        generated reference + MIGRATING.md + CHANGELOG.md
```

**Module dependency rules (enforced by the Gradle module graph):**

- `core` depends on no other GUPS module. It defines interfaces (`ScriptHost`, `RulesEngine`,
  `Persistence`, `OutputSink`) that the other modules implement.
- `script`, `persist`, `net`, and `auth` depend on `core` and never on each other.
- `harness` depends on `core` and `script` only. It never touches `net` or `persist`.
- `cli` is the only module that wires everything together.
- `plugin-api` exposes a narrow subset of `core` and `script` types. Plugins are loaded
  through `ServiceLoader` from a `plugins/` directory via a `URLClassLoader`.

**Release artifacts:**

- `gups` runtime images for `linux-x64`, `linux-arm64`, and `macos-arm64`, built
  with `jlink`. Students need no JDK.
- An OCI image for the course server.
- The engine version (semver) and `api_version` (integer) are independent. `api_version`
  bumps only on breaking script-facing changes.

### 2.3 Course World Repository

```
<course-world>/
  MODULE.gups              pins engine version and api_version; world settings such as
                           checkpoint_minutes (§5.11)
  CODEOWNERS               package ownership → builder roles
  rules/                   instructor-owned; package kind "rules"
    package.star
    combat.star
  lib/                     instructor-owned shared libraries; package kind "lib"
    package.star
    quest.star
  skills/<school>/         instructor-owned global skills; package kind "skills"
  zones/<name>/            student zones; package kind "zone"
    package.star
    *.star
    *_test.star            at least one required per zone
  zones/tutorial/          heavily commented reference zone, loaded by --dev
  .github/workflows/       CI gates (§7.3) using the pinned engine image
```

- **Package kinds:** `zone`, `lib`, `rules`, `skills`. Only `lib` and `rules` packages are
  *privileged*. They may declare shared attribute namespaces and global commands (§4.9).
- **Load labels vs target labels:** `load("//lib:quest.star", "quest")` addresses a *file*.
  `//zones/forest:clearing` addresses a *declaration* named `clearing` in any non-test
  `.star` file of package `zones/forest`. Declaration names are unique per package.
- **Deploying:** An engine upgrade is a PR that edits `MODULE.gups`. CI shows exactly which
  packages break.

### 2.4 Runtime Profiles

| Flag | Effect |
|---|---|
| `--dev` | Raw telnet on `127.0.0.1:4000`. Auto-creates a passwordless local `instructor` account bound to localhost. Auto-reload on file save. `@eval` enabled. Loads `zones/tutorial`. Checkpoints still run. |
| `--prod` | Only the TLS telnet (`:4443`) and WebSocket/TLS listeners. Raw telnet off (it can be re-enabled explicitly). `@eval` disabled. Deploys only through `gups deploy` / `@deploy`. |
| `--fresh` | Usable with either profile. Ignores saved **world** state (mob positions, ground items, room and zone state) and boots purely from the `.star` definitions. Accounts, characters, and items whose location chain ends in a player character are still loaded. |

### 2.5 Process and Thread Topology

- **Network I/O** runs on `Dispatchers.IO`. This covers Telnet and ANSI parsing, option
  negotiation, line framing, GMCP encoding, and output buffering.
- **The world thread** is a single-threaded dispatcher
  (`Dispatchers.Default.limitedParallelism(1)`). It owns the entire world graph, the
  scheduler, all Starlark evaluation, and checkpoint *capture*. Every world mutation
  happens here, so they are lock-free and race-free by construction.
- **The checkpoint writer** is one coroutine on `Dispatchers.IO`. It is the only SQLite
  writer, and it applies batches in FIFO order.
- **The file watcher** (`--dev` only) posts reload requests into the world input channel.
- **Ingress:** All network input reaches the world as `ClientInputEvent`s on one
  `Channel`. Output leaves through per-session `OutputSink`s, which never block the
  world thread.

---

## 3. Data Structures, Types & State Contracts

### 3.1 Time Constants

```kotlin
const val PULSE_MS            = 250L
const val COMBAT_ROUND_PULSES = 8      // 2.0 s
const val WORLD_TICK_PULSES   = 120    // 30.0 s
const val THINK_DEFAULT_PULSES = 4     // 1.0 s; per-mob override 1..N
const val BUBBLE_RADIUS       = 3      // exits
const val PERCEPTION_MAX_RADIUS = 1    // exits
const val ACTION_QUEUE_MAX    = 10
const val CASCADE_MAX_DEPTH   = 8
const val LINKDEAD_PULSES     = 720    // 3 min
const val IDLE_QUIT_PULSES    = 7200   // 30 min
```

Game time is `pulse: Long`. Every persisted deadline is an absolute pulse number.
Wall-clock time is used only for logging, backups, and session-token expiry.

### 3.2 Script-Compatible Values

```kotlin
@Serializable sealed interface AttrValue {
    @Serializable data class I(val v: Long) : AttrValue
    @Serializable data class S(val v: String) : AttrValue
    @Serializable data class B(val v: Boolean) : AttrValue
    @Serializable data class L(val v: List<AttrValue>) : AttrValue
    @Serializable data class M(val v: Map<String, AttrValue>) : AttrValue
}
```

- Starlark `int`, `str`, `bool`, `list`, and `dict` with string keys convert both ways.
  `None` means "absent": setting an attribute to `None` deletes it.
- Floats, functions, and host objects can't be stored.
- An entity's serialized `customAttributes` is limited to 64 KB.

### 3.3 Entities

All entities are strongly typed Kotlin classes that share a base:

```kotlin
sealed class Entity {
    abstract val id: EntityId                 // @JvmInline value class EntityId(val v: Long)
    abstract val protoLabel: Label            // e.g. //zones/goblin_caves:goblin_scout
    abstract var protoVersion: Int
    val customAttributes: MutableMap<String, AttrValue>   // keys are namespaced (§3.7)
    val timers: MutableList<ScriptTimer>
    val activeReasons: MutableSet<String>     // ref-counted by reason string (§5.9)
    val anchorReasons: MutableSet<String>
    var dirty: Boolean
}
```

**Room**
- `trackSize`: `CRAMPED = 1`, `NORMAL = 3`, or `LARGE = 5`.
- `exits: Map<Direction, Exit>`.
- `regions: Set<String>`.
- `modifiers: Map<ModifierPoint, StarlarkCallable>`, frozen with the prototype.
- `flags`: `always_active`, `dead_end_ok`.
- `spawns: List<SpawnRule>`.
- `commands: Map<String, StarlarkCallable>`.

**Exit**
- `direction`.
- `to: Label`.
- `entryIndex: Int`. Default `0`; `-1` means `trackSize - 1`.
- `blocks: Set<Channel>`, where `Channel` is one of `SIGHT`, `SOUND`, `NOISE`, `SPELL`.
- `door: Door?`.
- `oneWay: Boolean`.

**Door**
- `state`: `OPEN`, `CLOSED`, or `LOCKED`.
- `keyLabel: Label?`.
- `openBlocks`, `closedBlocks: Set<Channel>`.

The **effective** `blocks` set of an exit is the exit's `blocks` plus the door's set for its
current state.

**Actor** (sealed: `Player`, `Mob`)
- **Position:** `room: RoomRef` and `trackIndex: Int`.
- **Stats** (fixed v0 schema, typed):
  - Attributes: `str`, `dex`, `con`, `int`, `wis`.
  - Vitals: `hp/maxHp`, `mana/maxMana`, `move/maxMove`.
  - Leveling: `level`, `xp`.
  - Combat ratings: `armor`, and `resists: Map<DamageType, Int>`.
- **Skills:** `Map<Label, Int>`, the skill label and a proficiency of 0–100.
- **Affects:** `affects: List<Affect>`.
- **Action state:**
  - `actionQueue: ArrayDeque<QueuedAction>`, capacity 10.
  - `waitState: Int`, in pulses.
  - `casting: CastState?`.
  - `fighting: Set<EntityId>`.
- **Mob only:**
  - `behaviors`.
  - `thinkFn`.
  - `thinkEvery: Int`.
  - `senses: Map<Sense, Int>` with radius 0..1. Senses are `HEARING` and `SIGHT`.
  - `homeRoom`.
  - `dormantRecovery`: `FULL` (default), `REGEN`, or `NONE` (§5.9).
- **Player only:**
  - `accountId`.
  - `session: SessionRef?`. `null` while link-dead; the player is removed from the
    world after quitting.
  - `recallRoom`.

**Item**
- `location: ItemLocation`.
- `count: Int`. This is 1 unless the prototype is `stackable`.
- `weight`.
- `container: ContainerState?` with `capacity`, `maxWeight`, `state` (OPEN, CLOSED, or
  LOCKED), and `keyLabel`.
- `range: Band`, for weapons.
- `kind`, for example `melee`, `ranged`, `armor`, or `consumable`.
- `statDeltas`.
- `equipAffects`.
- `decayAt: Long?`.
- `debug: Boolean`. Builder-spawned items are flagged `debug` and are never persisted.

```kotlin
@Serializable sealed interface ItemLocation {
    data class InRoom(val room: EntityId, val trackIndex: Int) : ItemLocation
    data class Carried(val actor: EntityId) : ItemLocation
    data class Equipped(val actor: EntityId, val slot: String) : ItemLocation
    data class Contained(val container: EntityId) : ItemLocation
}
```

**Affect**
- `id`.
- `label`, the skill or affect prototype.
- `source: EntityId?`.
- `expiresAt: Long`, an absolute pulse.
- `statDeltas`.
- `flags`, for example `stunned`, `silenced`, or `no_regen` (suppresses all vitals regen,
  including dormant catch-up, until the affect expires).
- `tickEvery: Int?`.
- `onTick` and `onExpire`: `HookRef?`.
- `expireMessage`.

**ScriptTimer**
- `id`.
- `fn: HookRef`.
- `args: Map<String, AttrValue>`.
- `due: Long?` (absolute pulse, while running).
- `remaining: Int?` (while paused in a dormant room).

**HookRef** is `(file label, top-level function name)`. Lambdas and nested functions can't
be persisted: `ctx.after` rejects them with an explanatory error.

### 3.4 Prototypes, Instances, Versions

- **Freezing:** Evaluating a package's top level produces immutable, frozen prototypes.
  Each prototype carries an integer `version`, which defaults to 1.
- **Instance delta:** An instance stores `protoLabel`, `protoVersion`, and only the fields
  that differ from the prototype. That covers changed stats, attributes, location, timers,
  and affects.
- **Load and reload order:**
  1. Load the new prototype.
  2. If `instance.protoVersion < proto.version` and the package defines
     `migrate(old_version, attrs)`, call it. It returns the new attribute dict.
  3. Overlay the delta.
  4. Set `protoVersion`.
- **Deleted prototypes:**
  - Mobs and ground items from a deleted prototype are despawned.
  - Items in player inventories, equipment, or player-owned containers become inert
    **tombstone** items. A tombstone keeps the original name, the label, and the last
    serialized payload. Tombstones can be inspected and dropped, but they have no hooks or
    stats.

### 3.5 Accounts and Roles

```kotlin
enum class Role { PLAYER, BUILDER, INSTRUCTOR, ADMIN }   // ordered; higher implies lower
```

- **Account fields:** `id`, `login` (unique, case-insensitive), `pwHash` (Argon2id),
  `roles`, `githubHandle?`, `disabled`, and `createdAt`.
- **Characters:** An account owns 1..N characters. Character names are unique
  (case-insensitive) and checked against a reserved-name list.
- **Builder scope:** The `BUILDER` role is scoped to a set of package paths. The set is
  derived from `CODEOWNERS` by matching `githubHandle` on each deploy.

### 3.6 Persistence Schema (SQLite, WAL)

| Table | Columns (abridged) | Notes |
|---|---|---|
| `meta` | `key`, `value` | `schema_version`, `pulse`, `next_entity_id`, `world_commit` |
| `accounts` | `id`, `login`, `pw_hash`, `roles_json`, `github_handle`, `disabled`, `created_at` | |
| `session_tokens` | `token_hash`, `account_id`, `expires_at` | web client; revocable |
| `invites` | `code_hash`, `role`, `expires_at`, `used_by` | |
| `players` | `id`, `account_id`, `name`, `payload_json`, `updated_pulse` | character stats, skills, affects, attrs, position |
| `world_entities` | `id`, `kind`, `proto_label`, `proto_version`, `location_json`, `payload_json`, `updated_pulse` | rooms, mobs, and **all items**, including carried ones |
| `zone_states` | `package`, `payload_json` | reset and respawn deadlines, quarantine state |
| `item_events` | `id`, `item_id`, `pulse`, `wall_time`, `from_json`, `to_json`, `cause` | custody audit |
| `diagnostics` | `id`, `pulse`, `wall_time`, `package`, `label`, `hook`, `file`, `line`, `kind`, `message`, `stack`, `ctx_json`, `seed`, `repro_json` | |

- **Item location:** An item's location is stored **only** on the item row. Inventories and
  container contents are derived when the world loads. A player row never lists items, so
  no partial checkpoint can put one item in two places.
- **Entity IDs:** `next_entity_id` is written in every checkpoint transaction. IDs
  allocated after the last committed checkpoint may be reused after a crash. This is safe
  because nothing that referenced them was committed either.
- **Payloads:** Each JSON payload carries a `format_version` for engine-side schema
  evolution.

### 3.7 Attribute Namespacing

- A key written by package `zones/goblin_caves` is stored as `goblin_caves:<key>`. The
  namespace is the last path segment of the package, and must be unique across the world.
  CI lints for this.
- A package reads its own keys by their short name. Other packages read them by full
  name only.
- A package can **write only its own namespace**. Writing another namespace is a script
  error.
- **Shared namespaces:** Privileged packages may declare them, for example
  `package(shared_namespaces = ["faction"])`. Any package may write to a shared namespace,
  but only through helper functions exported by the declaring package. The declaring
  package's helpers run with write permission on that namespace.
- **Built-in state** isn't stored in attributes and isn't reachable through them. That
  covers stats, position, and inventory.

### 3.8 Invariants (state contracts)

1. Every item has exactly one `ItemLocation`. The containment graph is a forest: no
   cycles.
2. Every actor's `trackIndex` satisfies `0 ≤ trackIndex < room.trackSize`.
3. `hp ≤ maxHp`, and similarly for mana and move. Death is processed exactly once per life.
4. An actor is either idle, in recovery (`waitState > 0`), or casting
   (`casting != null`). It is never both in recovery and casting.
5. `actionQueue.size ≤ 10`.
6. A world mutation happens on the world thread or not at all.
7. A dormant room has no running timers, AI, or combat. Its timers are all paused.
8. Stack merges only happen between instances with identical deltas.

---

## 4. Interface & API Signatures

### 4.1 Package Declaration

```python
# zones/goblin_caves/package.star
package(
    api_version = 1,
    kind = "zone",                    # zone | lib | rules | skills
    title = "Goblin Caves",
    reset_pulses = 7200,              # zone reset interval (30 min); fires on_reset
)
```

### 4.2 Content Declarations

```python
room("entrance",
    name = "Cave Entrance",
    desc = "...",
    size = NORMAL,                                     # CRAMPED | NORMAL | LARGE
    regions = ["goblin_territory"],
    flags = [],                                        # "always_active", "dead_end_ok"
    exits = [
        exit("north", to=":tunnel",
             door=door(state="closed", key="//zones/goblin_caves:rusty_key",
                       open_blocks=[], closed_blocks=["sight", "sound", "noise"])),
        exit("east", to=":long_hall", blocks=ALL),     # never carries anything
        exit("south", to="//zones/forest:clearing", entry_index=-1),  # arrive at back
    ],
    spawns = [spawn(":goblin_scout", count=2, respawn_pulses=1200, at_index=2)],
    modifiers = {},                                    # §4.6
    commands = {"read": _read_sign},                   # zone verbs, tick lane
    before_exit = _guard_check,                        # hooks by name, §4.4
)

mob("goblin_scout",
    name = "a goblin scout", keywords = ["goblin", "scout"],
    level = 3, stats = {"str": 10, "dex": 14, "con": 9, "int": 6, "wis": 6},
    hp = 30, armor = 2, resists = {"fire": -10},
    skills = {"//skills/martial:kick": 40},
    senses = {"hearing": 1, "sight": 0},
    behaviors = [wander(radius=4, chance=0.1), aggressive(toward="players"),
                 assist(allies=["goblin_*"]), flee(at_hp_pct=20)],
    think = _scout_think, think_every = 4,
    dormant_recovery = "full",       # full | regen | none (§5.9)
    loot = [loot(":rusty_key", chance=0.25)],
    version = 2,
)

item("short_bow",
    name = "a short bow", kind = "ranged", range = "far", weight = 2,
    wield = "wield", dice = "1d6", damage_type = "pierce",
    stat_deltas = {"dex": 1},
    stackable = False, decay_pulses = None,
    commands = {}, on_use = None,
)

region("null_field", modifiers = {...})               # region-wide modifiers (§4.6)

def migrate(old_version, attrs):                       # optional, per package
    if old_version < 2:
        attrs["alarm_raised"] = attrs.pop("alarm", False)
    return attrs
```

### 4.3 Skills and Spells

```python
skill("fireball",
    kind      = "spell",              # free-form; rules interpret it
    cost      = {"mana": 25},
    warmup    = 6,                    # pulses in CASTING; 0 = instant
    lag       = 4,                    # waitState pulses after resolution
    range     = "far",                # melee | near | far | self | room
    target    = "enemy",              # enemy | ally | self | item | none
    channels  = ["spell", "noise"],
    interrupt = {"damage_pct": 10, "stun": True},
    attack_while_casting = False,     # default; True lets auto-attacks continue during warmup
    effect    = damage(dice="4d6", type="fire"),
    resolve   = None,                 # optional custom Starlark effect: def f(ctx)
)
```

**Range semantics:** `melee` means a band of 0, `near` means ≤1, `far` means any band in
the same room, `self` targets the caster only, and `room` affects everyone in the room.

**Effect primitives (v0):**

| Primitive | Purpose |
|---|---|
| `damage(dice, type)` | Deal damage. |
| `heal(dice)` | Restore hit points. |
| `apply_affect(name, duration, stat_deltas={}, flags=[], tick_every=None, on_tick=None, on_expire=None, expire_msg="")` | Attach a timed buff or debuff. |
| `dispel(filter)` | Remove matching affects. |
| `knockback(steps)` | Push the target along the track. |
| `pull(steps)` | Draw the target along the track. |
| `stun(pulses)` | Stun the target. |
| `summon(label, count=1, duration=None)` | Spawn mobs. |
| `sequence(*effects)` | Combine several primitives. |

### 4.4 Hook Catalog

Every hook is a named top-level function. Hooks named `before_*` may return
`ctx.deny(msg)` to veto the action. Any other return value means "allow". The return values
of `on_*` hooks are ignored.

| Hook | Veto | Attach to | Key ctx fields |
|---|---|---|---|
| `on_load` | — | Room, Mob, Item | `self` |
| `on_spawn` | — | Room, Mob, Item | `self` |
| `on_reset` | — | Room (zone reset) | `self` |
| `on_wake(ctx, elapsed_pulses)` | — | Room, Mob, Item | `self`, `elapsed_pulses` |
| `before_enter` / `on_enter` | ✓ / — | Room, Mob (observer) | `actor`, `direction`, `from_room` |
| `before_exit` / `on_exit` | ✓ / — | Room, Mob (observer) | `actor`, `direction`, `to_room` |
| `on_say` | — | Mob, Room | `actor`, `text` |
| `on_tell` | — | Mob | `actor`, `text` |
| `before_get` / `on_get` | ✓ / — | Item, Mob (observer) | `actor`, `item` |
| `before_drop` / `on_drop` | ✓ / — | Item, Mob (observer) | `actor`, `item` |
| `before_use` / `on_use` | ✓ / — | Item | `actor`, `item`, `target` |
| `on_give` | — | Mob (receiver), Item | `actor`, `item`, `target` |
| `before_attack` | ✓ | Mob, Item (equipped) | `actor`, `target`, `weapon` |
| `before_cast` / `on_cast_start` / `on_cast` | ✓ / — / — | Mob, Item (equipped), Room | `actor`, `skill`, `target` |
| `on_damage` | — | Mob, Item (equipped) | `actor` (source), `target`, `amount`, `type` |
| `on_death` | — | Mob, Room | `actor` (killer), `target` |
| `on_combat_tick` | — | Mob, Item (equipped) | `self`, `targets` |
| `on_tick` | — | Any (world tick) | `self` |
| `on_nearby` | — | Mob with `senses` | `event`, `actor`, `source_room`, `via_exit` |
| `think` | — | Mob | `self`, `proposals` |
| `commands[verb]` | — | Room, Item | `actor`, `args`, `target` |
| affect `on_tick` / `on_expire` | — | Affect | `self` (host), `affect` |

**Observer semantics:** Hooks on a mob fire for actions *by others in the same room*.
`ctx.self` is always the hook's owner.

### 4.5 The `ctx` Object

`ctx` is created fresh for each hook invocation and is invalid after the hook returns.
Entity accessors return **read-only views**. Every mutation goes through a verb. The
engine validates each verb and applies it immediately, on the world thread.

**Context fields:** `self`, `actor`, `target`, `item`, `room`, `direction`, `text`,
`args`, `event`, `source_room`, `via_exit`, `skill`, `weapon`, `amount`, `type`,
`proposals`, `elapsed_pulses`, `pulse`, `rng`.

**Actor view:**
- Identity: `id`, `name`, `label`, `is_player`.
- Stats: `level`, `hp`, `max_hp`, `mana`, `max_mana`, `move`, `max_move`, `stats`, `armor`,
  `resists`.
- Position: `room`, `track_index`, `band_of(other)`.
- State: `attrs`, `affects`, `skills`, `equipped(slot)`, `inventory()`,
  `has_item(label)`, `fighting()`, `is_casting`.

**Room view:**
- Identity: `label`, `name`, `size`, `regions`.
- Contents: `exits()`, `actors()`, `items()`.
- `exit(dir)`, which includes its effective `blocks` and door state.

**Item view:** `id`, `label`, `name`, `kind`, `count`, `weight`, `location`, `attrs`,
`container_state`.

**Verbs (v0):**

| Group | Verbs |
|---|---|
| Messaging | `say(speaker, text)`, `emote(actor, text)`, `send(target, text)` (one recipient), `echo(room, text, exclude=[])` |
| Veto | `deny(msg)`. Valid only as a return value from `before_*` hooks or `cast_allowed` modifiers. |
| Mob actions | `act(mob, command_line)`, `act_move(mob, dir)`, `act_attack(mob, target)`, `act_cast(mob, skill, target=None)`, `act_advance(mob, target)`, `act_retreat(mob, target=None)`, `move_toward(mob, dir_or_target)`. These enqueue tick-lane actions and apply only to mobs, never players. |
| Movement | `teleport(actor, room, index=None)`. Engine-validated; fires the enter and exit hooks. |
| Combat | `damage(target, amount, type, source=None)`, `heal(target, amount)`, `start_combat(a, b)`, `stop_combat(a, b=None)`, `apply_affect(target, ...)`, `remove_affect(target, name)` |
| Items | `spawn(label, at, index=0, count=1)`, `give(actor, label_or_item, count=1)`, `move_item(item, to)`, `destroy(item)`, `split(item, n)` |
| Doors and exits | `open_door(room, dir)`, `close_door`, `lock_door`, `unlock_door`, `set_exit_blocks(room, dir, channels)` |
| State | `set_attr(entity, key, value)`, `get_attr(entity, key, default=None)`, `del_attr(entity, key)` |
| Timers | `after(pulses, fn, **args) -> timer_id`, `cancel(timer_id)` |
| Simulation | `set_active(entity, reason)`, `clear_active(entity, reason)`, `set_anchor(entity, reason)`, `clear_anchor(entity, reason)` |
| Queries | `here(keyword)`, `find(label, scope="room")`, `enemies_near(actor, band)`, `allies_near(actor, band)`, `path_to(target) -> [dir]`, `roll(dice) -> int` |
| Randomness | `rng.int(lo, hi)`, `rng.chance(pct)`, `rng.choice(list)`. Seeded and deterministic; there is no other source of randomness. |

### 4.6 Rules Module and the Modifier Chain

`//rules:combat.star` must export the following functions. The engine calls them with
a rules `ctx` that is read-only, apart from `deny`.

| Function | Returns |
|---|---|
| `equipment_slots()` | `list[str]`. Default: `head, neck, body, arms, hands, finger_l, finger_r, waist, legs, feet, wield, offhand, held` |
| `to_hit(ctx)` | Hit chance in percent. `ctx.attacker`, `defender`, `weapon`, `band`, `skill`. |
| `damage(ctx)` | Raw damage, an int. |
| `mitigate(ctx, amount, type)` | Damage after armor and resists. |
| `initiative(ctx)` | Int; higher acts first within a combat round (§5.4.1). `ctx.actor`, `ctx.rng`. |
| `cast_allowed(ctx)` | `None` or `deny(msg)`. |
| `interrupt_check(ctx, amount)` | Whether a casting actor is interrupted, a bool. |
| `retreat_check(ctx)` | Success chance in percent, used when pinned. |
| `flee_chance(ctx)` | Percent. |
| `move_cost(ctx)` | Move points for a room transition. |
| `regen(ctx)` | `{"hp", "mana", "move"}` per world tick. |
| `carry_limit(ctx)` | Weight. |
| `encumbrance(ctx)` | Modifiers dict applied while over the limit. |
| `get_lag(ctx, band)` | Extra lag for picking up an item at a band. `None` means not allowed. |
| `xp_for_kill(ctx)`, `xp_to_level(level)` | Ints. |
| `on_player_death(ctx)` | `{"respawn": label, "xp_loss": int}` |

**Modifier points (v0):** `to_hit`, `damage`, `initiative`, `cast_allowed`, `move_cost`,
`regen`. The list grows with `api_version`.

**Chain order:** base rules result, then zone `package(modifiers=)`, then region
modifiers (sorted by region name), then room modifiers, then the actor's affects and
equipped items (sorted by affect id, then slot order).

Each modifier has the signature `fn(ctx, base) -> value`, or returns `ctx.deny(msg)` (only
for `cast_allowed`).

```python
room("antimagic_vault", modifiers = {
    "to_hit":       lambda ctx, base: base // 2 if ctx.weapon and ctx.weapon.kind == "melee" else base,
    "cast_allowed": lambda ctx, base: ctx.deny("Your magic fizzles into nothing."),
})
```

The harness and `@trace` print the full chain, for example
`rules.to_hit base=62 → region:null_field ×0.5 → 31 → roll 44 MISS`.

### 4.7 Behaviors (built in, implemented in Kotlin)

| Behavior | What it does |
|---|---|
| `wander(radius, chance)` | Moves randomly within `radius` exits of the home room. |
| `aggressive(toward, unless_level_above=None)` | Attacks matching targets on sight. |
| `assist(allies)` | Joins fights alongside allies. Supports glob labels. |
| `flee(at_hp_pct)` | Flees below the HP threshold. |
| `guard(exit)` | Denies the exit to non-allies. Implemented as a built-in `before_exit` observer. |
| `follow(target)` | Follows a target. |
| `pursue(target)` | Uses `path_to` across the whole world. |
| `sentinel()` | Never leaves its room. |
| `scavenge()` | Picks up items. |

Each behavior produces at most one `Proposal(action, priority, source)` per think cycle.

### 4.8 Mob Think

```python
def _scout_think(ctx):
    # ctx.proposals: list sorted by priority desc; ctx.proposals[0] is what would run.
    if ctx.self.hp < ctx.self.max_hp // 2 and ctx.allies_near(ctx.self, "far"):
        return ctx.act(ctx.self, "shout Help!")
    return None   # None = accept the top proposal; ctx.act_* = override; "idle" = do nothing
```

### 4.9 Commands

**Player commands, fast lane:** `say`, `tell`, `gossip`, `shout`, `whisper`, `look`,
`score`, `inventory`, `equipment`, `who`, `help`, `quests`, `stop`, `clear`, `quit`.

**Player commands, tick lane:**
- Movement: `north`, `south`, `east`, `west`, `up`, `down` and other exits, `advance`,
  `retreat`, `flee`.
- Combat: `kill` / `attack`, `cast`, `use`.
- Items: `get`, `drop`, `give`, `put`, `wear`, `wield`, `remove`.
- Doors: `open`, `close`, `lock`, `unlock`.
- Zone-defined `commands`.

**Global commands:** Privileged packages may register global commands:
`global_command("quests", fn, lane="fast")`. This is how `//lib:quest.star` provides
`quests`.

**Builder commands** (scoped to the builder's own packages; `debug` items only):

| Command | Purpose |
|---|---|
| `@goto <label>` | Move to a room. |
| `@stat <target>` | Show an entity's full state. |
| `@spawn <label>` | Spawn a mob or item (flagged `debug`). |
| `@trace zone\|entity\|hook\|rules\|off` | Stream hook execution. |
| `@errors [package]` | Show recent diagnostics. |
| `@where` | Show your track index and bands numerically. |
| `@eval <expr>` | Starlark REPL; `--dev` only. |

**Instructor commands** (global):
- Deployment and reload: `@reload <label...>`, `@deploy`.
- Quarantine: `@quarantine <pkg>`, `@unquarantine <pkg>`.
- Monitoring: `@bubble`, `@stats`, `@who` (with IP and transport).
- Operations: `@snapshot`, `@shutdown [delay] [reason]`.
- Accounts: `@role <account> <role> [scope]`, `@invite <role>`, `@audit item <id>`.

### 4.10 Network Protocol

- **Listeners:**
  - Raw telnet on `:4000` (`--dev`, or explicitly enabled).
  - Telnet over TLS on `:4443`.
  - WebSocket over TLS (`wss://host/ws`), with the bundled web client served from
    `https://host/`.
- **Telnet options:** NAWS, TTYPE/MTTS, GMCP, EOR (falls back to GA), and CHARSET (UTF-8
  only). Color depth (none, 16, or 256) is taken from MTTS. No MSDP or MXP.
- **GMCP packages (v0):**
  - `Core.Hello` and `Core.Supports`.
  - `Char.Vitals`, `Char.Status` (including casting progress and waitState), and
    `Char.Affects`.
  - `Room.Info` (label, name, exits, regions) and `Room.Bands` (actors and items grouped
    by band, from the viewer's perspective).
  - `Comm.Channel.Text`.
  - `Gups.Trace` (builders only).
- **WebSocket framing:** One JSON message per frame:
  `{"t":"text","d":"..."}` | `{"t":"gmcp","p":"Char.Vitals","d":{...}}` | `{"t":"input","d":"..."}`.
- **Limits:**
  - Input line ≤ 512 bytes; longer lines are truncated with a notice.
  - Per-session output buffer ≤ 256 KB. A client that exceeds it is disconnected as
    "too slow".

### 4.11 Kotlin Seams

```kotlin
data class ClientInputEvent(val session: SessionId, val line: String, val receivedNanos: Long)
sealed interface WorldControlEvent  // Reload(labels), Deploy(commit), Shutdown(delay), Connect, Disconnect

interface ScriptHost {
    fun loadPackages(paths: List<PackagePath>): LoadResult          // all-or-nothing per package
    fun invokeHook(owner: Entity, hook: HookName, ctx: HookContext): HookOutcome
}
interface RulesEngine {                                              // Starlark-backed, Kotlin fallback
    fun toHit(c: AttackContext): Int
    fun damage(c: AttackContext): Int
    /* ... one method per §4.6 function ... */
}
interface CheckpointSink { fun submit(batch: CheckpointBatch) }      // non-blocking
interface OutputSink { fun send(text: String); fun gmcp(pkg: String, json: JsonElement) }

interface GupsPlugin {                                               // ServiceLoader entry point
    val id: String
    fun register(r: PluginRegistry)   // addBuiltin(), addCommand(), addEffectPrimitive(), addGmcp()
}
```

### 4.12 Test Harness API (Starlark)

```python
def test_guard_blocks_without_pass(t):
    w = t.world(seed=42, load=["//zones/goblin_caves"])
    p = w.spawn_player("tester", at=":entrance", level=3)
    out = p.do("north")
    t.contains(out, "The guard steps in front of you.")
    t.eq(p.room, ":entrance")

def test_scout_flees_when_hurt(t):
    w = t.world(seed=7, load=["//zones/goblin_caves"])
    g = w.spawn("//zones/goblin_caves:goblin_scout", at=":tunnel")
    w.damage(g, pct=85)
    w.run(pulses=8)
    t.ne(g.room, ":tunnel")
```

| Object | API |
|---|---|
| `t` | `world(seed, load, rules=None)`, `eq`, `ne`, `true`, `false`, `contains`, `not_contains`, `fail` |
| `w` | `spawn_player`, `spawn`, `run(pulses)`, `damage`, `set_attr`, `room(label)`, `trace(on)`, `diagnostics()` |
| player handle `p` | `do(line) -> output`, `output()`, plus view fields |

The rules package is always loaded implicitly.

### 4.13 CLI

| Command | Purpose |
|---|---|
| `gups serve --dev\|--prod [--fresh] [--world DIR] [--db FILE]` | Run the server. |
| `gups test <label-pattern...>` | Run tests, e.g. `//zones/goblin_caves/...`. |
| `gups lint <label-pattern...>` | Run the static checks. |
| `gups docs [--out DIR]` | Generate the reference from `@StarlarkBuiltin`/`@StarlarkMethod` annotations, the hook catalog, and the effect primitives. |
| `gups repro <diagnostic-id\|bundle.json>` | Replay a diagnostic in the harness. |
| `gups backup` / `gups restore <file>` | Back up or restore the database. |
| `gups deploy --commit SHA` | Called by CI; talks to the admin port. |
| `gups invite --role R`, `gups import-roster roster.csv` | Create accounts. |

### 4.14 Admin Port

The admin port is HTTP, bound to localhost or a private interface, and requires a bearer
token.

| Endpoint | Purpose |
|---|---|
| `GET /metrics` | Prometheus metrics (Micrometer). |
| `POST /deploy` | `{commit}` |
| `GET /healthz` | Health check. |

**Metric names:**

| Area | Metrics |
|---|---|
| Pulse health | `gups_pulse_duration_seconds` (histogram), `gups_pulse_overruns_total`, `gups_world_input_queue_depth` |
| Scripts | `gups_script_steps_total{package}`, `gups_script_errors_total{package,kind}`, `gups_quarantined_packages` |
| Population | `gups_bubble_rooms`, `gups_bubble_entities`, `gups_sessions{transport}` |
| Persistence | `gups_checkpoint_duration_seconds`, `gups_checkpoint_entities`, `gups_checkpoint_backlog` |

---

## 5. Core Logic, Control Flow & Edge Cases

### 5.1 World Loop

The world coroutine selects over the input channel and the next pulse boundary. Pulse
boundaries come from `System.nanoTime()`.

- **Between pulses:** Each dequeued `ClientInputEvent` is classified (§5.2). Fast-lane
  commands execute immediately. Tick-lane commands are appended to the actor's queue.
- **At each pulse boundary**, `pulse++` and then these phases run in this exact order:
  1. **Timers:** Fire `ScriptTimer`s and affect ticks/expiries due at this pulse, ordered by
     `(due, id)`.
  2. **Action pulse:** For each active actor, in **initiative order** (§5.4.1):
     - If `casting`, advance it. When the warmup completes, resolve the action (§5.3).
     - Otherwise, if `waitState > 0`, decrement it.
     - Otherwise, if the queue is non-empty, pop the head and execute it.
     - Otherwise, if the actor is a mob whose think cadence is due, run the think cycle
       (§5.8).
  3. **Combat round** (`pulse % 8 == 0`): Roll initiative for the new round (§5.4.1).
     Then, for each engaged active actor in initiative order, resolve one auto-attack
     (§5.4) and fire `on_combat_tick`.

  Within phases 2 and 3, actors act in **initiative tiers**, from the highest
  initiative to the lowest. An actor killed or incapacitated by a *higher* tier is
  skipped for the rest of that phase, so a faster actor can kill an enemy before it
  strikes, even within the same pulse. Actors in the *same* tier act simultaneously
  (§5.4.1): all of their actions happen.
  4. **World tick** (`pulse % 120 == 0`):
     - Regenerate vitals via `rules.regen` and the modifier chain. Actors with a
       `no_regen` affect are skipped.
     - Fire `on_tick` for active entities.
     - Process zone resets and respawns that are due.
     - Advance item decay.
     - Capture a periodic checkpoint if the checkpoint interval has passed (§5.11).
  5. **Deferred perception:** Dispatch the `on_nearby` events queued during this pulse
     (§5.7).
  6. **Bubble maintenance:** Apply bubble changes recorded during this pulse (§5.9).
  7. **Output flush:** Flush output and send GMCP deltas (vitals and bands, only when
     changed).
- **Overrun policy:** If a pulse finishes after the next boundary, the scheduler does
  **not** run the missed pulses back to back. It skips to the next future boundary and
  increments `pulse_overruns` once per skipped boundary. The pulse counter advances only
  once per pulse actually executed, so a lag spike slows game time instead of making it
  lurch forward.
- **Overrun reporting:** If more than 10% of pulses in a rolling minute overrun,
  instructors are notified with the top step-consuming package. Nothing is
  auto-quarantined for this.

### 5.2 Command Classification and Admission

1. Parse the verb, resolve aliases, and look it up in the engine commands, global
   commands, then room and item `commands`. Ambiguity resolves in that order.
2. **Fast lane:**
   - Rate limiting uses a per-session token bucket: capacity 20, refill 5 tokens/s.
   - When the bucket is empty, input is dropped with "Slow down." More than 3 drops
     within 60s disconnects the session.
3. **Tick lane:**
   - The command is appended to the actor's queue.
   - If the queue already holds 10, it's rejected with "You can't plan that far ahead."
     and nothing is enqueued.
4. **`stop` / `clear`** (fast lane):
   - Flushes the queue.
   - Cancels a channeled cast, with no cost paid and no lag applied.
   - Does **not** end combat engagement or clear existing `waitState`.

### 5.3 Action Lifecycle (tick lane)

For each popped action:

1. **Validate:** Check that the target still exists and is in range (band), that the
   actor can pay the cost, and that it isn't stunned or silenced.
   - On failure, send a message. No lag is applied.
2. **Veto phase:**
   - Fire `before_*` hooks in dispatch order (§5.6). For casts, `cast_allowed` runs through
     the modifier chain first.
   - The first `deny` cancels the action, sends its message to the actor, and applies
     **no** lag.
3. **Warmup:**
   - If `warmup > 0`, set `casting = CastState(action, endsAt)` and fire `on_cast_start`.
   - Each incoming `damage` event calls `rules.interrupt_check`. A `stun` always
     interrupts if `interrupt.stun`.
   - An interrupt clears `casting`, pays no cost, and applies `lag / 2` (rounded down).
4. **Re-validate** when the warmup completes. The target may have moved out of range or
   died. A failed re-validation pays no cost and applies `lag / 2`.
5. **Resolve:**
   - Pay the cost.
   - Apply the `effect`, or call `resolve(ctx)`. All mutations go through verbs.
   - Fire the `on_*` hooks (`on_cast`, `on_damage`, …).
6. **Recovery:** Set `waitState = lag`.

Instant actions (`warmup == 0`) run steps 1, 2, 5, and 6 in the same pulse.

### 5.4 Combat Rounds

- **Engagement:**
  - Combat starts through `kill`/`attack`, `aggressive`/`assist` behaviors, a hostile
    skill landing, or `ctx.start_combat`.
  - It ends on death, on either party leaving the room, on a successful `flee`, or with
    `ctx.stop_combat`.
- **Auto-attacks:**
  - Every 8 pulses, each engaged actor auto-attacks one target: its current primary
    target, or the lowest-`EntityId` remaining opponent.
  - Auto-attacks are independent of `waitState` and of queued actions, so active skills
    weave between swings.
  - A **casting** actor does not auto-attack, unless the skill being cast sets
    `attack_while_casting = True`.
- **Range:** An auto-attack happens only if the target's band is within the wielded
  weapon's `range` (unarmed or melee: `MELEE`; a ranged weapon: up to `FAR`). Otherwise no
  swing occurs that round, and the actor is told "<target> is out of reach."
- **Resolution:**
  1. Call `before_attack` (vetoable).
  2. Roll `rng.int(1,100)` against `to_hit` (through the modifier chain).
  3. On a hit, compute `damage` (chain), then `mitigate`, then `ctx.damage`.
- **Death:**
  - Engine-owned. It fires `on_death` (target's own hook, then the room, then observers).
  - **For mobs:** awards XP via `xp_for_kill` and creates a corpse container holding the
    inventory and loot rolls, with decay.
  - **For players:** applies `rules.on_player_death`, moves the character to `respawn`, and
    leaves a corpse.
  - Death clears `casting`, the queue, and engagement. It's processed exactly once. During
    phases 2 and 3 it's deferred to the end of the current initiative tier (§5.4.1).
- **Dormancy:** Combat never continues in a dormant room. When a room goes dormant, its
  NPCs disengage. How their vitals recover is set per mob by `dormant_recovery` (§5.9).

#### 5.4.1 Initiative

- **Rolling:** At the start of each combat round, the engine calls `rules.initiative(ctx)`
  for every engaged active actor, through the `initiative` modifier point. The result is
  an int; higher acts first. The rules module may use stats, equipment, affects and
  `ctx.rng`, so initiative can be a fixed rating or a roll.
- **Duration:** The values hold for the whole round: the combat round phase and the 7
  action pulses after it. A haste affect that lands mid-round takes effect next round.
- **Tiers:** Engaged actors with the same initiative form one **tier**. Tiers run from
  the highest initiative to the lowest. Actors that aren't engaged form one final tier
  after every engaged tier.
- **Ties are simultaneous.** Every actor in a tier gets its action, even if another actor
  in the same tier kills, stuns, or displaces it first:
  1. **Snapshot:** When a tier starts, the engine records, for each actor in it: whether
     it can act, its room, and its track index. Same-tier actions are validated (target
     present, band in range, actor able to act) against this snapshot, not the live
     state.
  2. **Execution:** The tier's actions then execute one after another. Damage, healing,
     affects, and track movement apply immediately, and `on_damage` fires immediately.
     Ascending `EntityId` fixes the sequence, which decides only message order and the
     order of `rng` draws, never *whether* an actor acts.
  3. **Deferred removal:** Anything that would take an actor out of the fight is
     committed at the **end of the tier**, in ascending `EntityId` order. That covers
     death processing (`on_death`, XP, corpse) and room transitions (movement, `flee`,
     `teleport`).
  4. **Example:** A and B tie and each deals a killing blow to the other. Both blows
     land, both `on_damage` hooks fire, and both deaths are processed when the tier ends.
- **Mortally wounded:** An actor at `hp ≤ 0` waiting for end-of-tier death processing is
  *mortally wounded*. It still takes its own action, and can still be targeted. If a
  same-tier heal brings it back above 0 before the tier ends, it doesn't die.
- **Stuns between tiers:** A stun or incapacitation applied during a tier stops the
  target from acting in *later* tiers, but never stops a same-tier action.
- **Determinism:** The rolls use the seeded `rng`, so the same seed and inputs always
  produce the same order. That's what keeps replays exact.
- **Fallback:** The built-in Kotlin ruleset uses `dex` plus `level`, with no roll.
- **Visibility:** `@trace rules` prints each round's initiative order, and `Char.Status`
  carries the character's own initiative.

### 5.5 Intra-Room Spatial Model

All combat is self-contained within a single room. No projectiles cross exits.

```
Room Track Index:     [ 0 ]            [ 1 ]            [ 2 ]            [ 3 ]
Room Layout:         (Front)          (Mid 1)          (Mid 2)          (Back)
Entities:          [Player A]       [Goblin 1]                       [Goblin Archer]

Player A sees:  [MELEE] (nobody)   [NEAR] Goblin 1   [FAR] Goblin Archer
```

- **Track size:** Each room's `size` sets its track size. In a `CRAMPED` room everyone
  shares index 0, so all combat is melee.
- **Bands:** Viewers never see raw indices (except builders, via `@where`). `look` and
  `Room.Bands` group actors and items by band: `|X−Y| = 0` is `MELEE`, `1` is `NEAR`,
  `≥ 2` is `FAR`.
- **`advance <target>`:** Steps 1 index toward the target. It's a normal tick-lane action,
  with lag set by rules (default 2). It's a no-op with a message if the actor is already
  at the target's index.
- **`retreat [from <target>]`:**
  - Steps 1 index away from the target. With no target, it moves away from the nearest
    hostile, preferring the direction with more room.
  - It's impossible at a track edge, and the actor is told so with no lag.
  - **When pinned** (a hostile shares the actor's index), it requires a `retreat_check`
    roll. Failure applies extra lag (default 4) and no movement.
- **Room transitions:**
  - Non-combat movement costs one action plus `move_cost` move points.
  - Arrivals are placed at the exit's `entryIndex`: by default `0`, and `-1` maps to
    `trackSize−1`. When the destination is smaller, the index is clamped.
- **Pinning and exits:**
  - A normal exit command works from any index **unless a hostile is at the actor's
    index**.
  - A pinned actor must `retreat` first, or `flee`.
- **`flee`:**
  - Warmup 0. It rolls `flee_chance` and ignores pinning.
  - **On success:** the actor leaves through a random exit whose effective `blocks` doesn't
    make it impassable (closed or locked doors are excluded), and engagement ends.
  - **On failure:** lag (default 4).
- **Items on the ground:**
  - Items sit at the dropper's index.
  - `get` uses `rules.get_lag(band)`. The default is 0 for `MELEE`, 2 for `NEAR`, and not
    allowed at `FAR`.

### 5.6 Event Dispatch

- **Order:**
  1. The acting entity's own hook.
  2. The room's hook.
  3. The other actors in the room, ascending `EntityId`.
  4. The items in the room, then items carried or equipped by those actors, ascending
     `EntityId`.

  For `before_*` hooks, the first `deny` short-circuits.
- **Timing:** `before_*` hooks run when the action is *executed* (popped), not when it's
  enqueued.
- **`on_*` hooks** observe the post-change state. They can't undo it, only issue new verbs.
- **Cascades:**
  - Verbs that cause events (`damage` → `on_damage` → `on_death` → loot `on_spawn`) dispatch
    synchronously, nested.
  - The maximum nesting depth is 8. Deeper events are dropped with a diagnostic.
  - All nested hooks share the top-level event's step budget (§6.1).
- **Quarantine:** Hooks of a quarantined package aren't invoked.

### 5.7 Adjacent-Room Perception

- **Opt-in:** It's per mob, through `senses`, with a radius of 0 or 1. The default is 0
  (same room only).
- **Channels by event type:**

| Event | Channel | Carries to |
|---|---|---|
| `say`, `emote` | `SOUND` | `hearing ≥ 1` |
| `shout`, combat start, combat round, `death` | `NOISE` | `hearing ≥ 1` |
| `enter`, `exit` (visible movement) | `SIGHT` | `sight ≥ 1` |
| `whisper`, `tell` | — | never |

- **Propagation:** An event in room R reaches a listening mob in a room adjacent to R only
  if the connecting exit's effective `blocks` doesn't contain the event's channel. A closed
  default door blocks `SIGHT` and `SOUND` but not `NOISE`. `blocks = ALL` blocks
  everything.
- **Delivery:**
  - It's **deferred** to phase 5 of the current pulse, and calls `on_nearby` on the mob.
  - `ctx.via_exit` is the mob's own exit direction leading toward the source.
  - Only mobs that are themselves simulated (in the bubble, or active) receive events.
- **Notify only:** A mob can never veto from an adjacent room.
- **Budget:** The hook runs against the *listening* mob's package for step budget and
  quarantine.
- **Deduplication:** Events of the same type from the same source room are coalesced, at
  most one per mob per pulse.

### 5.8 Mob AI

- A mob is "a player controlled by code". It acts only by enqueueing tick-lane actions,
  and pays the same warmup, lag, pinning, retreat, and flee costs.
- **Think cycle:** It runs when the mob is idle (empty queue, `waitState == 0`, not casting)
  and `pulse − lastThink ≥ thinkEvery`. Each cycle:
  1. Each behavior proposes at most one action.
  2. Proposals are sorted by priority, then by behavior declaration order.
  3. If `think` is defined, it's called with `ctx.proposals`. A `None` return enqueues the
     top proposal, an `act_*` call overrides it, and `"idle"` skips the turn.
  4. Without `think`, the top proposal is enqueued.
- **Scope:** Thinking only happens for mobs in the bubble or marked active.

### 5.9 Reality Bubble

- **Anchors:** Every in-world player (including link-dead ones), plus every entity with a
  non-empty `anchorReasons`.
- **Active set:** All rooms within `BUBBLE_RADIUS = 3` exits of any anchor's room. Exit
  `blocks` and door state are ignored when computing it. Rooms flagged `always_active`
  are always in the set.
- **Simulated entities:** Everything in active rooms, plus entities with non-empty
  `activeReasons`, wherever they are. Pets and followers bonded to a player get the
  reason `bond:<player_id>`.
- **Active vs anchor:**
  - `active` simulates only the entity itself: its queue, timers, AI, and movement. Rooms
    it passes through stay dormant.
  - A simulated entity can't target, fight, or perceive *dormant* entities. They're
    inert to it until a bubble covers them.
  - `anchor` projects a full radius-3 bubble.
- **Reference counting:** Both sets are ref-counted by reason (`death_mark:<player_id>`).
  The entity stops being active or anchored only when its last reason is cleared.
- **Recomputation:**
  - It's incremental. When an anchor changes rooms, a BFS from the old and new rooms
    produces the set difference.
  - Rooms leaving the set go dormant. Rooms entering it wake up.
  - Changes are applied in phase 6.
- **Going dormant:**
  - AI, `on_tick`, and `on_combat_tick` stop.
  - Each `ScriptTimer` converts from `due` to `remaining`.
  - Affects are **not** paused. Their `expiresAt` is an absolute pulse, so they keep
    running out while the room is dormant.
  - Combat disengages (§5.4).
- **Waking (lazy catch-up, never simulation):**
  - Timers convert back from `remaining` to `due`.
  - Respawns, resets, regen, and decay whose absolute deadlines passed are applied in one
    pass.
  - **Mob vitals** recover according to the mob's `dormant_recovery`:

    | Mode | On wake |
    |---|---|
    | `full` (default) | Vitals reset to max, unless the mob has a `no_regen` affect. Classic MUD behavior. |
    | `regen` | Catch-up regen for the elapsed world ticks, computed with `rules.regen` and the modifier chain, so slowed regen stays slow. |
    | `none` | Vitals stay exactly as they were when the room went dormant. |

  - **Catch-up is piecewise.** The elapsed time is split at every affect expiry that fell
    inside it, and regen is computed per segment with the affects active in that segment.
    A 15-minute `no_regen` affect therefore suppresses exactly 15 minutes of healing,
    then normal regen resumes.
  - Affects that expired during dormancy are removed, and their `on_expire` hooks fire,
    in expiry order, before `on_wake`.
  - **Raid boss example:** when the boss drops below a threshold, its script applies
    `apply_affect(boss, "weakened", duration=3600, flags=["no_regen"])`. The boss stays
    weakened for 15 minutes even if the party runs far away, whatever its
    `dormant_recovery` mode. If the boss should also keep *acting* while players are
    away, mark it `active` instead (see "Active vs anchor" above).
  - Then `on_wake(ctx, elapsed_pulses)` fires on the room, then its mobs and items.
  - Nothing that would have happened while dormant is replayed. In particular, there's no
    offscreen wandering.
- **Pathfinding:** `ctx.path_to` runs a BFS over the full room graph. Results are cached
  per `(source, dest)` and invalidated when any door state, exit, or reload changes.

### 5.10 Hot Reload and Deploy

**`--dev` auto-reload:** The file watcher debounces for 200 ms, then posts
`Reload(changedPackages)`.

**Reload algorithm** (on the world thread, per package):

1. Evaluate the new package into fresh, frozen prototypes in isolation. Every label must
   resolve, including cross-package exits and loads, and `api_version` must match.
2. **On failure:** Keep the old version live. Report the error with file, line, and a
   caret snippet, in game to the owner and builders and in the `@errors` log. Stop here.
3. **On success:** Atomically swap the prototype table entries for that package.
4. Reconcile live instances (§3.4): run migrations, overlay deltas, and create tombstones.
5. Rebind `HookRef`s by `(file, name)`. Timers whose function no longer exists are dropped
   with a diagnostic. Hooks fire against the new code from the next event onward.
6. Invalidate the path cache, then take a checkpoint.

**Production deploy:**

1. After a merge, CI calls `gups deploy --commit SHA`, or an instructor runs `@deploy`.
2. The server fetches that commit into a staging directory and takes a pre-deploy
   snapshot.
3. It computes the changed packages and runs the reload algorithm on them as one batch:
   all succeed or none are swapped.
4. It updates `meta.world_commit` and syncs builder scopes from `CODEOWNERS`.
5. A deploy clears quarantine for the packages it changes.

A change to `MODULE.gups` (an engine upgrade) needs a restart with the new image. Hot
reload is refused for it.

### 5.11 Persistence and Checkpoints

- **Dirty tracking:** Any change, whether through a verb or engine code, sets the entity's
  `dirty` flag.
- **Periodic checkpoint** (every `checkpoint_minutes`, default 30, set in `MODULE.gups`;
  checked at world ticks). Writes are kept rare to spare the disk: crashes are expected to
  be rare, and every graceful shutdown and reload saves.
  1. The world thread serializes all dirty, non-`debug` entities, plus `zone_states`, the
     `meta` pulse, and `next_entity_id`, into a `CheckpointBatch` of JSON bytes.
  2. It clears the dirty flags and submits the batch.
  3. The writer commits each batch in **one transaction**, in FIFO order.
- **Immediate capture** (at the end of the triggering action's cascade): it captures every
  entity dirtied during that cascade, plus `item_events` rows, at once. The capture is held
  in memory and written in the same transaction as the next periodic checkpoint (or the
  final one at shutdown).
  - Triggers: an item changing hands between players, a shop purchase, a bank deposit, a
    level-up, character creation or deletion, and logout or quit.
  - The receive and the give are always captured at the same moment and land in the same
    transaction, which closes the classic give-then-crash duplication exploit.
- **Back-pressure:**
  - If a batch is still pending when the next periodic capture happens, the new capture
    is **merged** into the pending batch (last write wins per entity). It isn't queued
    separately.
  - If a submitted batch is still uncommitted after 3 world ticks, an alert fires.
- **Crash guarantee:**
  - At most one checkpoint interval of state is lost (by default 30 minutes), including
    held immediate captures.
  - An ownership transfer is either saved with both sides or lost with both sides.
  - The database is never inconsistent.
- **Shutdown and reload:** `@shutdown` and a successful reload both perform a final
  checkpoint (§5.10).
- **Backups:**
  - The SQLite online-backup API takes snapshots hourly (keep 48) and daily (keep 14).
  - A snapshot is also taken before every deploy and on `@snapshot`.
  - `gups restore` rolls the world back, for example to before a broken merge.
- **Startup:**
  1. Open the database and run engine schema migrations.
  2. Load the course world packages.
  3. Load `players` and `world_entities`. With `--fresh`, skip the world state described
     in §2.4.
  4. Reconcile all instances against prototypes.
  5. Set `pulse` from `meta`.
  6. Every room starts dormant. The bubble forms as players log in.

### 5.12 Sessions and Authentication

- **Accounts:**
  - Accounts are created by `gups import-roster` or by redeeming an invite code at login.
  - Open signup is a disabled config flag.
- **Login:**
  - The password is verified with Argon2id (m = 64 MiB, t = 3, p = 1).
  - Failures are rate-limited per account and per IP: 5 failures per 15 min, then
    exponential backoff up to 15 min.
  - The web client may exchange a successful login for a 30-day session token, stored
    hashed and revocable.
- **Characters:** After login, the player chooses a character, or creates one subject to
  rules-defined starting stats. The character is placed in its saved room, which becomes
  an anchor.
- **Disconnects:**
  - A dropped connection makes the character **link-dead**. It stays in the world, keeps
    anchoring its bubble, and keeps fighting with auto-attacks only, for 3 minutes.
    Disconnecting can't be used to escape combat.
  - Reconnecting within that window resumes the session.
  - Otherwise the character auto-quits, with an immediate checkpoint.
- **Idle players:** After 30 minutes without input, a player is auto-quit. Instructors are
  exempt.
- **`--dev`:** A passwordless `instructor` account that only accepts connections from
  `127.0.0.1`/`::1`.

### 5.13 Developer Tooling Behavior

- **`@trace`:**
  - Streams one line per hook invocation, verb call, and rules chain evaluation, with step
    counts.
  - Scope: on the shared server, the builder's own packages plus their own character's
    rolls. In `--dev`, everything.
  - It's rate-limited to 50 lines per pulse per session, after which it prints a
    summary line.
- **`@eval`** (`--dev` only): Evaluates Starlark with a live `ctx` bound to the
  caller's character (`self`, `room`). Its verbs mutate the live world.
- **`gups docs`:** Emits the HTML reference, tagged with `api_version`, plus the changelog.

---

## 6. Error Handling & Invariants

### 6.1 Script Resource Limits

Limits apply per top-level event: one hook invocation plus its synchronous cascade.

| Context | Step limit | Wall-clock backstop |
|---|---|---|
| Zone hooks, `think`, `on_nearby`, timers, commands | 50,000 | 20 ms |
| Rules module and modifier chain (per evaluation) | 200,000 | 20 ms |
| Package top-level evaluation (load) | 5,000,000 | 2 s |
| `@eval` | 1,000,000 | 1 s |

- The step counter is the **deterministic** limit: the harness and production agree on it
  exactly. The wall-clock guard only catches slow host calls.
- **Allocation caps:**
  - Any single string or list over 1 MB raises an error.
  - `customAttributes` over 64 KB per entity rejects the `set_attr` that would exceed it.
- Starlark's own guarantees still apply: frozen module globals, no I/O, and no unbounded
  recursion.

### 6.2 Script Error Handling

When a hook raises an error, exceeds a limit, or passes a bad verb argument:

1. The hook aborts. Verbs it **already applied stay applied**: there's no rollback, and
   each verb is individually atomic and invariant-preserving. Authors are taught to do
   their checks first and their verbs last.
2. The triggering action **proceeds as if the hook returned `None`**. A crashing
   `before_exit` never traps a player.
3. A diagnostic row is written. It contains:
   - The package, label, hook, file, line, and kind.
   - The message and Starlark stack.
   - A `ctx` summary.
   - The seed and pulse.
   - A **repro bundle**: the affected room's entities from the last checkpoint, plus the
     inputs (tick-lane commands and `say`s, never tells) of actors in that package's rooms
     since that checkpoint.

   Repro is exact in the harness and best-effort from production.
4. **Who sees what:**
   - Online owners of the package get a one-line in-game alert, with at most one per
     package per 10 s.
   - Players see nothing. For a failed zone command, they see "Nothing happens."

### 6.3 Quarantine (circuit breaker)

- **Trigger:** More than 20 errors from one package within 60 s.
- **Effect:**
  - All of that package's hooks, `think` functions, commands, and timers are disabled.
    Paused timers are kept, not dropped.
  - Its rooms, exits, items, and mobs remain as static content. Mobs fall back to their
    built-in behaviors only.
- **Notifications:** Instructors and the package's owners are notified.
- **Clearing:** Quarantine clears on the next successful deploy of that package, or by
  `@unquarantine`. Quarantine state persists in `zone_states`.
- **Rules package exception:**
  - If the rules package fails to load or throws, the engine falls **back** to the
    built-in Kotlin default ruleset for that call. A load failure falls back for
    everything.
  - It logs loudly and alerts instructors. Combat keeps working.

### 6.4 Engine-Side Failures

| Failure | Handling |
|---|---|
| Uncaught Kotlin exception in a command or phase | Log with context and abort that action only. The pulse continues. More than 100 per minute triggers an instructor alert. |
| Checkpoint write fails | Retry with backoff. The batch stays pending, and later captures merge into it (§5.11). Alert after 3 world ticks. Never block the world thread. |
| Disk full / database unwritable | As above. Also refuse new logins with a maintenance message until a write succeeds. |
| Slow client | Output buffer over 256 KB disconnects the client. The character goes link-dead. |
| Malformed telnet or GMCP | Ignore the bad sequence and continue. Repeated garbage disconnects after 5 errors. |
| Deploy fetch or load failure | The old world stays live. The diagnostic goes to the CI response and instructors. |
| Plugin fails to load | Skip the plugin and log it. If the world references its builtins, those packages fail to load (old version kept). |

### 6.5 Invariant Enforcement

- The invariants in §3.8 are enforced in the Kotlin verb implementations, never in scripts.
- In `--dev` and in the harness, an invariant check runs at the end of every pulse. A
  violation fails the test or logs an error with a stack trace.
- In `--prod`, the check runs at every world tick before checkpoint capture. A violation
  logs an error and alerts, and the offending entity is excluded from the checkpoint.

---

## 7. Verification & Test Plan

### 7.1 Engine Tests (Kotlin, `gups-engine` CI)

- **Matrix:** Run on `linux-x64`, `linux-arm64`, and `macos-arm64`.
- **Unit tests:**
  - **Scheduler:** phase ordering, combat round and world tick cadence, overrun skipping.
  - **Command pipeline:** lane classification, the token bucket, queue cap, and
    `stop`/`clear` semantics.
  - **Action lifecycle:** warmup, interrupt, re-validation, lag, and veto-no-lag.
  - **Initiative:** tier ordering within a round; an actor killed by a higher tier never
    acting later in that phase; same-tier mutual kills both landing; a same-tier flee and
    an attack on the fleeing actor both resolving; a mortally wounded actor healed within
    its tier surviving; identical order and outcomes across replays with the same seed.
  - **Tactical track:** bands, advance and retreat, pinning, flee, entry index clamping,
    and cramped rooms.
  - **Event dispatch:** ordering, short-circuiting, cascade depth limit, and the shared
    step budget.
  - **Perception:** channels × exit `blocks` × door states, deferral, and coalescing.
  - **Rules chain:** ordering and fallback to the Kotlin ruleset.
  - **Reality bubble:** incremental BFS correctness against a full recompute, the
    dormant/wake timer conversion, lazy catch-up, and active vs anchor semantics.
  - **Dormant recovery:** each `dormant_recovery` mode, and piecewise catch-up across
    `no_regen` and slowed-regen affects that expire during dormancy.
  - **Persistence:** schema migrations, and reconciliation (version migrate, delta overlay,
    tombstones).
  - **Auth:** Argon2id, rate limiting, invites, and CODEOWNERS mapping.
  - **Telnet:** option negotiation against recorded client transcripts (Mudlet, TinTin++,
    raw `nc`).
- **Property-based tests:**
  - Random sequences of item verbs never violate the single-location or forest invariant.
  - Random checkpoint, kill, and restore sequences never duplicate or lose an item that
    was part of a committed transfer.
- **Determinism:** The same seed and input script produce byte-identical output and final
  world state across runs and platforms.
- **Crash consistency:** `kill -9` the server at random points during checkpoint writes,
  hundreds of iterations. Every restart must open cleanly and satisfy §3.8.
- **Load test:**
  - 500 scripted bot connections over TLS telnet and WebSocket, in mixed combat and
    movement, against the tutorial world scaled ×50.
  - **Pass criteria:** pulse p99 < 50 ms, zero overruns over 30 min, and checkpoint p99 <
    500 ms.

### 7.2 Script Tests (Starlark, via harness)

- `gups test` runs `*_test.star` against the in-memory world, with a virtual clock and a
  seeded RNG. There's no network, no SQLite, and no wall clock.
- A failure prints a readable diff, the trace of the failing step, and the rules chain for
  any roll.
- The tutorial zone's tests are maintained as worked examples, and must pass on every engine
  commit.

### 7.3 Course World CI Gates (every PR)

1. **Load:** Every package loads, `api_version` matches, all labels resolve (exits, spawns,
   loads, keys, skills), and namespaces are unique.
2. **Lint:**
   - No dead-end rooms unless flagged `dead_end_ok`.
   - Every exit has a reverse unless `one_way`.
   - A prototype schema change requires a `version` bump.
   - Warn on attribute keys that nothing in the repo reads.
   - Hook names must match the catalog, so a typo such as `on_entr` is an error.
3. **Zone tests:** Every changed zone has at least one `*_test.star`, and every test passes.
4. **World smoke:**
   - The whole world loads together.
   - The fuzz walker runs 10,000 pulses through the changed zones, trying every exit,
     zone command, and item command, and attacking everything.
   - It asserts no uncaught script errors, no step-limit hits, no quarantine, and no
     invariant violations.
5. **Migration check:**
   - Load a sanitized snapshot of the production database against the PR.
   - Every version bump must have `migrate()` or a declared no-op.
   - Reconciliation must produce no tombstones the PR didn't declare
     (`package(expected_tombstones=[...])`).
6. **Budget:** The gates complete in about 2 minutes. Their output is posted as a PR
   summary.

### 7.4 Implementation Milestones (each gated by its tests)

| Milestone | Scope |
|---|---|
| **M0** | Gradle skeleton, module graph, `gups` CLI stub, CI matrix |
| **M1** | `core` scheduler, entities, command pipeline, tactical track, the Kotlin default ruleset, combat rounds. Runs headless with a Kotlin test world. |
| **M2** | `script`: loader, labels, freezing, `ctx`, views, verbs, hooks, limits, error handling, quarantine. `harness` with `gups test`. Tutorial zone. |
| **M3** | `net`: raw telnet with options and ANSI, plus `--dev` login. Playable locally. Auto-reload, `@trace`, `@eval`. |
| **M4** | `persist`: checkpoints, reconciliation, backups, `--fresh`, crash tests |
| **M5** | Rules module, modifier chain, skills and affects, behaviors and `think`, perception, reality bubble, items/equipment/containers, `//lib:quest.star` |
| **M6** | `auth`, TLS telnet, WebSocket with the web client, GMCP, roles, CODEOWNERS sync, deploy pipeline, metrics, admin commands. Load test. Course world template and CI gates. Staged and designed in detail in [SPEC_MILESTONE6.md](SPEC_MILESTONE6.md). |
| **M7** | `gups docs`, `MIGRATING.md`, `plugin-api` with one sample plugin |
