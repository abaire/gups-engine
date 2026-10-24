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
