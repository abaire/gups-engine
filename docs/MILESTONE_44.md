# Milestone 44: Target Preference, Retargeting and Combat Memory

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, section "Mobs and AI", heading `### Target preference for
  aggressive mobs`.

- **Design Summary:** the engine gives mobs the facts and the hooks needed to choose and switch targets. The world's
  rules decide whom to pick.

  - **One source of truth for what a mob senses.** The `via` argument of `aggressive` is removed. Every "can this mob
    sense X" question (noticing a candidate, and witnessing an event for memory) asks `rules.can_perceive` once per
    channel on which the mob's *effective* sense (`Actor.sense(channel)`: base + traits + affects + equipment) is above
    0, in the world's perception order (`schema.perceptionOrder`). It stops at the first channel that passes. A blinding
    affect therefore stops sight-based noticing automatically. A will-o-wisp is `mob(senses = {"sight": 0, "sound": 0,
    "magic": 5})`, and the world's `can_perceive` decides which events `magic` carries.

  - **`prefer=` on behaviors.** `aggressive(prefer=None)` and `assist(prefer=None)` take a value made of strings, ints,
    bools, and lists or dicts of those (stored as `AttrValue`, so behaviors keep value equality for the version-bump
    lint). The engine gives `prefer` no meaning.

  - **New optional rules export `target_order(ctx)`** (feature `combat`). It is called once per proposal, only when the
    behavior has a `prefer` and at least one candidate. It returns one actor from `ctx.candidates`, or `None` for
    "nobody this think".

    - **ctx fields:**
      - `actor`: the mob.
      - `behavior`: `"aggressive"`, `"assist"` or `"retarget"`.
      - `prefer`: an immutable Starlark copy.
      - `candidates`: the actors that passed the behavior's filters, ascending id.
      - `current`: the mob's primary target for `retarget`, the ally's primary target for `assist`, else `None`.
      - `current_since`: the pulse at which the mob's primary target last changed, for `retarget`; else `None`.
      - `dispositions`: with `standing`, the mob's disposition toward each candidate as a parallel list of ints; else
        `None`.
      - `ally`: the ally being assisted, for `assist`; else `None`.
      - `room`, `pulse`, `rng`.
      - The rules ctx method `memories(...)` (below).
    - **Results:**
      - A behavior without `prefer`, or a world without the export, keeps today's choice: lowest id for `aggressive`;
        the ally's primary target, else the lowest-id foe, for `assist`. `retarget` requires `prefer`.
      - A return that is not a candidate, a wrong type, or a rules error logs a rules diagnostic and falls back to
        today's choice (for `retarget`: no switch).
    - **Plugin seam `addTargetOrder`** runs after the export in plugin-id order and can keep, pick, or pick nobody.
  - **New built-in behavior `retarget(prefer, toward="players", disposition_below=None, chance=100, priority=55)`**
    (feature `combat`).
    - It proposes only while the mob is engaged, with probability `chance` percent per think.
    - **Candidates:**
      1. Every current opponent in the mob's room, with no other filter. Hiding mid-fight doesn't drop the mob's
         attention.
      2. Non-engaged actors in the room that pass `toward`, `disposition_below` and the senses-based notice check, as
         `aggressive` does.
      3. The owned-mob rule applies to both: a pet never picks its owner, the owner's group members, or a pet of any of
         them.
    - **Result:** it calls `target_order`. If the pick is an actor other than the current primary target, it proposes
      `kill <pick>` (`KillAction`: no lag, engages a non-engaged actor and raises the usual `attack` standing event).
      Otherwise it proposes nothing.
  - **Combat memory** (feature `combat`): a transient, never-saved, per-mob record of what the mob has experienced and
    witnessed.
    - **Window:** world option `memory_pulses` (default **0, off**), overridden per mob by `mob(memory_pulses=N)`
      (`None` uses the world's value, `0` turns it off for that mob). The template world sets 1200.
    - **Memory shape:** a memory has `subject` (actor id), `event` (string), `skill` (skill label or null), `target`
      (actor id or null), `first_pulse`, `pulse` (latest), `count`, `amount` (summed), and `channel` (latest; null when
      nothing was perceived).
    - **Merging (anti-flood):** repeats of the same key `(subject, event, skill, target)` merge into one memory: `pulse`
      = now, `count += 1`, `amount += amount`, `channel` = latest. Spam can't push out older memories.
    - **Expiry:** a memory is dropped once `now - pulse > window`. It is pruned lazily when the mob's memory is read or
      written.
    - **Hard cap:** `MEMORY_MAX_ENTRIES = 256` per mob. When over the cap, evict the memory with the smallest `pulse`
      (ties: smallest `first_pulse`, then insertion order).
    - **What the engine records, for a mob whose window is above 0:**
      - **About itself, without a perception check:**
        - `attack`: a fresh, non-coupled engagement the subject started against the mob.
        - `damage`: credited damage, with amount.
        - `arrive`: the mob entered a room, or was spawned. Its subject is the mob itself.
      - **Perception events (§5.7):** every propagated event with an actor (`say`, `emote`, `enter`, `exit`,
        `combat_start`, `combat`, `death`, `shout`, door events, `ctx.emit`) that the mob perceives in
        `deliverPerceptions` becomes a memory with that event name and the event's channel. This adds no perception
        calls beyond the one already made for that mob.
      - **Witnessed in the subject's room, as it happens, through the senses check (`ctx.event` names the event,
        intensity 1):**
        - `enter`: recorded synchronously so a mob thinking later in the same pulse already remembers the arrival (phase
          2 runs before perception delivery in phase 5). The deferred in-room `enter` is then not recorded again.
        - `cast_start` and `cast`: skill and target.
        - `heal`: target and effective amount above 0, including self-heals.
        - `help`: target. A helpful affect on another actor.
        - `kill`: target is the victim. The subject never witnesses its own event.
    - **Only simulated mobs witness.** A mob outside the bubble records only what happens to it.
    - **Who can read and write memory:**
      - The rules ctx gains `memories(subject=None, event=None, since=0)`, about `ctx.actor` (`[]` when `ctx.actor` is
        not a mob).
      - Hook and think ctxs gain `memories(mob, subject=None, event=None, since=0)`, `remember(mob, subject, event,
        skill=None, amount=None, target=None)` and `forget(mob, subject=None, event=None)`, which removes the matching
        memories (all of them when both filters are `None`) and returns how many it removed.
      - Plugins gain `PluginWorld.memories(...)`, `PluginWorld.remember(...)` and `PluginWorld.forget(...)`.
      - Results are newest first (by `pulse` descending, then subject id, then event).
  - **Arrival delay and every other targeting policy are rules.** With `prefer` set, `None` from `target_order` means
    "nobody this think". The template sample implements `{"delay": N}` from the `enter` memory's `first_pulse` (so
    stepping out and back in doesn't reset it) and the mob's own `arrive` memory.
  - **Thrash control is rules.** `ctx.current`, `ctx.current_since` and memory `pulse`, `count` and `amount` let the
    world keep the current target unless a challenger beats it by a margin, or keep a minimum time between switches. The
    template ships a reference threat function.
  - **Long-term grudges stay in standing (§5.14).** Personal memories age out. Faction records (already lowered by
    `kill`) never forget.
  - **Template stealth.** A `hidden` source fails every event on `sight`. `notice` on `sound` passes for a hidden source
    only when the mob has a memory of that source on `sound` within `NOISE_PULSES` (8). A hidden player who speaks,
    opens a door or casts is caught on the mob's next think. Players also stop seeing a hidden actor's movement and
    emotes.
  - **Template `pacify` spell** (`//skills/arcane:pacify`): a sample of `ctx.forget`.
    - Cast on a mob, it rolls against the caster's power, reduced by 10 per level the mob has over the caster. On
      success it ends the mob's fight with the caster (only the caster), makes the mob forget everything about the
      caster, and applies a `pacified` affect for 40 pulses.
    - While `pacified`, the template's `can_perceive` refuses `notice`, so the mob neither starts fights nor retargets.
      `assist` doesn't notice, so a pacified mob still answers an ally's fight.
    - It targets as an `ally` skill, so casting it never engages. Faction and personal standing are untouched, so a
      grudge held there comes back when the affect ends.
  - **Exploits reviewed:**
    - **Memory flood:** closed by merging.
    - **Hide mid-fight:** opponents are always `retarget` candidates.
    - **Doorway dancing against a delay:** the template uses `first_pulse`.
    - **Pets turning on their owner side through `retarget`:** the owned-mob rule applies.
    - **Pacify as a free escape:** it has an 8-pulse warmup that damage interrupts, it can fail, and it frees only the
      caster: groupmates stay remembered and engaged.
    - **Healer at the back:** non-engaged candidates are included, and a hidden healer's spells are heard.
- **Specification Impact:**
  - §3.9: the `memory_pulses` world option.
  - §3.10: the `combat` feature row (`retarget`, `prefer`, `target_order`, combat memory, `memory_pulses`, `memories`,
    `remember`).
  - §4.2: the `mob(...)` example and field notes (`memory_pulses`).
  - §4.5: the ctx query `memories` and the verbs `remember` and `forget`.
  - §4.6: the optional exports list, the rules ctx field list (`behavior`, `prefer`, `candidates`, `current`,
    `current_since`, `dispositions`, `ally`, `memories`), the export table row, and a **`target_order`** paragraph.
  - §4.7: the behavior table (`aggressive` loses `via` and gains `prefer`; `assist` gains `prefer`; new `retarget`), and
    the **Noticing**, **`via`**, **Engagement** and new **Preference** bullets.
  - §4.15: `addTargetOrder`, `PluginWorld.memories`, `PluginWorld.remember` and `PluginWorld.forget`, and the descriptor
    keys.
  - §5.7: the `notice` row and its paragraph.
  - §5.8: a new **Combat memory** subsection.
  - §7.4: an M44 row.
  - `docs/CHANGELOG.md`, `docs/PLUGINS.md`, and the regenerated API files.
- **Decision Record Updates:** None. Do not add `D-xxx` entries for anything this document settles: the spec text below
  is approved.

### Ground rules for every phase

1. **Re-read before editing.** Always re-read the current `SPEC.md`, `CHANGELOG.md`, `Behaviors.kt`,
   `BehaviorDescriptors.kt`, `Declarations.kt`, `Rules.kt` (script), `RulesEngine.kt`, `Ownership.kt`, `PluginHost.kt`
   and `Facade.kt`. Locate code by symbol name, never by the line numbers quoted here. Put the §7.4 row after the
   highest existing milestone row.
2. **TDD** (`AGENTS.md`): write the tests named in each phase first, run them and see them fail, then implement.
3. **Spec edits are pre-approved.** Apply the SPEC text given in each phase directly, adjusting only neighboring wording
   that would otherwise contradict it. Log a `DECISIONS.md` entry only for a genuinely new gap you have to fill.
