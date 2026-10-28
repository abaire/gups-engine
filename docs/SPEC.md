# GUPS Engine — Architecture & Technical Specification

Status: v0, implemented through milestone M7. Script API: `api_version = 1` (unstable).

This is the single source of truth for the engine's design. Choices made while implementing
that it doesn't settle are logged in [DECISIONS.md](DECISIONS.md) until they are folded in
here, and features it leaves out go in [DESIGN_NEEDED.md](DESIGN_NEEDED.md).

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
- Each engine release ships generated API docs (§4.16) and a human-readable changelog
  (§4.17).
- Migrating legacy zones with a local LLM, fed the changelog and the failing zone, is an
  intended course exercise.
- A breaking change to the script API requires raising `api_version`; engine CI enforces it
  (§4.17).
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
- **Engine core:** Kotlin (JVM), `kotlinx.coroutines`. Every listener runs on Netty 4.2
  (NIO transport); HTTP listeners use Ktor's Netty engine.
- **Scripting:** `net.starlark.java`, the canonical Bazel JVM implementation of Starlark. Its
  only Maven Central packaging is `com.eed3si9n.starlark:starlark` 4.2.1 (Bazel 4.2.1's
  interpreter). That version rejects `lambda` and nested `def`s at parse time, so every hook,
  command, timer and modifier is a top-level `def`. `load` is a reserved word.
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
| Schema | The world's declared stats, vitals, damage types and extra perception channels (§3.9). Fixed for the life of a database. |
| Feature | An engine mechanic a world can turn off: `combat`, `track`, `skills` or `leveling` (§3.10). Everything else is the kernel. |
| Condition | An actor's life state, `ok`, `incapacitated` or `dead`, decided by the rules (§4.6). |

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
- The §7.1 load test, deferred to a later milestone: the expected number of players is small
  for modern hosts.
- ACME certificate issuance (an external client such as certbot renews certificates, §4.10).
- GMCP input packages beyond `Core.*`.
- Team owners in CODEOWNERS.
- A self-service password reset (instructors reset passwords with `@role`, §4.9).
- A plugin marketplace, plugin hot reload, and plugin sandboxing (§4.15).
- Automating the `api_version` bump in the release job (§4.17 explains why it is a pre-tag
  step).
- Everything in [DESIGN_NEEDED.md](DESIGN_NEEDED.md), including a player's choice of
  starting stats at character creation.
- Changing a world's schema (§3.9) or features (§3.10) without a server wipe.

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
  samples/     sample plugins: weather-plugin (§4.15); robot-world, a small non-fantasy
               course world with its own schema and features (§3.9, §3.10)
  template-world/  the course world template, including the canonical tutorial zone,
               //lib and //rules (§2.3)
  docs/        SPEC.md, CHANGELOG.md, PLUGINS.md (the
               engine-developer guide), api/ (released API baselines, §4.17)
