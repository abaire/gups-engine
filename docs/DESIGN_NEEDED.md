# Design Needed

Features that aren't built and that [SPEC.md](SPEC.md) doesn't settle well enough to build without a design decision.
Each entry gives the spec sections involved, what the engine does today, and the open questions.

This is not the place for interim choices made while building: those go in [DECISIONS.md](DECISIONS.md). It's also not
for work the spec already schedules for a later milestone (M6, M7). When an entry is designed and folded into the spec,
delete it here.

---

## Combat and skills

### Skill proficiency

**Spec:** §3.3 (`skills: Map<Label, Int>`, proficiency 0–100), §4.3, §4.6. **Today:** proficiency is stored and readable
by scripts (`ctx.actor.skills`), but nothing rolls against it (D-024). **Questions:** - Does proficiency decide whether
a skill succeeds, how strong it is, or both? - Which rules export rolls it? `to_hit` has `ctx.skill`, but casts never
call `to_hit`. - How do players gain proficiency: through use, trainers, or levels?

### Whether a spell lands

**Spec:** §4.3 (effect primitives), §4.6 (rules exports). **Today:** an `enemy` spell whose target is valid and in range
always applies its effect. `mitigate` reduces damage, but nothing decides a miss, a resist or a save. A skill's `kind`
is "free-form; rules interpret it", yet no rules call that runs during a cast receives the skill and can act on it.
**Question:** is a rules export needed for spell success, such as `spell_hits(ctx) -> bool`, or a `save` check per
effect?

### Acquiring skills

**Spec:** §3.3, §4.3, §5.12. **Today:** players get skills only from the harness (`spawn_player(skills=...)`), Kotlin
code, or (after M11) a plugin's `setSkill`, which runs the `learn` restrictions (§4.19). Mobs get them from their
prototype. No command, script verb or rules hook teaches a skill. **Questions:** is there a `learn`/`practice` flow, a
`ctx.teach(actor, skill, pct)` verb, or skills granted by level through the rules?

### Skill-based advancement (worlds without character levels)

**Spec:** §3.3 (`skills: Map<Label, Int>`), §3.10 (`leveling` feature), §4.6. **Today:** a world with `leveling` off has
no `level` or `xp`, and skills change only through the harness, Kotlin code or a plugin's `setSkill` (§4.19). Nothing
tracks experience toward a skill, and no hook reports that a skill was used. Depends on "Skill proficiency" and
"Acquiring skills". **Questions:** - Do skills earn XP of their own (for example `skill_xp: Map<Label, Long>`), and is
the proficiency a function of it? - Is the advancement mechanism a rules export called after each use (for example
`skill_gain(ctx)`), a plugin timer or hook, or both? - What counts as a use: a cast, a hit with a weapon skill, a failed
attempt? Is there a cap per use or per time to stop farming? - Can a world mix character levels and skill levels, and
can a skill's level gate on the character's? - Which engine code may still read `level` when `leveling` is off? The
template rules read it in `to_hit` and `regen`; those are template choices, but the kernel must not assume it.

### Rerolls and point-buy at character creation

**Spec:** §4.6 (`starting_stats`), §4.19, §5.12. **Today (after M11):** creation offers only discrete choices from the
world's creation provider. `rules.starting_stats` sets the numbers once, possibly randomized, and the player never sees
them before confirming or gets to change them. **Questions:** - Should the confirmation show the rolled stats and offer
`reroll`? Is the number of rerolls limited, and who sets the limit (the rules module or the provider)? How do rerolls
stay deterministic for replays and the fuzz walker? - Should point-buy exist, as a new kind of creation step that takes
free-form input (`str +2`, `done`) instead of picking an option? Would the provider handle that input, or the rules
module through a new export such as `point_buy(ctx, spend)`? - How would a point-buy or rerolled result reach
`starting_stats`: as input in `ctx`, or as replacing its result?

### Group, party and pet XP sharing

**Spec:** §4.6 (`xp_for_kill`), §5.4 (Death: the killer is the last actor who damaged the victim), §5.9 (`bond:`
reasons), §4.7 (`assist`, `follow`). **Today:** only the killer is awarded XP, and nothing records who else fought the
victim. The engine has no group or party concept, so a party member who didn't land the final blow, a pet's owner, and
an assisting ally all earn nothing. Related designs: "Pets and followers bonded to a player" and "Hostility and
factions". **Questions:** - Is there an engine-level group or party (a command to form it, membership that persists
across logout), or is it only a `//lib` convention on shared attributes? - Should death track every actor that damaged
the victim, so XP can go to all contributors rather than the last hitter? - Does a pet's kill credit its owner, and does
an owner's kill credit the pet? - How is the award split: equal shares, or weighted by level or by damage dealt? Who
sets the formula, and is it a rules export (for example `xp_share(ctx)`)? - Are members out of the room, or far above or
below the victim's level, excluded from the split?

### Armor from equipment, shields and off-hand weapons

