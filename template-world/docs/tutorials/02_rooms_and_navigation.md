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
     size=NORMAL,
     flags=["dead_end_ok"],
     exits=[exit("north", to=":hall")],
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

Passages between rooms are defined with `exit()`:

```python
exits = [
    exit("north", to=":hall"),
    exit("east", to=":secret_tunnel", one_way=True),
]
```

### Two-Way Exit Matching Rule

The engine expects standard world navigation to be reciprocal. If room `:hall` has `exit("south", to = ":gate")`, then
`:gate` should have `exit("north", to = ":hall")`.

* If an exit is intentionally one-way (e.g. falling down a chute, jumping off a cliff), you **must** set
  `one_way = True` to prevent `gups lint` from failing.

---

## 3. Doors and Keys

To place a door across a passage, attach `door()` to the exit:

```python
room("hall",
     name="The Hall of Lessons",
     desc="A long, bright hall. Doors lead east and north; the gate is south.",
     size=LARGE,
     exits=[
         exit("south", to=":gate"),
         exit("north", to=":arena", entry_index=-1),
         exit("east", to=":armory", door=door(state="locked", key=":brass_key")),
     ],
     )
```

### How Paired Doors Work

1. **Synchronization:** Doors on both sides of a passage share state. When a player runs `unlock east` and `open east`
   in `:hall`, the corresponding door in `:armory` automatically opens as well.
2. **Door States:** `"open"`, `"closed"`, or `"locked"`.
3. **Key Matching:** The `key` parameter takes a target label (e.g. `:brass_key`). The player must have that item in
   their inventory to `unlock` or `lock` the door.

---

## 4. The Intra-Room Spatial Model (Tracks)

Unlike classic MUDs where everyone in a room is in immediate melee reach, GUPS features an intra-room track system
configured by `size`:

| Size      | Track Slots | Description                                                                       |
|-----------|-------------|-----------------------------------------------------------------------------------|
| `CRAMPED` | 1 slot      | Tight spaces (closet, elevator, tunnel). Everyone is at distance 0 (melee range). |
| `NORMAL`  | 3 slots     | Standard rooms (hallway, cottage, cave). Slots: 0 (front), 1 (mid), 2 (back).     |
| `LARGE`   | 5 slots     | Expansive areas (arena, cathedral, courtyard). Slots: 0 through 4.                |

### Tactical Entry Positions: `entry_index`

When players traverse an exit, they enter the destination room at a specific track slot:

```python
# Arrive at the far back of the arena (slot 4) instead of the front (slot 0):
exit("north", to=":arena", entry_index=-1)
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

## Reference Links

* **Reference Implementation:** [`template-world/zones/tutorial/rooms.star`](../../zones/tutorial/rooms.star)
* **Specification:** [SPEC.md §4.2: Content Declarations](../../../docs/SPEC.md#L942)
  and [§5.5: Intra-Room Spatial Model](../../../docs/SPEC.md#L2780)
* **HTML Documentation:** `declarations.html#room`, `declarations.html#exit`, and `declarations.html#door` in
  `gups docs`.
