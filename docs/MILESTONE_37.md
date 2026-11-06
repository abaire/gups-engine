# Milestone 37: Contribution Ledger, Shared Experience, and Player Groups

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, headings `### Group, party and pet XP sharing` (under "Combat and
  skills") and `### Player groups and parties` (under "Social and communication"). They are designed together because
  the experience split needs the group concept and the group concept is only useful with the split.
- **Design Summary:** the engine owns the mechanics a script cannot do, and `//lib` owns the social policy.
  1. **Contribution ledger.** Every actor keeps a transient record of who contributed to a fight against it: `damage`,
     `healing`, `support` and `last_pulse` per contributor.
     - `damage`: the vital loss the contributor dealt to the victim.
     - `healing`: the effective healing the contributor gave an actor who was engaged with the victim (overheal does not
       count; healing the victim itself is never recorded).
     - `support`: how many affects the contributor applied. A bane counts when applied to the victim. A buff counts when
       applied to an actor engaged with the victim. Either way the contributor must not be the target. Whether an affect
       is a bane or a buff is never guessed when the engine knows: an affect applied by a skill is a bane exactly when
       the skill's `target` is `enemy`, and a stun is always a bane. Only an affect applied without a skill (the raw
       `apply_affect` verb, an item's use effect, an item proc's primitive effect) is inferred, as a bane when the
       applier is already engaged with the target. Reapplying an affect that the engine keeps unchanged, because the
       existing one lasts longer, records nothing.
     - **Damage over time is attributed.** Damage or healing done by an affect's `on_tick` or `on_expire` hook without
       an explicit `source` is credited to the actor that applied the affect, if it is still in the world. A poisoner or
       a burning-spell caster therefore gets the ledger entry and, as the last damager, the kill. If the applier has
       left the world the damage has no source, as today. This holds for actor, item and room affects alike, since all
       three run their hooks with the affect in the hook context.
     - `last_pulse`: the pulse of the most recent contribution of any kind.
     - The record is never expired by the engine. `clear_contributions(victim, contributor=None)` clears it, and the
       world decides when to call it (see `on_disengage`). A pure healer or buffer therefore appears in the ledger
       without ever touching the victim.
  2. **`xp_share(ctx)` rules export** (`leveling` feature). The engine still prices the kill once with `xp_for_kill`,
     which becomes the **pool**. `xp_share` then decides who gets what:
     - `ctx.actor` is the killer (the last damager) and `ctx.target` the victim. `ctx.xp` is the pool.
       `ctx.contributors` is a list of structs: `actor`, `damage`, `healing`, `support`, `last_pulse`, `owner`, `group`
       (a group id or `None`) and `in_room` (whether it is in the victim's room at death).
     - It returns a dict `{actor: int}`. The engine awards the entries in ascending `EntityId` order after the corpse,
       as it does today. An entry may name any in-world actor, including one not in `ctx.contributors`, such as a pet's
       owner. Entries of 0, and entries for actors no longer in the world, are skipped. A negative entry, a key that is
       not an actor, or a value that is not an int is a rules error, and the kill falls back to the default.
     - The default, when a world does not define `xp_share`, is `{ctx.actor: ctx.xp}`, which is today's behavior.
     - No killer means no pool and no award, as today. A pool of 0 or less skips the call.
     - `ctx.contributors` holds every in-world ledger entry, plus the killer, plus every in-world member of a
       contributor's group even with zero contribution, wherever it is (`in_room` tells the formula where). Ascending
       `EntityId`.
  3. **Pet ownership reuses existing fields.** A contributor's `owner` is its `summonedBy`, else its `leader`, when that
     actor is an in-world player; otherwise `None`. No bond verb or `owner` field is added. A pet's group is its owner's
     group.
  4. **Session-scoped player groups.** A group is an in-memory record `{id, leader, members}` of players. It is never
     saved.
     - Verbs: `group_add(member, to)`, `group_remove(member)`, `group_set_leader(member)`, `group_disband(member)`.
     - Queries: `group_of(actor)` and `group_members(actor)`, where a pet resolves through its owner.
     - A quit, a link-dead expiry or any removal of a player from the world removes it from its group. The leader is
       then the lowest-id remaining member, and a group left with one member is disbanded. A server restart leaves no
       groups.
     - One global hook, `on_group_change`, fires for every change.
     - The engine has no invitations, consent, size cap or chat. Those are `//lib`.
  5. **`on_disengage` hook** (`combat` feature). Fires when an actor's last opponent leaves its engagement, so a world
     can call `clear_contributions` when the party breaks combat. The engine clears nothing on its own.
  6. **`allies_near` includes group members** and their pets. `assist` is unchanged.
  7. **GMCP.**
     - **`Char.Group`** is a new engine state package, recomputed every phase 7 and sent when it changes, like
       `Char.Vitals`. Its payload is `null` when the player has no group, else `{"id", "leader", "members": [{"name",
       "leader", "in_room", "level", "vitals"}]}`. `leader` at the top is the leader's name and `leader` in a member is
       a bool. `in_room` is whether the member is in the recipient's room. `level` appears only with `leveling`.
       `vitals` has the same keys as `Char.Vitals`. Members are listed leader first, then by ascending id, and include
       the recipient.
     - **`Group.Chat`** is an event package the template `//lib` declares with `gmcp_package` and sends with `gmcp` for
       `gtell`, `{"talker", "text"}`, following the `Shop.Stock` precedent. The engine has no group chat.
  8. **Template world** ships the reference: `xp_share` in `rules/combat.star` and the `group`, `invite`, `accept`,
     `leave`, `kick`, `leader`, `disband` and `gtell` commands in a new `lib/group.star`.
- **Deferred or deliberately out of scope:**
  - Friendly-fire policy. `before_attack` attaches only to mobs and items, so `//lib` cannot veto a player's attack on a
    groupmate. It is the `DESIGN_NEEDED` entry `### Friendly fire and area-of-effect harm to allies`, which this
    milestone leaves open.
  - Group fields in `who`, `score` and `Char.Status`, and the bundled web client's use of `Char.Group`.
  - Persisted groups, and any bond, `follow` or `order` mechanism for pets (the pet entry already carries a note to
    revisit `owner` with its design).
- **Specification Impact:** the entity-state list in §3.6 (the combat-state bullet), the features table in §3.9, the
  hooks table, the verbs table, the rules exports table, the death bullet in §5.4, the GMCP packages table with its
  delivery and payload notes, and a §7.4 row. Exact edits are in each phase.
- **Decision Record Updates:** None. `docs/DECISIONS.md` holds no live records and this milestone adds none. Spec text
  is approved by the maintainer through approval of this milestone.

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first and see them fail.
   - No spec section numbers or decision ids in comments, Kotlin or Starlark.
   - All Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. The engine is unreleased, so there are no migrations
   and no back-compat shims.
3. Add CHANGELOG bullets to a new `### Groups and shared experience` subsection at the top of `## api_version 1
   (unreleased)`, above whatever subsection is first there now. Create it in Phase 1 and have each later phase append.
   The `allies_near` change in Phase 4 is behavior-breaking for scripts: give it a before/after snippet.
4. In any phase that changes the script API or template-world content, regenerate the API files before the phase's final
   `./gradlew check` if any golden or API test fails:
   - Run `./gradlew :cli:test -Dgups.updateGolden=true`. This rewrites `cli/src/test/resources/api.golden.json` and the
     `cli/src/test/resources/docs-golden/` files.
   - Run `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout. This rewrites `docs/api/api-1.json`.
   - Read the diff. Only the symbols that phase names may change. `ApiDiffTest` must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing entry.
5. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation.
6. Spec text in this milestone is approved by the maintainer through approval of this milestone; edit `docs/SPEC.md`
   exactly as each phase says and nowhere else.
7. Locate code by the symbol names given, not by line numbers: the files drift. Where a phase says "mirror", open the
   named precedent first and copy its structure. Line numbers given in parentheses are only hints from when this
   milestone was written.
8. Feature ownership (`script/src/main/kotlin/gups/script/Ownership.kt`). Symbols this milestone adds belong to these
   features, and nothing else is gated:
   - Kernel, with no `owns(...)` entry: the contribution ledger, `clear_contributions`, the `heal` `source` parameter,
     the group verbs and queries, `on_group_change`, `ctx.group`, and `Char.Group`. The `damage` and `heal` verbs are
     kernel today too.
   - `Feature.LEVELING`: the `xp_share` rules export, as `"rules:xp_share"`.
   - `Feature.COMBAT`: the `on_disengage` hook, as `"hook:on_disengage"`.
9. Every new Starlark view class (a Kotlin class annotated `@StarlarkBuiltin`) must be added to `ScriptApi.VIEW_CLASSES`
   in `script/src/main/kotlin/gups/script/ApiModel.kt`, or the API model and golden files won't describe it. This
   milestone adds two: `ContributorView` (Phase 2) and `GroupView` (Phase 3).
10. Context budget. `docs/SPEC.md`, `Declarations.kt`, `Ctx.kt`, `Rules.kt` and `Views.kt` are each thousands of lines.
    Never read them whole. `grep -n` for the symbol or quoted text a step names, then read a window of about 80 lines
    around it. For the harness API in Phase 5, grep `docs/SPEC.md` for `spawn_player` and read that section.
11. In Starlark, every engine verb and query is a method on `ctx`. Write `ctx.group_add(...)`, `ctx.gmcp(...)` and
    `ctx.clear_contributions(...)`, never a bare `group_add(...)`. Top-level declarations such as `global_command`,
    `global_hook` and `gmcp_package` are the exception.

---

## Phase 1: The contribution ledger and `clear_contributions`

### Objective & Scope

- **In Scope:**
  - The `Contribution` type and the per-actor ledger on `Actor`.
  - Recording damage, healing and support into it.
  - Attributing damage and healing done by an affect's own hooks to the affect's applier.
  - The `World.ownerOf` helper.
  - The `clear_contributions` verb.
  - The CHANGELOG subsection.
- **Out of Scope:** *(the agent must not attempt any of these here)* `xp_share`, anything about groups, the
  `on_disengage` hook, any change to `runDeath`, `allies_near`, or the template world.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Vitals.kt` (`applyVitalDeltas`, the `lastAttacker` line).
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (`damage`, `heal`, `applyAffect`, `opponentsOf`, and `applyEffect`,
    including its local `val hostile` that decides whether a primitive engages).
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt` (around `val hostile = skill.target == TargetKind.ENEMY`):
    the precedent for "a skill is hostile exactly when its target is `enemy`". Read it only; it applies effects through
    `applyEffect` and needs no change.
  - `core/src/main/kotlin/gups/core/world/Procs.kt`: one `applyEffect` call passes a skill, the other passes `null`.
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Actor.lastAttacker`, `Mob.leader`, `Mob.summonedBy`,
    `Affect.source`).
  - `script/src/main/kotlin/gups/script/Ctx.kt`:
    - the `damage`, `heal` and `apply_affect` verbs, the `verb(...)` helper and `actorArg`;
    - the private `hc: HookContext` constructor field, and the `affect` struct field, which reads `hc.affect`.
  - `script/src/main/kotlin/gups/script/Ctx.kt`, `apply_affect`: it already passes `owner as? Actor` (the actor whose
    hook is running) as the affect's source. Leave it as it is.
  - The only call sites of `World.heal(` are `applyEffect`'s `Effect.Heal` branch in `Combat.kt` and the `heal` verb in
    `Ctx.kt`. Confirm with `grep -rn "\bheal(" core/src/main script/src/main`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (the ledger field)
  - `core/src/main/kotlin/gups/core/model/Contribution.kt` (new)
  - `core/src/main/kotlin/gups/core/world/Contributions.kt` (new: recording, clearing, `ownerOf`)
  - `core/src/main/kotlin/gups/core/world/Combat.kt`:
    - `damage`, `heal` and `applyAffect` call the recorder.
    - `applyEffect` passes the caster to `heal`, and passes the polarity to `applyAffect`.
  - `script/src/main/kotlin/gups/script/Ctx.kt`:
    - the `clear_contributions` verb;
    - `heal`'s new `source` parameter;
    - in both `damage` and `heal`, the affect-hook source fallback.
  - `script/src/main/kotlin/gups/script/DocExamples.kt`
  - Tests: new `core/src/test/kotlin/gups/core/world/ContributionTest.kt`, plus a verb test next to the existing verb
    tests in `script/src/test/kotlin/gups/script/`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the regenerated API files
