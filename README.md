# gups-engine

The GUPS text MUD engine. The design lives in [docs/SPEC.md](docs/SPEC.md); decisions made where the spec is silent are logged in [docs/DECISIONS.md](docs/DECISIONS.md) until they are folded back in.

## Building

Requires JDK 21 or newer to run Gradle; the build compiles against a JDK 21 toolchain and
downloads one if it is missing.

```bash
./gradlew check                # all tests, plus the module graph check
./gradlew :cli:installDist     # builds the `gups` launcher
cli/build/install/gups/bin/gups --help
```

## Developing in IntelliJ IDEA

1. Install a JDK 21 or newer (any distribution). IntelliJ can download one under
   **File → Project Structure → SDKs → + → Download JDK**.
2. **File → Open…** and pick the `gups-engine` directory (the one containing
   `settings.gradle.kts`). Trust the project when asked, and let IntelliJ import it as a
   Gradle project. The first import downloads Gradle and the dependencies, which takes a
   few minutes.
3. Under **Settings → Build, Execution, Deployment → Build Tools → Gradle**, set
   **Gradle JVM** to your JDK 21, and **Build and run using** and **Run tests using** to
   **Gradle**, so the IDE builds exactly like CI.
4. Under **Settings → Plugins**, make sure the bundled **Kotlin** plugin is enabled.

Shared run configurations live in [`.run/`](.run) and appear in the run-configuration
dropdown automatically:

| Configuration | What it does |
|---|---|
| `gups CLI / gups serve --dev` | Starts the server from source, debuggable. Edit the program arguments to add `--world <dir>` etc. |
| `gups CLI / gups test`, `gups lint`, `gups docs`, `gups --help` | Runs the other CLI tools from source. |
| `Gradle / check (all tests)` | Everything CI runs, including the module-graph check. |
| `Gradle / core tests` | Just the `core` module's tests. |
| `Gradle / installDist (gups launcher)` | Builds `cli/build/install/gups/bin/gups`, the launcher students get. |
| `Gradle / gups serve --dev (Gradle)` | Starts the server through Gradle's `run` task, as on the command line. |

Every configuration can be started with **Debug** as well as **Run**. To debug a single
test, click the gutter icon next to the test. For a new tool configuration, copy one of
the `gups CLI` entries and tick **Store as project file** so it lands in `.run/` and can
be committed.

Most `gups` subcommands are still stubs that exit with status 3 (see Status below). `gups
test` works: it runs the tutorial zone's tests with
`gups test --world template-world //zones/tutorial/...`.

## Modules

| Module | Scope | May depend on |
|---|---|---|
| `core` | world model, scheduler, commands, combat, rules chain, bubble | nothing |
| `script` | Starlark host | `core` |
| `persist` | SQLite/WAL persistence | `core` |
| `net` | telnet, TLS, WebSocket, GMCP | `core` |
| `auth` | accounts, roles, invites | `core` |
| `harness` | headless test world and runners | `core`, `script` |
| `plugin-api` | `.jar` plugin extension points | `core`, `script` |
| `webclient` | static browser client (resources) | nothing |
| `cli` | the `gups` binary; wires everything | anything |

`./gradlew checkModuleGraph` (part of `check`) fails if a module declares a dependency
outside these rules. The allowed graph is defined in the root `build.gradle.kts`.

`docs/` holds `SPEC.md`, `DECISIONS.md`, `CHANGELOG.md` and `MIGRATING.md`.

`template-world/` is the seed of the course world template. It holds `zones/tutorial`, the
heavily commented reference zone whose tests run on every engine commit.

## Status

M2 (SPEC §7.4) adds the Starlark script host and the test harness on top of the M1 core.

- **`script`:** the package loader (labels, `load()`, `api_version` check,
  all-or-nothing per package, structured errors with caret snippets) and the content
  declarations. Evaluation produces frozen prototypes. The host runs hooks, zone and global
  commands, timers, affect hooks, `think` and `resolve` with a fresh `ctx` of read-only
  views and engine-validated verbs. It enforces the step, wall-clock and allocation limits
  (with one budget per event cascade), logs script errors as diagnostics without blocking
  the action, and quarantines a package after too many errors.
- **`harness`:** headless worlds with a virtual clock for `*_test.star` files (`t`, `w`,
  `p` handles), and `gups test`.
- **`core`** (M1): the pulse scheduler, entities and prototypes, the command pipeline, the
  action lifecycle, the tactical track, the Kotlin default ruleset, and combat rounds with
  initiative tiers.

Still to come:
- M3: telnet and `serve --dev`, auto-reload, `@trace` and `@eval`.
- M4: persistence, including diagnostics storage and repro bundles.
- M5: the Starlark rules module and modifier chain, behaviors, perception and the bubble.
- M6: roles and owner alerts.

All `gups` subcommands except `test` still exit with status 3 ("not implemented").
