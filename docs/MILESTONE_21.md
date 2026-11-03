# Milestone 21: Plugin GMCP Input and GMCP Events

**Prerequisites:** [M20](MILESTONE_20.md) should be finished first. This milestone doesn't depend on its behavior, but
both edit `docs/SPEC.md` (the §7.4 table), `docs/CHANGELOG.md` and the regenerated API files. If M20 is not finished,
keep its entries and add this milestone's next to them. [M22](MILESTONE_22.md) builds on this milestone. Where line
numbers below are wrong, find the symbol by name.

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** [DESIGN_NEEDED.md](DESIGN_NEEDED.md): `## Networking and protocols` → `### GMCP input
  packages beyond Core.*`. The design came out of the shops design session (`## Economy and crafting` → `### Shops`),
  which needs a shop plugin's client UI to send requests and receive replies. Shops themselves are not part of this
  milestone.
- **Design Summary:**
  - **Inbound GMCP goes to plugins.** A plugin registers `r.onGmcp(pkg, doc) { call -> … }`. A client message whose
    package matches a registered package (exact match, ignoring case) is handed to that plugin's handler on the world
    thread. Messages for packages nobody registered are ignored, as today. `Core.*` keeps its existing handling in `net`
    and never reaches the world.
  - **Handlers can't change the world directly.** A handler gets a `PluginGmcpCall` whose `world` is a
    `PluginGmcpWorld`: the read-only part of the facade plus `send` and the new outbound `gmcp` event verb. To act, a
    handler calls `call.command(line)`, which runs the line as if the player typed it (`World.submit`), so lag, casting,
    incapacitation, queue limits, roles, restrictions and repro recording all apply unchanged. `command` may be called
    at most once per message; a second call throws. `command` lives on the call, not on the world, so it can only act as
    the player who sent the message.
  - **The read-only view is a real type.** `PluginWorld` is split: a new `PluginGmcpWorld` interface holds the reads,
    `features`, `send`, `gmcp`, `getAttr`, `diagnose` and `log`, and `PluginWorld` extends it with every mutating verb
    and `rng`. `rng` stays off the view because drawing from the seeded RNG changes world state that repro bundles don't
    record for inbound GMCP. The handler receives an object that implements only `PluginGmcpWorld`, so casting it to
    `PluginWorld` fails.
  - **Plugin GMCP events (outbound).** `r.addGmcpEvent(pkg, doc)` declares an event package, and `PluginWorld.gmcp(
    actor, pkg, data): Boolean` sends one message now (queued with the session's text, like `Comm.Channel.Text`, never
    cached). A plugin may send only under a package it declared with `addGmcp` or `addGmcpEvent` (equal or nested). It
    returns `false`, sending nothing, for a mob, a link-dead player, or a client whose `Core.Supports` doesn't cover the
    package. Being on `PluginGmcpWorld`, it is available to handlers, commands, effects and start callbacks.
  - **Names.** All three kinds (state `addGmcp`, event `addGmcpEvent`, input `onGmcp`) use the existing name pattern
    (two or more dot-separated parts). Comparisons ignore case everywhere, consistent with `Core.Supports`. This
    tightens the existing outbound overlap check. The root `Core` is reserved for every kind. Outbound packages (state
    and event) may not equal, contain or nest in an engine package, another plugin's package of any kind, or the same
    plugin's other outbound packages (today's rule). An input package may not equal, contain or nest in an engine
    package or another plugin's package of any kind, and may not repeat one of the plugin's own input packages; it
    **may** equal, contain or nest in the plugin's own outbound packages (`Shop.Buy` beside `Shop`).
  - **Validation and limits.** `net` parses the JSON body into plain values: `String`, `Long` (integral numbers that
    fit), `Double` (other numbers), `Boolean`, `List`, `Map<String, Any?>` and `null`. A body over **4096 bytes**
    (UTF-8), nested more than **8** levels, or that doesn't parse is *malformed*. `net` never counts a non-`Core`
    message as a protocol error; it forwards it marked malformed. The world ignores malformed messages for unregistered
    packages; for a registered package a malformed message counts as a dropped input (the same counter floods use: more
    than 3 in a minute disconnects with the "too much input" message). This also fixes today's telnet behavior, which
    counts malformed JSON on *any* package as a protocol error although §4.10 says only `Core.*` should.
  - **Rate and admission.** Every inbound non-`Core` message takes a token from the session's raw line bucket
    (`Session.lines`) first, like a typed line. A message before the session has a character is dropped silently. A
    message for a registered package then takes a token from the fast-lane bucket (`Session.bucket`); on failure the
    player gets "Slow down." and the drop is counted, as for a fast-lane line. `command()` charges nothing more. A
    handled message sets `lastInputPulse`.
  - **Ordering.** GMCP messages travel on the same input channel as typed lines, so their order is kept.
  - **Errors.** A handler exception is a `plugin` diagnostic (`GMCP input 'Shop.Buy' failed: …`) and never counts
    against the client.
  - **Replay.** Inbound GMCP is not recorded. What a handler does through `command()` is recorded by the existing
    tick-lane rule in `World.submit`.
  - **Docs.** `plugins.html` lists each plugin's GMCP packages with a kind column: `state`, `event`, `input`.
  - **Version.** `PluginApi.VERSION` stays 1 (unreleased). CHANGELOG entries are added.
  - **Out of scope:** Starlark sending GMCP (M22); Starlark receiving GMCP; inbound GMCP before a character exists; a
    shop plugin; the "Plugins that share a GMCP package tree" design.