```

**Module dependency rules (enforced by the Gradle module graph):**

- `core` depends on no other GUPS module. It defines interfaces (`ScriptHost`, `RulesEngine`,
  `Persistence`, `AccountStore`, `WebAccounts`, `LoginHandler`, `OutputSink`) that the other
  modules implement. It exports `kotlinx-coroutines` as an `api` dependency, because
  `WorldLoop`'s public API uses it.
- `script`, `persist`, `net`, and `auth` depend on `core` and never on each other.
- `harness` depends on `core` and `script` only. It never touches `net` or `persist`.
- `cli` is the only module that wires everything together. `net` reads the web client's
  files through a class loader that `cli` hands it, so it doesn't depend on `webclient`.
- `plugin-api` depends on `core` and `script` with `implementation`, and exposes only
  `api(libs.starlark)`. Plugins compile against `gups.pluginapi.*` and `net.starlark.java.*`
  alone; no public type in `gups.pluginapi` mentions a `core` or `script` type (a reflection
  test checks it). Plugins are loaded through `ServiceLoader` from the world's `plugins/`
  directory via a `URLClassLoader` (§4.15).
- `samples/*` may depend only on `:plugin-api` in their main configurations (their tests may
  use the harness), so the sample proves the facade is enough to write a real plugin.
- Nested projects (such as `:samples:weather-plugin`) are evaluated explicitly by the root
  build, so the module-graph check sees their dependencies too.

**Release artifacts:**

- `gups` runtime images for `linux-x64`, `linux-arm64`, and `macos-arm64`, built
  with `jlink`. Students need no JDK.
  - The image (`build/image/gups`) is a jlink runtime, the jars and a `bin/gups` launcher.
    Its modules are those `jdeps` finds in the plain jars, plus the non-static `requires` of
    the modular jars' descriptors, plus the reflectively loaded `jdk.crypto.ec`,
    `jdk.naming.dns`, `jdk.unsupported`, `java.management` and `java.naming`. The list is
    written to `modules.txt`.
  - Each is packed as `gups-<version>-<platform>.tar.gz`. Every CI platform builds and
    smoke-tests it with `gups test`; a `v*` tag attaches the archives to the GitHub release.
- An OCI image for the course server: `eclipse-temurin:21-jre-noble` plus `git`,
  `openssh-client`, `curl` and `jq`. One build serves `linux/amd64` and `linux/arm64`. It
  declares the volumes `/world`, `/data` and `/etc/gups`, marks `/world` a git
  `safe.directory`, runs `gups serve --prod --config /etc/gups/server.gups`, and health-checks
  `/healthz`. A `v*` tag pushes it as `ghcr.io/<owner>/gups:<version>` (the tag without `v`).
- A course world template archive (§2.3).
- The API reference (§4.16): `gups-docs-<version>.zip`, attached to the GitHub release, and
  a `docs/` directory inside the install directory, the jlink image and the OCI image
  (`/opt/gups/docs/`).
- The engine version (semver) and `api_version` (integer) are independent. `api_version`
  bumps only on breaking script-facing changes. Release jobs build with
  `-Pversion=${GITHUB_REF_NAME#v}`, so the engine, archives, OCI image and template agree.

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

- **Template:** The canonical template lives in the engine repository as `template-world/`.
  It holds the tutorial zone (whose tests must pass on every engine commit, §7.2), `lib/`
  (including `quest.star`) and `rules/`. Its `MODULE.gups` declares the fantasy schema of
  §3.9 with every feature on, and its `rules/combat.star` implements the template ruleset
  of §4.6; both are starting points the instructor edits. A release job packages it as the course world
  template; each term, instructors create the term's repository from it on GitHub.
  - In the engine repository its `MODULE.gups` is unpinned. Packaging the template stamps
    the engine version into `MODULE.gups` and the image owner and version tag into
    `.github/workflows/gates.yml`, which runs the gates in the pinned engine image.
  - Its `CODEOWNERS` gives instructors `MODULE.gups`, `rules/`, `lib/`, `skills/` and
    `zones/tutorial/`, with a commented example zone line. Owning `MODULE.gups` covers
    plugins, since a jar takes effect only once `MODULE.gups` pins it. The instructor is the
    placeholder `@CHANGE_ME_INSTRUCTOR`, to be replaced.
  - Its `README.md` tells instructors how to start a term, import the roster, deploy, and
    upload the migration snapshot (§7.3).
  - Its dead-end rooms carry `dead_end_ok`. It uses no plugins.
- **Package kinds:** `zone`, `lib`, `rules`, `skills`. Only `lib` and `rules` packages are
  *privileged*. They may declare shared attribute namespaces and global commands (§4.9).
- **Load labels vs target labels:** `load("//lib:quest.star", "quest")` addresses a *file*.
  `//zones/forest:clearing` addresses a *declaration* named `clearing` in any non-test
  `.star` file of package `zones/forest`. Declaration names are unique per package.
- **Loading a package:**
  - It also loads every package it refers to by label (exits, spawns, keys, loot, skills,
    summons) or by `load()`, transitively. A referenced package that isn't on disk is a
    label error.
  - A package fails if any of its files fails, if any label doesn't resolve to a declaration
    of the right kind, or if anything it depends on failed.
  - `package.star` must call `package()` exactly once and holds nothing else, apart from the
    top-level functions its `modifiers` name (§4.6). It can't be `load()`ed.
  - Content files evaluate in alphabetical order. Only test files may load `*_test.star`
    files.
  - Global commands may not shadow an engine command or alias, or another package's global
    command. An attribute namespace used by two packages (or by a package and a shared
    namespace, or equal to a plugin id) fails every package involved.
- **`MODULE.gups`:** Starlark with exactly one call,
  `module(engine, api_version, checkpoint_minutes, start_room, plugins, aliases, prefixes,
  stats, vitals, damage_types, unarmed_damage_type, channels, prompt, features, rename)`.
  The file is required, and so are `stats`, `vitals`, `damage_types` and
  `unarmed_damage_type`; every other argument is optional. A world without the file, or
  without one of the required arguments, stops the server.
  - `stats`, `vitals`, `damage_types`, `unarmed_damage_type`, `channels` and `prompt` are
    the world's schema (§3.9); `features` and `rename` choose its feature modules and
    command names (§3.10).
  - A pinned `api_version` the engine doesn't support stops the server. A different pinned
    `engine` version only warns.
  - `start_room` sets where new characters start, in both profiles (`--start` overrides it).
    Without either, it is `//zones/tutorial:gate` if that exists, else the first room by
    label. A `start_room` that isn't a room stops the server.
  - `plugins = {"<id>": "sha256:<64 lowercase hex>"}` pins plugin jars (§4.15). An id that
    doesn't match `[a-z][a-z0-9_]*`, that equals a reserved name, or whose checksum is
    malformed is a `MODULE.gups` problem, so it stops the server.
  - `aliases = {"l": "look", "inv": "inventory"}` gives engine commands shorter words, and
    `prefixes = {"'": "say", "%": "cast"}` makes a single character at the start of a line
    stand for a command (§5.2). Several aliases or prefixes may name the same command. The
    engine hardcodes none: a world without them has no aliases, and the template world's
    `MODULE.gups` ships the defaults (`l`, `i`/`inv`, `eq`, `k`/`attack`, `c`, `take`, `unwield`; `'`
    and `"` for `say`, `%` for `cast`). An alias that isn't `[a-z][a-z0-9_]*`, is an engine
    command or a standard direction, or names something other than a non-`@` engine
    command, and a prefix that isn't one character other than a letter, digit, space or
    `@`, or that names something other than such a command, stop the server.
  - Command names in `aliases` and `prefixes` are the names after `rename` (§3.10); an alias
    or prefix naming a command of a feature that is off stops the server.
  - Changing the file needs a restart (§5.10). The file watcher and reload never read it.
    Changing its schema or features also needs a server wipe (§3.9).
- **`MESSAGES.gups`:** optional Starlark with exactly one call, `messages({...})`, that
  overrides engine message text (§4.18). Like `MODULE.gups`, it is instructor-owned, read
  only at startup, and a problem in it stops the server.
- **CODEOWNERS:** the first of `.github/CODEOWNERS`, `CODEOWNERS` and `docs/CODEOWNERS`, in
  GitHub's order. No file means no CODEOWNERS scope.
  - Each line is a path pattern, then owners. A pattern maps to every package whose
    directory it matches; for example `/zones/forest/` owns `zones/forest` and the packages
    below it. The last matching pattern wins, as on GitHub.
  - Patterns are gitignore-style, matched against package directories. A leading `/` or an
    inner slash anchors to the world root; otherwise the name matches at any depth. `*`
    stays within a directory and `**` crosses them. A directory pattern also owns the
    packages below it. File patterns such as `*.star` own no package. Inline `#` comments
    aren't supported, as on GitHub.
  - Owners are `@user` handles, compared in lowercase with accounts' `github_handle`. Team
    and email owners are warnings, as are handles that match no account.
- **Deploying:** An engine upgrade is a PR that edits `MODULE.gups`. CI shows exactly which
  packages break.

### 2.4 Runtime Profiles

| Flag | Effect |
|---|---|
| `--dev` | Raw telnet on `127.0.0.1:4000` (`--port` changes the port). Auto-creates a passwordless local `instructor` account bound to localhost. Auto-reload on file save. `@eval` and `@reload` enabled. Loads every package under `--world` (default: the current directory); packages that fail to load are reported and left out. Serves the API reference over plain HTTP at `http://127.0.0.1:4080/docs/` (`--docs-port PORT` changes the port; `0` picks a free one). Checkpoints still run. |
| `--prod` | Account logins (§5.12). Listeners as configured: raw telnet only when asked, telnet over TLS, and HTTPS with the web client and `/docs/` (at least one is required). The admin port is always on. `@eval`, `@reload`, auto-reload and the file watcher are off. Deploys only through `gups deploy` / `@deploy`. The §3.8 check runs at each world tick, not every pulse. Requires a `git` binary. `--docs-port` is a usage error. |
| `--fresh` | Usable with either profile. Ignores saved **world** state (mob positions, ground items, room and zone state) and boots purely from the `.star` definitions. Accounts, characters, and items whose location chain ends in a player character are still loaded. |
| `--wipe` | A server wipe, usable with either profile. Like `--fresh`, and also deletes every character and every item, then records the current schema and features (§3.9). Accounts, restrictions, web tokens and setup codes are kept. A `prewipe` backup (§5.11) is taken first. |

- **Database:** `--db` defaults to `<world>/.gups/world.db` in both profiles. The hidden
  directory is skipped by the package scan and the file watcher. `gups backup`, `restore`
  and `repro` take the same `--world` and `--db`.
- **Reality bubble:** `gups serve` always runs with the bubble (§5.9). Kotlin tests, the
  harness and `gups repro` default to no bubble, where every room is always simulated.
- **Plugins** load the same way in both profiles (§4.15).
- **`--prod` configuration:** `--config FILE` reads the server configuration
  (`server.gups`), Starlark with one `server(...)` call like `MODULE.gups`, so instructors
  learn one syntax. It lives outside the course world repository, because it holds paths and
  settings for one machine. Secrets never go in it: the admin token and the deploy key are
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

  Every setting also has a CLI flag, and a flag overrides the file:
  - Listeners: `--telnet`/`telnet` (binds every interface), `--telnet-tls`/`telnet_tls`,
    and `--https`/`https`. Both TLS listeners share one certificate: `--tls-cert` and
    `--tls-key` (`tls_cert`, `tls_key`), or `--self-signed`, which needs a TLS listener.
  - Admin port: `--admin`/`admin` (default `127.0.0.1:9090`) and
    `--admin-token-file`/`admin_token_file`/`GUPS_ADMIN_TOKEN_FILE` (§4.14).
  - Accounts: `--open-signup`/`--no-open-signup`, `--max-characters`, `reserved_names`,
    `restriction_ladder`.
  - Deploys: `--repository`, `--deploy-branch` and `--deploy-key-file` (or
    `GUPS_DEPLOY_KEY_FILE`), with configuration keys of the same names.
  - Shutdown: `--shutdown-timeout`/`shutdown_timeout`, in seconds (default 30, at least 1;
    §5.11).
  - The existing `--world`, `--db`, `--start` and `--fresh`.
  - The startup message lists the bound port of each transport (`telnet`, `tls`, `ws`,
    `admin`).
- **`--prod` and the world commit:** When the world is a git working tree, `--prod` refuses
  to start if tracked files have uncommitted changes, or if `HEAD` isn't the
  `meta.world_commit` the database was saved at. `--force-commit` adopts what is there. The
  first start records `HEAD`. A world that isn't a working tree, or has no commits, starts
  with a warning and can't deploy.

### 2.5 Process and Thread Topology

- **Network I/O** runs on `Dispatchers.IO`. This covers Telnet and ANSI parsing, option
  negotiation, line framing, GMCP encoding, and output buffering.
- **The world thread** is a single-threaded dispatcher
  (`Dispatchers.Default.limitedParallelism(1)`). It owns the entire world graph, the
  scheduler, all Starlark evaluation, and checkpoint *capture*. Every world mutation
  happens here, so they are lock-free and race-free by construction. The dispatcher is one
  process-wide view, so every world in a process shares one thread.
- **The checkpoint writer** is a dedicated thread. It is the only SQLite writer, and it
  applies batches in FIFO order (§5.11).
- **Background work:** Argon2id takes roughly 100–300 ms per hash and a git fetch takes
  seconds; neither may run on the world thread. Slow work (password hashing, account store
  access, account commands, `@snapshot`, `@audit`) runs on one pool of 4 threads
  (`Dispatchers.IO.limitedParallelism(4)`). `World.later(work, then)` runs `work` there and
  `then` with its result on the world thread, through a `WorldControlEvent.Task`. A world
  without a pool (headless tests) runs both at once.
- **Deploys** run one at a time, each on a thread of its own (§5.10).
- **The file watcher** (`--dev` only) posts reload requests into the world input channel.
  It polls every 100 ms, comparing the modification time and size of every non-test `.star`
  file, rather than using `WatchService` (which polls every few seconds on macOS).
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
const val CORPSE_DECAY_PULSES = 1200   // 5 min
```

Game time is `pulse: Long`. Every persisted deadline is an absolute pulse number.
Wall-clock time is used only for logging, backups, and session-token expiry.

### 3.2 Script-Compatible Values

```kotlin
sealed interface AttrValue {
    data class I(val v: Long) : AttrValue
    data class S(val v: String) : AttrValue
    data class B(val v: Boolean) : AttrValue
    data class L(val v: List<AttrValue>) : AttrValue
    data class M(val v: Map<String, AttrValue>) : AttrValue
}
```

- Starlark `int`, `str`, `bool`, `list`, and `dict` with string keys convert both ways.
  `None` means "absent": setting an attribute to `None` deletes it.
- Floats, functions, and host objects can't be stored.
- An entity's serialized `customAttributes` is limited to 64 KB, measured as compact JSON.
- `core` stays free of kotlinx.serialization. `persist` maps values to JSON itself: ints
  and bools as JSON numbers and booleans, strings as strings, lists as arrays and dicts as
  objects.

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
    var debug: Boolean                        // builder-spawned; never persisted (§4.9)
}
```

**Reserved engine labels:** `//engine:player` is the prototype label of every player.
`//engine:corpse` is the built-in corpse container, always present in the item table.

**Prototypes** are immutable Kotlin data classes in `core` (`RoomProto`, `MobProto`,
`ItemProto`, `SkillProto`, `ZoneProto`). Behaviors, `think`, zone commands and custom
`resolve` are Kotlin function interfaces that the script host implements. Hooks are not
stored on prototypes: the host looks them up by prototype label.

**Room**
- `trackSize`: `CRAMPED = 1`, `NORMAL = 3`, or `LARGE = 5`. Always 1 without the `track`
  feature (§3.10).
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
- `sensesBlocked: Set<Channel>`, where a `Channel` is one of the built-in `sight`, `sound`
  and `noise`, or a channel the world declares (§3.9). It only stops perception (§5.7); it
  never affects whether the exit can be walked through.
- `door: Door?`.
- `oneWay: Boolean`.

**Door**
- `state`: `OPEN`, `CLOSED`, or `LOCKED`.
- `keyLabel: Label?`.
- `openSensesBlocked`, `closedSensesBlocked: Set<Channel>`.

The **effective** `sensesBlocked` set of an exit is the exit's `sensesBlocked` plus the door's set for its
current state.

**Actor** (sealed: `Player`, `Mob`)
- **Position:** `room: RoomRef` and `trackIndex: Int` (always 0 without the `track`
  feature, §3.10).
- **Stats and vitals,** keyed by the names the world's schema declares (§3.9):
  - `baseStats: Map<String, Int>`, one entry per declared stat. The effective value adds
    affect, equipment and encumbrance deltas.
  - `vitals: Map<String, Vital>`, one per declared vital. A `Vital` has `current` and `max`
    (`current ≤ max`; `current` may be negative) and `belowZeroSince: Long?`, the pulse
    `current` last dropped below 0, cleared when it is 0 or more again.
  - `resists: Map<DamageType, Int>`, keyed by declared damage types.
  - `condition`: `OK`, `INCAPACITATED` or `DEAD`, as `rules.condition` last decided (§4.6).
  - Leveling (the `leveling` feature): `level`, `xp`.
- **Skills** (the `skills` feature): `Map<Label, Int>`, the skill label and a proficiency of
  0–100.
- **Affects:** `affects: List<Affect>`.
- **Action state:**
  - `actionQueue: ArrayDeque<QueuedAction>`, capacity 10.
  - `waitState: Int`, in pulses.
  - `casting: CastState?` (only with the `skills` feature).
  - `fighting: Set<EntityId>` (only with the `combat` feature).
- **Mob only:**
  - `behaviors`.
  - `thinkFn`.
  - `thinkEvery: Int`.
  - `senses: Map<Sense, Int>` with radius 0..1. Senses are `HEARING` and `SIGHT`.
  - `homeRoom`.
  - `summonedBy: EntityId?`, the actor whose `summon` (§4.3) spawned it.
  - `dormantRecovery`: `FULL` (default), `REGEN`, or `NONE` (§5.9).
- **Player only:**
  - `accountId` (none for characters in a world without a database).
  - `role` and builder scope, copied from the account each time the character enters the
    game and whenever the account changes (§3.5).
  - `session: SessionRef?`. `null` while link-dead; the player is removed from the
    world after quitting.
  - `recallRoom`.

**Item**
- `location: ItemLocation`.
- `count: Int`. This is 1 unless the prototype is `stackable`.
- `weight`.
- `container: ContainerState?` with `capacity`, `maxWeight`, `state` (OPEN, CLOSED, or
  LOCKED), and `keyLabel`.
- `range: Band`, for weapons (the `track` feature).
- `kind`, for example `melee`, `ranged`, `armor`, or `consumable`.
- `statDeltas`, keyed by declared stats.
- `decayAt: Long?`.
- `modifiers` and `equipAffects`, which apply when the item is worn (§4.6, §4.2).
- `wear`: the item's slot or slot family (§4.2).

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
- `statDeltas`, keyed by declared stats.
- `flags`, for example `stunned`, `silenced` (blocks casting, §5.3, and speech, §4.9), or
  `no_regen` (suppresses all vitals regen, including dormant catch-up, until the affect
  expires).
- Re-applying an affect with the same name, a `stun` included, replaces the old one only if
  the new one would expire later. Otherwise the old one stays as it is, so a shorter affect
  never cuts a longer one short.
- `tickEvery: Int?`.
- `onTick` and `onExpire`: `HookRef?`.
- `modifiers`: modifier functions stored as `HookRef`s, so they persist (§4.6).
- `expireMessage`.

**ScriptTimer**
- `id`.
- `fn: HookRef`.
- `args: Map<String, AttrValue>`.
- `due: Long?` (absolute pulse, while running).
- `remaining: Int?` (while paused in a dormant room).

**HookRef** is `(file label, top-level function name)`. Lambdas and nested functions don't
parse (§1.5), so every function a script hands the engine is a top-level `def`.

**RNG:** `Rng` wraps `kotlin.random.Random(seed)` (XorWow), which is deterministic across
platforms. Changing the algorithm changes every replay.

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
- **`migrate()`:** A package defines at most one top-level `migrate(old_version, attrs)`, in
  any of its files.
  - It runs for each instance whose saved prototype version is below the current one: mobs,
    items and rooms restored at startup, items restored with a character at login, and
    live instances after a reload.
  - `attrs` is a dict of the package's own attributes by short name. The dict it returns
    replaces them; other packages' attributes are untouched.
  - An error, or a return value that isn't a dict of storable values, leaves the attributes
    as they were and logs a diagnostic. The version still moves up.
- **Live reconciliation (reload):** A field whose live value still equals the old
  prototype's takes the new prototype's value; any other field keeps its live value.
  - Mobs: level, vital maxima, base stats, resists, skills, `think_every` and
    `dormant_recovery` follow that rule, compared with the prototype's declared values (so
    values `rules.starting_stats` changed at spawn are kept). Current vitals stay, capped at
    the new maximum, except that a vital that was at its maximum moves to the new one.
  - Rooms: door states and exit `senses_blocked` sets follow the same rule.
  - Items read everything from their prototype, so they switch to the new one.
  - Actors forget skills whose prototype is gone.
  - Spawn rules carry their live mobs over by label. New rooms fill their spawns, then get
    `on_load`.
- **Deleted prototypes:**
  - Mobs from a deleted prototype are despawned, and so are items that aren't in a player's
    custody.
  - Items in player inventories, equipment, or player-owned containers become inert
    **tombstone** items: kind `tombstone`, the original name and label, and the payload they
    had when their prototype vanished. Tombstones can be inspected and dropped, but they
    have no hooks, commands or stats. A tombstone keeps its container state, so the contents
    survive. Saved items become tombstones when their owner logs in.
  - A deleted room sends its players to their recall room, or else the first room.

### 3.5 Accounts and Roles

```kotlin
enum class Role { PLAYER, BUILDER, INSTRUCTOR, ADMIN }   // ordered; higher implies lower
```

- **Account fields:** `id`, `login`, `pwHash` (Argon2id; absent until a roster account is
  set up), `roles`, `githubHandle?`, `disabled` (with `disabledBy?`, `disabledAt?` and
  `disabledReason?`), and `createdAt`.
  - Logins are 3–24 characters from `[a-z0-9_.-]`, unique case-insensitively and stored
    lowercase.
  - An account has one role; the higher roles imply the lower, so `roles_json` stores
    `{"role": "BUILDER", "scope": [...], "manual": [...]}`.
  - A disabled account can't log in (§5.12). Disabling it does four things at once: it quits
    the account's characters and closes its sessions, deletes its web tokens and unused setup
    codes, and records who disabled it, when and why. Re-enabling clears that record and
    restores nothing else. Accounts are disabled with `@role` (§4.9) or the roster (§4.13).
- **Characters:** An account owns 1..N characters (`max_characters`, default 5; instructors
  and admins unlimited) and plays one at a time, across every transport (§5.12). Character
  names are 2–16 letters, unique across all accounts (case-insensitive), and checked against
  the reserved names:
  - Built in: `self`, `me`, `all`, `someone`, `here`, the menu words `new`, `password` and
    `quit`, `guest`, `instructor`, `admin`, and every standard direction with its
    abbreviation.
  - Per world: the name and aliases of every engine, global and tool command (without `@`),
    every keyword and every word of the name of each mob prototype (so no player can pass
    for an NPC), and the server configuration's `reserved_names`.
  - Logins reserve only `new`.
- **Builder scope:** The `BUILDER` role is scoped to a set of package paths, the union of
  two scopes stored in `roles_json`:
  - The **CODEOWNERS scope**, derived from `CODEOWNERS` (§2.3) by matching `githubHandle`.
    It is synced at startup and after each deploy. Every account other than instructors and
    admins gets its CODEOWNERS packages, players included, so promoting a player scopes them
    at once. A sync never promotes or demotes: it only replaces scopes, and `@role` sets
    roles.
  - The **manual scope**, set by `@role <account> builder <packages>` (§4.9). Packages that
    CODEOWNERS covers are refused there, and a sync drops any that CODEOWNERS later covers.
  - Instructors and admins have neither scope; they see everything.
  - A changed scope applies to characters in play at once.
- **Storage seam:** `AccountStore` (in `core`, with `Account` and `Invite`) is exposed as
  `Persistence.accounts` and implemented in `persist`; `auth` uses only the interface, so it
  still depends only on `core`. Account changes are rare and small and must survive a crash,
  but they aren't world state, and the checkpoint writer stays the only SQLite writer:
  - Each change is a batch of its own, submitted to the checkpoint writer as an immediate
    batch (written at once, in FIFO order) so it never merges with world captures. The caller blocks (off the world thread)
    for at most 30 s until it commits. Account batches don't count toward the
    checkpoint-backlog alert.
  - A taken login (`INSERT OR IGNORE`) and a used invite (`UPDATE … WHERE used_by IS NULL`)
    are reported as results, never thrown, so they can't wedge the writer's retry loop. That
    also makes single use hold across processes.
  - Reads use a read-only connection of the store's own beside the writer (WAL). Accounts
    aren't cached, so CLI tools writing the same database directly are safe.
  - `update` writes role, scopes, GitHub handle and the disabled flag with its record, never
    the password. `setPassword` deletes the account's `session_tokens`. Disabling an account
    deletes them too, along with its unused setup codes.
- **Invite and setup codes:** 10 characters grouped as `XXXXX-XXXXX`, drawn from the
  alphabet `23456789ABCDEFGHJKMNPQRSTWXYZ` (no 0/O, 1/I/L, U/V). They are compared after uppercasing and removing spaces and dashes, and
  stored as the SHA-256 hex of that canonical form; they are random enough that an unsalted
  fast hash suffices. A setup code is bound to one account; an invite carries a role.

### 3.6 Persistence Schema (SQLite, WAL)

| Table | Columns (abridged) | Notes |
|---|---|---|
| `meta` | `key`, `value` | `schema_version`, `pulse`, `next_entity_id`, `next_handle` (timer and affect ids), `world_commit`, `world_schema` and `world_features` (§3.9), `snapshot_taken_at` (sanitized copies only) |
| `accounts` | `id`, `login`, `pw_hash`, `roles_json`, `github_handle`, `disabled`, `disabled_by`, `disabled_at`, `disabled_reason`, `created_at` | `roles_json` holds the role, CODEOWNERS `scope` and `manual` scope; the `disabled_*` columns record the current disable (migration 4) |
| `session_tokens` | `token_hash`, `account_id`, `expires_at` | web client; revocable |
| `invites` | `code_hash`, `role`, `expires_at`, `used_by`, `account_id` | `account_id` binds a setup code to its account (migration 2) |
| `restrictions` | `id`, `account_id`, `capability`, `until`, `reason`, `set_by`, `created_at`, `lifted_by`, `lifted_at` | migration 3 (§4.9) |
| `players` | `id`, `account_id`, `name`, `payload_json`, `updated_pulse` | character stats, skills, affects, attrs, position |
| `world_entities` | `id`, `kind`, `proto_label`, `proto_version`, `location_json`, `payload_json`, `updated_pulse` | rooms, mobs, and **all items**, including carried ones |
| `zone_states` | `package`, `payload_json` | reset and respawn deadlines, quarantine state; a quarantined non-zone package gets a row with only its quarantine flag |
| `item_events` | `id`, `item_id`, `pulse`, `wall_time`, `from_json`, `to_json`, `cause` | custody audit of changes involving a player; locations as JSON; cause `spawn`, `move` or `destroy` |
| `diagnostics` | `id`, `pulse`, `wall_time`, `package`, `label`, `hook`, `file`, `line`, `kind`, `message`, `stack`, `ctx_json`, `seed`, `repro_json` | |

- **Item location:** An item's location is stored **only** on the item row. Inventories and
  container contents are derived when the world loads. A player row never lists items, so
  no partial checkpoint can put one item in two places.
- **Entity IDs:** `next_entity_id` is written in every checkpoint transaction. IDs
  allocated after the last committed checkpoint may be reused after a crash. This is safe
  because nothing that referenced them was committed either.
- **Payloads:** Each JSON payload carries a `format_version` for engine-side schema
  evolution. Field names are camelCase except `format_version`.
- **References:** An item's location names a room by label (with a track index) and an
  actor or container by id. Rooms keep their saved ids across restarts, so ids stay unique,
  but nothing refers to a room by id.
- **Deltas:**
  - Rooms store door states and exit `senses_blocked` sets that differ from the prototype, plus
    attributes and timers.
  - Mobs always store position, current vitals, xp, home room and spawn binding. Level,
    vital maxima, stats, resists, skills, `think_every` and `dormant_recovery` are stored
    only when they differ from the prototype, each as a whole value.
  - Stats, vitals and resists are stored as objects keyed by their schema names; a vital is
    `{"cur", "max"}`, plus `"belowZeroSince"` when set.
  - Items store count, decay, name override, container state, attributes, timers and their
    prototype name.
  - Players store everything, role included.
  - Every entity and player stores its active and anchor reasons (§5.9).
- **SQLite settings:** WAL with `synchronous = NORMAL`, which survives process kills but may
  lose the last commits on power loss. `busy_timeout` is 5 s.

### 3.7 Attribute Namespacing

- A key written by package `zones/goblin_caves` is stored as `goblin_caves:<key>`. The
  namespace is the last path segment of the package, and must be unique across the world.
  CI lints for this.
- A package reads its own keys by their short name. Other packages read them by full
  name only. Short keys match `[A-Za-z0-9_]+`.
- **Which package is "the package":** at run time, relative labels and attribute keys
  resolve against the package of the innermost running function. A `//lib` helper therefore
  writes the lib's own namespace (and its shared namespaces) even when a zone hook calls it.
- A package can **write only its own namespace**. Writing another namespace is a script
  error.
- **Shared namespaces:** Privileged packages may declare them, for example
  `package(shared_namespaces = ["faction"])`. Any package may write to a shared namespace,
  but only through helper functions exported by the declaring package. The declaring
  package's helpers run with write permission on that namespace.
- **Plugin namespaces:** a plugin writes attributes in the namespace of its id, so no
  package's namespace or shared namespace may equal a plugin id; such a package fails to
  load with a `namespace` error.
- **Built-in state** isn't stored in attributes and isn't reachable through them. That
  covers stats, position, and inventory.

### 3.8 Invariants (state contracts)

1. Every item has exactly one `ItemLocation`. The containment graph is a forest: no
   cycles.
2. Every actor's `trackIndex` satisfies `0 ≤ trackIndex < room.trackSize`.
3. Every vital's `current ≤ max`. Death is processed exactly once per life.
4. An actor is either idle, in recovery (`waitState > 0`), or casting
   (`casting != null`). It is never both in recovery and casting. Without the `skills`
   feature, `casting` is always null.
5. `actionQueue.size ≤ 10`.
6. A world mutation happens on the world thread or not at all.
7. A dormant room has no running timers, AI, or combat. Its timers are all paused.
8. Stack merges only happen between instances with identical deltas.

### 3.9 World Schema

Each world declares in `MODULE.gups` the names its actors are measured by. The engine names
no stat, vital or damage type itself; everything that gives them meaning (damage, costs,
regeneration, death) is in the rules module (§4.6).

```python
# The template world: a fantasy term.
module(
    api_version = 1,
    stats = [
        stat("str", label = "Strength"), stat("dex", label = "Dexterity"),
        stat("con", label = "Constitution"), stat("int", label = "Intelligence"),
        stat("wis", label = "Wisdom"), "armor",
    ],
    vitals = [vital("hp", label = "Hit points"), "mana", vital("move", label = "Movement")],
    damage_types = ["bludgeon", "slash", "pierce", "fire"],
    unarmed_damage_type = "bludgeon",
    channels = ["spell"],
    prompt = "<{hp}hp {mana}m {move}mv> ",
)
```

```python
# samples/robot-world: a post-apocalyptic term.
module(
    api_version = 1,
    stats = ["might", "agility", "tech", "grit", "plating"],
    vitals = [vital("integrity", label = "Integrity"), "sanity", "power",
              vital("rads", label = "Radiation")],
    damage_types = ["kinetic", "energy", "psychic"],
    unarmed_damage_type = "kinetic",
    channels = ["psionic"],
    features = ["combat", "skills"],
    rename = {"cast": "invoke"},
)
```

- **Declarations:** `stat(name, label = None)` and `vital(name, label = None)`; a plain string
  is the same as the call with only a name. The label is shown to players (`score`, GMCP)
  and defaults to the name with its first letter capitalized. Declaration order is display
  order everywhere.
- **Numbers belong to actors, not the schema.** A schema has no starting values, maxima or
  floors: every new character and every spawned mob gets its numbers from
  `rules.starting_stats` (§4.6), so they may differ per actor and be randomized.
- **Names** of stats, vitals, damage types and channels match `[a-z][a-z0-9_]*`.
  - Stat and vital names are unique together, and may not equal an actor view field
    (§4.5), `base`, `resists`, `condition`, `below_zero_since`, or `max_<vital>` for a
    declared vital. The view fields are those of the actor view class itself, so a field
    added to it is reserved too. Vital names may not start with `max`.
  - Damage type names are unique; `unarmed_damage_type` must be one of them. It is the type
    of an unarmed auto-attack.
  - Channel names are unique and may not be a built-in channel (`sight`, `sound`, `noise`).
    Declared channels can be blocked by exits and doors and listed in a skill's `channels`,
    but nothing in the engine raises events on them.
  - A schema problem stops the server, with a "did you mean" suggestion where one applies.
    Suggestions come from an edit distance in which a swap of two letters counts as one
    edit, and the nearest name is offered when it is within a third of the misspelled
    name's length.
- **`prompt`** is the telnet prompt (§4.10). Its placeholders are `{<vital>}` and
  `{max_<vital>}` for declared vitals; another placeholder stops the server. A placeholder
  is `{name}` with `name` matching `[a-z][a-z0-9_]*`; braces that don't form one print as
  written. The default is `<` then `{<vital>}<vital>` for each vital, space-separated, then `> `.
- **Typed values.** Wherever the API takes or returns numbers keyed by schema names, it
  uses a constructor that checks its keywords against the schema, with a suggestion for a
  misspelling. A plain dict in their place is an error that names the constructor.

  | Constructor | Keys | Used for |
  |---|---|---|
  | `vitals(**n)` | declared vitals | skill `cost`, mob `vitals`, and the results of `mitigate`, `move_cost`, `regen` and `starting_stats` |
  | `stats(**n)` | declared stats | `stat_deltas` on items, affects and `apply_affect`; mob `stats`; the results of `encumbrance` and `starting_stats` |
  | `resists(**n)` | declared damage types | mob `resists` |

  Their values are ints, and they read back like structs (`cost.mana`). `DAMAGE` is a
  struct of the declared damage types (`DAMAGE.fire == "fire"`), so a misspelling fails at
  that line; plain strings are accepted wherever a damage type is, and checked too.
- **Where names are checked:**
  - At package load: `stat_deltas`, `cost`, mob `stats`/`vitals`/`resists`, item
    `damage_type`, the `damage(...)` and `heal(...)` primitives, `flee(below = ...)`,
    channels in `senses_blocked` and skill `channels`.
  - When the rules package loads: the dry run (§4.6).
  - At run time: actor view fields (`ctx.actor.lfe` is an error suggesting `life`), and the
    `type` and `vital` arguments of verbs.
- **Fixed per database.** The first start records the schema's names (stats, vitals,
  damage types and channels, in order) in `meta.world_schema`. A later start whose
  `MODULE.gups` declares different names refuses to start, lists what changed, and points
  to `--wipe` (§2.4). Labels, `prompt` and `unarmed_damage_type` aren't recorded and need
  only a restart. `gups migrate-check` fails the same way (§7.3).
  - The refusal reads "The world's schema in MODULE.gups differs from the one this database
    was started with:", then one line per kind that changed (`stats: added luck; removed
    wis`; a reordering reads `reordered`), then that saved characters and items can't
    follow a schema change and that `--wipe` starts again.
  - A wipe reports "Wiped N characters and M items; the previous database is at <prewipe
    backup>." and that the schema was recorded.

### 3.10 Feature Modules

The engine is a kernel plus four feature modules. A world lists the features it uses in
`MODULE.gups`, `features = ["combat", "track", "skills", "leveling"]`; leaving `features`
out turns all four on, and listing all four is the same as leaving it out.

| Feature | Owns | Requires |
|---|---|---|
| kernel (always on) | Sessions, the scheduler, persistence, hooks, timers, affects (the `apply_affect` and `remove_affect` verbs), movement through exits, `look`, communication, `who`/`help`/`score`/`quit`, items and equipment, doors and containers, `use`; vitals, conditions, incapacitation, death, corpses and respawn; the `damage` and `heal` verbs and `mitigate`; `regen`, `move_cost`, `carry_limit`, `encumbrance`, `starting_stats`, `condition`, `on_player_death` | — |
| `combat` | Engagement, `kill`, `flee`, combat rounds, initiative, auto-attacks; `to_hit`, `damage`, `initiative`, `flee_chance`; `before_attack`, `on_combat_tick`; the `aggressive`, `assist` and `flee` behaviors; `start_combat`, `stop_combat`, `enemies_near`, `allies_near`; the `fighting` and `initiative` fields of `Char.Status` | — |
| `track` | Track indices and bands, room `size`, exit `entry_index`, spawn `at_index`, item `range`, `advance` and `retreat`, pinning, `retreat_check`, `get_lag`, `knockback` and `pull`, the `SIZE` constants, `Room.Bands`, `@where`, `band_of`, a non-zero `index` argument of `teleport`, `spawn` and `move_item`, the skill ranges `melee`, `near` and `far`, `act_advance`/`act_retreat` | `combat` |
| `skills` | `skill()` declarations and the `skills` package kind, mob and player skills, `cast`, warmups and `CastState`, interrupts, `cast_allowed`, `interrupt_check`, skill costs, `before_cast`/`on_cast_start`/`on_cast`, `act_cast`, the effect primitives `damage`, `heal`, `apply_affect`, `dispel`, `stun`, `summon` and `sequence`, `casting` in `Char.Status` | — |
| `leveling` | `level` and `xp`, `xp_for_kill`, `xp_to_level`, XP loss at death, `aggressive(unless_level_above)`, level and XP in `Char.Status`, `score` and `who` | — |

- **A feature that is off doesn't exist** for the world:
  - Its commands don't exist ("Huh?"), and `help` and `gups docs --world` leave them out.
  - Its declarations, declaration arguments, hooks, behaviors and package kinds fail their
    package's load with "`<name>` needs the `<feature>` feature".
  - Its `ctx` verbs and view fields are errors naming the feature.
  - Its rules exports are neither required nor allowed (§4.6).
  - Its GMCP packages and fields aren't sent.
- **Without `track`,** every room has one track slot and every actor and item sits at index
  0, so combat, movement and items keep one code path; nothing shows bands. `look` lists
  them as `Also here: …` and `On the ground: …`. A skill's `range` may be omitted (any one target in
  the room), `self` or `room`. The rules `ctx.band` is `None`, and `get` costs no lag.
- **Without `combat`,** nobody engages. Damage from skills and verbs still lowers vitals and
  can still kill through `condition` (§5.4), with the last actor who damaged the victim as
  its killer. `quit` is never refused for fighting.
- **Without `skills`,** `use` and item `on_use` hooks are the way to act on things; affects
  still come from `ctx.apply_affect` and `equip_affects`.
- **Without `leveling`,** no XP is awarded and nothing levels up; `on_player_death`'s
  `xp_loss` is refused.
- **Renaming commands:** `rename = {"cast": "invoke", "kill": "attack"}` renames non-`@`
  engine commands, of the kernel or of an enabled feature. The old name stops being a
  command and is free for global or plugin commands. Aliases, prefixes, reserved exit and
  character names, `help` and the docs use the new names. Renaming a command that doesn't
  exist (or whose feature is off), or to a name that isn't `[a-z][a-z0-9_]*`, is a
  direction, or is another command or alias, stops the server.
- **Problems:** an unknown feature, or one whose requirement is off, stops the server.
- **Fixed per database:** the enabled features are recorded in `meta.world_features` with
  the schema and checked the same way (§3.9). A database that predates feature modules is
  taken to have all four. `rename` and `MESSAGES.gups` aren't recorded
  and need only a restart.
- **Plugins** declare the features they need (§4.15).

---

## 4. Interface & API Signatures

Builtins take structured arguments: keyword arguments and native Starlark values (lists,
dicts, `None`, booleans) rather than strings with a syntax of their own, so that the loader
can check them.

### 4.1 Package Declaration

```python
# zones/goblin_caves/package.star
package(
    api_version = 1,
    kind = "zone",                    # zone | lib | rules | skills
    title = "Goblin Caves",
    reset_world_ticks = 60,           # zone reset interval (30 min); fires on_reset
    modifiers = {},                   # zone-wide modifiers (§4.6)
    shared_namespaces = [],           # privileged packages only (§3.7)
    expected_tombstones = [],         # §7.3 gate 5
    migrate_noop = [],                # prototypes whose version bump needs no migrate()
)
```

Modifier functions a `package()` names are top-level `def`s in `package.star` itself, above
the `package()` call, because `package.star` can't load anything.

### 4.2 Content Declarations

```python
room("entrance",
    name = "Cave Entrance",
    desc = "...",
    size = SIZE.normal,                                # SIZE.cramped | SIZE.normal | SIZE.large
    regions = ["goblin_territory"],
    flags = [],                                        # "always_active", "dead_end_ok"
    exits = [
        exit(DIR.north, to=":tunnel",
             door=door(state=DOOR.closed, key="//zones/goblin_caves:rusty_key",
                       open_senses_blocked=[],
                       closed_senses_blocked=[SENSE.sight, SENSE.sound, SENSE.noise])),
        exit(DIR.east, to=":long_hall", senses_blocked=SENSE.all),  # never carries anything
        exit(DIR.south, to="//zones/forest:clearing", entry_index=-1),  # arrive at back
    ],
    spawns = [spawn(":goblin_scout", count=2, respawn_pulses=1200, at_index=2)],
    modifiers = {},                                    # §4.6
    commands = {"read": _read_sign},                   # zone verbs, tick lane
    before_exit = _guard_check,                        # hooks by name, §4.4
)

mob("goblin_scout",
    name = "a goblin scout", keywords = ["goblin", "scout"],
    level = 3, stats = stats(str = 10, dex = 14, con = 9, int = 6, wis = 6, armor = 2),
    vitals = vitals(hp = 30), resists = resists(fire = -10),   # schema-checked (§3.9)
    skills = {"//skills/martial:kick": 40},
    senses = {"hearing": 1, "sight": 0},
    behaviors = [wander(radius=4, chance=0.1), aggressive(toward="players"),
                 assist(allies=["goblin_*"]), flee(below=vitals(hp = 20))],
    think = _scout_think, think_every = 4,
    dormant_recovery = "full",       # full | regen | none (§5.9)
    loot = [loot(":rusty_key", chance=0.25)],
    version = 2,
)

item("short_bow",
    name = "a short bow", kind = "ranged", range = "far", weight = 2,
    wear = "wield", dice = "1d6", damage_type = "pierce",
    stat_deltas = stats(dex = 1),
    modifiers = {}, equip_affects = [],               # apply while worn (§4.6)
    stackable = False, decay_pulses = None,
    commands = {}, on_use = None,
)

item("satchel", container = container(capacity = 10, max_weight = 20,
                                      state = "closed", key = None))

region("null_field", modifiers = {...})               # region-wide modifiers (§4.6)

def migrate(old_version, attrs):                       # optional, per package (§3.4)
    if old_version < 2:
        attrs["alarm_raised"] = attrs.pop("alarm", False)
    return attrs
```

**Declaration rules:**

- **Arguments:** every declaration takes its identifier positionally (`id` in `room`, `mob`
  and `item`), everything else by keyword. `exit` takes `direction` positionally and `to`
  positionally or by keyword; `global_command` takes `name`, `fn` and `lane` either way.
  Unknown arguments are errors with a spelling suggestion. A package targeting a different
  `api_version` gets the version-mismatch diagnostic before any unknown-argument error.
- **Defaults:** the display `name` defaults to the identifier with `_` as spaces.
  `keywords` default to the display name's words minus articles, or else the identifier.
- **Damage type:** an item's `damage_type` is a declared damage type, `DAMAGE.fire` or the
  plain string, checked at load; an item that names none uses the world's
  `unarmed_damage_type`.
- **Item slots:** an item's slot is `wear = "<slot>"`; `wield = "<slot>"` is an alias. A
  slot family such as `wear = "finger"` fits the first free `finger_*` slot. An item with
  `equip_affects` or `modifiers` needs a `wear` slot. Each `equip_affects` entry is either
  an affect flag (for example `no_regen` or `silenced`), which the wearer has while the item
  is worn, or `{"flag": ..., "pulses": n}`, which is applied for `n` pulses when the item is
  equipped and runs out even if the item stays on. Equipping applies each entry to the wearer
  as an affect, through `apply_affect` like a spell's, and removing the item removes the
  untimed ones. `stunned` must be timed, so an item can't stun its wearer for good (§5.4.1).
- **Containers:** `container(capacity, max_weight, state, key)` makes an item a container.
  `capacity` counts items; `max_weight` weighs the contents.
- **Regions:** `region(name, modifiers = {...})` declares a region's modifiers. Region names
  are lowercase words and world-wide: one package declares each, and two packages declaring
  the same name both fail to load. A room may carry a region nobody declares; it is then
  only a tag.
- **Loot:** a loot `chance` is a probability between 0 and 1.
- **Mob numbers:** a mob's `stats`, `vitals` (maxima) and `resists` are its prototype's
  declared values, the input `rules.starting_stats` sees at each spawn (§4.6). There are no
  `hp` or `armor` arguments; those are schema names.
- **Features:** arguments that belong to a feature that is off (`size`, `entry_index`,
  `at_index` and `range` with `track`; `skills` with `skills`; `level` with `leveling`)
  fail the load, naming the feature (§3.10).
- **Constants:** Common engine constants are namespaced into structs (such as `DIR`,
  `MODIFIER`, `SIZE`, `SENSE`, `DOOR`, and `DAMAGE`) to prevent bare-string typos.
  The constant namespaces and their values are defined in
  `script/src/main/kotlin/gups/script/Declarations.kt` (`DeclarationBuiltins.constants`).
- **`struct(**fields)`** makes an immutable record. Libraries use it to export a namespace of
  functions (`quest.start`).
- **When builtins work:** declaration builtins work only while a package loads; effect
  primitives work anywhere.
- **`print()`:** in hooks its output goes to the trace; in tests, to the test's report (shown
  when the test fails); at load time it is dropped.

### 4.3 Skills and Spells

Everything in this section is the `skills` feature (§3.10).

```python
skill("fireball",
    kind      = "spell",              # free-form; rules interpret it
    cost      = vitals(mana = 25),    # schema-checked (§3.9)
    warmup    = 6,                    # pulses in CASTING; 0 = instant
    lag       = 4,                    # waitState pulses after resolution
    range     = "far",                # melee | near | far | self | room
    target    = "enemy",              # enemy | ally | self | item | none
    channels  = ["spell", "noise"],   # built-in or declared channels
    interrupt = {"damage_pct": 10, "stun": True},
    attack_while_casting = False,     # default; True lets auto-attacks continue during warmup
    effect    = damage(dice="4d6", type=DAMAGE.fire),
    resolve   = None,                 # optional custom Starlark effect: def f(ctx)
)
```

**Range semantics:** `melee` means a band of 0, `near` means ≤1, `far` means any band in
the same room, `self` targets the caster only, and `room` affects everyone in the room
except the caster (and, with a bubble, except dormant actors). Without the `track` feature,
`melee`, `near` and `far` are load errors, and an omitted `range` means any one target in
the room.

**Costs:** a cost is affordable when each named vital's `current` is at least the amount.
Otherwise the cast fails validation with "You don't have enough <label>." (§4.18).

**`interrupt`:** the engine reads only `stun`. The whole dict is given to
`rules.interrupt_check` as `ctx.skill.interrupt`, which decides what `damage_pct` means.

**Targeting:**
- An `enemy` skill with no keyword targets the caster's primary target.
- An `ally` skill with no keyword targets the caster.
- An `item` skill finds its item by keyword among worn, carried and ground items (ground
  items within `get_lag` reach). Effect primitives act only on actors, so an item skill does
  its work in `resolve`. There, and in the cast hooks, the item is `ctx.target`.
- The target is chosen at the first validation and stays fixed through the warmup.

**Proficiency** is stored but not rolled against: the rules have no proficiency input yet.
Rules functions can read it from `ctx.actor.skills`.

**Effect primitives (v0):**

| Primitive | Purpose |
|---|---|
| `damage(dice, type)` | Deal damage of a declared type, by default the world's `unarmed_damage_type`. The roll goes through `rules.mitigate`, which returns the vital losses (§4.6). |
| `heal(dice, vital)` | Restore a declared vital, up to its maximum. |
| `apply_affect(name, duration, stat_deltas=stats(), flags=[], tick_every=None, on_tick=None, on_expire=None, expire_msg="", modifiers={})` | Attach a timed buff or debuff. |
| `dispel(name=None, flag=None, all=False, count=None)` | Remove the target's affects that match every criterion given: `name` exactly, or as a prefix when it ends in `*`; `flag` (a string or a list) when the affect has any of those flags; `all=True` matches every affect. At least one criterion is required, checked at load. `count` caps how many are removed, oldest first. Affects flagged `intrinsic` are never dispelled. Each removed affect sends its `expire_msg` and fires its `on_expire` with reason `"removed"`. |
| `knockback(steps)` | Push the target away from the caster. On a shared index it goes toward the side with more room, ties toward the back (higher index). Clamped to the track. `track` feature only. |
| `pull(steps)` | Draw the target toward the caster, never past it. Clamped to the track. `track` feature only. |
| `stun(pulses)` | Stun the target. |
| `summon(label, count=1, duration=None)` | Spawn mobs beside the caster, each with `summonedBy` set to the caster. With a `duration`, each gets a `summoned` affect; when it expires or is removed, the mob fades away, disengages and leaves the world. |
| `sequence(*effects)` | Combine several primitives, passed as varargs. |

Spell `damage(...)` effects don't go through the `damage` modifier point; only auto-attacks
do (§4.6).

### 4.4 Hook Catalog

Every hook is a named top-level function. Hooks named `before_*` may return
`ctx.deny(msg)` to veto the action. Any other return value means "allow". The return values
of `on_*` hooks are ignored.

| Hook | Veto | Attach to | Key ctx fields |
|---|---|---|---|
| `on_load` | — | Room, Mob, Item | `self` |
| `on_spawn` | — | Room, Mob, Item | `self`, `item` |
| `on_reset` | — | Room (zone reset) | `self` |
| `on_wake(ctx, elapsed_pulses)` | — | Room, Mob, Item | `self`, `elapsed_pulses` |
| `before_enter` / `on_enter` | ✓ / — | Room, Mob (observer) | `actor`, `direction`, `from_room` |
| `before_exit` / `on_exit` | ✓ / — | Room, Mob (observer) | `actor`, `direction`, `to_room` |
| `on_say` | — | Mob, Room | `actor`, `text` |
| `on_tell` | — | Mob | `actor`, `text` (no `target`) |
| `before_get` / `on_get` | ✓ / — | Item, Mob (observer) | `actor`, `item` |
| `before_drop` / `on_drop` | ✓ / — | Item, Mob (observer) | `actor`, `item` |
| `before_use` / `on_use` | ✓ / — | Item | `actor`, `item`, `target` |
| `on_give` | — | Mob (receiver), Item | `actor`, `item`, `target` |
| `before_attack` | ✓ | Mob, Item (equipped) | `actor`, `target`, `weapon` |
| `before_cast` / `on_cast_start` / `on_cast` | ✓ / — / — | Mob, Item (equipped), Room | `actor`, `skill`, `target` |
| `on_damage` | — | Mob, Item (equipped) | `actor` (source), `target`, `amount`, `type`, `losses` |
| `on_death` | — | Mob, Room | `actor` (killer), `target` |
| `on_combat_tick` | — | Mob, Item (equipped) | `self`, `actor`, `targets` |
| `on_tick` | — | Any (world tick) | `self` |
| `on_nearby` | — | Mob (any; `senses` widen it, §5.7) | `event`, `actor`, `text`, `source_room`, `via_exit` |
| `think` | — | Mob | `self`, `proposals` |
| `commands[verb]` | — | Room, Item | `actor`, `args`, `target`, `item` |
| affect `on_tick` | — | Affect | `self` (host), `affect` |
| affect `on_expire` | — | Affect | `self` (host), `affect`, `reason` |

**Observer semantics:** Hooks on a mob fire for actions *by others in the same room*.
`ctx.self` is always the hook's owner.

**Context fields per hook:** Besides `self`, `room`, `pulse` and `rng`, the "Key ctx
fields" column is exact: those are the fields the engine may set for that hook, and every
other context field is `None`. The enter hooks set only `from_room` of the two rooms, and the
exit hooks only `to_room`.

**Catalog enforcement:** A hook keyword argument must be a catalog name (a typo is an error
that suggests the right one) and must be attachable to that kind of entity per the "Attach
to" column. `before_attack` and `on_combat_tick` need the `combat` feature, and the cast
hooks need `skills` (§3.10).

**`on_damage`:** `ctx.amount` is the total of the vital losses and `ctx.losses` the losses
themselves, a `vitals(...)` value (§3.9); `ctx.type` is the damage type. It fires once per
damage event, after the losses are applied.

**Timing:**
- `on_load` fires once on every entity, ascending id, after the world is built (or
  restored from the database) and global commands are installed. Spawns filled while the
  world is built fire `on_spawn` first. A reload fires `on_load` only on rooms it creates.
  Characters restored at login don't get it.
- `before_enter` dispatches in the destination room. On arrival the player's `look` is sent
  before `on_exit` and `on_enter` fire.
- `on_give` fires with the item as the primary owner, then the room and the others in it
  (so the receiver), with `ctx.target` the receiver. There is no `before_give`.

### 4.5 The `ctx` Object

`ctx` is created fresh for each hook invocation and is invalid after the hook returns.
Entity accessors return **read-only views**. Every mutation goes through a verb. The
engine validates each verb and applies it immediately, on the world thread.

**Context fields:** `self`, `actor`, `target`, `item`, `room`, `direction`, `text`,
`args`, `event`, `source_room`, `via_exit`, `skill`, `weapon`, `amount`, `type`, `losses`,
`proposals`, `elapsed_pulses`, `from_room`, `to_room`, `affect` (affect hooks), `reason`
(affect `on_expire`: `"expired"` when the affect reached its expiry pulse, else `"removed"`
for `remove_affect` or `dispel`; an affect removed on or after its expiry pulse counts as
expired), `targets`
(`on_combat_tick`), `pulse`, `rng`.

- **Commands:** `ctx.args` is the argument text, `ctx.target` the actor in the room matching
  the first argument word, and `ctx.item` the item for item commands. `ctx.self` is the room
  or item, or the actor for global commands.
- **Item targets:** `ctx.target` is the target item for `use` and item skills.

**Views:**
- Views read live state, and compare equal when they show the same entity.
- `attrs` shows the reader's own keys by short name.
- Using a `ctx` or view after its hook returned is an error.

**Actor view:**
- Identity: `id`, `name`, `label`, `keywords`, `is_player`, `in_world`.
- Schema fields (§3.9), generated from the world's schema:
  - `<stat>`, the effective value, for each declared stat (`ctx.actor.dex`), and
    `base.<stat>` for the base value.
  - `<vital>` and `max_<vital>` for each declared vital (`ctx.actor.hp`,
    `ctx.actor.max_hp`), and `below_zero_since(vital)`, the pulse the vital last dropped
    below 0, or `None`.
  - `resists.<type>` for each declared damage type.
  - An undeclared name is an error with a suggestion.
- Life: `condition`, one of `"ok"`, `"incapacitated"` and `"dead"`.
- Leveling (`leveling` feature): `level`, `xp`.
- Position: `room`, `track_index` and `band_of(other)` (`track` feature), `home_room`,
  `recall_room`.
- State: `attrs`, `affects`, `skills` and `is_casting`/`casting` (`skills` feature),
  `flags`, `has_flag(flag)`, `equipped(slot)`, `inventory()`, `carried_weight`,
  `has_item(label)`, `fighting()` (`combat` feature), `wait_state`.

**Room view:**
- Identity: `label`, `name`, `desc`, `size`, `track_size`, `regions`, `flags`.
- Contents: `exits()`, `actors()`, `items()`, `contents()`.
- `exit(dir)`, an exit view that includes its effective `senses_blocked` and door state.

**Item view:** `id`, `label`, `name`, `keywords`, `desc`, `kind`, `count`, `weight`,
`location`, `attrs`, `container_state`, `contents()`, `dice`, `damage_type`, `range`, `wear`,
`stat_deltas`, `in_world`.

**Skill view:** includes `cost`, `channels` and `interrupt`.

**Verbs (v0):**

| Group | Verbs |
|---|---|
| Messaging | `say(speaker, text)`, `emote(actor, text)`, `send(target, text)` (one recipient), `echo(room, text, exclude=[])` |
| Veto | `deny(msg)`. Valid only as a return value from `before_*` hooks or `cast_allowed` modifiers. |
| Mob actions | `act(mob, command_line)`, `act_move(mob, dir)`, `act_attack(mob, target)`, `act_cast(mob, skill, target=None)`, `act_advance(mob, target)`, `act_retreat(mob, target=None)`, `move_toward(mob, dir_or_target)`. These enqueue tick-lane actions and apply only to mobs, never players. |
| Movement | `teleport(actor, room, index=None)`. Engine-validated; fires the enter and exit hooks. |
| Vitals and combat | `damage(target, amount, type, vital, source=None)`, `heal(target, amount, vital)`, `start_combat(a, b)`, `stop_combat(a, b=None)`, `apply_affect(target, ...)`, `remove_affect(target, name)` |
| Items | `spawn(label, at, index=0, count=1)`, `give(actor, label_or_item, count=1)`, `move_item(item, to)`, `destroy(item)`, `split(item, n)` |
| Doors and exits | `open_door(room, dir)`, `close_door`, `lock_door`, `unlock_door`, `set_exit_senses_blocked(room, dir, channels)` |
| State | `set_attr(entity, key, value)`, `get_attr(entity, key, default=None)`, `del_attr(entity, key)` |
| Timers | `after(pulses, fn, **args) -> timer_id`, `cancel(timer_id)` |
| Simulation | `set_active(entity, reason)`, `clear_active(entity, reason)`, `set_anchor(entity, reason)`, `clear_anchor(entity, reason)` |
| Queries | `here(keyword)`, `find(label, scope="room")`, `enemies_near(actor, band)`, `allies_near(actor, band)`, `path_to(target) -> [dir]`, `roll(dice) -> int` |
| Randomness | `rng.int(lo, hi)`, `rng.chance(pct)`, `rng.choice(list)`. Seeded and deterministic; there is no other source of randomness. |

**Verb semantics:**
- **Errors:** a bad verb argument raises a script error at once. Invalid-argument and
  invalid-state errors from the engine become script errors too.
- **`deny`** raises unless the running hook is a `before_*` hook (or a `cast_allowed`
  modifier).
- **Timers:** callbacks are called as `fn(ctx, **args)` and also see the arguments as
  `ctx.args`. The timer's owner is the scheduling hook's `ctx.self`.
- **`damage`** is raw: no to-hit roll and no mitigation. It lowers the named vital by
  `amount`, with no floor, and fires `on_damage` with `losses` holding just that vital.
  **`heal`** raises the named vital up to its maximum. Both name a declared vital and
  damage type, checked when called: an undeclared name raises an error with a suggestion
  before anything changes.
- **Features:** `start_combat`, `stop_combat`, `enemies_near` and `act_attack` need
  `combat`; `act_cast` needs `skills`; `act_advance`, `act_retreat` and the `index`
  arguments of `teleport`, `spawn` and `move_item` need `track` (§3.10).
- **Items:**
  - `spawn` returns a view, or a list when it makes several entities. For items, `at` may
    be a room or room label (ground, at `index`), an actor (carried) or a container.
  - `give(actor, label_or_item, count=1)` spawns or moves the item into the inventory and
    fires no hooks.
  - `move_item` takes `index=0` for rooms.
  - `split` copies the stack's attributes and decay deadline to the new item, and refuses
    equipped stacks.
  - Verbs never merge stacks.
- **Doors:** door verbs set both sides at once and print nothing. `open_door` also unlocks;
  `close_door` leaves a locked door locked; `lock_door` closes the door; `unlock_door`
  leaves it closed.
- **Queries:**
  - `here(keyword)` searches the owner's room: actors first, then ground items.
  - `find(label, scope)` counts entities whose location chain ends in the room, so items
    carried there count. `"zone"` means rooms of the owner's room's package.
  - `enemies_near` returns engaged opponents. `allies_near` returns actors on the same side
    (players with players, mobs with mobs) not engaged with the actor.
  - `path_to` returns an empty list when already there and `None` when unreachable (§5.9).
- **Mob actions:**
  - They return whether the action was queued.
  - `move_toward(mob, x)` takes a direction, advances on an actor in the same room, or takes
    the first step of `path_to`. It returns `False` when there is no way.
  - `act_cast` requires the mob to know the skill.
- **Attributes:** `get_attr` returns a mutable copy.
- **Simulation verbs** return `None`. A reason added twice counts once.
- **`apply_affect`** takes `modifiers`, as the effect primitive does.

Verbs without a player command (`say`, `emote`, door and exit-block setters, `split`,
`path_to`, and the mob actions) are public `core` functions, so Kotlin and plugins share them.

### 4.6 Rules Module and the Modifier Chain

The rules package is required, and its `//rules:combat.star` (the package is named
`rules`, kind `rules`; `gups lint` reports its absence) defines the world's rules as
the functions below. The engine has no built-in ruleset and nothing to fall back to: every
export that an enabled feature uses (the "Feature" column) must be defined, and an export
of a feature that is off must not be. The engine calls them with a rules `ctx` that is
read-only, apart from `deny`. Any other public top-level function in `combat.star` (one
whose name doesn't start with `_`) is a load error for the rules package. A misspelled
export can't be silently ignored; helpers take a `_` prefix.

**The rules `ctx`** has `actor`, `attacker` (the same actor), `defender` and `target` (the
other actor, if any: the defender, the pinner, the killed or the killer), `weapon`, `item`,
`band` (a word, or `None` without the `track` feature), `skill`, `room` (the actor's),
`pulse`, `rng`, `roll(dice)`, and `deny(msg)`, valid only in `cast_allowed` and its
modifiers. It has no verbs. Its actor views have the schema fields of §4.5.

| Function | Feature | Returns |
|---|---|---|
| `equipment_slots()` | kernel | `list[str]`, the equipment slots. |
| `starting_stats(ctx)` | kernel | `struct(stats = stats(...), max = vitals(...), current = vitals(...))`, every field optional. Numbers for a new character or a spawned mob. |
| `condition(ctx)` | kernel | `"ok"`, `"incapacitated"` or `"dead"`. |
| `mitigate(ctx, amount, type)` | kernel | `vitals(...)`, the vital losses that `amount` damage of `type` causes. |
| `move_cost(ctx)` | kernel | `vitals(...)`, what a room transition spends. |
| `regen(ctx)` | kernel | `vitals(...)`, what each vital gains per world tick (negative to lose). |
| `carry_limit(ctx)` | kernel | Weight. |
| `encumbrance(ctx)` | kernel | `stats(...)`, deltas applied while over the limit. |
| `on_player_death(ctx)` | kernel | `{"respawn": label, "vitals": vitals(...), "xp_loss": int}` |
| `to_hit(ctx)` | `combat` | Hit chance in percent. `ctx.attacker`, `defender`, `weapon`, `band`, `skill`. |
| `damage(ctx)` | `combat` | Raw auto-attack damage, an int. |
| `initiative(ctx)` | `combat` | Int; higher acts first within a combat round (§5.4.1). `ctx.actor`, `ctx.rng`. |
| `flee_chance(ctx)` | `combat` | Percent. |
| `retreat_check(ctx)` | `track` | Success chance in percent, used when pinned. |
| `get_lag(ctx, band)` | `track` | Extra lag for picking up an item at a band. `None` means not allowed. |
| `cast_allowed(ctx)` | `skills` | `None` or `deny(msg)`. |
| `interrupt_check(ctx, amount)` | `skills` | Whether a casting actor is interrupted, a bool. |
| `xp_for_kill(ctx)`, `xp_to_level(level)` | `leveling` | Ints. |

**Signatures and results:**
- `mitigate(ctx, amount, type)` gets the damage type as a string, `get_lag(ctx, band)` the
  band as a word, and `interrupt_check(ctx, amount)` the spell being cast as `ctx.skill`
  and the total of the damage event's losses as `amount`. `equipment_slots()` and
  `xp_to_level(level)` get no ctx.
- `mitigate` is called for auto-attacks and the `damage(...)` effect primitive, never for
  the raw `ctx.damage` verb. Its result may name any vitals, so a world can send psychic
  damage to `sanity` or let a `shield` vital soak damage first. Each loss is subtracted
  with no floor.
- `move_cost` spends from each named vital. A transition the actor can't afford (some
  vital's `current` below its cost) fails with "You are too exhausted." (§4.18).
- `regen` adds its result to each vital, up to the vital's maximum; vitals it doesn't name
  don't change.
- `starting_stats(ctx)`: `ctx.actor` is the new actor. A player has every stat and vital at
  0; a mob carries its prototype's declared `stats`, `vitals` (as maxima) and `resists`.
  The result overrides those values. A `current` it doesn't give equals the maximum, so a
  vital that starts empty gives both: `max = vitals(rads = 100), current = vitals(rads = 0)`.
  It runs before the actor enters the world, so it can randomize with `ctx.rng`.
- `condition(ctx)` decides `ctx.actor`'s life state (§5.4): after any change to one of the
  actor's vitals, and for every simulated actor at each world tick, so a rule such as "dead
  after 60 pulses with `oxygen` below zero" can use `below_zero_since`.
- `on_player_death`: `respawn` resolves relative labels against the rules package;
  `vitals` sets the respawned character's current vitals (capped at their maxima);
  `xp_loss` is allowed only with the `leveling` feature.
- `get_lag` returns `None` for out of reach.
- `equipment_slots` returns lowercase slot names. It is asked once, and again after each
  reload.

**The dry run.** Rules functions are pure, so each time the rules package loads (at
startup, on reload and on deploy, and in `gups lint`), the engine calls every export it
defines against synthetic actors built from the schema: a fresh actor after
`starting_stats`, both as a player and as each mob prototype, and `mitigate` once per
declared damage type. The actors live in a throwaway world with one room: two players and
one mob per loaded mob prototype. Each actor is called against another (the first player
against the second, everyone else against the first player), unarmed. `get_lag` runs once
per band, and `cast_allowed` and `interrupt_check` get a synthetic skill with
`interrupt = {"damage_pct": 10}`. A mob whose own package failed to load isn't checked.
Only the package named `rules` is dry-run; a test world that names another rules package
(§4.12) isn't. An error, a wrong return type or an undeclared name in a result
fails the rules package's load with the usual diagnostic. A branch the dry run doesn't
reach is checked when it first runs.

**Engine-only rules:** The track-movement lags are fixed values of the `track` feature, not
Starlark exports in `api_version` 1: advance 2, retreat (success) 2, failed retreat 4. A
failed flee (`combat`) costs 4.

**Template ruleset.** The template world's `rules/combat.star` implements these, for its
schema (§3.9). They are the formulas an instructor starts from:

| Function | Formula |
|---|---|
| `equipment_slots` | `head, neck, body, arms, hands, finger_l, finger_r, waist, legs, feet, wield, offhand, held` |
| `starting_stats` | players: every stat 10 (`armor` 0), `hp` 20, `mana` 20, `move` 100; mobs: their prototype's values, with every stat 10 (`armor` 0), `hp` 10, `mana` 0 and `move` 100 where the prototype sets none |
| `condition` | `"dead"` at `hp ≤ 0`, else `"ok"` |
| `to_hit` | `60 + 2·(dex_a − dex_d) + 3·(level_a − level_d)`, clamped 5..95. Armor is not used here. |
| `damage` | weapon dice (unarmed `1d4`) + `(str − 10) / 2`, at least 1 |
| `mitigate` | `hp` loses `max(1, amount − armor/2)`, then `× (100 − resist) / 100`, at least 0 |
| `initiative` | `dex + level` |
| `interrupt_check` | `amount` ≥ the skill's `interrupt.damage_pct` percent of `max_hp` |
| `retreat_check` | `50 + 3·(dex − pinner dex)`, clamped 5..95 |
| `flee_chance` | `40 + 2·(dex − 10)`, clamped 5..95 |
| `move_cost` | `move` 1 |
| `regen` | hp `1 + con/4 + level/2`; mana `1 + (int + wis)/8`; move `2 + dex/4` |
| `carry_limit` | `str × 10` |
| `encumbrance` | `dex` −2 |
| `get_lag` | 0 for `MELEE`, 2 for `NEAR`, not allowed at `FAR` |
| `xp_for_kill` | `50 × victim level` |
| `xp_to_level` | `1000 × level` |
| `on_player_death` | respawn at the recall room with `hp` 1, lose 10% of XP |

**Modifier points (v0):** `to_hit`, `damage`, `initiative`, `cast_allowed`, `move_cost`,
`regen`. The list grows with `api_version`. `mitigate` is not a modifier point. Modifiers
of `move_cost` and `regen` take and return `vitals(...)` values; modifier points of a
feature that is off can't be named.

**Whose chain:** the attacker's for `to_hit` and `damage`, the caster's for `cast_allowed`,
the mover's for `move_cost` (from the room being left), and the actor's own for `regen` and
`initiative`. "Zone" means the package of that actor's room, and the regions and room are
those of that room too.

**Chain order:** base rules result, then zone `package(modifiers=)`, then region
modifiers (sorted by region name), then room modifiers, then the actor's affects (sorted by
affect id), then its worn items (in `equipment_slots()` order, then any other slots by
name). Worn items contribute their `item(modifiers=)`; affects contribute the `modifiers`
given to `apply_affect`.

Each modifier is a top-level function with the signature `fn(ctx, base) -> value`, or
returns `ctx.deny(msg)` (only for `cast_allowed`).
- A `cast_allowed` chain stops at the first denial.
- A failing modifier, or one that returns the wrong type, leaves the value as it was.

```python
def _halve_melee(ctx, base):
    return base // 2 if ctx.weapon and ctx.weapon.kind == "melee" else base

def _fizzle(ctx, base):
    return ctx.deny("Your magic fizzles into nothing.")

room("antimagic_vault", modifiers = {
    "to_hit":       _halve_melee,
    "cast_allowed": _fizzle,
})
```

The harness and `@trace` print the full chain, for example
`rules.to_hit base=62 → region:null_field ×0.5 → 31 → roll 44 MISS`.

**Encumbrance:** `rules.encumbrance` applies while the carried and worn weight exceeds
`rules.carry_limit` (computed without the current encumbrance). It is recomputed when an
actor's custody changes (spawn, move, destroy) and at every world tick, since stats can
change the limit. The actor is told when it starts and when it lifts. The carry-limit
check counts a container's contents.

**Equipment:** Slots come from `rules.equipment_slots()`. Wearing into an occupied slot
moves the old item back to the inventory; a slot family whose slots are all full swaps out
the first. `wield` only takes items whose slot is `wield`; `wear` takes any slotted item; `remove`
(`unwield` in the template world) takes off an item in any slot, worn or wielded.

### 4.7 Behaviors (built in, implemented in Kotlin)

| Behavior | Priority | What it does |
|---|---|---|
| `wander(radius=3, chance=0.1)` | 10 | Moves randomly within `radius` exits of the home room, counted through open doors. `chance` is the probability per think. |
| `aggressive(toward="players", unless_level_above=None)` | 60 | Attacks the lowest-id matching target on sight. `combat` feature; `unless_level_above` also needs `leveling`. |
| `assist(allies)` | 50 | Attacks an ally's primary target, or else its lowest-id opponent. `combat` feature. |
| `flee(below=vitals(...))` | 100 | Flees, engaged or not, when any listed vital is at or below that percentage of its maximum: `flee(below = vitals(hp = 20))`. `combat` feature. |
| `guard(exit, allies="mobs")` | — | Denies the exit to non-allies with "<Guard> blocks your way." A built-in `before_exit` observer that runs before the scripted ones, only while the guard is simulated and able to act. |
| `follow(target)` | 30 | Takes the first match it sees as leader and follows it within 3 exits; farther, it forgets the leader. |
| `pursue(target)` | 40 | Heads for the nearest simulated match by path length, then lowest id, using `path_to` across the whole world. |
| `sentinel()` | — | Never leaves its room: removes movement and flee proposals. |
| `scavenge()` | 5 | Takes the first reachable, carriable ground item that isn't a corpse. |

- **Whom they mean:** `players`, `mobs`, `all`, or label patterns with `*`, as a string or a
  list. A pattern without a package (`goblin_*`) matches the label name in any package;
  `:goblin_*` means this package.
- **Engagement:** behaviors other than `flee` propose nothing while engaged.
- **Failures:** a behavior that throws is logged as an engine diagnostic and proposes
  nothing.
- Built-in behaviors are data values with value equality, so the version-bump lint (§7.3)
  can compare them.

Each behavior produces at most one `Proposal(commandLine, priority, source, action)` per
think cycle. The `action` fixes the target the behavior chose; `commandLine` describes it
and is what `ctx.proposals[i].command` shows.

### 4.8 Mob Think

```python
def _scout_think(ctx):
    # ctx.proposals: list sorted by priority desc; ctx.proposals[0] is what would run.
    if ctx.self.hp < ctx.self.max_hp // 2 and ctx.allies_near(ctx.self, "far"):
        return ctx.act(ctx.self, "shout Help!")
    return None   # None = accept the top proposal; ctx.act_* = override; "idle" = do nothing
```

- If `think` queued an action for its own mob through `ctx.act*`, the top proposal is not
  queued as well.
- A failing `think` falls back to the top proposal.

### 4.9 Commands

**Player commands, fast lane:** `say`, `tell`, `gossip`, `shout`, `whisper`, `look`,
`score`, `inventory`, `equipment`, `keyring`, `who`, `help`, `quests`, `stop`, `clear`,
`quit`.

Commands of a feature that is off don't exist, and a world can rename any non-`@` engine
command (§3.10); this section uses the default names. Every player-facing message here is
the default text of an engine message (§4.18).

- **`tell <name>`** finds an in-world player by name first, otherwise a mob in the room by
  keyword, which receives `on_tell`.
- **`shout`** reaches the room and every room whose exit toward it doesn't block `noise`
  (§5.7), players included.
- **`whisper`** shows bystanders "X whispers something to Y."
- **`keyring`** lists the keys on the keyring: carried items with `kind = "key"` and no
  other purpose (no `wear` slot, `container`, `commands`, `dice`, `stat_deltas`,
  `equip_affects` or `modifiers`). `inventory` leaves them out; item commands still find
  them by keyword.
- **`gossip`** goes to every player, including link-dead ones (who see nothing).
- A `silenced` speaker can't use `say`, `tell`, `gossip`, `shout` or `whisper`: it is told
  "You can't get the words out!", nobody hears anything, and no `on_say` or `on_tell` fires.
- **`quit`** is refused while engaged: "No way! You're fighting for your life!" The
  link-dead auto-quit ignores combat.
- **`score`** lists the character's stats and vitals by label in schema order, its resists
  by damage type, and its level and XP with the `leveling` feature. It prints the name,
  the stats as `<label> <value>`, the vitals as `<label> cur/max`, `Resists: <type> <n>`
  for every declared damage type, and `Level N (X xp)`.
- **`look`** describes another actor's health by its condition first: "is incapacitated" or
  "is mortally wounded", and otherwise by its lowest vital as a share of its maximum
  (among vitals with a positive maximum): 100% or more "is in excellent condition", 75%
  "has a few scratches", 50% "is wounded", 25% "is badly hurt", below that "is in awful
  condition". Onlookers see an incapacitation only as "<actor> collapses." and
  "<actor> stirs.".

**Player commands, tick lane:**
- Movement: `north`, `south`, `east`, `west`, `up`, `down` and other exits, `advance`,
  `retreat` (`track`), `flee` (`combat`).
- Combat: `kill` (`combat`), `cast` (`skills`), `use`.
- An incapacitated actor (§5.4) can't use tick-lane commands: "You can't do that while
  incapacitated." Its queue is kept.
- Items: `get`, `drop`, `give`, `put`, `wear`, `wield`, `remove`.
- Doors: `open`, `close`, `lock`, `unlock`.
- Zone-defined `commands`.

**Item and combat command details:**
- **`kill`** engages, switches the attacker's primary target, costs no lag, and does not
  swing immediately: the first swing comes at the next combat round. `kill` on the current
  primary target is a no-op ("You're already fighting them!").
- **`get X from Y`** uses the container's ground band for `get_lag`; a container carried by
  the actor counts as `MELEE`. The container must be open.
- **`give <item> [to] <actor>`** gives the whole stack, and fails if the receiver can't carry
  it.
- **`put <item> in|into <container>`:** the container is carried, or on the ground within
  `get_lag` reach, and open.
- **Containers:** `open`, `close`, `lock` and `unlock` take a container when the word isn't a
  door direction here.
- **Keys:** locking or unlocking a door or container needs its key carried (keyring
  included) or equipped.
- **`use <item> [target]`:** the item is carried or worn and must have an `on_use` hook ("You
  can't use that." otherwise). `before_use` can veto. The target is an actor in the room,
  else an item. The engine never uses an item up; `on_use` destroys it if it should be.
- **Stacks:** `get`, `drop`, `give` and `put` merge a stackable item into a stack of the same
  prototype in the same place with identical attributes and decay deadline, and no timers
  or name override on either (§3.8 inv. 8).

**Global commands:** Privileged packages may register global commands:
`global_command("quests", fn, lane="fast")`. This is how `//lib:quest.star` provides
`quests`, which lists active and finished quests.

**Reserved exit names:** an exit's direction or custom name may not be the name or alias
of an engine command the world has (after `rename`, §3.10), or the name of any global
command. Such an exit fails its package's load with
a diagnostic saying the exit name is a reserved keyword.

**`//lib:quest.star`:** `quest.define(id, title, steps)` returns a quest description.
`start`, `advance(..., from_step=None)`, `complete`, `step`, `is_active` and `is_done` take
`(ctx, actor, quest)`. Progress is a `quests` dict in the lib's own `lib` namespace on the
player, so only the library writes it (§3.7).

**Roles and gating:** A command can require a role. To players without it the command
doesn't exist: they get "Huh?", and `help` leaves it out. This applies to engine `@` tools
and plugin commands alike. `@` commands registered from outside `core` are tools, and an `@`
prefix only matches other `@` commands.

**Builder commands** (scoped to the builder's own packages; `debug` entities only):

| Command | Purpose |
|---|---|
| `@goto <label\|player>` | Move to a room of the builder's packages (index 0), or to any player (their track index), through `teleport`, so hooks fire. A player's name wins over a label. Going to a player can be taken away per account (Restrictions, below). |
| `@stat <target>` | Show an entity's full state. The target is a keyword (actors first, then carried and ground items), `here` or `#id`. |
| `@spawn <label> [n]` | Spawn 1 to 10 mobs or items (flagged `debug`). |
| `@trace [all\|zone [pkg]\|entity <target>\|hook <name>\|rules\|off]` | Stream hook execution (§5.13). |
| `@errors [package]` | Show the last 10 diagnostics. |
| `@where` | Show your track index and bands numerically (`track` feature). |
| `@eval <expr>` | Starlark REPL; `--dev` only. |

- **Ownership:** a builder owns the packages in their scope. Rooms belong to their package,
  mobs and items to their prototype's. Players and engine items (corpses) belong to the
  package of the room they are in. Every builder command is refused outside the builder's
  packages with "That isn't one of your packages."; `@where` checks the room the builder
  stands in. Instructors and admins see and act on everything, as does `--dev`'s local
  instructor.
- **Labels:** `//pkg:name`, or `:name` for the package of the builder's room.
- **`@stat`** shows identity and version, location, vitals, effective and base stats,
  resists, skills, affects with seconds left, what the actor is doing, carried and worn
  items, timers, attributes grouped by namespace, and simulation state with reasons. Vitals
  show `cur/max` and `belowZeroSince` when set, and the condition is shown. Mobs
  also show their debug owner; players their account, role and scope.
- **`@spawn`:** builders may spawn both mobs and items of their own packages ("`debug`
  entities only" means every spawned entity is flagged `debug`). Mobs appear at the
  builder's index; items go into the builder's inventory (a stackable item as one stack).
  Debug entities have no audit trail and are never saved, so debug spawns can't leak into
  the real economy:
  - A debug mob despawns at the first world tick after its spawner is gone, or after 30
    minutes.
  - Killing a debug mob awards no XP. Everything it drops (inventory, loot, corpse) is
    `debug` too.

**Instructor commands** (global):

| Command | Purpose |
|---|---|
| `@reload <package...>` | Reload loaded packages (a manual auto-reload). Refused in `--prod`, with a pointer to `@deploy` (§1.4). |
| `@deploy [commit]` | Deploy the fetched tip of `deploy_branch`, or the given commit, through the deploy pipeline (§5.10), reporting progress in game. |
| `@quarantine <pkg>` / `@unquarantine <pkg>` | Quarantine or release a loaded package; each takes a routine checkpoint so the state persists. |
| `@bubble` | Active rooms per package, every anchor with its reasons and room, entities with active reasons in dormant rooms, and dormant-room counts per package. |
| `@stats` | p50 and p99 of the last minute's pulse durations, its overruns, input lines waiting, active rooms, sessions per transport, the writer's backlog in batches, and the package with the most script steps this minute. |
| `@who` | Each player's role, transport and address (or link-dead time) and idle time. |
| `@snapshot` | A routine checkpoint, a wait for the writer, then a `manual` backup. |
| `@shutdown [seconds] [reason]` | A first word that isn't a number starts the reason. Warns at once, then at 60, 30, 10 and 5 seconds left. |
| `@audit item <id>` | The item's committed `item_events` with UTC wall times, plus where it is now if it is in the world. Changes not yet checkpointed don't show. |
| `@role <account> [<role> [packages] \| reset \| revoke \| disable [reason] \| enable]` | Show or set an account's role, or disable it (below). |
| `@invite <role>` | Create an invite code. |
| `@restrict <account> <capability> [length] [reason]`, `@unrestrict <account> <capability>`, `@restrictions [account]` | Per-account restrictions (below). |

**`@role`:**
- `@role <account>` shows the role, GitHub handle, both scopes and whether a password is set.
- `@role <account> builder <packages>` sets the manual scope (§3.5). Packages are comma- or
  space-separated, with or without `//`, and must be loaded. Giving a role without packages
  keeps the old manual scope.
- Instructors may set player, builder and instructor. Only admins may make, change or reset
  admins, or invite them. Nobody may change their own role.
- A change applies at once to the account's characters in the world: role, scope and idle
  exemption.
- `@role <account> reset` clears the password, revokes web tokens and shows a 7-day setup
  code bound to the account. `@role <account> revoke` deletes the account's web tokens only.
- `@role <account> disable [reason]` disables the account (§3.5).
  - Each of its characters in the world is quit with "This account has been disabled.",
    including link-dead characters and characters in a fight. Connections still at login or
    the character menu get the same line and are closed with "Goodbye.". The player isn't
    told the reason.
  - A login already in progress when the account is disabled is refused or closed, so no
    session of the account outlives the instructor's reply.
  - `@role <account> enable` clears the flag and its record.
  - Both are refused on the instructor's own account ("You can't disable your own account." / "You can't enable your own account."),
    and on an admin's account unless the instructor is an admin ("Only an admin can disable an admin." / "Only an admin can enable an admin.").
    Disabling a disabled account, or enabling an enabled one, changes nothing and reports "<login> is already disabled." or "<login> is already enabled.".
    On success, they report "<login> is now disabled." or "<login> is now enabled.".
  - A character with no account (a `--dev` character) can't disable: "Only an account can
    disable an account.". So an account with no `disabledBy` was always disabled by the roster.
- For a disabled account, `@role <account>` also shows an indented line "disabled by <login>
  <UTC time>: <reason>", without the `: <reason>` suffix when there is no reason and with
  `#<id>` in place of the login if the disabling account no longer exists. With no
  `disabledBy`, the line is "disabled by the roster".

**Restrictions:** instructors can take a capability away from one account, for a while or
for good, without changing its role.

| Command | Purpose |
|---|---|
| `@restrict <account> <capability> [length] [reason]` | Adds a restriction. Without a length, the next step of the ladder applies. |
| `@unrestrict <account> <capability>` | Lifts the active restriction, permanent or not. The history stays. |
| `@restrictions [account]` | Restrictions in effect, or one account's full history. |

- **Capabilities:** `goto_player`, `goto`, `spawn`, `stat`, `where`, `trace`, `errors` and
  `gossip`; the list grows as commands are added. Each builder-command capability guards
  that command, `gossip` guards the `gossip` command, and `goto_player` guards going to a
  player with `@goto`.
- **Scope:** instructors and admins can't be restricted.
- **Effect:** the command still exists for a restricted player, unlike a missing role.
- **Lengths:** `<n>m`, `<n>h`, `<n>d`, `<n>w` or `permanent`. A third word that isn't a length
  starts the reason. Without a length, the next step of the server configuration's
  `restriction_ladder` applies (default `["1d", "7d", "permanent"]`, so the first offence is
  a day and the third is permanent until an instructor lifts it). Every earlier restriction
  of the same capability on the account counts toward the ladder, whatever its state
  (lifted and expired included); past the end, the last step repeats. The ladder is checked
  at startup.
- **Checking:** the world keeps every restriction in memory (loaded at startup, updated by
  the commands) and checks them against the wall clock, like session tokens, so they run out
  while the server is down. They are stored in the `restrictions` table (§3.6) through the
  checkpoint writer, like other account changes.
- **Message:** "You can't do that right now.", plus "That changes <UTC time>." for a timed
  restriction. The restricted player isn't told when one is set.
- **`@restrictions`** without an account lists those in effect; with one, the full history:
  state, who set it and when, the reason, and who lifted it. "Set by" is the instructor's
  account, or none for `--dev` characters without one.

### 4.10 Network Protocol

- **Listeners:**
  - Raw telnet on `:4000` (`--dev`, or explicitly enabled).
  - Telnet over TLS on `:4443`.
  - WebSocket over TLS (`wss://host/ws`), with the bundled web client served from
    `https://host/`.
- **Netty listeners:** all listeners run on Netty 4.2 with the NIO transport, one boss
  thread and Netty's default worker count per listener. Raw telnet and telnet over TLS build
  the same pipeline; TLS adds an `SslHandler` first. `TelnetConnection` (option
  negotiation, line framing, buffering) sits behind a byte-in, byte-out interface, so only
  the socket layer knows about Netty. Over TLS a session starts, and the world hears `Connect`, only once
  the handshake succeeds; its transport is `tls`, raw telnet's is `telnet`. Each sink call
  is written and flushed at once, and unwritten bytes count toward the output limit.
  `Disconnect` is posted whenever the channel closes (a no-op after a world-side close).
  Session ids come from one counter shared by all listeners.
- **TLS:** a PEM certificate chain and a PEM PKCS#8 key (certbot's `fullchain.pem` and
  `privkey.pem`), loaded with Netty's JDK provider. TLS 1.3 and 1.2 only, with the JDK's
  default cipher suites. Every hour the server compares both files' modification times and
  rebuilds the context if either changed, so an external ACME client such as certbot can
  renew them without a restart (the engine doesn't do ACME itself); a pair that fails to
  load is logged and the old context stays. Open connections keep their certificate. `--self-signed` makes an EC P-256
  certificate signed with SHA-256, valid for 30 days, for `localhost`, `127.0.0.1` and the
  bind host when it is a specific one, in a fresh temporary directory.
- **Telnet options:** NAWS, TTYPE/MTTS, GMCP, EOR (falls back to GA), and CHARSET (UTF-8
  only). No MSDP or MXP.
  - On connect the server sends DO TTYPE, DO NAWS, WILL EOR, WILL GMCP and WILL CHARSET.
    Requests for anything else are refused once. Nothing is re-sent for an option whose
    state didn't change.
  - TTYPE is asked up to three times, or until an answer repeats. NAWS is stored.
  - CHARSET: the server requests `UTF-8` and accepts a client's request only if it lists
    UTF-8. Output is UTF-8 regardless.
  - GA is sent only to clients that have sent at least one telnet command, so plain `nc`
    sessions don't see a GA byte after every prompt.
  - **Echo:** password prompts send `IAC WILL ECHO` / `IAC WONT ECHO`, only to clients that
    have sent a telnet command, and only when the state changes. `DO ECHO` is accepted
    silently while input is hidden and refused once otherwise; `DONT ECHO` is never
    answered, so the exchange can't loop. After each hidden line the server sends the
    newline the client didn't echo.
- **Line framing:** a line ends at CR, LF, CR LF or CR NUL. Backspace edits the line; other
  control characters are dropped, and invalid UTF-8 becomes U+FFFD.
- **Prompts:** after each burst of output, a playing session gets the world's `prompt`
  (§3.9) with its vitals filled in, for example `<20hp 20m 100mv> `. Telnet ends it with IAC EOR when the client agreed to EOR,
  else IAC GA.
- **Color markup:** text may contain `{red}` and the other base colors, `{bright_red}` etc.,
  `{bold}`, `{underline}`, `{/}` (reset) and `{x0}`–`{x255}`. `{{` is a literal brace;
  unknown tags stay as typed.
  - 16-color clients get xterm colors downgraded to the nearest base color. Clients without
    color get the tags stripped. Output that leaves a color on ends with a reset.
  - Color depth: MTTS bits decide (8 = 256 colors, 1 = ANSI). Without MTTS, terminal types
    decide: 256 for `*256COLOR*`, `*TRUECOLOR*`, Mudlet and TinTin; 16 for ANSI, XTERM,
    VT100 and the like. Otherwise there is no color.
  - Engine messages don't use color yet.
- **GMCP packages (v0):** the client sends `Core.Hello` and `Core.Supports.Set`/`Add`/
  `Remove`; GMCP input other than `Core.*` is ignored in v0. The server sends these, with
  lowercase field names:

  | Package | Payload | Sent |
  |---|---|---|
  | `Char.Schema` | `{"stats": [{"name", "label"}], "vitals": [{"name", "label"}], "damage_types": [names], "features": [names]}` | Once a session takes a character, and when the client starts supporting it |
  | `Char.Vitals` | `{"<vital>", "max<vital>"}` for every declared vital, for example `{"hp", "maxhp", "mana", "maxmana", "move", "maxmove"}` | When any field changed |
  | `Char.Status` | `{"name", "level", "xp", "xp_next", "wait", "casting": {"skill", "progress"} or null, "initiative" or null, "fighting": [names]}`; `level`, `xp` and `xp_next` only with `leveling`, `casting` only with `skills`, `initiative` and `fighting` only with `combat` | When changed |
  | `Char.Affects` | `[{"name", "remaining", "flags"}]` | When the set or a whole second of time left changed |
  | `Room.Info` | `{"label", "name", "exits": {dir: "open"/"closed"/"locked"/null}, "regions"}` | On entering a room, and when a door here changes |
  | `Room.Bands` | `{"melee": [...], "near": [...], "far": [...]}`, each entry `{"id", "name", "kind": "actor"/"item"}`; `track` feature only | When anything in the room moves, arrives or leaves |
  | `Comm.Channel.Text` | `{"channel": "say"/"tell"/"whisper"/"shout"/"gossip", "talker", "text"}` | With each message |
  | `Gups.Trace` | `{"line"}` | Builders with `@trace` on, instead of the `[trace]` text lines |

  `Room.Bands` entries carry entity ids, only ever for the viewer's own room, so clients can
  key their band view and tell identical mobs apart. Plugins may add packages (§4.15).
- **GMCP values:** `core` builds payloads as plain values (`String`, `Long`, `Boolean`,
  `List`, `Map<String, *>`, `null`), since it has no kotlinx.serialization dependency;
  `net` encodes them as JSON. The seam is `OutputSink.gmcp(pkg: String, data: Any?)`.
- **GMCP delivery:**
  - A package is sent only when GMCP is on and `Core.Supports` listed it, or a module above
    it (`Char` covers `Char.Vitals`), compared case-insensitively. Versions in the entries
    are ignored. `Set` replaces the list, `Add`/`Remove` change it, and `DONT GMCP` turns
    everything off. `Core.Hello`'s client and version are kept.
  - A `Core.*` message with malformed JSON counts as a protocol error (§6.4); other packages
    are ignored.
  - GMCP waits in the session with its text and goes out after the text and before the
    prompt. A flush with only GMCP sends no prompt.
  - Every phase-7 flush recomputes the state packages (`Char.Vitals`, `Char.Status`,
    `Char.Affects`, `Room.Info`, `Room.Bands`) and sends those whose whole payload changed.
    The cache resets when a session takes a character, so a reconnect gets everything again.
    Events (`Comm.Channel.Text`, `Gups.Trace`) are never cached.
  - IAC bytes in a payload are doubled.
- **GMCP payloads:**
  - `Char.Schema` is static for the life of the server (§3.9), in declaration order. Its
    `features` are the enabled features (all four ids until a world can turn features off). Clients draw vitals bars and stat labels from it
    instead of hardcoding names; the bundled web client does.
  - `Char.Vitals` keys are the vital names and `max` plus the name, in schema order.
  - `Char.Status`: `wait` is the recovery left in pulses; `xp_next` is the total XP for the
    next level (`rules.xp_to_level`); `casting.skill` is the skill's name, or `action` for a
    warmup that isn't a cast, with `progress` 0–100 through the warmup; `fighting` lists
    names; plus the character's own initiative.
  - `Char.Affects`: `remaining` is whole seconds; `flags` are sorted.
  - `Room.Info`: `exits` maps every exit's direction to its door state, or null when it has
    no door; `regions` are sorted.
  - `Room.Bands`: actors (the viewer excluded) and ground items of the viewer's room, by band
    from the viewer's index: actors first, then items, each in ascending id.
  - `Comm.Channel.Text`: `say` goes to everyone in the room, the speaker included. `tell` and
    `whisper` go to both ends. `shout` goes to the speaker's room and the rooms that hear it,
    and `gossip` to every player. `text` is what was said, without the framing.
  - `Gups.Trace`: `{"line"}` replaces the `[trace]` line, the per-pulse overflow summary
    included.
- **WebSocket framing:** One JSON message per frame:
  `{"t":"text","d":"..."}` | `{"t":"gmcp","p":"Char.Vitals","d":{...}}` | `{"t":"input","d":"..."}`.
  - Also from the server: `prompt`, `hide` (true or false, for password prompts), and
    `error` before a close. Also from the client: `auth`, and `gmcp` (which only reads
    `Core.Supports.*`, as over telnet).
  - `text` frames carry the color markup unrendered; the client renders it.
  - **Authentication:** the session cookie, or else a first `{"t":"auth","d":token}` frame
    within 30 seconds. Otherwise the server sends `{"t":"error","d":"Please log in first;
    your session may have expired."}` and closes with code 1008. A later `auth` frame is
    ignored.
  - A socket is a session with transport `ws` and the token's account, so the login greets
    it and goes straight to the character menu.
  - Frames are at most 64 KiB. Pings every 30 seconds, with a 60-second timeout. Five
    malformed frames end the socket.
  - Every minute each live socket's token is checked again; an expired or revoked one gets
    `{"t":"error","d":"Your session has ended. Please log in again."}` and is closed. Logout
    closes that token's sockets at once. The character goes link-dead as for any dropped
    link.
- **HTTPS:** Ktor's Netty engine with a plain connector and the same `SslHandler` and
  certificate handling as telnet over TLS. Because Ktor sees plain HTTP behind it, the
  session cookie is written by hand: `gups_session=<token>; Path=/; Max-Age=<seconds left>;
  HttpOnly; Secure; SameSite=Strict`; logout clears it with `Max-Age=0`. Without a
  certificate the server speaks plain HTTP, which only tests use.

  | Endpoint | Purpose |
  |---|---|
  | `GET /` | The bundled client. |
  | `POST /api/login` | `{"login", "password"}` returns `{"token", "expires_at"}` and sets the session cookie. Rate-limited like telnet (§5.12). |
  | `POST /api/logout` | Revokes the token. |
  | `POST /api/code`, `POST /api/signup` | Web sign-up with a one-time code (below). |
  | `GET /ws` | WebSocket upgrade (framing above). |
  | `GET /docs`, `/docs/`, `/docs/<file>` | The API reference, no login needed (§4.16). |

  - Errors answer `{"error": message}`: 401 for a wrong login or password, 429 while locked
    out, 403 for a disabled account, 400 for a malformed body.
  - After authentication a socket starts at character selection, using the same login state
    machine as telnet, so the two flows can't drift apart.
  - `POST /api/code` (`{"code"}`) answers `{"login": "ann"}` for a setup code (the account
    it sets up), `{"login": null}` for an invite, or 404.
  - `POST /api/signup` (`{"code", "login", "password"}`) redeems the code exactly as the
    telnet `new` flow does: a setup code sets its account's password and ignores `login`; an
    invite creates `login` with the invite's role. On success it answers like
    `/api/login`. 400 for a bad login or weak password, 409 for a taken login, 404 for a code
    that is unknown, used or expired. A bad code counts against the address, and a locked-out
    address gets 429 without its code being looked at. There is never a sign-up without a
    code, even with `open_signup`.
  - **Assets:** `index.html` is served at `/` with `no-cache`; every other file of the
    client is served under `/assets/<hash>/`, cached for a year as immutable. `<hash>` is the
    first 16 hex digits of a SHA-256 over all the files' names and contents, so any change
    changes every URL while relative imports keep working. `index.html` refers to
    `%ASSETS%`; the module lists its files in `files.txt`.
- **Web client:** plain ES modules (`app.js`, `markup.js`, `panels.js`) and `style.css`,
  documented with JSDoc, with no framework, no npm packages and no build step. There is no
  Node anywhere in the build or tests: checking TypeScript would need a JavaScript runtime,
  which the engine won't depend on. It targets current Chrome, Firefox and Safari.
  - It shows an output pane with the color markup rendered as HTML, an input line with
    history, vitals bars and an affects list from GMCP, and a band view (`Room.Bands`) of who
    is in melee, near and far.
  - On load it opens the socket, so a valid cookie goes straight to the game; an `error`
    frame shows the login form.
  - It echoes commands locally, keeps 100 lines of history (up and down arrows), and
    switches the input to a password field on `hide`. It keeps at most 2,000 lines of
    output.
  - It asks for the GMCP packages `Char`, `Room`, `Comm.Channel` and `Gups`.
  - A sign-up form takes a one-time code, checks it, then asks for a login (unless the code
    names one) and a password twice, compared in the browser before anything is sent.
- **Limits:**
  - Input line ≤ 512 bytes (UTF-8); the rest is dropped and the client is told "Your line
    was longer than 512 bytes and was cut short."
  - Per-session output buffer ≤ 256 KB (over WebSocket, counted in queued JSON
    characters). A client that exceeds it is disconnected as "too slow", and the character
    goes link-dead.

### 4.11 Kotlin Seams

```kotlin
data class ClientInputEvent(val session: SessionId, val line: String, val receivedNanos: Long)
sealed interface WorldControlEvent
    // Reload(packages), Deploy(commit), Shutdown(delay), Connect(peer, transport, account?),
    // Disconnect, AuthResult(session, outcome), Task(work)
interface WorldInbox                                                 // listeners submit here; WorldLoop implements it

interface ScriptHost {
    fun invokeHook(world: World, owner: Entity, hook: HookName, ctx: HookContext): HookOutcome
    fun invokeRef(world: World, owner: Entity, ref: HookRef, ctx: HookContext)   // timers, affect hooks
    fun isSuspended(owner: Entity, ref: HookRef): Boolean            // quarantined package's timers stay pending
    fun modify(world: World, point: ModifierPoint, ref: HookRef, source: String, c: RulesContext, base: Any?): Any?
    fun reload(world: World, packages: List<String>): ReloadResult   // package paths, e.g. "zones/forest"
    fun migrate(world: World, entity: Entity, fromVersion: Int)
    fun quarantine(world: World, pkg: String, why: String)
    fun unquarantine(world: World, pkg: String)
    val quarantinedPackages: Set<String>
}
interface RulesEngine {                                              // Starlark-backed; engine tests use a Kotlin fixture
    fun toHit(c: AttackContext): Int
    fun damage(c: AttackContext): Int
    /* ... one method per §4.6 function, plus the engine-only track lags ... */
}
interface Persistence {                                              // never blocks the world thread
    fun checkpoint(world: World, cause: String, immediate: Boolean)  // capture happens here, on the world thread
    fun loadCharacter(world: World, name: String): Player?
    fun diagnostic(world: World, d: Diagnostic)
    val maintenance: String?                                         // non-null while new logins are refused
    val accounts: AccountStore?
}
interface OutputSink {
    fun send(text: String)
    fun prompt(text: String)
    fun hideInput(hidden: Boolean)                                   // password prompts
    fun gmcp(pkg: String, data: Any?)
    fun wantsGmcp(pkg: String): Boolean
    fun close(reason: String)
}
interface LoginHandler {                                             // sessions without a character
    fun connected(world: World, session: Session)
    fun input(world: World, session: Session, line: String)
    fun completed(world: World, session: Session, outcome: Any)
    fun disconnected(world: World, session: SessionId)
    fun pulse(world: World)
}
interface WebAccounts {                                              // implemented in auth, so net needs only core
    fun login(login: String, password: String, remote: String): WebLogin
    fun checkCode(code: String, remote: String): CodeCheck
    fun signup(code: String, login: String?, password: String, remote: String): WebLogin
    fun logout(token: String)
    fun accountFor(token: String): Long?
}
```

The plugin seams (`GupsPlugin`, `PluginRegistry`, the `PluginWorld` facade, and the
plugin-agnostic hooks in `core` and `script` they fill in) are in §4.15.

- **Loading:** `loadPackages` is not on `ScriptHost`. The script loader returns a
  `LoadResult`, and the script host is built from it before the `World`. Reloads go through
  `ScriptHost.reload`.
- **The world:** `World.login: LoginHandler?` handles sessions without a character. With
  no handler, input from such a session gets "You are not playing a character." and the
  world closes and disconnects the session (§5.2).
  `World.onDiagnostic` lets the server log diagnostics as they happen. `World.later` and
  `WorldControlEvent.Task` run slow work off the world thread (§2.5).
- **Login outcomes:** `AuthResult(session, outcome)` carries the login handler's own
  continuation; the world passes it to `LoginHandler.completed`, then flushes the session.
  A result for a session that has closed is dropped. `LoginHandler.pulse` runs each pulse,
  before the idle checks, for prompt timeouts.
- **Saved worlds:** a saved world starts from a `Boot`: the saved pulse, the id counters,
  the saved room ids, and no fresh spawns. A restore API rebuilds entities with their saved
  ids. `World.requestImmediate(cause)` asks for an immediate checkpoint (§5.11).
- **Actions:** the tick lane's seam is the `Action` interface (`validate`, `veto`, `begin`,
  `resolve`) (§5.3).
- **Strict mode:** `WorldConfig(strict = true)` rethrows engine exceptions instead of logging
  them and aborting the action. Tests and the harness use it with the invariant check after
  every pulse (§6.5). `WorldConfig(bubble = true)` keeps a reality bubble (§5.9);
  `WorldConfig(production = true)` marks `--prod`.

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

`spawn_player` takes its numbers from `rules.starting_stats` and lets `vitals` and `stats`
(the constructors) override them: a named vital's maximum and current are both set to the
given value, and a named stat is replaced. `w.damage` lowers the named vital by `amount`
or by `pct` of its maximum, with `type` defaulting to the unarmed damage type.

| Object | API |
|---|---|
| `t` | `world(seed, packages=<this package>, rules=None, bubble=False)`, `eq`, `ne`, `true`, `false`, `contains`, `not_contains`, `fail` |
| `w` | `spawn_player(name, at, level, vitals, stats, skills, index)`, `spawn`, `run(pulses)`, `damage(target, vital, amount=None, pct=None, type)`, `set_attr`, `get_attr`, `room(label)`, `trace(on)`, `diagnostics()`, `quarantined()`, `pulse` |
| player handle `p` | `do(line) -> output`, `output()`, plus view fields |

- The rules package is always loaded implicitly. `rules` names a rules package to use
  instead of `//rules`. `bubble=True` keeps a reality bubble; without it every room is
  always simulated, so a mob can flee with no player near.