4. **No spec section numbers or decision ids in code comments**, Kotlin or Starlark.
5. **Versions and compatibility.** Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no
   `@ApiSince`. The engine is unreleased: no migrations or back-compat shims (removing `via` needs no deprecation path).
6. **CHANGELOG:** add bullets to a new `### Target preference, retargeting and combat memory` subsection at the top of
   `## api_version 1 (unreleased)`, above whatever subsection is first there now. Create it in Phase 1; later phases
   append.
7. **API files.** In any phase that changes the script API, plugin API or sample content, regenerate before the phase's
   final `./gradlew check` if a golden or API test fails:
   - `./gradlew :cli:test -Dgups.updateGolden=true` (rewrites `cli/src/test/resources/api.golden.json` and
     `cli/src/test/resources/docs-golden/`).
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout (rewrites `docs/api/api-1.json`).
   - Read the diff: only the symbols the phase names may be new, changed or removed. `ApiDiffTest` must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing entry.
8. **Gradle** runs outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation. `./gradlew check` must pass with zero warnings.

---

## Phase 1: Senses-Based Noticing (Remove `via`)

### Objective & Scope

- **In Scope:**
  - A shared Kotlin helper that asks whether a mob senses an actor for an event, over the mob's effective senses.
  - `aggressive` uses it. `via` is removed from the `Aggressive` data class, the `aggressive()` constructor, the
    descriptor and the plugin descriptor key set.
  - The template's stealth rule and notice test fixtures are updated for the new channel set.
  - Spec text for noticing.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):** `prefer` and
  `target_order` (Phase 2); `retarget` (Phase 3); any memory (Phases 4–5); the template's noise rule that lets a hidden
  actor be heard (Phases 7a–7b); plugin seams (Phase 6).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Perception.kt` (`tellPerceivers` shows the `schema.perceptionOrder` loop and
    the `RulesContext` shape).
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Actor.sense(channel)`).
  - `core/src/test/kotlin/gups/core/world/BehaviorTest.kt`, `BehaviorDescriptorsTest.kt` (test patterns).
  - SPEC §4.7, §5.7.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Behaviors.kt` (`Aggressive`, the private `notices`).
  - `core/src/main/kotlin/gups/core/world/BehaviorDescriptors.kt` (`descriptor()` and the allowed-keys map).
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`aggressive()`).
  - `core/src/test/kotlin/gups/core/world/*` tests that build `Aggressive(via = ...)` (about four uses), plus a new
    `core/src/test/kotlin/gups/core/world/SensesNoticeTest.kt`.
  - `template-world/rules/perception.star`, `template-world/zones/tutorial/notice_testdata.star`,
    `template-world/zones/tutorial/notice_test.star`.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `Assist`, `Guard`, `Follow`, `Pursue` and the other behaviors; persistence; `plugin-api/` sources
  (the plugin descriptor parser lives in `BehaviorDescriptors.kt`); `samples/`.

### Specification & Decision Record Changes

- §4.7 table row for `aggressive`: signature `aggressive(toward="players", unless_level_above=None,
  unless_level_below=None, chance=100, disposition_below=None, priority=60)`. The "What it does" text is unchanged.
- §4.7 replace the **Noticing** and **`via`** bullets with:
  - **Noticing:** a mob notices a candidate when, for some channel on which the mob's effective sense is above 0,
    `rules.can_perceive` (§4.6) passes, asked as the mob about event `notice` with intensity 1 and the candidate as
    `ctx.source`. Channels are asked in the world's perception order (§3.9), stopping at the first that passes, so a mob
    asks at most once per channel it senses. A mob's senses come from `mob(senses=)` and its sense deltas (§5.7): a mob
    with `sight` 0 never notices by sight, and a blinding affect stops it while it lasts. Stealth and invisibility are
    world rules: decide them when the actor hides, keep the result on an affect, and read it in `can_perceive`. Rolling
    inside `can_perceive` gives the mob a fresh roll on every look; `chance` sets how often it looks.
- §5.7 events table, the `notice` row: Channel `each channel the mob senses`. Replace the paragraph after the table
  with: "`whisper` and `tell` are never propagated or sent to `on_perceive`. `notice` is asked by the behaviors that
  look for targets (§4.7) once per candidate and per channel the mob senses, as the mob, about the candidate as
  `ctx.source`; it is never propagated and never sent to `on_perceive`, only used to decide whether the candidate is
  noticed."
- CHANGELOG bullet: "Breaking: `aggressive` no longer takes `via`. A mob notices on every channel it senses (its
  effective `senses` above 0), so declare a deaf or blind mob through `mob(senses=)` instead."

### Implementation Blueprint

- **Signatures & Types** (in `Behaviors.kt`, replacing the private `notices`):

```kotlin
/**
 * The first channel, in the world's perception order, on which [mob] senses [who] for [event]:
 * one `rules.can_perceive` call per channel whose effective sense is above 0. Null when none passes.
 */
internal fun World.sensedChannel(mob: Mob, who: Actor, event: String): Channel?

