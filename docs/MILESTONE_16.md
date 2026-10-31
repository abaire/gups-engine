# Milestone 16: Item Quantities, Pluralization, and `all`

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md` — `### Quantities and all` (under `## Items`, lines 179–183).
- **Design Summary:**
  - **Pluralization & Stack Display (`plural`):** `item(...)` declarations gain an optional `plural` string
    (`ItemProto.plural: String? = null`), exposed on the Starlark item view (`item.plural`) and `PluginItem.plural`.
    Words from `plural` (minus articles `a`, `an`, `the`, `some`) are included in keyword matching (`Item.matches` and
    default `keywords`) so players can target items by singular or plural words in any language without hardcoded
    English `-s` stripping. `Item.name` stays the singular name (`nameOverride ?: proto.name`), while
    `Item.describe(count: Int = this.count): String` formats quantities for player output: `item.name` when `count ==
    1`, `"$count ${proto.plural}"` when `count > 1 && nameOverride == null && proto.plural != null`, and `"${item.name}
    ($count)"` otherwise.
  - **Tag-Based `all` Exclusion (`no_all`):** Bare `all` (`get all`, `get all [from] <container>`, `drop all`, `put all
    [in|into] <container>`, `give all [to] <target>`) skips any item with `"no_all" in item.tags`. `Prototypes.CORPSE`
    carries `tags = setOf("no_all")`, and keyring-only items (`ItemProto.isKeyOnly`) automatically receive `"no_all"` in
    their tags. `put` also excludes the target container itself (`item !== box`). Explicit keyword filters (`all
    <keyword>`, `<count> <keyword>`, `<keyword>`) ignore `no_all`.
  - **No In-Place Stack Splitting (`split` removed; `count` on `move_item`, `give`, `destroy`):** `World.splitItem` and
    `ctx.split` are removed completely. Instead, `World.moveItem(item, to, count = item.count): Item`,
    `World.destroy(item, count = item.count)`, `ctx.move_item(item, to, index = 0, count = None)`, `ctx.give(actor,
    label_or_item, count = None)`, and `ctx.destroy(item, count = None)` directly accept a `count` argument to move or
    destroy part of a stack in a single step without leaving unmerged twin stacks at the source.
  - **Quantities, `all`, and Optional Prepositions in Commands:** `get <what> [[from] <container>]`, `drop <what>`,
    `give <what> [to] <target>`, and `put <what> [in|into] <container>` all resolve items at tick-lane execution time
    (`validate` / `resolve`) and support `<keyword>`, `<ordinal>.<keyword>`, `<count> <keyword>`, `<count>
    <ordinal>.<keyword>`, `all`, and `all <keyword>` (with a space, no `all.<keyword>` dot syntax).
  - **Capping & Single-Check Per Stack:** Requested counts cap to what is available in the source (when at least 1
    matching item exists) and to what fits within the destination's remaining weight/capacity (`carry_limit` for
    `get`/`give`, container `capacity` and `maxWeight` for `put`). Each stack `Item` moved runs a single `before_*` veto
    check, `moveItem(item, to, moveCount)`, message, `on_*` hook, and `stack(moved)` merge. When a transfer is capped by
    weight or capacity after moving at least 1 unit (or when 0 units can move), the existing full message (`CANT_CARRY`,
    `GIVE_FULL`, `PUT_FULL`, or `PUT_WONT_FIT`) is sent.
- **Specification Impact:** `docs/SPEC.md` `§3.3` (Item), `§4.2` (Content Declarations: `item`), `§4.5` (Item view and
  Item verbs), `§4.9` (Commands: `inventory`, `keyring`, `look`, `get`, `drop`, `give`, `put`), `§4.15` (PluginItem),
  `§7.4` (M16 row).
- **Decision Record Updates:** None (all prior `D-xxx` entries in `docs/DECISIONS.md` are already ratified and folded
  into `docs/SPEC.md`).

---

## Standing Rules for All Phases

1. **Read `AGENTS.md` first.** Run every `./gradlew` command with `BypassSandbox: true` so the Gradle client can connect
   to the local daemon.
2. **No spec or decision citations in code.** KDoc and comments in `.kt` files must never contain `§`, `SPEC`, or
   decision IDs such as `D-005`. State behavior directly.
3. **Every phase must end green.** Do not proceed to the next phase until all exit conditions for the current phase
   pass.
4. **Markdown formatting.** Whenever any `.md` file is modified, run `./gradlew markdownFormat` and verify `./gradlew
   markdownCheck`.

---

## Phase 1: Item `plural` Property, Plural Keyword Matching, `no_all` Tag, and Stack Display

### Objective & Scope

- **In Scope:**
  - Add `plural: String? = null` to `ItemProto`, `item(...)` in Starlark declarations, `ItemView.plural`, and
    `PluginItem.plural`.
  - Include words from `plural` (minus articles) in default `keywords` and in `Item.matches(keyword)`.
  - Add `"no_all"` tag to `Prototypes.CORPSE` and automatically include `"no_all"` in `ItemProto.tags` when an item is
    keyring-only (`isKeyOnly`).
  - Add `Item.describe(count: Int = this.count): String` and update `look` (room ground and open containers),
    `inventory`, and `keyring` to display stack quantities with `describe()`.
  - Update API model golden/baseline files, `docs/CHANGELOG.md`, and `docs/SPEC.md` (`§3.3`, `§4.2`, `§4.5`, `§4.9`,
    `§4.15`).
