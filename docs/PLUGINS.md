# Writing Plugins

Plugins are `.jar` files that extend the engine with host builtins, commands, effect primitives,
GMCP packages and a world-start hook (SPEC.md §4.15). This guide uses the sample plugin in
`samples/weather-plugin` as its running example: deterministic weather by region. For a step-by-step
tutorial on building a plugin from scratch, see the
[Weather Plugin Walkthrough](../samples/weather-plugin/docs/tutorials/README.md).

There are **no compatibility promises**. After the engine's first release, `PluginApi.VERSION`
changes whenever the plugin interface or the facade changes. Until then, the interface is still
being designed and changes stay at version 1 (as unreleased `api_version` 1 does for scripts);
plugins are rebuilt against each engine build anyway. Plugins built for another version are
refused at load, and must be rebuilt.

## Building a plugin

A plugin compiles against `plugin-api` only. It sees `gups.pluginapi.*` and the Starlark library
(`net.starlark.java.*`), never the engine's own modules. The sample's Gradle setup:

```kotlin
dependencies {
    implementation(project(":plugin-api"))
}

tasks.jar {
    archiveFileName = "weather.jar"
    from(layout.projectDirectory.dir("src/plugin"))
}
```

A plugin is one class implementing `GupsPlugin`, with a no-argument constructor:

```kotlin
class WeatherPlugin : GupsPlugin {
    override val id = "weather"
    override val pluginApiVersion = PluginApi.VERSION
    override val doc = "Deterministic weather by region: a sample of every plugin extension point."
```

- `id` matches `[a-z][a-z0-9_]*`. Scripts reach the plugin's builtins and effects as fields of a
  global with that name (`weather.now(room)`), so it can't be a predeclared name such as `room`.
- `pluginApiVersion` must equal `PluginApi.VERSION`.
- `requiredFeatures` (default none) lists the engine features the plugin needs, such as
  `setOf("leveling")`. A world that has one of them off, or that names an unknown one, skips the
  plugin with `plugin '<id>': needs the '<feature>' feature`. `PluginWorld.features` lists the
  features the world has on, for a plugin that only uses some of them optionally; `PluginActor.level`
  throws an `IllegalStateException` naming `leveling`, and `PluginRoom.size` one naming `track`,
  when the feature is off.
- Command names are checked against the world's engine commands after `rename`: a plugin command
  may take a name the world renamed away (or whose feature is off), and may not take the new name.
- `register(r)` adds everything through the `PluginRegistry`. It is atomic: if it throws, or
  registers an invalid or clashing name, the plugin adds nothing and is skipped with a warning.

The jar names the class in `META-INF/services/gups.pluginapi.GupsPlugin`, one line:

```text
gups.sample.weather.WeatherPlugin
```

The engine finds it with `ServiceLoader`. The jar must have exactly one provider.

## What plugins can touch

Plugins never see the engine's `World` or entities. They get a `PluginWorld` facade:

- **verbs:** `send`, `echo`, `damage`, `heal`, `applyAffect`, `removeAffect`, `teleport`,
  `getAttr`, `setAttr` and `delAttr`. Each does what the `ctx` verb of the same name does, so the
  engine's invariants hold. Attribute keys without `ns:` are in the plugin's own namespace (its
  id), and a plugin writes only its own namespace. `damage(target, amount, type, vital)` and
  `heal(target, amount, vital)` name the vital they change, as the `ctx` verbs do.
- **schema names:** a vital, stat or damage type the world's `MODULE.gups` doesn't declare, in a
  verb or in a `statDeltas` key, throws `IllegalArgumentException` with a "did you mean"
  suggestion.
- **reads:** `schema` (a `PluginSchema` with `stats` and `vitals`, each a `PluginNamed(name,
  label)`, and `damageTypes`, in declaration order), `pulse`, `seed`, `rng` (seeded from the
  world), `rooms()`, `room(label)` and `regions()`.
- **diagnostics:** `diagnose(message)` records a `"plugin"` diagnostic; `log(message)` writes to
  the server log.
- **views:** `PluginRoom`, `PluginActor` and `PluginItem` read current state on every access. A
  `PluginActor` has `stat(name)` (effective), `baseStat(name)`, `vital(name)`, `maxVital(name)`
  and `resist(type)` by schema name, plus `level`, `affects` and `hasAffect(name)`.
