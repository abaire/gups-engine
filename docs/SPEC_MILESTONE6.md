# Milestone 6 — Design

Status: draft for review. Open questions Q1–Q14 are settled; the other proposals await
review.

This document extends [SPEC.md](SPEC.md) §7.4, where M6 is one row: "`auth`,
TLS telnet, WebSocket with the web client, GMCP, roles, CODEOWNERS sync, deploy pipeline,
metrics, admin commands. Load test. Course world template and CI gates."

That row is too large to build and review as one change. This document splits it into six
stages, each small enough to land and review on its own, and settles the details SPEC.md
leaves open. Where SPEC.md already decides something, this document only points to it. Where
it doesn't, the design here is a **proposal**: once accepted, it is as binding as SPEC.md. Each
stage ends with the questions that were settled for it, numbered Q1–Q14.

The usual rules still apply. Choices made while implementing that neither document settles go
in [DECISIONS.md](DECISIONS.md), and features this design leaves out go in
[DESIGN_NEEDED.md](DESIGN_NEEDED.md).

---

## 1. Stages at a Glance

| Stage | Scope | Depends on | Done when |
|---|---|---|---|
| **M6.1** Accounts and login | `auth` module, account storage, telnet login, character select and creation, invites, roster import, the `--prod` profile over raw telnet | — | A student logs in to a local `--prod` server with a roster account |
| **M6.2** Roles and in-game tools | Builder scope, CODEOWNERS parsing, builder and instructor `@` commands, per-account restrictions, owner and instructor alerts | M6.1 | An instructor can run every §4.9 command; builders are confined to their packages |
| **M6.3** TLS and GMCP | Telnet over TLS, server certificates, the GMCP packages of §4.10, per-pulse GMCP deltas | M6.1 | Mudlet connects over TLS and shows live vitals |
| **M6.4** WebSocket and web client | HTTPS + WSS listener, session tokens, the bundled JavaScript client | M6.3 | A student plays from a browser |
| **M6.5** Operations | Admin port, metrics, the deploy pipeline, OCI and `jlink` images | M6.2 | CI deploys a merged commit to a running server |
| **M6.6** Course world and CI gates | Course world template repository, `gups lint`, fuzz walker, migration check, `gups snapshot`, CI workflow | — (M6.5 for `GET /snapshot`) | A PR to the template runs every §7.3 gate in about 2 minutes |

M6.6 needs nothing from the other stages except the production snapshot for its migration
check, so it can be built alongside M6.1–M6.5.

---

## 2. Cross-Cutting Design

### 2.1 Server configuration

SPEC.md has `MODULE.gups` for world settings (§2.3) and CLI flags (§4.13), but production also
needs ports, certificate paths, secrets and the course repository's address.

**Proposal:**
- **File:** `gups serve --prod --config FILE` reads a server configuration file. It is
  Starlark with one `server(...)` call, like `MODULE.gups` (D-107), so instructors learn one
  syntax.
- **Location:** the file lives outside the course world repository, because it holds paths
  and settings for one machine.
- **Secrets:** they never go in the file. The admin token and the deploy key are read from
  files the configuration names, or from environment variables
  (`GUPS_ADMIN_TOKEN_FILE`, `GUPS_DEPLOY_KEY_FILE`).
- **Flags:** every setting also has a CLI flag, and a flag overrides the file.

```python
server(
    world = "/srv/gups/world",              # a git working tree of the course repository
    db = "/srv/gups/world.db",
    telnet_tls = ":4443",
    https = ":443",                          # web client and WebSocket
    telnet = None,                           # raw telnet off in --prod; e.g. ":4000" to enable
    admin = "127.0.0.1:9090",
    tls_cert = "/etc/gups/fullchain.pem",
    tls_key = "/etc/gups/privkey.pem",
    admin_token_file = "/etc/gups/admin.token",
    repository = "git@github.com:course/world-2027.git",
    deploy_branch = "main",
    deploy_key_file = "/etc/gups/deploy_key",
    open_signup = False,                     # §1.7: designed for, disabled by default
    max_characters = 5,                      # per account; instructors are unlimited
    restriction_ladder = ["1d", "7d", "permanent"],  # §4.4
)
```

### 2.2 Slow work stays off the world thread