- **`p.do(line)`** submits the line directly, bypassing the session rate limiter. It runs
  pulses until the player's queue is empty and it isn't casting (at most 1000; more is an
  error), and returns all output since the last `do()` or `output()`.
- Harness players are exempt from the idle auto-quit. Worlds run in strict mode with the
  invariant check after every pulse, and with the wall-clock backstop off, so results never
  depend on machine speed.
- A test function's own code may spend 10,000,000 steps; pulses it runs don't count.
- Script diagnostics from a test's worlds are reported as warnings and don't fail the test.
  Tests assert on `w.diagnostics()` explicitly.
- `t.eq` and `t.ne` treat an entity view as equal to a label string that resolves (relative
  to the calling package) to its prototype label. Players are excluded.
- `w.trace()` keeps the last 400 lines (rules lines included) and prints them when the test
  fails.

### 4.13 CLI

| Command | Purpose |
|---|---|
| `gups serve --dev\|--prod [--fresh] [--world DIR] [--db FILE] ...` | Run the server (§2.4). |
| `gups test [--world DIR] <label-pattern...>` | Run tests, e.g. `//zones/goblin_caves/...`. |
| `gups lint [--base REF] [--format text\|github] [label-pattern...]` | Run the load and static checks (§7.3). |
| `gups fuzz [--pulses N] [--seed S] <label-pattern...>` | Run the fuzz walker (§7.3). |
| `gups snapshot --sanitized --out FILE [--admin ADDR]` | Make a sanitized database copy. |
| `gups migrate-check --snapshot FILE` | Run the migration check (§7.3). |
| `gups docs [--out DIR] [--world DIR]` | Write the HTML API reference (§4.16). |
| `gups docs --json [--out PATH]` | Write the `api.json` model (§4.16). |
| `gups docs --update-baseline [--docs-dir DIR]` | Before a release: write the API baseline (§4.17). |
| `gups repro <diagnostic-id\|bundle.json> [--save FILE]` | Replay a diagnostic in the harness. |
| `gups backup` / `gups restore <file>` | Back up or restore the database. |
| `gups deploy [--commit SHA]` | Called by CI; talks to the admin port. |
| `gups invite --role R`, `gups import-roster [--admin ADDR] [--days N] roster.csv` | Create accounts. |

