# Milestone 47: Recognizing Skills, Trait Callbacks and the Blue Mage Sample

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, section "Perception and the world", heading `### Recognizing an
  unfamiliar spell`.

- **Naming convention (applies to every phase):** engine and plugin identifiers say **skill**, never **spell**: rules
  exports, `ctx` fields, hook and event names, plugin API types and message ids. Every castable thing is a `skill()`
  whose `kind` is free-form, and "spell" is only one kind. Documentation of `cast` may talk about spells. Player-facing
  text may say "spell" where a world or a skill chooses it. Phase 1 writes this into `AGENTS.md`.

- **Design Summary:**

  - **Recognition.** Every onlooker of a cast, and its target, either **recognizes** the skill (sees its name) or
    doesn't (sees an anonymous line). The engine decides this once per onlooker per cast and caches the answer on the
    cast, so every line about that cast agrees.
    - Only **players** are asked. Mobs get no text, and mob combat memory already records casts through `witness`.
    - **Who is asked:**
      - Each player in the caster's room, other than the caster, who passes the sight check (the same `tellPerceivers`
        check `tellSight` uses), at the first line about the cast they would get: the warmup line, or the resolution
        line for an instant cast or for someone who arrived mid-warmup.
      - The cast's target, if a player, is told regardless of sight (as today) and is asked too.
    - A player who knows the skill (`skills` contains its label) recognizes it without a rules call.
    - Otherwise the engine asks the new **optional** rules export `recognizes_skill(ctx) -> bool`. If the world doesn't
      define it, the answer is `False`, which keeps today's behavior: only players who know the skill recognize it. A
      rules error counts as `False` and records a rules diagnostic.
    - The warmup line moves onto sight delivery. Today `CastAction.begin` sends to everyone in the room, blind or not.
  - **Anonymous lines.** A player who doesn't recognize the skill never sees its name in the room, victim or resisted
    lines; the caster's own lines don't change. Each anonymous line is picked in four tiers:
    1. The skill's own `unrecognized[key]`, a new optional `skill()` field.
    2. A **damage** line, when the skill's `effect` contains a `damage(...)` primitive. The engine takes the first one
       in depth-first order through `sequence`. A `resolve`-only skill has no damage type.
    3. An **aid** line (`ally` or `self` target) or a **harm** line (`enemy` target).
    4. A generic line.

    Tiers 2–4 are message catalog entries, so worlds reword them in `MESSAGES.gups`. They use a `{what}` placeholder:
    the damage type's adjective (if any) plus a noun. The noun is the skill's `unrecognized["noun"]`, or the catalog's
    `cast.anon.noun.harm` ("attack") for `enemy` skills, or `cast.anon.noun.aid` ("aid") otherwise.

  - **`damage_type()` constructor.** `damage_types` in `MODULE.gups` accepts `damage_type(name, label=None,
    adjective=None)` beside plain strings, the way `stat()` and `vital()` work. `label` defaults to the capitalized
    name. `adjective` (for example `"fiery"`) feeds `{what}`. Neither is recorded per database.

  - **Trait callbacks.** `PluginTrait` gains `on: Map<PluginTraitEvent, PluginTraitHandler>`. A handler gets a mutating
    `PluginWorld`, the bearer and event fields. This milestone ships a single event, `SKILL_RECOGNIZED`. It fires for
    each player who recognized a skill they don't know, on a cast whose outcome is `success` or `resist`. Handlers run
    after the cast's `on_skill_use` dispatch. Further events are the `DESIGN_NEEDED` entry "More trait event callbacks",
    which this milestone does not touch.

  - **Plugin-declared rules exports.** `PluginRegistry.addRulesExport(name, doc, type)` declares an optional export
    named `<plugin id>_<snake_case>`. A world's rules package may then define it. `PluginWorld.rules(name, fields,
    default)` calls it, and returns `default` when the world doesn't define it or when the call fails.

  - **`PluginWorld.skill(label): PluginSkill?`.** A lookup of a loaded skill's read-only view.

  - **`samples/bluemage-plugin`.** A demo that learns from mobs:

    - The trait `blue_mage`.
    - The builtin `bluemage.awaken(actor)`, which grants the trait.
    - A `SKILL_RECOGNIZED` handler with a learning formula, tunable through the rules export `bluemage_learn_chance`.
    - A `bluemagic` command.
    - It is standalone: `heritage-plugin` is not changed.
  - **Template world.**
    - `damage_type(..., adjective=...)` entries.
    - A `recognizes_skill` export based on Intelligence and Wisdom.
    - `spark` tagged `blue`, with an `unrecognized` dict.
    - A Starlark test of the anonymous lines and recognition.
    - The template does **not** define `bluemage_learn_chance`: a rules export of an unloaded plugin is a load error,
      and the template loads no plugins.
- **Specification Impact:**
  - §2.2: the `samples/` line of the module tree.
  - §3.9: `damage_types` and the new `damage_type()` declaration.
  - §3.10: the `skills` feature row (`recognizes_skill`, `skill(unrecognized=)`).
  - §4.3: the `skill()` example and a new **Recognition** paragraph.
  - §4.6:
    - The optional-exports sentence ("Seven" becomes "Eight").
    - A catalog row for `recognizes_skill`.
    - A new **Plugin-declared exports** paragraph.
    - The new `ctx` fields: `cast_target`, `base`, `args`.
  - §4.15: the `PluginRegistry` and facade lists, a new **Trait callbacks** bullet, a new **Rules exports** bullet, and
    a new sample bullet.
  - §4.18: the new message entries.
  - §4.19: a new **Callbacks** bullet under **Effects of traits**.
  - §5.3: steps 3 and 5.
  - §5.7: the in-room messages table (`cast` begin is sight).
  - `docs/PLUGINS.md`, `docs/CHANGELOG.md`, `AGENTS.md`, and the regenerated API files.
- **Decision Record Updates:** None. `docs/DECISIONS.md` holds no live records this design touches. Do not add `D-xxx`
  entries for anything this document settles: the spec text below is approved.

### Ground rules for every phase

1. **Ordering and concurrency.** Start only once Milestone 46 (movement pace and pursuit catch-up) is merged. It changes
   `Protos.kt`, `RulesEngine.kt`, both `Rules.kt` files, `Views.kt`, `Entities.kt`, `PluginApi.kt` and `Adapters.kt`,
   which this milestone edits too. Other milestones may also land first. Always re-read the current `SPEC.md`,
   `CHANGELOG.md`, `SkillCommands.kt`, `Perception.kt`, `Rules.kt` (both), `PluginHost.kt` and `Adapters.kt`. Find code
   by symbol name, never by the line numbers quoted here.
2. **TDD** (`AGENTS.md`): write the tests named in each phase first, run them and see them fail, then implement.
3. **Spec edits are pre-approved.** Apply the SPEC text given in each phase directly. Log a `DECISIONS.md` entry only
   for a genuinely new gap you have to fill.
4. **No spec section numbers or decision ids in code comments**, Kotlin or Starlark.
5. **Naming:** identifiers say "skill" (see the Overview).
6. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`. The engine is
   unreleased: no migrations, no save-format back-compat, no deprecation shims.
7. **CHANGELOG:** add bullets to a new `### Recognizing skills and trait callbacks` subsection at the top of `##
   api_version 1 (unreleased)`, above whatever subsection is first there now. Create it in the first phase that adds a
   bullet; later phases append.
8. **API files.** In any phase that changes the script API, regenerate before the phase's final `./gradlew check` if a
   golden or API test fails:
   - `./gradlew :cli:test -Dgups.updateGolden=true` (rewrites `cli/src/test/resources/api.golden.json` and
     `cli/src/test/resources/docs-golden/`).
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout (rewrites `docs/api/api-1.json`).
   - Read the diff: only the symbols the phase names may be new or changed. `ApiDiffTest` must then pass.
   - If a test fails because a new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring an existing entry.
9. **Gradle** runs outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation. `./gradlew check` must pass with zero warnings.

---

## Phase 1: Naming Note and the `damage_type()` Declaration

### Objective & Scope

