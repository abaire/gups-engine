# 03. Items, Equipment, and Containers

Items populate the world as weapons, armor, quest keys, consumables, and containers. This tutorial covers how items are
declared, equipped, interacted with through hooks, and stored.

---

## 1. Declaring an Item

Items are declared with `item()`. At minimum, an item specifies its label identifier, a display name, and a description:

```python
# zones/tutorial/items.star
item("brass_key",
     name="a brass key",
     # Keywords determine what players type: "get key" or "examine brass"
     keywords=["brass", "key"],
     desc="A small brass key stamped with an anvil.",
     )
```

### Keyword Matching Rules

* When a player types `get key`, the engine matches `key` against the item's `keywords`.
* By default, `keywords` automatically defaults to the words in `name` minus common articles (`"a"`, `"an"`, `"the"`).
* You can explicitly supply `keywords` to provide synonyms (e.g. `keywords = ["draught", "potion", "flask"]`).

---

## 2. Weapons and Combat Gear

Weapons are configured using combat properties that the world's rules module evaluates during combat rounds:

```python
item("practice_sword",
     name="a practice sword",
     desc="A blunt wooden sword, good for nothing but practice.",
     kind="melee",  # melee | ranged | armor | consumable
     wield="wield",  # equipment slot (aliased to wear)
     dice="1d6",  # weapon damage dice
     damage_type="bludgeon",  # must match a declared damage type in MODULE.gups
     weight=3,
     )
```

* **Slots:** `wield = "wield"` specifies the hand slot. Players equip it by typing `wield sword`.
* **Damage Types:** The `damage_type` must match one of the types declared in `MODULE.gups` (or `DAMAGE.bludgeon`). If
  omitted, it defaults to the world's `unarmed_damage_type`.
* **Ranged Weapons:** Set `kind = "ranged"` and assign a `range` (`"near"` or `"far"`).

---

## 3. Armor, Stat Deltas, and Item Modifiers

Items worn by a character can alter their capabilities in two distinct ways:

### A. Static Stat Deltas: `stat_deltas`

`stat_deltas` modifies character base stats (such as `str`, `dex`, `con`, `armor`) while equipped:

```python
item("leather_cap",
     name="a leather cap",
     desc="A scuffed leather cap. It smells of the last person who wore it.",
     kind="armor",
     wear="head",  # `wear cap`; slots come from rules' equipment_slots()
     stat_deltas=stats(con=1),
     )
```

* **Slot Allocation:** `wear = "head"` places the item in the head slot. If you assign a slot family like `wear =
  "finger"`, the engine equips it to the first open finger slot (`finger_1`, `finger_2`).
* **Stat Deltas:** `stat_deltas = stats(...)` modifies the wearer's stats as long as the item remains equipped. When
  removed, the bonus is automatically subtracted.

### B. Dynamic Rules Modifiers: `modifiers`

Items can also attach dynamic rules modifiers that participate directly in the rules calculation chain (such as boosting
damage, hit chance, or initiative) while equipped:

```python
def _heavy_strike(ctx, base):
    # Adds +2 raw damage to attacks made by the wearer
    return base + 2

item("spiked_gauntlets",
     name="spiked gauntlets",
     desc="Iron gauntlets lined with wicked studs.",
     wear="hands",
     modifiers={
         MODIFIER.damage: _heavy_strike,
     },
     )
```

* **Execution:** Item modifiers run as the final stage of the modifier chain (after zone, region, room, and affect
  modifiers), evaluated in `equipment_slots()` order.
* **Inspecting:** Just like room modifiers, equipped item modifiers can be traced during combat with `@trace rules`.

Modifiers can also change recovery lag (`MODIFIER.advance_lag`, `MODIFIER.retreat_lag`, `MODIFIER.retreat_fail_lag`, and
`MODIFIER.flee_fail_lag`). Like other modifiers, they receive the rules export's `ctx` (such as `ctx.room`), and while
intermediate steps may go below 0, the final lag value is clamped so it is never below 0. For example, the tar pit's
slow footwork comes from the rules module doubling the lag in rooms with the `sticky_floor` tag (tutorial 02 §7); these
boots inspect `ctx.room` to cancel that extra lag:

