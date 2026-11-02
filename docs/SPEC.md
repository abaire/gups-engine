# GUPS Engine — Architecture & Technical Specification

Script API: `api_version = 1` (unstable).

This is the single source of truth for the engine's design. Choices made while implementing that it doesn't settle are
logged in [DECISIONS.md](DECISIONS.md) until they are folded in here, and features it leaves out go in
[DESIGN_NEEDED.md](DESIGN_NEEDED.md).

---

## 1. Objective & Scope

### 1.1 Vision

GUPS is a text MUD engine built as a teaching platform for an open university course with students of mixed skill
levels. It preserves old-school text MUD pacing (pulse-driven actions, combat rounds, recovery lag). It gives students a
sandboxed, deterministic scripting environment for building content. Its world logic runs on a single thread, so there
is no thread-synchronization overhead anywhere in the game simulation.

### 1.2 Audience Tiers

|       Tier       |                                                                 Works in                                                                  |                                    Typical work                                     |
|------------------|-------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| Novice builder   | Starlark zone packages (`//zones/...`)                                                                                                    | Rooms, mobs, items, dialogue, simple quests, configuring built-in behaviors         |
| Advanced builder | Starlark `think` hooks, `resolve` hooks, shared libraries (`//lib/...`), the rules module (`//rules/...`), global skills (`//skills/...`) | Custom AI, custom spell effects, rebalancing, new libraries, GMCP-driven client UIs |
| Engine developer | Kotlin, `.jar` plugins against `plugin-api`                                                                                               | New core mechanics, new host builtins, engine internals                             |

The novice builder is the primary audience and drives the design. The engine-developer path is documented but carries no
compatibility promises.

### 1.3 API Stability Policy

- The Starlark API is explicitly **unstable**. Old scripts may break when the engine is updated.
- Every package declares the `api_version` it targets. The engine supports exactly one `api_version` at a time. A
  package that declares a different version fails to load with a structured diagnostic that names the version gap and
  links to the changelog.
- Each engine release ships generated API docs (§4.16) and a human-readable changelog (§4.17).
  - Migrating legacy zones with a local LLM, fed the changelog and the failing zone, is an intended course exercise.
- A breaking change to the script API requires raising `api_version`; engine CI enforces it (§4.17).
- The API will be stabilized over time. Stabilization is out of scope for v0.

### 1.4 Deployment Model (Hybrid)

1. Students develop locally: `gups serve --dev` runs a single-user server with auto-reload on save, an `@eval` REPL, and
   a local instructor account.
2. Content reaches the shared course server **only** through a pull request to the course world repository. CI runs the
   headless harness and the lint gates, and an instructor reviews and merges.
3. After the merge, the course server snapshots its database and hot-reloads the changed packages.
4. The shared server has no live in-game code editing.

### 1.5 Platform, Scale, Stack

- **Platforms:** Linux on ARM64 and x86_64, and macOS on Apple Silicon (ARM64) only.
- **Scale:** Hundreds of concurrent connections. Memory is not a design constraint; the whole world graph lives in
  memory.
- **Engine core:** Kotlin (JVM), `kotlinx.coroutines`. Every listener runs on Netty (NIO transport); HTTP listeners use
  Ktor's Netty engine. Pinned library versions are declared in [libs.versions.toml](../gradle/libs.versions.toml).
- **Scripting:** `net.starlark.java`, the canonical Bazel JVM implementation of Starlark (pinned in
  [libs.versions.toml](../gradle/libs.versions.toml)). The interpreter rejects `lambda` and nested `def`s at parse time,
  so every hook, command, timer and modifier is a top-level `def`. `load` is a reserved word.
- **Persistence:** SQLite in WAL mode, used as a hybrid document store. Payloads are `kotlinx.serialization` JSON.
- **Build:** Gradle with the Kotlin DSL. Releases are per-platform `jlink` runtime images plus a Docker image.

### 1.6 Glossary

|      Term       |                                                                                                   Meaning                                                                                                    |
|-----------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Pulse           | The base heartbeat, 250 ms. The pulse counter (`Long`, monotonic, persisted) *is* game time.                                                                                                                 |
| Fast lane       | Commands executed immediately, bypassing the action queue and lag.                                                                                                                                           |
| Tick lane       | Commands queued and executed on action pulses, subject to warmup and lag.                                                                                                                                    |
| Package         | A directory of `.star` files with a `package.star`, addressed as `//path/to/dir`.                                                                                                                            |
| Zone            | A package of kind `zone`. It is the unit of authoring, ownership, hot reload, and fault quarantine. It is **not** a performance or memory partition.                                                         |
| Label           | The Bazel-style address of a declaration: `//zones/forest:clearing`, or `:clearing` within the same package.                                                                                                 |
| Prototype       | An immutable, frozen template produced by evaluating a package's top level.                                                                                                                                  |
| Instance        | A live entity created from a prototype. It stores the prototype label, the prototype version, and a delta.                                                                                                   |
| Region          | A builder-applied tag on a set of rooms. It can span zones and is used for rule modifiers.                                                                                                                   |
| Track           | A room's 1D tactical position axis, with indices `0 .. trackSize-1`.                                                                                                                                         |
| Band            | Relative distance as seen by a viewer: `MELEE` (0), `NEAR` (1), `FAR` (2 or more).                                                                                                                           |
| Reality bubble  | The set of rooms currently simulated. Everything outside it is dormant.                                                                                                                                      |
| Active / anchor | Per-entity flags that keep an entity simulated outside the bubble (active), or make it project a bubble of its own (anchor).                                                                                 |
| Affect          | A timed buff or debuff attached to an actor or item.                                                                                                                                                         |
| Schema          | The world's declared stats, vitals, damage types and extra perception channels (§3.9). Fixed for the life of a database.                                                                                     |
| Feature         | An engine mechanic a world can turn off: `combat`, `track`, `skills` or `leveling` (§3.10). Everything else is the kernel.                                                                                   |
| Condition       | An actor's life state, `ok`, `incapacitated` or `dead`, decided by the rules (§4.6).                                                                                                                         |
| Choice          | A question a new character answers at creation, such as race or class, with a fixed set of options. Declared by a plugin, the world's creation provider (§4.19).                                             |
| Trait           | A named, permanent bundle of stat, max-vital and resist deltas, flags and modifiers that an actor carries: a player from its choices, a mob from its prototype, and either when granted by a plugin (§4.19). |
| Restriction     | A plugin check that can refuse wearing, entering, casting or learning (§4.19).                                                                                                                               |
| Tag             | A free-form word on a room, item or skill that plugins and scripts read; the engine gives it no meaning (§4.2).                                                                                              |

### 1.7 Out of Scope for v0 (Deferred)

Features deferred beyond v0 or requiring further architectural design are tracked in
[DESIGN_NEEDED.md](DESIGN_NEEDED.md). Refer to that document for descriptions of future functionality, current engine
status, and open design questions.

---

## 2. Target Files, Modules & Boundaries

### 2.1 Repositories

There are two repositories. The working tree is laid out as:

```
gups-project/
  gups-engine/            engine repository (this file is docs/SPEC.md)
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
  net/         Netty listeners (telnet, telnet+TLS, HTTPS+WebSocket), telnet option
               negotiation, line framing, ANSI rendering, GMCP, per-session output buffers
  auth/        accounts, Argon2id hashing, login rate limiting, invites, roster import,
               session tokens, roles, CODEOWNERS sync
  harness/     headless in-memory world, virtual clock, Starlark test runner, fuzz walker,
               repro runner
  plugin-api/  extension points for .jar plugins (§4.15): the gups.pluginapi interfaces and
               PluginWorld facade, plus the engine-side loader and host
               (gups.pluginapi.host); unstable, documented
  cli/         the `gups` binary, the docs renderer (§4.16)
  webclient/   static browser client (plain ES modules with JSDoc), bundled into the jar
               as resources
  samples/     sample plugins: weather-plugin and heritage-plugin, race and class (§4.15);
               robot-world, a small non-fantasy course world with its own schema and
               features (§3.9, §3.10)
  template-world/  the course world template, including the canonical tutorial zone,
               //lib and //rules (§2.3)
  docs/        SPEC.md, CHANGELOG.md, PLUGINS.md (the
               engine-developer guide), api/ (released API baselines, §4.17)
```

**Module dependency rules (enforced by the Gradle module graph):**

- `core` depends on no other GUPS module. It defines interfaces (`ScriptHost`, `RulesEngine`, `Persistence`,
  `AccountStore`, `WebAccounts`, `LoginHandler`, `OutputSink`) that the other modules implement. It exports
  `kotlinx-coroutines` as an `api` dependency, because `WorldLoop`'s public API uses it.
- `script`, `persist`, `net`, and `auth` depend on `core` and never on each other.
- `harness` depends on `core` and `script` only. It never touches `net` or `persist`.
- `cli` is the only module that wires everything together. `net` reads the web client's files through a class loader
  that `cli` hands it, so it doesn't depend on `webclient`.
- `plugin-api` depends on `core` and `script` with `implementation`, and exposes only `api(libs.starlark)`. Plugins
  compile against `gups.pluginapi.*` and `net.starlark.java.*` alone; no public type in `gups.pluginapi` mentions a
  `core` or `script` type (a reflection test checks it). Plugins are loaded through `ServiceLoader` from the world's
  `plugins/` directory via a `URLClassLoader` (§4.15).
- `samples/*` may depend only on `:plugin-api` in their main configurations (their tests may use the harness), so the
  sample proves the facade is enough to write a real plugin.
- Nested projects (such as `:samples:weather-plugin` and `:samples:heritage-plugin`) are evaluated explicitly by the
  root build, so the module-graph check sees their dependencies too.

**Release artifacts:**

- `gups` runtime images for `linux-x64`, `linux-arm64`, and `macos-arm64`, built with `jlink`. Students need no JDK.
  - The image (`build/image/gups`) is a jlink runtime, the jars and a `bin/gups` launcher, configured in
    [cli/build.gradle.kts](../cli/build.gradle.kts).
  - Each is packed as `gups-<version>-<platform>.tar.gz`. Every CI platform builds and smoke-tests it with `gups test`;
    a `v*` tag attaches the archives to the GitHub release.
- An OCI image for the course server: `eclipse-temurin:21-jre-noble` plus `git`, `openssh-client`, `curl` and `jq`. One
  build serves `linux/amd64` and `linux/arm64`. It declares the volumes `/world`, `/data` and `/etc/gups`, marks
  `/world` a git `safe.directory`, runs `gups serve --prod --config /etc/gups/server.gups`, and health-checks
  `/healthz`. A `v*` tag pushes it as `ghcr.io/<owner>/gups:<version>` (the tag without `v`).
- A course world template archive (§2.3).
- The API reference (§4.16): `gups-docs-<version>.zip`, attached to the GitHub release, and a `docs/` directory inside
  the install directory, the jlink image and the OCI image (`/opt/gups/docs/`).
- The engine version (semver) and `api_version` (integer) are independent. `api_version` bumps only on breaking
  script-facing changes. Release jobs build with `-Pversion=${GITHUB_REF_NAME#v}`, so the engine, archives, OCI image
  and template agree.

### 2.3 Course World Repository

```
<course-world>/
  MODULE.gups              required; pins engine version and api_version; the world's
                           schema (§3.9) and features (§3.10); world settings such as
                           checkpoint_minutes (§5.11) and command aliases (§5.2)
  MESSAGES.gups            optional; overrides of engine message text (§4.18)
  CODEOWNERS               package ownership → builder roles
  rules/                   instructor-owned; package kind "rules"; required (§4.6)
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
  plugins/<id>.jar         plugin jars, each pinned by checksum in MODULE.gups (§4.15)
  .github/workflows/       CI gates (§7.3) using the pinned engine image
  .gups/                   runtime data (database, backups); skipped by package scans
```

- **Template:** The canonical template lives in the engine repository as `template-world/`. It holds the tutorial zone
  (whose tests must pass on every engine commit, §7.2), `lib/` (including `quest.star`) and `rules/`. Its `MODULE.gups`
  declares the fantasy schema of §3.9 with every feature on, and its `rules/combat.star` implements the template ruleset
  of §4.6; both are starting points the instructor edits. A release job packages it as the course world template; each
  term, instructors create the term's repository from it on GitHub.
  - In the engine repository its `MODULE.gups` is unpinned. Packaging the template stamps the engine version into
    `MODULE.gups` and the image owner and version tag into `.github/workflows/gates.yml`, which runs the gates in the
    pinned engine image.
  - Its `CODEOWNERS` gives instructors `MODULE.gups`, `rules/`, `lib/`, `skills/` and `zones/tutorial/`, with a
    commented example zone line. Owning `MODULE.gups` covers plugins, since a jar takes effect only once `MODULE.gups`
    pins it. The instructor is the placeholder `@CHANGE_ME_INSTRUCTOR`, to be replaced.
  - Its `README.md` tells instructors how to start a term, import the roster, deploy, and upload the migration snapshot
    (§7.3).
  - It uses no plugins.
- **Package kinds:** `zone`, `lib`, `rules`, `skills`. Only `lib` and `rules` packages are *privileged*. They may
  declare shared attribute namespaces and global commands (§4.9).
- **Load labels vs target labels:** `load("//lib:quest.star", "quest")` addresses a *file*. `//zones/forest:clearing`
  addresses a *declaration* named `clearing` in any non-test `.star` file of package `zones/forest`. Declaration names
  are unique per package.
- **Loading a package:**
  - It also loads every package it refers to by label (exits, spawns, keys, loot, skills, summons) or by `load()`,
    transitively. A referenced package that isn't on disk is a label error.
  - A package fails if any of its files fails, if any label doesn't resolve to a declaration of the right kind, or if
    anything it depends on failed.
  - `package.star` must call `package()` exactly once and holds nothing else, apart from the top-level functions its
    `modifiers` name (§4.6). It can't be `load()`ed.
  - Content files evaluate in alphabetical order. Only test files may load `*_test.star` files.
  - Global commands may not shadow an engine command or alias, a standard direction or its alias (`north`, `n`), or
    another package's global command. An attribute namespace used by two packages (or by a package and a shared
    namespace, or equal to a plugin id) fails every package involved.
- **`MODULE.gups`:** Starlark with exactly one call, `module(...)`, documented in
  [WorldModule.kt](../script/src/main/kotlin/gups/script/WorldModule.kt). The file is required, and so are `stats`,
  `vitals`, `damage_types` and `unarmed_damage_type`; every other argument is optional. A world without the file, or
  without one of the required arguments, stops the server.
  - `stats`, `vitals`, `damage_types`, `unarmed_damage_type`, `channels` and `prompt` are the world's schema (§3.9);
    `features` and `rename` choose its feature modules and command names (§3.10).
  - A pinned `api_version` the engine doesn't support stops the server. A different pinned `engine` version only warns.
  - `start_room` sets where new characters start, in both profiles (`--start` overrides it). Without either, it is
    `//zones/tutorial:gate` if that exists, else the first room by label. A `start_room` that isn't a room stops the
    server.
  - `plugins` pins plugin jars (§4.15). Plugin identifier syntax and checksum validation are enforced per
    [WorldModule.kt](../script/src/main/kotlin/gups/script/WorldModule.kt).
  - `aliases` gives engine commands shorter words, and `prefixes` makes a single character at the start of a line stand
    for a command (§5.2). Validation rules for alias identifiers and prefix characters, along with template defaults,
    are documented in [WorldModule.kt](../script/src/main/kotlin/gups/script/WorldModule.kt).
  - Command names in `aliases` and `prefixes` are the names after `rename` (§3.10); an alias or prefix naming a command
    of a feature that is off stops the server.
  - Changing the file needs a restart (§5.10). The file watcher and reload never read it. Changing its schema or
    features also needs a server wipe (§3.9).
- **`MESSAGES.gups`:** optional Starlark with exactly one call, `messages({...})`, that overrides engine message text
  (§4.18), validated according to [WorldMessages.kt](../script/src/main/kotlin/gups/script/WorldMessages.kt). Like
  `MODULE.gups`, it is instructor-owned, read only at startup, and a problem in it stops the server.
- **CODEOWNERS:** the first of `.github/CODEOWNERS`, `CODEOWNERS` and `docs/CODEOWNERS`, in GitHub's order. No file
  means no CODEOWNERS scope.
  - Each line is a path pattern, then owners. A pattern maps to every package whose directory it matches; for example
    `/zones/forest/` owns `zones/forest` and the packages below it. The last matching pattern wins, as on GitHub.
  - Patterns are gitignore-style, matched against package directories. Pattern syntax and owner parsing rules follow
    [CodeOwners.kt](../auth/src/main/kotlin/gups/auth/CodeOwners.kt).
  - Owners are `@user` handles, compared in lowercase with accounts' `github_handle`. Team and email owners are
    warnings, as are handles that match no account.
- **Deploying:** An engine upgrade is a PR that edits `MODULE.gups`. CI shows exactly which packages break.

### 2.4 Runtime Profiles

|   Flag    |                                                                                                                                                                                                                                          Effect                                                                                                                                                                                                                                           |
|-----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `--dev`   | Raw telnet on `127.0.0.1:4000` (`--port` changes the port). Auto-creates a passwordless local `instructor` account bound to localhost. Auto-reload on file save. `@eval` and `@reload` enabled. Loads every package under `--world` (default: the current directory); packages that fail to load are reported and left out. Serves the API reference over plain HTTP at `http://127.0.0.1:4080/docs/` (`--docs-port PORT` changes the port; `0` picks a free one). Checkpoints still run. |
| `--prod`  | Account logins (§5.12). Listeners as configured: raw telnet only when asked, telnet over TLS, and HTTPS with the web client and `/docs/` (at least one is required). The admin port is always on. `@eval`, `@reload`, auto-reload and the file watcher are off. Deploys only through `gups deploy` / `@deploy`. The §3.8 check runs at each world tick, not every pulse. Requires a `git` binary. `--docs-port` is a usage error.                                                         |
| `--fresh` | Usable with either profile. Ignores saved **world** state (mob positions, ground items, room and zone state) and boots purely from the `.star` definitions. Accounts, characters, and items whose location chain ends in a player character are still loaded.                                                                                                                                                                                                                             |
| `--wipe`  | A server wipe, usable with either profile. Like `--fresh`, and also deletes every character and every item, then records the current schema and features (§3.9). Accounts, restrictions, web tokens and setup codes are kept. A `prewipe` backup (§5.11) is taken first.                                                                                                                                                                                                                  |

- **Database:** `--db` defaults to `<world>/.gups/world.db` in both profiles. The hidden directory is skipped by the
  package scan and the file watcher. `gups backup`, `restore` and `repro` take the same `--world` and `--db`.
- **Reality bubble:** `gups serve` always runs with the bubble (§5.9). Kotlin tests, the harness and `gups repro`
  default to no bubble, where every room is always simulated.
- **Plugins** load the same way in both profiles (§4.15).
- **`--prod` configuration:** `--config FILE` reads the server configuration (`server.gups`), Starlark with one
  `server(...)` call like `MODULE.gups`, so instructors learn one syntax. It lives outside the course world repository,
  because it holds paths and settings for one machine. Secrets never go in it: the admin token and the deploy key are
  read from files it names, or from the files environment variables name.

  ```python
  server(
    world = "/srv/gups/world",              # a git working tree of the course repository
    db = "/srv/gups/world.db",
    telnet_tls = ":4443",
    https = ":443",                          # web client, WebSocket and /docs/
    telnet = None,                           # raw telnet off; e.g. ":4000" to enable
    admin = "127.0.0.1:9090",
    tls_cert = "/etc/gups/fullchain.pem",
    tls_key = "/etc/gups/privkey.pem",
    admin_token_file = "/etc/gups/admin.token",
    repository = "git@github.com:course/world-2027.git",
    deploy_branch = "main",
    deploy_key_file = "/etc/gups/deploy_key",
    open_signup = False,                     # §1.7: designed for, disabled by default
    max_characters = 5,                      # per account; instructors are unlimited
    restriction_ladder = ["1d", "7d", "permanent"],  # §4.9
    reserved_names = [],                     # extra character names to refuse
    shutdown_timeout = 30,                   # seconds to wait for the final checkpoint (§5.11)
  )
  ```

  Every setting also has a CLI flag, and a flag overrides the file (see
  [ServerConfig.kt](../script/src/main/kotlin/gups/script/ServerConfig.kt) and
  [Commands.kt](../cli/src/main/kotlin/gups/cli/Commands.kt)):

  - **Listeners:** `--telnet`/`telnet` (binds every interface), `--telnet-tls`/`telnet_tls`, and `--https`/`https`. Both
    TLS listeners share one certificate: `--tls-cert` and `--tls-key` (`tls_cert`, `tls_key`), or `--self-signed`, which
    needs a TLS listener.
  - **Admin port:** `--admin`/`admin` (default `127.0.0.1:9090`) and
    `--admin-token-file`/`admin_token_file`/`GUPS_ADMIN_TOKEN_FILE` (§4.14).
  - **Accounts:** `--open-signup`/`--no-open-signup`, `--max-characters`, `reserved_names`, `restriction_ladder`.
  - **Deploys:** `--repository`, `--deploy-branch` and `--deploy-key-file` (or `GUPS_DEPLOY_KEY_FILE`), with
    configuration keys of the same names.
  - **Shutdown:** `--shutdown-timeout`/`shutdown_timeout`, in seconds (default 30, at least 1; §5.11).
  - **General options:** `--world`, `--db`, `--start` and `--fresh`.
  - **Startup output:** The startup message lists the bound port of each transport (`telnet`, `tls`, `ws`, `admin`).
- **`--prod` and the world commit:** When the world is a git working tree, `--prod` refuses to start if tracked files
  have uncommitted changes, or if `HEAD` isn't the `meta.world_commit` the database was saved at. `--force-commit`
  adopts what is there. The first start records `HEAD`. A world that isn't a working tree, or has no commits, starts
  with a warning and can't deploy.

### 2.5 Process and Thread Topology

- **Network I/O** runs on an asynchronous I/O thread pool. This covers Telnet and ANSI parsing, option negotiation, line
  framing, GMCP encoding, and output buffering.
- **The world thread** is a single dedicated thread owning the entire world graph, the scheduler, all Starlark
  evaluation, and checkpoint *capture* ([WorldLoop.kt](../core/src/main/kotlin/gups/core/loop/WorldLoop.kt)). Every
  world mutation happens here, so they are lock-free and race-free by construction.
- **The checkpoint writer** is a dedicated background thread. It is the only SQLite database writer, applying batches in
  FIFO order ([Checkpoints.kt](../persist/src/main/kotlin/gups/persist/Checkpoints.kt)).
- **Background work:** Slow operations such as Argon2id password hashing, account store access, git operations, and
  diagnostics run on a dedicated background worker pool. `World.later(work, then)` executes slow work off the world
  thread and dispatches the result back to the world loop as a control event.
- **Deploys** run one at a time, each on a dedicated thread (§5.10).
- **The file watcher** (`--dev` only) polls modification times and file sizes of non-test `.star` files every 100 ms and
  posts reload requests into the world input channel ([Watcher.kt](../script/src/main/kotlin/gups/script/Watcher.kt)).
- **Ingress and egress:** Network input reaches the world as client input events on a channel. Output leaves through
  per-session output sinks, which never block the world thread.

---

## 3. Data Structures, Types & State Contracts

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
  (`IDLE_QUIT_PULSES`), and corpse decay duration (`CORPSE_DECAY_PULSES`).

### 3.2 Script-Compatible Values

Custom attributes on entities store script-compatible primitives represented by
[`AttrValue`](../core/src/main/kotlin/gups/core/AttrValue.kt). Starlark types convert both ways according to the
conversion rules in [Values.kt](../script/src/main/kotlin/gups/script/Values.kt):

- Starlark `int` (as 64-bit integer), `str`, `bool`, `list`, and `dict` with string keys convert both ways.
- `None` means "absent": setting an attribute to `None` deletes it.
- Floats, functions, and host objects cannot be stored and produce errors when assigned.
- An entity's serialized custom attributes are limited to 64 KB, measured as compact JSON.
- `core` stays free of `kotlinx.serialization`. Custom attribute values are serialized directly to JSON in the `persist`
  module: ints and booleans as JSON numbers and booleans, strings as strings, lists as arrays, and dicts as objects.

### 3.3 Entities

The world graph consists of immutable **prototypes** evaluated from packages and mutable **live entities** simulated by
the engine.

#### Prototypes

Prototypes ([Protos.kt](../core/src/main/kotlin/gups/core/model/Protos.kt)) are immutable Kotlin data classes created
when a package evaluates. They define base templates for rooms (`RoomProto`), exits (`ExitProto`), doors (`DoorProto`),
mobs (`MobProto`), items (`ItemProto`), skills (`SkillProto`), and zones (`ZoneProto`).

- **Reserved prototype labels:** `//engine:player` is the prototype label of every player character. `//engine:corpse`
  is the built-in corpse container item prototype.
- **Behaviors and hooks:** Behaviors, `think`, zone commands and custom `resolve` are Kotlin function interfaces that
  the script host implements, held on the prototypes. Hooks are not stored on prototypes: the host looks them up by
  prototype label.

#### Live Entities

Live entities ([Entities.kt](../core/src/main/kotlin/gups/core/model/Entities.kt)) are stateful objects instantiated in
the world graph:

**Entity** ([`Entity`](../core/src/main/kotlin/gups/core/model/Entities.kt), abstract base of `Room`, `Actor`, and
`Item`):

- **Identity and versioning:** unique monotonic `id` (`EntityId`), immutable `protoLabel`, and mutable `protoVersion`.
- **Script storage:** namespaced `customAttributes` map (§3.7) and scheduled `timers` (`ScriptTimer`).
- **Simulation state:** active reasons (`activeReasons`) and anchor reasons (`anchorReasons`) reference sets (§5.9).
- **Engine metadata:** `dirty` persistence flag and transient builder `debug` flag.

**Room** ([`Room`](../core/src/main/kotlin/gups/core/model/Entities.kt)):

- **Tactical layout:** `trackSize` (`1`, `3`, or `5`; always `1` without the `track` feature, §3.10).
- **Spatial connectivity:** outward `exits` map keyed by cardinal `Direction`.
- **Classification and tags:** builder `regions`, queryable `tags` (§4.2), and frozen prototype `modifiers`.
- **Spawns and commands:** mob `spawns` rules, zone `commands` map, and simulation flags (`always_active`,
  `dead_end_ok`).

**Exit and Door** ([`Exit`](../core/src/main/kotlin/gups/core/model/Entities.kt),
[`Door`](../core/src/main/kotlin/gups/core/model/Entities.kt)):

- **Exit:** cardinal `direction`, target room destination `to` label, `entryIndex` on the destination track (default
  `0`, `-1` for end of track), queryable `tags` (e.g. `soundproof`, `sealed`), optional `door`, and `oneWay` flag.
- **Door:** state (`OPEN`, `CLOSED`, `LOCKED`), optional `keyLabel`, and `tags` (e.g. `heavy`, `transparent`).
- **Transmission costs:** exits and doors do not hardcode channel blockage; `rules.sense_cost` (§4.6) evaluates exit
  tags and door state dynamically to determine crossing costs per channel.

