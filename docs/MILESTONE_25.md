# Milestone 25: Shopkeepers in Combat

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, section "Economy and crafting", heading `### Shopkeepers in
  combat`.

- **Design Summary:**

  - **A fighting keeper refuses everyone.** In `//lib:shop.star`, `_shop_here` gains one more gate after the stun check.
    While the keeper's `fighting()` list is non-empty, `list`, `buy`, `sell` and `value` all answer "<keeper> is busy
    fighting." and change nothing. This applies to every customer, its opponents and bystanders alike. The `shop_price`
    hook is not consulted and cannot override the refusal, the same as the stun gate.
  - **Scripts can test for a feature.** A new kernel `ctx` verb, `ctx.has_feature(name) -> bool`, returns whether the
    world has the named feature module on. It is available whatever the world's features are. An unknown name raises a
    script error listing the valid ids. The library checks `ctx.has_feature("combat")` before calling
    `keeper.fighting()`, so the shop works unchanged in a world without combat. The §3.10 rule that an off feature's
    view fields are errors stays as it is: `actor.fighting()` still raises with combat off.
  - **No memory of attackers.** Once the fight ends the keeper trades with everyone again, its former attacker included.
    A world that wants grudges builds them itself: the keeper's `on_damage` hook records the source in an attribute, and
    `shop_price` refuses or reprices based on it. A general hate list belongs to the separate `### Hostility and
    factions` entry, and this milestone does not touch it.
  - **Restocking continues during a fight**, as it does during a stun: the keeper is still present. Restocking is lazy,
    so in practice refused customers trigger no catch-up. The first trade after the fight, or a container open or wake
    during it, catches up on the time that passed. No restock code changes.
  - **Out of scope:** `has_feature` on the rules `ctx` (`Rules.kt`) or the harness `w` handle; a load-time feature
    query; grudges, hate lists or exposing `lastAttacker` to scripts; pausing restock; any change to engagement,
    `fighting()` or §3.10's "off feature doesn't exist" rule.
- **Specification Impact:** §3.10 (a bullet saying scripts can query features), §4.5 (verb table Queries row and the
  "Features" semantics bullet), §4.9 (the `//lib:shop.star` paragraph), §7.4 (new M25 row); `docs/CHANGELOG.md`;
  `template-world/docs/tutorials/08_shops.md`; the regenerated API files.
- **Decision Record Updates:** None.

### Guardrails for every phase

1. Follow `AGENTS.md`: TDD (write tests first and see them fail), no spec section numbers or decision ids in comments
   (Kotlin or Starlark), all Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. New symbols need no `@ApiSince`.
