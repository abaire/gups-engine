# Implementation Decisions Not Covered by the Spec

This log records choices made while implementing [SPEC.md](SPEC.md) where the spec is
silent, ambiguous, or ahead of the current milestone. Each entry is a candidate for review:
accept it into the spec, or change it (and the code) to match a different decision.

**Format:** every entry has a stable ID, the spec section it relates to, what was decided,
and where the code lives. Statuses are `open` (not yet reviewed), `accepted` (folded into
the spec; keep the entry for history) or `rejected` (code must change; say to what).
Don't renumber entries; append new ones at the end of their section with the next free ID.

---

## Scheduler and initiative tiers (§5.1, §5.4.1)

### D-001 Actors engaged mid-round act in the final tier — `open`
Initiative is rolled only at the combat-round phase. An actor who becomes engaged after the
roll has no initiative until the next round, so it acts in the trailing unengaged tier.
*Code:* `World.runTiers`, `World.runCombatRound`.

### D-002 The action pulse on a round boundary uses the previous round's initiative — `open`
Phase 2 runs before phase 3 on pulses where `pulse % 8 == 0`, so that action pulse still
uses the old round's values. Initiative is cleared for every actor before each new roll.
*Code:* `World.runPulse`, `World.runCombatRound`.

### D-003 The tier snapshot covers every actor in the world — `open`
§5.4.1 says the snapshot records "each actor in [the tier]". The implementation records the
room, track index and ability to act of *all* actors when a tier starts. Same-tier actions
then validate their targets against tier-start state too, for example when a same-tier
knockback moves a lower-tier target.
*Code:* `TierSnapshot`.

### D-004 Stunned actors are skipped entirely — `open`
Stun is an affect carrying the `stunned` flag (see D-040). An actor that can't act at tier
start does nothing that pulse: its `waitState` does not count down and its cast does not
advance. Stun therefore effectively extends recovery.
*Code:* `World.runTiers`, `TierSnapshot.canAct`.

### D-005 Death wins over the same actor's movement at end of tier — `open`
If an actor is still mortally wounded when its tier ends, its deferred death is processed
and any deferred room transition of its own (move, flee, teleport) is dropped. If it was
healed above 0, the transition goes ahead.
*Code:* `World.commitDeferred`.

### D-006 Deaths and transitions outside a tier happen immediately — `open`
Damage dealt from timers, fast-lane commands, or direct verb calls outside phases 2 and 3
processes death right away instead of deferring.
*Code:* `World.deferRemoval`.

### D-007 Session expiry runs between phase 4 and the output flush — `open`
Link-dead and idle auto-quit aren't a numbered phase. They run after phases 5 (perception)
and 6 (bubble maintenance), before output flush.
*Code:* `World.expireSessions`.

### D-008 Fast-lane output is flushed immediately — `open`
Output produced while handling a session's input between pulses is flushed to that
session at once instead of waiting for phase 7. Output caused by anything else waits for
phase 7.
*Code:* `World.handle(ClientInputEvent)`.

### D-009 Finishing exactly on the next boundary is not an overrun — `open`
A pulse that ends at or before the following boundary is on time. The next boundary after
an overrun may be "now", in which case the next pulse runs immediately.
*Code:* `PulseTimer.pulseFinished`.

### D-010 Shutdown — `open`
`Shutdown(delayPulses, reason)` broadcasts the reason and stops the loop once the delay has
passed. The server then takes the final checkpoint (§5.11) on the world thread and waits up
to 30 s for the writer to commit it, including on a JVM shutdown (Ctrl-C).
*Code:* `World.handle(WorldControlEvent)`, `WorldLoop.run`, `GupsServer.run`.

## Commands (§4.9, §5.2)

### D-011 Lookup precedence and abbreviations — `open`
Order: exact engine command or alias, then exits, then exact global command, then room
`commands`, then item `commands` (inventory, equipment and ground items by ascending id),
then unique-prefix matches over engine commands and then global commands, in
registration order.
*Code:* `CommandRegistry.resolve`.

### D-012 Standard directions always resolve to movement — `open`
`north`, `ne`, etc. resolve to the movement command even if the room has no such exit
(they fail validation with "You can't go that way."). Non-standard exit names resolve to
movement only in rooms that have them. `Direction` is a free-form name with aliases and
opposites for the ten standard directions.
*Code:* `CommandRegistry.resolve`, `Direction`.

### D-013 Engine aliases — `open`
`l` = look, `i`/`inv` = inventory, `eq` = equipment, `k`/`attack` = kill, `c` = cast,
`take` = get, and a leading `'` is `say`.
*Code:* `EngineCommands.all`, `parseLine`.

### D-014 Rate limiting applies to fast-lane commands from sessions only — `open`
Tick-lane commands, unknown verbs and mob commands are not charged against the token
bucket. The 60 s drop window is measured with the input event's `receivedNanos`.
*Code:* `World.handle(ClientInputEvent)`, `TokenBucket`.

### D-015 Input from a session without a character — `open`
It goes to the world's `LoginHandler` (D-082). A world without one answers "You are not
playing a character."
*Code:* `World.handle(ClientInputEvent)`.

### D-016 `quit` is refused while engaged — `open`
"No way! You're fighting for your life!" The link-dead auto-quit ignores combat.
*Code:* `EngineCommands.quit`, `World.expireSessions`.

### D-017 Disconnecting clears the action queue — `open`
This matches "keeps fighting with auto-attacks only" in §5.12.
*Code:* `World.disconnect`.

### D-018 `tell`, `shout`, `whisper`, `gossip` — `open`
`tell` finds an in-world player by name first, otherwise a mob in the room by keyword, which
receives `on_tell`. `shout` reaches the room and every room whose exit toward it doesn't block
`noise` (D-120). `whisper` shows bystanders "X whispers something to Y."
`gossip` goes to every player, including link-dead ones (who see nothing).
*Code:* `EngineCommands`.

### D-019 Commands not in M1 — `open`
`give`, `put`, `wear` and `use` arrived in M5 (D-114), and `wield` and `remove` handle every
slot. `quests` comes from `//lib:quest.star` (D-126). The builder and instructor `@` commands
arrived in M6.2 (D-143, D-144).
*Code:* `EngineCommands`.

## Action lifecycle (§5.3)

### D-020 Commands are parsed into actions when popped — `open`
A queued entry keeps the verb and argument text. Arguments, including target keywords,
are resolved when the action is popped, so `kill goblin` targets whichever goblin is
present then. The Kotlin seam is the `Action` interface (`validate`, `veto`, `begin`,
`resolve`).
*Code:* `Action`, `World.runQueued`, `TickCommand.plan`.

### D-021 `cast_allowed` runs once, at the start of the veto phase — `open`
It runs before the `before_cast` hooks and is not re-checked at re-validation after the
warmup.
*Code:* `CastAction.veto`.

### D-022 Re-validation re-runs the full validation — `open`
After a warmup, target presence and band, cost, and the `silenced` flag are all checked
again. The cast target is chosen at the first validation and stays fixed through the
warmup.
*Code:* `CastAction`, `World.advanceCast`.

### D-023 Recovery never shortens existing lag — `open`
After a resolve, `waitState = max(waitState, lag)`. No lag is applied if the resolve left
the actor out of the world or casting.
*Code:* `World.finish`.

### D-024 Skill targeting defaults — `open`
- An `enemy` skill with no keyword targets the caster's primary target.
- An `ally` skill with no keyword targets the caster.
- `item` targets are supported from M5 (D-123).
- `room` range applies the effect to everyone in the room except the caster (and, with a
  bubble, except dormant actors).

Skill proficiency is stored but not rolled against: §4.6 gives the rules no proficiency
input yet. Rules functions can read it from `ctx.actor.skills`.
*Code:* `CastAction`.

### D-025 Cast messages — `open`
A warmup start shows "You begin casting X." to the caster and "X begins casting." to the
room. The resolve always tells the caster "You cast X [at Y]." and tells the target.
*Code:* `CastAction`.

## Tactical track (§5.5)

### D-026 Track-movement lag lives in the rules engine — `open`
`advanceLag`, `retreatLag` (success), `retreatFailLag` and `fleeFailLag` are Kotlin
`RulesEngine` methods with defaults 2, 2, 4 and 4. They are not Starlark rules exports in
`api_version` 1. The spec names only advance (2) and the failure lags (4); a successful
retreat costing 2 is new.
*Code:* `RulesEngine`.

### D-027 "Hostile" means an engaged opponent — `open`
Pinning, a target-less `retreat` and a target-less `advance` consider only actors in the
actor's `fighting` set who are in the same room. Engagement is always symmetric.
*Code:* `World.opponentsOf`, `World.isPinned`.

### D-028 Retreat details — `open`
- With no target, the threat is the nearest opponent by track distance, then lowest id.
  With none, "You have nothing to retreat from." and no lag.
- On a shared index the actor steps toward the side with more room; a tie goes toward the
  back (higher index).
- `retreat_check` is rolled against the first opponent at the actor's index.
*Code:* `RetreatAction`.

### D-029 Flee details — `open`
- An exit with a closed or locked door is impassable; `blocks` never affects passability,
  so `blocks = ALL` exits can be fled through.
- Flee costs no move points and does not fire `before_exit`/`before_enter`.
- With no passable exit, validation fails ("There's nowhere to flee!") and no lag applies.
*Code:* `FleeAction`.

### D-030 Walking costs no lag — `open`
"One action" in §5.5 is taken as the action pulse itself; recovery after a move is 0.
*Code:* `MoveAction`.

### D-031 Doors are synchronized on both sides — `open`
Opening, closing, locking or unlocking a door also sets the door on the first exit in
the destination room that leads back and has a door. Lock and unlock need the key in
inventory; an equipped key doesn't count.
*Code:* `EngineCommands.planDoor`, `World.reverseExit`.

### D-032 Picking up from containers — `open`
`get X from Y` uses the container's ground band for `get_lag`; a container carried by the
actor counts as `MELEE`. The container must be open. The carry-limit check includes the
weight of a container's contents.
*Code:* `GetAction`, `Item.weight`.

### D-033 Knockback and pull — `open`
Knockback pushes away from the caster; on a shared index it goes toward the side with more
room, ties toward the back. Pull moves toward the caster and never past it. Both clamp to
the track.
*Code:* `World.shove`, `World.pull`.

## Combat (§5.4)

### D-034 Engagement and `kill` — `open`
`start_combat` engages both sides and sets each side's primary target if it has none.
`kill` engages, switches the attacker's primary target, costs no lag, and does not swing
immediately: the first swing comes at the next combat round. `kill` on the current primary
target fails validation.
*Code:* `World.startCombat`, `EngineCommands.planKill`.

### D-035 `before_attack` dispatches on the defender — `open`
The defender is the primary owner, followed by the room, observers, and items, including
the attacker's equipped weapon.
*Code:* `World.autoAttack`.

### D-036 Combat messages show no numbers — `open`
"You hit X." / "X hits you." / "X misses Y."
*Code:* `World.autoAttack`.

