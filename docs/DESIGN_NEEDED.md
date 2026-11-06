# Design Needed

Features that aren't built and that [SPEC.md](SPEC.md) doesn't settle well enough to build without a design decision.
Each entry gives the spec sections involved, what the engine does today, and the open questions.

This is not the place for interim choices made while building: those go in [DECISIONS.md](DECISIONS.md). It's also not
for work the spec already schedules for a later milestone (M6, M7). When an entry is designed and folded into the spec,
delete it here.

---

## Combat and skills

### Skill-based advancement (worlds without character levels)

**Spec:** §3.3 (`skills: Map<Label, Int>`), §3.10 (`leveling` feature), §4.6. **Today:** a world with `leveling` off has
no `level` or `xp`, and skills change only through the harness, Kotlin code or a plugin's `setSkill` (§4.19). Nothing
tracks experience toward a skill, and no hook reports that a skill was used. Builds on the practice flow of
`//lib:trainer.star` (M33). **Questions:** - Do skills earn XP of their own (for example `skill_xp: Map<Label, Long>`),
and is the proficiency a function of it? - Is the advancement mechanism a rules export called after each use (for
example `skill_gain(ctx)`), a plugin timer or hook, or both? - What counts as a use: a cast, a hit with a weapon skill,
a failed attempt? Is there a cap per use or per time to stop farming? - Can a world mix character levels and skill
levels, and can a skill's level gate on the character's? - Which engine code may still read `level` when `leveling` is
off? The template rules read it in `to_hit` and `regen`; those are template choices, but the kernel must not assume it.
Also: the "Skill proficiency" design ([MILESTONE_31.md](MILESTONE_31.md)) makes proficiency drive `skill_check`
(success), `skill_power` (a cast's strength) and `item_power` (the strength of an item's `use_effect` and procs, which
rules may base on a passive skill such as an artificer's), and leaves all gain to this entry; M33 added trainers, which
raise proficiency by practice sessions. This design must also cover gain through use, including: a failed `skill_check`
pays cost and lag but fires no hook (`on_cast` fires only when the skill takes effect), so a use hook must report failed
attempts itself; skill procs skip `skill_check` and `skill_power`, so decide whether a proc counts as a use; and decide
whether a passive skill that only a rules export reads, such as one feeding `item_power`, can advance, and what event
counts as using it.

### Rerolls and point-buy at character creation

**Spec:** §4.6 (`starting_stats`), §4.19, §5.12. **Today (after M11):** creation offers only discrete choices from the
world's creation provider. `rules.starting_stats` sets the numbers once, possibly randomized, and the player never sees
them before confirming or gets to change them. **Questions:** - Should the confirmation show the rolled stats and offer
`reroll`? Is the number of rerolls limited, and who sets the limit (the rules module or the provider)? How do rerolls
stay deterministic for replays and the fuzz walker? - Should point-buy exist, as a new kind of creation step that takes
free-form input (`str +2`, `done`) instead of picking an option? Would the provider handle that input, or the rules
module through a new export such as `point_buy(ctx, spend)`? - How would a point-buy or rerolled result reach
`starting_stats`: as input in `ctx`, or as replacing its result?

### Friendly fire and area-of-effect harm to allies

**Spec:** §5.4 (Engagement: a hostile skill, including each actor in the room for a `room`-range skill, engages its
victim), §4.3 (hostile primitives), §4.2 and §4.6 (`before_attack`, its attach points). M37 added player groups and
group members in `allies_near`, but no rule about harming them. **Today:** `before_attack` attaches only to mobs and
items, so `//lib` cannot veto a player's attack on a groupmate. The engine does not treat groupmates, pets or owners
differently from anyone else when it resolves the targets of an attack or an area skill. **Questions:** - Should a world
decide whether friendly fire is allowed, and where does it say so: a rules export (for example `can_harm(ctx)`), a world
option, or an extension of `before_attack` to players? - What counts as an ally for the rule: group members, pets and
their owners, mobs of the same side, or whatever the world's export returns? - Should the rule cover every source of
harm (auto-attacks, targeted skills, area skills, affect ticks), or can a world cover only some, for example allow
friendly fire from area skills only? Allowing it for area skills is a possible deterrent against overusing them. - When
harm is refused, is a direct attack vetoed with a message while an area skill silently skips the ally, or does the world
choose? - When harm to an ally is allowed, does it engage the ally, start a fight with it, and record a contribution, or
does it only deal the damage? - Does the rule need information from the caller, such as whether the harm is deliberate
(a targeted attack) or incidental (an area effect)?

## Mobs and AI

### Hostility and factions

**Spec:** §4.7 (`aggressive(toward)`, `assist(allies)`, `guard`), §3.7 (the example `faction` shared namespace).
**Today:** "hostile" means "engaged with" (D-027). Behaviors pick targets by `players`, `mobs` or label pattern (D-121).
Nothing remembers who attacked whom once a fight ends. **Questions:** - Is there a faction or reputation model the
engine understands, or is it only a `//lib` convention on shared attributes? - Should mobs remember attackers (a hate
list) after a flee or a disengage?

### Target preference for aggressive mobs

**Spec:** §4.7 `aggressive(toward, ...)`. **Today:** an aggressive mob attacks the lowest-id actor that matches `toward`
and passes its level and perception checks. The engine has no notion of a role such as healer, and `ActorFilter` matches
only `players`, `mobs`, `all` and label patterns. **Questions:** - Should `aggressive` (or `assist`) take a preference,
such as `prefer=...`, that orders candidates by something other than id? - If so, what does it match on: plugin traits,
tags, a role model, or built-in orderings such as `weakest` and `strongest`? - Is the preference data with value
equality (behaviors are compared by the version-bump lint, §7.3), or can it be a script function? - How does it relate
to a hate list and factions ("Hostility and factions"), which would also change whom a mob prefers? - Should an
aggressive mob wait a number of pulses after a target arrives before attacking, which needs per-mob memory of arrivals?

## Items

### Corpses and looting

**Spec:** §5.4 (a mob corpse holds inventory and loot; a player corpse is left behind). **Today:** anyone can loot any
corpse. A player corpse decays like any other and drops its contents on the ground (D-038). **Questions:** - May other
players loot a player's corpse? - Does a player corpse last longer than a mob's, or is it protected? - Should the owner
be able to recover their corpse after logging out?

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

### Global and cross-zone event subscriptions

**Spec:** §4.6 (`rules.sense_cost`), §5.7 (Perception, `on_perceive`). **Today:** event propagation is sensory and
strictly local, priced across hops through the room graph and limited to adjacent rooms. There is no global
publish/subscribe event bus or cross-zone notification system. **Questions:** - Should the engine support pub/sub
channels or global event subscriptions (e.g., world-wide weather shifts, regional faction events)? - How to ensure
cross-zone event listeners do not wake dormant reality bubbles or violate pulse execution budgets?

## Social and communication

### Showing groups in `who`, `score` and clients

**Spec:** §3.3 (Groups), §4.9 (`who`, `score`), §4.10 (`Char.Status`, `Char.Group`). **Today (after M37):** groups
exist, `Char.Group` describes a player's group, and `//lib:group.star` lists it with `group`. `who`, `score` and
`Char.Status` show nothing about groups, and the bundled web client ignores `Char.Group`. **Questions:** - Should `who`
mark grouped players or their leader, and should `score` name the player's group? - Should `Char.Status` carry the group
id, or is `Char.Group` enough? - How should the web client show the group (a panel with members' vitals, a marker in the
room list)?

## Economy and crafting

### Crafting

**Spec:** §3.3 (items, `variants`), §4.3 (skills and proficiency). **Today:** the engine has no crafting recipes. Items
can be transformed into variants, spawned and destroyed by scripts, but nothing turns materials into a product. Split
from the shops design because it is much more involved; it can use `skill_check` and `item_power` (§4.3) for skill
checks. **Questions:** - Should crafting ship as a standard `//lib` library or require engine primitives? - How should
recipes define material requirements, tools, workstations and skill checks? - What happens on failure (materials lost, a
flawed product, nothing)? - How are item quality variations represented (variants, per-instance tags or affects, name
overrides)? - Does crafting take time (a warmup or lag), and can it be interrupted? - Does it work without the `skills`
feature?

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

### In-game character respec and choice changes

**Spec:** §4.19. **Today:** the engine provides mutation primitives (`setChoices`, `grantTrait`, `removeTrait`), but no
built-in player command, trainer, or shrine mechanic to alter character choices after creation; these belong to plugins.
**Questions:** - Should the engine or standard sample plugins provide a canonical respec flow (e.g. shrines, guild
trainers)? - What restrictions, cooldowns, or currency costs should govern resetting choices or traits?
