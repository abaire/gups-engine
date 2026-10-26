# gups-engine

The GUPS text MUD engine. The design lives in [docs/SPEC.md](docs/SPEC.md); decisions made
where the spec is silent are logged in [docs/DECISIONS.md](docs/DECISIONS.md) until they are
folded back in.

## Overview

GUPS splits the work into three layers, each in its own language:

| Layer | Language | Written by | What it covers |
|---|---|---|---|
| The engine (this repository) | Kotlin | engine maintainers | The pulse-driven simulation, combat, the tactical track, the reality bubble, persistence, telnet/TLS/web clients and GMCP, accounts and roles, and the `gups` tools. |
| Course worlds | Starlark | builders (students and instructors) | Zones with rooms, mobs, items and quests; hooks and `think` AI; shared libraries; the rules module that sets combat and movement formulas. Sandboxed, with resource limits. |
| Plugins | Kotlin `.jar`s against `plugin-api` | engine developers | New builtins and effect primitives for scripts, commands, GMCP packages and world-start hooks. Trusted code, pinned per world in `MODULE.gups`; see [docs/PLUGINS.md](docs/PLUGINS.md). |

Builders are the main audience: they never touch Kotlin, and `gups docs` documents
everything they can call. Plugins are for mechanics a world needs that Starlark can't
express, and they reach the world only through the `PluginWorld` facade.

## Building

Requires JDK 21 or newer to run Gradle; the build compiles against a JDK 21 toolchain and
downloads one if it is missing.

```bash
./gradlew check                # all tests, code formatting (ktfmt), and module graph check
./gradlew ktfmtFormat          # reformat all Kotlin files with ktfmt (Google style)
./gradlew :cli:installDist     # builds the `gups` launcher
cli/build/install/gups/bin/gups --help
```

The examples below call the launcher `gups`. Put `cli/build/install/gups/bin` on your `PATH`,
or write the full path.

## Running with the CLI

Every subcommand takes `--world DIR` (default: the current directory) and `--help`.
`template-world/` is a complete world to try them on.

**Play locally.** `--dev` is a single-user server on 127.0.0.1 with no passwords; you log in
as the local instructor. It reloads packages when their files change, and serves the API
reference at `http://127.0.0.1:4080/docs/`.

```bash
gups serve --dev --world template-world      # then, in another terminal:
telnet localhost 4000
```

`--start //zones/tutorial:gate` picks the room new characters start in, `--port` the telnet
port, and `--fresh` ignores saved state and boots from the `.star` files. World state is saved
in `<world>/.gups/world.db`.

**Test and check packages.** These are the same gates a course world's CI runs on each pull
request:

```bash
gups test --world template-world //zones/tutorial/...       # run *_test.star tests
gups lint --world template-world //...                      # static checks
gups fuzz --world template-world --pulses 1000 //zones/tutorial/...   # bot players explore
```

A failing fuzz run prints the exact command to rerun it and writes a repro bundle for
`gups repro`.

**Generate the API reference.** `gups docs` writes the offline HTML reference of the whole
Script API to `build/docs` (`--out DIR` to change it, `--world DIR` to include that world's
plugins). `gups docs --json` writes `api.json` instead.

**Try the shared-server login.** Import a roster, then start `--prod` with a self-signed
certificate:

```bash
printf 'login,github_handle,role\nann,ann-gh,player\n' > roster.csv
gups import-roster roster.csv --world template-world   # prints ann's setup code
gups serve --prod --world template-world --telnet-tls 127.0.0.1:4443 --https 127.0.0.1:8443 --self-signed
```

Then connect Mudlet (or `openssl s_client -connect localhost:4443`) to port 4443 with TLS,
accept the self-signed certificate, type `new`, enter the setup code and choose a password.
Or open `https://localhost:8443/` in a browser, choose "Sign up with your code", and do the
same there. Add `--telnet 127.0.0.1:4000` to serve raw telnet as well.

**Other commands:**

| Command | What it does |
|---|---|
| `gups invite` | Creates an invite code. |
| `gups backup`, `gups restore` | Back up the database, or restore it with the server stopped. |
| `gups snapshot --sanitized --out FILE` | A snapshot with personal data stripped, for `migrate-check`. |
| `gups migrate-check --snapshot FILE` | Checks migrations and reconciliation against a snapshot. |
| `gups migrate-info <package>...` | Lists where packages use API symbols that changed since their `api_version`. |
| `gups deploy --commit SHA --admin ADDR` | Deploys a course world commit to a running server (CI calls it). |
| `gups repro <diagnostic-id or bundle.json>` | Replays a diagnostic in the harness. |

## Creating a course world

A course world is its own git repository, one per term, holding the zones, rules and
libraries students write. Every release ships `course-world-template-<version>.tar.gz`, a copy
of [`template-world/`](template-world) whose CI workflow is pinned to that release's `gups`
image. Build it from a checkout with `./gradlew :cli:courseWorldTemplateArchive` (it lands in
`cli/build/distributions/`).