### D-037 Spell damage goes through `mitigate`; only damage starts combat — `open`
The `damage` effect primitive applies `rules.mitigate`. "A hostile skill landing" (§5.4) is
taken to mean a `damage` effect on someone other than the caster; a stun or debuff alone
does not start combat.
*Code:* `World.applyEffect`.

### D-038 Death specifics — `open`
- The killer is the last actor who damaged the victim, if still in the world.
- The order is: messages, `on_death`, XP, then the corpse.
- A mob corpse holds the mob's inventory, its equipment and the loot rolls.
- A player corpse holds the carried inventory only; equipment stays on the player.
- A player respawns at index 0 of the respawn room with 1 hp and all affects removed.
- Corpses decay after 1200 pulses (`CORPSE_DECAY_PULSES`, 5 min). Decay drops a
  container's contents where it lay.
- Loot `chance` is a probability between 0 and 1.
*Code:* `World.processDeath`, `World.decay`.

### D-039 Levelling — `open`
`awardXp` raises `level` while `xp >= xp_to_level(level)`. No stat or vital gains yet; the
spec has no rules hook for them.
*Code:* `World.awardXp`.

### D-040 Stun and silence are affects — `open`
`stun(pulses)` applies an affect named `stun` with the `stunned` flag. `silenced` is an
affect flag that fails cast validation. Re-applying an affect with the same name replaces
the old one.
*Code:* `World.applyAffect`, `Effect.Stun`.

### D-041 Summon durations use an affect — `open`
`summon(..., duration=N)` gives the summoned mob a `summoned` affect; when it expires the
mob fades away and leaves the world.
*Code:* `World.applyEffect`, `World.expireAffect`.

### D-042 Dispel filter syntax — `open`
An exact affect name, or a prefix ending in `*`.
*Code:* `World.applyEffect`.

### D-043 Respawn timing — `open`
A spawn rule's respawn deadline is set when its first live instance leaves the world. The
rule refills to `count` at the first world tick at or after the deadline. Rooms fill their
spawns when the world is constructed.
*Code:* `SpawnState`, `World.fillSpawn`, `World.removeActor`.

### D-044 Regen also runs in combat — `open`
The world-tick regen applies to every actor without `no_regen`, engaged or not.
*Code:* `World.runWorldTick`.

## Default ruleset (§4.6)

### D-045 Built-in formulas — `open`
| Function | Formula |
|---|---|
| `to_hit` | `60 + 2·(dex_a − dex_d) + 3·(level_a − level_d)`, clamped 5..95. Armor is not used here. |
| `damage` | weapon dice (unarmed `1d4`) + `(str − 10) / 2`, at least 1 |
| `mitigate` | `max(1, amount − armor/2)`, then `× (100 − resist) / 100`, at least 0 |
| `initiative` | `dex + level` (from the spec) |
| `interrupt_check` | damage ≥ the skill's `interrupt.damage_pct` percent of max hp |
| `retreat_check` | `50 + 3·(dex − pinner dex)`, clamped 5..95 |
| `flee_chance` | `40 + 2·(dex − 10)`, clamped 5..95 |
| `move_cost` | 1 |
| `regen` | hp `1 + con/4 + level/2`; mana `1 + (int + wis)/8`; move `2 + dex/4` |
| `carry_limit` | `str × 10` |
| `encumbrance` | `{"dex": -2}` |
| `get_lag` | 0 / 2 / not allowed (from the spec) |
| `xp_for_kill` | `50 × victim level` |
| `xp_to_level` | `1000 × level` |
| `on_player_death` | respawn at the recall room, lose 10% of XP |
*Code:* `DefaultRules`.

## Event dispatch and hooks (§4.4, §5.6)

### D-046 Who counts as an observer — `open`
Dispatch order is: the event's primary entity (the item for get/drop, the target for
damage/death, the defender for `before_attack`, the caster for casts, none for movement and
`say`), then the room, then other actors in the room ascending id, then the room's items,
then items carried or equipped by the primary actor and those other actors. The acting
actor is excluded from the observers unless it is the primary. Owners are de-duplicated.
*Code:* `World.dispatch`.

### D-047 Self-only hooks — `open`
`on_tick`, `on_combat_tick`, `on_spawn`, `on_tell`, `on_load`, `on_reset`, `on_wake` and
`on_nearby` fire on their owner only, not through observer dispatch. `on_tick` fires on every
simulated room, actor and item (SPEC §5.9) that has the hook, ascending id. (Until M2 it skipped items; with scripts, an item declaring `on_tick` that
never fired would be a trap.)
*Code:* `World.invokeOn`, `World.runWorldTick`.

### D-048 Hook timing around movement — `open`
`before_enter` dispatches in the destination room. On arrival the player's `look` is sent
before `on_exit` and `on_enter` fire.
*Code:* `MoveAction`, `World.transition`.

### D-049 Cascade depth counts every script entry — `open`
Self-only hooks, timer callbacks and affect hooks all count toward the depth-8 limit, not
just dispatched events.
*Code:* `Hooks.kt`.

### D-050 Script exceptions are always contained — `open`
Exceptions from the script host are logged as `script` diagnostics and treated as allow,
even in strict mode. Strict mode rethrows only engine exceptions.
*Code:* `World.dispatch`, `World.guarded`.

## Kotlin seams and types (§3, §4.11)

### D-051 Seams that differ from §4.11 — `open`
- `ScriptHost` is `invokeHook(world, owner, hook, ctx)` plus `invokeRef(world, owner, ref,
  ctx)` for timers and affect hooks, plus `isSuspended(owner, ref)`, which keeps a
  quarantined package's timers pending (D-068).
- `loadPackages` is not on `ScriptHost`: `gups.script.ScriptLoader.load(paths)` returns the
  `LoadResult`, and a `StarlarkHost` is built from it before the `World`. The world will
  drive loading itself once reload exists (M3).
- `OutputSink` has `send` and `close(reason)`; `gmcp` is deferred to M6.
- `CheckpointSink` and `Persistence` are deferred to M4.
- `CapturingSink` lives in `core` main so the harness can reuse it.
*Code:* `Hooks.kt`, `Seams.kt`.

### D-052 Prototypes are Kotlin types in `core` — `open`
`RoomProto`, `MobProto`, `ItemProto`, `SkillProto` and `ZoneProto` are immutable data
classes in `core`. Behaviors, `think`, zone commands and custom `resolve` are Kotlin function
interfaces; the script host implements `think`, commands and `resolve` (`ScriptThink`,
`ScriptCommand`, `ScriptResolve`). Hooks are not on the prototypes: the host looks them up
by prototype label.
*Code:* `model/Protos.kt`, `script/Host.kt`.

### D-053 Reserved engine labels — `open`
`//engine:player` is the prototype label of every player. `//engine:corpse` is the built-in
corpse container, always present in the item table.
*Code:* `Prototypes`.

### D-054 RNG algorithm — `open`
`Rng` wraps `kotlin.random.Random(seed)` (XorWow), which is deterministic across platforms.
Changing it changes every replay.
*Code:* `Rng`.

### D-055 `AttrValue` is serialized in `persist`, not annotated in `core` — `open`
§3.2 shows `AttrValue` as `@Serializable`. Instead `core` stays free of kotlinx.serialization,
and `persist` maps values to JSON itself: ints and bools as JSON numbers and booleans,
strings as strings, lists as arrays and dicts as objects.
*Code:* `AttrValue.toJson`, `JsonElement.toAttr`.

### D-056 Player names are unique among in-world players — `open`
`spawnPlayer` rejects a duplicate name, case-insensitively. Account-level uniqueness
(§3.5) comes with `auth`.
*Code:* `World.spawnPlayer`.

### D-057 Strict mode — `open`
`WorldConfig(strict = true)` rethrows engine exceptions instead of logging them and
aborting the action. Tests and the harness use it together with `checkInvariants`.
*Code:* `WorldConfig`, `World.guarded`.

### D-058 One shared world dispatcher — `open`
`WorldLoop.worldDispatcher` is a single process-wide `limitedParallelism(1)` view, so every
world in the process shares one thread.
*Code:* `WorldLoop`.

### D-059 Unsupported control events — `open`
`Deploy` is accepted but only logs a `control` diagnostic until M6. `Reload` works from M3
(D-081).
*Code:* `World.handle(WorldControlEvent)`.

## Script host (§2.2, §4.1–§4.5, §6) — M2

### D-060 The Starlark implementation and its language limits — `open`
The only Maven Central packaging of `net.starlark.java` is `com.eed3si9n.starlark:starlark`
4.2.1, Bazel 4.2.1's interpreter (it brings Guava 27). That version rejects `lambda` and
nested `def`s at parse time. So every hook, command, timer and future modifier is a
top-level `def`; the lambda modifiers in §4.6's example can't be written, and `ctx.after`'s
"rejects lambdas" case can't arise. `load` is also a reserved word (see D-072).
*Code:* `gradle/libs.versions.toml`, `script/build.gradle.kts`.

### D-061 Zone resets — `open`
A package with `reset_pulses` first resets that many pulses after the world is built, then
every `reset_pulses`. Resets are processed in the world tick, so the effective interval
rounds up to a world tick. A reset refills every spawn rule in the package to its count,
then fires `on_reset` on the package's rooms, ascending id. Items have no spawn rules; zones
restock them from `on_reset` (the tutorial's armory does).
*Code:* `World.resetZone`, `ZoneProto`.

### D-062 When `on_load` fires — `open`
`World.fireOnLoad()` fires `on_load` once on every entity, ascending id, after the world is
built and global commands are installed. Spawns filled while the world is built fire
`on_spawn` first, during construction. The harness calls it in `t.world()`, and `serve`
calls it once a saved world has been restored (so restored entities get it too). A reload
fires `on_load` only on rooms it creates. Characters restored at login don't get it.
*Code:* `World.fireOnLoad`, `TestCtx.world`, `GupsServer.boot`.

### D-063 `path_to` is an uncached BFS — `open`
Breadth-first over exits in declaration order, through open doors only (closed and locked
doors block), so ties resolve the same way every time. Empty list if already there, None if
unreachable. Results are cached from M5 (D-124).
*Code:* `World.pathTo`.

### D-064 What loading a package loads — `open`
- Loading a package also loads every package it refers to by label (exits, spawns, keys,
  loot, skills, summons) or by `load()`, transitively. A referenced package that isn't on
  disk is a label error.
- A package fails if any of its files fails, if any label doesn't resolve to a declaration
  of the right kind, or if anything it depends on failed.
- `package.star` must call `package()` exactly once and holds nothing else, apart from the
  top-level functions its `modifiers` name (D-111); it can't be `load()`ed. Content files evaluate in alphabetical order. Only test files may load
  `*_test.star` files.
- The 5,000,000-step load limit is per package, across its files.
- Global commands may not shadow an engine command or alias, or another package's global
  command. An attribute namespace used by two packages (or a package and a shared
  namespace) fails every package involved.
*Code:* `ScriptLoader`.

### D-065 What a script call is charged to — `open`
Package charging decides the step budget's diagnostics, the quarantine count, and the
quarantine check.
- A hook is charged to its owner's prototype package, even when the function lives in a
  loaded library file.
- Timers and affect hooks are charged to the owner's package if it is a script package,
  otherwise to the package of the function's file (for example, a timer on a player).
- Zone commands are charged to the declaring package, global commands to the registering
  package.

At run time, relative labels and attribute keys resolve against the package of the
innermost running function, so a `//lib` helper writes the lib's own namespace (and its
shared namespaces) even when a zone hook calls it.
*Code:* `StarlarkHost.chargeTo`, `Ctx.attrKey`, `callerFile`.

