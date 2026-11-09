# Changelog

Script-facing changes are listed per `api_version`, with before/after snippets for breaking ones.

## api_version 1 (unreleased)

### Movement pace and pursuit catch-up

- New optional rules export `move_pace(ctx)` and modifier point `move_pace`: hundredths of a pulse per room (default
  100). Actor views gain `move_pace`.
- Walking pace: each move adds `move_pace` to a movement debt, and an actor moves only while the debt is below 100. Fast
  actors make up to four complete moves a pulse.
- Breaking (timing): a mob's think now runs the action it chooses in the same pulse, so mob actions happen one pulse
  sooner than before.
- Chained thinks: the optional rules export `chain_think(ctx)` lets a mob think again as soon as its action finishes
  (`ctx.behavior`, `ctx.command`, `ctx.exit`, `ctx.chain`). By default, `pursue`, `follow`, `heel` and walking home
  chain, so pursuers catch up at their `move_pace`.
- Plugins can adjust chained thinks with `addChainThink`.

### Mob leashes and bounded pursuit

- `pursue` takes `range` (default 10 exits, `None` for no limit) and searches once per think instead of pathing to every
  match in the world; it no longer considers dead actors.
- Mobs have a `leash`: `mob(leash=)` (default `None`), the `set_leash` / `reset_leash` verbs, and the `leash` /
  `leash_overridden` view fields.
- `wander`, `follow` and `pursue` stay within a mob's leash.
- A leashed mob outside its home ground walks all the way home once nothing outranks it (an engine proposal at priority
  20, source `leash`). It stays put while its `follow` leader or `pursue` quarry shares its room inside the leash.
- `wander` outside its radius now heads home instead of standing still.
- New optional rules export `leash_return(ctx)` decides when a leashed mob heads home (`ctx.away`, `ctx.past_leash`).

### Target preference, retargeting and combat memory

- Breaking: `aggressive` no longer takes `via`. A mob notices on every channel it senses (its effective `senses` above
  0), so declare a deaf or blind mob through `mob(senses=)` instead.
- `aggressive(prefer=)` and `assist(prefer=)`, with the optional `target_order(ctx)` rules export that picks among the
  candidates or returns `None` for nobody.
- New behavior `retarget(prefer, ...)`: an engaged mob asks `target_order` whom to fight and switches to its pick,
  including an actor not yet in the fight.
- Combat memory: with `module(memory_pulses=)` or `mob(memory_pulses=)`, a mob remembers who attacked and damaged it and
  when it arrived, merged per subject and event and forgotten after the window.
- Combat memory also records the events a mob perceives, and the entrances, casts, heals, helpful affects and kills it
  witnesses in its room.
- Logging in (with any pets) and respawning after death raise a perceived `enter` in the room, as walking in does, so
  mobs there witness the arrival and `on_perceive` sees it.
- `ctx.memories(mob, ...)`, `ctx.remember(mob, subject, event, ...)` and `ctx.forget(mob, subject=None, event=None)`.
- The rules ctx `memories(...)`, so `target_order` and `can_perceive` can read what the mob remembers.
- `actor.primary_target`: the actor an engaged actor is attacking, or `None` (feature `combat`).
- Plugins: `addTargetOrder`, `PluginWorld.memories`, `PluginWorld.remember` and `PluginWorld.forget`.
- The template world keeps combat memory for 5 minutes and ships a sample `target_order` (weakest, strongest, hated,
  threat with fading weights, arrival delay and switch hysteresis). A hidden actor moves silently, but one who makes
  noise can be noticed by sound for a moment.
- The template world has a `pacify` spell: it ends a mob's fight with the caster, makes the mob forget the caster with
  `ctx.forget`, and keeps it from noticing anyone for a while. A cast is now remembered before its effects and `resolve`
  run, so `resolve` can make a mob forget it.

### Skill advancement through use

- The global `on_skill_use` hook (feature `skills`): every cast that resolves reports `"fail"`, `"resist"` or
  `"success"`, so a world can advance skills through use.
- `ctx.report_skill_use(actor, skill, target=None, item=None)` fires `on_skill_use` with `"report"`, for skills that
  only rules read, such as an artificer's.
- Plugins: `PluginWorld.reportSkillUse` fires `on_skill_use` with `"report"`, and `onEvent("on_skill_use", ...)`
  delivers `skill`, `outcome`, `target`, `item` and `targets` after the script global hooks.
- The robot world has a sample `//lib:skill_gain.star`: deterministic advancement through use with a tunable curve, and
  a `self_repair` routine learned from a repair chip.

### Custom creation steps and base values

- Plugins can set base stats, base vital maxima and base resists with `setBaseStat`, `setBaseMaxVital` and
  `setBaseResist`.
- Creation providers can declare custom steps (`PluginCreationStep`) shown after the choices, with a per-attempt seed,
  random streams, shared state and a preview actor, and a `prepare` callback that runs before a new character enters the
  world.
- `spawn_player(steps = {...})` runs a world's custom creation steps in tests.
- `starting_stats` draws from a per-attempt generator while a character is created through the creation steps.

### Feature-sized plugins

- `global_hook("on_death", fn)` fires once per death, after the victim's, room's and observers' `on_death` hooks and
  before the corpse and XP award, with `ctx.self` the victim and `ctx.actor` the killer.
- `ctx.has_feature(name)` also reports features provided by plugins, and returns `False` (instead of raising an error)
  for a well-formed name that is not a known feature.

  Before:

  ```python
  ctx.has_feature("hunger")  # raises: unknown feature 'hunger'
  ```

  After, with no plugin providing `hunger`:

  ```python
  ctx.has_feature("hunger")  # False
  ```
- Plugins can provide features, refuse a world at load, run periodic work and listen to engine events; see
  [`docs/PLUGINS.md`](PLUGINS.md).