- **Do Not Touch:** `runDeath`, `Rules.kt`, `persist/`, `harness/`, `template-world/`.

### Specification & Decision Record Changes

- `docs/SPEC.md` §3.6, the "Action and combat state" bullet: after `lastAttacker` add "and `contributions`, a transient
  per-contributor record of damage, healing and support, never saved".
- `docs/SPEC.md` verbs table, the "Vitals and combat" row: add `clear_contributions(victim, contributor=None)`. Add a
  short bullet under the table: "`clear_contributions` empties the actor's contribution record, or removes one
  contributor's entry. The engine never expires the record itself."
- Document `heal`'s new optional `source` in the same row if the Starlark verb gains it (see below).
- `docs/SPEC.md` §5.4 death bullet: after "The killer is the last actor who damaged the victim, if still in the world."
  add "Damage or healing an affect does from its own `on_tick` or `on_expire` hook, naming no `source`, counts as done
  by the actor that applied the affect, while that actor is in the world." Add the same sentence once more, directly
  after the `apply_affect` row or bullet in the verbs section (find it with `grep -n "apply_affect" docs/SPEC.md`). Make
  no other affect-text edits.
- `docs/SPEC.md`, the bullet that begins "A skill is hostile toward an actor other than the caster when its `target` is
  `enemy`" (in the engagement text of §5.4). Append: "The same rule decides whether an affect the skill applies counts
  as a bane or as help in the victim's contribution record. Where no skill is involved (the `apply_affect` verb, an
  item's use effect, an item proc's primitive effect), it is a bane when the applier is already engaged with the
  target."

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Contribution.kt
  /** What one actor has done toward a fight against a victim; held on the victim, never saved. */
  class Contribution {
    var damage: Int = 0
    var healing: Int = 0
    var support: Int = 0
    var lastPulse: Long = 0
  }

  // Entities.kt, on Actor, beside lastAttacker
  /** Who has contributed to fights against this actor, by contributor id; ascending. Not saved. */
  internal val contributions: java.util.TreeMap<EntityId, Contribution> = java.util.TreeMap()

  // Contributions.kt
  internal fun World.recordDamage(victim: Actor, source: Actor, amount: Int)
  internal fun World.recordHealing(healed: Actor, source: Actor, effective: Int)
  /** [hostile] is true for a bane, false for help, and null when the caller can't say. */
  internal fun World.recordSupport(target: Actor, source: Actor, hostile: Boolean?)
  fun World.clearContributions(victim: Actor, contributor: Actor? = null)
  /** The in-world player a pet belongs to: summonedBy, else leader, when that is a player. */
  fun World.ownerOf(a: Actor): Player?

  // Combat.kt: heal gains an optional source
  fun World.heal(target: Actor, vital: String, amount: Int, source: Actor? = null)

  // Combat.kt: applyAffect gains the polarity its caller knows (the existing source parameter stays)
  fun World.applyAffect(/* existing parameters */, hostile: Boolean? = null): Affect?
  ```
- **Algorithmic Steps & Invariants:**
  1. Every recorder ignores a null or self source, a source or victim that is not `inWorld`, and amounts of 0 or less.
     Each stamps `lastPulse = pulse` on the entry it touches. Do not set `dirty`: the ledger is not saved.
  2. `recordDamage(victim, source, amount)`: get or create `victim.contributions[source.id]` and add `amount` to its
     `damage`. Call it from `World.damage` right after its `applyVitalDeltas(...)` call, with `total` (the sum of the
     positive vital losses), only when `source != null && source !== target && total > 0`. Leave the `lastAttacker` line
     in `Vitals.kt` as it is, since it names the killer. Do not change `Vitals.kt`.
  3. `recordHealing(healed, source, effective)`. In `World.heal`, after the existing early return, compute `val
     effective = minOf(amount, maxOf(0, target.max(vital) - target.current(vital)))` before `changeVital`. Then, when
     `source != null`, call the recorder. The recorder adds `effective` to `healing` on the ledger of each actor in
     `opponentsOf(healed)`, under `source.id`, skipping an opponent that is the source itself. The entry lands on the
     enemies' ledgers, never on the healed actor's. If `healed` has no opponents, nothing is recorded: a heal outside
     combat earns nothing.
  4. `recordSupport(target, source, hostile)`. Call it from `World.applyAffect` just after `target.affects += affect`,
     when `source != null && source !== target`, passing the `hostile` argument through. A reapply that returns early,
     because the existing affect lasts longer, records nothing.
     - Resolve polarity first: `val bane = hostile ?: (source.id in target.fighting)`.
     - A bane adds `support += 1` to `target`'s own ledger under `source.id`.
     - Otherwise it is help: add `support += 1` to the ledger of each actor in `opponentsOf(target)` under `source.id`,
       if there are any.
     - Polarity at each `applyAffect` call site in `Combat.kt`:
       - `applyEffect`, `Effect.ApplyAffect` branch: `hostile = skill?.let { it.target == TargetKind.ENEMY }`. This is
         the same rule `SkillCommands.kt` uses to engage. Do not reuse `applyEffect`'s existing local `hostile`, which
         is about primitives that engage. Name the new value `skillHostile` and compute it once at the top of
         `applyEffect`.
       - `applyEffect`, `Effect.Stun` branch: `hostile = true`. A stun always engages its target first.
       - `summon` and `applyEquipAffects`: pass nothing (`null`). The summon's target is a fresh mob with no opponents,
         and equipment affects have no source, so nothing is recorded.
     - `applyEffect` is called with a skill from `SkillCommands.kt` and from the skill branch of `Procs.kt`. It is
       called with `skill = null`, so `skillHostile` is `null` and the engine infers, from item use in
       `KernelCommands.kt` and from the primitive-proc branch of `Procs.kt`. The `apply_affect` verb never passes
       `hostile`, so it always infers.
     - The inference exists only where no skill is known. Because skills don't use it, the order in which a hostile cast
       engages its victim and applies its affects doesn't matter for skills.
  5. `clear_contributions(victim, contributor)`: `contributor == null` clears the whole map; otherwise removes that one
     key. The verb takes an actor view and an optional actor view, validates with `actorArg`, and returns `None`.
  6. `ownerOf(a)`: for a `Mob`, `a.summonedBy ?: a.leader` resolved with `actor(id)`; return it only if it is an
     in-world `Player`. Anything else returns null. Players return null.
  7. Heal call sites:
     - In `applyEffect`, the `Effect.Heal` branch becomes `heal(t, effect.vital, ..., source = caster)`.
     - The `heal` verb in `Ctx.kt` gains a last parameter, mirroring the `damage` verb's `source` exactly: `Param(name =
       "source", defaultValue = "None", named = true, doc = "Actor credited with the healing, or None.")`. Convert it
       the way `damage` does: `if (isNone(source)) null else actorArg(source, "heal(source)")`.
     - A skill's Starlark `resolve` function that calls `ctx.heal` or `ctx.damage` without `source` stays unattributed.
       Do not add a fallback for it.
  8. Affect attribution, in `Ctx.kt` only. Add one private helper to `Ctx`, with a KDoc saying it returns the in-world
     actor that applied the affect whose hook is running, or null: `private fun affectApplier(): Actor? =
     hc.affect?.source?.let(world::actor)?.takeIf { it.inWorld }`.

     In the `damage` and `heal` verbs, the source becomes `if (isNone(source)) affectApplier() else actorArg(...)`. So:

     - An explicit actor always wins.
     - An omitted `source` and an explicit `source = None` both fall back to the applier. The verb can't tell them
       apart, and that's intended.
     - Outside an affect hook `hc.affect` is null, so nothing changes.
     - If the affect has no source, or its applier has left the world, the damage stays unattributed.
     - `hc.affect` is set for the tick and expire hooks of actor, item and room affects (the `invokeRef(...,
       HookContext(affect = ...))` calls in `World.kt`, `Combat.kt` and `RoomAffects.kt`), so all three are covered with
       no further code. Add no global state.
     - The `source` that reaches `World.damage` also makes the applier the victim's `lastAttacker`, so the applier is
       the killer when a tick kills.
     - If the applier is the damaged actor itself (a self-applied affect), `recordDamage` ignores it as self-damage, and
       `applyVitalDeltas` already refuses to make an actor its own `lastAttacker`.
- **Edge cases to test:**
  - Damage records `damage` and `lastPulse`; self-damage and environmental damage (no source) record nothing.
  - A heal on an engaged ally credits the healer on each opponent's ledger with the effective amount only (heal 50 into
    a gap of 20 records 20); healing at full health records nothing.
  - A heal on an actor with no opponents records nothing.
  - A mob M fights players P1 and P2. A shaman mob S heals M. The healer's entry lands on the ledgers of P1 and P2 (M's
    opponents) under S's id. M's own ledger gets no entry from S.
  - A reapplied affect that returns early, because the existing one lasts longer, records no `support`.
  - A skill with `target = "enemy"` that applies an affect records `support` on the victim's ledger, whether or not the
    caster was engaged beforehand. A skill with `target = "ally"` cast on an engaged ally records it on that ally's
    opponents' ledgers. The raw `apply_affect` verb infers from engagement, and the test shows both inferences.
  - A poison applied by a caster whose `on_tick` calls `damage` without a `source`:
    - records `damage` for the caster on the victim's ledger each tick, and makes the caster `lastAttacker`;
    - a tick that kills makes the caster the killer;
    - an explicit `source` wins;
    - if the caster has quit, the damage is unattributed and no entry is made.
  - A heal-over-time affect credits its applier the same way.
  - A room affect with a source whose `on_tick` damages an actor in the room credits that source.
  - `clear_contributions(victim)` empties it; with a contributor removes only that entry; both are no-ops on an empty
    ledger.
  - `ownerOf` for a summoned mob, a led mob, a mob with a non-player leader, a leader not in the world, and a player.

### Test & Documentation Requirements

- Write `ContributionTest` first. Use the existing test world fixtures the other `core/src/test/.../world/` tests use.
- Docstrings on every new public function. Add a `DocExamples.kt` entry for `clear_contributions`.
- CHANGELOG: create `### Groups and shared experience` and add a bullet for the ledger, the `heal` `source` parameter
  and `clear_contributions`.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `ContributionTest` and the verb test pass.
