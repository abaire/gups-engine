# gups-engine

The GUPS text MUD engine. The design lives in [docs/SPEC.md](docs/SPEC.md); decisions made
where the spec is silent are logged in [docs/DECISIONS.md](docs/DECISIONS.md) until they are
folded back in.

## Overview

GUPS splits the work into three layers, each in its own language:

| Layer                        | Language                            | Written by                          | What it covers                                                                                                                                                                          |
|------------------------------|-------------------------------------|-------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| The engine (this repository) | Kotlin                              | engine maintainers                  | The pulse-driven simulation, combat, the tactical track, the reality bubble, persistence, telnet/TLS/web clients and GMCP, accounts and roles, and the `gups` tools.                    |
| Course worlds                | Starlark                            | builders (students and instructors) | Zones with rooms, mobs, items and quests; hooks and `think` AI; shared libraries; the rules module that sets combat and movement formulas. Sandboxed, with resource limits.             |
| Plugins                      | Kotlin `.jar`s against `plugin-api` | engine developers                   | New builtins and effect primitives for scripts, commands, GMCP packages and world-start hooks. Trusted code, pinned per world in `MODULE.gups`; see [docs/PLUGINS.md](docs/PLUGINS.md). |

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

### Setting up a local development world

`template-world/` is the pristine seed for course worlds and is checked by the engine's test suite on every commit. **Do not modify or run directly against `template-world/`**: doing so triggers warnings about placeholder `@CHANGE_ME_INSTRUCTOR` handles in `CODEOWNERS`, writes local databases into the repository tree, and risks altering the canonical template.

If you are an advanced student or developer exploring the engine locally without a released course world repository:

1. **Clone the template to a local world directory:**
   ```bash
   cp -r template-world local-world
   ```
   (`local-world/` is ignored by git in `.gitignore`, keeping your working tree clean.)

2. **Remove or clear `local-world/CODEOWNERS`:**
   In a released course world, `CODEOWNERS` is used on GitHub for PR review routing and in `--prod` to assign package scopes to accounts imported from a class roster. In a local development world, the database's `AccountStore` has no roster accounts, so any handles listed in `CODEOWNERS` will report a warning that they match no account.

   Remove the file (or comment out its rules) so the server starts without warnings:
   ```bash
   rm local-world/CODEOWNERS
   ```
   (In `--dev` mode, you automatically log in as the local instructor with unrestricted access across all packages, so builder scoping is not needed.)

   *Optional:* If you want to test `CODEOWNERS` package scoping or multi-user accounts locally, import a local roster first so an account with that GitHub handle exists in the database:
   ```bash
   printf 'login,github_handle,role\ninstructor,your-handle,instructor\n' > roster.csv
   gups import-roster roster.csv --world local-world
   ```

You can then pass `--world local-world` from the engine root, or `cd local-world` and run commands without `--world` (since `--world` defaults to the current directory).

**Play locally.** `--dev` is a single-user server on 127.0.0.1 with no passwords; you log in
as the local instructor. It reloads packages when their files change, and serves the API
reference at `http://127.0.0.1:4080/docs/`.

```bash
gups serve --dev --world local-world
```

In another terminal, connect with standard telnet:

```bash
telnet localhost 4000
```

Or connect with **Mudlet**:

1. Click **Connect** → **New Profile**.
2. Set **Server address** to `localhost` (or `127.0.0.1`) and **Port** to `4000` (or your `--port`).
3. Set the connection type to standard **Telnet** (leave SSL/TLS **unchecked**, as `--dev` serves unencrypted telnet).
4. Click **Connect**. When prompted `By what name are you known? `, enter any character name (e.g. `Instructor` or
   `Tess`). No password is required, and your character is granted the instructor role with `@eval` and `@trace`
   enabled. Mudlet automatically negotiates GMCP (handling room info, vitals, and comm channels out of the box).

`--start //zones/tutorial:gate` picks the room new characters start in, `--port` the telnet
port (default 4000), `--docs-port` the HTTP docs port (default 4080), and `--fresh` ignores
saved state and boots from the `.star` files. `--wipe` is a server wipe: like `--fresh`, and it
also deletes every character and item and records the world's current schema (after a `prewipe`
backup; accounts stay). World state is saved in `<world>/.gups/world.db`.

