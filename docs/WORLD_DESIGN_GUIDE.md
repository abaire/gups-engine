# GUPS World Design Guide

This guide walks through designing and building a **new GUPS world from scratch**—from the top-level creative and
architectural decisions that lock in your database schema down to authoring zones, inhabitants, items, and skills.

Where the [World Builder's Guide](../template-world/docs/tutorials/README.md) teaches the syntax of individual Starlark
files and [Writing Plugins](PLUGINS.md) documents the Kotlin `PluginWorld` API, this document focuses on **design order,
architectural boundaries, balance baselines, and AI-assisted workflows** (both copy-pasteable agent prompts and the
interactive `gups-builder` CLI).

---

## Overview: The 7 Phases of World Creation

Because the GUPS engine has **no built-in game rules or fixed stat names**, early choices cascade into every package in
your world. Designing top-down prevents expensive rewrites or database wipes later:

1. **[Phase 1: Core Pillars & Early Gameplay Philosophy](#phase-1-core-pillars--early-gameplay-philosophy)** — Setting,
   vocabulary, death/corpse recovery, PvP and friendly-fire policy, and solo vs. group pacing.
2. **[Phase 2: Engine Features, Schema & Skinning](#phase-2-engine-features-schema--skinning-modulegups--messagesgups)**
   — Choosing active engine features (`combat`, `track`, `skills`, `leveling`, `standing`), declaring `stats`, `vitals`,
   `damage_types`, and `channels` in `MODULE.gups`, and skinning commands/messages in `MESSAGES.gups`.
3. **[Phase 3: Choosing the Right Layer (Starlark vs. Kotlin Plugins)](#phase-3-choosing-the-right-layer-starlark-vs-kotlin-plugins)**
   — Deciding what belongs in `//rules`, `//lib`, `//skills`, `//zones`, or a compiled Kotlin `.jar` plugin.
4. **[Phase 4: Global Rules, Progression & Economy](#phase-4-global-rules-progression--economy-rules--lib)** — Setting
   combat math, TTK (time-to-kill), equipment slots, skill learning models, and currency/weight budgets.
5. **[Phase 5: World Geography, Factions & Skill Catalogs](#phase-5-world-geography-factions--skill-catalogs)** —
   Planning the starting hub, zone topology, regional modifiers, faction matrices, and global skill packages.
6. **[Phase 6: Fleshing Out Zones, Inhabitants & Items](#phase-6-fleshing-out-zones-inhabitants--items)** — Iteratively
   building rooms, mobs, loot, shops, trainers, crafting stations, and quests using `gups-builder` and coding agents.
7. **[Phase 7: Verification, Scenarios & Balance Testing](#phase-7-verification-scenarios--balance-testing)** — Running
   `gups lint`, `gups test`, `gups scenario`, and `gups fuzz` to validate gameplay and math.

---

## Bootstrap: Starting Your World Repository

Never edit [`template-world/`](../template-world) in place (the engine's test suite verifies it on every commit). Copy
either the fantasy template or the non-fantasy sample world to start your own world repository:

- **Classic fantasy / full-featured baseline:** [`template-world/`](../template-world) (all 5 engine features on, 8
  stats, 3 vitals, trainers, shops, crafting, factions, and a full tutorial zone).
- **Minimal / non-fantasy baseline:** [`samples/robot-world/`](../samples/robot-world) (`combat` and `skills` only—no
  tactical track, no levels/XP, no factions, custom vitals like `integrity`/`sanity`/`power`/`rads`, and use-based skill
  progression).

```bash
# Build the gups CLI launcher from gups-engine:
./gradlew :cli:installDist

# Copy a starting world outside the engine repo (or to local-world for local experiments):
cp -r template-world ../my-world
rm -f ../my-world/CODEOWNERS

# Generate offline HTML documentation tailored to your world's active features and plugins:
cli/build/install/gups/bin/gups docs --world ../my-world --out ../my-world/build/docs
```

---

## Phase 1: Core Pillars & Early Gameplay Philosophy

Before editing `MODULE.gups` or writing a single room, settle four foundational questions. Ambiguity here causes
contradictory zone designs later.

### 1. Setting, Tone, and Action Vocabulary

- **What is the world, and who are the players?** Are players fledgling adventurers in a high-fantasy borderland,
  scavenger chassis in a post-human foundry ([`samples/robot-world`](../samples/robot-world)), or occult investigators
  in a gaslamp city?
- **What do players call their actions?** In GUPS, every active or passive ability is declared with `skill()`, and its
  `kind` (`"spell"`, `"prayer"`, `"routine"`, `"maneuver"`, `"craft"`) is free-form. Decide early whether players type
  `cast` (the default) or a setting-specific verb such as `invoke`, `execute`, or `channel` (configured via `rename` in
  `MODULE.gups`).

### 2. Death, Corpses, and Recovery Philosophy

How punishing should failure be? In GUPS, vitals, conditions, death, corpses, and respawn belong to the always-on engine
kernel ([`docs/SPEC.md` §5.4](SPEC.md)):

- **Incapacitation vs. Instant Death (`rules/combat.star:condition`):**
  - After any vital change and on every world tick, the engine calls `condition(ctx)` in
    [`rules/combat.star`](../template-world/rules/combat.star), which returns `"ok"`, `"incapacitated"`, or `"dead"`.
  - You can make `hp <= 0` immediately fatal (`"dead"`), or introduce a bleed-out / knock-out window (`"incapacitated"`
    when `hp <= 0 and hp > -10`, where the player cannot use tick-lane commands until healed or until
    `ctx.actor.below_zero_since("hp")` exceeds a threshold).
  - Non-traditional vitals can also kill: in
    [`samples/robot-world/rules/combat.star`](../samples/robot-world/rules/combat.star), dropping `rads` below zero for
    a full minute (`240` pulses) is fatal.
- **What Happens to Gear on Player Death (`//engine:corpse`):**
  - When a player dies, the engine leaves a corpse container (`//engine:corpse`) in the room holding the player's
    **carried inventory only**; **worn and wielded equipment stays on the player** when they respawn.
  - Corpses decay after `CORPSE_DECAY_PULSES` (dropping their contents on the ground and firing `on_destroy` with `event
    = "decay"`).
  - By default, **any actor can loot any corpse** on the ground (`get all corpse`). If your world wants private player
    corpses or no corpse drop at all, decide that upfront: you can either avoid putting essential progression items in
    loose inventory, or add a Kotlin plugin (`ContainmentFilter` / `Restriction`) or `on_death` hook that moves carried
    items or blocks looting.
- **Respawn Location and XP/Level Penalties (`rules/combat.star:on_player_death`):**
  - `on_player_death(ctx)` returns a dict with `"respawn"` (typically `ctx.actor.recall_room`), `"vitals"` (e.g.,
    `vitals(hp = 1)` or full vitals), `"xp_loss"`, and `"level_loss"`.
  - Decide early between:
    1. **Forgiving / Narrative:** `xp_loss = 0`, `level_loss = 0`, wake up at `recall_room` with half or full vitals.
    2. **Classic MUD (`template-world` default):** Lose 10% of XP earned within the current level (`level_loss = 0`),
       wake up at `1 hp`, and run back to recover carried inventory from the corpse.
    3. **Hardcore:** Lose 10% of total XP and de-level (`level_loss >= 1`) when XP drops below the current level's
       floor.

### 3. PvP, Friendly Fire, and Conflict Boundaries

By default, when the `combat` feature is enabled:

- Typing `kill <target>` or casting a hostile (`target = "enemy"`) skill can target **any actor in the room**, including
  another player.
- Casting a hostile skill with `range = "room"` hits **every other actor in the room**, including groupmates, pets, and
  bystander players, and engages them in combat.
- If a player's pet engages another player's pet in the same room, both owners are automatically drawn into combat with
  each other ([`docs/SPEC.md` §5.4](SPEC.md)).

Decide your world's conflict policy before designing skills and zones:

- **Strictly Cooperative (PvE Only):**
  - Avoid raw `range = "room", target = "enemy"` declarative damage primitives if players are meant to fight side by
    side; instead, give area skills a custom `resolve` function that loops over `ctx.room.actors` and skips players (`if
    not a.is_player`) or groupmates/allies (`ctx.allies_near`).
  - To prevent players from casting hostile single-target skills on each other, check `if ctx.target and
    ctx.target.is_player and ctx.actor.is_player` in `rules/combat.star:cast_allowed(ctx)` (or a plugin's
    `Restriction.CAST`).
  - Note that `before_attack` attaches to **mobs and equipped items** (not bare players). If you want to block `kill`
    swings between players globally in a PvE world, either give characters an innate/starter worn emblem with a
    `before_attack` hook that denies player attackers, or enforce sanctuary rules in hub rooms.
- **Sanctuary Hubs + Lawless Frontier:**
  - Mark town/hub rooms with a region (e.g. `regions = ["sanctuary"]`) or room `modifiers = {"cast_allowed": ...}` and
    station high-level guard mobs with the `guard()` behavior (`//zones/tutorial:mobs.star`) that intervene when combat
    breaks out.
- **Open or Faction PvP:**
  - Document clearly whether corpse looting is fair game and whether faction standing (`standing_change` in
    `rules/standing.star`) penalizes attacking members of allied factions.

### 4. Solo vs. Group Pacing

- **Solo-First:** Every zone should be completable by a single player (possibly with a charmed/owned pet via
  `ctx.set_owner` or `summon()`), and boss mechanics shouldn't require dedicated tank/healer/DPS roles.
- **Group-Oriented:** Use [`template-world/lib/group.star`](../template-world/lib/group.star) (`invite`, `accept`,
  `group`, `gtell`), tune `xp_share` weights (`WEIGHT_DAMAGE`, `WEIGHT_HEALING`, `WEIGHT_SUPPORT`, `IDLE_SHARE`) in
  [`rules/combat.star`](../template-world/rules/combat.star) to reward healers and support casters, and use `retarget` +
  `rules/targeting.star` threat weights so tanks can hold aggro.

### Agent Prompt: World Concept & Pillars Brainstorm

Copy and paste this prompt into an AI agent to interactively establish your Phase 1 World Bible:

```markdown
I am designing a new text MUD world on the GUPS engine. Interview me 3–4 questions at a time to help me lock in our
Phase 1 "World Bible" before we write `MODULE.gups` or any Starlark code. Cover:
1. Setting, atmosphere, player identity, and what "skills/casting" are called in-world.
2. Core gameplay loop and whether progression is level-based, skill-use-based, or gear/exploration-based.
3. Death, incapacitation, corpse recovery, and XP penalty philosophy (`condition` and `on_player_death`).
4. PvP and friendly-fire policy (strictly cooperative PvE, sanctuary towns + frontier PvP, or open conflict).
5. Solo vs. group focus and expected session pacing.

Once we finish the interview, synthesize our decisions into a concise 1-page World Design Pillars summary that we can
feed into `MODULE.gups` and `//rules` design.
```

---

## Phase 2: Engine Features, Schema & Skinning (`MODULE.gups` & `MESSAGES.gups`)

Once your pillars are clear, translate them into [`MODULE.gups`](../template-world/MODULE.gups) and (optionally)
[`MESSAGES.gups`](../samples/robot-world/MESSAGES.gups) at the root of your world directory.

> [!WARNING] **Database-Locked Settings (`gups serve --wipe`):** The engine records `features` and the exact names and
> order of `stats`, `vitals`, `damage_types`, and `channels` in the SQLite database on first boot. Changing, reordering,
> or removing any of those names later refuses server startup until you run `gups serve --wipe` (which deletes all saved
> characters and items). Labels, damage adjectives, `prompt`, `rename`, `aliases`, `prefixes`, `level_mode`,
> `memory_pulses`, and `standing_history_pulses` are **not** locked in the database and only require a server restart.

### 1. Choosing Active Engine Features (`features = [...]`)

GUPS consists of an always-on **kernel** plus **five optional feature modules** ([`docs/SPEC.md` §3.10](SPEC.md)).
Leaving `features` out of `MODULE.gups` turns all five on. Turning a feature off completely removes its commands,
declarations, hooks, rules exports, and GMCP messages from the world:

|  Feature   |                                                                                  What It Enables                                                                                   | Requires |                                                                                           Turn It Off When…                                                                                           |
|------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `combat`   | `kill`, `flee`, combat rounds, auto-attacks, initiative, weapon procs, `aggressive`/`assist`/`flee`/`defend`/`retarget` behaviors, combat memory (`memory_pulses`), `target_order` | —        | Your world is purely puzzle, narrative, social, or non-violent exploration.                                                                                                                           |
| `track`    | 1D tactical room track (`SIZE.cramped`/`normal`/`large`), `MELEE`/`NEAR`/`FAR` bands, `advance`/`retreat`, pinning, ranged weapons, `knockback`/`pull`                             | `combat` | You want classic flat room-wide MUD combat where everyone in the room is immediately in melee reach (see [`samples/robot-world/MODULE.gups`](../samples/robot-world/MODULE.gups)).                    |
| `skills`   | `skill()` declarations, `//skills/...` packages, `cast` (or its `rename`), `skills` command, `cast_allowed`, `skill_check`, `skill_power`, `ctx.skill_roll`                        | —        | Every special action is performed through items (`use`) or room/mob commands rather than learned character abilities.                                                                                 |
| `leveling` | `level` and `xp`, `xp_for_kill`, `xp_share`, `xp_to_level`, `on_level_up`, `level_mode` (`"auto"` vs `"deferred"`), XP/level loss on death                                         | —        | Progression is horizontal or skill-based (e.g., use-based skill gain via `on_skill_use` in [`samples/robot-world/lib/skill_gain.star`](../samples/robot-world/lib/skill_gain.star) or gear/crafting). |
| `standing` | `faction()` declarations, `mob(factions = [...])`, personal & faction standing (`-100..100`), `standing_change` rules export, disposition thresholds on behaviors                  | —        | Mobs have fixed hostility (`aggressive()` without disposition thresholds) and no reputation/grudge mechanics are needed.                                                                              |

### 2. Designing the World Schema (`MODULE.gups`)

Compare [`template-world/MODULE.gups`](../template-world/MODULE.gups) and
[`samples/robot-world/MODULE.gups`](../samples/robot-world/MODULE.gups):

- **`stats`:** The numbers that describe an actor's capabilities (e.g., `str`, `dex`, `con`, `int`, `wis`, `cha`,
  `speed`, `armor`, or `might`, `agility`, `tech`, `grit`, `plating`).
  - *Guideline:* Keep the list to **5–8 stats**. Only declare a stat if at least one formula in `//rules` or library in
    `//lib` actually reads it! If you want `rules/movement.star` to scale walking speed, include a `"speed"` stat (or
    adapt `move_pace`).
- **`vitals`:** The pools that rise and fall during play (e.g., `hp`, `mana`, `move`, or `integrity`, `sanity`, `power`,
  `rads`).
  - *Guideline:* Usually 2–4 vitals: one primary health pool, one ability/energy pool, one movement/stamina pool, and
    optionally an environmental/corruption meter (like `rads` or `sanity`).
- **`damage_types` and `unarmed_damage_type`:** Every weapon, `damage(...)` effect, resistance (`resists(...)`), and
  `mitigate` formula uses these names. Give each damage type a descriptive `adjective` (`damage_type("fire", adjective =
  "fiery")`) so onlookers who don't recognize a cast skill still see flavorful hit messages.
- **`channels`:** Sensory channels beyond the built-in `sight` and `sound` (e.g., `spell`, `psionic`, `radio`,
  `tremor`). Doors/exits can block them in `rules/perception.star:sense_cost`, skills can broadcast on them (`channels =
  ["spell"]`), and scripts can emit events on them (`ctx.emit`).

### 3. Renaming Commands & Skinning Engine Messages (`MESSAGES.gups`)

If your world isn't medieval fantasy, you don't need to fork the engine to change its vocabulary:

- **`rename` in `MODULE.gups`:** Map default engine command names to setting-appropriate verbs, e.g. `rename = {"cast":
  "invoke", "gossip": "broadcast"}`. Aliases and prefixes in `MODULE.gups` then point to the new name, and the old name
  is freed for custom commands.
- **`MESSAGES.gups`:** Override any stock engine message ID at the root of your world. Compare
  [`samples/robot-world/MESSAGES.gups`](../samples/robot-world/MESSAGES.gups), which replaces casting, combat, and
  health condition strings (`"is leaking coolant"`, `"has buckled plates"`) without touching Kotlin.

### Agent Prompt: Drafting `MODULE.gups` & `MESSAGES.gups`

```markdown
Read `template-world/MODULE.gups`, `samples/robot-world/MODULE.gups`, and `samples/robot-world/MESSAGES.gups`.
Based on our World Design Pillars:
[PASTE WORLD PILLARS HERE]

1. Propose the complete `MODULE.gups` for our new world, explaining:
   - Which of the 5 engine features (`combat`, `track`, `skills`, `leveling`, `standing`) are enabled and why.
   - What each declared `stat`, `vital`, `damage_type`, and sensory `channel` will be used for in `//rules`.
   - Any command `rename`s, `aliases`, `prefixes`, and `prompt` formatting.
2. Propose a `MESSAGES.gups` file that re-skins any default fantasy/engine phrasing to match our setting's tone.
```

---

## Phase 3: Choosing the Right Layer (Starlark vs. Kotlin Plugins)

One of the most important architectural decisions in GUPS is knowing where a game mechanic belongs. Over 90% of world
mechanics belong in **Starlark** (`//rules`, `//lib`, `//skills`, `//zones`), which hot-reloads on save in `--dev` mode
and is sandboxed with deterministic step limits. **Kotlin plugins** (`plugin-api`) are reserved for engine-level hooks
that Starlark deliberately cannot reach.

### Architectural Decision Matrix

|                                                             What You Want to Build                                                             |                        Where It Lives                         |                                                                                   Reference Files                                                                                    |
|------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Schema, features, prompt, command renames, aliases                                                                                             | `MODULE.gups`, `MESSAGES.gups`                                | [`template-world/MODULE.gups`](../template-world/MODULE.gups), [`samples/robot-world/MESSAGES.gups`](../samples/robot-world/MESSAGES.gups)                                           |
| Hit chance, damage, armor/resist mitigation, initiative, flee/retreat rolls                                                                    | `//rules/combat.star`                                         | [`template-world/rules/combat.star`](../template-world/rules/combat.star)                                                                                                            |
| Starting stats/vitals, level-up gains, XP curves, XP sharing, death penalties, encumbrance, equipment slots                                    | `//rules/combat.star`                                         | [`template-world/rules/combat.star`](../template-world/rules/combat.star)                                                                                                            |
| Mob target selection (`weakest`, `strongest`, `hated`, `threat`)                                                                               | `//rules/targeting.star`                                      | [`template-world/rules/targeting.star`](../template-world/rules/targeting.star)                                                                                                      |
| Faction & personal standing changes on attack/kill/heal/theft/donation                                                                         | `//rules/standing.star`                                       | [`template-world/rules/standing.star`](../template-world/rules/standing.star)                                                                                                        |
| Movement speed (`move_pace`) and pursuit step chaining (`chain_think`)                                                                         | `//rules/movement.star`                                       | [`template-world/rules/movement.star`](../template-world/rules/movement.star)                                                                                                        |
| Sight/sound/channel propagation across doors & rooms, stealth detection, recognizing skills                                                    | `//rules/perception.star`                                     | [`template-world/rules/perception.star`](../template-world/rules/perception.star)                                                                                                    |
| Door noise intensity and scaling item `use_effect`/proc strength (`item_power`)                                                                | `//rules/manipulation.star`                                   | [`template-world/rules/manipulation.star`](../template-world/rules/manipulation.star)                                                                                                |
| Shops, trainers, crafting stations, item salvage, quests, player groups, behavior trees, multi-room pursuit, use-based skill gain              | `//lib/*.star`                                                | [`template-world/lib/`](../template-world/lib), [`samples/robot-world/lib/skill_gain.star`](../samples/robot-world/lib/skill_gain.star)                                              |
| Global player commands (`quests`, `group`, `craft`) and global lifecycle hooks (`on_death`, `on_level_change`, `on_skill_use`, `on_disengage`) | `//lib/*.star` or `//rules/*.star`                            | [`template-world/lib/quest.star`](../template-world/lib/quest.star), [`template-world/lib/group.star`](../template-world/lib/group.star)                                             |
| Reusable spells, techniques, routines, or crafting skills                                                                                      | `//skills/<pkg>/*.star`                                       | [`template-world/skills/arcane/spells.star`](../template-world/skills/arcane/spells.star), [`template-world/skills/crafts/crafts.star`](../template-world/skills/crafts/crafts.star) |
| Rooms, exits, doors, mobs, items, spawns, local quests, boss AI                                                                                | `//zones/<name>/*.star`                                       | [`template-world/zones/tutorial/`](../template-world/zones/tutorial)                                                                                                                 |
| **Character creation questions** (race, class, chassis, background) and **permanent `Trait`s**                                                 | **Kotlin Plugin** (`CreationProvider`, `Trait`)               | [`samples/heritage-plugin/`](../samples/heritage-plugin), [`docs/PLUGINS.md`](PLUGINS.md)                                                                                            |
| **Hard restrictions** on wearing/removing/dropping/giving/putting items, entering rooms, casting, learning skills, or ordering pets            | **Kotlin Plugin** (`Restriction`)                             | [`samples/heritage-plugin/`](../samples/heritage-plugin), [`samples/curse-plugin/`](../samples/curse-plugin)                                                                         |
| **Item prototype transforms & containment filters** (e.g., cursed bags, procedural item variants on load)                                      | **Kotlin Plugin** (`transformPrototype`, `ContainmentFilter`) | [`samples/curse-plugin/`](../samples/curse-plugin)                                                                                                                                   |
| **World-wide periodic ticks** (`every(pulses)`) or **custom GMCP packages** for the web/Mudlet client                                          | **Kotlin Plugin** (`every`, `addGmcp`)                        | [`samples/weather-plugin/`](../samples/weather-plugin), [`samples/hunger-plugin/`](../samples/hunger-plugin)                                                                         |
| **Monster-skill observation** or cross-world event listeners that grant traits/skills                                                          | **Kotlin Plugin** (`onEvent`)                                 | [`samples/bluemage-plugin/`](../samples/bluemage-plugin)                                                                                                                             |

### Rule of Thumb: When to Write a Kotlin Plugin

Ask three questions before reaching for Kotlin (`plugin-api`):

1. **Can a `//rules` export or modifier point express it?** If it changes hit chance, damage, mitigation, casting
   permission, movement cost/pace, regen, lag, or skill/item power, write it in `//rules` or attach a `modifiers =
   {...}` dict to a zone, region, room, item, or affect.
2. **Can a `//lib` helper + custom hooks (`hooks = {"shop_price": ...}`) + entity attributes (`ctx.set_attr`) express
   it?** Shops, trainers, crafting, salvage, quests, groups, and use-based skill progression are all pure Starlark in
   `//lib/`.
3. **Does it require character creation prompts, `Trait` definitions, `may_*` equipment/learning restrictions, periodic
   world-wide timers outside any entity, or a new GMCP namespace?** If yes, build a Kotlin plugin following
   [`docs/PLUGINS.md`](PLUGINS.md) and the walkthroughs in `samples/*-plugin/docs/tutorials/`.

### Agent Prompt: System Architecture Triage

```markdown
Read `docs/PLUGINS.md` (first 200 lines) and `template-world/lib/package.star`.
Here are the custom mechanics we want in our GUPS world:
[LIST MECHANICS HERE, e.g., character classes, a corruption meter, crafting, regional weather, faction bounties]

For each mechanic, classify whether it should be implemented in:
1. `MODULE.gups` + `//rules/*.star`
2. A shared Starlark library in `//lib/*.star` (using `global_command`, `global_hook`, or custom entity `hooks`)
3. A Kotlin `.jar` plugin against `plugin-api`

Cite the exact extension points, hooks, or rules exports each mechanic will use, and point to the closest existing
example in `template-world/` or `samples/`.
```

---

## Phase 4: Global Rules, Progression & Economy (`//rules/` & `//lib/`)

The engine has no built-in ruleset: **every required export in `//rules` must exist and pass a dry-run check when the
world loads** ([`docs/SPEC.md` §4.6](SPEC.md)). Set your mathematical baselines in `//rules/` before authors start
creating mobs and items in zones, so builders know what "`str = 14`", "`armor = 6`", or "`value = 50`" actually means.

### 1. Combat, Vital Math & Action Economy (`//rules/combat.star`)

> [!NOTE] **GUPS Time Units & Engine Cadences
> ([TimeConstants.kt](../core/src/main/kotlin/gups/core/TimeConstants.kt)):** - **1 Pulse = 250 ms** (4 pulses = 1
> second; 240 pulses = 1 minute). Used for skill `warmup`, recovery `lag`, `cooldown`, affect durations,
> `ctx.after(pulses, ...)` timers, `memory_pulses`, and `CORPSE_DECAY_PULSES` (1200 pulses = 5 minutes). - **1 Combat
> Round = 8 pulses (2.0 seconds)** (`COMBAT_ROUND_PULSES`). Auto-attacks and combat round resolution occur on pulse
> boundaries divisible by 8. - **Default Mob `think` Cadence = 4 pulses (1.0 second)** (`THINK_DEFAULT_PULSES`). - **1
> World Tick = 120 pulses (30.0 seconds)** (`WORLD_TICK_PULSES`). Triggers passive vital regeneration (`regen(ctx)`) and
> periodic condition/encumbrance checks.

Establish a reference table for your world's numeric scale (or Level 1 to Max Level if `leveling` is on):

- **`starting_stats(ctx)`:** Returns `struct(stats = stats(...), max = vitals(...))` for new players
  (`ctx.actor.is_player`) and spawned mobs (reading `ctx.actor.base.<stat>` with sensible defaults so a mob prototype
  only has to declare stats that differ from the baseline).
- **Time-to-Kill (TTK) Triangle (`to_hit`, `damage`, `mitigate`):**
  - Tune `to_hit(ctx)`, `damage(ctx)`, and `mitigate(ctx, amount, type)` together so an even-level fight takes a
    predictable number of rounds (e.g., 6–10 combat rounds for a standard mob, 15–25 rounds for a boss).
  - Decide how **physical vs. non-physical mitigation** works: in
    [`template-world/rules/combat.star`](../template-world/rules/combat.star), `armor` reduces physical damage
    (`bludgeon`, `slash`, `pierce`) before percentage `resists` apply, while elemental damage (`fire`) bypasses `armor`
    and is reduced only by `resists.fire`.
- **Action Economy (`warmup` vs. `lag` vs. Auto-Attacks):**
  - **Recovery `lag`** only delays the actor's *next queued tick-lane command*; auto-attacks still fire on every 8-pulse
    combat round independently of `lag`, so instant (`warmup = 0`) skills weave seamlessly between weapon swings.
  - **Skill `warmup`** holds the actor's activity slot before resolution and **suppresses auto-attacks** while casting
    (unless the skill declares `attack_while_casting = True`). During `warmup`, taking damage can also interrupt the
    cast (`rules/combat.star:interrupt_check`). Giving a high-impact spell or heal a `warmup` of 4–8 pulses creates both
    a real opportunity cost against weapon swings and a tactical interrupt window.
- **Regeneration & Pacing (`regen`, `move_cost`):**
  - `regen(ctx)` runs every world tick (30 seconds / 120 pulses). Tune it so downtime between fights feels brisk (e.g.,
    1–2 minutes to recover full vitals, or faster if players rest/eat via items or affects).

### 2. Equipment Slots & Encumbrance (`//rules/combat.star`)

- **Equipment Topology:**
  - `equipment_slots()` defines all possible wear slots and the exact order the modifier chain visits worn items.
  - `slot_families()` groups interchangeable slots (e.g., `"ring": ["finger_l", "finger_r"]`, `"hand": ["wield",
    "offhand"]`).
  - `actor_slots(ctx)` can vary slots per actor (for example, a four-armed mutant trait or a wheeled robot chassis with
    no `"feet"` slot).
  - `slot_cost(ctx)` controls two-handed weapons (`slot_cost = 2` on the item, optionally reduced to `1` for
    high-strength actors).
- **Carry Weight & Encumbrance:**
  - `carry_limit(ctx)` sets maximum weight carried (`get` and `give` refuse above this).
  - `encumbrance_level(ctx)` and `encumbrance(ctx)` define tiered stat/movement penalties as a player's pack fills up.

### 3. Sensory Propagation & Item Manipulation (`//rules/perception.star` & `//rules/manipulation.star`)

- **Sensory Propagation & Stealth (`//rules/perception.star`):**
  - `sense_cost(ctx)` prices how `sight`, `sound`, and custom channels decay across each exit hop (`BUBBLE_RADIUS = 3`
    hops max; returning `-1` blocks the signal). In the template rules, closed doors block `sight` (unless tagged
    `"transparent"`), muffle `sound` (extra cost for `"heavy"` doors or `"large"` rooms), and stop at `"sealed"` or
    `"soundproof"` exits.
  - `can_perceive(ctx)` decides whether an actor notices an event or hidden actor (reading the `"hidden"` and
    `"pacified"` affect flags and recent noise memories).
  - `perceive_message(ctx)` formats distant sensory lines (`"You hear the sounds of combat from the east."`), and
    `recognizes_skill(ctx)` decides whether onlookers identify a cast skill by name or only see its generic
    `unrecognized` message (skills tagged `"subtle"` cannot be recognized by watching).
- **Door Noise & Item Power (`//rules/manipulation.star`):**
  - `door_intensity(ctx)` sets the `sound` intensity emitted when doors or containers are opened, closed, locked, or
    unlocked (honoring tags like `"silent"`, `"creaky"`, `"heavy"`, or `"noisy_lock"`).
  - `item_power(ctx)` returns the percentage strength (`100` = as declared) of an item's `use_effect` or weapon `proc`,
    allowing worlds to scale scrolls, wands, or gadgets with a character stat or artificer skill.

### 4. Skill Progression Model (`//rules/combat.star` + `//lib/`)

Decide how characters acquire and improve skills (`0..100` proficiency):

1. **Trainer NPCs + Practice Sessions (`//lib:trainer.star`):**
   - Players earn practice sessions on level-up (or start with some) and spend them at trainer mobs (`teach` /
     `practice` commands in [`template-world/lib/trainer.star`](../template-world/lib/trainer.star)).
2. **Use-Based Progression (`samples/robot-world/lib/skill_gain.star`):**
   - Register `global_hook("on_skill_use", ...)` in a `//lib` file so casting a skill or rolling a craft
     (`ctx.skill_roll`) has a chance to raise proficiency on the spot—ideal for worlds without `leveling`.
3. **Hybrid:** Learn rank `1` from a trainer, scroll, quest, or observation plugin
   ([`samples/bluemage-plugin`](../samples/bluemage-plugin)), then improve proficiency from `1` to `100` through use.

### 5. Economy, Currency Sinks & Power Budget Table

Write down a 1-page **Builder Budget Table** for your world so every zone uses the same scale:

| Tier / Level Band |   Mob HP & Base Stats   | Weapon `dice` | Armor Piece `stat_deltas(armor=)` | Skill Direct Damage, Cost & Lag |      XP & Kills-to-Level Pacing       | Standard Mob Coin Drop | Typical Item `value` (Shop Base Price) |
|-------------------|-------------------------|---------------|-----------------------------------|---------------------------------|---------------------------------------|------------------------|----------------------------------------|
| Tier 1 (Lv 1–3)   | 15–35 HP, stats 10–12   | `1d4` – `1d6` | `+1` to `+2`                      | `1d8+2`, 5–10 mana, 8p lag      | ~12–15 kills (or flat 20 in template) | 5–15 coins             | 10–50 coins                            |
| Tier 2 (Lv 4–7)   | 40–90 HP, stats 12–15   | `1d8` – `2d4` | `+2` to `+4`                      | `2d8+5`, 12–20 mana, 8p lag     | ~20–25 equal-level kills              | 20–60 coins            | 75–250 coins                           |
| Tier 3 (Lv 8–10+) | 100–250 HP, stats 15–20 | `2d6` – `3d4` | `+4` to `+7`                      | `4d6+10`, 25–40 mana, 10p lag   | ~30–40 equal-level kills              | 80–200 coins           | 300–1000 coins                         |

- **XP Pacing Note:** [`template-world/rules/combat.star`](../template-world/rules/combat.star) awards `50 * level` XP
  for an equal-level kill and sets `xp_to_level(ctx, level)` to `500 * level * (level + 1)`, which produces a flat **20
  equal-level kills per level** at every tier. Steepening `xp_to_level` gives the progressive `12–15` → `20–25` →
  `30–40` pacing above.
- **Currency Sinks:** Balance mob coin drops and salvage payouts against recurring gold drains so currency stays
  meaningful: multi-charge consumables (`charges = 3`, `empty = "destroy"`), crafting material/recipe costs
  (`//lib:craft.star`), trainer fees (`practice_offer` hook in `//lib:trainer.star`), and rate-capped faction donations
  (`ctx.standing_event("donation", ...)` in `rules/standing.star`).

### Agent Prompt: Designing & Verifying `//rules` Formulas

```markdown
Read `template-world/rules/combat.star`, `template-world/rules/movement.star`, `template-world/rules/perception.star`,
`template-world/rules/manipulation.star`, `template-world/rules/targeting.star`, and `template-world/rules/standing.star`.
Also read our `MODULE.gups`.

Help me design the `//rules/` package and a Builder Power Budget Table for our world:
1. Propose formulas for `starting_stats`, `condition`, `to_hit`, `damage`, `mitigate`, `regen`, `move_cost`,
   `carry_limit`, `encumbrance_level`, and `encumbrance` using our schema's exact stat/vital/damage_type names.
2. Calculate sample combat numbers (hit chance, average damage after mitigation, and expected rounds-to-kill) for:
   - A new character vs. a Tier 1 starter mob
   - A mid-tier character vs. an equal-tier mob
   - A character fighting a boss 3 levels higher
3. Produce a Builder Power Budget Table (Mob vitals/stats, weapon dice, armor deltas, item `value`, and XP/coin rewards
   by tier) that we can include in our world's README for zone builders.
```

---

## Phase 5: World Geography, Factions & Skill Catalogs

Before building individual rooms, sketch the macro-structure that connects zones together.

### 1. World Geography, Boundary Stubs, Start Room & Regions

- **The Starting Hub (`start_room` in `MODULE.gups`):**
  - Pick the room new players spawn in (e.g., `start_room = "//zones/haven:arrival"`).
  - Your starting hub should orient the player within 3–5 rooms: a safe recall/respawn room, a basic shop
    (`//lib:shop.star`), a skill trainer or terminal (`//lib:trainer.star`), a crafting/salvage bench
    (`//lib:craft.star`, `//lib:salvage.star`), and clear exits leading to beginner adventure zones.
- **Preventing Cross-Zone Exit Lint Deadlocks:**
  - `gups lint //...` (and `gups-builder`, which lints every staged proposal) fails if an exit points to a package or
    room label that does not exist yet, if an exit has no reciprocal return exit (unless `one_way = True`), or if a room
    has zero exits (unless `dead_end_ok = True`).
  - When planning multiple zones that connect at boundary rooms (`//zones/haven:east_gate` <->
    `//zones/forest:trailhead`), either:
    1. **Pre-create minimal stub packages** for planned neighboring zones (`zones/forest/package.star` + a stub
       `room("trailhead", ..., exits = {"west": exit(to = "//zones/haven:east_gate")})` in `rooms.star`), **or**
    2. **Leave the cross-zone exit out** (or behind a sealed/commented door) until the adjacent zone is created, wiring
       both sides of the boundary exit in the same change.
- **Cross-Zone `regions`:**
  - Any `room()` can declare `regions = ["indoors", "underground"]`.
  - Regions can span multiple zones and carry rule modifiers (`region("underwater", modifiers = {"move_cost": ...})`) or
    drive plugins (like [`samples/weather-plugin`](../samples/weather-plugin), which computes weather per region and
    suppresses outdoor weather in rooms tagged `"indoors"`). Agree on standard region names early (`"indoors"`,
    `"sanctuary"`, `"forest"`, `"underground"`, etc.).
- **Shared `tags` Vocabulary & Cross-Zone `//lib/` Helpers:**
  - **Free-form `tags = [...]`** on rooms, exits, doors, items, and skills (such as `"sticky_floor"`, `"soundproof"`,
    `"sealed"`, `"transparent"`, `"heavy"`, `"silent"`, `"subtle"`, or material/salvage tags) are read directly by
    `//rules/` and `//lib/`. Standardize your world's tag names in Phase 5 so every zone uses the same strings.
  - **Entity attributes (`ctx.set_attr` / `ctx.get_attr`) are write-isolated per package:** a script in
    `//zones/sunken_crypt` writes to the `zones/sunken_crypt:` namespace and **cannot** directly write another zone's or
    `//lib`'s attributes. Whenever multiple zones need to share dynamic state (quest progress, faction bounties, light
    sources, or reusable environmental hazards), define and export helper functions in `//lib/*.star` so all callers
    read and write through `//lib`'s namespace.

### 2. Factions & Disposition Matrix (`//lib/factions.star` & `//rules/standing.star`)

If the `standing` feature is enabled:

- Declare shared factions in [`lib/factions.star`](../template-world/lib/factions.star) (exported for cross-zone use) or
  zone-local factions in a zone's `.star` files:

  ```python
  faction(
      "town_guard",
      name = "The Watch",
      standing = {"bandits": -100, "merchants": 50},
  )
  ```
- Mobs join factions via `mob(..., factions = ["//lib:town_guard"])`.
- Built-in mob behaviors automatically react to effective disposition (`-100..100`) using thresholds:
  - `aggressive(disposition_below = -25)` — attacks players the faction distrusts or hates.
  - `assist(disposition_above = 25)` — joins fights to defend allied mobs or friendly players.
  - `guard(disposition_above = 0)` — protects non-hostile actors attacked in the room.

### 3. Global Skill Catalogs (`//skills/<pkg>/`)

If the `skills` feature is enabled, organize reusable skills into packages under `skills/` with `kind = "skills"` in
their `package.star` (see [`template-world/skills/arcane/`](../template-world/skills/arcane) and
[`template-world/skills/crafts/`](../template-world/skills/crafts)):

- **Declarative vs. Scripted Skills:**
  - Use declarative `effects = [damage(...), apply_affect(...), heal(...), stun(...), knockback(...)]` for standard
    combat/support abilities so the engine and `gups-builder` can inspect and balance them automatically.
  - Use a custom `resolve = _my_fn` hook when a skill needs custom targeting logic, memory manipulation (like `pacify`
    calling `ctx.forget` in [`template-world/skills/arcane/spells.star`](../template-world/skills/arcane/spells.star)),
    or room-wide filtering.
- **Using `gups-builder` in `skill` Mode:**
  - You can interactively generate and lint global skills with [`gups-builder`](https://github.com/abaire/gups-builder):

    ```bash
    gups-builder --world ../my-world skill skills/arcane
    ```
  - Inside the session, describe the skill in plain English (e.g., `> a fire bolt that burns an enemy at far range and
    leaves a 3-tick burning DoT`), inspect with `/show` and `/diff`, and commit with `/write`.

### Agent Prompt: World Atlas, Factions & Skill Catalog Planner

```markdown
Based on our World Design Pillars, `MODULE.gups`, and Builder Power Budget Table:
1. Propose a World Atlas of 4–6 initial zones (`//zones/<name>`), including:
   - The starting hub and tutorial flow (`start_room`)
   - Target level/power band and narrative theme for each zone
   - Which cross-zone `regions` exist and what rule `modifiers` they apply
   - How the zones connect at their boundary exits (`//zones/a:border` <-> `//zones/b:gate`)
2. Propose the global `faction()` declarations for `lib/factions.star`, including their initial faction-to-faction
   `standing` matrix (`-100` to `100`).
3. Propose 2–3 global skill packages (`//skills/<pkg>`) with 4–6 skills each, specifying `kind`, `target`, `range`,
   `cost`, `warmup`, `lag`, `cooldown`, and `effects`.
```

---

## Phase 6: Fleshing Out Zones, Inhabitants & Items

Each zone lives in its own package directory under `zones/<zone_name>/` with `kind = "zone"` in `package.star`.

### 1. Recommended File Layout Inside a Zone

All `.star` files in a package share one flat label namespace (`:room_id`, `:mob_id`, `:item_id`), and files can load
functions or constants from earlier files with `load("helpers.star", "...")`. Follow the layout of
[`template-world/zones/tutorial/`](../template-world/zones/tutorial):

|             Filename              |                                                                      What to Put in It                                                                      |
|-----------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `package.star`                    | `package(api_version = 1, kind = "zone", title = "...", reset_minutes = 15, modifiers = {...})` and any zone-wide `region()` declarations.                  |
| `rooms.star`                      | `room()` declarations, `exits = {...}`, `doors = {...}`, `spawns = [...]`, room `commands`, and room `on_enter`/`before_exit` hooks.                        |
| `mobs.star`                       | `mob()` declarations, `behaviors = [...]`, `factions = [...]`, `skills = {...}`, `loot = [...]`, and custom `think` / dialogue hooks (`on_say`, `on_give`). |
| `items.star`                      | `item()` declarations: weapons, armor, keys, containers, consumables (`use_effect` / `on_use`), and `item_affect()` definitions.                            |
| `shop.star` / `smithy.star`       | Optional NPC service wiring using `load("//lib:shop.star", "shop")`, `load("//lib:trainer.star", "trainer")`, or `load("//lib:craft.star", "craft")`.       |
| `helpers.star`                    | Shared helper functions used across multiple files in the zone.                                                                                             |
| `*_test.star` & `*_testdata.star` | Automated zone tests (`gups test`) and test-only fixtures.                                                                                                  |

### 2. Rapid Prototyping with `gups-builder`

The `gups-builder` CLI tool pairs a **logic** LLM (for room graphs, numbers, effects, and Starlark structure) with a
**prose** LLM (for names, descriptions, and hit/cast messages), staging every change against `gups lint` and `gups test`
before touching disk.

A typical workflow for a new zone (`zones/sunken_crypt`) moves through its three modes in one session:

1. **Lay Out the Room Graph (`area` mode):**

   ```bash
   gups-builder --world ../my-world area zones/sunken_crypt
   ```

   - Prompt with your spatial vision: `> a 10-room sunken crypt with a flooded entry stair, a locked ossuary gate, a
     collapsed chapel, and a drowned reliquary boss chamber`
   - Inspect the ASCII layout with `/map`, check individual room/door definitions with `/show <id>` and `/diff`, refine
     with follow-up prompts (e.g. `> make the flooded stair cramped and give the ossuary gate a rusted iron key`), and
     save with `/write`.
   - **Tactical Track Room Sizing (`SIZE` when `track` is enabled):** Vary room `size` intentionally across your zone's
     flow to create distinct combat encounters:
     - **`SIZE.cramped` (`trackSize = 1`, index `0` only):** Everyone stands at `BAND.melee` (distance `0`). `retreat`
       and `knockback` have nowhere to go, neutralizing kiting and ranged distance advantages—ideal for narrow stairs,
       crawlspaces, and ambush choke points.
     - **`SIZE.normal` (`trackSize = 3`, indices `0..2`, default):** Supports `MELEE` (`0`), `NEAR` (`1`), and `FAR`
       (`2`). Ranged attackers and casters can open at `FAR` (via `spawn(..., at_index = 2)` or `exit(..., entry_index =
       0)`), and one `knockback` or `retreat` creates breathing room.
     - **`SIZE.large` (`trackSize = 5`, indices `0..4`):** Provides multiple `FAR` steps (`2..4`) so archers or bosses
       spawned at index `4` require multiple `advance` actions to reach in melee, and increases `sound` propagation cost
       across exits in `rules/perception.star`—ideal for courtyards, sniper galleries, and boss arenas.
2. **Populate Inhabitants & Loot (`mob` mode):**
   - Switch modes inside the REPL:

     ```text
     > /mode mob
     > a skeletal sentry at the ossuary gate that guards the crypt faction, and a drowned prelate boss in the reliquary that flees never and drops the reliquary signet
     ```
   - Review the generated `mob()`, `item()`, and room `spawn()` entries with `/diff`, then `/write`.
3. **Add Zone or Boss Skills (`skill` mode):**
   - Switch to `skill` mode to give your inhabitants signature abilities:

     ```text
     > /mode skill
     > a bone-chilling curse the drowned prelate casts that deals cold damage and lowers str and dex for 20 pulses
     ```
   - Review and `/write`. Every `/write` also updates `builder_test.star` and verifies that `gups lint` passes cleanly.

### 3. Designing Rich Inhabitants (`mobs.star`)

Once the structural skeleton is in place, refine your mobs along three tiers of sophistication (see
[Tutorial 05: Mobs, Spawns, and Behaviors](../template-world/docs/tutorials/05_mobs_and_behaviors.md)):

- **Tier 1: Built-in Composable Behaviors:**
  - Combine `sentinel()`, `wander(stay_in_zone = True)`, `aggressive(prefer = "weakest", disposition_below = -10)`,
    `assist(prefer = "threat")`, `guard()`, `pursue(max_steps = 5)`, `retarget(prefer = "threat")`, and `flee(below =
    vitals(hp = 5))`.
- **Tier 2: Behavior Trees & Goal Planning (`//lib:ai.star`):**
  - Load `//lib:ai.star` (demonstrated in
    [`template-world/zones/tutorial/forager.star`](../template-world/zones/tutorial/forager.star)) to build multi-step
    routines (foraging, patrolling between waypoints, unlocking doors, or returning gathered items to a chest) using
    `ai.seq`, `ai.sel`, a persistent blackboard, and `ctx.plan`.
- **Tier 3: Custom `think` Hooks & Interactive Dialogue:**
  - Write a custom `think = _boss_think` function when a boss changes phases at 50% HP, shouts warnings, summons adds
    (`ctx.summon`), or targets healers.
  - Add `on_say`, `on_tell`, and `on_give` hooks for quest givers and reactive NPCs, using
    [`//lib:quest.star`](../template-world/lib/quest.star) to track quest stages and render `[!]` / `[?]` indicators in
    room descriptions.

### 4. Designing Items, Equipment & Services (`items.star`, `shop.star`, `smithy.star`)

- **Keywords & Specifiers:** Always include lowercase single-word nouns and distinctive adjectives in `keywords = [...]`
  (e.g., `keywords = ["rusted", "iron", "key"]`) so players can type `get key`, `get iron key`, or `get 2.key`.
- **Weapons & Procs:** Set `wear = "wield"` (or `"hand"`), `dice = "1d6"`, `damage_type = DAMAGE.slash`, `range =
  BAND.melee` (or `BAND.far` for bows/rifles when `track` is on), and optional `procs = [proc(trigger = "hit", chance =
  15, effect = ...)]`.
- **Armor & Equipment Affects:** Use `stat_deltas = stats(armor = 3, dex = 1)`, `max_vital_deltas = vitals(hp = 10)`,
  `resist_deltas = resists(fire = 15)`, or `equip_affects = [...]` for passive buffs while worn.
- **Consumables & Interactive Items:** Use `charges = 3`, `empty = "destroy"` (or `transform("empty_vial")`), and either
  a declarative `use_effect = heal(vital = "hp", dice = "2d4+2")` or a custom `on_use` hook.
- **Shops, Trainers, and Crafting Stations:**
  - Wire merchants with [`//lib:shop.star`](../template-world/docs/tutorials/08_shops.md) (`shop.define`), keeping stock
    in a locked chest in the room (for restocking and potential burglary) or a carried pack (for wandering peddlers).
  - Wire skill mentors with [`//lib:trainer.star`](../template-world/docs/tutorials/09_trainers.md) (`trainer.define`).
  - Wire workstations and recipes with [`//lib:craft.star`](../template-world/docs/tutorials/12_crafting.md) and
    salvageable drops with [`//lib:salvage.star`](../template-world/lib/salvage.star).

### 5. Starlark Sandbox & Determinism Rules

Both human builders and coding agents must follow three core GUPS scripting constraints:

1. **No Mutable Globals:** Top-level lists, dicts, and declarations in `.star` files are frozen as soon as the package
   finishes loading. Never try to mutate a module-level dict or list inside a hook; store all dynamic state on rooms,
   mobs, items, or players via `ctx.get_attr(entity, "key")` and `ctx.set_attr(entity, "key", val)`.
2. **Top-Level `def` Callbacks Only:** The Starlark host rejects `lambda` expressions and nested `def` statements at
   parse time so that every hook, command, modifier, `resolve` function, and `ctx.after` timer callback can be rebound
   by name across hot reloads. Define every callback as a top-level `def _my_hook(ctx):`.
3. **Reality Bubble Execution:** By default, only rooms within `BUBBLE_RADIUS = 3` exit hops of an online player are in
   the engine's active reality bubble; mobs outside the bubble are dormant and do not run `think` or behaviors. If a
   specific room or mob must stay simulated when no player is nearby, set `always_active = True` on its `room()` or
   `mob()` declaration, or call `ctx.set_active(entity, "reason")` / `ctx.set_anchor(entity, "reason")` at runtime (as
   [`//lib:pursuit.star`](../template-world/lib/pursuit.star) does while a mob is chasing a target). World-wide
   background simulation ticks outside any entity require a Kotlin plugin's `every(pulses)`.

### Agent Prompts: Zone Design & Scripting

#### Prompt A: Zone Design Brief & `gups-builder` Prompt Sequence

```markdown
Read `MODULE.gups`, `rules/combat.star`, and `template-world/zones/tutorial/rooms.star`.
We are designing a new zone `//zones/<zone_name>` for Tier [N] (levels [X–Y]).
Theme: [DESCRIBE THEME]
Adjacent connections: [LIST EXITS TO OTHER ZONES]

1. Design a 8–12 room graph with clear loops, landmarks, at least one locked door or environmental hazard, and varied
   room sizes (`SIZE.cramped`, `SIZE.normal`, `SIZE.large`).
2. List the inhabitants (`mob()`), their factions, behaviors, skills, and spawn placements.
3. List the items (`item()`), equipment rewards, keys, containers, and any shop/trainer/crafting NPCs, budgeted to Tier
   [N] of our Builder Power Budget Table.
4. Give me a sequence of 4–6 concise prompts I can feed directly into `gups-builder` (`area`, `mob`, and `skill` modes)
   to generate the initial `.star` files for this zone.
```

#### Prompt B: Scripting Custom Hooks, Quests & Boss Mechanics

```markdown
Read `template-world/zones/tutorial/mobs.star`, `template-world/zones/tutorial/shop.star`, and
`template-world/lib/quest.star`.
Also inspect our zone files in `zones/<zone_name>/`.

Add the following interactive mechanics to `zones/<zone_name>/`:
1. A multi-step quest using `//lib:quest.star` given by `:<npc_id>`, with `on_say` and `on_give` hooks and idempotent
   state stored via `ctx.get_attr` / `ctx.set_attr`.
2. A custom `think` hook on `:<boss_id>` that reacts to combat state (e.g., phase shift or special action) without
   violating Starlark top-level `def` rules.
3. Corresponding unit tests in `zones/<zone_name>/<zone_name>_test.star` that verify the quest walkthrough and boss
   behavior.
```

---

## Phase 7: Verification, Scenarios & Balance Testing

Every world change should pass four automated checks before deployment (see
[Tutorial 07: Testing and Verifying Your Zone](../template-world/docs/tutorials/07_testing_your_zone.md)):

### 1. Static Checks (`gups lint`)

Catches unresolved labels, one-way exit accidents, missing rules exports, schema mismatches, and prototype version bumps
missing a `migrate()` function:

```bash
gups lint --world ../my-world //...
```

### 2. Deterministic Package Tests (`gups test`)

Runs all `*_test.star` files in an isolated, deterministic in-memory world with a virtual clock:

```bash
gups test --world ../my-world //...
```

### 3. End-to-End Transcripts & Rules Tracing (`gups scenario`)

When tuning combat math, AI, or multi-player interactions, write a short scenario script and run `gups scenario`. Call
`w.trace()` before the commands you want to inspect—the runner prints every player command, room tag, output line, and
the full `rules.*` modifier chain (`to_hit`, `damage`, `mitigate`, `skill_check`):

```python
# scenario.star
def test_boss_fight_balance(t):
    w = t.world(seed = 42)
    p = w.spawn_player("Tess", at = ":boss_lair")
    w.trace()
    p.do("kill prelate")
    w.run(pulses = 80)
    print("Remaining HP:", p.hp)
```

```bash
gups scenario --world ../my-world --package //zones/sunken_crypt scenario.star
```

### 4. Automated Explorer Bots (`gups fuzz`)

Unleash simulated bot players across your zones to walk exits, open doors, pick up items, and fight mobs for thousands
of pulses. If a hook throws an error or breaks an invariant, `gups fuzz` prints the exact seed and writes a diagnostic
bundle for `gups repro`:

```bash
gups fuzz --world ../my-world --pulses 1000 //...
```

### 5. Live World Evolution & Prototype Migrations (`gups migrate-check`)

Once your world has a live database (`.gups/world.db`) with saved characters and items, you can evolve `room()`,
`mob()`, and `item()` declarations without running `gups serve --wipe`:

- **When to Bump `version`:** Presentation and code fields (`name`, `desc`, `keywords`, hooks, `commands`, `think`, and
  `resolve`) can be edited freely at any time. Whenever you change a structural or numeric field on a declaration
  (stats, vitals, dice, exits, behaviors, etc.) compared to your baseline commit, `gups lint --base main` requires
  incrementing `version` on that prototype (`version = 2`).
- **Transforming Saved Package Attributes (`def migrate`):** On startup or hot reload, any saved instance whose
  `protoVersion` is older than the prototype's new `version` reconciles its fields and, if the package defines a
  top-level `migrate(old_version, attrs)` function, calls it with a dict of the package's own custom attributes by short
  name:

  ```python
  item(
      "relic_blade",
      version = 2,
      name = "an ancient relic blade",
      keywords = ["ancient", "relic", "blade"],
      wear = "wield",
      dice = "2d6",
  )

  def migrate(old_version, attrs):
      # Upgrade v1 "attuned" boolean attribute to v2 integer tier:
      if old_version < 2 and "attuned" in attrs:
          return {"attunement_tier": 1 if attrs["attuned"] else 0}
      return attrs
  ```
- **Verifying Against a Snapshot:** Before deploying a migration to a running world, export a sanitized database
  snapshot and run `gups migrate-check` to verify that schema checks, version bumps, and `migrate()` functions succeed
  with zero unexpected tombstones:

  ```bash
  gups snapshot --sanitized --world ../my-world --out snapshot.db
  gups migrate-check --world ../my-world --snapshot snapshot.db
  ```

### Agent Prompt: Writing Zone Tests & Balance Scenarios

```markdown
Read `template-world/zones/tutorial/tutorial_test.star` and `template-world/docs/tutorials/07_testing_your_zone.md`.
Inspect all `.star` files in `zones/<zone_name>/`.

1. Write a comprehensive `zones/<zone_name>/<zone_name>_test.star` suite that tests:
   - Walking the room graph and unlocking any doors/containers
   - Interacting with every NPC, shop, trainer, or quest hook
   - Engaging key mobs and verifying loot drops and custom `think` or `on_use` hooks
2. Run `gups lint` and `gups test` on `//zones/<zone_name>/...` and fix any issues until both pass cleanly.
```