```python
def _sure_footed(ctx, base):
    # Modifiers see the same ctx as the rules export, so the boots can check the room.
    # In a sticky room they cancel the rules' doubled footwork lag; elsewhere they change nothing.
    return base // 2 if "sticky_floor" in ctx.room.tags else base

item("tar_walker_boots",
     name="a pair of tar-walker boots",
     keywords=["boots"],
     desc="Broad, flat-soled boots that spread your weight over the muck.",
     kind="armor",
     wear="feet",
     # Lag modifiers take the pulses the rules export returned and return new pulses.
     modifiers={
         MODIFIER.advance_lag: _sure_footed,
         MODIFIER.retreat_lag: _sure_footed,
     },
     )
```

### Defender Modifiers: `defend_hit`, `receive_damage`, and `mitigate`

Three modifier points run on the *defender's* chain — the chain of the actor being hit — so an equipped item guards its
wearer rather than empowering it. Where each runs:

* `MODIFIER.defend_hit` adjusts an auto-attack's or enemy spell's hit chance after the attacker's `to_hit` chain; it
  takes the attacker's result as its base. When rolling for a spell, `ctx.skill` is set (and `ctx.weapon` and `ctx.slot`
  are `None`).
* `MODIFIER.receive_damage` softens the raw damage after the attacker's `damage` chain and before armor and resists; a
  negative result counts as 0.
* `MODIFIER.mitigate` adjusts the vital losses `mitigate` returned; a negative loss is a gain to that vital, capped at
  its maximum.

On all three, `ctx.defender` is the wearer (the actor being hit) and `ctx.attacker` the one swinging or casting, so a
modifier reads its bearer as `ctx.defender`. Unlike the attacker, a defender's worn weapons count too: a dagger worn in
`wield` carries its modifiers even though it is not the swinging weapon. The tutorial arena's `shadow_cloak` blurs and
wards, and the `siphon_amulet` trades pain for mana:

```python
def _blur(ctx, base):
    # Runs on the wearer's chain when the wearer is hit: ctx.defender is the wearer.
    return base - 15

def _ward(ctx, base):
    # Runs on the wearer's chain before armor and resists: it softens every blow by 1.
    return max(0, base - 1)

item("shadow_cloak",
     name="a shadow cloak",
     keywords=["shadow", "cloak"],
     desc="A hooded cloak that seems to drink the light around its wearer.",
     kind="armor",
     wear="body",
     modifiers={
         MODIFIER.defend_hit: _blur,
         MODIFIER.receive_damage: _ward,
     },
     )

def _siphon(ctx, base):
    # Moves a tenth of the hp loss (at least 1 while any hp is lost) into a mana gain.
    moved = max(1, base.hp // 10) if base.hp > 0 else 0
    return vitals(hp=base.hp - moved, mana=base.mana - moved)

item("siphon_amulet",
     name="a siphon amulet",
     keywords=["siphon"],
     desc="A dark pendant that hums when its wearer is struck.",
     wear="neck",
     modifiers={MODIFIER.mitigate: _siphon},
     )
```

Trace these with `@trace rules`: a defender's chain continues the line after a `defender:defend_hit`,
`defender:receive_damage`, or `defender:mitigate` step.

### Spells that miss

An `enemy` spell rolls to land on each victim through the same chain as a swing: the `to_hit` rules export, the caster's
`to_hit` chain, then the victim's `defend_hit` chain. The rules tell the two apart by `ctx.skill`, which is set for a
spell (with `ctx.weapon` and `ctx.slot` `None`) and `None` for a swing. The template's `to_hit` hands spells to a helper
in `rules/combat.star` that pits the caster's `int` against the victim's `wis`, and reads the skill's free-form `kind`
to let area spells through:

```python
def _spell_hit(ctx):
    if ctx.skill.kind == "area":
        return 100
    return _clamp(70 + (ctx.attacker.int - ctx.defender.wis) * 2, 5, 95)
```

A chance of 100 or more always lands without a roll, so return 100 for spells that should never miss. A victim the spell
doesn't land on resists: it gets neither the skill's `effect` nor its `resolve`, but the caster still pays the cost and
lag, and the two are engaged in combat. `@trace rules` ends the line with `LAND`, `RESIST`, or `auto LAND`.

Because `defend_hit` runs for spells too, the `shadow_cloak` above blurs spells as well as swings. A modifier meant only
for blows checks `ctx.skill` first:

```python
def _blur(ctx, base):
    if ctx.skill:
        return base
    return base - 15
```

### Proficiency: success and power

