# Changelog

Script-facing changes are listed per `api_version`, with before/after snippets for breaking ones.

## api_version 1 (unreleased)

### Effects on items

- Added the `item_affect(name, applies = "worn", stat_deltas = stats(), sense_deltas = {}, modifiers = {}, flags = [])`
  declaration builtin for declaring prototype affects an item carries.
- Added `affects` to `item(...)`: affects every instance carries, built with `item_affect(...)`.
- Added `variants` to `item(...)`: free-form variant names mapped to related item prototype labels, for `transform`.
- A key that gains instance affects or tags in play leaves the keyring and shows in `inventory` until they are gone.
- Added `ctx.open_container(item)`, `ctx.close_container(item)`, `ctx.lock_container(item)` and
  `ctx.unlock_container(item)`. They mirror the door verbs and print nothing. `lock_container` fails on a container
  whose prototype has no `key`.
- Added `applies = "worn"` to `apply_affect(...)`: allowed only on `item` skills, setting whether the affect reaches the
  holder while worn or carried (`"worn"` or `"carried"`).
- Allowed `duration = None` in `apply_affect(...)` on `item` skills to attach a permanent item affect.
- An `item` skill's `effect` acts on the target item; it may use `apply_affect`, plugin primitives, and `sequence`
  combining them. Any other primitive fails the load.
- `ctx.apply_affect(target, ...)` and `ctx.remove_affect(target, name)` accept an item as the target; on an item,
  `apply_affect` takes `applies = "worn" | "carried"` (default `"worn"`) and allows `duration = None` for permanent
  affects.
- Added `ctx.add_tag(item, tag)` and `ctx.remove_tag(item, tag)`, returning whether the effective tags changed.
- Added `ctx.transform(item, label)` to swap an item's prototype in place, keeping instance attributes, affects and
  tags.
- Added `affects`, `has_tag(tag)` and `variants` to the item view; `item.tags` reflects effective tags.
- Added the `proto_affect` view for read-only views of prototype affects on items.
- Added `origin` (`"instance"` or `"proto"`) and `applies` (`"worn"` or `"carried"`, or `None` on actor affects) to
  affect views; `affect.host` is the actor or item carrying it.
- `affect.expires_at` and `affect.remaining` are `None` for an affect that never expires, including untimed
  `equip_affects`.
- `actor.flags` and `actor.has_flag(flag)` include flags from item affects.

### Experience and levels

- Added the `on_level_up(ctx)` rules export: called when an actor advances a level, returning `None` or a `struct` with
  optional `stats`, `max` and `current` deltas. It is required when the `leveling` feature is enabled and forbidden when
  it is off.
- Added the `leveling.level_down` message: sent when an actor loses one or more levels (`You have fallen to level
  {level}.`).
- Added optional `level_loss: int` to the `on_player_death(ctx)` return dictionary: the number of levels lost after the
  respawn. Allowed only when the `leveling` feature is enabled.
- Added `level_mode = "auto" | "deferred"` to `module(...)` in `MODULE.gups` (defaults to `"auto"`): chooses whether
  earned levels apply immediately or wait for an explicit script call (`ctx.level_up`). Requires the `leveling` feature.
- Added the `leveling.ready` message: sent in deferred level mode when an actor becomes eligible to advance (`You feel
  ready to advance.`).
- Added the read-only `pending_levels` field to the actor view: how many levels the actor has the XP for and has not
  been given. Requires the `leveling` feature.
- Changed the rules export to `xp_to_level(ctx, level)`, passing the actor whose threshold is being queried. `ctx.rng`,
  `ctx.roll` and `ctx.pulse` are not available in it, because it must give the same answer every time.
- Added the leveling ctx verbs: `ctx.award_xp(actor, amount)`, `ctx.take_xp(actor, amount)`, `ctx.level_up(actor)` and
  `ctx.level_down(actor, count=1)`. Each requires the `leveling` feature.
- Added leveling members to the plugin facade: `awardXp(actor, amount)`, `takeXp(actor, amount)`, `levelUp(actor)` and
  `levelDown(actor, count=1)` on `PluginWorld`, and `val pendingLevels: Int` on `PluginActor`. Each requires the
  `leveling` feature.