Argon2id at m = 64 MiB, t = 3 (§5.12) takes roughly 100–300 ms per hash. Fetching a git commit
takes seconds. Neither may run on the world thread (§2.5).

**Proposal:**
- **Pattern:** the world thread starts the work on a bounded executor, `Dispatchers.IO`
  limited to 4 threads, and the result comes back as a `WorldControlEvent`. That is the same
  route network input takes.
- **Login:** `LoginHandler` (D-082) becomes a small state machine per session. While a
  password check is pending, the session's input is held.
- **New events:** `WorldControlEvent` gains `AuthResult(session, outcome)` and
  `DeployStaged(commit, result)`.

### 2.3 Who may write SQLite

§2.5 makes the checkpoint writer the only SQLite writer. Account changes are rare, small and
must survive a crash, but they aren't world state.

**Proposal:**
- **Writing:** account, token and invite changes are submitted to the same writer as small
  batches of their own, written at once rather than held for the next checkpoint. The writer
  is still the only writer, and FIFO order is kept.
- **Reading:** account reads (login, `@role`) go to a separate read-only connection off the
  world thread. WAL allows that.
- **Seam:** `Persistence` (D-093) gains an `AccountStore` interface in `core`. `persist`
  implements it, and `auth` uses it, so `auth` still depends only on `core` (§2.2).

### 2.4 GMCP and JSON in `core`

`core` deliberately has no kotlinx.serialization dependency (D-055), but §4.11 gives
`OutputSink.gmcp(pkg, json: JsonElement)`.

**Proposal:** `core` builds GMCP payloads as plain values (`String`, `Long`, `Boolean`, `List`,
`Map<String, *>`, `null`), and `net` encodes them. The seam becomes
`OutputSink.gmcp(pkg: String, data: Any?)`.

### 2.5 Listener stack

The raw telnet listener uses `ktor-network` (D-090). That library has no server-side TLS, and
§1.5 names Ktor/Netty.

**Decision (Q1):**
- **Listeners:** all three run on Netty.
  - Raw telnet and telnet over TLS share one pipeline, with an optional `SslHandler` in front
    of the telnet codec.
  - HTTPS and WSS run on Ktor's Netty engine.
- **Telnet logic:** `TelnetConnection` (option negotiation, line framing, buffering) is kept
  unchanged behind a byte-in, byte-out interface. Only the socket layer moves.
- **Status:** this supersedes D-090, whose entry is updated when M6.3 lands.

---

## 3. M6.1 — Accounts and Login

**Goal:** real accounts, password login over telnet, and the `--prod` profile, still on raw
telnet so that this stage needs no TLS.

### 3.1 Accounts (§3.5, §3.6)

- **Account fields:** the `accounts` table already exists. An account has one role. The
  higher roles imply the lower (§3.5), so a set is redundant. `roles_json` stores
  `{"role": "BUILDER", "scope": [...]}`, where the scope is filled by CODEOWNERS sync (M6.2).
- **Logins:** 3–24 characters from `[a-z0-9_.-]`, unique case-insensitively.
- **Character names:** 2–16 letters, unique across all accounts. The existing
  `players.name UNIQUE` enforces that.
- **Reserved names:** checked against a built-in list plus `reserved_names` in the server
  configuration. The built-in list covers engine words (`self`, `me`, `all`, `someone`), the
  standard directions and the command names.
- **Characters per account:** at most 5 by default (`max_characters` in the server
  configuration). Instructors and admins have no limit.
- **Disabled accounts:** they can't log in, and a disabled account's characters that are
  online are quit.

### 3.2 Passwords and rate limiting (§5.12)

- **Hashing:** Argon2id, m = 64 MiB, t = 3, p = 1, with a 16-byte salt and a 32-byte hash,
  stored as the standard PHC string `$argon2id$v=19$m=65536,t=3,p=1$…`.
  - The library is Bouncy Castle's pure-Java `Argon2BytesGenerator`. It needs no native code,
    so the `jlink` images stay simple.
- **Password rules:** at least 10 characters, and not equal to the login.
- **Rate limiting:**
  - Failures are counted per account and per IP. Five failures within 15 minutes start a
    lockout of 1 minute, doubling with each further failure up to 15 minutes. The counts reset
    after 15 minutes without a failure.
  - A locked-out attempt is refused without hashing, so an attacker can't spend the server's
    CPU.
  - The counts live in memory only. A restart clears them.