- **In Scope:**
  - The `AGENTS.md` naming note.
  - `damage_type(name, label=None, adjective=None)` in `MODULE.gups`.
  - `WorldSchema` lookups for a damage type's label and adjective.
  - The §3.9 text.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):**
  - Any use of the label or adjective in messages (Phase 4a).
  - Changes to the template world's `MODULE.gups` (Phase 8).
  - Changing `WorldSchema.damageTypes` from `List<String>`. Every existing caller keeps working unchanged.

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/WorldModule.kt`: `stat`, `vital`, `named`, `names`, `NameDecl`, and the
    `damage_types` `DeclParam`.
  - `core/src/main/kotlin/gups/core/model/Schema.kt`: `WorldSchema`, `SchemaNames`.
  - `docs/SPEC.md` §3.9.
- **Files to Modify / Create:**
  - `AGENTS.md`
  - `core/src/main/kotlin/gups/core/model/Schema.kt`
  - `script/src/main/kotlin/gups/script/WorldModule.kt`
  - `script/src/main/kotlin/gups/script/DocExamples.kt`: only if the docs test asks for it.
  - The `WorldModule` test file. Find it with `rg -l "damage_types" script/src/test`.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the API files (ground rule 8).
- **Do Not Touch:** `persist/` (`SchemaRecord` still records names only), `template-world/`, `samples/`.

### Specification & Decision Record Changes

- **`AGENTS.md`**, a new section after "Working from the spec":

  > ## Naming: skills, not spells
  >
  > Everything a character casts is a `skill()`, and its `kind` is free-form; "spell" is only one kind. Name engine and
  > plugin identifiers (rules exports, `ctx` fields, hooks and events, plugin API types, message ids) with "skill":
  > `recognizes_skill`, not `recognizes_spell`. Documentation of `cast` may talk about spells, and player-facing text
  > may say "spell" where a world or a skill chooses that word.

- **§3.9, the template example:** leave it as is. Phase 8 changes the real file, and this example then becomes:
  `damage_types=[damage_type("bludgeon", adjective="crushing"), damage_type("slash", adjective="slashing"),
  damage_type("pierce", adjective="piercing"), damage_type("fire", adjective="fiery")],`. Make that example edit in
  Phase 8.
- **§3.9, the **Declarations** bullet** becomes: "`stat(name, label = None)`, `vital(name, label = None)` and
  `damage_type(name, label = None, adjective = None)`; a plain string is the same as the call with only a name. The
  label is shown to players (`score`, GMCP, messages) and defaults to the name with its first letter capitalized. A
  damage type's `adjective` (`"fiery"`) describes a hit of that type in the lines told to onlookers who don't recognize
  a skill (§4.3); it is a non-empty single line, or `None` for no adjective. Declaration order is display order
  everywhere."
- **§3.9, **Fixed per database**:** change "Labels, `prompt` and `unarmed_damage_type` are not recorded" to "Labels,
  damage type adjectives, `prompt` and `unarmed_damage_type` are not recorded".
- **CHANGELOG:** "- `damage_types` accepts `damage_type(name, label=None, adjective=None)` beside plain names."

### Implementation Blueprint

- **Signatures & Types** (`Schema.kt`):

```kotlin
/** A declared damage type's presentation; not recorded per database. */
data class DamageTypeInfo(val name: String, val label: String, val adjective: String? = null)

data class WorldSchema(
  // ... existing fields unchanged ...
  /** Label and adjective per damage type; a type missing here has the default label and no adjective. */
  val damageTypeInfo: Map<String, DamageTypeInfo> = emptyMap(),
) {
  fun damageTypeLabel(name: String): String =
    damageTypeInfo[name]?.label ?: name.replaceFirstChar { it.uppercase() }

  fun damageTypeAdjective(name: String): String? = damageTypeInfo[name]?.adjective
}
```

- **Algorithmic Steps & Invariants** (`WorldModule.kt`):
  1. Add a `DAMAGE_TYPE_SPEC` and a `@StarlarkMethod(name = "damage_type")` builtin next to `stat` and `vital`. It
     returns a private `DamageTypeDecl(name, label, adjective) : StarlarkValue`.
  2. Change the `damage_types` `DeclParam` type to `"list[str | damage_type]"`, doc "The damage types of the world, each
     a name or damage_type(name, label, adjective)."
  3. Parse it the way `names()` parses stats. Strings and `DamageTypeDecl`s both yield the name into `damageTypes`, and
     each fills `damageTypeInfo` with a `DamageTypeInfo` (default label for a string). Anything else fails with
     `module(damage_types): expected a name or damage_type(...), got <type>`.
  4. Validate `adjective`: it must be non-empty with no line break, or the schema fails with `damage_type(adjective):
     must be a non-empty single line`. Name rules and uniqueness stay where they are.

### Test & Documentation Requirements

- In the `WorldModule` test file:
  - Strings and `damage_type(...)` mix in one list, and `damageTypes` keeps declaration order.
  - The default label, and an explicit label.
  - `adjective` round-trips. An empty or multi-line adjective fails with the message above.
  - A non-string, non-decl element fails.
  - `SchemaNames.damageTypes` is unchanged by labels or adjectives.

### Exit Conditions

- [] `./gradlew :script:test :core:test` passes.
- [] `./gradlew check` passes with zero warnings.
- [] `AGENTS.md` has the naming section, and `markdownCheck` passes.

---

## Phase 2: The `skill(unrecognized=)` Field

### Objective & Scope

- **In Scope:**
  - The optional `unrecognized` dict on `skill()`, with its load checks.
  - `SkillProto.unrecognized`.
  - `ctx.skill.unrecognized` on the skill view.
  - The §4.3 `skill()` example and field text.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):**
  - Using the field to send anything (Phases 4a and 4b).
  - The rules export (Phase 3).
  - The template skills (Phase 8).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Declarations.kt`: `skill()`, `SKILL_SPEC`, and the `interrupt` dict parsing (the
    pattern to mirror for key checks with `choice(...)`).
  - `core/src/main/kotlin/gups/core/model/Protos.kt`: `SkillProto`.
  - `script/src/main/kotlin/gups/script/Views.kt`: the skill view (`label`, `tags`, `kind`, …).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt`
  - `script/src/main/kotlin/gups/script/Declarations.kt`
  - `script/src/main/kotlin/gups/script/Views.kt`
  - `script/src/main/kotlin/gups/script/DocExamples.kt`: only if asked.
  - The skill declaration tests. Find them with `rg -l "attack_while_casting" script/src/test`.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the API files.
- **Do Not Touch:** `SkillCommands.kt`, `Messages.kt`, `plugin-api/`.

### Specification & Decision Record Changes

- **§4.3, the `skill(...)` example:** add, after `tags=["arcane"],`:

```python
unrecognized={"noun": "bolt",  # optional; what onlookers who don't recognize it see (below)
              "victim": "{actor}'s bolt of flame burns you!"},
```

- **§4.3, after the **Range semantics** paragraph,** a new paragraph: the text in the blockquote below, with its list
  kept as a list.

> **`unrecognized`:** optional lines for players who don't recognize the skill (**Recognition**, below). Its keys are
> `begin`, `room`, `victim`, `resisted_room`, `resisted_victim` and `noun`; each is optional. `noun` (`"breath"`,
> `"spell"`) names the skill in the engine's anonymous lines. The others replace a whole line:
>
> - `begin`: the warmup line.
> - `room`: what bystanders see when the cast resolves, with or without a target.
> - `victim`: what the target sees.
> - `resisted_room` and `resisted_victim`: the lines when an `enemy` skill is resisted.
>
> Lines may use `{actor}` and `{target}`. `{target}` is a load error on a skill whose `target` is `self`, `none` or
> `room`, and in `room` on a skill whose `range` is `room`, which has no single target. An unknown key or placeholder is
> a load error too. Every value is a non-empty single line.

- **CHANGELOG:** "- `skill(unrecognized={...})` gives the lines players who don't recognize a skill see, and the noun
  the engine's anonymous lines use (`ctx.skill.unrecognized`)."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Protos.kt, new SkillProto field (after tags)
/** Lines for onlookers who don't recognize the skill, by key; see UNRECOGNIZED_KEYS. */
val unrecognized: Map<String, String> = emptyMap(),

// Protos.kt, top level
val UNRECOGNIZED_KEYS: List<String> =
  listOf("begin", "room", "victim", "resisted_room", "resisted_victim", "noun")
```

- **Algorithmic Steps & Invariants** (`Declarations.kt`, inside `skill()`):
  1. Add `DeclParam("unrecognized", "dict[str, str]", "Lines for players who don't recognize the skill: begin, room,
     victim, resisted_room, resisted_victim, noun.", default = "{}")` to `SKILL_SPEC`.
  2. Parse it like `interrupt`. Check each key with `choice(k, d.what("unrecognized"), UNRECOGNIZED_KEYS)`. Each value
     must be a string with no line break, and not empty.
  3. Placeholders: scan each line for `{name}` tokens (the regex `\{([a-z][a-z0-9_]*)\}`, ignoring `{{`). Allowed names
     are `actor` and `target`. `noun` takes no placeholders. `{target}` with `target` in `SELF`, `NONE` or `ROOM` fails
     with `skill(unrecognized["<key>"]): {target} needs a skill with a target`. So does `{target}` in the `room` key of
     a skill whose `range` is `ROOM`, which has no single target.
  4. Pass the map into `SkillProto(unrecognized = ...)`.
  5. **The view** (`Views.kt`): `unrecognized`, a struct field returning a frozen `dict[str, str]`, sorted by key. Doc:
     "Lines for onlookers who don't recognize the skill, by key."

### Test & Documentation Requirements

- In the skill declaration tests:
  - Every key is accepted.
  - An unknown key fails with a suggestion.
  - An empty value fails, and so does a multi-line value.
  - `{target}` on a `self` skill fails. `{target}` on an `enemy` skill passes. `{target}` in `room` on a `range="room"`
    skill fails, and in `resisted_room` on one passes.
  - An unknown placeholder `{caster}` fails.
  - `noun` with a placeholder fails.
  - The view returns the dict.