- **Out of Scope:**
  - `World.moveItem` / `World.destroy` partial counts and `ctx.split` removal (Phase 2).
  - Command argument parsing for `<count>` and `all` in `get`, `drop`, `give`, and `put` (Phases 3–4).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Messages.kt` (message placeholder capitalization rules in `Messages.fill`)
  - `script/src/main/kotlin/gups/script/ApiModel.kt`
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Protos.kt`
  - `core/src/main/kotlin/gups/core/model/Entities.kt`
  - `core/src/main/kotlin/gups/core/world/Reconcile.kt`
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt`
  - `script/src/main/kotlin/gups/script/Declarations.kt`
  - `script/src/main/kotlin/gups/script/Views.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt`
  - `core/src/test/kotlin/gups/core/world/ItemCommandsTest.kt`
  - `script/src/test/kotlin/gups/script/LoaderTest.kt`
  - `cli/src/test/resources/api.golden.json`
  - `docs/api/api-1.json`
  - `docs/CHANGELOG.md`
  - `docs/SPEC.md`
- **Do Not Touch:**
  - `core/src/main/kotlin/gups/core/world/Items.kt`
  - `core/src/main/kotlin/gups/core/world/Verbs.kt`
  - `script/src/main/kotlin/gups/script/Ctx.kt`

### Specification & Decision Record Changes

Update `docs/SPEC.md`: - **`§3.3` (Item):** Under `Quantities and decay`, note that `ItemProto` may declare an optional
`plural` display name used by `Item.describe` when formatting stacks of `count > 1`, and that items carrying the
`no_all` tag (including `//engine:corpse` and keyring-only keys) are skipped by bare `all` item commands. - **`§4.2`
(`item` declaration example and `Declaration rules`):** - Add `plural = None` to the `item(...)` example or `Defaults`
bullet: - Under `Defaults`: `"The display name defaults to the identifier with _ as spaces. An item may also give plural
(for example plural = "gold coins"), used when formatting stacks of 2 or more. keywords default to the display name's
(and plural's) words minus articles, or else the identifier; when keywords are given explicitly on an item, words from
plural minus articles are also matched."` - Under `Tags`: Note that `no_all` excludes an item from bare `all` in `get`,
`drop`, `give` and `put`, and is added automatically to `//engine:corpse` and keyring-only keys. - **`§4.5` (`Item
view`):** Add `plural` after `name`. - **`§4.9` (`keyring` and `look` / `inventory`):** Note that `inventory`,
`keyring`, and `look` format stacks of `count > 1` as `"<count> <plural>"` when `plural` is set on the prototype (and
the item has no `nameOverride`), or `"<name> (<count>)"` otherwise.

### Implementation Blueprint

- **Signatures & Types:**
  - In `core/src/main/kotlin/gups/core/model/Protos.kt`:

    ```kotlin
    data class ItemProto(
    val label: Label,
    val name: String,
    val keywords: List<String>,
    val desc: String = "",
    val kind: String = "misc",
    val range: Band = Band.MELEE,
    val weight: Int = 1,
    val wear: String? = null,
    val dice: Dice? = null,
    val damageType: DamageType? = null,
    val statDeltas: Map<String, Int> = emptyMap(),
    val senseDeltas: Map<Channel, Int> = emptyMap(),
    val tags: Set<String> = emptySet(),
    val stackable: Boolean = false,
    val decayPulses: Int? = null,
    val container: ContainerProto? = null,
    val commands: Map<String, ZoneCommand> = emptyMap(),
    val equipAffects: List<EquipAffect> = emptyList(),
    val modifiers: Modifiers = emptyMap(),
    val version: Int = 1,
    /** Optional plural display name used when formatting quantities greater than 1. */
    val plural: String? = null,
    )
    ```
  - In `Prototypes.CORPSE` (`Protos.kt` line 392): add `tags = setOf("no_all")`.
  - In `ItemProto`: ensure keyring-only items (`kind == "key"` with no `wear`, `container`, `commands`, `dice`,
    `statDeltas`, `senseDeltas`, `equipAffects`, or `modifiers`) include `"no_all"` in `tags` (either via an `init`
    block or computed backing property on `ItemProto`, e.g., in `ItemProto` constructor or `Declarations.kt` +
    `ItemProto` `isKeyOnly` helper moved to `ItemProto`). Moving `val isKeyOnly: Boolean` onto `ItemProto` in
    `Protos.kt` and normalizing `val tags: Set<String> = if (isKeyOnly) (tags + "no_all").toSortedSet() else tags`
    ensures both Kotlin fixture `ItemProto`s and Starlark `item()` declarations automatically carry `"no_all"` when
    `isKeyOnly` is true!
  - In `core/src/main/kotlin/gups/core/model/Entities.kt` on `Item`:

    ```kotlin
    /**
     * Display description of this item for a given [amount] (defaulting to [count]): [name] when
     * [amount] is 1, `"$amount ${proto.plural}"` when [amount] > 1 and [nameOverride] is null and
     * [ItemProto.plural] is set, or `"$name ($amount)"` otherwise.
    */
    fun describe(amount: Int = count): String =
    when {
    amount <= 1 -> name
    nameOverride == null && proto.plural != null -> "$amount ${proto.plural}"
    else -> "$name ($amount)"
    }
    ```

    And update `Item.matches(keyword: String): Boolean`:

    ```kotlin
    fun matches(keyword: String): Boolean {
    val k = keyword.lowercase()
    if (keywords.any { it.lowercase().startsWith(k) }) return true
    val p = proto.plural ?: return false
    return p.lowercase().split(WORD_SPLIT).any { it.isNotEmpty() && it !in ARTICLES && it.startsWith(k) }
    }
    ```

    (where `WORD_SPLIT = Regex("[^a-z0-9]+")` and `ARTICLES = setOf("a", "an", "the", "some")`).

  - In `core/src/main/kotlin/gups/core/world/Reconcile.kt`: update `tombstone(was: ItemProto)` to copy `plural =
    was.plural` and `tags = was.tags`.

  - In `core/src/main/kotlin/gups/core/world/KernelCommands.kt`:

    - In `look` (line 255, container contents): replace `inside.joinToString(", ") { it.name }` with
      `inside.joinToString(", ") { it.describe() }`.
    - In `inventory` (line 386) and `keyring` (line 400): replace `"item" to it.name` with `"item" to it.describe()`.
    - In `describeRoom` (lines 1279 and 1312): replace `it.name` with `it.describe()`.
    - In `drop`, `give`, `put`, and `GetAction` resolve messages: pass `item.describe()` for the `"item"` placeholder
      instead of `item.name` (note: obtain `val desc = item.describe()` *before* calling `w.stack(item)` in `resolve`,
      because `w.stack(item)` merges `item` into an existing stack at the destination and mutates `into.count`!).
  - In `script/src/main/kotlin/gups/script/Declarations.kt`:
    - Add `DeclParam("plural", "str", "Plural display name shown for stacks of 2 or more, such as 'gold coins'.",
      default = "None")` to `ITEM_SPEC` right after `"name"`.
    - In `DeclarationBuiltins.item`: read `val plural = d.str("plural")`, pass `plural = plural` to `ItemProto`, and
      when `d["keywords"] == null`, merge `(defaultKeywords(name, id) + (plural?.let { defaultKeywords(it, id) } ?:
      emptyList())).distinct()`.
  - In `script/src/main/kotlin/gups/script/Views.kt` on `ItemView`:

    ```kotlin
    @StarlarkMethod(
    name = "plural",
    structField = true,
    allowReturnNones = true,
    doc = "Plural display name for stacks of 2 or more, or None.",
    )
    fun plural(): Any? = read { item.proto.plural }
    ```
  - In `plugin-api/src/main/kotlin/gups/pluginapi/Facade.kt` on `PluginItem` and
    `plugin-api/src/main/kotlin/gups/pluginapi/host/Facade.kt` on `ItemFacade`:
    - Add `val plural: String?` returning `item.proto.plural`.