/** Whether [mob] notices [who] in its own room, as the target-seeking behaviors ask it. */
internal fun World.notices(mob: Mob, who: Actor): Boolean = sensedChannel(mob, who, "notice") != null
```

- `Aggressive` drops the `via: List<Channel>` property. Its constructor order becomes `toward, unlessLevelAbove,
  unlessLevelBelow, chance, dispositionBelow, priority`.

- **Algorithmic Steps & Invariants:**

1. `sensedChannel`: loop `for (ch in schema.perceptionOrder)`. Skip it if `mob.sense(ch) <= 0`. Build the `RulesContext`
   exactly as today's `notices` does (`actor = mob`, `target = mob`, `source = who`, `room = mob.room`, `sourceRoom =
   mob.room`, `channel = ch`, `event = event`, `intensity = 1`, `cost = 0`). Return `ch` on the first
   `rules.canPerceive(c)` that passes; return null after the loop.
2. `Aggressive.propose` calls `world.notices(mob, it)` and keeps it last in the filter chain, so the rules are asked
   only for candidates that passed every cheaper filter.
3. `Declarations.aggressive`: remove the `via` `Param`, the parameter, and its parsing. Passing `via=` must now be
   Starlark's normal "unexpected keyword" error.
4. `descriptor()`: drop `"via"` for `Aggressive`. Remove `"via"` from the `aggressive` allowed-keys set and from the
   descriptor parser. A plugin descriptor carrying `via` is now an invalid descriptor (diagnostic, list unchanged).
5. `template-world/rules/perception.star` `can_perceive`: replace the hidden check with `if ctx.source and
   ctx.source.has_flag("hidden") and (ctx.channel == "sight" or (ctx.event == "notice" and ctx.channel == "sound")):
   return False`, with a comment: a hidden actor can't be seen at all, and can't be noticed by sound either (Phase 7a
   lets recent noise give it away). Keep the rest of the function.
6. `notice_testdata.star`:
   - `test_listener` becomes `senses = {"sight": 0}` with plain `aggressive(toward = "players")`.
   - `test_blind` becomes `senses = {"sight": 0, "sound": 0}` and is described as "senses neither sight nor sound".
   - Add `test_deaf`: `senses = {"sound": 0}`, plain `aggressive(toward = "players")`.

### Test & Documentation Requirements

- **`SensesNoticeTest.kt` (new, using a scripted `RulesEngine` stub that records each `canPerceive` call's channel and
  event):**
  - A default mob (sight 1, sound 1) whose stub refuses `sight` and passes `sound` notices on `sound` after exactly two
    calls, `sight` first.
  - A mob with `sight` 0 makes no `sight` call.
  - A mob with every sense 0 makes no call and notices nothing.
  - A mob blinded by an affect with `sense_deltas = {"sight": -1}` makes no `sight` call while the affect lasts.
  - A declared channel the mob senses is asked after `sight` and `sound`, following `schema.perceptionOrder`.
- **Existing Kotlin tests** that pass `via`: rewrite them to use mob senses.
- **`notice_test.star`:**
  - The watcher test is unchanged and must still pass (hidden now fails on `sound` too).
  - `test_a_blind_mob_never_attacks_a_visible_player` is renamed `test_a_mob_without_senses_never_attacks` (uses
    `test_blind`).
  - New `test_a_blind_mob_hears_a_visible_player` (`test_listener`, player not hidden: attacked).
  - New `test_a_deaf_mob_sees_a_visible_player` (`test_deaf`: attacked).
  - `test_a_listener_attacks_a_hidden_player` is replaced by `test_a_listener_ignores_a_silent_hidden_player`
    (`test_listener` with a hidden player: not attacked after 20 pulses).
- **Docs:** update the `aggressive` KDoc in `Behaviors.kt` (no `via`) and the `Param` docs of `aggressive()`.

### Exit Conditions

- [] `./gradlew :core:test :script:test` passes.
- [] `./gradlew :cli:test` passes (template world lint and tests), after the API regeneration in ground rule 7.
- [] `grep -rnE "(aggressive|assist|retarget|guard|follow|pursue)\(.*via" template-world samples --include='*.star'`
  finds nothing. (`standing_history(via = ...)` in `template-world/rules/standing.star` is unrelated and must stay.)
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 2: `prefer` and the `target_order` Export (`aggressive`, `assist`)

### Objective & Scope

- **In Scope:**
  - `prefer` on `Aggressive` and `Assist` (constructors, data classes, descriptors).
  - `Actor.primaryTargetSince`.
  - New `RulesContext` fields and rules ctx fields.
  - `RulesEngine.targetOrder` and its script implementation.
  - The core `chooseTarget` helper with an (empty) adjuster list.
  - Dry run, feature ownership and spec.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):** `retarget` (Phase 3);
  memory and the rules ctx `memories` method (Phases 4–5); the plugin-facing `addTargetOrder` API (Phase 6; only the
  core adjuster type and `World` list are added here); template samples (Phases 7a–7b).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Rules.kt`: `ownedBehaviors` (the pattern for an optional export with a
    fallback), `RulesExport`, `RULES_EXPORTS` (`RulesExportSpec` with `hasDefault = true`), `RulesCtx` fields
    (`behaviors`, `pet`), `dryRunRules` (the `OWNED_BEHAVIORS` dry-run call).
  - `script/src/main/kotlin/gups/script/Values.kt`: `toAttr`, `fromAttr` (with `mu = null` for an immutable copy).
  - `core/src/main/kotlin/gups/core/world/Owners.kt`: `ownedBehaviorAdjusters` fold (pattern for adjusters).
  - `core/src/main/kotlin/gups/core/world/Combat.kt`: the writes of `primaryTarget` in `startCombat`, the stop paths and
    `disengageAll`. `CombatCommands.kt`: `KillAction.resolve`.
  - SPEC §4.6, §4.7.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`primaryTargetSince`).
  - `core/src/main/kotlin/gups/core/world/Combat.kt`, `CombatCommands.kt` (set `primaryTargetSince` wherever
    `primaryTarget` changes).
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt` (`RulesContext` fields, `withDamageType`, `targetOrder`).
  - `core/src/main/kotlin/gups/core/world/Behaviors.kt` (`Aggressive`, `Assist`, new `TargetChoice`, `chooseTarget`).
  - `core/src/main/kotlin/gups/core/world/BehaviorDescriptors.kt`, `core/src/main/kotlin/gups/core/world/World.kt`
    (`targetOrderAdjusters`).
  - `script/src/main/kotlin/gups/script/Declarations.kt` (`aggressive()`, `assist()`, a shared `preferArg`).
  - `script/src/main/kotlin/gups/script/Rules.kt`, `script/src/main/kotlin/gups/script/Ownership.kt`.
  - Tests: `core/src/test/kotlin/gups/core/world/TargetOrderTest.kt` (new),
    `script/src/test/kotlin/gups/script/TargetOrderExportTest.kt` (new), `BehaviorDescriptorsTest.kt`.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `plugin-api/`, `Perception.kt`, persistence (`primaryTargetSince` is transient), `template-world/`,
  `samples/`.

### Specification & Decision Record Changes

- **§4.7 table rows:**
  - `aggressive(toward="players", unless_level_above=None, unless_level_below=None, chance=100, disposition_below=None,
    prefer=None, priority=60)`: "Looks for a target on each think with probability `chance` percent. Attacks the
    matching target it notices that `target_order` picks, or else the lowest-id one. `combat` feature; the level
    arguments also need `leveling`."
  - `assist(allies, disposition_above=None, prefer=None, priority=50)`: "Attacks an ally's primary target, or else its
    lowest-id opponent; with `prefer`, the opponent `target_order` picks. `combat` feature."
- **§4.7 new bullet after **Disposition**:**
  - **Preference:** `prefer` is a string, int or bool, or a list or dict of those (dict keys are strings), at most 4
    deep and 64 values in all. The engine gives it no meaning: when a behavior has one, it asks the world's
    `target_order` export (§4.6) once per proposal to choose among the candidates that passed the behavior's other
    filters, and plugins' `addTargetOrder` handlers after it (§4.15). `None` from the export means nobody this think.
    Without `prefer`, or without the export, or when the export fails or returns an actor that isn't a candidate, the
    behavior chooses as described in the table. Like every behavior argument, `prefer` is plain data compared by value
    (§7.3).
- **§4.6:**
  - The optional exports sentence becomes "Six exports are optional: `xp_share`, ..., `owned_behaviors`, whose default
    is described in §4.7, `target_order`, without which behaviors choose as §4.7 describes, and `factions`, ...".
  - Add to the ctx field list: `behavior` (in `target_order`, the behavior asking: `"aggressive"`, `"assist"` or
    `"retarget"`; otherwise `None`), `prefer` (in `target_order`, the behavior's `prefer`; otherwise `None`),
    `candidates` (in `target_order`, the actors to choose from, in ascending id; otherwise `None`), `current` (in
    `target_order`, the mob's primary target for `retarget` and the ally's for `assist`, or `None`), `current_since` (in
    `target_order` for `retarget`, the pulse the mob's primary target last changed; otherwise `None`), `dispositions`
    (in `target_order` with the `standing` feature, the mob's disposition toward each candidate, in the same order;
    otherwise `None`), `ally` (in `target_order` for `assist`, the ally being helped; otherwise `None`).
  - Export table row: `| target_order(ctx) | combat | One actor of ctx.candidates for ctx.actor, a mob, to attack, or
    None for nobody (§4.7). |`.
  - New paragraph **`target_order`**: "called by a behavior with `prefer` (§4.7) once per proposal, when it has at least
    one candidate. A return that is not one of `ctx.candidates`, or an error, is logged as a rules diagnostic and the
    behavior chooses as if it had no `prefer`. The dry run calls it once with the throwaway mob as `ctx.actor`, the
    dry-run player as the only candidate, `ctx.behavior = "aggressive"` and `ctx.prefer = "dry_run"`."
- CHANGELOG bullet: "`aggressive(prefer=)` and `assist(prefer=)`, with the optional `target_order(ctx)` rules export
  that picks among the candidates or returns `None` for nobody."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Entities.kt, on Actor next to primaryTarget:
/** Pulse at which [primaryTarget] last changed; transient. */
var primaryTargetSince: Long = 0
  internal set

// Behaviors.kt
sealed interface TargetChoice {
  /** Choose as the behavior would without a preference. */
  data object Default : TargetChoice
  /** Attack nobody this think. */
  data object Nobody : TargetChoice
  data class Pick(val actor: Actor) : TargetChoice
}

/** The question a behavior asks the world's target_order. */
data class TargetOrderCall(
  val mob: Mob,
  val behavior: String,
  val prefer: AttrValue,
  val candidates: List<Actor>,   // ascending id, non-empty
  val current: Actor?,
  val currentSince: Long?,
  val ally: Actor?,
)

fun interface TargetOrderAdjuster {
  fun adjust(world: World, call: TargetOrderCall, choice: TargetChoice): TargetChoice
}

/** Asks the rules, then each adjuster; a pick outside the candidates becomes Default. */
internal fun World.chooseTarget(call: TargetOrderCall): TargetChoice

data class Aggressive(
  val toward: ActorFilter,
  val unlessLevelAbove: Int? = null,
  val unlessLevelBelow: Int? = null,
  val chance: Int = 100,
  val dispositionBelow: Int? = null,
  val prefer: AttrValue? = null,
  val priority: Int = PRIORITY_AGGRESSIVE,
) : Behavior

data class Assist(
  val allies: ActorFilter,
  val dispositionAbove: Int? = null,
  val prefer: AttrValue? = null,
  val priority: Int = PRIORITY_ASSIST,
) : Behavior

// World.kt
var targetOrderAdjusters: List<TargetOrderAdjuster> = emptyList()

// RulesEngine.kt: new RulesContext properties (default null), also copied in withDamageType:
val behavior: String? = null,
val prefer: AttrValue? = null,
val candidates: List<Actor>? = null,
val current: Actor? = null,
val currentSince: Long? = null,
val dispositions: List<Int>? = null,
val ally: Actor? = null,
// RulesEngine interface:
/** The target_order export's choice; Default when not defined or on failure. */
fun targetOrder(c: RulesContext): TargetChoice = TargetChoice.Default

// Declarations.kt
/** A behavior's prefer argument: None, or a str/int/bool/list/dict value; at most 4 deep, 64 values. */
private fun preferArg(v: Any?, what: String): AttrValue?
```

