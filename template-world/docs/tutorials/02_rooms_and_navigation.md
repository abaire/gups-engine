# 02. Rooms, Exits, and Navigation

Rooms are the spatial building blocks of your zone. This guide explains how to construct rooms, connect them with
bidirectional and one-way exits, add locked doors, configure combat tracks, and create custom room commands.

---

## 1. Declaring a Room

Rooms are defined using the `room()` builtin. A basic room needs a unique identifier, a display name, and a description:

```python
# zones/tutorial/rooms.star
room("gate",
     name="The Tutorial Gate",
     desc="A wooden gate stands open to the north. A sign is nailed to the post.",
     size=SIZE.normal,
     flags=["dead_end_ok"],
     exits=[exit(DIR.north, to=":hall")],
     commands={"read": _read},
     )
```

### Key Parameters:

* **Identifier (`"gate"`):** The first positional argument. Within this package, other files refer to it as `:gate`.
  From another package, it is addressed as `//zones/tutorial:gate`.
* **`name` & `desc`:** What players see when entering or typing `look`.
* **`flags`:**
  * `"dead_end_ok"`: By default, the `gups lint` CI check rejects any room that has only one exit (a dead end) unless
    this flag is explicitly set.
  * `"always_active"`: Keeps the room active in the world simulation even when no players are nearby (disabling the
    reality bubble sleep; see SPEC §5.9).

---

## 2. Exits and Navigation

Passages between rooms are defined with `exit()` using the `DIR` constants:

```python
exits = [
    exit(DIR.north, to=":hall"),
    exit(DIR.east, to=":secret_tunnel", one_way=True),
]
```

### Two-Way Exit Matching Rule

The engine expects standard world navigation to be reciprocal. If room `:hall` has `exit(DIR.south, to = ":gate")`, then
`:gate` should have `exit(DIR.north, to = ":hall")`.

* If an exit is intentionally one-way (e.g. falling down a chute, jumping off a cliff), you **must** set `one_way =
  True` to prevent `gups lint` from failing.

---

## 3. Doors and Keys

To place a door across a passage, attach `door()` to the exit:

```python
room("hall",
     name="The Hall of Lessons",
     desc="A long, bright hall. Doors lead east and north, a dark passage west; the gate is south.",
     size=SIZE.large,
     exits=[
         exit(DIR.south, to=":gate"),
         exit(DIR.north, to=":arena", entry_index=-1),
         exit(DIR.east, to=":armory",
              door=door(state=DOOR.locked, key=":brass_key", tags=["creaky"])),
     ],
     )
```

A door's `tags` are free-form words that the rules module reads: in the template, `rules/manipulation.star` makes
`"creaky"` and `"heavy"` doors louder to open and close, and `rules/perception.star` makes `"heavy"` doors muffle sound
more (see [06](06_hooks_and_interactivity.md)).

### How Paired Doors Work

1. **Synchronization:** Doors on both sides of a passage share state. When a player runs `unlock east` and `open east`
   in `:hall`, the corresponding door in `:armory` automatically opens as well.
2. **Door States:** `DOOR.open`, `DOOR.closed`, or `DOOR.locked`.
3. **Key Matching:** The `key` parameter takes a target label (e.g. `:brass_key`). The player must have that item in
   their inventory to `unlock` or `lock` the door.

---

## 4. The Intra-Room Spatial Model (Tracks)

Unlike classic MUDs where everyone in a room is in immediate melee reach, GUPS features an intra-room track system
configured by `size`:

|      Size      | Track Slots |                                    Description                                    |
|----------------|-------------|-----------------------------------------------------------------------------------|
| `SIZE.cramped` | 1 slot      | Tight spaces (closet, elevator, tunnel). Everyone is at distance 0 (melee range). |
| `SIZE.normal`  | 3 slots     | Standard rooms (hallway, cottage, cave). Slots: 0 (front), 1 (mid), 2 (back).     |
| `SIZE.large`   | 5 slots     | Expansive areas (arena, cathedral, courtyard). Slots: 0 through 4.                |

### Tactical Entry Positions: `entry_index`

When players traverse an exit, they enter the destination room at a specific track slot:

```python
# Arrive at the far back of the arena (slot 4) instead of the front (slot 0):
exit(DIR.north, to=":arena", entry_index=-1)
```

* `entry_index = 0` (default): Player arrives at the front of the room.
* `entry_index = -1`: Player arrives at the back slot of the destination track.

Actors move forward and backward across track slots using movement actions (`advance`, `retreat`), determining weapon
and spell ranges.

---

## 5. Custom Room Commands (Zone Verbs)

You can attach custom verbs directly to a room using the `commands` dictionary:

```python
def _read(ctx):
    # ctx.args holds whatever the player typed after "read"
    if ctx.args in ("", "sign"):
        ctx.send(ctx.actor, "The sign reads: 'Go north to meet the guide.'")
    else:
        ctx.send(ctx.actor, "There is nothing like that to read here.")


room("gate",
     # ...
     commands={"read": _read},
     )
```

* **Execution:** Room commands execute in the engine's **tick lane** just like standard player actions (`look`, `get`,
  `cast`), respecting action lag and priority.
