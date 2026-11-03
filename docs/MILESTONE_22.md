# Milestone 22: Starlark GMCP Events

**Prerequisites:** [M21](MILESTONE_21.md) must be finished first. This milestone uses its core name helpers
(`GMCP_PACKAGE_NAME`, `RESERVED_GMCP_ROOTS`, `gmcpOverlaps`, `gmcpWithin` in `core/.../world/Gmcp.kt`) and checks script
packages against plugins' state, event and input packages. Where line numbers below are wrong, find the symbol by name.

## Overview & Agreed Design

- **Source:** there is no `DESIGN_NEEDED` entry. The design came out of the shops design session
  ([DESIGN_NEEDED.md](DESIGN_NEEDED.md): `## Economy and crafting` → `### Shops`): a `//lib:shop.star` library needs to
  send its stock to a client UI. Shops themselves are not part of this milestone.
- **Design Summary:**
  - **Declaration.** `gmcp_package(name, doc = "")` declares a GMCP package a script package sends. Only privileged
    packages (`lib` and `rules`) may declare one, in any of their content files. A zone or skills package that declares
    one fails with "gmcp_package(): only lib and rules packages may declare GMCP packages".
  - **Names.** Two or more dot-separated parts (`GMCP_PACKAGE_NAME`). Compared ignoring case. Script packages may not
    use the roots `Core`, `Char`, `Room`, `Comm` or `Gups`: the bundled web client subscribes to those trees, so a
    script's `Char.Shop` would reach it unasked. Plugins keep their own rules (they may use `Gups`). A declared name may
    not equal, contain or nest in an engine package, any plugin package (state, event or input), another package's
    declared name, or another name of the same package. A cross-package overlap fails every package involved, as a
    shared region does; an overlap with a plugin fails the script package.
  - **Verb.** `ctx.gmcp(player, package, data) -> bool` sends one GMCP event now: queued with the session's text and
    flushed after it, before the prompt, like `Comm.Channel.Text`, and never cached. `package` must equal or nest in a
    name declared by the **caller's package** (the package of the innermost running function, as for attribute writes),
    so zones send through lib helpers. Any other name is a script error that names the declaring package if one exists.
    The verb returns `False`, sending nothing, for a mob, a link-dead player, or a client whose `Core.Supports` doesn't
    cover the package. A target that isn't an actor is a script error. The verb is kernel (no feature).
  - **Payloads.** `None`, ints (as `Long`), strings, bools, lists, and dicts with string keys, nested freely; `None` is
    allowed anywhere, unlike attributes. Floats, sets, views and other values are script errors. The compact JSON
    encoding may be at most **16 KiB** (16384 bytes, UTF-8); larger raises `alloc_limit`. Existing per-string and
    per-collection limits (`checkString`, `checkSize`) apply.
  - **No state packages, no rate cap.** Scripts send events only; a script that wants send-on-change keeps the last
    payload in an attribute. There is no per-pulse cap; step limits bound loops.
  - **Harness.** `w.spawn_player(..., gmcp = [...])` lists the packages the test client supports (default none, so
    existing tests and `Gups.Trace` behave as today). `p.gmcp(package = None)` returns the GMCP messages received since
    the previous call as a list of `{"package": str, "data": value}` dicts, filtered by package prefix (ignoring case)
    when `package` is given, and drains them. `p.do()` and `p.output()` don't change.
  - **Docs.** `gmcp_package` is a documented declaration like any other. `world.html` (with `gups docs --world`, and the
    server's built-in docs) lists each declared package with its doc and declaring package.
  - **Reload.** The checks rerun on every load, reload and deploy staging, like region and namespace checks.
  - **Out of scope:** Starlark receiving GMCP (a later `gmcp_package(on_receive = fn)` may add it on the same names);
    script state packages; a per-pulse cap; `//lib:shop.star`.
- **Specification Impact:** §2.3 (package kinds bullet: privileged packages may declare GMCP packages), §4.2
  (declaration and rules), §4.5 (Messaging row, verb semantics), §4.10 (who may add packages), §4.12 (harness), §4.16
  (declaration count, `world.html` row), §6.1 (the payload cap), §7.4 (new M22 row); `docs/CHANGELOG.md`; the
  regenerated API files.
- **Decision Record Updates:** None.

### Guardrails for every phase

1. Follow `AGENTS.md`: TDD (write tests first and see them fail), no spec section numbers or decision ids in code
   comments, all Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`.
3. Add CHANGELOG bullets in a new `### Starlark GMCP events` subsection at the top of `## api_version 1 (unreleased)`
   (above M21's subsection). Create it in the first phase that needs it.
4. In any phase that changes the script or harness API (a declaration, a `ctx` verb, a harness parameter or method),
   regenerate all three API files **before** the phase's final `./gradlew check`:
   - `./gradlew :cli:test -Dgups.updateGolden=true` rewrites `cli/src/test/resources/api.golden.json` and
     `cli/src/test/resources/docs-golden/api.json`;
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout, rewrites `docs/api/api-1.json`;
   - read the diff: only the symbols this phase adds or changes should differ. `ApiDiffTest` must then pass.
   - if a test fails because a new symbol has no example, add one in `script/.../DocExamples.kt`, mirroring an existing
     entry.
5. Gradle commands must run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that
   code blocks kept their indentation.
6. No database migrations.
7. Never read or touch `local-world/`.

---

## Phase 1: Converting script values to GMCP payloads

### Objective & Scope

- **In Scope:** a converter from Starlark values to plain Kotlin values with the payload rules and the 16 KiB cap.
- **Out of Scope:** any declaration or verb.

### Context Working Set

- **Read / Reference Only:** `script/src/main/kotlin/gups/script/Values.kt`: `toAttr` (line ~128), `checkString`,
  `checkSize`, `limitFail`, `jsonSize` (line ~188); `ScriptLimits`.
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Values.kt`
  - New `script/src/test/kotlin/gups/script/GmcpValuesTest.kt`
- **Do Not Touch:** everything else.

### Specification & Decision Record Changes

None in this phase (Phase 4 documents the rules with the verb).

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  /** Largest GMCP payload a script may send, as compact UTF-8 JSON. */
  internal const val MAX_GMCP_PAYLOAD_BYTES = 16_384

  /**
   * [v] as a plain GMCP value (null, Long, String, Boolean, List, Map<String, Any?>). `None` is
   * allowed anywhere. Raises `alloc_limit` when the JSON form is over [MAX_GMCP_PAYLOAD_BYTES].
   */
  internal fun toGmcp(v: Any?, what: String, limits: ScriptLimits): Any?

  /** The UTF-8 size of [v]'s compact JSON form. */
  internal fun gmcpJsonSize(v: Any?): Int
  ```
- **Algorithmic Steps & Invariants:**
  1. Mirror `toAttr`'s `when`: `None`/null → null; `StarlarkInt` → `Long` (`toLong(what)`); `String` → `checkString`;
     `Boolean`; `Sequence` → `checkSize` then a `List`, elements converted recursively (None allowed); `Dict` →
     `checkSize`, string keys only (same message as `toAttr`), values converted recursively. `StarlarkFloat` → "floats
     can't be sent; use an int (for example, tenths)". Anything else → "a <type> can't be sent; use an int, string,
     bool, list, dict or None".
  2. After converting the whole value, `if (gmcpJsonSize(result) > MAX_GMCP_PAYLOAD_BYTES) limitFail("alloc_limit",
     "$what: payload of N bytes is over the 16 KB limit")`.
  3. `gmcpJsonSize`: `null` 4, booleans 4/5, longs their decimal length, strings 2 plus the UTF-8 byte length of each
     character with JSON escaping (`"` and `\` add 1; control characters below 0x20 become `\u00XX`, 6 bytes; `\n`,
     `\t`, `\r`, `\b`, `\f` are 2), lists 2 plus elements plus commas, maps 2 plus `key:value` pairs (key as a string)
     plus commas. It must equal `kotlinx.serialization`'s compact output for the same value; the test checks that.
     (`script`'s test classpath may not have kotlinx.serialization; if not, compare against hand-written expected
     strings instead.)

### Test & Documentation Requirements

- `GmcpValuesTest.kt`: `` `converts nested values and allows None` `` (`{"a": [1, None, {"b": True}]}`); `` `floats are
  refused` ``; `` `dict keys must be strings` ``; `` `views are refused` `` (a struct or a frozen view; follow an
  existing `toAttr` test for how to make one); `` `a payload over 16 KB raises alloc_limit` `` and 16384 bytes exactly
  is fine; `` `the size counts escapes and multibyte characters` ``.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `./gradlew :script:test --tests '*GmcpValuesTest*'` passes.

---

## Phase 2: The `gmcp_package` declaration

### Objective & Scope

- **In Scope:** the declaration builtin, its `DeclSpec`, per-package checks (privileged kinds, name pattern, reserved
  roots, overlaps within the package), storage on `ScriptPackage`, docs example.
- **Out of Scope:** cross-package and plugin checks (Phase 3), the verb (Phase 4).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Declarations.kt`: `region(...)` (around line 1187) and `REGION_SPEC` as the
    pattern; `package(shared_namespaces)` (around line 632) for the privileged-only check and its message.
  - `script/src/main/kotlin/gups/script/Packages.kt`: `ScriptPackage` (`regionList` / `regions`, line ~149/186),
    `PackageKind` (`LIB("lib", true)`, line ~77).
  - `script/src/main/kotlin/gups/script/DocExamples.kt`: `LIB_SYMBOLS` (line 41).
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Declarations.kt`
  - `script/src/main/kotlin/gups/script/Packages.kt`
  - `script/src/main/kotlin/gups/script/DocExamples.kt`
  - `script/src/test/kotlin/gups/script/LoaderTest.kt` (or a new `GmcpPackageTest.kt` in the same directory)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API files
- **Do Not Touch:** `core/`, `plugin-api/`, `cli/` main code, `harness/`.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§4.2**, in the example block after the `region(...)` line, add: `gmcp_package("Shop.Stock", doc="Items for sale
   where the player stands.") # lib and rules only (§4.10)`.
2. **§4.2 declaration rules**, after the **Regions** bullet, add:
   - **GMCP packages:** `gmcp_package(name, doc = "")` declares a GMCP package the package sends with `ctx.gmcp`. Only
     `lib` and `rules` packages may declare one. A name has two or more dot-separated parts, may not use the roots
     `Core`, `Char`, `Room`, `Comm` or `Gups`, and may not equal, contain or nest in an engine package, a plugin's
     package, or another declared name, compared ignoring case. Two packages whose names overlap both fail to load.
3. **§2.3, "Package kinds" bullet**: "They may declare shared attribute namespaces and global commands (§4.9)." becomes
   "They may declare shared attribute namespaces, global commands (§4.9) and GMCP packages (§4.10)."
4. **§4.16**: "the 13 package declarations" becomes "the 14 package declarations" (check the current count in the
   sentence and in `ApiModel` first; add one to whatever it is).

### Implementation Blueprint

- **Signatures & Types:**

  `Packages.kt`:

  ```kotlin
  /** A GMCP package a lib or rules package sends with `ctx.gmcp`. */
  data class GmcpPackageDecl(val name: String, val doc: String, val pkg: String)
  ```

  `ScriptPackage`: `internal val gmcpList = mutableListOf<GmcpPackageDecl>()` and `val gmcpPackages:
  List<GmcpPackageDecl> get() = gmcpList`, mirroring `regionList`/`regions`.

  `Declarations.kt`: `@StarlarkMethod(name = "gmcp_package", …) fun gmcpPackage(args: Tuple, kwargs: Dict<String, Any>,
  thread: StarlarkThread): Any` and `GMCP_PACKAGE_SPEC` with `name` (positional, required) and `doc` (default `""`).

  Somewhere in `script` (next to the declaration):

  ```kotlin
  /** Roots script packages may not send under: engine trees the bundled client subscribes to. */
  internal val SCRIPT_RESERVED_GMCP_ROOTS = setOf("Core", "Char", "Room", "Comm", "Gups")
  ```
- **Algorithmic Steps & Invariants:**
  1. `contentCtx(thread, "gmcp_package")`, bind the spec, then in order:
     - kind not privileged → `fail("gmcp_package(): only lib and rules packages may declare GMCP packages")` (check how
       `shared_namespaces` reads the kind: `PackageKind.privileged`).
     - `!GMCP_PACKAGE_NAME.matches(name)` → "gmcp_package(): '$name' needs at least two dot-separated parts, such as
       Shop.Stock".
     - a reserved root `r` with `gmcpWithin(name, r)` → "gmcp_package(): '$name' uses the reserved root $r".
     - `ENGINE_GMCP_PACKAGES.firstOrNull { gmcpOverlaps(name, it) }` → "... overlaps the engine's $it".
     - this package's earlier declarations (`gmcpOverlaps`) → "... overlaps '$other', already declared here".
     - append `GmcpPackageDecl(name, doc, ctx.pkg.path)`; return `Starlark.NONE`.
  2. `DocExamples.kt`: add `"gmcp_package"` to `LIB_SYMBOLS` and an example such as `gmcp_package("Shop.Stock", doc =
     "Items for sale where the player stands.")`, mirroring the `global_command` example.
  3. CHANGELOG bullet: "Added `gmcp_package(name, doc)` for `lib` and `rules` packages."
  4. Regenerate the API files (guardrail 4): expect only the new declaration.

### Test & Documentation Requirements

- Loader tests (follow the shape of the existing region tests in `LoaderTest.kt`): `` `a lib package declares a GMCP
  package` ``; `` `a zone may not declare a GMCP package` ``; `` `a name needs two parts` ``; `` `reserved roots are
  refused` `` (`Gups.Shop`, `char.shop`); `` `a name may not overlap an engine package` `` (`Comm.Channel.Text.X`); ``
  `two names in one package may not nest` ``.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `ApiDiffTest`.
- [] `grep -n '"gmcp_package"' cli/src/test/resources/api.golden.json` finds the declaration.

---

## Phase 3: Cross-package and plugin name checks

### Objective & Scope

- **In Scope:** a loader check across packages; plugin GMCP names reaching the loader through `WorldPluginWiring`; the
  call sites that build a `ScriptLoader` pass them.
- **Out of Scope:** the verb; docs.

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Loader.kt`: the constructor (line ~110), `checkNamespaces`/`checkRegions` (lines
    ~569–620) and where they are called (line ~460).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`: the class implementing `WorldPluginWiring` (line
    ~387) and the plugins' registrations (`reg.gmcp`, `reg.gmcpEvents`, `reg.gmcpInputs` from M21).
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/WorldPluginWiring.kt`
  - `script/src/main/kotlin/gups/script/Loader.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`
  - The loader call sites that pass only `pluginGlobals`: `script/.../Host.kt` (line ~443, `stage`),
    `cli/.../GupsServer.kt` (line ~229), `cli/.../Commands.kt` (line ~510), `cli/.../MigrationChecker.kt` (line ~50),
    `harness/.../Repro.kt` (line ~46)
  - Tests: `script/src/test/kotlin/gups/script/LoaderTest.kt` (or the Phase 2 test file), and a `plugin-api` test
- **Do Not Touch:** `core/`, `net/`. Don't switch those call sites to `plugins = …`: that would also turn on the trait
  check there, which is out of scope.

### Specification & Decision Record Changes

None beyond Phase 2's wording, which already states the cross-package and plugin rules.

### Implementation Blueprint

- **Signatures & Types:**

  `WorldPluginWiring`:

  ```kotlin
  /** Every GMCP package plugins registered: state, event and input. */
  val gmcpPackages: Set<String>
    get() = emptySet()
  ```

  `ScriptLoader` constructor, after `knownTraits`: `val knownGmcp: () -> Set<String> = { plugins.gmcpPackages }`.

- **Algorithmic Steps & Invariants:**

  1. `PluginHost`'s wiring implements `gmcpPackages` as the union of every loaded plugin's state, event and input
     package names.
  2. `Loader.kt`: add `fun checkGmcp()` next to `checkRegions`, called right after it:
     - collect `(decl, pkgPath)` from every live package;
     - for each pair from **different** packages whose names `gmcpOverlaps`, add a `LoadError(p, "gmcp", "GMCP package
       '<a>' of //<pa> overlaps '<b>' of //<pb>")` to both packages (mirror `checkRegions`' error shape and kind
       naming);
     - for each declaration overlapping a name in `knownGmcp()`, add `LoadError(p, "gmcp", "GMCP package '<a>' overlaps
       the plugin package '<b>'")` to the declaring package only.
  3. At each listed call site, add `knownGmcp = { plugins.gmcpPackages }` next to `pluginGlobals = plugins.globals` (for
     `Host.stage`, `knownGmcp = loader?.knownGmcp ?: { emptySet() }`). Call sites that already pass `plugins = plugins`
     (`Linter.kt`, `FuzzWalker.kt`, `Runner.kt`) get it by default.
  4. Invariant: reload and deploy staging run the same check (they go through `ScriptLoader.load`).

### Test & Documentation Requirements

- `` `two packages declaring overlapping GMCP packages both fail` `` (a `lib` and a `rules` package; `Shop` and
  `shop.stock`).
- `` `a declaration overlapping a plugin package fails the script package` `` (pass `knownGmcp = { setOf("Shop.Buy")
  }`).
- A `plugin-api` test that the wiring's `gmcpPackages` lists a test plugin's state, event and input packages.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `grep -n "knownGmcp" cli/src/main/kotlin/gups/cli/GupsServer.kt harness/src/main/kotlin/gups/harness/Repro.kt`
  finds both call sites.

---

## Phase 4: `ctx.gmcp`

### Objective & Scope

- **In Scope:** the verb with the caller-package ownership check, payload conversion and delivery.
- **Out of Scope:** harness support (Phase 5). Tests here use a GMCP-capable sink directly.

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`: `verb()` (line ~111), `pkgOf` (line ~139), `send` (line ~422),
    `attrKey` (line ~1450, the pattern for finding the caller's package via `frame.host.scripts.packages[caller]` and
    naming another declaring package).
  - `core/src/main/kotlin/gups/core/world/Gmcp.kt`: `World.gmcp(p, pkg, data)`.
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`
  - New `script/src/test/kotlin/gups/script/GmcpVerbTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API files
- **Do Not Touch:** `core/` main code, `harness/`, `cli/` main code.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§4.5 verb table, Messaging row**: append `` `gmcp(player, package, data)` ``.
2. **§4.5 verb semantics**, add a bullet after **Errors**:
   - **`gmcp`:** sends the GMCP event `package` with `data` to `player`, after its pending text and before the prompt,
     and returns whether it was sent. `package` must be, or nest in, a GMCP package the calling package declared (§4.2);
     zones send through `lib` helpers. It returns `False` for a mob, a link-dead player, or a client that didn't ask for
     the package. `data` may hold `None`, ints, strings, bools, lists and dicts with string keys; its JSON may be at
     most 16 KB.
3. **§4.10**, the line "Plugins may add state and event packages, and receive GMCP input (§4.15)." (from M21): append "
   `lib` and `rules` packages may declare event packages (§4.2) and send them with `ctx.gmcp` (§4.5)."
4. **§6.1**: add the 16 KB GMCP payload limit next to the other allocation limits, as an `alloc_limit` failure (follow
   the section's existing wording).

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  @StarlarkMethod(
    name = "gmcp",
    doc = "Sends a GMCP event to a player's client. Returns whether it was sent.",
    parameters = [
    Param(name = "player", named = true, doc = "Actor who receives the event; mobs receive nothing."),
    Param(name = "package", named = true, doc = "A GMCP package this package declared, or one nested in it."),
    Param(name = "data", named = true, doc = "None, int, str, bool, list or dict (16 KB of JSON at most)."),
    ],
    useStarlarkThread = true,
  )
  fun gmcp(player: Any, `package`: Any, data: Any, thread: StarlarkThread): Any
  ```
- **Algorithmic Steps & Invariants:**
  1. `verb("gmcp", detail = pkgName) { … }`.
  2. `val a = actorArg(player, "gmcp(player)")`; ``val pkgName = str(`package`, "gmcp(package)")``;
     `require(GMCP_PACKAGE_NAME.matches(pkgName))` with "'$pkgName' is not a GMCP package name".
  3. Caller ownership: `val caller = pkgOf(thread)`; `val declared = frame.host.scripts.packages[caller]?.gmcpPackages
     .orEmpty()`. If none `gmcpWithin(pkgName, it.name)`, find `frame.host.scripts.packages.values` with a matching
     declaration and fail "gmcp(package): code in //$caller didn't declare a GMCP package for '$pkgName'" plus " (use
     the helpers exported by //<declarer>)" when one exists. Mirror `attrKey`'s message shape.
  4. `val payload = toGmcp(data, "gmcp(data)", frame.limits)` (use whatever `toAttr` callers in `Ctx.kt` pass for
     limits).
  5. `if (a !is Player) return false`; else `world.gmcp(a, pkgName, payload)`. Return a Starlark bool.
  6. CHANGELOG bullet for `ctx.gmcp`. Regenerate the API files.

### Test & Documentation Requirements

- `GmcpVerbTest.kt`, built on `ScriptFixture` with a `lib` package declaring `gmcp_package("Shop.Stock")` and exporting
  a helper that calls `ctx.gmcp`, and a zone hook calling the helper. Attach a session whose sink wants `Shop` and
  records GMCP (a small test `OutputSink`; see `TlsGmcpTest` for a recording sink). Cases:
  - `` `a lib helper sends under its declared package` `` (the zone hook calls it; the sink records `("Shop.Stock",
    data)`; the hook sees `True`).
  - `` `a nested name is allowed` `` (`Shop.Stock.Delta`).
  - `` `a zone calling ctx.gmcp directly fails and names the lib` ``.
  - `` `an undeclared package fails` ``.
  - `` `mobs, link-dead players and unsupported clients get False` ``.
  - `` `None is allowed inside lists and dicts` `` and `` `floats raise a script error` ``.
  - `` `the event goes out after the hook's text` `` (follow how `Comm.Channel.Text` ordering is tested in core).

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `ApiDiffTest`.
- [] `./gradlew :script:test --tests '*GmcpVerbTest*'` passes.

---

## Phase 5: Harness support

### Objective & Scope

- **In Scope:** `CapturingSink` records GMCP for the packages it supports; `spawn_player(gmcp = [...])`;
  `PlayerHandle.gmcp(package = None)`.
- **Out of Scope:** docs (Phase 6).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/Seams.kt`: `CapturingSink` (line ~122), `OutputSink.wantsGmcp`.
  - `harness/src/main/kotlin/gups/harness/Handles.kt`: `spawn_player` (line ~133), where the sink is made (line ~250),
    `do`/`output` (lines ~617–641).
  - `script/src/main/kotlin/gups/script/Values.kt`, `fromAttr` (to turn plain values back into Starlark values).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/Seams.kt`
  - `harness/src/main/kotlin/gups/harness/Handles.kt`
  - A harness test (find the existing ones with `grep -rln "spawn_player" harness/src/test`)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API files
- **Do Not Touch:** `script/` main code except a conversion helper if one is needed, `cli/` main code.

### Specification & Decision Record Changes

Edit `docs/SPEC.md` §4.12: add `gmcp = [...]` to the `spawn_player` entry ("the GMCP packages the test client supports;
none by default") and a `p.gmcp(package = None)` entry ("the GMCP messages received since the last call, as `{"package",
"data"}` dicts, optionally only those in `package`'s tree"). Follow the section's existing table or list format.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  class CapturingSink(
    /** GMCP packages this sink supports, as a client's Core.Supports would list them. */
    private val supports: Set<String> = emptySet(),
  ) : OutputSink {
    override fun wantsGmcp(pkg: String): Boolean
    override fun gmcp(pkg: String, data: Any?)
    /** Drains and returns the GMCP messages received since the previous call. */
    fun drainGmcp(): List<Pair<String, Any?>>
  }
  ```
- **Algorithmic Steps & Invariants:**
  1. `wantsGmcp`: `supports.any { gmcpWithin(pkg, it) }` (core helper from M21). `gmcp` appends to a list.
  2. `spawn_player` gains `gmcp` (a list of strings, default empty) and passes it to the sink it creates. Validate each
     entry with a clear `spawn_player(gmcp)` error if it isn't a string.
  3. `PlayerHandle.gmcp(package = None)`: drain the sink, convert each message to a frozen Starlark dict `{"package":
     pkg, "data": value}` (plain → Starlark: Long → `StarlarkInt`, List → list, Map → dict, null → `None`), keep those
     with `gmcpWithin(pkg, package)` when `package` is given, and return them as a list. Messages filtered out are still
     drained.
  4. Invariant: existing harness tests pass unchanged, including `Gups.Trace` behavior (no supports means no GMCP).
  5. CHANGELOG bullets for both harness additions. Regenerate the API files.

### Test & Documentation Requirements

- A harness test (Kotlin running a Starlark test file, following the existing harness tests): a `lib` package declares
  `Shop.Stock` and exposes a helper; a zone command calls it; the Starlark test spawns `p = w.spawn_player("a", gmcp =
  ["Shop"])`, runs the command, and asserts `p.gmcp("Shop") == [{"package": "Shop.Stock", "data": {...}}]` and that a
  second `p.gmcp()` is empty. A player spawned without `gmcp` receives nothing.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `ApiDiffTest`.
- [] `./gradlew :harness:test` passes.

---

## Phase 6: Docs listing and finalization

### Objective & Scope

- **In Scope:** `world.html` lists script GMCP packages; §4.16 and §7.4.
- **Out of Scope:** `//lib:shop.star` and any template-world content.

### Context Working Set

- **Read / Reference Only:** `cli/src/main/kotlin/gups/cli/Commands.kt`: the docs command (around lines 712–735) and
  `worldDocSpec` (line ~1102); `cli/src/main/kotlin/gups/cli/GupsServer.kt` (around lines 205–232), where the server
  renders its docs before loading packages.
- **Files to Modify / Create:**
  - `cli/src/main/kotlin/gups/cli/DocsRenderer.kt`
  - `cli/src/main/kotlin/gups/cli/Commands.kt`
  - `cli/src/main/kotlin/gups/cli/GupsServer.kt`
  - The `world.html` golden (if one exists) and the `cli` docs tests for `--world` (`grep -rln "world.html"
    cli/src/test`)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `script/`, `core/`, `plugin-api/` main code.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§4.16, `world.html` row**: after "its features and renamed commands", add ", and the GMCP packages its `lib` and
   `rules` packages declare (name, doc and declaring package)".
2. **§7.4**: add the row below after the M21 row (the formatter will pad the table).

```markdown
| **M22**   | Starlark GMCP events: `gmcp_package()` for `lib` and `rules` packages with reserved roots and world-wide overlap checks, `ctx.gmcp(player, package, data)`, GMCP payload values and the 16 KB cap, harness `spawn_player(gmcp=)` and `p.gmcp()`, `world.html` listing (§2.3, §4.2, §4.5, §4.10, §4.12, §4.16, §6.1). |
```

### Implementation Blueprint

- **Signatures & Types:** `data class ScriptGmcpDocSpec(val pkg: String, val doc: String, val declaredBy: String)`;
  `WorldDocSpec` gains `val gmcp: List<ScriptGmcpDocSpec> = emptyList()`.
- **Algorithmic Steps & Invariants:**
  1. `gups docs --world`: inside the block that loads plugins (`loadPlugins(...).use { … }`), also run
     `ScriptLoader(worldDir, ScriptLimits(), plugins = it).load(worldDir.packages())` and collect `gmcpPackages` from
     the packages that loaded (find the accessor on the load result; `result.prototypes()` is one nearby). Packages that
     fail are left out silently. Pass them to `worldDocSpec` (new optional parameter).
  2. `GupsServer`: render the docs after `loader.load(packages)` so the same list is available; keep everything else in
     that block in its current order.
  3. `DocsRenderer`: on `world.html`, when `gmcp` is not empty, a `<h2>GMCP packages</h2>` table with `Package`, `Doc`
     and `Declared by` columns, sorted by package, mirroring the plugins GMCP table's markup.
  4. CHANGELOG bullet for the docs listing. Run `./gradlew markdownFormat`.

### Test & Documentation Requirements

- A `cli` docs test with a world that has a `lib` package declaring `Shop.Stock`: `world.html` contains the table row.
  Regenerate goldens if the default world's output changed (it shouldn't: the template declares none).

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `markdownCheck`.
- [] `grep -n "M22" docs/SPEC.md` shows the §7.4 row.
- [] End to end: a harness test where a zone command calls a lib helper that sends `Shop.Stock`, and the test reads it
  with `p.gmcp("Shop")`.
- [] No `DESIGN_NEEDED` entry to mark: this milestone has none. Leave `### Shops` unchanged; a later milestone resolves
  it.
