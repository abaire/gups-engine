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
    senses={"hearing": 1},
    behaviors=[sentinel()],
    on_say=_guide_hears,
    before_exit=_guide_blocks,
    on_nearby=_guide_listens,
    )
```

### Essential Attributes:

* **`level`:** The mob's combat tier. Used by world rules for XP, hit chances, and difficulty scaling.
* **`vitals`:** Declares baseline maximum pools (e.g. `hp = 200`, `mana = 50`). Note: In GUPS, these parameter names
  correspond to your world's `MODULE.gups` schema, not hardcoded engine terms.
* **`stats`:** Base attributes (e.g. `stats(str = 14, dex = 12, con = 15)`).
* **`senses`:** Configures sensory perception:
    * `"sight": 0` — sees in the same room.
    * `"hearing": 1` — hears noise from adjacent rooms connected by exits.
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
        wander(radius=4, chance=0.1),

        # Initiate combat automatically against players:
        aggressive(toward="players"),

        # Join fights if any mob matching the pattern is attacked in this room:
        assist(allies=["goblin_*"]),

        # Run away when HP drops below 10:
        flee(below=vitals(hp=10)),
    ],
    loot=[loot(":rusty_key", chance=0.25)],
    )
```

### Available Behaviors:

* `sentinel()`: Never leaves its post; disables movement behaviors.
* `wander(radius, chance)`: Randomly patrols nearby interconnected rooms.
* `aggressive(toward)`: Attacks targets matching `"players"`, `"mobs"`, or specific labels.
* `assist(allies)`: Jumps into combat to defend friendly mobs.
* `flee(below)`: Disengages and runs through open exits when health falls below a vital threshold.

---

## 3. Custom AI: The `think` Hook

For complex mob routines (boss mechanics, patrolling specific waypoints, casting spells in combat), use `think` and
`think_every`:

```python
def _shaman_think(ctx):
    # ctx.self is the mob
    # ctx.target is who the mob is fighting (or None if out of combat)
    if ctx.target != None and ctx.self.mana >= 10:
        ctx.cast("//skills/magic:flame_strike", target=ctx.target)
    elif ctx.self.hp < 50:
        ctx.cast("//skills/magic:minor_heal", target=ctx.self)


mob("goblin_shaman",
    # ...
    think=_shaman_think,
    think_every=4,  # Execute every 4 pulses (1 second)
    )
```

* **Pulses:** 1 world pulse = 250 milliseconds. `think_every = 4` runs the AI loop once per second.
* **Combat Awareness:** Within `think`, `ctx.target` refers to the mob's active combat opponent.

---

## 4. Spawns and Zone Resets

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

* **Reference Implementation:** [`template-world/zones/tutorial/mobs.star`](../../zones/tutorial/mobs.star)
*
**Specification:** [SPEC.md §4.2: Content Declarations](../../../docs/SPEC.md#L965), [§4.7: Behaviors](../../../docs/SPEC.md#L1433),
and [§4.8: Mob Think](../../../docs/SPEC.md#L1460)
* **HTML Documentation:** `declarations.html#mob`, `declarations.html#spawn`, and `effects.html#behaviors` in
  `gups docs`.
