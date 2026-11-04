# Milestone 28: Combat Procs

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, section "Combat and skills", heading `### Combat procs: items and
  affects that cast on attack`.

- **Design Summary:**

  - **Procs are declarative, not hooks.** A new `proc(...)` value describes something that may happen during an
    auto-attack swing. No new hook and no new "cast" verb are added. Procs are accepted by:

    - `item(procs = [...])` (needs `wear`, like `modifiers`);
    - `item_affect(procs = [...])` (a `worn` one needs its item to have `wear`; a `carried` one doesn't);
    - the `apply_affect(...)` effect primitive and the `ctx.apply_affect(...)` verb, for actor affects and item instance
      affects.

    Mob prototypes and plugin traits get no procs. An "always on" mob proc is an intrinsic affect.

  - **Builtins.** Both work anywhere, like effect primitives, because `apply_affect` is called at run time too:

    - `proc(on, chance = 1.0, skill = None, effect = None, target = None, message = None, outcomes = None)`;
    - `outcome(weight, skill = None, effect = None, target = None, message = None)`.

    The rules:

    - A proc gives exactly one of `skill`, `effect` or `outcomes`.
    - `outcomes` can't be combined with `skill`, `effect`, `target` or `message`.
    - An `outcome` gives at most one of `skill` and `effect`. Giving neither means "nothing happens".
    - `chance` and `weight` are probabilities from 0 to 1, like `loot(chance =)`.
    - The `outcomes` weights must add up to at most 1. Any probability left over means nothing happens.
    - A simple proc is shorthand for one outcome of weight 1.
    - `on` is `"hit"`, `"struck"` or `"avoid"`.
    - `target` is `"enemy"`, `"self"` or `"none"`.
    - When `target` is omitted:
      - for `skill =`, it follows the skill's `target`: `enemy` → enemy, `self`/`ally` → self, `none` → none;
      - for `effect =` (and for an empty outcome), it is `enemy`.
    - `message`, when given, is a non-blank string.

    See "Example: the cracked wand" below for an outcome table.

  - **Triggers.** Procs fire only during an auto-attack swing. They never fire for spell damage, `use_effect`, the raw
    `ctx.damage` verb or proc damage, so procs can never set each other off.

    |   `on`   |       Whose procs       |                                                                               When                                                                               |
    |----------|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
    | `hit`    | The attacker's          | After a landed swing's damage has been applied, including when that hit left the defender mortally wounded. `enemy` is the defender.                             |
    | `struck` | The defender's (thorns) | Right after the attacker's `hit` procs for the same swing. `enemy` is the attacker.                                                                              |
    | `avoid`  | The defender's          | On a missed swing, after the miss messages. `enemy` is the attacker. This sets up the later `counter` skill. Reasons for a miss (block, parry) are out of scope. |

    A bearer's condition doesn't gate its procs, because procs are reactions, not actions. The same-tier snapshot rules
    (§5.4.1) don't apply. A proc needs its bearer in the world. An `enemy` proc also needs the other actor in the world
    and in the bearer's room.

  - **Source order** follows the modifier chain (§4.6) and fixes the order of `rng` draws:

    1. the bearer's own affects, by affect id;
    2. each active worn item in `equipment_slots()` order: its prototype `procs`, then its item affects' procs
       (prototype affects in declaration order, then instance affects by id);
    3. the `carried` item affects of other held items, depth first through containers (exactly as `computeHeld` walks
       them).

    On `hit`, the swinging-weapon filter applies: a worn item with `dice` (and its item affects) contributes only when
    it is the swing's weapon. On `struck` and `avoid`, every active worn item contributes, as on the defender's chains.

  - **Rolls.** Each proc of the matching trigger draws one `rng.fraction()`. It triggers when that draw is `< chance`.
    If it triggers and has more than one outcome, or a single outcome of weight below 1, it draws a second
    `rng.fraction()`. That draw picks the first outcome whose running total of weights is `>` the draw; past the total,
    nothing happens.

  - **Resolution (no cast pipeline):**

    - A **`skill =`** outcome needs the `skills` feature (a load error otherwise). It:
      - runs `World.castAllowed(RulesContext(world, bearer, recipient, skill = skill))`. That is the plugin `cast`
        restriction (§4.19) and then the `cast_allowed` chain, so a trait that may not cast also stops skill procs. A
        denial drops the proc silently, apart from the trace;
      - then, for each recipient (everyone in the room except the bearer for a `range = "room"` skill, otherwise the
        chosen target), starts combat for an `enemy` skill landing on someone else, as `CastAction` does, runs
        `applyEffect(skill.effect, bearer, recipient, skill)`, and then `skill.resolve`.
    - It skips:
      - cost;
      - warmup;
      - lag and `waitState`;
      - the range/band check;
      - `silenced`;
      - knowing the skill;
      - `before_cast`, `on_cast_start` and `on_cast`.
    - A skill whose `target` is `item`, or a skill label that no longer resolves (after a reload), skips the proc with a
      `script` diagnostic.
    - An **`effect =`** outcome runs `applyEffect(effect, bearer, recipient, null)`. The usual primitive rules apply:
      hostile primitives engage, and `damage` runs the damage chains.
    - In proc effects, `summon` is not allowed, nested `apply_affect` needs a duration and no `applies`, and plugin
      primitives are allowed.
  - **Messages.** Two new engine messages, in `CombatMsg` (feature `combat`):
    - `combat.proc.self`: "Power surges from {source}!" goes to the bearer;
    - `combat.proc.room`: "Power surges around {actor}!" goes to sight perceivers in the room, excluding the bearer.

    `{source}` is the item's display name for item and item-affect procs, or the affect name with `_` shown as spaces
    for an actor affect. A proc's or outcome's `message` replaces the bearer's line; the room line is still sent.
    Nothing is sent when the proc doesn't trigger, picks nothing, or is denied.

  - **Trace.** With `@trace rules` (and `w.trace()` in scenarios), each proc that rolls prints one line: `proc <source>
    <on> roll <r> → <result>`. `<source>` is `affect:<name>`, `item:<item name>` or `item:<item name>:<affect name>`.
    `<r>` is the first draw to two decimals. `<result>` is one of:

    - `no proc`;
    - `nothing`;
    - the skill label;
    - the primitive name (as `primitiveName` in `Declarations.kt`);
    - `cast_allowed denied`;
    - `target gone`.

    Example: `proc item:cracked_wand hit roll 0.37 → //zones/x:lightning_bolt`.

  - **Persistence.** Prototype procs (item, `item_affect`) derive from the prototype and are never saved. Instance
    affects (actor affects and item instance affects) save their `procs` through new `ProcJson`, `OutcomeJson` and
    `EffectJson` codecs that cover every `Effect` variant. Skill outcomes save the label, which is resolved at fire
    time. No migration is needed: the engine is unreleased.

  - **Out of scope:**

    - miss reasons (block, parry, dodge);
    - a `strike` swing primitive and the `counter` skill;
    - procs on spell damage;
    - `on = "swing"` and `on = "miss"`;
    - typed bonus damage (`### Typed bonus damage on attacks`);
    - procs from plugin traits or mob prototypes;
    - exposing procs on the affect or item views.
- **Specification Impact:**
  - §3.10 (the `combat` feature row);
  - §4.2 (the `item` example, the `item_affect` signature in "Item slots", a new "Procs" declaration-rules bullet, the
    "Features" bullet);
  - §4.3 (the `apply_affect` primitive row);
  - §4.4 (a note under the `on_damage` paragraph);
  - §4.5 (the `apply_affect` verb bullet);
  - §5.4 ("Resolution");
  - §7.4 (new M28 row);
  - `docs/CHANGELOG.md`;
  - `template-world/docs/tutorials/03_items_and_equipment.md`;
  - the regenerated API files.
- **Decision Record Updates:** None.

### Example: the cracked wand

Exactly one outcome per hit: one roll picks fireball, fear, lightning, the explosion, or nothing (the 10% left over).

```python
item("cracked_wand", wear="wield", dice="1d4", procs=[proc(on="hit", outcomes=[
  outcome(0.05, skill=":fireball"),
  outcome(0.40, skill=":fear"),
  outcome(0.44, skill=":lightning_bolt"),
  # the remaining 0.10 does nothing
  outcome(0.01, effect=damage("1d12", DAMAGE.fire), target="self",
          message="The wand cracks open and explodes in your hand!"),
])])
```

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first and see them fail.
   - No spec section numbers or decision ids in comments, Kotlin or Starlark.
   - All Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`. The engine is
   unreleased, so no migrations or back-compat shims are needed.
3. **Parallel work:** another milestone (M27) is being implemented in parallel and may touch `docs/SPEC.md`,
   `docs/CHANGELOG.md`, the API files and `Declarations.kt`. Never revert or rewrite text you didn't add. Regenerate
   generated files rather than hand-merging them.
4. **CHANGELOG:** add bullets in a new `### Combat procs` subsection at the top of `## api_version 1 (unreleased)`,
   above whatever subsection is first at that time. Create it in Phase 2, and have each later phase append to it.
5. **API files.** In a phase that changes the script API or the message catalog (Phases 2 and 3), regenerate all three
   API files before the phase's final `./gradlew check`:
   - Run `./gradlew :cli:test -Dgups.updateGolden=true`. It rewrites `cli/src/test/resources/api.golden.json` and the
     `cli/src/test/resources/docs-golden/` files.
   - Run `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout. This rewrites `docs/api/api-1.json`.
   - Read the diff. Only the symbols named in that phase's exit conditions should be new or changed, and `ApiDiffTest`
     must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring the `loot` and `item_affect` examples there.
6. Gradle commands must run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat`, then check
   that code blocks kept their indentation.
7. Scratch files and scenarios go in `local-world-test/` (see `AGENTS.md`), which is deleted when you're done. Never
   read or touch `local-world/`.

---

## Phase 1: The proc model and its plumbing (core)

### Objective & Scope

- **In Scope:**
  - The `Proc`, `ProcOutcome`, `ProcTrigger` and `ProcTarget` types.
  - `procs` fields on `ItemProto`, `ItemAffect`, `Affect` and `Effect.ApplyAffect`.
  - Passing them through `applyAffect`, `applyItemAffect`, `applyEffect`/`applyItemEffect` and the item-split copy.
  - `HeldAffect.procs` and `HeldEffects.procSteps`, in chain order.
- **Out of Scope:**
  - Firing procs (Phase 2).
  - Starlark builtins (Phase 3).
  - Persistence (Phase 4).
  - Spec text: this phase has no visible behavior.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Rules.kt`: `chainSteps` (the source order and the swinging-weapon filter).
  - `core/src/test/kotlin/gups/core/world/Fixture.kt`, `HoldingsTest.kt` and `ItemAffectsTest.kt` (test patterns).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Effects.kt`: the new types, after `Effect`, and the `procs` field on
    `ApplyAffect`.
  - `core/src/main/kotlin/gups/core/model/Protos.kt`: `ItemProto.procs` and `ItemAffect.procs`.
  - `core/src/main/kotlin/gups/core/model/Entities.kt`: `Affect.procs`.
  - `core/src/main/kotlin/gups/core/world/Combat.kt`: the `applyAffect`, `applyItemAffect`, `applyEffect` and
    `applyItemEffect` pass-through.
  - `core/src/main/kotlin/gups/core/world/Items.kt`: the `Affect(...)` copy at about line 77 also copies `procs`.
  - `core/src/main/kotlin/gups/core/world/Holdings.kt`.
  - New test: `core/src/test/kotlin/gups/core/world/ProcSourcesTest.kt`.
- **Do Not Touch:** `script/`, `persist/`, `Rules.kt` and the swing logic in `Combat.kt`.

### Specification & Decision Record Changes

- None.

### Implementation Blueprint

The new types go in `Effects.kt`. See "Proc types" below for the code. The fields:

- `Effect.ApplyAffect`: add `val procs: List<Proc> = emptyList()` as the last parameter.
- `ItemProto`: add `val procs: List<Proc> = emptyList()` next to `modifiers`, with KDoc: "Procs this item contributes
  while worn and active."
- `ItemAffect`: add `val procs: List<Proc> = emptyList()` after `flags`.
- `Affect`: add `val procs: List<Proc> = emptyList()` as the last constructor parameter, with KDoc: "Procs that fire for
  the host (or the item's holder) during auto-attack swings."
- `World.applyAffect(...)` and `World.applyItemAffect(...)`: add a trailing `procs: List<Proc> = emptyList()` parameter
  and pass it into the `Affect(...)` they build. In `applyEffect` and `applyItemEffect`, pass `effect.procs` through for
  `Effect.ApplyAffect`. Grep `Affect(` across `core/src/main` and pass `procs` through wherever an `Affect` is rebuilt
  from another one, such as the item split in `Items.kt`.
- `Holdings.kt`:
  - Add `val procs: List<Proc>` to `HeldAffect`. Fill it from `ItemAffect.procs` and from `Affect.procs` in
    `adminAffectsList()`.
  - Add `data class HeldProcs(val source: String, val item: Item, val procs: List<Proc>)`.
  - Add `val procSteps: List<HeldProcs> = emptyList()` to `HeldEffects`.
  - In `computeHeld`, append steps exactly where `modifierSteps` are appended. A worn active item's non-empty
    `proto.procs` becomes `HeldProcs("item:${item.protoLabel.name}", item, item.proto.procs)`. Inside `add(ha)`, a
    non-empty `ha.procs` becomes `HeldProcs("item:${ha.item.protoLabel.name}:${ha.name}", ha.item, ha.procs)`. The same
    `add` runs for `carried` affects, so they come after the worn ones as on the chain.

#### Proc types

```kotlin
/** When a proc fires during an auto-attack swing. */
enum class ProcTrigger(val word: String) {
  /** The attacker's procs, after a landed swing's damage. */
  HIT("hit"),
  /** The defender's procs, right after the attacker's for the same hit. */
  STRUCK("struck"),
  /** The defender's procs, when a swing at it misses. */
  AVOID("avoid");

  companion object {
    fun byWord(w: String): ProcTrigger? = entries.firstOrNull { it.word == w }
  }
}

/** Whom a proc outcome lands on. */
enum class ProcTarget(val word: String) {
  /** The other actor in the swing. */
  ENEMY("enemy"),
  /** The proc's bearer. */
  SELF("self"),
  /** No actor (a `target = "none"` skill). */
  NONE("none");

  companion object {
    fun byWord(w: String): ProcTarget? = entries.firstOrNull { it.word == w }
  }
}

/**
 * One possible result of a proc. With neither [skill] nor [effect] it does nothing. A null [target]
 * follows the skill's own target, or means [ProcTarget.ENEMY] for an effect.
 */
data class ProcOutcome(
  val weight: Double,
  val skill: Label? = null,
  val effect: Effect? = null,
  val target: ProcTarget? = null,
  val message: String? = null,
) {
  init {
    require(weight in 0.0..1.0) { "outcome weight must be between 0 and 1" }
    require(skill == null || effect == null) { "an outcome takes a skill or an effect, not both" }
  }
}

/** A chance, on [on], to resolve one of [outcomes] for the bearer. */
data class Proc(val on: ProcTrigger, val chance: Double, val outcomes: List<ProcOutcome>) {
  init {
    require(chance in 0.0..1.0) { "proc chance must be between 0 and 1" }
    require(outcomes.isNotEmpty()) { "a proc needs at least one outcome" }
    require(outcomes.sumOf { it.weight } <= 1.0 + 1e-9) { "proc outcome weights add up to more than 1" }
  }
}
```

### Test & Documentation Requirements

`ProcSourcesTest.kt`, using `Fixture` with extra `ItemProto`s, as `DefenderModifierTest` builds its `protos(...)`:

- A worn item's prototype procs appear in `heldEffects.procSteps` with source `item:<name>`, followed by its prototype
  item affect's procs (`item:<name>:<affect>`), then a `carried` affect of an inventory item.
- An item in an inactive slot contributes no worn procs (follow `HoldingsTest`'s inactive-slot setup).
- `applyAffect(..., procs = listOf(p))` stores `p` on the actor's `Affect`. `applyEffect` with an
  `Effect.ApplyAffect(procs = ...)` does too.
- Splitting a stack (`Items.kt`) keeps an instance affect's `procs`.
- The `Proc` constructor rejects weights adding up to more than 1, a chance outside 0..1, and empty outcomes.

### Exit Conditions

- [] `./gradlew :core:test` passes, including `ProcSourcesTest`.
- [] `./gradlew check` passes with zero warnings. The API files are unchanged.

---

## Phase 2: Firing procs in swings (core)

### Objective & Scope

- **In Scope:**
  - `World.fireProcs`.
  - Wiring it into `swing()` for `hit`, `struck` and `avoid`.
  - Outcome resolution for skills and effects, with `cast_allowed`.
  - The two messages and the trace line.
  - The §5.4 spec text and the CHANGELOG subsection.
- **Out of Scope:** Starlark (Phase 3) and persistence (Phase 4).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt`: `CastAction.resolve` (room-range targets, `startCombat` for
    enemy skills, `skill.resolve?.resolve(w, actor, each, null)`).
  - `core/src/main/kotlin/gups/core/world/Rules.kt`: `castAllowed`, `traceRules`.
  - `core/src/main/kotlin/gups/core/world/Hooks.kt`: `invokeOn`'s `diagnose` pattern.
  - `script/src/main/kotlin/gups/script/Declarations.kt`: `primitiveName` (copy its mapping, don't import it).
  - `core/src/test/kotlin/gups/core/world/DefenderModifierTest.kt` and `CombatTest.kt` (fixture, `FixedRules`, forcing
    hits and misses).
- **Files to Modify / Create:**
  - New: `core/src/main/kotlin/gups/core/world/Procs.kt`.
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (`swing` only).
  - `core/src/main/kotlin/gups/core/world/CombatCommands.kt` (`CombatMsg`).
  - New test: `core/src/test/kotlin/gups/core/world/ProcsTest.kt`.
  - `docs/SPEC.md` (§5.4), `docs/CHANGELOG.md`, and the API files (new messages).
- **Do Not Touch:** `script/` sources, `persist/`, and `Rules.kt` (other than reading it).

### Specification & Decision Record Changes

In **§5.4, "Resolution"**:

- Append to step 2: "On a miss, the defender's `avoid` procs fire after the miss messages."
- Add, as a new numbered step 4 after step 3, the text: "After the hit's damage is applied, the attacker's `hit` procs
  fire, then the defender's `struck` procs. Procs fire only from auto-attack swings, never from the effects they
  resolve, so they can't set each other off. A hit that left the defender mortally wounded still fires them."
- Don't add a `(§4.2)` cross-reference yet: the Procs bullet it points to lands in Phase 3, which adds the references.

### Implementation Blueprint

**Messages**, in `CombatMsg` (mirror `HIT_SELF`):

```kotlin
val PROC_SELF = msg("combat.proc.self", "Power surges from {source}!", "source")
val PROC_ROOM = msg("combat.proc.room", "Power surges around {actor}!", "actor")
```

**`Procs.kt`**, with this entry point:

```kotlin
/**
 * Fires [bearer]'s procs for [trigger] in one auto-attack swing against [other]. [weapon] is the
 * swing's weapon; on a hit, worn weapons other than it contribute nothing.
 */
internal fun World.fireProcs(trigger: ProcTrigger, bearer: Actor, other: Actor, weapon: Item?)
```

Algorithm:

1. **Gather the sources** as a snapshot list of private `ProcSource(trace: String, display: String, procs: List<Proc>,
   stillThere: () -> Boolean)`:
   1. For each `bearer.affects` sorted by id with non-empty `procs`:
      - `trace = "affect:${a.name}"`;
      - `display = a.name.replace('_', ' ')`;
      - `stillThere = { a in bearer.affects }`.
   2. For each `step` in `bearer.heldEffects.procSteps`:
      - skip it when `trigger == ProcTrigger.HIT && step.item.proto.dice != null && step.item.location is
        ItemLocation.Equipped && step.item !== weapon`, the same test as `chainSteps`;
      - otherwise `trace = step.source`, `display = step.item.name` (the item's display name; use whatever `Item`
        exposes for messages, such as in `CombatMsg.HIT_SELF` call sites), and `stillThere` is true while the item is
        still held by `bearer`.
2. **Roll each proc.** For each source (skip it if `!stillThere()`), for each `proc` in `procs` with `proc.on ==
   trigger`:
   1. If `!bearer.inWorld`, stop.
   2. Draw `val r = rng.fraction()`. If `r >= proc.chance`, trace `no proc` and continue.
   3. **Pick an outcome.** If `proc.outcomes.size == 1 && proc.outcomes[0].weight >= 1.0`, use it with no second draw.
      Otherwise draw `val p = rng.fraction()` and walk the outcomes with a running total; the first with `total > p`
      wins. If none wins, trace `nothing` and continue.
   4. Call `resolveProcOutcome(...)` (below), which returns the trace result word.
   5. Trace with `traceRules(bearer, "proc ${src.trace} ${trigger.word} roll ${"%.2f".format(Locale.ROOT, r)} →
      $result")`.
3. **Resolve the outcome** in a private `resolveProcOutcome(bearer, other, src, outcome): String`:
   1. `val skill = outcome.skill?.let { protos.skills[it] }`. If `outcome.skill != null && skill == null`, call
      `diagnose("script", "proc on ${src.trace}: unknown skill ${outcome.skill}")` and return `"nothing"`. If
      `skill?.target == TargetKind.ITEM`, diagnose "item skills can't be procs" and return `"nothing"`.
   2. **Work out the target kind.** Use `outcome.target`, or for a skill map `skill.target`: ENEMY → ENEMY, SELF and
      ALLY → SELF, NONE → NONE. For an effect or an empty outcome it is ENEMY. An empty outcome returns `"nothing"`.
   3. **Find the recipient.** ENEMY → `other`, but return `"target gone"` unless `other.inWorld && other.room ===
      bearer.room`. SELF → `bearer`. NONE → `null`.
   4. **For a skill:**
      1. If `castAllowed(RulesContext(this, bearer, recipient, skill = skill)) != null`, return `"cast_allowed denied"`.
         `castAllowed` applies the plugin `cast` restriction before the chain; keep that, and test it.
      2. Send the messages (step 5).
      3. The recipients are `bearer.room.actors.filter { it !== bearer && isSimulated(it) }` when `skill.range ==
         SkillRange.ROOM`, otherwise `listOf(recipient)`.
      4. For each recipient, call `startCombat(bearer, each)` when `skill.target == TargetKind.ENEMY && each != null &&
         each !== bearer`, then `skill.effect?.let { applyEffect(it, bearer, each, skill) }`, then
         `skill.resolve?.resolve(this, bearer, each, null)`.
      5. Return `skill.label.toString()`.
   5. **Messages:** `send(bearer, outcome.message ?: msg(CombatMsg.PROC_SELF, "source" to src.display))`, then
      `tellSight(bearer.room, "proc", bearer, msg(CombatMsg.PROC_ROOM, "actor" to bearer.name.cap()), bearer)`. Mirror
      the `tellSight` call in `CastAction.resolve`.
   6. **For an effect:** send the messages, call `applyEffect(effect, bearer, recipient, null)`, and return the
      primitive name.
4. **Wire it into `swing()`** in `Combat.kt`:
   - On the miss branch, after the `tellPerceivers(...)` and before `return`: `fireProcs(ProcTrigger.AVOID, target, a,
     weapon)`.
   - After `damage(target, hit.losses, type, a)` at the end: `fireProcs(ProcTrigger.HIT, a, target, weapon)`, then
     `fireProcs(ProcTrigger.STRUCK, target, a, weapon)`.

**Invariants:**

- `applyEffect` never calls `swing`, so procs can't recurse.
- The `autoAttack` loop already stops further swings when the target can't fight. Don't add checks there.

### Test & Documentation Requirements

`ProcsTest.kt`, with `Fixture`, `FixedRules(toHit = 100)` for hits and `toHit = 0` for misses, and a fixed seed:

- **Each trigger:**
  - A wielded weapon with `Proc(HIT, 1.0, [outcome effect Damage])` lowers the defender's hp beyond the swing damage and
    sends "Power surges from …" to the attacker.
  - `STRUCK` on the defender's worn item damages the attacker.
  - `AVOID` fires only on a miss, and `HIT` never fires on one.
- **Weapon filter:** a dual-wielder's off-hand proc dagger fires only on its own swing, which you can tell from the
  message count with two swings per round.
- **Outcome tables:**
  - With `chance = 0.0` nothing happens, and only one `rng` draw is made (assert via trace text or a recording rng, if
    `Fixture` supports one).
  - Weights `[0.5 → effect A, 0.5 → effect B]` pick exactly one per hit across a run.
  - Weights adding up to 0.1 often pick nothing.
- **Skill outcomes:**
  - A skill outcome applies the skill's effect, sets `ctx.skill` on the damage chain (assert through a recording
    `damage` modifier, as in `DefenderModifierTest`), and charges no cost and no lag (`waitState` unchanged).
  - A denying `cast_allowed` modifier stops it and sends no message.
- **Targets and resolution:**
  - `target = SELF` hits the bearer.
  - An affect proc on the actor fires, and its message names the affect.
  - The custom `message` replaces the bearer's line.
  - Proc damage that kills the attacker (thorns) doesn't throw, and death runs at the end of the tier.
- **Trace:** with rules tracing on, the trace contains `proc item:` lines.
- **CHANGELOG:** create `### Combat procs` with the bullet "Auto-attack swings fire procs: the attacker's `hit` procs
  after a landed hit, then the defender's `struck` procs; the defender's `avoid` procs on a miss. New engine messages
  `combat.proc.self` and `combat.proc.room`."

### Exit Conditions

- [] `./gradlew :core:test` passes, including `ProcsTest`.
- [] The regenerated API files differ only by the `message:combat.proc.self` and `message:combat.proc.room` symbols.
- [] `./gradlew markdownFormat`, then `./gradlew check`, passes with zero warnings.

---

## Phase 3: `proc()`, `outcome()` and `procs =` in Starlark (script)

### Objective & Scope

- **In Scope:**
  - The `proc` and `outcome` builtins.
  - `procs` on `item`, `item_affect`, the `apply_affect` primitive and the `ctx.apply_affect` verb.
  - All load and call checks, and feature ownership.
  - `DocExamples`, the spec text, CHANGELOG and the API files.
- **Out of Scope:** persistence (Phase 4) and template-world content (Phase 5).

### Context Working Set

- **Read / Reference Only:**
  - `Declarations.kt`:
    - `loot` (the builtin pattern, `LOOT_SPEC`, the probability check);
    - `summon` (resolving a label with or without `LoadCtx`, via `callerFile`);
    - `effectValue`, `primitiveName`, `flattenLeaves`, `effectRefs`;
    - `ctx.ref(skill, RefKind.SKILL, thread)` at about line 907;
    - how `item(modifiers =)` requires `wear`;
    - how `item_affect(modifiers =)` requires `wear` for a `worn` affect.
  - `script/src/main/kotlin/gups/script/Ownership.kt`: `owns(Feature.TRACK, ..., "item.range")` and `refusal`.
  - `script/src/test/kotlin/gups/script/ItemEffectsScriptTest.kt` and `LoaderTest.kt` (test patterns).
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Declarations.kt`:
    - `ProcDecl`, `OutcomeDecl`;
    - the `proc`/`outcome` builtins and their `*_SPEC`s;
    - `procs` in the `item`, `item_affect` and `apply_affect` builtins, their spec param lists, and `affectEffect` (at
      about line 2816).
  - `script/src/main/kotlin/gups/script/Ctx.kt`: the `apply_affect` verb (at about line 1017 and 1116).
  - `script/src/main/kotlin/gups/script/Ownership.kt`.
  - `script/src/main/kotlin/gups/script/DocExamples.kt`.
  - New test: `script/src/test/kotlin/gups/script/ProcScriptTest.kt`.
  - `docs/SPEC.md` (§3.10, §4.2, §4.3, §4.4, §4.5), `docs/CHANGELOG.md`, and the API files.
- **Do Not Touch:** `core/` (except fixing a Phase 1 or 2 bug, with a test) and `persist/`.

### Specification & Decision Record Changes

1. **§3.10, `combat` row:** after "`before_attack`, `on_combat_tick`;" insert "`proc()`, `outcome()` and every `procs`
   argument, and the proc messages;".
2. **§4.2:**
   - In the `item(...)` example, after the `modifiers={}, equip_affects=[],` line, add: `procs=[], # proc(...) entries
     that fire during auto-attacks (§4.2)`.
   - In the "Item slots" bullet, extend the `item_affect(...)` signature with `procs = []`. Change "An item with
     `equip_affects`, `modifiers`, `max_vital_deltas` or `resist_deltas` needs a `wear` slot" to include `procs`, and "A
     `worn` affect with `modifiers` needs a `wear` slot" to "with `modifiers` or `procs`".
   - In the "Features" bullet, add "`procs`, `proc` and `outcome` with `combat`".
   - Add a new bullet after "Use" (see "§4.2 Procs bullet" below).
3. **§4.3:** in the `apply_affect` row, extend the signature with `procs=[]` and append: "`procs` (§4.2, needs `combat`)
   fire for the affect's host, or for the holder of the item it is on."
4. **§4.4:** after the `on_damage` paragraph, add: "To react to one's own landed swing, or to being struck, use procs
   (§4.2): `on_damage` fires room-wide for any damage."
5. **§4.5:** in the `apply_affect` verb bullet, add `procs` to the list it shares with the effect primitive.
6. **§4.2 "When builtins work":** change it to "declaration builtins work only while a package loads; effect primitives,
   `proc` and `outcome` work anywhere."
7. **§5.4 "Resolution":** add `(§4.2)` after "`avoid` procs" in step 2 and after "`struck` procs" in step 4. Phase 2
   wrote those sentences without the references.

#### §4.2 Procs bullet

> - **Procs:** `proc(on, chance = 1.0, skill = None, effect = None, target = None, message = None, outcomes = None)` is
>   something that may happen during an auto-attack swing (§5.4), for `item(procs = [...])`, `item_affect(procs =
>   [...])` and `apply_affect(procs = [...])`. `on` is `"hit"` (the bearer's swing landed; a worn weapon counts only on
>   its own swing), `"struck"` (the bearer was hit) or `"avoid"` (a swing at the bearer missed). `chance` is a
>   probability between 0 and 1. A proc gives one of `skill` (a skill label), `effect` (an effect primitive) or
>   `outcomes`, a list of `outcome(weight, skill = None, effect = None, target = None, message = None)`. Exactly one
>   outcome is chosen per trigger by one roll; the weights are probabilities whose total is at most 1, and the rest is
>   "nothing happens", as is an outcome with no `skill` or `effect`. `target` is `"enemy"` (the other actor in the
>   swing), `"self"` or `"none"`; when omitted it follows a skill's own target (`ally` counts as `self`), or is
>   `"enemy"` for an effect. A proc resolves at once with the bearer as caster: a skill is checked against the bearer's
>   `cast` restriction (§4.19) and `cast_allowed` chain (a denial skips it) and then its `effect` and `resolve`, with no
>   cost, warmup, lag, range check or cast hooks, and the bearer needn't know it; an `item` skill can't be used. Proc
>   effects may not `summon`, and their `apply_affect` needs a duration and no `applies`. Procs fire only from
>   auto-attack swings, never from effects, so they can't set each other off. The bearer is told `combat.proc.self` (or
>   the `message`) and onlookers `combat.proc.room`. Procs are checked in modifier-chain order (§4.6): the bearer's
>   affects, then each worn item and its item affects, then `carried` item affects. `skill` needs the `skills` feature;
>   procs need `combat`.

### Implementation Blueprint

1. **Decl values:** `internal class ProcDecl(val proc: Proc) : DeclValue("proc")` and `internal class OutcomeDecl(val
   outcome: ProcOutcome) : DeclValue("outcome")`, next to `LootDecl`.
2. **Builtins.** Mirror `loot`'s `@StarlarkMethod` shape, but with `contentCtx` replaced by the `summon` package lookup,
   so they work at run time:
   - `outcome(weight, skill=None, effect=None, target=None, message=None)`:
     - `weight` is a number in 0..1 ("outcome(weight): must be between 0 and 1").
     - `skill` resolves with `label(str, pkg, "outcome(skill)")`. If a `LoadCtx` is present, also call `ctx.ref(label,
       RefKind.SKILL, thread)`. If `Feature.SKILLS !in features`, fail with `FeatureOwnership.refusal("proc(skill)",
       Feature.SKILLS)`.
     - `effect` goes through `effectValue`, then `checkProcEffect` (below).
     - Giving both `skill` and `effect` fails: "outcome(): give skill or effect, not both".
     - `target` is checked with `choice(..., listOf("enemy", "self", "none"))`.
     - `message` must be a non-blank string.
   - `proc(on, chance=1.0, skill=None, effect=None, target=None, message=None, outcomes=None)`:
     - `on` is checked with `choice(..., listOf("hit", "struck", "avoid"))`, and `chance` is in 0..1.
     - With `outcomes`: it must be a non-empty list of `OutcomeDecl`, it may not be combined with `skill`, `effect`,
       `target` or `message` ("proc(outcomes): can't be combined with skill, effect, target or message"), and its
       weights add up to at most 1 ("proc(outcomes): weights add up to more than 1").
     - Without `outcomes`: exactly one of `skill` and `effect` ("proc(): give one of skill, effect or outcomes"). Build
       one `ProcOutcome(1.0, ...)` with the same checks as `outcome`.
   - `checkProcEffect(e, what)`: for each leaf in `flattenLeaves(e)`, an `Effect.Summon` fails ("$what: summon can't be
     used in a proc"), and an `Effect.ApplyAffect` with `duration == null` or `applies != null` fails ("$what:
     apply_affect in a proc needs a duration and no applies"). When a `LoadCtx` is present, also run `effectRefs`.
3. **`procs` arguments.** A list of `ProcDecl`, with any other element an error naming the argument:
   - `item(procs=)` → `ItemProto.procs`. It needs `wear`, with the same message style as for `modifiers`.
   - `item_affect(procs=)` → `ItemAffect.procs`. A `worn` affect with procs needs its item to have `wear`; reuse the
     check that `modifiers` uses.
   - The `apply_affect` primitive, and `affectEffect`'s shared parameters, take `procs` (default `[]`) →
     `Effect.ApplyAffect.procs`.
   - The `ctx.apply_affect` verb passes `procs` into `applyAffect` and `applyItemAffect`.
4. **Ownership:** `owns(Feature.COMBAT, ...)` covers the `proc` and `outcome` builtins and the `procs` arguments of
   `item`, `item_affect` and `apply_affect`. Use whatever symbol strings `api.json` gives them, and check by grepping
   the regenerated `api-1.json`. Mirror `"item.range"`. A world without `combat` then gets "`procs` needs the `combat`
   feature" at load, and an error at call time for the verb.
5. **API docs:** each new parameter gets a one-line `doc`. `DocExamples` gains `proc` and `outcome` entries: a `proc(on
   = "hit", chance = 0.1, effect = damage(dice = "1d6", type = DAMAGE.fire))` and a two-outcome table.

### Test & Documentation Requirements

`ProcScriptTest.kt`, mirroring `ItemEffectsScriptTest` world setup:

- **Load results:**
  - `item(wear = "wield", dice = "1d4", procs = [proc(on = "hit", effect = damage(...))])` loads into `ItemProto.procs`
    with one outcome of weight 1, target null.
  - An outcome table loads in order with its weights.
  - `proc(skill = ":fireball")` resolves the label in the declaring package.
- **Load errors**, each asserting its message: an unknown `on`; `chance = 1.5`; weights adding up to 1.2; `outcomes`
  with `skill`; neither `skill` nor `effect`; both; `summon` in a proc effect; `apply_affect` without a duration in a
  proc; `procs` on an item without `wear`; a non-proc element in `procs`; `procs` in a world without `combat`; `skill =`
  in a world without `skills`; and an unknown skill label (an unresolved-ref error).
- **End to end:**
  - Through a script world, a hook calls `ctx.apply_affect(ctx.actor, "flame_blade", 50, procs = [proc(on = "hit",
    effect = damage(dice = "1d1", type = DAMAGE.fire))])`, and the next landed swing fires it (assert on the attacker's
    output).
  - An `item_affect(procs = ...)` fires while worn.
- **CHANGELOG bullet:** "Added `proc(...)` and `outcome(...)`, and `procs` on `item`, `item_affect` and `apply_affect`
  (the primitive and the verb): chance-based skills or effects that fire during auto-attack swings, with weighted one-of
  tables."

### Exit Conditions

- [] `./gradlew :script:test` passes, including `ProcScriptTest`.
- [] The regenerated API files add only `proc`, `outcome` and the `procs` parameters (plus their docs and examples).
- [] `./gradlew markdownFormat`, then `./gradlew check`, passes with zero warnings.
- [] The §3.10, §4.2, §4.3, §4.4 and §4.5 text matches the behavior.

---

## Phase 4: Saving instance-affect procs (persist)

### Objective & Scope

- **In Scope:** JSON codecs for `Proc`, `ProcOutcome` and every `Effect` variant; a `procs` field on `AffectJson`;
  restore; round-trip tests.
- **Out of Scope:** any behavior change. Prototype procs are never saved.

### Context Working Set

- **Read / Reference Only:**
  - `persist/src/main/kotlin/gups/persist/Codec.kt`: `RefJson`, `HookRef.json()`/`RefJson.ref()`, `AffectJson`,
    `affectJson`, `affectFromJson`, the modifier map encoding, and how `TimerJson.args` encodes plain values.
  - `persist/src/test/kotlin/gups/persist/CodecTest.kt` and `ItemAffectsPersistenceTest.kt`.
  - `core/src/main/kotlin/gups/core/model/Effects.kt` (every variant).
- **Files to Modify / Create:**
  - `persist/src/main/kotlin/gups/persist/Codec.kt`, or a new `persist/src/main/kotlin/gups/persist/EffectCodec.kt` if
    `Codec.kt` grows unwieldy.
  - New test: `persist/src/test/kotlin/gups/persist/ProcPersistenceTest.kt`.
  - `docs/CHANGELOG.md` (no bullet needed unless saved-state behavior is documented there).
- **Do Not Touch:** `core/` and `script/` behavior.

### Specification & Decision Record Changes

- None. §3.6 doesn't list affect fields.

### Implementation Blueprint

- **`@Serializable` classes**, with every field defaulted where sensible:
  - `OutcomeJson(weight: Double, skill: String? = null, effect: EffectJson? = null, target: String? = null, message:
    String? = null)`;
  - `ProcJson(on: String, chance: Double, outcomes: List<OutcomeJson>)`;
  - `EffectJson` as a `@Serializable sealed class` with one `@SerialName`'d subclass per variant: `damage(dice, type)`,
    `heal(dice, vital)`, `apply_affect(...all ApplyAffect fields, including nested procs...)`, `dispel`, `knockback`,
    `pull`, `stun`, `summon(label, count, duration)`, `sequence(effects)` and `plugin(pluginId, name, args:
    JsonObject)`.
  - If the codebase's `Json` instance has no class discriminator configured for sealed types, use a single data class
    with a `kind: String` and nullable fields instead. Pick whichever matches the existing `Json` configuration.
- **Encodings:**
  - `Dice` as its `toString()`, read back with `Dice.parse`.
  - `DamageType` and `Channel` by name.
  - `Label` as its string form; check the existing label encoding, for example for `summon`.
  - `HookRef` via `RefJson`, and `Modifiers` as the existing `scriptName → RefJson` map.
  - Plugin `args` are plain values (null, Boolean, Long or Int, Double, String, List, String-keyed Map). Encode them
    with the same plain-value encoder as timer args if it accepts these types; otherwise add a private
    `plainJson`/`fromPlainJson` pair.
- **Wiring:**
  - Add `val procs: List<ProcJson> = emptyList()` to `AffectJson`.
  - `affectJson` encodes `a.procs`, and `affectFromJson` decodes into `Affect(procs = ...)`.
  - Unknown `on`/`target` words, and an unparseable effect, fail the decode with a clear exception, as other malformed
    fields do.
  - Item instance affects already use `affectJson`, so they are covered.

### Test & Documentation Requirements

`ProcPersistenceTest.kt`:

- A round trip of an `Affect` carrying one proc per `Effect` variant, including a nested `Sequence`, an `ApplyAffect`
  with `onTick`, `modifiers` and nested `procs`, and a `Plugin` with list and map args, compares equal.
- **Restore:** an actor with a timed affect holding a skill-outcome proc, saved and restored through the
  `PersistFixture` path, still has the proc. An item instance affect with procs survives the same way (mirror
  `ItemAffectsPersistenceTest`).
- **Prototype procs:** an item whose procs come only from its prototype writes no `procs` in its JSON.

### Exit Conditions

- [] `./gradlew :persist:test` passes, including `ProcPersistenceTest`.
- [] `./gradlew check` passes with zero warnings, and the API files are unchanged.

---

## Phase 5: Template-world examples, tutorial, and finalization

### Objective & Scope

- **In Scope:**
  - Three tutorial items with zone tests.
  - The tutorial doc section.
  - The §7.4 row.
  - Marking the `DESIGN_NEEDED` entry completed.
  - A full-suite run and an end-to-end check.
- **Out of Scope:** engine changes. If a test here exposes an engine bug, fix it in the module that has it, with a test,
  and say so in the summary.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/zones/tutorial/items.star`: `dagger`, `shadow_cloak` and `siphon_amulet` (style).
  - `template-world/zones/tutorial/tutorial_test.star`: `_world(t)`, `_dummy_fight` and `_swings_in_one_round`.
  - `template-world/MODULE.gups`: damage types are `bludgeon`, `slash`, `pierce` and `fire`. The template declares no
    skills, so the examples use `effect =`.
  - `AGENTS.md` "End-to-end checks".
- **Files to Modify / Create:**
  - `template-world/zones/tutorial/items.star`.
  - `template-world/zones/tutorial/tutorial_test.star`.
  - `template-world/docs/tutorials/03_items_and_equipment.md`.
  - `docs/SPEC.md` (§7.4), `docs/DESIGN_NEEDED.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** core, script, persist and plugin-api sources; `rules/combat.star` formulas.

### Specification & Decision Record Changes

- **§7.4:** add a row after the last existing row (after M27 if it has landed; otherwise after the current last row).
  Write it as a single table-row line; the quote below is wrapped only for reading:

  > | **M28** | Combat procs: `proc(...)` and `outcome(...)` with weighted one-of tables; `procs` on `item`,
  > `item_affect` and `apply_affect`; `hit`, `struck` and `avoid` triggers on auto-attack swings, in modifier-chain
  > order with the swinging-weapon filter; skill procs through `cast_allowed` only; the `combat.proc.*` messages; saved
  > instance-affect procs; the tutorial's `ember_dagger`, `bramble_vest` and `cracked_wand` (§3.10, §4.2, §4.3, §4.4,
  > §4.5, §5.4). |

- **`docs/DESIGN_NEEDED.md`:** rename the heading to `### Combat procs: items and affects that cast on attack (COMPLETED
  — M28)`, following earlier completed entries, and leave the body in place.

### Implementation Blueprint

New items in `items.star`, in the file's style (short comments, no lambdas):

1. **`ember_dagger`**: `wear = "wield"`, `dice = "1d4"`, `damage_type = DAMAGE.pierce`, `procs = [proc(on = "hit",
   chance = 0.25, effect = damage(dice = "1d4", type = DAMAGE.fire))]`. A comment says it fires only when the dagger
   itself lands a swing.
2. **`bramble_vest`**: `wear = "body"` (the same slot as `shadow_cloak`, so a test wears only one), `stat_deltas =
   stats(armor = 1)`, `procs = [proc(on = "struck", effect = damage(dice = "1d2", type = DAMAGE.pierce), message =
   "Thorns rake your attacker!")]`. A comment says the thorns can't set off further procs.
3. **`cracked_wand`**: `name = "a cracked wand of many colors"`, `wear = "wield"`, `dice = "1d2"`, `damage_type =
   DAMAGE.bludgeon`, `procs = [proc(on = "hit", outcomes = [...])]` with:
   - `outcome(0.05, effect = damage(dice = "3d6", type = DAMAGE.fire), message = "A ball of fire bursts from the
     wand!")`;
   - `outcome(0.40, effect = apply_affect(name = "frightened", duration = 12, stat_deltas = stats(dex = -2), expire_msg
     = "You steady your nerves."), message = "The wand flashes a terrifying violet!")`;
   - `outcome(0.44, effect = stun(2), message = "A dazzling arc of light leaps from the wand!")`;
   - a comment saying the remaining 10% does nothing;
   - `outcome(0.01, effect = damage(dice = "1d12", type = DAMAGE.fire), target = "self", message = "The wand cracks open
     and explodes in your hand!")`.

### Test & Documentation Requirements

- **`tutorial_test.star`**, reusing `_dummy_fight`'s setup (high-hp player, `kill dummy`, fixed seed):
  - `test_ember_dagger_procs_on_its_own_hits`: wielding the ember dagger for a long fight, the player's output contains
    "Power surges from". With the plain `dagger` at the same seed, it never does.
  - `test_bramble_vest_rakes_attackers`: wearing the vest while the dummy swings back, the output contains "Thorns rake
    your attacker!".
  - `test_cracked_wand_picks_one_outcome`: over a long fight with the wand, at least one of the wand messages appears,
    and `w.diagnostics()` has no `script` errors. If no outcome shows at the chosen seed, pick another and say so in a
    comment.
- **`03_items_and_equipment.md`:** add a "Procs" subsection after the defender-modifiers subsection, covering:
  - the three triggers;
  - `chance` against outcome tables (the wand snippet);
  - the swinging-weapon rule;
  - that skill procs skip cost, warmup and lag but obey `cast_allowed`;
  - that procs never chain.

  Keep fenced code blocks at column 0.

- **CHANGELOG bullet:** "The tutorial zone has an `ember_dagger` (`hit`), a `bramble_vest` (`struck`) and a
  `cracked_wand` (an outcome table)."

- If `gups docs` output for the template world changes, regenerate the affected goldens and read the diff.

### Exit Conditions

- [] `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups test --world template-world
  //zones/tutorial/...`, passes.
- [] An end-to-end `gups scenario` in `local-world-test/` passes: a player wields the cracked wand, wears the bramble
  vest, types `kill dummy`, and fights for several rounds with `w.trace()` on. The printed trace shows `proc item:`
  lines, and no `script_error` diagnostics appear. Delete `local-world-test/` afterwards.
- [] `./gradlew markdownFormat`, then `./gradlew check`, passes with zero warnings.
- [] The `DESIGN_NEEDED` heading is marked `(COMPLETED — M28)` and the entry body is still present.
- [] The §3.10, §4.2, §4.3, §4.4, §4.5, §5.4 and §7.4 text matches the implemented behavior.