### D-066 Where the wall-clock backstop is checked — `open`
The 20 ms (hooks) and 2 s (load) limits are measured from the start of the top-level event
or package load, and checked at every `ctx` verb call and every declaration call. A hook
that loops without calling the engine is stopped by the step limit only. The harness turns
the wall clock off (`ScriptLimits.HARNESS`), so test results never depend on machine speed.
*Code:* `Frame.checkClock`, `LoadCtx.tick`.

### D-067 Allocation caps apply at the engine boundary — `open`
Starlark-Java has no allocation hook. The 1 MB cap is enforced where values reach the
engine: verb arguments, hook return values, stored attributes and timer arguments. A string
over 1,048,576 characters, or a list or dict over 131,072 elements (1 MB at 8 bytes each),
is an `alloc_limit` error. A hook can build larger values internally as long as it doesn't
hand them over; the step limit bounds that work. The 64 KB attribute cap is measured as
compact JSON.
*Code:* `checkString`, `checkSize`, `jsonSize`.

### D-068 Quarantine details — `open`
- The 60 s window is 240 pulses of game time, so the harness and production agree. The
  21st error inside the window quarantines.
- While a package is quarantined, its hooks allow, its zone and global commands answer
  "Nothing happens.", its `think` falls back to the top proposal, and its affects' `on_tick`
  and `on_expire` are skipped (the affects still tick down and expire).
- Its timers stay pending: the world skips them while due, and they fire on the first
  timer phase after the quarantine clears.
- `@quarantine` and `@unquarantine` call `ScriptHost.quarantine` and `unquarantine`, then
  checkpoint so the state persists in `zone_states` (D-146).
- Notifications go to `StarlarkHost.alerts`, which the server routes to instructor alerts
  (D-145).
*Code:* `StarlarkHost`, `World.runTimers`.

### D-069 Script diagnostics — `open`
- **Kinds:** `script_error`, `step_limit`, `wall_clock`, `alloc_limit`, `missing` (a hook
  or timer function no longer exists) and `quarantine`. The kind `script` remains for
  failures of Kotlin-hosted handlers.
- **Fields:** each diagnostic carries the package, prototype label, hook, file label, line,
  Starlark stack, a one-line ctx summary and the seed.
- **Room:** a script diagnostic also names the room its owner was in, which the repro
  bundle snapshots (D-098).
- **Owner alerts:** the kinds `script_error`, `step_limit`, `wall_clock` and `missing` send
  a one-line alert to the package's online builders, at most one per package per 10 s
  (D-145).
- **Storage:** diagnostics are kept in `World.diagnostics` and written to the `diagnostics`
  table with the next checkpoint batch, periodic or immediate.
*Code:* `Diagnostic`, `StarlarkHost.report`.

### D-070 Declaration details — `open`
- **Arguments:** every declaration takes its name positionally, everything else by
  keyword. Unknown arguments are errors with a spelling suggestion.
- **Defaults:** the display `name` defaults to the declaration name with `_` as spaces.
  `keywords` default to the display name's words minus articles, or else the declaration
  name.
- **Item slots:** an item's slot is `wear = "<slot>"`; `wield = "<slot>"`, as in the
  spec's example, is an alias.
- **New builtins:** `container(capacity, max_weight, state, key)` makes an item a container.
- **Constants:** `CRAMPED`, `NORMAL` and `LARGE` are the strings `"cramped"`, `"normal"` and
  `"large"`; `ALL` is the list of all channel names.
- **Not yet supported:** `expected_tombstones` (M6) is refused with "planned for M6" when
  non-empty. `behaviors`, `modifiers`, `equip_affects` and `region()` arrived in M5 (D-111,
  D-112, D-113, D-121).
- **When builtins work:** declaration builtins work only while a package loads; effect
  primitives work anywhere.
*Code:* `DeclarationBuiltins`, `Kw`.

### D-071 Harness details — `open`
- **`p.do(line)`:**
  - Submits the line directly, bypassing the session rate limiter.
  - Runs pulses until the player's queue is empty and it isn't casting (at most 1000; more
    is an error).
  - Returns all output since the last `do()` or `output()`.
- **Players and worlds:**
  - Harness players are exempt from the idle auto-quit.
  - Worlds run in strict mode with the invariant check after every pulse.
- **Test code:** a test function's own code may spend 10,000,000 steps; pulses it runs
  don't count.
- **Diagnostics:** script diagnostics from a test's worlds are reported as warnings and
  don't fail the test. Tests assert on `w.diagnostics()` explicitly.
- **`t.eq` and `t.ne`:** an entity view equals a label string that resolves (relative to
  the calling package) to its prototype label. Players are excluded.
- **`t.world`:** `packages` defaults to the test's own package. `rules` names a rules package
  to use instead of `//rules` (D-108), and `bubble=True` keeps a reality bubble (D-116).
- **Beyond §4.12:**
  - `spawn_player` takes `hp`, `mana`, `move`, `index`, `stats` and `skills`.
  - `w.damage` takes `amount` or `pct`, and `type`.
  - `w` also has `get_attr`, `quarantined()` and `pulse`.
  - `w.trace()` keeps the last 400 lines and prints them when the test fails.
- **`gups test`:**
  - `--world DIR` defaults to the current directory.
  - Patterns are `//...`, `//a/...`, `//a`, `//a:file_test.star` and `//a:test_name`.
  - Exits 1 if any test fails or errors, or a pattern matches nothing.
*Code:* `gups.harness`, `TestCmd`.

### D-072 `t.world(load=...)` is spelled `t.world(packages=...)` — `open`
`load` is a reserved word in Starlark, so §4.12's `t.world(seed=42, load=[...])` doesn't
parse. The tutorial and docs use `packages`.
*Code:* `TestCtx.world`.

### D-073 `ctx` semantics the spec leaves open — `open`
- **Errors:**
  - A bad verb argument raises a script error at once.
  - Core `IllegalArgumentException`/`IllegalStateException` from a verb become script
    errors too.
- **Timers:**
  - Timer callbacks are called as `fn(ctx, **args)` and also see the arguments as
    `ctx.args`.
  - The owner is the scheduling hook's `ctx.self`.
- **Combat verbs:** `ctx.damage(target, amount, type, source=None)` is raw: no to-hit roll
  and no mitigation.
- **`ctx.deny`:** raises unless the running hook is a `before_*` hook.
- **Item verbs:**
  - `spawn` returns a view, or a list when it makes several entities. For items, `at` may
    be a room or room label (ground, at `index`), an actor (carried) or a container.
  - `give(actor, label_or_item, count=1)` spawns or moves the item into the inventory and
    fires no hooks (`on_give` comes with the `give` command in M5).
  - `move_item` takes `index=0` for rooms.
- **Door verbs:** they set both sides at once (D-031) and print nothing.
  - `open_door` also unlocks.
  - `close_door` leaves a locked door locked.
  - `lock_door` closes the door.
  - `unlock_door` leaves the door closed.
- **Queries:**
  - `here(keyword)` searches the owner's room: actors first, then ground items.
  - `find(label, scope)` counts entities whose location ends in the room, so items carried
    there count. `"zone"` means rooms of the owner's room's package.
  - `enemies_near` returns engaged opponents. `allies_near` returns actors on the same side
    (players with players, mobs with mobs) not engaged with the actor.
- **Mob actions:**
  - `move_toward(mob, x)` takes a direction, advances on an actor in the same room, or
    takes the first step of `path_to`. It returns False when there is no way.
  - `act_cast` requires the mob to know the skill.
  - Mob verbs return whether the action was queued.
- **Command ctx:**
  - `ctx.args` is the argument text.
  - `ctx.target` is the actor in the room matching the first argument word.
  - `ctx.item` is the item, for item commands.
  - `ctx.self` is the room or item, or the actor for global commands.
- **Fields beyond §4.5:**
  - `ctx` also has `from_room`, `to_room`, `affect` (affect hooks) and `targets`
    (`on_combat_tick`).
  - `event`, `source_room` and `via_exit` are set for `on_nearby` (D-120) and None otherwise.
  - Views also expose `xp`, `in_world`, `keywords`, `wait_state`, `home_room`, `desc`,
    `track_size`, `flags`, `contents()` and an exit view.
- **Views:**
  - Views read live state.
  - Views compare equal when they show the same entity.
  - `attrs` shows the reader's own keys by short name.
  - Using a ctx or view after its hook returned is an error.
- **Attributes:**
  - Short keys are `[A-Za-z0-9_]+`.
  - `get_attr` returns a mutable copy.
- **Simulation verbs:** `set_active` and friends drive the bubble (D-116, D-119). Each
  returns None; a reason added twice counts once.
*Code:* `Ctx`, `Views.kt`.

### D-074 Hook catalog enforcement — `open`
A hook kwarg must be a catalog name (a typo suggests the right one) and must be attachable
to that kind of entity per §4.4's "Attach to" column. From M5 the engine fires every hook in
the catalog: `on_wake` (D-118), `on_nearby` (D-120), `before_use`/`on_use` and `on_give`
(D-114).
*Code:* `HookCatalog`.

### D-075 `think` works before behaviors — `open`
§7.4 puts `think` in M5, but the core seam already existed, so mobs could declare
`think = fn` from M2. `ctx.proposals` holds the behaviors' proposals from M5 (D-121).
- If `think` queued an action for its own mob through `ctx.act*`, the top proposal is not
  queued as well.
- Returning `"idle"` skips the turn.
- A failing `think` falls back to the top proposal.
*Code:* `StarlarkHost.think`.

### D-076 Engine verbs added to `core` — `open`
Verbs without a player command are public core functions:
- `say` and `emote`.
- `setDoor`, `setExitBlocks` and `splitItem`.
- `pathTo`.
- `enqueue`, `actMove`, `actAttack`, `actCast`, `actAdvance` and `actRetreat`.

`kill` is now a `KillAction`, and `CastAction` can take a fixed target. `split` copies the
stack's attributes and decay deadline to the new item, and refuses equipped stacks.
*Code:* `core/world/Verbs.kt`.

### D-077 The tutorial lives in the engine repository — `open`
§2.3 places `zones/tutorial` in the course world. Its tests must pass on every engine
commit (§7.2), so the canonical copy is `template-world/zones/tutorial` in this repository.
The course world template (M6) will start from `template-world/`. The harness and CLI
tests run it.
*Code:* `template-world/`, `TutorialTest`.