**Features, renames and messages.** `MODULE.gups` can turn off the engine features `combat`, `track`, `skills` and
`leveling` (`features = [...]`, fixed per database) and rename engine commands (`rename = {"cast": "invoke"}`);
`MESSAGES.gups` overrides engine messages by id. `samples/robot-world` uses all three.

**Test and check packages.** These are the same gates a course world's CI runs on each pull
request:

```bash
gups test --world local-world //zones/tutorial/...       # run *_test.star tests
gups lint --world local-world //...                      # static checks
gups fuzz --world local-world --pulses 1000 //zones/tutorial/...   # bot players explore
```

A failing fuzz run prints the exact command to rerun it and writes a repro bundle for
`gups repro`.

**Generate the API reference.** `gups docs` writes the offline HTML reference of the whole
Script API to `build/docs` (`--out DIR` to change it, `--world DIR` to include that world's
plugins). `gups docs --json` writes `api.json` instead.

**Web client and shared-server login.** The bundled browser client runs over HTTPS and a
WebSocket (`/ws`), rendering formatted text, input history (Up/Down arrows), and GMCP panels (vitals bars, room info,
active affects, and tactical track bands).

- **Testing against a local dev server:** Because the lightweight `--dev` profile only serves
  raw telnet and `/docs/`, use the `--prod` profile with a throwaway `--self-signed` certificate
  to test or develop with the web client locally:
  ```bash
  # 1. Create an instructor invite code:
  gups invite --role instructor --world local-world

  # 2. Start the local server with HTTPS on port 8443:
  gups serve --prod --world local-world --https 127.0.0.1:8443 --self-signed
  ```
  Open `https://localhost:8443/` in a browser, accept the browser's self-signed certificate warning,
  click **"Sign up with your code"**, enter the invite code, and choose an account name and password.

- **Running in production:** Start `--prod` with production TLS certificates (`--tls-cert` and
  `--tls-key`), or behind a TLS-terminating reverse proxy:
  ```bash
  # Import a roster CSV (login,github_handle,role) to generate setup codes:
  printf 'login,github_handle,role\nann,ann-gh,player\n' > roster.csv
  gups import-roster roster.csv --world local-world

  # Start with TLS telnet, HTTPS/WebSocket, and the admin port:
  gups serve --prod --world local-world \
    --telnet-tls :4443 \
    --https :443 \
    --tls-cert /etc/gups/fullchain.pem \
    --tls-key /etc/gups/privkey.pem \
    --admin 127.0.0.1:9090
  ```
  Players visit `https://<host>/` in any modern browser to log in or register with their setup
  or invite code. Mudlet players connect to port 4443 with SSL/TLS enabled, type `new`, and
  register with their setup code. Add `--telnet :4000` if you want to provide unencrypted telnet
  access alongside TLS.

**Other commands:**

| Command                                     | What it does                                                              |
|---------------------------------------------|---------------------------------------------------------------------------|
| `gups invite --role ROLE`                   | Creates an invite code for `player`, `builder`, `instructor`, or `admin`. |
| `gups import-roster roster.csv`             | Imports accounts from a roster CSV and prints initial setup codes.        |
| `gups backup`, `gups restore <backup-file>` | Back up the database, or restore it with the server stopped.              |
| `gups snapshot --sanitized --out FILE`      | A snapshot with personal data stripped, for `migrate-check`.              |
| `gups migrate-check --snapshot FILE`        | Checks migrations and reconciliation against a snapshot.                  |
| `gups deploy --commit SHA --admin ADDR`     | Deploys a course world commit to a running server (CI calls it).          |
| `gups repro <diagnostic-id or bundle.json>` | Replays a diagnostic in the harness.                                      |

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
2. **Configure it.** `MODULE.gups` sets the `api_version`, declares the world's stats, vitals,
   damage types and channels (the schema; the rules in `rules/combat.star` give them their
   meaning, and the engine has no built-in rules), sets the checkpoint cadence and
   `start_room`, and pins any plugins. A database is tied to its schema: changing a stat,
   vital, damage type or channel name later needs `gups serve --wipe`. In `CODEOWNERS`, replace `@CHANGE_ME_INSTRUCTOR`
   with
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

1. Install a JDK 21 or newer (any distribution). IntelliJ can download one under **File → Project Structure → SDKs → + →
   Download JDK**.
2. **File → Open…** and pick the `gups-engine` directory (the one containing
   `settings.gradle.kts`). Trust the project when asked, and let IntelliJ import it as a
   Gradle project. The first import downloads Gradle and the dependencies, which takes a
   few minutes.