**Spec:** §3.3 (Item `kind = "armor"`, `statDeltas`), §3.9, §4.6 slots (`offhand`, `held`), §5.4. **Today (after M9):**
- `armor` is an ordinary declared stat, so worn items and affects change it through `stat_deltas`. Resists are a
per-actor map that nothing worn can change; after M11 only a player's traits add to it (§4.19). - Only the `wield` slot
attacks. `offhand` and `held` are inert. - Nothing models two-handed weapons. **Questions:** - Should items and affects
carry `resist_deltas`? - Do off-hand weapons swing? Do shields add to `armor` or to a block chance? - Do two-handed
weapons fill or block the `offhand` slot?

### Defender-side modifiers in combat

**Spec:** §4.6 (Rules Module and the Modifier Chain: `to_hit`, `damage`, chain order). **Today:** the modifier chain for
`to_hit` and `damage` evaluates only the attacker's chain: zone, region, room, attacker affects, and attacker worn items
(D-111). Defender affects and worn items are not evaluated in the chain. Defensive scaling is limited to entity stats
(`dex`), `armor`, and `resists` evaluated in `to_hit` and `mitigate`. **Questions:** - How should defender items and
affects contribute to combat resolution (e.g. an evasion cloak reducing hit chance, or a damage-ward affect absorbing
flat damage)? - Should there be distinct modifier points such as `defend_hit` / `incoming_damage`, or should the
defender's affects and equipment be appended to the existing `to_hit` and `damage` chains? - If appended, in what order
do attacker and defender modifiers apply?

### Spell damage modifiers

**Spec:** §4.3 (effect primitives, `damage`), §4.6 (Modifier points: `damage`). **Today:** spell `damage(...)` effects
roll their dice and pass the amount directly to `rules.mitigate(ctx, amount, type)`, completely bypassing the `damage`
modifier point (D-111). Only auto-attacks evaluate `damageChain`. A room, zone, or item cannot scale or modify spell
damage (e.g. elemental sanctums or spellpower bonuses). **Questions:** - Should spell damage evaluate a modifier chain
(either a dedicated `spell_damage` modifier point or the existing `damage` modifier point with `ctx.skill` populated)? -
If using the existing `damage` modifier point, how do we distinguish general damage buffs from weapon-only physical
damage bonuses?

### Track-lag modifier points

**Spec:** §4.6 (modifier points, chain order), §5.5. **Today:** `advance_lag`, `retreat_lag`, `retreat_fail_lag` and
`flee_fail_lag` are plain rules exports. Rooms, gear and affects change them only through the rules module reading the
ctx (for example `ctx.room.tags`). **Questions:** - Should they become modifier points, so `room(modifiers=)`,
`item(modifiers=)` and affects can change them directly? - One `track_lag` point with an action word, or one point per
export? - Does `flee_fail_lag` get a point without `track`? - How does this relate to encumbrance changing lag
("Encumbrance beyond stats")?

## Mobs and AI

### Pets and followers bonded to a player

**Spec:** §5.9 says "Pets and followers bonded to a player get the reason `bond:<player_id>`." §4.7 has
`follow(target)`. **Today:** - Nothing creates a bond. - `follow` latches onto the first matching actor it sees, and
drops it after more than 3 exits (D-121). - Scripts can't tell a mob whom to follow. **Questions:** - Is there a verb
such as `ctx.bond(mob, player)`, or a `follow`/`order` command? - Who adds and clears the `bond:` active reason, and
does it survive logout? - Does a pet fight for its owner, and does it count as an ally for `assist`?

### Hostility and factions

**Spec:** §4.7 (`aggressive(toward)`, `assist(allies)`, `guard`), §3.7 (the example `faction` shared namespace).
**Today:** "hostile" means "engaged with" (D-027). Behaviors pick targets by `players`, `mobs` or label pattern (D-121).
Nothing remembers who attacked whom once a fight ends. **Questions:** - Is there a faction or reputation model the
engine understands, or is it only a `//lib` convention on shared attributes? - Should mobs remember attackers (a hate
list) after a flee or a disengage?

### Aggressive mobs and players' level or visibility

**Spec:** §4.7 `aggressive(toward, unless_level_above=None)`. **Today:** an aggressive mob attacks the lowest-id match
at once. Nothing hides players or models stealth. **Question:** is sneaking or invisibility in scope, and should
`aggressive` take a chance or a delay?

## Items

### Consumables and charges

**Spec:** §3.3 (`kind`, for example `consumable`), §4.4 (`before_use`/`on_use`). **Today:** `use` fires the hooks, and
the engine never uses an item up (D-114). Scripts call `ctx.destroy` themselves. **Questions:** - Should `consumable`
items lose one from the stack automatically after a successful `on_use`? - Should items have charges? - Should items
have a declarative `use_effect` (an effect primitive) for novices who don't write hooks?

### Effects on items

**Spec:** §4.3 (`target = "item"`, effect primitives). **Today:** an item-target skill runs only its `resolve`, because
every effect primitive acts on actors (D-123). Items have no affects. **Questions:** should items be able to carry
affects (enchantments, curses)? Should there be item primitives such as `repair` or `identify`?

### Quantities and `all`

