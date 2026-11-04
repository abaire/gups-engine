# Milestone 27: Typed Bonus Damage on Attacks

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, section "Combat and skills", heading `### Typed bonus damage on
  attacks`.

- **Design Summary:**

  - **A new kernel modifier point, `bonus_damage`, on the attacker's chain.** Its value is a `damages(...)` typed value
    (damage type → int). The base is empty (`damages()`), and there is no rules export. Like `damage`, it runs for
    auto-attack swings and for the `damage(...)` effect, with any features, so it is kernel-owned. It visits the full
    standard chain order: zone, regions, room, traits, affects, worn items, then `carried` item affects. It joins the
    swing-weapon filter: during a swing, a worn item with `dice` contributes only when it is `ctx.weapon`, exactly as
    for `to_hit` and `damage`. In the `bonus_damage` chain, `ctx.damage_type` is the hit's primary type.

  - **There is no declarative `bonus_damage=` field on items or affects.** Builders write a modifier. Declarative sugar
    may come later, and it is out of scope here.

  - **Spells are included, and modifiers scope themselves.** On a swing, `ctx.slot` and `ctx.weapon` are set. On a
    `damage(...)` effect, `ctx.slot` is `None` and `ctx.skill` is set. Outside swings, worn weapons contribute like any
    worn item. So:

    - A weapon's modifier that should add only to its own swings begins with `if ctx.slot == None: return base`.
    - An unguarded modifier, such as one on an amulet, adds to everything its bearer deals.
  - **Each part is mitigated on its own, then applied as one damage event.** A hit, or a `damage(...)` effect, resolves
    like this:
    1. **The primary part runs as today:** the raw amount → the attacker's `damage` chain → the defender's
       `receive_damage` chain → floor at 0 → `mitigate(primary type)` → the defender's `mitigate` chain.
    2. **The attacker's `bonus_damage` chain runs once**, on `damages()`.
    3. **Each bonus part `(type, amount)` with `amount > 0` runs on its own:** the defender's `receive_damage` chain →
       floor at 0 → `mitigate(type)` → the defender's `mitigate` chain. Parts go in the order of the schema's
       `damage_types`, and `ctx.damage_type` is set to that part's type. A part of the same type as the primary is still
       a separate part. A part of 0 or less after the attacker's chain is dropped, and no defender chain runs for it.
    4. **The losses of all parts are added together per vital** (gains included), and `World.damage` runs once:
       - one `on_damage`, one `interrupt_check` with the combined positive total, and one `condition` ask;
       - `ctx.type` stays the primary type, and `ctx.losses` is the combined positive losses.
  - **`on_damage` gains `ctx.parts`**, an ordered list of `struct(type, losses)`:
    - the primary part comes first, then each bonus part that ran;
    - `losses` is that part's positive losses, as `vitals(...)`;
    - for the raw `ctx.damage` verb, and for a hit with no bonus, it holds the single part.
  - **Typed values gain `+`.** It adds two typed values of the same kind key by key, and works for all four kinds:
    `vitals`, `stats`, `resists` and the new `damages`. Mixing kinds is an error naming both constructors. The idiomatic
    modifier is `return base + damages(fire = ctx.roll("1d2"))`, which never drops parts that earlier steps added.
  - **Trace.** After the primary part's `mitigate` step, the same line continues with ` → bonus_damage` and the
    attacker's steps, then one ` → part <type> <amount>` segment per part with that part's defender steps and its
    `mitigate` step. For example: `rules.damage base=6 → mitigate hp 4 slash → bonus_damage → item:flaming_sword
    damages(fire = 2) → part fire 2 → defender:receive_damage → item:oily_rags 4 → mitigate hp 4 fire`. When the
    attacker has no `bonus_damage` step, nothing is added, so existing traces don't change.
  - **Plugins.** Plugin traits can declare `BONUS_DAMAGE` modifiers. They receive and return a `Map<String, Long>` keyed
    by damage type. `PluginModifierCall.actor` is the attacker, the trait's bearer. `MODIFIER.bonus_damage` exists
    automatically, because `MODIFIER` is built from the enum.
  - **Template ruleset.** The template's `mitigate` applies armor, and the minimum of 1 that armor leaves, only to
    physical types (`bludgeon`, `slash`, `pierce`). `fire` is reduced only by resist. This keeps armor from eating each
    bonus part, and it means fire spells ignore armor (a small, intended balance change).
  - **Template samples:**
    - `flaming_sword`: guarded, so it adds +1d2 fire to its own swings only.
    - `ember_amulet`: unguarded, so it adds +1 fire to everything its wearer deals, spells included.
    - `oily_rags`: `stat_deltas(armor = 4)`, plus a `receive_damage` modifier that doubles fire parts.
  - **Out of scope:**
    - Declarative bonus fields.
    - Procs and `on_hit` hooks (`### Combat procs`).
    - To-hit rolls for spells.
    - Changes to the robot-world sample. Its `mitigate` already applies plating only to `kinetic`.
