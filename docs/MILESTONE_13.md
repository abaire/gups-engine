# Milestone 13: Senses, Perception, and Environmental Events

Perception stops being limited to hardcoded mob hearing/sight and actor-only events (`say`, `emote`, `shout`, `combat`,
`death`, `enter`, `exit`). Actors (both players and mobs) gain configurable, modifiable sensory faculties that can
perceive built-in and world-declared channels. Scripts can emit perceivable events from rooms, items, and spells, while
doors and containers emit sensory cues governed by the rules module.

This milestone resolves the two perception gaps in [DESIGN_NEEDED.md](DESIGN_NEEDED.md): - `### Events from rooms, doors
and items` - `### What players perceive from next door`

It builds on [M12](MILESTONE_12.md), which must be merged first. It bumps neither `api_version` nor `PluginApi.VERSION`:
the engine is unreleased, so the new exports and verbs join `api_version` 1 (§1.3).

Read [AGENTS.md](../AGENTS.md) before starting. The source of truth is [SPEC.md](SPEC.md); this file records what the
milestone built and where, and where the two differ the spec wins:

| Section                                 | What it says about this milestone                                                                                                                                                                |
|:----------------------------------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **§3.3 Entity Declarations**            | Actor `senses`, `door` `tags`, `item` and `apply_affect` `sense_deltas`                                                                                                                          |
| **§3.5 Entity Views**                   | `actor.senses`, `actor.base.senses`, `item.sense_deltas`, `affect.sense_deltas`, `exit.door` (`DoorView`)                                                                                        |
| **§3.9 World Schema**                   | Built-in channels (`sight`, `sound`) and world-declared channels                                                                                                                                 |
| **§4.4 Script Hooks**                   | Rename `on_nearby` $\to$ `on_perceive` (adding `channel`, `intensity`, `cost`); add `before_open`, `on_open`, `before_close`, `on_close`, `before_lock`, `on_lock`, `before_unlock`, `on_unlock` |
| **§4.6 Rules Module**                   | Multi-file `//rules` package support; new exports: `sense_cost`, `can_perceive`, `perceive_message`, `door_intensity`                                                                            |
| **§4.19 Character Creation and Traits** | `senseDeltas` on `PluginTrait` / `Trait`                                                                                                                                                         |
| **§5.7 Adjacent-Room Perception**       | Multi-hop shortest-path traversal, attenuation via `sense_cost`, listener checks via `can_perceive`, cross-room player output via `perceive_message`                                             |
| **§6.3 Script Failures**                | Neutral results for the new rules exports                                                                                                                                                        |
| **§7.4 Implementation Milestones**      | The `M13` milestone row                                                                                                                                                                          |

---

## Design (Settled)

These decisions were agreed upon with the maintainer and are settled:

1. **Multi-File Rules Package (`//rules`):**
   - `ScriptLoader` and `StarlarkRules` discover rules exports across **all** non-test `.star` files in the `//rules`
     package (e.g. `rules/combat.star`, `rules/perception.star`, `rules/manipulation.star`), using the same
     duplicate-definition check that `findMigrate` uses in `Loader.kt`.
   - If two `.star` files in `//rules` define the same `RulesExport` function name, package loading fails with a
     `LoadError` naming both files.
   - Single-file `//rules:combat.star` packages (such as synthetic test worlds in `TestWorlds.kt`) continue to work
     unchanged.
2. **Unified Sense & Channel Naming (`Player` and `Mob`):**
   - The legacy `enum class Sense { HEARING, SIGHT }` and the `"hearing"` sense key are removed. Sense keys are
     **channel names** (`Channel`): the built-in channels `"sight"` and `"sound"`, plus any custom channels declared in
     `MODULE.gups` (`world(channels = [...])`). There is no `noise` channel: combat, shouts and doors are `sound`.
   - Every actor (`Player` and `Mob`) has a baseline sense map where the built-in channels default to `1` (`Channel.
     BUILTIN_SENSES`, `{"sight": 1, "sound": 1}`) and custom world channels default to `0`.
   - When `mob(senses = {...})` or `starting_stats(ctx)` supplies a `senses` dict, its entries **merge over** that
     baseline (so `mob(senses = {"sound": 3})` results in `sight = 1, sound = 3`, while `mob(senses = {"sight": 0})`
     explicitly blinds the mob).
   - The old `PERCEPTION_MAX_RADIUS = 1` upper bound is gone; base sense values in `mob(senses=...)` and
     `starting_stats` must be integers $\ge 0$.
   - A sense score of `0` means **cannot sense at all on that channel** (even in the same room).
