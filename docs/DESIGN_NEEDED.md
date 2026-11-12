# Design Needed

Features that aren't built and that [SPEC.md](SPEC.md) doesn't settle well enough to build without a design decision.
Each entry gives the spec sections involved, what the engine does today, and the open questions.

This is not the place for interim choices made while building: those go in [DECISIONS.md](DECISIONS.md). It's also not
for work the spec already schedules for a later milestone (M6, M7). When an entry is designed and folded into the spec,
delete it here.

---

## Combat and skills

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
(a targeted attack) or incidental (an area effect)? - Should the ally rule ask `standing` (§5.14), for example treating
an actor the attacker's standing toward is above a threshold as an ally?

## Items

### Corpses and looting

**Spec:** §5.4 (a mob corpse holds inventory and loot; a player corpse is left behind). **Today:** anyone can loot any
corpse. A player corpse decays like any other and drops its contents on the ground (D-038). **Questions:** - May other
players loot a player's corpse? - Does a player corpse last longer than a mob's, or is it protected? - Should the owner
be able to recover their corpse after logging out?

## Perception and the world

### Global and cross-zone event subscriptions

**Spec:** §4.6 (`rules.sense_cost`), §5.7 (Perception, `on_perceive`). **Today:** event propagation is sensory and
strictly local, priced across hops through the room graph and limited to adjacent rooms. There is no global
publish/subscribe event bus or cross-zone notification system. **Questions:** - Should the engine support pub/sub
channels or global event subscriptions (e.g., world-wide weather shifts, regional faction events)? - How to ensure
cross-zone event listeners do not wake dormant reality bubbles or violate pulse execution budgets?

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

### More trait event callbacks

**Spec:** §4.15 (traits, event listeners), §4.19 (effects of traits). **Today:** `PluginTrait.on` maps a
`PluginTraitEvent` to a handler that may change the world, run for each bearer of the trait. The events are
`SKILL_RECOGNIZED`, `DAMAGED`, `HEALED`, `KILLED`, `DIED`, `LEVEL_CHANGED`, `ROOM_ENTERED`, `AFFECT_GAINED` and
`AFFECT_LOST`; handlers run inline at the event, after the entity hooks there, within the cascade depth cap. Only
plugins can declare them. **Questions:** - (Done in Milestone 50) Should there be affect-gained and affect-lost trait
events, and do they fire for affects that expire, are dispelled, or are replaced by a reapplication? - (Moved to ICEBOX:
Starlark-defined choices, traits, and restrictions) Should world content get a way to declare traits, or player-scoped
hooks, so the same behavior doesn't need a plugin? The trait events and dispatch sites it would build on now exist.

### In-game character respec and choice changes

**Spec:** §4.19. **Today:** the engine provides mutation primitives (`setChoices`, `grantTrait`, `removeTrait`), but no
built-in player command, trainer, or shrine mechanic to alter character choices after creation; these belong to plugins.
**Questions:** - Should the engine or standard sample plugins provide a canonical respec flow (e.g. shrines, guild
trainers)? - What restrictions, cooldowns, or currency costs should govern resetting choices or traits?
