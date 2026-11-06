# 10. Groups and Shared Experience: The `//lib:group.star` Library

By default a kill pays its experience to the player who landed the last blow. That is fine for a solo player and unfair
to a healer who never touches the mob. This tutorial covers the two halves of the fix: the engine's **contribution
ledger** and **player groups**, and the template world's policy built on them, `xp_share` in `rules/combat.star` and the
commands in `lib/group.star`.

The split of work is deliberate. The engine does what a script cannot: it records who damaged, healed or helped against
each actor, and it keeps the group itself. Everything social (who may invite whom, how experience is divided, what the
group chat looks like) is Starlark you can change.

---

## 1. The Contribution Ledger

Every actor keeps a transient record, never saved, of what each other actor has done toward a fight against it:

* `damage`: the vital loss the contributor dealt to the victim.
* `healing`: the effective healing the contributor gave an actor engaged with the victim. Overheal does not count.
* `support`: how many affects the contributor applied, either a bane on the victim or a buff on an actor engaged with
  it.
* `last_pulse`: the pulse of the most recent contribution of any kind.

A poison or a burning spell credits whoever applied it: damage an affect's `on_tick` or `on_expire` hook does with no
`source` counts as done by the actor that applied the affect, while that actor is still in the world.

The engine never expires the ledger. A world decides when a fight is over and calls `ctx.clear_contributions(victim)`
(see section 5).

---

## 2. The `xp_share` Rules Export

The engine prices a kill once with `xp_for_kill`, which becomes the **pool**. Then `xp_share(ctx)` decides who gets
what:

* `ctx.actor` is the killer, `ctx.target` the victim and `ctx.xp` the pool.
* `ctx.contributors` is a list of structs: `actor`, `damage`, `healing`, `support`, `last_pulse`, `owner`, `group` (an
  id or `None`) and `in_room`. It holds every ledger entry, the killer, and every member of a contributor's group, even
  one who did nothing or stands elsewhere.
* It returns a dict `{actor: int}`. An entry may name any actor in the world, including one not in the list, such as a
  pet's owner. Zero entries and actors who have left are skipped. A negative value, a key that isn't an actor or a
  non-int value is a rules error, and the kill falls back to paying the killer the whole pool.

A world that defines no `xp_share` pays the killer the pool, as before.

The template's version weighs each contributor, pays pets' shares to their owners, and gives the rounding remainder to
the killer (or, if the killer isn't paid, to the heaviest contributor) so the shares always add up to exactly the pool.
A solo kill therefore pays exactly what `xp_for_kill` priced.

---

## 3. Tuning the Template Formula

The constants sit at the top of the formula in `rules/combat.star`:

```python
CONTRIB_WINDOW_PULSES = 1200   # 5 minutes of recent activity
LEVEL_BAND = 10                # the level range a payee may be from the victim
WEIGHT_DAMAGE = 100            # weight per point of damage
WEIGHT_HEALING = 100           # weight per point of healing
WEIGHT_SUPPORT = 20            # weight per point of support
IDLE_SHARE = 10                # the minimum weight of a grouped member in the victim's room
```

A contributor is paid only if it stands in the room or belongs to a group, contributed within the window (a grouped
member in the room is exempt), and is within `LEVEL_BAND` levels of the victim. Raise `WEIGHT_HEALING` to reward
healers, raise `IDLE_SHARE` to pay a member who just stood there, or drop `LEVEL_BAND` to stop high-level players
carrying low-level friends.

---

## 4. Groups and Their Commands

A group is an in-memory set of players with a leader. It lasts as long as the server run: a quit, a link-dead expiry or
any removal of a player from the world drops that player, and a group left with one member is disbanded. The engine
provides the verbs `group_add`, `group_remove`, `group_set_leader` and `group_disband`, the queries `group_of` and
`group_members`, and the global hook `on_group_change`. It has no invitations, consent, size limit or chat.

`//lib:group.star` adds those. Its commands:

|      Command      | Lane |                              What it does                              |
|-------------------|------|------------------------------------------------------------------------|
| `invite <player>` | tick | Offers a place in your group; the offer lasts one minute.              |
| `accept`          | tick | Joins the inviter's group, forming it if needed.                       |
| `leave`           | tick | Leaves your group.                                                     |
| `kick <member>`   | tick | Leader only: removes a member.                                         |
| `leader <member>` | tick | Leader only: hands over leadership.                                    |
| `disband`         | tick | Leader only: ends the group.                                           |
| `group`           | fast | Lists members with level and health.                                   |
| `gtell <text>`    | fast | Speaks to the group (alias `gt`), and sends a `Group.Chat` GMCP event. |

The pending invite is kept as two attributes on the invited player. `gtell` sends both a text line and a `Group.Chat`
event, `{"talker", "text"}`, so a client without GMCP still sees the message. Clients also receive the engine's
`Char.Group` state package, which lists the members.

---

## 5. Forgetting a Fight with `on_disengage`

`on_disengage` fires when an engaged actor has no opponent left. `ctx.event` is `"flee"`, `"death"` or `"stop"`. The
engine clears nothing itself, so the template registers a global hook:

```python
def _on_disengage(ctx):
    # A mob that loses its last opponent forgets who fought it.
    if not ctx.actor.is_player:
        ctx.clear_contributions(ctx.actor)

global_hook("on_disengage", _on_disengage)
```

After a party flees, a fresh fight against the same mob starts from zero, so the old healer is not paid for work done in
an earlier fight.

---

## 6. Testing a Party

`zones/tutorial/group_test.star` forms a party of a fighter, a healer and a buffer. The healer casts `soothe` and the
buffer casts `ward`, two ally spells in the arcane school. The test checks that all three gain experience and that the
gains add up to exactly the pool:

```python
fighter.do("kill dummy")
buffer.do("cast ward Tess")
healer.do("cast soothe Tess")
w.run(pulses = 400)
t.eq(fighter.xp + healer.xp + buffer.xp, _pool(L, L))
```

Help counts only while the fighter is engaged, so both casts come after `kill` and before the death. The tests do not
assert who earned the most, since that depends on rolls.

---

## Reference Links

* **Reference Implementation:** [`template-world/lib/group.star`](../../lib/group.star) and `xp_share` in
  [`rules/combat.star`](../../rules/combat.star)
* **Arcane spells:** [`template-world/skills/arcane/spells.star`](../../skills/arcane/spells.star)
* **Specification:** [SPEC.md](../../../docs/SPEC.md), the `xp_share` rules export and the groups section
