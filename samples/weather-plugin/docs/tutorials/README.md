# Plugin Developer's Guide — Weather Plugin Walkthrough Series

Welcome to the GUPS Plugin Developer's Guide! This series of step-by-step tutorials teaches you how to build, test,
package, and install a custom Kotlin `.jar` plugin for the GUPS engine.

The canonical reference implementation for these guides is the heavily commented sample located in [
`samples/weather-plugin/src/main/kotlin/gups/sample/weather/WeatherPlugin.kt`](../../src/main/kotlin/gups/sample/weather/WeatherPlugin.kt).
While that file demonstrates a compact, production-ready plugin utilizing every extension point, this walkthrough series
serves as its step-by-step companion: explaining the underlying architecture, execution lifecycle, type-marshaling
system, security model, and testing patterns.

---

## What is a GUPS Plugin?

In GUPS, course content (rooms, mobs, items, quests) is authored in **Starlark** within course worlds. However, when
instructors or engine developers need to introduce **new host capabilities**—such as procedural weather, advanced combat
mechanics, custom commands, or out-of-band client telemetry—they write a **plugin**.

A plugin is a compiled JVM `.jar` archive that:

* Compiles strictly against `:plugin-api` (never internal engine modules).
* Registers host capabilities atomically through a clean, safe facade (`PluginWorld`).
* Executes safely on the world thread without needing multi-threaded synchronization.
* Integrates directly into Starlark scripts as a frozen namespace (e.g., `weather.now(room)`).
* Is cryptographically pinned in the course world's `MODULE.gups` file.

---

## Tutorial Roadmap

1. **[01. Project Setup & Architecture](01_project_setup_and_architecture.md)**
    * Module dependencies and the `:plugin-api` compile boundary
    * Gradle jar task configuration and SPI isolation
    * Declaring the plugin class: `id`, `pluginApiVersion`, and `requiredFeatures`
    * Java `ServiceLoader` configuration

2. **[02. The Plugin World Facade & Entity Views](02_the_plugin_facade.md)**
    * Why plugins never touch engine internals (`World`, `Actor`, `Room`)
    * Entity views: `PluginRoom`, `PluginActor`, and `PluginItem`
    * The World Thread execution model and cooperative non-blocking rules
    * Verb operations and schema-validated mutators (`damage`, `heal`, `applyAffect`)
    * Attribute namespaces and write isolation

3. **[03. Exposing Starlark Builtins](03_starlark_builtins.md)**
    * Exposing functions to scripts with `r.addBuiltin()`
    * Defining parameters with `PluginParam`
    * Automatic argument marshaling and return value conversion
    * Pure deterministic calculations using `world.seed` and `world.pulse`
    * Handling errors and execution scope rules

4. **[04. Adding Custom Commands](04_custom_commands.md)**
    * Registering game commands with `r.addCommand()`
    * Choosing execution lanes: `PluginLane.FAST` vs `PluginLane.TICK`
    * Role permissions: `PLAYER`, `BUILDER`, `INSTRUCTOR`, `ADMIN`
    * Command collision rules and engine command shadowing
    * Messaging actors and reading spatial context

5. **[05. Effect Primitives & Spells](05_effect_primitives.md)**
    * Extending the combat and spell system with `r.addEffectPrimitive()`
    * Prototype immutability and why arguments must be plain values
    * Applying custom affects and stat modifiers via `world.applyAffect()`
    * Integrating plugin effects into Starlark `skill()` declarations

6. **[06. GMCP Client Telemetry & Lifecycle](06_gmcp_and_lifecycle.md)**
    * Real-time client messaging via GMCP (`r.addGmcp()`)
    * Package naming conventions and subscription prefix rules
    * Phase 7 evaluation and automatic payload diffing
    * The `onWorldStarted` lifecycle callback for post-load discovery
    * Logging and diagnostics (`world.log()` and `world.diagnose()`)

7. **[07. Testing and Verifying Your Plugin](07_testing_your_plugin.md)**
    * Fast in-memory unit testing using fake facades (`FakeRegistry`, `FakeWorld`)
    * Verifying deterministic outputs across pulse windows
    * End-to-end integration testing with `TestRunner` and temporary Starlark worlds
    * Authoring `*_test.star` files to verify script integration

8. **[08. Packaging, Pinning, and Deployment](08_packaging_and_installation.md)**
    * Assembling the plugin jar with Gradle
    * Generating the SHA-256 checksum with `shasum`
    * Pinning the plugin in `MODULE.gups`
    * The trust and security model: JVM permissions and instructor review gates

---

## Essential Reference Pointers

As you work through this series, consult these authoritative references:

* **The Engine Specification:** [`docs/SPEC.md`](../../../docs/SPEC.md) §4.15 (*Plugins*) and §3.10 (*Feature Modules*).
* **Engine Developer Guide:** [`docs/PLUGINS.md`](../../../docs/PLUGINS.md) provides the reference summary of interfaces
  and the instructor security checklist.
* **The Reference Plugin Implementation:** [
  `samples/weather-plugin/src/main/kotlin/gups/sample/weather/WeatherPlugin.kt`](../../src/main/kotlin/gups/sample/weather/WeatherPlugin.kt).
* **The Plugin Test Suite:** [
  `samples/weather-plugin/src/test/kotlin/gups/sample/weather/WeatherPluginTest.kt`](../../src/test/kotlin/gups/sample/weather/WeatherPluginTest.kt).
