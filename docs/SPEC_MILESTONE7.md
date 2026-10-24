# Milestone 7 — Design

Status: draft for review. Open questions Q1–Q10 are settled; the other proposals await
review.

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
| **M7.3** Changelog and migration artifacts | A structured changelog, the generated `MIGRATING.md`, a machine-readable change list, and a CI check that API changes are recorded | M7.1 | Changing a builtin without a changelog entry fails engine CI |
| **M7.4** The plugin API | `GupsPlugin`, `PluginRegistry`, loading from `plugins/`, and failure handling (§6.4) | M7.1 | A plugin jar adds a builtin, a command, an effect primitive and a GMCP package |
| **M7.5** The sample plugin | One sample plugin using every extension point, plus the engine-developer guide | M7.4 | The sample builds, loads in `serve` and in `gups test`, and its tests pass |

M7.4 and M7.5 can be built alongside M7.2 and M7.3.

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

---

## 3. M7.2 — `gups docs`

**Goal:** the reference that §1.3 and §5.13 promise, generated from the M7.1 model.

### 3.1 Output

`gups docs [--out DIR] [--world DIR]` writes:

| File | Contents |
|---|---|
| `index.html` | Overview, the `api_version`, the engine version, and links to every section |
| `declarations.html` | `package`, `room`, `exit`, `door`, `mob`, `item`, `skill`, `region`, `spawn`, `loot`, `container`, `global_command`, `struct` |
| `hooks.html` | The hook catalog of §4.4: what each hook may veto, what it attaches to, and its ctx fields |
| `ctx.html` | `ctx` fields and verbs, and the actor, room, item, exit, affect, skill and proposal views |
| `effects.html` | Effect primitives and behaviors |
| `rules.html` | The rules exports, their ctx, and the modifier points |
| `harness.html` | `t`, `w` and `p` |
| `changelog.html` | The rendered changelog (M7.3) |
| `api.json` | The model itself (M7.1) |

- **Style:** pages are static, self-contained HTML with one stylesheet and no JavaScript, so
  they work offline and from a file system.
- **Plugins:** with `--world`, builtins from the world's plugins (M7.4) are included and
  marked as coming from a plugin.
- **Examples:** every entry may carry a short example. The examples in the reference are run
  as tests (§3.3), so they can't rot.

### 3.2 Where the docs go

- **Releases:** each engine release attaches the generated docs as a zip.
- **Images:** the `jlink` and OCI images include them.
- **The server:** `--prod` serves them at `https://host/docs/` on the M6.4 HTTPS listener,
  with no login (Q2). `--dev` serves them at `http://127.0.0.1:4080/docs/` (`--docs-port`), so
  students read the docs of the engine they are running. The served copy is generated at
  startup with the world's plugins included (§3.1).

### 3.3 Tests

- Golden-file tests of the generated HTML for a small fixture model.
- Every example in the model is evaluated in the harness and must pass.
- Links between pages resolve.

### 3.4 Settled questions

- **Q2 (settled).** The server serves the docs at `/docs/` with no login, in both `--prod`
  and `--dev` (§3.2).

---

## 4. M7.3 — Changelog and Migration Artifacts

**Goal:** what §1.3 asks of every release: a changelog of breaking changes, a `MIGRATING.md`
with before/after snippets, and migration artifacts that a local LLM can consume.

### 4.1 The structured changelog

**Proposal:** `docs/CHANGELOG.md` stays a human-written Markdown file, in a strict shape that
`gups docs` parses:

````markdown
## api_version 2

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
````

- **`kind`:** one of `removed`, `renamed`, `signature`, `behavior` or `added`. Only `added`
  isn't breaking.
- **`symbol`:** a path in `api.json`, such as `ctx.act_move`, `room.size` or `hook:on_wake`.
- **`replacement`:** optional.
- **Snippets:** zero or more `before`/`after` pairs.
- **Anchors:** each `## api_version N` heading is the anchor that the `api_version` mismatch
  diagnostic already links to (`CHANGELOG.md#api_version-N`, §1.3).

