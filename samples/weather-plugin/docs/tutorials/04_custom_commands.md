# 04. Adding Custom Commands

In GUPS, players interact with the game world by typing commands into telnet or the browser web client. Plugins can
register new game commands that behave like native engine commands.

This tutorial covers command registration, choosing execution lanes, role-based access control, collision avoidance, and
crafting player responses.

---

## 1. Registering a Command with `r.addCommand`

In [`WeatherPlugin.kt`](../../src/main/kotlin/gups/sample/weather/WeatherPlugin.kt), the `weather` command is registered
inside `register()`:

```kotlin
r.addCommand(
    name = "weather",
    lane = PluginLane.FAST,
    role = PluginRole.PLAYER,
    doc = "Describes the weather where you stand.",
) { call ->
    val room = call.actor.room
    call.world.send(
        call.actor,
        if (room.regions.isEmpty()) "You are indoors."
        else "The weather here is ${stateFor(call.world, room)}.",
    )
}
```

### Breakdown of Parameters

* **`name`:** The command word typed by the player (e.g. `"weather"`). Must be lowercase, contain no spaces, and cannot
  begin with `@` (prefix `@` is reserved for engine administrative tools).
* **`lane`:** Specifies whether the command runs immediately or queues on the character.
* **`role`:** The minimum security role required to execute the command.
* **`doc`:** Description shown in the in-game `help` index.
* **The Handler Lambda:** Receives a `PluginCommandCall` instance.

---

## 2. Choosing an Execution Lane: `FAST` vs. `TICK`

The GUPS command dispatcher supports two lanes:

|       Lane        |                 Queue Behavior                 |                   Typical Use Cases                   |
|-------------------|------------------------------------------------|-------------------------------------------------------|
| `PluginLane.FAST` | **Executes immediately** on the current pulse. | Read-only queries: `weather`, `score`, `look`, `help` |
| `PluginLane.TICK` | **Queued on the actor's action queue**.        | Tactical actions: spells, attacks, opening containers |

### When to Use `FAST`:

Informational commands that do not alter the balance of combat or require player action lag should use `FAST`. When a
player types `weather` in combat, it executes instantly without delaying their next melee swing or spell cast.

### When to Use `TICK`:

Actions that affect combat or world state should use `TICK`. If an actor is already suffering action lag (e.g.
recovering from a heavy weapon attack), a `TICK` command queues up and executes once their lag expires.

---

## 3. Security and Role Permissions

The `role` argument controls who can discover and invoke the command:

* `PluginRole.PLAYER`: Available to all connected users.
* `PluginRole.BUILDER`: Available to builders, instructors, and admins.
* `PluginRole.INSTRUCTOR`: Available to instructors and admins.
* `PluginRole.ADMIN`: Restricted to server administrators.

### Role Invariants

* **Invisible to Lower Roles:** A command registered with `PluginRole.BUILDER` does not exist for regular players. It
  will not appear in their `help` listing, and attempting to type it produces standard "Unknown command" output.
* **No Manual Privilege Checks:** You do not need to check `call.actor` permissions in your handler; the engine enforces
  the role requirement before the handler is invoked.

---

## 4. Name Resolution and Collision Rules

When registering commands, the engine enforces strict precedence to prevent conflicting interpretations:

1. **Engine Commands and Directions:** Plugin commands cannot shadow built-in engine commands (`look`, `say`, `get`),
   movement directions (`north`, `south`, `up`), or configured aliases (`l`, `inv`).
2. **Module Renaming Support:** If a course world uses `rename` in `MODULE.gups` to rename an engine command away (e.g.
   renaming `cast` to `invoke`), or turns off a feature module (SPEC §3.10), that original name becomes available for
   plugins to claim.
3. **Precedence Over Scripts:** Plugin commands are installed before Starlark package scripts load. If a zone script
   declares `global_command("weather", ...)`, the script command is skipped with a `load` diagnostic because the plugin
   command takes priority.
4. **Collision with Other Plugins:** Two plugins cannot register the same command name. Plugins are registered in
   alphabetical order by plugin ID; if an earlier plugin has already registered `"weather"`, a later plugin's attempt to
   register `"weather"` fails.

---

## 5. Handling the Call: `PluginCommandCall`

The `call` parameter passed to your command lambda provides:

```kotlin
data class PluginCommandCall(
    val world: PluginWorld,     // The world facade
    val actor: PluginActor,     // The character who typed the command
    val args: String            // Unparsed arguments following the command verb
)
```

### Parsing Arguments and Providing Feedback

In the `weather` command, we inspect the actor's current room to format custom feedback:

```kotlin
val room = call.actor.room

if (room.regions.isEmpty()) {
    call.world.send(call.actor, "You are indoors.")
} else {
    val currentWeather = stateFor(call.world, room)
    call.world.send(call.actor, "The weather here is $currentWeather.")
}
```

* `call.world.send(actor, text)` delivers text directly to the player's terminal or webclient.
* If your command accepts arguments (e.g. `forecast tomorrow`), access them via `call.args.trim()`.

---

## Next Steps

Next, learn how to create combat mechanics and status effects with
**[05. Effect Primitives & Spells](05_effect_primitives.md)**.
