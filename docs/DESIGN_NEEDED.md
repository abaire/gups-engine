# Design Needed

Features that aren't built and that [SPEC.md](SPEC.md) doesn't settle well enough to build
without a design decision. Each entry gives the spec sections involved, what the engine does
today, and the open questions.

This is not the place for interim choices made while building: those go in
[DECISIONS.md](DECISIONS.md). It's also not for work the spec already schedules for a later
milestone (M6, M7). When an entry is designed and folded into the spec, delete it here.

---

## Combat and skills

### Skill proficiency
**Spec:** §3.3 (`skills: Map<Label, Int>`, proficiency 0–100), §4.3, §4.6.
**Today:** proficiency is stored and readable by scripts (`ctx.actor.skills`), but nothing
rolls against it (D-024).
**Questions:**
- Does proficiency decide whether a skill succeeds, how strong it is, or both?
- Which rules export rolls it? `to_hit` has `ctx.skill`, but casts never call `to_hit`.
- How do players gain proficiency: through use, trainers, or levels?

### Whether a spell lands
**Spec:** §4.3 (effect primitives), §4.6 (rules exports).
**Today:** an `enemy` spell whose target is valid and in range always applies its effect.
`mitigate` reduces damage, but nothing decides a miss, a resist or a save. A skill's `kind`
is "free-form; rules interpret it", yet no rules call that runs during a cast receives the
skill and can act on it.
**Question:** is a rules export needed for spell success, such as
`spell_hits(ctx) -> bool`, or a `save` check per effect?

### Acquiring skills
**Spec:** §3.3, §4.3, §5.12.
**Today:** players get skills only from the harness (`spawn_player(skills=...)`) or Kotlin
code. Mobs get them from their prototype. No command, verb or rules hook teaches a skill.
**Questions:** is there a `learn`/`practice` flow, a `ctx.teach(actor, skill, pct)` verb, or
skills granted by level through the rules?

### Level-up gains
**Spec:** §4.6 (`xp_for_kill`, `xp_to_level`).
**Today:** a level-up raises `level` and nothing else (D-039).
**Question:** should a rules export such as `on_level_up(ctx)` return stat and max-vital gains?

### Experience subsystem and scaling
**Spec:** §4.6 (`xp_for_kill`, `xp_to_level`), §5.4.
**Today:** `xp_for_kill(ctx)` awards a flat `50 * victim.level` and `xp_to_level(level)` scales linearly `1000 * level` (D-045). There is no level-difference scaling (killing trivial mobs yields full XP), no group/party XP sharing, no diminishing returns, and death XP loss (`xp / 10`) does not consider level loss or de-leveling floors.
**Questions:**
- Should kill XP factor in the level difference between killer and victim (e.g. reduced/zero XP for grey/trivial mobs, bonus XP for challenging mobs)?
- How should XP be distributed among group members or multi-attacker assists?
- Should the default curve support non-linear / polynomial scaling to prevent linear grinding?
- What are the boundaries for death XP penalties (e.g. can a player de-level, or is there a floor at the start of the current level)?


### Rules-defined starting stats
**Spec:** §5.12 says new characters get "rules-defined starting stats". §4.6 lists no export
for it.
**Today:** new characters get the engine defaults: all stats 10, 20 hp, 20 mana and 100 move
(D-082).
**Question:** should there be an export such as `starting_stats(ctx)`, and does character
creation offer the player any choice?

### Armor from equipment, shields and off-hand weapons
**Spec:** §3.3 (Actor `armor`; Item `kind = "armor"`, `statDeltas`), §4.6 slots (`offhand`,
`held`), §5.4.
**Today:**
- `armor` is a flat number on the actor. Worn items can't change it, because `stat_deltas`
  only cover str, dex, con, int and wis.
- Only the `wield` slot attacks. `offhand` and `held` are inert.
- Nothing models two-handed weapons.
**Questions:**
- Should items carry an `armor` value, or should `stat_deltas` cover `armor` and resists?
- Do off-hand weapons swing? Do shields add to `armor` or to a block chance?
- Do two-handed weapons fill or block the `offhand` slot?

### Track-movement lags in the rules module
**Spec:** §5.5 gives the default lags for advance and failed retreat or flee. §4.6 lists no
exports for them.
**Today:** they are Kotlin-only rules methods, and a successful retreat costs 2 (D-026).
**Question:** should these become Starlark exports in the next `api_version`?