- **Specification Impact:**
  - §3.9 ("Typed values": the constructor table gains `damages(**n)`; a new sentence on `+`);
  - §3.10 (the kernel row of the feature table, if it lists the kernel modifier points);
  - §4.3 (the `damage(dice, type)` primitive row);
  - §4.4 (the hook catalog's `on_damage` row and the `on_damage` paragraph);
  - §4.5 (the `ctx` fields list, if it names `losses`);
  - §4.6:
    - "Modifier points (v0)" and its ownership sentence;
    - "Attack and defense";
    - "Weapons in swings";
    - the trace paragraph;
    - a new "Bonus damage" paragraph;
  - §4.19 (the trait "Modifiers" bullet, if it lists points);
  - §5.4 ("Resolution" step 3);
  - §7.4 (a new M27 row);
  - `docs/CHANGELOG.md`, `docs/PLUGINS.md` and `template-world/docs/tutorials/03_items_and_equipment.md`;
  - the regenerated API files.
- **Decision Record Updates:** None. No existing `D-xxx` covers this area: the former D-111 is already folded into §4.6.
  Do not add new decision records for anything this document settles. Log a `D-xxx` only for a genuinely unsettled
  choice, following `AGENTS.md`.

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first and see them fail.
   - No spec section numbers or decision ids in comments, Kotlin or Starlark.
   - All Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`. The engine is
   unreleased, so no migrations or back-compat shims are needed.
3. Add CHANGELOG bullets in a new `### Typed bonus damage` subsection at the top of `## api_version 1 (unreleased)`,
   above the current first subsection. Create the subsection in Phase 1, and have each later phase append its bullets to
   it.
4. In a phase that changes the script API (Phases 1, 2, 3 and 4), regenerate all three API files before the phase's
   final `./gradlew check`:
   - Run `./gradlew :cli:test -Dgups.updateGolden=true`. It rewrites `cli/src/test/resources/api.golden.json` and the
     `cli/src/test/resources/docs-golden/` files.
   - Run `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout. This rewrites `docs/api/api-1.json`.
   - Read the diff. Only the symbols named in that phase's exit conditions should be new or changed, and `ApiDiffTest`
     must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing modifier-point or constructor example
     there.
5. Gradle commands must run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that
   code blocks kept their indentation.
6. Never read or touch `local-world/`. Scratch scenarios and world copies go in `local-world-test/` (see `AGENTS.md`),
   which you delete when done.

---

## Phase 1: The `damages(...)` constructor and `+` on typed values

### Objective & Scope

- **In Scope:**
  - A `DAMAGES` typed kind with the constructor `damages(**n)`, keyed by declared damage types.
  - `+` between two typed values of the same kind.
  - Spec text, tests, API files and a CHANGELOG bullet.
- **Out of Scope:**
  - Any modifier point, including `bonus_damage` (Phase 3).
  - Any use of `damages(...)` in declarations. It is a value type only: no item or affect argument accepts it.

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Schema.kt`: `TypedKind`, `TypedMap`, `typed()`, `checkKeys`,
    `SchemaBuiltins.build` and how `resists` is predeclared.
  - `script/src/test/kotlin/gups/script/SchemaTest.kt`: the existing constructor tests, as the test pattern.
  - `net.starlark.java.eval.HasBinary` (in the `com.eed3si9n.starlark:starlark:4.2.1` jar; the interface is present).
  - SPEC §3.9 "Typed values" (around line 732).
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Schema.kt`.
  - `script/src/test/kotlin/gups/script/SchemaTest.kt`.
  - The API model entry for predeclared constructors, wherever `resists` is listed: search `ApiModel.kt` and
    `Declarations.kt` for `"resists"` and mirror it.
  - `docs/SPEC.md` (§3.9), `docs/CHANGELOG.md`, and the three API files.
- **Do Not Touch:** `core/`, `plugin-api/`, the template world.

### Specification & Decision Record Changes

- **§3.9 constructor table:** add a row after `resists`:

  > | `damages(**n)` | declared damage types | the value of `bonus_damage` modifiers (§4.6) |

  Phase 3 adds the `bonus_damage` point itself. Until then, the row's cross-reference points at text that doesn't exist
  yet, so phrase the row as "amounts of damage by type, such as the value of `bonus_damage` modifiers (§4.6)". Phase 3
  then makes the link real.

- **§3.9, after the sentence "Their values are ints, and they read back like structs":** add:

  > Two values of the same constructor add with `+`, key by key, so `vitals(hp = 2) + vitals(hp = 1, mana = 3)` is
  > `vitals(hp = 3, mana = 3)`; adding values of different constructors is an error that names both.

- **CHANGELOG bullet:** "Added the `damages(**n)` typed value, keyed by damage type, and `+` between typed values of the
  same kind (`vitals`, `stats`, `resists`, `damages`), which adds them key by key."

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  internal enum class TypedKind(val constructor: String, val noun: String) {
    VITALS("vitals", "vital"),
    STATS("stats", "stat"),
    RESISTS("resists", "damage type"),
    DAMAGES("damages", "damage type");
    // known(): DAMAGES -> schema.damageTypes
  }

  internal class TypedMap(...) : Structure, HasBinary {
    override fun binaryOp(op: TokenKind, that: Any, thisLeft: Boolean): Any?
  }
  ```
- **Algorithmic Steps & Invariants:**
  1. Add `DAMAGES` to `TypedKind`, and extend `known()` so it returns `schema.damageTypes` for it.
  2. In `SchemaBuiltins`, predeclare `damages` exactly as `resists` is predeclared, through `build(TypedKind.DAMAGES,
     kwargs)`.
  3. `TypedMap.binaryOp`:
     - Handle only `TokenKind.PLUS`; return `null` for every other operator, so Starlark reports its usual "unsupported
       binary operation" error.
     - When `that` is a `TypedMap` of the same `kind`, return a new `TypedMap` whose values are the key-by-key sum. Keys
       come from either side, keeping the left operand's key order and then any new keys from the right. Keep `known`.
       Result entries that sum to 0 stay present (the `repr` shows them); `getValue` already reads 0 for an absent key,
       so this is cosmetic, but keep it deterministic.
     - When `that` is a `TypedMap` of another kind, throw an `EvalException`: "can't add `<left constructor>(...)` and
       `<right constructor>(...)`". Mind `thisLeft`: the left constructor is the left operand.
     - When `that` is anything else, return `null`.
  4. Equality and hash code are unchanged.

### Test & Documentation Requirements

- **`SchemaTest.kt`:**
  - `damages(fire = 2)` reads back `.fire == 2`, and an undeclared type reads back `0`.
  - A misspelled type fails with a suggestion.
  - A plain dict where a `damages` is expected fails by naming `damages(...)`. Test this through `typed()` directly.
  - `vitals(hp = 2) + vitals(hp = 1, mana = 3) == vitals(hp = 3, mana = 3)`.
  - `damages(fire = 1) + damages(cold = 2)` has both keys.
  - `vitals() + stats()` fails, and the message names `vitals` and `stats`.
  - `vitals(hp = 1) + 1` fails with Starlark's unsupported-operation error.
- **KDoc:** on `binaryOp`, say what `+` means.

### Exit Conditions

- [] `./gradlew :script:test` passes.
- [] After the API regeneration, the only new symbol is `damages` (plus any example entry).
- [] `./gradlew check` passes with zero warnings.

---

## Phase 2: Bonus parts in the core damage pipeline

### Objective & Scope

- **In Scope:**
  - `ModifierPoint.BONUS_DAMAGE` (attacker chain).
  - The swing-weapon filter.
  - The per-part resolution in `resolveDamage`.
  - `DamagePart` and its list on `World.damage` and `HookContext`.
  - The trace.
  - All tested in `core` with Kotlin modifiers through `RecordingHost`.
  - **The minimum script wiring the new enum entry forces:**
    - The `baseValue`/`pointValue` branches and the `ModifierPointSpec`.
    - The API model's completeness check (`ApiModel.kt` ~line 678) fails without a spec for every `ModifierPoint`.
    - `MODIFIER` is built from the enum, so `MODIFIER.bonus_damage` and `modifier:bonus_damage` appear in the API files
      in this phase.
- **Out of Scope:**
  - `ctx.parts` and the script-level tests (Phase 3).
  - Plugins (Phase 4).
  - The spec text for the point (Phase 3 writes it, together with `ctx.parts`).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt`: `RulesContext` (a plain class, not a data class; lines
    21–70).
  - `core/src/main/kotlin/gups/core/model/Spatial.kt`: `DamageType`.
  - `core/src/main/kotlin/gups/core/model/Schema.kt`: `damageTypes`.
  - `core/src/test/kotlin/gups/core/world/DefenderModifierTest.kt`: the `Fixture`, `RecordingHost`, `FixedRules` and
    `mod(...)` patterns. Mirror them.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt`: the `ModifierPoint` enum.
  - `core/src/main/kotlin/gups/core/world/Rules.kt`: `chainSteps`, `resolveDamage`, `DamageResult`.
  - `core/src/main/kotlin/gups/core/world/Combat.kt`: `swing` (line ~160), the `Effect.Damage` branch (line ~582),
    `World.damage` (line ~186).
  - `core/src/main/kotlin/gups/core/world/Hooks.kt`: `HookContext`.
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt`: a copy helper on `RulesContext`.
  - Create `core/src/test/kotlin/gups/core/world/BonusDamageTest.kt`.
  - `script/src/main/kotlin/gups/script/Rules.kt`: only `baseValue`, `pointValue` (~lines 292–320) and the
    `ModifierPointSpec` list (~1100–1180). See "Script wiring" below.
  - The three API files (guardrail 4).
- **Do Not Touch:** `plugin-api/` (beyond a compile fix, see step 1), the template world, `docs/SPEC.md`, and the rest
  of `script/`.

### Specification & Decision Record Changes

- None in the spec in this phase. Phase 3 writes the spec for the whole behavior. The CHANGELOG bullets also wait for
  Phase 3.

### Implementation Blueprint

- **Signatures & Types:**

  ```kotlin
  // Protos.kt, in ModifierPoint, after DAMAGE:
  /** Adds typed damage parts to a hit or damage effect, on the attacker's chain. */
  BONUS_DAMAGE("bonus_damage"),

  // Hooks.kt or Rules.kt (next to DamageResult):
  /** One typed part of a damage event and the positive vital losses it caused. */
  data class DamagePart(val type: DamageType, val losses: Map<String, Int>)

  // Rules.kt:
  class DamageResult(val losses: Map<String, Int>, val parts: List<DamagePart>, val text: String)
  fun World.resolveDamage(c: RulesContext, base: Int, type: DamageType): DamageResult

  // RulesEngine.kt, a member of RulesContext:
  fun withDamageType(type: DamageType): RulesContext  // a copy with only damageType changed

  // Combat.kt:
  fun World.damage(
    target: Actor,
    losses: Map<String, Int>,
    type: DamageType,
    source: Actor? = null,
    parts: List<DamagePart> = listOf(DamagePart(type, losses.filterValues { it > 0 })),
  )

  // Hooks.kt, in HookContext:
  val parts: List<DamagePart>? = null,
  ```
- **Algorithmic Steps & Invariants:**
  1. **Enum.** Add `BONUS_DAMAGE` (not a defender point). Check every exhaustive `when` over `ModifierPoint` in `core`
     and make it compile. Script and plugin-api `when`s have `else` branches or are handled in Phases 3 and 4. If one
     outside `core` fails to compile, add the narrowest branch that keeps today's behavior (for example, return `base`),
     and note it for the later phase.
  2. **Swing filter.** In `chainSteps` (`Rules.kt` ~line 65), extend the condition `(point == ModifierPoint.TO_HIT ||
     point == ModifierPoint.DAMAGE)` to include `ModifierPoint.BONUS_DAMAGE`.
  3. **`withDamageType`.** Return a new `RulesContext` that copies every constructor argument and replaces `damageType`.
     List every field explicitly. Add a test that fails if a field is added and not copied, if a simple reflective check
     is practical; otherwise, put a KDoc note on the class.
  4. **`resolveDamage`:**
     - Compute the primary part as today (`atk`, `recv`, `amount`, `losses`, `mit`), keeping the same text.
     - Run `chain(ModifierPoint.BONUS_DAMAGE, c, emptyMap<String, Int>()) { it.showDamages() }`. Use the
       `chainSteps(...)` + `runSteps(...)` primitives directly, so that you control the text: when the step list is
       empty, add no text at all.
     - When there are steps, append ` → bonus_damage` and then each step's ` → <source> <value>`. `showDamages()`
       renders `damages(fire = 2)`, mirroring `TypedMap.repr`: `damages(` + `k = v` joined by `, ` + `)`.
     - For each name in `schema.damageTypes`, in order, where the bonus map has a value `> 0`:
       - `pc = c.withDamageType(DamageType(name))`;
       - append ` → part <name> <amount>`;
       - run `defenderSegment(RECEIVE_DAMAGE, pc, amount)`, floor at 0, then `rules.mitigate(pc, floored,
         DamageType(name))`, appending ` → mitigate <losses> <name>`, then `defenderSegment(MITIGATE, pc, losses)`.
     - Keys in the bonus map that aren't declared types can't occur from scripts, since `damages()` checks them. From a
       plugin, Phase 4 validates them. In core, skip unknown keys silently; the type order above already does that.
     - **Combine:** sum the final losses of all parts per vital (gains included), so a `hp 4` and an `hp -1` become `hp
       3`.
     - `parts` holds the primary part first, then each bonus part that ran, each with its final losses filtered to
       positive values.
     - Return `DamageResult(combined, parts, text)`.
  5. **Callers.** In `swing` and the `Effect.Damage` branch, pass `hit.parts` to `damage(...)`. The `type` argument
     stays the primary type.
  6. **`World.damage`.** Unchanged except for one thing: when `total > 0`, pass `parts = parts` into the `ON_DAMAGE`
     `HookContext`. The raw `ctx.damage` verb (script `Ctx.kt`) uses the default single part, so it needs no change.
  7. **Invariant.** With no `bonus_damage` modifiers anywhere, the results, hook calls and trace text are identical to
     today. Existing tests must pass unchanged.
  8. **Script wiring.** Apply the "Phase 2 script wiring" blueprint below.

#### Phase 2 script wiring

In `script/src/main/kotlin/gups/script/Rules.kt`:

- `baseValue`: add a `BONUS_DAMAGE` branch, as in the block below.
- `pointValue`: add `ModifierPoint.BONUS_DAMAGE -> typed(v, TypedKind.DAMAGES, what)`. A wrong-typed return (a dict, an
  int, or `vitals(...)`) then fails through `typed()`, and the existing "a failing modifier leaves the value as it was"
  path reports it.

```kotlin
ModifierPoint.BONUS_DAMAGE -> {
  @Suppress("UNCHECKED_CAST")
  TypedMap(TypedKind.DAMAGES, v as Map<String, Int>, schema.damageTypes)
}
```

Add this `ModifierPointSpec` right after the `DAMAGE` spec, with `ctxFields` the same list as `DAMAGE`'s:

```kotlin
ModifierPointSpec(
  point = ModifierPoint.BONUS_DAMAGE,
  signature = "fn(ctx, base)",
  valueType = "damages",
  ctxFields = listOf(/* same as DAMAGE */),
  doc =
    "Adds typed damage parts to an auto-attack hit or a damage(...) effect, on the attacker's chain; " +
      "each part is mitigated with its own type and applied with the hit as one damage event.",
)
```

The point is kernel-owned, so `script/src/main/kotlin/gups/script/Ownership.kt` gets no entry: `damage`,
`receive_damage` and `mitigate` have none either. Add one smoke test in
`script/src/test/kotlin/gups/script/RulesTest.kt` (mirror the `_siphon` test at ~line 230): an item modifier `return
base + damages(fire = 2)` produces a second `mitigate` call with type `fire`. The fuller script tests are in Phase 3.

### Test & Documentation Requirements

- **`BonusDamageTest.kt`** (mirror `DefenderModifierTest`):
  - With no bonus modifier, a hit's trace text and losses equal today's. Assert the exact trace string from an existing
    case.
  - **An item modifier returning `{"fire": 2}`:**
    - `mitigate` is called twice, once with the primary type and once with `fire`. Use a `FixedRules` or recording rules
      that record `(amount, type)` calls.
    - `on_damage` fires once, with `amount` equal to the combined total and `parts.size == 2`, the primary first.
  - **Parts go in schema order:** a modifier returning `{"cold": 1, "fire": 1}` with `damageTypes = [..., "fire",
    "cold"]` resolves fire before cold.
  - **A part of 0 or less runs no defender chain:** a recording `receive_damage` modifier is not called for it.
  - **The defender's `receive_damage` sees each part's type:** record `c.damageType` per call, and expect `[primary,
    "fire"]`.
  - **Combined gains:** a defender `mitigate` modifier that returns `{hp: -1}` on the fire part offsets that part's hp
    loss in the combined result.
  - **Interrupt:** a casting target gets one `interruptCheck` call per hit, with the combined amount.
  - **Swing-weapon filter:** with two `dice` items worn, the off-hand item's `bonus_damage` modifier doesn't run on the
    main-hand swing. It does run on its own swing.
  - **Outside swings:** a worn `dice` item's `bonus_damage` modifier does run for an `Effect.Damage` cast by the wearer.
  - **The `damage(...)` effect also produces parts:** the target's `on_damage` receives 2 parts.
- **KDoc:** update `resolveDamage`, `DamageResult` and `World.damage`.

### Exit Conditions

- [] `./gradlew :core:test :script:test` passes, with all existing tests unchanged.
- [] After the API regeneration, the only new or changed symbols are `modifier:bonus_damage` and the `MODIFIER` constant
  (plus any example entry).
- [] `./gradlew check` passes with zero warnings.

---

## Phase 3: `bonus_damage` and `ctx.parts` in scripts, and the spec

### Objective & Scope

- **In Scope:**
  - `ctx.parts` on `on_damage`.
  - Script-level tests.
  - All spec text for the feature.
  - API files and the CHANGELOG.
- **Out of Scope:** plugins (Phase 4) and the template world (Phases 5 and 6).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Ownership.kt`: confirm that kernel points need no entry. `damage`,
    `receive_damage` and `mitigate` have none.
  - `script/src/test/kotlin/gups/script/RulesTest.kt`: the `_siphon` mitigate-modifier test around line 230, as the
    pattern for script modifier tests.
  - `script/src/test/kotlin/gups/script/HookCtxFieldsTest.kt`: how hook ctx fields are checked.
  - SPEC §4.4 (the `on_damage` row at ~1048 and paragraph at ~1076), §4.6 (~1264–1516), §5.4 "Resolution" (~2870), §4.3
    `damage(dice, type)` row (~1017).
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`: a `parts` struct field next to `losses` (~line 286).
  - `script/src/main/kotlin/gups/script/Declarations.kt`: the `ON_DAMAGE` `HookSpec` `ctxFields` (~line 271).
  - `script/src/test/kotlin/gups/script/RulesTest.kt`, or a new
    `script/src/test/kotlin/gups/script/BonusDamageScriptTest.kt`.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the three API files.
- **Do Not Touch:** `plugin-api/`, the template world, and core behavior (only fix a core bug if a test exposes one,
  with a test).

### Specification & Decision Record Changes

Apply these edits to `docs/SPEC.md`. Keep the surrounding style, and put no numbers in messages.

1. **§4.6 "Modifier points (v0)":**
   - Add `bonus_damage` after `damage` in the list.
   - In the value-type sentence, add: "modifiers of `bonus_damage` take and return `damages(...)` values".
   - In the ownership sentence, make it "The `damage`, `bonus_damage`, `receive_damage` and `mitigate` points are the
     exception: they belong to the kernel…".
   - In the `combat`-off sentence, include `bonus_damage` among "these chains".
2. **§4.6 "Whose chain":** "the attacker's for `to_hit`, `damage` and `bonus_damage` (the caster is the attacker for
   spell damage)".
3. **§4.6, a new paragraph "Bonus damage", right after "Attack and defense":**

   > **Bonus damage:** after the primary amount is mitigated, the attacker's `bonus_damage` chain runs on `damages()`
   > (there is no export) with `ctx.damage_type` the hit's type, and each modifier adds parts, as in `return base +
   > damages(fire = ctx.roll("1d2"))`. Each part with an amount above 0, in `damage_types` order, then runs the
   > defender's `receive_damage` chain, `mitigate` with its own type, and the defender's `mitigate` chain, with
   > `ctx.damage_type` set to that type; a part of 0 or less is dropped. The losses of the primary and every part are
   > added per vital and applied as one damage event: `on_damage` fires once (with `ctx.parts`, §4.4), the interrupt
   > check runs once on the combined loss, and `condition` is asked once. The chain runs for auto-attacks and the
   > `damage(...)` effect alike; a modifier tells them apart by `ctx.slot` (set only in a swing) and `ctx.skill`. A
   > weapon whose bonus is for its own swings returns `base` when `ctx.slot` is `None`.

4. **§4.6 "Attack and defense":** at the end of the damage sentence ("…`mitigate` turns that amount into vital losses…
   and the result is applied"), change "and the result is applied" to "and the result, with any bonus parts (below), is
   applied".
5. **§4.6 "Weapons in swings":** "the modifiers of a worn item with `dice`, and of its item affects, apply to the
   `to_hit`, `damage` and `bonus_damage` points only when that item is `ctx.weapon`". Keep the rest.
6. **§4.6 trace paragraph:** add after the damage-chain example:

   > Bonus parts continue the same line: ` → bonus_damage` and the attacker's steps, then ` → part <type> <amount>` and
   > that part's defender and `mitigate` steps, for example `rules.damage base=6 → mitigate hp 4 slash → bonus_damage →
   > item:flaming_sword damages(fire = 2) → part fire 2 → mitigate hp 2 fire`; the segment is left out when the attacker
   > has no `bonus_damage` modifier.

7. **§4.6 rules `ctx` paragraph:** in the `damage_type` description, add `bonus_damage` to the chains where it is set
   ("in auto-attack `to_hit` and `damage` calls, `damage`, `bonus_damage`, `receive_damage` and `mitigate` chains and
   `mitigate`"). Phrase it to match the existing wording.
8. **§4.4 hook catalog:** in the `on_damage` row's ctx column, add `parts`.
9. **§4.4 `on_damage` paragraph:** append:

   > `ctx.parts` lists the damage event's parts in order, the hit's own type first and then each bonus part (§4.6), as
   > `struct(type, losses)` with `losses` that part's positive losses as `vitals(...)`; a single-type event, including
   > the raw `ctx.damage` verb, has one part.

10. **§4.3 `damage(dice, type)` row:** after "…the target's `receive_damage` chain", add "; the caster's `bonus_damage`
    chain may add typed parts, each mitigated separately (§4.6)".
11. **§5.4 "Resolution" step 3:** after "…and the defender's `mitigate` chain," add "then any `bonus_damage` parts, each
    through the defender's `receive_damage` chain, `mitigate` with its own type, and the defender's `mitigate` chain
    (§4.6)," and change "apply the vital losses" to "apply the combined vital losses". Keep "`on_damage` fires once".
12. **§3.9 constructor row for `damages`** (from Phase 1): change "Used for" to "the value of `bonus_damage` modifiers
    (§4.6)".
13. **§3.10:** if the feature table lists the kernel's modifier points, add `bonus_damage` there. If it doesn't, leave
    it alone.
14. **§4.5:** if a list of hook-ctx fields names `losses`, add `parts` beside it.

- **CHANGELOG bullets:**
  - "Added the kernel modifier point `bonus_damage` (`MODIFIER.bonus_damage`): the attacker's chain returns
    `damages(...)` parts that are each mitigated with their own type and applied with the hit as one damage event, for
    auto-attacks and the `damage(...)` effect."
  - "`on_damage` has `ctx.parts`, the event's parts as `struct(type, losses)`."

### Implementation Blueprint

- **Signatures & Types:** add a `parts` struct field to `Ctx.kt`, next to `losses` (~line 286), with the signature in
  "Phase 3 `ctx.parts`" below.
- **Algorithmic Steps & Invariants:**
  1. `parts()` returns a `StarlarkList` of structs, one per `hc.parts` entry, in order. Build each struct the way other
     struct-valued fields in `Ctx.kt` are built (search for `StructValue`). `type` is the type name string, and `losses`
     is `TypedMap(TypedKind.VITALS, part.losses, schema.vitalNames)`. It returns `None` when `hc.parts` is null.
  2. Add `"parts"` to the `ON_DAMAGE` `HookSpec` `ctxFields` in `Declarations.kt`, right after `"losses"`.

#### Phase 3 `ctx.parts`

```kotlin
@StarlarkMethod(
  name = "parts",
  structField = true,
  doc = "The damage event's parts, the hit's type first, as struct(type, losses), for on_damage.",
)
fun parts(): Any
```

### Test & Documentation Requirements

- **Script tests** (mirror the `_siphon` test in `RulesTest.kt`):
  - **An item modifier `return base + damages(fire = 2)` on a worn non-`dice` item:**
    - a swing's `on_damage` (on the target mob) records `len(ctx.parts) == 2` and `ctx.parts[1].type == "fire"`;
    - `ctx.amount` equals the sum of both parts' hp losses.
  - **Two sources (a room and an affect) each adding fire:** the parts add up, and the second doesn't drop the first.
  - **A modifier returning a plain dict:** it is reported, and the value is unchanged (no bonus part).
  - **A `slot` guard (`if ctx.slot == None: return base`) on a wielded `dice` item:**
    - it adds fire to its swing;
    - it doesn't add fire to a `damage(...)` spell the wearer casts.
  - **The same item without the guard** adds fire to the spell.
  - **The raw `ctx.damage(...)` verb's `on_damage`** has one part.
  - **`MODIFIER.bonus_damage == "bonus_damage"`.**
  - **With combat off,** `bonus_damage` can be declared and runs for a `damage(...)` effect. Mirror the combat-off
    `damage`-point test in `FeatureScriptTest.kt`.
- **`HookCtxFieldsTest`:** update it if it pins the `on_damage` field list.

### Exit Conditions

- [] `./gradlew :script:test :core:test` passes.
- [] After the API regeneration, the only new or changed symbols are `modifier:bonus_damage`, `hook:on_damage` (its ctx
  fields) and `ctx.parts`, plus any example.
- [] `./gradlew check` passes with zero warnings.

---

## Phase 4: Plugin trait modifiers on `bonus_damage`

### Objective & Scope

- **In Scope:**
  - `PluginModifierPoint.BONUS_DAMAGE`.
  - The adapter conversion `Map<String, Long>` ↔ `Map<String, Int>`, validated against damage types.
  - `docs/PLUGINS.md` and §4.19 text.
  - Tests.
- **Out of Scope:** sample plugins; the template world.

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt` (~lines 120–185): the `MITIGATE` vitals-map branch.
    Mirror it.
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt` (`MODIFIER_POINT_FEATURES`, ~line 172).
    `BONUS_DAMAGE` needs no feature, so it isn't listed there.
  - `plugin-api/src/test/kotlin/gups/pluginapi/CreationRegistrationTest.kt`: the existing `RECEIVE_DAMAGE` and
    `MITIGATE` trait tests.
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`: the `PluginModifierPoint` enum (~line 271), and the
    `PluginModifierCall` KDoc.
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt`.
  - `plugin-api/src/test/kotlin/gups/pluginapi/CreationRegistrationTest.kt`.
  - `docs/PLUGINS.md`, `docs/SPEC.md` (§4.19, only if it lists points), `docs/CHANGELOG.md`, and the API files if the
    plugin API is part of them.
- **Do Not Touch:** `core/`, `script/`, the samples, the template world.

### Specification & Decision Record Changes

- **§4.19 trait "Modifiers" bullet:** if it enumerates the plugin modifier points, add `BONUS_DAMAGE` and say its value
  is a map from damage type to amount.
- **`docs/PLUGINS.md`:** in the modifier-points list, add `BONUS_DAMAGE`: "`Map<String, Long>` by damage type; return
  the base with entries added. `actor` is the attacker."
- **CHANGELOG bullet:** "Plugin traits can declare `BONUS_DAMAGE` modifiers, which take and return a map from damage
  type to amount."

### Implementation Blueprint

- **Signatures & Types:** add `BONUS_DAMAGE` after `DAMAGE` in `PluginModifierPoint`.
  `PluginModifierPoint.valueOf(point.name)` in `Adapters.kt` then maps it.
- **Algorithmic Steps & Invariants:**
  1. In the adapter's `when (point)`, add a `ModifierPoint.BONUS_DAMAGE` branch:
     - pass `(base as Map<String, Int>).mapValues { it.value.toLong() }`;
     - accept a `Map<*, *>` whose keys are all `String` in `w.schema.damageTypes` and whose values are all `Long`;
     - clamp to `Int`, as `MITIGATE` does;
     - otherwise `refuse("returned an invalid damage map, expected Map<String, Long>")`.
  2. `actor` and `other` stay the attacker and the defender: it's not a defender point, so no change is needed. Confirm
     the KDoc on `PluginModifierCall` still reads correctly.

### Test & Documentation Requirements

- **`CreationRegistrationTest.kt`:**
  - A trait with a `BONUS_DAMAGE` modifier that returns `base + ("fire" to 2L)`: the bearer's swing produces a fire
    part. Check the target's `on_damage` parts, or the trace.
  - A modifier that returns an unknown key is refused, and the base is kept with a `plugin` diagnostic.
  - A modifier that returns `Int` values instead of `Long` is refused.

### Exit Conditions

- [] `./gradlew :plugin-api:test` passes.
- [] `./gradlew check` passes with zero warnings. If the plugin API appears in the API files, only `BONUS_DAMAGE` is
  new.

---

## Phase 5: Template `mitigate`: armor for physical damage only

### Objective & Scope

- **In Scope:**
  - The template's `mitigate` formula.
  - Fixing any template test whose numbers depended on armor reducing fire.
  - A tutorial note.
- **Out of Scope:**
  - The new sample items (Phase 6).
  - The robot-world sample (it's already type-aware).
  - Any engine change.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/rules/package.star` and `template-world/world.star` (or wherever `damage_types` is declared):
    confirm the four types `bludgeon`, `slash`, `pierce`, `fire`.
  - `samples/robot-world/rules/combat.star` `mitigate`: the type-aware pattern.
- **Files to Modify / Create:**
  - `template-world/rules/combat.star` (`mitigate`, ~line 88).
  - Any template `*_test.star` that fails because of the change: `template-world/zones/tutorial/tutorial_test.star`,
    `shop_test.star`, and rules tests if any.
  - `template-world/docs/tutorials/03_items_and_equipment.md`, if it describes the armor formula. Check the other
    tutorials too, with a grep for `armor`.
  - `docs/CHANGELOG.md`.
- **Do Not Touch:** `core/`, `script/`, `plugin-api/`, `samples/`.

### Specification & Decision Record Changes

- **SPEC:** none. The spec delegates the formulas to the template files. If §4.6 "Template ruleset" or §3.9 quotes the
  armor formula anywhere (grep for `armor // 2`), update that quote to match.
- **CHANGELOG bullet:** "The template's `mitigate` applies armor only to physical damage (`bludgeon`, `slash`,
  `pierce`); fire is reduced only by resists."