- Added the harness verbs to the `w` handle: `w.award_xp(actor, amount)`, `w.take_xp(actor, amount)`,
  `w.level_up(actor)` and `w.level_down(actor, count=1)`. Each requires the `leveling` feature.
- Added the `on_level_change` hook (attachable to mob, room and item) and its context fields `ctx.old_level` and
  `ctx.new_level`. Requires the `leveling` feature.
- Added the `leveling.score_ready` message: printed by `score` on the line after the level line while an actor has
  pending levels, in either level mode (`You are ready to advance.`).
- Changed the template world's rules: `xp_for_kill` pays 50 per victim level, scaled from 0% (victim 5 or more levels
  below) to 150% by the level difference; `xp_to_level` is the total-XP curve `500 * level * (level + 1)` (1000, 3000,
  6000, ...); `on_level_up` raises the hp, mana and move maxima and heals by the same amount; and `on_player_death`
  takes 10% of the XP earned within the current level, with a commented variant that also takes levels.

### Senses and perception: `on_perceive`, `senses`, `sense_deltas`, `ctx.emit`

Every actor, player or mob, has a sense score per channel. `sight` and `sound` default to 1 and declared channels to 0;
`mob(senses = {...})` and `starting_stats` merge over the defaults and take any score of 0 or more. A score of 0 senses
nothing on that channel, even in the same room. `"hearing"` is gone; use `"sound"`. `sense_deltas` on `item(...)`,
`apply_affect(...)`, `ctx.apply_affect(...)` and plugin traits (`PluginTrait.senseDeltas`) change the score while they
last. `actor.senses` and `actor.base.senses` read it.