- [] The only golden/API diffs are `clear_contributions`, `heal`'s `source`, and any doc text you changed on the
  `damage` and `heal` verbs.

---

## Phase 2: `xp_share` and death settlement

### Objective & Scope

- **In Scope:**
  - The `Contributor` type, `RulesContext` fields and the `xp_share` rules export, with the default.
  - The script binding with validation.
  - `runDeath` snapshot, call and award.
- **Out of Scope:** *(the agent must not attempt any of these here)* groups (the `group` field of a contributor is
  always `null` in this phase; Phase 3 fills it), `on_disengage`, the template world.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (`runDeath`, the `killer` and `killXp` lines, the final `awardXp`).
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt`:
    - `RulesEngine.xpForKill`.
    - `RulesContext`, a plain class with named, defaulted constructor parameters.
    - Its copy helpers `withDamageType` and `withAffect`, which list every field by name. Find any other copy helper
      with `grep -n "fun with" core/src/main/kotlin/gups/core/rules/RulesEngine.kt`.
  - `script/src/main/kotlin/gups/script/Rules.kt`:
    - `xpForKill`, which shows the binding shape `run(EXPORT, c, ctxArgs(c)) { v -> convert(v) } ?: default`. `run`
      returns null both when the world doesn't define the export and on a rules error.
    - `ctxArgs` and `RulesExport.XP_FOR_KILL`, with its `RulesExportSpec` entry.
    - `class RulesCtx`, where each `ctx.<field>` is a `@StarlarkMethod(structField = true)`. `encumbrance_level` is a
      recent, simple precedent.
  - `script/src/main/kotlin/gups/script/Views.kt` (`DoorView`: a small read-only `@StarlarkBuiltin` struct with
    `structField` methods, `isImmutable()` and `repr`) and `ApiModel.kt` (`ScriptApi.VIEW_CLASSES`).
  - `script/src/main/kotlin/gups/script/Ownership.kt` (`owns(Feature.LEVELING, "rules:xp_for_kill", ...)`).
  - `script/src/testFixtures/kotlin/gups/script/testing/TestWorlds.kt` (the feature map that names `xp_for_kill`).
  - `core/src/testFixtures/kotlin/gups/core/fixtures/FixtureRules.kt` (`xpForKillOf`).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt` (`xpShare`, `Contributor`, new `RulesContext` fields)
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (`runDeath`)
  - `core/src/main/kotlin/gups/core/world/Contributions.kt` (`contributorsOf`)
  - `script/src/main/kotlin/gups/script/Rules.kt` (export, spec, binding, `ctx.xp` and `ctx.contributors` on `RulesCtx`)
  - `script/src/main/kotlin/gups/script/Views.kt` (`ContributorView`) and `ApiModel.kt` (register it)
  - `script/src/main/kotlin/gups/script/Ownership.kt`: add `"rules:xp_share"` to the existing `owns(Feature.LEVELING,
    "rules:xp_for_kill", ...)` line.
  - `script/src/testFixtures/kotlin/gups/script/testing/TestWorlds.kt`: add `"xp_share" to setOf("leveling"),` beside
    `"xp_for_kill"` in the feature map.
  - `core/src/testFixtures/kotlin/gups/core/fixtures/FixtureRules.kt` (`xpShareOf`)
  - Tests: new `core/src/test/kotlin/gups/core/world/XpShareTest.kt`, plus a rules-export test beside the existing
    `xp_for_kill` script tests
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the regenerated API files
- **Do Not Touch:** `Leveling.kt`, group code, `persist/`, `template-world/`.

### Specification & Decision Record Changes

- `docs/SPEC.md` rules exports table, after the `xp_for_kill`/`xp_to_level` row, add a row in the table's three columns.
  Export: `xp_share(ctx)`. Feature: `leveling`. Returns: "Dict `{actor: int}`. Splits the pool among actors; see the
  death bullet."