### Exit Conditions

- [] `./gradlew :script:test` passes.
- [] `./gradlew check` passes with zero warnings and the regenerated API files show only `skill(unrecognized)` and the
  view's `unrecognized`.

---

## Phase 3: The `recognizes_skill` Rules Export

### Objective & Scope

- **In Scope:**
  - `RulesExport.RECOGNIZES_SKILL`.
  - `RulesEngine.recognizesSkill`.
  - The new `ctx.cast_target` field.
  - The dry-run call.
  - The §4.6 text.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):**
  - Calling it from casts (Phase 4b).
  - Plugin-declared exports (Phase 7).

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Rules.kt`: `RulesExport`, `RULES_EXPORTS` (the `LEASH_RETURN` spec with
    `hasDefault = true`), `StarlarkRules.leashReturn`, `RulesCtx`'s `away` field, and the dry run's `LEASH_RETURN`
    block.
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt`: `RulesContext`, `leashReturn`.
  - `docs/SPEC.md` §4.6.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt`
  - `script/src/main/kotlin/gups/script/Rules.kt`
  - `script/src/main/kotlin/gups/script/DocExamples.kt`: only if asked.
  - The rules tests that cover `leash_return`. Find them with `rg -l "leash_return" script/src/test`.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, and the API files.
- **Do Not Touch:** `SkillCommands.kt`, `template-world/`.

### Specification & Decision Record Changes

- **§4.6, the optional-exports sentence:** "Seven exports are optional" becomes "Eight exports are optional". Add
  "`recognizes_skill`, without which only players who know a skill recognize it (§4.3)," after the `leash_return`
  clause.
- **§4.6 catalog, a row after `skill_power`:** "| `recognizes_skill(ctx)` | `skills` | `bool`. Whether `ctx.actor`, a
  player watching `ctx.source` cast `ctx.skill` (or its target, `ctx.cast_target`), recognizes the skill without knowing
  it (§4.3). Asked at most once per player per cast. |"
- **§4.6, after the `leash_return` paragraph:** the text in the blockquote below, with its list kept as a list.

> **`recognizes_skill`**: asked about a player who doesn't know the skill, when the engine first tells them about a cast
> (§4.3 **Recognition**). Its `ctx`:
>
> - `ctx.actor` (also `ctx.target`): the onlooker.
> - `ctx.source`: the caster.
> - `ctx.skill`: the skill.
> - `ctx.cast_target`: the cast's target actor, or `None`.
> - `ctx.room`, `pulse` and `rng`.
>
> Its `kind` and `tags` let a world make some skills harder or impossible to recognize. A rules error counts as `False`.
> When defined, the load-time dry run calls it with the dry-run player as onlooker and the throwaway mob as caster.

- **CHANGELOG:** "- New optional rules export `recognizes_skill(ctx)` (`ctx.source`, `ctx.cast_target`)."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// RulesEngine.kt, RulesContext: new field (with the other cast fields)
/** The cast's target actor, in recognizes_skill; null elsewhere. */
val castTarget: Actor? = null,

// RulesEngine.kt, RulesEngine interface
/** Whether c.actor recognizes c.skill cast by c.source; null when the world doesn't define it. */
fun recognizesSkill(c: RulesContext): Boolean? = null

// Rules.kt
RECOGNIZES_SKILL("recognizes_skill"),   // in RulesExport, after SKILL_POWER
```

- **Algorithmic Steps & Invariants:**
  1. Add a `RulesExportSpec` for it, with `signature = "recognizes_skill(ctx)"`, `returnType = "bool"`, `ctxFields =
     listOf("actor", "target", "source", "skill", "cast_target", "room", "pulse", "rng")`, and `hasDefault = true`. Use
     the feature gate that `skill_check` uses (`skills`), so defining it with `skills` off is the existing forbidden
     export error.
  2. Implement `StarlarkRules.recognizesSkill` exactly like `leashReturn`: return `null` when it isn't defined.
     Otherwise convert the result, failing with `rules.recognizes_skill return value: must be a bool, got <type>`. The
     `run` helper already returns `null` on a fault. Callers (Phase 4b) treat `null` as `False`.
  3. `RulesCtx.cast_target`: a struct field, doc "The cast's target actor, in recognizes_skill; None elsewhere."
  4. **Dry run:** when defined and `skills` is on, call it with `RulesContext(world, actor = player, target = player,
     source = mobs.first(), skill = <the dry-run skill>, room = player.room)`. Mirror the `LEASH_RETURN` block and the
     dry-run skill already built for `skill_check`.

### Test & Documentation Requirements

- In the rules tests:
  - Undefined returns null.
  - Defined: `True` and `False` pass through.
  - A non-bool result records a rules diagnostic and returns null.
  - The dry run calls it once with a player and a mob.
  - Defining it with `skills` off is a load error.
  - `ctx.cast_target` reads back.

### Exit Conditions

- [] `./gradlew :script:test :core:test` passes.
- [] `./gradlew check` passes with zero warnings and the API diff shows only the new export and `ctx.cast_target`.

---

## Phase 4a: The Anonymous Line Catalog, Picker and Perceiver Query

### Objective & Scope

- **In Scope:**
  - The `cast.anon.*` message catalog entries.
  - A pure four-tier picker, `anonLine`.
  - `firstDamage`.
  - A non-sending perceiver query, `perceiversOf`, that `tellPerceivers` delegates to.
  - The §4.18 text.
  - No change to what any player is told: nothing calls `anonLine` yet.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):**
  - Recognition, the cache, and any change to `CastAction.begin` or `resolve` (Phase 4b).
  - Trait callbacks (Phase 5).
  - Any plugin API change.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Perception.kt`: `tellPerceivers`, `tellSight`.
  - `core/src/main/kotlin/gups/core/world/Messages.kt`: `MsgGroup.msg` and how a `Msg` is filled (`World.msg`).
  - `core/src/main/kotlin/gups/core/model/Effects.kt`: `Effect.Damage`, `Effect.Sequence`.
  - `docs/SPEC.md` §4.18.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt`: `SkillMsg` entries, plus top-level internal `anonLine` and
    `firstDamage`.
  - `core/src/main/kotlin/gups/core/world/Perception.kt`: `Perceived`, `perceiversOf`.
  - New `core/src/test/kotlin/gups/core/world/AnonLineTest.kt`.
  - `docs/SPEC.md`, and the docs goldens (the message catalog lists the new entries).
- **Do Not Touch:** `CastAction`, `Rules.kt` (both), `plugin-api/`, `Memories.kt`.

### Specification & Decision Record Changes

- **§4.18, a new bullet after **Cast failure entries**:** "Anonymous cast entries, for players who don't recognize a
  skill (§4.3); see the list below." Then, as a nested list under that bullet, one item per entry:

> - Placeholders `actor`, `target` and `what`. The damage lines also take `damage_type`, the type's label.
> - `cast.anon.victim.damage`: "{actor}'s {what} hits you!"
> - `cast.anon.room.damage`: "{actor}'s {what} hits {target}."
> - `cast.anon.victim.aid`: "{actor}'s {what} makes you feel better."
> - `cast.anon.room.aid`: "{actor}'s {what} washes over {target}."
> - `cast.anon.victim.harm`: "{actor}'s {what} makes you feel worse."
> - `cast.anon.room.harm`: "{actor}'s {what} strikes {target}."
> - `cast.anon.resisted.victim`: "You resist {actor}'s {what}."
> - `cast.anon.resisted.room`: "{target} resists {actor}'s {what}."
> - `cast.anon.room`: "{actor} does something."
> - `cast.anon.room.target`: "{actor} does something to {target}."
> - `cast.anon.victim`: "{actor} does something to you."
> - `cast.anon.noun.harm`: "attack", and `cast.anon.noun.aid`: "aid" (no placeholders).
> - `cast.begin.unknown` remains the anonymous warmup line.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// SkillCommands.kt, SkillMsg: one val per entry above; "actor" required on every line, nothing on the nouns.
val ANON_VICTIM_DAMAGE =
  msg("cast.anon.victim.damage", "{actor}'s {what} hits you!", "actor,what,damage_type", "actor")
val ANON_ROOM_DAMAGE =
  msg("cast.anon.room.damage", "{actor}'s {what} hits {target}.", "actor,target,what,damage_type", "actor")
// ... ANON_VICTIM_AID, ANON_ROOM_AID, ANON_VICTIM_HARM, ANON_ROOM_HARM, ANON_RESISTED_VICTIM,
// ANON_RESISTED_ROOM, ANON_ROOM, ANON_ROOM_TARGET, ANON_VICTIM, ANON_NOUN_HARM, ANON_NOUN_AID

/** The first damage primitive of [effect], depth-first through sequences, or null. */
internal fun firstDamage(effect: Effect?): Effect.Damage?

/**
 * The line a player who doesn't recognize [skill] is told. [key] is "room", "victim",
 * "resisted_room" or "resisted_victim"; [target] is the display name of the cast's target (an
 * actor or item), or null when there is none.
 */