`on_nearby` is renamed `on_perceive` and gains `ctx.channel`, `ctx.intensity` and `ctx.cost`. Which events carry, how
far and to whom is now decided by four rules exports, spread over as many files of the `//rules` package as you like (an
export defined in two files is a load error): `sense_cost(ctx)` prices each hop, `can_perceive(ctx)` decides who
notices, `perceive_message(ctx)` is what a player in another room is shown (replacing the engine's fixed "Someone nearby
shouts"), and `door_intensity(ctx)` is how loud a door or container is. All four are required.

`ctx.emit(channel, event, intensity = 1, text = None, room = None, actor = None)` raises a perceivable event from a
room, item or spell. `open`, `close`, `lock` and `unlock` on doors and containers fire `before_*` (vetoable) and `on_*`
hooks, and `door(tags = [...])` gives `door_intensity` something to read. `emote` is now seen rather than heard.

`exit.door` is a door view instead of a string, and `exit.key` is unchanged.

```python
# before
mob("wolf", senses = {"hearing": 1}, on_nearby = _heard)
if ctx.room.exit("north").door == "closed": ...

# after
mob("wolf", senses = {"sound": 1}, on_perceive = _heard)
if ctx.room.exit("north").door.state == "closed": ...
```

### Added `advance_lag`, `retreat_lag`, `retreat_fail_lag` and `flee_fail_lag`

The recovery lags of `advance`, a successful `retreat`, a failed `retreat_check` and a failed `flee` are rules exports
instead of fixed engine values. `advance_lag`, `retreat_lag` and `retreat_fail_lag` are required with the `track`
feature and forbidden without it; `flee_fail_lag` goes with `combat`. Each returns the recovery in pulses (an int of at
least 0; 0 means none) and replaces the action's recovery. `ctx.target` is the actor approached, retreated from or the
pinner, and `ctx.band` the band after the step (`"melee"` for a failed retreat); `flee_fail_lag` has neither. A rules
error gives 0.

`get_lag` now rejects negative values like the new exports do (neutral result 0, with a `script_error`); `None` still
means out of reach.

```python
# before: nothing in rules/combat.star; the engine used 2, 2, 4 and 4

# after
def advance_lag(ctx):
    return 2

def retreat_lag(ctx):
    return 2

def retreat_fail_lag(ctx):
    return 4

def flee_fail_lag(ctx):
    return 4
```

### Added `tags` to `room`, `item` and `skill`

Rooms, items and skills take an optional `tags` list of identifiers (`[a-z][a-z0-9_]*`). Tags are stored sorted and
deduplicated on the prototype, read via `.tags` on their respective views, and survive package reload.

```python
room("vault", name = "The Vault", tags = ["treasury", "secure"])
item("heavy_plate", name = "Heavy Plate", wear = "body", tags = ["heavy_armor"])
skill("magic_missile", kind = "spell", target = "enemy", tags = ["arcane"])
```

### Added `traits` to mob prototypes

Mob prototypes can declare plugin-defined traits via `traits = [...]`. The traits are validated against loaded plugins
and apply their stat, vital and resist deltas to the spawned mob. Prototype traits follow package reloads and cannot be
removed in play.

```python
mob("mage_trainer", name = "Mage Trainer", traits = ["mage"])
```

### Actor views show choices, traits, effective maxima and resists

Actor views (`ctx.actor`, `ctx.self`, `ctx.target`, etc.) provide read-only access to character creation choices and
traits: - `choices`: dictionary mapping choice ids to selected option ids. - `traits`: sorted list of effective trait
names. - `has_trait(name)`: checks if the actor carries the given trait. - `max_<vital>` and `resists.<type>`: reflect
effective values (base plus trait deltas). - `base.max_<vital>` and `base.resists.<type>`: reflect the underlying base
values without trait deltas.

### `rules.starting_stats` runs after choices and traits

When spawning players or mobs, `rules.starting_stats` now executes with the actor's choices and traits already set. The
return values configure the base stats and vitals, with trait deltas layered on top. A mob's `ctx.actor.max_<vital>` now
includes its prototype traits' deltas, so a ruleset that returns the prototype's maxima should read
`ctx.actor.base.max_<vital>` instead, as the template does:

```python
max = vitals(hp = _or(a.base.max_hp, 10), mana = a.base.max_mana)
```

### Harness additions: `spawn_player(choices)`, `set_choices`, `grant_trait`, `remove_trait`

Tests can configure character creation choices and modify traits directly: - `w.spawn_player("Name", choices = {"race":
"elf", "class": "mage"})` - `w.set_choices(player, {"class": "cleric"})` - `w.grant_trait(actor, "dwarf")` -
`w.remove_trait(actor, "dwarf")` - `player.choices`, `player.traits`, `player.has_trait("elf")`

### Added the world schema: `module.stats`, `module.vitals`, `module.damage_types`, `module.unarmed_damage_type`, `module.channels` and `module.prompt`

`MODULE.gups` is now required, and declares the names a world's actors are measured by. The engine names no stat, vital
or damage type itself. `stat(name, label)` and `vital(name, label)` add a label; a plain string is the same with the
default label.

```python
module(
    api_version = 1,
    stats = [stat("str", label = "Strength"), stat("dex", label = "Dexterity"), "armor"],
    vitals = [vital("hp", label = "Hit points"), "mana", "move"],
    damage_types = ["bludgeon", "slash", "fire"],
    unarmed_damage_type = "bludgeon",
    channels = ["spell"],
    prompt = "<{hp}hp {mana}m {move}mv> ",
)
```

The channel `spell` is no longer built in: a world that blocks or lists it declares it in `channels`. `ALL` lists the
built-in channels and the declared ones. The schema is fixed per database; a start with different names refuses to run,
and `gups serve --wipe` is the way forward (see below).

### Added `vitals(...)`, `stats(...)`, `resists(...)` and `DAMAGE`

Wherever numbers are keyed by schema names, a constructor checks its keywords against the schema, with a suggestion for
a misspelling. A plain dict is an error that names the constructor. `DAMAGE` is a struct of the declared damage types,
so `DAMAGE.fier` fails at that line.

Before:

```python
skill("fireball", cost = {"mana": 25}, effect = damage(dice = "4d6", type = "fire"))
item("cap", wear = "head", stat_deltas = {"con": 1})
```

After:

```python
skill("fireball", cost = vitals(mana = 25), effect = damage(dice = "4d6", type = DAMAGE.fire))
item("cap", wear = "head", stat_deltas = stats(con = 1))
```

### `mob(hp, mana, move, armor)` are now `mob(vitals)` and a stat; `mob(stats)` and `mob(resists)` use constructors

A mob declares the maxima of its vitals, its stats and its resists, all keyed by schema names. They are the input of
`rules.starting_stats` at each spawn.

Before:

```python
mob("goblin_scout", hp = 30, armor = 2, stats = {"str": 10, "dex": 14}, resists = {"fire": -10})
```

After:

```python
mob("goblin_scout", vitals = vitals(hp = 30), stats = stats(str = 10, dex = 14, armor = 2),
    resists = resists(fire = -10))
```

### `heal(dice)` is now `heal(dice, vital)`, and `ctx.damage` and `ctx.heal` name a vital

Damage and healing always change one named vital.

Before:

```python
skill("mend", target = "ally", effect = heal(dice = "2d4"))
ctx.damage(target, 5, "fire")
ctx.heal(target, 5)
```

After:

```python
skill("mend", target = "ally", effect = heal(dice = "2d4", vital = "hp"))
ctx.damage(target, 5, DAMAGE.fire, "hp")
ctx.heal(target, 5, "hp")
```

### `damage(dice, type)` goes through `rules.mitigate` to any vitals

The `damage` effect primitive and auto-attacks use the vital losses `rules.mitigate` returns. The raw `ctx.damage` verb
makes one loss and skips `mitigate`.

### `flee(at_hp_pct)` is now `flee(below)`

The mob flees when any listed vital is at or below that percentage of its maximum.

Before:

```python
mob("hare", behaviors = [flee(at_hp_pct = 50)])
```

After:

```python
mob("hare", behaviors = [flee(below = vitals(hp = 50))])
```

### The actor view's fields come from the schema

Each stat (effective), each vital and `max_<vital>`, `base.<stat>`, `resists.<type>`, `below_zero_since(vital)` and
`condition` replace the fixed `hp`, `max_hp`, `mana`, `move`, `stats`, `armor` and `resists` fields. An undeclared name
is an error with a suggestion, so `ctx.actor.lfe` suggests `life`.

Before:

```python
ctx.actor.stats["dex"]
ctx.actor.armor
ctx.actor.resists.get("fire", 0)
```

After:

```python
ctx.actor.dex
ctx.actor.armor
ctx.actor.resists.fire
```

### `on_damage` has `losses`, and `amount` is their total

`ctx.losses` is a `vitals(...)` value with the loss of each vital; `ctx.amount` is their sum.

### The rules package is required, with `starting_stats` and `condition`

The engine has no built-in ruleset and nothing to fall back to. `//rules:combat.star` must define every export, and
loading it runs a dry run of each against synthetic actors. A rules function that fails at run time gives a neutral
result, records a `script_error` and raises a `rules_error` alert (this replaces the `rules_fallback` alert); the rules
package is never quarantined.

- `starting_stats(ctx)` returns `struct(stats = stats(...), max = vitals(...), current = vitals(...))`, every field
  optional.
- `condition(ctx)` returns `"ok"`, `"incapacitated"` or `"dead"`.
- `mitigate`, `move_cost` and `regen` return `vitals(...)`, `encumbrance` returns `stats(...)`, and `on_player_death`
  may return `"vitals": vitals(...)`.
- `interrupt_check(ctx, amount)` gets the total of the damage event's losses.

Before:

```python
def mitigate(ctx, amount, type):
    return max(1, amount - ctx.defender.armor // 2)

def regen(ctx):
    return {"hp": 1, "mana": 1, "move": 2}
```

After:

```python
def mitigate(ctx, amount, type):
    return vitals(hp = max(1, amount - ctx.defender.armor // 2))

def regen(ctx):
    return vitals(hp = 1, mana = 1, move = 2)
```

### Conditions: incapacitation and death from any vital

An actor's condition is decided by `rules.condition` after any change to one of its vitals and at every world tick. An
incapacitated actor takes no actions or auto-attacks and its tick-lane commands are refused with "You can't do that
while incapacitated."; a `dead` condition kills, in or out of combat.

### `spawn_player` and `w.damage` in tests name vitals

Before:

```python
p = w.spawn_player("Tess", at = ":start", hp = 30, stats = {"str": 12})
w.damage(rat, pct = 100)
```

After:

```python
p = w.spawn_player("Tess", at = ":start", vitals = vitals(hp = 30), stats = stats(str = 12))
w.damage(rat, "hp", pct = 100)
```

### Added `Char.Schema`, and `Char.Vitals` keys follow the schema

`Char.Schema` carries the stats and vitals with their labels, the damage types and the enabled features. `Char.Vitals`
has `<vital>` and `max<vital>` for each declared vital. The telnet prompt is generated from `module.prompt`, and the web
client draws one bar per vital.

### Added `gups serve --wipe`

A server wipe takes a `prewipe` backup, deletes every character and item, ignores saved world state like `--fresh`, and
records the world's current schema. Accounts, restrictions and tokens stay. `gups migrate-check` fails when the
snapshot's schema differs from the pull request's.

### Added `module.aliases`

`MODULE.gups` sets the alias words for engine commands, such as `l` for `look`. The engine no longer has built-in
aliases, so a world keeps `l`, `i`, `inv`, `eq`, `k`, `attack`, `c` and `take` only by listing them, as the template
world does.

### Added `module.prefixes`

`MODULE.gups` sets the single characters that stand for an engine command at the start of a line, such as `'` for `say`.
A world keeps `'` only by listing it, as the template world does.

### Added `server.shutdown_timeout`

`server.gups` can set how many seconds a shutdown waits for the final checkpoint.

### `package(reset_pulses)` is now `package(reset_world_ticks)`

A zone's reset interval is given in world ticks (30 seconds each), so a reset always lands on a world tick.

Before:

```python
package(api_version = 1, kind = "zone", reset_pulses = 7200)
```

After:

```python
package(api_version = 1, kind = "zone", reset_world_ticks = 60)
```

### `dispel(filter)` is now `dispel(name, flag, all, count)`

`dispel` takes keyword criteria instead of a filter string: `name` (a trailing `*` matches a prefix), `flag` (a string
or a list), `all = True`, and an optional `count`. At least one of `name`, `flag` or `all` is required. Affects flagged
`intrinsic` are never dispelled, and a dispelled affect now sends its `expire_msg`.

Before:

```python
skill("cure", kind = "spell", target = "ally", effect = dispel(filter = "poison*"))
```

After:

```python
skill("cure", kind = "spell", target = "ally", effect = dispel(name = "poison*"))
```

### `item(equip_affects)` entries can be timed, and a stun must be

An `equip_affects` entry is now a flag name, held while the item is worn, or `{"flag": ..., "pulses": n}`, applied for
`n` pulses when the item is equipped. Worn items' affects are real affects on the wearer (SPEC §4.6). A bare `"stunned"`
no longer loads.

Before:

```python
item("dazing_helm", wear = "head", equip_affects = ["stunned"])
```

After:

```python
item("dazing_helm", wear = "head", equip_affects = [{"flag": "stunned", "pulses": 4}])
```

### Exits and doors carry tags instead of blocked channels

`exit(senses_blocked)`, `door(open_senses_blocked)`, `door(closed_senses_blocked)`, the views' `senses_blocked`,
`open_senses_blocked` and `closed_senses_blocked`, and `ctx.set_exit_senses_blocked` are gone. `exit(tags = [...])` and
the exit view's `tags` take their place, and `rules.sense_cost` reads the tags, the door's `state` and its `tags`. The
template's `sense_cost` blocks every channel through an exit tagged `sealed`, `sound` through one tagged `soundproof`,
and `sight` through a closed door unless it is tagged `transparent`.

A `sense_cost` of 0 is now a free hop; only a negative result blocks it.

Before:

```python
exit("east", to = ":long_hall", senses_blocked = ALL)
```

After:

```python
exit("east", to = ":long_hall", tags = ["sealed"])
```

### Added `module.features`: `combat`, `track`, `skills` and `leveling`

A world lists the engine features it uses; leaving the line out turns all four on, so existing worlds are unchanged.
`track` needs `combat`. A feature that is off doesn't exist for the world: its commands give "Huh?" and are left out of
`help` and `gups docs --world`, and its declarations, declaration arguments, hooks, behaviors, package kinds, effect
primitives, modifier points and rules exports are refused (see the next entries). The set is recorded in
`meta.world_features` with the schema: a start with different features refuses to run, and `gups serve --wipe` is the
way forward. `gups migrate-check` fails on a changed set.

```python
module(
    api_version = 1,
    features = ["combat", "skills"],
)
```

### Features that are off refuse their declarations, hooks, behaviors and rules

With a feature off, a package that uses one of its entries fails to load with "`<name>` needs the `<feature>` feature":

- `skills`: `skill(...)`, the `skills` package kind, `mob(skills)`, the hooks `before_cast`, `on_cast_start` and
  `on_cast`, the modifier point `cast_allowed`, the effect primitives `damage`, `heal`, `apply_affect`, `dispel`,
  `stun`, `summon` and `sequence`.
- `combat`: the hooks `before_attack` and `on_combat_tick`, the behaviors `aggressive`, `assist` and `flee`, the
  modifier points `to_hit`, `damage` and `initiative`.
- `track`: `room(size)`, `exit(entry_index)`, `spawn(at_index)`, `item(range)`, the effect primitives `knockback` and
  `pull`, and, for a skill, the ranges `melee`, `near` and `far` (a skill may omit `range`, or use `self` or `room`).
- `leveling`: `mob(level)` and `aggressive(unless_level_above)`.

The rules package may not define the exports of a feature that is off, and must define those of one that is on:
`to_hit`, `damage`, `initiative` and `flee_chance` (`combat`), `cast_allowed` and `interrupt_check` (`skills`),
`retreat_check` and `get_lag` (`track`), `xp_for_kill` and `xp_to_level` (`leveling`). `on_player_death`'s `xp_loss` is
an error without `leveling`.

### `ctx` verbs and view fields of a feature that is off raise "`<name>` needs the `<feature>` feature"

`ctx.start_combat`, `ctx.stop_combat`, `ctx.enemies_near`, `ctx.allies_near`, `ctx.act_attack` and the `fighting` method
(`combat`); `ctx.act_advance`, `ctx.act_retreat`, a non-zero `index` of `ctx.teleport`, `ctx.spawn` and `ctx.move_item`,
the actor's `track_index` and `band_of`, the room's `size` and `track_size`, an exit's `entry_index`, an item's `range`
and an item location's `index` (`track`); `ctx.act_cast`, and an actor's `skills`, `casting` and `is_casting`
(`skills`); an actor's `level` and `xp` (`leveling`). Without `track` the rules `ctx.band` is `None`.

### Added `module.rename`

`rename = {"cast": "invoke"}` renames engine commands that aren't `@` tools. Aliases, prefixes, reserved exit and
character names, `help` and the docs use the new names; the old name is free for `global_command` and plugin commands. A
rename of a command that doesn't exist (or whose feature is off), to a name that isn't `[a-z][a-z0-9_]*` or is a
direction, another command or an alias stops the server. `rename` needs only a restart.

### Added `MESSAGES.gups` and the engine message catalog

Every player-facing engine message is an entry of a catalog with a stable id (`cast.begin.known`), named placeholders
and default text. A world overrides entries with one `messages({...})` call in `MESSAGES.gups`; an unknown id, an
unknown placeholder or a missing required one stops the server, and an id of a feature that is off warns. The file is
read at startup only, and a deploy that changes it is refused. `api.json` lists the catalog under `messages`, and `gups
docs` shows it on `world.html`.

```python
messages({
    "cast.begin.known": "{actor}'s implant whines as it spins up {skill}.",
    "cast.begin.unknown": "{actor}'s implant whines.",
})
```

### Added `feature` to `api.json` entries, and `world.html`

Entries owned by a feature carry `feature`, and `gups docs --world` leaves out the entries of features the world has
off.

### Added plugin `requiredFeatures` and `PluginWorld.features`

A plugin that needs features lists their ids in `GupsPlugin.requiredFeatures`; it is skipped with "needs the '<feature>'
feature" when one is off. `PluginActor.level` and `PluginRoom.size` throw `IllegalStateException` naming the feature
when `leveling` or `track` is off.

### Item quantities, pluralization, and all

- `item(...)` takes an optional `plural` (for example `plural = "gold coins"`), the display name of a stack of 2 or
  more. `item.plural` reads it on the item view, and `PluginItem.plural` exposes it to plugins.
- Item commands match a phrase of keywords instead of one word: every word of the phrase must match the start of one of
  the item's keywords, the words of a keyword, or its plural words, and articles are ignored unless the phrase is only
  articles, so `get gold coin`, `get coins` and `get the coin` all find `a gold coin`, and `get an` still finds `an
  anvil`. `get 2.coin` picks the second match, as the other item commands do.
- A blank `plural` fails to load.
- `get <what> [[from] <container>]` and `drop <what>` take an item specifier `<what>` instead of a single word: a
  `<keyword>`, a `<ordinal>.<keyword>`, a `<count> <keyword>`, a `<count> <ordinal>.<keyword>`, `all`, or `all
  <keyword>`. A keyword may be a phrase. The `from` preposition in `get` is optional: without it, `get <item>
  <container>` takes from the container when the last word names a container that holds a match, and `get all
  <container>` loots it; otherwise the whole phrase is a ground item.
- Bare `all` in `get`, `drop`, `give` and `put` skips items tagged `no_all` (such as `//engine:corpse`) and keyring-only
  keys; an explicit keyword (`all <keyword>`, `<count> <keyword>`, or a bare `<keyword>`) ignores both exclusions.
- A requested count is capped to what the source holds and to what the actor can carry. When the carry limit caps the
  transfer, the actor is told "You can't carry that much." A `get` from a container the actor already carries is not
  capped, since the load does not change.
- Each source stack runs `before_get` / `before_drop`, the move, and `on_get` / `on_drop` once; a single command can
  collect across several stacks, each with its own message. A stack that the `before_*` hook destroys or moves away is
  skipped.
- `look`, `inventory` and `keyring` show a stack of 2 or more as `"<count> <plural>"` when the prototype declares a
  `plural` and the item has no name override, and as `"<name> (<count>)"` otherwise.
- The engine's corpse prototype carries the `no_all` tag, which keeps an item out of bare `all` in `get`, `drop`, `give`
  and `put`.
- `move_item(item, to, index=0, count=None)` moves the whole item, or `count` of a stack, and returns the moved item's
  view. A moved part is a new item with the stack's attributes, name override, debug flag and decay deadline, and fires
  no `on_spawn`. Moving part of an equipped or non-stackable item is an error.
- `give(actor, label_or_item, count=None)`: `count` now also bounds how much of an item view is moved (it previously
  only applied to labels). `destroy(item, count=None)` removes the whole item, or `count` of a stack.
- `give <what> [to] <target>` and `put <what> [in|into] <container>` take the same item specifier `<what>` as `get` and
  `drop`, and the `to`, `in` and `into` prepositions are optional, so `give 5 coins bob` and `put 3 coins chest` work. A
  `give` is capped to the receiver's carry limit ("<Target> can't carry that much." when capped) and fires `on_give`
  once per stack moved; a `put` is capped to the container's free item capacity and `max_weight` ("<Container> is full."
  and "<Item> won't fit in <container>.") and never moves the target container itself. The usage strings become "Usage:
  give <item|count item|all [item]> [to] <target>" and "Usage: put <item|count item|all [item]> [in|into] <container>".
- `ctx.split` is removed; use `move_item(..., count=n)` to move part of a stack.

```python
# before
part = ctx.split(coins, 2)
ctx.move_item(part, ctx.room)

# after
part = ctx.move_item(coins, ctx.room, index = 0, count = 2)
```

## api_version 1

Initial (unstable) script API.

### Added `module.plugins`

`MODULE.gups` can pin plugin jars.
