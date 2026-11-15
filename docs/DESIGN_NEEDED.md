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
