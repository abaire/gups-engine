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
cli/build/install/gups/bin/gups lint --world template-world //...
cli/build/install/gups/bin/gups fuzz --world template-world --pulses 1000 //zones/tutorial/...
```

To try the shared-server login locally, import a roster, then start `--prod` on raw telnet:

```bash
printf 'login,github_handle,role\nann,ann-gh,player\n' > roster.csv
cli/build/install/gups/bin/gups import-roster roster.csv --world template-world   # prints ann's setup code
cli/build/install/gups/bin/gups serve --prod --world template-world --telnet-tls 127.0.0.1:4443 --https 127.0.0.1:8443 --self-signed
```

Then connect Mudlet (or `openssl s_client -connect localhost:4443`) to port 4443 with TLS,
accept the self-signed certificate, type `new`, enter the setup code and choose a password.
Or open `https://localhost:8443/` in a browser, choose "Sign up with your code", and do the
same there. Add
`--telnet 127.0.0.1:4000` to serve raw telnet as well.

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

`./gradlew checkModuleGraph` (part of `check`) fails if a module declares a dependency
outside these rules. The allowed graph is defined in the root `build.gradle.kts`.

`docs/` holds `SPEC.md`, `DECISIONS.md`, `DESIGN_NEEDED.md` (unbuilt features the spec
doesn't settle yet), `CHANGELOG.md` and `MIGRATING.md`.

`template-world/` is the seed of the course world template. It holds `MODULE.gups` (world
metadata), the `rules` package, the shared `lib` package, and `zones/tutorial`, the heavily
commented reference zone whose tests run on every engine commit.

## Status

M7.3 ([SPEC_MILESTONE7.md](docs/SPEC_MILESTONE7.md) §4): changelog and migration artifacts.

- **Changelog format:** `docs/CHANGELOG.md` has one `## api_version N` section per version, newest
  first; breaking changes collect under `## api_version N+1 (unreleased)`. Each change is an entry:
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
  `Changelog.parse` rejects anything else with `CHANGELOG.md:<line>: <reason>`.
- **Every API change needs an entry:** a CI test diffs the current model against the released
  baseline `docs/api/api-N.json` and fails for any removed, added or changed symbol (doc-only edits
  excepted) that no entry covers; additions take `kind: added`, under the unreleased section or
  appended to `## api_version N`. Released sections must still match `docs/api/changes-N.json`.
- **Generated artifacts:** `docs/MIGRATING.md` (breaking entries with their snippets, checked by a
  test) and `changes.json`, which `gups docs` ships next to `api.json`.
- **`gups migrate-info [--world DIR] [--changelog FILE] [--format text|json] <package>...`:** lists
  every place a package uses a symbol that changed since its `api_version`, with the entry and its
  snippets. It reads the syntax tree only, so it works on packages the engine no longer loads.
- **Releasing:** before tagging, if the changelog has an `(unreleased)` section, drop
  ` (unreleased)` from its heading and set `EngineInfo.API_VERSION` to its version. Then run
  `gups docs --update-baseline` (from the engine checkout; `--docs-dir DIR` points elsewhere), which
  writes `docs/api/api-N.json`, `docs/api/changes-N.json` and `docs/MIGRATING.md`, and commit them.
  A release with only additions keeps `N` and just reruns that command.

M7.2 ([SPEC_MILESTONE7.md](docs/SPEC_MILESTONE7.md) §3): the HTML API reference.

- **`gups docs [--out DIR] [--world DIR]`:** writes a static, offline HTML reference of the whole
  Script API (default `build/docs`): declarations and settings, hooks, `ctx` and views, effects and
  behaviors, rules, the test harness, plugins, the changelog, and `api.json`. Every page is tagged
  with the `api_version`, and every `api.json` symbol has an anchor.
- **Examples that can't rot:** `DocExamples` holds a short example for every declaration, constant,
  effect primitive, behavior and harness type; `DocExamplesTest` runs each one as a harness test.
- **Where to find it:** in the OCI image at `/opt/gups/docs/`, in the jlink image and `installDist`
  under `docs/`, as `gups-docs-<version>.zip` on each GitHub release, and served by the server at
  `/docs/` with no login: on the `--https` listener with `--prod`, and at
  `http://127.0.0.1:4080/docs/` with `--dev` (`--docs-port PORT` changes the port).

M7.1 ([SPEC_MILESTONE7.md](docs/SPEC_MILESTONE7.md) §1–§2): one description of the Script API.

- **Spec-driven declarations:** `DeclSpec` defines every package declaration (`package`, `room`,
  `exit`, `door`, `mob`, `item`, `skill`, `region`, `spawn`, `loot`, `container`,
  `global_command`, `struct`) plus `module` (`MODULE.gups`) and `server` (`server.gups`),
  driving both runtime argument parsing (with spelling suggestions) and the documentation model.
- **Complete API model:** `ScriptApi.model()` combines `DeclSpec`, `HookCatalog`, `CONSTANT_SPECS`,
  `RULES_EXPORTS`, `MODIFIER_POINTS`, `HarnessApi`, and `@StarlarkBuiltin`/`@StarlarkMethod`
  reflection into one validated model.
- **`api.json`:** `gups docs --json [--out DIR_OR_FILE.json]` writes a deterministic, sorted JSON dump
  of the entire Script API tagged with `api_version` and `engine_version`, with a `symbol` and a `since`
  on every entry. A golden copy in `cli/src/test/resources` catches unreviewed API changes.

M6.6 ([SPEC_MILESTONE6.md](docs/SPEC_MILESTONE6.md) §8): course world template and CI gates.

- **Static checks:** `gups lint [--base REF] <patterns>` runs static checks on rooms (dead ends, reverse exits,
  door symmetry), schema version bumps since `--base` (presentation and code exempt per Q13),
  attribute keys written but never read, catalog hook names, and regions.
- **Fuzz walker:** `gups fuzz [--pulses N] [--seed S] <patterns>` runs headless with fixed seeds,
  simulating bot players that explore zones, and asserts zero forbidden diagnostics or invariant
  violations. Fails with exact rerun command and repro bundle.
- **Migration check:** `gups snapshot --sanitized --out FILE [--admin ADDR]` and
  `gups migrate-check --snapshot FILE` verify PR compatibility against a production snapshot.
- **Course world template:** `template-world/` provides `MODULE.gups`, `CODEOWNERS`,
  `.github/workflows/gates.yml` and `README.md`. A release job stamps version pins and image tags.

M6.5 ([SPEC_MILESTONE6.md](docs/SPEC_MILESTONE6.md) §7): operations.

- **Admin port** (default `127.0.0.1:9090`, bearer token from `admin_token_file`):
  - `/healthz` and Prometheus `/metrics`.
  - `/deploy`, `/roster` for `gups import-roster --admin`, and a sanitized `/snapshot`.
- **Deploys:** `gups deploy [--commit SHA]` (from CI) or `@deploy` fetches the deploy branch.
  The commit is checked and loaded off the world thread, and only then swapped in, so a bad
  commit changes nothing. Afterwards the server's working tree is moved to it, and
  CODEOWNERS is re-read. `--prod` won't start on a working tree that differs from the
  commit the database was saved at, unless `--force-commit`.
- **Images:**
  - `./gradlew :cli:jlinkArchive` builds a self-contained `gups` with its own runtime.
  - CI builds one for every platform and attaches them to tagged releases.
  - Tags also publish `ghcr.io/<owner>/gups:<version>`, built from `docker/Dockerfile`.

M6.4: the web client.

- **HTTPS:** `--https` serves the bundled client, with the same certificate as telnet over
  TLS. The client is plain JavaScript modules with no build step. It shows the game text in
  color, vitals bars, affects, and who is in melee, near and far.
- **Logins:** `POST /api/login` checks the password, rate-limited like telnet, and issues a
  30-day session token in a secure cookie. Logout, password changes and `@role revoke` end
  it.
- **Sign-up:** students can redeem their roster setup code or an invite code in the browser
  to choose their password, instead of over telnet.
- **WebSocket:** `/ws` plays with the same login state machine as telnet, starting at
  character selection.

M6.3: TLS and GMCP.

- **Listeners:** both run on Netty. `--prod` serves telnet over TLS (`--telnet-tls`, TLS 1.2
  and 1.3) and, only when asked, raw telnet.
- **Certificates:** PEM files that a renewal tool such as certbot can replace; the server
  picks them up within the hour, without a restart. `--self-signed` makes a throwaway one for
  local testing.
- **GMCP:** clients that send `Core.Supports` get `Char.Vitals`, `Char.Status`,
  `Char.Affects`, `Room.Info` and `Room.Bands` whenever they change. They also get
  `Comm.Channel.Text` with each message and, for builders tracing, `Gups.Trace`.

M6.2: roles and in-game tools.

- **Builder scope:** `CODEOWNERS` in the course world gives builders their packages at
  startup, and `@role` can add packages it doesn't cover. Builders' `@goto`, `@stat`,
  `@spawn` (debug mobs and items, never saved), `@where`, `@trace` and `@errors` work only
  on their own packages.
- **Instructor commands:** `@role`, `@invite`, `@reload` (`--dev` only), `@quarantine`,
  `@unquarantine`, `@bubble`, `@stats`, `@who`, `@snapshot`, `@shutdown` and
  `@audit item`. `@deploy` waits for M6.5.
- **Restrictions:** `@restrict`, `@unrestrict` and `@restrictions` take a capability away
  from one account for a while or for good, escalating along `restriction_ladder`.
- **Alerts:** script errors reach the package's online builders; quarantines, checkpoint
  backlog, bursts of engine errors and pulse overruns reach online instructors.

M6.1: accounts and login.

- **Accounts:** stored in SQLite, written through the checkpoint writer. Each account has one
  role. Passwords are Argon2id hashes.
- **Logins:** failed logins are rate-limited per account and per IP.
- **Creating accounts:** `gups import-roster` creates accounts and prints a one-time setup
  code for each. `gups invite` makes invite codes.
- **`gups serve --prod`:** students log in over raw telnet, set their passwords, and pick or
  create characters, one at a time. `--dev` keeps its passwordless local instructor, now as an
  `instructor` account.

M5: the world runs on rules builders can change.

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
- M7.3–M7.5: the structured changelog, `MIGRATING.md` and the API-change check, plugins.
