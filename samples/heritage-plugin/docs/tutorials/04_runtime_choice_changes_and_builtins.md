# 04. Runtime Choice Changes, Builtins & Commands

While character creation choices are established at login, many game worlds feature mechanics that allow characters to
change their race or class later in play—such as visiting an ancient rebirth shrine, training with a class mentor, or
completing a prestige quest.

This tutorial explores how plugins modify choices and traits at runtime, the cascading state updates triggered by a
change (including automatic equipment de-equipping), how to expose builtins to Starlark scripts, and how to implement
player commands.

---

## 1. The Runtime Modification Philosophy

The GUPS engine follows a strict separation of concerns regarding in-game choice modifications:

* **The Engine Provides the Mechanism:** The engine exposes atomic mutation verbs on `PluginWorld` (`setChoices`,
  `grantTrait`, `removeTrait`) and guarantees all game invariants are maintained.
* **The Plugin/Content Provides the Story:** The engine provides **no default player command** for respecs (such as
  `respec` or `change_race`). Worlds decide whether choice changes exist, whether they cost gold, require quests, or
  happen through specific NPC interactions.

---

## 2. What Happens During `world.setChoices()`

When a plugin calls `world.setChoices(actor, choicesMap)`, the engine executes a carefully ordered sequence of
validation and state updates:

```text
┌─────────────────────────────────────────────────────────────────┐
│                    world.setChoices(actor, ...)                 │
└────────────────────────────────┬────────────────────────────────┘
                                 │
                                 ▼
   1. Progressive Availability & Whole-Set Validation
      - Checks every new answer against available()
      - Runs validate() on the candidate answers
      - REFUSAL? Aborts immediately with error message.
                                 │
                                 ▼
   2. Trait Re-derivation & Attribute Recalculation
      - Recalculates option traits from new answers
      - Updates effective stats, resists, and flags
      - Computes new effective vital maxima
                                 │
                                 ▼
   3. Vital Capacity Clamping
      - current = min(current, newEffectiveMax)
                                 │
                                 ▼
   4. Automatic Equipment Re-Validation
      - Runs WEAR restrictions on all currently worn items
      - Incompatible items stay worn but become inactive
      - Sends player refusal explanation for each one
                                 │
                                 ▼
   5. Immediate State Capture
      - Persists character payload to storage immediately
```

### Key Behavioral Details:

1. **Atomic Evaluation:** If `validate()` refuses the combination, **no state changes**. The character keeps their
   existing choices and traits.
2. **Current Vital Clamping:** If a player switches from a Warrior (+10 Max HP) to a fragile Mage, their maximum HP
   drops. If their current HP was 105 out of 110, their current HP is immediately clamped to the new maximum (100).
3. **Inactive Equipment:** If a Warrior wearing heavy plate armor changes class to a Mage, the engine re-evaluates their
   worn gear against the `WEAR` restrictions. Because mages cannot wear heavy armor, the plate armor stays worn but
   becomes inactive: it adds nothing (no armor, no affects) and shows as `(inactive)` in `equipment`. The player is
   notified: `"Your spellcraft can't work through that much metal."` Nothing is taken off, so a cursed item stays put.
   The armor works again if a later change makes the restriction allow it.
4. **Base Stats and Skills Remain:** `rules.starting_stats` is **not** re-run during a change. Base attributes and
   previously learned skills remain untouched.

---

## 3. Clearing Choices & Triggering Login Re-Picks

A plugin can set an option value to `null` to clear a choice:

```kotlin
world.setChoices(actor, mapOf("class" to null))
```

When an option is cleared, that choice becomes **missing**.

The next time that player connects, the engine detects that the character is incomplete and automatically guides them
through the creation prompts for that missing choice! This allows course worlds to build respec fountains without
writing custom interactive menus—simply clear the choice and prompt the player to reconnect.

---

## 4. Exposing the Starlark Builtin: `heritage.choose`