- **Algorithmic Steps & Invariants:**
  1. **`primaryTargetSince`:** at every assignment that changes `primaryTarget` to a different value (including to null)
     in `Combat.kt` and `KillAction.resolve`, set `primaryTargetSince = pulse`. Prefer a small `internal fun
     World.setPrimaryTarget(a: Actor, id: EntityId?)` used at all six sites. It is not saved.
  2. **`preferArg`:**
     - `None` → null.
     - Otherwise convert with `toAttr(v, what, limits)`, which rejects floats, `None` inside lists and dicts, and
       non-string keys.
     - Then walk the result: fail `"$what: nested more than 4 deep"` or `"$what: more than 64 values"`, counting every
       scalar, list and dict node.
     - Add `prefer` `Param`s (named, default `None`, doc "Plain data passed to the world's target_order; None chooses as
       described.") to `aggressive()` and `assist()`, placed just before `priority`.
  3. **`chooseTarget(call)`:**
     - Build `RulesContext(this, actor = call.mob, room = call.mob.room, behavior, prefer, candidates, current,
       currentSince, ally, dispositions = if (Feature.STANDING in features) candidates.map { disposition(call.mob, it) }
       else null)`.
     - `var choice = rules.targetOrder(c)`, then fold `targetOrderAdjusters`. Each adjuster is called in a `try`; a
       thrown exception or a `Pick` whose actor is not in `call.candidates` (identity) records a `plugin` diagnostic
       (mirror how `ownedBehaviorAdjusters` failures are recorded in `Owners.kt`) and keeps the previous choice.
     - Return the choice.
  4. **`Aggressive.propose`:**
     - Compute `candidates = world.others(mob).filter { <today's filters, notices last> }` (in actor-index order, which
       is ascending id).
     - If empty → null. If `prefer == null` → first candidate.
     - Else `when (val ch = world.chooseTarget(TargetOrderCall(mob, "aggressive", prefer, candidates, null, null,
       null)))`: `Default` → first candidate, `Nobody` → null, `Pick` → `ch.actor`.
     - Note that with `prefer`, every candidate is checked with `notices` (no early exit); without it, keep
       `firstOrNull` so the cost is unchanged.
  5. **`Assist.propose`:**
     - Keep the ally loop. For the first ally with a non-empty `foes` list: if `prefer == null`, keep today's pick.
     - Otherwise call `chooseTarget` with `behavior = "assist"`, `candidates = foes` (sorted by id), `current =
       foes.firstOrNull { it.id == ally.primaryTarget }`, `ally = ally`.
     - `Default` → today's pick; `Nobody` → return null (do not try further allies); `Pick` → that foe.
  6. **Script `targetOrder` override in `Rules.kt`:**
     - `if (!defines(RulesExport.TARGET_ORDER)) return TargetChoice.Default`.
     - `run(RulesExport.TARGET_ORDER, c, ctxArgs(c)) { v -> ... }`: `None` → `Nobody`. An `ActorView` whose actor is in
       `c.candidates` (identity) → `Pick`. Otherwise `fail("rules.target_order return value: must be one of
       ctx.candidates or None, got ...")`.
     - A null result (the run failed) → `Default`.
     - Add `TARGET_ORDER("target_order")` to `RulesExport` and a `RulesExportSpec` (`hasDefault = true`, `returnType =
       "actor"`, `ctxFields = listOf("actor", "behavior", "prefer", "candidates", "current", "current_since",
       "dispositions", "ally", "room", "pulse", "rng")`).
  7. **`RulesCtx` fields** `behavior`, `prefer` (`fromAttr(it, null)`, else `None`), `candidates` (immutable list of
     views), `current`, `current_since`, `dispositions` (immutable list of ints, or `None`), `ally`. Mirror the `pet`
     and `behaviors` field style.
  8. **Ownership:** `owns(Feature.COMBAT, "rules:target_order", "aggressive.prefer", "assist.prefer")`, plus the new ctx
     fields if `Ownership.kt` registers rules ctx fields (check how `ctx.behaviors` is registered and mirror it).
  9. **Dry run:** in `dryRunRules`, beside the `OWNED_BEHAVIORS` call, with `Feature.COMBAT` and a dry mob, call
     `rules.targetOrder(...)` as the spec paragraph states.
  10. **Descriptors:**
      - Add `prefer` to `descriptor()` for `Aggressive` and `Assist` only when non-null, converted to plain values
        (`Long`, `String`, `Boolean`, `List`, `Map<String, Any?>`).
      - Add `"prefer"` to the allowed keys of both.
      - The descriptor parser converts plain values back to `AttrValue`, rejecting anything else as an invalid
        descriptor.

### Test & Documentation Requirements

- **`TargetOrderTest.kt` (core, with a stub `RulesEngine` whose `targetOrder` returns a scripted choice and records
  calls):**
  - Without `prefer`, `targetOrder` is never called and the lowest id is attacked.
  - With `prefer`: `Pick(b)` attacks `b`; `Nobody` proposes nothing; `Default` attacks the lowest id.
  - The candidates passed exclude actors failing `toward`, the level band, owner side, disposition or notice.
  - `assist` with `prefer` passes the ally's foes and `current = ally's primary target`; `Nobody` proposes nothing.
  - An adjuster that returns a non-candidate is ignored with a diagnostic.
  - `primaryTargetSince` updates on `kill`, on engagement and on disengagement.
- **`TargetOrderExportTest.kt` (script, loading a small rules package):**
  - The ctx fields have the documented values, including `dispositions` with `standing` on and `None` without.
  - Returning a non-candidate or a string logs a rules diagnostic and falls back.
  - `prefer` round-trips a nested dict unchanged.
  - `prefer` rejects a float, a 5-deep value, and 65 values.
  - Two `aggressive(prefer = {"a": 1})` are equal and `prefer = {"a": 2}` differs (the version-bump lint sees it).
- **`BehaviorDescriptorsTest.kt`:** `prefer` appears in descriptors and parses back.

### Exit Conditions

- [] `./gradlew :core:test :script:test` passes.
- [] `./gradlew :cli:test` passes after the API regeneration (new symbols: `prefer` params, `target_order`, rules ctx
  fields).
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 3: The `retarget` Behavior

### Objective & Scope

- **In Scope:** the `Retarget` data class, the `retarget()` constructor, descriptor and allowed keys, feature ownership,
  the descriptor parser, and spec.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):** memory (Phases 4–5);
  plugin seams (Phase 6); template content (Phases 7a–7b). Do not change `aggressive` or `assist` again.

### Context Working Set

- **Read / Reference Only:** `Behaviors.kt` (`Aggressive`, `Assist`, `others`, `below`, `notices`, `chooseTarget`),
  `Owners.kt` (`isOwnerSide`), `Combat.kt` (`opponentsOf`), `CombatCommands.kt` (`KillAction`), `Declarations.kt`
  (`aggressive()` as the constructor pattern, `priorityArg`, `preferArg`), SPEC §4.7.
- **Files to Modify / Create:** `core/src/main/kotlin/gups/core/world/Behaviors.kt`, `BehaviorDescriptors.kt`,
  `script/src/main/kotlin/gups/script/Declarations.kt` (including the behavior-name list near the other `"aggressive"`
  entry), `script/src/main/kotlin/gups/script/Ownership.kt` (add `"retarget"` to the `owns(Feature.COMBAT, "aggressive",
  ...)` behavior list), `core/src/test/kotlin/gups/core/world/RetargetTest.kt` (new), `BehaviorDescriptorsTest.kt`,
  `docs/SPEC.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `plugin-api/`, `Perception.kt`, `template-world/`, `samples/`.

### Specification & Decision Record Changes

- §4.7 table, new row after `assist`: `| retarget(prefer, toward="players", disposition_below=None, chance=100,
  priority=55) | 55 | While engaged, with probability chance percent on each think, asks target_order (§4.6) whom to
  fight and attacks its pick if that is not its primary target. combat feature. |`
- §4.7 replace the **Engagement** bullet: "**Engagement:** behaviors other than `flee` and `retarget` propose nothing
  while engaged; `retarget` proposes only while engaged."
- §4.7 new bullet after **Preference**:
  - **`retarget`:** its candidates are every actor the mob is engaged with in its room, with no other filter, and the
    actors in the room it isn't engaged with that match `toward`, pass `disposition_below` and are noticed, all in
    ascending id. `prefer` is required. With no candidates, or when `target_order` returns `None`, isn't defined, fails,
    or picks the primary target, it proposes nothing. Otherwise it proposes `kill` on the pick, which costs no lag and
    engages an actor not yet in the fight. An owned mob's `retarget` never picks its owner, its owner's group members,
    or a pet of any of them.
- §4.7, the **Owned mobs** bullet: "an owned mob's `aggressive`, `assist` and `retarget` never pick ...".
- CHANGELOG bullet: "New behavior `retarget(prefer, ...)`: an engaged mob asks `target_order` whom to fight and switches
  to its pick, including an actor not yet in the fight."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
const val PRIORITY_RETARGET = 55

/** `retarget(prefer, ...)`: while engaged, switches to the opponent or bystander target_order picks. */
data class Retarget(
  val prefer: AttrValue,
  val toward: ActorFilter = ActorFilter("players"),
  val dispositionBelow: Int? = null,
  val chance: Int = 100,
  val priority: Int = PRIORITY_RETARGET,
) : Behavior
```

- **Algorithmic Steps & Invariants (`Retarget.propose`):**
  1. If `Feature.COMBAT !in world.features` or `!mob.isEngaged`, return null. If `!world.rng.passes(chance)`, return
     null.
  2. `others = world.others(mob)` (already excludes the dead, the unsimulated and the mob itself).
  3. `engaged = others.filter { it.id in mob.fighting && !world.isOwnerSide(mob, it) }`.
  4. `bystanders = others.filter { it.id !in mob.fighting && toward.matches(it) && !world.isOwnerSide(mob, it) &&
     world.below(mob, it, dispositionBelow) && world.notices(mob, it) }`. Keep `notices` last.
  5. `candidates = (engaged + bystanders).sortedBy { it.id }`. If empty, return null.
  6. `current = mob.primaryTarget?.let { id -> others.firstOrNull { it.id == id } }`.
  7. Call `world.chooseTarget(TargetOrderCall(mob, "retarget", prefer, candidates, current, mob.primaryTargetSince,
     null))`. `Default` or `Nobody` → null. `Pick(x)` with `x === current` → null. Otherwise return
     `Proposal("${world.commands.nameOf("kill")} ${x.keywords.first()}", priority, "retarget", KillAction(x))`.
  8. **`retarget()` constructor:**
     - `prefer` is a required named param, `None` rejected: `"retarget(prefer): required; retarget never switches
       without it"`.
     - `toward` is parsed with `actorFilter(..., pkgPath)`.
     - `disposition_below` uses the same `need(...)` gating and -100..100 check as `aggressive`, registered as
       `"retarget.disposition_below"`; add `owns(Feature.STANDING, "retarget.disposition_below")` next to `aggressive`'s
       entry.
     - `chance` via `percent`, `priority` via `priorityArg(priority, PRIORITY_RETARGET, ...)`.
     - Call `need("retarget", "retarget()")` like `aggressive`.
  9. **Descriptor** `kind = "retarget"`, keys `prefer`, `toward`, `disposition_below` (only when set), `chance`,
     `priority`, plus the parser branch.

### Test & Documentation Requirements

- **`RetargetTest.kt` (stub rules as in Phase 2):**
  - It proposes nothing while not engaged, and nothing with `chance = 0`.
  - Candidates include an engaged opponent that fails `toward` and an engaged opponent the mob can't notice (stub
    refuses `notice`).
  - A non-engaged bystander is included only if it matches `toward` and is noticed.
  - Picking the current primary target proposes nothing; picking another proposes `kill` and, after the action runs,
    `primaryTarget` is the pick and the pick is engaged.
  - An owned mob never gets its owner or the owner's groupmate as a candidate.
  - `Nobody` and `Default` propose nothing.
  - `flee` (100) still beats it. While engaged, the proposal outranks `assist` (50).
- **Script test** (extend `TargetOrderExportTest.kt`): `retarget()` without `prefer` is a load error with the message
  above; `ctx.behavior == "retarget"` and `ctx.current_since` are set.
- **`BehaviorDescriptorsTest.kt`:** a round trip.

### Exit Conditions

- [] `./gradlew :core:test :script:test` passes.
- [] `./gradlew :cli:test` passes after the API regeneration (new symbol: `retarget`).
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 4a: Combat Memory Model and Self Events

### Objective & Scope

- **In Scope:**
  - The memory model on `Mob`, `World.remember`, `World.memoriesOf` and `World.memoryPulsesOf`.
  - The world option `memory_pulses` and `mob(memory_pulses=)`.
  - The self events only: `attack`, `damage` and `arrive`.
  - Spec §3.9, §4.2, and the §5.8 subsection with only its self-event bullets.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):**
  - Perception recording and `witness` (Phase 4b).
  - Any script or plugin access (`ctx.memories`, `ctx.remember`, rules ctx `memories`, `PluginWorld.*`: Phases 5–6).
  - Template changes (Phases 7a–7b).
  - Saving memory (never).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Contributions.kt` (a transient per-actor ledger: the pattern to mirror).
  - `core/src/main/kotlin/gups/core/world/Standing.kt` (`pruneStanding`, history cutoffs).
  - `script/src/main/kotlin/gups/script/WorldModule.kt` (`standing_history_pulses` parsing: the world-option pattern).
  - `Declarations.kt` (the `mob()` builder's `senses` parsing: the mob-field pattern).
  - SPEC §3.9, §4.2, §5.8.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Memory.kt` (new).
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Mob.memories`).
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (`MobProto.memoryPulses`).
  - `core/src/main/kotlin/gups/core/world/Memories.kt` (new: `remember`, `memoriesOf`, `memoryPulsesOf`).
  - `core/src/main/kotlin/gups/core/world/World.kt` (`memoryPulses`).
  - Recording call sites:
    - `Combat.kt`: `startCombat`.
    - `Contributions.kt`: `recordDamage`.
    - `Movement.kt`: mob arrival.
    - The mob spawn path: find where a spawned mob is placed and `on_spawn` is dispatched.
  - `script/src/main/kotlin/gups/script/WorldModule.kt`, `Declarations.kt` (`mob(memory_pulses=)`), `Ownership.kt`.
  - `core/src/test/kotlin/gups/core/world/MemoryTest.kt` (new), `script/src/test/kotlin/gups/script/MemoryDeclTest.kt`
    (new).
  - `docs/SPEC.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:**
  - `Persistence.kt`, `Restore.kt` and the database schema (memory is never saved).
  - `Perception.kt`, `SkillCommands.kt`, `Behaviors.kt`.
  - `plugin-api/`, `template-world/`, `samples/`.

### Specification & Decision Record Changes

- **§3.9 new bullet after `standing_history_pulses`:** "**`memory_pulses`:** how long a mob keeps a combat memory (§5.8)
  after it last happened, in pulses; default 0, which keeps none. An int of at least 0. A mob's `memory_pulses`
  overrides it. It needs the `combat` feature, and isn't recorded per database."
- **§4.2:** in the `mob(...)` example add `memory_pulses=None, # combat memory window; None uses the world's (§5.8)`
  after `senses=`, and a field note: "**Mob memory:** `mob(memory_pulses = n)` sets how long this mob keeps combat
  memories (§5.8), overriding the world's `memory_pulses`; `0` keeps none. Needs `combat`."