**Spec:** §4.9 lists `get`, `drop`, `give` and `put` without argument syntax. **Today:** these commands move one item,
or a whole stack. There is no `get all`, no `drop 5 coins`, and no `give 3 coins bob`. **Question:** what is the
argument syntax for counts and `all`, and does moving part of a stack split it?

### Corpses and looting

**Spec:** §5.4 (a mob corpse holds inventory and loot; a player corpse is left behind). **Today:** anyone can loot any
corpse. A player corpse decays like any other and drops its contents on the ground (D-038). **Questions:** - May other
players loot a player's corpse? - Does a player corpse last longer than a mob's, or is it protected? - Should the owner
be able to recover their corpse after logging out?

### Encumbrance beyond stats

**Spec:** §4.6 (`carry_limit`, and `encumbrance` returning a "modifiers dict"). **Today:** encumbrance is a set of stat
deltas only (D-115). It doesn't affect move cost, lag or the ability to move. **Question:** should `encumbrance` also be
able to return move-cost or lag changes, or forbid movement past a hard limit?

## Perception and the world

### Recognizing an unfamiliar spell

**Spec:** §5.3 (warmup messages), §4.6 (rules exports). **Today:** when a cast's warmup begins, onlookers who know the
skill see its name and everyone else sees only "<caster> begins casting." Nothing else lets an onlooker identify the
spell. **Questions:** - Should onlookers with high Wisdom or Intelligence, or who pass a perception check, recognize a
spell they don't know by watching the caster? - Is that check a rules export, such as `recognizes_spell(ctx) -> bool`,
and does it run once per onlooker per cast? - Does the skill's `kind` or another field make some spells harder to
recognize, or hide them entirely?

### Cost of `pursue` and other world-wide searches

**Spec:** §4.7 says `pursue` uses `path_to` across the whole world, and §5.9 caches paths. §7.1 sets the load-test
targets. **Today:** `pursue` asks for a path to every matching actor on each think cycle, using cached BFS (D-121,
D-124). With hundreds of players and many pursuers this may cost too much. **Question:** should `pursue` have a maximum
range, or a slower think cadence?

## Commands

### Exits named after a plugin command

**Spec:** §4.9 (reserved exit names), §4.15, §5.2. **Today:** a package fails to load if an exit is named after an
engine command, an engine alias or a global command. Plugin commands aren't checked: the script loader doesn't know
which plugin commands are registered. Movement is resolved before plugin commands, so an exit named after a plugin
command silently hides that command in the exit's room. **Questions:** - Should the loader receive the registered plugin
command names and aliases, and reject such an exit like any other reserved name? - Plugins load before packages but can
be added or removed between runs. Does a newly added plugin command fail packages that loaded fine before, or does the
plugin fail instead? - Do role-gated plugin commands reserve the name for everyone, even players who can't use them?

## Plugins

### Plugins that share a GMCP package tree

**Spec:** §4.15 (GMCP, loading order), §4.10. **Today:** a plugin's GMCP package may not equal, contain or nest in
another plugin's, and the plugin that registers later (larger id) fails to load (D-178). That stops a plugin from
silently sending under another's tree, since clients subscribe by prefix. It also rules out deliberate cooperation, such
as a `rain` plugin adding `Gups.Weather.Rain` next to `weather`'s `Gups.Weather`. **Sketch (not settled):** - Both sides
opt in. The owner allows sub-packages with `r.shareGmcp("Gups.Weather")`. The other plugin names the owner when it
registers: `r.addGmcp("Gups.Weather.Rain", doc, within = "weather") { … }`. A nested package without both still fails,
as today. - The owner must be loaded. If `weather` is missing or failed, `rain`'s nested package fails with "needs
plugin 'weather'", and so does `rain`, since registration is atomic. - Load order: today "earlier" means a smaller id,
so `arain` would register before `weather`. Either `within` makes the owner register first (a dependency edge, sorted by
id otherwise), or the overlap check runs once all plugins have registered. - The docs page lists a shared package under
its owner, so a student can see who sends what. - It's an API change: bump `PluginApi.VERSION` and add a CHANGELOG
entry. **Questions:** - Should the operator also agree in `MODULE.gups` (for example `plugins_share = {"rain":
["weather"]}`), or is the plugins' consent enough? - Should the same opt-in cover other things plugins share: commands
(a plugin adding a sub-command to another's), attribute namespaces, or calling another plugin's builtins? - Does a
dependency between plugins need a declared version range, and how does that relate to `PluginApi.VERSION`?

### Feature modules and the plugin interface

**Spec:** §3.10, §4.15. **Today (after M10):** feature modules are an enabled set with guards inside `core`, not a
registration interface, and plugins register through `PluginRegistry`. The two don't share a mechanism. **Questions:** -
Should features become registered modules behind an interface, and should that interface become the public plugin
interface, so plugins and built-in features register the same way? - That would put the feature interface under the
plugins' "no compatibility promises" policy. Is that acceptable, and what would a plugin-provided feature need
(commands, actor state, phase work, rules exports, GMCP, load checks)?