### Implementation Blueprint

```python
_PHYSICAL = ["bludgeon", "slash", "pierce"]

def mitigate(ctx, amount, type):
    # The vital losses that `amount` damage of `type` causes ctx.defender. Armor turns aside
    # physical blows only, and always lets at least 1 through; resistance to the type applies to
    # every type. Bonus parts (bonus_damage) are mitigated one by one, so armor isn't charged
    # against each.
    if amount <= 0:
        return vitals()
    d = ctx.defender
    after_armor = max(1, amount - d.armor // 2) if type in _PHYSICAL else amount
    resist = getattr(d.resists, type)
    return vitals(hp = max(0, after_armor * (100 - resist) // 100))
```

`_PHYSICAL` has a `_` prefix, as rules files require for non-exports. Confirm that a top-level `_`-prefixed constant is
allowed in a rules file. If it isn't, inline the list.

### Test & Documentation Requirements

- Run the template tests. For each failure, decide whether its number depended on armor reducing fire. If it did, update
  the expected value, with a comment where the reason isn't obvious. If it didn't, stop and investigate.
- Add one test in `tutorial_test.star`: with the same seed, a fire `damage(...)` effect deals the same hp loss to a
  target with and without `armor` (use an affect or an item with `stat_deltas(armor = 10)`). Use whatever fire spell the
  tutorial zone offers, or `ctx.damage`-free scaffolding the harness supports. If there's no fire source, defer this
  assertion to Phase 6, where `ember_amulet` provides one, and say so in the summary.
