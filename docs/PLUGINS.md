# Writing Plugins

Plugins are `.jar` files that extend the engine with host builtins, commands, effect primitives, GMCP packages and a
world-start hook (SPEC.md §4.15). This guide uses the sample plugin in `samples/weather-plugin` as its running example:
deterministic weather by region. For step-by-step tutorials on building plugins from scratch, see the
[Weather Plugin Walkthrough](../samples/weather-plugin/docs/tutorials/README.md), the
[Heritage Plugin Walkthrough](../samples/heritage-plugin/docs/tutorials/README.md) (character creation and traits), and
the [Curse Plugin Walkthrough](../samples/curse-plugin/docs/tutorials/README.md) (item affects, custody propagation,
item restrictions, prototype transforms, and containment filters).

There are **no compatibility promises**. After the engine's first release, `PluginApi.VERSION` changes whenever the
plugin interface or the facade changes. Until then, the interface is still being designed and changes stay at version 1
(as unreleased `api_version` 1 does for scripts); plugins are rebuilt against each engine build anyway. Plugins built
for another version are refused at load, and must be rebuilt.

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

- `id` matches `[a-z][a-z0-9_]*`. Scripts reach the plugin's builtins and effects as fields of a global with that name
  (`weather.now(room)`), so it can't be a predeclared name such as `room`.
- `pluginApiVersion` must equal `PluginApi.VERSION`.
- `requiredFeatures` (default none) lists the engine features the plugin needs, such as `setOf("leveling")`. A world
  that has one of them off, or that names an unknown one, skips the plugin with `plugin '<id>': needs the '<feature>'
  feature`. `PluginWorld.features` lists the features the world has on, for a plugin that only uses some of them
  optionally; `PluginActor.level` throws an `IllegalStateException` naming `leveling`, and `PluginRoom.size` one naming
  `track`, when the feature is off.
- Command names are checked against the world's engine commands after `rename`: a plugin command may take a name the
  world renamed away (or whose feature is off), and may not take the new name.
- `register(r)` adds everything through the `PluginRegistry`. It is atomic: if it throws, or registers an invalid or
  clashing name, the plugin adds nothing and is skipped with a warning.

The jar names the class in `META-INF/services/gups.pluginapi.GupsPlugin`, one line:

```text
gups.sample.weather.WeatherPlugin
```

The engine finds it with `ServiceLoader`. The jar must have exactly one provider.

## What plugins can touch

Plugins never see the engine's `World` or entities. They get a `PluginWorld` facade:

- **verbs:** `send`, `echo`, `damage`, `heal`, `applyAffect`, `removeAffect`, `teleport`, `getAttr`, `setAttr` and
  `delAttr`. Each does what the `ctx` verb of the same name does, so the engine's invariants hold. Attribute keys
  without `ns:` are in the plugin's own namespace (its id), and a plugin writes only its own namespace. `damage(target,
  amount, type, vital)` and `heal(target, amount, vital)` name the vital they change, as the `ctx` verbs do.
- **leveling:** `awardXp(actor, amount)`, `takeXp(actor, amount)`, `levelUp(actor)` and `levelDown(actor, count = 1)` do
  what `ctx.award_xp`, `ctx.take_xp`, `ctx.level_up` and `ctx.level_down` do. They throw `IllegalStateException` naming
  `leveling` when the feature is off, and `IllegalArgumentException` when `amount` or `count` is below 1.
- **schema names:** a vital, stat or damage type the world's `MODULE.gups` doesn't declare, in a verb or in a
  `statDeltas` key, throws `IllegalArgumentException` with a "did you mean" suggestion.
- **reads:** `schema` (a `PluginSchema` with `stats` and `vitals`, each a `PluginNamed(name, label)`, and `damageTypes`,
  in declaration order), `pulse`, `seed`, `rng` (seeded from the world), `rooms()`, `room(label)` and `regions()`.
- **diagnostics:** `diagnose(message)` records a `"plugin"` diagnostic; `log(message)` writes to the server log.
- **views:** `PluginRoom`, `PluginActor` and `PluginItem` read current state on every access. A `PluginActor` has
  `stat(name)` (effective), `baseStat(name)`, `vital(name)`, `maxVital(name)` and `resist(type)` by schema name, plus
  `level`, `pendingLevels`, `affects` and `hasAffect(name)`.
- **`PluginViews`** converts raw Starlark values for builtins installed with `addBuiltin(name, value, doc)`:
  `room(value)`, `actor(value)`, `item(value)`, and `world(thread)` for a method declared with `useStarlarkThread =
  true`.

Every call runs on the world thread.

## Extension points

### Builtins

`addBuiltin` binds the Starlark arguments to the declared parameters (missing required → error, unknown keyword → error,
missing optional → its `defaultValue`) and converts them: ints to `Long`, floats to `Double`, `None` to `null`, lists to
`List`, dicts to `Map`, and room, actor and item views to `PluginRoom`, `PluginActor` and `PluginItem`. The return value
converts back. A builtin runs only while the world does, from a hook or command; anywhere else (top-level package code,
a test function's own body) it fails.

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

The weather is a pure function of the seed, the one-minute window (`WINDOW` is 240 pulses) and the room's region, so
tests are deterministic:

```kotlin
fun stateFor(world: PluginGmcpWorld, room: PluginRoom, pulse: Long = world.pulse): String {
    val region = room.regions.minOrNull() ?: return "clear"
    var z = world.seed xor (pulse / WINDOW) * -0x61c8864680b583ebL xor region.hashCode().toLong()
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    z = z xor (z ushr 31)
    return STATES[((z ushr 1) % STATES.size).toInt()]
}
```

### Commands

Commands take a lane (`FAST` runs at once, `TICK` queues on the actor) and a minimum role. They may not shadow engine
commands, directions or another plugin's commands, and a script `global_command` with the same name is skipped.

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
    call.world.gmcp(call.actor, "Gups.Forecast", forecast(call.world, room))
}
```

### Effect primitives

An effect primitive is called in package code like an engine one, and its value works wherever theirs does:
`skill(effect = weather.soak(pulses = 3))`, or inside `sequence(...)`. Its arguments must be plain values (no entities),
because the effect is part of a frozen prototype. The engine calls `apply` when the skill resolves; `target` is null for
`target = "none"` skills.

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

On an `item` skill, `call.targetItem` is the item and `call.target` is null:

```kotlin
r.addEffectPrimitive(
    name = "bestow",
    doc = "Bestows a curse on an item by tagging it cursed.",
    params = emptyList(),
) { call ->
    val item = call.targetItem ?: return@addEffectPrimitive
    call.world.addTag(item, "cursed")
}
```

On a `room` skill, `call.targetRoom` is the caster's room, and both `call.target` and `call.targetItem` are null. A
primitive written for an actor or item target that gets a room call has no view of its usual target; ignore it or behave
as when handed no item.

### GMCP packages

A state package's producer runs in phase 7 for each player whose client supports the package, and only changed payloads
are sent. Return plain values.

```kotlin
r.addGmcp("Gups.Weather", "The weather where the player stands.") { world, actor ->
    mapOf("state" to stateFor(world, actor.room), "region" to actor.room.regions.minOrNull())
}
```

An event package sends one message at a time with `world.gmcp`. It is queued with the session's text and never cached;
it returns `false`, sending nothing, for a mob, a link-dead player, or a client that doesn't support the package. A
plugin may send only under a package it declared, or one nested in it. The `weather` command above sends the forecast
after its text.

```kotlin
r.addGmcpEvent(
    "Gups.Forecast",
    "The weather now and in the next minute where the player stands.",
)
```

An input package receives GMCP messages from the player's client for exactly that package (ignoring case). The handler
gets the sending player, the data as plain values (`String`, `Long`, `Double`, `Boolean`, `List`, `Map` or `null`), and
a read-only `PluginGmcpWorld`, which can read, send text and send GMCP events but not change the world. To act, it calls
`call.command(line)` at most once, which runs the line as if the player typed it, through the normal lanes, lag,
restrictions and repro recording.