**Actor** ([`Actor`](../core/src/main/kotlin/gups/core/model/Entities.kt), sealed base of
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
  `lastInputPulse` and `idleExempt`, `recallRoom`, and character creation `choices`.

**Item** ([`Item`](../core/src/main/kotlin/gups/core/model/Entities.kt)):

- **Location hierarchy:** mutually exclusive single containment via `ItemLocation`: in a room (`InRoom` with track
  index), carried in an actor's inventory (`Carried`), equipped in an actor's slot (`Equipped`), or contained in another
  item (`Contained`).
- **Quantities and decay:** `count` (stackable items), `weight`, and scheduled `decayAt` pulse. An item prototype may
  declare an optional `plural` display name, used to show stacks of 2 or more as `"<count> <plural>"`. Bare `all` in
  item commands skips items tagged `no_all` (such as `//engine:corpse`) and keyring-only keys.
- **Container properties:** optional `ContainerState` with `capacity`, `maxWeight`, access `state` (`OPEN`, `CLOSED`,
  `LOCKED`), and `keyLabel`.
- **Equipment and combat:** item `kind` (e.g. `melee`, `ranged`, `armor`, `consumable`), weapon `range` band, schema
  `statDeltas`, prototype `tags`, equipment slot `wear`, and worn `modifiers` and `equipAffects`.
- **Affects and tags:** per-instance `affects` (timed or permanent) and per-instance tag changes over the prototype's
  `tags`; prototype `affects` are read live from the prototype. Each item affect has an `applies` scope: `worn` reaches
  the holder while the item is equipped, `carried` while it is anywhere in the holder's custody, including nested
  containers, unless a plugin containment filter blocks it (§4.15). A tombstone contributes nothing. Effective `tags`
  are the prototype's plus those added, minus those removed, so a reload's prototype tag changes still reach instances.
  `variants` maps free-form names to related item prototypes; `transform` swaps an instance's prototype in place. A
  container keeps its open or closed state across a transform, and its locked state only when the new prototype has a
  `key`; otherwise a locked container ends up closed. A container that becomes a non-container drops its contents on the
  ground of the room it is in, and a stack that becomes a non-stackable item keeps one of its count.

**Affect** ([`Affect`](../core/src/main/kotlin/gups/core/model/Entities.kt)):

- **Tracking:** unique `id`, source `label`, optional `source` entity ID, and absolute expiration pulse `expiresAt`.
- **Mechanics:** schema `statDeltas`, status `flags` (such as `stunned`, `silenced`, or `no_regen`), periodic
  `tickEvery` cadence with `onTick` and `onExpire` hook references, and applied `modifiers`.
- **Replacement rule:** re-applying an affect with the same name replaces the existing affect only if the new
  `expiresAt` is strictly later; shorter affects never truncate longer active affects. A permanent item affect counts as
  expiring latest.
- **Scope:** item affects carry `applies` (`worn` or `carried`); an item affect's stat and sense deltas, flags and
  modifiers count for the item's holder while it reaches it. An item removed from the world mid-pulse takes its
  remaining affects with it; their hooks don't fire.

**ScriptTimer** ([`ScriptTimer`](../core/src/main/kotlin/gups/core/model/Entities.kt)):

- Identified by unique `id`, targeting callback `fn` (`HookRef`) with argument map `args`.
- Scheduled with absolute `due` pulse while running in active rooms, or preserved `remaining` pulse count while
  suspended in dormant rooms (§5.9).

**HookRef** ([`HookRef`](../core/src/main/kotlin/gups/core/model/Entities.kt)):

- Stores a package file label and top-level function name. Since Starlark forbids lambdas and nested functions (§1.5),
  all script callbacks are top-level definitions.

**RNG** ([`Rng`](../core/src/main/kotlin/gups/core/Rng.kt)):

- Wraps deterministic seeded pseudorandom number generation (`XorWow`), ensuring reproducible rolls across platforms and
  replay runs.

### 3.4 Prototypes, Instances, Versions

- **Freezing:** Evaluating a package's top level produces immutable, frozen prototypes. Each prototype carries an
  integer `version`, which defaults to 1.
- **Instance delta:** An instance stores `protoLabel`, `protoVersion`, and only the fields that differ from the
  prototype. That covers changed stats, attributes, location, timers, and affects.
- **Load and reload order:**
  1. Load the new prototype.
  2. If `instance.protoVersion < proto.version` and the package defines `migrate(old_version, attrs)`, call it. It
     returns the new attribute dict.
  3. Overlay the delta.
  4. Set `protoVersion`.
- **`migrate()`:** A package defines at most one top-level `migrate(old_version, attrs)`, in any of its files.
  - It runs for each instance whose saved prototype version is below the current one: mobs, items and rooms restored at
    startup, items restored with a character at login, and live instances after a reload.
  - `attrs` is a dict of the package's own attributes by short name. The dict it returns replaces them; other packages'
    attributes are untouched.
  - An error, or a return value that isn't a dict of storable values, leaves the attributes as they were and logs a
    diagnostic. The version still moves up.
- **Live reconciliation (reload):** A field whose live value still equals the old prototype's takes the new prototype's
  value; any other field keeps its live value.
  - Mobs: level, vital maxima, base stats, resists, skills, `think_every` and `dormant_recovery` follow that rule,
    compared with the prototype's declared values (so values `rules.starting_stats` changed at spawn are kept). Current
    vitals stay, capped at the new maximum, except that a vital that was at its maximum moves to the new one. Prototype
    traits always follow the new prototype (granted ones stay), and the maxima compared are base values; the effective
    maximum then follows from the traits.
  - Rooms: door states follow the same rule.
  - Items read everything from their prototype, so they switch to the new one.
  - Actors forget skills whose prototype is gone.
  - Spawn rules carry their live mobs over by label. New rooms fill their spawns, then get `on_load`.
- **Deleted prototypes:**
  - Mobs from a deleted prototype are despawned, and so are items that aren't in a player's custody.
  - Items in player inventories, equipment, or player-owned containers become inert **tombstone** items: kind
    `tombstone`, the original name and label, and the payload they had when their prototype vanished. Tombstones can be
    inspected and dropped, but they have no hooks, commands or stats. A tombstone keeps its weight, stackability, plural
    name and container (its capacity, weight limit, key and open, closed or locked state), so the contents survive, and
    it keeps them when it is restored from storage. Saved items become tombstones when their owner logs in.
  - A deleted room sends its players to their recall room, or else the first room.

### 3.5 Accounts and Roles

The engine defines a four-tier role hierarchy (`PLAYER < BUILDER < INSTRUCTOR < ADMIN`), where higher roles inherit all
privileges of lower roles ([Role.kt](../core/src/main/kotlin/gups/core/Role.kt)).

- **Account records:** Managed via [Accounts.kt](../core/src/main/kotlin/gups/core/Accounts.kt), an account contains
  `id`, `login`, `pwHash` (Argon2id; absent until a roster account is set up), role and builder scopes, optional
  `githubHandle`, active status (`disabled` with provenance fields `disabledBy`, `disabledAt`, and `disabledReason`),
  and `createdAt`.
  - Logins are 3–24 characters from `[a-z0-9_.-]`, unique case-insensitively and stored in lowercase.
  - An account has one role; the higher roles imply the lower, so builder roles record both CODEOWNERS and manual
    builder package scopes.
  - A disabled account cannot log in (§5.12). Disabling an account immediately disconnects its active characters, closes
    its sessions, revokes its web tokens and unused setup codes, and records provenance. Re-enabling clears the
    provenance record. Accounts are disabled via `@role` (§4.9) or roster import (§4.13).
- **Characters:** An account owns 1..N characters (`max_characters`, default 5; instructors and admins unlimited) and
  plays one at a time across all transports (§5.12). Character names are 2–16 letters, unique across all accounts
  (case-insensitive), and checked against reserved words:
  - Built-in reserved names: `self`, `me`, `all`, `someone`, `here`, menu commands (`new`, `password`, `quit`), `guest`,
    `instructor`, `admin`, and standard cardinal directions and abbreviations.
  - World reserved names: names and aliases of engine, global, and tool commands (without `@`), all words in mob
    prototype names (to prevent impersonating NPCs), and server configuration `reserved_names`.
  - Logins reserve only `new`.
- **Builder scope:** The `BUILDER` role is scoped to specific package paths, formed from the union of two scopes:
  - **CODEOWNERS scope:** derived from `CODEOWNERS` (§2.3) by matching `githubHandle`. It is synchronized at startup and
    after each deploy. Every account other than instructors and admins gets its CODEOWNERS packages, players included,
    so promoting a player scopes them at once. A sync never promotes or demotes: it only replaces scopes, and `@role`
    sets roles.
  - **Manual scope:** assigned via `@role <account> builder <packages>` (§4.9). Packages covered by CODEOWNERS are
    rejected when manually added, and a sync drops any manual package that CODEOWNERS later covers.
  - Instructors and admins have universal scope across all packages.
  - Scope modifications take effect on active characters immediately.
- **Storage seam:** `AccountStore` ([Accounts.kt](../core/src/main/kotlin/gups/core/Accounts.kt)) is exposed through
  `Persistence.accounts` and implemented in the `persist` module
  ([Database.kt](../persist/src/main/kotlin/gups/persist/Database.kt)). Account changes are crash-resilient but
  decoupled from world snapshots:
  - Each account mutation is dispatched to the checkpoint writer as an immediate batch written in FIFO order without
    merging into periodic world captures. Callers suspend or wait off the world thread for confirmation (up to 30 s).
  - Claiming a login or redeeming an invite is handled atomically and reported as an explicit result rather than
    throwing, preventing database retries from stalling the writer loop.
  - Reads use a dedicated read-only WAL connection. Accounts are not cached in memory, ensuring CLI tools directly
    interacting with SQLite remain consistent.
  - Updating account details modifies metadata and disabled provenance; passwords are updated separately via dedicated
    methods that revoke active session tokens. Disabling an account revokes all active session tokens and setup codes.
- **Invite and setup codes:** 10 characters grouped as `XXXXX-XXXXX`, drawn from an unambiguous alphanumeric alphabet
  (`23456789ABCDEFGHJKMNPQRSTWXYZ`, excluding confusing characters like 0/O, 1/I/L, and U/V). Codes are normalized by
  uppercasing and removing spaces and dashes, and stored as SHA-256 hashes. Setup codes bind to a specific account;
  invites grant an initial role.

### 3.6 Persistence Schema (SQLite, WAL)

The engine persists world state and account information to SQLite in WAL mode
([Database.kt](../persist/src/main/kotlin/gups/persist/Database.kt)).

|      Table       |                                                          Columns (abridged)                                                          |                                                                                           Notes                                                                                            |
|------------------|--------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `meta`           | `key`, `value`                                                                                                                       | `schema_version`, `pulse`, `next_entity_id`, `next_handle` (timer and affect ids), `world_commit`, `world_schema` and `world_features` (§3.9), `snapshot_taken_at` (sanitized copies only) |
| `accounts`       | `id`, `login`, `pw_hash`, `roles_json`, `github_handle`, `disabled`, `disabled_by`, `disabled_at`, `disabled_reason`, `created_at`   | `roles_json` holds role, CODEOWNERS scope, and manual scope; `disabled_*` columns record disable provenance                                                                                |
| `session_tokens` | `token_hash`, `account_id`, `expires_at`                                                                                             | web client session tokens; revocable                                                                                                                                                       |
| `invites`        | `code_hash`, `role`, `expires_at`, `used_by`, `account_id`                                                                           | invite and account setup codes; `account_id` binds setup codes to specific accounts                                                                                                        |
| `restrictions`   | `id`, `account_id`, `capability`, `until`, `reason`, `set_by`, `created_at`, `lifted_by`, `lifted_at`                                | temporary and permanent account capability bans (§4.9)                                                                                                                                     |
| `players`        | `id`, `account_id`, `name`, `payload_json`, `updated_pulse`                                                                          | character stats, skills, affects, attributes, position, `choices`, `grantedTraits` (§4.19), and `levelGains`                                                                               |
| `world_entities` | `id`, `kind`, `proto_label`, `proto_version`, `location_json`, `payload_json`, `updated_pulse`                                       | rooms, mobs, and **all items**, including carried and contained items                                                                                                                      |
| `zone_states`    | `package`, `payload_json`                                                                                                            | reset and respawn deadlines, package quarantine flags                                                                                                                                      |
| `item_events`    | `id`, `item_id`, `pulse`, `wall_time`, `from_json`, `to_json`, `cause`                                                               | custody audit trail for player-involved items (cause: `spawn`, `move`, `split` for a new item moved off part of a stack, `reduce` for a stack destroyed in part, `destroy`)                |
| `diagnostics`    | `id`, `pulse`, `wall_time`, `package`, `label`, `hook`, `file`, `line`, `kind`, `message`, `stack`, `ctx_json`, `seed`, `repro_json` | runtime script exceptions and diagnostics                                                                                                                                                  |

- **Single-location item invariant:** An item's location is stored **only** on its own `world_entities` row. Inventories
  and container contents are derived dynamically when the world loads. Player rows never list item IDs, preventing torn
  checkpoints from duplicating or stranding items.
- **Entity IDs:** `next_entity_id` is written in every checkpoint transaction. IDs allocated after the last committed
  checkpoint may be reused safely after a crash because uncommitted entities were never referenced by committed state.
- **JSON payloads and delta encoding:** Payloads are serialized using delta encoding
  ([Codec.kt](../persist/src/main/kotlin/gups/persist/Codec.kt)). Each payload carries a `format_version` integer for
  engine schema evolution:
  - Rooms store modified door states, custom attributes, and running timers.
  - Mobs store position, current vitals, XP, home room, and spawn rule bindings. Modifiable stats, vitals maxima,
    resists, skills, and behavior cadences are persisted only when differing from prototype definitions.
  - Schema-keyed stats, vitals, and resists are stored as JSON maps, recording current values, base maxima, and
    negative-vital pulse markers.
  - Items store stack counts, decay deadlines, name overrides, container state, attributes, and timers.
  - Players store complete character state, including base attributes, choices, granted traits, and level progression
    deltas. Restored traits are computed before vitals are applied so trait bonuses are preserved without truncation.
  - Active and anchor reasons are persisted across restarts (§5.9).
- **SQLite configuration:** WAL mode with `synchronous = NORMAL`, which survives process kills but may lose the last
  commits on power loss, and a 5-second `busy_timeout`.

### 3.7 Attribute Namespacing

- A key written by package `zones/goblin_caves` is stored as `goblin_caves:<key>`. The namespace is the last path
  segment of the package, and must be unique across the world. CI lints for this.
- A package reads its own keys by their short name. Other packages read them by full name only. Short keys match
  `[A-Za-z0-9_]+`.
- **Which package is "the package":** at run time, relative labels and attribute keys resolve against the package of the
  innermost running function. A `//lib` helper therefore writes the lib's own namespace (and its shared namespaces) even
  when a zone hook calls it.
- A package can **write only its own namespace**. Writing another namespace is a script error.
- **Shared namespaces:** Privileged packages may declare them, for example `package(shared_namespaces = ["faction"])`.
  Any package may write to a shared namespace, but only through helper functions exported by the declaring package. The
  declaring package's helpers run with write permission on that namespace.
- **Plugin namespaces:** a plugin writes attributes in the namespace of its id, so no package's namespace or shared
  namespace may equal a plugin id; such a package fails to load with a `namespace` error.
- **Built-in state** isn't stored in attributes and isn't reachable through them. That covers stats, position, and
  inventory.

### 3.8 Invariants (state contracts)

1. Every item has exactly one `ItemLocation`. The containment graph is a forest: no cycles.
2. Every actor's `trackIndex` satisfies `0 ≤ trackIndex < room.trackSize`.
3. Every vital's `current ≤` its effective maximum (§3.3). Death is processed exactly once per life.
4. An actor is either idle, in recovery (`waitState > 0`), or casting (`casting != null`). It is never both in recovery
   and casting. Without the `skills` feature, `casting` is always null.
5. `actionQueue.size ≤ 10`.
6. A world mutation happens on the world thread or not at all.
7. A dormant room has no running timers, AI, or combat. Its timers are all paused.
8. Stack merges only happen between instances with identical deltas.

### 3.9 World Schema

Each world declares in `MODULE.gups` the names its actors are measured by. The engine names no stat, vital or damage
type itself; everything that gives them meaning (damage, costs, regeneration, death) is in the rules module (§4.6).

```python
# The template world: a fantasy term.
module(
  api_version=1,
  stats=[
    stat("str", label="Strength"), stat("dex", label="Dexterity"),
    stat("con", label="Constitution"), stat("int", label="Intelligence"),
    stat("wis", label="Wisdom"), "armor",
  ],
  vitals=[vital("hp", label="Hit points"), "mana", vital("move", label="Movement")],
  damage_types=["bludgeon", "slash", "pierce", "fire"],
  unarmed_damage_type="bludgeon",
  channels=["spell"],
  prompt="<{hp}hp {mana}m {move}mv> ",
)
```

```python
# samples/robot-world: a post-apocalyptic term.
module(
  api_version=1,
  stats=["might", "agility", "tech", "grit", "plating"],
  vitals=[vital("integrity", label="Integrity"), "sanity", "power",
          vital("rads", label="Radiation")],
  damage_types=["kinetic", "energy", "psychic"],
  unarmed_damage_type="kinetic",
  channels=["psionic"],
  features=["combat", "skills"],
  rename={"cast": "invoke"},
)
```

- **Declarations:** `stat(name, label = None)` and `vital(name, label = None)`; a plain string is the same as the call
  with only a name. The label is shown to players (`score`, GMCP) and defaults to the name with its first letter
  capitalized. Declaration order is display order everywhere.
- **Numbers belong to actors, not the schema.** A schema has no starting values, maxima or floors: every new character
  and every spawned mob gets its numbers from `rules.starting_stats` (§4.6), so they may differ per actor and be
  randomized.
- **Names** of stats, vitals, damage types and channels match `[a-z][a-z0-9_]*`.
  - Stat and vital names are unique together, and may not equal an actor view field (§4.5), `base`, `resists`,
    `condition`, `below_zero_since`, or `max_<vital>` for a declared vital. The view fields are those of the actor view
    class itself, so a field added to it is reserved too. Vital names may not start with `max`.
  - Damage type names are unique; `unarmed_damage_type` must be one of them. It is the type of an unarmed auto-attack.
  - Channel names are unique and may not be a built-in channel (`sight`, `sound`). Declared channels can be blocked by
    exits and doors, listed in a skill's `channels`, sensed (`senses`, `sense_deltas`), and raised by scripts with
    `ctx.emit` (§5.7). The engine's own events use only the built-in ones.
  - `channel_priority = [...]` ranks channels, built-in or declared, from strongest to weakest. A player who is told
    about an event as it happens (§5.7) is told through the first channel they perceive that has text. A name that isn't
    a channel, or one listed twice, stops the server. Channels left out rank after the listed ones, `sight` and `sound`
    first, then the declared channels in order. It is not recorded per database; changing it needs only a restart.
  - A schema problem stops the server, providing "did you mean" suggestions for close matches based on optimal string
    alignment distance ([Schema.kt](../core/src/main/kotlin/gups/core/model/Schema.kt)).
- **`prompt`** is the telnet prompt (§4.10). Its placeholders are `{<vital>}` and `{max_<vital>}` for declared vitals;
  another placeholder stops the server. A placeholder is `{name}` with `name` matching `[a-z][a-z0-9_]*`; braces that
  don't form one print as written. The default is `<` then `{<vital>}<vital>` for each vital, space-separated, then `>
  `.
- **`level_mode`:** `level_mode = "auto"` (the default) applies a level as soon as the actor has the XP; `"deferred"`
  only adds XP and waits for `ctx.level_up` (§5.4). It needs the `leveling` feature, and isn't recorded per database.
- **Typed values.** Wherever the API takes or returns numbers keyed by schema names, it uses a constructor that checks
  its keywords against the schema, with a suggestion for a misspelling. A plain dict in their place is an error that
  names the constructor.

  |  Constructor   |         Keys          |                                                      Used for                                                      |
  |----------------|-----------------------|--------------------------------------------------------------------------------------------------------------------|
  | `vitals(**n)`  | declared vitals       | skill `cost`, mob `vitals`, and the results of `mitigate`, `move_cost`, `regen` and `starting_stats`               |
  | `stats(**n)`   | declared stats        | `stat_deltas` on items, affects and `apply_affect`; mob `stats`; the results of `encumbrance` and `starting_stats` |
  | `resists(**n)` | declared damage types | mob `resists`                                                                                                      |

  Their values are ints, and they read back like structs (`cost.mana`). `DAMAGE` is a struct of the declared damage
  types (`DAMAGE.fire == "fire"`), so a misspelling fails at that line; plain strings are accepted wherever a damage
  type is, and checked too.

- **Where names are checked:**

  - At package load: `stat_deltas`, `cost`, mob `stats`/`vitals`/`resists`, item `damage_type`, the `damage(...)` and
    `heal(...)` primitives, `flee(below = ...)` and skill `channels`.
  - When the rules package loads: the dry run (§4.6).
  - At run time: actor view fields (`ctx.actor.lfe` is an error suggesting `life`), and the `type` and `vital` arguments
    of verbs.
- **Fixed per database.** The first start records the schema's names (stats, vitals, damage types and channels, in
  order) in `meta.world_schema`. A later start whose `MODULE.gups` declares different names refuses to start, detailing
  the added, removed, or reordered elements and explaining that saved characters and items cannot follow schema
  modifications without a `--wipe` ([SchemaRecord.kt](../persist/src/main/kotlin/gups/persist/SchemaRecord.kt)). Labels,
  `prompt` and `unarmed_damage_type` are not recorded per database and need only a restart. `gups migrate-check`
  verifies schema compatibility the same way (§7.3).

### 3.10 Feature Modules

The engine is a kernel plus four feature modules. A world lists the features it uses in `MODULE.gups`, `features =
["combat", "track", "skills", "leveling"]`; leaving `features` out turns all four on, and listing all four is the same
as leaving it out.

|      Feature       |                                                                                                                                                                                                                                                            Owns                                                                                                                                                                                                                                                            | Requires |
|--------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| kernel (always on) | Sessions, the scheduler, persistence, hooks, timers, affects (the `apply_affect` and `remove_affect` verbs), movement through exits, `look`, communication, `who`/`help`/`score`/`quit`, items and equipment, item affects (`item_affect`, item `affects` and `variants`), doors and containers, `use`; vitals, conditions, incapacitation, death, corpses and respawn; the `damage` and `heal` verbs and `mitigate`; `regen`, `move_cost`, `carry_limit`, `encumbrance`, `starting_stats`, `condition`, `on_player_death` | —        |
| `combat`           | Engagement, `kill`, `flee`, combat rounds, initiative, auto-attacks; `to_hit`, `damage`, `initiative`, `flee_chance`, `flee_fail_lag`; `before_attack`, `on_combat_tick`; the `aggressive`, `assist` and `flee` behaviors; `start_combat`, `stop_combat`, `enemies_near`, `allies_near`; the `fighting` and `initiative` fields of `Char.Status`                                                                                                                                                                           | —        |
| `track`            | Track indices and bands, room `size`, exit `entry_index`, spawn `at_index`, item `range`, `advance` and `retreat`, pinning, `retreat_check`, `advance_lag`, `retreat_lag`, `retreat_fail_lag`, `get_lag`, `knockback` and `pull`, the `SIZE` constants, `Room.Bands`, `@where`, `band_of`, a non-zero `index` argument of `teleport`, `spawn` and `move_item`, the skill ranges `melee`, `near` and `far`, `act_advance`/`act_retreat`                                                                                     | `combat` |
| `skills`           | `skill()` declarations and the `skills` package kind, mob and player skills, `cast`, warmups and `CastState`, interrupts, `cast_allowed`, `interrupt_check`, skill costs, `before_cast`/`on_cast_start`/`on_cast`, `act_cast`, the effect primitives `damage`, `heal`, `apply_affect`, `dispel`, `stun`, `summon` and `sequence`, `casting` in `Char.Status`                                                                                                                                                               | —        |
| `leveling`         | `level`, `xp` and `levelGains`, `xp_for_kill`, `xp_to_level`, `on_level_up`, `on_level_change`, the `award_xp`, `take_xp`, `level_up` and `level_down` verbs, `pending_levels`, XP and level loss at death, `aggressive(unless_level_above)`, level and XP in `Char.Status`, `score` and `who`                                                                                                                                                                                                                             | —        |

- **A feature that is off doesn't exist** for the world:
  - Its commands don't exist ("Huh?"), and `help` and `gups docs --world` leave them out.
  - Its declarations, declaration arguments, hooks, behaviors and package kinds fail their package's load with "`<name>`
    needs the `<feature>` feature".
  - Its `ctx` verbs and view fields are errors naming the feature.
  - Its rules exports are neither required nor allowed (§4.6).
  - Its GMCP packages and fields aren't sent.
- **Without `track`,** every room has one track slot and every actor and item sits at index 0, so combat, movement and
  items keep one code path; nothing shows bands. `look` lists them as `Also here: …` and `On the ground: …`. A skill's
  `range` may be omitted (any one target in the room), `self` or `room`. The rules `ctx.band` is `None`, and `get` costs
  no lag.
- **Without `combat`,** nobody engages. Damage from skills and verbs still lowers vitals and can still kill through
  `condition` (§5.4), with the last actor who damaged the victim as its killer. `quit` is never refused for fighting.
- **Without `skills`,** `use` and item `on_use` hooks are the way to act on things; affects still come from
  `ctx.apply_affect` and `equip_affects`.
- **Without `leveling`,** no XP is awarded and nothing levels up; `on_player_death`'s `xp_loss` and `level_loss` are
  refused.
- **Renaming commands:** `rename = {"cast": "invoke", "kill": "attack"}` renames non-`@` engine commands, of the kernel
  or of an enabled feature. The old name stops being a command and is free for global or plugin commands. Aliases,
  prefixes, reserved exit and character names, `help` and the docs use the new names. Renaming a command that doesn't
  exist (or whose feature is off), or to a name that isn't `[a-z][a-z0-9_]*`, is a direction, or is another command or
  alias, stops the server.
- **Problems:** an unknown feature, or one whose requirement is off, stops the server.
- **Fixed per database:** the enabled features are recorded in `meta.world_features` with the schema and checked the
  same way (§3.9). A database that predates feature modules is taken to have all four. `rename` and `MESSAGES.gups`
  aren't recorded and need only a restart.
- **Plugins** declare the features they need (§4.15).

---

## 4. Interface & API Signatures

Builtins take structured arguments: keyword arguments and native Starlark values (lists, dicts, `None`, booleans) rather
than strings with a syntax of their own, so that the loader can check them. Package declarations, content declarations,
and constant structs are defined in the [ApiModel.kt](../script/src/main/kotlin/gups/script/ApiModel.kt) and implemented
in [Declarations.kt](../script/src/main/kotlin/gups/script/Declarations.kt).

### 4.1 Package Declaration

```python
# zones/goblin_caves/package.star
package(
  api_version=1,
  kind="zone",  # zone | lib | rules | skills
  title="Goblin Caves",
  reset_world_ticks=60,  # zone reset interval (30 min); fires on_reset
  modifiers={},  # zone-wide modifiers (§4.6)
  shared_namespaces=[],  # privileged packages only (§3.7)
  expected_tombstones=[],  # §7.3 gate 5
  migrate_noop=[],  # prototypes whose version bump needs no migrate()
)
```

Modifier functions a `package()` names are top-level `def`s in `package.star` itself, above the `package()` call,
because `package.star` can't load anything.

### 4.2 Content Declarations

```python
room("entrance",
     name="Cave Entrance",
     desc="...",
     size=SIZE.normal,  # SIZE.cramped | SIZE.normal | SIZE.large
     regions=["goblin_territory"],
     flags=[],  # "always_active", "dead_end_ok"
     tags=[],  # free-form, for plugins (§4.19)
     exits=[
       exit(DIR.north, to=":tunnel",
            door=door(state=DOOR.closed, key="//zones/goblin_caves:rusty_key",
                      tags=["creaky"])),  # free-form words the rules read (§4.6)
       exit(DIR.east, to=":long_hall", tags=["sealed"]),  # the template's sense_cost never carries anything
       exit(DIR.south, to="//zones/forest:clearing", entry_index=-1),  # arrive at back
     ],
     spawns=[spawn(":goblin_scout", count=2, respawn_pulses=1200, at_index=2)],
     modifiers={},  # §4.6
     commands={"read": _read_sign},  # zone verbs, tick lane
     before_exit=_guard_check,  # hooks by name, §4.4
     )

mob("goblin_scout",
    name="a goblin scout", keywords=["goblin", "scout"],
    level=3, stats=stats(str=10, dex=14, con=9, int=6, wis=6, armor=2),
    vitals=vitals(hp=30), resists=resists(fire=-10),  # schema-checked (§3.9)
    skills={"//skills/martial:kick": 40},
    senses={"sound": 3, "sight": 0},  # merged over sight/sound = 1; 0 senses nothing (§5.7)
    behaviors=[wander(radius=4, chance=0.1), aggressive(toward="players"),
               assist(allies=["goblin_*"]), flee(below=vitals(hp=20))],
    think=_scout_think, think_every=4,
    traits=[],  # plugin-defined traits, e.g. ["mage"] (§4.19)
    dormant_recovery="full",  # full | regen | none (§5.9)
    loot=[loot(":rusty_key", chance=0.25)],
    version=2,
    )

item("short_bow",
     name="a short bow", plural=None, kind="ranged", range="far", weight=2,
     wear="wield", dice="1d6", damage_type="pierce",
     stat_deltas=stats(dex=1),
     sense_deltas={"sight": 1},  # sense score changes while worn (§5.7)
     tags=["light_weapon"],  # free-form, for plugins (§4.19)
     modifiers={}, equip_affects=[],  # apply while worn (§4.6)
     affects=[],  # item_affect(...) entries (§3.3)
     variants={},  # related prototypes, for transform (§4.5)
     stackable=False, decay_pulses=None,
     commands={}, on_use=None,
     )

item("satchel", container=container(capacity=10, max_weight=20,
                                    state="closed", key=None))

region("null_field", modifiers={...})  # region-wide modifiers (§4.6)


def migrate(old_version, attrs):  # optional, per package (§3.4)
  if old_version < 2:
    attrs["alarm_raised"] = attrs.pop("alarm", False)
  return attrs
```

**Declaration rules:**

- **Arguments:** every declaration takes its identifier positionally (`id` in `room`, `mob` and `item`), everything else
  by keyword. `exit` takes `direction` positionally and `to` positionally or by keyword; `global_command` takes `name`,
  `fn` and `lane` either way. Unknown arguments are errors with a spelling suggestion. A package targeting a different
  `api_version` gets the version-mismatch diagnostic before any unknown-argument error.
- **Defaults:** the display `name` defaults to the identifier with `_` as spaces. `keywords` default to the display
  name's words minus articles, or else the identifier. An item may also give `plural` (for example `plural = "gold
  coins"`), used when showing stacks of 2 or more; a blank `plural` fails to load. Players can name an item by the words
  of its keywords or of its plural.
- **Damage type:** an item's `damage_type` is a declared damage type, `DAMAGE.fire` or the plain string, checked at
  load; an item that names none uses the world's `unarmed_damage_type`.
- **Item slots:** an item's slot is `wear = "<slot>"`; `wield = "<slot>"` is an alias. A slot family such as `wear =
  "finger"` fits the first free `finger_*` slot. An item with `equip_affects` or `modifiers` needs a `wear` slot. Each
  `equip_affects` entry is either an affect flag (for example `no_regen` or `silenced`), which the wearer has while the
  item is worn, or `{"flag": ..., "pulses": n}`, which is applied for `n` pulses when the item is equipped and runs out
  even if the item stays on. Equipping applies each entry to the wearer as an affect, through `apply_affect` like a
  spell's, and removing the item removes the untimed ones. `stunned` must be timed, so an item can't stun its wearer for
  good (§5.4.1). `item_affect(name, applies = "worn", stat_deltas = stats(), sense_deltas = {}, modifiers = {}, flags =
  [])` declares a prototype affect. A `worn` affect with `modifiers` needs a `wear` slot; a `carried` one doesn't.
  `stunned` isn't allowed. `variants` keys match the tag pattern and its values are item labels, checked at load like
  `loot`.
- **Tags:** `room`, `item` and `skill` take `tags`, a list of words matching `[a-z][a-z0-9_]*`, checked at load, stored
  sorted without repeats. The engine gives them no meaning; plugin restrictions and scripts read them (§4.19). They are
  part of the prototype, so a reload changes them for every instance; items can also gain or lose tags per instance
  (§4.5). The tag `no_all` keeps an item out of bare `all` in `get`, `drop`, `give` and `put`; `//engine:corpse` carries
  it.
- **Mob traits:** `mob(traits = [...])` names traits the world's plugins define (§4.19), stored sorted without repeats.
  A name no loaded plugin defines fails the package's load with a "did you mean" suggestion, as a skipped plugin's
  builtins do; every tool that loads packages registers the plugins first, so `gups lint` checks it too. The traits'
  deltas apply on top of the mob's declared `stats`, `vitals` and `resists`, which stay base values.
- **Containers:** `container(capacity, max_weight, state, key)` makes an item a container. `capacity` counts items;
  `max_weight` weighs the contents.
- **Regions:** `region(name, modifiers = {...})` declares a region's modifiers. Region names are lowercase words and
  world-wide: one package declares each, and two packages declaring the same name both fail to load. A room may carry a
  region nobody declares; it is then only a tag.
- **Loot:** a loot `chance` is a probability between 0 and 1.
- **Mob numbers:** a mob's `stats`, `vitals` (maxima) and `resists` are its prototype's declared values, the input
  `rules.starting_stats` sees at each spawn (§4.6). There are no `hp` or `armor` arguments; those are schema names.
- **Features:** arguments that belong to a feature that is off (`size`, `entry_index`, `at_index` and `range` with
  `track`; `skills` with `skills`; `level` with `leveling`) fail the load, naming the feature (§3.10).
- **Constants:** Common engine constants are namespaced into structs (such as `DIR`, `MODIFIER`, `SIZE`, `SENSE`,
  `DOOR`, and `DAMAGE`) to prevent bare-string typos. The constant namespaces and their values are defined in
  [Declarations.kt](../script/src/main/kotlin/gups/script/Declarations.kt) (`DeclarationBuiltins.constants`).
- **`struct(**fields)`** makes an immutable record. Libraries use it to export a namespace of functions (`quest.start`).
- **When builtins work:** declaration builtins work only while a package loads; effect primitives work anywhere.
- **`print()`:** in hooks its output goes to the trace; in tests, to the test's report (shown when the test fails); at
  load time it is dropped.

### 4.3 Skills and Spells

Everything in this section is the `skills` feature (§3.10).

```python
skill("fireball",
      kind="spell",  # free-form; rules interpret it
      cost=vitals(mana=25),  # schema-checked (§3.9)
      warmup=6,  # pulses in CASTING; 0 = instant
      lag=4,  # waitState pulses after resolution
      range="far",  # melee | near | far | self | room
      target="enemy",  # enemy | ally | self | item | none
      channels=["spell", "sound"],  # built-in or declared channels
      interrupt={"damage_pct": 10, "stun": True},
      attack_while_casting=False,  # default; True lets auto-attacks continue during warmup
      effect=damage(dice="4d6", type=DAMAGE.fire),
      resolve=None,  # optional custom Starlark effect: def f(ctx)
      tags=["arcane"],  # free-form, for plugins (§4.19)
      )
```

**Range semantics:** `melee` means a band of 0, `near` means ≤1, `far` means any band in the same room, `self` targets
the caster only, and `room` affects everyone in the room except the caster (and, with a bubble, except dormant actors).
Without the `track` feature, `melee`, `near` and `far` are load errors, and an omitted `range` means any one target in
the room.

**Costs:** a cost is affordable when each named vital's `current` is at least the amount. Otherwise the cast fails
validation with "You don't have enough <label>." (§4.18).

**`interrupt`:** the engine reads only `stun`. The whole dict is given to `rules.interrupt_check` as
`ctx.skill.interrupt`, which decides what `damage_pct` means.

**Targeting:**

- An `enemy` skill with no keyword targets the caster's primary target.
- An `ally` skill with no keyword targets the caster.
- An `item` skill finds its item by keyword among worn, carried and ground items (ground items within `get_lag` reach).
  Its `effect` acts on the item: `apply_affect` attaches an item affect (with `applies`), plugin primitives receive the
  item, and `sequence` may combine them. Any other primitive in an item skill's effect fails the load. In `resolve` and
  the cast hooks, the item is `ctx.target`.
- The target is chosen at the first validation and stays fixed through the warmup.

**Proficiency** is stored but not rolled against: the rules have no proficiency input yet. Rules functions can read it
from `ctx.actor.skills`.

**Effect primitives (v0):**

|                                                                                Primitive                                                                                 |                                                                                                                                                                                                                                   Purpose                                                                                                                                                                                                                                   |
|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `damage(dice, type)`                                                                                                                                                     | Deal damage of a declared type, by default the world's `unarmed_damage_type`. The roll goes through `rules.mitigate`, which returns the vital losses (§4.6).                                                                                                                                                                                                                                                                                                                |
| `heal(dice, vital)`                                                                                                                                                      | Restore a declared vital, up to its maximum.                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `apply_affect(name, duration, stat_deltas=stats(), flags=[], tick_every=None, on_tick=None, on_expire=None, expire_msg="", modifiers={}, sense_deltas={}, applies=None)` | Attach a timed buff or debuff. On an `item` skill, attaches an item affect; `applies` (`worn` or `carried`, `worn` when omitted) is allowed only there, and `duration` may be `None` for a permanent one.                                                                                                                                                                                                                                                                   |
| `dispel(name=None, flag=None, all=False, count=None)`                                                                                                                    | Remove the target's affects that match every criterion given: `name` exactly, or as a prefix when it ends in `*`; `flag` (a string or a list) when the affect has any of those flags; `all=True` matches every affect. At least one criterion is required, checked at load. `count` caps how many are removed, oldest first. Affects flagged `intrinsic` are never dispelled. Each removed affect sends its `expire_msg` and fires its `on_expire` with reason `"removed"`. |
| `knockback(steps)`                                                                                                                                                       | Push the target away from the caster. On a shared index it goes toward the side with more room, ties toward the back (higher index). Clamped to the track. `track` feature only.                                                                                                                                                                                                                                                                                            |
| `pull(steps)`                                                                                                                                                            | Draw the target toward the caster, never past it. Clamped to the track. `track` feature only.                                                                                                                                                                                                                                                                                                                                                                               |
| `stun(pulses)`                                                                                                                                                           | Stun the target.                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `summon(label, count=1, duration=None)`                                                                                                                                  | Spawn mobs beside the caster, each with `summonedBy` set to the caster. With a `duration`, each gets a `summoned` affect; when it expires or is removed, the mob fades away, disengages and leaves the world.                                                                                                                                                                                                                                                               |
| `sequence(*effects)`                                                                                                                                                     | Combine several primitives, passed as varargs.                                                                                                                                                                                                                                                                                                                                                                                                                              |

Spell `damage(...)` effects don't go through the `damage` modifier point; only auto-attacks do (§4.6).

### 4.4 Hook Catalog

Every hook is a named top-level function. Hooks named `before_*` may return `ctx.deny(msg)` to veto the action. Any
other return value means "allow". The return values of `on_*` hooks are ignored.

|                             Hook                              |   Veto    |               Attach to               |                                   Key ctx fields                                    |
|---------------------------------------------------------------|-----------|---------------------------------------|-------------------------------------------------------------------------------------|
| `on_load`                                                     | —         | Room, Mob, Item                       | `self`                                                                              |
| `on_spawn`                                                    | —         | Room, Mob, Item                       | `self`, `item`                                                                      |
| `on_reset`                                                    | —         | Room (zone reset)                     | `self`                                                                              |
| `on_wake(ctx, elapsed_pulses)`                                | —         | Room, Mob, Item                       | `self`, `elapsed_pulses`                                                            |
| `before_enter` / `on_enter`                                   | ✓ / —     | Room, Mob (observer)                  | `actor`, `direction`, `from_room`                                                   |
| `before_exit` / `on_exit`                                     | ✓ / —     | Room, Mob (observer)                  | `actor`, `direction`, `to_room`                                                     |
| `on_say`                                                      | —         | Mob, Room                             | `actor`, `text`                                                                     |
| `on_tell`                                                     | —         | Mob                                   | `actor`, `text` (no `target`)                                                       |
| `before_get` / `on_get`                                       | ✓ / —     | Item, Mob (observer)                  | `actor`, `item`                                                                     |
| `before_drop` / `on_drop`                                     | ✓ / —     | Item, Mob (observer)                  | `actor`, `item`                                                                     |
| `before_use` / `on_use`                                       | ✓ / —     | Item                                  | `actor`, `item`, `target`                                                           |
| `on_give`                                                     | —         | Mob (receiver), Item                  | `actor`, `item`, `target`                                                           |
| `before_attack`                                               | ✓         | Mob, Item (equipped)                  | `actor`, `target`, `weapon`                                                         |
| `before_cast` / `on_cast_start` / `on_cast`                   | ✓ / — / — | Mob, Item (equipped), Room            | `actor`, `skill`, `target`                                                          |
| `on_damage`                                                   | —         | Mob, Item (equipped)                  | `actor` (source), `target`, `amount`, `type`, `losses`                              |
| `on_death`                                                    | —         | Mob, Room                             | `actor` (killer), `target`                                                          |
| `on_level_change`                                             | —         | Mob, Room, Item (carried or equipped) | `actor`, `old_level`, `new_level`                                                   |
| `on_combat_tick`                                              | —         | Mob, Item (equipped)                  | `self`, `actor`, `targets`                                                          |
| `on_tick`                                                     | —         | Any (world tick)                      | `self`                                                                              |
| `on_perceive`                                                 | —         | Mob (§5.7)                            | `event`, `channel`, `intensity`, `cost`, `actor`, `text`, `source_room`, `via_exit` |
| `before_open`, `before_close`, `before_lock`, `before_unlock` | ✓         | Item, Room, Mob (§5.7)                | `actor`, `direction` (a door) or `item` (a container)                               |
| `on_open`, `on_close`, `on_lock`, `on_unlock`                 | —         | Item, Room, Mob (§5.7)                | `actor`, `direction` (a door) or `item` (a container)                               |
| `think`                                                       | —         | Mob                                   | `self`, `proposals`                                                                 |
| `commands[verb]`                                              | —         | Room, Item                            | `actor`, `args`, `target`, `item`                                                   |
| affect `on_tick`                                              | —         | Affect                                | `self` (host), `affect`                                                             |
| affect `on_expire`                                            | —         | Affect                                | `self` (host), `affect`, `reason`                                                   |

**Observer semantics:** Hooks on a mob fire for actions *by others in the same room*. `ctx.self` is always the hook's
owner.

**Context fields per hook:** Besides `self`, `room`, `pulse` and `rng`, the "Key ctx fields" column is exact: those are
the fields the engine may set for that hook, and every other context field is `None`. The enter hooks set only
`from_room` of the two rooms, and the exit hooks only `to_room`.

**Catalog enforcement:** A hook keyword argument must be a catalog name (a typo is an error that suggests the right one)
and must be attachable to that kind of entity per the "Attach to" column. `before_attack` and `on_combat_tick` need the
`combat` feature, and the cast hooks need `skills` (§3.10).

**`on_damage`:** `ctx.amount` is the total of the vital losses and `ctx.losses` the losses themselves, a `vitals(...)`
value (§3.9); `ctx.type` is the damage type. It fires once per damage event, after the losses are applied.

**`on_level_change`** needs `leveling`. It fires once per level step, after the gains or their unwinding and the gain
record, so it sees the new state; a loss of three levels fires it three times. A level gained is announced before its
hook fires; a loss is announced once, after the last step's hook.

**Timing:**

- `on_load` fires once on every entity, ascending id, after the world is built (or restored from the database) and
  global commands are installed. A fresh world's spawns, filled as it starts (§4.11), fire `on_spawn` first. A reload
  fires `on_load` only on rooms it creates. Characters restored at login don't get it.
- `before_enter` dispatches in the destination room. On arrival the player's `look` is sent before `on_exit` and
  `on_enter` fire.
- `on_give` fires with the item as the primary owner, then the room and the others in it (so the receiver), with
  `ctx.target` the receiver. There is no `before_give`.

### 4.5 The `ctx` Object

`ctx` is created fresh for each hook invocation and is invalid after the hook returns. Context instances and read-only
views are implemented in [Ctx.kt](../script/src/main/kotlin/gups/script/Ctx.kt) and
[Views.kt](../script/src/main/kotlin/gups/script/Views.kt). Every mutation goes through a verb. The engine validates
each verb and applies it immediately, on the world thread.

**Context fields:** `self`, `actor`, `target`, `item`, `room`, `direction`, `text`, `args`, `event`, `source_room`,
`via_exit`, `skill`, `weapon`, `amount`, `type`, `losses`, `proposals`, `elapsed_pulses`, `from_room`, `to_room`,
`old_level`, `new_level`, `affect` (affect hooks), `reason` (affect `on_expire`: `"expired"` when the affect reached its
expiry pulse, else `"removed"` for `remove_affect` or `dispel`; an affect removed on or after its expiry pulse counts as
expired), `targets` (`on_combat_tick`), `pulse`, `rng`.

- **Commands:** `ctx.args` is the argument text, `ctx.target` the actor in the room matching the first argument word,
  and `ctx.item` the item for item commands. `ctx.self` is the room or item, or the actor for global commands.
- **Item targets:** `ctx.target` is the target item for `use` and item skills.

**Views:**

- Views read live state, and compare equal when they show the same entity.
- `attrs` shows the reader's own keys by short name.
- Using a `ctx` or view after its hook returned is an error.

**Actor view:**

- **Identity:** `id`, `name`, `label`, `keywords`, `is_player`, `in_world`.
- **Schema fields:** (§3.9), generated from the world's schema:
  - `<stat>`, the effective value, for each declared stat (`ctx.actor.dex`), and `base.<stat>` for the base value.
  - `<vital>` and `max_<vital>` (the effective maximum, traits included, §4.19) for each declared vital (`ctx.actor.hp`,
    `ctx.actor.max_hp`), `base.max_<vital>` for the base maximum, and `below_zero_since(vital)`, the pulse the vital
    last dropped below 0, or `None`.
  - `resists.<type>` (effective) and `base.resists.<type>` for each declared damage type.
  - `senses` (the effective score on every channel) and `base.senses` (the unmodified scores).
  - An undeclared name is an error with a suggestion.
- **Creation:** (§4.19): `choices`, a dict of choice id → option id (known answers only; empty for mobs), `traits`, the
  sorted names of the traits the actor carries, and `has_trait(name)`.
- **Life:** `condition`, one of `"ok"`, `"incapacitated"` and `"dead"`.
- **Leveling:** (`leveling` feature): `level`, `xp`, and `pending_levels`: how many levels the actor has the XP for and
  has not been given (§5.4). Reading it without `leveling` is an error, like `level`.
- **Position:** `room`, `track_index` and `band_of(other)` (`track` feature), `home_room`, `recall_room`.
- **State:** `attrs`, `affects`, `skills` and `is_casting`/`casting` (`skills` feature), `flags`, `has_flag(flag)`,
  `equipped(slot)`, `inventory()`, `carried_weight`, `has_item(label)`, `fighting()` (`combat` feature), `wait_state`.

**Room view:**

- **Identity:** `label`, `name`, `desc`, `size`, `track_size`, `regions`, `flags`, `tags`.
- **Contents:** `exits()`, `actors()`, `items()`, `contents()`.
- **Exits:** `exit(dir)`, an exit view with its `tags` and `door`: a door view (`state`, `key`, `tags`), or `None`
  without a door.

**Item view:** `id`, `label`, `name`, `plural`, `keywords`, `desc`, `kind`, `count`, `weight`, `location`, `attrs`,
`container_state`, `contents()`, `dice`, `damage_type`, `range`, `wear`, `stat_deltas`, `sense_deltas`, `tags`,
`in_world`.

**Skill view:** includes `cost`, `channels`, `interrupt` and `tags`.

**Verbs (v0):**

|       Group       |                                                                                                                                         Verbs                                                                                                                                          |
|-------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Messaging         | `say(speaker, text)`, `emote(actor, text)`, `send(target, text)` (one recipient), `echo(room, text, exclude=[])`                                                                                                                                                                       |
| Veto              | `deny(msg)`. Valid only as a return value from `before_*` hooks or `cast_allowed` modifiers.                                                                                                                                                                                           |
| Mob actions       | `act(mob, command_line)`, `act_move(mob, dir)`, `act_attack(mob, target)`, `act_cast(mob, skill, target=None)`, `act_advance(mob, target)`, `act_retreat(mob, target=None)`, `move_toward(mob, dir_or_target)`. These enqueue tick-lane actions and apply only to mobs, never players. |
| Movement          | `teleport(actor, room, index=None)`. Engine-validated; fires the enter and exit hooks.                                                                                                                                                                                                 |
| Vitals and combat | `damage(target, amount, type, vital, source=None)`, `heal(target, amount, vital)`, `start_combat(a, b)`, `stop_combat(a, b=None)`, `apply_affect(target, ...)`, `remove_affect(target, name)`                                                                                          |
| Items             | `spawn(label, at, index=0, count=1)`, `give(actor, label_or_item, count=None)`, `move_item(item, to, index=0, count=None)`, `destroy(item, count=None)`                                                                                                                                |
| Perception        | `emit(channel, event, intensity=1, text=None, room=None, actor=None)`: raises a perceivable event (§5.7). `room` defaults to `ctx.room` and `actor` to `ctx.actor`; an intensity of 0 or less emits nothing.                                                                           |
| Doors and exits   | `open_door(room, dir)`, `close_door`, `lock_door`, `unlock_door`                                                                                                                                                                                                                       |
| Containers        | `open_container(item)`, `close_container`, `lock_container`, `unlock_container`                                                                                                                                                                                                        |
| State             | `set_attr(entity, key, value)`, `get_attr(entity, key, default=None)`, `del_attr(entity, key)`                                                                                                                                                                                         |
| Timers            | `after(pulses, fn, **args) -> timer_id`, `cancel(timer_id)`                                                                                                                                                                                                                            |
| Simulation        | `set_active(entity, reason)`, `clear_active(entity, reason)`, `set_anchor(entity, reason)`, `clear_anchor(entity, reason)`                                                                                                                                                             |
| Queries           | `here(keyword)`, `find(label, scope="room")`, `enemies_near(actor, band)`, `allies_near(actor, band)`, `path_to(target) -> [dir]`, `roll(dice) -> int`                                                                                                                                 |
| Randomness        | `rng.int(lo, hi)`, `rng.chance(pct)`, `rng.choice(list)`. Seeded and deterministic; there is no other source of randomness.                                                                                                                                                            |
| Leveling          | `award_xp(actor, amount)`, `take_xp(actor, amount)`, `level_up(actor)`, `level_down(actor, count=1)`                                                                                                                                                                                   |

**Verb semantics:**

- **Errors:** a bad verb argument raises a script error at once. Invalid-argument and invalid-state errors from the
  engine become script errors too.
- **`deny`:** raises unless the running hook is a `before_*` hook (or a `cast_allowed` modifier).
- **Timers:** callbacks are called as `fn(ctx, **args)` and also see the arguments as `ctx.args`. The timer's owner is
  the scheduling hook's `ctx.self`.
- **`damage` and `heal`:** `damage` is raw: no to-hit roll and no mitigation. It lowers the named vital by `amount`,
  with no floor, and fires `on_damage` with `losses` holding just that vital. `heal` raises the named vital up to its
  maximum. Both name a declared vital and damage type, checked when called: an undeclared name raises an error with a
  suggestion before anything changes.
- **Features:** `start_combat`, `stop_combat`, `enemies_near` and `act_attack` need `combat`; `act_cast` needs `skills`;
  `act_advance`, `act_retreat` and the `index` arguments of `teleport`, `spawn` and `move_item` need `track` (§3.10).
- **Items:**
  - `spawn` returns a view, or a list when it makes several entities. For items, `at` may be a room or room label
    (ground, at `index`), an actor (carried) or a container.
  - `give(actor, label_or_item, count=None)` spawns `count` of a label (default 1), or moves `count` of an item (default
    the whole stack), into the actor's inventory. It returns the item view, or a list of views when it spawns several
    non-stackable items, and fires no hooks.
  - `move_item(item, to, index=0, count=None)` moves the item, or `count` of a stack, and returns the moved item's view.
    A moved part is a new item with the stack's attributes, name override, debug flag and decay deadline, and doesn't
    fire `on_spawn`. Moving part of an equipped or non-stackable item is an error. A moved part also copies the stack's
    instance affects and tag changes.
  - `destroy(item, count=None)` removes the item and everything inside it, or, when `count` is less than the stack's
    count, removes that many from the stack.
  - Verbs never merge stacks. An item with instance affects or tag changes never merges into a stack.
- **Doors:** door verbs set both sides at once and print nothing. `open_door` also unlocks; `close_door` leaves a locked
  door locked; `lock_door` closes the door; `unlock_door` leaves it closed.
- **Containers:** container verbs print nothing. `open_container` also unlocks; `close_container` leaves a locked
  container locked; `lock_container` closes the container and fails on one whose prototype has no `key`, since only a
  container with a key has a lock; `unlock_container` leaves it closed.
- **Queries:**
  - `here(keyword)` searches the owner's room: actors first, then ground items.
  - `find(label, scope)` counts entities whose location chain ends in the room, so items carried there count. `"zone"`
    means rooms of the owner's room's package.
  - `enemies_near` returns engaged opponents. `allies_near` returns actors on the same side (players with players, mobs
    with mobs) not engaged with the actor.
  - `path_to` returns an empty list when already there and `None` when unreachable (§5.9).
- **Mob actions:**
  - They return whether the action was queued.
  - `move_toward(mob, x)` takes a direction, advances on an actor in the same room, or takes the first step of
    `path_to`. It returns `False` when there is no way.
  - `act_cast` requires the mob to know the skill.
- **Attributes:** `get_attr` returns a mutable copy.
- **Simulation verbs:** return `None`. A reason added twice counts once.
- **`apply_affect`:** takes `modifiers`, as the effect primitive does.

Verbs without a player command (`say`, `emote`, door and exit-block setters, `path_to`, and the mob actions) are public
`core` functions, so Kotlin and plugins share them.

- `award_xp(actor, amount)`: gives `actor` `amount` experience (an int of at least 1) as a kill does: the gain message,
  then level steps as the world's `level_mode` says (§5.4). Returns `None`.
- `take_xp(actor, amount)`: removes up to `amount` experience (at least 1), never below 0, and never changes the level.
  Sends nothing. Returns the amount removed.
- `level_up(actor)`: applies one pending level (§5.4). Returns `True`, or `False` with no change if none is pending. In
  `auto` mode the engine calls it for each owed level.
- `level_down(actor, count=1)`: takes up to `count` levels (at least 1) from `actor`, unwinding the gains they gave, and
  stops at level 1. Returns the number of levels taken. All four need `leveling`, and `actor` may be a player or a mob.

### 4.6 Rules Module and the Modifier Chain

The rules package is required, and its `.star` files (the package is named `rules`, kind `rules`; `gups lint` reports
its absence; the template splits them into `combat.star`, `perception.star` and `manipulation.star`) define the world's
rules as the functions below. Rules execution, synthetic dry runs, and modifier chain dispatch are implemented in
[Rules.kt](../script/src/main/kotlin/gups/script/Rules.kt). Each export is defined in exactly one non-test file of the
package; defining it in two is a load error naming both files. A missing export is reported once for the package, after
all its files have loaded, since it belongs to no file; a forbidden export or a public function that isn't an export is
reported at its file and line. The engine has no built-in ruleset and nothing to fall back to: every export that an
enabled feature uses (the "Feature" column) must be defined, and an export of a feature that is off must not be. The
engine calls them with a rules `ctx` that is read-only, apart from `deny`. Any other public top-level function in a
rules file (one whose name doesn't start with `_`) is a load error for the rules package. A misspelled export can't be
silently ignored; helpers take a `_` prefix.

**The rules `ctx`** has `actor`, `attacker` (the same actor), `defender` and `target` (the other actor, if any: the
defender, the pinner, the killed or the killer, or the actor advanced on or retreated from), `weapon`, `item`, `band` (a
word in the calls that set one, such as `get_lag` and the track lag exports; otherwise `None`, and always `None` without
the `track` feature), `skill`, `room` (the actor's, or the room the call is about), `pulse`, `rng`, `roll(dice)`, and
`deny(msg)`, valid only in `cast_allowed` and its modifiers. It has no verbs. Its actor views have the schema fields of
§4.5, and `choices`, `traits` and `has_trait` (§4.19).

|                   Function                    |  Feature   |                                                                                                   Returns                                                                                                    |
|-----------------------------------------------|------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `equipment_slots()`                           | kernel     | `list[str]`, the equipment slots.                                                                                                                                                                            |
| `starting_stats(ctx)`                         | kernel     | `struct(stats = stats(...), max = vitals(...), current = vitals(...), senses = {...})`, every field optional. Base numbers for a new character (after its choices, §4.19) or a spawned mob.                  |
| `condition(ctx)`                              | kernel     | `"ok"`, `"incapacitated"` or `"dead"`.                                                                                                                                                                       |
| `mitigate(ctx, amount, type)`                 | kernel     | `vitals(...)`, the vital losses that `amount` damage of `type` causes.                                                                                                                                       |
| `move_cost(ctx)`                              | kernel     | `vitals(...)`, what a room transition spends.                                                                                                                                                                |
| `regen(ctx)`                                  | kernel     | `vitals(...)`, what each vital gains per world tick (negative to lose).                                                                                                                                      |
| `carry_limit(ctx)`                            | kernel     | Weight.                                                                                                                                                                                                      |
| `encumbrance(ctx)`                            | kernel     | `stats(...)`, deltas applied while over the limit.                                                                                                                                                           |
| `on_player_death(ctx)`                        | kernel     | `{"respawn": label, "vitals": vitals(...), "xp_loss": int, "level_loss": int}`                                                                                                                               |
| `to_hit(ctx)`                                 | `combat`   | Hit chance in percent. `ctx.attacker`, `defender`, `weapon`, `band`, `skill`.                                                                                                                                |
| `damage(ctx)`                                 | `combat`   | Raw auto-attack damage, an int.                                                                                                                                                                              |
| `initiative(ctx)`                             | `combat`   | Int; higher acts first within a combat round (§5.4.1). `ctx.actor`, `ctx.rng`.                                                                                                                               |
| `flee_chance(ctx)`                            | `combat`   | Percent.                                                                                                                                                                                                     |
| `retreat_check(ctx)`                          | `track`    | Success chance in percent, used when pinned.                                                                                                                                                                 |
| `get_lag(ctx, band)`                          | `track`    | Extra lag for picking up an item at a band. `None` means not allowed.                                                                                                                                        |
| `advance_lag(ctx)`                            | `track`    | Recovery lag in pulses after `advance`. `ctx.target` is the actor approached; `ctx.band` is the band after the step.                                                                                         |
| `retreat_lag(ctx)`                            | `track`    | Recovery lag after a successful `retreat`. `ctx.target` is the threat; `ctx.band` is the band after the step.                                                                                                |
| `retreat_fail_lag(ctx)`                       | `track`    | Recovery lag after a failed `retreat_check`. `ctx.target` is the pinner; `ctx.band` is `"melee"`.                                                                                                            |
| `flee_fail_lag(ctx)`                          | `combat`   | Recovery lag after a failed `flee`, whether the roll failed or the exit refused. `ctx.target` and `ctx.band` are `None`.                                                                                     |
| `cast_allowed(ctx)`                           | `skills`   | `None` or `deny(msg)`.                                                                                                                                                                                       |
| `interrupt_check(ctx, amount)`                | `skills`   | Whether a casting actor is interrupted, a bool.                                                                                                                                                              |
| `sense_cost(ctx)`                             | kernel     | `int`. What an event on `ctx.channel` loses crossing `ctx.exit` into `ctx.room`, one hop toward the listeners; a negative price blocks the hop and 0 is free. `ctx.actor` is `None`.                         |
| `can_perceive(ctx)`                           | kernel     | `bool`. Whether the candidate listener `ctx.actor` notices the event (§5.7).                                                                                                                                 |
| `perceive_message(ctx)`                       | kernel     | `str` or `None`. What a player is told about an event they can perceive: in another room, or for a `shout`, also in its own room. In the event's own room otherwise, it is asked only about a world channel. |
| `door_intensity(ctx)`                         | kernel     | `int`. The sound intensity of `ctx.action` on a door (`ctx.exit`) or container (`ctx.item`); 0 or less is silent.                                                                                            |
| `xp_for_kill(ctx)`, `xp_to_level(ctx, level)` | `leveling` | Ints.                                                                                                                                                                                                        |
| `on_level_up(ctx)`                            | `leveling` | `None` or `struct(stats, max, current)`: deltas.                                                                                                                                                             |

**Perception exports** (§5.7): the perception calls get a `ctx` about a listener or a room rather than a fighter.

- `sense_cost`: `ctx.room` is the room the signal enters, `ctx.exit` the exit of that room leading back toward the
  source (with its `door` and `tags`), `ctx.channel`, `pulse`, `rng`; `ctx.actor` is `None`. The template's `sense_cost`
  charges one extra point on the `sound` channel for entering a `large` room. `room.size` is part of the `track`
  feature, so a world without `track` leaves that check out.
- `can_perceive` and `perceive_message`: `ctx.actor` (also `ctx.target`) is the listener, `ctx.source` the actor who
  caused the event (or `None`), `ctx.room` the listener's room, `ctx.source_room` where it happened, `ctx.via_exit` the
  listener's exit toward it (`None` in the same room), `ctx.channel`, `ctx.event`, `ctx.intensity` (the starting
  strength), `ctx.cost` (the attenuation along the cheapest path; 0 in the same room) and `ctx.text`.
- `door_intensity`: `ctx.actor`, `ctx.room`, `ctx.action` (`open`, `close`, `lock` or `unlock`), `ctx.tags` (those of
  the door or container), `ctx.exit` (a door) or `ctx.item` (a container). A negative result is 0.

**Signatures and results:**

- `mitigate(ctx, amount, type)` gets the damage type as a string, `get_lag(ctx, band)` the band as a word, and
  `interrupt_check(ctx, amount)` the spell being cast as `ctx.skill` and the total of the damage event's losses as
  `amount`. `equipment_slots()` gets no ctx, and `xp_to_level(ctx, level)` gets the level it is asked about as `level`.
- `mitigate` is called for auto-attacks and the `damage(...)` effect primitive, never for the raw `ctx.damage` verb. Its
  result may name any vitals, so a world can send psychic damage to `sanity` or let a `shield` vital soak damage first.
  Each loss is subtracted with no floor.
- `move_cost` spends from each named vital. A transition the actor can't afford (some vital's `current` below its cost)
  fails with "You are too exhausted." (§4.18).
- `regen` adds its result to each vital, up to the vital's maximum; vitals it doesn't name don't change.
- `starting_stats(ctx)`: `ctx.actor` is the new actor. A player has every stat and vital at 0; a mob carries its
  prototype's declared `stats`, `vitals` (as maxima) and `resists`. The result overrides those values. A `current` it
  doesn't give equals the maximum, so a vital that starts empty gives both: `max = vitals(rads = 100), current =
  vitals(rads = 0)`. A `senses` field (channel to a score of 0 or more) merges over the built-in `sight` and `sound` at
  1. It runs before the actor enters the world, so it can randomize with `ctx.rng`.
- For a player, the answers and traits are already set (§4.19): `ctx.actor.choices` is readable, and effective values
  already include trait deltas. The result's `stats` and `max` are base values; the trait deltas apply on top, and "the
  maximum" above is the effective one.
- Players created without choices (no provider, the harness without `choices`, the dry run's plain player) see
  `ctx.actor.choices == {}`.
- A mob runs with its prototype's traits set, so `ctx.actor.max_<vital>` already includes their deltas. A ruleset that
  keeps the prototype's maxima returns `ctx.actor.base.max_<vital>`, as the template does; returning the effective value
  would count the traits twice.
- `condition(ctx)` decides `ctx.actor`'s life state (§5.4): after any change to one of the actor's vitals, and for every
  simulated actor at each world tick, so a rule such as "dead after 60 pulses with `oxygen` below zero" can use
  `below_zero_since`.
- `on_player_death`: `respawn` resolves relative labels against the rules package; `vitals` sets the respawned
  character's current vitals (capped at their maxima); `xp_loss` (an int `>= 0`) is taken from `xp` before the respawn.
- `level_loss` (default 0) is the number of levels the player loses after the respawn. It is allowed only with the
  `leveling` feature, like `xp_loss`. The engine keeps `xp` at 0 or more and `level` at 1 or more and enforces no other
  floor.
- `on_level_up(ctx)`: `ctx.actor` is the actor, with its new `level` already set. The result is `None` or a `struct`
  with any of `stats`, `max` and `current`, built with `stats(...)` and `vitals(...)`. All are deltas and may be
  negative. `stats` and `max` change the base values and are remembered for that level so that losing it takes them back
  (§5.4); `current` is added to the current vitals, which are then capped at the new maxima, and is not remembered.
- `xp_to_level(ctx, level)` is the total XP at which `level` ends for `ctx.actor`, so a world can give each actor its
  own curve influenced by attributes. Note that `ctx.actor.level` may differ from `level`. The answer must depend only
  on the actor and `level`. `xp_to_level(ctx, level + 1)` must be greater than `xp_to_level(ctx, level)` for the same
  actor; a value that is not is a bad return value, and no level is owed past it (§5.4).
- `get_lag` returns `None` for out of reach.
- `advance_lag`, `retreat_lag`, `retreat_fail_lag`, `flee_fail_lag` and `get_lag` return pulses, an int of at least 0; a
  negative value is a bad return value. The lag exports set the action's recovery lag, and 0 means none.
- `equipment_slots` returns lowercase slot names. It is asked once, and again after each reload.

**The dry run.** Rules functions are pure, so each time the rules package loads (at startup, on reload and on deploy,
and in `gups lint`), the engine calls every export it defines against synthetic actors built from the schema: a fresh
actor after `starting_stats`, both as a player and as each mob prototype, and `mitigate` once per declared damage type.
`on_level_up` runs once for each synthetic actor as if it had just reached level 2, and its result is checked but not
applied; `xp_to_level` runs for levels 1 to 50 for each actor and must strictly increase for each. The actors live in a
throwaway world with one room: two players and one mob per loaded mob prototype. Each actor is called against another
(the first player against the second, everyone else against the first player), unarmed. `get_lag` runs once per band,
the three track lag exports see the pair's other actor as `ctx.target` at band `melee`, `flee_fail_lag` sees no target,
and `cast_allowed` and `interrupt_check` get a synthetic skill with `interrupt = {"damage_pct": 10}`. The room has a
north exit back to itself with a closed door. For every channel, `sense_cost` sees that exit, and `can_perceive` and
`perceive_message` are each called once as an event in the actor's own room and once as one carried in through the north
exit at cost 1, with no source room. `door_intensity` is called for each of `open`, `close`, `lock` and `unlock`, with
and without a tag. With a creation provider (§4.19), `starting_stats` also runs for one synthetic player per option of
each choice. Its answers are built in choice order: that option for its own choice, and the first available option for
every other. An option that isn't available on that path, or whose path the provider fails on, is skipped. A mob whose
own package failed to load isn't checked. Only the package named `rules` is dry-run; a test world that names another
rules package (§4.12) isn't. An error, a wrong return type or an undeclared name in a result fails the rules package's
load with the usual diagnostic. A branch the dry run doesn't reach is checked when it first runs.

**Template ruleset.** The template world implements these exports for its default schema (§3.9), serving as the starting
formulas for custom worlds. See [combat.star](../template-world/rules/combat.star),
[perception.star](../template-world/rules/perception.star), and
[manipulation.star](../template-world/rules/manipulation.star) in `template-world/rules/` for the complete reference
formulas.

**Modifier points (v0):** `to_hit`, `damage`, `initiative`, `cast_allowed`, `move_cost`, `regen`. The list grows with
`api_version`. `mitigate` is not a modifier point. Modifiers of `move_cost` and `regen` take and return `vitals(...)`
values; modifier points of a feature that is off can't be named.

**Whose chain:** the attacker's for `to_hit` and `damage`, the caster's for `cast_allowed`, the mover's for `move_cost`
(from the room being left), and the actor's own for `regen` and `initiative`. "Zone" means the package of that actor's
room, and the regions and room are those of that room too.

**Chain order:** base rules result, then zone `package(modifiers=)`, then region modifiers (sorted by region name), then
room modifiers, then the actor's traits (in name order, §4.19), then its affects (sorted by affect id), then its items:
each worn item in `equipment_slots()` order (then any other slots by name) contributes its `item(modifiers=)` and then
the modifiers of its item affects (prototype affects in declaration order, then instance affects by id); then every
other held item's `carried` affects, depth first through containers in item-id order. Items contribute their
`item(modifiers=)` and item affects their `modifiers`; affects contribute the `modifiers` given to `apply_affect`;
traits contribute the Kotlin modifiers of their plugin. Traits have no `cast_allowed` modifiers: the `cast` restriction
refuses casts (§4.19).

Each modifier is a top-level function with the signature `fn(ctx, base) -> value`, or returns `ctx.deny(msg)` (only for
`cast_allowed`):

- A `cast_allowed` chain stops at the first denial.
- A failing modifier, or one that returns the wrong type, leaves the value as it was.

```python
def _halve_melee(ctx, base):
  return base // 2 if ctx.weapon and ctx.weapon.kind == "melee" else base


def _fizzle(ctx, base):
  return ctx.deny("Your magic fizzles into nothing.")


room("antimagic_vault", modifiers={
  "to_hit": _halve_melee,
  "cast_allowed": _fizzle,
})
```

The harness and `@trace` print the full chain, for example `rules.to_hit base=62 → region:null_field ×0.5 → 31 → roll 44
MISS`.

**Encumbrance:** `rules.encumbrance` applies while the carried and worn weight exceeds `rules.carry_limit` (computed
without the current encumbrance). It is recomputed when an actor's custody changes (spawn, move, destroy) and at every
world tick, since stats can change the limit. The actor is told when it starts and when it lifts. The carry-limit check
counts a container's contents.

**Equipment:** Slots come from `rules.equipment_slots()`. Wearing into an occupied slot moves the old item back to the
inventory; a slot family whose slots are all full swaps out the first. `wield` only takes items whose slot is `wield`;
`wear` takes any slotted item; `remove` (`unwield` in the template world) takes off an item in any slot, worn or
wielded. The `wear` restriction (§4.19) runs once the slot is known and before anything moves, so a refused item swaps
nothing out.

### 4.7 Behaviors (built in, implemented in Kotlin)

Built-in mob behaviors are implemented in [Behaviors.kt](../core/src/main/kotlin/gups/core/world/Behaviors.kt).

|                        Behavior                         | Priority |                                                                                      What it does                                                                                      |
|---------------------------------------------------------|----------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `wander(radius=3, chance=0.1)`                          | 10       | Moves randomly within `radius` exits of the home room, counted through open doors. `chance` is the probability per think.                                                              |
| `aggressive(toward="players", unless_level_above=None)` | 60       | Attacks the lowest-id matching target on sight. `combat` feature; `unless_level_above` also needs `leveling`.                                                                          |
| `assist(allies)`                                        | 50       | Attacks an ally's primary target, or else its lowest-id opponent. `combat` feature.                                                                                                    |
| `flee(below=vitals(...))`                               | 100      | Flees, engaged or not, when any listed vital is at or below that percentage of its maximum: `flee(below = vitals(hp = 20))`. `combat` feature.                                         |
| `guard(exit, allies="mobs")`                            | —        | Denies the exit to non-allies with "<Guard> blocks your way." A built-in `before_exit` observer that runs before the scripted ones, only while the guard is simulated and able to act. |
| `follow(target)`                                        | 30       | Takes the first match it sees as leader and follows it within 3 exits; farther, it forgets the leader.                                                                                 |
| `pursue(target)`                                        | 40       | Heads for the nearest simulated match by path length, then lowest id, using `path_to` across the whole world.                                                                          |
| `sentinel()`                                            | —        | Never leaves its room: removes movement and flee proposals.                                                                                                                            |
| `scavenge()`                                            | 5        | Takes the first reachable, carriable ground item that isn't a corpse.                                                                                                                  |

- **Whom they mean:** `players`, `mobs`, `all`, or label patterns with `*`, as a string or a list. A pattern without a
  package (`goblin_*`) matches the label name in any package; `:goblin_*` means this package.
- **Engagement:** behaviors other than `flee` propose nothing while engaged.
- **Failures:** a behavior that throws is logged as an engine diagnostic and proposes nothing.
- Built-in behaviors are data values with value equality, so the version-bump lint (§7.3) can compare them.

Each behavior produces at most one `Proposal(commandLine, priority, source, action)` per think cycle. The `action` fixes
the target the behavior chose; `commandLine` describes it and is what `ctx.proposals[i].command` shows.

### 4.8 Mob Think

```python
def _scout_think(ctx):
  # ctx.proposals: list sorted by priority desc; ctx.proposals[0] is what would run.
  if ctx.self.hp < ctx.self.max_hp // 2 and ctx.allies_near(ctx.self, "far"):
    return ctx.act(ctx.self, "shout Help!")
  return None  # None = accept the top proposal; ctx.act_* = override; "idle" = do nothing
```

- If `think` queued an action for its own mob through `ctx.act*`, the top proposal is not queued as well.
- A failing `think` falls back to the top proposal.

### 4.9 Commands

Command definitions, dispatching lanes, and aliases are implemented in
[Commands.kt](../core/src/main/kotlin/gups/core/world/Commands.kt) and
[KernelCommands.kt](../core/src/main/kotlin/gups/core/world/KernelCommands.kt). Staff tools and administrative commands
are implemented in [Tools.kt](../core/src/main/kotlin/gups/core/world/Tools.kt) and
[AccountTools.kt](../auth/src/main/kotlin/gups/auth/AccountTools.kt).

**Player commands, fast lane:** `say`, `tell`, `gossip`, `shout`, `whisper`, `look`, `score`, `inventory`, `equipment`,
`keyring`, `who`, `help`, `quests`, `stop`, `clear`, `quit`.

Commands of a feature that is off don't exist, and a world can rename any non-`@` engine command (§3.10); this section
uses the default names. Every player-facing message here is the default text of an engine message (§4.18).

- **`tell <name>`** finds an in-world player by name first, otherwise a mob in the room by keyword, which receives
  `on_tell`.
- **`shout`** is a `sound` event (§5.7): every player it reaches, in the room or beyond, hears it through
  `rules.perceive_message`, so a deaf player doesn't. The speaker is told directly.
- **`whisper`** shows bystanders "X whispers something to Y."
- **`keyring`** lists the keys on the keyring: carried items with `kind = "key"` and no other purpose (no `wear` slot,
  `container`, `commands`, `dice`, `stat_deltas`, `sense_deltas`, `equip_affects`, `affects` or `modifiers`), and that
  haven't gained instance affects or tags (a key cursed in play shows in `inventory`). `inventory` leaves them out; item
  commands still find them by keyword.
- **`gossip`** goes to every player, including link-dead ones (who see nothing).
- A `silenced` speaker can't use `say`, `tell`, `gossip`, `shout` or `whisper`: it is told "You can't get the words
  out!", nobody hears anything, and no `on_say` or `on_tell` fires.
- **`quit`** is refused while engaged: "No way! You're fighting for your life!" The link-dead auto-quit ignores combat.
- **`score`** shows the character a summary of its own state: its stats, vitals and resists, its progression with the
  `leveling` feature, and its choices and traits (§4.19). Which lines it prints and how they are laid out is not fixed
  here; the lines are engine messages (§4.18). Clients read the same state as structured data from the GMCP
  `Char.Vitals`, `Char.Status` and `Char.Traits` packages (§4.10).
- **`look`** describes another actor's health by its condition first: "is incapacitated" or "is mortally wounded", and
  otherwise by its lowest vital as a share of its maximum (among vitals with a positive maximum): 100% or more "is in
  excellent condition", 75% "has a few scratches", 50% "is wounded", 25% "is badly hurt", below that "is in awful
  condition". Onlookers see an incapacitation only as "<actor> collapses." and "<actor> stirs.".

**Player commands, tick lane:**

- **Movement:** `north`, `south`, `east`, `west`, `up`, `down` and other exits, `advance`, `retreat` (`track`), `flee`
  (`combat`).
- **Combat:** `kill` (`combat`), `cast` (`skills`), `use`.
- **Incapacitation:** An incapacitated actor (§5.4) can't use tick-lane commands: "You can't do that while
  incapacitated." Its queue is kept.
- **Items:** `get`, `drop`, `give`, `put`, `wear`, `wield`, `remove`.
- **Doors:** `open`, `close`, `lock`, `unlock`.
- **Zone commands:** Zone-defined `commands`.

**Item and combat command details:**

- **Stack display:** `look`, `inventory` and `keyring` show a stack of 2 or more as `"<count> <plural>"` when the
  prototype declares a `plural` and the item has no name override, and as `"<name> (<count>)"` otherwise.
- **Item specifiers:** `get`, `drop`, `give` and `put` take a `<what>` specifier instead of a single word: a
  `<keyword>`, a `<ordinal>.<keyword>`, a `<count> <keyword>`, a `<count> <ordinal>.<keyword>`, `all`, or `all
  <keyword>`. A keyword may be a phrase, and every word of it must match the start of one of the item's keywords, a
  keyword's words, or its plural words; articles are ignored unless the phrase is only articles. `get 2.coin` picks the
  second match, as the other item commands do. Bare `all` skips items tagged `no_all` (such as `//engine:corpse`) and
  keyring-only keys; an explicit keyword ignores both exclusions.
- **`get <what> [[from] <container>]`:** the `from` preposition is optional. Without it, `get <item> <container>` takes
  from the container when the last word names a container that holds a match, and `get all <container>` loots it;
  otherwise the whole phrase is a ground item. It uses the container's ground band for `get_lag`; a container carried by
  the actor counts as `MELEE`, and a `get` from one is not capped by the carry limit. The container must be open. A
  requested count is capped to what the source holds and to what the actor can carry ("You can't carry that much." when
  the carry limit caps it). Each source stack runs `before_get`, the move, and `on_get` once; a stack that `before_get`
  destroys or moves away from the source is skipped.
- **`drop <what>`:** drops one item, a count, or all matching items from the inventory to the ground. A count is capped
  to what is carried. Each source stack runs `before_drop`, the move, and `on_drop` once; a stack that `before_drop`
  destroys or moves out of the inventory is skipped. With nothing matching, it says "You aren't carrying that."
- **`kill`:** engages, switches the attacker's primary target, costs no lag, and does not swing immediately: the first
  swing comes at the next combat round. `kill` on the current primary target is a no-op ("You're already fighting
  them!").
- **`give <what> [to] <actor>`:** gives one item, a count, or all matching items from the inventory. A count is capped
  to what is there and to what the receiver can carry ("<Target> can't carry that much." when capped). Fires `on_give`
  once per stack moved.
- **`put <what> [in|into] <container>`:** the container is carried, or on the ground within `get_lag` reach, and open.
  Moves one item, a count, or all matching items (never the container itself). A count is capped to the container's free
  item capacity and `max_weight`.
- **Containers:** `open`, `close`, `lock` and `unlock` take a container when the word isn't a door direction here.
- **Keys:** locking or unlocking a door or container needs its key carried (keyring included) or equipped.
- **`use <item> [target]`:** the item is carried or worn and must have an `on_use` hook ("You can't use that."
  otherwise). `before_use` can veto. The target is an actor in the room, else an item. The engine never uses an item up;
  `on_use` destroys it if it should be.
- **Stacks:** `get`, `drop`, `give` and `put` merge a stackable item into a stack of the same prototype in the same
  place with identical attributes and decay deadline, and no timers or name override on either (§3.8 inv. 8).

**Global commands:** Privileged packages may register global commands: `global_command("quests", fn, lane="fast")`. This
is how `//lib:quest.star` provides `quests`, which lists active and finished quests.

**Reserved exit names:** an exit's direction or custom name may not be the name or alias of an engine command the world
has (after `rename`, §3.10), or the name of any global command. Such an exit fails its package's load with a diagnostic
saying the exit name is a reserved keyword.

**`//lib:quest.star`:** `quest.define(id, title, steps)` returns a quest description. `start`, `advance(...,
from_step=None)`, `complete`, `step`, `is_active` and `is_done` take `(ctx, actor, quest)`. Progress is a `quests` dict
in the lib's own `lib` namespace on the player, so only the library writes it (§3.7).

**Roles and gating:** A command can require a role. To players without it the command doesn't exist: they get "Huh?",
and `help` leaves it out. This applies to engine `@` tools and plugin commands alike. `@` commands registered from
outside `core` are tools, and an `@` prefix only matches other `@` commands.

**Help and categories:**

- **Help index (`help`):** Lists all commands usable by the actor, grouped by category and sorted alphabetically within
  each category. Built-in categories appear in standard order: `Communication`, `Movement`, `Information`, `Items`,
  `Combat`, `Skills`, and `Game`. Custom categories defined by packages or plugins appear between `Game` and `Special`,
  sorted by explicit sort weight if supplied or alphabetically by title by default. `Special` contains all commands
  requiring a role above `Role.PLAYER` or whose name starts with `@` (regardless of execution lane), and is pinned at
  the end. Empty categories and categories with no commands allowed for the actor are omitted. Movement lists direction
  commands and notes `", and any exit name"`.
- **Detailed command help (`help <command>`):** Resolving a command by its canonical name, an alias, or a prefix
  shorthand character displays the command's canonical name, all configured aliases, and its detailed help text
  (declared on the command definition itself).
- **Category help (`help <category>`):** If `<target>` does not resolve to a command, it is matched against category
  names and titles. If the category defines category-level help text and has at least one command usable by the player,
  it displays the category title, its usable commands, and its category help text. A category without category-level
  help does not match.
- **Category descriptors:** Categories may be explicitly declared with an identifier, display title, optional sort
  weight, and optional category-level help. A command may also reference an undeclared category name, which defaults to
  an unweighted category titled by its identifier.

**Builder commands** (scoped to the builder's own packages; `debug` entities only):

|                               Command                                |                                                                                                                                                                                                                                                                                       Purpose                                                                                                                                                                                                                                                                                        |
|----------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `@goto <label\|player>`                                              | Move to a room of the builder's packages (index 0), or to any player (their track index), through `teleport`, so hooks fire. A player's name wins over a label. Going to a player can be taken away per account (Restrictions, below).                                                                                                                                                                                                                                                                                                                                               |
| `@stat <target>`                                                     | Show an entity's full state. The target is a keyword (actors first, then carried and ground items), `here` or `#id`; with the `skills` feature, a target no entity matches may name a skill by label or name, which shows its kind and `tags`. For a player this includes base and effective stats, maxima and resists, every saved answer and granted trait (unknown ones marked `(unknown)`), and the effective traits with where each comes from (`<choice>=<option>`, `prototype` or `granted`); mobs show their traits the same way. Rooms, items and skills show their `tags`. |
| `@spawn <label> [n]`                                                 | Spawn 1 to 10 mobs or items (flagged `debug`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `@trace [all\|zone [pkg]\|entity <target>\|hook <name>\|rules\|off]` | Stream hook execution (§5.13).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `@errors [package]`                                                  | Show the last 10 diagnostics.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `@where`                                                             | Show your track index and bands numerically (`track` feature).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `@eval <expr>`                                                       | Starlark REPL; `--dev` only.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |

- **Ownership:** a builder owns the packages in their scope. Rooms belong to their package, mobs and items to their
  prototype's. Players and engine items (corpses) belong to the package of the room they are in. Every builder command
  is refused outside the builder's packages with "That isn't one of your packages."; `@where` checks the room the
  builder stands in. Instructors and admins see and act on everything, as does `--dev`'s local instructor.
- **Labels:** `//pkg:name`, or `:name` for the package of the builder's room.
- **`@stat`** shows identity and version, location, vitals, effective and base stats, resists, skills, affects with
  seconds left, what the actor is doing, carried and worn items, timers, attributes grouped by namespace, and simulation
  state with reasons. Vitals show `cur/max` and `belowZeroSince` when set, and the condition is shown. Mobs also show
  their debug owner; players their account, role and scope.
- **`@spawn`:** builders may spawn both mobs and items of their own packages ("`debug` entities only" means every
  spawned entity is flagged `debug`). Mobs appear at the builder's index; items go into the builder's inventory (a
  stackable item as one stack). Debug entities have no audit trail and are never saved, so debug spawns can't leak into
  the real economy:
  - A debug mob despawns at the first world tick after its spawner is gone, or after 30 minutes.
  - Killing a debug mob awards no XP. Everything it drops (inventory, loot, corpse) is `debug` too.

**Instructor commands** (global):

|                                                        Command                                                        |                                                                                                       Purpose                                                                                                       |
|-----------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `@reload <package...>`                                                                                                | Reload loaded packages (a manual auto-reload). Refused in `--prod`, with a pointer to `@deploy` (§1.4).                                                                                                             |
| `@deploy [commit]`                                                                                                    | Deploy the fetched tip of `deploy_branch`, or the given commit, through the deploy pipeline (§5.10), reporting progress in game.                                                                                    |
| `@quarantine <pkg>` / `@unquarantine <pkg>`                                                                           | Quarantine or release a loaded package; each takes a routine checkpoint so the state persists.                                                                                                                      |
| `@bubble`                                                                                                             | Active rooms per package, every anchor with its reasons and room, entities with active reasons in dormant rooms, and dormant-room counts per package.                                                               |
| `@stats`                                                                                                              | p50 and p99 of the last minute's pulse durations, its overruns, input lines waiting, active rooms, sessions per transport, the writer's backlog in batches, and the package with the most script steps this minute. |
| `@who`                                                                                                                | Each player's role, transport and address (or link-dead time) and idle time.                                                                                                                                        |
| `@snapshot`                                                                                                           | A routine checkpoint, a wait for the writer, then a `manual` backup.                                                                                                                                                |
| `@shutdown [seconds] [reason]`                                                                                        | A first word that isn't a number starts the reason. Warns at once, then at 60, 30, 10 and 5 seconds left.                                                                                                           |
| `@audit item <id>`                                                                                                    | The item's committed `item_events` with UTC wall times, plus where it is now if it is in the world. Changes not yet checkpointed don't show.                                                                        |
| `@role <account> [<role> [packages] \| reset \| revoke \| disable [reason] \| enable]`                                | Show or set an account's role, or disable it (below).                                                                                                                                                               |
| `@invite <role>`                                                                                                      | Create an invite code.                                                                                                                                                                                              |
| `@restrict <account> <capability> [length] [reason]`, `@unrestrict <account> <capability>`, `@restrictions [account]` | Per-account restrictions (below).                                                                                                                                                                                   |

**`@role`:**

- `@role <account>` shows the role, GitHub handle, scopes, and password status. For a disabled account, it also shows
  who disabled it, when, and any recorded reason (or "disabled by the roster").
- `@role <account> builder <packages>` sets the manual scope (§3.5). Packages are comma- or space-separated and must be
  loaded. Giving a role without packages preserves the existing manual scope.
- Instructors may set `player`, `builder`, and `instructor`. Only admins may assign or reset admins or issue admin
  invites. Accounts cannot modify their own role.
- Changes apply immediately to active characters in the world (role, scope, and idle exemptions).
- `@role <account> reset` clears the password, revokes active web tokens, and generates a 7-day setup code.
- `@role <account> revoke` deletes the account's active web tokens.
- `@role <account> disable [reason]` disables the account (§3.5), immediately quitting any active characters (including
  link-dead characters and those in combat) and terminating pending logins. The player isn't told the reason. Disabling
  or enabling one's own account, or an admin's account without admin rights, is refused. Disabling an already-disabled
  account, or enabling an already-enabled one, changes nothing and says so. Characters without an account (`--dev`)
  cannot disable accounts.
- `@role <account> enable` re-enables the account.
- Staff command implementations and role enforcement are detailed in
  [AccountTools.kt](../auth/src/main/kotlin/gups/auth/AccountTools.kt) and
  [Tools.kt](../core/src/main/kotlin/gups/core/world/Tools.kt).

**Restrictions:** instructors can take a capability away from one account, for a while or for good, without changing its
role.

|                       Command                        |                                  Purpose                                   |
|------------------------------------------------------|----------------------------------------------------------------------------|
| `@restrict <account> <capability> [length] [reason]` | Adds a restriction. Without a length, the next step of the ladder applies. |
| `@unrestrict <account> <capability>`                 | Lifts the active restriction, permanent or not. The history stays.         |
| `@restrictions [account]`                            | Restrictions in effect, or one account's full history.                     |

- **Capabilities:** `goto_player`, `goto`, `spawn`, `stat`, `where`, `trace`, `errors` and `gossip`; the list grows as
  commands are added. Each builder-command capability guards that command, `gossip` guards the `gossip` command, and
  `goto_player` guards going to a player with `@goto`.
- **Scope:** instructors and admins can't be restricted.
- **Effect:** the command still exists for a restricted player, unlike a missing role.
- **Lengths:** `<n>m`, `<n>h`, `<n>d`, `<n>w` or `permanent`. A third word that isn't a length starts the reason.
  Without a length, the next step of the server configuration's `restriction_ladder` applies (default `["1d", "7d",
  "permanent"]`, so the first offence is a day and the third is permanent until an instructor lifts it). Every earlier
  restriction of the same capability on the account counts toward the ladder, whatever its state (lifted and expired
  included); past the end, the last step repeats. The ladder is checked at startup.
- **Checking:** the world keeps every restriction in memory (loaded at startup, updated by the commands) and checks them
  against the wall clock, like session tokens, so they run out while the server is down. They are stored in the
  `restrictions` table (§3.6) through the checkpoint writer, like other account changes.
- **Message:** "You can't do that right now.", plus "That changes <UTC time>." for a timed restriction. The restricted
  player isn't told when one is set.
- **`@restrictions`** without an account lists those in effect; with one, the full history: state, who set it and when,
  the reason, and who lifted it. "Set by" is the instructor's account, or none for `--dev` characters without one.

### 4.10 Network Protocol

The engine's network listeners and connection pipelines are implemented in `:net` using Netty and Ktor. Connection
lifecycle, telnet option negotiation, and line framing are handled in
[Telnet.kt](../net/src/main/kotlin/gups/net/Telnet.kt). TLS context loading, self-signed certificate generation, and
hourly certificate reloading are implemented in [Tls.kt](../net/src/main/kotlin/gups/net/Tls.kt). Terminal color depth
negotiation and markup conversion are in [Ansi.kt](../net/src/main/kotlin/gups/net/Ansi.kt). GMCP payload generation,
caching, and change detection are implemented in [Gmcp.kt](../core/src/main/kotlin/gups/core/world/Gmcp.kt). HTTP
endpoints, static assets, cookies, and WebSocket framing are handled by
[WebServer.kt](../net/src/main/kotlin/gups/net/WebServer.kt).

- **Listeners:**
  - Raw telnet on `:4000` (`--dev`, or explicitly enabled).
  - Telnet over TLS on `:4443`.
  - WebSocket over TLS (`wss://host/ws`), with the bundled web client served from `https://host/`.
- **Netty listeners:** all listeners run on Netty 4.2 with the NIO transport, one boss thread and Netty's default worker
  count per listener. Raw telnet and telnet over TLS build the same pipeline; TLS adds an `SslHandler` first.
  `TelnetConnection` (option negotiation, line framing, buffering) sits behind a byte-in, byte-out interface, so only
  the socket layer knows about Netty. Over TLS a session starts, and the world hears `Connect`, only once the handshake
  succeeds; its transport is `tls`, raw telnet's is `telnet`. Each sink call is written and flushed at once, and
  unwritten bytes count toward the output limit. `Disconnect` is posted whenever the channel closes (a no-op after a
  world-side close). Session ids come from one counter shared by all listeners.
- **TLS:** a PEM certificate chain and a PEM PKCS#8 key (certbot's `fullchain.pem` and `privkey.pem`), loaded with
  Netty's JDK provider. TLS 1.3 and 1.2 only, with the JDK's default cipher suites. Every hour the server compares both
  files' modification times and rebuilds the context if either changed, so an external ACME client such as certbot can
  renew them without a restart (the engine doesn't do ACME itself); a pair that fails to load is logged and the old
  context stays. Open connections keep their certificate. `--self-signed` makes an EC P-256 certificate signed with
  SHA-256, valid for 30 days, for `localhost`, `127.0.0.1` and the bind host when it is a specific one, in a fresh
  temporary directory.
- **Telnet options:** NAWS, TTYPE/MTTS, GMCP, EOR (falls back to GA), and CHARSET (UTF-8 only). No MSDP or MXP.
  - On connect the server sends DO TTYPE, DO NAWS, WILL EOR, WILL GMCP and WILL CHARSET. Requests for anything else are
    refused once. Nothing is re-sent for an option whose state didn't change.
  - TTYPE is asked up to three times, or until an answer repeats. NAWS is stored.
  - CHARSET: the server requests `UTF-8` and accepts a client's request only if it lists UTF-8. Output is UTF-8
    regardless.
  - GA is sent only to clients that have sent at least one telnet command, so plain `nc` sessions don't see a GA byte
    after every prompt.
  - **Echo:** password prompts send `IAC WILL ECHO` / `IAC WONT ECHO`, only to clients that have sent a telnet command,
    and only when the state changes. `DO ECHO` is accepted silently while input is hidden and refused once otherwise;
    `DONT ECHO` is never answered, so the exchange can't loop. After each hidden line the server sends the newline the
    client didn't echo.
- **Line framing:** a line ends at CR, LF, CR LF or CR NUL. Backspace edits the line; other control characters are
  dropped, and invalid UTF-8 becomes U+FFFD.
- **Prompts:** after each burst of output, a playing session gets the world's `prompt` (§3.9) with its vitals filled in,
  for example `<20hp 20m 100mv> `. Telnet ends it with IAC EOR when the client agreed to EOR, else IAC GA.
- **Color markup:** text may contain `{red}` and the other base colors, `{bright_red}` etc., `{bold}`, `{underline}`,
  `{/}` (reset) and `{x0}`–`{x255}`. `{{` is a literal brace; unknown tags stay as typed.
  - 16-color clients get xterm colors downgraded to the nearest base color. Clients without color get the tags stripped.
    Output that leaves a color on ends with a reset.
  - Color depth: MTTS bits decide (8 = 256 colors, 1 = ANSI). Without MTTS, terminal types decide: 256 for `*256COLOR*`,
    `*TRUECOLOR*`, Mudlet and TinTin; 16 for ANSI, XTERM, VT100 and the like. Otherwise there is no color.
  - Engine messages don't use color yet.
- **GMCP packages (v0):** the client sends `Core.Hello` and `Core.Supports.Set`/`Add`/ `Remove`; GMCP input other than
  `Core.*` is ignored in v0. The server sends these, with lowercase field names:

  |       Package       |                                                                                                                              Payload                                                                                                                              |                                               Sent                                               |
  |---------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
  | `Char.Schema`       | `{"stats": [{"name", "label"}], "vitals": [{"name", "label"}], "damage_types": [names], "features": [names]}`                                                                                                                                                     | Once a session takes a character, and when the client starts supporting it                       |
  | `Char.Vitals`       | `{"<vital>", "max<vital>"}` for every declared vital, for example `{"hp", "maxhp", "mana", "maxmana", "move", "maxmove"}`                                                                                                                                         | When any field changed                                                                           |
  | `Char.Status`       | `{"name", "level", "xp", "xp_next", "wait", "casting": {"skill", "progress"} or null, "initiative" or null, "fighting": [names]}`; `level`, `xp` and `xp_next` only with `leveling`, `casting` only with `skills`, `initiative` and `fighting` only with `combat` | When changed                                                                                     |
  | `Char.Affects`      | `[{"name", "remaining", "flags"}]`                                                                                                                                                                                                                                | When the set or a whole second of time left changed                                              |
  | `Room.Info`         | `{"label", "name", "exits": {dir: "open"/"closed"/"locked"/null}, "regions"}`                                                                                                                                                                                     | On entering a room, and when a door here changes                                                 |
  | `Room.Bands`        | `{"melee": [...], "near": [...], "far": [...]}`, each entry `{"id", "name", "kind": "actor"/"item"}`; `track` feature only                                                                                                                                        | When anything in the room moves, arrives or leaves                                               |
  | `Comm.Channel.Text` | `{"channel": "say"/"tell"/"whisper"/"shout"/"gossip", "talker", "text"}`                                                                                                                                                                                          | With each message                                                                                |
  | `Gups.Trace`        | `{"line"}`                                                                                                                                                                                                                                                        | Builders with `@trace` on, instead of the `[trace]` text lines                                   |
  | `Char.Creation`     | `{"name", "step": "choice"/"confirm", "choice": {"id", "title", "prompt", "options": [{"id", "label", "summary", "help"}]} or null, "answers": [{"choice", "title", "option", "label"}]}`, or `null`                                                              | With each creation prompt (§5.12), before the session has a character; `null` when the steps end |
  | `Char.Traits`       | `{"choices": [{"choice", "title", "option", "label"}], "traits": [{"name", "label", "doc"}]}`                                                                                                                                                                     | When changed; only in worlds with a creation provider or a trait                                 |

  `Room.Bands` entries carry entity ids, only ever for the viewer's own room, so clients can key their band view and
  tell identical mobs apart. Plugins may add packages (§4.15).

- **GMCP values:** `core` builds payloads as plain values (`String`, `Long`, `Boolean`, `List`, `Map<String, *>`,
  `null`), since it has no kotlinx.serialization dependency; `net` encodes them as JSON. The seam is
  `OutputSink.gmcp(pkg: String, data: Any?)`.

- **GMCP delivery:**

  - A package is sent only when GMCP is on and `Core.Supports` listed it, or a module above it (`Char` covers
    `Char.Vitals`), compared case-insensitively. Versions in the entries are ignored. `Set` replaces the list,
    `Add`/`Remove` change it, and `DONT GMCP` turns everything off. `Core.Hello`'s client and version are kept.
  - A `Core.*` message with malformed JSON counts as a protocol error (§6.4); other packages are ignored.
  - GMCP waits in the session with its text and goes out after the text and before the prompt. A flush with only GMCP
    sends no prompt.
  - Every phase-7 flush recomputes the state packages (`Char.Vitals`, `Char.Status`, `Char.Affects`, `Char.Traits`,
    `Room.Info`, `Room.Bands`) and sends those whose whole payload changed. The cache resets when a session takes a
    character, so a reconnect gets everything again. Events (`Comm.Channel.Text`, `Gups.Trace`, `Char.Creation`) are
    never cached. `Char.Creation` is the one package sent to a session without a character.
  - IAC bytes in a payload are doubled.
- **GMCP payloads:**
  - `Char.Schema` is static for the life of the server (§3.9), in declaration order. Its `features` are the enabled
    features (all four ids until a world can turn features off). Clients draw vitals bars and stat labels from it
    instead of hardcoding names; the bundled web client does.
  - `Char.Vitals` keys are the vital names and `max` plus the name, in schema order.
  - `Char.Status`: `wait` is the recovery left in pulses; `xp_next` is the total XP for the next level
    (`rules.xp_to_level`); `casting.skill` is the skill's name, or `action` for a warmup that isn't a cast, with
    `progress` 0–100 through the warmup; `fighting` lists names; plus the character's own initiative.
  - `Char.Affects`: `remaining` is whole seconds; `flags` are sorted.
  - `Room.Info`: `exits` maps every exit's direction to its door state, or null when it has no door; `regions` are
    sorted.
  - `Room.Bands`: actors (the viewer excluded) and ground items of the viewer's room, by band from the viewer's index:
    actors first, then items, each in ascending id.
  - `Comm.Channel.Text`: `say` goes to everyone in the room, the speaker included. `tell` and `whisper` go to both ends.
    `shout` goes to the speaker and to each player whom `rules.perceive_message` (§5.7) lets hear it, in any room, and
    `gossip` to every player. `text` is what was said, without the framing, except in a `shout` heard by someone else,
    where it is the text `perceive_message` returned for them.
  - `Gups.Trace`: `{"line"}` replaces the `[trace]` line, the per-pulse overflow summary included.
  - `Char.Creation` is an event, never cached. `name` is the character's name; `options` are the available ones, in
    order; `help` is `""` when the option has none; `answers` are those given so far (all of them at `confirm`, where
    `choice` is null). It is sent right after the prompt's text, so a client that ignores it still has the text. `null`
    is sent once when the steps end, whether the character entered the world or the player cancelled. Clients answer
    with ordinary input lines (an option id, `back`, `cancel`, `yes`), so nothing new is read from them.
  - `Char.Traits` is a state package: `choices` lists the known answers in choice order, `traits` the effective traits
    in name order. It is recomputed in every phase-7 flush, like `Char.Status`.
- **WebSocket framing:** One JSON message per frame: `{"t":"text","d":"..."}` |
  `{"t":"gmcp","p":"Char.Vitals","d":{...}}` | `{"t":"input","d":"..."}`.
  - Also from the server: `prompt`, `hide` (true or false, for password prompts), and `error` before a close. Also from
    the client: `auth`, and `gmcp` (which only reads `Core.Supports.*`, as over telnet).
  - `text` frames carry the color markup unrendered; the client renders it.
  - **Authentication:** the session cookie, or else a first `{"t":"auth","d":token}` frame within 30 seconds. Otherwise
    the server sends `{"t":"error","d":"Please log in first; your session may have expired."}` and closes with code
    1008\. A later `auth` frame is ignored.
  - A socket is a session with transport `ws` and the token's account, so the login greets it and goes straight to the
    character menu.
  - Frames are at most 64 KiB. Pings every 30 seconds, with a 60-second timeout. Five malformed frames end the socket.
  - Every minute each live socket's token is checked again; an expired or revoked one gets `{"t":"error","d":"Your
    session has ended. Please log in again."}` and is closed. Logout closes that token's sockets at once. The character
    goes link-dead as for any dropped link.
- **HTTPS:** Ktor's Netty engine with a plain connector and the same `SslHandler` and certificate handling as telnet
  over TLS. Because Ktor sees plain HTTP behind it, the session cookie is written by hand: `gups_session=<token>;
  Path=/; Max-Age=<seconds left>; HttpOnly; Secure; SameSite=Strict`; logout clears it with `Max-Age=0`. Without a
  certificate the server speaks plain HTTP, which only tests use.

  |               Endpoint                |                                                         Purpose                                                          |
  |---------------------------------------|--------------------------------------------------------------------------------------------------------------------------|
  | `GET /`                               | The bundled client.                                                                                                      |
  | `POST /api/login`                     | `{"login", "password"}` returns `{"token", "expires_at"}` and sets the session cookie. Rate-limited like telnet (§5.12). |
  | `POST /api/logout`                    | Revokes the token.                                                                                                       |
  | `POST /api/code`, `POST /api/signup`  | Web sign-up with a one-time code (below).                                                                                |
  | `GET /ws`                             | WebSocket upgrade (framing above).                                                                                       |
  | `GET /docs`, `/docs/`, `/docs/<file>` | The API reference, no login needed (§4.16).                                                                              |

  - Errors answer `{"error": message}`: 401 for a wrong login or password, 429 while locked out, 403 for a disabled
    account, 400 for a malformed body.
  - After authentication a socket starts at character selection, using the same login state machine as telnet, so the
    two flows can't drift apart.
  - `POST /api/code` (`{"code"}`) answers `{"login": "ann"}` for a setup code (the account it sets up), `{"login":
    null}` for an invite, or 404.
  - `POST /api/signup` (`{"code", "login", "password"}`) redeems the code exactly as the telnet `new` flow does: a setup
    code sets its account's password and ignores `login`; an invite creates `login` with the invite's role. On success
    it answers like `/api/login`. 400 for a bad login or weak password, 409 for a taken login, 404 for a code that is
    unknown, used or expired. A bad code counts against the address, and a locked-out address gets 429 without its code
    being looked at. There is never a sign-up without a code, even with `open_signup`.
  - **Assets:** `index.html` is served at `/` with `no-cache`; every other file of the client is served under
    `/assets/<hash>/`, cached for a year as immutable. `<hash>` is the first 16 hex digits of a SHA-256 over all the
    files' names and contents, so any change changes every URL while relative imports keep working. `index.html` refers
    to `%ASSETS%`; the module lists its files in `files.txt`.
- **Web client:** plain ES modules (`app.js`, `markup.js`, `panels.js`) and `style.css`, documented with JSDoc, with no
  framework, no npm packages and no build step. There is no Node anywhere in the build or tests: checking TypeScript
  would need a JavaScript runtime, which the engine won't depend on. It targets current Chrome, Firefox and Safari.
  - It shows an output pane with the color markup rendered as HTML, an input line with history, vitals bars and an
    affects list from GMCP, and a band view (`Room.Bands`) of who is in melee, near and far.
  - On load it opens the socket, so a valid cookie goes straight to the game; an `error` frame shows the login form.
  - It echoes commands locally, keeps 100 lines of history (up and down arrows), and switches the input to a password
    field on `hide`. It keeps at most 2,000 lines of output.
  - It asks for the GMCP packages `Char`, `Room`, `Comm.Channel` and `Gups`.
  - A sign-up form takes a one-time code, checks it, then asks for a login (unless the code names one) and a password
    twice, compared in the browser before anything is sent.
  - **Creation panel:** while `Char.Creation` isn't `null`, a panel over the output pane shows the step: the prompt and
    one button per option (label, summary, and the `help` text expandable under it), with Back and Cancel; at `confirm`,
    the answers with Create, Back and Cancel. A button sends the same line a player would type (the option id, `back`,
    `cancel` or `yes`), so the text flow stays the one source of truth and typing still works. `null` closes the panel.
    The client asks for `Char` already, so it gets the package.
  - It shows the character's choices and traits from `Char.Traits` in a small panel, with each trait's `doc` as its
    tooltip.
- **Limits:**
  - Input line ≤ 512 bytes (UTF-8); the rest is dropped and the client is told "Your line was longer than 512 bytes and
    was cut short."
  - Per-session output buffer ≤ 256 KB (over WebSocket, counted in queued JSON characters). A client that exceeds it is
    disconnected as "too slow", and the character goes link-dead.

### 4.11 Kotlin Seams

The engine decouples subsystems through explicit Kotlin interfaces and data contracts:

|                          Seam                           |                                                                          Responsibility                                                                           |                                  Source                                  |
|---------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------|
| `ClientInputEvent` / `WorldControlEvent` / `WorldInbox` | Queues player line input and delivers asynchronous world control events (reload, deploy, connect, disconnect, shutdown, tasks).                                   | [Seams.kt](../core/src/main/kotlin/gups/core/Seams.kt)                   |
| `ScriptHost`                                            | Executes hook functions, evaluates timer/affect callbacks, checks package quarantine, dispatches modifier chains, and runs package reloads and schema migrations. | [Hooks.kt](../core/src/main/kotlin/gups/core/world/Hooks.kt)             |
| `RulesEngine`                                           | Evaluates combat math, vitals mitigation, movement costs, regen, leveling calculations, perception propagation, and modifier points via Starlark rules exports.   | [RulesEngine.kt](../core/src/main/kotlin/gups/core/rules/RulesEngine.kt) |
| `Persistence`                                           | Coordinates non-blocking routine and immediate database checkpoints, entity delta capture, character restoration, diagnostic logging, and account store access.   | [Persistence.kt](../core/src/main/kotlin/gups/core/world/Persistence.kt) |
| `OutputSink`                                            | Transmits raw text lines, prompt updates, password echo toggling, and GMCP payloads to network sessions.                                                          | [Seams.kt](../core/src/main/kotlin/gups/core/Seams.kt)                   |
| `LoginHandler`                                          | Drives pre-game authentication, account sign-up, password verification, character selection, and creation state machines for connected sessions.                  | [Login.kt](../core/src/main/kotlin/gups/core/world/Login.kt)             |
| `WebAccounts`                                           | Bridges web HTTP authentication, setup/invite code validation, token session lifecycle, and web credential management to the account store.                       | [Accounts.kt](../core/src/main/kotlin/gups/core/Accounts.kt)             |
| `CreationProvider` / `Trait` / `Restriction`            | Defines player character creation steps, permanent stat/vital trait modifiers, and plugin restriction checks (`wear`, `enter`, `cast`, `learn`).                  | [Creation.kt](../core/src/main/kotlin/gups/core/model/Creation.kt)       |

The plugin seams (`GupsPlugin`, `PluginRegistry`, the `PluginWorld` facade, and the plugin-agnostic hooks in `core` and
`script` they fill in) are in §4.15.

- **The creation flow** is one class in `core` (a step machine over a session) that both `AccountLogin` and the `--dev`
  login drive, so the two can't drift apart. It owns the steps, the answer matching, `back`/`cancel`, the confirmation
  and `Char.Creation`.
- **Changes** go through one `World` function that checks, sets, recomputes and re-checks worn items (§4.19), used by
  the facade, the harness and the missing-choice flow.
- **Loading:** `loadPackages` is not on `ScriptHost`. The script loader returns a `LoadResult`, and the script host is
  built from it before the `World`. Reloads go through `ScriptHost.reload`.
- **Starting:** building a `World` registers its rooms and spawn rules but places no mobs. `World.start()` brings it to
  life once everything that shapes its actors is in place, in this order: construct, install the plugins
  (`WorldPluginWiring.install`, so traits, restrictions and the creation provider exist), install the script host
  (global commands and the rules' world), restore any saved state, then `start()`. A fresh world fills its rooms' spawns
  there, so every initial mob is built exactly as a later respawn is: its prototype traits are set before
  `rules.starting_stats` runs, and `on_spawn` runs with the scripts installed. Then `on_load` fires on every entity,
  ascending id (§4.4), and the bubble settles. Every place that builds a world (the server, the harness, the fuzz
  walker, tests) follows this order; anything a later plugin feature adds that shapes spawned actors is installed before
  `start()`, never patched onto them afterwards. A world rebuilt only to inspect or replay saved state (the migration
  check, a repro) has already started and doesn't call it.
- **The world:** `World.login: LoginHandler?` handles sessions without a character. With no handler, input from such a
  session gets "You are not playing a character." and the world closes and disconnects the session (§5.2).
  `World.onDiagnostic` lets the server log diagnostics as they happen. `World.later` and `WorldControlEvent.Task` run
  slow work off the world thread (§2.5).
- **Login outcomes:** `AuthResult(session, outcome)` carries the login handler's own continuation; the world passes it
  to `LoginHandler.completed`, then flushes the session. A result for a session that has closed is dropped.
  `LoginHandler.pulse` runs each pulse, before the idle checks, for prompt timeouts.
- **Saved worlds:** a saved world starts from a `Boot`: the saved pulse, the id counters, the saved room ids, and no
  fresh spawns. A restore API rebuilds entities with their saved ids. `World.requestImmediate(cause)` asks for an
  immediate checkpoint (§5.11).
- **Actions:** the tick lane's seam is the `Action` interface (`validate`, `veto`, `begin`, `resolve`) (§5.3).
- **Strict mode:** `WorldConfig(strict = true)` rethrows engine exceptions instead of logging them and aborting the
  action. Tests and the harness use it with the invariant check after every pulse (§6.5). `WorldConfig(bubble = true)`
  keeps a reality bubble (§5.9); `WorldConfig(production = true)` marks `--prod`.

### 4.12 Test Harness API (Starlark)

```python
def test_guard_blocks_without_pass(t):
  w = t.world(seed=42, packages=["//zones/goblin_caves"])
  p = w.spawn_player("tester", at=":entrance", level=3)
  out = p.do("north")
  t.contains(out, "The guard steps in front of you.")
  t.eq(p.room, ":entrance")


def test_scout_flees_when_hurt(t):
  w = t.world(seed=7, packages=["//zones/goblin_caves"])
  g = w.spawn("//zones/goblin_caves:goblin_scout", at=":tunnel")
  w.damage(g, "hp", pct=85)
  w.run(pulses=8)
  t.ne(g.room, ":tunnel")
```

`load` is a reserved word in Starlark, so the package list is `packages`.

`spawn_player` takes its numbers from `rules.starting_stats` and lets `vitals` and `stats` (the constructors) override
them: a named vital's maximum and current are both set to the given value, and a named stat is replaced. The overrides
are base values; trait deltas still apply on top. `w.damage` lowers the named vital by `amount` or by `pct` of its
maximum, with `type` defaulting to the unarmed damage type.

**Choices and traits in tests** (§4.19), with the world's plugins loaded as for `gups test`:

- `spawn_player(..., choices = {"race": "elf", "class": "mage"})` sets the answers before `starting_stats` runs. They
  are checked as at creation (known ids, availability in order, the provider's validation), and a refusal fails the test
  with its message. Missing choices are allowed, so a test can make an incomplete character; leaving `choices` out makes
  a player with none.
- `w.set_choices(player, {"class": "warrior"})` makes a change exactly as a plugin's `setChoices` does (`None` removes
  an answer) and returns the refusal message or `None`.
- `w.grant_trait(actor, name)` and `w.remove_trait(actor, name)` do the same for granted traits, on players and mobs. A
  mob from `w.spawn` carries its prototype's traits.
- Player handles have `choices`, `traits` and `has_trait` as view fields.

|      Object       |                                                                                                                                                                                                                        API                                                                                                                                                                                                                         |
|-------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `t`               | `world(seed, packages=<this package>, rules=None, bubble=False)`, `eq`, `ne`, `true`, `false`, `contains`, `not_contains`, `fail`                                                                                                                                                                                                                                                                                                                  |
| `w`               | `spawn_player(name, at, level, vitals, stats, skills, index, choices)`, `spawn`, `run(pulses)`, `damage(target, vital, amount=None, pct=None, type)`, `set_attr`, `get_attr`, `set_choices(player, changes)`, `grant_trait(actor, name)`, `remove_trait(actor, name)`, `award_xp(actor, amount)`, `take_xp(actor, amount)`, `level_up(actor)`, `level_down(actor, count=1)`, `room(label)`, `trace(on)`, `diagnostics()`, `quarantined()`, `pulse` |
| player handle `p` | `do(line) -> output`, `output()`, plus view fields                                                                                                                                                                                                                                                                                                                                                                                                 |

- The rules package is always loaded implicitly. `rules` names a rules package to use instead of `//rules`.
  `bubble=True` keeps a reality bubble; without it every room is always simulated, so a mob can flee with no player
  near.
- **`p.do(line)`** submits the line directly, bypassing the session rate limiter. It runs pulses until the player's
  queue is empty and it isn't casting (at most 1000; more is an error), and returns all output since the last `do()` or
  `output()`.
- Harness players are exempt from the idle auto-quit. Worlds run in strict mode with the invariant check after every
  pulse, and with the wall-clock backstop off, so results never depend on machine speed.
- A test function's own code may spend 10,000,000 steps; pulses it runs don't count.
- Script diagnostics from a test's worlds are reported as warnings and don't fail the test. Tests assert on
  `w.diagnostics()` explicitly.
- `t.eq` and `t.ne` treat an entity view as equal to a label string that resolves (relative to the calling package) to
  its prototype label. Players are excluded.
- `w.trace()` keeps the last 400 lines (rules lines included) and prints them when the test fails.

### 4.13 CLI

|                                      Command                                      |                      Purpose                      |
|-----------------------------------------------------------------------------------|---------------------------------------------------|
| `gups serve --dev\|--prod [--fresh] [--world DIR] [--db FILE] ...`                | Run the server (§2.4).                            |
| `gups test [--world DIR] <label-pattern...>`                                      | Run tests, e.g. `//zones/goblin_caves/...`.       |
| `gups lint [--base REF] [--format text\|github] [label-pattern...]`               | Run the load and static checks (§7.3).            |
| `gups fuzz [--pulses N] [--seed S] <label-pattern...>`                            | Run the fuzz walker (§7.3).                       |
| `gups snapshot --sanitized --out FILE [--admin ADDR]`                             | Make a sanitized database copy.                   |
| `gups migrate-check --snapshot FILE`                                              | Run the migration check (§7.3).                   |
| `gups docs [--out DIR] [--world DIR]`                                             | Write the HTML API reference (§4.16).             |
| `gups docs --json [--out PATH]`                                                   | Write the `api.json` model (§4.16).               |
| `gups docs --update-baseline [--docs-dir DIR]`                                    | Before a release: write the API baseline (§4.17). |
| `gups repro <diagnostic-id\|bundle.json> [--save FILE]`                           | Replay a diagnostic in the harness.               |
| `gups backup` / `gups restore <file>`                                             | Back up or restore the database.                  |
| `gups deploy [--commit SHA]`                                                      | Called by CI; talks to the admin port.            |
| `gups invite --role R`, `gups import-roster [--admin ADDR] [--days N] roster.csv` | Create accounts.                                  |

- `--world` defaults to the current directory. `gups backup`, `restore` and `repro` take `--world` and `--db` as `serve`
  does.
- Commands that load a world (`test`, `lint`, `fuzz`, `migrate-check`, `repro`, `docs --world`) load its plugins too
  (§4.15).
- **`gups test`:** patterns are `//...`, `//a/...`, `//a`, `//a:file_test.star` and `//a:test_name`. It exits 1 if any
  test fails or errors, or a pattern matches nothing.
- **`gups restore FILE`** replaces the database with SQLite's restore API. The server must be stopped first; nothing
  enforces it.
- **`gups import-roster`:**
  - The CSV has a header row in any column order. Blank lines and `#` lines are skipped, double quotes are allowed, a
    leading `@` on a GitHub handle is dropped, and roles are case-insensitive. An optional `disabled` column takes
    `true`/`false`, `yes`/`no` or `1`/`0` (case-insensitive, trimmed). A blank cell leaves the flag alone; any other
    value fails parsing with `line <N>: '<value>' is not true or false`. Any bad row (unknown column or role, bad or
    repeated login, too many fields) fails the whole import with every problem listed, and nothing is written.
  - Output is a `login,setup_code` header, then one line per new account; a summary goes to standard error: `<created>
    accounts created, <updated> updated, <unchanged> unchanged, <disabled> disabled, <enabled> enabled. Setup codes
    expire in <days> days.`. `created` includes new accounts created disabled, which get no code. `--days N` sets the
    codes' lifetime.
  - Existing accounts without a password get no new code, so a re-import is idempotent; `@role <account> reset` issues a
    fresh one.
  - A row with `disabled` true disables its account as `@role disable` does, with the reason `roster` and no
    `disabledBy`. A new login listed as disabled is created disabled and gets no setup code. `false` re-enables the
    account. A change to the flag alone counts as an update, and the summary also counts the accounts disabled and
    enabled. A row whose flag another change already set counts as none of these. Only an import through `--admin`
    closes the account's characters and sessions at once. Offline, the disable applies from the next login, and the
    summary warns: `Warning: characters already in the world were not quit; use --admin against a running server.`
  - With `--admin ADDR` it goes through a running server's admin port (and refuses `--days`: codes last the default 7
    days). Without it, it and `gups invite` write the database directly, which is safe beside a running server because
    SQLite serializes the writes.
- **`gups deploy`** defaults to `127.0.0.1:9090`, prints each progress line, and exits 1 unless the deploy succeeded. It
  and `import-roster --admin` take `--admin-token-file` or `GUPS_ADMIN_TOKEN_FILE`.
- **`gups snapshot`:** sanitized is the only mode, and `--sanitized` is required so every call says so. Against a
  running admin port it calls `GET /snapshot`. Offline it copies the database through SQLite's online-backup API on a
  plain connection (WAL included, without running schema migrations), sanitizes the copy in place, and records the epoch
  timestamp in `meta.snapshot_taken_at`.
- **Sanitizing:** after the writer catches up, the copy is made with SQLite's backup API. Then `accounts`,
  `session_tokens`, `invites`, `restrictions`, `diagnostics` and `item_events` are emptied, characters are renamed
  `Player1`, `Player2`, ... in id order and detached from accounts, and the file is vacuumed. World rows, zone states,
  characters' payloads and their items are kept.

### 4.14 Admin Port

The admin port is plain HTTP on Ktor's Netty engine, bound to localhost or a private interface (`--admin`, default
`127.0.0.1:9090`), always on in `--prod`, and requires a bearer token.

- **Token:** read from `--admin-token-file`, `admin_token_file` or the file `GUPS_ADMIN_TOKEN_FILE` names, trimmed.
  Without one the server starts with a warning, and every endpoint but `/healthz` answers 403.
- **Checking:** a missing or wrong token gets 401 with `WWW-Authenticate: Bearer`. Tokens are compared as SHA-256
  digests with a constant-time comparison, so neither content nor length leaks through timing.

|    Endpoint     |                                                                                                                                                                                              Purpose                                                                                                                                                                                               |
|-----------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /metrics`  | Prometheus metrics (Micrometer).                                                                                                                                                                                                                                                                                                                                                                   |
| `POST /deploy`  | `{"commit"}`, or `{}` for the fetched tip of `deploy_branch`. Answers `application/x-ndjson`: one `{"progress": line}` per step, then `{"ok", "commit", "packages", "errors"}`, naming the commit it deployed.                                                                                                                                                                                     |
| `POST /roster`  | The roster CSV as the body, for `gups import-roster --admin` against a running server. Answers the `login,setup_code` lines (200) or the problems (400). Accounts it disables are closed at once, as `@role disable` does, before it answers. If the world doesn't close them within 30 seconds (for example while the server stops), it answers 500: the roster was imported, but closing failed. |
| `GET /snapshot` | A sanitized copy of the database (§4.13) for the CI migration check (§7.3), as a download.                                                                                                                                                                                                                                                                                                         |
| `GET /healthz`  | `{"pulse", "uptime_s", "writer_healthy"}`, where `pulse` is the last finished pulse. 503 when no pulse has finished for 5 s or the writer is unhealthy. No token needed.                                                                                                                                                                                                                           |

**Metric names:**

|     Area     |                                                  Metrics                                                  |
|--------------|-----------------------------------------------------------------------------------------------------------|
| Pulse health | `gups_pulse_duration_seconds` (histogram), `gups_pulse_overruns_total`, `gups_world_input_queue_depth`    |
| Scripts      | `gups_script_steps_total{package}`, `gups_script_errors_total{package,kind}`, `gups_quarantined_packages` |
| Population   | `gups_bubble_rooms`, `gups_bubble_entities`, `gups_sessions{transport}`                                   |
| Persistence  | `gups_checkpoint_duration_seconds`, `gups_checkpoint_entities`, `gups_checkpoint_backlog`                 |

- **Library:** Micrometer's Prometheus registry; its naming produces these names (the `gups.pulse.duration` timer
  becomes `gups_pulse_duration_seconds`). Pulse and checkpoint durations publish percentile histograms; pulse buckets
  run from 0.1 ms to 2 s.
- **Threads:** the world loop records each pulse's duration. Every second of game time the world thread publishes a
  snapshot for the gauges, so a scrape never reads the world. The per-package step counter behind
  `gups_script_steps_total` also feeds the overrun alert (§5.1), and `@stats` shows the same numbers as `/metrics`.
- **Meanings:**
  - `gups_pulse_overruns_total` counts skipped pulse boundaries.
  - `gups_bubble_rooms` counts simulated rooms (every room when the bubble is off).
  - `gups_sessions` counts sessions playing a character; logins in progress don't count. Its `telnet`, `tls`, `ws` and
    `local` series exist from the start.
  - `gups_script_errors_total` counts script-kind diagnostics (`script_error`, `step_limit`, `wall_clock`,
    `alloc_limit`, `missing`). It and `gups_script_steps_total` are registered for every loaded package at startup.
  - `gups_checkpoint_entities` is the rows in the last committed batch.
  - `gups_checkpoint_duration_seconds` times every commit, account batches included.

### 4.15 Plugins

The engine provides an engine-developer path (§1.2) for modular extensions packaged as `.jar` files discovered via
`java.util.ServiceLoader`. Plugins can add custom Starlark builtins, commands, effect primitives, GMCP packages,
permanent traits, action restrictions, and character creation workflows (§4.19). For detailed instructions and design
guidelines, see the engine-developer guide in [PLUGINS.md](PLUGINS.md).

**Architecture and interfaces:** The plugin API is defined in `:plugin-api` and has no dependency on internal engine
subsystems (`core` or `script`). It consists of three primary components:

- **`GupsPlugin`:** The entry point for plugins discovered via `ServiceLoader`. Each plugin declares a unique lowercase
  `id`, a required engine API version (`pluginApiVersion`, matching `PluginApi.VERSION`), doc text, required feature
  flags, and a `register(r: PluginRegistry)` method. See
  [PluginApi.kt](../plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt).
- **`PluginRegistry`:** The registration interface passed to `GupsPlugin.register`. It allows plugins to register
  Starlark builtins (with automatic parameter binding and type conversion), player commands (fast or tick lane, with
  optional roles and categories), custom effect primitives, GMCP package producers, world start callbacks
  (`onWorldStarted`), traits (`PluginTrait`), character creation definitions (`PluginCreation`), and action restrictions
  (`PluginRestrictionPoint.WEAR`, `ENTER`, `CAST`, `LEARN`). See
  [PluginApi.kt](../plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt).
- **The Facade (`PluginWorld` and `PluginEntity`):** Plugins never interact directly with internal mutable `World` or
  entity instances. Instead, the engine provides safe facades
  ([Facade.kt](../plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt) and
  [PluginViews.kt](../plugin-api/src/main/kotlin/gups/pluginapi/PluginViews.kt)) with engine-side wiring in
  [PluginHost.kt](../plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt). The facade exposes safe mutation
  verbs (`send`, `echo`, `damage`, `heal`, `applyAffect`, `removeAffect`, `teleport`, `setAttr`, `delAttr`,
  `setChoices`, `grantTrait`, `removeTrait`, `setSkill`, `removeSkill`, `awardXp`, `takeXp`, `levelUp`, `levelDown`),
  query methods, RNG access, and read-only views of live entities (`PluginRoom`, `PluginActor`, `PluginItem`,
  `PluginSkill`). All facade mutations execute synchronously on the world thread with invariant checking.

**Plugin capabilities and lifecycle:**

- **Types:** Every type in plugin signatures and the facade belongs to `gups.pluginapi`, to Kotlin/Java, or to the
  Starlark library (`StarlarkValue`, `StarlarkThread`, and annotations). None comes from `core` or `script`.
- **Version:** After the engine's first release, `PluginApi.VERSION` changes whenever this interface or the facade
  changes. Until then, changes remain at version 1. Plugins built for another version are refused at load.
- **Builtins:** Appear to scripts under the plugin's id (`weather.now(room)`). Arguments are bound to declared
  parameters, converted automatically (Starlark numbers, strings, collections, and entity views), and return values are
  wrapped back into Starlark equivalents. Exceptions become script errors in the calling hook. Builtins can only be
  called while the world runs from a hook or command.
- **Effect primitives:** Declared via `addEffectPrimitive` (`weather.soak(pulses = 3)`). They bind arguments as builtins
  do, reject entity arguments, and return an effect value usable in `skill(effect = ...)` or `sequence(...)`.
- **Commands:** Integrated into the command registry (§5.2). Names must be lowercase with no whitespace and cannot start
  with `@`. They cannot shadow engine commands or aliases. Role-gated plugin commands are hidden from ineligible
  players.
- **Categories:** Plugins can declare categories via `addCategory(name, title = name, order = null, help = null)`.
  Category help is displayed when players query `help <category>` if the category defines help text and contains
  accessible commands.
- **GMCP:** Producers are invoked in phase 7 for sessions supporting the package, sending only changed payloads (§4.10).
  Names must follow `Name(.Name)+` and must not clash with engine or sibling plugin prefixes.
- **Failures at run time:** Exceptions in commands, effect callbacks, GMCP producers, or `onWorldStarted` hooks are
  caught and logged as `plugin` diagnostics while world execution continues.
- **Traits (`addTrait`):** Declares permanent bundles of stat, max vital, resist, and sense deltas, flags, and modifier
  chain functions (§4.19). Unique across all plugins.
- **Creation (`setCreation`):** Configures character creation choices, options, availability rules, validation, start
  room selection, and initial setup (`created`) (§4.19). At most one plugin per world may set creation.
- **Restrictions (`addRestriction`):** Intercepts actions at `WEAR`, `ENTER`, `CAST`, and `LEARN` restriction points
  (§4.19). Must be fast and side-effect free.

**Facade operations:**

- **Verbs:** Verbs mirror the semantics and argument conventions of `ctx` verbs, invoking the same underlying engine
  logic on the world thread. Attempting to target an entity that has left the world (a dead mob, logged-out player, or
  destroyed item) fails with an error rather than silently doing nothing. Rooms never leave.
- **Choices and traits:** `setChoices` validates and applies choice changes atomically or refuses all changes (§4.19).
  `grantTrait` and `removeTrait` add or remove granted traits across any actor, refusing invalid or duplicate
  modifications.
- **Skills and leveling:** `setSkill` enforces `LEARN` restrictions before applying skills. `awardXp`, `takeXp`,
  `levelUp`, and `levelDown` manage progression, throwing if the `leveling` feature is disabled.
- **Attributes:** Keys without an explicit namespace prefix (`ns:`) default to the plugin's own ID namespace (§3.7).
  Plugins may read any namespace but may only write to their own. Values adhere to script-compatible attribute types.
- **Schema validation:** Referencing undeclared stats, vitals, or damage types in verbs or trait deltas throws
  `IllegalArgumentException` with spelling suggestions.
- **Read-only views:** Views wrap entities dynamically (`PluginEntity`, `PluginRoom`, `PluginActor`, `PluginItem`,
  `PluginSkill`), comparing equal when wrapping the same entity. `PluginItem.plural` exposes the prototype's optional
  display name for stacks of 2 or more.
- **Thread bridge:** `PluginViews.world(thread)` and `PluginViews.room/actor/item(value)` bridge Starlark threads and
  facade values. Unqualified attribute writes are disallowed when the calling plugin cannot be determined.

**Seams in `core` and `script`** (which don't depend on `plugin-api`):

- `Effect.Plugin(pluginId, name, args)`: One case of the sealed `Effect`, holding only plain values so prototypes still
  compare by value on reload. `World.pluginEffects` applies it.
- `World.pluginGmcp`: Stores plugin GMCP producers by package name.
- `CommandRegistry.registerPlugin` and `pluginCommands`: Registers plugin commands; `clearGlobals()` clears only script
  global commands, preserving plugin commands.
- `WorldPluginWiring` (in `script`): Manages `globals` (plugin namespaces by id, predeclared in every package, test
  file, and `@eval`), `install(world)` (invoked before the script host installs), and `started(world)` (invoked after
  `World.start()`, §4.11). Every place that builds a world provides wiring (`NONE` by default). `scriptWorld(thread)`
  resolves the running world from a Starlark thread.
- `WorldPluginWiring.install(world)`: Populates `World.creation`, `World.traits`, and `World.restrictions` (§4.11) with
  adapters over plugin objects. Adapters convert facade types to `core` types, catch plugin exceptions as specified in
  §4.19, and validate modifier results.

**Loading:**

- **Where jars come from:** `plugins/<id>.jar` in the world repository, each pinned by a SHA-256 in `MODULE.gups`
  (`plugins = {"weather": "sha256:<64 lowercase hex>"}`, §2.3). Unpinned `.jar` files in `plugins/` are ignored with a
  warning.
- **Reserved ids:** The package globals `module`, `server`, `ctx`, `self`, `room`, `rules_ctx`, `t`, `w`, `p`,
  `diagnostic`, and the Starlark universe cannot be used as plugin IDs. No package namespace or shared namespace may
  equal a plugin ID (§3.7).
- **Class loading:** One `URLClassLoader` per jar, with the engine's class loader as parent. `ServiceLoader` locates the
  plugin provider.
- **Checks:** Evaluated per pinned ID in alphabetical order: jar presence, SHA-256 checksum match, single `GupsPlugin`
  provider, matching `plugin.id`, matching `pluginApiVersion`, and satisfied `requiredFeatures`. A plugin failing any
  check is skipped with a warning, its class loader is closed, and other plugins continue loading.
- **Order:** Unpinned-jar warnings appear first, followed by plugins in ID order. `register` runs after all jars are
  verified, also in ID order.
- **Warnings and diagnostics:** Skipped plugins record a `plugin` diagnostic and a `plugin_load` alert. CLI commands
  print warnings to stderr.
- **Skipped plugins:** Packages depending on skipped plugins fail to load (`name '<id>' is not defined`).
- **Restarts:** Adding, modifying, or removing plugins requires editing `MODULE.gups` and `plugins/` and restarting the
  server. Deploys modifying `plugins/` are rejected (§5.10).
- **Tools:** Standalone tools (`fuzz`, `repro`, `migrate-check`) initialize plugins immediately after installing them.
  Tools that do not build worlds (`lint`) load only plugin globals.

**Trust:** A plugin is trusted code with full JVM access. There is no sandbox: the JVM no longer provides a
`SecurityManager`, and running plugins out-of-process is out of scope. Trust is established through procedure:

- Only jars pinned by SHA-256 checksum in `MODULE.gups` are loaded.
- In `template-world`, `CODEOWNERS` assigns `MODULE.gups` to instructors, requiring instructor approval for new plugins.
- Loading a plugin requires a server restart, mirroring engine upgrades.
- [PLUGINS.md](PLUGINS.md) provides instructors with a code review checklist.

**Sample plugins:**

- **Weather Plugin ([samples/weather-plugin](../samples/weather-plugin/README.md)):** A complete sample plugin
  demonstrating all core extension points. It adds the `weather.now(room)` Starlark builtin (deterministic weather based
  on seed, pulse, and region), a player `weather` command, a `weather.soak(pulses)` effect primitive, a `Gups.Weather`
  GMCP package, and an `onWorldStarted` lifecycle hook.
- **Heritage Plugin ([samples/heritage-plugin](../samples/heritage-plugin/README.md)):** A fantasy race and class sample
  plugin demonstrating character creation and restriction points for the template world schema. It defines `race` and
  `class` choices, mutual exclusion rules (e.g., dwarf mages disallowed), permanent stat/vital traits, class skill
  assignments on creation (`created`), and restriction checks for `WEAR` (heavy armor), `CAST` (arcane/divine spells),
  `ENTER` (guild rooms), and `LEARN`.

**The guide:** [PLUGINS.md](PLUGINS.md) covers building plugins against `plugin-api` (Gradle setup, `GupsPlugin`,
`PluginApi.VERSION`, service descriptors), facade operations, extension points with `WeatherPlugin` as a running
example, jar pinning via SHA-256, and character creation traits and restrictions with `HeritagePlugin`.

### 4.16 The API Model and Reference Docs

The script API model is defined in [ApiModel.kt](../script/src/main/kotlin/gups/script/ApiModel.kt) and rendered to HTML
and JSON documentation by [DocsRenderer.kt](../cli/src/main/kotlin/gups/cli/DocsRenderer.kt).

**The model.** One machine-readable description of the script API drives the docs, the changelog check and plugin docs,
so they can't drift apart:

- **Declarations:** Each declaration (the 13 package declarations, plus `module` and `server`) has a `DeclSpec` listing
  its parameters with type, default, allowed values, doc and `since`. The builtins parse their arguments through the
  spec (§4.2), so unknown-argument errors, spelling suggestions and docs share one source. After parsing, every
  parameter of the spec must have been read, so a parameter a builtin ignores can't be accepted and silently dropped. A
  required parameter with allowed values lists them as "a, b or c".
- **Other entries:** Small Kotlin tables next to the code that implements them: the hook catalog (with ctx fields and
  docs), the rules exports (whose names are the `RulesExport` enum, all documented), the modifier points, the constants,
  and the harness objects (`t`, `w`, `p`, `diagnostic`, built by the harness module and passed in, since `script`
  doesn't depend on `harness`).
- **Annotated builtins:** Builtins (`ctx`, views, the rules ctx, effect primitives) keep their `@StarlarkMethod`
  annotations, read by reflection.
- **`since`:** Every entry has `since`, the `api_version` that added it (default 1, never the current version, so
  raising the version doesn't re-date the API). Later additions carry `@ApiSince(n)` or `since = n`. Method parameters
  have no `since`: a parameter added to an existing method is recorded in the changelog.
- **Symbols:** Every entry has a unique `symbol`, the path the changelog names it by. Package globals are bare (`room`,
  `room.size`, `damage.dice`, `SENSE`); other kinds are prefixed where a bare name would clash (`hook:on_wake`,
  `view:room.exits`, `rules:to_hit`, `modifier:to_hit`). `ctx`, `rules_ctx` and the harness objects use their own names
  (`ctx.act_move`, `ctx.apply_affect.name`, `rules_ctx.weapon`, `t.eq`), as do settings (`module.start_room`).
- **Docs:** A documented default is either a Starlark expression the declaration really uses (a test passes it
  explicitly and checks the result is unchanged), or one of the descriptions "id with spaces", "words of name" and
  "package path". Every declaration parameter and method parameter needs a doc string; a missing one fails the build.
  Method parameters have no type in the model; types stay in the doc text.
- **Ctx fields:** Hooks list the ctx fields the engine may set (§4.4), and rules exports and modifier points list their
  `rules_ctx` fields, which must exist.
- **Completeness:** Every package global, every method of every `@StarlarkBuiltin` type in `gups.script` and
  `gups.harness` (nested classes included), and every hook name must be in the model.
- **Schema fields:** The view fields generated from a world's schema (§3.9) are pattern entries, `view:actor.<stat>`,
  `view:actor.<vital>`, `view:actor.max_<vital>`, `view:actor.base.<stat>`, `view:actor.base.max_<vital>`,
  `view:actor.resists.<type>` and `view:actor.base.resists.<type>`, never a world's own names, so the model doesn't
  depend on a world. The constructors `vitals`, `stats` and `resists`, and `DAMAGE`, are ordinary entries.
- **Features:** Every entry that belongs to a feature (§3.10) carries `feature`, its feature id; kernel entries carry
  none.
- **Messages:** The engine message catalog (§4.18) is a model list of its own, each entry with its id, placeholders,
  default text and `feature`.

**`api.json`:** A canonical dump of the model, tagged with `api_version` and the engine version. Top-level keys come in
a fixed order (`api_version`, `engine_version`, `declarations`, `settings`, `constants`, `hooks`, `ctx`, `views`,
`effects`, `behaviors`, `rules`, `harness`); map entries and member lists (`fields`, `methods`, `exports`,
`modifier_points`) are sorted by name. It is identical across runs and platforms. A copy with `engine_version` blanked
(`api.golden.json`) is compared on every CI platform; it is a snapshot of the *current* model, separate from the
released baselines of §4.17.

- `gups docs --json` writes it to stdout without `--out`, to `PATH` when it ends in `.json`, and otherwise to
  `<PATH>/api.json`, creating the directory. A `PATH` that is an existing file, or has another extension, is a usage
  error. `--json` never includes plugins, so `--json` with `--world` is a usage error.

**The HTML reference.** `gups docs [--out DIR] [--world DIR]` writes it into `DIR` (default `build/docs`); an existing
file or a `.json` name is a usage error. With `--world` it includes that world's plugins, schema, features and messages,
after checking its `MODULE.gups` and `MESSAGES.gups`, and leaves out entries of features the world has off; commands
appear by their renamed names. The files, in order:

|        File         |                                                                                                                                                                                                               Contents                                                                                                                                                                                                                |
|---------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `style.css`         | The one stylesheet, a dark theme matching the web client.                                                                                                                                                                                                                                                                                                                                                                             |
| `index.html`        | `api_version`, engine version, a table linking every other page with its entry count, a link to `api.json`, "Writing plugins" (`plugins.html#guide`), and a link to the current version's changelog section.                                                                                                                                                                                                                          |
| `declarations.html` | Declarations, then settings (`module`, `server`), then constants.                                                                                                                                                                                                                                                                                                                                                                     |
| `hooks.html`        | A summary table, then each hook with its signature, whether it is vetoable, what it attaches to, and its ctx fields, linked into `ctx.html`.                                                                                                                                                                                                                                                                                          |
| `ctx.html`          | `ctx`, then every view.                                                                                                                                                                                                                                                                                                                                                                                                               |
| `effects.html`      | Effect primitives, then behaviors.                                                                                                                                                                                                                                                                                                                                                                                                    |
| `rules.html`        | `rules_ctx`, then the rules exports and modifier points with signatures, types and ctx fields.                                                                                                                                                                                                                                                                                                                                        |
| `harness.html`      | `t`, `w`, `p`, `diagnostic`.                                                                                                                                                                                                                                                                                                                                                                                                          |
| `world.html`        | With `--world`: the world's schema (stats, vitals, damage types and channels, with labels), its features and renamed commands. Always: the message catalog, with each id's placeholders and default text, and with `--world` the world's override.                                                                                                                                                                                    |
| `plugins.html`      | The engine-developer guide (`docs/PLUGINS.md`) as `<section id="guide">`, then `<h2 id="loaded-plugins">This world's plugins</h2>`: each plugin's builtins, effect primitives, commands, GMCP packages, traits (label, doc and effects), restrictions (point and doc) and, for the creation provider, its choices and options (title, labels, summaries, help and granted traits), badged `plugin: <id>`, or "No plugins are loaded." |
| `changelog.html`    | `docs/CHANGELOG.md` rendered as Markdown (§4.17).                                                                                                                                                                                                                                                                                                                                                                                     |
| `api.json`          | Byte for byte what `gups docs --json` writes.                                                                                                                                                                                                                                                                                                                                                                                         |

- **Entries** show name, `since`, a `feature: <id>` badge for feature entries, doc, example if any, and parameters,
  fields and methods as tables (declaration parameters with type, positional/keyword, required, default, allowed values
  and doc).
- **Page rules:** every page is HTML5 with a UTF-8 charset, a viewport meta tag, the title `<Page> — GUPS API Reference
  (api_version <N>)`, the shared stylesheet, and the same header (engine version, `api_version` badge, and a nav of
  every page plus `api.json`). There is no `<script>`, and every link is relative to a file of the reference, so the
  pages work offline and from `file://`. All text from the model, the changelog or a plugin is HTML-escaped. Output is
  deterministic, in model order, with `\n` line endings.
- **Anchors:** every symbol has an element with `id` equal to the symbol with `:` replaced by `-` (`hook:on_enter` →
  `hook-on_enter`). The page a symbol lives on comes from the model list that contains it. Plugin entries are anchored
  `plugin-<id>.<name>` (traits `plugin-<id>.trait-<name>`, choices `plugin-<id>.choice-<choice>`, restrictions
  `plugin-<id>.restrict-<point>-<n>` counting from 1) and are never part of `api.json`.
- **Examples:** an entry may carry a short example, kept in one table keyed by symbol (not in `api.json`, so editing one
  never changes the API or needs a changelog entry). An example has package code, a harness test body, or both. Every
  declaration, constant, effect primitive, behavior and harness type must have one. Each runs as a test in a temporary
  world with the template's `rules/`, a fixture room `start`, and the example as package `zones/ex` (or as `MODULE.gups`
  for `module`); it passes when its test passes with no warnings.
- **Markdown:** the changelog and the guide are rendered as CommonMark. Headings get ids (`api_version-<N>` for version
  headings, otherwise a lowercase slug, with `-2`, `-3`, … for repeats). Links and images render as their text only, so
  nothing leaves the reference. Raw HTML is escaped; none passes through.
- **Where it goes:** the release zip `gups-docs-<version>.zip` (under a top-level `gups-docs-<version>/`), `docs/` in
  the install directory, the jlink image and the OCI image (§2.2), and the running server. Both profiles render it once
  at startup, with the world's plugins, and serve it with no login: `--prod` on the HTTPS listener at
  `https://host/docs/` (no `--https`, no docs), `--dev` at `http://127.0.0.1:4080/docs/`. `GET /docs` redirects (302) to
  `/docs/`; `/docs/` is `index.html`; `/docs/<file>` is that file or 404. HTML, CSS and JSON are served as UTF-8.

### 4.17 Changelog and API Baselines

**`docs/CHANGELOG.md`** is plain, human-written Markdown listing script-facing changes per `api_version`, newest first,
with before/after snippets for breaking ones. The engine never parses its structure; `changelog.html` renders it as
Markdown (§4.16). Each version heading `## api_version <N>` gets the anchor `api_version-<N>` that the `api_version`
mismatch diagnostic links to (§1.3).

**The API-change check.** Engine CI fails when the script API changes incompatibly without an `api_version` bump. API
comparison and breaking change detection are implemented in
[ApiDiff.kt](../script/src/main/kotlin/gups/script/ApiDiff.kt). `docs/api/api-<N>.json` is the API as released at
`api_version` N, the **baseline**. With `N` the engine's `api_version`, the check compares the baseline of version `N`
with the current model:

1. **Diffs:** Compares every object with a `symbol` in both, keeping only scalar fields and lists of strings, without
   `symbol`, `doc` and `since` (so doc-only edits never fail). Child changes show up as their own symbols. A symbol is
   *removed*, *added* or *changed*; a removed or added symbol stands for the symbols beneath it.
2. If the baseline's `api_version` equals `N`, every removed or changed symbol is an error saying that breaking changes
   require raising `api_version`, and every added symbol must have `since` equal to `N`.
3. If the baseline's `api_version` differs from `N`, any difference is allowed.

**Raising `api_version`** is a pre-tag step, because the tagged commit must already contain the new baseline (the
tag-triggered release job can't commit it): set the engine's `api_version`, then run `gups docs --update-baseline
[--docs-dir DIR]`. It writes only `docs/api/api-<N>.json` and keeps older baselines. It takes no `--json`, `--out` or
`--world`. A release with only additions keeps `N` and rewrites that file.

### 4.18 Engine Messages

Every player-facing line the engine sends is an entry of the **message catalog**, managed in
[Messages.kt](../core/src/main/kotlin/gups/core/world/Messages.kt), so a world can change the words without changing the
mechanics ("You begin casting" becomes "Your implant spins up").

- **Entries:** each has a stable dotted id, named placeholders and default text, for example `cast.begin.known` =
  `"{actor} begins casting {skill}."` and `combat.quit_refused` = `"No way! You're fighting for your life!"`. The
  defaults are the texts this specification gives. Ids are grouped by the feature that sends them; an id of a feature
  that is off is never sent.
  - Leveling entries include `leveling.level_down` (`"You have fallen to level {level}."`, placeholders `level`,
    `count`), `leveling.ready` (`"You feel ready to advance."`, no placeholders) and `leveling.score_ready` (`"You are
    ready to advance."`, no placeholders).
- **Coverage:** every message to players and onlookers from the engine and its features: command replies and refusals,
  combat, cast and condition messages, `look`, `score`, `who` and `help` framing, and login and character-menu text.
  Output of `@` tools isn't in the catalog; staff see engine terms.
- **Placeholders** are `{name}`, filled in when the line is sent. Actor placeholders take the name as the viewer sees
  it; `{actor}` is capitalized for the start of a sentence, other actor and item placeholders are plain names. `{{` is a
  literal brace, as in color markup (§4.10), which message text may use.
- **Overrides:** `MESSAGES.gups` at the world root, with one call:

  ```python
  messages({
    "cast.begin.known": "{actor}'s implant whines as it spins up {skill}.",
    "cast.begin.unknown": "{actor}'s implant whines.",
  })
  ```

  An unknown id, an unknown placeholder, or text that leaves out a placeholder the entry marks required stops the
  server, with a "did you mean" suggestion. An id of a feature the world has off is a warning. The file is read only at
  startup (§2.3).

- **Docs:** `gups docs` lists the catalog on `world.html`, with the world's overrides when given `--world` (§4.16).

### 4.19 Character Creation, Traits and Restrictions

A world can have new characters make **choices** (a fantasy term's race and class), give them permanent **traits** that
follow from those choices, and **restrict** what a character may wear, cast, learn or enter. The engine names no choice,
option or trait: they all come from plugins (§4.15). A world without them creates characters as before, with no choices,
no traits and no restrictions, and players see nothing new.

**Choices:**

- A **creation provider** is the one plugin that declares the world's choices, in the order they are asked. A world has
  at most one.
- A choice has an id, a `title` ("Race"), a `prompt` ("Choose your race:", by default "Choose your <title in
  lowercase>:") and its options. An option has an id, a `label` ("Elf"), a one-line `summary`, an optional longer
  `help`, and the names of the traits it grants.
- Ids match `[a-z][a-z0-9_]*`. Choice ids are unique. Option ids are unique within their choice and may not be `back`,
  `cancel`, `help`, `yes` or `no`. Every choice has at least one option. The definitions are fixed for the life of the
  server.
- **Availability:** Given the answers to earlier choices (in declaration order), the provider says which of a choice's
  options are available, in declaration order; by default, all of them. This is how combinations are restricted: no
  dwarf mages means `class` doesn't offer `mage` once `race` is `dwarf`. A choice with no available option is skipped
  and gets no answer, so a `deity` choice can apply to clerics only. An id the provider returns that the choice doesn't
  define is dropped with a `plugin` diagnostic.
- **Validation:** Before a character is created, and before its choices change, the provider may refuse the whole set of
  answers with a message, for limits that availability can't express.
- **Start room:** The provider may name the room a new character starts in, given its answers. By default it is the
  world's start room. A label that doesn't resolve records a `plugin` diagnostic and uses the default.
- **Completeness:** A character's answers are complete when every choice with an available option (given the answers to
  the choices before it) has an answer that is one of them. Any other choice with an available option is **missing**.
- **Failures:** An exception from the provider records a `plugin` diagnostic. During creation the player is told
  "Character creation isn't working right now; please tell an instructor." and returned to the character menu. In a
  change (below) the change is refused with the same message.

**Answers on the character:**

- A player stores its answers as `choices` (choice id → option id), saved with the character (§3.6). Mobs have no
  choices.
- An answer whose choice or option the provider no longer defines is kept but has no effect, isn't shown to the player,
  and leaves its choice missing. A world that removes the plugin, or one option, loses nothing for good, and the player
  picks again at their next login (§5.12).

**Traits:**

- A trait is a named bundle of permanent effects: stat deltas, max-vital deltas, resist deltas, flags, and modifiers for
  the modifier chain (§4.6). Names match `[a-z][a-z0-9_]*` and are unique across all plugins. Each has a `label` for
  players and a `doc`.
- A player's traits are those its chosen options grant, plus traits **granted** to it directly (a curse, a blessing), in
  name order without repeats. Granted traits are saved with the character. Option traits are worked out from the answers
  whenever they change and when the character loads, so a plugin update that changes what an option grants reaches every
  character.
- A mob's traits are its prototype's `traits` (§4.2), plus traits granted to that mob, in name order without repeats. A
  guild trainer declared with `traits = ["mage"]` passes the same restrictions a mage player does, and has the same
  bonuses. Prototype traits follow the prototype on reload; granted ones are saved with the mob (§3.6). Mobs have no
  choices.
- A saved granted trait that no plugin defines is kept and has no effect. `@stat` lists it as unknown. A mob prototype
  naming an undefined trait fails its package's load instead (§4.2).
- Traits aren't affects: they never expire, `dispel` never touches them, and `affects` and `Char.Affects` don't list
  them.
- **Effects of traits:** The same for players and mobs:
  - **Stats:** Trait deltas join affect, equipment and encumbrance deltas in the effective value. Base stats don't
    change.
  - **Vitals:** A vital's effective maximum is its saved (base) maximum plus the trait deltas, at least 0. Everything
    that reads a maximum reads the effective one: views, the rules, `regen` and `heal` caps, `look`, the prompt, GMCP
    and invariant 3 (§3.8). Gaining a trait leaves `current` as it is; losing one caps `current` at the new effective
    maximum.
  - **Resists:** The effective resist is the base resist plus the trait deltas. `mitigate` and the views see the
    effective value.
  - **Flags:** A trait's flags are the actor's flags while it carries the trait, as an untimed affect's would be
    (`has_flag`, `silenced`, `no_regen`). `stunned` can't be a trait flag.
  - **Modifiers:** At the `to_hit`, `damage` and `initiative` points (`combat` feature) and `move_cost` and `regen`.
    They join the chain after the room's modifiers and before the actor's affects (§4.6), and `@trace` shows them as
    `trait:<name>`. Like a script modifier, one that fails or returns the wrong type leaves the value as it was; the
    failure is a `plugin` diagnostic.
  - **Schema names:** Stats, vitals, and damage types named in a trait are checked when the plugin registers it (§4.15).

**Restrictions** are checks that plugins register at four points. Each gets the actor and what it is about to do, and
either allows it or refuses with a message.

|  Point  |                            Checked                             |                                     Subject                                      | Feature  |
|---------|----------------------------------------------------------------|----------------------------------------------------------------------------------|----------|
| `wear`  | `wear` and `wield` (and their renames), before the item moves  | the item and the slot it would fill                                              | kernel   |
| `enter` | every room transition that runs `before_enter`, just before it | the destination room, the room being left, the direction (`None` for `teleport`) | kernel   |
| `cast`  | wherever `rules.cast_allowed` is asked, just before it         | the skill and its target                                                         | `skills` |
| `learn` | a plugin setting a skill on an actor (§4.15)                   | the skill and the proficiency                                                    | `skills` |

- Checks apply to every actor, players and mobs alike; a check that only cares about players allows mobs.
- An `enter` refusal stops the move as a `before_enter` veto does: walking costs no lag; a `flee` through that exit is a
  failed flee (the refusal and the failed-flee lag); a mob's `wander` or `pursue` step just fails. `teleport`, and so
  `@goto`, is refused before anything moves.
- All checks at a point run in plugin id order, and in registration order within a plugin. The first refusal wins. A
  player sees its message, and a refused action costs no lag, as a veto (§5.3). For a mob the action just fails.
- A check that throws records a `plugin` diagnostic and allows, so a broken plugin can't lock everyone out of a room.
- Being staff doesn't exempt anyone. A check may read the actor's role (§4.15) and allow builders through, for example.
- Restrictions only see what an item, skill or room declares. **Tags** (§4.2) are how content opts in:
  `item("plate_mail", tags = ["heavy_armor"])` and a `wear` check that refuses `heavy_armor` to mages. The engine gives
  tags no meaning.
- Script content restricts with the vetoes it already has: `before_enter`, `before_cast` and `cast_allowed` can read
  `ctx.actor.choices` and `ctx.actor.has_trait(...)`. There is no script hook for `wear`.

**Changing choices later:** Choices are permanent unless a plugin changes them. The engine provides the mechanism
(`setChoices`, `grantTrait` and `removeTrait`, §4.15) and no player command for it. Ways to change in play (a shrine
that changes your race, a trainer who lets you change class) are plugin features. A change:

1. Is checked as creation is: every answer must be available given the answers before it, and then the provider
   validates the whole set. A refusal changes nothing.
2. Sets the answers and works out the traits again. Effective values follow at once; `current` vitals are capped at the
   new maxima.
3. Checks every worn item against `wear` again. Each refused item is moved to the inventory, and a player is sent the
   refusal message.
4. Doesn't run `rules.starting_stats` again: base stats, base maxima and skills stay.
5. Triggers an immediate capture (§5.11).

Removing an answer leaves that choice missing, so the player chooses it again at their next login (§5.12). That is how a
plugin lets a player re-pick without building its own prompts. Granting or removing a trait follows steps 2 to 5, for
players and mobs alike; for a mob, step 5 only marks it dirty, as any mob change does. A prototype trait can't be
removed from one mob.

**Script access is read-only:**

- The actor view has `choices` (a dict, empty for mobs and when nothing is chosen), `traits` (sorted names of the known
  traits it carries, for players and mobs) and `has_trait(name)` (§4.5). `base` gains `base.max_<vital>` and
  `base.resists.<type>`. The rules ctx's actor views have the same fields.
- `rules.starting_stats` runs with the new character's answers and traits already set, so it can read
  `ctx.actor.choices` (class hit dice, say). For a mob it runs with the prototype's traits set. Its stats and maxima are
  base values; the traits' deltas apply on top.
- Content names traits only on mob prototypes (`traits = [...]`). Scripts can't change choices or traits; a `//lib` that
  needs to can call builtins its plugin exposes.

**Players see** their choices and traits in `score` (§4.9) and through GMCP: `Char.Creation` during the creation steps
and `Char.Traits` in play (§4.10). `gups docs --world` lists the choices, options, traits and restrictions on
`plugins.html` (§4.16).

---

## 5. Core Logic, Control Flow & Edge Cases

### 5.1 World Loop

The world coroutine in [WorldLoop.kt](../core/src/main/kotlin/gups/core/loop/WorldLoop.kt) and
[World.kt](../core/src/main/kotlin/gups/core/world/World.kt) selects over the input channel and the next pulse boundary.
Pulse boundaries come from `System.nanoTime()`.

- **Between pulses:** Each dequeued `ClientInputEvent` is classified (§5.2). Fast-lane commands execute immediately.
  Tick-lane commands are appended to the actor's queue. Output produced while handling a session's input between pulses
  is flushed to that session at once; output caused by anything else waits for phase 7.
- **At each pulse boundary**, `pulse++` and then these phases run in this exact order:
  1. **Timers:** Fire `ScriptTimer`s and affect ticks/expiries due at this pulse, ordered by `(due, id)`.
  2. **Action pulse:** For each active actor, in **initiative order** (§5.4.1):
     - If `casting`, advance it. When the warmup completes, resolve the action (§5.3).
     - Otherwise, if `waitState > 0`, decrement it.
     - Otherwise, if the queue is non-empty, pop the head and execute it.
     - Otherwise, if the actor is a mob whose think cadence is due, run the think cycle (§5.8).
  3. **Combat round** (`pulse % COMBAT_ROUND_PULSES == 0`,
     [TimeConstants.kt](../core/src/main/kotlin/gups/core/TimeConstants.kt)): Roll initiative for the new round
     (§5.4.1). Then, for each engaged active actor in initiative order, resolve one auto-attack (§5.4) and fire
     `on_combat_tick` on the actor, then on each item it has equipped that declares the hook, in ascending `EntityId`
     order (§4.4).

     Within phases 2 and 3, actors act in **initiative tiers**, from the highest initiative to the lowest. An actor
     killed or incapacitated by a *higher* tier is skipped for the rest of that phase, so a faster actor can kill an
     enemy before it strikes, even within the same pulse. Actors in the *same* tier act simultaneously (§5.4.1): all of
     their actions happen.

  4. **World tick** (`pulse % WORLD_TICK_PULSES == 0`,
     [TimeConstants.kt](../core/src/main/kotlin/gups/core/TimeConstants.kt)):

     - Regenerate vitals via `rules.regen` and the modifier chain. Actors with a `no_regen` affect are skipped.
     - Fire `on_tick` for active entities.
     - Process zone resets and respawns that are due.
     - Advance item decay.
     - Capture a periodic checkpoint if the checkpoint interval has passed (§5.11).
  5. **Deferred perception:** Dispatch the `on_perceive` events queued during this pulse (§5.7).
  6. **Bubble maintenance:** Apply bubble changes recorded during this pulse (§5.9).

     Session expiry is not a numbered phase: link-dead and idle auto-quit run between phases 6 and 7, before the output
     flush.

  7. **Output flush:** Flush output and send GMCP deltas (state packages only when changed, §4.10).

- **Deaths outside a tier:** Damage dealt from timers, fast-lane commands, or direct verb calls outside phases 2 and 3
  processes death, and any room transition, right away instead of deferring it to the end of a tier.

- **Overrun policy:** A pulse that ends at or before the following boundary is on time. If a pulse finishes after the
  next boundary, the scheduler does **not** run the missed pulses back to back. It skips to the next future boundary
  (which may be "now", in which case the next pulse runs immediately) and increments `pulse_overruns` once per skipped
  boundary. The pulse counter advances only once per pulse actually executed, so a lag spike slows game time instead of
  making it lurch forward.

- **Overrun reporting:** A pulse overruns when it takes longer than `PULSE_MS`
  ([TimeConstants.kt](../core/src/main/kotlin/gups/core/TimeConstants.kt)). If more than 10% of pulses in the last
  minute overran, instructors get an `overrun` alert (§6.2) naming the top step-consuming package: script steps per
  package (rules functions included) are sampled every combat round, and the package with the largest increase over the
  sampled minute is named. Nothing is auto-quarantined for this.

### 5.2 Command Classification and Admission

Command classification, queue admission, and rate limiting are handled by
[Commands.kt](../core/src/main/kotlin/gups/core/world/Commands.kt):

1. Parse the verb, resolve the world's aliases and prefixes (§2.3), and look it up in the engine commands, global
   commands, then room and item `commands`. Ambiguity resolves in that order.
   - Room `commands` are checked before item `commands`. Item `commands` come from equipped items, then inventory, then
     items on the ground; within a layer the lowest item ID wins. Items inside containers grant no commands.
   - A line starting with a prefix character runs that prefix's command with the rest of the line as its arguments
     (`'hello` is `say hello` in the template world).
   - An exact name or alias wins and is never ambiguous (in the template world `l` is `look`'s alias, though `lock` also
     starts with `l`). Otherwise a verb that is a prefix of the names or aliases of exactly one engine, global or plugin
     command resolves to it; a prefix matching more than one command (`cl` for `clear` and `close`) is ambiguous and
     gets "Huh?". Prefixes are tried only after every room and item command. An `@` prefix follows the same rule among
     the `@` commands the actor may use (§4.9).
   - **Line limit:** Before any of this, and before login, every input line takes a token from a second per-session
     bucket: capacity 25, refill 25 tokens/s. When it is empty the line is dropped silently, whatever it would have
     parsed to. These drops count toward the fast-lane disconnect below.
2. **Fast lane:**
   - Rate limiting uses a per-session token bucket: capacity 20, refill 5 tokens/s.
   - When the bucket is empty, input is dropped with "Slow down." More than 3 drops within 60s, counting line-limit
     drops, disconnects the session.
3. **Tick lane:**
   - The command is appended to the actor's queue.
   - If the queue already holds `ACTION_QUEUE_MAX`
     ([TimeConstants.kt](../core/src/main/kotlin/gups/core/TimeConstants.kt)), it's rejected with "You can't plan that
     far ahead." and nothing is enqueued.
4. **`stop` / `clear`** (fast lane):
   - Flushes the queue.
   - Cancels a channeled cast, with no cost paid and no lag applied.
   - Does **not** end combat engagement or clear existing `waitState`.
5. **Parsing:** A queued entry keeps the verb and argument text. Arguments, including target keywords, are resolved when
   the action is popped, so `kill goblin` targets whichever goblin is present then.

### 5.3 Action Lifecycle (tick lane)

Tick-lane action execution, warmup, interrupts, and recovery lag are handled by
[Actions.kt](../core/src/main/kotlin/gups/core/world/Actions.kt).

For each popped action:

1. **Validate:** Check that the target still exists and is in range (band), that the actor can pay the cost (§4.3), and
   that it isn't stunned, incapacitated or silenced.

- On failure, send a message. No lag is applied.

2. **Veto phase:**

- Fire `before_*` hooks in dispatch order (§5.6). For casts, `cast_allowed` runs through the modifier chain first, once;
  it is not re-checked after the warmup.
- The first `deny` cancels the action, sends its message to the actor, and applies **no** lag.

3. **Warmup:**

- If `warmup > 0`, set `casting = CastState(action, endsAt)` and fire `on_cast_start`.
- Onlookers who know the skill see "<caster> begins casting <skill>."; others see "<caster> begins casting."
- Each incoming `damage` event calls `rules.interrupt_check`. A `stun` always interrupts if `interrupt.stun`.
- An interrupt clears `casting`, pays no cost, and applies `lag / 2` (rounded down).

4. **Re-validate** when the warmup completes. This re-runs the full validation: target presence and band, cost, and the
   `silenced` flag. The target is the one chosen at the first validation. A failed re-validation pays no cost and
   applies `lag / 2`.
5. **Resolve:**

- Pay the cost.
- For casts, everyone else in the room except the target sees "<caster> casts <skill>.", with " at <target>" or " on
  <item>" when there is one. Instant casts are seen too.
- Apply the `effect`, or call `resolve(ctx)`. All mutations go through verbs.
- Fire the `on_*` hooks (`on_cast`, `on_damage`, …).

6. **Recovery:** Set `waitState = max(waitState, lag)`, so recovery never shortens existing lag. No lag is applied if
   the resolve left the actor out of the world or casting.

Instant actions (`warmup == 0`) run steps 1, 2, 5, and 6 in the same pulse. Casts, warmups and interrupts are the
`skills` feature; other tick-lane actions follow the same steps with no warmup.

### 5.4 Combat Rounds

Everything in this section is the `combat` feature, except conditions, death, corpses and respawns, which are the kernel
and work with or without it (§3.10). Combat resolution is implemented in
[Combat.kt](../core/src/main/kotlin/gups/core/world/Combat.kt), vitals and conditions in
[Vitals.kt](../core/src/main/kotlin/gups/core/world/Vitals.kt), and progression mechanics in
[Leveling.kt](../core/src/main/kotlin/gups/core/world/Leveling.kt).

- **Engagement:**
  - Combat starts through `kill`/`attack`, `aggressive`/`assist` behaviors, a hostile skill landing, or
    `ctx.start_combat`.
  - A skill is hostile when it lands on an actor other than the caster and either its `target` is `enemy` (including a
    `room`-range skill hitting each actor in the room), whatever its effects, or it applies a `damage`, `stun`,
    `knockback` or `pull` primitive (§4.3). Those primitives engage the same way wherever they are applied from.
  - It ends on death, on either party leaving the room, on a successful `flee`, or with `ctx.stop_combat`.
  - Engagement is always symmetric. `start_combat` engages both sides and sets each side's primary target if it has
    none. "Hostile", wherever the track rules use it, means an engaged opponent in the same room.
- **Auto-attacks:**
  - Every `COMBAT_ROUND_PULSES` ([TimeConstants.kt](../core/src/main/kotlin/gups/core/TimeConstants.kt)), each engaged
    actor auto-attacks one target: its current primary target, or the lowest-`EntityId` remaining opponent.
  - Auto-attacks are independent of `waitState` and of queued actions, so active skills weave between swings.
  - A **casting** actor does not auto-attack, unless the skill being cast sets `attack_while_casting = True`.
- **Range:** An auto-attack happens only if the target's band is within the wielded weapon's `range` (unarmed or melee:
  `MELEE`; a ranged weapon: up to `FAR`). Otherwise no swing occurs that round, and the actor is told "<target> is out
  of reach."
- **Resolution:**
  1. Call `before_attack` (vetoable) on the defender first (§5.6), so defensive wards evaluate before the attacker and
     its wielded weapon.
  2. Roll `rng.int(1,100)` against `to_hit` (through the modifier chain).
  3. On a hit, compute `damage` (chain), then `mitigate` with the weapon's damage type (or the world's
     `unarmed_damage_type`), then subtract the vital losses it returns. Every loss is subtracted, `on_damage` fires
     once, the interrupt check runs, and then `condition` is asked once for the damage event (death is still deferred to
     the end of an initiative tier). Outside a damage event, a vital change asks `condition` once.
  - Messages show no numbers: "You hit X." / "X hits you." / "X misses Y."
- **Regen** at the world tick applies to every actor without `no_regen`, engaged or not.
- **Conditions:** `rules.condition` (§4.6) decides each actor's condition after any change to one of its vitals, and for
  every simulated actor at each world tick. The engine acts when the condition changes:
  - **`incapacitated`:** the actor is told "You are incapacitated." and the room sees "<actor> collapses." Until its
    condition is `ok` again it is treated like a stunned actor (§5.4.1), without a `stunned` affect: it takes no actions
    and makes no auto-attacks, its cast is interrupted as by a stun with `interrupt.stun`, its recovery is held, and it
    can't use tick-lane commands (§4.9). It stays engaged, can be targeted and regenerates. When it ends the actor is
    told "You come to." and the room sees "<actor> stirs."
  - **`dead`:** the actor dies, as below.
  - **`ok`** again ends incapacitation. A rules error keeps the previous condition (§6.3).
- **Death:**
  - Engine-owned, and part of the kernel: any vital can kill, in or out of combat. The killer is the last actor who
    damaged the victim, if still in the world. The order is: messages, `on_death` (target's own hook, then the room,
    then observers), the corpse, then XP (with the `leveling` feature). The XP is priced by `xp_for_kill` as the victim
    died but awarded last, so a level it raises is not heard by the victim or by the belongings it leaves.
  - **For mobs:** creates a corpse container holding the mob's inventory, its equipment and the loot rolls, and awards
    XP via `xp_for_kill`. A summoned mob (`summonedBy` set) awards no XP and rolls no loot.
  - **For players:** applies `rules.on_player_death`, takes its `xp_loss` from `xp` (never below 0) and leaves a corpse
    holding the carried inventory only; equipment stays on the player. The player respawns at index 0 of the respawn
    room with the vitals `on_player_death` returned, its condition `ok`, and all affects removed. If `condition` still
    says `dead` for the respawned player, that is a rules error, and the player's vitals are set to their maxima. After
    the respawn the player loses `level_loss` levels.
  - Corpses decay after `CORPSE_DECAY_PULSES` ([TimeConstants.kt](../core/src/main/kotlin/gups/core/TimeConstants.kt)).
    Decay drops a container's contents where it lay.
  - Death clears `casting`, the queue, and engagement. It's processed exactly once. During phases 2 and 3 it's deferred
    to the end of the current initiative tier (§5.4.1).
- **Levelling** (the `leveling` feature): XP is gained from kills and `award_xp` and lost to `take_xp` and
  `on_player_death`. An actor is owed level `L + 1` once `xp` reaches its own `xp_to_level(ctx, L)`, unless that is not
  greater than its `xp_to_level(ctx, L - 1)`. In `auto` mode (`level_mode`, §3.9) the engine calls `level_up` for each
  owed level at once. In `deferred` mode it tells the player "You feel ready to advance." and applies a level only when
  `level_up` is called. Applying a level raises `level` by one, calls `on_level_up`, adds its `stats` and `max` to the
  base values and remembers them in `levelGains` under the new level, adds its `current` to the vitals, tells the actor,
  and fires `on_level_change`. Losing a level (`level_down`, or `level_loss` at death) subtracts the remembered gains of
  that level, lowers `level`, caps the vitals at the new maxima, sets `xp` to at most one below `xp_to_level(ctx,
  level)`, and fires `on_level_change`; the rules are not called. A level with nothing remembered unwinds nothing. Each
  level's entry belongs to that level alone: applying a level replaces whatever was remembered for it, and a level that
  gives no `stats` or `max` remembers nothing, so a later loss of it never subtracts gains it did not give. Entries for
  other levels are never touched. One call applies at most 100 levels. A player whose level changes, up or down, is sent
  `Char.Status` at once, with the message, rather than at the end of the pulse.
- **Respawns:** a spawn rule's respawn deadline is set when its first live instance leaves the world. The rule refills
  to `count` at the first world tick at or after the deadline. A fresh world's rooms fill their spawns when the world
  starts (§4.11).
- **Dormancy:** Combat never continues in a dormant room. When a room goes dormant, its NPCs disengage. How their vitals
  recover is set per mob by `dormant_recovery` (§5.9).

#### 5.4.1 Initiative

- **Rolling:** At the start of each combat round, the engine calls `rules.initiative(ctx)` for every engaged active
  actor, through the `initiative` modifier point. The result is an int; higher acts first. The rules module may use
  stats, equipment, affects and `ctx.rng`, so initiative can be a fixed rating or a roll.
- **Duration:** The values hold for the whole round: the combat round phase and the 7 action pulses after it. A haste
  affect that lands mid-round takes effect next round.
- **Tiers:** Engaged actors with the same initiative form one **tier**. Tiers run from the highest initiative to the
  lowest. Actors that aren't engaged form one final tier after every engaged tier. Initiative is rolled only in the
  combat round phase, so an actor engaged after the roll has none until the next round and acts in that final tier.
- **Ties are simultaneous.** Every actor in a tier gets its action, even if another actor in the same tier kills, stuns,
  or displaces it first:
  1. **Snapshot:** When a tier starts, the engine records, for each simulated actor (§5.9), which includes every actor
     in an active room: whether it can act, its room, and its track index. Same-tier actions are validated (target
     present, band in range, actor able to act) against this snapshot, not the live state. An actor outside the snapshot
     cannot be affected by the tier, so it is validated against its live state.
  2. **Execution:** The tier's actions then execute one after another. Damage, healing, affects, and track movement
     apply immediately, and `on_damage` fires immediately. Ascending `EntityId` fixes the sequence, which decides only
     message order and the order of `rng` draws, never *whether* an actor acts.
  3. **Deferred removal:** Anything that would take an actor out of the fight is committed at the **end of the tier**,
     in ascending `EntityId` order. That covers death processing (`on_death`, XP, corpse) and room transitions
     (movement, `flee`, `teleport`).
  4. **Example:** A and B tie and each deals a killing blow to the other. Both blows land, both `on_damage` hooks fire,
     and both deaths are processed when the tier ends.
- **Mortally wounded:** An actor whose condition became `dead` during a tier, waiting for end-of-tier death processing,
  is *mortally wounded*. It still takes its own action, and can still be targeted. If a same-tier heal brings it back
  above 0 before the tier ends, it doesn't die.
- **Death beats movement:** If an actor is still mortally wounded when its tier ends, its death is processed and any
  deferred room transition of its own (move, flee, teleport) is dropped. If it was healed above 0, the transition goes
  ahead.
- **Stuns between tiers:** A stun or incapacitation applied during a tier stops the target from acting in *later* tiers,
  but never stops a same-tier action.
- **Recovery during a stun:** When a stun starts, the target's `waitState` is set aside and cleared. This includes the
  `lag / 2` from a cast the stun interrupted (§5.3). When the last `stunned` affect expires or is removed, the set-aside
  value is restored as `waitState = max(waitState, held)`. So recovery doesn't count down during a stun, and an
  interrupted cast never resolves. A cast whose `interrupt.stun` is false is not broken; it stays paused, and its warmup
  ends that many pulses later. The action queue is kept, and queued actions run once the stun ends.
- **Determinism:** The rolls use the seeded `rng`, so the same seed and inputs always produce the same order. That's
  what keeps replays exact.
- **Template:** The template ruleset uses `dex` plus `level`, with no roll (§4.6).
- **Visibility:** `@trace rules` prints each round's initiative order, and `Char.Status` carries the character's own
  initiative.

### 5.5 Intra-Room Spatial Model

All combat is self-contained within a single room. No projectiles cross exits. Spatial bands and room track geometry are
defined in [Spatial.kt](../core/src/main/kotlin/gups/core/model/Spatial.kt) and movement verbs in
[Movement.kt](../core/src/main/kotlin/gups/core/world/Movement.kt). The track, bands, `advance`, `retreat` and pinning
are the `track` feature; without it every room has one slot (§3.10), and only room transitions below apply.

```
Room Track Index:     [ 0 ]            [ 1 ]            [ 2 ]            [ 3 ]
Room Layout:         (Front)          (Mid 1)          (Mid 2)          (Back)
Entities:          [Player A]       [Goblin 1]                       [Goblin Archer]

Player A sees:  [MELEE] (nobody)   [NEAR] Goblin 1   [FAR] Goblin Archer
```

- **Track size:** Each room's `size` sets its track size. In a `CRAMPED` room everyone shares index 0, so all combat is
  melee.
- **Bands:** Viewers never see raw indices (except builders, via `@where`). `look` and `Room.Bands` group actors and
  items by band: `|X−Y| = 0` is `MELEE`, `1` is `NEAR`, `≥ 2` is `FAR`.
- **`advance <target>`:** Steps 1 index toward the target. It's a normal tick-lane action, with lag from
  `rules.advance_lag`. It's a no-op with a message if the actor is already at the target's index. With no target, only
  hostiles are considered.
- **`retreat [from <target>]`:**
  - Steps 1 index away from the target, with lag from `rules.retreat_lag`. With no target, the threat is the nearest
    hostile by track distance, then lowest id; with none, "You have nothing to retreat from." and no lag.
  - On a shared index the actor steps toward the side with more room; a tie goes toward the back (higher index).
  - It's impossible at a track edge, and the actor is told so with no lag.
  - **When pinned** (a hostile shares the actor's index), it requires a `retreat_check` roll against the first opponent
    at the actor's index. Failure applies `rules.retreat_fail_lag` lag and no movement.
- **Room transitions:**
  - Non-combat movement costs one action plus the vitals `rules.move_cost` returns (§4.6). The action is the action
    pulse itself: walking leaves no recovery lag.
  - Arrivals are placed at the exit's `entryIndex`: by default `0`, and `-1` maps to `trackSize−1`. When the destination
    is smaller, the index is clamped.
- **Pinning and exits:**
  - A normal exit command works from any index **unless a hostile is at the actor's index**.
  - A pinned actor must `retreat` first, or `flee`.
- **`flee`:**
  - Warmup 0. It rolls `flee_chance` and ignores pinning.
  - **On success:** the actor picks a random passable exit (closed or locked doors are excluded). The exit is then
    checked like a walk: guards (§4.7), then `before_exit` and `before_enter`. If they allow it, the actor leaves
    through it and engagement ends; a denial shows its message and counts as a failure, so combat can't be used to slip
    past them.
  - **On failure:** lag from `rules.flee_fail_lag`.
- **Items on the ground:**
  - Items sit at the dropper's index.
  - `get` uses `rules.get_lag(band)`. The default is 0 for `MELEE`, 2 for `NEAR`, and not allowed at `FAR`.

### 5.6 Event Dispatch

Event propagation and hook dispatch are handled by [Hooks.kt](../core/src/main/kotlin/gups/core/world/Hooks.kt):

- **Order:**

  1. The acting entity's own hook.
  2. For `before_*` hooks, the actor performing the action (`ctx.actor`), if it isn't the acting entity. `on_*` hooks
     skip it, so an actor never observes its own events.
  3. The room's hook.
  4. The other actors in the room, ascending `EntityId`.
  5. The items in the room, ascending `EntityId`. Then the items carried or equipped by the acting entity (if an actor),
     the actor performing the action, and the other actors in the room, grouped by owner in that order and ascending
     `EntityId` within each owner. The performing actor's items fire for `on_*` hooks too, even though the actor itself
     is skipped.

  For `before_*` hooks, the first `deny` short-circuits.

- **Containment:** Exceptions from the script host are always logged as script diagnostics and treated as "allow", even
  in strict mode. Strict mode rethrows only engine exceptions.

- **Timing:** `before_*` hooks run when the action is *executed* (popped), not when it's enqueued.

- **`on_*` hooks** observe the post-change state. They can't undo it, only issue new verbs.

- **Cascades:**

  - Verbs that cause events (`damage` → `on_damage` → `on_death` → loot `on_spawn`) dispatch synchronously, nested.
  - The maximum nesting depth is `CASCADE_MAX_DEPTH`
    ([TimeConstants.kt](../core/src/main/kotlin/gups/core/TimeConstants.kt)). Every script entry counts toward it:
    dispatched events, self-only hooks (`on_tick`, `on_spawn`, …), and timer and affect callbacks. An entry that would
    nest deeper is dropped with a diagnostic.
  - All nested hooks share the top-level event's step budget (§6.1).
- **Quarantine:** Hooks of a quarantined package aren't invoked.

### 5.7 Perception

Sensory perception and deferred event propagation are implemented in
[Perception.kt](../core/src/main/kotlin/gups/core/world/Perception.kt).

An event starts in a room on a **channel** with an **intensity**, spreads through the room graph, and weakens on every
exit it crosses. Every simulated actor it reaches, player or mob, is asked whether it can perceive it.

- **Senses:** every actor has a score per channel. The built-in channels `sight` and `sound` default to 1, and channels
  the world declares (§3.9) to 0. `mob(senses = {...})` and the `senses` of `starting_stats` merge over those defaults,
  with scores of 0 or more: `senses = {"sound": 3}` leaves sight at 1, and `senses = {"sight": 0}` blinds. A score of 0
  means the actor cannot sense that channel at all, even in the room of the event.
- **Sense deltas:** `sense_deltas` on equipped items, on affects (`apply_affect`, `ctx.apply_affect`) and on traits
  (`PluginTrait.senseDeltas`) change a score while they last. The effective score is `max(0, base + traits + affects +
  equipment)`; `actor.senses` gives it for every channel and `actor.base.senses` the unmodified scores.
- **Events:** each has a channel, an intensity, a room, an optional actor (who caused it and never perceives it) and
  optional text:

|                 Event                 | Channel |        Intensity        |
|---------------------------------------|---------|-------------------------|
| `say`                                 | `sound` | 1                       |
| `emote`                               | `sight` | 1                       |
| `enter`, `exit` (visible movement)    | `sight` | 1                       |
| `combat_start`, `combat`, `death`     | `sound` | 2                       |
| `shout`                               | `sound` | 3                       |
| `open`, `close`, `lock`, `unlock`     | `sound` | `rules.door_intensity`  |
| `ctx.emit(channel, event, intensity)` | any     | the script's, default 1 |
| `whisper`, `tell`                     | —       | never perceived         |

`whisper` and `tell` are never propagated or sent to `on_perceive`.

- **In-room messages:** the built-in commands tell the players of the event's own room through the same
  `rules.can_perceive` check, so senses apply there too. Each player is tried channel by channel in `channel_priority`
  order and gets the first line for a channel they pass. The context's intensity is 2 for combat, the door's own for
  doors and containers, and 1 otherwise.

|                                                                                            Event                                                                                            |                              Channels                               |
|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------|
| `emote`, `enter`/`exit` (also teleport, respawn and a summon fading), `drop`, `give`, `put`, `wield`, `wear`, `get`, `advance`, `retreat`, `cast`, item decay, `incapacitated`, `recovered` | `sight`                                                             |
| `whisper` (the gesture seen by bystanders; its words go only to the target)                                                                                                                 | `sight`                                                             |
| `say`                                                                                                                                                                                       | `sound`                                                             |
| `combat_start`, `combat`, `death`                                                                                                                                                           | `sight`, then `sound`                                               |
| door or container `open`, `close`, `lock`, `unlock`                                                                                                                                         | `sight`, then `sound` (only when `rules.door_intensity` is above 0) |

A `say` has no sight alternative, and `shout` has its own delivery.

- **Event names and `ctx.actor`:** `say`, `emote` and `shout` carry their text, and the actor is the speaker.
  `combat_start`: the attacker. `combat` (once per room per round): no actor. `death`: the one who died. `enter` and
  `exit`, including teleports: the mover. `open`, `close`, `lock` and `unlock`: whoever did it.
- **Propagation:** a shortest-path search from the room of the event, up to `BUBBLE_RADIUS` exits
  ([TimeConstants.kt](../core/src/main/kotlin/gups/core/TimeConstants.kt)). A hop from a room to a neighbour whose exit
  leads into it asks `rules.sense_cost` (§4.6) with the neighbour as `ctx.room` and that exit as `ctx.exit`. A negative
  price blocks the hop; otherwise it adds to the cost of the path, and 0 is a free hop. Each room is reached by its
  cheapest path of at most `BUBBLE_RADIUS` exits, so a cheaper but longer path never hides a dearer one that is within
  range. The exit priced is the listener's own exit toward the source, so a one-way exit into the source room carries
  events and one out of it doesn't. The template's `sense_cost` blocks `sight` through a closed door unless its tags say
  `transparent`, muffles `sound` by the door's extra cost, and blocks everything through an exit tagged `sealed` (and
  `sound` through one tagged `soundproof`); the engine itself reads no tags.
- **Who perceives:** for each simulated actor in each reached room (the room of the event, at cost 0, included), the
  engine asks `rules.can_perceive` whenever the answer would be used: for a mob with an `on_perceive` hook, and for a
  player in another room or, for a `shout`, in the event's own room. The template needs a score above 0, and in other
  rooms `intensity + score > cost`.
- **Delivery:**
  - It's **deferred** to phase 5 of the current pulse. Events raised while phase 5 delivers wait for the next pulse's
    phase 5.
  - A mob with an `on_perceive` hook gets it with `ctx.event`, `channel`, `intensity`, `cost`, `actor`, `source_room`,
    `via_exit` (the mob's own exit toward the source, `None` in the same room) and `text`.
  - A player in another room is sent whatever `rules.perceive_message` returns, if it is a non-empty string. The room of
    the event is never sent this, except for a `shout`, which the speaker is told of directly and everyone else, in the
    room or not, hears this way (`ctx.via_exit` is `None` in the speaker's room). For any other event the built-in
    commands tell the players of the room as they happen, so reactions appear in causal order. Each player is tried
    channel by channel in `channel_priority` order and is told through the first channel they pass `rules.can_perceive`
    for and that has text, and through no other. A built-in channel's text is the engine's own message, which exists
    only on the channels an event has: `say` is heard, `emote` and most actions (`enter`, `exit`, `whisper`, `drop`,
    `give`, `put`, `wield`, `wear`, `get`, `advance`, `retreat`, `cast`, item decay, and a character falling
    incapacitated or recovering) are seen, and combat, deaths and doors have a sight and a sound line (a door's or
    container's sound only when `rules.door_intensity` is above 0). A world channel asks `rules.perceive_message`
    (`None` or an empty string for nothing) with `ctx.channel` set, so a psionic can feel a hit that a blinded player
    can't see. A player who passes no channel with text is told nothing. Link lost, reconnect and quit notices and the
    admin tools' lines are not events and are told to everyone.
- **Doors and containers:** `open`, `close`, `lock` and `unlock` run `before_*` (which can veto with `ctx.deny`) and
  `on_*` hooks, on the door's room, the container item and the people in the room. A door's sound starts in the room of
  the actor and in the room `exit.to` names, whether or not that room has an exit back. Each room is reached from
  whichever origin is cheaper, so a listener on the far side is in the event's own room: it gets no `via_exit`, and is
  told only by the door's own echo. A container's sound starts only in the actor's room. `rules.door_intensity` prices
  it from the door's or container's `tags`.
- **Notify only:** a mob can never veto from another room.
- **Budget:** the hook runs against the *listening* mob's package for step budget and quarantine.
- **Deduplication:** events are coalesced per (listener, event, source room) per pulse, keeping the strongest
  (`intensity - cost`), and the first on a tie.

### 5.8 Mob AI

Autonomous NPC behavior and think cycles are implemented in
[Behaviors.kt](../core/src/main/kotlin/gups/core/world/Behaviors.kt):

- A mob is "a player controlled by code". It acts only by enqueueing tick-lane actions, and pays the same warmup, lag,
  pinning, retreat, and flee costs.
- **Think cycle:** It runs when the mob is idle (empty queue, `waitState == 0`, not casting) and `pulse − lastThink ≥
  thinkEvery` (where `thinkEvery` defaults to `THINK_DEFAULT_PULSES`,
  [TimeConstants.kt](../core/src/main/kotlin/gups/core/TimeConstants.kt)). Each cycle:
  1. Each behavior proposes at most one action.
  2. Proposals are sorted by priority, then by behavior declaration order.
  3. If `think` is defined, it's called with `ctx.proposals`. A `None` return enqueues the top proposal, an `act_*` call
     overrides it, and `"idle"` skips the turn.
  4. Without `think`, the top proposal is enqueued.
- **Scope:** Thinking only happens for mobs in the bubble or marked active.

### 5.9 Reality Bubble

The reality bubble and room simulation lifecycle are implemented in
[Bubble.kt](../core/src/main/kotlin/gups/core/world/Bubble.kt):

- **Anchors:** Every in-world player (including link-dead ones), plus every entity with a non-empty `anchorReasons`.
- **Active set:** All rooms within `BUBBLE_RADIUS` exits
  ([TimeConstants.kt](../core/src/main/kotlin/gups/core/TimeConstants.kt)) of any anchor's room. Exit tags and door
  state are ignored when computing it. Rooms flagged `always_active` are always in the set.
- **Simulated entities:** Players, and entities with non-empty `activeReasons` or `anchorReasons`, wherever they are.
  Anything else is simulated when its room (following carriers and containers) is active. Pets and followers bonded to a
  player get the reason `bond:<player_id>`.
- **Configuration:** the bubble is a world option, on in `gups serve` and off by default in Kotlin tests, the harness
  and `gups repro` (§2.4).
- **Active vs anchor:**
  - `active` simulates only the entity itself: its queue, timers, AI, and movement. Rooms it passes through stay
    dormant.
  - A simulated entity can't target, fight, or perceive *dormant* entities. They're inert to it until a bubble covers
    them: they can't be found by keyword, aren't opponents, aren't in `enemies_near`/`allies_near`, and are skipped by
    `room`-range skills.
  - `anchor` projects a full `BUBBLE_RADIUS` bubble.
- **Reference counting:** Both sets are ref-counted by reason (`death_mark:<player_id>`). The entity stops being active
  or anchored only when its last reason is cleared.
- **Recomputation:**
  - It's incremental. Anchors are found by a scan of the world each pulse. When an anchor changes rooms, a BFS from the
    old and new rooms produces the set difference. A reload recomputes it in full.
  - Rooms leaving the set go dormant. Rooms entering it wake up. Rooms that sleep are processed before rooms that wake,
    each in ascending id.
  - Changes are applied in phase 6.
- **Startup and restarts:** Rooms start dormant from the boot pulse. After `on_load`, any restored timer running in a
  dormant room is paused, then the bubble forms. Active and anchor reasons are saved with every entity and player, so an
  `active` boss stays active across a restart; dormancy itself isn't saved.
- **Going dormant:**
  - AI, `on_tick`, and `on_combat_tick` stop. Entities with an active or anchor reason keep running.
  - Each `ScriptTimer` converts from `due` to `remaining`, keeping at least 1 pulse to run.
  - Affects are **not** paused. Their `expiresAt` is an absolute pulse, so they keep running out while the room is
    dormant.
  - Combat disengages (§5.4). Mobs drop their action queue and cast; recovery (`waitState`) is kept.
- **Waking (lazy catch-up, never simulation),** in this order:
  1. Timers convert back from `remaining` to `due`.
  2. Mobs recover (below).
  3. Affects that ran out are removed, firing `on_expire` in expiry order, then id: first each mob's own affects, mob by
     mob, then the affects of every item in the room (on the ground, inside containers, or carried by its mobs).
  4. A missed zone reset is applied to the room: its spawns, then `on_reset`.
  5. Respawns that are due fill.
  6. Items that decayed go.
  7. `on_wake(ctx, elapsed_pulses)` fires on the room, then its mobs, then its items (each ascending id), as they were
     before the respawns.
  - **Mob vitals** recover according to the mob's `dormant_recovery`:

    |       Mode       |                                                           On wake                                                           |
    |------------------|-----------------------------------------------------------------------------------------------------------------------------|
    | `full` (default) | Vitals reset to max, unless the mob has a `no_regen` affect. Classic MUD behavior.                                          |
    | `regen`          | Catch-up regen for the elapsed world ticks, computed with `rules.regen` and the modifier chain, so slowed regen stays slow. |
    | `none`           | Vitals stay exactly as they were when the room went dormant.                                                                |

  - With a `no_regen` affect still running at wake, `full` falls back to the piecewise catch-up, which then restores
    nothing while the affect lasts.

  - **Catch-up is piecewise.** The elapsed time is split at every affect expiry that fell inside it, and regen is
    computed per segment with the affects active in that segment. A 15-minute `no_regen` affect therefore suppresses
    exactly 15 minutes of healing, then normal regen resumes.

  - The world ticks counted are those after the room went dormant, up to and including the waking pulse. An affect
    counts as active at a tick when its `expiresAt` is later than the tick, matching live play, where expiry (phase 1)
    comes before regen (phase 4).

  - **Raid boss example:** when the boss drops below a threshold, its script applies `apply_affect(boss, "weakened",
    duration=3600, flags=["no_regen"])`. The boss stays weakened for 15 minutes even if the party runs far away,
    whatever its `dormant_recovery` mode. If the boss should also keep *acting* while players are away, mark it `active`
    instead (see "Active vs anchor" above).

  - Nothing that would have happened while dormant is replayed. In particular, there's no offscreen wandering.

- **Pathfinding:** `ctx.path_to` runs a BFS over the full room graph, over exits in declaration order (so ties resolve
  the same way every time) and through open doors only: closed and locked doors block. Results are cached per `(source,
  dest)`. A door change and a reload clear the cache.

### 5.10 Hot Reload and Deploy

Instance reconciliation and prototype swapping are implemented in
[Reconcile.kt](../core/src/main/kotlin/gups/core/world/Reconcile.kt), and the production git deployment workflow in
[Deployer.kt](../cli/src/main/kotlin/gups/cli/Deployer.kt).

**`--dev` auto-reload:** The file watcher (§2.5) reports changes once nothing has changed for 200 ms, grouped by
package: the nearest directory with a `package.star`, or the file's own directory if that file was just deleted. It then
posts `Reload(changedPackages)`, which carries package paths (`zones/forest`), since a reload swaps whole packages.

**Reload algorithm** (on the world thread):

1. Re-evaluate every live package plus the named ones, together, into fresh, frozen prototypes in isolation, so labels
   across packages are checked against the new code. Every label must resolve, including cross-package exits and loads,
   and `api_version` must match.
2. **On failure:** Any load error, or a result with no rooms, refuses the whole reload. Keep the old version live.
   Report the errors with file, line, and a caret snippet, in game to every connected builder and instructor and in the
   `@errors` log. Stop here.
3. **On success:** Atomically swap the prototype table entries. A package whose `package.star` is gone is removed,
   unless something still refers to it.
4. Reconcile live instances (§3.4): run migrations, overlay deltas, and create tombstones.
5. Rebind `HookRef`s by `(file, name)`. Timers whose function no longer exists are dropped with a `reload` diagnostic.
   Hooks fire against the new code from the next event onward.
6. Clear quarantine for the reloaded packages (a `--dev` save counts like a deploy).
7. Invalidate the path cache and recompute the bubble, then take a periodic checkpoint.

**Production deploy:**

1. After a merge, CI calls `gups deploy --commit SHA` (through `POST /deploy`), or an instructor runs `@deploy
   [commit]`. Without a commit, the fetched tip of `deploy_branch` is deployed; any commit deployed must be on that
   branch. Only one deploy runs at a time, each on a thread of its own; a second is refused at once. Deploys run only in
   `--prod` when the world is a git working tree; otherwise `@deploy` says why. The engine shells out to the `git`
   binary.
2. **Fetch:** `git fetch --no-tags <repository or origin> +refs/heads/<branch>:refs/remotes/gups-deploy/<branch>`, with
   the deploy key through `GIT_SSH_COMMAND` (`IdentitiesOnly`, `StrictHostKeyChecking=accept-new`). Only that branch is
   fetched, so a commit the server doesn't know is reported as not on the branch.
3. **Checks:** before anything is staged, the deploy is refused if the commit isn't an ancestor of the fetched tip, if
   the live tree has uncommitted changes to tracked files, if `MODULE.gups` or `MESSAGES.gups` differs between
   `meta.world_commit` (or `HEAD` if none) and the commit (an engine upgrade, a schema or feature change, or new
   messages need a restart), or if any changed file is under `plugins/` (plugin changes need a restart, §4.15).
4. **Changed packages:** each changed file is charged to the deepest package directory, old or new, that contains it.
5. **Staging:** a `predeploy` backup, then `git worktree add --detach` into a temporary directory. Every package is
   loaded there off the world thread, and any error refuses the whole deploy. The worktree is always removed afterwards.
6. **Swapping:** a `Task` on the world thread runs the reload's swap, so prototypes swap, live instances reconcile, and
   the changed packages leave quarantine. All packages succeed or none are swapped. Plugin commands, effects and GMCP
   producers live on the `World`, so they survive the swap and are never installed twice.
7. **After the swap:** `git checkout --detach` moves the live tree; `meta.world_commit` is set (saved with every
   checkpoint); CODEOWNERS is synced from the live tree, with new scopes applied to characters in play; a routine
   checkpoint is taken; and online instructors get an alert.
8. **Failure:** the world is unchanged. The errors go to the response, to a `deploy` diagnostic (so `@errors`), and to
   an instructor alert.

A change to `MODULE.gups` (an engine upgrade) or `MESSAGES.gups` needs a restart, with the new image for an upgrade, and
a schema or feature change also needs `--wipe` (§3.9). Hot reload is refused for both.

### 5.11 Persistence and Checkpoints

State persistence, checkpoints, and crash consistency are implemented in
[Checkpoints.kt](../persist/src/main/kotlin/gups/persist/Checkpoints.kt) and
[SqlitePersistence.kt](../persist/src/main/kotlin/gups/persist/SqlitePersistence.kt), state restoration in
[Restore.kt](../core/src/main/kotlin/gups/core/world/Restore.kt), and SQLite backups in
[Backups.kt](../persist/src/main/kotlin/gups/persist/Backups.kt).

- **Dirty tracking:** Any change, whether through a verb or engine code, sets the entity's `dirty` flag.
- **Periodic checkpoint** (every `checkpoint_minutes`, default 30, set in `MODULE.gups`; checked at world ticks). Writes
  are kept rare to spare the disk: crashes are expected to be rare, and every graceful shutdown and reload saves.
  1. The world thread runs the §3.8 invariant check, then serializes all dirty, non-`debug` entities, plus
     `zone_states`, the `meta` pulse, `next_entity_id`, `next_handle`, and pending diagnostics, into a `CheckpointBatch`
     of JSON bytes. Entities named in an invariant violation are left out and stay dirty.
  2. It clears the dirty flags and submits the batch.
  3. The writer commits each batch in **one transaction**, in FIFO order.
- **Immediate capture** (at the end of the triggering action's cascade): it captures every entity dirtied during that
  cascade, plus `item_events` rows, at once. The capture is held in memory and written in the same transaction as the
  next periodic checkpoint (or the final one at shutdown).
  - A cascade ends after each input line, after each actor's action in a tier, after the timer phase, and after the
    action phase.
  - Triggers: an item moving from one player's custody to another's, a shop purchase, a bank deposit, a level-up, a
    level lost, character creation or deletion, a change of choices or granted traits (§4.19), and logout or quit.
    (Shops and banks don't exist yet.)
  - The receive and the give are always captured at the same moment and land in the same transaction, which closes the
    classic give-then-crash duplication exploit.
- **The writer:**
  - It never makes the world wait. A batch merges into the last queued one (last write wins per entity) unless that one
    is being written right now.
  - A failed write stays at the head of the queue and is retried with backoff from 50 ms, doubling up to 5 s; later
    periodic captures may merge into it.
  - A failure makes the writer unhealthy until a write succeeds. While unhealthy, new logins are refused (§6.4).
  - If a submitted batch is still uncommitted three world ticks later, one `checkpoint` diagnostic is logged (and
    alerted) until the backlog clears.
- **The mirror:** the persistence layer keeps the last captured row of every entity and player in memory. Logins and
  repro bundles read the mirror, so they never wait on the database or miss a batch that isn't committed yet.
- **Debug entities** are skipped by every checkpoint, so they are never saved and are gone after a restart.
- **Crash guarantee:**
  - At most one checkpoint interval of state is lost (by default 30 minutes), including held immediate captures.
  - An ownership transfer is either saved with both sides or lost with both sides.
  - The database is never inconsistent.
- **Shutdown and reload:** `@shutdown` and a successful reload both perform a final checkpoint (§5.10). A shutdown
  broadcasts its reason, stops the world loop once its delay has passed, takes the final checkpoint on the world thread,
  and waits up to the server configuration's `shutdown_timeout` (default 30 seconds; §2.4) for the writer to commit it.
  A JVM shutdown (Ctrl-C, `SIGTERM`) does the same with no delay. `--dev` always waits 30 seconds. If the commit isn't
  done in time, the server logs "Some changes could not be saved before shutdown." and exits anyway; the uncommitted
  state is lost as in a crash.
- **Backups:**
  - Snapshots are taken with the SQLite online-backup API, as `<kind>-<UTC yyyyMMdd-HHmmss>.db` in `backups/` next to
    the database.
  - Kinds: `hourly` (keep 48), `daily` (keep 14), `manual` (`gups backup` and `@snapshot`, never pruned), `predeploy`
    (each deploy, never pruned) and `prewipe` (each `--wipe`, never pruned).
  - The server checks every 5 minutes, and once at startup, whether the newest snapshot of a kind is older than its
    period.
  - `gups restore` rolls the world back, for example to before a broken merge.
- **Startup:**
  1. Open the database and run engine schema migrations.
  2. Load the course world packages.
  3. Restore `world_entities` (not players). With `--fresh`, first delete every saved row that isn't a player's or a
     player-owned item, and rewrite zone state; the pulse and the id counters are kept, so saved characters' ids and
     deadlines stay valid.
  - Room rows whose room is gone (or got a different id) are deleted.
  - Items are placed after their holders, containers before contents. Rows that can't be placed are dropped.
  - Mobs and ground items of deleted prototypes are dropped.
  - Saved spawn bindings and respawn deadlines are restored. A rule that is short with no respawn pending (for example a
    new rule) fills at once.
  - Quarantine is restored from `zone_states`.
  4. Reconcile all instances against prototypes.
  5. Set `pulse` from `meta`.
  6. Fire `on_load` (§4.4). Every room starts dormant. The bubble forms as players log in.
- **Characters load at login,** not at startup. A player's row, and every item whose location chain ends in that player,
  are restored from the mirror when the player logs in. A player whose room is gone is placed in their recall room, or
  else the first room. On logout the character and everything it carries are captured in an immediate batch, then
  removed from the world; their rows stay.

### 5.12 Sessions and Authentication

Authentication and session management are implemented in
[AccountLogin.kt](../auth/src/main/kotlin/gups/auth/AccountLogin.kt), password hashing in
[Passwords.kt](../auth/src/main/kotlin/gups/auth/Passwords.kt), login rate limiting in
[LoginLimiter.kt](../auth/src/main/kotlin/gups/auth/LoginLimiter.kt), and the interactive character creation flow in
[CreationFlow.kt](../core/src/main/kotlin/gups/core/world/CreationFlow.kt).

- **Creating accounts:**
  - `gups import-roster roster.csv` reads the columns `login,github_handle,role` (only `login` is required; `role`
    defaults to `player`). New logins become accounts without a password, each with a **setup code**: a one-time, 7-day
    invite bound to that account, printed for the instructor to hand out. Students choose their own password with it;
    the server never hands out a usable password. Existing logins have their GitHub handle and role updated, and their
    passwords left alone. An optional `disabled` column disables or re-enables listed accounts (§4.13).
  - `gups invite --role R [--days N]` and `@invite <role>` make an unbound invite code (default 7 days). Only its hash
    is stored.
  - Open signup (`open_signup = True`) lets a new login be created at the telnet login prompt without a code. It is off
    by default (§1.7).
  - There is no self-service reset: `@role <account> reset` issues a new setup code. Players who know their password
    change it with `password` at the character menu.
- **Login:**
  - The password is verified with Argon2id (m = 64 MiB, t = 3, p = 1, a 16-byte salt and a 32-byte hash), stored as the
    standard PHC string `$argon2id$v=19$m=65536,t=3,p=1$…`. The implementation is Bouncy Castle's pure-Java generator,
    so the jlink images need no native code.
  - Passwords have at least 10 characters and don't equal the login.
  - **Lockouts:** failures are counted per lowercased login (whether or not it exists) and per peer address. The count
    resets after 15 minutes without a failure, so failures spaced less than 15 minutes apart keep accumulating. The
    fifth starts a 1-minute lockout, and each later one restarts the lockout at double length, capped at 15 minutes. A
    successful login clears the account's count but not the address's. Bad invite codes count against the address only.
    Telnet and web logins share one limiter. A locked-out attempt is refused without hashing, so an attacker can't spend
    the server's CPU. The counts live in memory only; a restart clears them.
  - A login that doesn't exist still costs one hash of a fixed dummy value, so response time doesn't reveal which logins
    exist.
  - An account without a password (a roster account not set up yet) fails like a wrong password, after a dummy hash, so
    it looks like any other wrong login.
  - A disabled account gets "This account is disabled." after a correct password.
  - The web client may exchange a successful login for a 30-day session token (§4.10): 32 random bytes as unpadded
    base64url, stored as SHA-256 hex with a wall-clock expiry. Expired rows are pruned whenever a token is added. A
    token stops working when it expires, at logout, when the password is changed or reset, at `@role revoke`, and when
    its account is disabled, which deletes it.
- **Telnet login flow:**

  ```
  By what name do you wish to be known? (login, or "new" with an invite code)
  > ann
  Password: (echo off)
  > ********
  Your characters: Ann, Tilly.  Type a name to play, "new" to create, or "password" to change yours.
  > ann
  Welcome back, Ann.
  ```

  - Prompts: the login prompt, then the character menu with the prompt `> `. `quit` at the menu closes the connection.
    An empty answer at a prompt asks again; at the character-name prompt it goes back to the menu. 60 seconds idle at
    any prompt closes the connection.
  - Echo is turned off for passwords (§4.10); clients that refuse simply see their typing.
  - Three strikes per connection close it. Strikes are wrong passwords, refused locked-out attempts, bad codes, and a
    wrong current password when changing it.
  - Password change: current, new, new again, then one background step that verifies and rehashes. A wrong current
    password counts as a failed login for the limiter.
  - `new` asks for a setup or invite code, then a login (unless the code is bound to an account), then a password twice.
    A bound setup code shows which login it sets up. Logins are lowercased. A redeem that loses a race reports it and
    goes back a step.
  - Slow work (hashing, account lookups, code checks, login-taken checks) runs on the background pool (§2.5). Lines
    typed while it is pending are held (at most 20) and replayed in order when it finishes: to the login, or as game
    commands once a character is playing.
- **Characters:** After login, the player chooses a character, or creates one with `new`: a name, then the creation
  steps if the world has a creation provider (§4.19), then the stats `rules.starting_stats` gives it (§4.6). Without a
  provider there are no steps. The menu lists saved characters plus any of the account's characters in the world not
  saved yet. Creating a character triggers an immediate checkpoint. The character is placed in its saved room, which
  becomes an anchor; new characters start in the provider's start room for their answers, or else the world's start room
  (§2.3). The role and idle exemption come from the account each time a character is entered. Loading a saved character
  is refused in maintenance mode.
- **Creation steps:** one prompt per choice that has an available option, in order, then a confirmation:

  ```
  > new
  What will your new character be called? Ann
  Choose your race:
    1. Human   Adaptable and ambitious.
    2. Elf     Quick, keen-eyed and long-lived.
    3. Dwarf   Stout, stubborn and hard to kill.
  Type a number or a name, "help <name>" to learn more, "back" or "cancel".
  > elf
  Choose your class:
    1. Warrior  Steel and stamina.
    2. Mage     Spells, and little armor.
  Type a number or a name, "help <name>" to learn more, "back" or "cancel".
  > 2
  Ann: Race Elf, Class Mage. Create this character? (yes, back or cancel)
  > yes
  Welcome, Ann.
  ```

  - **Options** are listed available ones only, numbered from 1, with the label padded to the longest one's width and
    then the summary. An answer is a number, an option id or a label, compared case-insensitively, or a prefix of
    exactly one id or label. Anything else gets "There's no <answer> to choose here." and the list again.
  - `help <name>` shows that option's label and its `help` (or its summary when it has none), then asks again. `help`
    alone shows the hint line.
  - `back` goes to the previous step that was asked; from the first choice it goes back to the name prompt, keeping the
    answers. Answering a choice again clears the answers after it, since what is available may change.
  - `cancel` returns to the character menu and creates nothing.
  - The confirmation lists every answer as `<title> <label>`, in choice order, separated by ", ". `yes` (or `y`) creates
    the character, `back` returns to the last choice asked (for a new character whose answers were all kept, the last
    one answered), and `cancel` returns to the menu. Anything else asks again. Kept answers last only until that attempt
    ends: the next `new` starts with none.
  - On `yes` the provider validates the answers. A refusal is shown and the player goes back to the last choice. The
    name is then checked again; if it was taken meanwhile the player is told and asked for another name, keeping the
    answers. Then come the character limit and maintenance checks, as for any creation.
  - The character is built with its answers set, so its traits apply; then `rules.starting_stats` runs (§4.6). A vital
    `starting_stats` gives no `current` for starts at its effective maximum. The character enters the world, and then
    the provider's `created` callback runs (§4.15).
  - Every step is a prompt for the 60-second idle rule. A line typed while the account step before it is still pending
    is held and replayed, as for the other prompts.
  - The engine texts are message catalog entries (§4.18), in the `login.creation.*` group: `hint`, `prompt` (`> `),
    `no_such_option` (`{answer}`), `confirm` (`{name}`, `{answers}`), `provider_failed` and `has_choices` (`{name}`).
    `score` adds `score.choice` (`{title}`, `{label}`) and `score.traits` (`{traits}`). Choice titles, prompts, labels,
    summaries and help come from the provider.
- **Missing choices:** a saved character with a missing choice (§4.19) is taken through just those steps, then the
  confirmation, when the player chooses it at the menu: "Ann has choices to make before playing." On `yes` the answers
  are checked as a change (§4.19 step 1); a refusal is shown and the player returns to the menu. Otherwise the character
  enters the world as usual, the answers are set as a change (steps 2 to 5, so a worn item the new answers refuse is
  moved to the inventory with its message), and `created` runs with `newCharacter = false`. An earlier answer to a
  choice the steps skipped is removed; answers to choices the provider no longer defines are kept (§4.19). `cancel` goes
  back to the menu without entering. A character taken over while already in the world isn't asked.
  - **One character at a time:** an account plays one character at a time, across every transport. Choosing a different
    character while one is in the world (link-dead included) is refused with "You're already playing Ann." Instructors
    are bound by this too (only `--dev` is exempt).
  - A character already playing is taken over: the old connection is told why and closed.
- **Disconnects:**
  - A dropped connection makes the character **link-dead**. It stays in the world, keeps anchoring its bubble, and keeps
    fighting with auto-attacks only, for `LINKDEAD_PULSES`
    ([TimeConstants.kt](../core/src/main/kotlin/gups/core/TimeConstants.kt)). Disconnecting clears the action queue, and
    can't be used to escape combat.
  - Reconnecting within that window resumes the session.
  - Otherwise the character auto-quits, with an immediate checkpoint. The auto-quit ignores combat.
- **Idle players:** After `IDLE_QUIT_PULSES` without input
  ([TimeConstants.kt](../core/src/main/kotlin/gups/core/TimeConstants.kt)), a player is auto-quit. Instructors are
  exempt.
- **`--dev`:**
  - With a database, `--dev` creates the account `instructor` (role instructor, no password) on first boot. Every dev
    character belongs to it (dev characters saved before it existed join it), so each gets the instructor role and is
    idle-exempt. Without a database (tests) characters have no account.
  - Only connections from `127.0.0.1`/`::1` are accepted; any other address is refused and closed.
  - The server asks for a character name: 2 to 16 letters, capitalized. Naming a character that is already playing takes
    it over and closes the old connection. Otherwise a saved character is restored, or a new one is created and saved at
    once. With a creation provider, a new character, or a saved one with a missing choice, goes through the creation
    steps first, as above; `cancel`, a refusal or a provider failure asks for a name again.
  - The one-character-at-a-time rule doesn't apply: several characters at once help testing.
  - While the database can't be written, new logins are refused with a maintenance message; taking over a character
    still works.

### 5.13 Developer Tooling Behavior

Interactive developer tooling, tracing, and dynamic evaluation are implemented in
[DevTools.kt](../script/src/main/kotlin/gups/script/DevTools.kt) and
[Tools.kt](../core/src/main/kotlin/gups/core/world/Tools.kt).

- **`@trace`:**
  - Streams one line per hook invocation, verb call, and rules chain evaluation, with step counts.
  - Scope: on the shared server, the builder's own packages plus their own character's rules lines. Instructors, admins
    and `--dev` see everything.
  - Filters: `zone` means the given package, or else the package of the caller's room. `entity here` traces the room
    itself. `hook` matches a hook name, or a command or timer prefix such as `command` or `timer`.
  - `rules` streams each rules function and modifier call, and one line per evaluation with its full chain. Auto-attacks
    add the to-hit roll and the mitigated damage; each combat round adds the initiative order.
  - It's rate-limited to 50 lines per pulse per session. The rest are counted, and the count is reported with the next
    traced line or at `@trace off`.
- **`@eval`** (`--dev` only): Evaluates Starlark with a live `ctx` bound to the caller's character. Its verbs mutate the
  live world.
  - The input is evaluated as an expression and its `repr` is shown; anything that isn't an expression runs as
    statements. `print()` output comes first.
  - `ctx`, `self` and `room` are predeclared, along with the package builtins (effect primitives, constants) and plugin
    globals. Each call gets a fresh module.
  - Relative labels and attribute keys belong to the package of the caller's room.
  - Limits: 1,000,000 steps and 1 s, shared with any hooks its verbs trigger.
  - Errors are shown to the caller only. They are not diagnostics and don't count toward quarantine.
- **`gups docs`:** Emits the HTML reference, tagged with `api_version`, plus the changelog.

---

## 6. Error Handling & Invariants

### 6.1 Script Resource Limits

Resource bounds, deterministic step accounting, wall-clock backstops, and boundary allocation caps are enforced by
[Limits.kt](../script/src/main/kotlin/gups/script/Limits.kt):

- **Deterministic step budgets:** Execution step limits apply per top-level event and its synchronous cascade (zone
  hooks, `think`, `on_perceive`, timers, and commands). The step counter is deterministic so the test harness and
  production environments agree exactly on execution bounds. Package loading and interactive `@eval` have dedicated step
  limits.
- **Wall-clock backstops:** A wall-clock guard catches slow host calls. It is measured from the beginning of the
  top-level event or package load and checked at every `ctx` verb call and declaration call. The test harness disables
  wall-clock checks to guarantee reproducible execution.
- **Rules evaluations:** Each rules function invocation and each modifier evaluation receives an independent step and
  wall-clock budget, even when triggered from within a hook cascade. Failures are charged to the package where the rules
  function or modifier file resides.
- **Package charging:** Diagnostics and quarantine error counts are attributed to the responsible package: hooks charge
  to the owner entity's prototype package; timers and affect hooks charge to the script owner or function file; and
  commands charge to their declaring or registering package.
- **Boundary allocation caps:** Values crossing from Starlark into the engine (strings, lists, dicts, timer arguments,
  and serialized entity `customAttributes`) are checked against boundary size caps to prevent memory exhaustion, raising
  an `alloc_limit` error when exceeded.
- Starlark's language invariants guarantee frozen module globals, no I/O access, and no unbounded recursion.

### 6.2 Script Error Handling

When a hook raises an error, exceeds a limit, or passes a bad verb argument:

1. The hook aborts. Verbs it **already applied stay applied**: there's no rollback, and each verb is individually atomic
   and invariant-preserving. Authors are taught to do their checks first and their verbs last.
2. The triggering action **proceeds as if the hook returned `None`**. A crashing `before_exit` never traps a player.
3. A diagnostic row is written with the next checkpoint batch, periodic or immediate. It contains:
   - The package, prototype label, hook, file label, line, and kind.
   - The message and Starlark stack.
   - A one-line `ctx` summary.
   - The seed and pulse, and the room the owner was in.
   - A **repro bundle**: the seed, the failure pulse, the pulse of the last periodic checkpoint, the hook and message,
     and the failing room's rows as of that checkpoint (the room, the mobs and players there, and every item in it or
     held by them, transitively). It also holds the inputs (tick-lane commands and `say`s, never tells) since that
     checkpoint by actors in rooms of the failing package.

   Repro is exact in the harness and best-effort from production.

   - **Script kinds:** `script_error`, `step_limit`, `wall_clock`, `alloc_limit`, `missing` (a hook or timer function no
     longer exists) and `quarantine`. The kind `script` is for failures of Kotlin-hosted handlers. Other kinds include
     `engine`, `invariant`, `reload`, `checkpoint`, `deploy` and `plugin`.
   - **`gups repro`:** builds a headless world from today's packages with the bundle's seed. It starts at the checkpoint
     pulse, with ids above the bundle's and no spawns, and doesn't fire `on_load`. It restores the bundle and replays
     each input by actor id at its pulse, running 16 pulses past the failure. It counts as reproduced when a diagnostic
     with the same hook and message appears; the exit status is 1 otherwise. Players come back without a connection, so
     output to them is dropped.
4. **Who sees what:**
   - **Owner alerts:** online builders (not instructors) whose scope has the package get a one-line in-game alert for
     `script_error`, `step_limit`, `wall_clock` and `missing`, with at most one per package per 10 s.
   - **Instructor alerts** go to online instructors and admins as `[alert] ...`, and always to the server log, at most
     once a minute per kind. Kinds include `quarantine`, `rules_error` (at most once a minute per failing rules
     function), `checkpoint`, `engine_errors`, `overrun`, `plugin_load` and deploy results.
   - Players see nothing. For a failed zone command, they see "Nothing happens."

### 6.3 Quarantine (circuit breaker)

- **Trigger:** More than 20 errors from one package within 60 s. The window is 240 pulses of game time, so the harness
  and production agree; the 21st error inside it quarantines.
- **Effect:**
  - Its hooks allow, its zone and global commands answer "Nothing happens.", its `think` falls back to the top proposal,
    and its affects' `on_tick` and `on_expire` are skipped (the affects still tick down and expire).
  - Its timers stay pending: the world skips them while due, and they fire on the first timer phase after the quarantine
    clears.
  - Its rooms, exits, items, and mobs remain as static content. Mobs fall back to their built-in behaviors only.
- **Notifications:** Instructors (a `quarantine` alert) and the package's owners are notified.
- **Clearing:** Quarantine clears on the next successful deploy or reload of that package, or by `@unquarantine`.
  Quarantine state persists in `zone_states`; `@quarantine` and `@unquarantine` take a checkpoint so it does.
- **Rules package exception:** the rules package is never quarantined; there is no built-in ruleset to fall back to
  (§4.6).
  - **Load:** a rules package that fails to load, or fails its dry run, stops the server at startup. A reload or deploy
    with such a package is refused, and the previous rules stay live.
  - **Run time:** a rules function that throws, or returns the wrong type or an undeclared name, records a
    `script_error` diagnostic against the rules package, raises a `rules_error` alert, and substitutes a neutral
    fallback result. Under the **safe-neutral-fallback principle**, rules errors never hurt, kill, reward, or trap
    actors: attacks miss (`to_hit = 0`), no damage or vital loss occurs, movement and lag penalties are zeroed,
    perception hops are blocked, casts fail harmlessly, and player deaths fall back to safe respawns at effective vital
    maxima without XP or level loss. Complete neutral fallback mappings and host integration are documented in
    [Rules.kt](../script/src/main/kotlin/gups/script/Rules.kt) and
    [Host.kt](../script/src/main/kotlin/gups/script/Host.kt).

### 6.4 Engine-Side Failures

|                     Failure                     |                                                                                     Handling                                                                                     |
|-------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Uncaught Kotlin exception in a command or phase | Log with context as an `engine` diagnostic and abort that action only. The pulse continues. More than 100 within the last minute (240 pulses) triggers an `engine_errors` alert. |
| Checkpoint write fails                          | Retry with backoff. The batch stays pending, and later captures merge into it (§5.11). Alert after 3 world ticks. Never block the world thread.                                  |
| Disk full / database unwritable                 | As above. Also refuse new logins with a maintenance message until a write succeeds.                                                                                              |
| Slow client                                     | Output buffer over 256 KB disconnects the client. The character goes link-dead.                                                                                                  |
| Malformed telnet, GMCP or WebSocket frames      | Ignore the bad sequence and continue. Repeated garbage disconnects after 5 errors.                                                                                               |
| Deploy fetch or load failure                    | The old world stays live. The diagnostic goes to the CI response and instructors.                                                                                                |
| Plugin fails to load                            | Skip the plugin and log it. If the world references its builtins, those packages fail to load (old version kept).                                                                |
| Built-in behavior throws                        | Log an engine diagnostic; the behavior proposes nothing.                                                                                                                         |

### 6.5 Invariant Enforcement

- The simulation invariants (§3.8) are enforced in the Kotlin verb implementations and validated by
  [Invariants.kt](../core/src/main/kotlin/gups/core/world/Invariants.kt), never in scripts.
- In `--dev` and in the harness, an invariant check runs at the end of every pulse. In the harness (strict mode) a
  violation throws and fails the test. In `--dev` each violation is logged as an `invariant` diagnostic every pulse it
  persists, and the world keeps running.
- Every world also runs the check at each world tick before checkpoint capture (§5.11). The entities named in a
  violation are excluded from the checkpoint and stay dirty. Worlds that don't check every pulse (`--prod`) log each
  violation then and alert.

---

## 7. Verification & Test Plan

### 7.1 Engine Tests (Kotlin, `gups-engine` CI)

- **Platform matrix:** Tests execute on `linux-x64`, `linux-arm64`, and `macos-arm64`.
- **Deterministic unit and simulation tests:** Validate core simulation contracts under deterministic virtual clocks and
  seeded RNG: scheduler phase ordering and overrun policies; command pipeline admission, rate limiting, and queue caps;
  tick-lane action lifecycles, warmup interrupts, and lag recovery; combat round initiative tiers, auto-attacks, and
  simultaneous tie resolution; tactical track movement, pinning, and fleeing; hook cascades, containment, and step
  accounting; multi-hop sensory perception, sense deltas, and deferred delivery; rules engine modifier chains, dry runs,
  and each export's neutral result (§6.3); reality bubble incremental BFS, dormancy transitions, and piecewise catch-up;
  SQLite schema migrations, instance reconciliation, checkpoints, and backups; authentication flows, Argon2id hashing,
  and lockout limiters; telnet/TLS option negotiation and GMCP delta suppression; developer tooling, tracing, and
  `@eval`; and character creation choices, trait calculations, restrictions, and reversible leveling steps. Specific
  requirements:
  - **Initiative:** an actor killed by a higher tier never acts later in that phase; same-tier mutual kills both land;
    replays with the same seed give identical order and outcomes.
  - **Auth:** hashing never runs on the world thread (a 1 s blocking hash keeps the world pulsing); an account change
    survives a crash.
  - **Telnet:** option negotiation is checked against recorded client transcripts (Mudlet, TinTin++, raw `nc`), over TLS
    too.
  - **Schema:** every test that touches vitals runs against two fixture schemas: the template's, and a non-fantasy one
    with no `hp` or `mana`.
  - **Plugins:** no `core` or `script` type appears in the public `gups.pluginapi` surface.
  - **Creation:** a world without a creation provider behaves byte-identically to one built before character creation
    existed.
  - **Levels:** the four level verbs and their feature gate are covered.
- **`FeatureMatrixTest`:** Tests all 12 valid feature combinations (`combat`, `track`, `skills`, `leveling`) through a
  smoke scenario with the invariant check every pulse. For each feature that is off, its commands say "Huh?", its
  declarations, hooks and exports fail to load naming it, and its GMCP packages and fields are absent.
- **Property-based tests:** Random item manipulation sequences verify single-location custody and acyclic containment
  forest invariants across arbitrary transfers, and random checkpoint/crash sequences ensure zero item duplication or
  loss.
- **SIGKILL crash-consistency tests:** A child JVM shuffles 20 gems between four players (who log in and out), two bags
  and the ground, checkpointing every few pulses (immediate and periodic mixed), and is killed with SIGKILL at a random
  moment. Every restart must open cleanly, satisfy §3.8, and hold exactly 20 gems once all players log in. `./gradlew
  check` runs 20 iterations; `-Dgups.crashIterations=300` runs the full set.
- **API model and golden checks:** Validate that every hook, view, and `@StarlarkBuiltin` in `gups.script` and
  `gups.harness` matches the documented API model, verify changelog integrity, and compare `api.json` and generated
  documentation against golden copies (`-Dgups.updateGolden=true` rewrites them).
- **Browser smoke tests:** Headless Firefox via Selenium's Java bindings (no Node) logs in, moves and sees vitals. It
  runs on Linux x64 CI (`CI=true`, `os.arch` `amd64`/`x86_64`) and is skipped elsewhere; `-Dgups.browserTest=true` runs
  it on any machine with Firefox.
- **Deterministic replay:** Byte-identical world states and outputs are verified across executions and target platforms
  given identical random seeds and input sequences.
- **Load test** (deferred, §1.7): 500 scripted bot connections over TLS telnet and WebSocket, in mixed combat and
  movement, against the tutorial world scaled ×50. Pass criteria: pulse p99 < 50 ms, zero overruns over 30 min, and
  checkpoint p99 < 500 ms.

### 7.2 Script Tests (Starlark, via harness)

- `gups test` runs `*_test.star` against the in-memory world, with a virtual clock and a seeded RNG. There's no network,
  no SQLite, and no wall clock.
- A failure prints a readable diff, the trace of the failing step, and the rules chain for any roll.
- The tutorial zone's tests are maintained as worked examples, and must pass on every engine commit. The canonical copy
  is `template-world/zones/tutorial` in the engine repository, run by the harness and CLI tests.

### 7.3 Course World CI Gates (every PR)

Gates 1 and 2 run together as `gups lint`; gate 4 is `gups fuzz`; gate 5 is `gups migrate-check`. The workflow is the
template's `.github/workflows/gates.yml`.

1. **Load:** Every package loads, `api_version` matches, all labels resolve (exits, spawns, loads, keys, skills), and
   namespaces are unique. Hook catalog errors and duplicate namespaces are errors.
2. **Lint:**

- No dead-end rooms (0 exits) unless flagged `dead_end_ok` (error).
- Every exit has a reverse unless `one_way`, and doors match on both sides (error).
- A prototype whose declaration changed since `--base` needs a `version` bump (error). Presentation and code don't
  count: `name`, `desc`, `keywords`, hooks, `commands`, `think` and `resolve`. The comparison clears those fields by
  construction, so new fields are tracked automatically. If `--base` fails to load, the lint fails rather than passing.
  `--base` is staged in a temporary git worktree.
- Warn on attribute keys that are written (`set_attr` string literals) but never read anywhere in the repo. Dynamic keys
  are skipped.
- Warn on regions applied to rooms but never declared, and declared regions never applied.
- Hook names must match the catalog, so a typo such as `on_entr` is an error.
- Output is human-readable lines, or GitHub Actions annotations with `--format github` (repository paths, with `%`, `\r`
  and `\n` percent-encoded), plus a Markdown summary appended to `$GITHUB_STEP_SUMMARY`. It exits 1 on any error.
  Patterns default to `//...`.

3. **Zone tests:** Every changed zone has at least one `*_test.star`, and every test passes.
4. **World smoke:**

- The whole world loads together in the harness, with the bubble off and a default seed of 42.
- The fuzz walker runs 10,000 pulses through the changed zones with one bot player per changed zone. With a creation
  provider (§4.19), each bot gets complete answers drawn with the seeded RNG from the available options, in choice
  order, redrawn when the provider refuses (up to 10 tries, then the bot has no choices), and a respawned bot keeps
  them. Each pulse, dead or absent bots respawn in a zone room, bots whose condition isn't `ok` or who have a vital
  below a quarter of its maximum get every vital set to its maximum, and a bot whose `wait_state` exceeds 50 pulses has
  it cleared. Idle bots pick randomly among exits, zone and item commands with empty arguments, get/drop/wear/use,
  `kill` on a random actor (with the `combat` feature), and `say`.
- It fails at once on an invariant violation or any diagnostic of kind `script_error`, `step_limit`, `wall_clock`,
  `quarantine`, `alloc_limit`, `invariant` or `missing`. On failure it prints the exact rerun command (`gups fuzz --seed
  S --pulses P <patterns>`) and exports a repro bundle with the bots' initial positions and all inputs, for `gups
  repro`.

5. **Migration check:**

- Load a sanitized snapshot of the production database against the PR. An instructor makes it by hand when they choose
  (`gups snapshot --sanitized`) and uploads it as the asset `snapshot.db` of a release tagged `migration-snapshot` in
  the course repository. If there is none yet, CI warns and skips the gate, so a new term's first PRs pass. Otherwise it
  reports the snapshot's age in the PR summary.
- The snapshot's `world_schema` and `world_features` must match the PR's `MODULE.gups`; a difference fails the gate with
  "this schema or feature change needs a server wipe" (§3.9).
- Every version bump on an entity in `world_entities` must have `migrate()` or be listed in
  `package(migrate_noop=[...])`.
- Reconciliation must produce no tombstones for items in player custody that the PR didn't declare
  (`package(expected_tombstones=[...])`).
- The snapshot must restore fully without script errors.
- The workflow downloads the `snapshot.db` release asset through the API with `Accept: application/octet-stream`, so
  private course repositories work with `GH_TOKEN`.

6. **Changed packages:** changed files under `zones/` map to packages by searching parent directories for
   `package.star`, among directories that still exist (so deleted zones don't cause errors). If shared code changes
   (`lib/`, `rules/`, `skills/` or `MODULE.gups`), every package is tested and fuzzed.
7. **Budget:** The gates complete in about 2 minutes on a standard GitHub runner: the engine image is pulled with layer
   caching, tests run only for changed zones, and the fuzz walker stays within its pulse count. Their output is one
   Markdown table, posted to `$GITHUB_STEP_SUMMARY` and as a single PR comment (marked `<!-- gups-gates-summary -->`)
   that later runs update. If any gate failed, the workflow exits 1 after posting the summary.

### 7.4 Implementation Milestones (each gated by its tests)

| Milestone |                                                                                                                                                                                                                         Scope                                                                                                                                                                                                                         |
|-----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **M0**    | Gradle skeleton, module graph, `gups` CLI stub, CI matrix                                                                                                                                                                                                                                                                                                                                                                                             |
| **M1**    | `core` scheduler, entities, command pipeline, tactical track, the Kotlin default ruleset, combat rounds. Runs headless with a Kotlin test world.                                                                                                                                                                                                                                                                                                      |
| **M2**    | `script`: loader, labels, freezing, `ctx`, views, verbs, hooks, limits, error handling, quarantine. `harness` with `gups test`. Tutorial zone.                                                                                                                                                                                                                                                                                                        |
| **M3**    | `net`: raw telnet with options and ANSI, plus `--dev` login. Playable locally. Auto-reload, `@trace`, `@eval`.                                                                                                                                                                                                                                                                                                                                        |
| **M4**    | `persist`: checkpoints, reconciliation, backups, `--fresh`, crash tests                                                                                                                                                                                                                                                                                                                                                                               |
| **M5**    | Rules module, modifier chain, skills and affects, behaviors and `think`, perception, reality bubble, items/equipment/containers, `//lib:quest.star`                                                                                                                                                                                                                                                                                                   |
| **M6**    | Built in six stages: **M6.1** accounts and login, and the `--prod` profile; **M6.2** roles, builder scope and the in-game tools, restrictions and alerts; **M6.3** TLS and GMCP; **M6.4** WebSocket and the web client; **M6.5** the admin port, metrics, the deploy pipeline and release images; **M6.6** the course world template and CI gates. The load test was deferred (§1.7).                                                                 |
| **M7**    | Built in five stages: **M7.1** the API model and `api.json` (§4.16); **M7.2** `gups docs` (§4.16); **M7.3** the changelog and the API-change check (§4.17); **M7.4** the plugin API (§4.15); **M7.5** the sample plugin and the engine-developer guide (§4.15).                                                                                                                                                                                       |
| **M8**    | Disabling accounts: `@role disable`/`enable`, the disabled record (migration 4), and the roster's `disabled` column (§3.5, §4.9, §4.13).                                                                                                                                                                                                                                                                                                              |
| **M9**    | Configurable actor schema: stats, vitals, damage types and channels declared in `MODULE.gups`; typed constructors and schema view fields; no built-in ruleset, with `starting_stats`, `condition`, vital-keyed `mitigate`/`move_cost`/`regen`, the dry run and neutral results; incapacitation; `Char.Schema`; the plugin facade by name; `--wipe`; `samples/robot-world` (§3.9, §4.6, §6.3).                                                         |
| **M10**   | Feature modules: `combat`, `track`, `skills` and `leveling` turned on per world, command renames, the engine message catalog and `MESSAGES.gups`, plugin feature requirements (§3.10, §4.18). Ships in the same release as M9.                                                                                                                                                                                                                        |
| **M11**   | Character creation: plugin-declared choices asked at creation over telnet, the web client and `--dev`; traits with effective stats, maxima, resists, flags and modifiers; restrictions on wearing, entering, casting and learning; `tags`; the choice, trait and skill verbs; `Char.Creation` and `Char.Traits`; `samples/heritage-plugin` (§4.19, §4.15, §5.12).                                                                                     |
| **M12**   | Track-movement lags as rules exports: `advance_lag`, `retreat_lag`, `retreat_fail_lag`, `flee_fail_lag`; the template's `sticky_floor` tar pit (§4.6, §5.5, §6.3).                                                                                                                                                                                                                                                                                    |
| **M13**   | Senses and perception: per-channel `senses` for every actor with `sense_deltas` on items, affects and traits; `on_perceive` (was `on_nearby`) with channel, intensity and cost; multi-hop propagation priced by `sense_cost`, listeners by `can_perceive`, distant players by `perceive_message`; `ctx.emit`; door and container hooks with `door_intensity`; `exit.door` as a door view; rules exports spread over several files (§4.6, §5.7, §6.3). |
| **M14**   | Experience and levels, built in three stages: **M14.1** the reversible level step, `on_level_up`, the gain record, `level_loss` and the template's curve, kill scaling and death loss; **M14.2** `level_mode`, the `award_xp`, `take_xp`, `level_up` and `level_down` verbs, `pending_levels` and the harness verbs; **M14.3** the `on_level_change` hook, the `score` line and the template examples (§3.9, §4.4, §4.6, §5.4).                       |
| **M15**   | Specification refactoring and KDoc alignment: high-level architectural spec in SPEC.md with implementation details, constants, entity fields, seams, and limits documented in KDoc.                                                                                                                                                                                                                                                                   |
| **M16**   | Item quantities, pluralization, and all: item(plural=), phrase keywords, stack count display, no_all tag, count on move_item/give/destroy (replacing split), and quantities, all, optional prepositions, and carry/capacity capping on get, drop, give, and put (§3.3, §4.2, §4.5, §4.9).                                                                                                                                                             |