3. Under **Settings → Build, Execution, Deployment → Build Tools → Gradle**, set **Gradle JVM** to your JDK 21, and
   **Build and run using** and **Run tests using** to **Gradle**, so the IDE builds exactly like CI.
4. Under **Settings → Plugins**, make sure the bundled **Kotlin** plugin is enabled.

Shared run configurations live in [`.run/`](.run) and appear in the run-configuration
dropdown automatically:

| Configuration                                                   | What it does                                                                                      |
|-----------------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| `gups CLI / gups serve --dev`                                   | Starts the server from source, debuggable. Edit the program arguments to point to `--world local-world` etc. |
| `gups CLI / gups test`, `gups lint`, `gups docs`, `gups --help` | Runs the other CLI tools from source.                                                             |
| `Gradle / check (all tests)`                                    | Everything CI runs, including the module-graph check.                                             |
| `Gradle / core tests`                                           | Just the `core` module's tests.                                                                   |
| `Gradle / installDist (gups launcher)`                          | Builds `cli/build/install/gups/bin/gups`, the launcher students get.                              |
| `Gradle / gups serve --dev (Gradle)`                            | Starts the server through Gradle's `run` task, as on the command line.                            |

Every configuration can be started with **Debug** as well as **Run**. To debug a single
test, click the gutter icon next to the test. For a new tool configuration, copy one of
the `gups CLI` entries and tick **Store as project file** so it lands in `.run/` and can
be committed.

`./gradlew :cli:test -Dgups.browserTest=true` also runs the headless Firefox test of the web
client, which otherwise runs only on Linux CI.

## Modules

| Module                   | Scope                                                             | May depend on    |
|--------------------------|-------------------------------------------------------------------|------------------|
| `core`                   | world model, scheduler, commands, combat, rules chain, bubble     | nothing          |
| `script`                 | Starlark host                                                     | `core`           |
| `persist`                | SQLite/WAL persistence                                            | `core`           |
| `net`                    | telnet, TLS, WebSocket, GMCP                                      | `core`           |
| `auth`                   | accounts, roles, invites                                          | `core`           |
| `harness`                | headless test world and runners                                   | `core`, `script` |
| `plugin-api`             | `.jar` plugin extension points                                    | `core`, `script` |
| `webclient`              | the browser client: plain JavaScript modules, served as resources | nothing          |
| `cli`                    | the `gups` binary; wires everything                               | anything         |
| `samples:weather-plugin` | the sample plugin                                                 | `plugin-api`     |

`./gradlew checkModuleGraph` (part of `check`) fails if a module declares a dependency
outside these rules. The allowed graph is defined in the root `build.gradle.kts`.

`docs/` holds `SPEC.md` (the design), `DECISIONS.md`, `DESIGN_NEEDED.md` (unbuilt features the spec doesn't
settle yet), `PLUGINS.md` (the plugin developer guide), and `CHANGELOG.md`.

`template-world/` is the pristine seed of the course world template. It holds `MODULE.gups` (world
metadata and schema), the `rules` package, the shared `lib` package, `zones/tutorial`, the
heavily commented reference zone whose tests run on every engine commit, and the
[World Builder's Guide](template-world/docs/tutorials/README.md). It should remain pristine; for
local development and experimentation, clone it to `local-world/` as described above.

`samples/robot-world/` is a second, complete course world with a different schema (integrity,
sanity, power and radiation instead of hit points, mana and movement). It is linted, tested and
fuzzed with the engine's own tests to show that nothing in the engine is fantasy-specific.

## Releasing

`docs/CHANGELOG.md` is plain Markdown with one `## api_version N` section per version, newest
first. Describe each script-facing change there, with before/after snippets for breaking ones.

A test diffs the current API against the released baseline `docs/api/api-N.json`. While
`EngineInfo.API_VERSION` is still `N`, any removed or changed symbol (doc-only edits excepted)
fails it, and added symbols must be marked `@ApiSince(N)`. A breaking change therefore needs
`EngineInfo.API_VERSION` raised.

Before tagging a release, run `gups docs --update-baseline` from the engine checkout, which
writes `docs/api/api-N.json`, and commit it. A release with only additions keeps `N` and just
reruns that command. Pushing a version tag builds the images,
the docs archive and the course world template, and attaches them to the GitHub release.
