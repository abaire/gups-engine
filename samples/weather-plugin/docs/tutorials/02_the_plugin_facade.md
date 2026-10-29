# 02. The Plugin World Facade & Entity Views

In GUPS, plugins never touch the engine's internal data structures. Instead, the engine provides a strictly defined,
read-safe, and mutation-guarded facade centered on `PluginWorld` and entity view interfaces.

This tutorial explains the threading model, how to read entities safely, how to mutate world state via verbs, and how
attribute namespaces guarantee isolation.

---

## 1. The Facade Boundary

To preserve game invariants and ensure plugins cannot corrupt internal state, the engine enforces strict encapsulation:

| Internal Engine Concept | Facade Exposure to Plugins |                     Description                     |
|-------------------------|----------------------------|-----------------------------------------------------|
| `World`                 | `PluginWorld`              | Engine facade for verbs, time, RNG, and diagnostics |
| `Room`                  | `PluginRoom`               | Read-only view of a room and its contents           |
| `Actor` (Player or Mob) | `PluginActor`              | Read-only view of a character, stats, and vitals    |
| `Item`                  | `PluginItem`               | Read-only view of an item                           |
| Any Entity              | `PluginEntity`             | Common base interface (`id`, `label`, `name`)       |

> **Why the Facade?** Plugins are loaded in separate classloaders and compile only against `:plugin-api`. By mediating
> all interactions through interfaces, the engine can evolve internal representations without breaking binary contracts,
> while preventing memory leaks or unsynchronized entity mutations.

---

## 2. The Single World Thread Execution Model

All plugin callbacks—builtins, commands, effect primitives, GMCP producers, and lifecycle hooks—run **synchronously on
the engine's single world thread**.

### Practical Implications for Developers:

* **No Synchronization Required:** You never need locks, mutexes, or atomic variables when interacting with
  `PluginWorld` or entities. State reads are always consistent.
* **Cooperative Multitasking:** Because you share the world thread with the entire game simulation (combat pulses,
  network I/O, script ticks), **you must never perform blocking operations**.
  * Do not call `Thread.sleep()`.
  * Do not perform synchronous disk reads or file writes.
  * Do not execute blocking HTTP or network requests.
  * Keep algorithms fast and deterministic.

---

## 3. Entity Views (`PluginRoom`, `PluginActor`, `PluginItem`)

Views are lightweight wrappers around live entities. Every property access queries the live world state in real time:

### `PluginRoom`

```kotlin
val room: PluginRoom = call.actor.room

val regions: List<String> = room.regions   // Outdoor region tags (e.g. ["valley", "meadow"])
val exits: Map<String, String> = room.exits // Direction -> Room Label (e.g. "north" -> ":hall")
val actors: List<PluginActor> = room.actors // Characters currently in this room
val items: List<PluginItem> = room.items   // Ground items in this room
val flags: Set<String> = room.flags        // Room flags (e.g. "dead_end_ok")
val size: String = room.size               // Intra-room track size: "cramped", "normal", "large"
```

### `PluginActor`

```kotlin
val actor: PluginActor = call.actor

val isPlayer: Boolean = actor.isPlayer
val name: String = actor.name
val label: String? = actor.label           // Null for players; prototype label for mobs

// Reading schema stats and vitals:
val currentHp: Int = actor.vital("hp")
val maxHp: Int = actor.maxVital("hp")
val effectiveDex: Int = actor.stat("dex")
val baseDex: Int = actor.baseStat("dex")
val fireResist: Int = actor.resist("fire")

// Inspecting status affects:
val affects: List<String> = actor.affects
val isSoaked: Boolean = actor.hasAffect("soaked")
```

### Departed Entity Safety

If a plugin method holds an actor or item reference that subsequently leaves the world (such as a mob dying, a player
disconnecting, or an item being destroyed), attempting to invoke a verb on that entity throws a clean
`IllegalArgumentException`:

```text
damage(target): Goblin is no longer in the world
```

Rooms never leave the world and are always valid.

---

## 4. Verbs: Safe State Mutations

Plugins mutate world state by invoking **verbs** on `PluginWorld`. These verbs correspond directly to the verbs
available in Starlark scripts via `ctx`:

```kotlin
// Sending messages:
world.send(actor, "The storm winds howl around you.")
world.echo(room, "Lightning flashes across the sky!", exclude = listOf(actor))

// Applying combat damage and healing:
world.damage(target = actor, amount = 10, type = "lightning", vital = "hp", source = null)
world.heal(target = actor, amount = 15, vital = "hp")

// Status affects:
world.applyAffect(
    target = actor,
    name = "soaked",
    duration = 4,                       // In pulses (1 pulse = 250ms)
    statDeltas = mapOf("dex" to -1),     // Schema stat modifications
    flags = emptySet()
)
world.removeAffect(target = actor, name = "soaked")

// Teleportation:
world.teleport(actor = actor, room = destinationRoom)
```

### World Schema Validation

When specifying stat names, vital names, or damage types in verbs or affect maps, the engine verifies them against the
active world's schema defined in `MODULE.gups` (SPEC §3.9). If you pass an undeclared name, the engine throws an
`IllegalArgumentException` with an intelligent "did you mean" suggestion.

---

## 5. Attributes and Namespaces

Entities in GUPS support custom dynamic attributes. Plugins can attach attributes to actors, rooms, or items using
`getAttr`, `setAttr`, and `delAttr`:

```kotlin
// Read an attribute (defaults to null or specified fallback)
val chills = world.getAttr(actor, "chilled_stacks", default = 0) as Int

// Set an attribute
world.setAttr(actor, "chilled_stacks", chills + 1)

// Delete an attribute
world.delAttr(actor, "chilled_stacks")
```

### Namespace Isolation Rules

1. **Automatic Prefixing:** Any key written without an explicit `namespace:` prefix is automatically namespaced under
   the plugin's `id`. For example, in the `weather` plugin, calling `world.setAttr(actor, "rain_soaked", true)` actually
   writes `weather:rain_soaked`.
2. **Write Isolation:** A plugin can only write to its own namespace. Trying to write `quest:stage` from the `weather`
   plugin is forbidden.
3. **Cross-Namespace Reads:** Plugins can read attributes from other namespaces (such as `world.getAttr(actor,
   "quest:completed")`), enabling seamless integration with course world scripts.

---

## Next Steps

Now that you understand the facade and state access, proceed to
**[03. Exposing Starlark Builtins](03_starlark_builtins.md)** to learn how to expose custom functions to world scripts.