- `--world` defaults to the current directory. `gups backup`, `restore` and `repro` take
  `--world` and `--db` as `serve` does.
- Commands that load a world (`test`, `lint`, `fuzz`, `migrate-check`, `repro`,
  `docs --world`) load its plugins too (§4.15).
- **`gups test`:** patterns are `//...`, `//a/...`, `//a`, `//a:file_test.star` and
  `//a:test_name`. It exits 1 if any test fails or errors, or a pattern matches nothing.
- **`gups restore FILE`** replaces the database with SQLite's restore API. The server must be
  stopped first; nothing enforces it.
- **`gups import-roster`:**
  - The CSV has a header row in any column order. Blank lines and `#` lines are skipped,
    double quotes are allowed, a leading `@` on a GitHub handle is dropped, and roles are
    case-insensitive. An optional `disabled` column takes `true`/`false`, `yes`/`no` or
    `1`/`0` (case-insensitive, trimmed). A blank cell leaves the flag alone; any other value
    fails parsing with `line <N>: '<value>' is not true or false`. Any bad row (unknown column or role, bad or repeated login, too many
    fields) fails the whole import with every problem listed, and nothing is written.
  - Output is a `login,setup_code` header, then one line per new account; a summary goes to
    standard error: `<created> accounts created, <updated> updated, <unchanged> unchanged, <disabled> disabled, <enabled> enabled. Setup codes expire in <days> days.`.
    `created` includes new accounts created disabled, which get no code.
    `--days N` sets the codes' lifetime.
  - Existing accounts without a password get no new code, so a re-import is idempotent;
    `@role <account> reset` issues a fresh one.
  - A row with `disabled` true disables its account as `@role disable` does, with the reason
    `roster` and no `disabledBy`. A new login listed as disabled is created disabled and gets
    no setup code. `false` re-enables the account. A change to the flag alone counts as an
    update, and the summary also counts the accounts disabled and enabled. A row whose flag
    another change already set counts as none of these. Only an import
    through `--admin` closes the account's characters and sessions at once. Offline, the
    disable applies from the next login, and the summary warns:
    `Warning: characters already in the world were not quit; use --admin against a running server.`
  - With `--admin ADDR` it goes through a running server's admin port (and refuses `--days`:
    codes last the default 7 days). Without it, it and `gups invite` write the database
    directly, which is safe beside a running server because SQLite serializes the writes.
