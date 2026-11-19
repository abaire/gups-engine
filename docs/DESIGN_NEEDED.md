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

### Item placements and one spawn-rule model for mobs and items (designed, awaiting a milestone plan)

**Spec:** §3.1 (`WORLD_TICK_PULSES`), §3.3 (entities, item locations), §3.4 (reload: spawn rules carry their live mobs
over by label), §3.6 (`zone_states`, the saved mob spawn binding), §4.1 (`reset_world_ticks`), §4.2 (`room(spawns=)`,
`spawn()`), §4.4 (`on_spawn`, `on_reset`, the hook catalog), §4.5 (`ctx.spawn`, `ctx.find`, `despawn`, `set_owner`),
§4.12 (harness `w`), §4.15 (plugin facade), §5.1 (world tick: resets and respawns), §5.4 (Respawns), §5.9 (dormancy and
the wake order), §5.11 (checkpoints).

**Today:** `room(spawns=[spawn(label, count, respawn_pulses, at_index)])` places mobs only (`SpawnRule` in
[Protos.kt](../core/src/main/kotlin/gups/core/model/Protos.kt), parsed in
[Declarations.kt](../script/src/main/kotlin/gups/script/Declarations.kt)). A rule refills to `count` at every zone reset
and again `respawn_pulses` after its first live mob leaves the world (`SpawnState` and `fillSpawn` in
[World.kt](../core/src/main/kotlin/gups/core/world/World.kt), `resetZone` and `respawnPulse` there too; release on owner
change in [Owners.kt](../core/src/main/kotlin/gups/core/world/Owners.kt); reload carry-over in
[Reconcile.kt](../core/src/main/kotlin/gups/core/world/Reconcile.kt); restore in
[Restore.kt](../core/src/main/kotlin/gups/core/world/Restore.kt)). Items are placed only by script, in `on_load` and
`on_reset` with a "does the room already have one?" check (the tutorial's pinecone in
[rooms.star](../template-world/zones/tutorial/rooms.star)). That cannot express:

- a one-time item: a key placed once and never again, even after a player picks it up, because the room no longer has
  one at the next reset;
- an item that respawns on its own timer rather than the zone's reset;
- a placement in one of several rooms, picked at random;
- "only while the copy this placement made is gone": `ctx.find` sees only a room or a zone, and only items whose
  location chain ends there, so a key in a player's pack in another zone is invisible.

Timers are fixed numbers everywhere (`respawn_pulses`, `reset_world_ticks`), so players can time respawns and resets.
The engine already keeps every item in memory, including those carried by logged-out players (all `world_entities` rows
load at start), so tracking copies world-wide is cheap.

**Example:** a vault key placed once in the antechamber; a rare sword that appears in one of five rooms across two
zones, 1–3 hours after the last one was destroyed; a goblin camp of up to 6 that refills 1–3 goblins at a time; a
wandering merchant who turns up in one of three villages; a stack of 15–25 arrows in a chest at each zone reset.

**Agreed design (settled with the maintainer, one decision at a time; needs a milestone plan and exact `SPEC.md`
wording, which the maintainer approves before the spec changes):**

1. **One rule type for mobs and items, one implementation.** The prototype the label names decides whether a rule places
   mobs or items. Mob spawn rules move onto the new model; there is no separate item mechanism.
2. **Two ways to declare it.** `room(spawns=[spawn(label, ...)])` for one room, and a top-level `spawner(id, label,
   rooms=..., pick=None, ...)` beside `room()`, `mob()` and `item()` for a pool of rooms. A room rule is a pool of one.
3. **Counting: a rule counts only its own copies.** Copies from loot, quest rewards, `ctx.spawn` or admins never count
   toward or block a rule. Two rules for the same prototype don't affect each other. A limit on every copy of a
   prototype from any source is a separate design (the next entry).