- Update the tutorial text that describes the armor formula.

### Exit Conditions

- [] `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups test --world template-world
  //zones/tutorial/...` (and any rules package tests) passes.
- [] `cli/build/install/gups/bin/gups lint --world template-world` passes, which includes the rules dry run.
- [] `./gradlew check` passes.

---

## Phase 6: Template samples, tutorial, and finalization

### Objective & Scope

- **In Scope:**
  - Three tutorial items (`flaming_sword`, `ember_amulet`, `oily_rags`) with zone tests.
  - The tutorial doc section.
  - The region comment that lists points.
  - The §7.4 row.
  - Marking the `DESIGN_NEEDED` entry completed.
  - A full-suite run and an end-to-end check.
- **Out of Scope:** engine changes. If a test here exposes an engine bug, fix it in its module with a test, and say so
  in the summary.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/zones/tutorial/items.star`: `practice_sword` and `greatsword` (weapon declarations), `shadow_cloak`
    with `_blur` and `_ward` (modifier items), and `siphon_amulet`.
  - `template-world/zones/tutorial/tutorial_test.star`: `_world(t)` and `_dummy_fight` (from M26), plus
    `test_shadow_cloak_blurs_and_wards`.
  - `template-world/rules/combat.star`: `equipment_slots()` (`head`, `neck`, `body`, `arms`, …, `wield`, `offhand`,
    `held`) and `mitigate` (Phase 5).
  - `AGENTS.md` "End-to-end checks".
- **Files to Modify / Create:**
  - `template-world/zones/tutorial/items.star`.
  - `template-world/zones/tutorial/tutorial_test.star`.
  - `template-world/zones/tutorial/rooms.star`: only the region comment that lists points ("Points: to_hit, damage, …").
  - `template-world/docs/tutorials/03_items_and_equipment.md`.
  - `docs/SPEC.md` (§7.4), `docs/DESIGN_NEEDED.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** the `core`, `script` and `plugin-api` sources; the `rules/combat.star` formulas.