1. **Unpack it and make it a repository:**
   ```bash
   tar -xzf course-world-template-<version>.tar.gz
   mv course-world-template world-2027 && cd world-2027
   git init && git add -A && git commit -m "Start the 2027 course world"
   ```
   Push it to GitHub. To reuse it each term, mark it as a template repository and create
   each term's world with **Use this template**.
2. **Configure it.** `MODULE.gups` sets the `api_version`, checkpoint cadence and
   `start_room`, and pins any plugins. In `CODEOWNERS`, replace `@CHANGE_ME_INSTRUCTOR` with
   the instructor's GitHub handle, and give each student team its zone:
   ```
   /zones/goblin_caves/ @student1 @student2
   ```
   At startup the server gives builders the packages CODEOWNERS assigns them.
3. **Add a zone.** A package is a directory with a `package.star` holding one `package()`
   call. Its other `.star` files hold the content, and `*_test.star` files hold its tests:
   ```python
   # zones/meadow/package.star
   package(api_version = 1, kind = "zone", title = "The Meadow")
   ```
   ```python
   # zones/meadow/rooms.star
   room("gate",
       name = "Meadow Gate",
       desc = "A stile leads over the wall into a meadow to the north.",
       flags = ["dead_end_ok"],
       exits = [exit("north", to = ":field")],
   )

   room("field",
       name = "Open Field",
       desc = "Tall grass sways in the wind. The stile is south.",
       flags = ["dead_end_ok"],
       exits = [exit("south", to = ":gate")],
   )
   ```
   ```python
   # zones/meadow/meadow_test.star
   def test_the_field_is_north_of_the_gate(t):
       w = t.world(seed = 1, packages = ["//zones/meadow"])
       p = w.spawn_player("Tess", at = ":gate")
       t.contains(p.do("north"), "Open Field")
   ```
   `zones/tutorial` is the commented reference zone: it shows mobs, items, doors, hooks,
   commands and quests, and each of its tests is a worked example.
4. **Try it:**
   ```bash
   gups test //...
   gups lint //...        # without dead_end_ok, it would flag both rooms as dead ends
   gups serve --dev --start //zones/meadow:gate
   ```

The template's own [README](template-world/README.md) covers the rest of the term: importing
the roster, running the production server, deploys, and the migration snapshot that CI
checks pull requests against. `gups docs` is the full reference for every declaration, hook
and `ctx` verb.

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

`./gradlew :cli:test -Dgups.browserTest=true` also runs the headless Firefox test of the web
client, which otherwise runs only on Linux CI.

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
| `webclient` | the browser client: plain JavaScript modules, served as resources | nothing |
| `cli` | the `gups` binary; wires everything | anything |
| `samples:weather-plugin` | the sample plugin | `plugin-api` |

`./gradlew checkModuleGraph` (part of `check`) fails if a module declares a dependency
outside these rules. The allowed graph is defined in the root `build.gradle.kts`.

`docs/` holds `SPEC.md` (the design), `DECISIONS.md`, `DESIGN_NEEDED.md` (unbuilt features the spec doesn't
settle yet), `PLUGINS.md` (the plugin developer guide), `CHANGELOG.md` and `MIGRATING.md`.

`template-world/` is the seed of the course world template. It holds `MODULE.gups` (world
metadata), the `rules` package, the shared `lib` package, and `zones/tutorial`, the heavily
commented reference zone whose tests run on every engine commit.

## Releasing

`docs/CHANGELOG.md` has one `## api_version N` section per version, newest first; breaking
changes collect under `## api_version N+1 (unreleased)`. Each change is an entry:

````markdown
### Renamed `ctx.act_move` to `ctx.walk`
- kind: renamed            # removed, renamed, signature, behavior or added
- symbol: ctx.act_move     # an api.json symbol
- replacement: ctx.walk    # required for renamed

One or more lines of summary.

```python before
ctx.act_move(ctx.self, "north")
```

```python after
ctx.walk(ctx.self, "north")
```
````

A test diffs the current API against the released baseline `docs/api/api-N.json` and fails
for any removed, added or changed symbol (doc-only edits excepted) that no entry covers.
Additions take `kind: added`, under the unreleased section or appended to
`## api_version N`.

Before tagging a release, if the changelog has an `(unreleased)` section, drop
` (unreleased)` from its heading and set `EngineInfo.API_VERSION` to its version. Then run
`gups docs --update-baseline` from the engine checkout, which writes `docs/api/api-N.json`,
`docs/api/changes-N.json` and `docs/MIGRATING.md`, and commit them. A release with only
additions keeps `N` and just reruns that command. Pushing a version tag builds the images,
the docs archive and the course world template, and attaches them to the GitHub release.