### Test & Documentation Requirements

- In `core/src/test/kotlin/gups/core/world/ItemCommandsTest.kt`:
  - Update `coin` in `ItemCommandsTest` (or add a pluralized stackable item alongside an un-pluralized one) to verify:
    - `Item.describe()` formats `count == 1` as `"a gold coin"`, `count > 1` with `plural = "gold coins"` as `"5 gold
      coins"`, and `count > 1` without `plural` (or with `nameOverride`) as `"a gold coin (5)"`.
    - `Item.matches("coins")` matches an item with `name = "a gold coin", plural = "gold coins"`.
    - `inventory` and `look` display `"5 gold coins"` (or `"a gold coin (5)"`) when `count > 1`.
    - `Prototypes.CORPSE` and `bareKey` (`isKeyOnly`) both have `"no_all" in item.tags`.
- In `script/src/test/kotlin/gups/script/LoaderTest.kt`:
  - Test declaring `item("loaf", name = "a loaf of bread", plural = "loaves of bread", stackable = True)` and reading
    `item.plural` and `item.keywords`.
- Regenerate API golden and baseline files:
  - `./gradlew :cli:test -Dgups.updateGolden=true`
  - `./gradlew :cli:installDist && cli/build/install/gups/bin/gups docs --update-baseline`
- Add a section `### Item quantities, pluralization, and all` under `## api_version 1 (unreleased)` in
  `docs/CHANGELOG.md` documenting `item(plural=...)`, `item.plural`, `PluginItem.plural`, stack display formatting, and
  the `no_all` tag.

### Exit Conditions

- [] `./gradlew markdownFormat && ./gradlew check` (with `BypassSandbox: true`) exits `0` with zero warnings.
- [] `grep -rnE "§|\bD-[0-9]{3}\b|(//|\*).*\bSPEC\b" */src/main/` returns `0` matches.
- [] `ApiDiffTest`, `LoaderTest`, and `ItemCommandsTest` pass.

---

## Phase 2: Replace `split` / `splitItem` with `count` on `moveItem`, `give`, and `destroy`

### Objective & Scope

- **In Scope:**
  - Remove `World.splitItem` from `core/src/main/kotlin/gups/core/world/Verbs.kt` and `ctx.split` from
    `script/src/main/kotlin/gups/script/Ctx.kt`.
  - Add `count: Int = item.count` to `World.moveItem(item: Item, to: ItemLocation, count: Int = item.count): Item` and
    `World.destroy(item: Item, count: Int = item.count)`.
  - Add optional `count` to `ctx.move_item(item, to, index = 0, count = None)` and `ctx.destroy(item, count = None)`,
    and support `count` when passing an `ItemView` to `ctx.give(actor, label_or_item, count = None)`.
  - Update `HostTest.kt`, `docs/SPEC.md` (`§4.5`), `docs/CHANGELOG.md`, and the API golden/baseline files.
- **Out of Scope:**
  - Player command argument parsing (`get`, `drop`, `give`, `put`), which is in Phases 3 and 4.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Invariants.kt`
  - `persist/src/test/kotlin/gups/persist/PersistenceTest.kt`
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Items.kt`
  - `core/src/main/kotlin/gups/core/world/Verbs.kt`
  - `script/src/main/kotlin/gups/script/Ctx.kt`
  - `script/src/test/kotlin/gups/script/HostTest.kt`
  - `core/src/test/kotlin/gups/core/world/ItemCommandsTest.kt`
  - `cli/src/test/resources/api.golden.json`
  - `docs/api/api-1.json`
  - `docs/CHANGELOG.md`
  - `docs/SPEC.md`
- **Do Not Touch:**
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt`

### Specification & Decision Record Changes

Update `docs/SPEC.md` `§4.5`: - In the **Verbs (v0)** table (`Items` row): - Replace `spawn(label, at, index=0,
count=1)`, `give(actor, label_or_item, count=1)`, `move_item(item, to)`, `destroy(item)`, `split(item, n)` with:
`spawn(label, at, index=0, count=1)`, `give(actor, label_or_item, count=None)`, `move_item(item, to, index=0,
count=None)`, `destroy(item, count=None)` - In **Verb semantics** under `Items`: - Update `give`: `"give(actor,
label_or_item, count=None) spawns count of a label (default 1) or moves count of an item (default the whole stack) into
the inventory, returns the item view (or list of views when spawning multiple non-stackable items), and fires no
hooks."` - Update `move_item`: `"move_item(item, to, index=0, count=None) moves the item (or count of a stack, copying
its attributes, name override, debug flag and decay deadline to the moved stack) and returns the moved item view. Moving
part of an equipped item or a non-stackable item is an error."` - Update `destroy`: `"destroy(item, count=None) removes
the item and everything inside it, or removes count from a stack when count is less than the stack's count."` - Remove
the `split` bullet and remove `"split,"` from the paragraph `"Verbs without a player command..."`.

### Implementation Blueprint

- **Signatures & Types:**
  - In `core/src/main/kotlin/gups/core/world/Items.kt`:

    ```kotlin
    /**
     * The `move_item` verb: moves [count] of [item] (by default the whole stack) to [to] and
     * returns the [Item] now at [to]. When [count] is less than [Item.count], [item] must be a
     * stackable, unequipped item; [count] is subtracted from [item] and placed at [to] as a new
     * [Item] with the same custom attributes, name override, debug flag, and decay deadline.
    */
    fun World.moveItem(item: Item, to: ItemLocation, count: Int = item.count): Item
    ```

    and

    ```kotlin
    /**
     * The `destroy` verb: removes [count] of [item] (by default the whole stack). When [count]
     * equals [Item.count], removes [item] and everything inside it; when [count] is smaller,
     * subtracts [count] from [Item.count] and updates encumbrance.
    */
    fun World.destroy(item: Item, count: Int = item.count)
    ```