```kotlin
r.onGmcp(
    "Gups.Weather.Get",
    "Sends Gups.Forecast, or with {\"describe\": true} runs `weather`.",
) { call ->
    if ((call.data as? Map<*, *>)?.get("describe") == true) call.command("weather")
    else call.world.gmcp(call.actor, "Gups.Forecast", forecast(call.world, call.actor.room))
}
```

Names have at least two dot-separated parts and are compared ignoring case. No package may use the reserved `Core` root
or overlap an engine package (`Char.*`, `Room.*`, `Comm.Channel.Text`, `Gups.Trace`) or another plugin's package. An
input package may share the plugin's own state and event tree, but a plugin's state and event packages may not equal,
contain or nest in each other. A handler that throws is recorded as a `"plugin"` diagnostic.

Inbound messages are limited. A body over 4096 bytes (UTF-8), nested more than 8 levels deep, or not valid JSON is
malformed: the handler doesn't run and the message counts as dropped input, like a flood, so a client that keeps sending
them is disconnected. Each message takes a token from the session's line limit and then one from the fast-lane bucket
("Slow down." when there is none); `command` charges nothing more. Messages for a package no plugin registered, and
messages sent before the player has a character, are ignored.

### Custom hook handlers

Scripts declare custom hooks on rooms, mobs and items (`hooks = {"name": fn}`) and run them with `ctx.call_hook(entity,
name, **args)`. A plugin joins the chain of a custom hook name with `addHook`, and its handler runs after the entity's
own hook, in plugin-id order. The handler gets a read-only `PluginGmcpWorld`, the entity, the arguments and the result
so far — all as plain values, with entities as plugin entity views — and returns the new result as plain values only. A
handler that throws is recorded as a `"plugin"` diagnostic and leaves the result unchanged. Names match
`[a-z][a-z0-9_]*` and may not be a catalog hook name; one plugin registers a name once, and several plugins may share
it.

The hook runs on the entity the script names (for a shop, the keeper), so anything else the handler needs arrives in
`call.args`. A `null` result means "keep the default", so a handler that adjusts a price starts from the result so far
when there is one and from the default otherwise. From `samples/curse-plugin`, where `bearsCurse` checks the item and
what it holds for the `cursed` tag:

```kotlin
r.addHook("shop_price", "Charges 25 more to buy a cursed item.") { call ->
    val item = call.args["item"] as? PluginItem
    val buying = call.args["action"] == "buy" || call.args["action"] == "list"
    val price = if (call.result == null) call.args["price"] as? Long else call.result as? Long
    if (buying && price != null && item?.let(::bearsCurse) == true) price + 25L else call.result
}
```

Script ints arrive as `Long`. A handler may return `Int`s and any `Iterable`; they reach the next handler and the script
as `Long`s and lists. Floats and other types are refused like a throw.

### Character creation, traits and restrictions

Plugins can define traits, character creation choices and operational restrictions (`samples/heritage-plugin`):

#### Traits

Traits carry stat, max-vital and resist deltas, flags, and modifiers. They are registered on the plugin registry. A
modifier at a defender point (`DEFEND_HIT`, `RECEIVE_DAMAGE` or `MITIGATE`) runs on the chain of the actor being hit, so
the call's `actor` is the trait's bearer, the defender, and `other` the attacker. The `BONUS_DAMAGE` modifier takes and
returns a `Map<String, Long>` keyed by damage type; return the base with entries added. `actor` is the attacker. The
heritage plugin's elf is harder to hit the higher its dexterity:

```kotlin
r.addTrait(
    PluginTrait(
        name = "elf",
        label = "Elf",
        doc = "Nimble and magical, but fragile.",
        statDeltas = mapOf("dex" to 2, "con" to -1),
        maxVitalDeltas = mapOf("mana" to 10),
        modifiers =
            mapOf(
                PluginModifierPoint.DEFEND_HIT to
                    PluginModifier { call, base ->
                        // On a defender point call.actor is the bearer, the one being swung at.
                        (base as Long) - call.actor.stat("dex") / 2
                    }
            ),
    )
)
```