internal fun World.anonLine(skill: SkillProto, key: String, caster: Actor, target: String?): String

// Perception.kt
/** How a player perceives an event: on [channel]; [text] is perceive_message's text for a world channel. */
internal data class Perceived(val channel: Channel, val text: String?)

/**
 * The players of [room] (except [exclude]) who would be told an event rendered on [channels], with the
 * first channel each passes can_perceive for and that has text. Sends nothing.
 */
internal fun World.perceiversOf(
  room: Room,
  event: String,
  source: Actor?,
  channels: Set<Channel>,
  intensity: Int = 1,
  eventText: String? = null,
  vararg exclude: Actor,
): Map<Player, Perceived>
```

- **Algorithmic Steps & Invariants:**
  1. **`perceiversOf`:** take the loop of `tellPerceivers`. A channel "has text" if it is in `channels`, or if it is a
     declared (non-built-in) channel whose `rules.perceiveMessage(c)` is non-empty; in that case store the text in
     `Perceived.text`. Rewrite `tellPerceivers` as: `perceiversOf(room, event, source, renderings.keys, ...)`, then send
     `renderings[p.channel] ?: p.text` to each player. Behavior must not change, and every existing perception test must
     still pass.
  2. **`anonLine`:**
     - `noun = skill.unrecognized["noun"] ?: msg(if (skill.target == TargetKind.ENEMY) ANON_NOUN_HARM else
       ANON_NOUN_AID)`.
     - `dmg = firstDamage(skill.effect)`.
     - `what = listOfNotNull(dmg?.let { schema.damageTypeAdjective(it.type.name) }, noun).joinToString(" ")`.
     - `needsTarget = key == "room"`. The room lines of tiers 2 and 3 name a target, and the victim and resisted lines
       always have one: the victim is the viewer, and a resist has a victim.
     - Pick the first line that applies:
       - **Tier 1:** `skill.unrecognized[key]`, filled with `actor` and `target`.
       - **Tier 2:** when `dmg != null`, key `victim` → `ANON_VICTIM_DAMAGE`; key `room` with a non-null `target` →
         `ANON_ROOM_DAMAGE`. Both get `damage_type = schema.damageTypeLabel(dmg.type.name)`.
       - **Tier 3:**
         - `ENEMY`: `victim` → `ANON_VICTIM_HARM`; `room` with a target → `ANON_ROOM_HARM`; `resisted_victim` →
           `ANON_RESISTED_VICTIM`; `resisted_room` → `ANON_RESISTED_ROOM`.
         - `ALLY` or `SELF` with a target: `victim` → `ANON_VICTIM_AID`, `room` → `ANON_ROOM_AID`.
       - **Tier 4:** `room` → `ANON_ROOM_TARGET` with a target, else `ANON_ROOM`; `victim` → `ANON_VICTIM`.
     - So a `range="room"` enemy skill (no single target) gets `ANON_ROOM` for bystanders, whatever its damage.
     - Fill `{actor}` with `caster.name.cap()`, the way the named `ROOM_*` lines do, and `{target}` with `target`.
  3. **Callers decide when to use it:** `anonLine` doesn't know about landing rolls. Phase 4b calls it only once the
     outcome is known.

### Test & Documentation Requirements

- `AnonLineTest.kt`, built on the fixture world `ActionLifecycleTest` uses. The schema should declare
  `damage_type("fire", adjective = "fiery")` and a plain `"bludgeon"`. Cover:
  - Tier 1 for every key, with `{actor}`/`{target}` filled.
  - Tier 2: "Mage's fiery attack hits Tess." / "Mage's fiery attack hits you!". With `noun = "bolt"`: "fiery bolt". With
    a bludgeon skill: no adjective, "Mage's attack hits you!".
  - Damage inside `sequence(stun(1), damage(...))` is found.
  - Tier 3: an `enemy` `stun` → harm; an `ally` `heal` with a target → aid.
  - Tier 4: an `item` skill → "does something to <item>."; `room` with a null target on a damage skill → "Mage does
    something."
  - Resisted lines use `{what}`.
  - `perceiversOf`: a blind player is absent; a sighted one gets `SIGHT`; a player reached only through a declared
    channel whose `perceive_message` returns text gets that channel and text.

### Exit Conditions

- [] `./gradlew :core:test` passes, with no existing test changed.
- [] `./gradlew check` passes with zero warnings, including the docs goldens with the new catalog entries.

---

## Phase 4b: Recognition in Casts

### Objective & Scope

- **In Scope:**
  - The per-cast recognition cache.
  - Sight-checked warmup lines.
  - Per-viewer resolve lines, with anonymous `enemy` lines sent after the landing roll.
  - §4.3 **Recognition**, §5.3, §5.7.
  - CHANGELOG.
  - The test churn.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):**
  - Trait callbacks and firing `SKILL_RECOGNIZED` (Phase 5). Do record `learnedBy` on the cache.
  - Any plugin API change.
  - Template content.
  - Changes to the named lines' wording or timing for players who recognize the skill.

### Context Working Set

- **Read / Reference Only:**
  - Phase 4a's `anonLine`, `perceiversOf`, `firstDamage`.
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt`: `spellLands`, for the `traceRules` pattern.
  - `docs/SPEC.md` §4.3, §5.3, §5.7.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt`: `CastAction.begin`, `CastAction.resolve`, plus a new
    `CastRecognition` and `recognizes`.
  - `core/src/test/kotlin/gups/core/world/ActionLifecycleTest.kt`, plus a new
    `core/src/test/kotlin/gups/core/world/CastRecognitionTest.kt`.
  - Existing tests that assert room, victim or resisted lines for a player who doesn't know the skill (expect churn).
    Find them with `rg -n "casts .* at|begins casting|resists .*'s" core/src/test script/src/test harness/src/test
    samples template-world`.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `Perception.kt` (Phase 4a finished it), `Rules.kt` (both), `plugin-api/`, `Memories.kt` (`witness`
  stays as it is).

### Specification & Decision Record Changes

- **§4.3, a new paragraph after **Targeting**:** the text in the blockquote below, with its list kept as a list.

> **Recognition:** each player other than the caster who is told about a cast either recognizes the skill and sees its
> name, or doesn't and sees an anonymous line.
>
> - **When it's decided:** once per player per cast, at the first line about the cast they get. That is the warmup line
>   for a player who sees it. For an instant cast, or for someone who arrived mid-warmup, it is the resolution. Every
>   later line about that cast follows the same answer.
> - **How it's decided:** a player who knows the skill recognizes it. Anyone else asks the optional
>   `rules.recognizes_skill` (§4.6), and without it doesn't recognize it.
> - **Who is told:** bystanders are told when they see the caster (§5.7). One reached only through a world channel is
>   told that channel's `perceive_message` text, as today, and isn't asked. The cast's target is told whether or not
>   they see. Mobs are never asked.
> - **What the caster sees:** their own lines always name the skill.
> - **When an `enemy` skill is resisted:** a player who doesn't recognize it is told of a hit only once the skill lands.
>   If the target resists, they get only the resisted lines.
> - **The anonymous line**, from the first source that has one:
>   1. The skill's `unrecognized` entry for that line.
>   2. For a skill whose `effect` deals damage (its first `damage(...)`, reading into `sequence`), a line naming the
>      damage.
>   3. An aid line for an `ally` or `self` skill, or a harm line for an `enemy` skill.
>   4. A generic line. A room line with no single target, such as from a `range="room"` skill, always uses the generic
>      line.
>
> The engine's lines (§4.18) use `{what}`: the damage type's `adjective` and a space when it has one, then the skill's
> `unrecognized["noun"]`, or "attack" for an `enemy` skill and "aid" for the others (`cast.anon.noun.harm` and
> `cast.anon.noun.aid`). A fizzle (`cast.failed.room`) names no skill and doesn't change.

- **§5.3 step 3:** replace "Onlookers who know the skill see "<caster> begins casting <skill>."; others see "<caster>
  begins casting."" with "Each player who sees the caster (§5.7) is told "<caster> begins casting <skill>." if they
  recognize the skill (§4.3), and otherwise the skill's `unrecognized["begin"]` or "<caster> begins casting.""
- **§5.3 step 5:** replace "everyone else in the room except the target sees "<caster> casts <skill>.", with " at
  <target>" or " on <item>" when there is one" with "everyone else in the room who sees it, except the target, is told
  "<caster> casts <skill>." (with " at <target>" or " on <item>" when there is one) if they recognize the skill. The
  target is told the same way. A player who doesn't recognize the skill gets an anonymous line instead (§4.3); for an
  `enemy` skill, that line comes after the landing roll, and only if it lands."
- **§5.7, the in-room messages table:** in the first row, change `cast` to "`cast` (its warmup line and its
  resolution)".
- **CHANGELOG:**
  - "- Breaking: players who don't recognize a skill no longer see its name when it resolves, is resisted, or hits them.
    They see the skill's `unrecognized` line or a new `cast.anon.*` message."
  - "- The warmup line now reaches only players who can see the caster."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
/** One cast's recognition answers, by player id; true = recognized. */
internal class CastRecognition {
  val answers = HashMap<Long, Boolean>()
  /** Players who recognized the skill without knowing it, in the order they were asked. */
  val learnedBy = LinkedHashSet<Long>()
}

// CastAction: new field
private val recognition = CastRecognition()

// SkillCommands.kt, private
private fun World.recognizes(
  viewer: Player,
  caster: Actor,
  skill: SkillProto,
  castTarget: Actor?,
  rec: CastRecognition,
): Boolean
```

