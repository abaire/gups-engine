# 12. Crafting: The `//lib:craft.star` Library

Crafting turns things a player carries into something new: ore into bars, bars into a dagger, a dull blade into a sharp
one. The engine knows nothing about recipes. It provides the pieces (timed tasks, skill rolls outside casts, item
renaming), and the template world's `//lib:craft.star` builds crafting from them, in the same shape as `//lib:shop.star`
(tutorial 08) and `//lib:trainer.star` (tutorial 09). The library ships the *mechanism*; your zones supply the recipes.

The tutorial zone's smithy, east of the shop, is the worked example: [`smithy.star`](../../zones/tutorial/smithy.star)
and [`smithing_recipes.star`](../../zones/tutorial/smithing_recipes.star). Load the library like the others:

```python
load("//lib:craft.star", "craft")
```

Players type `recipes` to see what they can make here, and `craft <recipe>` (or `craft <recipe> on <item>`) to make it.

---

## 1. Stations and `craft_recipes`

A **station** is anything that offers recipes: a room, an item on the ground, or an item the crafter carries (not worn,
and not inside a container). It offers them through the custom hook `craft_recipes` (tutorial 06, section 8), which
returns a list of recipes:

```python
load(":smithing_recipes.star", "ADVANCED", "BASIC")

def _forge_recipes(ctx):
    # A station may offer any number of recipe modules.
    return BASIC + ADVANCED

room("smithy", ..., hooks = {"craft_recipes": _forge_recipes, "craft_check": _forge_check})
```

The smithy has all three kinds: the room (its forge), a whetstone too heavy to carry that offers `hone`, and a repair
kit the player picks up that offers `patch`. `recipes` visits the room, then the ground, then the crafter's pack, and
lists each station's recipes under its name. When two stations offer the same recipe id, the first one wins; within one
station, a later recipe with the same id replaces an earlier one. Because recipes are plain data in module constants,
many stations can share one recipe module, and a plugin can add recipes to any station with `addHook("craft_recipes",
...)`.

## 2. Writing Recipes

A recipe says what it needs, how long it takes and what it gives:

```python
craft.recipe("forge_dagger",
    name = "an iron dagger", verb = "forge",
    inputs = [craft.need(_Z + "iron_bar", count = 2, lose = "50%")],
    tools = [craft.need(tag = "hammer")],
    skill = _SMITHING, difficulty = 10, time = 12, lag = 2,
    result = craft.make(_Z + "iron_dagger"),
)
```

* **`craft.need`** names exactly one of `label` (an absolute item label), `tag` or `kind`, with a `count`. Inputs are
  consumed; tools are only used. Needs match the crafter's carried items; a stack counts, and one item never serves two
  needs. A tag or kind need can take a `name` for how it reads in `recipes` ("pinch of grit"); a label need reads as its
  item's name.
* **`craft.make(label, count = 1)`** gives new items. **`craft.improve(...)`** changes an item the crafter holds
  instead, named after `on`: `craft hone on dagger`. Its `target` need says which items qualify. It can add a permanent
  item affect with stat, sense, vital or resist deltas, add and remove tags, swap the item for one of its `variants`,
  and refuse an item that already has its `unless_tag`.
* **`time`** is how many pulses the craft takes, and **`lag`** the recovery afterward. The craft's name in the engine's
  messages comes from **`doing`**, which defaults to the verb with `ing` (forge → forging), so `stop` says "You stop
  forging an iron dagger.".

Recipes are checked when the file loads: a relative label, a count of 0 or an `improve` recipe with no target is a load
error that names the function and argument.

## 3. Failure and Losses

A roll that fails consumes some inputs and makes nothing (or a flawed product). Each input's `lose` says how much:

| `lose`  |                   Loses                    |
|---------|--------------------------------------------|
| `2`     | exactly 2                                  |
| `"1d2"` | a dice roll                                |
| `"50%"` | that share of the need's count, rounded up |
| `"all"` | the whole count                            |
| `0`     | nothing                                    |

