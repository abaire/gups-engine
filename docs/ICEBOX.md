# Icebox

Potential features that are not currently planned but may be revived in the future.

This is just a placeholder document, assume that none of these will be built.

---

## Combat and skills

### Cross-room projectiles and ranged combat between rooms

**Spec:** §3.3 (item `range`, `kind = "ranged"`), §5.5. **Today:** all combat is strictly self-contained within a single
room. The tactical track, range bands, advance, and retreat operate only inside the room; no attacks or projectiles
cross exits. **Questions:** - How should line-of-sight and projectile paths across open or closed doors be evaluated? -
Does a ranged attack initiate combat by dragging the attacker into the target's room/track, or does it create a
cross-room engagement? - How do perception and aggro propagate across rooms when an attack is fired from a distance?

## Commands

### Exits named after a plugin command

**Spec:** §4.9 (reserved exit names), §4.15, §5.2. **Today:** a package fails to load if an exit is named after an
engine command, an engine alias or a global command. Plugin commands aren't checked: the script loader doesn't know
which plugin commands are registered. Movement is resolved before plugin commands, so an exit named after a plugin
command silently hides that command in the exit's room. **Questions:** - Should the loader receive the registered plugin
command names and aliases, and reject such an exit like any other reserved name? - Plugins load before packages but can
be added or removed between runs. Does a newly added plugin command fail packages that loaded fine before, or does the
plugin fail instead? - Do role-gated plugin commands reserve the name for everyone, even players who can't use them?

## Social and communication

### Mail, message boards, and additional channels

**Spec:** §4.9 (Communication commands: `say`, `tell`, `whisper`, `shout`, `gossip`). **Today:** communication is
synchronous and limited to built-in channels. There is no offline mail system, in-game message board / bulletin system,
or builder-defined chat channels. **Questions:** - Should asynchronous mail and message boards be backed by SQLite
tables in `persist`, or can they be built as `//lib` packages using custom entities and attributes? - How are retention,
moderation, quotas, and cross-character delivery handled?

## World building and administration

### In-game OLC editing

**Spec:** §1.2, §2.1, §2.3. **Today:** world building is strictly file-based through Starlark source files committed to
git and verified by CI gates (§7.3). There is no online creation (OLC) command set (`@redit`, `@medit`, `@oedit`).
**Questions:** - If builders could edit rooms, mobs, and items live in-game, how would mutations persist back to
Starlark code and git branches? - How would schema validation, CODEOWNERS boundaries, and concurrent edit conflicts be
handled?

### Live @reload on shared production servers

**Spec:** §2.4, §5.10. **Today:** `@reload` and `@eval` are permitted only in `--dev`. In `--prod`, code changes require
the deploy pipeline (`gups deploy` / `@deploy`) to ensure atomic checkpointing, reconciliation, and consistency.
**Questions:** - Can builders safely reload individual packages on a live shared server without desynchronizing state,
orphaning references, or disrupting active player sessions? - What role permissions or locks would be required?

### Schema and feature migrations without a server wipe

**Spec:** §2.4, §3.9, §3.10, §5.10. **Today:** changes to declared stats, vitals, or enabled features in `MODULE.gups`
require starting with `--wipe`, resetting all persistent characters and items. **Questions:** - Could the persistence
layer migrate existing character and item records (e.g. populating defaults for newly added stats, pruning removed
vitals, recalculating derived values) rather than requiring a server wipe? - How should automated migrations handle
incompatible feature flag changes?

## Accounts and authentication

### Open self-signup

**Spec:** §2.4, §5.12. **Today:** `open_signup = False` by default in `MODULE.gups`. Accounts require an invite code or
roster import. Enabling `open_signup = True` allows registration at the telnet login prompt, but remains minimal.
**Questions:** - What anti-abuse controls (rate limiting, CAPTCHA, email verification, or registration gates) are
required before open signup is safe for public internet servers? - How does open signup interact with web client invites
and session tokens?

### Self-service password reset

**Spec:** §4.9, §5.12. **Today:** there is no self-service password reset. Instructors reset passwords using `@role
<account> reset`, which generates a new setup code. **Questions:** - Without native email infrastructure, how should
self-service resets be authenticated (e.g., web recovery tokens, OAuth provider integration, or reset codes)?

### Team owners in CODEOWNERS

**Spec:** §2.4, §5.12. **Today:** `CODEOWNERS` recognizes only individual `@user` GitHub handles (matched against
account `github_handle`). Team handles (`@org/team`) are ignored. **Questions:** - How should team membership be
resolved and synchronized: via GitHub REST API calls during deploy, manual configuration in `auth`, or roster CSV
imports?

## Networking and protocols

### MSDP and MXP protocols