When a skill finishes its warmup, the engine evaluates whether the cast succeeds before any combat starts or effects
apply. The resolution process uses two exports from the `skills` feature:

1. **`rules.skill_check(ctx)`**: Evaluates the percent chance (0–100+) that the caster successfully channels the skill,
   followed by the caster's `skill_check` modifier chain. A chance of 100 or higher succeeds automatically without a
   roll.
2. **Failure handling**: If the d100 roll fails, the spell fizzles immediately. The caster receives `cast.failed.self`
   ("You fail to cast {skill}."), onlookers receive `cast.failed.room` ("{actor}'s spell fizzles."), and the caster pays
   the skill's vital cost and lag. No effect applies, custom `resolve` does not run, no landing roll (`to_hit`) is made,
   combat does not start, and `on_cast` is not triggered.
3. **`rules.skill_power(ctx)`**: If the check succeeds, cast power is determined by `rules.skill_power(ctx)` followed by
   the caster's `skill_power` modifier chain (returning a percentage where 100 represents nominal power).

Cast power scales what the effect primitives do, rounded down and never below 1 for a positive amount:

- `damage`: the rolled amount, before the caster's `damage` chain. Bonus damage parts are not scaled.
- `heal`: the rolled amount restored.
- `stun`: the stun duration in pulses.
- `apply_affect`: the duration, and the applied affect remembers the power as `affect.power`.
- `summon`: the duration of the summoned mobs, not the count.

Stat deltas, modifiers, `tick_every` and the like are not scaled; read `ctx.affect.power` to scale those yourself.

#### Reading `ctx.affect.power` in Modifiers

Modifiers contributed by an affect can inspect `ctx.affect.power` to adjust their contribution based on how strongly the
spell was cast. For example, a levitation buff might grant dodge evasion scaled by the spell's power:

```python
def _levitate_evasion(ctx, base):
    # ctx.affect.power is 100 for a standard cast, granting +10 evasion.
    return base + (ctx.affect.power // 10)
```

#### Custom `resolve` and `ctx.summon`

A skill's custom `resolve` can read the cast's power as `ctx.power`, and `ctx.summon` spawns mobs that belong to the
caster. The verb is not scaled by power, so a `resolve` that wants the duration to follow it passes the scaled value
itself, and can pick the mob by proficiency:

```python
def _call_beast(ctx):
    prof = ctx.actor.skills.get(":call_beast", 0)
    label = ":bear" if prof >= 80 else ":wolf" if prof >= 40 else ":dog"
    ctx.summon(ctx.actor, label, duration = 6 * ctx.power)
```

#### Item Power and Modifiers

Items with declarative `use_effect`s or weapon/armor procs run at `item_power`, evaluated via `rules.item_power(ctx)`
(from the kernel) and the user's `item_power` modifier chain. An artificer could boost all item activation strength
through an affect modifier:

```python
def _artificer_synergy(ctx, base):
    # Enhances item power by +1% per point of engineering proficiency.
    engineering = ctx.actor.skills.get(":engineering", 0)
    return base + engineering
```

Unlike cast spells, **procs never roll `skill_check` or `skill_power`**. Item procs evaluate `item_power`, item instance
affect procs run at `affect.power * item_power // 100`, and actor-affect procs run directly at the affect's stored
`affect.power`.

### Bonus damage: `bonus_damage` and `damages(...)`

A `bonus_damage` modifier adds typed parts to everything its bearer deals. It runs on the attacker's chain, takes a map
from damage type to amount — empty at the start of a swing — and returns it with entries added. The `damages(...)`
constructor builds such a value, and `+` on two typed values of the kind adds their entries. Every entry above 0 becomes
a damage part of its own: it runs the defender's `receive_damage` chain and `mitigate` with its own type, so armor and
resists apply to it separately, and the parts are then applied with the hit as one damage event, which is what
`on_damage`'s `ctx.parts` lists, the hit's type first. While a swing is in flight, only the swinging weapon's own
`bonus_damage` modifier runs among worn weapons — the same filter as `to_hit` and `damage` — so a neck amulet is always
in the chain and an off-hand dagger is not.

The tutorial's `flaming_sword` burns on its own swings only. Outside a swing — a spell, for instance — `ctx.slot` is
`None`, so the guard leaves the base alone and the fire never reaches the spell's damage:

```python
def _flame_edge(ctx, base):
    if ctx.slot == None:
        return base
    return base + damages(fire = ctx.roll("1d2"))

item("flaming_sword",
     name="a flaming sword",
     keywords=["flaming", "sword"],
     kind="melee",
     wield="wield",
     dice="1d6",
     damage_type="slash",
     modifiers={MODIFIER.bonus_damage: _flame_edge},
     )
```

The `ember_amulet` is unguarded, so everything its wearer deals carries a little fire, spells included. Its fire adds to
the sword's when the wearer wields both:

```python
def _embers(ctx, base):
    return base + damages(fire = 1)

item("ember_amulet",
     name="an ember amulet",
     keywords=["ember"],
     wear="neck",
     modifiers={MODIFIER.bonus_damage: _embers},
     )
```

From the defender's side, `receive_damage` already runs once per part with `ctx.damage_type` set to that part's type, so
a multiplicative effect can key on it. The tutorial's `oily_rags` double a fire part — a fireball and a flaming sword's
fire alike — while their armor turns aside the physical parts:

```python
def _flammable(ctx, base):
    return base * 2 if ctx.damage_type == DAMAGE.fire else base

item("oily_rags",
     name="sketchy oil-soaked rags of protection",
     keywords=["rags"],
     kind="armor",
     wear="arms",
     stat_deltas=stats(armor=4),
     modifiers={MODIFIER.receive_damage: _flammable},
     )
```

If you only wanted to lower the wearer's fire resist instead, `resist_deltas = resists(fire = -100)` does it additively
on the resists; a `receive_damage` modifier multiplies the part before armor and resists.

### Procs: `procs`

A **proc** is something that may happen during an auto-attack swing: a burst of fire from a blade, thorns that rake an
attacker. Items, item affects and `apply_affect` take a `procs` list of `proc(...)` values:

```python
item("ember_dagger",
    name = "an ember dagger",
    wield = "wield",
    dice = "1d4",
    damage_type = "pierce",
    procs = [proc(on = "hit", chance = 0.25, effect = damage(dice = "1d4", type = DAMAGE.fire))],
)
```

`on` picks the trigger:

* `"hit"`: the bearer's swing landed. It fires after the swing's damage.
* `"struck"`: the bearer was hit. It fires right after the attacker's `hit` procs.
* `"avoid"`: a swing at the bearer missed.

A worn weapon's procs count only on its own swing, so an off-hand dagger doesn't proc when the main hand lands. Armor,
rings and other worn items count on every swing, like the defender modifiers above.

A proc gives one of `effect` (an effect primitive), `skill` (a skill label, in worlds with the `skills` feature) or
`outcomes`. It lands on the other actor in the swing unless you pass `target = "self"` (or `"none"`); a skill proc
follows the skill's own target. `message` replaces the bearer's "Power surges from ..." line:

```python
item("bramble_vest",
    name = "a bramble vest",
    wear = "body",
    stat_deltas = stats(armor = 1),
    procs = [proc(
        on = "struck",
        effect = damage(dice = "1d2", type = DAMAGE.pierce),
        message = "Thorns rake your attacker!",
    )],
)
```

#### Outcome tables

`chance` decides whether a proc triggers at all. An `outcomes` list of `outcome(weight, ...)` entries then picks at most
one result with a single roll. The weights are probabilities that add up to at most 1, and whatever is left over means
nothing happens:

```python
item("cracked_wand",
    name = "a cracked wand of many colors",
    wield = "wield",
    dice = "1d2",
    procs = [proc(on = "hit", outcomes = [
        outcome(0.05, effect = damage(dice = "3d6", type = DAMAGE.fire),
                message = "A ball of fire bursts from the wand!"),
        outcome(0.40, effect = apply_affect(name = "frightened", duration = 12,
                                            stat_deltas = stats(dex = -2)),
                message = "The wand flashes a terrifying violet!"),
        outcome(0.44, effect = stun(2), message = "A dazzling arc of light leaps from the wand!"),
        # The remaining 10% does nothing.
        outcome(0.01, effect = damage(dice = "1d12", type = DAMAGE.fire), target = "self",
                message = "The wand cracks open and explodes in your hand!"),
    ])],
)
```

#### Rules to remember

* A skill proc skips the skill's cost, warmup and lag, and the bearer needn't know the skill, but it still has to pass
  `cast_allowed`: a bearer who may not cast doesn't get skill procs either.