- **`gups deploy`** defaults to `127.0.0.1:9090`, prints each progress line, and exits 1
  unless the deploy succeeded. It and `import-roster --admin` take `--admin-token-file` or
  `GUPS_ADMIN_TOKEN_FILE`.
- **`gups snapshot`:** sanitized is the only mode, and `--sanitized` is required so every call
  says so. Against a running admin port it calls `GET /snapshot`. Offline it copies the
  database through SQLite's online-backup API on a plain connection (WAL included, without
  running schema migrations), sanitizes the copy in place, and records the epoch timestamp in
  `meta.snapshot_taken_at`.
- **Sanitizing:** after the writer catches up, the copy is made with SQLite's backup API.
  Then `accounts`, `session_tokens`, `invites`, `restrictions`, `diagnostics` and
  `item_events` are emptied, characters are renamed `Player1`, `Player2`, ... in id order and
  detached from accounts, and the file is vacuumed. World rows, zone states, characters'
  payloads and their items are kept.

### 4.14 Admin Port

The admin port is plain HTTP on Ktor's Netty engine, bound to localhost or a private
interface (`--admin`, default `127.0.0.1:9090`), always on in `--prod`, and requires a
bearer token.

- **Token:** read from `--admin-token-file`, `admin_token_file` or the file
  `GUPS_ADMIN_TOKEN_FILE` names, trimmed. Without one the server starts with a warning, and
  every endpoint but `/healthz` answers 403.
- **Checking:** a missing or wrong token gets 401 with `WWW-Authenticate: Bearer`. Tokens
  are compared as SHA-256 digests with a constant-time comparison, so neither content nor
  length leaks through timing.

| Endpoint | Purpose |
|---|---|
| `GET /metrics` | Prometheus metrics (Micrometer). |
| `POST /deploy` | `{"commit"}`, or `{}` for the fetched tip of `deploy_branch`. Answers `application/x-ndjson`: one `{"progress": line}` per step, then `{"ok", "commit", "packages", "errors"}`, naming the commit it deployed. |
| `POST /roster` | The roster CSV as the body, for `gups import-roster --admin` against a running server. Answers the `login,setup_code` lines (200) or the problems (400). Accounts it disables are closed at once, as `@role disable` does, before it answers. If the world doesn't close them within 30 seconds (for example while the server stops), it answers 500: the roster was imported, but closing failed. |
| `GET /snapshot` | A sanitized copy of the database (§4.13) for the CI migration check (§7.3), as a download. |
| `GET /healthz` | `{"pulse", "uptime_s", "writer_healthy"}`, where `pulse` is the last finished pulse. 503 when no pulse has finished for 5 s or the writer is unhealthy. No token needed. |

**Metric names:**

| Area | Metrics |
|---|---|
| Pulse health | `gups_pulse_duration_seconds` (histogram), `gups_pulse_overruns_total`, `gups_world_input_queue_depth` |
| Scripts | `gups_script_steps_total{package}`, `gups_script_errors_total{package,kind}`, `gups_quarantined_packages` |
| Population | `gups_bubble_rooms`, `gups_bubble_entities`, `gups_sessions{transport}` |
| Persistence | `gups_checkpoint_duration_seconds`, `gups_checkpoint_entities`, `gups_checkpoint_backlog` |

- **Library:** Micrometer's Prometheus registry; its naming produces these names (the
  `gups.pulse.duration` timer becomes `gups_pulse_duration_seconds`). Pulse and checkpoint
  durations publish percentile histograms; pulse buckets run from 0.1 ms to 2 s.
- **Threads:** the world loop records each pulse's duration. Every second of game time the
  world thread publishes a snapshot for the gauges, so a scrape never reads the world. The
  per-package step counter behind `gups_script_steps_total` also feeds the overrun alert
  (§5.1), and `@stats` shows the same numbers as `/metrics`.
- **Meanings:**
  - `gups_pulse_overruns_total` counts skipped pulse boundaries.
  - `gups_bubble_rooms` counts simulated rooms (every room when the bubble is off).
  - `gups_sessions` counts sessions playing a character; logins in progress don't count. Its
    `telnet`, `tls`, `ws` and `local` series exist from the start.
  - `gups_script_errors_total` counts script-kind diagnostics (`script_error`, `step_limit`,
    `wall_clock`, `alloc_limit`, `missing`). It and `gups_script_steps_total` are registered
    for every loaded package at startup.
  - `gups_checkpoint_entities` is the rows in the last committed batch.
  - `gups_checkpoint_duration_seconds` times every commit, account batches included.

### 4.15 Plugins

The engine-developer path of §1.2: `.jar` plugins found through `ServiceLoader` that add host
builtins, commands, effect primitives and GMCP packages. There are no compatibility promises.
The engine-developer guide is `docs/PLUGINS.md`, and `samples/weather-plugin` is the worked
example.

**The interface** (package `gups.pluginapi` of `:plugin-api`):

```kotlin
object PluginApi {
    const val VERSION: Int = 1
}

interface GupsPlugin {
    val id: String                       // [a-z][a-z0-9_]*, unique
    val pluginApiVersion: Int            // must equal PluginApi.VERSION
    val doc: String get() = ""
    val requiredFeatures: Set<String> get() = emptySet()   // feature ids (§3.10)
    fun register(r: PluginRegistry)
}

interface PluginRegistry {
    fun addBuiltin(name: String, value: StarlarkValue, doc: String)
    fun addBuiltin(
        name: String,
        doc: String,
        params: List<PluginParam> = emptyList(),
        returnType: String = "any",
        call: (world: PluginWorld, positional: List<Any?>, named: Map<String, Any?>) -> Any?,
    )
    fun addCommand(
        name: String,
        lane: PluginLane,
        role: PluginRole = PluginRole.PLAYER,
        doc: String,
        run: (PluginCommandCall) -> Unit,
    )
    fun addEffectPrimitive(name: String, doc: String, params: List<PluginParam>, apply: PluginEffect)
    fun addGmcp(pkg: String, doc: String, produce: (PluginWorld, PluginActor) -> Any?)
    fun onWorldStarted(fn: (PluginWorld) -> Unit)
}

enum class PluginLane { FAST, TICK }                          // core Lane
enum class PluginRole { PLAYER, BUILDER, INSTRUCTOR, ADMIN }  // core Role

data class PluginParam(
    val name: String,
    val type: String,          // documentation only, e.g. "int", "room"
    val doc: String,
    val required: Boolean = true,
    val defaultValue: Any? = null,
)

data class PluginCommandCall(val world: PluginWorld, val actor: PluginActor, val args: String)

data class PluginEffectCall(
    val world: PluginWorld,
    val caster: PluginActor,
    val target: PluginActor?,   // null for target = "none" skills
    val args: Map<String, Any?>,
)

fun interface PluginEffect {
    fun apply(call: PluginEffectCall)
}
```

- **Types:** every type in these signatures and in the facade belongs to `gups.pluginapi`, to
  Kotlin/Java, or to the Starlark library (`StarlarkValue`, `StarlarkThread` and the
  annotations, which `plugin-api` exports). None comes from `core` or `script`.
- **Version:** `PluginApi.VERSION` changes whenever this interface or the facade changes.
  Plugins built for another version are refused at load, and must be rebuilt.
