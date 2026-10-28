# 05. Effect Primitives & Spells

In GUPS, skills and spells execute structured effects when resolved. While the engine includes built-in primitives like
`damage`, `heal`, and `apply_affect`, plugins can extend the spell system by registering custom **effect primitives**.

This tutorial explains how to implement custom effect primitives, the strict prototype immutability rules you must
follow, and how world builders consume your effects in Starlark.

---

## 1. What is an Effect Primitive?

World builders configure combat abilities, spells, and items using the `skill()` declaration in Starlark:

```python
# zones/valley/rooms.star
skill(
    "rain_call",
    kind="spell",
    target="self",
    effect=weather.soak(pulses=3),  # Custom effect primitive from the weather plugin!
)
```

When a character casts `"rain_call"`, the engine's combat resolution system invokes your plugin's effect handler.

Plugin effects work interchangeably with engine effects: they can be composed into sequences
(`sequence(damage("fire", 10), weather.soak(pulses = 4))`), scheduled over time, or triggered by magic items.

---

## 2. Registering an Effect Primitive with `r.addEffectPrimitive`

In [`WeatherPlugin.kt`](../../src/main/kotlin/gups/sample/weather/WeatherPlugin.kt), the `soak` effect primitive is
registered inside `register()`:

```kotlin
r.addEffectPrimitive(
    name = "soak",
    doc = "Soaks the target: dex -1 for the given number of pulses.",
    params =
        listOf(
            PluginParam(
                "pulses",
                "int",
                "How long the target stays soaked.",
                required = false,
                defaultValue = 2L,
            )
        ),
) { call ->
    val target = call.target ?: return@addEffectPrimitive
    val pulses = (call.args["pulses"] as Number).toInt()
    call.world.applyAffect(
        target,
        name = "soaked",
        duration = pulses,
        statDeltas = mapOf("dex" to -1)
    )
}
```

### Registration Breakdown

* **`name`:** The identifier exposed on your plugin's Starlark namespace (`"soak"` -> `weather.soak(...)`).
* **`doc`:** Documentation string visible in reference manuals (`gups docs`).
* **`params`:** List of `PluginParam` definitions specifying arguments the effect accepts in Starlark.
* **The `apply` Handler:** A lambda receiving a `PluginEffectCall` when the skill resolves.

---

## 3. Prototype Safety: The Plain-Value Rule

There is a critical architectural requirement for all effect primitives:

> **Rule:** Arguments passed to effect primitives must be **plain values** (integers, strings, booleans, lists, maps).
> Passing an entity reference (`room`, `actor`, `item`) as an argument to an effect primitive is strictly forbidden and
> rejected at load time.

### Why Are Entities Forbidden?

In GUPS, `skill()` definitions are evaluated at **top-level world load time** before any live players or simulation
entities exist. The resulting skill prototypes are completely frozen in memory.

If an effect primitive captured a live room or mob reference during declaration, that reference would become stale
across package reloads, world resets, or database restores. Plain arguments guarantee that skill prototypes remain safe
and immutable.

---

## 4. The Execution Callback: `PluginEffectCall`

When a player or mob triggers the skill, the engine evaluates the effect and calls your lambda with:

```kotlin
data class PluginEffectCall(
    val world: PluginWorld,         // The world facade
    val caster: PluginActor,         // The character casting or using the skill
    val target: PluginActor?,        // The target (null if target = "none")
    val args: Map<String, Any?>      // Parameter values bound from Starlark
)
```

### Handling Missing Targets

Always check whether `target` is null:

```kotlin
val target = call.target ?: return@addEffectPrimitive
```

Skills configured with `target = "none"` (such as area-of-effect spells or self-buffs without explicit targeting) pass
`null` for `target`. If your effect requires a target, simply return early or operate on `call.caster`.

---

## 5. Applying Status Affects: `world.applyAffect`

In our `soak` effect, we apply a temporary status condition:

```kotlin
val pulses = (call.args["pulses"] as Number).toInt()

call.world.applyAffect(
    target = target,
    name = "soaked",
    duration = pulses,                     // Duration in pulses (1 second = 4 pulses)
    statDeltas = mapOf("dex" to -1),        // Lowers dexterity by 1
    flags = emptySet()
)
```

### Understanding Status Affects:

* **Duration:** Measured in world pulses (1 pulse = 250 milliseconds). A duration of `4` lasts exactly 1 second.
* **`statDeltas`:** Temporarily modifies the actor's effective stats. When the affect expires, the modification is
  automatically reversed.
* **Schema Validation:** The stat name `"dex"` must match a stat declared in the world's `MODULE.gups` (SPEC §3.9). If a
  world uses custom stats (e.g. `agility`), the engine validates the key and throws an informative error if misspelled.

---

## 6. Verifying Effect Integration

To see this in action, review the integration test in [
`WeatherPluginTest.kt`](../../src/test/kotlin/gups/sample/weather/WeatherPluginTest.kt):

```python
# zones/valley/valley_test.star
def test_rain_call_soaks_the_caster(t):
    w = t.world()
    p = w.spawn_player("Ann", at=":field", skills={":rain_call": 100})

    # Cast the spell
    p.do("cast rain_call")

    # Fast-forward virtual world time by 2 pulses
    w.run(pulses=2)

    # Verify the "soaked" affect is active on the actor
    t.true("soaked" in [a.name for a in p.affects])
```

---

## Next Steps

Next, learn how to stream real-time JSON updates to modern game clients and react to server startup in
**[06. GMCP Client Telemetry & Lifecycle](06_gmcp_and_lifecycle.md)**.
