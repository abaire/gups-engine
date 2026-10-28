# 03. Exposing Starlark Builtins

One of the most powerful features of the GUPS plugin system is the ability to expose custom JVM functions directly to
Starlark world scripts. These functions appear as methods on a global object named after your plugin's `id` (e.g.
`weather.now(room)`).

This tutorial explains how to register builtins, declare parameters, handle automatic type marshaling, and ensure your
algorithms remain deterministic.

---

## 1. What is a Script Builtin?

When a world builder writes zone content in Starlark, they can query your plugin's functionality inside room hooks, mob
AI, or item interactions:

```python
# zones/valley/rooms.star
def _sky(ctx):
    # Call the plugin builtin 'now' on the 'weather' namespace:
    state = weather.now(ctx.room)
    ctx.send(ctx.actor, "The sky is " + state + ".")


room("field",
     name="Open Field",
     desc="An expansive meadow under the open sky.",
     regions=["meadow"],
     on_enter=_sky,
     )
```

Builtins allow you to offload complex, computationally expensive, or procedural logic to compiled Kotlin while
presenting a clean, idiomatic Starlark interface to world builders.

---

## 2. Registering a Builtin with `r.addBuiltin`

In [`WeatherPlugin.kt`](../../src/main/kotlin/gups/sample/weather/WeatherPlugin.kt), the `now` builtin is registered
inside `register()`:

```kotlin
r.addBuiltin(
    name = "now",
    doc = "The weather in a room: 'clear', 'rain' or 'storm'. Indoor rooms are always 'clear'.",
    params = listOf(PluginParam("room", "room", "The room to look at.")),
    returnType = "str",
) { world, pos, _ ->
    val room =
        pos[0] as? PluginRoom
            ?: throw IllegalArgumentException("weather.now(room): expected a room")
    stateFor(world, room)
}
```

### Registration Breakdown

* **`name`:** The field name on your plugin's global object (`"now"` produces `weather.now(...)`).
* **`doc`:** Documentation string displayed in generated API docs (`gups docs`).
* **`params`:** Ordered list of `PluginParam` definitions.
* **`returnType`:** Documentation type string (e.g. `"str"`, `"int"`, `"dict"`).
* **The Execution Lambda:** Receives `(world: PluginWorld, pos: List<Any?>, named: Map<String, Any?>)`.
    * `pos` contains arguments bound in declared positional order.
    * `named` contains arguments bound by name.

---

## 3. Parameter Specifications: `PluginParam`

The `PluginParam` class defines parameter validation and default values:

```kotlin
PluginParam(
    name = "room",
    type = "room",             // Documentation string
    doc = "The room to check.",
    required = true,           // If true, missing argument raises an error
    defaultValue = null        // Used when required = false and argument is omitted
)
```

The engine automatically validates calls from Starlark:

* Supplying too few arguments or omitting a required parameter raises an argument error.
* Passing an unrecognized keyword argument raises an error.
* If an optional parameter is omitted, `defaultValue` is bound automatically (Kotlin `Int` and `Float` defaults are
  automatically widened to `Long` and `Double`).

---

## 4. Automatic Type Marshaling

The engine handles bidirection type conversions between the Starlark runtime and your Kotlin plugin:

### Starlark to Kotlin (Arguments)

| Starlark Type            | Bound Kotlin Type             |
|--------------------------|-------------------------------|
| Integer (`42`)           | `java.lang.Long`              |
| Float (`3.14`)           | `java.lang.Double`            |
| String (`"rain"`)        | `java.lang.String`            |
| Boolean (`True`)         | `java.lang.Boolean`           |
| `None`                   | `null`                        |
| List or Tuple (`[1, 2]`) | `java.util.List<Any?>`        |
| Dict (`{"a": 1}`)        | `java.util.Map<String, Any?>` |
| Room View (`room`)       | `gups.pluginapi.PluginRoom`   |
| Actor View (`actor`)     | `gups.pluginapi.PluginActor`  |
| Item View (`item`)       | `gups.pluginapi.PluginItem`   |

### Kotlin to Starlark (Return Values)

| Kotlin Return Value   | Resulting Starlark Value |
|-----------------------|--------------------------|
| `null` or `Unit`      | `None`                   |
| `Int` or `Long`       | Starlark Integer         |
| `Float` or `Double`   | Starlark Float           |
| `String` or `Boolean` | Passed through directly  |
| `List<*>`             | Immutable Starlark list  |
| `Map<*, *>`           | Immutable Starlark dict  |
| `StarlarkValue`       | Passed through directly  |

> **Important Rule:** Never return a facade entity view (`PluginRoom`, `PluginActor`, or `PluginItem`) directly to
> Starlark. If your builtin needs to refer to an entity, return its string label (e.g. `room.label`).

---

## 5. Deterministic State Calculation

In GUPS, the game simulation must be **deterministic**: repeating an execution sequence with the same seed must produce
the exact same outcome. This enables reproducible automated tests (`gups test`), headless fuzz testing (`gups fuzz`),
and recorded bug reproduction (`gups repro`).

Notice how `WeatherPlugin` calculates weather without ever touching `System.currentTimeMillis()` or `java.util.Random`:

```kotlin
companion object {
    private val STATES = listOf("clear", "rain", "storm")

    /** A pure function of the seed, the one-minute window and the room's region. */
    fun stateFor(world: PluginWorld, room: PluginRoom): String {
        // Rooms without region tags are indoors and always clear:
        val region = room.regions.minOrNull() ?: return "clear"

        // 1 world pulse = 250ms; 240 pulses = 1 minute window
        val window = world.pulse / 240L

        // Deterministic bit mixing combining seed, pulse window, and region name:
        var z = world.seed xor (window * -0x61c8864680b583ebL) xor region.hashCode().toLong()
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)

        return STATES[((z ushr 1) % STATES.size).toInt()]
    }
}
```

By hashing `world.seed`, `world.pulse`, and `region`, the weather changes every minute, stays synchronized across all
rooms in the same region, and behaves identically in automated test suites.

---

## 6. Execution Scope and Error Handling

### Where Builtins Can Be Called

A builtin receives the `PluginWorld` of the hook or command that invoked it. Consequently:

* **Allowed:** Called from room hooks (`on_enter`), mob hooks (`think`, `on_say`), item callbacks (`on_use`), or custom
  commands.
* **Forbidden:** Called in top-level package code during world loading, or in a test function's own body outside a hook.
  Calling a builtin outside world execution throws an explicit error:
  ```text
  weather.now() can only be called while the world runs, from a hook or command
  ```

### Script Errors

Any unhandled exception thrown inside your builtin is caught by the engine and reported as a script error in the calling
hook:

```text
weather.now() failed: weather.now(room): expected a room
```

This isolates failures to the individual hook without crashing the game server.

---

## Next Steps

Next, learn how to add custom interactive commands for players and builders in
**[04. Adding Custom Commands](04_custom_commands.md)**.