- **Wrong login names:** a login that doesn't exist still costs one hash of a fixed dummy
  value, so response time doesn't reveal which logins exist.

### 3.3 Creating accounts

- **`gups import-roster roster.csv`:**
  - **Format:** CSV with a header row and the columns `login,github_handle,role`. Only
    `login` is required, and `role` defaults to `player`.
  - **New logins** become accounts without a password, each with a **setup code**: a
    one-time, 7-day code that is an invite bound to that account.
  - **Output:** the command writes `login,setup_code` to standard output for the instructor to
    hand out.
  - **Existing logins** have their `github_handle` and role updated. Their passwords are left
    alone.
  - **Running it:** it talks to a running server through the admin port, or works directly on
    the database while the server is stopped.
- **`gups invite --role R [--days N]`** and **`@invite <role>`** make an unbound invite code
  (default 7 days). Only its hash is stored (§3.6 `invites`).
- **Codes:** 10 characters from an unambiguous alphabet, grouped as `XXXXX-XXXXX`.
- **Open signup:** with `open_signup = True`, a new login can be created at the login prompt
  without a code. It is off by default (§1.7).

### 3.4 The telnet login flow

```
By what name do you wish to be known? (login, or "new" with an invite code)
> ann
Password: (echo off)
> ********
Your characters: Ann, Tilly.  Type a name to play, "new" to create, or "password" to change yours.
> ann
Welcome back, Ann.
```

- **Hiding passwords:** the server sends `IAC WILL ECHO` before the password prompt and
  `IAC WONT ECHO` after it. Clients that refuse simply see their typing.
- **`new`** asks for an invite or setup code, then a login (unless the code is bound to an
  account), then a password twice.
- **Character creation** asks for a name, then places the character in `start_room` (from
  `MODULE.gups`, defaulting to the tutorial gate as in `--dev`), with the starting stats of
  D-082. Rules-defined starting stats remain in DESIGN_NEEDED.md.
- **A character already playing** is taken over, and the old connection is told why and
  closed (as D-082).
- **One character at a time:** an account plays one character at a time, across every
  transport (Q9). Choosing a different character while one is in the world (link-dead
  included) is refused with "You're already playing Ann." Instructors are bound by this too.
- **Timeouts:** 60 seconds idle at any prompt closes the connection. Three wrong passwords on
  one connection close it.
- **`--dev`** keeps its passwordless local instructor (§2.4), but now as a real account,
  `instructor`, created on first boot and refusing non-loopback connections.

### 3.5 The `--prod` profile (§2.4)

- **Login:** the auth login above replaces `DevLogin`.
- **Off in production:** `@eval` (§2.4), auto-reload and the file watcher.
- **Bubble:** on.
- **Invariants:** the §3.8 check runs at every world tick before capture (§6.5, D-102), not
  every pulse.
- **Listeners in this stage:** only raw telnet, on the configured address, since TLS arrives
  in M6.3.
- **Idle quit:** applies to everyone below instructor (§5.12).

### 3.6 Tests

- Argon2 parameters and PHC round trip.
- Lockout timing per account and per IP, under a virtual clock.
- Dummy hashing for unknown logins.
- Roster import is idempotent, and setup codes are single-use and expire.
- The whole telnet login flow against a headless world: wrong passwords, takeover, creation,
  reserved names, the character limit.
- Hashing never runs on the world thread: a test with a hash function that blocks for 1 s
  keeps pulsing.
- A crash right after an account change keeps the change.

### 3.7 Settled questions

- **Q2 (settled).** Roster students get one-time setup codes and choose their own password
  (§3.3). The server never hands out a usable password.
- **Q3 (settled).** Up to 5 characters per account by default, configurable; instructors are
  unlimited (§3.1).
- **Q4 (settled).** No self-service reset. `@role <account> reset` clears the password,
  revokes the account's web tokens, and shows the instructor a new setup code to hand over
  (§4.3). Players who know their password change it with `password` at the character menu.

---

## 4. M6.2 — Roles and In-Game Tools

**Goal:** builder scope from CODEOWNERS, and every builder and instructor command of §4.9.

### 4.1 CODEOWNERS sync (§3.5, §5.10)

