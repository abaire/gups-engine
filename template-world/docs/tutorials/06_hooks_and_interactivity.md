# 06. Hooks, Perception, and Interactivity

Hooks allow you to execute Starlark code in response to world events. This tutorial explains the difference between
lifecycle notifications and action vetoes, how dialogue works, sensory perception, and delayed timers.

---

## 1. Notification Hooks vs. Interceptors

GUPS categorizes hooks into two families:

| Hook Type        | Naming                                    | Purpose                                    | Veto Allowed?            |
|------------------|-------------------------------------------|--------------------------------------------|--------------------------|
| **Notification** | `on_*` (`on_enter`, `on_say`, `on_death`) | Respond to events that *already happened*. | No                       |
| **Interceptor**  | `before_*` (`before_exit`, `before_use`)  | Observe an action before it takes effect.  | **Yes** via `ctx.deny()` |

---

## 2. Vetoing Player Actions: `before_*` & `ctx.deny()`

In [`mobs.star`](../../zones/tutorial/mobs.star), the guide stops unprepared players from entering the arena:

```python
def _guide_blocks(ctx):
    # A before_* hook on a mob is an observer for actions in the mob's room
    if ctx.direction == "north" and ctx.actor.is_player and not ctx.get_attr(ctx.actor, "ready", False):
        return ctx.deny("The guide blocks your way: say 'ready' first.")
```

### How `ctx.deny()` Works:

* **Action Cancellation:** If any interceptor returns `ctx.deny("Message")`, the action is immediately aborted.
* **No Action Lag:** A denied action costs the player **zero lag pulses**, allowing them to immediately try something
  else.
* **Player Feedback:** The string passed to `ctx.deny()` is sent directly to the actor as the refusal reason.
* If the action is permitted, simply return nothing (`None`).

---

## 3. Dialogue and Conversation: `on_say`

When any actor speaks (using `say` or `"`), the `on_say` hook fires on all other actors in the room:

```python
def _guide_hears(ctx):
    # ctx.text: What was spoken
    # ctx.actor: The character who spoke
    # ctx.self: The mob listening
    if said(ctx.text, "ready"):
        ctx.set_attr(ctx.actor, "ready", True)
        ctx.say(ctx.self, "Then off you go, %s. The arena is north." % ctx.actor.name)
    elif said(ctx.text, "key") and not ctx.actor.has_item(":brass_key"):
        ctx.give(ctx.actor, ":brass_key")
        ctx.emote(ctx.self, "hands %s a brass key." % ctx.actor.name)
        quest.advance(ctx, ctx.actor, FIRST_STEPS, from_step=0)
```

### Useful Speech Primitives:

* `ctx.say(speaker, text)`: Makes the speaker say a line to everyone in the room.
* `ctx.emote(actor, action)`: Displays a third-person narrative action (e.g., `"The guide hands Tess a brass key."`).
* `ctx.send(recipient, text)`: Sends private text to one specific player (invisible to observers).

---

## 4. Sensory Perception: `on_nearby`

Mobs with sensory acuity can detect events happening in the same room or through adjacent exits:

```python
def _guide_listens(ctx):
    # ctx.event: Event identifier (e.g. "combat_start", "spell_cast", "noise")
    # ctx.via_exit: Which exit the sound travelled through
    if ctx.event == "combat_start" and ctx.via_exit == "north":
        ctx.say(ctx.self, "I hear someone sparring in the arena. Good!")


mob("guide",
    # ...
    senses={"hearing": 1},  # Hear 1 room away
    on_nearby=_guide_listens,
    )
```

### Sensory Propagation Rules:

* A mob with `senses = {"hearing": 1}` hears loud sounds (combat, shouting, heavy doors) from rooms 1 step away.
* Exits can block senses using `senses_blocked`:
  ```python
  exit(DIR.east, to = ":quiet_room", senses_blocked = [SENSE.sound, SENSE.noise])
  ```
* Closed doors also muffle sound according to their `closed_senses_blocked` properties.

---

## 5. Delayed Execution: Timers (`ctx.after()`)

To schedule an action in the future without blocking the server loop, use `ctx.after()`:

In [`mobs.star`](../../zones/tutorial/mobs.star), the training dummy repairs itself after taking damage:

```python
def _mend(ctx):
    # Timer callbacks run with ctx.self set to the entity that scheduled the timer
    ctx.heal(ctx.self, 100, "hp")
    ctx.echo(ctx.self.room, "The training dummy's straw knits itself back together.")


def _dummy_hurt(ctx):
    # Schedule _mend to run in 8 pulses (2 seconds)
    ctx.after(8, _mend)


mob("training_dummy",
    name="a training dummy",
    vitals=vitals(hp=50),
    on_damage=_dummy_hurt,
    )
```

* **Pulses:** Time is measured in 250ms pulses (`4 pulses = 1 second`).
* **Parameters:** Any keyword arguments passed to `ctx.after(pulses, fn, **kwargs)` are forwarded to the callback
  function.
* **Safety:** If the entity is destroyed before the timer fires, the scheduled callback is safely cancelled.

---

## Reference Links

* **Reference Implementation:** [`template-world/zones/tutorial/mobs.star`](../../zones/tutorial/mobs.star)
* **Specification:** [SPEC.md §4.4: Hook Catalog](../../../docs/SPEC.md#L1102)
  and [§5.7: Adjacent-Room Perception](../../../docs/SPEC.md#L2863)
* **HTML Documentation:** `hooks.html` and `ctx.html#after` in `gups docs`.