- **§5.8 new subsection after the think-cycle bullets** (Phase 4b adds two more bullets before the last one):

  > **Combat memory** (`combat` feature). Each mob keeps a transient record of what it has experienced and witnessed,
  > never saved and gone when the mob leaves the world. Its window is the mob's `memory_pulses`, or else the world's
  > (§3.9); with a window of 0 the mob records nothing.
  >
  > - **A memory** is about one subject (an actor) and one event, with an optional skill and target. Repeats of the same
  >   subject, event, skill and target merge into one memory: `first_pulse` stays, `pulse` becomes the latest, `count`
  >   goes up by one, `amount` adds up, and `channel` is the latest. A memory is forgotten once more than the window has
  >   passed since its `pulse`. A mob keeps at most 256 memories; past that the one with the oldest `pulse` is
  >   forgotten.
  > - **About itself,** with no perception check: `attack` (the subject started a fight with it), `damage` (with the
  >   amount, credited as for contributions), and `arrive` (the mob entered a room or was spawned; the subject is the
  >   mob itself).
  > - Scripts and plugins read memory and add to it (§4.5, §4.6, §4.15).

- CHANGELOG bullet: "Combat memory: with `module(memory_pulses=)` or `mob(memory_pulses=)`, a mob remembers who attacked
  and damaged it and when it arrived, merged per subject and event and forgotten after the window."

### Implementation Blueprint

- **Signatures & Types:** see the block below.

```kotlin
// core/model/Memory.kt
const val MEMORY_MAX_ENTRIES = 256

data class MemoryKey(val subject: EntityId, val event: String, val skill: Label?, val target: EntityId?)

class Memory(val key: MemoryKey, val firstPulse: Long) {
  var pulse: Long = firstPulse
    internal set
  var count: Int = 1
    internal set
  var amount: Long = 0
    internal set
  var channel: Channel? = null
    internal set
}

// Entities.kt, on Mob:
/** Combat memories by key, in insertion order; transient, never saved. */
internal val memories: LinkedHashMap<MemoryKey, Memory> = LinkedHashMap()

// Protos.kt, MobProto: val memoryPulses: Long? = null
// World.kt constructor property beside standingHistoryPulses: var memoryPulses: Long = 0

// core/world/Memories.kt
/** The mob's memory window in pulses; 0 without the combat feature. */
fun World.memoryPulsesOf(mob: Mob): Long

/** Records or merges a memory; does nothing when the window is 0 or the mob is not in the world. */
fun World.remember(
  mob: Mob, subject: Actor, event: String,
  skill: Label? = null, amount: Long = 0, target: Actor? = null, channel: Channel? = null,
)

/** The mob's live memories, newest first, filtered; prunes expired ones first. */
fun World.memoriesOf(mob: Mob, subject: Actor? = null, event: String? = null, since: Long = 0): List<Memory>

/** Removes the mob's memories about [subject] and/or named [event]; all of them when both are null. */
fun World.forget(mob: Mob, subject: EntityId? = null, event: String? = null): Int
```

- **Algorithmic Steps & Invariants:**
  1. **`memoryPulsesOf`:** `if (Feature.COMBAT !in features) 0 else mob.proto.memoryPulses ?: memoryPulses`.
  2. **`prune(mob, window)`** (private): if `window == 0`, clear. Otherwise remove every memory with `pulse < this.pulse
     \- window`.
  3. **`remember`:**
     - Return if `!mob.inWorld` or the window is 0. Prune.
     - Build the key with `subject.id` and `target?.id`. If the key is present, merge: `pulse = this.pulse`, `count +=
       1`, `amount += amount`, `channel = channel ?: existing.channel`.
     - Otherwise insert a new `Memory(key, this.pulse)` with `amount` and `channel`.
     - While `size > MEMORY_MAX_ENTRIES`, remove the entry with the smallest `(pulse, firstPulse)`, earliest inserted on
       ties.
  4. **`memoriesOf`:** prune, filter by `subject?.id`, `event` and `pulse >= since`, then sort by `pulse` descending,
     `key.subject` ascending, `key.event` ascending.
  5. **`forget`:** prune, then remove every memory whose key matches both given filters (a null filter matches
     anything). Return the number removed, not counting what the prune dropped. The subject is an id so a departed actor
     can still be forgotten.
  6. **Recording points:**
     - **`startCombat`:** inside `if (fresh)`, beside the `!coupled` standing event: `(b as? Mob)?.let { remember(it, a,
       "attack") }`.
     - **`recordDamage(victim, source, amount)`:** after crediting, `(victim as? Mob)?.let { remember(it, source,
       "damage", amount = amount.toLong()) }`.
     - **Arrival:** synchronously, as soon as a mob is placed in its new room (`Movement.kt`, right after the room
       change, before any hooks that could make it think) and after a mob is spawned into the world: `remember(mob, mob,
       "arrive")`.
  7. **World option:** parse `memory_pulses` in `WorldModule.kt` exactly like `standing_history_pulses`: `need(...)`
     gated as `module.memory_pulses`, an int ≥ 0 (`"module(memory_pulses): must be at least 0"`), default 0. Pass it
     into `World.memoryPulses`.
  8. **Mob field:** parse `memory_pulses` in `mob()` as an optional int ≥ 0 into `MobProto.memoryPulses`.
     - Ownership: `owns(Feature.COMBAT, "module.memory_pulses", "mob.memory_pulses")`.
     - Prototype equality and the version-bump lint see it automatically if `MobProto` is a data class (confirm).

### Test & Documentation Requirements

- **`MemoryTest.kt` (core):**
  - With window 0 nothing is recorded.
  - `attack` on a fresh engagement; no `attack` for a coupled owner engagement.
  - `damage` accumulates `amount` and `count` in one memory.
  - Merging keeps `first_pulse`.
  - Expiry after the window, measured from `pulse`.
  - The cap: 300 distinct keys (planted with `remember`) leave 256, the oldest `pulse` evicted.
  - `arrive` on move and on spawn.
  - With `combat` off, nothing is recorded.
  - The mob override beats the world value, and an override of 0 turns memory off.
  - `forget` by subject, by event, by both, and with neither, each returning the count removed. Forgetting a subject
    leaves other subjects' memories, including memories whose `target` is that subject.
- **`MemoryDeclTest.kt` (script):**
  - `module(memory_pulses = -1)` and `mob(memory_pulses = -1)` are load errors.
  - Both are refused without `combat`.

### Exit Conditions

- [] `./gradlew :core:test :script:test` passes.
- [] `./gradlew :cli:test` passes after the API regeneration (new symbols: `module.memory_pulses`, `mob.memory_pulses`).
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 4b: Perceived and Witnessed Memories

### Objective & Scope

- **In Scope:**
  - Recording perceived §5.7 events in `deliverPerceptions`.
  - `World.witness` and its call sites: `enter`, `cast_start`, `cast`, `heal`, `help`, `kill`.
  - The remaining §5.8 bullets.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):** script and plugin access
  (Phases 5–6); template changes (Phases 7a–7b). Do not change the memory model from Phase 4a.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Memories.kt` (Phase 4a).
  - `Behaviors.kt` (`sensedChannel`, Phase 1).
  - SPEC §5.1 (phase order), §5.7, §5.8.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Memories.kt` (add `witness`).
  - Call sites:
    - `Perception.kt`: `deliverPerceptions`.
    - `Movement.kt` and `Login.kt`: beside each `perceive("enter", ...)`.
    - `Combat.kt`: `heal`, the affect-apply help branch, and the `kill` standing site.
    - `SkillCommands.kt`: beside the `ON_CAST_START` and `ON_CAST` dispatches.
  - `core/src/test/kotlin/gups/core/world/MemoryWitnessTest.kt` (new).
  - `docs/SPEC.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** the model and parsing from Phase 4a, `Behaviors.kt`, `script/`, `plugin-api/`, `template-world/`,
  `samples/`.

### Specification & Decision Record Changes

- **§5.8 Combat memory**, insert before the "Scripts and plugins" bullet:

  > - **Perceived events:** each event with an actor that reaches the mob and that `rules.can_perceive` lets it perceive
  >   (§5.7) is a memory named after the event, with the channel it was perceived on. An `enter` in the mob's own room
  >   is recorded as it happens instead (below), so a mob that thinks later in the same pulse already remembers it.
  > - **Witnessed in the subject's room, as it happens:** `enter` (the subject came into the room), `cast_start` and
  >   `cast` (the skill and its target), `heal` (the target and the effective amount, above 0), `help` (the target of a
  >   helpful affect another actor applied) and `kill` (the victim as target). A mob witnesses one when
  >   `rules.can_perceive`, asked as the mob about that event with the subject as `ctx.source` and intensity 1, passes
  >   on a channel it senses, in perception order. The subject never witnesses its own event, and only simulated mobs
  >   witness.

- CHANGELOG bullet: "Combat memory also records the events a mob perceives, and the entrances, casts, heals, helpful
  affects and kills it witnesses in its room."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// core/world/Memories.kt
/** Has every simulated in-world mob with memory in [room], except [subject], witness [event]. */
internal fun World.witness(
  room: Room, subject: Actor, event: String,
  skill: Label? = null, amount: Long = 0, target: Actor? = null,
)
```

- **Algorithmic Steps & Invariants:**
  1. **`witness`:**
     - For each `a` in `room.actorIndex.values` that is a `Mob`, not `subject`, `inWorld`, `isSimulated(a)`, and has
       `memoryPulsesOf(a) > 0`: `val ch = sensedChannel(a, subject, event) ?: continue`.
     - Then `remember(a, subject, event, skill, amount, target, ch)`.
     - `sensedChannel` uses `a.room` as both room and source room, which matches this room.
  2. **Call sites** (each only when the actors involved are in the world):
     - **`enter`:** beside every `perceive("enter", Channel.SIGHT, to, actor)` (in `Movement.kt` and `Login.kt`), call
       `witness(to, actor, "enter")` synchronously.
       - Why: mob thinks happen in phase 2 and perception delivery in phase 5. Without this, a mob acting later in the
         same pulse than the arriving player would have no `enter` memory yet.
     - **`heal`:** when `source != null && effective > 0`, `witness(target.room, source, "heal", amount =
       effective.toLong(), target = target)`. Self-heals included.
     - **Affect apply:** in the non-bane branch where `source !== target`, `witness(target.room, source, "help", target
       = target)`.
     - **Kill:** at the `"kill"` standing event site, with a killer, `witness(<the room the victim died in>, killer,
       "kill", target = victim)`. Capture the room before removal if needed.
     - **Casts:** beside the `ON_CAST_START` dispatch, `witness(caster.room, caster, "cast_start", skill = skill.label,
       target = target)`. Beside the `ON_CAST` dispatch, `witness(caster.room, caster, "cast", skill = skill.label,
       target = target)`. `target` is the cast's actor target or null.
  3. **Perception (`deliverPerceptions`):**
     - Add `val records = a is Mob && ev.actor != null && memoryPulsesOf(a) > 0 && !(ev.event == "enter" &&
       d.reach.origin === a.room)`, and include it in the "only ask when used" condition (`if (!runsHook && !isTold &&
       !records) continue`).
     - After `rules.canPerceive(c)` passes: `if (records) remember(a as Mob, ev.actor!!, ev.event, channel =
       ev.channel)`.
     - The subject is `ev.actor` even if it has since left the world. `remember` keys by id.

