# Changelog

Script-facing changes are listed per `api_version`, with before/after snippets for
breaking ones.

## api_version 1 (unreleased)

### Added the world schema: `module.stats`, `module.vitals`, `module.damage_types`, `module.unarmed_damage_type`, `module.channels` and `module.prompt`

`MODULE.gups` is now required, and declares the names a world's actors are measured by. The
engine names no stat, vital or damage type itself. `stat(name, label)` and `vital(name, label)`
add a label; a plain string is the same with the default label.

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

The channel `spell` is no longer built in: a world that blocks or lists it declares it in
`channels`. `ALL` lists the built-in channels and the declared ones. The schema is fixed per
database; a start with different names refuses to run, and `gups serve --wipe` is the way
forward (see below).

### Added `vitals(...)`, `stats(...)`, `resists(...)` and `DAMAGE`

Wherever numbers are keyed by schema names, a constructor checks its keywords against the
schema, with a suggestion for a misspelling. A plain dict is an error that names the
constructor. `DAMAGE` is a struct of the declared damage types, so `DAMAGE.fier` fails at that
line.

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

A mob declares the maxima of its vitals, its stats and its resists, all keyed by schema names.
They are the input of `rules.starting_stats` at each spawn.

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

The `damage` effect primitive and auto-attacks use the vital losses `rules.mitigate` returns.
The raw `ctx.damage` verb makes one loss and skips `mitigate`.

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

Each stat (effective), each vital and `max_<vital>`, `base.<stat>`, `resists.<type>`,
`below_zero_since(vital)` and `condition` replace the fixed `hp`, `max_hp`, `mana`, `move`,
`stats`, `armor` and `resists` fields. An undeclared name is an error with a suggestion, so
`ctx.actor.lfe` suggests `life`.

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

The engine has no built-in ruleset and nothing to fall back to. `//rules:combat.star` must
define every export, and loading it runs a dry run of each against synthetic actors. A rules
function that fails at run time gives a neutral result, records a `script_error` and raises a
`rules_error` alert (this replaces the `rules_fallback` alert); the rules package is never
quarantined.

- `starting_stats(ctx)` returns `struct(stats = stats(...), max = vitals(...), current = vitals(...))`,
  every field optional.
- `condition(ctx)` returns `"ok"`, `"incapacitated"` or `"dead"`.
- `mitigate`, `move_cost` and `regen` return `vitals(...)`, `encumbrance` returns `stats(...)`,
  and `on_player_death` may return `"vitals": vitals(...)`.
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

An actor's condition is decided by `rules.condition` after any change to one of its vitals and
at every world tick. An incapacitated actor takes no actions or auto-attacks and its tick-lane
commands are refused with "You can't do that while incapacitated."; a `dead` condition kills,
in or out of combat.

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

`Char.Schema` carries the stats and vitals with their labels, the damage types and the enabled
features. `Char.Vitals` has `<vital>` and `max<vital>` for each declared vital. The telnet prompt
is generated from `module.prompt`, and the web client draws one bar per vital.

### Added `gups serve --wipe`

A server wipe takes a `prewipe` backup, deletes every character and item, ignores saved world
state like `--fresh`, and records the world's current schema. Accounts, restrictions and
tokens stay. `gups migrate-check` fails when the snapshot's schema differs from the pull
request's.

### Added `module.aliases`

`MODULE.gups` sets the alias words for engine commands, such as `l` for `look`. The engine no
longer has built-in aliases, so a world keeps `l`, `i`, `inv`, `eq`, `k`, `attack`, `c` and
`take` only by listing them, as the template world does.

### Added `module.prefixes`

`MODULE.gups` sets the single characters that stand for an engine command at the start of a
line, such as `'` for `say`. A world keeps `'` only by listing it, as the template world does.

### Added `server.shutdown_timeout`

`server.gups` can set how many seconds a shutdown waits for the final checkpoint.

### `package(reset_pulses)` is now `package(reset_world_ticks)`

A zone's reset interval is given in world ticks (30 seconds each), so a reset always lands on
a world tick.

Before:

```python
package(api_version = 1, kind = "zone", reset_pulses = 7200)
```

After:

```python
package(api_version = 1, kind = "zone", reset_world_ticks = 60)
```

### `dispel(filter)` is now `dispel(name, flag, all, count)`

`dispel` takes keyword criteria instead of a filter string: `name` (a trailing `*` matches a
prefix), `flag` (a string or a list), `all = True`, and an optional `count`. At least one of
`name`, `flag` or `all` is required. Affects flagged `intrinsic` are never dispelled, and a
dispelled affect now sends its `expire_msg`.

Before:

```python
skill("cure", kind = "spell", target = "ally", effect = dispel(filter = "poison*"))
```

After:

```python
skill("cure", kind = "spell", target = "ally", effect = dispel(name = "poison*"))
```

### `item(equip_affects)` entries can be timed, and a stun must be

An `equip_affects` entry is now a flag name, held while the item is worn, or
`{"flag": ..., "pulses": n}`, applied for `n` pulses when the item is equipped. Worn items'
affects are real affects on the wearer (SPEC §4.6). A bare `"stunned"` no longer loads.

Before:

```python
item("dazing_helm", wear = "head", equip_affects = ["stunned"])
```

After:

```python
item("dazing_helm", wear = "head", equip_affects = [{"flag": "stunned", "pulses": 4}])
```

