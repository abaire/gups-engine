# 01. Traits & Effective Attributes

In the GUPS engine, a **trait** is a named, permanent bundle of stat bonuses, maximum vital increases, elemental
resistances, behavioral flags, and modifier functions. Traits represent intrinsic attributes of a character—such as
species biology or class training—rather than transient status conditions.

This tutorial explains how traits work, how they are declared and registered via `PluginTrait`, how the engine computes
effective character attributes, and how both players and NPCs carry traits.

---

## 1. Traits vs. Affects

Before diving into code, it is important to understand how traits differ from engine **affects** (status effects):

|   Dimension    |              Affects (`applyAffect`)               |                       Traits (`PluginTrait`)                       |
|----------------|----------------------------------------------------|--------------------------------------------------------------------|
| **Duration**   | Timed (pulses) or permanent until removed.         | Intrinsic and permanent unless explicitly changed.                 |
| **Dispel**     | Cleared by spells (e.g., `dispel`).                | Immune to dispel or cleansing magic.                               |
| **Origin**     | Spells, items, environment, poisons.               | Player creation choices, prototype declarations, or direct grants. |
| **Visibility** | Listed in `actor.affects` and GMCP `Char.Affects`. | Listed in `actor.traits`, `score`, and GMCP `Char.Traits`.         |
| **Modifiers**  | Evaluated late in the modifier chain.              | Evaluated early in the modifier chain (before affects).            |

---

## 2. Declaring Traits with `PluginTrait`

Traits are declared using the `PluginTrait` data class and registered in your plugin's `register(r: PluginRegistry)`
method via `r.addTrait()`.

A `PluginTrait` contains the following fields:

* **`name`:** A unique identifier matching `[a-z][a-z0-9_]*`. Must be globally unique across all plugins loaded into the
  world.
* **`label`:** A user-facing display name shown in `score`, `@stat`, and web client panels (e.g. `"Elf"`).
* **`doc`:** Documentation describing the trait, rendered in `plugins.html` and developer tools.
* **`statDeltas`:** A map of schema stat names to integer deltas (e.g. `mapOf("dex" to 2, "con" to -1)`).
* **`maxVitalDeltas`:** A map of schema vital names to integer maximum capacity deltas (e.g. `mapOf("mana" to 10)`).
* **`resistDeltas`:** A map of schema damage types to resistance percentage deltas (e.g. `mapOf("fire" to 10)`).
* **`flags`:** A set of boolean flag strings (e.g. `setOf("no_regen", "silenced")`). Note: `stunned` cannot be granted
  as a trait flag.
* **`modifiers`:** A map of `PluginModifierPoint` to modifier lambdas that run in the engine's modifier chain.

### Schema Validation at Registration

When `r.addTrait()` is called, the engine validates that every stat, vital, and damage type referenced in `statDeltas`,
`maxVitalDeltas`, and `resistDeltas` exists in the world's schema (`MODULE.gups`). If an invalid or misspelled name is
used, registration fails immediately with a descriptive "did you mean" suggestion.

---

## 3. Reference Traits in `HeritagePlugin.kt`

In [`HeritagePlugin.kt`](../../src/main/kotlin/gups/sample/heritage/HeritagePlugin.kt), six distinct traits are
registered to represent fantasy races and classes:

```kotlin
override fun register(r: PluginRegistry) {
  // ---- Race Traits ------------------------------------------------------------------------
  r.addTrait(
    PluginTrait(
      name = "human",
      label = "Human",
      doc = "Adaptable and well-rounded.",
      statDeltas = mapOf("str" to 1, "dex" to 1, "con" to 1, "int" to 1, "wis" to 1),
    )
  )

  r.addTrait(
    PluginTrait(
      name = "elf",
      label = "Elf",
      doc = "Nimble and magical, but fragile.",
      statDeltas = mapOf("dex" to 2, "con" to -1),
      maxVitalDeltas = mapOf("mana" to 10),
    )
  )

  r.addTrait(
    PluginTrait(
      name = "dwarf",
      label = "Dwarf",
      doc = "Tough, sturdy, and resistant to heat.",
      statDeltas = mapOf("con" to 2),
      maxVitalDeltas = mapOf("hp" to 5),
      resistDeltas = mapOf("fire" to 10),
    )
  )

  // ---- Class Traits -----------------------------------------------------------------------
  r.addTrait(
    PluginTrait(
      name = "warrior",
      label = "Warrior",
      doc = "Master of martial prowess.",
      statDeltas = mapOf("str" to 2),
      maxVitalDeltas = mapOf("hp" to 10),
    )
  )

  r.addTrait(
    PluginTrait(
      name = "mage",
      label = "Mage",
      doc = "Wielder of arcane mysteries.",
      statDeltas = mapOf("int" to 2),
      maxVitalDeltas = mapOf("mana" to 20),
    )
  )

  r.addTrait(
    PluginTrait(
      name = "cleric",
      label = "Cleric",
      doc = "Channeler of divine grace.",
      statDeltas = mapOf("wis" to 2),
      maxVitalDeltas = mapOf("mana" to 10),
      modifiers =
        mapOf(
          PluginModifierPoint.REGEN to
            PluginModifier { _, base ->
              @Suppress("UNCHECKED_CAST") val map = (base as Map<String, Long>).toMutableMap()
              map["mana"] = (map["mana"] ?: 0L) + 1L
              map
            }
        ),
    )
  )
}
```

---

## 4. Base Attributes vs. Effective Attributes

Understanding how traits modify an actor requires distinguishing between **base** and **effective** values:

### 1. Stats (`str`, `dex`, `int`, etc.)

* **Base Stat:** The persistent starting value established by character creation and level progression
  (`baseStat(name)`). Base stats never change when gaining or losing traits.
* **Effective Stat:** The actual value used in gameplay calculations (`stat(name)`). It is computed dynamically as:
  $$\text{Effective Stat} = \text{Base} + \text{Trait Deltas} + \text{Equipment Deltas} + \text{Affect Deltas} +
  \text{Encumbrance Deltas}$$

### 2. Vital Maxima (`hp`, `mana`, `stamina`)

* **Base Maximum:** The raw unaugmented capacity saved with the character (`baseMaxVital(name)`).
* **Effective Maximum:** The runtime capacity limit (`maxVital(name)`): $$\text{Effective Max} = \max(0, \text{Base Max}
  \+ \text{Trait Max Vital Deltas})$$
* **Vital Capping Rule:**
  * When an actor **gains** a trait that increases maximum HP or mana, their `current` vital remains unchanged (they do
    not instantly gain free healing).
  * When an actor **loses** a trait that decreases maximum capacity, their `current` vital is automatically clamped so
    it does not exceed the new effective maximum: $$\text{current} = \min(\text{current}, \text{Effective Max})$$
* **Every engine system** reads the effective maximum: regen ticks, healing caps, user prompts (`100/110hp`), GMCP
  status packages, and invariant assertions.

### 3. Elemental Resistances (`fire`, `cold`, `acid`, etc.)

* **Effective Resistance:** Calculated as $\text{Base Resist} + \text{Trait Resist Deltas}$.
* Passed directly into the combat rules function `rules.mitigate(ctx)` to reduce incoming elemental damage.

---

## 5. Trait Modifiers in the Modifier Chain

Traits can alter calculation pipelines such as combat accuracy (`TO_HIT`), strike damage (`DAMAGE`), turn order
(`INITIATIVE`), movement stamina (`MOVE_COST`), and tick regeneration (`REGEN`).

### Modifier Chain Execution Order

When a calculation runs through the engine's modifier chain, modifiers execute in strict layered order:

1. **Room Modifiers:** Environmental effects in the current location.
2. **Trait Modifiers:** Intrinsic modifiers from the actor's carried traits (evaluated in alphabetical trait name
   order).