### Defender-side modifiers in combat
**Spec:** §4.6 (Rules Module and the Modifier Chain: `to_hit`, `damage`, chain order).
**Today:** the modifier chain for `to_hit` and `damage` evaluates only the attacker's chain:
zone, region, room, attacker affects, and attacker worn items (D-111). Defender affects and
worn items are not evaluated in the chain. Defensive scaling is limited to entity stats
(`dex`), `armor`, and `resists` evaluated in `to_hit` and `mitigate`.
**Questions:**
- How should defender items and affects contribute to combat resolution (e.g. an evasion cloak
  reducing hit chance, or a damage-ward affect absorbing flat damage)?
- Should there be distinct modifier points such as `defend_hit` / `incoming_damage`, or should
  the defender's affects and equipment be appended to the existing `to_hit` and `damage` chains?
- If appended, in what order do attacker and defender modifiers apply?

### Spell damage modifiers
**Spec:** §4.3 (effect primitives, `damage`), §4.6 (Modifier points: `damage`).
**Today:** spell `damage(...)` effects roll their dice and pass the amount directly to
`rules.mitigate(ctx, amount, type)`, completely bypassing the `damage` modifier point (D-111).
Only auto-attacks evaluate `damageChain`. A room, zone, or item cannot scale or modify spell
damage (e.g. elemental sanctums or spellpower bonuses).
**Questions:**
- Should spell damage evaluate a modifier chain (either a dedicated `spell_damage` modifier
  point or the existing `damage` modifier point with `ctx.skill` populated)?
- If using the existing `damage` modifier point, how do we distinguish general damage buffs
  from weapon-only physical damage bonuses?

## Mobs and AI

### Pets and followers bonded to a player
**Spec:** §5.9 says "Pets and followers bonded to a player get the reason `bond:<player_id>`."
§4.7 has `follow(target)`.
**Today:**
- Nothing creates a bond.
- `follow` latches onto the first matching actor it sees, and drops it after more than 3
  exits (D-121).
- Scripts can't tell a mob whom to follow.
**Questions:**
- Is there a verb such as `ctx.bond(mob, player)`, or a `follow`/`order` command?
- Who adds and clears the `bond:` active reason, and does it survive logout?
- Does a pet fight for its owner, and does it count as an ally for `assist`?

### Hostility and factions
**Spec:** §4.7 (`aggressive(toward)`, `assist(allies)`, `guard`), §3.7 (the example `faction`
shared namespace).
**Today:** "hostile" means "engaged with" (D-027). Behaviors pick targets by `players`, `mobs`
or label pattern (D-121). Nothing remembers who attacked whom once a fight ends.
**Questions:**
- Is there a faction or reputation model the engine understands, or is it only a `//lib`
  convention on shared attributes?
- Should mobs remember attackers (a hate list) after a flee or a disengage?

### Aggressive mobs and players' level or visibility
**Spec:** §4.7 `aggressive(toward, unless_level_above=None)`.
**Today:** an aggressive mob attacks the lowest-id match at once. Nothing hides players or
models stealth.
**Question:** is sneaking or invisibility in scope, and should `aggressive` take a chance or
a delay?

## Items

### Consumables and charges
**Spec:** §3.3 (`kind`, for example `consumable`), §4.4 (`before_use`/`on_use`).
**Today:** `use` fires the hooks, and the engine never uses an item up (D-114). Scripts call
`ctx.destroy` themselves.
**Questions:**
- Should `consumable` items lose one from the stack automatically after a successful `on_use`?
- Should items have charges?
- Should items have a declarative `use_effect` (an effect primitive) for novices who don't
  write hooks?

### Effects on items
**Spec:** §4.3 (`target = "item"`, effect primitives).
**Today:** an item-target skill runs only its `resolve`, because every effect primitive acts
on actors (D-123). Items have no affects.
**Questions:** should items be able to carry affects (enchantments, curses)? Should there be
item primitives such as `repair` or `identify`?

### Quantities and `all`
**Spec:** §4.9 lists `get`, `drop`, `give` and `put` without argument syntax.
**Today:** these commands move one item, or a whole stack. There is no `get all`, no
`drop 5 coins`, and no `give 3 coins bob`.
**Question:** what is the argument syntax for counts and `all`, and does moving part of a
stack split it?

### Corpses and looting
**Spec:** §5.4 (a mob corpse holds inventory and loot; a player corpse is left behind).
**Today:** anyone can loot any corpse. A player corpse decays like any other and drops its
contents on the ground (D-038).
**Questions:**
- May other players loot a player's corpse?
- Does a player corpse last longer than a mob's, or is it protected?
- Should the owner be able to recover their corpse after logging out?