### D-078 `print()` — `open`
In hooks, `print()` output goes to the trace. In tests it goes to the test's report, shown
when the test fails. At load time it is dropped.
*Code:* `StarlarkHost.call`, `TestRunner.runTest`, `ScriptLoader`.

## Network, dev server and tooling (§2.4, §4.10, §5.10, §5.13) — M3

### D-079 Seams added for M3 — `open`
- `WorldControlEvent.Reload` carries package paths (`zones/forest`), not labels, since a
  reload swaps whole packages.
- `Connect` carries the peer address and transport, for the dev login's localhost check and
  `@who` (M6).
- `OutputSink.prompt(text)` sends a prompt (D-083). Headless sinks ignore it.
- `WorldInbox` (in `core`) is what listeners submit to; `WorldLoop` implements it.
- `World.login: LoginHandler?` handles sessions without a character (D-082).
- `ScriptHost.reload(world, packages): ReloadResult` is the reload seam. `World.onDiagnostic`
  lets the server log diagnostics as they happen.
- `core` exports `kotlinx-coroutines` as an `api` dependency, because `WorldLoop`'s public
  API uses it.
*Code:* `Seams.kt`, `Hooks.kt`, `Login.kt`.

### D-080 Live reconciliation on reload — `open`
Without persisted deltas (M4), a reload computes each instance's delta on the spot: a field
whose live value still equals the old prototype's takes the new prototype's value, and any
other field keeps its live value.
- **Mobs:** level, max hp/mana/move, armor, base stats, resists, skills, `think_every` and
  `dormant_recovery` follow that rule. Current vitals stay, capped at the new maximum.
- **Rooms:** door states and exit blocks follow the same rule.
- **Items:** they read everything from their prototype, so they simply switch to the new one.
- **Deleted prototypes:**
  - Mobs are despawned, and so are items that aren't in a player's custody.
  - Items in a player's custody become tombstones: kind `tombstone`, same label and name, no
    hooks, commands or stats, and they keep any container so the contents survive.
  - A deleted room sends its players to their recall room, or else the first room.
- **Skills:** actors forget skills whose prototype is gone.
- **Spawns:** spawn rules carry their live mobs over by label. New rooms fill their spawns,
  then get `on_load`.
- **Versions:** when an instance's prototype version goes up, the package's `migrate()`
  runs (D-094).
*Code:* `World.applyPrototypes`.

### D-081 How a reload runs — `open`
- **Evaluation:** a reload re-evaluates every live package plus the named ones, together,
  so labels across packages are checked against the new code.
- **Failure:** any load error refuses the whole reload, and so does a result with no rooms.
  The old version stays live, and the errors (with caret snippets) go to every connected
  builder and instructor and to `@errors`.
- **Removal:** a package whose `package.star` is gone is removed, unless something still
  refers to it.
- **Timers:** a timer whose function no longer exists is dropped, with a `reload`
  diagnostic.
- **Quarantine:** a successful reload clears the reloaded packages' quarantine. §6.3 only
  says a deploy does this; `--dev` saves are treated the same.
- **Checkpoint:** a successful reload ends with a periodic checkpoint (§5.10 step 6).
- **Not yet:** `@reload` arrives in M6 with the other instructor commands.
*Code:* `StarlarkHost.reload`, `World.reload`.

### D-082 The `--dev` login and world — `open`
- **Prompting:** the server asks for a name: 2 to 16 letters, capitalized.
- **Characters:** every character belongs to the local instructor account (D-131), so it gets
  `Role.INSTRUCTOR` and is idle-exempt. Naming a character that is already playing takes it
  over and closes the old connection. Otherwise a saved character is restored (D-095), or a
  new one is created and saved at once.
- **Maintenance:** while the database can't be written, new logins are refused with a
  maintenance message (§6.4); taking over a character still works.
- **Connections:** a remote address that isn't loopback is refused and closed.
- **Starting room:** new characters start in `--start`, else `start_room` (D-138), else
  `//zones/tutorial:gate` if it exists, else the first room by label.
- **Starting stats:** they are the engine defaults. §5.12 calls them rules-defined, but §4.6
  has no rules export for them, so the rules module (M5) doesn't set them either.
- **Loading:** `--dev` loads every package under `--world` (default: the current
  directory). Packages that fail to load are reported and left out.
*Code:* `DevLogin`, `GupsServer`.

### D-083 Prompts — `open`
After each burst of output, a playing session gets `<{hp}hp {mana}m {move}mv> `. Telnet ends
it with IAC EOR when the client agreed to EOR, else IAC GA (D-088).
*Code:* `promptFor`, `World.flush`, `TelnetConnection.encodePrompt`.

### D-084 Invariant violations in `--dev` — `open`
A world in strict mode (the harness) throws. Otherwise (`--dev`) each violation is logged
as an `invariant` diagnostic every pulse it persists, and the world keeps running. The
`--prod` policy (check at the world tick, keep the entity out of the checkpoint) arrives
with checkpoints in M4.
*Code:* `World.runPulse`.

### D-085 `@eval` — `open`
- **What it runs:** the input is evaluated as an expression and its `repr` is shown;
  anything that isn't an expression runs as statements. `print()` output comes first.
- **Bindings:** `ctx`, `self` and `room` are predeclared, along with the package builtins
  (effect primitives, constants). Each call gets a fresh module.
- **Package:** relative labels and attribute keys belong to the package of the caller's
  room.
- **Limits:** 1,000,000 steps and 1 s, shared with any hooks its verbs trigger.
- **Errors:** errors are shown to the caller only. They are not diagnostics and don't count
  toward quarantine.
*Code:* `StarlarkHost.eval`, `DevTools`.

### D-086 The `--dev` file watcher polls — `open`
Every 100 ms it compares the modification time and size of every non-test `.star` file.
Changes are reported once nothing has changed for 200 ms, grouped by package: the nearest
directory with a `package.star`, or the file's own directory if that file was just deleted.
It doesn't use `WatchService`, which polls every few seconds on macOS.
*Code:* `WorldWatcher`, `GupsServer.run`.

### D-087 Color markup and color depth — `open`
§2.2 asks for ANSI rendering but defines no markup.
- **Tags:** text may contain `{red}` and the other base colors, `{bright_red}` etc., `{bold}`,
  `{underline}`, `{/}` (reset) and `{x0}`–`{x255}`. `{{` is a literal brace, and unknown
  tags stay as typed.
- **Rendering:** 16-color clients get xterm colors downgraded to the nearest base color.
  Clients without color get the tags stripped. Output that leaves a color on ends with a
  reset.
- **Color depth:** MTTS bits decide (8 = 256 colors, 1 = ANSI). Without MTTS, terminal
  types decide: 256 for `*256COLOR*`, `*TRUECOLOR*`, Mudlet and TinTin; 16 for ANSI, XTERM,
  VT100 and the like. Otherwise there is no color.
- **Engine text:** engine messages don't use color yet.
*Code:* `Ansi`, `TelnetConnection.depth`.

### D-088 GA only for real telnet clients — `open`
Negotiation is always offered when a client connects. GA is only sent to clients that have
sent at least one telnet command, so plain `nc` sessions don't show a GA byte after every
prompt.
*Code:* `TelnetConnection.speaksTelnet`.

### D-089 Telnet details — `open`
- **Opening:** the server sends DO TTYPE, DO NAWS, WILL EOR, WILL GMCP and WILL CHARSET.
  Requests for anything else are refused once; nothing is ever re-sent for an option whose
  state didn't change.
- **TTYPE and MTTS:** TTYPE is asked up to three times, or until an answer repeats.
- **CHARSET:** the server requests `UTF-8` and accepts a client's request only if it lists
  UTF-8. Output is UTF-8 regardless.
- **Other options:** GMCP is negotiated, but messages are ignored until M6. NAWS is stored.
- **Lines:** a line ends at CR, LF, CR LF or CR NUL. Backspace edits the line; other control
  characters are dropped, and invalid UTF-8 becomes U+FFFD.
- **Long lines:** past 512 bytes the rest is dropped, and the client is told "Your line was
  longer than 512 bytes and was cut short."
- **Disconnects:** five malformed sequences disconnect the client. Unsent output over
  256 KB disconnects it as too slow, and the character goes link-dead.
*Code:* `TelnetConnection`, `TelnetServer`.

### D-090 Raw sockets with `ktor-network` — `rejected`
Superseded by Q1 of the M6 design: every listener runs on Netty (D-148). Until M6.3 the
listener used `ktor-network` 3.6.0, which has no server-side TLS. Session ids still come
from one `AtomicLong` shared by listeners.
*Code:* `TelnetServer`, `gradle/libs.versions.toml`.

### D-091 Roles and builder tools — `open`
- **Roles:** `Role` lives in `core`, and players have a `role`.
- **Gating:** a command can require a role. To players without it, the command doesn't
  exist: they get "Huh?", and `help` leaves it out.
- **Registration:** `@` commands from outside `core` register as tools. An `@` prefix only
  matches other `@` commands.
- **Scope:** builders see and act on their own packages only (D-143). Instructors and
  admins see everything, as does `--dev`'s local instructor.
- **`@trace` filters:** `@trace [all|zone [package]|entity <target>|hook <name>|rules|off]`.
  - `zone` means the given package, or else the package of the caller's room.
  - `entity here` traces the room itself.
  - `hook` matches a hook name, or a command or timer prefix such as `command` or `timer`.
  - `rules` streams rules functions, modifiers and each evaluation's chain (D-127).
- **`@trace` rate limit:** past 50 lines per pulse, the rest are counted. The count is
  reported with the next traced line or at `@trace off`.
- **`@errors [package]`:** shows the last 10 diagnostics.
- The other builder commands are M6.2's (D-143).
*Code:* `CommandDef.role`, `CommandRegistry.registerTool`, `DevTools`, `Tools.errors`.

### D-092 `gups serve` options — `open`
`--prod` exits with status 3 until M6. `--fresh` and `--db` work from M4 (D-099, D-101).
Two options are new: `--port` (default 4000) and `--start` (D-082).
*Code:* `Serve`.

## Persistence (§3.4, §3.6, §5.11, §6.4) — M4

### D-093 Persistence seams — `open`
- **`Persistence`:** §4.11's `CheckpointSink` becomes a `core` interface with four parts:
  `checkpoint(world, cause, immediate)`, `loadCharacter`, `diagnostic` and `maintenance`.
  Capture (serialization) happens inside `persist`, on the world thread.
- **`Boot`:** a saved world starts from a `Boot`: the saved pulse, the id counters, the
  saved room ids, and no fresh spawns. A small restore API rebuilds entities with their
  saved ids: `restorePlayer`, `restoreMob`, `restoreItem`, `bindSpawn`, `setRespawn` and
  `setZoneReset`.