3. **Actor Affect Modifiers:** Transient buffs or debuffs on the actor.
4. **Equipment Modifiers:** Enchantments on worn items.

In administrative tracing commands like `@trace`, trait modifiers appear with a distinct badge: `trait:<trait_name>`
(e.g. `trait:cleric`).

### The Cleric Mana Regeneration Modifier

Notice how the `cleric` trait in `HeritagePlugin.kt` hooks into `PluginModifierPoint.REGEN`:

```kotlin
modifiers = mapOf(
  PluginModifierPoint.REGEN to PluginModifier { _, base ->
    @Suppress("UNCHECKED_CAST")
    val map = (base as Map<String, Long>).toMutableMap()
    map["mana"] = (map["mana"] ?: 0L) + 1L
    map
  }
)
```

At every world tick, the engine passes the initial vital regeneration amounts into the chain. The cleric's trait
modifier adds `+1L` to the `mana` recovery map. If a modifier throws an unhandled exception or returns an invalid map,
the engine logs a diagnostic and keeps the previous value unchanged, ensuring the simulation never crashes.

---

## 6. How Actors Acquire Traits

An actor can acquire traits from three sources:

### 1. Player Option Traits

When a player completes character creation or respecifies their choices, their chosen options map to traits. For
example, selecting the `elf` race grants the `elf` trait, and selecting the `mage` class grants the `mage` trait. Option
traits are dynamically derived from the player's saved `choices` map (`choices["race"] == "elf"`).

### 2. Granted Traits

A trait can be awarded directly to a player or mob using the engine facade:

```kotlin
world.grantTrait(actor, "dwarf")
```

Granted traits are saved directly in the character's persistent payload (`grantedTraits: Set<String>`). They are useful
for permanent storyline curses, blessings, deity pacts, or titles. If a plugin defining a granted trait is later
uninstalled, the unknown trait identifier is safely preserved in the database without corrupting state.

### 3. Mob Prototype Traits

NPCs and monsters do not make character creation choices, but they can be assigned traits directly in Starlark package
declarations:

```python
mob(
    name = "guild_trainer",
    doc = "The venerable master of the arcane academy.",
    traits = ["mage"],
    level = 20,
)
```

Because traits are assigned at the prototype level, this guild trainer NPC automatically receives the `+2 int` and `+20
max mana` bonuses. More importantly, as we will explore in Tutorial 03, **operational restrictions check traits, not
player choices**—meaning this NPC can walk into mage-restricted guild halls while a warrior mob cannot!

---

## 7. In-Game Inspection

Players and builders can inspect traits through several mechanisms:

* **`score` command:** Lists the character's active choices and all acquired traits:

  ```text
  Race: Elf
  Class: Mage
  Traits: elf, mage
  ```
* **`@stat` command:** Staff tool displaying side-by-side base and effective attributes, the list of granted traits, and
  the provenance of every effective trait.
* **GMCP Telemetry:** The engine automatically publishes the `Char.Traits` state package whenever traits change:

  ```json
  {
    "traits": [
    {"name": "elf", "label": "Elf", "doc": "Nimble and magical, but fragile."},
    {"name": "mage", "label": "Mage", "doc": "Wielder of arcane mysteries."}
    ]
  }
  ```

---

## Summary

* **Traits** represent permanent, intrinsic attributes with stat, vital, resistance, flag, and modifier bonuses.
* Effective stats and vital maxima automatically factor in trait bonuses while leaving base values pristine.
* Trait modifier points hook directly into the engine's modifier chain (e.g. bonus mana regeneration).
* Both players (via creation choices and granted traits) and mobs (via prototype declarations) carry traits under
  unified rules.

In **[Tutorial 02: Character Creation Lifecycle & Providers](02_character_creation_provider.md)**, we will see how to
build the interactive login creation flow that lets players choose these traits.