- **When:** the server reads `CODEOWNERS` from the world at startup and on every deploy.
- **Syntax:** GitHub's — a path pattern, then owners.
- **Mapping:** a pattern maps to every package whose directory it matches. For example,
  `/zones/forest/` owns `zones/forest` and the packages below it.
- **Scope:** an account whose `github_handle` matches an owner gets those packages in its
  builder scope.
- **Teams:** team owners (`@org/team`) aren't supported in v0. The sync reports them as
  warnings.
- **Instructors and admins:** they are never scoped.
- **Effect on roles:** a sync never demotes or promotes roles. It only replaces scopes.
  `@role` sets roles.

### 4.2 Builder commands (§4.9)

All of them are refused outside the builder's scope ("That isn't one of your packages.").
Instructors may use them anywhere.

| Command | Design |
|---|---|
| `@goto <label or player>` | A room label in the builder's packages, or any player's name (Q6). It teleports through `teleport`, so hooks fire. Going to a player can be taken away per account (§4.4). |
| `@stat <target>` | A keyword in the room, `here`, or `#id`. It shows the full state: stats, affects, timers, attributes by namespace, location, simulation reasons and dormancy. |
| `@spawn <label> [count]` | Mobs and items of the builder's packages (Q5). Both are flagged `debug` and never persisted (§3.3). A debug mob despawns when its spawner logs out, or after 30 minutes. Everything it drops (inventory, loot, corpse) is `debug` too, and killing it awards no XP, so debug spawns can't leak into the real economy. |
| `@where` | Your room, track index, and each actor's and item's index and band, as numbers (§5.5). |
| `@trace …` | As D-091, but builders see only their own packages' events and their own character's rules lines (§5.13). |
| `@errors [package]` | As D-091, limited to the builder's packages. |

### 4.3 Instructor commands (§4.9)

| Command | Design |
|---|---|
| `@reload <label…>` | Package labels. It is refused in `--prod` with a pointer to `@deploy` (§1.4). In `--dev` it is a manual auto-reload. |
| `@deploy [commit]` | Deploys the tip of `deploy_branch`, or a given commit, through the M6.5 pipeline. It reports progress in game. |
| `@quarantine <pkg>` / `@unquarantine <pkg>` | Call `StarlarkHost.quarantine` and `unquarantine` (D-068). The state persists (§6.3). |
| `@bubble` | Active rooms by zone, anchors with their reasons, active entities outside the bubble, and dormant-room counts. |
| `@stats` | Pulse p50/p99 over the last minute, overruns, input queue depth, bubble size, sessions by transport, checkpoint backlog. These are the same numbers as `/metrics` (M6.5). |
| `@who` | Players with IP, transport, idle time, link-dead state and role (§4.9). |
| `@snapshot` | A `manual` backup (D-097) taken off the world thread. |
| `@shutdown [delay] [reason]` | The delay in seconds, default 0. Countdown warnings at 60, 30, 10 and 5 seconds, then the final checkpoint (D-010). |
| `@role <account> <role> [scope]` | Sets the role. Scope can only be set by hand for packages CODEOWNERS doesn't cover. `@role <account> reset` clears the password, revokes web tokens and shows a new setup code (Q4); `@role <account> revoke` revokes web tokens only. |
| `@invite <role>` | As §3.3. |
| `@audit item <id>` | The item's `item_events` history, newest last, with wall times. |

### 4.4 Restrictions

Instructors can take a capability away from one account, for a while or for good, without
changing its role (Q6).

- **Capabilities:** `goto_player`, plus the name of any builder command (`goto`, `spawn`,
  `stat`, `where`, `trace`, `errors`) and `gossip`. The list grows as commands are added.
- **Commands:**

  | Command | Design |
  |---|---|
  | `@restrict <account> <capability> [duration] [reason]` | Adds a restriction. `duration` is `30m`, `1d`, `7d` or `permanent`. Without one, the next step of the ladder below applies. |
  | `@unrestrict <account> <capability>` | Lifts the active restriction, permanent or not. The history stays. |
  | `@restrictions [account]` | Active restrictions, or one account's full history with who set each, when and why. |