- **Algorithmic Steps & Invariants:**
  1. **`World.moveItem(item, to, count)`:**
     - Validate `require(item.id in entities) { "$item is not in the world" }` and `require(count in 1..item.count) {
       "move_item: count must be between 1 and ${item.count}, got $count" }`.
     - If `count < item.count`:
       - `require(item.proto.stackable) { "${item.name} is not stackable" }`
       - `require(item.location !is ItemLocation.Equipped) { "move_item: can't move part of an equipped item" }`
       - `checkTarget(item, to)`
       - Create `val part = Item(allocateId(), item.proto, to, count)` with `part.debug = item.debug`,
         `part.nameOverride = item.nameOverride`, `part.decayAt = item.decayAt`,
         `part.customAttributes.putAll(item.customAttributes)`, and `part.dirty = true`.
       - Decrement `item.count -= count` and set `item.dirty = true`.
       - `register(part)` and `index(part, to)`.
       - Custody audit trail: let `val from = item.location`, `val before = custodian(item) as? Player`, `val after =
         custodian(part) as? Player`. If `(before != null || after != null) && !part.debug`:
         - Record `ItemEvent(part.id, pulse, from, to, "move")`.
         - If `before != null && after != null && before !== after`, call `requestImmediate("transfer")`.
       - Update encumbrance: `holderOf(from)?.let { updateEncumbrance(it) }` and `custodian(part)?.let {
         updateEncumbrance(it) }`.
       - Return `part`.
     - If `count == item.count`:
       - Run the existing whole-item move logic and return `item`.
  2. **`World.destroy(item, count)`:**
     - Validate `require(item.id in entities) { "$item is not in the world" }` and `require(count in 1..item.count) {
       "destroy: count must be between 1 and ${item.count}, got $count" }`.
     - If `count < item.count`:
       - `item.count -= count`
       - `item.dirty = true`
       - `custodian(item)?.let { updateEncumbrance(it) }`
       - Return.
     - If `count == item.count`: run the existing full-item removal logic.
  3. **In `script/src/main/kotlin/gups/script/Ctx.kt`:**
     - Delete `fun split(...)` and the `import gups.core.world.splitItem`.
     - Update `give(actor, labelOrItem, count, thread)`:
       - Change `count` parameter `defaultValue = "None"` and doc to `"Number of items to spawn or move; defaults to 1
         for a label or the whole stack for an item."`.
       - When `labelOrItem is ItemView`:
         - `val i = itemArg(labelOrItem, "give(item)")`
         - `val n = if (isNone(count)) i.count else int(count, "give(count)")`
         - `if (n !in 1..i.count) fail("give(count): must be between 1 and ${i.count}")`
         - `return@verb v(world.moveItem(i, to, n))`
       - When `labelOrItem` is a label string:
         - `val n = if (isNone(count)) 1 else int(count, "give(count)")` (rest unchanged).
     - Update `moveItem(item, to, index, count, thread)`:
       - Add `Param(name = "count", named = true, defaultValue = "None", doc = "Number of items from the stack to move,
         or None for the whole stack.")`.
       - `val i = itemArg(item, "move_item(item)")`
       - `val n = if (isNone(count)) i.count else int(count, "move_item(count)")`
       - `if (n !in 1..i.count) fail("move_item(count): must be between 1 and ${i.count}")`
       - Return `v(world.moveItem(i, location(to, index, "move_item(to)", thread), n))`.
     - Update `destroy(item, count)`:
       - Add `Param(name = "count", named = true, defaultValue = "None", doc = "Number of items from the stack to
         remove, or None for the whole stack.")`.
       - `val i = itemArg(item, "destroy(item)")`
       - `val n = if (isNone(count)) i.count else int(count, "destroy(count)")`
       - `if (n !in 1..i.count) fail("destroy(count): must be between 1 and ${i.count}")`
       - Call `world.destroy(i, n)` and return `Starlark.NONE`.

### Test & Documentation Requirements

- In `script/src/test/kotlin/gups/script/HostTest.kt`:
  - Update `item, door and query verbs` (line 413) to use `ctx.move_item(coins, ctx.room, index = 0, count = 2)` instead
    of `ctx.split`, and also test `ctx.give(actor, item, count = ...)` and `ctx.destroy(item, count = ...)`.
  - Add error tests verifying out-of-range `count` (`0` or `> item.count`) on `move_item`, `give`, and `destroy` raises
    a script error.
- Regenerate API golden and baseline files (`./gradlew :cli:test -Dgups.updateGolden=true` and `gups docs
  --update-baseline`) and update `docs/CHANGELOG.md`.

### Exit Conditions

- [] `./gradlew markdownFormat && ./gradlew check` (with `BypassSandbox: true`) exits `0` with zero warnings.
- [] `grep -rn "splitItem" */src/` returns `0` matches.
- [] `grep -rnE "§|\bD-[0-9]{3}\b|(//|\*).*\bSPEC\b" */src/main/` returns `0` matches.

---

## Phase 3: Item Specifier Parsing, Optional Prepositions, and `get` / `drop` with Quantities and `all`

### Objective & Scope

- **In Scope:**
  - Implement shared item target parsing (`ItemSpec`) and pool matching in
    `core/src/main/kotlin/gups/core/world/KernelCommands.kt` supporting:
    - `<keyword>` and `<ordinal>.<keyword>`
    - `<count> <keyword>` and `<count> <ordinal>.<keyword>`
    - `all` and `all <keyword>` (with `"no_all" in item.tags` excluded for bare `all`).
  - Support optional `from` in `get <what> [[from] <container>]` with ground-item vs. container disambiguation.
  - Refactor `GetAction` and `planDrop` (`DropAction`) to resolve items at tick-lane execution time (`validate` /
    `resolve`), collect across multiple stacks/items, cap to available and carry limits, run one `before_*` / `moveItem`
    / `on_*` / `stack` cycle per moved stack, and emit `KernelMsg.CANT_CARRY` when capped by carry limit.
  - Add any needed empty-pool messages (`GET_NOTHING`, `GET_EMPTY`, `DROP_NOTHING`) to `KernelMessages.kt` and
    `docs/SPEC.md` (`§4.18`).
- **Out of Scope:**
  - `give` and `put` commands (Phase 4).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Items.kt`
  - `core/src/main/kotlin/gups/core/world/Messages.kt`
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/KernelMessages.kt`
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt`
  - `core/src/test/kotlin/gups/core/world/ItemCommandsTest.kt`
  - `cli/src/test/resources/api.golden.json`
  - `docs/api/api-1.json`
  - `docs/CHANGELOG.md`
  - `docs/SPEC.md`
- **Do Not Touch:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`

