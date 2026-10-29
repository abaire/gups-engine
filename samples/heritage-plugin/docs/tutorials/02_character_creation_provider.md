# 02. Character Creation Lifecycle & Providers

In GUPS, character creation is driven by a single **Creation Provider**. The provider defines the sequence of choices offered to new characters, controls option availability based on previous answers, validates the resulting configuration, assigns a starting location, and applies initial equipment or skills.

This tutorial covers the `PluginCreation` interface, defining choices and options, implementing dynamic availability and validation, handling post-creation hooks, and how the creation experience translates across Telnet and the web client.

---

## 1. The Creation Provider Concept

A world may have **at most one** creation provider. It is registered on the `PluginRegistry` during the plugin's `register()` call:

```kotlin
r.setCreation(myCreationProvider)
```

If multiple plugins attempt to register a creation provider, or if the provider declares invalid option names, the engine rejects the registration with an atomic failure and logs a diagnostic.

If **no plugin** registers a creation provider, character creation proceeds directly after naming: players pick a name, password, and immediately spawn into the world.

---

## 2. Defining Choices and Options

Choices are declared as an ordered list of `PluginChoice` instances, each holding a list of `PluginOption` definitions.

In [`HeritagePlugin.kt`](../../src/main/kotlin/gups/sample/heritage/HeritagePlugin.kt), the companion object declares the race and class choices:

```kotlin
companion object {
  val RACE_OPTIONS =
    listOf(
      PluginOption(
        id = "human",
        label = "Human",
        summary = "Adaptable and well-rounded.",
        help = "Humans receive +1 to all attributes except armor.",
        traits = listOf("human"),
      ),
      PluginOption(
        id = "elf",
        label = "Elf",
        summary = "Graceful and attuned to magic.",
        help = "Elves receive +2 dexterity, -1 constitution, and +10 mana.",
        traits = listOf("elf"),
      ),
      PluginOption(
        id = "dwarf",
        label = "Dwarf",
        summary = "Sturdy folk with deep constitution.",
        help = "Dwarves receive +2 constitution, +5 hp, and +10 fire resistance.",
        traits = listOf("dwarf"),
      ),
    )

  val CLASS_OPTIONS =
    listOf(
      PluginOption(
        id = "warrior",
        label = "Warrior",
        summary = "Masters of weapons and armor.",
        help = "Warriors receive +2 strength and +10 hp.",
        traits = listOf("warrior"),
      ),
      PluginOption(
        id = "mage",
        label = "Mage",
        summary = "Scholars of arcane destruction.",
        help = "Mages receive +2 intelligence and +20 mana.",
        traits = listOf("mage"),
      ),
      PluginOption(
        id = "cleric",
        label = "Cleric",
        summary = "Holy servants of divine recovery.",
        help = "Clerics receive +2 wisdom, +10 mana, and bonus mana regeneration.",
        traits = listOf("cleric"),
      ),
    )
}
```

### Breakdown of Structure

* **`PluginChoice(id, title, options, prompt)`:**
  * `id`: Unique identifier matching `[a-z][a-z0-9_]*` (e.g. `"race"`, `"class"`).
  * `title`: User-facing title used in menus and `score` (e.g. `"Race"`).
  * `options`: List of candidate `PluginOption`s.
  * `prompt`: Optional custom prompt string (defaults to `"Choose your <title in lowercase>:"`).
* **`PluginOption(id, label, summary, help, traits)`:**
  * `id`: Identifier unique within this choice. **Reserved names prohibited:** cannot be `back`, `cancel`, `help`, `yes`, or `no`.
  * `label`: Capitalized display name (e.g. `"Elf"`).
  * `summary`: One-line summary shown alongside the option in creation menus.
  * `help`: Optional multi-line explanation printed when a player types `help <option>` or clicks for details.
  * `traits`: The list of trait names granted to the character when this option is selected.

---

## 3. Implementing the `PluginCreation` Interface

The `PluginCreation` interface requires implementing five methods:

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

---

## 4. Progressive Dynamic Availability

The `available` method is the core mechanism for conditional character options:

```kotlin
override fun available(
  world: PluginWorld,
  choice: String,
  answers: Map<String, String>,
): List<String>
```

As the player steps through the sequence, `available()` is queried with the answers selected so far.

### Key Rules:
1. **Filtering Incompatible Options:** In `HeritagePlugin`, when `answers["race"] == "dwarf"`, the available classes returned are only `listOf("warrior", "cleric")`. The `mage` option is omitted from the menu entirely.
2. **Skipping Choices Automatically:** If `available()` returns an empty list (`emptyList()`) for a given choice, the engine skips that choice completely without asking the player. For example, a `deity` choice can be configured to return options only if `answers["class"] == "cleric"`; other classes skip it transparently.
3. **Declaration Order:** The returned options are always presented in the declaration order established in `PluginChoice.options`. Any unrecognized option ID returned by `available()` is safely dropped with an engine diagnostic.