### Groups and levels in who and score

- `who` shows each player's level (with `leveling`) and group leader: new entries `leveling.who_level` and `who.group`,
  and new `{level}` and `{group}` placeholders in `who.player` and `who.linkdead`. Override a fragment to `""` in
  `MESSAGES.gups` to hide it.
- `score` shows `score.group` while the character is in a group.

### Standing and factions

- The optional `standing` feature module introduces factions, declared opinions and standing history. Leaving `features`
  out in `MODULE.gups` enables it along with all other features.
- `faction(name, standing = {}, doc = "")` declares a content faction `//<package>:<name>` and its declared opinions of
  other factions (each in -100..100). A faction with the same label as a mob prototype in the same package configures
  that prototype's implicit faction.
- `mob(factions = [...])` specifies the factions a mob prototype belongs to; when omitted, it defaults to its own
  prototype label.
- `module(standing_history_pulses = 14400)` sets how long the engine keeps standing history in pulses (at least 1; needs
  `standing`).
- Personal standing records on mobs, faction standing records on the world, history queries, and pruning. A mob's
  disposition toward an actor (what it thinks of them) defaults to the sum of the declared, faction-to-actor,
  faction-to-faction, and personal parts, clamped to -100..100.
- Optional rules exports `rules:factions(ctx)`, `rules:disposition(ctx)` and `rules:standing_change(ctx)`, with
  `rules_ctx.disposition_parts`, `rules_ctx.amount`, `rules_ctx.helped`, and `rules_ctx.pet`.
- `standing_delta(holder, subject, amount)` constructs a delta value inside `rules.standing_change`.
- Rules `ctx` query methods `factions_of(actor)`, `standing(holder, subject)` (a record's amount) and
  `standing_history(holder, subject, since = 0, via = None, event = None)`.
- Built-in standing events: mobs remember attackers when `standing` is on, and the engine automatically raises `attack`,
  `kill`, `heal`, `help`, `assist`, and `give` events.
- Script hook context queries `ctx.disposition(holder, subject)`, `ctx.standing(holder, subject)` (a record's amount,
  not including declared faction opinions) and `ctx.factions_of(actor)`.
- Script hook context verbs `ctx.standing_history(holder, subject, since = 0, via = None, event = None)`,
  `ctx.standing_event(affected, actor, event, amount = 0, item = None)`, `ctx.adjust_standing(holder, subject, amount)`,
  and `ctx.clear_standing(holder, subject = None)`.
- Disposition thresholds on behaviors: `disposition_below` on `aggressive` and `pursue`, and `disposition_above` on
  `assist`, `guard` and `follow`.
- Saved standing: faction standing records in the `standings` database table, personal standing records with history in
  the mob payload, checkpoint pruning, and dropping of orphaned records on restore and reload.
- `PluginRegistry.addDispositionModifier(doc, adjust)` allows plugins to adjust a disposition after the rules export in
  plugin-id order; `PluginRegistry.addFactions(doc, adjust)` allows plugins to adjust an actor's faction labels in
  plugin-id order.
- `PluginWorld` exposes `disposition(holder, subject)`, `standing(holder, subject)`, `factionsOf(actor)`,
  `raiseStandingEvent(affected, actor, event, amount, item)` and `adjustStanding(holder, subject, amount)`.
- An actor's cached factions are forgotten when its owner changes, so a `factions` export may base a pet's factions on
  its owner. Asking for the factions or the disposition being worked out (`ctx.factions_of` of the same actor inside the
  `factions` export, or a plugin handler asking for the value it adjusts) is an error instead of endless recursion.
- Faction records about a pet held out of the world while its owner is offline are kept at checkpoints and on restore.
- The `standings` table is part of the base database schema; databases created before it need `--wipe`.
- The template world demonstrates standing and factions with `template-world/lib/factions.star` (`townsfolk` and
  `goblins`), `template-world/rules/standing.star` deltas with a gratitude ceiling and rate caps, disposition thresholds
  on tutorial mobs, and tutorial 11 on standing and factions.

### Pets and owners

- `summon` (the effect primitive and the verb) now sets the owner of each mob it spawns to the caster when the caster is
  an in-world player. `summonedBy` and the owner are set before the mob's `on_spawn` runs.
- **Breaking:** a mob's owner is its own `owner` field, no longer its `summonedBy` or `leader`. A mob that merely
  follows a player is no longer that player's pet for `xp_share`, groups or `allies_near`.

```python
# Before: a mob following a player counted as that player's pet in xp_share, group_of and allies_near.
# After: only a mob whose owner is set counts.
ctx.set_owner(wolf, player)
```

- `ctx.set_owner(mob, player)` and `ctx.clear_owner(mob)` set and remove a mob's owner. An owned mob gets the active
  reason `owner:<player_id>` while its owner is in the world, leaves its spawn rule, and stops fighting its owner's
  side. Both return whether anything changed.
- The actor view has an `owner` field: the view of the in-world player that owns the actor, or `None`. Because `owner`
  is now an actor view field, it can no longer be a stat or vital name.
- `on_owner_change` is a global hook that fires after each change of a mob's owner, with `ctx.actor` the mob,
  `ctx.target` the owner gained or lost and `ctx.event` `"set"` or `"clear"`.
- A mob's `owner` is saved with it.
- Every built-in behavior that has a priority takes `priority=` (`wander`, `aggressive`, `assist`, `flee`, `follow`,
  `pursue`, `scavenge`); the old fixed values are now the defaults. Proposals sort by priority, then declaration order.
- `heel(priority=30)` follows the mob's owner by the shortest path, however far. `defend(group=True, priority=50)`
  attacks whatever the owner, or with `group` the owner's in-room group members, are fighting (`combat` feature).
- A mob with an owner runs an owned behavior list instead of its prototype's `behaviors`: by default the prototype's
  `flee` and `sentinel` behaviors, then `heel()`, then `defend()` with `combat`. An owned mob's `aggressive` and
  `assist` never pick its owner, the owner's group members, or a pet of any of them.
- `owned_behaviors(ctx)` is a new optional rules export that returns the list of behaviors an owned mob runs, with
  `ctx.actor` the mob, `ctx.target` the owner (`None` while the owner is offline) and `ctx.behaviors` the prototype's
  behaviors. Behavior constructors may be called inside it. A rules error keeps the default list, and the list is
  recomputed after a reload. When defined, the load-time dry run calls it with a throwaway mob and player.
- `PluginRegistry.addOwnedBehaviors(doc, adjust)` allows plugins to adjust a mob's owned behavior list after the
  `owned_behaviors` rules export, in plugin-id order, receiving and returning behaviors as plain descriptor maps.
- **Breaking:** when a mob with an owner becomes engaged with an actor, its owner in the same room is engaged with that
  actor too. When the owner's engagement with an opponent ends (by fleeing, leaving the room, `stop_combat`, death or
  quit), its in-room pets engaged with that opponent are disengaged as well and receive `on_disengage` with the owner's
  event.

