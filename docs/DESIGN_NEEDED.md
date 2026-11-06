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

### Friendly fire and area-of-effect harm to allies

**Spec:** §5.4 (Engagement: a hostile skill, including each actor in the room for a `room`-range skill, engages its
victim), §4.3 (hostile primitives), §4.2 and §4.6 (`before_attack`, its attach points). Related design: "Player groups
and parties", whose milestone adds groups and `allies_near` but not any rule about harming them. **Today:**
`before_attack` attaches only to mobs and items, so `//lib` cannot veto a player's attack on a groupmate. The engine
does not treat groupmates, pets or owners differently from anyone else when it resolves the targets of an attack or an
area skill. **Questions:** - Should a world decide whether friendly fire is allowed, and where does it say so: a rules
export (for example `can_harm(ctx)`), a world option, or an extension of `before_attack` to players? - What counts as an
ally for the rule: group members, pets and their owners, mobs of the same side, or whatever the world's export returns?
\- Should the rule cover every source of harm (auto-attacks, targeted skills, area skills, affect ticks), or can a world
cover only some, for example allow friendly fire from area skills only? Allowing it for area skills is a possible
deterrent against overusing them. - When harm is refused, is a direct attack vetoed with a message while an area skill
silently skips the ally, or does the world choose? - When harm to an ally is allowed, does it engage the ally, start a
fight with it, and record a contribution, or does it only deal the damage? - Does the rule need information from the
caller, such as whether the harm is deliberate (a targeted attack) or incidental (an area effect)?

## Mobs and AI

### Pets and followers bonded to a player

**Spec:** §5.9 says "Pets and followers bonded to a player get the reason `bond:<player_id>`." §4.7 has
`follow(target)`. **Today:** - Nothing creates a bond. - `follow` latches onto the first matching actor it sees, and
drops it after more than 3 exits (D-121). - Scripts can't tell a mob whom to follow. **Questions:** - Is there a verb
such as `ctx.bond(mob, player)`, or a `follow`/`order` command? - Who adds and clears the `bond:` active reason, and
does it survive logout? - Does a pet fight for its owner, and does it count as an ally for `assist`? Does spawning a pet
provide hooks that can be used for mobs/characters to react? Could we allow counterspells to prevent a spawn?

**Revisit with the group and XP sharing milestone:** that milestone identifies a pet's owner for `xp_share` by reusing
`Mob.summonedBy`, else `Mob.leader` when the leader is a player, rather than adding an explicit owner. When this entry
is designed, decide whether bonds need a first-class `owner` field and update the `xp_share` contributor `owner` lookup
to read it.

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

### Player groups and parties

**Spec:** §4.7 (`assist`, `follow`), §4.6 (`xp_for_kill`), §5.4 (Death). **Today:** the engine has no group or party
concept; XP goes solely to the killer, and assisting allies or followers do not share rewards. Related design: "Group,
party and pet XP sharing". **Questions:** - Should party formation, invitations, party leadership, group chat channels,
and friendly-fire prevention be engine primitives or implemented in `//lib`? - How should party state handle player
disconnects, zone transfers, and persistence across logout?

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

## Scripting and runtime

### Transactional script execution

**Spec:** §5.13, §6.2. **Today:** script verbs apply immediately when called. If a hook aborts or throws an error,
mutations already performed stay applied; there is no rollback. **Questions:** - Should script execution support atomic
transactions where failures rollback mutations? - What performance overhead would mutation buffering or journaling
introduce for frequent hooks like `think` and `on_pulse`?

### Supporting multiple api_versions concurrently

**Spec:** §1.3, §4.17. **Today:** the engine supports exactly one `api_version` at a time. Packages declaring a
different version fail to load. **Questions:** - Should the engine support running legacy zones alongside newer packages
by maintaining multiple Starlark API bindings concurrently? - How would compatibility shims be structured, and what is
the maintenance cost across multiple API versions?

### Starlark-defined choices, traits, and restrictions

**Spec:** §4.2, §4.19. **Today:** character creation choices, traits, and restrictions can only be declared in Kotlin by
plugins. Starlark content can tag mobs with existing trait names, but cannot define new choices, traits, or restriction
logic. **Questions:** - Should Starlark packages (`//rules` or `//lib`) be able to declare custom traits and creation
choices without requiring a compiled JVM plugin? - How would Starlark-defined restrictions be evaluated during character
creation and item equips without performance penalties?

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

### Plugin marketplace, plugin hot reload, and plugin sandboxing

**Spec:** §4.15. **Today:** plugins are `.jar` files in `plugins/`, pinned by SHA-256 in `MODULE.gups`, loaded at boot
via `URLClassLoader`, and run with full JVM permissions. There is no plugin marketplace, dynamic hot reload, or security
sandboxing. **Questions:** - Can plugins be reloaded dynamically without classloader leaks or state desynchronization? -
What sandboxing mechanism is viable on modern JVMs to isolate untrusted plugin code? - Should a discovery registry or
package manager exist for community plugins?

### In-game character respec and choice changes

**Spec:** §4.19. **Today:** the engine provides mutation primitives (`setChoices`, `grantTrait`, `removeTrait`), but no
built-in player command, trainer, or shrine mechanic to alter character choices after creation; these belong to plugins.
**Questions:** - Should the engine or standard sample plugins provide a canonical respec flow (e.g. shrines, guild
trainers)? - What restrictions, cooldowns, or currency costs should govern resetting choices or traits?

### Plugins calling custom hooks

**Spec:** §4.4, §4.5, §4.15. **Today (after the shops milestone):** rooms, mobs and items can declare custom hooks
(`hooks = {...}`), scripts run them with `ctx.call_hook(entity, name, **args)`, and plugins can join a hook's handler
chain with `r.addHook(name, doc)`. Kotlin plugin code can't *call* a custom hook: a plugin-run feature (for example a
plugin shop asking a keeper's `shop_price`) has no `PluginWorld.callHook`. **Questions:** - Should the facade gain
`callHook(entity, name, args): Any?`, running the same chain as `ctx.call_hook`? - How are Starlark return values
converted to plain values for Kotlin, and plugin arguments to Starlark values? - Can a plugin's `callHook` run from
inside a GMCP input handler or a hook handler (read-only contexts), given that the Starlark hook may change the world? -
Should a plugin be able to call a hook that its own handler is part of, and how is recursion bounded?

## Operations, scalability and testing

### Multi-server sharding, distributed tracing, and external log aggregation

**Spec:** §1.1, §2.2, §5.1. **Today:** GUPS is designed as a single-process server with an in-memory tick and SQLite
persistence. Distributed tracing and external log aggregation are not built-in. **Questions:** - How would zone
handoffs, cross-zone combat, and item custody work across a sharded cluster? - Should standard OpenTelemetry
instrumentation be integrated for distributed metrics and tracing?

### Dedicated multi-client load testing

**Spec:** §7.1, §7.4 (M6). **Today:** headless harness tests and fuzz walkers verify state transitions and game rules,
but end-to-end load tests with hundreds of simulated telnet/web clients were deferred. **Questions:** - What tool or
harness should drive synthetic player traffic to validate the 200-player and tick-latency targets of §7.1? - How should
load testing be incorporated into CI without exceeding runner resource limits?

### Automated api_version bump in release jobs

**Spec:** §4.17. **Today:** bumping `api_version` is a manual pre-release step because CI validates the API baseline
against checked-in files, and a tag-triggered release workflow cannot commit back to git. **Questions:** - Can tooling
or a release bot detect breaking script API changes and automate the version bump, doc regeneration, and baseline update
as part of a pre-release PR?