### Specification & Decision Record Changes

Update `docs/SPEC.md`: - **`§4.9` (`Item and combat command details`):** - Document the `<what>` syntax shared by `get`,
`drop`, `give`, and `put`: `<keyword>`, `<ordinal>.<keyword>`, `<count> <keyword>`, `<count> <ordinal>.<keyword>`,
`all`, and `all <keyword>`. - Document that `from` in `get <what> [[from] <container>]` is optional, that bare `all`
skips items tagged `no_all` (including corpses and keyring-only keys), and that `<count>` caps to what is available and
what the actor can carry (sending `"You can't carry that much."` when capped by `carry_limit`), evaluating `before_*`,
`moveItem`, `on_*`, and stack merging once per moved stack. - **`§4.18` (Engine Messages):** If any new message IDs are
added to `KernelMsg` (or if existing `DONT_SEE` / `NOT_CARRYING` are used), keep `§4.18` and the API files in sync.
*(Recommendation: reuse existing `KernelMsg.DONT_SEE` when `get` finds nothing on the ground or in a container, and
`KernelMsg.NOT_CARRYING` / `KernelMsg.INVENTORY_EMPTY` when `drop` finds nothing in inventory, so no new message IDs are
needed unless helpful!)*

### Implementation Blueprint

- **Signatures & Types (`KernelCommands.kt`):**

  ```kotlin
  /**
   * Parsed `<what>` item specifier for `get`, `drop`, `give`, and `put`.
   *
   * - [all] is true for `all` and `all <keyword>` ([keyword] is null for bare `all`).
   * - [count] is the explicit requested unit count (`>= 1`), or null when none was given (meaning
   *   one matched [Item] entity, or all matching entities when [all] is true).
   * - [ordinal] is the 1-based match index (`2.sword` -> 2, default 1).
   * - [keyword] is the target keyword phrase, or null for bare `all`.
   */
  internal data class ItemSpec(
    val all: Boolean,
    val count: Int?,
    val ordinal: Int,
    val keyword: String?,
  )
  ```