### 4.2 Generated artifacts

- **`MIGRATING.md`:** generated from the changelog. It holds every breaking entry with its
  snippets, grouped by `api_version`. It is no longer hand-edited, and CI checks that it is up
  to date.
- **`changes.json`:** the changelog as data. It is included in the docs and in the images.
- **`gups migrate-info <package>`:** for the course's LLM migration exercise (§1.3). It loads
  a package that targets an older `api_version` far enough to list every use of a changed
  symbol, with file and line. It prints the matching changelog entries next to each use, as
  text or JSON, to feed to a model along with the package.

### 4.3 The API-change check

- **Baseline:** engine CI regenerates `api.json` and compares it with a committed baseline,
  `docs/api/api-<api_version>.json`.
- **Rules:** every difference from the last released baseline must be covered by a changelog
  entry, so additions need an `added` entry too (Q4).
- **When `api_version` rises (Q3):** once per engine release that contains a breaking change,
  not once per change.
  - Between releases, entries collect under a heading `## api_version N+1 (unreleased)`, where
    N is the last released version. The engine keeps accepting N, so the course world doesn't
    churn.
  - CI fails if a breaking entry sits under a released version.
  - The release job raises `EngineInfo.API_VERSION` to N+1, drops "(unreleased)", and commits
    the new baseline `docs/api/api-<N+1>.json`.
  - A release with only `added` entries keeps the version, since nothing that worked stops
    working.
- **Updating the baseline:** `gups docs --update-baseline` rewrites it once the changelog is
  written.

### 4.4 Tests

- Parsing the changelog, including errors that point to a line.
- `MIGRATING.md` generation.
- The check fails when a parameter is removed without an entry, and when a breaking entry
  lacks an `api_version` bump.
- `migrate-info` finds the uses of a renamed verb.

### 4.5 Settled questions

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

```kotlin
interface GupsPlugin {
    val id: String                       // [a-z][a-z0-9_]*, unique
    val pluginApiVersion: Int            // must equal PluginApi.VERSION
    fun register(r: PluginRegistry)
}

interface PluginRegistry {
    fun addBuiltin(name: String, value: StarlarkValue, doc: String)
    fun addCommand(name: String, lane: PluginLane, role: PluginRole = PluginRole.PLAYER, doc: String, run: (PluginCommandCall) -> Unit)
    fun addEffectPrimitive(name: String, doc: String, params: List<PluginParam>, apply: PluginEffect)
    fun addGmcp(pkg: String, doc: String, produce: (PluginWorld, PluginActor) -> Any?)
    fun onWorldStarted(fn: (PluginWorld) -> Unit)
}
```

- **Types:** every type in these signatures belongs to `plugin-api` or to the Starlark library
  (`StarlarkValue` and its annotations, which `plugin-api` exports). None comes from `core` or
  `script` (Q6).
- **Version:** `PluginApi.VERSION` is an integer that changes whenever this interface or the
  facade changes. Plugins built for another version are refused at load.
- **Builtins:** plugin builtins appear to scripts under the plugin's id, as fields of one
  namespace value (Q5). A plugin `weather` with builtin `now` is used as `weather.now()`.
  A plugin id can't equal an engine builtin's name, and loading refuses a package file that
  defines a global with a loaded plugin's id.
- **Effect primitives:** they appear the same way (`weather.storm(pulses = 3)`). They produce
  an `Effect.Plugin(pluginId, name, args)`, which `core` applies by calling the plugin. That
  needs one new case in the sealed `Effect` (§4.3).
- **Commands:** they go through the normal command registry (§5.2). They may not shadow engine
  commands, global commands, or another plugin's commands (as D-064).
- **GMCP:** a producer is called in phase 7 for clients that support the package, and only
  changed payloads are sent (SPEC_MILESTONE6.md §5.2).

