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

To try the shared-server login locally, import a roster, then start `--prod` on raw telnet:

```bash
printf 'login,github_handle,role\nann,ann-gh,player\n' > roster.csv
cli/build/install/gups/bin/gups import-roster roster.csv --world template-world   # prints ann's setup code
cli/build/install/gups/bin/gups serve --prod --world template-world --telnet-tls 127.0.0.1:4443 --self-signed
```

Then connect Mudlet (or `openssl s_client -connect localhost:4443`) to port 4443 with TLS,
accept the self-signed certificate, type `new`, enter the setup code and choose a password.
Add `--telnet 127.0.0.1:4000` to serve raw telnet as well.

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

M6.3 ([SPEC_MILESTONE6.md](docs/SPEC_MILESTONE6.md) §5): TLS and GMCP.

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
- M6.4–M6.6: WebSocket and the web client, the admin port and deploys, and the course world
  CI gates.
- M7: `gups docs`, `MIGRATING.md`, plugins.

`lint`, `docs` and `deploy` still exit with status 3 ("not implemented").
