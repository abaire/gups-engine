# Sample Plugin: Weather System

This directory contains the canonical sample plugin for the GUPS engine: **a deterministic weather system by region**.

It serves as the reference implementation demonstrating every plugin extension point provided by the engine's plugin
API: * **Host Builtins:** Exposing `weather.now(room)` to Starlark zone scripts. * **Player Commands:** Adding a
fast-lane `weather` command for players to inspect current conditions. * **Effect Primitives:** Providing
`weather.soak(pulses)` for spells and combat skills. * **Client Telemetry (GMCP):** Broadcasting real-time weather
updates to web and MUD clients via `Gups.Weather`. * **Lifecycle Hooks:** Registering startup logic with
`onWorldStarted` to index outdoor regions and log diagnostics. * **Periodic Work:** An `every` handler that slams and
locks keyed outdoor doors when a storm starts, and unlocks them when it passes.

---

## Directory Layout

```text
samples/weather-plugin/
├── build.gradle.kts           # Gradle build configuration compiling against :plugin-api
├── README.md                  # This file
├── docs/
│   └── tutorials/             # Step-by-step plugin development walkthrough series
│       ├── README.md          # Series roadmap and architecture overview
│       ├── 01_project_setup_and_architecture.md
│       ├── 02_the_plugin_facade.md
│       ├── 03_starlark_builtins.md
│       ├── 04_custom_commands.md
│       ├── 05_effect_primitives.md
│       ├── 06_gmcp_and_lifecycle.md
│       ├── 07_testing_your_plugin.md
│       └── 08_packaging_and_installation.md
└── src/
    ├── main/kotlin/gups/sample/weather/
    │   └── WeatherPlugin.kt   # Complete plugin implementation
    ├── plugin/META-INF/services/
    │   └── gups.pluginapi.GupsPlugin  # Java ServiceLoader SPI declaration
    └── test/kotlin/gups/sample/weather/
        └── WeatherPluginTest.kt       # Fast unit tests and end-to-end integration tests
```

---

## Step-by-Step Walkthrough

If you are building a new plugin or extending an existing one, start with the
**[Plugin Developer's Guide](docs/tutorials/README.md)**:

1. **[01. Project Setup & Architecture](docs/tutorials/01_project_setup_and_architecture.md)** — Gradle setup,
   dependencies, SPI declarations, and plugin metadata.
2. **[02. The Plugin World Facade & Entity Views](docs/tutorials/02_the_plugin_facade.md)** — Threading model, safe
   entity views, namespaced attributes, and schema validation.
3. **[03. Exposing Starlark Builtins](docs/tutorials/03_starlark_builtins.md)** — Declaring host functions, parameter
   typing, argument marshaling, and deterministic state.
4. **[04. Adding Custom Commands](docs/tutorials/04_custom_commands.md)** — Registering game commands, execution lanes
   (`FAST` vs `TICK`), roles, and collision rules.
5. **[05. Effect Primitives & Spells](docs/tutorials/05_effect_primitives.md)** — Integrating custom effects into
   skills, prototype safety, and applying status affects.
6. **[06. GMCP Client Telemetry & Lifecycle](docs/tutorials/06_gmcp_and_lifecycle.md)** — Real-time out-of-band JSON
   messaging, change diffing, and world startup hooks.
7. **[07. Testing and Verifying Your Plugin](docs/tutorials/07_testing_your_plugin.md)** — Fast unit testing with mock
   facades and full end-to-end testing with `TestRunner`.
8. **[08. Packaging, Pinning, and Deployment](docs/tutorials/08_packaging_and_installation.md)** — Building the jar,
   calculating SHA-256 hashes, pinning in `MODULE.gups`, and the instructor trust model.

---

## Building and Testing

### Build the Plugin Jar

To compile the plugin and package the `.jar` bundle:

```bash
./gradlew :samples:weather-plugin:jar
```

The output jar is generated at:

```text
samples/weather-plugin/build/libs/weather.jar
```

### Run the Test Suite

The sample includes fast unit tests verifying deterministic calculations and isolated components, as well as an
end-to-end integration test that boots a test world and evaluates Starlark scripts:

```bash
./gradlew :samples:weather-plugin:test
```

---

## Essential Reference Pointers

* **Engine Architecture Specification:** See [`docs/SPEC.md`](../../docs/SPEC.md) §4.15 (*Plugins*) and §3.10 (*Feature
  Modules*).
* **Plugin Developer Reference:** See [`docs/PLUGINS.md`](../../docs/PLUGINS.md) for the API facade specification and
  instructor review checklist.
* **World Building Guide:** See
  [`template-world/docs/tutorials/README.md`](../../template-world/docs/tutorials/README.md) for information on writing
  Starlark zones, rooms, and quests.