3. **Sense Modifiers (`sense_deltas`):**
   - Senses are modified for both players and mobs via `sense_deltas` (channel name to integer, negative allowed) on:
     - **Plugin Traits:** `PluginTrait.senseDeltas: Map<String, Int>` in `plugin-api`, validated against
       `schema.allChannels` in `PluginHost` and adapted to `Trait.senseDeltas` in `core`. (Traits remain plugin-provided
       only; there is no Starlark `trait(...)` declaration.)
     - **Items (Equipment):** `item(..., sense_deltas = {"sight": 1})`, stored on `ItemProto`, exposed on
       `ItemView.sense_deltas`, and checked in `ItemProto.isKeyOnly`.
     - **Affects:** `apply_affect(..., sense_deltas = {"sight": -1})` (skill effect) and `ctx.apply_affect(...,
       sense_deltas = {"sight": -1})`, stored on `Affect`, and exposed on `AffectView.sense_deltas`.
   - The new `senseDeltas` parameters come last in their constructors and functions, so positional callers still
     compile.
   - Effective sense on `Actor` is computed on the fly (like `Actor.stat(name)`): $$\text{effective}(c) = \max\bigl(0,\
     \text{base}(c) + \text{traitDelta}(c) + \text{affectDelta}(c) + \text{equipDelta}(c)\bigr)$$
   - `ActorView.senses` returns a read-only dict of effective senses for all channels in `schema.allChannels`;
     `BaseView.senses` (`actor.base.senses`) returns the unmodified base senses.
4. **Event Emission (`ctx.emit`) & Built-in Event Intensities:**
   - A Starlark verb on `Ctx` raises perceivable events:

     ```python
     ctx.emit(channel, event, intensity = 1, text = None, room = None, actor = None)
     ```

     - `channel` (`str`, required): validated against `schema.allChannels`.
     - `event` (`str`, required): non-empty event identifier (e.g. `"mechanism_click"`, `"alarm"`).
     - `intensity` (`int`, default `1`): initial signal strength $I$. If $I \le 0$, nothing is queued.
     - `text` (`str | None`, default `None`): optional payload text.
     - `room` (`RoomView | str | None`, default `ctx.room`): room where the event originates.
     - `actor` (`ActorView | None`, default `ctx.actor`): actor who caused the event, if any. The causer never perceives
       it.
   - Built-in engine events pass the following default initial intensities to `w.perceive(...)`:

     | Event                             | Channel |      Default Intensity      | Origin                                               |
     |:----------------------------------|:--------|:---------------------------:|:-----------------------------------------------------|
     | `say`                             | `sound` |             `1`             | `KernelCommands.say`                                 |
     | `emote`                           | `sight` |             `1`             | `KernelCommands.emote`                               |
     | `enter`, `exit`                   | `sight` |             `1`             | Movement in `World.kt` / `KernelCommands.kt`         |
     | `combat_start`, `combat`, `death` | `sound` |             `2`             | Combat lifecycle in `Combat.kt`                      |
     | `shout`                           | `sound` |             `3`             | `KernelCommands.shout`                               |
     | `open`, `close`, `lock`, `unlock` | `sound` | `rules.door_intensity(ctx)` | Door & container manipulation in `KernelCommands.kt` |

5. **`DoorView` on `ExitView.door`:**

   - `exit.door` returns a `DoorView` (`@StarlarkBuiltin(name = "door")`) when the exit has a door, or `None` when it
     has none. It used to return a state string.
   - `DoorView` is built from the view scope and the `Door` alone, and exposes:
     - `door.state -> str`: `"open"`, `"closed"`, or `"locked"`.
     - `door.key -> str | None`: key item prototype label string, or `None`.
     - `door.tags -> list[str]`: sorted list of tags declared on `door(tags = [...])`.
   - There are no per-door blocked-channel lists and no `exit.senses_blocked`. What an exit or door lets through is
     decided entirely by `rules.sense_cost`, which reads `exit.tags`, `door.state` and `door.tags`; the engine itself
     reads no tags.