- **Algorithmic Steps & Invariants:**
  1. **`recognizes`:**
     - Return the cached answer if there is one.
     - If `skill.label in viewer.skills`, cache `true` (not added to `learnedBy`).
     - Otherwise cache `rules.recognizesSkill(RulesContext(this, actor = viewer, target = viewer, source = caster, skill
       = skill, room = viewer.room, castTarget = castTarget)) ?: false`. Add the viewer to `learnedBy` when it is true.
     - Trace with `traceRules(viewer, "recognizes_skill <label> → <answer>")`, mirroring `spellLands`.
  2. **`begin`:**
     - Keep `BEGIN_SELF`.
     - Replace the `actorIndex` loop with `perceiversOf(actor.room, "cast", actor, setOf(Channel.SIGHT), exclude =
       arrayOf(actor))`.
     - For each player: if `p.channel == SIGHT`, send `BEGIN_KNOWN` when `recognizes(...)`, otherwise
       `skill.unrecognized["begin"]` (filled) or `BEGIN_UNKNOWN`. Otherwise send `p.text`.
     - Mobs are no longer sent text (sending to a mob was already a no-op). `witness` and `on_cast_start` don't change.
  3. **`resolve`** (after `skill_check` passes; `FAILED_*` doesn't change):
     - The caster's `DONE_*` lines don't change.
     - Compute the bystanders once: `perceiversOf(room, "cast", actor, setOf(SIGHT), exclude = caster and t)`. Players
       reached through a world channel get `p.text` at once, as today.
     - Split the sighted bystanders and the target (a player, not the caster) into **named** (`recognizes` is true) and
       **anonymous**.
     - **Named:** exactly today's lines, at today's times: `DONE_VICTIM` and `ROOM_*` before the effects, then
       `RESISTED_TARGET`/`RESISTED_ROOM` if the target resists.
     - **Anonymous, `enemy` skill with a single actor target:** send nothing before the landing roll.
       - When `spellLands` returns **true**: the target gets `anonLine(skill, "victim", actor, null)`, and each
         anonymous bystander gets `anonLine(skill, "room", actor, t.name)`. Send these right after the roll and before
         the effect is applied, so they come before any damage or death lines.
       - When it returns **false**: the target gets `anonLine(..., "resisted_victim", actor, null)`, each anonymous
         bystander gets `anonLine(..., "resisted_room", actor, t.name.cap())`, and nothing else.
     - **Anonymous, any other skill:** send the line where today's line goes, before the effects:
       - The target (an `ally` target other than the caster) gets `anonLine(..., "victim", ...)`.
       - Bystanders get `anonLine(..., "room", actor, t?.name ?: onItem?.name)`. A `range="room"` skill passes `null`.
       - For a `range="room"` `enemy` skill, each victim it doesn't land on still gets the resisted lines, chosen per
         player as above.
  4. **Invariants:**
     - `recognizes` runs at most once per player per `CastAction`.
     - A player gets exactly one of "hit" or "resisted" for each victim, never both.
     - An interrupted cast, or one that fails re-validation, discards the cast and its cache.

### Test & Documentation Requirements

- New `CastRecognitionTest.kt`:
  - A known skill is named at warmup and at resolve, with no rules call. Assert with a rules stub that counts calls.
  - An unknown skill with no export gets the anonymous begin line, then the tier-2 damage lines for the target and a
    bystander.
  - **A resist:** with `to_hit` forced to 0 through the fixture's rules, an anonymous target and bystander get only the
    resisted lines: no "hits" line before or after. A named bystander gets `ROOM_TARGET` then `RESISTED_ROOM`, as today.
  - **A landed hit:** the anonymous lines arrive before the damage messages.
  - A `range="room"` enemy damage skill gives anonymous bystanders "X does something."
  - With an export that returns `True`: the named lines, and the export is called once per player across warmup and
    resolve.
  - A player who arrives mid-warmup is asked at resolve.
  - A blind bystander (`senses={"sight": 0}`) gets no warmup or room line. A blind target still gets the victim line.
  - The caster's own lines always name the skill.
  - `learnedBy` holds exactly the players who recognized the skill without knowing it.
- Update the churned assertions in existing tests. A test that expects the skill's name from a bystander who doesn't
  know it now expects the anonymous line, unless the bystander is given the skill.

### Exit Conditions

- [] `./gradlew :core:test` passes.
- [] `./gradlew check` passes with zero warnings (template and sample tests included).
- [] `rg -n "BEGIN_KNOWN|BEGIN_UNKNOWN" core/src/main` shows them used only in `SkillMsg` and `begin`.

---

## Phase 5: Core Trait Callbacks and `SKILL_RECOGNIZED`

### Objective & Scope

- **In Scope:**
  - The core `Trait` handler map, `TraitEvent.SKILL_RECOGNIZED`.
  - Firing the event after a cast's `on_skill_use`.
  - The §4.19 text.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):**
  - The plugin-facing types (Phase 6).
  - Any other trait event: the `DESIGN_NEEDED` entry "More trait event callbacks" owns those, so leave it unchanged.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/model/Entities.kt`: `recomputeTraits` (an actor's `traits` list is name-sorted).
  - `core/src/main/kotlin/gups/core/world/Verbs.kt`: `dispatchSkillUse`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Creation.kt`: `Trait`, plus the new `TraitEvent` and `TraitHandler`.
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt`: `CastAction.resolve`, after `reportCastUse`.
  - `core/src/test/kotlin/gups/core/world/CastRecognitionTest.kt`.
  - `docs/SPEC.md`.
- **Do Not Touch:** `plugin-api/`, `Rules.kt`.

### Specification & Decision Record Changes

- **§4.19, **Effects of traits**, a new bullet after **Modifiers**:** the text in the blockquote below, with its list
  nested under the new bullet.

> **Callbacks:** a trait may handle events of its bearer. Unlike modifiers, a handler may change the world, through the
> plugin facade (§4.15). The one event is `skill_recognized`:
>
> - **When it fires:** for each player who recognized a skill they don't know (§4.3) on a cast whose outcome is
>   `success` or `resist`, after that cast's `on_skill_use`.
> - **Order:** in the order the players were asked, then by trait name.
> - **Fields:** `source` (the caster), `skill` (its label), `outcome`, `target` (the cast's target actor, or null) and
>   `power` (the cast's power).
> - **Who is skipped:** a bearer no longer in the world.
> - **Failures:** a handler that throws records a `plugin` diagnostic.

### Implementation Blueprint

- **Signatures & Types** (`Creation.kt`):

```kotlin
enum class TraitEvent { SKILL_RECOGNIZED }

fun interface TraitHandler {
  /** Handles [event] for [bearer]; [fields] as described per event. May change the world. */
  fun handle(world: gups.core.world.World, bearer: Actor, event: TraitEvent, fields: Map<String, Any?>)
}

// Trait: new field
/** Callbacks run for the trait's bearer, by event. */
val handlers: Map<TraitEvent, TraitHandler> = emptyMap(),
```

- **Algorithmic Steps & Invariants** (`resolve`, after the existing `reportCastUse(..., outcome, landed)` for `success`
  and `resist` only):
  1. For each id in `recognition.learnedBy`:
     - Look up the player. Skip it if it's gone, `!inWorld`, or if it now knows the skill (a handler for an earlier
       player may have taught it).
     - For each trait in `player.traits` (name order) with a handler for `SKILL_RECOGNIZED`, call `handle(this, player,
       SKILL_RECOGNIZED, mapOf("source" to actor, "skill" to skill.label.v, "outcome" to outcome, "target" to t (null
       when an item or room skill), "power" to power))`.
  2. Wrap each call. A throw records `Diagnostic(pulse, "plugin", "trait '<name>' handler SKILL_RECOGNIZED threw:
     <message>")` and continues. Mirror the diagnostic shape in `traitAdapter`'s `refuse`.
  3. This runs before `resolve` returns its lag. A handler that removes the caster or the bearer from the world must not
     break recovery: the existing "No lag … if the resolve left the actor out of the world" check already covers the
     caster.

### Test & Documentation Requirements

- In `CastRecognitionTest.kt`:
  - Register a core `Trait` with a handler (directly in `world.traits`) and grant it to a player.
  - The handler fires once with the right fields for a recognized, unknown skill.
  - It doesn't fire for a known skill, for `fail`, or for a bystander who didn't recognize it.
  - Two players fire in ask order.
  - A throwing handler records a diagnostic and the second player's still fires.
  - A handler that teaches the skill to a later player skips that player.

### Exit Conditions

- [] `./gradlew :core:test` passes.
- [] `./gradlew check` passes with zero warnings.

---

## Phase 6: Plugin API for Trait Callbacks and Skill Lookup

### Objective & Scope

- **In Scope:**
  - `PluginTraitEvent`, `PluginTraitHandler`, `PluginTraitCall`, and `PluginTrait.on`.
  - `PluginWorld.skill(label)` / `PluginGmcpWorld.skill(label)`.
  - The adapter wiring.
  - The §4.15 text and `PLUGINS.md`.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):**
  - Plugin rules exports (Phase 7).
  - The sample plugin (Phase 9).

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt`: `traitAdapter` (the modifier wrapping and diagnostic
    pattern).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`: `SkillFacade`, `WorldFacade`, and `ActorFacade`.
