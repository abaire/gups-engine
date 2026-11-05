# 09. Trainers: The `//lib:trainer.star` Library

Skills are a player's learned abilities, from a spell to a craft. The engine stores a skill on an actor as a label and a
proficiency from 0 to 100, and the `skills` feature (tutorial 03) provides the verbs and the `skills` command that show
them. How a player *acquires* a skill, though, is up to your world. The template world ships `//lib:trainer.star`, a
library in the same shape as `//lib:shop.star` (tutorial 08) that turns any mob into a **trainer**: a keeper who teaches
a fixed list of skills in exchange for **practice sessions**.

Practice sessions are earned by changing level and spent with `practice <skill>`. The tutorial zone's mentor stands in
the arena and teaches the arcane spells `spark` and `mend`. Load the library the same way you load shops:

```python
load("//lib:trainer.star", "trainer")
```

---

## 1. What Skills Are

A skill is declared with `skill(...)` (tutorial 03), either in a `skills` package or in any zone that needs it. The
`skills` command lists the skills an actor knows, and the `Char.Skills` GMCP package gives a client the same list. A
trainer does not create skills; it raises the proficiency an actor already has, or sets one for the first time. The
proficiency an actor holds is what the rules read when they score a cast (tutorial 03, section 3): a higher proficiency
means a better chance to get the skill off and a stronger effect.

---

## 2. Defining a Trainer

A trainer is described by one `trainer.define(...)` call, kept in a module constant. The keeper's `on_spawn` hands the
constant to `trainer.open`, which marks the keeper as teaching it:

```python
MENTOR = trainer.define(
    id = "mentor",
    teaches = [
        trainer.teach("//skills/arcane:spark", up_to = 50, gain = 10),
        trainer.teach("//skills/arcane:mend", up_to = 40, gain = 10),
    ],
)

def _mentor_open(ctx):
    trainer.open(ctx, ctx.self, MENTOR)

mob("mentor",
    name = "the mentor",
    desc = "A robed figure who hums to themselves.",
    behaviors = [sentinel()],
    skills = {"//skills/arcane:spark": 100, "//skills/arcane:mend": 100},
    on_spawn = _mentor_open,
)
```

* **Teach lines:** `trainer.teach(label, up_to, gain, start=None)` describes one skill. `label` is an absolute skill
  label (the trainer sets the skill with `ctx.set_skill`, so the skill must be a loaded one). `up_to` is the highest
  proficiency the trainer will raise it to, from 1 to 100. `gain` is the proficiency added per session. `start` is the
  proficiency a skill is set to when the player doesn't know it yet; when it is `None`, the line's `gain` is used
  instead.
* **The keeper knows the skills it teaches.** Giving the keeper `skills = {...}` at full proficiency does two things: it
  makes the keeper a credible teacher in play, and it is what loads the skills package, so the labels resolve. A trainer
  that names a skill no loaded package provides simply can't teach it (the line is left out and a note is printed).
* **The cap** a student can reach is the lower of the line's `up_to` and 100, and a plugin's `learn` restriction (below)
  may refuse anything below it.

---

## 3. Practice Sessions

A player's practice balance is a single number, kept in the library's own attribute namespace on the player, and it
starts at 0. It changes in two ways:

* **Levels grant and take sessions.** The library registers a global `on_level_change` hook (tutorial 06) that adds
  `PRACTICES_PER_LEVEL` (2) for every level gained and subtracts it for every level lost. The balance may go negative,
  so losing and regaining a level nets to nothing and a player can't die to farm sessions.
* **`trainer.grant_practices(ctx, actor, n)`** adds `n` (which may be negative) directly. An admin, a quest reward or a
  timed event can use it to hand out extra sessions.

In the keeper's room a player types `practice`:

```text
> practice
You have 2 practice sessions.
the mentor teaches:
  spark: not learned, up to 50%
  mend: not learned, up to 40%
```

`practice` alone shows the balance and what the trainer here teaches. To spend a session the player names the skill,
matched against the trainer's lines by a prefix of the skill's name:

```text
> practice spark
You learn spark from the mentor. (10%)
```

A skill the player doesn't know is set to the line's `start` (or `gain`); a known skill is raised by `gain`, never past
`up_to`. At the cap the trainer refuses ("you've learned all the mentor can teach you about spark.") and spends nothing.
A refused session, whether from the cap or a `learn` restriction, changes nothing. Onlookers see "Tess practices with
the mentor." A trainer that is incapacitated, stunned or fighting doesn't teach, the same way a shopkeeper doesn't
trade.

---

## 4. The `practice_offer` Hook

The keeper's offer is a suggestion. A keeper can declare a custom hook `practice_offer` (tutorial 06, section 8) that
the library asks before it settles a session. The hook receives the customer, the skill's label, the customer's current
proficiency (`None` for a new skill), the library's default `gain` and `cost`, and a refusal message. Returning a
**string** refuses the session with that message, returning a **dict** may replace `gain` and/or `cost` (ints, with
`gain` at least 1 and `cost` at least 0), and returning **None** keeps the defaults.

The tutorial mentor charges two sessions once a student knows a skill well, to push them toward a new one:

```python
def _mentor_offer(ctx, customer, skill, current, gain, cost, refusal):
    if current != None and current > 30:
        return {"cost": 2}
    return None
```

and the mob carries `hooks = {"practice_offer": _mentor_offer}`. Plugins join the same hook: a plugin registers a
handler for the name `practice_offer` with `PluginRegistry.addHook` (tutorial 06, section 8), and the library's
`ctx.call_hook` runs the keeper's hook first and then every plugin handler, each able to change the gain, the cost or
the refusal.

### Class Caps with `learn`

The library sets a skill with `ctx.set_skill`, which runs the `learn` restrictions (§4.19) on every set, including a
raise of a skill the player already knows. A class plugin uses this to cap proficiency: a warrior who knows more than 30
in a spell is refused the session and spends nothing. The plugin registers a `learn` restriction that returns a message
when the skill or the target proficiency is out of bounds for the actor's class, and the trainer's session is refused
with that message.

---

## 5. Testing a Trainer

The tutorial zone keeps a private trainer and rooms in a test-only fixture (tutorial 07, section 6) that
`trainer_test.star` drives. The real mentor is tested in `tutorial_test.star`. A session is bought with a level, which
grants sessions, then spent with `practice`:

```python
def test_the_mentor_teaches_spark(t):
    w = _world(t)
    p = w.spawn_player("Tess", at = ":arena")
    w.award_xp(p, 1000)   # one level: +2 practice sessions
    t.contains(p.do("practice spark"), "You learn spark from the mentor. (10%)")
    t.eq(p.skills["//skills/arcane:spark"], 10)
    t.contains(p.do("skills"), "spark")
```

`w.award_xp` raises the player a level (auto mode), the library's global hook grants the sessions, and `practice spark`
spends one to learn the skill. The test then checks the proficiency and that the `skills` command lists it.

---

## Reference Links

* **Reference Implementation:** [`template-world/lib/trainer.star`](../../lib/trainer.star), and the mentor in
  [`zones/tutorial/mobs.star`](../../zones/tutorial/mobs.star)
* **Skills package:** [`template-world/skills/arcane/`](../../skills/arcane)
* **Custom Hooks:** tutorial 06, section 8
* **Specification:** [SPEC.md §4.9: Commands and Global Commands](../../../docs/SPEC.md)