```python
# Before: ordering or letting a pet fight left its owner out of the fight.
# After: the owner is engaged with whatever its pet fights in the same room,
# and the pet leaves the fight when its owner does.
```

- `order <pet> <command>` queues a command line on one of the player's pets in the room, with the pet paying its own
  warmup and lag and the player paying none. `order pets <command>` orders each of the player's pets in the room, in
  ascending id. Ordered actions are checked again when they run: if the owner is no longer in the room or no longer owns
  the pet, the action is dropped.
- `before_order` is a vetoable hook on Mob (the pet) and Room evaluated before an order is queued, with `ctx.actor` the
  owner, `ctx.target` the pet and `ctx.text` the command line.
- `PluginRestrictionPoint.ORDER` allows plugins to restrict ordering pets.
- `before_summon` is a vetoable hook on Room and Mob (observer) evaluated before mobs are summoned by the `summon`
  primitive or `ctx.summon`, with `ctx.actor` the caster, `ctx.label` the prototype label and `ctx.count` the count. A
  veto stops the summon and `ctx.summon` returns `[]`.
- `on_summon` is an observer hook on Room and Mob that fires after all mobs of a summon have spawned and any
  `on_owner_change` has fired, with `ctx.actor` the caster and `ctx.targets` the new mobs in spawn order.
- `ctx.label` and `ctx.count` expose the prototype label and count in `before_summon`; `ctx.targets` also lists the
  summoned mobs in `on_summon`.
- Pets are stored with their owner across logout and restart: on quit, each pet leaves the world with what it carries
  and is saved in the player's immediate checkpoint. At login each pet is restored beside the player in ascending id,
  with its items, active `owner:` reason and owned behaviors. A held pet whose owner's character or prototype no longer
  exists is dropped with a log note.
- The tutorial zone defines a summonable spectral familiar (a pet with default owned behaviors) and the arcane school
  adds the `familiar` spell.

### Groups and shared experience

- Each actor holds a transient record of the damage, healing and support other actors do toward fights against it, kept
  on the victim and never saved. Damage and healing an affect does from its own `on_tick` or `on_expire` hook, naming no
  `source`, are credited to the actor that applied the affect while it is in the world.
- `heal` takes an optional `source`, the actor credited with the healing; an omitted or `None` source is the applier of
  the affect whose hook is running.
- `clear_contributions(victim, contributor=None)` empties an actor's contribution record, or removes one contributor's
  entry. The engine never expires the record itself.
- `xp_share(ctx)` splits a kill's experience pool among actors, after `xp_for_kill` prices it. `ctx.contributors` are
  the victim's contributors as structs, and `ctx.xp` is the pool. It is the one optional rules export: the default keeps
  today's behavior, paying the killer the whole pool. When defined, the load-time dry run calls it with a pool of 100
  and two contributors.
- Player groups are an in-memory set of players with a leader: `group_add(member, to)`, `group_remove(member)`,
  `group_set_leader(member)` and `group_disband(member)`, with the queries `group_of(actor)` and `group_members(actor)`.
  A group is session-scoped: a quit, a link-dead expiry, or any removal of a member from the world drops that member,
  the leader on leaving is replaced by the lowest-id remaining member, and a group left with one member is disbanded.
- `on_group_change` is a global hook that fires for each group change, with `ctx.event` the `"join"`, `"leave"`,
  `"leader"` or `"disband"` and `ctx.group` the group after that step; `group_of` returns the same `group` view. Each
  change is complete before its events fire, and a hook that disbands the group drops the events still pending for it.
- `ctx.contributors` now also lists every in-world member of a contributor's group, even with no contribution and
  wherever it is, so `xp_share` can pay an idle member; each carries `group` and `in_room`.
- The `Char.Group` GMCP state package shows a player's group, or `null` without one, recomputed when it changes.
- `on_disengage` fires on an actor and its room, once for each actor that was engaged and has no opponent left, with
  `ctx.event` the `"flee"`, `"death"` or `"stop"`. A dying actor itself does not get the hook, and the engine clears no
  ledger: a world calls `clear_contributions` from it. It fires once the move, quit or death that ended the engagement
  has finished, so a hook can't re-engage an actor that is half gone, and a quitter does not get it.
- Nothing engages an actor while its death runs, so an `on_death` hook can't leave its killer fighting a removed mob:
  `ctx.start_combat` with a dying actor is an error naming it.
- `allies_near` now also returns members of the actor's group, and their pets, within the band and not fighting it.
- `global_hook` also accepts `on_disengage`, so a privileged package can react whenever any engaged actor loses its last
  opponent.