- **Files to Modify / Create:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Adapters.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`
  - The plugin host tests that cover trait modifiers. Find them with `rg -l "PluginModifierPoint" plugin-api/src/test`.
  - `docs/SPEC.md`, `docs/PLUGINS.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `core/` (Phase 5 settled it), `samples/`.

### Specification & Decision Record Changes

- **§4.15, the `PluginRegistry` bullet:** after "traits (`PluginTrait`)" insert "(with event callbacks, `on`)".
- **§4.15, the facade bullet:** add `skill` to the query methods: "query methods (including `skill(label)`, a loaded
  skill's `PluginSkill` view or null)".
- **§4.15, a new bullet after **Event listeners**:**

  "**Trait callbacks:** `PluginTrait(on = mapOf(PluginTraitEvent.SKILL_RECOGNIZED to handler))` runs the handler for
  each bearer of the trait when the event happens to it (§4.19). The call carries a `PluginWorld`, which may change the
  world, along with the event, the `bearer` and `fields`. For `SKILL_RECOGNIZED`, the fields are `source` (a
  `PluginActor`), `skill` (the label), `outcome`, `target` (a `PluginActor` or null) and `power` (a `Long`). A throw
  records a `plugin` diagnostic."

- **`PLUGINS.md`, under `#### Traits`:** a new paragraph, "Traits can also react", showing a ten-line `on =` example
  with `SKILL_RECOGNIZED` that sends the bearer a line, and pointing to `samples/bluemage-plugin` (README added in Phase
  9).

- **CHANGELOG:** "- Plugins: `PluginTrait.on` trait callbacks (`SKILL_RECOGNIZED`) and `PluginWorld.skill(label)`."

### Implementation Blueprint

- **Signatures & Types** (`PluginApi.kt`):

```kotlin
enum class PluginTraitEvent { SKILL_RECOGNIZED }

data class PluginTraitCall(
  val world: PluginWorld,
  val event: PluginTraitEvent,
  /** The actor carrying the trait. */
  val bearer: PluginActor,
  /** The event's fields as plain values: ints as Long, actors as PluginActor. */
  val fields: Map<String, Any?>,
)

fun interface PluginTraitHandler { fun handle(call: PluginTraitCall) }

// PluginTrait: new last field
val on: Map<PluginTraitEvent, PluginTraitHandler> = emptyMap(),

// Facade.kt, PluginGmcpWorld. The default body keeps existing test doubles compiling; the host overrides it.
/** A loaded skill's view by absolute label, or null. */
fun skill(label: String): PluginSkill? = null
```

- **Algorithmic Steps & Invariants:**
  1. **`traitAdapter`:** map `pt.on` to `Trait.handlers`. Each core handler does the following:
     - Builds `PluginTraitCall(WorldFacade(w, pluginId, Facades.logOf(w)), event, ActorFacade(bearer, w), fields)`,
       converting `Actor` → `ActorFacade` and `Int` → `Long`.
     - Calls the plugin handler.
     - Catches any throw and records `plugin '<id>': trait '<name>' handler SKILL_RECOGNIZED threw: <msg>` as a `plugin`
       diagnostic, the way `refuse` does.
  2. **`skill(label)`** in the host facade: parse the label with `Label(...)`, returning null on a parse failure. Look
     it up in `w.protos.skills` (find the skill map with `rg -n "skills\["
     core/src/main/kotlin/gups/core/world/Protos*`) and wrap it in `SkillFacade`. Put it on the `PluginGmcpWorld`
     implementation, so GMCP handlers can read it too.
  3. Test doubles of `PluginWorld`/`PluginGmcpWorld` in `samples/` and `plugin-api/src/test` need no change, because of
     the default body. Don't edit them.

### Test & Documentation Requirements

- In the host tests:
  - A plugin trait with `on` fires through a real cast in a fixture world, with fields of the documented types.
  - A throwing handler records the `plugin` diagnostic.
  - `skill("//skills:x")` returns the view with tags and kind. An unknown or malformed label returns null.

### Exit Conditions

- [] `./gradlew :plugin-api:test` and every `samples:*:test` pass.
- [] `./gradlew check` passes with zero warnings.

---

## Phase 7: Plugin-Declared Rules Exports

### Objective & Scope

- **In Scope:**
  - `PluginRegistry.addRulesExport` and `PluginRulesType`.
  - `PluginWorld.rules(...)`.
  - The loader accepting declared names.
  - `RulesEngine.pluginExport`.
  - The new `ctx.base` and `ctx.args` fields.
  - The dry run.
  - Plugin docs info.
  - The §4.6 and §4.15 text, and `PLUGINS.md`.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):**
  - The sample plugin's export (Phase 9).
  - Any feature gating of plugin exports.

### Context Working Set

- **Read / Reference Only:**
  - `script/src/main/kotlin/gups/script/Rules.kt`: `StarlarkRules.run`, `leashReturn`, and the dry-run function.
  - `script/src/main/kotlin/gups/script/Loader.kt`: `unknownRulesExports` and its caller.
  - `script/src/main/kotlin/gups/script/WorldPluginWiring.kt`.
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`: `PluginRegistryImpl` (the `MEMBER` regex and
    `onEvent` validation), `PluginInfo`/`PluginTraitInfo`, and the `WorldPluginWiring` implementation.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/rules/RulesEngine.kt`
  - `script/src/main/kotlin/gups/script/Rules.kt`
  - `script/src/main/kotlin/gups/script/Loader.kt`
  - `script/src/main/kotlin/gups/script/WorldPluginWiring.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`
  - Tests in `script/src/test` (loader and rules) and `plugin-api/src/test` (host).
  - `docs/SPEC.md`, `docs/PLUGINS.md`, `docs/CHANGELOG.md`, and the API files.
- **Do Not Touch:** `SkillCommands.kt`, `samples/`, `template-world/`.

### Specification & Decision Record Changes

- **§4.6, a new paragraph after the opening paragraph's list of optional exports:** the text in the blockquote below,
  with its list kept as a list.

> **Plugin-declared exports:** a plugin may declare optional exports of its own (§4.15), named `<plugin id>_<name>`
> (`bluemage_learn_chance`). The rules package may define any of them; defining one no loaded plugin declares is the
> usual "is not a rules export" load error.
>
> - **The call:** the plugin calls its export with fields of its choosing. `actor`, `target`, `source`, `skill` and
>   `item` become those `ctx` fields. `base` is `ctx.base`, the value the plugin would use by itself. Anything else is
>   in `ctx.args`, a dict.
> - **Results:** the result must be the declared type (`int` or `bool`). A wrong type or an error is a rules diagnostic,
>   and the plugin gets its default.
> - **Dry run:** the load-time dry run calls each defined plugin export once, with the dry-run player as `ctx.actor`,
>   the throwaway mob as `ctx.source`, `ctx.base` 0 or `False`, and empty `ctx.args`.
> - **Tracing:** `@trace rules` shows each call.

- **§4.15, the `PluginRegistry` bullet:** add "optional rules exports (`addRulesExport`)" to the list. **The facade
  bullet:** add `rules` to the verbs.

- **§4.15, a new bullet after **Trait callbacks**:** the text in the blockquote below, with its list nested under the
  new bullet.

> **Rules exports:** `addRulesExport(name, doc, type)` declares an optional rules export the world may define (§4.6).
>
> - **Names:** `name` must be `<id>_<snake_case>`. Registering a name twice fails `register`.
> - **Types:** `type` is `PluginRulesType.INT` or `BOOL`.
> - **Calling it:** `world.rules(name, fields, default)` runs the world's definition and returns a `Long` or a
>   `Boolean`. When the world doesn't define it, or the call fails, it returns `default`. Calling a name the plugin
>   didn't declare throws.
> - **Docs:** declared exports appear with the plugin's docs.

- **CHANGELOG:** "- Plugins can declare optional rules exports (`addRulesExport`, `PluginWorld.rules`); rules calls gain
  `ctx.base` and `ctx.args`."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// PluginApi.kt
enum class PluginRulesType { INT, BOOL }
// PluginRegistry. Default bodies keep existing test doubles compiling; the host overrides both.
fun addRulesExport(name: String, doc: String, type: PluginRulesType) {}