* **Arguments:** `ctx.args` holds the raw text after the command verb (e.g., typing `read old sign` passes `"old sign"`
  into `ctx.args`).

---

## 6. Room and Region Rules Modifiers

While hooks respond to events, **modifiers** allow rooms and regions to dynamically intercept and adjust engine rule
calculations—such as damage dealt in combat, hit chances, movement costs, or casting permissions.

### Declaring a Modifier Function

A modifier is a top-level function that receives the read-only rules context (`ctx`) and the current accumulated value
(`base`):

```python
def _blunted(ctx, base):
    # A modifier receives the read-only rules ctx and the accumulated value so far.
    # It returns the newly adjusted value. This example halves all raw auto-attack damage.
    return max(1, base // 2)
```

* **Read-Only Context:** Modifiers adjust numbers; `ctx` is strictly read-only (it has no verbs like `ctx.damage` or
  `ctx.send`).
* **Available Modifier Points:**
  * `MODIFIER.to_hit`: Adjusts attack hit chance in percent (`base`: `int`).
  * `MODIFIER.damage`: Adjusts raw attack damage before mitigation (`base`: `int`).
  * `MODIFIER.initiative`: Adjusts combat round turn order (`base`: `int`).
  * `MODIFIER.cast_allowed`: Vetoes spell casting (`return ctx.deny("...")` to cancel; `base`: `None`).
  * `MODIFIER.move_cost`: Adjusts vitals cost for leaving the room (`base`: `vitals(...)`).
  * `MODIFIER.regen`: Adjusts vital recovery per world pulse (`base`: `vitals(...)`).

### Attaching Modifiers to Rooms and Regions

You can attach modifiers either directly to a single room or across multiple rooms using a `region()`:

```python
# 1. Direct room modifier (applies only in this room)
room("antimagic_chamber",
     name="Antimagic Chamber",
     desc="The air hums with nullifying energies.",
     flags=["dead_end_ok"],
     modifiers={
         MODIFIER.damage: _blunted,
     },
     )

# 2. Region-wide modifier (applies to all rooms tagged with the region)
region("practice_grounds",
       modifiers={
           MODIFIER.damage: _blunted,
       },
       )

room("arena",
     name="The Sparring Arena",
     regions=["practice_grounds"],
     # ...
     )
```

### The Modifier Execution Chain

When a rules calculation runs (e.g., computing attack damage), the engine processes modifiers in a strict deterministic
order: 1. **Base Rule:** Calculated by the world ruleset (e.g. `rules.damage(ctx)` in `rules/combat.star`). 2. **Zone
Package Modifiers:** Set in `package(kind = "zone", modifiers = {...})`. 3. **Region Modifiers:** From regions tagged on
the room, sorted alphabetically by region name. 4. **Room Modifiers:** From the specific `room(modifiers = {...})`. 5.
**Actor Affect Modifiers:** Active temporary affects on the actor. 6. **Worn Item Modifiers:** From equipped gear,
evaluated in `equipment_slots()` order.

### Inspecting Modifiers in Action

You can see every step of the modifier chain execute in real time using the `@trace rules` command in telnet or
`w.trace(True)` in test scripts:

```
rules.damage base=12 → region:practice_grounds ×0.5 → 6
```

---

## 7. Room Tags and the Rules Module

A room can carry `tags`, plain identifiers the engine gives no meaning. The tutorial's tar pit (west of the hall) is
tagged `sticky_floor`:

```python
room("tar_pit",
    name = "The Tar Pit",
    desc = "Black tar clings to your boots; every step is an effort.",
    size = SIZE.normal,
    flags = ["dead_end_ok"],
    tags = ["sticky_floor"],
    exits = [exit(DIR.east, to = ":hall"), exit(DIR.west, to = ":gravel")],
)
```

The tag does something only because `rules/combat.star` reads it. The rules module sets the recovery lag of `advance`
and `retreat`, and the template doubles it in a tagged room:

```python
def _footing(ctx):
    # Rooms tagged "sticky_floor" make every step take twice as long.
    return 2 if "sticky_floor" in ctx.room.tags else 1

def advance_lag(ctx):
    # Recovery pulses after stepping toward ctx.target.
    return 2 * _footing(ctx)
```

A tag means only what the world's rules or plugins make of it. Since the rules package is world-wide, a tag such as
`sticky_floor` is a contract between the rules and every zone that uses it: tag a room in any zone and the rules apply
there.

---

## Reference Links

* **Reference Implementation:** [`template-world/zones/tutorial/rooms.star`](../../zones/tutorial/rooms.star)
* **Specification:** [SPEC.md §4.2: Content Declarations](../../../docs/SPEC.md#L942),
  [§4.6: Rules Module and the Modifier Chain](../../../docs/SPEC.md#L1277), and
  [§5.5: Intra-Room Spatial Model](../../../docs/SPEC.md#L2780)
* **HTML Documentation:** `declarations.html#room`, `declarations.html#region`, `declarations.html#exit`, and
  `declarations.html#door` in `gups docs`.