- **Script host:** `ScriptHost` gains `migrate`, `quarantinedPackages` and `quarantine`.
- **Immediate checkpoints:** `World.requestImmediate(cause)` asks for one. It is submitted
  at the end of the current cascade: after each input line, after each actor's action in a
  tier, after the timer phase, and after the action phase.
*Code:* `world/Persistence.kt`, `world/Restore.kt`, `Boot`.

### D-094 `migrate()` — `open`
- **Where:** a package defines at most one top-level `migrate(old_version, attrs)`, in any
  of its files.
- **When:** it runs for each instance whose saved prototype version is below the current
  one. That covers mobs, items and rooms restored at startup, items restored with a
  character at login, and live instances after a reload.
- **Arguments:** `attrs` is a dict of the package's own attributes by short name. The dict
  it returns replaces them; other packages' attributes are untouched.
- **Failures:** an error, or a return value that isn't a dict of storable values, leaves
  the attributes as they were and logs a diagnostic. The version still moves up.
*Code:* `StarlarkHost.migrate`, `World.upgrade`.

### D-095 Characters load at login, from an in-memory mirror — `open`
§5.11 startup says "load `players` and `world_entities`", but §5.12 places a character only
when it logs in.
- **Startup:** only world state is restored at startup. A player's row, and every item
  whose location chain ends in that player, are restored when the player logs in.
- **The mirror:** `SqlitePersistence` keeps the last captured row of every entity and
  player in memory. Logins and repro bundles read the mirror, so they never wait on the
  database or miss a batch that isn't committed yet.
- **Logout and creation:** on logout the character and everything it carries are captured
  in an immediate batch, then removed from the world; their rows stay. Creating a
  character also triggers an immediate checkpoint.
*Code:* `SqlitePersistence.loadCharacter`, `World.quit`, `DevLogin`.

### D-096 The checkpoint writer — `open`
- **Thread:** it is a dedicated thread, not a coroutine on `Dispatchers.IO`. It is still the
  only writer, it commits in FIFO order with one transaction per batch, and the world never
  waits for it.
- **Merging:** a batch merges into the last queued one unless that one is being written
  right now. Immediate captures reach the writer already folded into a periodic batch
  (§5.11).
- **Failures:** a failed write stays at the head of the queue. It is retried with backoff
  from 50 ms doubling up to 5 s, and later periodic captures may merge into it.
- **Health:** a failure makes the writer unhealthy until a write succeeds. While unhealthy,
  `maintenance` refuses new logins.
- **Alerts:** if a submitted batch is still uncommitted three world ticks later, one
  `checkpoint` diagnostic is logged until the backlog clears.
- **SQLite settings:** WAL with `synchronous = NORMAL`, which survives process kills
  (tested) but may lose the last commits on power loss. `busy_timeout` is 5 s.
*Code:* `CheckpointWriter`, `Database.open`.

### D-097 Backups — `open`
- **Naming:** snapshots are `<kind>-<UTC yyyyMMdd-HHmmss>.db` in `backups/` next to the
  database.
- **Kinds:** `hourly` (keep 48), `daily` (keep 14), `manual` (`gups backup`, never pruned)
  and `predeploy` (for M6).
- **Schedule:** the server checks every 5 minutes whether the newest snapshot of a kind is
  older than its period, and also once at startup.
- **Restore:** `gups restore FILE` replaces the database with SQLite's restore API. The
  server must be stopped first; nothing enforces it.
*Code:* `Backups`, `Backup`, `Restore`.

### D-098 Repro bundles and `gups repro` — `open`
- **Contents:** a bundle holds the seed, the failure pulse, the pulse of the last periodic
  checkpoint, the hook and message, and the failing room's rows as of that checkpoint: the
  room, the mobs and players there, and every item in it or held by them, transitively.
  It also holds the tick-lane commands and `say`s since that checkpoint, by actors in rooms
  of the failing package.
- **Replay world:** `gups repro <id|bundle.json>` builds a headless world from today's
  packages with the bundle's seed. It starts at the checkpoint pulse, with ids above the
  bundle's and no spawns, and doesn't fire `on_load`. Then it restores the bundle and
  replays each input by actor id at its pulse.
- **Verdict:** the run goes 16 pulses past the failure. It counts as reproduced when a
  diagnostic with the same hook and message appears. `--save FILE` exports the bundle.
  Exit status is 1 when not reproduced.
- **Players:** players come back without a connection, so output to them is dropped.
*Code:* `SqlitePersistence.bundle`, `ReproRunner`, `restoreBundle`, `Repro`.

### D-099 Where the database lives — `open`
`--db` defaults to `<world>/.gups/world.db`. The hidden directory is skipped by the package
scan and the file watcher. `gups backup`, `restore` and `repro` take the same `--world` and
`--db`.
*Code:* `GupsServer.defaultDb`.

### D-100 Row formats — `open`
- **References:**
  - An item's location names a room by label (with a track index) and an actor or
    container by id.
  - Rooms keep their saved ids across restarts (`Boot.roomIds`), so ids stay unique, but
    nothing refers to a room by id.
- **Deltas:**
  - Rooms store door states and exit blocks that differ from the prototype, plus
    attributes and timers.
  - Mobs always store position, vitals, xp, home room and spawn binding. Level, maxima,
    armor, stats, resists, skills, `think_every` and `dormant_recovery` are stored only
    when they differ from the prototype, each as a whole value.
  - Items store count, decay, name override, container state, attributes, timers and
    their prototype name.
- **Players:** they store everything, role included.
- **JSON:** field names are camelCase except `format_version` (§3.6).
- **`item_events`:** `from_json` and `to_json` hold location JSON. The cause is `spawn`,
  `move` or `destroy`.
- **`meta`:** it also stores `next_handle`, the timer and affect id counter.
*Code:* `Codec.kt`, `Database.kt`.

### D-101 Restore details — `open`
- **Rooms:** room rows whose room is gone (or got a different id) are deleted.
- **Items:** they are placed holders first, containers before contents. Rows that can't
  be placed are dropped.
- **Deleted prototypes:**
  - Mobs and ground items are dropped.
  - Items in a player's custody become tombstones when that player logs in. A tombstone
    keeps the payload it had when its prototype vanished.
- **Players:** a player whose room is gone is placed in their recall room, or else the
  first room.
- **Spawns:** saved spawn bindings and respawn deadlines are restored. A rule that is short
  with no respawn pending (for example a new rule) fills at once.
- **Quarantine:** it is restored from `zone_states`.
- **`--fresh`:** it deletes every saved row that isn't a player's or a player-owned item,
  and rewrites zone state. It keeps the pulse and the id counters, so saved characters'
  ids and deadlines stay valid.
*Code:* `SqlitePersistence.restore`, `placeItems`.

### D-102 Invariants before every capture — `open`
The world tick always runs the §3.8 check before its checkpoint. Entities named in a
violation are left out of that capture and stay dirty. Worlds that don't check every pulse
(`--prod`, M6) also log each violation then; `--dev` already logs them every pulse
(D-084).
*Code:* `World.runWorldTick`, `World.invariantViolations`.

### D-103 The crash test — `open`
A child JVM shuffles 20 gems between four players (who log in and out), two bags and the
ground. It checkpoints every few pulses, immediate and periodic mixed, and is killed with
SIGKILL at a random moment. Each restart must open cleanly, hold exactly 20 gems once all
players log in, and pass §3.8. It calls `checkpoint` directly, far more often than a server would, so kills land mid-write.
`./gradlew check` runs 20 iterations;
`-Dgups.crashIterations=300` runs the full "hundreds" of §7.1. This also covers the
checkpoint/kill/restore property test.
*Code:* `CrashTest`.

### D-104 Immediate checkpoint triggers in M4 — `open`
- **Implemented:** an item moving from one player's custody to another's, a player
  levelling up, character creation, and logout or quit. Each captures at once; the write
  waits for the next periodic checkpoint (§5.11).
- **Not yet:** shops and banks don't exist yet.
- **Audit log:** custody changes involving a player go to `item_events`.
*Code:* `World.moveItem`, `World.awardXp`, `DevLogin`, `World.quit`.

### D-107 The `MODULE.gups` format — `open`
§2.3 says `MODULE.gups` pins the engine version and `api_version` and holds world settings
such as `checkpoint_minutes`, but gives no syntax.
- **Syntax:** it is Starlark with exactly one `module(engine, api_version,
  checkpoint_minutes)` call, all optional. A world without the file gets the defaults
  (`checkpoint_minutes` 30).
- **Pins:** a pinned `api_version` this engine doesn't support stops the server. A different
  pinned `engine` version only warns.
- **Changes:** changing the file needs a restart (§5.10). The file watcher and reload never
  read it.
*Code:* `WorldModule`, `GupsServer.boot`.

## Rules, simulation and content (§4.2–§4.7, §5.7–§5.9) — M5

### D-108 The rules module and its fallback — `open`
- **Where:** the rules functions live in `//rules:combat.star`; `t.world(rules = "//house")`
  uses `//house:combat.star` instead. The harness always loads the rules package (§4.12);
  `serve` and `gups repro` use `//rules`.
- **Missing exports:** a function the file doesn't define uses the built-in Kotlin rule,
  silently. Instructors can override one formula at a time.
- **Failures:** an error, a step-limit hit or an unusable return value falls back to the
  Kotlin rule for that call. It logs a script diagnostic and a `rules` diagnostic, and
  alerts instructors, at most once per function per 240 pulses (a minute).
- **Load failure:** if the rules package fails to load, everything falls back, with one alert.
- **Not exports:** the track-movement lags of D-026 stay Kotlin-only.
- **Template:** `template-world/rules/combat.star` reproduces the built-in formulas (D-045)
  exactly; a test checks that they agree.
*Code:* `StarlarkRules`, `template-world/rules`.