A modifier tells a spell from a swing by `call.skill`: the skill being cast (a `PluginSkill`) when the rules call has
one, which is an enemy spell's `TO_HIT` and `DEFEND_HIT` roll and a spell's `DAMAGE`, `RECEIVE_DAMAGE` and `MITIGATE`
chains, and null otherwise. A `DEFEND_HIT` modifier returning `if (call.skill?.kind == "spell") 0L else base` makes its
bearer resist every enemy spell of kind `"spell"` cast at it, while swings roll as usual.

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

Restrictions intercept operations (`WEAR`, `ENTER`, `CAST`, `LEARN`, `REMOVE`, `DROP`, `GIVE`, `PUT`, `ORDER`) and can
refuse them. `ORDER` is a player giving an order to one of its pets: `call.actor` is the owner and `call.target` the
pet:

```kotlin
r.addRestriction(PluginRestrictionPoint.WEAR, "Mages cannot wear heavy armor.") { call ->
    val actor = call.actor
    val item = call.item
    if (actor.hasTrait("mage") && item != null && "heavy_armor" in item.tags) {
        "Your spellcraft can't work through that much metal."
    } else null
}
```

Item restrictions guard commands only, for players and for mobs acting through commands. Script and facade verbs
(`move_item`, `give`, `destroy`, `transform`) and the engine's own moves (death, decay) don't run them. The `WEAR`
restriction is also asked again about worn items after a change of choices or traits; a refused item stays worn but is
inactive, contributing nothing, until a later re-check allows it. For details on item restrictions and swap validation,
see the [Item Restriction Points Tutorial](../samples/curse-plugin/docs/tutorials/02_item_restriction_points.md).

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

### Item affects, tags and transform

The facade allows plugins to inspect and manipulate items, their effective tags, instance affects, and prototype
transforms:

- **Tags:** `world.addTag(item, tag)` and `world.removeTag(item, tag)` add or remove per-instance tags. Effective tags
  (`item.tags`, `item.hasTag(tag)`) reflect prototype tags plus added minus removed tags.
- **Charges:** `world.setCharges(item, charges)` sets an item's remaining uses, from 0 to its prototype's charges
  (`item.maxCharges`); setting 0 does not run its `empty` action. Refuses unlimited and stackable items. `item.charges`
  reads uses left (`null` when unlimited), so `if (item.charges == 0) call.world.setCharges(item, item.maxCharges!!)`
  recharges an empty item.
- **Instance affects:** `world.applyItemAffect(item, name, duration, applies = "worn", ...)` and
  `world.removeItemAffect(item, name)` apply or remove instance affects. Setting `duration = null` creates a permanent
  affect. `applies` must be `"worn"` or `"carried"`.
- **Transform:** `world.transform(item, label)` swaps an item's prototype in place, preserving its id, location,
  attributes, instance affects and tag changes. The curse sample's `lift` primitive does this for an item skill's
  `call.targetItem`, here bound to `item`:

```kotlin
val clean = item.variants["uncursed"]
if (clean != null) {
    call.world.transform(item, clean)
} else {
    call.world.removeTag(item, "cursed")
}
```

Prefer `transform` over removing a tag when the clean version is its own prototype. Tag changes are kept across a
`transform`, so removing a prototype tag first would leave the new item with a stray `-cursed` change, and an item with
tag changes never merges into a stack. For an end-to-end example, see the
[Curse Plugin Walkthrough](../samples/curse-plugin/docs/tutorials/03_item_effects_transform_and_variants.md).

### Containment filters

Containment filters intercept carried item affects reaching their holder through containers:

```kotlin
r.addContainmentFilter("Blocks carried affects behind warded containers.") { call ->
    call.container.hasTag("warded") && call.item.hasTag("cursed")
}
```

