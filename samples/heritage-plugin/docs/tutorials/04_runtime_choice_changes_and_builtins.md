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

When an option is cleared, that choice becomes **missing**. The next time that player connects, the engine detects that
the character is incomplete and guides them through the creation prompts for that missing choice. `created(world, actor,
newCharacter = false)` then runs, so `HeritageCreation` grants the new class's starter skills.

This still works, but it makes the player log out to change. For a change in the middle of a session, with a price and a
menu, build a shrine instead (next section): the player stays in the world and the change takes effect at once.

---

## 4. A Respec Shrine

The sample ships a working shrine in its [`world/`](../../world) tree: a Starlark library,
[`lib/respec.star`](../../world/lib/respec.star), and a zone, [`//zones/shrine`](../../world/zones/shrine/shrine.star),
with a guild mentor who changes class and a rebirth altar that remakes race and class. A player walks up to a keeper and
types `respec` to see what can change and at what price, then `respec <option>` to change.

### The split between plugin and library

The engine stays mechanism-only: it sets no price, cooldown or skill policy, and never re-runs creation steps. The rest
splits by who knows what:

* **The heritage plugin owns the choices.** Only it knows the options, their labels, `available()` and `validate()`. Its
  builtins turn the player's words into option ids, say why an option is closed, apply the change and grant the class's
  starter skills.
* **`respec.star` owns world policy:** which choices a shrine changes, the price and currency, the cooldown, what
  happens to skills, the keeper's state, and every line players read.

A respec is a class change, not a fresh character. Base stats, base maxima and `rules.starting_stats` are never touched.
Trait modifiers and deltas follow the new traits, worn equipment is re-checked (section 2), skills follow the shrine's
policy, and standing follows an event and a hook.

### The four builtins

[`RespecBuiltins.kt`](../../src/main/kotlin/gups/sample/heritage/RespecBuiltins.kt) registers them over a `Respec`
helper that reads only the creation provider. `choices` limits a shrine to the listed choice ids; `allow_same = True`
lets the current option be picked again.

|                               Builtin                               |                                           Returns                                            |
|---------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| `heritage.respec_menu(actor, choices, allow_same = False)`          | list of `{"choice", "title", "current", "options": [{"id", "label", "summary", "refusal"}]}` |
| `heritage.respec_check(actor, phrase, choices, allow_same = False)` | `{"choice", "option", "label", "refusal"}`; pure, changes nothing                            |
| `heritage.respec(actor, choice, option, allow_same = False)`        | refusal string or `None`; re-checks, then `setChoices`                                       |
| `heritage.starter_skills(actor)`                                    | list of skill labels it granted                                                              |

`respec_check` matches case-insensitively. A leading word that names exactly one choice scopes the rest (`respec class
mage`, `respec cl mage`). A prefix of an id or label works (`respec mag`), but an exact match beats prefixes. Two
candidates ask which: if class also offered an Elementalist, `respec el` would answer `"Which do you mean: Elf (race),
Elementalist (class)?"`. A refusal is one of:

* `"That is already your class."` (unless `allow_same`)
* `"Mage isn't open to you."` (`available()` doesn't list it)
* `"That would leave your class (Mage) unavailable."` (an elf mage turning dwarf)
* whatever `validate()` says

`heritage.choose(actor, choice, option)` is still there for quest scripts that change a choice outright, with no menu or
price.

### Defining a shrine

A keeper serves a definition from its `on_spawn`, as a trainer does:

```python
load("//lib:respec.star", "respec")

MENTOR = respec.define(id = "mentor", choices = ["class"], cost = 10)

def _mentor_open(ctx):
    respec.open(ctx, ctx.self, MENTOR)
```

Every `define` parameter except `id` and `choices` (`skills`, `cost`, `currency`, `cooldown_pulses`) falls back to a
constant at the top of `respec.star`. Those constants are the world-wide defaults: edit them once and every shrine that
doesn't say otherwise follows.

```python
DEFAULT_SKILLS = "keep"          # "keep" or "reset"
DEFAULT_COST = 0
DEFAULT_CURRENCY = "//lib:gold_coin"
DEFAULT_COOLDOWN_PULSES = 0
```

The cooldown is one attribute per player, shared by every shrine, so players can't hop between keepers.

### The menu and the request

`respec` alone lists, for each choice the shrine changes, every option with its price or the reason it is closed:

```text
the guild mentor can change your class (now Warrior):
  Warrior: Masters of weapons and armor. (That is already your class.)
  Mage - 10 gold coins: Scholars of arcane destruction.
  Cleric - 10 gold coins: Holy servants of divine recovery.
You can change now.
```

`respec <words>` runs as one tick-lane command on the world thread, so nothing interleaves. In order:

1. Find the keeper: none here, incapacitated or stunned, or fighting refuses.
2. `heritage.respec_check`: the player's words become one option, or a refusal.
3. `respec_offer` with `action = "respec"`: the keeper may refuse or change the terms.
4. The cooldown, read from the stored ready pulse.
5. Under `keep`, the current option is refused.
6. The coin count: `"You can't afford that."`
7. `heritage.respec`, which re-checks and calls `setChoices`.
8. The skill policy.
9. `heritage.starter_skills`.
10. Pay the keeper, then stamp the cooldown.
11. The messages, the room echo, the standing event, and `respec_done`.

Coins are counted before the change and taken only after it succeeds. Nothing is paid until nothing can fail, so the
library needs no refund path. The cooldown check reads the stored pulse, so a `cooldown_pulses` the hook returns only
sets the wait after this change.

### `keep` against `reset`

* **`keep`** leaves every skill. A mage turned warrior still knows magic missile, but heritage's `CAST` and `LEARN`
  restrictions make it unusable until they change back, with their progress intact. The current option is refused.
* **`reset`** is a fresh start: the current option is allowed. The library gives back the practice sessions the trainer
  recorded, forgets every skill, then grants the starter skills:

```text
You pay 100 gold coins.
Your class is now Cleric.
You forget everything you had learned.
2 practice sessions are returned to you.
You learn minor_heal.
```

The refund comes from `//lib:trainer.star`, which records the sessions each skill cost; `trainer.refund(ctx, actor,
skill=None)` gives them back and clears the record. Skills from use, starter grants or direct `set_skill` calls have no
record and refund nothing.

### The keeper's hooks

```python
def respec_offer(ctx, action, customer, choice, old, new, cost, currency, cooldown_pulses, skills): ...
def respec_done(ctx, customer, choice, old, new, skills, cost): ...
```

* **`respec_offer`** is asked once per option the menu shows that the plugin leaves open (`action = "list"`) and once
  per request (`action = "respec"`). A string refuses; a dict may override `cost`, `cooldown_pulses` and `skills`;
  `None` keeps the terms. A bad value is printed and ignored. The guild mentor gives clerics a fifth off with `{"cost":
  cost * 8 // 10}`.
* **`respec_done`** runs after a change has been made and paid for. Its return value is ignored.

Plugin handlers for either name join through `ctx.call_hook`. The rebirth altar prices by standing:

```python
REBIRTH = respec.define(id = "rebirth", choices = ["race", "class"], skills = "reset", cost = 100,
                        cooldown_pulses = 14400)

def altar_offer(ctx, action, customer, choice, old, new, cost, currency, cooldown_pulses, skills):
    if not ctx.has_feature("standing"):
        return None
    d = ctx.disposition(ctx.self, customer)
    if d <= -50:
        return "The priestess turns her back on you."
    if d < 0:
        return {"cost": cost * 5}
    return None

def altar_done(ctx, customer, choice, old, new, skills, cost):
    ctx.adjust_standing(ctx.self, customer, 10)   # she warms to those she remakes
```

### The `"respec"` standing event

After paying, in a world with the `standing` feature, the library raises the world standing event `"respec"` on the
keeper (`ctx.actor`, the one affected) with the customer as `ctx.target` and `amount` the coins paid. World events have
no default deltas, so nothing changes unless the world's `standing_change` handles it. To treat a paid respec like a
donation to the keeper's factions, add a branch to `rules/standing.star`:

```python
if ctx.event == "respec":
    deltas = []
    for f in ctx.factions_of(ctx.actor):
        gain = _faction_gain(ctx, f, ctx.target, ctx.amount // 10)
        if gain > 0:
            deltas.append(standing_delta(f, ctx.target, gain))
    return deltas
```

Class-specific standing effects (a cleric's god frowning on a deserter, say) belong in `respec_done`, which knows the
old and new options.

### Using the shrine in a world

1. Build the jar: `./gradlew :samples:heritage-plugin:jar`.
2. Copy the sample's `world/` tree over your world's root, so `lib/respec.star` joins your `//lib` package and the
   `//skills/heritage_skills` and `//zones/shrine` packages appear.
3. Copy `build/libs/heritage.jar` into the world's `plugins/` directory and pin it in `MODULE.gups`: `plugins =
   {"heritage": "sha256:<hash>"}`, with the hash from `shasum -a 256 plugins/heritage.jar`.
4. Run the shrine's tests: `gups test --world <world> //zones/shrine`.

The starter skills live in `//skills/heritage_skills` rather than `//skills/heritage`: a package's attribute namespace
is its last path segment, and `heritage` is the plugin's own.

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

* `world.setChoices()` validates candidate answers, re-derives traits, clamps current vitals, and makes refused gear
  inactive.
* Setting an option to `null` marks that choice as missing, prompting the player to re-answer it on their next login.
* A respec shrine splits the work: the plugin's `respec_menu`, `respec_check`, `respec` and `starter_skills` builtins
  own the choices, and `respec.star` owns price, cooldown, skill policy and every message.
* `heritage.choose` lets quest scripts change a choice outright.
* Commands like `heritage` and engine features like `score` and GMCP ensure players always know their character's
  heritage and traits.

In **[Tutorial 05: Testing Character Creation & Traits](05_testing_creation_and_traits.md)**, we will explore unit
testing and harness integration testing for character creation plugins.

---

## Reference Links

* [`docs/SPEC.md`](../../../../docs/SPEC.md) §4.19, *Changing choices later*, and §4.9, the `//lib:trainer.star` ledger.
* [`docs/PLUGINS.md`](../../../../docs/PLUGINS.md), the respec builtins.
* [`Respec.kt`](../../src/main/kotlin/gups/sample/heritage/Respec.kt) and
  [`RespecTest.kt`](../../src/test/kotlin/gups/sample/heritage/RespecTest.kt): matching and refusals.
* [`world/lib/respec.star`](../../world/lib/respec.star): the library, with its hooks in the header comment.
* [`world/zones/shrine/respec_test.star`](../../world/zones/shrine/respec_test.star): every message the shrine sends, in
  a passing test.
