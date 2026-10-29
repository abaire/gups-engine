# Sample Plugin: Heritage System

This directory contains the reference character creation and heritage plugin for the GUPS engine: **a fantasy race and class system**.

While [`weather-plugin`](../weather-plugin) demonstrates environmental hooks and procedural Starlark builtins, `heritage-plugin` demonstrates how a plugin can hook into **character creation**, define permanent **traits**, and enforce operational **restrictions**:

* **Traits:** Defining permanent trait bundles (`PluginTrait`) with stat deltas, maximum vital deltas, elemental resistance deltas, flags, and modifier chain functions (`PluginModifierPoint.REGEN`).
* **Character Creation Provider:** Declaring a multi-step login creation sequence (`PluginCreation`) with `race` and `class` choices, progressive dynamic availability (e.g. forbidding dwarf mages), whole-set validation, and post-creation skill assignment (`created`).
* **Operational Restrictions:** Enforcing game rules across four core lifecycle points (`WEAR`, `CAST`, `ENTER`, `LEARN`) using character traits and content `tags` on items, rooms, and skills.
* **In-Game Re-spec & Verbs:** Allowing runtime choice modifications via `world.setChoices()`, Starlark script calls via `heritage.choose()`, and inspecting player state via the `heritage` command.

---

## Directory Layout

```text
samples/heritage-plugin/
├── build.gradle.kts           # Gradle build configuration compiling against :plugin-api
├── README.md                  # This file
├── docs/
│   └── tutorials/             # Step-by-step walkthrough series
│       ├── README.md          # Series roadmap and architecture overview
│       ├── 01_traits_and_effective_attributes.md
│       ├── 02_character_creation_provider.md
│       ├── 03_operational_restrictions_and_tags.md
│       ├── 04_runtime_choice_changes_and_builtins.md
│       └── 05_testing_creation_and_traits.md
└── src/
    ├── main/kotlin/gups/sample/heritage/
    │   └── HeritagePlugin.kt   # Complete plugin implementation
    ├── plugin/META-INF/services/
    │   └── gups.pluginapi.GupsPlugin  # Java ServiceLoader SPI declaration
    └── test/kotlin/gups/sample/heritage/
        ├── HeritagePluginTest.kt      # Fast unit tests with mock facades
        └── HeritageWorldTest.kt       # End-to-end integration and telnet tests
```

---

## Step-by-Step Walkthrough

If you are building a character creation plugin, defining custom species/classes, or implementing equipment and movement restrictions, start with the **[Heritage Plugin Developer's Guide](docs/tutorials/README.md)**:

1. **[01. Traits & Effective Attributes](docs/tutorials/01_traits_and_effective_attributes.md)** — Registering `PluginTrait`, base vs. effective stats and vitals, elemental resistances, flags, modifier points, and mob prototype traits.
2. **[02. Character Creation Lifecycle & Providers](docs/tutorials/02_character_creation_provider.md)** — Implementing `PluginCreation`, defining choices and options, dynamic availability filtering, validation, starting rooms, and the `created()` callback.
3. **[03. Operational Restrictions & Content Tags](docs/tutorials/03_operational_restrictions_and_tags.md)** — Hooking into `WEAR`, `CAST`, `ENTER`, and `LEARN`, reading Starlark `tags` on items/rooms/skills, staff role overrides, and automatic de-equipping.
4. **[04. Runtime Choice Changes, Builtins & Commands](docs/tutorials/04_runtime_choice_changes_and_builtins.md)** — Respec mechanics with `world.setChoices()`, exposing `heritage.choose` to zone scripts, and registering the fast-lane `heritage` command.
5. **[05. Testing Character Creation & Traits](docs/tutorials/05_testing_creation_and_traits.md)** — Fast unit testing with fake registries and entities, authoring `*_test.star` harness scripts, and testing live telnet creation flows.

---

## Building and Testing

### Build the Plugin Jar

To compile the plugin and package the `.jar` bundle:

```bash
./gradlew :samples:heritage-plugin:jar
```

The output jar is generated at:
```text
samples/heritage-plugin/build/libs/heritage.jar
```

### Run the Test Suite

The sample includes fast unit tests verifying trait calculations and mock creation sessions, as well as an end-to-end integration test that boots a test world with the pinned jar:

```bash
./gradlew :samples:heritage-plugin:test
```

---

## Essential Reference Pointers

* **Engine Architecture Specification:** See [`docs/SPEC.md`](../../docs/SPEC.md) §4.19 (*Character Creation, Traits and Restrictions*) and §4.15 (*Plugins*).
* **Plugin Developer Reference:** See [`docs/PLUGINS.md`](../../docs/PLUGINS.md) for plugin interfaces and the instructor security checklist.
* **Weather Plugin Sample:** See [`samples/weather-plugin/README.md`](../weather-plugin/README.md) for environmental builtins, effect primitives, and GMCP telemetry.