When the engine recomputes an actor's holdings, registered containment filters are called for each container along the
path to a carried item affect. Returning `true` blocks the affect from propagating beyond that container. If a filter
throws an exception, the exception is logged as a `"plugin"` diagnostic and the affect is not blocked.

Filters never see worn or directly carried items: only an affect behind at least one container is filtered. The engine
caches the result and recomputes it only when custody, item affects, item tags or prototypes change, so a filter may
read only the container's and item's label, kind, tags and affects. A filter that reads anything else (attributes, the
holder's stats, the pulse, randomness) gives answers the cache won't notice changing. Filters must also be fast and
side-effect free. For a detailed guide and testing examples, see the
[Containment Filters Tutorial](../samples/curse-plugin/docs/tutorials/04_containment_filters.md).

### Owned behaviors

While a mob has an owner it runs an owned behavior list instead of its prototype's behaviors. A plugin's
`addOwnedBehaviors` handler adjusts that list after the world's `owned_behaviors` rules export: the handler gets
`call.mob`, `call.owner` and `call.behaviors`, and returns the new list, for example the same maps with `heel`'s
`priority` raised to `80L`.

Adjusters run in plugin-id order, each getting the list so far and returning the new one. `call.owner` is null while the
owner is offline. Each behavior is a plain map: `kind` (the builtin's name, such as `"heel"` or `"defend"`) and its
builtin arguments by name, with ints as `Long`, actor filters (`toward`, `allies`, `target`) as lists of strings, where
a label pattern is absolute (`//pkg:name*`) or a bare `name*` matching in any package (a `:`-relative pattern is
invalid, since a plugin has no package), `below` as a map of vital names to `Long`, and `exit` as a direction name. An
invalid map, or an adjuster that throws, records a `"plugin"` diagnostic and leaves the list unchanged.

The list is computed when a mob gains an owner, when a pet is restored at its owner's login, and after a reload, so an
adjuster should depend only on the mob, its owner and the list it is given.

### World start

`onWorldStarted` callbacks run once the world has loaded, in plugin-id order.

```kotlin
r.onWorldStarted { world ->
    val regions = world.regions()
    world.log("weather: tracking ${regions.size} region(s): ${regions.joinToString(", ")}")
}
```

A failure in a command, effect, GMCP producer, GMCP input handler or start callback is recorded as a `"plugin"`
diagnostic and the pulse goes on. An exception from a builtin is a script error in the calling hook.

## Installing a plugin

Put the jar at `plugins/<id>.jar` in the course world repository and pin its checksum in `MODULE.gups`:

```bash
shasum -a 256 plugins/weather.jar
```

```python
module(
    api_version = 1,
    plugins = {"weather": "sha256:<the 64 hex digits shasum printed>"},
)
```

The server, `gups test`, `gups lint`, `gups fuzz`, `gups migrate-check`, `gups repro` and `gups docs --world` all load
the same jars. A jar in `plugins/` that `MODULE.gups` doesn't pin is ignored with a warning. Adding, removing or
changing a plugin needs a restart: deploys that touch `plugins/` or `MODULE.gups` are refused.

A plugin that is missing, fails its checksum, has another id or API version, or fails in `register` is skipped with a
warning (logged, recorded as a diagnostic, and sent to instructors). Packages that use it then fail to load, and the
server starts without them.

## Trust

A plugin is trusted code with full JVM access: there is no sandbox. Only instructors can approve changes to
`MODULE.gups` and `plugins/` (CODEOWNERS), so review a plugin as you would engine code:

- Build the jar yourself from reviewed source, and pin the checksum of that build.
- Check that it imports only `gups.pluginapi.*`, `net.starlark.java.*`, Kotlin and the JDK.
- Look for file, network, process, reflection or class-loading calls; a game plugin rarely needs any.
- Check that builtins are deterministic where tests rely on them (no clock or unseeded randomness).
- Make sure it fails loudly rather than silently, and that its commands don't need a higher role than they declare.
- Re-review on every engine upgrade, since plugins must be rebuilt then.