- The template world's `xp_share` splits a kill's pool by weighted damage, healing and support, pays a pet's share to
  its owner, pays a grouped member in the room a minimum share, and always sums to exactly the pool, so a solo kill pays
  as before.
- The template world's `//lib:group.star` adds the `group`, `invite`, `accept`, `leave`, `kick`, `leader`, `disband` and
  `gtell` commands (alias `gt`), and a `Group.Chat` GMCP event. The arcane school gains the ally spells `soothe` and
  `ward`.
- Tutorial 10 explains shared experience, the `xp_share` contract, tuning the constants and clearing contributions on
  `on_disengage`.

```python
# before: a player's grouped pet was not an ally
# after: allies_near(player, "near") includes the player's group members and their pets
```

### Percent chances and noticing

- `wander(chance)` is a whole percent from 0 to 100, not a 0..1 float; a float is a load error with a hint.

```python
# before
wander(radius=4, chance=0.1)

# after
wander(radius=4, chance=10)
```

- `loot(chance)` is a whole percent from 0 to 100, not a 0..1 float; a float is a load error with a hint.

```python
# before
mob("rat", loot=[loot(":tail", chance=0.25)])

# after
mob("rat", loot=[loot(":tail", chance=25)])
```

- `proc(chance)` and `outcome(weight)` are whole percents from 0 to 100, not 0..1 floats; a float is a load error with a
  hint. An outcome table's weights are percents whose total is at most 100, and the rest is "nothing happens".

```python
# before
item("ember_dagger", procs=[proc(on="hit", chance=0.25, effect=damage(...))])
item("wand", procs=[proc(on="hit", outcomes=[outcome(0.4, effect=damage(...))])])

# after
item("ember_dagger", procs=[proc(on="hit", chance=25, effect=damage(...))])
item("wand", procs=[proc(on="hit", outcomes=[outcome(40, effect=damage(...))])])
```

- The harness's `w.force_rng(fractions=...)` is removed; force ints. Every chance now shares one int queue, so a forced
  int lands on the next roll of any kind (a proc's `chance`, a loot or wander roll, `rng.chance`, and so on).
- `aggressive` gains `chance`, `via` and `unless_level_below`: it looks for a target with probability `chance` percent
  per think, notices a candidate only through the channels in `via` (a built-in or declared channel), and spares actors
  below `unless_level_below`. The new `notice` event is asked through `rules.can_perceive`; `aggressive` now asks
  `can_perceive` as the mob, so a mob with `senses = {"sight": 0}` no longer attacks unless `via` names a channel it
  senses.

```python
# before
aggressive(toward="players", unless_level_above=5)

# after
aggressive(toward="players", unless_level_above=5, unless_level_below=1, chance=50, via=["sight", "sound"])
```

- The template world's `can_perceive` hides actors that carry the `hidden` flag from a `notice` on `sight`.

### Room enchantments

- Rooms can carry timed affects (tags, modifiers, hooks).
- Room affects expire while their room is dormant, and are removed first on wake; the dormant regen catch-up accounts
  for room affects piece by piece.
- `apply_affect` durations are 64-bit; pulse arithmetic saturates.
- `SEMI_PERMANENT`: a predeclared constant for a duration that effectively never ends, accepted wherever a timed affect
  takes a duration, and never scaled by power. An affect that expires at that value reports `expires_at` and `remaining`
  as `None`; GMCP `Char.Affects` sends its `remaining` as null and `@stat` shows it as permanent.
- `ctx.apply_affect` and `ctx.remove_affect` take a room as their target, applying a room affect (§3.3). A room's
  `add_tags` and `remove_tags` grant and withdraw tags for as long as the affect is live; both are an error on any other
  target. Actor-only fields (`stat_deltas`, `sense_deltas`, `max_vital_deltas`, `resist_deltas`, `procs`, `applies`) are
  an error on a room, and a room's affect needs a duration.
- `room.affects` lists a room's live affects, by id. `room.tags` is the effective tags, so it includes a room affect's
  added tags and hides its removed ones.
- The `room` skill target: `skill(target="room")` enchants the caster's room and needs no keyword. Its `effect` may use
  `apply_affect`, `dispel`, `sequence` and plugin primitives; `apply_affect` attaches a room affect and `dispel` removes
  the room's matching affects (sending each one's `expire_msg`). The cast's power scales the room affect's duration,
  which it remembers. A `room` skill ignores `range`: `resolve` runs once with `ctx.target` `None`. If the caster is no
  longer in the room where the cast began when the warmup ends, the cast fails with the new `cast.moved` message. A
  `room` skill can't be a proc's skill (a diagnostic, as for an `item` skill), and `apply_affect` in a proc's effect
  can't take `add_tags` or `remove_tags`. `item(use_target="room")` is a load error.
- Plugin effect primitives run in `room` skills. `PluginEffectCall` gains `targetRoom`: the caster's room on a `room`
  skill, where `target` and `targetItem` are null, and null otherwise.

### Encumbrance hook

- `global_hook("on_encumbrance_change", fn)` for `lib` and `rules` packages: fires once for each change of an actor's
  encumbrance level, with `ctx.old_encumbrance_level` and `ctx.new_encumbrance_level`.
- The template world adds a flavor line at heavy encumbrance levels through the new hook.

### Acquiring skills

- `ctx.set_skill`, `ctx.remove_skill` and `ctx.skill_info`, and the harness's `w.set_skill` and `w.remove_skill`.
  Setting a skill runs the `learn` restrictions, also when it raises a skill the actor knows.