- **Escalation ladder:** each new restriction of the same capability on the same account
  without an explicit duration takes the next step of `restriction_ladder` in the server
  configuration. The default is `["1d", "7d", "permanent"]`, so the first offence is a day
  and the third is permanent until an instructor lifts it. Lifted and expired restrictions
  still count toward the ladder.
- **Effect:** a restricted player who tries the capability is told "You can't do that right
  now." and, for timed restrictions, when it ends. The command still exists for them, unlike a
  missing role.
- **Storage:** a new `restrictions` table (`account_id`, `capability`, `until` (wall time, or
  null for permanent), `reason`, `set_by`, `created_at`, `lifted_by`, `lifted_at`), added by
  schema migration 2 and written through the checkpoint writer like other account changes
  (§2.3). Restrictions use wall-clock time, like session tokens, so they run out while the
  server is down.
- **Scope:** instructors and admins can't be restricted.

### 4.5 Alerts

- **Owner alerts (§6.2):** a script error sends a one-line alert to the package's online
  owners, at most one per package per 10 seconds. This finishes D-069.
- **Instructor alerts:** every `StarlarkHost.alerts` message reaches online instructors and
  the server log. That covers quarantine, rules fallback (D-108), checkpoint backlog (D-096),
  more than 100 engine exceptions in a minute (§6.4), and pulse overruns.
- **Pulse overruns (§5.1):** when more than 10% of pulses in a rolling minute overrun, the
  alert names the package that used the most script steps in that minute. This needs a
  per-package step count, which M6.5's metrics also use.
- **Spam limit:** each alert kind is repeated at most once a minute.

### 4.6 Tests

- CODEOWNERS parsing, including precedence (the last matching pattern wins, as on GitHub).
- Scope enforcement for every builder command.
- Restrictions: timed expiry under a virtual wall clock, the escalation ladder, lifting,
  history, and survival across a restart.
- Each instructor command against a headless world.
- Alert rate limits under a virtual clock.
- The overrun attribution picks the right package.

### 4.7 Settled questions

- **Q5 (settled).** Builders may `@spawn` both mobs and items of their own packages, all
  flagged `debug` (§4.2). §4.9's "`debug` items only" is read as "only debug entities".
- **Q6 (settled).** Builders may `@goto` any player by default. Instructors can take that
  away per account, for a time or permanently, with an escalation ladder (§4.4).

---

## 5. M6.3 — TLS and GMCP

**Goal:** encrypted telnet and the GMCP packages of §4.10.

### 5.1 TLS

- **Listener:** telnet over TLS on `:4443` (§4.10), on the Netty listener of §2.5.
- **Protocol:** TLS 1.2 and 1.3 only, with the JDK's default cipher suites.
- **Certificates:** PEM files (`tls_cert`, `tls_key`), reloaded when they change on disk,
  checked every hour. That way an external ACME client such as certbot can renew them
  without a restart. The engine doesn't do ACME itself.
- **Testing:** `gups serve --prod --self-signed` generates a throwaway certificate for local
  testing.

### 5.2 GMCP messages

The client sends `Core.Hello` and `Core.Supports.Set`/`Add`/`Remove`, and the server sends
only the packages a client supports. Field names are lowercase.