### 5.2 What plugins can touch

**Decision (Q6):** plugins don't get raw `World` or entities. The `plugin-api` module exposes a
`PluginWorld` facade instead:
- the `ctx` verbs as Kotlin methods;
- read-only views of entities;
- `rng`, `pulse` and diagnostics.

This is the "narrow subset" of §2.2. Every mutation stays on the world thread and keeps the
§3.8 invariants, because it goes through the same verbs scripts use.
- **Build change:** `plugin-api` then depends on `core` and `script` with `implementation`
  instead of `api` (its build file currently exports both whole), so plugins compile only
  against the facade (Q6).

### 5.3 Loading

- **Where jars come from (Q7):** `plugins/*.jar` in the course world repository, each pinned
  by a SHA-256 in `MODULE.gups` (`plugins = {"weather": "sha256:…"}`). The server, `gups test`,
  `gups lint`, `gups fuzz` and CI all load the same jars. A jar in `plugins/` that
  `MODULE.gups` doesn't name is ignored, with a warning.
- **Loading:** one `URLClassLoader` per jar, with the engine's class loader as parent.
  `ServiceLoader<GupsPlugin>` finds the plugin in the jar.
- **Restarts:** loading happens once at startup. Adding, removing or changing a plugin is a
  `MODULE.gups` change, which needs a restart, like an engine upgrade (§5.10). A deploy that
  touches `plugins/` is refused.
- **Failures (§6.4):** if a jar fails its checksum, the version check, or `register`, the
  plugin is skipped and logged, and instructors are alerted. Packages that use its builtins
  then fail to load: on a restart the server starts without them, and in a deploy the deploy
  fails and the old version stays live.
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

### 5.5 Tests

- Loading a test plugin jar built in the test.
- Each extension point works.
- Name clashes are refused.
- A checksum mismatch, a version mismatch and an exception in `register` each skip only that
  plugin.
- A package using a missing plugin's builtin fails to load.
- Plugin effect primitives inside skills and affects.

### 5.6 Settled questions

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

**Decision (Q9):** a `weather` plugin in a new `samples/weather-plugin` Gradle project. It depends
only on `plugin-api`.

| Extension point | What the sample adds |
|---|---|
| Builtin | `weather.now(room)`: `"clear"`, `"rain"` or `"storm"` for a room's region. It is deterministic from the world seed and the pulse. |
| Command | `weather` (fast lane) describes the weather where you stand. |
| Effect primitive | `weather.soak(pulses)`: applies a `soaked` affect with `dex - 1`, for skills such as a rain spell. |
| GMCP | `Gups.Weather {"state"}` for clients that support it. |
| World hook | On world start, it registers the regions it knows and logs what it found. |

- **Module graph (Q10):** `checkModuleGraph` covers `samples/*` and allows them only
  `:plugin-api`, so the sample proves the facade is enough to write a real plugin.
- **Usage:** `template-world` gains no dependency on it. A separate fixture world under the
  sample's tests uses it, so `gups test` with plugins is covered end to end.

### 6.2 The engine-developer guide

`docs/PLUGINS.md` covers:
- building a plugin against `plugin-api`;
- the facade;
- each extension point;
- how to pin a jar in `MODULE.gups`;
- the trust model;
- the fact that there are no compatibility promises (§1.2).

`gups docs` links it.

### 6.3 Settled questions

- **Q9 (settled).** The sample is the `weather` plugin (§6.1).
- **Q10 (settled).** `samples/*` may depend only on `:plugin-api`, enforced by
  `checkModuleGraph` (§6.1).

---

## 7. Out of M7

These stay out, as SPEC.md or this design leaves them:
- The load test of §7.1, deferred from M6 to a later milestone.
- Stabilizing the script API (§1.3).
- A plugin marketplace, plugin hot reload, and sandboxing.
- Everything in DESIGN_NEEDED.md.
