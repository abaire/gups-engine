# 06. Hooks, Perception, and Interactivity

Hooks allow you to execute Starlark code in response to world events. This tutorial explains the difference between
lifecycle notifications and action vetoes, how dialogue works, sensory perception, and delayed timers.

---

## 1. Notification Hooks vs. Interceptors

GUPS categorizes hooks into two families:

|    Hook Type     |                  Naming                   |                  Purpose                   |      Veto Allowed?       |
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

## 4. Sensory Perception: `on_perceive`

Events happen on a **channel**: `sight` (movement, emotes) or `sound` (speech, combat, shouts, doors), or any channel
your world declares in `MODULE.gups`. An event starts in a room with an **intensity** and spreads through the exits,
losing strength on every hop. Mobs notice the events they can perceive through `on_perceive`:

```python
def _guide_listens(ctx):
    # ctx.event:     "combat_start", "say", "crunch", ...
    # ctx.channel:   the channel it travelled on
    # ctx.intensity: how strong it started; ctx.cost: how much it weakened on the way here
    # ctx.via_exit:  the mob's own exit toward the source, or None in the same room
    if ctx.event == "combat_start" and ctx.via_exit == "north":
        ctx.say(ctx.self, "I hear someone sparring in the arena. Good!")


mob("guide",
    # ...
    senses={"sound": 1},
    on_perceive=_guide_listens,
    )
```

### Who notices what

Three functions in the rules module decide, so you can change them without touching the engine:

* **`sense_cost(ctx)`** (`rules/perception.star`) prices each hop: the room the signal enters is `ctx.room`, the exit
  leading back toward the source is `ctx.exit`, and `ctx.channel` is the channel. Return the amount to add to the
  event's cost (0 is a free hop), or a negative number to block the hop for good. The template blocks `sealed` exits,
  charges more for large rooms and for closed doors (`"heavy"` doors cost double), and treats a closed door as opaque.
* **`can_perceive(ctx)`** decides, for each candidate listener in the rooms an event reaches, whether they notice it. In
  the template a listener needs `ctx.actor.senses[ctx.channel] > 0`, and outside the event's own room `ctx.intensity +
  sense > ctx.cost`. So a shout (intensity 3) carries farther than a spoken word (1), and a mob with `senses={"sound":
  3}` hears farther than most.
* **`perceive_message(ctx)`** is what a *player* in another room is shown, or `None` for nothing. The room where the
  event happens is told as it happens, so this is only for distant players.

Exits and doors have `tags` that `sense_cost` can read. The template treats an exit tagged `sealed` as carrying nothing
and one tagged `soundproof` as carrying no sound, and a closed door as opaque unless it is tagged `transparent`:

```python
exit(DIR.east, to = ":quiet_room", tags = ["soundproof"])
```

Inside a rules function, `ctx.exit.tags` are the exit's tags, and `ctx.exit.door` is a door view (`state`, `key`,
`tags`), or `None` when the exit has no door.

### Raising your own events: `ctx.emit`

A script can make any room, item or spell perceivable. From the gravel path in the tutorial:

```python
def _crunch(ctx):
    if ctx.actor.is_player:
        ctx.emit("sound", "crunch", intensity = 3, text = "Gravel crunches loudly somewhere to the west.")

room("gravel", exits = [exit(DIR.east, to = ":tar_pit")], on_enter = _crunch, ...)
```

`ctx.emit(channel, event, intensity = 1, text = None, room = None, actor = None)` defaults `room` to `ctx.room` and
`actor` to `ctx.actor`; the actor who caused an event never perceives it, and an intensity of 0 or less emits nothing.
The guide two rooms away hears it in `on_perceive`; a player in the tar pit is shown the `text` by `perceive_message`.

### Doors and containers make sound

`open`, `close`, `lock` and `unlock` on a door or container run `before_open` / `on_open` (and the same for the other
three) on the door's room and the container, and fire the hooks of anyone in the room. A `before_*` hook vetoes the
action with `ctx.deny(msg)`; `ctx.direction` is set for a door and `ctx.item` for a container. Afterwards
`door_intensity(ctx)` in `rules/manipulation.star` says how loud it was, from the door's or container's `tags`: the
tutorial's `"creaky"` armory door is twice as loud to open, so a player at the gate hears it from two rooms away.

### Changing a mob's or player's senses

`sense_deltas` raise or lower a score while they last, on items (`item("lens", wear = "wield", sense_deltas = {"sight":
1})`), on affects (`ctx.apply_affect(target, "dazzled", 40, sense_deltas = {"sight": -1})`) and on plugin traits. The
effective score is never below 0; read it with `ctx.actor.senses["sight"]`, or the unmodified score with
`ctx.actor.base.senses["sight"]`.

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
* **Specification:** [SPEC.md §4.4: Hook Catalog](../../../docs/SPEC.md#L993) and
  [§5.7: Perception](../../../docs/SPEC.md#L2853)
* **HTML Documentation:** `hooks.html` and `ctx.html#after` in `gups docs`.