### D-109 What a rules function gets — `open`
- **`ctx`:** a read-only rules ctx with `actor`, `attacker` (the same actor), `defender` and
  `target` (the other actor, if any: the defender, the pinner, the killed or the killer),
  `weapon`, `item`, `band` (a word), `skill`, `room` (the actor's), `pulse`, `rng`,
  `roll(dice)`, and `deny(msg)`, valid only in `cast_allowed` and its modifiers. It has no
  verbs.
- **Signatures:** `mitigate(ctx, amount, type)` gets the damage type as a string,
  `get_lag(ctx, band)` the band as a word, and `interrupt_check(ctx, amount)` the spell being
  cast as `ctx.skill`. `equipment_slots()` and `xp_to_level(level)` get no ctx.
- **Results:**
  - `regen` returns a dict with `hp`, `mana` and `move` (missing keys are 0).
  - `on_player_death` returns `{"respawn": label, "xp_loss": int}`, with relative labels
    resolved against the rules package.
  - `get_lag` returns None for out of reach.
  - `equipment_slots` returns lowercase slot names.
*Code:* `RulesCtx`, `StarlarkRules`.

### D-110 Rules step budget — `open`
§6.1 gives the rules module and modifier chain 200,000 steps and 20 ms "per evaluation".
Each rules function call and each modifier counts as one evaluation. It gets a fresh budget
even when a hook triggered it (for example `ctx.damage` asking `interrupt_check`), and its
steps aren't charged to the hook's cascade. Failures are charged to the package of the
function's file: the rules package, or the zone, library or skill package a modifier lives
in. They count toward that package's quarantine.
*Code:* `StarlarkHost.call(budget)`, `ScriptLimits.rulesSteps`.

### D-111 The modifier chain — `open`
- **Whose chain:** the attacker's for `to_hit` and `damage`, the caster's for `cast_allowed`,
  the mover's for `move_cost` (from the room being left), and the regenerating actor's for
  `regen` and `initiative`. "Zone" means the package of that actor's room, and the regions and
  room are those of that room too.
- **Declaring:** every modifier is a top-level function `fn(ctx, base)`; lambdas don't parse
  (D-060).
  - Package modifiers can't come from another file, because `package.star` can't load
    anything. Their functions are defined in `package.star` itself, above the `package()`
    call.
  - `item(modifiers=)` applies while the item is worn.
  - `apply_affect(..., modifiers=)` (the primitive and the verb) gives an affect modifiers.
    They are stored as function references, so they persist.
- **Evaluation:**
  - A `cast_allowed` chain stops at the first denial.
  - A failing modifier, or one that returns the wrong type, leaves the value as it was.
  - Spell `damage(...)` effects don't go through the `damage` point; only auto-attacks do.
    `mitigate` is not a modifier point.
- **Order:** affects are sorted by id; worn items follow `equipment_slots()` order, then any
  other slots by name.
*Code:* `World.modifierChain`, `Rules.kt` (core and script).

### D-112 Regions — `open`
`region(name, modifiers = {...})` declares a region's modifiers. Region names are world-wide:
one package declares each, and two packages declaring the same name both fail to load. A room
may carry a region nobody declares; it is then only a tag. Region names are lowercase words.
*Code:* `DeclarationBuiltins.region`, `ScriptLoader.checkRegions`, `Prototypes.regions`.

### D-113 Equipment — `open`
- **`equip_affects`:** a list of affect flags (for example `no_regen` or `silenced`) the
  wearer has while the item is worn. An item with `equip_affects` or `modifiers` needs a
  `wear` slot.
- **Slots:** they come from `rules.equipment_slots()`, asked once and again after each
  reload.
  - A slot family such as `wear = "finger"` fits the first free `finger_*` slot, else swaps
    out the first.
  - Wearing into an occupied slot moves the old item back to the inventory.
  - `wield` only takes items whose slot is `wield`; `wear` takes any slotted item.
*Code:* `EngineCommands.planWear`, `World.slotFor`, `Actor.hasFlag`.

### D-114 Item commands — `open`
- **`give <item> [to] <actor>`:** gives the whole stack. It fails if the receiver can't carry
  it. It fires `on_give` with the item as primary owner, then the room and the others in it
  (so the receiver), with `ctx.target` the receiver. There is no `before_give` in the catalog.
- **`put <item> in|into <container>`:** the container is carried, or within `get_lag` reach on
  the ground, and open. `capacity` counts items; `max_weight` weighs the contents.
- **Containers:** `open`, `close`, `lock` and `unlock` take a container when the word isn't a
  door direction here. Locking needs the container's key in the inventory.
- **`use <item> [target]`:** the item is carried or worn and must have an `on_use` hook
  ("You can't use that." otherwise). `before_use` can veto. The target is an actor in the room,
  else an item. The engine never uses an item up; `on_use` destroys it if it should be.
- **Stacks:** `get`, `drop`, `give` and `put` merge a stackable item into a stack of the same
  prototype in the same place with identical attributes and decay deadline, and no timers or
  name override on either (§3.8 inv. 8). Verbs don't merge.
*Code:* `EngineCommands`, `World.stack`.

### D-115 Encumbrance — `open`
`rules.encumbrance` applies while the carried and worn weight exceeds `rules.carry_limit`.
- **When:** it is recomputed when an actor's custody changes (spawn, move, destroy) and at
  every world tick, since stats can change the limit.
- **Limit:** it is computed without the current encumbrance.
- **Messages:** the actor is told when it starts and when it lifts.
*Code:* `World.updateEncumbrance`, `Actor.encumbrance`.

### D-116 The bubble is a world option — `open`
`WorldConfig(bubble = true)` keeps a reality bubble.
- **Where it's on:** `gups serve` turns it on. Kotlin tests, the harness and `gups repro`
  default to off, where every room is always simulated. That matches §4.12's example of a mob
  fleeing with no player near. `t.world(bubble = True)` turns it on in a test.
- **Who is simulated:** players, and entities with an active or anchor reason, are always
  simulated. Anything else is simulated when its room (following carriers and containers) is
  active.
*Code:* `WorldConfig.bubble`, `World.isSimulated`.

### D-117 Going dormant — `open`
- **Mobs:** besides disengaging, they drop their action queue and cast; recovery
  (`waitState`) is kept.
- **Timers:** a paused timer keeps at least 1 pulse to run.
- **Exceptions:** entities with an active or anchor reason keep running.
- **Targeting:** dormant actors are inert to simulated ones. They can't be found by keyword,
  aren't opponents, aren't in `enemies_near`/`allies_near`, and are skipped by `room`-range
  skills.
- **Order:** rooms that sleep are processed before rooms that wake, each ascending id.
*Code:* `World.sleep`, `World.maintainBubble`.

### D-118 Waking up — `open`
- **Order:**
  1. Timers resume.
  2. Mobs recover.
  3. Affects that ran out are removed (`on_expire` by expiry, then id).
  4. A missed zone reset is applied to the room (its spawns, then `on_reset`).
  5. Respawns that are due fill.
  6. Items that decayed go.
  7. `on_wake(ctx, elapsed_pulses)` fires on the room, then its mobs, then its items (each
     ascending id), as they were before the respawns.
- **`full` recovery:** with a `no_regen` affect still running at wake, `full` falls back to
  the piecewise catch-up, which then restores nothing while the affect lasts.
- **Catch-up ticks:** the world ticks counted are those after the room went dormant, up to and
  including the waking pulse. An affect counts as active at a tick when its `expiresAt` is
  later than the tick, matching live play, where expiry (phase 1) comes before regen (phase 4).
*Code:* `World.wake`, `World.catchUpRegen`.

### D-119 Bubble state at startup and across restarts — `open`
- **Startup:** rooms start dormant from the boot pulse. `fireOnLoad` (D-062) ends by pausing
  any restored timer that is running in a dormant room, then forms the bubble.
- **Persistence:** active and anchor reasons are saved with every entity and player, so an
  `active` boss stays active across a restart. Dormancy itself isn't saved.
- **Upkeep:** anchors are found by a scan of the world each pulse. Coverage is updated
  incrementally from each anchor's old and new room. A reload recomputes it in full.
*Code:* `World.settleBubble`, `World.recomputeBubble`, `Codec.kt`.

### D-120 Perception details — `open`
- **Own room:** a mob perceives events in its own room whatever its senses, with no
  `via_exit`. Senses of 1 add the rooms its own exits lead to.
  - `hearing` covers `sound` and `noise`; `sight` covers `sight`.
  - The exit checked is the listener's exit toward the source, so a one-way exit into the
    source room carries events and one out of it doesn't.
- **Events and `ctx.actor`:**
  - `say`, `emote` and `shout` carry their text; `ctx.actor` is the speaker.
  - `combat_start`: the attacker.
  - `combat` (once per room per round): no actor.
  - `death`: the one who died.
  - `enter` and `exit`, including teleports: the mover.
- **Delivery:** only mobs with an `on_nearby` hook get events. They are coalesced per (mob,
  event, source room), keeping the first. Events raised while phase 5 delivers wait for the
  next pulse's phase 5.
- **Players:** `shout` also reaches players next door over the same exits (D-018).
*Code:* `Perception.kt`.

### D-121 Built-in behaviors — `open`
- **Priorities:** flee 100, aggressive 60, assist 50, pursue 40, follow 30, wander 10,
  scavenge 5.
- **Whom they mean:** `players`, `mobs`, `all`, or label patterns with `*`. A pattern without
  a package (`goblin_*`) matches the label name in any package; `:goblin_*` means this package.
  A string or a list.
- **Each behavior:**
  - `wander(radius = 3, chance = 0.1)`: `chance` is the probability per think. The radius is
    counted from the home room through open doors.
  - `aggressive(toward = "players", unless_level_above = None)` attacks the lowest-id match.
  - `assist(allies)` attacks the ally's primary target, or else its lowest-id opponent.
  - `flee(at_hp_pct = 20)` fires at or below the threshold, engaged or not, as §4.12's
    example needs.
  - `guard(exit, allies = "mobs")` says "<Guard> blocks your way." It runs before the scripted
    `before_exit` observers and only while the guard is simulated and able to act.
  - `follow(target)` takes the first match it sees as leader and follows it within 3 exits;
    farther, it forgets the leader.
  - `pursue(target)` heads for the nearest simulated match by path length, then lowest id.
  - `sentinel()` removes movement and flee proposals.
  - `scavenge()` takes the first reachable, carriable ground item that isn't a corpse.
- **Engagement:** behaviors other than flee propose nothing while engaged.
- **Failures:** a behavior that throws is logged as an engine diagnostic and proposes
  nothing.
*Code:* `Behaviors.kt`, `DeclarationBuiltins`.

### D-122 Proposals carry their action — `open`
§4.7's `Proposal(action, priority, source)` is `Proposal(commandLine, priority, source,
action)`. The action fixes the target the behavior chose. `commandLine` describes it and is
what `ctx.proposals[i].command` shows.
*Code:* `Proposal`, `World.think`.

### D-123 Item-target skills — `open`
A `target = "item"` skill finds its item by keyword among worn, carried and ground items
(ground items within `get_lag` reach). Effect primitives act only on actors, so an item skill
does its work in `resolve`. There, and in the cast hooks, the item is `ctx.target`.
`SkillResolver` gained the item argument.
*Code:* `CastAction`, `SkillResolver`.

### D-124 Path cache — `open`
`path_to` results are cached per (from, to). A door change (`setDoor`, so every door verb
and command) and a reload clear the cache. `set_exit_blocks` doesn't, since blocks never
affect passability (D-029).
*Code:* `World.pathTo`, `World.graphChanged`.

### D-125 Script API additions in M5 — `open`
- **`struct(**fields)`:** a builtin that makes an immutable record. Libraries use it to export
  a namespace of functions (`quest.start`). Core Starlark has none.
- **Views:**
  - Item views gained `dice`, `damage_type`, `range`, `wear` and `stat_deltas`.
  - Actor views gained `recall_room`, `flags`, `has_flag(flag)`, `carried_weight` and
    `casting`.
  - Skill views gained `cost`, `channels` and `interrupt`.
- **ctx:**
  - `ctx.target` is the target item for `use` and item skills.
  - `ctx.apply_affect` takes `modifiers`.
*Code:* `Views.kt`, `Ctx.kt`, `DeclarationBuiltins.struct`.

### D-126 `//lib:quest.star` — `open`
- **Where:** like the tutorial (D-077), `lib/` and `rules/` live in `template-world/`.
- **API:** `quest.define(id, title, steps)` returns a quest description. `start`,
  `advance(..., from_step=None)`, `complete`, `step`, `is_active` and `is_done` take
  `(ctx, actor, quest)`.
