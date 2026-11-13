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
- `requiredFeatures` (default none) lists the features the plugin needs: engine features such as `setOf("leveling")`, or
  features another plugin provides ([Feature-sized plugins](#feature-sized-plugins)). A world that has one of them off,
  or that names one neither the engine nor a loading plugin has, skips the plugin with `plugin '<id>': needs the
  '<feature>' feature`. `PluginWorld.features` lists the features the world has (the engine features that are on and
  those loaded plugins provide), for a plugin that only uses some of them optionally; `PluginActor.level` throws an
  `IllegalStateException` naming `leveling`, and `PluginRoom.size` one naming `track`, when the feature is off.
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

- **verbs:** `send`, `gmcp`, `echo`, `damage`, `heal`, `applyAffect`, `removeAffect`, `teleport`, `getAttr`, `setAttr`
  and `delAttr`. Each (except `gmcp`, which sends a GMCP event to a client) does what the `ctx` verb of the same name
  does, so the engine's invariants hold. `teleport` asks the `enter` restriction and `before_enter` first, and returns
  the refusal or veto, or null once the actor has moved. Attribute keys without `ns:` are in the plugin's own namespace
  (its id), and a plugin writes only its own namespace. `damage(target, amount, type, vital, source)` and `heal(target,
  amount, vital, source)` name the vital they change, as the `ctx` verbs do. A `source` passed to either is credited
  with the effect.
- **skills and items:** `skillRoll(actor, skill, item, target, event = "craft", adjust = 0)` rolls a skill outside a
  cast and returns a `PluginSkillRoll(chance, roll, success, power)`, as `ctx.skill_roll` does (it throws
  `IllegalStateException` naming `skills` when that feature is off); `setItemName(item, name)` sets or clears an item's
  name override, as `ctx.set_item_name` does; `wear(actor, item, wield = false)` and `removeWorn(actor, item)` put a
  carried item on and take a worn one off, as `ctx.wear` and `ctx.remove_worn` do: they ask the `WEAR` and `REMOVE`
  restrictions, return the refusal or null, and send nothing. `mayWear(actor, item, wield = false)`, `mayRemove`,
  `mayDrop`, `mayGive(actor, item, to)`, `mayPut(actor, item, container)`, `mayEnter(actor, room)` and `mayLearn(actor,
  skill, proficiency)` ask whether the matching command or verb would refuse, as the `ctx.may_*` queries do: each
  returns the refusal message or null, moves nothing and runs no hook. An argument the command couldn't act on, such as
  an item the actor doesn't carry, throws `IllegalArgumentException`. `fits(item, to, count = null)` and `fits(label,
  to, count = null)` return how many of `count` would fit at `to`, a container item, an actor or a room, as `ctx.fits`
  does: the container's free capacity and weight, an actor's `carry_limit` asked about the item, any number for a room.
  They change nothing; an unknown label, a `to` that isn't a container, or a count outside the stack's throws
  `IllegalArgumentException`.
- **timed tasks:** `startTask`, `stopTask`, `PluginActor.task` and `PluginActor.isBusy`; see
  [Timed tasks](#timed-tasks).
- **leveling:** `awardXp(actor, amount)`, `takeXp(actor, amount)`, `levelUp(actor)` and `levelDown(actor, count = 1)` do
  what `ctx.award_xp`, `ctx.take_xp`, `ctx.level_up` and `ctx.level_down` do. They throw `IllegalStateException` naming
  `leveling` when the feature is off, and `IllegalArgumentException` when `amount` or `count` is below 1.
- **doors and containers:** `openDoor(room, dir)`, `closeDoor`, `lockDoor`, `unlockDoor`, `openContainer(item)`,
  `closeContainer`, `lockContainer` and `unlockContainer` set the exit's or container's state, and `room.exit(dir)`
  returns a `PluginExit(direction, to, tags, door, key, passable, oneWay)` view of the exit, where `door` is a
  `PluginDoor(state, key, tags)`, null when the room has no exit in that direction, while `item.containerState` reads a
  container's state, null for an item that isn't a container. The door and container verbs follow `ctx`: they run no
  hook, take no key and print nothing, and locking fails on a door or container without a `key`.
- **messaging and simulation:** `say(speaker, text)` and `emote(actor, text)` speak as a named actor at once, as
  `ctx.say` and `ctx.emote` do. `emit(room, channel, event, intensity = 1, text = null, actor = null)` raises a
  perceivable event on a declared channel; an `intensity` of 0 or less emits nothing, and an unknown channel throws with
  a suggestion. `setActive(entity, reason)`, `clearActive`, `setAnchor(entity, reason)` and `clearAnchor` add and remove
  named reasons that keep an entity simulated or projecting a bubble, as `ctx.set_active` and friends do;
  `clearStanding(holder, subject = null)` removes a holder's standing records, and `roll(dice)` rolls a dice expression
  such as `2d6+1` with the world's RNG.
- **mobs:** `spawnMob(label, room, count = 1)` makes `count` mobs as `ctx.spawn` does for a mob label and returns them:
  no owner, no leash override and no spawn rule, a home room equal to `room`, at track index 0. `act(mob, commandLine)`,
  `actMove(mob, dir)`, `actAttack(mob, target)` and `actCast(mob, skill, target = null)` queue tick-lane actions for a
  mob, never a player, and return whether the action was queued; `actAttack` needs the `combat` feature and `actCast`
  the `skills` feature. `moveToward(mob, dir)`, `moveToward(mob, room)` and `moveToward(mob, target)` queue one step
  toward a direction, a room or an actor, returning false when there is no way. `summon(caster, label, count = 1,
  duration = null)` spawns mobs beside the caster as `ctx.summon` does; a `before_summon` veto returns an empty list.
  `setOwner(mob, player)` makes an in-world player the owner of a mob and `clearOwner(mob)` releases it, each returning
  whether anything changed; `setLeash(mob, leash)` overrides the mob's leash with an int of at least 0 or unleashes it
  with null, and `resetLeash(mob)` drops the override. `PluginActor` reads `owner` (the in-world player or null),
  `homeRoom` (a mob's home room or null), `leash` (a mob's effective leash) and `leashOverridden`.
- **combat and groups:** `startCombat(a, b)`, `stopCombat(a, b = null)` and `clearContributions(victim, contributor =
  null)` engage and disengage combat and credit contributions, as `ctx.start_combat`, `ctx.stop_combat` and
  `ctx.clear_contributions` do; `startCombat`, `stopCombat` and the `PluginActor.fighting` / `primaryTarget` reads need
  the `combat` feature, and `startCombat` refuses an actor fighting itself or one across rooms. `groupAdd(member, to)`,
  `groupRemove(member)`, `groupSetLeader(member)` and `groupDisband(member)` manage player groups, as the `ctx.group_*`
  verbs do, and a mob argument throws. `enemiesNear(actor, band)` and `alliesNear(actor, band)` return the opponents and
  allies of `actor` in its room within `band` (`"melee"`, `"near"` or `"far"`), by id, as `ctx.enemies_near` and
  `ctx.allies_near` do. `pathTo(from, to)` gives the directions of the shortest passable walk, empty when `from` is
  `to`, null when unreachable. `plan(facts, actions, goal, maxExpansions = 1024)` runs the GOAP planner and returns the
  cheapest action names that reach `goal`, `[]` when it already holds, null when unreachable; fact values are `Boolean`,
  `Int`/`Long` or `String`, and an action is a `PluginPlanAction(name, pre, effects, cost)`. `groupOf(actor)` returns a
  `PluginGroup(id, leader, members)` of the player group `actor` is in, and `groupMembers(actor)` its members, leader
  first.
- **schema names:** a vital, stat or damage type the world's `MODULE.gups` doesn't declare, in a verb or in a
  `statDeltas` key, throws `IllegalArgumentException` with a "did you mean" suggestion.
- **reads:** `schema` (a `PluginSchema` with `stats` and `vitals`, each a `PluginNamed(name, label)`, and `damageTypes`,
  in declaration order), `pulse`, `seed`, `features` (the engine features that are on and the features loaded plugins
  provide), `rng` (seeded from the world, with `int`, `chance` and `choice(list)`), `standingHistory(holder, subject,
  since, via, event)` (the `PluginStandingTotals` of gains and losses, needing `standing`), `rooms()`, `room(label)`,
  `regions()` and `itemInfo(label)` (an item prototype's catalog fields as a `PluginItemInfo`, or null, as
  `ctx.item_info` gives them). The world also reads `enemiesNear(actor, band)`, `alliesNear`, `pathTo(from, to)`,
  `plan(facts, actions, goal, maxExpansions)` and the group queries `groupOf(actor)` and `groupMembers(actor)`; a
  `PluginActor` reads `fighting` and `primaryTarget` (both need `combat`). `PluginRoom` also reads `desc` and the names
  of its live `affects`.
- **diagnostics:** `diagnose(message)` records a `"plugin"` diagnostic; `log(message)` writes to the server log.
- **views:** `PluginRoom`, `PluginActor` and `PluginItem` read current state on every access. A `PluginActor` has
  `stat(name)` (effective), `baseStat(name)`, `vital(name)`, `maxVital(name)` and `resist(type)` by schema name, plus
  `level`, `pendingLevels`, `affects` and `hasAffect(name)`, and reads `inWorld`, `keywords`, `xp` (needs `leveling`),
  `condition`, `senses`, `belowZeroSince(vital)`, `carriedWeight`, `encumbranceLevel`, `isCasting` and `casting` (both
  need `skills`), `hasFlag(flag)`, `hasItem(label)`, `slots`, `waitState`, `movePace` and `recallRoom`. A `PluginItem`
  reads `inWorld`, `dice`, `damageType`, `statDeltas` and `senseDeltas`.
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

### Rules exports

A plugin can let the world tune its numbers by declaring an optional rules export with `addRulesExport`. The name is the
plugin id, an underscore and a snake_case name, and the type is `PluginRulesType.INT` or `BOOL`. Declare it, then ask
for the value where the plugin needs it, passing what the plugin would use by itself as `base`:

```text
r.addRulesExport("bluemage_learn_chance", "Percent chance a blue mage learns the skill.", PluginRulesType.INT)

val pct = (world.rules(
    "bluemage_learn_chance",
    mapOf("actor" to bearer, "source" to caster, "skill" to skill, "base" to base, "power" to power),
    default = base,
) as Long).toInt().coerceIn(0, 33)
```

The world defines the export in its rules package (`def bluemage_learn_chance(ctx): return ctx.base + ctx.actor.int -
10`). `actor`, `target`, `source`, `skill` and `item` become those `ctx` fields, `base` becomes `ctx.base`, and any
other field is in the `ctx.args` dict. When the world doesn't define the export, or the call fails, `rules` returns the
default, so the plugin works without any world support. Clamp the result after the call, since the world's value is
untrusted.

A world that doesn't load the plugin must not define the export: it is then an ordinary "is not a rules export" load
error. The load-time dry run calls each defined export once with `ctx.base` 0 (or `False`) and empty `ctx.args`, so a
rule should read `ctx.args` with `get`. `@trace rules` shows each call.

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

Traits can also react. `on` maps an event to a `PluginTraitHandler` that runs for each bearer when the event happens to
it, and unlike a modifier it may change the world through `call.world`. The call carries the `bearer`, the `event` and
its `fields`, where ints are `Long`, actors are `PluginActor` and rooms are `PluginRoom` and items are `PluginItem`:

- `SKILL_RECOGNIZED`: a player recognized a skill they don't know, after a cast that succeeded or was resisted. Fields:
  `source`, `skill` (the label), `outcome`, `target` (a `PluginActor` or null) and `power`.
- `DAMAGED`: the bearer took damage with a positive loss. Fields: `source` (a `PluginActor` or null), `amount`, `type`
  (the damage type's name), `losses` (vital name to loss) and `parts` (a list of maps with `type` and `losses`).
- `HEALED`: the bearer gained vital points from a heal. Fields: `source` (a `PluginActor` or null), `vital`, `amount`
  (after the maximum cap) and `requested`.
- `KILLED`: the bearer landed the killing blow. Field: `victim`.
- `DIED`: the bearer died. Field: `killer` (a `PluginActor` or null). The handler can't prevent the death.
- `LEVEL_CHANGED`: the bearer gained or lost a level, once per step, with leveling enabled. Fields: `old_level` and
  `new_level`.
- `ROOM_ENTERED`: the bearer arrived by walking, fleeing or teleporting. Fields: `from_room` (a `PluginRoom`) and
  `direction` (a `String`, or null for a teleport).
- `AFFECT_GAINED`: an affect was put on the bearer, new or replacing one that ran out sooner. Fields: `name`, `flags` (a
  sorted list of `String`s), `source` (a `PluginActor` or null), `source_item` (the `PluginItem` the affect lasts while
  it is worn, or null), `power`, `expires_at` (the absolute pulse, or null when it never runs out) and `replaced` (a
  `Boolean`).
- `AFFECT_LOST`: an affect came off the bearer, by expiry, `remove_affect`, `dispel` or its item coming off, after its
  `on_expire`. Fields: `name`, `flags`, `source`, `source_item`, `power` and `reason` (`"expired"` or `"removed"`).

Handlers run synchronously where the event happens, right after the script hooks on entities, the room and items there.
At death and at a level change they run before the global hooks and plugin event listeners. Bearers can be players or
mobs, except for `SKILL_RECOGNIZED`, which is players only. A handler that causes another event (a `DAMAGED` handler
that deals damage, say) is cut off by the cascade depth, with a `cascade` diagnostic. `call.world.skill(label)` returns
a loaded skill's view, or null. A handler that throws records a `plugin` diagnostic. A replaced affect fires only
`AFFECT_GAINED`, with `replaced` true, and restoring a saved actor fires neither. See `samples/bluemage-plugin` for a
trait that learns skills from `SKILL_RECOGNIZED`.

A trait that reacts when its bearer is hurt (from `samples/bluemage-plugin`, which registers it with
`PluginTraitEvent.DAMAGED to PluginTraitHandler(::onDamaged)`):

```kotlin
private fun onDamaged(call: PluginTraitCall) {
    val world = call.world
    val source = call.fields["source"] as? PluginActor ?: return
    // Only the first blow from each new attacker, so a long fight doesn't repeat the line.
    if (source.isPlayer || world.getAttr(call.bearer, STUDIED, null) == source.id) return
    world.setAttr(call.bearer, STUDIED, source.id)
    world.send(call.bearer, "You study how ${source.name} fights.")
}
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

        override fun prepare(world: PluginWorld, actor: PluginActor, attempt: PluginCreationAttempt) {
            val bonus = attempt.state["bonus_str"] as? Int ?: 0
            if (bonus > 0) {
                world.setBaseStat(actor, "str", actor.baseStat("str") + bonus)
            }
        }

        override fun created(world: PluginWorld, actor: PluginActor, newCharacter: Boolean) {
            // Both new and re-picked characters get the starter skills their class lacks.
            for (label in STARTER_SKILLS[actor.choices["class"]].orEmpty()) {
                if (label in actor.skills) continue
                try {
                    world.setSkill(actor, label, 25)
                } catch (_: IllegalArgumentException) {
                    // Skill not defined in world
                }
            }
        }
    }
)