### `exit(blocks)` is now `exit(senses_blocked)`

The name says the channels are senses an exit stops, not whether it can be walked through.

Before:

```python
exit("east", to = ":long_hall", blocks = ALL)
```

After:

```python
exit("east", to = ":long_hall", senses_blocked = ALL)
```

### `door(open_blocks)` is now `door(open_senses_blocked)`

Renamed to match `exit(senses_blocked)`.

### `door(closed_blocks)` is now `door(closed_senses_blocked)`

Renamed to match `exit(senses_blocked)`.

### `ctx.set_exit_blocks` is now `ctx.set_exit_senses_blocked`

Renamed to match `exit(senses_blocked)`; the arguments are unchanged.

### The exit view's `blocks` is now `senses_blocked`

Renamed to match `exit(senses_blocked)`.

### Added `module.features`: `combat`, `track`, `skills` and `leveling`

A world lists the engine features it uses; leaving the line out turns all four on, so existing
worlds are unchanged. `track` needs `combat`. A feature that is off doesn't exist for the world:
its commands give "Huh?" and are left out of `help` and `gups docs --world`, and its declarations,
declaration arguments, hooks, behaviors, package kinds, effect primitives, modifier points and
rules exports are refused (see the next entries). The set is recorded in `meta.world_features`
with the schema: a start with different features refuses to run, and `gups serve --wipe` is the
way forward. `gups migrate-check` fails on a changed set.

```python
module(
    api_version = 1,
    features = ["combat", "skills"],
)
```

### Features that are off refuse their declarations, hooks, behaviors and rules

With a feature off, a package that uses one of its entries fails to load with "`<name>` needs the
`<feature>` feature":

- `skills`: `skill(...)`, the `skills` package kind, `mob(skills)`, the hooks `before_cast`,
  `on_cast_start` and `on_cast`, the modifier point `cast_allowed`, the effect primitives
  `damage`, `heal`, `apply_affect`, `dispel`, `stun`, `summon` and `sequence`.
- `combat`: the hooks `before_attack` and `on_combat_tick`, the behaviors `aggressive`, `assist`
  and `flee`, the modifier points `to_hit`, `damage` and `initiative`.
- `track`: `room(size)`, `exit(entry_index)`, `spawn(at_index)`, `item(range)`, the effect
  primitives `knockback` and `pull`, and, for a skill, the ranges `melee`, `near` and `far` (a
  skill may omit `range`, or use `self` or `room`).
- `leveling`: `mob(level)` and `aggressive(unless_level_above)`.

The rules package may not define the exports of a feature that is off, and must define those of
one that is on: `to_hit`, `damage`, `initiative` and `flee_chance` (`combat`), `cast_allowed` and
`interrupt_check` (`skills`), `retreat_check` and `get_lag` (`track`), `xp_for_kill` and
`xp_to_level` (`leveling`). `on_player_death`'s `xp_loss` is an error without `leveling`.

### `ctx` verbs and view fields of a feature that is off raise "`<name>` needs the `<feature>` feature"

`ctx.start_combat`, `ctx.stop_combat`, `ctx.enemies_near`, `ctx.allies_near`, `ctx.act_attack`
and the `fighting` method (`combat`); `ctx.act_advance`, `ctx.act_retreat`, a non-zero `index` of
`ctx.teleport`, `ctx.spawn` and `ctx.move_item`, the actor's `track_index` and `band_of`, the
room's `size` and `track_size`, an exit's `entry_index`, an item's `range` and an item location's
`index` (`track`); `ctx.act_cast`, and an actor's `skills`, `casting` and `is_casting` (`skills`);
an actor's `level` and `xp` (`leveling`). Without `track` the rules `ctx.band` is `None`.

### Added `module.rename`

`rename = {"cast": "invoke"}` renames engine commands that aren't `@` tools. Aliases, prefixes,
reserved exit and character names, `help` and the docs use the new names; the old name is free for
`global_command` and plugin commands. A rename of a command that doesn't exist (or whose feature
is off), to a name that isn't `[a-z][a-z0-9_]*` or is a direction, another command or an alias
stops the server. `rename` needs only a restart.

### Added `MESSAGES.gups` and the engine message catalog

Every player-facing engine message is an entry of a catalog with a stable id (`cast.begin.known`),
named placeholders and default text. A world overrides entries with one `messages({...})` call
in `MESSAGES.gups`; an unknown id, an unknown placeholder or a missing required one stops the
server, and an id of a feature that is off warns. The file is read at startup only, and a deploy
that changes it is refused. `api.json` lists the catalog under `messages`, and `gups docs` shows it
on `world.html`.

```python
messages({
    "cast.begin.known": "{actor}'s implant whines as it spins up {skill}.",
    "cast.begin.unknown": "{actor}'s implant whines.",
})
```

### Added `feature` to `api.json` entries, and `world.html`

Entries owned by a feature carry `feature`, and `gups docs --world` leaves out the entries of
features the world has off.

### Added plugin `requiredFeatures` and `PluginWorld.features`

A plugin that needs features lists their ids in `GupsPlugin.requiredFeatures`; it is skipped with
"needs the '<feature>' feature" when one is off. `PluginActor.level` and `PluginRoom.size` throw
`IllegalStateException` naming the feature when `leveling` or `track` is off.

## api_version 1

Initial (unstable) script API.

### Added `module.plugins`

`MODULE.gups` can pin plugin jars.
