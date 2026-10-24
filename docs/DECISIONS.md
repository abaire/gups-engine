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
Link-dead and idle auto-quit aren't a numbered phase. They run after the world tick (and
the not-yet-built phases 5 and 6), before output flush.
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

### D-010 Shutdown has no final checkpoint yet — `open`
`Shutdown(delayPulses, reason)` broadcasts the reason and stops the loop once the delay
has passed. The final checkpoint (§5.11) arrives with persistence in M4.
*Code:* `World.handle(WorldControlEvent)`, `WorldLoop.run`.

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
It is answered with "You are not playing a character." until login exists (M3).
*Code:* `World.handle(ClientInputEvent)`.

### D-016 `quit` is refused while engaged — `open`
"No way! You're fighting for your life!" The link-dead auto-quit ignores combat.
*Code:* `EngineCommands.quit`, `World.expireSessions`.

### D-017 Disconnecting clears the action queue — `open`
This matches "keeps fighting with auto-attacks only" in §5.12.
*Code:* `World.disconnect`.

### D-018 `tell`, `shout`, `whisper`, `gossip` — `open`
`tell` finds an in-world player by name first, otherwise a mob in the room by keyword, which
receives `on_tell`. `shout` reaches the room and every adjacent room regardless of exit
`blocks` until perception (M5). `whisper` shows bystanders "X whispers something to Y."
`gossip` goes to every player, including link-dead ones (who see nothing).
*Code:* `EngineCommands`.

### D-019 Commands not in M1 — `open`
`give`, `put`, `wear`, `use`, `quests` and the builder and instructor `@` commands are not
implemented. `wield` and `remove` handle only the `wield` slot.
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
- `item` targets are not supported yet.
- `room` range applies the effect to everyone in the room except the caster.

Skill proficiency is stored but not yet rolled against.
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
`on_tick`, `on_combat_tick`, `on_spawn`, `on_tell`, `on_load` and `on_reset` fire on their
owner only, not through observer dispatch. `on_tick` fires on every room, actor and item,
ascending id. (Until M2 it skipped items; with scripts, an item declaring `on_tick` that
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

### D-055 `AttrValue` has no serialization yet — `open`
JSON serialization arrives with persistence in M4.
*Code:* `AttrValue.kt`.

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
`Reload` and `Deploy` are accepted but only log a `control` diagnostic until M3 (reload)
and M6 (deploy).
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
`on_spawn` first, during construction. The harness calls it in `t.world()`; `serve` will in
M3. After reload (M3) and restore (M4) it will fire on reconciled instances.
*Code:* `World.fireOnLoad`, `TestCtx.world`.

### D-063 `path_to` is an uncached BFS — `open`
Breadth-first over exits in declaration order, through open doors only (closed and locked
doors block), so ties resolve the same way every time. Empty list if already there, None if
unreachable. The `(source, dest)` cache of §5.9 arrives with the bubble work in M5.
*Code:* `World.pathTo`.

### D-064 What loading a package loads — `open`
- Loading a package also loads every package it refers to by label (exits, spawns, keys,
  loot, skills, summons) or by `load()`, transitively. A referenced package that isn't on
  disk is a label error.
- A package fails if any of its files fails, if any label doesn't resolve to a declaration
  of the right kind, or if anything it depends on failed.
- `package.star` must call `package()` exactly once and holds nothing else; it can't be
  `load()`ed. Content files evaluate in alphabetical order. Only test files may load
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
- `@quarantine` and `@unquarantine` (M6) will call `StarlarkHost.quarantine` and
  `unquarantine`. Persisting the state in `zone_states` arrives with M4.
- Notifications go to `StarlarkHost.alerts` until roles exist (M6).
*Code:* `StarlarkHost`, `World.runTimers`.

### D-069 Script diagnostics — `open`
- **Kinds:** `script_error`, `step_limit`, `wall_clock`, `alloc_limit`, `missing` (a hook
  or timer function no longer exists) and `quarantine`. The kind `script` remains for
  failures of Kotlin-hosted handlers.
- **Fields:** each diagnostic carries the package, prototype label, hook, file label, line,
  Starlark stack, a one-line ctx summary and the seed.
- **Not yet:** the repro bundle needs checkpoints, so it and `gups repro` move to M4 (the
  CLI stub said M2). The in-game owner alert, at most one per package per 10 s, needs
  package ownership (M6).
- **Storage:** diagnostics live in `World.diagnostics` until the diagnostics table (M4).
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
- **Not yet supported:** these arguments are refused with "planned for Mx" when non-empty:
  `behaviors` (M5), `modifiers` (M5), `equip_affects` (M5) and `expected_tombstones` (M6).
  `region()` (M5) and `migrate()` (M4) are not available yet.
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
- **`t.world`:** `t.world(rules=...)` is refused until M5. `packages` defaults to the test's
  own package.
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
  - `event`, `source_room` and `via_exit` are None until perception (M5).
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
- **Simulation verbs:** `set_active` and friends only edit the reason sets until the bubble
  (M5).
*Code:* `Ctx`, `Views.kt`.

### D-074 Hook catalog enforcement — `open`
A hook kwarg must be a catalog name (a typo suggests the right one) and must be attachable
to that kind of entity per §4.4's "Attach to" column. The engine doesn't fire some hooks
yet:
- `on_wake` and `on_nearby` arrive with the bubble and perception (M5).
- `before_use`, `on_use` and `on_give` arrive with the `use` and `give` commands (M5).
*Code:* `HookCatalog`.

### D-075 `think` works before behaviors — `open`
§7.4 puts `think` in M5, but the core seam already existed. Mobs may declare
`think = fn` now. `ctx.proposals` is empty until behaviors arrive.
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