### Encumbrance beyond stats
**Spec:** §4.6 (`carry_limit`, and `encumbrance` returning a "modifiers dict").
**Today:** encumbrance is a set of stat deltas only (D-115). It doesn't affect move cost, lag
or the ability to move.
**Question:** should `encumbrance` also be able to return move-cost or lag changes, or forbid
movement past a hard limit?

## Perception and the world

### What players perceive from next door
**Spec:** §5.7 defines perception for mobs (`on_nearby`) only.
**Today:** players hear nothing from adjacent rooms except `shout`, which follows the noise
channel (D-018, D-120).
**Question:** should players also hear combat, death cries and voices through open exits, as
text such as "You hear fighting to the north"?

### Recognizing an unfamiliar spell
**Spec:** §5.3 (warmup messages), §4.6 (rules exports).
**Today:** when a cast's warmup begins, onlookers who know the skill see its name and everyone
else sees only "<caster> begins casting." Nothing else lets an onlooker identify the spell.
**Questions:**
- Should onlookers with high Wisdom or Intelligence, or who pass a perception check, recognize
  a spell they don't know by watching the caster?
- Is that check a rules export, such as `recognizes_spell(ctx) -> bool`, and does it run once
  per onlooker per cast?
- Does the skill's `kind` or another field make some spells harder to recognize, or hide them
  entirely?

### Events from rooms, doors and items
**Spec:** §5.7's table lists only actor events.
**Today:** doors opening and closing, items breaking or falling, and room-scripted events are
never perceived.
**Question:** should scripts be able to raise perceivable events, for example with
`ctx.noise(room, event, text)`, and should door commands emit `sound`?

### Cost of `pursue` and other world-wide searches
**Spec:** §4.7 says `pursue` uses `path_to` across the whole world, and §5.9 caches paths.
§7.1 sets the load-test targets.
**Today:** `pursue` asks for a path to every matching actor on each think cycle, using cached
BFS (D-121, D-124). With hundreds of players and many pursuers this may cost too much.
**Question:** should `pursue` have a maximum range, or a slower think cadence?

## Commands

### Exits named after a plugin command
**Spec:** §4.9 (reserved exit names), §4.15, §5.2.
**Today:** a package fails to load if an exit is named after an engine command, an engine
alias or a global command. Plugin commands aren't checked: the script loader doesn't know
which plugin commands are registered. Movement is resolved before plugin commands, so an exit
named after a plugin command silently hides that command in the exit's room.
**Questions:**
- Should the loader receive the registered plugin command names and aliases, and reject such
  an exit like any other reserved name?
- Plugins load before packages but can be added or removed between runs. Does a newly added
  plugin command fail packages that loaded fine before, or does the plugin fail instead?
- Do role-gated plugin commands reserve the name for everyone, even players who can't use
  them?

### Global commands named after a direction
**Spec:** §4.9 (global commands), §5.2.
**Today:** `global_command("north", …)` (or `n`, or any standard direction) loads, but it can
never run: a standard direction always resolves to movement before global commands are
looked up. Plugin commands named after a direction are already refused.
**Questions:**
- Should such a global command fail its package's load, as plugin commands do?
- Should the check cover direction aliases (`n`, `ne`) as well as full names?

## Plugins

### Plugins that share a GMCP package tree
**Spec:** §4.15 (GMCP, loading order), §4.10.
**Today:** a plugin's GMCP package may not equal, contain or nest in another plugin's, and the
plugin that registers later (larger id) fails to load (D-178). That stops a plugin from silently
sending under another's tree, since clients subscribe by prefix. It also rules out deliberate
cooperation, such as a `rain` plugin adding `Gups.Weather.Rain` next to `weather`'s
`Gups.Weather`.
**Sketch (not settled):**
- Both sides opt in. The owner allows sub-packages with `r.shareGmcp("Gups.Weather")`. The
  other plugin names the owner when it registers: `r.addGmcp("Gups.Weather.Rain", doc,
  within = "weather") { … }`. A nested package without both still fails, as today.
- The owner must be loaded. If `weather` is missing or failed, `rain`'s nested package fails
  with "needs plugin 'weather'", and so does `rain`, since registration is atomic.
- Load order: today "earlier" means a smaller id, so `arain` would register before `weather`.
  Either `within` makes the owner register first (a dependency edge, sorted by id otherwise),
  or the overlap check runs once all plugins have registered.