---

## 5. Whole-Set Validation

Before the character is finalized (or before changes are committed in-game), the engine calls `validate(world, answers)`:

* Returns `null` if the combination of answers is legal.
* Returns an error message string if the combination is refused.

```kotlin
override fun validate(world: PluginWorld, answers: Map<String, String>): String? =
  if (answers["race"] == "dwarf" && answers["class"] == "mage") {
    "Dwarves cannot be mages."
  } else null
```

While dynamic availability usually prevents invalid combinations in the interactive UI, `validate()` acts as an essential defense-in-depth gate, especially when options are modified in play or submitted through custom network scripts.

---

## 6. Dynamic Starting Rooms

The `startRoom(world, answers)` method allows plugins to spawn characters in different locations based on their heritage:

* Return a zone room label (e.g. `"//zones/forest:glade"` for Elves, `"//zones/mountain:keep"` for Dwarves).
* Return `null` to use the course world's default spawn room.
* If a returned room label fails to resolve, the engine logs a diagnostic and safely falls back to the default start room.

---

## 7. The `created()` Lifecycle Hook

Once choices are confirmed and the character entity is spawned into the world, the engine runs `created(world, actor, newCharacter)`:

```kotlin
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
```

### Initializing Starter Skills and Gear:
* **`newCharacter = true`:** Called when a player creates a brand new character. Here, `HeritagePlugin` uses `world.setSkill()` to grant starting spell proficiencies:
  * Mages receive `//skills:magic_missile` at proficiency 25.
  * Clerics receive `//skills:minor_heal` at proficiency 25.
  * Notice the graceful `try/catch`: if the course world has not implemented these skill prototypes, the plugin handles it cleanly without failing login.
* **`newCharacter = false`:** Called when an existing saved character logs in and completes *missing choices* (see below). Because they are an existing character, you typically avoid granting duplicate starter items or skills.

---

## 8. Player Experience Across Protocols

The engine automatically bridges `PluginCreation` across all communication protocols:

### 1. Telnet Login Experience
Over Telnet or raw socket connections, the engine formats choices into clear numbered lists:

```text
Choose your race:
  1. Human  - Adaptable and well-rounded.
  2. Elf    - Graceful and attuned to magic.
  3. Dwarf  - Sturdy folk with deep constitution.
[1-3, help <name>, back, cancel]: 
```

* **Flexible Input:** Players can select options by:
  * Number: `2`
  * Option identifier: `elf`
  * Option label: `Elf`
  * Unique prefix: `el`
* **Navigation Commands:**
  * `help <option>`: Prints the detailed `help` text for that option.
  * `back`: Steps backward to the previous choice, discarding later answers.
  * `cancel`: Aborts character creation and returns to the main menu.
  * Final confirmation prompt: `confirm` or `yes` to finalize.

### 2. Web Client GMCP Integration
For web and graphical MUD clients, the engine broadcasts the `Char.Creation` GMCP package:

```json
{
  "step": "class",
  "choice": "Class",
  "options": [
    {"id": "warrior", "label": "Warrior", "summary": "Masters of weapons and armor."},
    {"id": "cleric", "label": "Cleric", "summary": "Holy servants of divine recovery."}
  ],
  "answers": {"race": "dwarf"}
}
```

When character creation completes, the engine sends a single `Char.Creation: null` event to signal the client to close the creation UI and open the game viewport.

### 3. Handling Missing Choices for Saved Characters
What happens if you install `heritage-plugin` into a running world that already has existing player accounts, or if you add a new `deity` choice in a future update?

1. When a player logs in with a character lacking an answer for an available choice, the engine detects that the character is incomplete.
2. The player is told: `"Your character has choices to make before playing."`
3. The engine guides the player **only through the missing choices**, keeping all existing answers intact.
4. Once completed, `created(world, actor, newCharacter = false)` is called, and the player enters the world seamlessly.

---

## Summary

* `PluginCreation` provides an engine-driven, multi-protocol character creation experience.
* `available()` provides dynamic, step-by-step filtering of available options.
* `validate()` prevents illegal combinations of choices.
* `created()` initializes starter skills and inventory on newly spawned characters.
* Saved characters automatically prompted for missing choices when plugin definitions expand.

In **[Tutorial 03: Operational Restrictions & Content Tags](03_operational_restrictions_and_tags.md)**, we will see how to enforce equipment and movement restrictions using the traits granted by these choices.