- **Algorithmic Steps & Invariants:**
  1. **Parsing `ItemSpec` (`parseItemSpec(raw: String): ItemSpec?`):**
     - Trim `raw`. If empty, return `null`.
     - If `raw.equals("all", ignoreCase = true)`, return `ItemSpec(all = true, count = null, ordinal = 1, keyword =
       null)`.
     - Split first token `first` and remainder `rest = splitFirst(raw)`:
       - If `first.equals("all", ignoreCase = true)` and `rest.isNotEmpty()`:
         - Return `ItemSpec(all = true, count = null, ordinal = 1, keyword = rest.lowercase())`.
       - If `first.toIntOrNull()` is an integer `n`:
         - If `n < 1` or `rest.isEmpty()`, return `null` (invalid count syntax).
         - Parse `(ord, word) = ordinal(rest)`. If `word.isEmpty()`, return `null`.
         - Return `ItemSpec(all = false, count = n, ordinal = ord, keyword = word)`.
       - Otherwise:
         - Parse `(ord, word) = ordinal(raw)`. If `word.isEmpty()` or `ord < 1`, return `null`.
         - Return `ItemSpec(all = false, count = null, ordinal = ord, keyword = word)`.
  2. **Matching Candidates (`matchItems(pool: List<Item>, spec: ItemSpec, exclude: Item? = null): List<Pair<Item,
     Int>>`):**
     - Filter `pool` by `it !== exclude`:
       - If `spec.all && spec.keyword == null`: keep `it` where `"no_all" !in it.tags`.
       - Else: keep `it` where `it.matches(spec.keyword!!)`.
     - Apply `drop(spec.ordinal - 1)`.
     - If `spec.all`: return each matched item paired with `it.count`.
     - If `spec.count == null`: take the first matched item (if any) paired with `it.count` (moves the single `Item` or
       whole stack).
     - If `spec.count != null`: iterate through matched items accumulating `take = minOf(remaining, it.count)` until
       `remaining == 0` or the list ends, returning `List<Pair<Item, Int>>`.
  3. **Parsing `get <what> [[from] <container>]` (`planGet` and `GetAction`):**
     - In `planGet(c: CommandCall)`:
       - If `c.args.isBlank()`, tell `KernelMsg.GET_WHAT` and return `null`.
       - Update `get` help string usage to `"Usage: get <item|count item|all [item]> [[from] <container>]"`.
       - Pass `c.args.trim()` to `GetAction(rawArgs = c.args.trim())` so container and item resolution happen at
         execution time (`validate` / `resolve`)! (Keep the secondary constructor `GetAction(what: String, container:
         Item?, fixed: Item? = null)` for `scavenge()` in `Behaviors.kt`, which passes `fixed = item`).
     - How `GetAction` parses `rawArgs` against `actor` and `room`:
       - Check if `rawArgs` contains `\s+from\s+` (case-insensitive):
         - Split into `whatStr` and `fromStr`. Both must be non-empty (`parseItemSpec(whatStr)` non-null), else return
           `w.msg(KernelMsg.GET_WHAT)`. `container` is `w.findItem(actor, fromStr, inventory = true, ground = true) ?:
           return w.msg(KernelMsg.DONT_SEE)`.
       - If `rawArgs` has no `\s+from\s+`:
         - Can `rawArgs` have a trailing `<container>` word without `from`?
         - Split off the last whitespace-delimited token: `beforeLast` and `lastWord`.
         - If `beforeLast.isNotEmpty()`:
           - Check if `lastWord` matches a container in inventory or on the ground (`val candidateBox =
             w.findItem(actor, lastWord, inventory = true, ground = true)?.takeIf { it.container != null }`) AND
             `parseItemSpec(beforeLast)` is non-null.
           - When does ground item win over `candidateBox`?
             - If `beforeLast.equals("all", ignoreCase = true)` and `candidateBox != null`: `"get all chest"` / `"get
               all corpse"` means `what = all`, `container = candidateBox`!
             - Otherwise, if `candidateBox != null`: check if `parseItemSpec(rawArgs)` matches any item directly on the
               ground in `room.items`. If it does NOT match any ground item, treat `candidateBox` as the container and
               `parseItemSpec(beforeLast)` as `spec`!
         - If not resolved as `<what> <container>`, parse `spec = parseItemSpec(rawArgs) ?: return
           w.msg(KernelMsg.GET_WHAT)` with `container = null`.
  4. **Validating and Resolving `GetAction`:**
     - In `validate(w, actor)`:
       - Determine `sourcePool`: if `fixed != null`, `listOf(fixed)` (if in room and present); else if `container ==
         null`, `room.items`; else validate `container` is present, has `container != null` (`NOT_CONTAINER`), is `OPEN`
         (`PUT_CLOSED`), and reachable if on ground (`w.reach(actor, container)`), then `container.contents`.
       - Compute `matches = matchItems(sourcePool, spec)`. If `matches.isEmpty()`, return `w.msg(KernelMsg.DONT_SEE)`.
       - Check reach and carry limit across `matches`:
         - If none of the matched items can be reached (when `Feature.TRACK in w.features`), return
           `w.msg(KernelMsg.CANT_REACH, "item" to matches.first().first.name)`.
         - If the first reachable item cannot fit even 1 unit (`unitWeight(item) > remainingCarry(w, actor)` where
           `remainingCarry = w.rules.carryLimit(RulesContext(w, actor, item = item)) - actor.carriedWeight()`), and no
           subsequent matched item in an `all` command can fit 1 unit, return `w.msg(KernelMsg.CANT_CARRY)`.
     - In `resolve(w, actor)`:
       - Re-evaluate `matches` (or iterate the matched items still in the source location).
       - Track `var maxLag = 0` and `var cappedByWeight = false`.
       - For each `(item, wantedCount)` in `matches`:
         - Verify `item` is still at the expected source location (`InRoom` or `Contained(container.id)`).
         - If `Feature.TRACK in w.features`:
           - Compute `band` (for `container ?: item`). Ask `w.rules.getLag(RulesContext(w, actor, item = item, band =
             band), band)`.
           - If `lag == null`: tell `actor` `w.msg(KernelMsg.CANT_REACH, "item" to item.name)` and continue.
         - Compute how many units of `item` fit in `actor`'s remaining carry limit:
           - `val limit = w.rules.carryLimit(RulesContext(w, actor, item = item))`
           - `val remWeight = limit - actor.carriedWeight()`
           - For a stackable item (`item.proto.stackable`, which never has container contents):
             - `val fitCount = if (item.proto.weight <= 0) wantedCount else (remWeight /
               item.proto.weight).coerceAtLeast(0)`
             - If `fitCount <= 0`: `cappedByWeight = true`; continue (or break if not `spec.all`).
             - If `fitCount < wantedCount`: `cappedByWeight = true`.
             - `val actualCount = minOf(wantedCount, fitCount)`
           - For a non-stackable item (`actualCount = 1`):
             - If `item.weight > remWeight`: `cappedByWeight = true`; continue.
         - Check `val veto = w.dispatch(HookName.BEFORE_GET, item, actor.room, HookContext(actor = actor, item = item))`
           - If `veto != null`: `w.send(actor, veto)`; continue.
         - If `Feature.TRACK in w.features`: `maxLag = maxOf(maxLag, itemLag)`.
         - Move `val moved = w.moveItem(item, ItemLocation.Carried(actor.id), actualCount)`.
         - Format `val desc = moved.describe()` (before `w.stack(moved)`!).
         - Send `GET_SELF` / `GET_SELF_FROM` and `GET_ROOM` using `"item" to desc`.
         - Dispatch `ON_GET` with `moved`.
         - If `moved.id in w.entities`, call `w.stack(moved)`.
       - After the loop, if `cappedByWeight` is true, `w.tell(actor, KernelMsg.CANT_CARRY)`.
       - Return `maxLag`.
  5. **Refactoring `planDrop` (`DropAction`):**
     - Update `drop` help string usage to `"Usage: drop <item|count item|all [item]>"`.
     - In `planDrop(c: CommandCall)`: parse `val spec = parseItemSpec(c.args) ?: return null.also {
       c.world.tell(c.actor, KernelMsg.NOT_CARRYING) }` (or if `c.args.isBlank()`, tell `"Drop what?"` or `NOT_CARRYING`
       — check existing tests: if any test calls `drop` with an unknown item, it expects `NOT_CARRYING`).
     - In `DropAction.validate(w, actor)`:
       - `val matches = matchItems(actor.inventory, spec)`
       - If `matches.isEmpty()`, return `w.msg(KernelMsg.NOT_CARRYING)`.
       - Return `null`.
     - In `DropAction.resolve(w, actor)`:
       - For each `(item, count)` in `matchItems(actor.inventory, spec)`:
         - If `item.location != ItemLocation.Carried(actor.id)` continue.
         - Check `BEFORE_DROP` on `item`: if vetoed (`veto != null`), `w.send(actor, veto)` and continue.
         - `val moved = w.moveItem(item, ItemLocation.InRoom(actor.room.id, actor.trackIndex), count)`
         - `val desc = moved.describe()`
         - Tell `DROP_SELF` and `DROP_ROOM` with `"item" to desc`.
         - Dispatch `ON_DROP` with `moved`.
         - If `moved.id in w.entities`, call `w.stack(moved)`.
       - Return `0`.

### Test & Documentation Requirements

- In `core/src/test/kotlin/gups/core/world/ItemCommandsTest.kt`, add tests covering:
  - `get 5 coins` and `drop 3 coins` splitting a stack and merging at the destination.
  - Collecting `<count>` across multiple stacks (e.g., two stacks of coins with different custom attributes, `drop 6
    coins` drops the first stack of 4 and 2 from the second stack).
  - Collecting `<count>` across multiple non-stackable items (`get 2 sword`, `drop 2 ring`).
  - `get 50000 coins` and `drop 50000 coins` when only 10 exist caps to the 10 available.
  - `get 50000 coins` when the actor can only carry 10 more weight units picks up 10 coins in a single
    `before_get`/`on_get` check and prints `"You get 10 gold coins."` followed by `"You can't carry that much."`.
  - `get all`, `get all coin`, `drop all`, `drop all coin`.
  - `get all` skips corpses and `drop all` skips keyring-only keys (`no_all` tag), while `get all corpse` and `drop all
    iron` include them.
  - Optional `from` in `get`: `get coin chest`, `get 5 coins chest`, `get all chest`, and disambiguating `get gold coin`
    (ground item) vs `get coin chest` (container).

### Exit Conditions

