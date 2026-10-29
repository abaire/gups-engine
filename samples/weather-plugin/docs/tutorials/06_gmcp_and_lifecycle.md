# 06. GMCP Client Telemetry & Lifecycle Hooks

GUPS is designed for both traditional text telnet clients and modern browser-based web clients. To drive rich UI
components (such as weather widgets, compasses, or mini-maps), plugins can stream real-time JSON payloads using **GMCP**
(Generic MUD Communication Protocol).

In addition, plugins can hook into the server's startup sequence using **world lifecycle callbacks**. This tutorial
covers GMCP telemetry, automatic payload diffing, and world initialization.

---

## 1. What is GMCP?

GMCP is an out-of-band messaging protocol that runs alongside the normal stream of game text. While text messages are
printed to the player's screen, GMCP messages deliver structured JSON payloads directly to client-side UI handlers:

```json
// Example GMCP message sent to web client:
Gups.Weather {
  "state": "storm",
  "region": "meadow"
}
```

The web client or desktop MUD client can use this payload to update a weather icon, change ambient background sounds, or
display atmospheric effects without cluttering the player's text feed.

---

## 2. Registering a GMCP Producer with `r.addGmcp`

In [`WeatherPlugin.kt`](../../src/main/kotlin/gups/sample/weather/WeatherPlugin.kt), the `Gups.Weather` GMCP package is
registered inside `register()`:

```kotlin
r.addGmcp("Gups.Weather", "The weather where the player stands.") { world, actor ->
    mapOf(
        "state" to stateFor(world, actor.room),
        "region" to actor.room.regions.minOrNull()
    )
}
```

### Registration Breakdown

* **Package Name (`"Gups.Weather"`):** The dotted identifier that clients subscribe to.
* **`doc`:** Documentation string for the protocol specification.
* **The Producer Lambda:** Receives `(world: PluginWorld, actor: PluginActor)` and returns a serializable data structure
  (`Map`, `List`, `String`, `Number`, `Boolean`, or `null`).

---

## 3. Package Naming Conventions

The engine enforces strict naming rules for GMCP packages to avoid clashing with core engine streams:

* **Format:** Must match `Name(.Name)+` (at least two dot-separated parts, PascalCase recommended).
* **Reserved Engine Namespaces:** Your package name must not equal, contain, or be contained in any engine GMCP package:
  * `Char.*` (character vitals, status, inventory)
  * `Room.*` (room info, exits, map updates)
  * `Comm.*` (chat channels)
  * `Gups.Trace` (debugging tools)
* **Other Plugins:** Packages must not conflict with earlier loaded plugins.

---

## 4. The GMCP Execution Lifecycle and Automatic Diffing

The engine handles GMCP distribution automatically during **phase 7** of the world pulse:

1. **Client Negotiation:** The engine queries client capabilities during the Telnet/WebSocket handshake. If a client
   does not support GMCP, the producer is never called.
2. **Per-Actor Evaluation:** On each pulse, your producer is invoked for each connected player session.
3. **Automatic Payload Diffing:** The engine compares the newly generated payload against the previously sent payload
   for that session. **If the payload has not changed, nothing is transmitted.** This keeps network bandwidth minimal
   without requiring manual caching in your plugin.
4. **Resilience:** If your producer throws an exception, the failure is recorded as a `plugin` diagnostic and skipped
   for that pulse; the rest of the world loop continues unaffected.

---

## 5. World Startup Hook: `r.onWorldStarted`

Often, a plugin needs to inspect world state or initialize caches after all world packages have finished loading. You
can register a startup hook with `r.onWorldStarted`:

```kotlin
r.onWorldStarted { world ->
    val regions = world.regions()
    world.log("weather: tracking ${regions.size} region(s): ${regions.joinToString(", ")}")
}
```

### When Does It Run?

* Runs **once at startup** after all `.star` packages have been evaluated, rooms and mobs have been instantiated, and
  the initial world state is ready.
* Callbacks run in **alphabetical order of plugin IDs**.
* This is the ideal place to validate that required zone labels exist, scan room tags, or build spatial lookup indexes.

---

## 6. Server Logging and Diagnostics

Plugins have access to structured logging through `PluginWorld`:

```kotlin
// Writes to the server log:
world.log("weather: initialized region table successfully")

// Emits a structured diagnostic problem (visible in gups lint / admin reports):
world.diagnose("Room '//zones/forest:clearing' has unknown weather region 'tundra'")
```

* **Automatic Prefixing:** Calls to `world.log(...)` automatically prepend `plugin '<id>': ` in the server log.
* **Audit Trail:** Log messages are included in server startup reports and captured during automated test runs.

---

## Next Steps

Now that you have implemented the complete plugin feature set, proceed to
**[07. Testing and Verifying Your Plugin](07_testing_your_plugin.md)** to learn how to write unit and integration tests.