- **Storage:** progress is a `quests` dict in the lib's own `lib` namespace on the player, so
  only the library writes it (§3.7).
- **Command:** `quests` is a fast-lane global command that lists active and finished quests.
*Code:* `template-world/lib/quest.star`.

### D-127 `@trace rules` — `open`
It streams each rules function and modifier call, and one line per evaluation with its full
chain. Auto-attacks add the to-hit roll and the mitigated damage; each combat round adds
the initiative order. `w.trace()` in the harness records the same lines. Builders see only
their own character's rules lines (D-091).
*Code:* `World.onRules`, `DevTools`, `WorldHandle.trace`.

## Accounts and login (§3.5, §3.6, §5.12; M6 design §2–§3) — M6.1

### D-128 Account storage and its writes — `open`
- **Seam:** `AccountStore` lives in `core` (`Accounts.kt`) with `Account` and `Invite`.
  `Persistence.accounts` exposes it; `SqliteAccountStore` in `persist` implements it, and
  `auth` uses only the interface.
- **Writes:** each change is a `Batch` of its own carrying `AccountOp`s, submitted to the
  checkpoint writer as an immediate batch so it never merges with world captures. The caller
  blocks (off the world thread) until it commits, for at most 30 s. Account batches don't
  count toward the checkpoint-backlog alert.
- **Conflicts:** a taken login (`INSERT OR IGNORE`) and a used invite (`UPDATE … WHERE used_by
  IS NULL`) are reported as results, never thrown, so they can't wedge the writer's retry
  loop. That also makes single use hold across processes.
- **Reads:** a read-only connection of the store's own, serialized, beside the writer (WAL).
  Accounts aren't cached, so the CLI tools writing the same database directly are safe.
- **Schema migrations:** 2 adds `invites.account_id`, the account a setup code is bound to,
  so M6.2's `restrictions` table is migration 3 (`id`, `account_id`, `capability`, `until`,
  `reason`, `set_by`, `created_at`, `lifted_by`, `lifted_at`).
- **Characters:** `Persistence.characters(accountId)` and `savedCharacter(name)` read the
  in-memory player rows (D-095). `Player.accountId` became a `var` and `spawnPlayer` takes it.
- **`update`** writes role, scope, GitHub handle and the disabled flag, never the password;
  `setPassword` also deletes the account's `session_tokens`.
*Code:* `Accounts.kt` (core, persist), `AccountOp`, `Database.writeAccounts`, `SqlitePersistence`.

### D-129 Slow login work and the `LoginHandler` seam — `open`
- **Outcome:** `WorldControlEvent.AuthResult(session, outcome: Any)` carries the handler's own
  continuation; the world passes it to `LoginHandler.completed`, then flushes the session. A
  result for a session that has closed is dropped.
- **New hooks:** `LoginHandler.disconnected` (called by `disconnect` and `closeSession`) drops
  per-session state; `LoginHandler.pulse` runs each pulse, before the idle checks, for prompt
  timeouts.
- **Held input:** lines typed while work is pending are held (at most 20) and replayed in
  order when it finishes: to the login, or as game commands once a character is playing.
- **Executor:** `--prod` uses `Dispatchers.IO.limitedParallelism(4)`. Everything that touches
  the store runs there, not only hashing: login lookups, code checks and login-taken checks.
- **Helpers:** `World.takeOver` and `World.enterGame` are shared by `DevLogin` and
  `AccountLogin`; `World.flush` became public.
*Code:* `Seams.kt`, `Login.kt`, `Sessions.kt`, `AccountLogin.later`.

### D-130 Login flow details — `open`
- **Prompts:** the login prompt is the M6 design's text. The character menu prompt is `> `.
  `quit` at the menu closes the connection. An empty answer at a prompt asks again; at the
  character-name prompt it goes back to the menu.
- **Accounts without a password** (roster accounts not set up yet) fail like a wrong password,
  after a dummy hash, so they look like any other wrong login.
- **Strikes:** the three per connection count wrong passwords, refused locked-out attempts,
  bad codes and a wrong current password when changing it.
- **Password change:** current, new, new again, then one background step that verifies and
  rehashes. A wrong current password counts as a failed login for the limiter.
- **Disabled accounts** get "This account is disabled." after a correct password.
- **`new`:** a bound setup code shows which login it sets up and skips the login prompt.
  Logins are lowercased. A redeem that loses a race reports it and goes back a step.
- **Characters:** the menu lists saved characters plus any of the account's characters in the
  world not saved yet. Loading a saved character respects maintenance mode, as D-082 does.
  The role and idle exemption come from the account each time a character is entered.
*Code:* `AccountLogin`.

### D-131 The `--dev` instructor account — `open`
With a database, `--dev` creates the account `instructor` (role instructor, no password) on
first boot. Its characters get its id, and dev characters saved before it existed join it.
`--dev` doesn't apply one-character-at-a-time (Q9): it is a single-user tool where several
characters at once help testing. Without a database (tests) characters have no account.
*Code:* `GupsServer.boot`, `DevLogin`.

### D-132 Lockout counting — `open`
Failures are counted per lowercased login (whether or not it exists) and per peer address.
The count resets after 15 minutes without a failure, so failures spaced less than 15 minutes
apart keep accumulating. The fifth starts a 1-minute lockout and each later one restarts the
lockout at double length, capped at 15 minutes. A successful login clears the account's
count but not the address's. Bad invite codes count against the address only.
*Code:* `LoginLimiter`.

### D-133 Invite and setup codes — `open`
The alphabet is `23456789ABCDEFGHJKMNPQRSTWXYZ` (no 0/O, 1/I/L, U/V). Codes are compared
after uppercasing and removing spaces and dashes, and stored as the SHA-256 hex of that
canonical form. They are random enough that an unsalted fast hash is enough.
*Code:* `Codes`.

### D-134 Roster import — `open`
- **CSV:** a header row in any column order; blank lines and `#` lines skipped; double quotes
  allowed; a leading `@` on a GitHub handle dropped; roles case-insensitive. Any bad row
  (unknown column or role, bad or repeated login, too many fields) fails the whole import
  with every problem listed, and nothing is written.
- **Output:** a `login,setup_code` header, then one line per new account; a summary goes to
  standard error. `--days N` sets the codes' lifetime.
- **Existing accounts** without a password get no new code, so a re-import stays idempotent;
  `@role <account> reset` issues a fresh one.
- **Offline only:** `gups import-roster` and `gups invite` write the database directly. With
  the admin port (M6.5) they will go through a running server instead; until then, running
  them beside a server is safe because SQLite serializes the writes (D-128).
*Code:* `Roster`, `AccountService.importRoster`, `ImportRoster`, `Invite`.

### D-135 `--prod` and the server configuration in M6.1 — `open`
- **Listener:** `--prod` needs at least one listener (D-152). `:4000` binds every interface.
- **Flags:** `--config`, `--telnet`, `--open-signup`/`--no-open-signup`, `--max-characters`,
  plus the existing `--world`, `--db`, `--start` and `--fresh`, each overriding the file.
  `--port` stays `--dev`-only. The settings of later stages are read and checked but only
  warned about when set.
- **`reserved_names`** in the server configuration adds character names to refuse.
- **Database:** `--prod` defaults to the world's `.gups/world.db`, as `--dev` does (D-099).
- **Not yet:** nothing disables an account yet, so quitting a disabled account's online
  characters waits for a command that disables (DESIGN_NEEDED.md).
*Code:* `Serve.prodServer`, `ServerConfig`, `GupsServer`.

### D-136 Reserved character names — `open`
Built in: `self`, `me`, `all`, `someone`, `here`, the menu words `new`, `password` and `quit`,
`guest`, `instructor`, `admin`, and every standard direction with its abbreviation. Added per
world: the name and aliases of every engine, global and tool command (without `@`), and the
server configuration's `reserved_names`. Logins only reserve `new`.
*Code:* `Names`.

### D-137 Telnet echo for passwords — `open`
`OutputSink.hideInput` sends `IAC WILL ECHO` / `IAC WONT ECHO`, only to clients that have sent
some telnet command (D-088), and only when the state changes. `DO ECHO` is accepted silently
while input is hidden and refused once otherwise; `DONT ECHO` is never answered, so the
exchange can't loop. After each hidden line the server sends the newline the client didn't
echo.
*Code:* `TelnetConnection.encodeEcho`, `TelnetServer`.

### D-138 `start_room` in `MODULE.gups` — `open`
`module(start_room = "//zones/tutorial:gate")` sets where new characters start, in both
profiles. `--start` overrides it. Without either, it is the tutorial gate if it exists, else
the first room by label, as in D-082. A `start_room` that isn't a room stops the server.
*Code:* `WorldModule`, `GupsServer.boot`.

## Roles and in-game tools (§3.5, §4.9, §6.2; M6 design §4) — M6.2

### D-139 CODEOWNERS — `open`
- **File:** the first of `.github/CODEOWNERS`, `CODEOWNERS` and `docs/CODEOWNERS` in the
  world, GitHub's order. No file means no CODEOWNERS scope.
- **Patterns:** gitignore-style, matched against package directories. A leading `/` or a
  slash inside anchors to the world root; otherwise the name matches at any depth. `*` stays
  within a directory, `**` crosses them, and a directory pattern also owns the packages below
  it. File patterns such as `*.star` own no package. Inline `#` comments aren't supported, as
  on GitHub.
- **Owners:** `@user` handles, compared in lowercase with accounts' `github_handle`. Team and
  email owners are warnings, as are handles that match no account.
- **Sync:** at startup only until deploys (M6.5). Instructors and admins have both scopes
  cleared. Every other account, players included, gets its CODEOWNERS packages, so promoting a
  player with `@role` scopes them at once. Online characters get new scopes when they next
  enter the game.
*Code:* `CodeOwners`, `syncScopes`, `GupsServer.syncCodeOwners`.

### D-140 Hand scope — `open`
`@role <account> builder <packages>` sets a scope of its own, stored as `manual` in
`roles_json` beside the CODEOWNERS `scope`. A builder may work on the union. Packages
CODEOWNERS covers are refused there, and a sync drops any that CODEOWNERS later covers.
Packages are comma- or space-separated, with or without `//`, and must be loaded packages.
Giving a role without packages keeps the old hand scope. Instructors and admins have none.
*Code:* `Account.manualScope`, `RolesJson`, `AccountTools.setRole`.