- Add after that table a paragraph: "**`xp_share`** (§5.4): called once per kill, after `xp_for_kill`. `ctx.actor` is
  the killer, `ctx.target` the victim, `ctx.xp` the pool `xp_for_kill` returned, and `ctx.contributors` the contributors
  as structs with `actor`, `damage`, `healing`, `support`, `last_pulse`, `owner`, `group` and `in_room`. It returns a
  dict from actors to non-negative ints. An actor not in `ctx.contributors` may be named. Entries for actors no longer
  in the world, and entries of 0, are skipped. The default pays the killer the whole pool. A rules error keeps the
  default."

- `docs/SPEC.md` §5.4 death bullet, replace "then XP (with the `leveling` feature). The XP is priced by `xp_for_kill` as
  the victim died but awarded last" with "then XP (with the `leveling` feature). The pool is priced by `xp_for_kill` and
  split by `xp_share` as the victim died, with the contributors taken before its engagement ended, but awarded last to
  each recipient in ascending id order". Add to the "For mobs" bullet: "the pool is split by `xp_share`".

- `docs/SPEC.md` §3.9 `leveling` row: add `xp_share`.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // RulesEngine.kt
  /** One actor's part in a kill, as `xp_share` sees it. */
  data class Contributor(
    val actor: Actor,
    val damage: Int,
    val healing: Int,
    val support: Int,
    val lastPulse: Long,
    val owner: Player?,
    val groupId: Int?,
    val inRoom: Boolean,
  )

  // RulesContext gains two constructor parameters at the end (every copy helper passes them on):
  val xp: Long? = null
  val contributors: List<Contributor>? = null

  // RulesEngine (an interface): a default method, so FixtureRules and other implementations compile unchanged
  /** Who is awarded what of [c]`xp` for the kill; the default pays the killer all of it. */
  fun xpShare(c: RulesContext): Map<Actor, Long> = c.actor?.let { mapOf(it to (c.xp ?: 0L)) } ?: emptyMap()

  // Views.kt
  @StarlarkBuiltin(name = "contributor", doc = "One actor's part in a kill, for rules.xp_share.")
  class ContributorView internal constructor(private val scope: ViewScope, private val c: Contributor) : StarlarkValue
  ```
- **Algorithmic Steps & Invariants:**
  1. `contributorsOf(victim, killer): List<Contributor>` in `Contributions.kt`: take `victim.contributions` entries
     whose actor is `actor(id)` and `inWorld` and not the victim; add `killer` with a zero entry if absent; build each
     `Contributor` with `owner = ownerOf(actor)`, `groupId = null` (Phase 3 sets it), `inRoom = actor.room ===
     victim.room`. Sort by actor id. Return an immutable list.
  2. `runDeath`: immediately after the line `val killer = victim.lastAttacker?...`, which comes after the early returns,
     add `val snapshot = contributorsOf(victim, killer)` and then `victim.contributions.clear()`. Both must run before
     `disengageAll(victim)`. A player victim's ledger must be empty for its respawn. `contributorsOf` takes `killer:
     Actor?`; with a null killer, nothing is added for it.
  3. Pricing, where `killXp` is computed today: rename `killXp` to `pool` and compute it exactly as now. Then: `val
     awards: Map<Actor, Long> = if (killer != null && pool > 0) rules.xpShare(RulesContext(this, killer, victim, xp =
     pool, contributors = snapshot)) else emptyMap()`. When `pool` is 0, which covers a debug or summoned victim, no
     killer, or `leveling` off, `xpShare` is not called.
  4. The final award: replace `awardXp(killer, killXp)` with `for ((a, n) in awards.entries.sortedBy { it.key.id }) if
     (a.inWorld && n > 0) awardXp(a, n)`. The victim is skipped if it is a mob that has been removed (the `inWorld`
     check does it); a player victim who respawned is still `inWorld` and may be awarded.
  5. Script binding, in the class in `Rules.kt` that overrides `xpForKill`: `override fun xpShare(c: RulesContext) =
     run(RulesExport.XP_SHARE, c, ctxArgs(c)) { v -> convert(v) } ?: super.xpShare(c)`. The `?: super.xpShare(c)` covers
     both an undefined export and a rules error.
     - `convert` checks that the value is a Starlark `Dict`; otherwise it fails with `"rules.xp_share return value: must
       be a dict"`.
     - For each entry: the key must be an `ActorView`, and the value an int of at least 0. Otherwise fail with
       `"rules.xp_share return value: <what was wrong>"`.
     - Build a `Map<Actor, Long>` from `ActorView`'s actor and the int. A `fail(...)` inside the block is a rules error,
       so `run` returns null.
     - To see how an `ActorView` yields its `Actor`, grep `Rules.kt` and `Ctx.kt` for `is ActorView`.
  6. `RulesCtx` fields, each a `@StarlarkMethod(structField = true)`:
     - `xp`: `c.xp ?: Starlark.NONE`, an int.
     - `contributors`: `None` when `c.contributors` is null, else a Starlark list of `ContributorView`s.
     - `ContributorView` mirrors `DoorView`: a `read { }` guard on `scope.check()`, one `structField` method per field,
       `isImmutable() = true`, and a `repr` such as `<contributor Name>`. Its fields:
       - `actor`: an actor view, built with the same `view(scope, e)` helper other views use.
       - `damage`, `healing`, `support`, `last_pulse`: ints.
       - `owner`: an actor view or `None`.
       - `group`: an int or `None`.
       - `in_room`: a bool.
     - Add `ContributorView::class.java` to `ScriptApi.VIEW_CLASSES`, keeping the list alphabetical.
  7. Add `XP_SHARE("xp_share")` beside `XP_FOR_KILL` in the `RulesExport` enum. Add a `RulesExportSpec` beside the
     `XP_FOR_KILL` one, mirroring it, with `signature = "xp_share(ctx)"`, `returnType = "dict"` and `ctxFields =
     listOf("actor", "target", "xp", "contributors", "room", "pulse", "rng")`. Its `doc` is "Splits a kill's experience
     pool among actors."