- **Specification Impact:** §4.10 (GMCP input bullet, WebSocket `gmcp` frame line, plugin packages note), §4.11 (seam
  table row), §4.15 (registry list, `onGmcp`, `addGmcpEvent`, facade `gmcp`, read-only handler view, name rules), §4.16
  (`plugins.html` row), §5.1 (between-pulse input), §6.4 (malformed-frames row), §7.4 (new M21 row); `docs/PLUGINS.md`;
  `docs/CHANGELOG.md`.
- **Decision Record Updates:** None. D-178 (plugin GMCP packages may not overlap) is already folded into §4.15 and the
  "Plugins that share a GMCP package tree" entry; its rule is extended here to input and event packages and made
  case-insensitive. That `DESIGN_NEEDED` entry is not resolved by this milestone.

### Guardrails for every phase

1. Follow `AGENTS.md`: TDD (write the tests first and see them fail), no spec section numbers or decision ids in code
   comments, all Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`.
3. Add CHANGELOG bullets in a new `### Plugin GMCP input and events` subsection at the top of `## api_version 1
   (unreleased)` in `docs/CHANGELOG.md`. Create it in the first phase that needs it.
4. If a phase changes `plugins.html` output, regenerate the docs goldens with `./gradlew :cli:test
   -Dgups.updateGolden=true` and read the diff: only the plugin GMCP table should change. No phase in this milestone
   changes the script API, so `docs/api/api-1.json` and `api.golden.json` must not change; if they do, something is
   wrong.
5. Gradle commands must run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that
   code blocks kept their indentation.
6. No database migrations.
7. Never read or touch `local-world/`.

---

## Phase 1: Decoding inbound GMCP in `net`

### Objective & Scope

- **In Scope:** a plain-value JSON decoder with size and depth limits, shared by telnet and WebSocket; telnet stops
  counting malformed non-`Core` messages as protocol errors.
- **Out of Scope:** sending anything to the world (Phase 3); any `core` change.

### Context Working Set

- **Read / Reference Only:**
  - `net/src/main/kotlin/gups/net/Telnet.kt`: `gmcpIn` (around line 391), `toJson` (around line 452), `updateSupports`
    (around line 467), `MAX_SUBNEGOTIATION` (line 47).
  - `net/src/main/kotlin/gups/net/WebServer.kt`: `receive(text)` (around line 469).
- **Files to Modify / Create:**
  - New `net/src/main/kotlin/gups/net/GmcpInput.kt`
  - `net/src/main/kotlin/gups/net/Telnet.kt`
  - New `net/src/test/kotlin/gups/net/GmcpInputTest.kt`
  - `net/src/test/kotlin/gups/net/TelnetConnectionTest.kt`
- **Do Not Touch:** `core/`, `plugin-api/`, `WebServer.kt` (Phase 3).

### Specification & Decision Record Changes

None. The telnet fix makes the code match §4.10 ("A `Core.*` message with malformed JSON counts as a protocol error;
other packages are ignored").

### Implementation Blueprint

- **Signatures & Types** (`GmcpInput.kt`):

  ```kotlin
  /** Largest inbound GMCP body, in UTF-8 bytes, that is passed to the world. */
  const val MAX_GMCP_IN_BYTES = 4096

  /** Deepest nesting of lists and objects in an inbound GMCP body. */
  const val MAX_GMCP_IN_DEPTH = 8

  /** An inbound GMCP body as plain values, or [Malformed]. */
  sealed interface GmcpBody {
    data class Ok(val data: Any?) : GmcpBody
    data object Malformed : GmcpBody
  }

  /**
   * Parses [text], the part after the package name (null when the message has none), into plain
   * values: String, Long, Double, Boolean, List, Map<String, Any?> or null.
   */
  fun decodeGmcpBody(text: String?): GmcpBody

  /** [e] as plain values, or null when it nests deeper than [MAX_GMCP_IN_DEPTH]. */
  internal fun fromJson(e: JsonElement, depth: Int = 0): Any?
  ```
- **Algorithmic Steps & Invariants:**
  1. `decodeGmcpBody(null)` is `Ok(null)`. A text whose UTF-8 size is over `MAX_GMCP_IN_BYTES` is `Malformed`. Parse
     with `Json.parseToJsonElement` inside `runCatching`; failure is `Malformed`.
  2. `fromJson`: `JsonNull` → null; a string primitive → `String`; `true`/`false` → `Boolean`; a number → `longOrNull`
     first, else `doubleOrNull`; `JsonArray` → `List`; `JsonObject` → `LinkedHashMap<String, Any?>` keeping order. Each
     array or object level adds 1 to `depth`; above `MAX_GMCP_IN_DEPTH` the body is malformed. Use a private exception
     or a sentinel to unwind; don't return a nullable that collides with JSON `null`.
  3. `Telnet.kt`, `gmcpIn`: split the package from the body first. If the package starts with `core.` (ignoring case),
     keep today's behavior exactly (parse, count `errors++` on failure, `Core.Hello`, `updateSupports`). Otherwise do
     nothing yet (Phase 3 forwards it) and never touch `errors`. Update the KDoc ("Only `Core.*` is read; a malformed
     `Core.*` message counts as a protocol error.").

### Test & Documentation Requirements

- `GmcpInputTest.kt`: `` `decodes plain values` `` (object with string, int, float, bool, null, nested list); ``
  `integral numbers are longs and others doubles` ``; `` `a body over 4096 bytes is malformed` `` (4096 bytes is fine);
  `` `nesting deeper than 8 is malformed` `` (8 levels is fine); `` `invalid JSON is malformed` ``; `` `no body is null`
  ``.
- `TelnetConnectionTest.kt`: `` `malformed JSON on a non-Core package is not a protocol error` `` (send `IAC SB GMCP
  Shop.Buy {bad IAC SE`, assert `errors == 0`); keep or add `` `malformed JSON on Core.Supports.Set is a protocol error`
  ``.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `./gradlew :net:test --tests '*GmcpInputTest*' --tests '*TelnetConnectionTest*'` passes.

---

## Phase 2: The GMCP input event and dispatch in `core`

### Objective & Scope

- **In Scope:** a sealed client event type with a GMCP case on the existing input channel; `World.handle` for GMCP with
  rate limits, drops and dispatch; the `World.pluginGmcpIn` table; shared name helpers.
- **Out of Scope:** `net` producing the event (Phase 3); plugin registration (Phases 4–5).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Sessions.kt`: `Session` (buckets at lines ~28–33), `World.handle(
    ClientInputEvent)` (line ~90), `dropped` (line ~124).
  - `core/src/main/kotlin/gups/core/world/Commands.kt`: `World.submit` (line ~435).
  - `core/src/main/kotlin/gups/core/world/Gmcp.kt`: `ENGINE_GMCP_PACKAGES` (line 26), `World.gmcp` (line ~33).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/Seams.kt`
  - `core/src/main/kotlin/gups/core/loop/WorldLoop.kt`
  - `core/src/main/kotlin/gups/core/world/Sessions.kt`
  - `core/src/main/kotlin/gups/core/world/World.kt`
  - `core/src/main/kotlin/gups/core/world/Gmcp.kt`
  - New `core/src/test/kotlin/gups/core/world/GmcpInputTest.kt`
  - `docs/SPEC.md`
- **Do Not Touch:** `net/`, `plugin-api/`, `script/`. Existing callers that construct `ClientInputEvent(session, line,
  nanos)` must keep compiling unchanged (about 20 test files use it).

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§4.11 seam table**, the `ClientInputEvent` row: rename the first cell to `` `ClientEvent` / `WorldControlEvent` /
   `WorldInbox` `` and its text to "Queues player input (typed lines and inbound GMCP messages, in arrival order) and
   delivers asynchronous world control events (reload, deploy, connect, disconnect, shutdown, tasks)."
2. **§5.1, "Between pulses"**: replace "Each dequeued `ClientInputEvent` is classified (§5.2)." with "Each dequeued
   input line is classified (§5.2), and each inbound GMCP message goes to the plugin that registered its package
   (§4.10)."

### Implementation Blueprint

- **Signatures & Types:**

  `Seams.kt`:

  ```kotlin
  /** Input from a connected client session, in the order it arrived. */
  sealed interface ClientEvent {
    val session: SessionId
    val receivedNanos: Long
  }

  /** A line of raw text input received from a connected client session. */
  data class ClientInputEvent(
    override val session: SessionId,
    val line: String,
    override val receivedNanos: Long,
  ) : ClientEvent

  /** A GMCP message from a client, for a package other than `Core.*`. */
  data class ClientGmcpEvent(
    override val session: SessionId,
    /** The package as the client spelled it. */
    val pkg: String,
    /** The body as plain values; null when [malformed]. */
    val data: Any?,
    /** True when the body was too large, too deep or not JSON. */
    val malformed: Boolean,
    override val receivedNanos: Long,
  ) : ClientEvent
  ```

  Keep the existing KDoc on `ClientInputEvent`'s properties. `WorldInbox` gains `fun submit(event: ClientGmcpEvent) =
  Unit` (a default, so test inboxes keep compiling).

  `World.kt`, next to `pluginGmcp`:

  ```kotlin
  /** Inbound GMCP packages, keyed by lowercase name. */
  val pluginGmcpIn: MutableMap<String, GmcpInput> = LinkedHashMap()
  ```

  `Gmcp.kt`:

  ```kotlin
  /** A GMCP package clients may send, and what handles it. */
  class GmcpInput(
    /** The package as registered. */
    val pkg: String,
    val handle: (Player, Any?) -> Unit,
  )

  /** A GMCP package name: two or more dot-separated parts. */
  val GMCP_PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

  /** Roots no plugin or script package may use. */
  val RESERVED_GMCP_ROOTS: Set<String> = setOf("Core")

  /** True if [a] and [b] are equal or one nests in the other, ignoring case. */
  fun gmcpOverlaps(a: String, b: String): Boolean

  /** True if [pkg] is [root] or nests in it, ignoring case. */
  fun gmcpWithin(pkg: String, root: String): Boolean
  ```

  `Sessions.kt`:

  ```kotlin
  /** Handles one inbound GMCP message between pulses. */
  fun World.handle(event: ClientGmcpEvent)
  ```
- **Algorithmic Steps & Invariants:**
  1. `WorldLoop.kt`: `input` becomes `Channel<ClientEvent>`. `submit(ClientInputEvent)` stays; add `override fun
     submit(event: ClientGmcpEvent)` with the same body. In the `select`, `input.onReceive { waiting.decrementAndGet();
     when (it) { is ClientInputEvent -> world.handle(it); is ClientGmcpEvent -> world.handle(it) } }`.
  2. `World.handle(ClientGmcpEvent)`, in this order:
     1. `val s = sessions[event.session] ?: return`.
     2. `if (!s.lines.tryTake(event.receivedNanos)) { dropped(s, event.receivedNanos); return }`.
     3. `val player = s.player ?: return` (silent: no character yet).
     4. `val input = pluginGmcpIn[event.pkg.lowercase()] ?: return` (unknown packages are ignored).
     5. `if (event.malformed) { dropped(s, event.receivedNanos); return }`.
     6. If `!s.bucket.tryTake(event.receivedNanos)`: append `msg(KernelMsg.SLOW_DOWN)`, `flush(s)`, `dropped(...)`,
        return. Mirror the fast-lane branch of the line handler exactly.
     7. `player.lastInputPulse = pulse`.
     8. `guarded("GMCP input '${input.pkg}' of $player") { input.handle(player, event.data) }`.
     9. `flushImmediate()`, `flush(s)`.
  3. `gmcpOverlaps(a, b)`: `a.equals(b, true) || a.startsWith("$b.", true) || b.startsWith("$a.", true)`.
     `gmcpWithin(pkg, root)`: `pkg.equals(root, true) || pkg.startsWith("$root.", true)`.
  4. Invariant: line handling is unchanged; existing session tests pass untouched.

### Test & Documentation Requirements

- `GmcpInputTest.kt` (core), using the existing `Fixture` and a player attached to a session (see `SessionTest.kt` for
  how sessions and `CapturingSink` are set up). Register `world.pluginGmcpIn["shop.buy"] = GmcpInput("Shop.Buy") { p, d
  -> calls += p to d }`. Cases:
  - `` `a registered package reaches its handler with its data` `` (client spelling `shop.BUY` also matches).
  - `` `unknown packages are ignored` `` (no call, no drop counted: send 10 and the session stays open).
  - `` `messages before a character are dropped silently` ``.
  - `` `malformed messages for a registered package count as drops` `` (4 within a minute close the session).
  - `` `the fast-lane bucket limits handled messages` `` ("Slow down." after the bucket's capacity).
  - `` `a throwing handler records a diagnostic and keeps the session` ``.
  - `` `lines and GMCP keep their order through the loop` ``: submit a line then a GMCP event to a `WorldLoop`, and
    assert the handler sees the line's effect first (follow an existing `WorldLoop` test; `grep -rln "WorldLoop("
    core/src/test`).
- Unit tests for `gmcpOverlaps` and `gmcpWithin` (equal, nested both ways, case, `Shop` vs `Shopkeeper` is not an
  overlap).

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `./gradlew :core:test --tests '*GmcpInputTest*'` passes.
- [] `grep -n "Each dequeued \`ClientInputEvent\`" docs/SPEC.md` returns nothing.

---

## Phase 3: `net` forwards inbound GMCP

### Objective & Scope

- **In Scope:** telnet and WebSocket submit `ClientGmcpEvent` for every non-`Core` GMCP message; the §4.10 and §6.4
  wording.
- **Out of Scope:** plugin registration (nothing is registered yet, so the world ignores every message).

### Context Working Set

- **Read / Reference Only:** `net/src/main/kotlin/gups/net/GmcpInput.kt` (Phase 1); `core/.../Seams.kt` (Phase 2).
- **Files to Modify / Create:**
  - `net/src/main/kotlin/gups/net/Telnet.kt`
  - `net/src/main/kotlin/gups/net/TelnetServer.kt`
  - `net/src/main/kotlin/gups/net/WebServer.kt`
  - `net/src/test/kotlin/gups/net/TelnetServerTest.kt`, `net/src/test/kotlin/gups/net/WebServerTest.kt`
  - `docs/SPEC.md`
- **Do Not Touch:** `core/`, `plugin-api/`.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§4.10, "GMCP packages (v0)" bullet**: replace "GMCP input other than `Core.*` is ignored in v0." with "Other
   packages go to the world, which hands each to the plugin that registered it (§4.15) and ignores the rest."
2. **§4.10**, after the "GMCP delivery" bullets, add:
   - **GMCP input:** a client message for a package other than `Core.*` is parsed into plain values (strings, integers,
     other numbers, booleans, lists, objects and null) and queued with the session's typed lines, keeping their order. A
     body over 4096 bytes, nested more than 8 levels deep, or not valid JSON is malformed. Each message takes a token
     from the session's line limit; a message before the session has a character, or for a package no plugin registered,
     is then ignored. A malformed message for a registered package counts as dropped input, like a flood. A registered
     package's message takes a fast-lane token ("Slow down." when there is none) and runs the plugin's handler. Handlers
     act through ordinary command lines (§4.15). Inbound GMCP is never recorded for repro bundles; the command lines it
     causes are.
3. **§4.10, WebSocket framing**: replace "and `gmcp` (which only reads `Core.Supports.*`, as over telnet)" with "and
   `gmcp` (`Core.Supports.*` is read as over telnet; other packages are GMCP input)".
4. **§6.4**, the "Malformed telnet, GMCP or WebSocket frames" row: append "A malformed GMCP message for a package other
   than `Core.*` is not a protocol error (§4.10)."

### Implementation Blueprint

- **Signatures & Types:** `TelnetConnection.receive(bytes: ByteArray, onGmcp: (String, GmcpBody) -> Unit = { _, _ -> },
  onLine: (String, Boolean) -> Unit)`. `onLine` stays last so trailing-lambda callers keep compiling. Store `onGmcp` for
  the duration of the call (the option handler at line ~345 calls `gmcpIn` while `receive` runs).
- **Algorithmic Steps & Invariants:**
  1. `Telnet.kt`, `gmcpIn`, non-`Core` branch: `onGmcp(pkg, decodeGmcpBody(bodyText))`, where `bodyText` is the text
     after the first space or null. Never increments `errors`.
  2. `TelnetServer.kt`: pass `onGmcp = { pkg, body -> inbox.submit(ClientGmcpEvent(id, pkg, (body as?
     GmcpBody.Ok)?.data, body is GmcpBody.Malformed, System.nanoTime())) }` to `conn.receive`.
  3. `WebServer.kt`, the `"gmcp"` case: `val pkg = o.string("p") ?: return bad()`. If `pkg` starts with `core.`
     (ignoring case) keep `updateSupports`. Otherwise decode `o["d"]` without re-serializing when possible: if `o["d"]`
     is absent → `Ok(null)`; else check `o["d"].toString()`'s UTF-8 size against `MAX_GMCP_IN_BYTES` and run `fromJson`
     (depth). Submit a `ClientGmcpEvent`. A missing `p` is still `bad()`.
  4. Invariant: lines and GMCP from one connection are submitted in the order they arrive, on the same inbox.

### Test & Documentation Requirements

- `TelnetServerTest.kt`: `` `non-Core GMCP is submitted to the inbox in order with lines` `` (use the test inbox the
  file already has; it now also records `ClientGmcpEvent`s by overriding the new `submit`). Also: a malformed body
  arrives with `malformed = true` and the connection stays open.
- `WebServerTest.kt`: `` `a gmcp frame for another package is submitted` `` and `` `Core.Supports frames still update
  supports` ``.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings (including `markdownCheck`).
- [] `./gradlew :net:test` passes.
- [] `grep -n "is ignored in v0" docs/SPEC.md` returns nothing.

---

## Phase 4: Plugin GMCP events and the read-only world view

### Objective & Scope

- **In Scope:** `PluginGmcpWorld` split out of `PluginWorld`; `PluginWorld.gmcp`; `PluginRegistry.addGmcpEvent`;
  case-insensitive name checks and the reserved `Core` root for state and event packages.
- **Out of Scope:** `onGmcp` and inbound dispatch (Phase 5); docs rendering (Phase 6).

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`: `PluginRegistryImpl.addGmcp` (around line 271), the
    `GMCP` regex (around line 358), install loop for `p.reg.gmcp` (around line 476), `takenGmcp` (line ~683).
  - `core/src/main/kotlin/gups/core/world/Gmcp.kt`: `World.gmcp`, `gmcpOverlaps`, `gmcpWithin`, `RESERVED_GMCP_ROOTS`
    (Phase 2).
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt` (`WorldFacade`)
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`
  - `plugin-api/src/test/kotlin/gups/pluginapi/FacadeTest.kt`, `PluginLoaderTest.kt`, `ApiSurfaceTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `core/` main code, `net/`, `script/`, `cli/`.

### Specification & Decision Record Changes

Edit `docs/SPEC.md` §4.15:

1. **"`PluginRegistry`" bullet**: "GMCP package producers" becomes "GMCP state packages and event packages".
2. **"The Facade" bullet**: add `gmcp` to the verb list, and append: "`PluginWorld` extends `PluginGmcpWorld`, which
   holds the reads (except `rng`), `send`, `gmcp`, `getAttr`, `diagnose` and `log`; it is what GMCP input handlers
   receive."
3. **"GMCP" bullet** (under "Plugin capabilities and lifecycle"), replace with:
   - **GMCP:** `addGmcp(pkg, doc, produce)` declares a state package whose producer runs in phase 7 for sessions
     supporting it, sending only changed payloads (§4.10). `addGmcpEvent(pkg, doc)` declares an event package;
     `gmcp(actor, pkg, data)` sends one message under a package the plugin declared (equal or nested), queued with the
     session's text and never cached, and returns false, sending nothing, for a mob, a link-dead player or a client that
     doesn't support it. Names have two or more dot-separated parts and are compared ignoring case. No plugin may use
     the root `Core`. A plugin's state and event packages may not equal, contain or nest in an engine package, another
     plugin's package, or each other.

Add a CHANGELOG bullet per new API (`addGmcpEvent`, `PluginWorld.gmcp`, `PluginGmcpWorld`) and one for the tightened,
case-insensitive overlap check and the reserved `Core` root.

### Implementation Blueprint

- **Signatures & Types:**

  `Facade.kt` (api):

  ```kotlin
  /** What a plugin may do without changing the world: reads, messages and GMCP events. */
  interface PluginGmcpWorld {
    val features: Set<String> get() = setOf("combat", "track", "skills", "leveling")
    fun send(target: PluginActor, text: String)
    /**
     * Sends the GMCP event [pkg] with [data] (plain values) to [actor]. [pkg] must be, or nest in,
     * a package this plugin declared with addGmcp or addGmcpEvent. Returns false, sending nothing,
     * for a mob, a link-dead player, or a client that doesn't support [pkg].
   */
    fun gmcp(actor: PluginActor, pkg: String, data: Any?): Boolean
    fun getAttr(entity: PluginEntity, key: String, default: Any? = null): Any?
    val schema: PluginSchema
    val pulse: Long
    val seed: Long
    fun rooms(): List<PluginRoom>
    fun room(label: String): PluginRoom?
    fun regions(): List<String>
    fun diagnose(message: String)
    fun log(message: String)
  }

  interface PluginWorld : PluginGmcpWorld { /* rng and every remaining verb, unchanged */ }
  ```

  Move the listed members (with their KDoc) out of `PluginWorld`; leave the rest where they are. Update the class KDoc.

  `PluginApi.kt`, next to `addGmcp`:

  ```kotlin
  /** A GMCP event package (at least two dot-separated parts), sent with PluginWorld.gmcp. */
  fun addGmcpEvent(pkg: String, doc: String)
  ```

  `PluginHost.kt`: `data class GmcpEventReg(val pkg: String, val doc: String)`; `PluginRegistryImpl.gmcpEvents`.

- **Algorithmic Steps & Invariants:**

  1. One private `checkOutbound(pkg)` in `PluginRegistryImpl`, used by `addGmcp` and `addGmcpEvent`:
     - `require(GMCP_PACKAGE_NAME.matches(pkg))` with today's message; delete the private `GMCP` regex in favor of the
       core one.
     - `RESERVED_GMCP_ROOTS.firstOrNull { gmcpWithin(pkg, it) || gmcpWithin(it, pkg) }` → "GMCP package '$pkg' uses the
       reserved root Core".
     - engine overlap (`ENGINE_GMCP_PACKAGES`, `gmcpOverlaps`), other plugins (`takenGmcp`, `gmcpOverlaps`), and this
       plugin's state and event packages (`gmcpOverlaps`), with today's messages. An exact repeat (ignoring case) keeps
       the "is already registered" message.
  2. `takenGmcp` (line ~683) becomes every earlier plugin's state, event (and, from Phase 5, input) package names.
  3. `WorldFacade` gets a constructor parameter `ownedGmcp: List<String>` (the plugin's state and event packages);
     `facade(world, id)` passes them (empty for a null id). `gmcp(actor, pkg, data)`:
     - `require(ownedGmcp.any { gmcpWithin(pkg, it) })` → "gmcp: plugin '<id>' didn't declare a GMCP package for
       '$pkg'". Also `require(GMCP_PACKAGE_NAME.matches(pkg))`.
     - Check [data] is plain (null, String, Boolean, Int, Long, Double, List/Iterable of plain, Map with String keys of
       plain); otherwise `IllegalArgumentException` naming the bad type.
     - Resolve the actor like the other verbs (a stale view fails). A `Mob` returns false. Otherwise return
       `world.gmcp(player, pkg, data)`.
  4. `ApiSurfaceTest`: add `PluginGmcpWorld::class` to the list.

### Test & Documentation Requirements

- `PluginLoaderTest.kt`: `` `GMCP names are compared ignoring case` `` (`Gups.weather` vs another plugin's
  `Gups.Weather` fails); `` `no plugin may use the Core root` `` (`Core.Shop` fails for both kinds); `` `an event
  package may not overlap another plugin's state package` ``; `` `a plugin's event and state packages may not nest` ``.
- `FacadeTest.kt`: `` `gmcp sends an event under a declared package` `` (a session sink that wants the package records
  it; follow how `TlsGmcpTest` or `FacadeTest` builds a GMCP-capable sink); `` `gmcp refuses an undeclared package` ``;
  `` `gmcp returns false for a mob and for an unsupported package` ``; `` `gmcp rejects non-plain data` ``.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `./gradlew :plugin-api:test` passes, including `ApiSurfaceTest`.
- [] `samples/` still build unchanged (they implement nothing that moved).

---

## Phase 5: `onGmcp`: plugins receive GMCP input

### Objective & Scope

- **In Scope:** `PluginRegistry.onGmcp`, `PluginGmcpCall`, input-name rules, installing handlers into
  `World.pluginGmcpIn`, the once-per-message `command`, end-to-end tests.
- **Out of Scope:** docs rendering (Phase 6).

### Context Working Set

- **Read / Reference Only:** `core/.../Commands.kt` `World.submit`; `core/.../Sessions.kt` `World.handle(
  ClientGmcpEvent)`; the command adapter loop in `PluginHost.kt` (around lines 445–474) for the `guard` pattern.
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt` (a read-only wrapper)
  - New `plugin-api/src/test/kotlin/gups/pluginapi/GmcpInputTest.kt`; `PluginLoaderTest.kt`; `ApiSurfaceTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `core/` main code, `net/`.

### Specification & Decision Record Changes

Edit `docs/SPEC.md` §4.15:

1. **"`PluginRegistry`" bullet**: add "GMCP input handlers (`onGmcp`)".
2. After the **GMCP** bullet, add:
   - **GMCP input:** `onGmcp(pkg, doc, handle)` receives client messages for exactly `pkg` (ignoring case, §4.10). An
     input package may not use the root `Core` or equal, contain or nest in an engine package or another plugin's
     package; it may share the plugin's own tree (`Shop.Buy` beside `Shop`). The handler gets the sending player, the
     data as plain values, and a `PluginGmcpWorld`, which can read, send text and send GMCP events but not change the
     world. To act, it calls `command(line)` once at most, which runs the line as if the player typed it, through the
     normal lanes, lag, restrictions and repro recording. A handler that throws records a `plugin` diagnostic.
3. **"Failures at run time" bullet**: add "GMCP input handlers" to the list.

### Implementation Blueprint

- **Signatures & Types** (`PluginApi.kt`):

  ```kotlin
  /** One GMCP message from a player's client. */
  interface PluginGmcpCall {
    val world: PluginGmcpWorld
    val actor: PluginActor
    /** The package as registered. */
    val pkg: String
    /** The body as plain values: String, Long, Double, Boolean, List, Map<String, Any?> or null. */
    val data: Any?
    /**
     * Runs [line] as if the player typed it, and returns whether it was accepted. May be called
     * at most once per message.
   */
    fun command(line: String): Boolean
  }

  /** Receives client GMCP messages for exactly [pkg] (ignoring case). */
  fun onGmcp(pkg: String, doc: String, handle: (PluginGmcpCall) -> Unit)
  ```

  `PluginHost.kt`: `data class GmcpInputReg(val pkg: String, val doc: String, val handle: (PluginGmcpCall) -> Unit)`;
  `PluginRegistryImpl.gmcpInputs`.

  `host/Facade.kt`: `internal class GmcpWorldFacade(inner: WorldFacade) : PluginGmcpWorld by inner`, so the handler's
  world can't be cast to `PluginWorld`.

- **Algorithmic Steps & Invariants:**

  1. `onGmcp` checks: the name pattern; the reserved root; `gmcpOverlaps` with `ENGINE_GMCP_PACKAGES` and with
     `takenGmcp` (other plugins' state, event and input packages); an exact repeat of its own input packages ("is
     already registered"). It does **not** check against its own state or event packages.
  2. Every outbound check from Phase 4 also rejects overlap with other plugins' input packages (via `takenGmcp`); add
     input names to `takenGmcp`.
  3. Install loop (after the `p.reg.gmcp` loop): for each input registration, `world.pluginGmcpIn[g.pkg.lowercase()] =
     GmcpInput(g.pkg) { player, data -> guard(world, p.id, "GMCP input '${g.pkg}'") { g.handle(call(player, data)) } }`,
     where `call` builds a private `PluginGmcpCall` implementation with `world = GmcpWorldFacade(facade(world, p.id))`,
     `actor = ActorFacade(player, world)`, and `command(line)`: if already used, `throw IllegalStateException("command()
     runs at most one line per GMCP message")`; else mark used and return `world.submit(player, line)`.
  4. Invariant: a reload that swaps plugins clears and refills `pluginGmcpIn` the same way `pluginGmcp` is handled (find
     where `pluginGmcp` is cleared, if anywhere, and mirror it).

### Test & Documentation Requirements

- `GmcpInputTest.kt` (plugin-api), using the existing `Fixture` with a test plugin that registers `addGmcp("Shop", …)`,
  `onGmcp("Shop.Buy", …)` and a tick-lane command `buy`:
  - `` `a client message runs the handler with plain data` `` (drive it with `world.handle(ClientGmcpEvent(...))`).
  - `` `command queues a tick-lane line that runs at the next pulse and is recorded` `` (assert `world.inputs` has the
    line, and the command ran after `runPulse`).
  - `` `command can be called only once per message` ``.
  - `` `the handler's world can't change anything` `` (`assertFailsWith<ClassCastException> { call.world as PluginWorld
    }`).
  - `` `a handler can reply with a GMCP event` `` (`call.world.gmcp(call.actor, "Shop.Stock", …)` reaches the sink).
  - `` `a throwing handler records a plugin diagnostic` ``.
- `PluginLoaderTest.kt`: `` `an input package may nest in the plugin's own tree` ``; `` `an input package may not nest
  in another plugin's package` ``; `` `an input package may not repeat` ``.
- `ApiSurfaceTest.kt`: add `PluginGmcpCall::class`.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `./gradlew :plugin-api:test --tests '*GmcpInputTest*'` passes.

---

## Phase 6: Docs, plugin guide and finalization

### Objective & Scope

- **In Scope:** the `plugins.html` GMCP table with a kind column; `docs/PLUGINS.md`; §4.16 and §7.4; the `DESIGN_NEEDED`
  entry marked completed.
- **Out of Scope:** sample plugin changes.

### Context Working Set

- **Read / Reference Only:** `cli/src/main/kotlin/gups/cli/Commands.kt` (line ~803, building `PluginGmcpDocSpec`).
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt` (`PluginGmcpInfo`, `describe`)
  - `cli/src/main/kotlin/gups/cli/DocsRenderer.kt`, `cli/src/main/kotlin/gups/cli/Commands.kt`
  - `cli/src/test/resources/docs-golden/plugins.html` (regenerated), any `cli` test asserting the GMCP table
  - `docs/PLUGINS.md`, `docs/SPEC.md`, `docs/CHANGELOG.md`, `docs/DESIGN_NEEDED.md`
- **Do Not Touch:** `core/`, `net/`, `script/`.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§4.16, `plugins.html` row**: "GMCP packages" becomes "GMCP packages (with their kind: state, event or input)".
2. **§4.10**, after the package table: "Plugins may add packages (§4.15)." becomes "Plugins may add state and event
   packages, and receive GMCP input (§4.15)."
3. **§7.4**: add the row below after the last milestone row (the formatter will pad the table).

```markdown
| **M21**   | Plugin GMCP input and events: inbound GMCP routed to the plugin that registered the package (`onGmcp`), with size, depth and rate limits; a read-only `PluginGmcpWorld` whose handlers act through `command`; plugin GMCP event packages (`addGmcpEvent`, `PluginWorld.gmcp`); case-insensitive GMCP name checks and the reserved `Core` root (§4.10, §4.11, §4.15, §4.16, §5.1, §6.4). |
```

### Implementation Blueprint

- **Signatures & Types:** `data class PluginGmcpInfo(val pkg: String, val doc: String, val kind: String)` with kind
  `"state"`, `"event"` or `"input"`; `data class PluginGmcpDocSpec(val pkg: String, val doc: String, val kind: String =
  "state")`.
- **Algorithmic Steps & Invariants:**
  1. `describe` lists all three kinds, sorted by package then kind.
  2. `DocsRenderer` (around line 574): the table gains a `Kind` column between `Package` and `Doc`.
  3. `docs/PLUGINS.md`, `### GMCP packages`: keep the state example; add an event example
     (`r.addGmcpEvent("Gups.Weather.Forecast", …)` then `world.gmcp(actor, "Gups.Weather.Forecast", mapOf(…))`) and an
     input example (`r.onGmcp("Gups.Weather.Get", …) { call -> call.world.gmcp(call.actor, "Gups.Weather.Forecast", …)
     }` and one using `call.command("look")`). State the name rules (case, `Core`, overlaps, own tree), the limits, and
     that handlers can't change the world. Keep code blocks out of lists (see `AGENTS.md`). Mention input handlers in
     the failures paragraph (around line 387).
  4. `DESIGN_NEEDED.md`: change the heading `### GMCP input packages beyond Core.*` to `### GMCP input packages beyond
     Core.* (COMPLETED — M21)`. Do not delete it.
  5. Run `./gradlew markdownFormat`, then regenerate goldens (guardrail 4).

### Test & Documentation Requirements

- The `plugins.html` golden test, regenerated and reviewed. If a `cli` test builds a plugin with GMCP for the docs,
  extend it with one event and one input package so the golden shows all three kinds.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `markdownCheck`.
- [] `grep -n "COMPLETED — M21" docs/DESIGN_NEEDED.md` shows the entry.
- [] `grep -n "M21" docs/SPEC.md` shows the §7.4 row.
- [] End to end: a test plugin's `onGmcp` handler, reached through a telnet `IAC SB GMCP` message in a `net` test or a
  `ClientGmcpEvent` in a `plugin-api` test, queues a command that runs at the next pulse.
