# 03. Operational Restrictions & Content Tags

In GUPS, **restrictions** are plugin-registered validation gates that can veto player or mob actions across four
critical lifecycle points: wearing equipment, casting spells, moving between rooms, and learning skills.

Instead of hardcoding rules into engine verbs, plugins declare restrictions that inspect the acting entity's **traits**
and the target's **tags**. This tutorial covers how restriction points work, why content tags are essential, how staff
roles can bypass restrictions, and how the engine handles vetoes and errors.

---

## 1. What is an Operational Restriction?

A restriction is registered on the `PluginRegistry` using `r.addRestriction()`:

```kotlin
r.addRestriction(
  point = PluginRestrictionPoint.WEAR,
  doc = "Mages cannot wear heavy armor.",
) { call: PluginRestrictionCall ->
  // Return null to ALLOW, or a refusal message string to VETO
}
```

The handler lambda receives a `PluginRestrictionCall` containing contextual metadata for the action:

* **`actor: PluginActor`:** The character attempting the action (player or mob).
* **`item: PluginItem?`:** The equipment item being donned or wielded (for `WEAR`).
* **`slot: String?`:** The equipment slot being filled (for `WEAR`).
* **`skill: PluginSkill?`:** The skill or spell being cast or learned (for `CAST` and `LEARN`).
* **`room: PluginRoom?`:** The target destination room (for `ENTER`).
* **`fromRoom: PluginRoom?`:** The room the character is leaving (for `ENTER`).
* **`direction: String?`:** Exit direction (e.g. `"north"`), or `null` for teleports (for `ENTER`).
* **`world: PluginWorld`:** The world facade for diagnostics and state queries.

### Veto Semantics

* **`null`:** The action is approved.
* **`String`:** The action is vetoed. The returned string is sent directly to the player as the explanation, and the
  action is cancelled **with zero lag applied**. For mobs, the action simply fails silently.

---

## 2. The Four Restriction Points

The engine defines four lifecycle checkpoints via the `PluginRestrictionPoint` enum:

|    Point    |                 Intercepted Operations                  |                               Typical Use Cases                                |
|-------------|---------------------------------------------------------|--------------------------------------------------------------------------------|
| **`WEAR`**  | `wear` and `wield` commands, before items move.         | Armor weight restrictions, weapon class proficiencies, alignment-attuned gear. |
| **`CAST`**  | Pre-warmup spellcasting checks (before casting begins). | Magic school affinities (arcane vs. divine), element specializations.          |
| **`ENTER`** | All room transitions that trigger `before_enter`.       | Guild hall access, level-gated zones, class chambers.                          |
| **`LEARN`** | Skill acquisition via `world.setSkill()`.               | Restricting trainer skill progression to matching classes.                     |

---

## 3. Golden Rule: Check Traits, Not Choices!

When writing restriction logic, **always inspect the actor's traits (`actor.hasTrait()`), never their choices
(`actor.choices`)**.

```kotlin
// BAD: Only works for players, ignores NPCs entirely!
if (actor.choices["class"] == "mage") { ... }

// GOOD: Works uniformly for players AND mob prototypes!
if (actor.hasTrait("mage")) { ... }
```

### Why This Matters:

1. **Mobs have no choices:** NPCs never participate in the character creation sequence, so `actor.choices` is always
   empty for mobs.
2. **Unified NPC Behavior:** If a course world creates an NPC trainer with `mob("archmage", traits = ["mage"])`, that
   NPC automatically shares the same permissions and restrictions as player mages.
3. **Decoupling:** Your restriction rules remain independent of however creation choices happen to be named or grouped.

---

## 4. Bridging Content with Tags

The engine core attaches no built-in semantic rules to item types or room names. Instead, **tags** serve as the contract
between Starlark world content and Kotlin plugin code:

### Tagging in Starlark:

```python
# In zones/items.star
item(
    name = "plate_cuirass",
    doc = "Heavy steel plate armor.",
    tags = ["heavy_armor"],
    wear = "body",
)

# In zones/rooms.star
room(
    name = "sanctum",
    title = "Sanctum of the Arcane",
    tags = ["guild_mage"],
)

# In rules/skills.star
skill(
    name = "magic_missile",
    doc = "Fires a bolt of force.",
    tags = ["arcane"],
)
```

In your Kotlin plugin, you simply inspect the `tags` property on `PluginItem`, `PluginRoom`, or `PluginSkill`.

---

## 5. Walkthrough of Restrictions in `HeritagePlugin.kt`

Let's examine how [`HeritagePlugin.kt`](../../src/main/kotlin/gups/sample/heritage/HeritagePlugin.kt) implements all
four points:

### 1. Armor Restriction (`WEAR`)

Mages are forbidden from donning heavy armor:

```kotlin
r.addRestriction(PluginRestrictionPoint.WEAR, "Mages cannot wear heavy armor.") { call ->
  val actor = call.actor
  val item = call.item
  if (actor.hasTrait("mage") && item != null && "heavy_armor" in item.tags) {
    "Your spellcraft can't work through that much metal."
  } else null
}
```

If an actor carrying the `mage` trait attempts to `wear plate_cuirass`, the item remains in their inventory, and they
are told: `"Your spellcraft can't work through that much metal."`

### 2. Magic School Restrictions (`CAST`)

Only mages can cast arcane spells, and only clerics can cast divine prayers:

```kotlin
r.addRestriction(
  PluginRestrictionPoint.CAST,
  "Arcane skills require the mage trait; divine skills require the cleric trait.",
) { call ->
  val actor = call.actor
  val skill = call.skill
  if (skill != null) {
    if ("arcane" in skill.tags && !actor.hasTrait("mage")) {
      "You don't know how to call on that power."
    } else if ("divine" in skill.tags && !actor.hasTrait("cleric")) {
      "You don't know how to call on that power."
    } else null
  } else null
}
```

This check runs before the spell warmup starts. If vetoed, the cast does not initiate, no mana is deducted, and no lag
is applied.

### 3. Guild Room Security & Staff Bypasses (`ENTER`)

Rooms tagged `guild_<class>` admit only characters carrying that class trait. However, world administrators and
instructors should not be locked out of rooms:

```kotlin
r.addRestriction(
  PluginRestrictionPoint.ENTER,
  "Guild rooms admit only actors with matching class trait or staff.",
) { call ->
  val actor = call.actor
  val room = call.room ?: return@addRestriction null

  // Staff roles bypass guild restrictions
  if (
    actor.role == PluginRole.BUILDER ||
      actor.role == PluginRole.INSTRUCTOR ||
      actor.role == PluginRole.ADMIN
  ) {
    return@addRestriction null
  }

  val classes = listOf("warrior" to "Warrior", "mage" to "Mage", "cleric" to "Cleric")
  for ((c, label) in classes) {
    if ("guild_$c" in room.tags && !actor.hasTrait(c)) {
      return@addRestriction "Only ${label}s may enter."
    }
  }
  null
}
```

#### Movement Behavior on Refusal:

* **Walking (`north`, `east`, etc.):** The player is stopped in their current room, shown the refusal message, and
  suffers **no lag penalty**.
* **Fleeing (`flee`):** If a fleeing character rolls an exit into a forbidden room, the flee fails, and normal flee
  recovery lag applies.
* **Mob Navigation:** Mobs attempting to wander into a restricted room simply fail their step and remain where they are.
  A wandering NPC with `traits = ["mage"]` passes through `guild_mage` rooms freely!
* **Teleportation (`teleport` / `@goto`):** The transition is blocked before the entity moves.

### 4. Skill Training Restrictions (`LEARN`)

Prevents characters from acquiring incompatible skills through plugin verbs or scripts:

```kotlin
r.addRestriction(
  PluginRestrictionPoint.LEARN,
  "Arcane skills require the mage trait; divine skills require the cleric trait.",
) { call ->
  val actor = call.actor
  val skill = call.skill
  if (skill != null) {
    if ("arcane" in skill.tags && !actor.hasTrait("mage")) {
      "You don't know how to call on that power."
    } else if ("divine" in skill.tags && !actor.hasTrait("cleric")) {
      "You don't know how to call on that power."
    } else null
  } else null
}
```

If a script or plugin calls `world.setSkill(actor, "//skills/heritage_skills:magic_missile", 25)` on a warrior, the call
is rejected with an `IllegalArgumentException` containing the refusal message.

---

## 6. Failure Semantics & Error Handling

To ensure server stability, the engine wraps every restriction check in an exception guard:

* **Fail-Open Policy:** If your restriction lambda throws an unhandled exception (e.g. `NullPointerException`), the
  engine catches it, logs a `"plugin"` diagnostic, and **allows the action**. This prevents buggy plugins from
  permanently trapping characters in rooms or bricking core movement.
* **Execution Order:** Multiple restrictions registered at the same point execute in plugin-id order, followed by
  registration order within each plugin. The **first restriction to return a non-null message wins**, and subsequent
  checks are skipped.

---

## Summary

* Restrictions intercept actions at `WEAR`, `CAST`, `ENTER`, and `LEARN`.
* Always test `actor.hasTrait(...)` so restrictions apply to both players and mob prototypes.
* Use content `tags` (`heavy_armor`, `guild_mage`, `arcane`) to associate world entities with plugin rules.
* Check `actor.role` to allow builders and instructors to bypass restrictions.
* Refusals cancel actions without lag; unhandled exceptions fail open and log diagnostics.

In **[Tutorial 04: Runtime Choice Changes, Builtins & Commands](04_runtime_choice_changes_and_builtins.md)**, we will
see what happens when choices change in play and how invalid equipment stops working.