- **Edge cases to test:**
  - No `xp_share` defined: a kill pays exactly as before (the whole suite of existing level tests is the check).
  - A formula splitting between the killer and a healer who never damaged the victim pays both.
  - A formula naming an actor outside the contributors (a pet's owner) pays it.
  - A recipient who has left the world, a zero entry, and the victim itself are skipped; a negative value falls back to
    the default.
  - A contributor who has quit by death time is not listed. A summoned or debug victim pays nothing and `xp_share` is
    not called.
  - The ledger of a dead player victim is empty after respawn.

### Test & Documentation Requirements

- Write `XpShareTest` and the script test first and see them fail. Extend `FixtureRules` with `xpShareOf`, mirroring
  `xpForKillOf`.
- CHANGELOG: a bullet for `xp_share`, noting the default keeps today's behavior.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] Every pre-existing leveling and death test passes unchanged.
- [] The only golden/API diffs are the `xp_share` export, `ctx.xp`, `ctx.contributors` and the new `contributor` view
  type.

---

## Phase 3: Player groups

### Objective & Scope

- **In Scope:**
  - The in-memory group record and the `World` bookkeeping.
  - The verbs `group_add`, `group_remove`, `group_set_leader`, `group_disband`.
  - The queries `group_of` and `group_members`.
  - Removal on quit, link-dead expiry or any removal of a player from the world, with leader promotion.
  - The global `on_group_change` hook.
  - Filling `Contributor.groupId` and adding group members to the contributor list.
  - The `Char.Group` GMCP state package.
- **Out of Scope:** *(the agent must not attempt any of these here)* `allies_near`, `on_disengage`, any command, the
  template world (including the `Group.Chat` event package), persistence of groups, `who`, `score`, `Char.Status`, and
  the bundled web client.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/World.kt` (`removeActor`, a member function of `World`; `actor(id)`).
  - `core/src/main/kotlin/gups/core/world/Sessions.kt` (`quit` and `expireSessions` both end in `removeActor(player)`).
  - `core/src/main/kotlin/gups/core/world/Hooks.kt`:
    - `HookName`;
    - `HookContext`, which already has `val event: String?`, used today by `on_perceive`;
    - `dispatchGlobal`. The `ON_ENCUMBRANCE_CHANGE` global hook is the precedent.
  - `script/src/main/kotlin/gups/script/Ctx.kt`, the existing `event` struct field. It already exposes `hc.event`.
  - `core/src/main/kotlin/gups/core/world/Leveling.kt` (the `dispatchGlobal(HookName.ON_LEVEL_CHANGE, ...)` calls).
  - `script/src/main/kotlin/gups/script/Declarations.kt` (the `ON_ENCUMBRANCE_CHANGE` `HookSpec`).
  - `script/src/main/kotlin/gups/script/Ctx.kt` (a query verb that returns actors, such as `allies_near`; the view
    classes in `Views.kt`, `ActorView`).
  - `core/src/main/kotlin/gups/core/world/Contributions.kt` from Phase 2.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Group.kt` (new)
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Player.groupId`)
  - `core/src/main/kotlin/gups/core/world/Groups.kt` (new: all group operations and queries)
  - `core/src/main/kotlin/gups/core/world/World.kt` (the group map, one line in `removeActor`)
  - `core/src/main/kotlin/gups/core/world/Hooks.kt` (`ON_GROUP_CHANGE`, the `HookContext.group` field)
  - `core/src/main/kotlin/gups/core/world/Contributions.kt` (fill `groupId`, add group members)
  - `core/src/main/kotlin/gups/core/world/Gmcp.kt` (`Char.Group`; read `gmcpPayload`'s `Char.Vitals` branch and
    `STATE_PACKAGES` first)
  - `core/src/test/kotlin/gups/core/world/GmcpTest.kt` (extend it)
  - `script/src/main/kotlin/gups/script/Ctx.kt` (the four verbs, the two queries, the `ctx.group` field, and the `event`
    field's doc text)
  - `script/src/main/kotlin/gups/script/Views.kt` (`GroupView`) and `ApiModel.kt` (register it)
  - `script/src/main/kotlin/gups/script/Declarations.kt` (the hook spec)
  - `script/src/main/kotlin/gups/script/DocExamples.kt`
  - Tests: new `core/src/test/kotlin/gups/core/world/GroupTest.kt`, verb and hook tests in `script/src/test/...`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the regenerated API files
- **Do Not Touch:** `persist/` (groups are never saved: do not add a column, do not serialize `groupId`), `Combat.kt`
  except through `Contributions.kt`, `template-world/`.

### Specification & Decision Record Changes

- `docs/SPEC.md` §3.6 player-specific state bullet: add "and `groupId`, the player's current group (transient, never
  saved)".
- `docs/SPEC.md` verbs table: add a row "Groups": `group_add(member, to)`, `group_remove(member)`,
  `group_set_leader(member)`, `group_disband(member)`; queries `group_of(actor) -> group | None` and
  `group_members(actor) -> [actor]` (also add the two queries to the "Queries" row).
- Add a new subsection (next to the entity-state text) "Groups" with: a group is an in-memory set of players with a
  leader; it is session-scoped (a quit, a link-dead expiry or any removal of the player from the world removes it; a
  restart leaves none; link-dead and zone changes do not); the leader on leaving is replaced by the lowest-id remaining
  member; a group with one member is disbanded; the engine has no consent, size cap or chat; a pet resolves to its
  owner's group; a group view has `id`, `leader` and `members` (leader first, then ascending id).
- `docs/SPEC.md` hooks table: add a global-only row `on_group_change` (no attach point), ctx `actor`, `group`, `event`.
  Document `event` as `"join"`, `"leave"`, `"leader"` or `"disband"`. Fix the order in the text: `"leave"`, then
  `"leader"` if the leader changed, then `"disband"` if one member remained.
- `docs/SPEC.md` §5.4 death text: nothing more in this phase. The contributor `group` and `in_room` text is in the
  `xp_share` paragraph; extend that paragraph with "`ctx.contributors` also lists every in-world member of a
  contributor's group, even with no contribution and wherever it is, so the formula can pay an idle member; `in_room`
  says whether it is in the victim's room".
- `docs/SPEC.md` GMCP packages table: add a row after `Char.Skills`, in the table's three columns. Package:
  `Char.Group`. Payload: `null` when the player has no group, else `{"id", "leader", "members": [{"name", "leader",
  "in_room", "level", "vitals"}]}`, where `leader` is the leader's name at the top and a bool in a member, and `level`
  appears only with `leveling`. When sent: "When changed".

  In the GMCP delivery text, add `Char.Group` to the list of state packages recomputed in every phase-7 flush. In the
  GMCP payloads text add: "`Char.Group`: `members` lists the leader first, then ascending id, and includes the
  recipient. `in_room` is whether the member is in the recipient's room. `vitals` has the keys of `Char.Vitals`. A
  client with no group gets `null` once, then nothing until it changes."

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Group.kt
  class Group(val id: Int, var leader: Player) {
    /** Members in join order; ascending id for display is applied by queries. */
    val members: LinkedHashSet<Player> = linkedSetOf(leader)
  }

  // Entities.kt, on Player
  /** Id of this player's group, or null. Not saved. */
  var groupId: Int? = null
    internal set

  // Groups.kt
  fun World.groupOf(a: Actor): Group?                 // a player's own, else a pet's owner's
  fun World.groupMembers(a: Actor): List<Player>      // leader first, then ascending id; empty if none
  fun World.groupAdd(member: Player, to: Player)
  fun World.groupRemove(member: Player)
  fun World.groupSetLeader(member: Player)
  fun World.groupDisband(member: Player)
  internal fun World.leaveGroupOnRemoval(p: Player)   // used by removeActor
  ```
- **Algorithmic Steps & Invariants:**
  1. `World` holds `internal val groups = java.util.TreeMap<Int, Group>()` and a counter `nextGroupId` (starts at 1,
     never reused).
  2. `groupOf(a)`: for a `Player` its `groupId` lookup; for a `Mob` the lookup of `ownerOf(a)`'s group; otherwise null.
  3. `groupAdd(member, to)`:
     - Both must be `inWorld` players and `member !== to`. Otherwise throw `IllegalArgumentException` with a clear
       message.
     - If `member.groupId` is set and differs from `to`'s group, throw `IllegalArgumentException("<name> is already in a
       group")`.
     - If `member` is already in `to`'s group, return: a no-op that fires nothing.
     - If `to` has no group: create one with id `nextGroupId++` and `to` as leader and sole member, set `to.groupId`,
       and dispatch `"join"` with `actor = to`.
     - Then add `member` to `members`, set `member.groupId`, and dispatch `"join"` with `actor = member`.
     - So creating a group fires exactly two `"join"` events, `to`'s first.
  4. `groupRemove(member)`. If `member` is not in a group, it is a no-op. Otherwise:
     - Remove it from `members` and clear its `groupId`.
     - Dispatch `"leave"` with `actor = member` and a snapshot that no longer lists it.
     - If `member` was the leader and at least one member remains, set the leader to the lowest-id remaining member and
       dispatch `"leader"` with `actor =` the new leader.
     - If exactly one member remains, disband through the same code as step 6, with `actor =` that remaining member.
     - If no member remains, delete the group and fire nothing more.
  5. `groupSetLeader(member)`: member must be in a group and not already the leader; set it; dispatch `"leader"`.
  6. `groupDisband(member)`: take the whole group at once. Snapshot the members, clear every `groupId`, delete the
     group, dispatch one `"disband"` with `actor = member`. The auto-disband in step 4 uses the same code for its one
     remaining member, so there the snapshot lists that one member.
  7. The `on_group_change` context:
     - `HookContext` gains one field, `val group: GroupSnapshot? = null`. Declare `data class GroupSnapshot(val id: Int,
       val leader: Player, val members: List<Player>)` in `Group.kt`. Build it at dispatch time, with members leader
       first, then ascending id.
     - The event name goes in the existing `HookContext.event` field. Do not add a second event field.
     - `ctx.event` already exposes `hc.event`. Widen its `doc` string to mention `on_group_change`'s values as well as
       `on_perceive`'s.
     - Add a `group` struct field to `Ctx` returning a `GroupView` of `hc.group`, or `None`.
     - `ctx.actor` is the player named in steps 3 to 6.
     - Dispatch with `dispatchGlobal(HookName.ON_GROUP_CHANGE, HookContext(actor = ..., event = ..., group = ...))`.
       `dispatchGlobal` already contains hook errors.
     - `HookName.ON_GROUP_CHANGE("on_group_change")` goes in the enum beside `ON_ENCUMBRANCE_CHANGE`. Its `HookSpec` in
       `Declarations.kt` mirrors `ON_ENCUMBRANCE_CHANGE`'s: `attachTo = emptyList()`, which makes it global-only, and
       `ctxFields = listOf("self", "actor", "event", "group", "room", "pulse", "rng")`.
  8. `removeActor`: add `if (a is Player) leaveGroupOnRemoval(a)` at the top, before the player leaves `actorIndex`,
     which calls `groupRemove`. This covers quit, link-dead expiry and `unload`. Do not call it on player death: a
     respawned player keeps its group.
  9. Contributor filling (in `contributorsOf`): set `groupId = groupOf(actor)?.id`. After building the entries, for each
     distinct group among the contributors add every member of that group who is `inWorld`, not already listed and not
     the victim, with a zero entry, and `inRoom` computed as before. Keep the whole list sorted by id.
  10. Query verbs: `group_of(actor)` returns a `GroupView` or `None`. `group_members(actor)` returns a list of actor
      views, leader first, which is empty when the actor has no group.
      - Validate arguments with `actorArg`, as `allies_near` does.
      - The four mutating verbs require player actors, and fail with `fail("group_add(member): must be a player")` and
        the like otherwise.
      - Inside `verb(...)`, the engine's `IllegalArgumentException` already becomes a script error, so don't catch it.
        Check this by reading `verb`.
      - `GroupView` mirrors `DoorView`: `@StarlarkBuiltin(name = "group")`, built from a `GroupSnapshot`, which is taken
        from the live `Group` for the query. Its `structField`s are `id` (int), `leader` (actor view) and `members`
        (list of actor views, leader first, then ascending id).
      - Add `GroupView::class.java` to `ScriptApi.VIEW_CLASSES`.
  11. `Char.Group`: add `"Char.Group"` to `STATE_PACKAGES` (after `Char.Skills`), which also makes it an engine package
      that plugins may not reuse. In `gmcpPayload`, the `"Char.Group"` branch returns `null` when `groupOf(p)` is null
      (use the player's own group, not a pet's), else a `LinkedHashMap` `{"id": group.id, "leader": group.leader.name,
      "members": [...]}`. Each member map is built in this key order: `name`, `leader` (member === group.leader),
      `in_room` (`member.room === p.room`), `level` only when `Feature.LEVELING` is on, and `vitals`, a map built
      exactly like the `Char.Vitals` branch for that member (`name`, `max`+`name`, in schema order). Members come from
      `groupMembers(p)`. Values are plain `String`, `Long`/`Int`, `Boolean`, `List` and `Map`. No other code is needed:
      phase 7 recomputes it and sends the delta, so a joiner, a leaver, a leader change and a member's vitals change
      each reach every member's client in the same flush. Do not add `refreshGmcp` calls. `queueIfChanged` sends a first
      payload even when it is `null`, because the session cache has no entry yet, so "`null` once" needs no special
      code. Add no feature skip for `Char.Group` in `gmcpDeltas`.
- **Edge cases to test:**
  - Create via `group_add(b, a)` makes `a` the leader; a third member joins via `group_add(c, b)` (any member).
  - Adding a player already in another group fails; adding one already in the same group is a no-op.
  - A leader quitting promotes the lowest-id remaining member; a two-member group disbands when one quits; hook events
    are in the order given above.
  - Link-dead keeps the group; link-dead expiry removes the player; a player's death and respawn keep it.
  - A pet resolves to its owner's group; a mob with a non-player leader has none.
  - `group_disband` clears everyone; a restart (a fresh `World`) has no groups; nothing about groups is written by the
    persistence layer (assert no new saved field).
  - Contributors: an idle member in the victim's room is listed with zeros and `in_room = true`; one in another room is
    listed with `in_room = false`; a member of a group of a pet contributor is listed.
  - `Char.Group` (in `GmcpTest`): a client supporting it gets `null` with no group; after `group_add` each member gets
    the same member list, leader first, with `leader` true only for the leader; a member in another room has `in_room =
    false` for a recipient elsewhere; `vitals` follows the `Char.Vitals` keys and changes when a member takes damage;
    `level` is absent without `leveling`; a leader change, a leave and a disband each produce a new message to the
    remaining members and `null` to the one who left; an unchanged group sends nothing on the next flush; a client that
    did not list the package gets nothing.

### Test & Documentation Requirements

- Write `GroupTest` first. Add script tests for the verbs, `GroupView` and the hook (`ctx.event`, `ctx.group`).
- DocExamples entries for each new verb, query and the hook.
- CHANGELOG: bullets for the group verbs, queries, `on_group_change` and the cleanup behavior.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `GroupTest` and the script tests pass.
- [] The persistence layer and its goldens are unchanged.
- [] The only golden/API diffs are the group verbs and queries, `on_group_change`, `ctx.group`, the `ctx.event` doc
  text, the new `group` view type, and `Char.Group` wherever the goldens list engine GMCP packages.

---

## Phase 4: `on_disengage` and groups in `allies_near`

### Objective & Scope

- **In Scope:**
  - The `on_disengage` hook (`combat` feature), fired when an actor's last opponent leaves.
  - `allies_near` returning group members and pets of the group.
- **Out of Scope:** *(the agent must not attempt any of these here)* any engine-side clearing of the ledger, any change
  to `assist`, friendly fire, the template world.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (`stopCombat`, `disengageAll`, `runDeath`'s `disengageAll` call).
  - The flee path: `FleeAction.resolve` in `CombatCommands.kt` calls `transition(actor, exit, CombatMsg.FLEE_ROOM)` in
    `Movement.kt`, and `transition` calls `disengageAll(actor)`. There is no flee-specific disengage today.
  - The other `disengageAll` callers are in `Movement.kt` (two calls), `Sessions.kt` (`quit`), `Bubble.kt`,
    `Reconcile.kt`, `Tools.kt`, and `endAffect` in `Combat.kt` (a summon fading). Step 6 below says what reason each one
    passes. List them with `grep -rn "disengageAll(" core/src/main`.
  - `core/src/main/kotlin/gups/core/world/Hooks.kt`, `Leveling.kt` (the `dispatch` plus `dispatchGlobal` pair).
    `dispatch` and `dispatchGlobal` already run inside `withCascadeGuard`, which bounds recursive hook cascades.
  - `script/src/main/kotlin/gups/script/Declarations.kt` (the `ON_DEATH` `HookSpec`).
  - `script/src/main/kotlin/gups/script/Ownership.kt` (the `owns(Feature.COMBAT, "hook:before_attack",
    "hook:on_combat_tick")` line).
  - `script/src/main/kotlin/gups/script/Ctx.kt` (`alliesNear`, `near`).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Combat.kt`, `Hooks.kt`
  - `core/src/main/kotlin/gups/core/world/Movement.kt` (`transition` gains a `reason` parameter), `CombatCommands.kt`
    (`FleeAction` passes `"flee"`)
  - `script/src/main/kotlin/gups/script/Declarations.kt`, `Ownership.kt`, `Ctx.kt` (`alliesNear` doc and filter)
  - Tests: new `core/src/test/kotlin/gups/core/world/DisengageTest.kt`, `AlliesNearTest` additions in
    `script/src/test/...`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the regenerated API files
- **Do Not Touch:** the ledger code, `Rules.kt`, `persist/`, `template-world/`.

### Specification & Decision Record Changes

- `docs/SPEC.md` hooks table: add `on_disengage`, attach points Mob and Room (like `on_death`), ctx `actor`, `event`.
  Add to the text: "Fires when an actor that was engaged has no opponent left, once for each such actor. `ctx.event` is
  `"flee"` after a successful flee, `"death"` when it was freed by an opponent's death, and `"stop"` otherwise
  (`stop_combat`, an opponent leaving the room, or a quit). The event names how the engagement ended, so the actor that
  fled and the opponents it left behind all see `"flee"`. The dying actor itself does not get the hook. The engine
  clears no ledger itself: a world calls `clear_contributions` from this hook." Fire the room and entity hooks as
  `on_death` does, then the global hook.
- `docs/SPEC.md` §3.9 `combat` row: add `on_disengage`.
- `docs/SPEC.md` verbs table, "Queries" row, and the `allies_near` bullet in §4.7 or wherever it is described: change to
  "Actors of the same side (players with players, mobs with mobs) within a band who are not fighting actor, and members
  of actor's group (and their pets) within the band, simulated and not fighting actor."

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Hooks.kt
  ON_DISENGAGE("on_disengage"),    // doc: see the spec text; HookContext.actor, HookContext.event

  // Combat.kt
  fun World.stopCombat(a: Actor, b: Actor? = null)                                   // unchanged signature
  internal fun World.disengageAll(a: Actor, reason: String? = "stop")    // reason added; null fires no hook
  private fun World.unlink(a: Actor, b: Actor): List<Actor>              // new: the actors this freed
  private fun World.fireDisengage(x: Actor, reason: String)              // new

  // Movement.kt
  internal fun World.transition(actor: Actor, exit: Exit, leave: Msg = KernelMsg.MOVE_LEAVES, reason: String = "stop")
  ```
- **Algorithmic Steps & Invariants:**
  1. Hooks fire only after every state change is finished. A hook may start a new fight, so it must never run while an
     engagement is half torn down.
  2. `unlink(a, b)` changes state only and fires nothing:
     - Note `val aWas = a.isEngaged` and `val bWas = b.isEngaged`.
     - Run the body that `stopCombat(a, b)` has today: the lines that edit `fighting` and `primaryTarget`, and the two
       `initiative` lines.
     - Return, in the order `a` then `b`, each of them that was engaged before and is not engaged now.
  3. The public `stopCombat(a, b)` with non-null `b` becomes `for (x in unlink(a, b)) fireDisengage(x, "stop")`.
  4. `disengageAll(a, reason)`:
     - Collect `val freed = LinkedHashSet<Actor>()`, then loop over `a.fighting.toList()`, adding `unlink(a, other)` to
       it.
     - Run the three trailing lines as today: `fighting.clear()`, `primaryTarget = null` and `initiative = null`.
     - Only then, if `reason != null`, call `fireDisengage(x, reason)` for each `x` in `freed`, skipping `a` itself when
       `reason == "death"`.
  5. `fireDisengage(x, reason)`: return if `Feature.COMBAT !in features`, if `!x.inWorld`, or if `!isSimulated(x)`.
     Otherwise `val ctx = HookContext(actor = x, event = reason)`, `dispatch(HookName.ON_DISENGAGE, x, x.room, ctx)`,
     then `dispatchGlobal(HookName.ON_DISENGAGE, ctx)`, mirroring `ON_LEVEL_CHANGE` in `Leveling.kt`. Add no re-entry
     set of your own: `withCascadeGuard` inside `dispatch` already bounds a hook that starts and stops fights.
  6. Call sites of `disengageAll`:
     - `runDeath`: `disengageAll(victim, "death")`, so only the opponents the victim frees get the hook.
     - `transition` in `Movement.kt` passes its new `reason` to `disengageAll`. `FleeAction.resolve` calls
       `transition(actor, exit, CombatMsg.FLEE_ROOM, reason = "flee")`. Every other `transition` caller keeps `"stop"`.
     - The other call in `Movement.kt`, and `quit` in `Sessions.kt`, keep the default `"stop"`: an opponent walked away
       or left the game.
     - Engine bookkeeping passes `null`, so it fires no hook: `sleep` in `Bubble.kt` (a room going dormant), both calls
       in `Reconcile.kt` (a reload moving or removing actors), `despawnDebug` in `Tools.kt`, and `endAffect` in
       `Combat.kt` (a summon fading away).
  7. `HookName.ON_DISENGAGE("on_disengage")` in the enum. The `HookSpec` mirrors `ON_DEATH`'s: `attachTo = listOf(M,
     R)`, `ctxFields = listOf("self", "actor", "event", "room", "pulse", "rng")`. Its `doc` is "Fires when an engaged
     actor has no opponent left; ctx.event is 'flee', 'death' or 'stop'." Add `"hook:on_disengage"` to the
     `owns(Feature.COMBAT, "hook:before_attack", "hook:on_combat_tick")` line in `Ownership.kt`. The `event` field
     already exists on `HookContext` and `ctx` (Phase 3 reused it); widen `ctx.event`'s doc once more.
  8. `alliesNear` in `Ctx.kt`: keep the existing same-side filter and add the group clause. With `val ga =
     world.groupOf(a)`, the filter is `within.filter { x -> x.id !in a.fighting && (x.isPlayer == a.isPlayer || (ga !=
     null && world.groupOf(x) === ga)) }`. `near` already excludes `a` itself, limits to the band, and keeps only
     simulated actors; do not duplicate that. Update the verb's `doc` to the spec wording below.
- **Edge cases to test:**
  - A two-fighter duel ending by `stop_combat`, by flee, and by a death fires the right `ctx.event` for each survivor
    once; a three-way fight fires only for the actor whose last opponent leaves; the dying actor gets no hook.
  - A flee fires `"flee"` for the fleeing actor and for the opponent it leaves; walking out of a fight (a `transition`
    without a reason) fires `"stop"`.
  - A hook that calls `ctx.clear_contributions(ctx.actor)` empties the ledger without error, and a following kill pays
    nothing to the old contributors.
  - A summon fading away while it fights fires no hook for its opponent, and neither does a room going dormant.
  - An `on_disengage` hook that calls `ctx.start_combat` on the freed actor leaves a consistent engagement: both actors
    list each other in `fighting`.
  - A hook that restarts combat (`ctx.start_combat`) and stops it again does not loop forever. The cascade guard drops
    it and logs a diagnostic, and the test asserts the world keeps running.
  - The hook does not exist without `combat` (a load error to attach it, like `on_combat_tick`).
  - `allies_near`: a player's group members and a pet of a member are returned when in range and not fighting; the same
    actors are absent when not grouped; same-side behavior is unchanged.

### Test & Documentation Requirements

- Write `DisengageTest` and the script tests first. DocExamples entry for `on_disengage`.
- CHANGELOG: a bullet for `on_disengage`, and a before/after for `allies_near`:

  ```python
  # before: a player's grouped pet was not an ally
  # after: allies_near(player, "near") includes the player's group members and their pets
  ```

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] The existing `allies_near`, flee and death tests pass unchanged.
- [] The only golden/API diffs are `on_disengage`, the `ctx.event` doc text and the `allies_near` doc text.

---

## Phase 5: Template world reference and end-to-end scenario

### Objective & Scope

- **In Scope:**
  - `xp_share` in `template-world/rules/combat.star` (damage, healing and support weights, group and level-band rules).
  - `lib/group.star` with the commands `group`, `invite`, `accept`, `leave`, `kick`, `leader`, `disband` and `gtell`.
  - Two ally spells, `soothe` and `ward`, in the template's arcane school, so the scenario has a real healer and buffer.
  - A scenario test of a party killing a mob with a healer.
  - A tutorial page.
  - The `Group.Chat` event package declared and sent from `lib/group.star`.
- **Out of Scope:** *(the agent must not attempt any of these here)* new engine behavior, friendly-fire prevention,
  changes to `Char.Group`, persisted groups, pet commands.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/rules/combat.star` (`xp_for_kill` and the constants near it).
  - `template-world/lib/quest.star` and `template-world/lib/trainer.star` (`global_command(...)`, how state is kept with
    attributes, how a command reads its arguments and replies).
  - `template-world/lib/package.star` (the header comment lists each library).
  - `template-world/lib/shop.star`, its last lines: `gmcp_package("Shop.Stock", ...)` and `global_command(...)` with
    lanes. Read-only commands use `lane = "fast"` and commands that change state use `lane = "tick"`.
  - `template-world/skills/arcane/spells.star` (`spark` and `mend`; the pattern for a new spell).
  - `script/src/main/kotlin/gups/script/DocExamples.kt`, the `skill("cure", ..., target = "ally", ...)` example and the
    `effect = apply_affect(...)` examples, for the syntax of an ally spell and an affect effect.
  - The harness world methods `w.set_skill(actor, skill, proficiency)`, `w.damage(...)` and `w.force_rng(...)`
    (`harness/src/main/kotlin/gups/harness/Handles.kt`, or the harness section of `docs/SPEC.md`).
  - `template-world/zones/tutorial/shop_test.star`, `tutorial_test.star` (the `*_test.star` harness).
  - `template-world/docs/tutorials/` (how a tutorial is written and indexed).
  - `docs/SPEC.md` for the harness API, as named in `AGENTS.md`.
- **Files to Modify / Create:**
  - `template-world/rules/combat.star` (add `xp_share` and its helpers)
  - `template-world/lib/group.star` (new), `template-world/lib/package.star` (list it)
  - `template-world/skills/arcane/spells.star`: add two ally spells so the scenario has a real healer and buffer:
    - `soothe`: `target = "ally"`, `range = "near"`, `cost = vitals(mana = 8)`, `lag = 4`, `effect = heal(dice = "2d6",
      vital = "hp")`.
    - `ward`: `target = "ally"`, `range = "near"`, `cost = vitals(mana = 8)`, `lag = 4`, `effect = apply_affect(name =
      "warded", duration = 60, stat_deltas = stats(armor = 2), expire_msg = "Your ward fades.")`. Mirror the
      `DocExamples.kt` `"apply_affect"` example (the `slow` skill) for the syntax.
    - If an existing template test lists every arcane spell, update its expected list.
  - `template-world/zones/tutorial/group_test.star` (new)
  - `template-world/docs/tutorials/10_groups_and_shared_experience.md` (new) and
    `template-world/docs/tutorials/README.md` (the index entry)
  - `docs/CHANGELOG.md`
- **Do Not Touch:** Kotlin sources (except through a test-only fixture if a harness gap forces it; stop and report
  instead), `docs/SPEC.md`.

### Specification & Decision Record Changes

- None. Phase 5 is content. If a command needs an engine feature the previous phases did not provide, stop and report it
  in the final summary instead of adding it.

### Implementation Blueprint

- **Signatures & Types:**

  ```python
  # rules/combat.star
  def xp_share(ctx):
    # ctx.xp is the pool. Returns {actor: int}.

  # lib/group.star
  # lane = "fast": group, gtell (and gt). lane = "tick": invite, accept, leave, kick, leader, disband.
  ```
- **Algorithmic Steps & Invariants:**
  1. `xp_share(ctx)` reference formula. Put these constants above `xp_for_kill` in `rules/combat.star`, each with a
     one-line comment. A pulse is 250 ms.
     - `CONTRIB_WINDOW_PULSES = 1200`: 5 minutes.
     - `LEVEL_BAND = 10`.
     - `WEIGHT_DAMAGE = 100`, `WEIGHT_HEALING = 100` and `WEIGHT_SUPPORT = 20`.
     - `IDLE_SHARE = 10`: the minimum weight of a grouped member in the victim's room.

     The algorithm, in this order:

     - Step A. For each `c` in `ctx.contributors`, let `payee = c.owner if c.owner != None else c.actor`. A pet's share
       goes to its owner, who may not be listed itself.
     - Step B. Keep `c` only if all three hold: `c.in_room or c.group != None`; `ctx.pulse - c.last_pulse <=
       CONTRIB_WINDOW_PULSES or (c.group != None and c.in_room)`; `abs(payee.level - ctx.target.level) <= LEVEL_BAND`.
     - Step C. The weight of a kept `c` is `c.damage * WEIGHT_DAMAGE + c.healing * WEIGHT_HEALING + c.support *
       WEIGHT_SUPPORT`, raised to `IDLE_SHARE` when `c.group != None and c.in_room`.
     - Step D. Add each weight to a dict keyed by `payee`. Actor views are hashable, and two views of the same actor are
       equal, so a pet's weight and its owner's own weight land on one key.
     - Step E. Let `total` be the sum of the weights. If `total == 0`, return `{ctx.actor: ctx.xp}`.
     - Step F. Each payee gets `ctx.xp * weight // total`.
     - Step G. Add the remainder, `ctx.xp` minus the sum given out, to `ctx.actor` if it is a payee. Otherwise add it to
       the payee with the largest weight, and on a tie the one listed first. The shares then sum to exactly `ctx.xp`,
       and a solo kill pays exactly `ctx.xp` as before.
  2. `lib/group.star`: In each command `me` is `ctx.self`, the typing player, and the argument text is `ctx.args`, as in
     `lib/trainer.star`.
     - `invite <player>`:
       - The inviter must be alone or the leader of a group.
       - The target must be a player (`is_player`) in the same room who isn't the inviter, isn't in a group, and has no
         unexpired invite.
       - Store the pending invite as attributes on the target, `group_invite_from` (the inviter's name) and
         `group_invite_until` (`ctx.pulse + 240`, one minute), and tell both players.
       - Find the target among `ctx.self.room`'s actors by name, the way an existing `lib` command matches a keyword.
     - `accept`:
       - Read and clear both attributes.
       - If `ctx.pulse` is past `group_invite_until`, or the inviter is no longer in the room, say so.
       - Otherwise call `ctx.group_add(me, inviter)`. If the inviter has since joined someone else's group, the call
         adds `me` to that group, which is fine.
     - `leave`: `ctx.group_remove(me)`.
     - `kick <member>`: leader only; `ctx.group_remove(target)`.
     - `leader <member>`: leader only; `ctx.group_set_leader(target)`.
     - `disband`: leader only; `ctx.group_disband(me)`.
     - `group`: list the members as "Name (level) hp%" with a "(leader)" marker, or "You are not in a group.".
     - `gtell <text>`:
       - Send the text to every member of `ctx.group_members(me)`, including the speaker, as "[Group] Name: text".
       - Register `global_command("gt", ...)` with the same function as an alias.
       - Declare `gmcp_package("Group.Chat", doc = "A group chat line.")` at the bottom of the file next to the
         commands, as `shop.star` does for `Shop.Stock`.
       - Send each member `ctx.gmcp(member, "Group.Chat", {"talker": me.name, "text": text})`. The text line and the
         GMCP event both go out, so a client without GMCP still sees the text.
     - `global_hook("on_group_change", _on_group_change)` tells every member of `ctx.group` (and, for `"leave"`,
       `ctx.actor` too) "X joins the group.", "X leaves the group.", "X is now the leader." or "The group disbands.", by
       `ctx.event`.
     - `global_hook("on_disengage", _on_disengage)`: when `ctx.actor` is not a player, call
       `ctx.clear_contributions(ctx.actor)`. A mob that loses its last opponent forgets who fought it.
  3. State and commands use only verbs and queries from earlier phases. Errors are in-world messages, not script errors.
- **Edge cases to test (in `group_test.star`):**
  - Setup:
    - Use `t.world(seed = 1)`.
    - Spawn the victim first with `w.spawn("//zones/tutorial:training_dummy", at = ...)`. It has 30 hp and doesn't fight
      back. Spawn each player with `level = dummy.level`, except the out-of-band player, who gets `dummy.level + 11`.
    - Give the healer `soothe` and the buffer `ward` with `w.set_skill(p, "//skills/arcane:soothe", 100)`, and so on.
  - Three players, a fighter, a healer who never attacks and a buffer, group up. The fighter attacks. The buffer casts
    `ward` on the fighter. Once the fighter is below full hp (or after `w.damage(...)` on the fighter, so the heal has
    room), the healer casts `soothe` on the fighter. They kill the mob. Record each player's `xp` before and after.
    - If the dummy dies before the buffer and healer have acted, use a sturdier tutorial mob from
      `zones/tutorial/mobs.star`.
    - Help counts only while the fighter is engaged, so both casts must land after the fighter's `kill` and before the
      death.
    - All three gained more than 0.
    - The gains sum to exactly the pool, which you can compute from `xp_for_kill`'s formula or read from a solo kill of
      the same mob at the same levels.
    - Do not assert which of the three got the most, since that depends on rolls.
  - A solo player's kill pays exactly `xp_for_kill`.
  - A grouped member outside the room with no contribution gets nothing. A member outside the level band gets nothing.
    An idle member in the room gets more than 0.
  - Invitation flow: invite, accept, expiry, double-invite refusal, leader-only checks, kick, leader change.
  - `gtell` reaches all members and no one else; a member who quits stops receiving it. Each recipient's
    `p.gmcp("Group.Chat")` returns the event with the right `talker` and `text`.
  - `p.gmcp("Char.Group")` for a member shows the group after `accept`, and `null` after `leave`.
  - After a flee, the freed mob's ledger is empty, so a later kill does not pay the old contributors.

### Test & Documentation Requirements

- Put the scenario in `template-world/zones/tutorial/group_test.star` so it runs with the package; also run it ad hoc
  with `gups scenario` per `AGENTS.md` once, from `local-world-test/`, and delete the scratch afterward.
- Tutorial page: how a party shares experience, the `xp_share` contract, how to tune the constants, and how a world can
  clear contributions on `on_disengage`. Link it from the tutorial index.
- CHANGELOG: bullets for the template `xp_share`, the group commands and the tutorial.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including the template world's tests and the Markdown check.
- [] The scenario passes: grouped healer and buffer earn XP, a solo kill pays exactly as before.
- [] `gups scenario` ran against the template world once with the expected transcript, and `local-world-test/` is gone
  afterward.

---

## Phase 6: Finalization & Backlog Update

- In `docs/SPEC.md` §7.4 add a row "**M37**" summarizing: the contribution ledger and `clear_contributions`; the
  `xp_share` export; session-scoped player groups with the group verbs, queries and `on_group_change`; `on_disengage`;
  groups in `allies_near`; and the template-world reference.
- In `docs/DESIGN_NEEDED.md`. The file's header says designed entries are deleted, but for this milestone keep them and
  mark them, as M36 asked. "Mark as completed" means adding this line as the first paragraph under the heading:
  `**Status:** completed in M37.`
  - Mark `### Group, party and pet XP sharing` as completed. Do not delete it.
  - Mark `### Player groups and parties` as completed, and under it record what is not done: "Friendly fire is the
    separate entry `### Friendly fire and area-of-effect harm to allies`. Group fields in `who`, `score` and
    `Char.Status`, and the bundled web client's use of `Char.Group`, are not covered." Do not delete it.
  - Leave `### Friendly fire and area-of-effect harm to allies` open.
  - Leave `### Pets and followers bonded to a player` open. Its existing note about revisiting an explicit `owner`
    stays.
- Run `./gradlew markdownFormat`, then the full `./gradlew check`, and confirm the golden and API diffs name only this
  milestone's symbols: `clear_contributions`, the `heal` `source` parameter, the `damage` and `heal` verbs' doc text
  about affect attribution, `xp_share`, `ctx.xp`, `ctx.contributors`, the `contributor` and `group` view types, the
  group verbs and queries, `on_group_change`, `ctx.group`, the `ctx.event` doc text, `on_disengage`, the `allies_near`
  doc text, and the `Char.Group` package (in the schema or docs goldens, if they list engine packages). The template
  world's new `soothe` and `ward` spells may also appear in docs goldens generated from the template.
- Verify the final state: the persistence layer is untouched, no new `D-xxx` was added, and `local-world-test/` does not
  exist.
