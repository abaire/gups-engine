# 01. Project Setup & Architecture

This guide covers how to set up the build environment for a new GUPS plugin, configure dependencies against
`:plugin-api`, declare the plugin entry point, and register the service provider with Java `ServiceLoader`.

---

## 1. What is a GUPS Plugin?

In GUPS, a plugin is a compiled Java Archive (`.jar`) that extends the engine host with new capabilities:

* **Custom Starlark builtins** reachable by zone scripts (e.g. `weather.now(ctx.room)`).
* **New player and builder commands** (e.g. `weather`).
* **Effect primitives** usable in spell and combat declarations (e.g. `weather.soak(pulses = 3)`).
* **GMCP packages** that push structured real-time JSON data to web and MUD clients.
* **World startup hooks** to discover and index world state.

Plugins run directly in the host JVM and execute on the world thread. Because they run within the engine process,
plugins are compiled against an explicit API boundary—`:plugin-api`—and never depend on internal engine modules like
`:core` or `:script`.

---

## 2. Gradle Build Configuration

In the `samples/weather-plugin` project, the build configuration is defined in
[`build.gradle.kts`](../../build.gradle.kts):

```kotlin
plugins { id("gups.kotlin-module") }

dependencies {
    // Plugins compile against the public plugin API ONLY:
    implementation(project(":plugin-api"))

    // Test dependencies may use the harness and engine modules:
    testImplementation(project(":core"))
    testImplementation(project(":script"))
    testImplementation(project(":harness"))
}

tasks.jar {
    archiveFileName = "weather.jar"

    // The service descriptor goes only into the jar, so the test classpath
    // doesn't also offer the plugin through the parent class loader.
    from(layout.projectDirectory.dir("src/plugin"))
}
```

### The Strict Compile Boundary

* **Main Configuration:** Your plugin's main code may depend **only** on `:plugin-api`.
* **Zero Internal Dependencies:** You must never add `implementation(project(":core"))` or
  `implementation(project(":script"))` to your main source set. The engine's module-graph test verifies that no internal
  types leak across this boundary.
* **Starlark Access:** The `:plugin-api` module exposes `gups.pluginapi.*` and the official Starlark library
  (`net.starlark.java.*`).

### The `tasks.jar` Setup and `src/plugin`

Notice that the ServiceLoader provider descriptor is placed in `src/plugin/META-INF/services/` rather than the
traditional `src/main/resources/META-INF/services/`.

> **Key Architectural Insight:** If the provider descriptor were placed in `src/main/resources`, Gradle would place it
> onto the test runtime classpath. When running unit tests, Java's `ServiceLoader` would find the plugin through the
> parent classloader rather than the isolated `URLClassLoader` that the engine uses at runtime. Storing the SPI
> configuration under `src/plugin` and packing it into the jar via `from(layout.projectDirectory.dir("src/plugin"))`
> guarantees that tests test genuine dynamic loading.

---

## 3. Registering the SPI Service Provider

GUPS uses Java's standard `ServiceLoader` architecture to locate plugins inside `.jar` files. Every plugin jar must
contain exactly one service configuration file:

```text
src/plugin/META-INF/services/gups.pluginapi.GupsPlugin
```

This text file must contain **exactly one line** specifying the fully-qualified class name of your plugin class:

```text
gups.sample.weather.WeatherPlugin
```

> **Rule:** A plugin jar must provide **exactly one** `GupsPlugin` implementation. A jar with zero providers or multiple
> providers will be rejected by the engine during world startup.

---

## 4. Implementing the `GupsPlugin` Interface

Your plugin class must implement `gups.pluginapi.GupsPlugin` and provide a public no-argument constructor:

```kotlin
package gups.sample.weather

import gups.pluginapi.GupsPlugin
import gups.pluginapi.PluginApi
import gups.pluginapi.PluginRegistry

class WeatherPlugin : GupsPlugin {
    // Unique identifier matching [a-z][a-z0-9_]*
    override val id = "weather"

    // Strict version check against the engine's plugin API
    override val pluginApiVersion = PluginApi.VERSION

    // Human-readable documentation describing the plugin
    override val doc = "Deterministic weather by region: a sample of every plugin extension point."

    // Optional: required feature modules (e.g. setOf("leveling"))
    override val requiredFeatures: Set<String> = emptySet()

    override fun register(r: PluginRegistry) {
        // Register extension points here...
    }
}
```

### Essential Properties

1. **`id`:**
   * Must match the regex `[a-z][a-z0-9_]*`.
   * Starlark scripts access your plugin's builtins and effects through a global namespace object with this name (e.g.
     `weather.now(room)`).
   * It cannot collide with reserved language keywords or predeclared engine globals (`module`, `server`, `ctx`, `self`,
     `room`, `rules_ctx`, `t`, `w`, `p`, `diagnostic`).
2. **`pluginApiVersion`:**
   * Must equal `PluginApi.VERSION`.
   * **Zero Compatibility Promises:** GUPS enforces strict binary compatibility. Any change to the plugin interface or
     facade bumps `PluginApi.VERSION`. The engine refuses plugins built for another version at startup.
3. **`requiredFeatures`:**
   * Declares optional engine feature modules (such as `"leveling"`, `"combat"`, `"skills"`, or `"track"`) that the
     plugin requires (SPEC §3.10).
   * If a world loads your plugin with one of these features disabled in `MODULE.gups`, the engine cleanly skips the
     plugin with an informative warning: `plugin '<id>': needs the '<feature>' feature`.
4. **`register(r: PluginRegistry)`:**
   * Invoked once at startup to register builtins, commands, effects, GMCP producers, and lifecycle hooks.
   * **Atomic Registration:** If `register()` throws an exception, or attempts to register a conflicting command or
     invalid name, the entire registration is rolled back and the plugin is skipped with a warning.

---

## Next Steps

Now that your project structure and entry point are established, proceed to
**[02. The Plugin World Facade & Entity Views](02_the_plugin_facade.md)** to learn how plugins interact with world state
and the single-threaded execution model.
