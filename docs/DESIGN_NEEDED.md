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

### Cross-room projectiles and ranged combat between rooms

**Spec:** §3.3 (item `range`, `kind = "ranged"`), §5.5. **Today:** all combat is strictly self-contained within a single
room. The tactical track, range bands, advance, and retreat operate only inside the room; no attacks or projectiles
cross exits. **Questions:** - How should line-of-sight and projectile paths across open or closed doors be evaluated? -
Does a ranged attack initiate combat by dragging the attacker into the target's room/track, or does it create a
cross-room engagement? - How do perception and aggro propagate across rooms when an attack is fired from a distance?

### Combat procs: items and affects that cast on attack

**Spec:** §4.3 (skills and effect primitives), §4.4 (hooks), §4.6 (auto-attacks). **Today (after M18):** every equipped
item with `dice` makes its own auto-attack swing, so an amulet can deal weapon-style damage through the `to_hit` and
`damage` chains. An equipped item's `on_combat_tick` hook already runs once per round, so a script can fire a per-round
effect. Nothing ties an effect to a hit: "10% chance on hit to cast `fireball`" has no hook that fires after a swing
lands, `before_attack` is a veto hook that fires before the roll, and `on_damage` fires for any damage in the room.
Affects have no combat hook at all. **Questions:** - Is this a new non-veto hook (such as `on_hit` or `after_attack`)
dispatched to the attacker's held items and affects, or a declarative `item(attack_skill=..., chance=...)`? - Does it
fire per swing or per hit? - Does the proc go through `cast_allowed`, warmup, cost and lag, or resolve its effects
directly? - Can a defender's items proc when struck (thorns, retaliation)?

### Typed bonus damage on attacks

**Spec:** §4.6 (`damage` modifier point, `mitigate`). **Today (after M18):** the `damage` modifier chain changes the
amount of a hit but not its type, so a slashing sword can't also deal 2 fire damage. **Questions:** - Should items,
affects and traits carry declarative per-type bonus damage, or should `damage` modifiers be able to return extra typed
amounts? - Is each extra type mitigated separately, and does it fire `on_damage` and `interrupt_check` once per type or
once per hit? - Does bonus damage apply to spell damage as well as auto-attacks?

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
`aggressive` take a chance or a delay? Also, should we support "unless_level_below" and more complex player targeting
like `attack_healers_first`?

### Behavior trees and GOAP

**Spec:** §4.7 (mob behaviors, `think` hooks). **Today:** mob AI is built from declarative primitive behaviors
(`patrol`, `wander`, `follow`, `pursue`, `aggressive`, `assist`, `guard`) and custom Starlark logic in `think` hooks.
The engine does not provide a native Behavior Tree or Goal-Oriented Action Planning (GOAP) runtime. **Questions:** -
Should complex AI frameworks like behavior trees or GOAP be built as Kotlin primitives in `core` or as a standard
`//lib` Starlark library? - How would planning algorithms remain within the Starlark step and allocation limits (§4.11)
during expensive state evaluations?

## Items

### Corpses and looting

**Spec:** §5.4 (a mob corpse holds inventory and loot; a player corpse is left behind). **Today:** anyone can loot any
corpse. A player corpse decays like any other and drops its contents on the ground (D-038). **Questions:** - May other
players loot a player's corpse? - Does a player corpse last longer than a mob's, or is it protected? - Should the owner
be able to recover their corpse after logging out?

### Encumbrance beyond stats

**Spec:** §4.6 (`carry_limit`, `encumbrance`, the modifier chain). **Today:** encumbrance is a set of stat deltas only
(D-115). It doesn't affect move cost, lag or the ability to move. Since M19, recovery lag has modifier points
(`advance_lag`, `retreat_lag`, `retreat_fail_lag`, `flee_fail_lag`) alongside `move_cost`, but nothing lets encumbrance
reach them: rules and modifiers can't tell that an actor is over its carry limit (there is no `encumbered` actor view
field), and `encumbrance(ctx)` can only return stats. **Questions:** - Should actors expose a read-only `encumbered`
view field, so rules exports and modifiers can add lag or move cost while it is set? - Or should being over the limit
apply an engine-managed affect that carries `modifiers` (and its stat deltas), so encumbrance uses the ordinary chain? -
Or should `encumbrance` return move-cost and lag changes directly? - Should movement be forbidden past a hard limit, and
is that a second rules export or part of the same result?

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

### Enchantments on rooms

**Spec:** §4.2 (room `tags` and `modifiers`, `region` modifiers), §4.6 (the modifier chain). **Today:** a room's tags
and modifiers come only from its prototype and its regions, so a script or plugin can't consecrate, ward or curse one
room for a while. Items gain per-instance tag changes and affects; rooms don't. **Questions:** - Should rooms carry
enchantments at all, and should they work through per-instance tag changes like items, through timed room affects that
contribute modifiers to the chain, or through some other mechanism? - Does a zone reset keep, clear or restore a room's
enchantments? - Do room enchantments tick and expire while the room is dormant, or catch up on wake as mob affects do?

## Commands

### Exits named after a plugin command

**Spec:** §4.9 (reserved exit names), §4.15, §5.2. **Today:** a package fails to load if an exit is named after an
engine command, an engine alias or a global command. Plugin commands aren't checked: the script loader doesn't know
which plugin commands are registered. Movement is resolved before plugin commands, so an exit named after a plugin
command silently hides that command in the exit's room. **Questions:** - Should the loader receive the registered plugin
command names and aliases, and reject such an exit like any other reserved name? - Plugins load before packages but can
be added or removed between runs. Does a newly added plugin command fail packages that loaded fine before, or does the
plugin fail instead? - Do role-gated plugin commands reserve the name for everyone, even players who can't use them?