// Facade.kt, PluginWorld
/**
 * Calls this plugin's declared rules export [name]; returns a Long or Boolean, or [default] when the
 * world doesn't define it or the call fails.
 */
fun rules(name: String, fields: Map<String, Any?>, default: Any): Any = default

// WorldPluginWiring.kt
/** Plugin-declared rules exports: name to "int" or "bool". */
val rulesExports: Map<String, String>
  get() = emptyMap()

// RulesEngine.kt
// RulesContext: new fields
/** The plugin's own value, in a plugin-declared export; null elsewhere. */
val base: Any? = null,
/** Extra plugin fields, in a plugin-declared export; null elsewhere. */
val args: Map<String, Any?>? = null,
// RulesEngine
/** Runs plugin-declared export [name]; null when undefined, on error, or on a wrong type. */
fun pluginExport(name: String, type: String, c: RulesContext): Any? = null
```

- **Algorithmic Steps & Invariants:**
  1. **Registry** (`PluginRegistryImpl`):
     - Require `name` to match `^${id}_[a-z][a-z0-9_]*$`. On failure: "addRulesExport(): '<name>' must be '<id>_' then a
       snake_case name".
     - Require the name to be unused within the plugin, and not equal to any `RulesExport.scriptName`.
     - Store a `PluginRulesExportInfo(name, doc, type)`. Add it to `PluginInfo` beside `traits`, so the docs page lists
       it, and update the docs renderer if it enumerates `PluginInfo` fields.
  2. **Wiring:** the host's `WorldPluginWiring.rulesExports` returns every loaded plugin's declared names, mapped to
     `"int"`/`"bool"`.
  3. **Loader:**
     - `unknownRulesExports(module, extra: Set<String>)` adds `extra` to `known`. The caller passes
       `plugins.rulesExports.keys`.
     - `StarlarkRules` needs the map too. Pass it when the loader constructs it (find the constructor call with `rg -n
       "StarlarkRules(" script/src/main`).
  4. **`StarlarkRules.pluginExport(name, type, c)`:**
     - Return null unless the module defines `name`.
     - Generalize `run` so it takes a script name instead of a `RulesExport`: extract the body into `runNamed(name:
       String, ...)` and have `run(export, ...)` delegate.
     - Convert the result: `"int"` needs a Starlark int, returned as a `Long`; `"bool"` needs a bool. Otherwise fail
       with `rules.<name> return value: must be an int` (or `a bool`), which `run` turns into a fault, so the call
       returns null.
  5. **`RulesCtx`:**
     - `base`: a struct field converting `Long`/`Int`/`Boolean` back to Starlark, or `None`.
     - `args`: a frozen dict of the plain values, with actors converted through `view(frame, ...)` and lists or maps
       converted recursively, or `None` outside plugin exports.
     - Docs: "The plugin's own value, in a plugin-declared rules export; None elsewhere." and "Extra fields a plugin
       passed to its rules export; None elsewhere."
  6. **`WorldFacade.rules(name, fields, default)`:**
     - Require `name` to be among this plugin's declared names, or throw `IllegalArgumentException("rules(): '<name>' is
       not declared by plugin '<id>'")`.
     - Require `default` to match the type (a `Long`/`Int` for `INT`, a `Boolean` for `BOOL`).
     - Build the `RulesContext`:
       - `actor`/`target`/`source`: `PluginActor` → live actor via the facade's existing unwrap.
       - `skill`: a `PluginSkill` or label string → `SkillProto`.
       - `item`: → `Item`.
       - `base`: as given.
       - `args`: the remaining entries, with views unwrapped the same way.
       - `room`: the actor's room.
     - Call `w.rules.pluginExport(name, typeName, c)`, and return the result, or `default` (as a `Long` for `INT`) when
       it is null.
     - Trace with `traceRules(actor, "rules.<name> → <value>")` when there is an actor.
  7. **Dry run:** for each `(name, type)` in the map that the module defines, call `pluginExport` with
     `RulesContext(world, actor = player, target = player, source = mobs.firstOrNull(), base = 0L or false, args =
     emptyMap())`, and add a failure to `problems` the way the other dry-run calls do.

### Test & Documentation Requirements

- **Loader:** a rules file defining `demo_chance` loads when a fake wiring declares it, and fails "is not a rules
  export" without it.
- **Rules:**
  - `pluginExport` returns the int.
  - A wrong type returns null and records a diagnostic.
  - `ctx.base` and `ctx.args` read back, including an actor view inside `args`.
  - The dry run calls it once.
- **Host:**
  - A bad name fails `register`.
  - A duplicate name fails `register`.
  - `rules()` on an undeclared name throws.
  - `rules()` returns the default when the world doesn't define the export, and the world's value when it does.
- **`PLUGINS.md`:** a new `### Rules exports` section after `### Custom hook handlers`, with the walkthrough from the
  design: declare, define in rules, call with `base`, clamp after. Note that worlds without the plugin must not define
  the export.

### Exit Conditions

- [] `./gradlew :script:test :plugin-api:test` passes.
- [] `./gradlew check` passes with zero warnings, and the API diff shows only `ctx.base`, `ctx.args` and the plugin docs
  model additions.

---

## Phase 8: Template World Content

### Objective & Scope

- **In Scope:**
  - The template's `MODULE.gups` damage types.
  - `recognizes_skill` in the template rules.
  - `spark`'s `blue` tag and `unrecognized` dict.
  - A template Starlark test.
  - The §3.9 example.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):**
  - `bluemage_learn_chance`: never in the template, since a rules export of an unloaded plugin is a load error.
  - `samples/robot-world`: it defines no `recognizes_skill` and keeps plain damage type strings.
  - Tutorials under `template-world/docs/`, except a one-paragraph note in the skills tutorial if one covers `skill()`
    fields. Find it with `rg -l "kind = \"spell\"" template-world/docs`.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/rules/combat.star`: the `skill_check` style.
  - `template-world/zones/tutorial/trainer_test.star`: the harness test style.
- **Files to Modify / Create:**
  - `template-world/MODULE.gups`
  - `template-world/rules/perception.star`: add `recognizes_skill` here; it's a perception question.
  - `template-world/skills/arcane/spells.star`
  - New `template-world/zones/tutorial/recognize_test.star`
  - `docs/SPEC.md` (§3.9 example), `docs/CHANGELOG.md`.
- **Do Not Touch:** `samples/robot-world/`, `core/`, `script/`, `plugin-api/`.

### Specification & Decision Record Changes

- **§3.9, the template example:** `damage_types=["bludgeon", "slash", "pierce", "fire"],` becomes the four
  `damage_type(...)` calls in the Phase 1 note.
- **CHANGELOG:** "- Template world: `recognizes_skill` (Intelligence and Wisdom; skills tagged `subtle` are never
  recognized), damage type adjectives, and `spark` carries the `blue` tag and an `unrecognized` dict."

### Implementation Blueprint

- **`MODULE.gups`:** `damage_types = [damage_type("bludgeon", adjective = "crushing"), damage_type("slash", adjective =
  "slashing"), damage_type("pierce", adjective = "piercing"), damage_type("fire", adjective = "fiery")]`.
- **`perception.star`**, the agreed formula:

```python
def recognizes_skill(ctx):
    # Whether ctx.actor, watching ctx.source, recognizes ctx.skill without knowing it.
    # Skills tagged "subtle" can't be recognized by watching. A technique is easier to read than an
    # incantation.
    if "subtle" in ctx.skill.tags:
        return False
    pct = 10 + 3 * (ctx.actor.int - 10) + 2 * (ctx.actor.wis - 10)
    if ctx.skill.kind != "spell":
        pct += 30
    return ctx.rng.chance(_clamp(pct, 0, 95))