6. **Environmental Attenuation via `rules.sense_cost(ctx)`:**
   - Signature: `def sense_cost(ctx) -> int` (in `rules/perception.star`).
   - Evaluated per hop across an inbound exit during Phase 5 graph traversal.
   - `ctx` fields for `sense_cost`:
     - `ctx.room`: `RoomView` — the room being entered by the signal (the listener-side room of the hop).
     - `ctx.exit`: `ExitView` — the exit in `ctx.room` leading toward the previous hop (including `ctx.exit.door` and
       `ctx.exit.tags`).
     - `ctx.channel`: `str` — the sensory channel name.
     - `ctx.pulse`, `ctx.rng`. (Note: `ctx.actor` is `None` during room-graph traversal.)
   - Return value:
     - A negative integer (conventionally `-1`) means the hop is **completely blocked**.
     - `0` is a free hop; a positive integer is the attenuation added to the path's cumulative `cost`.
   - The template blocks `sight` through a closed door unless its tags say `transparent`, muffles `sound` by the door's
     extra cost, makes large rooms muffle `sound`, and blocks everything through an exit tagged `sealed` (and `sound`
     through one tagged `soundproof`).
   - Neutral fallback on script failure: `-1` (blocks cross-room propagation safely).
7. **Perception Check via `rules.can_perceive(ctx)`:**
   - Signature: `def can_perceive(ctx) -> bool` (in `rules/perception.star`).
   - Asked of candidate listeners (`Mob` or `Player`) in the rooms the event reaches (including its own room at cost
     `0`), excluding `ev.actor` who caused the event. Only simulated actors in the world are candidates.
   - `ctx` fields for `can_perceive` and `perceive_message`:
     - `ctx.actor`: `ActorView` — the candidate listener being tested.
     - `ctx.target`: `ActorView` — alias for `ctx.actor` (the listener).
     - `ctx.source`: `ActorView | None` — the actor who caused the event (`ev.actor`), if any and still in world.
     - `ctx.room`: `RoomView` — the listener's room (`ctx.actor.room`).
     - `ctx.source_room`: `RoomView` — the room the cheapest path to the listener starts from. For most events that is
       the room of the event; for a door it is whichever of its two sides is cheaper.
     - `ctx.via_exit`: `str | None` — direction name of the exit in `ctx.room` leading toward `ctx.source_room`, or
       `None` when the listener is in the starting room.
     - `ctx.channel`: `str` — the channel name.
     - `ctx.event`: `str` — the event type (`"say"`, `"shout"`, `"combat"`, etc.).
     - `ctx.intensity`: `int` — the event's initial intensity $I$.
     - `ctx.cost`: `int` — cumulative path attenuation cost from the starting room to `room` (`0` in the same room).
     - `ctx.text`: `str | None` — the event's text payload, if any.
     - `ctx.pulse`, `ctx.rng`.
   - Template ruleset logic for `can_perceive(ctx)`:

     ```python
     def can_perceive(ctx):
     sense = ctx.actor.senses[ctx.channel]
     if sense <= 0:
             return False
     if ctx.source_room == ctx.room:
             return True
     return ctx.intensity + sense > ctx.cost
     ```
   - Neutral fallback on script failure: `False`.