## Social and communication

### Player groups and parties

**Spec:** §4.7 (`assist`, `follow`), §4.6 (`xp_for_kill`), §5.4 (Death). **Today:** the engine has no group or party
concept; XP goes solely to the killer, and assisting allies or followers do not share rewards. Related design: "Group,
party and pet XP sharing". **Questions:** - Should party formation, invitations, party leadership, group chat channels,
and friendly-fire prevention be engine primitives or implemented in `//lib`? - How should party state handle player
disconnects, zone transfers, and persistence across logout?

### Mail, message boards, and additional channels

**Spec:** §4.9 (Communication commands: `say`, `tell`, `whisper`, `shout`, `gossip`). **Today:** communication is
synchronous and limited to built-in channels. There is no offline mail system, in-game message board / bulletin system,
or builder-defined chat channels. **Questions:** - Should asynchronous mail and message boards be backed by SQLite
tables in `persist`, or can they be built as `//lib` packages using custom entities and attributes? - How are retention,
moderation, quotas, and cross-character delivery handled?

## Economy and crafting

### Crafting

**Spec:** §3.3 (items, `variants`), §4.3 (skills and proficiency). **Today:** the engine has no crafting recipes. Items
can be transformed into variants, spawned and destroyed by scripts, but nothing turns materials into a product. Split
from the shops design because it is much more involved; it depends on "Skill proficiency" for skill checks.
**Questions:** - Should crafting ship as a standard `//lib` library or require engine primitives? - How should recipes
define material requirements, tools, workstations and skill checks? - What happens on failure (materials lost, a flawed
product, nothing)? - How are item quality variations represented (variants, per-instance tags or affects, name
overrides)? - Does crafting take time (a warmup or lag), and can it be interrupted? - Does it work without the `skills`
feature?

## World building and administration

### In-game OLC editing

**Spec:** §1.2, §2.1, §2.3. **Today:** world building is strictly file-based through Starlark source files committed to
git and verified by CI gates (§7.3). There is no online creation (OLC) command set (`@redit`, `@medit`, `@oedit`).
**Questions:** - If builders could edit rooms, mobs, and items live in-game, how would mutations persist back to
Starlark code and git branches? - How would schema validation, CODEOWNERS boundaries, and concurrent edit conflicts be
handled?

### Live @reload on shared production servers

**Spec:** §2.4, §5.10. **Today:** `@reload` and `@eval` are permitted only in `--dev`. In `--prod`, code changes require
the deploy pipeline (`gups deploy` / `@deploy`) to ensure atomic checkpointing, reconciliation, and consistency.
**Questions:** - Can builders safely reload individual packages on a live shared server without desynchronizing state,
orphaning references, or disrupting active player sessions? - What role permissions or locks would be required?

### Schema and feature migrations without a server wipe

**Spec:** §2.4, §3.9, §3.10, §5.10. **Today:** changes to declared stats, vitals, or enabled features in `MODULE.gups`
require starting with `--wipe`, resetting all persistent characters and items. **Questions:** - Could the persistence
layer migrate existing character and item records (e.g. populating defaults for newly added stats, pruning removed
vitals, recalculating derived values) rather than requiring a server wipe? - How should automated migrations handle
incompatible feature flag changes?

## Accounts and authentication

### Open self-signup

**Spec:** §2.4, §5.12. **Today:** `open_signup = False` by default in `MODULE.gups`. Accounts require an invite code or
roster import. Enabling `open_signup = True` allows registration at the telnet login prompt, but remains minimal.
**Questions:** - What anti-abuse controls (rate limiting, CAPTCHA, email verification, or registration gates) are
required before open signup is safe for public internet servers? - How does open signup interact with web client invites
and session tokens?

### Self-service password reset

**Spec:** §4.9, §5.12. **Today:** there is no self-service password reset. Instructors reset passwords using `@role
<account> reset`, which generates a new setup code. **Questions:** - Without native email infrastructure, how should
self-service resets be authenticated (e.g., web recovery tokens, OAuth provider integration, or reset codes)?

### Team owners in CODEOWNERS

**Spec:** §2.4, §5.12. **Today:** `CODEOWNERS` recognizes only individual `@user` GitHub handles (matched against
account `github_handle`). Team handles (`@org/team`) are ignored. **Questions:** - How should team membership be
resolved and synchronized: via GitHub REST API calls during deploy, manual configuration in `auth`, or roster CSV
imports?

## Networking and protocols

### MSDP and MXP protocols

**Spec:** §4.10, §4.13. **Today:** telnet negotiations support NAWS, TTYPE/MTTS, GMCP, EOR, and CHARSET. MSDP and MXP
are explicitly unsupported; structured client communication relies entirely on GMCP. **Questions:** - Is there
sufficient demand from legacy MUD clients to warrant implementing MSDP and MXP parsers and formatters? - How would MXP
formatting tags integrate with ANSI rendering and web client pipelines?

### Built-in ACME certificate issuance

**Spec:** §4.10, §4.13. **Today:** the engine monitors PEM certificate files on disk and live-reloads them when changed,
relying on an external client (such as certbot) to negotiate ACME issuance and renewal. **Questions:** - Should the
engine embed an ACME client (responding to HTTP-01 or TLS-ALPN-01 challenges directly) to manage Let's Encrypt
certificates without external dependencies?

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