An input without `lose` uses the recipe's `failure.lose`, which defaults to `"50%"`. `craft.failure(make = ...)` gives a
flawed product (the smithy's bent dagger), and `message` replaces "You fail to forge an iron dagger.". A failed
`improve` leaves the target alone. A recipe with no skill never fails.

## 4. Skills, Difficulty and Quality Tiers

A recipe that names a `skill` rolls it with `ctx.skill_roll` when the craft completes. The template's rules give a
chance of proficiency + 20 (5 to 100) and a power of 50 + proficiency / 2 + 2 per level above the first. `difficulty` is
subtracted from the chance.

**Quality tiers** reward strong rolls. The highest tier whose `at` is at most the roll's power applies to the result:
its `name` renames the item (`ctx.set_item_name`), its deltas become a permanent item affect, and its `add_tags` are
added. A level 1 smith with smithing 50 has power 75 and forges "a fine iron dagger"; at level 11, power 95 makes "a
masterwork iron dagger"; with no training, power 50 makes a plain one. A recipe with no skill has power 100.

Every rolled craft reports a use of its skill (`ctx.report_skill_use`), so a world's skill-advancement hook sees
crafting the way it sees casting. The smith in the smithy teaches smithing with `//lib:trainer.star`. A craft skill is
never cast: the template's `cast_allowed` refuses `cast smithing`.

## 5. Tools and Charges

A tool need with `charges = n` matches only an item with at least `n` charges left (or unlimited ones), and each craft
spends `n`, whether it succeeds or fails. The repair kit's `patch` needs an awl with a charge; the awl has 5. At 0 it
stays in the pack but no longer matches, until something recharges it (`ctx.set_charges`).

## 6. The Station Hooks

The station that offered a recipe may hook into it:

```python
def _forge_check(ctx, crafter, recipe, target, adjust):
    if recipe not in ("forge_dagger", "temper_dagger"):
        return None
    if not ctx.get_attr(ctx.self, "forge_lit", False):
        return "The forge is cold. Stoke it first."
    return adjust + 10    # a roaring forge makes the work easier
```

* **`craft_check(ctx, crafter, recipe, target, adjust)`** runs once when a craft starts. `None` keeps the default, a
  string refuses with that message, and an int replaces `adjust` (minus the recipe's difficulty).
* **`craft_done(ctx, crafter, recipe, outcome, items)`** runs after the craft resolves, with `outcome` `"success"` or
  `"fail"` and the items made or improved. Its result is ignored.

Plugin handlers for either hook join in, as for any custom hook.

## 7. Learned Recipes

`learned = True` hides a recipe from everyone who hasn't been taught it. `craft.teach(ctx, actor, recipe_id)` teaches it
and `craft.knows(ctx, actor, recipe_id)` asks. The smithy's tempering notes teach `temper_dagger` when used:

```python
def _read_notes(ctx):
    if craft.teach(ctx, ctx.actor, "temper_dagger"):
        ctx.send(ctx.actor, "You study the notes and learn how to temper a dagger.")
```

## 8. GMCP

`recipes` sends `Craft.Recipes`: every recipe in reach with its station, skill, time, whether it is ready, and each
need's name, count, how many the crafter has and what a failure loses. A finished or stopped craft sends `Craft.Result`
with the outcome (`"success"`, `"fail"` or `"stopped"`), the quality tier reached, the items made and what was lost.
Neither is sent to mobs, which may craft too, through `ctx.act(mob, "craft ...")`.

## 9. Testing a Recipe

A craft runs over pulses, so `p.do("craft ...")` returns once it is done, with every message. Fix the roll with
`w.force_rng` and state the expected chance and power next to the assertion:

```python
def test_forge_a_fine_dagger(t):
    w = t.world(seed = 1)
    p = w.spawn_player("Tess", at = ":smithy")
    w.set_skill(p, "//skills/crafts:smithing", 50)
    p.do("get ore")
    p.do("get charcoal")
    p.do("get hammer")
    p.do("craft smelt")
    p.do("craft smelt")
    p.do("stoke")
    w.force_rng(ints = [70])   # chance 50 + 20 - 10 + 10 = 70; power 75
    t.contains(p.do("craft forge dagger"), "You forge a fine iron dagger.")
```

[`smithy_test.star`](../../zones/tutorial/smithy_test.star) walks the whole smithy, and
[`craft_test.star`](../../zones/tutorial/craft_test.star) covers the library itself.

## 10. Under the Hood

The library uses only verbs any script can call:

* **`ctx.start_task`** (tutorial 06) keeps the crafter busy for the recipe's `time`. `stop`, a stun or dying cancels the
  craft, and nothing is consumed: the library only records which items it will use, and checks they are still there when
  the task completes.
* **`ctx.skill_roll`** rolls a skill outside a cast, with an `event` (`"craft"`) that `skill_check` can read and an
  `adjust` for difficulty.
* **`ctx.set_item_name`** renames an item for a quality tier; **`ctx.item_info`** reads an item prototype's name without
  spawning one, which is how `recipes` names what you're missing.

---

## Reference Links

* **Reference Implementation:** [`template-world/lib/craft.star`](../../lib/craft.star), and the smithy in
  [`zones/tutorial/smithy.star`](../../zones/tutorial/smithy.star) and
  [`zones/tutorial/smithing_recipes.star`](../../zones/tutorial/smithing_recipes.star)
* **Skills package:** [`template-world/skills/crafts/`](../../skills/crafts)
* **Timed tasks:** tutorial 06, section 7; **Trainers:** tutorial 09; **Custom Hooks:** tutorial 06, section 8
* **Specification:** [SPEC.md §4.9: Commands and Global Commands](../../../docs/SPEC.md)