### Test & Documentation Requirements

- **`MemoryWitnessTest.kt` (core, with a stub `can_perceive`):**
  - **Heal:** a heal of an opponent is witnessed by a mob in the room, with target and amount. A stub refusing every
    channel means nothing is witnessed.
  - **Declared-channel-only mob:** a mob whose only sense above 0 is a declared channel, and whose stub passes only that
    channel for event `cast`, records the cast (skill, target, that channel) and records no `heal`.
  - **The subject doesn't witness itself.**
  - **Cross-room `say`:** a `say` perceived from the next room records `say` with channel `sound`.
  - **Merging:** 50 `say`s in the room give one memory with `count = 50`, and an earlier `attack` memory survives.
  - **Same-pulse `enter`:** a player entering is remembered immediately (before phase 5) with `count = 1`, and isn't
    recorded a second time when phase 5 delivers the same `enter`.
  - **Bubble:** a mob outside the bubble witnesses nothing.
  - **No extra perception calls:** with every mob at window 0, `deliverPerceptions` makes no `can_perceive` call for a
    mob without `on_perceive`.

### Exit Conditions

- [] `./gradlew :core:test` passes.
- [] `./gradlew :cli:test` passes (every shipped world has window 0, so its tests are unchanged).
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 5: Memory in Scripts and Rules

### Objective & Scope

- **In Scope:** hook and think ctx `memories(...)`, `remember(...)` and `forget(...)`; the rules ctx `memories(...)`;
  the Starlark memory struct; spec §4.5 and §4.6.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):** plugin access (Phase 6);
  template use (Phases 7a–7b).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`: `standingHistory` (verb/query pattern, `actorArg`, `mobArg`,
    `StructValue`), `setSkill` (skill-label argument resolution).
  - `script/src/main/kotlin/gups/script/Rules.kt`: `RulesCtx.standingHistory` and `factionsOf` (rules ctx method
    pattern, `view(frame, e)`).
  - SPEC §4.5, §4.6.
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`, `Rules.kt`, `Ownership.kt`, `DocExamples.kt`.
  - A shared helper (put it in `Views.kt` or a new `script/src/main/kotlin/gups/script/MemoryValues.kt`): `internal fun
    memoryValue(frame: Frame, m: Memory): StructValue`.
  - `script/src/test/kotlin/gups/script/MemoryCtxTest.kt` (new).
  - `docs/SPEC.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `core/` (except fixing a bug you find in Phase 4a or 4b code, with a test), `plugin-api/`,
  `template-world/`, `samples/`.

### Specification & Decision Record Changes

- **§4.5 query table:** add `memories(mob, subject=None, event=None, since=0)` to the Queries row.
- **§4.5 verb list:** add `remember(mob, subject, event, skill=None, amount=None, target=None)` and `forget(mob,
  subject=None, event=None)`.
- **§4.5 new bullets:**
  - **`memories`:** the live combat memories (§5.8) of `mob`, newest first (latest `pulse`, then subject id, then
    event), optionally only those about `subject`, named `event`, or with `pulse` at or after `since`. Each is a struct
    with `subject` and `target` (actor views, or `None` when absent or no longer in the world), `event`, `skill` (an
    absolute skill label, or `None`), `first_pulse`, `pulse`, `count`, `amount` and `channel` (a channel name, or
    `None`). A mob with no memory window has none. Needs `combat`.
  - **`remember`:** adds to `mob`'s combat memory as the engine does, merging with a memory of the same subject, event,
    skill and target; returns `None` and does nothing when the mob keeps no memory. `event` matches `[a-z][a-z0-9_]*`
    and may be a built-in name. `skill` is a skill label (absolute or relative to the calling package) and needs
    `skills`. `amount` is an int of at least 0. The memory's channel is `None`. Needs `combat`; any package may call it.
  - **`forget`:** removes `mob`'s combat memories about `subject` (an actor view), named `event`, or both; with neither
    it removes them all. Returns the number removed. A charm, a pacify spell or a bribe uses it to make a mob let go of
    someone. It changes no standing (§5.14). Needs `combat`; any package may call it.
- **§4.6 rules ctx list:** add "the helper query `memories(subject = None, event = None, since = 0)`, the combat
  memories of `ctx.actor` as `ctx.memories` gives them (§4.5), or `[]` when `ctx.actor` is not a mob".
- CHANGELOG bullets: "`ctx.memories(mob, ...)`, `ctx.remember(mob, subject, event, ...)` and `ctx.forget(mob,
  subject=None, event=None)`" and "the rules ctx `memories(...)`, so `target_order` and `can_perceive` can read what the
  mob remembers".

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Ctx.kt
@StarlarkMethod(name = "memories", ...) fun memories(mob: Any, subject: Any, event: Any, since: Any): Any
@StarlarkMethod(name = "remember", ..., useStarlarkThread = true)
fun remember(mob: Any, subject: Any, event: Any, skill: Any, amount: Any, target: Any, thread: StarlarkThread): Any
@StarlarkMethod(name = "forget", ...) fun forget(mob: Any, subject: Any, event: Any): Any
// Rules.kt, RulesCtx
@StarlarkMethod(name = "memories", ...) fun memories(subject: Any, event: Any, since: Any): Any
```

- **Algorithmic Steps & Invariants:**
  1. **`memoryValue`:** a `StructValue(linkedMapOf(...))` with the fields in the spec order. `subject` and `target`
     resolve their ids through `world.actor(id)` and give a view only if `inWorld`, else `None`. `skill` is `label.v` or
     `None`, `channel` is `channel.name` or `None`, and ints use `sInt`.
  2. **`ctx.memories`:**
     - `mob` through `mobArg`. `subject` is `None` or an actor view. `event` is `None` or a string. `since` is an int
       coerced to ≥ 0.
     - Return an immutable list of `memoryValue`s from `world.memoriesOf(...)`.
     - It is a read: allowed in `think` and every hook.
  3. **`ctx.remember`:**
     - Inside `verb("remember")`. `mob` through `mobArg`, `subject` through `actorArg`.
     - `event` must match `Regex("[a-z][a-z0-9_]*")` (`"remember(event): must match [a-z][a-z0-9_]*"`).
     - A non-`None` `skill` resolves to a skill prototype label as `set_skill` does, and fails if `skills` is off.
     - `amount` is `None` → 0, else an int ≥ 0. `target` is `None` or `actorArg`.
     - Call `world.remember(...)` and return `None`.
  4. **`ctx.forget`:** inside `verb("forget")`. `mob` through `mobArg`, `subject` `None` or `actorArg`, `event` `None`
     or a string. Return `sInt(world.forget(mob, subject?.id, event))`.
  5. **Rules ctx `memories`:** `field { ... }`. If `c.actor !is Mob`, return an empty list. Otherwise as in step 2, with
     `mob = c.actor`. Reading memory doesn't make an export non-deterministic, so it is allowed in every export.
  6. **Ownership:** `owns(Feature.COMBAT, "ctx.memories", "ctx.remember", "ctx.forget")`, plus the rules ctx helper the
     way rules ctx helpers are registered (check `standing_history`'s registration). Add a `DocExamples.kt` entry for
     each new symbol.

### Test & Documentation Requirements

- **`MemoryCtxTest.kt`:**
  - `remember` then `memories` round-trips every field.
  - Filters work: subject, event, since.
  - Newest-first order.
  - A departed subject shows as `None`.
  - Bad `event`, negative `amount`, an unknown skill, and a player as `mob` are errors.
  - `remember` on a mob with window 0 is a no-op.
  - `forget` with a subject removes only that subject's memories and returns the count; with an event only that event;
    with neither, everything; a player as `mob` is an error.
  - The rules ctx `memories` inside a `target_order` sees memories added by `ctx.remember`. Inside `can_perceive` for a
    player actor it returns `[]`.
  - Without `combat`, the three ctx names are refused at load (feature ownership).

### Exit Conditions

- [] `./gradlew :script:test` passes.
- [] `./gradlew :cli:test` passes after the API regeneration (new symbols: `ctx.memories`, `ctx.remember`, `ctx.forget`,
  the rules ctx `memories`).
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 6: Plugin Seams

### Objective & Scope

- **In Scope:** `PluginApi.addTargetOrder` with its call and choice types, wired into `World.targetOrderAdjusters`;
  `PluginWorld.memories`, `PluginWorld.remember` and `PluginWorld.forget`; §4.15 and `docs/PLUGINS.md`.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):** template content (Phase
  7). No new core behavior: the adjuster type and `World` list exist since Phase 2, memory since Phases 4a and 4b.

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`: `addOwnedBehaviors`, `PluginOwnedBehaviorsCall`.
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt`: the `OwnedBehaviorAdjuster` adapter.
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`: `addOwnedBehaviors` and where `allAdjusters` is
    assigned to `World`.
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt` and `host/Facade.kt`: `raiseStandingEvent` and `standing` for
    the facade pattern.
  - `docs/PLUGINS.md`, SPEC §4.15.
- **Files to Modify / Create:** `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`, `Facade.kt`,
  `host/Adapters.kt`, `host/PluginHost.kt`, `host/Facade.kt`, plugin-api tests (new
  `plugin-api/src/test/kotlin/gups/pluginapi/TargetOrderPluginTest.kt` and `MemoryPluginTest.kt`, following the existing
  owned-behaviors plugin test), `docs/SPEC.md`, `docs/PLUGINS.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `core/` (other than wiring it already exposes), `script/`, `template-world/`, `samples/`.

### Specification & Decision Record Changes

- **§4.15 new bullet after **Owned behaviors**:** "**Target order (`addTargetOrder`):** adjusts the choice a behavior
  with `prefer` (§4.7) makes, after the world's `target_order` export, in plugin-id order. A handler gets a read-only
  `PluginWorld`, the mob, the behavior name, `prefer` as plain values (`Long`, `String`, `Boolean`, `List`, `Map`), the
  candidates in ascending id, the current target and ally (or null), and the choice so far. It returns
  `PluginTargetChoice.Default` (choose as without `prefer`), `Nobody`, or `Pick(actor)` with one of the candidates;
  returning the choice it was given keeps it. A pick that isn't a candidate, or a handler that throws, records a
  `plugin` diagnostic and keeps the choice so far. Must be fast and side-effect free."
- **§4.15 `PluginWorld` facade list:** add `memories(mob, subject, event, since)`, `remember(mob, subject, event, skill,
  amount, target)` and `forget(mob, subject, event)`, with the same meaning as `ctx.memories`, `ctx.remember` and
  `ctx.forget` (§4.5).
- **§4.15 descriptor sentence:** add "`prefer` as plain values" to the list of descriptor value types, and note the
  `retarget` kind.
- `docs/PLUGINS.md`: document the seam and the facade methods next to `addOwnedBehaviors` and the standing methods.
- CHANGELOG bullet: "Plugins: `addTargetOrder`, `PluginWorld.memories`, `PluginWorld.remember` and
  `PluginWorld.forget`."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// PluginApi.kt
fun addTargetOrder(doc: String, choose: (PluginTargetOrderCall) -> PluginTargetChoice)

sealed interface PluginTargetChoice {
  data object Default : PluginTargetChoice
  data object Nobody : PluginTargetChoice
  data class Pick(val actor: PluginActor) : PluginTargetChoice
}

data class PluginTargetOrderCall(
  val world: PluginWorld,
  val mob: PluginActor,
  val behavior: String,
  val prefer: Any?,
  val candidates: List<PluginActor>,
  val current: PluginActor?,
  val ally: PluginActor?,
  val choice: PluginTargetChoice,
)

data class PluginMemory(
  val subject: PluginActor?, val event: String, val skill: String?, val target: PluginActor?,
  val firstPulse: Long, val pulse: Long, val count: Int, val amount: Long, val channel: String?,
)

// Facade.kt, PluginWorld
fun memories(mob: PluginActor, subject: PluginActor? = null, event: String? = null, since: Long = 0): List<PluginMemory>
fun remember(
  mob: PluginActor, subject: PluginActor, event: String,
  skill: String? = null, amount: Long = 0, target: PluginActor? = null,
)
fun forget(mob: PluginActor, subject: PluginActor? = null, event: String? = null): Int
```