- The `skills` command and the `Char.Skills` GMCP package.
- `global_hook("on_level_change", fn)` for `lib` and `rules` packages: fires for every actor's level step.
- `//lib:trainer.star`: trainers, practice sessions earned per level, `practice`, and the `practice_offer` hook. The
  template world has a `//skills/arcane` package with `spark` and `mend`.
- The tutorial zone's mentor teaches `spark` and `mend`; tutorial 09 covers trainers.

### Progressive encumbrance

- New kernel rules export `encumbrance_level(ctx)`, required in every ruleset: the actor's encumbrance level, an int of
  at least 0 (0 unencumbered); a negative result is a bad return value. It sees `ctx.limit` (the actor's `carry_limit`
  asked without an item) and `ctx.carried` (carried and worn weight, containers' contents included).
- New `rules_ctx` fields `limit` and `carried` (set in `encumbrance_level` and `encumbrance`, `None` elsewhere;
  `carry_limit`'s own ctx is unchanged) and `encumbrance_level` (set in `encumbrance`, `None` elsewhere).
- The actor view gains `encumbrance_level`: the level `rules.encumbrance_level` last gave; 0 while that rule runs.
- `rules.encumbrance` now runs only while the level is above 0.
- New engine messages `encumbrance.up` (a higher nonzero level) and `encumbrance.down` (a lower nonzero level), each
  with `{level}` and `{was}`; `encumbrance.on` and `encumbrance.off` cover the transitions to and from 0.
- The template and robot worlds now define `encumbrance_level`, and existing rulesets must add it. The robot world's
  one-level formula keeps the old over-the-limit behavior, and the template grades the share of the limit, charging each
  level in dex, move and recovery pulses. A ruleset that wants the old behavior adds the one-level formula:

```python
# before: rulesets defined no level; only the stat penalty
def encumbrance(ctx):
    return stats(dex = -2)

# after: the level is a required export; this formula keeps the old behavior
def encumbrance_level(ctx):
    return 1 if ctx.carried > ctx.limit else 0
```

### Skill proficiency

- Tutorial 03 covers proficiency: `skill_check`, `skill_power`, `item_power`, `ctx.affect.power`, `ctx.power` and
  `ctx.summon`.
- Item `use_effect`s and item procs run at `item_power`; procs of an item's instance affect at the affect's power ×
  `item_power` / 100 (with `ctx.affect` set in that `item_power` call); actor-affect procs at the affect's power.
- Casts roll `skill_check` and run at `skill_power`: spells that fail fizzle early with `cast.failed.self` and
  `cast.failed.room`, paying cost and lag without triggering effects, resolvers, combat, or `on_cast`. Successful casts
  thread `power` to effects and expose `ctx.power` in custom `resolve`.
- New modifier points `skill_check` and `skill_power` (`skills`) and `item_power` (kernel). Plugin traits can modify
  them (`PluginModifierPoint`), and `PluginModifierCall` gains `item` and `event`.
- New rules exports `skill_check` and `skill_power` (`skills`) and `item_power` (kernel). Every ruleset must define
  them.
- Modifiers contributed by an affect see it as `ctx.affect` (`ctx.affect.power`).
- New hook verb `ctx.summon(caster, label, count=1, duration=None)`.
- The affect view exposes `power`, the percent strength the affect was applied at (100 when unscaled).

### Mob planning

- Added `ctx.plan(facts, actions, goal, max_expansions=1024)`: native, deterministic goal-oriented planning over
  equality facts. It returns the cheapest list of action names, `[]` when the goal holds, or `None` when unreachable or
  out of bounds, and spends no script steps.
- The template has `//lib:ai.star`: behavior trees (`selector`, `sequence`, `condition`, `action`, `inverter`, `steps`)
  re-evaluated from the root each `think`, a per-mob blackboard, and an `ai.goap` leaf that caches `ctx.plan` results.
- The tutorial zone's squirrel uses `//lib:ai.star` to fetch pinecones from the tar pit.
- Added `w.trace_lines()` to the test harness: the lines `w.trace()` has kept, so a test can check which verbs a hook
  called (for example, that a mob's blackboard isn't saved when unchanged).
- Tutorial 05 covers behavior trees and planning, and no longer says `ctx.target` is the opponent inside `think` (use
  `ctx.self.fighting()`).

### Spells that miss

- Script API entries can be owned by several features, either any of them or all of them. `api.json` writes `features`
  and `features_mode` for such entries instead of `feature`.
- `to_hit` (the export) and the `to_hit` and `defend_hit` modifier points now belong to `combat` or `skills`. A world
  with `skills` and without `combat` must define `to_hit`, and the dry run calls it with a synthetic skill whenever
  `skills` is on.
- Enemy spells can miss. An `enemy` skill rolls `to_hit` (with `ctx.skill` set and no weapon or slot), the caster's
  `to_hit` chain and the victim's `defend_hit` chain, once per victim. A victim it doesn't land on gets neither the
  effect nor `resolve`, but the cost, lag and engagement still happen. A chance of 100 or more lands without a roll.
  `@trace rules` ends the line with `LAND`, `RESIST` or `auto LAND`.
- Added the messages `cast.resisted.self`, `cast.resisted.target` and `cast.resisted.room`.
- `PluginModifierCall` has `skill`, the skill being cast when the rules call has one, so a trait modifier can tell a
  spell from a swing.
- The template's `to_hit` has a spell branch (`_spell_hit`: `int` against `wis`, and `area` spells always land), and the
  robot world's routines are resisted by `grit`.

### Combat procs

- Auto-attack swings fire procs: the attacker's `hit` procs after a landed hit, then the defender's `struck` procs; the
  defender's `avoid` procs on a miss. New engine messages `combat.proc.self` and `combat.proc.room`.
- Added `proc(...)` and `outcome(...)`, and `procs` on `item`, `item_affect` and `apply_affect` (the primitive and the
  verb): chance-based skills or effects that fire during auto-attack swings, with weighted one-of tables.
- The tutorial zone has an `ember_dagger` (`hit`), a `bramble_vest` (`struck`) and a `cracked_wand` (an outcome table).
- Added `w.force_rng(ints = [], fractions = [])` to the test harness: it queues the results of later rolls, so tests can
  force a proc or a hit without searching for a seed.

### Test fixture files

- A package may hold `*_testdata.star` files: test-only fixtures for content that only tests need. They are ordinary
  content files (same builtins, load order and declaration rules, one name space with the package's other files), loaded
  only by `gups test` and `gups scenario`. The server, `gups lint`, `gups fuzz` and every other command leave them out,
  so normal content that names a fixture fails with an unresolved label, whose error names the fixture file.
- Only test files and other fixture files may `load()` a `*_testdata.star` file.

### Typed bonus damage

- Added the `damages(**n)` typed value, keyed by damage type, and `+` between typed values of the same kind (`vitals`,
  `stats`, `resists`, `damages`), which adds them key by key.
- Added the kernel modifier point `bonus_damage` (`MODIFIER.bonus_damage`): the attacker's chain returns `damages(...)`
  parts that are each mitigated with their own type and applied with the hit as one damage event, for auto-attacks and
  the `damage(...)` effect.
- `on_damage` has `ctx.parts`, the event's parts as `struct(type, losses)`.
- Plugin traits can declare `BONUS_DAMAGE` modifiers, which take and return a map from damage type to amount.
- The template's `mitigate` applies armor only to physical damage (`bludgeon`, `slash`, `pierce`); fire is reduced only
  by resists.
- The tutorial zone has a `flaming_sword` (a `bonus_damage` modifier on its own swings), an `ember_amulet` (a
  `bonus_damage` modifier on everything its wearer deals) and `oily_rags` (armor, and double fire through a
  `receive_damage` modifier).

### Harness mob actions

- Added `w.act(mob, command_line)` to the test harness: it queues a command for a mob, as `ctx.act` does, so a test can
  have a mob wear an item.

### Scenario traces

- `gups scenario` prints the `w.trace()` record after the transcript even when the scenario passes, so the rules chains
  can be read without `@trace`.

### Defender-side modifiers

- A negative entry in `mitigate`'s result is now a gain to that vital, capped at its maximum, instead of being ignored;
  `on_damage` and `interrupt_check` count only the losses.
- Added the `defend_hit` modifier point (`MODIFIER.defend_hit`, `combat` feature): it runs on the defender's chain
  (zone, regions, room, then the defender's traits, affects and every active worn item, weapons included) after the
  attacker's `to_hit` chain, with the same `ctx`; `@trace rules` shows it after a `defender:defend_hit` step.
- Added the kernel modifier points `receive_damage` (int, the defender's chain after the attacker's `damage` chain and
  before `mitigate`) and `mitigate` (`vitals(...)`, the defender's chain after the `mitigate` export), for auto-attacks
  and the `damage(...)` effect; the `damage(...)` effect's trace now ends with the `mitigate` step.
- Plugin traits can declare `DEFEND_HIT`, `RECEIVE_DAMAGE` and `MITIGATE` modifiers; on these the `PluginModifierCall`'s
  `actor` is the trait's bearer, the defender. The heritage sample's elf trait has one, a `DEFEND_HIT` modifier scaled
  by its bearer's dex.
- The tutorial zone has a `shadow_cloak` (`defend_hit` and `receive_damage`) and a `siphon_amulet` (`mitigate` moving a
  tenth of hp loss into a mana gain).

### The `damage` modifier point belongs to the kernel

- The `damage` modifier point no longer needs the `combat` feature: zones, regions, rooms, items, affects and plugin
  traits may declare it in any world, and it applies to the `damage(...)` effect primitive (`cast`, `use`). The
  `damage(ctx)` rules export still belongs to `combat`.
- The documented `ctx` fields of `to_hit`, `damage` (export and modifier point) and `mitigate` now list `damage_type`,
  and those of `to_hit` and `damage` list `slot`, which they already had.

### Shopkeepers in combat

- `ctx.has_feature(name)` reports whether the world has a feature module on; an unknown name is an error.
- `//lib:shop.star`: a keeper that is fighting refuses every customer with "<keeper> is busy fighting."; restocking goes
  on, and worlds without combat are unaffected.

### Shops

- Added `//lib:shop.star` to the template world: `shop.define(id, stock, ...)` describes a shop — stock lines with
  `count`/`every`/`amount` (`count` `None` is unlimited, `0` is sell-only), a `till`, `buy_markup` (120 by default) and
  `sell_rate` (50) — and the keeper's `on_spawn` calls `shop.open(ctx, keeper, shop)`, which fills and locks a chest in
  the keeper's room (or gives the keeper a carried pack) and gives it the key. Stock and till are real items in the
  container, restocked lazily over time while a keeper is present: the catch-up runs when the container opens or wakes
  and before every `buy`, `sell` and `value`; `list` projects the same arithmetic without applying it.
- The library registers the global commands `list` (fast lane), `buy`, `sell` and `value` (tick lane) and the GMCP
  package `Shop.Stock`, which `list` sends with the keeper's name, the currency label, and one entry per line with the
  item's name, label, price and count (`null` for unlimited stock). Prices start from `item(value = N)`: the customer
  pays `value * buy_markup / 100`, rounded up, at least 1, and the shop pays `value * sell_rate / 100`. The keeper's
  custom hook `shop_price` (M23's `hooks = {...}`) may return an int to change any price or a string to refuse any
  customer, and plugin handlers registered for the name join the chain.
- A shop is closed ("The shop is closed.") when its keeper's condition isn't `ok` or its container is missing, and a
  stunned keeper answers "<Keeper> is in no state to trade." while still restocking. A fighting keeper still trades.
- Added the library's items: `//lib:gold_coin` (a stackable currency, `value = 1`), `//lib:shop_chest` and
  `//lib:shop_pack` (stock containers whose `on_open`/`on_wake` run the library's catch-up) and `//lib:shop_chest_key`.
- The tutorial zone has a shopkeeper in a new shop room off the armory: it sells copper rings and wooden shields from a
  locked chest and healing draughts without limit, and its `shop_price` hook discounts buying and pays more for selling
  to customers with charisma above 10 and refuses items tagged `"stolen"`.
- Added `ctx.merge_stack(item)`: merges the item into a stack of the same prototype in the same place with identical
  attributes, decay deadline and charges (as `get` already did), and returns the view of the stack that now holds it
  (the item itself if nothing matched). Equipped items and items that can't stack are returned unchanged.

### Item value and custom hooks

- Added `item(value = N)`, an item's base price, an int of at least 0 (default 0). The engine gives it no other meaning
  and shows it nowhere in `look`, `inventory` or `examine`; libraries such as shops read it. It is a prototype field, so
  a reload changes it for every instance and nothing is saved per instance.
- `item.value` on item views and `PluginItem.value` on plugin item views expose the per-item base price.
  `//engine:corpse` has 0.
- The template world declares `cha` (Charisma): `stat("cha", label = "Charisma")` in `MODULE.gups`, with
  `starting_stats` giving players 10 and mobs their declared value or 10, like the other ability stats. Existing
  template-based worlds need `--wipe` to pick it up.
- Added `hooks = {"name": fn}` to `room`, `mob` and `item`: custom hooks of the builder's own naming, never fired by the
  engine. Names match `[a-z][a-z0-9_]*` and may not be catalog hook names (the error suggests passing the catalog hook
  as its keyword instead); each value is a top-level function. Scripts run them with `ctx.call_hook`.
- Added `ctx.call_hook(entity, name, **args)`: runs `entity`'s custom hook `name` as `fn(ctx, **args)` with `ctx.self`
  the entity, in the hook's own package, then every plugin handler for `name`, and returns the result as a fresh copy.
  Arguments may hold None, ints, strings, bools, lists, dicts and actor, item and room views (which the hook receives as
  its own views, or `None` if the entity left the world); results may not hold views. It returns `None` when the entity
  is gone, nothing handles the name, the package is quarantined or the hook fails, and more than 8 nested `call_hook`s
  is a script error.
- `PluginRegistry.addHook(name, doc, handle)` registers a plugin handler for a custom hook name: after the entity's own
  hook, the plugin's handlers run in plugin-id order, each with a read-only `PluginGmcpWorld`, the entity, the arguments
  and the result so far as plain values, returning the new result as plain values (a `PluginHookCall`). A handler that
  throws records a `plugin` diagnostic and leaves the result unchanged. Names follow the custom hook rules; one plugin
  registers a name once, and several plugins may share it. A handler's `Int`s become `Long`s and its iterables lists;
  floats are refused.

### Starlark GMCP events

- Added `gmcp_package(name, doc)` for `lib` and `rules` packages, declaring a GMCP package the package sends with
  `ctx.gmcp`. A name has two or more dot-separated parts, may not use the roots `Core`, `Char`, `Room`, `Comm` or
  `Gups`, and may not overlap an engine package, a plugin's package, or another declared name.
- Added `ctx.gmcp(player, package, data)`: sends the GMCP event `package` with `data` to `player`, after its pending
  text and before the prompt, returning whether it was sent. `package` must be, or nest in, a GMCP package the calling
  package declared. It returns `False` for a mob, a link-dead player, or a client that didn't ask for the package.
  `data` may hold `None`, ints, strings, bools, lists and dicts with string keys, nested at most 8 deep; its compact
  JSON form is at most 16 KB (`alloc_limit` beyond).
- Attribute values, timer arguments and each key of a `migrate` result may nest lists and dicts at most 8 deep and be at
  most 64 KB as compact JSON; a deeper or larger value raises `alloc_limit`. A value that shares one list many times or
  contains itself now fails at these caps instead of exhausting memory or the stack.
- The test harness accepts `w.spawn_player(..., gmcp = [...])`, the GMCP packages the test client supports (none by
  default), and `p.gmcp(package = None)`, which returns the GMCP messages received since the last call as `{"package",
  "data"}` dicts, optionally only those in `package`'s tree, and drains them.
- `gups docs --world` and the server's built-in docs list the GMCP packages the world's `lib` and `rules` packages
  declare, with each package's doc and declaring package.

### Plugin GMCP input and events

- `PluginRegistry.addGmcpEvent(pkg, doc)` declares a GMCP event package, sent with `PluginWorld.gmcp`.
- `PluginWorld.gmcp(actor, pkg, data)` sends one GMCP message with a plain payload to the actor's session, queued with
  the session's text and never cached. It returns false, sending nothing, for a mob, a link-dead player or a client that
  doesn't support the package.
- `PluginWorld` extends a new `PluginGmcpWorld`, which holds the reads (except `rng`), `send`, `gmcp`, `getAttr`,
  `diagnose` and `log`; it is what GMCP input handlers receive.
- GMCP package name checks are tightened: names are compared ignoring case, the reserved `Core` root is refused, and a
  plugin's state and event packages may not equal, contain or nest in an engine package, another plugin's package, or
  each other.
- `PluginRegistry.onGmcp(pkg, doc, handle)` receives client GMCP messages for exactly `pkg` (ignoring case). The handler
  gets the sending player, the data as plain values, and a `PluginGmcpWorld`; it acts by calling `call.command(line)`
  once, which runs the line as if the player typed it, through the normal lanes, lag, restrictions and repro recording.
  An input package may not use the `Core` root or overlap an engine package or another plugin's package, but may share
  the plugin's own state and event tree.
- `plugins.html` lists each plugin GMCP package with its kind (`state`, `event` or `input`), anchored
  `plugin-<id>.<pkg>.<kind>`.

### Consumables and charges

- `item(use_effect=..., use_target=..., use_lag=...)` allows items to apply declarative effect primitives on use with
  the user as caster, resolve targets (`self`, `ally`, `enemy`, `item` or `none`), and set recovery lag in pulses.
- `item(charges=N, empty=...)` declares the uses an item has before being spent (`charges`, at least 1, or `None` for
  unlimited) and what happens when the last is spent (`empty`: `"destroy"`, `"keep"`, or the name of a variant). A
  stackable item may only have `charges = 1` and cannot `keep`.
- `item.charges` and `item.max_charges` on item views expose uses left and the prototype's charges, or `None` when
  unlimited.
- `ctx.set_charges(item, charges)` sets an item's remaining uses, from 0 to its prototype's charges; unlimited and
  stackable items are refused, and 0 does not run `empty`.
- `PluginItem.charges` and `PluginItem.maxCharges` expose uses left and the prototype's charges, or `null` when
  unlimited. `Facade.setCharges(item, charges)` sets an item's remaining uses, from 0 to its prototype's charges;
  unlimited and stackable items are refused, and 0 does not run `empty`.
- An item with `charges` and none left is refused ("There are no charges left in {item}."). After `on_use`, if the item
  is still in the world, the use spends one charge, or one item of a stack; the last charge runs the item's `empty`
  action: destroy it, keep it at 0 charges, or `transform` it into the named variant. An item without `charges` is never
  used up; `on_use` destroys it if it should be.
- Added kernel messages `use.self_only` ("You can only use {item} on yourself."), `use.at_whom` ("Use {item} on whom?"),
  `use.on_what` ("Use {item} on what?"), `use.done` ("You use {item}."), and `use.done.room` ("{actor} uses {item}.").
- `use <item> <target item>` now refuses a target item on the ground that is out of `get_lag` reach, even for items
  without a `use_effect`.
- The effect primitives `damage`, `heal`, `apply_affect`, `dispel`, `stun`, `summon` and `sequence` no longer need the
  `skills` feature; `knockback` and `pull` still need `track`.

### Lag modifier points: advance_lag, retreat_lag, retreat_fail_lag and flee_fail_lag

- Added four modifier points (`advance_lag`, `retreat_lag`, `retreat_fail_lag`, and `flee_fail_lag`, accessible via
  `MODIFIER.advance_lag`, etc.) allowing zone, region, room, affect, and item modifiers to adjust recovery lag.
- Lag modifier points run on the acting actor's modifier chain with the corresponding rules export's context
  (`ctx.target` and `ctx.band` for track lag points; `ctx.target == None` and `ctx.band == None` for `flee_fail_lag`).
- Intermediate steps in a lag modifier chain may go below 0, but the chain's final value is clamped to 0.
- Feature gating follows the exports: `advance_lag`, `retreat_lag`, and `retreat_fail_lag` require the `track` feature,
  while `flee_fail_lag` requires only `combat`.
- `get_lag` is not a modifier point and sets pickup lag directly.

### Equipment slots, slot families, item attacks, and vital and resist deltas

- Added `slot_families()`, `actor_slots(ctx)` and `slot_cost(ctx)` rules exports, `item(slot_cost=)`, `max_vital_deltas`
  and `resist_deltas` on items and affects, `ctx.damage_type` and `ctx.slot`, item view `inactive`, actor view `slots`,
  spell damage through the `damage` chain, and attacks from every item with `dice`.

### Effects on items

- Added the `item_affect(name, applies = "worn", stat_deltas = stats(), sense_deltas = {}, modifiers = {}, flags = [])`
  declaration builtin for declaring prototype affects an item carries.
- Added `affects` to `item(...)`: affects every instance carries, built with `item_affect(...)`.
- Added `variants` to `item(...)`: free-form variant names mapped to related item prototype labels, for `transform`.
- A key that gains instance affects or tags in play leaves the keyring and shows in `inventory` until they are gone.
- Added `applies = None` to `apply_affect(...)`: allowed only on `item` skills, setting whether the affect reaches the
  holder while worn or carried (`"worn"` or `"carried"`; `None` means `"worn"`).
- Allowed `duration = None` in `apply_affect(...)` on `item` skills to attach a permanent item affect.
- An `item` skill's `effect` acts on the target item; it may use `apply_affect`, plugin primitives, and `sequence`
  combining them. Any other primitive fails the load.
- `ctx.apply_affect(target, ...)` and `ctx.remove_affect(target, name)` accept an item as the target; on an item,
  `apply_affect` takes `applies = "worn" | "carried"` (`None`, the default, means `"worn"`) and allows `duration = None`
  for permanent affects.
- Added `ctx.add_tag(item, tag)` and `ctx.remove_tag(item, tag)`, returning whether the effective tags changed.
- Added `ctx.transform(item, label)` to swap an item's prototype in place, keeping its id, location, attributes,
  affects, tag changes, name and timers. A stack transformed into a non-stackable prototype keeps a count of 1; a
  container transformed into a non-container drops its contents on the ground; a locked container stays locked only if
  the new prototype has a `key`, else it ends up closed; an equipped item that no longer fits its slot moves to the
  inventory. It fails when the contents wouldn't fit the new container.
- Added `ctx.open_container(item)`, `ctx.close_container(item)`, `ctx.lock_container(item)` and
  `ctx.unlock_container(item)`. They mirror the door verbs and print nothing. `lock_container` fails on a container
  whose prototype has no `key`.
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
