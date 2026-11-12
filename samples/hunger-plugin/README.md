# Sample Plugin: Hunger System

This directory contains the feature-sized sample plugin for the GUPS engine: **a complete hunger system in a single
plugin**.

The other samples each add one mechanic ([weather](../weather-plugin) shows environmental hooks and a deterministic
builtin, [heritage](../heritage-plugin) shows character creation and traits, [curse](../curse-plugin) shows item affects
and restrictions). `hunger-plugin` shows a feature-sized plugin: one that provides a named feature, refuses a world that
can't support it, does periodic work, listens to an engine event, and offers a builtin for hooks. It demonstrates each
of those extension points with the hunger mechanic:

* **Provided feature:** `providedFeatures = setOf("hunger")`. Scripts test it with `ctx.has_feature("hunger")`, and
  other plugins may name it in `requiredFeatures`.
* **World check and refusal:** if the world has no `hp` vital or no damage type, `register` calls `r.refuse(...)`, so
  the world is skipped with a loud warning rather than silently doing nothing.
* **Periodic work:** `r.every(120, ...)` raises each simulated player's hunger once per world tick, after the world
  tick's work, in plugin-id order. It sends "You are getting hungry." at 5 and "You are starving." at 10, and at 10 it
  deals 1 damage.
* **Engine event listener:** `r.onEvent("on_death", ...)` resets hunger when the actor dies. It runs after the scripts'
  global `on_death` hooks, while the dying actor is still in the world.
* **Builtin:** `hunger.eat(actor, amount)` lowers an actor's hunger by `amount`, not below 0, and returns the new value.
  An item's `on_use` hook calls it to feed the actor.
* **Timed task:** `hunger.dine(actor, amount, pulses)` eats over a timed task (`world.startTask`): the actor is busy for
  `pulses`, hunger drops only when the meal completes, and `stop` cancels it ("You stop eating.") with hunger unchanged.
  It returns None once the meal starts, or the refusal message ("You're busy.").

The plugin's source is commented to show one extension point per block; read
[`HungerPlugin.kt`](src/main/kotlin/gups/sample/hunger/HungerPlugin.kt) for the full walkthrough.

---

## Building and Testing

### Build the Plugin Jar

```bash
./gradlew :samples:hunger-plugin:jar
```

The output jar is generated at:

```text
samples/hunger-plugin/build/libs/hunger.jar
```

### Pin the Plugin in a World

Copy the jar to `plugins/hunger.jar` in the course world repository and pin its checksum in `MODULE.gups`:

```bash
shasum -a 256 plugins/hunger.jar
```

```python
module(
    api_version = 1,
    vitals = ["hp", "mana"],
    plugins = {"hunger": "sha256:<the 64 hex digits shasum printed>"},
)
```

The plugin refuses a world with no `hp` vital or no damage type, so a world without them is skipped with a warning
(`plugin 'hunger': refused: ...`) instead of doing nothing. See
[Installing a plugin](../../docs/PLUGINS.md#installing-a-plugin) for how jars are loaded and verified, and the
[Feature-sized plugins](../../docs/PLUGINS.md#feature-sized-plugins) guide for the extension points.

### Use it from a hook

`hunger.eat` runs only from a hook or command. An item's `on_use` hook feeds the actor:

```python
def _feed(ctx):
    hunger.eat(ctx.actor, 3)
```

The `hunger` namespace exists only while the plugin loads, so a file that calls `hunger.eat` fails to load in a world
without it, even behind `ctx.has_feature("hunger")`. Use `has_feature` in shared code that only reads the plugin's
attributes:

```python
def _describe(ctx):
    if ctx.has_feature("hunger") and ctx.get_attr(ctx.actor, "hunger:hunger", 0) >= 5:
        ctx.send(ctx.actor, "Your stomach growls.")
```

### Run the Test Suite

The sample pins the built jar into a copy of the template world and drives it through the harness, covering
`ctx.has_feature`, the periodic work, the `on_death` reset and the `eat` builtin, plus a world without an `hp` vital
that refuses the plugin:

```bash
./gradlew :samples:hunger-plugin:test
```

---

## Essential Reference Pointers

* **Engine Architecture Specification:** See [`docs/SPEC.md`](../../docs/SPEC.md) §4.15 (*Plugins*) and §3.10
  (*Features*).
* **Plugin Developer Reference:** See [`docs/PLUGINS.md`](../../docs/PLUGINS.md) for the feature-sized plugin guide.