To allow Starlark zone scripts and quest NPCs to trigger choice changes,
[`HeritagePlugin.kt`](../../src/main/kotlin/gups/sample/heritage/HeritagePlugin.kt) registers a builtin using
`r.addBuiltin()`:

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

### Calling from Starlark Scripts:

In a course world quest script or NPC interaction hook:

```python
# In zones/sanctum/trainer.star
def on_talk(ctx):
    if ctx.actor.choices.get("class") != "mage":
        refusal = heritage.choose(ctx.actor, "class", "mage")
        if refusal:
            ctx.send(ctx.actor, "The archmage frowns: " + refusal)
        else:
            ctx.send(ctx.actor, "The archmage initiates you into the arcane mysteries!")
```

If a dwarf attempts to become a mage, `heritage.choose` returns `"Dwarves cannot be mages."`, and the script prints the
archmage's refusal. If a human warrior attempts it, `heritage.choose` returns `None`, their class switches to Mage,
their traits update, and any worn heavy armor stays on but stops working.

---

## 5. Adding the `heritage` Player Command

To let players inspect their current race, class, and summaries at any time, `HeritagePlugin` registers a player
command:

```kotlin
r.addCommand(
  name = "heritage",
  lane = PluginLane.FAST,
  role = PluginRole.PLAYER,
  doc = "Displays your race and class.",
) { call ->
  val actor = call.actor
  val race = actor.choices["race"]
  val cls = actor.choices["class"]
  val lines = mutableListOf<String>()

  if (race != null) {
    val opt = RACE_OPTIONS.find { it.id == race }
    lines += "Race: ${opt?.label ?: race} - ${opt?.summary ?: ""}"
  }
  if (cls != null) {
    val opt = CLASS_OPTIONS.find { it.id == cls }
    lines += "Class: ${opt?.label ?: cls} - ${opt?.summary ?: ""}"
  }

  if (lines.isEmpty()) {
    call.world.send(actor, "You have not chosen a race or class.")
  } else {
    call.world.send(actor, lines.joinToString("\n"))
  }
}
```

### Command Characteristics:

* **`PluginLane.FAST`:** Executes immediately on the world thread without enqueuing on the actor's action queue.
* **`PluginRole.PLAYER`:** Accessible to all authenticated players.
* **Output:**

  ```text
  Race: Elf - Graceful and attuned to magic.
  Class: Mage - Scholars of arcane destruction.
  ```

---

## 6. Inspecting Choices and Traits in the Engine

Beyond plugin commands, the engine natively displays choices and traits across multiple standard tools:

### In `score`:

```text
Name: Valeros
Level: 1
Race: Human
Class: Warrior
Traits: human, warrior
HP: 110/110   Mana: 100/100   Stamina: 100/100
```

### In `@stat` (Administrative Tool):

Staff members inspecting an actor see side-by-side base and effective attributes, followed by choice provenance:

```text
Choices:
  race: human
  class: warrior
Traits:
  human (from race:human)
  warrior (from class:warrior)
```

### In GMCP Telemetry:

Whenever `setChoices`, `grantTrait`, or `removeTrait` executes, the engine pushes a fresh `Char.Traits` payload to
connected web clients, keeping user interfaces perfectly synchronized with server state.

---

## Summary

* `world.setChoices()` validates candidate answers, re-derives traits, clamps current vitals, and automatically
  un-equips invalid gear.
* Setting an option to `null` marks that choice as missing, prompting the player to re-answer it on their next login.
* Builtins like `heritage.choose` allow zone scripts and quest NPCs to trigger choice changes safely.
* Commands like `heritage` and engine features like `score` and GMCP ensure players always know their character's
  heritage and traits.

In **[Tutorial 05: Testing Character Creation & Traits](05_testing_creation_and_traits.md)**, we will explore unit
testing and harness integration testing for character creation plugins.