val STARTER_SKILLS =
    mapOf(
        "mage" to listOf("//skills/heritage_skills:magic_missile"),
        "cleric" to listOf("//skills/heritage_skills:minor_heal"),
    )
```

`samples/heritage-plugin` keeps this loop in `HeritageCreation.grantStarterSkills`, which its `heritage.starter_skills`
builtin also calls after a respec.

##### prepare

`prepare` runs before a new character enters the world, after traits and `starting_stats` have been applied. While
`prepare` runs, base-value setters (`setBaseStat`, `setBaseMaxVital`, `setBaseResist`), trait verbs (`grantTrait`,
`removeTrait`), skill verbs (`setSkill`, `removeSkill`), and attributes (`setAttr`, `delAttr`) accept the pre-entry
character. Any vital whose current value was not explicitly specified by `starting_stats` is refilled to its effective
maximum once `prepare` returns. Read stats from `actor.baseStat(...)` rather than `preview` so adjustments stack on top
of the world's `starting_stats`.

##### Custom steps

A provider may declare custom steps (`PluginCreationStep`) in its `steps` list, shown in order after the choices and
before confirmation. Each step provides:

- `id`: identifier `[a-z][a-z0-9_]*`, unique among steps and distinct from choice ids.
- `title`: display heading (e.g. `"Stats"`).
- `applies(world, attempt)`: whether the step runs for the current attempt. Skipped if false.
- `show(world, attempt)`: returns `PluginStepView(text, gmcp)` to present prompts, instructions and state.
- `input(world, attempt, line)`: handles player input lines, returning `PluginStepReply.Stay(message)` to redisplay with
  an optional message or `PluginStepReply.Done` to advance to the next step.
- `summary(world, attempt)`: optional single-line summary string appended to the confirmation prompt and GMCP summaries.

Every step receives the `PluginCreationAttempt`:

```kotlin
interface PluginCreationAttempt {
    val name: String
    val answers: Map<String, String>
    val seed: Long
    val state: MutableMap<String, Any?>
    val preview: PluginActor
    fun rng(stream: Int): PluginRng
}
```

The `state` map is shared across all steps and `prepare`. If a player navigates `back` to a choice or changes an answer,
`state` is cleared, previous step progress is reset, and the `preview` actor is reconstructed.

##### Adding rolls with attempt.rng

While the heritage sample implements point-buy, providers can implement dice rolls or random attribute assignment using
deterministic random streams from `attempt.rng(stream)`:

```kotlin
val rollRng = attempt.rng(0)
val rolledStrength = rollRng.int(1, 6) + rollRng.int(1, 6) + rollRng.int(1, 6)
attempt.state["rolled_str"] = rolledStrength
```

Best practices for random creation steps:

- **Numbered streams:** Use stream 0 for initial rolls, stream 1 for the first reroll, stream 2 for the second, etc.
- **Reroll limit:** Track reroll counts in `attempt.state` to enforce a hard cap.
- **Anti-farming guarantee:** `attempt.seed` is derived deterministically from the world seed, account ID, and creation
  counter. Canceling creation, typing `back`, or disconnecting replays the exact same random sequence, preventing
  players from repeatedly reconnecting to farm optimal stats. The world seed is drawn anew when the server starts, so
  the sequence changes only across server restarts, which players can't cause. Each call to `attempt.rng(stream)` within
  one step call continues the same stream; the next call (the next line the player types) starts it again.
- **Pre-entry application:** Apply the rolled values in `prepare` with `world.setBaseStat` and `world.setBaseMaxVital`.

For a full step-by-step walkthrough, see the
[Character Creation Provider Tutorial](../samples/heritage-plugin/docs/tutorials/02_character_creation_provider.md#10-adding-rolls-with-attemptrng).

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
(`move_item`, `grant_item`, `destroy`, `transform`) and the engine's own moves (death, decay) don't run them. The `WEAR`
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

#### Respec builtins

The heritage sample also registers four builtins for respec shrines, so world content can offer in-session changes with
its own price, cooldown and skill policy. The engine sets none of those.

- `heritage.respec_menu(actor, choices, allow_same=False)` lists each choice with its current option and every option
  with its refusal or `None`.
- `heritage.respec_check(actor, phrase, choices, allow_same=False)` matches the player's words to one option and says
  whether it may be picked, changing nothing.
- `heritage.respec(actor, choice, option, allow_same=False)` re-checks and then calls `setChoices`.
- `heritage.starter_skills(actor)` grants the class's starter skills the actor lacks.

The sample's `world/lib/respec.star` builds shrine keepers on them. See
[Tutorial 04: Runtime Choice Changes](../samples/heritage-plugin/docs/tutorials/04_runtime_choice_changes_and_builtins.md#4-a-respec-shrine).

#### Base values

A plugin can set an actor's base stats, base vital maxima, and base resistances directly using `setBaseStat`,
`setBaseMaxVital`, and `setBaseResist`. For example, a trainer command or builtin can train a character's base strength:

```kotlin
val currentBase = actor.baseStat("str")
world.setBaseStat(actor, "str", currentBase + 1)
```

Lowering a vital maximum caps `current` at the new effective maximum; raising it leaves `current` untouched. Changes
refresh encumbrance and equipment fit and persist with the actor.

### Item affects, tags and transform

The facade allows plugins to inspect and manipulate items, their effective tags, instance affects, and prototype
transforms:

- **Tags:** `world.addTag(item, tag)` and `world.removeTag(item, tag)` add or remove per-instance tags. Effective tags
  (`item.tags`, `item.hasTag(tag)`) reflect prototype tags plus added minus removed tags.
- **Charges:** `world.setCharges(item, charges)` sets an item's remaining uses, from 0 to its prototype's charges
  (`item.maxCharges`); setting 0 does not run its `empty` action. Refuses unlimited and stackable items. `item.charges`
  reads uses left (`null` when unlimited), so `if (item.charges == 0) call.world.setCharges(item, item.maxCharges!!)`
  recharges an empty item.
- **Custody:** `world.spawnItem(label, to, count = 1)` spawns items at a room, an actor's inventory or a container item
  and returns a list: one stack for a stackable label, `count` separate items otherwise. `world.moveItem(item, to, count
  = null)` moves the whole stack or `count` of it and returns the item now there; `world.destroy(item, count = null)`
  removes it; `world.mergeStack(item)` folds it into an identical stack and returns the stack that holds it. They follow
  `ctx.spawn`, `ctx.move_item`, `ctx.destroy` and `ctx.merge_stack`: they skip item restrictions and the commands' carry
  and container limits (`world.fits` asks those), and room placement is at track index 0. There is no `grantItem`:
  `moveItem` or `spawnItem` with an actor as the destination puts the items in its inventory. A mob label, unknown
  label, count below 1 or non-container item as destination is an error.
- **Where things are:** `item.count` is the stack's size, `item.location` is a `PluginItemLocation` (`InRoom(room)`,
  `Carried(actor)`, `Equipped(actor, slot)` or `InContainer(container)`), `actor.inventory` lists carried items by id
  (not worn ones) and `actor.equipment` maps slot to worn item in slot order, inactive ones included.
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
adjuster should depend only on the mob, its owner and the list it is given. A descriptor's `prefer` is plain values, and
`retarget` is a kind like the others.

### Target order

A behavior with `prefer` (`aggressive`, `assist`, `retarget`) asks the world's `target_order` rules export whom to
attack. A plugin's `addTargetOrder(doc, choose)` handler adjusts that choice afterwards, in plugin-id order. The handler
gets `call.world` (read-only), `call.mob`, `call.behavior`, `call.prefer` as plain values (`Long`, `String`, `Boolean`,
`List`, `Map`), `call.candidates` in ascending id, `call.current`, `call.ally` and `call.choice`, the choice so far. It
returns `PluginTargetChoice.Default` (choose as without `prefer`), `Nobody`, or `Pick(actor)` with one of the
candidates; returning `call.choice` keeps it. A pick that isn't a candidate, or a handler that throws, records a
`"plugin"` diagnostic and keeps the choice so far. Handlers aren't called for a behavior without `prefer`, and must be
fast and side-effect free.

### Chained thinks

A mob whose think chose a tick-lane action is, once that action finishes, asked whether to think again as soon as it can
act, ignoring `think_every` (the `chain_think` rules export, §4.6). The built-in chasers (`pursue`, `follow`, `heel`,
`leash`) chain by default, so they keep moving at their `move_pace`. A plugin's `addChainThink(doc, decide)` handler
adjusts that answer afterwards, in plugin-id order. The handler gets `call.world` (read-only), `call.mob`, `call.source`
(the behavior that ended), `call.command`, `call.exit` (the direction name last crossed, or `null` for a non-move
action), `call.chain` (how many chained thinks this mob has done in a row) and `call.chained`, the answer so far, and
returns the new answer. A handler that throws records a `"plugin"` diagnostic and keeps the answer so far. Handlers must
be fast and side-effect free. The weather sample makes a storm wash out the trail, so a chaser caught in one goes back
to its cadence:

```kotlin
r.addChainThink("Storms wash out the trail, so chasers stop chaining.") { call ->
  call.chained && stateFor(call.world, call.mob.room) != "storm"
}
```

### Standing and factions

Plugins take part in standing (§5.14 of the spec) through `addDispositionModifier` and `addFactions`:

- `addDispositionModifier(doc, adjust)`: adjusts what one actor thinks of another after the rules `disposition` export,
  in plugin-id order. Handlers receive `call.world`, `call.holder`, `call.subject` and `call.disposition`, and return
  the adjusted value, which is clamped to `-100..100`.
- `addFactions(doc, adjust)`: adjusts an actor's faction list after the rules `factions` export, in plugin-id order.
  Handlers receive `call.world`, `call.actor`, and `call.factions`, and return the replacement list of faction label
  strings. Unknown faction labels are dropped with a `"plugin"` diagnostic.

A handler that throws, or that asks for the disposition or the factions it is adjusting, records a `"plugin"` diagnostic
and leaves the value unchanged.

`PluginWorld` provides:

- `disposition(holder, subject)`: what one `PluginActor` thinks of another, after the rules export and the modifiers.
- `standing(holder, subject)`: the amount of one standing record, or 0. The holder is a mob `PluginActor` or a faction
  label string, the subject a `PluginActor` or a faction label string. Declared faction opinions are not records.
- `factionsOf(actor)`: the faction labels `actor` belongs to.
- `raiseStandingEvent(affected, actor, event, amount = 0, item = null)`: raises a world standing event on `affected` by
  `actor`.
- `adjustStanding(holder, subject, amount)`: changes a standing record directly.

Combat memory (a mob's transient record of what it experienced and witnessed) is also on `PluginWorld`:

- `memories(mob, subject = null, event = null, since = 0)`: the mob's memories, newest first, as `PluginMemory`; a
  negative `since` counts as 0.
- `remember(mob, subject, event, skill = null, amount = 0, target = null)`: records a memory on a mob.
- `forget(mob, subject = null, event = null)`: removes the matching memories (all when both filters are null) and
  returns how many it removed.

### Timed tasks

`startTask(actor, pulses, name, lag, interrupt, onStop, onDone)` keeps an actor busy for `pulses`, like a cast's warmup,
then calls `onDone`; it follows the rules of `ctx.start_task`. It returns null when the task started, or the refusal for
the actor ("You're busy."). `onStop`, if given, gets a `PluginTaskStop` whose `reason` is `INTERRUPTED`, `CANCELLED`,
`INVALID` or `GONE`. Both callbacks get a fresh `world`, the `actor` and the `task`. A callback that throws is a
`plugin` diagnostic; the task has ended either way.

The hunger sample's `dine` builtin eats over a task, lowering hunger only when the meal completes:

```kotlin
// The engine names the task in its lines: "You stop eating."
world.startTask(actor, intArg(pos[2], "pulses"), "eating") { done ->
  feed(done.world, done.actor, amount)
  done.world.send(done.actor, "You finish eating.")
}
```

`lag` (recovery once it completes) and `interrupt` (a `PluginTaskInterrupt(stun, damagePct)`) are optional, as is
`onStop`.

`stop` and `clear` cancel the task and the engine says "You stop eating.", so name a task with a gerund phrase.
`stopTask(actor)` cancels a task of either kind, script or plugin, and `PluginActor.task` describes whichever is
running. Tasks are not saved.

### World start

`onWorldStarted` callbacks run once the world has loaded, in plugin-id order.

```kotlin
r.onWorldStarted { world ->
    val regions = world.regions()
    world.log("weather: tracking ${regions.size} region(s): ${regions.joinToString(", ")}")
}
```

A failure in a command, effect, GMCP producer, GMCP input handler, start callback, periodic work or event listener is
recorded as a `"plugin"` diagnostic and the pulse goes on. An exception from a builtin is a script error in the calling
hook.

## Feature-sized plugins

The samples above each add one mechanic. A feature-sized plugin is a complete system: it provides a named feature a
world can have on, refuses a world that can't support it, does periodic work, listens to engine events and offers a
builtin for hooks. `samples/hunger-plugin` ([hunger sample](../samples/hunger-plugin/README.md)) is one: it provides the
`hunger` feature, refuses a world with no `hp` vital, raises each player's hunger once per world tick, warns them as it
grows, resets it on death and adds `hunger.eat`. Its `register` reads the schema, runs the world checks and adds the
periodic work, the event listener and the builtin.

### Provided and required features

A plugin declares the features it provides with `providedFeatures`. A provided feature is `[a-z][a-z0-9_]*`, and may not
be an engine feature id (`combat`, `track`, `skills`, `leveling`, `standing`) or a reserved name; a plugin that breaks
this is skipped with `provides invalid feature '<name>'`. If two plugins provide the same feature, the one with the
larger id is skipped. A script tests a provided feature with `ctx.has_feature("hunger")`, which returns `False` when no
loaded plugin provides it. A plugin's namespace (`hunger.eat`) exists only while the plugin loads, so a file that calls
it fails to load without the plugin whatever `has_feature` guards; the guard is for code that only reads the plugin's
attributes, such as `ctx.get_attr(ctx.actor, "hunger:hunger", 0)`.

Another plugin may name a provided feature in its `requiredFeatures`, next to engine features. A plugin is skipped with
`needs the '<feature>' feature` when a feature it requires is neither an enabled engine feature nor provided by a plugin
that is loading, and that skip cascades: a plugin whose provider failed a check, refused the world or threw from
`register` is skipped too, and its registrations are discarded.

Built-in features are not plugins: they reach into the world's own state and phases (the combat round, track slots, XP
loss at death), so they stay inside the engine and are turned on in `MODULE.gups`. A plugin adds a feature through the
plugin interface instead. A plugin's feature is not recorded in the world's saved features; adding or removing a plugin
needs only a restart.

```kotlin
override val providedFeatures: Set<String> = setOf("hunger")
```

### World checks and refusal

`r.schema` is the world's `MODULE.gups` schema (stats, vitals and damage types, in declaration order) and `r.features`
the enabled engine features plus every feature a loading plugin provides. A plugin that needs a particular vital, stat
or damage type checks the schema and calls `r.refuse(reason)`, which skips the plugin with the warning `plugin '<id>':
refused: <reason>`, a `plugin` diagnostic and a `plugin_load` alert. Anything it registered before refusing is
discarded. Refusing is better than silently doing nothing: a world that turns off the vital hunger needs gets a loud
warning.

```kotlin
if ("hp" !in r.schema.vitals.map { it.name }) r.refuse("needs an 'hp' vital")
if (r.schema.damageTypes.isEmpty()) r.refuse("needs a damage type")
```

### Periodic work

`every(pulses, doc, callback)` runs the callback on every pulse where `pulse % pulses == 0`; `pulses` is at least 8 (one
combat round), and a smaller value fails `register`. It runs after the world tick's work and before perceptions are
delivered, in plugin-id order and then registration order. The callback gets a `PluginTickCall` with `world` and
`actors`: every simulated actor in the world, sorted by id, so mobs in dormant rooms are left alone. A failure is
recorded as a `"plugin"` diagnostic, and the next run happens as scheduled.

```kotlin
r.every(120, "Raises hunger once per world tick.") { call ->
    for (actor in call.actors) {
        if (!actor.isPlayer) continue
        val hunger = ((call.world.getAttr(actor, "hunger", 0L) as? Long) ?: 0L) + 1
        val next = hunger.coerceAtMost(STARVING)
        call.world.setAttr(actor, "hunger", next)
        when (next) {
            HUNGRY -> call.world.send(actor, "You are getting hungry.")
            STARVING -> {
                call.world.send(actor, "You are starving.")
                call.world.damage(actor, 1, damageType, "hp")
            }
        }
    }
}
```

### Engine event listeners

`onEvent(name, doc, listener)` listens to an engine event and runs after the scripts' global hook for it. The listener
gets a `PluginEventCall` with `world`, `event`, the `actor` and the event's `fields`. The seven events are
`on_level_change` (with `old_level` and `new_level`), `on_encumbrance_change` (with `old_encumbrance_level` and
`new_encumbrance_level`), `on_group_change` (with `event` and a `group`), `on_owner_change` (with `target` and `event`),
`on_disengage` (with `event`), `on_death` (with `killer`, or null) and `on_skill_use` (with `skill`, `outcome`,
`target`, `item`, and `targets`, the landed actors). Any other name fails `register`. Ints arrive as `Long`s and actors
as `PluginActor`s. A listener can't veto or change the event, but may change the world as a script global hook may;
listeners run in plugin-id order, then registration order. A listener for an event whose feature is off, such as
`on_level_change` without `leveling`, registers and never runs. At `on_death` the victim is still in the world, so the
listener can still change it.

```kotlin
r.onEvent("on_death", "Death resets hunger.") { call ->
    call.world.setAttr(call.actor, "hunger", null)
}
```

### The builtin

`hunger.eat(actor, amount)` lowers an actor's hunger by `amount`, not below 0, and returns the new value. A builtin runs
only from a hook or command, so an item's `on_use` hook calls it to feed the actor:

```python
def _feed(ctx):
    hunger.eat(ctx.actor, 3)
```

`hunger.dine(actor, amount, pulses)` eats over a [timed task](#timed-tasks): the actor is busy for `pulses`, hunger
drops only when the meal completes, and `stop` cancels it ("You stop eating.") with hunger unchanged. It returns None
once the meal starts, or the refusal message.

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