### Specification & Decision Record Changes

- **§7.4:** add a row after M26:

  > | **M27** | Typed bonus damage: the kernel `bonus_damage` modifier point (attacker's chain, `damages(...)` values,
  > swing-weapon filter), each part mitigated with its own type and applied with the hit as one damage event,
  > `on_damage`'s `ctx.parts`, `+` on typed values, `BONUS_DAMAGE` plugin trait modifiers, the template's physical-only
  > armor, and the tutorial's `flaming_sword`, `ember_amulet` and `oily_rags` (§3.9, §4.3, §4.4, §4.6, §4.19, §5.4). |

- **`docs/DESIGN_NEEDED.md`:** rename the heading to `### Typed bonus damage on attacks (COMPLETED — M27)`, following
  M24–M26, and leave the body in place.

### Implementation Blueprint

Add to `items.star`, following the file's style: short comments, `_private` helpers, no lambdas or nested functions.
Pick prices and weights in line with the neighbors.

1. **`flaming_sword`:** a `wear = "wield"` weapon with `dice` (for example `"1d6"`) and `damage_type = "slash"`, whose
   `modifiers = {MODIFIER.bonus_damage: _flame_edge}`:

   ```python
   def _flame_edge(ctx, base):
    # Only this blade's own swings burn: outside a swing (a spell), ctx.slot is None.
    if ctx.slot == None:
           return base
    return base + damages(fire = ctx.roll("1d2"))
   ```