- [] `./gradlew markdownFormat && ./gradlew check` (with `BypassSandbox: true`) exits `0` with zero warnings.
- [] `grep -rnE "§|\bD-[0-9]{3}\b|(//|\*).*\bSPEC\b" */src/main/` returns `0` matches.
- [] All new and existing `ItemCommandsTest` cases pass.

---

## Phase 4: `give` and `put` with Quantities, `all`, Optional Prepositions, and Finalization

### Objective & Scope

- **In Scope:**
  - Refactor `planGive` (`GiveAction`) and `planPut` (`PutAction`) in `KernelCommands.kt` to use `ItemSpec`, resolve
    items at tick-lane execution time (`validate` / `resolve`), support `<count>` and `all` / `all <keyword>`, and
    support optional `to` and `in`/`into`.
  - Cap `give` transfers to the receiver's remaining `carry_limit` (emitting `KernelMsg.GIVE_FULL` when capped) and cap
    `put` transfers to the container's remaining `capacity` and `maxWeight` (emitting `KernelMsg.PUT_FULL` or
    `KernelMsg.PUT_WONT_FIT` when capped).
  - Ensure `put` automatically excludes the target container (`item !== box`) when matching candidates.
  - Mark `### Quantities and all` in `docs/DESIGN_NEEDED.md` as completed (do not delete the entry).
  - Add the **M16** row to `docs/SPEC.md` `§7.4` and update `§4.9` and `docs/CHANGELOG.md`.
- **Out of Scope:**
  - Deleting the `DESIGN_NEEDED.md` entry (reserved for post-implementation maintainer review).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/KernelMessages.kt`
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt`
  - `core/src/test/kotlin/gups/core/world/ItemCommandsTest.kt`
  - `docs/SPEC.md`
  - `docs/CHANGELOG.md`
  - `docs/DESIGN_NEEDED.md`
- **Do Not Touch:**
  - `core/src/main/kotlin/gups/core/world/Items.kt`
  - `script/src/main/kotlin/gups/script/Ctx.kt`

### Specification & Decision Record Changes

Update `docs/SPEC.md`: - **`§4.9` (`Item and combat command details`):** - Update the `give` bullet: `- **give <what>
[to] <actor>:** gives one item, a count, or all matching items from the inventory, capping to what is available and what
the receiver can carry ("<Target> can't carry that much." when capped).` - Update the `put` bullet: `- **put <what>
[in|into] <container>:** the container is carried, or on the ground within get_lag reach, and open. Moves one item, a
count, or all matching items (excluding the container itself), capping to the container's remaining item capacity and
max_weight.` - **`§7.4` (`Implementation Milestones`):** - Add row: `| **M16** | Item quantities, pluralization, and
all: item(plural=), stack count display, no_all tag, count on move_item/give/destroy (replacing split), and quantities,
all, optional prepositions, and carry/capacity capping on get, drop, give, and put (§3.3, §4.2, §4.5, §4.9). |`

Update `docs/DESIGN_NEEDED.md`: - Mark `### Quantities and all` as `### Quantities and all (COMPLETED — M16)` (keep the
entry text intact).

### Implementation Blueprint