- **`PluginViews`** converts raw Starlark values for builtins installed with
  `addBuiltin(name, value, doc)`: `room(value)`, `actor(value)`, `item(value)`, and
  `world(thread)` for a method declared with `useStarlarkThread = true`.

Every call runs on the world thread.

## Extension points

### Builtins

`addBuiltin` binds the Starlark arguments to the declared parameters (missing required → error,
unknown keyword → error, missing optional → its `defaultValue`) and converts them: ints to
`Long`, floats to `Double`, `None` to `null`, lists to `List`, dicts to `Map`, and room, actor and
item views to `PluginRoom`, `PluginActor` and `PluginItem`. The return value converts back.
A builtin runs only while the world does, from a hook or command; anywhere else (top-level
package code, a test function's own body) it fails.

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

The weather is a pure function of the seed, the one-minute window and the room's region, so
tests are deterministic:

```kotlin
fun stateFor(world: PluginWorld, room: PluginRoom): String {
    val region = room.regions.minOrNull() ?: return "clear"
    var z =
        world.seed xor (world.pulse / 240L) * -0x61c8864680b583ebL xor region.hashCode().toLong()
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    z = z xor (z ushr 31)
    return STATES[((z ushr 1) % STATES.size).toInt()]
}
```

### Commands

Commands take a lane (`FAST` runs at once, `TICK` queues on the actor) and a minimum role. They
may not shadow engine commands, directions or another plugin's commands, and a script
`global_command` with the same name is skipped.

```kotlin
r.addCommand(
    "weather",
    PluginLane.FAST,
    PluginRole.PLAYER,
    "Describes the weather where you stand.",
) { call ->
    val room = call.actor.room
    call.world.send(
        call.actor,
        if (room.regions.isEmpty()) "You are indoors."
        else "The weather here is ${stateFor(call.world, room)}.",
    )
}
```

### Effect primitives

An effect primitive is called in package code like an engine one, and its value works wherever
theirs does: `skill(effect = weather.soak(pulses = 3))`, or inside `sequence(...)`. Its arguments
must be plain values (no entities), because the effect is part of a frozen prototype. The engine
calls `apply` when the skill resolves; `target` is null for `target = "none"` skills.

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
    call.world.applyAffect(target, "soaked", duration = pulses, statDeltas = mapOf("dex" to -1))
}
```

### GMCP packages

A producer runs in phase 7 for each player whose client supports the package, and only changed
payloads are sent. Return plain values. The name has at least two dot-separated parts and must
not overlap an engine package (`Char.*`, `Room.*`, `Comm.Channel.Text`, `Gups.Trace`).

```kotlin
r.addGmcp("Gups.Weather", "The weather where the player stands.") { world, actor ->
    mapOf("state" to stateFor(world, actor.room), "region" to actor.room.regions.minOrNull())
}
```

### Character creation, traits and restrictions

Plugins can define traits, character creation choices and operational restrictions (`samples/heritage-plugin`):

#### Traits

Traits carry stat, max-vital and resist deltas, flags, and modifiers. They are registered on the plugin registry:

```kotlin
r.addTrait(
    PluginTrait(
        name = "elf",
        label = "Elf",
        doc = "Nimble and magical, but fragile.",
        statDeltas = mapOf("dex" to 2, "con" to -1),
        maxVitalDeltas = mapOf("mana" to 10),
    )
)
```

#### Creation provider

A plugin registers at most one creation provider that presents choices (such as race and class) at login:

```kotlin
r.setCreation(
    object : PluginCreation {
        override val choices =
            listOf(
                PluginChoice("race", "Race", RACE_OPTIONS),
                PluginChoice("class", "Class", CLASS_OPTIONS),
            )

        override fun available(
            world: PluginWorld,
            choice: String,
            answers: Map<String, String>,
        ): List<String> =
            when (choice) {
                "race" -> listOf("human", "elf", "dwarf")
                "class" ->
                    if (answers["race"] == "dwarf") listOf("warrior", "cleric")
                    else listOf("warrior", "mage", "cleric")
                else -> emptyList()
            }

        override fun validate(world: PluginWorld, answers: Map<String, String>): String? =
            if (answers["race"] == "dwarf" && answers["class"] == "mage") {
                "Dwarves cannot be mages."
            } else null

        override fun startRoom(world: PluginWorld, answers: Map<String, String>): String? = null

        override fun created(world: PluginWorld, actor: PluginActor, newCharacter: Boolean) {
            if (newCharacter) {
                val skill =
                    when (actor.choices["class"]) {
                        "mage" -> "//skills:magic_missile"
                        "cleric" -> "//skills:minor_heal"
                        else -> null
                    }
                if (skill != null) {
                    try {
                        world.setSkill(actor, skill, 25)
                    } catch (_: IllegalArgumentException) {
                        // Skill not defined in world
                    }
                }
            }
        }
    }
)
```

#### Restrictions

Restrictions intercept operations (`WEAR`, `ENTER`, `CAST`, `LEARN`) and can refuse them:

```kotlin
r.addRestriction(PluginRestrictionPoint.WEAR, "Mages cannot wear heavy armor.") { call ->
    val actor = call.actor
    val item = call.item
    if (actor.hasTrait("mage") && item != null && "heavy_armor" in item.tags) {
        "Your spellcraft can't work through that much metal."
    } else null
}
```

#### Modifying choices in play

A plugin can let players change choices through builtins or commands using `PluginWorld.setChoices`:

```kotlin
r.addBuiltin(
    name = "choose",
    doc = "Sets or changes a character's choice. Returns the refusal message or None.",
    params =
        listOf(
            PluginParam("actor", "actor", "The actor to modify."),
            PluginParam("choice", "str", "The choice id to set."),
            PluginParam(
                "option",
                "str",
                "The option id, or None to clear.",
                required = false,
                defaultValue = null,
            ),
        ),
    returnType = "str",
) { world, pos, _ ->
    val actor =
        pos[0] as? PluginActor
            ?: throw IllegalArgumentException("heritage.choose(actor): expected an actor")
    val choice =
        pos[1] as? String
            ?: throw IllegalArgumentException("heritage.choose(choice): expected a string")
    val option = if (pos.size > 2) pos[2] as? String else null
    world.setChoices(actor, mapOf(choice to option))
}
```

### World start

`onWorldStarted` callbacks run once the world has loaded, in plugin-id order.

```kotlin
r.onWorldStarted { world ->
    val regions = world.regions()
    world.log("weather: tracking ${regions.size} region(s): ${regions.joinToString(", ")}")
}
```

A failure in a command, effect, GMCP producer or start callback is recorded as a `"plugin"`
diagnostic and the pulse goes on. An exception from a builtin is a script error in the calling
hook.

## Installing a plugin

Put the jar at `plugins/<id>.jar` in the course world repository and pin its checksum in
`MODULE.gups`:

```bash
shasum -a 256 plugins/weather.jar
```

```python
module(
    api_version = 1,
    plugins = {"weather": "sha256:<the 64 hex digits shasum printed>"},
)
```

The server, `gups test`, `gups lint`, `gups fuzz`, `gups migrate-check`, `gups repro` and
`gups docs --world` all load the same jars. A jar in `plugins/` that `MODULE.gups` doesn't pin is
ignored with a warning. Adding, removing or changing a plugin needs a restart: deploys that touch
`plugins/` or `MODULE.gups` are refused.

A plugin that is missing, fails its checksum, has another id or API version, or fails in
`register` is skipped with a warning (logged, recorded as a diagnostic, and sent to instructors).
Packages that use it then fail to load, and the server starts without them.

## Trust

A plugin is trusted code with full JVM access: there is no sandbox. Only instructors can approve
changes to `MODULE.gups` and `plugins/` (CODEOWNERS), so review a plugin as you would engine code:

- Build the jar yourself from reviewed source, and pin the checksum of that build.
- Check that it imports only `gups.pluginapi.*`, `net.starlark.java.*`, Kotlin and the JDK.
- Look for file, network, process, reflection or class-loading calls; a game plugin rarely needs any.
- Check that builtins are deterministic where tests rely on them (no clock or unseeded randomness).
- Make sure it fails loudly rather than silently, and that its commands don't need a higher role
  than they declare.
- Re-review on every engine upgrade, since plugins must be rebuilt then.
