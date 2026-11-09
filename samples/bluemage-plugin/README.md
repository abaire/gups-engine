# Sample Plugin: Blue Mage

This directory contains a sample plugin for the GUPS engine in which **a character learns skills by watching mobs use
them**.

It is a small demo of three extension points that the other samples don't cover:

* **Trait callbacks:** the `blue_mage` trait registers a handler for `SKILL_RECOGNIZED`, which the engine fires for a
  player who recognizes a skill they don't know, on a cast that succeeded or was resisted. Unlike a trait's modifiers, a
  handler may change the world. It also handles `DAMAGED`: the first blow from each new mob attacker tells the blue mage
  "You study how ... fights.", remembered in the `bluemage:studied` attribute.
* **A plugin-declared rules export:** `addRulesExport("bluemage_learn_chance", ...)` lets a world tune the learning
  chance in its rules package. A world that doesn't define it gets the plugin's own formula.
* **Skill lookup:** `world.skill(label)` reads a loaded skill's name and tags.

The plugin is standalone: it needs the `skills` feature and nothing else.

## How it works

When a mob casts a skill, the engine decides per onlooker whether they recognize it: a player who knows the skill does,
and anyone else is asked the world's `recognizes_skill` rules export. A blue mage who recognizes a skill they don't know
gets a `SKILL_RECOGNIZED` call, and `onRecognized` runs these steps:

1. It stops unless the caster is a mob, the outcome is `success` or `resist`, the skill is tagged `blue`, and the blue
   mage doesn't already know it.
2. It computes a base chance:

   ```text
   base = 1
        + 10 if the blue mage was the target
        - 5 for each level the caster has above the blue mage
        - (power - 100) / 10
   ```
3. It asks the world's `bluemage_learn_chance` for the final percentage, passing `base` as `ctx.base`, and uses `base`
   itself when the world doesn't define it. The result is clamped to 0 to 33.
4. It rolls the chance with the world's RNG. On success it teaches the skill at proficiency 10, tells the blue mage "You
   grasp the secret of ...!", and appends the label to the `bluemage:learned` attribute. A `LEARN` restriction that
   refuses the skill (a class that can't use it, say) makes the plugin do nothing.

The `bluemagic` command lists what the character learned this way.

## What a world adds

**Tag a skill `blue`** so blue mages may learn it:

```python
skill("breath",
      kind = "spell",
      range = "near",
      target = "enemy",
      tags = ["blue"],
      effect = damage(dice = "2d6", type = DAMAGE.fire),
)
```

**Grant the trait** from content. `bluemage.awaken(actor)` returns `None`, or the reason it was refused:

```python
def _teach(ctx):
    if "blue" in ctx.text:
        bluemage.awaken(ctx.actor)

mob("sage", name = "a sage", desc = "A robed sage.", keywords = ["sage"], on_say = _teach)
```

**Optionally tune the chance** by defining the export in the rules package. `ctx.base` is the plugin's own value, and
`ctx.args` holds `power` and `was_target`:

```python
def bluemage_learn_chance(ctx):
    return ctx.base + (ctx.actor.int - 10)
```

Define it only in a world that pins this plugin: a rules export no loaded plugin declares is a load error.

Players only learn what they recognize, so the world's `recognizes_skill` decides how often a blue mage gets the chance
at all. The template world's version is based on Intelligence and Wisdom.

---

## Building and Testing

### Build the Plugin Jar

```bash
./gradlew :samples:bluemage-plugin:jar
```

The output jar is generated at:

```text
samples/bluemage-plugin/build/libs/bluemage.jar
```

### Pin the Plugin in a World

Copy the jar to `plugins/bluemage.jar` in the world and pin its checksum in `MODULE.gups`:

```bash
shasum -a 256 plugins/bluemage.jar
```

```python
module(
    api_version = 1,
    plugins = {"bluemage": "sha256:<the 64 hex digits shasum printed>"},
)
```

### Run the Tests

```bash
./gradlew :samples:bluemage-plugin:test
```

`BlueMagePluginTest` checks the gates and the formula against mock facades. `BlueMageWorldTest` loads the built jar into
a copy of the template world and runs a Starlark test in which a drake breathes on a blue mage.

The source is commented to show one extension point per block; read
[`BlueMagePlugin.kt`](src/main/kotlin/gups/sample/bluemage/BlueMagePlugin.kt).