2. **`ember_amulet`:** `wear = "neck"` (shared with `amulet` and `siphon_amulet`, so each test wears only one of them),
   with `modifiers = {MODIFIER.bonus_damage: _embers}`:

   ```python
   def _embers(ctx, base):
    # Unguarded, so everything the wearer deals carries a little fire, spells included.
    return base + damages(fire = 1)
   ```
3. **`oily_rags`:** `name = "sketchy oil-soaked rags of protection"`, `wear = "arms"` (not `body`, so it can be worn
   with `shadow_cloak`), `stat_deltas = stats(armor = 4)`, and `modifiers = {MODIFIER.receive_damage: _flammable}`:

   ```python
   def _flammable(ctx, base):
    # On the wearer's chain, once per damage part: ctx.damage_type is that part's type, so this
    # doubles a fireball and a flaming sword's fire alike, before armor and resists.
    return base * 2 if ctx.damage_type == DAMAGE.fire else base
   ```
4. **`rooms.star`:** add `bonus_damage` to the region comment's list of points.

### Test & Documentation Requirements

- **`tutorial_test.star`** (use or extend `_dummy_fight`, and assert on vitals and hook-observable state, since
  `w.trace()` prints only on failure):
  - **`test_flaming_sword_adds_fire`:** a player with the `flaming_sword` deals more total damage to a high-hp target
    over a fixed number of pulses than with an otherwise identical plain `dice` weapon, at the same seed.
    - If the tutorial has no plain weapon with the same dice, compare against `practice_sword` and assert only that the
      fire part exists.
    - The target can be a mob with an `on_damage` hook that records `ctx.parts` types to an attribute. If the tutorial
      has no such mob, spawn one in the test world, as other tests do, or place the record hook on a worn item of the
      target.
  - **`test_flaming_sword_spares_spells`:** a player wielding the sword who casts a fire `damage(...)` spell produces
    one part. With `ember_amulet` instead, the spell produces two parts. If the tutorial zone has no damage spell,
    declare a minimal one in the test package, if the harness allows that, or skip this test and cover it with the Phase
    3 script test, saying so in the summary.
  - **`test_oily_rags_double_fire`:** the wearer takes about twice the fire damage of a non-wearer from the same fire
    source and seed, and less physical damage (armor 4).
  - If Phase 5 deferred its "fire ignores armor" assertion, add it here using `ember_amulet`'s fire.
  - **The fire source for defender-side tests.** The tutorial zone has nothing that deals fire, and in `_dummy_fight`
    the player is the one being hit, by the dummy's unarmed `bludgeon` swings. To hit the player with fire, put an
    `ember_amulet` on the dummy, so each of its swings carries a 1-fire part. Copy the pattern of
    `test_a_mob_can_wear_an_amulet` in `tutorial_test.star`:
    - `w.spawn(":ember_amulet", at = dummy)`, then `w.act(dummy, "wear amulet")`, then `w.run(pulses = 4)`.
    - Then assert `dummy.equipped("neck").label` before starting the fight, so a failure to wear shows up as a clear
      error rather than as wrong damage numbers.
    - `"amulet"` must pick out the ember amulet. If the dummy carries another amulet, use a keyword unique to it.
    - Use a dummy handle you spawned yourself (`w.spawn(":training_dummy", at = ":arena")`), and have the player `kill`
      it. If the arena also spawns its own dummy, the player's `kill dummy` may pick that one instead. In that case,
      spawn yours in another room that allows fighting, and fight there.
