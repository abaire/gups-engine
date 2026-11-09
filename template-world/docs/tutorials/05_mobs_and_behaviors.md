# 05. Mobs, Spawns, and Behaviors

Mobs (non-player characters, monsters, vendors, quest-givers) bring your world to life. This guide explains how mobs are
declared, how built-in behaviors automate basic AI, how custom think routines work, and how rooms spawn and restock
mobs.

---

## 1. Declaring a Mob

Mobs are defined using the `mob()` builtin:

```python
# zones/tutorial/mobs.star
mob("guide",
    name="the guide",
    desc="A patient old adventurer with a lantern.",
    level=10,
    vitals=vitals(hp=200),
    senses={"sound": 1},
    behaviors=[sentinel()],
    on_say=_guide_hears,
    before_exit=_guide_blocks,
    on_perceive=_guide_listens,
    on_level_change=_guide_congratulates,
    )
```

### Essential Attributes:

* **`level`:** The mob's combat tier. Used by world rules for XP, hit chances, and difficulty scaling.
* **`vitals`:** Declares baseline maximum pools (e.g. `hp = 200`, `mana = 50`). Note: In GUPS, these parameter names
  correspond to your world's `MODULE.gups` schema, not hardcoded engine terms.
* **`stats`:** Base attributes (e.g. `stats(str = 14, dex = 12, con = 15)`).
* **`senses`:** Base sense scores by channel. Every actor starts with `sight` and `sound` at 1 (and any channel your
  world declares at 0); `senses` merges over those defaults, so `senses={"sound": 3}` leaves sight at 1. A score of 0
  means the mob cannot sense that channel at all, even in its own room: `senses={"sight": 0}` blinds it. Higher scores
  let a mob notice fainter and more distant events. Items (`sense_deltas`), affects (`apply_affect(...,
  sense_deltas={...})`) and plugin traits change the score while they last.
* **`dormant_recovery`:** Sets what happens when a mob wakes from the reality bubble sleep (`"full"`, `"regen"`, or
  `"none"`).

---

## 2. Built-in Behaviors

The GUPS engine includes composable AI behaviors (SPEC §4.7) that handle standard monster behaviors without requiring
custom code:

```python
mob("goblin_scout",
    name="a goblin scout",
    level=3,
    vitals=vitals(hp=30),
    behaviors=[
        # Stay within 4 rooms of spawn, 10% chance to step per idle pulse:
        wander(radius=4, chance=10),

        # Initiate combat automatically against players:
        aggressive(toward="players"),

        # Join fights if any mob matching the pattern is attacked in this room:
        assist(allies=["goblin_*"]),

        # Run away when HP drops below 10:
        flee(below=vitals(hp=10)),
    ],
    loot=[loot(":rusty_key", chance=25)],
    )
```

### Available Behaviors:

* `sentinel()`: Never leaves its post; disables movement behaviors.
* `wander(radius, chance)`: Randomly patrols nearby interconnected rooms.
* `aggressive(toward="players", unless_level_above=None, unless_level_below=None, chance=100, via="sight")`: Looks for a
  target with probability `chance` percent each think and attacks the first matching actor it notices on a channel in
  `via`.
* `assist(allies)`: Jumps into combat to defend friendly mobs.
* `flee(below)`: Disengages and runs through open exits when health falls below a vital threshold.

### Noticing and hiding

`aggressive` asks the rules whether the mob can perceive each candidate, once per candidate and per channel in `via`,
about the `notice` event. The template's `can_perceive` hides an actor that carries the `hidden` flag from a sight
notice:

```python
def can_perceive(ctx):
    if ctx.event == "notice" and ctx.channel == "sight" and ctx.source and ctx.source.has_flag("hidden"):
        return False
    ...
```

Decide stealth when the actor hides and store the result as a flag on an affect, then read it in `can_perceive`. Don't
roll inside `can_perceive`; that would give the mob a fresh roll on every look. `chance` sets how often the mob looks,
and `via` the channels it uses; a world may declare a channel such as `scent` (§3.9) so a creature that senses it can't
be hidden from. The template ships no `hide` command.

---

## 3. Custom AI: The `think` Hook

For complex mob routines (boss mechanics, patrolling specific waypoints, casting spells in combat), use `think` and
`think_every`:

```python
def _shaman_think(ctx):
    # ctx.self is the mob
    # ctx.self.fighting() lists the mob's opponents (empty when it isn't fighting)
    foes = ctx.self.fighting()
    if foes and ctx.self.mana >= 10:
        return ctx.act_cast(ctx.self, "//skills/magic:flame_strike", foes[0])
    elif ctx.self.hp < 50 and ctx.self.mana >= 5:
        return ctx.act_cast(ctx.self, "//skills/magic:minor_heal", ctx.self)
    return None


mob("goblin_shaman",
    # ...
    think=_shaman_think,
    think_every=4,  # Execute every 4 pulses (1 second)
    )
```

* **Pulses:** 1 world pulse = 250 milliseconds. `think_every = 4` runs the AI loop once per second.
* **Combat Awareness:** Within `think`, `ctx.self.fighting()` lists the mob's opponents; `ctx.target` is not set.
* **Return value:** `None` lets the top built-in proposal run (`ctx.proposals[0]`), queuing an action with `ctx.act*`
  replaces it, and `"idle"` skips the turn.

---

## 4. Behavior Trees and Planning

A `think` hook full of nested `if`s gets hard to follow once a mob has more than a couple of things to do. The
template's library `//lib:ai.star` gives you two tools on top of `think`: **behavior trees**, for deciding what to do
this cycle, and **goal-oriented planning** (GOAP), for working out a sequence of steps that reaches a goal.

### Trees

```python
load("//lib:ai.star", "ai")

def _hurt(ctx, bb):
    return ctx.self.hp < ctx.self.max_hp // 3

def _shout(ctx, bb):
    ctx.act(ctx.self, "shout Help!")
    return ai.SUCCESS

TREE = ai.selector([
    ai.sequence([ai.condition(_hurt), ai.action(_shout)]),
    ai.action(_patrol),  # another action, written like _shout
])

def _think(ctx):
    return ai.run(ctx, TREE)
```

Build the tree once, at the top of the file, and return `ai.run(ctx, TREE)` from `think`. Every node returns
`ai.SUCCESS`, `ai.FAILURE` or `ai.RUNNING`. `RUNNING` means "I acted this cycle and am not done yet", and parents treat
it like success.

* `ai.selector(children)` tries children in order and returns the first that doesn't fail.
* `ai.sequence(children)` runs children in order while they succeed and stops at the first that doesn't.
* `ai.condition(fn)` turns `fn(ctx, bb) -> bool` into success or failure.
* `ai.action(fn)` calls `fn(ctx, bb)`, which does something and returns a status.
* `ai.inverter(child)` swaps success and failure.
* `ai.steps(id, children)` runs its children one per cycle, in order, across cycles.
* `ai.goap(...)` plans, described below.

`ai.run` returns `"idle"` when the tree succeeded or is running, so the tree has the turn, and `None` when it failed, so
the mob's top built-in behavior runs instead. A tree that fails on purpose is how you hand control back to `flee` or
`wander`.

### The blackboard

The tree is evaluated from the root on every `think`. It doesn't remember which node was running last cycle, so a mob
reacts at once when the world changes. Anything that must carry over goes in the **blackboard**, the `bb` dict every
leaf receives. `ai.run` saves it in the mob's attributes when it changed, so it counts against the 64 KB attribute cap:
keep it to a few small values. Use `ai.steps` when a routine really is a fixed order of steps; it keeps its place in the
blackboard.

### Planning

Planning separates *what is true* from *what to do*. You describe actions by what they need and what they make true, and
`ctx.plan` finds the cheapest sequence that reaches the goal:

```python
ACTIONS = [
    {"name": "walk_to_pinecone", "pre": {"pinecone_known": True, "has_pinecone": False},
     "effects": {"pinecone_here": True}, "run": _walk_to_pinecone},
    {"name": "take_pinecone", "pre": {"pinecone_here": True, "has_pinecone": False},
     "effects": {"has_pinecone": True, "pinecone_here": False}, "run": _take_pinecone},
    # go_home, drop_pinecone ...
]

TREE = ai.sequence([
    ai.inverter(ai.condition(_fighting)),
    ai.goap("cache", _sense, ACTIONS, {"cached": True}),
])
```

Each cycle the `ai.goap` leaf:

1. calls `sense(ctx, bb)`, which turns world queries into a dict of facts;
2. succeeds if the goal already holds;
3. otherwise follows its cached plan if the next step can still run, or calls `ctx.plan` for a new one;
4. calls that step's `run(ctx, bb)` and returns `RUNNING`, or fails if no plan exists.