3. Add CHANGELOG bullets in a new `### Shopkeepers in combat` subsection at the top of `## api_version 1 (unreleased)`
   (above M24's `### Shops`). Create it in Phase 1.
4. In a phase that changes the script API (Phase 1 only), regenerate all three API files **before** the phase's final
   `./gradlew check`:
   - `./gradlew :cli:test -Dgups.updateGolden=true` rewrites `cli/src/test/resources/api.golden.json` and the
     `cli/src/test/resources/docs-golden/` files;
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` from the engine
     checkout, rewrites `docs/api/api-1.json`;
   - read the diff: only `ctx.has_feature` and `ctx.has_feature.name` should be new. `ApiDiffTest` must then pass;
   - if a test fails because the new symbol has no example, add one in
     `script/src/main/kotlin/gups/script/DocExamples.kt`, mirroring the `"ctx.merge_stack"` entry (a room command that
     sends the result, and a test that checks the reply).

   Phases 2 and 3 change only Starlark content, Kotlin tests and docs, so the API files must not change. If `gups docs`
   output for the template world changes, regenerate the affected goldens and read the diff.

5. Gradle commands must run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that
   code blocks kept their indentation.

6. Starlark style: follow `template-world/lib/shop.star` itself (short docstrings, `_private` helpers, no lambdas or
   nested functions).

7. Never read or touch `local-world/`.

---

## Phase 1: `ctx.has_feature`

### Objective & Scope

- **In Scope:** a kernel `ctx` verb reporting whether a feature module is enabled; its spec text, tests, CHANGELOG
  bullet and API files.
- **Out of Scope:** the shop library; the rules `ctx`; the harness `w` handle; `FeatureOwnership` (the verb belongs to
  the kernel, so no ownership entry is added).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Features.kt`: `Feature` (with `byId`), `Features.contains`.
  - `script/src/main/kotlin/gups/script/Ctx.kt`: the private `verb(name, detail) { ... }` wrapper (around line 118);
    `roll` (around line 1903) as the pattern for a single-string-argument query verb; `str(x, what)` and `fail(...)`
    helpers.
  - `script/src/test/kotlin/gups/script/FeatureScriptTest.kt`: `without(...)`, `world(...)`, `ScriptFixture`, and the
    private `runtimeError(off, body)` helper (around line 376), which runs a snippet in a room's `on_enter`.
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`
  - `script/src/test/kotlin/gups/script/FeatureScriptTest.kt`
  - `script/src/main/kotlin/gups/script/DocExamples.kt` (only if a docs test demands an example)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, the regenerated API files (guardrail 4)
- **Do Not Touch:** `script/src/main/kotlin/gups/script/Ownership.kt`, `Views.kt`, `Rules.kt`, `core/` main code,
  `template-world/`.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§4.5, verb table, Queries row:** append `` `has_feature(name) -> bool` `` after `` `roll(dice) -> int` ``, so the
   row reads: `` `here(keyword)`, `find(label, scope="room")`, `enemies_near(actor, band)`, `allies_near(actor, band)`,
   `path_to(target) -> [dir]`, `roll(dice) -> int`, `has_feature(name) -> bool` ``.
2. **§4.5, "Verb semantics", the "Features" bullet:** append this sentence: "`has_feature(name)` belongs to the kernel
   and works in every world: it returns whether the world has the feature `name` on (`"combat"`, `"track"`, `"skills"`
   or `"leveling"`), and any other name raises an error."
3. **§3.10, the "A feature that is off doesn't exist" bullet list:** add a final sub-bullet: "Scripts can test for a
   feature with `ctx.has_feature(name)` (§4.5), so a library can serve worlds with and without it."

CHANGELOG (new `### Shopkeepers in combat` subsection, guardrail 3): one bullet, "`ctx.has_feature(name)` reports
whether the world has a feature module on; an unknown name is an error."

### Implementation Blueprint

- **Signatures & Types:** in `Ctx.kt`, next to `roll` in the queries area:

  ```kotlin
  @StarlarkMethod(
    name = "has_feature",
    doc = "Whether the world has the named feature module on.",
    parameters = [Param(name = "name", named = true, doc = "Feature id, such as 'combat'.")],
  )
  fun hasFeature(name: Any): Any
  ```

  Add `import gups.core.world.Feature` if it isn't already imported.

- **Algorithmic Steps & Invariants:**

  1. Wrap the body in `verb("has_feature", "$name") { ... }`. The wrapper's ownership check finds no owner for
     `ctx.has_feature`, so the verb works in every world.
  2. `val id = str(name, "has_feature(name)")`.
  3. `val f = Feature.byId(id) ?: fail("has_feature(): unknown feature '$id'; expected one of " +
     Feature.entries.joinToString { it.id })`. Mirror how `roll` raises its own `fail("roll(): ...")`.
  4. Return `f in world.features` as a Kotlin `Boolean`. Other verbs return booleans the same way, for example `gmcp`.
  5. The verb reads only. It must not change world state, so fast-lane commands such as `list` can call it.

### Test & Documentation Requirements

Add to `FeatureScriptTest.kt`, mirroring `runtimeError` (a snippet in the hall's `on_enter` that sends its result to the
player, read back from the player's output; or record it with `ctx.set_attr` and read the attribute, whichever the
fixture supports more simply):

- `` `has_feature reports each enabled feature` ``: with `Features.ALL`, `ctx.has_feature(x)` is `True` for all four
  ids. With `without(Feature.COMBAT)`, `"combat"` and `"track"` are `False`, and `"skills"` and `"leveling"` are `True`.
- `` `has_feature rejects an unknown name` ``: `ctx.has_feature("magic")` raises a script error containing `unknown
  feature 'magic'`. `ctx.has_feature(3)` raises a type error, not the feature refusal.
- `` `has_feature works whatever is off` ``: with all four features off (`Features(emptySet())`, which `world(...)`
  writes as `features = []` with `TestWorlds.rulesFor(emptyList())`), `ctx.has_feature("combat")` returns `False` and
  raises no `needs the ... feature` error.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `ApiDiffTest` and the docs goldens.
- [] `git diff docs/api/api-1.json` (or a plain diff, if the checkout isn't a git repo) shows only the `ctx.has_feature`
  entries added.
- [] `grep -n "has_feature" docs/SPEC.md` shows the Queries row, the Features bullet and the §3.10 sub-bullet.

---

## Phase 2: The fighting gate in `//lib:shop.star`

### Objective & Scope

- **In Scope:** the refusal in `_shop_here`; fixture commands that start and stop a fight with the test keeper; template
  tests with combat on; a Kotlin harness test showing the shop works with combat off.
- **Out of Scope:** spec §4.9, §7.4, the tutorial page, `DESIGN_NEEDED` (Phase 3); any restock change; any engine code.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/lib/shop.star`: `_shop_here` (the stun gate and its comment), `_catch_up`.
  - `template-world/zones/tutorial/shop_test.star`: `_shop_player`, `_chest`, `_contents`, `_coins`, `_carried_count`,
    and `test_stunned_keeper_does_not_trade` (the pattern to mirror), `test_partial_restock_after_buyout` (restock
    assertions).
  - `template-world/zones/tutorial/shop.star`: `_stun_keeper` and `_restock` (fixture command pattern), the
    `shop_test_room` `commands` dict.
  - `harness/src/test/kotlin/gups/harness/RunnerTest.kt`: `world(files, defaults)`, `run(files, patterns)`, and `` `the
    four w leveling verbs fail with the feature refusal when leveling is off` `` (a features-off world built from
    `TestWorlds.module`, `TestWorlds.RULES_PACKAGE` and `TestWorlds.rulesFor`).
  - `template-world/lib/package.star` (the `lib` package declaration to copy into the temp world).
- **Files to Modify / Create:**
  - `template-world/lib/shop.star`
  - `template-world/zones/tutorial/shop.star` (fixture commands only)
  - `template-world/zones/tutorial/shop_test.star`
  - A new `harness/src/test/kotlin/gups/harness/ShopWithoutCombatTest.kt` (or a new test in `RunnerTest.kt`, if a
    separate file would duplicate too much of its private helpers)
  - `docs/CHANGELOG.md`
- **Do Not Touch:** the engine's main code, `docs/SPEC.md`, the API files, other zones.

### Specification & Decision Record Changes

- None in `SPEC.md` this phase (Phase 3 updates §4.9).
- CHANGELOG, under `### Shopkeepers in combat`: "`//lib:shop.star`: a keeper that is fighting refuses every customer
  with \"<keeper> is busy fighting.\"; restocking goes on, and worlds without combat are unaffected."

### Implementation Blueprint

- **Signatures & Types:** no new public library symbols. The `shop` struct export is unchanged.

- **Algorithmic Steps & Invariants:**

  1. In `_shop_here`, right after the `keeper.has_flag("stunned")` block and before `_container_of`, add the code in
     "Gate code" below. Order matters: an incapacitated keeper still reads "The shop is closed." and a stunned one
     "...is in no state to trade.". The `has_feature` test must come first, because `keeper.fighting()` raises when
     combat is off. Because the gate returns before `_catch_up`, a refused command neither restocks nor changes
     anything.
  2. Update the comment above the stun gate so it covers both gates. For example: "A stun or a fight only stops trading:
     the keeper is still present, so restocking goes on." Don't touch `_keeper_of` or `_catch_up`. A fighting keeper
     still counts as present there, so container `on_open`/`on_wake` keep restocking.
  3. Extend the header comment at the top of `shop.star` with one line: "A keeper that is fighting, stunned or down
     doesn't trade."
  4. Fixture commands in `zones/tutorial/shop.star`, added to the `shop_test_room` `commands` dict:
     - `fight_keeper`: finds `//zones/tutorial:test_shopkeeper` in `ctx.room.actors()` (as `_stun_keeper` does) and
       calls `ctx.start_combat(ctx.actor, a)`, replying "You attack the keeper.".
     - `calm_keeper`: finds the keeper and calls `ctx.stop_combat(a)`, replying "The keeper calms down.".

     Each replies "The keeper is not here." when it's missing. Add a one-line comment above each, like `_stun_keeper`'s.

#### Gate code

Indent this to the level of `_shop_here`'s body, like the stun gate above it.

```python
if ctx.has_feature("combat") and keeper.fighting():
    ctx.send(ctx.self, "%s is busy fighting." % keeper.name)
    return None
```

### Test & Documentation Requirements

In `template-world/zones/tutorial/shop_test.star`, after `test_stunned_keeper_does_not_trade`:

- `test_fighting_keeper_refuses_everyone`: player Tess (the customer, with 100 coins and a `test_axe`, as in the stun
  test) and player Ash, both in `:shop_test_room`. Ash runs `fight_keeper`. Then for **both** Ash and Tess, `list`, `buy
  sword`, `sell axe` and `value axe` each contain "the test shopkeeper is busy fighting.". `list` sends no `Shop` GMCP
  to Tess. Tess's coins and carried counts are unchanged. Then Ash runs `calm_keeper`; Tess's `list` contains "offers"
  and `buy sword` contains "You buy 1 a test sword". Ash, the former attacker, can also `buy sword` (no memory of
  attackers). Keep the fight short: run no pulses between `fight_keeper` and the checks, so neither side takes
  meaningful damage.
- `test_fighting_keeper_keeps_restocking`: Tess runs `drain sword`, then `w.run(pulses = 600)` with no fight. Ash runs
  `fight_keeper`. Tess runs `restock` (the fixture command that calls `shop.catch_up` on the chest), and the chest now
  holds 1 `test_sword` while the keeper is still engaged. Then Ash runs `calm_keeper`.

New Kotlin harness test (`ShopWithoutCombatTest`, mirroring the RunnerTest leveling-off test):

- Build a temp world with `features = ["skills", "leveling"]` (combat and track off), `TestWorlds.RULES_PACKAGE`,
  `TestWorlds.rulesFor(listOf("skills", "leveling"))`, `lib/package.star` and `lib/shop.star` copied verbatim from the
  template world. Find the template world with `System.getProperty("gups.templateWorld") ?: "../template-world"`, as
  `FuzzWalkerTest` does. Add a small zone with a keeper whose `on_spawn` calls `shop.open` on a one-line shop (one
  stored item with `value`, `till = shop.till(count = 100)`), and a `_test.star` file.
  - `test_shop_trades_without_combat`: a player with coins runs `list` (contains "offers"), `buy <item>` (contains "You
    buy"), and `value <item>` on what they bought. Assert the run's single result passed and that no script error
    mentioning `needs the combat feature` appears.
- `TestWorlds.withDefaults` supplies only `MODULE.gups` and the rules package, so the test must write both
  `lib/package.star` and `lib/shop.star` itself.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including the template world's Starlark tests and the new harness
  test.
- [] `docs/api/api-1.json` and `cli/src/test/resources/api.golden.json` are unchanged by this phase.
- [] Temporarily removing the `ctx.has_feature("combat") and` guard makes `test_shop_trades_without_combat` fail with
  the combat refusal. Restore the guard afterwards.

---

## Phase 3: Spec text, tutorial and finalization

### Objective & Scope

- **In Scope:** the §4.9 paragraph and §7.4 row; the tutorial shops page; marking the `DESIGN_NEEDED` entry completed; a
  full verification run.
- **Out of Scope:** any code.

### Context Working Set

- **Read / Reference Only:** `docs/MILESTONE_24.md` Phase 5 (format of the §7.4 row).
- **Files to Modify / Create:** `docs/SPEC.md`, `template-world/docs/tutorials/08_shops.md`, `docs/DESIGN_NEEDED.md`.
- **Do Not Touch:** code, tests, API files; the `### Hostility and factions` and `### Crafting` entries.

### Specification & Decision Record Changes

Edit `docs/SPEC.md`:

1. **§4.9, the `//lib:shop.star` paragraph:** after "...restocked lazily over time while a keeper is present.", insert:
   "A keeper that is incapacitated, stunned or fighting doesn't trade: while it is engaged (`combat` feature) it refuses
   every customer, and its stock still restocks. The library keeps no memory of attackers; a world can refuse or reprice
   them from the keeper's `shop_price` hook."
2. **§7.4:** add this row after the M24 row:

```markdown
| **M25**   | Shopkeepers in combat: `ctx.has_feature`; a fighting keeper refuses every customer while restocking goes on, and shops work without the `combat` feature (§3.10, §4.5, §4.9). |
```

### Implementation Blueprint

1. `08_shops.md`, the "The keeper must be fit to trade" bullet: replace "A keeper that is fighting still trades." with
   "A keeper that is fighting answers every customer, its attacker included, with \"the shopkeeper is busy fighting.\"
   until the fight ends, and its stock keeps restocking meanwhile. The keeper bears no grudge afterwards. For one, have
   the keeper's `on_damage` hook record `ctx.actor` in an attribute and refuse that customer from `shop_price`."
   Optionally add one sentence after the bullet list: a library that must run in worlds with and without a feature
   checks `ctx.has_feature("combat")` before touching combat fields, as the shop does.
2. `DESIGN_NEEDED.md`: change `### Shopkeepers in combat` to `### Shopkeepers in combat (COMPLETED — M25)`. Do not
   delete or otherwise edit the entry.
3. Run `./gradlew markdownFormat`; regenerate the docs goldens if the template's docs output changed (guardrail 4), and
   read the diff.

### Test & Documentation Requirements

- No new tests. The full suite from Phases 1–2 must pass.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including `markdownCheck`, `ApiDiffTest` and the template world's
  tests.
- [] `grep -n "COMPLETED — M25" docs/DESIGN_NEEDED.md` shows the entry.
- [] `grep -n "fighting still trades" template-world/docs/tutorials/08_shops.md` returns nothing.
- [] End to end in `--dev`: two players at the tutorial shopkeeper. While one `kill`s the keeper, `list` from either
  answers "...is busy fighting."; after the attacker `flee`s, both can trade again.