8. **Cross-Room Player Perception via `rules.perceive_message(ctx)`:**
   - Signature: `def perceive_message(ctx) -> str | None` (in `rules/perception.star`).
   - Sent to a **Player** in another room (`ctx.via_exit` is non-null) when `can_perceive(ctx)` returns `True`. The room
     of the event is not sent it, because built-in commands and script hooks echo same-room messages synchronously in
     Phases 2–4 so hook reactions appear in causal order. The exception is `shout`, whose speaker is told directly and
     whose every other hearer, in the room or not, gets it from `perceive_message` (`ctx.via_exit` is `None` in the
     speaker's room).
   - If `perceive_message(ctx)` returns a non-empty `str`, the engine sends it to the player (`w.send(player, msg)`);
     for a shout it is also delivered as the player's `shout` channel text. Returning `None` or `""` suppresses player
     output.
   - `KernelCommands.shout` has no hardcoded 1-hop loop and `KernelMsg.SHOUT_NEAR` is retired.
   - Neutral fallback on script failure: `None`.
9. **Mob Perception Hook (`on_perceive`, renamed from `on_nearby`):**
   - `HookName.ON_NEARBY("on_nearby")` is renamed to `HookName.ON_PERCEIVE("on_perceive")` across the engine, scripts,
     tests, and docs. It attaches to mobs only.
   - When `can_perceive(ctx)` returns `True` for a simulated `Mob` that defines `on_perceive` (and `mob !== ev.actor`),
     `on_perceive` is invoked in Phase 5.
   - `on_perceive` receives `ctxFields`: `listOf("self", "event", "channel", "intensity", "cost", "actor",
     "source_room", "via_exit", "text", "room", "pulse", "rng")`.
     - Here `ctx.intensity` is the initial intensity $I$, and `ctx.cost` is the cumulative path cost (`0` in the same
       room).
10. **Spatial Propagation Algorithm (in `Perception.kt`):**
    - For each queued `Perception` event in Phase 5 (`deliverPerceptions()`), `spread` runs a shortest-path search from
      the event's room (and a second room, `alsoAt`, for doors) with `cost = 0`, `hops = 0`, `via = null`. A min-heap
      ordered by cumulative `cost` walks `inbound(currRoom)`:
      - Each `(neighborRoom, inboundExit)` is an exit **in `neighborRoom` that leads to `currRoom`**, so
        `inboundExit.direction` is the exact `via_exit` direction from a listener in `neighborRoom` toward the source.
        (One-way exits into `currRoom` propagate back to `neighborRoom`; one-way exits out of `currRoom` with no return
        exit in `neighborRoom` are not in `inbound(currRoom)`.)
      - A path never uses more than `BUBBLE_RADIUS` (`3`) exits.
      - The hop is priced with `rules.senseCost(RulesContext(world, room = neighborRoom, exit = inboundExit, channel =
        ev.channel))`. A negative price blocks the hop. Prices are remembered per delivery pass by (room, exit,
        channel).
      - Each room is reached by its cheapest path of at most `BUBBLE_RADIUS` exits, so the search keeps the best cost
        per room and number of exits used: a cheaper but longer path never hides a dearer one that is within range.
    - **Deduplication per pulse:** If multiple events with the same `(listener.id, ev.event, ev.room.id)` reach an actor
      in the same pulse, keep the one with the highest effective strength (`ev.intensity - cost`), preserving first-seen
      order on ties.
11. **Door and Container Manipulation Hooks & `door_intensity(ctx)`:**
    - `door(...)` declarations gain `tags: list[str] = []` (stored on `DoorProto.tags` and `Door.tags`, exposed on
      `DoorView.tags`).
    - Eight hooks are added to `HookName` and `HookCatalog`: `before_open`, `on_open`, `before_close`, `on_close`,
      `before_lock`, `on_lock`, `before_unlock`, `on_unlock`.
    - **Attachment (`OwnerKind`):** following the `before_enter`/`before_exit` pattern, all 8 hooks attach to items,
      rooms and mobs and are dispatched via standard `w.dispatch(hook, primary, room = actor.room, ctx)`.
      - `before_*` hooks veto the action by returning `ctx.deny("reason")`, which sends the reason to the actor and
        aborts the state change.
      - `ctxFields` for all 8 hooks: `listOf("self", "actor", "direction", "item", "room", "pulse", "rng")`. (For doors,
        `direction` is set and `item` is `None`; for containers, `item` is set and `direction` is `None`.)
    - **Noise emission via `rules.door_intensity(ctx) -> int`:**
      - Signature: `def door_intensity(ctx) -> int` (in `rules/manipulation.star`).
      - `ctx` fields: `ctx.actor`, `ctx.room`, `ctx.action` (`"open"`, `"close"`, `"lock"`, `"unlock"`), `ctx.tags`
        (`list[str]` — the door's `tags` or the container item's `tags`), `ctx.exit` (`ExitView | None`), `ctx.item`
        (`ItemView | None`), `ctx.pulse`, `ctx.rng`.
      - When a door or container state change succeeds, `KernelCommands.makeNoise` queues one `sound` event with
        `intensity = rules.door_intensity(ctx)` (nothing if it is `0`):
        - For a door it starts in the actor's room and in the room `exit.to` names, both at cost `0`, whether or not
          that room has an exit back. Each room is reached from whichever origin is cheaper, so a listener on the far
          side is in the event's own room: it gets no `via_exit` and no `perceive_message`, and is told only by the
          door's own echo.
        - For a container it starts only in the actor's room.
      - Neutral fallback on script failure: `0` (silent). Negative returns are clamped to `0`.

---

## Work Plan

Each step below names the files and signatures it touched and the **Exit Condition** that verified it.

### Step 1: Core Model, Plugin API, and Effective Senses (`plugin-api`, `core`)

1. **Remove `enum class Sense` and unify on `Channel`:** in `core/.../model/Spatial.kt`, delete the enum and add
   `Channel.BUILTIN_SENSES` (`sight` and `sound`, both `1`).
2. **Add `tags` to `DoorProto` and `Door`** (`Protos.kt`, `Entities.kt`), copied from the proto when rooms are
   instantiated.
3. **Add `senseDeltas`**, always as the last parameter:
   - `PluginTrait.senseDeltas: Map<String, Int>` in `plugin-api/.../PluginApi.kt`; `Trait.senseDeltas: Map<Channel,
     Int>` in `Creation.kt`.
   - `plugin-api/.../host/PluginHost.kt` validates the keys against `schema.allChannels`, reporting unknown channels
     like unknown `statDeltas`; `Adapters.kt` converts keys to `Channel`.
   - `MobProto.senses` becomes `Map<Channel, Int>`; `ItemProto.senseDeltas` is added and `ItemProto.isKeyOnly` checks it
     (`Protos.kt`, `KernelCommands.kt`).
   - `Affect.senseDeltas` (`Entities.kt`), `Effect.ApplyAffect.senseDeltas` (`Effects.kt`) and
     `World.applyAffect(senseDeltas)` (`Combat.kt`).
4. **Move `senses` to `Actor` and `StartingStats`:** `StartingStats.senses` (`RulesEngine.kt`); `Actor.baseSenses`
   starts from `Channel.BUILTIN_SENSES`, `Actor.traitSenseDeltas` is recomputed with the traits, and
   `Actor.sense(channel)` / `Actor.senses` give the effective score.
5. **Update `HookName` and `HookContext`** (`Hooks.kt`): rename `ON_NEARBY` to `ON_PERCEIVE`, add the eight door and
   container hooks, and add `channel`, `intensity` and `cost` to `HookContext`.

**Exit Condition:** `./gradlew :plugin-api:test :core:compileKotlin` compiles with zero warnings and passes, including a
test that `PluginTrait(senseDeltas = ...)` is validated.

---

### Step 2: Multi-File Rules Package, Rules Engine Interface & `RulesCtx` (`core`, `script`)

1. **Allow `//rules` exports across multiple `.star` files:** `ScriptPackage.rulesExports` (`Package.kt`) is filled by a
   `findRulesExports` step in `Loader.kt` next to `findMigrate`, which records a `LoadError` when one export is defined
   in more than one file. `StarlarkRules` (`Rules.kt`) looks exports up there instead of hardcoding
   `//rules:combat.star`.
2. **Extend `RulesContext` and `RulesEngine`** (`RulesEngine.kt`): `actor` becomes nullable, and `room`, `exit`,
   `channel`, `event`, `intensity`, `cost`, `sourceRoom`, `viaExit`, `text`, `action`, `tags` and `source` are added.
   `RulesEngine` gains `senseCost` (default `1`), `canPerceive`, `perceiveMessage` (default `null`) and `doorIntensity`
   (default `1` for open and close, else `0`).
3. **Extend `RulesCtx`, `RulesExport`, `StarlarkRules` and `dryRunRules`** (`Rules.kt`):
   - `RulesCtx` exposes `source`, `exit`, `channel`, `event`, `intensity`, `cost`, `source_room`, `via_exit`, `text`,
     `action` and `tags`.
   - New exports `sense_cost`, `can_perceive`, `perceive_message` and `door_intensity`, with the `ctxFields` and neutral
     fallbacks given above (`-1`, `False`, `None`, `0`).
   - `starting_stats` may return `senses`, validated and merged over `Channel.BUILTIN_SENSES`.
   - `dryRunRules` gives `//engine:dry_run` a loopback exit with a door and exercises the four exports across channels
     and actions.

**Exit Condition:** `./gradlew :script:test --tests "gups.script.RulesTest"` passes, including a test that splitting
exports across `rules/combat.star` and `rules/perception.star` works and defining the same export in both is a
`LoadError`.

---

### Step 3: Starlark Declarations, Views (`DoorView`), Hook Catalog & `ctx.emit` (`script`)

1. **Views** (`Views.kt`): `DoorView(scope, door)` with `state`, `key` and `tags`; `ExitView.door()` returns it;
   `ActorView.senses`, `BaseView.senses`, `ItemView.sense_deltas` and `AffectView.sense_deltas`. `DoorView` is listed in
   `VIEW_CLASSES` in `ApiModel.kt`.
2. **Declarations** (`Declarations.kt`): a `senses(...)` helper validates channel keys and integer values; `mob(senses)`
   no longer has the radius cap; `item(sense_deltas)`, `apply_affect(sense_deltas)` and `door(tags)` are added; the
   `HookCatalog` renames `on_nearby` to `on_perceive` (mobs only) and adds the eight door and container hooks (items,
   rooms and mobs).
3. **`Ctx.kt`:** `ctx.channel`, `ctx.intensity` and `ctx.cost`; `sense_deltas` on `ctx.apply_affect`; and `ctx.emit`,
   which validates the channel and event, defaults `room` and `actor`, and calls `world.perceive` when the intensity is
   above `0`.

**Exit Condition:** `./gradlew :script:test --tests "gups.script.ApiModelTest" --tests "gups.script.LoaderTest" --tests
"gups.script.HostTest"` passes, with `HostTest` using `exit.door.state`.

---

### Step 4: Ruleset Implementations (`template-world`, `samples/robot-world`, `TestWorlds.kt`)

1. **`rules/perception.star`** in `template-world` and `samples/robot-world` defines `sense_cost`, `can_perceive` and
   `perceive_message`. `sense_cost` reads `exit.tags` (`sealed`, `soundproof`), `door.state` and `door.tags`
   (`transparent`, `heavy`) and room size; `perceive_message` words shouts, combat, deaths and door sounds. See the
   files for the exact code.
2. **`rules/manipulation.star`** defines `door_intensity`: `silent` is `0`; `open` and `close` are `2` for `heavy`,
   `creaky` or `loud` tags and `1` otherwise; `lock` and `unlock` are `1` for `clanking`, `noisy_lock` or `heavy` tags
   and `0` otherwise.
3. **`TestWorlds.RULES`** includes the four exports.
4. **`template-world/zones/tutorial/mobs.star`** uses `senses = {"sound": 1}` and `on_perceive`.

**Exit Condition:** `./gradlew :cli:run --args="lint template-world"` and `./gradlew :cli:run --args="lint
samples/robot-world"` load and lint with 0 errors.

---

### Step 5: Multi-Hop Perception Engine & Door/Container Hooks (`core`, `script`)

1. **`Perception.kt`:** `Perception` and `World.perceive(...)` take an `intensity` (ignored when `<= 0`), an optional
   second starting room `alsoAt`, and `tellRoom`. `spread` runs the propagation described above and
   `deliverPerceptions()` deduplicates, asks `can_perceive`, runs `on_perceive` and sends `perceive_message`. Built-in
   events use their default intensities from `KernelCommands.kt`, `Combat.kt` and `World.kt`.
2. **Door and container hooks** (`KernelCommands.kt`): `planDoor` and `planContainer` dispatch `before_*` (a denial is
   sent to the actor and aborts), change the state, echo to the room, call `makeNoise`, and then dispatch `on_*`.
3. **Tests:** `HookCtxFieldsTest` exercises all eight hooks on both a door and a container; `PerceptionTest` covers
   multi-hop spread, closed doors, `perceive_message` for distant players, `on_perceive` fields, blind and deaf actors,
   custom channels through `sense_deltas`, door and container hooks with `door_intensity`, and the
   cheapest-path-within-range rule.

**Exit Condition:** `./gradlew :core:test --tests "gups.core.world.PerceptionTest"` and `./gradlew :script:test --tests
"gups.script.HookCtxFieldsTest" --tests "gups.script.M5ScriptTest"` pass.

---

### Step 6: Tutorial Content, Spec Updates, Golden Files & Final Verification

1. **Tutorial world and docs:** `template-world/zones/tutorial/` has a `ctx.emit` example and creaky-door example with
   tests, and the tutorials (`05_mobs_and_behaviors.md`, `06_hooks_and_interactivity.md`, `README.md`) use `on_perceive`
   and document `ctx.emit`, `sense_deltas`, `sense_cost`, `can_perceive`, `perceive_message` and `door_intensity`.
2. **`SPEC.md` and `DESIGN_NEEDED.md`:** SPEC §3.3, §3.5, §3.9, §4.4, §4.6, §4.19, §5.7, §6.3 and §7.4 describe the
   settled design; the two resolved sections are gone from `DESIGN_NEEDED.md`.
3. **Golden files and Markdown:** the API model and docs baselines are regenerated and `./gradlew markdownFormat` is
   run.

**Exit Condition:** `./gradlew check` passes completely (including `markdownCheck` and the module-graph check) with zero
warnings.
