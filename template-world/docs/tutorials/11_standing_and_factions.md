# 11. Standing and Factions: Opinions, Grudges, and Disposition Thresholds

Mobs in a living world don't split players into friends and foes once and for all. They belong to factions, hold grudges
against attackers, remember who healed them, and keep disliked strangers out of town.

This tutorial covers the `standing` feature:

1. **Factions:** declaring groups and what they think of each other, in `//lib:factions.star`.
2. **Standing and disposition:** the records the engine keeps, and the opinion it works out from them.
3. **Standing events and caps:** the `standing_change` rules export and how the template stops farming.
4. **Disposition thresholds:** gating `aggressive`, `pursue`, `assist`, `guard` and `follow` on what a mob thinks.

---

## 1. Declaring Factions

A faction is a label for a group (a town, a guild, a clan), declared with `faction()`:

```python
faction("townsfolk",
    standing = {":goblins": -80},
    doc = "Ordinary town citizens, merchants, and guards.",
)

faction("goblins",
    standing = {":townsfolk": -80},
    doc = "Wild goblin tribes of the wilderness.",
)
```

- `standing` maps other factions to this faction's **declared** opinion of them, from `-100` (hatred) to `100` (ally).
  Opinions are one-way, so mutual dislike is declared on both sides.
- Every mob prototype is also a faction of its own, named by its label, so a faction can hold an opinion of one kind of
  mob without declaring anything else.

Mobs join factions with `mob(factions = [...])`. Without it, a mob belongs only to its own prototype's faction. Players
belong to no faction unless the world's `factions` rules export says otherwise.

```python
mob("shopkeeper",
    name = "the shopkeeper",
    factions = ["//lib:townsfolk"],
    behaviors = [sentinel(), aggressive(toward = "all", disposition_below = -40)],
    ...
)
```

---

## 2. Standing and Disposition

**Standing** is what the engine records. Each record is an amount from `-100` to `100` with a history of the changes
that made it:

- a mob's **personal** record of one actor (a grudge or gratitude), saved with the mob and gone when it dies;
- a **faction** record of one actor or of another faction, saved with the world.

`ctx.standing(holder, subject)` reads one record's amount, or 0 when there is none. The holder is a mob or a faction
label, the subject an actor or a faction label. Declared opinions are not records, so `ctx.standing(":townsfolk",
":goblins")` is 0 until something changes it.

**Disposition** is what one actor thinks of another, worked out from the records. By default it is the sum, clamped to
`-100..100`, of four parts:

1. **`declared`:** each of the holder's factions' declared opinion of each of the subject's factions.
2. **`faction_to_actor`:** each of the holder's factions' record of the subject.
3. **`faction_to_faction`:** each of the holder's factions' record of each of the subject's factions.
4. **`personal`:** the holder's own record of the subject, when the holder is a mob.

`ctx.disposition(holder, subject)` asks for it. A world can change the formula with a `disposition(ctx)` rules export,
which gets the parts as `ctx.disposition_parts`, and plugins can adjust the result. Behaviors always use disposition.

---

## 3. Standing Events and the `standing_change` Rules Export

When something notable happens, the engine raises a standing event: `attack`, `kill`, `heal`, `help`, `assist` or
`give`. Worlds raise their own, such as a theft or a donation, with `ctx.standing_event(...)`. In each one `ctx.actor`
is the actor **affected** and `ctx.target` the actor **who acted**. The `standing_change(ctx)` export in
[`rules/standing.star`](../../rules/standing.star) turns the event into a list of `standing_delta(holder, subject,
amount)` changes:

```python
def standing_change(ctx):
    if ctx.event == "attack":
        if ctx.actor.is_player:
            return []
        return [standing_delta(ctx.actor, ctx.target, -50)]

    if ctx.event == "kill":
        deltas = []
        killer_factions = ctx.factions_of(ctx.target)
        for f in ctx.factions_of(ctx.actor):
            deltas.append(standing_delta(f, ctx.target, -50))
            for g in killer_factions:
                deltas.append(standing_delta(f, g, -5))
        return deltas

    if ctx.event in ("heal", "help"):
        if ctx.actor.is_player:
            return []
        gain = _personal_gain(ctx, ctx.actor, ctx.target, 5)
        return [standing_delta(ctx.actor, ctx.target, gain)] if gain > 0 else []
    ...
```

A player keeps no personal records, so the personal changes apply only when the affected actor is a mob.

### Capping Gains

The engine applies whatever the export returns and caps nothing itself. Without limits, a player could heal a damaged
mob over and over until it adores them. The template limits gratitude two ways:

```python
STANDING_WINDOW = 14400
RATE_CAP = 10
GRATITUDE_CEILING = 25

def _personal_gain(ctx, mob, subject, amount):
    since = ctx.pulse - STANDING_WINDOW
    recent = 0
    for event in ("heal", "help"):
        recent += ctx.standing_history(mob, subject, since = since, event = event).gained
    headroom = GRATITUDE_CEILING - ctx.standing(mob, subject)
    return min(amount, headroom, RATE_CAP - recent)
```

- **The ceiling** reads the record itself with `ctx.standing`: gratitude never lifts a personal record above 25, however
  long a player keeps at it.
- **The rate cap** reads `ctx.standing_history`, which sums the gains and losses of a record since a pulse, optionally
  only those from one `event` or caused through one mob (`via`). At most 10 points of gratitude count per hour.

The `donation` world event shows the same pattern for a faction: `_faction_gain` passes `via = ctx.actor`, so donating
to the same mob again and again earns at most 10 points with its factions per hour.

---

## 4. Disposition Thresholds on Behaviors

Built-in behaviors can act only on actors the mob feels strongly enough about:

|     Behavior argument     |       Applies to       |                                     Meaning                                     |
|---------------------------|------------------------|---------------------------------------------------------------------------------|
| `disposition_below = -40` | `aggressive`, `pursue` | Only attack or chase actors the mob's disposition toward is below -40.          |
| `disposition_above = 50`  | `guard`                | Also let through anyone the guard's disposition toward is above 50.             |
| `disposition_above = 0`   | `assist`, `follow`     | Only help or follow actors above 0; `assist` never fights a foe above 0 either. |

### Worked Examples in the Tutorial Zone

- **The shopkeeper (`aggressive(toward = "all", disposition_below = -40)`):** starts neutral and trades with everyone.
  Attack it and it records a `-50` grudge, so when you come back it attacks on sight. A groupmate who only healed you
  gets a `-10` grudge, stays above the threshold, and can still shop.
- **The gate guard (`guard(exit = DIR.north, disposition_above = 50)`):** blocks anyone it doesn't trust. A buff counts
  as help only while the guard is fighting, and even then the rate cap and the ceiling keep a buff-spammer far below 50.

---

## 5. Reference Links

- **Factions:** [`template-world/lib/factions.star`](../../lib/factions.star)
- **Standing rules and caps:** [`template-world/rules/standing.star`](../../rules/standing.star)
- **Tutorial mobs:** [`template-world/zones/tutorial/mobs.star`](../../zones/tutorial/mobs.star)
- **Tests:** [`template-world/zones/tutorial/standing_test.star`](../../zones/tutorial/standing_test.star)
- **Engine specification:** [`docs/SPEC.md`](../../../docs/SPEC.md) (§5.14 Standing and Factions)