| Package | Payload | Sent |
|---|---|---|
| `Char.Vitals` | `{"hp", "maxhp", "mana", "maxmana", "move", "maxmove"}` | When any field changed this pulse |
| `Char.Status` | `{"name", "level", "xp", "xp_next", "wait", "casting": {"skill", "progress"} or null, "initiative" or null, "fighting": [names]}` | When changed. `progress` is 0–100. |
| `Char.Affects` | `[{"name", "remaining", "flags"}]`, with `remaining` in seconds | When the set or a whole second of time left changed |
| `Room.Info` | `{"label", "name", "exits": {dir: "open"/"closed"/"locked"/null}, "regions"}` | On entering a room, and when a door here changes |
| `Room.Bands` | `{"melee": [...], "near": [...], "far": [...]}`, each entry `{"id", "name", "kind": "actor"/"item"}` | When anything in the room moves, arrives or leaves (§4.10, from the viewer's perspective) |
| `Comm.Channel.Text` | `{"channel": "say"/"tell"/"whisper"/"shout"/"gossip", "talker", "text"}` | With each message |
| `Gups.Trace` | `{"line"}` | Builders with `@trace` on, instead of the `[trace]` text lines when supported |

- **When it's sent:** GMCP is computed in phase 7 (§5.1). A per-session cache of the last
  payload sent makes each message a delta, so unchanged packages send nothing.
- **Seam:** `OutputSink.gmcp(pkg, data)`, as §2.4.
- **Input:** GMCP from the client other than `Core.*` is ignored in v0.

### 5.3 Tests

- Byte-level negotiation transcripts for Mudlet and TinTin++ over TLS, recorded as in §7.1.
- Delta suppression.
- `Room.Bands` from each viewer's index.
- A client that never sends `Core.Supports` gets no GMCP.
- Certificate hot reload.

### 5.4 Settled questions

- **Q1 (settled).** All listeners move to Netty (§2.5).
- **Q7 (settled).** `Room.Bands` entries carry entity ids, only ever for the viewer's own
  room, so clients can key their band view and tell identical mobs apart.

---

## 6. M6.4 — WebSocket and Web Client

**Goal:** play from a browser over `wss://host/ws`, with the client served from
`https://host/` (§4.10).

### 6.1 HTTPS endpoints

| Endpoint | Purpose |
|---|---|
| `GET /` | The bundled client (the `webclient` module's resources). |
| `POST /api/login` | `{"login", "password"}` returns `{"token", "expires_at"}` and sets an `HttpOnly; Secure; SameSite=Strict` cookie. It is rate-limited like telnet (§3.2). |
| `POST /api/logout` | Revokes the token. |
| `GET /ws` | WebSocket upgrade. It authenticates with the cookie, or with a first frame `{"t":"auth","d":token}`. |

- **Tokens:** 30 days (§5.12), stored as SHA-256 hashes in `session_tokens`. They are revoked
  on logout, on a password change, and with `@role <account> revoke`.
- **New frame type:** `auth` is added to §4.10's frame types. The server answers with
  `{"t":"error","d":"..."}` before closing an unauthenticated socket.
- **After authentication:** the socket starts at character selection, using the same login
  state machine as telnet, so the two flows can't drift apart.

### 6.2 The client

- **Code:** plain JavaScript as native ES modules, plus one CSS file, with no framework, no
  npm packages and no build step (Q8). This replaces the "TypeScript" of SPEC.md §2.2:
  checking TypeScript needs a JavaScript runtime such as Node, which the engine won't depend
  on. JSDoc comments document the message shapes.
- **Contents:**
  - An output pane with ANSI-like color from the `{red}` markup (D-087), rendered as HTML.
  - An input line with history.
  - Vitals bars and an affects list from GMCP.
  - A band view (`Room.Bands`) showing who is in melee, near and far.
- **Packaging:** the `webclient` module packages the files unchanged into the jar as
  resources, as SPEC.md §2.2 already says. The server sends them with long cache lifetimes and
  a content hash in each URL, so a new engine version is never mixed with an old cached file.
- **Browsers:** current Chrome, Firefox and Safari.

### 6.3 Tests

- HTTP login and lockout.
- Token expiry and revocation close live sockets.
- A frame-level session through the WebSocket.
- A headless browser test on Linux CI only, using Selenium's Java bindings with headless
  Firefox (no Node), that logs in, moves and sees vitals.

### 6.4 Settled questions

- **Q8 (settled).** No Node anywhere in the build or tests: the client is plain JavaScript
  modules served as written (§6.2). TypeScript can be revisited if a type checker becomes
  available that doesn't need a JavaScript runtime.
- **Q9 (settled).** One character in play per account, on every transport (§3.4).

---

## 7. M6.5 — Operations

**Goal:** the admin port and metrics (§4.14), the production deploy pipeline (§5.10), and the
release images (§2.2).

### 7.1 The admin port (§4.14)

- **Server:** HTTP on `admin` (default `127.0.0.1:9090`), served by Ktor.
- **Authentication:** every endpoint needs `Authorization: Bearer <admin token>` except
  `GET /healthz`. The token is compared in constant time.

| Endpoint | Design |
|---|---|
| `GET /healthz` | `200 {"pulse", "uptime_s", "writer_healthy"}`. `503` if the world thread hasn't pulsed for 5 s or the writer is unhealthy. |
| `GET /metrics` | Prometheus text from Micrometer, with the §4.14 names. |
| `POST /deploy` | `{"commit"}`, or `{}` for the fetched tip of `deploy_branch` (Q11). It streams NDJSON progress lines and ends with `{"ok": bool, "commit", "packages": [...], "errors": [...]}`, naming the commit it deployed. |
| `POST /roster` | The body of a roster CSV, for `gups import-roster` against a running server (§3.3). |
| `GET /snapshot` | A sanitized copy of the database for the CI migration check (§8.4). |

- **Metrics:** the world thread updates them with cheap counters and histograms. A
  per-package step counter feeds `gups_script_steps_total{package}` and the overrun
  attribution of §4.5.

### 7.2 The deploy pipeline (§5.10)

1. **Trigger:** `gups deploy [--commit SHA]` (from CI, via the admin port) or
   `@deploy [commit]`. Without a commit, the fetched tip of `deploy_branch` is deployed (Q11).
   - The server refuses a deploy while another one runs.
   - It also refuses a commit that isn't on `deploy_branch`.
2. **Staging** (off the world thread):
   - `git fetch` with the deploy key (through `GIT_SSH_COMMAND`), then `git worktree add` of
     the commit into a staging directory. The engine shells out to the `git` binary (Q10);
     `--prod` refuses to start without it, and the OCI image includes it.
   - The changed packages are computed as `git diff --name-only <world_commit>..<commit>`,
     mapped to packages.
   - A change to `MODULE.gups` refuses the deploy: an engine upgrade needs a restart (§5.10).
3. **Pre-deploy snapshot:** a `predeploy` backup (D-097).
4. **Loading:** the staged tree is loaded and checked as one batch: "all succeed or none are
   swapped" (§5.10). This is the M3 reload (D-081), pointed at the staging directory instead
   of the live one.
5. **Swapping** (world thread, through `DeployStaged`):
   - The prototypes swap and live instances reconcile, as in D-080.
   - Only then is the live working tree moved to the commit, with `git checkout --detach`.
   - `meta.world_commit` is set.
   - CODEOWNERS is synced (§4.1).
   - Quarantine is cleared for the changed packages (§5.10 step 5).
   - A checkpoint is taken.
6. **Failure:** the world doesn't change, and the errors go to the deploy response, online
   instructors and `@errors` (§6.4).
7. **Startup:** the server refuses to start if the working tree isn't at `meta.world_commit`,
   unless `--force-commit` is given. That stops an unnoticed manual edit on the server.

### 7.3 Images (§2.2)

- **`jlink` runtime images** for `linux-x64`, `linux-arm64` and `macos-arm64`, built by the
  CI matrix and attached to GitHub releases.
- **OCI image:** `ghcr.io/<org>/gups:<version>`, based on a distroless or slim JRE image with
  `git` added. Its volumes are `/world`, `/data` (the database and backups) and `/etc/gups`.
  Its healthcheck calls `/healthz`.

### 7.4 Tests

- Admin auth, and constant-time comparison.
- Metric names exist after one pulse.
- Deploy end to end against a local bare repository:
  - success;
  - a load failure leaves the world untouched;
  - concurrent deploys are refused;
  - a `MODULE.gups` change is refused;
  - CODEOWNERS changes take effect.
- `/healthz` goes to 503 when the world thread stalls.

### 7.5 Settled questions

- **Q10 (settled).** The server shells out to the `git` binary, which `--prod` requires.
- **Q11 (settled).** A deploy without a commit deploys the fetched tip of `deploy_branch`;
  any commit deployed must be on that branch.

---

## 8. M6.6 — Course World Template and CI Gates

**Goal:** the template repository that each term's course world starts from, and the §7.3
gates that run on every pull request.

### 8.1 The template repository

- **Contents:** `template-world/` in this repository is its source (D-077). A release job
  copies it into the `course-world-template` repository and adds:
  - `MODULE.gups` pinned to that release's engine version;
  - `CODEOWNERS` with instructor ownership of `rules/`, `lib/` and `skills/`, and an example
    zone line;
  - `.github/workflows/gates.yml`, which runs the gates in the pinned engine image;
  - a `README.md` for instructors: how to start a term, import the roster and deploy.
- **Each term:** instructors create the term's repository from the template on GitHub.

### 8.2 `gups lint` (§7.3 gate 2)

`gups lint [--base REF] <pattern...>` runs these checks:

| Check | Severity | Notes |
|---|---|---|
| Dead-end rooms (one exit or fewer) without `dead_end_ok` | error | |
| Every exit has a reverse unless `one_way` | error | Doors must match on both sides. |
| A prototype whose declaration changed since `--base` without a `version` bump | error | It compares the frozen prototype of each label against the one built from the base commit's tree. Presentation and code don't count (Q13): `name`, `desc`, `keywords`, hooks, `commands`, `think` and `resolve`. Every other field does. |
| Attribute keys written but never read anywhere in the repository | warning | A static scan of `set_attr`/`get_attr` string literals; dynamic keys are skipped. |
| Hook names match the catalog | error | Already a load error (D-074); lint reports it in the same format. |
| Namespace uniqueness | error | Already a load error (D-064). |
| Regions applied to rooms but never declared, and the reverse | warning | |

- **Output:** human-readable lines, or `--format github` for annotations, plus a Markdown
  summary for `$GITHUB_STEP_SUMMARY`.
- **Exit status:** 1 on errors.

### 8.3 The fuzz walker (§7.3 gate 4)

`gups fuzz --pulses 10000 <pattern...>` loads the whole world in the harness with a fixed
seed and the bubble off.

- **What it does:** it spawns one bot player per changed zone at random rooms of that zone.
  Each bot repeatedly picks a random action:
  - an exit;
  - a zone or item command with empty arguments;
  - `get`, `drop`, `wear` or `use` on whatever is around;
  - `kill` on a random actor;
  - `say`.
- **Survival:** bots respawn (are healed) when near death, so the run stays busy.
- **Assertions:** there must be no `script_error`, `step_limit`, `wall_clock` or `quarantine`
  diagnostics and no invariant violations. Every failure prints its repro bundle.

### 8.4 The migration check (§7.3 gate 5)

- **Snapshot:** an instructor makes a **sanitized snapshot** by hand when they choose (Q12),
  with `gups snapshot --sanitized --out FILE`. Against a running server it goes through the
  admin port's `GET /snapshot`; with the server stopped it reads the database directly. It is
  a copy of the database with `accounts`, `session_tokens`, `invites`, `restrictions`,
  `diagnostics` and `item_events` emptied, and character names replaced by `PlayerN`.
- **Where it lives:** the instructor uploads it as the asset `snapshot.db` of a release
  tagged `migration-snapshot` in the course repository. The template's README explains how.
- **Staleness:** CI prints the snapshot's age in the PR summary. With no snapshot uploaded
  yet, the gate is skipped with a warning rather than failing, so a new term's first PRs pass.
- **What CI does:** `gups migrate-check --snapshot FILE` restores the PR's world against it
  and fails on:
  - a version bump with no `migrate()` and no declared no-op (`migrate_noop = [labels]`, a new
    `package()` argument);
  - tombstones not listed in `package(expected_tombstones = [...])`. This argument is accepted
    from this stage (D-070).

### 8.5 Gate budget (§7.3 gate 6)

- **Target:** the whole workflow runs in about 2 minutes on a standard GitHub runner.
- **Speed-ups:** the engine image is pulled with layer caching, tests run only for changed
  zones (gate 3), and the fuzz walker stays within its pulse count.
- **Report:** all gates post one combined PR summary.

### 8.6 Settled questions

- **Q12 (settled).** Instructors make and upload the sanitized snapshot by hand, as a release
  asset of the course repository; CI reports its age and skips the gate if there is none
  (§8.4).
- **Q13 (settled).** Text and code changes (`name`, `desc`, `keywords`, hooks, `commands`,
  `think`, `resolve`) never need a `version` bump; any other prototype field does (§8.2).

---

## 9. Out of M6

These stay out, as SPEC.md or this design leaves them:
- The load test of §7.1 (Q14), moved to a future milestone. The expected number of players
  is small for modern hosts, so it isn't needed to ship M6.
- `gups docs`, `MIGRATING.md` tooling and `plugin-api` (M7).
- ACME certificate issuance (§5.1 here).
- GMCP input packages beyond `Core.*`.
- Team owners in CODEOWNERS.
- A self-service password reset.
- Everything in DESIGN_NEEDED.md, including rules-defined starting stats.