```

Use the stat access the template already uses (`ctx.actor.dex` in `initiative`, so `ctx.actor.int`), and define or reuse
`_clamp` in that file. Check `ctx.rng`'s method name in `Ctx.kt`/`Rules.kt` (`chance` or `int`), and use `ctx.rng.int(1,
100) <= pct` if there is no `chance`.

- **`spells.star`, `spark`:** add `tags = ["blue"]` and `unrecognized = {"noun": "spark", "begin": "{actor}'s fingertips
  crackle with heat."}`. Leave `mend`, `soothe` and `ward` without `unrecognized`, so they show the fallback tiers.
  Check that `heritage-plugin`'s `LEARN`/`CAST` restrictions key on `arcane`, not `blue`: `spark` has no `arcane` tag,
  so they are unaffected.

- **`recognize_test.star`:**

  - Spawn two players at the mentor's room with `stats` giving `int`/`wis` of 3 (never recognizes; pct clamps to 0) and
    18 (pct 50; use a seed where the roll succeeds, found by running).
  - Have the mentor `act` `cast spark` at a third player.
  - Assert:
    - the low-Int player sees "The mentor's fiery spark hits <target>."
    - the target, with low Int, sees "The mentor's fiery spark hits you!"
    - the high-Int player sees "The mentor casts spark at <target>."
  - Use `w.act(mob, "cast spark <name>")` per the harness API. Check the exact name capitalization in the transcript
    before asserting.

### Test & Documentation Requirements

- `recognize_test.star` passes under `./gradlew check`; template package tests run there.
- Run the template's existing tests. Any that assert a bystander's `spark` line now need the bystander to know `spark`
  or to expect the anonymous line.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including every template-world and sample test (they copy the
  template's rules and `MODULE.gups`).
- [] `gups scenario` sanity check per `AGENTS.md`: a scratch scenario in `local-world-test/` where a low-Int player
  watches the mentor cast `spark` shows the anonymous lines. Delete `local-world-test/` afterwards.

---

## Phase 9: The `bluemage-plugin` Sample

### Objective & Scope

- **In Scope:**
  - `samples/bluemage-plugin`: plugin code, a fixture-world integration test, unit tests, and a README.
  - Registering it in `settings.gradle.kts`.
  - The §2.2 and §4.15 sample text.
- **Out of Scope (the agent must not implement these in this phase; later phases own them):**
  - Any change to `heritage-plugin`.
  - A multi-chapter tutorial series. One README is enough for this demo.
  - Any engine change. If the plugin needs one, stop and report it.

### Context Working Set

- **Read / Reference Only:**
  - `samples/curse-plugin/`: `build.gradle.kts`, `src/plugin/META-INF/services/gups.pluginapi.GupsPlugin`,
    `CursePlugin.kt` (builtin and command registration), and `CurseWorldTest.kt` (the fixture world built from the
    template's `MODULE.gups` and rules).
  - `samples/hunger-plugin/`: builtin registration and attr use.
- **Files to Modify / Create:**
  - `settings.gradle.kts`: add `"samples:bluemage-plugin"`.
  - `samples/bluemage-plugin/build.gradle.kts`: mirror curse, with jar `bluemage.jar` and property
    `gups.sample.bluemageJar`.
  - `samples/bluemage-plugin/src/plugin/META-INF/services/gups.pluginapi.GupsPlugin`
  - `samples/bluemage-plugin/src/main/kotlin/gups/sample/bluemage/BlueMagePlugin.kt`
  - `samples/bluemage-plugin/src/test/kotlin/gups/sample/bluemage/BlueMagePluginTest.kt`: mock-facade unit tests of the
    formula.
  - `samples/bluemage-plugin/src/test/kotlin/gups/sample/bluemage/BlueMageWorldTest.kt`
  - `samples/bluemage-plugin/README.md`
  - `docs/SPEC.md` (§2.2, §4.15), `docs/CHANGELOG.md`.
- **Do Not Touch:** `core/`, `script/`, `plugin-api/`, `samples/heritage-plugin/`, `template-world/`.

### Specification & Decision Record Changes

- **§2.2, the `samples/` line:** "sample plugins: weather-plugin, heritage-plugin (race and class), curse-plugin,
  hunger-plugin and bluemage-plugin (§4.15)". Keep the robot-world clause.
- **§4.15, a new sample bullet after **Hunger Plugin**:**

  "**Blue Mage Plugin ([samples/bluemage-plugin](../samples/bluemage-plugin/README.md)):** trait callbacks and
  plugin-declared rules exports. Its `blue_mage` trait, granted by the `bluemage.awaken(actor)` builtin, handles
  `SKILL_RECOGNIZED`. When a blue mage recognizes a mob's skill tagged `blue` that they don't know, they may learn it at
  proficiency 10. The chance falls with the caster's level above theirs and with the cast's power, rises if they were
  the target, passes through the optional rules export `bluemage_learn_chance`, and is clamped to 0–33%. A `bluemagic`
  command lists what they learned this way."

- **CHANGELOG:** "- New sample `samples/bluemage-plugin`."

### Implementation Blueprint

- **`BlueMagePlugin`:** `id = "bluemage"`, `requiredFeatures = setOf("skills")` (check the property name in
  `GupsPlugin`), a doc string, and `register(r)`.
  1. `r.addRulesExport("bluemage_learn_chance", <doc from the design walkthrough>, PluginRulesType.INT)`.
  2. Register `PluginTrait(name = "blue_mage", label = "Blue mage", doc = "Learns marked skills by watching mobs use
     them.", on = mapOf(PluginTraitEvent.SKILL_RECOGNIZED to PluginTraitHandler(::onRecognized)))`, with `addTrait` or
     whatever the registry calls it.
  3. **Builtin `bluemage.awaken(actor)`:** `world.grantTrait(actor, "blue_mage")`. Return `None` on success, or the
     refusal string. Mirror the hunger builtin's registration.
  4. **Command `bluemagic`** (fast lane, player): list the labels in the attr `bluemage.learned` (a list of strings),
     one per line, as each skill's `world.skill(label)?.name`. Print "You haven't learned any blue magic." when it's
     empty, and "You are not a blue mage." for non-bearers.
  5. **`onRecognized(call)`**, the gates and formula:
     - Return when `source.isPlayer`, when `outcome !in setOf("success", "resist")`, when `world.skill(label)` is null,
       when `"blue" !in skill.tags`, or when `label in bearer.skills`.
     - `wasTarget = target?.id == bearer.id`.
     - `base = 1 + (if (wasTarget) 10 else 0) - 5 * max(0, source.level - bearer.level) - (power - 100) / 10`.
     - `pct = (world.rules("bluemage_learn_chance", mapOf("actor" to bearer, "source" to source, "skill" to skill,
       "base" to base.toLong(), "power" to power, "was_target" to wasTarget), default = base.toLong()) as
       Long).toInt().coerceIn(0, 33)`.
     - If `!world.rng.chance(pct)`, return.
     - If `world.setSkill(bearer, label, 10) != null` (a `LEARN` restriction refused), return silently.
     - `world.send(bearer, "You grasp the secret of ${skill.name}!")`.
     - Append the label to the `bluemage.learned` attr.
- **Attr namespacing:** check §3.7. Plugin attrs may need the plugin-id prefix the facade enforces, so use whatever
  `hunger-plugin` uses.

### Test & Documentation Requirements

- **`BlueMagePluginTest`** (mock facades, mirroring `CursePluginTest`):
  - Every gate returns early: player caster, `fail`, untagged skill, known skill.
  - The formula, without a rules export (mock `rules` returning the default):
    - Equal levels, power 100, not the target → 1.
    - The target → 11.
    - Caster 3 levels higher → 0 after the clamp.
    - Power 200 → base reduced by 10.
  - A rules value of 80 clamps to 33.
  - A refused `setSkill` sends nothing.
- **`BlueMageWorldTest`** (mirror `CurseWorldTest`):
  - Copy the template's `MODULE.gups` (with `plugins = {"bluemage": "<sha>"}`) and rules.
  - Append to the copied `perception.star` a `bluemage_learn_chance(ctx)` that returns `ctx.base + (ctx.actor.int -
    10)`.
  - Write a small zone with a mob that knows a `blue`-tagged `enemy` skill, and a player who recognizes it. Either give
    the player a high `int`, or **edit** the copied `perception.star` so its `recognizes_skill` returns `True`. Don't
    add a second `recognizes_skill` in another file: an export defined in two files is a load error.
  - Through `TestRunner`, check:
    - `bluemage.awaken` grants the trait.
    - After enough mob casts with a fixed seed (or with `bluemage_learn_chance` returning 33 and a seed known to
      succeed), the player gets "You grasp the secret of …", knows the skill at 10, and `bluemagic` lists it.
    - A player without the trait never learns.
    - A rules file defining `bluemage_learn_chance` without the plugin pinned fails to load.
- **`README.md`:** what it shows (trait callbacks, a rules export, skill lookup), the formula, the rules snippet a world
  adds, how to grant the trait from content (for example a mob `on_say` hook calling `bluemage.awaken(ctx.actor)`), and
  how to tag a skill `blue`.

### Exit Conditions

- [] `./gradlew :samples:bluemage-plugin:test` passes.
- [] `./gradlew check` passes with zero warnings, including the module-graph check (the sample's main configuration
  depends only on `:plugin-api`).

---

## Phase 10: Finalization & Backlog Update

- In `docs/DESIGN_NEEDED.md`, mark `### Recognizing an unfamiliar spell` as completed: add a line "**Status:** completed
  by Milestone 47." directly under the heading. Do not delete the entry. Leave "More trait event callbacks" unchanged.
- Re-read §4.3, §4.6, §4.15, §4.18, §4.19, §5.3 and §5.7 against the code, and fix any drift the phases left.
- Check that the CHANGELOG subsection holds every bullet listed above.
- Grep for the naming rule: `rg -n "recogni[sz]es_spell|SPELL_RECOGNI|spell_recogni" -g '!docs/MILESTONE_*' -g
  '!docs/DESIGN_NEEDED.md'` returns nothing. The source entry's original question keeps its old wording.
- Run `./gradlew markdownFormat`, then `./gradlew check`. It must pass with zero warnings.
- End-to-end check with `gups scenario` (per `AGENTS.md`) against the template world: a player with low Int watches the
  mentor cast `spark` and sees "…'s fiery spark hits …"; a player who knows `spark` sees its name. Delete
  `local-world-test/` afterwards.
