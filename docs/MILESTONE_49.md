# Milestone 49: `gups describe-world`

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** None. This came from a maintainer design session. An external authoring tool (a separate
  repository, `gups-builder`) needs a machine-readable description of one course world, and nothing provides one today:
  `gups docs --json` describes only the engine's API and refuses `--world`.

- **Rules for every phase** (from `AGENTS.md`):

  - Test first: write the tests, run them and watch them fail, then write the code.
  - Do not cite spec sections or decision IDs in code comments. Comments explain why, not what.
  - Run `./gradlew check` outside the sandbox (`BypassSandbox: true`). All Kotlin warnings are errors.
  - After editing any Markdown file, run `./gradlew markdownFormat` (outside the sandbox), then check that code blocks
    inside lists kept their indentation.
  - Never read or touch `local-world/`.
- **Design Summary:**
  - **Command.** `gups describe-world [--world DIR] [--out PATH]` loads the world the way `gups lint` does, with its
    plugins, and writes one JSON document describing it. It writes to stdout without `--out`, and to `PATH` otherwise,
    creating parent directories. The output never depends on the server, the database or the network.
  - **Contents.** The world's schema, its features, its command renames, every package and whether it loaded, a short
    summary of every declaration that loaded (rooms, mobs, items, skills, factions, regions), each loaded plugin's
    builtin, effect primitive and trait names, and the load errors. Descriptions, hooks, function bodies, spawns,
    behaviors and loot are deliberately left out.
  - **Broken packages don't stop it.** A package that fails to load is listed with `"ok": false`, its errors go in
    `errors`, and everything that did load is still described. The command exits 0. Only a `MODULE.gups` that fails to
    read, or has `problems()`, exits 1, printing each problem to stderr as `error: <problem>`.
  - **Contract version.** The top-level `format` (an integer, `1`) is the version of this document's shape, separate
    from `api_version`. It changes only when a field is removed or changes meaning; adding a field does not change it.
  - **Determinism.** Keys appear in the fixed order of the schema below. Every list of declarations is sorted by label;
    packages by path; plugins by id; names inside sets (tags, regions, traits, flags, channels) alphabetically. Exits
    keep their declared order. Map-valued fields (`stats`, `vitals`, `resists`, `cost`, `skills`, `commands`) are sorted
    by key. Output is pretty-printed (the library's default indent) with `\n` line endings, and is byte-identical across
    runs and platforms.
  - **No script API change.** No builtin, hook, declaration or `api.json` entry changes, so there is no CHANGELOG entry
    and no `api_version` bump.
- **Specification Impact:** §4.13 (CLI): a new table row and a new `gups describe-world` bullet (Phase 2).
- **Decision Record Updates:** None.

### The JSON document (format 1)

This is the complete shape. Every key shown is always present (empty lists and maps are written, never omitted). `null`
appears only where noted.

```json
{
  "format": 1,
  "api_version": 1,
  "engine_version": "0.0.0-dev",
  "schema": {
    "stats": [{"name": "str", "label": "Strength"}],
    "vitals": [{"name": "hp", "label": "Health"}],
    "damage_types": [{"name": "fire", "label": "Fire", "adjective": "fiery"}],
    "unarmed_damage_type": "bludgeon",
    "channels": ["sight", "smell", "sound", "spell"]
  },
  "features": ["combat", "leveling", "skills", "track"],
  "commands": {"cast": "invoke"},
  "packages": [{"path": "zones/tutorial", "kind": "zone", "ok": true}],
  "rooms": [
    {
      "label": "//zones/tutorial:hall",
      "name": "The Hall of Lessons",
      "size": "large",
      "regions": [],
      "tags": [],
      "flags": [],
      "exits": [
        {"dir": "south", "to": "//zones/tutorial:gate", "one_way": false, "tags": [], "door": null},
        {"dir": "east", "to": "//zones/tutorial:armory", "one_way": false, "tags": [],
         "door": {"state": "locked", "key": "//zones/tutorial:brass_key", "tags": ["creaky"]}}
      ]
    }
  ],
  "mobs": [
    {
      "label": "//zones/tutorial:guide",
      "name": "the guide",
      "keywords": ["guide"],
      "level": 1,
      "stats": {"str": 10},
      "vitals": {"hp": 30},
      "resists": {"fire": -10},
      "skills": {"//skills/martial:kick": 40},
      "traits": [],
      "factions": null
    }
  ],
  "items": [
    {"label": "//zones/tutorial:brass_key", "name": "a brass key", "keywords": ["key", "brass"],
     "kind": "key", "wear": null, "weight": 1, "value": 0}
  ],
  "skills": [
    {"label": "//skills/arcane:spark", "kind": "spell", "target": "enemy", "range": "near",
     "cost": {"mana": 8}, "warmup": 0, "lag": 4, "channels": ["sound", "spell"], "tags": ["blue"]}
  ],
  "factions": ["//zones/tutorial:townsfolk"],
  "regions": ["goblin_territory"],
  "plugins": [{"id": "weather", "builtins": ["forecast"], "effects": ["drench"], "traits": ["soaked"]}],
  "errors": [{"package": "zones/broken", "file": "//zones/broken:rooms.star", "line": 12, "message": "..."}]
}
```

Field rules (the values in the sample above are illustrative, not the template's real data):

|              Field               |                                                                                    Source and rule                                                                                    |
|----------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `engine_version`                 | `EngineInfo` version, the same value `api.json` writes.                                                                                                                               |
| `schema.stats` / `schema.vitals` | `WorldSchema.stats` / `.vitals` (`SchemaName`), in declaration order (not sorted: the world's order is meaningful).                                                                   |
| `schema.damage_types`            | `WorldSchema.damageTypes` in declaration order; `label` and `adjective` from `damageTypeInfo` (default label as the engine renders it, `adjective` `null` when absent).               |
| `schema.channels`                | The built-in channels plus `WorldSchema.channels`, sorted.                                                                                                                            |
| `features`                       | `WorldModule.features.ids`, sorted.                                                                                                                                                   |
| `commands`                       | `WorldModule.rename` (old name → new name), sorted by key. Empty when nothing is renamed.                                                                                             |
| `packages`                       | Every path of `WorldDir.packages()`, sorted. `kind` is `ScriptPackage.kind.word` for a loaded package and `null` for one that failed. `ok` is whether it is in `LoadResult.packages`. |
| `rooms[].size`                   | `TrackSize.name.lowercase()`.                                                                                                                                                         |
| `rooms[].exits[].dir`            | `Direction.name`.                                                                                                                                                                     |
| `rooms[].exits[].door`           | `null` without a door; else `state` is `DoorState.name.lowercase()`, `key` the key label or `null`.                                                                                   |
| `mobs[].resists`                 | `MobProto.resists`, keyed by `DamageType.name`.                                                                                                                                       |
| `mobs[].factions`                | `MobProto.factions` as labels, or `null` when the prototype declares none (do not substitute the mob's own label).                                                                    |
| `skills[].range` / `.target`     | `SkillRange.name.lowercase()` / `TargetKind.name.lowercase()`.                                                                                                                        |
| `items`                          | Every item prototype except the reserved corpse prototype (`Prototypes.CORPSE`, whatever its exact constant is).                                                                      |
| `factions` / `regions`           | Labels of `FactionProto`s / names of `RegionProto`s, sorted.                                                                                                                          |
| `plugins`                        | `PluginHost.describe()`: `id`, and the `name`s of `builtins`, `effects` and `traits`, each sorted.                                                                                    |
| `errors`                         | `LoadResult.errors` in order: `package` (`LoadError.pkg`), `file` and `line` (`null` when absent), `message`. Test-file errors (`testErrors`) are not included.                       |

All labels are written in canonical form (`Label.v`, e.g. `//zones/tutorial:hall`).

---

## Phase 1: The world describer

### Objective & Scope

- **In Scope:**
  - A pure function that turns a loaded world into the JSON document above.
  - Unit tests against small temporary worlds.
- **Out of Scope:** *The agent must not attempt these in this phase.*
  - The Clikt command, `--out`, exit codes (Phase 2).
  - Golden files for `template-world` and `robot-world` (Phase 2).
  - Any spec or Markdown change (Phase 2).
  - Changing `Linter`, `ScriptLoader`, `LoadResult` or any prototype class. If a value you need isn't reachable from
    public API, stop and report it rather than widening visibility in `script` or `core`.

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Loader.kt` (`LoadResult`, `ScriptLoader`, `prototypes()`).
  - `script/src/main/kotlin/gups/script/Packages.kt` (`WorldDir.packages()`, `LoadError`, `ScriptPackage`,
    `PackageKind`).
  - `script/src/main/kotlin/gups/script/WorldModule.kt` (`WorldModule.read`, `schema`, `features`, `rename`,
    `problems()`).
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`RoomProto`, `ExitProto`, `DoorProto`, `MobProto`, `ItemProto`,
    `SkillProto`, `Prototypes`).
  - `core/src/main/kotlin/gups/core/model/Schema.kt`, `core/src/main/kotlin/gups/core/model/Spatial.kt`,
    `core/src/main/kotlin/gups/core/world/Features.kt`.
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt` (`PluginInfo`, `PluginHost.describe()`).
  - `script/src/main/kotlin/gups/script/Linter.kt` lines 117-140: the pattern for building a `ScriptLoader` with plugins
    and loading `dir.packages()`.
  - `cli/src/test/kotlin/gups/cli/CliTestFixtures.kt` (`tempWorld`).
- **Files to Modify / Create:**
  - `cli/build.gradle.kts`: add `implementation(libs.kotlinx.serialization.json)`. Only the JSON element builders are
    used, so the serialization compiler plugin is not needed.
  - Create `cli/src/main/kotlin/gups/cli/WorldDescriber.kt`.
  - Create `cli/src/test/kotlin/gups/cli/WorldDescriberTest.kt`.
- **Do Not Touch:** `script/`, `core/`, `plugin-api/`, `docs/`, `template-world/`, `samples/`.

### Specification & Decision Record Changes

- None in this phase.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
package gups.cli

/** The `format` of the JSON [describeWorld] writes. */
const val DESCRIBE_WORLD_FORMAT = 1

/**
 * Loads [dir] with [plugins] and describes it as the `gups describe-world` JSON document.
 * Returns null and adds to [problems] when MODULE.gups can't be read or has problems; a package
 * that fails to load never makes it return null.
 */
fun describeWorld(dir: WorldDir, plugins: WorldPluginWiring, describe: List<PluginInfo>,
                  problems: MutableList<String>): String?
```

`describe` is passed separately (the caller passes `pluginHost.describe()`) so tests can pass `WorldPluginWiring.NONE`
and `emptyList()`.

- **Algorithmic Steps & Invariants:**
  1. `val (module, readError) = WorldModule.read(dir)`. If `readError != null`, add its message to `problems`. Add every
     `module.problems()`. If `problems` is non-empty, return null.
  2. Build `ScriptLoader(dir, ScriptLimits(), plugins = plugins)` and `load(dir.packages())`, as `Linter.lint` does. Do
     not load test fixtures.
  3. Take `result.prototypes()` and build the document with `buildJsonObject`, inserting keys in exactly the order of
     the schema above. Write each section with a small private function (`schemaJson`, `roomJson`, `mobJson`, ...).
  4. Sorting: follow the Determinism bullet of the design summary. Never iterate a `Map` or `Set` from a prototype
     without sorting it first.
  5. Serialize with `Json { prettyPrint = true }` (do not set `prettyPrintIndent`: it is an experimental API, and its
     opt-in warning fails the build) and end the string with a single `\n`.
  6. Invariant: the function never throws because of a package's load error. An exception from the loader itself is not
     caught (it is an engine bug).

### Test & Documentation Requirements

- `cli/src/test/kotlin/gups/cli/WorldDescriberTest.kt`, using `tempWorld` with a minimal `MODULE.gups`, a `rules`
  package (copy the minimal rules that other `cli` tests use) and small zones:
  - `describes schema in declaration order`: stats and vitals keep the `MODULE.gups` order.
  - `room exits keep declared order and doors are described`: a room with two exits, one with a locked keyed door.
  - `mob fields`: level, stats, vitals, resists keyed by type name, skills by label, `factions` null when undeclared.
  - `skill fields`: range and target lowercased, cost sorted, channels sorted.
  - `corpse prototype is not listed in items`.
  - `failed package is listed and its errors reported`: a zone with a syntax error appears with `ok: false` and `kind:
    null`, `errors` has its file and line, and a second healthy zone is still fully described.
  - `module problems return null`: an unsupported `api_version` in `MODULE.gups` returns null and fills `problems`.
  - `output is deterministic`: two calls on the same world return identical strings.
- KDoc on `describeWorld` and `DESCRIBE_WORLD_FORMAT` only; no user docs yet.

### Exit Conditions

- [] `./gradlew :cli:test --tests "gups.cli.WorldDescriberTest"` passes.
- [] `./gradlew check` passes with zero warnings (outside the sandbox), including `checkModuleGraph`.
- [] No new comment mentions a spec section or a `D-` id.

---

## Phase 2: The command, golden files, spec and finalization

### Objective & Scope

- **In Scope:**
  - The `describe-world` Clikt command wired into `gups`.
  - Golden files for `template-world` and `robot-world`, compared on every test run.
  - The §4.13 spec text.
  - Finalization checks.
- **Out of Scope:** *The agent must not attempt these in this phase.*
  - Any change to the document's shape from Phase 1. If a golden file shows a value that looks wrong, fix the describer
    only when it contradicts the field rules of this milestone; otherwise leave it.
  - Label-pattern arguments, a `--format` option, or any other flag not listed here.
  - Anything in the `gups-builder` repository.

### Context Working Set

- **Read / Reference Only:**
  - `cli/src/main/kotlin/gups/cli/Commands.kt`: `Lint` (lines ~502-576) for the option style, `withPlugins` (line ~817),
    the `subcommands(...)` list (line ~75), and `Docs` for how `--out` is validated.
  - `cli/build.gradle.kts`: how `gups.apiGolden`, `gups.templateWorld` and `gups.robotWorld` are passed as system
    properties and declared as inputs.
  - `cli/src/test/kotlin/gups/cli/SampleWorldsTest.kt` and the `api.golden.json` test (search for `gups.apiGolden` and
    `gups.updateGolden`) for the golden-file pattern.
- **Files to Modify / Create:**
  - `cli/src/main/kotlin/gups/cli/Commands.kt`: add `DescribeWorld` and register it after `Docs()`.
  - `cli/build.gradle.kts`: pass the golden directory as a system property, mirroring `gups.apiGolden`.
  - Create `cli/src/test/kotlin/gups/cli/DescribeWorldCommandTest.kt`.
  - Create `cli/src/test/resources/describe-world/template-world.golden.json` and `robot-world.golden.json` (generated
    with `-Dgups.updateGolden=true`, then reviewed).
  - `docs/SPEC.md` §4.13.
- **Do Not Touch:** `script/`, `core/`, `plugin-api/`, `template-world/`, `samples/`, `docs/api/`, `docs/CHANGELOG.md`.

### Specification & Decision Record Changes

Add this row to the §4.13 table, directly after the `gups docs --update-baseline` row:

```
| `gups describe-world [--world DIR] [--out PATH]` | Write one world's schema and content summary as JSON. |
```

Add this bullet to §4.13, directly after the `gups scenario` bullet:

```markdown
- **`gups describe-world`:** loads the world and its plugins as `gups lint` does and writes one JSON document
  describing it, for external tools: to stdout without `--out`, else to `PATH` (parent directories are created).
  - Top-level keys, in this order: `format` (the document's own version, `1`, raised only when a field is removed or
    changes meaning), `api_version`, `engine_version`, `schema` (stats and vitals with labels and damage types with
    label and adjective, in declaration order; the unarmed damage type; all channels), `features`, `commands` (the
    world's renames), `packages` (path, kind, and whether it loaded), `rooms`, `mobs`, `items`, `skills`, `factions`,
    `regions`, `plugins` (each plugin's builtin, effect primitive and trait names) and `errors` (the load errors).
  - Each declaration is a summary: a room's name, size, regions, tags, flags and exits with their doors; a mob's name,
    keywords, level, stats, vitals, resists, skills, traits and factions; an item's name, keywords, kind, wear slot,
    weight and value; a skill's kind, target, range, cost, warmup, lag, channels and tags. Descriptions, hooks, spawns,
    behaviors and loot are not included.
  - A package that fails to load is listed with `ok` false and its errors; everything else is still described, and the
    command exits 0. A `MODULE.gups` that can't be read or has problems prints them and exits 1.
  - Output is deterministic: declarations sorted by label, packages by path, plugins by id, set-valued fields and maps
    sorted, exits in declared order.
```

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
class DescribeWorld : CliktCommand(name = "describe-world") {
  val world by option("--world", help = "Course world directory (default: the current directory).")
    .file(mustExist = true, canBeFile = false)
  val out by option("--out", metavar = "PATH", help = "Write the JSON here instead of stdout.").file()
  override fun help(context: Context) = "Describe a world's schema and content as JSON."
  override fun run()
}
```

- **Algorithmic Steps & Invariants:**
  1. `val dir = WorldDir((world ?: File(".")).toPath())`.
  2. `withPlugins(dir) { host -> describeWorld(dir, host, host.describe(), problems) }`.
  3. If the result is null, `echo("error: $it", err = true)` for each problem and `throw ProgramResult(1)`.
  4. If `out` is null, write the JSON to stdout with no extra trailing newline (`echo(json, trailingNewline = false)`).
     Otherwise create parent directories and write the file as UTF-8. An `out` that is an existing directory is a
     `UsageError`.
  5. Plugin warnings already go to stderr through `withPlugins`; stdout must hold only the JSON.

### Test & Documentation Requirements

- `cli/src/test/kotlin/gups/cli/DescribeWorldCommandTest.kt`:
  - `template world matches golden` and `robot world matches golden`: run the describer on each world with its plugins,
    replace the `engine_version` value with `""`, and compare with the golden file. With `-Dgups.updateGolden=true`
    rewrite the file instead, as the `api.golden.json` test does.
  - `stdout holds only json`: run the command through Clikt (`Gups().test(...)` or the pattern `CommandsTest` uses) on a
    temp world and parse stdout as JSON.
  - `--out writes the file and creates directories`.
  - `bad module exits 1`: an unsupported `api_version` prints `error: ...` to stderr and exits 1.
  - `broken zone still exits 0`.
- After generating the goldens, read them and check by hand against `template-world/zones/tutorial/rooms.star` that the
  hall's exits and the armory door appear as declared.
- Run `./gradlew markdownFormat` after editing `SPEC.md`.

### Exit Conditions

- [] `./gradlew :cli:test --tests "gups.cli.DescribeWorldCommandTest" --tests "gups.cli.WorldDescriberTest"` passes.
- [] `./gradlew :cli:installDist && cli/build/install/gups/bin/gups describe-world --world template-world | head` prints
  JSON starting with `"format": 1`.
- [] `./gradlew markdownFormat` run; `./gradlew check` passes with zero warnings.
- [] No new comment mentions a spec section or a `D-` id.

### Finalization

- There is no `DESIGN_NEEDED` entry to mark completed for this milestone.
- Run the full suite (`./gradlew check`) and confirm §4.13 describes exactly what the command does.