### D-141 `@role` — `open`
- `@role <account>` shows the role, GitHub handle, both scopes and whether a password is set.
- Instructors may set player, builder and instructor; only admins may make, change or reset
  admins, or invite them. Nobody may change their own role.
- A change applies at once to the account's characters in the world: role, scope and idle
  exemption.
- `reset` clears the password, revokes web tokens and shows a 7-day setup code bound to the
  account. `revoke` deletes the account's web tokens only.
*Code:* `AccountTools`, `World.applyAccount`.

### D-142 Slow work for commands — `open`
`World.later(work, then)` runs `work` on `World.background` and `then` with its result on the
world thread, through a new `WorldControlEvent.Task` posted with `World.post`. A world
without them (headless tests) runs both at once. Account commands, `@snapshot` and `@audit`
use it. Their replies are flushed when they arrive. The server uses one 4-thread pool for
logins and these.
*Code:* `World.later`, `WorldControlEvent.Task`, `GupsServer`.

### D-143 Builder commands — `open`
- **Ownership:** a builder owns a package in its scope. Rooms belong to their package, mobs
  and items to their prototype's. Players and engine items (corpses) belong to the package
  of the room they are in. Every builder command is refused outside it with "That isn't one
  of your packages."; `@where` checks the room the builder stands in.
- **Labels:** `//pkg:name`, or `:name` for the package of the builder's room.
- **`@goto`:** a player's name wins over a label; you land at their track index. A room
  lands you at index 0.
- **`@stat`:** a keyword (actors first, then carried and ground items), `here` or `#id`.
  It shows identity and version, location, vitals, effective and base stats, resists,
  skills, affects with seconds left, what the actor is doing, carried and worn items,
  timers, attributes grouped by namespace, and simulation state with reasons. Mobs also show
  their debug owner; players their account, role and scope.
- **`@spawn`:** 1 to 10 at a time. Mobs appear at the builder's index; items go into the
  builder's inventory (a stackable item as one stack). Debug entities have no audit trail.
  A debug mob also despawns at the first world tick after its spawner is gone. Items in its
  inventory when it despawns or dies become debug too.
- **Debug on every entity:** `debug` moved from `Item` to `Entity`.
*Code:* `Tools`, `World.packageOf`, `World.statLines`.

### D-144 Instructor commands — `open`
- **`@reload`:** refused when `WorldConfig.production` is set (`--prod`). In `--dev` it
  reloads the named packages, which must be loaded.
- **`@deploy`:** answers that deploys arrive with M6.5's pipeline.
- **`@quarantine`/`@unquarantine`:** packages must be loaded. Each takes a routine
  checkpoint so the change persists.
- **`@bubble`:** active rooms per package, every anchor with its reasons and room, entities
  with active reasons in dormant rooms, and dormant-room counts per package.
- **`@stats`:** p50 and p99 of the last minute's pulse durations, its overruns (pulses longer
  than 250 ms), input lines waiting, active rooms, sessions per transport, the writer's
  backlog in batches, and the package with the most script steps this minute.
- **`@who`:** each player's role, transport and address (or link-dead time) and idle time.
- **`@snapshot`:** takes a routine checkpoint, waits for the writer, then a `manual` backup.
- **`@shutdown [seconds] [reason]`:** a first word that isn't a number is part of the reason.
  It warns at once, then at 60, 30, 10 and 5 seconds left. The existing `Shutdown` event
  uses the same countdown.
- **`@audit item <id>`:** the item's committed `item_events` with UTC wall times, plus where
  it is now if it is in the world. Changes not yet checkpointed don't show.
*Code:* `Tools`, `World.scheduleShutdown`, `SqlitePersistence.backup`, `itemHistory`.

### D-145 Alerts — `open`
- **Instructor alerts** go to online instructors and admins as `[alert] ...`, and always to
  the server log. Script host alerts (quarantine, rules fallback) are keyed by their text,
  so the same message repeats at most once a minute. The other kinds are `checkpoint` (the
  writer-backlog diagnostic of D-096), `engine_errors` and `overrun`.
- **Engine errors:** more than 100 `engine` diagnostics within the last 240 pulses.
- **Overruns:** the world loop records each pulse's duration, and a pulse overruns when it
  takes longer than 250 ms. The alert fires when a full minute is recorded and more than 10%
  of it overran. Script steps per package are sampled every combat round, and the package
  with the largest increase over the sampled minute is named. Steps of every script call
  count, rules functions included.
- **Owner alerts** go to online builders (not instructors) whose scope has the package:
  `[error] <file:line>: <first line of the message> (@errors <pkg>)`.
- Alerts are queued like other output and flushed with the pulse or the command.
*Code:* `World.alert`, `World.alertFor`, `World.checkOverruns`, `PulseStats`, `StarlarkHost.stepsByPackage`.

### D-146 Saving quarantine and debug entities — `open`
`zone_states` also gets a row for a quarantined package that isn't a zone (a library, the
rules), holding only its quarantine flag, and keeps updating rows it once had. Debug
entities are skipped by every checkpoint, so they are never saved and are gone after a
restart.
*Code:* `SqlitePersistence.zoneStates`, `restore`, `checkpoint`.

### D-147 Restrictions — `open`
- **Lengths:** `<n>m`, `<n>h`, `<n>d`, `<n>w` or `permanent`. A third word that isn't a
  length starts the reason.
- **Ladder:** every earlier restriction of the same capability on the account counts,
  whatever its state; past the end of the ladder its last step repeats. The server
  configuration's ladder is checked at startup.
- **Checking:** the world keeps every restriction in memory, loaded at startup and updated by
  the commands, and checks them against the wall clock (`World.wallClock`). The `gossip`
  capability guards the `gossip` command; `goto` guards `@goto` as a whole and
  `goto_player` going to a player.
- **Message:** "You can't do that right now.", plus "That changes <UTC time>." for a timed
  restriction. The restricted player isn't told when one is set.
- **`@restrictions`** without an account lists those in effect; with one, the full history:
  state, who set it and when, the reason, and who lifted it.
- **Set by:** the instructor's account, or none for `--dev` characters without one.
*Code:* `AccountTools`, `World.restriction`, `World.refuseRestricted`, `Capabilities`.

## TLS and GMCP (§4.10, §4.11; M6 design §2.4, §2.5, §5) — M6.3

### D-148 Netty listeners — `open`
- **Stack:** Netty 4.2.17 (the version Ktor 3.6 uses), NIO transport, one boss thread and
  Netty's default worker count per listener. `ktor-network` is gone.
- **Pipeline:** raw telnet and telnet over TLS build the same pipeline; TLS adds an
  `SslHandler` first. `TelnetConnection` is unchanged.
- **Sessions:** over TLS a session starts, and the world hears `Connect`, only once the
  handshake succeeds. Its transport is `tls`; raw telnet's stays `telnet`.
- **Output:** each sink call is written and flushed at once. Bytes not yet written count
  toward the 256 KiB limit until Netty finishes writing them.
- **Disconnect:** `Disconnect` is posted whenever the channel closes. After a world-side
  close it is a no-op.
*Code:* `TelnetServer`.

### D-149 GMCP seam and delivery — `open`
- **Seam:** `OutputSink.gmcp(pkg, data)`, plus `wantsGmcp(pkg)`, so the world computes only
  what the client asked for.
- **Supports:** a package is wanted when GMCP is on and `Core.Supports` listed it, or a
  module above it (`Char` covers `Char.Vitals`), compared case-insensitively. Versions in
  the entries are ignored. `Set` replaces the list and `Add`/`Remove` change it; `DONT GMCP`
  turns everything off. `Core.Hello`'s client and version are kept. A `Core.*` message with
  malformed JSON counts as a protocol error (§6.4); other packages are ignored.
- **Order:** GMCP waits in the session with its text and goes out after the text and before
  the prompt. A flush with only GMCP sends no prompt.
- **Deltas:** every phase-7 flush (`World.flushOutput`) recomputes the state packages
  (`Char.Vitals`, `Char.Status`, `Char.Affects`, `Room.Info`, `Room.Bands`) and sends those
  whose whole payload changed. The cache resets when a session takes a character, so a
  reconnect gets everything again. Events (`Comm.Channel.Text`, `Gups.Trace`) are never
  cached.
- **Encoding:** `net` turns the plain values into JSON with kotlinx.serialization and
  doubles IAC bytes in the payload.
*Code:* `Seams.kt`, `Gmcp.kt`, `World.flush`, `TelnetConnection.gmcpIn`, `encodeGmcp`.

### D-150 TLS certificates — `open`
- **Files:** a PEM certificate chain and a PEM PKCS#8 key (certbot's `fullchain.pem` and
  `privkey.pem`), loaded with Netty's JDK provider. TLS 1.3 and 1.2 only, with the JDK's
  default cipher suites.
- **Reload:** every hour the server compares both files' modification times. If either
  changed it rebuilds the context. A pair that fails to load is logged and the old context
  stays. Open connections keep their certificate.
- **`--self-signed`:** an EC P-256 certificate signed with SHA-256, valid for 30 days. Its
  subject alternative names are `localhost`, `127.0.0.1` and the bind host when it is a
  specific one. It is written to a fresh temporary directory, using Bouncy Castle's
  `bcpkix`.
*Code:* `ServerTls`, `Serve.serverTls`.

### D-151 GMCP payloads — `open`
- **`Char.Status`:** `wait` is the recovery left in pulses, and `xp_next` is the total XP for
  the next level (`rules.xp_to_level`). `casting.skill` is the skill's name, or `action` for a
  warmup that isn't a cast, with `progress` 0–100 through the warmup. `fighting` lists names.
- **`Char.Affects`:** `remaining` is whole seconds; `flags` are sorted.
- **`Room.Info`:** `exits` maps every exit's direction to its door state, or null when it has
  no door; `regions` are sorted.
- **`Room.Bands`:** actors (the viewer excluded) and ground items of the viewer's room, by
  band from the viewer's index: actors first, then items, each in ascending id.
- **`Comm.Channel.Text`:** `say` goes to everyone in the room, the speaker included. `tell`
  and `whisper` go to both ends. `shout` goes to the speaker's room and the rooms that hear
  it, and `gossip` to every player. `text` is what was said, without the framing.
- **`Gups.Trace`:** `{"line"}` replaces the `[trace]` line, the per-pulse overflow summary
  included.
*Code:* `World.gmcpPayload`, `World.channel`, `DevTools.line`.

### D-152 `--prod` listeners — `open`
`--prod` serves raw telnet only when asked (`--telnet` or `telnet = ...`) and telnet over TLS
with `--telnet-tls`/`telnet_tls`. At least one is required. TLS needs `--tls-cert` and
`--tls-key` (or `tls_cert` and `tls_key`), or `--self-signed`, and `--self-signed` needs a
TLS address. `GupsServer.ports` reports the bound port per transport. The startup message
lists both listeners. `--dev` stays raw telnet on 127.0.0.1.
*Code:* `Serve.prodServer`, `GupsServer`, `ProdOptions`, `TlsListener`.
