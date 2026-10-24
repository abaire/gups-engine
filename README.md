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

Some `gups` subcommands are still stubs that exit with status 3 (see Status below). These
work:

```bash
cli/build/install/gups/bin/gups serve --dev --world template-world   # then: telnet localhost 4000
cli/build/install/gups/bin/gups test --world template-world //zones/tutorial/...
```

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

`docs/` holds `SPEC.md`, `DECISIONS.md`, `DESIGN_NEEDED.md` (unbuilt features the spec
doesn't settle yet), `CHANGELOG.md` and `MIGRATING.md`.

`template-world/` is the seed of the course world template. It holds `MODULE.gups` (world
metadata), the `rules` package, the shared `lib` package, and `zones/tutorial`, the heavily
commented reference zone whose tests run on every engine commit.

## Status

M5 (SPEC §7.4): the world runs on rules builders can change.

- **Rules module:** `//rules:combat.star` supplies the combat, movement and regen formulas.
  Anything it leaves out or gets wrong falls back to the built-in Kotlin ruleset.
  `template-world/rules` starts out identical to the built-in formulas.
- **Modifier chain:** zones, regions, rooms, affects and worn items adjust `to_hit`,
  `damage`, `initiative`, `cast_allowed`, `move_cost` and `regen`. `@trace rules` shows every
  step.
- **Reality bubble:** `gups serve` simulates only rooms within three exits of a player or
  anchor. Dormant rooms catch up when they wake: timers, respawns, resets, decay and each
  mob's `dormant_recovery`.
- **Mobs:** perception (`on_nearby`) across exits by sound, noise and sight. The built-in
  behaviors (`wander`, `aggressive`, `assist`, `flee`, `guard`, `follow`, `pursue`,
  `sentinel`, `scavenge`) feed `think`.
- **Items:** `wear`, `give`, `put`, `use`, container locks, stacking, `equip_affects`,
  encumbrance, and item-target skills.
- **Libraries:** `template-world/lib/quest.star` provides quests and the `quests` command;
  the tutorial uses it.
- **Earlier milestones:**
  - M4: SQLite persistence, checkpoints, reconciliation, backups and `--fresh`.
  - M3: telnet and `gups serve --dev` with auto-reload, `@trace` and `@eval`.
  - M2: the Starlark host and `gups test`.
  - M1: the core simulation.

Still to come:
- M6: TLS, WebSocket, GMCP, accounts and roles, instructor commands, deploys.
- M7: `gups docs`, `MIGRATING.md`, plugins.

`gups serve --prod`, `lint`, `docs`, `deploy`, `invite` and `import-roster` still exit with
status 3 ("not implemented").