4. **When a copy stops counting (`until`, items only).** `until = "gone"` (default): the copy counts wherever it ends up
   (a pack, a chest in another zone, a logged-out player) until it leaves the world: destroyed, decayed, used up, merged
   into another stack, or its prototype deleted. `until = "taken"`: it counts until its location chain no longer ends in
   the room it was placed in (as `find(..., scope="room")` counts, so an item in a container or carried by a mob in that
   room still counts). Mobs have no `until`: a mob counts until it leaves the world or leaves its rule as today
   (`set_owner`, `despawn`).
5. **Respawn modes (`respawn`).** `"never"`: the first fill and nothing after. That it has run is saved, so restarts
   don't place again; `--fresh` does. `"reset"`: refilled at the declaring package's zone reset, and only then. `pulses`
   or `(min, max)`: refilled only by the rule's own timer. **Zone resets no longer refill timed rules**, including mob
   rules: this is a deliberate change from today. Modes don't combine. Default `1200`, as `respawn_pulses` is today.
6. **Cap and batches.** `max` (renamed from `count`, no alias) caps tracked copies, held copies included. `batch` (int
   or `(min, max)`, default: up to `max`) is how many one refill adds, never above `max`. The world-start fill (and a
   new rule's first fill at a reload) goes straight to `max`; every later refill (a timer, or a reset in `"reset"` mode)
   adds one batch. The timer starts when the rule first drops below `max`; another copy leaving doesn't restart a
   running timer. If the rule is still below `max` after a timed batch, the next timer is rolled at once.
7. **Ranges.** Wherever a range is allowed (`respawn`, `batch`, `stack`, `reset_world_ticks`), it is an inclusive `(min,
   max)` tuple rolled with even odds from the seeded world RNG (as `rng.int(lo, hi)`). A plain int is a fixed value. The
   loader checks `min <= max` and each setting's own bounds. A rolled deadline is saved, so a restart doesn't roll
   again. Timed deadlines still land at the first world tick at or after them.
8. **`reset_world_ticks` takes a range.** Each reset rolls the next interval from the pulse it ran on and saves it in
   `zone_states`.
9. **Pools across packages.** `rooms` may name rooms in any package, checked at load like exit labels. `"reset"` mode
   follows the declaring package's resets. A pool room that is missing after a reload, or whose package is quarantined,
   is skipped when picking, with one diagnostic; a pool with no usable room waits.
10. **Picking.** `rooms = [...]` gives even odds, `{label: weight}` weighted odds, and `pick = fn` a script choice, the
    same forms as random exits (M57). Each copy in a batch is picked on its own, and the room it is placed in is its
    home (for `until = "taken"`, mob leashes and going home). Picks are purely random: a room that already holds a copy
    is not avoided; weights or `pick` control clumping.
11. **Held copies.** A copy picked into a dormant room is held: it counts toward `max` at once and is placed (with its
    hooks) when the room wakes, with the room's other catch-up. This is today's single-room behavior extended to pools,
    so dormant rooms still run no hooks. A held copy is not an entity yet: `find` and world counts don't see it.
12. **Containers.** `container = ":chest"` places the copy into the first container of that prototype in the picked
    room, lowest id, whether open, closed or locked (an engine placement, not a player's `put`). If the room has none,
    or `fits` refuses, the copy is held and retried at each world tick, still counting toward `max`; it is never dropped
    on the floor. A container gone for good means a held copy forever, so a container that must exist needs its own
    rule. `at_index` (floor or track position) is ignored with `container`.
13. **Stacks.** A copy is one item entity. `stack` (int or `(min, max)`, default 1) sets a stackable copy's size; `max`
    and `batch` count entities. A partial take splits off a new, untracked item (the existing `split` custody event) and
    the remainder keeps counting, so the rule refills only once the last of the stack is gone or taken.
14. **Identity across reloads and restarts.** A rule is identified by its label when it has one (a `spawner` id, or a
    room rule's `id`), otherwise by room plus prototype label plus its order among same-label rules in that room, as
    today. Under the same identity a reload keeps tracked copies, held copies, the pending deadline and the `"never"`
    ran-flag, whatever settings changed: lowering `max` removes nothing (no refill until below the cap); a changed timer
    applies from the next deadline; a copy whose room left the pool stays and keeps counting. A removed rule releases
    its copies (they stay, untracked) and drops its held copies. A new rule does its first fill at the reload. Renaming
    a label or id makes a new rule. Items get the saved "placed by" binding that mobs already have. `--fresh` starts
    every rule from nothing.
15. **`on_place` hook.** `spawn()` and `spawner()` take `on_place = fn`, fired once per copy after the copy is placed
    and its own `on_spawn` hooks have run, with `ctx.item` or `ctx.actor` the copy, `ctx.room` its room and
    `ctx.spawner` the rule handle. A held copy fires it when finally placed. It joins the hook catalog and the load-time
    dry run.
16. **`spawned_by` view field** on items and mobs: the rule handle, or `None`. Same field on the plugin views.
17. **Script verbs.** `ctx.spawner(rule)` returns `struct(live, held, max, next_at, ran)`. `ctx.refill(rule,
    count=None)` adds one batch now (or `count` copies), capped by `max`, through the normal picking, holding and
    `on_place` path, in every mode, so a quest can bring back a destroyed `"never"` key. It doesn't cancel or reset a
    pending timer. Both get `PluginWorld` counterparts (facade parity, `CtxParityTest`).
18. **Naming rules.** A `spawner` is `//pkg:id` (relative `":id"` in scripts). `spawn(..., id="x")` gives a room rule a
    label in the same namespace; duplicates across both are a load error. A room rule without `id` is reachable only
    through its handle (`spawned_by`, `on_place`'s `ctx.spawner`). `ctx.spawner` and `ctx.refill` take a label or a
    handle.
19. **Signatures.**

    ```python
    spawn(label, id=None, max=1, batch=None, respawn=1200, until=None,
          stack=None, container=None, at_index=0, on_place=None)

    spawner(id, label, rooms=[...], pick=None, max=1, batch=None, respawn=1200,
            until=None, stack=None, container=None, at_index=0, on_place=None)
    ```

    `in` is reserved in Starlark, hence `container`. On a `spawner`, `at_index` is clamped to each pool room's track.
    `until=None` means `"gone"` for an item rule. Passing `until` or `stack` on a mob rule is a load error, and so is
    `stack` on a non-stackable item.

**Left for the milestone plan:**

- The exact `SPEC.md` wording for §4.1, §4.2, §4.4, §4.5, §5.4 and §5.9, and the order a reset runs in now that timed
  rules skip it (`"reset"` rules, then `on_reset`, as today?).
- **Name clash to settle:** item 15 makes `ctx.spawner` the rule-handle field in `on_place`, and item 17 makes
  `ctx.spawner(rule)` the read verb; one `ctx` cannot have both. Options: rename the field (for example `ctx.rule`) or
  the verb (for example `ctx.spawner_info` or `ctx.rule_state`).
- The rule handle's Starlark type and what it prints; the shape of the read verb's `next_at` for held copies.
- Where saved rule state lives (`zone_states` per package for deadlines, the `"never"` flag and held copies; the item
  and mob binding on their own `world_entities` rows) and its payload format.
- Whether a held copy waiting for a container is retried in a dormant room, or only once the room wakes.
- The harness API (§4.12): whether `w` gets `spawner`/`refill` for tests, and how a scenario inspects held copies.
- Admin visibility (an `@spawners` command or a `@where` extension), if any.
- In-repo callers of `count=` and `respawn_pulses=` to update: template world zones, tutorials (`05_mobs_and_behaviors`
  section 6, `03_items_and_equipment` on reset restocking), `samples/robot-world`, Kotlin tests, `DocExamples.kt`,
  `DeclarationSpecs.kt`, the API golden files and `docs/api/api-1.json`. The tutorial's `on_reset` item restocking
  becomes a `spawn(..., respawn="reset")` example. Pre-release: no `api_version` bump.

### A world-wide limit on copies of an item prototype

**Spec:** §4.2 (`item()`), §4.5 (`ctx.spawn`, `grant_item`), §5.4 (loot), §4.15 (plugin facade). **Today:** nothing
limits how many copies of a prototype exist. Spawn rules (previous entry) cap only their own copies, so a unique item
can still be duplicated by loot, quest rewards, `ctx.spawn`, `grant_item` or an admin. **Questions:** - Is the limit an
`item()` property (for example `max_in_world=1`) enforced on every path that creates an item? - What does a creation
over the limit do: fail silently, return `None`, raise a script error, or skip a loot roll? - Do held copies of spawn
rules count toward it? - Does a copy in a logged-out player's custody count (it is loaded, so it can), and what about
items in a deleted character's custody? - How does `transform` into a limited prototype behave? - Do admin spawns bypass
it?

## Perception and the world

### Global and cross-zone event subscriptions

**Spec:** §4.6 (`rules.sense_cost`), §5.7 (Perception, `on_perceive`). **Today:** event propagation is sensory and
strictly local, priced across hops through the room graph and limited to adjacent rooms. There is no global
publish/subscribe event bus or cross-zone notification system. **Questions:** - Should the engine support pub/sub
channels or global event subscriptions (e.g., world-wide weather shifts, regional faction events)? - How to ensure
cross-zone event listeners do not wake dormant reality bubbles or violate pulse execution budgets?

### Trick exits with a varying destination (Done in Milestone 57)

**Spec:** §3.3 (Exit: a fixed `to` label), §4.4 (`before_exit`), §4.5 (`teleport`), §4.7 (`wander`, `follow`, `pursue`,
leashes), §4.10 (`Room.Info` exits, the Mudlet mapper), §5.5 (walking, movement debt, `flee`), §5.7 (perception across
exits), §5.9 (`path_to`), §7.3 (reverse-exit and dead-end gates). **Today:** an exit's destination is the `to` label it
loads with, and nothing changes it at runtime. A world can fake an exit whose destination comes from a roll or a timer
(a maze passage, a shifting portal, a tide-dependent stair) only by calling `ctx.teleport` in the room's `before_exit`
and then returning `ctx.deny(...)`. Allowing the walk instead would make the move look the same direction up again in
the room the actor was just teleported to. The workaround prints the veto text after the new room's `look`, skips
`move_cost` and movement debt, makes a `flee` that way count as a failure (lag, still engaged) after the actor has
already left, teleports mobs that walk, wander or pursue through it while `path_to` still plans with the declared room,
and prices perception through the declared room. `Room.Info` reports the declared destination, so Mudlet's mapper draws
the exit to a room the player may never reach, places each room the player actually lands in at the area's origin with
no link, and stops a map walk through the exit with "Walk interrupted.". **Example:** a "shifting corridor" whose north
exit leads to one of three vaults, picked anew for each traveler, and a sea stair that goes down to the grotto at low
tide and to a drowned cave at high tide. **Questions:** - Where does the destination come from: a list of candidate
labels on `exit()` (with weights, picked by the engine's rng), a hook such as `before_exit` returning a destination
instead of a veto, a rules export asked for each crossing, or a verb that retargets an exit for a while (`set_exit_to`,
restored on reset)? A candidate list can be validated at load and lets the engine know every possible destination; a
hook or retargeting verb covers timers and world state but leaves the set open. - Is the destination picked per crossing
(each traveler may land somewhere different) or per exit for a period (everyone who goes north this hour lands in the
same place)? A per-period pick behaves like an ordinary exit while it lasts, so following, perception and pathfinding
can use it unchanged. - Does a group or a pet following its leader through the exit land with the leader, or roll its
own destination? - Does a redirected crossing count as a walk through the exit: `move_cost`, `move_pace` and debt,
`entry_index` (whose: the exit's, or one per candidate?), `before_enter` in the room actually reached, the `enter`
restriction, `on_exit` with `ctx.to_room` the real destination? What happens if the room picked refuses the actor: is
the crossing denied, or is another candidate tried? - How does `flee` treat such an exit: does it succeed and end
engagement like a walk? - What do mobs do: may `wander`, `follow` and `pursue` take a trick exit, how does `path_to`
treat it (skip it, use the current destination, or count every candidate as reachable), and how does a leash count
distance through it? - Which room does perception price through the exit (`sense_cost`, `via_exit`): the current
destination, every candidate, or none? - What does `look` show, and what does `Room.Info` send for the exit: the current
destination's `num`, `null` (as for an unloaded room, so Mudlet draws a stub), or a new marker? Sending the real
destination gives away the trick and is wrong for a per-crossing pick; `null` keeps the map honest but means a map walk
can never route through the exit. - Should the Mudlet mapper link the room the player came from to the room they arrived
in when no declared exit led there, or leave the jump undrawn? - How do the CI gates treat a trick exit: does each
candidate need an exit back unless the exit is `one_way`, and does an exit with candidates count as an exit for the
dead-end gate? - Is it a kernel feature, or part of an existing feature such as `track`?

## Mobs

### Removing a mob without killing it (Done in Milestone 54)

**Spec:** §4.3 (`summon` primitive), §4.5 (`spawn`, `destroy`, `summon`), §4.2 (spawns), §5.4 (respawns), §4.15.
**Today:** a mob leaves the world when it dies, when its `summoned` affect ends (it "fades away": a sight message, every
engagement dropped, then removed), when a reload deletes its prototype or spawn rule, or as a debug mob when its spawner
is gone. Neither `ctx` nor the plugin facade can remove one otherwise: `ctx.destroy` and `PluginWorld.destroy` take
items only. A mob from `ctx.spawn`, with no spawn rule, stays until something kills it. **Example:** an invasion plugin
spawns raiders into a town and, when the invasion ends, wants the survivors gone rather than slaughtered, with no
corpses, loot or XP. **Questions:** - Should there be a verb (`ctx.despawn(mob)` and a facade counterpart), or should
content give the mob a `summoned`-style affect and let it fade? - Does it fire a hook (`on_despawn`, or `on_death` with
a reason), and what do players in the room see? - What happens to what the mob carries and wears: destroyed with it, or
dropped? - May it remove a mob a spawn rule tracks (the zone then respawns it on schedule), or an owned pet?

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

### Plugin timers (`after` and `cancel` on the facade)

**Spec:** §3.3 (`ScriptTimer`), §4.5 (Timers), §4.15 (Periodic work), §5.1 (timer phase), §5.10, §5.11. **Today:** a
plugin schedules only through `every(pulses, doc, fn)`, which runs on a fixed period for the life of the world.
`ctx.after` saves a `ScriptTimer` whose callback is rebound on reload by `(file, name)`; a plugin's Kotlin closure can't
be saved or rebound that way, so the `ctx` semantics don't carry over. **Example:** a weather plugin that locks a door
for exactly 40 pulses after a lightning strike. Today it stores the due pulse in an attribute and checks it from an
`every` handler. **Questions:** - Is `every` plus attributes enough, so this belongs in [ICEBOX.md](ICEBOX.md)? - If
not, how is a pending plugin timer identified across a save or restart: a callback the plugin registers by name at
`register` time, with only the name and plain-value arguments saved? - Who owns the timer (an entity, the world, the
plugin), and what happens to it when its owner leaves the world or the plugin fails to load? - How does `cancel` name
it, and can a plugin cancel a script's timer or the reverse?

### Track positions on the plugin facade

**Spec:** §3.10 (`track` feature), §4.5 (Position, the `index` arguments, `act_advance`, `act_retreat`), §4.15, §5.5.
**Today:** `spawnItem`, `moveItem` and `teleport` place at index 0 of a track room, and no facade read reports where
along a track an item or actor is, or the band between two actors. The mob actions that move along a track
(`act_advance`, `act_retreat`) aren't on the facade either. **Example:** an ambush plugin that spawns archers at the far
end of a road and has them close in. **Questions:** - Do the existing verbs grow an `index` parameter (`teleport(actor,
room, index = null)`), as `ctx` does, or does the facade get separate track-aware verbs? - Which reads to add:
`PluginActor.trackIndex`, `PluginItem.trackIndex`, `bandOf(a, b)`, a room's `trackSize`? - What does each do in a world
without `track`: error as `ctx` does when an `index` is passed, or quietly treat every room as size 1?

## Networking and clients

### Mudlet automapper support (Done in Milestone 56)

**Spec:** §3.1 (room declarations), §4.10 (Network Protocol, `Room.Info`, HTTP endpoints), §5.10 (Persistence).
**Today:** Mudlet's map dock is empty on a fresh profile because Mudlet's C++ mapper visual canvas only renders rooms
created by a client Lua script (listening to `gmcp.Room.Info` or scraping text) or imported from a map file (via GMCP
`Client.Map`). GUPS's `Room.Info` payload (`{"label", "name", "exits": {dir: "open"/"closed"/"locked"/null},
"regions"}`) is designed for the bundled web client and diverges from the standard GMCP `Room.Info` schema expected by
Mudlet's `generic_mapper` and standard GMCP mapper scripts: - Mudlet's mapper graph keys every room and exit target by a
stable positive integer ID (`num`), whereas GUPS rooms are identified by string labels (`//zones/tutorial:gate`) and
ephemeral runtime `EntityId`s (`Room.id.v`), with no integer vnum in `Room.Info`. - Standard `Room.Info.exits` maps
direction strings (typically short names like `"n"`, `"s"`, `"u"`) to destination room `num` integers (`{"n": 102}`),
whereas GUPS maps full direction names (`"north"`) to door states (`"open"`, `"closed"`, `"locked"`, or `null`). -
Standard `Room.Info` includes `"area"` (string area/zone name, whereas GUPS sends `"regions"`, a sorted list of strings)
and `"environment"` (terrain string for room coloring), plus optional `"coords"` (`x`, `y`, `z`). - Even with a
Mudlet-compatible `Room.Info`, Mudlet's built-in `generic_mapper` defaults to text scraping until configured or bridged,
unless Mudlet receives a GMCP `Client.Map {"url": "..."}` pointing to a server-hosted MMP XML map or a `Client.GUI
{"version": "...", "url": "..."}` pointing to a `.mpackage` containing a GMCP mapper bridge script. **Questions:** -
Which mechanism should populate Mudlet's map: (a) a GMCP mapper script delivered automatically via `Client.GUI` (or
documented for students to install), (b) a server-generated MMP XML map served over HTTP and advertised via
`Client.Map`, or (c) adapting `Room.Info` to work with Mudlet's built-in `generic_mapper`? - How should rooms get stable
integer IDs (`num`) for Mudlet: a deterministic hash of `room.label.v` (handling collisions), a persisted label-to-int
table in SQLite, or an index assigned in package/declaration order? - How should `Room.Info` carry both exit destination
IDs (for Mudlet) and door states (for the web client): move door states to a separate `"doors"` map, nest exit objects,
or add Mudlet-specific fields alongside existing ones? - Should `Room.Info` include `"area"` (derived from the first
region in `room.regions` or from the package path `room.label.pkg`) and `"environment"` (from a room tag or attribute)?
- Should room prototypes in Starlark optionally declare `(x, y, z)` coordinates, or should coordinates be laid out
automatically from the exit graph (or left to Mudlet's incremental layout)? - Should destination room numbers in `exits`
be omitted or masked for unvisited rooms, closed doors, or custom non-compass exits so the payload does not spoil hidden
areas? - In `gups serve --dev` (where the main HTTPS server is off and only `DocsServer` runs on `--docs-port`), should
`DocsServer` host the `.mpackage` or MMP XML map if `Client.GUI` or `Client.Map` is used?