- **Builtins** appear to scripts under the plugin's id, as fields of one immutable namespace
  value: a plugin `weather` with builtin `now` is used as `weather.now(room)`. A package or
  test file that defines a top-level global with a plugin's id fails to load.
  - The lambda overload binds Starlark arguments to `params` in declared order: a missing
    required argument or an unknown keyword is an error, and a missing optional parameter
    gets `defaultValue` (Kotlin `Int`/`Float` defaults become `Long`/`Double`). The handler
    gets every bound value, in declared order, as `positional`, and by name as `named`.
  - Argument conversion: `StarlarkInt` → `Long`, `StarlarkFloat` → `Double`, `None` → `null`,
    list/tuple → `List`, `Dict` → `Map`, script room/actor/item views →
    `PluginRoom`/`PluginActor`/`PluginItem`. Strings and booleans pass as they are.
  - Return conversion: `null`/`Unit` → `None`, `Int`/`Long` → `StarlarkInt`, `Double` →
    `StarlarkFloat`, `List` → immutable list, `Map` → immutable dict, a `StarlarkValue` as
    is. Strings and booleans pass as they are. Any other type, including a facade entity
    (return its label), is an error.
  - An exception other than `EvalException` becomes `<id>.<name>() failed: <e>`, a script
    error in the calling hook (§6.2).
  - It gets the `PluginWorld` of the hook that is running. Called where no hook runs
    (top-level package code, or a test function's own body), it fails with
    `weather.now() can only be called while the world runs, from a hook or command`.
  - `addBuiltin(name, value, doc)` installs `value` as is. For entity arguments such a value
    uses `PluginViews.room/actor/item(value)` and `PluginViews.world(thread)`.
- **Effect primitives** appear the same way (`weather.soak(pulses = 3)`). Calling one binds
  and converts its arguments as builtins do, refuses entity arguments (an effect is part of a
  frozen prototype), and returns an effect value that works wherever an engine effect
  primitive's does: `skill(effect = …)`, `sequence(a, weather.soak())`. It needs no world, so
  it works in top-level package code. An effect whose plugin isn't loaded records a `plugin`
  diagnostic and does nothing.
- **Commands** go through the normal command registry (§5.2). Names must be lowercase, contain
  no whitespace, and can't start with `@` (an `@` word only reaches engine tools). They may
  not shadow the world's engine commands (after `rename`, §3.10) or aliases, directions, or
  an earlier plugin's commands. A name freed by `rename`, or of a feature that is off, is
  free. A
  script `global_command` with a plugin command's name is skipped with a `load` diagnostic,
  since plugins are installed first. Reloads and deploys keep plugin commands. Plugin
  commands with a role don't exist for other players; `help` lists the allowed ones after the
  global commands.
- **GMCP:** a producer is called in phase 7 for each session whose client supports the
  package, and only changed payloads are sent (§4.10). It returns plain values, as `core`'s
  own payloads do. A package name must match `Name(.Name)+`. It must not equal, contain, or
  be contained in an engine package, an earlier plugin's package, or another package of the
  same plugin, because clients subscribe by prefix. A producer that throws is recorded as a
  `plugin` diagnostic and its package is skipped for that pulse.
- **Failures at run time:** an exception from a command, effect, GMCP producer or
  `onWorldStarted` callback is caught and recorded as a `plugin` diagnostic, and the pulse
  goes on.

**The facade.** Plugins never get the raw `World` or entities. `PluginWorld` exposes:
- **Verbs**, with the meaning and argument names of the `ctx` verbs, each calling the same
  `core` function, so the §3.8 invariants hold and every mutation stays on the world thread:

  ```kotlin
  fun send(target: PluginActor, text: String)
  fun echo(room: PluginRoom, text: String, exclude: List<PluginActor> = emptyList())
  fun damage(target: PluginActor, amount: Int, type: String, vital: String,
             source: PluginActor? = null)
  fun heal(target: PluginActor, amount: Int, vital: String)
  fun applyAffect(target: PluginActor, name: String, duration: Int,
                  statDeltas: Map<String, Int> = emptyMap(), flags: Set<String> = emptySet())
  fun removeAffect(target: PluginActor, name: String): Boolean
  fun teleport(actor: PluginActor, room: PluginRoom)
  fun getAttr(entity: PluginEntity, key: String, default: Any? = null): Any?
  fun setAttr(entity: PluginEntity, key: String, value: Any?)
  fun delAttr(entity: PluginEntity, key: String)
  ```

  A verb given an actor or item that has left the world (a dead mob, a player who quit, a
  destroyed item) fails with `<verb>(<param>): <name> is no longer in the world` rather than
  silently doing nothing. Rooms never leave.
- **Attributes:** keys without `ns:` are in the plugin's namespace (its id, §3.7). A plugin
  may read any namespace and write only its own. Values are the attribute types of §3.2
  (`Double` is refused), and `null` deletes. The script attribute size limit doesn't apply
  to plugins (trusted code).
- **Schema names:** a vital, stat or damage type the schema doesn't declare (§3.9), in a verb
  or a `statDeltas` key, throws `IllegalArgumentException` with a "did you mean"
  suggestion, which a builtin's caller sees as `<id>.<name>() failed: …`.
- **Reads:** `schema: PluginSchema` (`stats`, `vitals`, each with `name` and `label`, and
  `damageTypes`, in declaration order), `features: Set<String>` (the enabled feature ids),
  `pulse`, `seed`, `rng: PluginRng` (`int(min, max)`, and `chance(pct)` taking a
  percentage like `ctx.rng.chance`, seeded from the world's RNG), `rooms()` (sorted by
  label), `room(label)`, and `regions()` (every region tag a room carries, sorted).
- **Diagnostics:** `diagnose(message)` records a `plugin` diagnostic, and `log(message)`
  writes to the server log, prefixed `plugin '<id>': `.
- **Read-only views**, reading current state on every access and comparing equal when they
  wrap the same entity:
  - `PluginEntity`: `id`, `label` (the room label, or the prototype label of a mob or item;
    null for players), `name`;
  - `PluginRoom`: `regions` (sorted), `flags` (sorted), `size` (lowercase track size), `exits`
    (direction → room label), `actors`, `items`;
  - `PluginActor`: `isPlayer`, `room`, `stat(name)` (effective), `baseStat(name)`,
    `vital(name)`, `maxVital(name)`, `resist(type)`, `level`, `affects` (names),
    `hasAffect(name)`. An undeclared name throws as above; `level` throws
    `IllegalStateException` naming the feature without `leveling`;
  - `PluginItem`: `keywords`.
- **`PluginViews`:** `world(thread)`, and `room/actor/item(value)`, which accept a facade value
  or a script view (typed `Any?`, so no `script` type leaks). The world reached through
  `world(thread)` can't know which plugin is calling, so it has no namespace: unqualified keys
  and all writes fail, and its `diagnose`/`log` carry no prefix. It is also set for `@eval`.

Extending the facade is a `PluginApi.VERSION` bump. The engine-side loader, host and facade
implementations live in `gups.pluginapi.host`, which may use `core` and `script` types; only
`cli` and tests call it.

**Seams in `core` and `script`** (which don't depend on `plugin-api`):
- `Effect.Plugin(pluginId, name, args)`, one case of the sealed `Effect`, holding only plain
  values so prototypes still compare by value on reload. `World.pluginEffects` applies it.
- `World.pluginGmcp`, the plugin GMCP producers by package.
- `CommandRegistry.registerPlugin` and `pluginCommands`; `clearGlobals()` clears only script
  global commands.
- `WorldPluginWiring` (in `script`): `globals` (plugin namespaces by id, predeclared in every
  package, test file and `@eval`), `install(world)` (before the script host installs), and
  `started(world)` (after `on_load`). Every place that builds a world takes one, `NONE` by
  default. `scriptWorld(thread)` finds the running world from a Starlark thread.

**Loading:**
- **Where jars come from:** `plugins/<id>.jar` in the course world repository, each pinned by
  a SHA-256 in `MODULE.gups` (`plugins = {"weather": "sha256:<64 lowercase hex>"}`, §2.3).
  The server, `--dev`, every CLI command that loads a world, and CI all load the same jars.
  A `.jar` in `plugins/` that `MODULE.gups` doesn't name is ignored, with the warning
  `plugins/<name>.jar is not pinned in MODULE.gups; ignoring it`.
- **Reserved ids:** the package globals, `module`, `server`, `ctx`, `self`, `room`,
  `rules_ctx`, `t`, `w`, `p`, `diagnostic`, and the Starlark universe. No package namespace
  or shared namespace may equal a plugin id (§3.7).
- **Class loading:** one `URLClassLoader` per jar, with the engine's class loader as parent.
  `ServiceLoader` finds the plugin. Loaders stay open while the plugins are in use.
- **Checks**, per pinned id in id order: the jar is present; its checksum matches; it has
  exactly one `GupsPlugin` provider (instantiating it may throw); `plugin.id` is the declared
  id; `pluginApiVersion` matches; every id in `requiredFeatures` is a feature the world has
  on (otherwise `plugin '<id>': needs the '<feature>' feature`); `register` succeeds with no
  invalid or clashing name.
  Registration is atomic: a failed plugin adds nothing. A plugin that fails any check is
  skipped with a warning starting `plugin '<id>': ` (for example
  `plugin 'weather': checksum mismatch for plugins/weather.jar (expected sha256:…, got sha256:…)`),
  and its class loader is closed. The other plugins still load.
- **Order:** unpinned-jar warnings come first, then plugins in id order. `register` runs after
  every jar is checked, also in id order, so "earlier plugin" means a smaller id.
- **Warnings:** the server records each one as a `plugin` diagnostic and a `plugin_load`
  alert just before `on_load` fires. CLI commands print them as `warning: …` on stderr.
- **Skipped plugins:** packages that use a skipped plugin fail to load with
  `name '<id>' is not defined`. On a restart the server starts without them; in a deploy the
  staged load fails and the old version stays live.
- **Restarts:** loading happens once at startup. Adding, removing or changing a plugin is a
  `MODULE.gups` and `plugins/` change, which needs a restart like an engine upgrade. A
  deploy whose changed files include anything under `plugins/` is refused (§5.10).
- **Tools:** tools that build a world without the server (fuzz, repro, migration check)
  start plugins right after installing them. Tools that build no world (lint and its
  `--base` loader) only take the plugin globals.

**Trust:** a plugin is trusted code with full JVM access. There is no sandbox: the JVM no
longer has one (the `SecurityManager` is gone), and running plugins in another process is out
of scope. Trust comes from procedure instead:
- only jars pinned by checksum in `MODULE.gups` are loaded;
- the template's `CODEOWNERS` gives `MODULE.gups` to instructors, so adding a plugin is a PR
  only an instructor can approve;
- loading one needs a restart, like an engine upgrade;
- `docs/PLUGINS.md` tells instructors to review a plugin as they would engine code, with a
  review checklist.

**The sample plugin** (`samples/weather-plugin`, `:samples:weather-plugin`) uses every
extension point and depends only on `:plugin-api` (§2.2). A room is **outdoors** when it
carries at least one region tag; its **region** is the first tag in sorted order. Rooms with
no tag are indoors.

| Extension point | What the sample adds |
|---|---|
| Builtin | `weather.now(room)`: `"clear"`, `"rain"` or `"storm"` for an outdoor room, `"clear"` for an indoor one. A pure function of `world.seed`, `world.pulse / 240` (a one-minute window) and the region, mixed with a SplitMix64-style finalizer; it never uses `Random` or the clock. |
| Command | `weather` (`FAST`, `PLAYER`): `The weather here is <state>.` outdoors, `You are indoors.` indoors. |
| Effect primitive | `weather.soak(pulses = 2)`: the affect `soaked`, `stat_deltas` `{"dex": -1}`, for `pulses` pulses on the target; nothing when the skill has no target. |
| GMCP | `Gups.Weather`: `{"state": <state>, "region": <region or null>}`. |
| World hook | `onWorldStarted`: logs `weather: tracking <n> region(s): <tags, comma-separated>`. |

- The jar is `weather.jar`. Its service descriptor goes only into the jar, never onto the
  test classpath, so the parent class loader can't also offer the plugin.
- Its tests are unit tests against fakes of the `plugin-api` interfaces, plus an end-to-end
  test that pins the built `weather.jar` in a fixture world and runs a `*_test.star` through
  the harness test runner. `template-world` doesn't use it.

**The guide** (`docs/PLUGINS.md`) covers building a plugin against `plugin-api` (the Gradle
setup, `GupsPlugin`, `PluginApi.VERSION`, `META-INF/services/gups.pluginapi.GupsPlugin`), the
facade, each extension point with `WeatherPlugin` as the running example, putting a jar in
`plugins/<id>.jar` and pinning it (`shasum -a 256`), the trust model with a review checklist,
and the lack of compatibility promises. Its Kotlin samples are copied from `WeatherPlugin.kt`,
and a test checks that every Kotlin block (except Gradle snippets) appears there.

### 4.16 The API Model and Reference Docs

**The model.** One machine-readable description of the script API drives the docs, the
changelog check and plugin docs, so they can't drift apart.
- **Declarations:** each declaration (the 13 package declarations, plus `module` and
  `server`) has a `DeclSpec` listing its parameters with type, default, allowed values, doc
  and `since`. The builtins parse their arguments through the spec (§4.2), so unknown-argument
  errors, spelling suggestions and docs share one source. After parsing, every parameter of
  the spec must have been read, so a parameter a builtin ignores can't be accepted and
  silently dropped. A required parameter with allowed values lists them as "a, b or c".
- **Other entries** are small Kotlin tables next to the code that implements them: the hook
  catalog (with ctx fields and docs), the rules exports (whose names are the `RulesExport`
  enum, all documented), the modifier points, the constants, and the harness objects (`t`,
  `w`, `p`, `diagnostic`, built by the harness module and passed in, since `script` doesn't
  depend on `harness`).
- **Annotated builtins** (`ctx`, views, the rules ctx, effect primitives) keep their
  `@StarlarkMethod` annotations, read by reflection.
- **`since`:** every entry has `since`, the `api_version` that added it (default 1, never the
  current version, so raising the version doesn't re-date the API). Later additions carry
  `@ApiSince(n)` or `since = n`. Method parameters have no `since`: a parameter added to an
  existing method is recorded in the changelog.
- **Symbols:** every entry has a unique `symbol`, the path the changelog names it by. Package
  globals are bare (`room`, `room.size`, `damage.dice`, `SENSE`); other kinds are prefixed
  where a bare name would clash (`hook:on_wake`, `view:room.exits`, `rules:to_hit`,
  `modifier:to_hit`). `ctx`, `rules_ctx` and the harness objects use their own names
  (`ctx.act_move`, `ctx.apply_affect.name`, `rules_ctx.weapon`, `t.eq`), as do settings
  (`module.start_room`).
- **Docs:** a documented default is either a Starlark expression the declaration really uses
  (a test passes it explicitly and checks the result is unchanged), or one of the
  descriptions "id with spaces", "words of name" and "package path". Every declaration
  parameter and method parameter needs a doc string; a missing one fails the build. Method
  parameters have no type in the model; types stay in the doc text.
- **Ctx fields:** hooks list the ctx fields the engine may set (§4.4), and rules exports and
  modifier points list their `rules_ctx` fields, which must exist.
- **Completeness:** every package global, every method of every `@StarlarkBuiltin` type in
  `gups.script` and `gups.harness` (nested classes included), and every hook name must be in
  the model.

- **Schema fields:** the view fields generated from a world's schema (§3.9) are pattern
  entries, `view:actor.<stat>`, `view:actor.<vital>`, `view:actor.max_<vital>`,
  `view:actor.base.<stat>` and `view:actor.resists.<type>`, never a world's own names, so
  the model doesn't depend on a world. The constructors `vitals`, `stats` and `resists`, and
  `DAMAGE`, are ordinary entries.
- **Features:** every entry that belongs to a feature (§3.10) carries `feature`, its
  feature id; kernel entries carry none.
- **Messages:** the engine message catalog (§4.18) is a model list of its own, each entry
  with its id, placeholders, default text and `feature`.

**`api.json`** is a canonical dump of the model, tagged with `api_version` and the engine
version. Top-level keys come in a fixed order (`api_version`, `engine_version`,
`declarations`, `settings`, `constants`, `hooks`, `ctx`, `views`, `effects`, `behaviors`,
`rules`, `harness`); map entries and member lists (`fields`, `methods`, `exports`,
`modifier_points`) are sorted by name. It is identical across runs and platforms. A copy with
`engine_version` blanked (`api.golden.json`) is compared on every CI platform; it is a
snapshot of the *current* model, separate from the released baselines of §4.17.
- `gups docs --json` writes it to stdout without `--out`, to `PATH` when it ends in `.json`,
  and otherwise to `<PATH>/api.json`, creating the directory. A `PATH` that is an existing
  file, or has another extension, is a usage error. `--json` never includes plugins, so
  `--json` with `--world` is a usage error.

**The HTML reference.** `gups docs [--out DIR] [--world DIR]` writes it into `DIR` (default
`build/docs`); an existing file or a `.json` name is a usage error. With `--world` it includes
that world's plugins, schema, features and messages, after checking its `MODULE.gups` and
`MESSAGES.gups`, and leaves out entries of features the world has off; commands appear by
their renamed names. The files, in order:

| File | Contents |
|---|---|
| `style.css` | The one stylesheet, a dark theme matching the web client. |
| `index.html` | `api_version`, engine version, a table linking every other page with its entry count, a link to `api.json`, "Writing plugins" (`plugins.html#guide`), and a link to the current version's changelog section. |
| `declarations.html` | Declarations, then settings (`module`, `server`), then constants. |
| `hooks.html` | A summary table, then each hook with its signature, whether it is vetoable, what it attaches to, and its ctx fields, linked into `ctx.html`. |
| `ctx.html` | `ctx`, then every view. |
| `effects.html` | Effect primitives, then behaviors. |
| `rules.html` | `rules_ctx`, then the rules exports and modifier points with signatures, types and ctx fields. |
| `harness.html` | `t`, `w`, `p`, `diagnostic`. |
| `world.html` | With `--world`: the world's schema (stats, vitals, damage types and channels, with labels), its features and renamed commands. Always: the message catalog, with each id's placeholders and default text, and with `--world` the world's override. |
| `plugins.html` | The engine-developer guide (`docs/PLUGINS.md`) as `<section id="guide">`, then `<h2 id="loaded-plugins">This world's plugins</h2>`: each plugin's builtins, effect primitives, commands and GMCP packages, badged `plugin: <id>`, or "No plugins are loaded." |
| `changelog.html` | `docs/CHANGELOG.md` rendered as Markdown (§4.17). |
| `api.json` | Byte for byte what `gups docs --json` writes. |

- **Entries** show name, `since`, a `feature: <id>` badge for feature entries, doc, example
  if any, and parameters, fields and methods as
  tables (declaration parameters with type, positional/keyword, required, default, allowed
  values and doc).
- **Page rules:** every page is HTML5 with a UTF-8 charset, a viewport meta tag, the title
  `<Page> — GUPS API Reference (api_version <N>)`, the shared stylesheet, and the same header
  (engine version, `api_version` badge, and a nav of every page plus `api.json`). There is
  no `<script>`, and every link is relative to a file of the reference, so the pages work
  offline and from `file://`. All text from the model, the changelog or a plugin is
  HTML-escaped. Output is deterministic, in model order, with `\n` line endings.
- **Anchors:** every symbol has an element with `id` equal to the symbol with `:` replaced by
  `-` (`hook:on_enter` → `hook-on_enter`). The page a symbol lives on comes from the model
  list that contains it. Plugin entries are anchored `plugin-<id>.<name>` and are never part
  of `api.json`.
- **Examples:** an entry may carry a short example, kept in one table keyed by symbol (not in
  `api.json`, so editing one never changes the API or needs a changelog entry). An example
  has package code, a harness test body, or both. Every declaration, constant, effect
  primitive, behavior and harness type must have one. Each runs as a test in a temporary
  world with the template's `rules/`, a fixture room `start`, and the example as package
  `zones/ex` (or as `MODULE.gups` for `module`); it passes when its test passes with no
  warnings.
- **Markdown:** the changelog and the guide are rendered as CommonMark. Headings get ids
  (`api_version-<N>` for version headings, otherwise a lowercase slug, with `-2`, `-3`, … for
  repeats). Links and images render as their text only, so nothing leaves the reference. Raw
  HTML is escaped; none passes through.
- **Where it goes:** the release zip `gups-docs-<version>.zip` (under a top-level
  `gups-docs-<version>/`), `docs/` in the install directory, the jlink image and the OCI image
  (§2.2), and the running server. Both profiles render it once at startup, with the world's
  plugins, and serve it with no login: `--prod` on the HTTPS listener at
  `https://host/docs/` (no `--https`, no docs), `--dev` at `http://127.0.0.1:4080/docs/`.
  `GET /docs` redirects (302) to `/docs/`; `/docs/` is `index.html`; `/docs/<file>` is that
  file or 404. HTML, CSS and JSON are served as UTF-8.

### 4.17 Changelog and API Baselines

**`docs/CHANGELOG.md`** is plain, human-written Markdown listing script-facing changes per
`api_version`, newest first, with before/after snippets for breaking ones. The engine never
parses its structure; `changelog.html` renders it as Markdown (§4.16). Each
version heading `## api_version <N>` gets the anchor `api_version-<N>` that the
`api_version` mismatch diagnostic links to (§1.3).

**The API-change check.** Engine CI fails when the script API changes incompatibly without an
`api_version` bump. `docs/api/api-<N>.json` is the API as released at `api_version` N, the
**baseline**. With `N` the engine's `api_version`, the check compares the baseline of
version `N` with the current model:
1. **Diffs** every object with a `symbol` in both, keeping only scalar fields and lists of
   strings, without `symbol`, `doc` and `since` (so doc-only edits never fail). Child changes
   show up as their own symbols. A symbol is *removed*, *added* or *changed*; a removed or
   added symbol stands for the symbols beneath it.
2. If the baseline's `api_version` equals `N`, every removed or changed symbol is an error
   saying that breaking changes require raising `api_version`, and every added symbol must
   have `since` equal to `N`.
3. If the baseline's `api_version` differs from `N`, any difference is allowed.

**Raising `api_version`** is a pre-tag step, because the tagged commit must already contain
the new baseline (the tag-triggered release job can't commit it): set the engine's
`api_version`, then run `gups docs --update-baseline [--docs-dir DIR]`. It writes only
`docs/api/api-<N>.json` and keeps older baselines. It takes no `--json`, `--out` or
`--world`. A release with only additions keeps `N` and rewrites that file.

### 4.18 Engine Messages

Every player-facing line the engine sends is an entry of the **message catalog**, so a world
can change the words without changing the mechanics ("You begin casting" becomes "Your
implant spins up").

- **Entries:** each has a stable dotted id, named placeholders and default text, for example
  `cast.begin.known` = `"{actor} begins casting {skill}."` and
  `combat.quit_refused` = `"No way! You're fighting for your life!"`. The defaults are the
  texts this specification gives. Ids are grouped by the feature that sends them; an id of a
  feature that is off is never sent.
- **Coverage:** every message to players and onlookers from the engine and its features:
  command replies and refusals, combat, cast and condition messages, `look`, `score`, `who`
  and `help` framing, and login and character-menu text. Output of `@` tools isn't in the
  catalog; staff see engine terms.
- **Placeholders** are `{name}`, filled in when the line is sent. Actor placeholders take the
  name as the viewer sees it; `{actor}` is capitalized for the start of a sentence, other
  actor and item placeholders are plain names. `{{` is a literal brace, as in color markup (§4.10), which
  message text may use.
- **Overrides:** `MESSAGES.gups` at the world root, with one call:

  ```python
  messages({
      "cast.begin.known": "{actor}'s implant whines as it spins up {skill}.",
      "cast.begin.unknown": "{actor}'s implant whines.",
  })
  ```

  An unknown id, an unknown placeholder, or text that leaves out a placeholder the entry
  marks required stops the server, with a "did you mean" suggestion. An id of a feature the
  world has off is a warning. The file is read only at startup (§2.3).
- **Docs:** `gups docs` lists the catalog on `world.html`, with the world's overrides when
  given `--world` (§4.16).

---

## 5. Core Logic, Control Flow & Edge Cases

### 5.1 World Loop

The world coroutine selects over the input channel and the next pulse boundary. Pulse
boundaries come from `System.nanoTime()`.

- **Between pulses:** Each dequeued `ClientInputEvent` is classified (§5.2). Fast-lane
  commands execute immediately. Tick-lane commands are appended to the actor's queue.
  Output produced while handling a session's input between pulses is flushed to that
  session at once; output caused by anything else waits for phase 7.
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
     (§5.4) and fire `on_combat_tick` on the actor, then on each item it has equipped
     that declares the hook, in ascending `EntityId` order (§4.4).

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
  - **Session expiry** (not a numbered phase): link-dead and idle auto-quit run here,
    after phases 5 and 6 and before the output flush.
  7. **Output flush:** Flush output and send GMCP deltas (state packages only when
     changed, §4.10).
- **Deaths outside a tier:** Damage dealt from timers, fast-lane commands, or direct verb
  calls outside phases 2 and 3 processes death, and any room transition, right away instead
  of deferring it to the end of a tier.
- **Overrun policy:** A pulse that ends at or before the following boundary is on time. If a
  pulse finishes after the next boundary, the scheduler does **not** run the missed pulses
  back to back. It skips to the next future boundary (which may be "now", in which case the
  next pulse runs immediately) and increments `pulse_overruns` once per skipped boundary.
  The pulse counter advances only once per pulse actually executed, so a lag spike slows
  game time instead of making it lurch forward.
- **Overrun reporting:** A pulse overruns when it takes longer than 250 ms. If more than 10%
  of pulses in the last minute overran, instructors get an `overrun` alert (§6.2) naming the
  top step-consuming package: script steps per package (rules functions included) are
  sampled every combat round, and the package with the largest increase over the sampled
  minute is named. Nothing is auto-quarantined for this.

### 5.2 Command Classification and Admission

1. Parse the verb, resolve the world's aliases and prefixes (§2.3), and look it up in the engine commands, global
   commands, then room and item `commands`. Ambiguity resolves in that order.
   - Room `commands` are checked before item `commands`. Item `commands` come from equipped
     items, then inventory, then items on the ground; within a layer the lowest item ID
     wins. Items inside containers grant no commands.
   - A line starting with a prefix character runs that prefix's command with the rest of
     the line as its arguments (`'hello` is `say hello` in the template world).
   - An exact name or alias wins and is never ambiguous (in the template world `l` is
     `look`'s alias, though `lock` also starts with `l`). Otherwise a verb that is a prefix of the names or aliases
     of exactly one engine, global or plugin command resolves to it; a prefix matching more
     than one command (`cl` for `clear` and `close`) is ambiguous and gets "Huh?". Prefixes
     are tried only after every room and item command. An `@` prefix follows the same rule
     among the `@` commands the actor may use (§4.9).
   - **Line limit:** Before any of this, and before login, every input line takes a token
     from a second per-session bucket: capacity 25, refill 25 tokens/s. When it is empty
     the line is dropped silently, whatever it would have parsed to. These drops count
     toward the fast-lane disconnect below.
2. **Fast lane:**
   - Rate limiting uses a per-session token bucket: capacity 20, refill 5 tokens/s.
   - When the bucket is empty, input is dropped with "Slow down." More than 3 drops
     within 60s, counting line-limit drops, disconnects the session.
3. **Tick lane:**
   - The command is appended to the actor's queue.
   - If the queue already holds 10, it's rejected with "You can't plan that far ahead."
     and nothing is enqueued.
4. **`stop` / `clear`** (fast lane):
   - Flushes the queue.
   - Cancels a channeled cast, with no cost paid and no lag applied.
   - Does **not** end combat engagement or clear existing `waitState`.
5. **Parsing:** A queued entry keeps the verb and argument text. Arguments, including target
   keywords, are resolved when the action is popped, so `kill goblin` targets whichever
   goblin is present then.

### 5.3 Action Lifecycle (tick lane)

For each popped action:

1. **Validate:** Check that the target still exists and is in range (band), that the
   actor can pay the cost (§4.3), and that it isn't stunned, incapacitated or silenced.
   - On failure, send a message. No lag is applied.
2. **Veto phase:**
   - Fire `before_*` hooks in dispatch order (§5.6). For casts, `cast_allowed` runs through
     the modifier chain first, once; it is not re-checked after the warmup.
   - The first `deny` cancels the action, sends its message to the actor, and applies
     **no** lag.
3. **Warmup:**
   - If `warmup > 0`, set `casting = CastState(action, endsAt)` and fire `on_cast_start`.
   - Onlookers who know the skill see "<caster> begins casting <skill>."; others see
     "<caster> begins casting."
   - Each incoming `damage` event calls `rules.interrupt_check`. A `stun` always
     interrupts if `interrupt.stun`.
   - An interrupt clears `casting`, pays no cost, and applies `lag / 2` (rounded down).
4. **Re-validate** when the warmup completes. This re-runs the full validation: target
   presence and band, cost, and the `silenced` flag. The target is the one chosen at the
   first validation. A failed re-validation pays no cost and applies `lag / 2`.
5. **Resolve:**
   - Pay the cost.
   - For casts, everyone else in the room except the target sees "<caster> casts <skill>.",
     with " at <target>" or " on <item>" when there is one. Instant casts are seen too.
   - Apply the `effect`, or call `resolve(ctx)`. All mutations go through verbs.
   - Fire the `on_*` hooks (`on_cast`, `on_damage`, …).
6. **Recovery:** Set `waitState = max(waitState, lag)`, so recovery never shortens existing
   lag. No lag is applied if the resolve left the actor out of the world or casting.

Instant actions (`warmup == 0`) run steps 1, 2, 5, and 6 in the same pulse. Casts, warmups
and interrupts are the `skills` feature; other tick-lane actions follow the same steps with
no warmup.

### 5.4 Combat Rounds

Everything in this section is the `combat` feature, except conditions, death, corpses and
respawns, which are the kernel and work with or without it (§3.10).

- **Engagement:**
  - Combat starts through `kill`/`attack`, `aggressive`/`assist` behaviors, a hostile
    skill landing, or `ctx.start_combat`.
  - A skill is hostile when it lands on an actor other than the caster and either its
    `target` is `enemy` (including a `room`-range skill hitting each actor in the room),
    whatever its effects, or it applies a `damage`, `stun`, `knockback` or `pull` primitive
    (§4.3). Those primitives engage the same way wherever they are applied from.
  - It ends on death, on either party leaving the room, on a successful `flee`, or with
    `ctx.stop_combat`.
  - Engagement is always symmetric. `start_combat` engages both sides and sets each side's
    primary target if it has none. "Hostile", wherever the track rules use it, means an
    engaged opponent in the same room.
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
  1. Call `before_attack` (vetoable) on the defender first (§5.6), so defensive wards
     evaluate before the attacker and its wielded weapon.
  2. Roll `rng.int(1,100)` against `to_hit` (through the modifier chain).
  3. On a hit, compute `damage` (chain), then `mitigate` with the weapon's damage type
     (or the world's `unarmed_damage_type`), then subtract the vital losses it returns.
     Every loss is subtracted, `on_damage` fires once, the interrupt check runs, and then
     `condition` is asked once for the damage event (death is still deferred to the end of
     an initiative tier). Outside a damage event, a vital change asks `condition` once.
  - Messages show no numbers: "You hit X." / "X hits you." / "X misses Y."
- **Regen** at the world tick applies to every actor without `no_regen`, engaged or not.
- **Conditions:** `rules.condition` (§4.6) decides each actor's condition after any change
  to one of its vitals, and for every simulated actor at each world tick. The engine acts
  when the condition changes:
  - **`incapacitated`:** the actor is told "You are incapacitated." and the room sees
    "<actor> collapses." Until its condition is `ok` again it is treated like a stunned
    actor (§5.4.1), without a `stunned` affect: it takes no actions and makes no
    auto-attacks, its cast is interrupted as by a stun with `interrupt.stun`, its recovery
    is held, and it can't use tick-lane commands (§4.9). It stays engaged, can be targeted
    and regenerates. When it ends the actor is told "You come to." and the room sees
    "<actor> stirs."
  - **`dead`:** the actor dies, as below.
  - **`ok`** again ends incapacitation. A rules error keeps the previous condition (§6.3).
- **Death:**
  - Engine-owned, and part of the kernel: any vital can kill, in or out of combat. The
    killer is the last actor who damaged the victim, if still in the world. The order is:
    messages, `on_death` (target's own hook, then the room, then observers), XP (with the
    `leveling` feature), then the corpse.
  - **For mobs:** awards XP via `xp_for_kill` and creates a corpse container holding the
    mob's inventory, its equipment and the loot rolls. A summoned mob (`summonedBy` set)
    awards no XP and rolls no loot.
  - **For players:** applies `rules.on_player_death` and leaves a corpse holding the carried
    inventory only; equipment stays on the player. The player respawns at index 0 of the
    respawn room with the vitals `on_player_death` returned, its condition `ok`, and all
    affects removed. If `condition` still says `dead` for the respawned player, that is a
    rules error, and the player's vitals are set to their maxima.
  - Corpses decay after `CORPSE_DECAY_PULSES`. Decay drops a container's contents where it
    lay.
  - Death clears `casting`, the queue, and engagement. It's processed exactly once. During
    phases 2 and 3 it's deferred to the end of the current initiative tier (§5.4.1).
- **Levelling** (the `leveling` feature): awarding XP raises `level` while
  `xp >= xp_to_level(level)`. There are no stat or vital gains yet: the rules have no hook
  for them.
- **Respawns:** a spawn rule's respawn deadline is set when its first live instance leaves
  the world. The rule refills to `count` at the first world tick at or after the deadline.
  Rooms fill their spawns when the world is constructed.
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
  after every engaged tier. Initiative is rolled only in the combat round phase, so an
  actor engaged after the roll has none until the next round and acts in that final tier.
- **Ties are simultaneous.** Every actor in a tier gets its action, even if another actor
  in the same tier kills, stuns, or displaces it first:
  1. **Snapshot:** When a tier starts, the engine records, for each simulated actor
     (§5.9), which includes every actor in an active room: whether it can act, its room,
     and its track index. Same-tier actions are validated (target present, band in range,
     actor able to act) against this snapshot, not the live state. An actor outside the
     snapshot cannot be affected by the tier, so it is validated against its live state.
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
- **Mortally wounded:** An actor whose condition became `dead` during a tier, waiting for
  end-of-tier death processing, is *mortally wounded*. It still takes its own action, and can still be targeted. If a
  same-tier heal brings it back above 0 before the tier ends, it doesn't die.
- **Death beats movement:** If an actor is still mortally wounded when its tier ends, its
  death is processed and any deferred room transition of its own (move, flee, teleport) is
  dropped. If it was healed above 0, the transition goes ahead.
- **Stuns between tiers:** A stun or incapacitation applied during a tier stops the
  target from acting in *later* tiers, but never stops a same-tier action.
- **Recovery during a stun:** When a stun starts, the target's `waitState` is set aside
  and cleared. This includes the `lag / 2` from a cast the stun interrupted (§5.3). When the
  last `stunned` affect expires or is removed, the set-aside value is restored as
  `waitState = max(waitState, held)`. So recovery doesn't count down during a stun, and an
  interrupted cast never resolves. A cast whose `interrupt.stun` is false is not broken; it
  stays paused, and its warmup ends that many pulses later. The action queue is kept, and
  queued actions run once the stun ends.
- **Determinism:** The rolls use the seeded `rng`, so the same seed and inputs always
  produce the same order. That's what keeps replays exact.
- **Template:** The template ruleset uses `dex` plus `level`, with no roll (§4.6).
- **Visibility:** `@trace rules` prints each round's initiative order, and `Char.Status`
  carries the character's own initiative.

### 5.5 Intra-Room Spatial Model

All combat is self-contained within a single room. No projectiles cross exits. The track,
bands, `advance`, `retreat` and pinning are the `track` feature; without it every room has
one slot (§3.10), and only room transitions below apply.

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
  at the target's index. With no target, only hostiles are considered.
- **`retreat [from <target>]`:**
  - Steps 1 index away from the target, with lag set by rules (default 2). With no target,
    the threat is the nearest hostile by track distance, then lowest id; with none, "You
    have nothing to retreat from." and no lag.
  - On a shared index the actor steps toward the side with more room; a tie goes toward the
    back (higher index).
  - It's impossible at a track edge, and the actor is told so with no lag.
  - **When pinned** (a hostile shares the actor's index), it requires a `retreat_check`
    roll against the first opponent at the actor's index. Failure applies extra lag
    (default 4) and no movement.
- **Room transitions:**
  - Non-combat movement costs one action plus the vitals `rules.move_cost` returns (§4.6).
    The action is the action pulse itself: walking leaves no recovery lag.
  - Arrivals are placed at the exit's `entryIndex`: by default `0`, and `-1` maps to
    `trackSize−1`. When the destination is smaller, the index is clamped.
- **Pinning and exits:**
  - A normal exit command works from any index **unless a hostile is at the actor's
    index**.
  - A pinned actor must `retreat` first, or `flee`.
- **`flee`:**
  - Warmup 0. It rolls `flee_chance` and ignores pinning.
  - **On success:** the actor picks a random passable exit (closed or locked doors are
    excluded). The exit is then checked like a walk: guards (§4.7), then `before_exit` and
    `before_enter`. If they allow it, the actor leaves through it and engagement ends; a
    denial shows its message and counts as a failure, so combat can't be used to slip past
    them.
  - **On failure:** lag (default 4).
- **Items on the ground:**
  - Items sit at the dropper's index.
  - `get` uses `rules.get_lag(band)`. The default is 0 for `MELEE`, 2 for `NEAR`, and not
    allowed at `FAR`.

### 5.6 Event Dispatch

- **Order:**
  1. The acting entity's own hook.
  2. For `before_*` hooks, the actor performing the action (`ctx.actor`), if it isn't the
     acting entity. `on_*` hooks skip it, so an actor never observes its own events.
  3. The room's hook.
  4. The other actors in the room, ascending `EntityId`.
  5. The items in the room, ascending `EntityId`. Then the items carried or equipped by
     the acting entity (if an actor), the actor performing the action, and the other
     actors in the room, grouped by owner in that order and ascending `EntityId` within
     each owner. The performing actor's items fire for `on_*` hooks too, even though the
     actor itself is skipped.

  For `before_*` hooks, the first `deny` short-circuits.
- **Containment:** Exceptions from the script host are always logged as script diagnostics
  and treated as "allow", even in strict mode. Strict mode rethrows only engine exceptions.
- **Timing:** `before_*` hooks run when the action is *executed* (popped), not when it's
  enqueued.
- **`on_*` hooks** observe the post-change state. They can't undo it, only issue new verbs.
- **Cascades:**
  - Verbs that cause events (`damage` → `on_damage` → `on_death` → loot `on_spawn`) dispatch
    synchronously, nested.
  - The maximum nesting depth is 8. Every script entry counts toward it: dispatched events,
    self-only hooks (`on_tick`, `on_spawn`, …), and timer and affect callbacks. An entry that
    would nest deeper is dropped with a diagnostic.
  - All nested hooks share the top-level event's step budget (§6.1).
- **Quarantine:** Hooks of a quarantined package aren't invoked.

### 5.7 Adjacent-Room Perception

- **Opt-in:** It's per mob, through `senses`, with a radius of 0 or 1. The default is 0
  (same room only). A mob perceives events in its own room whatever its senses, with no
  `via_exit`. A sense of 1 adds the rooms its own exits lead to: `hearing` covers `sound`
  and `noise`, `sight` covers `sight`.
- **Channels:** `sight`, `sound` and `noise` are built in, and the engine's events use only
  them. Channels a world declares (§3.9) can be blocked by exits and doors, but no event is
  raised on them yet.
- **Channels by event type:**

| Event | Channel | Carries to |
|---|---|---|
| `say`, `emote` | `sound` | `hearing ≥ 1` |
| `shout`, combat start, combat round, `death` | `noise` | `hearing ≥ 1` |
| `enter`, `exit` (visible movement) | `sight` | `sight ≥ 1` |
| `whisper`, `tell` | — | never |

- **Event names and `ctx.actor`:** `say`, `emote` and `shout` carry their text, and the
  actor is the speaker. `combat_start`: the attacker. `combat` (once per room per round): no
  actor. `death`: the one who died. `enter` and `exit`, including teleports: the mover.
- **Propagation:** An event in room R reaches a listening mob in a room adjacent to R only
  if the connecting exit's effective `senses_blocked` doesn't contain the event's channel. The exit
  checked is the listener's exit toward the source, so a one-way exit into the source room
  carries events and one out of it doesn't. A closed default door blocks `SIGHT` and `SOUND`
  but not `NOISE`. `senses_blocked = SENSE.all` blocks everything.
- **Delivery:**
  - It's **deferred** to phase 5 of the current pulse, and calls `on_nearby` on the mob.
    Events raised while phase 5 delivers wait for the next pulse's phase 5.
  - `ctx.via_exit` is the mob's own exit direction leading toward the source.
  - Only mobs that are themselves simulated (in the bubble, or active) and have an
    `on_nearby` hook receive events.
- **Notify only:** A mob can never veto from an adjacent room.
- **Budget:** The hook runs against the *listening* mob's package for step budget and
  quarantine.
- **Deduplication:** Events are coalesced per (mob, event, source room) per pulse, keeping
  the first.

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
  `senses_blocked` and door state are ignored when computing it. Rooms flagged `always_active`
  are always in the set.
- **Simulated entities:** Players, and entities with non-empty `activeReasons` or
  `anchorReasons`, wherever they are. Anything else is simulated when its room (following
  carriers and containers) is active. Pets and followers bonded to a player get the reason
  `bond:<player_id>`.
- **Configuration:** the bubble is a world option, on in `gups serve` and off by default in
  Kotlin tests, the harness and `gups repro` (§2.4).
- **Active vs anchor:**
  - `active` simulates only the entity itself: its queue, timers, AI, and movement. Rooms
    it passes through stay dormant.
  - A simulated entity can't target, fight, or perceive *dormant* entities. They're
    inert to it until a bubble covers them: they can't be found by keyword, aren't
    opponents, aren't in `enemies_near`/`allies_near`, and are skipped by `room`-range
    skills.
  - `anchor` projects a full radius-3 bubble.
- **Reference counting:** Both sets are ref-counted by reason (`death_mark:<player_id>`).
  The entity stops being active or anchored only when its last reason is cleared.
- **Recomputation:**
  - It's incremental. Anchors are found by a scan of the world each pulse. When an anchor
    changes rooms, a BFS from the old and new rooms produces the set difference. A reload
    recomputes it in full.
  - Rooms leaving the set go dormant. Rooms entering it wake up. Rooms that sleep are
    processed before rooms that wake, each in ascending id.
  - Changes are applied in phase 6.
- **Startup and restarts:** Rooms start dormant from the boot pulse. After `on_load`, any
  restored timer running in a dormant room is paused, then the bubble forms. Active and
  anchor reasons are saved with every entity and player, so an `active` boss stays active
  across a restart; dormancy itself isn't saved.
- **Going dormant:**
  - AI, `on_tick`, and `on_combat_tick` stop. Entities with an active or anchor reason keep
    running.
  - Each `ScriptTimer` converts from `due` to `remaining`, keeping at least 1 pulse to run.
  - Affects are **not** paused. Their `expiresAt` is an absolute pulse, so they keep
    running out while the room is dormant.
  - Combat disengages (§5.4). Mobs drop their action queue and cast; recovery
    (`waitState`) is kept.
- **Waking (lazy catch-up, never simulation),** in this order:
  1. Timers convert back from `remaining` to `due`.
  2. Mobs recover (below).
  3. Affects that ran out are removed, firing `on_expire` in expiry order, then id.
  4. A missed zone reset is applied to the room: its spawns, then `on_reset`.
  5. Respawns that are due fill.
  6. Items that decayed go.
  7. `on_wake(ctx, elapsed_pulses)` fires on the room, then its mobs, then its items (each
     ascending id), as they were before the respawns.
  - **Mob vitals** recover according to the mob's `dormant_recovery`:

    | Mode | On wake |
    |---|---|
    | `full` (default) | Vitals reset to max, unless the mob has a `no_regen` affect. Classic MUD behavior. |
    | `regen` | Catch-up regen for the elapsed world ticks, computed with `rules.regen` and the modifier chain, so slowed regen stays slow. |
    | `none` | Vitals stay exactly as they were when the room went dormant. |

  - With a `no_regen` affect still running at wake, `full` falls back to the piecewise
    catch-up, which then restores nothing while the affect lasts.
  - **Catch-up is piecewise.** The elapsed time is split at every affect expiry that fell
    inside it, and regen is computed per segment with the affects active in that segment.
    A 15-minute `no_regen` affect therefore suppresses exactly 15 minutes of healing,
    then normal regen resumes.
  - The world ticks counted are those after the room went dormant, up to and including the
    waking pulse. An affect counts as active at a tick when its `expiresAt` is later than
    the tick, matching live play, where expiry (phase 1) comes before regen (phase 4).
  - **Raid boss example:** when the boss drops below a threshold, its script applies
    `apply_affect(boss, "weakened", duration=3600, flags=["no_regen"])`. The boss stays
    weakened for 15 minutes even if the party runs far away, whatever its
    `dormant_recovery` mode. If the boss should also keep *acting* while players are
    away, mark it `active` instead (see "Active vs anchor" above).
  - Nothing that would have happened while dormant is replayed. In particular, there's no
    offscreen wandering.
- **Pathfinding:** `ctx.path_to` runs a BFS over the full room graph, over exits in
  declaration order (so ties resolve the same way every time) and through open doors only:
  closed and locked doors block. Results are cached per `(source, dest)`. A door change and
  a reload clear the cache; `set_exit_senses_blocked` doesn't, since `senses_blocked` never
  affects passability.

### 5.10 Hot Reload and Deploy

**`--dev` auto-reload:** The file watcher (§2.5) reports changes once nothing has changed
for 200 ms, grouped by package: the nearest directory with a `package.star`, or the file's
own directory if that file was just deleted. It then posts `Reload(changedPackages)`, which
carries package paths (`zones/forest`), since a reload swaps whole packages.

**Reload algorithm** (on the world thread):

1. Re-evaluate every live package plus the named ones, together, into fresh, frozen
   prototypes in isolation, so labels across packages are checked against the new code.
   Every label must resolve, including cross-package exits and loads, and `api_version`
   must match.
2. **On failure:** Any load error, or a result with no rooms, refuses the whole reload. Keep
   the old version live. Report the errors with file, line, and a caret snippet, in game to
   every connected builder and instructor and in the `@errors` log. Stop here.
3. **On success:** Atomically swap the prototype table entries. A package whose
   `package.star` is gone is removed, unless something still refers to it.
4. Reconcile live instances (§3.4): run migrations, overlay deltas, and create tombstones.
5. Rebind `HookRef`s by `(file, name)`. Timers whose function no longer exists are dropped
   with a `reload` diagnostic. Hooks fire against the new code from the next event onward.
6. Clear quarantine for the reloaded packages (a `--dev` save counts like a deploy).
7. Invalidate the path cache and recompute the bubble, then take a periodic checkpoint.

**Production deploy:**

1. After a merge, CI calls `gups deploy --commit SHA` (through `POST /deploy`), or an
   instructor runs `@deploy [commit]`. Without a commit, the fetched tip of `deploy_branch`
   is deployed; any commit deployed must be on that branch. Only one deploy runs at a time,
   each on a thread of its own; a second is refused at once. Deploys run only in `--prod`
   when the world is a git working tree; otherwise `@deploy` says why. The engine shells out
   to the `git` binary.
2. **Fetch:** `git fetch --no-tags <repository or origin>
   +refs/heads/<branch>:refs/remotes/gups-deploy/<branch>`, with the deploy key through
   `GIT_SSH_COMMAND` (`IdentitiesOnly`, `StrictHostKeyChecking=accept-new`). Only that
   branch is fetched, so a commit the server doesn't know is reported as not on the branch.
3. **Checks:** before anything is staged, the deploy is refused if the commit isn't an
   ancestor of the fetched tip, if the live tree has uncommitted changes to tracked files,
   if `MODULE.gups` or `MESSAGES.gups` differs between `meta.world_commit` (or `HEAD` if
   none) and the commit (an engine upgrade, a schema or feature change, or new messages need
   a restart), or if any changed file is under `plugins/` (plugin changes need a restart,
   §4.15).
4. **Changed packages:** each changed file is charged to the deepest package directory, old
   or new, that contains it.
5. **Staging:** a `predeploy` backup, then `git worktree add --detach` into a temporary
   directory. Every package is loaded there off the world thread, and any error refuses the
   whole deploy. The worktree is always removed afterwards.
6. **Swapping:** a `Task` on the world thread runs the reload's swap, so prototypes swap,
   live instances reconcile, and the changed packages leave quarantine. All packages
   succeed or none are swapped. Plugin commands, effects and GMCP producers live on the
   `World`, so they survive the swap and are never installed twice.
7. **After the swap:** `git checkout --detach` moves the live tree; `meta.world_commit` is
   set (saved with every checkpoint); CODEOWNERS is synced from the live tree, with new
   scopes applied to characters in play; a routine checkpoint is taken; and online
   instructors get an alert.
8. **Failure:** the world is unchanged. The errors go to the response, to a `deploy`
   diagnostic (so `@errors`), and to an instructor alert.

A change to `MODULE.gups` (an engine upgrade) or `MESSAGES.gups` needs a restart, with the
new image for an upgrade, and a schema or feature change also needs `--wipe` (§3.9). Hot
reload is refused for both.

### 5.11 Persistence and Checkpoints

- **Dirty tracking:** Any change, whether through a verb or engine code, sets the entity's
  `dirty` flag.
- **Periodic checkpoint** (every `checkpoint_minutes`, default 30, set in `MODULE.gups`;
  checked at world ticks). Writes are kept rare to spare the disk: crashes are expected to
  be rare, and every graceful shutdown and reload saves.
  1. The world thread runs the §3.8 invariant check, then serializes all dirty, non-`debug`
     entities, plus `zone_states`, the `meta` pulse, `next_entity_id`, `next_handle`, and
     pending diagnostics, into a `CheckpointBatch` of JSON bytes. Entities named in an
     invariant violation are left out and stay dirty.
  2. It clears the dirty flags and submits the batch.
  3. The writer commits each batch in **one transaction**, in FIFO order.
- **Immediate capture** (at the end of the triggering action's cascade): it captures every
  entity dirtied during that cascade, plus `item_events` rows, at once. The capture is held
  in memory and written in the same transaction as the next periodic checkpoint (or the
  final one at shutdown).
  - A cascade ends after each input line, after each actor's action in a tier, after the
    timer phase, and after the action phase.
  - Triggers: an item moving from one player's custody to another's, a shop purchase, a
    bank deposit, a level-up, character creation or deletion, and logout or quit. (Shops and
    banks don't exist yet.)
  - The receive and the give are always captured at the same moment and land in the same
    transaction, which closes the classic give-then-crash duplication exploit.
- **The writer:**
  - It never makes the world wait. A batch merges into the last queued one (last write wins
    per entity) unless that one is being written right now.
  - A failed write stays at the head of the queue and is retried with backoff from 50 ms,
    doubling up to 5 s; later periodic captures may merge into it.
  - A failure makes the writer unhealthy until a write succeeds. While unhealthy, new logins
    are refused (§6.4).
  - If a submitted batch is still uncommitted three world ticks later, one `checkpoint`
    diagnostic is logged (and alerted) until the backlog clears.
- **The mirror:** the persistence layer keeps the last captured row of every entity and
  player in memory. Logins and repro bundles read the mirror, so they never wait on the
  database or miss a batch that isn't committed yet.
- **Debug entities** are skipped by every checkpoint, so they are never saved and are gone
  after a restart.
- **Crash guarantee:**
  - At most one checkpoint interval of state is lost (by default 30 minutes), including
    held immediate captures.
  - An ownership transfer is either saved with both sides or lost with both sides.
  - The database is never inconsistent.
- **Shutdown and reload:** `@shutdown` and a successful reload both perform a final
  checkpoint (§5.10). A shutdown broadcasts its reason, stops the world loop once its delay has
  passed, takes the final checkpoint on the world thread, and waits up to the server
  configuration's `shutdown_timeout` (default 30 seconds; §2.4) for the writer to commit it. A
  JVM shutdown (Ctrl-C, `SIGTERM`) does the same with no delay. `--dev` always waits 30 seconds.
  If the commit isn't done in time, the server logs "Some changes could not be saved before
  shutdown." and exits anyway; the uncommitted state is lost as in a crash.
- **Backups:**
  - Snapshots are taken with the SQLite online-backup API, as
    `<kind>-<UTC yyyyMMdd-HHmmss>.db` in `backups/` next to the database.
  - Kinds: `hourly` (keep 48), `daily` (keep 14), `manual` (`gups backup` and `@snapshot`,
    never pruned), `predeploy` (each deploy, never pruned) and `prewipe` (each `--wipe`,
    never pruned).
  - The server checks every 5 minutes, and once at startup, whether the newest snapshot of
    a kind is older than its period.
  - `gups restore` rolls the world back, for example to before a broken merge.
- **Startup:**
  1. Open the database and run engine schema migrations.
  2. Load the course world packages.
  3. Restore `world_entities` (not players). With `--fresh`, first delete every saved row
     that isn't a player's or a player-owned item, and rewrite zone state; the pulse and the
     id counters are kept, so saved characters' ids and deadlines stay valid.
     - Room rows whose room is gone (or got a different id) are deleted.
     - Items are placed after their holders, containers before contents. Rows that can't be
       placed are dropped.
     - Mobs and ground items of deleted prototypes are dropped.
     - Saved spawn bindings and respawn deadlines are restored. A rule that is short with no
       respawn pending (for example a new rule) fills at once.
     - Quarantine is restored from `zone_states`.
  4. Reconcile all instances against prototypes.
  5. Set `pulse` from `meta`.
  6. Fire `on_load` (§4.4). Every room starts dormant. The bubble forms as players log in.
- **Characters load at login,** not at startup. A player's row, and every item whose
  location chain ends in that player, are restored from the mirror when the player logs in.
  A player whose room is gone is placed in their recall room, or else the first room. On
  logout the character and everything it carries are captured in an immediate batch, then
  removed from the world; their rows stay.

### 5.12 Sessions and Authentication

- **Creating accounts:**
  - `gups import-roster roster.csv` reads the columns `login,github_handle,role` (only
    `login` is required; `role` defaults to `player`). New logins become accounts without a
    password, each with a **setup code**: a one-time, 7-day invite bound to that account,
    printed for the instructor to hand out. Students choose their own password with it; the
    server never hands out a usable password. Existing logins have their GitHub handle and
    role updated, and their passwords left alone. An optional `disabled` column disables or
    re-enables listed accounts (§4.13).
  - `gups invite --role R [--days N]` and `@invite <role>` make an unbound invite code
    (default 7 days). Only its hash is stored.
  - Open signup (`open_signup = True`) lets a new login be created at the telnet login prompt
    without a code. It is off by default (§1.7).
  - There is no self-service reset: `@role <account> reset` issues a new setup code. Players
    who know their password change it with `password` at the character menu.
- **Login:**
  - The password is verified with Argon2id (m = 64 MiB, t = 3, p = 1, a 16-byte salt and a
    32-byte hash), stored as the standard PHC string `$argon2id$v=19$m=65536,t=3,p=1$…`. The
    implementation is Bouncy Castle's pure-Java generator, so the jlink images need no
    native code.
  - Passwords have at least 10 characters and don't equal the login.
  - **Lockouts:** failures are counted per lowercased login (whether or not it exists) and
    per peer address. The count resets after 15 minutes without a failure, so failures
    spaced less than 15 minutes apart keep accumulating. The fifth starts a 1-minute
    lockout, and each later one restarts the lockout at double length, capped at 15
    minutes. A successful login clears the account's count but not the address's. Bad
    invite codes count against the address only. Telnet and web logins share one limiter.
    A locked-out attempt is refused without hashing, so an attacker can't spend the
    server's CPU. The counts live in memory only; a restart clears them.
  - A login that doesn't exist still costs one hash of a fixed dummy value, so response time
    doesn't reveal which logins exist.
  - An account without a password (a roster account not set up yet) fails like a wrong
    password, after a dummy hash, so it looks like any other wrong login.
  - A disabled account gets "This account is disabled." after a correct password.
  - The web client may exchange a successful login for a 30-day session token (§4.10):
    32 random bytes as unpadded base64url, stored as SHA-256 hex with a wall-clock expiry.
    Expired rows are pruned whenever a token is added. A token stops working when it
    expires, at logout, when the password is changed or reset, at `@role revoke`, and when
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

  - Prompts: the login prompt, then the character menu with the prompt `> `. `quit` at the
    menu closes the connection. An empty answer at a prompt asks again; at the
    character-name prompt it goes back to the menu. 60 seconds idle at any prompt closes
    the connection.
  - Echo is turned off for passwords (§4.10); clients that refuse simply see their typing.
  - Three strikes per connection close it. Strikes are wrong passwords, refused locked-out
    attempts, bad codes, and a wrong current password when changing it.
  - Password change: current, new, new again, then one background step that verifies and
    rehashes. A wrong current password counts as a failed login for the limiter.
  - `new` asks for a setup or invite code, then a login (unless the code is bound to an
    account), then a password twice. A bound setup code shows which login it sets up.
    Logins are lowercased. A redeem that loses a race reports it and goes back a step.
  - Slow work (hashing, account lookups, code checks, login-taken checks) runs on the
    background pool (§2.5). Lines typed while it is pending are held (at most 20) and
    replayed in order when it finishes: to the login, or as game commands once a character
    is playing.
- **Characters:** After login, the player chooses a character, or creates one with the
  stats `rules.starting_stats` gives it (§4.6); the player makes no choices. The menu
  lists saved characters plus any of the account's characters in the world not saved yet.
  Creating a character triggers an immediate checkpoint. The character is placed in its
  saved room, which becomes an anchor; new characters start in the start room (§2.3). The
  role and idle exemption come from the account each time a character is entered. Loading a
  saved character is refused in maintenance mode.
  - **One character at a time:** an account plays one character at a time, across every
    transport. Choosing a different character while one is in the world (link-dead
    included) is refused with "You're already playing Ann." Instructors are bound by this
    too (only `--dev` is exempt).
  - A character already playing is taken over: the old connection is told why and closed.
- **Disconnects:**
  - A dropped connection makes the character **link-dead**. It stays in the world, keeps
    anchoring its bubble, and keeps fighting with auto-attacks only, for 3 minutes.
    Disconnecting clears the action queue, and can't be used to escape combat.
  - Reconnecting within that window resumes the session.
  - Otherwise the character auto-quits, with an immediate checkpoint. The auto-quit ignores
    combat.
- **Idle players:** After 30 minutes without input, a player is auto-quit. Instructors are
  exempt.
- **`--dev`:**
  - With a database, `--dev` creates the account `instructor` (role instructor, no
    password) on first boot. Every dev character belongs to it (dev characters saved before
    it existed join it), so each gets the instructor role and is idle-exempt. Without a
    database (tests) characters have no account.
  - Only connections from `127.0.0.1`/`::1` are accepted; any other address is refused and
    closed.
  - The server asks for a character name: 2 to 16 letters, capitalized. Naming a character
    that is already playing takes it over and closes the old connection. Otherwise a saved
    character is restored, or a new one is created and saved at once.
  - The one-character-at-a-time rule doesn't apply: several characters at once help testing.
  - While the database can't be written, new logins are refused with a maintenance message;
    taking over a character still works.

### 5.13 Developer Tooling Behavior

- **`@trace`:**
  - Streams one line per hook invocation, verb call, and rules chain evaluation, with step
    counts.
  - Scope: on the shared server, the builder's own packages plus their own character's
    rules lines. Instructors, admins and `--dev` see everything.
  - Filters: `zone` means the given package, or else the package of the caller's room.
    `entity here` traces the room itself. `hook` matches a hook name, or a command or timer
    prefix such as `command` or `timer`.
  - `rules` streams each rules function and modifier call, and one line per evaluation with
    its full chain. Auto-attacks add the to-hit roll and the mitigated damage; each combat
    round adds the initiative order.
  - It's rate-limited to 50 lines per pulse per session. The rest are counted, and the
    count is reported with the next traced line or at `@trace off`.
- **`@eval`** (`--dev` only): Evaluates Starlark with a live `ctx` bound to the
  caller's character. Its verbs mutate the live world.
  - The input is evaluated as an expression and its `repr` is shown; anything that isn't an
    expression runs as statements. `print()` output comes first.
  - `ctx`, `self` and `room` are predeclared, along with the package builtins (effect
    primitives, constants) and plugin globals. Each call gets a fresh module.
  - Relative labels and attribute keys belong to the package of the caller's room.
  - Limits: 1,000,000 steps and 1 s, shared with any hooks its verbs trigger.
  - Errors are shown to the caller only. They are not diagnostics and don't count toward
    quarantine.
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
  exactly. The wall-clock guard only catches slow host calls: it is measured from the start
  of the top-level event or package load, and checked at every `ctx` verb call and every
  declaration call, so a hook that loops without calling the engine is stopped by the step
  limit only. The harness turns the wall clock off.
- The load limit is per package, across its files.
- **Rules evaluations:** each rules function call and each modifier counts as one
  evaluation, with a fresh budget even when a hook triggered it (for example `ctx.damage`
  asking `interrupt_check`); its steps aren't charged to the hook's cascade. Failures are
  charged to the package of the function's file (the rules package, or the zone, library
  or skill package a modifier lives in) and count toward that package's quarantine.
- **Charging:** a package's charges decide the step budget's diagnostics, the quarantine
  count and the quarantine check.
  - A hook is charged to its owner's prototype package, even when the function lives in a
    loaded library file.
  - Timers and affect hooks are charged to the owner's package if it is a script package,
    otherwise to the package of the function's file (for example, a timer on a player).
  - Zone commands are charged to the declaring package, global commands to the registering
    package.
- **Allocation caps:** Starlark-Java has no allocation hook, so caps apply where values
  reach the engine: verb arguments, hook return values, stored attributes and timer
  arguments.
  - A string over 1,048,576 characters, or a list or dict over 131,072 elements (1 MB at 8
    bytes each), is an `alloc_limit` error. A hook can build larger values internally as
    long as it doesn't hand them over; the step limit bounds that work.
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
3. A diagnostic row is written with the next checkpoint batch, periodic or immediate. It
   contains:
   - The package, prototype label, hook, file label, line, and kind.
   - The message and Starlark stack.
   - A one-line `ctx` summary.
   - The seed and pulse, and the room the owner was in.
   - A **repro bundle**: the seed, the failure pulse, the pulse of the last periodic
     checkpoint, the hook and message, and the failing room's rows as of that checkpoint
     (the room, the mobs and players there, and every item in it or held by them,
     transitively). It also holds the inputs (tick-lane commands and `say`s, never tells)
     since that checkpoint by actors in rooms of the failing package.

   Repro is exact in the harness and best-effort from production.
   - **Script kinds:** `script_error`, `step_limit`, `wall_clock`, `alloc_limit`, `missing`
     (a hook or timer function no longer exists) and `quarantine`. The kind `script` is for
     failures of Kotlin-hosted handlers. Other kinds include `engine`, `invariant`,
     `reload`, `checkpoint`, `deploy` and `plugin`.
   - **`gups repro`** builds a headless world from today's packages with the bundle's seed.
     It starts at the checkpoint pulse, with ids above the bundle's and no spawns, and
     doesn't fire `on_load`. It restores the bundle and replays each input by actor id at
     its pulse, running 16 pulses past the failure. It counts as reproduced when a
     diagnostic with the same hook and message appears; the exit status is 1 otherwise.
     Players come back without a connection, so output to them is dropped.
4. **Who sees what:**
   - **Owner alerts:** online builders (not instructors) whose scope has the package get a
     one-line in-game alert for `script_error`, `step_limit`, `wall_clock` and `missing`,
     with at most one per package per 10 s.
   - **Instructor alerts** go to online instructors and admins as `[alert] ...`, and always
     to the server log, at most once a minute per kind. Kinds include `quarantine`,
     `rules_error` (at most once a minute per failing rules function), `checkpoint`, `engine_errors`, `overrun`, `plugin_load` and deploy
     results.
   - Players see nothing. For a failed zone command, they see "Nothing happens."

### 6.3 Quarantine (circuit breaker)

- **Trigger:** More than 20 errors from one package within 60 s. The window is 240 pulses of
  game time, so the harness and production agree; the 21st error inside it quarantines.
- **Effect:**
  - Its hooks allow, its zone and global commands answer "Nothing happens.", its `think`
    falls back to the top proposal, and its affects' `on_tick` and `on_expire` are skipped
    (the affects still tick down and expire).
  - Its timers stay pending: the world skips them while due, and they fire on the first
    timer phase after the quarantine clears.
  - Its rooms, exits, items, and mobs remain as static content. Mobs fall back to their
    built-in behaviors only.
- **Notifications:** Instructors (a `quarantine` alert) and the package's owners are
  notified.
- **Clearing:** Quarantine clears on the next successful deploy or reload of that package,
  or by `@unquarantine`. Quarantine state persists in `zone_states`; `@quarantine` and
  `@unquarantine` take a checkpoint so it does.
- **Rules package exception:** the rules package is never quarantined; there is no
  built-in ruleset to fall back to (§4.6).
  - **Load:** a rules package that fails to load, or fails its dry run, stops the server at
    startup. A reload or deploy with such a package is refused, and the previous rules stay
    live.
  - **Run time:** a rules function that throws, or returns the wrong
    type or an undeclared name, records a `script_error` diagnostic against the rules
    package, raises a `rules_error` alert, and gives a neutral result. A rules error never
    hurts, kills, rewards or traps anyone:

    | Export | Neutral result |
    |---|---|
    | `to_hit`, `retreat_check`, `flee_chance` | 0 |
    | `damage`, `mitigate` | no damage |
    | `initiative` | 0 |
    | `cast_allowed` | denied with "Nothing happens.", no lag |
    | `interrupt_check` | false |
    | `move_cost`, `regen`, `encumbrance`, `starting_stats` | nothing spent, gained, changed or overridden |
    | `carry_limit` | no limit (`Int.MAX_VALUE`) |
    | `get_lag` | 0 |
    | `condition` | the actor's previous condition |
    | `xp_for_kill` | 0 |
    | `xp_to_level` | no level-up (`Long.MAX_VALUE`) |
    | `on_player_death` | respawn at the recall room, vitals at their maxima, no XP loss |
    | `equipment_slots` | the last good list (empty before any) |

### 6.4 Engine-Side Failures

| Failure | Handling |
|---|---|
| Uncaught Kotlin exception in a command or phase | Log with context as an `engine` diagnostic and abort that action only. The pulse continues. More than 100 within the last minute (240 pulses) triggers an `engine_errors` alert. |
| Checkpoint write fails | Retry with backoff. The batch stays pending, and later captures merge into it (§5.11). Alert after 3 world ticks. Never block the world thread. |
| Disk full / database unwritable | As above. Also refuse new logins with a maintenance message until a write succeeds. |
| Slow client | Output buffer over 256 KB disconnects the client. The character goes link-dead. |
| Malformed telnet, GMCP or WebSocket frames | Ignore the bad sequence and continue. Repeated garbage disconnects after 5 errors. |
| Deploy fetch or load failure | The old world stays live. The diagnostic goes to the CI response and instructors. |
| Plugin fails to load | Skip the plugin and log it. If the world references its builtins, those packages fail to load (old version kept). |
| Built-in behavior throws | Log an engine diagnostic; the behavior proposes nothing. |

### 6.5 Invariant Enforcement

- The invariants in §3.8 are enforced in the Kotlin verb implementations, never in scripts.
- In `--dev` and in the harness, an invariant check runs at the end of every pulse. In the
  harness (strict mode) a violation throws and fails the test. In `--dev` each violation is
  logged as an `invariant` diagnostic every pulse it persists, and the world keeps running.
- Every world also runs the check at each world tick before checkpoint capture (§5.11). The
  entities named in a violation are excluded from the checkpoint and stay dirty. Worlds that
  don't check every pulse (`--prod`) log each violation then and alert.

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
  - **Perception:** channels × exit `senses_blocked` × door states, deferral, and coalescing.
  - **Rules chain:** ordering; the dry run; each export's neutral result (§6.3).
  - **Reality bubble:** incremental BFS correctness against a full recompute, the
    dormant/wake timer conversion, lazy catch-up, and active vs anchor semantics.
  - **Dormant recovery:** each `dormant_recovery` mode, and piecewise catch-up across
    `no_regen` and slowed-regen affects that expire during dormancy.
  - **Persistence:** schema migrations, and reconciliation (version migrate, delta overlay,
    tombstones).
  - **Auth:** Argon2id parameters and PHC round trip; lockout timing per account and per IP
    under a virtual clock; dummy hashing for unknown logins; roster import idempotence and
    single-use, expiring setup codes; the whole telnet login flow against a headless world
    (wrong passwords, takeover, creation, reserved names, the character limit); hashing
    never on the world thread (a 1 s blocking hash keeps the world pulsing); an account
    change surviving a crash.
  - **Roles and tools:** CODEOWNERS parsing and precedence; scope enforcement for every
    builder command; restrictions (timed expiry under a virtual wall clock, the ladder,
    lifting, history, surviving a restart); each instructor command; alert rate limits; the
    overrun attribution.
  - **Telnet and GMCP:** option negotiation against recorded client transcripts (Mudlet,
    TinTin++, raw `nc`), over TLS too; GMCP delta suppression; `Room.Bands` from each
    viewer's index; no GMCP without `Core.Supports`; certificate hot reload.
  - **Web:** HTTP login and lockout; token expiry and revocation closing live sockets; a
    frame-level WebSocket session; a headless browser test (Selenium's Java bindings with
    headless Firefox, no Node) that logs in, moves and sees vitals. It runs on Linux x64 CI
    (`CI=true`, `os.arch` `amd64`/`x86_64`) and is skipped elsewhere;
    `-Dgups.browserTest=true` runs it on any machine with Firefox. Selenium Manager fetches
    geckodriver.
  - **Operations:** admin auth and constant-time comparison; metric names present after one
    pulse; deploys end to end against a local bare repository (success, a load failure
    leaving the world untouched, concurrent deploys refused, a `MODULE.gups` or `plugins/`
    change refused, CODEOWNERS changes taking effect); `/healthz` turning 503 when the world
    thread stalls.
  - **Docs and API:** the rendered reference is deterministic, every symbol has its anchor,
    every link resolves, nothing unescaped gets through, and a small model matches golden
    files; every doc example runs; the API-change check (§4.17) passes and covers removal,
    addition, rename, signature and version-bump cases.
  - **Schema:** each schema problem stopping the server (§3.9); constructors refusing
    undeclared names and plain dicts; view fields and suggestions; the dry run catching a
    misspelled vital and a wrong return type; `starting_stats` for players and mobs,
    randomized and not; conditions, incapacitation and time-based deaths; a changed schema
    or feature set refusing to start, and `--wipe` keeping accounts. Every test that
    touches vitals runs against two fixture schemas: the template's, and a non-fantasy one
    with no `hp` or `mana`.
  - **Features:** `FeatureMatrixTest` runs every valid feature set (12) through a smoke
    scenario (moving, getting, using, a death through `condition`, plus what each enabled
    feature adds) with the invariant check every pulse. For each feature that is off: its
    commands say "Huh?", its declarations, hooks and exports fail to load naming it, and its
    GMCP packages and fields are absent. Renames, and the message catalog (every id has a
    default; overrides apply; bad ids and placeholders stop the server).
  - **Plugins:** every extension point end to end in the server and in `gups test`; each
    loading failure skipping only its plugin; every name clash refused; deploys touching
    `plugins/` refused while other deploys keep plugins working; no `core` or `script` type
    in the public `gups.pluginapi` surface; the sample plugin's unit and end-to-end tests.
- **Property-based tests:**
  - Random sequences of item verbs never violate the single-location or forest invariant.
  - Random checkpoint, kill, and restore sequences never duplicate or lose an item that
    was part of a committed transfer.
- **Determinism:** The same seed and input script produce byte-identical output and final
  world state across runs and platforms.
- **Crash consistency:** `kill -9` the server at random points during checkpoint writes,
  hundreds of iterations. Every restart must open cleanly and satisfy §3.8.
  - A child JVM shuffles 20 gems between four players (who log in and out), two bags and the
    ground. It checkpoints every few pulses, immediate and periodic mixed, calling
    `checkpoint` far more often than a server would so kills land mid-write, and is killed
    with SIGKILL at a random moment. Each restart must hold exactly 20 gems once all players
    log in. This also covers the checkpoint/kill/restore property test.
  - `./gradlew check` runs 20 iterations; `-Dgups.crashIterations=300` runs the full set.
- **API model:** tests fire every hook and check its documented `ctx` fields both ways, check
  that every `@StarlarkBuiltin` class in `gups.script` and `gups.harness` is in the model,
  and compare `api.json` with the golden copy (`-Dgups.updateGolden=true` rewrites it).
- **Load test** (deferred to a later milestone, §1.7):
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
  commit. The canonical copy is `template-world/zones/tutorial` in the engine repository,
  run by the harness and CLI tests.

### 7.3 Course World CI Gates (every PR)

Gates 1 and 2 run together as `gups lint`; gate 4 is `gups fuzz`; gate 5 is
`gups migrate-check`. The workflow is the template's `.github/workflows/gates.yml`.

1. **Load:** Every package loads, `api_version` matches, all labels resolve (exits, spawns,
   loads, keys, skills), and namespaces are unique. Hook catalog errors and duplicate
   namespaces are errors.
2. **Lint:**
   - No dead-end rooms (1 exit or fewer) unless flagged `dead_end_ok` (error).
   - Every exit has a reverse unless `one_way`, and doors match on both sides (error).
   - A prototype whose declaration changed since `--base` needs a `version` bump (error).
     Presentation and code don't count: `name`, `desc`, `keywords`, hooks, `commands`,
     `think` and `resolve`. The comparison clears those fields by construction, so new
     fields are tracked automatically. If `--base` fails to load, the lint fails rather than
     passing. `--base` is staged in a temporary git worktree.
   - Warn on attribute keys that are written (`set_attr` string literals) but never read
     anywhere in the repo. Dynamic keys are skipped.
   - Warn on regions applied to rooms but never declared, and declared regions never
     applied.
   - Hook names must match the catalog, so a typo such as `on_entr` is an error.
   - Output is human-readable lines, or GitHub Actions annotations with `--format github`
     (repository paths, with `%`, `\r` and `\n` percent-encoded), plus a Markdown summary
     appended to `$GITHUB_STEP_SUMMARY`. It exits 1 on any error. Patterns default to
     `//...`.
3. **Zone tests:** Every changed zone has at least one `*_test.star`, and every test passes.
4. **World smoke:**
   - The whole world loads together in the harness, with the bubble off and a default seed
     of 42.
   - The fuzz walker runs 10,000 pulses through the changed zones with one bot player per
     changed zone. Each pulse, dead or absent bots respawn in a zone room, bots whose
     condition isn't `ok` or who have a vital below a quarter of its maximum get every
     vital set to its maximum, and a bot whose `wait_state` exceeds 50 pulses has it cleared. Idle bots
     pick randomly among exits, zone and item commands with empty arguments,
     get/drop/wear/use, `kill` on a random actor (with the `combat` feature), and `say`.
   - It fails at once on an invariant violation or any diagnostic of kind `script_error`,
     `step_limit`, `wall_clock`, `quarantine`, `alloc_limit`, `invariant` or `missing`. On
     failure it prints the exact rerun command (`gups fuzz --seed S --pulses P <patterns>`)
     and exports a repro bundle with the bots' initial positions and all inputs, for
     `gups repro`.
5. **Migration check:**
   - Load a sanitized snapshot of the production database against the PR. An instructor
     makes it by hand when they choose (`gups snapshot --sanitized`) and uploads it as the
     asset `snapshot.db` of a release tagged `migration-snapshot` in the course repository.
     If there is none yet, CI warns and skips the gate, so a new term's first PRs pass.
     Otherwise it reports the snapshot's age in the PR summary.
   - The snapshot's `world_schema` and `world_features` must match the PR's `MODULE.gups`;
     a difference fails the gate with "this schema or feature change needs a server wipe"
     (§3.9).
   - Every version bump on an entity in `world_entities` must have `migrate()` or be listed
     in `package(migrate_noop=[...])`.
   - Reconciliation must produce no tombstones for items in player custody that the PR
     didn't declare (`package(expected_tombstones=[...])`).
   - The snapshot must restore fully without script errors.
   - The workflow downloads the `snapshot.db` release asset through the API with
     `Accept: application/octet-stream`, so private course repositories work with
     `GH_TOKEN`.
6. **Changed packages:** changed files under `zones/` map to packages by searching parent
   directories for `package.star`, among directories that still exist (so deleted zones
   don't cause errors). If shared code changes (`lib/`, `rules/`, `skills/` or
   `MODULE.gups`), every package is tested and fuzzed.
7. **Budget:** The gates complete in about 2 minutes on a standard GitHub runner: the engine
   image is pulled with layer caching, tests run only for changed zones, and the fuzz walker
   stays within its pulse count. Their output is one Markdown table,
   posted to `$GITHUB_STEP_SUMMARY` and as a single PR comment (marked
   `<!-- gups-gates-summary -->`) that later runs update. If any gate failed, the workflow
   exits 1 after posting the summary.

### 7.4 Implementation Milestones (each gated by its tests)

| Milestone | Scope |
|---|---|
| **M0** | Gradle skeleton, module graph, `gups` CLI stub, CI matrix |
| **M1** | `core` scheduler, entities, command pipeline, tactical track, the Kotlin default ruleset, combat rounds. Runs headless with a Kotlin test world. |
| **M2** | `script`: loader, labels, freezing, `ctx`, views, verbs, hooks, limits, error handling, quarantine. `harness` with `gups test`. Tutorial zone. |
| **M3** | `net`: raw telnet with options and ANSI, plus `--dev` login. Playable locally. Auto-reload, `@trace`, `@eval`. |
| **M4** | `persist`: checkpoints, reconciliation, backups, `--fresh`, crash tests |
| **M5** | Rules module, modifier chain, skills and affects, behaviors and `think`, perception, reality bubble, items/equipment/containers, `//lib:quest.star` |
| **M6** | Built in six stages: **M6.1** accounts and login, and the `--prod` profile; **M6.2** roles, builder scope and the in-game tools, restrictions and alerts; **M6.3** TLS and GMCP; **M6.4** WebSocket and the web client; **M6.5** the admin port, metrics, the deploy pipeline and release images; **M6.6** the course world template and CI gates. The load test was deferred (§1.7). |
| **M7** | Built in five stages: **M7.1** the API model and `api.json` (§4.16); **M7.2** `gups docs` (§4.16); **M7.3** the changelog and the API-change check (§4.17); **M7.4** the plugin API (§4.15); **M7.5** the sample plugin and the engine-developer guide (§4.15). |
| **M8** | Disabling accounts: `@role disable`/`enable`, the disabled record (migration 4), and the roster's `disabled` column (§3.5, §4.9, §4.13). |
| **M9** | Configurable actor schema: stats, vitals, damage types and channels declared in `MODULE.gups`; typed constructors and schema view fields; no built-in ruleset, with `starting_stats`, `condition`, vital-keyed `mitigate`/`move_cost`/`regen`, the dry run and neutral results; incapacitation; `Char.Schema`; the plugin facade by name; `--wipe`; `samples/robot-world` (§3.9, §4.6, §6.3). |
| **M10** | Feature modules: `combat`, `track`, `skills` and `leveling` turned on per world, command renames, the engine message catalog and `MESSAGES.gups`, plugin feature requirements (§3.10, §4.18). Ships in the same release as M9. |
