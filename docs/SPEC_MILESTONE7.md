# Milestone 7 — Design

Status: draft for review. Open questions Q1–Q10 are settled and M7.1 is implemented. The
other proposals, including the implementation chunks of §3–§6, await review.

This document extends [SPEC.md](SPEC.md) §7.4, where M7 is one row: "`gups docs`,
`MIGRATING.md`, `plugin-api` with one sample plugin."

Like [SPEC_MILESTONE6.md](SPEC_MILESTONE6.md), it splits the milestone into stages that land
and get reviewed on their own, and settles the details SPEC.md leaves open. Where SPEC.md
already decides something, this document only points to it. Where it doesn't, the design here
is a **proposal**: once accepted, it is as binding as SPEC.md. Each stage ends with the
questions that were settled for it, numbered Q1–Q10.

The usual rules still apply. Choices made while implementing that neither document settles go
in [DECISIONS.md](DECISIONS.md), and features this design leaves out go in
[DESIGN_NEEDED.md](DESIGN_NEEDED.md).

---

## 1. Stages at a Glance

| Stage | Scope | Depends on | Done when |
|---|---|---|---|
| **M7.1** One description of the script API | A machine-readable model of every builtin, declaration, verb, view, hook and effect primitive, and `api.json` | — | `api.json` covers the whole API, and a test fails if any builtin is missing from it |
| **M7.2** `gups docs` | The HTML reference tagged with `api_version`, with the changelog | M7.1 | `gups docs --out DIR` writes a browsable reference of the whole API |
| **M7.3** Changelog and migration artifacts | A structured changelog, the generated `MIGRATING.md`, a machine-readable change list, and a CI check that API changes are recorded | M7.1, M7.2 | Changing a builtin without a changelog entry fails engine CI |
| **M7.4** The plugin API | `GupsPlugin`, `PluginRegistry`, loading from `plugins/`, and failure handling (§6.4) | M7.1 | A plugin jar adds a builtin, a command, an effect primitive and a GMCP package |
| **M7.5** The sample plugin | One sample plugin using every extension point, plus the engine-developer guide | M7.2, M7.4 | The sample builds, loads in `serve` and in `gups test`, and its tests pass |

M7.4 can be built alongside M7.2 and M7.3. M7.3 needs M7.2's `DocsRenderer` and bundled
changelog. M7.5 needs M7.2's `plugins.html` page.

### 1.1 Notes for the implementing agent

These apply to every chunk in §3–§6.

- **Read first:** [AGENTS.md](../AGENTS.md), [README.md](../README.md), and the whole section
  for your stage. The design sections (`x.1`, `x.2`, …) define the behavior; the chunks
  (`x.n Implementation chunks`) only order the work.
- **One chunk at a time.** Finish each chunk with `./gradlew check` passing (all Kotlin warnings
  are errors, and `checkModuleGraph` runs too) before you start the next.
- **The code wins over this document's names.** The chunks name real classes, functions and
  files as of M7.1. If one doesn't exist or has a different signature, adapt to the code, don't
  build a parallel copy that matches the text, and log what you did in `DECISIONS.md`.
- **Find every caller before you change a signature** (`grep -rn`). Add new constructor and
  function parameters at the end with a default, and pass them by name.
- **Log decisions** this document doesn't settle in `docs/DECISIONS.md`, as AGENTS.md says. One
  entry per chunk, with bullets (like D-171), is fine. If you change behavior an existing entry
  describes, update that entry. Don't edit `docs/SPEC.md` or this file.
- **Never delete or weaken an existing test** to make a chunk pass, unless a chunk tells you to.
- **Deterministic output:** sort everything, use `\n` line endings and UTF-8, and put no
  timestamps, absolute paths or hash-map iteration order in generated files.
- **Golden files:** regenerate them only with `-Dgups.updateGolden=true`, and read the diff
  before you commit it.

---

## 2. M7.1 — One Description of the Script API

**Goal:** a single model of the script API that the docs, the changelog check and plugins are
all built from, so they can't drift apart.

### 2.1 The problem

§4.13 says `gups docs` generates the reference "from `@StarlarkBuiltin`/`@StarlarkMethod`
annotations". That works for `ctx` verbs, views and effect primitives, whose parameters are
annotated. It doesn't work for the content declarations (`room`, `mob`, `item`, `skill`,
`exit`, `door`, `package`, `region`, `spawn`, `loot`, `container`): they take `**kwargs` and
read them by hand (D-070), so their annotations list no parameters. Nor does it cover the hook
catalog (§4.4), the rules exports (§4.6), the modifier points, the behavior arguments, or the
harness API (§4.12).

### 2.2 Design

**Proposal** (the declaration specs are settled, Q1):
- **Declaration specs:** each declaration gets a `DeclSpec` in `script`: its parameters, with
  their type, default, allowed values, a doc line, and the milestone or `api_version` that
  added them.
  - The declaration builtins parse their keyword arguments through the spec instead of by
    hand.
  - Unknown-argument errors and spelling suggestions come from the spec, so parsing and docs
    share one source.
  - This replaces most of `Kw` (D-070).
- **Other entries:** the remaining parts of the API are described by small Kotlin tables next
  to the code that implements them:
  - the hook catalog (`HookCatalog` already is one, extended with ctx fields and docs);
  - the rules exports, with their signatures (§4.6);
  - the modifier points;
  - the constants (`CRAMPED`, `ALL`, …);
  - the harness objects (`t`, `w`, `p`).
- **Annotated builtins:** `ctx`, views, the rules ctx and effect primitives keep their
  `@StarlarkMethod` annotations, which are read by reflection.
- **`api.json`:** a canonical, sorted JSON dump of the whole model, tagged with
  `api_version` and the engine version. `gups docs --json` writes it, and it is the input to
  M7.2 and M7.3.
- **Completeness test:** every global a package file sees (`PACKAGE_GLOBALS`), every method
  of every `@StarlarkBuiltin` type, and every hook name must appear in the model with a doc
  string. A missing doc string fails the build.

### 2.3 Tests

- `api.json` is identical across runs and platforms.
- The completeness test above.
- Every declaration accepts exactly its spec's parameters, and the spelling suggestions still
  work (the existing loader tests keep passing).

### 2.4 Settled questions

- **Q1 (settled).** The declarations are refactored onto `DeclSpec`, which drives both their
  parsing and their docs (§2.2).

### 2.5 What M7.1 left for later stages

Implemented as D-171. Later stages build on these facts:
- `ScriptApi.model(HarnessApi.specs())` builds the `ScriptApiModel`; `model.toJson()` writes
  `api.json`, and `model.symbols()` lists every entry's symbol (`room`, `room.size`,
  `damage.dice`, `ALL`, `hook:on_wake`, `ctx.act_move`, `ctx.apply_affect.name`,
  `view:room.exits`, `rules:to_hit`, `modifier:to_hit`, `rules_ctx.weapon`, `t.eq`,
  `module.start_room`).
- `cli/src/test/resources/api.golden.json` is a copy of `api.json` with `engine_version` set to
  `"<engine>"`. `CommandsTest` compares the live model with it on every CI platform.
- `gups docs --json [--out PATH]` works. `gups docs` without `--json` is still a `StubCommand`
  that exits 3.
- `:script` has no JSON parser; `ApiModel.kt` writes JSON by hand.

---

## 3. M7.2 — `gups docs`

**Goal:** the reference that §1.3 and §5.13 promise, generated from the M7.1 model.

### 3.1 Output

`gups docs [--out DIR] [--world DIR]` writes the HTML reference into `DIR` (default
`build/docs`, relative to the working directory). `gups docs --json [--out PATH]` keeps its
M7.1 behavior (D-171).

The reference is the files listed in `DocsRenderer.FILES`, in this order:

| File | Contents |
|---|---|
| `style.css` | The one stylesheet every page links. Dark theme matching `webclient/style.css`: `#111418` background, `#1a1f25` panels, `#d8dee4` text, `#4ea1ff` accents, monospace code. |
| `index.html` | Overview: `api_version`, engine version, a table linking every other page with its entry count, a link to `api.json`, and a link to `changelog.html#api_version-<N>` for the current version. |
| `declarations.html` | `model.declarations`, then `model.settings` (`module`, `server`), then `model.constants`. |
| `hooks.html` | `model.hooks` (§4.4): a summary table, then one section per hook with its signature, whether it is vetoable (linking `ctx.html#ctx.deny` when it is), what it attaches to, and its ctx fields, each linking `ctx.html#ctx.<field>`. |
| `ctx.html` | `model.ctx`, then every view in `model.views`. |
| `effects.html` | `model.effects`, then `model.behaviors`. |
| `rules.html` | `model.rules.ctx` (`rules_ctx`), then `model.rules.exports` and `model.rules.modifierPoints`, each with its signature, return or value type, and ctx fields linking `rules.html#rules_ctx.<field>`. |
| `harness.html` | `model.harness` (`t`, `w`, `p`, `diagnostic`). |
| `plugins.html` | The world's plugins (`--world`, §3.1 "Plugins"), or the line "No plugins are loaded." M7.5 adds the engine-developer guide here. |
| `changelog.html` | `docs/CHANGELOG.md` rendered with `Markdown.toHtml`. M7.3 replaces this with a structured rendering. |
| `api.json` | `model.toJson()`, byte for byte what `gups docs --json` writes. |

Every entry shows its name, `since`, doc string, its example if it has one, and its
parameters, fields and methods as tables. A declaration parameter's row shows name, type,
positional/keyword, required, default, allowed values and doc. A method parameter's row shows
name, positional/named, default and doc.

**Page rules:**
1. Every `.html` file starts with `<!DOCTYPE html>`, then `<html lang="en">`, and its `<head>`
   has `<meta charset="utf-8">`, `<meta name="viewport" content="width=device-width, initial-scale=1">`,
   `<title><Page> — GUPS API Reference (api_version <N>)</title>` and
   `<link rel="stylesheet" href="style.css">`.
2. Every page starts its `<body>` with the same `<header>`: the engine version, an
   `api_version <N>` badge, and a `<nav>` linking every `.html` file in `FILES` plus `api.json`.
3. No `<script>` element anywhere, and every `href`/`src` is a relative link to a file in
   `FILES`, optionally with a `#fragment`. The pages work offline and from `file://`.
4. All text from the model, the changelog or a plugin is HTML-escaped (`&` → `&amp;`,
   `<` → `&lt;`, `>` → `&gt;`, `"` → `&quot;`) before it is written.
5. Output is deterministic and uses `\n` line endings. Entries appear in model order (already
   sorted by M7.1).

**Anchors:** every symbol in `model.symbols()` has an element (`<section>`, `<article>` or
`<tr>`) with `id="<id>"`, where `<id>` is `symbol.replace(':', '-')` (`hook:on_enter` →
`hook-on_enter`). The page a symbol lives on is the page of the model list that contains it,
so `DocsRenderer.symbolHref(model, symbol)` builds its symbol → page map from the model. A
bare symbol such as `damage` can't be placed by its spelling alone.

**Plugins:** `DocsRenderer.render` takes a `plugins: List<PluginDocSpec>` argument. M7.2 always
passes an empty list, and M7.4 fills it from the world's plugins. Each plugin gets a
`<section id="plugin-<id>">` on `plugins.html` listing its builtins, effect primitives, commands
and GMCP packages, each marked with `<span class="badge plugin">plugin: <id></span>` and
anchored `plugin-<id>.<name>`. Plugin entries are not part of `api.json`.

**Examples (`DocExamples`):** an entry may carry a short example. The examples are run as
tests, so they can't rot.
- Examples live in one table, `DocExamples.ALL: Map<String, DocExample>` in
  `script/src/main/kotlin/gups/script/DocExamples.kt`, keyed by symbol. They are not fields on
  the model and are **not** written to `api.json`, so editing an example never changes
  `api.golden.json` or (M7.3) needs a changelog entry.
- `data class DocExample(val pkg: String = "", val test: String = "")`: `pkg` is Starlark for a
  zone package's content file, `test` is the body of a harness test function. At least one is
  non-blank. The page shows `pkg`, then `test` under the caption "Test", each in
  `<pre><code>`.
