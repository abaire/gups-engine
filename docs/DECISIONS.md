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
`on_tick`, `on_combat_tick`, `on_spawn` and `on_tell` fire on their owner only, not
through observer dispatch. `on_tick` fires on rooms and actors but not items.
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
  ctx)` for timers and affect hooks. `loadPackages` is deferred to M2.
- `OutputSink` has `send` and `close(reason)`; `gmcp` is deferred to M6.
- `CheckpointSink` and `Persistence` are deferred to M4.
- `CapturingSink` lives in `core` main so the harness can reuse it.
*Code:* `Hooks.kt`, `Seams.kt`.

### D-052 Prototypes are Kotlin types in `core` — `open`
`RoomProto`, `MobProto`, `ItemProto` and `SkillProto` are immutable data classes in `core`.
Behaviors, `think`, zone commands and custom `resolve` are Kotlin function interfaces that
the script host will implement in M2.
*Code:* `model/Protos.kt`.

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
`Reload` and `Deploy` are accepted but only log a `control` diagnostic until M2 and M6.
*Code:* `World.handle(WorldControlEvent)`.