- **Algorithmic Steps & Invariants (`KernelCommands.kt`):**
  1. **Parsing `give <what> [to] <target>` (`planGive` and `GiveAction`):**
     - Update `give` help string usage to `"Usage: give <item|count item|all [item]> [to] <target>"`.
     - If `c.args` contains `\s+to\s+` (case-insensitive): split on the last `\s+to\s+` into `whatStr` and `targetStr`.
     - Otherwise: split on whitespace; if fewer than 2 words, tell `KernelMsg.GIVE_USAGE` and return `null`; `targetStr`
       is the last word and `whatStr` is the preceding words joined by `" "`.
     - Parse `val spec = parseItemSpec(whatStr) ?: return null.also { w.tell(c.actor, KernelMsg.GIVE_USAGE) }`.
     - Return `GiveAction(spec, targetStr)`.
  2. **Validating and Resolving `GiveAction`:**
     - In `validate(w, actor)`:
       - Find `val target = w.findActor(actor, targetWord) ?: return w.msg(KernelMsg.NOT_HERE)`.
       - If `target === actor`, return `w.msg(KernelMsg.GIVE_ALREADY)`.
       - `val matches = matchItems(actor.inventory, spec)`
       - If `matches.isEmpty()`, return `w.msg(KernelMsg.NOT_CARRYING)`.
       - Check if `target` can carry at least 1 unit of at least one matched item:
         - `val first = matches.first().first`
         - `val unitW = if (first.proto.stackable) first.proto.weight else first.weight`
         - If `matches.none { (i, _) -> val uw = if (i.proto.stackable) i.proto.weight else i.weight;
           target.carriedWeight() + uw <= w.rules.carryLimit(RulesContext(w, target, item = i)) }`: return
           `w.msg(KernelMsg.GIVE_FULL, "target" to target.name)`.
       - Return `null`.
     - In `resolve(w, actor)`:
       - Re-resolve `val target = w.findActor(actor, targetWord) ?: return 0`.
       - Track `var cappedByWeight = false`.
       - For each `(item, wantedCount)` in `matchItems(actor.inventory, spec)`:
         - If `item.location != ItemLocation.Carried(actor.id)` continue.
         - Compute `remWeight = w.rules.carryLimit(RulesContext(w, target, item = item)) - target.carriedWeight()`.
         - For stackable `item`:
           - `val fitCount = if (item.proto.weight <= 0) wantedCount else (remWeight /
             item.proto.weight).coerceAtLeast(0)`
           - If `fitCount <= 0`: `cappedByWeight = true`; continue.
           - If `fitCount < wantedCount`: `cappedByWeight = true`.
           - `val actualCount = minOf(wantedCount, fitCount)`
         - For non-stackable `item` (`actualCount = 1`):
           - If `item.weight > remWeight`: `cappedByWeight = true`; continue.
         - `val moved = w.moveItem(item, ItemLocation.Carried(target.id), actualCount)`
         - `val desc = moved.describe()`
         - Send `GIVE_SELF`, `GIVE_TARGET`, and `GIVE_ROOM` using `"item" to desc`.
         - Dispatch `ON_GIVE` with `moved` and `target`.
         - If `moved.id in w.entities`, call `w.stack(moved)`.
       - If `cappedByWeight`, `w.tell(actor, KernelMsg.GIVE_FULL, "target" to target.name)`.
       - Return `0`.
  3. **Parsing `put <what> [in|into] <container>` (`planPut` and `PutAction`):**
     - Update `put` help string usage to `"Usage: put <item|count item|all [item]> [in|into] <container>"`.
     - If `c.args` contains `\s+(in|into)\s+` (case-insensitive): split on the last `\s+(in|into)\s+` into `whatStr` and
       `boxStr`.
     - Otherwise: split on whitespace; if fewer than 2 words, tell `KernelMsg.PUT_USAGE` and return `null`; `boxStr` is
       the last word and `whatStr` is the preceding words joined by `" "`.
     - Parse `val spec = parseItemSpec(whatStr) ?: return null.also { w.tell(c.actor, KernelMsg.PUT_USAGE) }`.
     - Return `PutAction(spec, boxStr)`.
  4. **Validating and Resolving `PutAction`:**
     - Helper `canMergeInContainer(item: Item, box: Item): Boolean`: returns `true` if `item.proto.stackable &&
       item.timers.isEmpty() && item.nameOverride == null && box.contents.any { it.protoLabel == item.protoLabel &&
       it.timers.isEmpty() && it.nameOverride == null && it.decayAt == item.decayAt && it.customAttributes ==
       item.customAttributes }`.
     - In `validate(w, actor)`:
       - Find `val box = w.findItem(actor, boxWord, inventory = true, ground = true) ?: return
         w.msg(KernelMsg.DONT_SEE)`.
       - Check `val matches = matchItems(actor.inventory, spec, exclude = box)`.
       - If `matches.isEmpty()`:
         - If `!spec.all && actor.inventory.any { it === box && (spec.keyword == null || it.matches(spec.keyword)) }`:
           return `w.msg(KernelMsg.PUT_ITSELF)` (preserves `"put chest in chest"` -> `"You can't put something inside
           itself."`).
         - Return `w.msg(KernelMsg.NOT_CARRYING)`.
       - Validate `box`:
         - `val state = box.container ?: return w.msg(KernelMsg.NOT_CONTAINER, "item" to box.name)`
         - `if (!w.isPresent(box, w.roomOf(actor))) return w.msg(KernelMsg.DONT_SEE)`
         - `w.reach(actor, box)?.let { return it }`
         - `if (state.state != DoorState.OPEN) return w.msg(KernelMsg.PUT_CLOSED, "container" to box.name)`
       - Check capacity and weight for at least 1 unit of the first matched item:
         - If `matches.none { (i, _) -> box.contents.size < state.capacity || canMergeInContainer(i, box) }`: return
           `w.msg(KernelMsg.PUT_FULL, "container" to box.name)`.
         - If `matches.none { (i, _) -> val uw = if (i.proto.stackable) i.proto.weight else i.weight; box.contents.sumOf
           { it.weight } + uw <= state.maxWeight }`: return `w.msg(KernelMsg.PUT_WONT_FIT, "item" to
           matches.first().first.name, "container" to box.name)`.
       - Return `null`.
     - In `resolve(w, actor)`:
       - Find `val box = w.findItem(actor, boxWord, inventory = true, ground = true) ?: return 0`.
       - `val state = box.container ?: return 0`
       - Track `var cappedFull = false` and `var cappedWeightItem: String? = null`.
       - For each `(item, wantedCount)` in `matchItems(actor.inventory, spec, exclude = box)`:
         - If `item.location != ItemLocation.Carried(actor.id)` continue.
         - Check item slot capacity: if `box.contents.size >= state.capacity && !canMergeInContainer(item, box)`:
           `cappedFull = true`; continue.
         - Check weight capacity: `val remWeight = state.maxWeight - box.contents.sumOf { it.weight }`.
         - For stackable `item`:
           - `val fitCount = if (item.proto.weight <= 0) wantedCount else (remWeight /
             item.proto.weight).coerceAtLeast(0)`
           - If `fitCount <= 0`: `if (cappedWeightItem == null) cappedWeightItem = item.name`; continue.
           - If `fitCount < wantedCount`: `if (cappedWeightItem == null) cappedWeightItem = item.name`.
           - `val actualCount = minOf(wantedCount, fitCount)`
         - For non-stackable `item` (`actualCount = 1`):
           - If `item.weight > remWeight`: `if (cappedWeightItem == null) cappedWeightItem = item.name`; continue.
         - `val moved = w.moveItem(item, ItemLocation.Contained(box.id), actualCount)`
         - `val desc = moved.describe()`
         - Send `PUT_SELF` and `PUT_ROOM` using `"item" to desc` and `"container" to box.name`.
         - `w.stack(moved)`
       - After the loop:
         - If `cappedWeightItem != null`, `w.tell(actor, KernelMsg.PUT_WONT_FIT, "item" to cappedWeightItem, "container"
           to box.name)`.
         - Else if `cappedFull`, `w.tell(actor, KernelMsg.PUT_FULL, "container" to box.name)`.
       - Return `0`.

### Test & Documentation Requirements

- In `core/src/test/kotlin/gups/core/world/ItemCommandsTest.kt`, add tests covering:
  - `give 5 coins to bob` and `give 5 coins bob` (splitting a stack, formatting `"You give 5 gold coins to Bob."`, and
    merging into Bob's stack).
  - `give all bob` and `give all coins bob` (skipping keyring-only keys on bare `give all`).
  - `give 50000 coins bob` capping to Bob's remaining carry limit and emitting `"Bob can't carry that much."`.
  - `put 3 coins in chest`, `put 3 coins into chest`, and `put 3 coins chest` (without `in`/`into`).
  - `put all in chest` and `put all chest` (automatically excluding `chest` itself and `no_all` items).
  - `put 50000 coins chest` capping to `chest`'s `maxWeight` (moving the coins that fit and emitting `"A gold coin won't
    fit in a chest."`) and `put all` capping to `chest`'s `capacity` (emitting `"A chest is full."`).
  - Queued commands in the same tick batch (`get all chest; drop 2 coins; put all chest`) resolving dynamically at
    execution time.

### Exit Conditions

- [] `docs/DESIGN_NEEDED.md` has `### Quantities and all (COMPLETED — M16)`.
- [] `docs/SPEC.md` `§7.4` includes the **M16** row.
- [] `grep -rnE "§|\bD-[0-9]{3}\b|(//|\*).*\bSPEC\b" */src/main/` returns `0` matches.
- [] `./gradlew markdownFormat && ./gradlew check` (with `BypassSandbox: true`) exits `0` with zero warnings and all
  tests passing.