- **Required examples:** every declaration (`model.declarations`), every constant, every effect
  primitive, every behavior and every harness type (`t`, `w`, `p`, `diagnostic`). Examples for
  hooks, `ctx`, views, rules entries and `module` are welcome but optional. `server` examples
  aren't supported (the runner below can't execute them).
- **How an example runs (§3.3 chunk 2):** in a temp world holding `MODULE.gups`, a copy of
  `template-world/rules/`, and package `zones/ex` with:
  - `package.star`: `package(api_version = 1, kind = "zone")`;
  - `fixture.star`: `DocExamples.FIXTURE`, a fixed snippet declaring `room("start", ...)`;
  - `example.star`: the example's `pkg`;
  - `ex_test.star`: `def test_example(t):` followed by the example's `test` indented four
    spaces, or `    t.world(seed = 1)` when `test` is blank (so the package must at least load).

  For the `module` symbol, `pkg` is written as `MODULE.gups` instead of `example.star`. The
  example passes when `TestRunner(...).run(listOf("//zones/ex"))` reports no problems, every
  result is `Status.PASS`, and no result has warnings (warnings are the script diagnostics the
  test's worlds recorded). An example must not redeclare the fixture's `start` room.

`Markdown.toHtml(md)` in `cli/src/main/kotlin/gups/cli/Markdown.kt` renders the changelog and,
in M7.5, `docs/PLUGINS.md`. It supports only this subset, and escapes all text first (no raw
HTML passes through):
- `#` to `####` headings, as `<h1>`–`<h4>` with an `id`: `api_version-<N>` for a heading whose
  text is `api_version <N>` or `api_version <N> (unreleased)`, otherwise a slug (lowercase, each
  run of characters other than `a-z0-9` becomes `-`, trimmed of `-`), with `-2`, `-3`, … added
  to repeats;
- paragraphs (lines separated by blank lines);
- `- ` bullet lists;
- fenced code blocks (```` ``` ```` with any info string) as `<pre><code>`;
- inline `` `code` `` and `**bold**`;
- `[text](url)` renders as `text` only, so links can't point outside the reference.

### 3.2 Where the docs go

- **Releases:** `:cli:docsArchive` writes `cli/build/distributions/gups-docs-<version>.zip`,
  whose files sit under a top-level `gups-docs-<version>/` directory. CI attaches it to GitHub
  releases next to the image tarballs.
- **Images:** `:cli:generateDocs` runs `gups docs` into `cli/build/generated-docs`.
  `installDist` copies that into `cli/build/install/gups/docs/`, so the OCI image (whose
  Dockerfile copies the install directory to `/opt/gups`) has `/opt/gups/docs/`, and
  `jlinkImage` copies it from the install directory into `build/image/gups/docs/`.
- **The server:** both profiles serve the reference with no login (Q2), rendered once at
  startup with the world's plugins (from M7.4):
  - `--prod` serves it on the M6.4 `--https` listener (`WebServer`) at `https://host/docs/`. No
    `--https`, no docs.
  - `--dev` serves it over plain HTTP at `http://127.0.0.1:4080/docs/`. `--docs-port PORT`
    changes the port (`0` picks a free one). `--docs-port` with `--prod` is a usage error.
  - Routes, on both listeners: `GET /docs` → `302 Found` with `Location: /docs/` (so the pages'
    relative links resolve). `GET /docs/` → `index.html`. `GET /docs/<file>` → that file from
    `FILES`, or `404 Not Found`. Content types: `.html` → `text/html; charset=utf-8`, `.css` →
    `text/css; charset=utf-8`, `.json` → `application/json; charset=utf-8`.

### 3.3 Implementation chunks

#### Chunk 1 — `Markdown`, `DocsRenderer`, the changelog's shape, and renderer tests

Files:
- `docs/CHANGELOG.md`: rewrite into the M7.3 shape (§4.1), which gives version headings that
  produce the `api_version-<N>` anchors the `api_version` mismatch diagnostic already links to
  (`Declarations.kt`, `ApiVersionMismatch`):
  ```markdown
  # Changelog

  Breaking script-facing changes are listed per `api_version`. See `MIGRATING.md` for
  before/after snippets.

  ## api_version 1

  Initial (unstable) script API.
  ```
- `cli/build.gradle.kts`:
  - bundle the changelog: `tasks.processResources { from(rootProject.file("docs/CHANGELOG.md")) { into("gups/docs") } }`;
  - in `tasks.test`, add `systemProperty("gups.docsGolden", layout.projectDirectory.dir("src/test/resources/docs-golden").asFile.absolutePath)`,
    next to the existing `gups.apiGolden`.
- `script/src/main/kotlin/gups/script/DocExamples.kt`: `data class DocExample` (§3.1) and
  `object DocExamples { val FIXTURE: String; val ALL: Map<String, DocExample> }`. `ALL` stays
  empty until chunk 2.
- `cli/src/main/kotlin/gups/cli/Markdown.kt`: `object Markdown { fun toHtml(md: String): String }`
  (§3.1).
- `cli/src/main/kotlin/gups/cli/DocsRenderer.kt`:
  ```kotlin
  data class PluginCommandDocSpec(val name: String, val lane: String, val role: String, val doc: String)
  data class PluginGmcpDocSpec(val pkg: String, val doc: String)
  data class PluginDocSpec(
      val id: String,
      val doc: String = "",
      val builtins: List<MethodSpec> = emptyList(),
      val effects: List<MethodSpec> = emptyList(),
      val commands: List<PluginCommandDocSpec> = emptyList(),
      val gmcp: List<PluginGmcpDocSpec> = emptyList(),
  )

  object DocsRenderer {
      val FILES: List<String> // §3.1 table order
      fun symbolHref(model: ScriptApiModel, symbol: String): String // "<page>.html#<id>"
      fun bundledChangelog(): String // reads resource gups/docs/CHANGELOG.md; fails clearly if missing
      fun render(
          model: ScriptApiModel,
          changelogMarkdown: String = bundledChangelog(),
          plugins: List<PluginDocSpec> = emptyList(),
          examples: Map<String, DocExample> = DocExamples.ALL,
      ): Map<String, String> // file name -> content, keys in FILES order
  }
  ```
  Write HTML with `buildString` and one small `esc(text)` helper. Don't add an HTML library.
- `cli/src/test/kotlin/gups/cli/DocsRendererTest.kt` and golden files in
  `cli/src/test/resources/docs-golden/`, one per file in `FILES`.

Done when:
1. `render(ScriptApi.model(HarnessApi.specs()))` returns exactly `FILES`, each non-blank, and
   two calls return equal maps.
2. For every symbol in `model.symbols()`, `symbolHref` names a page in `FILES`, and that page
   contains `id="<id>"`.
3. Every `href="…"` and `src="…"` on every page is a relative link to a file in the map, and its
   `#fragment`, if any, is an `id` on that page. No page contains `<script`.
4. A model whose doc strings and defaults contain `<b>`, `&` and `"` renders them escaped.
5. **Golden:** a small model renders byte for byte as the files in `docs-golden/`. Build it from
   the real model, so you don't construct specs by hand:
   ```kotlin
   val full = ScriptApi.model(HarnessApi.specs())
   val small = full.copy(
       engineVersion = "<engine>",
       declarations = full.declarations.filter { it.name == "exit" },
       settings = full.settings.filter { it.name == "module" },
       constants = full.constants.filter { it.name == "ALL" },
       hooks = full.hooks.filter { it.name == "on_enter" },
       views = full.views.filter { it.name == "exit" },
       effects = full.effects.filter { it.name == "heal" },
       behaviors = full.behaviors.filter { it.name == "wander" },
       rules = full.rules.copy(
           exports = full.rules.exports.take(1),
           modifierPoints = full.rules.modifierPoints.take(1),
       ),
       harness = full.harness.filter { it.name == "t" },
   )
   ```
   Render it with a fixed two-version changelog string, one `PluginDocSpec`, and one example.
   Its links may point at symbols the small model lacks, so skip conditions 2–3 for it.
   `-Dgups.updateGolden=true` rewrites the golden files, as `CommandsTest` does for
   `api.golden.json`.

#### Chunk 2 — Examples and the test that runs them

Files:
- `script/src/main/kotlin/gups/script/DocExamples.kt`: write `FIXTURE` and every required
  example (§3.1). Keep each one short (2–10 lines) and realistic. Base them on
  `template-world/zones/tutorial/` and its tests, which are known to work. Add
  `fun problems(model: ScriptApiModel): List<String>`: every key of `ALL` is in
  `model.symbols()`, no example has both `pkg` and `test` blank, and every required symbol has
  an example.
- The existing M7.1 completeness test (the one that calls `ScriptApi.validate`) also asserts
  `DocExamples.problems(model)` is empty.
- `harness/src/test/kotlin/gups/harness/DocExamplesTest.kt`: one dynamic test (JUnit
  `@TestFactory`) per entry of `DocExamples.ALL`, running it as §3.1 describes. Copy
  `rules/` from `System.getProperty("gups.templateWorld")`, which the harness tests already get.
  A failing example's message names its symbol and includes the `TestResult` message and
  warnings.

Done when:
1. `DocExamples.problems` is empty, and a unit test shows it reports a missing required example
   and an unknown symbol.
2. `DocExamplesTest` runs every example and all of them pass.
3. `api.golden.json` is unchanged.

#### Chunk 3 — `gups docs` writes HTML

Files:
- `cli/src/main/kotlin/gups/cli/Commands.kt`, `Docs`:
  - Make it a plain `CliktCommand(name = "docs")` with `help` "Generate the API reference."
    `StubCommand` and `EXIT_NOT_IMPLEMENTED` then have no users: delete them, and the
    `CommandsTest` case that expects exit 3 from `gups docs` becomes the new HTML test below.
  - Add `val world by option("--world", help = "Course world directory; include its plugins.").file(mustExist = true, canBeFile = false)`.
  - `--json` keeps its M7.1 behavior exactly. `--json` together with `--world` is a
    `UsageError("--world is for the HTML reference; api.json never includes plugins")`.
  - Without `--json`: `val dir = out ?: File("build/docs")`. If `dir.isFile` or
    `dir.name.endsWith(".json")`, throw `UsageError("--out: give a directory for the HTML reference, not ${dir.name}")`.
    With `--world`, read `WorldModule.read(WorldDir(world.toPath()))` and turn its error or
    `problems()` into a `UsageError`; M7.4 adds the plugins. Then
    `DocsRenderer.render(ScriptApi.model(HarnessApi.specs()))`, `dir.mkdirs()`, write each file as
    UTF-8, and `echo("Wrote the API reference (api_version ${EngineInfo.API_VERSION}) to $dir")`.
- `cli/src/test/kotlin/gups/cli/CommandsTest.kt`: tests for the conditions below.
- `README.md`: add an M7.2 section, like the M7.1 one, describing `gups docs --out DIR`.
- `docs/DECISIONS.md`: update D-171's CLI bullet, which still says `gups docs` without
  `--json` exits 3.

Done when:
1. `gups docs --out <tmp>` exits 0, prints the confirmation, and writes every file in `FILES`.
2. `gups docs --world <template-world> --out <tmp>` exits 0 and writes the same files.
3. `gups docs --out <an existing file>` and `gups docs --out x.json` fail with a usage error
   (exit status 1).
4. Every existing `gups docs --json` test still passes.

#### Chunk 4 — Serving `/docs/`

Files:
- `net/src/main/kotlin/gups/net/WebServer.kt`:
  - `WebAssets.contentType`: add `"json" -> ContentType.Application.Json`. Serve HTML, CSS and
    JSON with `; charset=utf-8` (`ContentType.withCharset(Charsets.UTF_8)`).
  - Add `fun Route.mountDocs(docs: Map<String, ByteArray>)` registering the three routes of
    §3.2. Ktor may treat `/docs` and `/docs/` as one route: if so, redirect only when
    `call.request.path()` doesn't end in `/`. The tests below decide.
  - Add a last constructor parameter `docs: Map<String, ByteArray> = emptyMap()` to `WebServer`,
    and call `mountDocs(docs)` in its `routing { }` block when `docs` isn't empty.
  - Add `class DocsServer(docs: Map<String, ByteArray>, host: String, port: Int)` with
    `fun start(): Int` (returns the bound port) and `fun stop()`, a plain-HTTP Ktor Netty server
    with only `mountDocs`. Copy how `WebServer` starts and reports its port.
- `cli/src/main/kotlin/gups/cli/GupsServer.kt`:
  - Add the constructor parameter `docsPort: Int? = null` after `prod`. `null` means no dev docs
    server, so the existing tests that build `GupsServer` directly don't open port 4080.
  - In `boot()`, after `WorldModule.read`, render the docs once:
    `docs = DocsRenderer.render(ScriptApi.model(HarnessApi.specs())).mapValues { it.value.toByteArray(Charsets.UTF_8) }`
    (M7.4 adds the plugins). Pass `docs` to `WebServer` in `webServer`.
  - In `run`, when `prod == null && docsPort != null`, start a `DocsServer(docs, "127.0.0.1", docsPort)`
    and add `"docs" to` its port at the **end** of `ports`, because `ready(ports.values.first())`
    must still get the telnet port. Stop it with the other listeners.
- `cli/src/main/kotlin/gups/cli/Commands.kt`, `Serve`:
  - `val docsPort by option("--docs-port", metavar = "PORT", help = "--dev: HTTP port for /docs/ on 127.0.0.1 (default 4080).").int()`.
  - `prodServer`: if `docsPort != null`, throw `UsageError("--docs-port is for --dev; --prod serves /docs/ on --https")`.
  - The `--dev` branch passes `docsPort = docsPort ?: 4080` by name.
  - The `--dev` ready message also names the docs URL.

Done when:
1. A `--prod` `GupsServer` with `--https` answers, with no cookie or token: `GET /docs` → 302 to
   `/docs/`; `GET /docs/` → 200 `text/html` containing `api_version`;
   `GET /docs/declarations.html` → 200; `GET /docs/style.css` → 200 `text/css`;
   `GET /docs/api.json` → 200 `application/json`; `GET /docs/nope.html` → 404. Follow how the
   existing HTTPS tests reach `WebServer` (`grep -rn "WebServer\|\"ws\"" cli/src/test net/src/test`).
2. A `--dev` `GupsServer` with `docsPort = 0` has `ports["docs"]`, and
   `GET http://127.0.0.1:<port>/docs/hooks.html` → 200.
3. `gups serve --prod --docs-port 4080 …` fails with exit status 1 and a message containing
   `--docs-port is for --dev`.
4. Every existing `GupsServerTest` and `OperationsTest` test still passes.

#### Chunk 5 — Build tasks, images and CI

Files:
- `cli/build.gradle.kts`:
  ```kotlin
  val generatedDocsDir = layout.buildDirectory.dir("generated-docs")

  val generateDocs by tasks.registering(JavaExec::class) {
      group = "documentation"
      description = "Writes the HTML API reference into build/generated-docs."
      classpath = sourceSets.main.get().runtimeClasspath
      mainClass = "gups.cli.MainKt"
      val out = generatedDocsDir
      argumentProviders += CommandLineArgumentProvider { listOf("docs", "--out", out.get().asFile.absolutePath) }
      outputs.dir(generatedDocsDir)
      doFirst { out.get().asFile.deleteRecursively() }
  }

  distributions {
      main {
          contents {
              from(generateDocs) { into("docs") }
          }
      }
  }

  val docsArchive by tasks.registering(Zip::class) {
      group = "distribution"
      description = "Packs the API reference as build/distributions/gups-docs-<version>.zip."
      archiveFileName = "gups-docs-${project.version}.zip"
      destinationDirectory = layout.buildDirectory.dir("distributions")
      into("gups-docs-${project.version}")
      from(generateDocs)
  }
  ```
  The runtime classpath already covers the bundled `CHANGELOG.md`, so it needs no extra input.
  In `jlinkImage`'s `doLast`, after `lib.copyRecursively(out.resolve("lib"))`, add
  `install.get().dir("docs").asFile.copyRecursively(out.resolve("docs"))`. It already depends on
  `installDist`.
- `.github/workflows/ci.yml`:
  - Smoke tests: add `test -f cli/build/install/gups/docs/index.html` to "Smoke-test the gups
    binary" and `test -f cli/build/image/gups/docs/index.html` to "Smoke-test the jlink image".
  - After the course-world-template steps, add a step on `matrix.platform == 'linux-x64'` that
    runs `./gradlew :cli:docsArchive $VERSION_ARG --console=plain` (with the same `env` as the
    other steps), then an upload step on tags (`startsWith(github.ref, 'refs/tags/v') && matrix.platform == 'linux-x64'`)
    with `name: gups-docs` and `path: cli/build/distributions/gups-docs-*.zip`.
  - `release` job: `gh release upload … --clobber ./*.tar.gz ./*.zip`.
- `README.md`: where the reference is found (image, release zip, `/docs/`).

Done when:
1. `./gradlew :cli:installDist :cli:docsArchive` creates `cli/build/install/gups/docs/` holding
   every file in `FILES`, and a zip whose entries are `gups-docs-<version>/<file>` for each of
   them.
2. `./gradlew :cli:jlinkImage` creates `cli/build/image/gups/docs/index.html`.
3. `./gradlew check` passes.

### 3.4 Pitfalls

- Don't put examples in `api.json`, and don't change `api.golden.json` in M7.2.
- `symbolHref` needs the model: `damage` is an effect, `room` a declaration, and only the model
  knows which.
- A golden test that reads its expected files from the test classpath can't rewrite them. Use
  the `gups.docsGolden` path, as `gups.apiGolden` does.
- Keep the telnet port first in `GupsServer.ports`.

### 3.5 Settled questions

- **Q2 (settled).** The server serves the docs at `/docs/` with no login, in both `--prod`
  and `--dev` (§3.2).

---

## 4. M7.3 — Changelog and Migration Artifacts

**Goal:** what §1.3 asks of every release: a changelog of breaking changes, a `MIGRATING.md`
with before/after snippets, migration artifacts a local LLM can consume (`changes.json`,
`gups migrate-info`), and a CI check that every API difference is in the changelog.

### 4.1 The structured changelog

**Proposal:** `docs/CHANGELOG.md` stays a human-written Markdown file in a strict shape that
`Changelog.parse` validates, reporting errors with 1-based line numbers:

````markdown
# Changelog

Breaking script-facing changes are listed per `api_version`. See `MIGRATING.md` for
before/after snippets.

## api_version 2 (unreleased)

### Renamed `ctx.act_move` to `ctx.walk`
- kind: renamed
- symbol: ctx.act_move
- replacement: ctx.walk

Mobs now walk with `ctx.walk(mob, dir)`.

```python before
ctx.act_move(ctx.self, "north")
```

```python after
ctx.walk(ctx.self, "north")
```

## api_version 1

Initial (unstable) script API.
````

Grammar (blank lines are allowed between any two parts):
1. **Preamble:** lines before the first `## ` heading are ignored, except that a `### ` heading
   or a `- key: value` line there is an error.
2. **Version sections:** every `## ` line must match
   `^## api_version ([1-9][0-9]*)( \(unreleased\))?$`.
   - Versions strictly descend, with no repeats.
   - At most one section is `(unreleased)`, and only the first.
   - Prose lines may follow the heading before its first entry. They are the section's `intro`.
   - Each version heading is the anchor `api_version-<N>` that the `api_version` mismatch
     diagnostic links to (`CHANGELOG.md#api_version-N`, §1.3).
3. **Entries:** a `### <title>` line (title non-blank) starts an entry. Next comes a block of
   `- <key>: <value>` lines:
   - `kind` (required): `removed`, `renamed`, `signature`, `behavior` or `added`. Only `added`
     isn't breaking.
   - `symbol` (required): matches
     `^(hook:|view:|rules:|modifier:)?[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$`.
     Whether the symbol exists is the API check's business (§4.3), not the parser's: a removed
     symbol no longer exists.
   - `replacement`: same pattern. Required for `renamed`, where it must differ from `symbol`.
     Optional for `removed`, `signature` and `behavior`. An error on `added`.
   - Any other key, or a key given twice, is an error.
   - Then at least one prose line, the `summary` (lines joined with `\n`, trimmed).
   - Then zero or more snippet pairs: a ```` ```python before ```` fence, its closing ```` ``` ````,
     then a ```` ```python after ```` fence and its closing ```` ``` ````. Snippets are allowed
     on every kind.
   - Errors: an unclosed fence (reported at its opening line); a fence whose info string isn't
     `python before` or `python after`; a `before` not followed by an `after`; an `after` without
     a `before`; prose after the first fence.
4. **Errors** are `ChangelogParseException(line, reason)`, whose message is
   `CHANGELOG.md:<line>: <reason>`. The parser stops at the first error.

### 4.2 Generated artifacts

1. **`docs/MIGRATING.md`** = `changelog.toMigratingMarkdown()`. It is generated and never
   hand-edited, and a test fails when it is stale.
   - With no breaking entry anywhere, it is exactly (this is today's file):
     ```markdown
     # Migrating Between api_versions

     No migrations yet: `api_version = 1` is the first script API.
     ```
   - Otherwise it is `# Migrating Between api_versions`, a blank line,
     ``Breaking script-facing changes per `api_version`, with before/after snippets.``, then for
     each section with a breaking entry, newest first: its heading exactly as in the changelog,
     then each breaking entry in order as
     ````markdown
     ### <title>
     - **kind:** `<kind>`
     - **symbol:** `<symbol>`
     - **replacement:** `<replacement>`

     <summary>

     ```python before
     <before>
     ```

     ```python after
     <after>
     ```
     ````
     The `replacement` line appears only when there is one. Blocks are separated by exactly one
     blank line, and the file ends with one `\n`. `added` entries never appear.
2. **`changes.json`** = `changelog.toJson()`: 2-space-indented JSON ending in `\n`:
   ```json
   {
     "api_version": 1,
     "versions": [
       {
         "api_version": 1,
         "unreleased": false,
         "intro": "Initial (unstable) script API.",
         "entries": []
       }
     ]
   }
   ```
   The top-level `api_version` is `EngineInfo.API_VERSION`. Each entry is
   `{"title", "kind", "symbol", "replacement" (string or null), "summary", "snippets": [{"before", "after"}]}`,
   with keys in that order. `gups docs` ships it (it joins `DocsRenderer.FILES`), so it reaches
   `/docs/changes.json`, the images and the docs zip.
3. **`changelog.html`** renders the parsed changelog instead of raw Markdown:
   `id="api_version-<N>"` on each version section; `id="change-<api_version>-<index>"` on each
   entry (`index` counts from 1 within its section, since a symbol can appear in several
   entries); a `kind` badge; `symbol` and `replacement` linked through `symbolHref` when the
   current model has them, plain `<code>` otherwise; the summary; and the snippets.
4. **`gups migrate-info [--world DIR] [--changelog FILE] [--format text|json] <package>...`**
   lists every place a package uses a symbol that changed since the package's `api_version`
   (§1.3's LLM migration exercise).
   - **It reads the AST and never loads the package.** A package written for an older
     `api_version` fails in `ScriptLoader` at `package(api_version = …)` before anything else is
     read. `MigrateInfo` instead parses each `.star` file with
     `StarlarkFile.parse(ParserInput.fromString(text, label), FILE_OPTIONS)` and walks it with a
     `NodeVisitor`, so any syntactically valid package works. `Linter.scanAttributeKeys` shows
     the technique.
   - **Steps for each package** (`//zones/forest` or `zones/forest`):
     1. Resolve it under `--world` (default `.`). No `package.star` → `UsageError`.
     2. Find the `package(...)` call in `package.star` and its integer literal `api_version`
        keyword. A syntax error, or no literal, is an error for that package (exit status 1 at
        the end).
     3. The relevant entries are those in every section whose version is greater than the
        package's, including an `(unreleased)` one, whose uses are labelled "(unreleased)".
     4. Visit every `.star` file of the package, `*_test.star` included, in name order, and
        record a use for each AST node matching a relevant entry's `symbol`:

        | Symbol shape | Matches |
        |---|---|
        | `fn` (a declaration, effect, behavior or setting) | `CallExpression` whose callee is `Identifier(fn)` |
        | `fn.param` where `fn` is such a global | a call to `fn` with keyword `param`, or a positional argument at `param`'s positional index (from `DeclSpec` or `MethodSpec`) |
        | `NAME` in capitals (a constant) | `Identifier(NAME)` |
        | `hook:name` | keyword argument `name` in any call |
        | `ctx.member`, `rules_ctx.member`, `t.member`, `w.member`, `p.member`, `diagnostic.member` | `DotExpression` with field `member` whose receiver is `Identifier(obj)`. For `ctx`, the receiver may also be the first parameter of any top-level `def`, whatever its name |
        | `obj.method.param`, `view:kind.method.param` | a call `<x>.method(...)` with keyword `param` |
        | `view:kind.member` | any `DotExpression` with field `member` whose receiver isn't `Identifier("ctx")`, `Identifier("rules_ctx")` or a harness object |
        | `rules:fn` | `DefStatement` named `fn` in a package whose `package(kind = "rules")` |
        | `modifier:point` | a string key `point` in a dict passed as `modifiers = {...}` |

        The view and hook rules are heuristics and may report false positives. That is
        acceptable, since a person or model reads the report.
     5. A use is `SymbolUse(file: String /* "//zones/forest:mobs.star" */, line: Int, sourceLine: String, entry: ChangelogEntry, apiVersion: Int, unreleased: Boolean)`.
        Sort uses by `(file, line, symbol)`. Relevant `behavior` entries with no use go in
        `generalChanges`.
   - **Output:** see §4.6 for the exact text and JSON. The changelog defaults to the engine's
     bundled copy (`DocsRenderer.bundledChangelog()`). `--changelog FILE` overrides it, for tests
     and for trying a changelog before it ships.

### 4.3 The API-change check

**Files** (all committed, all with `"engine_version": "<engine>"` where applicable):
- `docs/api/api-<N>.json`: the API as released at `api_version` N, the **baseline**.
- `docs/api/changes-<N>.json`: `changelog.toJson()` taken with that baseline, the **frozen
  history**.
- `cli/src/test/resources/api.golden.json` stays as M7.1 left it: a snapshot of the *current*
  model, docs included, that catches every change and any difference between platforms. Right
  after a release it equals the baseline. Between releases it moves while the baseline stays.

**`ApiDiff.check(baselineJson: String, frozenJson: String, current: ScriptApiModel, changelog: Changelog): List<String>`**
returns the problems, each naming its symbol and the entry that would fix it. Empty means the
check passes. Let `N = current.apiVersion` (`EngineInfo.API_VERSION`).
1. **Released sections are frozen.** For every section of `frozenJson`, the changelog's section
   with the same version must start with the same entries in the same order, compared as their
   `changes.json` objects. Extra entries after them are allowed only in section `N` and only
   with `kind: added`. Anything else is a problem: "breaking changes since api-N.json go under
   `## api_version N+1 (unreleased)`" (Q3), or "released entry '<title>' was edited or
   removed".
2. **The unreleased section**, if any, must be `N + 1`.
3. **Active entries** = every entry of the unreleased section, plus the extra `added` entries of
   section `N` from step 1.
4. **Diff.** Parse both JSON documents (`current.toJson()` for the current one) and collect
   every object that has a `"symbol"`. For each, keep only its scalar fields and lists of
   strings, without `symbol`, `doc` and `since`. Drop lists of objects (`params`, `fields`,
   `methods`, …), because child changes show up as their own symbols. Then:
   - *removed:* in the baseline, not current;
   - *added:* current, not in the baseline;
   - *changed:* in both, with different kept fields.
5. **Coverage.** "`E` covers `S`" when `E.symbol == S`, or `S` starts with `E.symbol + "."`:
   - a removed `S` needs an active `removed` or `renamed` entry covering it (a `renamed`
     entry's `replacement` must be a current symbol);
   - an added `S` needs an active `added` entry covering it, or a `renamed` entry whose
     `replacement` covers it;
   - a changed `S` needs an active `signature` or `behavior` entry covering it;
   - a `signature` entry also covers removed and added symbols beneath it, such as a new
     parameter of a changed method.
6. **No stale entries:** every active entry must cover at least one removed, added or changed
   symbol, except a `behavior` entry, whose symbol only has to exist in the current model (a
   behavior change may leave the signature alone).

`ApiDiff` lives in `:script`. Add `implementation(libs.kotlinx.serialization.json)` to
`script/build.gradle.kts` and use `Json.parseToJsonElement`. It needs no compiler plugin.

**Raising `api_version` (Q3).** It happens before a release is tagged, because the tagged commit
must already contain the new baseline. The tag-triggered release job can't commit it.
1. If the changelog has an `(unreleased)` section (so it has breaking changes): drop
   ` (unreleased)` from its heading and set `EngineInfo.API_VERSION` to its version.
2. Run `gups docs --update-baseline`. It refuses while an `(unreleased)` section exists or while
   the newest section's version isn't `EngineInfo.API_VERSION`. Then it writes
   `docs/api/api-<N>.json`, `docs/api/changes-<N>.json` and `docs/MIGRATING.md`, and keeps older
   baselines as history. `--docs-dir DIR` (default `docs`) points it elsewhere, for tests.
3. A release with only `added` entries keeps `N`: step 2 alone rewrites the version-`N` files.

### 4.4 Implementation chunks

#### Chunk 1 — `Changelog`: parser, `MIGRATING.md`, `changes.json`

Files:
- `script/build.gradle.kts`: add `implementation(libs.kotlinx.serialization.json)`.
- `script/src/main/kotlin/gups/script/Changelog.kt`:
  ```kotlin
  enum class ChangeKind(val wireName: String, val isBreaking: Boolean) {
      REMOVED("removed", true), RENAMED("renamed", true), SIGNATURE("signature", true),
      BEHAVIOR("behavior", true), ADDED("added", false),
  }
  data class Snippet(val before: String, val after: String)
  data class ChangelogEntry(
      val title: String, val kind: ChangeKind, val symbol: String, val replacement: String?,
      val summary: String, val snippets: List<Snippet>, val line: Int,
  )
  data class ChangelogVersion(
      val apiVersion: Int, val unreleased: Boolean, val intro: String,
      val entries: List<ChangelogEntry>, val line: Int,
  )
  class ChangelogParseException(val line: Int, val reason: String) :
      IllegalArgumentException("CHANGELOG.md:$line: $reason")
  data class Changelog(val versions: List<ChangelogVersion>) {
      fun toMigratingMarkdown(): String
      fun toJson(currentApiVersion: Int = EngineInfo.API_VERSION): String
      companion object { fun parse(markdown: String): Changelog }
  }
  ```
  Parse with a line-by-line state machine that tracks line numbers. Add no Markdown library.
  Build JSON with `buildJsonObject` and write it with
  `Json { prettyPrint = true; prettyPrintIndent = "  " }`, adding the final `\n`.
- `script/src/test/kotlin/gups/script/ChangelogTest.kt`.

Done when:
1. The real `docs/CHANGELOG.md` parses (read it through a `gups.docsDir` system property; add
   it to `script/build.gradle.kts`'s `tasks.test` along with
   `inputs.dir(rootProject.file("docs"))`).
2. A changelog using every kind, an unreleased section, intro prose, multi-line summaries and
   two snippet pairs parses into the expected objects.
3. Each error in §4.1 has a test asserting its exact line number, including §4.6 example 2.
4. `toMigratingMarkdown()` of the real changelog equals `docs/MIGRATING.md`, and §4.6 example 1
   produces exactly its expected output.
5. `toJson()` of the real changelog equals the §4.2 example.

#### Chunk 2 — `ApiDiff` and the committed baseline

Files:
- `docs/api/api-1.json`: a copy of `cli/src/test/resources/api.golden.json`.
- `docs/api/changes-1.json`: `Changelog.parse(docs/CHANGELOG.md).toJson()`.
- `script/src/main/kotlin/gups/script/ApiDiff.kt`: `object ApiDiff { fun check(...): List<String> }`
  (§4.3), plus `fun normalize(apiJson: String): String`, which replaces the `engine_version`
  value with `"<engine>"`.
- `script/src/test/kotlin/gups/script/ApiDiffTest.kt`. Make changed models with `copy(...)` on
  `ScriptApi.model(...)`. `:script` tests can't see `HarnessApi`, so pass `emptyList()` for the
  harness types and build the baseline from the same model.

Done when each of these holds:
1. Identical model and baseline with the real changelog → no problems.
2. Changing only a `doc` string → no problems.
3. Removing `room.size` or `ctx.act_move` with no entry → one problem naming the symbol.
4. Adding a parameter or method with no entry → a problem. With an `added` entry under
   `## api_version 2 (unreleased)`, or appended to `## api_version 1` → none.
5. Renaming `ctx.act_move` to `ctx.walk` with one `renamed` entry → none (the old and new
   parameters are covered).
6. Changing a parameter's `default` with no entry → a problem. With a `signature` entry → none.
7. A breaking entry appended to `## api_version 1` → a problem (Q3). Editing a frozen entry → a
   problem.
8. An unreleased entry naming a symbol that didn't change (other than `behavior`) → a problem.

#### Chunk 3 — `MigrateInfo` and `gups migrate-info`

Files:
- `script/src/main/kotlin/gups/script/MigrateInfo.kt`: `SymbolUse` (§4.2 item 4);
  `data class MigrationReport(val pkg: String, val pkgApiVersion: Int, val currentApiVersion: Int, val uses: List<SymbolUse>, val generalChanges: List<ChangelogEntry>)`
  with `fun renderText(): String` and `fun toJson(): String`; and
  `object MigrateInfo { fun inspect(dir: WorldDir, pkg: String, changelog: Changelog, model: ScriptApiModel, currentApiVersion: Int = EngineInfo.API_VERSION): MigrationReport }`.
  `model` supplies positional indexes for `fn.param` symbols.
- `cli/src/main/kotlin/gups/cli/Commands.kt`: `class MigrateInfoCmd : CliktCommand(name = "migrate-info")`,
  registered in `gupsCommand()`, with `--world` (default `.`), `--changelog FILE`,
  `--format` (`choice("text", "json").default("text")`) and
  `argument("package").multiple(required = true)`. Several packages print one report each:
  in text, separated by a blank line; in JSON, as an array.
- `script/src/test/kotlin/gups/script/MigrateInfoTest.kt` and a `CommandsTest` case.

Done when: a temp world whose package is at `api_version = 1`, checked against a synthetic
changelog with `## api_version 2 (unreleased)` entries for `ctx.act_move` (renamed), `room.size`
(removed), `hook:on_wake` (renamed), `CRAMPED` (removed), `view:actor.hp` (signature),
`rules:to_hit` (signature) and `modifier:to_hit` (behavior), reports each use with its exact
file and line, in text and in JSON. The package also passes a keyword that today's `room`
rejects, which proves nothing is loaded.

#### Chunk 4 — Docs integration, `--update-baseline`, and the CI check

Files:
- `cli/src/main/kotlin/gups/cli/DocsRenderer.kt`: add `changes.json` to `FILES` (after
  `changelog.html`) and render `changelog.html` from `Changelog.parse` (§4.2 item 3). Regenerate
  the docs golden files.
- `cli/src/main/kotlin/gups/cli/Commands.kt`, `Docs`: `--update-baseline` and `--docs-dir DIR`
  (default `docs`), per §4.3. They print what they wrote. `--update-baseline` with `--json`,
  `--out` or `--world` is a usage error.
- `cli/build.gradle.kts`, `tasks.test`: `systemProperty("gups.docsDir", rootProject.file("docs").absolutePath)`
  and `inputs.dir(rootProject.file("docs"))`.
- `cli/src/test/kotlin/gups/cli/CommandsTest.kt`: a **repository check**, which is what makes
  "changing a builtin without a changelog entry fails engine CI" true:
  - `docs/CHANGELOG.md` parses;
  - `docs/MIGRATING.md` equals `toMigratingMarkdown()`;
  - `ApiDiff.check(api-<N>.json, changes-<N>.json, ScriptApi.model(HarnessApi.specs()), changelog)`
    is empty, where `N = EngineInfo.API_VERSION`.

  Its failure message tells the reader what to do: add a changelog entry, or regenerate
  `MIGRATING.md` with `gups docs --update-baseline`. Also test `--update-baseline --docs-dir <tmp>`
  (it writes the three files, and refuses with an `(unreleased)` section).
- `README.md`: the changelog format, `gups migrate-info`, and the §4.3 release steps.
- `docs/DECISIONS.md`: this stage's decisions, including the frozen-history file and that the
  version bump is a pre-tag step.

Done when `./gradlew check` passes and the checklist in §4.7 holds.

### 4.5 Pitfalls

- Never call `ScriptLoader` from `migrate-info`.
- Don't make doc-only edits need a changelog entry: drop `doc` and `since` before comparing.
- A parent entry (`removed`, `renamed`, `added` or `signature` on `ctx.act_move`) covers its
  children (`ctx.act_move.mob`). Don't demand separate entries for them.
- `:script` must not depend on `:harness` or `:cli`.
- Keep `api.golden.json` and its test. The baseline doesn't replace it (§4.3).

### 4.6 Examples

**Example 1:** this changelog:
````markdown
# Changelog

## api_version 2 (unreleased)

### Renamed `ctx.act_move` to `ctx.walk`
- kind: renamed
- symbol: ctx.act_move
- replacement: ctx.walk

Mobs now walk with `ctx.walk(mob, dir)`.

```python before
ctx.act_move(ctx.self, "north")
```

```python after
ctx.walk(ctx.self, "north")
```

### Added `room.lighting`
- kind: added
- symbol: room.lighting

Rooms may now declare a `lighting` level.

## api_version 1

Initial (unstable) script API.
````
gives this `MIGRATING.md`:
````markdown
# Migrating Between api_versions

Breaking script-facing changes per `api_version`, with before/after snippets.

## api_version 2 (unreleased)

### Renamed `ctx.act_move` to `ctx.walk`
- **kind:** `renamed`
- **symbol:** `ctx.act_move`
- **replacement:** `ctx.walk`

Mobs now walk with `ctx.walk(mob, dir)`.

```python before
ctx.act_move(ctx.self, "north")
```

```python after
ctx.walk(ctx.self, "north")
```
````

**Example 2: errors.** In this changelog, line 5 is the `### Renamed …` heading and line 11
opens the `before` fence:
````markdown
# Changelog

## api_version 2 (unreleased)

### Renamed `ctx.act_move` to `ctx.walk`
- kind: renamed
- symbol: ctx.act_move

Mobs now walk with `ctx.walk(mob, dir)`.

```python before
ctx.act_move(ctx.self, "north")
```
````
- As written: `CHANGELOG.md:5: entry 'Renamed `ctx.act_move` to `ctx.walk`' has kind 'renamed' but no '- replacement: <symbol>'`.
- With `- replacement: ctx.walk` added as line 8 (moving the fence to line 12):
  `CHANGELOG.md:12: a 'python before' block must be followed by a 'python after' block`.

**Example 3: `gups migrate-info --world W //zones/forest`**, with example 1 as the changelog:
```text
//zones/forest (package api_version 1, current api_version 1)
  //zones/forest:mobs.star:14: ctx.act_move(ctx.self, "north")
    [renamed] ctx.act_move -> ctx.walk (api_version 2, unreleased)
    Mobs now walk with `ctx.walk(mob, dir)`.
    before:
      ctx.act_move(ctx.self, "north")
    after:
      ctx.walk(ctx.self, "north")
```
Relevant `behavior` entries with no use follow under `General changes (check by hand):`, one
`[behavior] <symbol> (api_version N): <summary>` line each. With `--format json`:
```json
{
  "package": "//zones/forest",
  "package_api_version": 1,
  "current_api_version": 1,
  "uses": [
    {
      "file": "//zones/forest:mobs.star",
      "line": 14,
      "source": "ctx.act_move(ctx.self, \"north\")",
      "api_version": 2,
      "unreleased": true,
      "title": "Renamed `ctx.act_move` to `ctx.walk`",
      "kind": "renamed",
      "symbol": "ctx.act_move",
      "replacement": "ctx.walk",
      "summary": "Mobs now walk with `ctx.walk(mob, dir)`.",
      "snippets": [
        {
          "before": "ctx.act_move(ctx.self, \"north\")",
          "after": "ctx.walk(ctx.self, \"north\")"
        }
      ]
    }
  ],
  "general_changes": []
}
```

### 4.7 Checklist

- [ ] `ChangelogTest`: the real changelog parses; every §4.1 error is reported at its line.
- [ ] `docs/MIGRATING.md` equals the generated text; `changes.json` ships in `gups docs`.
- [ ] `docs/api/api-1.json` and `docs/api/changes-1.json` exist, and the repository check passes.
- [ ] `ApiDiffTest` covers the eight cases of chunk 2.
- [ ] `gups docs --update-baseline` writes three files and refuses an `(unreleased)` section.
- [ ] `gups migrate-info` reports uses with exact lines in text and JSON, without loading the
      package.
- [ ] `./gradlew check` passes; decisions are logged; `docs/SPEC.md` is untouched.

### 4.8 Settled questions

- **Q3 (settled).** Breaking changes collect under the next, unreleased `api_version`, which
  ships with the next release that has any (§4.3).
- **Q4 (settled).** Every API difference needs a changelog entry, additions included, as
  `kind: added` (§4.3).

---

## 5. M7.4 — The Plugin API

**Goal:** the engine-developer path of §1.2: `.jar` plugins found through `ServiceLoader`
(§2.2) that add host builtins, commands, effect primitives and GMCP packages (§4.11). There are
no compatibility promises.

### 5.1 The interface

Everything here is in package `gups.pluginapi` of `:plugin-api`:

```kotlin
object PluginApi {
    const val VERSION: Int = 1
}

interface GupsPlugin {
    val id: String                       // [a-z][a-z0-9_]*, unique
    val pluginApiVersion: Int            // must equal PluginApi.VERSION
    val doc: String get() = ""
    fun register(r: PluginRegistry)
}

interface PluginRegistry {
    fun addBuiltin(name: String, value: StarlarkValue, doc: String)
    fun addBuiltin(
        name: String,
        doc: String,
        params: List<PluginParam> = emptyList(),
        returnType: String = "any",
        call: (world: PluginWorld, positional: List<Any?>, named: Map<String, Any?>) -> Any?,
    )
    fun addCommand(
        name: String,
        lane: PluginLane,
        role: PluginRole = PluginRole.PLAYER,
        doc: String,
        run: (PluginCommandCall) -> Unit,
    )
    fun addEffectPrimitive(name: String, doc: String, params: List<PluginParam>, apply: PluginEffect)
    fun addGmcp(pkg: String, doc: String, produce: (PluginWorld, PluginActor) -> Any?)
    fun onWorldStarted(fn: (PluginWorld) -> Unit)
}

enum class PluginLane { FAST, TICK }                          // core Lane
enum class PluginRole { PLAYER, BUILDER, INSTRUCTOR, ADMIN }  // core Role

data class PluginParam(
    val name: String,
    val type: String,          // documentation only, e.g. "int", "room"
    val doc: String,
    val required: Boolean = true,
    val defaultValue: Any? = null,
)

data class PluginCommandCall(val world: PluginWorld, val actor: PluginActor, val args: String)

data class PluginEffectCall(
    val world: PluginWorld,
    val caster: PluginActor,
    val target: PluginActor?,   // null for target = "none" skills
    val args: Map<String, Any?>,
)

fun interface PluginEffect {
    fun apply(call: PluginEffectCall)
}
```

- **Types:** every type in these signatures, and in §5.2's facade, belongs to `gups.pluginapi`,
  to Kotlin/Java, or to the Starlark library (`StarlarkValue`, `StarlarkThread` and the
  annotations, which `plugin-api` exports). None comes from `core` or `script` (Q6).
- **Version:** `PluginApi.VERSION` changes whenever this interface or the facade changes.
  Plugins built for another version are refused at load.
- **Builtins:** they appear to scripts under the plugin's id, as fields of one immutable
  namespace value (Q5). A plugin `weather` with builtin `now` is used as `weather.now(room)`.
  - The lambda overload binds Starlark arguments to `params`, in declared order: missing
    required → error, unknown keyword → error, a missing optional parameter gets `defaultValue`.
    It then converts the values: `StarlarkInt` → `Long`, `StarlarkFloat` → `Double`, `None` →
    `null`, list/tuple → `List`, `Dict` → `Map`, `RoomView`/`ActorView`/`ItemView` →
    `PluginRoom`/`PluginActor`/`PluginItem`. Strings and booleans pass as they are.
  - Return values convert back: `null`/`Unit` → `None`, `Int`/`Long` → `StarlarkInt`, `Double`
    → `StarlarkFloat`, `List` → immutable `StarlarkList`, `Map` → immutable `Dict`. Strings and
    booleans pass as they are. Returning a facade entity is an error in this version (return
    its label).
  - It gets the `PluginWorld` of the hook that is running. Called where no hook runs (top-level
    package code, or a test function's own body), it fails with
    `weather.now() can only be called while the world runs, from a hook or command`.
  - `addBuiltin(name, value, doc)` installs `value` as is. For entity arguments, such a value
    uses `PluginViews.room/actor/item(value)` and `PluginViews.world(thread)`
    (`useStarlarkThread = true`).
- **Effect primitives:** they appear the same way (`weather.soak(pulses = 3)`). Calling one
  binds and converts its arguments as builtins do, refuses entity arguments (an effect is part
  of a frozen prototype), and returns an effect value wrapping
  `Effect.Plugin(pluginId, name, args)`. That value works wherever an engine effect primitive's
  does: `skill(effect = …)`, `sequence([...])`. It needs no world, so it works in top-level
  package code. `core` applies `Effect.Plugin` by calling `World.pluginEffects`, which is one
  new case in the sealed `Effect` (§4.3).
- **Commands:** they go through the normal command registry (§5.2) via
  `CommandRegistry.registerPlugin`. They may not shadow engine commands, directions, or another
  plugin's commands (as D-064). A script `global_command` with a plugin command's name is
  skipped with a `"load"` diagnostic, because plugins are installed first and can't change
  without a restart. Reloads and deploys keep plugin commands (`clearGlobals()` clears only
  script global commands).
- **GMCP:** a producer is called in phase 7 for each session whose client supports the package,
  and only changed payloads are sent (SPEC_MILESTONE6.md §5.2). It returns plain values (`Map`,
  `List`, `String`, numbers, `Boolean`, `null`), as `core`'s own payloads do. A plugin package
  name has at least two dot-separated parts (`Gups.Weather`). It must not equal, contain, or be
  contained in an engine package (`Char.Vitals`, `Char.Status`, `Char.Affects`, `Room.Info`,
  `Room.Bands`, `Comm.Channel.Text`, `Gups.Trace`), because `supportsGmcp` matches by prefix.
  Nor may it equal another plugin's package.
- **Failures at run time:** an exception from a plugin builtin becomes a script error in the
  calling hook (the normal hook-failure path, SPEC §6.2). An exception from a command, effect,
  GMCP producer or `onWorldStarted` callback is caught and recorded as a `"plugin"` diagnostic,
  and the pulse goes on.

### 5.2 What plugins can touch

**Decision (Q6):** plugins don't get raw `World` or entities. The `plugin-api` module exposes a
`PluginWorld` facade instead:
- **verbs:** a subset of the `ctx` verbs, as Kotlin methods with the same meaning and argument
  names. Each one calls the same `core` function as the `Ctx` verb of that name
  (`script/src/main/kotlin/gups/script/Ctx.kt`), so the §3.8 invariants hold:
  ```kotlin
  fun send(target: PluginActor, text: String)
  fun echo(room: PluginRoom, text: String, exclude: List<PluginActor> = emptyList())
  fun damage(target: PluginActor, amount: Int, type: String, source: PluginActor? = null)
  fun heal(target: PluginActor, amount: Int)
  fun applyAffect(target: PluginActor, name: String, duration: Int,
                  statDeltas: Map<String, Int> = emptyMap(), flags: Set<String> = emptySet())
  fun removeAffect(target: PluginActor, name: String): Boolean
  fun teleport(actor: PluginActor, room: PluginRoom)
  fun getAttr(entity: PluginEntity, key: String, default: Any? = null): Any?
  fun setAttr(entity: PluginEntity, key: String, value: Any?)
  fun delAttr(entity: PluginEntity, key: String)
  ```
  Attribute keys are in the plugin's own namespace (its id), as a package's are in its own.
- **reads:** `val pulse: Long`, `val seed: Long`, `val rng: PluginRng`
  (`int(min, max)`, `chance(p)`, seeded from the world's RNG), `fun rooms(): List<PluginRoom>`
  (sorted by label), `fun room(label: String): PluginRoom?`, and
  `fun regions(): List<String>` (every region tag a room carries, sorted).
- **diagnostics:** `fun diagnose(message: String)` records a `"plugin"` diagnostic, and
  `fun log(message: String)` writes to the server log (the loader's `log`).
- **read-only views:** each reads current state on every access, like the script views:
  - `PluginEntity`: `id: Long`, `label: String?`, `name: String`;
  - `PluginRoom : PluginEntity`: `regions: List<String>`, `flags: Set<String>`,
    `size: String`, `exits: Map<String, String>` (direction → room label),
    `actors: List<PluginActor>`, `items: List<PluginItem>`;
  - `PluginActor : PluginEntity`: `isPlayer`, `room: PluginRoom`, `hp`, `maxHp`, `mana`,
    `maxMana`, `move`, `maxMove`, `level`, `affects: List<String>` (names),
    `fun hasAffect(name: String): Boolean`;
  - `PluginItem : PluginEntity`: `keywords: List<String>`;
- **`object PluginViews`:** `fun world(thread: StarlarkThread): PluginWorld?`, and
  `fun room(value: Any?): PluginRoom?`, `actor(value: Any?): PluginActor?`,
  `item(value: Any?): PluginItem?`, which accept a facade value or a script view (typed `Any?`,
  so no `script` type leaks).

This is the "narrow subset" of §2.2. Every mutation stays on the world thread and keeps the
§3.8 invariants, because it goes through the same verbs scripts use. Extending the facade
later is a `PluginApi.VERSION` bump.
- **Build change:** `plugin-api` depends on `core` and `script` with `implementation` instead of
  `api`, and exposes `api(libs.starlark)`, so plugins compile only against `gups.pluginapi.*` and
  `net.starlark.java.*` (Q6).
- **Engine-side code** (loader, host, facade implementations) lives in package
  `gups.pluginapi.host` of the same module. It may use `core` and `script` types, since only
  `:cli` and tests call it, and plugins have no reason to. The facade implementation classes
  are `internal`.

### 5.3 Loading

- **Where jars come from (Q7):** `plugins/<id>.jar` in the course world repository, each pinned
  by a SHA-256 in `MODULE.gups` (`plugins = {"weather": "sha256:<64 lowercase hex>"}`). The
  server, `gups test`, `gups lint`, `gups fuzz`, `gups migrate-check`, `gups repro`,
  `gups docs --world` and CI all load the same jars. A `.jar` in `plugins/` that `MODULE.gups`
  doesn't name is ignored, with a warning.
- **`MODULE.gups` checks:** a plugin id that doesn't match `[a-z][a-z0-9_]*`, that equals a
  reserved name, or whose checksum isn't `sha256:` plus 64 lowercase hex digits is a
  `WorldModule.problems()` entry, so it stops the server like any other `MODULE.gups` error.
  Reserved names: the keys of `PACKAGE_GLOBALS`, `module`, `server`, `ctx`, `self`, `room`,
  `rules_ctx`, `t`, `w`, `p`, `diagnostic`, and the Starlark universe (`Starlark.UNIVERSE`).
- **Loading:** one `URLClassLoader` per jar, with the engine's class loader as parent.
  `ServiceLoader.load(GupsPlugin::class.java, loader)` finds the plugin. Loaders stay open while
  the plugins are in use and close with `PluginHost.close()`.
- **Restarts:** loading happens once at startup. Adding, removing or changing a plugin is a
  `MODULE.gups` / `plugins/` change, which needs a restart, like an engine upgrade (§5.10).
  `Deployer` already refuses a deploy that changes `MODULE.gups`, and it also refuses one whose
  changed files include anything under `plugins/`.
- **Failures (§6.4):** a plugin is skipped, with a warning, when:
  - its jar is missing or fails its checksum;
  - the jar has zero or several `GupsPlugin` providers;
  - `plugin.id` isn't the id `MODULE.gups` declared;
  - `pluginApiVersion != PluginApi.VERSION`;
  - `register` throws, or registers an invalid or clashing name. Registration is atomic: a
    failed plugin adds nothing.

  The other plugins still load. The server logs each warning, records it as a `"plugin"`
  diagnostic, and alerts instructors. Packages that use a skipped plugin then fail to load,
  with `name '<id>' is not defined`. `ScriptLoader` already leaves failed packages out, so on a
  restart the server starts without them, and in a deploy `host.stage` fails and the old
  version stays live.
- **Profiles:** `--dev` loads plugins the same way, so students can run a world that uses
  them.

### 5.4 Trust

**Decision (Q8):** a plugin is trusted code with full JVM access. There is no sandbox: the JVM
no longer has one (the `SecurityManager` is gone), and running plugins in another process is
out of scope. Trust comes from procedure instead:
- only jars pinned by checksum in `MODULE.gups` are loaded;
- the course world template's `CODEOWNERS` (SPEC_MILESTONE6.md §8.1) gives `MODULE.gups` and
  `plugins/` to instructors, so adding a plugin is a PR only an instructor can approve;
- loading one needs a restart, like an engine upgrade;
- `docs/PLUGINS.md` tells instructors to review a plugin as they would engine code.

### 5.5 Implementation chunks

#### Chunk 1 — Seams in `:core` and `:script`

These are plugin-agnostic hooks that `:plugin-api` fills in. `:core` and `:script` must not
depend on `:plugin-api`.

- `core/src/main/kotlin/gups/core/model/Effects.kt`: add
  `data class Plugin(val pluginId: String, val name: String, val args: Map<String, Any?>) : Effect`.
  `args` holds only plain values (`String`, `Long`, `Double`, `Boolean`, `null`, `List`, `Map`),
  so prototypes still compare by value on reload. Fix every `when (effect)` the compiler now
  flags.
- `core/src/main/kotlin/gups/core/world/World.kt`:
  ```kotlin
  fun interface PluginEffectRunner {
      fun apply(world: World, effect: Effect.Plugin, caster: Actor, target: Actor?)
  }
  ```
  On `World`: `var pluginEffects: PluginEffectRunner = PluginEffectRunner { _, _, _, _ -> }` and
  `val pluginGmcp: MutableMap<String, (Player) -> Any?> = LinkedHashMap()`.
- `core/src/main/kotlin/gups/core/world/Combat.kt`, `World.applyEffect(effect, caster, target, skill)`:
  add a top-level branch `is Effect.Plugin -> pluginEffects.apply(this, effect, caster, target)`
  next to `is Effect.Sequence` (not inside the `else` branch, which needs a target).
- `core/src/main/kotlin/gups/core/world/Gmcp.kt`:
  - `val ENGINE_GMCP_PACKAGES: Set<String>` with the seven names of §5.1, kept in sync with
    `STATE_PACKAGES` and the event packages.
  - `gmcpDeltas(s: Session)`: after the `STATE_PACKAGES` loop, do the same for each `pkg` in
    `pluginGmcp`, calling the producer inside `runCatching`. A failure records
    `Diagnostic(pulse, "plugin", "GMCP producer $pkg failed: $e", e)` and skips that package for
    this pulse.
- `core/src/main/kotlin/gups/core/world/Commands.kt`, `CommandRegistry`:
  - `private val plugins = mutableListOf<CommandDef>()`;
  - `fun registerPlugin(command: CommandDef)`: `require` that the name and aliases match no
    engine command name or alias, no standard direction or direction alias (any word
    `Direction.parse` turns into a member of `Direction.STANDARD`), and no plugin command;
  - `fun isPluginCommand(name: String): Boolean`;
  - `registerGlobal`: `require(!isPluginCommand(command.name))`;
  - `clearGlobals()` is unchanged (it clears only `global`);
  - `resolve`: try `exact(plugins, v)` right after `exact(global, v)`, and `prefix(plugins, v)`
    last;
  - add `val pluginCommands: List<CommandDef>` for `help` and the docs.
- `script/src/main/kotlin/gups/script/WorldModule.kt`:
  - `SPEC` gets `DeclParam("plugins", "dict[str, str]", "Plugin jars in plugins/<id>.jar, each pinned by its 'sha256:<hex>' checksum.", default = "{}")`.
  - `WorldModule` gets `val plugins: Map<String, String> = emptyMap()`, read in `Reader.module`'s
    `SPEC.bind` block with `d.dict("plugins")`. Each value must be a string, else
    `fail("module(plugins): …")`.
  - `problems()` gets the §5.3 checks.
  - This is an API addition: regenerate `api.golden.json` (`-Dgups.updateGolden=true`). If M7.3
    has landed, also append to `## api_version 1` in `docs/CHANGELOG.md`:
    ```markdown
    ### Added `module.plugins`
    - kind: added
    - symbol: module.plugins

    `MODULE.gups` can pin plugin jars.
    ```
- `script/src/main/kotlin/gups/script/Declarations.kt`: add a public
  `fun pluginEffect(pluginId: String, name: String, args: Map<String, Any?>): StarlarkValue = EffectDecl(Effect.Plugin(pluginId, name, args))`
  (`EffectDecl` stays internal).
- **The running world, from a thread** (`Host.kt`): right after a `Frame` is created for a
  hook call, `thread.setThreadLocal(ViewScope::class.java, frame)`. Add a public
  `fun scriptWorld(thread: StarlarkThread): World? = thread.getThreadLocal(ViewScope::class.java)?.world`.
  This is how plugin builtins find their world, with no plugin-side thread-local.
- `script/src/main/kotlin/gups/script/WorldPluginWiring.kt`:
  ```kotlin
  /** What the script layer needs from the world's plugins; `:plugin-api` implements it (M7.4). */
  interface WorldPluginWiring {
      /** Plugin namespaces by id, predeclared in every package and test file. */
      val globals: Map<String, Any>
      /** Registers plugin commands, effects and GMCP producers. Call before StarlarkHost.install. */
      fun install(world: World)
      /** Runs the onWorldStarted callbacks. Call after World.fireOnLoad. */
      fun started(world: World)

      companion object {
          val NONE: WorldPluginWiring = object : WorldPluginWiring {
              override val globals: Map<String, Any> = emptyMap()
              override fun install(world: World) = Unit
              override fun started(world: World) = Unit
          }
      }
  }
  ```
- `ScriptLoader` (`Loader.kt`): new last constructor parameter
  `val pluginGlobals: Map<String, Any> = emptyMap()`. Every place that evaluates a file with
  `PACKAGE_GLOBALS` (the header and content passes, and test files) uses
  `PACKAGE_GLOBALS + pluginGlobals`. After a file runs, any top-level global it defined whose
  name is in `pluginGlobals` is a
  `LoadError(pkg, "plugin", "cannot define '$name': it is the namespace of plugin '$name'", file, line)`.
- `StarlarkHost`:
  - `stage(dir)` builds its loader as
    `ScriptLoader(dir, limits, pluginGlobals = loader?.pluginGlobals.orEmpty())`, so deploys see
    the plugins (reload already reuses `loader`);
  - the `@eval` module (`PACKAGE_GLOBALS + mapOf("ctx" to …)`) adds the plugin globals too;
  - `install`: a global command that `world.commands.isPluginCommand(name)` is skipped with
    `world.record(Diagnostic(world.pulse, "load", "global_command '$name' clashes with a plugin command; skipped", pkg = pkg.path))`.
- **Every place that builds a world** passes plugins through: it constructs `ScriptLoader(…,
  pluginGlobals = plugins.globals)`, calls `plugins.install(world)` before `host.install(world)`,
  and `plugins.started(world)` after `world.fireOnLoad()`. As of M7.1 these are:
  - `harness`: `Runner.kt` (`TestRunner`, which hands its wiring to each `TestCtx`),
    `Assertions.kt` (`TestCtx.world`), `FuzzWalker.kt` and `Repro.kt`;
  - `script`: `Linter.kt`;
  - `cli`: `GupsServer.kt`, `MigrationChecker.kt`, and the `--base` loader in `Commands.kt`.

  Each gets a last parameter `plugins: WorldPluginWiring = WorldPluginWiring.NONE`. Re-run
  `grep -rn 'ScriptLoader(\|StarlarkHost(' */src/main` to catch new sites.

Done when `./gradlew check` passes with no behavior change (every wiring is `NONE`) and new
unit tests cover: `Effect.Plugin` reaching `pluginEffects`; a plugin GMCP producer sending
deltas, including one that throws; `registerPlugin` refusing `look`, `north`, `n` and a
duplicate; `clearGlobals()` keeping plugin commands; `module(plugins = …)` validation; and the
`ScriptLoader` clash error.

#### Chunk 2 — The API and the facade (`:plugin-api`)

- `plugin-api/build.gradle.kts`:
  ```kotlin
  dependencies {
      implementation(project(":core"))
      implementation(project(":script"))
      api(libs.starlark)
  }
  ```
- `plugin-api/src/main/kotlin/gups/pluginapi/`: everything in §5.1 and §5.2's interfaces, one
  file per topic (`PluginApi.kt`, `Facade.kt`, `PluginViews.kt`). Replace the placeholder
  `Package.kt` if it no longer has a purpose.
- `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`: `internal` implementations of
  `PluginWorld`, `PluginRoom`, `PluginActor` and `PluginItem` over a `World` and its entities,
  each verb calling what the matching `Ctx` verb calls. `PluginViews.room/actor/item` unwrap
  `RoomView.room`, `ActorView.actor` and `ItemView.item` (public properties in `:script`).
  `PluginViews.world(thread)` is `scriptWorld(thread)` wrapped in the facade.

Done when a unit test drives every facade verb against a small `World` (build one the way
`TestCtx.world` does), and a reflection test checks every public class in package
`gups.pluginapi` (not `gups.pluginapi.host`). The test lists the classes explicitly and asserts
that no public method's parameter or return type, and no property's type, has a name starting
with `gups.core.` or `gups.script.`.

#### Chunk 3 — Namespaces, registry, loader and host (`gups.pluginapi.host`)

- `PluginNamespace(pluginId, members: Map<String, Any>)`: implements `StarlarkValue` and
  `net.starlark.java.eval.Structure` (`getValue`, `getFieldNames`, and
  `getErrorMessageForUnknownField` → `"plugin '$pluginId' has no member '$name'"`), with
  `isImmutable() = true` so packages can share it.
- `PluginBuiltin` and `PluginEffectPrimitive`: `StarlarkCallable`s implementing §5.1's binding,
  conversion and world lookup. Override `getName()` and
  `call(thread: StarlarkThread, args: Tuple, kwargs: Dict<String, Any>)`; the default `fastcall`
  delegates to `call`. `Structure.getFieldNames()` returns a Guava `ImmutableCollection`.
- `PluginRegistryImpl`: collects one plugin's registrations and validates as it goes:
  - builtin and effect names match `[a-z][a-z0-9_]*` and are unique within the plugin;
  - command names are non-blank, contain no whitespace, and clash with no engine command,
    direction or earlier plugin's command (check against a fresh `CommandRegistry()`'s engine
    commands);
  - GMCP packages follow §5.1.
  Any violation throws, so `register` fails atomically.
- `PluginHost : WorldPluginWiring, AutoCloseable`:
  - `globals`: one `PluginNamespace` per loaded plugin;
  - `warnings: List<String>`;
  - `install(world)`:
    - registers each command as a `FastCommand` or `TickCommand` (wrap a `TickCommand` as
      `StarlarkHost.install` wraps tick-lane global commands), with its `PluginRole` mapped to
      `Role`;
    - sets `world.pluginEffects` to dispatch on `(pluginId, name)`;
    - puts each producer in `world.pluginGmcp`;
    - wraps every callback in `runCatching`, recording a `"plugin"` diagnostic on failure;
  - `started(world)`: runs each `onWorldStarted` callback in plugin-id order, in `runCatching`;
  - `fun describe(): List<PluginInfo>`: ids, docs, members with their params, commands and GMCP
    packages, for `gups docs`. `PluginInfo` is a plain data class in this package; `:cli` maps
    it to `PluginDocSpec`.
- `PluginLoader.load(dir: WorldDir, module: WorldModule, parent: ClassLoader = GupsPlugin::class.java.classLoader, log: (String) -> Unit = {}): PluginHost`.
  For each `(id, checksum)` of `module.plugins`, sorted by id, it runs the §5.3 checks in order:
  1. jar present;
  2. checksum;
  3. exactly one provider (`ServiceLoader.load(...).stream().toList()`; instantiating it may
     throw, which also skips the plugin);
  4. `id` matches;
  5. version matches;
  6. `register`, clashes included.

  Warning texts start with `plugin '<id>': ` (for example
  `plugin 'weather': checksum mismatch for plugins/weather.jar (expected sha256:…, got sha256:…)`).
  Each warning goes to `log` and `warnings`. A skipped plugin's class loader is closed at once.
  Unpinned jars produce `plugins/<name>.jar is not pinned in MODULE.gups; ignoring it`.

Done when `PluginLoaderTest` covers each failure of §5.3 (each skips only its own plugin while a
second, valid plugin loads), an unpinned jar, and a successful load whose namespace exposes a
builtin and an effect primitive.

#### Chunk 4 — The CLI, the server and deploys (`:cli`)

- A helper `loadPlugins(dir: WorldDir, module: WorldModule, log: (String) -> Unit): PluginHost`
  used by `test`, `lint`, `fuzz`, `migrate-check`, `repro` and `docs --world`. Each closes the
  host when done (`use { }`) and passes it to the classes from chunk 1.
- `Docs --world`: `DocsRenderer.render(…, plugins = host.describe().map(::toDocSpec))`.
- `GupsServer.boot()`:
  1. after `WorldModule.read` and its problems, load the plugins, logging each warning as
     `warning: …`;
  2. build the loader with `pluginGlobals = plugins.globals`. Packages using a missing plugin
     fail to load and are left out, as any failed package is, so no retry is needed;
  3. construct the `World`, then `plugins.install(world)` before `host.install(world)`;
  4. after `world.fireOnLoad()`, call `plugins.started(world)`;
  5. once the world exists, record each warning with
     `world.record(Diagnostic(world.pulse, "plugin", it))` and `world.alert("plugin_load", it)`;
  6. render `/docs/` with the plugins;
  7. close the host when the server stops.
- `Deployer.deploy`: next to the `MODULE.gups` refusal, add
  `if (files.any { it.startsWith("plugins/") }) throw Refused("plugins/ changed between $from and $target: plugin changes need a restart.")`.
  The staged load gets the plugin globals through `host.stage` (chunk 1). The swap keeps plugin
  commands, effects and GMCP, because they live on the `World` and in `plugins`, which
  `clearGlobals()` doesn't touch. **Don't call `plugins.install` again after a swap:** the
  commands would be registered twice and `registerPlugin` refuses that.

Done when the `PluginIntegrationTest` cases below pass.

#### Chunk 5 — End-to-end tests

- Build jars in `@TempDir` from test plugin classes in
  `plugin-api/src/test/kotlin/gups/pluginapi/testplugins/` (top-level public classes with a
  no-argument constructor). Write the `META-INF/services` file only into the jar, never into
  test resources, or every loader would also find it through the parent class loader:
  ```kotlin
  fun buildPluginJar(jar: Path, vararg classes: KClass<out GupsPlugin>): String {
      Files.createDirectories(jar.parent)
      JarOutputStream(Files.newOutputStream(jar)).use { out ->
          out.putNextEntry(JarEntry("META-INF/services/${GupsPlugin::class.java.name}"))
          out.write(classes.joinToString("\n", postfix = "\n") { it.java.name }.toByteArray())
          out.closeEntry()
          for (c in classes) {
              val path = c.java.name.replace('.', '/') + ".class"
              out.putNextEntry(JarEntry(path))
              out.write(c.java.classLoader.getResourceAsStream(path)!!.use { it.readBytes() })
              out.closeEntry()
          }
      }
      val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))
      return "sha256:" + digest.joinToString("") { "%02x".format(it) }
  }
  ```
  The classes still load from the test classpath (the parent loader goes first). That is fine,
  because the jar carries the descriptor, and M7.5 tests a real built jar.
- `cli/src/test/kotlin/gups/cli/PluginIntegrationTest.kt`, with a temp world whose `MODULE.gups`
  pins the jars, covering:
  - every extension point: a builtin called from an `on_enter` hook; a fast and a tick command
    run with `p.do`; an effect primitive in a skill and inside `sequence([...])`; GMCP deltas
    for a client that supports the package; `onWorldStarted`;
  - clashes: plugin id `room` (a `MODULE.gups` problem); a package defining `weather = 1`; a
    command `look`, `north` or another plugin's; a script `global_command` named like a plugin
    command (skipped with a diagnostic); GMCP `Char.Vitals`, `Char` and another plugin's
    package;
  - failures: a checksum mismatch, a missing jar, a wrong `pluginApiVersion` and a throwing
    `register` each skip only that plugin. A package using a skipped plugin is left out at
    boot, and the server starts with the rest;
  - deploys: a deploy touching `plugins/` is refused, and a deploy of other changes keeps plugin
    commands working (extend the existing deploy tests; find them with `grep -rn "Deployer(" cli/src/test`);
  - `gups test` on that world runs a `*_test.star` that uses the plugin.

Done when `./gradlew check` passes and the checklist in §5.7 holds.

### 5.6 Pitfalls

- `plugin-api` must use `implementation` for `:core` and `:script`. No public type in package
  `gups.pluginapi` may mention their types; `gups.pluginapi.host` may.
- There is no `TA` role or `COMBAT` lane: roles and lanes mirror `core`'s `Role` and `Lane`.
- Room flags are a closed list (`always_active`, `dead_end_ok`); don't invent flags. Region tags
  are free-form strings.
- The ctx verb for affects takes `duration` and `stat_deltas`, not `pulses` and `mods`.
- `Diagnostic`'s third constructor argument is the message: use named arguments past
  `kind`.
- Don't register plugin commands twice on one `World`.

### 5.7 Checklist

- [ ] `plugin-api/build.gradle.kts` uses `implementation` for `:core` and `:script` and
      `api(libs.starlark)`; the reflection test passes.
- [ ] All five extension points work end to end, in the server and in `gups test`.
- [ ] Every §5.3 failure skips only its plugin; unpinned jars warn.
- [ ] Every clash in chunk 5 is refused.
- [ ] Deploys that touch `plugins/` are refused; other deploys keep plugins working.
- [ ] `./gradlew check` passes; decisions are logged; `docs/SPEC.md` is untouched.

### 5.8 Settled questions

- **Q5 (settled).** Plugin builtins and effect primitives are fields of one global named by
  the plugin id (§5.1).
- **Q6 (settled).** Plugins see only the `PluginWorld` facade and `plugin-api` types;
  `core` and `script` become `implementation` dependencies of `plugin-api` (§5.2).
- **Q7 (settled).** Plugin jars live in the course repository's `plugins/`, pinned by checksum
  in `MODULE.gups` (§5.3).
- **Q8 (settled).** Plugins are trusted and unsandboxed; checksum pinning, instructor-owned
  review and a restart are the controls (§5.4).

---

## 6. M7.5 — The Sample Plugin

**Goal:** one sample that shows every extension point, built and tested with the engine.

### 6.1 The sample

**Decision (Q9):** a `weather` plugin in a new `samples/weather-plugin` Gradle project
(`:samples:weather-plugin`). It depends only on `:plugin-api`.

A room is **outdoors** when it carries at least one region tag. Its **region** is the first tag
in sorted order. Rooms with no tag are indoors.

| Extension point | What the sample adds |
|---|---|
| Builtin | `weather.now(room)`: `"clear"`, `"rain"` or `"storm"` for an outdoor room, `"clear"` for an indoor one. It is a pure function of `world.seed`, `world.pulse / 240` (a one-minute weather window at 250 ms per pulse) and the region. |
| Command | `weather` (`FAST`, `PLAYER`): `The weather here is <state>.` outdoors, `You are indoors.` indoors. |
| Effect primitive | `weather.soak(pulses = 2)`: applies the affect `soaked`, with `stat_deltas` `{"dex": -1}`, for `pulses` pulses to the target. It does nothing when the skill has no target. |
| GMCP | `Gups.Weather`: `{"state": <state>, "region": <region or null>}`. |
| World hook | `onWorldStarted`: logs `weather: tracking <n> region(s): <tags, comma-separated>`. |

```kotlin
fun stateFor(world: PluginWorld, room: PluginRoom): String {
    val region = room.regions.minOrNull() ?: return "clear"
    var z = world.seed xor (world.pulse / 240L) * -0x61c8864680b583ebL xor region.hashCode().toLong()
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    z = z xor (z ushr 31)
    return STATES[((z ushr 1) % STATES.size).toInt()] // STATES = listOf("clear", "rain", "storm")
}
```

- **Module graph (Q10):** `checkModuleGraph` covers `samples/*` and allows them only
  `:plugin-api`, so the sample proves the facade is enough to write a real plugin. The check
  reads the main configurations of samples, not their test ones, so the sample's tests may use
  the harness.
- **Usage:** `template-world` gains no dependency on it. The sample's tests build a fixture world
  in a temp directory with the built `weather.jar` pinned in `MODULE.gups`, so `gups test` with
  plugins is covered end to end.

### 6.2 The engine-developer guide

`docs/PLUGINS.md` covers:
- building a plugin against `plugin-api`: the Gradle setup, `GupsPlugin`, `PluginApi.VERSION`,
  and `META-INF/services/gups.pluginapi.GupsPlugin`;
- the facade (`PluginWorld`, the views, `PluginViews`);
- each extension point, using `WeatherPlugin` as the running example;
- how to put a jar in `plugins/<id>.jar` and pin it in `MODULE.gups`, and how to compute the
  checksum (`shasum -a 256`);
- the trust model, with a review checklist for instructors;
- the fact that there are no compatibility promises (§1.2): any facade change bumps
  `PluginApi.VERSION`, and plugins must be rebuilt.

`gups docs` links it. `plugins.html` starts with the guide, rendered by `Markdown.toHtml`, and
then lists the world's plugins. `index.html` links it under "Writing plugins".

### 6.3 Implementation chunks

#### Chunk 1 — Gradle and the module graph

- `settings.gradle.kts`: add `"samples:weather-plugin"` to `include(...)`. Gradle then also
  creates an empty `:samples` project with no build file.
- Root `build.gradle.kts`:
  - `val codeProjects = subprojects.filter { it.buildFile.isFile }`. Use it instead of
    `subprojects` in `declaredModuleDeps` and in the root `check` task's `dependsOn`, so
    `:samples` (no build file, no `check` task) is skipped.
  - `declaredModuleDeps` today reads **every** configuration, test ones included. For projects
    under `:samples:`, read only configurations whose name doesn't start with `test`. Leave
    the other modules as they are:
    ```kotlin
    p.configurations
        .filter { !p.path.startsWith(":samples:") || !it.name.startsWith("test") }
        .flatMap { it.dependencies.withType<ProjectDependency>() }
    ```
  - `allowedModuleDeps`: add `":samples:weather-plugin" to setOf(":plugin-api")`.
- `samples/weather-plugin/build.gradle.kts`:
  ```kotlin
  plugins {
      id("gups.kotlin-module")
  }

  dependencies {
      implementation(project(":plugin-api"))
      testImplementation(project(":core"))
      testImplementation(project(":script"))
      testImplementation(project(":harness"))
  }

  tasks.jar {
      archiveFileName = "weather.jar"
      // The service descriptor goes only into the jar, so the test classpath doesn't also
      // offer the plugin through the parent class loader (M7.5 §6.3).
      from(layout.projectDirectory.dir("src/plugin"))
  }

  tasks.test {
      val jar = tasks.jar.flatMap { it.archiveFile }
      inputs.file(jar)
      systemProperty("gups.sample.weatherJar", jar.get().asFile.absolutePath)
      val templateWorld = rootProject.layout.projectDirectory.dir("template-world")
      inputs.dir(templateWorld)
      systemProperty("gups.templateWorld", templateWorld.asFile.absolutePath)
  }
  ```
  Put the descriptor at
  `samples/weather-plugin/src/plugin/META-INF/services/gups.pluginapi.GupsPlugin`, containing
  `gups.sample.weather.WeatherPlugin`.

Done when `./gradlew checkModuleGraph` passes, and fails if you temporarily add
`implementation(project(":core"))` to the sample (try it, then revert).

#### Chunk 2 — `WeatherPlugin`

`samples/weather-plugin/src/main/kotlin/gups/sample/weather/WeatherPlugin.kt`, importing only
`gups.pluginapi.*` (and Kotlin):

```kotlin
class WeatherPlugin : GupsPlugin {
    override val id = "weather"
    override val pluginApiVersion = PluginApi.VERSION
    override val doc = "Deterministic weather by region: a sample of every plugin extension point."

    override fun register(r: PluginRegistry) {
        r.addBuiltin(
            name = "now",
            doc = "The weather in a room: 'clear', 'rain' or 'storm'. Indoor rooms are always 'clear'.",
            params = listOf(PluginParam("room", "room", "The room to look at.")),
            returnType = "str",
        ) { world, pos, _ ->
            val room = pos[0] as? PluginRoom ?: throw IllegalArgumentException("weather.now(room): expected a room")
            stateFor(world, room)
        }
        r.addCommand("weather", PluginLane.FAST, PluginRole.PLAYER, "Describes the weather where you stand.") { call ->
            val room = call.actor.room
            call.world.send(call.actor, if (room.regions.isEmpty()) "You are indoors." else "The weather here is ${stateFor(call.world, room)}.")
        }
        r.addEffectPrimitive(
            name = "soak",
            doc = "Soaks the target: dex -1 for the given number of pulses.",
            params = listOf(PluginParam("pulses", "int", "How long the target stays soaked.", required = false, defaultValue = 2L)),
        ) { call ->
            val target = call.target ?: return@addEffectPrimitive
            val pulses = (call.args["pulses"] as Number).toInt()
            call.world.applyAffect(target, "soaked", duration = pulses, statDeltas = mapOf("dex" to -1))
        }
        r.addGmcp("Gups.Weather", "The weather where the player stands.") { world, actor ->
            mapOf("state" to stateFor(world, actor.room), "region" to actor.room.regions.minOrNull())
        }
        r.onWorldStarted { world ->
            val regions = world.regions()
            world.log("weather: tracking ${regions.size} region(s): ${regions.joinToString(", ")}")
        }
    }

    companion object {
        private val STATES = listOf("clear", "rain", "storm")

        /** §6.1: a pure function of the seed, the one-minute window and the room's region. */
        fun stateFor(world: PluginWorld, room: PluginRoom): String {
            val region = room.regions.minOrNull() ?: return "clear"
            var z = world.seed xor (world.pulse / 240L) * -0x61c8864680b583ebL xor region.hashCode().toLong()
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            return STATES[((z ushr 1) % STATES.size).toInt()]
        }
    }
}
```

Done when it compiles against `:plugin-api` alone and `./gradlew :samples:weather-plugin:jar`
produces `weather.jar` containing the descriptor and the class.

#### Chunk 3 — Tests

- `samples/weather-plugin/src/test/kotlin/gups/sample/weather/WeatherPluginTest.kt`:
  - **Unit tests against `plugin-api` only.** Write small fakes of `PluginRegistry`,
    `PluginWorld`, `PluginRoom` and `PluginActor`; unused members can `TODO()`. Register the
    plugin into the fake registry, then check:
    - `stateFor` is the same for the same seed, pulse window and region, and is `"clear"` with
      no region;
    - the command's two messages;
    - `soak` calls `applyAffect(target, "soaked", 3, {"dex": -1})` for `pulses = 3`;
    - the GMCP map;
    - the `onWorldStarted` log line.
  - **End to end:** copy `gups.sample.weatherJar` to `<tmp>/plugins/weather.jar`, compute its
    checksum, and write:
    - `MODULE.gups`: `module(api_version = 1, plugins = {"weather": "sha256:…"})`;
    - `rules/`: copied from `gups.templateWorld`;
    - `zones/valley/package.star`: `package(api_version = 1, kind = "zone")`;
    - `zones/valley/rooms.star`: room `cabin` (no regions, exit `north` to `:field`); room
      `field` (`regions = ["meadow"]`, exit `south` to `:cabin`, and an `on_enter` hook doing
      `ctx.send(ctx.actor, "The sky is " + weather.now(ctx.room) + ".")`); and
      `skill("rain_call", …, effect = weather.soak(pulses = 3))`. Check `skill`'s parameters in
      `docs/api/api-1.json` or `api.golden.json` (for example `target`), and make it self-cast
      if the engine allows;
    - `zones/valley/valley_test.star`:
      - entering `:field` from `:cabin` prints `The sky is `;
      - `p.do("weather")` in `:cabin` contains `You are indoors.`, and in `:field` contains
        `The weather here is `;
      - a player spawned with the skill (`w.spawn_player(..., skills = …)`; check the
        parameter's doc for its format) who does `p.do("cast rain_call")`, then
        `w.run(pulses = …)`, has `soaked` among `[a.name for a in p.affects]`.

    Load the plugins with `PluginLoader.load(WorldDir(tmp), module)`, run
    `TestRunner(WorldDir(tmp), plugins = host).run(listOf("//zones/valley"))`, and assert no
    problems, every result `PASS`, and no warnings. Also assert the loader's `log` received
    `weather: tracking 1 region(s): meadow` when a test world started.
- Don't touch `template-world`.

Done when `./gradlew :samples:weather-plugin:test` passes and is part of `./gradlew check`.

#### Chunk 4 — `docs/PLUGINS.md` and `plugins.html`

- Write `docs/PLUGINS.md` (§6.2). Its code samples must match the sample's real code. Copy from
  `WeatherPlugin.kt`; don't paraphrase it.
- `cli/build.gradle.kts`: bundle it next to the changelog
  (`from(rootProject.file("docs/PLUGINS.md")) { into("gups/docs") }`).
- `DocsRenderer`: `plugins.html` renders the bundled guide with `Markdown.toHtml`, then the
  world's plugins (§3.1). `index.html` links `plugins.html` as "Writing plugins". Regenerate the
  docs golden files.
- `README.md`: point engine developers to `docs/PLUGINS.md`.

Done when `gups docs` writes a `plugins.html` with the guide, the link checks of M7.2 chunk 1
still pass, and `./gradlew check` passes.

### 6.4 Pitfalls

- `samples/weather-plugin` must not use `:core` or `:script` in its main code. The module graph
  check catches it.
- Don't add `plugins = …` or a jar to `template-world`.
- `weather.now` must not use `Random`, `Math.random()` or the clock: only seed, pulse and region.
- `String.hashCode()` is stable across JVMs, so `stateFor` is deterministic. Don't swap in a
  hash that isn't.

### 6.5 Checklist

- [ ] `:samples:weather-plugin` compiles against `:plugin-api` only, enforced by
      `checkModuleGraph`.
- [ ] All five extension points are implemented and tested, and the end-to-end test runs the
      built `weather.jar` through `gups test`'s `TestRunner`.
- [ ] `template-world` doesn't use the plugin.
- [ ] `docs/PLUGINS.md` covers §6.2, and `gups docs` renders and links it.
- [ ] `./gradlew check` passes; decisions are logged; `docs/SPEC.md` is untouched.

### 6.6 Settled questions

- **Q9 (settled).** The sample is the `weather` plugin (§6.1).
- **Q10 (settled).** `samples/*` may depend only on `:plugin-api`, enforced by
  `checkModuleGraph` (§6.1).

---

## 7. Out of M7

These stay out, as SPEC.md or this design leaves them:
- The load test of §7.1, deferred from M6 to a later milestone.
- Stabilizing the script API (§1.3).
- A plugin marketplace, plugin hot reload, and sandboxing.
- Automating the `api_version` bump in the release job (§4.3 explains why it is a pre-tag step).
- Everything in DESIGN_NEEDED.md.