- **Algorithmic Steps & Invariants:**
  1. **Adapter:** mirror the `OwnedBehaviorAdjuster` adapter.
     - Convert the core `TargetOrderCall` and `TargetChoice` to plugin views (read-only `PluginWorld`; `prefer` via the
       same plain conversion as descriptors).
     - Call the handler and map the result back. Resolve `Pick` by identity against `call.candidates` by entity id; a
       miss throws, so `chooseTarget` records the diagnostic.
     - Exceptions propagate to `chooseTarget`, which already records them.
  2. **`PluginHost.addTargetOrder`:** register like `addOwnedBehaviors`, collect in plugin-id order, and assign
     `world.targetOrderAdjusters` where `ownedBehaviorAdjusters` is assigned.
  3. **Facade `memories`/`remember`/`forget`:** validate as `ctx.remember` does (a mob, the event regex, a skill label
     resolving to a skill prototype, amount ≥ 0). `remember` and `forget` follow `raiseStandingEvent`'s rule for
     read-only facades.
  4. Keep the symbol docs complete so the plugin API dump lists them.

### Test & Documentation Requirements

- **`TargetOrderPluginTest.kt`:**
  - A handler that picks the last candidate overrides the rules' `Default`.
  - Two handlers run in plugin-id order and the second sees the first's choice.
  - `Nobody` stops the proposal.
  - A non-candidate pick, or a thrown exception, records a `plugin` diagnostic and keeps the previous choice.
  - Handlers are not called without `prefer`.
