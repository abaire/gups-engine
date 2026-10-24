# gups-engine

The GUPS text MUD engine. The design lives in [SPEC.md](SPEC.md).

## Building

Requires JDK 21 or newer to run Gradle; the build compiles against a JDK 21 toolchain and
downloads one if it is missing.

```bash
./gradlew check                # all tests, plus the module graph check
./gradlew :cli:installDist     # builds the `gups` launcher
cli/build/install/gups/bin/gups --help
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

`docs/` holds `CHANGELOG.md` and `MIGRATING.md`.

## Status

M0 (SPEC §7.4): build skeleton, module graph, `gups` CLI stub, and the CI matrix. Every
`gups` subcommand parses its final options but exits with status 3 ("not implemented").