- **`03_items_and_equipment.md`:** add a subsection under "B. Dynamic Rules Modifiers" titled "Bonus damage". It covers:
  - the `bonus_damage` point and `damages(...)` with `+`;
  - per-part mitigation and the single damage event;
  - the `ctx.slot` guard, with the sword and amulet snippets;
  - the oily rags as a defender-side example, with one line contrasting `resist_deltas = resists(fire = -100)`, which
    adds to other resists, with the `receive_damage` doubling, which multiplies.

  Keep fenced code blocks at column 0.

- **CHANGELOG bullet:** "The tutorial zone has a `flaming_sword` (`bonus_damage` on its own swings), an `ember_amulet`
  (`bonus_damage` on everything) and `oily_rags` (armor, and double fire through `receive_damage`)."

- If `gups docs` output for the template world changes, regenerate the affected goldens and read the diff.

### Exit Conditions

- [] `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups test --world template-world
  //zones/tutorial/...` passes.
- [] `cli/build/install/gups/bin/gups lint --world template-world` passes.
- [] An end-to-end `gups scenario` in `local-world-test/` runs:
  - a player wields the `flaming_sword`, wears the `oily_rags`, types `kill dummy`, and fights for several rounds with
    `w.trace()` on;
  - the printed trace shows ` → bonus_damage → item:flaming_sword damages(fire = …) → part fire`;
  - `w.diagnostics()` shows no `script_error`.

  Delete `local-world-test/` afterwards.

- [] `docs/DESIGN_NEEDED.md` marks `### Typed bonus damage on attacks (COMPLETED — M27)`, with the body kept.

- [] `./gradlew check` passes with zero warnings. This is the full suite: module-graph, `markdownCheck` and
  `ApiDiffTest` included.