- **`MemoryPluginTest.kt`:** `remember` then `memories` round-trips; `forget` returns the count and removes only the
  matching memories; an invalid event throws `IllegalArgumentException` (or the facade's usual error type).

### Exit Conditions

- [] `./gradlew :plugin-api:test` passes.
- [] `./gradlew :cli:test` passes after the API regeneration (plugin API dump: only the symbols above are new).
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 7a: Template World Samples and Stealth Noise

### Objective & Scope

- **In Scope:**
  - The template turns on memory (`memory_pulses = 1200`).
  - A sample `target_order` in a new `template-world/rules/targeting.star`, with strategies `"weakest"`, `"strongest"`,
    `"hated"`, `"threat"`, and `{"delay": N}`.
  - The stealth noise rule.
  - Starlark tests.
  - A §4.9-style paragraph describing the sample.
- **Out of Scope (the agent must not implement these in this phase):** any engine change (report bugs and fix them with
  a Kotlin test only if a sample can't work otherwise); robot-world changes; changing existing tutorial mobs' behavior
  outside the test fixtures.

### Context Working Set

- **Read / Reference Only:** `template-world/rules/combat.star` (style, constants such as `CONTRIB_WINDOW_PULSES`),
  `template-world/rules/standing.star`, `template-world/MODULE.gups`, `template-world/zones/tutorial/notice_*.star`,
  SPEC §4.6, §4.7, §5.8.
- **Files to Modify / Create:**
  - `template-world/MODULE.gups` (add `memory_pulses = 1200,` with a comment).
  - `template-world/rules/targeting.star` (new), `template-world/rules/perception.star`.
  - `template-world/zones/tutorial/target_testdata.star` and `target_test.star` (new),
    `template-world/zones/tutorial/notice_test.star`.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `core/`, `script/`, `plugin-api/`, `samples/`.

### Specification & Decision Record Changes

- **SPEC §4.7**, a new paragraph after the behavior bullets: "**The template's `target_order`** (`rules/targeting.star`)
  reads `prefer` as a strategy name, a list of them, or a dict:
  - `"weakest"` and `"strongest"` order by the lowest and highest hit points as a share of maximum, and `"hated"` by the
    lowest disposition. In a list, each later name breaks the earlier ties, then the lowest id.
  - `"threat"` scores each candidate from the mob's combat memory, each memory worth its event's weight times its amount
    (or its count when the amount is 0), fading linearly to nothing over `THREAT_PULSES`.
  - Any other name is ignored.
  - A dict may give `order` (a name or list, as above), `weights` (event to weight, over the defaults), `delay` (spare a
    candidate until that many pulses after it arrived: the first `enter` the mob remembers of it, or the mob's own
    latest `arrive` if that is later), `switch_margin` (for `retarget`, the percent by which a challenger's threat must
    beat the current target's) and `min_dwell` (for `retarget`, pulses after a switch before another).
  - It is a sample for worlds to tune."
- CHANGELOG bullet: "The template world keeps combat memory for 5 minutes and ships a sample `target_order` (weakest,
  strongest, hated, threat with fading weights, arrival delay and switch hysteresis). A hidden actor who makes noise can
  be noticed by sound for a moment."

### Implementation Blueprint

- **Signatures & Types** (`rules/targeting.star`; public names other than `target_order` are not allowed in a rules
  file, so keep helpers `_private` and constants UPPER_CASE):

```python
THREAT_PULSES = 120                 # 30 s for a memory's threat to fade to nothing
DEFAULT_WEIGHTS = {"attack": 10, "damage": 1, "cast": 20, "cast_start": 5, "heal": 1, "help": 10, "kill": 50}
DEFAULT_SWITCH_MARGIN = 25          # percent
DEFAULT_MIN_DWELL = 8               # pulses

def target_order(ctx): ...
def _settings(prefer): ...          # -> struct(order, weights, delay, switch_margin, min_dwell)
def _threat(ctx, actor, weights): ... # -> int
def _arrived(ctx, actor): ...       # -> pulse or None
```

- **Algorithmic Steps & Invariants:**
  1. **`_settings`:**
     - A string or list → `order` = that, other fields defaulted.
     - A dict → read `order` (default `["threat"]` for `retarget`, else `[]`), `weights` merged over `DEFAULT_WEIGHTS`,
       `delay` (0), `switch_margin`, `min_dwell`.
     - Anything else → `fail(...)`; the engine logs it and falls back.
     - Strategy names other than `"weakest"`, `"strongest"`, `"hated"` and `"threat"` contribute nothing to the sort key
       and are not errors. The engine's dry run passes `prefer = "dry_run"`, and `gups lint` must stay clean.
  2. **`target_order`:**
     - `cands = ctx.candidates`.
     - If `delay > 0`, drop candidates with `_arrived(ctx, c)` not `None` and `ctx.pulse - arrived < delay`. If none
       remain, `return None`. Never apply the delay to `retarget`'s engaged opponents: skip the filter when
       `ctx.behavior == "retarget"`.
     - Sort a copy by a tuple key built from `order`:
       - `"weakest"` → `hp * 100 // max(1, max_hp)`;
       - `"strongest"` → its negation;
       - `"hated"` → `ctx.dispositions[i]` (0 when `None`);
       - `"threat"` → `-_threat(...)`;
       - finally the actor id. Use the stable index into `ctx.candidates` to look up dispositions.
     - `best = sorted[0]`.
     - For `ctx.behavior == "retarget"` and `ctx.current` among the candidates:
       - Keep the current target (`return ctx.current`) when `ctx.pulse - ctx.current_since < min_dwell`.
       - Also keep it when `order` contains `"threat"` and `_threat(best) * 100 <= _threat(current) * (100 +
         switch_margin)`.
     - Return `best`.
  3. **`_threat`:**
     - Sum over `ctx.memories(subject = actor)`: `w = weights.get(m.event, 0)`; skip 0.
     - `base = w * (m.amount if m.amount > 0 else m.count)`, `age = ctx.pulse - m.pulse`, add `base * max(0,
       THREAT_PULSES - age) // THREAT_PULSES`.
     - Integer math only.
  4. **`_arrived`:** `e = ctx.memories(subject = actor, event = "enter")` (use `e[0].first_pulse` if any) and `a =
     ctx.memories(subject = ctx.actor, event = "arrive")` (use `a[0].pulse` if any). Return the max of those present, or
     `None`, in which case the candidate is not delayed. The engine records `enter` synchronously (Phase 4b), so a
     player who just walked in always has one on the mob's next think.
  5. **`perception.star` `can_perceive`:** replace the Phase 1 hidden check:

```python
NOISE_PULSES = 8   # how long a hidden actor's noise gives it away

if ctx.source and ctx.source.has_flag("hidden"):
    if ctx.channel == "sight":
        return False
    if ctx.event == "notice" and ctx.channel == "sound":
        heard = [m for m in ctx.memories(subject = ctx.source, since = ctx.pulse - NOISE_PULSES)
                 if m.channel == "sound"]
        if not heard:
            return False
```

`NOISE_PULSES` is a module-level constant; the `if` block goes at the top of `can_perceive`, and the sense and distance
checks stay after it. Spellcasting events (`cast_start`, `cast`, `heal`, `help`) on `sound` are allowed for a hidden
source, so a hidden caster is heard.

6. **`target_testdata.star`:**
   - Two rooms joined only to each other: `target_test_hall` `east` to `target_test_room` and back (both flags
     `["dead_end_ok"]`, no other exits).
   - `target_test_room` has a command `mend <name>` that finds the named player in the room and calls `ctx.heal(target,
     5, "hp", source = ctx.actor)`, so a test player heals without the skill setup.
   - Probe mobs with small `hp`:
     - `test_picker_weak`: `aggressive(toward = "players", prefer = "weakest")`.
     - `test_picker_odd`: `aggressive(toward = "players", prefer = "nonsense")`.
     - `test_waiter`: `aggressive(toward = "players", prefer = {"delay": 12})`.
     - `test_switcher`: `retarget(prefer = {"order": "threat", "weights": {"attack": 0, "damage": 1, "heal": 10},
       "switch_margin": 25, "min_dwell": 8})`, with no `aggressive` (tests start the fight with `kill`). The explicit
       weights make the tests independent of the default tuning.
   - A wisp-style mob with only a declared channel is covered by the Kotlin tests of Phase 4b, not here.

### Test & Documentation Requirements

- **`target_test.star`:**
  - `test_unknown_strategy_still_attacks`: `test_picker_odd` (`prefer = "nonsense"`) attacks the lowest-id player.
  - `test_weakest_is_attacked_first`: two players, one wounded with `w`'s vital setter; the wounded one is attacked.
  - `test_delay_spares_a_newcomer`: `test_waiter` in `target_test_room`, the player in `target_test_hall`.
    - The player walks `east`. The player isn't attacked on the mob's first think after entry (`w.run(pulses = 4)`), nor
      before 12 pulses, and is attacked after.
    - Variant: walking `west` and back `east` within the window doesn't reset the delay (measured from the first
      `enter`).
  - `test_switcher_turns_on_the_healer`:
    - Players A and B are in `target_test_room` with `test_switcher`. A is set to low `hp` with `w`'s vital setter, and
      A does `kill switcher`.
    - B does `mend A` three times.
    - Within 16 pulses the mob's primary target is B, and B is engaged.
  - `test_switcher_doesnt_thrash`: after switching to B, a single weaker event by A doesn't switch it back within
    `min_dwell`, nor while A's threat doesn't beat B's by the margin.
  - `test_memory_flood_does_not_erase_threat`: A attacks, then says something 50 times. The switcher still ranks A's
    `attack` memory (check through a test command printing `len(ctx.memories(mob, subject = A))` and the `count`).
- **`notice_test.star`:**
  - Add `test_a_hidden_player_who_speaks_is_heard`: `test_listener`, the hidden player isn't attacked for 20 pulses; the
    player `say`s something; the player is attacked within the next 8 pulses.
  - Add `test_a_listener_ignores_noise_it_did_not_hear`: the hidden player `say`s something, then `w.run(pulses = 20)`,
    then `test_listener` is spawned. It isn't attacked in the following 20 pulses, because only noise the mob itself
    heard counts.
- Run `./gradlew :cli:test` (lint, template tests, fuzz).
- **End-to-end, per `AGENTS.md`:**
  - Write `local-world-test/scenario.star`: spawn two players in a template room with a `test_switcher`; one fights, the
    other heals; print the mob's primary target each 8 pulses.
  - Run it with `cli/build/install/gups/bin/gups scenario --world template-world --package //zones/tutorial
    local-world-test/scenario.star` and read the transcript.
  - Then delete `local-world-test/`.

### Exit Conditions

- [] `./gradlew :cli:test` passes.
- [] The scenario transcript shows the switch to the healer and no back-and-forth.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 7b: The Template `pacify` Spell

### Objective & Scope

- **In Scope:**
  - A `pacify` spell in `//skills/arcane` whose `resolve` ends the fight with the caster, calls `ctx.forget`, and
    applies a `pacified` affect.
  - The `pacified` check in the template's `can_perceive`.
  - Starlark tests and a spec paragraph.
- **Out of Scope (the agent must not implement these in this phase):**
  - Any engine change.
  - Teaching `pacify` through the tutorial trainer or giving it to starting characters.
  - Changing `assist` so a pacified mob stays out of an ally's fight.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/skills/arcane/spells.star` (declaration style; `soothe` is an `ally` spell).
  - `template-world/rules/combat.star` (`skill_check`, `skill_power`, `_clamp`).
  - `template-world/zones/tutorial/notice_testdata.star` (`ctx.apply_affect(..., flags = [...])`).
  - `template-world/zones/tutorial/trainer_testdata.star` (giving mobs and players skills in tests).
  - SPEC §4.3 (skill declaration, `resolve`, targeting), §4.5 (`stop_combat`, `apply_affect`, `roll`, `send`, `echo`).
- **Files to Modify / Create:**
  - `template-world/skills/arcane/spells.star`.
  - `template-world/rules/perception.star`.
  - `template-world/zones/tutorial/target_testdata.star` and `target_test.star` (append).
  - `docs/SPEC.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `core/`, `script/`, `plugin-api/`, `samples/`, the other spells.

### Specification & Decision Record Changes

- **SPEC §4.7**, after the paragraph on the template's `target_order`: "**The template's `pacify`**
  (`//skills/arcane:pacify`) is a sample of `ctx.forget`. It is cast as an `ally` spell, so it never engages its target.
  - **The roll:** a 1d100 roll at or under the caster's power (`ctx.power`), less 10 for each level the target has over
    the caster, clamped to 5..95.
  - **On success, on a mob:** it ends the mob's engagement with the caster alone, makes it forget everything it
    remembers about the caster, and gives it a `pacified` affect for `PACIFY_PULSES` (40).
  - **While pacified:** the template's `can_perceive` refuses `notice`, so the mob starts no fight and retargets nobody.
    It still joins an ally's fight through `assist`.
  - **What it doesn't touch:** standing, so a grudge held there returns when the affect ends."
- CHANGELOG bullet: "The template world has a `pacify` spell: it ends a mob's fight with the caster, makes the mob
  forget the caster with `ctx.forget`, and keeps it from noticing anyone for a while."

### Implementation Blueprint

- **Signatures & Types** (`spells.star`; mirror the existing declarations):

```python
PACIFY_PULSES = 40   # how long a pacified mob notices nobody

def _pacify(ctx): ...

skill("pacify",
      kind = "spell",
      cost = vitals(mana = 15),
      warmup = 8,
      lag = 4,
      range = "near",
      target = "ally",
      interrupt = {"damage_pct": 10},
      resolve = _pacify,
)
```

- **Algorithmic Steps & Invariants (`_pacify`):**
  1. `mob = ctx.target`. If `mob == None`, `mob == ctx.actor` or `mob.is_player`, send the caster "That has no quarrel
     to forget." and return.
  2. **The roll:**
     - `chance = clamp(ctx.power - 10 * max(0, mob.level - ctx.actor.level), 5, 95)`. Write a local `_clamp`: helpers
       can't be imported from the rules package.
     - The template has `leveling`. Guard the level term with `ctx.has_feature("leveling")` anyway, since this is a
       sample.
     - If `ctx.roll("1d100") > chance`: send the caster "{Mob} shrugs off your calm." and return.
  3. If the mob is engaged with the caster, `ctx.stop_combat(mob, ctx.actor)`.
  4. `ctx.forget(mob, subject = ctx.actor)`.
  5. `ctx.apply_affect(mob, "pacified", PACIFY_PULSES, flags = ["pacified"])`.
  6. Send the caster "{Mob} calms, its anger toward you gone." and `ctx.echo(ctx.room, "{Mob} calms down.", exclude =
     [ctx.actor])`.
  7. If the declaration fails to load because a skill needs an `effect`, or because an `ally` skill can't target this
     actor, stop and report it instead of changing the engine.
  8. **`perception.star` `can_perceive`:** add, before the hidden check, `if ctx.event == "notice" and
     ctx.actor.has_flag("pacified"): return False` with a one-line comment. `ctx.actor` is the noticing mob in a
     `notice` call.
  9. **`target_testdata.star`:**
     - Add `test_brute`: small `hp`, `level` 1, `behaviors = [aggressive(toward = "players")]`. It keeps memory through
       the world's `memory_pulses`.
     - Add a room command `memof <mob keyword>` in `target_test_room` that sends the actor `str(len(ctx.memories(mob,
       subject = ctx.actor)))`.

### Test & Documentation Requirements

- **`target_test.star`** (the caster gets `//skills/arcane:pacify` at 100 with `w.set_skill`, and the roll is forced
  with `w.force_rng`):
  - `test_pacify_ends_the_fight_and_forgets`:
    - A is attacked by `test_brute`. A runs enough pulses to cast, then casts `pacify brute` with a forced low roll.
    - A is no longer fighting, and `memof brute` prints `0`.
    - The brute doesn't attack A for 40 pulses, and attacks A again after the affect ends.
  - `test_pacify_frees_only_the_caster`: A and B fight the brute; B pacifies it. The brute keeps fighting A, and `memof
    brute` typed by A is above 0.
  - `test_pacify_can_fail`: with a forced high roll, the caster sees "shrugs off", the fight goes on, and memories stay.
  - `test_pacify_does_not_start_a_fight`: casting at a brute that hasn't noticed the caster (spawned after the player
    hides, as in `notice_test.star`) leaves both unengaged.
- Run `./gradlew :cli:test` (lint, template tests, fuzz).

### Exit Conditions

- [] `./gradlew :cli:test` passes.
- [] `./gradlew markdownFormat`, then `./gradlew check` passes with zero warnings.

---

## Phase 8: Finalization & Backlog Update

- **§3.10:** add to the `combat` row: "`aggressive(prefer=)`, `assist(prefer=)`, `retarget`, the `target_order` export,
  combat memory with `memory_pulses` (module and mob), `memories`, `remember` and `forget`". Format it like the existing
  cells.
- **§7.4:** add a row after the highest milestone row: `| **M44** | Target preference and combat memory: senses-based
  noticing (aggressive loses via), prefer on aggressive and assist, the optional target_order export and addTargetOrder
  plugin seam, the retarget behavior, transient per-mob combat memory (memory_pulses, merged by subject, event, skill
  and target; self, perceived and witnessed events) with ctx.memories, ctx.remember, ctx.forget and the plugin facade,
  and the template's sample target_order, pacify spell and noise-aware stealth (§3.9, §3.10, §4.2, §4.5, §4.6, §4.7,
  §4.15, §5.7, §5.8). |` (backticks as neighboring rows use them).
- **`docs/DESIGN_NEEDED.md`:** under `### Target preference for aggressive mobs`, add a first line `**Status: completed
  in Milestone 44.**` followed by one sentence: "Behaviors take a plain-data `prefer` that the optional `target_order`
  rules export interprets over the post-filter candidates; `retarget` switches targets mid-fight; mobs keep a transient,
  merged combat memory the rules read; arrival delays and thrash control are rules policy, sampled in the template." Do
  not delete the entry.
- **Greps:**
  - `grep -n "via" docs/SPEC.md`: confirm no behavior `via` remains (perception `via_exit` and `standing_history(via=)`
    are unrelated).
  - `grep -n "lowest-id matching target" docs/SPEC.md`: confirm the `aggressive` row was updated.
  - Confirm §4.6's optional-export count matches its list.
- Run `./gradlew markdownFormat`, then the full suite `./gradlew check` from a clean build.

### Exit Conditions

- [] `docs/DESIGN_NEEDED.md` marks the entry completed without deleting it.
- [] SPEC §3.9, §3.10, §4.2, §4.5, §4.6, §4.7, §4.15, §5.7, §5.8 and §7.4 describe exactly what the code does.
- [] `./gradlew check` passes with zero warnings.