**Spec:** §4.10, §4.13. **Today:** telnet negotiations support NAWS, TTYPE/MTTS, GMCP, EOR, and CHARSET. MSDP and MXP
are explicitly unsupported; structured client communication relies entirely on GMCP. **Questions:** - Is there
sufficient demand from legacy MUD clients to warrant implementing MSDP and MXP parsers and formatters? - How would MXP
formatting tags integrate with ANSI rendering and web client pipelines?

### Built-in ACME certificate issuance

**Spec:** §4.10, §4.13. **Today:** the engine monitors PEM certificate files on disk and live-reloads them when changed,
relying on an external client (such as certbot) to negotiate ACME issuance and renewal. **Questions:** - Should the
engine embed an ACME client (responding to HTTP-01 or TLS-ALPN-01 challenges directly) to manage Let's Encrypt
certificates without external dependencies?

## Scripting and runtime

### Transactional script execution

**Spec:** §5.13, §6.2. **Today:** script verbs apply immediately when called. If a hook aborts or throws an error,
mutations already performed stay applied; there is no rollback. **Questions:** - Should script execution support atomic
transactions where failures rollback mutations? - What performance overhead would mutation buffering or journaling
introduce for frequent hooks like `think` and `on_pulse`?

### Supporting multiple api_versions concurrently

**Spec:** §1.3, §4.17. **Today:** the engine supports exactly one `api_version` at a time. Packages declaring a
different version fail to load. **Questions:** - Should the engine support running legacy zones alongside newer packages
by maintaining multiple Starlark API bindings concurrently? - How would compatibility shims be structured, and what is
the maintenance cost across multiple API versions?

### Starlark-defined choices, traits, and restrictions

**Spec:** §4.2, §4.19. **Today:** character creation choices, traits, and restrictions can only be declared in Kotlin by
plugins. Starlark content can tag mobs with existing trait names, but cannot define new choices, traits, or restriction
logic. **Questions:** - Should Starlark packages (`//rules` or `//lib`) be able to declare custom traits and creation
choices without requiring a compiled JVM plugin? - How would Starlark-defined restrictions be evaluated during character
creation and item equips without performance penalties?

## Plugins

### Plugin marketplace, plugin hot reload, and plugin sandboxing

**Spec:** §4.15. **Today:** plugins are `.jar` files in `plugins/`, pinned by SHA-256 in `MODULE.gups`, loaded at boot
via `URLClassLoader`, and run with full JVM permissions. There is no plugin marketplace, dynamic hot reload, or security
sandboxing. **Questions:** - Can plugins be reloaded dynamically without classloader leaks or state desynchronization? -
What sandboxing mechanism is viable on modern JVMs to isolate untrusted plugin code? - Should a discovery registry or
package manager exist for community plugins?

### Plugins calling custom hooks

**Spec:** §4.4, §4.5, §4.15. **Today (after the shops milestone):** rooms, mobs and items can declare custom hooks
(`hooks = {...}`), scripts run them with `ctx.call_hook(entity, name, **args)`, and plugins can join a hook's handler
chain with `r.addHook(name, doc)`. Kotlin plugin code can't *call* a custom hook: a plugin-run feature (for example a
plugin shop asking a keeper's `shop_price`) has no `PluginWorld.callHook`. **Questions:** - Should the facade gain
`callHook(entity, name, args): Any?`, running the same chain as `ctx.call_hook`? - How are Starlark return values
converted to plain values for Kotlin, and plugin arguments to Starlark values? - Can a plugin's `callHook` run from
inside a GMCP input handler or a hook handler (read-only contexts), given that the Starlark hook may change the world? -
Should a plugin be able to call a hook that its own handler is part of, and how is recursion bounded?

## Operations, scalability and testing

### Multi-server sharding, distributed tracing, and external log aggregation

**Spec:** §1.1, §2.2, §5.1. **Today:** GUPS is designed as a single-process server with an in-memory tick and SQLite
persistence. Distributed tracing and external log aggregation are not built-in. **Questions:** - How would zone
handoffs, cross-zone combat, and item custody work across a sharded cluster? - Should standard OpenTelemetry
instrumentation be integrated for distributed metrics and tracing?

### Dedicated multi-client load testing

**Spec:** §7.1. **Today:** headless harness tests and fuzz walkers verify state transitions and game rules, but
end-to-end load tests with hundreds of simulated telnet/web clients were deferred. **Questions:** - What tool or harness
should drive synthetic player traffic to validate the 200-player and tick-latency targets of §7.1? - How should load
testing be incorporated into CI without exceeding runner resource limits?

### Automated api_version bump in release jobs

**Spec:** §4.17. **Today:** bumping `api_version` is a manual pre-release step because CI validates the API baseline
against checked-in files, and a tag-triggered release workflow cannot commit back to git. **Questions:** - Can tooling
or a release bot detect breaking script API changes and automate the version bump, doc regeneration, and baseline update
as part of a pre-release PR?