- The docs page lists a shared package under its owner, so a student can see who sends what.
- It's an API change: bump `PluginApi.VERSION` and add a CHANGELOG entry.
**Questions:**
- Should the operator also agree in `MODULE.gups` (for example
  `plugins_share = {"rain": ["weather"]}`), or is the plugins' consent enough?
- Should the same opt-in cover other things plugins share: commands (a plugin adding a
  sub-command to another's), attribute namespaces, or calling another plugin's builtins?
- Does a dependency between plugins need a declared version range, and how does that relate
  to `PluginApi.VERSION`?

## World shape across terms

Each term's course world may use a different genre: high fantasy with spells one term, a
post-apocalyptic world with robots, mutants and tech the next. Whole mechanics such as ranged
combat or spellcasting may change shape or disappear. Race and class are already content (no
engine concept), but the actor model, the command set and several mechanics are fixed in
`core`. The two entries below propose moving genre out of the engine's types and into the
world's configuration, while keeping the mechanisms in Kotlin. Neither is settled.

Moving every command into `.jar` plugins was considered and rejected as the main route.
Plugins are trusted Kotlin that only instructors approve, so students couldn't change a
world's genre. They carry no compatibility promises and must be rebuilt every release. They
need a restart to change. And commands such as `get`, `flee` and `kill` depend on stacking,
pinning, combat rounds and the invariant checks, so the `PluginWorld` facade would have to
expose nearly all of `core`. Plugins stay the route for genuinely new mechanics (§4.15).

### Configurable actor schema
**Spec:** §3.3 (Actor stats, "fixed v0 schema"), §3.6, §4.3 (`cost`), §4.6 (`regen`,
`interrupt_check`), §4.10 (`Char.Vitals`), §4.15 (facade).
**Today:**
- Base stats are already a map (`Actor.baseStats`), but the names are fixed to `STAT_NAMES`
  (`str`, `dex`, `con`, `int`, `wis`), and `stat_deltas` accepts only those.
- Vitals are typed fields: `hp/maxHp`, `mana/maxMana`, `move/maxMove`. A skill's `cost` keys
  must be `hp`, `mana` or `move`. `regen` returns those three keys, `Char.Vitals` sends them,
  and persistence stores them as columns.
- Damage types are already free-form (`DamageType`).
- A robot world that wants `integrity`, `power` and `heat`, or no `mana` at all, can't
  express it. It can only rename things in prose.

**Sketch (not settled):** the world declares its stats and vitals in `MODULE.gups`, because
changing them affects persisted state and already needs a restart. Formulas stay in the
rules module. A vital gets a `role` when the engine itself has to find it:

| Role | Engine use | Required |
|---|---|---|
| `life` | Damage lowers it; the actor dies at 0. `interrupt_check` and `flee(at_hp_pct)` read it. | exactly one |
| `movement` | `move_cost` spends it; at 0 the actor can't leave the room. | at most one |
| none | Spent only by skill `cost`s and scripts. | any number |

```python
# A fantasy term: the same as today's built-in schema, and the default when omitted.
module(
    api_version = 2,
    stats = ["str", "dex", "con", "int", "wis"],
    vitals = [
        vital("hp", role = "life", label = "Hit points", start = 20),
        vital("mana", label = "Mana", start = 20),
        vital("move", role = "movement", label = "Movement", start = 100),
    ],
)
```

```python
# A post-apocalyptic term.
module(
    api_version = 2,
    stats = ["might", "agility", "tech", "grit", "psi"],
    vitals = [
        vital("integrity", role = "life", label = "Integrity", start = 30),
        vital("power", label = "Power", start = 10),
        vital("rads", label = "Radiation", start = 0, max = 100, regen = False),
    ],
)
```

With this, every place that names a stat or vital checks it against the schema at load:
`stat_deltas`, `cost`, mob prototypes, `regen`'s returned keys, `ctx.actor.stats[...]` and
`ctx.actor.vitals[...]` in scripts. `Char.Vitals` sends every vital with its label. The
built-in Kotlin ruleset is valid only for the default schema. A world that declares its own
must define every rules export that reads a stat, and `gups lint` reports any it is missing.

**Questions:**
- A character saved under the old schema meets a changed one mid-term. Does a new vital start
  at its `start` or its max? Is a removed stat dropped silently or with a diagnostic? Does
  `gups migrate-check` cover schema changes?
- Do `level` and `xp` stay engine concepts, or become optional for skill-based worlds with no
  levels? `xp_for_kill` and `xp_to_level` assume them.
- Do `armor` and `resists` stay typed fields, or become ordinary stats the rules interpret?
  This interacts with "Armor from equipment" above.
- Should vitals declare a regen floor or ceiling (a `rads` vital that only rises), or is that
  left to `regen` in the rules?
- Are stats always ints, or can a world declare a float or a string stat?
- Does the plugin facade switch to `getStat(actor, name)` / `getVital(actor, name)`, and is
  that a `PluginApi.VERSION` bump in the same release?
- Is this `api_version = 2` on its own, or bundled with other breaking changes?

### Engine feature modules
**Spec:** §4.3, §4.9 (command lists), §5.3 (action lifecycle), §5.4 (combat rounds), §5.5
(the track), §5.7 (perception channels), §4.15.
**Today:** every engine command is in `EngineCommands.kt`, and the mechanics behind them are
always on. Every actor has `casting: CastState?`. Exits can block the `SPELL` channel. Every
room has a track with bands, and items have `range`. A world without magic can only leave
`cast` unused, and a world without ranged combat still has `advance`, `retreat` and bands.

**Sketch (not settled):** split `core` internally into a small kernel plus feature modules.
Each module registers its own commands, actor state, world-loop phase work, rules exports
(with built-in defaults), GMCP packages and load-time checks through an internal Kotlin
interface. The modules ship inside the engine: they are reviewed, versioned and tested with
it, and are not plugins. A world turns them on in `MODULE.gups`.

| Module | Owns | Depends on |
|---|---|---|
| kernel (always on) | Sessions, the scheduler, persistence, hooks, timers, affects, movement through exits, `look`, communication, `who`/`help`/`score`/`quit`, `get`/`drop`/`give`/`inventory`/`equipment`/`wear`/`remove`, `use` | — |
| `combat` | `kill`, `flee`, combat rounds, initiative, auto-attacks, death and corpses, `to_hit`/`damage`/`mitigate`/`initiative`/`flee_chance` | — |
| `track` | `advance`, `retreat`, bands, `range` on items, band-based `get_lag`, `Room.Bands` | — |
| `skills` | `cast` (renameable), warmups and `CastState`, interrupts, `cast_allowed`, `interrupt_check`, skill `cost`s, the `SPELL` channel (renameable) | — |
| `doors` | doors on exits, `open`/`close`/`lock`/`unlock` on exits | — |
| `containers` | `put`, `get ... from`, `open`/`close`/`lock`/`unlock` on containers | — |

```python
module(
    api_version = 2,
    # Omitting `features` turns every module on, which is today's behavior.
    features = [
        feature("combat"),
        feature("skills",
            # Players type `invoke`, not `cast`; `cast` is no longer a command.
            rename = {"cast": "invoke"},
            # The perception channel exits block, shown as "psionic" to builders.
            channel = "psionic",
        ),
        feature("doors"),
        feature("containers"),
        # No "track": every room is a single point, so everything is in melee reach.
    ],
    aliases = {"iv": "invoke", "k": "kill"},
    prefixes = {"%": "invoke"},
)
```

Content that needs a module that is off fails its package's load with a diagnostic naming
the module: a skill when `skills` is off, an item with `range` when `track` is off, a door
when `doors` is off. `aliases`, `prefixes` and reserved exit names use the commands after
renaming. `help` and `gups docs --world` list only what the world has turned on, so students
never see `advance` in a world without a track. A plugin can declare the modules it requires
and fails to load without them.

**Questions:**
- Without `track`, does each room behave as a track of size 1 (keeping the code paths), or do
  bands disappear from the model altogether? The first is much cheaper. The second keeps
  `Room.Bands` and `@where` out of worlds that don't use them.
- Is `combat` really optional? A world with no fighting is plausible, but `damage`, `heal` and
  death are used by skills and hooks. Does death belong to the kernel, with `combat` adding only
  rounds and auto-attacks?
- How fine should modules be? `doors` and `containers` may be too small to be worth a switch,
  and `skills` may be too large: a world might want skills without warmups.
- Is renaming enough to reskin a module, or do modules also need their player-facing messages
  overridable ("You begin casting" → "You spin up your implant")? Does that go in `MODULE.gups`,
  in the rules module, or in a messages file?
- Do perception channels become world-declared (`channels = ["sight", "sound", "psionic"]`),
  rather than a module renaming one built-in channel?
- What happens to persisted state that belongs to a module that is later turned off mid-term?
- Should the internal module interface later become the public plugin interface, so plugins and
  built-in modules register the same way? That would put the module interface under the same
  "no compatibility promises" policy as plugins until it settles.