* Procs fire only from auto-attack swings, never from spells, `use_effect` or other procs' damage, so they can't chain.
* A proc's `apply_affect` needs a duration, and procs can't `summon`.
* `@trace rules` (or `w.trace()` in a test) prints one `proc ...` line per roll. In tests, `w.force_rng(fractions =
  [0.0])` makes the next proc trigger instead of searching for a lucky seed.

## 4. Consumables, Charges, and Declarative Use

Items can have limited uses or trigger effects when used. The `use` command finds items worn, carried, or on the ground
within reach.

### A. Declarative Use: `charges` and `use_effect`

Simple consumables (like potions) or charged magical items (like wands) can be declared without writing custom hook
functions:

```python
item("healing_draught",
     name="a healing draught",
     keywords=["draught", "potion"],
     kind="consumable",
     charges=1,
     use_effect=heal(dice="2d4+6", vital="hp"),
     )
```

* **`charges`:** The number of uses the item has (at least 1), or `None` for unlimited uses. Each completed `use` spends
  one charge (or one item from a stack). An item with 0 charges left cannot be used.
* **`empty`:** What happens when the last charge is spent. Defaults to `"destroy"`, which removes the item (or one item
  from a stack). Setting `empty = "keep"` leaves the item in inventory at 0 charges until recharged. Any other string
  names one of the item's `variants`, transforming the item into that variant when exhausted:

```python
# A wand that stays in inventory when empty
item("wand",
     name="a wand",
     charges=3,
     empty="keep",
     use_effect=damage(dice="1d4", type=DAMAGE.fire),
     use_target="enemy",
     )

# A flask that becomes an empty flask once it has been drunk twice
item("empty_flask",
     name="an empty flask",
     keywords=["flask"],
     )

item("flask",
     name="a flask of elixir",
     keywords=["flask", "elixir"],
     charges=2,
     empty="spent",
     variants={"spent": ":empty_flask"},
     use_effect=heal(dice="1d6", vital="hp"),
     )
```

* **`use_effect`:** An effect primitive (`heal`, `damage`, `apply_affect`, `dispel`, `stun`, `summon`, or `sequence`)
  applied when the item is used, with the user as caster. When an item has a `use_effect` and no `on_use` hook, the
  engine automatically narrates the action (`"You use {item}."` to the user, `"{actor} uses {item}."` to onlookers).
* **`use_target`:** Specifies who or what `use_effect` acts on (defaults to `"self"`):
  * `"self"`: The user. Specifying a target word is refused.
  * `"ally"`: The named actor in the room, or the user if no target is specified.
  * `"enemy"`: The named actor in the room, or the user's primary combat target. With neither, the use is refused ("Use
    {item} on whom?").
  * `"item"`: A named item worn, carried, or on the ground in the room.
  * `"none"`: The effect runs without a target (e.g. for `summon`).
* **`use_lag`:** Recovery lag in pulses (at least 0, default 0) applied to the user after a successful use.

### B. Custom Item Hooks

For complex behaviors, you can attach lifecycle hooks. In item hooks, `ctx.self` is the item entity, and `ctx.actor` is
the character interacting with it. Verbs such as `ctx.heal(ctx.actor, 10, "hp")` or `ctx.destroy(ctx.self)` act on them
directly.

* `on_use`: Runs when a player types `use <item>`. If the item also has a `use_effect`, the effect runs first, then
  `on_use`.
* `before_use`: Can return `ctx.deny("Reason")` to prevent usage (e.g., if already at full health). A denied use costs
  zero charges and zero lag.
* `on_get`: Fires when a player picks up the item from a room or container.
* `on_drop`: Fires when a player drops the item into a room.

---

## 5. Containers

An item can act as a container (chest, backpack, sack) by providing `container()`:

```python
item("satchel",
     name="a leather satchel",
     desc="A sturdy travel bag with brass buckles.",
     container=container(
         capacity=10,  # Maximum number of individual items
         max_weight=20,  # Maximum total weight of contents
         state="closed",  # "open", "closed", or "locked"
         key=None,  # Optional item label required to unlock
     ),
     )
```

Players can `open satchel`, `put sword in satchel`, and `look in satchel`.

---

## 6. Spawning and Item Custody

Items are created and transferred through several mechanisms:

* **Giving directly to an actor:**

  ```python
  ctx.give(ctx.actor, ":brass_key")
  ```

  Spawns a new instance of `:brass_key` straight into the player's inventory (used in quests and NPC rewards).

* **Placing in a room at reset:** Rooms can restock items during their `on_reset` hook:

  ```python
  def _restock_armory(ctx):
    if not ctx.room_has_item(":practice_sword"):
          ctx.spawn(":practice_sword", at = ctx.self)
  ```
* **Custody Logging:** Every item transfer between rooms, containers, and players is recorded in SQLite (`item_events`)
  to prevent item duplication or loss.

---

## 7. Item Affects, Tags, and Prototype Transforms

In addition to static item properties, items can host affects and dynamic tags, and swap their underlying prototype in
place using transforms.

### A. Prototype Affects: `item_affect` and `affects`

Items can define innate affects using `item_affect()`. Unlike actor affects which attach directly to characters, item
affects live on the item and propagate their bonuses or penalties to the character in custody of the item:

```python
item("lucky_charm",
     name="a lucky charm",
     desc="A polished rabbit's foot on a brass chain.",
     affects=[
         item_affect("luck", applies="carried", stat_deltas=stats(dex=1)),
     ],
     )
```

The `applies` parameter determines propagation scope:

* **`applies = "worn"`:** The affect is active only while the item is equipped in a valid equipment slot (such as
  `wield`, `body`, or `neck`).
* **`applies = "carried"`:** The affect is active as long as the character has custody of the item, whether carried
  directly in inventory or stowed inside containers and backpacks.

### B. Dynamic Item Affects: `ctx.apply_affect` and `ctx.remove_affect`

Scripts and spells can apply dynamic affects directly to item instances at runtime using `ctx.apply_affect`:

```python
# Applies a temporary keen edge (+1 dex while wielded) for 600 pulses (2.5 minutes at 250 ms a pulse)
ctx.apply_affect(sword, "keen", 600, applies="worn", stat_deltas=stats(dex=1))

# Remove the affect early
ctx.remove_affect(sword, "keen")
```

Setting `duration = None` makes the item affect permanent until explicitly removed.

#### Item-Targeting Skills

Skills with `target = "item"` apply their effects directly to the target item. For example, an `enchant_weapon` spell:

```python
skill("enchant_weapon",
      kind="spell",
      target="item",
      cost=vitals(mana=10),
      channels=["spell"],
      effect=apply_affect("keen", 600, applies="worn", stat_deltas=stats(dex=1)),
      )
```

When a player types `cast enchant_weapon sword`, the `apply_affect` effect primitive attaches the `"keen"` affect to the
sword instance.

### C. Per-Instance Tags: `ctx.add_tag` and `ctx.remove_tag`

While prototypes declare default tags via `tags = ["valuable", "fragile"]`, scripts and plugins can modify tags on
individual item instances:

```python
# Mark an item instance as blessed
ctx.add_tag(item, "blessed")

# Strip an existing tag
ctx.remove_tag(item, "cursed")
```

An item's effective tags (`item.tags` or `item.has_tag(tag)`) reflect its prototype tags plus any added tags, minus any
removed tags.

### D. Prototype Variants and In-Place Transforms: `variants` and `ctx.transform`

An item prototype can declare variants linking identifier keys to alternate item prototypes:

```python
item("cursed_dagger",
     name="a cursed dagger",
     desc="A wicked blade inscribed with sinister runes.",
     kind="melee",
     wield="wield",
     dice="1d4",
     damage_type="pierce",
     tags=["cursed"],
     variants={
         "uncursed": ":silver_dagger",
     },
     )

item("silver_dagger",
     name="a silver dagger",
     desc="A polished silver blade.",
     kind="melee",
     wield="wield",
     dice="1d4+1",
     damage_type="pierce",
     )
```

When an event or spell purifies the item, `ctx.transform` swaps its prototype in place:

```python
def _purify_weapon(ctx):
    clean_proto = ctx.self.variants.get("uncursed")
    if clean_proto:
        ctx.transform(ctx.self, clean_proto)
        ctx.send(ctx.actor, "The sinister runes fade, revealing polished silver.")
```

#### Why `transform` is Preferred over Tag Deltas

Transforming an item swaps its prototype while preserving its instance entity ID, container or inventory location,
custom attributes, and per-instance affects. This is preferred over simply removing the `cursed` tag because:

1. **Different Base Properties:** The clean prototype can have distinct names, descriptions, damage dice, stats, and
   keywords that a tag removal cannot express.
2. **Clean Stacking:** Items with instance-level tag deltas cannot stack with pristine prototype items. `transform`
   restores a clean prototype baseline without leaving stray per-instance tag changes behind.

---

## 8. Weight, Carry Limit, and Encumbrance

An item's `weight` matters only because the rules give it meaning. Two rules exports turn it into a graded penalty:

* **`carry_limit(ctx)`** is the hard cap that `get`, `give` and mob scavenging enforce. The template's is `ctx.actor.str
  \* 10`, so a strength-10 player picks up at most 100 weight.
* **`encumbrance_level(ctx)`** gives the actor's encumbrance level, an int of at least 0 (0 means unencumbered). The
  engine gives a level no meaning: how many there are, where they start, and what they cost all belong to the world. The
  export sees `ctx.limit` (the actor's `carry_limit`) and `ctx.carried` (carried and worn weight, containers' contents
  included), plus whatever else of `ctx.actor` it reads. The template grades the share of the limit in
  `rules/combat.star`:

```python
def encumbrance_level(ctx):
    # Tiers by share of the carry limit; a world is free to use race, class or anything on ctx.actor.
    pct = ctx.carried * 100 // ctx.limit if ctx.limit > 0 else 100
    if pct >= 100: return 4
    if pct >= 90: return 3
    if pct >= 85: return 2
    if pct >= 75: return 1
    return 0
```

The level is read back as `ctx.actor.encumbrance_level`, so the world's own exports price it: the template's `move_cost`
and the `advance_lag` / `retreat_lag` exports charge one more per level,

```python
def move_cost(ctx):
    # The vitals spent for walking out of ctx.room. Every encumbrance level costs more.
    return vitals(move = 1 + ctx.actor.encumbrance_level)
```

and `rules.encumbrance(ctx)` supplies stat deltas only while the level is above 0, the template's losing one of dex per
level. A trait or an item changes the cost through a modifier on any modifier point instead: a `MODIFIER.move_cost`
reads the same `ctx.actor.encumbrance_level`,

```python
def _weighted(ctx, base):
    # Heavy gear doubles the step cost from the moment encumbrance begins.
    return base * 2 if ctx.actor.encumbrance_level > 0 else base

item("oiled_harness",
     name="an oiled harness",
     desc="Bridle and girth, oiled and honest.",
     wear="waist",
     modifiers={MODIFIER.move_cost: _weighted},
     )
```

and a room, region, or affect modifier can do the same wherever its chain reaches `move_cost`.

**Per-tier flavor.** When an actor's encumbrance level changes, the engine sends its own generic line (`encumbrance.up`,
`encumbrance.down`, ...), and the world's `rules` package can add its own. A `global_hook("on_encumbrance_change", fn)`
registered by the rules (or lib) package fires once for each change of any actor's level, after the engine has already
told them. The template's `rules/combat.star` uses it to send a heavier flavor line at the top tiers, and only on the
way up:

```python
def _encumbrance_flavor(ctx):
    # Extra flavor for the heavy tiers; the engine already sent its generic line. Fires only on
    # the way up into a heavy level, so lightening never repeats it.
    if ctx.new_encumbrance_level >= 3 and ctx.new_encumbrance_level > ctx.old_encumbrance_level:
        ctx.send(ctx.actor, "Your pack drags at your shoulders, and every step is a labor.")

global_hook("on_encumbrance_change", _encumbrance_flavor)
```

`ctx.old_encumbrance_level` and `ctx.new_encumbrance_level` hold the level before and after the change, so a hook can
act on the tier it crossed into; a change of stat deltas that leaves the level alone fires nothing.

---

## Reference Links

* **Reference Implementation:** [`template-world/zones/tutorial/items.star`](../../zones/tutorial/items.star)
* **Curses and Containment:** For complete plugin examples using item restrictions, containment filters, and transforms,
  see the [Curse Plugin Walkthrough](../../../samples/curse-plugin/docs/tutorials/README.md).
* **Specification:** [SPEC.md §4.2: Content Declarations](../../../docs/SPEC.md#L979) and
  [§3.6: Persistence Schema](../../../docs/SPEC.md#L713)
* **HTML Documentation:** `declarations.html#item` and `declarations.html#container` in `gups docs`.