Facts are compared for equality only, so do the comparing while sensing: return `"hp_low": True`, not the hit points. A
fact that is missing matches nothing.

The tutorial zone's squirrel ([`forager.star`](../../zones/tutorial/forager.star)) is a complete example. It lives on
the gravel path, plans its way to the pinecone in the tar pit and back, and caches it at home. While it is fighting the
first branch fails the whole tree, so its built-in `flee` takes over.

### What it costs

Trees, sensing and every `run` are ordinary script and spend `think` steps. `ctx.plan` runs in the engine and spends
none, but it is bounded: when a search runs out of room it returns `None`, which `ai.goap` treats as failure. Keep the
action table small, and plan only when the cached plan breaks, as `ai.goap` does. With many planning mobs in a zone,
give them different `think_every` values so they don't all think in the same pulse, and avoid world-wide queries such as
`ctx.find(..., scope = "world")` in every cycle.

AI that needs more speed than this belongs in a plugin builtin written in Kotlin (see
[PLUGINS.md](../../../docs/PLUGINS.md#builtins)).

---

## 5. Movement Pace and Pursuit

A mob's `speed` stat sets how fast it walks. The rules turn it into a **move pace** — the hundredths of a pulse the
actor spends per room — with `10000 // max(speed, 10)`, capped at four rooms a pulse. A speed of `100` walks a room a
pulse, `300` walks three, and `75` walks three rooms every four pulses. A faster pursuer closes the gap on a fleeing
player; a slower one falls behind.

```python
mob("hunting_cat",
    name="a hunting cat",
    level=1,
    vitals=vitals(hp=30, move=100),
    stats=stats(speed=300),
    behaviors=[aggressive(toward="players"), pursue("players")],
    )
```

`pursue` takes one step toward its quarry per think. After that step the mob gets a **chained think**: it thinks again
as soon as its pace allows instead of waiting for `think_every`, so a fast mob closes a gap of several rooms in a single
pulse, and attacks the moment it arrives. The rules export `chain_think` decides which actions chain: the template's
(`rules/movement.star`) returns true after a step taken for `pursue`, `follow`, `heel` or the walk home, so the mob
keeps moving until it catches up. Plugins can adjust the decision with `addChainThink` (see
[PLUGINS.md](../../../docs/PLUGINS.md)).

To hand a player or mob a burst of speed, apply an affect that raises `speed`; the pace reads the stat after affects, so
the burst lasts only for the affect:

```python
ctx.apply_affect(ctx.actor, "haste", 100, stat_deltas=stats(speed=50))
```

---

## 6. Spawns and Zone Resets

Mobs are placed into rooms through the `spawns` list on a `room()`:

```python
room("arena",
     name="The Training Arena",
     desc="A wide circular arena with sand on the floor.",
     size=SIZE.large,
     spawns=[
         spawn(":training_dummy", count=1, at_index=2),
     ],
     )
```

### Parameters for `spawn()`:

* **Target label:** Positionally passed (e.g. `":training_dummy"`).
* **`count`:** Maximum number of living instances allowed in the room.
* **`at_index`:** Which track slot on the room's spatial track the mob spawns at (0 = front, 2 = center, -1 = back).
* **`respawn_pulses`:** Optional timer to respawn individually after death.

### Zone Resets (`reset_world_ticks`)

In [`package.star`](../../zones/tutorial/package.star):

```python
package(
    kind="zone",
    reset_world_ticks=10,  # Every 10 world ticks (5 minutes)
)
```

When a zone reset triggers:

1. Every spawn list in the zone refills up to its `count`.
2. Every room's `on_reset` hook runs (allowing custom chest restocking, puzzle resets, or door relocking).

---

## Reference Links

* **Reference Implementation:** [`template-world/zones/tutorial/mobs.star`](../../zones/tutorial/mobs.star) and
  [`template-world/zones/tutorial/forager.star`](../../zones/tutorial/forager.star)
* **Library Implementation:** [`template-world/lib/ai.star`](../../lib/ai.star)
* **Specification:** [SPEC.md §4.2: Content Declarations](../../../docs/SPEC.md#L965),
  [§4.7: Behaviors](../../../docs/SPEC.md#L1433), and [§4.8: Mob Think](../../../docs/SPEC.md#L1460)
* **HTML Documentation:** `declarations.html#mob`, `declarations.html#spawn`, and `effects.html#behaviors` in `gups
  docs`.
